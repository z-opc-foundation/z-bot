# P19 EVIDENCE —— 命令单收成单一真源（writer p19b）

工作树：`/private/tmp/zbot-wt-p19`（branch `w14-p19`）。
本笔产出两枚 commit：`147389f`（WEB/ACP 消费端 + 一致性守卫）、`11e2d4f`（ACP 广告的 initialize 闸 + 闸测）。
工单：`~/.cache/zbot-p19-lead/dispatch_p19a.md`。所有时间戳都是写那一刻 `date` 的输出，不是回忆。

---

## 0. 接盘取证（工单 §0′，命令原样跑、输出原样贴）

跑的是工单点的那三条，没换写法：

```
$ cd /private/tmp/zbot-wt-p19 && git log --oneline -3
00abfb4 wip(P19): 封存前棒在途的命令表单源改动，防被并行会话的 checkout 抹掉
ba04ce3 Merge branch 'main' into w14-p19
cda3452 test(p28): 修掉杠③量具自己 8 处失真判据，让 lead_r1 的 10 条红各归其位

$ md5 -q z-bot-core/src/main/java/com/zifang/z/bot/slash/CommandCatalog.java
d705ea48a1fcd5034caaef9cc11a619b

$ git status --porcelain | wc -l
       0
```

`git show --stat 00abfb4` 的五件套（工单说"动了哪 5 个文件"）：

```
 .../java/com/zifang/z/bot/channel/HttpChannel.java |  31 +-
 .../com/zifang/z/bot/channel/TerminalChannel.java  |  28 +-
 .../com/zifang/z/bot/slash/CommandCatalog.java     | 531 +++++++++++++++++++++
 .../java/com/zifang/z/bot/slash/SlashRegistry.java | 400 +---------------
 .../com/zifang/z/bot/ui/RawTerminalReader.java     |  12 +-
 5 files changed, 602 insertions(+), 400 deletions(-)
```

**判定：盘就是我接的那份**（`00abfb4` 在顶、md5 前缀 `d705ea48`、工作树干净、接盘时 main = `cda3452`）。
`00abfb4` 里既没有 `web/index.html` 也没有 `bot/acp/**` —— 与工单说的"WEB/ACP 两个消费端一行没写"对得上。
合 main：`git merge main` → `ba04ce3`（merge，不是 rebase；main 之后又走了一笔 `9884059`，见 §8）。

---

## 1. 编译第一手读数（工单说"编译没验过"，先验它）

全 reactor、不带 `-pl`、`test-compile`（`~/.cache/zbot-p19-lead/compile_r1.log`）：

```
30:[INFO] Compiling 138 source files with javac [debug target 8] to target/classes
47:[INFO] Compiling 117 source files with javac [debug target 8] to target/test-classes
73:[INFO] BUILD SUCCESS
```

⇒ **前棒那 5 个文件本身是能编译的**，"没验过"不等于"坏了"。这一点必须记清楚，因为它决定了后面 14 条红的归因不能算在 `00abfb4` 头上。

我写完守卫之后我自己的编译错（`compile_r3.log`，原样）：

```
49:[ERROR] COMPILATION ERROR :
51:[ERROR] /private/tmp/zbot-wt-p19/z-bot-core/src/test/java/com/zifang/z/bot/slash/CommandSurfaceConsistencyTest.java:[356,55] ')' or ',' expected
52:[ERROR] /private/tmp/zbot-wt-p19/z-bot-core/src/test/java/com/zifang/z/bot/slash/CommandSurfaceConsistencyTest.java:[356,62] ';' expected
62:[INFO] BUILD FAILURE
```

原因：中文串里嵌了裸 `"`，把 Java 字面量截断了（换成 `「」`）。同类错还有两处，都是我写的，不再重复贴。

一条踩坑要记账：`mvn -o test -Dtest='A+B'`（加号分隔）不会跑测试，而是

```
[ERROR] Failed to execute goal ...maven-surefire-plugin ... No tests matching pattern "A+B" were executed! → BUILD FAILURE
```

按工单"空输入一律 FATAL、跳过不等于 passed"的口径，**这算失败**，不能当绿。改成逗号分隔后才有真正的 `Tests run:` 行。

---

## 2. 改了哪些文件（两枚 commit 的净面）

主代码（4 个文件，全在工单 §2 许可域内）：

