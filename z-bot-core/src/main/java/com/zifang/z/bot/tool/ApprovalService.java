package com.zifang.z.bot.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 每会话审批队列 + 四档决议（P11 新增）。
 *
 * <h2>为什么不是"确认/不确认"二元</h2>
 * 旧实现只有一个 {@code volatile ToolCall pendingConfirmation} 槽位：多条待批会互相覆盖，
 * 人也只能回答"是/否"。这里对齐 hermes {@code tools/approval.py} 的
 * {@code _gateway_queues}（:2016 起，每会话一个 FIFO，{@code resolve_gateway_approval}
 * 不带 {@code resolve_all} 时只弹出最老的一条）与四档结果
 * {@code "once"|"session"|"always"|"deny"}（:2037 注释、:2399-2410 交互分支）。
 *
 * <h2>四档语义</h2>
 * <ul>
 *   <li>{@link Resolution#ONCE} —— 只放行这一次调用，不留痕。</li>
 *   <li>{@link Resolution#SESSION} —— 本会话内同一条命令（{@link ExecGuard#approvalKey} 精确匹配）
 *       免再问；<b>只在内存里</b>，{@link #forgetSession} 或换会话即失效，<b>不落盘</b>。</li>
 *   <li>{@link Resolution#ALWAYS} —— 唯一会落盘的一档：写进
 *       {@code <configDir>/config.properties} 的 {@code agent.exec.approval.always}。</li>
 *   <li>{@link Resolution#DENY} —— 拒绝，不执行，并把原因回吐给调用方。</li>
 * </ul>
 *
 * <h2>和硬线表的关系</h2>
 * 本类<b>无权</b>放行硬线命令：所有决议最终都要回到 {@link ExecGuard#decide}，
 * 而 decide 的第一关就是硬线表（红线 7）。{@link #approvePermanent} 只是记下"这条命令
 * 以后不再问人"，命中硬线的命令即使出现在精确名单里也仍会被拒。
 *
 * <p>落盘只走 {@link Persistence} 回调（由 {@code BotConfig} 实现），因此受红线 1 约束：
 * 只写 configDir，绝不写代码目录。</p>
 */
public final class ApprovalService {

    /** 一个会话最多排多少条待批（超出丢最老的，防止无人应答时无限增长）。 */
    public static final int DEFAULT_MAX_QUEUE_PER_SESSION = 32;

    /** 没有会话 id 时使用的兜底会话键（单测/裸工具直调）。 */
    public static final String DEFAULT_SESSION_KEY = "default";

    /** 决议四档。 */
    public enum Resolution {
        ONCE, SESSION, ALWAYS, DENY;

        /**
         * 宽松解析人的输入；认不出来时一律按 {@link #DENY} 处理（宁可少放行，不可误放行）。
         *
         * @param raw 人给的字符串，可为 null
         * @param fallback raw 为空白时的缺省值
         */
        public static Resolution parse(String raw, Resolution fallback) {
            if (raw == null || raw.trim().isEmpty()) {
                return fallback == null ? ONCE : fallback;
            }
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if (v.equals("once") || v.equals("o") || v.equals("y") || v.equals("yes")
                    || v.equals("approve") || v.equals("approv") || v.equals("allow") || v.equals("1")) {
                return ONCE;
            }
            if (v.equals("session") || v.equals("s") || v.equals("this-session") || v.equals("session-only")) {
                return SESSION;
            }
            if (v.equals("always") || v.equals("a") || v.equals("permanent") || v.equals("forever")) {
                return ALWAYS;
            }
            if (v.equals("deny") || v.equals("denied") || v.equals("n") || v.equals("no")
                    || v.equals("reject") || v.equals("block") || v.equals("0")) {
                return DENY;
            }
            return DENY;
        }
    }

    /** 一条待批请求（FIFO 元素）。 */
    public static final class Request {
        private final String id;
        private final String sessionKey;
        private final String toolName;
        private final String argsJson;
        private final String command;
        private final String approvalKey;
        private final String reason;
        private final String rule;
        private final long createdAtMillis;

        Request(String id, String sessionKey, String toolName, String argsJson, String command,
                String approvalKey, String reason, String rule, long createdAtMillis) {
            this.id = id;
            this.sessionKey = sessionKey;
            this.toolName = toolName;
            this.argsJson = argsJson;
            this.command = command;
            this.approvalKey = approvalKey;
            this.reason = reason;
            this.rule = rule;
            this.createdAtMillis = createdAtMillis;
        }

        public String id() {
            return id;
        }

        public String sessionKey() {
            return sessionKey;
        }

        public String toolName() {
            return toolName;
        }

        public String argsJson() {
            return argsJson;
        }

        public String command() {
            return command;
        }

        /** 精确匹配 key（{@link ExecGuard#approvalKey}）。 */
        public String approvalKey() {
            return approvalKey;
        }

        public String reason() {
            return reason;
        }

        /** 命中的表条目（{@link ExecGuard#MODE_ALL} / 危险表描述 / unresolved-indirection）。 */
        public String rule() {
            return rule;
        }

        public long createdAtMillis() {
            return createdAtMillis;
        }

        @Override
        public String toString() {
            return "#" + id + " " + toolName + " " + command;
        }
    }

    /** 决议结果：被消费掉的请求 + 采用的决议。 */
    public static final class Resolved {
        private final Request request;
        private final Resolution resolution;

        Resolved(Request request, Resolution resolution) {
            this.request = request;
            this.resolution = resolution;
        }

        public Request request() {
            return request;
        }

        public Resolution resolution() {
            return resolution;
        }

        public boolean denied() {
            return resolution == Resolution.DENY;
        }
    }

    /** ALWAYS 落盘的写入口，由 {@code BotConfig} 实现（红线 1：只写 configDir）。 */
    public interface Persistence {
        /** 追加一条精确命令到 {@code agent.exec.approval.always}；失败要抛异常，不许静默。 */
        void appendAlwaysApproval(String approvalKey);
    }

    private final Object lock = new Object();
    private final Map<String, Deque<Request>> queues = new HashMap<String, Deque<Request>>();
    private final Map<String, Set<String>> sessionApprovals = new HashMap<String, Set<String>>();
    private final Set<String> permanentApprovals = new LinkedHashSet<String>();
    private final List<String> prefixWhitelist = new ArrayList<String>();
    private volatile Persistence persistence;
    private volatile Supplier<String> sessionKeySupplier;
    private volatile int maxQueuePerSession = DEFAULT_MAX_QUEUE_PER_SESSION;

    public ApprovalService() {
    }

    public ApprovalService(List<String> prefixWhitelist, List<String> alwaysApprovals) {
        setPrefixWhitelist(prefixWhitelist);
        if (alwaysApprovals != null) {
            for (String entry : alwaysApprovals) {
                if (entry != null && !entry.trim().isEmpty()) {
                    permanentApprovals.add(entry.trim());
                }
            }
        }
    }

    // ===== 装配 =====

    /** 人在配置文件里写的前缀白名单（token 边界匹配）。 */
    public void setPrefixWhitelist(List<String> entries) {
        synchronized (lock) {
            prefixWhitelist.clear();
            if (entries != null) {
                for (String entry : entries) {
                    if (entry != null && !entry.trim().isEmpty()) {
                        prefixWhitelist.add(entry.trim());
                    }
                }
            }
        }
    }

    /** 前缀白名单快照（配置里的那份，不含 session/always 精确项）。 */
    public List<String> prefixWhitelist() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<String>(prefixWhitelist));
        }
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    /** 会话 id 的来源（{@code BotAgent::currentSessionId}）；null 时用 {@link #DEFAULT_SESSION_KEY}。 */
    public void bindSessionKey(Supplier<String> sessionKeySupplier) {
        this.sessionKeySupplier = sessionKeySupplier;
    }

    public void setMaxQueuePerSession(int maxQueuePerSession) {
        this.maxQueuePerSession = Math.max(1, maxQueuePerSession);
    }

    /** 当前会话键（拿不到时回落到 {@link #DEFAULT_SESSION_KEY}）。 */
    public String currentSessionKey() {
        Supplier<String> supplier = this.sessionKeySupplier;
        if (supplier == null) {
            return DEFAULT_SESSION_KEY;
        }
        try {
            String key = supplier.get();
            return key == null || key.trim().isEmpty() ? DEFAULT_SESSION_KEY : key.trim();
        } catch (Exception e) {
            return DEFAULT_SESSION_KEY;
        }
    }

    // ===== FIFO =====

    /**
     * 请求进队（FIFO 尾部）。队列满时丢最老的一条并返回新请求 —— 被丢掉的那条不会再有人应答。
     */
    public Request submit(String sessionKey, String toolName, String argsJson, String command,
                         String approvalKey, String reason, String rule) {
        String key = normalizeKey(sessionKey);
        Request request = new Request(UUID.randomUUID().toString().substring(0, 8), key, toolName,
                argsJson, command, approvalKey, reason, rule, System.currentTimeMillis());
        synchronized (lock) {
            Deque<Request> queue = queues.get(key);
            if (queue == null) {
                queue = new ArrayDeque<Request>();
                queues.put(key, queue);
            }
            while (queue.size() >= maxQueuePerSession) {
                queue.pollFirst();
            }
            queue.addLast(request);
        }
        return request;
    }

    /** 该会话的待批快照（按 FIFO 顺序）。 */
    public List<Request> pending(String sessionKey) {
        synchronized (lock) {
            Deque<Request> queue = queues.get(normalizeKey(sessionKey));
            if (queue == null || queue.isEmpty()) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(new ArrayList<Request>(queue));
        }
    }

    /** 该会话当前会话（{@link #currentSessionKey()}）的待批快照。 */
    public List<Request> pending() {
        return pending(currentSessionKey());
    }

    /** 队头（不出队）；没有待批时返回 null。 */
    public Request next(String sessionKey) {
        synchronized (lock) {
            Deque<Request> queue = queues.get(normalizeKey(sessionKey));
            return queue == null ? null : queue.peekFirst();
        }
    }

    /**
     * 取代语义：把本会话里除 {@code keepRequestId} 之外的待批全部出队，返回被取代的条数。
     *
     * <p>为什么需要：z-bot 的工具确认是"抛异常中断当前回合"，不是 hermes 的"工具线程阻塞在
     * 自己的审批上"。同一会话里新一条待批到来时，更老的那些永远不会再被执行；留着它们，
     * {@code /confirm} 就会消费掉一条与真实动作无关的旧账（红线 8 禁止的账实分离）。</p>
     *
     * <p>队列本身仍是 FIFO —— 通道层（HTTP/P16 送达）可以在同一会话里并发持有多个请求，
     * 那类请求不走本方法，只有 agent 中断式确认会取代。</p>
     */
    public int supersedePending(String sessionKey, String keepRequestId) {
        if (keepRequestId == null) {
            return 0;
        }
        String key = normalizeKey(sessionKey);
        synchronized (lock) {
            Deque<Request> queue = queues.get(key);
            if (queue == null || queue.size() < 2) {
                return 0;
            }
            int dropped = 0;
            java.util.Iterator<Request> it = queue.iterator();
            while (it.hasNext()) {
                if (keepRequestId.equals(it.next().id())) {
                    continue;
                }
                it.remove();
                dropped++;
            }
            if (queue.isEmpty()) {
                queues.remove(key);
            }
            return dropped;
        }
    }

    public int pendingCount(String sessionKey) {
        synchronized (lock) {
            Deque<Request> queue = queues.get(normalizeKey(sessionKey));
            return queue == null ? 0 : queue.size();
        }
    }

    /** 按 FIFO 消费队头；没有待批时返回 {@link Resolved} 且 {@code request()==null}。 */
    public Resolved resolveNext(String sessionKey, Resolution resolution) {
        String key = normalizeKey(sessionKey);
        Request head;
        synchronized (lock) {
            Deque<Request> queue = queues.get(key);
            if (queue == null || queue.isEmpty()) {
                return new Resolved(null, resolution == null ? Resolution.ONCE : resolution);
            }
            head = queue.pollFirst();
            if (queue.isEmpty()) {
                queues.remove(key);
            }
        }
        return afterResolve(head, resolution);
    }

    /** 按 request id 消费（不在队里时按新请求处理，保证 /confirm 不会把人的决议吞掉）。 */
    public Resolved resolve(String sessionKey, String requestId, Resolution resolution) {
        String key = normalizeKey(sessionKey);
        Request target;
        synchronized (lock) {
            Deque<Request> queue = queues.get(key);
            target = null;
            if (queue != null) {
                for (Request request : queue) {
                    if (requestId != null && requestId.equals(request.id())) {
                        target = request;
                        break;
                    }
                }
                if (target != null) {
                    queue.remove(target);
                    if (queue.isEmpty()) {
                        queues.remove(key);
                    }
                }
            }
        }
        if (target == null) {
            return new Resolved(null, resolution == null ? Resolution.ONCE : resolution);
        }
        return afterResolve(target, resolution);
    }

    private Resolved afterResolve(Request head, Resolution resolution) {
        Resolution r = resolution == null ? Resolution.ONCE : resolution;
        if (r == Resolution.SESSION) {
            approveSession(head.sessionKey(), head.approvalKey());
        } else if (r == Resolution.ALWAYS) {
            approvePermanent(head.approvalKey());
        }
        // ONCE / DENY 不留任何可复用痕迹
        return new Resolved(head, r);
    }

    /** 清空该会话的待批队列（会话结束/换会话时调用）。 */
    public void clearQueue(String sessionKey) {
        synchronized (lock) {
            queues.remove(normalizeKey(sessionKey));
        }
    }

    /** 会话结束：待批队列和 session 级决议一起失效（ALWAYS 已落盘，不受影响）。 */
    public void forgetSession(String sessionKey) {
        String key = normalizeKey(sessionKey);
        synchronized (lock) {
            queues.remove(key);
            sessionApprovals.remove(key);
        }
    }

    // ===== 免确认名单 =====

    /** SESSION 档：本会话内该 key 免再问（纯内存）。 */
    public void approveSession(String sessionKey, String approvalKey) {
        if (approvalKey == null || approvalKey.trim().isEmpty()) {
            return;
        }
        synchronized (lock) {
            Set<String> set = sessionApprovals.get(normalizeKey(sessionKey));
            if (set == null) {
                set = new LinkedHashSet<String>();
                sessionApprovals.put(normalizeKey(sessionKey), set);
            }
            set.add(approvalKey.trim());
        }
    }

    /** ALWAYS 档：内存名单 + 通过 {@link Persistence} 落盘；落盘失败不影响本次进程内生效。 */
    public void approvePermanent(String approvalKey) {
        if (approvalKey == null || approvalKey.trim().isEmpty()) {
            return;
        }
        String key = approvalKey.trim();
        synchronized (lock) {
            permanentApprovals.add(key);
        }
        Persistence sink = this.persistence;
        if (sink != null) {
            sink.appendAlwaysApproval(key);
        }
    }

    /** 精确名单（已 ALWAYS 的）快照。 */
    public List<String> permanentApprovals() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<String>(permanentApprovals));
        }
    }

    /** 该会话的 session 级精确名单快照。 */
    public List<String> sessionApprovals(String sessionKey) {
        synchronized (lock) {
            Set<String> set = sessionApprovals.get(normalizeKey(sessionKey));
            return set == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(set));
        }
    }

    /** 命令是否已被精确放行（session 或 always）。硬线不在此处判断，由 {@link ExecGuard} 负责。 */
    public boolean isApproved(String sessionKey, String approvalKey) {
        if (approvalKey == null || approvalKey.trim().isEmpty()) {
            return false;
        }
        String key = approvalKey.trim();
        synchronized (lock) {
            if (permanentApprovals.contains(key)) {
                return true;
            }
            Set<String> set = sessionApprovals.get(normalizeKey(sessionKey));
            return set != null && set.contains(key);
        }
    }

    private static String normalizeKey(String sessionKey) {
        return sessionKey == null || sessionKey.trim().isEmpty() ? DEFAULT_SESSION_KEY : sessionKey.trim();
    }

    /** 测试/诊断：当前有多少会话挂着待批。 */
    public int sessionCountWithPending() {
        synchronized (lock) {
            return queues.size();
        }
    }
}
