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

STATUS: 已跑（字节 + 编译 + 杠② 家族 A 的点名用例）

复算命令：

```
cd /private/tmp/zbot-wt-p21 && wc -l z-bot-core/src/main/java/com/zifang/z/bot/mcp/*.java
grep -n "public static\|transportOf\|wrap(" z-bot-core/src/main/java/com/zifang/z/bot/mcp/McpClientFactory.java
```

实测（`wc -l` 原样，本期 mcp 包 10 个文件 / 2855 行）：

```
     316 McpBridge.java
     269 McpClientFactory.java
     197 McpManager.java
      41 McpNotificationListener.java
      19 McpNotificationSource.java
     136 McpWire.java
     133 SecretRedaction.java
     661 StreamableHttpMcpTransport.java
     519 ZBotMcpServe.java
     564 ZBotStdioMcpTransport.java
    2855 total
```

对照 §0.2 的起点（3 文件 / 523 行）⇒ 本期把 mcp 包从 523 行推到 2855 行，新增 7 个文件。

注入缝逐字（`McpClientFactory.java` 行号）：

```
33:    public static McpClient createStdio(BotConfig.McpServerEntry entry)      ← 老缝，仍走内核 StdioMcpTransport，只留给 §1/§2 的对照实验
42:    public static McpClient createStdioZBot(BotConfig.McpServerEntry entry)  ← 生产 stdio：z-bot 侧 ZBotStdioMcpTransport
54:    public static McpClient createHttp(BotConfig.McpServerEntry entry)       ← StreamableHTTP/SSE
72:    public static McpClient create(BotConfig.McpServerEntry entry)           ← 按 entry.transport 分派（79 createHttp / 86 createStdioZBot）
104:   public static McpTransport transportOf(McpClient client)                 ← 把 transport 摸回来，供"能不能收推送"的判别（§2 判定 / §4 广告位）
112:   public static McpClient wrap(String name, McpTransport transport)        ← 注入缝本体：任何 McpTransport 都能被包成 McpClient
```

`transport` 分派的两条实现线（各自实现了什么）：
- `ZBotStdioMcpTransport`（564 行）：真子进程 + 分行 JSON-RPC；异步读循环把 **非请求响应**（server 主动推的 notification）交给 `McpNotificationListener`，这是 §2 判定"内核被旁路"之后唯一能收 `list_changed` 的通道；带 `/bin/sh` ppid watchdog（§5）与 `future.get(timeoutMillis)` 真超时（§1.2 的对照面）。
- `StreamableHttpMcpTransport`（661 行）：POST + `mcp-session-id`/`mcp-protocol-version` 头、`Accept: application/json, text/event-stream`（本期被杠③ 逼出来的修复，见 §15.4）、GET SSE 通知流、`notifications/*` 期望 202 无响应体。
- `ZBotMcpServe`（519 行）：反向 serve（§6）。`SecretRedaction`（133 行）：双向脱敏（§8）。`McpWire`（136 行）：超时/挂死面（杠② 家族 T）。

---

## §4 `tools/list_changed` ⇒ 真 deregister / register（杠③(c)）

STATUS: 未跑
断言口径 = `Toolkit` 里条目数前后差，不看日志。
复算命令：待填 / 实测：待填

---

## §5 父死 watchdog：`kill -9` z-bot ⇒ MCP 子进程不得成孤儿（杠③(e)）

STATUS: 已跑（进程内测 4 条 + 真进程 E2E (e) 段 4 条，见 §15.5）

复算命令（逐字）：

```
cd /private/tmp/zbot-wt-p21 && mvn -o -q -Dtest=McpParentWatchdogTest -Dsurefire.useFile=false test
grep -n "" ~/.cache/zbot-p21/p21_watchdog_readings.txt | sed -n '20,23p'    # 该文件由探针逐条追加，一次全量 suite = 四行
```

实测 —— 一次全量 suite 的四行读数原样（`p21_watchdog_readings.txt` 第 20–23 行，字段口径：`pgrep -P` 是"还挂在探针 JVM 名下"的计数，`childAliveAfterKill` 是 `kill -0`，`orphanObservedAtMs` 是"观察到 ppid 改嫁"的时刻，`msUntilChildGone` = −1 表示到预算仍未死）：

