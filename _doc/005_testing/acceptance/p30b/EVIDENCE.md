# P30b EVIDENCE —— 入站 body 上限：门在分配之前（09-27）

闭合 `_doc/005_testing/acceptance/p30/EVIDENCE.md` §6 的 **D-P30-3**（任务 #47）。一句话结论：
四个入站面的 body 读取现在只有**一个**出口 `channel/InboundLimits.java`，上限 64 KiB，
超限在把 body 读进堆之前抛出，四面各自把它映射成 413；**尺寸门没有插到任何已有鉴权门前面**
（钉钉仍是"先 401 后 413"，飞书因验签必须覆盖原始 body 而天然是"先有界读取、后验签"）——
两种顺序分别有测试钉住，见 §6.3。

---

## §0 树身份：三根杠量的是同一份字节

`HEAD = 90f361f`。杠② / 杠③ 在 `fcbc2fc`（实现 + 冻结的预期红集）上跑，杠① 跑在两遍之上
（第一遍只多了 README 一处改动，第二遍在含全部文档笔的最终树，见 §1 末），所以"三根杠同树"这句话
不能靠叙述，得逐文件比字节：

| 被测量的源文件 | 工作树 md5 | `git show HEAD:` md5 | |
|---|---|---|---|
| `channel/InboundLimits.java` | `772c7189703c` | `772c7189703c` | SAME |
| `channel/BodyTooLargeException.java` | `882174cd7290` | `882174cd7290` | SAME |
| `channel/FeishuChannel.java` | `d5cbc5c1d483` | `d5cbc5c1d483` | SAME |
| `channel/DingTalkChannel.java` | `07c0dbbabad6` | `07c0dbbabad6` | SAME |
| `channel/WebhookChannel.java` | `8abac1b63045` | `8abac1b63045` | SAME |
| `channel/HttpChannel.java` | `1c7bbbfe75a2` | `1c7bbbfe75a2` | SAME |
| `test/.../InboundBodyLimitTest.java` | `874221851229` | `874221851229` | SAME |

第一遍杠①（06:53）跑之前工作树里唯一的改动是 `M README.md`（外加三个未跟踪的 `__pycache__`）；
第二遍（07:02，见 §1 末）之前是四处文档笔：`README.md`、`p30/EVIDENCE.md` §6 的闭合标记、
`hermes-roadmap.md` §8.14/§8.15、以及本文件。README 会不会影响测试结果不是"我觉得"：
`ReadmeClaimsTest` 只核对**定量主张**（`revision` / 内核 pin / 路由行数 / 路径数 / `/api/commands` 行数 /
子命令名单），不读我改的那两处；而全仓**运行时真的会打开 `_doc/` 文件**的测试只有四处 ——
`HttpRouteLedgerTest`（`p28/ROUTES.tsv` + `p28/EVIDENCE.md`）、`SessionsColumnAlignmentTest`
（`p15b/sessions_column_alignment.md`）、`RealMcpHarness`（`p21` 目录），都不指向 `p30b/`。
（`grep -rn "_doc/" src/test` 另有 `p16`/`p19`/`p20b`/`p25` 命中，逐条看过是 javadoc 里的出处引用，不读文件。）

**跑前的现场清理（一笔卫生账，不是产品问题）**：`ps` 探到 5 个昨天 P16/P17/P18/P23 的 E2E 遗留
gateway JVM（父进程已是 launchd，存活 11h44m–13h03m，无 python 量具持有），占着
`127.0.0.1` 的 61003/61004/61006/61007/61014/61015/61016/61017/62618/62619。全量测试里有绑固定端口的
用例 ⇒ 不清就会互造假红。`kill`（SIGTERM）后 `ps -o pid=,etime=` 输出为空 ⇒ 五个全部退出；它们无一持有
`~/.zbot`（各自的 `--config-dir` 是 `p16/out/…`、`/tmp/p17-e2e-…`、`zbot-p18-e2e-…`、
`~/.cache/zbot-p23-lead/…`）。

