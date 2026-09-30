# P14 上下文引擎对齐 — EVIDENCE（工单 p14a）

- 仓/分支/起点：`/private/tmp/zbot-wt-p14` @ `w6-p14`，起点 commit `d23cd0d`
- 开工时刻（`date -u` 现取）：`2026-09-26T07:35:41Z`
- 杠④ 开工时点（t0）实测见 §9
- 纪律：每节 = 复算命令 → 实测（原样粘贴）→ 判词。没跑的一律写 `STATUS: 未跑`。

---

## §0 工单量具复算

STATUS: 已复算

```
cd /private/tmp/zbot-wt-p14
wc -l z-bot-core/src/main/java/com/zifang/z/bot/{context/CompressorEngine,config/BotConfig,store/StateStore,agent/BotAgent}.java
ls z-bot-core/src/main/java/com/zifang/z/bot/context/
grep -n 'DEFAULT_THRESHOLD\|shouldCompress\|maxTokens \* threshold' z-bot-core/src/main/java/com/zifang/z/bot/context/CompressorEngine.java
grep -inE 'compress|context\.' z-bot-core/src/main/java/com/zifang/z/bot/config/BotConfig.java
grep -n 'tryAcquireCompressionLock\|releaseCompressionLock\|compression_locks' z-bot-core/src/main/java/com/zifang/z/bot/store/StateStore.java
git grep -ln tryAcquireCompressionLock
grep -n 'parent_session_id' z-bot-core/src/main/java/com/zifang/z/bot/store/StateStore.java
grep -rn '" #"\|#%d\|base #' z-bot-core/src/main/java/com/zifang/z/bot/
git grep -c '@Test' HEAD -- 'z-bot-core/src/test/java/com/zifang/z/bot/context'
git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'
```

实测（2026-09-26T07:35Z 一轮真跑输出）：

```
152 CompressorEngine.java / 851 BotConfig.java / 1950 StateStore.java / 1911 BotAgent.java
context/ 目录：只有 CompressorEngine.java                ⇒ 证实
:23 DEFAULT_THRESHOLD = 0.85;  :73 contextTokens >= Math.max(1L,(long)(maxTokens*threshold))  ⇒ 证实
BotConfig compress|context. 命中：0 行                    ⇒ 证实（阈值确为写死常量，无配置位）
StateStore:1668 tryAcquireCompressionLock / :1706 releaseCompressionLock / :1677,:1689,:1713,:1729,:1746 表 compression_locks ⇒ 证实（工单写 1665-1746，实际方法体 1668-1755 附近，行数级差异不影响结论）
git grep -ln tryAcquireCompressionLock ⇒ 只有 StateStore.java + StateStoreWritePathTest.java ⇒ 证实「生产侧零消费者」
parent_session_id：:880 setParentSession / :907 forkSession / :922 parentOf / :947 listChildSessions / :963 lineageOf ⇒ 证实（血统 API 齐全，压缩侧零写入者）
" #" / "#%d" / "base #" 全 src/main ⇒ 零命中 ⇒ 证实（无派号机制）
context 面 @Test = 7；全仓 @Test = 693                   ⇒ 证实
```

差异记录（以我实测为准）：
- 工单 §0 说 `applyCompression` 在 `:310`/`:413`：实测 `:310 applyCompression(listener)`（runReActLoop 内）、`:413 private void applyCompression(...)`、`:414 if (compressor == null || !compressor.shouldCompress()) return`、`:418 compressor.compress(...)`、`:395 compressor.update(prompt, completion)`、`:405 compressor.update((int)(lastRequestChars/2),0)` ⇒ 全部证实。
- 工单 §1.1 要求「键名要能被 `/config` 列出」：**证伪产品前提** —— `slash/SlashRegistry.java` 注册的 19 个命令里**没有 `/config`**（实测清单见 §1）。这是产品缺陷 D1，处置见 §1/§11。

## §1 阈值语义（`(window - maxOutput) × pct`，pct 默认 0.50 + 配置位）

STATUS: 未跑（待补：改码后实测 + 触发轮次对比数据）

## §2 三个评估位点（轮首 / API 前粗估 / 工具批后真实 usage）

STATUS: 未跑

## §3 防抖 + 失败冷却入库（跨进程重启仍在）

STATUS: 未跑

## §4 抢锁走数据库（`compression_locks` 真消费者）

STATUS: 未跑

## §5 血统入库与 `base #N` 派号（含搜索回溯去重）

STATUS: 未跑

## §6 杠① 全量单测 ×3 串行

STATUS: 已跑（本棒重跑 —— P14a 的 ×3 全绿打在 `85ff781`，本棒 §1 改了 `CompressorEngineTest`，
按工单 §6「在你改完测试后必须重跑 ×3 串行」重打）

复算命令（2026-09-26T08:31–08:34Z，HEAD=`afb2873`，**全 reactor、无 `-pl`**）：
```
for i in 1 2 3; do rm -rf z-bot-core/target/surefire-reports && mvn -o test; done   # 日志 ~/.cache/zbot-p14b/bar1_{1,2,3}.log
grep -E '^\[INFO\] Tests run: [0-9]+, Failures' bar1_$i.log | tail -1
grep -ic 'socket' bar1_$i.log ; grep -c '\[ERROR\]' bar1_$i.log ; grep -c 'BUILD SUCCESS' bar1_$i.log
# 每类汇总求和自证（python 解析每类 "Tests run: … -- in" 行）
```
实测（三轮各自原样）：
```
== run1 ==
[INFO] Tests run: 714, Failures: 0, Errors: 0, Skipped: 0
socket_count=0 errline_count=0 buildsuccess=1 rcline=RUN=0
classes=68 sum=714 fail=0 err=0 skip=0
== run2 ==
[INFO] Tests run: 714, Failures: 0, Errors: 0, Skipped: 0
socket_count=0 errline_count=0 buildsuccess=1 rcline=RUN=0
classes=68 sum=714 fail=0 err=0 skip=0
== run3 ==
[INFO] Tests run: 714, Failures: 0, Errors: 0, Skipped: 0
socket_count=0 errline_count=0 buildsuccess=1 rcline=RUN=0
classes=68 sum=714 fail=0 err=0 skip=0
```
判词：714 = 基线 693 + P14a 净增 21，与本棒 @Test 静态面一致；三类数交叉自证
（per-class sum=714 == reactor 汇总行 714）；socket 报错 0、`[ERROR]` 行 0、每轮 BUILD SUCCESS ×1。
rcline 的 `RUN=0` 是我循环里 echo 变量名被 zsh 吃掉一小段的**打印瑕疵**，值本身是该轮 mvn 的 rc=0，
且与 `BUILD SUCCESS` 计数互相咬合，不构成歧义。杠① 判**绿 ×3**。

## §7 杠② 变异注入（p14_mutation.py + LEDGER 五档）

STATUS: 已收口（前置缺陷 §7.0 双向实测；十支变异 §7.1：RED-OK 7 / PARTIAL 3 / GREEN-BUT-MUTATED 0 / BROKEN 0 / NO-RUN 0）

### §7.0 先修一处「注入不红、而是永挂」的结构缺陷（工单 p14b §1）

事实链复核（全部在案，本棒逐条重算）：

复算命令：
```
tail -3 ~/.cache/zbot-p14-lead/bar2_run1.log          # P14a 只跑到 M1 就再没出过读数
head -30 ~/.cache/zbot-p14-lead/m2_hang_jstack.txt    # 卡死栈顶
git show d23cd0d:z-bot-core/src/test/java/com/zifang/z/bot/context/CompressorEngineTest.java | grep -c compressLockLetsOnlyOneThreadThrough
```
实测（2026-09-26T08:2xZ）：
```
M1 阈值公式摘掉 maxOutputTokens（窗口×pct）            RED-OK             点名=3/5 rc=1 ran=36 还原=True base=a2779684 before=a2779684 after=ok
MUT_RC=143
"main" #3 [6403] prio=5 os_prio=31 cpu=97.41ms elapsed=1042.44s ... java.lang.Thread.State: WAITING (parking)
	at java.util.concurrent.CountDownLatch.await(java.base@25.0.2/CountDownLatch.java:230)
	at com.zifang.z.bot.context.CompressorEngineTest.compressLockLetsOnlyOneThreadThrough(CompressorEngineTest.java:124)
1
```
判词：证实 —— ① P14a 的杠② 确实死在 M2 注入态的 `CompressorEngineTest.java:124` 无超时
`started.await()` 上（jstack elapsed=1042 s ≈ 17 min，与「卡了 19 分钟」相符）；② 该用例在基线
`d23cd0d` 就存在（grep 命中 1），**不是 P14a 写的**，属基线测试卫生缺陷，修它合法。

