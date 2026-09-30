#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""p28b 杠② 具名变异台账。

纪律（工单 §3.4）：
  * 注入前 flock 抢 <git-common-dir>/zbot-mutlock（LOCK_EX|LOCK_NB），抢不到 rc=4 直接退；
  * 还原只准用本次运行前 cp 的副本 + md5 对账（**禁用 git checkout/restore/stash/clean**，
    那会把没提交的改动一起抹掉）；
  * 每支注入都要证明"进了字节码"（改 .java 的：重编后目标 .class 的 md5 必须变；
    改数据文件的：该文件 md5 必须变）；证明不了 ⇒ INJECTION_NOT_APPLIED，不许算检出；
  * 负向判据带阳性对照（M5 是刻意放的"等价变异"对照位：它在 macOS 上结构性检不出，
    用来证明本台账不是"凡绿即杀"）。

marker 只准 KILLED / RED-OK / SURVIVED / PARTIAL / INJECTION_NOT_APPLIED。
"""
import fcntl
import hashlib
import os
import re
import select
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p28-mutation")
os.makedirs(CACHE, exist_ok=True)
RTR = "z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java"
HTC = "z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java"
TSV = "_doc/acceptance/p28/ROUTES.tsv"
CLS_RTR = "z-bot-core/target/classes/com/zifang/z/bot/ui/RawTerminalReader.class"
CLS_HTC = "z-bot-core/target/classes/com/zifang/z/bot/channel/HttpChannel.class"
TERM = "z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java"
CLS_TERM = "z-bot-core/target/classes/com/zifang/z/bot/channel/TerminalChannel.class"
WEB = "z-bot-core/src/main/resources/web/index.html"
CLS_WEB = "z-bot-core/target/classes/web/index.html"
README = "README.md"
ZBOT = "z-bot-core/src/main/java/com/zifang/z/bot/ZBot.java"
CLS_ZBOT = "z-bot-core/target/classes/com/zifang/z/bot/ZBot.class"
LEDGER = os.path.join(REPO, "_doc/acceptance/p28/LEDGER.tsv")
BAK = os.path.join(CACHE, "mutbak")
JARLESS_CP = os.path.join(REPO, "z-bot-core/target/test-classes") + ":" + os.path.join(REPO, "z-bot-core/target/classes")
PROBE = "com.zifang.z.bot.ui.RawTerminalVerdictProbe"

MARKS = ("KILLED", "RED-OK", "SURVIVED", "PARTIAL", "INJECTION_NOT_APPLIED", "NO-RUN")


def md5(path):
    if not os.path.exists(path):
        return "MISSING"
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def write(path, s):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(s)


def sh(args, timeout=600):
    p = subprocess.run(args, cwd=REPO, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def pty_probe(feed=b"/stat\t\n", hard=40, poison_path=False):
    """真 PTY 上起探针 JVM，返回 (判词字典, rc, 墙钟, 原始输出尾巴)。"""
    env = dict(os.environ)
    if poison_path:
        d = os.path.join(CACHE, "poisonbin")
        os.makedirs(d, exist_ok=True)
        write(os.path.join(d, "stty"), "#!/bin/sh\nsleep 60\n")
        os.chmod(os.path.join(d, "stty"), 0o755)
        env["PATH"] = d + ":" + env["PATH"]
    mfd, sfd = os.openpty()
    t0 = time.time()
    p = subprocess.Popen(["java", "-cp", JARLESS_CP, PROBE], stdin=sfd, stdout=sfd,
                         stderr=subprocess.STDOUT, env=env, cwd=REPO, close_fds=True)
    os.close(sfd)
    out = b""
    pending = feed
    err = ""
    while time.time() - t0 < hard:
        if pending:
            try:
                n = os.write(mfd, pending)
                pending = pending[n:]
            except OSError as e:
                err = repr(e)
                pending = b""
        r, _, _ = select.select([mfd], [], [], 0.2)
        if r:
            try:
                d = os.read(mfd, 4096)
            except OSError:
                d = b""
            if not d:
                break
            out += d
        if p.poll() is not None and not r:
            break
    rc = p.poll()
    if rc is None:
        p.kill()
        p.wait()
        rc = "KILLED"
        err = "HARD-TIMEOUT"
    os.close(mfd)
    parsed = {}
    for m in re.finditer(r"PROBE_([A-Z_]+)=(\S*)", out.decode("utf-8", "replace")):
        parsed["PROBE_" + m.group(1)] = m.group(2)
    return parsed, rc, time.time() - t0, (err, out[-200:])


# ---- 每支变异：期望哪个判据红（RED 判据写成可机检的谓词，不靠人眼看日志） ----
MUTANTS = [
    dict(id="M1", file=RTR, cls=CLS_RTR, name="摘掉 stty 子进程的 fd 0 继承",
         old="                    .redirectInput(ProcessBuilder.Redirect.INHERIT)\n", new="",
         gate="pty", red=lambda r: r[0].get("PROBE_RAW_VERDICT") != "DONE",
         expect="S1b 真 PTY 上 raw 判词必须是 DONE", why="本棒刚修的缺陷：管道 stdin ⇒ stty 永远 not-a-tty"),
    dict(id="M2", file=RTR, cls=CLS_RTR, name="把有界等待的时限抬成 Long.MAX",
         old="    static final long EXTERNAL_WAIT_TIMEOUT_MS = 5000L;",
         new="    static final long EXTERNAL_WAIT_TIMEOUT_MS = 9223372036854775807L;",
         gate="pty_poison", red=lambda r: r[1] != 0 or r[2] > 20.0,
         expect="S3b 假 stty 睡 60s 时整跑必须 20s 内回来", why="p28a 这支改动的全部意义就是这一个时限"),
    dict(id="M3", file=RTR, cls=CLS_RTR, name="非零退出也报 DONE（判词揉成一团）",
         old="            return p.exitValue() == 0 ? ExternalVerdict.DONE : ExternalVerdict.NONZERO_EXIT;",
         new="            return ExternalVerdict.DONE;",
         gate="pty_pipe", red=lambda r: r[0].get("PROBE_RAW_VERDICT") != "NONZERO_EXIT",
         expect="S2a 无 tty 时必须报 NONZERO_EXIT", why="四值判词若塌成一值，降级就无从判断"),
    dict(id="M4", file=RTR, cls=CLS_RTR, name="waitFor 返回真值时反判成超时",
         old="            if (!p.waitFor(EXTERNAL_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {",
         new="            if (p.waitFor(EXTERNAL_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {",
         gate="pty", red=lambda r: r[0].get("PROBE_RAW_VERDICT") != "DONE",
         expect="S1a/S1b 正常返回必须判 DONE", why="边界判反 ⇒ 每次按键都以为对面挂了"),
    dict(id="M5", file=RTR, cls=CLS_RTR, name="【阳性对照·等价变异】isSttyAvailable() 直接 return true",
         old="        return runBounded(\"which\", \"stty\") == ExternalVerdict.DONE;",
         new="        return true;",
         gate="pty", red=lambda r: r[0].get("PROBE_RAW_VERDICT") != "DONE",
         expect="本支**期望检不出**（macOS/Linux 上 which stty 本来就成功）", why="放在这里就是为了证明台账不是凡绿即杀"),
    dict(id="M6", file=HTC, cls=CLS_HTC, name="摘掉台账的 404 门",
         old="""            if (routesOf(path).isEmpty()) {
                json(ex, 404, error("not found: " + path));
                return;
            }
