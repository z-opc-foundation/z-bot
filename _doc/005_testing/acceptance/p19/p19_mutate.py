#!/usr/bin/env python3
"""
杠② · P19 命令表单源守卫的具名变异台账。

每一支变异都做四件事，缺一不算数：
  1. 抢互斥锁 zbot-mutlock（LOCK_EX|LOCK_NB）—— 抢不到 rc=4 直接退出，不 sleep 重试、不杀持有者；
  2. 往**源文件字节**里注入一处具名漂移（替换次数必须恰好等于预期，否则 INJECTION_NOT_APPLIED）；
  3. 证明变异**进了运行时读的那份字节**（.class 的 md5 变了 / 资源副本的 md5 变了），
     否则"红"可能只是编译噪声；
  4. 跑被点名的那条判据，必须看到**那条方法**红（不是"整个 build 红"就算杀）；
  5. 按保存的原字节写回并复验 md5 —— 绝不用 git checkout/restore（那会连别人会话的成果一起抹掉）。

另有一条**非空跑对照**：每支变异前先在该测试上量一次"没变异 ⇒ 必须绿"，
否则一条本来就红的判据会被误记成"我的守卫抓到了漂移"。

仓库根从 __file__ 派生（p25/p26/p27 三支尺刚因为硬编码写手树绝对路径被判废）。
"""
import fcntl
import hashlib
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p19-lead")
os.makedirs(CACHE, exist_ok=True)
OUT_TSV = os.path.join(REPO, "_doc", "005_testing", "acceptance", "p19", "LEDGER.tsv")
RAW_LOG = os.path.join(CACHE, "mutate_raw.jsonl")

TEST_ROOT = "z-bot-core/src/test/java"
MAIN_ROOT = "z-bot-core/src/main"


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def md5_file(p):
    if not os.path.isfile(p):
        return "<missing>"
    with open(p, "rb") as f:
        return md5_bytes(f.read())


def read(p):
    with open(p, "rb") as f:
        return f.read()


def write(p, data):
    with open(p, "wb") as f:
        f.write(data)


# ---------------- 锁 ----------------

def acquire_lock():
    r = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                       cwd=REPO, capture_output=True, text=True)
    if r.returncode != 0:
        print("FATAL|git rev-parse --git-common-dir 失败: " + r.stderr.strip())
        sys.exit(2)
    common = r.stdout.strip()
    if not os.path.isabs(common):
        common = os.path.join(REPO, common)
    path = os.path.join(common, "zbot-mutlock")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fh = open(path, "a+")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("LOCKED|抢不到 %s ⇒ rc=4 退出（不重试、不杀持有者）" % path)
        sys.exit(4)
    print("LOCK|acquired %s pid=%d" % (path, os.getpid()))
    return fh


# ---------------- maven ----------------

Tally = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)")
Named = re.compile(r"\[ERROR\]\s+(\w+)\.(\w+)")


def mvn_test(selector):
    r = subprocess.run(["mvn", "-o", "test", "-Dtest=" + selector, "-DfailIfNoTests=false"],
                       cwd=REPO, capture_output=True, text=True)
    out = r.stdout + r.stderr
    cls = selector.split("#")[0].split(",")[0]
    runs = [(int(a), int(b), int(c), int(d)) for a, b, c, d in Tally.findall(out)]
    # 只取"本类"的那一行 tally，不用全场汇总（全场汇总会把别的类的红算成自己的）
    m = re.search(r"Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+.*?-- in [\w.]*\b%s\b"
                  % re.escape(cls), out, re.S)
    one = Tally.search(m.group(0)) if m else None
    failed_named = sorted({"%s.%s" % (a, b) for a, b in Named.findall(out)})
    if not m:
        return {"rc": r.returncode, "ran": None, "no_test_ran": True,
                "failed_named": failed_named, "tail": out[-1200:]}
    return {"rc": r.returncode, "no_test_ran": False,
            "ran": int(one.group(1)), "failures": int(one.group(2)),
            "errors": int(one.group(3)), "skipped": int(one.group(4)),
            "failed_named": failed_named}


