# P24 待接线清单（写手第 10 棒 → 主编亲手下）

规矩：我**一字未改** `agent/BotAgent.java` 与 `config/BotConfig.java`（p14/p23/p26 三支在里面写）。
下面是我需要它们变的原样 hunk：**行号按基线 `main = 53222e1` 计**（`git show HEAD:<path>` 的第 N 行），
上下文前后各 3 行照抄，集成时的行号请以你那次 rebase 后的实际位置为准。

## 1. `z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java` :1855—1861 —— 把预算接到 config 上

现状（`git show HEAD:…` 第 1855—1861 行）：

```java
                File childSessions = config != null && config.getConfigDir() != null
                        ? new File(config.getConfigDir(), "delegate/children")
                        : new File(sandbox.root().getParentFile(), "delegate-children");
                delegation = new DelegateManager(config, raw, sandbox, childSessions, d, maxDepth);
                toolkit.register(delegation.delegateTool());
            }
            if (memoryStore == null && config != null && config.getConfigDir() != null) {
```

请改成（只动 `:1858` 那一行的赋值，其余三行是上下文）：

```java
            if (memoryStore == null && config != null && config.getConfigDir() != null) {
                memoryStore = new MemoryStore(new File(config.getConfigDir(), "memories"),
                        config.getMemoryCharLimit(), config.getUserCharLimit());
            }
```

前置：`config/BotConfig.java` 需要两个 getter（我无权加，也没替它编实现）：

```java
    /** `memory.char.limit`，缺省 2200（对齐 hermes MemoryStore(memory_char_limit=2200)）。 */
    public int getMemoryCharLimit() {
        return intProp("memory.char.limit", 2200);
    }

    /** `user.char.limit`，缺省 1375（对齐 hermes MemoryStore(user_char_limit=1375)）。 */
    public int getUserCharLimit() {
        return intProp("user.char.limit", 1375);
    }
```

**`intProp(String, int)` 这个读取器在 `BotConfig` 里叫什么我没有实测**（该文件在我的禁改区，
本期没读它），请换成该文件里既有的整型读法；若你判定"预算不该开放配置"，
那就不接——`MemoryStore(File)` 单参构造器现在就是 2200 / 1375 缺省，功能不缺，只是少一个配置位。

## 2. 同文件 :1030—1059 `memoryManage(String)` —— 给漂移证据一个人能看见的出口

现状（第 1030—1042 行，前 3 行 + 后 3 行上下文）：

```java
    /** {@code /memory [user|pending|forget [user]]} — 查看记忆三层 / 审批状态 / 清空。 */
    public String memoryManage(String args) {
        if (memoryStore == null) {
            return "未启用本地记忆";
        }
        String a = args == null ? "" : args.trim();
        boolean user = a.toLowerCase().contains("user");
        if (a.toLowerCase().startsWith("pending")) {
            ToolCall p = pendingConfirmation;
            return p == null ? "没有待审批的操作"
                    : "待审批: " + p.getName() + " " + p.getArgumentsJson() + "（/confirm 放行）";
        }
```

请在 `pending` 那一段之后插入一条分支（**这一条是本期唯一"不做就没人能看见证据"的接线**：
门禁拒写时留下的 `MEMORY.md.bak.<毫秒>` 只有模型在报错文案里看得到一次，
人侧 `/memory` 现在没有任何入口，快照会静静堆在 `~/.zbot/memories/` 里）：

```java
        if (a.toLowerCase().startsWith("drift")) {
            File[] all = memoryStore.getDir().listFiles();
            StringBuilder sb = new StringBuilder("记忆目录: " + memoryStore.getDir());
            if (all != null) {
                java.util.Arrays.sort(all);
                for (File f : all) {
                    if (f.getName().contains(".bak.")) {
                        sb.append("\n  ").append(f.getName()).append("  ")
                          .append(f.length()).append(" 字节");
                    }
                }
            }
            return sb.toString();
        }
```

顺带把方法上方那行 javadoc 的命令签名改成
`/memory [user|pending|drift|forget [user]]`（两处：本方法注释 + `/memory` 在命令帮助表里的条目；
帮助表的位置我没实测，禁改区里没去找）。

## 3. 同文件 :1866 —— **不需要改**（负向结论，省你一次核对）

```java
                toolkit.register(MemoryTools.memoryTool(memoryStore));
```

`MemoryTools.memoryTool(MemoryStore)` 签名一字未动，新增的 `replace/remove/batch` 三个 action
走的是同一个工具的 `action` 参数（schema 里加了 `old_text` / `operations` 两个可选字段），
所以注册点、审批挂起（`Confirmations`）路径都不需要跟着改。
基线那 4 支 `MemoryStoreTest` 与 `agent` 包里 3 个碰记忆的测试类（`BotAgentMemoryTest`
`SystemPromptCacheFreezeTest` `VolatileContextPersistenceTest`）本期未改，仍全绿。
