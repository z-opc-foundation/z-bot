package com.zifang.z.bot.delegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 委托现场台账（对齐 hermes {@code tools/delegation_live_log.py}：
 * {@code LIVE_RETENTION_DAYS = 7} @ :44、{@code LiveTranscriptWriter} @ :112、
 * {@code prune_stale_live_dirs} @ :404）。
 *
 * <p>每条委托一个目录：{@code <root>/<id>/state.json} + {@code <root>/<id>/events.log}。
 * <b>建账就落盘</b>（子代理还没跑完目录就已经在盘上），所以委托方进程被打死之后，
 * 台账仍然读得回来；重启时非终态且心跳超期的现场被 {@link #adoptOrphans(long, long)}
 * 判成 {@link DelegateState#UNKNOWN}，超期的终态现场被 {@link #pruneStale(long, long)} 回收。</p>
 *
 * <p>抄她的四条设计约束：
 * <ul>
 *   <li><b>绝不把异常抛回 agent 循环</b>：写失败翻转 {@code ok=false}，降级成一条 debug 日志；</li>
 *   <li><b>能扛子进程崩溃</b>：一次写一次开合，不留长句柄；{@code state.json} 走 tmp + 原子 rename，
 *       半截文件不会取代完整现场；</li>
 *   <li><b>只旁路不改正文</b>；</li>
 *   <li><b>不留裸密钥</b>：落盘前过一遍 {@link #redact(String)}（她那边是
 *       {@code _redact}，理由一样 —— 这些文件是给人 {@code tail -f} 的）。</li>
 * </ul>
 *
 * <p>跟她不同、且故意更强的两条：
 * <ul>
 *   <li>她按目录 mtime 回收，这里按<b>盘上时间戳字段</b>回收（mtime 会被 {@code touch}、
 *       备份、同步盘改写，现场年龄不该由文件系统说了算）；</li>
 *   <li>她只判年龄，这里<b>非终态一律不删</b> —— 在飞委托的现场是唯一能证明"当时真的跑过"的东西。</li>
 * </ul>
 * 两条都有正反两问的断言（过期删 / 未过期不删 / 在飞不删）。</p>
 */
public final class DelegationLedger {

    private static final Logger LOG = LoggerFactory.getLogger(DelegationLedger.class);
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 现场保留窗口（天）—— 与 hermes {@code delegation_live_log.py:44} 同值。 */
    public static final int LIVE_RETENTION_DAYS = 7;

    /** {@link #LIVE_RETENTION_DAYS} 的毫秒形式，回收默认值。 */
    public static final long LIVE_RETENTION_MILLIS = LIVE_RETENTION_DAYS * 24L * 60L * 60L * 1000L;

    /**
     * 非终态现场多久没有新事件就判孤儿。她的对应物是
     * {@code _HEARTBEAT_STALE_CYCLES_IDLE = 15}（15 × 30s = 450s）；
     * z-bot 侧没有 30s 心跳线程，按"一次委托不会真的跑半小时不产生事件"取 30 分钟。
     */
    public static final long ORPHAN_STALE_MILLIS = 30L * 60L * 1000L;

    /** 回复正文落盘上限（字符）；超出部分记省略数，不静默截断。 */
    public static final int REPLY_ON_DISK_CAP = 4000;

    private static final Pattern ID_OK = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SECRET_IN_TEXT = Pattern.compile("(?i)(api[_-]?key|apikey|token|secret|password|authorization)\\s*[=:]\\s*\\S+");
    private static final Pattern KEYISH = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b");

    private final File root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object ioLock = new Object();
    private volatile boolean ok = true;
    private volatile String lastError = "";
    private final AtomicIntegerHolder writes = new AtomicIntegerHolder();

    public DelegationLedger(File root) {
        this.root = root;
    }

    /** 台账根目录（null ⇒ 本台账不工作，所有写都是 no-op，调用方从 {@link #enabled()} 判）。 */
    public File root() {
        return root;
    }

    public boolean enabled() {
        return root != null;
    }

    /** 写侧是否还健康（对齐她 "first failure disables the writer"）。 */
    public boolean healthy() {
        return ok;
    }

    public String lastError() {
        return lastError;
    }

    /** 成功落盘的 {@code state.json} 次数（E2E 用它证明确实写了盘，而不是只改了内存）。 */
    public int saveCount() {
        return writes.get();
    }

    // ================= 目录 / id =================

    static String checkedId(String id) {
        String s = id == null ? "" : id.trim();
        if (!ID_OK.matcher(s).matches()) {
            throw new IllegalArgumentException("非法委托 id（不可作为目录名）: " + id);
        }
        return s;
    }

    public File dirOf(String id) {
        return new File(root, checkedId(id));
    }

    private File stateFile(String id) {
        return new File(dirOf(id), "state.json");
    }

    private File eventsFile(String id) {
        return new File(dirOf(id), "events.log");
    }

    public boolean exists(String id) {
        return enabled() && stateFile(id).isFile();
    }

    // ================= 建账 / 读回 =================

    /**
     * 建账：目录 + header + 首条 {@code state.json} 立刻落盘（此刻 state=QUEUED、
     * delivery=PENDING、attempts=0）。<b>先落盘再执行</b>是她
     * {@code LiveTranscriptWriter} 的"pre-created with a header at dispatch time"，
     * 也是"进程死了还在盘上"成立的前提。
     */
    public Entry create(String id, String task, int depth, String label, File childSessionDir) {
        long now = System.currentTimeMillis();
        Entry e = new Entry(checkedId(id));
        e.task = task == null ? "" : task;
        e.label = label == null ? "" : label;
        e.depth = depth;
        e.childSession = childSessionDir == null ? "" : childSessionDir.getAbsolutePath();
        e.ownerPid = currentPid();
        e.state = DelegateState.QUEUED;
        e.delivery = DeliveryState.PENDING;
        e.dispatchedAt = now;
        e.updatedAt = now;
        if (!enabled()) {
            return e;
        }
        synchronized (ioLock) {
            if (!ok) {
                return e;
            }
            try {
                File d = dirOf(e.id);
                if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                    throw new IOException("mkdirs 失败: " + d);
                }
                writeHeaderOnce(d, e);
            } catch (Exception ex) {
                disable("create", ex);
                return e;
            }
        }
        save(e);
        appendEvent(e.id, DelegateEvent.DISPATCH, "task=" + oneLine(e.task, 200)
                + " depth=" + depth + " pid=" + e.ownerPid);
        return e;
    }

    /** 读回一条现场；盘上没有 / 解析失败 ⇒ null（并留一条 debug）。 */
    public Entry load(String id) {
        if (!enabled()) {
            return null;
        }
        File f = stateFile(id.trim());
        if (!f.isFile()) {
            return null;
        }
        try {
            JsonNode n = mapper.readTree(f);
            return Entry.fromJson(n);
        } catch (Exception ex) {
            LOG.debug("[delegate-ledger] state.json 解析失败 {}: {}", f, ex.getMessage());
            return null;
        }
    }

    /** 全量现场，按建账时间升序（读不回本目录之外）。 */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        if (!enabled() || !root.isDirectory()) {
            return out;
        }
        File[] kids = root.listFiles();
        if (kids == null) {
            return out;
        }
        for (File k : kids) {
            if (!k.isDirectory()) {
                continue;
            }
            Entry e = load(k.getName());
            if (e != null) {
                out.add(e);
            }
        }
        Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                int c = Long.compare(a.dispatchedAt, b.dispatchedAt);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /** 覆写 {@code state.json}（tmp + 原子 rename）。失败 ⇒ false，且不抛。 */
    public boolean save(Entry e) {
        if (!enabled() || e == null) {
            return false;
        }
        synchronized (ioLock) {
            if (!ok) {
                return false;
            }
            File d = dirOf(e.id);
            File tmp = new File(d, "state.json.tmp");
            File target = new File(d, "state.json");
            Writer w = null;
            try {
                if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                    throw new IOException("mkdirs 失败: " + d);
                }
                w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
                mapper.writerWithDefaultPrettyPrinter().writeValue(w, e.toMap());
                w.close();
                w = null;
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException amn) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                writes.increment();
                return true;
            } catch (Exception ex) {
                disable("save", ex);
                return false;
            } finally {
                if (w != null) {
                    try {
                        w.close();
                    } catch (IOException ignored) {
                        // best-effort
                    }
                }
                if (tmp.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        }
    }

    /**
     * 追加一行事件（一次写一次开合 + flush，不留长句柄）。
     * 格式 {@code <epochMillis>|<wireName>|<单行详情>}。
     */
    public boolean appendEvent(String id, DelegateEvent event, String detail) {
        if (!enabled() || event == null) {
            return false;
        }
        File d = dirOf(id);
        Writer w = null;
        try {
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                throw new IOException("mkdirs 失败: " + d);
            }
            w = new OutputStreamWriter(new FileOutputStream(eventsFile(id), true), UTF8);
            w.write(System.currentTimeMillis() + "|" + event.wireName() + "|"
                    + redact(oneLine(detail == null ? "" : detail, 400)) + "\n");
            w.flush();
            return true;
        } catch (Exception ex) {
            disable("appendEvent", ex);
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    /** 读事件流尾部（不存在 ⇒ 空表）。 */
    public List<String> tail(String id, int maxLines) {
        List<String> out = new ArrayList<String>();
        if (!enabled()) {
            return out;
        }
        File f = eventsFile(id);
        if (!f.isFile()) {
            return out;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
                if (out.size() > maxLines * 4) {
                    break; // 防御：不把整本日志吸进内存
                }
            }
        } catch (IOException ex) {
            LOG.debug("[delegate-ledger] tail 失败 {}: {}", f, ex.getMessage());
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
        int from = Math.max(0, out.size() - Math.max(0, maxLines));
        return new ArrayList<String>(out.subList(from, out.size()));
    }

    // ================= 状态推进 =================

    /**
     * 走生命周期轴一条边并落盘。非法迁移由 {@link DelegateTransitions#next} 大声抛，
     * <b>不会</b>先写盘再抛（盘上留一条推进过一半的现场比抛异常更难查）。
     *
     * <p><b>先与盘上事实对齐再判迁移</b>：一条现场可以被多个线程握着（{@code /stop} 走的是
     * {@link #load(String)} 出来的副本，子代理那边握的是原件）。不 reconcile 的话就是
     * 陈旧副本后写覆盖先落的终态 —— 实测过的真缺陷（EVIDENCE §9 P27-D3）：
     * 用户按了停止，盘上最后一条却写着 {@code DONE}。</p>
     */
    public Entry advance(Entry e, DelegateEvent event, String detail) {
        if (e == null) {
            throw new IllegalArgumentException("advance: entry 不能为 null");
        }
        reconcileLifeFromDisk(e, event);
        DelegateState next = DelegateTransitions.next(e.state, event);
        long now = System.currentTimeMillis();
        e.state = next;
        e.updatedAt = now;
        if (event == DelegateEvent.TASK_SPAWNED && e.startedAt == 0L) {
            e.startedAt = now;
        }
        if (next.terminal() && event != DelegateEvent.TASK_PROGRESS) {
            e.finishedAt = now;
        }
        save(e);
        appendEvent(e.id, event, detail);
        return e;
    }

    /** 走投递轴一条边并落盘；非法迁移大声抛。 */
    public Entry advanceDelivery(Entry e, DelegateEvent event, String detail) {
        if (e == null) {
            throw new IllegalArgumentException("advanceDelivery: entry 不能为 null");
        }
        reconcileDeliveryFromDisk(e, event);
        DeliveryState next = DelegateTransitions.nextDelivery(e.delivery, event);
        e.delivery = next;
        e.updatedAt = System.currentTimeMillis();
        save(e);
        appendEvent(e.id, event, detail);
        return e;
    }

    /** 盘上已是终态 ⇒ 拒绝被陈旧副本改写；盘上更靠前 ⇒ 采纳盘上事实再走这一步。 */
    private void reconcileLifeFromDisk(Entry e, DelegateEvent event) {
        Entry disk = load(e.id);
        if (disk == null || disk.state == e.state) {
            return;
        }
        if (disk.state.terminal()) {
            throw new DelegateTransitions.IllegalTransitionException(
                    "lifecycle(盘上已是 " + disk.state + "，握着的副本是 " + e.state + ")", disk.state, event);
        }
        e.state = disk.state;
        e.updatedAt = disk.updatedAt;
        e.finishedAt = disk.finishedAt;
        e.startedAt = disk.startedAt;
    }

    private void reconcileDeliveryFromDisk(Entry e, DelegateEvent event) {
        Entry disk = load(e.id);
        if (disk == null || disk.delivery == e.delivery) {
            return;
        }
        if (disk.delivery.terminal()) {
            throw new DelegateTransitions.IllegalTransitionException(
                    "delivery(盘上已是 " + disk.delivery + "，握着的副本是 " + e.delivery + ")",
                    disk.delivery, event);
        }
        e.delivery = disk.delivery;
        e.deliveryAttempts = disk.deliveryAttempts;
        e.claimToken = disk.claimToken;
        e.claimExpiresAt = disk.claimExpiresAt;
        e.updatedAt = disk.updatedAt;
    }

    // ================= 回收 / 孤儿 =================

    /**
     * 认领孤儿现场：非终态且 {@code updatedAt} 比 {@code now - staleOlderThanMillis} 还老
     * ⇒ 走 {@link DelegateEvent#ORPHAN_ADOPTED} 判成 {@link DelegateState#UNKNOWN}。
     *
     * <p>这是"委托方进程死了"的判词入口：现场本来就为这一刻准备着。
     * 未超期的非终态（真在飞的那些）一条都不动 —— 正反两问都有断言。</p>
     *
     * @return 被认领的现场条数
     */
    public int adoptOrphans(long staleOlderThanMillis, long now) {
        int n = 0;
        if (!enabled()) {
            return 0;
        }
        for (Entry e : list()) {
            if (e.state.terminal()) {
                continue;
            }
            if (e.updatedAt + staleOlderThanMillis > now) {
                continue;
            }
            advance(e, DelegateEvent.ORPHAN_ADOPTED, "无新事件超过 " + staleOlderThanMillis + "ms");
            n++;
        }
        return n;
    }

    /** 用默认窗口回收。 */
    public int pruneStale(long now) {
        return pruneStale(LIVE_RETENTION_MILLIS, now);
    }

    /**
     * 回收超期的<b>终态</b>现场目录。
     *
     * <p>三道闸，按她的语义逐条收紧：
     * ① 只碰本台账目录树下的直接子目录；
     * ② 只删<b>终态</b>（{@link DelegateState#terminal()}）—— 在飞委托的现场多老都不删；
     * ③ 年龄用<b>盘上字段</b>（{@code finishedAt}，为 0 时退回 {@code updatedAt}）而不是 mtime。</p>
     *
     * @return 被删掉的现场条数（正向证据：调用方能拿返回值对账）
     */
    public int pruneStale(long maxAgeMillis, long now) {
        int removed = 0;
        if (!enabled() || !root.isDirectory()) {
            return 0;
        }
        for (Entry e : list()) {
            if (!e.state.terminal()) {
                continue;
            }
            long ageBase = e.finishedAt > 0L ? e.finishedAt : e.updatedAt;
            if (ageBase + maxAgeMillis > now) {
                continue;
            }
            if (deleteRecursively(dirOf(e.id))) {
                removed++;
            } else {
                LOG.debug("[delegate-ledger] 现场删除失败，留着下次再收: {}", e.id);
            }
        }
        return removed;
    }

    /** 孤儿 + 回收一步走完（默认常量）。 */
    public String sweepDefault(long now) {
        int adopted = adoptOrphans(ORPHAN_STALE_MILLIS, now);
        int removed = pruneStale(LIVE_RETENTION_MILLIS, now);
        return "adopted=" + adopted + " pruned=" + removed;
    }

    // ================= 内部工具 =================

    private void writeHeaderOnce(File d, Entry e) throws IOException {
        File f = new File(d, "events.log");
        if (f.isFile()) {
            return;
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f, true), UTF8);
        try {
            w.write("=== z-bot 委托现场（append-only；子代理还在跑时可以直接 tail -f）===\n");
            w.write("delegation: " + e.id + "  depth=" + e.depth + "  pid=" + e.ownerPid + "\n");
            w.write("goal: " + oneLine(redact(e.task), REPLY_ON_DISK_CAP) + "\n");
            w.write("started: " + new java.util.Date(e.dispatchedAt) + "\n");
        } finally {
            w.close();
        }
    }

    private void disable(String where, Exception ex) {
        ok = false;
        lastError = where + ": " + ex;
        LOG.debug("[delegate-ledger] {} 失败，台账降级为 no-op: {}", where, ex.toString());
    }

    private boolean deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (!deleteRecursively(k)) {
                    return false;
                }
            }
        }
        return f.delete();
    }

    /** 落盘前遮密钥（她的 {@code _redact} 同位）。 */
    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = KEYISH.matcher(text).replaceAll("sk-***REDACTED***");
        s = SECRET_IN_TEXT.matcher(s).replaceAll("$1=***REDACTED***");
        return s;
    }

    /** 压成一行 + 截断并写明省略了多少（不静默丢字）。 */
    static String oneLine(String text, int limit) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (s.length() > limit) {
            int omitted = s.length() - limit;
            s = s.substring(0, limit) + " …(+" + omitted + " chars)";
        }
        return s;
    }

    /** 当前进程号（Java 8 可用：RuntimeMXBean 名字形如 {@code 1234@host}）。 */
    public static long currentPid() {
        String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        try {
            return Long.parseLong(at > 0 ? name.substring(0, at) : name);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** 去掉 {@code /} 与空白，做成能当目录名片段的东西（抄她 {@code slug()} 的语义）。 */
    static String slug(String label) {
        return label == null ? "" : label.replaceAll("[^0-9A-Za-z_-]", "-");
    }

    // ================= 一条现场 =================

    /** 一条委托现场。字段全部随 {@code state.json} 落盘。 */
    public static final class Entry {
        public final String id;
        public String task = "";
        public String label = "";
        public int depth;
        public String childSession = "";
        public long ownerPid = -1L;
        public DelegateState state = DelegateState.QUEUED;
        public DeliveryState delivery = DeliveryState.PENDING;
        public int deliveryAttempts;
        public String claimToken = "";
        public long claimExpiresAt;
        public long dispatchedAt;
        public long startedAt;
        public long finishedAt;
        public long updatedAt;
        public String reply = "";
        public String error = "";

        Entry(String id) {
            this.id = id;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("task", redact(oneLine(task, REPLY_ON_DISK_CAP)));
            m.put("label", redact(oneLine(label, 120)));
            m.put("depth", depth);
            m.put("child_session", childSession);
            m.put("owner_pid", ownerPid);
            m.put("state", state.name());
            m.put("delivery_state", delivery.name());
            m.put("delivery_attempts", deliveryAttempts);
            m.put("claim_token", claimToken);
            m.put("claim_expires_at", claimExpiresAt);
            m.put("dispatched_at", dispatchedAt);
            m.put("started_at", startedAt);
            m.put("finished_at", finishedAt);
            m.put("updated_at", updatedAt);
            m.put("reply", redact(oneLine(reply, REPLY_ON_DISK_CAP)));
            m.put("error", redact(oneLine(error, 400)));
            return m;
        }

        static Entry fromJson(JsonNode n) {
            Entry e = new Entry(text(n, "id"));
            e.task = text(n, "task");
            e.label = text(n, "label");
            e.depth = n.path("depth").asInt(0);
            e.childSession = text(n, "child_session");
            e.ownerPid = n.path("owner_pid").asLong(-1L);
            e.state = DelegateState.fromLegacy(text(n, "state"));
            e.delivery = DeliveryState.valueOf(text(n, "delivery_state", "PENDING"));
            e.deliveryAttempts = n.path("delivery_attempts").asInt(0);
            e.claimToken = text(n, "claim_token");
            e.claimExpiresAt = n.path("claim_expires_at").asLong(0L);
            e.dispatchedAt = n.path("dispatched_at").asLong(0L);
            e.startedAt = n.path("started_at").asLong(0L);
            e.finishedAt = n.path("finished_at").asLong(0L);
            e.updatedAt = n.path("updated_at").asLong(0L);
            e.reply = text(n, "reply");
            e.error = text(n, "error");
            return e;
        }

        private static String text(JsonNode n, String key) {
            return text(n, key, "");
        }

        private static String text(JsonNode n, String key, String dflt) {
            JsonNode v = n.path(key);
            return v.isTextual() ? v.asText() : dflt;
        }

        /** 现场年龄（以完成时刻为准，没完成就看最后一次更新）。 */
        public long ageAt(long now) {
            long base = finishedAt > 0L ? finishedAt : updatedAt;
            return now - base;
        }

        @Override
        public String toString() {
            return id + " " + state + " delivery=" + delivery + "(" + deliveryAttempts + ")";
        }
    }

    /** 一个够用的自增计数（避免为一个字段再引一个 import 行）。 */
    private static final class AtomicIntegerHolder {
        private final java.util.concurrent.atomic.AtomicInteger n =
                new java.util.concurrent.atomic.AtomicInteger();

        int get() {
            return n.get();
        }

        void increment() {
            n.incrementAndGet();
        }
    }

    /** 大小写无关的 locale 稳定化入口（测试里会用到 {@code Locale.ROOT} 同一条语义）。 */
    static String upperRoot(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
