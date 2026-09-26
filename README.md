# z-bot

本地 agent 应用：CLI（repl / 一次性提问）+ HTTP/SSE + web 控制台 + 常驻 gateway，
带工具沙箱、会话库（SQLite）、技能/MCP/ACP 接入，runtime 用内置的
`z-agent-kernel`。工程口径见 [`_doc/hermes-roadmap.md`](_doc/hermes-roadmap.md)（对标 hermes 的全量升级计划）。

| 项 | 值 | 怎么自己验一遍 |
|---|---|---|
| 坐标 | `io.github.yuku123:z-bot`（聚合）/ `:z-bot-core`（唯一有代码的模块） | `grep -n '<artifactId>' pom.xml` |
| 当前源码版本 | `0.2.0`（`pom.xml` 的 `<revision>`，唯一真源） | `grep -n '<revision>' pom.xml` |
| Central 上实际有的版本 | **只有 `z-bot-core:0.1.0`**（`0.2.0` 实测 404 ⇒ 本仓 0.2.0 **没发过**） | `curl -sI https://repo1.maven.org/maven2/io/github/yuku123/z-bot-core/0.2.0/z-bot-core-0.2.0.jar \| head -1` |
| JDK | Java 8（`maven.compiler.source/target=8`） | `grep -n 'maven.compiler' pom.xml` |
| 内核 pin | `z-agent-kernel.version=0.2.1` | `grep -n 'z-agent-kernel.version' pom.xml` |
| 模块 | `z-bot-core`（全部代码与测试）、`z-bot-desktop-packager`（jpackage 桌面包配置，无测试） | `grep -n '<module>' pom.xml` |

**所以：要用 0.2.0 就从源码构建**，别直接依赖 Central（那里只有 0.1.0）。

## 30 秒跑起来

```bash
mvn -o -DskipTests package                       # 产出 z-bot-core/target/z-bot-core.jar
java -jar z-bot-core/target/z-bot-core.jar repl  # 交互式；↑↓ 翻历史，/help 看命令
java -jar z-bot-core/target/z-bot-core.jar "用一句话解释 MVCC"   # 一次性提问，不进 repl
java -jar z-bot-core/target/z-bot-core.jar serve --port 8080     # HTTP + SSE + web 控制台
# 浏览器开 http://127.0.0.1:8080/
```

顶层子命令共 10 支：`chat`、`repl`（别名 `interactive`）、`serve`、`gateway`、`status`、
`sessions`、`send`、`pair`、`mcp`、`acp`。名单以 `ZBot.java` 的 `subcommands` 为准，
复算：`sed -n '39,42p' z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java`。
`java -jar … --help` / `--version` 由 picocli 的 `mixinStandardHelpOptions` 提供。

## 配置与 profile 隔离（结构性红线 1）

数据与代码分离：`~/.zbot/`（或你指定的任何目录）里**只放数据与用户配置**，仓库里不放。
根目录解析优先级：`-Dzbot.home` > 环境变量 `ZBOT_HOME` > `~/.zbot`
（见 `ZBot.java` 类注释；命令行 `--config-dir <目录>` 是同一件事的显式写法，
多 profile / CI / 测试都用它，跑完不会碰你的真实数据）。

一个 profile 目录里正好这 8 项（真实 `~/.zbot/` 的实测清单，`ls -A ~/.zbot | wc -l` = 8；
测试与 E2E 跑完必须回到这个数，见杠④）：

```
config.properties    # 唯一的配置入口（键见下表）
state.db             # 会话/消息/委托台账（SQLite）
sessions/  memories/  cron/  workspace/
models-cache.json    # 模型目录缓存
.stty.bak            # RawTerminalReader 起 tty 前的 stty 备份（别删，它要还原）
```

配置键（键名一律从代码取，别信文档里的举例）：

