# P19 · 命令表单一真源 —— 单源在哪、四端各自怎么消费

> 本文件的"数"不手敲：`29 / 3 别名 / tui=29 http=20 web=6 acp=20 / repl 26 条`
> 全部由 `p19_e2e.py` 从**真进程**打出来（`_doc/005_testing/acceptance/p19/p19_e2e.py`，判据 A/B/C 那三段
> `NOTE|` 行），复算命令见 §5。相等性由 `CommandSurfaceConsistencyTest` +
> `AcpCommandAdvertisementTest` 钉住，杀判据的变异台账见 `LEDGER.tsv`。

## 1. 单源在哪

`z-bot-core/src/main/java/com/zifang/z/bot/slash/CommandCatalog.java`

| 位置 | 是什么 |
| --- | --- |
| `CommandCatalog.java:54` | `enum Endpoint { TUI, HTTP, WEB, ACP }` —— 四端各自一段，键名 `tui/http/web/acp` |
| `CommandCatalog.java:145` | `DEFS`：整张表的载体，类初始化定死，运行时不改写 |
| `CommandCatalog.java:174—192` | 通道私有一段 9 条（`def(...)`，`executor=null`）：`/status /theme /feedback /confirm /help /? /exit /quit /q` |
| `CommandCatalog.java:206—349` | 服务端一段 20 条：`:206—311` 是执行入口 `EXECUTORS`，`:315—348` 是 `server(...)` 登记（名字/说明/参数提示/可见性段） |
| `CommandCatalog.java:363` | `serverDefs()` —— 服务端注册表读它 |
| `CommandCatalog.java:396` | `namesFor(Endpoint)` —— 某端该广告的名字集合（守卫的派生侧） |
| `CommandCatalog.java:405` | `rowsFor(Endpoint)` —— 帮助表行（该端可见且非别名） |
| `CommandCatalog.java:447` | `skillEndpoints()` —— 技能派生命令的可见性政策，**只写这一处** |
| `CommandCatalog.java:452` | `skillRow(...)` —— 技能行的序列化（`scope=skill`） |
| `CommandCatalog.java:477` | `asRows()` —— 整张表的序列化形态（`/api/commands` 的分母） |
| `CommandCatalog.java:513` | `selfCheck()` —— 表内自洽（重名/缺说明/别名主条目不存在） |

**可见性口径 = 广告即兑现**：`endpoints` 记的是"这个端真接得住"，不是"想显示"。所以
`/exit`、`/theme` 不在 WEB 段（web 没有退出概念、没有主题分支），17 条服务端命令不在 WEB 段
（web 没有转发分支）。技能段是 `TUI/HTTP/ACP`（`WEB` 无转发），由 `:447` 一处定。

## 2. 四端各自怎么消费

### 2.1 服务端注册表（装配，不另立清单）

* `SlashRegistry.java:66 withBuiltinCommands()` → `:70` 逐条 `for (CommandCatalog.Def d : CommandCatalog.serverDefs())`
  注册 `SlashCommand`，`name()/description()/execute()` 三个方法全部转发到同一个 `Def`。
  收口前这里自带 400 行手抄命令体，现在只剩"装配 + 技能那一段"。
* 技能派生命令仍叠在同一张注册表上（`registerSkillCommands`），不产生第二份清单。

### 2.2 TUI（两条输入实现共用同一段）

* `RawTerminalReader.java:41 LOCAL_COMMANDS` = `CommandCatalog.localNames()`（不再手抄 9 个名字）。
* `TerminalChannel.java:107` 帮助说明行读 `CommandCatalog.localDefs()`（不再手抄 `LOCAL_DOC`）。
* `TerminalChannel.java:116 commandRows()` → `:53`/`:236` `TerminalUI.printCommandTable(commandRows())`。
* **实际接住**的位置：`TerminalChannel.java:219 handleSlashCommand` 里
  `"/status" "/theme" "/feedback" "/confirm" "/help" "/?" "/exit" "/quit" "/q"` 九个分支
  （`:227—237`）；服务端那 20 条走 `slash.find(name)`（`:224`），所以这张分支表应当
  逐名字等于"通道私有一段"——由守卫的面 4 钉。

### 2.3 HTTP（把整张表序列化出去）

