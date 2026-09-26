#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P24（记忆与身份面）杠② —— 变异检验：把本期每一条守卫逐条改坏，看有没有**具名 testcase** 判红。

工单 §3 杠② 的硬要求与本文的对应关系：

  * **预期红集先写死**：下面 MUTANTS 表里第 7 列 `expected` 就是预期红集，随脚本一起提交，
    跑之前不许改；跑完若某支落在 PARTIAL / GREEN-BUT-MUTATED，就在 EVIDENCE §8 记账，
    **不回头凑绿**。
  * **注入范围 = 本期写域**：只动 `z-bot-core/src/main/.../memory/*.java` 六个文件，
    不碰 `agent/BotAgent.java`、`config/BotConfig.java`（另三支写手在里面），也不碰 `channel/**`。
  * **判级集跨两个包**：本期契约的下游消费者在 `bot.agent` 包里（SOUL 进 system prompt 骨架、
    rewrite 要审批），所以点名 testcase 允许 `bot.memory.*` 与 `bot.agent.*` 两边，
    XML 按各自包名去找（见 CLS_PKG）。
  * 每支变异体只跑它点名的 testcase（`-Dtest=Class#m1+m2`），不跑全量。
  * 判定只认 surefire XML 里的 testcase 名，五档：
      RED-OK             点名的全红、没有别的红
      PARTIAL            点名的一部分红 / 红了别人
      GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
      BROKEN             编译不过
      NO-RUN             阳性对照进不来（配不到猎物），写明原因，不算分
  * **阳性对照**：每族先跑一次 `injection=NONE`，点名的 testcase 必须全绿且真跑到
    （ran>0），否则该族所有变异体记 NO-RUN。
  * 还原只从内存里的原文写回（不用 `git checkout --`），每支跑完立刻 md5 对账，对不上就停。
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲。

**永挂体检（跑变异之前必做，工单 §3 杠② 的前置）**：点名的判级集里若有测试会永挂，就会一直占着
全编队共享的 `zbot-mutlock`（上一棒就是这么把 P14a 饿死的）。本脚本启动时机械重跑这条检查，
只扫**会真被跑到的那些类**（点名集里的测试类）+ 被注入的 main 源码：无界等待原语
（`await()` / `waitFor()` / `CountDownLatch` / `ExecutorService` / `newFixedThreadPool`）
命中即 FATAL 拒绝开跑；有界 `Thread.sleep(n)` 登记不致命（`VolatileContextPersistenceTest` 里
那两处各 1.1s，是故意拉开时间戳，且该类不在本期判级集里）。

锁：`$(git rev-parse --path-format=absolute --git-common-dir)/zbot-mutlock`。
抢不到 ⇒ 只退避 3 次 ×10s（**不死等**），然后按设计 rc=4 退出，回头整批重跑。

复算: python3 -u _doc/acceptance/p24/p24_mutation.py            # 全量一轮
      python3 -u _doc/acceptance/p24/p24_mutation.py --check    # 只验锚点/点名/永挂体检
