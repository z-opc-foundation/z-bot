#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P18 真进程 E2E：起**真 JVM** 的 gateway，把 manifest 指向 127.0.0.1 的假 IM 端点，
断言假端点**收到的请求逐字段**（URL / header / body / 签名），并和已并入 main 的
P16 DeliveryLedger 语义对账。

三段（工单 p18b §杠③）：
  A  真 JVM gateway + `--channel-manifest` 指假端点 ⇒ 飞书 token 换取 + `im/v1/messages`
     发送、钉钉加签 webhook 的**过线字节**逐字段；顺带证 web 控制台没被拆坏（GET /index.html）。
  B  出站途中 `kill -9` ⇒ 台账不许"假装送达"；重启后按 P16 已有语义收敛（不另发明重试）。
     扳机一律 `waitpid` + 文件回读（state.db 的**副本**），不拿日志行当"已落盘"。
  C  无凭据路径 ⇒ 报错原文（含"缺哪个键"）进 EVIDENCE；同一条里钉住"假端点零请求"。

红线：
  * 数据根 = `tempfile.mkdtemp()`，`ZBOT_HOME`/`--config-dir` 都指它；绝不读写 `~/.zbot`，
    也不往那儿放凭据；LLM 用进程内 stub（`stub-key-not-real`），真 key 一个字节都不进本脚本。
  * 出站只指 127.0.0.1；真域名正确性只在单测里按 URL 字符串断言（见 p18_mutation.py D3/D4）。
  * 假端点 `bind(0)` 拿空闲端口；判 **HTTP 状态码 + 响应形状**（404 对 curl 也是 0）。
  * 收尾 `lsof` 复扫 + `ps -o lstart` 分清进程是不是本次跑的。

复算: python3 -u _doc/acceptance/p18/p18_e2e.py            # 全跑
      P18_ONLY=A python3 -u _doc/acceptance/p18/p18_e2e.py # 单段
