# P30c EVIDENCE —— 飞书面 GET 门外回显拆掉 + 平铺 v1 容错划边界（09-27）

闭合 `_doc/005_testing/acceptance/p30/EVIDENCE.md` §6 记的 **D-P30-1 / D-P30-2**（roadmap §8.14 那条"等一份权威出处"，任务 #48）。两句话结论：

1. **D-P30-1 判为"形状串台"并拆掉**：`GET /feishu/event?echostr=` 那条半轴是**企微**的回调校验形状，飞书侧三份参照里一个字都没有 ⇒ 代码里删掉（连带删掉它唯一的调用者 `queryParams`），现在这一面只有 POST，GET/PUT/DELETE 一律 405 且不回显任何查询参数。
2. **D-P30-2 判为"问题本身没有出处"**：`event.content` 在 `lark_oapi` 的类型化模型和 hermes 的入站分发里都不是读取点 ⇒ **不新开读取口**，只把已有的平铺容错注明它是容错，并新增一支用例钉住"无出处的键不许产出投递"。

**出处先到、再裁定**，顺序不能反：这两条都没有用"我觉得"当依据，§5 每一行都带现场复算命令与 07:34:55 的实读结果。

---

## §0 树身份：四根杠量的是同一份字节

`HEAD = 6f26621`（"P30c 起手：拆掉飞书面 GET ?echostr= 门外回显（D-P30-1）+ 平铺 v1 容错划边界（D-P30-2）"）。杠①②③ 全部跑在这份树上，且**跑完后三个被测量的源文件与 HEAD 逐字节相同**（不是"我保证没改"，是 md5 对账）：

| 被测量的源 | 工作树 md5 | `git show HEAD:` md5 | |
|---|---|---|---|
| `channel/FeishuChannel.java` | `c2d946e70f9bd3c3f1bb7f728e257dd7` | `c2d946e70f9bd3c3f1bb7f728e257dd7` | SAME |
| `test/.../channel/FeishuChannelTest.java` | `8151c29621b4f33aa6bd36423bc2083b` | `8151c29621b4f33aa6bd36423bc2083b` | SAME |
| `_doc/005_testing/acceptance/p30c/p30c_mutation.py`（含冻结的预期红集） | `30a865455807144f65ebf012940ea7d1` | `30a865455807144f65ebf012940ea7d1` | SAME |

杠③ 的量具 `_doc/005_testing/acceptance/p30c/p30c_e2e.py`（`bf48ee6d31add6c7238f8d1483e08111`）与杠② 的产出 `LEDGER.tsv`（`31708c2f73fd416ce2dc068acb80e89e`）在跑时是**未跟踪**状态（量具边写边跑），收口时才入库 —— 这句话必须写出来，否则"三根杠同树"会被读成"四份文件早就在树里"。

**一笔我自己的破形（记在这里，别学）**：`README.md` 的最后改动是 07:28:07，早于杠① 第一跑 07:28:31 ⇒ 唯一**会被测试打开**的那份文档在三跑期间一个字没动；但 `_doc/005_testing/acceptance/p30/EVIDENCE.md`（07:29:56）和 `_doc/001_arch/hermes-roadmap.md`（07:30:16）是**在 ×3 循环中途**改的，本文件更是写在三跑之后。为什么这三笔结构上影响不到读数 —— 现测的口径（不是回忆）：