"""
import fcntl
import hashlib
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
MEM_TEST_SRC = os.path.join(CORE, "src", "test", "java", "com", "zifang", "z", "bot", "memory")
AGENT_TEST_SRC = os.path.join(CORE, "src", "test", "java", "com", "zifang", "z", "bot", "agent")

LOCK_RETRIES = 3             # 抢锁重试次数（刻意小：抢不到就该让路，不是死等）
LOCK_BACKOFF = 10            # 每次退避秒数 ⇒ 最多等 30s
PER_RUN_TIMEOUT = 900        # 单支变异体的 mvn 上限（秒）

MEM = "com.zifang.z.bot.memory"
AGT = "com.zifang.z.bot.agent"
CLS_PKG = {                      # 点名 testcase 所在包（判级集跨两个包）
    "MemoryContentScanTest": MEM,
    "MemoryWriteGateTest": MEM,
    "MemoryDriftGuardTest": MEM,
    "MemoryStoreContractTest": MEM,
    "MemoryIdentityContractTest": MEM,
    "MemoryToolsContractTest": MEM,
    "MemoryStoreTest": MEM,
    "BotAgentMemoryTest": AGT,
    "SystemPromptCacheFreezeTest": AGT,
    "VolatileContextPersistenceTest": AGT,
}

SRC = {
    "sec": "z-bot-core/src/main/java/com/zifang/z/bot/memory/MemorySection.java",
    "scan": "z-bot-core/src/main/java/com/zifang/z/bot/memory/MemoryContentScan.java",
    "gate": "z-bot-core/src/main/java/com/zifang/z/bot/memory/MemoryWriteGate.java",
    "drift": "z-bot-core/src/main/java/com/zifang/z/bot/memory/MemoryDriftGuard.java",
    "store": "z-bot-core/src/main/java/com/zifang/z/bot/memory/MemoryStore.java",
    "tools": "z-bot-core/src/main/java/com/zifang/z/bot/memory/MemoryTools.java",
}

# (id, family, 文件键, old, new, 锚点应出现次数, 点名期望红的 testcase, 说明)
MUTANTS = [
    # ===== G 族：写入门禁·内容扫描（工单 §1#1 前半：命中即拒 + 回报位点）=====
    ("G1 门禁不扫内容（投毒照写）", "G-内容扫描", "gate",
     "        MemoryContentScan.Hit hit = MemoryContentScan.scan(content);\n",
     "        MemoryContentScan.Hit hit = null;\n", 1,
     ["MemoryWriteGateTest#scanHitRejectsAndNamesOffset",
      "MemoryStoreContractTest#poisonedContentNeverReachesDisk",
      "MemoryStoreContractTest#poisonedOpRejectsWholeBatchBeforeTouchingDisk",
      "MemoryToolsContractTest#poisonedAppendCarriesMachineReadableCode",
      "MemoryToolsContractTest#rewriteStillScansEvenAfterConfirmation"],
     "记忆是冻结进 system prompt 的：一条投毒内容会一直坏到有人删它为止"),

    ("G2 空正文不算拒绝（静默成功）", "G-内容扫描", "gate",
     "        if (content == null || content.trim().isEmpty()) {",
     "        if (content == null && false) {", 1,
     ["MemoryWriteGateTest#emptyContentIsRejectedWithCode"],
     "空写入常被当成成功；replace 只给 old_text 不给新正文时它就该报错而不是写空行"),

    ("G3 多条命中取最晚（不取最早）", "G-内容扫描", "scan",
     "                if (best == null || h.offset < best.offset) {",
     "                if (best == null || h.offset > best.offset) {", 1,
     ["MemoryContentScanTest#reportsEarliestHitNotTableOrder"],
     "报错里的位点必须是真实最早那处，否则人按位点去查会查不到东西"),

    ("G4 外传类特征不脱敏（原文进文案）", "G-内容扫描", "scan",
     "                        rule.redact ? \"***\" : bound(m.group()));",
     "                        bound(m.group()));", 1,
     ["MemoryContentScanTest#hitsSecretMaterialAndRedactsSnippet",
      "MemoryContentScanTest#hitsCredentialAssignmentAndProviderKey"],
     "门禁文案会进 transcript 与日志：把疑似密钥原文抄进去就是二次外传"),

    ("G5 命中片段不截断", "G-内容扫描", "scan",
     "    static final int SNIPPET_MAX = 48;",
     "    static final int SNIPPET_MAX = 48000;", 1,
     ["MemoryContentScanTest#snippetIsBoundedTo48Chars"],
     "不封顶就等于把整段攻击载荷原文抄进日志；截断本身是这条文案的规格"),

    ("G6 整页重写不扫（漂移出口顺带绕过门禁）", "G-内容扫描", "store",
     "        if (!body.trim().isEmpty()) {\n            MemoryWriteGate.gateContent(section.fileName() + \" 的新正文\", body, 0);\n        }",
     "        if (false) {\n            MemoryWriteGate.gateContent(section.fileName() + \" 的新正文\", body, 0);\n        }", 1,
     ["MemoryToolsContractTest#rewriteStillScansEvenAfterConfirmation",
      "MemoryIdentityContractTest#soulIsIdentityNotEntryStore"],
     "「escape hatch」只绕漂移判定，绕开内容扫描就等于给投毒留了一条免检通道"),

    # ===== L 族：写入门禁·old_text 定位（§1#1 后半：缺 old_text 绝不降级成新建）=====
    ("L1 缺 old_text 的 replace 降级成新建", "L-old_text定位", "store",
     "                case REPLACE: {\n                    int at = MemoryWriteGate.locate(op.kind(), op.oldText(), working, idx);",
     "                case REPLACE: {\n"
     "                    if (op.oldText() == null || op.oldText().trim().isEmpty()) {\n"
     "                        working.add(MemoryDriftGuard.entryLine(Instant.now().toString(), op.content()));\n"
     "                        applied++;\n"
     "                        break;\n"
     "                    }\n"
     "                    int at = MemoryWriteGate.locate(op.kind(), op.oldText(), working, idx);", 1,
     ["MemoryStoreContractTest#replaceWithoutOldTextIsErrorNotSilentCreate",
      "MemoryToolsContractTest#replaceWithoutOldTextComesBackWithInventory"],
     "本期最硬的一条：凭空多出一条假记忆，比写失败坏得多（她 _missing_old_text_error 的原意）"),

    ("L1b 门禁层 null 的 old_text 不再报错", "L-old_text定位", "gate",
     "        if (oldText == null || oldText.trim().isEmpty()) {",
     "        if (oldText != null && oldText.trim().isEmpty()) {", 1,
     ["MemoryWriteGateTest#missingOldTextErrorsNeverSilentlyCreates"],
     "同一件事的另一半：结构化输出的客户端常把可选字段整个省掉（null 而非空串），"
     "门禁只挡空串就等于没挡"),

    ("L2 命中 0 条不报错（NO_MATCH 消失）", "L-old_text定位", "gate",
     "        if (hits.isEmpty()) {",
     "        if (!hits.isEmpty() && false) {", 1,
     ["MemoryWriteGateTest#noMatchErrorsCarryInventory",
      "MemoryStoreContractTest#batchAppliesAllOrNothingAndNamesTheOffendingIndex",
      "MemoryStoreContractTest#replaceAndRemoveHitExactlyOneEntry",
      "MemoryToolsContractTest#batchFailureNamesTheOperationIndex"],
     "没命中就删/改一条不存在的记忆：批量「全有或全无」失去意义（后续 hits.get(0) 必越界）"),

    ("L3 两条互不相同也算歧义不拒", "L-old_text定位", "gate",
     "        if (distinct.size() > 1) {",
     "        if (distinct.size() > 2) {", 1,
     ["MemoryWriteGateTest#ambiguousMatchIsRefusedButIdenticalDuplicatesAreAllowed"],
     "猜错就是改错条目，而模型看不见盘：它连自己改了什么都不知道"),

    ("L4 幂等去重关掉（重复追加照写）", "L-old_text定位", "store",
     "                    if (MemoryWriteGate.isDuplicate(working, op.content())) {",
     "                    if (false && MemoryWriteGate.isDuplicate(working, op.content())) {", 1,
     ["MemoryStoreContractTest#duplicateAppendIsIdempotentAndSaysSo",
      "MemoryStoreContractTest#batchSuccessAppliesInOrderAndCountsOnlyRealChanges"],
     "重放一次调用就把同一件事记两遍，预算被复读吃光，读出来的画像也是双份"),

    # ===== B 族：预算（2200/1375 按最终状态判）=====
    ("B1 预算读枚举缺省（无视实例档位）", "B-预算", "gate",
     "        int total = MemoryDriftGuard.render(candidate).length();\n        if (total > charLimit) {",
     "        int total = MemoryDriftGuard.render(candidate).length();\n        if (total > section.charLimit()) {", 1,
     ["MemoryWriteGateTest#gateReadsCallersLimitNotEnumDefault",
      "MemoryWriteGateTest#budgetJudgedOnFinalRenderedPageAgainstCallersLimit",
      "MemoryStoreContractTest#budgetIsEnforcedAndReportedInNumbers",
      "MemoryStoreContractTest#batchFreesRoomThenAddsInOneCommit",
      "MemoryIdentityContractTest#budgetsArePerSectionAndIndependent"],
     "配置里把预算调小是没用的：读枚举就是悄悄放宽回缺省值"),

    ("B2 预算边界改成「等于也拒」", "B-预算", "gate",
     "        if (total > charLimit) {",
     "        if (total >= charLimit) {", 1,
     ["MemoryWriteGateTest#exactlyAtBudgetIsAccepted"],
     "预算是「不得超过」，写成「等于也不行」会让人莫名其妙删掉一条有用的记忆"),

    ("B3 store 落盘前不判预算（只留裸门禁）", "B-预算", "store",
     "        MemoryWriteGate.requireBudget(section, charLimit(section), working, 0);",
     "        // (mutant) 预算不再在落盘前判", 1,
     ["MemoryStoreContractTest#budgetIsEnforcedAndReportedInNumbers",
      "MemoryStoreContractTest#batchFreesRoomThenAddsInOneCommit",
      "MemoryIdentityContractTest#budgetsArePerSectionAndIndependent"],
     "门禁单测全绿而整条产品通道不设防 —— 这一支专防「只测判据不测接线」"),

    # ===== D 族：漂移、取证快照、半写还原（§1#2）=====
    ("D1 漂移只判往返，不判形状", "D-漂移判定", "drift",
     "                if (!isEntryLine(line)) {",
     "                if (false && !isEntryLine(line)) {", 1,
     ["MemoryDriftGuardTest#signalShapeCatchesManuallyAppendedProse",
      "MemoryStoreContractTest#externalEditBlocksEntryWriteAndLeavesEvidence"],
     "手编的散文照样能往返，只判往返抓不到它 —— 而整页刷出去就会丢掉它"),

    ("D2 漂移拒写但不留取证", "D-漂移判定", "drift",
     "            Files.copy(target.toPath(), bak.toPath());",
     "            // (mutant) 不落取证快照", 1,
     ["MemoryDriftGuardTest#signalShapeCatchesManuallyAppendedProse",
      "MemoryStoreContractTest#externalEditBlocksEntryWriteAndLeavesEvidence",
      "MemoryToolsContractTest#driftBackupPathReachesTheCallerThroughTheTool"],
     "bak_path 就是 hermes _drift_error 的全部意义：拒写之后人得能看见被护住的是什么"),

    ("D3 同毫秒快照互相覆盖（抄她的按秒粒度）", "D-漂移判定", "drift",
     "        while (f.exists()) {",
     "        while (false && f.exists()) {", 1,
     ["MemoryDriftGuardTest#sameTimestampBackupsDoNotClobberEachOther"],
     "她按秒取整，同一秒两次漂移会把第一枚证据覆盖掉；这处我们明知故不抄，必须能量出来"),

    ("D4 写后不回读对账（半写当成功）", "D-还原与对账", "store",
     "            byte[] onDisk = target.isFile() ? Files.readAllBytes(target.toPath()) : new byte[0];",
     "            byte[] onDisk = payload;", 1,
     ["MemoryStoreContractTest#halfWriteIsRolledBackToExactOriginalBytes"],
     "半写是最坏的情况：盘上留着一份既不是旧也不是新的东西，而且没人报错"
     "（对账摘掉之后盘上剩 3 个字节，回执却写着成功）"),

    ("D5 写失败不还原原字节", "D-还原与对账", "store",
     "            MemoryDriftGuard.restore(target, bak);",
     "            if (bak.length() < 0) {\n                MemoryDriftGuard.restore(target, bak);\n            }", 1,
     ["MemoryStoreContractTest#halfWriteIsRolledBackToExactOriginalBytes",
      "MemoryStoreContractTest#sinkFailureKeepsReasonAndRestores"],
     "留下半写文件等于把损坏交给下一次读取；还原必须是失败路径的默认动作"),

    ("D6 写成功也保留快照（每写一行留一枚 .bak）", "D-还原与对账", "store",
     "            MemoryDriftGuard.deleteQuietly(bak);   // 成功 ⇒ 保险作废",
     "            // (mutant) 成功也留着保险", 1,
     ["MemoryStoreContractTest#duplicateAppendIsIdempotentAndSaysSo",
      "MemoryIdentityContractTest#successfulWritesLeaveNoTempOrBackupArtifacts"],
     "快照是保险不是证据；不删就会把 memories/ 变成 .bak 堆场，谁也不知道哪一枚还有意义"),

    # ===== I 族：身份三件套契约（§1#3）=====
    ("I1 isEmpty 把 SOUL 算进非空", "I-身份三件套", "store",
     "        return readMemory().isEmpty() && readUser().isEmpty();",
     "        return readMemory().isEmpty() && readUser().isEmpty() && readSoul().isEmpty();", 1,
     ["MemoryIdentityContractTest#soulAloneNeverMakesTheStoreNonEmpty"],
     "SOUL 是 ensureSoul 人人都会建出来的骨架：算进非空，「这台机器还没记住任何东西」就永远测不出来"),

    ("I2 ensureSoul 无条件覆盖（手编作废）", "I-身份三件套", "store",
     "                if (!target.exists()) {",
     "                if (true) {", 1,
     ["MemoryIdentityContractTest#ensureSoulIsIdempotentAndRespectsHandEdits",
      "MemoryStoreTest#ensureSoulGeneratesOnceAndKeepsEdits"],
     "人格是用户手编的资产；每启一次就被刷回缺省，等于没有这一份文件"),

    ("I3 条目操作也接受整页形状", "I-身份三件套", "store",
     "        if (section == null || !section.isEntryList()) {",
     "        if (section == null) {", 1,
     ["MemoryStoreContractTest#entryOpsOnSoulAreRefused",
      "MemoryIdentityContractTest#soulIsIdentityNotEntryStore",
      "MemoryToolsContractTest#soulIsReadableButNotWritableThroughTheTool"],
     "对 SOUL.md 走条目渲染会把整页散文压成一行 —— 人格文件被吃掉且回不来"),

    ("I4 未知 target 名退成 MEMORY（不报错）", "I-身份三件套", "sec",
     "        if (\"soul\".equals(t) || \"persona\".equals(t)) {\n            return SOUL;\n        }\n        return null;",
     "        if (\"soul\".equals(t) || \"persona\".equals(t)) {\n            return SOUL;\n        }\n        return MEMORY;", 1,
     ["MemoryToolsContractTest#unknownSectionIsRefusedNotDefaulted",
      "MemoryIdentityContractTest#targetNamesMapOntoIdentityWithoutGuessing"],
     "拼错的 section 名静默落到长期记忆里：写错地方比写失败坏，而且没人报错"),

    # ===== T 族：工具面与批量语义（§1#4 + 审批不变量）=====
    ("T1 批量失败不再指名第几条", "T-批量与回执", "store",
     "        return batch ? i + 1 : 0;",
     "        return 0;", 1,
     ["MemoryStoreContractTest#batchAppliesAllOrNothingAndNamesTheOffendingIndex",
      "MemoryStoreContractTest#poisonedOpRejectsWholeBatchBeforeTouchingDisk",
      "MemoryToolsContractTest#batchFailureNamesTheOperationIndex"],
     "「全有或全无」不带第几条，模型只能整批重发同一组操作（她的报错里也带 idx）"),

    ("T2 空批量当成成功", "T-批量与回执", "store",
     "        if (ops == null || ops.isEmpty()) {",
     "        if (ops == null) {", 1,
     ["MemoryStoreContractTest#emptyAndMalformedBatchesAreRefused"],
     "空批量回「成功」是最典型的假绿：什么都没做，而调用方以为落了"),

    ("T3 rewrite 不等审批直接落盘", "T-审批与回执", "tools",
     "                                if (!Confirmations.alreadyConfirmed(args)) {\n"
     "                                    return Confirmations.needsConfirmation(\n"
     "                                            \"整页重写 \" + section.fileName() + \"（覆盖现有内容）\");",
     "                                if (false && !Confirmations.alreadyConfirmed(args)) {\n"
     "                                    return Confirmations.needsConfirmation(\n"
     "                                            \"整页重写 \" + section.fileName() + \"（覆盖现有内容）\");", 1,
     ["MemoryToolsContractTest#rewriteSuspendsUntilConfirmedAndThenWrites",
      "BotAgentMemoryTest#memoryRewriteSuspendsUntilConfirmed"],
     "整页覆盖是这一层唯一的破坏性写；绕过 /confirm 就是 agent 自己签字删用户记忆"
     "（这一支的第二个猎物在 bot.agent 包里：只有真 agent 循环能验出「挂起等 /confirm」这条链路）"),

    ("T4 forget 不等审批直接清空", "T-审批与回执", "tools",
     "                                if (!Confirmations.alreadyConfirmed(args)) {\n"
     "                                    return Confirmations.needsConfirmation(\n"
     "                                            \"清空 \" + section.fileName());",
     "                                if (false && !Confirmations.alreadyConfirmed(args)) {\n"
     "                                    return Confirmations.needsConfirmation(\n"
     "                                            \"清空 \" + section.fileName());", 1,
     ["MemoryToolsContractTest#forgetSuspendsUntilConfirmedAndThenClears"],
     "清空与整页重写同罪：一次幻觉出来的 action=forget 就把用户画像抹了"),

    ("T5 operations 解析不动静默当空批量", "T-批量与回执", "tools",
     "                throw new MemoryWriteRejectedException(MemoryWriteRejectedException.BAD_OPERATION,\n                        \"operations 不是合法 JSON：\" + e.getMessage());",
     "                return out;", 1,
     ["MemoryToolsContractTest#batchRejectsUnparseableOperationsLoudly"],
     "把坏 JSON 变成空批量，就变成 T2 那个假绿：一次「什么都没做」的成功回执"),
]

# 每族的阳性对照点名集 = 该族所有变异体点名 testcase 的并集（机械算，不手敲第二份）
FAMILY_PREY = {}
for _m in MUTANTS:
    FAMILY_PREY.setdefault(_m[1], set()).update(_m[6])

# 预期红集（跑之前冻死在这里）：id -> 点名 testcase。LEDGER 里 expected_red_set 列必须与此逐字一致。
EXPECTED_RED_FROZEN = {m[0]: list(m[6]) for m in MUTANTS}

# 无界等待原语：命中即不许占锁（这些一旦等不到就永不返回，会把全编队共享的变异锁占死）。
UNBOUNDED_PATTERNS = [
    r"\.await\s*\(",                 # 无界 latch / future 等待
    r"\.waitFor\s*\(",               # 子进程等待
    r"CountDownLatch",
    r"ExecutorService",
    r"newFixedThreadPool",
]
# 有界睡眠：不致命，但要在台账里看得见（它拖慢的是我自己，不会占死锁）。
BOUNDED_PATTERNS = [r"\bThread\.sleep\s*\("]
MEM_MAIN_SRC = os.path.join(CORE, "src", "main", "java", "com", "zifang", "z", "bot", "memory")


def measure_suite_total():
    """全量-suite 用例总数只认机械量：`git grep -c '@Test' HEAD` 求和。不许手敲常量。"""
    proc = subprocess.run(["git", "-C", ZBOT, "grep", "-c", "@Test", "HEAD", "--",
                           "z-bot-core/src/test"], stdout=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("FATAL: git grep 量具本身失败 rc=%d，不收读数" % proc.returncode)
    total, files = 0, 0
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        try:
            total += int(line.rsplit(":", 1)[1])
            files += 1
        except ValueError:
            raise SystemExit("FATAL: git grep 输出行形状不对: %r" % line)
    if files == 0 or total <= 0:
        raise SystemExit("FATAL: 量到 suite 总数 %d（文件 %d 个）= 空输入，不收读数" % (total, files))
    return total, files


def hang_check():
    """永挂体检：只扫**本脚本会真跑的**那些 testcase 所在类 + 被注入的 main 源码。

    判据分两档：无界等待原语（await/waitFor/latch/线程池）命中即致命 —— 它一旦等不到就永不返回，
    会把全编队共享的 `zbot-mutlock` 占死（上一棒就是这么把 P14a 饿死的）；
    有界的 `Thread.sleep(n)` 只登记不致命（它拖慢的是我自己，不会占死锁）。
    """
    named_classes = sorted({c for m in MUTANTS for c, _ in
                            [x.split("#", 1) for x in m[6]]})
    targets = []
    if os.path.isdir(MEM_MAIN_SRC):
        for n in sorted(os.listdir(MEM_MAIN_SRC)):
            if n.endswith(".java"):
                targets.append(os.path.join(MEM_MAIN_SRC, n))
    for cls in named_classes:
        root = MEM_TEST_SRC if CLS_PKG.get(cls) == MEM else AGENT_TEST_SRC
        p = os.path.join(root, cls + ".java")
        if os.path.isfile(p):
            targets.append(p)
    unbounded, bounded = [], []
    for p in targets:
        with io.open(p, encoding="utf-8") as fh:
            text = fh.read()
        for pat in UNBOUNDED_PATTERNS:
            for m in re.finditer(pat, text):
                line = text[:m.start()].count("\n") + 1
                unbounded.append("%s:%d %s" % (os.path.basename(p), line, pat))
        for pat in BOUNDED_PATTERNS:
            for m in re.finditer(pat, text):
                line = text[:m.start()].count("\n") + 1
                bounded.append("%s:%d" % (os.path.basename(p), line))
    return [os.path.basename(p) for p in targets], unbounded, bounded


def abspath(key):
    return os.path.join(ZBOT, SRC[key])


def read(key):
    with io.open(abspath(key), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with io.open(abspath(key), "w", encoding="utf-8") as fh:
        fh.write(text)


def md5(path):
    h = hashlib.md5()
    with io.open(path, "rb") as fh:
        h.update(fh.read())
    return h.hexdigest()


def check_named_tests():
    """点名的 Class#method 必须真的存在于测试源码里（指向已删测试的变异体只会崩出假红）。"""
    missing = []
    cache = {}
    for m in MUTANTS:
        for full in m[6]:
            cls, meth = full.split("#", 1)
            if cls not in CLS_PKG:
                missing.append("%s（%s 不在判级集登记表 CLS_PKG 里）" % (full, cls))
                continue
            root = MEM_TEST_SRC if CLS_PKG[cls] == MEM else AGENT_TEST_SRC
            if cls not in cache:
                p = os.path.join(root, cls + ".java")
                cache[cls] = io.open(p, encoding="utf-8").read() if os.path.isfile(p) else ""
            if ("void " + meth + "(") not in cache[cls]:
                missing.append("%s（%s 里找不到 `void %s(`）" % (full, cls + ".java", meth))
    return sorted(set(missing))