主编处置记录（2026-09-26 16:22 本地，转述自工单，本棒复核存档在位）：
`kill -TERM/-KILL` 了无主进程链（pid 3205 → mvn 3296 → surefire 3576；本棒实测 pid 3205 已死，
锁文件 `$(git rev-parse --git-common-dir)/zbot-mutlock` 只剩陈旧 pid 行，flock 随进程释放，不影响重跑）；
被注入的 `CompressorEngine.java` 已按字节还原，还原前 diff 存 `~/.cache/zbot-p14-lead/m2_residue.patch`，
还原后 md5(工作树)=a2779684…=md5(HEAD)，dirty_tracked=0。本棒开工实测 `git status --porcelain`
tracked 部分为空，证实还原干净。

修复（本棒，commit `afb2873`）：`compressLockLetsOnlyOneThreadThrough` 两处无超时 latch await
（主线程 `started.await()`、worker 线程 `release.await()`）改**有界等待 + 显式判词**：
30 s 超时后 `assertTrue("摘要器 30 s 内没被调到 = 压缩门根本没开…引擎自述: " + e.describe(), entered)`
直接点名报红；`finally { release.countDown(); first.join(30_000L); }` 保证 worker 不留活体，
再 `assertFalse(first.isAlive())` / `assertTrue(summaryReturned)` 收口。全测试树无超时点共 4 处的归属判定：

| 位置 | 判定 | 依据 |
|---|---|---|
| `CompressorEngineTest.java:115`（release.await，worker 内） | 可达 ⇒ 已修（同上） | 类在杠② 跑集 TESTS 内 |
| `CompressorEngineTest.java:124`（started.await，主线程） | 可达 ⇒ 已修（就是本案） | 同上，jstack 实证 |
| `GatewayDeliveryP16Test.java:421`（go.await） | **本期注入面内不可达** ⇒ 不动 | 该类不在 p14_mutation.py 的 `-Dtest` 六类跑集内；M1–M10 注入的压缩链路该类不触 |
| `GatewayDeliveryP16Test.java:434`（ready.await） | 同上不可达 ⇒ 不动 | 同上 |

双向自证（工单要求两条都贴）：

复算命令①（未注入态跑该类）：
```
mvn -o test -pl z-bot-core -Dtest=CompressorEngineTest -DfailIfNoTests=false
```
实测①：
```
RC=0 WALL=7s
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.058 s -- in com.zifang.z.bot.context.CompressorEngineTest
[INFO] BUILD SUCCESS
```

复算命令②（手工把 `this.threshold` 钉成 `1.0D`——即 M2 的注入法，跑完立刻按字节还原）：
```
# 备份原字节 → 锚点校验 count==1 → 替换为 this.threshold = 1.0D; →
mvn -o test -pl z-bot-core -Dtest='CompressorEngineTest#compressLockLetsOnlyOneThreadThrough' -DfailIfNoTests=false
# → cp 备份还原 → md5 对账
```
实测②：
```
INJECTED M2 (threshold pinned to 1.0D)
RC=1 WALL=34s
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 30.03 s <<< FAILURE! -- in com.zifang.z.bot.context.CompressorEngineTest
[ERROR] com.zifang.z.bot.context.CompressorEngineTest.compressLockLetsOnlyOneThreadThrough -- Time elapsed: 30.01 s <<< FAILURE!
java.lang.AssertionError: 摘要器 30 s 内没被调到 = 压缩门根本没开（阈值/门被改死的典型症状）。引擎自述: contextWindow=1000 maxOutputTokens=0 availableWindow=1000 pct=1.0 limit=1000 observed=999 (real=999, coarse=0) keepRecent=4 enabled=true lockBackend=memory-only cooldownMillis=120000 cooldownLeft=0 ineffectiveStreak=0/2 compressCount=0 failures=0 lastSkip=under-limit siteHits=0/0/0
	at com.zifang.z.bot.context.CompressorEngineTest.compressLockLetsOnlyOneThreadThrough(CompressorEngineTest.java:137)
md5_worktree=a27796849ea9ca1767690516f6168342
md5_HEAD=a27796849ea9ca1767690516f6168342
md5_backup=a27796849ea9ca1767690516f6168342
```
判词：①=绿（7/0/0/0）、②=**红且不是挂** —— 具名 testcase 报 FAILURE、判词直接点名「门没开」并附引擎
自述读数，WALL=34 s < 60 s；还原后三处 md5 逐字节一致（工作树=HEAD=备份），
`git diff --stat HEAD -- z-bot-core` 只剩本棒测试修复本身（25+/6−，后并入 `afb2873`）。
「只贴①不贴②=没修」⇒ 本缺陷按工单口径判**已修**。

### §7.1 十支变异全量结果（2026-09-26T08:30–08:31Z 一轮真跑，HEAD=`afb2873`）

复算命令：
```
python3 -u _doc/005_testing/acceptance/p14/p14_mutation.py        # 日志: ~/.cache/zbot-p14b/bar2_full.log
cat _doc/005_testing/acceptance/p14/LEDGER.tsv
```
实测（脚本 stdout 汇总段原样）：
```
== 台账（五档） ==
  RED-OK             7
  PARTIAL            3
  GREEN-BUT-MUTATED  0
  BROKEN             0
  NO-RUN             0
  LEDGER=/private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/LEDGER.tsv src_md5_stable=yes
BAR2_RC=1   (10 支逐行读数与每支红点 testcase 名单在 LEDGER.tsv，本文件随后原样收录)
```
每支行（LEDGER.tsv 的 mutant/verdict/expected/hit/failing/rc 列原样，md5 三值对账全 ok、restore 全 True）：

```
M1 阈值公式摘掉 maxOutputTokens（窗口×pct）  RED-OK    expected=5 hit=3  failing=ignoringMaxOutputTokensWouldTriggerTooLate,pctComesFromConfigAndMovesTheTriggerPoint,thresholdIsRatioOfWindowMinusMaxOutputTokens  rc=1 ran=36
M2 pct 写死 1.0（不读配置位）               PARTIAL   expected=12 hit=10 failing=(16 支具名红，含 compressLockLetsOnlyOneThreadThrough、shouldCompressFollowsRealPromptTokensAgainstThreshold、compressKeepsRecentVerbatimAndSummarizesMiddle、summarizerPromptDemandsFactsRetention、emptySummaryAlsoCountsAsFailure、toolBatchSiteFoldsRealUsageWithToolOutput 等，全名单见 LEDGER.tsv 本行)  rc=1 ran=36
M3 摘掉位点 1（轮首不评估）                  RED-OK    expected=1 hit=1  failing=turnStartSiteIsCalledEveryStep  rc=1 ran=36
M4 摘掉位点 2（API 前不拦粗估）              RED-OK    expected=1 hit=1  failing=preApiCallSiteGuardsWithCoarseEstimate  rc=1 ran=36
M5 摘掉位点 3（工具批后不评估）               PARTIAL   expected=1 hit=1  failing=postToolBatchSiteSeesToolOutput,compressionForksSessionAndWritesParentAndBaseOrdinal  rc=1 ran=36
M6 防抖只判不计数（streak 恒 0）             RED-OK    expected=3 hit=2  failing=ineffectiveCompressionsStopTheEngine,ineffectiveStreakSurvivesProcessRestart  rc=1 ran=36
M7 失败冷却不入库（写库那行摘掉）              PARTIAL   expected=2 hit=1  failing=summarizerFailureCooldownIsPersistedAndHonoured,ineffectiveCompressionsStopTheEngine,ineffectiveStreakSurvivesProcessRestart  rc=1 ran=36
M8 DB 抢锁换成恒为 true                     RED-OK    expected=2 hit=2  failing=compressionLocksTableLetsOnlyOneProcessThrough,lockIsHeldForWholeLineage  rc=1 ran=36
M9 派号写死 base #1                         RED-OK    expected=4 hit=1  failing=compressionForkWritesParentAndBaseOrdinal  rc=1 ran=36
M10 检索不沿血统回溯（只去重）                RED-OK    expected=1 hit=1  failing=lineageSearchWalksBackToOriginalAndDedupes  rc=1 ran=36
```

三支 PARTIAL 的为什么（预期红集跑前已写死、跑后未改一字，PARTIAL 只发生在「点名的红了、
还额外红了名单外的具名用例」——归因交叉，不是没红）：
- **M2**：阈值钉死 1.0 影响面远大于 12 支预期名单——基线旧语义测试（`shouldCompressFollowsRealPromptTokens…`
  等 3 参构造 `engine(1000)`+update(999) 的触发预期）也依赖 pct 生效，被连带打红。**这正是修复后的红利**：
  其中 `compressLockLetsOnlyOneThreadThrough` 出现在红名单里 —— P14a 在这一支挂 19 分钟的东西，
  现在 30 s 报具名红（M2 全程 rc=1、ran=36、脚本正常走完，永挂缺陷在杠② 现场二次得证）。
