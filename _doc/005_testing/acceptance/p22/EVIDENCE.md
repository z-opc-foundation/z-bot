# P22 执行后端 SPI — EVIDENCE（工单 p22a）

- 工作树：`/private/tmp/zbot-wt-p22`　分支：`w9-p22`　起点 commit：`d23cd0d4c0206f4e51cc58f8b33613b52742006c`
- 对标：`~/.hermes/hermes-agent` @ `cbc1054e2`（只读）
- 开工时刻（`date` 现取）：`Sat Sep 26 15:53:02 CST 2026`
- 本文件所有数字均为本轮实测；`STATUS: 未跑` = 未做，不代表通过。

## §0 第 0 步：工单量具读数复算（动笔前逐条）　STATUS: 已完成（7 条，1 条证伪 + 1 条低估）

**0.1 行数**
```
$ wc -l z-bot-core/src/main/java/com/zifang/z/bot/tool/{ExecGuard,BuiltinTools,ApprovalService,Confirmations,Toolkit,Toolsets,Sandbox}.java
1242 ExecGuard / 503 BuiltinTools / 492 ApprovalService / 85 Confirmations / 567 Toolkit / 266 Toolsets / 47 Sandbox
```
→ **证实**（工单七个数一字不差）。
```
$ ls z-bot-core/src/main/java/com/zifang/z/bot/tool/env
ls: .../tool/env: No such file or directory
```
→ **证实**：仓里没有任何执行环境抽象/后端层/SPI。

**0.2 执行落点（工单 §0 的核心假设）—— 证伪**
```
$ git grep -n 'ProcessBuilder\|Runtime.getRuntime' -- '*/src/main/*'
channel/DeliveryLedger.java:311:  new ProcessBuilder("ps", "-p", String.valueOf(pid), "-o", format).start();
checkpoint/CheckpointManager.java:50/:194/:212:  new ProcessBuilder(...)
checkpoint/CheckpointManager.java:226:  private static String exec(ProcessBuilder pb, String what)
tool/BuiltinTools.java:126:  Runtime rt = Runtime.getRuntime();
tool/BuiltinTools.java:136:  Runtime rt = Runtime.getRuntime();
tool/BuiltinTools.java:457:  ProcessBuilder pb = new ProcessBuilder("bash", "-c", script);
ui/RawTerminalReader.java:73:  new ProcessBuilder("which", "stty").start();
ui/RawTerminalReader.java:238/:252:  Runtime.getRuntime().exec(new String[]{"sh", "-c", ...});
```
实测 `sed -n '124,132p'` ⇒ BuiltinTools.java:126 在 `health()` 里，只读 `rt.totalMemory()/freeMemory()`；:136 在 `sysinfo()` 里，只读 `availableProcessors()`。**这两处 `Runtime.getRuntime()` 一个子进程都不派生**。
→ **证伪**：工单说的「`BuiltinTools.java:126` 工具执行走 Runtime.exec」不成立；**工具执行真正的落点是 `BuiltinTools.java:455-490` 私有 `bash(Sandbox,String,int,int)` 里的 `ProcessBuilder("bash","-c",script)`（:457）**，被 `exec`(:320) / `mvn_build`(:392) / `curl_test`(:426) 三条工具共用。本棒接线就接 :455 这一路。

**0.3 "三套各写各的" —— 证实，且工单还低估了一套**
| 落点 | 超时 | 杀进程 | 取输出 | 合并 stderr |
|---|---|---|---|---|
| `tool/BuiltinTools.java:455` `bash()` | **无任何时间上限**，只 `p.waitFor()`(:478)；中止全靠 `InterruptScope.watch(p)`(:463) 的旗子看门狗 | 看门狗只按旗子动手，不端进程树 | 逐行 `readLine` 存进 `StringBuilder`(:465-477)，**行长无上限** | `redirectErrorStream(true)`(:461) |
| `checkpoint/CheckpointManager.java:226` `exec(pb,what)` | `p.waitFor(GIT_TIMEOUT_SECONDS, SECONDS)` | `destroyForcibly()` 单进程，**不端树** | `drain(is)` 整段进内存 | 合并 |
| `channel/DeliveryLedger.java:298` `runPs()` | `p.waitFor()` **无超时** | 仅异常路径 `p.destroy()` | 1024B 块读到 EOF 进 `ByteArrayOutputStream` | **故意不合并**（注释在 :309-310） |
| `ui/RawTerminalReader.java:238/:252` | 未见时间上限 | — | — | — |
→ 第四套（`ui/`）工单没列；本期不动它，进 §未做。

**0.4 测试面**
```
$ git grep -c '@Test' -- 'z-bot-core/src/test/java/com/zifang/z/bot/tool' | awk -F: '{s+=$NF} END{print s}'
134
$ git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'
693
```
→ **证实**（134 / 693 与工单一致）。

**0.5 本机环境（实测，非抄工单）**
```
$ which docker ⇒ /usr/local/bin/docker（Client 29.5.3，Context desktop-linux）
$ docker info ⇒ rc=1
  Server:
  failed to connect to the docker API at unix:///Users/zifang/.docker/run/docker.sock; check if the path is
  correct and if the daemon is running: dial unix /Users/zifang/.docker/run/docker.sock: connect: no such file or directory
$ ls -l ~/.docker/run/docker.sock ⇒ No such file or directory
$ which ssh ⇒ /usr/bin/ssh
```
→ **证实**：docker CLI 在、**daemon 没起**。⇒ 真 daemon 全链路本期只能记「未验收」，见 §3。
（红线自查：全程未启动 Docker Desktop / 任何常驻服务。）

**0.6 工具链**
```
$ java -version ⇒ openjdk version "25.0.2" (Corretto-25.0.2.10.1)   # 运行期 JDK
$ mvn -v        ⇒ Apache Maven 3.9.14
$ grep maven.compiler pom.xml ⇒ source=8 / target=8（仓是 Java 8 源码级 ⇒ 本棒禁用 ProcessHandle/... Java 9+ API，pid 走反射取）
```

**0.7 hermes 锚复算（`~/.hermes/hermes-agent` @ `cbc1054e2`）**
```
$ wc -l tools/environments/*.py ⇒ 共 11 个 .py 文件 / 6486 total
local.py 1534 / docker.py 1460 / base.py 1125 / modal.py 478 / file_sync.py 473 / ssh.py 375
managed_modal.py 282 / daytona.py 270 / singularity.py 265 / modal_utils.py 210 / __init__.py 14
$ ls -A tools/environments/ ⇒ 12 项（11 个 .py + __pycache__，工单说 11 文件 = 只数 .py，成立）
$ grep -n ... base.py:
54:class _BoundedOutputCollector     68: def buffered_chars      73: def total_chars      114: def render
147:def set_activity_callback         156: def touch_activity_if_due   182: def get_sandbox_dir
202:def _pipe_stdin                   236: def _popen_bash           259: def _load_json_store
269:def _save_json_store              275: def _file_mtime_key       289: class ProcessHandle(Protocol)
307:class _ThreadedProcessHandle
```
→ **证实**（行数与 14 个行号锚全部对上）。她的收集器语义（本棒照搬）：`max_chars` 拆 40% head + 60% tail，`total_chars` 记全量，`render()` 拼 `... [OUTPUT TRUNCATED - N chars omitted out of M total] ...`，且 `_UNBOUNDED_CAPTURE_CHARS = 2**63-1` 走同一代码路径。
## §0-b 第 0 步复算（第四棒 p22b，09-26 17:0x 实测；与主编工单读数并排）　STATUS: 已完成（7 条：6 条证实 + 1 条实测比工单更准）

