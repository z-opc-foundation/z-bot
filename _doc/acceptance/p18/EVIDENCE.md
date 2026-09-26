# P18 验收 EVIDENCE — 通道注册表 SPI + 飞书/钉钉真出站

- 工作树 `/private/tmp/zbot-wt-p18`，分支 `w4-p18`，起点 `926b8b5`
- 工单 `/Users/zifang/.cache/zbot-p17/dispatch_p18.md`
- 现场/日志根 `~/.cache/zbot-p18/`（`.gitignore:5` = `*.log` ⇒ 决定性读数原样贴本文）

## 0. 第 0 步实测（复算工单假设）

| # | 假设 | 复算命令 | 实测 | 判定 |
|---|---|---|---|---|
| 0-1 | channel 面 16 个 .java | `ls z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java \| wc -l` | `16` | 成立 |
| 0-2 | 合计 5032 行 | `cat z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java \| wc -l` | `5032` | 成立 |
| 0-2b | 头部规模 | `wc -l .../channel/*.java \| sort -rn \| head -8` | `DeliveryLedger 831 / HttpChannel 741 / Supervisor 569 / ChannelBus 397 / TurnLease 339 / TerminalChannel 338 / Gateway 336 / FeishuChannel 331`（DingTalkChannel 265） | 成立；工单没提 DeliveryLedger 才是最大的那个 |
| 0-3 | 飞书/钉钉 0 生产构造点 | `git grep -n "new FeishuChannel\|new DingTalkChannel" -- 'z-bot-core/src/main/java'` | `rc=1`，0 命中 | **成立**（工单②）⇒ 注册表就是给它们接生产线路 |
| 0-3b | 别的通道有构造点 | `git grep -n "new HttpChannel\|new WebhookChannel\|new TerminalChannel" -- 'z-bot-core/src/main/java'` | `cli/GatewayCommand.java:61`（HttpChannel）、`:63`（WebhookChannel）、`cli/ReplCommand.java:23`、`cli/ServeCommand.java:30` | 成立 |
| 0-4 | 出站是 stub | `grep -n "stub" .../channel/FeishuChannel.java`、`.../DingTalkChannel.java`（改动前） | Feishu `:42 没有真发 HTTP 给飞书 / :152 仅日志 / :153 [feishu:stub] / :235 token 永远 static 或 "stub" / :304 URL 只算不发`（工单写 306，实测 304）；DingTalk `:28 / :134 [dingtalk:stub]` | 成立（工单③） |
| 0-5 | okhttp 已在依赖 | `grep -n "okhttp" z-bot-core/pom.xml pom.xml` | `pom.xml:71 <okhttp.version>4.12.0`、`pom.xml:164-166` depMgmt、`z-bot-core/pom.xml:84-85` 直接依赖 | 成立 ⇒ 本期零新依赖 |
| 0-6 | kernel-app 是空模块 | `grep -n "z-agent-kernel" z-bot-core/pom.xml \| head -20`；`unzip -l ~/.m2/repository/io/github/yuku123/z-agent-kernel-app/0.2.1/*.jar \| grep '\.class$'` | 直依赖 12 个 kernel 模块（工单说 11，实测数出 12 行 artifactId），**其中没有 kernel-app**；jar 里只有 `com/zifang/z/agent/kernel/app/package-info.class`（135 字节） | 成立 ⇒ "kernel-app SPI" 不存在，注册表落在 z-bot 这侧，内核仓一个字节未动 |
| 0-7 | z-bot 从无 ServiceLoader | `git grep -n "ServiceLoader" -- 'z-bot-core/src/main'` | `rc=1`，0 命中 | 成立 |
| 0-8 | 无第三方 IM 凭据 | `grep -o '^[a-zA-Z0-9._-]*=' ~/.zbot/config.properties`（只列键名）；`awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties` | 键名只有 `minimax.api.key / minimax.base.url / minimax.model`（3 行）；api.key 值**长度 125**（未打印/未复制/未入日志）；**无任何 feishu/dingtalk 键** | 成立 ⇒ **不做真发验收**，见 §未做 |
| 0-9 | @Test 起点 619 | `git grep -c '@Test' 926b8b5 -- 'z-bot-core/src/test' \| awk -F: '{s+=$NF} END {print s}'` | `sum @Test = 619`（58 个测试文件；`channel/` 下 12 个类） | 成立 |
| 0-10 | BotConfig 与通道配置无关 | `grep -cnE "feishu\|dingtalk" .../config/BotConfig.java`；`grep -n rawProps .../BotConfig.java \| head -1` | `0`；`114:    private Properties rawProps = new Properties();`（私有、无公开 getter） | 成立 ⇒ 通道声明走自己的 manifest；`BotConfig.java` 本期未被修改 |

