# P25 ACP（IDE 面）EVIDENCE

STATUS: 未跑（§0 实测已完成，代码未落）

写手：p25a　分支：`w12-p25`　起点：`53222e1`（主仓 main）
工单：`~/.cache/zbot-p25-lead/dispatch_p25a.md`

> 本文件 §0（p25a 所写）保留原样：那份协议面取证是复算过的、行号可核，没有理由重写。
> **p25a 死于第 150 轮，杠①②③④ 一条没跑，且 acp/ 整套留在未跟踪状态**；
> 下面 `§0b` 起是 p25b（收口棒）的实测。§0 里被盘上否掉的两处，p25b 在 `§0b.3` 点名。

---

## §0 第 0 步实测（逐条自己复算，与工单并排）

| 项 | 工单读数（09-26 17:1x） | 我的复算 | 命令 | 判定 |
|---|---|---|---|---|
| z-bot 现状 | `acp` 全仓 0 命中 | 空输出、`rc=1` | `git grep -il 'acp' HEAD -- 'z-bot-core/src'` | ✅ 相符 |
| CLI seam 行数 | `ZBot.java` 68 行 | `68 z-bot-core/.../ZBot.java` | `wc -l z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java` | ✅ 相符 |
| subcommands 位置/条数 | `:40—:42`、9 条 | 实测 9 条（chat/repl/serve/gateway/status/sessions/send/pair/mcp），块占 `:40—:42`，`McpCommand.class}` 收尾在 `:42` | `sed -n '39,43p' z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java` | ✅ 相符 |
| `cli/` 形状 | 10 个类（`AgentOptions` + 各 `*Command`） | main 侧正好 10 个；`git ls-files \| grep '/cli/'` 另含 1 个测试类 `SessionsCommandTest.java` ⇒ 总 11 行 | `git ls-files \| grep '/cli/'` | ✅ 相符（工单说"10 个类"指 main，实测 main=10） |
| 她的锚 | `acp_adapter/` 11 文件 / 5,373 行 | `5373 total`、11 个 `.py` | `wc -l ~/.hermes/hermes-agent/acp_adapter/*.py \| tail -1` | ✅ 相符 |
| 参照副本 git | `cbc1054e2` | `cbc1054e2387c51b51f128b24a507481dc5b221d` | `git -C ~/.hermes/hermes-agent rev-parse HEAD` | ✅ 相符 |
| 基线 `@Test` | 735 / 71 文件 | **735** 条 / **71** 个含 `@Test` 的文件（口径：`grep -rl`；测试 .java 总数是 77） | `grep -rho "@Test" …\|wc -l`、`grep -rl "@Test" …\|wc -l` | ✅ 相符 |
| 杠④ T0（开工） | 8 / 2dadaed0 / 690ddbc0 | `8` / `2dadaed0` / `690ddbc0`（17:27:41） | `ls -A ~/.zbot\|wc -l`、两个 `md5 -q…\|cut -c1-8` | ✅ 相符 |
| 真 key 只量长度 | 125 字符 | `125` | `awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties` | ✅ 相符 |

### §0.1 工单"方法面计数"被盘上否掉的地方（工单自己要求点名）

工单 §0 那行 `initialize=1 prompt=2 cancel=1 fork=2 authenticate=1 / newSession=0 loadSession=0` 是**在 `acp_adapter/` 目录内按字面量数**的。复算结论：这些字面量之所以稀疏，是因为 **ACP 的 wire 方法名带 `session/` 命名空间、Python 侧方法是 snake_case**，两边都不是裸字面量。逐格对到盘上：

```
$ grep -n "    async def \(initialize\|authenticate\|new_session\|load_session\|resume_session\|cancel\|fork_session\|list_sessions\|prompt\|set_session_model\|set_session_mode\|set_config_option\)" acp_adapter/server.py
865:    async def initialize(
899:    async def authenticate(self, method_id: str, **kwargs: Any) -> AuthenticateResponse | None:
1113:    async def new_session(
1133:    async def load_session(
1180:    async def resume_session(
1215:    async def cancel(self, session_id: str, **kwargs: Any) -> None:
1229:    async def fork_session(
1249:    async def list_sessions(
1296:    async def prompt(
2022:    async def set_session_model(
2056:    async def set_session_mode(
2072:    async def set_config_option(
```

⇒ `newSession=0 / loadSession=0` **不是因为协议里没有这两个方法**，而是 Python 实现叫 `new_session` / `load_session`（`server.py:1113`、`:1133`），wire 上叫 `session/new` / `session/load`。

### §0.2 ACP 协议方法全集（权威出处 = 她 import 的那个 SDK 的派发表，不是字面量）

她依赖 `agent-client-protocol==0.9.0`（`~/.hermes/hermes-agent/pyproject.toml:221`：`acp = ["agent-client-protocol==0.9.0"]`），SDK 装在
`~/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/`。派发表就是 `acp/meta.py` 的 `AGENT_METHODS` —— **这就是工单 §0 要的"我数到哪一行"**：

```
$ cat -n ~/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/meta.py
     1  # Generated from schema/meta.json. Do not edit by hand.
     2  # Schema ref: refs/tags/v0.11.2
     3  AGENT_METHODS = {
     4      "authenticate": "authenticate",
     5      "initialize": "initialize",
     6      "session_cancel": "session/cancel",
     7      "session_close": "session/close",
     8      "session_fork": "session/fork",
     9      "session_list": "session/list",
    10      "session_load": "session/load",
    11      "session_new": "session/new",
    12      "session_prompt": "session/prompt",
    13      "session_resume": "session/resume",
    14      "session_set_config_option": "session/set_config_option",
    15      "session_set_mode": "session/set_mode",
    16      "session_set_model": "session/set_model",
    17  }
    18  CLIENT_METHODS = {
    19      "fs_read_text_file": "fs/read_text_file",
    20      "fs_write_text_file": "fs/write_text_file",
    21      "session_request_permission": "session/request_permission",
    22      "session_update": "session/update",
    23      "terminal_create": "terminal/create",
    24      "terminal_kill": "terminal/kill",
    25      "terminal_output": "terminal/output",
    26      "terminal_release": "terminal/release",
    27      "terminal_wait_for_exit": "terminal/wait_for_exit",
    28  }
    29  PROTOCOL_VERSION = 1
```

⇒ **agent 侧（客户端→z-bot）协议方法全集 = 13 条**：`initialize`、`authenticate`、`session/new`、`session/load`、`session/prompt`、`session/cancel`、`session/fork`、`session/list`、`session/resume`、`session/close`、`session/set_mode`、`session/set_model`、`session/set_config_option`。
⇒ **client 侧（z-bot→客户端）9 条**：`fs/read_text_file`、`fs/write_text_file`、`session/request_permission`、`session/update`（通知）、`terminal/*` 5 条。
⇒ 版本口径：`PROTOCOL_VERSION = 1`（`meta.py:29`），schema ref `v0.11.2`（`meta.py:2`），SDK `0.9.0`。字段名以 `acp/schema.py` 的 alias 为准（下面 §0.4）。