命令与原始读数（全部本棒现跑，不是抄工单）：

| 项 | 工单读数（主编 09-26 16:4x） | p22b 实测（现取） | 判定 |
|---|---|---|---|
| HEAD / 笔数 | `1988bfa`，`d23cd0d..HEAD` = 2 | `git log --oneline -3` 首行 `1988bfa P22a 补口：docker 容器带 cap-drop ALL…`；`git rev-list --count d23cd0d..HEAD` = **2** | 证实 |
| `@Test` 总数 | 729（起点 693 ⇒ +36） | `git grep -c '@Test' HEAD -- z-bot-core/src/test \| awk -F: '{s+=$NF} END{print s}'` = **729** | 证实 |
| 新增三类 | Spi 18 / Docker 12 / Wiring 6 | `wc -l` = 444 / 609 / 431；`grep -c '@Test'` = **18 / 12 / 6** | 证实 |
| 未提交物 | `p22_e2e.py` 21174 B @16:44、`p22_mutation.py` 16521 B @16:40，两个都从未执行过 | `git status --porcelain` = `?? p22_e2e.py` `?? p22_mutation.py`；`ls -lT` = 21174 @16:44:32 / 16521 @16:40:50 | 证实（且 EVIDENCE §8/§9 原文写 `STATUS: 未跑`，两本量具确实一次都没跑过） |
| 杠① | 作者已跑 ×3 全绿 | 本棒按工单只复跑**一次** `mvn -o test`：`[INFO] Tests run: 729, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `Total time: 45.502 s` / rc=0；socket 类命中 `grep -cE 'BindException\|Connection refused\|SocketTimeout'` = **0**（`~/.cache/zbot-p22-lead/logs/bar1_baseline_p22b.log`） | 证实 |
| 杠②③④ | §8/§9 未跑、§10 进行中（T0 已取）、§11/§12/§未做 未跑 | `grep -n 'STATUS' EVIDENCE.md` 实测：205 `STATUS: 未跑`(§8) / 209 未跑(§9) / 213 `进行中（T0 已取）`(§10) / 217、221、225 未跑 | 证实 |
| 真 docker | `docker info` rc=1、`docker.sock` 不存在 | `docker info >/dev/null 2>&1; echo rc=$?` = **rc=1**；但 `ls -l /var/run/docker.sock` 实测是 **一条存在但悬空的符号链接** `docker.sock -> /Users/zifang/.docker/run/docker.sock`（目标不存在） | **工单「docker.sock 不存在」/ 实测「链接在、目标不在」** —— 结论不变（无 daemon），按实测记 |

杠④ 起笔时点（T0，本棒开工第一条命令）：
```
$ ls -A ~/.zbot | wc -l | tr -d ' '   ⇒ 8
$ md5 -q ~/.zbot/config.properties | cut -c1-8   ⇒ 2dadaed0
$ md5 -q ~/.zbot/state.db | cut -c1-8   ⇒ 690ddbc0
$ date '+%Y-%m-%d %H:%M:%S'   ⇒ 2026-09-26 16:51:14
```
→ 与工单第 4 节三时点固定读数 `8 / 2dadaed0 / 690ddbc0` **逐格相同**。

**开工第 1 动作（落盘节奏 §1.1）**：`git add -- <两个 .py + EVIDENCE>` → commit `927ed98`（"原样提交前棒留下的两个量具与 EVIDENCE"，2 files changed / 783 insertions）。这一步之后 HEAD 里的 .py 字节才是杠② 逐字节对账的基准。

## §1 SPI 与语义（`tool/env/ExecEnvironment`）

STATUS: 已完成（13 个文件 / 3527 行真码 + 36 条新单测全绿）

新建包 `z-bot-core/src/main/java/com/zifang/z/bot/tool/env/`（`wc -l` 实测）：

| 文件 | 行 | 职责 |
|---|---|---|
| `ExecEnvironment.java` | 71 | SPI |
| `ExecRequest.java` | 252 | argv 请求（不可变）+ 输出/超时/stdin 策略 + `ProcessObserver` |
| `ExecResult.java` | 173 | 退出码 / 双路输出 / `*Truncated` / `*TotalBytes` / `timedOut` / `killedDescendants` / `backend` |
| `BoundedCollector.java` | 214 | 字节上限的头尾窗口（环形缓冲） |
| `ProcessTree.java` | 92 | `descendants/descendantPids/killTree/awaitGone` |
| `LocalExecEnvironment.java` | 382 | local 后端 |
| `DockerExecEnvironment.java` | 778 | docker 后端（每 exec 一容器） |
| `DockerEngineClient.java` | 403 | 裸 HTTP 打 Engine API（无新依赖） |
| `SshExecEnvironment.java` | 448 | ssh 后端 |
| `ExecEnvConfig.java` | 317 | 只读 `exec.env.*`（**没碰 `config/BotConfig.java`**） |
| `ExecEnvironments.java` | 111 | 后端选择（只 local/docker/ssh）+ 测试可替换的 `install()` 接缝 |
| `ExecEnvException.java` | 56 | 错误码枚举 |
| `JsonLite.java` | 230 | 无依赖 JSON 读/写 |

SPI 实测签名（`ExecEnvironment.java` 行号）：`name()`:30 / `exec(ExecRequest)`:41 / `writeFile(String,byte[])`:44 / `readFile(String)`:47 / `sandboxRoot()`:50 / `supportsStdin()`:53 / `default maxOutputBytesHint()`:56 / `default defaultTimeoutMillisHint()`:61 / `describe()`:66 / `close()`:70。
请求侧只收 **argv**（`List<String>`，逐个 null 检查、`unmodifiableList`），结果侧一律带「截断旗 + 全量字节数」——照 hermes `tools/environments/base.py:54 _BoundedOutputCollector`（40% 头 + 60% 尾、`:68 buffered_chars`、`:73 total_chars`、`:114 render`）的语义搬到字节口径；`BoundedCollector.render()` 连截断提示一起夹进 `maxBytes`（这里踩过一次：提示没夹 ⇒ 渲染结果能超上限，hermes 用 `notice[:available]` 正是要防这个）。

**对工单 §4「三档抽象」的实测更正**（影响本棒取舍，记正文）：
```
$ grep -rn '_BrowserEnvironment' --include='*.py' ~/.hermes/hermes-agent ⇒ 0 命中
$ grep -rn 'class .*Environment' --include='*.py' tools/
  base.py:390 class BaseEnvironment(ABC)     ← 唯一的抽象基类
  local.py:1263 / docker.py:568 / ssh.py:36 / modal.py:164 / modal_utils.py:58
  daytona.py:30 / singularity.py:158 / managed_modal.py:36   ← 8 个子类，全部平级
