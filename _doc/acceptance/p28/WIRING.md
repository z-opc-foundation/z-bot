# WIRING — 控制台 / TUI 侧命令面接线对账（p28b 出，P19 的输入）

> 本文件只干一件事：**把"命令面在几处、各自从哪来、差集是哪几条"机械地量出来**。
> 每一节的取数命令都可以直接重放；本文件里没有任何"我看了看代码写下的清单"。
>
> 写域纪律：`slash/SlashRegistry.java` 与 `web/index.html` **归 P19**，本棒一行未改
> （`git log --oneline` 里对 index.html 只有一条"原样封存 p28a 在途改动"的 commit）。
> 本棒只做控制台/TUI 侧，并把 `/api/commands` 的**接缝**如实记在这里。
>
> 基线：分支 `w13-p28`，本文件写成时 HEAD=`5e3b6e1`（已并 `main=f237a25`）。

---

## §0 复跑入口（三条命令重放本文件的全部数字）

```
# R1 三张命令表 + 差集
bash ~/.cache/zbot-p28-lead/w_diff.sh
# R2 真进程读 /api/commands 与 served 字节里的命令面（需先 mvn -o package -DskipTests）
python3 ~/.cache/zbot-p28-lead/p28b_pty_e2e.py a | grep -E '^S5|^CHECKS'
# R3 注册表静态字面量（含 file:line）
bash ~/.cache/zbot-p28-lead/w_registry.sh
```

R1 的输出是本文件 §3 的唯一来源；R2 是 §2 与 §3.4/§3.5 的唯一来源。
`w_diff.sh` / `w_registry.sh` 只写 `~/.cache/zbot-p28-lead/sets/` 下的中间文件，不落 `/tmp`。

---

## §1 控制台/TUI 侧命令来源（文件:行）

命令名在**终端**这一侧只有一张表，分三段拼出来，没有第二份硬编码清单：

| # | 来源 | 位置 | 条数（实测量） | 取数命令 |
|---|---|---|---|---|
| 1 | 服务端注册表（唯一单源） | `slash/SlashRegistry.java:66` `withBuiltinCommands()`；每条命令的 `name()` 在 `:71…:437` | **20** | `bash ~/.cache/zbot-p28-lead/w_registry.sh \| grep -vc DYNAMIC` |
| 1b | 技能派生命令（同一张表尾追，名字是变量不是字面量） | `SlashRegistry.java:452` 起、`registerSkillCommands(...)` `:495` | 运行时才知道，静态口径**排除**（见 §1.1） | `bash ~/.cache/zbot-p28-lead/w_registry.sh \| grep -c '^DYNAMIC'` ⇒ `1` |
| 2 | 终端私有命令（需要本地 UI 状态，故意不进注册表） | `ui/RawTerminalReader.java:36` `LOCAL_COMMANDS` | **9** | `R1` 的 B 表 |
| 3 | 补全池 = 1 ∪ 2 | `channel/TerminalChannel.java:93` `slashCommandPool()`（`:95` 遍历 `slash.all()`，`:98` 并 `LOCAL_COMMANDS`） | 20+9 | `grep -n 'addAll(RawTerminalReader.LOCAL_COMMANDS)' z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java` |
| 4 | 帮助表 = 1 ∪ 2 的说明行 | `TerminalChannel.java:114` `commandRows()`；私有说明 `LOCAL_DOC` `:104-110`（6 条主条目，别名折进主条目） | 20+6 | `grep -c 'LOCAL_DOC.put' z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java` ⇒ `6` |
| 5 | 输入实现分两支：JLine 主路 / RawTerminalReader 降级 | `ui/LineEditor.java:122` `fallback = new RawTerminalReader()`、`:126` `setSlashCommands(names)`、`:174/:177` 缺省池回落 `LOCAL_COMMANDS` | — | `grep -n 'RawTerminalReader' z-bot-core/src/main/java/com/zifang/z/bot/ui/LineEditor.java` |
| 6 | 分发：注册表**优先**，私有分支在后 | `TerminalChannel.java:224-243`（`:225` `if (command != null)` ⇒ 注册表命中就不再走私有分支） | 9 条私有全部有分支接住 | `sed -n '224,243p' z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java` |

注册表缓存（`/help`、`/skills sync` 之后重算同一张表，不另立清单）：`SlashRegistry.java:461` `LIVE`、`:463` `live()`、`:468` `coreCommandNames()`、`:479` `refreshSkillCommands()`。

### §1.1 静态 20 条与"注册了 22 次"的差是怎么对上的

`grep -c 'public String name()' SlashRegistry.java` ⇒ **21**，而 `grep -c 'register(new SlashCommand()'` ⇒ **22**。
两把尺都不等于 20，差在哪必须有交代，不许靠人眼数：

```
$ bash ~/.cache/zbot-p28-lead/w_registry.sh | tail -1
DYNAMIC	514	                    return "[skill " + skill.name + "] " + ...
```

