# P27 委托面对齐 —— EVIDENCE

STATUS: **p27b 收口完成** —— 四杠全部落地（杠① §5／杠② §6／杠③ §7／杠④ §8）、工单 §3.2 三问的证伪面在 §10、尺和产品的错分开记在 §9、停在哪儿写在 §收口。本文件所有决定性读数均为**实测**，`.log` 被 `.gitignore` 收走 ⇒ 原文贴在节内。

写域：`z-bot-core/src/main/java/com/zifang/z/bot/delegate/**`、`z-bot-core/src/test/java/com/zifang/z/bot/delegate/**`、`_doc/005_testing/acceptance/p27/**`、roadmap 自己那一行。
禁止改动：`agent/BotAgent.java`、`config/BotConfig.java`、`session/**`（p14/p23/p26 在写）。

## §0 第 0 步实测（本棒 2026-09-26 17:01 +0800 复算；工单读数并排列出）

工作树 `/private/tmp/zbot-wt-p27`，分支 `w11-p27`，`HEAD = 53222e1`（与工单基线一致）。

| 项 | 工单读数 | 本棒实测 | 裁定 |
|---|---|---|---|
| z-bot 现状体量 | `DelegateManager.java` 295 行 / 1 文件 | `295 z-bot-core/.../delegate/DelegateManager.java`，同目录只有这一个文件 | 一致 |
| `CHILD_BUDGET_FRACTION` | `= 0.25` @ :40 | `:40 public static final double CHILD_BUDGET_FRACTION = 0.25;` | 一致 |
| 公开面 | `attach(71)/delegateTool(76)/stopChildren(135)/inFlightCount(145)/submitAsync(176)/describeAsync(229)/asyncResult(243)` | 同一批行号逐条对上（另有构造器 `:60`、包级字段 `lastChild :52`） | 一致 |
| **测试面** | **0 支** | **5 支**：`git grep -c '@Test' HEAD -- 'z-bot-core/src/test/java/com/zifang/z/bot/delegate'` ⇒ `HEAD:.../delegate/DelegateTaskTest.java:5` | **推翻工单**：测试面不是 0；但 5 支全盯"子代理构建/深度剥离/预算裁切/中断入口"，**盯投递上限与台账落盘的是 0 支** |
| 全量 `@Test` 基线 | 735 / 71 文件 | `git grep -c '@Test' HEAD -- 'z-bot-core/src/test'` ⇒ `tests=735 files=71`（全仓含 z-bot-app 侧另算：`tests=796 files=81`） | 一致 |
| 接线面 | 只有 `agent/BotAgent.java`：`:157` 字段、`:214` `attach`、`:1854` 构造、`:1154` getter | `git grep -n 'DelegateManager' HEAD -- '*.java'` ⇒ BotAgent 5 处（23 import / 157 / 1154 / 1605 / 1854）+ 自身文件 3 处；`delegation.attach(this)` 实测 `:214`，`toolkit.register(delegation.delegateTool())` 实测 `:1855` | 一致 |
| hermes `tools/delegate_tool.py` | 3,655 行 / **63 个 def** / `class DelegateEvent` @ :624 | `wc -l` = **3655**；`class DelegateEvent(str, enum.Enum)` @ **:624**；def 计数 = **61**（`grep -cE '(^\|[[:space:]])def '`） | 行数/枚举一致；**def 数推翻工单：63 → 实测 61** |
| hermes `tools/async_delegation.py` | 990 行、`_MAX_DELIVERY_ATTEMPTS = 8` @ :84、claim/投递重试 @ :358/:364 | `wc -l` = **990**；`_MAX_DELIVERY_ATTEMPTS = 8` @ **:84**；上限取证的两条 SQL 在 **:354-:364**（`delivery_state='dropped' ... delivery_attempts>=?`） | 一致（工单只给了 :358/:364 两个参数行，真正的置 dropped 语句起于 :354） |
| hermes `tools/delegation_live_log.py` | 424 行、`LIVE_RETENTION_DAYS = 7` @ :44、`LiveTranscriptWriter` @ :112、`prune_stale_live_dirs` @ :404 | 全对上：424 / `:44` / `:112` / `:404` | 一致 |
| 三处合计 | 5,069 行 | `wc -l` 三文件 total = **5069** | 一致 |
| 她谁在用 delegate | "10 处引用" | `grep -rln 'delegate_tool\|async_delegation' ~/.hermes/hermes-agent --include='*.py' \| wc -l` = **55**（工单的 10 是 `\| head` 截断所致） | **推翻工单**：55 文件引用（其中 `tests/` 占 9+） |
| `~/.zbot` 三时点首测 | `8 / 2dadaed0 / 690ddbc0` | `ls -A ~/.zbot \| wc -l` = **8**；config md5 前 8 = **2dadaed0**；state.db 前 8 = **690ddbc0**；`awk` 量 key 长度 = 125（未读取内容） | 一致 |

**§0 结论（可达性实量，回答工单靶子四的怀疑）**：

```
$ grep -rn 'CHILD_BUDGET_FRACTION\|maxDepth' z-bot-core/src/main/java/
z-bot-core/.../delegate/DelegateManager.java:40   (声明)
z-bot-core/.../delegate/DelegateManager.java:47   private final int maxDepth;
z-bot-core/.../delegate/DelegateManager.java:61   构造器形参
z-bot-core/.../delegate/DelegateManager.java:67   赋值
z-bot-core/.../delegate/DelegateManager.java:89   if (depth + 1 > maxDepth)   ← delegateTool 拒委托
z-bot-core/.../delegate/DelegateManager.java:90   ToolResult.error(...)
z-bot-core/.../delegate/DelegateManager.java:164  b.budget(... childBudget(CHILD_BUDGET_FRACTION))
z-bot-core/.../delegate/DelegateManager.java:177  if (depth + 1 > maxDepth)   ← submitAsync 拒委托
z-bot-core/.../delegate/DelegateManager.java:178  return "已达到委托深度上限 ..."
z-bot-core/.../agent/BotAgent.java:1847,1849,1854 maxDepth 由 config.getDelegateMaxDepth() 喂进来
```

⇒ 工单"0 引用的常量只能误导门禁"这一条**不成立**：`CHILD_BUDGET_FRACTION` 有 1 个真实读点（`:164`，且 `DelegateTaskTest:70-71` 已断言其效果 8→2 / 40000→10000）。
但 **`:164` 在 `if (p != null)` 分支里**：`parent` 未回填时子代理拿**父的全额预算**，这条静默旁路**零测试**，本期补。
`maxDepth` 同理可达（`:89/:177`），只是 `maxDepth` 的守卫只挡"再往下委托"，**不挡已经在飞的子代理数**——并发闸是 `config.getDelegateMaxChildren()`（默认 3，`:181`）。

## §0.1 现场与写域自查

- `~/.cache/zbot-p27-lead/` 已存在，E2E 的 `--config-dir` 一律指它下面，不写 `/tmp`。
- 本棒未动 `agent/BotAgent.java`、`config/BotConfig.java`、`session/**`、`store/**`（`StateStore` 只在读侧引用）。

## §0.2 p27b 开工复测（2026-09-26 17:5x +0800，工单读数列并排）

工单 §0 那组命令**原样跑过**，逐字输出：

```
$ cd /private/tmp/zbot-wt-p27
$ git log --oneline -2; git rev-parse --short HEAD
07f25a8 wip(P27a): 委托面投递上限(8)+live台账落盘+两轴状态机穷举 — delegate/** 新增 6 类、49 支新用例
53222e1 docs(roadmap): §8 内核那条"仍未 push / 对外停在 0.2.0"按 16:43 实测作废——…
07f25a8

$ git status --porcelain
 M _doc/005_testing/acceptance/p27/EVIDENCE.md
 M _doc/005_testing/acceptance/p27/p27_mutation.py
 M z-bot-core/src/main/java/com/zifang/z/bot/delegate/DelegateManager.java
 M z-bot-core/src/test/java/com/zifang/z/bot/delegate/DelegateManagerLedgerTest.java
?? _doc/005_testing/acceptance/p27/p27_e2e.py
?? z-bot-core/src/test/java/com/zifang/z/bot/delegate/P27DelegationDriver.java

$ git grep -c '@Test' HEAD -- z-bot-core/src/test | awk -F: '{s+=$NF} END{print "committed_at="s}'
committed_at=784
$ find z-bot-core/src/main/java/com/zifang/z/bot/delegate -name '*.java' | wc -l
       7
$ wc -l z-bot-core/src/main/java/com/zifang/z/bot/delegate/*.java | tail -1
    1780 total
$ wc -l _doc/005_testing/acceptance/p27/*.py _doc/005_testing/acceptance/p27/EVIDENCE.md
     281 _doc/005_testing/acceptance/p27/p27_e2e.py
     539 _doc/005_testing/acceptance/p27/p27_mutation.py
     225 _doc/005_testing/acceptance/p27/EVIDENCE.md
    1045 total
```

（`git status --porcelain` 实测就是这 6 行：4 条 `M` + 2 条 `??`，路径与工单逐条对上。）

| 项 | 工单读数 | p27b 实测 | 裁定 |
|---|---|---|---|
| 开工 HEAD | `07f25a8`（父 = 旧 main `53222e1`） | `07f25a8`，父 `53222e1` | 一致 |
| 未提交/未跟踪 | 4 改 + 2 新 | 4 改 + 2 新，路径逐条对上 | 一致 ⇒ 第一动作按 §1 保存现场 |
| 提交前 `@Test`（`z-bot-core/src/test`） | "以实测为准" | **784**（与前棒 EVIDENCE §4.1 自称的 735→784 对上） | 一致 |
| `delegate` 目录 `.java` 数 | 未给（wip 提交信息自称"新增 6 类"） | **7 个文件 / 1780 行** = 旧 `DelegateManager.java` 1 + `07f25a8` 新增 6（`git show --name-status` 复算：6 个 `A` + 1 个 `M`） | **不是矛盾**：工单/提交信息说的是"新增 6 类"，目录总数是 7。引用时须带口径 |
| **`git merge --ff-only main`** | 工单 §3.1 要求 ff 到新基线 | **ff 不可能**：`main = 9ade134`，`git rev-list --left-right --count main...HEAD` = **`27  2`**（main 有 27 笔我们没有、我们有 2 笔 main 没有 ⇒ 分叉，不是落后）。`git merge --ff-only main` ⇒ `fatal: Not possible to fast-forward, aborting.`，**rc=128**，跑后 `git status --porcelain` 空、HEAD 未变 | **推翻工单的前置假设**（工单以为 HEAD 是 main 的祖先）。按工单"不要硬合"的红线：**本棒不合并**，全部四杠都在 `w11-p27`（HEAD `558900b`）上量 |
| ff 失败之后能不能安全并 | 未给 | 只读探测 `git merge-tree --write-tree main HEAD` ⇒ **rc=0，只输出一个 tree oid `e75448baebf9e117ee76e306156b6d726bd65a78`，没有 CONFLICT 段** | 交给主编：并 `w11-p27` 到 main 是**无冲突**的（三方合并可直接由主编下手），本棒无权合并也不 push |
| 基线含不含 main 那批修复 | "main 已并 p22/p23/p14/p26 + 一条 `user.home` 回归修复" | 本树 `HEAD` **不含**这 27 笔（含 `user.home` 修复）；`main` 侧对 `delegate/**` 没有独立改动（`git log main -- …/delegate` 只到 `b64d294`/`f12c44e` 两笔旧账） | 杠①②③④ 全部读数是**这条基线**的读数，不等于合并树读数；主编并完后需按工单另跑一遍目标树四杠 |
| 杠④ 时点①（开工） | 8 / 2dadaed0 / 690ddbc0 | `HOME\|t1_open\|entries=8\|config=2dadaed0\|state=690ddbc0`；`awk` 量 key 长度 = **125**（只量长度，未读内容） | 一致（详见 §8） |

### §0.3 保存现场（第一动作，先于任何新代码）

6 个路径全部用**显式路径** `git add --` / `git commit -- `（共享索引，未用 `add -A`/`add .`）：

```
$ git diff --cached --stat
 _doc/005_testing/acceptance/p27/EVIDENCE.md                    |  12 +-
 _doc/005_testing/acceptance/p27/p27_e2e.py                     | 281 +++++++++++++++++++
 _doc/005_testing/acceptance/p27/p27_mutation.py                |  75 +++++-
 .../com/zifang/z/bot/delegate/DelegateManager.java |   7 +-
 .../z/bot/delegate/DelegateManagerLedgerTest.java  |  15 +-
 .../zifang/z/bot/delegate/P27DelegationDriver.java | 299 +++++++++++++++++++++
 6 files changed, 678 insertions(+), 11 deletions(-)

$ git commit -m "wip(P27b): 保存前棒未提交的委托面改动与量具" -- <同 6 路径>
558900b wip(P27b): 保存前棒未提交的委托面改动与量具
$ git status --porcelain
（空）
```

⇒ 从 `558900b` 起，前棒的 678 行改动与两支未跟踪量具都进了历史；本棒**没有**用过
`checkout/clean/restore/stash/reset --hard`（工单红线），还原类操作只按 §6 用 cp 副本 + md5 对账。

## §1 靶子一：投递有上限、且上限可取证

STATUS: 未跑

## §2 靶子二：live 台账落盘（进程死了还在盘上）

STATUS: 未跑

## §3 靶子三：事件枚举与状态机（穷举 + 非法迁移大声失败）

STATUS: 未跑

## §4 靶子四：深度与预算的可达性

STATUS: 未跑

## §5 杠① `mvn -o test` 串行三跑

STATUS: **已跑（p27b）** —— 三跑全绿，双尺同数 789，F/E/S 全 0，socket 0。

命令（工单 §3.3 原样；本树基线 `HEAD = 558900b`，含本棒新增的 5 支 `P27FalsificationTest`）：

```
$ rm -rf z-bot-core/target/surefire-reports && mvn -o test      # 全 reactor（3 模块：z-bot / z-bot-core / z-bot-desktop-packager）
```

三跑逐字读数（`~/.cache/zbot-p27-lead/bar1_{a,b,c}.log`）：

```
bar1_a: [INFO] Tests run: 789, Failures: 0, Errors: 0, Skipped: 0
        [INFO] BUILD SUCCESS     [INFO] Total time:  58.582 s
bar1_b: [INFO] Tests run: 789, Failures: 0, Errors: 0, Skipped: 0
        [INFO] BUILD SUCCESS     [INFO] Total time:  58.883 s
bar1_c: [INFO] Tests run: 789, Failures: 0, Errors: 0, Skipped: 0
        [INFO] BUILD SUCCESS     [INFO] Total time:  54.483 s
```

现成尺 `python3 ~/.cache/zbot-integrate/b1parse.py bar1_a.log bar1_b.log bar1_c.log` 原样输出：

```
BAR1PARSE bar1_a.log module_lines=1 class_lines=76 module_sum=789 class_sum=789 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE bar1_b.log module_lines=1 class_lines=76 module_sum=789 class_sum=789 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE bar1_c.log module_lines=1 class_lines=76 module_sum=789 class_sum=789 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
```

- 双尺对账：模块级求和 **789** == 类级 `-- in ` 行求和 **789**（76 条类级行）；
  `F/E/S` 两侧都是 0；`BUILD SUCCESS`；socket 类错误 0 命中。
- `@Test` 口径：提交树 `git grep -c '@Test' HEAD -- z-bot-core/src/test` = 784（前棒 49 支 + 历史 735）
  ⇒ 杠① 实测 **789** = 784 + 本棒 `P27FalsificationTest` 5 支。两把尺同数，没有"少跑一条红"的模块。
- `module_lines=1` 的口径解释（防误读）：全 reactor 只有 `z-bot-core` 有测试，
  所以"模块级汇总行"天然只有 1 条；这不是参照集为空（尺在 0 条时会打 `PARSE_FATAL` 并以 rc=2 退出）。

**第一版 bar1_c 是环境事故，不是产品红**（如实记账，日志留在
`~/.cache/zbot-p27-lead/bar1_c.aborted-foreign-pkill.log`）：

```
[ERROR] The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
[ERROR] org.apache.maven.surefire.booter.SurefireBooterForkException: …
[ERROR] Crashed tests: …（当时正跑 mcp 族：McpBridgeDeregisterTest / McpRealStdioServerTest）
尺读数：BAR1PARSE … bar1_c.log NOT_GREEN build=FAILURE module_sum=237 class_sum=237（F=0 E=0）
```