$ wc -l tools/environments/modal.py ⇒ 478（工单引的 `modal.py:732` 锚不存在）
$ grep -n '_ensure_ssh_available' tools/environments/ssh.py ⇒ :24（工单引的 `ssh.py:237` 实测是 `_ssh_bulk_upload` 的 docstring）
```
⇒ 她现实里就是**一层 ABC**，没有「browser 不继承 env」那一档；本棒按她的现实做一层 `ExecEnvironment`，三档切分进 §12/§未做。

## §2 local 后端（含逐字节回归）

STATUS: 已完成（18 条 SPI 用例 + 6 条接线用例全绿；并查出 1 条产品级挂死缺陷）

- argv 直接 `ProcessBuilder`，**没有** `bash -c`；并发用 `Semaphore` 夹（`exec.env.max.concurrent`，默认 4）。
- 超时 → `ProcessTree.killTree()`（`Process#descendants()`；仓是 Java 8 源码级 ⇒ pid 走反射取）→ 回填 `killedDescendants`；`localExec_timeoutTakesTheWholeProcessTreeDown` 实测子+孙都消失。
- 输出有界（`maxOutputBytes` + `OutputPolicy`），stdin 独立线程喂（对标 `base.py:202 _pipe_stdin`）：`localExec_stdinIsPipedOnItsOwnThread`（`wc -c` 读回 4）。
- 沙箱越界走 `Sandbox.resolve` 一份判定，不复制实现：`localExec_rejectsSandboxEscape`（含 sibling-prefix 那一支）。
- **查出的产品缺陷（老 `bash()`，工单 §3 的猜测是真的，且比猜测更狠）**：老代码逐行 `readLine` 到 `maxLines` 就 `break` 跳出读循环、之后才 `p.waitFor()` —— 到了上限就**不再排空管道**，子进程写满 64KB 管道后阻塞，`waitFor()` 又没有超时 ⇒ **永久挂死**。本轮两处钉住：
  - 单测 `localExec_keepsDrainingAfterTheCapSoWaitCannotHang`（40000 行输出，到上限后仍继续读到 EOF，实测 0.857s 内 18 条全绿）；
  - E2E 模式 `legacy-hang-model`：把老口径原样复现（`break` 后 `waitFor`）并对比新路径，见 §9。
- **逐字节回归**：`BuiltinToolsExecWiringTest.execOutputIsByteIdenticalToTheLegacyImplementation` 把老 `bash()` 抄成对照实现，与接线后的 `exec` 工具比字节（`exit=N` 尾行、`\n...(已截断)`、行长裁剪、尾换行）——老实现 `readLine`+`append('\n')` 会给末行补一个换行，这条差异也被钉进断言里，不是靠"大概一样"。

## §3 docker 后端（含本地假 dockerd 逐字段断言；真 daemon 验收状态）

STATUS: 单测 12/12 绿（假 dockerd）；**真 daemon 未验收**

- 零新增依赖：`DockerEngineClient` 裸 HTTP（`tcp://` 走 `HttpURLConnection`，`unix://` 走 `curl --unix-socket` argv，超时 `ProcessTree.killTree`）。
- 每次 exec 一个容器：`/_ping` → `/containers/create`（`Cmd` 收 argv **数组**、`HostConfig.Memory/NanoCpus/PidsLimit/NetworkMode/CapDrop/SecurityOpt`、`Labels z-bot.p22.{managed,owner-pid,run}`、`AutoRemove=false`）→ `/start` → inspect 轮询 → `/logs?stdout&stderr`（8 字节多路帧解流 + 有界收集）→ `finally` 里 `DELETE ?force=true`。
- 逐字段断言（`DockerExecEnvironmentTest`，12 条 / 实测 0.637s 全绿）：`dockerSendsArgvNotAStringAndCarriesTheResourceLimits`（含限额三项、`NetworkMode:none`、`CapDrop:["ALL"]`、`SecurityOpt:["no-new-privileges:true"]`——后两项是本轮照 hermes `docker.py` 开头的安全承诺补的）、`dockerTimeoutStillKillsAndReclaimsTheContainer`、`dockerReapsLedgerOrphansLeftByADeadProcess`、`dockerContainerIsLedgeredBeforeItStarts`、`dockerTreatsConnectedButNotDockerAsFailure`、`dockerFrameParserOnlyAcceptsRealDockerFrames`、`dockerKeepsOutputBounded`、`dockerFileOpsGoThroughArchiveApi`、`dockerRejectsPathOutsideItsSandboxRoot`、`dockerRefusesStdinInsteadOfQuietlySwitchingToAnotherBackend`。
- 假 dockerd 用**手写 `ServerSocket`**（不是 `com.sun.net.httpserver`——它的 `stop()` 在 `preClose0` 会永久挂住，P22 之前就踩过），只 `bind(0)` 到 127.0.0.1、每个响应都 `Connection: close`（复用连接会让第二次请求报 `Unexpected end of file from server`，这是真踩过的坑）。
- **真 daemon：未验收**（红线禁止我启动 Docker Desktop）。原始报错：
  ```
  $ docker info; echo rc=$?
  Client:
    Context: desktop-linux
  Server:
   failed to connect to the docker API at unix:///Users/zifang/.docker/run/docker.sock; check if the path is
   correct and if the daemon is running: dial unix /Users/zifang/.docker/run/docker.sock: connect: no such file or directory
  rc=1
  ```
  复算命令：`docker info; echo rc=$?` 与 `cd /private/tmp/zbot-wt-p22 && mvn -o -pl z-bot-core -Dtest=DockerExecEnvironmentTest test`。

## §4 ssh 后端（目标主机来自配置 / 无命令拼接 / 不静默降级）

STATUS: 已完成（6 条断言全绿；未连任何真实远端）

- host/user/port/identity **只能**来自 `ExecEnvConfig`（`exec.env.ssh.*`）；请求层给不出主机（`ssh_targetMustComeFromConfig`）。`HOST_PATTERN`/`USER_PATTERN` 白名单校验，带 shell 或 ssh 选项花活的 host 直接拒（`ssh_hostWithShellOrOptionTricksIsRejected`）。
- 无命令拼接：argv 逐元素（`-o BatchMode=yes`、`ConnectTimeout`、`-p`、`-i`、`user@host`、远端调用作为**一个**元素），远端调用里**每个 token 各自** `posixQuote`。
- 这条不是自证：`ssh_metacharactersInArgsCannotEscapeQuoting` 拿真 `/bin/bash` 当分词 oracle，把远端调用喂给 `bash -c "printf '<%s>\n' …"`，实测只切出 **2** 个词、含 `; echo PWNED >&2` 的那串原样落到参数里（没被执行）。
- 不可用就大声失败、绝不降级 local：`ssh_missingIdentityFailsLoudlyNoSilentLocal`（`SSH_NO_CREDENTIALS`）、`factory_unknownBackendFailsLoudlyNeverFallsBackToLocal`（`BACKEND_SELECTION_FAILED`）。
- 与 hermes 的差别（如实记）：她 `ssh.py:349/351` 是 `["bash","-c",shlex.quote(cmd_string)]`——命令串在 Python 侧拼好再整串 quote；本棒请求层根本不收命令串（`ExecRequest` 只收 argv），逐 token quote。都不可注入，差别在「谁负责拼」。

## §5 孤儿容器回收与资源限额

STATUS: 单测绿；真 daemon 下未验收（同 §3）

