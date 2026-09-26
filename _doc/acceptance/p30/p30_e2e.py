#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P30 真进程 E2E（杠③）：起**真 JVM** gateway，用真 HTTP 打飞书/钉钉入站，判据一律落在
"跨过进程边界的东西"上 —— 入站被挡下 ⇒ 假端点一条出站都不许多；入站放行 ⇒ 假端点收到的
出站字节里带着那条消息的正文。

为什么不能只靠单测（同一份代码里已经有的 21+13 支）：
  1. 单测的签名/密文是**测试自己算的**。P18 就是这么把 SHA-1 当正确答案签了 12 支绿测试，
     连 `_doc/acceptance/p18/p18_e2e.py` 的 `feishu_inbound()` 也照着实现写成了 sha1 ——
     替身把实现的错误复制了一遍，量具就再也看不见它。这里签名与密文一律由
     **python hashlib + openssl** 现场算（两个独立实现），Java 侧只做"认/不认"。
  2. 装配层：manifest 的 `encrypt-key` / `inbound-secret` 到底有没有流进构造器，只有
     走 `--channel-manifest` 的真 JVM 才算数（p30_mutation.py 的 I5 就是打这一刀的）。

每轮（共 `P30_ROUNDS` 轮，缺省 3）都跑同样一组判据，轮与轮之间不共享状态：
  A1..A3 飞书：外部 key 签 / 代 SHA-1 摘要 / 一个签名头都不带 ⇒ 401 + 零出站
  A4      飞书：SHA-256 正确签名 + 明文事件 ⇒ 200 + 出站正文带该条标记
  A5      飞书：SHA-256 签名 + **AES-256-CBC 加密**的 v2 事件 ⇒ 200 + 出站正文带解密后的标记
  A6      飞书：签名正确但密文是别人 key 加的 ⇒ 400 + 零出站（不许降级成"解不开就放行"）
  A7      飞书：加密的 url_verification ⇒ 回显**解出来**的那个 challenge
  A8      飞书：token 不对的 challenge ⇒ 不回显（先鉴权后回显）
  B1..B5 钉钉：正确入站签名 / 拿 webhook secret 签（配了 inbound-secret）/ 两小时前的旧时间戳
              / 缺头 ⇒ 依次 200+出站、401、401、401；B5 = B2..B4 三条零出站
  C1..C3 反向对照：**另起一个不配 encrypt-key / inbound-secret 的 JVM** ⇒ 未签名入站照样 200
              （门是配了密钥才关的；这一族防的是"改成恒验"那种过头修法）
  H1..H5 卫生：JVM 收尸、端口 lsof 复扫、`ps -o lstart` 复核、真数据根 ~/.zbot 不变（杠④ 口径）、
              现场文件递归扫 100+ 字符类 key 长串（带 125 字符合成串的阳性对照）

红线：数据根 = `~/.cache/zbot-p30/e2e/round-<n>/`（不写 /tmp：同机别的机会扫空 /tmp），
`ZBOT_HOME` 与 `--config-dir` 都指它；LLM 是进程内假端点，key 写 `stub-key-not-real`，
真 key 一个字节都不进本脚本；出站只指 127.0.0.1，真域名一个包都不发；端口一律 `bind(0)`。

复算: python3 -u _doc/acceptance/p30/p30_e2e.py            # 3 轮
      P30_ROUNDS=1 python3 -u _doc/acceptance/p30/p30_e2e.py
