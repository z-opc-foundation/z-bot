# z-bot

> 本地 agent 应用（Claude Code / hermes-agent 同型）：终端 CLI（repl 与一次性提问）+ HTTP/SSE web 控制台
> + 常驻多通道 gateway（HTTP / webhook / 飞书 / 钉钉），带工具沙箱、SQLite 会话库、技能与 MCP/ACP 接入，
> runtime 走 `z-agent-kernel`。

它解决的是"我这台机器上有一个能长期干活的 agent"：模型/key 归你自己的 profile，会话与台账落在本地
`state.db`，命令在沙箱里执行并走审批闸门；对外它说四种面 —— 终端、HTTP/SSE（含内置 web 控制台）、
MCP（既是 client 也能 `mcp serve` 反当 server）、ACP（IDE 走 stdio 接入）。
**它不是一等 Spring 服务，也不是可嵌入库**：入口是 picocli 的 `main()`，HTTP 面自己起 JDK
`com.sun.net.httpserver`，数据根是自己独占的 profile 目录（进程形态与并机约束见下文）。

工程口径与逐期实测读数见 [`_doc/001_arch/hermes-roadmap.md`](_doc/001_arch/hermes-roadmap.md)。

---

## 📋 基本信息

| 项 | 值 | 怎么自己验一遍 |
|---|---|---|
| 仓库 | `z-bot`（本地 agent 应用；`z-bot-core` 是唯一有代码的模块） | `ls -d */` |
| Maven 坐标 | `io.github.yuku123:z-bot`（聚合 `pom`）/ `:z-bot-core`（`jar`，shade 件）/ `:z-bot-desktop-packager`（`pom`） | `grep -n '<artifactId>\|<packaging>' pom.xml */pom.xml` |
| 当前源码版本 | `0.2.1`（根 POM `<revision>`，唯一真源；CI-friendly versions + `flatten-maven-plugin` 1.7.2 `flattenMode=oss`。源码侧的自报版本面值集中在 `z-bot-core/src/main/java/com/zifang/z/bot/BuildInfo.java` 的 `REVISION` 一格，其余各处一律从它派生；两处没对齐由 `BuildInfoDriftTest` 判红，三支具名变异验过牙） | `grep -n '<revision>' pom.xml` |
| 父项目 | `io.github.yuku123:z-boot-parent:1.0.21`（`<relativePath/>` 留空，parent 在 repo1 不在磁盘；它自己的 `<parent>` 是 `z-boot-dependencies:1.0.20`） | `grep -n -A4 '<parent>' pom.xml` |
| Central 实测状态 | **对外可见的仍是 `0.2.0`**：`z-bot:0.2.0.pom` / `z-bot-core:0.2.0.pom` / `z-bot-core:0.2.0.jar` / `:sources.jar` / `:javadoc.jar` / `z-bot-desktop-packager:0.2.0.pom` 均 200（6/6）；三个坐标的 `maven-metadata.xml` 的 `latest`/`release` 都是 `0.2.0`，`versions` 各为 `[0.1.0, 0.2.0]`，`lastUpdated` = `20260929022811`（z-bot-core）/`20260929022812`（另两坐标）。`0.1.0` 同样在架；没有 `0.3.0`。**`0.2.1` 已抬号但还没 deploy**（2026-10-01 19:4x 现读：3 个 reactor 坐标下的 `z-bot-0.2.1.pom` / `z-bot-core-0.2.1.pom` / `z-bot-core-0.2.1.jar` / `z-bot-desktop-packager-0.2.1.pom` 全 404 ⇒ 0/4，同一次运行里 `0.2.0` 侧读到 6/6=200 作阳性对照） | `curl -s https://repo1.maven.org/maven2/io/github/yuku123/z-bot-core/maven-metadata.xml` |
| 内核 pin | `z-agent-kernel.version=0.2.1`（本仓按坐标写 12 条直接 DM 顶住 fleet 的 0.1.1；repo1 实测 12 件 `0.2.1` 全部 200 ⇒ 已可解析） | `grep -n 'z-agent-kernel.version' pom.xml` |
| 默认端口 | http 控制台 `8080` · webhook `8090` · 飞书 `9101` · 钉钉 `9102`；**缺省只绑 `127.0.0.1`** | `cat z-bot-core/src/main/resources/com/zifang/z/bot/channel/channels.builtin.properties` |
| 运行口径 | Java 8（`maven.compiler.source/target=8` 由 `z-boot-parent:1.0.21` 下发，本仓不重抄）· **不引 Spring** | `curl -s https://repo1.maven.org/maven2/io/github/yuku123/z-boot-parent/1.0.21/z-boot-parent-1.0.21.pom \| grep maven.compiler` |
| 模块 | 2 支：`z-bot-core`（全部代码与测试）、`z-bot-desktop-packager`（jpackage 配置，无 `src`） | `grep -n '<module>' pom.xml` |
| 最近更新 | 2026-09-30 | — |

---

## 🎯 能力清单（每条都对着代码）

