#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P26a 杠③ —— 真 jar / 真进程 / 真 okhttp，打 **只在 127.0.0.1** 的假端点。

红线（照 dispatch 原样）：
  * 绝不出网：端点一律 bind(("127.0.0.1", 0))，密钥一律 ``stub-key-not-real``；
  * 现场目录：``~/.cache/zbot-p26-lead/e2e/<label>/``，**不写 /tmp**；
  * 子进程 HOME 指向现场的 ``zbot-home`` ⇒ 真 ``~/.zbot`` 不可能被碰；
  * 环境的 ``Z_BOT_*`` 一律擦掉，避免继承到真凭据/真地址；
  * 每条读数都直接印在 stdout（决定性读数原样贴进被跟踪的 EVIDENCE.md）。

用法：
    python3 _doc/005_testing/acceptance/p26/p26_e2e.py                     # 跑 S0 闸门 + 9 个场景
    python3 _doc/005_testing/acceptance/p26/p26_e2e.py --label r2           # 现场目录 ROOT/r2/<scene>（整跑要互不相同的 label）
    python3 _doc/005_testing/acceptance/p26/p26_e2e.py --only s1            # 只跑 label 含该子串的场景（S0 闸门必跑）
    python3 _doc/005_testing/acceptance/p26/p26_e2e.py --only s0-positive-control   # 最小阳性对照：stub 收不收得到
内部模式：
    python3 p26_e2e.py --proxy 127.0.0.1:PORT PORTFILE   # 中转子进程（可被 SIGSTOP/SIGKILL）

p26b 相对 P26a 版的改动（全部属于"量具自身缺陷"，见 EVIDENCE §8.2）：
  * ``providers=openai`` 这行 run1 时还没写进 write_config ⇒ BotConfig 不认 openai 这个 code ⇒
    activeProvider().baseUrl=null、keys=0 ⇒ kernel 用它自己的默认域 **真出了网**（8 发全 401），
    stub 收到 0 发 ⇒ 25 条红全是量空气，另有 6 条判词在空集上恒真；
  * 新增 S0 阳性对照闸门（stub 收不到就 rc=4 直接废轮，不再往下判）；
  * 新增 --label / --only（docstring 早就广告过、main() 里没实现）；
  * 空集恒真的 5 条判词补了非空前提；S4/S8 补了"错误路径不许冒充快速收尾"；
  * 每个场景补 stub 实收计数；新增 S9 协议级破断，把 S6/S7 的"静默"钉在 kernel 的
    onClosed→(null Runnable) 上，而不是"bot 不上抛 I/O 错"；
  * 收尾加一把全局尺：本轮现场日志里出现的每个 URL 都必须是 127.0.0.1。
