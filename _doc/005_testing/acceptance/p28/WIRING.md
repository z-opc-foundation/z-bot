# WIRING — 控制台 / TUI 侧命令面接线对账（p28b 出 → 主编在 P19 并入后重锚）

> 本文件只干一件事：**把"命令面在几处、各自从哪来、差集是哪几条"机械地量出来**。
> 每一节的取数命令都可以直接重放；本文件里不允许有"我看了看代码写下的清单"。
>
> 谁改的：§0–§3 由主编在 `w14-p19` 并入 `main` 之后整段重锚（数从 `p28_e2e.py` 的派生行照抄）；
> §4/§5 是 p28b 写下的**原文快照**（那一棒按约不改 `SlashRegistry.java`/`web/index.html`，
> 只留接缝与仲裁项），一个字未动，兑现情况在 §4 顶部一句话里交代。
> 基线：本版 = `main` `79c77d0`；上一版 = 分支 `w13-p28` 的 `5e3b6e1`
> （`git show 5e3b6e1:_doc/005_testing/acceptance/p28/WIRING.md` 可整份取回）。

---

## §0 这是哪一版：P19 并入后的重锚

| | |
|---|---|
| 上一版 | 分支 `w13-p28`、HEAD `5e3b6e1`（p28b 出，作为 P19 的**输入**） |
| 本版 | `main` = `79c77d0`（`w14-p19` 的 `147389f`/`11e2d4f`/`bff2ccc` 并入之后） |
| 数从哪来 | 一律来自 `_doc/005_testing/acceptance/p28/p28_e2e.py` 在**真进程**上跑出的 `TABLE_*|` / `WIRING_EMIT|` 行；本次日志 `~/.cache/zbot-integrate/p28_emit.log` |
| 旧版原文 | `git show 5e3b6e1:_doc/005_testing/acceptance/p28/WIRING.md`（差集结论怎么变的，见 `EVIDENCE.md` 的 P28-lead-6） |

复跑入口只有一条（约 12 分钟，含起 `z-bot serve`）：

```
python3 _doc/005_testing/acceptance/p28/p28_e2e.py <tag> \
  | grep -E '^(TABLES|TABLE_|HAND_COPIED|WIRING_EMIT|BUCKET|CHECK\|T[1238])'
```

p28b 那一版的 R1/R2/R3 三支入口 **全部作废**，不作"顺手修一下"的处理：`w_diff.sh` / `w_registry.sh`
第一行硬 `cd /private/tmp/zbot-wt-p28` ⇒ 量的是写手树不是目标树（与 p25/p26/p27 那三支同一个病），
而且它们的锚（注册表 `name()` 的纯字面量 `return "X";`、`LOCAL_COMMANDS = Arrays.asList(`）
在 P19 之后**恒空** —— 一把恒返回 0 的尺比"没有尺"更坏，因为它看着像量过了。

---

## §1 命令面在几处、各自从哪来（`文件:行` 现取于 `79c77d0`）

| # | 段 | 位置 | 条数（实测量） | 怎么复算 |
|---|---|---|---|---|
| 1 | 服务端注册表（唯一单源 = `CommandCatalog` 的服务端段） | `slash/CommandCatalog.java:363 serverDefs()`，被 `slash/SlashRegistry.java:70` 消费装配 | **20** | `§0` 的 `WIRING_EMIT|服务端注册表（唯一单源）` |
| 2 | 通道私有一段（终端专有，故意不进注册表） | `ui/RawTerminalReader.java:41` `LOCAL_COMMANDS = Collections.unmodifiableList(CommandCatalog.localNames())` | **9** | `§0` 的 `WIRING_EMIT|终端私有命令` |
| 3 | 补全池 = 1 ∪ 2 | `channel/TerminalChannel.java:92 slashCommandPool()`（`:97` 并 `LOCAL_COMMANDS`） | 20+9 | `grep -n 'addAll(RawTerminalReader.LOCAL_COMMANDS)' z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java` |
| 4 | 帮助表行（该端可见且非别名） | `slash/CommandCatalog.java:405 rowsFor(Endpoint)` | 按端点各一份 | `§0` 的 `TABLE_D`（web 那份 6 条） |
| 5 | 对外口径 HTTP | `channel/HttpChannel.java:301` 登记 `GET /api/commands`、`:428` 分发、`:701 commandListResult()` | 29 行（含 3 行别名） | `§2` 的真进程读数 |
| 6 | WEB 消费端 | `web/index.html:1173 loadCommands()` → `:1175 fetch('/api/commands')`，帮助文本 `:1196 commandHelpText()` | 6 | `§0` 的 `TABLE_D=` |
| 7 | ACP 消费端 | `acp/AcpAgentServer.java:291 advertiseCommands()` → `:302 publisher.sendAvailableCommands()`（`acp/AcpStreamPublisher.java:206`）；`:278/:331` 两处调用点 | 20 | `grep -n 'advertiseCommands' z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpAgentServer.java` |
| 8 | TUI 分发优先级 | `channel/TerminalChannel.java:225 if (command != null)` ⇒ 注册表命中就不再走私有分支 | 9 条私有全有分支接住 | `sed -n '225,245p' z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java` |