| 文件 | 动了什么 |
| --- | --- |
| `z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpStreamPublisher.java` | 新增 `sendAvailableCommands()`（`:206`）与 `commandNode()`（`:225`）；帧内容取 `CommandCatalog.defsFor(Endpoint.ACP)`，再叠 `SlashRegistry.live()` 的技能段 |
| `z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpConnection.java` | `dispatchRequest` 在 `respond()` 之后跑 `runAfterResponse()`（`:201`），ACP_ASYNC 与两个 catch 分支 `clearAfterResponse()`；新增 `scheduleAfterResponse(Runnable)`（`:228`）——广告帧必须排在响应之后，否则客户端先收到 update 再收到它的 `session/new` 回包 |
| `z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpAgentServer.java` | `advertiseCommands(session)`（`:291`），由 `newSession`（`:278`）与 `loadSession`（`:331`）调用；函数头是 initialize 闸 |
| `z-bot-core/src/main/resources/web/index.html` | 侧栏改容器 `#quick-commands`（`:831`）；新增 `loadCommands()`（`:1173`）/`renderQuickCommands()`（`:1188`）/`commandHelpText()`（`:1196`）；`/help` 分支（`:1228`）改为播报 `/api/commands` 的 web 段；**删掉了 6 行手抄清单**（含 `/exit — 退出`，那条 web 根本没有兑现能力） |
| `z-bot-core/src/main/java/com/zifang/z/bot/slash/CommandCatalog.java` | **一个字节没改**：`md5 -q` 现在是 `d705ea48a1fcd5034caaef9cc11a619b`，与 `00abfb4` 版本逐位相等（取证见 §0 与本节上方）。真源归前棒，我只做消费端 |

测试（两枚新文件，21 条）：

- `z-bot-core/src/test/java/com/zifang/z/bot/slash/CommandSurfaceConsistencyTest.java`（11 条）
- `z-bot-core/src/test/java/com/zifang/z/bot/acp/AcpCommandAdvertisementTest.java`（8 条）

台账与交付：`_doc/acceptance/p19/{WIRING.md,EVIDENCE.md,LEDGER.tsv,p19_e2e.py,p19_mutate.py}`、`_doc/acceptance/p28/ROUTES.tsv`（机器再生成，见 §7）。

---

## 3. ACP 帧取证（工单："不许凭记忆编 ACP 方法名/帧形状"）

### 3.1 我搜过的位置与命中数（命令原样）

```
$ git grep -n "available_commands_update" 00abfb4 -- '*.java' | wc -l
2
  → 00abfb4:.../acp/AcpMethods.java:81:    public static final String UPDATE_AVAILABLE_COMMANDS = "available_commands_update";
  → 00abfb4:.../slash/CommandCatalog.java:36: *   <li><b>ACP</b>：{@code session/update} 的 {@code available_commands_update} 帧

$ git grep -n "UPDATE_AVAILABLE_COMMANDS" ba04ce3 -- '*.java'
ba04ce3:z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpMethods.java:81:  ← 全仓只有这一行，即"定义了、零引用"

$ git grep -n "sendAvailableCommands" ba04ce3 | wc -l
0
```

⇒ 前棒的注释（`CommandCatalog.java:36`）说 ACP 端要发这个帧，`AcpMethods:81` 甚至把方法名常量备好了，但**它在接盘时是死代码**：没有任何一处引用、没有任何发送函数。所以"ACP 消费端一行没写"不是夸张，是可数的。

### 3.2 帧形状的权威出处（真帧字段逐一对上 schema，不是抄注释）

`$ grep -n AvailableCommandsUpdate\|available_commands_update <schema> | head`

```
2165:class _AvailableCommandsUpdate(BaseModel):
2442:class AvailableCommandsUpdate(_AvailableCommandsUpdate):
2443:    session_update: Annotated[Literal["available_commands_update"], Field(alias="sessionUpdate")]
3155:            AvailableCommandsUpdate,
```

文件：`/Users/zifang/.hermes/hermes-agent/venv/lib/python3.11/site-packages/acp/schema.py`（156351 字节）。
注：工单/前棒注释里引的 `acp/schema.py:2138` 这个**行号是对的**，但接盘时我不知道文件在哪，`grep -rn ~/.hermes` 会超时；定界后拿到的是 venv 里那份。