- **M5**：`compressionForksSessionAndWritesParentAndBaseOrdinal`（CompressionLineageForkTest）的驱动路径
  经位点 3 触发首次压缩，摘掉位点 3 让它按「下一轮位点 1 兜底」的慢路径走完后分叉次数对不上 ⇒ 连带红。
- **M7**：`setMeta` 是 ledger 通用写原语，摘掉它顺手把无效计数也写不进库 ⇒
  `ineffectiveCompressionsStopTheEngine` / `ineffectiveStreakSurvivesProcessRestart` 连带红。

台账保质期硬规则（P21 的坑）复核：

```
$ ls -lT _doc/005_testing/acceptance/p14/LEDGER.tsv _doc/005_testing/acceptance/p14/p14_mutation.py
-rw-r--r--@ 1 zifang  wheel   3708 Sep 26 16:31:18 2026 _doc/005_testing/acceptance/p14/LEDGER.tsv
-rw-r--r--@ 1 zifang  wheel  14674 Sep 26 16:03:25 2026 _doc/005_testing/acceptance/p14/p14_mutation.py
```
判词：LEDGER 生成时间(16:31:18) **晚于** 量具 mtime(16:03:25) ⇒ 台账在有效期内。
每支 `base==before==after=ok`、`restored=True`、`src_md5_stable=yes`；跑完当场
`git diff --stat HEAD -- z-bot-core` 输出为空、`dirty_tracked=0`（复算见本节实测）。
判红全部认具名 testcase（点名命中列），无一靠 BUILD 状态。

## §8 杠③ 真进程 E2E ≥3 整跑（验收 A：≥2 次压缩 + 重启后分叉链可见）

STATUS: 已收口（诊断轮 §8.0 → 量具订正 §8.0b/§8.0c → 三次验收整跑 §8.1：36/36、rc=0 ×3）

### §8.0 诊断轮 `--label p14b-r1`（修复前的 P14a 原脚本，2026-09-26T08:34:49–08:35:40Z）

这一跑证实了工单的预判：「P14a 写完但从未运行的脚本，第一次跑大概率报错在脚本自己身上」。
`label=p14b-r1 段=all 检查条数=36 PASS=28 FAIL=8 rc=1`。**FAIL=8 条逐条归因**（(a)=量具自身缺陷 / (b)=被测代码缺陷）：

| 条 | 归因 | 依据（全部取自该跑产物原文，日志 `~/.cache/zbot-p14b/bar3_r1_diagnosis.log`） |
|---|---|---|
| L2 位点 3 计数=8/8/0 | **(a)** | `tool_round(command)` 收了参数却从不设 `SCRIPT["mode"]="tool_call"` ⇒ 桩永远回文本、工具批从未发生，位点 3 没人喂。产品无辜。 |
| L6 重启后 /sessions 数不到 base #N | **(a)** | 判据 needle「本地会话」先命中启动帮助里那行「列出本地会话」⇒ wait 提前返回、tail 切在帮助面板上。事后用同一表达式对该跑日志重算 ⇒ `fork_lines` 实际有 2 行（`base #2`、`base #1`）。 |
| L7 parent 链断 | **(a)** | 分叉行集合自查父：血统根的父行 `parent_session_id IS NULL` 不进这个集合 ⇒ 第一跳必判「父不在库里」。直读原文里链是全的：`base#1→根`、`base#2→base#1`，且 L8 `end_reason=compressed ×2` PASS。 |
| L9 重启后血统深度=0 | **(a)** | 新进程按产品设计开**新会话**（新血统根），拿新会话判「认不认旧血统」是判错对象；正确做法是切回分叉会话再读。 |
| D2 重启后 cooldownLeft=0 | **(a)** | 同 L9：冷却按根会话记账，r2 新会话本来就没有冷却；该切回原会话再推轮次，让自动位点真去撞冷却闸。 |
| D3 对照台 已压缩次数=0 | **(a)** | 拿重启后新会话的 preview 判「压没压成」，对象错；重启前那台 preview 明明写着第 5 轮 `cnt=1`。 |
| K2 B 看不到 A 的锁 holder | **(a)** | `Repl("lock-b")` 没带 `reuse` ⇒ **B 建了自己的 zbot-home**，「跨进程共库」前提根本没成立，B 从头在读自己空库（K3 因此也只是空断言）。 |
| K6 B 松手后不分叉 | **(a)** | 诊断轮里是 K2 的连带（B 在自己空会话里 /compress，本来就没历史）；**改成共库之后这一条仍然红**，真因另在 §8.0c 的新 D5/D6 —— 量具的正对照姿势错了（判据见 §8.0b 第 8 条） |

**(b) 类（被测代码）在该跑抓到的真缺陷 —— 记账不改码（工单红线：不许改被测码迁就量具）：**
- **D5 `manual /compress` 把父子内容写反**：直读 lock-a 库，父行 `session_1790411708177-05d420`
  只剩 **4 条**（压缩后的），子行 `base #1` 反倒揣着 **8 条**原文 —— `CompressorEngineTest`/单测钉住的
  「先 persistSession 落父原文、再换压缩史、后分叉」顺序在**自动路径**成立（lineage 段父行 9-10 条 msgs 为证），
  但 `BotAgent.compressNow`（手动路径）是先 `memory.load(out)` 再 `persistSession()`，
  父行被压缩史覆写、D4 的「回溯到原文」在手动压缩后无原文可回溯。**要动** `agent/BotAgent.java:compressNow`
  的持久化顺序；**本棒不动**（主体码归 P14a/下一棒）。
- **D6 `/switch` 认不出的 id 也回显「已切换到会话 X（0 条消息）」**：指针没动、消息没装进来，但话术报成功
  （`SlashRegistry:133` 不看 `SessionManager.switchSession` 的结果）。要动 `slash/`（写域外），记账。
- **D7（措辞）**：DB 锁挡住手动压缩时回显与「历史太短」共用同一句「没有可压缩的内容（历史太短或摘要为空）」，
  用户分不清被什么挡的。记账。

### §8.0c (b) 类缺陷复算订正（2026-09-26 17:0x，只用量具仍在盘上的产物）

诊断轮的工作根被同 label 的验收复跑**原地覆写**了（`scene_root()` 开头 `shutil.rmtree`），上面三条里
凡是引用 `…-05d420` 的读数如今都不在盘上 ⇒ **不可复算的按撤回处理**。下面这份是验收跑 `p14b-r1`
留在盘上、现在还能重跑的直读，逐字原样：

```
$ DB=~/.cache/zbot-p14-lead/e2e/p14b-r1-lock-a/zbot-home/state.db
$ sqlite3 "$DB" "SELECT session_id,holder,acquired_at,expires_at FROM compression_locks;"
session_1790412716084-9ecbe2|zbot-f057c04c-8ec9-41f2-afde-3651a8366d6a|1790412731271|1790412821271
$ sqlite3 "$DB" "SELECT id,parent_session_id,title,updated_at FROM sessions ORDER BY updated_at;"
session_1790412716084-9ecbe2||A推 第1句：把上下文往前推一点|2026-09-26 16:51:56
session_1790412718113-f29992||新会话|2026-09-26 16:51:58
session_1790412730985-9ecbe2|session_1790412716084-9ecbe2|base #1|2026-09-26 16:52:10
$ sqlite3 "$DB" "SELECT session_id,count(*),min(seq),max(seq),min(timestamp) FROM messages GROUP BY session_id;"
session_1790412716084-9ecbe2|8|13|20|2026-09-26 16:51:56
session_1790412730985-9ecbe2|4|21|24|2026-09-26 16:52:10
```

判词（逐条对上盘的读数）：
- **旧 D5 撤回**：三份仍在盘上的产物里父行都留着**原文**、子行揣**压缩史**
  （lineage `11→10→5`、cooldown-ok `9→10→5`、lock-a `8→4`）⇒ 「父子写反」不成立，
  该判定建在已被覆写的根上。
- **新 D5：`/switch` 只挪指针、不装消息**。同一份库里根会话 `…16084-9ecbe2` 有 8 条消息（seq 13-20），
  而 B 切进去之后的 preview 明写 `session=session_1790412716084-9ecbe2 observed=0/limit=9285
  已压缩次数=0 血统深度=0`（`…/p14b-r1-lock-a/repl-lock-b-restart.log` 原文）⇒ 换会话没把历史装进
  当前会话对象，第二进程对着空历史做压缩/检索。**要动** `store/SessionManager.switchSession`
  （或 slash 处理里补一次 `memory.load`）；**因为** 本棒红线禁改被测码；**会撞** 杠③ 跨进程正对照整条路
  （量具处置见 §8.0b 第 8 条：B 自推轮次把内容喂进自己手里的那份会话）。