即 20 条纯字面量 + 1 条 `name()` 返回变量（技能派生，`:502` 那个 `register`）= 21 个 `name()`。
判据钉在 `R1` 脚本头部注释里：**只有"紧跟 `name()` 的纯字面量 `return "X";`"才算静态命令**，
带 `+` 拼接的一律进 DYNAMIC 桶点名。第一版脚本把 `description()` 的
`return "[skill " …` 误计成命令名（假阳性），已按该判据剔除并保留可见的 DYNAMIC 行。

---

## §2 `/api/commands`：**不存在**（真进程 + 全仓双向取证）

### 2.1 真进程读数（`z-bot serve` 起在 127.0.0.1 空闲口，`--config-dir` 临时 profile + stub key）

```
S5 /api/commands -> code=404 body=b'{"ok":false,"error":"not found: /api/commands"}'
```

同一次运行里量到的**同类接缝是有的、能用**：

| 端点 | 状态码 | 条数读数 | 来源 |
|---|---|---|---|
| `GET /api/commands` | **404** | 无此路由 | 台账里没有这一行 |
| `GET /bot/tools` | 200 | `n=14` | `agent.getToolkit()`，`HttpChannel` 台账行 `shape=JSON_ARRAY` |
| `GET /api/sessions` | 200 | `n=0` | 临时 profile 的 `sessions/_index.json`（0 行是真的，不是没读） |
| `GET /console` | 200 | served HTML `handled=['/clear','/help','/new','/status','/tools']`、`advertised=['/clear','/confirm','/exit','/new','/status','/tools']` | 从**服务端吐出的字节**再派生，不读盘上的文件 |

复跑：`python3 ~/.cache/zbot-p28-lead/p28b_pty_e2e.py a | grep -E '^S5'`

### 2.2 全仓字符串取证（负向判据双跑 + 阳性对照）

```
== N1 全仓 -c（命中桶要点名）==
_doc/hermes-roadmap.md:1                 ← 唯一命中桶：路线图文档，不是代码
== N2 限定 src/main 前缀 ==
(空 = 0 命中)
== N3 阳性对照：同一把尺换成确实存在的 bot/tools ==
z-bot-core/src/main/…: …                 ← 尺本身是活的
== N4 dispatch 里到底有没有 commands 这条字面量 ==
(空 = 台账里没有，与真进程 404 相互独立)
```

**两个独立口径一致**：静态台账 `HttpChannel.paths()`（22 条，见
`_doc/acceptance/p28/ROUTES.tsv`）不含 `/api/commands`；真进程答 404。
⇒ 记 `不存在`，不是"还没测到"。

---

## §3 差集逐条点名（每条都带取证命令）

以下 A/B/C/D 四张表全部由 `bash ~/.cache/zbot-p28-lead/w_diff.sh` 现场派生：
A=服务端注册表 20，B=TUI 私有 9，C=web 有分支 6，D=web `/help` 广告 6。

| 桶 | 条数 | 成员 |
|---|---|---|
| 3.1 只在服务端注册表（web 侧答"未知命令"） | **17** | `/agents /background /checkpoints /compress /cron /memory /model /queue /rollback /sessions /skill /skills /steer /stop /switch /sync /usage` |
| 3.2 只在 TUI 私有（不在注册表 ⇒ web 更不可能有） | **9** | `/? /confirm /exit /feedback /help /q /quit /status /theme` |
| 3.3 只在 web 有分支（注册表 ∪ TUI 都没有） | **0** | 空集 —— 见 §3.3 的负向双跑 |
| 3.4 web 广告了但没有分支接住 | **1** | `/exit` |
| 3.5 web 有分支但没广告 | **1** | `/help` |
| 3.6 两边都有、实现不同 | **6** | `/new /clear /tools /status /confirm /help` |
| **合计点名条数** | **34** | 17+9+0+1+1+6 |

复跑：

```
$ bash ~/.cache/zbot-p28-lead/w_diff.sh | sed -n '/=== DIFF/,$p'
```

### 3.1 「17 条只在服务端」在终端里是可用的、在 web 里是死的

同一名字在两侧的实际行为（真进程量）：

- TUI：`/agents` 之类命中注册表 ⇒ `TerminalChannel.java:225` `command != null` 分支执行；
  S4b 抽样 7 条（`/agents /checkpoints /background /rollback /compress /queue /steer`）
  **7/7 出现在真 REPL 的 `/help` 表里**。
- web：`index.html:1205` 直接 `未知命令: … 输入 /help 查看所有命令`（17 条一条都不转发）。

⇒ 这 17 条不是"缺实现"，是**缺转发**：服务端能力已经在，UI 没接。
这是 P19 最该省事的 17 条。

### 3.2 「9 条只在 TUI 私有」里有 5 条在 web 侧连名字都没有

`B − A` = 9 条，但按"web 是否有对应物"再切一刀：