| 能力 | 落点 | 说明 |
|---|---|---|
| ReAct 循环与预算 | `agent/BotAgent.java`、`agent/BudgetLedger.java` | 迭代/令牌预算、`/steer` 中途改写、中断收口 |
| 终端 TUI | `ui/TerminalUI.java`、`ui/LineEditor.java`（JLine）、`ui/RawTerminalReader.java`、`ui/MarkdownRenderer.java`、`ui/PasteFolder.java` | ↑↓ 翻历史、Tab 补全、raw 模式与 `stty` 还原 |
| 一次性提问 / 脚本化 | `cli/ChatCommand.java`、`cli/SendCommand.java` | `z-bot "…"` 等价 `chat`；`send` 把消息外发给某个 channel |
| HTTP / SSE 与 web 控制台 | `channel/HttpChannel.java` + `src/main/resources/web/index.html` | 26 行路由台账见下文；`POST /bot/chat/stream` 每个 ReAct 步骤一帧 |
| 常驻多通道 gateway | `channel/Gateway.java`、`Supervisor`、`TurnLease`、`DeliveryLedger`、`DeadTargets`、`PairingService` | 租约 + 送达台账 + 投递目标失效兜底；`--pairing` 起配对码授权 |
| IM 通道 | `channel/FeishuChannel.java`、`DingTalkChannel.java`、`WebhookChannel.java`、`HttpConsoleChannel.java` | 四种内置 kind；IM 通道**缺省 `enabled=false`**，凭据不随包发 |
| 会话库 | `store/StateStore.java`、`store/SchemaMigrations.java`、`session/SessionManager.java` | SQLite（WAL）；`requiredTables()` 8 张必检表：`sessions`/`messages`/`session_model_usage`/`async_delegations`/schema_version/state_meta/compression_locks/gateway_routing；`sessions search` 走 FTS5，不可用则降级 LIKE |
| 工具与沙箱 | `tool/BuiltinTools.java`、`Sandbox`、`ExecGuard`、`ApprovalService`、`Toolkit`、`Toolsets` | 内建 14 支：`echo` `message` `time` `counter` `health` `sysinfo` `read_file` `write_file` `search` `exec` `mvn_build` `curl_test` `memory` `cronjob`（+`delegate_task`）；沙箱根缺省 `<profile>/workspace` |
| 执行后端 SPI | `tool/env/LocalExecEnvironment.java`、`SshExecEnvironment`、`DockerEngineClient`、`ProcessTree` | `exec.env.*` 一族键；`ZBOT_EXEC_ENV_BACKEND` / `DOCKER_HOST` 可覆盖；JDK 8 下进程树走 `ps -eo pid=,ppid=` 快照（`5985a10`） |
| 子代理委托 | `delegate/DelegateManager.java`、`SummaryBudget`、`DelegationLedger` | 深度/宽度/摘要裁切三个缺省见「配置」节；子代理撞审批闸门当场 `[auto-denied]` |
| 上下文压缩 | `context/CompressorEngine.java`、`CompressionGate`、`CompressionLedger` | 超窗按 `contextWindow` 触发，压缩台账落库 |
| 记忆与身份 | `memory/MemoryStore.java`、`MemoryWriteGate`、`MemoryDriftGuard`、`MemoryTools` | `<profile>/memories/*.md`（`SOUL.md` / `MEMORY.md`）；写入门禁 + 漂移守卫 |
| 技能体系 | `skill/SkillLoader.java`、`Frontmatter`、`SkillCommands`、`SkillGuard`、`SkillSync` | frontmatter 解析、技能→斜杠命令、从认证中心 sync（`origin_hash`） |
| MCP（双向） | `mcp/McpClientFactory.java`、`StreamableHttpMcpTransport`、`ZBotStdioMcpTransport`、`ZBotMcpServe`、`SecretRedaction` | client 两种 transport：`stdio` 与 `http`（StreamableHTTP/SSE），未知值直接抛不静默降级；`z-bot mcp serve` 反向把 z-bot 当只读 MCP server；出站文本脱敏 |
| ACP（IDE 面） | `acp/AcpAgentServer.java`、`StdioAcpTransport`、`AcpApprovalBridge`、`AcpStreamPublisher` | stdio JSON-RPC；`available_commands_update` 帧从命令单源派生 |
| 定时任务 | `cron/CronScheduler.java`、`CronTools`、`ChannelCronDelivery`、`LocalCronDelivery` | `cronjob` 工具 + `<profile>/cron`，投递可指到 outbound 通道 |
| LLM 韧性 | `llm/LlmRouter.java`、`KeyPoolLlmProvider`、`ResilientLlmProvider`、`JitteredBackoff`、`StreamStaleWatchdog`、`ModelCatalogCache`、`ModelUsage` | 多 key 凭据池、429/超时重试、fallback 链、流式陈旧看门狗、模型目录缓存 `<profile>/models-cache.json` |
| 认证中心接入 | `center/BotCenterClient.java`、`BotLifecycle` | `center.url` / `center.app.code`；`POST /api/agent/register` 是它的对偶入口 |
| 命令面单源 | `slash/CommandCatalog.java`、`SlashRegistry` | 见「命令面只有一份源」 |

---

## 🧱 进程形态：它不能塞进别人的 JVM（2026-09 中间件并机时撞到的约束）

组织要把中间件收到一台机器/一个进程里时，z-bot 这一格**不能按 starter 嵌进宿主应用**，四条实测依据：

1. **入口是 CLI 而不是装配**：`ZBot.main()` 走 picocli 后 `System.exit(...)`
   （`z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java`）—— 进程退出权在 z-bot 手里，
   当库嵌进去等于把 `System.exit` 交给一个依赖；`repl` 还要接管 tty（raw 模式 + `stty` 备份还原）。
2. **中央在架的那个 jar 就是 uber jar**：shade 在 `package` 阶段执行、`createDependencyReducedPom=false`、
   **不做 relocation**。实测 `target/z-bot-core.jar` = 20,594,265 B / 4,509 条目，而未 shade 的
   `target/original-z-bot-core.jar` 只有 1,004,488 B；包内自带副本
   `kotlin/` 1,263、`com/fasterxml/` 942、`org/jline/` 503、`okhttp3/` 346、`picocli/` 228、
   `org/sqlite/` 176、`okio/` 116、`org/slf4j/` 49 —— 自家代码 `com/zifang/` 只占 591 条目。
   repo1 上的 `z-bot-core-0.2.0.jar` 是 20,591,303 B，同一个形状。请进宿主 JVM 就是同批类两份定义，
   谁先加载取决于 classpath 顺序。
3. **版本口径与宿主不同**：本仓刻意把 jackson 整族钉在 `2.13.5`（三条按坐标写的 DM 条目），
   而地板 `z-boot-dependencies:1.0.20` 的 `jackson.version` 是 `2.18.6`（repo1 实测）。
4. **它没有 Spring 接入点**：`z-bot-core/src/main/resources` 只有 `web/index.html` 与
   `channel/channels.builtin.properties`，**没有** `META-INF/spring.factories` / `AutoConfiguration.imports`；
   主源码里 `org.springframework` 命中 0 处（`grep -rl "org.springframework" z-bot-core/src/main/java | wc -l` ⇒ 0）。
   HTTP 面自己 `new HttpServer` 并占端口（8080/8090/9101/9102），持久化是本地文件库 `state.db`。

