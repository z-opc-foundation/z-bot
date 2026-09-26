package com.zifang.z.bot.memory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆内容扫描器 —— hermes {@code tools/memory_tool.py:88} 的
 * {@code _scan_memory_content()} 在我层的等价物。
 *
 * <p>为什么这一层非有不可（她的注释原话）：<i>"memory enters the system prompt as a
 * FROZEN snapshot, so a poisoned entry persists for the entire session and across
 * sessions until explicitly removed."</i> 一条被投毒的记忆不是"这次对话坏了"，
 * 是<b>直到人删掉之前每一轮都坏</b>，而且它藏在提示词前缀里、模型自己看不见。
 * 所以写入门禁必须在落盘之前扫，而不是注入之前扫。</p>
 *
 * <p>与她的差异（有意为之，不是漏做）：她调 {@code tools/threat_patterns.first_threat_message}
 * 那张共享特征表（strict 档），那张表面向我够不着（不在本仓）；本表是<b>为记忆这一层
 * 手写的窄表</b>，只收"能变成指令"或"能带走密钥"这两类形状，宁窄勿宽 ——
 * 记忆条目绝大多数是中文短句，宽表会把正常偏好记成攻击。每条都带
 * {@code benign()} 反例（{@code MemoryContentScanTest} 逐条断言不命中），
 * 命中位点必回报，且<b>命中即拒写</b>（不是"警告后照写"）。</p>
 *
 * <p>外传类特征（私钥块 / api key 赋值）的 {@code snippet} 一律打 {@code ***}：
 * 门禁的报错文案会进 transcript 和日志，把疑似密钥原文抄进文案就是二次外传。</p>
 */
public final class MemoryContentScan {

    /** 一次命中：哪条特征、第几个字符、可展示的片段。 */
    public static final class Hit {
        private final String patternId;
        private final int offset;
        private final String snippet;

        Hit(String patternId, int offset, String snippet) {
            this.patternId = patternId;
            this.offset = offset;
            this.snippet = snippet;
        }

        public String patternId() {
            return patternId;
        }

        /** 命中起点（字符下标，0 起算）。门禁"回报命中位点"就是这一个数。 */
        public int offset() {
            return offset;
        }

        /** 命中片段（已截断，外传类为 {@code ***}）。 */
        public String snippet() {
            return snippet;
        }

        @Override
        public String toString() {
            return patternId + "@" + offset + ":" + snippet;
        }
    }

    private static final class Rule {
        final String id;
        final Pattern pattern;
        final boolean redact;

        Rule(String id, String regex, boolean redact) {
            this.id = id;
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            this.redact = redact;
        }
    }

    /** 片段展示上限（字符）。 */
    static final int SNIPPET_MAX = 48;

    private static final Rule[] RULES = {
            // 1. 指令覆盖：让模型丢掉既有指令的那类句式（中英各一形）
            new Rule("instruction-override",
                    "(ignore|disregard|forget)\\s+(all\\s+|any\\s+)?(previous|prior|above|earlier)"
                            + "\\s+(instructions?|prompts?|rules?|system\\s+prompt)", false),
            new Rule("instruction-override-zh",
                    "(忽略|无视|忘记|忘掉)[^。\\n]{0,8}(之前|先前|上文|以上|前面)[^。\\n]{0,8}(指令|规则|提示|设定|系统)", false),
            // 2. 角色重指派：把模型改写成别的身份/档位
            new Rule("role-reassignment",
                    "you\\s+are\\s+now\\s+(a|an|the)\\s+[^.\\n]{0,40}(mode|system|administrator|admin|developer)"
                            + "|new\\s+system\\s+prompt\\s*:|developer\\s+mode\\s+(enabled|activated)", false),
            new Rule("role-reassignment-zh",
                    "你(现在|从此)[是成][^。\\n]{0,12}(系统|开发者|管理员|越狱)", false),
            // 3. 提示词分隔符越界：想把记忆正文变成结构
            new Rule("delimiter-breakout", "</?\\s*(system|assistant|instructions?|prompt)\\s*>", false),
            // 4. 密钥/凭据外传：私钥块、显式赋值、OpenAI 风格 sk- 前缀
            new Rule("secret-material", "-----BEGIN [A-Z ]*PRIVATE KEY-----", true),
            new Rule("credential-assignment",
                    "(api[_-]?key|access[_-]?token|secret[_-]?key|password)\\s*[:=]\\s*[A-Za-z0-9+_\\-]{12,}", true),
            new Rule("provider-key-token", "\\bsk-[A-Za-z0-9]{16,}", true),
            // 5. 下载即执行：把记忆变成供应链入口
            new Rule("remote-exec-pipe", "(curl|wget)\\s+\\S+\\s*\\|\\s*(ba|z|d)?sh", false),
    };

    private MemoryContentScan() {
    }

    /**
     * 扫一段待写入内容。
     *
     * @return 最早处的命中；全表不命中 ⇒ {@code null}
     */
    public static Hit scan(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Hit best = null;
        for (Rule rule : RULES) {
            Matcher m = rule.pattern.matcher(content);
            if (m.find()) {
                Hit h = new Hit(rule.id, m.start(),
                        rule.redact ? "***" : bound(m.group()));
                if (best == null || h.offset < best.offset) {
                    best = h;
                }
            }
        }
        return best;
    }

    /** 命中即拒写时给人看的文案（含位点，满足"回报命中位点"）。 */
    public static String describeHit(Hit hit) {
        return "记忆内容扫描命中注入/外传特征 " + hit.patternId()
                + "（起始下标 " + hit.offset() + "，片段 \"" + hit.snippet() + "\"）";
    }

    /** 只做判定，不给细节。 */
    public static boolean isBlocked(String content) {
        return scan(content) != null;
    }

    private static String bound(String raw) {
        String oneLine = raw.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= SNIPPET_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, SNIPPET_MAX) + "…";
    }
}