| 线上键 | schema 出处（实测行） | 声明 |
| --- | --- | --- |
| `sessionUpdate` = `"available_commands_update"` | `:2443` | `Literal` 判别字，alias `sessionUpdate` |
| `availableCommands` | `:2181` `Field(alias="availableCommands", description="Commands the agent can execute")`，类首 `:2165` | `List[AvailableCommand]`，必填 |
| `AvailableCommand.name` | `:2138` 类首，字段块 `:2159` | |
| `AvailableCommand.description` | `:2152`（`Annotated[str, …Human-readable description…]`） | |
| `AvailableCommand.input` | `:2154`，类型 `UnstructuredCommandInput`（`:1104`），其 `hint` 在 `:1118` | |

⇒ 结论：**ACP 侧确有对应帧**，形状是 `session/update` 通知里 `update.sessionUpdate == "available_commands_update"` + `update.availableCommands[]`，每项 `{name, description, input?{hint}}`。`AcpStreamPublisher.commandNode()` 写的就是这三个键，`input` 只在 hint 非空时才带（schema 里 `input` 是 `Optional`）。

### 3.3 时序与闸（hermes 同构证据，不是我自己拍的）

`/Users/zifang/.hermes/hermes-agent/acp_adapter/server.py`：

```
524:        self._conn: Optional[acp.Client] = None
528:    def on_connect(self, conn: acp.Client) -> None:
529:        """Store the client connection for sending session updates."""
530:        self._conn = conn
1714:    async def _send_available_commands_update(self, session_id: str) -> None:
1716:        if not self._conn:
1717:            return
1722:                update=AvailableCommandsUpdate(
1723:                    session_update="available_commands_update",
1734:    def _schedule_available_commands_update(self, session_id: str) -> None:
1735:        """Send the command advertisement after the session response is queued."""
1736:        if not self._conn:
1737:            return
1740:            asyncio.create_task, self._send_available_commands_update(session_id)
```
（行号取自 `sed -n '522,532p;1714,1718p;1734,1738p'` 的对位输出与 `grep -n`，两种口径互检过。）

调用点：`1122`、`1170`、`1205`、`1242`（新建/加载会话的路径都会广告一次）。
两个事实被我在 Java 侧一比一复刻：① 广告排在会话响应之后（`scheduleAfterResponse`，对应 `:1735` 的 docstring）；② 未建立连接就不发（`if not self._conn: return`，对应我的 `if (!initialized) return;`）。

---

## 4. 杠① 首跑 14 条红：归因与处置（这一段是本笔最贵的）

首跑读数（当时落在 `~/.cache/zbot-p19-lead/bar1_a.log`；该文件后被终跑覆盖，读数是终端原样输出）：

```
BAR1PARSE /Users/zifang/.cache/zbot-p19-lead/bar1_a.log NOT_GREEN build=FAILURE F=12 E=2 socket=0
BAR1PARSE /Users/zifang/.cache/zbot-p19-lead/bar1_a.log module_lines=1 class_lines=107 module_sum=1149 class_sum=1149 F=12 E=2 S=0 build=FAILURE socket_hits=0 agree=YES
```

12 failures + 2 errors = 14，全在 `com.zifang.z.bot.acp` 包里：`AcpRealAgentChainTest`、`AcpApprovalBridgeTest`、`AcpProtocolSurfaceTest`、（`AcpStreamPublisherTest` 自己是 11/11 绿的）。

**归因（不是推定，是对拍）：**另起一个 detached worktree `~/.cache/zbot-p19-base` 指向接盘基线 `ba04ce3`，只跑这 4 个类：

```
67:[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, ... in com.zifang.z.bot.acp.AcpRealAgentChainTest
71:[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, ... in com.zifang.z.bot.acp.AcpProtocolSurfaceTest
73:[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, ... in com.zifang.z.bot.acp.AcpStreamPublisherTest
76:[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, ... in com.zifang.z.bot.acp.AcpApprovalBridgeTest
80:[INFO] Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
97:[INFO] BUILD SUCCESS
```

⇒ 基线 40/40 全绿 ⇒ **红是我引入的**：我在 `session/new` 之后多插了一帧，而这些测试是**按位置**读 `recorder.last()` 的，帧数一变它们就错位。

处置选择上有一条硬约束：不能为了让自己的绿去改别人的判据。所以：

- **没改**任何既有测试的判据（4 个类的文件一行没动，`git diff --stat` 里不含它们）；
- 把广告帧的**触发条件**改成协议正确的：`AcpAgentServer.advertiseCommands()` 头部 `if (!initialized) { return; }` —— 依据就是 §3.3 的 `server.py:1716/:1736`（`_conn` 在 `on_connect` 之后才有值，而 `on_connect` 发生在 `initialize` 之后）。这不是"绕过测试"，而是"未握过手的连接本来就不该被广告"，与 hermes 一致；
- 我自己的 8 条测试补上 `initialize` handshake（否则它们测不到东西）；
- 另加 2 条**闸测**把这条行为钉住：`nothingIsAdvertisedBeforeInitialize`、`initializeThenNewDoesAdvertise` —— 这样"发/不发"的边界变成有测试的契约，而不是我脑子里的默契。