⇒ 并机方案只能按"**一个 z-bot = 一个独立进程 + 一个独立 profile 数据根**"来排：
`java -jar z-bot-core.jar gateway`，宿主侧要接它只能走它对外说的那两面（HTTP/SSE，或 MCP/ACP over stdio）。
另外 `z-boot-fleet` 里 `z-bot.version` 那格滞后（2026-10-01 19:1x 现读：`z-boot-parent:1.0.21` 导入
`z-boot-fleet:1.0.1`，其中 `z-bot.version=0.2.0`，而本仓已抬到 `0.2.1` ⇒ 对外发布后仍滞后一格），所以任何引 z-bot 的工程
都得像本仓一样按坐标把面值写死，否则会被 fleet 反向压成旧字节码。

---

## 🏗️ 项目结构

```
z-bot/
├── pom.xml                        # 聚合 POM：z-boot-parent:1.0.21 + <revision>0.2.1</revision> + 12 条 kernel 直接 DM
├── LICENSE                        # MIT
├── z-bot-core/                    # 唯一有代码的模块（jar / shade）
│   ├── pom.xml                    # finalName=z-bot-core，shade 3.6.0，mainClass=com.zifang.z.bot.ZBot
│   └── src/
│       ├── main/java/com/zifang/z/bot/
│       │   ├── ZBot.java          # picocli 根命令（10 支顶层子命令）
│       │   ├── acp/               # ACP agent 面（stdio JSON-RPC、审批桥、流发布）
│       │   ├── agent/             # BotAgent（ReAct 循环）、预算台账、中断作用域
│       │   ├── center/            # BotCenterClient（认证中心接入）
│       │   ├── channel/           # Channel SPI + Gateway + 四内置通道 + InboundLimits
│       │   ├── checkpoint/        # CheckpointManager
│       │   ├── cli/               # 11 个文件 = 10 支子命令 + AgentOptions mixin
│       │   ├── config/            # BotConfig（profile 根解析与全部配置键）
│       │   ├── context/           # 压缩引擎与台账
│       │   ├── cron/              # 调度与投递
│       │   ├── delegate/          # 子代理委托与摘要预算
│       │   ├── llm/               # 路由、凭据池、重试、模型目录缓存
│       │   ├── mcp/               # MCP client（stdio/http）与反向 mcp serve
│       │   ├── memory/            # 记忆面（写入门禁、漂移守卫）
│       │   ├── session/           # 会话装配与消息编解码
│       │   ├── skill/             # 技能装载、frontmatter、命令覆盖
│       │   ├── slash/             # CommandCatalog（命令面单一真源）
│       │   ├── store/             # StateStore / SchemaMigrations / SqliteTx / DbRecovery
│       │   ├── tool/              # BuiltinTools / Sandbox / ExecGuard / ApprovalService / env/
│       │   └── ui/                # 终端 UI（JLine 与 raw 两条输入实现）
│       └── main/resources/
│           ├── web/index.html                                   # 内置控制台
│           └── com/zifang/z/bot/channel/channels.builtin.properties
├── z-bot-desktop-packager/        # 只有 pom.xml（无 src、无 target、无 dist）
└── _doc/                          # 见文末「文档目录」
```

实测体量（今日工作树）：主源码 `z-bot-core/src/main/java` **144 个 `.java` / 42,341 行**；
测试 `src/test` **129 个 `.java` / 38,036 行**（其中 `*Test.java` 116 支，其余 13 支是驱动/探针）。
复算：`find z-bot-core/src/main/java -name '*.java' | wc -l`。

仓里**没有** `Dockerfile`、`docker-compose*.yml`、`deploy/`、`k8s/`、`Makefile`（`find . -maxdepth 2` 实测无命中），
也没有 `frontend` 工程 —— 控制台是打进 jar 的单文件 `web/index.html`。

---

## 🔧 技术栈

| 层级 | 技术（版本全部来自 pom 实测） |
|---|---|
| 语言 / 运行时 | Java 8（`z-boot-parent:1.0.21` 下发 `maven.compiler.source/target=8`，class major 52） |
| 框架 | **无 Spring**；runtime 是 `z-agent-kernel-*:0.2.1` 的 12 件（llm / message / types / tool / memory / event / middleware / workspace / agent / formatter / mcp / credential） |
| CLI | picocli `4.7.6`（面值写在模块 pom，地板与 fleet 都不管 `info.picocli`） |
| 终端 | JLine `3.24.1`（父链不供这个坐标，本仓留属性） |
| HTTP 服务端 | JDK 内置 `com.sun.net.httpserver.HttpServer` |
| HTTP 客户端 | OkHttp（受管，地板面值 `4.12.0`） |
| JSON | Jackson 本仓钉 `2.13.5`（databind/core/annotations 三条按坐标写死；地板是 `2.18.6`） |
| 持久化 | SQLite — `org.xerial:sqlite-jdbc:3.41.2.2`（3.41.x 是最后一条支持 Java 8 的线） |
| 日志 | slf4j-api + `slf4j-simple`（`runtime` + `optional`，避免 NOP warning 也不污染下游绑定） |
| 桌面打包 | `org.panteleyev:jpackage-maven-plugin:1.5.2`（需本机 JDK 14+ 的 `jpackage`） |
| 构建 | Maven（CI-friendly `${revision}` + flatten `oss`）；发布走根 pom 的 `central` profile（source/javadoc/gpg + `central-publishing-maven-plugin:0.8.0`，`autoPublish=true`） |

---

## 🚀 快速开始

### 编译

```bash
mvn -o -DskipTests package      # 产出 z-bot-core/target/z-bot-core.jar（shade 件，4,509 条目 / ~20.6 MB）
```