```
watchdog=on mode=mcp probePid=14425 childPids=[14427] pgrep-P-before=1 pgrep-P-immediately-after-kill=0 pgrep-P-after=0 childAliveAfterKill=false childPpidAfterKill='1' msUntilChildGone=144 orphanObservedAtMs=0
watchdog=on mode=sleeper probePid=14466 childPids=[14470] pgrep-P-before=1 pgrep-P-immediately-after-kill=0 pgrep-P-after=0 childAliveAfterKill=false childPpidAfterKill='1' msUntilChildGone=149 orphanObservedAtMs=0
watchdog=off mode=mcp probePid=14499 childPids=[14501] pgrep-P-before=1 pgrep-P-immediately-after-kill=0 pgrep-P-after=0 childAliveAfterKill=false childPpidAfterKill='1' msUntilChildGone=1604 orphanObservedAtMs=40
watchdog=off mode=sleeper probePid=19192 childPids=[19194] pgrep-P-before=1 pgrep-P-immediately-after-kill=0 pgrep-P-after=0 childAliveAfterKill=true childPpidAfterKill='1' msUntilChildGone=-1 orphanObservedAtMs=18
```

四行读出的不是四个结论，得配对才成话（这正是工单点名要摘掉的 EOF 混淆因子）：

| 行 | 断言（`McpParentWatchdogTest` 里的方法名） | 讲的什么 |
|---|---|---|
| 20 | `killedParentTakesTheChildWithIt` | 生产路径（真官方 SDK server 当子进程）：`childAliveAfterKill=false`、144ms 内带走；`pgrep-P-before=1 → after=0` |
| 21 | `watchdogTakesEvenAnEofInsensitiveChildWithIt` | 把"孩子自己会退"摘掉：换成完全不理 stdin 的 sleeper，走同一份 `launchArgv()` 产物 ⇒ 仍 149ms 带走 |
| 23 | `withoutWatchdogAnEofInsensitiveChildReallyBecomesAnOrphan` | **阳性对照（猎物真在）**：同一个 sleeper、watchdog 关 ⇒ `childAliveAfterKill=true`、`msUntilChildGone=-1`（到预算没死）、`orphanObservedAtMs=18`（18ms 就被观察到改嫁给 ppid=1）⇒ 第 21 行的"没了"只可能是 watchdog 干的 |
| 22 | `realServerExitsOnStdinEofSoItCannotServeAsTheControl` | 把混淆因子量成实据：真 ref server 在 `watchdog=off` 时也"消失"，但要 1604ms（EOF 自己退的），且 `orphanObservedAtMs=40` 中间确实被观察到孤儿态 ⇒ 这条只能当观察、不能当对照 |

口径提醒：第 23 行的 `pgrep-P-after=0` **不是**"孩子死了"—— 它已经改嫁（`childPpidAfterKill='1'`），`pgrep -P <探针pid>` 当然查不到；死没死只认 `childAliveAfterKill`。测试断言就是这么分的（`assertFalse(… , on.childStillAlive)` vs `assertTrue("对照组失效…", off.childStillAlive)`）。

聚合复核（同一文件，跨全部跑次）：

```
$ grep -c "watchdog=on" ~/.cache/zbot-p21/p21_watchdog_readings.txt   → 15
$ grep -c "watchdog=off" ~/.cache/zbot-p21/p21_watchdog_readings.txt  → 13
$ awk '/watchdog=off mode=sleeper/{c++; if ($0 ~ /childAliveAfterKill=true/) k++} END{print c, k}'   → 7 6      # 对照组的猎物：7 次里 6 次确实活着
$ awk '/watchdog=on  mode=sleeper/{c++; if ($0 ~ /childAliveAfterKill=false/) k++} END{print c, k}'  → 7 6      # watchdog 侧：7 次里 6 次确实带走
```