* 台账行：`HttpChannel.java:301` `new Route("GET", "/api/commands", BodyShape.JSON_ARRAY, ...)`。
* 分派：`HttpChannel.java:428—429` → `commandListResult()`。
* 体：`HttpChannel.java:701 commandListResult()` = `CommandCatalog.asRows()`（静态 29 行）
  + `SlashRegistry.live().skillCommandKeys()` 的技能段（`CommandCatalog.skillRow`）。
  每行自带 `name/description/args/scope/endpoints`，别名行多一个 `aliasOf`。
* **端点名的取舍**：用 `/api/commands`（roadmap 写法），不用台账既有的 `/bot/*` 前缀。
  依据：`_doc/005_testing/acceptance/p28/WIRING.md §2` 的双口径取证钉的就是"`/api/commands` 在 p28 不存在"，
  补上时沿用它点名的那个路径，台账与 roadmap 才不会各说一套；`/bot/*` 那批是"按状态读一嘴"的
  旧形状（`/bot/status`、`/bot/tools`），而这条端点吐的是**整表 + 可见性段**，形状不同、
  语义也不是 bot 状态。加了端点即同批改 `ROUTES.tsv`（由 `HttpRouteLedgerTest` 机器再生成，
  见 §4）。
* 消费端：`web/index.html`（§2.4）。

### 2.4 WEB 控制台（本笔新补的消费端）

* `web/index.html:1173 loadCommands()` —— `fetch('/api/commands')`，
  留 `row.endpoints` 含 `"web"` 且非 `aliasOf` 的行 → `WEB_COMMANDS`。
* `web/index.html:1188 renderQuickCommands()` —— 侧栏 `:831 <div id="quick-commands">` 由它渲染
  （旧版这里是 5 行手写 `<code>`，已删）。
* `web/index.html:1196 commandHelpText()` —— `/help` 的正文（`:1228` 那条分支用它）。
  旧版这里手抄 6 行、其中 `/exit` 本页根本没有分支，p28 `WIRING.md §3.4/§3.5` 量的就是这两笔。
* **实际接住**的位置：`web/index.html:1206 handleSlash` 的六个分支
  `/new /clear /status /tools /help /confirm` —— 与 WEB 段逐名字相等（守卫面 3 + E2E 判据 C）。
* 取不到表就什么都不广告（`catch` 里置空），**不退回手抄备用清单** —— 抄的那份正是漂移源头。

### 2.5 ACP（本笔新补的消费端）

* `AcpStreamPublisher.java:206 sendAvailableCommands()` —— 内容 =
  `CommandCatalog.defsFor(Endpoint.ACP)` + 技能段（`SlashRegistry.live()`）。
* `AcpStreamPublisher.java:225 commandNode(name, description, argsHint)` ——
  单条命令的帧内形状，`argsHint` 为空时**省略 `input`**（与 hermes `input=None` 同形）。
* 调用点：`AcpAgentServer.java:291 advertiseCommands(session)`，由 `:278`（`session/new`）与
  `:331`（`session/load`、`session/resume` 共用体）两处触发。
* 时机：`AcpConnection.java:228 scheduleAfterResponse(...)` + `:201 runAfterResponse()`
  —— 通知排在**本条请求的回包写出去之后**，不让客户端还没拿到 `sessionId` 确认就先收到通知。
* 只握手过的连接才广告：`AcpAgentServer.java:291` 体内的 `if (!initialized) return;`。
* **帧形状的取证位置**（不是凭记忆，行号是本笔实测）：
  权威文件 = `~/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/schema.py`

  | 实测行 | 内容 | 落在线上的键 |
  | --- | --- | --- |
  | `:2443` | `AvailableCommandsUpdate`：`session_update: Literal["available_commands_update"] = Field(alias="sessionUpdate")` | `sessionUpdate` |
  | `:2179—2181` | `available_commands = Field(alias="availableCommands")` | `availableCommands` |
  | `:2138` | `AvailableCommand{name, description, input}`（三字段无 alias） | 同名 |
  | `:1321` + `:1104—1120` | `AvailableCommandInput(RootModel[UnstructuredCommandInput])`、`hint: str` | `input: {"hint": …}` |
  | `:733` 等 | 外层 `sessionId` + `update` 包裹 | 与本仓 `AcpStreamPublisher.send()` 已有五种帧同一套 |

  发起侧的行为取证 = `~/.hermes/hermes-agent/acp_adapter/server.py:1699—1741`
  （`_available_commands` / `_send_available_commands_update` /
  `_schedule_available_commands_update`，调用点 `:1122` new、`:1170` load、`:1205` resume、
  `:1242` fork），`:1716`/`:1736` 两个方法体首行都是 `if not self._conn: return`，
  而 `_conn` 是 `initialize` 之后才拿到的（`:524`/`:530`）⇒ "没握手不广告"是她既有口径。
  `AcpMethods.java:81 UPDATE_AVAILABLE_COMMANDS` 这个常量在本笔之前**声明了但零引用**，
  现在它是第一次真的被用起来（`AcpStreamPublisher.java:206`）。
  `session/fork` 本仓是 `notImplemented`（`AcpAgentServer.java:169—176`），所以广告点 3/4，
  少的那个没有对应会话可广告。

