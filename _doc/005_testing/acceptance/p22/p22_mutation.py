#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P22 杠②：命名变异注入（自带 MUTANTS 表，不 import 任何战役脚本）。

规矩（工单第四条）：
  * 全仓共享的 flock（``$(git rev-parse --git-common-dir)/zbot-mutlock``）：抢不到就 rc=4 退出，**不 sleep 重试**。
  * 注入前逐文件与 ``git show HEAD:<path>`` 逐字节比（比之前两边各做一次"行尾空白归一"），
    不一致就直接 abort —— 别的写手在改同一个文件时不许悄悄量他。
  * 基线（不注入）必须先绿；每支变异体跑完立刻按字节还原 + 复算 md5 对账。
  * 五档：KILLED / SURVIVED / BASELINE_NOT_GREEN / INJECTION_NOT_APPLIED / RESTORE_FAILED。
  * 台账 LEDGER.tsv 机械生成；"摘掉接线"单独一支（WIRING_DETACHED），不许只靠算法用例。

退出码：0=全 KILLED 且还原干净；2=有 SURVIVED / INJECTION_NOT_APPLIED / 基线不绿；
4=锁被占；3=与 HEAD 不一致（现场不干净）。
"""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

REPO = subprocess.check_output(["git", "rev-parse", "--show-toplevel"]).decode().strip()
COMMON = subprocess.check_output(["git", "rev-parse", "--path-format=absolute",
                                  "--git-common-dir"]).decode().strip()
LOCK_PATH = os.path.join(COMMON, "zbot-mutlock")
SCRATCH = os.path.expanduser("~/.cache/zbot-p22-lead/mut")

SPI = "com.zifang.z.bot.tool.env.ExecEnvironmentSpiTest"
DOCKER = "com.zifang.z.bot.tool.env.DockerExecEnvironmentTest"
WIRING = "com.zifang.z.bot.tool.BuiltinToolsExecWiringTest"

BT = "z-bot-core/src/main/java/com/zifang/z/bot/tool/BuiltinTools.java"
LOC = "z-bot-core/src/main/java/com/zifang/z/bot/tool/env/LocalExecEnvironment.java"
SSH = "z-bot-core/src/main/java/com/zifang/z/bot/tool/env/SshExecEnvironment.java"
DOC = "z-bot-core/src/main/java/com/zifang/z/bot/tool/env/DockerExecEnvironment.java"
FAC = "z-bot-core/src/main/java/com/zifang/z/bot/tool/env/ExecEnvironments.java"

# 每支：名字 / 摘掉的是什么 / 文件 / needle / 替身 / 必须红的用例（点名，不看总数）
MUTANTS = [
    dict(
        name="WIRING_DETACHED", family="接线被摘", file=BT, tests=[WIRING],
        needle="        com.zifang.z.bot.tool.env.ExecEnvironment env =\n"
               "                com.zifang.z.bot.tool.env.ExecEnvironments.current(cwd);",
        repl="        com.zifang.z.bot.tool.env.ExecEnvironment env =\n"
             "                new com.zifang.z.bot.tool.env.LocalExecEnvironment(\n"
             "                        new com.zifang.z.bot.tool.env.ExecEnvConfig(), cwd);",
        why="BuiltinTools 不查当前后端、自己 new 一个 local —— 行为一模一样，但装了 spy 也拿不到调用",
        must_red=["execToolActuallyRoutesThroughTheInstalledBackend"],
    ),
    dict(
        name="INTERRUPT_BRIDGE", family="P12 中断桥被摘", file=BT, tests=[WIRING],
        needle="                        .timeoutMillis(env.defaultTimeoutMillisHint())\n"
               "                        .observer(new InterruptBridgeObserver());",
        repl="                        .timeoutMillis(env.defaultTimeoutMillisHint());",
        why="旗子不再递进 SPI：看门狗看不见在飞的进程，中止只能等它自己跑完",
        must_red=["execToolStillCarriesTheInterruptBridge"],
    ),
    dict(
        name="SANDBOX_CHECK", family="沙箱判定被摘", file=LOC, tests=[SPI],
        needle="        return sandbox.resolve(path);",
        repl="        return new File(sandbox.root().getPath() + File.separator + path);",
        why="不 resolve、不判越界 —— 路径逃逸从此无人管",
        must_red=["localExec_rejectsSandboxEscape"],
    ),
    dict(
        name="OUTPUT_CAP", family="输出上界被摘", file=LOC, tests=[SPI],
        needle="        BoundedCollector out = new BoundedCollector(req.maxOutputBytes(), req.policy());",
        repl="        BoundedCollector out = new BoundedCollector(0, ExecRequest.OutputPolicy.HEAD);",
        why="maxBytes<=0 走 UNBOUNDED（Integer.MAX_VALUE），且 policy=HEAD ⇒ 不建 tailRing，只涨不炸：整段输出进内存",
        must_red=["localExec_boundsOutputAndSaysSo"],
    ),
    dict(
        name="TREE_KILL", family="超时只杀父", file=LOC, tests=[SPI],
        # p22b 修正：原 needle 是 12 空格缩进的 `ProcessTree.killTree(p);`，实测在
        # LocalExecEnvironment.java 里只命中 1 次（:217 的 InterruptedException 兜底），
        # 而真正守「超时/中止要端整棵树」的是 :189（旗子路）与 :209（超时路）那两行
        # `killedDescendants = ProcessTree.killTree(p);` ⇒ 改成它，count_expect=2。
        needle="                    killedDescendants = ProcessTree.killTree(p);",
        repl="                    killedDescendants = ProcessTree.killParentOnly(p);",
        count_expect=2,
        why="descendants() 不看 —— 孙子进程继续活（sleep 60 那种），且 killParentOnly 返回 0 ⇒ killedDescendants 谎报为 0",
        must_red=["localExec_timeoutTakesTheWholeProcessTreeDown"],
    ),
    dict(
        name="ORPHAN_RECLAIM", family="孤儿回收被摘", file=DOC, tests=[DOCKER],
        needle="            deleteContainer(id, started);",
        repl="            // P22-MUTANT ORPHAN_RECLAIM: 收尾不删容器",
        why="finally 里的 DELETE 摘掉 ⇒ 每次 exec 留一个容器在地上",
        must_red=["dockerTimeoutStillKillsAndReclaimsTheContainer"],
    ),
    dict(
        name="LEDGER_REAP", family="账本兜底被摘", file=DOC, tests=[DOCKER],
        needle="        List<String> mine = ledgerIds();",
        repl="        List<String> mine = new java.util.ArrayList<String>();",
        why="账本不再当第二路兜底：owner 死了留下的容器没人捞",
        must_red=["dockerReapsLedgerOrphansLeftByADeadProcess"],
    ),
    dict(
        name="SSH_COMMAND_CONCAT", family="ssh 拼接命令", file=SSH, tests=[SPI],
        needle="            sb.append(ExecRequest.posixQuote(request.argv().get(i)));",
        repl="            sb.append(request.argv().get(i));",
        why="token 直接落进远端命令串 —— argv 里一个 ; 就能在远端执行任何东西",
        must_red=["ssh_metacharactersInArgsCannotEscapeQuoting"],
    ),
    dict(
        name="BACKEND_FALLBACK", family="后端选择静默降级", file=FAC, tests=[SPI],
        needle="        throw new ExecEnvException(ExecEnvException.Code.BACKEND_SELECTION_FAILED,\n"
               "                \"不认识的后端名：\" + normalized + \"（只支持 local/docker/ssh）。\"",
        repl="        return new LocalExecEnvironment(cfg, sandbox); // P22-MUTANT BACKEND_FALLBACK\n"
             "        /*\n"
             "        throw new ExecEnvException(ExecEnvException.Code.BACKEND_SELECTION_FAILED,\n"
             "                \"不认识的后端名：\" + normalized + \"（只支持 local/docker/ssh）。\"",
        count_expect=1,
        why="配置写错就悄悄用 local 跑 —— 沙箱/限额全不在，而调用方看不出来",
        must_red=["factory_unknownBackendFailsLoudlyNeverFallsBackToLocal"],
        close_comment=True,
    ),
]


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def normalize(b):
    lines = b.decode("utf-8", "replace").split("\n")
    return "\n".join(l.rstrip() for l in lines).rstrip("\n") + "\n"


def head_blob(rel):
    return subprocess.check_output(["git", "show", "HEAD:" + rel])


def read(rel):
    with open(os.path.join(REPO, rel), "rb") as f:
        return f.read()


def write(rel, data):
    with open(os.path.join(REPO, rel), "wb") as f:
        f.write(data)


# p22b 修正（第一跑 8/9 全被判 INJECTION_NOT_APPLIED 的真因就在这条正则上）：
# mvn 的聚合行在**构建失败**时前缀是 `[ERROR]` 而不是 `[INFO]`（实测 baseline.log 与 8 份变异体日志
# 逐行对过：`[ERROR] Tests run: 6, Failures: 3, Errors: 0, Skipped: 0`），旧正则只认 [INFO] ⇒
# summary 取不到 ⇒ 被判成「套件跑不起来」。变异其实全落地了。
MVN_SUMMARY = re.compile(r"^\[(?:INFO|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$")
MVN_CLASS = re.compile(r"Tests run: .*-- in ([\w.$]+)$")
MVN_FAILNAME = re.compile(r"^\[ERROR\]\s+([\w.$]+)\.([\w$]+)[:\[]")


def run_mvn(test_classes, timeout_s):
    cmd = ["mvn", "-o", "-pl", "z-bot-core", "-Dtest=" + ",".join(test_classes),
           "-DfailIfNoTests=false", "test"]
    try:
        p = subprocess.run(cmd, cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=timeout_s)
        out = p.stdout.decode("utf-8", "replace")
        rc = p.returncode
    except subprocess.TimeoutExpired as e:
        out = (e.output or b"").decode("utf-8", "replace") if isinstance(e.output, bytes) else ""
        out += "\n<<<TIMEOUT %ds>>>\n" % timeout_s
        rc = 124
    summary = None
    for line in out.split("\n"):
        m = MVN_SUMMARY.match(line)
        if m:
            summary = tuple(int(x) for x in m.groups())
    red = set()
    for line in out.split("\n"):
        m = MVN_FAILNAME.match(line.strip()) or MVN_FAILNAME.match(line)
        if m:
            red.add(m.group(1) + "." + m.group(2))
    if summary is None:
        # 编译不过 / 一个用例都没跑起来：当作整类红，rc 里留真相
        summary = (0, 0, 0)
        for c in test_classes:
            red.add(c + ".<BUILD_OR_RUN_BROKEN>")
    return dict(rc=rc, summary=list(summary if summary else [0, 0, 0]),
                red=sorted(red), log=out)


def write_log(run_dir, tag, text):
    path = os.path.join(run_dir, tag + ".log")
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--json-out", default="")
    ap.add_argument("--timeout", type=int, default=420)
    args = ap.parse_args()
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = os.path.join(SCRATCH, ts)
    os.makedirs(run_dir, exist_ok=True)

    lock_fd = open(LOCK_PATH, "a+")
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        sys.stderr.write("LOCK_BUSY：%s 被别的写手占着（不重试，直接退）\n" % LOCK_PATH)
        return 4

    want = set(x.strip() for x in args.only.split(",") if x.strip())
    mutants = [m for m in MUTANTS if not want or m["name"] in want]
    files = sorted(set([m["file"] for m in mutants]))
    report = dict(ts=ts, run_dir=run_dir, files=files, stages=[])
    rows = []

    # 1) 现场必须等于 HEAD（归一后逐字节）
    baseline_md5 = {}
    for rel in files:
        want_b = normalize(head_blob(rel))
        have_b = normalize(read(rel))
        baseline_md5[rel] = md5_bytes(want_b.encode())
        if want_b != have_b:
            print("WORKTREE_DIVERGES_FROM_HEAD %s" % rel)
            print("  HEAD   md5=%s" % baseline_md5[rel])
            print("  现场 md5=%s" % md5_bytes(have_b.encode()))
            diff = [i for i, (a, b) in enumerate(zip(want_b.split("\n"), have_b.split("\n"))) if a != b]
            print("  首个不同行（1 起）：%s" % (diff[:5] or "长度不同"))
            return 3
    report["baseline"] = dict(status="clean", md5=baseline_md5)

    # 2) 正向对照：不注入必须先绿
    all_tests = sorted(set(t for m in mutants for t in m["tests"]))
    base = run_mvn(all_tests, args.timeout)
    base_log = write_log(run_dir, "baseline", base["log"])
    report["positive_control"] = dict(rc=base["rc"], summary=base["summary"], log=base_log)
    base_green = base["rc"] == 0 and base["summary"][1] == 0 and base["summary"][2] == 0
    if not base_green:
        rows.append(("injection=NONE", "POSITIVE_CONTROL", "-", "BASELINE_NOT_GREEN",
                     "0", str(base["rc"]), "基线不绿，后面所有 RED 都不能算变异体的功劳", base_log))
    else:
        rows.append(("injection=NONE", "POSITIVE_CONTROL", "-", "GREEN",
                     str(base["summary"][0]), "0", "正向对照：不注入全绿", base_log))

    # 3) 逐支
    for m in mutants:
        rel = m["file"]
        copy = os.path.join(run_dir, m["name"] + ".orig")
        shutil.copyfile(os.path.join(REPO, rel), copy)
        text = read(rel).decode("utf-8")
        n = text.count(m["needle"])
        expect = m.get("count_expect")
        if n == 0 or (expect is not None and n != expect):
            rows.append((m["name"], m["family"], rel, "INJECTION_NOT_APPLIED",
                         "0", "-", "needle 命中 %d 次（要求 %s）⇒ 注入没发生，绝不记 SURVIVED"
                         % (n, expect if expect else ">=1"), "-"))
            continue
        repl = m["repl"]
        if m.get("close_comment"):
            # 把原 throw 的其余部分留在块注释里（下面还有两行）
            repl = repl  # noqa
        new_text = text.replace(m["needle"], repl, n)
        if m.get("close_comment"):
            new_text = new_text.replace(
                "                        + \"这里不许静默换成 \" + ExecEnvConfig.DEFAULT_BACKEND + \" —— 见 EVIDENCE §1\");",
                "                        + \"这里不许静默换成 \" + ExecEnvConfig.DEFAULT_BACKEND + \" —— 见 EVIDENCE §1\");\n        */",
                1)
        write(rel, new_text.encode("utf-8"))
        if md5_bytes(new_text.encode()) == baseline_md5[rel]:
            rows.append((m["name"], m["family"], rel, "INJECTION_NOT_APPLIED",
                         "0", "-", "注入后 md5 与基线相同 ⇒ 等价注入", "-"))
            shutil.copyfile(copy, os.path.join(REPO, rel))
            continue
        res = run_mvn(m["tests"], args.timeout)
        log = write_log(run_dir, m["name"], res["log"])
        if res["summary"][0] == 0 and res["rc"] != 0:
            # 一个用例都没跑起来（编译不过 / 起不来）——这不是"测试抓住了变异"，另记一档
            rows.append((m["name"], m["family"], rel, "INJECTION_NOT_APPLIED",
                         "0", str(res["rc"]), "注入后套件跑不起来（多半是编译不过）⇒ 没有量到任何卫兵", log))
            shutil.copyfile(copy, os.path.join(REPO, rel))
            now = normalize(read(rel))
            ok = md5_bytes(now.encode()) == baseline_md5[rel]
            report.setdefault("restored", []).append(dict(name=m["name"], rel=rel,
                                                          md5=baseline_md5[rel], ok=ok))
            continue
        red = set(res["red"])
        missed = [t for t in m["must_red"] if not any(t in r for r in red)]
        total_fail = res["summary"][1] + res["summary"][2]
        if missed:
            status = "SURVIVED"
            note = "点名的用例没红：%s（实际红 %d 条）" % (",".join(missed), total_fail)
        else:
            status = "KILLED"
            note = "点名的用例全红（共红 %d 条 / 跑 %d 条）" % (total_fail, res["summary"][0])
        rows.append((m["name"], m["family"], rel, status, str(res["summary"][0]),
                     str(res["rc"]), note + "；摘的是：" + m["why"], log))
        # 还原 + 三方对账
        shutil.copyfile(copy, os.path.join(REPO, rel))
        now = normalize(read(rel))
        ref = subprocess.check_output(["git", "show", "HEAD:" + rel])
        if md5_bytes(now.encode()) != baseline_md5[rel] or now != normalize(ref):
            rows.append((m["name"], m["family"], rel, "RESTORE_FAILED", "0", "-",
                         "还原后 md5 对不上（现场=%s 基线=%s HEAD=%s）"
                         % (md5_bytes(now.encode()), baseline_md5[rel], md5_bytes(normalize(ref).encode())),
                         "-"))
        else:
            report.setdefault("restored", []).append(dict(name=m["name"], rel=rel,
                                                          md5=baseline_md5[rel]))
    # 4) 收尾复扫：现场必须 == HEAD
    dirty = subprocess.check_output(["git", "status", "--porcelain", "--"] + files).decode().strip()
    report["final_git_status"] = dirty
    ledger = os.path.join(REPO, "_doc/005_testing/acceptance/p22/LEDGER.tsv")
    with open(ledger, "a", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(["p22", ts] + [str(x) for x in r]) + "\n")
    counts = {}
    for r in rows:
        counts[r[3]] = counts.get(r[3], 0) + 1
    print("=" * 100)
    print("P22 杠② 台账（run=%s，锁=%s，收尾 git status=%r）" % (ts, LOCK_PATH, dirty))
    print("=" * 100)
    for r in rows:
        print("%-20s %-18s %-16s tests=%-4s rc=%-4s %s" % (r[0], r[1], r[3], r[4], r[5], r[6]))
        print("%20s 文件 %s" % ("", r[2]))
    print("-" * 100)
    print("五档计数：" + json.dumps(counts, ensure_ascii=False, sort_keys=True))
    if args.json_out:
        report["rows"] = [list(r) for r in rows]
        report["counts"] = counts
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=1)
    bad = [k for k in counts if k not in ("GREEN", "KILLED")]
    return 2 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
