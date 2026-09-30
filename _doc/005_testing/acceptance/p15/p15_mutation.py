#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P15 变异检验：往生产代码里注入"守卫被拿掉"，看有没有**具名测试**判红。

纪律（沿用 _doc/005_testing/acceptance/p11/p11_mutation.py）：
  * 注入前校验锚点在文件里出现的次数（锚点漂了就是量具坏了，不是代码坏了）；
  * 每个变异体跑完立刻按 md5 还原，收尾再全量核对一次；
  * 判定只认 surefire XML 里的 testcase 名字 —— "某个测试红了"不算证据，
    点名期望的那条红了才算 RED-OK；红了但是姊妹测试算 PARTIAL；全绿记 GREEN-BUT-MUTATED。

复算: python3 _doc/005_testing/acceptance/p15/p15_mutation.py
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

SRC = {
    "tx": "z-bot-core/src/main/java/com/zifang/z/bot/store/SqliteTx.java",
    "mig": "z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java",
    "store": "z-bot-core/src/main/java/com/zifang/z/bot/store/StateStore.java",
    "cli": "z-bot-core/src/main/java/com/zifang/z/bot/cli/SessionsCommand.java",
    "cfg": "z-bot-core/src/main/java/com/zifang/z/bot/config/BotConfig.java",
}

# (id, 文件键, old, new, 出现次数, -Dtest 选择器, 点名期望红的测试方法, 说明)
MUTANTS = [
    ("MU-1 begin-immediate 被忽略", "tx",
     'st.execute(cfg.beginImmediate() ? "BEGIN IMMEDIATE" : "BEGIN");',
     'st.execute("BEGIN");', 1,
     "StateStoreWritePathTest",
     ["concurrentAppendKeepsEveryRowAndNoCallerVisibleBusy"],
     "写事务退化成 deferred：读-改-写会在升级写锁时撞 BUSY"),

    ("MU-2 重试阶梯被拆", "tx",
     "final int attempts = Math.max(1, cfg.writeRetries() + 1);",
     "final int attempts = 1;", 3,
     "StateStoreWritePathTest",
     ["retryLadderCarriesAWriteThroughAnExternalLockHolder"],
     "撞锁一次就放弃（阶梯是 busy_timeout=0 时的唯一熬法）"),

    ("MU-3 抖动退避被清零", "tx",
     "long sleep = min + (max == min ? 0 : ThreadLocalRandom.current().nextLong(max - min + 1));",
     "long sleep = 0;", 1,
     "StateStoreWritePathTest",
     [],
     "重试次数照跑但不睡：单测没有一处断言时间窗，只能靠 A/B 实测看吞吐"),

    ("MU-4 幂等加列守卫被拆", "mig",
     "if (hasColumn(c, table, column)) {\n            return false;",
     "if (false) {\n            return false;", 1,
     "SchemaMigrationsTest",
     ["repeatedOpensAreIdempotent", "rollingVersionBackReRunsStepsWithoutDuplicatingColumns"],
     "第二次打开对同一列再 ALTER ⇒ duplicate column name"),

    ("MU-5 requireEnded 被忽略", "store",
     "String where() {\n            StringBuilder sb = new StringBuilder(\"updated_at < ?\");\n            if (requireEnded) {",
     "String where() {\n            StringBuilder sb = new StringBuilder(\"updated_at < ?\");\n            if (false) {",
     1, "StateStoreRetentionTest,SessionsCommandTest",
     ["pruneSkipsInFlightSessionsEvenWhenOld", "inFlightNeedsItsOwnSwitch"],
     "在飞会话被连带删掉 —— 保留策略的第一态"),

    ("MU-6 孤儿子会话不脱钩", "store",
     "if (c.detachOrphans()) {", "if (false) {", 1,
     "StateStoreRetentionTest",
     ["detachOrphansClearsParentPointerByDefault"],
     "父会话删了、子会话还指着不存在的父 ⇒ 血统断链"),

    ("MU-7 软归档变硬删", "store",
     '"UPDATE sessions SET archived=1 WHERE id IN (" + in + ") AND archived=0"))',
     '"DELETE FROM sessions WHERE id IN (" + in + ")"))', 1,
     "StateStoreRetentionTest,SessionsCommandTest",
     ["softArchiveHidesThenHardDeleteFreesSpace", "archiveIsReversibleAndHidesFromDefaultList"],
     "archiveMatching 不再留数据 ⇒ 可撤销的归档没了"),

    ("MU-8 开库自动清理被摘", "store",
     "this.lastAutoPrune = maybeAutoPrune();", "this.lastAutoPrune = null;", 1,
     "StateStoreRetentionTest",
     ["autoPruneRunsOnOpenAndOncePerDayWhenEnabled"],
     "auto_prune 打开也不跑 ⇒ 配置成了摆设"),

    ("MU-9 CLI 默认档的 0 消息封顶被拆", "cli",
     "c.maxMessages(Integer.valueOf(0));", "c.maxMessages(null);", 1,
     "SessionsCommandTest",
     ["pruneDefaultNeverTouchesEndedSessionWithMessages"],
     "主编加的闸门：prune 默认只能清空会话（P2 语义），扩面要显式开关"),

    ("MU-10 配置接线 autoPrune 漏接", "cfg",
     '        o.autoPrune(boolOf("agent.state.prune.auto", o.autoPrune()));\n', "", 1,
     "BotConfigStateStoreOptionsTest",
     ["eachKeyAloneMovesExactlyItsOwnField"],
     "键登记了但没接线 ⇒ 用户写了配置、行为不变、没有任何东西会变红"),

    ("MU-11 配置接线 checkpoint 漏接", "cfg",
     '        o.checkpointEveryNWrites(intOf("agent.state.db.checkpoint.every.n", o.checkpointEveryNWrites()));\n',
     "", 1, "BotConfigStateStoreOptionsTest", ["eachKeyAloneMovesExactlyItsOwnField"],
     "同上：10 个键逐个钉"),

    ("MU-12 配置接线 retry 时间窗漏接", "cfg",
     '        o.retryWindowMillis(longOf("agent.state.db.write.retry.min.ms", o.retryMinMillis()),\n'
     '                longOf("agent.state.db.write.retry.max.ms", o.retryMaxMillis()));\n', "", 1,
     "BotConfigStateStoreOptionsTest", ["eachKeyAloneMovesExactlyItsOwnField"],
     "一行覆盖两个 long 键"),

    ("MU-13 configKeys 多登记一个没接线的键", "store",
     '"agent.state.prune.auto", "agent.state.prune.retention.days"));',
     '"agent.state.prune.auto", "agent.state.prune.retention.days",\n'
     '                    "agent.state.db.bogus.not.wired"));', 1,
     "BotConfigStateStoreOptionsTest", ["everyRegisteredKeyHasACase"],
     "分母守卫：登记表本身当分母，漏接线当场红"),

    ("MU-14 CLI 的在飞守卫被摘（E2E 抓到的洞复原）", "cli",
     "                    .treatUnusedEmptyAsEnded(false)\n", "", 1,
     "SessionsCommandTest",
     ["emptyInFlightSessionIsOnlyGoneWithItsOwnSwitch"],
     "摘掉就回到 E2E 现场：默认档把 0 消息的在飞会话一起抹掉"),
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


def run_tests(selector):
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    cmd = ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + selector,
           "-DfailIfNoTests=false"]
    proc = subprocess.run(cmd, cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    failing, totals = set(), []
    if os.path.isdir(REPORTS):
        for name in sorted(os.listdir(REPORTS)):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(REPORTS, name)).getroot()
            except Exception:
                continue
            run = int(root.get("tests") or 0)
            bad = int(root.get("failures") or 0) + int(root.get("errors") or 0)
            totals.append((name, run, bad))
            for tc in root.iter("testcase"):
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.add(tc.get("name").split("[")[0])
    return proc.returncode, failing, totals, proc.stdout.decode("utf-8", "replace")


