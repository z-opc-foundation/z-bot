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

## §8 杠②：变异注入 `_doc/acceptance/p22/p22_mutation.py`

STATUS: 未跑

## §9 杠③：真进程 E2E `≥3` `_doc/acceptance/p22/p22_e2e.py`

STATUS: 未跑

## §10 杠④：`~/.zbot` 一字未动（三时点）

STATUS: 进行中（T0 已取）

## §11 安全红线自查

STATUS: 未跑

## §12 明确不做（逐条理由）

STATUS: 未跑

## §未做清单

STATUS: 未跑
