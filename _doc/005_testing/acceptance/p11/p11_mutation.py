#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P11 变异检验：把守卫逐条改坏，断言"具名测试"必须判红。

设计约束（踩过的坑）：
- 只认具名测试判红：编译崩 / mvn 非零但没有具名 failure ⇒ 记 CRASH，不算证据。
- 每个变异前记录源文件 md5，恢复后必须逐字节一致，否则当场 FATAL 停止（防止把在途代码改丢）。
- 锚点找不到 = FATAL（不允许"静默跳过"当成通过）。
"""
import hashlib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# 上溯四级 = 仓根（_doc/005_testing/acceptance/p11 → z-bot）；复算命令不依赖任何人的绝对路径
ZBOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, os.pardir, os.pardir))
SRC = os.path.join(ZBOT, "z-bot-core/src/main/java/com/zifang/z/bot")
REPORTS = os.path.join(ZBOT, "z-bot-core/target/surefire-reports")

EG = os.path.join(SRC, "tool/ExecGuard.java")
BT = os.path.join(SRC, "tool/BuiltinTools.java")
BA = os.path.join(SRC, "agent/BotAgent.java")
AS = os.path.join(SRC, "tool/ApprovalService.java")

MUTATIONS = [
    ("M1 白名单退回 substring", EG,
     "if (!prefix.get(i).equals(tokens.get(i))) {",
     "if (!tokens.get(i).startsWith(prefix.get(i))) {",
     "ExecGuardWhitelistTest",
     ["whitelistMatchesOnTokenBoundaryNotSubstring", "tokenBoundaryDecidesThroughDecide"]),
    ("M2 复合命令走白名单豁免", EG,
     "if (forms.opaque || isCompoundCommand(command)) {",
     "if (forms.opaque) {",
     "ExecGuardWhitelistTest",
     ["compoundCommandsNeverGetWhitelistExemption"]),
    ("M3 off 模式越过硬线表", EG,
     "        if (hardline != null) {\n",
     "        if (hardline != null && !MODE_OFF.equalsIgnoreCase(mode == null ? \"\" : mode.trim())) {\n",
     "ExecGuardHardlineTest,BuiltinToolsExecGateTest",
     ["offModeAllowsDangerousButNotHardline", "yoloModeDoesNotExecuteHardlineCommand"]),
    ("M4 mvn_build 拼接不过闸门", BT,
     'if (ExecGuard.isHardline("mvn " + goal)) {',
     'if (false && ExecGuard.isHardline("mvn " + goal)) {',
     "BuiltinToolsExecGateTest",
     ["mvnBuildCannotBeInjectedIntoHardline"]),
    ("M5 curl_test 拼接不过闸门", BT,
     "if (ExecGuard.isHardline(cmd)) {",
     "if (false && ExecGuard.isHardline(cmd)) {",
     "BuiltinToolsExecGateTest",
     ["curlTestCannotBeInjectedIntoHardline"]),
    ("M6 硬线文案丢前缀", EG,
     'return rule == null ? null : HARDLINE_PREFIX + "：" + rule + " — " + command;',
     "return rule;",
     "BuiltinToolsExecGateTest",
     ["mvnBuildCannotBeInjectedIntoHardline", "curlTestCannotBeInjectedIntoHardline"]),
    ("M7 模型自带确认章不被剥", BA,
     "        args.remove(Confirmations.CONFIRMED_ARG);\n",
     "",
     "BotAgentTest",
     ["parseArgsStripsModelSuppliedConfirmationStamp"]),
    ("M8 审批队列退成 LIFO", AS,
     "head = queue.pollFirst();",
     "head = queue.pollLast();",
     "ApprovalServiceTest,BuiltinToolsExecGateTest",
     ["resolveNextConsumesOldestFirst", "multiplePendingCommandsLineUpInOrder"]),
    ("M9 ALWAYS 不落盘", AS,
     "        } else if (r == Resolution.ALWAYS) {\n            approvePermanent(head.approvalKey());\n",
     "        } else if (r == Resolution.ALWAYS) {\n",
     "ApprovalServiceTest,BuiltinToolsExecGateTest",
     ["alwaysIsTheOnlyResolutionThatPersists", "alwaysResolutionPersistsThroughService"]),
    ("M10 SESSION 决议也落盘", AS,
     "} else if (r == Resolution.ALWAYS) {",
     "} else if (r == Resolution.ALWAYS || r == Resolution.SESSION) {",
     "ApprovalServiceTest",
     ["sessionApprovalAppliesToSameCommandOnlyAndNeverHitsDisk"]),
]


def md5(path):
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def failed_tests():
    out = set()
    if not os.path.isdir(REPORTS):
        return out
    for name in os.listdir(REPORTS):
        if not (name.startswith("TEST-") and name.endswith(".xml")):
            continue
        try:
            root = ET.parse(os.path.join(REPORTS, name)).getroot()
        except Exception as exc:
            print("      ! surefire xml unreadable %s: %s" % (name, exc))
            continue
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                out.add(tc.get("name"))
    return out


def tally():
    total = run = 0
    for name in os.listdir(REPORTS) if os.path.isdir(REPORTS) else []:
        m = re.match(r"TEST-.*\.xml$", name)
        if not m:
            continue
        root = ET.parse(os.path.join(REPORTS, name)).getroot()
        run += int(root.get("tests", 0))
        total += int(root.get("failures", 0)) + int(root.get("errors", 0))
    return run, total


def main():
    files = sorted({p for _, p, _, _, _, _ in MUTATIONS})
    baseline = {p: md5(p) for p in files}
    print("== baseline md5 ==")
    for p in files:
        print("   %s  %s" % (baseline[p][:12], os.path.basename(p)))

    results = []
    for label, path, anchor, repl, selector, expected in MUTATIONS:
        text = read(path)
        if anchor not in text:
            print("\n[ %s ] FATAL: 锚点不存在，变异未施加 —— 结论不可信" % label)
            results.append((label, "FATAL-ANCHOR", expected, set()))
            continue
        if text.count(anchor) != 1:
            print("\n[ %s ] FATAL: 锚点出现 %d 次，无法唯一定位" % (label, text.count(anchor)))
            results.append((label, "FATAL-AMBIG", expected, set()))
            continue
        write(path, text.replace(anchor, repl, 1))
        subprocess.run(["rm", "-rf", REPORTS])
        proc = subprocess.run(
            ["mvn", "-o", "test", "-pl", "z-bot-core", "-Dtest=%s" % selector,
             "-DfailIfNoTests=false"],
            cwd=ZBOT, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        rc = proc.returncode
        fails = failed_tests()
        r, t = tally()
        write(path, text)   # 逐字节还原为变异前内容
        assert md5(path) == baseline[path], "RESTORE FAILED on %s" % path
        missing = [e for e in expected if e not in fails]
        if rc == 0 and not fails:
            verdict = "GREEN-BUT-MUTATED (守卫无测试!)"
        elif missing:
            verdict = "PARTIAL (未判红: %s)" % ",".join(missing)
        else:
            verdict = "RED-OK"
        if rc != 0 and r == 0:
            verdict = "CRASH(编译/启动崩，非证据) rc=%d" % rc
        print("\n[ %s ] %s\n      rc=%d run=%d failed=%d names=%s"
              % (label, verdict, rc, r, t, sorted(fails)))
        results.append((label, verdict, expected, fails))

    print("\n== 汇总 ==")
    bad = 0
    for label, verdict, _, _ in results:
        flag = "  " if verdict == "RED-OK" else "!!"
        if verdict != "RED-OK":
            bad += 1
        print("%s %-44s %s" % (flag, label, verdict))
    print("\nfinal md5 check:", all(md5(p) == baseline[p] for p in files))
    print("GUARDS_WITHOUT_EVIDENCE=%d" % bad)


if __name__ == "__main__":
    sys.exit(main())
