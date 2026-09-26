# P21 验收证据 — MCP 对齐（StreamableHTTP/SSE transport、tools/list_changed 真注销、父死 watchdog、反向 mcp_serve）

工作树 `/private/tmp/zbot-wt-p21`，分支 `w3-p21`，起点 `926b8b5`。
本文件是唯一决定性读数载体（`.gitignore:5` = `*.log` ⇒ 现场日志不入库，关键读数**原样**贴在这里）。
长日志现场：`~/.cache/zbot-p21/`。

约定：每个 § 两栏 —— **复算命令**（任何人可重跑）与 **实测**（原样贴读数，不转述）。
未跑通的小节一律留在 `STATUS: 未跑` 并写清停在哪一条，不空转、不事后凑。

---

## §0 第 0 步实测（工单给的现状 —— 逐条证伪/证实）

STATUS: 已跑

| # | 复算命令 | 工单假设 | 实测 |
|---|---|---|---|
| 0.1 | `ls z-bot-core/src/main/java/com/zifang/z/bot/mcp/*.java \| wc -l` | 3 | 3 |
| 0.2 | `cat z-bot-core/src/main/java/com/zifang/z/bot/mcp/*.java \| wc -l` | 523 | 523（McpBridge 193 / McpClientFactory 186 / McpManager 144） |
| 0.3 | `javap -classpath $J com.zifang.z.agent.kernel.mcp.McpTransport` | 4 方法公开接口 | `void open()` / `String request(String)` / `void close()` / `boolean isOpen()`，`public interface` ⇒ **HTTP/SSE 可在 z-bot 侧实现，内核零改动** |
| 0.4 | `javap -classpath $J ...McpClient` | 6 方法 | `name/connect/listTools/call/disconnect/isConnected`（`McpClient` 也是公开接口） |
| 0.5 | `grep -n "wrap\|createStdio" .../McpClientFactory.java` | 缝已存在 | `:29 createStdio`（硬编码内核 `StdioMcpTransport`）、`:34 wrap(String,McpTransport)`（注释"测试用"）⇒ **注入点确认** |
| 0.6 | `grep -n "connect()" .../McpBridge.java` | :65 同步无超时 | `:65 client.connect();` |
| 0.7 | `grep -n "createStdio" .../McpManager.java` | :54 唯一生产构造点 | `:54 McpBridge bridge = new McpBridge(McpClientFactory.createStdio(entry), toolkit);` |
| 0.8 | `grep -n "class McpServerEntry" -A 30 .../config/BotConfig.java` | 只有 name+command | `:828-850`，字段仅 `name` / `command`；解析在 `:228-253` |
| 0.9 | `python3 -m pip show mcp \| sed -n '1,3p'` | 1.27.1 | `Version: 1.27.1` |
| 0.10 | `python3 -c "import mcp.server.fastmcp, ... httpx; print(...)"` | 全可 import | `reference server deps all importable` ⇒ 杠③ 有真参照实现，无需联网/装包 |
| 0.11 | `ls z-bot-core/src/test/java/com/zifang/z/bot/mcp/` | P20b 遗产 | `InMemoryMcpTransport.java` `McpBridgeDeregisterTest.java` `McpBridgeReloadReverseAssertionTest.java` `McpManagerTest.java` |
| 0.12 | `git grep -n "STUB_PRETTY" -- '_doc/acceptance/p20b/mcp_stub_server.py'` | :84/85 | `:84`（注释）、`:85` `seps = (", ", ": ") if os.environ.get("STUB_PRETTY") else (",", ":")` |
| 0.13 | `git grep -c '@Test' 926b8b5 -- 'z-bot-core/src/test' \| awk -F: '{s+=$NF} END {print "sum="s}'` | 619 | **sum=619**（58 个测试文件） |

---

## §1 内核 stdio transport 三个已知未修缺陷 —— 逐条实测

STATUS: 未跑
复算命令：待填
实测：待填