def main():
    # 可选参数：只跑 id 里带这些子串的变异体（复算单个洞时用）
    global MUTANTS
    if len(sys.argv) > 1:
        MUTANTS = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not MUTANTS:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:])
            return 2
    bad_anchors = []
    for mid, key, old, new, want, sel, expected, note in MUTANTS:
        got = read(key).count(old)
        if got != want:
            bad_anchors.append("%s: 锚点出现 %d 次，期望 %d 次" % (mid, got, want))
    if bad_anchors:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad_anchors:
            print("  " + line)
        return 2

    baseline = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0}
    lines = []
    for mid, key, old, new, want, sel, expected, note in MUTANTS:
        path = os.path.join(ZBOT, SRC[key])
        before = md5(path)
        original = read(key)
        write(key, original.replace(old, new))
        rc, failing, totals, out = run_tests(sel)
        # 只看编译错误：测试判红时 maven 同样会打 BUILD FAILURE，拿它当"编译不过"会把所有 RED-OK 误判成 BROKEN
        compiled = "COMPILATION ERROR" not in out
        hit = sorted(set(expected) & failing)
        others = sorted(failing - set(expected))
        if not compiled:
            verdict = "BROKEN"
            detail = "编译不过（锚点已漂或语法不闭合），当场弃"
        elif failing and hit:
            verdict = "RED-OK"
            detail = "点名红了: " + ", ".join(hit)
        elif failing:
            verdict = "PARTIAL"
            detail = "红了但不是点名的: " + ", ".join(others)
        else:
            verdict = "GREEN-BUT-MUTATED"
            detail = "全绿（跑了 %d 条）⇒ 该守卫无单测可杀，只能靠实测记账" % sum(t[1] for t in totals)
        tally[verdict] += 1
        write(key, original)
        restored = md5(path) == before
        lines.append("%-42s %-18s %s | rc=%d | 还原=%s" % (mid, verdict, detail, rc, restored))
        print(lines[-1], flush=True)
        if not restored:
            print("FATAL: %s 还原失败，停在这里" % mid)
            return 3

    print("\n== 变异台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
        print("  %-20s %d" % (k, tally[k]))
    final_ok = all(md5(os.path.join(ZBOT, v)) == baseline[k] for k, v in SRC.items())
    print("  final md5 ok: %s" % final_ok)
    return 0 if final_ok else 3


if __name__ == "__main__":
    sys.exit(main())
