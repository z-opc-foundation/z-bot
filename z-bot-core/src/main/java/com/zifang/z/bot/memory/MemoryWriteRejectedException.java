package com.zifang.z.bot.memory;

/**
 * 一次被门禁<b>拒绝</b>的记忆写入。
 *
 * <p>三个字段就是本期契约里"不许静默"的三件凭证：</p>
 * <ul>
 *   <li>{@link #code()} —— 机读原因码（{@link #SCAN_HIT} / {@link #MISSING_OLD_TEXT} /
 *       {@link #NO_MATCH} / {@link #AMBIGUOUS_MATCH} / {@link #OVER_BUDGET} /
 *       {@link #EXTERNAL_DRIFT} / {@link #WRITE_UNVERIFIED} / {@link #EMPTY_BATCH}），
 *       调用方按码分支，不许去 match 人话文案；</li>
 *   <li>{@link #opIndex()} —— 批量写里第几条出的事（<b>1 起算</b>，非批量 ⇒ 0）。
 *       对齐她的 {@code "Operation {i+1}"} 前缀：批量要么全落要么全不落，
 *       但必须指名是哪一条，否则模型无从重试；</li>
 *   <li>{@link #bakPath()} —— 漂移/半写时留在盘上的取证快照路径（无备份 ⇒ null）。
 *       对齐她的 {@code _drift_error(path, bak_path)}：拒绝写入的同时必须告诉人
 *       "外部内容我替你保住了，在哪"。</li>
 * </ul>
 *
 * <p>继承 {@link IOException}：本层所有写通道本来就 {@code throws IOException}，
 * 这样门禁拒绝不会被调用方当成"不用处理"的 RuntimeException 漏掉。</p>
 */
public class MemoryWriteRejectedException extends java.io.IOException {

    private static final long serialVersionUID = 1L;

    /** 内容扫描命中注入/外传特征。 */
    public static final String SCAN_HIT = "scan_hit";
    /** 改/删已有条目却没给 old_text。 */
    public static final String MISSING_OLD_TEXT = "missing_old_text";
    /** old_text 在现有条目里一条都没命中。 */
    public static final String NO_MATCH = "no_match";
    /** old_text 命中多条<b>互不相同</b>的条目 —— 不许猜哪一条。 */
    public static final String AMBIGUOUS_MATCH = "ambiguous_match";
    /** 写入后整份超预算。 */
    public static final String OVER_BUDGET = "over_budget";
    /** 盘上内容与本层解析器不往返 ⇒ 外部改过，拒绝覆盖。 */
    public static final String EXTERNAL_DRIFT = "external_drift";
    /** 写通道报错（半写/磁盘不可写/临时文件被占）。 */
    public static final String WRITE_FAILED = "write_failed";
    /** 落盘后回读字节与待写字节不一致（半写/被截/通道说谎）。 */
    public static final String WRITE_UNVERIFIED = "write_unverified";
    /** 批量里一条操作都没有。 */
    public static final String EMPTY_BATCH = "empty_batch";
    /** 该份身份不接受这个操作（如对 SOUL 做条目级 replace）。 */
    public static final String BAD_OPERATION = "bad_operation";
    /** 写内容为空。 */
    public static final String EMPTY_CONTENT = "empty_content";

    private final String code;
    private final int opIndex;
    private final String bakPath;

    public MemoryWriteRejectedException(String code, String message) {
        this(code, message, 0, null);
    }

    public MemoryWriteRejectedException(String code, String message, int opIndex, String bakPath) {
        super(message);
        this.code = code;
        this.opIndex = opIndex;
        this.bakPath = bakPath;
    }

    public String code() {
        return code;
    }

    /** 1 起算的失败条号；非批量 ⇒ 0。 */
    public int opIndex() {
        return opIndex;
    }

    /** 盘上取证快照路径；没有备份 ⇒ null。 */
    public String bakPath() {
        return bakPath;
    }

    /** 消息里是否带了指失败条号（门禁"指名第几条"的可断言面）。 */
    public boolean namesOperation() {
        return opIndex > 0 && getMessage().contains("第 " + opIndex + " 条");
    }
}
