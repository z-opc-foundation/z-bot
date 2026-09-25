#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P11b（红线 1 profile 隔离）验收③：真进程 E2E。

只测单测测不到的两件事：
  * **环境变量** —— 进程内改不了 env，`ZBOT_HOME` 这一档只有真进程能证（变异检验里 M1 就是全绿）；
  * **真终端** —— `stty` 备份换一个落点，只有真 pty 跑一次 REPL 才知道会不会互相踩。

红线：全程 --config-dir / ZBOT_HOME 指向临时目录；真 key 不进任何临时目录（M11b 的 config 里
一律 stub-key-not-real）；收尾核对 ~/.zbot 项数与两个 md5，并核对 .stty.bak 的 mtime 没动。

复算:
  mvn -o -pl z-bot-core package -DskipTests
  python3 _doc/acceptance/p11b/p11b_e2e.py
"""
import hashlib
import os
import select
import shutil
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")

RESULTS = []


def check(name, ok, detail):
    RESULTS.append((name, bool(ok), detail))
    print("%-4s %-46s %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)
    return bool(ok)


def flat(out):
    keep = [l for l in out.split("\n")
            if l.strip() and not l.startswith("WARNING:")
            and "enable-native-access" not in l and "Restricted methods" not in l]
    return " ⏎ ".join(keep)


def run(cli_args, env=None, java_props=(), timeout=120):
    cmd = ["java"] + ["-D" + p for p in java_props] + ["-jar", JAR] + list(cli_args)
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       env=env, timeout=timeout)
    return p.returncode, p.stdout.decode("utf-8", "replace")


def rows(out):
    """把 `z-bot status` 的两空格缩进表格摊成 dict。"""
    table = {}
    for line in out.split("\n"):
        s = line.strip()
        if not s or line.startswith("WARNING:") or " " not in s:
            continue
        if not line.startswith("  ") or line.startswith("    "):
            continue
        key, _, value = s.partition(" ")
        table[key] = value.strip()
    return table


def home_env(extra=None):
    env = dict(os.environ)
    env.update(extra or {})
    return env


def snapshot_profile():
    names = sorted(os.listdir(REAL_HOME))
    digests = {}
    for n in ("config.properties", "state.db"):
        path = os.path.join(REAL_HOME, n)
        if os.path.isfile(path):
            with open(path, "rb") as fh:
                digests[n] = hashlib.md5(fh.read()).hexdigest()
    bak = os.path.join(REAL_HOME, ".stty.bak")
    mtime = int(os.stat(bak).st_mtime) if os.path.exists(bak) else None
    return names, digests, mtime


def write_stub_config(dirpath):
    os.makedirs(dirpath, exist_ok=True)
    with open(os.path.join(dirpath, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("provider=minimax\n"
                 "minimax.type=openai\n"
                 "minimax.base.url=http://127.0.0.1:9/v1\n"
                 "minimax.api.key=stub-key-not-real\n"
                 "minimax.model=stub-model\n")


def pty_repl(workdir, io_tmp, feed_after=3.0, timeout=90):
    """在真 pty 里跑一次降级模式 REPL：先记 stty，再跑 repl，喂 /exit，最后再记 stty。

    TERM=dumb 把 JLine 让路给 RawTerminalReader（否则 stty 是 JLine 自己管的，
    这一跑就证不到我们改的那段）。java.io.tmpdir 指到专用目录，好让守望线程
    抓到"备份文件真的存在过"这一正证据。
    """
    before = os.path.join(workdir, "stty-before")
    after = os.path.join(workdir, "stty-after")
    script = ('stty -g > "%s" 2>/dev/null; '
              'java -Djava.io.tmpdir="%s" -jar "%s" repl --config-dir "%s"; '
              'stty -g > "%s" 2>/dev/null; exit 0'
              % (before, io_tmp, JAR, workdir, after))
    mfd, sfd = os.openpty()
    proc = subprocess.Popen(["/bin/sh", "-c", script], stdin=sfd, stdout=sfd,
                            stderr=subprocess.STDOUT, close_fds=True,
                            env=home_env({"ZBOT_HOME": workdir, "TERM": "dumb"}))
    os.close(sfd)
    seen = [False]

    def watch():
        end = time.time() + timeout
        while time.time() < end:
            try:
                if any(n.startswith("zbot-stty-") for n in os.listdir(io_tmp)):
                    seen[0] = True
            except OSError:
                return
            time.sleep(0.02)

    def feed():
        end = time.time() + timeout
        while time.time() < end:
            if proc.poll() is not None:
                return
            try:
                os.write(mfd, b"/exit\r")
            except OSError:
                return
            time.sleep(0.5)

    threading.Thread(target=watch, daemon=True).start()
    threading.Timer(feed_after, feed).start()
    chunks = []
    deadline = time.time() + timeout
    while time.time() < deadline:
        r, _, _ = select.select([mfd], [], [], 0.5)
        if not r:
            if proc.poll() is not None:
                break
            continue
        try:
            data = os.read(mfd, 4096)
        except OSError:
            break
        if not data:
            break
        chunks.append(data)
    try:
        os.close(mfd)
    except OSError:
        pass
    if proc.poll() is None:
        proc.kill()
        proc.wait()
    out = b"".join(chunks).decode("utf-8", "replace")
    read = lambda p: open(p, encoding="utf-8").read().strip() if os.path.exists(p) else None
    leftovers = [n for n in os.listdir(io_tmp) if n.startswith("zbot-stty-")]
    return out, read(before), read(after), seen[0], leftovers


def main():
    if not os.path.exists(JAR):
        print("FATAL: 没有 %s —— 先 mvn -o -pl z-bot-core package -DskipTests" % JAR)
        return 2
    base_names, base_digests, base_bak_mtime = snapshot_profile()
    tmp = tempfile.mkdtemp(prefix="p11b-e2e-")
    try:
        home_a = os.path.join(tmp, "homeA")
        home_b = os.path.join(tmp, "homeB")
        write_stub_config(home_a)
        write_stub_config(home_b)
        real_literal = os.path.join(os.path.expanduser("~"), ".zbot")

        # ---- E1 ZBOT_HOME（真 env，单测造不出来）----
        rc, out = run(["status"], env=home_env({"ZBOT_HOME": home_a}))
        t = rows(out)
        check("E1a status rc=0", rc == 0, "rc=%s | %s" % (rc, flat(out)[:120]))
        check("E1b sandbox 落在 ZBOT_HOME 里",
              t.get("sandbox", "").startswith(home_a), t.get("sandbox", "(无行)"))
        check("E1c sessions 落在 ZBOT_HOME 里",
              t.get("sessions", "").startswith(home_a), t.get("sessions", "(无行)"))
        check("E1d config 行落在 ZBOT_HOME 里",
              t.get("config", "").startswith(home_a), t.get("config", "(无行)"))
        check("E1e 输出里不再出现真实 home 的 .zbot",
              real_literal not in out, "命中 %s" % real_literal if real_literal in out else "0 命中")

        # ---- E2 --config-dir 与 ZBOT_HOME 同向 ----
        rc, out = run(["status", "--config-dir", home_b])
        t = rows(out)
        check("E2a --config-dir 的 sandbox/sessions 都在该 profile",
              t.get("sandbox", "").startswith(home_b)
              and t.get("sessions", "").startswith(home_b),
              "%s | %s" % (t.get("sandbox"), t.get("sessions")))
        check("E2b ZBOT_HOME 未设时不误捡真实 home",
              real_literal not in out, flat(out)[:100])

        # ---- E3 沙箱阶梯在真进程里 ----
        s1 = os.path.join(tmp, "sysprop-sandbox")
        s2 = os.path.join(tmp, "cli-sandbox")
        os.makedirs(s1, exist_ok=True)
        os.makedirs(s2, exist_ok=True)
        rc, out = run(["status", "--config-dir", home_a, "--sandbox", s2],
                      java_props=("zbot.sandbox=" + s1,))
        check("E3a --sandbox 压过 -Dzbot.sandbox",
              rows(out).get("sandbox") == s2, rows(out).get("sandbox"))
        rc, out = run(["status", "--config-dir", home_a], java_props=("zbot.sandbox=" + s1,))
        check("E3b 无 --sandbox 时 -Dzbot.sandbox 生效",
              rows(out).get("sandbox") == s1, rows(out).get("sandbox"))
        rc, out = run(["status", "--config-dir", home_a])
        check("E3c 两者都不给才落 <profile>/workspace",
              rows(out).get("sandbox") == os.path.join(home_a, "workspace"),
              rows(out).get("sandbox"))

        # ---- E4 两个 profile 的库互不相见（真 sqlite3 塞行，再看谁看得见）----
        db_a = os.path.join(home_a, "state.db")
        rc_a, out_a = run(["sessions", "--config-dir", home_a, "list"])
        check("E4a 新 profile 自己建了 state.db", os.path.exists(db_a),
              "rc=%s | %s" % (rc_a, flat(out_a)[-90:]))
        check("E4b homeA 开局 0 个会话（没读到真实 home 的库）",
              "共 0 个会话" in out_a, flat(out_a)[-90:])
        subprocess.run(["sqlite3", db_a,
                        "INSERT INTO sessions(id,title,created_at,updated_at) VALUES("
                        "'p11b-probe','红线1探针','2026-09-25 12:00:00','2026-09-25 12:00:00');"],
                       check=True, stdout=subprocess.DEVNULL)
        _, out_a2 = run(["sessions", "--config-dir", home_a, "list"])
        _, out_b2 = run(["sessions", "--config-dir", home_b, "list"])
        check("E4c 写进 homeA 的会话只有 homeA 看得见",
              "p11b-probe" in out_a2 and "p11b-probe" not in out_b2
              and "共 0 个会话" in out_b2,
              "A=%s B=%s" % ("有 probe" if "p11b-probe" in out_a2 else "无",
                             flat(out_b2)[-60:]))
        names_mid, digests_mid, bak_mid = snapshot_profile()
        check("E4d 跑完 sessions 后真实 state.db 没被动",
              digests_mid.get("state.db") == base_digests.get("state.db"),
              "%s -> %s" % (base_digests.get("state.db", "")[:8],
                            digests_mid.get("state.db", "")[:8]))

        # ---- E5 真 pty 降级模式 REPL：终端设置能恢复，且备份不碰共享的 .stty.bak ----
        repl_dir = os.path.join(tmp, "repl")
        write_stub_config(repl_dir)
        io_tmp = os.path.join(tmp, "iotmp")
        os.makedirs(io_tmp, exist_ok=True)
        out_pty, stty_before, stty_after, bak_seen, leftovers = pty_repl(repl_dir, io_tmp)
        check("E5a pty REPL 跑通并退出", "再见" in out_pty or "bye" in out_pty.lower()
              or "z-bot" in out_pty, "尾部: %s" % flat(out_pty)[-120:])
        check("E5b 终端设置被恢复（stty -g 前后一致）",
              stty_before is not None and stty_before == stty_after,
              "before=%s… after=%s…" % (str(stty_before)[:28], str(stty_after)[:28]))
        check("E5c 备份落在本进程 tmpdir（跑的时候抓到过 zbot-stty-*）", bak_seen,
              "守望线程抓到=%s" % bak_seen)
        check("E5d 用完即删：iotmp 里没有 zbot-stty-* 残留", not leftovers, str(leftovers[:3]))
        _, _, bak_after = snapshot_profile()
        check("E5e 真实 ~/.zbot/.stty.bak 的 mtime 没动",
              bak_after == base_bak_mtime, "%s -> %s" % (base_bak_mtime, bak_after))

        # ---- E6 机械回归：主源码里除单一解析点外不许再有 user.home ----
        p = subprocess.run(["grep", "-rn", "user.home",
                            "z-bot-core/src/main/java"],
                           cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        hits = [l for l in p.stdout.decode("utf-8", "replace").split("\n")
                if l.strip() and "config/BotConfig.java" not in l]
        check("E6 主源码里 user.home 只剩 BotConfig 一处解析点",
              not hits, "%d 处: %s" % (len(hits), hits[:2]))

        # ---- E7 真实 profile 全程不变 ----
        names_end, digests_end, _ = snapshot_profile()
        check("E7a ~/.zbot 项数 %d → %d" % (len(base_names), len(names_end)),
              names_end == base_names, str(sorted(set(names_end) ^ set(base_names))))
        check("E7b config.properties md5 未变",
              digests_end.get("config.properties") == base_digests.get("config.properties"),
              "%s -> %s" % (base_digests.get("config.properties", "")[:8],
                            digests_end.get("config.properties", "")[:8]))
        check("E7c state.db md5 未变",
              digests_end.get("state.db") == base_digests.get("state.db"),
              "%s -> %s" % (base_digests.get("state.db", "")[:8],
                            digests_end.get("state.db", "")[:8]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print("\n== E2E %d/%d ==" % (passed, len(RESULTS)))
    for name, ok, detail in RESULTS:
        if not ok:
            print("  FAIL %s | %s" % (name, detail))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
