#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P30b 真进程 E2E（杠③）：起**真 JVM** gateway，用真 HTTP 打四个入站面，判据落在
"跨过进程边界的东西"上 —— 超限的 body 既不许变成出站消息，也不许变成一次 LLM 调用；
正好到上限的 body 必须照样进得来。

为什么单测不够（`InboundBodyLimitTest` 已有 9 支）：
  1. 单测起的是**同一个 JVM 里的** HttpServer，边界值取样用的是**被测量自己那个常量**。
     这里上限是从 `InboundLimits.java` 的字节里读出来的（K1），进程外再对一次。
  2. 装配层：manifest 里 `channel.webhook.enabled=true` 的通道到底有没有真在监听、
     监听的那个端口是不是我以为的那个 —— 只有真 gateway 算数。
  3. 尺寸：单测里"超限"是 64 KiB + 1；这里额外打一份 **2×上限的分块请求**（真 socket、
     真 chunked 框架、一个 Content-Length 字节都不出现），看服务端在读到第几个字节时断掉。

每轮（`P30B_ROUNDS`，缺省 3）跑同一组判据，轮与轮之间不共享状态：
  K1      上限常量：从源码字节里读到，且必须是 65_536
  F1      飞书：正确 SHA-256 签名 + 超限 body ⇒ 413，且该 marker 既不出站也不进 LLM
  F2      飞书：正好上限的 body ⇒ 200（正臂：门不是恒关的）
  F3      飞书：**分块传输**（无 Content-Length）打 2×上限 ⇒ 413
  D1      钉钉：正确入站签名 + 超限 ⇒ 413 零副作用
  D2      钉钉：超限 + **不签名** ⇒ 401 —— 上限不许抢在鉴权门前面（否则未授权请求能靠状态码
              探到"我的 body 被读过了"）
  W1      webhook：超限 ⇒ 413；W2 webhook：正常尺寸 ⇒ 200 且 marker 真进了 LLM
  H1      控制台：/bot/steer 超限 ⇒ 413；H2 正好上限 ⇒ 200
  N1      连打三条超限之后，一条正常消息仍要 200 + 进 LLM（门不能把通道本身打死）

红线：数据根 = `~/.cache/zbot-p30b/e2e/round-<n>/`（不写 /tmp：同机别的机会扫空 /tmp），
`ZBOT_HOME` 与 `--config-dir` 都指它；LLM 是进程内假端点，key 写 `stub-key-not-real`，
真 key 一个字节都不进本脚本；出站只指 127.0.0.1，真域名一个包都不发；端口一律 `bind(0)`。
本脚本**不 import 别的战役脚本**（量具之间不许互相借读数），只用标准库。

复算: python3 -u _doc/acceptance/p30b/p30b_e2e.py
      P30B_ROUNDS=1 python3 -u _doc/acceptance/p30b/p30b_e2e.py