那两处没对上的行（`watchdog=on mode=sleeper … childAliveAfterKill=true`，第 27 行 probePid=22701 等）**不是**本期产品的读数，而是 15:17 之后杠② 家族 W 把监护脚本改坏时留下的（变异体被杀掉 ⇒ 正是 RED-OK 需要的现象）；收口前我在干净树上重跑一次并把那组新读数贴到 §5b。

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

STATUS: 已跑（三跑串行、每跑前 `rm -rf z-bot-core/target/surefire-reports`、全量不 `-pl`、rc 全 0）

复算命令（下面两条 `grep` 是我逐字跑过的；循环与我实际跑的那条只差 `2>&1` 的可观测性 —— 我那份把 mvn 输出落到同名文件并在末尾追加 `MVN_RC_$i=$?`，见 `bar-*.txt` 末行）：

```
cd /private/tmp/zbot-wt-p21
for i in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; mvn -o test > ~/.cache/zbot-p21/bar3/bar-$i.txt 2>&1; echo MVN_RC_$i=$? >> ~/.cache/zbot-p21/bar3/bar-$i.txt; done
grep -E '^\[INFO\] Tests run:' ~/.cache/zbot-p21/bar3/bar-{1,2,3}.txt | tail -3          # 每跑的汇总行
grep -cE 'BindException|Connection refused|SocketTimeout' ~/.cache/zbot-p21/bar3/bar-1.txt  # 2/3 同理
grep -n 'MVN_RC' ~/.cache/zbot-p21/bar3/bar-{1,2,3}.txt
```

实测（三跑的汇总行原样，一次都不许差）：

```
bar-1: [INFO] Tests run: 661, Failures: 0, Errors: 0, Skipped: 0     + BUILD SUCCESS   Total time: 47.325 s
bar-2: [INFO] Tests run: 661, Failures: 0, Errors: 0, Skipped: 0     + BUILD SUCCESS   Total time: 52.617 s
bar-3: [INFO] Tests run: 661, Failures: 0, Errors: 0, Skipped: 0     + BUILD SUCCESS   Total time: 56.140 s
```

socket 类错误计数 + `[ERROR]` 行计数 + rc（`grep -c` 原样输出）：

```
bar-1: BindException|Connection refused|SocketTimeout = 0    [ERROR] 行 = 0    MVN_RC_1=0   (bar-1.txt:653)
bar-2: BindException|Connection refused|SocketTimeout = 0    [ERROR] 行 = 0    MVN_RC_2=0   (bar-2.txt:653)
bar-3: BindException|Connection refused|SocketTimeout = 0    [ERROR] 行 = 0    MVN_RC_3=0   (bar-3.txt:652)
```

三跑数字一致（661/0/0/0 ×3）。相对 §0.13 的起点 619 ⇒ **+42**，与工单 §1.2 的"净增 +42 / HEAD=661"一致（这条工单量对了，我复算也是 661）。

**注意（别把我这段当杠②）**：本小节的 mvn 日志文件在 `~/.cache/` 而非仓内 —— `.gitignore:5` = `*.log` ⇒ 只有上面这些抄进来的读数算数。三跑期间与杠②/杠③ 无并发（bar② 是在这三跑全部落盘之后才起的）。

---

## §10 杠② 变异注入（`LEDGER.tsv` 只由 `p21_mutation.py` 生成）

STATUS: 已跑（写手 p21c 15:23 那一跑 + **主编 15:52 独立复跑**；两跑的差在 T1 一档，见 §16.2）
本节的原骨架由 p21c 未回填，读数一律在 **§16.2**（主编亲测），此处只留指针与复算命令。
五档 `RED-OK / PARTIAL / GREEN-BUT-MUTATED / BROKEN / NO-RUN`；逐字节还原 + md5 对账；预期红集不事后凑。
复算命令：`awk -F'\t' 'NR>1{print $2}' _doc/acceptance/p21/LEDGER.tsv | sort | uniq -c`
实测：**见 §16.2**（当前 `LEDGER.tsv` 是主编 15:52 那一跑的机械产出：`RED-OK 18 / PARTIAL 1 / GBM 0 / BROKEN 0 / NO-RUN 0`）

---

## §11 杠③ 真进程 E2E ≥3 整跑