---

## §1 杠①：全 reactor `mvn -o test` ×3 串行

`rm -rf */target/surefire-reports` 后 `mvn -o test`（**不 `-pl`**），日志
`~/.cache/zbot-p30b/bar1_{1,2,3}.log`。三跑逐字相同的判定行（原样粘）：

```
=== bar1 run 1 start 06:53:05 other-maven-procs=0
=== bar1 run 1 rc=0
[INFO] Tests run: 1194, Failures: 0, Errors: 0, Skipped: 0
BUILD_SUCCESS_LINES=1
socket_hits=0
=== bar1 run 2 start 06:54:10 other-maven-procs=0
=== bar1 run 2 rc=0
[INFO] Tests run: 1194, Failures: 0, Errors: 0, Skipped: 0
BUILD_SUCCESS_LINES=1
socket_hits=0
=== bar1 run 3 start 06:55:15 other-maven-procs=0
=== bar1 run 3 rc=0
[INFO] Tests run: 1194, Failures: 0, Errors: 0, Skipped: 0
BUILD_SUCCESS_LINES=1
socket_hits=0
```

每跑的 reactor 摘要三模块全 SUCCESS：`z-bot … SUCCESS` / `z-bot-core … SUCCESS [01:03 min]` /
`z-bot-desktop-packager … SUCCESS`，`Total time` 01:03–01:04 min。

**规模尺三把对着读**（1194 这个数字由不得单口供）：surefire 合计 **1194**；跑完留在盘上的
111 份 `*/target/surefire-reports/*.xml` 逐份求和 **tests=1194 failures=0 errors=0 skipped=0**；
文本尺 `grep -rho '@Test' src/test` = **1197**（工作树与 `git grep -c HEAD` **同为 1197**），减去注释里
3 处 `@Test` 字样（`RawTerminalVerdictProbe:7`、`P26RetryPolicyTest:37`、`MemoryE2eDriver:16`）= **1194**。
P30 收口时是 1185，本期新增 1 个测试类 9 支 ⇒ 1194 对得上。

> 量具自己的一笔：`bar1.sh` 整体退出码是 **1**，因为收尾那条 `grep -cE "BindException|…" ` 命中 0 时
> 自己返回 1（脚本开了 `pipefail`）。构建的 rc 是逐跑那三行 `rc=0`。包装脚本的退出码不是被判据，
> 别把它读成"这跑挂了"。

**这一遍才是定稿读数**。上面那三跑之后我又改了 `README.md`（"最近一次目标树读数"那一行 + 新增的
入站 body 段落），而 `ReadmeClaimsTest` **真的会读 README** ⇒ 被测量的树变了。全量三跑在含文档笔的
最终树上复跑（`bar1f.sh` → `~/.cache/zbot-p30b/bar1f_{1,2,3}.log`），逐字相同：

```
=== bar1f run 1 start 07:02:23 other-maven-procs=0
=== bar1f run 1 rc=0
[INFO] Tests run: 1194, Failures: 0, Errors: 0, Skipped: 0
BUILD_SUCCESS_LINES=1
socket_hits=0
=== bar1f run 2 start 07:03:28 other-maven-procs=0   （同上四行逐字相同）
=== bar1f run 3 start 07:04:33 other-maven-procs=0   （同上四行逐字相同）
```

复跑（07:02:23–07:04:33）之前落定的文档笔是四处：`README.md`、`_doc/005_testing/acceptance/p30/EVIDENCE.md` §6 的闭合
标记、`hermes-roadmap.md` §8.14 的 D-P30-3 一行 + §8.15 整节、以及本文件。复跑之后**只剩本文件自己**在改
（这一节与 §4），而全仓会读 `_doc/` 的测试只有 §0 列的那四处、都不指向 `p30b/` ⇒ 不再触发第三遍。

---

## §2 杠②：具名变异 7 支，两跑（run1 6/6、run2 7/7）

