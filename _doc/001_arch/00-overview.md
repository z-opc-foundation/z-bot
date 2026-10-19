# 项目总览

## 1. 项目简介

z-bot 是 z-opc-foundation 的**本地 agent 应用**（Claude Code / hermes-agent 同型）：在自己这台机器上跑一个能长期干活的 agent —— 终端 CLI（交互式 repl 与一次性提问）、HTTP/SSE + 内置 web 控制台、常驻多通道 gateway（HTTP / webhook / 飞书 / 钉钉），带工具沙箱、SQLite 会话库、技能装载与 MCP / ACP 接入，runtime 依赖 `z-agent-kernel`。

它解决的是"模型与 key 归我自己的 profile，会话与台账落在本地 `state.db`，命令在沙箱里执行并走审批闸门"。对外它说四种面：**终端**、**HTTP/SSE**、**MCP**（既能当 client，也能 `mcp serve` 反过来把自己当只读 server）、**ACP**（IDE 走 stdio 接入）。

**它不是一等 Spring 服务，也不是可嵌入库** —— 入口是 picocli 的 `main()`，HTTP 面自己 `new com.sun.net.httpserver.HttpServer`，数据根是自己独占的 profile 目录。主源码里 `org.springframework` 命中 **0 处**，`src/main/resources` 里没有 `META-INF/spring.factories` / `AutoConfiguration.imports`。

**技术栈：** Java 8 · 无 Spring（`z-agent-kernel` 12 件做 runtime）· picocli 4.7.6 + JLine 3.24.1（终端）· JDK 内置 `HttpServer`（HTTP 面）· OkHttp（HTTP 客户端）· Jackson 2.13.5（整族钉死）· SQLite（`org.xerial:sqlite-jdbc:3.41.2.2`）· Maven shade 打 uber jar

## 2. 模块架构

```
z-bot/
├── pom.xml                       # 聚合 POM：z-boot-parent:1.0.21 + <revision>0.2.1</revision> + 12 条 kernel 直接 DM
├── z-bot-core/                   # 唯一有代码的模块（jar / shade 件，mainClass=com.zifang.z.bot.ZBot）
│   └── src/main/java/com/zifang/z/bot/
│       ├── ZBot.java             # picocli 根命令，10 支顶层子命令
│       ├── acp/                  # ACP agent 面：stdio JSON-RPC、审批桥、流发布
│       ├── agent/                # BotAgent（ReAct 循环）、预算台账、中断作用域
│       ├── center/               # BotCenterClient（认证中心接入）
│       ├── channel/              # Channel SPI + Gateway + 四个内置通道 + InboundLimits
│       ├── checkpoint/           # CheckpointManager
│       ├── cli/                  # 11 个文件 = 10 支子命令 + AgentOptions mixin
│       ├── config/               # BotConfig（profile 根解析与全部配置键）
│       ├── context/              # 压缩引擎与台账
│       ├── cron/                 # 调度与投递
│       ├── delegate/             # 子代理委托与摘要预算
│       ├── llm/                  # 路由、凭据池、重试、模型目录缓存
│       ├── mcp/                  # MCP client（stdio / http）与反向 mcp serve
│       ├── memory/               # 记忆面（写入门禁、漂移守卫）
│       ├── session/              # 会话装配与消息编解码
│       ├── skill/                # 技能装载、frontmatter、命令覆盖
│       ├── slash/                # CommandCatalog（命令面单一真源）
│       ├── store/                # StateStore / SchemaMigrations / SqliteTx / DbRecovery
│       ├── tool/                 # BuiltinTools / Sandbox / ExecGuard / ApprovalService / env/
│       └── ui/                   # 终端 UI（JLine 与 raw 两条输入实现）
├── z-bot-core/src/main/resources/
│   ├── web/index.html                                   # 内置控制台（打进 jar 的单文件页面）
│   └── com/zifang/z/bot/channel/channels.builtin.properties  # 通道注册表缺省档
├── z-bot-desktop-packager/      # 只有 pom.xml（jpackage 配置，无 src）—— .dmg / .exe / .deb
├── _frontend/                   # 两套前端工程：z-bot-suit（壳）+ z-bot-component（页面）
└── _doc/                        # 001_arch（roadmap）+ 005_testing（验收证据），共 157 个文件
```

## 3. 核心功能

### 3.1 命令面：10 支顶层子命令

`ZBot.java` 的 `subcommands` 逐个列出：`chat`、`repl`（别名 `interactive`）、`serve`、`gateway`、`status`、`sessions`、`send`、`pair`、`mcp`、`acp`。`--help` / `--version` 由 picocli 的 `mixinStandardHelpOptions` 提供，`--version` 打的是 `z-bot 0.2.1`（取自 `BuildInfo.CLI_VERSION`，与 `<revision>` 的绑定由 `BuildInfoDriftTest` 钉住）。`sessions` 还有二级动词：`list` / `search` / `export`（JSONL）/ `stats` / `prune` / `version` / `lineage` / `end` / `archive`。

