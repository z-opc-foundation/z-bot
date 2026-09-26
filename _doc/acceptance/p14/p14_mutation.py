#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P14（上下文压缩引擎对齐）变异检验 —— 杠②。

纪律（照 p11c/p12 同族，不 import 战役脚本）：
  * 入口 `if __name__ == "__main__": sys.exit(main())`，import 不执行任何事；
  * 注入前逐条校验锚点出现次数；漂了 FATAL 退出，一个源文件都不碰；
  * 互斥锁在 **git 公共目录** `$(git rev-parse --git-common-dir)/zbot-mutlock`，
    只用 `fcntl.flock(fd, LOCK_EX|LOCK_NB)`，抢不到直接 rc=4 退出（不 sleep 重试）；
  * 判定只认 surefire XML 里的**具名 testcase**；mvn 退出码非 0 不算证据；编译不过算 BROKEN；
  * 每支变异：逐字节备份+还原，注入前后各算一次磁盘 md5，并与 `git show <基线>:<path>` 对账
    （LEDGER 最后一列 `base=/before=/after=` 三值）；
  * 预期红集**跑前**就写死在下面 MUTANTS 表里；LEDGER.tsv 由本脚本机械写出。

覆盖面（派单 §3 杠② 点名的那些）：阈值公式的 `- maxOutputTokens`、pct 改 1.0、
三个位点各摘一个、防抖计数、失败冷却入库、DB 抢锁换成恒为 true、派号、血统回溯去重。

复算:
  python3 -u _doc/acceptance/p14/p14_mutation.py            # 全量 9 支
  python3 -u _doc/acceptance/p14/p14_mutation.py M3 M7      # 按 id 选