`SlashRegistry.java` 从今天起只做装配 + 技能段追加，**名字/说明/执行入口都不在它里面**。
所以 p28b 那一版 §1.1 的整套静态计数法（"20 条纯字面量 + 1 条变量名 = 21 个 `name()`、22 次 register"）
**作废**：今天 `grep -c 'public String name()' SlashRegistry.java` 只数得到匿名类那一处，量不到任何名字。
等价的事实换由两处钉住，都不靠本文件：单源自洽 `CommandCatalog.java:513 selfCheck()`，
与"注册表注册的 == 表的服务端段"这条测试
（`z-bot-core/src/test/java/com/zifang/z/bot/slash/CommandSurfaceConsistencyTest.java:80`
`serverRegistryRegistersExactlyTheCatalogServerSegment`）。

### §1.1 三处"手抄清单回来了没有"——回归探测器，实测三路全空

`p28_e2e.py` 的 T8 拿 P19 之前的三种形状当探针，任何一路非空 ⇒ 有人绕开单源又抄了一份：

```
$ grep '^HAND_COPIED|' ~/.cache/zbot-integrate/p28_emit.log
HAND_COPIED|registry_name_literals=[] reader_literals=[] web_list_literals=[]
```

负向判据的阳性对照（证明这三路不是"解析器坏了所以恒空"）在 `~/.cache/zbot-p28-lead/selfcheck_v2.py`
里喂 P19 之前的原文各跑一遍（`SELFCHECK|…` 那几行），跑法见 `EVIDENCE.md` P28-lead-2。

---

## §2 `/api/commands`：**已存在**（P19 兑现了 p28b 记的那条接缝）

上一版本节标题是"不存在（真进程 + 全仓双向取证）"，那个结论在它自己的基线上是对的；
`79c77d0` 之后它存在了。形状是真进程量出来的（`~/.cache/zbot-p28-lead/probe_api_commands.py`，
临时 profile + stub key，`serve` 起在空闲回环口）：

```
PROBE|/api/commands status=HTTP/1.1 200 OK bytes=4321 ctype=None
PROBE|top_type=list rows=29
PROBE|row0_keys=args,description,endpoints,name,scope
PROBE|segments={"acp": 20, "http": 20, "tui": 29, "web": 6}
PROBE|aliases=3
PROBE|row0={"name": "/status", "description": "显示当前状态（模型 / 工具 / 技能）", "args": "", "scope": "channel", "endpoints": ["tui", "web"]}
PROBE|method_probe GET -> 200
PROBE|method_probe POST -> 405
PROBE|options -> 204
```

三格如实记账，不粉：

- **顶层是数组**（`BodyShape.JSON_ARRAY`，`ROUTES.tsv` 第 30 行登记一致）。
- **`aliasOf` 随状态分叉**：`row0_keys` 只有 5 个键（非别名行没有 `aliasOf`），
  台账声明的 `rowfields` 是**并集**口径 ⇒ 与 `/api/agent/register` 同一形状的"顶层字段随状态分叉"，
  台账只钉"必有那一列"。要不要统一成"永远带 `aliasOf`（值可为 null）"归主编裁定，见 §5。
- `ctype=None`：这条 JSON 响应没带 `Content-Type`。SSE 那几条带（§B 组钉着），
  JSON 面缺 ⇒ 前端靠 `r.json()` 能跑，但台账里 `BodyShape.JSON_ARRAY` 的兑现程度就只到"形状对、媒体类型没说"。
  这条**本尺未钉**（钉它要先定"该不该恒带"），记在 §5。

---

## §3 差集逐条点名（数与成员全部照抄 `WIRING_EMIT|` 行）

四张表的实测输入（`§0` 那条命令产出，A/B/D 来自真进程 `/api/commands` 的 `endpoints` 分段，
C 来自盘上 `index.html` 的分支）：