### 1.1 响应匹配靠字符串包含（非紧凑 JSON 永远配不上 ⇒ 永久挂死）
### 1.2 30s deadline 不成立（`readLine()` 阻塞，deadline 只在两次往返之间检查一次）
### 1.3 握手自报版本写死 `"version":"0.2.0"`

---

## §2 【判定】内核 stdio 结构上收不到 server 主动推的 `notifications/tools/list_changed`

STATUS: 未跑
这是决定本期设计走向的一条。证法：真 server 在两次 `tools/list` 之间**主动推**一条 notification，
看 z-bot（走内核 `StdioMcpTransport`）侧能不能观察到。阳性对照必须同批配。
复算命令：待填
实测：待填
判定：待填（成立 ⇒ 内核被旁路，"list_changed→真注销"只走 z-bot 侧 transport；且**不对外广告 `listChanged`**）

---

## §3 新增 transport 与注入缝

STATUS: 未跑
文件清单 / 各自实现了什么 / 走 `McpClientFactory.wrap(name, transport)` 的哪一处：待填

---

## §4 `tools/list_changed` ⇒ 真 deregister / register（杠③(c)）

STATUS: 未跑
断言口径 = `Toolkit` 里条目数前后差，不看日志。
复算命令：待填 / 实测：待填

---

## §5 父死 watchdog：`kill -9` z-bot ⇒ MCP 子进程不得成孤儿（杠③(e)）

STATUS: 未跑
前后 `pgrep -P` / `ps` 计数各贴一次。
复算命令：待填 / 实测：待填

---

## §6 反向 `mcp_serve`：官方 SDK 真客户端连 z-bot（杠③(d)）

STATUS: 未跑
只暴露 `conversations`/`messages` 读端。命令与响应原文进这里。

---

## §7 `config/BotConfig.java`：`McpServerEntry` transport 判别 + 老写法回归

STATUS: 未跑
复算命令：待填 / 实测：待填

---

## §8 headers 脱敏双向断言（安全红线）

STATUS: 未跑
同一支测试里两条都要绿：哨兵**确实进了 config**（阳性对照）+ 哨兵**不出现在**日志/`toMapList()`/`toString()` 产物里。
缺任一 ⇒ 记 NO-RUN 而非满分。
复算命令：待填 / 实测：待填

---

## §9 杠① `mvn -o test` ×3 顺序独立

STATUS: 未跑
起点 `@Test`=619（见 §0.13）。每跑贴 `Tests run` + F/E/S + socket 类错误计数（应为 0）。
日志一律 `~/.cache/zbot-p21/`（并发波次：p18a/p12d 也在跑 mvn，不拿它们的日志当我的）。
复算命令：待填 / 实测：待填

---

## §10 杠② 变异注入（`LEDGER.tsv` 只由 `p21_mutation.py` 生成）

STATUS: 未跑
五档 `RED-OK / PARTIAL / GREEN-BUT-MUTATED / BROKEN / NO-RUN`；逐字节还原 + md5 对账；预期红集不事后凑。
复算命令：`awk -F'\t' 'NR>1{print $2}' _doc/acceptance/p21/LEDGER.tsv | sort | uniq -c`
实测：待填

---

## §11 杠③ 真进程 E2E ≥3 整跑

STATUS: 未跑
复算命令：`python3 -u _doc/acceptance/p21/p21_e2e.py`
(a) 官方 SDK 真 stdio server / (b) 官方 SDK 真 StreamableHTTP-SSE server / (c) 外部改工具表⇒真注销 / (d) 反向 mcp_serve / (e) 父死 watchdog
每跑条数 + 状态码纪律（HTTP 状态 + 响应形状）+ `lsof` 复扫 + `ps -o lstart`：待填

---

## §12 杠④ `~/.zbot` 未被污染

STATUS: 未跑
`ls -A ~/.zbot | wc -l` = 8；`md5` 前缀 `2dadaed0` / `690ddbc0` 一字未动。
复算命令：待填 / 实测：待填

---