- **先记账、后起容器**：`dockerContainerIsLedgeredBeforeItStarts` 断言 create/start 之前账本已落盘；条目 key 是 container id，value 带 `run` id 与 owner pid（进程死了留下的就是孤儿）。
- 账本落在 `<configDir>/tool-env/docker-ledger.properties`（hermes `_load_json_store/_save_json_store`（`base.py:259/:269`）的「小 store + 原子写 + 全量重写」口径，键值格式走 java.util.Properties）；E2E 里 `configDir` 是临时 zbot-home，**不碰** `~/.zbot`（见 §11）。
- `reapOrphans()` 两路兜底：① 账本里 owner pid 已死的容器；② 按 label `z-bot.p22.managed` 全量扫（账本被删也能捞回来）。`close()` 先删会话容器再回收。用例：`dockerReapsLedgerOrphansLeftByADeadProcess`。
- 限额默认：memory 512MB / cpus 1.0 / pids 128 / 断网 / `cap-drop ALL` / `no-new-privileges`，全部可 `exec.env.docker.*` 覆写；`AutoRemove=false` 是**故意的**——不自清才需要回收，也把「摘掉 delete」暴露给变异注入。

## §6 既有三套 exec 的处置与接线层

STATUS: 已完成 1 路接线（工具执行主路）；另 2+1 路未动，理由见 §未做

- 工单说的落点 `BuiltinTools.java:126` 实测是 `health()` 里读 `Runtime.getRuntime().totalMemory()`，**一个子进程都不派生**（§0.2）。真正的工具执行落点是 `BuiltinTools.java:455-490` 私有 `bash(Sandbox,String,int,int)` 的 `ProcessBuilder("bash","-c",script)`（:457），`exec`(:320)/`mvn_build`(:392)/`curl_test`(:426) 三路共用。
- 本棒就把 `bash()` 换成后端调用：`ExecEnvironments.current(cwd).exec(...)`，`mergeStreams(true)` 保住老行为、`OutputPolicy.HEAD`、`maxOutputBytes = maxChars*4`、超时取 `env.defaultTimeoutMillisHint()`；行数上限、字符裁剪 + `\n...(已截断)`、尾换行归一都在**调用方**保留 ⇒ 对模型可见的文本逐字节不变（§2 的回归用例钉的就是这个）。diff：+75/−30（`git diff --stat d23cd0d HEAD -- .../BuiltinTools.java` 实测）。
- P12 的中断语义**没丢**：`InterruptBridgeObserver`（`BuiltinTools` 内部类）在 `started` 时 `InterruptScope.watch(p)`、`finished` 时 `unwatch`；`LocalExecEnvironment` 的等待循环**每轮先**问 `observer.interrupted()`（快命令原来根本没机会被观察到），旗子一立就端进程树。用例：`execToolStillCarriesTheInterruptBridge`（用委托包装，不继承 `final` 的 local 后端）。
- 接线本身有独立卫兵：`execToolActuallyRoutesThroughTheInstalledBackend`（装一个假后端，断言 `WIRING` 命中、真 bash 一次都没起）——所以「摘掉接线」在杠② 里是一支**独立变异体**，不会只被算法用例漏掉（P18 的 `D10` 就是这么骗过人的）。
- 未动：`checkpoint/CheckpointManager.java:226`（git 调用，超时 `destroyForcibly` 单进程不端树）、`channel/DeliveryLedger.java:298` `runPs()`（`waitFor()` **无超时**，又是一处同款挂死风险）、`ui/RawTerminalReader.java:238/:252`（工单连列都没列的第四套）。三条都进 §未做，各附复算命令。

## §7 杠①：全量单测 ×3 串行（全 reactor）

STATUS: 已跑（起点 commit 状态 3/3 绿；CapDrop 补丁后按 §7 末「重跑」再取一遍）

命令（串行，`rm -rf z-bot-core/target/surefire-reports` 后跑，日志落 `~/.cache/zbot-p22-lead/bar1_$i.log`）：`mvn -o test`

| 轮 | 结尾 `Tests run` | Failures/Errors/Skipped | RC | 总耗时 | z-bot-core | `BindException\|Connection refused\|SocketTimeout` 命中 |
|---|---|---|---|---|---|---|
| 1 | 729 | 0 / 0 / 0 | 0 | 31.845 s | 31.556 s | 0 |
| 2 | 729 | 0 / 0 / 0 | 0 | 30.168 s | 29.874 s | 0 |
| 3 | 729 | 0 / 0 / 0 | 0 | 29.740 s | 29.391 s | 0 |

`BUILD SUCCESS` ×3；reactor 三个模块（`z-bot` / `z-bot-core` / `z-bot-desktop-packager`）。
测试面：`git grep -c '@Test' HEAD -- 'z-bot-core/src/test'` 求和 = **729**（起点 `d23cd0d` 为 693 ⇒ **+36**），测试文件数 65 → **68**（+3）。本轮新增三类：`ExecEnvironmentSpiTest` 18（0.857s）、`DockerExecEnvironmentTest` 12（0.561s）、`BuiltinToolsExecWiringTest` 6（0.138s）——三条读数都摘自 `bar1_3.log`。

## §8 杠②：变异注入 `_doc/005_testing/acceptance/p22/p22_mutation.py`

STATUS: 已跑两批（第一跑 ts=20260926-165422 量具判词全错 ⇒ 修量具三处；第二跑 ts=20260926-170852 五档计数 `{"GREEN": 1, "KILLED": 9}`，SURVIVED=0，收尾 `git status=''` 还原干净）

**8.0 跑之前的"永挂"体检（工单 §2 末条，命令照原样）**
```
$ grep -rn '\.await()\|\.waitFor()' _doc/005_testing/acceptance/p22/p22_mutation.py z-bot-core/src/test/java/com/zifang/z/bot/tool/
z-bot-core/src/test/java/com/zifang/z/bot/tool/BuiltinToolsExecWiringTest.java:322:        int exit = p.waitFor();
```
→ 命中 **1 处**，全仓量具与三个被测类里没有第二处无界 `await()`。它在前棒的 `legacyBash()`（旧实现的逐字节参照）里 —— 这段是杠② 每支变异体都会重跑的，无界等待一旦在某个变异体下堵死就把**全编队共享的锁**占死（P14a 的死法）。
→ 改法（commit `3992df2`，改的是 `src/test` 的量具参照，产品语义零动）：`BuiltinToolsExecWiringTest.java:322` 由 `int exit = p.waitFor();` 改成
```java
boolean finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
if (!finished) { ProcessTree.killTree(p); fail("参照实现 legacyBash 的 waitFor 超过 20s 未返回…判红，不再无界等待"); }
int exit = p.exitValue();
```
正常退出路径取到的 `exit` 与原实现逐字节相同（`execOutputIsByteIdenticalToTheLegacyImplementation` 复跑仍绿）。顺带体检了另两类无界原语：`grep -rn '\.join()\|CountDownLatch\|await(' ` 在这三个类里只命中 `DockerExecEnvironmentTest.java:251 acceptor.join(2000L)`（有界）。

**8.1 锁（全编队共享，行为照脚本设计）**
- 17:02:07 第二次尝试抢锁失败，脚本自己退：**rc=4**，原始输出：
  `LOCK_BUSY：/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock 被别的写手占着（不重试，直接退）`