第三方版本由 `z-boot-parent:1.0.21` → `z-boot-dependencies`（地板）+ `z-boot-fleet`（兄弟仓权威表）供给；
本仓只有 kernel 12 件、jackson 3 件、jline、picocli、sqlite-jdbc 是刻意按坐标写的面值。若构建报找不到 parent，
先确认本机/镜像能解析到 repo1 上的 `io.github.yuku123:z-boot-parent:1.0.21`。

### 跑起来

```bash
java -jar z-bot-core/target/z-bot-core.jar repl                    # 交互式 TUI（↑↓ 翻历史，/help 看命令）
java -jar z-bot-core/target/z-bot-core.jar "用一句话解释 MVCC"      # 一次性提问，不进 repl
java -jar z-bot-core/target/z-bot-core.jar serve --port 8080        # HTTP + SSE + web 控制台
java -jar z-bot-core/target/z-bot-core.jar gateway --webhook-port 8090   # 多通道常驻
# 浏览器开 http://127.0.0.1:8080/
```

顶层子命令共 10 支：`chat`、`repl`（别名 `interactive`）、`serve`、`gateway`、`status`、
`sessions`、`send`、`pair`、`mcp`、`acp`。名单以 `ZBot.java` 的 `subcommands` 为准，
复算：`sed -n '39,42p' z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java`。
`--help` / `--version` 由 picocli 的 `mixinStandardHelpOptions` 提供（`--version` 打的是 `z-bot 0.2.1`，
面值不在 `ZBot.java` 里手写，取自 `BuildInfo.CLI_VERSION`；与 `<revision>` 的绑定由 `BuildInfoDriftTest` 钉住）。
`sessions` 还有二级动词：`list` / `search` / `export`（JSONL）/ `stats` / `prune` / `version` / `lineage` / `end` / `archive`。

### profile 与数据根（结构性红线 1）

代码与数据分离：profile 目录里只放数据与用户配置，仓库里不放。根目录解析优先级
`-Dzbot.home` > 环境变量 `ZBOT_HOME` > `~/.zbot`（`BotConfig.defaultConfigDir()` 是唯一实现，
`--config-dir <目录>` 是同一件事的显式写法，多 profile / CI / 测试都用它）。

一个填满的 profile 目录是这 8 项（验收基线，见 roadmap §5 与 `_doc/005_testing/acceptance/*/EVIDENCE.md` 的杠④读数：
`config.properties`、`state.db`、`sessions/`、`memories/`、`cron/`、`workspace/`、`models-cache.json`、
`.stty.bak`）；此外按用法再长出 `skills/`、`delegate/summaries/`、`channels.properties`、`pairing.json`。
注意：本机今天**没有** `~/.zbot`（`test -d ~/.zbot` ⇒ 假），那 8 项是跑过之后的形态，不是新装机的既有清单。

**所有凭据只能经配置键或环境变量注入，README 与仓里都不放值。** 允许出现的名字只有这些：

| 环境变量 | 用途 | 出处 |
|---|---|---|
| `ZBOT_HOME` | profile 数据根 | `config/BotConfig.java` |
| `Z_BOT_PROVIDER` / `Z_BOT_MODEL` / `Z_BOT_API_KEY` / `Z_BOT_BASE_URL` | provider 级覆盖（只覆盖，不是存放真 key 的地方） | `config/BotConfig.java` |
| `ZBOT_SKILLS_DIR` / `ZBOT_SKILL_PLATFORM` / `ZBOT_SKILL_ENVIRONMENTS` | 技能目录与平台/环境覆盖 | `slash/SlashRegistry.java`、`skill/SkillLoader.java` |
| `ZBOT_EXEC_ENV_BACKEND` / `DOCKER_HOST` | 执行后端选择与 docker 端点 | `tool/env/ExecEnvConfig.java` |

配置键（键名从代码取）：

| 键 | 作用 | 出处 |
|---|---|---|
| `llm.provider` / `llm.model` / `providers` | 选 provider、模型、显式登记表 | `config/BotConfig.java` |
| `llm.fallback.models` | fallback 模型链 | 同上 |
| `llm.retry.max` / `llm.retry.backoff.ms` | 重试（429/超时）策略；也支持从指定环境变量取键式 | `llm/RetryPolicyConfig.java` |
| `<provider>.api.key` / `<provider>.base.url` / `<provider>.model` / `<provider>.type` | provider 段键式；`.api.key` 允许逗号分隔多 key（凭据池） | `BotConfig`、`llm/KeyPoolLlmProvider.java` |
| `agent.max.steps` / `agent.max.tokens` / `agent.token.budget` / `agent.temperature` / `agent.tool.choice` | ReAct 循环预算与工具选择策略 | `config/BotConfig.java` |
| `agent.exec.confirm` / `agent.exec.confirm.whitelist` | 危险命令审批：硬线/危险表 + 白名单 | `tool/ExecGuard.java`、`tool/ApprovalService.java` |
| `agent.delegate.max.depth` / `agent.delegate.max.children` / `agent.delegate.max.summary.chars` | 子代理委托：深度上限缺省 **2**（0=关掉 `delegate_task`）、异步并发宽度缺省 **3**、单条子代理回复进父上下文的字符上限缺省 **24000**（0=关掉裁切；超限则头尾各留一段、全文溢出落 `<configDir>/delegate/summaries`，footer 给 `read_file … offset=` 翻页指针） | `delegate/DelegateManager.java`、`delegate/SummaryBudget.java` |
| `zbot.sandbox` / `agent.state.db` / `zbot.state.db` | 沙箱根与库路径覆盖 | `config/BotConfig.java`、`tool/Sandbox.java` |
| `mcp.servers` | MCP server 清单（`stdio` / `http`） | `mcp/McpClientFactory.java` |
| `skills.*`（`skills.bundled.dir` / `skills.commands.enabled` / `skills.guard.source` / `skills.platform.override`） | 技能装载与命令覆盖 | `skill/` |
| `exec.env.*` | 执行环境这一族键（**只读这一族**） | `tool/env/ExecEnvConfig.java` |
| `center.url` / `center.app.code` | 认证中心接入 | `center/BotCenterClient.java` |
| `channel.<name>.enabled` / `.config.*` / `channel.<kind>.requires` | 通道 manifest（`<configDir>/channels.properties`，后写覆盖缺省档） | `channel/ChannelRegistry.java` |

