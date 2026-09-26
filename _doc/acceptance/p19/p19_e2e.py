#!/usr/bin/env python3
"""
杠③ · P19 命令表单源 —— 真进程 E2E。

两个真 JVM、两个真端点，量的都是进程吐出来的真字节：

  JVM#1  {@code ZBot serve --config-dir <临时 profile> --host 127.0.0.1 --port 0}
         ⇒ 真发 HTTP 拿 {@code GET /api/commands}（单源序列化出来的整张表），
         ⇒ 再拿 {@code GET /}（内置控制台的 HTML 字节）。
  JVM#2  {@code ZBot repl --config-dir <同一 profile>}，stdin 喂 {@code /help}
         ⇒ 拿 TUI 真打出来的那张命令表。

判据：
  A. {@code /api/commands} 200 + 数组 + 每行有 endpoints/scope；四面各非空。
  B. **HTTP 的 TUI 段（去掉别名） == TUI /help 真表** —— 两个进程、两条代码路径同源。
  C. **HTTP 的 WEB 段 == 从真 HTML 字节里扫出的 handleSlash 分支集** —— "广告即兑现"
     在真字节上成立（这一段不读仓库源码，读的就是进程回的那 200 字节）。
  D. 旧的手抄残留不许回来（{@code /exit      — 退出} 那行、侧栏那 5 行 {@code <code>}）。
  E. 漂移探针（阳性对照）：把真 HTML 里一条分支改名，重跑 C ⇒ 必须判出漂移。
     没有这条，C 可能是一条永远不会红的死判据。
  F. 杠④：三个时点各测一次 {@code ~/.zbot} 不变量；全程只喂 {@code --config-dir}
     的临时 profile，真 key 只量长度。

仓库根从 __file__ 派生（p25/p26/p27 三支尺刚因为硬编码写手树绝对路径被判"量的是写手树"）。
"""
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p19-lead")
os.makedirs(CACHE, exist_ok=True)
CP_MAIN = os.path.join(REPO, "z-bot-core", "target", "classes")
CP_DEPS = os.path.join(CACHE, "cp.txt")
MAIN = "com.zifang.z.bot.ZBot"
HOST = "127.0.0.1"

FAILS = []
NOTES = []


def fail(msg):
    FAILS.append(msg)
    print("FAIL|" + msg, flush=True)


def note(msg):
    NOTES.append(msg)
    print("NOTE|" + msg, flush=True)


def md5_8(path):
    if not os.path.isfile(path):
        return "<missing>"
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()[:8]


# ---------------- 杠④：三个时点的 ~/.zbot 不变量 ----------------

def bar4(tag):
    home = os.path.expanduser("~/.zbot")
    entries = sorted(os.listdir(home))
    cfg = md5_8(os.path.join(home, "config.properties"))
    db = md5_8(os.path.join(home, "state.db"))
    # 真 key 永不读取，只量长度（许可口径：awk -F= '/^minimax\\.api\\.key=/{print length($2)}'）
    keylen = subprocess.run(
        ["awk", "-F=", "/^minimax\\.api\\.key=/{print length($2)}",
         os.path.join(home, "config.properties")],
        capture_output=True, text=True).stdout.strip()
    line = "BAR4|tag=%s dir_count=%d cfg_md5_8=%s db_md5_8=%s minimax_key_len=%s entries=%s" % (
        tag, len(entries), cfg, db, keylen, ",".join(entries))
    print(line, flush=True)
    if len(entries) != 8 or cfg != "2dadaed0" or db != "690ddbc0" or keylen != "125":
        fail("杠④ %s 不变量破了: %s" % (tag, line))
    return set(entries)


# ---------------- profile ----------------

def make_profile(name):
    d = os.path.join(CACHE, name)
    os.makedirs(os.path.join(d, "cron"), exist_ok=True)
    os.makedirs(os.path.join(d, "memories"), exist_ok=True)
    os.makedirs(os.path.join(d, "sessions"), exist_ok=True)
    with open(os.path.join(d, "config.properties"), "w") as f:
        f.write("providers=p19stub\n"
                "llm.provider=p19stub\n"
                "p19stub.type=openai\n"
                "p19stub.api.key=stub-key-not-real\n"
                "p19stub.base.url=http://127.0.0.1:1/v1\n"
                "p19stub.model=p19-test-model\n"
                "agent.exec.confirm=off\n")
    return d


def classpath():
    if not os.path.isfile(CP_DEPS) or os.path.getsize(CP_DEPS) == 0:
        raise SystemExit("FATAL|依赖 classpath 台账为空/缺失（%s）—— 先跑 "
                         "mvn -o -pl z-bot-core dependency:build-classpath" % CP_DEPS)
    deps = open(CP_DEPS).read().strip()
    if not os.path.isdir(CP_MAIN):
        raise SystemExit("FATAL|没有编译产物 %s —— 先 mvn -o test-compile" % CP_MAIN)
    return CP_MAIN + ":" + deps