| 键 | 作用 | 出处 |
|---|---|---|
| `llm.provider` / `llm.model` | 选哪个 provider、哪个模型 | `config/BotConfig.java` |
| `llm.fallback.models` | fallback 模型链 | 同上 |
| `llm.retry.max` / `llm.retry.backoff.ms` | 重试（429/超时）策略 | 同上 |
| `<provider>.api.key` / `<provider>.base.url` | provider 段键式；`.api.key` 允许逗号分隔多 key（凭据池） | `BotConfig.java:162,176,907` |
| `agent.max.steps` / `agent.max.tokens` / `agent.token.budget` / `agent.temperature` | ReAct 循环预算 | `BotConfig.java` |
| `agent.tool.choice` | 工具选择策略 | 同上 |
| `agent.exec.confirm` / `agent.exec.confirm.whitelist` | 危险命令审批：硬线/危险表 + 白名单（token 边界匹配） | `tool/ExecGuard.java`、`tool/ApprovalService.java` |
| `zbot.sandbox` / `agent.state.db` / `zbot.state.db` | 沙箱开关、库路径覆盖 | `BotConfig.java` |
| `mcp.servers` | MCP server 清单（stdio / HTTP+SSE） | `mcp/` |
| `skills.*`（`skills.bundled.dir` / `skills.commands.enabled` / `skills.guard.source` / `skills.platform.override`） | 技能装载与命令覆盖 | `skill/` |
| `exec.env.*` | 执行环境这一族键（**只读这一族**，别的键不归它） | `tool/env/ExecEnvConfig.java` |
| `center.url` / `center.app.code` | 认证中心接入 | `center/` |

`serve` / `gateway` 的监听地址缺省**只绑 `127.0.0.1`**；要让同网段别的机器连，必须显式
`--host 0.0.0.0`（`cli/ServeCommand.java:34-36`）。端口由 profile 的 channel manifest
（`channel.http.default-port`）说话，`--port` 覆盖它；启动时打印的是**实际**监听地址，
绑了通配不会谎报成回环（`channel/HttpChannel.java:93`）。

入站 body 也**只有一个**读取口：`channel/InboundLimits.java`，上限 64 KiB（与 hermes wecom 的
`_MAX_BODY = 65_536` 同值），飞书 / 钉钉 / webhook / http 四面共用。门是**已读字节数**而不是
`Content-Length` 头 ⇒ 不带长度的分块上传同样超不过去；超限在把 body 读进堆之前抛出，四个 handler
各自排在通用 `catch (Exception)` **之前**映射成 413，而鉴权门仍排在尺寸门之前（未签名的超限 body 先得 401）。
守卫是 `InboundBodyLimitTest`（含"还有谁自己调了 `getRequestBody()`"那支接线守卫），变异检验见
`_doc/acceptance/p30b/LEDGER.tsv`。

飞书这一面的入站形状**只有** `POST /feishu/event`：GET / PUT / DELETE 一律 405，旧的
`GET …?echostr=` 门外回显已在 D-P30-1 裁定后拆掉 —— 查询参数原样回显是**企微**的回调校验形状
（`lark_oapi` 1.5.3 全包与 hermes 的飞书适配器里都是 0 命中，她的 wecom 适配器 6 命中且解密后才回显），
挂在飞书面上等于在验签/token 门**之前**开一条回显口。现在这一面唯一的"把请求内容吐回去"的口是
`url_verification` 的 `challenge`，它排在 verification-token 门之后；v1 平铺事件的容错只认代码里
已经读过的那几个键，无出处的 `event.content` 不开读取点（`_doc/acceptance/p30c/EVIDENCE.md`）。

## 命令面只有一份源

斜杠命令的注册表是唯一单源，TUI / HTTP / web 控制台 / ACP 四个端都从它派生
（`slash/CommandCatalog.java`，守卫 `CommandSurfaceConsistencyTest`）。
`GET /api/commands` 实测返回 **29 行**（`rows=29`，HTTP 200）。别在这里手抄一份清单——
P19 之前那种"四个端各抄一份、各自漂"的病就是这么来的，台账与分桶对照见
[`_doc/acceptance/p28/WIRING.md`](_doc/acceptance/p28/WIRING.md)。

## HTTP / SSE 路由台账

路由清单是被跟踪的文件 [`_doc/acceptance/p28/ROUTES.tsv`](_doc/acceptance/p28/ROUTES.tsv)：
**26 行（方法粒度）/ 23 条路径（dispatch 字面量口径）**，含 `GET /` 系列控制台页、
`/bot/*`（含 `POST /bot/chat/stream` 的 SSE 帧）、`/api/sessions`、`/api/models`、
`/api/skill/*`、`/api/cron`、`/api/agent/register`、`/api/commands`。