她和这份全集的差：**`HermesACPAgent`（`server.py:450`）实现了 12 条，独独没有 `session/close`** —— 全目录 grep `close_session` 只命中 SDK 自己的可选声明（`acp/interfaces.py:227`），`acp_adapter/server.py` 内 0 命中。这条差不是遗漏就写进 §未做。

z-bot 本期取哪几条：**11 条真处理路径**（`initialize` / `authenticate` / `session/new` / `session/load` / `session/list` / `session/prompt` / `session/cancel` / `session/resume` / `session/set_mode` / `session/set_model` / `session/set_config_option`）+ `session/fork`、`session/close` 明确"大声未实现"（见 §未做）。

### §0.3 基线 @Test 文件数

```
$ grep -rl "@Test" --include='*.java' z-bot-core/src/test | wc -l
<TODO>
$ grep -rho "@Test" --include='*.java' z-bot-core/src/test | wc -l
     735
```

> **[p25b] 这条 `<TODO>` 已在 §0b.3 补齐**（main = 97 文件 / 含 ACP = 102；杠① 实跑 101 类 / 1101 例，
> 对账见 §2.1）。那个 `735` 与本棒在 HEAD 上实测的 `1103` 差 368，**本棒未复量 p25a 当时的树**，
> 差因（基线漂移 / glob 口径 / 计数方式）未查 ⇒ 记在这里当未结账，不许写成"两者已对齐"。

工单写"735 / 71 文件"。`@Test` 条数 ✅ 对上；"71 文件"与我的两种数法都对不上（测试 .java 文件总数 77），见 §口径。

### §0.4 传输与字段口径（实测，不凭记忆）

- 传输：**stdio 上的 newline-delimited JSON-RPC 2.0**，不是 `Content-Length` 帧。出处 `acp/connection.py:62`：`"""Minimal JSON-RPC 2.0 connection over newline-delimited JSON frames."""`，读侧 `acp/stdio.py:56` `data = sys.stdin.buffer.readline()`。日志一律走 stderr，stdout 只放协议（`acp_adapter/entry.py` 的 `_setup_logging`）。
- `initialize` 响应字段：`protocolVersion` / `agentInfo` / `agentCapabilities` / `authMethods`（`acp/schema.py:2308` `class InitializeResponse` 的 alias）。
- `session/new` 响应：`sessionId`（+ 可选 `models` / `modes` / `configOptions`）（`schema.py:2848`）。
- `session/prompt` 响应：`stopReason` ∈ `{"end_turn","max_tokens","max_turn_requests","refusal","cancelled"}`（`schema.py:14` `StopReason`）。
- `session/request_permission` 请求：`sessionId` + `toolCall`（`ToolCallUpdate`，含 `toolCallId`/`rawInput`/`rawOutput`）+ `options[]`，选项字段 `optionId`/`kind`/`name`（`schema.py:2901`、`:1673`、`:2991`）。
- 权限决议：`{"outcome":{"outcome":"selected","optionId":…}}` 或 `{"outcome":{"outcome":"cancelled"}}`（`schema.py:1825` `class AllowedOutcome: outcome: Literal["selected"]`）。
- `session/update` 通知：`{sessionId, update:{sessionUpdate:"agent_message_chunk"|"agent_thought_chunk"|"user_message_chunk"|"tool_call"|"tool_call_update"|"plan"|…, content:{type:"text",text:…}}}`（`schema.py:2565/2569/2573/3122/2975/2439`）。
- 她的 optionId↔审批档位映射（`acp_adapter/permissions.py:21-27`）：`allow_once→once`、`allow_session→session`、`allow_always→always`、`deny→deny`、`deny_always→deny`。**这五档正好落在 z-bot `ApprovalService.Resolution{ONCE,SESSION,ALWAYS,DENY}`（`tool/ApprovalService.java:53`）上** ⇒ §1.2 的 bridge 走这条映射，不另起审批。

### §0.5 z-bot 侧既有接缝（复算过的行号）

- `agent/BotAgent.java:251` `public String chat(String userMessage, StreamListener listener)`；`:273` 把 `ToolConfirmationNeeded` 翻译成 `WAIT_CONFIRM:tool|args|reason`（前缀常量 `:81`）。⇒ ACP 的 `session/prompt` 就吃这个返回值分流。
- `agent/BotAgent.java:581` `pendingApprovals()` → `ApprovalService.Request` 只读快照；`:709` `confirmTool(String toolName, String argsJson, ApprovalService.Resolution)`。⇒ 外送审批只走这两个口，不新建审批状态。
- `agent/BotAgent.java:928` `stop()` → `context.interrupt().request(...)`。⇒ `session/cancel` 走它。
- `agent/StreamEvent.java` 九种 `Kind`（`STEP_START/THOUGHT_DELTA/TOOL_CALL_REQUEST/TOOL_RESULT/FINAL_DELTA/DONE/STEER/COMPACTED/ERROR`）⇒ 流式回推的输入面。
- `tool/ApprovalService.java` 每会话 FIFO：`submit:261`、`pending:281/292`、`resolve:365`、`Resolution.parse:62`。
- `session/SessionManager.java`：`createSession:84`、`switchSession:106`、`listSessions:112`、`loadMessages:179` —— z-bot 会话 id 的真身。
- 依赖面：Java 8、`jackson-databind 2.13.5`（`z-bot-core/pom.xml`，root `pom.xml:72`）、`picocli 4.7.6`、`junit`（test）。

### §0.6 口径

- `wc -l` 一律对盘上真文件；`grep -c` 类计数原样贴命令。
- 工单的计数只当"该去哪看"的指路，不当分母。

---

# §0b p25b（收口棒）第 0 步实测

工单：`~/.cache/zbot-p25-lead/dispatch_p25b.md`　开工时刻的读数一律现取。

## §0b.1 开工现场（原样贴回，与工单 §0 那组命令同形）