```
$ grep -rn "_doc/" z-bot-core/src/test --include='*.java' | grep -vE ":[0-9]+: *\*"
mcp/RealMcpHarness.java:37:            File direct = new File(dir, "_doc/005_testing/acceptance/p21");
mcp/RealMcpHarness.java:41:            File under = new File(dir, "z-bot-core/_doc/005_testing/acceptance/p21");
mcp/RealMcpHarness.java:46:        File guess = new File(cwd, "../_doc/005_testing/acceptance/p21").getAbsoluteFile();
mcp/RealMcpHarness.java:48:            throw new IllegalStateException("找不到 _doc/005_testing/acceptance/p21（从 " + cwd + " 往上找了一遍）"
channel/HttpRouteLedgerTest.java:123:        File committed = repoFileOrMake("_doc/005_testing/acceptance/p28/ROUTES.tsv");
channel/HttpRouteLedgerTest.java:134:                    + " —— 用 cp 覆盖 _doc/005_testing/acceptance/p28/ROUTES.tsv 后重跑");
channel/HttpRouteLedgerTest.java:145:                repoFile("_doc/005_testing/acceptance/p28/EVIDENCE.md").toPath()), StandardCharsets.UTF_8);
channel/HttpRouteLedgerTest.java:328:        File committed = repoFileOrMake("_doc/005_testing/acceptance/p28/ROUTES.tsv");
store/SessionsColumnAlignmentTest.java:47:    private static final String DOC_REL = "_doc/005_testing/acceptance/p15b/sessions_column_alignment.md";
$ grep -rn 'README\.md' z-bot-core/src/test --include='*.java' | grep -vE ":[0-9]+: *(\*|//)"
ReadmeClaimsTest.java:186 / :198 / :233        ← 运行时真会打开 README.md 的只有这一支测试类
```

全仓测试运行时打开的 `_doc/` 目标只有 `p28/ROUTES.tsv`、`p28/EVIDENCE.md`、`p15b/sessions_column_alignment.md`、`p21/` 目录四个，**没有一个指向本期改的文件**；被改的三篇（`p30/EVIDENCE.md`、`hermes-roadmap.md`、本文件）没有任何测试读它们。README 则在三跑之前就已定稿。**下期的规矩**：文档笔全部赶在杠① 之前落，别再用"读不到"替自己开脱 —— 形破了就是破了。

---

## §1 杠①：全 reactor `mvn -o test` ×3 串行（最终树）

先跑一遍 **bar1a 预跑**（唯一目的：写文档之前先量到真实总数，别把 `@Test` 个数敲进 README）。它的 mvn 自报结束时刻与从那份 log 现算的判定行：

```
[INFO] Tests run: 1195, Failures: 0, Errors: 0, Skipped: 0
bar1a BUILD_SUCCESS_LINES=1
bar1a socket_hits=0
bar1a Finished: [INFO] Finished at: 2026-09-27T07:26:52+08:00
```

（bar1a 那行 `=== bar1a start … other-maven-procs=…` 是脚本 echo 到终端、**没进 log**，所以本期不引用它的起跑时刻 —— 只引用得自 `bar1a.log` 本身的读数。）

定稿三跑 = **bar1f**，`rm -rf */target/surefire-reports` 之后 `mvn -o test`（**不带 `-pl`**，reactor 三模块全跑），逐字粘 `~/.cache/zbot-p30c/bar1f.log`：

```
=== bar1f run 1 start 07:28:31 other-maven-procs=1
=== bar1f run 1 rc=0
[INFO] Tests run: 1195, Failures: 0, Errors: 0, Skipped: 0
BUILD_SUCCESS_LINES=1
socket_hits=0
xml_files=     111
=== bar1f run 2 start 07:29:36 other-maven-procs=4   （上面四行逐字相同）
=== bar1f run 3 start 07:30:41 other-maven-procs=4   （同上）
```

三份单跑 log（`bar1f_{1,2,3}.log`）各自现算复核，`grep -c 'Tests run: 1195, Failures: 0, Errors: 0, Skipped: 0'` = **1/1/1**，`BUILD SUCCESS` = 1/1/1，socket 类命中 = 0/0/0，mvn 自报 `Finished at` = 07:29:36 / 07:30:41 / 07:31:45。

**规模尺三把对着读**（本期新增 1 支用例 `flatV1ToleranceStopsAtTheKeysAlreadyReadThere`，1194→1195，所以分母必须重算而不是沿用）：

- surefire 合计 **1195**；
- 盘上 111 份 XML 求和：`xml_runs=111 tests=1195 failures=0 errors=0 skipped=0`；
- 文本 `@Test` **1198** − 注释里的 **3** 处字样 = 1195。那 3 处逐条点名（都是"说明自己没有 @Test"或"描述 @Test 命名规则"的注释）：
  `ui/RawTerminalVerdictProbe.java:7`、`llm/P26RetryPolicyTest.java:37`、`memory/MemoryE2eDriver.java:16`。