- 本棒**没有** sleep 死等、**没有** kill 任何进程、**没有**动别人的锁文件；行为是"回头整批重跑"（17:08:52 那批抢到锁）。
- 收工前的最后一次复测（17:18:1x，`--only WIRING_DETACHED`）**又是 rc=4**（同一条 LOCK_BUSY 输出，`~/.cache/zbot-p22-lead/logs/bar2_rcprobe.log`）—— 别的写手此刻正在跑。
- **如实记账**：ts=20260926-170852 那批是用 `nohup` 起的，本棒当时没 `echo $?`，所以那批的退出码没有实测值；能实测的是它打印的判词行（`五档计数：{"GREEN": 1, "KILLED": 9}`）与收尾复扫 `收尾 git status=''`。不拿这两条冒充 rc。

**8.2 第一跑 ts=20260926-165422（前棒量具的原样行为，一字未改先跑一遍）**
```
五档计数：{"GREEN": 1, "INJECTION_NOT_APPLIED": 8, "SURVIVED": 1}
```
逐档：POSITIVE_CONTROL GREEN（tests=36 rc=0）；`INTERRUPT_BRIDGE` **SURVIVED**（tests=6 rc=0，点名的 `execToolStillCarriesTheInterruptBridge` 没红，实际红 0 条）；其余 8 支全 `INJECTION_NOT_APPLIED`。
→ 8 支"注入没落地"里 **7 支是量具在撒谎**：脚本 `MVN_SUMMARY` 只认 `[INFO] Tests run:` 前缀，而构建失败时 mvn 打的是 `[ERROR] Tests run:`（第一跑日志原文：`[ERROR] Tests run: 6, Failures: 3, Errors: 0, Skipped: 0` / `[ERROR] Failed to execute goal …surefire… There are test failures.`）⇒ 取不到聚合行 ⇒ 被归成"套件跑不起来"。第 8 支（`TREE_KILL`）是 needle 数不对：`count_expect=3`，实测 `12` 空格缩进的 `ProcessTree.killTree(p);` 在 `LocalExecEnvironment.java` 只命中 **1** 次（:217 的 `InterruptedException` 兜底），真正守树的是 :189（旗子路）与 :209（超时路）。
→ 另有一处**串味**：`SANDBOX_CHECK` 落地时真把 `escaped.txt` 写进了沙箱父目录，而那个父目录是所有用例共用的 scratch 根 ⇒ 后续三支（OUTPUT_CAP / SSH_COMMAND_CONCAT / BACKEND_FALLBACK）各多带一条 `localExec_rejectsSandboxEscape:170` 的"额外红"。判词本身没错（点名的用例照样红），但红集被污染，所以把 `localExec_rejectsSandboxEscape` 的沙箱根改成每次调用独立父目录（`tempDir("p22-local-sandbox-" + System.nanoTime() + "/ws")`），并清掉那次留下的 `~/.cache/zbot-p22-lead/test/escaped.txt`。

**8.3 唯一那支 SURVIVED 的归因（这是产品的卫兵盲，不是产品的洞）**
`execToolStillCarriesTheInterruptBridge`（前棒写的）在 spy 后端里**用 `.observer(自己新写的探针)` 重塞了一份 request** 再交给内层后端 ⇒ 它观察的永远是自己的 observer，`BuiltinTools` 那份 `InterruptBridgeObserver` 摘掉之后三支旗子照样全绿。这正是本战役记过的老坑（"测试层没接上真实接线"）。
→ 修法（commit `4e8934f`，仍然只动 `src/test`）：spy 里先 `productObserver[0] = request.observer();` 把**产品递进来的那份**抓下来，然后按桥本身的契约直接验它 —— 绑上 `InterruptFlag` 并 `flag.request(...)` 之后 `productObserver.interrupted()` 必须为真；`started(p)` 必须让 `InterruptScope.liveProcessCount()` +1；`finished(p)` 必须回到原值。摘掉 `.observer(new InterruptBridgeObserver())` ⇒ request 带的是 `NOOP_OBSERVER` ⇒ 三条立刻落空。

**8.4 第二跑 ts=20260926-170852（量具修好之后整批重跑，9 支全具名）**
```
injection=NONE  POSITIVE_CONTROL  GREEN   tests=36  rc=0
WIRING_DETACHED   接线被摘        KILLED  tests=6   rc=1  点名的用例全红（共红 3 条 / 跑 6 条）
INTERRUPT_BRIDGE  P12 中断桥被摘   KILLED  tests=6   rc=1  点名的用例全红（共红 1 条 / 跑 6 条）
SANDBOX_CHECK     沙箱判定被摘      KILLED  tests=18  rc=1  点名的用例全红（共红 1 条 / 跑 18 条）
OUTPUT_CAP        输出上界被摘      KILLED  tests=18  rc=1  点名的用例全红（共红 1 条 / 跑 18 条）
TREE_KILL         超时只杀父       KILLED  tests=18  rc=1  点名的用例全红（共红 1 条 / 跑 18 条）
ORPHAN_RECLAIM    孤儿回收被摘      KILLED  tests=12  rc=1  点名的用例全红（共红 2 条 / 跑 12 条）
LEDGER_REAP       账本兜底被摘      KILLED  tests=12  rc=1  点名的用例全红（共红 1 条 / 跑 12 条）
SSH_COMMAND_CONCAT ssh 拼接命令     KILLED  tests=18  rc=1  点名的用例全红（共红 2 条 / 跑 18 条）
BACKEND_FALLBACK  后端选择静默降级    KILLED  tests=18  rc=1  点名的用例全红（共红 1 条 / 跑 18 条）
五档计数：{"GREEN": 1, "KILLED": 9}
P22 杠② 台账（run=20260926-170852 … 收尾 git status=''）
```
点名红集（脚本从 `[ERROR]   类.方法:行` 抽的具名用例，不是"总数掉了"）：
| 变异体 | 摘的门 | 必须红的用例（实测红） |
|---|---|---|
| WIRING_DETACHED | 不查 `ExecEnvironments.current()`，自己 new local | `execToolActuallyRoutesThroughTheInstalledBackend:118`「exec 工具一次都没经过 SPI —— 接线断了 expected:<1> but was:<0>」＋另 2 条连带 |
| INTERRUPT_BRIDGE | 摘掉 `.observer(new InterruptBridgeObserver())` | `execToolStillCarriesTheInterruptBridge`（8.3 补口后独立红，只它一条 ⇒ 说明这一门只有它守） |
| SANDBOX_CHECK | `sandbox.resolve(path)` 换成裸拼接 | `localExec_rejectsSandboxEscape:166`「越界路径必须被拒（这条红了就是 Sandbox 检查被摘掉）」 |
| OUTPUT_CAP | `maxOutputBytes` 写成 0（=UNBOUNDED） | `localExec_boundsOutputAndSaysSo:102`「stdout 必须被压在上界内：实测 200000」 |
| TREE_KILL | `killTree` → `killParentOnly`（:189/:209 两处） | `localExec_timeoutTakesTheWholeProcessTreeDown`（`killedDescendants` 变 0 ⇒ `>=1` 红） |
| ORPHAN_RECLAIM | `finally` 里的 DELETE 摘掉 | `dockerTimeoutStillKillsAndReclaimsTheContainer:400`＋`dockerSendsArgvNotAStringAndCarriesTheResourceLimits:372`「收尾没 DELETE ⇒ 就是这么留下孤儿容器的」 |
| LEDGER_REAP | `ledgerIds()` 换成空表 | `dockerReapsLedgerOrphansLeftByADeadProcess:423`「删干净之后台账里不该再有它」 |
| SSH_COMMAND_CONCAT | 远端 token 不再 `posixQuote` | `ssh_metacharactersInArgsCannotEscapeQuoting:268`「原始恶意串不许裸进远端命令」＋`ssh_buildsArgvWithoutAnyCommandConcat:253` |
| BACKEND_FALLBACK | 不认识的后端名静默退 local | `factory_unknownBackendFailsLoudlyNeverFallsBackToLocal:202` |