它和代码不同源就会红，不用靠人记：

```bash
mvn -o test -Dtest=HttpRouteLedgerTest#routesTsvIsInSyncWithLedger -Dp28.routes.write=true
```

## 验收口径（四条杠，每期都我自己量）

计划与逐期实测读数都在 `_doc/hermes-roadmap.md`（§8），各系列的原始证据在
`_doc/acceptance/pNN/EVIDENCE.md` + 脚本自写的 `LEDGER.tsv`。四杠是：

1. **杠①** `rm -rf z-bot-core/target/surefire-reports && mvn -o test` 连跑 3 次（全 reactor，不许 `-pl`），
   每轮 `Failures=Errors=Skipped=0` 且 `BindException|Connection refused|SocketTimeout` 命中 0；
2. **杠②** 变异注入：把守卫改坏，必须**具名 testcase** 变红（`_doc/acceptance/p*/p*_mutation.py`，
   共享锁 `$(git rev-parse --git-common-dir)/zbot-mutlock`，同一时刻只准一支量具动 src）；
3. **杠③** 真进程 E2E ≥3 整跑（起真 serve / 真 tty / 真 sqlite，读代码不算证据），
   量具如 `_doc/acceptance/p28/p28_e2e.py`；
4. **杠④** 测试与 E2E 一个字都不许动 `~/.zbot/`（一律 `--config-dir` 指临时根）。

最近一次目标树读数（实现 `6f26621`；杠①×3 跑在**含本期文档笔的最终树**上，逐字读数与起始时刻见
`_doc/acceptance/p30c/EVIDENCE.md` §1，被测量的生产文件与测试类逐字节未变，md5 对账见同一份 §0）：
**1195 个测试 ×3 全绿**，测试文件 111 个（同一数字三把尺对着读：surefire 合计 1195 == 跑后 111 份
`*/target/surefire-reports/*.xml` 求和 1195 == 文本 `@Test` 1198 − 注释里的 3 处字样），
socket 命中 0，杠④ 三点不变；杠② 六族
P19 8/8 KILLED、P27 21 支 `10 RED-OK / 10 PARTIAL / 1 SURVIVED`、P28 14 支 `13 RED-OK / 1 SURVIVED`
（两族的 SURVIVED 分别是判为等价的 M13 与故意注入的阳性对照 M5）、P30 15 支、P30b 7 支、P30c 5 支全 RED-OK
（**每族的读数都是它自己收口那一跑的**，不是同一遍扫出来的；跨族汇总别照着这一行做减法）。

## 明确没做的（别当成已完成）

- **`0.2.0` / `0.3.0` 都没发 Central**，repo1 上只有 `z-bot-core:0.1.0`；P29（抬号 + 发布 + 外部工程真 pull 验证）未开工。
- 入站的**真凭据握手**仍然零验证：飞书 `{"encrypt": …}` 解密、飞书 **SHA-256** 事件验签、钉钉入站 `sign`+1 小时窗口校验三条 P30 都已实现并具名钉住（`_doc/hermes-roadmap.md` §8.14），但进出站的全部证据仍是"对 127.0.0.1 假端点发出的字节"——本机没有飞书/钉钉凭据。
- 入站 body 上限 64 KiB（四面共用、门在分配之前）已闭合（`_doc/hermes-roadmap.md` §8.15）；`GET /feishu/event?echostr=` 那条验签门外的回显也已拆掉（§8.16，D-P30-1），v1 平铺容错同时划了边界：无出处的 `event.content` 不是读取点，由一支边界用例钉住（D-P30-2）。仍欠的是**上限本身不是配置项**（改尺寸要重编）与真凭据握手零验证（上一条）。
- `POST /api/skill/push` 的语义（推 vs 拉）尚未裁定（D-P28-2）；`POST /api/agent/register` 该返 200 还是 501 未定。
- 真 tty 下的人机体验（渲染、光标键、中文宽字符）没有自动化验收，只有 pty 探针取证，记 NO-RUN。
- `z-bot-desktop-packager` 只有 jpackage 配置，本机没打过 .dmg/.exe/.deb。

## 许可

MIT，见 [`LICENSE`](LICENSE)。