```
$ git log --oneline -2
47b702c docs(p25): EVIDENCE 骨架 + §0 第 0 步实测复算（…）
53222e1 docs(roadmap): §8 内核那条"仍未 push / 对外停在 0.2.0"按 16:43 实测作废——…
$ git rev-parse --short HEAD
47b702c
$ git status --porcelain
 M z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java
?? z-bot-core/src/main/java/com/zifang/z/bot/acp/
?? z-bot-core/src/main/java/com/zifang/z/bot/cli/AcpCommand.java
?? z-bot-core/src/test/java/com/zifang/z/bot/acp/
$ git grep -c '@Test' HEAD -- z-bot-core/src/test | awk -F: '{s+=$NF} END{print "committed_at="s}'
committed_at=735
$ find …/bot/acp …/src/test/java/…/bot/acp -type f | wc -l
      17
$ find …/bot/acp -name '*.java' | xargs wc -l | tail -1
    2595 total
$ ls _doc/005_testing/acceptance/
p11 p11b p11c p12 p15 p15b p16 p17 p18 p20b p21 p25
```

工单 §0"已知"逐格复核：HEAD `47b702c` ✅、父 `53222e1` ✅、`M ZBot.java` + 三个未跟踪路径 ✅ 全对上，
本棒没有需要改写工单 §0 的地方（工单自己那条 `@Test 1060/97 文件` 说的是 **main**，见 §0b.2）。

## §0b.2 第一动作=保存现场，以及基线并树

前棒那 4406 行（1 改 + 3 未跟踪路径，实测 13 生产/测试 java 文件 + `AcpCommand` + 5 个测试文件）
在动任何新代码之前用**显式路径**提交成 `9158f0a`；提交前 `git diff --cached --stat` 回读 =
正好 19 条路径、无夹带；提交后 `git status --porcelain` 空。之后全程没用过
`checkout/restore/clean/stash/reset`（工单 §2 红线），还原一律走本棒自己 cp 的副本 + md5 对账。

> 记一条自伤：我第一条 `git add` 里手打了一个盘上不存在的
> `z-bot-core/src/test/java/com/zifang/z/bot/acp/AcpAgentServerTest.java` ⇒ git 直接
> `fatal: pathspec … did not match any files`、**整条 add 一条都没暂存**（`git diff --cached --stat` 空）。
> 共享索引环境下这属"运气好"，不是我做对了：改成先 `find` 出真实清单再照抄。

并 main：`git merge main`（`main = f237a25`）零冲突通过，合并提交 `c8ca6f9`。

```
$ git rev-parse --short main; git grep -c '@Test' main -- z-bot-core/src/test | awk …; git grep -c '@Test' main -- z-bot-core/src/test | wc -l
f237a25
main_at=1060
main_test_files=97        # 工单说的 1060/97 ✅ 与我的实测一致（这条不用改写工单）
$ git rev-list --count HEAD..main   → 41        # 前棒分叉之后 main 又走了 41 笔
$ grep -rn '@Test' --include='*.java' z-bot-core/src/test | wc -l     → 1100   # 合并后工作树口径
$ find z-bot-core/src/test -name '*Test.java' | wc -l                → 100
```

合并树比 main 多的 40 个 `@Test` 来自 p25a 那 4 个含 `@Test` 的 ACP 测试文件（`AcpFakes.java` 是料、
不带 `@Test`）。`1100` 是**刚合完 main 那一刻**的工作树
读数；本棒随后自己又加了 `AcpProductionRegistrationTest`（3 条），收口时 HEAD 口径复测 =
`1103` 条 / `102` 个含 `@Test` 的文件（同一次命令：`git grep -c '@Test' HEAD -- z-bot-core/src/test`
求和、`git grep -l … | wc -l`）。**别和 §0.3 那条 p25a 的 735 混**（那是分叉点 `47b702c` 的 HEAD 口径）。

## §0b.3 对 §0（p25a 稿）的两处点名修正

1. §0 结尾那条"本期取 **11 条真处理路径** + `session/fork`、`session/close` 明确大声未实现"**不成立**：
   `session/close` 有真实现 —— 注册在 `acp/AcpAgentServer.java:162`，实现体 `:383 closeSession()`
   走 `registry.close(sessionId)` 并回 `{closed:true, sessionId, zbotSessionStillRestorable:true}`。
   实测口径是 **12 条真处理 + 1 条（`session/fork`）大声未实现**（`:169` 注册 → `:172` 抛
   `notImplemented`）。p25a 那句写在其代码落地之前，属过期计划而非谎报。
2. §0.3 那条 `<TODO>`（`grep -rl "@Test" … | wc -l`）本棒补测：合并树
   `grep -rl '@Test' --include='*.java' z-bot-core/src/test | wc -l` = **97**（main 口径），
   含 ACP 5 个文件后 = **102**。

## §0b.4 杠④ T0（开工时点，三格原样）

```
$ ls -A ~/.zbot | wc -l            → 8
$ md5 -q ~/.zbot/config.properties | cut -c1-8   → 2dadaed0
$ md5 -q ~/.zbot/state.db | cut -c1-8            → 690ddbc0
$ awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties  → 125
```

与工单"已知"三格一致。**key 的值一次都没被读过/打印过**（只量长度）；键名清单来自
`grep -o '^[a-zA-Z0-9._-]*=' ~/.zbot/config.properties` = `minimax.api.key / minimax.base.url / minimax.model` 三行。

---

# §1 工单 §3.2 那三问（本棒真正的产出）

## 问 1：`AcpCommand` 到底有没有进 `ZBot.java` 的生产命令注册表？

**注册这件事是真的**，出处不是"我记得"：

```
$ grep -n "AcpCommand\|subcommands" z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java
40:        subcommands = {ChatCommand.class, ReplCommand.class, ServeCommand.class, GatewayCommand.class,
42:                McpCommand.class, com.zifang.z.bot.cli.AcpCommand.class},
```

但"那行在"不等于"测到了"：**p25a 那 4 个 ACP 测试文件里没有任何一条读生产命令表** ——
`AcpProtocolSurfaceTest` / `AcpApprovalBridgeTest` / `AcpRealAgentChainTest` / `AcpStreamPublisherTest`
全部经 `AcpFakes.server(conn, …)` 自造 `AcpConnection`（`grep -rn "ZBot.class\|getSubcommands" z-bot-core/src/test/java/com/zifang/z/bot/acp/`
在新增那个文件之前是 **0 命中**）。也就是说，把 `ZBot.java:42` 那一行摘掉，整套 ACP 仍然全绿 ——
那叫测了个不存在的东西。

新增 `z-bot-core/src/test/java/com/zifang/z/bot/acp/AcpProductionRegistrationTest`（3 条）就是补这一层，
三条都只读**生产**面：注解 `subcommands` → 名字表；`z-bot --help` 的用法文本；真 `CommandLine` 规格的
`userObject instanceof AcpCommand` + 四个选项面（`--check/--allow-always/--model-catalog/--config-dir`）。
同一条测试里带了两把对照：`acpx` 必须查不到（防"什么都能查到"），子命令数 ≥10（防"表里只剩一格"）。