**8.5 还原与复扫**
- 每支跑完脚本立刻 `shutil.copyfile` 还原 + 与 `git show HEAD:` 三方 md5 对账；两批的收尾复扫都是 `收尾 git status=''`。
- 本棒收工前独立复算：`git status --porcelain -- '*/src/main/*'` 行数 = **0**；`git diff --stat HEAD -- '*/src/main/*'` = **0 行**。
- `LEDGER.tsv` 由脚本机械生成（20 行：两批各 10 行），mtime `Sep 26 17:09:31` **晚于** 被跑的 `p22_mutation.py` mtime `Sep 26 17:00:45` —— 台账不作废。（第一跑的 10 行是**修好前**那份 .py 产出的，字节在 commit `927ed98`；两批靠第 2 列 ts 区分，没手动改过任何一行。）
- `WIRING_DETACHED` 是**独立一支**变异体（不吃算法用例），工单点名的 P18 `D10` 那一坑这次有实测红。

## §9 杠③：真进程 E2E `≥3` `_doc/005_testing/acceptance/p22/p22_e2e.py`

STATUS: 已跑 —— 量具修好后的**三整跑全绿**（`E2E_RC=0` ×3、`"runs": 5, "pass_runs": 5`、每跑 49 条断言红 0）；真 docker daemon **NO-RUN**（红线禁止启动 Docker Desktop）

**9.0 先记账：这本量具在前棒手里从没跑过，一跑就连炸七处，全是量具自己的错（产品语义零改动）**
| # | 现象（实测） | 根因 | 改法 |
|---|---|---|---|
| 1 | 17:04 首跑：Python 侧 `FileNotFoundError: ~/.zbot/workspace` 一个场景都没跑 | `zbot_snapshot()` 用 `os.path.isfile` 跟随符号链接，而 `~/.zbot/workspace` 是指向已消失目录的**断链** | 改 `os.lstat` + `S_ISLNK` 分档 + `OSError` 兜底；**绝不跟随链接、绝不打开文件** |
| 2 | local 场景 10 条里红 7 条 | 判词读的是 `SURVIVOR_COUNT/KILLED/STDOUT_CAPPED/STDOUT_TOTAL/STDIN_SEEN/…`，驱动实际打的是 `TREE_SURVIVORS_AFTER/TREE_KILLED_DESCENDANTS/BOUND_RENDERED/BOUND_TOTAL/STDIN_COUNT/…` —— 一个键名都不对 | 判词整体对齐真 marker（13 条），键名逐条从驱动 `grep -n 'System.out'` 抄 |
| 3 | docker 场景 12 条全红、java 炸 `[DOCKER_BAD_RESPONSE] /v1.44/_ping 实测 status=404` | 假 dockerd 的路由没剥 API 版本前缀 | `norm()` 剥 `/vX.Y` |
| 4 | docker「超时那一路也删了容器」「孤儿按账本回收」永远对不上 | ① `DELETE /containers/<id>?force=true` 的查询串被 `([^/]+)` 吃进 id；② `sleep_ids` 写成 `[c[0] for c, b in fake.created]`（c 已是 id 字符串 ⇒ 变成 `['e','e']`） | 路由统一剥查询串；下标改对 |
| 5 | docker `DOCKER_TIMEOUT=false`（看着像"超时没生效"） | 假服务用 `cid.endswith("2")` 猜"第 2 个容器就是 sleep"，实测驱动在 sleep 前已起了 2 个容器 ⇒ sleep 是 `…00003`，假服务当场回 `Running=false` | 改成按 create 请求体里的 `Cmd` 真判 sleep |
| 6 | docker「账本里孤儿那条已划掉」红 | E2E 把孤儿种成 `<id>=owner=999999,run=stale`，产品认的键是 `LEDGER_PREFIX="container."`+id、value 是 `runToken@pid` ⇒ `ledgerIds()` 根本没看见，"账本兜底"这一路 E2E 从没量到 | 种成 `container.<id>=stale-run-token@999999` |
| 7 | ssh 场景 8 条全红、java 抛 `SSH_NO_CREDENTIALS` | E2E 写的键是 `exec.env.ssh.identity`，产品读的是 `exec.env.ssh.identity.file`（`ExecEnvConfig.java:48`） | 改键名 |
| + | ssh「无凭据时显式报 SSH_NO_CREDENTIALS」实测成 `SSH_TARGET_NOT_CONFIGURED` | 驱动给的 `no-cred` 现场 host 与 identity **两样都没配** ⇒ 先撞 host 白名单；产品的"大声失败不降级"成立，短码取决于缺哪一项（`SSH_NO_CREDENTIALS` 那一支由单测 `ExecEnvironmentSpiTest:336` 钉着） | 判词改成"两个显式码之一、且不许 ALLOWED"，把差异写在 detail 里 |
| + | 17:16:30 那次第 3 跑 local 场景红 1 条：`TREE_KILLED_DESCENDANTS=2 / TREE_SURVIVORS_AFTER=2`，而同一次运行的 `TREE_PGREP=50/       0` | 驱动在 `killTree` 之后**立刻**打 ps —— SIGKILL 已发、macOS 还没摘表项（取证竞态，不是没端干净） | Python 侧加**独立、有上限**的 settling 复探（≤1s、每 100ms 一次，复探后仍非 0 就判红），并把驱动首探读数一并留在判词里 —— 不藏 |

**9.1 三整跑原文尾巴（量具定稿后，`~/.cache/zbot-p22-lead/logs/bar3_final{1,2,3}.log`）**
同一份 jar：`jar=z-bot-core.jar sha256[:8]=efbe62e2 HEAD=4e8934faae21ccdc4f76e7225b377ea5aac6b90e`（脚本自打，不是抄的）。
> 说明：这份脚本的结论行**没有** `CHECKS n/m` 这个 token（工单 §3 那么写是口径）——它打的是每个场景一行 `断言 N 条，红 M 条` 加结尾 `runs/pass_runs` 计数与 `E2E_RC`。下面原样贴，不换算成 CHECKS。

```
run1  17:17:46→17:17:54   rc=0
  local  rc=0 secs=1.54  断言 13 条，红 0 条
  docker rc=0 secs=0.8   断言 16 条，红 0 条
  ssh    rc=0 secs=0.43  断言 10 条，红 0 条
  wiring rc=0 secs=0.1   断言  5 条，红 0 条
  legacy rc=0 secs=6.55  断言  5 条，红 0 条
  {"runs": 5, "pass_runs": 5, "zbot_entries": 8, "zbot_drift": []}  E2E_RC=0
run2  17:17:55→17:18:03   rc=0
  local 1.42 / docker 0.77 / ssh 0.43 / wiring 0.08 / legacy 6.43   —— 场景 rc 全 0，断言 13+16+10+5+5=49 条，红 0 条
  {"runs": 5, "pass_runs": 5, "zbot_entries": 8, "zbot_drift": []}  E2E_RC=0
run3  17:18:04→17:18:12   rc=0
  local 1.59 / docker 0.79 / ssh 0.42 / wiring 0.07 / legacy 6.43   —— 场景 rc 全 0，断言 49 条，红 0 条
  {"runs": 5, "pass_runs": 5, "zbot_entries": 8, "zbot_drift": []}  E2E_RC=0
```
每跑独立现场目录（同一个 TS 命名空间，互不复用）：`~/.cache/zbot-p22-lead/e2e/{local,docker,ssh,wiring,legacy}-<ts>/`，机读台账 `~/.cache/zbot-p22-lead/e2e/ledger-<ts>.jsonl`。**没写 `/tmp`。**

