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
    """只 stat 现场根条目的存在性/mtime，不跟随符号链接、不读内容（红线 1：里面有真 key）。

    p22b 修正：`~/.zbot/workspace` 是一个**断链的符号链接**（指向已不存在的目标），
    旧写法 `os.path.isfile()` 跟随链接 ⇒ False ⇒ 走"目录"分支，再 `os.path.getmtime()`
    直接 FileNotFoundError ⇒ 整个 E2E 一个场景都没跑就炸（实测第一跑）。改成 lstat + 兜底。
    """
    out = {}
    for name in sorted(os.listdir(REAL_HOME)):
        p = os.path.join(REAL_HOME, name)
        try:
            st = os.lstat(p)  # lstat：绝不跟随符号链接，绝不打开文件
        except OSError as e:
            out[name] = "unreadable:%r" % (e,)
            continue
        import stat as _stat
        if _stat.S_ISLNK(st.st_mode):
            out[name] = "link:%d" % st.st_mtime
        elif _stat.S_ISDIR(st.st_mode):
            out[name] = "dir:%d" % st.st_mtime
        else:
            with open(p, "rb") as f:
                out[name] = hashlib.md5(f.read()).hexdigest()[:8] + ":" + str(st.st_mtime)
    return out


# ---------------------------------------------------------------- 假 dockerd

def norm(path):
    """剥掉 docker Engine API 的版本前缀（实测客户端打的是 /v1.44/_ping）。"""
    stripped = re.sub(r"^/v\d+\.\d+", "", path)
    return stripped or path


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
        # p22b 修正①：客户端打的是带版本前缀的 /v1.44/_ping，旧路由只匹配 /_ping ⇒ 全 404，
        #   java 侧当场炸 [DOCKER_BAD_RESPONSE]，docker 场景 12 条断言全红（量具的错，不是产品的）。
        # p22b 修正②：DELETE 的路径是 /containers/<id>?force=true，旧写法让 ([^/]+) 把查询串一起
        #   吃进 container id ⇒ self.deleted 里存的是 "e2econtainer00001?force=true"，
        #   Python 侧的"删了没删"永远对不上号 ⇒ 这里统一剥前缀和查询串。
        path = norm(path.split("?")[0])
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
            # p22b 修正③：旧判据 `cid.endswith("2")` 假设"第二个容器就是 sleep 那支"，实测驱动
            # 在 sleep 之前已经起了 2 个容器（echo + writeFile）⇒ sleep 落在 ...00003，
            # 假 dockerd 立刻回 Running=false，DOCKER_TIMEOUT 实测成 false（不是产品的超时没生效，
            # 是假服务没把"还在跑"这件事演对）。改成按 create 请求里的 Cmd 真判 sleep。
            body_by_id = dict(self.created)
            is_sleep = any("sleep" in str(x) for x in body_by_id.get(cid, {}).get("Cmd", []))
            still_running = is_sleep and self.json_polls[cid] <= 200
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