def compile_all():
    r = subprocess.run(["mvn", "-o", "test-compile"], cwd=REPO, capture_output=True, text=True)
    return r.returncode


# ---------------- 变异定义 ----------------
# 每条：(id, 说明, 目标文件, 原文片段, 变异后片段, 期望出现次数, 被点名的判据, 证明进字节的目标文件)

MUTATIONS = [
    dict(
        id="M1-web-advertises-exit-without-branch",
        what="web/index.html 的 handleSlash 里加一条 /exit 分支（表里没有 ⇒ 该端多吐）",
        path=MAIN_ROOT + "/resources/web/index.html",
        needle="        } else if (cmd.startsWith('/confirm')) {",
        patch="        } else if (cmd === '/exit') {\n            nope();\n" + \
              "        } else if (cmd.startsWith('/confirm')) {",
        count=1,
        test="CommandSurfaceConsistencyTest#webConsoleHandlesExactlyTheWebSegment",
        bytes=[MAIN_ROOT + "/resources/web/index.html"],
    ),
    dict(
        id="M2-tui-drops-theme-branch",
        what="TerminalChannel 的 /theme 分支摘掉（表里承诺了但该端不再接）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/channel/TerminalChannel.java",
        needle="        } else if (\"/theme\".equals(name)) {\n            cmdTheme(args);\n",
        patch="        } else if (\"/themeX\".equals(name)) {\n            cmdTheme(args);\n",
        count=1,
        test="CommandSurfaceConsistencyTest#tuiHandlesExactlyTheChannelLocalSegment",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/channel/TerminalChannel.class"],
    ),
    dict(
        id="M3-http-drops-one-row",
        what="/api/commands 少吐一行（commandListResult 里滤掉 /usage）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/channel/HttpChannel.java",
        needle="                new ArrayList<Map<String, Object>>(CommandCatalog.asRows());",
        patch="                new ArrayList<Map<String, Object>>(CommandCatalog.asRows());\n"
              "                for (java.util.Iterator<Map<String, Object>> it = out.iterator();"
              " it.hasNext(); ) {\n"
              "                    if (\"/usage\".equals(it.next().get(\"name\"))) { it.remove(); }\n"
              "                }",
        count=1,
        test="CommandSurfaceConsistencyTest#httpCommandsEndpointServesExactlyTheCatalog",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/channel/HttpChannel.class"],
    ),
    dict(
        id="M4-catalog-advertises-theme-to-web",
        what="表里把 /tools 的 WEB 可见性摘掉（反向：广告段与实际分支不再同源）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/slash/CommandCatalog.java",
        needle="        server(\"/tools\", \"列出已注册工具\", \"\", Endpoint.TUI, Endpoint.HTTP, Endpoint.WEB, Endpoint.ACP);",
        patch="        server(\"/tools\", \"列出已注册工具\", \"\", Endpoint.TUI, Endpoint.HTTP, Endpoint.ACP);",
        count=1,
        test="CommandSurfaceConsistencyTest#webConsoleHandlesExactlyTheWebSegment",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/slash/CommandCatalog.class"],
    ),
    dict(
        id="M5-acp-frame-wrong-discriminator",
        what="ACP 帧的判别字写成 session_update（自造协议键 ⇒ 抓不到帧）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/acp/AcpStreamPublisher.java",
        needle="        update.put(\"sessionUpdate\", AcpMethods.UPDATE_AVAILABLE_COMMANDS);",
        patch="        update.put(\"session_update\", AcpMethods.UPDATE_AVAILABLE_COMMANDS);",
        count=1,
        test="AcpCommandAdvertisementTest#sessionNewEmitsExactlyTheAcpSegmentOfTheCatalog",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/acp/AcpStreamPublisher.class"],
    ),
    dict(
        id="M6-acp-frame-drops-one-command",
        what="ACP 帧只发表里前 19 条（少广告一条）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/acp/AcpStreamPublisher.java",
        needle="        for (CommandCatalog.Def d : CommandCatalog.defsFor(CommandCatalog.Endpoint.ACP)) {",
        patch="        java.util.List<CommandCatalog.Def> acpDefs =\n"
              "                new java.util.ArrayList<CommandCatalog.Def>(\n"
              "                        CommandCatalog.defsFor(CommandCatalog.Endpoint.ACP));\n"
              "        acpDefs.remove(0);\n"
              "        for (CommandCatalog.Def d : acpDefs) {",
        count=1,
        test="AcpCommandAdvertisementTest#sessionNewEmitsExactlyTheAcpSegmentOfTheCatalog",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/acp/AcpStreamPublisher.class"],
    ),
    dict(
        id="M7-registry-skips-one-server-def",
        what="服务端注册表装配时跳过 /sessions（表里承诺了但没人注册）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/slash/SlashRegistry.java",
        needle="        for (final CommandCatalog.Def d : CommandCatalog.serverDefs()) {",
        patch="        for (final CommandCatalog.Def d : CommandCatalog.serverDefs()) {\n"
              "            if (\"/sessions\".equals(d.name())) { continue; }",
        count=1,
        test="CommandSurfaceConsistencyTest#serverRegistryRegistersExactlyTheCatalogServerSegment",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/slash/SlashRegistry.class"],
    ),
    dict(
        id="M8-acp-advertise-call-removed",
        what="摘掉 session/new 之后的广告调用（ACP 端一行不吐）",
        path=MAIN_ROOT + "/java/com/zifang/z/bot/acp/AcpAgentServer.java",
        needle="        advertiseCommands(session);\n        return result;\n    }\n\n    /**\n     * P19",
        patch="        return result;\n    }\n\n    /**\n     * P19",
        count=1,
        test="AcpCommandAdvertisementTest#sessionNewEmitsExactlyTheAcpSegmentOfTheCatalog",
        bytes=["z-bot-core/target/classes/com/zifang/z/bot/acp/AcpAgentServer.class"],
    ),
]