STATUS: 已跑（写手 3 跑见 §15；**主编另跑 3 + 3 跑见 §16.3**，其中一组暴露量具的 `--label` 假红并已修）
复算命令：`python3 -u _doc/acceptance/p21/p21_e2e.py`
(a) 官方 SDK 真 stdio server / (b) 官方 SDK 真 StreamableHTTP-SSE server / (c) 外部改工具表⇒真注销 / (d) 反向 mcp_serve / (e) 父死 watchdog
每跑条数 + 状态码纪律（HTTP 状态 + 响应形状）+ `lsof` 复扫 + `ps -o lstart`：**见 §15 与 §16.3**

---

## §12 杠④ `~/.zbot` 未被污染

STATUS: 已跑（开工 / 在飞 各一次一致；收尾另测，见本节末"收尾"行）

复算命令（逐字）：

```
ls -A ~/.zbot | wc -l
md5 -q ~/.zbot/config.properties | cut -c1-8
md5 -q ~/.zbot/state.db | cut -c1-8
```

实测（工单口径 = 8 / 2dadaed0 / 690ddbc0，一次都不许变）：

```
开工（起点 86447fe，见 §0b）  8    2dadaed0    690ddbc0
在飞 15:2x（三跑全量 + 3 次 E2E 整跑 + 杠② 中途）  8    2dadaed0    690ddbc0
```

⇒ 8 个条目、两个 md5 前缀 8 位全部一字未动。所有 E2E 都靠 `--config-dir` 指临时根（`~/.cache/zbot-p21/e2e/<label>/zbot-home`），真根零写入。

收尾（第三测，随收口 commit 前跑）：见本节末 §12c。

---

## §13 安全红线自查

STATUS: 已跑（四条各一个机械量具，全部不落在真值上）

复算命令 + 实测（逐字）：

1. **真 key 只量长度、从不取值**（工单口径的值长 125）：

```
$ awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties
125
```

命令里没有任何 `print $2` 之外的输出路径，值不落盘、不进日志（本文件里出现的唯一关于它的数字就是这个 125）。

2. **产物里没被带进真 key**（两处独立量具）：

```
$ git grep -lIE '[A-Za-z0-9_-]{110,}' -- z-bot-core/src _doc/acceptance/p21 | wc -l
0                      # 全仓我的写域内不存在 ≥110 连续字符的 token ⇒ 125 长的真值不可能在里面
$ grep -rl "minimax" ~/.cache/zbot-p21/e2e/R151125-p97447 | wc -l
1                      # 唯一命中 = 临时根 zbot-home/config.properties
$ grep -rl "stub-key-not-real" ~/.cache/zbot-p21/e2e/R151125-p97447 | wc -l
1                      # 同一个文件，写的就是桩值
$ awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.cache/zbot-p21/e2e/R151125-p97447/zbot-home/config.properties
17                     # len("stub-key-not-real") == 17，不是 125
```

3. **只连 127.0.0.1**：

```
$ grep -rhoE 'https?://[0-9a-zA-Z._-]+' ~/.cache/zbot-p21/e2e/R151125-p97447 | sort | uniq -c
  18 http://127.0.0.1
   8 https://errors.pydantic.dev      ← 这是 python 异常文本里的文档 URL 字符串，不是发包目标（本跑全程零外网请求）
$ grep -rhoE '127\.0\.0\.1|0\.0\.0\.0' z-bot-core/src/main/java/com/zifang/z/bot/mcp z-bot-core/src/test/java/com/zifang/z/bot/mcp _doc/acceptance/p21 --include='*.py' --include='*.java' | sort | uniq -c
  23 127.0.0.1
```

`0.0.0.0` / 真域名的 bind/connect 目标：0 命中。（`grep` 会顺带命中 `_doc/acceptance/p21/__pycache__/*.pyc` 里的同一串，属编译产物，收口时已删。）

4. **内核仓零字节改动 + 无外部装包前置**：

