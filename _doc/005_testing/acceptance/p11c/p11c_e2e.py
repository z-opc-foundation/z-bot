#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P11c（通道监听收口）验收③：真进程 E2E。

单测能证"socket 绑在哪"，证不了这两件：
  * **同机两个进程抢同一端口** —— 变异检验只证明测试会红；真进程才知道邻居占住
    127.0.0.1:P 时，serve 到底是报错退出，还是"启动成功但没人应答"；
  * **网卡暴露面** —— 认 lsof 的内核事实，不认代码里的意图。

因此本脚本所有监听判定都**按 pid 过滤**（`listening()` 带 pid）：否则邻居自己占着
同一个口，会把"z-bot 起来了"读成假阳性 —— 那正是这一批要抓的现象本身。

E4 是这条修复的必要性证据：邻居占住回环口时，`--host 0.0.0.0` 那侧照样起得来（共存），
但打到 127.0.0.1:P 的请求被邻居抢答 —— 那就是修好之前 serve 的缺省行为。

红线：全程 --config-dir 指向临时目录，真 key 不进任何临时文件（config 一律 stub-key-not-real）；
收尾核对 ~/.zbot 项数与 config.properties / state.db 两个 md5 一字未动。

复算:
  mvn -o -pl z-bot-core package -DskipTests
  python3 _doc/005_testing/acceptance/p11c/p11c_e2e.py