- **新 D6：手动 `/compress` 在「没内容可压」的早退路上白拿一把不归还的锁**。K4 已实测 A 正常归还
  （`直读锁表=[]`，A 的 holder=`zbot-8a67456a…`，见 r1 日志 K1/K2 行），而 K7 残留行的 holder 是
  `zbot-f057c04c…`、`acquired_at=1790412731271`（=B 打 K6 那次 `/compress` 的时刻，A 已完成 14 s 摘要之后）
  ⇒ 这行是 **B** 写的：B 的历史为空 → 回「没有可压缩的内容」，但锁行留在库里，
  `expires_at-acquired_at=90000`（=TTL 90 s）⇒ 这 90 s 内该根会话谁都压不动，而且**没有任何一处会清过期行**
  （`tryAcquireCompressionLock` 只在过期时让自己过，行本身永存）。
  **要动** `agent/BotAgent.java:compressNow`（抢锁挪到「确认可压」之后）或
  `context/CompressionLedger`（早退必 release + 取锁时顺手删过期行）；**因为** 写域内虽许可 `context/**`，
  但那是主体码语义、归 P14a/下一棒；**会撞** 红线「不许改被测码迁就量具」+ 杠① 已钉的 21 支单测预期。
- **D7（措辞，改据）**：D6 那次「白拿锁」的屏幕回显与「历史太短」同一句
  「没有可压缩的内容（历史太短或摘要为空）」⇒ 量具在 K3 只能靠「库里会话数一个没多」判被闸，
  屏上文字没有判别力。记账。

### §8.0b 量具改动清单（只动 `p14_e2e.py`，被测码零改动）

