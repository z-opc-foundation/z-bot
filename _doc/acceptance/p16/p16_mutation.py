#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P16（送达台账 / 死目标 / turn lease / 监管者）变异自证：把本期新加的每一条守卫逐条改坏，
看有没有**具名测试**判红。

格式与纪律照抄 _doc/acceptance/p11b/p11b_mutation.py 与 _doc/acceptance/p17/p17_mutation.py：

  * 入口 `if __name__ == "__main__": sys.exit(main())` —— 不许 import 即执行；
  * 注入前逐条校验锚点出现次数（锚点漂了 = 量具坏了，不是代码坏了 ⇒ FATAL，一个字节都不改）；
  * **同一时刻只允许一支注入脚本在飞**：跨编队互斥锁落在 `git rev-parse --git-common-dir`
    下的 zbot-mutlock（各 worktree 的 _doc/acceptance/ 是各自独立的目录，锁放那儿跨不了编队）。
    实现只用 fcntl.flock(LOCK_EX|LOCK_NB)：**不用 fcntl.lockf** —— 主编 2026-09-26 本机实测，
    真 JVM 正攥着同一文件的 tryLock 时 python 的 lockf 一律回 ACQUIRED（见 p17/probe_lock_namespace.py），
    拿 lockf 当互斥就是空跑。拿不到锁 ⇒ 直接退出，一个源文件都不碰；
  * 每个变异体跑完按内存里的原文逐字节还原，收尾再核对全量 md5，并另打一份注入前/后 md5 对拍；
  * 分母钉成**用例标题清单**（本期五支测试类 82 条），不只核对数量；
  * 判定只认 surefire XML 里**具名 testcase** 出现 failure/error；
    `mvn` 退出码非 0 不算证据；编译不过算 BROKEN（必须换成能编译的等价旧写法重做）；
    点名的红了且没有别的红 = RED-OK；点名的红了但还有别人红 = PARTIAL；点名的没红 = GREEN-BUT-MUTATED；
    PARTIAL / GREEN-BUT-MUTATED 一律如实记账，**不许回头改期望集把它洗成 RED-OK**；
  * LEDGER.tsv 由本脚本机械输出，禁止手敲。

注入清单覆盖工单要求的 8 条闸（每条闸都在注释里标了 M 号）：
  ① 先落账再发        M01 M02
  ② attempting 带标记  M13 M08（顺序那一刀在 M01）
  ③ 标记不叠加        M14
  ④ 404 注册死目标+短路 M04 M05 M06 M07 M18 M19
  ⑤ 子会话级 404 不判死整会话 M17
  ⑥ 来源通道不在册记 failed M03
  ⑦ 3 次/60s 熔断跳过续跑 M08 M09 M20 M21
  ⑧ 活实例锁拒双启 + 干净退出删自己的锁 M10 M22 M23 M24
另外三条是台账侧的防护（双实例不烧双份预算 / 发不出去的别认领 / 预算烧完就弃）：M11 M12 M15 M16。

复算: python3 -u _doc/acceptance/p16/p16_mutation.py [M号子串...]
      python3 -u _doc/acceptance/p16/p16_mutation.py --all-tests M07   # 把某条注入放到**全量**
                                                                       # 测试类上验覆盖，只写
                                                                       # logs/FULLSUITE.tsv
      python3 -u _doc/acceptance/p16/p16_mutation.py --hold-lock 90   # 只攥锁，不注入（互斥实测用）
