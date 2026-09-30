#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P30c 真进程 E2E（杠③）：起**真 JVM** gateway，用真 HTTP 打飞书这一面，判本期两条裁定
在进程边界之外也成立：
  * D-P30-1 —— `GET /feishu/event?echostr=` 的门外回显已经拆掉，GET/PUT/DELETE 一律 405，
    攻击者可控的查询参数**一个字节都不回显**；
  * D-P30-2 —— 平铺 v1 的容错仍在（`event.text` 真投递），但它**只认代码里已读过的那几个键**，
    无出处的 `event.content` 不是读取点。

为什么单测不够（`FeishuChannelTest` 里那两支已经钉过）：
  1. 单测起的是同 JVM 的 HttpServer ⇒ 装配层（manifest 里飞书通道到底起没起、监听的是哪个端口）
     只有真 gateway 算数；
  2. 负向主张（"不回显""不投递"）在单测里判的是 bus 内存表；这里判的是**进程外两个观察口**
     （假 LLM 的上下文、假 IM 的出站字节），跟"回声"是同一层的东西；
  3. 构件身份：这一轮量的必须是**含本期字节的那版 jar** —— `FeishuChannel.class` 里
     `echostr` 出现 0 次，而同一次扫描在同一个 token 上必须看得见它（拿 `FeishuChannelTest.class`
     当合成猎物）。读不到字节一律 FATAL，不许"读失败"被读成"不含该串"。

每轮（`P30C_ROUNDS`，缺省 3）跑同一组判据，轮与轮之间不共享状态：
  G1  GET ?echostr=<本轮唯一 marker> ⇒ 405 + 响应里没有 marker + 该 marker 哪儿都没去
  G2  PUT / DELETE ⇒ 405（形状合同不只 GET：这一面唯一的入站形状是 POST）
  G3  POST url_verification（签名对、token 对）⇒ 200 且回显 challenge（门不是恒关的）
  G4  POST url_verification 错 token ⇒ 401 且 challenge 不回显（进程外这一层同样是"鉴权在前"）
  G5  平铺 v1 事件（`event.text`）⇒ 200 且 marker 真进 LLM/出站（D-P30-2 那条容错是活的）
  G6  平铺事件只带无出处的 `event.content` ⇒ 200（面活着）但 marker 零投递（边界）
  G7  v2 事件（`sender` + `message.content` 字符串化 JSON）⇒ 200 且 marker 投递
        （本期改动没碰坏有出处的那条主路）
  G8  控制台 `/bot/steer` 仍 200（本期只碰飞书面，别的门不许被牵连）

另有 `P30C_TEETH_PROBE=1` 一支**探针**（见 `teeth_probe`）：把被拆掉的旧形状临时装回去、
重建 jar、跑一轮，要求 G1 真的红 —— 负向判据没红过一次就是空跑。它同时是 `jar_identity()`
那把字节尺的活体阳性对照（注入后 jar 里的生产类必须数得出 `echostr`）。

红线：数据根 = `~/.cache/zbot-p30c/e2e/round-<n>/`（不写 /tmp：同机别的机会扫空 /tmp），
`ZBOT_HOME` 与 `--config-dir` 都指它；LLM 是进程内假端点，key 写 `stub-key-not-real`，
真 key 一个字节都不进本脚本；出站只指 127.0.0.1，真域名一个包都不发；端口一律 `bind(0)`。
本脚本**不 import 别的战役脚本**（量具之间不许互相借读数），只用标准库。

复算: python3 -u _doc/acceptance/p30c/p30c_e2e.py
      P30C_ROUNDS=1 python3 -u _doc/acceptance/p30c/p30c_e2e.py
      P30C_TEETH_PROBE=1 python3 -u _doc/acceptance/p30c/p30c_e2e.py   # 需要变异锁