**"摘掉那一行会不会红"是实测的，不是推断的**（杠② run `p25b_mut2`，台账 `_doc/005_testing/acceptance/p25/LEDGER.tsv`）：

- **M1 `acp-registration-removed` ⇒ `KILLED`**。注入 = `ZBot.java:42` 的
  `McpCommand.class, com.zifang.z.bot.cli.AcpCommand.class}` 改回 `McpCommand.class}`；
  字节码对账 `class_md5 cae235b8→44816494`、`needle absent AcpCommand=1`（`ZBot*.class` 整族 javap+strings 里
  `AcpCommand` 由有到无）；`ran=3 broke=3` 三断言全红，具名捕获者
  `com.zifang.z.bot.acp.AcpProductionRegistrationTest`，逐字首行：
  `java.lang.AssertionError: 生产注册表里要有 acp，实测=[chat, repl, serve, gateway, status, sessions, send, pair, mcp]`
  —— 实测表里就是少了 acp 那一格，这串是红的输出告诉我的，不是我的推测。
- **M2 `acp-command-renamed` ⇒ `KILLED`**。注入 = `@Command(name = "acp"` → `"acpx"`；
  `class_md5 177cfd94→15a3fb67`、`needle present acpx=1`；`ran=3 broke=2`，
  `java.lang.AssertionError: getSubcommands() 里没有 acp: [chat, repl, interactive, serve, gateway, status,
  sessions, send, pair, mcp, acpx]`。这一针同时反过来证明"名字这一格"被测到了（M1 只证明"存在一格"）。

这两针都只红在**生产注册表**这一层。至于"p25a 那 4 个自造 `AcpConnection` 的测试为什么结构上捕不到 M1"，
凭的是 §问 1 开头那条实测（新增那个文件之前 `grep -rn "ZBot.class\|getSubcommands" src/test/…/acp/` = 0 命中：
没有任何一条测试读生产命令表），**不是**"在 M1 下复跑那 4 类仍全绿"——后者本棒没量过（量具每支变异只跑
`-Dtest=<catcher>` 一个类，见 `p25_mutation.py:252`），不许当成已有证据。


## 问 2：ACP 的 method 表 / 状态机，实现里真实现了哪些？

权威全集 = 她 SDK 的 `acp/meta.py`（§0.2 已逐行贴过）：agent 面 13 条、client 面 9 条。
z-bot 侧**逐条对到盘上**（行号 `grep -n` 现取，`AcpAgentServer.java`）：

| # | agent 面方法 | 注册行 | 实现体 | 真做了什么（口径来自代码，不是承诺） |
|---|---|---|---|---|
| 1 | `initialize` | `:101` | `:205` | 版本不合直接 `INVALID_PARAMS`（只认 `PROTOCOL_VERSION=1`），否则回 `protocolVersion/agentInfo/agentCapabilities/authMethods` |
| 2 | `authenticate` | `:107` | `:256` | 只接受"本 profile 真配了 key 的 provider code"+ `z-bot-config`，表外 methodId 拒 |
| 3 | `session/new` | `:113` | `:272` | 经 `AcpSessionRegistry` 真建 `BotAgentAcpTarget`，回 `acp-` 前缀句柄 + `_meta.zbotSessionId` |
| 4 | `session/load` | `:119` | `:294`(`resume=false`) | 旧 ACP 句柄跨进程 ⇒ `-32602` 并点名恢复办法；给 z-bot 会话 id ⇒ 显式恢复并回新句柄 |
| 5 | `session/resume` | `:125` | `:294`(`resume=true`) | 同上分支 |
| 6 | `session/list` | `:131` | `:350` | 列表来自 `registry`（真会话），非空壳 |
| 7 | `session/prompt` | `:137` | `:471 startPrompt` | 工作线程跑真 `BotAgent`，`ACP_ASYNC` 挂起、由 `AcpStreamPublisher` 逐事件回 `session/update`，末尾回 `id` 响应 |
| 8 | `session/set_mode` | `:144` | `:394` | 白名单外 `INVALID_PARAMS`；命中则 `session.setModeId(...)` 并回 `availableModes` |
| 9 | `session/set_model` | `:150` | `:412` | 白名单外拒；命中走 `registry.reconfigure(...)` 重建 target，**并回读 `target.model()` 自证真换了**，不换则 `-32603` |
| 10 | `session/set_config_option` | `:156` | `:437` | 本期只承接 `reasoning` 这一条真有效果的开关，其余 config option 大声拒（不"收下不办事"） |
| 11 | `session/close` | `:162` | `:383` | `registry.close(sessionId)` ⇒ 关连接句柄，回 `closed:true` + `zbotSessionStillRestorable:true` |
| 12 | `session/fork` | `:169` | — | **已知未实现 ⇒ `:172` 抛 `notImplemented`（-32601 带理由）**，不装作成功 |
| 13 | `session/cancel` | `:178`（**通知**，`onNotification`） | `:452` | 走 `stop()`/interrupt 面；回包 `stopReason=cancelled` |

⇒ **13/13 都有分派**（不会静默吞），其中 **12 条真处理 + 1 条大声未实现**。
表外方法另有 `AcpConnection.java:189` 兜底：`-32601` 且把已知方法全集塞进 `data.knownMethods`（E2E 实测见 §问 3 第 4 步）。

client 面（z-bot→客户端）9 条**只真用 2 条**，点名不糊：

```
$ grep -rn "SESSION_UPDATE\|SESSION_REQUEST_PERMISSION" acp/ --include='*.java' | grep -v AcpMethods.java
acp/AcpStreamPublisher.java:274:        conn.notify(AcpMethods.SESSION_UPDATE, params);
acp/AcpApprovalBridge.java:194:        JsonNode result = conn.requestClient(AcpMethods.SESSION_REQUEST_PERMISSION, params,
```

`fs/read_text_file`、`fs/write_text_file`、`terminal/create|output|release|wait_for_exit|kill` **共 7 条
零调用点**（除 `AcpMethods` 里的声明与 `CLIENT_METHODS` 集合成员本身）⇒ 进 §未做，不许说"对齐了 client 面"。

状态机/取值面的"声明了但从不产出"（`grep -c` 全 `acp/` 包，refs=1 即只有它自己那行声明）：