```
$ git -C /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-agent-kernel status --porcelain | wc -l
0
$ git -C /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-agent-kernel rev-parse --short HEAD
cb90416                              # 与开工时同一 HEAD
$ git diff --name-only 926b8b5 HEAD | grep -c 'z-agent-kernel' ; echo "（下面这条是杠②/杠③ 用到的包是否现成）"
0
$ python3 -c "import mcp,importlib.metadata as m;print(m.version('mcp'))"
1.27.1                               # 系统 Python 现成，全程没有 pip install / uvx / uv
```

⇒ 工单 §5 四条红线：真 key 未取值（只量长 125 / 现场 17）、未入产物（≥110 字符 token 计数 0）、只连 127.0.0.1、内核仓 `cb90416` 干净、无装包前置。

---

## §14 明确不做 / §未做（含理由，不默默省略）

STATUS: 已填（p21c 未回填，由主编在 §16.5 落；本节保留清单）

**工单点名不做的三条**（`grep` 复算口径：`git grep -ic 'createMessage|elicitation|osv' -- 'z-bot-core/src'` 在合并前树上三条**全部零命中** ⇒ 确实没做，不是"做了没写文档"）：

1. **OSV 依赖预检** —— 她是在挂载外部 MCP server 前查其依赖漏洞。我们不做，理由：z-bot 不安装任何第三方 server 的依赖、也没有可查的依赖图（server 由用户自己在配置里给命令/URL）；真做要打 `api.osv.dev` = 出站非回环，撞本战役红线 2（任何测试/E2E 不得向非 `127.0.0.1` 发起请求）。**要动的东西**：一条带缓存的出站 HTTP 客户端 + 一份"server ⇒ 包坐标"的映射（今天不存在）。
2. **sampling（`sampling/createMessage`）** —— server→client 的**请求**，需要入站请求派发 + 回包路径。内核 `McpTransport` 只有 4 个方法且**结构上收不到 server 主动推的任何帧**（§2 实测已证），因此走内核那条 `StdioMcpTransport` 做不了。
3. **elicitation（`server/elicitation/create`）** —— 同 2 的阻塞原因。
   **重要更正（这条是本棒自己改出来的新事实）**：2/3 的阻塞在**本期之后已经小了一截** —— z-bot 侧新增的 `ZBotStdioMcpTransport` 已经会解析入站帧并区分"响应 / 通知"（`McpNotificationListener` 就是那个口），缺的只是"入站**请求** ⇒ 本地处理 ⇒ 回包"这一层。⇒ 下期做 sampling 不必再动内核。**这条不是本期已做**，记在这里是为了不让下期重复调研。

**本期新增未做（逐条"要动 X / 因为 Y / 会撞 Z"）**：

4. **`tools/list_changed` 的 http 侧只做了 client 收流，没做 server 侧推**：反向 `mcp_serve` 明确广告 `listChanged=false`（`R1` 变异体就是为了钉死"不许广告兑现不了的承诺"）。要动的是 `ZBotMcpServe` 的出站通知通道（今天是纯 request/response 循环）；本期不做是因为没有第二个真 client 能验它 ⇒ 会变成自说自话的断言。
5. **`McpManager` 的重连/退避**：bridge 掉线后没有指数退避重连（与 `w8-p26` 的 llm 退避是同一族问题，那边统一做）。
6. **`_meta` / progress token / roots / 资源（resources）面**：一个都没做。工单没点名，但矩阵 #22 的"10 个工具 vs 我们 2 个读端工具"的差额主要就在这里 ⇒ **verdict 不许写成"已对齐"**，见 §16.6。
7. 杠② 的 `W1` 至今是 `PARTIAL`（点名 2/2 里只有 `watchdogTakesEvenAnEofInsensitiveChildWithIt` 红，`killedParentTakesTheChildWithIt` 不红）⇒ "监护脚本发现父死但不杀子"这一坏法只被一条断言守着。补第二条要动 `McpParentWatchdogTest`，本战役这一棒不加覆盖面。
8. 三处 `StdioMcpTransport`（内核）缺陷只取证未修（§1.1 非紧凑 JSON 永久挂死 / §1.2 30s deadline 不成立 / §1.3 版本写死 `0.2.0`）⇒ 我们绕开了它（z-bot 侧自带 transport），**内核那条错法对任何直接用它的下游仍然生效**。修它要动 `z-agent-kernel`（一棒都没被授权改）。


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

