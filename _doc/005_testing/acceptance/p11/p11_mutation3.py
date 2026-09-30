#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P11 变异检验 第二轮：补第一轮没打中的三条守卫（M10 空变异、opaque、注释剥离）。

规则同第一轮：只认具名测试判红；锚点必须唯一定位；源文件逐字节还原，md5 不符立即 FATAL。
"""
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

# 上溯四级 = 仓根（_doc/005_testing/acceptance/p11 → z-bot）；复算命令不依赖任何人的绝对路径
ZBOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, os.pardir, os.pardir))
SRC = os.path.join(ZBOT, "z-bot-core/src/main/java/com/zifang/z/bot")
REPORTS = os.path.join(ZBOT, "z-bot-core/target/surefire-reports")
EG = os.path.join(SRC, "tool/ExecGuard.java")
AS = os.path.join(SRC, "tool/ApprovalService.java")
BA = os.path.join(SRC, "agent/BotAgent.java")

MUTATIONS = [
    ("M13 新待批不取代旧的", BA,
     "        approvals.supersedePending(sessionKey, requestId);\n",
     "",
     "BotAgentTest",
     ["newerPendingSupersedesTheAbandonedOne"]),
    ("M14 放行可挪用到别的命令", BA,
     "        if (head != null) {\n            toolName = head.toolName();\n",
     "        if (false && head != null) {\n            toolName = head.toolName();\n",
     "BotAgentTest",
     ["confirmCannotBeRepointedToADifferentCommand"]),
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
        root = ET.parse(os.path.join(REPORTS, name)).getroot()
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                out.add(tc.get("name"))
    return out


def tally():
    run = 0
    if not os.path.isdir(REPORTS):
        return 0
    for name in os.listdir(REPORTS):
        if name.startswith("TEST-") and name.endswith(".xml"):
            run += int(ET.parse(os.path.join(REPORTS, name)).getroot().get("tests", 0))
    return run


def main():
    files = sorted({p for _, p, _, _, _, _ in MUTATIONS})
    baseline = {p: md5(p) for p in files}
    results = []
    for label, path, anchor, repl, selector, expected in MUTATIONS:
        text = read(path)
        if text.count(anchor) != 1:
            print("\n[ %s ] FATAL: 锚点命中 %d 次，变异未施加" % (label, text.count(anchor)))
            results.append((label, "FATAL-ANCHOR"))
            continue
        write(path, text.replace(anchor, repl, 1))
        subprocess.run(["rm", "-rf", REPORTS])
        proc = subprocess.run(
            ["mvn", "-o", "test", "-pl", "z-bot-core", "-Dtest=%s" % selector,
             "-DfailIfNoTests=false"],
            cwd=ZBOT, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
        rc = proc.returncode
        fails = failed_tests()
        run = tally()
        write(path, text)
        assert md5(path) == baseline[path], "RESTORE FAILED on %s" % path
        missing = [e for e in expected if e not in fails]
        if rc != 0 and run == 0:
            verdict = "CRASH(编译/启动崩，非证据) rc=%d" % rc
        elif rc == 0 and not fails:
            verdict = "GREEN-BUT-MUTATED (守卫无测试或空变异)"
        elif missing:
            verdict = "PARTIAL (未判红: %s) 实红=%s" % (",".join(missing), sorted(fails))
        else:
            verdict = "RED-OK 实红=%s" % sorted(fails)
        print("\n[ %s ] %s\n      rc=%d run=%d" % (label, verdict, rc, run))
        results.append((label, verdict))

    print("\n== 汇总 ==")
    bad = 0
    for label, verdict in results:
        if not verdict.startswith("RED-OK"):
            bad += 1
        print("%s %-38s %s" % ("  " if verdict.startswith("RED-OK") else "!!", label, verdict))
    print("final md5 ok:", all(md5(p) == baseline[p] for p in files))
    print("GUARDS_WITHOUT_EVIDENCE=%d" % bad)


if __name__ == "__main__":
    sys.exit(main())