def acquire_lock():
    top = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--path-format=absolute",
                          "--git-common-dir"], stdout=subprocess.PIPE)
    common = top.stdout.decode("utf-8", "replace").strip()
    if not common or not os.path.isabs(common):
        common = os.path.join(ZBOT, ".git")
    lock_path = os.path.join(common, "zbot-mutlock")
    for attempt in range(LOCK_RETRIES):
        fh = io.open(lock_path, "a+")
        try:
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fh, lock_path
        except IOError:
            fh.close()
            print("[锁] 第 %d/%d 次没抢到 %s —— 同机别人在跑，%ds 后再试一次；"
                  "本脚本**不死等**，重试到上限就 rc=4 让路"
                  % (attempt + 1, LOCK_RETRIES, lock_path, LOCK_BACKOFF), flush=True)
            time.sleep(LOCK_BACKOFF)
    return None, lock_path


def selector(named):
    by_class = {}
    for full in named:
        cls, meth = full.split("#", 1)
        by_class.setdefault(cls, []).append(meth)
    return ",".join("%s#%s" % (c, "+".join(sorted(set(ms))))
                    for c, ms in sorted(by_class.items())), sorted(by_class)


def run_named(named):
    sel, classes = selector(named)
    for name in os.listdir(REPORTS) if os.path.isdir(REPORTS) else []:
        os.remove(os.path.join(REPORTS, name))
    cmd = ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + sel,
           "-DfailIfNoTests=false"]
    t0 = time.time()
    try:
        proc = subprocess.run(cmd, cwd=ZBOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=PER_RUN_TIMEOUT)
        rc, out = proc.returncode, proc.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return -1, set(), 0, "TIMEOUT after %ds" % PER_RUN_TIMEOUT, time.time() - t0, sel
    failing, ran = set(), 0
    for cls in classes:
        xml = os.path.join(REPORTS, "TEST-%s.%s.xml" % (CLS_PKG[cls], cls))
        if not os.path.isfile(xml):
            continue
        try:
            root = ET.parse(xml).getroot()
        except Exception:
            continue
        ran += int(root.get("tests") or 0)
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                failing.add("%s#%s" % (tc.get("classname").split(".")[-1],
                                       tc.get("name").split("[")[0]))
    return rc, failing, ran, out, time.time() - t0, sel