"""
import hashlib
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")

RESULTS = []


def check(name, ok, detail):
    RESULTS.append((name, bool(ok), detail))
    print("%-4s %-52s %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)
    return bool(ok)


def flat(out):
    keep = [l for l in out.split("\n")
            if l.strip() and not l.startswith("WARNING:")
            and "enable-native-access" not in l and "Restricted methods" not in l]
    return " ⏎ ".join(keep)[:300]


def write_stub_config(dirpath):
    os.makedirs(dirpath, exist_ok=True)
    with open(os.path.join(dirpath, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("provider=minimax\n"
                 "minimax.type=openai\n"
                 "minimax.base.url=http://127.0.0.1:9/v1\n"
                 "minimax.api.key=stub-key-not-real\n"
                 "minimax.model=stub-model\n")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def listening(port):
    """[(pid, 监听地址)] —— lsof 的内核事实；带 pid 才不会把邻居的监听算成 z-bot 的。"""
    proc = subprocess.run(["lsof", "-nP", "-iTCP:%d" % port, "-sTCP:LISTEN"],
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    found = []
    for line in proc.stdout.decode("utf-8", "replace").split("\n")[1:]:
        cols = line.split()
        if len(cols) < 3:
            continue
        try:
            pid = int(cols[1])
        except ValueError:
            continue
        for token in cols:
            if token.endswith(":%d" % port):
                found.append((pid, token))
                break
    return found


def get(port, path, timeout=5):
    try:
        with urllib.request.urlopen("http://127.0.0.1:%d%s" % (port, path),
                                    timeout=timeout) as resp:
            return resp.getcode(), resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, e)


class Serve(object):
    """起一次真 z-bot 进程；就绪判定只认**本进程 pid** 的 LISTEN，进程先退即判失败。"""

    def __init__(self, args, workdir):
        self.args = list(args)
        self.workdir = workdir
        self.proc = None

    def start(self, port, wait=25.0):
        env = dict(os.environ)
        env.pop("ZBOT_HOME", None)
        self.proc = subprocess.Popen(["java", "-jar", JAR] + self.args
                                     + ["--config-dir", self.workdir],
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     env=env)
        end = time.time() + wait
        while time.time() < end:
            if self.proc.poll() is not None:
                return False
            if self.mine(port):
                return True
            time.sleep(0.1)
        return False

    def mine(self, port):
        pid = self.proc.pid if self.proc else None
        return [a for p, a in listening(port) if p == pid]

    def finish(self):
        if self.proc is None:
            return (-1, "")
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        return (self.proc.returncode, self.proc.stdout.read().decode("utf-8", "replace"))


class Neighbor(object):
    """占住 127.0.0.1:P 并在应答里盖章，用来认出请求到底被谁答的。"""

    MARK = "FOREIGN-NEIGHBOR-OWNED-THIS-PORT"

    def __init__(self, port):
        self.port = port
        self.sock = None
        self.hits = 0

    def __enter__(self):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", self.port))
        s.listen(16)
        self.sock = s
        threading.Thread(target=self._serve).start()
        return self

    def __exit__(self, *exc):
        try:
            self.sock.close()
        except Exception:
            pass

    def _serve(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except Exception:
                return
            try:
                conn.recv(4096)
                body = self.MARK.encode("utf-8")
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                             b"Content-Length: " + str(len(body)).encode("utf-8")
                             + b"\r\nConnection: close\r\n\r\n" + body)
                self.hits += 1
            except Exception:
                pass
            finally:
                try:
                    conn.close()
                except Exception:
                    pass


def snapshot_profile():
    names = sorted(os.listdir(REAL_HOME)) if os.path.isdir(REAL_HOME) else []
    digests = {}
    for n in ("config.properties", "state.db"):
        path = os.path.join(REAL_HOME, n)
        if os.path.isfile(path):
            with open(path, "rb") as fh:
                digests[n] = hashlib.md5(fh.read()).hexdigest()
    return names, digests


def main():
    if not os.path.isfile(JAR):
        print("FATAL: 找不到 %s，先 mvn -o -pl z-bot-core package -DskipTests" % JAR)
        return 2
    home_before, digests_before = snapshot_profile()
    workdir = tempfile.mkdtemp(prefix="zbot-p11c-")
    write_stub_config(workdir)
    print("临时 profile: %s" % workdir)

    # ---- E1 缺省 serve：只绑回环，且真能应答 ----
    p1 = free_port()
    s = Serve(["serve", "--port", str(p1)], workdir)
    up = s.start(p1)
    addrs = s.mine(p1)
    code, body = get(p1, "/bot/status")
    rc, out = s.finish()
    check("E1a serve 缺省起得来", up, "port=%d rc=%s" % (p1, rc))
    loop_only = bool(addrs) and all(a.startswith("127.0.0.1:") for a in addrs)
    check("E1b lsof(本 pid): 缺省只监听回环", loop_only, str(addrs))
    check("E1c 回环上真应答 200", code == 200, "code=%s body=%s" % (code, body[:50]))
    check("E1d 横幅印的是实际回环地址", "http://127.0.0.1:%d" % p1 in out, flat(out)[:150])
    check("E1e 缺省不打 LAN 警告", "同一网络内的其他机器" not in out, flat(out)[:150])

    # ---- E2 显式 --host 0.0.0.0 才通配，且如实报告 ----
    p2 = free_port()
    s = Serve(["serve", "--port", str(p2), "--host", "0.0.0.0"], workdir)
    up = s.start(p2)
    addrs = s.mine(p2)
    code, _ = get(p2, "/bot/status")
    rc, out = s.finish()
    check("E2a 显式 opt-in 起得来且可应答", up and code == 200, "rc=%s code=%s" % (rc, code))
    check("E2b lsof(本 pid): 确实摊开通配", any(a.startswith("*:") for a in addrs), str(addrs))
    check("E2c 横幅如实写 0.0.0.0", "http://0.0.0.0:%d" % p2 in out, flat(out)[:150])
    check("E2d 打了 LAN 警告", "同一网络内的其他机器" in out, flat(out)[:220])

    p3 = free_port()
    with Neighbor(p3):
        # ---- E3 邻居占住回环口：缺省 serve 必须报错退出 ----
        s = Serve(["serve", "--port", str(p3)], workdir)
        up = s.start(p3, wait=20.0)
        rc, out = s.finish()
        check("E3a 缺省 serve 撞邻居 ⇒ 退出非 0", (not up) and rc != 0,
              "rc=%s 就绪=%s" % (rc, up))
        check("E3b 报错说清是端口被占", "Address already in use" in out, flat(out)[:200])
        check("E3c 不得先报「已启动」", "已启动" not in out, flat(out)[:200])

        # ---- E4 必要性证据：通配那侧静默共存并被抢答 ----
        s = Serve(["serve", "--port", str(p3), "--host", "0.0.0.0"], workdir)
        up = s.start(p3, wait=20.0)
        addrs = s.mine(p3)
        code, body = get(p3, "/bot/status", timeout=6)
        rc, out = s.finish()
        check("E4a 通配 opt-in 与邻居共存成功(旧缺省处境)", up and any(a.startswith("*:") for a in addrs),
              "addrs=%s rc=%s" % (addrs, rc))
        check("E4b 请求被邻居抢答而非 z-bot", Neighbor.MARK in body,
              "code=%s body=%s" % (code, body[:40]))

    # ---- E5/E6 gateway 两个通道同受 --host 管 ----
    g1, g2 = free_port(), free_port()
    s = Serve(["gateway", "--port", str(g1), "--webhook-port", str(g2)], workdir)
    up = s.start(g1, wait=30.0)
    a1, a2 = s.mine(g1), s.mine(g2)
    code, _ = get(g2, "/webhook/in", timeout=5)
    rc, out = s.finish()
    check("E5a gateway 缺省起两通道", up and bool(a1) and bool(a2), "rc=%s" % rc)
    check("E5b 两通道都只绑回环",
          bool(a1) and bool(a2) and all(x.startswith("127.0.0.1:") for x in a1 + a2),
          "%s / %s" % (a1, a2))
    check("E5c webhook 口有应答(非 404/无响应)", code not in (None, 404), "code=%s" % code)

    g3, g4 = free_port(), free_port()
    s = Serve(["gateway", "--port", str(g3), "--webhook-port", str(g4),
               "--host", "0.0.0.0"], workdir)
    up = s.start(g3, wait=30.0)
    a3, a4 = s.mine(g3), s.mine(g4)
    rc, out = s.finish()
    check("E6 一个 --host 同时管两通道",
          up and any(x.startswith("*:") for x in a3) and any(x.startswith("*:") for x in a4),
          "%s / %s" % (a3, a4))

    # ---- E7 真 key 不落临时目录 ----
    real_key = ""
    cfg_path = os.path.join(REAL_HOME, "config.properties")
    if os.path.isfile(cfg_path):
        with open(cfg_path, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("minimax.api.key="):
                    real_key = line.split("=", 1)[1].strip()
    if real_key:
        leaked = []
        for root, _dirs, files in os.walk(workdir):
            for n in files:
                path = os.path.join(root, n)
                try:
                    with open(path, encoding="utf-8", errors="ignore") as fh:
                        if real_key in fh.read():
                            leaked.append(path)
                except Exception:
                    pass
        check("E7 真 key 没进临时 profile(key 长度 %d)" % len(real_key),
              not leaked, "%d 处: %s" % (len(leaked), [os.path.basename(x) for x in leaked[:3]]))
    else:
        check("E7 真 key 没进临时 profile", False, "读不到真 key ⇒ 判不了，记 UNKNOWN")

    # ---- E8 ~/.zbot 一字未动 ----
    home_after, digests_after = snapshot_profile()
    check("E8a ~/.zbot 项数 %d → %d" % (len(home_before), len(home_after)),
          home_before == home_after,
          "差异: %s" % sorted(set(home_after) ^ set(home_before)))
    for n in ("config.properties", "state.db"):
        b, a = digests_before.get(n), digests_after.get(n)
        check("E8b %s md5 未变" % n, b == a, "%s -> %s" % (str(b)[:8], str(a)[:8]))

    shutil.rmtree(workdir, ignore_errors=True)
    failed = [n for n, ok, _ in RESULTS if not ok]
    print("\n== E2E %d/%d%s ==" % (len(RESULTS) - len(failed), len(RESULTS),
                                   "" if not failed else "  FAIL: %s" % failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