---

## 📡 通道与入站面（只写键名，不写值）

缺省档随 jar 走：`z-bot-core/src/main/resources/com/zifang/z/bot/channel/channels.builtin.properties`，
四种内置 kind 都能被 profile manifest 引用；`requires` 缺键 ⇒ 抛 `ChannelConfigException` 并点名缺哪个键。

| kind | 缺省 enabled | outbound | 缺省端口 | 需要的配置键（值不入仓） | 入站路径 |
|---|---|---|---|---|---|
| `http`（控制台） | true | false | 8080 | — | `/`、`/bot/*`、`/api/*`（下表） |
| `webhook` | true | true | 8090 | — | `POST /webhook/in`，出站 `GET /webhook/out` 轮询 |
| `feishu` | **false** | true | 9101 | `app-id`、`app-secret`；事件面另用 `verification-token`、`encrypt-key`；出站另有 `static-token` 通路 | `POST /feishu/event`，出站 `GET /feishu/out` |
| `dingtalk` | **false** | true | 9102 | `webhook-url`（加签群机器人）+ 入站 `sign` 密钥 | `POST /dingtalk/in`，出站 `GET /dingtalk/out` |

三面共用**一个**入站 body 读取口：`channel/InboundLimits.MAX_BODY_BYTES = 65_536`（64 KiB，与 hermes wecom
`_MAX_BODY` 同值）。门看**已读字节数**而不是 `Content-Length`，超限在把 body 读进堆之前抛出，
四个 handler 各自把它映射成 413，且鉴权门排在尺寸门之前。守卫 `InboundBodyLimitTest`。
**上限本身不是配置项**（改尺寸要重编）。

飞书入站形状**只有** `POST /feishu/event`（GET/PUT/DELETE 一律 405）：`GET …?echostr=` 那条验签门外的回显
已在 D-P30-1 裁定后拆掉（查询参数回显是**企微**的校验形状）；现在这一面唯一"把请求内容吐回去"的口是
`url_verification` 的 `challenge`，它排在 verification-token 门之后。入站三件套（SHA-256 事件验签、
`{"encrypt": …}` AES-256-CBC 解密、钉钉 `sign` + 1 小时窗口）都已实现并具名钉住。

`serve` / `gateway` 缺省**只绑 `127.0.0.1`**；要同网段访问必须显式 `--host 0.0.0.0`（`cli/ServeCommand.java`、
`cli/GatewayCommand.java`）。端口优先级：`--port` > profile manifest 的 `channel.http.config.port` >
缺省档 `channel.http.default-port`。启动打印的是**实际**监听地址，绑了通配不会谎报成回环。

子代理**不会**向人申请确认：`buildChild` 出来的每个子 agent 都声明"没有可以等的人"
（`BotAgent.Builder.nonInteractive`），撞上审批闸门当场记 `[auto-denied]` 并把拒绝句回灌给子模型
—— 对标 hermes 的 `_subagent_auto_deny`，缺省 deny，放开要显式改父配置 `agent.exec.confirm=off` 或白名单。
`nonInteractive` 不放宽任何一道门：硬线在任何模式下都照旧直接拒。异步委托的线程池是实例字段，
随 `agent.shutdown()` 收池；收掉后再派当场拒绝并记 `FAILED`。

---

## 🔌 HTTP / SSE 路由台账

路由清单是被跟踪的文件 [`_doc/005_testing/acceptance/p28/ROUTES.tsv`](_doc/005_testing/acceptance/p28/ROUTES.tsv)：
**26 行（方法粒度）/ 23 条路径（dispatch 字面量口径）**，与代码不同源就红，不用靠人记：

```bash
mvn -o test -Dtest=HttpRouteLedgerTest#routesTsvIsInSyncWithLedger -Dp28.routes.write=true
```

分面（全部 `NONE(loopback-only)` 鉴权、`LOOPBACK_DEFAULT` 绑定）：

| 面 | 路径 |
|---|---|
| 控制台页 | `GET /`、`/index.html`、`/web`、`/console`（同一份 `resource:/web/index.html`） |
| 运行态 | `GET /bot/status`、`GET /bot/clear`、`GET /bot/tools`、`POST /bot/stop`、`POST /bot/steer`、`POST /bot/chat`、`POST /bot/confirm` |
| 流式 | `POST /bot/chat/stream`（SSE 帧：`step` `thought` `tool_call` `tool_result` `steer` `compact` `final` `done` `error` `confirm`） |
| 会话 | `GET/POST /api/sessions`、`GET /api/session/messages`、`POST /api/session/switch`、`POST /api/session/delete` |
| 模型 | `GET /api/models`（`ModelCatalogCache#catalog()`，供应商 `GET /v1/models`） |
| 技能 | `GET /api/skill/list`、`GET/POST /api/skill/sync`、`POST /api/skill/push` |
| 任务与注册 | `GET/POST /api/cron`、`POST /api/agent/register` |
| 命令面 | `GET /api/commands` |

## 命令面只有一份源

斜杠命令的注册表是唯一单源，TUI / HTTP / web 控制台 / ACP 四个端都从它派生
（`slash/CommandCatalog.java`，守卫 `CommandSurfaceConsistencyTest`；表声明序 = 终端私有 9 条 + 服务端 20 条）。
`GET /api/commands` 实测返回 **29 行**（`rows=29`，HTTP 200；与 `CommandCatalog.defs()` 同数）。
别在这里手抄一份清单 —— P19 之前那种"四个端各抄一份、各自漂"的病就是这么来的，
台账与分桶对照见 [`_doc/005_testing/acceptance/p28/WIRING.md`](_doc/005_testing/acceptance/p28/WIRING.md)。

---

## 🧪 测试与验收

```bash
rm -rf z-bot-core/target/surefire-reports z-bot-desktop-packager/target/surefire-reports
mvn -o test          # 全 reactor（packager 无测试），不要加 -pl
```