def main():
    mutants = MUTANTS
    check_only = "--check" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        mutants = [m for m in MUTANTS if any(a in m[0] for a in args)]
        if not mutants:
            print("FATAL: 选择器没命中任何变异体 id: %s" % args)
            return 2

    # ===== 注入前的三组预检：永挂体检 + 锚点唯一性 + 点名存在性 =====
    hang_files, hang_hits, sleep_hits = hang_check()
    print("永挂体检: 判级集 %d 个类 + 被注入 main 共 %d 个 java 文件；无界等待原语 %d 条 ⇒ %s"
          % (len({c for m in MUTANTS for c, _ in [x.split("#", 1) for x in m[6]]}),
             len(hang_files), len(UNBOUNDED_PATTERNS),
             "0 命中（可以占锁）" if not hang_hits else
             "命中 %d 处，禁止开跑（会占死全编队共享的变异锁）:\n  %s"
             % (len(hang_hits), "\n  ".join(hang_hits))), flush=True)
    print("永挂体检: 有界 Thread.sleep 登记 %d 处（不致命，只拖时间）：%s"
          % (len(sleep_hits), ", ".join(sleep_hits) or "无"), flush=True)
    if hang_hits:
        return 2

    bad = []
    for mid, fam, key, old, new, want, expected, note in mutants:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    missing = check_named_tests()
    if missing:
        bad.append("点名的 testcase 在测试源码里找不到: " + ", ".join(missing))
    if bad:
        print("FATAL 预检失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2
    suite_total, suite_files = measure_suite_total()
    print("预检: 锚点 %d 支全部唯一命中；点名 testcase %d 个全部存在；"
          "预期红集已冻结（%d 支 / %d 个断言点）；suite 总数=%d（%d 个测试文件，机械量得）"
          % (len(mutants), len({x for m in mutants for x in m[6]}), len(EXPECTED_RED_FROZEN),
             sum(len(v) for v in EXPECTED_RED_FROZEN.values()), suite_total, suite_files),
          flush=True)
    if check_only:
        return 0

    if not os.path.isdir(REPORTS):
        os.makedirs(REPORTS)

    lock_fh, lock_path = acquire_lock()
    if lock_fh is None:
        print("FATAL: 变异锁拿不到（%s）—— 按设计 rc=4 让路，回头整批重跑；"
              "本脚本不带死等，也不动别人的进程" % lock_path)
        return 4
    print("[锁] 已取得 %s" % lock_path, flush=True)

    baseline = {k: md5(abspath(k)) for k in SRC}
    rows = []
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0, "NO-RUN": 0}
    ledger = os.path.join(HERE, "LEDGER.tsv")

    def flush_ledger():
        with io.open(ledger, "w", encoding="utf-8") as fh:
            fh.write("\t".join(["id", "family", "target", "testcase", "injection",
                                "expected_red_set", "verdict", "detail"]) + "\n")
            for r in rows:
                fh.write("\t".join(r) + "\n")
            fh.write("\t".join(["#tally", "", "", "", "",
                                "RED-OK=%d|PARTIAL=%d|GREEN-BUT-MUTATED=%d|BROKEN=%d|NO-RUN=%d"
                                % (tally["RED-OK"], tally["PARTIAL"], tally["GREEN-BUT-MUTATED"],
                                   tally["BROKEN"], tally["NO-RUN"]), "", ""]) + "\n")
            fh.write("\t".join(["#mutants_injected", "", "", "", "", "",
                                str(len([r for r in rows if not r[0].startswith("CTRL-")])),
                                ""]) + "\n")
            fh.write("\t".join(["#expected_red_frozen_points", "", "", "", "", "",
                                str(sum(len(v) for v in EXPECTED_RED_FROZEN.values())), ""]) + "\n")
            fh.write("\t".join(["#suite_total_full_tests_measured", "", "", "", "", "",
                                str(suite_total), ""]) + "\n")
            fh.write("\t".join(["#hang_check", "", "", "", "", "",
                                "files=%d unbounded_hits=%d bounded_sleeps=%d"
                                % (len(hang_files), len(hang_hits), len(sleep_hits)),
                                ",".join(hang_files)]) + "\n")
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "p24_mutation.py",
                                time.strftime("%Y-%m-%dT%H:%M:%S%z")]) + "\n")

    families = sorted({m[1] for m in mutants})
    prey_ok = {}
    # ===== 阳性对照：每族 injection=NONE，点名的 testcase 必须真跑到且全绿 =====
    for fam in families:
        named = sorted(FAMILY_PREY.get(fam, set()))
        if not named:
            prey_ok[fam] = False
            rows.append(["CTRL-" + fam, fam, "-", "(无点名 testcase)", "NONE", "-", "NO-RUN",
                         "该族没有任何点名 testcase"])
            tally["NO-RUN"] += 1
            flush_ledger()
            continue
        rc, failing, ran, out, secs, sel = run_named(named)
        verdict = "BROKEN" if "COMPILATION ERROR" in out else (
            "NO-RUN" if (ran == 0 or failing) else "OK")
        prey_ok[fam] = verdict == "OK"
        rows.append(["CTRL-" + fam, fam, "-", sel, "NONE", ",".join(named), verdict,
                     "ran=%d rc=%s %.1fs 红=%s" % (ran, rc, secs, ",".join(sorted(failing)) or "-")])
        print("[对照] %-16s %-6s ran=%d 点名=%d %.1fs %s"
              % (fam, verdict, ran, len(named), secs, ",".join(sorted(failing)) or "全绿"), flush=True)
        flush_ledger()

    # ===== 逐支注入 =====
    for mid, fam, key, old, new, want, expected, note in mutants:
        if EXPECTED_RED_FROZEN[mid] != list(expected):
            print("FATAL: %s 的预期红集与冻结表不一致（表被改过）" % mid)
            return 2
        if not prey_ok.get(fam, False):
            rows.append([mid, fam, SRC[key], ",".join(expected), "SKIPPED", ",".join(expected),
                         "NO-RUN", "阳性对照进不来猎物：CTRL-%s 不是 OK" % fam])
            tally["NO-RUN"] += 1
            print("%-44s NO-RUN（阳性对照失败）" % mid, flush=True)
            flush_ledger()
            continue
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, out, secs, sel = run_named(expected)
        write(key, original)
        restored = md5(abspath(key)) == baseline[key]
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0:
            verdict = "NO-RUN"
        else:
            hit = set(expected) & failing
            if hit and not (failing - set(expected)) and len(hit) == len(expected):
                verdict = "RED-OK"
            elif hit or failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        who = ",".join(sorted(failing)) if failing else "全绿"
        print("%-44s %-18s 点名 %d/%d ran=%d rc=%s %.1fs 还原=%s | %s"
              % (mid, verdict, len(set(expected) & failing), len(expected), ran, rc, secs,
                 restored, who), flush=True)
        rows.append([mid, fam, SRC[key], sel,
                     "\\n".join(new.split("\n"))[:90] or "(删掉锚点)", ",".join(expected), verdict,
                     "红=%s ran=%d rc=%s %.1fs 还原=%s 说明=%s" % (who, ran, rc, secs, restored, note)])
        flush_ledger()
        if not restored:
            print("FATAL: %s 之后没还原成基线，停在这里（后面读数不可信）" % mid)
            break

    # ===== 还原对账：md5 + git status（工单：收尾时 src/main 必须干净）=====
    untracked = subprocess.run(["git", "-C", ZBOT, "status", "--porcelain"],
                               stdout=subprocess.PIPE).stdout.decode("utf-8", "replace")
    src_changed = [k for k in SRC if md5(abspath(k)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-18s %d" % (k, tally[k]))
    print("  注入后 src/main 与基线 md5 有差异的文件: %s" % (src_changed or "无（逐字节还原）"))
    print("  git status --porcelain: %s" % (untracked.strip().replace("\n", " | ") or "（空）"))
    try:
        fcntl.flock(lock_fh.fileno(), fcntl.LOCK_UN)
        lock_fh.close()
    except Exception:
        pass
    return 0 if not src_changed else 3


if __name__ == "__main__":
    sys.exit(main())