量具 `_doc/005_testing/acceptance/p30b/p30b_mutation.py`（flock 互斥锁 `$(git rev-parse --git-common-dir)/zbot-mutlock`；
分母尺 `git grep -c @Test HEAD`；每支注入前 `cp` 快照、还原后 md5 对账）。日志
`~/.cache/zbot-p30b/bar2.log`（run1）与 `bar2-run2.log`（run2 = 加了 B7 之后）。

| 支 | 打在哪一层 | 裁决 | 杀手（点名/命中，ran=跑了多少支） |
|---|---|---|---|
| B1 上限放宽 1000 倍（常量层） | `InboundLimits.MAX_BODY_BYTES` | RED-OK | 1/1 ran=8 |
| B2 摘掉按字节累加的门，只留 `Content-Length` 头检 | `InboundLimits.readBodyBytes` | RED-OK | 1/1 ran=1 |
| B3 webhook 退回自己拼的无界读取（全限定名内联） | 接线层 | RED-OK | 2/2 ran=4 |
| B4 飞书少了 413 映射（掉进通用 catch ⇒ 500） | `FeishuChannel:466` | RED-OK | 1/1 ran=1 |
| B5 钉钉少了 413 映射 | `DingTalkChannel:369` | RED-OK | 1/1 ran=2 |
| B6 控制台少了 413 映射 | `HttpChannel:433` | RED-OK | 1/1 ran=1 |
| B7 机制地图：与 B2 同刀，全类九支谁红谁绿（只出报表） | `InboundLimits.readBodyBytes` | RED-OK | 1/1 ran=9 |

两跑的判定行：`RED-OK=6 / PARTIAL=0 / GREEN-BUT-MUTATED=0 / BROKEN=0 / NO-RUN=0`（run1，四族阳性对照
`ran=4/8/4/1` 全绿）与 `RED-OK=7 / …=0`（run2，对照 `ran=4/8/4/9` 全绿）。两跑收尾都自报
`注入后 src 有差异的文件: 无`、每支 `还原=True`。

**B7 是补给我自己的一句话的**：run1 的 B2 备注里我写了"四对边界用例全绿"，而那一跑其实只跑了
1 支用例（`ran=1`）—— 那句话没有量具。我没有回改台账，而是新增一支**只出报表**的臂（同 B2 的刀、
全类九支都跑），让"谁看不见这一刀"从推断变成读数：`红= InboundBodyLimitTest#chunkedOversizedBodyWithNoContentLengthIsStillCapped`
**九支里只有这一支**。所以 B2 的 expected 名单是 1 而不是 4 —— 这不是覆盖漏洞，而是**分工**：
按字节累加那道门只对"不带 `Content-Length` 的分块臂"可见，头检那道门只对声明尺寸的臂可见，
两支互补才盖住整个面（run1 台账保留为 `_doc/005_testing/acceptance/p30b/LEDGER-run1.tsv`，`LEDGER.tsv` 由脚本独占产出）。

**两支故意不注入的等价变异**（记在量具 docstring 里，不拿来凑读数）：
① 摘掉 `declaredLength(ex) > MAX_BODY_BYTES` 那半道快路径 —— 按字节累加那道还在，超尺寸请求照样 413，
行为不可区分；② 摘掉 `readBody(String)` 包装而让各面自己 `new String(readBodyBytes(ex), UTF_8)` ——
字节语义相同。"能靠一行假代码归零的计数"不当验收。

**一笔预期红集的自纠**：我在票面上把 `oversizedBodyNeverReachesTheBus` 写错了名字（真方法是
`…NeverReachesTheBus`）。它没进任何读数 —— 量具在开跑前会校验"点名的 testcase 必须真实存在"，当场拦下。

---

## §3 杠③：真进程 E2E 3 轮 ×（10 轮内臂 + 6 支全局）= 36/36