def count_sleep25():
    """Python 侧独立取证：现场里还有没有 `sleep 25` 活着（驱动 ① 用的就是这两个字）。

    p22b 加这一段的原因（实测，不是猜）：final 三跑的第 3 跑里
    `TREE_KILLED_DESCENDANTS=2 / TREE_EXIT=137 / TREE_SURVIVORS_AFTER=2` ——
    驱动在 killTree 之后**立刻**打了一次 ps，SIGKILL 已发出但 macOS 还没把条目摘掉；
    同一次运行里稍后的 `pgrep 'sleep 25' | wc -l` 又是 0（TREE_PGREP=50/  0）。
    判据要的是"树真的没了"，所以这里给一个**有上限**的 settling 复探（最多 1s），
    复探之后还不干净就是真红 —— 首探读数一并留在 detail 里，不藏。
    """
    first = None
    for i in range(11):
        out = subprocess.run(["/bin/ps", "-axo", "pid,ppid,command"],
                             stdout=subprocess.PIPE).stdout.decode("utf-8", "replace")
        n = sum(1 for line in out.split("\n")
                if "sleep 25" in line and "[s]leep" not in line)
        if first is None:
            first = n
        if n == 0:
            return dict(first=first, settled_in_ms=i * 100, now=0)
        time.sleep(0.1)
    return dict(first=first, settled_in_ms=1000, now=n)


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
    # p22b 修正：以下判词全部对齐 P22E2eDriver 实际打印的 marker 名（旧稿写的是
    # SURVIVOR_COUNT / KILLED / STDOUT_CAPPED / STDOUT_TOTAL / STDOUT_BYTES / DRAINED_WITHOUT_HANG /
    # STDIN_SEEN / SANDBOX_ESCAPE —— 实测驱动一个都不打，7/10 条只是因为读不到键而红，不是产品红）
    def num(key):
        try:
            return int(mk.get(key, "") or 0)
        except ValueError:
            return -1
    ps_probe = count_sleep25()
    return dict(mode="local", rc=r["rc"], secs=r["secs"], markers=mk, ps_probe=ps_probe, checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("驱动跑到收尾（LOCAL_RC=0）", mk.get("LOCAL_RC") == "0", mk.get("LOCAL_RC")),
        check("后端是 local", mk.get("BACKEND") == "local", mk.get("BACKEND")),
        check("超时确实发生", mk.get("TREE_TIMEOUT") == "true", mk.get("TREE_TIMEOUT")),
        check("超时把整棵树端干净（独立 ps 复探，1s 上限内归零）",
              ps_probe["now"] == 0 and int(mk.get("TREE_KILLED_DESCENDANTS", "0") or 0) >= 2,
              "驱动首探=%s 复探=%s settling=%sms pgrep=%s killed=%s"
              % (mk.get("TREE_SURVIVORS_AFTER"), ps_probe["now"], ps_probe["settled_in_ms"],
                 mk.get("TREE_PGREP"), mk.get("TREE_KILLED_DESCENDANTS"))),
        check("killedDescendants 记了数（>0）", num("TREE_KILLED_DESCENDANTS") > 0,
              mk.get("TREE_KILLED_DESCENDANTS")),
        check("收尾有上限（TREE_COST_MS<8000，实测 %s）" % mk.get("TREE_COST_MS"),
              0 <= num("TREE_COST_MS") < 8000, mk.get("TREE_COST_MS")),
        check("输出按字节上界夹住（BOUND_RENDERED<=4096 且标了 truncated）",
              num("BOUND_RENDERED") > 0 and num("BOUND_RENDERED") <= 4096
              and mk.get("BOUND_TRUNCATED") == "true",
              (mk.get("BOUND_RENDERED"), mk.get("BOUND_TRUNCATED"))),
        check("全量字节数照记（5MB 输入不许因截断少报）",
              num("BOUND_TOTAL") == 5 * 1024 * 1024 and num("BOUND_TOTAL") > num("BOUND_RENDERED"),
              (mk.get("BOUND_TOTAL"), mk.get("BOUND_RENDERED"))),
        check("到上限之后仍把管道读干净（60000 行回 EOF、没堵死）",
              mk.get("DRAIN_EXIT") == "0" and num("DRAIN_TOTAL") > 400000
              and num("DRAIN_COST_MS") < 20000,
              (mk.get("DRAIN_EXIT"), mk.get("DRAIN_TOTAL"), mk.get("DRAIN_COST_MS"))),
        check("stdin 走独立线程（wc -c 对得上 5242880）", mk.get("STDIN_COUNT") == "5242880",
              mk.get("STDIN_COUNT")),
        check("越界路径被拒（ESCAPE=REFUSED:）", str(mk.get("ESCAPE", "")).startswith("REFUSED"),
              mk.get("ESCAPE")),
        check("现场目录在 cache 根下、~/.zbot 没被写",
              home.startswith(os.path.expanduser("~/.cache/zbot-p22-lead/e2e"))
              and not os.path.exists(os.path.join(home, "escaped.txt")), home),
    ])