### ⚠️ 先决条件：9 支 MCP 用例需要官方 SDK（本机没装时它们会红）

`McpParentWatchdogTest`（4 支）+ `McpRealStdioServerTest`（5 支）要拉起真 stdio server，
`RealMcpHarness.requireOfficialSdk()` 直接 `import mcp.server.fastmcp` 做前置检查。
**这 9 支是刻意 fail 而不 skip 的**（skip 会把这一杠糊成满分），所以缺依赖时就是红，
不是环境问题、也不该改成跳过：

```bash
python3 -m venv ~/.cache/zbot_mcpvenv
~/.cache/zbot_mcpvenv/bin/pip install "mcp<2"        # ← 版本上限是硬的
export P21_PYTHON=~/.cache/zbot_mcpvenv/bin/python  # ← 硬前提，不设则退回系统 python3
mvn -o test
```

**为什么必须 `<2`**：`mcp.server.fastmcp` 是 v1 API。2.x 已把 `FastMCP` 更名为 `MCPServer`，
装最新版会得到

    ModuleNotFoundError: No module named 'mcp.server.fastmcp'. This is mcp 2.x, where
    FastMCP was renamed to MCPServer (from mcp.server.mcpserver import MCPServer) ...

而这条信息**不会**告诉你"你装错大版本了"——它看起来像 SDK 没装。
实测装 `mcp<2`（拿到 1.30.0）后这 9 支 `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`。
不设 `P21_PYTHON` 时 `RealMcpHarness.python()` 会退回系统 `python3`，多数机器上那里没有 `mcp`。

验收口径是四条杠（定义见 roadmap §5，逐期读数进 `_doc/005_testing/acceptance/pNN/EVIDENCE.md`）：

1. **杠①** `mvn -o test` 连跑 3 次，每轮 `Failures=Errors=Skipped=0` 且
   `BindException|Connection refused|SocketTimeout` 命中 0；
2. **杠②** 变异注入：把守卫改坏，必须**具名 testcase** 变红（`_doc/005_testing/acceptance/p*/p*_mutation.py`，
   共享锁 `$(git rev-parse --git-common-dir)/zbot-mutlock`）；
3. **杠③** 真进程 E2E ≥3 整跑（起真 serve / 真 tty / 真 sqlite，读代码不算证据），量具如 `p28_e2e.py`；
4. **杠④** 测试与 E2E 一个字都不许动 `~/.zbot/`（一律 `--config-dir` 指临时根）。

**当前树的杠① 是全绿的** —— 逐跑读数、三把尺与环境身份见
`_doc/005_testing/acceptance/recheck-0930/EVIDENCE.md` §8.3（同机串行，跑在哪一棵树由同页 `identity.txt` 现读；
§1–§7 记的是这批修复之前、红着的时候）。每跑 `Tests run: 1237, Failures: 0, Errors: 0, Skipped: 0`、
`socket_hits=0`、`fail_or_err_lines=0`。三把尺对着读 —— surefire 聚合 **1237** == 跑后 **116** 份
`*/target/surefire-reports/*.xml` 的 `tests` 求和 **1237** == 文本 `@Test` **1240** − 注释里的 **3** 处字样
（逐条点名：`ui/RawTerminalVerdictProbe.java:7`、`llm/P26RetryPolicyTest.java:37`、`memory/MemoryE2eDriver.java:16`）。
分母从上一版的 1236 涨到 1237 **只来自一支新测试**
`memory/MemoryWriteGateTest.oldTextMatchesBodyNotTimestamp` —— 它长在已有文件里，所以 `test_files` 仍 129、`xml_files` 仍 116。

这一版绿是**修出来的**：§1 那批的 `Failures=4` 是四支常红，根因三处在产品面、一处在测试面，本期按
hermes（`~/.hermes/hermes-agent` @ `cbc1054e2`）口径改掉，且每处都有**具名变异**兜住（摘掉哪处就红哪一支，
名单与那支作废重做的假变异见 §8.2）：`ProcessTree.pidOf()` 的反射句柄由 `p.getClass()` 换成 `Process.class`
—— 实际对象是未导出模块里的 final `ProcessImpl`，旧写法在 JDK 9+ 上恒返 0，超时/中断收尾每轮静默退化成"只端根"，
一轮泄漏一个约 27 年才自杀的孤儿进程（`5985a10` 的 JDK 8 回移把活路换成死路）；`PasteFolder` 补上 hermes 在数行
之前那一步剥尾换行（`lib/text.ts:189`）⇒ 折叠阈值与标记里的行数一起回到 hermes 口径；`MemoryWriteGate.locate()`
改为在**正文**上匹配 `old_text`，不再拿含 `[时间戳]` 的整行比 —— 旧写法要么永远命中不到，要么把不该删的条目静默端走。
`PasteFolderTest` 预览头那支是**测试期望写错**（hermes `lib/text.ts:85` 定 head=16，测试写了 14），改的是测试。
至于 §1.4 那支间歇红 `MemoryStoreContractTest.replaceAndRemoveHitExactlyOneEntry`：它在整页文本上找子串，而每行都带
墙钟时间戳，独立量具单跑率量到 0.5%；这批绿系列里它一次没出现，**这只算观察不算判愈**（样本量撑不起"消失了"，
机制与复现命令见 §4/§1.4）。

**但四杠仍不齐全，别把本页读成"已收口"**：`p30c/EVIDENCE.md` 的杠②（5 支具名变异，`RED-OK=5`）与
杠③（真进程 3 轮，`PASS=30 FAIL=0`）跑在 `6f26621`，本期 §8.2 只补了上面那四处修复自己的变异牙口，
杠③ 在改完之后一个字节都没重跑 ⇒ 当前树**没有一根杠是四杠齐全的**。历史"全绿"读数一律要连树身份一起引
（`p27c/EVIDENCE.md` §1 记 `3d57571` 上 `1223 ×3`、`p30c/EVIDENCE.md` §1 记 `6f26621` 上 `1195 ×3`，
两者都是 HEAD 的祖先，`git merge-base --is-ancestor` rc=0）。