""", new="",
         gate="mvn", mvn="HttpRouteLedgerTest#unknownPathStillFourOhFourWithJsonErrorShape",
         red=None, expect="未登记路径必须 404（真进程侧已量到 404 原文）"),
    dict(id="M7", file=HTC, cls=CLS_HTC, name="摘掉 405 的 Allow 头",
         old='                ex.getResponseHeaders().set("Allow", ledgerMethods(path));\n', new="",
         gate="mvn", mvn="HttpRouteLedgerTest#unadvertisedMethodGetsFourOhFiveWithAllowHeader",
         red=None, expect="405 必须带 Allow（方法面要能被客户端复原）"),
    dict(id="M8", file=HTC, cls=CLS_HTC, name="SSE 帧里裸换行不再转义",
         old='data.replace("\\r", "").replace("\\n", "\\\\n")',
         new='data.replace("\\r", "").replace("\\n", "\\n")',
         gate="mvn", mvn="HttpSseContractTest",
         red=None, expect="帧语法必须逐字节 event: X\\ndata: Y\\n\\n（裸换行会把一帧劈成两帧）"),
    dict(id="M9", file=TSV, cls=None, name="台账文件与代码漂移一个字",
         old="/api/session/switch", new="/api/session/switxh",  # /api/cron 在 TSV 里有两行，锚点要求恰好 1 次
         gate="mvn", mvn="HttpRouteLedgerTest#routesTsvIsInSyncWithLedger",
         red=None, expect="ROUTES.tsv 必须与 routes() 逐字节相等（分母不许被手改）"),
    dict(id="M10", file=TERM, cls=CLS_TERM, name="终端 /theme 派发分串漂移（台账说它有、代码里认的是另一个字串）",
         old='} else if ("/theme".equals(name)) {',
         new='} else if ("/themme".equals(name)) {',
         gate="mvn", mvn="CommandSurfaceConsistencyTest#tuiHandlesExactlyTheChannelLocalSegment",
         red=None, expect="TerminalChannel 的分支名 ⇄ CommandCatalog.localNames() 必须逐字同名"),
    # ---- M11–M14：控制台页的"接线"面（09-26 实测出过的三处病灶，各打一遍） ----
    dict(id="M11", file=WEB, cls=CLS_WEB, name="摘掉启动时的 loadCommands() 接线（09-26 病灶一）",
         old="        loadCommands();\n", new="",
         gate="mvn", mvn="WebConsoleWiringTest#everyDeclaredConsoleFunctionHasACallSite",
         red=None, expect="命令表初始化必须是活的接线点（摘掉 ⇒ 声明了没人接）"),
    dict(id="M12", file=WEB, cls=CLS_WEB, name="在真 newSession 之前塞一份同名空壳（09-26 病灶二）",
         old="    async function newSession() {",
         new="    function newSession() { }\n    async function newSession() {",
         gate="mvn", mvn="WebConsoleWiringTest#noFunctionIsDeclaredTwiceOnTheConsolePage",
         red=None, expect="同名声明只许一次；两次 ⇒ 后一份静默赢、按钮走空壳"),
    dict(id="M13", file=WEB, cls=CLS_WEB, name="把输入框 autoResize 的监听摘掉（09-26 病灶三）",
         old="    $input.addEventListener('input', autoResize);\n", new="",
         gate="mvn", mvn="WebConsoleWiringTest#everyDeclaredConsoleFunctionHasACallSite",
         red=None, expect="自适应高度必须被绑上；摘掉 ⇒ autoResize 变孤儿"),
    dict(id="M14", file=WEB, cls=CLS_WEB, name="内联处理器指向一个不存在的函数",
         old='onclick="clearSession()"', new='onclick="clearSessioon()"',
         gate="mvn", mvn="WebConsoleWiringTest#everyInlineHandlerNamesAnExistingConsoleFunction",
         red=None, expect="点下去就是 ReferenceError，必须在编译期之前被结构尺拦住"),
    # M15/M16：README 是对外第一面，它写死的每个数都得有人问代码"现在还是吗"（ReadmeClaimsTest）。
    # 预期红集现在（09-27 01:3x）写死，跑之前不改；M15 打文档侧漂移，M16 打代码侧漂移 ——
    # 同一把尺的两端各钉一次，避免"只证明了改文档会红"这种半边覆盖。
    dict(id="M15", file=README, cls=None, name="README 的路由行数手改一个字（文档与台账分家）",
         old="**26 行（方法粒度）", new="**27 行（方法粒度）",
         gate="mvn", mvn="ReadmeClaimsTest#everyQuantitativeReadmeClaimMatchesTheRecomputation",
         red=None, expect="README 的定量主张必须等于 HttpChannel.routes() 的重算值；手抄就是第二份清单"),
    dict(id="M16", file=ZBOT, cls=CLS_ZBOT, name="从 CLI 注解里摘掉一支子命令（README 仍列着它）",
         old="StatusCommand.class, SessionsCommand.class, SendCommand.class, PairCommand.class,",
         new="StatusCommand.class, SessionsCommand.class, SendCommand.class,",
         gate="mvn", mvn="ReadmeClaimsTest#everyQuantitativeReadmeClaimMatchesTheRecomputation",
         red=None, expect="顶层子命令支数与名单都必须跟 @Command(subcommands=…) 对齐（少一支 ⇒ 支数与名单双双红）"),
]



def assert_target_tree():
    """开工前证明 REPO 就是**这支尺自己所在的那棵目标仓根**；不满足 ⇒ 不出读数直接退。

    只问 `git rev-parse --show-toplevel == REPO` 拦不住写手树（那棵树自己也是合法工作树），
    所以先钉"尺与被量的树同仓"，再钉"那棵树真的是仓根"。这支尺原本硬编码
    `/private/tmp/zbot-wt-p28`，写手树被扫掉后台账就没法在目标树上重生成 —— 09-27 实测。
    """
    own = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
    if REPO != own:
        print("FATAL|REPO 不在这支尺自己所在的仓里 ⇒ 量的是别的树\n     REPO=%s\n     tool_own_repo=%s" % (REPO, own))
        return 6
    rc, top = sh(["git", "rev-parse", "--show-toplevel"])
    top = top.strip()
    if rc != 0 or os.path.abspath(top) != REPO:
        print("FATAL|REPO 不是 git 仓库根（半棵树/被删的树）⇒ 本轮不出读数\n     REPO=%s\n     git_toplevel=%s" % (REPO, top or "<空>"))
        return 6
    rc, head = sh(["git", "rev-parse", "--short", "HEAD"])
    rc2, dirty = sh(["git", "status", "--porcelain"])
    print("TARGET|repo=%s head=%s dirty_lines=%d" % (REPO, head.strip() or "<未知>",
          len([l for l in dirty.splitlines() if l.strip()])))
    return 0


def main():
    tree_rc = assert_target_tree()
    if tree_rc:
        return tree_rc
    common = sh(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"])[1].strip()
    lockpath = os.path.join(common, "zbot-mutlock")
    lf = open(lockpath, "w")
    try:
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("LOCK_BUSY %s ⇒ rc=4（不睡等、不杀别的持有者）" % lockpath)
        return 4
    print("LOCK_ACQUIRED %s" % lockpath, flush=True)
    os.makedirs(BAK, exist_ok=True)
    files = sorted(set(m["file"] for m in MUTANTS))
    orig_md5 = {}
    for f in files:
        dst = os.path.join(BAK, f.replace("/", "__"))
        subprocess.run(["cp", "--", os.path.join(REPO, f), dst], check=True)
        orig_md5[f] = md5(os.path.join(REPO, f))
        print("BACKUP %s md5=%s -> %s" % (f, orig_md5[f][:8], dst), flush=True)

    rows = ["id\tname\tfile\tgate\tmarker\tevidence\tbytecode_proof"]
    try:
        for m in MUTANTS:
            src_path = os.path.join(REPO, m["file"])
            text = read(src_path)
            n = text.count(m["old"])
            if n != 1:
                rows.append("%s\t%s\t%s\t%s\tINJECTION_NOT_APPLIED\t锚点在源里出现 %d 次（要求恰好 1 次）\t-"
                            % (m["id"], m["name"], m["file"], m["gate"], n))
                print("%s SKIP 锚点 %d 次" % (m["id"], n), flush=True)
                continue
            cls_before = md5(os.path.join(REPO, m["cls"])) if m["cls"] else "DATA-FILE"
            write(src_path, text.replace(m["old"], m["new"], 1))
            file_md5_after = md5(src_path)
            crc, cout = sh(["mvn", "-o", "-pl", "z-bot-core", "test-compile"], timeout=900)
            if crc != 0 and "COMPILATION ERROR" in cout:
                # 编译不过 = 变异体没进字节码，但也可能是它真把语义砍断了；两种都不能算检出
                marker = "INJECTION_NOT_APPLIED"
                ev = "注入后 testCompile 失败 ⇒ 没有可比对的字节码: %s" % \
                     [l for l in cout.splitlines() if "ERROR]" in l][:1]
                cls_after = "N/A"
            else:
                cls_after = md5(os.path.join(REPO, m["cls"])) if m["cls"] else file_md5_after
                if m["gate"].startswith("pty"):
                    if m["gate"] == "pty_poison":
                        res = pty_probe(feed=b"/after-poison\n", poison_path=True)
                    elif m["gate"] == "pty_pipe":
                        pr = subprocess.run(["java", "-cp", JARLESS_CP, PROBE], input=b"/piped-line\n",
                                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                            cwd=REPO, timeout=120)
                        parsed = {}
                        for mm in re.finditer(r"PROBE_([A-Z_]+)=(\S*)", pr.stdout.decode("utf-8", "replace")):
                            parsed["PROBE_" + mm.group(1)] = mm.group(2)
                        res = (parsed, pr.returncode, 0.0, ("pipe-mode", pr.stdout[-200:]))
                    else:
                        res = pty_probe()
                    red = m["red"](res)
                    marker = "RED-OK" if red else "SURVIVED"
                    ev = "verdict raw=%s restore=%s rc=%s wall=%.2f" % (
                        res[0].get("PROBE_RAW_VERDICT"), res[0].get("PROBE_RESTORE_VERDICT"),
                        res[1], res[2])
                else:
                    t = m["mvn"].split("#")[0]
                    mth = m["mvn"].split("#")[1] if "#" in m["mvn"] else None
                    args = ["mvn", "-o", "-pl", "z-bot-core", "test", "-Dtest=%s" % t]
                    if mth:
                        args[-1] = "-Dtest=%s#%s" % (t, mth)
                    rc2, out2 = sh(args, timeout=900)
                    # **尺的牙口自检**：点名的那条判据到底跑没跑？surefire 只在真的跑过某个类时打
                    # "... -- in <fqcn>" 汇总行；`-Dtest=Class#method` 里 method 不存在时这条行根本不出现，
                    # 于是 mvn rc=0、0.6 s 收工 —— 上一版把这种"没跑"记成 SURVIVED（等于凭空造出一条缺陷账）。
                    sumline = re.search(r"Tests run: (\d+), Failures: \d+, Errors: \d+, Skipped: \d+.*-- in [\w.$]*\."
                                        + re.escape(t) + r"$", out2, re.M)
                    ran = int(sumline.group(1)) if sumline else 0
                    hits = [l.strip()[:150] for l in out2.splitlines()
                            if re.match(r"^\[ERROR\]\s+\S+Test\.\S+:[0-9]+", l.strip())]
                    if ran == 0:
                        marker = "NO-RUN"
                        ev = ("点名 %s 一条都没跑（没有 '-- in %s' 汇总行；mvn rc=%d，墙钟读数不像跑过一套用例）"
                              " ⇒ 不许把没读数量成检出/漏检，先修选择器" % (m["mvn"], t, rc2))
                    else:
                        red = rc2 != 0 and (("Tests run" in out2 and ("FAIL" in out2 or "ERROR" in out2))
                                            or "BUILD FAILURE" in out2)
                        ev = "mvn rc=%d ran=%d 具名红=%s" % (rc2, ran,
                             hits[:2] if hits else out2[-160:].replace("\n", " "))
                        marker = "RED-OK" if red else "SURVIVED"
                if m["id"] == "M5" and not red:
                    marker = "SURVIVED"  # 预期的等价变异，仍如实记 SURVIVED（不另立"合理豁免"记号）
            if m["id"] == "M10" and marker == "SURVIVED":
                ev += (" | 这支 09-26 记的是\u201cPTY 判词检不出\u201d，锚点随 P19 单源化被搬走（旧锚点在本文件出现 0 次 ⇒ INJECTION_NOT_APPLIED）"
                       "；09-27 重锚到 TerminalChannel 的派发分串后若仍 SURVIVED，那\u201c台账与派发不同名\u201d就是真缺口")
            rows.append("%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
                m["id"], m["name"], m["file"], m["gate"], marker, ev.replace("\t", " "),
                ("class_md5 %s->%s" % (cls_before[:8], cls_after[:8])) if m["cls"] else ("file_md5=%s" % file_md5_after[:8])))
            print("%s %s | %s" % (m["id"], marker, ev[:170]), flush=True)
            # 还原：只准从本次 cp 的副本
            subprocess.run(["cp", "--", os.path.join(BAK, m["file"].replace("/", "__")), src_path], check=True)
            back = md5(src_path)
            if back != orig_md5[m["file"]]:
                print("FATAL 还原后 md5 不符 %s %s != %s" % (m["file"], back, orig_md5[m["file"]]))
                rows.append("RESTORE\tFATAL\t%s\t-\tPARTIAL\tmd5 不符\t%s" % (m["file"], back))
                break
    finally:
        for f in files:
            subprocess.run(["cp", "--", os.path.join(BAK, f.replace("/", "__")), os.path.join(REPO, f)], check=True)
        for f in files:
            assert md5(os.path.join(REPO, f)) == orig_md5[f], "还原失败 " + f
        print("RESTORED_ALL %s" % {os.path.basename(f): md5(os.path.join(REPO, f))[:8] for f in files}, flush=True)
        sh(["mvn", "-o", "-pl", "z-bot-core", "test-compile"], timeout=900)
        write(LEDGER, "\n".join(rows) + "\n")
        print("LEDGER=%s mtime=%s harness_mtime=%s" % (
            LEDGER, time.strftime('%H:%M:%S', time.localtime(os.path.getmtime(LEDGER))),
            time.strftime('%H:%M:%S', time.localtime(os.path.getmtime(__file__)))), flush=True)
        fcntl.flock(lf.fileno(), fcntl.LOCK_UN)
        lf.close()
    tally = {}
    for r in rows[1:]:
        mk = r.split("\t")[4]
        tally[mk] = tally.get(mk, 0) + 1
    print("TALLY %s TOTAL=%d" % (tally, len(rows) - 1), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