副作用检查：`AcpProtocolSurfaceTest` 里那批按位置读帧的判据，在 initialize 闸下重新成立（因为它们不发 `initialize`，等价于接盘时的行为）。

---

## 5. 四杠读数（原样贴脚本输出）

### 杠① 全 reactor `mvn -o test` ×3（终态，`bar1_{a,b,c}.log`）

```
BAR1PARSE /Users/zifang/.cache/zbot-p19-lead/bar1_a.log module_lines=1 class_lines=107 module_sum=1151 class_sum=1151 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE /Users/zifang/.cache/zbot-p19-lead/bar1_b.log module_lines=1 class_lines=107 module_sum=1151 class_sum=1151 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE /Users/zifang/.cache/zbot-p19-lead/bar1_c.log module_lines=1 class_lines=107 module_sum=1151 class_sum=1151 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
```

分母口径自校对：`module_sum == class_sum == 1151`（`agree=YES`）。我新加的两类在终跑里的单项：

```
750:[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, ... in com.zifang.z.bot.slash.CommandSurfaceConsistencyTest
768:[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, ... in com.zifang.z.bot.acp.AcpCommandAdvertisementTest
```

（`z-bot-core/target/surefire-reports/` 里这两类现在是 `Tests run: 1, Failures: 1` 的残留——那是杠②变异跑最后一针留下的，不是终态。终态以 `bar1_*.log` 为准。）

### 杠② 具名变异（`python3 _doc/acceptance/p19/p19_mutate.py`，抢 `zbot-mutlock`）

```
LEDGER=/private/tmp/zbot-wt-p19/_doc/acceptance/p19/LEDGER.tsv rows=8 tally={'KILLED': 8} secs=86
mtimes harness=1790429015 ledger=1790429101
```

每行都带 `marker=KILLED base=green ran=1 F=1 named=True restored=True`。
`KILLED=8 / SURVIVED=0 / RED-OK=0 / PARTIAL=0 / INJECTION_NOT_APPLIED=0`。台账全文见同目录 `LEDGER.tsv`（机器生成，别手敲）。

### 杠③ 真进程 E2E ×3（`python3 _doc/acceptance/p19/p19_e2e.py --runs 3`，`~/.cache/zbot-p19-lead/bar3_runs3.log`）

全文原样（`bar3_runs3.log` 一字未改，2310 字节、23 行）：

```
REPO=/private/tmp/zbot-wt-p19
BAR4|tag=t0_before dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
=== RUN 1 ===
NOTE|serve 起在 http://127.0.0.1:54637（profile=e2e-profile-r1）
NOTE|R1 /api/commands 行=29 别名=3 段大小 tui=29 http=20 web=6 acp=20
NOTE|R1 判据B 过：repl /help 的 26 条 == /api/commands 的 tui 段(去别名)
NOTE|R1 判据C 过：web 段 6 条与真 HTML 字节的分支逐名字相等
NOTE|R1 判据E 过：改一条分支即判出漂移 (['/confirm', '/confirmX'])
BAR4|tag=run1_after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
NOTE|serve 已收（pid=35762 rc=143）
=== RUN 2 ===
NOTE|serve 起在 http://127.0.0.1:54645（profile=e2e-profile-r2）
NOTE|R2 /api/commands 行=29 别名=3 段大小 tui=29 http=20 web=6 acp=20
NOTE|R2 判据B 过：repl /help 的 26 条 == /api/commands 的 tui 段(去别名)
NOTE|R2 判据C 过：web 段 6 条与真 HTML 字节的分支逐名字相等
NOTE|R2 判据E 过：改一条分支即判出漂移 (['/confirm', '/confirmX'])
BAR4|tag=run2_after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
NOTE|serve 已收（pid=35793 rc=143）
=== RUN 3 ===
NOTE|serve 起在 http://127.0.0.1:54652（profile=e2e-profile-r3）
NOTE|R3 /api/commands 行=29 别名=3 段大小 tui=29 http=20 web=6 acp=20
NOTE|R3 判据B 过：repl /help 的 26 条 == /api/commands 的 tui 段(去别名)
NOTE|R3 判据C 过：web 段 6 条与真 HTML 字节的分支逐名字相等
NOTE|R3 判据E 过：改一条分支即判出漂移 (['/confirm', '/confirmX'])
BAR4|tag=run3_after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
NOTE|serve 已收（pid=35872 rc=143）
BAR4|tag=t3_final dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
E2E|result=PASS runs=3 notes=18
```