**同机竞争**：三跑期间 `other-maven-procs=1/4/4`（另两个会话在跑 z-cache / z-mq 的 mvn）。判"不是环境红"的依据不是"我觉得没关系"，而是**三跑逐字相同 + socket_hits=0**；跑之前还探过现场：6xxxx 段无任何监听进程，常驻的只有 z-lc-admin(18090) 与一个 z-mcp jar，与本期端口不重叠。

---

## §2 杠②：具名变异 5 支，两跑

量具 `_doc/005_testing/acceptance/p30c/p30c_mutation.py`（预期红集**跑前**就写死在该文件里并随 `6f26621` 入库）。锁：`$(git rev-parse --git-common-dir)/zbot-mutlock` 上 `fcntl.flock(LOCK_EX|LOCK_NB)`，抢不到 **rc=4 退出**（不 sleep 重试、不 unlink、不杀别人的持有）；两跑是同机**串行**的第二遍。

| id | 打的是哪句话 | 点名 testcase | run1 | run2 |
|---|---|---|---|---|
| CTRL `C-GET形状` | 阳性对照（未注入） | `getEchostrIsNotAnsweredOnTheFeishuFace` | `ran=1` 全绿 | `ran=1` 全绿 |
| CTRL `C-token门序` | 阳性对照 | `urlVerificationChallengeIsNotEchoedWithoutValidToken` | `ran=1` 全绿 | `ran=1` 全绿 |
| CTRL `C-平铺读取点` | 阳性对照 | `flatV1ToleranceStopsAtTheKeysAlreadyReadThere` + `postEventWithValidTokenDeliversToBus` | `ran=2` 全绿 | `ran=2` 全绿 |
| **C1** | 把 GET `?echostr=` 门外回显**原样装回去** | `getEchostrIsNotAnsweredOnTheFeishuFace` | RED-OK | RED-OK |
| **C2** | 405 松成 200（门失效但不回显） | 同上 | RED-OK | RED-OK |
| **C3** | 摘掉平铺 v1 的 `event.text` 读取 | `postEventWithValidTokenDeliversToBus` | RED-OK | RED-OK |
| **C5** | 给**无出处**的 `event.content` 开一个读取点 | `flatV1ToleranceStopsAtTheKeysAlreadyReadThere` | RED-OK | RED-OK |
| **C4** | challenge 抢在 token 门之前回显 | `urlVerificationChallengeIsNotEchoedWithoutValidToken` | RED-OK | RED-OK |

台账两跑（首跑 `_doc/005_testing/acceptance/p30c/LEDGER-run1.tsv` @ `generated_by 2026-09-27T07:20:09+0800`，二跑入库的 `LEDGER.tsv` @ `07:21:10+0800`；run1 那份是 `cp` 自 `~/.cache/zbot-p30c/LEDGER-run1.tsv`，两边 `md5 -q` = `320b9186634bbc8c160f8ce4d3018718` 相同，脚本独占产出、没有手敲）：

```
#tally   RED-OK=5|PARTIAL=0|GREEN-BUT-MUTATED=0|BROKEN=0|NO-RUN=0
#mutants_injected   5
#suite_total_at_HEAD  1198
```

- **两跑严格对账**（把耗时列与 `generated_by` 时间戳规范化后 diff）：`diff` rc=0 ⇒ 除耗时与生成时刻之外**逐字节相同**；每支 `还原=True`，两跑收尾都自报"注入后 src 有差异的文件: 无 / `git diff --name-only`（空）"。
- `#suite_total_at_HEAD = 1198` 是脚本用 `git grep -c '@Test' HEAD -- z-bot-core/src/test` 现算的（失败即 FATAL），**不是手敲常量** —— 它和 §1 的文本尺 1198 是两条独立取数路径对上了。
- **C1 与 C2 是互补的两支，少一支就是空话**：C1 证"攻击者可控的那半轴不许留下"，C2 证"405"这句本身有牙 —— 只留 C1 的话，一个"200 + 空 body"的实现（GET 仍然有人应答）照样全绿。
- **C3 与 C5 一正一反**：C3 证明那条平铺容错**确有消费者**（否则它是死代码，注释里"两种形状都得认"就是空的）；C5 证明新那支边界用例拦得住"给无出处的键开读取点"。
- **明确不注入的两支（按未覆盖记账，不注了凑数）**：
  (a) 类 javadoc / 行内注释里那段出处对照 —— 注释不是可观察行为，测试结构上读不到它；守它的是 `FeishuChannel.java:51-57/395` 的文本与 review。
  (b) 把删掉的 `queryParams` helper 加回去 —— 它当时零调用点，加回去不改变任何响应 ⇒ 不会有用例红。这条"它确实是死的"不是推断：`grep -rn "queryParams" z-bot-core/src` 现测 = **0** 命中（全仓，含测试）。