"""
import base64
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p30")
OUT = os.path.join(CACHE, "e2e")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")

STUB_KEY = "stub-key-not-real"
FAKE_APP_ID = "cli_stub_app_not_real"
FAKE_APP_SECRET = "cli_stub_secret_not_real"
FAKE_VERIFY_TOKEN = "cli_stub_p30_verify_token"
FAKE_ENCRYPT_KEY = "cli_stub_p30_encrypt_key"
DING_TOKEN = "stub_ding_access_token_not_real"
DING_WEBHOOK_SECRET = "SECstub_ding_webhook_sign_not_real"
DING_INBOUND_SECRET = "SECstub_ding_inbound_sign_not_real"

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


def http_get(url, timeout=15):
    try:
        with urllib.request.urlopen(urllib.request.Request(url), timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def http_post(url, raw_body, headers=None, timeout=25):
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


# ===== 独立实现侧：签名与密文都由本机工具算，不借 Java =====

def feishu_sign(ts, nonce, key, raw_body):
    """飞书事件订阅：sha256((ts+nonce+encrypt_key) 的 UTF-8 字节 + 原始 body 字节)。"""
    return hashlib.sha256((ts + nonce + key).encode("utf-8") + raw_body.encode("utf-8")).hexdigest()


def feishu_sign_sha1(ts, nonce, key, raw_body):
    """P18 那版实现用的错摘要 —— 只当猎物用（它必须被拒）。"""
    return hashlib.sha1((ts + nonce + key + raw_body).encode("utf-8")).hexdigest()


def aes_encrypt(plain, key_str):
    """AES-256-CBC：key = sha256(encrypt_key)，IV 前置到密文，PKCS#7 —— 由 openssl CLI 完成。"""
    key_hex = hashlib.sha256(key_str.encode("utf-8")).hexdigest()
    iv = os.urandom(16)
    data = plain.encode("utf-8")
    pad = 16 - (len(data) % 16)
    data = data + bytes(bytearray([pad] * pad))
    proc = subprocess.run(["openssl", "enc", "-aes-256-cbc", "-K", key_hex,
                           "-iv", iv.hex(), "-e", "-nopad"],
                          input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("FATAL: openssl 加密失败（量具坏了，不收读数）: %s"
                         % proc.stderr.decode("utf-8", "replace")[:200])
    return base64.b64encode(iv + proc.stdout).decode("ascii")


def ding_sign(ts, key):
    """钉钉入站/出站同式：base64(HMAC-SHA256(key, ts + "\\n" + key))。"""
    digest = hmac.new(key.encode("utf-8"), ("%s\n%s" % (ts, key)).encode("utf-8"),
                      hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


# ===== 假 IM 端点（飞书 token/发送 + 钉钉 webhook 共用一个进程内 server）=====

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
        rec = {"t": time.time(), "method": "POST", "path": path, "raw_target": self.path,
               "headers": {k.lower(): str(v) for k, v in self.headers.items()},
               "body": raw.decode("utf-8", "replace")}
        with IM_LOCK:
            IM_RECORDS.append(rec)      # 到达即入账（在飞窗口不许被量具自己吃掉）
        if path.endswith("/auth/v3/tenant_access_token/internal"):
            payload = json.dumps({"code": 0, "msg": "ok",
                                  "tenant_access_token": "t-p30-fake", "expire": 3600})
        elif path.endswith("/im/v1/messages"):
            payload = json.dumps({"code": 0, "msg": "ok", "data": {"message_id": "om_p30"}})
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
    """把最后一条 user 消息**回显**出去 ⇒ 出站字节里带着入站正文，链路才叫打通。"""
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        msgs = req.get("messages") or []
        last = ""
        for m in reversed(msgs):
            if m.get("role") == "user":
                last = m.get("content") or ""
                break
        with LLM_LOCK:
            LLM_HITS.append({"t": time.time(), "user": last})
            seq = len(LLM_HITS)
        body = "P30-ECHO-%03d[%s]" % (seq, last[:120])
        payload = json.dumps({
            "id": "p30-%d" % seq, "object": "chat.completion",
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
    fake_base = "http://127.0.0.1:%d/open-apis" % im.server_address[1]
    base_url = "http://127.0.0.1:%d/v1" % llm.server_address[1]
    return im, llm, fake_base, base_url


def im_records(suffix=""):
    with IM_LOCK:
        return [r for r in IM_RECORDS if r.get("path", "").endswith(suffix)]


def im_clear():
    with IM_LOCK:
        IM_RECORDS[:] = []


# ===== 真 JVM gateway =====

class Gateway(object):
    def __init__(self, tag, profile, base_url, manifest, port):
        self.tag = tag
        self.profile = profile
        self.http_port = port
        self.logpath = os.path.join(OUT, "jvm-%s.out" % tag)
        self.logfile = io.open(self.logpath, "w", encoding="utf-8")
        env = dict(os.environ)
        env.pop("ZBOT_MODEL", None)
        env.update({"ZBOT_HOME": profile,
                    "Z_BOT_BASE_URL": base_url,
                    "Z_BOT_API_KEY": STUB_KEY,
                    "Z_BOT_MODEL": "stub-model",
                    "Z_BOT_PROVIDER": "stub"})
        self.env = env
        self.cmd = ["java", "-jar", JAR, "gateway", "--config-dir", profile,
                    "--port", str(self.http_port), "--webhook-port", str(free_port()),
                    "--channel-manifest", manifest]
        self.proc = None
        SPAWNED.append(self)

    def start(self):
        self.proc = subprocess.Popen(self.cmd, stdout=self.logfile, stderr=subprocess.STDOUT,
                                     env=self.env, cwd=self.profile)
        JVM_PIDS.append(self.proc.pid)
        log("%s fork pid=%d profile=%s console=%d" % (self.tag, self.proc.pid,
                                                     os.path.basename(self.profile), self.http_port))

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def listening(self, port, tries=200):
        for _ in range(tries):
            if not self.alive():
                return 0
            if lsof_pid_port(self.proc.pid, port):
                return 1
            time.sleep(0.25)
        return 0

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


def manifest_lines(fake_base, feishu_port, ding_port, with_keys=True):
    lines = ["channel.http.kind=http", "channel.http.enabled=true", "channel.http.outbound=false",
             "channel.webhook.kind=webhook", "channel.webhook.enabled=false",
             "channel.feishu.kind=feishu", "channel.feishu.enabled=true",
             "channel.feishu.outbound=true",
             "channel.feishu.default-port=%d" % feishu_port,
             "channel.feishu.requires=app-id,app-secret",
             "channel.feishu.config.api-base=%s" % fake_base,
             "channel.feishu.config.receive-id-type=chat_id",
             "channel.feishu.config.app-id=%s" % FAKE_APP_ID,
             "channel.feishu.config.app-secret=%s" % FAKE_APP_SECRET,
             "channel.feishu.config.verification-token=%s" % FAKE_VERIFY_TOKEN,
             "channel.dingtalk.kind=dingtalk", "channel.dingtalk.enabled=true",
             "channel.dingtalk.outbound=true",
             "channel.dingtalk.default-port=%d" % ding_port,
             "channel.dingtalk.requires=webhook-url",
             "channel.dingtalk.config.webhook-url=%s/robot/send?access_token=%s"
             % (fake_base, DING_TOKEN),
             "channel.dingtalk.config.secret=%s" % DING_WEBHOOK_SECRET]
    if with_keys:
        # 这两行就是本期新接的键：配了才关，工厂不传的话这里就是空话（p30_mutation I5 打的就是它）
        lines.append("channel.feishu.config.encrypt-key=%s" % FAKE_ENCRYPT_KEY)
        lines.append("channel.dingtalk.config.inbound-secret=%s" % DING_INBOUND_SECRET)
    return "\n".join(lines) + "\n"


def start_gateway(tag, fake_base, base_url, with_keys=True):
    if not base_url:
        # 挂上假 LLM 的地址是"reply 能出栈"的前提；带 None 起 JVM 会打出一条与本期无关的红，
        # 与其让它去猜，不如当场判量具坏。
        raise SystemExit("FATAL: base_url 为空 ⇒ 假端点没起来，量具坏（不收这条读数）")
    root = write_profile(os.path.join(OUT, "round-%s" % tag))
    feishu_port, ding_port, console = free_port(), free_port(), free_port()
    mpath = os.path.join(root, "channels-%s.properties" % tag)
    with io.open(mpath, "w", encoding="utf-8") as fh:
        fh.write(manifest_lines(fake_base, feishu_port, ding_port, with_keys))
    gw = Gateway(tag, root, base_url, mpath, console)
    gw.ports = {"feishu": feishu_port, "dingtalk": ding_port, "console": console}
    gw.start()
    if not gw.listening(console):
        raise SystemExit("FATAL: %s 的 gateway 没起来（看 %s）" % (tag, gw.logpath))
    return gw


# ===== 入站请求构造（一律用独立实现算签名/密文）=====

_NONCE = [0]


def feishu_event_body(conv, marker, token=FAKE_VERIFY_TOKEN):
    chat_type, chat_id = conv.split(":", 1)
    return json.dumps({"token": token,
                       "event": {"sender_id": "ou_p30", "text": marker,
                                 "chat_id": chat_id, "chat_type": chat_type}})


def feishu_v2_encrypted_body(chat_id, marker, key=FAKE_ENCRYPT_KEY, chat_type="p2p",
                             token=FAKE_VERIFY_TOKEN, bad_key=False):
    """v2.0 事件（token 在 header、正文在 message.content 的字符串化 JSON 里），整份 AES 加密。"""
    inner_content = json.dumps({"text": marker})
    plain = json.dumps({"schema": "2.0",
                        "header": {"event_id": "evt_p30", "event_type": "im.message.receive_v1",
                                   "token": token},
                        "event": {"sender": {"sender_id": {"open_id": "ou_p30_v2"}},
                                  "message": {"message_id": "om_p30", "chat_id": chat_id,
                                              "chat_type": chat_type, "message_type": "text",
                                              "content": inner_content}}})
    blob = aes_encrypt(plain, DING_INBOUND_SECRET if bad_key else key)
    return json.dumps({"encrypt": blob}), plain


def post_feishu(port, raw_body, ts=None, nonce=None, key=FAKE_ENCRYPT_KEY, sign="correct"):
    headers = {}
    if sign:
        _NONCE[0] += 1
        ts = ts or str(int(time.time()))
        nonce = nonce or ("p30-n%d-%d" % (os.getpid(), _NONCE[0]))
        sig = (feishu_sign(ts, nonce, key, raw_body) if sign == "correct"
               else feishu_sign_sha1(ts, nonce, key, raw_body))
        headers = {"X-Lark-Request-Timestamp": ts, "X-Lark-Request-Nonce": nonce,
                   "X-Lark-Signature": sig}
    return http_post("http://127.0.0.1:%d/feishu/event" % port, raw_body, headers=headers)


def post_ding(port, conv, marker, ts=None, key=DING_INBOUND_SECRET, sign=True):
    body = json.dumps({"conversationId": conv, "senderId": "staff_p30", "text": marker})
    ts = ts or str(int(time.time() * 1000))
    headers = {"timestamp": ts, "sign": ding_sign(ts, key)} if sign else {}
    return http_post("http://127.0.0.1:%d/dingtalk/in" % port, body, headers=headers)


def outbound_contains(marker, seconds=25):
    """等假 IM 端点收到带该标记的出站字节（跨第二个进程边界的判据）。"""
    return wait_until(lambda: any(marker in r["body"] for r in im_records()), seconds)


def quiet(seconds=4.0, suffix=""):
    """负向臂的观察窗：等这么久，假端点仍一条都没收到，才叫"挡下"。

    为什么不能"看一眼就走"：HTTP 的 401 是同步返回的，而"没挡住"的那条消息要走
    总线→LLM→出站，落地比响应晚一拍 —— 立刻读 IM_RECORDS 必然为空，断言就成了结构上
    的空跑（同一份代码里 A4/B1 那两条正臂证明确实有东西会落在这里）。
    为什么不是"把 timeout 拉长"糊过去：断言的对象是 **absence**，absence 没有因果信号
    可等，有界观察窗就是它唯一诚实的去竞态方式。
    """
    time.sleep(seconds)
    return not im_records(suffix)


# ===== 一轮 =====

def run_round(n, fake_base, base_url):
    pfx = "R%d/" % n
    gw = start_gateway("r%d" % n, fake_base, base_url, with_keys=True)
    fp, dp = gw.ports["feishu"], gw.ports["dingtalk"]
    try:
        # ---- A 族：飞书入站鉴真 ----
        im_clear()
        st, body = post_feishu(fp, feishu_event_body("p2p:oc_a1", "p30-a1-should-not-enter"),
                              key="someone-elses-encrypt-key")
        q = quiet()
        check(pfx + "A1 外部 key 签的飞书事件 401 且零出站",
              st == 401 and "signature mismatch" in body and q,
              "status=%d body=%s 出站=%d" % (st, body[:60], len(im_records())))

        st, body = post_feishu(fp, feishu_event_body("p2p:oc_a2", "p30-a2-should-not-enter"),
                              sign="sha1")
        q = quiet()
        check(pfx + "A2 代 SHA-1 摘要（P18 的错算法）必须被拒",
              st == 401 and "signature mismatch" in body and q,
              "status=%d body=%s 出站=%d" % (st, body[:60], len(im_records())))

        st, body = post_feishu(fp, feishu_event_body("p2p:oc_a3", "p30-a3-should-not-enter"),
                              sign=None)
        q = quiet()
        check(pfx + "A3 一个签名头都不带 ⇒ fail-closed 401",
              st == 401 and q, "status=%d 出站=%d" % (st, len(im_records())))

        im_clear()
        st, body = post_feishu(fp, feishu_event_body("p2p:oc_a4", "p30a4marker"))
        ok4 = st == 200 and outbound_contains("p30a4marker")
        rec = [r for r in im_records("/im/v1/messages")]
        check(pfx + "A4 正确 SHA-256 签名放行 ⇒ 回复真的发到了假飞书端点",
              ok4, "status=%d body=%s 出站条数=%d" % (st, body[:40], len(rec)))

        im_clear()
        raw_env, plain = feishu_v2_encrypted_body("oc_a5", "p30a5decryptedmarker")
        st, body = post_feishu(fp, raw_env)
        sent = [r["body"] for r in im_records("/im/v1/messages")]
        check(pfx + "A5 加密 v2 事件：验签→AES 解开→header.token→投递，正文字符级出现在出站字节里",
              st == 200 and outbound_contains("p30a5decryptedmarker"),
              "status=%d body=%s 出站=%d 明文前 60=%s" % (st, body[:40], len(sent), plain[:60]))

        im_clear()
        raw_env, _p = feishu_v2_encrypted_body("oc_bad", "p30-a-should-not-enter", bad_key=True)
        st, body = post_feishu(fp, raw_env)   # 签名正确、密文是别人 key 加的
        q = quiet()
        check(pfx + "A6 签名对但解不开的密文 ⇒ 400 且零出站（不许降级成'未授权也能过'）",
              st == 400 and "decrypt failed" in body and q,
              "status=%d body=%s 出站=%d" % (st, body[:60], len(im_records())))

        im_clear()
        chal_plain = json.dumps({"schema": "2.0",
                                 "header": {"event_type": "url_verification",
                                            "token": FAKE_VERIFY_TOKEN},
                                 "type": "url_verification", "challenge": "p30-chal-777"})
        raw_env = json.dumps({"encrypt": aes_encrypt(chal_plain, FAKE_ENCRYPT_KEY)})
        st, body = post_feishu(fp, raw_env)
        check(pfx + "A7 加密的 url_verification ⇒ 回显**解出来**的那个 challenge",
              st == 200 and body.strip() == json.dumps({"challenge": "p30-chal-777"}),
              "status=%d body=%s" % (st, body[:80]))

        st, body = http_post("http://127.0.0.1:%d/feishu/event" % fp,
                             json.dumps({"type": "url_verification", "token": "wrong",
                                         "challenge": "p30-attacker-echo"}),
                             headers={"X-Lark-Request-Timestamp": str(int(time.time())),
                                      "X-Lark-Request-Nonce": "p30-n-token",
                                      "X-Lark-Signature": feishu_sign(
                                          str(int(time.time())), "p30-n-token", FAKE_ENCRYPT_KEY,
                                          json.dumps({"type": "url_verification", "token": "wrong",
                                                      "challenge": "p30-attacker-echo"}))})
        check(pfx + "A8 token 不对的 challenge 不许回显（先鉴权后回显这个顺序在真 JVM 里成立）",
              st == 401 and "p30-attacker-echo" not in body, "status=%d body=%s" % (st, body[:80]))

        # ---- B 族：钉钉入站验签 ----
        im_clear()
        st, body = post_ding(dp, "cid_b1", "p30b1marker")
        ok1 = st == 200 and outbound_contains("p30b1marker")
        check(pfx + "B1 正确入站签名放行 ⇒ 回复真的发到了假钉钉 webhook",
              ok1, "status=%d body=%s 出站=%d" % (st, body[:40], len(im_records())))
        # B1 自己那条出站必须清空后再数 B2..B4：否则"零出站"会被上一条正臂的记录污染（或反过来，
        # 让正臂的余波被读成"门没挡住"）。
        im_clear()

        st, body = post_ding(dp, "cid_b2", "p30-b2-should-not-enter", key=DING_WEBHOOK_SECRET)
        check(pfx + "B2 拿 webhook 加签密钥签的必须拒（manifest 里 inbound-secret 真的接上了）",
              st == 401 and "signature mismatch" in body, "status=%d body=%s" % (st, body[:60]))

        stale = str(int(time.time() * 1000) - 2 * 3600 * 1000)
        st, body = post_ding(dp, "cid_b3", "p30-b3-should-not-enter", ts=stale)
        check(pfx + "B3 两小时前的旧时间戳 + 对该 ts 正确的签名 ⇒ 窗口挡下",
              st == 401, "status=%d body=%s" % (st, body[:60]))

        st, body = post_ding(dp, "cid_b4", "p30-b4-should-not-enter", sign=False)
        check(pfx + "B4 缺 timestamp/sign 头 ⇒ fail-closed 401",
              st == 401 and "signature mismatch" in body, "status=%d body=%s" % (st, body[:60]))
        q = quiet()
        check(pfx + "B5 B2..B4 三条未授权入站零出站", q,
              "robot/send 出站=%d 全部出站=%d" % (len(im_records("/robot/send")), len(im_records())))
    finally:
        gw.terminate()
        gw.close()

    # ---- C 族：反向对照（另起一个不配两把入站密钥的 JVM）----
    gw2 = start_gateway("r%dc" % n, fake_base, base_url, with_keys=False)
    try:
        im_clear()
        st, body = http_post("http://127.0.0.1:%d/feishu/event" % gw2.ports["feishu"],
                             feishu_event_body("p2p:oc_c1", "p30c1marker"))
        ok_c1 = st == 200 and outbound_contains("p30c1marker")
        check(pfx + "C1 未配 encrypt-key：未签名的飞书事件照样进得来（门不是恒关的）",
              ok_c1, "status=%d body=%s" % (st, body[:40]))
        im_clear()
        st, body = http_post("http://127.0.0.1:%d/dingtalk/in" % gw2.ports["dingtalk"],
                             json.dumps({"conversationId": "cid_c2", "senderId": "u",
                                         "text": "p30c2marker"}))
        check(pfx + "C2 未配 inbound-secret：钉钉未签名入站照样进得来",
              st == 200 and outbound_contains("p30c2marker"), "status=%d body=%s" % (st, body[:40]))
        # 同一把密钥的回退：只配 secret 时，用 secret 签必须收
        im_clear()
        ts = str(int(time.time() * 1000))
        st, body = http_post("http://127.0.0.1:%d/dingtalk/in" % gw2.ports["dingtalk"],
                             json.dumps({"conversationId": "cid_c3", "senderId": "u",
                                         "text": "p30c3marker"}),
                             headers={"timestamp": ts, "sign": ding_sign(ts, DING_WEBHOOK_SECRET)})
        check(pfx + "C3 只配 secret 时按 secret 验签必须收（inbound-secret 的回退半轴）",
              st == 200 and outbound_contains("p30c3marker"), "status=%d body=%s" % (st, body[:40]))
    finally:
        gw2.terminate()
        gw2.close()


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
    # 假绿自查：本脚本自己的现场不许留着 125 字符长串（真 minimax key 就是这个量级）
    # 与 p18_e2e.py 同一把尺、同一个阳性对照：0 命中只有在校验过"这把尺认得 125 字符合成串"
    # 之后才是证据，否则它可能只是根本没跑。
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


def jar_contains(jar, entry, needle):
    """读**构件字节**判断它是不是本期这版代码，不看 mtime。

    为什么不用 mtime：盘上留着一个 2026-09-26 的旧 jar，mtime 一样"像新的"，
    而杠③要的判据是"跑的是含 decryptEvent 的那版"。旧 jar 会让 A5/A6/A7 红成
    "产品坏"，实际上量具量的是上一个版本 —— 这是最坏的一种假红（会把结论记进 EVIDENCE）。
    """
    try:
        import zipfile
        with zipfile.ZipFile(jar) as zf:
            blob = zf.read(entry)
    except Exception as e:
        log("读 jar 里的 %s 失败：%s" % (entry, e.__class__.__name__))
        return False
    return needle.encode("utf-8") in blob


def jar_is_p30(jar):
    """三条都指向本期新加的字节：解密的失败分支、钉钉入站守卫的失败分支、窗口常量所在的方法名。"""
    checks = [("com/zifang/z/bot/channel/FeishuChannel.class", "decrypt failed"),
              ("com/zifang/z/bot/channel/FeishuChannel.class", "decryptEvent"),
              ("com/zifang/z/bot/channel/DingTalkChannel.class", "verifyInbound")]
    return all(jar_contains(jar, e, n) for e, n in checks)


def build_if_needed():
    if os.path.isfile(JAR) and jar_is_p30(JAR) and not os.environ.get("P30_FORCE_BUILD"):
        log("jar 已是含本期代码的那版（构件字节校验：decryptEvent / verifyInbound）%s mtime=%s"
            % (JAR, time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(JAR)))))
        return True
    log("jar 缺失**或不含本期字节** ⇒ mvn -o package -DskipTests（日志 ~/.cache/zbot-p30/e2e-build.log）")
    with io.open(os.path.join(CACHE, "e2e-build.log"), "w", encoding="utf-8") as fh:
        rc = subprocess.run(["mvn", "-o", "-q", "package", "-pl", "z-bot-core", "-DskipTests"],
                            cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT).returncode
    if rc != 0 or not os.path.isfile(JAR):
        return False
    # 建完必须自己复核：构建"成功"但构件里没有本期字节 = 量的还是旧版
    if not jar_is_p30(JAR):
        log("FATAL: 构建 rc=0 但 jar 里仍找不到 decryptEvent/verifyInbound ⇒ 量的不是这版代码")
        return False
    return True


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    rounds = int(os.environ.get("P30_ROUNDS") or "3")
    before = home_snapshot()
    if not build_if_needed():
        print("FATAL: 起不了 jar（看 ~/.cache/zbot-p30/e2e-build.log）")
        return 2
    _im, _llm, fake_base, base_url = start_servers()
    log("假 IM %s ；假 LLM %s ；轮数=%d" % (fake_base, base_url, rounds))
    t0 = time.time()
    for n in range(1, rounds + 1):
        log("== 第 %d 轮 ==" % n)
        run_round(n, fake_base, base_url)
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
