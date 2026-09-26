# P22 执行后端 SPI — EVIDENCE（工单 p22a）

- 工作树：`/private/tmp/zbot-wt-p22`　分支：`w9-p22`　起点 commit：`d23cd0d4c0206f4e51cc58f8b33613b52742006c`
- 对标：`~/.hermes/hermes-agent` @ `cbc1054e2`（只读）
- 开工时刻（`date` 现取）：`Sat Sep 26 15:53:02 CST 2026`
- 本文件所有数字均为本轮实测；`STATUS: 未跑` = 未做，不代表通过。

## §0 第 0 步：工单量具读数复算（动笔前）

STATUS: 部分完成（行号类已全部复算，见下）

## §1 SPI 与语义（`tool/env/ExecEnvironment`）

STATUS: 未跑

## §2 local 后端（含逐字节回归）

STATUS: 未跑

## §3 docker 后端（含本地假 dockerd 逐字段断言；真 daemon 验收状态）

STATUS: 未跑

## §4 ssh 后端（目标主机来自配置 / 无命令拼接 / 不静默降级）

STATUS: 未跑

## §5 孤儿容器回收与资源限额

STATUS: 未跑

## §6 既有三套 exec 的处置与接线层

STATUS: 未跑

## §7 杠①：全量单测 ×3 串行（全 reactor）

STATUS: 未跑

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
### §0 第 0 步：工单量具读数复算（动笔前逐条）　STATUS: 已完成（7 条，1 条证伪 + 1 条低估）

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