def scenario_docker(jar):
    home = os.path.join(ROOT, "docker-%s" % TS)
    orphan = "orphan-from-dead-process-%s" % TS[-6:]
    fake = FakeDockerd(orphan)
    fake.start()
    ledger = os.path.join(home, "tool-env", "docker-ledger.properties")
    os.makedirs(os.path.dirname(ledger), exist_ok=True)
    with open(ledger, "w", encoding="utf-8") as f:
        # p22b 修正④：产品认的键是 DockerExecEnvironment.LEDGER_PREFIX("container.") + id，
        # value 形如 runToken@pid（:647）；旧稿写的 "<id>=owner=999999,run=stale" 没有前缀 ⇒
        # ledgerIds() 根本看不见它，孤儿只走 label 那一路被捞，"账本兜底"这条 E2E 没量到，
        # 而且删完之后那行还留在盘上（旧判据据此报 FAIL，是量具种错，不是产品没划掉）。
        f.write("container.%s=stale-run-token@999999\n" % orphan)
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
    seq = ["%s %s" % (q["method"], norm(q["path"].split("?")[0])) for q in fake.requests]
    create = [q for q in fake.requests if "/containers/create" in q["path"]]
    body = json.loads(create[0]["body"]) if create else {}
    hc = body.get("HostConfig", {})
    seq_paths = " | ".join(seq)
    created_ids = [c[0] for c in fake.created]
    # p22b：上一版这里写成 [c[0] for c, b in fake.created ...] —— c 已经是 id 字符串，
    # 于是 sleep 那支被算成 ['e','e']，"删了没删"永远对不上（量具自己的下标错，实测 docker 场景连红 1 条）。
    sleep_ids = [c for c, b in fake.created if any("sleep" in str(x) for x in b.get("Cmd", []))]
    fake.shutdown()
    fake.join(timeout=5)
    left = os.path.exists(ledger) and open(ledger, encoding="utf-8").read()
    return dict(mode="docker", rc=r["rc"], secs=r["secs"], markers=mk,
                request_seq=seq, request_count=len(fake.requests),
                create_body=body, checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("驱动跑到收尾（DOCKER_RC=0）", mk.get("DOCKER_RC") == "0", mk.get("DOCKER_RC")),
        check("选的是 docker 后端", mk.get("BACKEND") == "docker", mk.get("BACKEND")),
        check("先 _ping（且判了 status/不是 HTML）", seq and seq[0] == "GET /_ping", seq[:2]),
        check("create→start→json→logs→DELETE 全序列",
              all(x in seq for x in ("POST /containers/create",
                                     "POST /containers/%s/start" % (created_ids[0] if created_ids else "x"),
                                     "GET /containers/%s/json" % (created_ids[0] if created_ids else "x"),
                                     "GET /containers/%s/logs" % (created_ids[0] if created_ids else "x"),
                                     "DELETE /containers/%s" % (created_ids[0] if created_ids else "y"))),
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
        check("AutoRemove=false（不自清才需要回收）", body.get("HostConfig", {}).get("AutoRemove") is False
              or body.get("AutoRemove") is False, body.get("HostConfig", {}).get("AutoRemove")),
        check("stdout/stderr 分帧解出来", mk.get("DOCKER_STDOUT") == "hello-from-p22-e2e"
              and mk.get("DOCKER_STDERR") == "to-stderr", (mk.get("DOCKER_STDOUT"), mk.get("DOCKER_STDERR"))),
        check("超时那一路确实超时了", mk.get("DOCKER_TIMEOUT") == "true", mk.get("DOCKER_TIMEOUT")),
        check("超时那一路也删了容器",
              bool(sleep_ids) and all(i in fake.deleted for i in sleep_ids),
              (sleep_ids, fake.deleted)),
        check("孤儿按账本回收", orphan in fake.deleted, fake.deleted),
        check("账本里孤儿那条已划掉", orphan not in (left or ""), (left or "")[:200]),
        check("stdin 明确拒绝而不是换后端", mk.get("DOCKER_STDIN") == "CAPABILITY_UNSUPPORTED",
              mk.get("DOCKER_STDIN")),
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
        # p22b 修正：产品读的键是 ExecEnvConfig.KEY_SSH_IDENTITY = "exec.env.ssh.identity.file"
        # （实测 ExecEnvConfig.java:48），旧稿写的 "exec.env.ssh.identity" 没人认 ⇒
        # SshExecEnvironment.<init> 当场抛 SSH_NO_CREDENTIALS，8 条断言全红是量具的错不是产品的。
        "exec.env.ssh.identity.file": ident,
        "exec.env.ssh.workdir": os.path.join(home, "remote-sandbox"),
    })
    r = run_java(jar, "ssh", home)
    mk = markers(r)
    argv_text = open(marker, encoding="utf-8").read() if os.path.exists(marker) else ""
    argv_line_count = len([x for x in argv_text.split("\n") if x.strip()])
    return dict(mode="ssh", rc=r["rc"], secs=r["secs"], markers=mk, fake_ssh_argv=argv_text,
                checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("驱动跑到收尾（SSH_RC=0）", mk.get("SSH_RC") == "0", mk.get("SSH_RC")),
        check("后端选的是 ssh（不是静默 local）", mk.get("BACKEND") == "ssh"
              and mk.get("SSH_BACKEND_FROM_CONFIG") == "ssh",
              (mk.get("BACKEND"), mk.get("SSH_BACKEND_FROM_CONFIG"))),
        check("假 ssh 真被跑起来了（argv 落了盘）", os.path.exists(marker) and argv_line_count > 0,
              argv_line_count),
        check("BatchMode=yes（不许交互式问密码）", "<BatchMode=yes>" in argv_text, argv_text[:200]),
        check("目标只从配置来（user@host 落在一个 argv 元素里）",
              "<p22e2e@127.0.0.1>" in argv_text, argv_text[:300]),
        check("-p / -i 都是独立 argv 元素", "<-p>" in argv_text and "<-i>" in argv_text, argv_text[:300]),
        check("远端调用只占 1 个 argv 元素（没被摊成多词）",
              argv_text.count("rm -rf") == 1 and mk.get("SSH_EXIT") == "0",
              (argv_text.count("rm -rf"), mk.get("SSH_EXIT"))),
        check("带分号的参数原样落地、没被执行",
              os.path.exists(pwned) is False and "rm -rf" in mk.get("SSH_REMOTE", ""),
              (os.path.exists(pwned), mk.get("SSH_REMOTE"))),
        check("无凭据的现场必须大声失败、绝不静默退 local",
              mk.get("SSH_NO_CRED") in ("SSH_NO_CREDENTIALS", "SSH_TARGET_NOT_CONFIGURED"),
              mk.get("SSH_NO_CRED") + "（驱动给的 no-cred 现场 host 与 identity 都没配，"
              "实测先撞 host 白名单 ⇒ 短码是 SSH_TARGET_NOT_CONFIGURED；"
              "只缺钥匙那一支由单测 ssh_missingIdentityFailsLoudlyNoSilentLocal 钉 SSH_NO_CREDENTIALS）"),
    ])