"""
import hashlib
import hmac
import io
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
LIMITS_SRC = os.path.join(ZBOT, "z-bot-core", "src", "main", "java", "com", "zifang", "z",
                          "bot", "channel", "InboundLimits.java")
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p30b")
OUT = os.path.join(CACHE, "e2e")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")

STUB_KEY = "stub-key-not-real"
FAKE_APP_ID = "cli_stub_app_not_real"
FAKE_APP_SECRET = "cli_stub_secret_not_real"
FAKE_VERIFY_TOKEN = "cli_stub_p30b_verify_token"
FAKE_ENCRYPT_KEY = "cli_stub_p30b_encrypt_key"
DING_TOKEN = "stub_ding_access_token_not_real"
DING_WEBHOOK_SECRET = "SECstub_p30b_webhook_sign_not_real"
DING_INBOUND_SECRET = "SECstub_p30b_inbound_sign_not_real"

RESULTS = []
SPAWNED = []
JVM_PIDS = []
IM_RECORDS = []
IM_LOCK = threading.Lock()
LLM_HITS = []
LLM_LOCK = threading.Lock()


# ===== 量具 =====

def check(name, ok, detail):
    RESULTS.append((name, bool(ok), detail))
    print("%-4s %-58s %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)
    return bool(ok)


def log(msg):
    print("     · %s" % msg, flush=True)


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def md5(path):
    if not os.path.isfile(path):
        return None
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def http_post(url, raw_body, headers=None, timeout=30):
    """body 只做一次序列化：签名算的就是上线的那串字节。"""
    body = raw_body.encode("utf-8") if isinstance(raw_body, str) else raw_body
    h = {"Content-Type": "application/json; charset=utf-8"}
    h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=h),
                                    timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def http_post_chunked(port, path, raw_body, headers=None, chunk=8192, timeout=30):
    """真 chunked 框架：请求行里**没有** Content-Length 这一行，结束块是 `0\\r\\n\\r\\n`。

    为什么不用 urllib：它一定会写 Content-Length，那样打的是"头检"那一层，
    量不到"按字节累加"那道门（p30b_mutation.py 的 B2 就是打这一刀的）。
    """
    c = HTTPConnection("127.0.0.1", port, timeout=timeout)
    c.putrequest("POST", path)
    c.putheader("Content-Type", "application/json; charset=utf-8")
    c.putheader("Transfer-Encoding", "chunked")
    for k, v in (headers or {}).items():
        c.putheader(k, v)
    c.endheaders()
    for i in range(0, len(raw_body), chunk):
        seg = raw_body[i:i + chunk]
        c.send(b"%x\r\n" % len(seg) + seg + b"\r\n")
    c.send(b"0\r\n\r\n")
    try:
        r = c.getresponse()
        return r.status, r.read().decode("utf-8", "replace")
    finally:
        c.close()


def wait_until(pred, timeout, every=0.2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if pred():
                return True
        except Exception:
            pass
        time.sleep(every)
    return False


def lsof_port(port):
    r = subprocess.run(["lsof", "-nP", "-iTCP:%d" % port, "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return [l for l in (r.stdout or "").split("\n")[1:] if l.strip()]


def lsof_pid_port(pid, port):
    r = subprocess.run(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP:%d" % port, "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    return [l for l in (r.stdout or "").split("\n")[1:] if l.strip()]


# ===== 独立实现侧：签名由 python hashlib/hmac 现算，不借 Java =====

def feishu_sign(ts, nonce, key, raw_body):
    blob = (ts + nonce + key).encode("utf-8") + (
        raw_body.encode("utf-8") if isinstance(raw_body, str) else raw_body)
    return hashlib.sha256(blob).hexdigest()


def ding_sign(ts, key):
    digest = hmac.new(key.encode("utf-8"), (str(ts) + "\n" + key).encode("utf-8"),
                      hashlib.sha256).digest()
    import base64
    return base64.b64encode(digest).decode("ascii")


# ===== 假端点：LLM 回显 + IM 记录（入站是否"真的被受理"的进程外观察口）=====

class FakeIm(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
            return
        print("     · fake-im handle_error: %s: %s" % (type(exc).__name__, exc), flush=True)


class FakeImHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        path = self.path.split("?")[0]
        with IM_LOCK:
            IM_RECORDS.append({"t": time.time(), "path": path,
                               "body": raw.decode("utf-8", "replace")})
        if path.endswith("/auth/v3/tenant_access_token/internal"):
            payload = json.dumps({"code": 0, "msg": "ok",
                                  "tenant_access_token": "t-p30b-fake", "expire": 3600})
        elif path.endswith("/im/v1/messages"):
            payload = json.dumps({"code": 0, "msg": "ok", "data": {"message_id": "om_p30b"}})
        elif path.endswith("/robot/send"):
            payload = json.dumps({"errcode": 0, "errmsg": "ok"})
        else:
            payload = json.dumps({"code": 1, "msg": "no such path"})
        data = payload.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class StubLlm(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        print("     · stub-llm handle_error: %s" % sys.exc_info()[1], flush=True)


class StubLlmHandler(BaseHTTPRequestHandler):
    """把最后一条 user 消息记下来并回显 ⇒ "入站被受理"在进程外有第二个观察口。"""
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        last = ""
        for m in reversed(req.get("messages") or []):
            if m.get("role") == "user":
                last = m.get("content") or ""
                break
        with LLM_LOCK:
            LLM_HITS.append({"t": time.time(), "user": last})
            seq = len(LLM_HITS)
        body = "P30B-ECHO-%03d[%s]" % (seq, last[:120])
        payload = json.dumps({
            "id": "p30b-%d" % seq, "object": "chat.completion",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": body},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 5, "completion_tokens": 7, "total_tokens": 12},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def start_servers():
    im = FakeIm(("127.0.0.1", 0), FakeImHandler)
    threading.Thread(target=im.serve_forever, daemon=True).start()
    llm = StubLlm(("127.0.0.1", 0), StubLlmHandler)
    threading.Thread(target=llm.serve_forever, daemon=True).start()
    return ("http://127.0.0.1:%d/open-apis" % im.server_address[1],
            "http://127.0.0.1:%d/v1" % llm.server_address[1])


def im_records():
    with IM_LOCK:
        return list(IM_RECORDS)


def llm_saw(marker):
    with LLM_LOCK:
        return any(marker in h["user"] for h in LLM_HITS)


def entered(marker):
    """一条入站"真的被受理"的两处证据：进了 LLM 上下文，或产生了出站字节。"""
    return llm_saw(marker) or any(marker in r["body"] for r in im_records())


def settle(seconds=3.0, marker=""):
    """负向臂的观察窗：等这么久，两处观察口都没有这条 marker，才叫"挡下"。

    absence 没有因果信号可等，有界观察窗是唯一诚实的去竞态方式（判的是"这一条 marker"，
    不是"记录表为空"⇒ 不会被同一轮里正臂的余波污染，也不需要 im_clear 这种全局副作用）。
    """
    deadline = time.time() + seconds
    while time.time() < deadline:
        if entered(marker):
            return False
        time.sleep(0.2)
    return not entered(marker)


# ===== 真 JVM gateway =====

class Gateway(object):
    def __init__(self, tag, profile, base_url, manifest, ports):
        self.tag = tag
        self.profile = profile
        self.ports = ports
        self.logpath = os.path.join(OUT, "jvm-%s.out" % tag)
        self.logfile = io.open(self.logpath, "w", encoding="utf-8")
        env = dict(os.environ)
        env.update({"ZBOT_HOME": profile,
                    "Z_BOT_BASE_URL": base_url,
                    "Z_BOT_API_KEY": STUB_KEY,
                    "Z_BOT_MODEL": "stub-model",
                    "Z_BOT_PROVIDER": "stub"})
        self.env = env
        self.cmd = ["java", "-jar", JAR, "gateway", "--config-dir", profile,
                    "--port", str(ports["console"]),
                    "--webhook-port", str(ports["webhook"]),
                    "--channel-manifest", manifest]
        self.proc = None
        SPAWNED.append(self)

    def start(self):
        self.proc = subprocess.Popen(self.cmd, stdout=self.logfile, stderr=subprocess.STDOUT,
                                     env=self.env, cwd=self.profile)
        JVM_PIDS.append(self.proc.pid)
        log("%s fork pid=%d profile=%s ports=%s" % (self.tag, self.proc.pid,
                                                   os.path.basename(self.profile), self.ports))

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def listening_all(self):
        for _ in range(200):
            if not self.alive():
                return 0
            if all(lsof_pid_port(self.proc.pid, p) for p in self.ports.values()):
                return 1
            time.sleep(0.25)
        return 0

    def missing_listeners(self):
        return [k for k, p in self.ports.items() if not lsof_pid_port(self.proc.pid, p)]

    def text(self):
        try:
            self.logfile.flush()
        except Exception:
            pass
        try:
            return io.open(self.logpath, encoding="utf-8", errors="replace").read()
        except Exception:
            return ""

    def terminate(self):
        if not self.alive():
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=30)
        except Exception:
            self.proc.kill()
            self.proc.wait(timeout=10)

    def close(self):
        try:
            self.logfile.close()
        except Exception:
            pass


def write_profile(root):
    if not os.path.isdir(root):
        os.makedirs(root)
    with io.open(os.path.join(root, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("minimax.api.key=%s\n" % STUB_KEY)
        fh.write("minimax.model=stub-model\n")
    return root


def manifest_lines(fake_base, ports):
    return "\n".join([
        "channel.http.kind=http", "channel.http.enabled=true", "channel.http.outbound=false",
        # webhook 这一面在 p30 的 manifest 里是 enabled=false（本期要量它的入站尺寸 ⇒ 打开）
        "channel.webhook.kind=webhook", "channel.webhook.enabled=true",
        "channel.webhook.default-port=%d" % ports["webhook"],
        "channel.feishu.kind=feishu", "channel.feishu.enabled=true",
        "channel.feishu.outbound=true",
        "channel.feishu.default-port=%d" % ports["feishu"],
        "channel.feishu.requires=app-id,app-secret",
        "channel.feishu.config.api-base=%s" % fake_base,
        "channel.feishu.config.receive-id-type=chat_id",
        "channel.feishu.config.app-id=%s" % FAKE_APP_ID,
        "channel.feishu.config.app-secret=%s" % FAKE_APP_SECRET,
        "channel.feishu.config.verification-token=%s" % FAKE_VERIFY_TOKEN,
        "channel.feishu.config.encrypt-key=%s" % FAKE_ENCRYPT_KEY,
        "channel.dingtalk.kind=dingtalk", "channel.dingtalk.enabled=true",
        "channel.dingtalk.outbound=true",
        "channel.dingtalk.default-port=%d" % ports["dingtalk"],
        "channel.dingtalk.requires=webhook-url",
        "channel.dingtalk.config.webhook-url=%s/robot/send?access_token=%s"
        % (fake_base, DING_TOKEN),
        "channel.dingtalk.config.secret=%s" % DING_WEBHOOK_SECRET,
        "channel.dingtalk.config.inbound-secret=%s" % DING_INBOUND_SECRET,
    ]) + "\n"


def start_gateway(tag, fake_base, base_url):
    if not base_url:
        raise SystemExit("FATAL: base_url 为空 ⇒ 假端点没起来，量具坏（不收这条读数）")
    root = write_profile(os.path.join(OUT, "round-%s" % tag))
    ports = {"console": free_port(), "webhook": free_port(),
             "feishu": free_port(), "dingtalk": free_port()}
    mpath = os.path.join(root, "channels-%s.properties" % tag)
    with io.open(mpath, "w", encoding="utf-8") as fh:
        fh.write(manifest_lines(fake_base, ports))
    gw = Gateway(tag, root, base_url, mpath, ports)
    gw.start()
    if not gw.listening_all():
        # 端口没起来就判 FATAL：四个面少任何一个，下面的"四面都有门"就是空跑
        raise SystemExit("FATAL: %s 的 gateway 缺少监听 %s（看 %s）"
                         % (tag, gw.missing_listeners(), gw.logpath))
    return gw


# ===== 入站 body 构造（尺寸一律从源码常量推，不在脚本里抄第二份数字）=====

def read_limit():
    src = io.open(LIMITS_SRC, encoding="utf-8").read()
    m = re.search(r"MAX_BODY_BYTES\s*=\s*([0-9_]+)\s*;", src)
    if not m:
        raise SystemExit("FATAL: 在 %s 里读不到 MAX_BODY_BYTES ⇒ 量具坏" % LIMITS_SRC)
    return int(m.group(1).replace("_", ""))


def pad_json(prefix, suffix, total):
    n = total - len(prefix) - len(suffix)
    assert n > 0, "fixture 前后缀比总长还长"
    s = prefix + "x" * n + suffix
    assert len(s.encode("utf-8")) == total, "padding 没精确到字节: %d" % len(s.encode("utf-8"))
    return s


def feishu_headers(body, limit_tag):
    ts = str(int(time.time()))
    nonce = "p30b-%s-%d-%d" % (limit_tag, os.getpid(), int(time.time() * 1000) % 100000)
    return {"X-Lark-Request-Timestamp": ts, "X-Lark-Request-Nonce": nonce,
            "X-Lark-Signature": feishu_sign(ts, nonce, FAKE_ENCRYPT_KEY, body)}


def ding_headers(body):
    ts = str(int(time.time() * 1000))
    return {"timestamp": ts, "sign": ding_sign(ts, DING_INBOUND_SECRET)}


# ===== 一轮 =====

def run_round(n, limit, fake_base, base_url):
    pfx = "R%d/" % n
    gw = start_gateway("r%d" % n, fake_base, base_url)
    fp, dp, wp, cp = (gw.ports["feishu"], gw.ports["dingtalk"],
                      gw.ports["webhook"], gw.ports["console"])
    try:
        # ---- 四面各自超限 ⇒ 413，且这条 marker 哪儿都没去 ----
        over = pad_json('{"token":"%s","pad":"' % FAKE_VERIFY_TOKEN, '"}', limit + 1)
        st, body = http_post("http://127.0.0.1:%d/feishu/event" % fp, over,
                             headers=feishu_headers(over, "f1"))
        q = settle(2.0, "should-not-enter")
        check(pfx + "F1 飞书超限(签名正确) ⇒ 413 且零副作用",
              st == 413 and "too large" in body and q,
              "status=%d body=%s" % (st, body[:70]))

        st, body = http_post("http://127.0.0.1:%d/feishu/event" % fp,
                             pad_json('{"token":"%s","pad":"' % FAKE_VERIFY_TOKEN, '"}', limit),
                             headers=feishu_headers(
                                 pad_json('{"token":"%s","pad":"' % FAKE_VERIFY_TOKEN, '"}', limit),
                                 "f2"))
        check(pfx + "F2 飞书正好上限 ⇒ 200（门不是恒关的）", st == 200,
              "status=%d body=%s size=%d" % (st, body[:40], limit))

        big = pad_json('{"token":"%s","pad":"' % FAKE_VERIFY_TOKEN, '"}', limit * 2).encode("utf-8")
        try:
            st, body = http_post_chunked(fp, "/feishu/event", big,
                                         headers=feishu_headers(big, "f3"))
        except Exception as e:
            st, body = -1, "%s: %s" % (e.__class__.__name__, e)
        check(pfx + "F3 飞书分块 2×上限（请求里一个 Content-Length 都没有）⇒ 413",
              st == 413, "status=%d body=%s 发出字节=%d" % (st, body[:60], len(big)))

        d_over = pad_json('{"conversationId":"cid_d1","text":"should-not-enter-', '"}', limit + 1)
        st, body = http_post("http://127.0.0.1:%d/dingtalk/in" % dp, d_over,
                             headers=ding_headers(d_over))
        check(pfx + "D1 钉钉超限(签名正确) ⇒ 413 且该 marker 零副作用",
              st == 413 and settle(2.0, "should-not-enter"),
              "status=%d body=%s" % (st, body[:70]))

        st, body = http_post("http://127.0.0.1:%d/dingtalk/in" % dp, d_over)
        check(pfx + "D2 钉钉超限 + 不签名 ⇒ 仍是 401（上限不许抢在鉴权门前面）",
              st == 401 and "signature mismatch" in body, "status=%d body=%s" % (st, body[:60]))

        w_over = pad_json('{"conversationId":"cid_w1","text":"should-not-enter-', '"}', limit + 1)
        st, body = http_post("http://127.0.0.1:%d/webhook/in" % wp, w_over)
        check(pfx + "W1 webhook 超限 ⇒ 413 且零副作用",
              st == 413 and settle(2.0, "should-not-enter"), "status=%d body=%s" % (st, body[:60]))

        w_ok = '{"conversationId":"cid_w2","text":"p30b-w2-marker-%d"}' % n
        st, body = http_post("http://127.0.0.1:%d/webhook/in" % wp, w_ok)
        ok_w2 = st == 200 and wait_until(lambda: llm_saw("p30b-w2-marker-%d" % n), 25)
        check(pfx + "W2 webhook 正常尺寸 ⇒ 200 且 marker 真进了 LLM（这个面没被门打死）",
              ok_w2, "status=%d body=%s llm_hits=%d" % (st, body[:40], len(LLM_HITS)))

        st, body = http_post("http://127.0.0.1:%d/bot/steer" % cp, "x" * (limit + 1))
        check(pfx + "H1 控制台 /bot/steer 超限 ⇒ 413", st == 413,
              "status=%d body=%s" % (st, body[:70]))

        st, body = http_post("http://127.0.0.1:%d/bot/steer" % cp, "x" * limit)
        check(pfx + "H2 控制台正好上限 ⇒ 200", st == 200, "status=%d body=%s" % (st, body[:40]))

        # ---- N1：连打三条超限后，正常链路仍完整 ----
        for i in range(3):
            bad = pad_json('{"token":"%s","pad":"' % FAKE_VERIFY_TOKEN, '"}', limit + 1 + i)
            http_post("http://127.0.0.1:%d/feishu/event" % fp, bad,
                      headers=feishu_headers(bad, "n1-%d" % i))
        marker = "p30b-n1-marker-%d" % n
        ev = json.dumps({"token": FAKE_VERIFY_TOKEN,
                         "event": {"sender_id": "ou_p30b", "text": marker,
                                   "chat_id": "oc_n1", "chat_type": "p2p"}})
        st, body = http_post("http://127.0.0.1:%d/feishu/event" % fp, ev,
                             headers=feishu_headers(ev, "n1"))
        ok_n1 = st == 200 and wait_until(lambda: llm_saw(marker)
                                         or any(marker in r["body"] for r in im_records()), 25)
        check(pfx + "N1 三条超限之后，正常事件仍 200 且走完 总线→LLM→出站", ok_n1,
              "status=%d body=%s 出站条数=%d" % (st, body[:40], len(im_records())))
    finally:
        gw.terminate()
        gw.close()


# ===== 卫生 / 杠④ 口径 =====

def home_snapshot():
    entries = sorted(os.listdir(REAL_HOME))
    return {"n": len(entries),
            "config_md5": md5(os.path.join(REAL_HOME, "config.properties")),
            "state_md5": md5(os.path.join(REAL_HOME, "state.db"))}


def hygiene(before):
    alive = [g for g in SPAWNED if g.alive()]
    check("H1 我起的 JVM 全部收尸", not alive, "仍在跑=%s" % [g.tag for g in alive])
    still = []
    for g in SPAWNED:
        for p in list(g.ports.values()):
            for l in lsof_port(p):
                still.append("%d:%s" % (p, l.split()[0] + "/" + l.split()[1]))
    check("H2 我占过的端口全部释放（lsof 复扫）", not still, "残留=%s" % (still or "无"))
    mine = {}
    for pid in JVM_PIDS:
        r = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True)
        if (r.stdout or "").strip():
            mine[pid] = r.stdout.strip()
    check("H3 ps -o lstart 复核：没有我还活着的本次 JVM", not mine,
          "还活着=%s" % (mine or "无（%d 个 pid 全部退出）" % len(JVM_PIDS)))
    after = home_snapshot()
    check("H4 真数据根 ~/.zbot 未被触碰（条目数 + 两个 md5 都不变）",
          after["n"] == before["n"] and after["config_md5"] == before["config_md5"]
          and after["state_md5"] == before["state_md5"],
          "n=%d config=%s state=%s" % (after["n"], (after["config_md5"] or "")[:8],
                                       (after["state_md5"] or "")[:8]))
    long_tok = re.compile(r"[A-Za-z0-9_-]{100,}")
    assert long_tok.search("x" * 125), "尺坏了：125 字符合成串都不命中，0 命中就没有意义"
    hits, scanned = [], 0
    for root, _dirs, files in os.walk(OUT):
        for name in sorted(files):
            p = os.path.join(root, name)
            try:
                txt = io.open(p, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            scanned += 1
            if long_tok.search(txt):
                hits.append(os.path.relpath(p, OUT))
    check("H5 现场文件（含 round-* 目录）里没有 100+ 字符的类 key 长串",
          not hits, "扫了 %d 份，命中=%s；尺的阳性对照=125 字符合成串命中" % (scanned, hits or "无"))
    return after


def jar_is_p30b(jar):
    """读**构件字节**判断跑的是不是含本期代码的那版，不看 mtime（盘上有旧 jar）。"""
    import zipfile
    try:
        with zipfile.ZipFile(jar) as zf:
            names = set(zf.namelist())
            if "com/zifang/z/bot/channel/InboundLimits.class" not in names:
                return False
            blob = zf.read("com/zifang/z/bot/channel/FeishuChannel.class")
    except Exception as e:
        log("读 jar 失败：%s" % e.__class__.__name__)
        return False
    return b"request body too large" in blob


def build_if_needed():
    if os.path.isfile(JAR) and jar_is_p30b(JAR) and not os.environ.get("P30B_FORCE_BUILD"):
        log("jar 已是含本期字节的那版（InboundLimits.class + 'request body too large'）")
        return True
    log("jar 缺失或不含本期字节 ⇒ mvn -o package -DskipTests（日志 ~/.cache/zbot-p30b/e2e-build.log）")
    if not os.path.isdir(CACHE):
        os.makedirs(CACHE)
    with io.open(os.path.join(CACHE, "e2e-build.log"), "w", encoding="utf-8") as fh:
        rc = subprocess.run(["mvn", "-o", "-q", "package", "-pl", "z-bot-core", "-DskipTests"],
                            cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT).returncode
    if rc != 0 or not os.path.isfile(JAR):
        return False
    if not jar_is_p30b(JAR):
        log("FATAL: 构建 rc=0 但 jar 里仍找不到本期字节 ⇒ 量的不是这版代码")
        return False
    return True


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    rounds = int(os.environ.get("P30B_ROUNDS") or "3")
    before = home_snapshot()
    limit = read_limit()
    check("K1 上限常量从源码字节里读到且为 65_536（四对边界臂的尺寸来源）",
          limit == 65_536, "读到=%d 来自=%s" % (limit, os.path.relpath(LIMITS_SRC, ZBOT)))
    if limit != 65_536:
        print("FATAL: 尺寸都不是 65_536，后面的边界读数没有意义")
        return 2
    if not build_if_needed():
        print("FATAL: 起不了 jar（看 ~/.cache/zbot-p30b/e2e-build.log）")
        return 2
    fake_base, base_url = start_servers()
    log("假 IM %s ；假 LLM %s ；轮数=%d" % (fake_base, base_url, rounds))
    t0 = time.time()
    for n in range(1, rounds + 1):
        log("== 第 %d 轮 ==" % n)
        run_round(n, limit, fake_base, base_url)
    after = hygiene(before)
    passed = sum(1 for _n, ok, _d in RESULTS if ok)
    print("\n== 汇总 ==")
    print("PASS=%d FAIL=%d 轮数=%d 用时 %.1fs" % (passed, len(RESULTS) - passed, rounds,
                                                  time.time() - t0))
    for name, ok, detail in RESULTS:
        if not ok:
            print("  FAIL %s | %s" % (name, detail))
    log("现场: %s ；~/.zbot 条目 before/after=%d/%d" % (OUT, before["n"], after["n"]))
    return 0 if passed == len(RESULTS) and rounds >= 1 else 1


if __name__ == "__main__":
    sys.exit(main())