### 3.2 HTTP / SSE 与内置控制台

路由清单是**被跟踪的文件** `_doc/005_testing/acceptance/p28/ROUTES.tsv`（由 `HttpChannel.routes()` 渲染，**26 行方法粒度 / 23 条路径 dispatch 字面量口径**），与代码不同源就红（`HttpRouteLedgerTest#routesTsvIsInSyncWithLedger`，`-Dp28.routes.write=true` 重新生成）。

覆盖面：`GET /`、`/index.html`、`/web`、`/console`（控制台 HTML）、`/bot/status`、`/bot/clear`、`/bot/stop`、`/bot/steer`、`/bot/tools`、`/bot/chat`、`/bot/confirm`、`/bot/chat/stream`（SSE 帧：`step` `thought` `tool_call` `tool_result` `steer` `compact` `final` `done` `error` `confirm`）、`/api/sessions`（GET/POST）、`/api/session/messages`、`/api/session/switch`、`/api/session/delete`、`/api/models`、`/api/skill/list`、`/api/skill/sync`（GET/POST）、`/api/skill/push`、`/api/cron`（GET/POST）、`/api/agent/register`、`/api/commands`。全部路由 `auth=NONE(loopback-only)`、`bind_scope=LOOPBACK_DEFAULT`。

### 3.3 通道与 gateway

四种内置 kind 写在 `channels.builtin.properties` 里，每种都声明 `kind` / `enabled` / `outbound` / `default-port`，IM 通道另带 `requires`（缺键由 `ChannelRegistry.create()` 点名抛错）与 `config`：

| kind | enabled 缺省 | outbound | default-port |
|---|---|---|---|
| `http` | true | false | 8080 |
| `webhook` | true | true | 8090 |
| `feishu` | **false** | true | 9101（`requires=app-id,app-secret`） |
| `dingtalk` | **false** | true | 9102（`requires=webhook-url`） |

常驻 gateway 侧由 `Gateway` / `Supervisor` / `TurnLease` / `DeliveryLedger` / `DeadTargets` / `PairingService` 组成：租约 + 送达台账 + 投递目标失效兜底，`--pairing` 起配对码授权。

### 3.4 会话库

`store/StateStore.java` + `store/SchemaMigrations.java` 基于 SQLite（WAL）。`SchemaMigrations.requiredTables()` 返回 **8 张必检表**：`sessions`、`messages`、`session_model_usage`、`async_delegations`、`schema_version`、`state_meta`、`compression_locks`、`gateway_routing`；`requiredColumns()` 另定义每张表的列清单，`checkHead` 逐列核对。`sessions search` 走 FTS5，不可用则降级 LIKE。

### 3.5 工具与沙箱

`tool/BuiltinTools.java` 内建 **14 支**：`echo`、`message`、`time`、`counter`、`health`、`sysinfo`、`read_file`、`write_file`、`search`、`exec`、`mvn_build`、`curl_test`、`memory`、`cronjob`，另有 `delegate_task`。沙箱根缺省 `<profile>/workspace`，由 `Sandbox` / `ExecGuard` / `ApprovalService` / `Toolkit` / `Toolsets` 把关。执行后端是 SPI：`tool/env/LocalExecEnvironment.java`、`SshExecEnvironment`、`DockerEngineClient`、`ProcessTree`，`exec.env.*` 一族配置键可切换，`ZBOT_EXEC_ENV_BACKEND` / `DOCKER_HOST` 可覆盖。

### 3.6 其余能力面

