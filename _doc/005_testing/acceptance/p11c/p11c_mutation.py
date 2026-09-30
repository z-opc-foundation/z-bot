#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P11c（通道监听收口）变异检验：把"缺省只绑回环 / 通配要显式 opt-in / 横幅印真地址"
三条守卫逐个改坏，看有没有**具名测试**判红。

纪律沿用 _doc/005_testing/acceptance/p11b/p11b_mutation.py（不 import 它，免得两批结论互相拖累）：
  * 注入前先校验锚点出现次数（锚点漂了是量具坏了，不是代码坏了）；
  * 每个变异体跑完按内存里的原文还原，收尾核对 md5；
  * 判定只认 surefire XML 里的 testcase 名：点名那条红了才算 RED-OK，
    红了别的算 PARTIAL，全绿算 GREEN-BUT-MUTATED，编译不过算 BROKEN；
  * LEDGER.tsv 由本脚本自己写，不手抄。

双向探针（少一条就是自欺）：
  M5 把 resolve() 钉成"永远通配" ⇒ 缺省那 4 条必红；
  M6 把 resolve() 钉成"永远回环" ⇒ opt-in 那 2 条必红。
  只有 M6 能证明"显式 0.0.0.0 真能绑到通配"这层断言不是空跑。

复算: python3 _doc/005_testing/acceptance/p11c/p11c_mutation.py [id 子串...]
"""
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
TESTS = "ChannelBindTest,ProfileIsolationTest"
LEDGER = os.path.join(HERE, "LEDGER.tsv")

CH = "z-bot-core/src/main/java/com/zifang/z/bot/channel/"
SRC = {
    "http": CH + "HttpChannel.java",
    "wh": CH + "WebhookChannel.java",
    "fs": CH + "FeishuChannel.java",
    "dt": CH + "DingTalkChannel.java",
    "cb": CH + "ChannelBind.java",
    "cli": "z-bot-core/src/main/java/com/zifang/z/bot/cli/AgentOptions.java",
}

BIND_OLD = "new InetSocketAddress(ChannelBind.resolve(host), port)"
BIND_NEW = "new InetSocketAddress(port)"
DT_BIND_OLD = "new java.net.InetSocketAddress(ChannelBind.resolve(host), port)"
RESOLVE_OLD = "return h.isEmpty() ? InetAddress.getLoopbackAddress() : InetAddress.getByName(h);"

DEF_RED = ["httpChannelDefaultsToLoopback", "webhookChannelDefaultsToLoopback",
           "feishuChannelDefaultsToLoopback", "dingTalkChannelDefaultsToLoopback"]
OCC = ["occupiedLoopbackPortFailsFastInsteadOfSilentlyCoexisting",
       "occupiedLoopbackPortAlsoFailsFastForWebhook"]
OPT_IN = ["explicitAnyHostActuallyBindsWildcardForHttp",
          "explicitAnyHostActuallyBindsWildcardForWebhook"]
BOTH = ["loopbackDefaultAndWildcardOptInAreDistinguishable",
        "blankHostResolvesToLoopbackAndAnyResolvesToWildcard"]
BANNER = ["consoleUrlReportsTheActualBindNotAHardcodedLoopback"]

# (id, 文件键, old, new, 期望锚点数, 点名期望红的测试, 说明)
MUTANTS = [
    ("M1 Http 改回通配绑定", "http", BIND_OLD, BIND_NEW, 1,
     ["httpChannelDefaultsToLoopback"] + OCC[:1] + BOTH[:1] + BANNER,
     "这就是 §6 记的那次间歇 400 的成因：通配能和邻居的 127.0.0.1:P 共存"),

    ("M2 Webhook 改回通配绑定", "wh", BIND_OLD, BIND_NEW, 1,
     ["webhookChannelDefaultsToLoopback"] + OCC[1:],
     "gateway 的第二个监听面"),

    ("M3 飞书改回通配绑定", "fs", BIND_OLD, BIND_NEW, 1,
     ["feishuChannelDefaultsToLoopback"], "IM 通道同样不得摊到 LAN"),

    ("M4 钉钉改回通配绑定", "dt", DT_BIND_OLD, "new java.net.InetSocketAddress(port)", 1,
     ["dingTalkChannelDefaultsToLoopback"], "同上"),

    ("M5 resolve 永远通配", "cb", RESOLVE_OLD, "return InetAddress.getByName(ANY);", 1,
     DEF_RED + OCC + BOTH + BANNER,
     "缺省那一半全塌：4 条缺省 + 2 条占端口 + 2 条双向 + 1 条横幅"),

    ("M6 resolve 永远回环（host 被忽略）", "cb", RESOLVE_OLD,
     "return InetAddress.getLoopbackAddress();", 1,
     OPT_IN + ["loopbackDefaultAndWildcardOptInAreDistinguishable",
               "blankHostResolvesToLoopbackAndAnyResolvesToWildcard"],
     "反空跑探针：显式 opt-in 那层若不断真地址，这里就该一直绿"),

    ("M7 横幅写死 127.0.0.1", "http",
     'return "http://" + ChannelBind.describe(getBindAddress()) + ":" + getPort() + "/index.html";',
     'return "http://127.0.0.1:" + getPort() + "/index.html";', 1, BANNER,
     "绑到通配却说只在本机 = 把暴露范围报反"),

    ("M8 缺 key 提示写死 ~/.zbot", "cli",
     '                    + "用 --api-key 或写进 "\n'
     '                    + (config.getConfigDir() == null\n'
     '                       ? "~/.zbot" : config.getConfigDir().getPath())\n'
     '                    + "/config.properties 的 "\n',
     '                    + "用 --api-key 或写进 ~/.zbot/config.properties 的 "\n', 1,
     ["missingKeyHintNamesTheProfileItWasLoadedFrom"],
     "P11b 漏网的用户可见文案：第二 profile 里缺 key 会被指引去改一个不生效的文件"),
]


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def read(key):
    with open(os.path.join(ZBOT, SRC[key]), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with open(os.path.join(ZBOT, SRC[key]), "w", encoding="utf-8") as fh:
        fh.write(text)


def run_tests():
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    proc = subprocess.run(
        ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + TESTS,
         "-DfailIfNoTests=false"],
        cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    failing, ran = set(), 0
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
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.add(tc.get("name").split("[")[0])
    return proc.returncode, failing, ran, proc.stdout.decode("utf-8", "replace")


def main():
    selected = MUTANTS
    if len(sys.argv) > 1:
        selected = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not selected:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:])
            return 2
    bad = []
    for mid, key, old, new, want, expected, note in selected:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2

    baseline = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    rows = []
    tally = {}
    for mid, key, old, new, want, expected, note in selected:
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, out = run_tests()
        write(key, original)
        restored = md5(os.path.join(ZBOT, SRC[key])) == baseline[key]
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        else:
            hit = sorted(set(expected) & failing)
            if hit and not (failing - set(expected)):
                verdict = "RED-OK"
            elif hit or failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] = tally.get(verdict, 0) + 1
        who = ",".join(sorted(failing)) if failing else "全绿（跑了 %d 条）" % ran
        print("%-34s %-18s 点名=%d/%d ⇒ %s | rc=%s | 还原=%s"
              % (mid, verdict, len(set(expected) & failing), len(expected), who, rc, restored),
              flush=True)
        if verdict != "RED-OK":
            print("     %s" % note)
        rows.append([mid, verdict, str(len(expected)), str(len(set(expected) & failing)),
                     who, note, "ok" if restored else "RESTORE-FAILED"])

    still = [k for k, v in SRC.items() if md5(os.path.join(ZBOT, v)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
        print("  %-18s %d" % (k, tally.get(k, 0)))
    print("  final md5 ok: %s" % (not still))
    with open(LEDGER, "w", encoding="utf-8") as fh:
        fh.write("\t".join(["id", "verdict", "named_expected", "named_hit",
                            "tests_that_went_red", "note", "restored"]) + "\n")
        for r in rows:
            fh.write("\t".join(c.replace("\t", " ") for c in r) + "\n")
    print("  台账已机械写出: %s" % os.path.relpath(LEDGER, ZBOT))
    return 0 if not still else 3


if __name__ == "__main__":
    sys.exit(main())