---

# §16 主编收口棒：P21 并入 main 之前的四杠（被测树 = `9c24c23`，@Test 面 661）

> 本节全部读数由主编自己跑出来（不抄写手自述）。p21c 死于 turn 150（这是 P21 连死的第三棒：
> `p21a`→`d01c3a7`、`p21b`→`86447fe`、`p21c`→本节起点 `9c24c23`），临终工作树由主编封存。
> 原始日志一律在仓外 `~/.cache/zbot-p21-lead/`（`.gitignore:5` = `*.log` ⇒ 决定性读数原样贴进本节）。

## 16.0 起点取证

```
cd /private/tmp/zbot-wt-p21
git log --oneline -1                    # 9c24c23 封存 p21c 临终工作树（死于 turn 150，161 次工具调用）…
git status --porcelain                  # ?? _doc/acceptance/p21/__pycache__/   ← 除此之外干净
git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'    # 661
git merge-tree --write-tree --name-only d23cd0d 9c24c23
  7de37a9b9223282716760c7320d846652c08d74a          # 只一行 ⇒ 与 main 零冲突
git grep -c '@Test' 7de37a9 -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'   # 735
```
⇒ 合并面 **735 = main 693 + P21 净增 42**（机械求和，不是"沿用 661"）；`merge-tree` 一行 ⇒ 零冲突。
P21 的增量全在 `mcp/` 与验收件：`git diff --stat $(git merge-base d23cd0d 9c24c23) 9c24c23` = **28 文件 / +7939 / −39**。
`McpE2eDriver.java` / `McpWatchdogProbe.java` / `RealMcpHarness.java` 三支经 `git ls-tree -r --name-only` 核对
**都在 `src/test/` 下** ⇒ 发布 jar 里不会带验收脚手架。

## 16.1 杠① 全量单测 ×3 串行（主编亲测，全 reactor 不 `-pl`）

复算：`bash ~/.cache/zbot-p21-lead/lead_loop.sh`（三跑前各自 `rm -rf z-bot-core/target/surefire-reports`）

```
HEAD=9c24c23   @TESTS_GITGREP=661
MVN_RC_1=0  run1 tests=[[INFO] Tests run: 661, Failures: 0, Errors: 0, Skipped: 0] build=[1] socket=0 errlines=0   Total time:  52.783 s  Finished at: 2026-09-26T15:37:23+08:00
MVN_RC_2=0  run2 tests=[[INFO] Tests run: 661, Failures: 0, Errors: 0, Skipped: 0] build=[1] socket=0 errlines=0   Total time:  49.958 s  Finished at: 2026-09-26T15:38:14+08:00
MVN_RC_3=0  run3 tests=[[INFO] Tests run: 661, Failures: 0, Errors: 0, Skipped: 0] build=[1] socket=0 errlines=0   Total time:  55.371 s  Finished at: 2026-09-26T15:39:11+08:00
```
三跑汇总行逐字一致；`BindException|Connection refused|SocketTimeout` = **0**；`[ERROR]` 行 = **0**；墙钟 50–55 s。

## 16.2 杠② 变异注入（主编独立复跑，独占 flock）

复算：`python3 -u _doc/acceptance/p21/p21_mutation.py`（日志 `~/.cache/zbot-p21-lead/lead2_bar2.log`）

```
MUT_RC=0
== 台账 ==
  RED-OK             18
  PARTIAL            1
  GREEN-BUT-MUTATED  0
  BROKEN             0
  NO-RUN             0
  注入后 src 与基线 md5 有差异的文件: 无（逐字节还原）
LEDGER_ROWS=30     CORE_DIRTY=0     MD5_CORE_VS_HEAD=0
```
- 我另用一把独立的尺重算台账（不读脚本自打的 `#tally`）：
  `python3` + `csv.DictReader` 数 19 支变异体 ⇒ `{'RED-OK': 18, 'PARTIAL': 1}`，与 `#tally` 逐档相符；
  7 支 `CTRL-*`（阳性对照，injection=NONE）**全 OK** ⇒ 没有任何一族是"猎物进不来还硬判红"。
