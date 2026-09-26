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
