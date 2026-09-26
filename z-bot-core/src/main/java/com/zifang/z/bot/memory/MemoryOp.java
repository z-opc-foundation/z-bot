package com.zifang.z.bot.memory;

/**
 * 一条条目级写操作 —— 对齐 hermes {@code apply_batch()} 吃的
 * {@code {"action": "add|replace|remove", "content": …, "old_text": …}}。
 *
 * <p>不可值对象：只装"要干什么"，不含任何盘上状态。校验一律在
 * {@link MemoryWriteGate} 里做，批量路径靠这个结构先把整组校验完再落盘（要么全落要么全不落）。</p>
 */
public final class MemoryOp {

    /** 操作种类。 */
    public enum Kind {
        /** 追加一条新条目。 */
        APPEND,
        /** 把命中 {@code oldText} 的那一条换成新正文（改已有条目）。 */
        REPLACE,
        /** 删掉命中 {@code oldText} 的那一条。 */
        REMOVE;

        /** 她那侧的动作名（{@code add}/{@code replace}/{@code remove}）；未知 ⇒ null。 */
        public static Kind of(String action) {
            if (action == null) {
                return null;
            }
            String a = action.trim().toLowerCase();
            if ("add".equals(a) || "append".equals(a)) {
                return APPEND;
            }
            if ("replace".equals(a) || "update".equals(a)) {
                return REPLACE;
            }
            if ("remove".equals(a) || "delete".equals(a)) {
                return REMOVE;
            }
            return null;
        }
    }

    private final Kind kind;
    private final String content;
    private final String oldText;

    public MemoryOp(Kind kind, String content, String oldText) {
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        this.kind = kind;
        this.content = content == null ? "" : content.trim();
        this.oldText = oldText == null ? "" : oldText.trim();
    }

    public static MemoryOp append(String content) {
        return new MemoryOp(Kind.APPEND, content, null);
    }

    public static MemoryOp replace(String oldText, String content) {
        return new MemoryOp(Kind.REPLACE, content, oldText);
    }

    public static MemoryOp remove(String oldText) {
        return new MemoryOp(Kind.REMOVE, null, oldText);
    }

    public Kind kind() {
        return kind;
    }

    /** 新正文（APPEND / REPLACE 用）。 */
    public String content() {
        return content;
    }

    /** 定位已有条目用的短唯一子串（REPLACE / REMOVE 用）。 */
    public String oldText() {
        return oldText;
    }

    /** 改已有条目的操作（REPLACE / REMOVE）：缺 {@code old_text} 必须报错，不许静默新建。 */
    public boolean targetsExistingEntry() {
        return kind == Kind.REPLACE || kind == Kind.REMOVE;
    }

    @Override
    public String toString() {
        return kind + "(old_text=" + oldText + ", content=" + content + ")";
    }
}
