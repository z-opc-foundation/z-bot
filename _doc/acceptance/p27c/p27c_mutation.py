#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P27c（delegate 三条"未做"收口：子代理审批当场 deny / 摘要上限+溢出落文件 / 线程池随 agent 关闭）
变异检验。

口径照 `_doc/acceptance/p30c/p30c_mutation.py`（同一套四杠纪律），判据一字不动：
  RED-OK   点名全红，且没有 `named ∪ allow_extra` 之外的红
  PARTIAL  点名一部分红 / 红了预期之外的人
  GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
  BROKEN   编译不过   NO-RUN  阳性对照没进猎物 / ran=0

预期红集按**谁读这个值**派生，不按主题（`feedback-mutation-expectation-forensics`）：
每条 named 都能说出"是哪条断言的消息会红"，说不出来的不写进 named。

族按测试类分（不是按主题分）：C=SubagentApprovalDenialTest、D=SummaryBudgetTest、
E=DelegateSummaryWiringTest、F=DelegatePoolLifecycleTest、G=ReadmeClaimsTest。
一支变异点名跨类用例是允许的 —— 五支 CTRL 在逐支注入**之前**全部跑过，
所以任何一条 named 都已证明"注入前跑得动且是绿的"。

三族各自要证的"有牙"（逐条对着本期主张）：
  * **子代理审批**：C1 摘机制、C2 摘队列结清、C3 摘接线、C4 把分支对所有人打开。
    四支的红集互不相同（C1⊃C2、C3⊃C1、C4 只红交互那支），因此"机制在"、"账平了"、
    "接上了"、"只作用于子代理"这四句话是四把分开的尺 —— 少任何一支，另一句就会被读成它。
  * **摘要预算**：D 族量纯函数（吸附/关掉/上抛/footer），E 族量两处朝向父模型的出口。
    E1（cap 写死常量）与 E3（同步出口不裁）的红集只差一条 `asyncExit…`，
    而 `capZeroDeliversTheWholeReplyAndWritesNothing` 在 E1 下**结构上分不开**
    （写死 24000 与"关掉"对一份 2020 字符的回复产出完全相同）⇒ 那条只能由源码守卫
    `capKeyIsActuallyReadByTheDelegateExits` 拦，这一条不记成行为覆盖。
  * **线程池**：F1 改回 static 的连带红是**时序相关**的（同一个 JVM 里前一支把共用池收了，
    后一支就提交不出去）⇒ 那三支进 `allow_extra`，不当作点名命中。

纪律（一条都不许破）：
  * **预期红集先写死在本文件里**，跑之前随脚本一起 commit ⇒ 不许事后凑；
  * 注入前逐条校验锚点出现次数，并校验"点名的 testcase 真实存在"；
  * 每支只点名它自己的 testcase，不跑全量（全量是杠①）；
  * **阳性对照**：每族先跑一次 `injection=NONE`，`named ∪ allow_extra` 必须真跑到(ran>0)且全绿；
  * 还原只从**本次运行开始时读进的原文**逐字节写回（绝不 `git checkout` —— git 基线是 HEAD，
    不是我开始测量那一刻），每支跑完 md5 对账；
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲；
  * 杠② 期间不碰 `~/.zbot`：注入体里没有任何一条写路径能落到真 profile
    （溢出目录那两支改的是"不按 configDir 推导"，落点是 tmp 的 sessions 目录或 null），
    且整跑前后各采一次样、末尾判等 —— 这是补上"杠② 对杠④ 的空档"。
      采样器自己有牙：`sample_teeth()` 在 tmp 里造一份 profile，改一个字节必须只让
      cfg_md5(+key_len) 变、加一个文件必须只让 entries 变，两向都判才算数。

