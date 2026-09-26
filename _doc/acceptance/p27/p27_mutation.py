#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P27 委托面 —— 杠② 变异量具（自带 MUTANTS 表，不 import 任何别的战役脚本）。

五档判定（工单 §3.4，一条不欠）：
  RED-OK                 注入后变红，且红的恰是预期红集里的具名用例（无预期外红）
  PARTIAL                预期红集命中，但同时有预期外用例变红（杀得掉，归属不干净）
  KILLED                 注入后变红，但预期红集一支都没红——被别的用例杀掉的
  SURVIVED               注入后仍然全绿（必须在报告里点名 + 说为什么）
  INJECTION_NOT_APPLIED  被替换的原文在本文件里不是唯一一处（注入本身不可信）

停机型第六档（不在五档之内，出现即说明本轮不可读，不许拿它冒充杀变异）：
  BASELINE_NOT_GREEN     跑之前基线就不绿 —— 本轮一律不出判定
  RESTORE_FAILED         逐字节还原后 md5 对不上 —— 立刻停机，不许继续测量
  NONINFORMATIVE         rc≠0 却既无具名红也无类级红（编译断/没跑到用例）
  ERROR                  mvn 超时（永挂嫌疑）

共享锁：$(git rev-parse --path-format=absolute --git-common-dir)/zbot-mutlock
抢不到 ⇒ rc=4 直接退出（不 sleep 死等、不 kill 别人）。
动笔前先做无界 await()/waitFor() 体检：委托这一路天生等子进程，无界等待会把全编队的锁占死。
"""

import fcntl
import hashlib
import os
import re
import shutil
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
# 量具必须钉在**目标树**上。原先这里硬 `/private/tmp/zbot-wt-p27`（写手树），而那次杠② 之后
# main 又并了 P19 与控制台三处修复 ⇒ 今天再跑，改的是 09-26 16:57 冻结的那份分支树、
# LEDGER 也是写回那棵树（tracked 的 LEDGER 第 2 行 `# repo=/private/tmp/…` 就是自证）。
REPO = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
LEAD = os.path.expanduser("~/.cache/zbot-p27-lead")
BAK = os.path.join(LEAD, "mutbak")
OUT = os.path.join(LEAD, "mutation")
LEDGER = os.path.join(REPO, "_doc/acceptance/p27/LEDGER.tsv")
MAIN_DIR = "z-bot-core/src/main/java/com/zifang/z/bot/delegate"
TEST_DIR = "z-bot-core/src/test/java/com/zifang/z/bot/delegate"
SCOPE = "com.zifang.z.bot.delegate.*Test,BotAgentTest"   # 具名范围（含被波及的 BotAgentTest）
MVN_TIMEOUT = 420

# ---------------------------------------------------------------------------
# MUTANTS 表：每条 = (编号, 文件, 原文, 变异文, 预期红集, 这支在测什么)
# 原文必须在文件里唯一命中，否则判 INJECTION_NOT_APPLIED。
# ---------------------------------------------------------------------------
D = lambda f: MAIN_DIR + "/" + f

MUTANTS = [
    dict(id="M01-cap-anchor-bumped",
         file=D("DelegationDelivery.java"),
         old="public static final int MAX_DELIVERY_ATTEMPTS = 8;",
         new="public static final int MAX_DELIVERY_ATTEMPTS = 9;",
         expect=["attemptCapIsEightLikeTheHermesAnchor", "eachClaimBurnsExactlyOneAttempt",
          "exhaustedAttemptsConvergeToTerminalDropped", "theCapEvidenceSurvivesARestart", "deliveredIsTerminal",
          "summaryCountsEveryDeliveryState", "summaryOnEmptyLedgerIsHonest",
          "asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",
          "q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth",],
         why="上限锚点被挪动一格（8→9）：取证价值当场归零"),
    dict(id="M02-cap-unbounded",
         file=D("DelegationDelivery.java"),
         old="if (e.deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {",
         new="if (e.deliveryAttempts >= Integer.MAX_VALUE) {",
         expect=["exhaustedAttemptsConvergeToTerminalDropped", "theCapEvidenceSurvivesARestart",
          "q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth",],
         why="把上限摘成事实无界 ⇒ 没人接的完成事件永远回到 PENDING，重启无限重放 G1 机制订正：`droppedRowsAreNotOfferedForRestoreButPendingRowsAre` 从预期红集摘出——它的 DROPPED 来自显式 `drop()`（DelegationDelivery.java:146-157），结构上不经被改的 `release` 收敛分支（:105），永远抓不到这支；它仍是恢复队列只看 PENDING 的产品级守卫，别删。"),
    dict(id="M03-ack-without-claim-allowed",
         file=D("DelegateTransitions.java"),
         old="putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);",
         new=("putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);\n"
              "        putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DELIVERED, DeliveryState.DELIVERED);"),
         expect=["deliveryMatrixIsExhaustive", "ackWithoutClaimIsIllegal"],
         why="给 PENDING 开一条到 DELIVERED 的口子 = 把 P16 那个同型洞重新焊回去 G1b 机制订正"
             "（三轮实测同一读数：预期红却没红:ackWithoutClaimFailsLoudly ⇒ 摘出预期红集）："
             "真红的两支都在**表格层**（DelegateStateMachineTest.deliveryMatrixIsExhaustive 逐格对“状态×事件”手写基准、"
             "ackWithoutClaimIsIllegal 问 nextDelivery(PENDING, RESULT_DELIVERED) 必抛）；"
             "而 ackWithoutClaimFailsLoudly 走**服务层**——DelegationDelivery.complete():131 在查表之前就"
             " `if (e.delivery == PENDING) throw`，表格多一条边改不动它的通过条件 ⇒ 属“与它的通过条件等价”，不是尺空跑。"
             "服务层另立 M22 来注：两层的账不许并成一支（当年并成一支才读出“没红=没牙”的假信号）。"),
    dict(id="M04-claim-does-not-burn",
         file=D("DelegationDelivery.java"),
         old="""        e.deliveryAttempts++;
        e.claimToken = token;""",
         new="""        e.deliveryAttempts += 0;
        e.claimToken = token;""",
         expect=["eachClaimBurnsExactlyOneAttempt", "exhaustedAttemptsConvergeToTerminalDropped",
          "theCapEvidenceSurvivesARestart", "claimLeaseBlocksOtherConsumersUntilItExpires", "deliveredIsTerminal",
          "summaryCountsEveryDeliveryState", "asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",
          "q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth",],
         why="claim 不烧尝试数 ⇒ 上限变成不会走动的表"),
    dict(id="M05-prune-age-inverted",
         file=D("DelegationLedger.java"),
         old="if (ageBase + maxAgeMillis > now) {",
         new="if (ageBase + maxAgeMillis < now) {",
         expect=["pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse", "pruneHonoursTheRetentionWindowBoundary",
          "unknownScenesAreTerminalAndThusPrunable", "corruptStateFileIsIgnoredNotCrashed",
          "q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath",
          "startupSweepAdoptsStaleSceneAndReportsFromDisk", "unfinishedAsyncSceneIsAdoptableAsOrphan",],
         allow_extra=["q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt",],
         why="年龄判定反了：窗口内的删、过期的留 G1 机制订正：`q3a_…` 的 `pruneStale(0L, now)` 是零窗口刀口（判据 `ageBase+0 > now`，要靠两次取时之间跨过 1 ms 才分辨得出），记为允许的波及不作确定性判据。"),
    dict(id="M06-prune-eats-inflight",
         file=D("DelegationLedger.java"),
         old="""        for (Entry e : list()) {
            if (!e.state.terminal()) {
                continue;
            }
            long ageBase""",
         new="""        for (Entry e : list()) {
            if (false && !e.state.terminal()) {
                continue;
            }
            long ageBase""",
         expect=["pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse"],
         why="回收不看终态 ⇒ 在飞委托的唯一现场会被顺手删掉"),
    dict(id="M07-retention-window-bloated",
         file=D("DelegationLedger.java"),
         old="public static final int LIVE_RETENTION_DAYS = 7;",
         new="public static final int LIVE_RETENTION_DAYS = 70;",
         expect=["retentionConstantMatchesHermesSevenDays"],
         why="保留窗口漂走一个数量级（她的锚是 7 天）"),
    dict(id="M08-age-basis-dispatch-time",
         file=D("DelegationLedger.java"),
         old="long ageBase = e.finishedAt > 0L ? e.finishedAt : e.updatedAt;",
         new="long ageBase = e.dispatchedAt;",
         expect=["pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse", "pruneHonoursTheRetentionWindowBoundary",
          "unknownScenesAreTerminalAndThusPrunable", "corruptStateFileIsIgnoredNotCrashed",],
         why="拿建账时刻当年龄基准 ⇒ 刚完成的老事件永不回收（盘只涨不落）"),
    dict(id="M09-orphan-adoption-ignores-age",
         file=D("DelegationLedger.java"),
         old="""            if (e.updatedAt + staleOlderThanMillis > now) {
                continue;
            }""",
         new="""            if (false && e.updatedAt + staleOlderThanMillis > now) {
                continue;
            }""",
         expect=["orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes",
          "q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt",],
         why="认领孤儿不看心跳年龄 ⇒ 真在飞的委托被判死"),
    dict(id="M10-orphan-adoption-eats-terminal",
         file=D("DelegationLedger.java"),
         old="""        for (Entry e : list()) {
            if (e.state.terminal()) {
                continue;
            }
            if (e.updatedAt""",
         new="""        for (Entry e : list()) {
            if (false && e.state.terminal()) {
                continue;
            }
            if (e.updatedAt""",
         expect=["orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes"],
         why="收工的现场也被再过一遍孤儿流程 ⇒ DONE 被改判 UNKNOWN G1b 机制订正"
             "（三轮实测同一读数：预期红却没红:asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery ⇒ 摘出）："
             "那一支从头到尾不调 adoptOrphans（它测 dispatch→闸门→pull 的投递轴），结构上看不见这支变异。"
             "顺带查清一条**反向**的：unfinishedAsyncSceneIsAdoptableAsOrphan 确实调了 adoptOrphans 并断言"
             "“只有超期的非终态被认领”，但它那条 DONE 现场是**刚写的**（updatedAt=now），第二道年龄闸自己就把它挡住了"
             " ⇒ 摘掉终态闸它照样绿，也不是判据。终态闸今天只有**一支**确定性捕手 ="
             " orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes（它的 dlg-done 是 10× 超期，"
             "只有终态闸挡得住），实测 3/3 红。"),
    dict(id="M11-done-means-delivered",
         file=D("DelegateManager.java"),
         old='advanceQuiet(live, DelegateEvent.TASK_COMPLETED, "异步收工");',
         new=('live.delivery = DeliveryState.DELIVERED;\n'
              '                    advanceQuiet(live, DelegateEvent.TASK_COMPLETED, "异步收工");'),
         expect=["asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",
          "unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached",
          "q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth",
          "q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt",],
         why="★ 本期主攻的那一格：子代理自己给自己签收条（P16 同型洞的原形状） G1 机制订正：`syncDelegationWritesASceneThatIsReadableWithoutTheCaller` 从预期红集摘出——变异块在 `submitAsync` 的池线程 runnable 里（DelegateManager.java:377），同步路径收工在 :211 且字面量不同，调用链上碰不到；它仍是'同步委托不许替消费者签收'的守卫。"),
    dict(id="M12-scene-never-created",
         file=D("DelegationLedger.java"),
         old="""                File d = dirOf(e.id);
                if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                    throw new IOException("mkdirs 失败: " + d);
                }
                writeHeaderOnce(d, e);""",
         new="""                File d = dirOf(e.id);
                if (false && !d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                    throw new IOException("mkdirs 失败: " + d);
                }
                writeHeaderOnce(d, e);""",
         expect=["createWritesTheSceneBeforeAnythingRuns", "syncDelegationWritesASceneThatIsReadableWithoutTheCaller",
          "asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",],
         allow_extra=["sceneIsReadableFromAFreshInstance", "eventsLogCarriesWireNamesInOrder",
          "atomicWriteLeavesNoTempFileAndNoHalfState", "secretsAreMaskedBeforeHittingDisk",
          "pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse", "pruneHonoursTheRetentionWindowBoundary",
          "orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes", "unknownScenesAreTerminalAndThusPrunable",
          "corruptStateFileIsIgnoredNotCrashed", "advanceRejectsIllegalTransitionBeforeTouchingDisk",
          "staleCopyCannotOverwriteATerminalSceneOnDisk", "aBehindCopyAdoptsTheDiskStateBeforeAdvancing",
          "listIsOrderedByDispatchTime", "eachClaimBurnsExactlyOneAttempt", "ackWithoutClaimFailsLoudly",
          "staleOrForeignTokenCannotAck", "exhaustedAttemptsConvergeToTerminalDropped", "theCapEvidenceSurvivesARestart",
          "droppedRowsAreNotOfferedForRestoreButPendingRowsAre", "claimLeaseBlocksOtherConsumersUntilItExpires",
          "deliveredIsTerminal", "explicitDropFromPendingIsHonouredOnce", "summaryCountsEveryDeliveryState",
          "unfinishedAsyncSceneIsAdoptableAsOrphan", "stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins",
          "unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached",
          "concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth", "startupSweepAdoptsStaleSceneAndReportsFromDisk",
          "q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth",
          "q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry",
          "q3a_killedDelegatorLeavesAPhantomInFlightSceneAndNoReadPathReconcilesIt",
          "q3b_afterARestartTheUserFacingEntriesLoseTheSceneWhileTheDiskStillHoldsIt",
          "q3c_unparsableAndZeroTimestampScenesEscapeEveryReadAndPrunePath",],
         why="建账时不建目录 ⇒ 现场只在内存里，进程死了就没了（靶子二的正面） G1 机制订正：本支不是'少建一个目录'，而是整本台账被降级成 no-op——短路 mkdirs 之后 `writeHeaderOnce` 抛 FileNotFoundException → `catch` → `disable(\"create\")` 把 ok 永久翻转 （DelegationLedger.java:174→:517-521），于是 save/load/list/appendEvent 全灭；33 支连坐是按这条级联逐个定位到'第一个从盘上读的断言'得来的，不是读数回填。"),
    dict(id="M13-non-atomic-state-write",
         file=D("DelegationLedger.java"),
         old="""                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);""",
         new="""                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);""",
         expect=["atomicWriteLeavesNoTempFileAndNoHalfState"],
         why="丢掉 ATOMIC_MOVE：单进程读写序下这条不可观测（预期可能 SURVIVED，见 EVIDENCE §6）"),
    dict(id="M14-legacy-status-silent-default",
         file=D("DelegateState.java"),
         old='throw new IllegalArgumentException("未知委托状态字面量: " + raw);',
         new='return UNKNOWN;',
         expect=["legacyStatusLiteralsAreNormalizedOrRejected"],
         why="认不出的状态字面量被静默吞成 UNKNOWN ⇒ 拼错不报错，回到改造前的裸字符串病"),
    dict(id="M15-redaction-off",
         file=D("DelegationLedger.java"),
         old='String s = KEYISH.matcher(text).replaceAll("sk-***REDACTED***");',
         new="String s = text;",
         expect=["secretsAreMaskedBeforeHittingDisk"],
         why="落盘前不遮密钥（她的 _redact 同位被摘）"),
    dict(id="M16-id-validation-off",
         file=D("DelegationLedger.java"),
         old="if (!ID_OK.matcher(s).matches()) {",
         new="if (false && !ID_OK.matcher(s).matches()) {",
         expect=["pathEscapingIdsAreRejected",
          "q1_capEightCountsPerDelegationDeliveryAttemptsNotQueueLengthOrInFlightWidth",],
         why="id 不再校验 ⇒ `../` 能把台账写出根目录（现场台账变任意目录删除器）"),
    dict(id="M17-gate-counts-running-only",
         file=D("DelegateManager.java"),
         old='        return !"DONE".equals(status) && !"FAILED".equals(status);',
         new='        return "RUNNING".equals(status);',
         expect=["flyingPredicateCountsQueuedRowsButNotTerminals"],
         allow_extra=["concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth",],
         why="并发闸退回只数 RUNNING（修之前的写法）⇒ 连发可越过 width。"
             "09-27 改锚：谓词已从闸门里抽成 DelegateManager.isFlying(String)，由那条无时序的"
             "谓词用例确定性抓（实测：注入后红的恰是它，见 EVIDENCE §M17 有牙探针）。 G1b 实测三轮改口："
             "同一张表、同一棵树、串行三跑（r1 02:46 / r2 02:57 / r3 03:00）读数是**红、红、绿** ⇒ "
             "原先那句“连发用例结构上看不到 QUEUED 那半边”**被实测否证，撤回**：它看得见，红的时候失败点是 "
             "DelegateManagerLedgerTest.java:257 “前 3 条都该收下 expected:<3> but was:<4>”（闸真放行了 width 之外的条数，真阳性）。"
             "但它不是确定性判据——submitAsync 的 ASYNC_POOL 是 cached pool，行 N 的 d.status=RUNNING 与主线程下一次闸门检查赛跑。"
             "负载也解释不了：r3 的 load_average=20.36 比两支红时的 9.5 更高，方向相反 ⇒ 纯竞态，没有环境预测因子。"
             "因此记 allow_extra（可真红、但不承重），载荷仍由 flyingPredicateCountsQueuedRowsButNotTerminals 承担（3/3 红）；"
             "连发那支继续留在套件里当“槽位数对不对”的产品级守卫"),
    dict(id="M18-poll-burns-delivery",
         file=D("DelegateManager.java"),
         old='if (!"DONE".equals(d.status) && !"FAILED".equals(d.status)) {',
         new="if (false) {",
         expect=["q2_deliveryAxisHasNoLifecycleGateWhileOnlyTheInMemoryStatusGuardsThePullEntry",],
         allow_extra=["asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",
          "unfinishedAsyncSceneIsAdoptableAsOrphan",],
         why="还没收工就去 claim ⇒ 看进度本身烧投递配额，8 次轮询后结果被记成 dropped G1 机制订正：确定性判据换成 `q2_…`（闸门还关着，子代理必定堵在 chat() 里 ⇒'没收工就去 claim'这一格必被执行）。原先那两支靠 `awaitPull` 以'判词含还在'为退出条件，M18 之后第一次拉取就早退 ⇒ 属同一场竞态，记为允许的波及。与 M17 的摘法同一条规矩：靠时序的量不到谓词。"),
    dict(id="M19-stale-copy-overwrites-scene",
         file=D("DelegationLedger.java"),
         old="if (disk.state.terminal()) {",
         new="if (false && disk.state.terminal()) {",
         expect=["staleCopyCannotOverwriteATerminalSceneOnDisk"],
         why="关掉 reconcile ⇒ 后写的陈旧副本覆盖先落的终态：按了停止、盘上却是 DONE。"
             "09-27 改判（三支都在 EVIDENCE §11 的有牙探针里**实测**过，不是推的）：摘掉这一行在**盘上**是等价的"
             "——落空后仍会走'采纳盘上事实'那半边，迁移表同样因为'终态无出口'而抛，state.json 一个字都不差；"
             "p27b 那轮它 SURVIVED 就是因为只比对了盘上现场。"
             "两处真正不等价的东西被 `staleCopyCannotOverwriteATerminalSceneOnDisk` 钉住了："
             "① 判词点名'盘上已是 X／握着的副本是 Y'；② 被拒的那一步不许顺手改写调用方握着的那份副本"
             "（摘掉后 `e.state` 会被 adoption 改掉）。"
             "另外两支**从预期红集里摘出**、各自的理由是机制＋一轮实测："
             "`stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins` 断言的是盘上现场（两种写法逐字节相同，"
             "实测 rc=1 时它仍绿），但它仍是 M20（/stop 不再落 STOPPED）的预期红 ⇒ 不是死用例；"
             "`aBehindCopyAdoptsTheDiskStateBeforeAdvancing` 造的是**非终态**的盘上领先（RUNNING），"
             "被摘的那个 `if` 结构上进不去 ⇒ 实测同样不红，它钉的是 reconcile 的另一半边"),
    dict(id="M20-stop-leaves-no-scene",
         file=D("DelegateManager.java"),
         old="if (liveId != null) {",
         new="if (false && liveId != null) {",
         expect=["stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins"],
         why="/stop 不再落 STOPPED ⇒ 台账分不清\"死了\"和\"被停了\""),
    dict(id="M21-agents-hides-delivery",
         file=D("DelegateManager.java"),
         old='.append("  投递=").append(delivery.describe(d.id))',
         new='.append("").append("")',
         expect=["asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery"],
         why="/agents 不再显示投递格 ⇒ 界面全绿而账上是空的（对操作员撒谎）"),
    dict(id="M22-ack-precheck-removed",
         file=D("DelegationDelivery.java"),
         old="""        if (e.delivery == DeliveryState.PENDING) {
            throw new IllegalStateException("拒绝无凭证的 ack: " + id""",
         new="""        if (false && e.delivery == DeliveryState.PENDING) {
            throw new IllegalStateException("拒绝无凭证的 ack: " + id""",
         expect=["ackWithoutClaimFailsLoudly"],
         why="摘掉服务层“没 claim 就想 ack”的预检 ⇒ complete() 退化成静默返回 false，P16 那个洞换个口子回来。"
             "G1b 与 M03 配对立的账：M03 注在**表格层**（红的是 DelegateStateMachineTest 那两支），这一支注在**服务层**"
             "（预期红只有 ackWithoutClaimFailsLoudly，从 M03 摘出来挪到这里）。两层互补：M03 点名的集合里不含服务层、"
             "M22 不含表格层 ⇒ “接线对但算法错”和“算法对但没人调”各钉一层（差集就是分层的证据）。"
             "同一判据在真进程那层由 P27DelegationDriver 第⑧段（E2E|ack_without_claim=IllegalStateException）钉住，"
             "它不在杠② 的 -Dtest 范围里 ⇒ 记账指向杠③，不在这里冒充覆盖。"),
]

# ---------------------------------------------------------------------------


def self_check_table():
    """MUTANTS 表自证（改表之后必须先过这一关才许开跑）。

    09-27 我手改 M19 时真造出过"同一个 dict 里两份 why"，所以给这张表配了守卫；
    四支注入对照量下来，各档的**归因**是（别把解释器的功劳记成尺的）：
      `dict(why=…, why=…)`      ⇒ `SyntaxError: keyword argument repeated` —— 解释器拦下，走不到这里；
      键名拼错（`expct=`）      ⇒ 合法语法、**静默**：该支从此没有预期红集 ⇒ 本函数 rc=9；
      `expect=[]`               ⇒ 合法语法、**静默**：只能判 SURVIVED/KILLED，对账成空话 ⇒ 本函数 rc=9；
      两支 id 撞车              ⇒ 合法语法、**静默**：台账行互相顶 ⇒ 本函数 rc=9。
    重复键的检查仍然留着（`dict(...)` 调不到，但 `{...}` 字面量会静默后者覆盖前者）。
    """
    import ast
    import collections
    src = open(os.path.abspath(__file__), encoding="utf-8").read()
    dups = []
    for node in ast.walk(ast.parse(src)):
        if isinstance(node, ast.Dict):
            keys = [k.value for k in node.keys if isinstance(k, ast.Constant) and isinstance(k.value, str)]
            for k, c in collections.Counter(keys).items():
                if c > 1:
                    dups.append("line=%d key=%s x%d" % (node.lineno, k, c))
    if dups:
        die("MUTANTS 表里有重复键（Python 会静默用后者覆盖前者）:\n     " + "\n     ".join(dups), 9)
    _validate_table(MUTANTS)
    print("TABLE_SELFCHECK|mutants=%d ids_unique=yes fields_exact=yes dup_keys=0 expectations_nonempty=yes"
          % len(MUTANTS))


def _validate_table(mutants):
    """表的结构判据（抽成纯函数，`--selftest` 拿伪造表来验它真的会拦）。

    `allow_extra` 是 G1 新增的唯一可选键：它承载"机制上预测得到、但不充当杀变异判据"的连带红
    （零窗口刀口、`awaitPull` 竞态、台账整体 no-op 的级联）。**跑之前写死，不许照读数回填。**
    它只在 expect 之外生效：同一名既在 expect 又在 allow_extra 是矛盾（前者要求它红并当判据，
    后者要求它红但不当判据）⇒ 当场拒。
    """
    ids = [m["id"] for m in mutants]
    if len(set(ids)) != len(ids):
        die("id 有重复: " + str([i for i, c in _counter(ids).items() if c > 1]), 9)
    for m in mutants:
        if set(m) - {"allow_extra"} != {"id", "file", "old", "new", "expect", "why"}:
            die("变异 %s 的字段不对（少键/拼错键会让它永远没有预期红集）: %s" % (m["id"], sorted(m)), 9)
        if not m["expect"]:
            die("变异 %s 的预期红集为空 ⇒ 它只能判 SURVIVED/KILLED，不许冒充对账" % m["id"], 9)
        allow = m.get("allow_extra", [])
        if not isinstance(allow, list):
            die("变异 %s 的 allow_extra 不是列表" % m["id"], 9)
        overlap = sorted(set(m["expect"]) & set(allow))
        if overlap:
            die("变异 %s 的名字 %s 既在 expect 又在 allow_extra（判据和'允许的波及'不能是同一支）"
                % (m["id"], overlap), 9)
        if len(set(allow)) != len(allow):
            die("变异 %s 的 allow_extra 里有重复名" % m["id"], 9)


def _counter(xs):
    out = {}
    for x in xs:
        out[x] = out.get(x, 0) + 1
    return out


def classify(expect, allow_extra, failed):
    """把"这一轮红了谁"翻成档位。**纯函数、不碰 mvn** —— 所以 `--selftest` 能双向验它。

    RED-OK    预期红集有命中，且所有红都在预测范围内（expect ∪ allow_extra）
    PARTIAL   预期红集有命中，但出现了**没预测过**的红 ⇒ 归属不清，必须查
    KILLED    预期红集一支没中，红的是别处的具名用例（"杀掉了但判据不是我们点名的那些"）
              —— 这个名字在本战役里一直被读成"未被杀"，建议下一期改名 ATTRIBUTED（记在 roadmap 待裁定）
    """
    hit = [t for t in expect if t in failed]
    unforeseen = sorted(x for x in failed if x not in expect and x not in allow_extra)
    allowed = sorted(x for x in failed if x in allow_extra)
    missed = [t for t in expect if t not in failed]
    if not hit:
        return "KILLED", hit, unforeseen, allowed, missed
    if unforeseen:
        return "PARTIAL", hit, unforeseen, allowed, missed
    return "RED-OK", hit, unforeseen, allowed, missed


def selftest():
    """量具自证：档位判据的**双向**对照 + 表判据真的会拦哪几种坏表。

    为什么要有这一段：G1 把 10 支的 expect 换窄/换名、又加了 allow_extra 这个放行口子。
    只验"能放行"是不合格的 —— 一个把什么都放行的尺读起来永远绿。所以两个方向都要有猎物：
      正向：被允许的连带红不许把 RED-OK 拖成 PARTIAL；
      反向：**没预测过**的红必须照旧判 PARTIAL（否则 allow_extra 变成万能后门）。
    """
    fails = 0

    def ck(label, got, want):
        if got != want:
            print("  SELFTEST FAIL|%s 得到 %s，应当 %s" % (label, got, want))
            return 1
        print("  ok|%s -> %s" % (label, got))
        return 0

    E = ["a_kill", "b_kill"]
    A = ["c_allowed"]
    cases = [
        ("expect 全红、无其它红", classify(E, A, {"a_kill", "b_kill"}), "RED-OK"),
        ("expect 全红 + 被允许的连带红（正向：放行）",
         classify(E, A, {"a_kill", "b_kill", "c_allowed"}), "RED-OK"),
        ("expect 全红 + 没预测过的红（反向：仍须咬住）",
         classify(E, A, {"a_kill", "b_kill", "surprise"}), "PARTIAL"),
        ("连带红在、预期红也红、再叠一支意外红",
         classify(E, A, {"a_kill", "c_allowed", "surprise"}), "PARTIAL"),
        ("预期红集零命中，被别处杀掉", classify(E, A, {"someone_else"}), "KILLED"),
        ("allow_extra 单独红不算命中（不许拿连带冒充判据）",
         classify(E, A, {"c_allowed"}), "KILLED"),
        ("预期红只中一半、另一半没红但无意外红", classify(E, A, {"a_kill"}), "RED-OK"),
    ]
    for label, got, want in cases:
        fails += ck(label, got[0], want)
    # missed 必须在返回值里可见：RED-OK 不等于"expect 全中"，这一点不能靠读文案猜
    fails += ck("只中一半时 missed 点名为 b_kill", classify(E, A, {"a_kill"})[4], ["b_kill"])

    def rejects(label, table):
        try:
            _validate_table(table)
        except SystemExit:
            print("  ok|%s -> 被拦" % label)
            return 0
        print("  SELFTEST FAIL|%s 没被拦（判据是空的）" % label)
        return 1

    def mutant(**over):
        m = dict(id="X", file="f", old="o", new="n", expect=["a"], why="w")
        for k, v in over.items():
            if v is None:
                m.pop(k, None)
            else:
                m[k] = v
        return m

    fails += rejects("少 expect 键", [mutant(expect=None)])
    fails += rejects("键名拼错（expct）", [dict((("expct", ["a"]) if k == "expect" else (k, v))
                                             for k, v in mutant().items())])
    fails += rejects("expect 为空集", [mutant(expect=[])])
    fails += rejects("id 撞车", [mutant(id="DUP"), mutant(id="DUP")])
    fails += rejects("同一名既在 expect 又在 allow_extra", [mutant(allow_extra=["a"])])
    fails += rejects("allow_extra 不是列表", [mutant(allow_extra="a")])
    print("SELFTEST|cases=%d failures=%d" % (len(cases) + 1 + 6, fails))
    return 1 if fails else 0



def sh(args, cwd=REPO, timeout=None):
    """跑一条命令，返回 (rc, 合并输出)。超时则整组干掉，绝不留孤儿占着锁。"""
    p = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         close_fds=False, preexec_fn=os.setsid)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, (out or b"").decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except OSError:
            pass
        out, _ = p.communicate()
        return 124, (out or b"").decode("utf-8", "replace") + "\n<<TIMEOUT>>"


def lock_path():
    rc, out = sh(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"])
    if rc != 0:
        die("git rev-parse 失败: " + out[-400:])
    return os.path.join(out.strip(), "zbot-mutlock")


def die(msg, code=1):
    print("!! " + msg)
    sys.exit(code)


def acquire_lock():
    """共享锁 = `flock(LOCK_EX|LOCK_NB)`，与 `p19_mutate.py` / `p25_mutation.py` 同一协议。

    为什么不再"读 owner pid、死了就接管"（P27b-G7）：接管这一步在 flock 下本来就是多余的
    ——持有者一死内核就释放，下一棒直接取到。而**按文件内容判生死**会把活着的持有者判成死锁:
    p19/p25 取锁后一个字都不写（锁文件 0 字节），旧码 `int("".split()[0])` 抛 ValueError
    ⇒ `hp=None` ⇒ 落进 unlink 接管分支，于是两根杠② 在同一份 `src/main` 上交错改写、
    各自的 `md5_restored` 都还能对上（因为它们还原的是自己那一版）。
    所以 owner 内容只当**诊断信息**读，判据一律交给 flock；读不到内容也算"别人占着"。
    红线守住：不 sleep 死等、不 kill 别人、不 unlink 别人的锁。
    """
    lp = lock_path()
    try:
        fd = os.open(lp, os.O_RDWR | os.O_CREAT, 0o644)
    except OSError as e:
        die("锁文件打不开 %s: %s" % (lp, e.strerror), 5)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        try:
            with os.fdopen(os.dup(fd), "r") as f:
                who = f.read().strip() or "0 字节（p19/p25 型持有者不写 owner 内容）"
        except OSError:
            who = "<读不到>"
        os.close(fd)
        print("rc=4 LOCK_BUSY 锁被别人占着（flock 未释放 ⇒ 持有者还活着）owner=<%s> path=%s" % (who, lp))
        print("P27 杠② NO-RUN（不 sleep 死等、不 kill 别人、不 unlink 活锁），回头整批重跑。")
        sys.exit(4)
    try:
        os.ftruncate(fd, 0)
        os.write(fd, ("%d p27-mutation %s\n" % (os.getpid(), time.strftime("%Y-%m-%dT%H:%M:%S"))).encode())
    except OSError:
        pass                       # 内容只是诊断，写不进去不影响锁本身
    print("LOCK|acquired=%s pid=%d proto=flock" % (lp, os.getpid()))
    return fd


def assert_target_tree():
    """开工前证明 REPO 就是**这支尺自己所在的那棵目标仓根**；不满足 ⇒ FATAL，不出读数。

    为什么两重：只问 `git rev-parse --show-toplevel == REPO` 拦不住写手树 ——
    `/private/tmp/zbot-wt-p27` 自己就是一棵合法的 git 工作树，那把尺量它照样"通过"
    （这条是被本函数自己的注入对照抓出来的，不是推出来的）。所以先钉"尺与被量的树同仓"，
    再钉"那棵树真的是仓根"，最后把 head/branch 打进读数里供台账归因。
    """
    own = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
    if REPO != own:
        die("FATAL|REPO 不在这支尺自己所在的仓里 ⇒ 量的是别的树\n"
            "     REPO=%s\n     tool_own_repo=%s" % (REPO, own), 6)
    rc, out = sh(["git", "rev-parse", "--show-toplevel"])
    top = os.path.abspath(out.strip())
    if rc != 0 or top != REPO:
        die("FATAL|REPO 不是 git 仓库根（半棵树/被删的树）⇒ 本轮不出读数\n"
            "     REPO=%s\n     git_toplevel=%s" % (REPO, out.strip() or "<空>"), 6)
    for d in (MAIN_DIR, TEST_DIR):
        if not os.path.isdir(os.path.join(REPO, d)):
            die("FATAL|目标树里没有 %s ⇒ 这支尺的锚点不在被量的那棵树上" % d, 6)
    rc, head = sh(["git", "rev-parse", "--short", "HEAD"])
    rc2, branch = sh(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    rc3, dirty = sh(["git", "status", "--porcelain"])
    print("TARGET|repo=%s head=%s branch=%s dirty_lines=%d"
          % (REPO, head.strip() or "<未知>", branch.strip() or "<未知>",
             len([l for l in dirty.splitlines() if l.strip()])))


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def forever_wait_health_check():
    """杠② 前置体检：测试里不许出现无界 await()/waitFor()（P14a 死法）。"""
    rc, out = sh(["grep", "-rn", "-E", r"\.await\(|\.waitFor\(", TEST_DIR])
    hits = [l for l in out.splitlines() if l.strip()]
    print("== 永挂体检：await()/waitFor() 命中 %d 处 ==" % len(hits))
    bad = []
    for line in hits:
        bounded = ("TimeUnit.SECONDS" in line) or ("TimeUnit.MILLISECONDS" in line) \
                  or re.search(r"await\(\s*[0-9_]+\s*,", line) or re.search(r"waitFor\(\s*[0-9_]+\s*,", line)
        print(("  OK   " if bounded else "  UNBOUND ") + line)
        if not bounded:
            bad.append(line)
    if bad:
        die("发现 %d 处无界等待，变异跑起来会占死全编队的锁：%s" % (len(bad), bad[0]), 5)
    print("== 体检通过：0 处无界等待 ==")
    return len(hits)


def failed_tests_from_surefire():
    """从 surefire XML 里取具名红用例（比抠控制台稳）。

    P27b-G5 修尺：surefire 写的是 `<testcase name="<方法>" classname="<类>" .../>`，
    旧正则 `<testcase[^>]*name="..."` 的贪婪 `[^>]*` 会退到 `classname=` 里那个 `name=`
    上，取回的是**类名**——于是每一行都成"预期红却没红"，工单 §3.4 要求的
    "这条 bug 被哪一支具名测试抓到"对账整个失效。这里按属性逐个解析，
    只在字面是 `name=`（前面不是标识符字符）时取值。
    """
    d = os.path.join(REPO, "z-bot-core/target/surefire-reports")
    out = set()
    if not os.path.isdir(d):
        return out
    for fn in sorted(os.listdir(d)):
        if not fn.startswith("TEST-") or not fn.endswith(".xml"):
            continue
        xml = open(os.path.join(d, fn), encoding="utf-8", errors="replace").read()
        for block in re.split(r"(?=<testcase )", xml):
            if not block.startswith("<testcase"):
                continue
            head = block.split("</testcase>")[0]
            attrs = head.split(">")[0]
            m = re.search(r'(?:^|\s)name="([^"]+)"', attrs)
            if m and ("<failure" in head or "<error" in head):
                out.add(m.group(1).split("(")[0])
    return out


def run_suite(tag):
    """跑一套具名范围的用例；每次先把 surefire-reports 清空，否则上一支的红会漏进这一支。"""
    log = os.path.join(OUT, tag + ".log")
    reports = os.path.join(REPO, "z-bot-core/target/surefire-reports")
    if os.path.isdir(reports):
        shutil.rmtree(reports)
    rc, out = sh(["mvn", "-o", "test", "-pl", "z-bot-core",
                  "-Dtest=" + SCOPE, "-DfailIfNoTests=false",
                  "-Dsurefire.failIfNoSpecifiedTests=false"], timeout=MVN_TIMEOUT)
    with open(log, "w") as f:
        f.write(out)
    failed = failed_tests_from_surefire()
    if not failed:
        for m in re.finditer(r"^\[ERROR\]\s+[\w.]*?\.([\w$]+)\s+--\s+Time elapsed.*<<< (?:FAILURE|ERROR)!", out, re.M):
            failed.add(m.group(1))
    classes = re.findall(r"<<< (?:FAILURE|ERROR)! -- in ([\w.$]+)", out)
    summary = re.findall(r"^\[INFO\] Tests run:.*$", out, re.M)
    return rc, failed, classes, summary, log


def restore(path, backup, want_md5):
    shutil.copyfile(backup, path)
    return md5(path) == want_md5


def main():
    self_check_table()
    assert_target_tree()
    os.makedirs(BAK, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    if os.path.isdir(BAK):
        shutil.rmtree(BAK)
    os.makedirs(BAK, exist_ok=True)

    lock_fd = acquire_lock()
    try:
        code = run_all()
    finally:
        # 只关 fd：内核随关即放锁。绝不 unlink —— 锁路径是全编队共用的，
        # 删掉它只会让下一棒在一个已死的 inode 上取锁（别人还锁在旧 inode 上）。
        os.close(lock_fd)
        print("== 锁已释放 ==")
    sys.exit(code)


def run_all():
    hits = forever_wait_health_check()
    rows = []
    header = ["mutant", "file", "outcome", "mvn_rc", "killed_by_or_red_tests", "expected_red",
              "unexpected", "elapsed_s", "md5_restored", "allowed_extra"]

    # ---- 基线必须绿 ----
    print("== 基线（未注入）==")
    t0 = time.time()
    rc, failed, classes, summary, log = run_suite("baseline")
    print("   rc=%d elapsed=%.1fs %s" % (rc, time.time() - t0, summary[-1] if summary else "<无汇总>"))
    baseline_green = (rc == 0)
    if not baseline_green:
        print("!! 基线不绿 ⇒ 全部判 BASELINE_NOT_GREEN，本轮不出判定（日志 " + log + "）")
        for m in MUTANTS:
            rows.append([m["id"], m["file"], "BASELINE_NOT_GREEN", str(rc), "", ";".join(m["expect"]),
                         "n/a", "0", "n/a"])
        write_ledger(rows, header, 0, hits, baseline_green, [])
        return 1

    # ---- 逐支注入 ----
    counts = {}
    survivors = []
    for m in MUTANTS:
        missed = unforeseen = allowed = []   # classify 的三个差集；没跑到分档那一步就保持空
        path = os.path.join(REPO, m["file"])
        rel = m["id"]
        backup = os.path.join(BAK, m["id"].replace("/", "_"))
        shutil.copyfile(path, backup)
        want = md5(path)
        src = open(path, encoding="utf-8").read()
        n = src.count(m["old"])
        if n != 1:
            outcome = "INJECTION_NOT_APPLIED"
            detail = "原文命中 %d 次（要求恰好 1 次）" % n
            rc_i, failed_i, classes_i, summary_i, log_i = None, set(), [], [], None
            elapsed = 0.0
            restored = "n/a"
        else:
            open(path, "w", encoding="utf-8").write(src.replace(m["old"], m["new"], 1))
            if md5(path) == want:
                outcome = "INJECTION_NOT_APPLIED"
                detail = "注入后文件字节未变"
                rc_i, failed_i, elapsed = None, set(), 0.0
                restored = "n/a"
            else:
                t0 = time.time()
                rc_i, failed_i, classes_i, summary_i, log_i = run_suite(m["id"])
                elapsed = time.time() - t0
                restored = "OK"
                if rc_i == 124:
                    outcome = "ERROR"
                    detail = "mvn 超时（永挂嫌疑），日志 " + log_i
                elif rc_i == 0:
                    outcome = "SURVIVED"
                    detail = "全绿：没有任何用例咬住这支变异"
                elif not failed_i and not classes_i:
                    # 停机型第六档：这类"红"不携带任何判据信息，
                    # 记成 NONINFORMATIVE 而不是 KILLED，免得拿假绿冒充杀变异。
                    outcome = "NONINFORMATIVE"
                    detail = "rc=%d 但既无具名红也无类级红（编译断或没跑到用例），日志 %s" % (rc_i, log_i)
                else:
                    # 按"预期红集是否命中 + 红是否在预测范围内"分档——P27b-G5 修尺前这是空话
                    # （取回的是类名），修尺后 RED-OK / PARTIAL / KILLED 三档才真的可区分；
                    # G1 起 allow_extra 承载"机制上预测到的连带"，它放行但不充当杀变异判据。
                    outcome, hit, unforeseen, allowed, missed = classify(
                        m["expect"], m.get("allow_extra", []), failed_i)
                    if outcome == "KILLED":
                        detail = "预期红集零命中，实际全部具名红:"
                    elif outcome == "PARTIAL":
                        detail = "预期红命中 %d 支，未预测的红 %d 支，实际全部具名红:" % (len(hit), len(unforeseen))
                    else:
                        detail = "预期红有命中且无未预测的红，实际全部具名红:"
                    detail += ",".join(sorted(failed_i)) or ("类级红:" + ",".join(classes_i))
                ok = restore(path, backup, want)
                if not ok:
                    outcome = "RESTORE_FAILED"
                    detail += " / 还原后 md5 不符（立刻停机）"
                    counts[outcome] = counts.get(outcome, 0) + 1
                    rows.append([m["id"], m["file"], outcome, str(rc_i), detail,
                                 ";".join(m["expect"]), "n/a", "%.1f" % elapsed, "BAD",
                                 ";".join(m.get("allow_extra", []))])
                    write_ledger(rows, header, counts, hits, baseline_green, survivors)
                    die("还原失败，现场不可信，停机: " + m["file"], 3)
        # 预期红集对账：三件事分开报，别混成一句"预期内"
        #   missed      点名的判据没红 —— 要么推导错了，要么这条 bug 换了条路走
        #   unforeseen  出现了表里没写过的红 —— 归属不清，必须查（这才是 PARTIAL 的成因）
        #   allowed     预测到的连带红了 —— 记账但不当判据，也不扣分
        unexpected = ""
        if outcome in ("KILLED", "PARTIAL", "RED-OK"):
            bits = []
            if missed:
                bits.append("预期红却没红:" + ",".join(missed))
            if unforeseen:
                bits.append("未预测的红:" + ",".join(unforeseen))
            if allowed:
                bits.append("允许连带红 %d 支:%s" % (len(allowed), ",".join(allowed)))
            unexpected = " | ".join(bits) if bits else "预期内"
        elif outcome == "SURVIVED":
            survivors.append((m["id"], m["why"]))
        counts[outcome] = counts.get(outcome, 0) + 1
        rows.append([m["id"], m["file"], outcome, str(rc_i), detail, ";".join(m["expect"]),
                     unexpected, "%.1f" % elapsed, restored, ";".join(m.get("allow_extra", []))])
        print("%-34s %-22s rc=%-4s %s" % (m["id"], outcome, rc_i, unexpected or detail[:90]))
        sys.stdout.flush()
        # 每支都落一次台账：中途掉线也留得下已判定的部分（22 支整批实测 ≈3 分钟，旧注释"几十分钟"是废案期的数）
        write_ledger(rows, header, counts, hits, baseline_green, survivors)

    write_ledger(rows, header, counts, hits, baseline_green, survivors)

    # ---- 收尾：src/main 必须干净 ----
    rc, out = sh(["git", "status", "--porcelain", "--", MAIN_DIR])
    dirty = [l for l in out.splitlines() if l.strip() and " M " not in l]
    rc2, out2 = sh(["git", "diff", "--name-only", "--", MAIN_DIR])
    print("== 收尾 src/main 洁净检查 ==")
    print("   git diff --name-only (src/main/delegate): " + (out2.strip() or "<空>"))
    print("   git status --porcelain 里的非预期条目: " + (("\n     " + "\n     ".join(dirty)) if dirty else "<无>"))
    if out2.strip():
        die("变异之后 src/main 没有还原干净: " + out2.strip(), 3)
    print("== 计数: " + ", ".join("%s=%d" % (k, counts[k]) for k in sorted(counts)) + " ==")
    if survivors:
        print("== SURVIVED 点名 ==")
        for sid, why in survivors:
            print("   " + sid + " —— " + why)
    return 0


def write_ledger(rows, header, counts, hits, baseline_green, survivors):
    """LEDGER.tsv 只由本脚本自写；#generated_by 必晚于 .py 的 mtime。"""
    tmp = LEDGER + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("# p27 杠② 变异台账（脚本自写，勿手改）\n")
        f.write("# repo=%s scope=%s mutants=%d\n" % (REPO, SCOPE, len(MUTANTS)))
        f.write("# forever_wait_hits_bounded=%d baseline_green=%s\n" % (hits, "yes" if baseline_green else "NO"))
        if isinstance(counts, dict):
            f.write("# counts " + " ".join("%s=%d" % (k, counts[k]) for k in sorted(counts)) + "\n")
        if survivors:
            f.write("# survivors " + ",".join(s for s, _ in survivors) + "\n")
        f.write("\t".join(header) + "\n")
        for r in rows:
            f.write("\t".join(str(x).replace("\t", " ") for x in r) + "\n")
        f.write("#generated_by %s pid=%d\n" % (time.strftime("%Y-%m-%dT%H:%M:%S%z"), os.getpid()))
    shutil.move(tmp, LEDGER)


if __name__ == "__main__":
    # --selftest 只验尺自己的判据（纯函数 + 伪造表），不抢锁、不碰 src、不跑 mvn
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        sys.exit(selftest())
    main()