判据：F/E/S 全 0 却 `BUILD FAILURE`、且类级行数（30）远小于 76 ⇒ 是**分叉 JVM 被外因干掉**，不是断言失败。
根因实测：同一台机器上另一个会话在 18:04 跑了 `pkill -f surefire`
（`ps` 里能看到它的命令行原文），它按**命令行子串**匹配，
把我那个后台包装 shell 也一起杀了 —— 因为我的命令串里带着
`rm -rf z-bot-core/target/surefire-reports` 这个字面 token。
**记账（量具的错 P27b-G1）**：共享机器上 `pkill -f <token>` 会杀掉任何命令行含该 token 的进程，
包括量具自己的包装 shell ⇒ 收口棒把删除目录改成
`find z-bot-core/target -maxdepth 1 -type d -name '*-reports' -exec rm -rf {} +`
（命令行不再出现该 token），重跑 `bar1_c` 得到上面的全绿读数。
**这条不是产品缺陷，也不许拿它当"间歇缺陷"证据**：一跑被外因杀 = 一次事故，不是率。

## §6 杠② 变异测试（p27_mutation.py）

STATUS: **已跑（p27b）** —— 同一张 MUTANTS 表（21 支）跑了两批：**批一 18:14:36 的判定作废**（尺有 P27b-G5 缺陷，归属整列取到的是类名），**批二 18:22:51 是本节判定依据**：`RED-OK=8 / PARTIAL=10 / SURVIVED=3` ⇒ 检出 18/21 = **85.7%**；`md5_restored=OK` **21/21**；`INJECTION_NOT_APPLIED / NONINFORMATIVE / ERROR / RESTORE_FAILED / BASELINE_NOT_GREEN` **全 0**。首跑还有一次 rc=5 停机（P27b-G2，见 §9.1）。

### 6.1 命令（逐字）与两批日志

```
cd /private/tmp/zbot-wt-p27
python3 _doc/005_testing/acceptance/p27/p27_mutation.py            # 批一 → ~/.cache/zbot-p27-lead/bar2-mutation-run.log（18:14:36 台账，作废）
python3 _doc/005_testing/acceptance/p27/p27_mutation.py            # 批二 → ~/.cache/zbot-p27-lead/bar2-fixedG5.log（18:22:51 台账，本节依据）
```

批一的台账已归档为 `~/.cache/zbot-p27-lead/LEDGER.pre-G5-fix.tsv`（不删，作 G5 的取证面）；仓内 `_doc/005_testing/acceptance/p27/LEDGER.tsv` 是批二那一版。

### 6.2 锁 / 永挂体检 / 基线（批二日志开头逐字）

```
== 锁已取: /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock ==
== 永挂体检：await()/waitFor() 命中 3 处 ==
  OK   z-bot-core/src/test/java/com/zifang/z/bot/delegate/P27DelegationDriver.java:255:                p.waitFor(30, TimeUnit.SECONDS);   // 有界，绝不裸 waitFor()
  OK   z-bot-core/src/test/java/com/zifang/z/bot/delegate/P27FalsificationTest.java:365:                return !latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
  OK   z-bot-core/src/test/java/com/zifang/z/bot/delegate/DelegateManagerLedgerTest.java:456:                return latch.await(10, TimeUnit.SECONDS);
== 体检通过：0 处无界等待 ==
== 基线（未注入）==
   rc=0 elapsed=6.1s [INFO] Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
```

口径提醒：这里的 85 是 `SCOPE=com.zifang.z.bot.delegate.*Test,BotAgentTest` 那一套的规模，**不是**杠① 的 789（全 reactor）。批二 21 跑合计 wall 179.9s（最慢 M12=45.1s）。

### 6.3 逐支记号 + "这条 bug 被哪一支具名测试抓到"（批二台账，`hit`=预期红集中真红的那几支）

| 变异 | 记号 | 预期红集中命中的具名测试 | 预期红却没红 | 预期外变红 |
|---|---|---|---|---|
| M01-cap-anchor-bumped | PARTIAL | attemptCapIsEightLikeTheHermesAnchor、exhaustedAttemptsConvergeToTerminalDropped | 无 | 7 支（asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery、deliveredIsTerminal、…） |
| M02-cap-unbounded | PARTIAL | exhaustedAttemptsConvergeToTerminalDropped、theCapEvidenceSurvivesARestart | droppedRowsAreNotOfferedForRestoreButPendingRowsAre | 1 支（q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth） |
| M03-ack-without-claim-allowed | RED-OK | deliveryMatrixIsExhaustive、ackWithoutClaimIsIllegal | ackWithoutClaimFailsLoudly | 无 |
| M04-claim-does-not-burn | PARTIAL | eachClaimBurnsExactlyOneAttempt、exhaustedAttemptsConvergeToTerminalDropped、theCapEvidenceSurvivesARestart、claimLeaseBlocksOtherConsumersUntilItExpires | 无 | 4 支（asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery、deliveredIsTerminal、…） |
| M05-prune-age-inverted | PARTIAL | pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse、pruneHonoursTheRetentionWindowBoundary、unknownScenesAreTerminalAndThusPrunable | 无 | 5 支（corruptStateFileIsIgnoredNotCrashed、q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt、…） |
| M06-prune-eats-inflight | RED-OK | pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse | 无 | 无 |
| M07-retention-window-bloated | RED-OK | retentionConstantMatchesHermesSevenDays | 无 | 无 |
| M08-age-basis-dispatch-time | PARTIAL | pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse、pruneHonoursTheRetentionWindowBoundary | 无 | 2 支（corruptStateFileIsIgnoredNotCrashed、unknownScenesAreTerminalAndThusPrunable） |
| M09-orphan-adoption-ignores-age | PARTIAL | orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes | 无 | 1 支（q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt） |
| M10-orphan-adoption-eats-terminal | RED-OK | orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes | asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery | 无 |
| M11-done-means-delivered | PARTIAL | asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery、unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached | syncDelegationWritesASceneThatIsReadableWithoutTheCaller | 2 支（q1_capEight…、q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt） |
| M12-scene-never-created | PARTIAL | createWritesTheSceneBeforeAnythingRuns、asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery、unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached | 无 | 31 支（ackWithoutClaimFailsLoudly、advanceRejectsIllegalTransitionBeforeTouchingDisk、…） |
| **M13-non-atomic-state-write** | **SURVIVED** | 无 | atomicWriteLeavesNoTempFileAndNoHalfState | 无 |
| M14-legacy-status-silent-default | RED-OK | legacyStatusLiteralsAreNormalizedOrRejected | 无 | 无 |
| M15-redaction-off | RED-OK | secretsAreMaskedBeforeHittingDisk | 无 | 无 |
| M16-id-validation-off | PARTIAL | pathEscapingIdsAreRejected | 无 | 1 支（q1_capEight…） |
| **M17-gate-counts-running-only** | **SURVIVED** | 无 | concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth | 无 |
| M18-poll-burns-delivery | PARTIAL | asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery | 无 | 2 支（q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry、unfinishedAsyncSceneIsAdoptableAsOrphan） |
| **M19-stale-copy-overwrites-scene** | **SURVIVED** | 无 | stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins | 无 |
| M20-stop-leaves-no-scene | RED-OK | stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins | 无 | 无 |
| M21-agents-hides-delivery | RED-OK | asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery | 无 | 无 |

分布合计：`hit` 非空的行 18/21；4 行有"预期红却没红"（M02/M03/M10/M11）、10 行有"预期外变红"、6 行完全"预期内"。

### 6.4 三处 SURVIVED 点名（为什么不许折成 KILLED）

| 变异 | 注入的是什么 | 为什么尺咬不到 | 与本节其他证据的关系 |
|---|---|---|---|
| **M13** | 去掉 `ATOMIC_MOVE`（`DelegationLedger` 的 tmp+move 改直写） | 预期测试 `atomicWriteLeavesNoTempFileAndNoHalfState` 只验"没有 tmp 残留 / 读回是完整态"，单进程读写序下这两件事与是否原子**无关** ⇒ 等价类观测不到。批二里同一支的红仍然只可能在并发窗口出现 | 不是产品没问题，是**这一档证据取不到**；E2E 的 `tmp_residue=false`（§7）是另一条独立读径，它同样只在"没崩"的前提下成立 |
| **M17** | 并发闸退回"只数 RUNNING"（修之前的写法）⇒ 连发可以越过 width | 现有测试全部在**单线程**里驱动 `advance()`，`concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth` 数不到"QUEUED 也占位"这一层 | 正是 §未做 第 4 条（不做跨线程并发 advance）的**实测量**：不补并发驱动，这支变异永远免费 |
| **M19** | 关掉写回对账（reconcile）⇒ 后写的陈旧副本覆盖先落的终态：按了停止、盘上却是 DONE | 预期测试 `stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins` 走的是"先 STOPPED 后 DONE"的单写序；同一支在 M20 下能红（`RED-OK`），说明它咬的是"有没有现场"而不是"谁覆盖谁" | 与 §10.3 的"读回零对账"是同一条病（P27b-D5/D6），差别在于本棒只钉证据不动产品代码 |

### 6.5 台账有效性与现场洁净对账（实测）

- `#generated_by 2026-09-26T18:22:51+0800 pid=80022`；`stat` 实测 `p27_mutation.py` mtime=1790417962 < `LEDGER.tsv` mtime=1790418172 ⇒ **晚于脚本**，符合 §3.4。
- 跑完的收尾自检（批二日志逐字）：`git diff --name-only (src/main/delegate): <空>`、`git status --porcelain 里的非预期条目: <无>`。
- 字节级复核：`z-bot-core/src/main/java/com/zifang/z/bot/delegate/DelegateManager.java` md5 = `17273a1b301084634195d9146f1d463c`（23113B），与本棒保存现场的提交 `558900b` 里同一文件 md5 **完全相同**；`git status --porcelain -- z-bot-core/src/main` = **0 条** ⇒ 21 支注入零残留。
- **我自己差点记错的一条**：`~/.cache/zbot-p27-lead/baseline-DelegateManager.java`（12788B，md5 `210725f4…`）实测 == `git show 53222e1:…` ⇒ 它是**前棒开工前**（旧 main）的基线副本，不是本棒的"注入前副本"。本棒逐支还原用的是脚本内的 `md5(path)` → `shutil.copyfile` 还原 → 比对（21/21 `OK`），加上面这条 git 对账。两条尺别混引。


## §7 杠③ 真进程 E2E（p27_e2e.py，≥3 整跑）

STATUS: **已跑（p27b）** —— 修尺（P27b-G6）后 **四整跑全过**：`e2ed / e2ee / e2ef / e2eg`，每跑 `CHECKS=20 FAILED=0`、退出码 0；修尺前三跑 `e2ea / e2eb / e2ec` 各 `CHECKS=20 FAILED=4`、退出码 1，四条 FAIL 全部是量具取键不存在的**假红**（判据与取证见 §9.1 P27b-G6）。

### 7.1 命令（逐字）

```
cd /private/tmp/zbot-wt-p27
for t in a b c; do python3 _doc/005_testing/acceptance/p27/p27_e2e.py e2e$t; done   # → ~/.cache/zbot-p27-lead/bar3-e2e-x3.log（修尺前）
for t in d e f; do python3 _doc/005_testing/acceptance/p27/p27_e2e.py e2e$t; done   # → ~/.cache/zbot-p27-lead/bar3-e2e-x3-fixedG6.log（修尺后）
python3 _doc/005_testing/acceptance/p27/p27_e2e.py e2eg                              # → ~/.cache/zbot-p27-lead/bar3-e2e-run-g.log
```

每跑 = `mvn -o package`（`BUILD|package_rc=0`，离线）+ 真 `java -cp` 起委托方 + 量具读盘 + **`kill -9`** + **另一个 JVM** 只从盘上判词。

### 7.2 三跑逐字读数（`bar3-e2e-x3-fixedG6.log`，`e2ed/e/f`）

```
HOME|before|entries=8|config=2dadaed0|state=690ddbc0
BUILD|package_rc=0
CHECK|driver_class_built                       PASS /private/tmp/zbot-wt-p27/z-bot-core/target/test-classes/com/zifang/z/bot/delegate/P27DelegationDriver.class
CHECK|owner_pid_is_the_live_process            PASS owner_pid=88917 java_pid=88917      # e2ee=89094、e2ef=89287
CHECK|scene_pre_created_before_kill            PASS state=RUNNING
CHECK|scene_still_non_terminal                 PASS state=RUNNING
CHECK|delivery_axis_untouched                  PASS delivery=PENDING attempts=0
CHECK|delegating_process_really_died           PASS returncode=-9
CHECK|owner_pid_gone_from_ps                   PASS ps 命中=0
E2E|CHECKS=11 FAILED=0
INSPECT|rc=0
CHECK|java_side_all_checks_green               PASS E2E|CHECKS=11 FAILED=0
CHECK|no_real_key_in_ledger                    PASS real_key_len=125（只量长度，未读内容）
CHECK|zbot_home_untouched                      PASS ('8', '2dadaed0', '690ddbc0') -> ('8', '2dadaed0', '690ddbc0')
RUN|e2ed|CHECKS=20|FAILED=0|全过
RUN|e2ee|CHECKS=20|FAILED=0|全过
RUN|e2ef|CHECKS=20|FAILED=0|全过
```

`scene_bytes_unchanged_after_kill`（三跑各一条，逐字）：

```
PASS md5_before=a40a039881c8bf398d8ca51301fbe324 md5_after=a40a039881c8bf398d8ca51301fbe324   # e2ed
PASS md5_before=7a12d8fcdebb6d6f3a9f30bfeb6c17b9 md5_after=7a12d8fcdebb6d6f3a9f30bfeb6c17b9   # e2ee
PASS md5_before=42e5747f8ff59b4249e71faf01977626 md5_after=42e5747f8ff59b4249e71faf01977626   # e2ef
```

`e2eg`（第四跑，用来给 §8 的 t2 括住窗口）同样 `RUN|e2eg|CHECKS=20|FAILED=0|全过`。

### 7.3 inspect 侧原判词行（`~/.cache/zbot-p27-lead/e2e/e2ea/inspect.out` 逐字，取键修好前后同一份）

```
E2E|scenes_on_disk=1
E2E|scene id=bg1790418199948-1 state=RUNNING delivery=PENDING attempts=0 owner_pid=88917 dispatched_at=1790418199948 updated_at=1790418199977 child_session=
E2E|after_kill state=RUNNING delivery=PENDING attempts=0 non_terminal=true
E2E|prune_before_adoption=0
E2E|adopted=1 state_after_adopt=UNKNOWN events=7
E2E|prune_with_zero_window=1 still_readable=false
E2E|cap_before_pull=PENDING(0/8)
E2E|cap_attempt_8=DROPPED(8/8)
E2E|cap_attempt_9=refused DROPPED(8/8)
E2E|cap_final=DROPPED(8/8) token_used_last=pull-console:88949:8 restore_offered=0
E2E|cap_from_fresh_instance=DROPPED(8)
E2E|tmp_residue=false
E2E|ack_without_claim=IllegalStateException delivery=PENDING
E2E|CHECKS=11 FAILED=0
```

判读（真进程口径，与 §10 的 JVM 内测试互为对照）：
1. **幻影在飞条目是真的**：`kill -9` 前后 `state.json` 字节不变（`returncode=-9` + md5 两端相同），新 JVM 读回 `state=RUNNING delivery=PENDING attempts=0 non_terminal=true`，`prune_before_adoption=0` ⇒ 保留期内任何回收都碰不到它；只有显式 `adoptOrphans(0)` 才转成 `UNKNOWN`（`adopted=1 … events=7`）。⇒ 支持 P27b-D5。
2. **上限 8 在真进程里数得对**：第 8 次领取后 `DROPPED(8/8)`，第 9 次 `refused`，`restore_offered=0`（DROPPED 不进恢复面），换新实例仍 `DROPPED(8)`。⇒ 支持 §10.1 的"8 数的是每条委托的领取次数"。
3. **无凭证 ack 大声失败**：`IllegalStateException`，投递轴仍 `PENDING`。
4. **密钥不落盘**：`no_real_key_in_ledger PASS real_key_len=125`（只量长度）。
5. `~/.zbot` 全程不动：每跑 before/after 同为 `('8','2dadaed0','690ddbc0')`（§8）。

