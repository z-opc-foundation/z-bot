package com.zifang.z.bot.memory;

/**
 * 一次成功落盘的写操作的回执 —— 她的 {@code _success_response()} /
 * {@code _consolidation_failure()} 里那份"带现场状态"的返回体在我层的等价物。
 *
 * <p>存在理由：门禁放行之后，调用方（模型或人）还得能<b>不回读文件就</b>知道自己写成了什么 ——
 * 条目数从几变到几、整份现在多少字符、预算还剩多少、是不是撞了去重没写。
 * 只回"已写入"三个字等于让调用方自己猜，猜错就一直重复追加。</p>
 */
public final class MemoryReceipt {

    private final MemorySection section;
    private final int entriesBefore;
    private final int entriesAfter;
    private final int charCountAfter;
    private final int charLimit;
    private final int opsApplied;
    private final boolean duplicateSkipped;
    private final boolean snapshotTaken;
    private final String backupPath;

    MemoryReceipt(MemorySection section, int entriesBefore, int entriesAfter, int charCountAfter,
                  int charLimit, int opsApplied, boolean duplicateSkipped, boolean snapshotTaken,
                  String backupPath) {
        this.section = section;
        this.entriesBefore = entriesBefore;
        this.entriesAfter = entriesAfter;
        this.charCountAfter = charCountAfter;
        this.charLimit = charLimit;
        this.opsApplied = opsApplied;
        this.duplicateSkipped = duplicateSkipped;
        this.snapshotTaken = snapshotTaken;
        this.backupPath = backupPath;
    }

    public MemorySection section() {
        return section;
    }

    public int entriesBefore() {
        return entriesBefore;
    }

    public int entriesAfter() {
        return entriesAfter;
    }

    public int charCountAfter() {
        return charCountAfter;
    }

    public int charLimit() {
        return charLimit;
    }

    /** 真正落盘的操作条数（批量用；单条写为 1，去重跳过为 0）。 */
    public int opsApplied() {
        return opsApplied;
    }

    /** 同正文条目已存在 ⇒ 本次没写（幂等，不算失败）。 */
    public boolean duplicateSkipped() {
        return duplicateSkipped;
    }

    /**
     * 这次写入<b>动手之前</b>是否真的做过原字节快照（覆盖已有文件 ⇒ true）。
     * 这是"失败可还原"的前提被成立的唯一可观察面：快照没做，还原就是空话。
     */
    public boolean snapshotTaken() {
        return snapshotTaken;
    }

    /**
     * 写成功后快照只是保险、不是证据 ⇒ 当场清掉，回执里为 {@code null}。
     * 非空只出现在"证据被特意留下"的场合（写失败抛的是
     * {@link MemoryWriteRejectedException}，它自己带 {@code bakPath}）。
     */
    public String backupPath() {
        return backupPath;
    }

    public int freeChars() {
        return Math.max(0, charLimit - charCountAfter);
    }

    /** 给模型/人看的一行状态。 */
    public String usage() {
        return section.fileName() + " 条目 " + entriesBefore + "→" + entriesAfter
                + "，" + charCountAfter + "/" + charLimit + " 字符（剩 " + freeChars() + "）";
    }

    @Override
    public String toString() {
        return (duplicateSkipped ? "duplicate-skipped; " : "") + usage();
    }
}
