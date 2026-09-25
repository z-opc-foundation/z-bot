# P16 验收证据（杠①②③④）

* 分支 / worktree：`w2-p16ev` @ `/private/tmp/zbot-wt-p16ev`，基线 `235a9a7`（= `w2-p16`，未动）
* 产出人：写手代理 `p16ev`；日期 2026-09-26（本机 darwin 25.5，java 1.8，mvn -o 离线）
* **本期一条产品代码都没改**：`git status --porcelain` 只有一行 `?? _doc/acceptance/p16/`
  （见下面「全树未被改动」那节的两条 md5 对拍）；`_doc/hermes-roadmap.md` 一个字没动（共享文件，避开撞车）

本文件里每个数字都跟着产生它的那条命令。判定只认 surefire XML 里的具名 testcase，
`mvn` 退出码非 0 一律不算证据（照 p11b/p17 的纪律）。

---

## 杠① 全量单测（自己在 235a9a7 上复跑，不复用主编的数）

命令（worktree 根目录）：

```
rm -rf z-bot-core/target/surefire-reports && mvn -o test
```

读数（日志 `_doc/acceptance/p16/logs/bar1_full_test_r1.log`）：

```
[INFO] Tests run: 481, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  19.266 s
MVN_EXIT=0
```

surefire XML 的独立计数（同一份 target，另尺核对）：

```
python3 -c "import glob,xml.etree.ElementTree as ET; ...print('classes=%d tests=%d failures=%d errors=%d skipped=%d')"
→ classes=48 tests=481 failures=0 errors=0 skipped=0
```

账也对得上（`@Test` 静态计数，不靠跑）：

```
for f in $(git ls-tree -r --name-only d71657d | grep 'Test\.java$'); do git show d71657d:$f | grep -c "@Test"; done | awk '{s+=$1} END{print s}'   → 399
同上取 235a9a7                                                                                                                              → 481
本期五支新类（235a9a7）@Test 数：DeadTargetsTest 15 / DeliveryLedgerTest 22 / GatewayDeliveryP16Test 18 / SupervisorTest 16 / TurnLeaseTest 11 = 82
399 + 82 = 481 ✓
```

注入跑完之后又整跑了一遍全量（证明逐字节还原不是空话）：见「全树未被改动」一节。

---

## 杠② 注入自证（`_doc/acceptance/p16/p16_mutation.py`）

命令：

```
python3 -u _doc/acceptance/p16/p16_mutation.py            > logs/mutation_full_r2.log
```

读数（LEDGER.tsv 由脚本机械写出）：

| 项 | 读数 |
| --- | --- |
| 变异体总数 | 24 |
| 每轮跑的用例数 | 82（五支测试类，分母钉成 82 条**标题清单**常量 `EXPECTED_TESTS`，标题不齐 ⇒ 该轮判 NO-RUN 而不是收数） |
| RED-OK | **22** |
| PARTIAL | **1**（M09，见下） |
| GREEN-BUT-MUTATED | **1**（M07，**故意**留的空点名集，见「未覆盖」） |
| BROKEN | 0 |
| NO-RUN | 0 |
| SRC_MD5_STABLE | **yes**（五支被测源文件注入前/后 md5 逐字节相同，LEDGER.tsv 里每支都打了 before/after 对拍行） |

### 工单要求的 8 条闸 ⇒ 变异体 ⇒ 判红的具名用例

| 闸 | 变异体 | 点名的测试（真判红） |
| --- | --- | --- |
| ① 先落账再发 | M01 send 前不落 attempting / M02 整段不落账 | `obligationIsOnTheLedgerBeforeTheSendSideEffectStarts`、`deliveredObligationConvergesAndASecondSweepDoesNotResend`、`statusExposesTheSelfHealingReadings`、`chatLevelNotFound…`、`subChatLevel…`、`unregisteredSourceChannel…`（M02 六条全红） |
| ② attempting 崩溃重投带「可能重复」前缀 | M13 `needsMarker` 写死 false | `crashMidAttemptIsRedeliveredWithDuplicateWarning`、`failedObligationIsRedeliveredWithMarker`、`attemptingRowOwnedByADeadProcessIsRedeliveredWithDuplicateWarning`、`markerIsNotStackedWhenARowIsClaimedTwice` |
| ③ 同一条被认领两次时前缀不叠加 | M14 摘掉 `startsWith(RECOVERED_MARKER)` 幂等护栏 | `markerIsNotStackedOnReDelivery` |
| ④ 404 注册死目标 + 后续短路 | M04 不登记 / M05 登记了不短路 / M06 重投成功不清标记 / M18 `not_found` 不算死 / M19 不读 cause 链 | `chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends`、`bootRedeliveryIsTheSelfHealPathForADeadTarget`、`unclassifiableFailuresAreUnknownAndNeverDead`、`classificationLooksAtExceptionMessageToo`、`causeChainIsReadUpToADepthCapAndNeverLoops` |
| ⑤ 子会话级 404 不得把整个 chat 注册成死目标 | M17 子会话标记返回 `NOT_FOUND` | `chatLevelNotFoundIsDeadButSubChatLevelIsNot`、`subChatLevelNotFoundNeverRegistersTheWholeChat` |
| ⑥ 来源通道未在册记 failed 而非 silently delivered | M03 那行改成 `STATE_DELIVERED` | `unregisteredSourceChannelIsRecordedFailedNotSilentlyDelivered` |
| ⑦ 3 次/60s 熔断跳过自动续跑 | M08 只记账不跳过 / M09 空启动也攒账 / M20 阈值 off-by-one / M21 窗口拉到 1 小时 | `thirdInterruptedBootWithinTheWindowSkipsAutoContinuation`、`cleanBootWithNothingToResumeChargesNothing`、`thirdInterruptedBootInSixtySecondsTripsTheBreaker`、`bootsOutsideTheWindowAreForgotten` |
| ⑧ 活实例锁拒绝双启 + 干净退出删掉自己那把锁 | M10 stop 不 release / M22 存活判定反过来 / M23 release 不看归属 / M24 无 profile 也落盘 | `cleanStopRemovesOurOwnInstanceLock`、`liveInstanceLockRefusesADoubleBootAndOwnsWhatItWrote`、`staleLockIsTakenOverAndLiveLockRefusesADoubleBoot`、`releaseRemovesOnlyOurOwnLock`、`lockIsNotPersistedWithoutAProfile` |

