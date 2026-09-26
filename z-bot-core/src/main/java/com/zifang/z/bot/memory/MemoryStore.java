package com.zifang.z.bot.memory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 本地长期记忆三层（对齐 hermes memories）：
 * <ul>
 *   <li>{@code MEMORY.md} — agent 主动积累的长期记忆；</li>
 *   <li>{@code USER.md} — 用户画像/偏好；</li>
 *   <li>{@code SOUL.md} — 人格文件，缺省生成、可手编，注入 system prompt 头部。</li>
 * </ul>
 *
 * <p>写入走 {@code <file>.lock} 文件锁 + 临时文件原子替换，多进程同写不撕裂。</p>
 *
 * <p><b>P24 起这一层有契约</b>（此前只有"能写进去"这一条隐式保证）。三件事每次写都成立：</p>
 * <ol>
 *   <li><b>写入门禁</b>（{@link MemoryWriteGate}）：内容扫描命中注入/外传特征 ⇒ 拒写并回报位点；
 *       改/删已有条目必须给 {@code old_text}，缺了大声报错，<b>不降级成新建</b>；
 *       写后整份不得超该份预算。</li>
 *   <li><b>漂移与取证</b>（{@link MemoryDriftGuard}）：条目级写入前先判盘上内容是否与本层解析器往返，
 *       不往返 ⇒ 先快照 {@code <file>.bak.<毫秒>} 再拒绝；落盘前先留原字节，
 *       落盘后<b>回读对账</b>，半写/写失败 ⇒ 用快照还原成原字节并把 {@code bakPath} 报出来。</li>
 *   <li><b>批量全有或全无</b>（{@link #applyBatch}）：整组先在内存里演算，任一条不合格
 *       整组一个字节都不落，错误里指名第几条。</li>
 * </ol>
 *
 * <p>历史遗留的 {@code appendMemory/appendUser/rewriteMemory/rewriteUser} 保留原签名，
 * 但<b>已经改道走上面这条门禁</b>（工单 §1#1 的要求就是「append / rewrite 两类通道要走真校验」）；
 * 它们的返回类型是 {@code void}，所以拿不到 {@link MemoryReceipt}，被拒时抛
 * {@link MemoryWriteRejectedException}（{@code IOException} 子类，调用方的
 * {@code throws IOException} 签名不必改）。</p>
 */
public final class MemoryStore {

    private static final String DEFAULT_SOUL = "# SOUL\n\n"
            + "你是 z-bot，一个务实、直接的本地 Agent。\n"
            + "- 说人话，先给结论再给依据；\n"
            + "- 能用工具解决的绝不空谈，做完为止；\n"
            + "- 对不可逆操作保持谨慎，拿不准就先问。\n";

    /**
     * 落盘末端。生产实现是"临时文件 + 原子改名"；这个缝的存在理由只有一个：
     * <b>半写与写失败这两条路径必须在测试和 E2E 里真能发生</b>，而真实文件系统
     * 不肯配合我们演。注错之后走的仍然是同一套快照 / 回读对账 / 还原代码。
     */
    public interface WriteSink {
        void write(File target, byte[] payload) throws IOException;
    }

    /** 生产通道：委托 {@link MemoryDriftGuard#atomicReplace}。 */
    public static final WriteSink FILE_SINK = new WriteSink() {
        @Override
        public void write(File target, byte[] payload) throws IOException {
            MemoryDriftGuard.atomicReplace(target, payload);
        }
    };

    /** 锁内执行体（锁的粒度 = 一次完整读-改-写）。 */
    private interface IoFn<T> {
        T apply() throws IOException;
    }

    private final File dir;
    private final int memoryCharLimit;
    private final int userCharLimit;
    private final WriteSink sink;

    public MemoryStore(File dir) {
        this(dir, MemorySection.MEMORY.charLimit(), MemorySection.USER.charLimit());
    }

    public MemoryStore(File dir, int memoryCharLimit, int userCharLimit) {
        this(dir, memoryCharLimit, userCharLimit, FILE_SINK);
    }

    /**
     * 测试/E2E 注入：替换落盘末端（见 {@link WriteSink}）。
     * 前两档预算 {@code <=0} 直接拒 —— 静默退化成"无预算"就是把整份文件写成无限长。
     */
    public MemoryStore(File dir, int memoryCharLimit, int userCharLimit, WriteSink sink) {
        this.dir = dir;
        if (memoryCharLimit <= 0 || userCharLimit <= 0) {
            throw new IllegalArgumentException("记忆预算必须为正：memory=" + memoryCharLimit
                    + ", user=" + userCharLimit);
        }
        this.memoryCharLimit = memoryCharLimit;
        this.userCharLimit = userCharLimit;
        this.sink = sink == null ? FILE_SINK : sink;
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("记忆目录不可创建: " + dir);
        }
    }

    public File getDir() {
        return dir;
    }

    /** 生产写盘通道（供 {@link WriteSink} 注入点做对照）。 */
    public WriteSink sink() {
        return sink;
    }

    // ===== 读 =====

    public File fileOf(MemorySection section) {
        return new File(dir, section.fileName());
    }

    /** 未 trim 的盘上原文（对账字节用）。文件不在 ⇒ ""。 */
    public String rawOf(MemorySection section) {
        return readFile(fileOf(section));
    }

    public String readMemory() {
        return trimOf(MemorySection.MEMORY);
    }

    public String readUser() {
        return trimOf(MemorySection.USER);
    }

    public String readSoul() {
        return trimOf(MemorySection.SOUL);
    }

    /** 整份正文（trim 后）；三件套统一入口。 */
    public String readPage(MemorySection section) {
        return trimOf(section);
    }

    /**
     * 三份都算上了才算"有内容"？—— 不算。空判定只看可写记忆两份：
     * {@code SOUL.md} 是 {@link #ensureSoul()} 人人都会建出来的骨架文件，
     * 把它算进"非空"会让"这台机器还没记住任何东西"这个状态永远测不出来。
     */
    public boolean isEmpty() {
        return readMemory().isEmpty() && readUser().isEmpty();
    }

    /** 现有条目行（含时间戳前缀的整行）。整页形状（SOUL）⇒ 空表。 */
    public List<String> entries(MemorySection section) {
        if (section == null || !section.isEntryList()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (String line : MemoryDriftGuard.parse(rawOf(section))) {
            if (MemoryDriftGuard.isEntryLine(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** 条目正文（剥掉时间戳前缀）。 */
    public List<String> entryBodies(MemorySection section) {
        List<String> out = new ArrayList<String>();
        for (String line : entries(section)) {
            out.add(MemoryDriftGuard.entryBody(line));
        }
        return out;
    }

    /** 该份预算（条目清单按身份覆盖成实例档位，所以这里不走 {@link MemorySection#charLimit()}）。 */
    public int charLimit(MemorySection section) {
        if (section == MemorySection.MEMORY) {
            return memoryCharLimit;
        }
        if (section == MemorySection.USER) {
            return userCharLimit;
        }
        return section.charLimit();
    }

    /**
     * 该份现在占多少字符。
     *
     * <p>条目清单按<b>本层渲染口径</b>算（逐行 trim、行间单换行、无尾换行）—— 预算约束的就是
     * 这一份将要落盘的字节；整页散文按 trim 后的真实长度算，因为它的行数与空行由人决定，
     * 拿"丢掉空行"的口径去报一页的体量会少报。</p>
     */
    public int charCount(MemorySection section) {
        String raw = rawOf(section);
        if (!section.isEntryList()) {
            return raw.trim().length();
        }
        return MemoryDriftGuard.render(MemoryDriftGuard.parse(raw)).length();
    }

    public boolean soulExists() {
        return fileOf(MemorySection.SOUL).isFile();
    }

    // ===== 条目级写（走全部门禁）=====

    /** 追加一条新条目；同正文已存在 ⇒ 幂等跳过（回执里 {@code duplicateSkipped}）。 */
    public MemoryReceipt appendEntry(MemorySection section, String content) throws IOException {
        return applyOps(section, Collections.singletonList(MemoryOp.append(content)), false);
    }

    /**
     * 改一条已有条目。<b>没有 {@code oldText} 就是报错，不是新建</b>
     * （{@link MemoryWriteRejectedException#MISSING_OLD_TEXT}）。
     */
    public MemoryReceipt replaceEntry(MemorySection section, String oldText, String newContent)
            throws IOException {
        return applyOps(section,
                Collections.singletonList(MemoryOp.replace(oldText, newContent)), false);
    }

    /** 删一条已有条目；同 {@link #replaceEntry}，缺 {@code oldText} 必错。 */
    public MemoryReceipt removeEntry(MemorySection section, String oldText) throws IOException {
        return applyOps(section, Collections.singletonList(MemoryOp.remove(oldText)), false);
    }

    /**
     * 一组操作原子落盘：整组演算通过才写一次盘。任一条不合格 ——
     * 一个字节都不落，且异常里 {@code opIndex} 指明第几条（1 起算）。
     */
    public MemoryReceipt applyBatch(MemorySection section, List<MemoryOp> ops) throws IOException {
        return applyOps(section, ops, true);
    }

    private MemoryReceipt applyOps(final MemorySection section, final List<MemoryOp> ops,
                                   final boolean batch) throws IOException {
        if (section == null) {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                    "记忆写入缺 section：不接受按「记忆」猜是哪一份。");
        }
        if (!section.isEntryList()) {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                    section.fileName() + " 是整页散文，不接受条目级操作（append/replace/remove/batch）；"
                            + "要改它就整页重写。");
        }
        if (ops == null || ops.isEmpty()) {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.EMPTY_BATCH,
                    "一组操作都没有 —— 批量写入拒绝空转（空批量常被当成「成功」，其实什么都没做）。");
        }
        // 1) 碰盘之前先把整组内容扫一遍：一条投毒内容足以否掉整批（她的同款口径）。
        for (int i = 0; i < ops.size(); i++) {
            MemoryOp op = ops.get(i);
            if (op == null) {
                throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                        prefix(i, batch) + "操作是 null。", idx(i, batch), null);
            }
            if (op.kind() == MemoryOp.Kind.APPEND || op.kind() == MemoryOp.Kind.REPLACE) {
                MemoryWriteGate.gateContent(op.kind() == MemoryOp.Kind.APPEND
                        ? "追加内容" : "替换后的正文", op.content(), idx(i, batch));
            }
        }
        // 2) 锁内：读盘 → 漂移判定 → 内存演算 → 预算 → 一次落盘。
        return withLock(fileOf(section), new IoFn<MemoryReceipt>() {
            @Override
            public MemoryReceipt apply() throws IOException {
                return applyLocked(section, ops, batch, fileOf(section));
            }
        });
    }

    private MemoryReceipt applyLocked(MemorySection section, List<MemoryOp> ops, boolean batch,
                                      File target) throws IOException {
        String raw = readFile(target);
        MemoryDriftGuard.Drift drift = MemoryDriftGuard.detect(target, section);
        if (drift != null) {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.EXTERNAL_DRIFT,
                    drift.message(section.fileName()), 0, absolute(drift.backup()));
        }
        List<String> working = new ArrayList<String>(MemoryDriftGuard.parse(raw));
        int entriesBefore = working.size();
        int applied = 0;
        boolean duplicateSkipped = false;

        for (int i = 0; i < ops.size(); i++) {
            MemoryOp op = ops.get(i);
            int idx = idx(i, batch);
            switch (op.kind()) {
                case APPEND:
                    if (MemoryWriteGate.isDuplicate(working, op.content())) {
                        duplicateSkipped = true;
                        continue;
                    }
                    working.add(MemoryDriftGuard.entryLine(Instant.now().toString(), op.content()));
                    applied++;
                    break;
                case REPLACE: {
                    int at = MemoryWriteGate.locate(op.kind(), op.oldText(), working, idx);
                    working.set(at, MemoryDriftGuard.entryLine(Instant.now().toString(),
                            op.content()));
                    applied++;
                    break;
                }
                case REMOVE: {
                    int at = MemoryWriteGate.locate(op.kind(), op.oldText(), working, idx);
                    working.remove(at);
                    applied++;
                    break;
                }
                default:
                    throw new MemoryWriteRejectedException(
                            MemoryWriteRejectedException.BAD_OPERATION,
                            prefix(i, batch) + "不认识的操作 " + op.kind() + "。", idx, null);
            }
        }
        // 预算在最终状态上判，不在中途判 —— 她的同款：允许同一批里"先腾地方再写新的"。
        MemoryWriteGate.requireBudget(section, charLimit(section), working, 0);

        String rendered = MemoryDriftGuard.render(working);
        WriteOutcome w = writeLocked(target, rendered);
        return new MemoryReceipt(section, entriesBefore, working.size(), rendered.length(),
                charLimit(section), applied, duplicateSkipped, w.snapshotTaken, absolute(w.evidence));
    }

    // ===== 整页写（rewrite/forget 通道：不判条目往返，因为它就是漂移的补救出口）=====

    /**
     * 整页重写一份。内容仍要过内容扫描（清空除外），仍然"先快照后写、写完回读对账"。
     * 不做漂移判定，也不做条目形状判定：这是给人把脏文件刷回干净条目清单的那条出口。
     */
    public MemoryReceipt rewritePage(MemorySection section, final String content)
            throws IOException {
        if (section == null) {
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,
                    "整页重写缺 section。");
        }
        String body = content == null ? "" : content;
        if (!body.trim().isEmpty()) {
            MemoryWriteGate.gateContent(section.fileName() + " 的新正文", body, 0);
        }
        final File target = fileOf(section);
        final String payload = body;
        return withLock(target, new IoFn<MemoryReceipt>() {
            @Override
            public MemoryReceipt apply() throws IOException {
                int entriesBefore = MemoryDriftGuard.parse(readFile(target)).size();
                WriteOutcome w = writeLocked(target, payload);
                int entriesAfter = MemoryDriftGuard.parse(payload).size();
                return new MemoryReceipt(sectionOf(target), entriesBefore, entriesAfter,
                        payload.length(), charLimit(sectionOf(target)), 1, false,
                        w.snapshotTaken, absolute(w.evidence));
            }
        });
    }

    // ===== 兼容历史签名（内部已改道走门禁）=====

    /** 追加一条带时间戳的记忆行。等价于 {@code appendEntry(MEMORY, entry)}，只是不回传回执。 */
    public void appendMemory(String entry) throws IOException {
        appendEntry(MemorySection.MEMORY, entry);
    }

    public void appendUser(String entry) throws IOException {
        appendEntry(MemorySection.USER, entry);
    }

    /** 整页重写 MEMORY.md。 */
    public void rewriteMemory(String content) throws IOException {
        rewritePage(MemorySection.MEMORY, content);
    }

    /** 整页重写 USER.md。 */
    public void rewriteUser(String content) throws IOException {
        rewritePage(MemorySection.USER, content);
    }

    /** SOUL.md 不存在时生成默认人格；已存在则原样保留（手编优先于缺省）。 */
    public void ensureSoul() throws IOException {
        final File target = fileOf(MemorySection.SOUL);
        withLock(target, new IoFn<Void>() {
            @Override
            public Void apply() throws IOException {
                if (!target.exists()) {
                    writeLocked(target, DEFAULT_SOUL);
                }
                return null;
            }
        });
    }

    /** 当前人格正文缺省值（用于断言"ensureSoul 写的是这一份"）。 */
    public static String defaultSoul() {
        return DEFAULT_SOUL;
    }

    public void clearMemory() throws IOException {
        rewriteMemory("");
    }

    public void clearUser() throws IOException {
        rewriteUser("");
    }

    // ===== 内部 =====

    private static String trimOf(File f) {
        return readFile(f).trim();
    }

    private String trimOf(MemorySection section) {
        return trimOf(fileOf(section));
    }

    private static MemorySection sectionOf(File f) {
        MemorySection s = MemorySection.ofFileName(f.getName());
        return s == null ? MemorySection.MEMORY : s;
    }

    private static String readFile(File f) {
        if (!f.isFile()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String absolute(File f) {
        return f == null ? null : f.getAbsolutePath();
    }

    private static int idx(int i, boolean batch) {
        return batch ? i + 1 : 0;
    }

    private static String prefix(int i, boolean batch) {
        return batch ? "第 " + (i + 1) + " 条操作失败：" : "";
    }

    /** 拿 {@code <file>.lock} 排他锁跑一次完整读-改-写；锁文件本身不是记忆内容。 */
    private static <T> T withLock(File target, IoFn<T> body) throws IOException {
        File lock = new File(target.getParentFile(), target.getName() + ".lock");
        try (RandomAccessFile raf = new RandomAccessFile(lock, "rw");
             FileChannel ch = raf.getChannel();
             FileLock ignored = ch.lock()) {
            return body.apply();
        }
    }

    /**
     * 锁内落盘三步：<b>先</b>把原字节快照走，<b>再</b>写，<b>后</b>回读对账。
     * 写失败或对不上账 ⇒ 用快照还原原字节、<b>保留</b>快照为取证并把 {@code bakPath} 抛出去；
     * 写成功 ⇒ 快照只是保险，不是证据，<b>当场清掉</b>（否则 {@code memories/} 会被
     * 每写一行留下一枚 {@code .bak} 污染，谁都不知道哪一枚还有意义）。
     *
     * @return 这次写入留下的取证快照（写成功 ⇒ null；原本没有文件可保 ⇒ null）
     */
    private WriteOutcome writeLocked(File target, String content) throws IOException {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        File bak = MemoryDriftGuard.snapshot(target);
        try {
            sink.write(target, payload);
            byte[] onDisk = target.isFile() ? Files.readAllBytes(target.toPath()) : new byte[0];
            if (!Arrays.equals(onDisk, payload)) {
                throw new MemoryWriteRejectedException(
                        MemoryWriteRejectedException.WRITE_UNVERIFIED,
                        "写后回读与待写字节不一致：" + target.getName() + " 盘上 " + onDisk.length
                                + " 字节，应为 " + payload.length + " 字节。", 0, absolute(bak));
            }
            MemoryDriftGuard.deleteQuietly(bak);   // 成功 ⇒ 保险作废
            return new WriteOutcome(bak != null, null);
        } catch (IOException e) {
            MemoryDriftGuard.deleteQuietly(new File(target.getParentFile(),
                    target.getName() + ".tmp"));
            String note = restoreOrAnnotate(target, bak, e.getMessage());
            if (e instanceof MemoryWriteRejectedException) {
                MemoryWriteRejectedException m = (MemoryWriteRejectedException) e;
                throw new MemoryWriteRejectedException(m.code(), m.getMessage() + note,
                        m.opIndex(), absolute(bak));
            }
            throw new MemoryWriteRejectedException(MemoryWriteRejectedException.WRITE_FAILED,
                    "写入 " + target.getName() + " 失败：" + e.getMessage() + note, 0, absolute(bak));
        }
    }

    /** 一次落盘的可观察后果：有没有做过快照、事后盘上还留着哪一枚取证快照。 */
    private static final class WriteOutcome {
        private final boolean snapshotTaken;
        private final File evidence;

        WriteOutcome(boolean snapshotTaken, File evidence) {
            this.snapshotTaken = snapshotTaken;
            this.evidence = evidence;
        }
    }

    /** 能还原就还原（还原后的字节必须等于原字节），还原不了就把失败原因附在报错后面。 */
    private static String restoreOrAnnotate(File target, File bak, String cause) throws IOException {
        if (bak == null) {
            return "（写之前盘上没有这份文件，无可还原内容。）";
        }
        try {
            MemoryDriftGuard.restore(target, bak);
            return " 已按快照 " + bak.getName() + " 还原，盘上字节与写之前一致。";
        } catch (IOException restoreFailure) {
            restoreFailure.addSuppressed(new IOException("原始写失败: " + cause));
            throw restoreFailure;
        }
    }

    /**
     * 历史内部通道（原子改名 + 快照 + 回读对账），走生产 {@link #FILE_SINK}。
     * 保留这个签名是因为旧测试用反射直取 SOUL 的写入通道模拟手编。
     */
    static void lockedWrite(final File target, final String content) throws IOException {
        withLock(target, new IoFn<Void>() {
            @Override
            public Void apply() throws IOException {
                byte[] payload = content.getBytes(StandardCharsets.UTF_8);
                File bak = MemoryDriftGuard.snapshot(target);
                try {
                    FILE_SINK.write(target, payload);
                    MemoryDriftGuard.deleteQuietly(bak);
                } catch (IOException e) {
                    MemoryDriftGuard.deleteQuietly(new File(target.getParentFile(),
                            target.getName() + ".tmp"));
                    restoreOrAnnotate(target, bak, e.getMessage());
                    throw e;
                }
                return null;
            }
        });
    }
}