"""

import hashlib
import json
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    os.pardir, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(REPO, "z-bot-core/target/z-bot-core.jar")
DRIVER_SRC = os.path.join(REPO, "_doc/005_testing/acceptance/p26/P26E2eDriver.java")
ROOT = os.path.expanduser("~/.cache/zbot-p26-lead/e2e")
CLASSES = os.path.join(ROOT, "classes")
STUB_KEY = "stub-key-not-real"

FAILED = []
EXTRA = {}
START_MONO = time.monotonic()

# p26b：--label 决定本次整跑的现场目录（ROOT/<RUN>/<scene>），--only 做子串过滤（跑 S0 阳性对照用）。
# 两者都是 docstring 早就广告过、但 main() 里没实现的能力 ⇒ 属于量具缺陷，先补再跑。
RUN = "run"
ONLY = ""


def scene_dir(label):
    d = os.path.join(ROOT, RUN, label)
    os.makedirs(os.path.join(d, "zbot-home"), exist_ok=True)
    return d


def now():
    return "%.3fs" % (time.monotonic() - START_MONO)


def sha8(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()[:8]


# --------------------------------------------------------------------------- 
# 假 LLM 端点：只绑 127.0.0.1，按 mode 决定回什么，并把每一发的服务端时刻记下来
# --------------------------------------------------------------------------- 

def ok_body(model):
    return {
        "id": "chatcmpl-p26", "object": "chat.completion", "created": 1, "model": model,
        "choices": [{"index": 0, "message": {"role": "assistant", "content": "pong"},
                     "finish_reason": "stop"}],
        # 带 cache 字段的 usage：kernel 0.2.1 的 OpenAIProvider 不读这两个键（§0.8 的活证据）
        "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18,
                  "prompt_tokens_details": {"cached_tokens": 5},
                  "cache_creation_input_tokens": 3},
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _note(self, extra):
        srv = self.server
        t = time.monotonic()
        srv.reqs.append(t)
        srv.log.append("req#%d t=%s peer=%s %s" % (len(srv.reqs), now(), self.client_address[0], extra))

    def do_POST(self):
        srv = self.server
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b"{}"
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        model = req.get("model")
        key = (self.headers.get("Authorization") or "").replace("Bearer ", "")
        which = "BAD" if "bad" in key else ("GOOD" if "good" in key else key[:6])
        stream = bool(req.get("stream"))
        self._note("mode=%s path=%s model=%s key=%s stream=%s" % (srv.mode, self.path, model, which, stream))
        sys.stderr.flush()

        if srv.mode == "429_body":
            self._send(429, {"Retry-After": "1"},
                       {"error": {"code": "rate_limit_exceeded", "type": "ratelimit",
                                  "message": "Too Many Requests: retry_after 900ms"}})
        elif srv.mode == "429_hdr":
            self._send(429, {"Retry-After": "1"},
                       {"error": {"code": "rate_limit_exceeded", "type": "ratelimit",
                                  "message": "Too Many Requests"}})
        elif srv.mode == "key403":
            if which == "BAD":
                self._send(403, {}, {"error": {"code": "invalid_api_key",
                                               "message": "Invalid api key or permission denied"}})
            else:
                self._send(200, {}, ok_body(model))
        elif srv.mode == "model404":
            if model == "p26-model-gone":
                self._send(404, {}, {"error": {"code": "model_not_found",
                                               "message": "The model `p26-model-gone` does not exist"}})
            else:
                self._send(200, {}, ok_body(model))
        elif srv.mode == "slow_stream":
            self._start_sse()
            self._chunk("ping", None)
            time.sleep(srv.stall_seconds)
            self._chunk("post-stall", "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            self.close_connection = True
        elif srv.mode == "fast_stream":
            self._start_sse()
            for i in range(3):
                self._chunk("tok%d" % i, None)
                time.sleep(0.12)
            self._chunk("done", "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            self.close_connection = True
        elif srv.mode == "broken_chunked":
            # 阳性对照的反面：宣告一个 0xc8 字节的块，只写 10 字节就硬断（SO_LINGER=0 ⇒ RST）。
            # 这是"协议级破断"，okhttp 必抛 IOException ⇒ kernel onFailure ⇒ bot onError。
            # 用它把 S6/S7 的"没有错误"钉死成"kernel 把 FIN/EOF 当完成"，而不是"bot 不处理 I/O 错"。
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            body = ("data: %s\n\n" % json.dumps({"id": "x", "object": "chat.completion.chunk",
                                                 "model": "p26",
                                                 "choices": [{"index": 0,
                                                              "delta": {"role": "assistant",
                                                                        "content": "ping"},
                                                              "finish_reason": None}]})).encode("utf-8")
            self.wfile.write(b"%x\r\n" % len(body) + body + b"\r\n")
            self.wfile.flush()
            self.wfile.write(b"c8\r\n0123456789")
            self.wfile.flush()
            self.close_connection = True
            try:
                import struct as _st
                self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, _st.pack("ii", 1, 0))
            except Exception:
                pass
        else:
            self._send(200, {}, ok_body(model))

    def _start_sse(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _chunk(self, text, finish):
        delta = {"role": "assistant", "content": text}
        body = {"id": "chatcmpl-p26", "object": "chat.completion.chunk", "model": "p26",
                "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
        self.wfile.write(("data: %s\n\n" % json.dumps(body)).encode("utf-8"))
        self.wfile.flush()

    def _send(self, code, headers, obj):
        payload = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, mode, stall_seconds=4.0):
        ThreadingHTTPServer.__init__(self, ("127.0.0.1", 0), Handler)
        self.mode = mode
        self.stall_seconds = stall_seconds
        self.reqs = []
        self.log = []

    def start(self):
        threading.Thread(target=self.serve_forever, daemon=True).start()
        return self.port

    @property
    def port(self):
        return self.server_address[1]

    def gaps(self):
        return [round((self.reqs[i + 1] - self.reqs[i]) * 1000) for i in range(len(self.reqs) - 1)]


# --------------------------------------------------------------------------- 
# 中转子进程：外部可 SIGSTOP（静默卡流）/ SIGKILL（半路断流）
# --------------------------------------------------------------------------- 

def proxy_main(upstream, portfile):
    host, _, port = upstream.partition(":")
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(16)
    with open(portfile, "w") as f:
        f.write("%d\n" % srv.getsockname()[1])
        f.flush()
        os.fsync(f.fileno())
    while True:
        try:
            client, _ = srv.accept()
        except Exception:
            return
        threading.Thread(target=pump, args=(client, host, int(port)), daemon=True).start()


def pump(client, host, port):
    buf = b""
    while b"\r\n\r\n" not in buf:
        d = client.recv(4096)
        if not d:
            client.close()
            return
        buf += d
    head, rest = buf.split(b"\r\n\r\n", 1)
    m = re.search(rb"Content-Length:\s*(\d+)", head)
    need = int(m.group(1)) if m else 0
    while len(rest) < need:
        d = client.recv(4096)
        if not d:
            break
        rest += d
    up = socket.create_connection((host, port))
    up.sendall(head + b"\r\n\r\n" + rest)
    try:
        while True:
            d = up.recv(4096)
            if not d:
                break
            client.sendall(d)
    except Exception:
        pass
    for s in (client, up):
        try:
            s.close()
        except Exception:
            pass


# --------------------------------------------------------------------------- 
# 场景跑批
# --------------------------------------------------------------------------- 

def java_bin():
    home = os.environ.get("JAVA_HOME")
    if home and os.path.isfile(os.path.join(home, "bin/java")):
        return os.path.join(home, "bin/java")
    javac = which("javac")
    if javac:
        c = os.path.realpath(javac)
        j = os.path.join(os.path.dirname(os.path.dirname(c)), "java")
        if os.path.isfile(j):
            return j
    return "java"


def which(exe):
    for p in os.environ.get("PATH", "").split(os.pathsep):
        c = os.path.join(p, exe)
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


def child_env(home):
    env = dict(os.environ)
    for k in list(env):
        if k.startswith("Z_BOT_") or k in ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "MINIMAX_API_KEY"):
            env.pop(k, None)
    env["HOME"] = home
    return env


def write_config(d, base_url, keys, model, extra):
    lines = [
        "providers=openai",
        "llm.provider=openai",
        "openai.type=openai",
        "openai.api.key=%s" % ",".join(keys),
        "openai.base.url=%s" % base_url,
        "openai.model=%s" % model,
        "agent.max.tokens=4096",
    ]
    lines += extra
    with open(os.path.join(d, "config.properties"), "w") as f:
        f.write("\n".join(lines) + "\n")
    return lines


def run_java(label, d, mode, model=None, timeout=60):
    log = os.path.join(d, "driver.log")
    # 死代理兜底：JDK 的 net.properties 默认 http.nonProxyHosts 放行 127.*，
    # 所以真·出网会被丢到 127.0.0.1:9（discard，无人监听）⇒ connect 立刻被拒。
    argv = [java_bin(),
            "-Djava.net.preferIPv4Stack=true",
            "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
            "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
            "-Dhttp.nonProxyHosts=localhost|127.*|[::1]",
            "-cp", JAR + ":" + CLASSES,
            "P26E2eDriver", mode, d] + ([model] if model else [])
    fh = open(log, "w")
    p = subprocess.Popen(argv, stdout=fh, stderr=subprocess.STDOUT, env=child_env(os.path.join(d, "zbot-home")))
    return p, log, fh


def read_log(log):
    try:
        with open(log, "r") as f:
            return f.read()
    except Exception:
        return ""


def wait_for(path_or_text, needle, seconds, is_path=True, every=0.02):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        text = read_log(path_or_text) if is_path else path_or_text
        if needle in text:
            return True, text
        time.sleep(every)
    return False, (read_log(path_or_text) if is_path else path_or_text)


def has(text, needle):
    return needle in text


def check(name, cond, detail=""):
    print("  %s %-52s %s" % ("PASS" if cond else "FAIL", name, detail))
    sys.stdout.flush()
    if not cond:
        FAILED.append(name)
    return cond


def wait_exit(p, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        rc = p.poll()
        if rc is not None:
            return rc
        time.sleep(0.05)
    p.kill()
    p.wait()
    return "TIMEOUT_KILLED"


def parse_waits(text):
    m = re.search(r"P26 WAITS \[([^\]]*)\]", text)
    if not m or not m.group(1).strip():
        return []
    return [int(x.strip()) for x in m.group(1).split(",") if x.strip()]


def line_with(text, prefix):
    for ln in text.splitlines():
        if ln.startswith(prefix):
            return ln
    return ""


def gaps_within(gaps, lo, hi):
    return len(gaps) > 0 and all(lo <= g <= hi for g in gaps)


RUN_SCENES = 0


def selected(label):
    return (not ONLY) or (ONLY in label)


def guard_aborted(text):
    return "P26 GUARD ABORT" in text


def scene_chat(label, mode, keys, model, extra, expect, retries=None, force=False):
    global RUN_SCENES
    if not (force or selected(label)):
        print("\n=== [%s] SKIPPED by --only=%r ===" % (label, ONLY))
        return None
    RUN_SCENES += 1
    d = scene_dir(label)
    srv = Server(mode)
    port = srv.start()
    base = "http://127.0.0.1:%d/v1" % port
    cfg = write_config(d, base, keys, model, extra)
    print("\n=== [%s] mode=%s keys=%s model=%s ===" % (label, mode, len(keys), model))
    print("  base=%s" % base)
    for ln in cfg:
        if ln.startswith(("llm.", "agent.", "providers")):
            print("  cfg %s" % ln)
    p, log, fh = run_java(label, d, "chat", None)
    rc = wait_exit(p, 60)
    fh.close()
    text = read_log(log)
    for ln in text.splitlines():
        print("  drv " + ln)
    print("  srv " + "\n  srv ".join(srv.log))
    print("  SERVER n=%d gaps=%s rc=%s" % (len(srv.reqs), srv.gaps(), rc))
    sys.stdout.flush()
    if guard_aborted(text):
        print("  !! GUARD ABORT：端点不是 127.0.0.1 或无 key ⇒ 本场景一条判词都不下（不量空气）")
        sys.stdout.flush()
        srv.shutdown()
        srv.server_close()
        return None
    expect(label, text, srv, rc)
    srv.shutdown()
    srv.server_close()
    return text


def scene_stream(label, mode, stale_ms, use_proxy, sig1, hold, sig2, expect):
    """流式现场：可选套一层 proxy 子进程，收到第 1 块后发 SIGSTOP/SIGKILL。"""
    global RUN_SCENES
    if not selected(label):
        print("\n=== [%s] SKIPPED by --only=%r ===" % (label, ONLY))
        return None
    RUN_SCENES += 1
    d = scene_dir(label)
    up = Server(mode)
    up_port = up.start()
    base_port = up_port
    proxy = None
    if use_proxy:
        portfile = os.path.join(d, "proxy.port")
        proxy = subprocess.Popen([sys.executable, os.path.abspath(__file__), "--proxy",
                                  "127.0.0.1:%d" % up_port, portfile],
                                 stdout=open(os.path.join(d, "proxy.log"), "w"),
                                 stderr=subprocess.STDOUT, env=child_env(os.path.join(d, "zbot-home")))
        deadline = time.monotonic() + 10
        while not os.path.exists(portfile) and time.monotonic() < deadline:
            time.sleep(0.02)
        base_port = int(open(portfile).read().strip())
    base = "http://127.0.0.1:%d/v1" % base_port
    extra = ["llm.stream.stale-timeout-ms=%d" % stale_ms,
             "llm.stream.stale-check-interval-ms=150",
             "llm.retry.max=0"]
    cfg = write_config(d, base, [STUB_KEY], "p26-stream-model", extra)
    print("\n=== [%s] mode=%s stale=%dms proxy=%s(%s hold=%ss then %s) ===" % (
        label, mode, stale_ms, bool(proxy), sig1, hold, sig2 or "-"))
    print("  base=%s" % base)
    for ln in cfg:
        if ln.startswith("llm."):
            print("  cfg %s" % ln)
    p, log, fh = run_java(label, d, "stream")
    got1, _ = wait_for(log, "P26 CHUNK n=1", 20)
    check("%s 首块到达" % label, got1)

    def ps_of(proc):
        if proc is None:
            return ""
        return subprocess.run(["ps", "-o", "pid=,stat=,etime=", "-p", str(proc.pid)],
                              capture_output=True, text=True).stdout.strip()

    if proxy and sig1:
        os.kill(proxy.pid, signal.SIGSTOP if sig1 == "STOP" else signal.SIGKILL)
        time.sleep(0.25)
        print("  PROXY pid=%d %s ps=[%s]" % (proxy.pid, sig1, ps_of(proxy) or "GONE"))
        sys.stdout.flush()
    if hold:
        time.sleep(hold)
        mid = read_log(log)
        EXTRA[label] = {"mid_onerror": "P26 ONERROR" in mid,
                        "mid_chunks": (re.search(r"P26 CHUNK n=(\d+)", mid.split("P26 STREAM")[0]).group(1)
                                       if re.search(r"P26 CHUNK n=(\d+)", mid.split("P26 STREAM")[0]) else "0"),
                        "ps_during_hold": ps_of(proxy)}
        print("  HOLD %ds mid-read: onerror=%s chunks=%s ps=[%s]" % (
            hold, EXTRA[label]["mid_onerror"], EXTRA[label]["mid_chunks"],
            EXTRA[label]["ps_during_hold"] or "GONE"))
        sys.stdout.flush()
    if proxy and sig2:
        try:
            os.kill(proxy.pid, signal.SIGKILL)
        except Exception:
            pass
        print("  PROXY pid=%d %s ps=[%s]" % (proxy.pid, sig2, ps_of(proxy) or "GONE"))
        sys.stdout.flush()
    rc = wait_exit(p, 40)
    fh.close()
    text = read_log(log)
    for ln in text.splitlines():
        print("  drv " + ln)
    print("  srv " + "\n  srv ".join(up.log))
    print("  SERVER n=%d rc=%s" % (len(up.reqs), rc))
    sys.stdout.flush()
    if proxy:
        try:
            os.kill(proxy.pid, signal.SIGCONT)
        except Exception:
            pass
        try:
            proxy.send_signal(signal.SIGKILL)
        except Exception:
            pass
        proxy.wait()
    if guard_aborted(text):
        print("  !! GUARD ABORT：端点不是 127.0.0.1 或无 key ⇒ 本场景一条判词都不下（不量空气）")
        sys.stdout.flush()
        up.shutdown()
        up.server_close()
        return None
    expect(label, text, up, rc)
    up.shutdown()
    up.server_close()
    return text


# --------------------------------------------------------------------------- 
# 各场景期望
# --------------------------------------------------------------------------- 

def exp_ok(label, text, srv, rc):
    """S0 阳性对照：stub 必须真收到这一发。收到 0 ⇒ 后面所有读数都是量空气，整轮直接作废退出。

    (a) 类量具缺陷的根判：run1 的 8 个场景全是 ``url=null keys=0`` ⇒ 请求根本没打到 stub，
    却仍打给真厂商 API 拿到 401，于是 25 条红里混着 6 条"空集上的 PASS"。这条对照就是用来
    把那种情况在**第一发**掐掉的。
    """
    n = len(srv.reqs)
    check("S0 阳性对照：stub 真收到 1 发", n == 1, "server n=%d" % n)
    check("S0 阳性对照：这一发的对端=127.0.0.1", n == 1 and "peer=127.0.0.1" in "".join(srv.log),
          str(srv.log[:1]))
    check("S0 阳性对照：驱动 GUARD 放行 loopback", "P26 GUARD" in text and "loopback=true" in text,
          line_with(text, "P26 GUARD"))
    check("S0 阳性对照：驱动拿到 pong", "P26 RESULT ok content=pong" in text,
          line_with(text, "P26 RESULT"))
    check("S0 阳性对照：stub 侧 model/key 与配置一致",
          n == 1 and "model=p26-model-ok" in srv.log[0] and "key=" in srv.log[0], str(srv.log[:1]))
    return n == 1


def exp_429_body(label, text, srv, rc):
    w = parse_waits(text)
    check("S1 分类=RATE_LIMIT/http:429", "RATE_LIMIT" in text and "http:429" in text,
          line_with(text, "P26 DECISION"))
    check("S1 等待=[900,900]（Retry-After 正文标签被采信）", w == [900, 900], str(w))
    check("S1 服务端 3 发", len(srv.reqs) == 3, str(len(srv.reqs)))
    check("S1 服务端实测间隔≈900ms", gaps_within(srv.gaps(), 850, 1600), str(srv.gaps()))
    check("S1 只回 429 最终上抛", has(text, "P26 RESULT err") and "LlmException" in text)
    # p26b：原来写成 all(... for l in srv.log) ⇒ 空 log 上恒真（run1 就是 0 发却 PASS）。
    check("S1 全部对端=127.0.0.1", len(srv.log) > 0 and all("peer=127.0.0.1" in l for l in srv.log),
          "n=%d" % len(srv.log))


def exp_429_hdr(label, text, srv, rc):
    w = parse_waits(text)
    check("S2 无正文标签 ⇒ 不采信 Retry-After 头（kernel 丢头）",
          w and w[0] < 700, "waits=%s" % w)
    check("S2 短档=指数 100/200/400 + 抖动∈[1,1.5]×",
          len(w) >= 3 and 100 <= w[0] <= 150 and 200 <= w[1] <= 300 and 400 <= w[2] <= 600, str(w))
    check("S2 长档=限流阶梯 800/1600/1600（第 4~6 次失败）",
          len(w) == 6 and 800 <= w[3] <= 1200 and 1600 <= w[4] <= 2400 and 1600 <= w[5] <= 2400, str(w))
    check("S2 服务端 7 发 = 6 次等待 + 最后一无空等", len(srv.reqs) == 7, str(len(srv.reqs)))
    # p26b 归因（a) 类：原判词"S2 单调不回退"写成"等待序列本身不减"，但抖动是 uniform[0,0.5×] 的
    # **加性随机项**，长档第 5/6 次同属 1600 这一档 ⇒ 两次抖动抽样谁大谁小是掷硬币（本轮实测
    # 2306 → 1713 就被判红）。产品性质是"**档位**不减 + 抖动只往上加"，不是"裸序列单调"。
    # 改成判档位包络：w[i] ∈ [rung[i], 1.5×rung[i]+150ms 调度余量]，rung=[100,200,400,800,1600,1600]。
    rung = [100, 200, 400, 800, 1600, 1600]
    env = len(w) == 6 and all(rung[i] <= w[i] <= 1.5 * rung[i] + 150 for i in range(6))
    check("S2 档位不减且抖动只上加（w∈[rung,1.5×rung+150]）", env, "waits=%s rung=%s" % (w, rung))
    # p26b：这条原来在 w==[] 时恒真（run1 就是空集 PASS）。判据要求"确实有 6 次等待"才算数。
    check("S2 总预算内", len(w) == 6 and sum(w) <= 12000, "%s sum=%d" % (w, sum(w)))
    check("S2 全部对端=127.0.0.1", len(srv.log) > 0 and all("peer=127.0.0.1" in l for l in srv.log),
          "n=%d" % len(srv.log))


def line_like(text, needle):
    for ln in text.splitlines():
        if needle in ln:
            return ln
    return ""


def exp_key403(label, text, srv, rc):
    # p26b 归因（a) 类量具缺陷：原判词读的是 ResilientLlmProvider.lastDecision()，而 403 在
    # KeyPoolLlmProvider 层就被消化掉了（§1.1 的设计：ROTATE_KEY 由 KeyPool 轮换，Resilient 层
    # 不重试也不看见异常）⇒ lastDecision 恒为 null，是**观测点接错**，不是分类表判错。
    # 改成读两条真证据：KeyPool 的具名轮换日志（KeyPoolLlmProvider.java:128，只有分类=ROTATE_KEY
    # 才会走到）+ stub 侧的 BAD→GOOD 顺序。
    kp = ("[KeyPool]" in text and "换下一个 key" in text and "HTTP 403" in text)
    check("S3 分类=ROTATE_KEY ⇒ KeyPool 层消化（403）", kp,
          line_like(text, "[KeyPool] key#") or "驱动日志里没有 KeyPool 轮换行")
    check("S3 首 key 403 后换到好 key ⇒ 成功", has(text, "P26 RESULT ok content=pong"))
    check("S3 未触发模型层重试（KeyPool 内部消化）", parse_waits(text) == [] and "P26 RETRIES []" in text,
          line_with(text, "P26 RETRIES"))
    order = [l.split("key=")[1].split()[0] for l in srv.log]
    check("S3 服务端看到 key 顺序 BAD→GOOD", order == ["BAD", "GOOD"], str(order))
    check("S3 服务端 2 发（BAD 403 + GOOD 200）", len(srv.reqs) == 2, str(len(srv.reqs)))
    check("S3 usage 归一（prompt=11/completion=7）", "promptTokens=11" in text or "prompt=11" in text,
          line_with(text, "P26 USAGE"))
    # §5.4 的活证据（不是新缺陷）：kernel 0.2.1 不读 cache 字段 ⇒ 真进程跑出来只能是 0。
    check("S3 cache 两位=0（§5.4 kernel 卡口，原样记账）",
          "cacheRead=0, cacheWrite=0" in text, line_with(text, "P26 USAGE"))


def exp_model404(label, text, srv, rc):
    check("S4 分类=FALLBACK_MODEL/http:404-model", "FALLBACK_MODEL" in text and "404" in text,
          line_with(text, "P26 DECISION"))
    models = [l.split("model=")[1].split()[0] for l in srv.log]
    check("S4 先打坏模型再打降级模型", models == ["p26-model-gone", "p26-model-ok"], str(models))
    check("S4 降级后拿到回复", has(text, "P26 RESULT ok content=pong"))
    # p26b：这条原来是 parse_waits(text)==[] ⇒ 空气上恒真。改成"降级成功**且**确实没退避"。
    check("S4 换模型不重跑退避（0 等待）", parse_waits(text) == [] and has(text, "P26 RESULT ok"),
          "waits=%s ok=%s" % (parse_waits(text), "P26 RESULT ok" in text))


def exp_stream_stall(label, text, srv, rc):
    check("S5 stub 真收到这一发（非空集）", len(srv.reqs) == 1, "n=%d" % len(srv.reqs))
    check("S5 卡流被判陈旧", "StreamStaleException" in text, line_with(text, "P26 ONERROR"))
    m = re.search(r"P26 ONERROR t=(\d+)ms", text)
    ms = int(m.group(1)) if m else -1
    check("S5 1500ms 档 ⇒ 1.5~3.5s 内报错", 1400 <= ms <= 3500, "t=%dms" % ms)
    check("S5 只收到卡流前的 1 块", "chunks=1" in text, line_with(text, "P26 STREAM"))
    check("S5 看门狗线程不泄漏", "leaked=0" in text, line_with(text, "P26 THREADS"))
    check("S5 进程自然退出 rc=0", rc == 0, "rc=%s" % rc)


def exp_stream_nowatchdog(label, text, srv, rc):
    mid = EXTRA.get(label, {})
    check("S6 stub 真收到这一发（非空集）", len(srv.reqs) == 1, "n=%d" % len(srv.reqs))
    check("S6 关掉看门狗 + 外层静默卡流 ⇒ 12s 内**没有**任何错误（对照组）",
          mid.get("mid_onerror") is False and mid.get("mid_chunks") == "1", str(mid))
    check("S6 卡住期间 proxy 处于 T（停）态", "T" in str(mid.get("ps_during_hold", "")),
          str(mid.get("ps_during_hold")))
    # p26b 归因（b) 类：原判词"S6 只有 SIGKILL 断流后 java 才拿到错误"假设了"TCP 断 ⇒ 上抛 I/O 错"。
    # kernel 字节码级实测（§8.3）：OpenAIProvider.streamChat 往 postJsonStream 传的 Runnable 是
    # aconst_null ⇒ LlmHttp$1.onClosed 无事可做 ⇒ **EOF/FIN 收尾对 bot 完全不可见**，
    # bot 层的 streamChat 根本没有"流结束"这个回调接缝。所以"断流后拿到错误"这条期望
    # 是量具写错了模型，不是被测码没兑现：SIGKILL 一个空闲的中转进程发出的是 FIN（不是 RST）。
    # 于是这条判词改成它唯一能成立的形式：**截断静默**（缺陷 F 的正面证据，见 §8.4/§11），
    # 并在 S9 用协议级破断（真 IOException）做双向对照，证明 bot 确实会上抛"真 I/O 错"。
    check("S6【缺陷 F 记账】FIN 截断静默：无 ONERROR 且驱动自带上界到点",
          "P26 ONERROR" not in text and "P26 STREAM done=false" in text,
          line_with(text, "P26 STREAM"))
    m = re.search(r"waitedMs=(\d+)", text)
    ms = int(m.group(1)) if m else -1
    check("S6 挂够 12s（证明卡的不是内层）", ms >= 12000, "waitedMs=%d" % ms)
    check("S6 关看门狗时不产生看门狗线程", "before=0 after=0 leaked=0" in text, line_with(text, "P26 THREADS"))
    check("S6 驱动不永挂（有界 await 25s 后自行退出）", rc == 0, "rc=%s" % rc)


def exp_stream_kill(label, text, srv, rc):
    check("S7 stub 真收到这一发（非空集）", len(srv.reqs) == 1, "n=%d" % len(srv.reqs))
    # p26b 归因（a) 类（与 S6 同一个 kernel 事实）：SIGKILL 空闲中转进程 ⇒ FIN ⇒ kernel 视作正常收尾
    # ⇒ 能兜住这条的**只有** §4 的看门狗，所以"立刻 I/O 错、且不是 StreamStale"这个原判词
    # 与交付语义（§4：陈旧检测=阈值+巡检周期）互相矛盾。改判成设计口径：
    # 断流在 stale 阈值 + ≤1 个巡检周期(150ms) + 调度余量 内被看门狗检出并上抛一次。
    check("S7 半路断流被陈旧看门狗检出（唯一的兜底路径）",
          "StreamStaleException" in text, line_with(text, "P26 ONERROR"))
    m = re.search(r"P26 ONERROR t=(\d+)ms", text)
    check("S7 4000ms 档 ⇒ 4.0~5.5s 内报错（阈值+巡检 150ms 余量）",
          m and 3950 <= int(m.group(1)) <= 5500, "t=%s" % (m.group(1) if m else "?"))
    check("S7 断流后不留挂线程/不永挂（有界 await 到点即退）", rc == 0, "rc=%s" % rc)
    check("S7 看门狗随错误一起收尾（leaked=0）", "leaked=0" in text, line_with(text, "P26 THREADS"))
    check("S7 只收到断流前的 1 块", "chunks=1" in text, line_with(text, "P26 STREAM"))


def exp_stream_break(label, text, srv, rc):
    """S9 双向对照：协议级破断（块长写 0xc8 只发 10 字节）⇒ okhttp 必抛 IOException。

    这一支存在的意义：如果只有 S6/S7，就无法区分"kernel 把 FIN 当完成"和"bot 压根不上抛 I/O 错"。
    拿到**非 stale** 的即时错误 ⇒ 前者成立，S6/S7 的静默是被上游挡住的，不是量具放宽判词。
    """
    check("S9 stub 真收到这一发（非空集）", len(srv.reqs) == 1, "n=%d" % len(srv.reqs))
    check("S9 协议级破断 ⇒ 即时上抛非 stale 的 I/O 错",
          "P26 ONERROR" in text and "StreamStale" not in text, line_with(text, "P26 ONERROR"))
    m = re.search(r"P26 ONERROR t=(\d+)ms", text)
    check("S9 破断到报错 < 5s（关掉看门狗也拿得到）", m and int(m.group(1)) < 5000,
          "t=%s" % (m.group(1) if m else "?"))
    check("S9 错误在收尾块之前（chunks<=1）", re.search(r"chunks=(\d+)", text)
          and int(re.search(r"chunks=(\d+)", text).group(1)) <= 1, line_with(text, "P26 STREAM"))
    check("S9 进程自然退出 rc=0", rc == 0, "rc=%s" % rc)


def exp_stream_ok(label, text, srv, rc):
    check("S8 stub 真收到这一发（非空集）", len(srv.reqs) == 1, "n=%d" % len(srv.reqs))
    check("S8 正常流不被误判陈旧（无 ONERROR）", "P26 ONERROR" not in text, line_with(text, "P26 STREAM"))
    check("S8 收到 4 块含收尾块", "chunks=4" in text, line_with(text, "P26 STREAM"))
    m = re.search(r"waitedMs=(\d+)", text)
    # p26b：这条原来只看 waitedMs<5000 ⇒ run1 里 waitedMs=1243 其实是"打真 API 401 后立刻返回"，
    # 被当成"收尾块到齐即结束"的绿。加上"无错误"这个前提，避免错误路径冒充快速收尾。
    check("S8 收尾块到齐即结束（<5s，不空等 stale）",
          m and int(m.group(1)) < 5000 and "P26 ONERROR" not in text,
          "waitedMs=%s onerror=%s" % (m.group(1) if m else "?", "P26 ONERROR" in text))
    check("S8 正常流后看门狗线程归零", "leaked=0" in text, line_with(text, "P26 THREADS"))
    check("S8 rc=0", rc == 0, "rc=%s" % rc)


def main():
    global RUN, ONLY
    if "--proxy" in sys.argv:
        i = sys.argv.index("--proxy")
        proxy_main(sys.argv[i + 1], sys.argv[i + 2])
        return 0
    if "--label" in sys.argv:
        RUN = sys.argv[sys.argv.index("--label") + 1]
    if "--only" in sys.argv:
        ONLY = sys.argv[sys.argv.index("--only") + 1]

    os.makedirs(CLASSES, exist_ok=True)
    print("P26 E2E harness  started=%s  run_label=%s  only=%r" % (
        time.strftime("%Y-%m-%d %H:%M:%S %z"), RUN, ONLY or "ALL"))
    print("  jar=%s" % JAR)
    print("  jar_sha8=%s" % (sha8(JAR) if os.path.isfile(JAR) else "MISSING"))
    print("  git_head=%s" % subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"],
                                           capture_output=True, text=True).stdout.strip())
    print("  git_src_dirty=%r" % subprocess.run(["git", "-C", REPO, "status", "--porcelain",
                                                 "--", "z-bot-core/src"],
                                                capture_output=True, text=True).stdout.strip())
    print("  java=%s" % java_bin())
    print("  root=%s/%s" % (ROOT, RUN))
    if not os.path.isfile(JAR):
        print("!! jar 不存在：先 mvn -o package -DskipTests -pl z-bot-core")
        return 2
    jc = which("javac")
    cp = "%s:%s" % (JAR, CLASSES)
    r = subprocess.run([jc, "-encoding", "UTF-8", "-cp", JAR, "-d", CLASSES, DRIVER_SRC],
                       capture_output=True, text=True)
    print("  javac rc=%s %s" % (r.returncode, (r.stdout + r.stderr).strip()[:400]))
    sys.stdout.flush()
    if r.returncode != 0:
        return 3

    # ---- (0) 阳性对照闸门：stub 收不到 ⇒ 后面全是量空气，直接判废不跑（不受 --only 影响，必跑）
    gate = scene_chat("s0-positive-control", "ok", [STUB_KEY], "p26-model-ok",
                      ["llm.retry.max=0"], exp_ok, force=True)
    if gate is None:
        print("\n=== E2E ABORT === driver 侧 GUARD 拦住了非回环端点（rc=4）：接线未通，拒绝量空气")
        print("rc=4")
        return 4
    if ("S0 阳性对照：stub 真收到 1 发" in FAILED) or ("P26 GUARD ABORT" in (gate or "")):
        print("\n=== E2E ABORT === S0 阳性对照红 ⇒ stub 没收到请求，后续 8 个场景一律不判（避免量空气）")
        print("scenes=1 failed_checks=%d %s" % (len(FAILED), FAILED))
        print("rc=4")
        return 4

    # ---- (a) 真 429 + Retry-After：正文有标签 / 只有 HTTP 头，两种
    scene_chat("s1-429-body", "429_body", [STUB_KEY], "p26-model-ok",
               ["llm.retry.max=2", "llm.retry.base-delay-ms=200", "llm.retry.jitter-ratio=0.0",
                "llm.retry.retry-after-cap-ms=1200", "llm.retry.total-budget-ms=5000"], exp_429_body)
    scene_chat("s2-429-hdronly", "429_hdr", [STUB_KEY], "p26-model-ok",
               ["llm.retry.max=6", "llm.retry.base-delay-ms=100", "llm.retry.jitter-ratio=0.5",
                "llm.retry.short-attempts=3", "llm.retry.rate-limit-ladder-ms=800,1600",
                "llm.retry.max-delay-ms=20000", "llm.retry.total-budget-ms=12000"], exp_429_hdr)
    # ---- (b) 换 key
    scene_chat("s3-rotate-key", "key403", ["stub-key-bad", "stub-key-good"], "p26-model-ok",
               ["llm.retry.max=0", "llm.retry.base-delay-ms=100"], exp_key403)
    # ---- (c) 换模型
    scene_chat("s4-switch-model", "model404", [STUB_KEY], "p26-model-gone",
               ["llm.retry.max=2", "llm.retry.base-delay-ms=100", "llm.retry.jitter-ratio=0.0",
                "llm.fallback.models=p26-model-ok"], exp_model404)
    # ---- (d) 流式卡死：看门狗 / 关看门狗对照 / 半路断流 / 正常流
    scene_stream("s5-stream-stall", "slow_stream", 1500, True, "STOP", 0, None, exp_stream_stall)
    scene_stream("s6-stream-nowatchdog", "slow_stream", 0, True, "STOP", 12, "KILL", exp_stream_nowatchdog)
    scene_stream("s7-stream-kill", "slow_stream", 4000, True, "KILL", 0, None, exp_stream_kill)
    scene_stream("s8-stream-ok", "fast_stream", 1500, False, None, 0, None, exp_stream_ok)
    # ---- (f) 双向对照：协议级破断（真 IOException）——用来证明 S6/S7 的静默是上游 EOF 语义，
    #      而不是"bot 不上抛 I/O 错"。关掉看门狗也必须有错。
    scene_stream("s9-stream-break", "broken_chunked", 0, False, None, 0, None, exp_stream_break)

    # ---- (e) 全局红线尺：本轮现场里**每一个**被打印出来的 URL 都必须回环
    # run1 的教训：配置没接上 ⇒ kernel 用它自己的默认域 https://api.openai.com/v1 真出了网
    # （8 个场景各 1 发，全部 401）。这一条尺把"量具自己先出网"变成会红的判词，而不是事后发现。
    leak = []
    pat = re.compile(r"(?:^|[ \[])(?:url=|\[)(https?://[^\s\]]+)")
    for r_, _d, fs in os.walk(os.path.join(ROOT, RUN)):
        for fn in sorted(fs):
            if not fn.endswith(".log"):
                continue
            for m in pat.findall(read_log(os.path.join(r_, fn))):
                if not re.match(r"https?://(127\.0\.0\.1|localhost)(:\d+)?(/|$)", m):
                    leak.append("%s:%s" % (fn, m))
    check("全局：本轮所有 URL 均为 127.0.0.1（无真出网）", not leak, str(leak[:4]))

    print("\n=== E2E SUMMARY ===")
    print("scenes=%d failed_checks=%d %s" % (RUN_SCENES, len(FAILED), FAILED))
    print("rc=%d" % (1 if FAILED else 0))
    sys.stdout.flush()
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