## §13 安全红线自查

STATUS: 未跑
- 真 key（`minimax.api.key`，125 字符）**从未被读值/打印/复制/提交/进日志**；E2E 只用 `--config-dir`/`ZBOT_HOME` 临时根 + `stub-key-not-real`。
- 全程只连 127.0.0.1，无真外网 MCP 端点。
- 内核仓 `../z-agent-kernel` 一个字节未改（`git -C ../z-agent-kernel status --porcelain` 为空）。
- 无 `pip install`/`uvx` 拉包作为验收前置。
实测：待填

---

## §14 明确不做 / §未做（含理由，不默默省略）

STATUS: 未跑
工单点名不做：OSV 依赖预检、sampling 与 elicitation 全链路 —— 理由待填。
本期新增未做项：待填（越界才验收的一律不越，写"要动 X / 因为 Y / 会撞 Z"）

---

## §3 新增 transport 与注入缝 —— 里程碑 M1（能编译）

STATUS: 已跑（编译 + 类产出）

复算命令：
```
cd /private/tmp/zbot-wt-p21 && mvn -o -q -pl z-bot-core compile && ls z-bot-core/target/classes/com/zifang/z/bot/mcp/
```

实测（`ls z-bot-core/target/classes/com/zifang/z/bot/mcp/`，原样）：
```
McpBridge$1.class   McpBridge$2.class   McpBridge$ConnectionProbe.class   McpBridge.class
McpClientFactory$StdIoMcpClient$1.class  McpClientFactory$StdIoMcpClient$2.class
McpClientFactory$StdIoMcpClient.class    McpClientFactory.class
McpManager$BridgeStatus.class   McpManager.class
McpNotificationListener$1.class  McpNotificationListener.class  McpNotificationSource.class
McpWire.class
SecretRedaction$1.class  SecretRedaction.class
StreamableHttpMcpTransport$1..3.class  StreamableHttpMcpTransport$HttpReply.class
StreamableHttpMcpTransport$Options.class  StreamableHttpMcpTransport.class
ZBotStdioMcpTransport$1..3.class  ZBotStdioMcpTransport$Options.class  ZBotStdioMcpTransport.class
```

**注入缝**：全部走既有的 `McpClientFactory.wrap(String, McpTransport)` 那条口径 —— 新 transport 只实现
公开接口 `com.zifang.z.agent.kernel.mcp.McpTransport`（4 方法），**内核 jar 一个字节未改**（见 §13 的 `git status` 读数）。
新增 `McpClientFactory.create(entry)` 做生产路径的 transport 判别，`createStdio(entry)` 原样保留当
§2 的对照组（它仍走内核 `StdioMcpTransport`）。

| 文件 | 干什么 | 为什么必须在 z-bot 侧 |
|---|---|---|
| `mcp/ZBotStdioMcpTransport.java` | 常驻读线程 stdio：JSON 解析配对端 id、`future.get(timeout)` 真超时、版本可配、`/bin/sh` ppid 监护脚本 | 内核三条缺陷 + 收不到 notification（§1/§2 取证） |
| `mcp/StreamableHttpMcpTransport.java` | POST JSON-RPC + `mcp-session-id` + `mcp-protocol-version` + 202 语义 + **json/SSE 双形态** + 常驻 GET SSE 通知流 + DELETE 收尾 | 内核根本没有 HTTP transport；`McpTransport` 是公开接口 ⇒ 不需要动它 |
| `mcp/McpNotificationListener.java` / `McpNotificationSource.java` | server→client 通知的回调与"这条通道到底能不能收"的自证位 | 不收 ⇒ `listChanged` 不许对外广告 |
| `mcp/McpWire.java` | id 解析 / 请求拼装 / POSIX 单引号化 | 把"按字符串包含配对端"这条错法从根上换掉 |
| `mcp/SecretRedaction.java` | `mask/maskAll/scrub/maskUrl` | headers/url 可能带 secret，三条泄漏路径各拦一条 |

---

