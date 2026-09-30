package com.zifang.z.bot.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 写入门禁 —— hermes {@code _apply_write_gate()}（:833）、
 * {@code _missing_old_text_error()}（:937）、{@code add/replace/remove} 三处校验的
 * 合流等价物。它只做判定与报错，<b>不碰盘</b>；碰盘在 {@link MemoryStore}。
 *
 * <p>本期钉死的四条"不许静默"：</p>
 * <ol>
 *   <li><b>改已有条目必须给 {@code old_text}</b>。缺了就大声报错，
 *       错误里带<b>当前条目清单</b>和"重试时把 old_text 填成其中某条的一部分"的指令 ——
 *       她那句注释说得很直白：只回 "old_text is required" 是个死胡同，
 *       结构化输出的客户端经常就是不带可选字段。绝不许把"没给 old_text 的 replace"
 *       降级成一次新建（那等于凭空多出一条假记忆）。</li>
 *   <li><b>内容命中注入/外传特征 ⇒ 拒写</b>，并回报命中的特征名与位点（{@link MemoryContentScan}）。</li>
 *   <li><b>{@code old_text} 命中 0 条 ⇒ {@link MemoryWriteRejectedException#NO_MATCH}；
 *       命中多条互不相同的条目 ⇒ {@link MemoryWriteRejectedException#AMBIGUOUS_MATCH}</b>，
 *       两种都带清单回抛。命中的多条正文完全相同 ⇒ 取第一条（她的同款判断：重复条目是同一件事）。</li>
 *   <li><b>写后整份超预算 ⇒ 拒写</b>，报"现在多少 / 这条多少 / 超了多少"，
 *       并指名该删哪几条（清单在错误里）。</li>
 * </ol>
 */
public final class MemoryWriteGate {

    /** 错误文案里最多回抛几条现存条目。 */
    static final int INVENTORY_MAX = 12;

    private MemoryWriteGate() {
    }

    /**
     * 校验一段要落盘的新正文：非空 + 内容扫描。
     *
     * @param what    人话里给这段内容起的名字（"追加内容" / "替换后的正文"）
     * @param opIndex 批量里的第几条（1 起算；单条写 ⇒ 0）
     */
    public static void gateContent(String what, String content, int opIndex)
            throws MemoryWriteRejectedException {
        if (content == null || content.trim().isEmpty()) {
            throw reject(MemoryWriteRejectedException.EMPTY_CONTENT, opIndex,
                    what + "为空：没有任何东西可写。");
        }
        MemoryContentScan.Hit hit = MemoryContentScan.scan(content);
        if (hit != null) {
            throw reject(MemoryWriteRejectedException.SCAN_HIT, opIndex,
                    MemoryContentScan.describeHit(hit) + " —— 已拒写，未落盘。");
        }
    }

    /**
     * 用 {@code old_text} 在现有条目里定位唯一一条。
     *
     * @return 命中下标
     */
    public static int locate(MemoryOp.Kind kind, String oldText, List<String> entries, int opIndex)
            throws MemoryWriteRejectedException {
        String verb = kind == MemoryOp.Kind.REPLACE ? "replace" : "remove";
        if (oldText == null || oldText.trim().isEmpty()) {
            throw reject(MemoryWriteRejectedException.MISSING_OLD_TEXT, opIndex,
                    verb + " 必须给 old_text（要改/删的那一条正文里的一段唯一子串）。你没给，"
                            + "所以这一条没法执行 —— 我们不会把它当成一次新建。"
                            + inventory(entries, "当前条目"));
        }
        List<Integer> hits = new ArrayList<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            // 匹配的必须是"正文"：整行里有 [时间戳]，old_text 一旦沾到时间就永远命中不到，
            // 反过来 —— 正文里没有那段时，命中别条的时间戳也会把不该删的条目端走。
            if (MemoryDriftGuard.entryBody(entries.get(i)).contains(oldText)) {
                hits.add(i);
            }
        }
        if (hits.isEmpty()) {
            throw reject(MemoryWriteRejectedException.NO_MATCH, opIndex,
                    "没有条目匹配 old_text \"" + oldText + "\"。" + inventory(entries, "当前条目"));
        }
        Set<String> distinct = new LinkedHashSet<String>();
        for (Integer i : hits) {
            distinct.add(MemoryDriftGuard.entryBody(entries.get(i)));
        }
        if (distinct.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("old_text \"").append(oldText).append("\" 命中 ")
                    .append(hits.size()).append(" 条互不相同的条目，没法猜你要动哪一条。命中项：");
            for (String d : distinct) {
                sb.append("\n  - ").append(preview(d));
            }
            throw reject(MemoryWriteRejectedException.AMBIGUOUS_MATCH, opIndex, sb.toString());
        }
        return hits.get(0).intValue();
    }

    /**
     * 整份预算校验（在<b>最终</b>状态上判，批量中途超不算数 —— 她的同款口径）。
     *
     * @param charLimit 生效预算：由调用方（{@link MemoryStore#charLimit(MemorySection)}）给，
     *                  不从这里读枚举缺省 —— 实例上覆盖过预算时，读枚举就是把预算放宽回缺省值。
     */
    public static void requireBudget(MemorySection section, int charLimit,
                                     List<String> candidate, int opIndex)
            throws MemoryWriteRejectedException {
        int total = MemoryDriftGuard.render(candidate).length();
        if (total > charLimit) {
            throw reject(MemoryWriteRejectedException.OVER_BUDGET, opIndex,
                    section.fileName() + " 写完会是 " + total + " 字符，超过预算 "
                            + charLimit + "。先在同一批里删掉或合并几条旧条目再重试。"
                            + inventory(candidate, "这批算完之后的条目"));
        }
    }

    /** 同正文条目是否已存在（幂等去重，对齐她的 "Entry already exists (no duplicate added)"）。 */
    public static boolean isDuplicate(List<String> entries, String body) {
        String b = body == null ? "" : body.trim();
        for (String e : entries) {
            if (MemoryDriftGuard.entryBody(e).equals(b)) {
                return true;
            }
        }
        return false;
    }

    /** 把现存条目抄进错误文案（门禁的"不许把调用方逼进死胡同"那一半）。 */
    public static String inventory(List<String> entries, String label) {
        if (entries == null || entries.isEmpty()) {
            return label + "：（无）—— 这一份现在没有任何条目。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("（共 ").append(entries.size()).append(" 条");
        int shown = Math.min(entries.size(), INVENTORY_MAX);
        if (shown < entries.size()) {
            sb.append("，只列前 ").append(shown);
        }
        sb.append("）：");
        for (int i = 0; i < shown; i++) {
            sb.append("\n  ").append(i + 1).append(". ").append(preview(entries.get(i)));
        }
        sb.append("\n重试时把 old_text 填成上面某条正文里的一段唯一子串。");
        return sb.toString();
    }

    private static MemoryWriteRejectedException reject(String code, int opIndex, String why) {
        return new MemoryWriteRejectedException(code, label(opIndex) + why, opIndex, null);
    }

    private static String label(int opIndex) {
        return opIndex > 0 ? "第 " + opIndex + " 条操作失败：" : "";
    }

    private static String preview(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