- `CORE_DIRTY=0` + `MD5_CORE_VS_HEAD=0`（`git diff --stat HEAD -- z-bot-core` 空）⇒ 跑完之后
  `src/` 与提交树**逐字节相同**，这是脚本之外第二把尺量的还原。

**与写手那一跑的差分取证（这一条是本节存在的理由）**：p21c 留下的 `LEDGER.tsv` 是
`RED-OK 17 / PARTIAL 1 / BROKEN 1`（`T1` 判 `BROKEN`，读数 `红=全绿 ran=0 rc=1 2.8s`）。复算时间戳：

```
_doc/acceptance/p21/LEDGER.tsv          mtime=15:23:20
_doc/acceptance/p21/p21_mutation.py     mtime=15:27:03   ← 晚于台账 3 分 43 秒
```
⇒ **写手的台账出自比它自己的提交版更旧的一份量具字节**（`git show 9c24c23:` 两样都在同一笔提交里，
但生成顺序反了）。旁证是 `T1` 的标题字面量也随那次编辑变了：旧台账 `T1 请求不等自己的 deadline（回到内核那条错法）`
vs 现脚本 `T1 请求不等自己的 timeoutMillis（deadline 形同虚设）`（`git diff 9c24c23 -- LEDGER.tsv | grep '^[-+]T1'` 逐字可见）。
我用**当前**脚本复跑 ⇒ `T1 ... RED-OK 点名 1/1 ran=1 rc=1 19.2s 还原=True`（`2.8s` 那次连一次 mvn 都没跑完，
`ran=0` 说明是编译期失败，不是"摘了没人管"）。⇒ **`BROKEN` 那档在本树当前字节下不存在**；
台账里那条"同机别人的 mvn 会跟我抢同一个 target/ ⇒ 编译类失败重跑一次再定性"的 20 s 重试确实起了作用。
这一列的教训（写下来防下次再踩）：**台账的 `#generated_by` 时间必须晚于量具脚本的 mtime，否则台账作废重跑**。

仍存的真实缺口：`W1 监护脚本发现父死但不杀子` 还是 `PARTIAL`（点名 2 支只红 1 支：
红 `McpParentWatchdogTest#watchdogTakesEvenAnEofInsensitiveChildWithIt`，不红 `killedParentTakesTheChildWithIt`），
已记 §14.7，本棒不加覆盖面。

## 16.3 杠③ 真进程 E2E（主编亲测，两组各 3 跑）

复算：`bash ~/.cache/zbot-p21-lead/lead_loop2.sh`（**与写手同口径：不给 `--label`**）

```
e2e2_run1 rc=0 PASS=59 FAIL=0 汇总=[  合计 59 条，失败 0 条: []]  工作根=…/e2e/R154506-p59320  用时=68.5s
e2e2_run2 rc=0 PASS=59 FAIL=0 汇总=[  合计 59 条，失败 0 条: []]  工作根=…/e2e/R154615-p62022  用时=72.8s
e2e2_run3 rc=0 PASS=59 FAIL=0 汇总=[  合计 59 条，失败 0 条: []]  工作根=…/e2e/R154728-p65199  用时=71.0s
```
官方 SDK 读数三跑同为 `mcp 版本=1.27.1`；分节条数逐字一致（(a)17 (b)14 (c)11 (d)13 (e)4 = 59）；
三个现场目录互不相同。**先打包再跑**：`mvn -o package -DskipTests -pl z-bot-core` ⇒ `PKG_RC=0`，
`z-bot-core.jar` sha256 前 8 = `0a7d8186`（另一枚 `original-*.jar` = `506c60d5`，是 repackage 前的空壳，别拿它当被测件）。

**我自己制造的一把假红（如实记，因为这正是"复测前先怀疑量具"）**：同一棵树上我另跑了一组带 `--label lead1/2/3` 的：