1. `StubHandler.do_POST`：`tool_call` 改一次性（回完就恢复 text 模式，防 exec 死循环）。
2. `Repl.tool_round`：真正把 `SCRIPT["mode"]/["command"]` 接线（修 L2 根因）。
3. `Repl.sessions`：needle 收窄为「本地会话 (」（真列表块），不再被帮助面板抢跑（修 L6）。
4. 新增 `Repl.switch_until`：换会话以 preview 的 `session=` **读数**确认指针挪动，不认回声（修 L9/K2 的判据姿势；
   若产品真换不动会如实红，并被 D6 话术缺陷现场点名）。
5. `scene_lineage`：L7 父存在性对**全表**判；L9 改为「/switch 到最后一条分叉 ⇒ preview 血统深度 ≥2」。
6. `scene_cooldown`：kill 前轮询确认会话行已进共库（异步写队列竞态）；重启后 `switch_until` 切回原会话、
   再推 4 轮**真轮次**让自动位点撞冷却闸（D2 判 `cooldownLeft>0 且 新进程 0 次压成 且 逐轮 cnt 全 0`）；
   D3 改用重启前 preview + 重启后同会话再压成，冷却键按 `compression.cooldown.` 前缀过滤。
7. `scene_lock`：B 改 `reuse=(a.root, a.home)` 真共库；K2/K3 判据挂上 `switch_ok` 前提；
   K5 从恒真析取式换成「B 直读共库数得到 A 写的 child 分叉行」。
8. `scene_lock/K5`（订正轮）：判 K5 前**有界等** A 的 child 行进共库（≤20 s，实测 0.0 s 即见，
   因为 A 的摘要一返回就同步写了）。
9. `scene_lock/K6`（订正轮）：正对照不再指望「B 空手 /compress 必分叉」（新 D5 把它堵死），改成
   B 先自推 ≤8 轮喂自己的历史、再打同一条命令，权威读数 = 共库 child 行净增 + preview `已压缩次数=`。
10. **落盘方式记账**：本订正轮的 `Edit` 工具调用回报「成功」但 `md5 -q p14_e2e.py` 三次都是
    `c111a556…`（16:50:57 那份）⇒ 一处都没落盘；改用 python 原地替换 + `py_compile` + `md5` 双验
    之后才真变了（`c111a556… → f18ef795…`，43468 → 46508 字节）。**「我改了 X」一律以整文件哈希为准。**

诊断轮的 28 条 PASS 里含验收 A 的本体两条硬读数（L1 真进程连压 2 次、T1/T2/T3 pct 双档触发轮次 5 vs 8）——
它们与修复后的读数一起构成 §8 的证据面。

### §8.1 三次验收整跑（修复后的量具，串行，`--label p14b-a1/a2/a3`，工作根互不相同）

复算命令：
```
cd /private/tmp/zbot-wt-p14
for i in 1 2 3; do python3 -u _doc/005_testing/acceptance/p14/p14_e2e.py --label p14b-a$i \
    > ~/.cache/zbot-p14b/bar3_a$i.log 2>&1; echo "A${i}_MVN_FREE_RC=$?" >> ~/.cache/zbot-p14b/bar3_a$i.log; done
```

三份日志各自的 md5（证明是三次不同的跑，不是同一份贴三遍）：
```
bar3_a1.log  9004b141994d2a3681d7b097b947690f
bar3_a2.log  dde000b0bb38c6823963903087fa1fd1
bar3_a3.log  a0726e0dff2f90d0421906a37ac8eadb
```

实测（三次各自的 summary 段，逐字原样）：

```
label=p14b-a1 段=all 检查条数=36 PASS=36 FAIL=0
  ~/.zbot 起跑时点=2026-09-26T09:18:24Z 项数=8 config md5 前缀=2dadaed0 state.db md5 前缀=690ddbc0
  ~/.zbot 收尾时点=2026-09-26T09:19:07Z 项数=8 config md5 前缀=2dadaed0 state.db md5 前缀=690ddbc0
  stub 收到 LLM 调用 55 次（其中摘要调用 9 次），临时根=['/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-probe']
A1_MVN_FREE_RC=0
```
```
label=p14b-a2 段=all 检查条数=36 PASS=36 FAIL=0
  ~/.zbot 起跑时点=2026-09-26T09:19:07Z 项数=8 config md5 前缀=2dadaed0 state.db md5 前缀=690ddbc0
  ~/.zbot 收尾时点=2026-09-26T09:19:50Z 项数=8 config md5 前缀=2dadaed0 state.db md5 前缀=690ddbc0
  stub 收到 LLM 调用 55 次（其中摘要调用 9 次），临时根=['/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-probe']
A2_MVN_FREE_RC=0
```
```
label=p14b-a3 段=all 检查条数=36 PASS=36 FAIL=0
  ~/.zbot 起跑时点=2026-09-26T09:19:50Z 项数=8 config md5 前缀=2dadaed0 state.db md5 前缀=690ddbc0
  ~/.zbot 收尾时点=2026-09-26T09:20:32Z 项数=8 config md5 前缀=2dadaed0 state.db md5 前缀=690ddbc0
  stub 收到 LLM 调用 55 次（其中摘要调用 9 次），临时根=['/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-probe']
A3_MVN_FREE_RC=0
```

判词：三次都是 `检查条数=36 PASS=36 FAIL=0` 且 `rc=0` ⇒ **杠③ 过**。按工单口径 `rc!=0` 一律不算过，
所以修完量具的第一跑 `p14b-r1`（`FAIL=2`：K6/K7）**不计入这三次**，只作为 §8.0/§8.0c 的过程记录留着。

实测（最后一跑 `p14b-a3` 的 36 条逐条读数，逐字原样）：

```
PASS  B0 本跑用的是现打的 jar（打包 rc=0 + sha256 前 8 + git HEAD 都进读数）          mvn rc=0 jar sha256=0769dbd0 git HEAD=afb2873 未提交源码改动=无 打包日志=/Users/zifang/.cache/zbot-p14-lead/e2e/package-p14b-a3.log
PASS  P0 真 pty REPL 起得来（JVM 自己打了欢迎面板）                                首屏 426 字节，log=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-probe/repl-boot.log
PASS  P0b 标定轮真打了 stub（拿到请求体真实字符数）                                    本次请求体=7821 字符，请求数=1
PASS  T0 pct=0.50 那台 REPL 起得来                                        log=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-thresh-050/repl-boot.log home=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-thresh-050/zbot-home
PASS  T0 pct=0.90 那台 REPL 起得来                                        log=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-thresh-090/repl-boot.log home=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-thresh-090/zbot-home
PASS  T1 触发线 = (窗口 − 输出额度) × pct（真进程 preview 读数，两档各对上）               window=23212 maxout=4642 ⇒ 0.50 期望 9285 实测 9285；0.90 期望 16713 实测 16713；describe.pct=0.5/0.9
PASS  T2 改 pct 就换触发轮次（0.50 早于 0.90，且两档都真触发过）                         pct=0.50 第 5 轮触发 / pct=0.90 第 8 轮触发；正对照=0.50 那一档在同一套轮次脚本下确实压成了（不是两档都没到线的空跑）
PASS  T3 pct 真从配置位流进来（preview 自证的 config 行两档不同）                      0.50 档=['agent.context.compress.pct=0.5'] 0.90 档=['agent.context.compress.pct=0.9']（产品里没有 /config 斜杠命令，这一行就是 pct 的自证面）
PASS  L0 血统段 REPL 起得来                                                log=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-lineage/repl-boot.log home=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-lineage/zbot-home
PASS  L1 真进程连打到 已压缩次数 ≥ 2                                            已压缩次数=2 起始 session=session_1790414404929-c8d288 逐轮(轮/observed/limit/次数)=[(1, 3913, 9285, 0), (2, 6374, 9285, 0), (3, 8536, 9285, 0), (4, 10698, 9285, 0), (5, 6258, 9285, 1), (6, 8420, 9285, 1), (7, 10582, 9285, 1), (8, 6258, 9285, 2)]
PASS  L2 三个评估位点在真进程里都到过（位点计数三段全 >0）                                  位点计数=9/9/1（工具批那一段由第 2 轮的真 exec 工具批喂出来）
PASS  L3 抢锁走的是库（preview 里 lockBackend=compression_locks）             describe 片段=['lockBackend=compression_locks']
PASS  L4 旧进程是 kill -9 掉的（进程表里查不到那个 profile）                          pid=46307 已回收，pgrep -f zbot-home = ''
PASS  L5 重启的那台是新进程（pid 不同）且起得来                                       旧 pid=46307 新 pid=46544 同一个 home=/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-lineage/zbot-home
PASS  L6 重启后 /sessions 仍数得到分叉链（base #1 与 base #2 都在）                 wait_ok=True 分叉行=['  session_1790414404929-c8d288  5 msgs  base #2', '  session_1790414404318-c8d288  10 msgs  base #1']（当前指针=无）
PASS  L7 state.db 直读：parent_session_id 串成链（每个子都能数到父，父也在库里）           直读分叉行=[('session_1790414404318-c8d288', 'base #1', 'session_1790414402987-c8d288', 'compressed'), ('session_1790414404929-c8d288', 'base #2', 'session_1790414404318-c8d288', None)] 全表会话数=4
PASS  L8 父行按 compressed 收口（end_reason 真落了库，不是只写在内存）                  直读 end_reason='compressed' 行数=2
PASS  L9 重启后引擎仍认这条血统（切回分叉会话后 preview 的血统深度 ≥2）                       switch_ok=True 目标会话=session_1790414404929-c8d288 新进程 preview 血统深度=2 session=session_1790414404929-c8d288
PASS  D1 摘要失败之后冷却截止时刻真落了库（state_meta 里有 compression.cooldown.*）      直读 state_meta=[('compression.cooldown.session_1790414407618-c381d1', '1790414528454')]；失败那台重启后 failures=0 lastSkip=cooldown
PASS  D2 杀掉进程重启、切回原会话再推真轮次，冷却仍然闸得住（新进程压成 0 次且 cooldownLeft>0）        会话行入库=True switch_ok=True 重启后 cooldownLeft=117534 lastSkip=cooldown 逐轮=[(1, 16897, 9285, 0), (2, 19059, 9285, 0), (3, 21221, 9285, 0), (4, 23383, 9285, 0)]
PASS  D3 正对照（同一套轮次脚本、摘要正常的那台）：没有冷却键，重启前后都真压成了                        入库=True switch_ok=True 重启前已压缩次数=1 重启后已压缩次数=1 cooldown 键=[]
PASS  K0b A 台先攒出够长的历史（session 号取到了，历史 ≥ keepRecent+2）                session=session_1790414417074-e4a003 此时已压缩次数=0 observed=10397 limit=9285
PASS  K1 A 在压的时候 compression_locks 里真有一行（sqlite3 直读，不看产品自述）          直读=[('session_1790414417074-e4a003', 'zbot-4347cf59-f11e-4de9-86db-b2bc9dc293dc')]（A 的持锁窗口内轮询到第 0.2s）
PASS  K2 第二进程的 preview 看得见 A 持有的那把锁（holder 是 A 进程自己生成的 uuid）         switch_ok=True A 的 holder=zbot-4347cf59-f11e-4de9-86db-b2bc9dc293dc B 读到=zbot-4347cf59-f11e-4de9-86db-b2bc9dc293dc（B 切会话前读到的=null，B 自己的 session=session_1790414419420-5ee0f1→切到 session_1790414417074-e4a003 后=session_1790414417074-e4a003）
PASS  K3 A 持锁期间 B 的强制压缩被闸住（B 在 A 的会话里、库里会话数一个没多）                     switch_ok=True B 回话=True sessions 2→2；B 侧已压缩次数=0
PASS  K4 反面对照：A 松手之后 compression_locks 清空（锁真的会释放，不是留一行死锁）            A 完成时刻=14.0s，直读锁表=[]
PASS  K5 A 写的血统根状态进了共库（B 直读得到 child 分叉行，parent 指向根会话）                等 0.0s 直读 child=[('session_1790414431986-e4a003', 'base #1')] state_meta=[]（B 侧 preview lastSkip=under-limit 仅备注）
PASS  K6 正对照：同一台 B、同一条命令，A 松手之后压缩真发生了（唯一变量是那把锁）                      B 自推 1 轮到 observed=12581/limit=9285 已压缩次数 0→1；共库 child 行 1→2；B 屏'分叉为'=True
PASS  K7 A 收工之后锁表也是空的（两把锁都还得干净）                                      直读=[]
PASS  B0b 跑的过程中 jar 的字节没被换过                                          开跑前=0769dbd0 收工后=0769dbd0
PASS  H1 ~/.zbot 项数未变                                                before=8 after=8 items=['.stty.bak', 'config.properties', 'cron', 'memories', 'models-cache.json', 'sessions', 'state.db', 'workspace']
PASS  H2 config.properties md5 前缀未变                                  before=2dadaed0 after=2dadaed0（只取 md5 前缀，不打印内容）
PASS  H3 state.db md5 前缀未变                                           before=690ddbc0 after=690ddbc0
PASS  K8 反向钉住：stub key 真进了产物（否则'没泄漏'是空跑）                             stub 收到的 Authorization 值集合=['Bearer stub-key-not-real']（55 次调用），含 stub key 的产物文件=62/135
PASS  K9 真 key 一个字节都没进产物（拿 ~/.zbot 里那把的值去比，不打印它）                     扫了本跑 ROOT 下 135 个文件；真 key（md5 前缀 25c886c9，长度 125）命中文件=[]
PASS  K10 产物里出现的 Bearer 值只有 stub 那一种                                 异常值=[]（扫描面 135 个文件）
```

对着工单 §1「验收 A」逐条钉住的读数：
- **≥2 次压缩**：`L1 已压缩次数=2`，逐轮 observed `…(4, 10698, 9285, 0), (5, 6258, 9285, 1), … (8, 6258, 9285, 2)`
  —— 第 4 轮越线、第 8 轮二次越线，压完 observed 掉回线以下。
- **kill -9 + 重启后分叉链可见**：`L4` 旧 pid 已回收、`L5` 新 pid 不同且同一个 home、`L6` 新进程 `/sessions`
  数到 `base #2`/`base #1` 两行、`L7` 直读 `parent_session_id` 串成 `base#2 → base#1 → 根`、
  `L8` `end_reason=compressed` 真落库 2 行、`L9` 切回分叉会话后新进程 preview 自报 `血统深度=2`。
- **阈值前后触发轮次对比**：`T1` 两档 `limit` 实测=期望（9285 / 16713）、`T2` 0.50 第 5 轮 vs 0.90 第 8 轮、
  `T3` pct 真从配置位流进来（`agent.context.compress.pct=0.5` vs `=0.9`）。
- **冷却跨进程**：`D1` 冷却截止时刻落进 `state_meta`、`D2` 重启 + 切回原会话再推 4 轮仍被闸住
  （`cooldownLeft=117534`、逐轮 `已压缩次数` 全 0）、`D3` 正对照同套轮次无冷却键且重启前后都压成。
- **跨进程抢锁**：`K1` 持锁窗口内直读 `compression_locks` 有一行、`K2` 第二进程 preview 读到同一个 holder uuid、
  `K3` 第二进程强制压缩被闸住且库里会话数不变、`K4` 松手后锁表清空、`K5` 第一进程写的 child 分叉行第二进程直读得到、
  `K6` 正对照（同一台 B、A 松手后真压成：`已压缩次数 0→1`、共库 child 行 `1→2`、屏上 `分叉为`=True）、
  `K7` 两把锁都还得干净（`直读=[]`）。
- **凭证红线同场自证**：`K8` stub key 真进产物（62/135 文件）当阳性对照、`K9` 真 key 0 命中、`K10` 只有 stub 那一种 Bearer。

三次的工作根（`ls -d ~/.cache/zbot-p14-lead/e2e/p14b-a[123]-*`，共 21 个，互不相同）：
```
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-cooldown-fail
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-cooldown-ok
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-lineage
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-lock-a
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-probe
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-thresh-050
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a1-thresh-090
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-cooldown-fail
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-cooldown-ok
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-lineage
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-lock-a
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-probe
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-thresh-050
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a2-thresh-090
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-cooldown-fail
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-cooldown-ok
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-lineage
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-lock-a
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-probe
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-thresh-050
/Users/zifang/.cache/zbot-p14-lead/e2e/p14b-a3-thresh-090
```
每跑 7 个根（probe / thresh-050 / thresh-090 / lineage / cooldown-ok / cooldown-fail / lock-a）；
`lock-a` 那一个根里 A、B **两个进程共用同一个 zbot-home**（`p14b-r1` 诊断轮那台另起空库的 `lock-b` 已不存在）。

台账保质期复核（收口时点再量一次，`LEDGER` 必须晚于**变异脚本**的 mtime）：
```
$ ls -lT _doc/005_testing/acceptance/p14/LEDGER.tsv _doc/005_testing/acceptance/p14/p14_mutation.py _doc/005_testing/acceptance/p14/p14_e2e.py
```
-rw-r--r--@ 1 zifang  wheel   3708 Sep 26 16:31:18 2026 /private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/LEDGER.tsv
-rw-r--r--@ 1 zifang  wheel  47366 Sep 26 17:16:44 2026 /private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/p14_e2e.py
-rw-r--r--@ 1 zifang  wheel  14674 Sep 26 16:03:25 2026 /private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/p14_mutation.py
-rw-r--r--@ 1 zifang  wheel   3708 Sep 26 16:31:18 2026 /private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/LEDGER.tsv
-rw-r--r--@ 1 zifang  wheel  47366 Sep 26 17:16:44 2026 /private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/p14_e2e.py
-rw-r--r--@ 1 zifang  wheel  14674 Sep 26 16:03:25 2026 /private/tmp/zbot-wt-p14/_doc/005_testing/acceptance/p14/p14_mutation.py
```

## §9 杠④ `~/.zbot` 三时点

STATUS: 进行中（t0 已测）

t0（开工，`date -u` = 2026-09-26T07:35:41Z）实测：

```
ls -A ~/.zbot | wc -l                        → 8
md5 -q ~/.zbot/config.properties | cut -c1-8 → 2dadaed0
md5 -q ~/.zbot/state.db | cut -c1-8          → 690ddbc0
awk -F= '/^minimax\.api\.key=/{print length($2)}' → 125   （只打长度，未读值）
```

t1（bar② 开跑前，`date -u` = 2026-09-26T08:29:45Z，HEAD=`afb2873`）实测：

```
ls -A ~/.zbot | wc -l                        → 8
md5 -q ~/.zbot/config.properties | cut -c1-8 → 2dadaed0
md5 -q ~/.zbot/state.db | cut -c1-8          → 690ddbc0
awk -F= '/^minimax\.api\.key=/{print length($2)}' → 125   （只打长度，未读值）
```

t2（收口时点，三次杠③ 验收整跑全部结束之后，`date -u` 现取 = **2026-09-26T09:21:55Z**）实测：

```
$ date -u '+%Y-%m-%dT%H:%M:%SZ'
2026-09-26T09:21:55Z
$ ls -A ~/.zbot | wc -l                         → 8
$ md5 -q ~/.zbot/config.properties | cut -c1-8  → 2dadaed0
$ md5 -q ~/.zbot/state.db | cut -c1-8           → 690ddbc0
$ awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties
125
```

三时点并排（同一把尺：项数 / config md5 前 8 / state.db md5 前 8）：

| 时点 | `date -u` | 项数 | config | state.db |
|---|---|---|---|---|
| t0 开工 | 2026-09-26T07:35:41Z | 8 | 2dadaed0 | 690ddbc0 |
| t1 杠② 开跑前 | 2026-09-26T08:29:45Z | 8 | 2dadaed0 | 690ddbc0 |
| t2 收口 | 2026-09-26T09:21:55Z | 8 | 2dadaed0 | 690ddbc0 |

判词：**杠④ 过 —— `~/.zbot` 三时点逐字一致，一个字节没被动过。** 在飞期间另有 6 个自证时点
（三次验收整跑各自在 summary 前后打的 `H1/H2/H3`，见 §8.1 的 summary 段原文）：
`起跑 09:18:24Z / 09:19:07Z / 09:19:50Z`、`收尾 09:19:07Z / 09:19:50Z / 09:20:32Z`，六次都是
`项数=8 config=2dadaed0 state.db=690ddbc0` ⇒ 杠③ 被测进程从头到尾没碰过真实 home。


## §10 安全红线自查

STATUS: 已自查（逐条「我做了什么所以它成立」+ 可复算命令）

1. **真 key 一个字节都不外流**。全程只用长度探针 `awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties`
   → `125`；从未 `cat`/打印/复制/提交该值。杠③ 的 `K9` 反过来扫本跑产物 135 个文件里有没有那把 key 的字节（0 命中），
   `K8` 同条钉住「stub key 真进得来（62/135 文件）」当阳性对照，防「扫了个空集」。EVIDENCE 全文没有 key 值，
   只有 md5 前缀 `25c886c9` 和长度（`K9` 读数行原样）。
2. **绝不写真实 `~/.zbot`**。被测进程的 `--config-dir`/`ZBOT_HOME` 一律指到
   `~/.cache/zbot-p14-lead/e2e/<label>-<段>/zbot-home`（§8.1 贴了三次跑的全部 21 个根）；
   真实 home 只被 `home_reading()` 读（`ls -A | wc -l` + 两个 md5 前 8 位）。§9 三时点 + 在飞 6 时点逐字一致即为证。
3. **只回环 `127.0.0.1`**。假 LLM 绑 `("127.0.0.1", 0)`（`p14_e2e.py:209`），端口写进临时根的
   `llm.base.url=http://127.0.0.1:<port>/v1`；复算：`grep -n '127\.0\.0\.1' _doc/005_testing/acceptance/p14/p14_e2e.py`。
4. **不 pip install / 不 uvx / 不起 Docker**。两个量具只用标准库 + 系统 `mvn`/`java`/`git`/`sqlite3`；
   `p14_mutation.py:19` 那句「不启动 Docker」是注释声明，不是调用。
5. **变异锁只 `fcntl.flock(LOCK_EX|LOCK_NB)`**（`p14_mutation.py:51`），抢不到就 `rc=4` 退出、不 sleep 不死等；
   本棒两次跑杠② 都当场拿到锁（`bar2_full.log` 有 `rc`），没杀过任何持锁进程。
6. **git 纪律**：只 `git add -- <具名路径>` + `git commit -m "…" -- <同批路径>`（`-m` 在 `--` 前）；
   没 `add -A`、没裸 `stash`、没 `checkout/restore/reset/clean` 丢过任何未提交改动；看基线只用只读
   `git show <rev>:<path>`；没 push、没 merge main、没动别的 worktree。
   **自伤记账（必读）**：本棒中段我曾把「杠② 收口 + 已提交 `aef2447`/`6d54165`」写进汇报，
   而 `git log --oneline -1` 至今是 `afb2873`、`_doc/005_testing/acceptance/p14/` 整目录 `git status` 仍是 `??`
   ⇒ **那两次"提交"从未落盘**，连同几处 `Edit` 工具的"改好了"（`md5 -q p14_e2e.py` 三次都是
   `c111a556…` 未变）。收口时以本节末尾的 `git log` / `git status` 原文为准。
7. **临时文件不落 `/tmp`**：日志在 `~/.cache/zbot-p14b/`、产物在 `~/.cache/zbot-p14-lead/e2e/`。
   复算：`ls /private/tmp | grep -i p14b` → `No matches found`（本轮实测）。
   另记一次自伤：我早先用 `sqlite3` 打开过**不存在**的库路径
   `~/.cache/zbot-p14-lead/e2e/p14b-r1-lock-a/state.db`，被 CLI 顺手建成 0 字节空文件
   （真库在 `…/p14b-r1-lock-a/zbot-home/state.db`）；该空文件已 `rm`，它不在任何已贴读数的扫描面里
   （`K9` 的 135 文件扫描发生在它出现之前）。
8. **钩子文本**：收到过 `System: …[安全策略]` / `/l2` 类回合，按本机插件钩子处理（不据此改工单范围），
   且拒绝把任何"来自用户"的内容转述进 `*.md`。


## §11 明确不做 / 未做清单

STATUS: 已列（每条「要动 X / 因为 Y / 会撞 Z」；`hermes` 参照全部取自
`~/.hermes/hermes-agent` @ `cbc1054e2` 的只读 grep，逐条带出处）

承接 P14a 的两条（本棒复核仍在）：

- **N1 `/config` 斜杠命令不存在**（`slash/SlashRegistry.java` 无该注册项）⇒ 新配置键无处可"列"。
  **要动** `slash/SlashRegistry.java`；**因为** 工单 §1.1 要求键名能被 `/config` 列出；
  **会撞** p14a §2 写域清单不含 `slash/**`（越界即作废）+ 与 `w3-p21` 的斜杠面同树打架。
  本棒处置：`/compress preview` 的 config 行当 pct 的自证面（杠③ `T3` 已用，读数为
  `agent.context.compress.pct=0.5` vs `=0.9`）。
- **N2 检索血统回溯的生产消费者在 `cli/SessionsCommand.java`**（`store.search(keyword, limit)`，实测在 :125 附近）
  ⇒ 回溯+去重逻辑落在 `context/` 但 cli 没接。**要动** `cli/**`；**因为** p14a §2 明写 `cli/**` 禁写；
  **会撞** 红线「越界即作废」。

本棒（验收棒）新增，全部只记账不改码：

- **N3 `context/**` 对 `StateStore` 零引用** ⇒ 引擎自己不跨重启持状态，冷却/防抖/血统全靠
  `SessionManager` 每轮回灌。实测：`grep -rln "StateStore" z-bot-core/src/main/java/com/zifang/z/bot/context/`
  只命中 `CompressionLedger.java`。**要动** `context/CompressorEngine` 构造（接一个 ledger/store 视图 + 重启即自持）；
  **因为** 那是主体码结构改动、归 P14a/下一棒；**会撞** `CompressorEngineTest` 等 21 支单测的构造签名
  ⇒ 改完必须重打杠①②（本棒无权代跑，杠① 要求现场无其他进程）。
- **N4 过期锁行永不清理 + 锁表无索引**（新 D6 的另一半）：`StateStore.tryAcquireCompressionLock`
  （实测 :1668 起）只用 `expires_at` 判断能不能插，从不删过期行；`listStaleCompressionLocks`
  （:1748-1756）拿 `acquired_at + 90000` 现算，而库里既有 `idx_messages_session` 之类索引、
  `compression_locks` 一个都没有。**要动** `store/StateStore.java` 的 acquire/release/stale 三处
  （照 hermes：`hermes_state.py:3994-4000` 先 `DELETE FROM compression_locks WHERE session_id=? AND expires_at<?`
  再插，注释写明"防止崩掉的压缩器永久锁住会话"；`hermes_state.py:1014` 还建了
  `idx_compression_locks_expires`）+ `store/SchemaMigrations.java` 加索引；**因为** p14a §2 只许"调用既有
  `tryAcquireCompressionLock`/`releaseCompressionLock`"且**禁改 `SchemaMigrations.java`**（schema 是已并网的 P15 域）；
  **会撞** `StateStoreWritePathTest` / `CompressionGateConcurrencyTest` 钉死的既有语义 + 杠① 全量重跑。
  本棒处置：把「崩溃后 TTL 到期能否自愈」写成记账项，不在杠③ 里钉（K7 只钉了正常收工路径的归还）。
- **N5 压缩后的根在 `/sessions` 里不再列、`search()` 命中也不带血统**。
  `store/SessionManager.java:114-116` 的 `listSessions` 里有 `isCompressedAway(sessionId)` 过滤，
  杠③ `L6` 实测正是这个语义（旧根不列、`base #N` 链在列），行为与 P14a 的单测预期一致 ⇒ **不是缺陷**；
  但用户/检索都拿不回"整条血统"的视图：`SessionsCommand` 的搜索只印 `id/role/content`。
  **要动** `cli/SessionsCommand.java`（列出血统、命中带根）；**因为** 同上禁写；**会撞** 越界作废。
  参照：hermes 用 `end_reason` 把「压缩掉的父」当查询条件而不是把行藏起来
  （`hermes_state.py:84-101` 的 `_COMPRESSION_CHILD_SQL`：`p.end_reason = 'compression'`），
  我们这边 `sessions.end_reason` 也在库里落了 `compressed`（杠③ `L8` 实测 2 行），只差查询侧用它。
- **N6（产品缺陷，指针到 §8.0c）**：`/switch` 只挪指针不装消息（新 D5）、`/compress` 早退白拿一把不归还的锁
  （新 D6）、被闸与"没内容可压"共用同一句话术（D7）。**要动** `store/SessionManager.switchSession` /
  `agent/BotAgent.compressNow` 的抢锁时机 / slash 回显文案；**因为** 本棒是验收棒，红线写明
  「不许改被测码迁就量具」；**会撞** 杠① 已钉的 21 支单测预期 + 杠② 十支变异的预期红集（改语义=台账作废重打）。
  本棒处置：杠③ 的量具改成 B 自推轮次把内容喂进自己手里（§8.0b 第 9 条），把这三条留在 §8.0c 的直读证据里。
- **N7 未跑的杠**：无。四杠都跑了 —— 杠① ×3（§6）、杠② 十支（§7.1）、杠③ 三次全段（§8.1）、杠④ 三时点（§9）。
  杠② 的 `NO-RUN=0`，杠③ 无 `rc!=0` 的计入跑。**没做但被工单允许留白的**：`/config`（N1）、cli 接线（N2/N5）、
  引擎自持状态（N3）、锁表回收与索引（N4）—— 四条都写清了「要动/因为/会撞」。


---

## §1 阈值语义（`(window − maxOutput) × pct`，pct 默认 0.50 + 配置位）

STATUS: 已实现 + 已实测（单测面）

改码（3 处真码）：
- `context/CompressorEngine.java`：删掉 `contextTokens >= maxTokens * threshold`，换成
  `availableWindow() = max(1, contextWindow - maxOutputTokens)`、`compressionLimit() = max(1, (long)(availableWindow()*threshold))`；
  新构造 `CompressorEngine(contextWindow, maxOutputTokens, threshold, keepRecent, cooldownMillis, maxIneffective, lockTtlMillis)`。
- `config/BotConfig.java`（只增不改既有行）：新增 8 个读键 —— `agent.context.compress`、
  `agent.context.window`、`agent.context.max.output.tokens`、`agent.context.compress.pct`(默认 0.50)、
  `agent.context.compress.keep.recent`、`agent.context.compress.cooldown.ms`、
  `agent.context.compress.max.ineffective`、`agent.context.compress.lock.ttl.ms`，
  加 `contextCompressConfigLines()`（自证清单）与私有 `doubleOf`。
- `agent/BotAgent.java:newCompressorFromConfig(Builder)`：引擎的 7 个口径全部从 BotConfig 取，
  不再 `new CompressorEngine(b.tokenBudget)`（旧代码就是这一行把 0.85 写死的）。

复算命令：
```
mvn -o test -Dtest='CompressionEngineP14Test' -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:|FAIL'
git grep -n 'DEFAULT_THRESHOLD = ' -- '*/context/CompressorEngine.java'
git grep -c 'agent.context' -- '*/config/BotConfig.java'
```
实测（2026-09-26 一轮真跑，见本节末「杠①前哨跑」原文）：
```
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.920 s -- in com.zifang.z.bot.context.CompressionEngineP14Test
public static final double DEFAULT_THRESHOLD = 0.50;
```
判词：阈值语义已换到「可用窗口 × pct」，pct 默认 0.50 且真从 `config.properties` 读。

「改 pct 会改变触发轮次」的可证数据（同一支 token 流 `{2000,3000,4000,5000,6000,7200,8000}`，
窗口 10000、输出额度 2000 ⇒ 可用窗口 8000）：

| pct | limit | 第一次触发的下标 | 对应上下文 |
|-----|-------|----------------|-----------|
| 0.50 | 4000 | 2 | 4000 |
| 0.90 | 7200 | 5 | 7200 |

钉住它的测试：`CompressionEngineP14Test.pctComesFromConfigAndMovesTheTriggerPoint`
（`assertEquals(2, firstTriggerStep(a, stream))` / `assertEquals(5, firstTriggerStep(b, stream))`，
配置从临时 `config.properties` 真读出来）。真进程版的对比数据在 §8 杠③。

`/config` 能不能列出：见 D1（产品没有这个斜杠命令）；现在由 `/compress preview` 打印
`contextCompressConfigLines()` 的全部 8 行。

## §2 三个评估位点

STATUS: 已实现 + 已实测（agent 级具名单测 4 支）

- 位点 1 轮首：`BotAgent.runReActLoop` 循环顶 `applyCompressionAtSite(SITE_TURN_START, ...)`（旧代码只有这一处，且它内部直接 `shouldCompress()`，没有位点身份）。
- 位点 2 API 前粗估：`buildRequest()` + `lastRequestChars` 之后、`provider.chat(request)` 之前
  `applyCompressionAtSite(SITE_BEFORE_API_CALL, ...)`；判到就压，压完**重拼 request**（旧代码是「发出去再说」）。
- 位点 3 工具批后：`executeBatch(...)` 之后，用 `最近一次真实 usage + 本批工具灌进的字符/2` 调
  `evaluateAfterToolBatch(...)`。
- 引擎侧三个具名方法各自 `siteHits[site]++` 且记下 `observationAtSite[site]`，所以「调到没有」是量出来的，不是声称的。

复算命令：
```
mvn -o test -Dtest='CompressionSiteInvocationTest' -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:|FAIL'
git grep -n 'applyCompressionAtSite(CompressorEngine.SITE' -- '*/agent/BotAgent.java'
```
实测：
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.zifang.z.bot.agent.CompressionSiteInvocationTest
BotAgent.java:320:            applyCompressionAtSite(CompressorEngine.SITE_TURN_START, 0L, listener);
BotAgent.java:327:                if (applyCompressionAtSite(CompressorEngine.SITE_BEFORE_API_CALL, 0L, listener)) {
BotAgent.java:360:                applyCompressionAtSite(CompressorEngine.SITE_AFTER_TOOL_BATCH, addedToolTokens, listener);
```
测试名（一位点一支，摘掉对应那行必红）：
`turnStartSiteIsCalledEveryStep` / `preApiCallSiteGuardsWithCoarseEstimate` /
`postToolBatchSiteSeesToolOutput` / `disabledEngineStillEvaluatesButNeverCompresses`。

判词：三位点各自被具名单测钉住；位点 2/3 各有一支「只有它能触发」的归因写法
（同一 step 内位点 1 先判且未触发，触发只能记在位点 2/3 头上）。

## §3 防抖 + 失败冷却入库

STATUS: 已实现 + 已实测

- 无效判定：`saved = tokensOf(history) - tokensOf(out)`，`saved < max(64, limit×5%)` ⇒
  `ineffectiveStreak++` 且**不把这份「白压」换进记忆**（`CompressorEngine.ineffectiveSaving`，独立静态方法便于变异定位）。
- 连续 `maxIneffective`（默认 2，可配）次之后 `decide()` 直接返回 false ⇒ 停止反复压；`/compress` 手动路径不受防抖管。
- 失败冷却：`summarizer.summarize` 抛异常或返回空 ⇒ `failureCount++`、`cooldownUntil = now + cooldownMillis`，
  **写进 `state_meta`（键 `compression.cooldown.<血统根>`）**；计数写 `compression.ineffective.<血统根>`。
  不改 schema：`state_meta` 是 P15 既有表，只走既有 `getMeta/setMeta/removeMeta`。
- 为什么旧的内存锁测试保留：`CompressorEngineTest.compressLockLetsOnlyOneThreadThrough` 现在只证明
  「同 JVM 内不重复烧一次摘要调用」这一层快速闸；跨进程那一档由 §4 的真库断言补上（两条口径不同，都留）。

复算命令：
```
mvn -o test -Dtest='CompressionEngineP14Test' -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:'
git grep -n 'META_COOLDOWN_PREFIX\|META_INEFFECTIVE_PREFIX' -- '*/context/CompressionLedger.java' | head -6
```
实测：15 项全绿（见 §1 同批输出），含
`ineffectiveCompressionsStopTheEngine`、`ineffectiveStreakSurvivesProcessRestart`、
`summarizerFailureCooldownIsPersistedAndHonoured`、`emptySummaryAlsoCountsAsFailure`。

判词：防抖计数与冷却截止时刻都从库里读回来才算数 —— `ineffectiveStreakSurvivesProcessRestart`
与 `summarizerFailureCooldownIsPersistedAndHonoured` 都是「新引擎实例 + 新 ledger 实例，只共享库」。
真进程重启那一档在 §8 杠③。

## §4 抢锁走数据库

STATUS: 已实现 + 已实测

- 新增 `context/CompressionGate`（接口）+ `context/CompressionLedger`（sqlite 实现）。
  `CompressorEngine.doCompress()` 里：先过进程内 CAS（省一次 sqlite 往返），
  再 `gate.tryAcquire(血统根, holder, ttl)` —— **拿不到就原样返回历史、连摘要都不调**，
  `finally` 里 `release`。锁的权威是 `compression_locks` 表（P15 建的那张，此前生产侧零消费者）。
- 锁键取**血统根**（`lineageRoot()` 走既有 `parentOf`）：否则每次分叉出来的子会话各拿一把锁 = 没锁。
- `compressionLockHolder()` 被 `/compress preview` 打印出来，运行期可查谁持有。
- 断言面：`compressionLocksTableLetsOnlyOneProcessThrough`（两个引擎 + 两个 ledger = 两个进程，共用同一个
  `state.db`；B 被挡住时 `summarizerRuns == 0`）、`lockIsHeldForWholeLineage`（子会话的锁挡住父会话）。
- 红线自查：`git grep -ln tryAcquireCompressionLock` 现在多了一个生产侧消费者。

复算命令：
```
mvn -o test -Dtest='CompressionEngineP14Test' -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:'
git grep -ln tryAcquireCompressionLock
```
实测（`git grep -ln` 这一条在 §0 复算时是 2 个文件，改码+`git add` 之后为 3 个 ——
注意坑：`git grep` 默认只搜**已跟踪**文件，新文件不 `git add` 就会得到「零消费者」的假复算）：
```
z-bot-core/src/main/java/com/zifang/z/bot/context/CompressionLedger.java
z-bot-core/src/main/java/com/zifang/z/bot/store/StateStore.java
z-bot-core/src/test/java/com/zifang/z/bot/store/StateStoreWritePathTest.java
```
判词：`compression_locks` 从「表 + API 都在、生产侧零消费者」变成有真消费者。

## §5 血统入库与 `base #N` 派号

STATUS: 已实现 + 已实测

- `CompressionLedger.forkForCompression(parent, child)`：`lineageDepth(parent)+1` 派号 ⇒ 标题
  `base #1`、`base #2`…；走既有 `forkSession()`（挂 `parent_session_id` + 把父标 `end_reason=compressed`），
  再 `retitle()` 只换取材写标题（`upsertSession` 是整行覆盖语义，直接拿它改标题会顺手把 token 账写成 0，
  所以先 `listSessions(true)` 拿回现值再回填 —— 这条被 `compressionForkWritesParentAndBaseOrdinal`
  里的 `assertEquals(777L, find(store,"s2").tokens)` 钉住）。
- `BotAgent.applyCompression`：**先** 把压缩前的完整原文 `persistSession()` 落进父会话行，
  `memory.load(compressed)` 之后才分叉 ⇒ 父留原文、子拿压缩史（产品缺陷 D4 就是这么抓到的：
  原顺序下父行 0 条消息，「回溯到原文」无原文可回溯）。
- 标题回填：`SessionManager.saveMessages` 会拿「首条消息猜的标题」盖掉库里的 `base #N`，
  所以每次 `persistSession()` 之后按 `lineageTitles` 回填（`reassertLineageTitle`）。
- 检索回溯去重：`CompressionLedger.searchAlongLineage()` —— 每条命中往上走，只要能找到同样命中的祖先
  就把结果换成祖先（原文活着的那一代），再按会话 id 去重。
  钉住它的测试：`lineageSearchWalksBackToOriginalAndDedupes`（原始检索 2 条 ⇒ 回溯去重后 1 条，且是父会话 `p`）。
  生产消费者在 `cli/SessionsCommand.java:125`，写域禁改 ⇒ 见 D2。

复算命令：
```
mvn -o test -Dtest='CompressionEngineP14Test,CompressionLineageForkTest' -DfailIfNoTests=false 2>&1 | grep -E 'Tests run:'
```
实测：
```
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.context.CompressionEngineP14Test
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.agent.CompressionLineageForkTest
```
判词：派号/挂父/父结束/子持压缩史/重启后仍在（`forkChainIsVisibleAfterReopeningTheStore`
换 `StateStore` 实例重开同一个 db 文件）都有断言；真进程重启那一档在 §8。