**9.2 逐场景实测读数（摘自 run3 的 markers，每条都有读数）**
- local（13/13）：`BACKEND=local`、`TREE_TIMEOUT=true`、`TREE_KILLED_DESCENDANTS=2`、`TREE_COST_MS=849` 量级（判词上限 8000）、`BOUND_RENDERED=4096 / BOUND_TOTAL=5242880 / BOUND_TRUNCATED=true`、`DRAIN_EXIT=0 DRAIN_TOTAL=648890 DRAIN_COST_MS=336`（60000 行不堵）、`STDIN_COUNT=5242880`、`ESCAPE=REFUSED:路径超出沙箱目录 …`、`LOCAL_RC=0`。
- docker（16/16，**假 dockerd**）：请求序列 `GET /_ping → POST /containers/create → POST /containers/<id>/start → GET /containers/<id>/json → GET /containers/<id>/logs → DELETE /containers/<id>`；create body `Cmd=["/bin/echo","hello-from-p22-e2e"]`（数组）、`HostConfig.Memory=268435456 / NanoCpus=500000000 / PidsLimit=64 / NetworkMode=none / CapDrop=["ALL"] / SecurityOpt=["no-new-privileges:true"]`、`Labels.z-bot.p22.managed=true`、`AutoRemove` 非 true；分帧解出 `DOCKER_STDOUT=hello-from-p22-e2e`、`DOCKER_STDERR=to-stderr`；`DOCKER_TIMEOUT=true` 且 sleep 那支的 id 落在 `DELETE` 里；预置的 `container.orphan-from-dead-process-<ts>` 被回收且从台账划掉；`DOCKER_STDIN=CAPABILITY_UNSUPPORTED`（不换后端）。
- ssh（10/10，**只连本机假 ssh**）：`SSH_ARGV=[<现场>/bin/ssh][-o][BatchMode=yes][-o][ConnectTimeout=5][-o][StrictHostKeyChecking=accept-new][-p][22][-i][<现场>/id_ed25519][p22e2e@127.0.0.1][cd '<现场>/remote-sandbox' && '/bin/echo' 'hi; rm -rf /tmp/nope']` —— 远端调用是**一个**元素、逐 token 带单引号；`SSH_STDOUT=ssh-fake-said:hi; not-pwned`、`PWNED` 哨兵文件不存在、`SSH_EXIT=0`。
- wiring（5/5）：`WIRING_SPY_CALLS=1`、`WIRING_TOOL_OUTPUT=exit=0\n从 SPI 回来的\n`、`WIRING_IS_ERROR=false`、`WIRING_RC=0`。（前棒那条"摘掉后端时是显式报错"的判词驱动里根本没做 —— 已删掉这个假期望，改判驱动真做了的 `install(null)` 收尾；"后端不可用要显式红"由单测 `execToolFailsLoudlyWhenTheBackendIsDown` 守，杠② 的 WIRING_DETACHED 那批里它实测红过。）
- legacy（5/5）：`LEGACY_HANG=1`（老口径读满 `LEGACY_READ_LINES=100` 行就撒手 ⇒ 6s 内回不来，模型自己带硬上限）、`NEW_COST_MS` 量级 446、`NEW_TOTAL_BYTES=648890` —— 同场景新路径不堵。
- 端口面：假 dockerd 只 `bind(("127.0.0.1", 0))`（脚本 66 行），Java 侧配的是 `exec.env.docker.host=tcp://127.0.0.1:<port>`（脚本 287 行），ssh 走现场目录里的假可执行文件 —— **没有任何非 127.0.0.1 的出网**。

**9.3 真 docker daemon：NO-RUN**（不是 skip，更不是 passed）
理由照工单：红线禁止启动 Docker Desktop / 任何机器级守护进程。本棒全程只 `docker info` 探了一次状态，收工复测仍是原错：
```
$ docker info >/dev/null 2>&1; echo rc=$?   ⇒ rc=1
（原文报错见本文件 §0.5 与 §3：failed to connect to the docker API at
 unix:///Users/zifang/.docker/run/docker.sock … no such file or directory）
```
复算命令：`docker info; echo rc=$?` 与 `cd /private/tmp/zbot-wt-p22 && mvn -o -pl z-bot-core -Dtest=DockerExecEnvironmentTest test`。
⇒ 本期 docker 后端的真机端到端**未验收**，只有假 dockerd 的逐字段实测 + 单测。

## §10 杠④：`~/.zbot` 一字未动（三时点）

STATUS: 已取三时点，逐格与工单固定读数相同（`8 / 2dadaed0 / 690ddbc0`），无事故

| 时点 | 时刻（`date` 现取） | `ls -A ~/.zbot \| wc -l` | `md5 -q ~/.zbot/config.properties \| cut -c1-8` | `md5 -q ~/.zbot/state.db \| cut -c1-8` |
|---|---|---|---|---|
| T0 起笔（开工第一条命令） | 2026-09-26 16:51:14 | 8 | 2dadaed0 | 690ddbc0 |
| T1 杠③ 中途（三整跑的第 2 跑与第 3 跑之间） | 17:18:03 | 8 | 2dadaed0 | 690ddbc0 |
| T2 收工（本文件写完、提交之前） | 17:21:31 | 8 | 2dadaed0 | 690ddbc0 |

（中间还随手取过一次 17:19:22，同读数；四格全等 ⇒ 一字未动。）

三格全等 ⇒ 一字未动。另附两条独立取证：
- 每跑 E2E 自己也在 Java 进程起起前后各拍一次快照并比对，脚本结尾 `zbot_snapshot` 实测三跑都是 `"zbot_entries": 8, "zbot_drift": []`。
- `.stty.bak`（0 B）与空的 `cron/` 都在原位、没删；`~/.zbot/skills` 本棒一次都没写过（全程零技能安装动作）。
- 现场根一律在 `~/.cache/zbot-p22-lead/`：E2E 用 `-Dzbot.home=<cache 现场>`，`ls -l ~/.cache/zbot-p22-lead/e2e/` 可见每次跑的独立目录。

## §11 安全红线自查

STATUS: 已完成（第 5 节 5 条逐条给"证据命令 + 实测读数"）