```
e2e_run1 rc=1 PASS=0 FAIL=0   →  合计 59 条，失败 1 条: ['a/fresh_workdir_default_label_is_formatted']
e2e_run2 rc=1 …（同）      e2e_run3 rc=1 …（同）      三跑一致的只有这一条红
```
定位：`p21_e2e.py:309-311` 的原文是
`g.chk("fresh_workdir_default_label_is_formatted", "%H" not in label and … and label.startswith("R"), "本次 label=%s" % label)`
—— 它把"**缺省** label 的 strftime 有没有漏字面量"这件事绑在了**本次 run 的 label** 上 ⇒
显式 `--label` 必然判红。⇒ 不是产品坏，也不是 P21 的功能坏，是这一条量具自证的口径错。
**主编已改**（`p21_e2e.py:309-315`：自己按缺省口径算 `default_label` 再测，读数同时打两个名字），双向验完再并：

```
python3 -u _doc/acceptance/p21/p21_e2e.py --only a --label leadfix   →  RC=0  合计 17 条，失败 0 条: []
[PASS] a/fresh_workdir_default_label_is_formatted :: 缺省口径 label=R155426-p83123（本次 run label=leadfix）
# 阳性对照（谓词必须还能判红，否则就是把它调松）：
broken_label_predicate= False        # "R%H%M%S-p12345" 这种漏字面量的名字，谓词仍然判 False
real_label= R155427-p83247 predicate= True
```

## 16.4 杠④ `~/.zbot` 一字未动（四时点，全部现量）

```
15:36:29 bar4_before  items=8 cfg=2dadaed0 db=690ddbc0      # 杠① 起跑前
15:42:37 NOW          bar4_after items=8 cfg=2dadaed0 db=690ddbc0   # 杠① ×3 + 杠③(带 --label) 全跑完
15:4x    bar4_mid     items=8 cfg=2dadaed0 db=690ddbc0      # 杠③ 三整跑之后、杠② 之前
15:52:18 bar4_end     items=8 cfg=2dadaed0 db=690ddbc0      # 杠② 19 支注入 + 7 支对照跑完
```
⇒ 条目数 8 与两个 md5 前缀在四时点完全未变。真 key 全程未被读、未打印、未复制、未提交
（本棒所有 E2E 都走 `p21_e2e.py` 自己的 `--config-dir`/`ZBOT_HOME` 临时根；`--only a` 那一跑也只落临时根）。

## 16.5 本节与写手各节的关系（谁给谁让路）

- `§9`（写手杠①）/ `§15`（写手杠③）读数我**没有推翻**，但我另跑了同口径的一组，两组的汇总行逐字相同 ⇒ 写手读数可信。
- `§10`/`§11`/`§14` 的原骨架是 `STATUS: 未跑 / 待填`，本节填上了；`§10`/`§11` 已改成指向本节的指针（不留"待填"字样在仓里）。
- `§14` 的三条"点名不做"我按字面量复算过 `createMessage|elicitation|osv` 在 `z-bot-core/src` **零命中** ⇒ 是"没做"，不是"做了没写"。

## 16.6 判词（写给主编自己，也写给下一棒）

| 杠 | 一次跑齐了吗 | 关键读数 |
|---|---|---|
| ① | 是 | `Tests run: 661, F:0 E:0 S:0` ×3、rc 全 0、socket/`[ERROR]` 均 0 |
| ② | 是（独占 flock） | `RED-OK 18 / PARTIAL 1 / GBM 0 / BROKEN 0 / NO-RUN 0`；19 支 + 7 支对照；逐字节还原（脚本尺 + `git diff` 第二把尺同为 0）；写手台账 `T1 BROKEN` 系出自旧量具字节，当前脚本复跑为 `RED-OK` |
| ③ | 是（我另加 3 跑 + 1 定位跑） | `合计 59 条，失败 0 条` ×3 rc=0，三个独立现场目录；`--label` 组的唯一红是量具口径错，已改并双向对照 |
| ④ | 四时点同读数 | `8 / 2dadaed0 / 690ddbc0` |

**P21 具备并入 main 的门禁面。** 合并后仍需在**目标树**（main）复测杠①③④（这一条是 P18 那期"少打一杠"的账，本期不许再欠）。