## §8 杠④ `~/.zbot` 三时点

STATUS: **已跑（p27b）** —— 三格工单读数与实测逐次相符，四杠跑完仍然不变。

| 时点 | 实测命令 | `ls -A ~/.zbot \| wc -l` | `md5 -q config.properties\|cut -c1-8` | `md5 -q state.db\|cut -c1-8` | 备注 |
|---|---|---|---|---|---|
| t1 开工 | §0.2 那一组 | **8** | **2dadaed0** | **690ddbc0** | 与工单 §2 三格一致（key 长度另量=125，只量长度） |
| t2 测量在飞 | 18:15 杠② 21 支注入/还原窗口内独立复测；杠③ 每一跑由量具自打的 `HOME\|before`/`HOME\|after` **括住**"起真子进程 → `kill -9` → 另一个 JVM 判词"整段 | **8** | **2dadaed0** | **690ddbc0** | 7 跑 × 两端读数全同值（§7.2）；`e2eg` 那次我又在跑内单独打了一次：8 / 2dadaed0 / 690ddbc0 / key_len=125 |
| t3 收尾 | 四杠全部跑完之后复测 | **8** | **2dadaed0** | **690ddbc0** | 见 §收口（最后一次复测与 t1 逐字相同） |

**t2 的一处诚实记账**：工单写的字面是"E2E 在飞"那一瞬的读数。我在 `e2eg` 起跑后 +8s 打了一次 `ps` 采样想证明"此刻真子进程在飞"，**采样落空**（`grep -c '[P]27DelegationDriver --mode dispatch'`=0、`[/bin/sleep 40]`=0 —— 那时进程还在 `mvn -o package` 阶段）。所以 t2 的证据形态是**夹逼**：每跑 `HOME\|before` 与 `HOME\|after` 两端同值、区间内 `CHECK\|delegating_process_really_died PASS returncode=-9` 证明确实有真子进程被起出并打死，而不是我手动抓到的某一瞬中值。不许把它写成"在飞瞬间读数"。

## §9 量具的错 / 产品的错（分两节）

STATUS: **p27b 已记**（本棒新增：量具 **7** 条 + 产品 6 条；前棒留下的 P27-D2/D3 见 §4 与 §6 的 M17/M19/M20。表是追加式写的，**G7 物理行排在 G6 之前，读表按编号不按行序**）

### 9.1 量具的错（尺的问题，不是产品的问题）

| 编号 | 现象 | 判据 / 根因 | 处置 |
|---|---|---|---|
| **P27b-G1** | 杠① 第一版 `bar1_c` 读出 `BUILD FAILURE`（`module_sum=237 class_sum=237 F=0 E=0 S=0`，类级行 30 条） | F/E/S 全 0 却翻 FAILURE + 类级行数远小于 76 ⇒ 分叉 JVM 被外因杀：`The forked VM terminated without properly saying goodbye`。根因实测 = 同一台机器另一个会话在 18:04 跑 `pkill -f surefire`，按**命令行子串**匹配，把我那个后台包装 shell（命令里带 `rm -rf …/surefire-reports` 这个 token）连锅端了 | 判为**环境事故、一跑一次**，不许写成"间歇缺陷率"；日志留 `bar1_c.aborted-foreign-pkill.log`；重跑改用 token-free 的 `find … -name '*-reports' -exec rm -rf {} +` ⇒ 全绿（§5） |
| **P27b-G2** | 杠② 前置体检 `!! 发现 2 处无界等待` ⇒ **rc=5 停机**，一支变异都没注入 | 体检是**逐行**正则 `\.await\(|\.waitFor\(`，看不见方法体：命中的是 `DelegateManagerLedgerTest:510/515` 的 `sticky.await()` / `g.await()`，而 `Gate.await()` 的**方法体**是 `latch.await(10, TimeUnit.SECONDS)`（有界）。⇒ 守卫把一个有界包装读成无界 | **不改判据**（判据本身是对的：无界等待会在共享变异锁下酿成全编队事故）；把包装方法改名 `blocked()`（与本棒 `P27FalsificationTest.Gate.blocked()` 同形），改完剩下 3 处命中全是**字面**有界调用、0 处 UNBOUND。改名后实测见 §6 开头 |
| **P27b-G3** | 工单 §3.4 允许的记号集 = `KILLED / RED-OK / SURVIVED / PARTIAL / INJECTION_NOT_APPLIED`，而 `p27_mutation.py`（前棒版）的产出集 = `KILLED / SURVIVED / INJECTION_NOT_APPLIED / BASELINE_NOT_GREEN / RESTORE_FAILED / NONINFORMATIVE / ERROR` | 两侧**不是同一个词表**：脚本没有 `RED-OK`、`PARTIAL`，只有把两者一并压成 `KILLED` 的粗档；多的 4 个是"停机/不具信息"类（`NONINFORMATIVE` = rc≠0 但既无具名红也无类级红，多半是编译断或没跑到用例） | **不为了对齐词表把 `NONINFORMATIVE` 折叠成 `KILLED`**（那是拿假绿冒充杀变异）。与 P27b-G5 同一批改动里引入 `RED-OK`（预期红命中且无预期外红）/ `PARTIAL`（命中但有预期外红）/ `KILLED`（预期红集零命中、被别的用例杀掉），并把 `BASELINE_NOT_GREEN / RESTORE_FAILED / NONINFORMATIVE / ERROR` 在 docstring 里明确标成"停机型第六档：出现即本轮不可读"。批二实测第六档 **0 次**，台账只出现五档之内的三个记号 |
| P27b-G4 | 工单 §3.1 断言"`git merge --ff-only main` 可行" | 实测分叉（`27  2`），ff 结构上不可能；红线又写"禁合并 main" | 见 §0.2：不硬合，四杠都在本分支基线上量；只读 `merge-tree` 探测证明"并得干净"（tree `e75448b`、无 CONFLICT）|
| **P27b-G5** | 杠② 批一 21 行**每一行**都印 `预期红却没红:<方法名> \| 预期外变红:com.zifang.z.bot.delegate.<类名>`，`killed_by_or_red_tests` 整列只有类名 ⇒ 工单 §3.4 要的"这条 bug 被哪一支**具名**测试抓到"系统性失效 | 尺的错，不是产品的错：`failed_tests_from_surefire()` 用 `re.search(r"<testcase[^>]*name=\"([^\"]+)\"", head)`，而 surefire 写的是 `<testcase name="<方法>" classname="<类>">`——贪婪 `[^>]*` 会退到 `classname=` 里那个 `name=` 上。取证（同一份 XML 两种正则对拍）：`<testcase name="stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins" classname="com.zifang.z.bot.delegate.DelegateManagerLedgerTest"` → 旧式取到 `com.zifang.z.bot.delegate.DelegateManagerLedgerTest`，新式取到 `stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins`。预期红集本身**不是虚构**：24 个方法名逐个 `grep 'public void <名>('` 复算，全部存在且各只在一支测试类里 | **不折叠、不弱化判据**：改 `p27_mutation.py` 的取键（逐属性解析，只在字面 `name=` 处取值）后**整批 21 支重跑**（批二 18:22:51），批一台账归档 `~/.cache/zbot-p27-lead/LEDGER.pre-G5-fix.tsv` 只留证不作判定。批二才有 `RED-OK=8 / PARTIAL=10 / SURVIVED=3` 的真实分布（§6.3）|
| **P27b-G7** | 共享变异锁可以是 **0 字节**（没有 owner pid），于是 `p27_mutation.py` 的"死锁才接管"退化成"读不到 pid 就接管" ⇒ 真实持有者还活着时这道闸形同虚设，别的编写棒的杠② 会被无声抢锁 | 实测：收口时 `.git/zbot-mutlock` `size=0 mtime=Sep 26 18:26:02`，而本棒两批都打印过 `== 锁已释放 ==`、且脚本取锁必写 `<pid> p27a-mutation <ts>`（非 0 字节）⇒ 不是我留的；`p27_mutation.py:289` 的 `int(stale.split()[0])` 在空串上抛 `ValueError` → `hp=None` → 走 `:296-301` 的 `unlink` 接管分支。旁证：`grep -n mutlock _doc/005_testing/acceptance/p27/p27_e2e.py` = **0 命中** ⇒ 杠③ 压根不取锁（我 18:26 的 E2E 四跑就是在无锁下跑的） | **只登记不改**（改法有两条且都要跨会话统一：空内容一律 `rc=4` 走人 / 把 owner 交给 `flock` 而不是文件内容；杠③ 是否必须共锁也得主编定）。按红线我没删别人的锁、也没 kill 任何持有者 |
| **P27b-G6** | 杠③ 修尺前三跑（`e2ea/b/c`）各 4 条 CHECK 恒红：`inspect_read_back_the_scene FAIL <missing>`、`orphan_adopted_to_unknown FAIL adopted=1 state_after_adopt=UNKNOWN … state_after_adopt=<missing>`、`dropped_not_replayed FAIL restore_offered=<missing>`、`ack_without_claim_loud_fail FAIL ack_without_claim=IllegalStateException delivery=PENDING … delivery=<missing>` | 同一行里"既报拿到了值、又报该键 missing"⇒ 只可能是量具取键错：`p27_e2e.py` 的 `parts = l[4:].split("=", 1)` 一行只按**第一个** `=` 切一次，于是 key 成了字面量 `"after_kill state"`，行内其余字段（`state_after_adopt` / `restore_offered` / `delivery` / `still_readable`）永远查不到；驱动（`P27DelegationDriver`）一行发多字段是设计如此，java 侧 `E2E|CHECKS=11 FAILED=0` 一直是对的 | 改尺不改判词：逐对 `k=v` 收进 `tokens`，另按"行首字段/裸标签"建 `byline`，五条 CHECK 改判 `field(head, key)`。**先拿已知样本回读**（`e2ea/inspect.out` 离线重放）验第一版改完 `after_kill` 仍 `<missing>`（裸标签行首没有 `=`），补 `bare` 登记后才对上；阴性对照同时保留：`field('after_kill','nope')`、`field('nope','state')` 仍 `<missing>` ⇒ 尺没被改成恒真。修后 `e2ed/e/f/g` 四跑 `CHECKS=20 FAILED=0`（§7）|

### 9.2 产品的错（实现的问题，本棒只钉不改）

| 编号 | 判词 | 取证 |
|---|---|---|
| **P27b-D1** | **投递上限与恢复面都没有生产入口**：`release()`（全仓唯一把 8 当守卫读的地方，`DelegationDelivery:105`）、`drop()`、`undeliveredTerminalResults()`、`sweepAtStartup()` 在 `src/main` 的调用者 = **0** | §10.1 的 `git grep` 原文；`P27FalsificationTest#q1_…`（生产 6 次拉取之后 `attempts=1`、`DELIVERED(1/8)`） |
| **P27b-D2** | `DelegationDelivery.describe()` 现场不在时判词写死 **`MISSING(-/1)`**，同一本账别处都是 `/8` ⇒ 操作员会读成"只许领一次" | `q1_…` 钉住字面量 |
| **P27b-D3** | `DelegationLedger.load()` 的注释是"盘上没有 / 解析失败 ⇒ null"，但 id 不合 `checkedId`（`[A-Za-z0-9]` 起头 + ASCII 形状）时它**抛** `IllegalArgumentException`；`/background result <用户输入>` 这条口将来直通这里 | `q1_…` 最后两行（阳性对照：形状合规的不存在 id 确实返 null） |
| **P27b-D4** | **两轴交叉没有门**：`claim()` 只查投递轴 `terminal()`（`DelegationDelivery:71`），对生命周期轴零查询 ⇒ `(QUEUED, DELIVERED)` 可产出，而恢复面只认终态 ⇒ 结果从没生成却被记成交付、且永不重放；生产入口唯一那道门是 `DelegateManager:419` 的**内存** status，进程一死门就没了（新进程 `/agents` 说"暂无"、`backgroundResult` 说"未知 id"，而盘上就躺着那条） | `q2_…`、`q3b_…` |
| **P27b-D5** | **幻影在飞条目 + 读回零对账**：`load()/list()` 不判超期不回写；`pruneStale()` 只碰终态 ⇒ 被 `kill -9` 的委托现场以 `RUNNING/PENDING` 永久留在盘上且**永不回收**，同时被 `delivery.summary()` 计入 `delegations=/pending=`；对账层 `adoptOrphans()` 存在但没人自动调（同 D1） | `q3a_…`；`p27_e2e.py` 的 `scene_still_non_terminal` 那一格（§7） |
| **P27b-D6** | **不可解析的现场从所有读/收射程里消失**：按 hermes 线面值（小写 `dropped`，`async_delegation.py:354` 的 SQL 面值）写 `delivery_state` ⇒ `DeliveryState.valueOf`（`DelegationLedger:630`）抛 ⇒ `catch (Exception)` 静默返 null（`:197-200`）⇒ `list()/adoptOrphans()/pruneStale()` 全看不见 ⇒ **目录永久泄漏**。同一根 `ageBase` 的另一面：字段缺失、时间戳为 0 的**终态**现场在第一次 sweep 就被判"超期"当场删除 | `q3c_…`（阳性对照：`pruneStale(0)` 返回值 == 2，完好现场确实被删） |

**本棒没有动 `src/main` 的任何产品代码**：工单 §3.2 给的是"补，或诚实记为未覆盖"，
而这几条的修法都要跨出写域（`BotAgent.java` 接线）或改产品语义（交叉轴到底允不允许
`DONE` 之前投递），主编点头前只点名不动刀。修法清单在 §10.3 末。

## §未做（明确不做 + 理由）

STATUS: **p27b 已记**（下方每一条都点名"没做 + 为什么 + 依据哪条实测量"）

1. **不合并、不 push、不改 roadmap** —— 红线；且 `ff` 结构上不可能（§0.2 实测 `27  2`）。
   四杠读数全部属于 `w11-p27`，**不等于合并树读数**；主编并完要在目标树重测四杠。
2. **不补两轴交叉合法表**（`DelegateTransitions` 里没有 (state, delivery) 这一格）——
   产品语义未定（§10.2 未覆盖①）。
3. **不测 `(X, CLAIMED)` 在写盘降级下的滞留** —— 未覆盖（§10.2 未覆盖②）：
   需要把 `DelegationLedger` 的写侧打到 `ok=false` 再走并发领取，本棒没造那个现场。
4. **不测同一 id 的跨线程并发 `advance`** —— `q3a` 是"跨实例读"，不是"跨线程写"；
   前棒的 `stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins` 覆盖的是先落终态者胜，
   并发写窗口（M19/M20 的注入面）没有压力测试。
   **本棒新增实测量**：批二 `M17-gate-counts-running-only` 与 `M19-stale-copy-overwrites-scene`
   两支变异 `SURVIVED`（§6.4）——不补并发驱动，这两档证据结构性取不到。
5. **不做 hermes 侧的 990 行 `async_delegation.py` 逐条对表** —— 本期只对齐了
   `MAX_DELIVERY_ATTEMPTS / CLAIM_LEASE / LIVE_RETENTION_DAYS / state 面值` 四个数与形状，
   SQL 级语义（`restore_undelivered_completions` 的 `WHERE state != 'running'`）在产品里没有对应物（D1）。
6. **不真发 minimax 请求** —— 红线；杠③ 的 `stub.base.url=http://127.0.0.1:1/v1` + `stub-key-not-real`，
   只绑回环，真 key 只量长度（125）。
7. **不给三处 `SURVIVED` 补测试** —— `M13`（要真并发读写窗口）、`M17`（要跨线程连发压 `advance`）、
   `M19`（要"两条写序互相覆盖"的双写现场）都要往 `src/test` 里加并发驱动；本棒按 §未做 第 4 条
   的口径只点名不补，且这三条正是把 85.7% 检出率推到 100% 的唯一缺口。
8. **不重跑杠① 来吃 §6/§7 之后的新状态** —— 三跑（§5）跑在本棒最后一次 `.java` 改动之后；
   那之后我只改了两个 `.py` 量具（`p27_mutation.py` / `p27_e2e.py`）和 `EVIDENCE.md`、并新增
   `LEDGER.tsv`（未被 `.gitignore` 收走，`.log` 才被收走），**没有动任何 `.java`** ⇒ 789 那个数仍成立。
   若主编要"合并前最后一眼"，请在合并树重跑 §5 那一组（每跑约 100s）。