三次三个不同端口（54637/54645/54652），`--port 0` + 从 serve 自己打印的 URL 反解，没绑过 `0.0.0.0`。
`rc=143` = SIGTERM 回收，不是我把它杀了装作通过。

判据 E 是**正对照**：改 HTML 里一条分支（`/confirm`→`/confirmX`）后量具必须判出漂移——它判出了，所以 C 那条"过"不是空转。
踩坑记账：repl 的 `/help` 表被 SGR 色码包裹（`'    \x1b[38;5;51m/tools …'`），第一次跑判据 B 直接 FATAL"没扫到任何表行"。修法是在**渲染层**剥 ANSI（`ANSI = re.compile(r"\x1b\[[0-9;]*m")`），不是放宽判据。

### 杠④ 真 profile 三时点不变量（原样，5 个 tag 全等）

```
BAR4|tag=t0_before dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
BAR4|tag=run1_after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
BAR4|tag=run2_after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
BAR4|tag=run3_after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
BAR4|tag=t3_final dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 minimax_key_len=125 entries=.stty.bak,config.properties,cron,memories,models-cache.json,sessions,state.db,workspace
```

`t0/run1/run2/run3/t3` 都在同一个进程里打，等的是杠③那一次 `--runs 3` 的完整跑。
不变量不是靠我肉眼比对：`bar4()` 自己带断言（`p19_e2e.py:82-83`，`len(entries)!=8 or cfg!="2dadaed0" or db!="690ddbc0" or keylen!="125"` ⇒ `fail()`），终态 `E2E|result=PASS` 就是这 5 个时点都没破的机器凭证。

`entries` 用 `ls -A`（8 条含 `.stty.bak`）；一度用 `ls | wc -l` 得 7，那是量具口径错，不是真 profile 变了。
key 只量长度：`awk -F= '/^minimax\.api\.key=/{print length($2)}'` → `125`，值一次都没读出来过。
E2E 全部跑在 `~/.cache/zbot-p19-lead/e2e-profile-r{1,2,3}`（`--config-dir`），里面写的是 `stub-key-not-real`。真 profile 一个字节没被写过。

---

## 6. 守卫抓什么、怎么证明它抓得到

`WIRING.md` 有逐行的"单源在哪 + 四端各自怎么消费"。这里只贴**判据 ↔ 注入探针**的对应（每条都有对应测试，且每针都进过 LEDGER）：

| 面 | 真判据 | 派生口径 | 注入 |
| --- | --- | --- | --- |
| 目录自检 | `catalogSelfCheckIsGreen` | `CommandCatalog.selfCheck()`（`:513`） | — |
| 注册表 ⇄ 目录 | `serverRegistryRegistersExactlyTheCatalogSegment` | `serverDefs()`（`:363`）vs `SlashRegistry` | M7 |
| HTTP | `httpCommandsEndpointServesExactlyTheCatalogRows` | `namesFor()` vs **真 HttpChannel** 的 `GET /api/commands` 字节（`P28HttpFixture`，port 0，回环） | M3 |
| WEB | `webConsoleHandlesExactlyTheWebSegment` | `namesFor(WEB)`（去别名）vs `target/classes/web/index.html` 里的 `cmd ===` / `cmd.startsWith` 分支 | M1、M4 |
| TUI | `tuiHandlesExactlyTheChannelLocalSegment` | `localDefs()` vs `TerminalChannel.java` / `RawTerminalReader.java` 源码里的 `"/x".equals(name)` 分支 | M2 |
| ACP | `sessionNewEmitsExactlyTheAcpSegmentOfTheCatalog` 等 8 条 | `defsFor(ACP)` + 技能段 vs **真帧**（`AcpFakes.Recorder`） | M5、M6、M8 |
| 四端齐备 | `fourConsumersAreAllDeclared` | 四个 `Endpoint` 都必须有消费者 | — |

两个自己写的 bug 是被**自己的注入探针**逮住的（不是我想明白了才改对的），值得记账：

