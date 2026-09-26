#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P22 杠③：真 jar / 真子进程 / 真 HTTP 的端到端（每跑独立现场目录）。

覆盖（工单点名）：
  * local  —— 真起 /bin/sh、超时端整棵树（`ps` 现场取证）、有界输出、stdin 另线程、越界拒绝
  * docker —— 真发 HTTP 到 **Python 起的假 dockerd**（127.0.0.1 + bind(0)），逐字段断言请求序列 /
              Cmd 是数组 / 限额与安全基线字段带过去 / 超时也删容器 / 台账回收
  * ssh    —— 假 ssh 可执行文件（就在现场目录里），验 argv 落地与"没有静默降级"
  * wiring —— BuiltinTools.exec 真的经过被装上的后端
  * legacy —— 把老 `bash()` 的"到 maxLines 就不读了"原样复现，证明它堵死（这是本轮查出的产品缺陷）

红线：只连 127.0.0.1；数据根 = `~/.cache/zbot-p22-lead/e2e/<label>-<ts>/`，
      绝不碰 `~/.zbot`（收尾复扫 md5）；每个子进程都有硬超时 + 进程树兜底；不写 `/tmp`。
"""
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime

REPO = subprocess.check_output(["git", "rev-parse", "--show-toplevel"]).decode().strip()
JAR_DIR = os.path.join(REPO, "z-bot-core", "target")
TEST_CLASSES = os.path.join(JAR_DIR, "test-classes")
ROOT = os.path.expanduser("~/.cache/zbot-p22-lead/e2e")
DRIVER = "com.zifang.z.bot.tool.env.P22E2eDriver"
REAL_HOME = os.path.expanduser("~/.zbot")
TS = datetime.now().strftime("%Y%m%d-%H%M%S")
LEDGER = os.path.join(ROOT, "ledger-%s.jsonl" % TS)

KEY_STUB = "stub-key-not-real"


def log(msg):
    print("[%s] %s" % (datetime.now().strftime("%H:%M:%S"), msg))
    sys.stdout.flush()


def zbot_snapshot():
    out = {}
    for name in sorted(os.listdir(REAL_HOME)):
        p = os.path.join(REAL_HOME, name)
        if os.path.isfile(p):
            with open(p, "rb") as f:
                out[name] = hashlib.md5(f.read()).hexdigest()[:8] + ":" + str(os.path.getmtime(p))
        else:
            out[name] = "dir:" + str(os.path.getmtime(p))
    return out


# ---------------------------------------------------------------- 假 dockerd

class FakeDockerd(threading.Thread):
    """最小 Engine API 假服务：只 127.0.0.1、只这一个线程、每响应必带 status。"""

    def __init__(self, orphan_id):
        threading.Thread.__init__(self)
        self.daemon = True
        self.orphan_id = orphan_id
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(16)
        self.port = self.sock.getsockname()[1]
        self.requests = []
        self.json_polls = {}
        self.stop = False
        self.created = []
        self.deleted = []
        self.archives = {}

    def run(self):
        while not self.stop:
            try:
                c, _ = self.sock.accept()
            except OSError:
                return
            try:
                self.serve(c)
            except Exception as e:  # 现场不炸主线程
                log("fake dockerd 处理失败：%r" % (e,))
            finally:
                try:
                    c.close()
                except OSError:
                    pass

    def serve(self, c):
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = c.recv(4096)
            if not chunk:
                return
            buf += chunk
        head, _, rest = buf.partition(b"\r\n\r\n")
        lines = head.split(b"\r\n")
        method, path, _version = lines[0].decode().split(" ", 2)
        headers = {}
        for l in lines[1:]:
            k, _, v = l.decode().partition(": ")
            headers[k.lower()] = v
        cl = int(headers.get("content-length", "0") or 0)
        while len(rest) < cl:
            rest += c.recv(4096)
        self.requests.append(dict(method=method, path=path, body=rest.decode("utf-8", "replace")))
        status, ctype, body = self.route(method, path, rest)
        c.sendall(("HTTP/1.1 %d X\r\nContent-Type: %s\r\nContent-Length: %d\r\n"
                   "Connection: close\r\n\r\n" % (status, ctype, len(body))).encode() + body)

    def route(self, method, path, body):
        if path.startswith("/_ping"):
            return 200, "text/plain", b"OK"
        if path.startswith("/version"):
            return 200, "application/json", b'{"ApiVersion":"1.24","Os":"linux"}'
        if "/containers/create" in path:
            n = len(self.created) + 1
            cid = "e2econtainer%05d" % n
            self.created.append((cid, json.loads(body)))
            return 201, "application/json", ('{"Id":"%s","Warnings":[]}' % cid).encode()
        m = re.search(r"/containers/([^/]+)/json", path)
        if m:
            cid = m.group(1)
            self.json_polls[cid] = self.json_polls.get(cid, 0) + 1
            # sleep 999 那一支（第 2 个容器）永远 running ⇒ 客户端超时
            still_running = cid.endswith("2") and self.json_polls[cid] <= 200
            state = '{"Running":%s,"ExitCode":0,"Dead":false}' % ("true" if still_running else "false")
            return 200, "application/json", ('{"Id":"%s","State":%s}' % (cid, state)).encode()
        m = re.search(r"/containers/([^/]+)/(start|stop)", path)
        if m:
            return 204, "application/json", b""
        m = re.search(r"/containers/([^/]+)/logs", path)
        if m:
            frame = lambda t, s: bytes([t, 0, 0, 0, (len(s) >> 24) & 255,
                                        (len(s) >> 16) & 255, (len(s) >> 8) & 255, len(s) & 255]) + s
            return 200, "application/octet-stream", \
                frame(1, b"hello-from-p22-e2e\n") + frame(2, b"to-stderr\n")
        m = re.search(r"/containers/([^/]+)/archive", path)
        if m:
            cid = m.group(1)
            if method == "PUT":
                self.archives[cid] = body
                return 200, "application/json", b""
            return 200, "application/x-tar", self.archives.get(cid, tar_one("payload", "没有内容\n"))
        if "/containers/json" in path:
            ids = [self.orphan_id] + [c for c, _ in self.created if c not in self.deleted]
            arr = ",".join('{"Id":"%s","State":"exited"}' % i for i in ids)
            return 200, "application/json", ("[%s]" % arr).encode()
        m = re.search(r"/containers/([^/]+)", path)
        if m and method == "DELETE":
            self.deleted.append(m.group(1))
            return 204, "application/json", b""
        return 404, "application/json", b'{"message":"page not found"}'

    def shutdown(self):
        self.stop = True
        try:
            socket.socket().connect(("127.0.0.1", self.port))
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


def tar_one(name, content):
    data = content.encode()
    h = bytearray(b"\0" * 512)
    h[0:len(name)] = name.encode()
    h[100:107] = b"0000644"
    h[108:115] = b"0000000"
    h[116:123] = b"0000000"
    h[124:135] = ("%011o" % len(data)).encode()
    h[136:147] = b"00000000000"
    h[148:156] = b"        "
    h[156] = ord("0")
    h[257:262] = b"ustar"
    h[263:265] = b"00"
    chk = sum(h)
    h[148:156] = ("%07o" % chk).encode() + b"\0"
    pad = (512 - len(data) % 512) % 512
    return bytes(h) + data + b"\0" * pad + b"\0" * 1024


# ---------------------------------------------------------------- java 侧

def build():
    if not os.path.isdir(TEST_CLASSES):
        log("test-classes 不在，先 mvn -o package -DskipTests -pl z-bot-core")
    jars = [f for f in os.listdir(JAR_DIR) if f.endswith(".jar") and "original" not in f]
    if not jars:
        subprocess.check_call(["mvn", "-o", "-q", "package", "-DskipTests", "-pl", "z-bot-core"],
                              cwd=REPO)
        jars = [f for f in os.listdir(JAR_DIR) if f.endswith(".jar") and "original" not in f]
    jar = os.path.join(JAR_DIR, sorted(jars)[0])
    with open(jar, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()[:8]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO).decode().strip()
    return jar, sha, head


def run_java(jar, mode, home, extra=None, timeout=90):
    cp = jar + os.pathsep + TEST_CLASSES   # 故意不放 target/classes：生产类只能来自 jar
    cmd = ["java", "-cp", cp, "-Dzbot.home=" + home, DRIVER, mode, home] + (extra or [])
    t0 = time.time()
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         cwd=home, preexec_fn=os.setsid)
    try:
        out, _ = p.communicate(timeout=timeout)
        rc = p.returncode
    except subprocess.TimeoutExpired:
        try:
            subprocess.run(["pkill", "-TERM", "-P", str(p.pid)], timeout=5)
            os.killpg(os.getpgid(p.pid), 9)
        except Exception:
            pass
        out, _ = p.communicate(timeout=20)
        rc = 124
    return dict(rc=rc, secs=round(time.time() - t0, 2),
                lines=[l for l in out.decode("utf-8", "replace").split("\n") if l.strip()])


def markers(res):
    d = {}
    for l in res["lines"]:
        k, _, v = l.partition("=")
        if _ and not l.startswith("["):
            d.setdefault(k.strip(), v.strip())
    return d


def check(name, cond, detail=""):
    return dict(name=name, ok=bool(cond), detail=str(detail)[:220])


# ---------------------------------------------------------------- 五种模式

def write_cfg(home, kv):
    os.makedirs(home, exist_ok=True)
    lines = ["minimax.api.key=%s" % KEY_STUB]
    for k, v in kv.items():
        lines.append("%s=%s" % (k, v))
    with open(os.path.join(home, "config.properties"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def scenario_local(jar):
    home = os.path.join(ROOT, "local-%s" % TS)
    write_cfg(home, {"exec.env.backend": "local", "exec.env.default.timeout.ms": "4000"})
    r = run_java(jar, "local", home)
    mk = markers(r)
    return dict(mode="local", rc=r["rc"], secs=r["secs"], markers=mk, checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("后端是 local", mk.get("BACKEND") == "local", mk.get("BACKEND")),
        check("超时把整棵树端干净（ps 现场取证的存活后代=0）",
              mk.get("SURVIVOR_COUNT") in ("0", None) and "SURVIVOR_COUNT=0" in "\n".join(r["lines"]),
              mk.get("SURVIVOR_COUNT")),
        check("killedDescendants 记了数（>0）", int(mk.get("KILLED", "0") or 0) > 0, mk.get("KILLED")),
        check("输出按字节上界夹住", mk.get("STDOUT_CAPPED") == "1", mk.get("STDOUT_BYTES")),
        check("全量字节数照记（不因截断而少报）",
              int(mk.get("STDOUT_TOTAL", "0") or 0) > int(mk.get("STDOUT_BYTES", "0") or 0),
              (mk.get("STDOUT_BYTES"), mk.get("STDOUT_TOTAL"))),
        check("到上限之后仍把管道读干净（没堵死）", mk.get("DRAINED_WITHOUT_HANG") == "1", mk.get("DRAIN_SECS")),
        check("stdin 走独立线程（wc -c 对得上）", mk.get("STDIN_SEEN") == "6", mk.get("STDIN_SEEN")),
        check("越界路径被拒（SANDBOX_ESCAPE）", "SANDBOX_ESCAPE" in str(mk.get("ESCAPE")), mk.get("ESCAPE")),
        check("现场目录里才有产物，~/.zbot 没被写",
              os.path.isdir(os.path.join(home, "sandbox")) or "SANDBOX_ROOT" not in mk
              or mk["SANDBOX_ROOT"].startswith(home), mk.get("SANDBOX_ROOT")),
    ])


def scenario_docker(jar):
    home = os.path.join(ROOT, "docker-%s" % TS)
    orphan = "orphan-from-dead-process-%s" % TS[-6:]
    fake = FakeDockerd(orphan)
    fake.start()
    ledger = os.path.join(home, "tool-env", "docker-ledger.properties")
    os.makedirs(os.path.dirname(ledger), exist_ok=True)
    with open(ledger, "w", encoding="utf-8") as f:
        f.write("%s=owner=%d,run=stale\n" % (orphan, 999999))
    write_cfg(home, {
        "exec.env.backend": "docker",
        "exec.env.docker.host": "tcp://127.0.0.1:%d" % fake.port,
        "exec.env.docker.image": "busybox:latest",
        "exec.env.docker.memory.mb": "256",
        "exec.env.docker.cpus": "0.5",
        "exec.env.docker.pids.limit": "64",
        "exec.env.docker.ledger": ledger,
    })
    r = run_java(jar, "docker", home)
    mk = markers(r)
    seq = ["%s %s" % (q["method"], q["path"].split("?")[0]) for q in fake.requests]
    create = [q for q in fake.requests if "/containers/create" in q["path"]]
    body = json.loads(create[0]["body"]) if create else {}
    hc = body.get("HostConfig", {})
    seq_paths = " | ".join(seq)
    fake.shutdown()
    fake.join(timeout=5)
    left = os.path.exists(ledger) and open(ledger, encoding="utf-8").read()
    return dict(mode="docker", rc=r["rc"], secs=r["secs"], markers=mk,
                request_seq=seq, request_count=len(fake.requests),
                create_body=body, checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("先 _ping（且判了 status/不是 HTML）", seq[0] == "GET /_ping", seq[:2]),
        check("create→start→json→logs→DELETE 全序列",
              all(x in seq for x in ("POST /containers/create", "POST /containers/%s/start" % fake.created[0][0] if fake.created else "x",
                                     "DELETE /containers/%s" % fake.created[0][0] if fake.created else "y")),
              seq_paths[:400]),
        check("Cmd 是数组不是字符串（无 shell 拼接）",
              isinstance(body.get("Cmd"), list) and body["Cmd"][0] == "/bin/echo", body.get("Cmd")),
        check("限额三项带过去（memory/cpus/pids）",
              hc.get("Memory") == 256 * 1024 * 1024 and hc.get("NanoCpus") == 500000000
              and hc.get("PidsLimit") == 64, hc),
        check("安全基线：断网 + cap-drop ALL + no-new-privileges",
              hc.get("NetworkMode") == "none" and hc.get("CapDrop") == ["ALL"]
              and hc.get("SecurityOpt") == ["no-new-privileges:true"], hc),
        check("label 带过去（托管标记）",
              body.get("Labels", {}).get("z-bot.p22.managed") == "true", body.get("Labels")),
        check("stdout/stderr 分帧解出来", mk.get("DOCKER_STDOUT") == "hello-from-p22-e2e"
              and mk.get("DOCKER_STDERR") == "to-stderr", (mk.get("DOCKER_STDOUT"), mk.get("DOCKER_STDERR"))),
        check("超时那一路也删了容器",
              fake.created and any("sleep" in str(b.get("Cmd")) for _, b in fake.created)
              and fake.created[1][0] in fake.deleted if len(fake.created) > 1 else False,
              (fake.created and [c[0] for c in fake.created], fake.deleted)),
        check("孤儿按账本回收", orphan in fake.deleted, fake.deleted),
        check("账本里孤儿那条已划掉", orphan not in (left or ""), (left or "")[:200]),
        check("stdin 明确拒绝而不是换后端", mk.get("DOCKER_STDIN", "").startswith("STDIN_NOT"), mk.get("DOCKER_STDIN")),
    ])


def scenario_ssh(jar):
    home = os.path.join(ROOT, "ssh-%s" % TS)
    bindir = os.path.join(home, "bin")
    os.makedirs(bindir, exist_ok=True)
    fake_ssh = os.path.join(bindir, "ssh")
    marker = os.path.join(home, "ssh-argv.txt")
    pwned = os.path.join(home, "PWNED")
    with open(fake_ssh, "w", encoding="utf-8") as f:
        f.write("#!/bin/sh\n"
                "for a in \"$@\"; do printf '<%%s>\\n' \"$a\"; done > '%s'\n"
                "if [ -e '%s' ]; then echo '远端真的执行了分号后面的东西' >&2; exit 77; fi\n"
                "echo 'ssh-fake-said:hi; not-pwned'\n"
                "exit 0\n" % (marker, pwned))
    os.chmod(fake_ssh, 0o755)
    ident = os.path.join(home, "id_ed25519")
    with open(ident, "w", encoding="utf-8") as f:
        f.write("不是真钥匙，只是让后端认为凭据齐了\n")
    write_cfg(home, {
        "exec.env.backend": "ssh",
        "exec.env.ssh.bin": fake_ssh,
        "exec.env.ssh.host": "127.0.0.1",
        "exec.env.ssh.port": "22",
        "exec.env.ssh.user": "p22e2e",
        "exec.env.ssh.identity": ident,
        "exec.env.ssh.workdir": os.path.join(home, "remote-sandbox"),
    })
    r = run_java(jar, "ssh", home)
    mk = markers(r)
    argv_text = open(marker, encoding="utf-8").read() if os.path.exists(marker) else ""
    return dict(mode="ssh", rc=r["rc"], secs=r["secs"], markers=mk, fake_ssh_argv=argv_text,
                checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("后端选的是 ssh（不是静默 local）", mk.get("BACKEND") == "ssh", mk.get("BACKEND")),
        check("BatchMode=yes（不许交互式问密码）", "-o" in argv_text and "BatchMode=yes" in argv_text, argv_text[:200]),
        check("目标只从配置来（user@host 落在一个 argv 元素里）",
              "<p22e2e@127.0.0.1>" in argv_text, argv_text[:300]),
        check("-p / -i 都是独立 argv 元素", "<-p>" in argv_text and "<-i>" in argv_text, argv_text[:300]),
        check("远端调用只有 1 个元素（没有把命令摊成多词）",
              argv_text.strip().count("\n") <= 8 and "<cd " in mk.get("SSH_REMOTE", ""),
              mk.get("SSH_REMOTE")),
        check("带分号的参数原样落地、没被执行",
              os.path.exists(pwned) is False and "rm -rf" in mk.get("SSH_REMOTE", ""),
              (os.path.exists(pwned), mk.get("SSH_REMOTE"))),
        check("无凭据时显式报 SSH_NO_CREDENTIALS", mk.get("SSH_NO_CRED") == "SSH_NO_CREDENTIALS",
              mk.get("SSH_NO_CRED")),
    ])


def scenario_wiring(jar):
    home = os.path.join(ROOT, "wiring-%s" % TS)
    write_cfg(home, {"exec.env.backend": "local"})
    r = run_java(jar, "wiring", home)
    mk = markers(r)
    return dict(mode="wiring", rc=r["rc"], secs=r["secs"], markers=mk, checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("exec 工具真的经过被装上的后端", mk.get("SPY_CALLS") not in (None, "0"), mk.get("SPY_CALLS")),
        check("后端返回的文本原样回到工具里", "从假后端回来的输出" in str(r["lines"]), mk.get("TOOL_OUT")),
        check("摘掉后端时是显式报错，不是退回 local 跑", "BACKEND_WAS_DOWN" in str(r["lines"]), mk.get("TOOL_ERR")),
    ])


def scenario_legacy(jar):
    home = os.path.join(ROOT, "legacy-%s" % TS)
    write_cfg(home, {"exec.env.backend": "local"})
    r = run_java(jar, "legacy-hang-model", home, timeout=120)
    mk = markers(r)
    return dict(mode="legacy-hang-model", rc=r["rc"], secs=r["secs"], markers=mk, checks=[
        check("java rc=0（模型自己带硬上限，不会把验收器拖死）", r["rc"] == 0, r["lines"][-3:]),
        check("老口径复现出堵死（LEGACY_HANG=1）", mk.get("LEGACY_HANG") == "1",
              {k: v for k, v in mk.items() if k.startswith("LEGACY")}),
        check("新路径同场景不堵（NEW_PATH_HANG=0）", mk.get("NEW_PATH_HANG") == "0", mk.get("NEW_PATH_HANG")),
    ])


def main():
    os.makedirs(ROOT, exist_ok=True)
    jar, sha, head = build()
    log("jar=%s sha256[:8]=%s HEAD=%s" % (os.path.basename(jar), sha, head[:8]))
    t0 = zbot_snapshot()
    scenarios = [scenario_local, scenario_docker, scenario_ssh, scenario_wiring, scenario_legacy]
    results = []
    only = sys.argv[1:] or None
    for fn in scenarios:
        if only and fn.__name__.replace("scenario_", "") not in only:
            continue
        log("=== %s ===" % fn.__name__)
        try:
            res = fn(jar)
        except Exception as e:
            res = dict(mode=fn.__name__, rc=99, error=repr(e), checks=[check("没炸", False, repr(e))])
        res["failed"] = [c["name"] for c in res["checks"] if not c["ok"]]
        log("rc=%s secs=%s 断言 %d 条，红 %d 条%s" % (res["rc"], res.get("secs"), len(res["checks"]),
                                                  len(res["failed"]),
                                                  ("：" + "、".join(res["failed"])) if res["failed"] else ""))
        results.append(res)
    t1 = zbot_snapshot()
    drift = [k for k in set(list(t0) + list(t1)) if t0.get(k) != t1.get(k)]
    tally = dict(runs=len(results), pass_runs=sum(1 for r in results if not r["failed"]),
                 jar_sha=sha, git_head=head, zbot_entries=len(t0), zbot_drift=drift)
    with open(LEDGER, "w", encoding="utf-8") as f:
        f.write(json.dumps(dict(ts=TS, tally=tally, results=results), ensure_ascii=False) + "\n")
    print("=" * 100)
    print(json.dumps(tally, ensure_ascii=False, indent=1))
    for r in results:
        print("--- %s rc=%s secs=%s" % (r["mode"], r["rc"], r.get("secs")))
        for c in r["checks"]:
            print("    %s %-58s %s" % ("OK  " if c["ok"] else "FAIL", c["name"],
                                        "" if c["ok"] else c["detail"]))
        for k in sorted(r["markers"]):
            print("      %s=%s" % (k, r["markers"][k]))
    print("台账：%s" % LEDGER)
    bad = tally["runs"] != tally["pass_runs"] or drift
    print("E2E_RC=%d" % (2 if bad else 0))
    return 2 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