"""
import fcntl
import hashlib
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
LOGS = os.path.join(HERE, "logs")
LEDGER = os.path.join(HERE, "LEDGER.tsv")
BASELINE = os.environ.get("P14_MUT_BASE", "HEAD")

CTX = "z-bot-core/src/main/java/com/zifang/z/bot/context/"
SRC = {
    "engine": CTX + "CompressorEngine.java",
    "ledger": CTX + "CompressionLedger.java",
    "bot": "z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java",
}
TESTS = ("CompressionEngineP14Test,CompressionSiteInvocationTest,"
         "CompressionLineageForkTest,CompressorEngineTest,UsageFallbackCompressTest,"
         "P12RefundAndSteerGuardTest")

MUTANTS = [
    ("M1 阈值公式摘掉 maxOutputTokens（窗口×pct）", "engine",
     "return Math.max(1L, contextWindow - maxOutputTokens);",
     "return Math.max(1L, contextWindow);",
     ["thresholdIsRatioOfWindowMinusMaxOutputTokens",
      "ignoringMaxOutputTokensWouldTriggerTooLate",
      "pctComesFromConfigAndMovesTheTriggerPoint",
      "preApiCallSiteGuardsWithCoarseEstimate",
      "postToolBatchSiteSeesToolOutput"],
     "给输出额度留位是这次改口径的全部理由：摘掉之后触发线晚一倍，"
     "三条依赖触发线的测试必须一起红"),

    ("M2 pct 写死 1.0（不读配置位）", "engine",
     "this.threshold = threshold <= 0D ? DEFAULT_THRESHOLD : Math.min(threshold, 1D);",
     "this.threshold = 1.0D;",
     ["thresholdIsRatioOfWindowMinusMaxOutputTokens",
      "ignoringMaxOutputTokensWouldTriggerTooLate",
      "pctComesFromConfigAndMovesTheTriggerPoint",
      "realUsageClearsStaleCoarseEstimate",
      "coarseEstimateSiteTriggersBeforeAnyRealUsage",
      "toolBatchSiteFoldsRealUsageWithToolOutput",
      "preApiCallSiteGuardsWithCoarseEstimate",
      "postToolBatchSiteSeesToolOutput",
      "ineffectiveCompressionsStopTheEngine",
      "summarizerFailureCooldownIsPersistedAndHonoured",
      "compressionLocksTableLetsOnlyOneProcessThrough",
      "compressionForksSessionAndWritesParentAndBaseOrdinal"],
     "「不许只改常量」的反证：pct 不从配置流进来 ⇒ 配置位是死的，触发点挪不动"),

    ("M3 摘掉位点 1（轮首不评估）", "bot",
     "applyCompressionAtSite(CompressorEngine.SITE_TURN_START, 0L, listener);",
     "// mutated: 位点 1 摘掉",
     ["turnStartSiteIsCalledEveryStep"],
     "位点计数为 0 ⇒ 那条具名测试必须红，不许靠读数声称调到过"),

    ("M4 摘掉位点 2（API 前不拦粗估）", "bot",
     "if (applyCompressionAtSite(CompressorEngine.SITE_BEFORE_API_CALL, 0L, listener)) {",
     "if (false && applyCompressionAtSite(CompressorEngine.SITE_BEFORE_API_CALL, 0L, listener)) {",
     ["preApiCallSiteGuardsWithCoarseEstimate"],
     "真实 usage 回来之前那一档没人拦 ⇒ oversized 请求照发"),

    ("M5 摘掉位点 3（工具批后不评估）", "bot",
     "applyCompressionAtSite(CompressorEngine.SITE_AFTER_TOOL_BATCH, addedToolTokens, listener);",
     "// mutated: 位点 3 摘掉",
     ["postToolBatchSiteSeesToolOutput"],
     "工具输出一大段灌进上下文之后不当场判 ⇒ 只能等下一轮首位点，白胖一轮"),

    ("M6 防抖只判不计数（streak 恒 0）", "engine",
     "    static boolean ineffectiveSaving(long saved, long floor) {\n"
     "        return saved < floor;\n"
     "    }",
     "    static boolean ineffectiveSaving(long saved, long floor) {\n"
     "        return false;\n"
     "    }",
     ["ineffectiveCompressionsStopTheEngine",
      "ineffectiveStreakSurvivesProcessRestart",
      "compressionForksSessionAndWritesParentAndBaseOrdinal"],
     "「压了但没省下多少」不再被记 ⇒ 反复烧摘要，且无效的白压会去分叉会话"),

    ("M7 失败冷却不入库（写库那行摘掉）", "ledger",
     "        store.setMeta(key, Long.toString(value));",
     "        // mutated: 不写库 ⇒ 冷却只活在内存里",
     ["summarizerFailureCooldownIsPersistedAndHonoured",
      "emptySummaryAlsoCountsAsFailure"],
     "这就是「跨进程重启仍在」的那一条：不入库就是没入库"),

    ("M8 DB 抢锁换成恒为 true", "ledger",
     "        return store.tryAcquireCompressionLock(lockKey(sessionId), holder, Math.max(1L, ttlMillis));",
     "        store.tryAcquireCompressionLock(lockKey(sessionId), holder, Math.max(1L, ttlMillis));\n"
     "        return true;",
     ["compressionLocksTableLetsOnlyOneProcessThrough",
      "lockIsHeldForWholeLineage"],
     "表又回到零消费者，而且两个进程会同时压同一份历史"),

    ("M9 派号写死 base #1", "ledger",
     "        String title = FORK_TITLE_PREFIX + (depth + 1);",
     "        String title = FORK_TITLE_PREFIX + 1;",
     ["compressionForkWritesParentAndBaseOrdinal",
      "forkChainIsVisibleAfterReopeningTheStore",
      "lockIsHeldForWholeLineage",
      "compressionForksSessionAndWritesParentAndBaseOrdinal"],
     "第二代开始每一代都叫 base #1 ⇒ 链在 /sessions 里看不出深浅"),

    ("M10 检索不沿血统回溯（只去重）", "ledger",
     "            for (int depth = 0; depth < MAX_LINEAGE_DEPTH; depth++) {",
     "            for (int depth = 0; depth < 0; depth++) {",
     ["lineageSearchWalksBackToOriginalAndDedupes"],
     "压缩过的子会话摘要与父会话原文各命中一次 ⇒ 不回溯就还是两条，用户看到重复"),
]


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def git_md5(key):
    pr = subprocess.run(["git", "show", "%s:%s" % (BASELINE, SRC[key])],
                        cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if pr.returncode != 0:
        return None
    return hashlib.md5(pr.stdout).hexdigest()


def all_sources():
    out = []
    for root, dirs, files in os.walk(os.path.join(CORE, "src")):
        dirs[:] = [d for d in dirs if d != "target"]
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(root, f))
    return sorted(out)


def snapshot():
    return {p: md5(p) for p in all_sources()}


def src_path(key):
    return os.path.join(ZBOT, SRC[key])


def read(key):
    with open(src_path(key), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with open(src_path(key), "w", encoding="utf-8") as fh:
        fh.write(text)


def common_dir():
    return subprocess.check_output(["git", "rev-parse", "--git-common-dir"],
                                   cwd=ZBOT).decode().strip()


def lock_path():
    d = common_dir()
    if not os.path.isabs(d):
        d = os.path.join(ZBOT, d)
    return os.path.join(d, "zbot-mutlock")


def acquire(label):
    path = lock_path()
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as ex:
        os.close(fd)
        print("LOCK-BUSY %s：另一支注入脚本攥着 %s（%s）⇒ 本次不跑，一个源文件都没碰"
              % (label, path, ex), flush=True)
        return None
    try:
        os.truncate(fd, 0)
        os.write(fd, ("pid=%s tag=%s t=%s\n" % (os.getpid(), label, time.strftime("%F %T")))
                 .encode("utf-8"))
    except OSError:
        pass
    print("LOCK-ACQUIRED %s flock=LOCK_EX|LOCK_NB path=%s" % (label, path), flush=True)
    return fd


def check_anchors(selected):
    bad = []
    for _mid, key, old, _new, _exp, _note in selected:
        got = read(key).count(old)
        if got != 1:
            bad.append("%s: 锚点出现 %d 次，期望 1 次（%s）" % (_mid, got, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信任何读数）:", flush=True)
        for line in bad:
            print("  " + line, flush=True)
    return bad


def run_mvn(log_name):
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    logpath = os.path.join(LOGS, log_name)
    argv = ["mvn", "-o", "test", "-pl", "z-bot-core", "-Dtest=" + TESTS,
            "-DfailIfNoTests=false"]
    with open(logpath, "wb") as fh:
        rc = subprocess.Popen(argv, cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT).wait()
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
    with open(logpath, encoding="utf-8", errors="replace") as fh:
        out = fh.read()
    return rc, failing, ran, out


def parse_ids(argv):
    want = [a for a in argv if not a.startswith("--")]
    if not want:
        return MUTANTS
    out = []
    for m in MUTANTS:
        tag = m[0].split()[0]
        if any(w.upper() in tag or tag.lower() in w.lower() for w in want):
            out.append(m)
    return out


def main():
    os.makedirs(LOGS, exist_ok=True)
    selected = parse_ids(sys.argv[1:])
    if not selected:
        print("没有匹配的变异体", flush=True)
        return 2
    if check_anchors(selected):
        return 3
    fd = acquire("p14_mutation")
    if fd is None:
        return 4
    try:
        return run_mutants(selected)
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)
        except OSError:
            pass


def run_mutants(selected):
    before = snapshot()
    rows = []
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0, "NO-RUN": 0}
    for mid, key, old, new, expected, note in selected:
        tag = mid.split()[0]
        original = read(key)
        gbase = git_md5(key)
        write(key, original.replace(old, new, 1))
        verdict, hit, extra, rc, ran = "NO-RUN", [], [], -1, 0
        try:
            rc, failing, ran, out = run_mvn("mut-%s-mvn.log" % tag)
            if "COMPILATION ERROR" in out:
                verdict = "BROKEN"
            else:
                hit = sorted(set(expected) & failing)
                extra = sorted(failing - set(expected))
                if hit and not extra:
                    verdict = "RED-OK"
                elif hit or extra:
                    verdict = "PARTIAL"
                else:
                    verdict = "GREEN-BUT-MUTATED"
        except Exception as ex:      # 跑不动 = NO-RUN，如实记，不改写成「跳过」
            verdict = "NO-RUN"
            note = note + " / 异常: %r" % ex
        finally:
            write(key, original)
        restored = md5(src_path(key)) == before[src_path(key)]
        after_ok = gbase is not None and md5(src_path(key)) == gbase
        tally[verdict] = tally.get(verdict, 0) + 1
        who = ",".join(sorted(failing)) if failing else "全绿（跑了 %d 条）" % ran
        print("%-44s %-18s 点名=%d/%d rc=%s ran=%d 还原=%s base=%s before=%s after=%s"
              % (mid, verdict, len(hit), len(expected), rc, ran, restored,
                 (gbase or "")[:8], md5(src_path(key))[:8], "ok" if after_ok else "FAIL"),
              flush=True)
        print("     红在: %s" % who, flush=True)
        if verdict != "RED-OK":
            print("     %s" % note, flush=True)
        rows.append([mid, verdict, str(len(expected)), str(len(hit)), who, str(rc),
                     "ran=%d" % ran, note,
                     "restored=%s" % restored,
                     "base=%s before=%s after=%s" % ((gbase or "-")[:8],
                                                     md5(src_path(key))[:8],
                                                     "ok" if after_ok else "FAIL")])
    after = snapshot()
    drift = [p for p in after if after[p] != before[p]]
    with open(LEDGER, "w", encoding="utf-8") as fh:
        fh.write("# P14 杠② 变异台账（由 p14_mutation.py 机械写出，基线 %s）\n" % BASELINE)
        fh.write("# 五档: RED-OK / PARTIAL / GREEN-BUT-MUTATED / BROKEN / NO-RUN\n")
        fh.write("mutant\tverdict\texpected\thit\tfailing\trc\tran\tnote\trestore\tmd5 对账\n")
        for r in rows:
            fh.write("\t".join(r) + "\n")
        fh.write("# tally\t" + "\t".join("%s=%d" % (k, v) for k, v in sorted(tally.items())) + "\n")
        fh.write("# src_md5_stable\t%s\n" % ("yes" if not drift else "NO:%s" % drift))
    print("\n== 台账（五档） ==", flush=True)
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-18s %d" % (k, tally.get(k, 0)), flush=True)
    print("  LEDGER=%s src_md5_stable=%s" % (LEDGER, "yes" if not drift else "NO:%s" % drift),
          flush=True)
    return 0 if not drift and all(r[1] == "RED-OK" for r in rows) else 1


if __name__ == "__main__":
    sys.exit(main())
