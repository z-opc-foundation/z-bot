#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P20b（工具真注册表 / toolset 声明层 / 探测 TTL 与宽限窗 / 结果上限与溢出落盘 / MCP 桥级注销）
变异检验：把本期新增的每条守卫的**判据**逐个改坏，看有没有**具名测试**判红。

骨架照 _doc/005_testing/acceptance/p11c/p11c_mutation.py，纪律照本单工单：
  * 入口 `if __name__ == "__main__": sys.exit(main())`，import 不执行；
  * 注入前先校验锚点出现次数 + 盘上原文与 `git show <BASE>:<path>` 逐字节一致，
    漂了 FATAL 退出，一个源文件都不碰；
  * **控制跑先于任何注入**：先不带变异跑一遍全量，把全部 testcase 名收进 `all_names`；
    任何变异体点名期望的用例若不在 `all_names` 里（= 空跑的期望），当场 FATAL 退出，
    一次注入都不做（防"点名一个不存在的测试"这种自欺）；
  * 期望集在本文件里**先写死**（下面 MUTANTS 常量），跑完不许回填；
  * 判定只认 surefire XML 里的具名 testcase；mvn 退出码非 0 不算证据；编译不过算 BROKEN；
  * 互斥锁：`$(git rev-parse --git-common-dir)/zbot-mutlock`，只准
    `fcntl.flock(LOCK_EX|LOCK_NB)`（工单点名：`fcntl.lockf` 看不见 JVM 的 tryLock = 空跑）；
    拿不到锁直接退出并记账；跑前 `ps` 看有没有别人的 mvn，有就等，等待期间不改被测源码；
  * 写盘后 `flush + fsync + 重读比对` 才算"状态已落定"（P17 实测日志→落盘有 0.1–8.8ms 窗口）；
  * 按内存原文逐字节还原，**还原取证不吃脚本自己的话**：每个变异体都另记一行
    `LEDGER_RESTORE.tsv`，把盘上 md5 与 `git show <BASE>:<path>` 的 md5 逐字节比（机器算的）；
  * LEDGER.tsv / LEDGER_RESTORE.tsv 由本脚本机械输出，人一行都不许敲。

四类判定：RED-OK（点名的全红且没有多红的）/ PARTIAL（点名红了但另有未点名的红，
或点名的只红了一部分）/ GREEN-BUT-MUTATED（改坏了还全绿 = 该守卫在单测层没有活的猎物）
/ BROKEN（编译不过，必须换成能编译的等价旧写法重做）。

复算:
  python3 -u _doc/005_testing/acceptance/p20b/p20b_mutation.py --check          # 只做锚点/漂移/期望名存在性预检
  python3 -u _doc/005_testing/acceptance/p20b/p20b_mutation.py                  # 全量注入
  python3 -u _doc/005_testing/acceptance/p20b/p20b_mutation.py TS1 MB3          # 只跑 id 子串
  python3 -u _doc/005_testing/acceptance/p20b/p20b_mutation.py --hold-lock 25   # 攥锁探针（双向实测的一侧）
