package com.zifang.z.bot.memory;

/**
 * 身份三件套 —— 本地长期记忆层里那三份文件的"身份"，一份一个枚举常量。
 *
 * <p>对齐 hermes {@code tools/memory_tool.py} 的 {@code _path_for()}：她只有
 * {@code MEMORY.md} / {@code USER.md} 两份可写清单 + 一份人格，我们把三份都纳进契约，
 * 因为 {@code SOUL.md} 在 z-bot 里是<b>进 system prompt 骨架</b>的那一份
 * （见 {@code agent/BotAgent#buildSystemPrompt}），它的写入形状与前两份不同：</p>
 *
 * <table border="1">
 *   <tr><th>常量</th><th>文件</th><th>形状</th><th>字符预算</th></tr>
 *   <tr><td>{@link #MEMORY}</td><td>MEMORY.md</td><td>条目清单</td><td>2 200</td></tr>
 *   <tr><td>{@link #USER}</td><td>USER.md</td><td>条目清单</td><td>1 375</td></tr>
 *   <tr><td>{@link #SOUL}</td><td>SOUL.md</td><td>整页散文</td><td>4 096</td></tr>
 * </table>
 *
 * <p>预算值照抄她的缺省（{@code MemoryStore(memory_char_limit=2200, user_char_limit=1375)}），
 * 不是我们自己拍的。条目清单的写入形状是"一行一条 {@code - [时间戳] 正文}"，
 * 只有这种形状能通过 {@link MemoryDriftGuard} 的往返校验；整页散文不做条目往返校验
 * （它是漂移发生时的<b>补救通道</b>，见 {@link MemoryStore#rewritePage}）。</p>
 */
public enum MemorySection {

    /** agent 主动积累的长期记忆。 */
    MEMORY("MEMORY.md", Shape.ENTRY_LIST, 2200),

    /** 用户画像 / 偏好。 */
    USER("USER.md", Shape.ENTRY_LIST, 1375),

    /** 人格文件：缺省生成、可手编、注入 system prompt 头部。 */
    SOUL("SOUL.md", Shape.PAGE, 4096);

    /** 写入形状。 */
    public enum Shape {
        /** 一行一条目的清单，能被解析器往返（漂移可判）。 */
        ENTRY_LIST,
        /** 整页散文，不做条目往返漂移判定。 */
        PAGE
    }

    private final String fileName;
    private final Shape shape;
    private final int charLimit;

    MemorySection(String fileName, Shape shape, int charLimit) {
        this.fileName = fileName;
        this.shape = shape;
        this.charLimit = charLimit;
    }

    public String fileName() {
        return fileName;
    }

    public Shape shape() {
        return shape;
    }

    /** 该份的字符预算：整份文件的渲染长度不得超过它（她的 char_limit 等价物）。 */
    public int charLimit() {
        return charLimit;
    }

    public boolean isEntryList() {
        return shape == Shape.ENTRY_LIST;
    }

    /** 按文件名反查身份；不是三件套之一 ⇒ null（调用方负责拒绝）。 */
    public static MemorySection ofFileName(String name) {
        for (MemorySection s : values()) {
            if (s.fileName.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 她那侧的 target 名（"memory" / "user"）到我们身份的映射；未知 ⇒ null。 */
    public static MemorySection ofTargetName(String target) {
        if (target == null) {
            return null;
        }
        String t = target.trim().toLowerCase();
        if (t.isEmpty() || "memory".equals(t) || "memories".equals(t)) {
            return MEMORY;
        }
        if ("user".equals(t) || "profile".equals(t)) {
            return USER;
        }
        if ("soul".equals(t) || "persona".equals(t)) {
            return SOUL;
        }
        return null;
    }
}