| 常量 | refs_in_acp_pkg | 判定 |
|---|---|---|
| `STOP_END_TURN` / `STOP_CANCELLED` | 3 / 3 | 真产出（`AcpAgentServer.java:513/539/540/542`） |
| `STOP_MAX_TOKENS` / `STOP_REFUSAL` | 1 / 1 | **只会是纸面档位** ⇒ §未做 |
| `UPDATE_AGENT_MESSAGE_CHUNK` / `UPDATE_AGENT_THOUGHT_CHUNK` / `UPDATE_TOOL_CALL` / `UPDATE_TOOL_CALL_UPDATE` | 3 / 4 / 3 / 3 | 真产出 |
| `UPDATE_USER_MESSAGE_CHUNK` / `UPDATE_PLAN` / `UPDATE_AVAILABLE_COMMANDS` | 1 / 1 / 1 | 从不产出 ⇒ §未做 |
| `OPTION_DENY_ALWAYS` | 1 | 从不外送（实测选项集四档 `allow_once/allow_session/allow_always/deny`）⇒ §未做 |
| `OPTION_ALLOW_ALWAYS` | 3 | 仅在 `--allow-always` 那条路上才外送（会落盘 `config.properties`） |

## 问 3：真进程往返（stdio）到底跑没跑起来？

跑起来了。量具 = `_doc/005_testing/acceptance/p25/p25_e2e.py`（本棒写，一次调用=一整跑）：
真 `java -cp <mvn 依赖 classpath> com.zifang.z.bot.ZBot acp --config-dir <临时 profile>` 子进程、
stdin 发帧 / stdout 读帧、假 LLM 是**自写 socket**（`bind(0)` + `Connection: close`、只绑 127.0.0.1，
没用 `com.sun.net.httpserver` 也没用 python 的 http.server）、`stub-key-not-real`、
临时 profile 落在 `~/.cache/zbot-p25-lead/e2e/<tag>/cfg`（**不写 /tmp**）。

**杠③ 正式三跑（`runa/runb/runc`，各 = 一次整跑：mvn compile → 真 java 子进程 stdio 往返 → 第二进程
`acp --check`）逐字读数**，logs 在 `~/.cache/zbot-p25-lead/bar3_{a,b,c}.out`：

```
E2E|run=runa out=/Users/zifang/.cache/zbot-p25-lead/e2e/runa
BAR4|tag=t0_before_build dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
BUILD|compile_rc=0 acp_class=/private/tmp/zbot-wt-p25/z-bot-core/target/classes/com/zifang/z/bot/acp/AcpAgentServer.class exists=True
BUILD|classpath_file=/Users/zifang/.cache/zbot-p25-lead/e2e/runa/cp.txt size=3044 rc=0
BAR4|tag=t1_after_build dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
FAKELLM|port=58616
PROC|acp_pid=50218
CHECK|initialize_answers_id1                       PASS frames=1
CHECK|initialize_protocolVersion_is_1              PASS
CHECK|initialize_names_the_agent                   PASS agentInfo.name=z-bot
CHECK|session_new_answers_id2_with_sessionId       PASS sessionId=acp-5d259ab86b91
CHECK|session_list_sees_the_live_session           PASS payload={"jsonrpc": "2.0", "id": 3, "result": {"sessions": [{"sessionId": "acp-5d259ab86b91", …
CHECK|unknown_method_is_minus_32601                PASS {'code': -32601, 'message': 'method not found: session/teleport', 'data': {'unknownMethod': …
CHECK|unknown_method_carries_known_method_table    PASS data={"unknownMethod": "session/teleport", "knownMethods": ["authenticate", "initialize", "session/cancel", …
CHECK|prompt_answers_id5                           PASS updates_seen=1
CHECK|prompt_stopReason_is_end_turn                PASS stopReason=end_turn err={}
CHECK|prompt_streamed_agent_message_chunk          PASS chunks=1 text=ACP-E2E-OK
CHECK|fake_llm_was_actually_called                 PASS hits=[{'bytes': 8455, 'stream': False}]
CHECK|every_stdout_line_is_a_complete_jsonrpc_frame PASS frames=6 bad=0 first_bad=[]
CHECK|acp_process_exits_on_stdin_eof               PASS exit_code=0
CHECK|acp_process_reaped_per_ps                    PASS ps_says=''
CHECK|stdout_held_only_protocol_logs_went_stderr   PASS stderr_has_banner=True stdout_clean=True
CHECKMODE|rc=0 tail=[acp --check] 装配自检通过：initialize/newSession/list 有回帧，未知方法已按 -32601 大声失败
CHECK|acp_check_selfcheck_exit_zero                PASS rc=0
CHECK|acp_check_reports_no_missing_frame           PASS seen_missing=False
CHECK|acp_check_verifies_the_32601_step            PASS tail=[acp --check] 装配自检通过：…
CHECK|temp_profile_used_for_state_db               PASS files_in_cfg=['config.properties', 'cron', 'memories', 'sessions', 'state.db', 'workspace']
BAR4|tag=t2_after_e2e dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
E2E|run=runa checks=19 pass=19 fail=0 llm_hits=1 result=OK
```

三跑汇总（每跑的 `CHECK|…` 行数与 PASS 行数现数，不手数）：

```
$ for r in a b c; do printf 'run%s CHECK=%s PASS=%s %s\n' $r \
    "$(grep -c '^CHECK|' ~/.cache/zbot-p25-lead/bar3_$r.out)" \
    "$(grep -c ' PASS' ~/.cache/zbot-p25-lead/bar3_$r.out)" \
    "$(grep '^E2E|run=' ~/.cache/zbot-p25-lead/bar3_$r.out | tail -1)"; done
runa CHECK=19 PASS=19 E2E|run=runa checks=19 pass=19 fail=0 llm_hits=1 result=OK
runb CHECK=19 PASS=19 E2E|run=runb checks=19 pass=19 fail=0 llm_hits=1 result=OK
runc CHECK=19 PASS=19 E2E|run=runc checks=19 pass=19 fail=0 llm_hits=1 result=OK
```

三跑之间只有 sessionId / port / pid / 时间戳 / 假 LLM 请求字节数（8455 三跑同值）会变，19 条判据名与
PASS 位次逐行相同。早先那次冒烟 `run=smoke1`（19:09）本稿曾"逐字"贴过，实际只贴了 19 条里的 13 条、
却写了"19/19"——那是本棒的记账错，不是量具错，已按 §量具自身的错 记账，并以 `runa/runb/runc` 为准。
回收这件事不是"看日志尾巴"：`proc.stdin.close()` ⇒ `wait()` 拿到真退出码 ⇒ 再 `ps -p <pid>` 复扫为空。
`smoke1` 那跑的 `every_stdout_line_is_a_complete_jsonrpc_frame` 判据当时是空转的（`all("\n" not in …)`
对已按行切好的 list 恒真），已换成"每行必须解析得回带 `jsonrpc:2.x` 的完整帧 + 帧数下限"。

---

# §2 四杠实测（p25b 收口）

## §2.1 杠①：全 reactor `mvn -o test` ×3

命令（每跑前 `rm -rf z-bot-core/target/surefire-reports`；**全 reactor，无 `-pl`**，离线 `-o`；
日志落 `~/.cache/zbot-p25-lead/`，不写 `/tmp`）：