| 私有命令 | web 侧 | 取数命令 |
|---|---|---|
| `/status` `/confirm` `/help` `/exit` | 有名字（见 3.4/3.6） | `R1` 的 C 表 |
| `/theme` `/feedback` `/?` `/q` `/quit` | **完全没有** | `grep -c 'theme' z-bot-core/src/main/resources/web/index.html` ⇒ 0（`feedback` 同） |

⇒ 主题切换与回复评分是**终端独有能力**，hermes 侧要的是多端一致，这 5 条要 P19 裁定
"下沉到注册表 + `/api/commands` 广告"还是"承认终端专有"。

### 3.3 负向判据双跑：「web 独有 = 0 条」

`comm -23 C (A ∪ B)` ⇒ 空。为了不把"我看漏了"当成"零命中"：

```
$ bash ~/.cache/zbot-p28-lead/w_diff.sh | grep -A1 '只在 web 有分支'
=== DIFF: 只在 web 有分支（注册表 ∪ TUI 都没有） ===
                                        ← 空行=空集，且上一行标题在场（证明尺跑到了这一支）
```

阳性对照（同一支尺，换个确实存在的桶）：把 `C` 换成 `D` 就非空 —— §3.4 的 `/exit`
正是从同一套 `comm` 出来的，所以这一支不是坏尺。

### 3.4 `/exit` 是假广告（两个口径各量一次）

```
$ sed -n "/命令列表：/,/\`)/p" z-bot-core/src/main/resources/web/index.html | grep -oE '^/[a-z-]+' | sort
/clear /confirm /exit /new /status /tools
$ grep -n 'exit' z-bot-core/src/main/resources/web/index.html
1197:/exit      — 退出（仅 Terminal 模式）`);      ← 全文件仅此一处，没有任何分支
```

真进程复核（S5f）：`advertised` 含 `/exit`、`handled` 不含 ⇒ 同一条差集在**盘上**与
**服务端吐出的字节**上各成立一次。

### 3.6 两边都有、行为不同（6 条，逐条钉住差在哪）

| 命令 | 终端侧 | web 侧 | 差 |
|---|---|---|---|
| `/help` | `TerminalChannel.java:235-236` → `commandRows()`（注册表派生 26 行） | `index.html:1190-1196` 手写 6 行字符串 | **来源不同** ⇒ 表内容必然漂移，这条就是 P19 的主案 |
| `/new` | 注册表 `/new`（`SlashRegistry.java:71`）→ `agent.newSession()`，服务端建会话 | `index.html:1170` → `newSession()`，**见下面的缺陷** | 一个动服务端，一个只动本地 |
| `/clear` | 注册表 `/clear`（`:87`）→ `agent.clearMemory()` | `index.html:1172` → `clearSession()` = `newSession()` + `GET /bot/clear` | web 的 `/clear` 顺手换会话，终端的不会 |
| `/tools` | 注册表 `/tools`（`:151`）→ `agent.describeTools()` | `index.html:1177` → `GET /bot/tools`（p28a 已改成 Toolkit 单源） | 渲染不同、**来源已同源**，这条算已对齐 |
| `/status` | `TerminalChannel.java:227` → `renderStartupPanel()` | `index.html:1174` → `GET /bot/status` | 两支实现，字段是否同口径本棒未裁（见 §5） |
| `/confirm` | `TerminalChannel.java:233` → `cmdConfirm()` 吃本地 `pendingConfirm` | `index.html:1198` → `POST /bot/confirm` 带 `toolName/argsJson` | 一次确认谁能消费，跨端未对账（见 §5） |

#### 缺陷 P28b-W1（web `/new` 与 `/clear` 其实什么都没在服务端做）— 归 P19

`index.html` 里 `newSession` **声明了两次**，后一份赢：

```
$ grep -oE 'function [A-Za-z0-9_]+' z-bot-core/src/main/resources/web/index.html | sort | uniq -c | awk '$1>1'
   2 function newSession
$ grep -n 'async function newSession' z-bot-core/src/main/resources/web/index.html
911:    async function newSession() {          ← POST /api/sessions，拿服务端真 id
1243:   async function newSession() {          ← 只清本地 + GET /bot/clear + 自造 'new_'+Date.now()
```

语言规则不是"我以为"，是实测：

```
$ node ~/.cache/zbot-p28-lead/js_lastwins.js
WINNER=B_local_fake
```

真进程复核（S5g）：`/console` 返回的字节里 `function newSession` 计数 = 2。

⇒ 用户点 `/new` 之后，界面徽标显示 `session: new`，而 `currentSessionId` 是浏览器自己
编的字符串；随后任何 `/api/session/switch|delete|messages` 带这个 id 都是 404
（404 行为由 p28a 台账测试 `switchAndDeleteTellTheTruthAboutUnknownIds` 钉着）。
本棒**未改** `index.html`（P19 所有权），只把证据钉在这。

---

## §4 给 P19 的接缝（本棒按约不实现）

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