"""
import fcntl
import hashlib
import io
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")

TEST_CLASSES = ["DeadTargetsTest", "DeliveryLedgerTest", "GatewayDeliveryP16Test",
                "SupervisorTest", "TurnLeaseTest"]
TESTS = ",".join(TEST_CLASSES)

# 分母：本期五支测试类的**用例标题清单**（静态常量，不许只核对数量）。
# 来源：git grep -n "@Test" 235a9a7 -- 这五支类，逐条抄下方法名。
EXPECTED_TESTS = {
    "DeadTargetsTest": [
        "forbiddenErrorsAreDeadTargets", "onlyKnownStringsAreForbiddenKickedIsNot",
        "chatLevelNotFoundIsDeadButSubChatLevelIsNot", "unclassifiableFailuresAreUnknownAndNeverDead",
        "classificationLooksAtExceptionMessageToo", "causeChainIsReadUpToADepthCapAndNeverLoops",
        "markIsIdempotentAndReportsFirstTimeOnly", "platformKeyIsCaseInsensitiveAndChatIsTrimmed",
        "successfulSendClearsTheMark", "registrySurvivesAProcessRestart", "snapshotIsDefensive",
        "corruptRegistryDegradesToEmptyNotThrow", "unwritablePathDegradesToInMemory",
        "forConfigDirPutsTheFileUnderTheProfile", "reasonIsCapped",
    ],
    "DeliveryLedgerTest": [
        "obligationMovesPendingAttemptingDelivered", "markingAnUnknownObligationIsFalseNotAnError",
        "ledgerCreatesItsOwnTableOnAFreshProfile", "crashMidAttemptIsRedeliveredWithDuplicateWarning",
        "pendingObligationIsRedeliveredWithoutMarker", "failedObligationIsRedeliveredWithMarker",
        "markerIsNotStackedOnReDelivery", "deliveredRowIsNeverClaimedAgain",
        "rowsOwnedByALiveProcessAreLeftAlone", "secondSweeperBacksOffOnceALiveOwnerHoldsTheRow",
        "exhaustedAttemptBudgetTurnsAbandonedInsteadOfSpinning", "staleObligationIsAbandonedNotReplayed",
        "sweepOnlyClaimsRowsThisBootCanActuallyDeliver", "sweepSurvivesMissingTableAndBadInput",
        "obligationIdIsStablePerTurnAndSensitiveToContent", "reRecordingTheSameObligationResetsItsAttemptBudget",
        "pruneDropsOldTerminalRowsAndCapsTotal", "osLivenessKnowsItselfAndRejectsBogusPids",
        "pidReuseIsDetectedByStartStampMismatch", "deadProcessIsNeverSeenAsAlive",
        "lstartParsingIsLenient", "debugRowsSummarisesForStatusAndLogs",
    ],
    "GatewayDeliveryP16Test": [
        "obligationIsOnTheLedgerBeforeTheSendSideEffectStarts",
        "deliveredObligationConvergesAndASecondSweepDoesNotResend",
        "attemptingRowOwnedByADeadProcessIsRedeliveredWithDuplicateWarning",
        "pendingRowOwnedByADeadProcessIsRedeliveredWithoutWarning",
        "markerIsNotStackedWhenARowIsClaimedTwice",
        "chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends",
        "subChatLevelNotFoundNeverRegistersTheWholeChat",
        "bootRedeliveryIsTheSelfHealPathForADeadTarget",
        "unregisteredSourceChannelIsRecordedFailedNotSilentlyDelivered",
        "thirdInterruptedBootWithinTheWindowSkipsAutoContinuation",
        "cleanBootWithNothingToResumeChargesNothing",
        "graftedChatsShareOneSessionAndSerializeWithoutWedging",
        "rawConcurrentChatOnOneAgentReallyDoesWedge", "ungraftedChatsKeepTheirOwnSessions",
        "liveInstanceLockRefusesADoubleBootAndOwnsWhatItWrote", "cleanStopRemovesOurOwnInstanceLock",
        "statusExposesTheSelfHealingReadings", "artifactsStayInsideProfile",
    ],
    "SupervisorTest": [
        "cleanExitIsNeverRestarted", "crashIsRestartedUntilItStopsCrashing",
        "restartOnCrashFalseLeavesTheTaskDead", "rapidCrashesTripTheRestartCeiling",
        "crashesAfterTheHealthyWindowStartAFreshStreak", "handlesAreListedForStatus",
        "backoffGrowsExponentiallyAndCaps", "thirdInterruptedBootInSixtySecondsTripsTheBreaker",
        "bootsOutsideTheWindowAreForgotten", "cleanShutdownClearsTheBreakerDebt",
        "corruptBreakerLedgerFailsOpen", "breakerWorksWithoutAProfile",
        "staleLockIsTakenOverAndLiveLockRefusesADoubleBoot", "releaseRemovesOnlyOurOwnLock",
        "unreadableOrMissingLockFileIsNotABarrier", "lockIsNotPersistedWithoutAProfile",
    ],
    "TurnLeaseTest": [
        "twoRouteKeysOnSameSessionIdSerializeAndKeepTranscriptAlternating",
        "unsynchronizedFlushOnSharedSessionWedgesTranscript", "releaseIsIdempotentAndIdentityChecked",
        "crossThreadReleaseCannotLeaveTheLockHeldForever", "leaseWaitTimeoutFailsOpenInsteadOfWedging",
        "degradedTurnIsReportedToTheCallerNotSwallowed", "rebindAliasesHeldLeaseOntoNewSessionId",
        "rebindRefusesToMergeTwoLiveSerializationDomains", "evictionNeverDropsALiveLease",
        "registryCountsEveryTrackedSession", "noSessionIdMeansNoLease",
    ],
}
EXPECTED_TOTAL = sum(len(v) for v in EXPECTED_TESTS.values())   # 82

SRC = {
    "bus": "z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelBus.java",
    "gw": "z-bot-core/src/main/java/com/zifang/z/bot/channel/Gateway.java",
    "led": "z-bot-core/src/main/java/com/zifang/z/bot/channel/DeliveryLedger.java",
    "dead": "z-bot-core/src/main/java/com/zifang/z/bot/channel/DeadTargets.java",
    "sup": "z-bot-core/src/main/java/com/zifang/z/bot/channel/Supervisor.java",
}

# (id, 文件键, old, new, 期望锚点数, 点名期望红的测试, 说明)
MUTANTS = [
    ("M01 闸① send 里看不到 attempting", "bus",
     "        markLedger(led, oid, STATE_ATTEMPTING, null);\n"
     "        try {\n"
     "            source.send(out);\n"
     "        } catch (Exception e) {",
     "        try {\n"
     "            source.send(out);\n"
     "            markLedger(led, oid, STATE_ATTEMPTING, null);\n"
     "        } catch (Exception e) {", 1,
     ["obligationIsOnTheLedgerBeforeTheSendSideEffectStarts"],
     "红线 8 的时序那一半：账是落了，但 send 里面还看不见（先副作用后记账）"),

    ("M02 闸① 根本不落账", "bus",
     "                led.recordObligation(oid, routeKey, platform, chatId, out.text);",
     "                // 注入：把「先落账」整段摘掉（观察发出去之后无账可收敛的后果）", 1,
     ["obligationIsOnTheLedgerBeforeTheSendSideEffectStarts",
      "deliveredObligationConvergesAndASecondSweepDoesNotResend",
      "chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends",
      "subChatLevelNotFoundNeverRegistersTheWholeChat",
      "unregisteredSourceChannelIsRecordedFailedNotSilentlyDelivered",
      "statusExposesTheSelfHealingReadings"],
     "红线 8 本体：掉电重启等于这一枪没开过"),

    ("M03 闸⑥ 来源通道不在册谎报 delivered", "bus",
     "            markLedger(led, oid, STATE_FAILED, \"no-channel: \" + platform);",
     "            markLedger(led, oid, STATE_DELIVERED, \"no-channel: \" + platform);", 1,
     ["unregisteredSourceChannelIsRecordedFailedNotSilentlyDelivered"],
     "235a9a7 修的那条真缺陷原样回来：台账与真实世界分叉，通道回来后 sweep 也认领不回来"),

    ("M04 闸④ 发送失败不注册死目标", "bus",
     "            if (dead != null && DeadTargets.isDeadErrorKind(kind)) {",
     "            if (dead != null && DeadTargets.UNKNOWN.equals(kind)) {", 1,
     ["chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends"],
     "判死登记断在写侧（isDeadErrorKind 认出的那类不再登记）"),

    ("M05 闸④ 判死了也不短路", "bus",
     "        if (dead != null && dead.isDead(platform, chatId)) {\n"
     "            LOG.warn(\"[dead-targets] {}:{} 已判不可达 —— 跳过本次投递（义务留在台账里等自愈）\", platform, chatId);\n"
     "            markLedger(led, oid, STATE_FAILED, \"dead-target\");\n"
     "            return;\n"
     "        }",
     "        // 注入：摘掉短路（观察每条新消息继续撞同一堵墙的代价）", 1,
     ["chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends"],
     "登记了却不用 ⇒ 每次投递照撞，且台账里写不到 dead-target 原因"),

    ("M06 闸④ 重投成功不清死标", "gw",
     "                dead.clear(c.platform, c.chatId);",
     "                // 注入：自愈不清（观察目标恢复了仍被永久拦在外面的后果）", 1,
     ["bootRedeliveryIsTheSelfHealPathForADeadTarget"],
     "自愈的收尾那一步：发出去过一次就该认为目标活着"),

    ("M07 闸④ bus 侧发成功不清死标（探未覆盖）", "bus",
     "                dead.clear(platform, chatId); // 发出去过一次 ⇒ 目标还活着，标记自愈",
     "                // 注入：bus 侧不清标记", 1,
     [],
     "点名集**故意留空**：本期单测只在 Gateway.redeliver 那条路上验了清除，bus 直发路径上"
     "「发出去过一次 ⇒ 标记自愈」这一刀没有测试盯 ⇒ 预期 GREEN-BUT-MUTATED（真缺口，不是测试抓到了）"),

    ("M08 闸⑦ 熔断了照样续跑", "gw",
     "        if (supervisor != null && supervisor.checkAndRecordInterruptedBoot()) {\n"
     "            LOG.warn(\"[gateway] {} 条待续跑义务先搁置 —— 熔断已触发，见 Supervisor 的告警\", claimed.size());\n"
     "            return -1;\n"
     "        }",
     "        if (supervisor != null) {\n"
     "            supervisor.checkAndRecordInterruptedBoot();\n"
     "        }", 1,
     ["thirdInterruptedBootWithinTheWindowSkipsAutoContinuation"],
     "记账还在，跳过没了 ⇒ 紧密重启动循环里每轮都把所有在途义务再砸一遍平台"),

    ("M09 闸⑦ 干净启动也给熔断器攒账", "gw",
     "        if (claimed.isEmpty()) {\n"
     "            return 0;\n"
     "        }",
     "        // 注入：空启动也往下走（观察「只有真有活才记账」这条约束被摘掉的后果）", 1,
     ["cleanBootWithNothingToResumeChargesNothing",
      "thirdInterruptedBootWithinTheWindowSkipsAutoContinuation"],
     "一次正常重启就被记成「上次崩了」，三次之后真该续跑的也不续了"),

    ("M10 闸⑧ 干净退出不删自己的锁", "gw",
     "        if (instanceLock != null) {\n"
     "            instanceLock.release();\n"
     "            instanceLock = null;\n"
     "        }",
     "        if (instanceLock != null) {\n"
     "            instanceLock = null;\n"
     "        }", 1,
     ["cleanStopRemovesOurOwnInstanceLock"],
     "锁赖在盘上 ⇒ 下次启动白白走一遍陈旧锁自愈（真双开的防护被稀释）"),

    ("M11 闸⑧/台账 活着的 owner 的行也抢", "led",
     "                        if (probe.alive(ownerPid == null ? 0L : ownerPid.longValue(), ownerStarted)) {\n"
     "                            continue; // 还活着的网关拥有这一行\n"
     "                        }",
     "                        if (probe.alive(ownerPid == null ? 0L : ownerPid.longValue(), ownerStarted)) {\n"
     "                            // 注入：不跳过（观察两个网关同时烧同一条重投预算）\n"
     "                        }", 1,
     ["rowsOwnedByALiveProcessAreLeftAlone", "secondSweeperBacksOffOnceALiveOwnerHoldsTheRow"],
     "双实例同时 sweep 会把一条义务的重投预算烧双份，且互相踩 transcript"),

    ("M12 台账：发不出去的也认领", "led",
     "                        if (deliverablePlatforms != null && !deliverablePlatforms.contains(row[2])) {\n"
     "                            continue; // 本次启动发不出去 ⇒ 不认领，别白烧预算\n"
     "                        }",
     "                        if (deliverablePlatforms != null && !deliverablePlatforms.contains(row[2])) {\n"
     "                            // 注入：照样认领（观察预算被平台没起来的轮次烧光）\n"
     "                        }", 1,
     ["sweepOnlyClaimsRowsThisBootCanActuallyDeliver"],
     "三条尝试用完 ⇒ 通道回来时这条已经 abandoned，永远发不出去"),

    ("M13 闸② 歧义窗口不带「可能重复」标记", "led",
     "                                        (String) row[4], state, !PENDING.equals(state), attempts + 1));",
     "                                        (String) row[4], state, false, attempts + 1));", 1,
     ["crashMidAttemptIsRedeliveredWithDuplicateWarning", "failedObligationIsRedeliveredWithMarker",
      "attemptingRowOwnedByADeadProcessIsRedeliveredWithDuplicateWarning",
      "markerIsNotStackedWhenARowIsClaimedTwice"],
     "at-least-once 的契约在用户可见处不再诚实：重投的第三条被当成新消息"),

    ("M14 闸③ 标记叠两层", "led",
     "            if (!needsMarker || content == null || content.startsWith(RECOVERED_MARKER)) {",
     "            if (!needsMarker || content == null) {", 1,
     ["markerIsNotStackedOnReDelivery"],
     "幂等那半边：同一行被认领两次 ⇒ 用户看到两遍 ♻️ 前缀"),

    ("M15 尝试预算不封顶", "led",
     "                        if (attempts + 1 > MAX_ATTEMPTS || now - createdAt > STALE_AFTER_MILLIS) {",
     "                        if (false && (attempts + 1 > MAX_ATTEMPTS || now - createdAt > STALE_AFTER_MILLIS)) {",
     1,
     ["exhaustedAttemptBudgetTurnsAbandonedInsteadOfSpinning", "staleObligationIsAbandonedNotReplayed"],
     "永远重试 ⇒ 一个坏平台能把台账拖成无限循环"),

    # 注：第一版这条只往 SQL 里加了第 4 个 `?` 而没有 setString(4) ⇒ SQLException 被
    #     sweep 自己吞成空表 ⇒ 82 条全绿（LEDGER r1 里 M16 = GREEN-BUT-MUTATED）。
    #     那是量具坏，不是守卫守住，按工单要求换成真能改坏语义的等价写法重做。
    ("M16 delivered 的行还能被再认领", "led",
     "                                    + \" WHERE state IN (?,?,?) ORDER BY created_at\");\n"
     "                    try {\n"
     "                        sel.setString(1, PENDING);\n"
     "                        sel.setString(2, ATTEMPTING);\n"
     "                        sel.setString(3, FAILED);",
     "                                    + \" WHERE state IN (?,?,?,?) ORDER BY created_at\");\n"
     "                    try {\n"
     "                        sel.setString(1, PENDING);\n"
     "                        sel.setString(2, ATTEMPTING);\n"
     "                        sel.setString(3, FAILED);\n"
     "                        sel.setString(4, DELIVERED);", 1,
     ["deliveredRowIsNeverClaimedAgain"],
     "结清的行被重新拖进重投队列 ⇒ 同一条消息再发一遍（真把 DELIVERED 放进认领范围，"
     "不是只改 SQL 占位符）"),

    ("M17 闸⑤ 子会话级 404 判成整会话死", "dead",
     "        for (String s : SUBCHAT_LEVEL_NOT_FOUND) {\n"
     "            if (blob.contains(s)) {\n"
     "                return THREAD_NOT_FOUND;\n"
     "            }\n"
     "        }",
     "        for (String s : SUBCHAT_LEVEL_NOT_FOUND) {\n"
     "            if (blob.contains(s)) {\n"
     "                return NOT_FOUND;\n"
     "            }\n"
     "        }", 1,
     ["chatLevelNotFoundIsDeadButSubChatLevelIsNot", "subChatLevelNotFoundNeverRegistersTheWholeChat"],
     "改一条消息失败 ⇒ 整个会话被永久拉黑（她 hermes 里分开这两类的全部意义）"),

    ("M18 闸④ not_found 不再算死", "dead",
     "        return FORBIDDEN.equals(errorKind) || NOT_FOUND.equals(errorKind);",
     "        return FORBIDDEN.equals(errorKind);", 1,
     ["unclassifiableFailuresAreUnknownAndNeverDead",
      "chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends"],
     "会话真没了却继续每条都撞"),

    ("M19 闸④ 不读 cause 链", "dead",
     "        for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {",
     "        for (int depth = 0; t != null && depth < 1; depth++) {", 1,
     ["classificationLooksAtExceptionMessageToo", "causeChainIsReadUpToADepthCapAndNeverLoops"],
     "通道层都把 HTTP 失败包一层 ⇒ 分类器永远只见最外层，死目标登记成摆设"),

    ("M20 闸⑦ 熔断阈值 off-by-one", "sup",
     "        boolean tripped = maxRestarts > 0 && boots.size() >= maxRestarts;",
     "        boolean tripped = maxRestarts > 0 && boots.size() > maxRestarts;", 1,
     ["thirdInterruptedBootInSixtySecondsTripsTheBreaker",
      "thirdInterruptedBootWithinTheWindowSkipsAutoContinuation"],
     "3 次/60s 变 4 次/60s ⇒ 那一轮真投递还是被砸了出去"),

    ("M21 闸⑦ 窗口长度失效", "sup",
     "    private synchronized List<Long> recordBoot(long now, int windowSeconds) {\n"
     "        long cutoff = now - Math.max(1L, windowSeconds) * 1000L;",
     "    private synchronized List<Long> recordBoot(long now, int windowSeconds) {\n"
     "        long cutoff = now - Math.max(1L, windowSeconds) * 3600L * 1000L;", 1,
     ["bootsOutsideTheWindowAreForgotten"],
     "窗口外的旧账不清 ⇒ 攒够三次之后这台机器永远不再自动续跑"),

    ("M22 闸⑧ 存活判定反了", "sup",
     "            if (ownerPid != null && ownerPid.longValue() != pid && p.alive(ownerPid.longValue(), ownerStarted)) {",
     "            if (ownerPid != null && ownerPid.longValue() != pid && !p.alive(ownerPid.longValue(), ownerStarted)) {",
     1,
     ["staleLockIsTakenOverAndLiveLockRefusesADoubleBoot",
      "liveInstanceLockRefusesADoubleBootAndOwnsWhatItWrote"],
     "活实例锁被抢（双开）而陈旧锁抢不动（崩过的机器永远起不来）——两头同时坏"),

    ("M23 闸⑧ release 误删别人的锁", "sup",
     "                if (raw != null && raw.contains(\"\\\"pid\\\":\" + pid)) {",
     "                if (raw != null) {", 1,
     ["releaseRemovesOnlyOurOwnLock", "liveInstanceLockRefusesADoubleBootAndOwnsWhatItWrote"],
     "收尾时把新实例刚写的锁删了 ⇒ 第三个实例也能起来"),

    ("M24 闸⑧ 无 profile 也把锁往盘上写", "sup",
     "            return new InstanceLock(null, pid, startedAt, false, \"no-profile: 锁不落盘\");",
     "            return new InstanceLock(new File(\"gateway.lock\"), pid, startedAt, true,\n"
     "                    \"no-profile: 锁不落盘\");", 1,
     ["lockIsNotPersistedWithoutAProfile"],
     "红线 1 的反面：进程内测试 / 无 profile 场景往当前目录写锁"),
]


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def read(key):
    with io.open(os.path.join(ZBOT, SRC[key]), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with io.open(os.path.join(ZBOT, SRC[key]), "w", encoding="utf-8") as fh:
        fh.write(text)


def common_dir():
    """git 公共目录：各 worktree 共用 ⇒ 锁能跨编队。"""
    out = subprocess.check_output(["git", "rev-parse", "--git-common-dir"], cwd=ZBOT)
    d = out.decode().strip()
    return d if os.path.isabs(d) else os.path.abspath(os.path.join(ZBOT, d))


LOCK_PATH = os.path.join(common_dir(), "zbot-mutlock")
_lock_handle = None


def acquire_lock():
    """只用 flock(LOCK_EX|LOCK_NB)；lockf 在本机测不出邻居 ⇒ 禁止。"""
    global _lock_handle
    _lock_handle = io.open(LOCK_PATH, "a+b")
    try:
        fcntl.flock(_lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as e:
        _lock_handle.close()
        _lock_handle = None
        raise
    return _lock_handle


def hold_lock_only(seconds):
    """互斥实测用：真进程攥住 flock N 秒，期间不碰任何源文件。"""
    print("lock file = %s" % LOCK_PATH, flush=True)
    try:
        acquire_lock()
    except OSError as e:
        print("LOCK-BUSY 攥不住（%s）⇒ 已有别的注入脚本在飞" % e, flush=True)
        return 4
    print("LOCK-HELD pid=%d 攥住 %ss（不碰任何源文件）" % (os.getpid(), seconds), flush=True)
    time.sleep(seconds)
    fcntl.flock(_lock_handle.fileno(), fcntl.LOCK_UN)
    _lock_handle.close()
    print("LOCK-RELEASED pid=%d" % os.getpid(), flush=True)
    return 0


def expected_names():
    out = set()
    for v in EXPECTED_TESTS.values():
        out.update(v)
    return out


def run_tests(classes=None):
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    cmd = ["mvn", "-o", "-q", "test", "-pl", "z-bot-core"]
    if classes:
        cmd += ["-Dtest=" + classes, "-DfailIfNoTests=false"]
    t0 = time.time()
    proc = subprocess.run(cmd, cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    failing, ran, names = set(), 0, set()
    if os.path.isdir(REPORTS):
        for name in sorted(os.listdir(REPORTS)):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(REPORTS, name)).getroot()
            except Exception:
                continue
            ran += int(root.get("tests") or 0)
            for tc in root.iter("testcase"):
                names.add(tc.get("name").split("[")[0])
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.add(tc.get("name").split("[")[0])
    return proc.returncode, failing, ran, names, proc.stdout.decode("utf-8", "replace"), time.time() - t0


def full_suite_probe(ids):
    """杠② 的补账量具：把"点名集为空"的那几条注入放到**全量测试类**上再跑一遍，
    分清"本期五支类没覆盖"与"全仓 481 条都没覆盖"。

    只写 logs/FULLSUITE.tsv，**不碰 LEDGER.tsv** —— 整账仍由无参整跑机械产出。
    判定照旧只认 surefire 里的具名 testcase，mvn 退出码不算证据。
    """
    sel = [m for m in MUTANTS if any(a in m[0] for a in ids)]
    if not sel:
        print("FATAL: --all-tests 的选择器没命中任何变异体 id: %s" % (ids,), flush=True)
        return 2
    print("lock     = %s (fcntl.flock LOCK_EX|LOCK_NB)" % LOCK_PATH, flush=True)
    try:
        acquire_lock()
    except OSError:
        print("FATAL 互斥锁被别的注入脚本攥着（%s）⇒ 本轮不跑，一个源文件都不碰"
              % (sys.exc_info()[1],), flush=True)
        return 4
    bad = []
    for mid, key, old, new, anchors, _expected, _note in sel:
        got = read(key).count(old)
        if got != anchors:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, anchors, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，下面的读数一律不收）:")
        for line in bad:
            print("  " + line)
        return 2

    md5_before = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    rows = []
    stable = True
    for mid, key, old, new, anchors, expected, note in sel:
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, _names, out, secs = run_tests(None)     # 全量：不带 -Dtest
        write(key, original)
        ok = md5(os.path.join(ZBOT, SRC[key])) == md5_before[key]
        stable = stable and ok
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif failing:
            verdict = "COVERED-BY-NAMED-TEST"
        else:
            verdict = "NOT-COVERED-REPO-WIDE"
        who = ",".join(sorted(failing)) if failing else "全量 %d 条具名 testcase 无一判红" % ran
        print("%-34s %-24s ran=%d 点名集=%d 红=%s | mvn_rc=%s | %.1fs | 还原=%s"
              % (mid, verdict, ran, len(expected), who if failing else "-", rc, secs, ok),
              flush=True)
        rows.append((mid, verdict, str(ran), "%d/%d" % (len(set(expected) & failing),
                                                        len(expected)), who, str(rc), str(ok)))
    md5_after = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    stable = stable and all(md5_after[k] == md5_before[k] for k in SRC)
    ledger = os.path.join(HERE, "logs", "FULLSUITE.tsv")
    with io.open(ledger, "w", encoding="utf-8") as fh:
        fh.write("id\tverdict\ttests_ran\tnamed_expected_red\ttest_that_went_red"
                 "\tsurefire_rc\trestored\n")
        for r in rows:
            fh.write("\t".join(r) + "\n")
        fh.write("# generated_by\tp16_mutation.py --all-tests %s\tat\t%s\n"
                 % (",".join(ids), time.strftime("%Y-%m-%dT%H:%M:%S%z")))
        fh.write("# denominator\t全量测试类（不带 -Dtest），非本期五支的 82 条\n")
        for k in sorted(SRC):
            fh.write("# md5\t%s\tbefore=%s\tafter=%s\n" % (k, md5_before[k], md5_after[k]))
    print("\n== FULLSUITE PROBE: %d 条注入 ⇒ %s（LEDGER.tsv 未改）=="
          % (len(rows), " / ".join("%s=%s" % (r[1], r[0].split()[0]) for r in rows)), flush=True)
    print("  SRC_MD5_STABLE=%s  FULLSUITE -> %s" % ("yes" if stable else "NO", ledger), flush=True)
    fcntl.flock(_lock_handle.fileno(), fcntl.LOCK_UN)
    _lock_handle.close()
    return 0 if stable else 3


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--all-tests":
        return full_suite_probe(sys.argv[2:] or ["M07"])
    if len(sys.argv) > 2 and sys.argv[1] == "--hold-lock":
        return hold_lock_only(float(sys.argv[2]))

    print("worktree = %s" % ZBOT, flush=True)
    print("HEAD     = %s" % subprocess.check_output(
        ["git", "rev-parse", "--short", "HEAD"], cwd=ZBOT).decode().strip(), flush=True)
    print("lock     = %s (fcntl.flock LOCK_EX|LOCK_NB)" % LOCK_PATH, flush=True)
    mutants = MUTANTS
    if len(sys.argv) > 1:
        mutants = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not mutants:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:], flush=True)
            return 2
        print("选择器 %s ⇒ 只跑 %d/%d 条（LEDGER 会被这一子集覆盖，整账要整跑）"
              % (sys.argv[1:], len(mutants), len(MUTANTS)), flush=True)
    try:
        acquire_lock()
    except OSError as e:
        print("FATAL 互斥锁被别的注入脚本攥着（%s）⇒ 本轮不跑，一个源文件都不碰" % e, flush=True)
        return 4

    want = expected_names()
    print("分母 = %d 条用例标题（五支类：%s）" % (len(want), TESTS), flush=True)
    if len(want) != EXPECTED_TOTAL:
        print("FATAL: 标题清单有重复（去重后 %d != 声明 %d）" % (len(want), EXPECTED_TOTAL), flush=True)
        return 2

    bad = []
    for mid, key, old, new, anchors, expected, note in mutants:
        got = read(key).count(old)
        if got != anchors:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, anchors, SRC[key]))
        for t in expected:
            if t not in want:
                bad.append("%s: 点名 %s 不在本期用例标题清单里（期望集自己漂了）" % (mid, t))
    if bad:
        print("FATAL 锚点/期望集校验失败（代码已漂，下面的读数一律不收）:")
        for line in bad:
            print("  " + line)
        return 2

    md5_before = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0, "NO-RUN": 0}
    rows = []
    ran_last = 0
    for mid, key, old, new, anchors, expected, note in mutants:
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, names, out, secs = run_tests()
        write(key, original)                       # 按内存原文逐字节还原
        restored = md5(os.path.join(ZBOT, SRC[key])) == md5_before[key]
        missing = sorted(want - names)
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif missing or ran != EXPECTED_TOTAL:
            verdict = "NO-RUN"
        else:
            hit = sorted(set(expected) & failing)
            others = sorted(failing - set(expected))
            if hit and not others:
                verdict = "RED-OK"
            elif hit:
                verdict = "PARTIAL"
            elif failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        ran_last = ran
        who = ",".join(sorted(failing)) if failing else ("全绿（跑了 %d 条）" % ran)
        print("%-34s %-18s 点名=%d/%d ⇒ %s | ran=%d | mvn_rc=%s | %.1fs | 还原=%s"
              % (mid, verdict, len(set(expected) & failing), len(expected), who, ran, rc, secs,
                 restored), flush=True)
        if verdict != "RED-OK":
            print("     %s" % note, flush=True)
        if missing:
            print("     分母漂了，缺: %s" % ",".join(missing[:6]), flush=True)
        rows.append((mid, verdict, "%d/%d" % (len(set(expected) & failing), len(expected)),
                     who, str(ran), str(rc), str(restored)))
        if not restored:
            print("FATAL: %s 之后文件没还原成基线，停在这里（后面的读数不可信）" % mid, flush=True)
            break

    md5_after = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    stable = all(md5_after[k] == md5_before[k] for k in SRC)
    here = os.path.dirname(os.path.abspath(__file__))
    ledger = os.path.join(here, "LEDGER.tsv")
    with io.open(ledger, "w", encoding="utf-8") as fh:
        fh.write("id\tverdict\tnamed_expected_red\ttest_that_went_red\ttests_ran"
                 "\tsurefire_rc\trestored\n")
        for r in rows:
            fh.write("\t".join(r) + "\n")
        fh.write("# tally\t%s\n" % "\t".join("%s=%d" % (k, tally[k]) for k in
                                             ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED",
                                              "BROKEN", "NO-RUN")))
        fh.write("# mutants_injected\t%d\n" % len(rows))
        fh.write("# p16_tests_ran_per_round\t%d\n" % ran_last)
        fh.write("# p16_test_titles_pinned\t%d\n" % EXPECTED_TOTAL)
        fh.write("# SRC_MD5_STABLE\t%s\n" % ("yes" if stable else "NO"))
        for k in sorted(SRC):
            fh.write("# md5\t%s\tbefore=%s\tafter=%s\n" % (k, md5_before[k], md5_after[k]))
        fh.write("# ledger_generated_by\tp16_mutation.py\tat\t%s\n"
                 % time.strftime("%Y-%m-%dT%H:%M:%S%z"))

    print("\n== 台账 ==", flush=True)
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-18s %d" % (k, tally[k]), flush=True)
    print("  LEDGER -> %s" % ledger, flush=True)
    print("  SRC_MD5_STABLE=%s" % ("yes" if stable else "NO"), flush=True)
    fcntl.flock(_lock_handle.fileno(), fcntl.LOCK_UN)
    _lock_handle.close()
    return 0 if stable else 3


if __name__ == "__main__":
    sys.exit(main())