# ============ 以下由 p21c（验收层收尾棒）追加，落盘节奏：每 2–3 节一次 ============

## §0b 工单 p21c §1 的"第 0 步实测"逐条复算（起点 86447fe）

STATUS: 已跑（9 条：8 条证实、1 条推翻）

复算命令（逐条，任何人可重跑）：
```
cd /private/tmp/zbot-wt-p21 && git rev-parse --abbrev-ref HEAD && git rev-parse HEAD
cd /private/tmp/zbot-wt-p21 && mvn -o -q test-compile -pl z-bot-core; echo RC=$?
cd /private/tmp/zbot-wt-p21 && for rev in 926b8b5 HEAD main; do printf "%s " $rev; \
  git grep -c '@Test' $rev -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'; done
cd /private/tmp/zbot-wt-p21 && git diff --name-only 926b8b5 HEAD | sort | wc -l
cd /private/tmp/zbot-wt-p21 && git diff --name-only 926b8b5 HEAD | grep -c channel
cd /private/tmp/zbot-wt-p21 && MT=$(git merge-tree --write-tree main w3-p21 | head -1); \
  git ls-tree -r --name-only $MT -- z-bot-core/src/test | while read f; do git cat-file -p "$MT:$f"; done | grep -c '@Test'
cd /private/tmp/zbot-wt-p21 && ls _doc/acceptance/p21/
cd /private/tmp/zbot-wt-p21 && grep -c '未跑' _doc/acceptance/p21/EVIDENCE.md
cd /private/tmp/zbot-wt-p21 && grep -rn 'class ZBotMcpServe' z-bot-core/src/main/java
cd /private/tmp/zbot-wt-p21 && grep -rn '_advertise_list_changed' _doc
python3 -c "import mcp,importlib.metadata as m;print(m.version('mcp'))"
ls -A ~/.zbot | wc -l; md5 -q ~/.zbot/config.properties|cut -c1-8; md5 -q ~/.zbot/state.db|cut -c1-8
```

实测（原样贴读数）：

| 工单 # | 工单假设 | p21c 复算读数 | 判定 |
|---|---|---|---|
| 1 | `mvn -o -q test-compile -pl z-bot-core` ⇒ RC=0 | `COMPILE_RC=0` | 证实 |
| 2 | `@Test` 619 / 661 / 672，净增 +42 | `926b8b5 619`、`HEAD 661`、`main 672`（661−619=42） | 证实 |
| 3 | p21b 相对 base 只碰 **24** 个文件，全在 mcp/cli/config/_doc 内（没碰 channel） | `N_DIFF=      26`；`git diff --name-only 926b8b5 HEAD \| grep -c channel` ⇒ **0** | **推翻一半**：文件数是 **26 不是 24**（多出的 2 支是 `McpWatchdogProbe.java`、`RealMcpHarness.java`）。**"没碰 channel" 成立**（0 命中），26 支全在写域白名单内 |
| 4 | merge-tree 预览 1 行；合并后 `@Test` 预测 714 | `git merge-tree --write-tree main w3-p21` ⇒ 1 行（`6e8efcd2480a28448d074ed800c0b52000b5279a`）；对那棵树机械数 `@Test` ⇒ **714** | 证实（且 714 是从合并树里真数出来的，不是 672+42 的口算） |
| 5 | 杠② 从没跑过：无 `LEDGER.tsv`、无 `p21_mutation.py` | `ls _doc/acceptance/p21/` ⇒ `EVIDENCE.md p21_e2e.py p21_pipe_relay.py p21_ref_mcp_server.py __pycache__` | 证实（`__pycache__/` 是 p21b 跑量具留下的未跟踪目录，属噪声，已纳入清理） |
| 6 | EVIDENCE 里 15 处 `未跑` | `grep -c '未跑'` ⇒ **15** | 证实 |
| 7 | 杠③ 半死：`合计 9 条，失败 3 条`、`用时 0.8s`、`E2E_RC=2`、(b)(c)(d)(e) 零断言 | `~/.cache/zbot-p21-lead/e2e_probe1.log` 逐字：`合计 9 条，失败 3 条: ['a/handshake_version_and_peer_caps', 'a/disconnect_is_final', 'fatal/unhandled']`、`FATAL: 以下小节一条断言都没产出（空输入不许当满分）: ['b', 'c', 'd', 'e']`、`用时=0.8s`；p21c 自己复跑同版本 ⇒ 同样 3 红 | 证实 |
| 8 | 反向 `mcp_serve` 产品码已在 | `z-bot-core/src/main/java/com/zifang/z/bot/mcp/ZBotMcpServe.java:50:public final class ZBotMcpServe {` | 证实 |
| 9 | 本机官方 SDK = mcp 1.27.1（硬失败不是 skip） | `1.27.1`；python `/Library/Frameworks/Python.framework/Versions/3.14/bin/python3`（3.14.0） | 证实 |

