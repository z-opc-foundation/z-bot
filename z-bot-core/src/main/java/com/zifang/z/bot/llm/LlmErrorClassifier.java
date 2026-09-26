package com.zifang.z.bot.llm;

import com.zifang.z.agent.kernel.llm.support.LlmException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P26：可审计的 LLM 失败分类表（替换 {@code ResilientLlmProvider} 原来的两条正则）。
 *
 * <p>判定优先级（越靠前越硬）：</p>
 * <ol>
 *   <li><b>结构化 HTTP 状态码</b> —— {@link LlmException#getHttpStatus()}（kernel 0.2.1 起就有，
 *       原实现却把它扔掉只读 message）</li>
 *   <li><b>异常类型链</b> —— {@code SocketTimeoutException} / {@code ConnectException} / … </li>
 *   <li><b>收窄后的消息特征</b> —— 只认带上下文的短语（{@code rate limit}、{@code invalid api key}），
 *       <b>不再</b>对裸数字 {@code \b429\b} 做正则；正文里出现 "429" 不再被判成限流</li>
 *   <li>兜底 {@code unclassified} ⇒ {@link FailureClass#FATAL}（判不准就不重试，宁可上抛）</li>
 * </ol>
 *
 * <p>每条规则有具名（{@link Decision#getRule()}），单测按规则名单独钉；变异注入按规则名摘。</p>
 */
public final class LlmErrorClassifier {

    /** 失败该往哪儿走。 */
    public enum FailureClass {
        /** 同 key 同模型重试（连接类 / 5xx / 超时）。 */
        RETRY_SAME_KEY,
        /** 限流：走限流长档表，且上游 {@code Retry-After} 优先。 */
        RATE_LIMIT,
        /** 换 key：鉴权失效 / 配额耗尽（401/402/403）。 */
        ROTATE_KEY,
        /** 换模型：这个模型本身不可用（404 模型不存在 / 上下文超长）。 */
        FALLBACK_MODEL,
        /** 直接上抛：参数错、413/415/422/451、判不准的兜底。 */
        FATAL
    }

    /** 判据来源，用于验证"不靠 message 抓数字"。 */
    public enum Evidence {
        /** 结构化 HTTP 状态码。 */
        HTTP_STATUS,
        /** 异常类型链。 */
        EXCEPTION_TYPE,
        /** 收窄消息特征（只有这一类才是文本匹配）。 */
        MESSAGE_PATTERN,
        /** 什么都没命中 ⇒ 兜底。 */
        NONE
    }

    // ---- 收窄消息特征：一律要求"带词的"，绝不裸匹配数字 ----
    /**
     * **带协议标签**的状态码：{@code HTTP 429} / {@code status=429} / {@code "status": 429} /
     * {@code error code 429}。裸数字（"工单号 429"、"端口 429"）不算 —— 这是原实现
     * {@code \b429\b} 假阳性的收口点。
     */
    private static final Pattern MSG_LABELED_STATUS = Pattern.compile(
            "(?i)\\b(?:http|https|status(?:[\\s_]+code)?|error[\\s_]+code|response[\\s_]+code|status[\\s_]*code)"
                    + "[\\s_:=\"'\\-/.]{0,6}(\\d{3})\\b");
    private static final Pattern MSG_RATE_LIMIT = Pattern.compile(
            "(?i)\\b(rate[\\s_-]?limit(?:ed)?|too many requests|quota[ _-]?(?:exceeded|exhausted)"
                    + "|insufficient[\\s_]+quota|requests?[\\s_]?per[\\s_]?second|throttl\\w*)\\b");
    private static final Pattern MSG_KEY_SCOPED = Pattern.compile(
            "(?i)\\b(invalid[\\s_]+api[\\s_]*key|api[\\s_]*key[\\s_]+(?:not[\\s_]+)?valid"
                    + "|authentication(?:error)?|unauthorized|incorrect[\\s_]+api[\\s_]*key"
                    + "|permission[\\s_]?denied|exceeded[\\s_]+your[\\s_]+current[\\s_]+quota"
                    + "|forbidden|payment[\\s_]?required|insufficient[\\s_]+credits?)\\b");
    private static final Pattern MSG_MODEL_SCOPED = Pattern.compile(
            "(?i)\\b(model[\\s_-]?(?:not[\\s_]?found|unavailable|does[\\s_]?not[\\s_]?exist"
                    + "|not[\\s_]?supported|not_supported)|no[\\s_]?such[\\s_]?model"
                    + "|unsupported[\\s_]+model|unknown[\\s_]+model|context[\\s_]?length"
                    + "|maximum[\\s_]?context|too[\\s_]?many[\\s_]?tokens|input[\\s_]?is[\\s_]?too[\\s_]?long)\\b");
    private static final Pattern MSG_TRANSIENT = Pattern.compile(
            "(?i)\\b(timeout|timed[\\s_]?out|connection[\\s_]?(?:refused|reset|closed|aborted)"
                    + "|broken[\\s_]?pipe|server[\\s_]?overload(?:ed)?|overloaded\\w*|service[\\s_]?unavailable"
                    + "|bad[\\s_]?gateway|internal[\\s_]?server[\\s_]?error|network[\\s_]?(?:error|failure)"
                    + "|stream[\\s_]?(?:closed|aborted|reset)|premature[\\s_]?end)\\b");
    private static final Pattern MSG_MODEL_HINT = Pattern.compile(
            "(?i)\\b(model|engine|deployment)[\\s_\"':=]*[\\s_\"':=]?\\w+|not_found_error\\b");
    private static final Pattern MSG_FATAL_HINT = Pattern.compile(
            "(?i)\\b(invalid[\\s_]+(?:request|parameter|params|body|json|temperature|value)"
                    + "|unprocessable|content[\\s_]?policy|safety|moderation[\\s_]?block"
                    + "|payload too large|unsupported[\\s_]?media[\\s_]?type)\\b");

    /** {@code Retry-After} 只认带标签的写法：头部回声、JSON 键、或 {@code retry after 30}。 */
    private static final Pattern RETRY_AFTER = Pattern.compile(
            "(?i)(?:retry[\\s_-]?after|retry[\\s_-]?delay|retryafter|\"retry_after\"|\"retryDelay\")"
                    + "\\D{0,4}(\\d{1,5})(?:\\.\\d+)?\\s*(ms|msec|seconds?|sec|s\\b)?");

    private LlmErrorClassifier() {
    }

    /** 审计面：规则清单（顺序即优先级），杠② 的变异族按名字摘。 */
    public static List<String> ruleNames() {
        return Collections.unmodifiableList(new ArrayList<String>(java.util.Arrays.asList(
                "interrupt", "http:429", "http:401/402/403", "http:404-model", "http:404-path",
                "http:5xx", "http:4xx-fatal", "type:socket-timeout", "type:connect", "type:dns",
                "type:io", "msg:rate-limit", "msg:key-scoped", "msg:model-scoped", "msg:transient",
                "msg:fatal-hint", "unclassified")));
    }

    /** 分类一条异常（不带配置 ⇒ {@code Retry-After} 不设上限）。 */
    public static Decision classify(Throwable error) {
        return classify(error, null);
    }

    /** 分类一条异常。 */
    public static Decision classify(Throwable error, RetryPolicyConfig policy) {
        if (error == null) {
            return new Decision(FailureClass.FATAL, "unclassified", Evidence.NONE, null, 0L);
        }
        Integer status = httpStatusOf(error);
        String msg = messageOf(error);

        if (contains(error, InterruptedException.class)) {
            return new Decision(FailureClass.FATAL, "interrupt", Evidence.EXCEPTION_TYPE, status, 0L);
        }
        if (status != null) {
            FailureClass cls = classOfStatus(status, msg);
            String rule = ruleOfStatus(status, msg);
            return new Decision(cls, rule, Evidence.HTTP_STATUS, status, retryAfterMs(msg, policy));
        }
        Evidence typeEvidence = Evidence.EXCEPTION_TYPE;
        FailureClass byType = classOfThrowableType(error);
        if (byType != null) {
            return new Decision(byType, ruleOfThrowableType(error), typeEvidence, null, 0L);
        }
        if (msg.length() > 0) {
            Matcher labeled = MSG_LABELED_STATUS.matcher(msg);
            if (labeled.find()) {
                int labeledStatus = Integer.parseInt(labeled.group(1));
                if (labeledStatus >= 400) {
                    return new Decision(classOfStatus(labeledStatus, msg), "msg:http:" + labeledStatus,
                            Evidence.MESSAGE_PATTERN, Integer.valueOf(labeledStatus), retryAfterMs(msg, policy));
                }
            }
            if (MSG_RATE_LIMIT.matcher(msg).find()) {
                return new Decision(FailureClass.RATE_LIMIT, "msg:rate-limit", Evidence.MESSAGE_PATTERN,
                        null, retryAfterMs(msg, policy));
            }
            if (MSG_KEY_SCOPED.matcher(msg).find()) {
                return new Decision(FailureClass.ROTATE_KEY, "msg:key-scoped", Evidence.MESSAGE_PATTERN, null, 0L);
            }
            if (MSG_MODEL_SCOPED.matcher(msg).find()) {
                return new Decision(FailureClass.FALLBACK_MODEL, "msg:model-scoped",
                        Evidence.MESSAGE_PATTERN, null, 0L);
            }
            if (MSG_TRANSIENT.matcher(msg).find()) {
                return new Decision(FailureClass.RETRY_SAME_KEY, "msg:transient", Evidence.MESSAGE_PATTERN, null, 0L);
            }
            if (MSG_FATAL_HINT.matcher(msg).find()) {
                return new Decision(FailureClass.FATAL, "msg:fatal-hint", Evidence.MESSAGE_PATTERN, null, 0L);
            }
        }
        return new Decision(FailureClass.FATAL, "unclassified", Evidence.NONE, null, 0L);
    }

    /** 结构化状态码：kernel 的 {@link LlmException} 直取，别的类型不猜。 */
    static Integer httpStatusOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof LlmException) {
                Integer s = ((LlmException) t).getHttpStatus();
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    private static String messageOf(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null && sb.length() < 8000; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(' ');
            }
            sb.append(t.getClass().getName()).append(' ');
        }
        return sb.toString();
    }

    private static FailureClass classOfStatus(int status, String msg) {
        if (status == 429) {
            return FailureClass.RATE_LIMIT;
        }
        if (status == 401 || status == 402 || status == 403) {
            return FailureClass.ROTATE_KEY;
        }
        if (status == 404 || status == 410) {
            return mentionsModel(msg) ? FailureClass.FALLBACK_MODEL : FailureClass.FATAL;
        }
        if (status == 409 && mentionsModel(msg)) {
            return FailureClass.FALLBACK_MODEL;
        }
        if (status >= 500 && status <= 504 || status == 522 || status == 524) {
            return FailureClass.RETRY_SAME_KEY;
        }
        if (status >= 400 && status < 500) {
            return FailureClass.FATAL;
        }
        return status >= 500 ? FailureClass.RETRY_SAME_KEY : FailureClass.FATAL;
    }

    private static String ruleOfStatus(int status, String msg) {
        if (status == 429) {
            return "http:429";
        }
        if (status == 401 || status == 402 || status == 403) {
            return "http:401/402/403";
        }
        if (status == 404 || status == 410) {
            return mentionsModel(msg) ? "http:404-model" : "http:404-path";
        }
        if (status >= 500) {
            return "http:5xx";
        }
        return "http:4xx-fatal";
    }

    private static boolean mentionsModel(String msg) {
        return msg != null && MSG_MODEL_HINT.matcher(msg).find();
    }

    private static FailureClass classOfThrowableType(Throwable error) {
        if (contains(error, SocketTimeoutException.class)) {
            return FailureClass.RETRY_SAME_KEY;
        }
        if (contains(error, ConnectException.class) || contains(error, NoRouteToHostException.class)) {
            return FailureClass.RETRY_SAME_KEY;
        }
        if (contains(error, UnknownHostException.class)) {
            return FailureClass.RETRY_SAME_KEY;
        }
        if (contains(error, SocketException.class)) {
            return FailureClass.RETRY_SAME_KEY;
        }
        if (contains(error, IOException.class)) {
            return FailureClass.RETRY_SAME_KEY;
        }
        return null;
    }

    private static String ruleOfThrowableType(Throwable error) {
        if (contains(error, SocketTimeoutException.class)) {
            return "type:socket-timeout";
        }
        if (contains(error, UnknownHostException.class)) {
            return "type:dns";
        }
        if (contains(error, ConnectException.class) || contains(error, NoRouteToHostException.class)) {
            return "type:connect";
        }
        return "type:io";
    }

    private static boolean contains(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** 解析 {@code Retry-After}（毫秒）；没有标签命中 ⇒ 0；超过 {@code retry-after-cap-ms} ⇒ 封顶。 */
    static long retryAfterMs(String msg, RetryPolicyConfig policy) {
        if (msg == null) {
            return 0L;
        }
        Matcher m = RETRY_AFTER.matcher(msg);
        if (!m.find()) {
            return 0L;
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2).toLowerCase(Locale.ROOT);
        long ms = "ms".equals(unit) || "msec".equals(unit) ? n : n * 1000L;
        if (policy != null && policy.getRetryAfterCapMs() > 0) {
            ms = Math.min(ms, policy.getRetryAfterCapMs());
        }
        return ms;
    }

    /** 分类结果：类别 + 命中的具名规则 + 判据来源 + 结构化状态码 + Retry-After。 */
    public static final class Decision {
        private final FailureClass failureClass;
        private final String rule;
        private final Evidence evidence;
        private final Integer httpStatus;
        private final long retryAfterMs;

        Decision(FailureClass failureClass, String rule, Evidence evidence, Integer httpStatus, long retryAfterMs) {
            this.failureClass = failureClass;
            this.rule = rule;
            this.evidence = evidence;
            this.httpStatus = httpStatus;
            this.retryAfterMs = retryAfterMs;
        }

        public FailureClass getFailureClass() {
            return failureClass;
        }

        public String getRule() {
            return rule;
        }

        public Evidence getEvidence() {
            return evidence;
        }

        public Integer getHttpStatus() {
            return httpStatus;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }

        /** 会不会被再试一次（限流也算）。 */
        public boolean isRetryable() {
            return failureClass == FailureClass.RETRY_SAME_KEY || failureClass == FailureClass.RATE_LIMIT;
        }

        /** 该不该跳出当前模型（换模型）。 */
        public boolean requiresModelFallback() {
            return failureClass == FailureClass.FALLBACK_MODEL;
        }

        @Override
        public String toString() {
            return failureClass + "/" + rule + " (" + evidence + (httpStatus == null ? "" : ", HTTP " + httpStatus)
                    + (retryAfterMs > 0 ? ", retryAfter=" + retryAfterMs + "ms" : "") + ")";
        }
    }
}