9. **`p27_e2e.py` 的 `HOME|before` 不是"在飞瞬间"读数** —— §8 t2 的诚实形态是夹逼，
   我一次 `ps` 采样落空（§8 末）；要做真·瞬间读数需要在 dispatch 与 kill 之间插一次外部测量，本棒没做。


STATUS: 已跑（§1—§4）

## §1 靶子一：投递有上限、且上限可取证

### 1.1 现状量法（先把洞量出来，再决定补什么）

现状探针 `P27BaselineProbeTest`（**只在修之前跑**，跑完删除，不进最终测试面）：
默认 JSON 会话模式下 `SessionManager(dir).getStore()` 取数、`submitBackground` 一条异步委托、
`backgroundResult` 连拉 12 次。原始输出：

```
$ mvn -o test -Dtest=P27BaselineProbeTest -DfailIfNoTests=false   # rc=0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.169 s -- in com.zifang.z.bot.delegate.P27BaselineProbeTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
PROBE|submitted_line=已提交异步委托 bg1790413451432-1（/agents 查看进度，/background result bg1790413451432-1 取回结果）
PROBE|id=bg1790413451432-1
PROBE|describeAgents_nobody_has_read_yet=异步委托 (1)   bg1790413451432-1  DONE  bg probe task
PROBE|store_from_json_mode_sessionmanager=null
PROBE|pulls_returning_reply=12/12
PROBE|last_pull=[DONE] bg probe task probe-reply-body
PROBE|session_dir_files_after_kill_sim=
PROBE|delegate_dir_files=delegate-children(d) sandbox(d) sessions(d)
```

配套静态读数（修之前，同一棵树 `HEAD = 53222e1`）：

```
$ git grep -n 'ATTEMPT\|MAX_DELIVERY\|retention\|RETENTION' HEAD -- 'z-bot-core/src/main/java/com/zifang/z/bot/delegate'
grep_rc=1        # 0 命中：投递上限这根轴压根不存在
```

### 1.2 判词（现状到底是无界还是静默成功 —— 两个都是）

| 问 | 实测答复 | 证据 |
|---|---|---|
| "对端没接住"会不会被记成成功？ | **会，而且是默认**。子代理线程自己写 `d.status="DONE"`，`/agents` 当场显示 `DONE`，此时**没有任何人接过**（`nobody_has_read_yet` 那一行就是没拉过一次的状态） | `PROBE\|describeAgents_nobody_has_read_yet` |
| 拉模式有没有上限/计数？ | **无界**：连拉 12 次全返回回复，无尝试计数、无终态、无 `dropped` | `PROBE\|pulls_returning_reply=12/12` |
| 这套旗子在不在盘上？ | **一行都不在**：默认 JSON 模式下 `getStore()` 返 `null` ⇒ `upsertDelegation` 整段被跳过；会话目录里 0 个文件 | `PROBE\|store_from_json_mode_sessionmanager=null`、`PROBE\|session_dir_files_after_kill_sim=`（空） |

⇒ 这正是 P16 抓过的 `lastDelivery='ok'` 同型洞的第二份实现：**把"我这边跑完了"当成"对方收到了"**。
所以本期补的不是"重试逻辑"，是**投递轴本身**：`DONE` 与 `DELIVERED` 分家、claim 烧尝试数、
到 8 次收敛成终态 `DROPPED`、尝试数落 `state.json`（可取证）。

### 1.3 补完之后（同一批判据的反向断言）

- 锚点常量：`DelegationDelivery.MAX_DELIVERY_ATTEMPTS = 8`（与 `async_delegation.py:84` 同值，测试钉死）；
  租约 `CLAIM_LEASE_MILLIS = 300_000`（她 `claim_completion_delivery` 的 `now - 300`）。
- 收工 ≠ 送达：`DelegateManager.submitAsync` 的 worker 只推进生命周期轴，投递轴留在 `PENDING`
  （`DelegationDeliveryTest#readyResult` 里那句 `assertEquals("收工不等于送达", PENDING, e.delivery)`）。
- 无凭证的 ack 大声抛 `IllegalStateException`（`ackWithoutClaimFailsLoudly`），
  底层同一条规矩是**非法迁移**：`PENDING --RESULT_DELIVERED-->` 抛（`DelegateStateMachineTest#ackWithoutClaimIsIllegal`）。
- 烧完 ⇒ `DROPPED(8/8)`，第 9 次 `claim` 直接领不到、`drop` 返 false、终态判词事件只落一次
  （`exhaustedAttemptsConvergeToTerminalDropped`）。
- 上限可取证：换一个新 `DelegationLedger` 实例读同一个根（= 进程重启），
  `attempts` 仍是 8、`delivery_state` 仍是 `DROPPED`，且 `state.json` 里能 grep 到 `"delivery_attempts" : 8`
  （`theCapEvidenceSurvivesARestart`）。
- 重启恢复只捞 `PENDING`：`undeliveredTerminalResults()` 不含 `DROPPED`/`DELIVERED`
  （`droppedRowsAreNotOfferedForRestoreButPendingRowsAre`）⇒ 没人接的完成事件不会永远重放。

## §2 靶子二：live 台账落盘（进程死了还在盘上）

新增 `delegate/DelegationLedger.java`：每条委托一个目录 `<root>/<id>/{state.json,events.log}`，
形状抄她 `delegation_live_log.py` 的 `cache/delegation/live/<delegation_id>/task-<n>.log`。

| 她的约束 | 本期怎么落 | 断言在哪 |
|---|---|---|
| "pre-created with a header at dispatch time" | `DelegateManager.submitAsync`/`runChild` **在建账那一刻**就 `mkdirs + header + state.json`，之后才进线程池/建子代理 | `createWritesTheSceneBeforeAnythingRuns`、`asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery`（子代理被闸门挡住时，另起一个 `DelegationLedger` 实例已经读得到 `QUEUED/RUNNING`） |
| "Never raise into the agent loop" | 第一次写失败翻转 `ok=false`，之后全部 no-op；根目录推不出来（`ledgerRootFor` 返 `null`）时整本台账 disabled | `writeFailureDegradesToNoOpInsteadOfBreakingTheAgent`、`disabledLedgerIsHarmless` |
| "Survive child crashes"（一次写一次开合） | `state.json` 走 `tmp + ATOMIC_MOVE`，`events.log` 每次 `append + flush` 不留长句柄 | `atomicWriteLeavesNoTempFileAndNoHalfState`、`eventsLogCarriesWireNamesInOrder` |
| `LiveTranscriptWriter` 的 `_redact` 边界 | 落盘前统一遮 `sk-…` 与 `api_key=…`（任务文本、回复正文、事件详情三条口） | `secretsAreMaskedBeforeHittingDisk`、`syncDelegationWritesAScene…` |
| `LIVE_RETENTION_DAYS = 7` + `prune_stale_live_dirs` | 同名常量 + `pruneStale(maxAge, now)` 返回删除条数 | 见下两行 |
| 她只判 mtime、只判年龄 | **两处故意收紧**：① 年龄看盘上字段 `finishedAt`（不是 mtime）；② 非终态一律不删 | `pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse` —— 过期终态删 1 条、未过期终态留、超期在飞留，且测试里把留着的目录 `setLastModified` 敲老 400 天做 mtime 口径的证伪 |

回收的正反两问 + 边界（`§6` 变异里 M05/M06/M07/M08 四支都盯这一段）：

- `pruneHonoursTheRetentionWindowBoundary`：三条现场年龄分别为 `窗口-60s / +60s / +180s` ⇒ 删 2 留 1，第二次跑必须删 0。
- `unknownScenesAreTerminalAndThusPrunable`：被 `kill -9` 之后判成的 `UNKNOWN` 也是终态，进得了回收。

"进程死了"的判词入口是 `adoptOrphans(staleMillis, now)`：非终态且心跳超期 ⇒ 走
`DelegateEvent.ORPHAN_ADOPTED` 判成 `DelegateState.UNKNOWN`（她的 `state='unknown'`，
`async_delegation.py:262` 同位），并留一条 `delegate.orphan_adopted` 事件行。
正反两问：`orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes`
（超期的改判、在飞的不许动、已收工的不许再过一遍）。

## §3 靶子三：事件枚举与状态机

- `DelegateEvent`（12 个成员，线名 `delegate.*`）对齐她 `delegate_tool.py:624` 的 `class DelegateEvent(str, enum.Enum)`
  与 `_LEGACY_EVENT_MAP`：入站裸字符串走 `fromLegacyStatus()`，**认不出来就抛**（M14 盯这条）。
- 两条正交轴：`DelegateState`（`QUEUED/RUNNING/DONE/FAILED/STOPPED/UNKNOWN`）
  与 `DeliveryState`（`PENDING/CLAIMED/DELIVERED/DROPPED`），迁移表在 `DelegateTransitions`。
- **穷举复算**：`DelegateStateMachineTest` 里手写一份期望表，对 `6×12` 与 `4×12` 全笛卡尔积逐格比对
  （`lifecycleMatrixIsExhaustive` 断言复算格子数 = 状态数 × 事件数，`deliveryMatrixIsExhaustive` 同理）；
  表里没有的边一律必须抛。另有 `everyEventHasADocumentedVerdict`（新加事件不登记就红）、
  `terminalStatesHaveNoExitAtAll`（终态出口数必须 0；非终态出口数不许 0，否则"永远推不动"也是缺陷）。
- 非法迁移的判词两侧都点名（`illegalTransitionsFailLoudWithBothSidesNamed`），
  且**抛之前不落盘**（`advanceRejectsIllegalTransitionBeforeTouchingDisk` 用 `saveCount()` 对账）。
- 并发下的第二次判决：`DelegationLedger.advance()` 先与盘上事实 reconcile，
  盘上已终态 ⇒ 拒写（`stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins`，M19/M20 盯这一段）。

## §4 靶子四：深度与预算的可达性

见 §0 结论段（`CHILD_BUDGET_FRACTION` 与 `maxDepth` 都可达，工单的"0 引用"怀疑不成立）。
本期补的是**取证面**而不是搬运：

1. `parent == null` 时 `:164` 不裁子预算这条旁路，以前零测试零痕迹 ⇒ 现在建账事件里多一条
   `delegate.budget_unclamped`（并 `LOG.warn`），断言在
   `unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached`（直接 `new DelegateManager(...)` 不 `attach`）。
2. 深度上限拒绝路径**不许建账**：`depthExceededWritesNoSceneAtAll`
   （同步 `delegateTool().execute` 与 `submitAsync` 两条口都试，断言 `list().size()==0` 且 `saveCount()==0`）。
3. 并发宽度不是装饰（工单靶子四的怀疑方向对了一半）：`delegateMaxChildren` 全仓读点 1 处
   `DelegateManager.java:328`（`config == null ? 3 : config.getDelegateMaxChildren()`），闸门 `:329-338`；
   基线只数 `"RUNNING".equals(d.status)`（`53222e1` 版 `:183`）是漏的 ⇒ 改成数非终态 +
   `concurrencyGateBurstCannotExceedWidth`。
   ※ 工单靶子四的另一半（"深度守卫读死常量""实例池跨委托缓存"）**在基线里不存在**：
   `git show 53222e1:...DelegateManager.java` 全文 295 行里 `maxDepth` 是构造器形参（`:47/:61/:67`），
   两处守卫 `:89`（`delegateTool`）与 `:177`（`submitAsync`）都读它；`buildChild`（`:153-167`）
   每次 `new SessionManager(new File(childSessionDir, "d<depth>-<seq>"))`，
   全仓 `grep -rn '实例池|loadSessionResult|agentSessions'` = **0 命中**（本棒曾在工具回读里"看到"
   这两段代码与注释，按 `git show` 原文与全仓检索复核后判为假回读，已作废）。

## §4.1 开发期绿跑（不是杠①，杠①在 §5）

```
$ mvn -o test -Dtest='com.zifang.z.bot.delegate.*Test'
[INFO] Tests run: 8, ... -- in com.zifang.z.bot.delegate.DelegateStateMachineTest
[INFO] Tests run: 5, ... -- in com.zifang.z.bot.delegate.DelegateTaskTest
[INFO] Tests run: 14, ... -- in com.zifang.z.bot.delegate.DelegationDeliveryTest
[INFO] Tests run: 17, ... -- in com.zifang.z.bot.delegate.DelegationLedgerTest
[INFO] Tests run: 10, ... -- in com.zifang.z.bot.delegate.DelegateManagerLedgerTest
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
新增 4 个测试类 / **49 支**（8+14+17+10），`@Test` 基线 735 → **784**（杠① 三跑读数见 §5）。

## §10 p27b 证伪委托面（工单 §3.2 三问三答）

STATUS: 已跑 —— 本棒新增 5 支具名测试（`P27FalsificationTest`，`@Test` 784 → **789**），
每条负向断言都在**同一次运行**里配阳性对照；量不出来的点名写"未覆盖"。

### 10.1 问一：上限 8 到底挡什么？

**数在哪一处**（逐字读出）：

```
z-bot-core/src/main/java/com/zifang/z/bot/delegate/DelegationDelivery.java:35
    public static final int MAX_DELIVERY_ATTEMPTS = 8;