```
TABLES|A=20 B=9 C=6 D=6 api_status='200' rows=29
TABLE_A=/agents /background /checkpoints /clear /compress /cron /memory /model /new /queue /rollback /sessions /skill /skills /steer /stop /switch /sync /tools /usage
TABLE_B=/? /confirm /exit /feedback /help /q /quit /status /theme
TABLE_C=/clear /confirm /help /new /status /tools
TABLE_D=/clear /confirm /help /new /status /tools
```

A=服务端段（http），B=通道私有段（tui 减 http），C=web 真分支，D=web 该广告的那一段（服务端说的）。

| 桶 | 条数 | 成员 |
|---|---|---|
| 3.1 只在服务端注册表（web 侧答"未知命令"） | **17** | `/agents /background /checkpoints /compress /cron /memory /model /queue /rollback /sessions /skill /skills /steer /stop /switch /sync /usage` |
| 3.2 只在 TUI 私有（不在注册表 ⇒ web 更不可能有） | **9** | `/? /confirm /exit /feedback /help /q /quit /status /theme` |
| 3.3 只在 web 有分支（服务端两段都没有） | **0** | 空集 |
| 3.4 web 广告了但没有分支接住 | **0** | 空集 —— 上一版这一格点名的是 exit 那条假广告，见下面 §3.4′ |
| 3.5 web 有分支但没广告 | **0** | 空集 —— 上一版这一格点名的是 help（有分支没广告），见 §3.4′ 末尾 |
| 3.6 两边都有、实现不同 | **6** | `/clear /confirm /help /new /status /tools` |
| **合计点名条数** | **32** | 17+9+0+0+0+6（上一版是 34） |

**3.4/3.5 从 1 掉到 0 不是量具坏，是 P19 把两条假广告关掉了**：

- `/exit`：上一版它是"web 手抄的 `/help` 清单里写着、但一个分支都没有"。现在 web 不再自己抄清单
  （`index.html:1173` 读 `/api/commands` 的 web 段），而 `CommandCatalog` 没把 `/exit` 标成 WEB 可见 ⇒
  它从广告里消失，差集自然为空。守卫落在两处：`webDoesNotAdvertiseExitAndCannotRegressSilently`
  （`CommandSurfaceConsistencyTest.java:188`）+ 本尺的 3.4 格。
- `/help`：上一版是"有分支却没进广告清单"。现在 `/help` 的分支渲染的就是表本身（`index.html:1231`
  那条 `命令列表（来自 /api/commands 的 web 段）`）⇒ 同源，不会再漂。

### §3.4′ 但"web 答未知命令"这一类没灭绝：17 条仍在

3.1 那 17 条是**缺转发**不是缺实现：服务端能力已经在（`http` 段里有它们），
web 侧没有对应分支 ⇒ 用户在控制台敲 `/agents` 仍走"未知命令"。P19 的选择记在
`CommandCatalog` 的可见性政策里（技能段/服务端段不默认进 WEB），
要不要下沉归 §5 的仲裁项。**本尺不替它裁**，只把 17 这个数字钉在台账上。

### §3.5′ 负向判据双跑：「web 独有 = 0 条」怎么证明不是空尺

同一套差集代码，换个确实非空的桶就非空：3.6 那 6 条与 3.1 那 17 条都是从
`A/B/C/D` 同一次派生里 `comm` 出来的（见上面四行 `TABLE_*`）⇒ 3.3/3.4/3.5 的 0 不是"没跑"。
另外尺自己还有一条硬前置：`T8` 里 `cmd_rows` 为空或 `A`/`D` 为空 ⇒ 直接判红，
所以"`/api/commands` 取不到 ⇒ 全体变 0 ⇒ 全体绿"这条路是堵住的。

### §3.6 两边都有、行为不同（6 条，P19 之后差在哪）

上一版这一节列了 6 条"来源不同"，其中 `/help`（手写 6 行字符串）与 `/tools`（已同源）两条的成因在 P19 后消失。
剩下 4 条是**实现语义**的差，不是清单的差，逐条给位置：