def target_method(test_sel):
    cls, meth = test_sel.split("#")
    return cls, meth


def run_one(mut):
    path = os.path.join(REPO, mut["path"])
    original = read(path)
    rec = {"id": mut["id"], "what": mut["what"], "path": mut["path"], "test": mut["test"]}
    text = original.decode("utf-8")
    n = text.count(mut["needle"])
    if n != mut["count"]:
        rec["marker"] = "INJECTION_NOT_APPLIED"
        rec["detail"] = "锚点在源文件里出现 %d 次（期望 %d）" % (n, mut["count"])
        return rec

    # 非空跑对照：先确认这条判据在**没变异**时是绿的
    base = mvn_test(mut["test"])
    rec["baseline"] = base
    if base.get("no_test_ran") or base.get("failures", 0) + base.get("errors", 0) > 0:
        rec["marker"] = "PARTIAL"
        rec["detail"] = "对照跑就不绿/没跑（不能把这轮红算成守卫抓到漂移）: %s" % base
        return rec

    # 先记下"运行时真正被读的那份"的基线 md5（.class 或 target/classes 下的资源副本），
    # 注入后必须与它不同 —— 只记不比对等于没证（这一条就是"注入要证明进了字节码"）。
    def measured(rel):
        return ("z-bot-core/target/classes/web/index.html"
                if rel.endswith("resources/web/index.html") else rel)

    baseline_bytes = {measured(rel): md5_file(os.path.join(REPO, measured(rel)))
                      for rel in mut["bytes"]}
    mutated = text.replace(mut["needle"], mut["patch"], mut["count"])
    write(path, mutated.encode("utf-8"))
    try:
        crc = compile_all()
        if crc != 0:
            rec["marker"] = "INJECTION_NOT_APPLIED"
            rec["detail"] = "注入后编译不过 ⇒ 变异没进字节码，不算杀"
            return rec
        prov = {k: md5_file(os.path.join(REPO, k)) for k in baseline_bytes}
        rec["baseline_bytes"] = baseline_bytes
        rec["mutated_bytes"] = prov
        unchanged = sorted(k for k in prov if prov[k] == baseline_bytes[k])
        if unchanged:
            rec["marker"] = "INJECTION_NOT_APPLIED"
            rec["detail"] = "注入没改变运行时读的那份字节（%s）⇒ 不算杀" % ",".join(unchanged)
            return rec
        res = mvn_test(mut["test"])
        cls, meth = target_method(mut["test"])
        hit = "%s.%s" % (cls, meth) in res.get("failed_named", [])
        rec["result"] = res
        rec["marker"] = "KILLED" if (hit and res["rc"] != 0) else (
            "RED-OK" if res["rc"] != 0 else "SURVIVED")
        if rec["marker"] == "RED-OK":
            rec["detail"] = "红了，但不是被点名的那条判据红的: %s" % res.get("failed_named")
        elif rec["marker"] == "SURVIVED":
            rec["detail"] = "漂移没被抓到 —— 守卫是死的"
    finally:
        write(path, original)
        assert md5_file(path) == md5_bytes(original), "原字节写回失败: " + path
        rec["restored_md5_ok"] = True
    return rec