---

## §3 杠③：真进程 E2E —— 冒烟 14/14、牙口探针 11/12（那 1 红是故意的）、正式 3 轮 30/30

量具 `_doc/005_testing/acceptance/p30c/p30c_e2e.py`：`java -jar` 起**真 gateway 进程**（`--config-dir` 指向 `~/.cache/zbot-p30c/e2e/round-*` 临时根），假飞书 IM + 假 LLM 都只绑 `127.0.0.1` 的 `bind(0)` 空闲端口；**没有**用 `com.sun.net.httpserver`（新量具禁用），假端点是 python `http.server`。

**K1 构件身份**（先证明"我量的是这个字节码"）：直接扫 jar 里 `FeishuChannel.class` 的字节，`echostr` 命中 **0**，而**同一把尺**在同一轮里三处正向对照都数得出来 —— `challenge=1`、`method not allowed=1`、`token mismatch=1`，猎物（测试类 `FeishuChannelTest.class`）`=2`。读不到字节一律 FATAL，不许 `return False`。

这一步是必需的：`src/main` 里现在仍有 **4** 处 `echostr` 字样（`:51/:53/:56/:395`，全在 javadoc 与行内注释里），注释不会进字节码 ⇒ 只有扫 `.class` 才能把"没有那个读取点了"和"那个读取点还在但没被 GET 走到"分开。

**牙口探针**（`P30C_TEETH_PROBE=1`，取变异锁、注入前先 `cp` 副本、注入 → 强制重建 → 观测 → 按字节还原 → 再强制重建真树）：

```
     · 已注入旧形状（38812 字节 → 39490 字节）
PASS P1 字节尺的活体阳性对照：注入旧形状后 jar 内生产类数得出 echostr      注入后 prod_echostr=1（真树那一跑读到的是 0）
FAIL R99/G1 GET ?echostr= ⇒ 405 且攻击者可控串不回显、零副作用            status=200 body=p30c-r99-echo-marker
PASS P2 这一杠见过红：注入旧形状后 G1 必须 FAIL                          G1 观测=FAIL（有牙）
PASS P3 同一轮里正臂仍 PASS ⇒ G1 的红不是『整个面死了』读出来的            G3 观测=PASS
     · 还原=True（md5 对账 c2d946e70f9b，副本 FeishuChannel.java.pre-probe）
PASS P4 探针还原后源文件与注入前逐字节相同                                 字节数=38812
== 探针汇总 == PASS=11/12
```

那唯一一条 FAIL **就是探针的存在理由**：它证明 G1 那条"405 + 不回显"是量出来的，不是"没人应答所以什么都不回"读出来的。还原用的是**注入前 `cp` 的副本**，不是 `git checkout`（git 基线是 HEAD，不是我开始测量那一刻）。

**正式 3 轮**（`=== bar3 3-rounds start 07:24:52 other-mvn-procs=0 ===`，pid 63457/63502/63612，端口 `bind(0)` 现取）：