还有一支专门量 README 自己的守卫：`z-bot-core/src/test/java/com/zifang/z/bot/ReadmeClaimsTest.java`
把本文的定量主张（版本、内核 pin、路由行数/路径数、`/api/commands` 行数、子命令支数与名单、
delegate 三个缺省）逐条与代码重算对账，缺一条或数不等都算红 —— 本页那些加粗数字因此不许随手改。

---

## 📦 打包与分发

**可执行 jar**：`z-bot-core` 的 `finalName=z-bot-core`，shade 在 `package` 阶段把 12 件 kernel +
jline + okhttp/kotlin + jackson + sqlite + picocli 全打进去，manifest `Main-Class=com.zifang.z.bot.ZBot`。
中央上在架的那个件就是这个形状（20,591,303 B），所以它是"拿来直接跑"的产物，不是给别的 JVM 引的瘦库
（见「进程形态」一节）。

**桌面包**：`z-bot-desktop-packager`（`packaging=pom`，仓里只有它的 `pom.xml`）配
`org.panteleyev:jpackage-maven-plugin:1.5.2` —— 输入是 `../z-bot-core/target`、主 jar `z-bot-core.jar`、
`appName=z-bot`、`appVersion=${revision}`、`vendor=z-team`、产物落 `dist/`；按宿主平台由 `jpackage` 产出
macOS `.dmg` / Windows `.exe` / Linux `.deb`。默认 `<jpackage.skip>true</jpackage.skip>`，
要打包需本机有 JDK 14+ 的 `jpackage` 并显式：

```bash
mvn -o -DskipTests package                                  # 先产 core 的 shade jar
mvn -o -pl z-bot-desktop-packager -Djpackage.skip=false package   # 再打桌面包
```

本仓**从未在本机打过桌面包**（`z-bot-desktop-packager/` 下无 `target/`、无 `dist/`），也**没有**容器化资产
（无 `Dockerfile`、无 `docker-compose*.yml`、无 `k8s/`）—— 想要容器形态，就自己把 shade jar 塞进 JRE 基础镜像，
并挂一个 profile 目录作数据卷。

---

## 🚧 明确没做的（别当成已完成）

- **`0.2.0` 已在 Central**（2026-09-29 靠 `e35c46f` 补 `<autoPublish>true</autoPublish>` 才从 `VALIDATED` 变公开；
  没有这一行时 `deploy` 一路 `BUILD SUCCESS` 却永远不可见）。仍**没做**的是"外部工程真 pull 验证"（P29 的后半腿）：
  目前没有第三个工程从 repo1 拉 `z-bot-core:0.2.0` 跑通的证据。`0.3.0` 不存在。
- `z-boot-fleet` 的 `z-bot.version` 那格实测仍是 `0.1.0`（滞后一版），本仓靠按坐标写的 DM 条目顶住。
- 入站的**真凭据握手**仍然零验证：飞书/钉钉三条校验（SHA-256 验签、`{"encrypt": …}` 解密、钉钉 `sign` + 1 小时窗口）
  都实现并具名钉住了，但全部证据仍是"对 `127.0.0.1` 假端点发出的字节" —— 本机没有飞书/钉钉凭据。
- 入站 body 上限 64 KiB 已闭合（门在分配之前），但**上限不是配置项**，改尺寸要重编。
- 摘要预算只做**静态那一层**：hermes 的第二把尺 `_parent_summary_char_budget`（按一批 N 条分摊、地板 2000 字符）
  没抄 —— `delegate_task` 只有单个 `task` 参数，`DelegateManager` 里 `tasks`/`batch` 实测 0 命中，
  没有"分摊"这一步可算。她的 `subagent_auto_approve` 也没抄（那等于把 `agent.exec.confirm=off` 藏进委托面）。
- `POST /api/skill/push` 的语义（推 vs 拉）未裁定（D-P28-2）；`POST /api/agent/register` 该返 200 还是 501 未定。
- **未知 provider 代号会静默换出口**（未修）：`BotConfig.fromProperties` 只自动登记 `LEGACY_PROVIDERS`
  （实测就 `minimax` / `spark` 两个，`BotConfig.java:27-32`），别的代号不写 `providers=<code>` 就进不了表，
  而 `activeProvider()` 在这种形状下不报错：表里还有别人就拿第一个，表空则回落到 `openai` 类型且 `baseUrl=null`
  ⇒ `LlmRouter` 交出用默认域的 `OpenAIProvider`。这不是推的：P26 杠③ 曾因此 8 发全打到
  `https://api.openai.com/v1/chat/completions` 拿 401（`_doc/005_testing/acceptance/p26/EVIDENCE.md` §8.0）。
  现在 E2E 的 profile 显式写 `providers=stub` 并把"非回环端点"判 NO-RUN；产品侧怎么修（直接 FATAL
  还是允许回落但不许无 baseUrl 出站）**等裁定**。
- 真 tty 下的人机体验（渲染、光标键、中文宽字符）没有自动化验收，只有 pty 探针取证，记 NO-RUN。
- 桌面打包（`.dmg` / `.exe` / `.deb`）只有配置，没打过。

---

## 📄 License

MIT，见仓根 [`LICENSE`](LICENSE)（版权行 `Copyright (c) 2026 z-opc-foundation`）；根 POM `<licenses>` 同为 MIT License。

_Maintained by the z-opc-foundation organization._

---

## 文档目录

本仓 `_doc/` 下只有两格（`find _doc -maxdepth 1 -type d` 实测）：`001_arch`（1 份）与
`005_testing`（验收证据）。`002_deploy` / `003_script` / `004_skill` / `006_release` /
`007_backlog` / `008_troubleshooting` 六个槽位**按需不建** —— 本仓没有对应内容，不留空目录。

- [`_doc/001_arch/hermes-roadmap.md`](_doc/001_arch/hermes-roadmap.md) — 唯一的规划/记账文档（v2，1,405 行）：对标 hermes-agent 的
  22 面能力矩阵、结构性红线（含红线 1 代码/数据分离、缺省绑回环）、P0→P30 分期与 §8 逐期实测读数。