def main():
    print("REPO=" + REPO)
    assert os.path.isdir(os.path.join(REPO, "z-bot-core")), "REPO 派生错了: " + REPO
    lock = acquire_lock()
    os.makedirs(os.path.dirname(OUT_TSV), exist_ok=True)
    rows, records = [], []
    t0 = time.time()
    try:
        for mut in MUTATIONS:
            print("=== %s ===" % mut["id"], flush=True)
            rec = run_one(mut)
            records.append(rec)
            with open(RAW_LOG, "a") as f:
                f.write(json.dumps(rec, ensure_ascii=False, default=str) + "\n")
            rows.append(rec)
            print("%s -> %s %s" % (rec["id"], rec["marker"], rec.get("detail", "")), flush=True)
    finally:
        fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
        lock.close()
    # 收尾把所有还原后的字节重新编译一遍，别把变异的 class 留在 target 里
    compile_all()
    header = "# 杠② P19 命令表单源守卫 · 具名变异台账 —— 由 p19_mutate.py 机器生成，别手敲\n" \
             "# 再生成: python3 _doc/005_testing/acceptance/p19/p19_mutate.py   (需抢 zbot-mutlock，抢不到 rc=4)\n" \
             "# 判据: KILLED=被点名的那条测试红且字节已变; RED-OK=红了但归因不到那条; " \
             "SURVIVED=漂移没被抓到; PARTIAL=对照不成立; INJECTION_NOT_APPLIED=字节没进\n"
    cols = ["id", "marker", "guard_test", "target_file",
            "runtime_bytes_md5_baseline", "runtime_bytes_md5_mutated", "bytes_changed",
            "baseline_green", "tests_ran", "failures", "errors", "killed_by_named_test",
            "restored_md5_ok", "what"]
    lines = [header, "\t".join(cols)]
    for r in rows:
        res = r.get("result", {})
        base = r.get("baseline", {})
        cls, meth = target_method(r["test"])
        lines.append("\t".join(str(x) for x in [
            r["id"], r["marker"], r["test"], r["path"],
            json.dumps(r.get("baseline_bytes", {}), ensure_ascii=False),
            json.dumps(r.get("mutated_bytes", {}), ensure_ascii=False),
            bool(r.get("mutated_bytes")),
            "no_test_ran" if base.get("no_test_ran") else (
                "green" if base.get("failures", 1) == 0 and base.get("errors", 1) == 0 else "red"),
            res.get("ran", ""), res.get("failures", ""), res.get("errors", ""),
            "%s.%s" % (cls, meth) in res.get("failed_named", []) if res else "",
            r.get("restored_md5_ok", False), r["what"],
        ]))
    write(OUT_TSV, ("\n".join(lines) + "\n").encode("utf-8"))
    tally = {}
    for r in rows:
        tally[r["marker"]] = tally.get(r["marker"], 0) + 1
    print("LEDGER=%s rows=%d tally=%s secs=%.0f" % (OUT_TSV, len(rows), tally, time.time() - t0))
    print("mtimes harness=%d ledger=%d" % (os.path.getmtime(__file__), os.path.getmtime(OUT_TSV)))
    bad = [r["id"] for r in rows if r["marker"] != "KILLED"]
    if bad:
        print("NOT_ALL_KILLED " + ",".join(bad))
        sys.exit(1)


if __name__ == "__main__":
    main()