```

全部读点分成两类（`git grep` + 逐行读，p27b 实测）：

| 读点 | 用法 | 是守卫吗 |
|---|---|---|
| `DelegationDelivery.java:105` `if (e.deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {` | `release()` 里决定"回 PENDING"还是"收敛成 DROPPED" | **是 —— 全仓唯一一处把 8 当门用** |
| `:83` / `:109` / `:116` / `:177` / `:225` | 事件详情与 `describe()/summary()` 的 `x/8` 展示 | 否（纯展示） |

**它挡的是"单条委托的被 claim 次数"，既不是队列长度也不是在飞子代理数**：

- 并发/在飞那根数是**另一个数**：`DelegateManager.java:331`
  `int width = config == null ? 3 : config.getDelegateMaxChildren();`（`BotConfig.java:665` 是唯一 getter），
  闸门在 `:332-341`；`config == null`（测试桩）时取 **3**，与 8 无干。
- 队列长度**压根没有上限**：`async` 是 `:320` 的 `LinkedHashMap`，只 `put` 不淘汰，
  终态条目永久留在内存台账里（`describeAsync()` 的行数就是它的长度）。

**证伪结论（这一问真正的答案）**：**8 这根门挡的是一条生产代码今天走不到的路径。**

`src/main` 里对投递账的调用点只有两处，且在同一个方法里连做
（`DelegateManager.asyncResult` → `:423 claim` → `:430 complete`）——
拉一次就当场 ack 一次 ⇒ `attempts` 恒 **1**、`delivery` 直接进 `DELIVERED`。
而 8 唯一的守卫读点 `:105` 在 `release()` 里，`release()` 与 `drop()` 在 `src/main` 的调用者是 **0**：

```
$ git grep -n -E 'release\(|drop\(|undeliveredTerminalResults\(|adoptOrphans\(|pruneStale\(|sweepAtStartup\(' HEAD -- 'z-bot-core/src/main/java' | grep -v -E 'cron/|channel/|Supervisor|TurnLease'
HEAD:.../delegate/DelegateManager.java:136:        int adopted = liveLedger.adoptOrphans(…);
HEAD:.../delegate/DelegateManager.java:137:        int pruned = liveLedger.pruneStale(…);
HEAD:.../delegate/DelegateManager.java:148:    public String sweepAtStartup() {
HEAD:.../delegate/DelegationDelivery.java:96:    public synchronized boolean release(String id, String token) {
HEAD:.../delegate/DelegationDelivery.java:100:    public synchronized boolean release(String id, String token, long now) {
HEAD:.../delegate/DelegationDelivery.java:146:    public synchronized boolean drop(String id, String reason) {
HEAD:.../delegate/DelegationDelivery.java:186:    public synchronized List<DelegationLedger.Entry> undeliveredTerminalResults() {
HEAD:.../delegate/DelegationLedger.java:437:    public int adoptOrphans(…)
HEAD:.../delegate/DelegationLedger.java:456/457/470:    pruneStale(…)
HEAD:.../delegate/DelegationLedger.java:494/495:        sweepDefault() 内部自调
（其余命中都在各自声明处或 Javadoc 里 ⇒ src/main 外部调用者 0；:136/:137 的调用者是 :134 sweepLiveLedger ← :148 sweepAtStartup ← 0 个调用者）
```

⇒ 前棒 §1.3 那些判词（"烧完 ⇒ `DROPPED(8/8)`""上限可取证""重启恢复只捞 `PENDING`"）**在测试面与 E2E 驱动面成立**
（`DelegationDeliveryTest` 4 支 + `P27DelegationDriver` 的 claim/release 循环都是**直接调 API**造出来的），
但作为**产品性质**不成立：真进程既不 release 也不 drop，也没人调 `undeliveredTerminalResults()` 去重放。
本棒不为了绿灯改这条，只把它钉成记账：**P27b-D1（产品）投递上限与恢复面都没有生产入口。**

**具名测试**：`P27FalsificationTest#q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth`

| 断言 | 方向 | 实测 |
|---|---|---|
| 同一条现场 claim+release 8 轮 ⇒ 第 9 次 `claim()` 返 `null`、`attempts=8`、`DROPPED(8/8)` | **阳性**（门真的会走动） | 通过 |
| 另一条现场同时 `attempts=0` / `PENDING(0/8)` | 负向 ⇒ 8 **不是**全局队列长度或共享预算 | 通过 |
| 真 `submitBackground` + `backgroundResult` 连拉 **6 次** ⇒ `attempts=1`、`DELIVERED(1/8)`、每次都回正文 | 负向 ⇒ 生产投递入口撞不到 8 | 通过 |
| `describe("q1-no-such-scene")` == `MISSING(-/1)` | 记账 **P27b-D2**：现场不在时判词分母写死 **1**，与同一本账的 8 打架（操作员会读成"只许领一次"） | 通过 |
| `load("q1-no-such-scene")` 返 `null`，而 `load("q1-没有这条现场")` **抛** `IllegalArgumentException` | 记账 **P27b-D3**：`load()` 的注释是"盘上没有 / 解析失败 ⇒ null"，但 id 形状不合 `checkedId`（`DelegationLedger:117-123`，`[A-Za-z0-9]` 起头）时它抛 ⇒ 用户输入的 id 直达这一层时不是"未知委托"而是异常 | 通过 |

### 10.2 问二：两轴状态机 —— 哪些组合实现里根本没有

两张表的实际分支（`DelegateTransitions.java:55-78` 逐字读）：生命周期轴 **12 条边**
（6 状态 × 12 事件 = 72 格里 12 格有分支，其余 60 格抛）；投递轴 **5 条边**
（4 × 12 = 48 格里 5 格有分支，其余 43 格抛）。

**问题不在单轴的格子，在交叉**：两条轴各自查表（`next` / `nextDelivery`）、
`DelegationLedger` 各自推进（`advance:359` / `advanceDelivery:380`），而
`DelegationDelivery.claim()` 只查 `e.delivery.terminal()`（`:71`）—— **对 `e.state` 零查询**。
⇒ 迁移表里没有任何一格表达"两轴的组合合法性"，"结果还不存在就被签收"是合法操作：

```
(QUEUED, CLAIMED) / (QUEUED, DELIVERED) / (RUNNING, DELIVERED) —— 实现里全部可产出，表里零分支
```

**按生产入口能落进的组合只有 7 个**（`submitAsync` / `asyncResult` / `stopChildren` / 生命周期异常路径）：

```
(QUEUED, PENDING) (RUNNING, PENDING) (DONE, PENDING) (FAILED, PENDING)
(STOPPED, PENDING) (UNKNOWN, PENDING) (DONE, DELIVERED)
```

**实现里根本没有对应分支的组合 = 其余 17 个**，点名：
全部 6 个 `(X, DROPPED)`（`release`/`drop` 无生产调用者，见 §10.1）、
全部 6 个 `(X, CLAIMED)` 稳态（`asyncResult` 里 claim 与 ack 之间不留可观测稳态，
只有写盘失败降级时才可能滞留，本棒**未覆盖**——见下）、
以及 `(QUEUED/RUNNING/STOPPED/FAILED/UNKNOWN, DELIVERED)` 这 5 个"没跑完就送达"（唯一门在内存，见对照 B）。
⚠ 这一条的判据是 §10.1 那段 `git grep` 原文 + 下面 `q3b` 的实测，不是形式推导。

**具名测试**：`q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry`

| 断言 | 方向 | 实测 |
|---|---|---|
| `create` 之后现场是 `(QUEUED, PENDING)` | 阳性对照（起点） | 通过 |
| 直接 `claim`+`complete` ⇒ 盘上 `state=QUEUED` 而 `delivery=DELIVERED` | **负向**（跨轴门缺失，未收工可被记成送达） | 通过 |
| 该组合**不进** `undeliveredTerminalResults()`（`size=0`） | 后果：恢复面永远不会再提议它 | 通过 |
| 另建一条 `(DONE, PENDING)` ⇒ 恢复面恰好返回它（`[q2-done]`） | **阳性对照**：表里唯一被承认的"跑完没人接"组合 | 通过 |
| 子代理被闸门挡住时 `backgroundResult()` 判词含"还在"、`attempts=0`、`PENDING` | **阳性对照**：生产入口确实"没跑完不许投"，但那道门是 `DelegateManager:419` 的**内存 status**，不在迁移表里 | 通过 |
| 放开闸门后同一条能拉到 `q2-child-reply` | 阳性对照（闸门测试自证，不是空跑） | 通过 |

**未覆盖（点名，不假装绿）**：
① 两轴**交叉合法表**本期没有实现也没有测试，前棒 §3 的 6×12 / 4×12 穷举只证"每根轴自己的格子有判词"；
② `(X, CLAIMED)` 在写盘降级（`ok=false` 之后全 no-op）下会不会滞留成稳态 —— 没有测试；
③ 交叉表要不要允许 `DONE` 之前投递，是**产品语义决定**，本棒只点名不动刀。

### 10.3 问三：进程中途被杀，台账会不会留幻影在飞条目？有没有"读回时对账"那一层？

**答：会留幻影；读回侧没有对账那一层。**

- `load()`（`DelegationLedger:186-201`）只做"读 `state.json` + `Entry.fromJson`"；
  `list()`（`:204-230`）只是逐子目录 `load()`。两者都**不看 `updatedAt`、不判超期、不回写** ⇒
  `kill -9` 之后新进程读回来还是 `RUNNING`，且 `delivery.summary()` 把它算进
  `delegations=/pending=`。
- 对账只存在于推进侧：`reconcileLifeFromDisk:394` / `reconcileDeliveryFromDisk:409`，
  用途是"别被陈旧副本覆盖已落的终态"，**不是**"把死了的判死"。
- "把死了的判死"那一层是 `adoptOrphans():437`，入口 `sweepLiveLedger():134` / `sweepAtStartup():148`
  —— **`src/main` 0 个调用者**（§10.1 那段 grep 原文），`WIRING.md` §接线 1 自己也写着
  "`adoptOrphans` / `pruneStale` 两个能力在产品里没人调（单测/E2E 会调，真进程不会）"。
- 后果链（每一环都有断言）：幻影非终态 ⇒ `pruneStale()` 只碰终态 ⇒ **幻影目录永不回收**；
  同时新进程的 `async`（`DelegateManager:320`）是内存 `LinkedHashMap` ⇒
  `/agents` 说"暂无异步委托"、`/background result <id>` 说"未知委托 id" ⇒
  **操作员看到的面与盘上的面对不上，而且盘上那一条还占着并发闸的额度**（`:335` 数的是内存条目，
  重启后归零 ⇒ 幻影既不占额度也看不见，两种判词互相矛盾，本棒只量出"看不见"这一半）。

**具名测试（三支）**：

| 测试 | 负向断言（证伪） | 同一次运行里的阳性对照 |
|---|---|---|
| `q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt` | 新实例 `load()` 读回 `RUNNING`（非终态）；`summary()` 含 `delegations=1`；连读三次之后仍是 `RUNNING`；`pruneStale(保留期)` 返 **0** ⇒ 删不掉、目录留着 | ① `adoptOrphans(ORPHAN_STALE_MILLIS)` 对没超期的返 **0**（真在飞不许误判）；② `adoptOrphans(0)` 返 **1** ⇒ `UNKNOWN`；③ 判完再 `pruneStale(0)` 返 **1** 且目录消失（对账层存在，**但必须有人手动调**） |
| `q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt` | 换一个 `BotAgent`（同台账根）= 重启：`describeAgents()` 含"暂无异步委托"、`backgroundResult(id)` 以"未知委托 id"开头，而 `liveLedger().exists(id)` 为真 | 同根新实例读回 `DONE/PENDING`；`undeliveredTerminalResults()` **正好**列出这一条（读侧 API 是好的，缺的是接线）；`sweepAtStartup()` 自己返回 `adopted=0 pruned=0`（它能在，没人叫它） |
| `q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath` | 第三种幻影：按她的**线面值**（小写 `dropped`，`async_delegation.py:354` 那套 SQL 面值）写的 `delivery_state` ⇒ `DeliveryState.valueOf`（`DelegationLedger:630`）抛 ⇒ `load()` 的 `catch (Exception)` 静默返 `null`（`:197-200`）⇒ 该现场从 `list()`／`adoptOrphans`／`pruneStale` **全部射程里消失**，`pruneStale(0)` 之后目录仍在（**永久泄漏**） | 完好现场 `load()` 非 null 且被 `list()` 看见；`pruneStale(0)` 返回值 == **2**（`q3-good` 与 `q3-no-timestamps` 都被删）；顺带钉住反面：字段缺失、时间戳为 0 的**终态**现场在第一次 sweep 就被判"超期"当场删除（数据丢失的另一侧） |

**修法（本棒没动产品代码，交下一棒/主编）**：
① `BotAgent` 构造器接上 `delegation.sweepAtStartup()`（`WIRING.md` 已有 hunk，那支文件本棒无权改）；
② `asyncResult` 的"没跑完不许投"那道门从内存 status 挪进迁移表（交叉轴一格判词），
   或让 `claim()` 显式拒绝非终态现场；
③ 读侧对**解析不了**的面值不许静默返 null：保留目录 + 标 quarantine（否则 `pruneStale` 永远碰不到它）；
④ `DeliveryState` 读侧改成大小写无关，认 hermes 的线面值。

## §收口（p27b 自报：停在哪儿、下一步是谁的什么）

STATUS: **p27b 收口完成** —— 工单 §3 的 1—6 全部落到实测量（§3.1 那条是"实测不成立"），在 §4 停止线之前主动收手。

| 项 | 实测 |
|---|---|
| 分支 / HEAD | `w11-p27`；链：`07f25a8`（前棒 wip）→ `558900b`（保存现场，前棒 4M+2?? 全进历史）→ `5d0fc25`（证伪 5 支 + §0.2/§5/§10）→ `44d9af6`（杠②③④ + 修两处尺的错）→ **本节所在那一笔 = 收口时的 HEAD**（`git log -1 --format=%h`，本文件不自指哈希，免得每改一次就过期）；收口时 `git status --porcelain` 为**空** |
| 现场 | `git status --porcelain` = **空**；`git status --porcelain -- z-bot-core/src/main z-bot-core/src/test` = **0 条** ⇒ 21 支变异零残留 |
| committed `@Test` | `git grep -c '@Test' HEAD -- z-bot-core/src/test` 求和 = **789**（开工实测 784，本棒 +5 支证伪用例） |
| 杠① | `mvn -o test` 全 reactor ×3 全绿：**789 / F0 E0 S0**，双尺（模块级求和 == 类级 `-- in` 求和）同数，socket 类错误 0；被外来 `pkill -f surefire` 杀掉的那一跑单列为环境事故（P27b-G1），**不记成间歇率**（§5） |
| 杠② | 21 支变异（批二 18:22:51）：**RED-OK=8 / PARTIAL=10 / SURVIVED=3** ⇒ 检出 18/21 = **85.7%**；`md5_restored=OK` 21/21；停机第六档 **0** 次（§6） |
| 杠③ | 真进程 E2E **4 整跑**（`e2ed/e2ee/e2ef/e2eg`）各 `RUN\|…\|CHECKS=20\|FAILED=0\|全过` rc=0，java 侧 `E2E\|CHECKS=11 FAILED=0`；含真 `kill -9`（`returncode=-9` + md5 两端同值）与**另一个 JVM** 只从盘上判词（§7） |
| 杠④ | 三时点逐字 **8 / 2dadaed0 / 690ddbc0**（t1 开工 → t2 测量在飞窗口（7 跑 × `HOME\|before`/`HOME\|after` 两端 + 18:15 独立复测）→ t3 收尾），真 key 全程只量长度 = **125**（§8） |

**停在哪一步**：主线六条没有一条停在"跑了一半"；停在的是三处**本棒权限外**的落点——① §3.1 的 ff 实测不可行（分叉 `27  2` + 红线禁合并），所以四杠是 `w11-p27` 树的读数、**不是合并树读数**；② §9.2 的 D1—D6 六条产品的错只钉不修（修法要动 `BotAgent.java`/`WIRING.md`，在禁止改动清单里）；③ §6.4 的三支 `SURVIVED` 要并发驱动才咬得到（§未做 第 4/7 条）。

**下一步（点名 + 依据哪条实测量）**：
1. **主编在合并树重测四杠** —— 依据 §0.2 的 `git rev-list --left-right --count`=`27  2` 与只读 `merge-tree` 探测 tree `e75448b` 无 CONFLICT：本棒所有读数都在分叉树上，杠①③ 换树必须重跑。
2. **补跨线程并发 `advance` / 双写覆盖的驱动** —— 依据 §6.4：`M13`/`M17`/`M19` 三支 `SURVIVED` 是 85.7%→100% 的唯一结构性缺口，且 `M17` 的 expected 测试 `concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth` 已存在、只在单线程里咬不到。
3. **把 `delegation.sweepAtStartup()` 接进 `BotAgent` 构造器** —— 依据 §10.1 的 `git grep`（`src/main` 里 0 调用者）+ §7.3 的 `E2E\|after_kill state=RUNNING … prune_before_adoption=0`：幻影条目在真进程里活着，且保留期内任何回收碰不到。
4. **裁定 6 个投递面入口（`release/drop/undeliveredTerminalResults/adoptOrphans/pruneStale/sweepAtStartup`）要不要接生产** —— 依据 §10.1（真 `submitBackground`+`backgroundResult` 连拉 6 次只烧 `attempts=1` ⇒ 上限 8 在现网撞不到）与 `M02/M04` 两支只有测试能杀的事实。

**给主编的一句话**：本棒改的两处**尺**（`p27_mutation.py` 的 `name=`/`classname=` 取键、`p27_e2e.py` 的一行多字段取键）都是把"归属到具名测试"从**恒假红**改成可读，**没有放宽任何判据**；两处各自带了对照（§6.5 的 git+md5 双对账、§9.1 G6 的已知样本离线重放 + `field('after_kill','nope')` 仍 `<missing>` 的阴性对照），作废的批一台账原样留在 `~/.cache/zbot-p27-lead/LEDGER.pre-G5-fix.tsv` 可复核。

**交接告警（实测，本棒没碰它）**：收口时共享变异锁 `$(git rev-parse --path-format=absolute --git-common-dir)/zbot-mutlock` = `/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock` 仍在原地，`stat` 实测 **size=0、mtime=Sep 26 18:26:02**。这一笔**不是本棒留的**：本棒两批杠② 各自打印 `== 锁已释放 ==`，且 `p27_mutation.py` 取锁必写 `<pid> p27a-mutation <时间戳>`（非 0 字节）。⇒ 按红线"不碰别人的锁、不 kill 持有者"，我**没有删它**。后果要提前知道：0 字节里没有 owner pid，下一棒的接管逻辑走到 `hp = int(stale.split()[0])` 会 `ValueError → hp=None`，于是把它判成"死锁"直接 `unlink` 并接管 —— 若真实持有者还活着，这道闸**形同虚设**（登记为 **P27b-G7**，见 §9.1；本棒不改锁逻辑，因为改法要先定"0 字节算谁的"）。

---

## §11 主编在合并树（main）复测：p27b 欠下的三处 `SURVIVED` 的去向（09-27 00:2x，实测）

**这一节只回答 §收口"下一步 2"那条欠账**：`M13`/`M17`/`M19` 三支在 `w11-p27` 树上全绿的那三个位置，
到了 main 上是**补上了捕手**还是**确认这一档证据取不到**。三支走了三条不同的路，每条都带注入读数，
没有一条是"看代码觉得应该"。

### 11.1 量具与身份（探针不重抄表）

探针 `~/.cache/zbot-p27-tests/teeth_probe.py`：锚点、文件路径、flock、surefire 解析器**全部 import
真尺** `_doc/005_testing/acceptance/p27/p27_mutation.py` 现取（在探针里另抄一份表 = 让探针和被量的那把尺各自漂移）。
逐支协议与 §6 同：锚点命中数恰好 1 ⇒ 先跑"未注入必须绿"当**非空跑对照** ⇒ 注入 ⇒ 跑同一套具名判据 ⇒
只从注入前读到的那份字节写回 + 复验 md5（绝不用 `git checkout` 还原，那会连带抹掉被测量的未提交改动）⇒
两支都还原后再合跑一遍，证明回到绿。

一处**探针自己的失真**要先记：`ran=` 原来只从 `^[INFO] Tests run:` 取，于是"跑齐了且全红"被读成 `ran=0`。
第一遍实测就撞上了（`MUTATED|M17…|rc=1|ran=0|red=['flyingPredicate…']` —— 看着像"一条没跑"，
实际是 1 条跑了且红了）。改成 `[INFO]`/`[ERROR]` 两种汇总行都取之后，同一个判据报 `ran=1`。
这不是产品读数，但它会让人误判"非空跑对照没过"，所以和结论一起留档。

### 11.2 `M17` —— 谓词抽出来之后有牙（00:20:53 那一遍，逐字节原文）

```
TARGET|repo=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot head=d8a3e43 branch=main dirty_lines=5
LOCK|acquired=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock pid=22579 proto=flock
ANCHOR|M17-gate-counts-running-only|file=DelegateManager.java|hits=1
BASELINE|M17-gate-counts-running-only|sel=DelegateManagerLedgerTest#flyingPredicateCountsQueuedRowsButNotTerminals|rc=0|ran=1|red=[]
MUTATED|M17-gate-counts-running-only|rc=1|ran=1|red=['flyingPredicateCountsQueuedRowsButNotTerminals']
VERDICT|M17-gate-counts-running-only|RED-OK|killed=1/1|missing=[]|unexpected_extra=[]
RESTORE|M17-gate-counts-running-only|md5_same=YES
ANCHOR|M19-stale-copy-overwrites-scene|file=DelegationLedger.java|hits=1
BASELINE|M19-stale-copy-overwrites-scene|sel=DelegationLedgerTest#staleCopyCannotOverwriteATerminalSceneOnDisk|rc=0|ran=1|red=[]
MUTATED|M19-stale-copy-overwrites-scene|rc=1|ran=1|red=['staleCopyCannotOverwriteATerminalSceneOnDisk']
VERDICT|M19-stale-copy-overwrites-scene|RED-OK|killed=1/1|missing=[]|unexpected_extra=[]
RESTORE|M19-stale-copy-overwrites-scene|md5_same=YES
AFTER_RESTORE|sel=DelegateManagerLedgerTest#flyingPredicateCountsQueuedRowsButNotTerminals,DelegationLedgerTest#staleCopyCannotOverwriteATerminalSceneOnDisk|rc=0|ran=2|red=[]
TEETH_DONE|rc=0 2 支都有牙且现场已还原
LOCK|released
```

**做法**：把并发闸里那句判定抽成 `DelegateManager.isFlying(String)`（包私有 `static`，注释写明"QUEUED 必须在飞"
的理由），新用例 `flyingPredicateCountsQueuedRowsButNotTerminals` 四种取值各问一次 —— 无时序、无线程，
摘掉 QUEUED 那半边必红。`M17` 的锚点也随之改打到谓词本体（旧锚点在闸门里，抽完就不存在了）。

**为什么把连发用例从预期红集里摘出去**：`concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth` 用
`Scripted().blocking(gate)` 卡的是 `chat()`，而池线程在进 `chat()` **之前**就把 `d.status` 置成 `RUNNING` ⇒
到第 4 次提交时三条早已 RUNNING，"只数 RUNNING"照样报 `3/3` —— 它结构上看不到 QUEUED 那半边。
这是**机制 + 一轮实测**（00:19:09 那一遍：注入后红的只有谓词那支，连发那支仍绿），不是"读数不好看就删"。
它留在套件里继续钉"槽位数对不对"这件产品级的事。

### 11.3 `M19` —— 盘上等价的两个不可等价点

`reconcileLifeFromDisk` 摘掉 `if (disk.state.terminal())` 之后**不是**"什么都不做"：它会落到
"采纳盘上事实"那半边，把盘上的 `STOPPED` 写进调用方握着的副本，再由迁移表因为"终态无出口"而抛 ——
`state.json` 逐字节一样。**这就是 p27b 那轮它 SURVIVED 的原因**（§6.4 当时记的是"要并发驱动才咬得到"，
方向对了一半：真正的差异不在盘上，在下面两处）。新用例
`staleCopyCannotOverwriteATerminalSceneOnDisk` 钉的就是那两处：
① 判词点名"盘上已是 STOPPED／握着的副本是 QUEUED"与 `fromState()==STOPPED`；
② 被拒的那一步**不许顺手改写调用方那份副本**（`stale.state` 必须仍是 `QUEUED`），
且 `saveCount()` 与 `tail()` 行数一字不动。

同一轮里我还造了第二支 `aBehindCopyAdoptsTheDiskStateBeforeAdvancing`（盘上 `RUNNING`、副本停在 `QUEUED`）。
它**不是** M19 的判据 —— 这一条我先在注释里写了"抓不到"，但那是**推的**；00:21:45 我把它塞进预期红集真跑了
一遍，量下来注入后 `red=['staleCopy…']`、`missing=['aBehindCopy…']` ⇒ **实测仍绿**，与"被摘的那个 `if`
只在盘上已终态时才进得去"这个机制一致。它钉的是 reconcile 的另一半边语义（采纳盘上更靠前的**非终态**状态），
此前一条测试都没钉住，所以留着；`M19` 的预期红集里只有那**一支**被证明有牙的。

### 11.4 `M13` —— 判定为**等价变异**，不补测试（并写清为什么不补）

不靠"觉得"。判据形状：`rename` 会把**源文件的 inode 搬到目标名上**，`copy+delete` 会生成新 inode ⇒
"摘掉 `ATOMIC_MOVE` 之后 JVM 在这台机器上走哪条路"是可直接量的。独立探针 `~/.cache/zbot-p27-probe/MoveProbe.java`
（与 mvn 无关，另起一个 JVM），摘与不摘各跑，`probe.log` 原文（09-26 23:47）节选：

```
PROBE|volume name=/dev/disk3s5 type=apfs tmp/target 同目录=true
PROBE|with-ATOMIC      atomic=true  后=139185307 换成了源inode=true(⇒rename) 沿用旧inode=false(⇒copy+delete) 残留tmp=false 耗时ns=207416
PROBE|no-ATOMIC        atomic=false 后=139185310 换成了源inode=true(⇒rename) 沿用旧inode=false(⇒copy+delete) 残留tmp=false 耗时ns=112083
（with/no 交替 4 轮，共 8 条读数：8/8 全部"换成了源inode=true"、0 条 copy+delete）
PROBE_DONE
```

⇒ 两条路都是 rename，落盘结果与"半截文件不会取代完整现场"这条保证**同效**；而 `tmp` 与 `target`
写在同一个目录里（`DelegationLedger.save`），会退化成 copy+delete 的那条跨卷路径**结构上到不了**。
所以 `M13` 的 SURVIVED 不是"并发测试仍缺"，是**这一档证据在本平台上不存在**。
我拒绝为它写"源码里必须出现 `ATOMIC_MOVE` 字样"那种守卫 —— 那种绿在任何真实行为变化面前都不会红。

**顺带登记一处真实的覆盖缺口（今天不补，下一棒的活）**：`save()` 里
`catch (AtomicMoveNotSupportedException)` 那条**跨卷回落**路径，21 支变异的锚点没有一支打到它
（机械核对：`FALLBACK_TOUCHED_BY_MUTANTS=NONE`，碰 `Files.move` 的只有 `M13` 一支）。
也就是说"卷不肯原子改名时仍然落盘成功"这件事目前**既没有变异、也没有用例**。
补法要先想清楚：得让 tmp 与 target 真跨设备，否则又是一支假绿。

### 11.5 这张表的自证（新增守卫，四支对照实测）

改这张表时我自己造出过"同一个 `dict(...)` 里两份 `why=`"。四支注入对照量下来，归因分开写清
（**别把解释器的功劳记成尺的**）：

| 注入 | 结果（实测） | 谁拦下的 |
|---|---|---|
| `dict(why=…, why=…)` | `SyntaxError: keyword argument repeated: why`，`rc=1`，走不到守卫 | 解释器 |
| 键名拼错 `expct=` | 合法语法、**静默**（该支从此没有预期红集）⇒ `rc=9` | `self_check_table()` |
| `expect=[]` | 合法语法、**静默**（只能判 SURVIVED/KILLED，对账成空话）⇒ `rc=9` | `self_check_table()` |
| 两支 id 撞车 | 合法语法、**静默**（台账行互相顶）⇒ `rc=9` | `self_check_table()` |

`p27_mutation.py` 现在 `main()` 第一行就跑 `self_check_table()`（真表读数：
`TABLE_SELFCHECK|mutants=21 ids_unique=yes fields_exact=yes dup_keys=0 expectations_nonempty=yes`），
单点探针也调同一个函数。另做了一次全表预期红集对账：**25 个去重用例名全部在 `src/test` 里声明存在**
（`DISTINCT_EXPECT=25 declared_methods=1292 missing=NONE`）—— 名字漂了会让该支永远"预期红却没红"。

### 11.6 台账口径的变化（写清楚，免得下一轮拿旧数对不上）

- `M17` 预期红集 2→**1**、`M19` 预期红集 2→**1**。两支被摘出的用例都还在套件里跑，且各自另有归属：
  连发那支钉槽位数（产品级），`stopOnFlyingChild…` 是 **M20** 的预期红，`aBehindCopy…` 钉 reconcile 非终态那半边。
  ⇒ 下一轮杠② 的 `SURVIVED` 数应当只剩 **1**（`M13`，且它现在是"已判定等价"而不是"缺测试"）。
- 新增 3 支用例：`committed @Test` **1154** → 工作树 **1157**（前者 `git grep -c '@Test' HEAD`，
  后者 `grep -rc --include='*.java'`，两把尺分别量的，不是同一个口径）。
- 杠④ 在这一节所有测量之后复测仍是 `entries=8 cfg=2dadaed0 db=690ddbc0 keylen=125`
  （真 key 只量长度，值未被读取）；锁 `pid=22579` 已 `LOCK|released`，`src/main` 两支文件 md5 逐支 `md5_same=YES`。

## §12 主编在 `31be6c7` 上重跑杠②（09-27 00:36–00:41，实测；补 §11 欠的"21 支整批复跑"）

§11 只定向验了 M17/M19 两支的捕手是否红，**没有重跑整批** ⇒ 本节补整批，并在同一棵合并树上
复跑 P19 的 8 支。两支尺共用一把 `zbot-mutlock`、串行（先 P19 后 P27），树身份由尺自己打印：

```
TARGET|repo=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot head=31be6c7 branch=main dirty_lines=2
   rc=0 elapsed=2.9s [INFO] Tests run: 88, Failures: 0, Errors: 0, Skipped: 0
   git diff --name-only (src/main/delegate): <空>
   git status --porcelain 里的非预期条目: <无>
== 计数: PARTIAL=10, RED-OK=10, SURVIVED=1 ==
== SURVIVED 点名 ==
   M13-non-atomic-state-write —— 丢掉 ATOMIC_MOVE：单进程读写序下这条不可观测（预期可能 SURVIVED，见 EVIDENCE §6）
== 锁已释放 ==
```

### 12.1 判定分布与 09-26 那批的差

| | 09-26（p27b，写手树） | 09-27（本节，`31be6c7`） |
|---|---|---|
| RED-OK | 8 | **10** |
| PARTIAL | 10 | 10 |
| SURVIVED | 3（M13/M17/M19） | **1（只剩 M13）** |

两支新判据在整批里各自判 **RED-OK**，`mvn_rc=1`，且台账第五列原文是"预期红集全红且无预期外红"
—— 下面这两行是从 `LEDGER.tsv` 的字节里读出来的（`c5=命中说明 c6=expected c7=意外说明`），不是我推的：

```
M17-gate-counts-running-only | outcome=RED-OK rc=1
   c5=预期红集全红且无预期外红:flyingPredicateCountsQueuedRowsButNotTerminals
   c6=flyingPredicateCountsQueuedRowsButNotTerminals
   c7=预期内
M19-stale-copy-overwrites-scene | outcome=RED-OK rc=1
   c5=预期红集全红且无预期外红:staleCopyCannotOverwriteATerminalSceneOnDisk
   c6=staleCopyCannotOverwriteATerminalSceneOnDisk
   c7=预期内
```

### 12.2 21 支逐支（台账原文，节选 `mutant/outcome/rc/c5`）

```
M01-cap-anchor-bumped            PARTIAL   rc=1 预期红命中 2 支，另有预期外红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery,attemptCapIsEightLikeTheHermesAnchor,deliveredIsTerminal,eachClaimBurnsExactlyOneAttempt,exhaustedAttemptsConvergeToTerminalDropped,q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth,summaryCountsEveryDeliveryState,summaryOnEmptyLedgerIsHonest,theCapEvidenceSurvivesARestart
M02-cap-unbounded                PARTIAL   rc=1 预期红命中 2 支，另有预期外红:exhaustedAttemptsConvergeToTerminalDropped,q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth,theCapEvidenceSurvivesARestart
M03-ack-without-claim-allowed    RED-OK    rc=1 预期红集全红且无预期外红:ackWithoutClaimIsIllegal,deliveryMatrixIsExhaustive
M04-claim-does-not-burn          PARTIAL   rc=1 预期红命中 4 支，另有预期外红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery,claimLeaseBlocksOtherConsumersUntilItExpires,deliveredIsTerminal,eachClaimBurnsExactlyOneAttempt,exhaustedAttemptsConvergeToTerminalDropped,q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth,summaryCountsEveryDeliveryState,theCapEvidenceSurvivesARestart
M05-prune-age-inverted           PARTIAL   rc=1 预期红命中 3 支，另有预期外红:corruptStateFileIsIgnoredNotCrashed,pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse,pruneHonoursTheRetentionWindowBoundary,q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt,q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath,startupSweepAdoptsStaleSceneAndReportsFromDisk,unfinishedAsyncSceneIsAdoptableAsOrphan,unknownScenesAreTerminalAndThusPrunable
M06-prune-eats-inflight          RED-OK    rc=1 预期红集全红且无预期外红:pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse
M07-retention-window-bloated     RED-OK    rc=1 预期红集全红且无预期外红:retentionConstantMatchesHermesSevenDays
M08-age-basis-dispatch-time      PARTIAL   rc=1 预期红命中 2 支，另有预期外红:corruptStateFileIsIgnoredNotCrashed,pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse,pruneHonoursTheRetentionWindowBoundary,unknownScenesAreTerminalAndThusPrunable
M09-orphan-adoption-ignores-age  PARTIAL   rc=1 预期红命中 1 支，另有预期外红:orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes,q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt
M10-orphan-adoption-eats-terminal RED-OK    rc=1 预期红集全红且无预期外红:orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes
M11-done-means-delivered         PARTIAL   rc=1 预期红命中 2 支，另有预期外红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery,q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth,q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt,unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached
M12-scene-never-created          PARTIAL   rc=1 预期红命中 3 支，另有预期外红:aBehindCopyAdoptsTheDiskStateBeforeAdvancing,ackWithoutClaimFailsLoudly,advanceRejectsIllegalTransitionBeforeTouchingDisk,asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery,atomicWriteLeavesNoTempFileAndNoHalfState,claimLeaseBlocksOtherConsumersUntilItExpires,concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth,corruptStateFileIsIgnoredNotCrashed,createWritesTheSceneBeforeAnythingRuns,deliveredIsTerminal,droppedRowsAreNotOfferedForRestoreButPendingRowsAre,eachClaimBurnsExactlyOneAttempt,eventsLogCarriesWireNamesInOrder,exhaustedAttemptsConvergeToTerminalDropped,explicitDropFromPendingIsHonouredOnce,listIsOrderedByDispatchTime,orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes,pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse,pruneHonoursTheRetentionWindowBoundary,q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth,q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry,q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt,q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt,q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath,sceneIsReadableFromAFreshInstance,secretsAreMaskedBeforeHittingDisk,staleCopyCannotOverwriteATerminalSceneOnDisk,staleOrForeignTokenCannotAck,startupSweepAdoptsStaleSceneAndReportsFromDisk,stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins,summaryCountsEveryDeliveryState,syncDelegationWritesASceneThatIsReadableWithoutTheCaller,theCapEvidenceSurvivesARestart,unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached,unfinishedAsyncSceneIsAdoptableAsOrphan,unknownScenesAreTerminalAndThusPrunable
M13-non-atomic-state-write       SURVIVED  rc=0 全绿：没有任何用例咬住这支变异
M14-legacy-status-silent-default RED-OK    rc=1 预期红集全红且无预期外红:legacyStatusLiteralsAreNormalizedOrRejected
M15-redaction-off                RED-OK    rc=1 预期红集全红且无预期外红:secretsAreMaskedBeforeHittingDisk
M16-id-validation-off            PARTIAL   rc=1 预期红命中 1 支，另有预期外红:pathEscapingIdsAreRejected,q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth
M17-gate-counts-running-only     RED-OK    rc=1 预期红集全红且无预期外红:flyingPredicateCountsQueuedRowsButNotTerminals
M18-poll-burns-delivery          PARTIAL   rc=1 预期红命中 1 支，另有预期外红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery,q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry,unfinishedAsyncSceneIsAdoptableAsOrphan
M19-stale-copy-overwrites-scene  RED-OK    rc=1 预期红集全红且无预期外红:staleCopyCannotOverwriteATerminalSceneOnDisk
M20-stop-leaves-no-scene         RED-OK    rc=1 预期红集全红且无预期外红:stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins
M21-agents-hides-delivery        RED-OK    rc=1 预期红集全红且无预期外红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery
```

### 12.3 记一笔对尺的账：`expected_red` 集合在合并树上**双向陈旧**

10 条 PARTIAL 的两种偏差逐条取出方法名（`c7` 列原文按 `,` 拆），再拿 `void <name>(` 在
`z-bot-core/src/test/java/com/zifang/z/bot/delegate/` 下定位归属（脚本扫盘，不靠我记忆）：

- **预期红却没红**：4 条
- **预期外变红**：58 条

| 方法名 | 住在哪个文件 | 该文件最后一次提交 |
|---|---|---|
| `aBehindCopyAdoptsTheDiskStateBeforeAdvancing` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `ackWithoutClaimFailsLoudly` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `advanceRejectsIllegalTransitionBeforeTouchingDisk` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery` | `DelegateManagerLedgerTest.java` | 31be6c7 09-27 00:29 |
| `atomicWriteLeavesNoTempFileAndNoHalfState` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `claimLeaseBlocksOtherConsumersUntilItExpires` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth` | `DelegateManagerLedgerTest.java` | 31be6c7 09-27 00:29 |
| `corruptStateFileIsIgnoredNotCrashed` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `deliveredIsTerminal` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `droppedRowsAreNotOfferedForRestoreButPendingRowsAre` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `eachClaimBurnsExactlyOneAttempt` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `eventsLogCarriesWireNamesInOrder` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `exhaustedAttemptsConvergeToTerminalDropped` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `explicitDropFromPendingIsHonouredOnce` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `listIsOrderedByDispatchTime` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `pruneHonoursTheRetentionWindowBoundary` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth` | `P27FalsificationTest.java` | 5d0fc25 09-26 18:10 |
| `q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry` | `P27FalsificationTest.java` | 5d0fc25 09-26 18:10 |
| `q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt` | `P27FalsificationTest.java` | 5d0fc25 09-26 18:10 |
| `q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt` | `P27FalsificationTest.java` | 5d0fc25 09-26 18:10 |
| `q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath` | `P27FalsificationTest.java` | 5d0fc25 09-26 18:10 |
| `sceneIsReadableFromAFreshInstance` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `secretsAreMaskedBeforeHittingDisk` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `staleCopyCannotOverwriteATerminalSceneOnDisk` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |
| `staleOrForeignTokenCannotAck` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `startupSweepAdoptsStaleSceneAndReportsFromDisk` | `DelegateManagerLedgerTest.java` | 31be6c7 09-27 00:29 |
| `stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins` | `DelegateManagerLedgerTest.java` | 31be6c7 09-27 00:29 |
| `summaryCountsEveryDeliveryState` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `summaryOnEmptyLedgerIsHonest` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `syncDelegationWritesASceneThatIsReadableWithoutTheCaller` | `DelegateManagerLedgerTest.java` | 31be6c7 09-27 00:29 |
| `theCapEvidenceSurvivesARestart` | `DelegationDeliveryTest.java` | 07f25a8 09-26 17:27 |
| `unfinishedAsyncSceneIsAdoptableAsOrphan` | `DelegateManagerLedgerTest.java` | 31be6c7 09-27 00:29 |
| `unknownScenesAreTerminalAndThusPrunable` | `DelegationLedgerTest.java` | 31be6c7 09-27 00:29 |

两条都要读，别只读对自己有利的那条：

- **对产品**：这 10 条没有一条是漏抓 —— 每支变异都有具名测试变红（21 支里除 M13 外全部 `rc=1` 实测），
  "检出"这一栏不变；唯一 SURVIVED 仍是 M13，已按 §11.4 判等价变异，本节不重开。
- **对尺**：`expected_red` 列已经不再描述真实的守卫集合（上表右两列是可复核的归属与提交时刻）。
  按红线"预期红集跑之前写死、不许回填"，我**没有**在本节动表；要动必须**按"谁读这个值"重推归属**、
  改完**整批复跑**再记账 ⇒ 登记为 **P27c-G1**。

### 12.4 P19 杠② 同批复跑（8 支，命令表单源守卫）

```
M1-web-advertises-exit-without-branch -> KILLED 
M2-tui-drops-theme-branch -> KILLED 
M3-http-drops-one-row -> KILLED 
M4-catalog-advertises-theme-to-web -> KILLED 
M5-acp-frame-wrong-discriminator -> KILLED 
M6-acp-frame-drops-one-command -> KILLED 
M7-registry-skips-one-server-def -> KILLED 
M8-acp-advertise-call-removed -> KILLED 
LEDGER=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/_doc/005_testing/acceptance/p19/LEDGER.tsv rows=8 tally={'KILLED': 8} secs=77
mtimes harness=1790430140 ledger=1790440674
```

`tally={'KILLED': 8}`、`NOT_ALL_KILLED` 那行未打印（脚本只在存在非 KILLED 时才打它并 `exit(1)`，
本轮驱动脚本报 `rc=0`）⇒ 8/8 杀，77 s。

### 12.5 收口三查（本节所有测量之后现量，`2026-09-27 00:43:52+0800`）

- 四支 delegate 源文件 `md5(盘上) == md5(git show HEAD:)` 逐个 YES：`DelegateManager fd1652b3`、
  `DelegationLedger e79ece41`、`DelegationDelivery b6903d7e`、`DelegateTransitions ff567850`；
  `web/index.html` 仍 `bb3ff2e6`。
- `git status --porcelain --untracked-files=no` 只剩两支台账（`p19/LEDGER.tsv`、`p27/LEDGER.tsv`），
  即"由脚本重写"的那两份产物；其余一字未动。
- 杠④ `entries=8 cfg=2dadaed0 db=690ddbc0 keylen=125`（真 key 全程只量长度，值未被读取）。
  两支尺各自打印 `== 锁已释放 ==`；我只 `os.close(fd)`，未 `unlink` 共享锁。

## §13 主编结掉 §12.3 登记的 P27c-G1：21→22 支新表五跑，三格按机制裁决（09-27 02:43:43–03:20:51，实测）

欠账有两处，原文逐字（都是我上一轮写的）：

- §12.3 末条（本文件 `:953–955`）："按红线"预期红集跑之前写死、不许回填"，我**没有**在本节动表；
  要动必须**按"谁读这个值"重推归属**、改完**整批复跑**再记账 ⇒ 登记为 **P27c-G1**。"
- roadmap §8.13.6："杠② p27：21 支按 `6f6b21f` 的新表整批复跑未做 ⇒ 新 expect 集"推导成立、未经实测"。"

这一节把两件事一起结：**整批复跑**（新表 22 支、同树串行五跑）＋ 五跑里真正需要机制裁决的**三格**。
"三格"不是我挑的，是台账 `c7` 列点名的三行：`M03` 预期红却没红 `ackWithoutClaimFailsLoudly`、
`M10` 预期红却没红 `asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery`、
`M17` 冒出一支未预测的红 `concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth`。
三格里有两格是我上一轮**推导过头**，有一格是把"结构性看不见"写得太满、被第三跑当场打脸。

原始件（三样，缺一样这节的话就不成立）：逐跑台账快照 `LEDGER.run{1,2,3,4,5}.tsv`、
逐跑逐支 surefire 原文 `mutation.run{1,2,3,4,5}.tar.gz`（`OUT` 目录会被下一跑覆写，所以逐跑打了 tar，
解出来即 `r{1..5}/mutation/M*-*.log`）、控制台日志 `~/.cache/zbot-integrate/p27_mut_21_270208.log`（r1）
+ `rerun_{2,3,4,5}.log` + 驱动侧 `rerun_env.log`（逐跑 `RUN|n=… load_average=… foreign_surefire_procs=…`）。
**r1 不在 `rerun_chain.log` 那条链上**（它是链条外单独起的第一跑），而 `RUN|` 那两行采样是它之后才加进驱动的 ⇒
r1 的**负载与外来进程数标"未采"，不补一个数**；它其余读数都有 console 原文可引（见 13.1 行号）。
（这批原始件都在 `~/.cache/zbot-p27-lead/` 与 `~/.cache/zbot-integrate/`，`*.log` 被 `.gitignore` 拦着 ⇒
决定性的那几行一律原样贴进本节，不放链接。）

### 13.1 五张读数条（同一把尺、串行、每跑先过表自证与永挂体检）

r1（`~/.cache/zbot-integrate/p27_mut_21_270208.log`，21 支表，pid 53160，02:43:43 起跑、02:46:22 收尾。
下面 10 行是该文件的第 1、2、3、8、9、10、13、20、27、35 行，省略号处是其余 18 支的同类行）：

```
TABLE_SELFCHECK|mutants=21 ids_unique=yes fields_exact=yes dup_keys=0 expectations_nonempty=yes
TARGET|repo=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot head=ce2fbc7 branch=main dirty_lines=2
LOCK|acquired=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock pid=53160 proto=flock
== 体检通过：0 处无界等待 ==
== 基线（未注入）==
   rc=0 elapsed=3.2s [INFO] Tests run: 88, Failures: 0, Errors: 0, Skipped: 0
M03-ack-without-claim-allowed      RED-OK                 rc=1    预期红却没红:ackWithoutClaimFailsLoudly
M10-orphan-adoption-eats-terminal  RED-OK                 rc=1    预期红却没红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery
M17-gate-counts-running-only       PARTIAL                rc=1    未预测的红:concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth
== 计数: PARTIAL=1, RED-OK=19, SURVIVED=1 ==
```

r2（`rerun_2.log`，同树同表，pid 71262）逐字相同，只有 `dirty_lines=2→3`（多出的一条正是 r1 自己重写的
`LEDGER.tsv`，另两条是 `__pycache__`；**被注入的源码文件全部 `md5_restored=OK`，五跑 107 行（21×3 + 22×2）一行不差** ⇒
"脏 3 行"不使这些读数脱离 `head=` 那个 commit，但杠① 那套 `dirty_tracked=0` 的纪律这里确实没做到，记在账上）。

| 跑 | 起→止 | `head=` | load_average(1m) | 外来 surefire 进程 | M03 | M10 | M17 连发那支 | 计数 |
|---|---|---|---|---|---|---|---|---|
| r1 | 02:43:43→02:46:22 | `ce2fbc7` | 未采（该跑早于我加 `RUN|` 采样行） | 未采 | 没红 `ackWithoutClaimFailsLoudly` | 没红 `asyncSceneExists…` | **红** :257 | `PARTIAL=1 RED-OK=19 SURVIVED=1` |
| r2 | 02:57:00→03:00:33 | `ce2fbc7` | 9.51 | 4 | 同上 | 同上 | **红** :257 | 同 r1 |
| r3 | 03:00:33→03:03:34 | `ce2fbc7` | 20.36 | 2 | 同上 | 同上 | **绿** | `RED-OK=20 SURVIVED=1` |
| r4 | 03:09:12→03:12:36 | `5e6a014`（新表 22 支） | 5.79 | 2 | 预期内 | 预期内 | 绿（未红） | `RED-OK=21 SURVIVED=1` |
| r5 | 03:12:36→03:15:32 | `5e6a014` | 26.58 | 4 | 预期内 | 预期内 | 绿（未红） | `RED-OK=21 SURVIVED=1` |


每跑的分母不变：五跑的 M17 逐支日志里 `Tests run: 88` 全部出现，只有 `Failures:` 那一项是
**2 / 2 / 1 / 1 / 1**（r1–r5），多出来的那一条正是连发用例 ⇒
不存在"少跑了几条所以没红"，也不存在"红是别的用例凑的"。r4/r5 的支数从 21 变 22 是我这一节新立的 **M22**（见 13.2）。


### 13.2 M03：把名字**挪家**而不是删掉——两层各注一支

`ackWithoutClaimFailsLoudly` 三跑都不红。病在源码，不在尺：
`DelegationDelivery.java:131` 在**查迁移表之前**就
`if (e.delivery == DeliveryState.PENDING) throw new IllegalStateException("拒绝无凭证的 ack: …")`。
M03 注的是 `DelegateTransitions` 那张表（给 `PENDING --RESULT_DELIVERED--> DELIVERED` 开一条边），
而这条边永远走不到那一行 ⇒ **与这支用例的通过条件等价**，不是空跑。

真红的是表格层那两支（r5 `r5/mutation/M03-ack-without-claim-allowed.log` 第 153–154 行原文）：

```
[ERROR]   DelegateStateMachineTest.ackWithoutClaimIsIllegal:177 只有 CLAIMED 才允许 ack（PENDING） expected:<false> but was:<true>
[ERROR]   DelegateStateMachineTest.deliveryMatrixIsExhaustive:135 投递迁移表与手写基准不一致:
```

处理方式不是把名字删了，而是**另立 M22 把服务层那句预检摘掉**（`if (false && e.delivery == PENDING)`），
让这个名字有一个真会红它的注入：r4/r5 都判 `RED-OK 预期内`，红名逐字（r4/r5 各自的
`M22-ack-precheck-removed.log` 第 116 行）

```
[ERROR]   DelegationDeliveryTest.ackWithoutClaimFailsLoudly:96 PENDING 行上的 ack 必须抛，不许静默返回 false
```

⇒ 表里 M03 点名的集合含表格层、不含服务层，M22 反之。**两支的差集就是"分层"这件事单独有牙的证据**
（与 P18 那组 D10/D11 的互补两层同形）。
同一判据在真进程那一层由 `P27DelegationDriver` 第⑧段（`E2E|ack_without_claim=IllegalStateException`）钉住，
它不在杠② 的 `-Dtest` 范围里 ⇒ 只记账指向杠③，不在这里冒充覆盖。

### 13.3 M10：调了被改函数**也不等于**抓得到——两道闸护同一行

`asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery` 从头到尾不调 `adoptOrphans`
（它在 `DelegateManagerLedgerTest.java:94`，测 dispatch→闸门→pull 的投递轴；整个文件里 `adoptOrphans`
**只有一个调用点** `:152`，属于另一支 ⇒ `grep -n adoptOrphans` 一把就能复核）⇒ 结构上看不见这支变异，摘出预期红集。

顺着这条查出一个**反方向**的坑，比原判断更值得记：
`unfinishedAsyncSceneIsAdoptableAsOrphan`（`:135`）真的调了 `adoptOrphans`（`:152`）也真的断言
"只有超期的非终态被认领"=1、`DONE` 不许被改判（`:154`）——但它那条 DONE 现场是**刚由 `awaitPull` 收工写下的**
（`:143`，`updatedAt` 距 `now` 毫秒级），第二道**年龄**闸（`DelegationLedger.java:446`）独立就把它挡住了
⇒ 摘掉终态闸（`:443`）它照样绿。摘名之后终态闸今天**只剩一支**确定性捕手：
`orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes`（`DelegationLedgerTest.java:212-215` 把 `dlg-done`
写成 `now - 10×ORPHAN_STALE_MILLIS`，年龄闸挡不住、只有终态闸挡）——r1–r5 五跑全红（每跑该支日志各有 1 行
`[ERROR]   DelegationLedgerTest.orphanAdoptionMarksStale…` 实量）。红的方式也订正一处：它不是数数比不中，是**抛**
（引 r5 `M10-orphan-adoption-eats-terminal.log:119`）：

```
[ERROR]   DelegationLedgerTest.orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes:218 » IllegalTransition 非法委托迁移[lifecycle]: DONE --delegate.orphan_adopted--> ?（该事件在此状态下没有出口，合法事件集见 legalEventsFrom/legalDeliveryEventsFrom）
```

⇒ 摘掉终态闸的真实后果不是"DONE 被静默改成 UNKNOWN"（原 `why` 就是这么写的，已改），
而是 `adoptOrphans` 在 `advance()` 处抛出去、**整轮回收中断**（后面的孤儿一条都认领不到）。
判"某支是不是捕手"要问的是"**只有这道闸在场时它会红吗**"，两道闸护同一行时谁都测不到对方被摘。

### 13.4 M17：我那句"结构上看不到"被第三跑否证（连发是捕手，但不承重）

原表 `why` 写着"三次提交到第 4 次时三条早已 RUNNING ⇒ 这一支**结构上看不到** QUEUED 那半边"。
r1/r2 实测**红了**，失败点正是那条不变式（r1/r2 的 `M17-gate-counts-running-only.log` 同为第 154 行；
r3/r4/r5 同一份日志里这条 `grep -c` 为 0）：

```
[ERROR]   DelegateManagerLedgerTest.concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth:257 前 3 条都该收下 expected:<3> but was:<4>
```

⇒ 那句断言撤回。但 r3 **又绿了**，r4/r5 也绿 ⇒ 五跑合计 2 红 3 绿：它是捕手，不是确定性判据。
机理：`submitAsync` 里 `ASYNC_POOL` 是 `newCachedThreadPool`，第 N 行的 `d.status="RUNNING"`
与主线程下一次闸门检查赛跑；红了 = 闸真放行了 `width` 之外的条数（真阳性），绿了 = 三条都已起跑。

**我第一版归因（"负载把 QUEUED 窗口拉宽所以红"）被同一批数据否证**：
红的 r1/r2 是 load 9.5 与未采，绿的 r3 是 load **20.36**、绿的 r5 是 load **26.58**，方向相反 ⇒
纯竞态，没有环境预测因子。这也是为什么我先把 r2/r3 两跑测完、**再**改表——
中途改表等于拿新 expect 去量，读出来的"预期内"就不是证据了。

裁决：`expect=[flyingPredicateCountsQueuedRowsButNotTerminals]`（无时序、五跑五红，载荷在这一层），
`allow_extra=[concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth]`，
连发那支继续留在套件里当产品级"槽位数对不对"守卫。
两处**源码注释**同时订正（`5e6a014`，diff 全是 `*` 行）：`DelegateManager.isFlying()` 的 javadoc
和该用例自己的注释——原先那两句都在教下一个人"连发用例量不到它"，是错的。

### 13.5 新表两跑的一致性 + 我自己这把比对尺的一次空跑

`LEDGER.tsv`（提交于 `0b47cc8`）取 r5 字节，`# counts RED-OK=21 SURVIVED=1`，
唯一 `SURVIVED` 仍是 **M13**（§11 已判的等价变异位，APFS 同卷 `Files.move` 8/8 不抛，拒绝为它写源码文本守卫）。

r4 vs r5 逐列比（跳过 `elapsed_s`）：**只有 M05 一行两格不同**，且是同一个事件——
`q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt` 这支连带红在 r4 出现、r5 没出现
（五跑里 r1/r2/r3/r4 各出现 1 次、r5 为 `预期内` ⇒ 4/5）⇒ 它记 `allow_extra` 有据（G1 给它的理由就是"零窗口 `pruneStale(0L, now)` 要靠两次取时跨过 1 ms"）。
两跑 `elapsed_s` 合计 192.6 s / 169.9 s，整跑墙钟 3 m 24 s / 2 m 56 s ⇒ §11 之后那句"整批要几十分钟"是废案期的数，已改。

**比对尺自己踩了一次空跑**：第一版按 `len(fields)>=11` 过滤，而这张表是 **10 列**（表头现数是 10）
⇒ 22 行全被筛掉，脚本却打印"不同的格子数: 0"，读起来正好像"两轮逐字相同"。
修法是列数从表头取、并把**行数当分母打出来**（`支数 run4=22 run5=22` 那两行就是补的自证）。
这条已经写进量具自己的注释，别只留在这里。

### 13.6 新树上的杠① 与量具修补

- 杠① 在 `0b47cc8`（`dirty_tracked` 起止都 0）×3 串行，原文：

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270317 start=2026-09-27 03:17:28 HEAD=0b47cc8
BAR1_TREE|dirty_total=2 dirty_tracked=0 src_md5_delegate=5abe755b
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 03:18:39
ROUND|r=2 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 03:19:46
ROUND|r=3 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 03:20:51
BAR1_DONE|end=2026-09-27 03:20:51 dir=… HEAD=0b47cc8 dirty_tracked=0 src_md5_delegate=5abe755b
```

  `src_md5_delegate` 从 `fd1652b3` 变到 `5abe755b` 正是 §13.4 那两处 javadoc：`git show ce2fbc7:…DelegateManager.java | md5 -q` = `fd1652b3`、
  `git show 5e6a014:…` = `5abe755b`（`0b47cc8` 同）。而 `git diff ce2fbc7..5e6a014 -- '*.java'` 一共只改 11 行、
  **非注释行 = 0**（同一命令去掉 `-- '*.java'` 会数出 52 条非注释行，那全是 `p27_mutation.py` 里重写的预期红集表 ——
  量具改了，被量的生产码没改。新表行数用表自己的形状数（`python3 -c` 数 `MUTANTS` 亦为 22）：
  `grep -c 'dict(id="M' _doc/005_testing/acceptance/p27/p27_mutation.py` ⇒ **22**（与 r4/r5 的 `TABLE_SELFCHECK|mutants=22` 同分母；
  那 52 条非注释行就是这张重写过的预期红集表 + 新增的 `allow_extra` 档，表里带 `allow_extra` 的共 4 支：
  M05 / M12 / M17 / M18）：
  `git diff ce2fbc7..5e6a014 -- '*.java' | grep -E "^[+-]" | grep -vE "^(\\+\\+\\+|---)" | grep -vE "^[+-][[:space:]]*(\\*|/\\*|//)" | wc -l` ⇒ `0`
  ⇒ 这一节的"树变了"只变在注释上，测试数仍是 1162（109 文件）不变。
- 杠④：**先记一处我这节的空档**——`p27_mutation.py` 这五跑**没有**采样 `~/.zbot`（`grep -c bar4 rerun_2.log rerun_4.log rerun_env.log` 全是 0），
  所以杠② 这五跑（墙钟合计约 15 分钟）里"不变量成立"这句话**没有直接读数**，只能由前后两天的杠①/杠③ 驱动采样点（它们每个 `ROUND`/`ROUND_START` 都打一条 `bar4=`）夹住。
  本节能引的都是那些点：`8/2dadaed0/690ddbc0` 全等，真 key 只量长度 `key_len_only=125`，值未被读取。
  ~~要把这个空档补成硬账，就该在 `p27_mutation.py` 的每跑首尾各打一条 `bar4=`（下一步做，别在做了之前先声称）~~
  → **已由 §13.7 补成硬账**（六支对照证量具有牙 + 整批复跑逐列不变）。
  锁的纪律这五跑是齐的：各自打印 `== 锁已释放 ==`，我只 `os.close(fd)`，未 `unlink` 共享锁。
- 跨轮一致性那把尺的口径错与修补（`CROSS|e2e_verdict` 数出 4 那件事）**写在 `p28/EVIDENCE.md` P28-lead-10**——
  尺和被量的判据都在 p28 那一族，别在两个文件里各写一遍然后各自漂。本节只借用它的结论：
  杠③ 在当前树成立 —— `0b47cc8` 上 03:21:26–03:57:32 串行三轮，每轮 `checks=32 pass=32 fail=0`、
  `CROSS|e2e_verdict=1`、`CROSS_CTRL|…实测=2`，且 `BAR3_SRC_MD5_END` 与 START 同字节 ⇒ `§8.12.5`/`§8.13.6` 那句"待补"可以划掉。

## §13.7 把 §13.6 那句"下一步做"兑现掉：杠② 现在自己采 `~/.zbot`，六支对照 + 整批复跑逐列不变（09-27 04:04:46–04:07:21，主编亲测）

上一节的空档原话（`EVIDENCE.md:1158–1162`）："**先记一处我这节的空档**——`p27_mutation.py` 这五跑**没有**采样 `~/.zbot`
…… 要把这个空档补成硬账，就该在 `p27_mutation.py` 的每跑首尾各打一条 `bar4=`（下一步做，别在做了之前先声称）。"
这一节做掉它，并按老规矩**先证量具有牙，再拿它量一次整批**。

**改了什么**（`p27_mutation.py` 795 → 945 行，`git diff --numstat` = `152 2`）：新增 `PROFILE_DIR`、
`bar4_sample()`（只量条目数 + `config.properties`/`state.db` 的 md5 前 8 位 + `minimax.api.key` 的**长度**，值一次都不读）、
`bar4_verdict()`（首尾同字节才算 `same=YES`，且两次采样都必须完备）、`bar4_probe()`（六支对照），并把 `run_all()`
的收尾 `return 0` 换成"判决参与退出码"。还加了一个 `ZBOT_LEDGER_OUT` 出口 —— 默认仍写**受跟踪**那份台账（正式台账必须脚本自写），
它只服务下面那次"自证跑不覆写提交树字节"。那 2 条删除行就是 `LEDGER = os.path.join(...)` 原句与 `return 0`。

**"只改量具、没改被量的表"这句话要有尺**，不能靠我描述：

```
$ git diff --ignore-all-space -- '*.java' --numstat | wc -l          ⇒ 0     # Java 侧一字未动
$ git diff HEAD -- _doc/005_testing/acceptance/p27/p27_mutation.py | grep -E "^[+-]" \
    | grep -vE "^(\+\+\+|---)" | grep -E 'id="M|expect=|allow_extra=' | wc -l  ⇒ 0     # 预期红集表零改动
```

**量具有牙**（`python3 _doc/005_testing/acceptance/p27/p27_mutation.py --bar4-probe`，rc=0，不取锁、不跑 mvn；原文，真 profile 只出现长度）：

```
CTRL1|真 profile 连采两次应相同=YES
CTRL2|临时目录未改动应相同=YES
CTRL3|加一个文件应且只应点名 entries=YES diff=entries
CTRL4|改 config 字节应点名 cfg_md5+key_len=YES diff=cfg_md5,key_len
CTRL5|缺文件必须带 PROBLEMS 且不许算成相同=YES
CTRL6|独立 shell 尺=YES python=8/2dadaed0/690ddbc0/125
      shell=8/2dadaed0/690ddbc0/125
BAR4_PROBE|fails=无
```

CTRL3–CTRL5 是三张"必红"的对照，其中 **CTRL5 是这刀的全部理由**：删掉 `config.properties` 后两次采样字节相同，
一把只看"start == end"的尺会说 `same=YES` —— 所以判决要求"两次采样都完备"，缺文件当场 `same=NO` +
`PROBLEMS=config.properties_missing`（原文：`BAR4_VERDICT|same=NO|…|diff=无|problems=config.properties_missing/无`，
即 `diff=无` 而 `same=NO`，两者不是一回事）。CTRL6 拿一支完全独立的 bash 尺
（`ls -A | wc -l` / `md5 -q | cut -c1-8` / `awk -F= '/^minimax\.api\.key=/{print length($2)}'`）对拍 python 侧读数，
防的是"自己算自己验"那种同源自证。`--selftest` 另跑一遍：`SELFTEST|cases=14 failures=0`。

**整批复跑（补刀后的第一次正式量）**：`ZBOT_LEDGER_OUT=$HOME/.cache/zbot-p27-lead/LEDGER.bar4probe.tsv` 重定向台账，
控制台 `~/.cache/zbot-integrate/p27_mut_bar4_batch.log`（`*.log` 被 `.gitignore` 拦 ⇒ 决定性行原样贴在这里）。
起 04:04:46、止 04:07:21（日志 `ctime` 与台账 `#generated_by 2026-09-27T04:07:21+0800 pid=52787`），2 m 35 s，
`head=681791b`、`dirty_lines=3`（就是这份 harness 改动 + 两个 `__pycache__`）：

```
TABLE_SELFCHECK|mutants=22 ids_unique=yes fields_exact=yes dup_keys=0 expectations_nonempty=yes
TARGET|repo=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot head=681791b branch=main dirty_lines=3
BAR4|start|dir=/Users/zifang/.zbot|entries=8|cfg_md5=2dadaed0|db_md5=690ddbc0|key_len_only=125
   rc=0 elapsed=3.0s [INFO] Tests run: 88, Failures: 0, Errors: 0, Skipped: 0
== 计数: RED-OK=21, SURVIVED=1 ==
   M13-non-atomic-state-write —— 丢掉 ATOMIC_MOVE：单进程读写序下这条不可观测（预期可能 SURVIVED，见 EVIDENCE §6）
BAR4|end|dir=/Users/zifang/.zbot|entries=8|cfg_md5=2dadaed0|db_md5=690ddbc0|key_len_only=125
BAR4_VERDICT|same=YES|start=(8, '2dadaed0', '690ddbc0', '125')|end=(8, '2dadaed0', '690ddbc0', '125')|diff=无|problems=无/无
== 锁已释放 ==
```

**新台账与提交台账逐列比**（这次比对的尺把 §13.5 那个"过滤器把 22 行全筛掉却打印 0 格不同"的病补掉了 ——
列数从表头现取、并把行数当分母打出来，`elapsed_s` 一列归一后参与比对）：

```
ROWS|committed=22 new=22 cols=10/10
DIFF|cells=0 ids=无
```

⇒ 补这一刀没有改变任何一支的判决，`RED-OK=21 SURVIVED=1` 与 §13.1 的 r4/r5 同形；唯一 `SURVIVED` 仍是 **M13**（§11.4 判过的等价变异位）。
受跟踪台账**字节未被覆写**：`md5 -q _doc/005_testing/acceptance/p27/LEDGER.tsv` 在这一次跑的前后都是
`b52619503be915960b91831daf7a1b5b`（重定向正是为了这一条；未使用 `git checkout --` 当还原步）。

**这一节证到哪一步为止**：它证的是"杠② 整跑期间真 profile 的**首尾**两个时刻读数完备且相同"。
`same=YES` 不证明"中途一次都没被写过又改回来"—— 中途不变这条仍靠 `ZBOT_HOME` 临时根隔离与"杠② 不启动 serve"的结构保证，
和 §13.6/杠① 用的是同一个口径，不把它说成比读数更强。锁的纪律这次也一样：打印 `== 锁已释放 ==`，只 `os.close(fd)`，未 `unlink`。

**这笔量具改动之后在新提交树补量杠①**（`c38e276`，04:11:44→04:15:00，串行三轮，日志 `~/.cache/zbot-integrate/bar1_c38e276.log`）：

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270411 start=2026-09-27 04:11:44 HEAD=c38e276
BAR1_TREE|dirty_total=2 dirty_tracked=0 src_md5_delegate=5abe755b
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 04:12:49
ROUND|r=2 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 04:13:55
ROUND|r=3 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 04:15:00
BAR1_SRC_MD5_END|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
BAR1_CLASSES|src=bb3ff2e6 classes=bb3ff2e6 src_len=53093 cls_len=53093
BAR1_DONE|end=2026-09-27 04:15:00 dir=/Users/zifang/.cache/zbot-integrate/bar1_270411 HEAD=c38e276 dirty_tracked=0 src_md5_delegate=5abe755b
```

`dirty_tracked` 起止都是 0 ⇒ 这三跑属于 `c38e276` 这个 commit；`1162 / files=109` 与 §13.6 同分母（这一节只动 Python 量具与文档）；
杠④ 六个采样点（每轮首尾各一次）逐格 `8/2dadaed0/690ddbc0`。