```
for r in a b c; do rm -rf z-bot-core/target/surefire-reports; mvn -o test > ~/.cache/zbot-p25-lead/bar1_$r.log 2>&1; done
```

三跑逐字读数（判绿尺 = 唯一口径 `~/.cache/zbot-integrate/b1parse.py`）：

```
BAR1_a rc=0
BAR1PARSE /Users/zifang/.cache/zbot-p25-lead/bar1_a.log module_lines=1 class_lines=101 module_sum=1101 class_sum=1101 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1_b rc=0
BAR1PARSE /Users/zifang/.cache/zbot-p25-lead/bar1_b.log module_lines=1 class_lines=101 module_sum=1101 class_sum=1101 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1_c rc=0
BAR1PARSE /Users/zifang/.cache/zbot-p25-lead/bar1_c.log module_lines=1 class_lines=101 module_sum=1101 class_sum=1101 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
```

三跑**确实**是全 reactor（`bar1_c.log:10/18/855` 现取）：

```
[INFO] Building z-bot (Local Agent App, cc/Hermes style) 0.2.0            [1/3]
[INFO] Building z-bot-core 0.2.0                                          [2/3]
[INFO] Building z-bot-desktop-packager 0.2.0                              [3/3]
[INFO] z-bot (Local Agent App, cc/Hermes style) ........... SUCCESS [  0.292 s]
[INFO] z-bot-core ......................................... SUCCESS [01:01 min]
[INFO] z-bot-desktop-packager ............................. SUCCESS [  0.031 s]
```

`1101` 这个数与 §0b.2/§0b.3 里我贴的 `1103 / 102 文件` **不矛盾，是两把不同的尺**，本棒当场对账：
`grep -rc '@Test'` 的字面命中 1103 里有 **2 处注释里的散文**（`llm/P26RetryPolicyTest.java:37`、
`memory/MemoryE2eDriver.java:16` 的 `{@code @Test}`），真注解 = **1101 = 实跑数**；102 个含 `@Test` 的
文件里有 1 个不叫 `*Test.java`（`MemoryE2eDriver.java`，surefire 不收，注释里自己就写着"不是测试"）
⇒ `class_lines=101`。两条尺各自闭合，没有"消失的用例"。

## §2.2 杠②：具名变异台账

量具 `_doc/005_testing/acceptance/p25/p25_mutation.py`，台账 `_doc/005_testing/acceptance/p25/LEDGER.tsv`（机生成，人不手写一格）。
有效跑 = run **`p25b_mut2`**（8 行 = 1 CONTROL + 7 变异）。开工那三行自证：

```
RUN|p25b_mut2 repo=/private/tmp/zbot-wt-p25 ledger=/private/tmp/zbot-wt-p25/_doc/005_testing/acceptance/p25/LEDGER.tsv
LOCK|path=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock acquired=1
REFERENCE|dir=/Users/zifang/.cache/zbot-p25-lead/mutref/p25b_mut2 files=5 manifest_md5=77484136
```

（`LOCK|path` 那个值 = `git rev-parse --path-format=absolute --git-common-dir` 在本 worktree 的实测落点
（共享 `.git`，不在 worktree 里）；`LOCK_EX|LOCK_NB`，`acquired=1`，没有睡等、没有杀别的持有者。）

**"注入是不是真进了字节码"** 每行都带机读凭据：`class_md5` = 该支 needle 声明的那**一族** class 文件
（`ZBot*.class` / `cli/AcpCommand*.class` / `acp/AcpAgentServer*.class` / `acp/AcpApprovalBridge*.class` /
`acp/AcpStreamPublisher*.class`）合并 md5 的"注入前→注入后"，`needle present|absent <token>=1` =
`javap -v` + `strings` 在那个 token 上的实测结果：

| 变异 | 源码级注入 | 字节码凭据（proof 列逐字段） | 具名捕获者 | ran/broke | 记号 |
|---|---|---|---|---|---|
| CONTROL | 零注入（阳性对照） | 5 个 mvn 类 + 1 条 e2e 判词全绿 | — | ran=10/3/12/7/11，broke=0 | `RED-OK` |
| M1 `acp-registration-removed` | `ZBot.java:42` 摘掉 `, com.zifang.z.bot.cli.AcpCommand.class}` | `cae235b8→44816494`, `needle absent AcpCommand=1` | `AcpProductionRegistrationTest` | 3/3 | `KILLED` |
| M2 `acp-command-renamed` | `@Command(name="acp")` → `"acpx"` | `177cfd94→15a3fb67`, `needle present acpx=1` | `AcpProductionRegistrationTest` | 3/2 | `KILLED` |
| M3 `session-load-unregistered` | 摘掉 `session/load` 那一格注册 | `b1907072→5c3931f8`, `needle absent session/load=1` | `AcpRealAgentChainTest` | 7/1 | `KILLED` |
| M4 `permission-rawinput-shell` | 审批帧 `rawInput` 退回本棒修前的记账壳 | `568ad678→a13bf844`, `needle absent approvalArgsJson=1` | `AcpApprovalBridgeTest` | 10/1 | `KILLED` |
| M5 `selfcheck-clear-before-check` | `--check` 里把"先 clear 再 isEmpty"复原 | `177cfd94→2e4ded28`, `needle present acp --check=1` | **真进程 E2E** 的 `acp_check_selfcheck_exit_zero` | 1/1 | `KILLED` |
| M6 `fork-pretends-success` | `session/fork` 的 `notImplemented` 改成回空 `{}` | `b1907072→758ce1bb`, `needle absent notImplemented=1` | `AcpProtocolSurfaceTest` | 12/1 | `KILLED` |
| M7 `session-update-typo` | client 面通知名 `session/update` → `session/updates` | `d10db5db→7908ff20`, `needle present session/updates=1` | `AcpStreamPublisherTest` | 11/1 | `KILLED` |

判红只认具名那一行，M1 的逐字红样（`…/mutlogs/p25b_mut2/M1-acp-registration-removed.test.log`，
`.log` 不进 git 故原文贴这儿）：

```
[ERROR] Tests run: 3, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.205 s <<< FAILURE! -- in com.zifang.z.bot.acp.AcpProductionRegistrationTest
[ERROR] com.zifang.z.bot.acp.AcpProductionRegistrationTest.acpIsRegisteredInProductionZBotSubcommands -- Time elapsed: 0.022 s <<< FAILURE!
java.lang.AssertionError: 生产注册表里要有 acp，实测=[chat, repl, serve, gateway, status, sessions, send, pair, mcp]
[ERROR] com.zifang.z.bot.acp.AcpProductionRegistrationTest.productionCommandLineResolvesAcpWithItsRealOptionSurface -- Time elapsed: 0.083 s <<< FAILURE!
[ERROR] com.zifang.z.bot.acp.AcpProductionRegistrationTest.acpShowsUpInProductionUsageMessage -- Time elapsed: 0.044 s <<< FAILURE!
```