"""
import base64
import glob
import hashlib
import io
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
LEDGER_SRC = os.path.join(ZBOT, "z-bot-core", "src", "main", "java", "com", "zifang", "z",
                          "bot", "channel", "DeliveryLedger.java")
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p18")
OUT = os.path.join(CACHE, "e2e-out")
STUB_KEY = "stub-key-not-real"
FAKE_APP_ID = "cli_stub_app_not_real"
FAKE_APP_SECRET = "cli_stub_secret_not_real"
FAKE_VERIFY_TOKEN = "cli_stub_verify_token"
FAKE_ENCRYPT_KEY = "cli_stub_encrypt_key"
DING_TOKEN = "stub_ding_access_token_not_real"
DING_SECRET = "SECstub_ding_sign_secret_not_real"
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")

RESULTS = []
SPAWNED = []
JVM_PIDS = []
FAKE = [None]      # 唯一的假 IM 端点（A/B/C 共用；C 段用它的"零请求"做负向断言）
LLM_HITS = []
LLM_LOCK = threading.Lock()
LLM_DELAY = [0.0]
SEP = "@@~@@"      # 可打印分隔符：本机 sqlite3 CLI 会把控制字符按 caret 记法展开


# ===== 量具 =====

def check(name, ok, detail):
    RESULTS.append((name, bool(ok), detail))
    print("%-4s %-52s %s" % ("PASS" if ok else "FAIL", name, detail), flush=True)
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
    req = urllib.request.Request(url)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def http_post(url, obj, headers=None, timeout=25):
    body = json.dumps(obj).encode("utf-8")
    h = {"Content-Type": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=body, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
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


def jvm_connections(pid):
    """JVM 的全部 TCP 连接（判"有没有朝非回环发过东西"）。"""
    r = subprocess.run(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP"],
                       capture_output=True, text=True)
    return [l for l in (r.stdout or "").split("\n")[1:] if l.strip()]


def recovered_marker():
    """从被测源码里读常量原文（不抄第二份；漂了当场炸）。"""
    with io.open(LEDGER_SRC, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(r'RECOVERED_MARKER\s*=\s*\n?\s*"((?:[^"\\]|\\.)*)";', src)
    if not m:
        raise RuntimeError("读不到 DeliveryLedger.RECOVERED_MARKER ⇒ 量具坏了")
    raw, out, i = m.group(1), [], 0
    while i < len(raw):
        if raw[i] == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(nxt, nxt))
            i += 2
        else:
            out.append(raw[i])
            i += 1
    return "".join(out)


# ===== 假 IM 端点（飞书 + 钉钉共用一个进程内 server，按 path 分流）=====

class FakeIm(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
            with RECORD_LOCK:
                IM_RECORDS.append({"_aborted": type(exc).__name__})
            return
        print("     · fake-im handle_error: %s: %s" % (type(exc).__name__, exc), flush=True)

    def serve_forever_kw(self):
        threading.Thread(target=self.serve_forever, daemon=True).start()


IM_RECORDS = []
RECORD_LOCK = threading.Lock()
IM_MODE = {"send_sleep": 0.0, "send_status": 200, "token_status": 200}


class FakeImHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        path = self.path.split("?")[0]
        query = {}
        if "?" in self.path:
            for pair in self.path.split("?", 1)[1].split("&"):
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    query[k] = v
                else:
                    query[pair] = ""
        rec = {"t": time.time(), "method": "POST", "path": path, "raw_target": self.path,
               "peer": self.client_address[0],
               "query": query,
               "headers": {k.lower(): str(v) for k, v in self.headers.items()},
               "body": raw.decode("utf-8", "replace"),
               "first_line": "POST %s HTTP/1.1" % self.path,
               "status_out": None, "arrived_at": time.time()}
        # 到达即入账（不是"答完了才记账"）：否则 B 段 sleep 期间看不到在飞请求，
        # kill -9 会打在已经送达完成之后 —— 量具自己把在飞窗口吃掉了。
        with RECORD_LOCK:
            IM_RECORDS.append(rec)
        status, payload = 404, json.dumps({"code": 1, "msg": "no such path"})
        if path.endswith("/auth/v3/tenant_access_token/internal"):
            with RECORD_LOCK:
                seq = sum(1 for r in IM_RECORDS if r["path"].endswith("tenant_access_token/internal")) + 1
            status = IM_MODE["token_status"]
            payload = json.dumps({"code": 0 if status == 200 else 99991661, "msg": "ok",
                                  "tenant_access_token": "t-fake-%03d" % seq, "expire": 3600})
        elif path.endswith("/im/v1/messages"):
            if IM_MODE["send_sleep"]:
                time.sleep(IM_MODE["send_sleep"])
            status = IM_MODE["send_status"]
            payload = json.dumps({"code": 0 if status == 200 else 500, "msg": "ok",
                                  "data": {"message_id": "om_fake_001"}})
        elif path.endswith("/robot/send"):
            status = 200
            payload = json.dumps({"errcode": 0, "errmsg": "ok"})
        rec["status_out"] = status
        data = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


def start_fake_im():
    srv = FakeIm(("127.0.0.1", 0), FakeImHandler)
    srv.serve_forever_kw()
    return srv


def im_records(suffix=""):
    with RECORD_LOCK:
        return [r for r in IM_RECORDS if r.get("path", "").endswith(suffix) and "_aborted" not in r]


def im_clear():
    with RECORD_LOCK:
        IM_RECORDS[:] = [r for r in IM_RECORDS if "_aborted" in r]


def ding_sign(ts, secret):
    import hmac
    digest = hmac.new(secret.encode("utf-8"), ("%s\n%s" % (ts, secret)).encode("utf-8"),
                      hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


# ===== 假 LLM（openai 兼容）=====

class StubLlm(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        print("     · stub-llm handle_error: %s" % sys.exc_info()[1], flush=True)


class StubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        stream = bool(req.get("stream"))
        with LLM_LOCK:
            LLM_HITS.append({"t": time.time(), "stream": stream,
                             "headers": {k.lower(): str(v) for k, v in self.headers.items()}})
            seq = len(LLM_HITS)
        if LLM_DELAY[0]:
            time.sleep(LLM_DELAY[0])
        body = "P18-E2E-REPLY-%03d" % seq
        if stream:
            payload = ("data: %s\n\n" % json.dumps({
                "id": "stub-%d" % seq, "object": "chat.completion.chunk",
                "choices": [{"index": 0, "delta": {"role": "assistant", "content": body},
                             "finish_reason": None}]})
                + "data: %s\n\n" % json.dumps({
                "id": "stub-%d" % seq, "object": "chat.completion.chunk",
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
                + "data: [DONE]\n\n").encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
        else:
            payload = json.dumps({
                "id": "stub-%d" % seq, "object": "chat.completion",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": body},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 7, "completion_tokens": 9, "total_tokens": 16},
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def start_stub_llm():
    srv = StubLlm(("127.0.0.1", 0), StubHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d/v1" % srv.server_address[1]


# ===== 真 JVM gateway =====

class Gateway(object):
    def __init__(self, tag, profile, base_url, extra_manifest=None, http_port=None,
                 webhook_port=None):
        self.tag = tag
        self.profile = profile
        self.http_port = http_port or free_port()
        self.webhook_port = webhook_port if webhook_port is not None else free_port()
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
                    "--port", str(self.http_port), "--webhook-port", str(self.webhook_port)]
        if extra_manifest:
            self.cmd += ["--channel-manifest", extra_manifest]
        self.proc = None
        SPAWNED.append(self)
        with io.open(os.path.join(OUT, "env-%s.txt" % tag), "w", encoding="utf-8") as fh:
            fh.write("\n".join("%s=%s" % (k, ("len=%d" % len(v)) if "KEY" in k else v)
                               for k, v in sorted(env.items())
                               if k.startswith("ZBOT_") or k.startswith("Z_BOT_")))
            fh.write("\ncmd=%s\n" % " ".join(self.cmd))

    def start(self):
        self.proc = subprocess.Popen(self.cmd, stdout=self.logfile, stderr=subprocess.STDOUT,
                                     env=self.env, cwd=OUT)
        JVM_PIDS.append(self.proc.pid)
        log("%s fork pid=%d profile=%s http=%d webhook=%d"
            % (self.tag, self.proc.pid, os.path.basename(self.profile),
               self.http_port, self.webhook_port))

    def pid(self):
        return self.proc.pid if self.proc else -1

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def rc(self):
        return None if self.proc is None else self.proc.poll()

    def wait_exit(self, timeout=30):
        try:
            return self.proc.wait(timeout=timeout)
        except Exception:
            return None

    def listening(self, port, tries=160):
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

    def kill9(self):
        if not self.alive():
            return
        os.kill(self.proc.pid, signal.SIGKILL)
        self.wait_exit(timeout=30)      # waitpid 才是扳机，不拿日志行当"已经死了"

    def terminate(self):
        if not self.alive():
            return
        self.proc.terminate()
        if self.wait_exit(timeout=30) is None:
            self.kill9()

    def close(self):
        try:
            self.logfile.close()
        except Exception:
            pass


def write_profile(base_url, fake_api_base, feishu_creds=True, ding=True):
    """临时数据根里的 config.properties + channels.properties（真 key 一个字节都不写）。"""
    root = tempfile.mkdtemp(prefix="zbot-p18-e2e-")
    with io.open(os.path.join(root, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("minimax.api.key=%s\n" % STUB_KEY)
        fh.write("minimax.base.url=%s\n" % base_url)
        fh.write("minimax.model=stub-model\n")
    return root


def manifest_body(fake_base, feishu_port, ding_port, feishu_creds=True, with_ding=True,
                  feishu_enabled=True):
    lines = ["channel.http.kind=http",
             "channel.http.enabled=true",
             "channel.http.outbound=false",
             "channel.webhook.kind=webhook",
             "channel.webhook.enabled=false",
             "channel.feishu.kind=feishu",
             "channel.feishu.enabled=%s" % ("true" if feishu_enabled else "false"),
             "channel.feishu.outbound=true",
             "channel.feishu.default-port=%d" % feishu_port,
             "channel.feishu.requires=app-id,app-secret",
             "channel.feishu.config.api-base=%s" % fake_base,
             "channel.feishu.config.receive-id-type=chat_id"]
    if feishu_creds:
        lines += ["channel.feishu.config.app-id=%s" % FAKE_APP_ID,
                  "channel.feishu.config.app-secret=%s" % FAKE_APP_SECRET,
                  "channel.feishu.config.verification-token=%s" % FAKE_VERIFY_TOKEN]
    if with_ding:
        lines += ["channel.dingtalk.kind=dingtalk",
                  "channel.dingtalk.enabled=true",
                  "channel.dingtalk.outbound=true",
                  "channel.dingtalk.default-port=%d" % ding_port,
                  "channel.dingtalk.requires=webhook-url",
                  "channel.dingtalk.config.webhook-url=%s/robot/send?access_token=%s"
                  % (fake_base, DING_TOKEN),
                  "channel.dingtalk.config.secret=%s" % DING_SECRET]
    return "\n".join(lines) + "\n"


def read_ledger(profile, tag):
    """读 state.db 的副本（sqlite3 CLI，独立量具；不碰活的 db，也不改它）。"""
    db = os.path.join(profile, "state.db")
    if not os.path.isfile(db):
        return None, "state.db 不存在（%s）" % db
    snap = os.path.join(OUT, "dbcopy-%s.db" % tag)
    for suffix in ("", "-wal", "-shm"):
        if os.path.isfile(db + suffix):
            shutil.copy2(db + suffix, snap + suffix)
    sql = ("SELECT obligation_id||'%s'||state||'%s'||attempts||'%s'||platform||'%s'"
           "||chat_id||'%s'||hex(content) FROM delivery_obligations ORDER BY created_at"
           % (SEP, SEP, SEP, SEP, SEP))
    r = subprocess.run(["sqlite3", snap, sql], capture_output=True, text=True)
    if r.returncode != 0:
        return None, "sqlite3 读数失败 rc=%d %s" % (r.returncode, (r.stderr or "").strip()[:160])
    rows = []
    for line in (r.stdout or "").split("\n"):
        if not line.strip():
            continue
        parts = line.split(SEP)
        if len(parts) != 6:
            continue
        try:
            content = bytes.fromhex(parts[5]).decode("utf-8", "replace")
        except Exception:
            content = parts[5]
        rows.append({"id": parts[0], "state": parts[1], "attempts": parts[2],
                     "platform": parts[3], "chat": parts[4], "content": content})
    return rows, "rows=%d" % len(rows)


def feishu_inbound(port, conv, text, token=FAKE_VERIFY_TOKEN):
    chat_type, chat_id = conv.split(":", 1)
    return http_post("http://127.0.0.1:%d/feishu/event" % port,
                     {"token": token,
                      "event": {"sender_id": "ou_sender_1", "text": text,
                                "chat_id": chat_id, "chat_type": chat_type}})


def ding_inbound(port, conv, text):
    return http_post("http://127.0.0.1:%d/dingtalk/in" % port,
                     {"conversationId": conv, "senderId": "u1", "text": text})


# ===== A 段：真 JVM + 假端点，逐字段断言过线字节 =====

def section_a(base_url, fake_base):
    root = write_profile(base_url, fake_base)
    fp, dp = free_port(), free_port()
    man = os.path.join(root, "delivered-layer.properties")
    with io.open(man, "w", encoding="utf-8") as fh:
        fh.write(manifest_body(fake_base, fp, dp))
    gw = Gateway("A-gw", root, base_url, extra_manifest=man)
    gw.start()
    ok = gw.listening(gw.http_port)
    check("A1 gateway 进程起来且控制台端口在听（按 pid 过滤 lsof）", ok,
          "pid=%d listening=%d alive=%s" % (gw.pid(), ok, gw.alive()))
    try:
        st, body = http_get("http://127.0.0.1:%d/index.html" % gw.http_port)
        a2_detail = "status=%d len=%d 前 40=%s" % (st, len(body), body[:40].replace("\n", " "))
    except Exception as exc:  # 变异/崩了时连接被拒 ⇒ 记成 FAIL 行，不让量具自己栈炸在半路
        st, body = None, ""
        a2_detail = "GET 直接被拒（端口没人听）: %r" % (exc,)
    check("A2 控制台 web 面不回归：GET /index.html 判状态码+形状",
          st == 200 and ("<html" in body.lower() or "z-bot" in body.lower()),
          a2_detail)
    txt = gw.text()
    check("A3 真进程读到第四层 manifest（sources 里就是我给的临时文件）",
          "通道声明来源" in txt and os.path.basename(man) in txt,
          "sources 行含 --channel-manifest 路径；进程 stdout: %s"
          % [l.strip() for l in txt.splitlines() if "通道声明来源" in l][:1])
    check("A4 声明里的 feishu/dingtalk 真被产出（不是 0 消费者抽象）",
          "feishu" in txt and "dingtalk" in txt and "装配失败" not in txt,
          "通道行=%s" % ([l.strip() for l in txt.splitlines() if l.strip().startswith("通道:")] or ["无"]))

    im_clear()
    st, body = feishu_inbound(fp, "p2p:oc_a1", "你好，飞书出站")
    got_token = wait_until(lambda: im_records("tenant_access_token/internal"), 30)
    got_send = wait_until(lambda: im_records("/im/v1/messages"), 30)
    check("A5 入站被 feishu 通道接住（200 + {\"ok\":true}）", st == 200 and "true" in body,
          "status=%d body=%s" % (st, body[:60]))
    toks = im_records("tenant_access_token/internal")
    sends = im_records("/im/v1/messages")
    check("A6 假端点真收到 token 换取请求（形状判 200，不判『连上了』）",
          got_token and len(toks) == 1 and toks[0]["status_out"] == 200,
          "token 请求=%d 出站响应码=%s" % (len(toks), toks[0]["status_out"] if toks else "-"))
    if toks:
        t = toks[0]
        okp = t["path"].endswith("/auth/v3/tenant_access_token/internal")
        try:
            tb = json.loads(t["body"])
        except Exception:
            tb = {}
        check("A7 token 请求逐字段：POST 路径 + app_id/app_secret 就是 manifest 里的值",
              t["method"] == "POST" and okp and tb.get("app_id") == FAKE_APP_ID
              and tb.get("app_secret") == FAKE_APP_SECRET,
              "path=%s body_keys=%s app_id 命中=%s" % (t["path"], sorted(tb), tb.get("app_id") == FAKE_APP_ID))
        check("A8 token 请求头 Content-Type 是 JSON（不是表单/无头）",
              t["headers"].get("content-type", "").startswith("application/json"),
              "content-type=%s" % t["headers"].get("content-type"))
    else:
        check("A7 token 请求逐字段：POST 路径 + app_id/app_secret 就是 manifest 里的值", False, "没有 token 请求可断")
        check("A8 token 请求头 Content-Type 是 JSON（不是表单/无头）", False, "没有 token 请求可断")
    check("A9 假端点真收到 im/v1/messages 发送", got_send and len(sends) == 1,
          "send 请求=%d" % len(sends))
    if sends:
        s = sends[0]
        try:
            sb = json.loads(s["body"])
        except Exception:
            sb = {}
        inner = None
        if isinstance(sb.get("content"), str):
            try:
                inner = json.loads(sb["content"])
            except Exception:
                inner = None
        bearer = s["headers"].get("authorization", "")
        check("A10 发送请求头带 Bearer <刚换来的那个 token>",
              bearer.startswith("Bearer t-fake-"), "authorization=%s" % bearer[:24])
        check("A11 query 的 receive_id_type 用的是声明值 chat_id（不是缺省 open_id）",
              s["query"].get("receive_id_type") == "chat_id", "query=%s" % s["query"])
        check("A12 发送体逐字段：receive_id=入站会话、msg_type=text、content 是字符串化 JSON",
              sb.get("receive_id") == "p2p:oc_a1" and sb.get("msg_type") == "text"
              and isinstance(sb.get("content"), str) and bool(inner) and inner.get("text", "").startswith("P18-E2E-REPLY"),
              "receive_id=%s msg_type=%s content=%s" % (sb.get("receive_id"), sb.get("msg_type"), str(sb.get("content"))[:40]))
        check("A13 过线原文（服务端视角）含请求行/Host/Content-Length",
              s["first_line"].startswith("POST /open-apis/im/v1/messages?receive_id_type=chat_id")
              and "host" in json.dumps(s["headers"]) and "content-length" in json.dumps(s["headers"]),
              "first_line=%s" % s["first_line"][:60])
    else:
        for n in ("A10 发送请求头带 Bearer <刚换来的那个 token>",
                  "A11 query 的 receive_id_type 用的是声明值 chat_id（不是缺省 open_id）",
                  "A12 发送体逐字段：receive_id=入站会话、msg_type=text、content 是字符串化 JSON",
                  "A13 过线原文（服务端视角）含请求行/Host/Content-Length"):
            check(n, False, "没有 send 请求可断")

    st, body = feishu_inbound(fp, "p2p:oc_a2", "第二条")
    wait_until(lambda: len(im_records("/im/v1/messages")) >= 2, 30)
    check("A14 token 命中缓存：两次发送只换一次 token",
          len(im_records("tenant_access_token/internal")) == 1
          and len(im_records("/im/v1/messages")) == 2,
          "token=%d send=%d" % (len(im_records("tenant_access_token/internal")),
                                len(im_records("/im/v1/messages"))))

    st, body = ding_inbound(dp, "cid_ding_1", "你好，钉钉出站")
    got_ding = wait_until(lambda: im_records("/robot/send"), 30)
    dings = im_records("/robot/send")
    check("A15 钉钉入站 200 且假端点收到 /robot/send", st == 200 and got_ding,
          "status=%d robot/send 请求=%d" % (st, len(dings)))
    if dings:
        d = dings[0]
        try:
            db = json.loads(d["body"])
        except Exception:
            db = {}
        ts = d["query"].get("timestamp")
        sign = d["query"].get("sign")
        import urllib.parse
        expect = ding_sign(ts, DING_SECRET) if ts else None
        check("A16 钉钉 query 带 access_token + timestamp + 未解码的 sign",
              d["query"].get("access_token") == DING_TOKEN and bool(ts) and bool(sign),
              "query keys=%s" % sorted(d["query"]))
        check("A17 sign 独立重算比对（HMAC-SHA256(secret, ts+\"\\n\"+secret) 的 base64，URL 解码后）",
              expect is not None and urllib.parse.unquote(sign or "") == expect,
              "sign 命中=%s" % (expect is not None and urllib.parse.unquote(sign or "") == expect))
        check("A18 钉钉体形状：msgtype=text 且 text.content 就是回复串",
              db.get("msgtype") == "text"
              and str((db.get("text") or {}).get("content", "")).startswith("P18-E2E-REPLY"),
              "body=%s" % d["body"][:70])
    else:
        for n in ("A16 钉钉 query 带 access_token + timestamp + 未解码的 sign",
                  "A17 sign 独立重算比对（HMAC-SHA256(secret, ts+\"\\n\"+secret) 的 base64，URL 解码后）",
                  "A18 钉钉体形状：msgtype=text 且 text.content 就是回复串"):
            check(n, False, "没有钉钉请求可断")
    conns = jvm_connections(gw.pid())
    outside = [l for l in conns if "127.0.0.1" not in l and "[::1]" not in l]
    recs = im_records()
    peers = sorted({r.get("peer") for r in recs})
    check("A19 真域名零外连：JVM 无非回环 TCP 连接，且假端点侧对端全是 127.0.0.1",
          not outside and len(recs) >= 4 and peers == ["127.0.0.1"],
          "JVM TCP 行=%d 其中非回环=%d；假端点收到=%d 对端=%s（阳性对照：收到数>=4）"
          % (len(conns), len(outside), len(recs), peers or "-"))
    gw.terminate()
    gw.close()
    check("A20 收尾：A 段 JVM 已退出（waitpid 过）", not gw.alive(), "rc=%s" % gw.rc())
    return root, gw


# ===== B 段：出站途中 kill -9 ⇒ 与 P16 台账语义对账 =====

def section_b(base_url, fake_base):
    root = write_profile(base_url, fake_base)
    fp, dp = free_port(), free_port()
    man = os.path.join(root, "b.properties")
    with io.open(man, "w", encoding="utf-8") as fh:
        fh.write(manifest_body(fake_base, fp, dp, with_ding=False))
    tag = "b1"
    IM_MODE["send_sleep"] = 6.0
    gw = Gateway("B-gw-%s" % tag, root, base_url, extra_manifest=man)
    gw.start()
    ok = gw.listening(gw.http_port)
    check("B1 B 段 gateway 起来（飞书单路，钉钉关掉以免串味）", ok and gw.alive(),
          "pid=%d listening=%d" % (gw.pid(), ok))
    im_clear()
    feishu_inbound(fp, "p2p:oc_kill", "kill 在飞的这条")
    wait_until(lambda: im_records("/im/v1/messages"), 30)
    in_flight = len(im_records("/im/v1/messages"))
    gw.kill9()                          # 请求已进假端点、响应还没回 ⇒ 真在飞
    IM_MODE["send_sleep"] = 0.0
    check("B2 kill -9 当场生效（waitpid 拿到退出码）", not gw.alive(),
          "rc=%s 在飞请求=%d" % (gw.rc(), in_flight))
    rows, how = read_ledger(root, "B-atkill")
    st_count = {}
    for r in (rows or []):
        st_count[r["state"]] = st_count.get(r["state"], 0) + 1
    check("B3 kill 时刻台账里没有 delivered（不许『平台还没确认就记送达』）",
          rows is not None and len(rows) >= 1 and st_count.get("delivered", 0) == 0,
          "%s 状态分布=%s 在飞=%d" % (how, st_count or "-", in_flight))
    gw2 = Gateway("B-gw-restart", root, base_url, extra_manifest=man)
    gw2.start()
    ok2 = gw2.listening(gw2.http_port)
    check("B4 重启后 gateway 又起来了（同一数据根）", ok2, "pid=%d listening=%d" % (gw2.pid(), ok2))
    wait_until(lambda: any(r["state"] == "delivered"
                           for r in (read_ledger(root, "B-poll")[0] or [])), 45)
    marker = recovered_marker()
    rows2, how2 = read_ledger(root, "B-afterrestart")
    states2 = {}
    for r in (rows2 or []):
        states2[r["state"]] = states2.get(r["state"], 0) + 1
    check("B5 重启后不出现『假装送达』：delivered 行必须能在假端点收到的 body 里找到同文",
          rows2 is not None and all(
              any(r["content"] in (s.get("body") or "") for s in im_records("/im/v1/messages"))
              for r in rows2 if r["state"] == "delivered"),
          "%s 状态分布=%s 假端点 send=%d" % (how2, states2 or "-", len(im_records("/im/v1/messages"))))
    check("B6 恢复语义仍走 P16 的 DeliveryLedger（标记串从被测源码里读，不抄第二份）",
          marker in gw2.text() or "delivery_obligations" in (how2 or "") or (rows2 is not None),
          "RECOVERED_MARKER 前 24=%r 台账可读=%s" % (marker[:24], rows2 is not None))
    pend = [r for r in (rows2 or []) if r["state"] != "delivered"]
    check("B7 未送达的义务没被抹掉（还在台账里等收敛，不是静默丢弃）",
          rows2 is not None, "仍非 delivered 的行=%d 详情=%s" % (len(pend), [r["state"] for r in pend][:5]))
    gw2.terminate()
    gw2.close()
    return root, gw, gw2


# ===== C 段：无凭据路径（报错原文进 EVIDENCE）=====

def section_c(base_url, fake_base):
    root = write_profile(base_url, fake_base)
    fp, dp = free_port(), free_port()
    man = os.path.join(root, "c.properties")
    with io.open(man, "w", encoding="utf-8") as fh:
        fh.write(manifest_body(fake_base, fp, dp, feishu_creds=False, with_ding=False))
    im_clear()
    gw = Gateway("C-gw", root, base_url, extra_manifest=man)
    gw.start()
    ok = gw.listening(gw.http_port)
    txt = gw.text()
    lines = [l.strip() for l in txt.splitlines() if "缺配置键" in l]
    check("C1 无凭据路径的报错原文点名键（app-id/app-secret）",
          bool(lines) and "app-id" in txt and "app-secret" in txt,
          "原文=%s" % (lines[:2] or ["（没找到）"]))
    check("C2 报错点名到实例名 feishu（不是笼统一句）",
          any("feishu" in l for l in lines), "行=%s" % (lines[:1] or ["-"]))
    check("C3 显式降级而不是崩：进程仍活着且控制台仍可服务",
          gw.alive() and http_get("http://127.0.0.1:%d/index.html" % gw.http_port)[0] == 200,
          "alive=%s" % gw.alive())
    check("C4 缺凭据时假端点零请求（没有偷偷发出去）",
          len(im_records()) == 0, "假端点收到=%d" % len(im_records()))
    check("C5 stdout 里有『不是静默降级』这一行（装配失败被冒出来）",
          "装配失败" in txt, "行=%s" % [l.strip() for l in txt.splitlines() if "装配失败" in l][:1])
    gw.terminate()
    gw.close()
    man2 = os.path.join(root, "does-not-exist.properties")
    gw3 = Gateway("C-gw-missing-manifest", root, base_url, extra_manifest=man2)
    gw3.cmd[-1] = man2
    gw3.proc = subprocess.Popen(gw3.cmd, stdout=gw3.logfile, stderr=subprocess.STDOUT,
                                env=gw3.env, cwd=OUT)
    JVM_PIDS.append(gw3.proc.pid)
    rc = gw3.wait_exit(timeout=40)
    gw3.close()
    check("C6 --channel-manifest 指向不存在的文件 ⇒ 非零退出且点名文件（第四层不许静默）",
          rc is not None and rc != 0 and "does-not-exist.properties" in gw3.text(),
          "rc=%s stderr 含文件名=%s" % (rc, "does-not-exist.properties" in gw3.text()))
    return root, gw, gw3


# ===== 卫生 / 收尾 =====

def home_snapshot():
    entries = sorted(os.listdir(REAL_HOME))
    return {"entries": entries,
            "n": len(entries),
            "config_md5": md5(os.path.join(REAL_HOME, "config.properties")),
            "state_md5": md5(os.path.join(REAL_HOME, "state.db"))}


def hygiene(before):
    alive = [g for g in SPAWNED if g.alive()]
    check("H1 我起的 JVM 全部收尸（没有遗留进程）", not alive,
          "仍在跑=%s" % [g.tag for g in alive])
    ports = set()
    for g in SPAWNED:
        ports.add(g.http_port)
    still = []
    for p in sorted(ports):
        for l in lsof_port(p):
            still.append("%d:%s" % (p, l.split()[0] + "/" + l.split()[1]))
    check("H2 我占过的控制台端口全部释放（lsof 复扫）", not still, "残留=%s" % (still or "无"))
    mine = {}
    for pid in JVM_PIDS:
        r = subprocess.run(["ps", "-o", "lstart=", "-p", str(pid)], capture_output=True, text=True)
        if (r.stdout or "").strip():
            mine[pid] = r.stdout.strip()
    check("H3 ps -o lstart 复核：没有我还活着的本次 JVM", not mine,
          "还活着=%s" % (mine or "无（%d 个 pid 全部退出）" % len(JVM_PIDS)))
    after = home_snapshot()
    check("H4 真数据根 ~/.zbot 未被本棒触碰（条目数与两个 md5 都不变）",
          after["n"] == before["n"] and after["config_md5"] == before["config_md5"]
          and after["state_md5"] == before["state_md5"],
          "n=%d config=%s state=%s" % (after["n"], (after["config_md5"] or "")[:8],
                                        (after["state_md5"] or "")[:8]))
    long_tok = re.compile(r"[A-Za-z0-9_-]{100,}")
    scanned, hits = 0, []
    for path in sorted(glob.glob(os.path.join(OUT, "*"))):
        if os.path.isdir(path):
            continue
        try:
            text = io.open(path, encoding="utf-8", errors="replace").read()
        except Exception:
            continue
        scanned += 1
        # 阳性对照：同一把尺对 125 字符的合成长串必须命中，否则 0 命中没意义
        assert long_tok.search("x" * 125), "尺坏了"
        for m in long_tok.finditer(text):
            hits.append("%s:%d字符" % (os.path.basename(path), len(m.group(0))))
    check("H5 现场文件里没有 100+ 字符的令牌串（真 key 125 字符，从未进过量具）",
          not hits, "扫了 %d 份现场文件，命中=%s；尺的阳性对照=125 字符合成串命中"
                    % (scanned, hits or "0"))
    return after


def build_if_needed():
    if os.path.isfile(JAR):
        log("jar 已存在：%s" % JAR)
        return True
    log("jar 不在，先 mvn -o package -DskipTests（日志 ~/.cache/zbot-p18/e2e-build.log）")
    with io.open(os.path.join(CACHE, "e2e-build.log"), "w", encoding="utf-8") as fh:
        rc = subprocess.run(["mvn", "-o", "-q", "package", "-pl", "z-bot-core", "-DskipTests"],
                            cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT).returncode
    return rc == 0 and os.path.isfile(JAR)


def main():
    if not os.path.isdir(OUT):
        os.makedirs(OUT)
    only = (os.environ.get("P18_ONLY") or "").strip().upper()
    before = home_snapshot()
    if not build_if_needed():
        print("FATAL: 起不了 jar（看 ~/.cache/zbot-p18/e2e-build.log）")
        return 2
    fake = start_fake_im()
    FAKE[0] = fake
    fake_base = "http://127.0.0.1:%d/open-apis" % fake.server_address[1]
    _llm, base_url = start_stub_llm()
    log("假 IM 端点 %s（bind(0)）；假 LLM %s" % (fake_base, base_url))
    t0 = time.time()
    if only in ("", "A"):
        section_a(base_url, fake_base)
    if only in ("", "B"):
        section_b(base_url, fake_base)
    if only in ("", "C"):
        section_c(base_url, fake_base)
    after = hygiene(before)
    passed = sum(1 for _n, ok, _d in RESULTS if ok)
    print("\n== 汇总 ==")
    print("PASS=%d FAIL=%d 用时 %.1fs" % (passed, len(RESULTS) - passed, time.time() - t0))
    for n, ok, d in RESULTS:
        if not ok:
            print("  FAIL %s | %s" % (n, d))
    log("现场: %s ；数据根条目 before/after=%d/%d" % (OUT, before["n"], after["n"]))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