# ---------------- 起进程 ----------------

def start_serve(cp, profile):
    p = subprocess.Popen(["java", "-cp", cp, MAIN, "serve", "--config-dir", profile,
                          "--host", HOST, "--port", "0"],
                         cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, bufsize=1)
    deadline = time.time() + 60
    buf = []
    while time.time() < deadline:
        line = p.stdout.readline()
        if not line:
            if p.poll() is not None:
                break
            time.sleep(0.05)
            continue
        buf.append(line.rstrip())
        m = re.search(r"http://%s:(\d+)/" % re.escape(HOST), line)
        if m:
            return p, int(m.group(1)), buf
    out = "\n".join(buf)
    try:
        p.kill()
    except Exception:
        pass
    raise SystemExit("FATAL|serve 没打印监听端口（判 FATAL，不猜端口）：\n" + out[-3000:])


def http_get(port, path, timeout=15):
    url = "http://%s:%d%s" % (HOST, port, path)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, "%s: %s" % (type(e).__name__, e)


def run_repl_help(cp, profile):
    """真 JVM#2：repl 里打 /help，拿它真打出来的那张表。"""
    r = subprocess.run(["java", "-cp", cp, MAIN, "repl", "--config-dir", profile],
                       input="/help\n/exit\n", cwd=REPO,
                       capture_output=True, text=True, timeout=120)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


# ---------------- 判据 ----------------

ANSI = re.compile(r"\x1b\[[0-9;]*m")


def table_names_from_repl(text):
    """/help 表行形如 {@code "    /tools        列出已注册工具"}（四空格 + 名字 + ≥2 空格 + 说明）。

    <p>真终端会带 SGR 着色（实测行字节是
    {@code '    \\x1b[38;5;51m/tools        \\x1b[0m  \\x1b[2m列出已注册工具\\x1b[0m'}），
    所以先剥 ANSI 再扫 —— 剥的是渲染层，名字与说明这些载荷字节一个字都不动。</p>
    """
    plain = ANSI.sub("", text)
    names = []
    for m in re.finditer(r"(?m)^ {4}(/[A-Za-z0-9_-]+)\s{2,}\S", plain):
        if m.group(1) not in names:
            names.append(m.group(1))
    return names


JS_BRANCH = re.compile(r"cmd\s*===\s*'(/[^']*)'|cmd\.startsWith\('(/[^']*)'\)")


def js_branches(html):
    i = html.find("async function handleSlash(text) {")
    if i < 0:
        raise SystemExit("FATAL|serve 吐回来的 HTML 里找不到 handleSlash —— 判 FATAL 不判通过")
    body = html[i:]
    j = body.find("\n    }\n")
    out = []
    for m in JS_BRANCH.finditer(body[:j if j > 0 else len(body)]):
        name = m.group(1) or m.group(2)
        if name not in out:
            out.append(name)
    return out