"""
import hashlib
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
CLASS_IN_JAR = "com/zifang/z/bot/channel/FeishuChannel.class"
TEST_CLASS = os.path.join(ZBOT, "z-bot-core", "target", "test-classes",
                          "com", "zifang", "z", "bot", "channel", "FeishuChannelTest.class")
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p30c")
OUT = os.path.join(CACHE, "e2e")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")

STUB_KEY = "stub-key-not-real"
FAKE_APP_ID = "cli_stub_app_not_real"
FAKE_APP_SECRET = "cli_stub_secret_not_real"
FAKE_VERIFY_TOKEN = "cli_stub_p30c_verify_token"
FAKE_ENCRYPT_KEY = "cli_stub_p30c_encrypt_key"

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


def http_send(method, url, raw_body=b"", headers=None, timeout=30):
    """GET/PUT/DELETE 也走这条路：本期要打的就是非 POST 形状。"""
    body = raw_body.encode("utf-8") if isinstance(raw_body, str) else raw_body
    h = {"Content-Type": "application/json; charset=utf-8"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def http_post(url, raw_body, headers=None, timeout=30):
    return http_send("POST", url, raw_body, headers, timeout)


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


# ===== 独立实现侧：签名由 python hashlib 现算，不借 Java =====

def feishu_sign(ts, nonce, key, raw_body):
    blob = (ts + nonce + key).encode("utf-8") + (
        raw_body.encode("utf-8") if isinstance(raw_body, str) else raw_body)
    return hashlib.sha256(blob).hexdigest()


def feishu_headers(body, tag):
    ts = str(int(time.time()))
    nonce = "p30c-%s-%d-%d" % (tag, os.getpid(), int(time.time() * 1000) % 100000)
    return {"X-Lark-Request-Timestamp": ts, "X-Lark-Request-Nonce": nonce,
            "X-Lark-Signature": feishu_sign(ts, nonce, FAKE_ENCRYPT_KEY, body)}


# ===== 假端点：LLM 回显 + IM 记录（"入站真的被受理"的进程外观察口）=====

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
                                  "tenant_access_token": "t-p30c-fake", "expire": 3600})
        elif path.endswith("/im/v1/messages"):
            payload = json.dumps({"code": 0, "msg": "ok", "data": {"message_id": "om_p30c"}})
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
        body = "P30C-ECHO-%03d[%s]" % (seq, last[:120])
        payload = json.dumps({
            "id": "p30c-%d" % seq, "object": "chat.completion",
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
    不是"记录表为空"⇒ 不会被同一轮里正臂的余波污染）。
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
    # 只开 http（gateway 需要它）+ feishu（本期唯一的写域面）。钉钉/webhook 不在本期改动面上，
    # 它们的尺寸门由 p30b 那一杠量过，这里不重复借读数。
    return "\n".join([
        "channel.http.kind=http", "channel.http.enabled=true", "channel.http.outbound=false",
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
    ]) + "\n"


def start_gateway(tag, fake_base, base_url):
    if not base_url:
        raise SystemExit("FATAL: base_url 为空 ⇒ 假端点没起来，量具坏（不收这条读数）")
    root = write_profile(os.path.join(OUT, "round-%s" % tag))
    ports = {"console": free_port(), "webhook": free_port(), "feishu": free_port()}
    mpath = os.path.join(root, "channels-%s.properties" % tag)
    with io.open(mpath, "w", encoding="utf-8") as fh:
        fh.write(manifest_lines(fake_base, ports))
    gw = Gateway(tag, root, base_url, mpath, ports)
    gw.start()
    if not gw.listening_all():
        # 端口没起来就判 FATAL：飞书面没监听，下面的"405/不回显"全是空跑
        raise SystemExit("FATAL: %s 的 gateway 缺少监听 %s（看 %s）"
                         % (tag, gw.missing_listeners(), os.path.join(OUT, "jvm-%s.out" % tag)))
    return gw


# ===== 构件身份：字节级，且负向扫描自己要有牙 =====

def scan(path_or_zip, token, member=None):
    """返回 token 在该构件字节里出现的次数。读不到 = FATAL（不许把"读失败"读成"不含该串"）。"""
    if member is None:
        if not os.path.isfile(path_or_zip):
            raise SystemExit("FATAL: 量具读不到 %s ⇒ 身份判据不能空跑" % path_or_zip)
        blob = io.open(path_or_zip, "rb").read()
    else:
        import zipfile
        try:
            with zipfile.ZipFile(path_or_zip) as zf:
                blob = zf.read(member)
        except Exception as e:
            raise SystemExit("FATAL: 从 %s 里读 %s 失败（%s）—— 身份判据不许靠读失败蒙过 0 命中"
                             % (path_or_zip, member, e.__class__.__name__))
    return blob.count(token)


def jar_identity():
    """这一轮量的必须是"GET 回显已拆掉"的那版字节。

    单向的"不含 echostr"没有价值 ⇒ 同一次扫描配两个对照：
      * jar 内仍该在的串（`challenge` / `token mismatch` / `method not allowed`）必须命中，
        证明扫的是这个类的字节而不是空 blob；
      * 一个**真的含 `echostr`** 的构件（本期的测试类，方法与 URL 字面量里都有它）必须命中 ≥1，
        证明这把尺看得见这个 token。
    """
    echo = b"echostr"
    in_prod = scan(JAR, echo, CLASS_IN_JAR)
    pos = dict((t, scan(JAR, t, CLASS_IN_JAR)) for t in
               (b"challenge", b"token mismatch", b"method not allowed"))
    prey = scan(TEST_CLASS, echo)
    return in_prod, pos, prey


# ===== 一轮 =====

def run_round(n, fake_base, base_url):
    pfx = "R%d/" % n
    gw = start_gateway("r%d" % n, fake_base, base_url)
    fp, cp = gw.ports["feishu"], gw.ports["console"]
    ev_url = "http://127.0.0.1:%d/feishu/event" % fp
    try:
        # ---- G1/G2：非 POST 形状 ----
        marker = "p30c-r%d-echo-marker" % n
        st, body = http_send("GET", ev_url + "?echostr=" + marker)
        check(pfx + "G1 GET ?echostr= ⇒ 405 且攻击者可控串不回显、零副作用",
              st == 405 and marker not in body and settle(2.0, marker),
              "status=%d body=%s" % (st, body[:60]))

        st_put, body_put = http_send("PUT", ev_url + "?echostr=" + marker, b"{}")
        st_del, body_del = http_send("DELETE", ev_url)
        check(pfx + "G2 PUT / DELETE ⇒ 一律 405（这一面唯一的入站形状是 POST）",
              st_put == 405 and st_del == 405 and marker not in body_put,
              "put=%d delete=%d body=%s" % (st_put, st_del, body_put[:40]))

        # ---- G3/G4：POST 仍是活的，challenge 仍在 token 门之后 ----
        chal = json.dumps({"type": "url_verification", "token": FAKE_VERIFY_TOKEN,
                           "challenge": "p30c-r%d-chal" % n})
        st, body = http_post(ev_url, chal, headers=feishu_headers(chal, "g3-%d" % n))
        check(pfx + "G3 POST url_verification(token 对) ⇒ 200 + 回显 challenge（门不是恒关的）",
              st == 200 and ("p30c-r%d-chal" % n) in body, "status=%d body=%s" % (st, body[:60]))

        bad = json.dumps({"type": "url_verification", "token": "wrong-token",
                          "challenge": "p30c-r%d-attacker" % n})
        st, body = http_post(ev_url, bad, headers=feishu_headers(bad, "g4-%d" % n))
        check(pfx + "G4 POST url_verification(token 错) ⇒ 401 且 challenge 不回显",
              st == 401 and ("p30c-r%d-attacker" % n) not in body,
              "status=%d body=%s" % (st, body[:60]))

        # ---- G5：平铺 v1 容错仍活着（有出处的键：event.text）----
        flat_marker = "p30c-r%d-flat-marker" % n
        flat = json.dumps({"token": FAKE_VERIFY_TOKEN,
                           "event": {"sender_id": "ou_p30c", "text": flat_marker,
                                     "chat_id": "oc_flat", "chat_type": "p2p"}})
        st, body = http_post(ev_url, flat, headers=feishu_headers(flat, "g5-%d" % n))
        ok5 = st == 200 and wait_until(lambda: entered(flat_marker), 25)
        check(pfx + "G5 平铺 v1 event.text ⇒ 200 且 marker 真进 LLM/出站（容错是活的）",
              ok5, "status=%d body=%s llm=%d im=%d" % (st, body[:30], len(LLM_HITS), len(im_records())))

        # ---- G6：无出处的键不是读取点 ----
        loose = json.dumps({"token": FAKE_VERIFY_TOKEN,
                            "event": {"sender_id": "ou_p30c", "chat_id": "oc_loose",
                                      "chat_type": "p2p",
                                      "content": "p30c-r%d-loose-marker" % n}})
        st, body = http_post(ev_url, loose, headers=feishu_headers(loose, "g6-%d" % n))
        check(pfx + "G6 平铺 event.content（无出处）⇒ 200 但该 marker 一个字都不投递",
              st == 200 and settle(2.5, "p30c-r%d-loose-marker" % n),
              "status=%d body=%s" % (st, body[:40]))

        # ---- G7：有出处的主路（v2）没被本期改动碰坏 ----
        v2_marker = "p30c-r%d-v2-marker" % n
        v2 = json.dumps({"schema": "2.0",
                         "header": {"event_type": "im.message.receive_v1",
                                    "token": FAKE_VERIFY_TOKEN},
                         "event": {"sender": {"sender_id": {"open_id": "ou_p30c_v2"}},
                                   "message": {"chat_id": "oc_v2", "chat_type": "p2p",
                                               "message_type": "text",
                                               "content": json.dumps({"text": v2_marker})}}})
        st, body = http_post(ev_url, v2, headers=feishu_headers(v2, "g7-%d" % n))
        ok7 = st == 200 and wait_until(lambda: entered(v2_marker), 25)
        check(pfx + "G7 v2 事件（sender + message.content 字符串化 JSON）⇒ 200 且投递",
              ok7, "status=%d body=%s llm=%d im=%d" % (st, body[:30], len(LLM_HITS), len(im_records())))

        # 控制台面仍活着（本期只碰飞书，别的门不许被牵连）
        st, body = http_post("http://127.0.0.1:%d/bot/steer" % cp, "p30c-r%d-console" % n)
        check(pfx + "G8 控制台 /bot/steer 不受本期改动影响 ⇒ 200", st == 200,
              "status=%d body=%s" % (st, body[:40]))
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


def build_if_needed(force=False):
    if os.path.isfile(JAR) and not force and not os.environ.get("P30C_FORCE_BUILD"):
        try:
            if scan(JAR, b"method not allowed", CLASS_IN_JAR) >= 1:
                src = os.path.join(ZBOT, "z-bot-core", "src", "main", "java", "com", "zifang",
                                   "z", "bot", "channel", "FeishuChannel.java")
                if os.path.getmtime(JAR) >= os.path.getmtime(src):
                    log("jar 存在且不早于 FeishuChannel.java 的 mtime ⇒ 不重建")
                    return True
        except SystemExit:
            pass
    log("jar 缺失/陈旧%s ⇒ mvn -o -q package -pl z-bot-core -DskipTests"
        "（日志 ~/.cache/zbot-p30c/e2e-build.log）" % ("（探针强制重建）" if force else ""))
    if not os.path.isdir(CACHE):
        os.makedirs(CACHE)
    with io.open(os.path.join(CACHE, "e2e-build.log"), "w", encoding="utf-8") as fh:
        rc = subprocess.run(["mvn", "-o", "-q", "package", "-pl", "z-bot-core", "-DskipTests",
                             "-Dmaven.test.skip=true"],
                            cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT).returncode
    return rc == 0 and os.path.isfile(JAR)


# ===== 尺自己的牙：这一杠的负向判据（405 + 不回显）见过红吗？ =====

GET_ECHO_ANCHOR = (
    '            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {\n'
    '                text(ex, 405, "method not allowed");\n')

GET_ECHO_PREY = (
    '            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {\n'
    '                String q = ex.getRequestURI().getRawQuery();\n'
    '                String echostr = "";\n'
    '                if (q != null) {\n'
    '                    for (String pair : q.split("&")) {\n'
    '                        int eq = pair.indexOf(\'=\');\n'
    '                        if (eq > 0 && "echostr".equals(pair.substring(0, eq))) {\n'
    '                            echostr = java.net.URLDecoder.decode(pair.substring(eq + 1),\n'
    '                                    StandardCharsets.UTF_8);\n'
    '                        }\n'
    '                    }\n'
    '                }\n'
    '                text(ex, 200, echostr);\n'
    '                return;\n'
    '            }\n' + GET_ECHO_ANCHOR)

PROD_SRC = os.path.join(ZBOT, "z-bot-core", "src", "main", "java", "com", "zifang", "z",
                        "bot", "channel", "FeishuChannel.java")


def teeth_probe():
    """把被拆掉的那半轴临时装回去，看这一杠会不会红 —— 然后逐字节还原。

    为什么值得多花两次构建：G1/G6 是负向判据（"405""不回显""不投递"）。一整轮没红过的
    受跟踪探针 = 空跑；它现在至少被观测红过一次，且那次红的正是本期拆掉的那个形状。
    同时这里也是 `jar_identity()` 那把字节尺的**活体阳性对照**：注入后 `echostr` 必须
    在 jar 里的生产类字节上数得出来（不是拿测试类当替身）。

    纪律：抢同一把变异锁（抢不到就 rc=4 退出，绝不等待、绝不抢别人的）；注入前把原文读进内存
    并另存副本；还原只从这份副本写回（**绝不 `git checkout`** —— git 基线是 HEAD，不是我
    开始测量那一刻）；还原后 md5 对账；最后再重建一次，让盘上的 jar 回到真树。
    """
    import fcntl
    top = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--git-common-dir"],
                         stdout=subprocess.PIPE)
    common = top.stdout.decode("utf-8", "replace").strip()
    if not os.path.isabs(common):
        common = os.path.join(ZBOT, common)
    lock_path = os.path.join(common, "zbot-mutlock")
    fh = io.open(lock_path, "a+")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except IOError:
        print("FATAL: 变异锁被别的写手占着（%s）—— 探针不带等待，直接退出" % lock_path)
        return 4

    baseline_b = io.open(PROD_SRC, "rb").read()
    baseline = baseline_b.decode("utf-8")
    if baseline.count(GET_ECHO_ANCHOR) != 1:
        print("FATAL: 锚点出现 %d 次（期望 1）⇒ 代码已漂，探针不做"
              % baseline.count(GET_ECHO_ANCHOR))
        return 2
    backup = os.path.join(CACHE, "FeishuChannel.java.pre-probe")
    io.open(backup, "wb").write(baseline_b)
    ok = 0
    try:
        io.open(PROD_SRC, "w", encoding="utf-8").write(
            baseline.replace(GET_ECHO_ANCHOR, GET_ECHO_PREY, 1))
        log("已注入旧形状（%d 字节 → %d 字节）" % (len(baseline_b), os.path.getsize(PROD_SRC)))
        if not build_if_needed(force=True):
            print("FATAL: 注入后构建失败（看 ~/.cache/zbot-p30c/e2e-build.log）")
            return 2
        injected = scan(JAR, b"echostr", CLASS_IN_JAR)
        check("P1 字节尺的活体阳性对照：注入旧形状后 jar 内生产类数得出 echostr",
              injected >= 1, "注入后 prod_echostr=%d（真树那一跑读到的是 0）" % injected)
        fake_base, base_url = start_servers()
        log("== 探针轮（期望 G1 红）==")
        run_round(99, fake_base, base_url)
        g1 = [(n, o) for n, o, _d in RESULTS if n.endswith("/G1 GET ?echostr= ⇒ 405 且攻击者可控串不回显、零副作用")]
        check("P2 这一杠见过红：注入旧形状后 G1 必须 FAIL", bool(g1) and not g1[0][1],
              "G1 观测=%s" % ("FAIL（有牙）" if g1 and not g1[0][1] else "没红（尺是空的）"))
        g3 = [(n, o) for n, o, _d in RESULTS if n.endswith("/G3 POST url_verification(token 对) ⇒ 200 + 回显 challenge（门不是恒关的）")]
        check("P3 同一轮里正臂仍 PASS ⇒ G1 的红不是『整个面死了』读出来的", bool(g3) and g3[0][1],
              "G3 观测=%s" % ("PASS" if g3 and g3[0][1] else "FAIL"))
    finally:
        io.open(PROD_SRC, "wb").write(baseline_b)
        restored = io.open(PROD_SRC, "rb").read() == baseline_b
        log("还原=%s（md5 对账 %s，副本 %s）" % (restored, md5(PROD_SRC)[:12],
                                              os.path.relpath(backup, CACHE)))
        check("P4 探针还原后源文件与注入前逐字节相同", restored, "字节数=%d" % os.path.getsize(PROD_SRC))
        if not build_if_needed(force=True):
            print("FATAL: 还原后重建失败 ⇒ jar 与树不一致，别跑后面的量具")
            ok = 3
    passed = sum(1 for _n, o, _d in RESULTS if o)
    print("\n== 探针汇总 == PASS=%d/%d" % (passed, len(RESULTS)))
    return ok


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    if os.environ.get("P30C_TEETH_PROBE"):
        return teeth_probe()
    rounds = int(os.environ.get("P30C_ROUNDS") or "3")
    before = home_snapshot()
    if not build_if_needed():
        print("FATAL: 起不了 jar（看 ~/.cache/zbot-p30c/e2e-build.log）")
        return 2
    if not os.path.isfile(TEST_CLASS):
        raise SystemExit("FATAL: 没有 %s ⇒ 字节尺的阳性对照做不了，先 mvn test-compile"
                         % TEST_CLASS)
    in_prod, pos, prey = jar_identity()
    check("K1 构件身份：jar 内 FeishuChannel.class 的 echostr=0，"
          "而同一把尺在测试类里看得见它",
          in_prod == 0 and all(v >= 1 for v in pos.values()) and prey >= 1,
          "prod_echostr=%d 正向对照=%s 猎物(test类)=%d"
          % (in_prod, ",".join("%s=%d" % (k.decode(), v) for k, v in sorted(pos.items())), prey))
    if not (in_prod == 0 and all(v >= 1 for v in pos.values()) and prey >= 1):
        print("FATAL: 构件身份不成立 ⇒ 量的不是这版字节（或尺瞎了），不收后面的读数")
        return 2

    fake_base, base_url = start_servers()
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