1. **真 minimax key 不读不打印不拷贝；唯一允许的探查是量长度**
   ```
   $ awk -F= '/^minimax.api.key=/{print length($2)}' ~/.zbot/config.properties   ⇒ 125
   ```
   全程只用过这一条命令看这个文件（外加 §10 允许的 `md5 -q … | cut -c1-8`）。
   - E2E 每个现场目录里的 `config.properties` 第一行是脚本自己写的 `minimax.api.key=stub-key-not-real`（`p22_e2e.py:36 KEY_STUB`），跑在 `-Dzbot.home=<cache 现场>`，够不着真根。
   - 本棒落在 `~/.cache/zbot-p22-lead/logs/` 的 **15 份日志**逐份 `grep -c minimax` 实测全为 **0**（`bar1_baseline_p22b / bar2_mutation_p22b / bar2_mutation_p22b_run2 / bar2_run2 / bar2_rcprobe / bar3_e2e_run1 / bar3_run1 / bar3_newrun{1,2,3} / bar3_final{1,2,3} / gaugefix_check / package_p22b`）——没有任何 key 进日志。
   - `p22_e2e.py` 的快照函数只 `lstat` 条目名与 mtime、对普通文件算 8 位 md5 前缀（与工单 §4 允许的探针同口径），**从不打开 `config.properties` 的内容进 stdout 或台账**。
2. **没有 pip/uvx 当前置、没有启动 Docker Desktop / 任何常驻守护进程**：全程只跑 `mvn -o …` 与系统自带 `python3`；`docker info` rc=1（§9.3）本身就是"daemon 没被起来"的实测。缺的那一档（真 docker 端到端）记 **NO-RUN**，没写成 skip/passed。
3. **Git 纪律**：本棒 4 笔提交全部是 `git add -- <显式路径>` + `git commit -m "…" -- <同一批路径>`；`git status --porcelain` 在每笔之后回读过。**零** `add -A`/`add .`、**零** 裸 `stash`、**零** `checkout/restore/reset/clean`（工作树里前棒的成果一次都没被丢弃过；唯一"还原"是杠② 脚本自己按字节 `cp` 副本 + md5 三方对账，收尾 `git status=''`）。没有 push、没有碰 `main`、没有进别人的 worktree（全程 `cd /private/tmp/zbot-wt-p22`）。
4. **没为了让量具绿去改 `src/main` 的语义**：`git status --porcelain -- '*/src/main/*'` = 0 行、`git diff --stat HEAD -- '*/src/main/*'` = 0 行（§8.5）。本棒动过的非量具文件只有 2 个，都在 `src/test`：`BuiltinToolsExecWiringTest.java`（无界 `waitFor` 改有界 + 中断桥卫兵补口）、`ExecEnvironmentSpiTest.java`（沙箱用例的父目录隔离）。量具的错（§9.0 那 7 处 + §8.2 那 3 处）与产品的错分开写：本棒**没有**新增确证的产品缺陷，前棒查出的老 `bash()` 挂死在 §2/§9.2 的 legacy 场景里仍复现（`LEGACY_HANG=1`）；新查出的产品级观察只有一条、且是**取证竞态**不是缺陷（killTree 之后立刻 ps 仍看得见条目，见 §9.0 末行）。
5. **插件钩子文本**：本轮次里**没有**出现"安全策略 / `/l2`"之类要求我往 `*.md` 转述内容的文本；如出现，按工单第 5 节第 5 条一律拒绝并在此记录。（顺带记录一次真发生过的干扰：回合中数条 "There is missing code in your previous edit" 之类的系统提示与任务无关，未据此改动任何结论。）

## §12 明确不做（逐条理由 + 复算命令）

STATUS: 已完成（前棒点名的三条路径原样保留，本棒没动它们）

| 未动路径 | 为什么这期不动 | 复算命令（照原样） |
|---|---|---|
| `checkpoint/CheckpointManager.java:226` `exec(ProcessBuilder, String)` | 这是 git 调用那一路，超时用的是 `waitFor(GIT_TIMEOUT_SECONDS, …)` + `destroyForcibly()` **单进程**、不端进程树；本期工单点名的接线落点是 `BuiltinTools.bash()`，接线只接了一路，改它属于另立一期 | `git grep -n 'GIT_TIMEOUT_SECONDS\|destroyForcibly' -- '*/checkpoint/CheckpointManager.java'` 与 `sed -n '220,240p' z-bot-core/src/main/java/com/zifang/z/bot/checkpoint/CheckpointManager.java` |
| `channel/DeliveryLedger.java:298` `runPs()` | `p.waitFor()` **无超时** —— 与 P14a 死于 `CompressorEngineTest:124` 同类。属于工单 §7 加分项，但四杠（§2—§4）在 115 轮早停线之前必须优先收口 ⇒ **本棒没碰**，见「§未做清单」第 1 条 | `sed -n '295,315p' z-bot-core/src/main/java/com/zifang/z/bot/channel/DeliveryLedger.java` 与 `git grep -n 'waitFor' -- '*/channel/DeliveryLedger.java'` |
| `ui/RawTerminalReader.java:238/:252` | 工单连列都没列的第四套 `Runtime.getRuntime().exec(new String[]{"sh","-c",…})`；`mvn -o test` 跑不到（终端 UI 路径），本期既没接线也没卫兵，动它是无对照面的改写 | `git grep -n 'Runtime.getRuntime().exec' -- '*/ui/RawTerminalReader.java'` |

## §未做清单

STATUS: 已记（4 条，各附原因；其中 1 条是工单 §7 加分项，主动放弃以保四杠）

1. **加分项 `DeliveryLedger.runPs()` 的无界 `waitFor()` 未修、未加具名单测** —— 工单 §7 写明"只有 §2—§4 全部收口并 commit 之后才许碰，不许挤掉四杠"。本棒把 115 轮预算花在"两本从没跑过的量具 + 10 处量具缺陷 + 三整跑"上，杠② 的锁还被别的写手占过两次（各 rc=4，退避重跑要整批 ~4 分钟）。现状：`src/main` 一字未动（§11.4），这条留在 HEAD 之下。
2. **真 docker daemon 端到端 = NO-RUN**（§9.3），红线禁止启动 Docker Desktop；假 dockerd 只保证"请求形状"对，不保证 daemon 真按 `CapDrop/PidsLimit` 建容器。
3. **另两套既有 exec（`checkpoint/`、`ui/`）未接进 SPI**（§12 前两行 + 第四行），本期接线只覆盖了 `BuiltinTools.bash()` 这一路 —— SPI 层因此只算"一路接线实测"，不算"三套统一"。
4. **ssh 后端跑的是现场目录里的假 ssh 可执行文件**，"真远端 + 真凭据 + 真网络"这条链路一期没做；另外 `exec.env.ssh.workdir` 的远端 cd 语义只在 argv 形态上断言过，没在真 shell 里断过。

### 本棒没能做完/没做到的（如实）
- 杠① 只按工单复跑了**一次**（729/0/0/0 rc=0，45.502 s）；前棒的 ×3 我没重取，若判词要"本棒口径 ×3"则这一格是缺的。
- ts=20260926-170852 那批杠② 的**进程退出码**没实测（nohup 起的，当时没 `echo $?`）；收工前的补测撞了两次 LOCK_BUSY（rc=4），没拿到成功批的 rc。台账的判词与 mtime 对账都是实测，退出码不是。
- 17:16:30 那一次（ settling 复探加进去之前）的原始 `.log` 被同名三整跑覆写；那次红条的读数（`TREE_SURVIVORS_AFTER=2` 等）摘自覆写前的 grep 输出，机读副本在 `~/.cache/zbot-p22-lead/e2e/ledger-20260926-1716*.jsonl`。