M2 的断言串（这一条同时把 `acpx` 那格打印出来，是"名字这格被测到"的直接凭据）：

```
java.lang.AssertionError: getSubcommands() 里没有 acp: [chat, repl, interactive, serve, gateway,
status, sessions, send, pair, mcp, acpx]
```

CONTROL 逐字（M5 那支的注入点在零注入下必须是绿的，否则整张台账无意义）：

```
CONTROL|com.zifang.z.bot.acp.AcpApprovalBridgeTest ran=10 broke=0 rc=0
CONTROL|com.zifang.z.bot.acp.AcpProductionRegistrationTest ran=3 broke=0 rc=0
CONTROL|com.zifang.z.bot.acp.AcpProtocolSurfaceTest ran=12 broke=0 rc=0
CONTROL|com.zifang.z.bot.acp.AcpRealAgentChainTest ran=7 broke=0 rc=0
CONTROL|com.zifang.z.bot.acp.AcpStreamPublisherTest ran=11 broke=0 rc=0
CONTROL|e2e:acp_check_selfcheck_exit_zero green=1
```

还原对账（**只准用本次运行前 cp 的副本 + md5**，没有 `checkout/clean/restore/stash/reset`）：

```
RESTORE|md5_all_match=1 git_dirty_for_mutated_files=no
DONE|rows=8 restore_verified=True
MUT_RC=0
```

台账自身合规性（现量，不是"我记得我合规"）：

```
$ awk -F'\t' 'NR>1{print $5}' LEDGER.tsv | sort | uniq -c
   7 KILLED
   1 RED-OK
$ awk -F'\t' 'NR>1 && $5 !~ /^(KILLED|RED-OK|SURVIVED|PARTIAL|INJECTION_NOT_APPLIED)$/{print "BAD:"$0}' LEDGER.tsv | wc -l   → 0
$ stat -f '%N %m' p25_mutation.py LEDGER.tsv
p25_mutation.py 1790421497
LEDGER.tsv      1790421645      # 台账晚于量具 148 s ⇒ 是量具跑的，不是人抄的
```

## §2.3 杠③：真进程 E2E ≥3 整跑

三跑 `runa/runb/runc` 全部 `checks=19 pass=19 fail=0 llm_hits=1 result=OK`，逐字读数在 **§问 3**
（同一条命令：`for r in a b c; … grep -c '^CHECK|' …`，`CHECK=19 PASS=19` 三行）。
没有一支是 `NO-RUN`：每跑都真起 `java … com.zifang.z.bot.ZBot acp`（pid 50218 / 50497 / 50953）、
真打自写假 LLM（`fake_llm_was_actually_called` 三跑 `bytes=8455`）、真收进程（`exit_code=0` + `ps` 复扫为空）。

## §2.4 杠④：`~/.zbot` 不变量（三时点，一格没动）

- **开工时点**：§0b.4 已记（`8 / 2dadaed0 / 690ddbc0`，key 只量长度 =125）。
- **E2E 在飞**：三跑各自内置 `t0_before_build / t1_after_build / t2_after_e2e` 共 9 行，逐字（三跑完全同值）：

```
BAR4|tag=t0_before_build dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
BAR4|tag=t1_after_build  dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
BAR4|tag=t2_after_e2e    dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
```

- **收尾时点（杠①②③ 全部跑完之后现量）**：

```
$ ls -A ~/.zbot | wc -l                             → dir_count=8
$ md5 -q ~/.zbot/config.properties | cut -c1-8       → cfg_md5_8=2dadaed0
$ md5 -q ~/.zbot/state.db | cut -c1-8                → db_md5_8=690ddbc0
$ awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties → key_len_only=125
```

三时点（开工 / E2E 在飞 9 行 / 收尾）值完全相同，且与工单"已知"三格一致 ⇒ 本棒没有碰过 `~/.zbot`。
**key 的值全程未被读取/打印/复制**，只出现长度。

杠② 那 7 支变异与杠① 那 3 跑都只碰 worktree 里的 `src/`，没有任何一条命令往 `~/.zbot` 写；
E2E 全程 `--config-dir <临时 profile>`（`temp_profile_used_for_state_db` 判据就是钉这件事：
真跑落在临时 profile 的 `cfg` 里，`files_in_cfg=['config.properties', 'cron', 'memories', 'sessions', 'state.db', 'workspace']`）。

## §2.5 量具自身的错（单独记账，不许伪装成产品缺陷）

1. **判红正则不容中段**（`p25_mutation.py` 第一版）：maven 的具名红行长这样
   `…AcpProductionRegistrationTest.acpIsRegistered… -- Time elapsed: 0.022 s <<< FAILURE!`，
   旧正则把 `<<< FAILURE!` 紧贴方法名 ⇒ 认不出名字。后果实测在 run `p25b_mut1`：
   `M1 … ran=3 broke=3 named=False` 被误记 `SURVIVED` —— **真红了，量具没认出**，这是量具错。
   修法：`re.escape(simple) + r"\.\w+[^\n]*? <<< (FAILURE|ERROR)!"`；同一注入在 `mut2` 复跑得 `named=True → KILLED`。
   `LEDGER.tsv` 是机生成、被 `mut2` 重写，`mut1` 那 8 行只留在 `~/.cache/zbot-p25-lead/mutation_run1.out`（`.log/.out` 不进 git，故凭据字段已抄进上表）。
2. **`mut1` 的 CONTROL 记 `SURVIVED` 不是量具错，是被测代码里真有的竞态**：`AcpRealAgentChainTest ran=7 broke=1 rc=1`
   在**零注入**下真红（`expected:<2> but was:<0>` —— 三条"等 A 断言 B"的计数等待）。已改 `:192/:229/:385`
   为等因果（`awaitFrame("\"id\":2,\"result\"…)"` / `awaitFrame(SESSION_REQUEST_PERMISSION…)`），
   复测同形态 ×3 全绿（`~/.cache/zbot-p25-lead/flake_{1,2,3}.log`，43 例）。它污染的是台账可信度，
   所以放在这一节点名，而不是塞进 §杠②。
3. **判绿尺的空参照集陷阱**（工单点名的 `^\[INFO\]` 锚）：本棒实测现行尺 `b1parse.py` 用 `\[\w+\]`，
   红跑（摘要行前缀被 maven 翻成 `[ERROR]`）不丢锚，空输入必 FATAL：

