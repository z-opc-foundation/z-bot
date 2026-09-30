#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P25b 杠② —— ACP 面的具名变异台账（机生成 LEDGER.tsv，判定不靠人眼）。

纪律（工单 §2 / §3.4）：
  * 注入前 `flock(LOCK_EX|LOCK_NB)` 抢 `<git-common-dir>/zbot-mutlock`；抢不到 ⇒ rc=4 退出，不睡等、不杀别人。
  * 参照集 = **本次运行**开工时从工作树 cp 的副本；还原只从副本 cp + md5 逐字对账。
    全程不许 `git checkout/restore/clean/stash/reset`（共享 worktree，破坏不可逆）。
  * 每支变异必须**证明进了字节码**（class md5 delta + `javap`/`strings` 针），证不进去就记
    `INJECTION_NOT_APPLIED`，不许写成"检查抓不到"。
  * 判红只认具名测试类出现在 `<<< FAILURE!`/`<<< ERROR!` 行里，且该次 `Tests run>0`
    （编译不通过的注入不算杀；`mvn -q` 会吞掉 `COMPILATION ERROR` 头，所以这里不加 -q）。
  * 台账里带一行**阳性对照**（不注入任何 bug，同一套判据必须全绿），否则整张表可能是"怎么跑都红"。

退出码：0=全跑完 / 1=有 SURVIVED（照实记账，不算脚本失败） / 4=抢不到变异锁 / 5=还原对账失败(FATAL)
"""

import datetime
import fcntl
import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys

# 量具钉在目标树。原先硬 `/private/tmp/zbot-wt-p25`：那棵树还留在盘上、冻结在 443f5f6，
# 今天照原样跑会静默改那棵树的 src/main 并把 LEDGER 写回去（tracked 那份就是它的自证）。
REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    os.pardir, os.pardir, os.pardir, os.pardir))
LEAD = os.path.expanduser("~/.cache/zbot-p25-lead")


def time_stamp():
    return datetime.datetime.now().strftime("mut%H%M%S")


RUN_ID = sys.argv[1] if len(sys.argv) > 1 else time_stamp()


REF = os.path.join(LEAD, "mutref", RUN_ID)
LOGS = os.path.join(LEAD, "mutlogs", RUN_ID)
LEDGER = os.path.join(REPO, "_doc", "005_testing", "acceptance", "p25", "LEDGER.tsv")
CORE = os.path.join(REPO, "z-bot-core")
CLASSES = os.path.join(CORE, "target", "classes", "com", "zifang", "z", "bot")

SRC = {
    "ZBot": "z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java",
    "AcpCommand": "z-bot-core/src/main/java/com/zifang/z/bot/cli/AcpCommand.java",
    "AcpAgentServer": "z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpAgentServer.java",
    "AcpApprovalBridge": "z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpApprovalBridge.java",
    "AcpStreamPublisher": "z-bot-core/src/main/java/com/zifang/z/bot/acp/AcpStreamPublisher.java",
}

# 每支：mutant / 文件 / old / new / 字节码针（kind=present|absent, token, 类名） / 捕获者
MUTANTS = [
    dict(
        id="M1-acp-registration-removed", file="ZBot",
        old="                McpCommand.class, com.zifang.z.bot.cli.AcpCommand.class},",
        new="                McpCommand.class},",
        needle=("absent", "AcpCommand", "ZBot*.class"),
        catcher="com.zifang.z.bot.acp.AcpProductionRegistrationTest", kind="mvn",
        claim="§3.2 问 1：摘掉 ZBot.java 的注册那一行，生产注册表断言必红"),
    dict(
        id="M2-acp-command-renamed", file="AcpCommand",
        old='@Command(name = "acp", description',
        new='@Command(name = "acpx", description',
        needle=("present", "acpx", "cli/AcpCommand*.class"),
        catcher="com.zifang.z.bot.acp.AcpProductionRegistrationTest", kind="mvn",
        claim="acp 这一格真指向 name=acp，改名即红"),
    dict(
        id="M3-session-load-unregistered", file="AcpAgentServer",
        old="""        conn.onRequest(AcpMethods.SESSION_LOAD, new AcpConnection.AcpRequestHandler() {
            @Override
            public JsonNode handle(AcpConnection c, JsonRpc.Message msg) {
                return loadSession(msg.params(), false);
            }
        });""",
        new="        // MUTANT M3: session/load 不再注册（假装有这个方法）",
        needle=("absent", "session/load", "acp/AcpAgentServer*.class"),
        catcher="com.zifang.z.bot.acp.AcpRealAgentChainTest", kind="mvn",
        claim="method 表不是抄来的：session/load 这一格真在分派里"),
    dict(
        id="M4-permission-rawinput-shell", file="AcpApprovalBridge",
        old="""        call.set("rawInput", AcpStreamPublisher.parseJsonOrWrap(head.argsJson()));
        // requestId 只经 ApprovalService 拿得到：这条 meta 就是"桥真的接在 FIFO 上"的证据。
        ObjectNode meta = JsonRpc.object();
        meta.put("approvalRequestId", head.id());
        meta.put("approvalSessionKey", head.sessionKey());
        meta.put("approvalArgsJson", head.argsJson() == null ? "" : head.argsJson());""",
        new="""        ObjectNode rawInput = JsonRpc.object();
        rawInput.put("command", head.command());
        rawInput.put("argsJson", head.argsJson());
        call.set("rawInput", rawInput);
        // requestId 只经 ApprovalService 拿得到：这条 meta 就是"桥真的接在 FIFO 上"的证据。
        ObjectNode meta = JsonRpc.object();
        meta.put("approvalRequestId", head.id());
        meta.put("approvalSessionKey", head.sessionKey());""",
        needle=("absent", "approvalArgsJson", "acp/AcpApprovalBridge*.class"),
        catcher="com.zifang.z.bot.acp.AcpApprovalBridgeTest", kind="mvn",
        claim="审批帧 rawInput 必须是工具真入参（本棒修的那条），退回记账壳即红"),
    dict(
        id="M5-selfcheck-clear-before-check", file="AcpCommand",
        old="""                emitted = new ArrayList<String>(frames);
                frames.clear();""",
        new="""                frames.clear();
                emitted = new ArrayList<String>(frames);""",
        needle=("present", "acp --check", "cli/AcpCommand*.class"),
        catcher="acp_check_selfcheck_exit_zero", kind="e2e",
        claim="本棒修的 --check 缺陷由真进程 E2E 钉住（杠③ 的 acp --check 那步）"),
    dict(
        id="M6-fork-pretends-success", file="AcpAgentServer",
        old="""                throw AcpProtocolException.notImplemented(AcpMethods.SESSION_FORK,
                        "需要「复制历史到新会话」的落盘原语；BotAgent.forkFor 只派生空会话，"
                                + "照它做会给出一个看不到父会话历史的假 fork（见 EVIDENCE §未做）");""",
        new="                return JsonRpc.object(); // MUTANT M6: 假成功",
        needle=("absent", "notImplemented", "acp/AcpAgentServer*.class"),
        catcher="com.zifang.z.bot.acp.AcpProtocolSurfaceTest", kind="mvn",
        claim="已知未实现要大声，不能装作成功"),
    dict(
        id="M7-session-update-typo", file="AcpStreamPublisher",
        old="        conn.notify(AcpMethods.SESSION_UPDATE, params);",
        new='        conn.notify("session/updates", params); // MUTANT M7',
        needle=("present", "session/updates", "acp/AcpStreamPublisher*.class"),
        catcher="com.zifang.z.bot.acp.AcpStreamPublisherTest", kind="mvn",
        claim="client 面 session/update 的拼写就是协议名，改一个字母即红"),
]

ROWS = []


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sh(args, timeout=900, cwd=REPO):
    p = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out, _ = p.communicate(timeout=timeout)
    return p.returncode, (out or b"").decode("utf-8", "replace")


def row(m, verdict, proof, extra=""):
    ROWS.append([RUN_ID, m.get("id", "CONTROL"), m.get("file", "-"),
                 (m.get("claim", "无注入：同一套判据必须全绿")).replace("\t", " "),
                 verdict, proof, extra])
    line = "\t".join(ROWS[-1])
    print("LEDGER|%s" % line, flush=True)
    with open(LEDGER, "a") as f:
        f.write(line + "\n")


def acquire_lock():
    rc, common = sh(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"])
    common = common.strip()
    if not common:
        print("FATAL 拿不到 git-common-dir")
        sys.exit(5)
    lock_path = os.path.join(common, "zbot-mutlock")
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("LOCK|path=%s held_by_other=1 rc=4 退出（不睡等、不杀持有者）" % lock_path)
        sys.exit(4)
    print("LOCK|path=%s acquired=1" % lock_path)
    return fd


def snapshot_reference():
    os.makedirs(REF)
    os.makedirs(LOGS)
    manifest = {}
    dirty = subprocess.Popen(["git", "status", "--porcelain"], cwd=REPO,
                             stdout=subprocess.PIPE).communicate()[0].decode()
    if dirty.strip():
        print("FATAL 开工时工作树不干净，参照集不可信：\n%s" % dirty)
        sys.exit(5)
    for key, rel in SRC.items():
        dst = os.path.join(REF, key + ".java")
        shutil.copyfile(os.path.join(REPO, rel), dst)
        manifest[key] = md5_file(dst)
    with open(os.path.join(REF, "MANIFEST.tsv"), "w") as f:
        for k, v in manifest.items():
            f.write("%s\t%s\n" % (k, v))
    print("REFERENCE|dir=%s files=%d manifest_md5=%s"
          % (REF, len(manifest), md5_file(os.path.join(REF, "MANIFEST.tsv"))[:8]))
    return manifest


def restore(manifest):
    bad = []
    for key, rel in SRC.items():
        shutil.copyfile(os.path.join(REF, key + ".java"), os.path.join(REPO, rel))
        if md5_file(os.path.join(REPO, rel)) != manifest[key]:
            bad.append(key)
    if bad:
        print("RESTORE-FATAL|%s 与副本对不上账，停手别继续" % ",".join(bad))
        sys.exit(5)
    rc, out = sh(["git", "status", "--porcelain", "--"] + [v for v in SRC.values()])
    print("RESTORE|md5_all_match=1 git_dirty_for_mutated_files=%s"
          % ("yes:" + out.strip() if out.strip() else "no"))
    return not out.strip()


def bytecode_state(pattern):
    """整族（含匿名内部类 $1…$N）class 的合并指纹 + javap/strings 全文。

    针只盯主类会假报"没进字节码"：例如 `notImplemented` 的调用点长在匿名内部类里。
    """
    paths = sorted(glob.glob(os.path.join(CLASSES, pattern)))
    if not paths:
        return None, ""
    h = hashlib.md5()
    dump = []
    for p in paths:
        with open(p, "rb") as f:
            blob = f.read()
        h.update(os.path.basename(p).encode())
        h.update(blob)
        dump.append(subprocess.run(["javap", "-v", "-p", p], capture_output=True,
                                   text=True).stdout)
        dump.append(subprocess.run(["strings", p], capture_output=True,
                                   text=True).stdout)
    return h.hexdigest(), "\n".join(dump)


def compile_core(tag):
    rc, out = sh(["mvn", "-o", "-pl", "z-bot-core", "compile"], timeout=900)
    with open(os.path.join(LOGS, tag + ".compile.log"), "w") as f:
        f.write(out)
    comp_err = "COMPILATION ERROR" in out
    return rc, comp_err


def parse_class(out, fqcn):
    """按类拆账：Maven 在真失败时把摘要行前缀翻成 [ERROR]，所以锚不钉 ^\\[INFO\\]。"""
    m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)[^\n]*-- in "
                  + re.escape(fqcn), out)
    simple = fqcn.split(".")[-1]
    # 实测行形：`[ERROR] com...AcpRealAgentChainTest.someTest -- Time elapsed: 0.013 s <<< FAILURE!`
    # ⇒ 方法名与 <<< FAILURE! 之间还夹着 " -- Time elapsed: … s"，锚死紧邻会把真红误记成 SURVIVED。
    named = re.search(re.escape(simple) + r"\.\w+[^\n]*? <<< (FAILURE|ERROR)!", out)
    return dict(ran=int(m.group(1)) if m else 0,
                broke=(int(m.group(2)) + int(m.group(3))) if m else 0,
                named=bool(named))


def run_mvn_tests(catchers_csv, tag):
    log = os.path.join(LOGS, tag + ".test.log")
    rc, out = sh(["mvn", "-o", "-pl", "z-bot-core", "test", "-Dtest=" + catchers_csv,
                  "-DfailIfNoTests=false"], timeout=900)
    with open(log, "w") as f:
        f.write(out)
    comp_err = "COMPILATION ERROR" in out
    res = {}
    for c in catchers_csv.split(","):
        d = parse_class(out, c)
        d["rc"] = rc
        d["comp_err"] = comp_err
        d["log"] = log
        res[c] = d
    return res


def run_mvn_test(catcher, tag):
    return run_mvn_tests(catcher, tag)[catcher]


def run_e2e_all(tokens, tag):
    """真进程 E2E 当捕获者：判词是 `CHECK|<token> PASS|FAIL` 那一行。"""
    log = os.path.join(LOGS, tag + ".e2e.log")
    rc, out = sh(["python3", "_doc/005_testing/acceptance/p25/p25_e2e.py", RUN_ID + "_" + tag], timeout=1500)
    with open(log, "w") as f:
        f.write(out)
    green = {}
    for t in tokens:
        hit = re.search(r"CHECK\|%s\s+(PASS|FAIL)" % re.escape(t), out)
        green[t] = bool(hit and hit.group(1) == "PASS" and hit is not None)
        if hit is None:
            green[t] = False
    return green


def run_e2e(catcher_token, tag):
    green = run_e2e_all([catcher_token], tag)
    ok = green[catcher_token]
    return dict(ran=1, broke=0 if ok else 1, named=(not ok), comp_err=False,
                log=os.path.join(LOGS, tag + ".e2e.log"))


def assert_target_tree():
    """开工前证明 REPO 就是**这支尺自己所在的那棵目标仓根**（病根见 REPO 上方注释）。

    两重缺一不可：写手树 `/private/tmp/zbot-wt-p25` 自己也是合法 git 工作树，
    只问"toplevel 等不等于 REPO" 拦不住它（这条是被 p27 那支的注入对照实测出来的）。
    """
    own = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                       os.pardir, os.pardir, os.pardir))
    if REPO != own:
        print("FATAL|REPO 不在这支尺自己所在的仓里 ⇒ 量的是别的树\n"
              "      REPO=%s\n      tool_own_repo=%s" % (REPO, own))
        sys.exit(6)
    out = subprocess.Popen(["git", "rev-parse", "--show-toplevel"], cwd=REPO,
                           stdout=subprocess.PIPE).communicate()[0].decode().strip()
    if os.path.abspath(out) != REPO:
        print("FATAL|REPO 不是 git 仓库根（半棵树/被删的树）⇒ 本轮不出读数\n"
              "      REPO=%s\n      git_toplevel=%s" % (REPO, out or "<空>"))
        sys.exit(6)
    missing = [rel for rel in set(SRC.values()) if not os.path.isfile(os.path.join(REPO, rel))]
    if missing:
        print("FATAL|目标树里没有变异锚点所在文件 %d 个（第一个=%s）⇒ 尺的锚点不在被量的那棵树上"
              % (len(missing), missing[0]))
        sys.exit(6)
    head = subprocess.Popen(["git", "rev-parse", "--short", "HEAD"], cwd=REPO,
                            stdout=subprocess.PIPE).communicate()[0].decode().strip()
    branch = subprocess.Popen(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=REPO,
                              stdout=subprocess.PIPE).communicate()[0].decode().strip()
    print("TARGET|repo=%s head=%s branch=%s anchors=%d" % (REPO, head or "<未知>",
                                                           branch or "<未知>", len(SRC)))


def main():
    print("RUN|%s repo=%s ledger=%s" % (RUN_ID, REPO, LEDGER), flush=True)
    assert_target_tree()
    lock_fd = acquire_lock()
    manifest = snapshot_reference()
    if os.path.exists(LEDGER):
        os.remove(LEDGER)
    with open(LEDGER, "w") as f:
        f.write("run_id\tmutant\tfile\tclaim\tverdict\tproof\textra\n")

    control = {"id": "CONTROL", "file": "-", "claim": "阳性对照：零注入时同一套判据必须全绿"}
    # 一次 mvn 跑完 4 个具名捕获类（分开跑会把 5 分钟的固定开销乘 4），再加一次真进程 E2E。
    mvn_catchers = ",".join(sorted(set(m["catcher"] for m in MUTANTS if m["kind"] == "mvn")))
    res = run_mvn_tests(mvn_catchers, "control_mvn")
    bad = [c for c, r in res.items() if r["ran"] == 0 or r["broke"] != 0 or r["comp_err"]]
    for c, r in res.items():
        print("CONTROL|%s ran=%d broke=%d rc=%d" % (c, r["ran"], r["broke"], r["rc"]), flush=True)
    e2e_tokens = [m["catcher"] for m in MUTANTS if m["kind"] == "e2e"]
    eres = run_e2e_all(e2e_tokens, "control_e2e")
    ebad = [t for t, ok in eres.items() if not ok]
    for t, ok in eres.items():
        print("CONTROL|e2e:%s green=%d" % (t, int(ok)), flush=True)
    green_all = not bad and not ebad
    # CONTROL 行的记号口径：RED-OK = 零点成立（零注入全绿，本表每一行的红/绿才有参照）；
    # 不成立记 PARTIAL 并把异常项写进 proof —— 这是**量具/测试的噪声**，不许记成产品缺陷。
    row(control, "RED-OK" if green_all else "PARTIAL",
        "零注入下捕获者全绿（mvn 4 类 + e2e %d 判词）；异常项=%s%s"
        % (len(eres), ",".join(bad), ",".join(ebad)))

    for m in MUTANTS:
        rel = SRC[m["file"]]
        abs_src = os.path.join(REPO, rel)
        with open(abs_src) as f:
            body = f.read()
        n = body.count(m["old"])
        if n != 1:
            row(m, "INJECTION_NOT_APPLIED", "replace 命中数=%d（要求恰为 1）" % n, m["id"])
            continue
        with open(abs_src, "w") as f:
            f.write(body.replace(m["old"], m["new"]))
        class_rel = m["needle"][2]
        before_md5, _ = bytecode_state(class_rel)
        rc, comp_err = compile_core(m["id"])
        after_md5, dump = bytecode_state(class_rel)
        if comp_err or after_md5 is None:
            row(m, "INJECTION_NOT_APPLIED", "编译不过（mvn rc=%d）⇒ 字节码没产出" % rc, m["id"])
            restore_one(manifest, m["file"])
            continue
        mode, token, _ = m["needle"]
        found = token in dump
        needle_ok = found if mode == "present" else (not found)
        delta = before_md5 != after_md5
        if not (needle_ok and delta):
            row(m, "INJECTION_NOT_APPLIED",
                "字节码无 delta=%s 针 %s=%s（token=%s）⇒ 不能据此说检查抓不到"
                % (delta, mode, found, token), m["id"])
            restore_one(manifest, m["file"])
            continue

        if m["kind"] == "mvn":
            res = run_mvn_test(m["catcher"], m["id"])
        else:
            res = run_e2e(m["catcher"], m["id"])
        killed = res["ran"] > 0 and res["broke"] > 0 and res["named"] and not res["comp_err"]
        verdict = "KILLED" if killed else "SURVIVED"
        proof = ("class_md5 %s→%s;needle %s %s=1;ran=%d broke=%d named=%s;log=%s"
                 % (before_md5[:8], after_md5[:8], mode, token, res["ran"], res["broke"],
                    res["named"], os.path.basename(res["log"])))
        row(m, verdict, proof, "catcher=%s" % m["catcher"])
        restore_one(manifest, m["file"])
        compile_core("restore_" + m["id"])

    ok = restore(manifest)
    print("DONE|rows=%d restore_verified=%s" % (len(ROWS), ok), flush=True)
    fcntl.flock(lock_fd, fcntl.LOCK_UN)
    os.close(lock_fd)
    surv = [r for r in ROWS if r[4] == "SURVIVED"]
    sys.exit(0 if not surv else 1)


def restore_one(manifest, key):
    shutil.copyfile(os.path.join(REF, key + ".java"), os.path.join(REPO, SRC[key]))
    got = md5_file(os.path.join(REPO, SRC[key]))
    if got != manifest[key]:
        print("RESTORE-FATAL|%s md5 %s != %s" % (key, got[:8], manifest[key][:8]))
        sys.exit(5)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:
        import traceback
        traceback.print_exc()
        print("MUT-EXC %r" % (exc,))
        sys.exit(5)
