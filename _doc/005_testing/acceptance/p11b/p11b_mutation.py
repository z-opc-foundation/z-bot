#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P11b（红线 1 profile 隔离）变异检验：把"缺省值跟着 profile 走"这条守卫逐条改坏，
看有没有**具名测试**判红。

纪律沿用 _doc/005_testing/acceptance/p15/p15_mutation.py：
  * 注入前校验锚点出现次数（锚点漂了说明量具坏了，不是代码坏了）；
  * 每个变异体跑完按内存里的原文还原，收尾核对全量 md5；
  * 判定只认 surefire XML 里的 testcase 名 —— 点名那条红了才算 RED-OK，
    红了别的算 PARTIAL，全绿算 GREEN-BUT-MUTATED（= 这条守卫只能靠实测），编译不过算 BROKEN。

M1（ZBOT_HOME 被忽略）与 M12（stty 备份回到 ~/.zbot）**预期就是 GREEN-BUT-MUTATED**：
前者要真进程的 env，后者要真 pty，进程内都造不出来 —— 这两条的证据在 p11b_e2e.py，不在这里。

复算: python3 _doc/005_testing/acceptance/p11b/p11b_mutation.py [id 子串...]
"""
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ZBOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    os.pardir, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
TESTS = "ProfileIsolationTest"

SRC = {
    "cfg": "z-bot-core/src/main/java/com/zifang/z/bot/config/BotConfig.java",
    "sb": "z-bot-core/src/main/java/com/zifang/z/bot/tool/Sandbox.java",
    "sess": "z-bot-core/src/main/java/com/zifang/z/bot/session/SessionManager.java",
    "agent": "z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java",
    "cli": "z-bot-core/src/main/java/com/zifang/z/bot/cli/AgentOptions.java",
    "st": "z-bot-core/src/main/java/com/zifang/z/bot/cli/StatusCommand.java",
    "cc": "z-bot-core/src/main/java/com/zifang/z/bot/center/BotCenterClient.java",
    "rtr": "z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java",
}

HOME = 'System.getProperty("user.home")'

# (id, 文件键, old, new, 期望锚点数, 点名期望红的测试, 说明)
MUTANTS = [
    ("M1 ZBOT_HOME 被忽略", "cfg",
     '        if (home.isEmpty()) {\n            home = trim(System.getenv("ZBOT_HOME"));\n        }\n',
     "", 1, [],
     "进程内改不了环境变量 ⇒ 单测杀不掉它；真凭据在 p11b_e2e.py 的真进程 env"),

    ("M2 profile 缺省写死回 ~/.zbot", "cfg",
     '        return new File(home.isEmpty()\n                ? %s + "/.zbot" : home);' % HOME,
     '        return new File(%s + "/.zbot");' % HOME, 1,
     ["zbotHomePropertyIsTheProfileDefault", "everyDataDirFollowsTheProfile",
      "agentBuilderKeepsEveryArtifactInsideTheProfile"],
     "六份写死字面量的第一份被请回来"),

    ("M3 沙箱阶梯被换序", "cfg",
     "        String v = trim(cliOverride);\n        if (v.isEmpty()) {\n"
     '            v = trim(System.getProperty("zbot.sandbox"));\n        }',
     '        String v = trim(System.getProperty("zbot.sandbox"));\n'
     "        if (v.isEmpty()) {\n            v = trim(cliOverride);\n        }", 1,
     ["workspaceResolutionIsCliThenSysPropThenProfile", "eachSwitchAloneMovesOnlyItsOwnDir"],
     "--sandbox 压不过 -Dzbot.sandbox ⇒ 用户的显式开关成了空话"),

    ("M4 workspace 不跟 profile", "cfg",
     '        return new File(configDir == null ? defaultConfigDir() : configDir, "workspace");',
     '        return new File(%s + "/.zbot", "workspace");' % HOME, 1,
     ["everyDataDirFollowsTheProfile", "eachSwitchAloneMovesOnlyItsOwnDir",
      "workspaceResolutionIsCliThenSysPropThenProfile",
      "agentBuilderKeepsEveryArtifactInsideTheProfile",
      "statusCommandReportsProfilePathsNotRealHome"],
     "多 profile 共享同一个沙箱 = 红线 1 本体"),

    ("M5 sessionsDir 不跟 profile", "cfg",
     '        return new File(configDir == null ? defaultConfigDir() : configDir, "sessions");',
     '        return new File(%s + "/.zbot/sessions");' % HOME, 1,
     ["everyDataDirFollowsTheProfile", "agentBuilderKeepsEveryArtifactInsideTheProfile",
      "eachSwitchAloneMovesOnlyItsOwnDir", "statusCommandReportsProfilePathsNotRealHome"],
     "P15 已把 state.db 接上 profile，JSON 目录是剩下的那一半"),

    ("M6 AgentOptions 丢掉 profile", "cli",
     "        File fromCli = config == null ? configDir : config.getConfigDir();",
     "        File fromCli = null;", 1,
     ["statusCommandReportsProfilePathsNotRealHome"],
     "只认 --config-dir 字段本身、不认已解析的 config ⇒ 未显式给开关就回真实 home"),

    ("M7 status 回到无参 SessionManager", "st",
     "        SessionManager sessions = new SessionManager(config.sessionsDir());",
     "        SessionManager sessions = new SessionManager();", 1,
     ["statusCommandReportsProfilePathsNotRealHome"],
     "status 是用户核对『这个 profile 到底在用哪些目录』的唯一出口，它报错路径就是没守卫"),

    ("M8 Builder 的沙箱写死", "agent",
     "                sandbox = new Sandbox(BotConfig.resolveWorkspaceDir(\n"
     "                        config == null ? null : config.getConfigDir(), null).getAbsolutePath());",
     '                sandbox = new Sandbox(%s + "/.zbot/workspace");' % HOME, 1,
     ["agentBuilderKeepsEveryArtifactInsideTheProfile"],
     "不经 CLI 直接 BotAgent.create() 的路径（serve/gateway/测试）走的正是这一档"),

    ("M9 Builder 的会话目录写死", "agent",
     "                            config.sessionsDir(),",
     '                            new java.io.File(%s + "/.zbot/sessions"),' % HOME, 1,
     ["agentBuilderKeepsEveryArtifactInsideTheProfile"],
     "还原成 P11b 之前的现场：state.db 跟 profile，JSON 目录跟真实 home"),

    ("M10 Sandbox 空根不跟 profile", "sb",
     "                ? com.zifang.z.bot.config.BotConfig.resolveWorkspaceDir(null, null).getPath()",
     '                ? %s + "/.zbot/workspace"' % HOME, 1,
     ["everyDataDirFollowsTheProfile"],
     "第三份抄来的优先级链复活"),

    ("M11 SessionManager 兜底写死", "sess",
     '        this(new File(com.zifang.z.bot.config.BotConfig.defaultConfigDir(), "sessions"));',
     '        this(new File(%s + "/.zbot/sessions"));' % HOME, 1,
     ["everyDataDirFollowsTheProfile"],
     "ZBOT_HOME 在这一档又变成装饰"),

    ("M12 stty 备份回到 ~/.zbot 共享文件", "rtr",
     "            sttyBackup = bak.getAbsolutePath();",
     '            sttyBackup = %s + "/.zbot/.stty.bak";' % HOME, 1, [],
     "两个 profile 的 REPL 会互相踩掉对方的终端恢复；只有真 pty 跑得出来（见 p11b_e2e.py E5）"),

    ("M13 BotCenterClient 忽略传入目录", "cc",
     "        this.zbotDir = zbotDir;",
     "        this.zbotDir = new File(%s, \".zbot\");" % HOME, 1,
     ["centerClientWritesItsArtifactsIntoTheProfile"],
     "被删掉的单参构造器换个写法回来（instance.json / skills 又会写真实 home）"),
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
    cmd = ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + TESTS,
           "-DfailIfNoTests=false"]
    proc = subprocess.run(cmd, cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
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
    global MUTANTS
    if len(sys.argv) > 1:
        MUTANTS = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not MUTANTS:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:])
            return 2
    bad = []
    for mid, key, old, new, want, expected, note in MUTANTS:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2

    baseline = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0}
    for mid, key, old, new, want, expected, note in MUTANTS:
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, out = run_tests()
        restored = False
        write(key, original)
        try:
            restored = md5(os.path.join(ZBOT, SRC[key])) == baseline[key]
        except Exception:
            restored = False
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
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
        who = ",".join(sorted(failing)) if failing else ("全绿（跑了 %d 条）" % ran)
        print("%-30s %-18s 点名=%d/%d ⇒ %s | rc=%s | 还原=%s"
              % (mid, verdict, len(set(expected) & failing), len(expected), who, rc, restored),
              flush=True)
        if verdict == "GREEN-BUT-MUTATED":
            print("     %s" % note)

    still = [k for k, v in SRC.items() if md5(os.path.join(ZBOT, v)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
        print("  %-18s %d" % (k, tally[k]))
    print("  final md5 ok: %s" % (not still))
    return 0 if not still else 3


if __name__ == "__main__":
    sys.exit(main())