| 命令 | 终端侧 | web 侧 | 差 |
|---|---|---|---|
| `/new` | 注册表 `/new`（`CommandCatalog` 服务端段）→ 服务端建会话 | `index.html:908` 与 `:1277` **两个** `function newSession` | 见 §3.6′，这条仍未闭，且**后一份赢** |
| `/clear` | 注册表 `/clear` → `agent.clearMemory()` | `clearSession()`（`:1292`）= `newSession()` + `GET /bot/clear` | web 的 `/clear` 顺手换会话，终端的不会 |
| `/status` | `TerminalChannel.java:228` → `renderStartupPanel()` | `index.html` → `GET /bot/status` | 两支实现，字段是否同口径未裁（§5） |
| `/confirm` | `TerminalChannel.java:234` → `cmdConfirm()` 吃本地 `pendingConfirm` | `index.html` → `POST /bot/confirm` 带 `toolName/argsJson` | 一次确认谁能消费，跨端未对账（§5） |

### §3.6′ 缺陷 P28b-W1（web 的 `/new` 在服务端什么都没做）—— P19 并入后**仍然存在**

`index.html` 里 `newSession` 声明了两次，后一份赢；两份的差别不是风格：

```
$ grep -n 'function newSession' z-bot-core/src/main/resources/web/index.html
908:    async function newSession() {      ← fetch POST /api/sessions，用服务端返回的真 id
1277:   async function newSession() {      ← 只清本地 + GET /bot/clear + 自造 'new_'+Date.now()

$ grep -oE 'function [A-Za-z0-9_]+' z-bot-core/src/main/resources/web/index.html | sort | uniq -c | awk '$1>1'
   2 function newSession

$ node ~/.cache/zbot-p28-lead/js_lastwins.js
WINNER=B_local_fake
```

⇒ 用户在控制台点"新建会话"或敲 `/new`，徽标显示 `session: new`，而 `currentSessionId` 是浏览器自己编的
字符串；随后任何带这个 id 的 `/api/session/switch|delete|messages` 都是 404
（404 那一半由 p28a 的测试 `switchAndDeleteTellTheTruthAboutUnknownIds` 钉着）。
**本尺未钉这一条**：钉它要先裁定"`/new` 该不该顺手清记忆"（`clearSession()` 现在靠 `newSession()`
里那一次 `GET /bot/clear` 才真的清），改错方向会把用户已经习惯的行为弄丢 ⇒ 归 §5 仲裁项，
不在收口 P19 的这一棒里顺手改。

---

## §4 给 P19 的接缝（本棒按约不实现）

> **兑现交代（主编，`79c77d0` 之后）**：这一节的接缝 P19 已经做了 —— 单源搬到
> `slash/CommandCatalog.java`、`GET /api/commands` 已存在、WEB/ACP 两个消费端各读自己那一段。
> 逐条兑现与它自己的四杠读数见 `_doc/005_testing/acceptance/p19/` 的 WIRING.md / EVIDENCE.md / LEDGER.tsv；
> 本节文字保留 p28b 写下时的原样，作为需求快照（其中 17 条缺转发仍未闭，见 §3.4′）。

1. `/api/commands` 现在**不存在**（§2 两个独立口径）。它的天然单源就是
   `SlashRegistry.live()`（`SlashRegistry.java:461/:463`）+ `coreCommandNames()` `:468`
   的"核心 vs 技能"分段，TUI 已经在吃这张表（§1 第 3/4 行）。
2. 台账侧要同一份分母：`HttpChannel.routes()`/`paths()`（`:298/:303/:314`）加一行
   `GET /api/commands`，`shape=JSON_ARRAY`，`row_count_from=SlashRegistry.live()`；
   漂移卫兵 `HttpRouteLedgerTest#ledgerPathsMatchDispatchLiterals` 会自动要求
   `dispatch()` 里出现该字面量 ⇒ 加端点不可能加一半。
3. 条数尺已经现成：`HttpRouteShapeTest#countOrTotalFieldsNeverLie` 会普查任何
   `count/total/size` 字段，所以 `/api/commands` 若广告 `count` 就必须等于数组长度。
4. 差集里最省的是 §3.1 的 **17 条**（能力已在服务端），最贵的是 §3.6 的 `/help`
   与缺陷 P28b-W1。

---

## §5 本棒没裁的（不在写域内，点名给主编）

- `/status` 的两支实现（本地面板 vs `GET /bot/status`）字段是否同口径：未逐列对。
  取数起点：`TerminalChannel.java:227` 与 `HttpChannel` 台账行 `/bot/status`。
- `/confirm` 的一次性消费跨端语义：终端 `pendingConfirm` 与 HTTP `POST /bot/confirm`
  是否共用同一张待确认台账，未量。
- `LOCAL_DOC` 6 条说明行与注册表 20 条的 `description()` 在**文案**层是否重复（比如
  `/status` 两处各写了一份说明）：只数了条数，没做文本对账。