复算: python3 -u _doc/acceptance/p27c/p27c_mutation.py [id 子串...]
锁:   $(git rev-parse --git-common-dir)/zbot-mutlock —— 只 try-lock，抢不到就 rc=4 退出
"""
import fcntl
import glob
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")

MAIN = "z-bot-core/src/main/java/com/zifang/z/bot"
SRC = {
    "agent": MAIN + "/agent/BotAgent.java",
    "dm": MAIN + "/delegate/DelegateManager.java",
    "sb": MAIN + "/delegate/SummaryBudget.java",
    "cfg": MAIN + "/config/BotConfig.java",
}

SUB = "SubagentApprovalDenialTest#"
SUM = "SummaryBudgetTest#"
WIR = "DelegateSummaryWiringTest#"
POL = "DelegatePoolLifecycleTest#"
RDC = "ReadmeClaimsTest#"

# 子代理那一族的四条用例（①②④⑤⑥ 与硬线那支③）
DENIED_INLINE = SUB + "childApprovalIsDeniedInlineAndTurnKeepsGoing"
INTERACTIVE = SUB + "interactiveParentStillAsksAndQueues"
ASYNC_DENIED = SUB + "asyncChildAlsoDeniesInsteadOfStalling"
NO_PENDING = SUB + "deniedApprovalLeavesNoPendingRowInThePrivateQueue"
DECLARED = SUB + "delegatedChildIsDeclaredNonInteractive"

# (id, family, 文件键, old, new, 锚点次数, named 杀手, allow_extra 连带, 说明)
MUTANTS = [
    # ===== C-子代理审批当场裁决 =====
    ("C1 摘掉 deny 分支（子代理退回'抛暂停'）", "C-子代理审批", "agent",
     "        if (Confirmations.isRequired(result)) {\n            if (nonInteractive) {",
     "        if (Confirmations.isRequired(result)) {\n            if (false && nonInteractive) {", 1,
     [DENIED_INLINE, ASYNC_DENIED, NO_PENDING], [],
     "回到修之前的形状：子代理撞闸门 ⇒ 抛 ToolConfirmationNeeded ⇒ chat() 把 "
     "`WAIT_CONFIRM:…` 当最终回复返回，DelegateManager 记 TASK_COMPLETED（父模型收到假成功）。"
     "红的断言：①`assertEquals(1, subagentAutoDeniedApprovals())`、④`assertFalse(result.contains(WAIT_CONFIRM))`、"
     "⑤同一条计数（⑤ 的第一条断言先红，但红集与 C2 不同 —— C2 下计数是对的、只有队列不空）。"),

    ("C2 摘掉队列结清（deny 了却留一条永不消费的待批）", "C-子代理审批", "agent",
     "                if (approvals != null && requestId != null) {",
     "                if (false && approvals != null && requestId != null) {", 1,
     [NO_PENDING, ASYNC_DENIED], [],
     "exec 闸门在本 agent 私有的 ApprovalService 里落了一条 pending；裁决当场做了却不结清 ⇒ "
     "红线 8 的账实分离。红的断言只有 ⑤`assertEquals(0, child.pendingApprovals().size())` 与 "
     "④`assertEquals(0, lastChild.pendingApprovals().size())` —— ① 里同样的两条已挪进 ⑤，"
     "所以 ① 在这一刀下是绿的：这就是 C1/C2 红集不同的原因（JUnit 一条方法内 fail-fast，"
     "同一条用例扛不住两把分开的尺，所以拆成 ⑤）。"),

    ("C3 摘掉 buildChild 的接线（机制在、没接上）", "C-子代理审批", "dm",
     "\n                .nonInteractive(true)", "", 1,
     [DENIED_INLINE, ASYNC_DENIED, NO_PENDING, DECLARED], [],
     "C1 打的是分支，这一支打的是**声明**。红集 = C1 的红集再加 ⑥ —— ⑥ 只问"
     "`lastChild.isNonInteractive()` 这一句话。少了 C3，'代码里有 deny 分支'与'子代理真的被"
     "声明成非交互'在测试里长得一样（⑥ 也是这一句的出处守卫）。"),

    ("C4 deny 分支对所有 agent 打开（顺手关掉交互申请）", "C-子代理审批", "agent",
     "        if (Confirmations.isRequired(result)) {\n            if (nonInteractive) {",
     "        if (Confirmations.isRequired(result)) {\n            if (true || nonInteractive) {", 1,
     [INTERACTIVE], [],
     "反向那半轴：`nonInteractive` 必须真的把两条路分开。红的断言：②"
     "`assertTrue(reply.startsWith(WAIT_CONFIRM_PREFIX))`（交互父代理不再回抛待批）。"
     "这一支不要求 ①④⑤⑥ 红 —— 它们在'人人都是子代理'下反而更'正常'，"
     "所以只点名 ②；红了别人就是意外。"),

    # ===== D-摘要预算（纯函数层）=====
    ("D1 摘掉头尾吸附到行边界", "D-摘要预算", "sb",
     "        int nl = head.lastIndexOf('\\n');\n"
     "        if (nl > headBudget * 0.5) {\n            head = head.substring(0, nl);\n        }\n"
     "        int tn = tail.indexOf('\\n');\n"
     "        if (tn >= 0 && tn < tailBudget * 0.5) {\n            tail = tail.substring(tn + 1);\n        }",
     "", 1,
     [SUM + "footerOffsetPointsAtTheOmittedMiddleAndIsOneIndexed"], [],
     "不吸附就是在一行中间劈一刀，而 footer 的 `offset=` 是按'头里已有的整行数 + 2'算的 ⇒ "
     "指针落到下一行的头上。红的断言：`assertEquals(line(7), lines.get(offset - 1))`"
     "（真值 line(7)，读到 line(8)）。注意 `overCapKeepsHeadAndTail…` 在这一刀下**仍是绿的**："
     "它只断 `head.contains(line(5))`/`!contains(line(10))`，半行不触到这两条 —— "
     "所以'吸附'这句话目前只有 offset 那一支在守，已按覆盖面缺口记进 EVIDENCE。"),

    ("D2 cap<=0 不再表示关掉上限", "D-摘要预算", "sb",
     "        if (cap <= 0 || summary.length() <= cap) {",
     "        if (cap < 0 || summary.length() <= cap) {", 1,
     [SUM + "capZeroOrNegativeDisablesTrimming", WIR + "capZeroDeliversTheWholeReplyAndWritesNothing"],
     [],
     "她的合同是 \"0 disables the ceiling\"（delegate_tool.py:590 注释）。红的断言：纯函数那条"
     "`assertEquals(text, trim(text, 0).text)`，接线那条 `assertFalse(delivered.contains(\"[SUMMARY TRUNCATED]\"))`"
     "（第一、二条断言在这一刀下仍成立：裁过后 tail 里确实还有全文）。"),

    ("D3 溢出落盘失败改成上抛（写不进去 ⇒ 走 catch 那一支）", "D-摘要预算", "sb",
     "        } catch (Exception e) {\n            return null;\n        } finally {",
     "        } catch (Exception e) {\n            throw new IllegalStateException(\"spill failed\", e);\n        } finally {", 1,
     [SUM + "spillDirectoryUnwritableStillTrimsButSaysSo"], [],
     "她的 `_spill_summary_to_file` 是 best-effort（:1615-1637）：磁盘不可写不能把裁切本身放弃，"
     "更不能让一次委托失败。红的断言：这一支整个用例以 IllegalStateException 报错。"
     "**run1 这一支恒绿过**：当时的 fixture 是「目录位置放一个文件」，那走的是 `mkdirs()` 返回 false"
     "的早退（catch 之前），所以量的不是同一件事 ⇒ 换 `setWritable(false)` 真造异常，"
     "另外两条早退各立一支（D5/D6）。"),

    ("D5 目录建不出来改成上抛（mkdirs=false 那一支）", "D-摘要预算", "sb",
     "            if (!dir.isDirectory() && !dir.mkdirs()) {\n                return null;\n            }",
     "            if (!dir.isDirectory() && !dir.mkdirs()) {\n"
     "                throw new Error(\"no spill dir\");\n            }", 1,
     [SUM + "spillDirectoryUnbuildableStillTrimsButSaysSo"], [],
     "早退那一支与 catch 那一支是两码事：一条压根不抛异常。红的断言：用例以该 throwable 报错。"
     "**run2 用 `IllegalStateException` 时恒绿** —— 那一支在 `try` 之内，抛出的 Exception 被同一"
     "方法的 `catch (Exception e) { return null; }` 吞掉（探针取证：字节码里 marker 有、行为没变，"
     "见 EVIDENCE §4），换 `Error` 才不被那道 catch 接住，这一刀才量得到'早退不许把失败带上抛'。"),

    ("D6 推不出目录（dir==null）改成上抛", "D-摘要预算", "sb",
     "        if (dir == null) {\n            return null;\n        }",
     "        if (dir == null) {\n            throw new IllegalStateException(\"no dir at all\");\n        }", 1,
     [SUM + "twoArgOverloadHasNoPointer"], [],
     "`summariesRoot()` 允许推不出来（她的 footer 就为此写「全文没能落盘」），两参重载更是恒无指针。"
     "红的断言：用例以 IllegalStateException 报错。"),


    ("D4 footer 里去掉'原文共 L 字符'", "D-摘要预算", "sb",
     "                .append(\" 字符，原文共 \").append(summary.length()).append(\" 字符\")",
     "                .append(\" 字符\")", 1,
     [SUM + "overCapKeepsHeadAndTailAndSpillsFullText",
      WIR + "syncExitTrimsIntoParentContextAndSpillsUnderProfileDir"], [],
     "footer 的三个数（显示 head N / tail M / 原文共 L）是父模型判断'我还差多少'的唯一依据。"
     "红的断言：`footer.contains(\"原文共 \" + text.length() + \" 字符\")` 与接线层同一条字符串。"),

    # ===== E-两处朝向父模型的出口 =====
    ("E1 摘要上限改成写死的常量", "E-委托出口裁切", "dm",
     "    private int summaryCap() {\n        return config == null\n"
     "                ? SummaryBudget.DEFAULT_MAX_SUMMARY_CHARS : config.getDelegateMaxSummaryChars();\n    }",
     "    private int summaryCap() {\n        return SummaryBudget.DEFAULT_MAX_SUMMARY_CHARS;\n    }", 1,
     [SUM + "capKeyIsActuallyReadByTheDelegateExits",
      WIR + "syncExitTrimsIntoParentContextAndSpillsUnderProfileDir",
      WIR + "asyncExitIsTrimmedByTheSameCap"], [],
     "`agent.delegate.max.summary.chars` 变成一件装饰品（用户改了、行为不变）。红的断言：源码守卫"
     "`assertTrue(src.contains(\"config.getDelegateMaxSummaryChars()\"))` + 两个出口各自"
     "`contains(\"[SUMMARY TRUNCATED]\")`（夹具 2020 字符 < 24000 ⇒ 不裁了）。"
     "**已知分不开**：`capZeroDelivers…` 在这一刀下是绿的（写死 24000 与关掉裁切对 2020 字符的"
     "回复产出逐字相同）⇒ 那一句只能由源码守卫拦，不记成行为覆盖。"),

    ("E2 溢出目录不再按 configDir 推导", "E-委托出口裁切", "dm",
     "        if (config != null && config.getConfigDir() != null) {\n"
     "            return new File(config.getConfigDir(), \"delegate/summaries\");\n        }",
     "        if (config != null && config.getConfigDir() != null && false) {\n"
     "            return new File(config.getConfigDir(), \"delegate/summaries\");\n        }", 1,
     [WIR + "configDirWinsOverWhereverTheChildSessionsSit"], [],
     "红线 1 的形状：写盘位置必须由 profile 推导，不能落到别处。注入体刻意不回落到 `~/.zbot` "
     "—— 杠② 期间一个字节都不许写进真 profile。"
     "**预期红集是按机制点的，不是按主题**：run1~run4 这一支判 GREEN-BUT-MUTATED，原因是那两条"
     "走真实接线的用例（`syncExitTrims…`/`asyncExitIsTrimmed…`）结构上分不开这两支 —— "
     "`BotAgent.build()` 在 config 模式下把 childSessions 定成 `<configDir>/delegate/children`，"
     "fall-through 之后 `new File(parentFile, \"summaries\")` 算出的仍是 "
     "`<configDir>/delegate/summaries`，同一个路径。于是补了一支绕开 `build()` 直接构造分岔形状"
     "的守卫（有 profile、children 在 profile 之外），这一支才真能杀它 —— "
     "那两条旧用例继续留在它们该在的地方（量裁切行为），不再点到这一支的名单里充数。"),

    ("E4 摘掉 config==null 的那条 fallback 推导", "E-委托出口裁切", "dm",
     "        if (childSessionDir != null && childSessionDir.getParentFile() != null) {\n"
     "            return new File(childSessionDir.getParentFile(), \"summaries\");\n        }",
     "        if (false) {\n"
     "            return new File(childSessionDir.getParentFile(), \"summaries\");\n        }", 1,
     [WIR + "noConfigSummariesRootDerivesFromTheChildSessionsParent"], [],
     "没有 profile 的程序化 agent 是活的形状（`BotAgent.build()` 走 `<sandbox 父目录>/delegate-children`"
     "那一支）；这一刀之后 `summariesRoot()` 恒 null ⇒ 全文只裁不落。红的断言："
     "`assertEquals(new File(base, \"summaries\"), bare.summariesRoot())`。"),

    ("E3 同步出口整份照回（只留异步那把尺）", "E-委托出口裁切", "dm",
     "            SummaryBudget.Trimmed trimmed = SummaryBudget.trim(reply, summaryCap(), summariesRoot(), live.id);",
     "            SummaryBudget.Trimmed trimmed = new SummaryBudget.Trimmed(reply, false, null);", 1,
     [WIR + "syncExitTrimsIntoParentContextAndSpillsUnderProfileDir",
      SUM + "capKeyIsActuallyReadByTheDelegateExits"], [],
     "与 E1 的区别：出口还在，只是同步那一道不走尺。红的断言：`delivered.contains(\"[SUMMARY TRUNCATED]\")`"
     "与源码守卫的 `assertEquals(2, count(\"SummaryBudget.trim(\"))`（数得见异步那处还剩 1）。"
     "台账里 `live.reply` 仍是全文这一条不在本期断言范围内（磁盘侧另有 REPLY_ON_DISK_CAP 那一档）。"),

    # ===== F-线程池随 agent 生命周期 =====
    ("F1 池改回进程共用一口（static）", "F-池生命周期", "dm",
     "    private final java.util.concurrent.ExecutorService asyncPool =",
     "    private static final java.util.concurrent.ExecutorService asyncPool =", 1,
     [POL + "poolFieldIsAnInstanceFieldNotAStatic", POL + "shutdownClosesThisAgentsPoolOnly"],
     [POL + "submitAfterShutdownRefusesAndDoesNotLeakASlot",
      POL + "asyncChildIsShutDownWhenItFinishes",
      POL + "syncChildIsStillShutDownWhenItReturns"],
     "roadmap §W5 P27 记的那条'未做'的原始形状。点名的两条：结构守卫"
     "`assertEquals(0, statics.size())`/`assertEquals(1, instancePools)`，行为尺"
     "`assertFalse(\"收掉一个 agent 不该把别人的池一起关掉\", b…asyncPoolShutdown())`。"
     "allow_extra 那三支的红是**时序相关**的（同一 JVM 里前一支收了共用池，后一支就提交不出去）"
     "⇒ 记为连带，不当点名命中，也不因为'多红了三条'判 PARTIAL。"),

    ("F2 shutdown() 不再把池交下去", "F-池生命周期", "agent",
     "        if (delegation != null) {\n            delegation.shutdown();\n        }",
     "        if (delegation != null) {\n            // delegation.shutdown();\n        }", 1,
     [POL + "shutdownClosesThisAgentsPoolOnly", POL + "submitAfterShutdownRefusesAndDoesNotLeakASlot"],
     [],
     "字段已经是个例池，但没人收 ⇒ 生命周期那条主张仍然不成立。红的断言："
     "`assertTrue(\"agent.shutdown() 没把委托池交下去\", a…asyncPoolShutdown())` 与"
     "`assertTrue(first.contains(\"异步委托未提交\"))`（池还开着 ⇒ 任务被接下，永不被拒）。"),

    ("F3 异步那条路收工不 shutdown 子代理", "F-池生命周期", "dm",
     "                        child.shutdown();",
     "                        // child.shutdown();", 1,
     [POL + "asyncChildIsShutDownWhenItFinishes"], [],
     "同族漏的第二半：同步 :234 一直有，异步没有 ⇒ 子 agent 的 cron/mcp/lifecycle 线程留在进程里。"
     "红的断言：`assertTrue(\"异步子代理收工后没被 shutdown…\", child.isShutDown())`。"
     "`syncChildIsStillShutDownWhenItReturns` 必须仍是绿的 —— 它是这一支的对照组"
     "（只注异步那一处，如果两支都红就是锚点选错了，不是覆盖变宽了）。"),

    ("F4 池已关时的拒收兜底摘掉", "F-池生命周期", "dm",
     "        } catch (java.util.concurrent.RejectedExecutionException closed) {",
     "        } catch (NullPointerException closed) {", 1,
     [POL + "submitAfterShutdownRefusesAndDoesNotLeakASlot"], [],
     "摘掉兜底 ⇒ RejectedExecutionException 从 `submitBackground` 直接冒到调用方，条目留在 QUEUED"
     "永久占着宽度闸门。红的断言：`assertTrue(first.contains(\"异步委托未提交\"))`（这一支以异常报错）。"),

    ("E5 取回时现裁（读路径每次拉取重写一份全文）", "E-委托出口裁切", "dm",
     "        SummaryBudget.Trimmed r = d.rendered;\n"
     "        if (r == null) {\n"
     "            r = d.rendered = SummaryBudget.trim(d.reply, summaryCap(), summariesRoot(), d.id);\n"
     "        }\n"
     "        return r;",
     "        return SummaryBudget.trim(d.reply, summaryCap(), summariesRoot(), d.id);", 1,
     [WIR + "repeatPullsRenderOnceAndShareOneSpillFile"], [],
     "杠③ run1 复算时才显形的缺陷：裁切原本挂在 `asyncResult()` 这条**读**路径上，而溢出文件名带"
     "毫秒戳 ⇒ 同一个委托 id 拉三次就多三个全文文件（她收集时只裁一次）。红的断言："
     "`assertEquals(1, spills.length)` 那一条（拉三次）；两条指针断言在这一刀下**仍是绿的**"
     "（每次现裁也各自含一个合法指针），所以点名只有这一支 —— 这一支就是"
     "\"读路径不许写盘\"这句话的全部覆盖。"),

    # ===== G-README/配置缺省对账 =====
    ("G1 BotConfig 摘要缺省 24000 → 24001", "G-缺省值对账", "cfg",
     "    private int delegateMaxSummaryChars = 24000;",
     "    private int delegateMaxSummaryChars = 24001;", 1,
     [SUM + "defaultCapIsTheHermesNumberAndTheConfigDefaultAgrees",
      RDC + "everyQuantitativeReadmeClaimMatchesTheRecomputation"], [],
     "两个读数她是一个：`SummaryBudget.DEFAULT_MAX_SUMMARY_CHARS`（hermes :590）与 config 字段缺省。"
     "红的断言：`assertEquals(SummaryBudget.DEFAULT_MAX_SUMMARY_CHARS, missing.getDelegateMaxSummaryChars())`"
     "与 README 尺`摘要字符上限缺省：README 写 24000，代码/台账重算是 24001`。"
     "这一支证明 README 那条 pin 真的一直量到 BotConfig 的字段初始化器。"),

    ("G2 BotConfig 宽度缺省 3 → 4", "G-缺省值对账", "cfg",
     "    private int delegateMaxChildren = 3;",
     "    private int delegateMaxChildren = 4;", 1,
     [RDC + "everyQuantitativeReadmeClaimMatchesTheRecomputation"], [],
     "README 的委托族还钉着另外两个缺省，这一支量宽度那一条。"
     "**为什么只有 README 红**：`DelegateManager` 的宽度取法是 `config == null ? 3 : …`，"
     "那条连发用例（`DelegateManagerLedgerTest#concurrencyGate…`）走的是 config==null 那一支"
     "⇒ 改字段缺省结构上碰不到它。已按覆盖面缺口记进 EVIDENCE（不是'已覆盖'）。"),
]

FAMILY_PREY = {}
for _m in MUTANTS:
    FAMILY_PREY.setdefault(_m[1], set()).update(_m[6])
    FAMILY_PREY[_m[1]].update(_m[7])


def measure_suite_total():
    """全量-suite 用例总数只认机械量：`git grep -c '@Test' HEAD` 求和（不认手敲常量）。
    空输入 / 解析不出形状一律 FATAL，不收读数。"""
    proc = subprocess.run(["git", "-C", ZBOT, "grep", "-c", "@Test", "HEAD", "--",
                           "z-bot-core/src/test"], stdout=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("FATAL: git grep 量具本身失败 rc=%d，不收读数" % proc.returncode)
    total, files = 0, 0
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        try:
            total += int(line.rsplit(":", 1)[1]); files += 1
        except ValueError:
            raise SystemExit("FATAL: git grep 输出行形状不对: %r" % line)
    if files == 0 or total <= 0:
        raise SystemExit("FATAL: 量到 suite 总数 %d（文件 %d 个）= 空输入，不收读数" % (total, files))
    return total


SUITE_TOTAL = measure_suite_total()
PER_RUN_TIMEOUT = 900

PROFILE_DIR = os.path.abspath(os.path.expanduser(
    os.environ.get("ZBOT_PROFILE_DIR") or "~/.zbot"))


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def abspath(key):
    return os.path.join(ZBOT, SRC[key])


def read(key):
    with io.open(abspath(key), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with io.open(abspath(key), "w", encoding="utf-8") as fh:
        fh.write(text)


# ---------------------------------------------------------------------------
# 杠④ 的那四点：整跑前后各采一次，末尾判等（杠② 不许碰到真 profile）
# 只读；key 只量长度、值一律不进日志、不进台账（红线）。
# ---------------------------------------------------------------------------
def sample_home(root):
    entries = sorted(os.listdir(root)) if os.path.isdir(root) else []
    out = [len(entries)]
    for rel in ("config.properties", "state.db"):
        p = os.path.join(root, rel)
        out.append(md5(p)[:8] if os.path.isfile(p) else "NOFILE")
    cfg = os.path.join(root, "config.properties")
    klen = -1
    if os.path.isfile(cfg):
        with io.open(cfg, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith("minimax.api.key="):
                    klen = len(line.rstrip("\r\n").split("=", 1)[1])
    out.append(klen)
    return tuple(out)


SAMPLE_FIELDS = ("entries", "cfg_md5", "db_md5", "key_len")


def sample_diff(before, after):
    return [n for n, a, b in zip(SAMPLE_FIELDS, before, after) if a != b]


def sample_teeth():
    """采样器自己的阳性对照（双向）：改一个字节必须点名 cfg_md5+key_len，
    加一个文件必须只点名 entries。任何一向不对 ⇒ 采样器没有牙 ⇒ 整跑的 4 点判等不算证据。"""
    tmp = tempfile.mkdtemp(prefix="p27c-sample-teeth-")
    fails = []
    try:
        cfg = os.path.join(tmp, "config.properties")
        with io.open(cfg, "w", encoding="utf-8") as fh:
            fh.write("minimax.api.key=aaaa\nllm.provider=glm\n")
        with io.open(os.path.join(tmp, "state.db"), "wb") as fh:
            fh.write(b"x" * 16)
        base = sample_home(tmp)
        if sample_diff(base, sample_home(tmp)) != []:
            fails.append("T1 同一份 profile 两次采样不相等")
        if base[3] != 4:
            fails.append("T2 key 长度不是 4（=%s）⇒ 采样器读错了" % (base[3],))
        with io.open(cfg, "w", encoding="utf-8") as fh:
            fh.write("minimax.api.key=aaaaaaaaaaaa\nllm.provider=glm\n")
        d = sample_diff(base, sample_home(tmp))
        if sorted(d) != ["cfg_md5", "key_len"]:
            fails.append("T3 改 config 字节应点名 cfg_md5+key_len，实际 %s" % (",".join(d) or "无"))
        base2 = sample_home(tmp)
        with io.open(os.path.join(tmp, "extra.txt"), "w", encoding="utf-8") as fh:
            fh.write("y\n")
        d2 = sample_diff(base2, sample_home(tmp))
        if d2 != ["entries"]:
            fails.append("T4 加一个文件应只点名 entries，实际 %s" % (",".join(d2) or "无"))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return fails


def acquire_lock():
    top = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--git-common-dir"],
                         stdout=subprocess.PIPE)
    common = top.stdout.decode("utf-8", "replace").strip()
    if not os.path.isabs(common):
        common = os.path.join(ZBOT, common)
    lock_path = os.path.join(common, "zbot-mutlock")
    fh = io.open(lock_path, "a+")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except IOError:
        print("FATAL: 变异锁被别的写手占着（%s）—— 本脚本不带等待，直接退出" % lock_path)
        return None, None
    return fh, lock_path


def selector(named):
    by_class = {}
    for full in named:
        cls, meth = full.split("#", 1)
        by_class.setdefault(cls, []).append(meth)
    return ",".join("%s#%s" % (c, "+".join(ms)) for c, ms in sorted(by_class.items())), sorted(by_class)


def run_named(named):
    sel, classes = selector(named)
    for path in glob.glob(os.path.join(REPORTS, "*")):
        os.remove(path)
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
        xmls = glob.glob(os.path.join(REPORTS, "TEST-*.%s.xml" % cls))
        for xml in xmls:
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


def known_testcases():
    """点名的 testcase 必须真实存在：抄错一个字母，"没红"会被读成"变异体活了"。"""
    known = set()
    dirs = [os.path.join(CORE, "src", "test", "java", "com", "zifang", "z", "bot"),
            os.path.join(CORE, "src", "test", "java", "com", "zifang", "z", "bot", "delegate")]
    for d in dirs:
        if not os.path.isdir(d):
            continue
        for fn in os.listdir(d):
            if not fn.endswith(".java"):
                continue
            with io.open(os.path.join(d, fn), encoding="utf-8") as fh:
                txt = fh.read()
            cls = fn[:-5]
            for line in txt.split("\n"):
                s = line.strip()
                if s.startswith("public void "):
                    known.add("%s#%s" % (cls, s[len("public void "):].split("(")[0].strip()))
    return known


def main():
    mutants = MUTANTS
    if len(sys.argv) > 1:
        mutants = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not mutants:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:])
            return 2

    if not os.path.isdir(REPORTS):
        os.makedirs(REPORTS)
    bad = []
    for mid, fam, key, old, new, want, named, extra, note in mutants:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2

    known = known_testcases()
    typos = []
    for mid, fam, key, old, new, want, named, extra, note in mutants:
        for t in list(named) + list(extra):
            if t not in known:
                typos.append("%s → %s" % (mid, t))
    if typos:
        print("FATAL 点名了不存在的 testcase（名字抄错，读数会全假）:")
        for t in typos:
            print("  " + t)
        return 2

    # 采样器自己得先有牙，否则"整跑前后相等"这条判据是空话
    teeth = sample_teeth()
    print("SAMPLER_TEETH|fails=%s" % (",".join(teeth) or "无"), flush=True)
    if teeth:
        print("FATAL: ~/.zbot 采样器没有牙 ⇒ 杠④ 的判等不可信，停")
        return 2

    lock_fh, lock_path = acquire_lock()
    if lock_fh is None:
        return 4

    home_before = sample_home(PROFILE_DIR) if os.path.isdir(PROFILE_DIR) else None
    print("BAR4_BEFORE|%s=%s" % (PROFILE_DIR, "/".join(str(x) for x in home_before)), flush=True)

    baseline = {k: md5(abspath(k)) for k in SRC}
    originals = {k: read(k) for k in SRC}
    rows = []
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0, "NO-RUN": 0}

    def flush_ledger():
        with io.open(os.path.join(HERE, "LEDGER.tsv"), "w", encoding="utf-8") as fh:
            fh.write("\t".join(["id", "family", "target", "testcase", "injection",
                                "named_kills", "allow_extra", "verdict", "detail"]) + "\n")
            for r in rows:
                fh.write("\t".join(r) + "\n")
            fh.write("\t".join(["#tally", "", "", "", "",
                                "RED-OK=%d|PARTIAL=%d|GREEN-BUT-MUTATED=%d|BROKEN=%d|NO-RUN=%d"
                                % (tally["RED-OK"], tally["PARTIAL"],
                                   tally["GREEN-BUT-MUTATED"], tally["BROKEN"], tally["NO-RUN"]),
                                "", "", ""]) + "\n")
            fh.write("\t".join(["#mutants_injected", "", "", "", "", "", "",
                                str(len([r for r in rows if not r[0].startswith("CTRL-")])), ""]) + "\n")
            fh.write("\t".join(["#suite_total_at_HEAD", "", "", "", "", "", "",
                                str(SUITE_TOTAL), ""]) + "\n")
            fh.write("\t".join(["#home_before", "", "", "", "", "", "",
                                "/".join(str(x) for x in home_before) if home_before else "NO-DIR",
                                ""]) + "\n")
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "", "p27c_mutation.py",
                                time.strftime("%Y-%m-%dT%H:%M:%S%z")]) + "\n")

    # ===== 阳性对照：每族 injection=NONE，点名的 testcase 必须真跑到且全绿 =====
    prey_ok = {}
    for fam in sorted(FAMILY_PREY):
        named = sorted(FAMILY_PREY[fam])
        if not named:
            prey_ok[fam] = False
            rows.append(["CTRL-" + fam, fam, "-", "(无点名 testcase)", "NONE", "-", "-", "NO-RUN",
                         "该族没有任何点名 testcase"])
            tally["NO-RUN"] += 1
            flush_ledger()
            continue
        rc, failing, ran, out, secs, sel = run_named(named)
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0 or failing:
            verdict = "NO-RUN"
        else:
            verdict = "OK"
        prey_ok[fam] = verdict == "OK"
        rows.append(["CTRL-" + fam, fam, "-", sel, "NONE", ",".join(named), "-", verdict,
                     "ran=%d rc=%s %.1fs 红=%s" % (ran, rc, secs,
                                                   ",".join(sorted(failing)) or "-")])
        print("[对照] %-14s %-6s ran=%d 点名=%d %.1fs %s" % (fam, verdict, ran, len(named), secs,
                                                            ",".join(sorted(failing)) or "全绿"),
              flush=True)
        flush_ledger()

    # ===== 逐支注入 =====
    for mid, fam, key, old, new, want, named, extra, note in mutants:
        if not prey_ok.get(fam, False):
            rows.append([mid, fam, SRC[key], "-", "SKIPPED", ",".join(named), ",".join(extra),
                         "NO-RUN", "阳性对照没进猎物：CTRL-%s 判 NO-RUN/BROKEN" % fam])
            tally["NO-RUN"] += 1
            print("%-46s NO-RUN（阳性对照失败）" % mid, flush=True)
            flush_ledger()
            continue
        write(key, originals[key].replace(old, new, 1))
        rc, failing, ran, out, secs, sel = run_named(sorted(set(named) | set(extra)))
        if "COMPILATION ERROR" in out:
            # 同机别人的 mvn 会跟我抢同一个 target/ —— 编译类失败重跑一次再定性
            time.sleep(20)
            rc, failing, ran, out, secs, sel = run_named(sorted(set(named) | set(extra)))
        write(key, originals[key])
        restored = md5(abspath(key)) == baseline[key]
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0:
            verdict = "NO-RUN"
        else:
            hit = set(named) & failing
            unexpected = failing - set(named) - set(extra)
            if hit == set(named) and not unexpected:
                verdict = "RED-OK"
            elif hit or failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        who = ",".join(sorted(failing)) if failing else "全绿"
        print("%-46s %-18s 杀手 %d/%d ran=%d rc=%s %.1fs 还原=%s | %s"
              % (mid, verdict, len(set(named) & failing), len(named), ran, rc, secs, restored, who),
              flush=True)
        rows.append([mid, fam, SRC[key], sel,
                     "\\n".join(new.split("\n"))[:90] or "(删掉锚点)",
                     ",".join(named), ",".join(extra) or "-", verdict,
                     "红=%s ran=%d rc=%s %.1fs 还原=%s" % (who, ran, rc, secs, restored)])
        flush_ledger()
        if not restored:
            print("FATAL: %s 之后没还原成基线，停在这里（后面读数不可信）" % mid)
            break

    home_after = sample_home(PROFILE_DIR) if os.path.isdir(PROFILE_DIR) else None
    drift = sample_diff(home_before, home_after) if (home_before and home_after) else ["采样对象缺失"]
    print("BAR4_AFTER |%s=%s" % (PROFILE_DIR, "/".join(str(x) for x in home_after)), flush=True)
    print("BAR4_DRIFT|%s" % (",".join(drift) or "无（整跑没碰真 profile）"), flush=True)
    with io.open(os.path.join(HERE, "LEDGER.tsv"), "a", encoding="utf-8") as fh:
        fh.write("\t".join(["#home_after", "", "", "", "", "", "",
                            "/".join(str(x) for x in home_after) if home_after else "NO-DIR",
                            "drift=" + (",".join(drift) or "无")]) + "\n")

    diff = subprocess.run(["git", "diff", "--name-only"], cwd=ZBOT,
                          stdout=subprocess.PIPE).stdout.decode("utf-8", "replace").strip()
    src_changed = [k for k in SRC if md5(abspath(k)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-18s %d" % (k, tally[k]))
    print("  注入后 src 有差异的文件: %s" % (src_changed or "无"))
    print("  git diff --name-only : %s" % (diff or "（空）"))
    return 0 if (not src_changed and not drift) else 3


if __name__ == "__main__":
    sys.exit(main())