补充读数（工单没量但影响判定）：
- `git status --porcelain` 起点只有 `?? _doc/acceptance/p21/__pycache__/` 一支 ⇒ 工作树干净，前棒字节都在 HEAD 里。
- 杠④ 基线（开工时点）：`ls -A ~/.zbot | wc -l` = **8**、config md5 前 8 = **2dadaed0**、state.db = **690ddbc0**、`minimax.api.key` 长度探针 = **125**（只量长度，未读值）。

## §2b 缺陷 A/B/C 的定性与复算（先复算再修）

- **缺陷 B 复算 ⇒ 证实。** 现场 `McpE2eDriver.java:97/98/110/196/202/203/217` 全部走 `fact(k, Boolean.valueOf(...))`，
  而 `fact()`（`:349`）是 `String.valueOf(v)` ⇒ 落盘是小写 `true`/`false`；
  工单点到的 `p21_e2e.py:262/263/296` 比的是 `"True"`/`"False"`（Python `str(True)` 口径）⇒ **两条假红**，
  与产品无关。同一口径 bug 还潜伏在 `:314`（`session_id_present`）与 `:466`（`notification_capable`），
  会在 (b)/(c) 段起来后继续假红 ⇒ 一起改。
  另 `:264` detail 串写 `peer_listChanged=`、`:458` 写 `toolkit=`，与真键名
  `peer_advertises_list_changed` / `toolkit_size_after_unregister` 不一致 ⇒ 已统一（读现场不再误导）。
- **缺陷 C 复算 ⇒ 证实，但机制比工单写的更糟一层。** `p21_e2e.py:864`（原行号）
  `ap.add_argument("--label", default=time.strftime("R%%H%%M%%S"))` —— `%%` 让 strftime 原样吐 `%`，
  所以默认 label 是**字面量** `R%H%M%S`，读数逐字：`工作根=/Users/zifang/.cache/zbot-p21/e2e/R%H%M%S`、
  `汇总行 label=R%H%M%S`。工单说"上一跑的产物会被下一跑读回当真凭据"——**实际更直接**：
  紧跟着的 `if wd.exists(): shutil.rmtree(wd)` 会把上一跑现场**整个删掉**，
  于是"≥3 次整跑"只留得下最后一次的现场（对账不能），且同机并发复跑会互删在途目录。
- **缺陷 A 复算 ⇒ 证实。** `git grep -n '_advertise_list_changed' -- '_doc'` 全仓唯一命中就是
  `p21_ref_mcp_server.py:151` 的**调用点**，无任何定义 ⇒ 官方 http server 一启动就 `NameError`，
  现场 `~/.cache/zbot-p21/e2e/R%H%M%S/ref-http.out`（工单读数 `RuntimeError: 官方 http server 起不来，exit=1`）。
  根因查到底（本机 site-packages 取证）：`mcp/server/streamable_http_manager.py:196` 与 `:276` 调的是
  **不带参数**的 `self.app.create_initialization_options()` ⇒ 走 `NotificationOptions()` 默认全 false
  （`mcp/server/lowlevel/server.py:185`），而 `get_capabilities()`（`:216`）把它写进
  `ToolsCapability(listChanged=notification_options.tools_changed)` ⇒ **官方 SDK 的 http 路径默认永远广告 listChanged=false**，
  与"我这个 server 到底能不能推"无关。stdio 路径没这问题（`run_stdio()` 显式传了 `_init_options()`）。