"""
import fcntl
import glob
import hashlib
import io
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
LOGS = os.path.join(ZBOT, ".cache", "p20b", "logs")
LEDGER = os.path.join(HERE, "LEDGER.tsv")
RESTORE_TSV = os.path.join(HERE, "LEDGER_RESTORE.tsv")

TS = "z-bot-core/src/main/java/com/zifang/z/bot/tool/"
MC = "z-bot-core/src/main/java/com/zifang/z/bot/mcp/"
SRC = {
    "toolsets": TS + "Toolsets.java",
    "toolkit": TS + "Toolkit.java",
    "bridge": MC + "McpBridge.java",
    "manager": MC + "McpManager.java",
}

# ===== 期望集（先写死，跑完不许回填）=====
# 每条: id, 文件键, 锚点原文, 替换文, 锚点期望次数, 点名期望红的用例, 猎物说明, 备注
MUTANTS = [
    # ---- toolset 声明层：清单三查 ----
    ("TS1 emptyCapabilityToolsets 写死空表", "toolsets",
     "empty.add(name);", "// 红线 2 审计被摘掉", 1,
     ["auditReportsACapabilityWhoseToolsWereAllDeregistered"],
     "同一条里的活猎物=摘掉 exec 后必须点名 [exec] + 空 toolkit 上必须点名四个能力子集",
     "审计若恒返回空表，builtinAssemblyFillsEveryDeclaredCapability 之类'不得含 X'的断言就全是空跑"),

    ("TS2 undeclaredToolsets 过滤反了", "toolsets",
     "if (!isDeclared(ts)) {", "if (isDeclared(ts)) {", 1,
     ["undeclaredToolsetNameIsReportedNotSilentlyAccepted",
      "builtinAssemblyFillsEveryDeclaredCapability"],
     "活猎物=清单外名 browser-automation 必须被点名",
     "反向之后：清单外的报不出来，清单内的反倒被报"),

    ("TS3 manifestToolsNeverRegistered 过滤反了", "toolsets",
     "if (!present.contains(tool)) {", "if (present.contains(tool)) {", 1,
     ["undeclaredToolsetNameIsReportedNotSilentlyAccepted",
      "builtinAssemblyFillsEveryDeclaredCapability"],
     "活猎物=裸 toolkit 上清单 11 个工具全算没注册",
     "第三查反向"),

    ("TS4 toolsetForTool 一律退回兜底槽", "toolsets",
     "return ts == null ? Toolkit.DEFAULT_TOOLSET : ts;",
     "return Toolkit.DEFAULT_TOOLSET;", 1,
     ["builtinAssemblyFillsEveryDeclaredCapability",
      "eachBuiltinToolLandsInItsDeclaredCapability",
      "readOnlyToolsDeclareParallelSafetyAndWriteToolsDoNot",
      "auditReportsACapabilityWhoseToolsWereAllDeregistered",
      "crossOwnerDeregisterOfWholeToolsetIsRefused"],
     "活猎物=eachBuiltinToolLands... 里 core/file/exec/net 四个子集的成员清单",
     "声明层的归属真源被摘掉，内建工具全落 builtin"),

    ("TS5 mcpToken 放过中划线", "toolsets",
     "|| (c >= '0' && c <= '9') || c == '.';",
     "|| (c >= '0' && c <= '9') || c == '.' || c == '-';", 1,
     ["mcpToolsetAndOwnerSpellingAreCentralized",
      "serverNamesWithDashesGetUnambiguousToolsets"],
     "活猎物=mcp-deep-kb 必须拼成 mcp-deep_kb 且注销后该 toolset 真空了",
     "toolset 名歧义（mcp-a-b 分不清 server 是 a 还是 a-b）"),

    ("TS6 capabilityNames 把兜底槽/动态族也算能力", "toolsets",
     "if (d.kind() == Kind.CAPABILITY) {", "if (d.kind() != null) {", 1,
     ["everyCapabilityToolsetDeclaresConsumerAndMembers",
      "builtinAssemblyFillsEveryDeclaredCapability",
      "auditReportsACapabilityWhoseToolsWereAllDeregistered"],
     "活猎物=能力子集排序后必须恰为 [core, exec, file, net]",
     "红线 2 的口径漏进兜底槽与 mcp- 前缀族"),

    # ---- 探测 TTL / 宽限窗 / schema 缓存键 ----
    ("TK1 快照键丢掉不可用名单", "toolkit",
     "if (current != null && current.generation == generation "
     "&& current.unavailable.equals(unavailable)) {",
     "if (current != null && current.generation == generation) {", 1,
     ["probeResultIsCachedForTheWholeTtlWindow",
      "transientFailureInsideGraceWindowKeepsTheToolAndIsNotCached",
      "availabilityFlipInvalidatesTheSchemaSnapshotEvenWithoutGenerationChange",
      "systemPromptToolListFollowsTheAvailabilityFlip"],
     "活猎物=探测翻脸（无注册/注销、代际没动）之后外发清单必须变空、指纹必须换",
     "只拿代际当键：掉线工具会被一直发给模型"),

    ("TK2 unavailableToolNames 恒空", "toolkit",
     "return registry.unavailableNames();", "return Collections.emptyList();", 1,
     ["probeResultIsCachedForTheWholeTtlWindow",
      "transientFailureInsideGraceWindowKeepsTheToolAndIsNotCached",
      "sustainedOutageHidesSchemaButDispatchStillReachesTheSlot",
      "availabilityFlipInvalidatesTheSchemaSnapshotEvenWithoutGenerationChange",
      "systemPromptToolListFollowsTheAvailabilityFlip",
      "registeredToolsIncludeUnavailableOnesWhileAllToolsDoesNot"],
     "活猎物=unavailableToolNames() 必须点得到 probe_me/flaky/dark/off 这些真被摘掉的名字",
     "探测结果对外露不出来，快照键也退化成只看待注销"),

    # p20d 改注点：上一棒的注点是 accessor Toolkit#generation()（:222，全仓只 1 处读），
    # 快照键用的是 snapshot() 里的 :310 ⇒ 期望集里那 4 条读快照的用例根本不碰被注入的那一行，
    # 判成 PARTIAL 3/7 是"量具的账"。本棒把注点挪到真正的缓存键那一行（p20d EVIDENCE §9.3）。
    # p20d R1 之后收窄一条：`deregisterActuallyRemovesTheSlotFromEveryView` 从期望里摘掉。
    # 依据（不是印象）：R1 实跑 `TK3 … 点名=4/5`（logs/r1_mut_TK3.log: Tests run: 527, Failures: 4），
    # 没红的正是它 —— ToolkitRegistryTest.java:42 那条在注册表变化之前从没读过任何快照视图
    # （:50/:51 两次读都在 :45 deregister 之后，第一次 snapshot() 就是新状态 ⇒ 键对不对都看不出来），
    # 且它读的是 tk.generation() 那个 accessor（:53），accessor 已不是本变异体的注点。
    # ⇒ 量具的账（期望写宽），不是产品的红；判定文本与注点语义一字未改（p20d EVIDENCE §9.3）。
    ("TK3 schema 快照键里的代际被钉死 0", "toolkit",
     "long generation = registry.generation();", "long generation = 0L;", 1,
     ["registerAndDeregisterEachInvalidateTheSchemaSnapshot",
      "exposedNamesTrackTheRegistryAfterNukeAndRepave",
      "unregisteringIsNotStubOverwrite",
      "reloadDropsTheDeadServersToolNamesFromGetToolNames"],
     "活猎物=注册/注销之后外发清单/清单文本/指纹必须换（快照键真的吃代际）",
     "代际不参与缓存键 ⇒ 上层缓存没有失效键（红线 6：schema 参与 prompt）。"
     "注点在 snapshot() 的键那一行，不是 Toolkit#generation() 那个 accessor"),

    # ---- 结果上限（全局 / 单工具声明 / UNBOUNDED）与溢出落盘 ----
    # p20d 机械补集（TK4）：R1 实跑读数 `TK4 … 点名=1/1 | 多红未点名: unboundedSentinelMeansNoTruncationAtAll`
    # （logs/LEDGER_R1.tsv TK4 行）。这一把红是**因果成立**的：上限不走注册表 ⇒ 声明 NO_MAX_RESULT_CHARS
    # 的工具退回全局 1000 的那条反向腿读不到全局值、被硬编码魔数顶掉 ⇒ 反向腿判红
    # （p20d EVIDENCE §9.1.1/§9.9.2）。判定文本与注点没动，只把实跑的差集补进期望集。
    ("TK4 上限不走注册表", "toolkit",
     "return registry.maxResultChars(name, maxResultChars);", "return maxResultChars;", 1,
     ["perToolDeclarationBeatsTheGlobalDefault",
      "unboundedSentinelMeansNoTruncationAtAll"],
     "活猎物=声明 64 的工具 resultCapFor 必须回 64 而不是全局 9000",
     "工单点名：上限走 registry 的 maxResultChars，不许自己再写一个魔数"),

    # p20d 改注点：上一棒的替换（摘掉 `cap == UNBOUNDED ||` 这个析取项）是**等价变异** ——
    # int 长度与 Long.MAX_VALUE 比恒真，任何用例都判不出来（本棒拿原注入+补完活猎物的用例
    # 实测复跑过一次，仍全绿，读数在 p20d EVIDENCE §9.4）。换成不等价的形式：哨兵不再表示
    # "不设限"，而表示"零上限"⇒ 只有声明 UNBOUNDED 的那批结果会被误截，别人一律不动。
    ("TK5 UNBOUNDED 哨兵失效（当成零上限截）", "toolkit",
     "if (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS || content.length() <= cap) {",
     "if (content.length() <= (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS ? 0L : cap)) {", 1,
     ["unboundedSentinelMeansNoTruncationAtAll"],
     "活猎物=同一条里两把钥匙：声明 UNBOUNDED 的 50_000 字符一字不改且溢出目录 0 个文件，"
     "换 NO_MAX_RESULT_CHARS 的同一份正文必须被全局上限 1000 截掉并落盘 1 个文件",
     "声明不设限是给 persist→read→persist 那条死循环留的口子；"
     "上一棒记的等价变异已由本棒实测确认后换成可判形式（p20d §9.4）"),

    ("TK6 预览不受上限约束", "toolkit",
     "int keep = (int) Math.min(previewChars, cap);", "int keep = (int) previewChars;", 1,
     ["previewIsClampedToTheCapItself"],
     "活猎物=previewChars=10000 / cap=300 时正文必须只留 300",
     "配错了不许把上下文撑爆"),

    # p20d 机械补集（TK7）：R1 实跑读数 `TK7 … 点名=3/3 | 多红未点名:
    # unboundedSentinelMeansNoTruncationAtAll,zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot`
    # （logs/LEDGER_R1.tsv TK7 行）。两条都是因果成立的多红，不是巧合：
    #   - unboundedSentinel… 的反向腿钉"被截的那一份必须恰有 1 个溢出文件"⇒ 落盘被摘掉就判红；
    #   - zbotHomeEnvLevel… 钉 PROBE_DIR_COUNT=1 / PROBE_SPILLED_BYTES=4000 ⇒ 同一把刀。
    # 判定文本与注点没动，只把实跑的差集补进期望集（p20d EVIDENCE §9.1.1/§9.9.2）。
    ("TK7 溢出落盘被摘掉", "toolkit",
     "String path = dir == null ? null : spillToFile(dir, result.getName(), "
     "result.getCallId(), content);",
     "String path = null;", 1,
     ["oversizedResultSpillsFullTextToDiskAndKeepsOnlyAPreview",
      "spillGoesToADirectoryCreatedOnDemand",
      "errorResultsAreCappedTheSameWayAndStayMarkedError",
      "unboundedSentinelMeansNoTruncationAtAll",
      "zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot"],
     "活猎物=落盘文件必须存在、字节与原文逐字相同、路径要回在回执里",
     "只截断不落盘 = 全文永久丢失"),

    ("TK8 上限边界从 <= 改成 <", "toolkit",
     "if (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS || content.length() <= cap) {",
     "if (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS || content.length() < cap) {", 1,
     ["exactlyAtTheCapIsNotTouched"],
     "活猎物=正好 120 字符 / 上限 120 必须一字不改",
     "off-by-one：边界上不该动结果"),

    ("TK9 溢出目录解析不看 $ZBOT_HOME", "toolkit",
     "        String home = System.getenv(ZBOT_HOME_ENV);\n"
     "        if (home != null && !home.trim().isEmpty()) {\n"
     "            return new File(new File(home.trim()), \"tool-results\");\n"
     "        }\n",
     "        // 摘掉 $ZBOT_HOME 这一级\n", 1,
     ["zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot"],
     "活猎物=p20d 新用例起一个真带 ZBOT_HOME 的子 JVM（OverflowDirEnvProbe）⇒ 解析必须是 "
     "<profile>/tool-results 且全文落在那儿；同一把探针摘掉 ZBOT_HOME 必须解析成 NULL 且不落盘",
     "红线 1（跟着 profile 走）；上一棒记'单测层结构上打不到'，p20d 用子 JVM 把这一级接上了"
     "（子 JVM 带 -Duser.home=<假 home>，真的 ~/.zbot 一个字节都不许多）"),

    ("TK10 溢出文件名净化失效", "toolkit",
     "if (name.length() > 120) {", "if (name.length() > 100000) {", 1,
     ["spilledFileNamesAreSanitizedAndSalted"],
     "活猎物=400 字符工具名的文件名长度必须被压住",
     "路径注入/超长名不许原样落盘"),

    ("TK11 toolset 整组注销的 owner 参数被顶成内建 owner", "toolkit",
     "return registry.deregisterByToolset(toolset, owner);",
     "return registry.deregisterByToolset(toolset, DEFAULT_OWNER);", 1,
     ["deregisterToolsetRemovesEveryMemberAndOnlyThose",
      "deregisterToolsetCleansNamesTheBridgeNoLongerKnowsAbout",
      "exposedNamesTrackTheRegistryAfterNukeAndRepave",
      "crossOwnerDeregisterOfWholeToolsetIsRefused",
      "aBridgeCannotDeregisterAnotherOwnersToolByToolsetName",
      "bridgeOnlyNukesItsOwnToolset", "serverNamesWithDashesGetUnambiguousToolsets",
      "unregisteringIsNotStubOverwrite", "unregisterLeavesNoPlaceholderBehindInAnyListView",
      "oldServerToolNamesAreGoneAfterUnregisterInsideTheSameJvm",
      "deregistrationHappensInsideThisJvmNotByRestartingIt",
      "managerStopAllClearsEveryBridgeToolsetAndReloadRepaves",
      "failedRegisterAllDoesNotLeaveHalfRegisteredTools",
      # ↓ p20d 机械补进：这三条是上一棒 84ca7b9 那一跑的实跑差集（EVIDENCE §2.2(6) 点过名），
      #   这条是本棒新写的桥级用例（owner 被顶成内建 owner 之后整组注销直接抛"不能注销"）。
      "bridgeRegistersAnAvailabilityProbeBackedByTheConnection",
      "reloadDropsTheDeadServersToolNamesFromGetToolNames",
      "repavingWithTheSameToolNameWorksAfterUnregister",
      "unregisterAllCleansSlotsTheBridgeNeverRecorded"],
     "活猎物=owner 传 mcp:srv 时那 3 个成员必须真被摘掉（removed.size()==3）",
     "owner 边界一旦退化，桥级 nuke 变静默 no-op，且别人家的槽也保不住"),

    # ---- MCP 桥级注销 ----
    ("MB1 桥不接可用性探测", "bridge",
     "new ToolDescriptor(toolset(), false, probe, owner()));",
     "new ToolDescriptor(toolset(), false, null, owner()));", 1,
     ["bridgeRegistersAnAvailabilityProbeBackedByTheConnection"],
     "活猎物=取 schema 必须真探一次（probeInvocations 1→TTL 窗内不涨）、断连后不许再外发",
     "check_fn 断线 ⇒ 掉线 server 的 schema 继续发给模型"),

    ("MB2 桥按'记住的名字'逐个注销", "bridge",
     "List<String> removed = toolkit.deregisterToolset(toolset(), owner());",
     "        List<String> removed = new ArrayList<String>();\n"
     "        for (String name : new ArrayList<String>(registered)) {\n"
     "            if (toolkit.deregister(name, owner())) {\n"
     "                removed.add(name);\n"
     "            }\n"
     "        }\n", 1,
     ["unregisterAllCleansSlotsTheBridgeNeverRecorded"],
     "活猎物=p20d 新用例造出'注册表里有、桥没记住'的桥级现场（同名 server 的第二个桥实例 + "
     "上一轮留下的僵尸槽，同一个 mcp-<server> toolset 与 mcp:<server> owner）⇒ 逐个删记住的名字"
     "必留两个僵尸，整组注销才清得干净",
     "工单原文：故意不拿'我记住的那批名字'当结论；上一棒说桥级用例造不出这个分岔，"
     "p20d 用同名双桥 + 僵尸槽造出来了（§9.6）"),

    ("MB3 退回同名 stub 覆盖（P20 要杀的旧实现）", "bridge",
     "List<String> removed = toolkit.deregisterToolset(toolset(), owner());",
     "        List<String> removed = new ArrayList<String>();\n"
     "        for (final String name : new ArrayList<String>(registered)) {\n"
     "            toolkit.register(new BaseTool(name, \"[unregistered]\", emptyObjectSchema()) {\n"
     "                @Override\n"
     "                protected ToolResult doExecute(Map<String, Object> args) {\n"
     "                    return ToolResult.error(\"MCP server 已断开，工具已注销\");\n"
     "                }\n"
     "            }, new ToolDescriptor(toolset(), false, null, owner()));\n"
     "            removed.add(name);\n"
     "        }\n", 1,
     ["unregisteringIsNotStubOverwrite", "unregisterLeavesNoPlaceholderBehindInAnyListView",
      "reloadDropsTheDeadServersToolNamesFromGetToolNames",
      "deregistrationHappensInsideThisJvmNotByRestartingIt",
      "managerStopAllClearsEveryBridgeToolsetAndReloadRepaves",
      "failedRegisterAllDoesNotLeaveHalfRegisteredTools",
      "oldServerToolNamesAreGoneAfterUnregisterInsideTheSameJvm",
      "bridgeRegistersAnAvailabilityProbeBackedByTheConnection"],
     "活猎物=注销后 tk.size() 必须掉到 0、getToolNames() 必须不含旧名、清单文本不含 [unregistered]",
     "这就是工单点名的'反向断言在修之前必须先红一次'那条：留桩=占着 schema 名额"),

    ("E1 stopAll 只断连不注销", "manager",
     "b.unregisterAll();", "b.client().disconnect();", 1,
     ["managerStopAllClearsEveryBridgeToolsetAndReloadRepaves",
      "reloadDropsTheDeadServersToolNamesFromGetToolNames",
      "failedRegisterAllDoesNotLeaveHalfRegisteredTools"],
     "活猎物=stopAll 之后 getToolNames() 必须是空表",
     "工单点名的 stopAll 清空注册表与外发清单"),
]


def out(msg=""):
    sys.stdout.write(str(msg) + "\n")
    sys.stdout.flush()


def sh(cmd, cwd=ZBOT):
    return subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def disk_md5(rel):
    with open(os.path.join(ZBOT, rel), "rb") as fh:
        return md5_bytes(fh.read())


def git_md5(base, rel):
    pr = sh(["git", "show", "%s:%s" % (base, rel)])
    if pr.returncode != 0:
        return None
    return md5_bytes(pr.stdout)


def git_bytes(base, rel):
    pr = sh(["git", "show", "%s:%s" % (base, rel)])
    return pr.stdout if pr.returncode == 0 else None


def lock_path():
    common = sh(["git", "rev-parse", "--git-common-dir"]).stdout.decode().strip()
    if not os.path.isabs(common):
        common = os.path.join(ZBOT, common)
    return os.path.join(common, "zbot-mutlock")


def acquire_lock(need=True):
    """只准 flock(LOCK_EX|LOCK_NB)：工单点名 lockf 看不见 JVM 的 tryLock（= 空跑）。"""
    p = lock_path()
    fd = os.open(p, os.O_RDWR | os.O_CREAT, 0o600)
    if not need:
        return None
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as e:
        os.close(fd)
        out("FATAL 锁被占（%s）: %s ⇒ 本棒不硬跑，按工单记'杠②未跑完：锁被占'" % (p, e))
        out("  锁文件邻居信息: %s" % subprocess.list2cmdline(
            ["ls", "-l", p]).replace("\n", " "))
        return "BUSY"
    os.write(fd, ("%d %s\n" % (os.getpid(), time.strftime("%H:%M:%S"))).encode())
    return fd


def report_dirs():
    """模块名单不写死：整仓 glob */target/surefire-reports（含嵌套模块）。"""
    pats = [os.path.join(ZBOT, "*", "target", "surefire-reports"),
            os.path.join(ZBOT, "*", "*", "target", "surefire-reports")]
    out = []
    for pat in pats:
        out += [p for p in sorted(glob.glob(pat)) if os.path.isdir(p)]
    return out


def parse_reports():
    failing, names, ran = set(), set(), 0
    for d in report_dirs():
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(d, fn)).getroot()
            except Exception:
                continue
            ran += int(root.get("tests") or 0)
            for tc in root.iter("testcase"):
                nm = (tc.get("name") or "").split("[")[0]
                names.add(nm)
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.add(nm)
    return failing, names, ran


def clear_reports():
    for d in report_dirs():
        for fn in os.listdir(d):
            os.remove(os.path.join(d, fn))


MVN_MARKERS = ("classworlds", "surefirebooter", "maven.conf", "plexus")


def foreign_mvn_running():
    """跑前 ps：有别人的 mvn/surefire 就等（等待期间一个源文件都不碰）。

    只认 maven 真身留下的命令行标记（plexus classworlds 启动器 / surefirebooter）。
    踩过的坑：拿 `grep mvn` 当判据会把**任何命令文本里提到过 mvn 的 shell**
    （包括本棒自己的监控 shell、以及本脚本自己）当成邻居 ⇒ 越等越死。
    """
    pr = sh(["ps", "-axww", "-o", "pid=,command="])
    me = os.getpid()
    hits = []
    for line in pr.stdout.decode("utf-8", "replace").splitlines():
        if not any(m in line for m in MVN_MARKERS):
            continue
        try:
            pid = int(line.split()[0])
        except (IndexError, ValueError):
            continue
        if pid == me or ZBOT in line or "p20b_mutation.py" in line:
            continue
        hits.append(line.strip()[:200])
    return hits


def wait_for_quiet(timeout_s, quiet_s=5):
    deadline = time.time() + timeout_s
    while True:
        hits = foreign_mvn_running()
        if not hits:
            return True
        if time.time() > deadline:
            out("FATAL 跑了 %ds 还有别人的 mvn 在飞，本棒不硬来：\n  %s" % (timeout_s, "\n  ".join(hits)))
            return False
        out("  等别人的 mvn（%d 个）… %s" % (len(hits), hits[0][:80]))
        time.sleep(quiet_s)


def write_and_verify(rel, text):
    """落定 = flush+fsync 后重读逐字节比；日志/返回值都不算'盘上已落定'。"""
    p = os.path.join(ZBOT, rel)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(text)
        fh.flush()
        os.fsync(fh.fileno())
    with open(p, encoding="utf-8") as fh:
        back = fh.read()
    return back == text


def run_mvn(tag):
    log = os.path.join(LOGS, "mut_%s.log" % tag)
    if not os.path.isdir(LOGS):
        os.makedirs(LOGS)
    clear_reports()
    t0 = time.time()
    pr = subprocess.run(["mvn", "-o", "test"], cwd=ZBOT,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = pr.stdout.decode("utf-8", "replace")
    with io.open(log, "w", encoding="utf-8") as fh:
        fh.write("$ cd %s && mvn -o test\n  rc=%d wall=%.1fs\n%s\n"
                 % (ZBOT, pr.returncode, time.time() - t0, text))
    failing, names, ran = parse_reports()
    return pr.returncode, failing, names, ran, os.path.relpath(log, ZBOT)


def preflight(base, selected):
    """锚点次数 + 盘上原文对 git 权威 + 期望用例存在性（都在注入之前）。"""
    fatal = []
    for rel in sorted(set(SRC[k] for _, k, _, _, _, _, _, _ in selected)):
        b = git_bytes(base, rel)
        if b is None:
            fatal.append("git show %s:%s 取不到" % (base, rel))
            continue
        with open(os.path.join(ZBOT, rel), "rb") as fh:
            if fh.read() != b:
                fatal.append("盘上原文与 git show %s:%s 不一致（代码已漂，别信读数）" % (base, rel))
    for mid, key, old, new, want, expected, prey, note in selected:
        with open(os.path.join(ZBOT, SRC[key]), encoding="utf-8") as fh:
            got = fh.read().count(old)
        if got != want:
            fatal.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    for mid, _, _, _, _, expected, _, _ in selected:
        if not expected:
            out("  注：%s 的期望集为空 —— 该守卫在单测层没有活的猎物，"
                "脚本会把它判成 GREEN-BUT-MUTATED 并记'未覆盖'" % mid)
    return fatal


def main():
    args = [a for a in sys.argv[1:]]
    base = os.environ.get("P20B_BASE", "HEAD")
    if "--base" in args:
        i = args.index("--base")
        base = args[i + 1]
        del args[i:i + 2]
    if "--hold-lock" in args:
        i = args.index("--hold-lock")
        secs = int(args[i + 1])
        del args[i:i + 2]
        fd = acquire_lock(True)
        if fd == "BUSY":
            return 5
        out("LOCK HELD pid=%d %ds" % (os.getpid(), secs))
        time.sleep(secs)
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        out("LOCK RELEASED pid=%d" % os.getpid())
        return 0
    mode_check = "--check" in args
    want_lock = "--want-lock" in args
    args = [a for a in args if a not in ("--check", "--want-lock")]
    if want_lock:
        # 锁的双向实测用这条：能拿到就 rc=0 立刻松开，拿不到就 rc=5 且不碰任何源文件
        fd = acquire_lock(True)
        if fd == "BUSY":
            return 5
        for k, v in SRC.items():
            out("  拿到锁，源文件 md5 未动: %s %s" % (disk_md5(v), v))
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        out("LOCK ACQUIRED-THEN-RELEASED pid=%d" % os.getpid())
        return 0
    if args:
        selected = [m for m in MUTANTS if any(a in m[0] for a in args)]
        if not selected:
            out("FATAL 选择器没命中任何变异体 id: %s" % args)
            return 2
    else:
        selected = list(MUTANTS)

    out("== 变异体 %d 个，基线 git rev = %s，锁 = %s"
        % (len(selected), sh(["git", "rev-parse", base]).stdout.decode().strip(), lock_path()))
    fatal = preflight(base, selected)
    if fatal:
        out("FATAL 预检失败（一个源文件都没碰）:")
        for line in fatal:
            out("  " + line)
        return 2
    out("  预检过：锚点次数 + 盘上原文 == git show + 无漂移")
    if mode_check:
        out("  --check：到此为止，不做注入")
        return 0

    lock = acquire_lock(True)
    if lock == "BUSY":
        # 每个变异体各记一行"未跑：锁被占"（第一版这里写成 [[m[0], ...]] 直接 NameError，
        # 等于把兜底路径弄成崩溃 —— 崩溃不产生台账，记在 EVIDENCE §6）
        rows = [[x[0], "BROKEN", str(len(x[5])), "0", "未跑：锁被占",
                 "杠②未跑完：锁被占（flock LOCK_EX|LOCK_NB 当场不可得）", "n/a"] for x in MUTANTS]
        with io.open(LEDGER, "w", encoding="utf-8") as fh:
            fh.write("\t".join(HEADERS) + "\n")
            for r in rows:
                fh.write("\t".join(c.replace("\t", " ") for c in r) + "\n")
        return 5
    if not wait_for_quiet(int(os.environ.get("P20B_MVN_WAIT", "900"))):
        return 6

    # 控制跑：不带任何变异，先确认全绿 + 收全部 testcase 名（期望名不存在 = 空跑，当场拒）
    out("== 控制跑（不注入）")
    rc, failing, all_names, ran, clog = run_mvn("control")
    out("  控制跑 rc=%d 跑了 %d 条 红=%s 名字集=%d 条 日志=%s"
        % (rc, ran, sorted(failing) or "无", len(all_names), clog))
    phantoms = []
    for mid, _, _, _, _, expected, _, _ in selected:
        for nm in expected:
            if nm not in all_names:
                phantoms.append("%s 点名了不存在的用例 %s" % (mid, nm))
    if phantoms or failing:
        for line in phantoms:
            out("FATAL " + line)
        if failing:
            out("FATAL 控制跑就不是全绿（%s）⇒ 基线不可信，一次注入都不做" % sorted(failing))
        return 2

    originals = {k: git_bytes(base, SRC[k]).decode("utf-8") for k in SRC}
    rows, rrows = [], []
    tally = {}
    for mid, key, old, new, want, expected, prey, note in selected:
        rel = SRC[key]
        before = disk_md5(rel)
        if before != git_md5(base, rel):
            out("FATAL %s 注入前盘上 md5 已与 git 不符（%s）" % (mid, rel))
            return 3
        mutated = originals[key].replace(old, new, 1)
        if not wait_for_quiet(int(os.environ.get("P20B_MVN_WAIT", "900"))):
            return 6
        if not write_and_verify(rel, mutated):
            out("FATAL %s 注入未落定（重读与内存原文不一致）" % mid)
            return 3
        during = disk_md5(rel)
        bmd5 = git_md5(base, rel)
        rc, failing, _names, ran, log = run_mvn(mid.split()[0])
        with io.open(os.path.join(ZBOT, log), encoding="utf-8") as fh:
            mvn_text = fh.read()
        broken = "COMPILATION ERROR" in mvn_text
        if not write_and_verify(rel, originals[key]):
            out("FATAL %s 还原未落定" % mid)
            return 3
        after = disk_md5(rel)
        # 这一格读作"注入有没有真进盘"：盘上 md5 != git show md5 ⇒ 进盘了（yes）。
        # 第一版把这个三元写反了（差异成立时打印 "no(注入确实改了盘)"），记在 EVIDENCE §6。
        rrows.append([mid, rel, bmd5, during,
                      "yes(disk!=git show)" if during != bmd5 else "NO(注入没进盘!)",
                      after, "ok" if after == bmd5 else "RESTORE-FAILED"])
        hit = sorted(set(expected) & failing)
        extra = sorted(failing - set(expected))
        if broken:
            verdict = "BROKEN"
        elif expected and len(hit) == len(expected) and not extra:
            verdict = "RED-OK"
        elif hit or extra:
            verdict = "PARTIAL"
        else:
            verdict = "GREEN-BUT-MUTATED"
        tally[verdict] = tally.get(verdict, 0) + 1
        who = ",".join(sorted(failing)) if failing else "全绿（跑了 %d 条）" % ran
        detail = note + (" | 多红未点名: " + ",".join(extra) if extra else "")
        out("%-46s %-18s 点名=%d/%d rc=%s ran=%s 还原=%s/%s"
            % (mid, verdict, len(hit), len(expected), rc, ran,
               "ok" if after == bmd5 else "FAILED", "mutated" if during != bmd5 else "NO-OP"))
        if verdict != "RED-OK":
            out("     %s" % detail)
        rows.append([mid, verdict, str(len(expected)), str(len(hit)), who, detail,
                     "ok" if after == bmd5 else "RESTORE-FAILED"])

    still = [k for k, v in SRC.items() if disk_md5(v) != git_md5(base, v)]
    with io.open(LEDGER, "w", encoding="utf-8") as fh:
        fh.write("\t".join(HEADERS) + "\n")
        for r in rows:
            fh.write("\t".join(c.replace("\t", " ") for c in r) + "\n")
    with io.open(RESTORE_TSV, "w", encoding="utf-8") as fh:
        fh.write("\t".join(RHEADERS) + "\n")
        for r in rrows:
            fh.write("\t".join(c.replace("\t", " ") for c in r) + "\n")
    out("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
        out("  %-18s %d" % (k, tally.get(k, 0)))
    out("  LEDGER.tsv %d 行 / LEDGER_RESTORE.tsv %d 行（均为脚本产物）" % (len(rows), len(rrows)))
    out("  SRC_MD5_STABLE=%s" % ("yes" if not still else "no:" + ",".join(still)))
    fcntl.flock(lock, fcntl.LOCK_UN)
    os.close(lock)
    return 0 if not still else 3


HEADERS = ["id", "verdict", "named_expected", "named_hit", "tests_that_went_red", "note", "restored"]
RHEADERS = ["id", "file", "git_show_md5_baseline", "disk_md5_during_injection",
            "injection_landed", "disk_md5_after_restore", "restored"]

if __name__ == "__main__":
    sys.exit(main())