量具 `_doc/005_testing/acceptance/p30b/p30b_e2e.py`（自包含，不 import 别的战役脚本；起真 `java -jar` gateway，
四个面各自 `bind(0)` 拿端口，任一没监听即 FATAL；数据根 `~/.cache/zbot-p30b/e2e/round-<n>` +
`ZBOT_HOME` + `--config-dir`，凭据一律 `stub-key-not-real`，只碰 127.0.0.1；假 IM / 假 LLM 同 P30 那套）。
完整日志 `~/.cache/zbot-p30b/bar3-3rounds.log`，逐字汇总：

```
== 汇总 ==
PASS=36 FAIL=0 轮数=3 用时 23.3s
     · 现场: /Users/zifang/.cache/zbot-p30b/e2e ；~/.zbot 条目 before/after=8/8
```

分母对账：每轮 10 臂（F1 F2 F3 / D1 D2 / W1 W2 / H1 H2 / N1）× 3 = 30，加 `K1`（上限常量从**源码字节**
里读到且为 `65_536`）与 `H1..H5`（收尸 / 端口复扫 / `ps -o lstart` / `~/.zbot` 不变量 / 现场无 100+ 字符长串）
= **36**。

本轮要钉死的三件事，读数逐条：

- **门真的在分配之前，而不是只信头**：`F3` 手工写 `Transfer-Encoding: chunked`、请求里**一个
  `Content-Length` 都没有**、实发 **131072 字节** ⇒ `status=413 body={"ok":false,"error":"request body too large"}`
  三轮相同。
- **门不是恒关的**：`F2`/`H2` 正好 `65536` 字节 ⇒ 200；`W2` 正常尺寸 webhook 事件不但 200，marker
  还**真的进了假 LLM**；`N1` 三条超限之后正常事件仍 200 且走完总线→LLM→出站。
  计数器读法要注意：`llm_hits` 1/3/5、出站条数 2/4/6 是**三轮共用一个假进程**的累积值，不是"每轮 1/3/5 条"；
  递增本身正是"这一轮又真打通了一次"的证据。缺了这两支正臂，"四面全 413"也可以是假绿。
- **上限不许抢在鉴权门之前**：`D2` = 钉钉超限 + 不签名 ⇒ `status=401 body={"ok":false,"error":"signature mismatch"}`
  （P30 的 `verifyInbound` 仍排在 `readBody` 之前）。`F1`/`D1`/`W1` 要求超限臂**零副作用**：负向判据没有
  可等的因果信号，所以是"在有界观察窗内数那个 marker 出现没有"，而不是"等一会儿看有没有东西"。

卫生：`H1 仍在跑=[]`、`H2 残留=无`、`H3 还活着=无（3 个 pid 全部退出）`、`H5 扫了 35 份，命中=无`
（同一把尺先验 125 字符合成串命中才算有牙）。

---

## §4 杠④：`~/.zbot` 不变量

| 时点 | 条目数 | `config.properties` md5 前 8 | `state.db` md5 前 8 | `minimax.api.key` **长度** |
|---|---|---|---|---|
| 杠③ E2E 自测（`H4`，三轮跑完） | 8 | `2dadaed0` | `690ddbc0` | — |
| 杠① 第一遍三跑之后复采样（06:5x） | 8 | `2dadaed0` | `690ddbc0` | 125 |
| **最终树**三跑之后复采样（07:05:43，`bar1f` 07:02:23–07:04:33） | 8 | `2dadaed0` | `690ddbc0` | 125 |

条目清单：`.stty.bak`（未删）、`config.properties`、`cron`（仍是空目录，`ls -1 | wc -l`=0）、`memories`、
`models-cache.json`、`sessions`、`state.db`、`workspace` = **8**。真 key 全程只量长度（`awk -F= '/^minimax\.api\.key=/{print length($2)}'`），
未读、未打印、未拷贝。

---

## §5 权威出处（不拿"我觉得"当出处）