## §15 杠③ 真进程 E2E 三跑（`p21_e2e.py` 整跑 a–e，真 JVM / 真进程 / 官方 SDK 真客户端）

STATUS: 已跑（3 次整跑全绿，现场目录三次各不同）

复算命令：
```
cd /private/tmp/zbot-wt-p21 && for i in 1 2 3; do python3 -u _doc/acceptance/p21/p21_e2e.py \
  > ~/.cache/zbot-p21/bar3/full-$i.txt 2>&1; echo RC=$?; done
grep -E '读数汇总|合计|ALL GREEN' ~/.cache/zbot-p21/bar3/full-*.txt
```

三跑汇总（逐字，`full-1/2/3.txt`）：
```
官方 SDK mcp 版本=1.27.1 python=/Library/Frameworks/Python.framework/Versions/3.14/bin/python3 工作根=/Users/zifang/.cache/zbot-p21/e2e/R150901-p90704
== P21 杠③ 读数汇总 label=R150901-p90704 用时=72.1s ==   (a) 17  (b) 14  (c) 11  (d) 13  (e) 4   合计 59 条，失败 0 条: []  ALL GREEN
label=R151014-p94426 用时=70.4s   (a) 17  (b) 14  (c) 11  (d) 13  (e) 4   合计 59 条，失败 0 条: []  ALL GREEN
label=R151125-p97447 用时=70.7s   (a) 17  (b) 14  (c) 11  (d) 13  (e) 4   合计 59 条，失败 0 条: []  ALL GREEN
```
三跑的 `^\[FAIL` 行数：`full-1.txt:0 full-2.txt:0 full-3.txt:0`；分节条数三次逐字一致。
（`grep -c '^\[FAIL'` 无匹配时 zsh 会把 `0` 打成 `No matches found` —— 这里按文件逐个量，0 支就是 0 支。）

现场目录三次不同 ⇒ 缺陷 C 已修死（原读数 `工作根=…/e2e/R%H%M%S` 三次同名，见 §2b）。
量具自证三条（每跑都产）：
```
[PASS] a/fresh_workdir_two_runs_are_distinct_paths :: run1=PROBE-0-150902-p90704 run2=PROBE-1-150903-p90704
[PASS] a/fresh_workdir_second_run_cannot_see_first_run_files :: 第二跑目录里的文件=[] 第一跑的哨兵仍在=True
[PASS] a/fresh_workdir_default_label_is_formatted :: 本次 label=R150901-p90704
```

### 15.1 (a) 官方 stdio server —— 布尔口径修好后的关键读数 + 断言杀力实测
```
[PASS] a/handshake_version_and_peer_caps :: server_protocol=2025-06-18 peer_advertises_list_changed=true notification_capable=true server_info=p21-ref-server@1.27.1
[PASS] a/disconnect_is_final :: open_after_disconnect=false toolkit_size_after_unregister=0
```
缺陷 B 修完之后**仍杀得死**两种坏法（拿本次真 FACT 注入重放，逐字）：
```
[PASS] a/gauge/bool_baseline_passes_on_real_readings :: 真读数下两条都过=['handshake_version_and_peer_caps', 'disconnect_is_final']（红=[]）
[PASS] a/gauge_kills_peer_never_advertises_listChanged :: 注入 peer_advertises_list_changed='false'（对端根本不推 listChanged）⇒ 红=['handshake_version_and_peer_caps'] 异常=
[PASS] a/gauge_kills_reopen_after_disconnect :: 注入 open_after_disconnect='true'（真断开后又打开）⇒ 红=['disconnect_is_final'] 异常=
[PASS] a/gauge_kills_toolkit_not_emptied :: 注入 toolkit_size_after_unregister='5'（断开没真 deregister）⇒ 红=['disconnect_is_final'] 异常=
[PASS] a/gauge_kills_unparseable_bool_value :: 注入 peer_advertises_list_changed='TRUEISH'（读不出的布尔值必须抛错，不许悄悄当 false）⇒ 红=[] 异常=ValueError: 布尔 FACT … 无法归一化
[PASS] a/gauge_kills_missing_fact_key :: 注入 notification_capable=None（读不到不许当满分）⇒ 红=[] 异常=MissingFact: notification_capable
```
⇒ 口径收敛成 `flag()` 一处：只认 `true/1/yes` 与 `false/0/no`，**读不到 = 抛错**，认不出 = 抛错，
不存在"值随便是什么都算过"。

