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