def check_run(n, cp, entries_snapshot):
    profile = make_profile("e2e-profile-r%d" % n)
    proc = None
    print("=== RUN %d ===" % n, flush=True)
    try:
        proc, port, boot = start_serve(cp, profile)
        note("serve 起在 http://%s:%d（profile=%s）" % (HOST, port, os.path.basename(profile)))

        code, body = http_get(port, "/api/commands")
        if code != 200:
            fail("R%d /api/commands 状态码 %s（不是 200）: %s" % (n, code, body[:200]))
            return
        try:
            rows = json.loads(body)
        except Exception as e:
            fail("R%d /api/commands 不是 JSON: %s / %s" % (n, e, body[:200]))
            return
        if not isinstance(rows, list) or not rows:
            fail("R%d /api/commands 数组为空 ⇒ FATAL（空输入不判通过）" % n)
            return
        seg = {"tui": [], "http": [], "web": [], "acp": []}
        aliases = []
        for row in rows:
            for k in ("name", "description", "scope", "endpoints"):
                if k not in row:
                    fail("R%d 行缺列 %s: %s" % (n, k, row))
                    return
            eps = row["endpoints"]
            if not isinstance(eps, list) or not eps:
                fail("R%d 行 endpoints 为空: %s" % (n, row["name"]))
                return
            if row.get("aliasOf"):
                aliases.append(row["name"])
            for e in eps:
                seg.setdefault(e, []).append(row["name"])
        for e in ("tui", "http", "web", "acp"):
            if not seg[e]:
                fail("R%d 端点段 %s 为空 ⇒ 那一端根本没消费单源" % (n, e))
                return
        note("R%d /api/commands 行=%d 别名=%d 段大小 tui=%d http=%d web=%d acp=%d" % (
            n, len(rows), len(aliases), len(seg["tui"]), len(seg["http"]),
            len(seg["web"]), len(seg["acp"])))

        # ---- 判据 D：手抄残留不许回来 ----
        code_h, html = http_get(port, "/")
        if code_h != 200:
            fail("R%d GET / 状态码 %s" % (n, code_h))
            return
        if "fetch('/api/commands')" not in html:
            fail("R%d 控制台没有改读 /api/commands（还在自己写清单）" % n)
        if 'id="quick-commands"' not in html:
            fail("R%d 控制台侧栏没有从单源渲染的挂载点 quick-commands" % n)
        for ghost in ["/exit      — 退出", "/new — 新建会话", "/status — 状态"]:
            if ghost in html:
                fail("R%d 手抄清单残留回来了: %r" % (n, ghost))

        # ---- 判据 B：HTTP 的 TUI 段（去别名） == repl /help 真表 ----
        rc, out = run_repl_help(cp, profile)
        if rc != 0:
            fail("R%d repl 退出码 %d，尾部: %s" % (n, rc, out[-500:]))
            return
        tui_table = table_names_from_repl(out)
        if not tui_table:
            fail("R%d 从 repl 输出里没扫到任何 /help 表行 ⇒ FATAL（空判红不判过）" % n)
            return
        tui_derived = [x for x in seg["tui"] if x not in aliases]
        if sorted(tui_derived) != sorted(tui_table):
            miss = sorted(set(tui_derived) - set(tui_table))
            extra = sorted(set(tui_table) - set(tui_derived))
            fail("R%d TUI 面不同源：表里有/help 没打=%s /help 打了表里没有=%s" % (n, miss, extra))
            return
        note("R%d 判据B 过：repl /help 的 %d 条 == /api/commands 的 tui 段(去别名)" % (
            n, len(tui_table)))

        # ---- 判据 C：WEB 段 == 真 HTML 字节里的分支集 ----
        web_table = js_branches(html)
        if sorted(web_table) != sorted(seg["web"]):
            fail("R%d WEB 面不同源：/api/commands web 段=%s 但 index.html 分支=%s" % (
                n, sorted(seg["web"]), sorted(web_table)))
            return
        note("R%d 判据C 过：web 段 %d 条与真 HTML 字节的分支逐名字相等" % (n, len(web_table)))

        # ---- 判据 E：阳性对照 —— 把一条分支改名，C 必须判出漂移 ----
        probe = html.replace("} else if (cmd.startsWith('/confirm')) {",
                             "} else if (cmd.startsWith('/confirmX')) {", 1)
        if probe == html:
            fail("R%d 漂移探针没打上（锚点不在真 HTML 字节里）" % n)
            return
        drifted = js_branches(probe)
        if sorted(drifted) == sorted(seg["web"]):
            fail("R%d 探针注入后仍然'同源' ⇒ 判据 C 是死的" % n)
            return
        note("R%d 判据E 过：改一条分支即判出漂移 (%s)" % (n, sorted(set(seg["web"]) ^ set(drifted))))

        # 顺带确认这个端点在 p28 之前的"不存在"结论已经翻篇：404 不该再出现在这条路径上
        code_x, _ = http_get(port, "/api/commands-not-a-route")
        if code_x != 404:
            fail("R%d 未注册路径应 404，实到 %s" % (n, code_x))
        bar4("run%d_after" % n)
    finally:
        if proc is not None:
            proc.send_signal(signal.SIGTERM)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
            note("serve 已收（pid=%s rc=%s）" % (proc.pid, proc.returncode))
        now = set(os.listdir(os.path.expanduser("~/.zbot")))
        if now != entries_snapshot:
            fail("E2E 往真 profile 里写了东西: %s" % sorted(now ^ entries_snapshot))


def main():
    runs = 1
    for a in sys.argv[1:]:
        if a.startswith("--runs="):
            runs = int(a.split("=", 1)[1])
    print("REPO=" + REPO, flush=True)
    if not os.path.isdir(os.path.join(REPO, "z-bot-core")):
        raise SystemExit("FATAL|REPO 派生不对: " + REPO)
    cp = classpath()
    snap = bar4("t0_before")
    for i in range(1, runs + 1):
        check_run(i, cp, snap)
    bar4("t3_final")
    if FAILS:
        print("E2E|result=FAILED fails=%d" % len(FAILS), flush=True)
        for f in FAILS:
            print("  - " + f, flush=True)
        sys.exit(1)
    print("E2E|result=PASS runs=%d notes=%d" % (runs, len(NOTES)), flush=True)


if __name__ == "__main__":
    main()