- **ReAct 循环与预算** —— `agent/BotAgent.java` / `agent/BudgetLedger.java`：迭代与令牌预算、`/steer` 中途改写、中断收口。
- **子代理委托** —— `delegate/DelegateManager.java` / `SummaryBudget` / `DelegationLedger`：深度、宽度、摘要裁切三个缺省；子代理撞审批闸门当场 `[auto-denied]`。
- **上下文压缩** —— `context/CompressorEngine.java` / `CompressionGate` / `CompressionLedger`：超窗按 `contextWindow` 触发，台账落库。
- **记忆与身份** —— `memory/MemoryStore.java` / `MemoryWriteGate` / `MemoryDriftGuard` / `MemoryTools`：`<profile>/memories/*.md`（`SOUL.md` / `MEMORY.md`），写入门禁 + 漂移守卫。
- **技能体系** —— `skill/SkillLoader.java` / `Frontmatter` / `SkillCommands` / `SkillGuard` / `SkillSync`：frontmatter 解析、技能映射斜杠命令、按 `origin_hash` 从认证中心 sync。
- **MCP（双向）** —— `mcp/McpClientFactory.java` / `StreamableHttpMcpTransport` / `ZBotStdioMcpTransport` / `ZBotMcpServe` / `SecretRedaction`：client 两种 transport（`stdio` / `http`），未知值直接抛不静默降级；`z-bot mcp serve` 反向把 z-bot 当只读 MCP server；出站文本脱敏。
- **ACP（IDE 面）** —— `acp/AcpAgentServer.java` / `StdioAcpTransport` / `AcpApprovalBridge` / `AcpStreamPublisher`：stdio JSON-RPC，`available_commands_update` 帧从命令单源派生。
- **定时任务** —— `cron/CronScheduler.java` / `CronTools` / `ChannelCronDelivery` / `LocalCronDelivery`：`cronjob` 工具 + `<profile>/cron`，投递可指到 outbound 通道。
- **LLM 韧性** —— `llm/LlmRouter.java` / `KeyPoolLlmProvider` / `ResilientLlmProvider` / `JitteredBackoff` / `StreamStaleWatchdog` / `ModelCatalogCache` / `ModelUsage`：多 key 凭据池、429/超时重试、fallback 链、流式陈旧看门狗、模型目录缓存 `<profile>/models-cache.json`。
- **认证中心接入** —— `center/BotCenterClient.java` / `BotLifecycle`，`center.url` / `center.app.code`；其对偶入口是 `POST /api/agent/register`。
- **命令面单源** —— `slash/CommandCatalog.java` + `SlashRegistry`。

### 3.7 profile 与数据根

根目录解析优先级 `-Dzbot.home` > 环境变量 `ZBOT_HOME` > `~/.zbot`（唯一实现是 `BotConfig.defaultConfigDir()`，`--config-dir <目录>` 是同一件事的显式写法）。一个填满的 profile 目录含 8 项：`config.properties`、`state.db`、`sessions/`、`memories/`、`cron/`、`workspace/`、`models-cache.json`、`.stty.bak`；按用法还会长出 `skills/`、`delegate/summaries/`、`channels.properties`、`pairing.json`。**代码与数据分离：profile 目录里只放数据与用户配置，仓库里不放。**

## 4. 对外集成 / 上下游

**上游依赖：** `z-agent-kernel` 的 **12 件**（`z-agent-kernel-llm` / `-message` / `-types` / `-tool` / `-memory` / `-event` / `-middleware` / `-workspace` / `-agent` / `-formatter` / `-mcp` / `-credential`，版本 `z-agent-kernel.version=0.2.1`）按坐标直接 DM 顶住父链 fleet；另有刻意按坐标写死的 jackson 三件（2.13.5）、JLine 3.24.1、picocli 4.7.6、sqlite-jdbc 3.41.2.2。

**下游 / 对外边界：**

| 面 | 边界形态 | 端口 / 协议 |
|---|---|---|
| 终端 | picocli `main()` + JLine | 无 |
| HTTP / SSE | JDK `com.sun.net.httpserver.HttpServer`，缺省只绑 `127.0.0.1` | **8080** |
| webhook 通道 | 通用 webhook 入站 + 出站轮询 | **8090** |
| 飞书通道 | 出站真发 `tenant_access_token` + `im/v1/messages`，`api-base=https://open.feishu.cn/open-apis` | **9101**（缺省 `enabled=false`） |
| 钉钉通道 | 出站真发加签群机器人 webhook | **9102**（缺省 `enabled=false`） |
| MCP | client（`stdio` / `http`）与 `mcp serve` 反向 server | stdio / StreamableHTTP / SSE |
| ACP | stdio JSON-RPC | stdio |
| 认证中心 | `POST /api/agent/register`（对偶面）+ `center.url` | 由 `center.url` 决定 |

**对宿主应用：无 starter 边界。** 仓内没有 `Dockerfile`、`docker-compose*.yml`、`deploy/`、`k8s/`、`Makefile`（README 以 `find . -maxdepth 2` 实测无命中），也没有前端构建产物进仓 —— 控制台是打进 jar 的单文件 `web/index.html`。

**进程形态约束（并机时的硬约束）：** `ZBot.main()` 走 picocli 后 `System.exit(...)`，进程退出权在 z-bot 手里；shade 在 `package` 阶段执行、`createDependencyReducedPom=false`、**不做 relocation**，所以中央在架的 jar 本身就是 uber jar；本仓刻意把 jackson 整族钉在 2.13.5（地板是 2.18.6）。⇒ 并机方案只能按「一个 z-bot = 一个独立进程 + 一个独立 profile 数据根」来排。

## 5. 技术特点