## 3. 谁在守这条线

| 面 | 派生侧 | 实际吐出侧（怎么量的） | 测试 |
| --- | --- | --- | --- |
| 表内自洽 | `selfCheck()` | — | `CommandSurfaceConsistencyTest.catalogIsInternallyConsistent` |
| 服务端 | `serverNames()` | `SlashRegistry.withBuiltinCommands().coreCommandNames()` | `…serverRegistryRegistersExactlyTheCatalogServerSegment` |
| HTTP | `defs()` 全表 | **真起 HttpChannel（端口 0）** 打 `GET /api/commands`，逐行比 `description/args/endpoints` | `…httpCommandsEndpointServesExactlyTheCatalog` |
| WEB | `namesFor(WEB)` | 正则扫 `web/index.html` 的 `handleSlash` 分支字面量 | `…webConsoleHandlesExactlyTheWebSegment` |
| TUI | `localNames()` | 正则扫 `TerminalChannel.handleSlashCommand` 的 `"/x".equals(name)` | `…tuiHandlesExactlyTheChannelLocalSegment` |
| ACP | `namesFor(ACP)` | 真连接（内存通道）走完 `initialize → session/new`，解析回来的帧 | `AcpCommandAdvertisementTest`（6 条判据 + 2 条注入 + 2 条闸） |

守卫为什么必须存在（新失真形态）：单源之后漂移不再发生在"四份抄本之间"，而发生在
**"表里说某端能接 ⇔ 那端其实没接"** —— 那正是 `endpoints` 一列的全部含义，所以每一端都要
拿实际吐出的集合回来对一次，而不是信注释。

`CommandSurfaceConsistencyTest` 与 ACP 面分成两个类，是因为 ACP 面要复用包私有的
`AcpFakes` 真连接替身；两边的类注释互指，四面齐要两处一起看。

## 4. 台账

`_doc/005_testing/acceptance/p28/ROUTES.tsv` 由 `HttpRouteLedgerTest` 机器再生成（不是手敲）：
`分母: 26 行 (方法粒度) / 23 条路径`，新增行
`GET /api/commands JSON_ARRAY … array;rowfields:-:name,description,args,scope,endpoints,aliasOf`。
再生成命令：`mvn -o test -Dtest=HttpRouteLedgerTest#routesTsvIsInSyncWithLedger -Dp28.routes.write=true`。

## 5. 复算

```
# 杠①（全 reactor ×3）
rm -rf z-bot-core/target/surefire-reports && mvn -o test
python3 ~/.cache/zbot-integrate/b1parse.py <log>
# 守卫 + ACP 面
mvn -o test -Dtest=CommandSurfaceConsistencyTest,AcpCommandAdvertisementTest
# 杠③（真进程，本文件所有数字的出处）
python3 _doc/005_testing/acceptance/p19/p19_e2e.py --runs=3
# 杠②（变异台账）
python3 _doc/005_testing/acceptance/p19/p19_mutate.py
```

## 6. 本笔**没有**动的东西

命令语义/行为一个字没改，没新增命令，没动 skill 加载逻辑（D-2 由主编裁定）；
`config/BotConfig.java`、`tool/env/**`、`memory/**`、`delegate/**` 未触碰；
既有 ACP 判据 14 条**没有被修改**（成因与处置见 `EVIDENCE.md` §5）。