```
PASS R1/G1 GET ?echostr= ⇒ 405 且攻击者可控串不回显、零副作用                  status=405 body=method not allowed
PASS R1/G2 PUT / DELETE ⇒ 一律 405（这一面唯一的入站形状是 POST）              put=405 delete=405 body=method not allowed
PASS R1/G3 POST url_verification(token 对) ⇒ 200 + 回显 challenge（门不是恒关的） status=200 body={"challenge":"p30c-r1-chal"}
PASS R1/G4 POST url_verification(token 错) ⇒ 401 且 challenge 不回显 status=401 body={"error":"token mismatch"}
PASS R1/G5 平铺 v1 event.text ⇒ 200 且 marker 真进 LLM/出站（容错是活的）     status=200 body={"ok":true} llm=1 im=2
PASS R1/G6 平铺 event.content（无出处）⇒ 200 但该 marker 一个字都不投递         status=200 body={"ok":true}
PASS R1/G7 v2 事件（sender + message.content 字符串化 JSON）⇒ 200 且投递   status=200 body={"ok":true} llm=2 im=3
PASS R1/G8 控制台 /bot/steer 不受本期改动影响 ⇒ 200                        status=200 body=已入队，将在下次对话开头并入
…（R2、R3 同八臂，只有 marker 串不同：G3 body={"challenge":"p30c-r2-chal"} / "p30c-r3-chal"）
PASS H1 我起的 JVM 全部收尸                        仍在跑=[]
PASS H2 我占过的端口全部释放（lsof 复扫）            残留=无
PASS H3 ps -o lstart 复核：没有我还活着的本次 JVM     还活着=无（3 个 pid 全部退出）
PASS H4 真数据根 ~/.zbot 未被触碰                   n=8 config=2dadaed0 state=690ddbc0
PASS H5 现场文件里没有 100+ 字符的类 key 长串         扫了 46 份，命中=无；尺的阳性对照=125 字符合成串命中

== 汇总 ==  PASS=30 FAIL=0 轮数=3 用时 18.8s
```

**分母对账**：8 臂 ×3 轮 = 24，`K1` = 1，卫生 `H1–H5` = 5 ⇒ **30**，与 `PASS=30` 一致（不是"跑了很多条"）。
**G6 的正臂是同一轮的 G5**：`event.content` 那条判的是"没投递"，而"没投递"本身没有因果信号 —— 同一轮里 `event.text` 那条必须真的投递（`llm=…`/`im=…` 计数在动），否则 G6 的绿可以是"整个面死了"读出来的。同理 **G3 是 G1/G4 的正臂**：证明 405/401 不是"这个端点根本没人接"。

---

## §4 杠④：`~/.zbot` 不变量（三个采样点 + 收口前复采）

| 采样时刻 | 来源 | 读数 |
|---|---|---|
| 07:22:44 | 杠③ 冒烟 `H4`（before/after） | `n=8 config=2dadaed0 state=690ddbc0`，条目 before/after=8/8 |
| 07:25:11 | 杠③ 正式 3 轮 `H4` | 同上，逐字相同 |
| **07:34:28** | 杠① ×3 跑完之后**另起一次**现场 `ls -1A` + `md5` | `entries=8`（`.stty.bak` `config.properties` `cron` `memories` `models-cache.json` `sessions` `state.db` `workspace`）；`cfg=2dadaed05e0cefe7a53f48baba3c403a`；`state=690ddbc0e0e35f3a9dc183302f1d5182` |

真 key 一个字都没读、没打印、没复制：`H5` 那支只扫"长度 ≥100 的类 key 串"，其阳性对照是尺自己造的 **125 字符**合成串（与 `awk -F= '/^minimax\.api\.key=/{print length($2)}'` 同值），命中数 = 0。`cron` 仍是空目录，`.stty.bak` 未被删。

**一笔本期没减的旧账**：杠② 的 `p30c_mutation.py` **自己不采样 `~/.zbot`**（`grep -c BAR4` = 0，那 1 处命中只是 docstring 里的红线声明）；p30 / p30b 两族同病。补过这件事的只有 `p27_mutation.py`（11 处命中，首尾各采一次并入台账）。⇒ 已写进 §6。

---

## §5 权威出处（09-27 07:34:55 现场复算，不靠转述）

参照物两份：`lark_oapi` **1.5.3**（`/Library/Frameworks/Python.framework/Versions/3.14/lib/python3.14/site-packages/lark_oapi`，dist-info 现读）；hermes 权威副本 `~/.hermes/hermes-agent` @ git `cbc1054e2`。