- [`_doc/005_testing/acceptance/`](_doc/005_testing/acceptance/) — 每期验收证据，**24 个期目录**；典型四件套是
  `EVIDENCE.md`（逐字读数 + 产生读数的命令）、`LEDGER.tsv`（杠② 变异台账）、`pNN_e2e.py`（杠③ 真进程量具）、
  `pNN_mutation.py`（杠② 变异注入）。例外如实写：`p11/`、`p15/` **没有** `EVIDENCE.md`/`LEDGER.tsv`（只有脚本与探针）。

  ⚠ 驱动脚本的**运行态**（profile 快照、`state.db`、`*.lock`、`redfirst_*.log`、`__pycache__`）一律写到
  仓根 `.cache/<pNN>/`（已 `.gitignore`）或系统临时目录，不再落在 `acceptance/` 里 —— 那些是下一次跑就覆盖的东西，
  入库就等于把"某台机器某一次的现场"当成了证据。`EVIDENCE.md` 与 `LEDGER*.tsv` 这类被点名的读数照旧留桶内。

| 期 | 主题（取自各自 `EVIDENCE.md` 标题） |
|---|---|
| [`p11/`](_doc/005_testing/acceptance/p11/) | 无 EVIDENCE：`p11_e2e2.py` 与三支变异脚本 |
| [`p11b/`](_doc/005_testing/acceptance/p11b/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p11b/EVIDENCE.md) — 红线 1（代码/数据分离）真落地 |
| [`p11c/`](_doc/005_testing/acceptance/p11c/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p11c/EVIDENCE.md) — 通道监听收口（缺省绑回环 + `BindProbe`） |
| [`p12/`](_doc/005_testing/acceptance/p12/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p12/EVIDENCE.md) — 预算台账 / 中断收口 / steer 排空 / system prompt 快照冻结（`out/` 是量具跑出的采样，已入仓） |
| [`p14/`](_doc/005_testing/acceptance/p14/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p14/EVIDENCE.md) — 上下文引擎对齐 |
| [`p15/`](_doc/005_testing/acceptance/p15/) | 无 EVIDENCE：`AbWriter.java` + e2e/mutation 脚本 |
| [`p15b/`](_doc/005_testing/acceptance/p15b/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p15b/EVIDENCE.md) — [`sessions_column_alignment.md`](_doc/005_testing/acceptance/p15b/sessions_column_alignment.md) |
| [`p16/`](_doc/005_testing/acceptance/p16/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p16/EVIDENCE.md) — 送达台账与 gateway 投递 |
| [`p17/`](_doc/005_testing/acceptance/p17/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p17/EVIDENCE.md) — cron 投递闭环（`out/lockprobe` 是锁探针） |
| [`p18/`](_doc/005_testing/acceptance/p18/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p18/EVIDENCE.md) — 通道注册表 SPI + 飞书/钉钉真出站 |
| [`p19/`](_doc/005_testing/acceptance/p19/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p19/EVIDENCE.md) + [`WIRING.md`](_doc/005_testing/acceptance/p19/WIRING.md) — 命令收成单一真源 |
| [`p20b/`](_doc/005_testing/acceptance/p20b/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p20b/EVIDENCE.md) — MCP 工具面与审批（`logs/` 是多轮台账，`mcp_stub_server.py` 是假端点） |
| [`p21/`](_doc/005_testing/acceptance/p21/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p21/EVIDENCE.md) — MCP 对齐（StreamableHTTP/SSE transport、`tools/list_changed` 真注销、父死 watchdog、反向 `mcp_serve`） |
| [`p22/`](_doc/005_testing/acceptance/p22/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p22/EVIDENCE.md) — 执行后端 SPI（local / ssh / docker） |
| [`p23/`](_doc/005_testing/acceptance/p23/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p23/EVIDENCE.md) — 技能体系对齐 |
| [`p24/`](_doc/005_testing/acceptance/p24/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p24/EVIDENCE.md) + [`WIRING.md`](_doc/005_testing/acceptance/p24/WIRING.md) — 记忆与身份面 |
| [`p25/`](_doc/005_testing/acceptance/p25/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p25/EVIDENCE.md) — ACP（IDE 面） |
| [`p26/`](_doc/005_testing/acceptance/p26/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p26/EVIDENCE.md) — LLM 用量核算与失败恢复（含 §8.0 那次意外出网的归因） |
| [`p27/`](_doc/005_testing/acceptance/p27/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p27/EVIDENCE.md) + [`WIRING.md`](_doc/005_testing/acceptance/p27/WIRING.md) — 委托面对齐 |
| [`p27c/`](_doc/005_testing/acceptance/p27c/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p27c/EVIDENCE.md) — delegate 三条"未做"收口 + 本期撞出的两条；`LEDGER-run1..4.tsv` 是多轮台账 |
| [`p28/`](_doc/005_testing/acceptance/p28/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p28/EVIDENCE.md) + [`ROUTES.tsv`](_doc/005_testing/acceptance/p28/ROUTES.tsv) + [`WIRING.md`](_doc/005_testing/acceptance/p28/WIRING.md) — web 控制台 / HTTP 通道 / 终端 UI 对位面 |
| [`p30/`](_doc/005_testing/acceptance/p30/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p30/EVIDENCE.md) — 飞书入站（SHA-256 验签 + `{"encrypt":…}` 解密）与钉钉入站验签 |
| [`p30b/`](_doc/005_testing/acceptance/p30b/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p30b/EVIDENCE.md) — 入站 body 上限：门在分配之前 |
| [`p30c/`](_doc/005_testing/acceptance/p30c/) | [`EVIDENCE.md`](_doc/005_testing/acceptance/p30c/EVIDENCE.md) — 飞书面 GET 门外回显拆掉 + 平铺 v1 容错划边界 |

`p12/__pycache__`、`p25/__pycache__` 等目录是量具跑出的字节码缓存，历史上被一并跟踪进了仓（未清理）；
`*.log` 被 `.gitignore` 排除，所以原始构建日志不入仓，证据以 `EVIDENCE.md` 里逐字贴出的读数为准。