## 1. 注册表落地形态：**自建声明式 manifest（不是 ServiceLoader）**

落地文件（来源按序合并，后写覆盖）：

- `z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelRegistry.java`（新）
- `z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelConfigException.java`（新）
- `z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpConsoleChannel.java`（新：原 `GatewayCommand` 私有内部类 `HttpChannelAdapter` 提到 `channel/` 下，注册表才产得出 `http` 这一路；`send()` 依旧是刻意空转，语义逐字保留）
- `z-bot-core/src/main/resources/com/zifang/z/bot/channel/channels.builtin.properties`（新：缺省档，四种 kind 的形状；IM 两条 `enabled=false`）
- 用户侧：`<configDir>/channels.properties`（`--config-dir` / `ZBOT_HOME` 指向的数据根）；`--channel-manifest <FILE>` 是第四层
- 装配点：`cli/GatewayCommand.java`、`cli/ServeCommand.java`

| 主张 | 复算命令 | 实测 |
|---|---|---|
| 不用 ServiceLoader、不做反射批量加载（只算代码行，注释里的类名提及不计） | `grep -E "Class\.forName\|ServiceLoader\|getDeclaredConstructor\|java\.lang\.reflect" z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelRegistry.java \| grep -vc '^[[:space:]]*\*'` | `0`（含注释的裸 `grep -c` 是 `1`，就是本类 javadoc 里那句"为什么不用 ServiceLoader"） |
| 惰性：加载 + 查声明都不构造实例 | `ChannelRegistryTest.loadingSpecsConstructsNothing`（量 `registry.createCount()`） | 待填 |
| 后写覆盖三层 | `ChannelRegistryTest.profileManifestOverridesBuiltinAndCliBeatsProfile` | 待填 |
| 接进生产线路（不是 0 消费者抽象） | `grep -rn "new FeishuChannel\|new DingTalkChannel" z-bot-core/src/main/java` | feishu/dingtalk 的唯一构造点在 `ChannelRegistry.java` 的两个工厂体内 |

**为什么不选 ServiceLoader**：provider 注册文件只能落 `src/test/resources/META-INF/services/`，而本期写域只到 `z-bot-core/src/test/**/channel/**` —— 一个"证不了它真被加载过"的 SPI 就是红线 2 的另一个候选。manifest 的加载证据在杠③ 真进程 stdout 里（`通道声明来源: [...]`）。

## 2. 真出站（Feishu token 换取 + 发送 + 错误分类）

| 主张 | 复算命令 | 实测 |
|---|---|---|
| 飞书真发 HTTP（token + send 两处） | `grep -c "http\.newCall" z-bot-core/src/main/java/com/zifang/z/bot/channel/FeishuChannel.java` | `2` |
| 钉钉真发 HTTP | `grep -c "http\.newCall" z-bot-core/src/main/java/com/zifang/z/bot/channel/DingTalkChannel.java` | `1` |
| 两个文件不再有"没真发"的自述 | `grep -n "没有真发" z-bot-core/src/main/java/com/zifang/z/bot/channel/{Feishu,DingTalk}Channel.java` | `rc=1`（0 命中） |
| 编译过 | `mvn -o -q -pl z-bot-core compile`（有无 `[ERROR]` 行） | `rc=0`、无 ERROR |

## 3. 显式降级（缺键必须报出来）

- 复算命令 / 实测：（待填）

## 杠① 单测 ×3 顺序独立

- 复算命令 / 实测：（待填）

## 杠② 变异注入

- 复算命令 / 实测：（待填）

## 杠③ 真进程 E2E

- 复算命令 / 实测：（待填）

## 杠④ 用户数据根未被触碰

- 复算命令 / 实测：（待填）

## §未做

- （待填）