另外三条盯的是台账侧的"别把预算烧双份/别白烧"（工单没点名，属于本期同一批守卫）：
M11（活 owner 的行也抢 ⇒ `rowsOwnedByALiveProcessAreLeftAlone`、`secondSweeperBacksOffOnceALiveOwnerHoldsTheRow` 红）、
M12（本次发不出去的也认领 ⇒ `sweepOnlyClaimsRowsThisBootCanActuallyDeliver` 红）、
M15（尝试预算不封顶 ⇒ `exhaustedAttemptBudgetTurnsAbandonedInsteadOfSpinning`、`staleObligationIsAbandonedNotReplayed` 红）、
M16（把 `delivered` 也放进认领范围 ⇒ `deliveredRowIsNeverClaimedAgain` 红）。

### PARTIAL 如实记账（没洗）

`M09 闸⑦ 干净启动也给熔断器攒账` — 点名的 2 条红了，另有第 3 条一起红：
`markerIsNotStackedWhenARowIsClaimedTwice`。
因果判断：摘掉 `claimed.isEmpty() ⇒ return 0` 之后，**setUp 那次无活可续的启动也向熔断器记了一笔**，
于是该用例里第三次带活启动的 `recoverPendingDeliveries()` 被熔断挡下（返回 -1 而不是 1）。
这不是测试互相污染的假红，正是"干净启动攒账"的下游后果 —— 但按纪律**仍然记 PARTIAL**，
不把它挪进期望集洗成 RED-OK。r1/r2 两次整跑读到的红集合完全一致（⇒ 不是抖动）。

### 量具自己坏过的一条（记下来，免得后人以为那是"测到了"）

M16 第一版只往 SQL 里多加了一个 `?` 而没有配 `setString(4, DELIVERED)` ⇒ SQLException 被
`sweepRecoverable` 自己吞成空表 ⇒ 82 条全绿。r1 里它被如实记成 GREEN-BUT-MUTATED
（`logs/LEDGER_r1_superseded.tsv`）。那是**注入没注进去**，不是守卫守住了 ⇒
按工单"必须换成能编译的等价旧写法重做"改成 SQL + 绑定参数一起改，r2 里 M16 = RED-OK。

### 互斥锁（flock，跨编队）双向实测

锁文件 = `$(git rev-parse --git-common-dir)/zbot-mutlock`
= `/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock`（主树的 git 目录，
各 worktree 算出来是同一个 ⇒ 能跨编队）；实现只用 `fcntl.flock(fd, LOCK_EX|LOCK_NB)`，**没有用 `fcntl.lockf`**。

① 邻居真攥着时本脚本拒跑且一个字节都不改：

```
python3 -u _doc/acceptance/p16/p16_mutation.py --hold-lock 100     # 真进程 pid=9165 攥住 flock
    → logs/locktest_holder.log: "LOCK-HELD pid=9165 攥住 100.0s（不碰任何源文件）"
python3 -u _doc/acceptance/p16/p16_mutation.py                      # 另起一支真跑
    → EXIT_WHEN_BUSY=4
    → "FATAL 互斥锁被别的注入脚本攥着（[Errno 35] Resource temporarily unavailable）⇒ 本轮不跑，一个源文件都不碰"
diff logs/locktest_md5_before.txt logs/locktest_md5_after.txt   → MD5_IDENTICAL=yes（五支被测源文件）
diff logs/locktest_status_before.txt logs/locktest_status_after.txt → STATUS_IDENTICAL=yes（全树 git status --porcelain）
```

② 邻居松开后本脚本照常拿得到锁：`kill 9165` 之后再跑 ⇒ 24 轮全部执行完，
`logs/mutation_full_r2.log` 末行 `EXIT=0`，`SRC_MD5_STABLE=yes`。

（附：第一次"拒跑"读数差点被记错 —— 那条命令里用了 `timeout 60`，本机没有这个命令，
`EXIT_WHEN_BUSY=127` 是"命令没跑成"而不是"被锁挡下"。已复测成上面的 4。这条按
"坏读数也要复测"记账。）

---

## 全树未被改动（一条产品代码都不许改）

```
git status --porcelain            → ?? _doc/acceptance/p16/      （只有这一行）
md5 -q z-bot-core/src/main/java/com/zifang/z/bot/channel/{ChannelBus,Gateway,DeliveryLedger,DeadTargets,Supervisor}.java
  与基线（235a9a7 取出的原文）逐支对拍：见 logs/locktest_md5_before.txt / LEDGER.tsv 的 # md5 before/after 行
```

<!-- BAR3 -->