1. `JS_BRANCH` 原本 `[A-Za-z?]+`，把连字符截了 ⇒ 注入 `/injected-branch` 被读成 `/injected`，判据看着"过"其实错位。改成 `'(/[^']*)'`。
2. `readSourceFile` 从 codeSource 往上推仓库根推少了一层 ⇒ 拼出 `z-bot-core/z-bot-core/...`。改名 `readMainSource`、走模块相对路径。

还有一条更要紧的：`httpFacetInjectionIsCaught` 最初把同一个 list 喂给 `diff()` 两侧——**永远不可能红**的假探针。重写成 derived vs `with(derived, "/injected-not-in-catalog")` 再加反向注入。
`diff()` 引擎对空集合一律 FATAL，所以"源码没读到/正则没命中"不会伪装成通过。

---

## 7. ROUTES.tsv（不是手写的）

`GET /api/commands` 进了台账，`_doc/acceptance/p28/ROUTES.tsv` 由测试机器再生成：

```
# 分母: 26 行 (方法粒度) / 23 条路径 (dispatch 字面量口径)
30:GET	/api/commands	JSON_ARRAY	NONE(loopback-only)	LOOPBACK_DEFAULT(opt-in via --host)	array;rowfields:-:name,description,args,scope,endpoints,aliasOf	CommandCatalog.defs() + SlashRegistry.live() 的技能段	-
```

再生成方式（写在文件头）：`mvn -o test -Dtest=HttpRouteLedgerTest#routesTsvIsInSyncWithLedger -Dp28.routes.write=true`，然后 `cp z-bot-core/target/p28/ROUTES.tsv _doc/acceptance/p28/ROUTES.tsv`。

端点名是我定的（工单没指定）：取 `/api/commands` 而不是 `/api/slash-commands`，因为它广告的是**整张表**（含通道私有条目，`scope=channel`），"slash" 会把口径说窄；且 `/api/*` 前缀与既有 p28 路由一致。命名取舍在 `WIRING.md` 也有一段。

---

## 8. 没做完 / 交给 lead 的事（按工单"以及你没做完什么"）

1. **p28 `WIRING.md` 的再派生没做**（工单 §1′.2）。实测理由，不是判断：
   - `_doc/acceptance/p28/w_diff.sh` 第一行是 `cd /private/tmp/zbot-wt-p28`，在 P19 工作树上跑会量错盘；
   - 它表 A/B/D 的锚是 **P19 之前**的形状（手抄 web 清单、ACP 无广告帧、命令表 4 处散定义）——这些正是本笔按设计删掉的东西，锚点必然失效。
   - 我一度写了替代量具 `w_derive.sh`，实测吐 `sed: bad flag`、`W_web_branches=0`、`T_tui_local_branches=0`、`A_acp_visible=6`——一个会把"量不到"报成 0 的量具比没有量具更坏，**已删除**，没入库。P19 侧的数字改由 `p19_e2e.py` 的 `NOTE|` 行给出（§5 杠③原文），且判据 E 证明它会咬。
   - ⇒ 需要 lead 决定：是改 `w_diff.sh` 的锚以适配单源后的形状，还是认账"P19 之后 p28 表 A/B/D 不再适用"。
2. **分支落后 main 一笔**：`main` 现在是 `9884059 test(p28): 修掉 S0b 的第二处失真——拿括号当回环锚`；我的 merge 停在 `cda3452`。禁改 main 工作树、也没 push，所以 rebase/再合留给 lead。
3. **技能段不进 WEB 段**是有意的、且写在唯一一处：`CommandCatalog:440-447`（web 控制台没有技能转发分支，p28 `WIRING.md` §3.1 量的就是"缺转发"）。如果 lead 想让 web 也吃技能命令，那要的是**先补 web 的转发兑现**再开这一段——广告即兑现，顺序不能反。
4. **web 段只有 6 条**（`/status /confirm /help` + `/new /clear /tools`，实测量具 `web=6`）。这是当前兑现面的真实大小，不是漏配。扩它要配注入探针，否则 §6 那张表就空转。
5. **ACP 技能段依赖 `SlashRegistry.live()`**：没有活注册表时（单测路径）只广告目录段。真进程侧 ACP 只有 `serve` 的 stdio 通道，E2E 的量是 HTTP+TUI+WEB 三面；**ACP 帧的端到端真进程验证只有 JVM 内 fake-transport 测试**，这是覆盖面上诚实的一格空缺。
6. 杠②的 8 针都只打在"命令面"这一件事上。别的（HTTP 状态码、技能发现顺序）不在本笔守卫的射程内。