| 主张 | 复算命令 | 现场读数 |
|---|---|---|
| `echostr` **不是飞书的形状** | `grep -rn echostr plugins/platforms/feishu/ \| wc -l` | **0** |
| 飞书官方 SDK 全包也没有它 | `grep -rl echostr $LARK \| wc -l` | **0**（`lark_oapi-1.5.3.dist-info`） |
| 它是**企微**的形状，而且企微**先解密再回显** | `grep -rn echostr plugins/platforms/wecom/*.py` | 4 命中：`callback_adapter.py:274` `echostr = request.query.get("echostr", "")` → `:278` `plain = crypt.verify_url(msg_signature, timestamp, nonce, echostr)`；`wecom_crypto.py:84-85` `verify_url` 内部第一件事就是 `self.decrypt(...)` |
| challenge 必须排在 token 之后 | `sed -n '3552,3570p' plugins/platforms/feishu/adapter.py` | `:3552-3562` `hmac.compare_digest(incoming_token.encode(), …)` 不匹配就 `return web.Response(status=401, …)`；`:3568-3570` 才 `if payload.get("type") == "url_verification"`，注释原文 "Validate the token (above) before reflecting the challenge so an unauthenticated remote request cannot prove endpoint control…" |
| v2 入站分发**只读 `header.event_type`**，没有平铺分支 | `sed -n '3583,3596p' …/adapter.py` | `:3583` `event_type = str((payload.get("header") or {}).get("event_type") or "")`，其后整条 `elif` 链都按 `event_type` 分派，`data` 走 `_namespace_from_mapping(payload)` ⇒ 零处读 `payload["event"]["content"]` |
| v2 事件的类型化模型里没有 `content` | `sed -n '10,14p' $LARK/api/im/v1/model/p2_im_message_receive_v1.py` | `class P2ImMessageReceiveV1Data(object): _types = {"sender": EventSender, "message": EventMessage}` —— 只有两个键 |
| 全 feishu adapter 里 `payload.get("text")` 那唯一一处**不是入站分发** | `awk 'NR<=1104 && /^[[:space:]]{0,8}def /{l=NR;t=$0} END{print l": "t}' …/adapter.py` | `1099: def _build_media_ref_from_payload(payload, *, resource_type)` —— 出站 post 媒体文件名助手，`:1104` 在它函数体内 |
| "容错要写明它是容错"的先例 | `sed -n '458,460p' …/adapter.py` | `# receive_v1 docs say {user, bot}; accept "app" defensively.` |

一处**我们没有照抄**、而且要写明的差别：hermes 的签名校验排在 `:3572`，也就是**在 challenge 回显（`:3568`）之后**（token 门在前、验签在后）。我们的顺序更严：`encrypt-key` 配了就先验签（且 fail-closed 要求带头）→ 解密 → token 门 → 才回显 challenge（`FeishuChannel.java:402` 有界读 → `:409` 验签 → `:419` 解密 → `:432` token → `:436-439` 回显）。这不是缺陷也不是偏离，是把"未鉴权就能证明打到了你的回调地址"这条口关得更死；写清楚，免得后人以为这里在模仿谁。

---

## §6 本期仍然欠的（写在这里，别让下一个人以为已过）

1. **真凭据零验证**（P30 起就欠着，一项没减）：本期所有出站/入站证据都是"对 127.0.0.1 假端点发出的字节"。"飞书开放平台真的只用 POST"这条的依据是 SDK 与 hermes 两份**离线参照**，不是一次真握手。
2. **杠② 三族量具（p30 / p30b / p30c）不采样 `~/.zbot`**：杠④ 只在杠①③ 里有采样点，杠② 那 5 支注入的窗口是空的。做法照 `p27_mutation.py`（首尾各采一次、写进台账、条数与两个 md5 不变才继续）。
3. **64 KiB 仍是钉在代码里的常量**、`channel.*.max-body-bytes` 开不开未定（定了我还得同时裁定"配 0 = 关掉门还是拒一切"）—— 这条从 P30b §6.1 原样搬过来，未动。
4. **p25 族的预期红集**还没做"谁读这个值"的按机制复查（p27、p30 各已为此重写过一次）。
5. **CI 没接成第五把尺**：四杠是同机串行的一遍，z-mcp 的先例是"本机全绿而 CI 判 2 红"。
6. **`<revision>` 0.2.0→0.3.0 / 发 Central / 外部工程真 pull** 都在 z-bot 的 push 授权之外 ⇒ **等点头**。
7. **本节 §0 那笔形破**（在 ×3 循环中途改文档）：本期靠"哪些文件被测试运行时打开"这个现测口径证明无影响，下期改成"文档先冻结、再跑杠①"，别依赖解释。