### 15.2 (b) 官方 StreamableHTTP/SSE server —— 缺陷 A 修好 + 能力位不是空断言
```
[PASS] b/http_transport_class_and_url :: transport_class=com.zifang.z.bot.mcp.StreamableHttpMcpTransport url=http://127.0.0.1:62767/mcp
[PASS] b/session_id_from_initialize_response_header :: session_id_present=true prefix=d7a97f negotiated=2025-06-18 peer_advertises_list_changed=true
[PASS] b/response_shape_counted_and_nonzero :: json=0 sse=1（registerAll 期间）→ json_after=0 sse_after=6（三次 call 之后）
[PASS] b/wire_log_shows_session_header_carried_on_posts :: POST 取证 8 行，其中带会话头 7 行，前缀=448e1e 首行=POST initialize -> 200 text/event-stream mcp-session-id=<none>
[PASS] b/real_server_enforces_the_session_header :: 真会话=200(工具 5) 假会话=404(…-32600…) 不带头=400(…-32600…)
[PASS] b/stub_credential_enters_but_only_masked_renders :: entry_render=McpServer{name='e2e', transport=http, url=http://127.0.0.1:63421/mcp, headers={Authorization=Be********al}} || safe_map={… headerNames=[Authorization] …}
```
对照臂（摘掉 `_advertise_list_changed`，同一个 server 文件只差 `--no-list-changed`）：
```
[PASS] b/negative_control_server_still_serves_five_tools :: 摘掉广告后 server 仍活着：registered=5 names=[…5 条…]
[PASS] b/advertise_patch_flips_python_observed_cap :: python 裸 initialize 看到 tools.cap={"listChanged": false}（打了补丁的那台 listChanged=True）
[PASS] b/advertise_patch_flips_zbot_observed_cap :: z-bot 读到的 peer_advertises_list_changed=false（notification_capable=true 是我方通道状态，不随对端广告变）
```

### 15.3 (c) 外部 actor 改表 ⇒ 真 deregister / register（stdio + http 两条绑定）
```
[PASS] c/stdio_drop_is_real_deregistration_not_hidden :: after1_size=5 after1=[…dyn_a 进来、beta 没了…]（一增一减：条数不变，只有名字集能判）
[PASS] c/stdio_pure_drop_shrinks_the_count_by_exactly_two :: after2_size=3 after2=['mcp-e2e-p21_alpha','mcp-e2e-p21_apply_table','mcp-e2e-p21_echo']
[PASS] c/stdio_notification_drove_the_swap :: notifications=2 refresh=2 waited_ms=57/63 transport_notif=2
[PASS] c/stdio_relay_pushed_line_is_a_notification_not_a_response :: 命中 2 条；第一条=S2C 1790406578.458310 {"method":"notifications/tools/list_changed","jsonrpc":"2.0"}
[PASS] c/stdio_server_actually_changed_and_broadcast :: table_changed=2 notification_sent=2 events=8
[PASS] c/http_swap_was_driven_by_the_get_stream :: refresh=2 transport_notif=2 capable=true sse_shape=1 json_shape=0
```
中继观察点这条原来是个**假绿**：`"list_changed" in line` 会命中 `tools/list` 的**响应体**
（工具描述里就写着"推 list_changed"）⇒ 已改成只认 `"method":"notifications/tools/list_changed"`，
并在同一条里钉"它没有 result/id ⇒ 是 server 主动推的 notification"（改前读数 `list_changed_lines=5`，改后 `=2`，与两轮换血严格对齐）。