1. **刻意零 Spring 依赖**：主源码 `org.springframework` 命中 0 处，HTTP 面自己 `new HttpServer`。这不是偷懒而是并机约束的直接后果 —— Spring 装配会把「可嵌入」读成「可嵌入」，而 z-bot 的进程退出权、tty 接管与 uber jar 形状都不允许它进别人的 JVM。
2. **uber jar 且不 relocation**：`z-boot-parent` 的 flatten 是 `oss`，shade 把 kotlin / jackson / jline / okhttp / picocli / sqlite 全部打进 `z-bot-core.jar`，自家 `com/zifang/` 只是一小部分条目。收益是 `java -jar` 就能跑，代价是同一批类在宿主 JVM 里会有两份定义。
3. **路由台账是生成物且被测试锁住**：`ROUTES.tsv` 由 `HttpChannel.routes()` 渲染，测试断言它与代码同源；26 行 / 23 路径的分子分母都写在文件头注释里，不靠人记。
4. **版本零漂移靠"按坐标钉死"而非改地板**：jackson 3 件（2.13.5）、kernel 12 件（0.2.1）、jline、picocli、sqlite 全部走直接 DM，因为父链 / fleet 管不到这些坐标或版本口径与地板冲突。
5. **命令面单一真源**：`slash/CommandCatalog` 是命令定义的唯一来源，ACP 的 `available_commands_update` 帧与 `/api/commands` 都从它派生，不存在第二份命令名单。
6. **通道注册表是"声明 + 兑现路径"而非配置堆积**：`channels.builtin.properties` 每条都写清 `requires`（缺键点名抛错）、`default-port`（CLI/config 都没给端口时用它）、`outbound`（决定能不能当 cron 投递目标），缺一不可。
7. **IM 通道缺省关闭**：飞书 / 钉钉 `enabled=false` 且 `requires` 凭据键，凭据不随包发，必须由数据根的 `channels.properties` 显式打开。
8. **验收证据是可跑的、不是散文**：`_doc/005_testing/acceptance/` 下 26 个期目录（p11 … p30c、recheck-0930、release-021），典型四件套是 `EVIDENCE.md`（逐字读数 + 产生读数的命令）、`LEDGER.tsv`（变异台账）、`pNN_e2e.py`（真进程量具）、`pNN_mutation.py`（变异注入）。

## 6. 目录结构

本仓只有**一个** Java 包根 `com.zifang.z.bot`，主源码 **145 个 `.java` / 42,479 行**，测试 **130 个 `.java` / 38,360 行**（`find` 实测）。20 个子包按职责分：

```
z-bot-core/src/main/java/com/zifang/z/bot/
├── ZBot.java              # picocli 根命令（10 支顶层子命令）
├── agent/      (7)       # ReAct 循环、预算台账、中断作用域
├── channel/    (21)      # Channel SPI、Gateway、Supervisor、租约、送达台账、四个内置通道
├── cli/        (11)      # 10 支子命令 + AgentOptions mixin
├── tool/       (20)      # BuiltinTools、Sandbox、ExecGuard、ApprovalService、Toolkit、env/ 执行后端
├── acp/        (12)      # ACP agent 面、stdio transport、审批桥、流发布
├── mcp/        (10)      # MCP client 两种 transport、反向 mcp serve、密钥脱敏
├── llm/        (9)       # 路由、凭据池、重试、模型目录缓存、用量统计
├── memory/     (9)       # 记忆存储、写入门禁、漂移守卫、记忆工具
├── delegate/   (8)       # 子代理委托、摘要预算、委托台账
├── cron/       (8)       # 调度、cron 工具、通道投递、本地投递
├── ui/         (7)       # 终端 UI：TerminalUI、LineEditor、RawTerminalReader、MarkdownRenderer
├── skill/      (5)       # 技能装载、frontmatter、命令覆盖、同步
├── store/      (4)       # StateStore、SchemaMigrations、SqliteTx、DbRecovery
├── slash/      (3)       # CommandCatalog、SlashRegistry
├── context/    (3)       # 压缩引擎、闸门、压缩台账
├── session/    (2)       # 会话装配、消息编解码
├── center/     (2)       # BotCenterClient、BotLifecycle
├── config/     (1)       # BotConfig（profile 根解析与全部配置键）
└── checkpoint/ (1)       # CheckpointManager
```

`_doc/` 侧两个桶：`001_arch/`（本文 + `07-roadmap.md`，1,405 行的 v2 能力矩阵与逐期实测账）与 `005_testing/`（`01-mcp-test-env.md` + `acceptance/` 26 个期目录，共 156 个文件）。

`_frontend/` 侧两套 Vite 工程：`z-bot-suit`（壳：`App.jsx` / `main.jsx`）与 `z-bot-component`（页面：`Console.jsx` / `HomePage.jsx` / `LoginPage.jsx` + `services/api.js` + `pages-manifest.jsx`）。