def scenario_wiring(jar):
    home = os.path.join(ROOT, "wiring-%s" % TS)
    write_cfg(home, {"exec.env.backend": "local"})
    r = run_java(jar, "wiring", home)
    mk = markers(r)
    # p22b 修正：驱动打的是 WIRING_SPY_CALLS / WIRING_TOOL_OUTPUT / WIRING_IS_ERROR，
    # 旧稿读的是 SPY_CALLS / TOOL_OUT（键名对不上）；而"摘掉后端时显式报错"这一条驱动里
    # 根本没有对应场景（那是单测 execToolFailsLoudlyWhenTheBackendIsDown 守的）⇒ 删掉这条假期望，
    # 改成驱动真做了的"install(null) 之后收尾成功"。
    return dict(mode="wiring", rc=r["rc"], secs=r["secs"], markers=mk, checks=[
        check("java rc=0", r["rc"] == 0, r["lines"][-3:]),
        check("驱动跑到收尾（WIRING_RC=0）", mk.get("WIRING_RC") == "0", mk.get("WIRING_RC")),
        check("exec 工具真的经过被装上的后端", mk.get("WIRING_SPY_CALLS") not in (None, "", "0"),
              mk.get("WIRING_SPY_CALLS")),
        check("后端返回的文本原样回到工具里", "从 SPI 回来的" in str(mk.get("WIRING_TOOL_OUTPUT")),
              mk.get("WIRING_TOOL_OUTPUT")),
        check("spy 正常返回时工具不判错", mk.get("WIRING_IS_ERROR") == "false", mk.get("WIRING_IS_ERROR")),
    ])


def scenario_legacy(jar):
    home = os.path.join(ROOT, "legacy-%s" % TS)
    write_cfg(home, {"exec.env.backend": "local"})
    r = run_java(jar, "legacy-hang-model", home, timeout=120)
    mk = markers(r)
    # p22b 修正：驱动打的是 NEW_TOTAL_LINES / NEW_TOTAL_BYTES / NEW_COST_MS 与
    # LEGACY_READ_LINES / LEGACY_HANG / LEGACY_RC_MODEL_DONE；旧稿读的 NEW_PATH_HANG 不存在。
    # "新路径不堵"的实测判据 = 同一个 60000 行的场景，新后端在硬上限内返回且模型跑完。
    new_cost = int(mk.get("NEW_COST_MS", "-1") or -1)
    return dict(mode="legacy-hang-model", rc=r["rc"], secs=r["secs"], markers=mk, checks=[
        check("java rc=0（模型自己带硬上限，不会把验收器拖死）", r["rc"] == 0, r["lines"][-3:]),
        check("老口径复现出堵死（LEGACY_HANG=1）", mk.get("LEGACY_HANG") == "1",
              {k: v for k, v in mk.items() if k.startswith("LEGACY")}),
        check("老口径确实只读满 100 行就撒手（LEGACY_READ_LINES=100）",
              mk.get("LEGACY_READ_LINES") == "100", mk.get("LEGACY_READ_LINES")),
        check("模型本身跑完了（LEGACY_RC_MODEL_DONE=1）",
              mk.get("LEGACY_RC_MODEL_DONE") == "1", mk.get("LEGACY_RC_MODEL_DONE")),
        check("新路径同场景不堵：60000 行在 %sms 内返回且全量字节照记" % mk.get("NEW_COST_MS"),
              0 <= new_cost < 20000 and int(mk.get("NEW_TOTAL_BYTES", "0") or 0) > 400000,
              (mk.get("NEW_COST_MS"), mk.get("NEW_TOTAL_LINES"), mk.get("NEW_TOTAL_BYTES"))),
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