```
$ python3 ~/.cache/zbot-integrate/b1parse.py mutlogs/p25b_mut2/M1-acp-registration-removed.test.log
BAR1PARSE … module_lines=1 class_lines=1 module_sum=3 class_sum=3 F=3 E=0 S=0 build=FAILURE socket_hits=0 agree=YES
BAR1PARSE … NOT_GREEN build=FAILURE F=3 E=0 socket=0        rc=0
$ : > ~/.cache/zbot-p25-lead/empty_probe.log
$ python3 ~/.cache/zbot-integrate/b1parse.py ~/.cache/zbot-p25-lead/empty_probe.log
BAR1PARSE … PARSE_FATAL module_lines=0 (no module summary: 空参照集，不许当满分)      rc=2
```

4. **`mvn -q` 吞 `COMPILATION ERROR` 头**：工单/前棒已知陷阱，**本棒未复现**（两支量具全程 `mvn -o test` 不带 `-q`，
   编译失败会以 `BUILD FAILURE` + `[ERROR] COMPILATION ERROR` 进日志并被尺子抓到）。列在此处只为不下沉成"我不知道"。
5. **E2 判据空转**：`every_stdout_line_is_a_complete_jsonrpc_frame` 首版是 `all("\n" not in line …)`，
   对已按 `\n` 切好的 list 恒真 ⇒ 满分假绿。换成"逐行 `json.loads` + 必须有 `jsonrpc:2.x` + 帧数下限"，
   修后三跑 `frames=6 bad=0 first_bad=[]`。这是量具错，改的是尺不是产品。
6. **记账错（不是量具错也不是产品缺陷）**：§问 3 首稿把 `smoke1` 的 19 条判据只贴了 13 条却写"19/19"，
   现已改成 `runa` 全量 + 三跑汇总。另 §0b.2 记过我自己 `git add` 手打不存在路径导致整条暂存失败的自伤。

## §2.6 未做 / 未覆盖（点名，不许说"已对齐协议"）

1. **client 面 9 条只真用 2 条**：`fs/read_text_file`、`fs/write_text_file`、`terminal/create|output|release|
   wait_for_exit|kill` **7 条零调用点**（§问 2 的 `grep -rn` 实测），IDE 想让 z-bot 读盘/开终端会直接拿不到能力。
2. **`stopReason` 只产出 2 档**：全仓 `grep -rn "STOP_" z-bot-core/src/main/java/com/zifang/z/bot/acp/`
   在 `AcpMethods` 之外只有 4 个落点 —— `AcpAgentServer.java:513`（审批中断那一路固定 `end_turn`）、
   `:539`（`reply.startsWith("已中止")` → `cancelled`，否则 `end_turn`）、`:540`、`:542`；
   ⇒ 实际会外送的值只有 `end_turn` 与 `cancelled`。`STOP_MAX_TOKENS`、`STOP_REFUSAL` 常量 refs=1，
   **从不产出**（而且 `AcpMethods.java:84-87` 一共只声明了这 4 档，协议侧若有更高档位连常量都没有）
   ⇒ 客户端按协议分档处理时永远收不到这两档。
3. **`session/update` 只发 4 种**：`agent_message_chunk / agent_thought_chunk / tool_call / tool_call_update`；
   `user_message_chunk`、`plan`、`available_commands` 从不产出 ⇒ IDE 看不到 z-bot 的真 plan 与可命令清单。
4. **审批选项四档外送**（`allow_once/allow_session/allow_always/deny`），`OPTION_DENY_ALWAYS` refs=1 从不外送。
5. **`session/fork` 大声未实现**（`AcpAgentServer.java:169→:172`）——本期刻意如此，M6 钉住"不许装作成功"。
6. **真进程 E2E 只测一条 prompt 路径**：`session/prompt` → 逐帧 → `end_turn`。审批链路（`session/request_permission`）
   只在 JUnit 里用自造 `AcpConnection` 测（`AcpApprovalBridgeTest` / `AcpRealAgentChainTest`），
   **没有**真子进程 stdio 往返；杠② M4 红的是 JVM 内测试，不是真进程。同理 `--allow-always` 落盘
   `config.properties` 那条路在 E2E 里没走（走了就会写临时 profile，杠④不受影响但本棒没量）。
7. **杠② 的覆盖面**：每支变异只跑它自己的具名捕获者（`p25_mutation.py:252` 的 `-Dtest=<catcher>`），
   没在注入态下跑全 reactor ⇒ 变异对**其他**测试类的连带影响未量（这一条在 M7 那种"改协议名拼写"上尤其可能低估）。
8. **多轮/并发**：同一 ACP 连接上第二次 `session/prompt`、两个 session 交叉、`session/cancel` 打断真模型调用
   （E2E 里 cancel 未测）都未覆盖。
9. **未提交**：收口时 `git status --porcelain` 应归零；杠④ 收尾读数与杠① 三跑读数在本节与 §2.1 落定后
   一起提交 `LEDGER.tsv` + `EVIDENCE.md`。
10. **一笔口径旧账未结**：§0.3 里 p25a 量的 `@Test` 条数 `735` 与本棒在 HEAD 量的 `1103` 差 368，
    本棒未复量她当时的树 ⇒ 差因未查（基线漂移 / glob / 计数口径）。杠① 只保证**本棒这棵树**的
    1101 例自洽（§2.1 两把尺已闭合），不给那个数背书。




## §2.7 收口状态与提交链

- 本文件所在的那个提交即 **p25b 收口提交**；写这份文档之前的 HEAD = `11a6c27`，收口提交之后
  `git status --porcelain` 为空（本棒产出的全部盘上凭据 = `LEDGER.tsv` + `EVIDENCE.md` 都在 git 里；
  `.log`/`.out` 被 gitignore ⇒ 决定性读数已原样贴进本文件）。
- 链条（`git log --oneline`）：`9158f0a` 保存现场（p25a 未提交的 19 路径 / 4406 行，显式路径 add）
  → `c8ca6f9` 并 main `f237a25`（无冲突，未 push）→ `55c7e8a` 三处生产/测试修复 + 两支量具
  → `11a6c27` 测试竞态拆除 + 变异量具判红正则修正 → 收口提交（文档 + 台账）。
- 四杠结论一句话版：**杠① 3/3 全绿（1101 例、F=E=S=0、socket=0）；杠② CONTROL `RED-OK` + 7/7 `KILLED`
  且还原 `md5_all_match=1`；杠③ 3/3 各 19/19（真 java 子进程 stdio 往返，无 NO-RUN）；杠④ 三时点
  `8 / 2dadaed0 / 690ddbc0` 未变。** 未做的面全部点名在 §2.6，尤其 §2.6.1（client 面 7/9 零调用点）
  与 §2.6.6（审批链路无真进程覆盖）是下一棒的活。