| 主张 | hermes 里的位置 |
|---|---|
| 上限 64 KiB：`_MAX_BODY = 65_536` | `plugins/platforms/wecom/callback_adapter.py:57-59`（注释就是 `Cap pre-auth request bodies`） |
| 413 排在**任何签名工作之前** | 同文件 `:147`（`# (413) before our handler — and before any signature work — runs.`）、`:294`（`web.Response(status=413, text="payload too large")`） |

z-bot 侧的落点：单一来源 `channel/InboundLimits.java`（`:21` 常量、`:27` 头检快路径、`:34-41` 按字节累加的门）；
四面对接线 `FeishuChannel:635`、`DingTalkChannel:491`、`WebhookChannel:266`、`HttpChannel:956`；
四处 413 映射 `FeishuChannel:466`、`DingTalkChannel:369`、`WebhookChannel:174`、`HttpChannel:433`；
"谁还自己读了 body"守卫 `InboundBodyLimitTest#everyInboundBodyReadGoesThroughTheSingleSource`
（全量 `channel/*.java` 扫 `getRequestBody()`，持有者集合必须**恰好等于** `{InboundLimits.java}`，
外加一支同形猎物 `PREY_INLINE_READER` 证这把尺有牙、一份已委托文件证它不冤枉人）。

**与 hermes 的一处有意不同**：她的 413 由前置中间件统一发（aiohttp），我们的四个面各自 catch ——
因为 `HttpExchange` 没有中间件位，而"各面自己拼读取"正是本期要消灭的形状；差异写在这里而不是藏着。

---

## §6 本期仍然欠的

1. **上限是拍在 64 KiB 这一个值上的，不是配置项**：想收更大的事件（飞书卡片回调带附件 JSON）要改代码重编。
   要不要开 `channel.*.max-body-bytes` 一键，本期未定 —— 定了就得同时决定"配 0 = 关掉门还是拒一切"。
2. **接线守卫的扫描面是 `channel/`，比这个窄的口径我复核过一次**：`grep -rn "getRequestBody()"` 全
   `src/main` ⇒ 命中只有 `InboundLimits.java:34`，而用 `HttpExchange` 的文件也只有 `channel/` 那五个
   ⇒ "入站 body 只有一个读取口"是全仓成立，不是目录内自洽。守卫不去扫 `ByteArrayOutputStream`
   是因为 `HttpChannel.readAll`（`:959`）确实也"自己拼缓冲区"，但它唯一的调用点 `:904` 读的是
   **classpath 里的控制台资源**（`getResourceAsStream(CONSOLE_RESOURCE)`），不是入站 body ——
   扫缓冲区会把这一处冤枉成违规。（我第一版把这条记成"上传文件走的是另一条无界读取"，那是**没读代码的推断**，
   看 `:904/:959` 上下文当场否掉，改判为"不在口径内"。）
3. **三种门序形状，各有测试钉住，别混着引用**：
   *飞书* —— 验签必须覆盖原始 body，绕不开读 ⇒ 它兑现的是"读取本身有界、413 先于验签"
   （`InboundBodyLimitTest#feishuAcceptsExactlyMaxAndRejectsOneByteOver` + E2E `F1/F3`）；
   *钉钉* —— `verifyInbound` 排在 `readBody` 之前 ⇒ 未签名的超限 body 先得 401（`bodyCapDoesNotPreemptTheSignatureGate`
   + E2E `D2`，这条反向臂管的是"上限不许抢在鉴权门前面"）；
   *webhook / 控制台* —— 这两面**没有**鉴权门，只剩尺寸门，所以正臂（`F2`/`H2` 正好上限 ⇒ 200、
   `W2` 正常尺寸真进 LLM）才是它们唯一的"门不是恒关的"证据。
4. D-P30-1（`GET /feishu/event?echostr=` 门外回显）、D-P30-2（v1 平铺 `event.content` 零读取点）
   仍**等权威出处**；真凭据零验证、p25 族预期红集复查、CI 那把尺未接 —— 与 P30 §7 同一批，没减项。