### 15.4 (d) 反向 `mcp serve`：官方 SDK 真 client 连 z-bot
```
[PASS] d/official_sdk_client_can_talk_to_zbot :: 官方 SDK client 全程无异常
[PASS] d/advertises_only_tools_and_no_listChanged_promise :: protocolVersion=2025-06-18 serverInfo={…'name': 'z-bot', 'version': '0.2.0-dev'…} tools_caps={'listChanged': False}
[PASS] d/read_only_surface_two_tools_and_no_write_capable_name :: tools=['zbot_conversations_list', 'zbot_messages_read'] banned=[]
[PASS] d/default_list_hides_archived_and_archived_list_shows_all_three :: default=['e2e-live-2','e2e-live-1'] archived=['e2e-arch-1','e2e-live-2','e2e-live-1']
[PASS] d/missing_required_argument_is_jsonrpc_32602 :: {"code": -32602, "message": "zbot_messages_read 需要 params.conversation_id", …}
[PASS] d/raw_wire_one_response_per_request_and_none_for_notifications :: 进了 5 条 (1 条是 notification) ⇒ 出了 4 条
[PASS] d/raw_wire_exits_at_eof_and_reports_a_bounded_summary :: exit=0 收尾行=[…'处理 5 行，拒绝 2 行']
```

### 15.5 (e) 父死 watchdog（含"不吃 stdin EOF"的对照组）
```
[PASS] e/e_watchdog_production_stdio_child_goes_with_the_parent :: before=1 pgrep-P-after=0 child_alive=False gone_ms=138
[PASS] e/e_watchdog_takes_an_eof_insensitive_child_too :: before=1 child_alive=False gone_ms=143
[PASS] e/e_without_watchdog_that_same_child_really_survives_as_orphan :: child_alive=True child_ppid=1 parent=93502（对照失效就说明'带走了'不是 watchdog 干的）
[PASS] e/e_no_orphan_left_behind_by_this_gauge :: 本节点完后还在的孤儿数=0
```
p21b 临终说的 EOF 混淆因子已在对照臂上摘掉：`sleeper` 那组的孩子是 `python -u -c "import time;time.sleep(600)"`
（**根本不读 stdin**，`McpWatchdogProbe.java:57-64`），父死之后它仍活着（`child_ppid=1`）⇒
"没了"只可能是 watchdog 干的，不是"stdin EOF 自己退"。

### 15.6 本期在杠③ 里查出的**产品**缺陷（不是量具）
`StreamableHttpMcpTransport.post()` 原来给 notification 发的 Accept 只有 `application/json`
（`:376` 旧字节 `expectBody ? ACCEPT_BOTH : "application/json"`）⇒ 官方 server 在
`_validate_accept_header()` 里按 2025-06-18 线规（POST 必须同时广告两种媒体类型）直接拒：
逐字现场 `McpRealStdioServerTest` 之外第一次 (b) 跑：
```
[FAIL] fatal/unhandled :: RuntimeError: b-driver 失败 rc=1 error=java.lang.RuntimeException: mcp connect failed: notifications/initialized 返回 HTTP 406
```
已改为 POST 一律 `application/json, text/event-stream`，并留一条断言钉住（`b/accept_header_on_notifications_post_is_both_media_types`，
读数 `['202 application/json mcp-session-id=c90d52']`）。
顺手把会话头写进 wire 取证（只记前 6 位，会话 id 全文不入日志）—— 原来 `wire_log` 里根本没有会话头，
那条断言只能靠"猜 server 认了"，现在是逐 POST 行数对账。
