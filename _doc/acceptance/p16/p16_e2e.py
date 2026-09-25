#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P16（送达台账 / 死目标 / turn lease / 监管者）验收③：真进程 E2E。

本期 roadmap §5 的硬要求是「投递途中 kill -9」——这件事在进程内单测里造不出真现场，
必须真 JVM + 真 SQLite + 真 SIGKILL。四段全是真跑：

  A 投递在飞时 kill -9 ⇒ 盘上账还在 ⇒ 重启后按状态语义重投且台账收敛
    真 `java -jar z-bot-core.jar gateway --config-dir <临时 profile>`，LLM 用 stub；
    24 条会话同时进（reply 同时回），首条回执一进 webhook 队列就 SIGKILL；
    量盘用 **sqlite3 CLI 读 state.db 的副本**（独立量具，不认产品自己的日志/端点），
    并与产品自己的 GET /bot/status 对拍。重启后逐条核对：
      · kill 时 pending 的行 ⇒ 重投正文不带 ♻️ 标记
      · kill 时 attempting/failed 的行 ⇒ 重投正文必须带且只带一层 ♻️ 标记
      · 全部行收敛成 delivered；行数不减；每条 attempts 只 +1（没双烧预算）
  B 双实例同刻只有一个真发（两个 JVM + 同一 profile，两边日志都给）
  C 干净退出删掉自己那把锁 / 崩退出把锁留在盘上 ⇒ 下一个实例陈旧锁自愈；
    没拿到锁的那个实例收尾时不许删别人的锁
  D ~/.zbot 前后一字不变（只取「文件名清单 + 整文件 md5」，不读内容、不打印任何值）
  X 凭证卫生：产物里只许出现 stub-key-not-real；探测器先用哨兵自证咬得住（负向断言要有猎物）

红线：全程 `--config-dir` / `ZBOT_HOME` 指临时目录；key 只有 stub-key-not-real；
不读写 ~/.zbot 的内容（杠④/D 段只算摘要），真 key 的值不进任何产物、不打印。

前置: mvn -o -pl z-bot-core package -DskipTests
复算: python3 -u _doc/acceptance/p16/p16_e2e.py
"""
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
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
LEDGER_SRC = os.path.join(ZBOT, "z-bot-core", "src", "main", "java", "com", "zifang", "z",
                          "bot", "channel", "DeliveryLedger.java")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")
OUT = os.path.join(HERE, "out")
LOGS = os.path.join(HERE, "logs")
STUB_KEY = "stub-key-not-real"

RESULTS = []
SPAWNED = []
LLM_HITS = []
LLM_LOCK = threading.Lock()
LLM_DELAY = [0.0]


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


def lsof_listening(pid, port):
    """按 pid 过滤：邻居在别的端口/别的 pid 上的监听一律不算证据。"""
    r = subprocess.run(["lsof", "-nP", "-a", "-p", str(pid), "-iTCP:%d" % port, "-sTCP:LISTEN"],
                       capture_output=True, text=True)
    lines = [l for l in (r.stdout or "").split("\n")[1:] if l.strip()]
    return len(lines), r.returncode


def recovered_marker():
    """从被测源码里读 RECOVERED_MARKER 常量原文（不抄第二份，漂了当场炸）。"""
    with io.open(LEDGER_SRC, encoding="utf-8") as fh:
        src = fh.read()
    m = re.search(r'RECOVERED_MARKER\s*=\s*\n?\s*"((?:[^"\\]|\\.)*)";', src)
    if not m:
        raise RuntimeError("读不到 DeliveryLedger.RECOVERED_MARKER ⇒ 量具坏了")
    # Java 字面量里只有 \n \" \\ 这一类转义；手工还原，别用 unicode_escape（会把中文/emoji 打碎）
    raw = m.group(1)
    out, i = [], 0
    while i < len(raw):
        if raw[i] == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(nxt, nxt))
            i += 2
        else:
            out.append(raw[i])
            i += 1
    return "".join(out)


MARKER = recovered_marker()

# 分隔符必须是**可打印**串：本机实测 sqlite3 CLI 会把控制字符按 caret 记法打出来
# （0x1f → 两个 ASCII 字符 "^_"），拿 \x1f 当分隔符 ⇒ split 出来是 6 段假形状。
SEP = "@@~@@"


def read_ledger(profile, tag):
    """sqlite3 CLI 读 state.db 的**副本**（独立量具；不碰活的 db 文件，也不改它）。"""
    db = os.path.join(profile, "state.db")
    if not os.path.isfile(db):
        return None, "state.db 不存在（%s）" % db
    snap = os.path.join(OUT, "dbcopy-%s.db" % tag)
    for suffix in ("", "-wal", "-shm"):
        src = db + suffix
        if os.path.isfile(src):
            shutil.copy2(src, snap + suffix)
    sql = ("SELECT obligation_id||'%s'||state||'%s'||attempts||'%s'||platform||'%s'"
           "||chat_id||'%s'||hex(content) FROM delivery_obligations ORDER BY created_at"
           % (SEP, SEP, SEP, SEP, SEP))
    r = subprocess.run(["sqlite3", snap, sql], capture_output=True, text=True)
    if r.returncode != 0:
        return None, "sqlite3 读数失败 rc=%d %s" % (r.returncode, (r.stderr or "").strip()[:160])
    rows = {}
    for line in (r.stdout or "").split("\n"):
        line = line.strip()
        if not line:
            continue
        parts = line.split(SEP)
        if len(parts) != 6:
            return None, "行形状不对: %r" % line[:120]
        oid, state, attempts, platform, chat_id, hexc = parts
        try:
            content = bytes.fromhex(hexc).decode("utf-8")
        except Exception as e:
            return None, "hex 解不出: %s (%s)" % (line[:60], e)
        rows[chat_id] = dict(oid=oid, state=state, attempts=int(attempts),
                             platform=platform, content=content)
    return rows, "sqlite3 读到 %d 行（副本 %s）" % (len(rows), os.path.basename(snap))




# ===== 假 LLM 网关（OpenAI 兼容；只为把「真跑一轮 agent」变成可观测的） =====

# 多线程：单线程的 HTTPServer 会把 24 条并发 turn 排成队，那样「在飞」这个现场就造不出来。
# （上一版这里手写 process_request_thread 却继承了 HTTPServer —— 那个方法只有 ThreadingMixIn
#   会调，等于没生效；日志里那几段 socketserver 的 Traceback 就是它漏底的证据。）
class StubServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError)):
            print("     · stub: 对端（被 kill 的 JVM）断链 %s" % type(exc).__name__, flush=True)
            return
        print("     · stub handle_error: %s: %s" % (type(exc).__name__, exc), flush=True)


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
        body = "P16-E2E-REPLY-%03d" % seq
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


def start_stub():
    srv = StubServer(("127.0.0.1", 0), StubHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d/v1" % srv.server_address[1]


# ===== gateway 进程 =====

class Gateway(object):
    def __init__(self, tag, profile, base_url, http_port, webhook_port):
        self.tag = tag
        self.profile = profile
        self.http_port = http_port
        self.webhook_port = webhook_port
        self.logpath = os.path.join(OUT, "%s.log" % tag)
        self.logfile = open(self.logpath, "w", encoding="utf-8")
        env = dict(os.environ)
        env.pop("ZBOT_MODEL", None)
        env.update({
            "ZBOT_HOME": profile,                 # 杠④：家目录改指临时 profile
            "Z_BOT_BASE_URL": base_url,
            "Z_BOT_API_KEY": STUB_KEY,            # 只允许这一把 key
            "Z_BOT_MODEL": "stub-model",
            "Z_BOT_PROVIDER": "stub",
        })
        self.env = env
        self.cmd = ["java", "-jar", JAR, "gateway", "--config-dir", profile,
                    "--port", str(http_port), "--webhook-port", str(webhook_port)]
        self.proc = None
        SPAWNED.append(self)
        # 凭证卫生的取证面：每个 JVM 真正带出去的环境变量（含 key 的那几条）落盘留档
        with open(os.path.join(OUT, "env-%s.txt" % tag), "w", encoding="utf-8") as fh:
            fh.write("\n".join("%s=%s" % (k, v) for k, v in sorted(self.env.items())
                               if k.startswith("ZBOT_") or k.startswith("Z_BOT_")
                               or k in ("HOME", "PATH")))
            fh.write("\ncmd=%s\n" % " ".join(self.cmd))

    def start(self):
        self.proc = subprocess.Popen(self.cmd, stdout=self.logfile, stderr=subprocess.STDOUT,
                                     env=self.env, cwd=OUT)
        log("%s 已 fork pid=%s profile=%s http=%d webhook=%d"
            % (self.tag, self.proc.pid, os.path.basename(self.profile),
               self.http_port, self.webhook_port))

    def pid(self):
        return self.proc.pid if self.proc else -1

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def listening(self, port, tries=200):
        """「起起来了」只按 pid 过滤 lsof 判，不认日志行、不认邻居的端口。"""
        for _ in range(tries):
            if not self.alive():
                return 0
            n, _rc = lsof_listening(self.proc.pid, port)
            if n:
                return n
            time.sleep(0.25)
        return 0

    def text(self):
        try:
            self.logfile.flush()
        except Exception:
            pass
        try:
            with io.open(self.logpath, encoding="utf-8") as fh:
                return fh.read()
        except Exception:
            return ""

    def wait_log(self, needle, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if needle in self.text():
                return True
            if not self.alive():
                return False
            time.sleep(0.2)
        return False

    def terminate(self):
        """干净退出：SIGTERM → 走 Runtime shutdown hook → Gateway.stop()"""
        if not self.alive():
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=30)
        except Exception:
            os.kill(self.proc.pid, signal.SIGKILL)
            self.proc.wait(timeout=10)
        self._close_log()

    def kill9(self):
        if not self.alive():
            return
        os.kill(self.proc.pid, signal.SIGKILL)
        self.proc.wait(timeout=30)          # 等到真退出（waitpid）才去读盘，不拿日志行当触发器
        self._close_log()

    def _close_log(self):
        try:
            self.logfile.close()
        except Exception:
            pass


def webhook_in(webhook_port, conversation_id, text):
    body = json.dumps({"conversationId": conversation_id, "senderId": "alice",
                       "text": text}).encode("utf-8")
    req = urllib.request.Request("http://127.0.0.1:%d/webhook/in" % webhook_port,
                                 data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.read().decode("utf-8")


def poll_out(gw, sink, stop_flag, max_empty=8):
    """GET /webhook/out（服务端最多阻塞 20s）；收到空手而归若干次就收工。"""
    empties = 0
    while not stop_flag[0] and empties < max_empty:
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/webhook/out" % gw.webhook_port,
                                        timeout=40) as r:
                payload = json.loads(r.read().decode("utf-8") or "{}")
        except Exception:
            time.sleep(0.4)
            empties += 1
            continue
        text = (payload or {}).get("text") or ""
        if text.strip():
            sink.append(payload)
        else:
            empties += 1
    return sink


def burst(gw, convs, prefix="p16 请只回一句", pace=0.03):
    """把入站摊开在几秒里：这样回波是一个 wave，才谈得上「在飞」。"""
    for cid in convs:
        webhook_in(gw.webhook_port, cid, "%s-%s" % (prefix, cid))
        time.sleep(pace)


def storm(gw, convs, threads=8, prefix="p16 请只回一句"):
    """**同一瞬间**把入站砸进去，让 N 个回合在 stub 的同一个延迟之后一起回来。

    为什么不能用上面那个 pace=0.03 的摊开式 burst：入站是异步的
    （WebhookChannel.handleInbound → bus.deliver → newCachedThreadPool，立刻回 200），
    120 条被摊成 3.6 秒的流水 ⇒ 每个 dispatch 的 record→attempting→delivered 三段写
    早在下一条开始之前就自己结清了 ⇒ 杀在任何时刻盘上都是全绿现场。
    实测就是这样：e2e_full_r2.log 的 A 段 3 次抓现场全是 20 行 delivered / 未结清 0 行
    ⇒ A2、A4 FAIL，A7–A11 空跑。摊开式 burst 不是「更接近真实流量」，它是**量具坏**。
    一起砸才让 N 个三段写挤在同一个 SQLite 写锁上排队 ⇒ 「在飞」有了可观测的宽度。
    """
    def worker(slice_):
        for cid in slice_:
            try:
                webhook_in(gw.webhook_port, cid, "%s-%s" % (prefix, cid))
            except Exception as e:
                log("storm 入站异常 %s: %s" % (cid, type(e).__name__))
    ths = [threading.Thread(target=worker, args=(convs[i::threads],))
           for i in range(threads)]
    t0 = time.time()
    for t in ths:
        t.start()
    for t in ths:
        t.join(timeout=120)
    return time.time() - t0


def kill_on_live_window(profile, gw, min_open=3, min_settled=1, timeout=90, tag="live"):
    """盯**活着的** state.db（读它的副本）直到盘上同时出现「未结清」与「已结清」两类行，
    立刻 SIGKILL。

    上一版的杀人时机是「我抽到第 N 条回执」——那量的是我自己 GET /webhook/out 的
    抽水速度，跟产品有没有在飞没有因果关系（见 storm 的注释）。现在改成看到未结清
    行才杀：观察读数只用来**挑杀人时机**，判分的读数仍然是 kill 之后那一次
    （那时进程已 waitpid 退出，盘上没有活的写者）。
    min_settled 是为了让 A11（已结清的行不许被再投一遍）也有猎物，不是放松。
    返回 (dead_pid, 观察到的状态分布, 采样次数)。
    """
    deadline = time.time() + timeout
    samples = 0
    seen = {}
    while time.time() < deadline:
        rows, _how = read_ledger(profile, tag)          # 同一个 tag ⇒ 副本覆盖，不堆文件
        samples += 1
        if rows:
            seen = states(rows)
            if seen.get("delivered", 0) >= min_settled \
                    and sum(v for k, v in seen.items() if k != "delivered") >= min_open:
                break
        time.sleep(0.02)
    pid = gw.pid()
    gw.kill9()                                            # 内部 waitpid ⇒ 杀透了才回
    return pid, seen, samples


def states(rows):
    out = {}
    for r in rows.values():
        out[r["state"]] = out.get(r["state"], 0) + 1
    return out


def home_snapshot():
    """杠④：只取「文件名清单 + 整文件 md5」，一个文件的内容都不读进产物、不打印。"""
    if not os.path.isdir(REAL_HOME):
        return {"exists": False}
    listing = []
    for dirpath, dirs, files in os.walk(REAL_HOME):
        dirs.sort()
        rel = os.path.relpath(dirpath, REAL_HOME)
        for f in sorted(files + dirs):
            p = os.path.join(dirpath, f)
            rp = f if rel == "." else os.path.join(rel, f)
            try:
                st = os.lstat(p)          # lstat：符号链接/悬空链接都不至于把量具打崩
                kind = "/" if os.path.isdir(p) and not os.path.islink(p) else ""
                listing.append((rp + kind, st.st_size))
            except OSError as e:
                listing.append((rp + "!stat-failed:%s" % type(e).__name__, -1))
    digests = {}
    for name in ("config.properties", "state.db"):
        digests[name] = md5(os.path.join(REAL_HOME, name))
    return {"exists": True, "listing": sorted(listing), "digests": digests,
            "top_level": len(os.listdir(REAL_HOME))}


# ================= A =================

def section_a(root, base_url, tries=3):
    print("\n===== A 投递在飞 kill -9 ⇒ 账还在 ⇒ 重启带标记重投并收敛 =====", flush=True)
    LLM_DELAY[0] = 6.0
    best_all = []
    for attempt in range(1, tries + 1):
        prof = os.path.join(root, "A-profile-r%d" % attempt)
        os.makedirs(prof)
        convs = ["a-conv-%03d" % i for i in range(120)]
        gw = Gateway("A-gw-r%d" % attempt, prof, base_url, free_port(), free_port())
        gw.start()
        up = gw.listening(gw.webhook_port)
        if not up:
            check("A0 gateway 起来了（按 pid 过滤 lsof）", False,
                  "pid=%s webhook 端口 %d 没监听起来；日志尾部: %s"
                  % (gw.pid(), gw.webhook_port, gw.text()[-300:]))
            gw.kill9()
            return None
        sink = []
        stop = [False]
        th = threading.Thread(target=poll_out, args=(gw, sink, stop), daemon=True)
        th.start()
        storm(gw, convs)
        # 杀人时机 = 盘上同时看到「未结清」和「已结清」两类行（因果量具），不再是我抽水条数
        dead_pid, seen_live, samples = kill_on_live_window(
            prof, gw, min_open=3, tag="A-r%d-live" % attempt)
        got_first = len(sink) > 0
        th.join(timeout=45)
        stop[0] = True
        rows, how = read_ledger(prof, "A-r%d-afterkill" % attempt)
        if rows is None:
            check("A1 崩溃后盘上还有台账", False, how)
            return None
        open_rows = {k: v for k, v in rows.items() if v["state"] != "delivered"}
        log("第 %d 次抓现场：活盘采样 %d 次（最后一次状态=%s）→ SIGKILL pid=%d；"
            "杀后读死盘 %d 行 状态=%s 未结清 %d 行"
            % (attempt, samples, seen_live, dead_pid, len(rows), states(rows),
               len(open_rows)))
        best_all.append((prof, convs, rows, open_rows, sink, got_first, attempt, dead_pid))
        if len(open_rows) >= 3 and len(set(v["state"] for v in open_rows.values())) >= 2:
            break
    best_all.sort(key=lambda b: (len(set(v["state"] for v in b[3].values())), len(b[3])))
    (prof, convs, rows_at_kill, open_rows, sink_before, got_first, attempt,
     dead_pid) = best_all[-1]
    log("用第 %d 次抓到的现场（未结清 %d 行，状态 %s）"
        % (attempt, len(open_rows), sorted(set(v["state"] for v in open_rows.values()))))
    check("A1 崩溃前真在投递（kill 前抽到 ≥1 条回执，不是空跑的现场）", got_first,
          "kill -9 前抽到 %d 条回执；pid=%d 已 SIGKILL" % (len(sink_before), dead_pid))
    check("A2 kill -9 之后盘上台账还在（sqlite3 独立量具读 state.db 副本）",
          len(rows_at_kill) > 0 and len(open_rows) > 0,
          "%s；kill 时未结清 %d 行 状态分布=%s" % (how, len(open_rows), states(rows_at_kill)))
    check("A3 未结清的行不许被记成 delivered（账实相符）",
          all(v["state"] != "delivered" for v in open_rows.values())
          and len(open_rows) + sum(1 for v in rows_at_kill.values()
                                   if v["state"] == "delivered") == len(rows_at_kill),
          "未结清 %d + 已结清 %d = 总 %d" % (len(open_rows),
                                    len(rows_at_kill) - len(open_rows), len(rows_at_kill)))
    open_states = set(v["state"] for v in open_rows.values())
    check("A4 kill 时同时抓到 pending 与 attempting/failed 两类窗口（标记语义的两半都有猎物）",
          ("pending" in open_states) and (open_states & {"attempting", "failed"}),
          "kill 时在飞的状态集合=%s（缺哪半就记哪半未覆盖，不许造）" % sorted(open_states))

    # ---- 重启续跑 ----
    gw2 = Gateway("A-gw2", prof, base_url, free_port(), free_port())
    gw2.start()
    up2 = gw2.listening(gw2.webhook_port)
    check("A5 重启的 gateway 起来了（同一 profile）", bool(up2),
          "pid=%s lsof 监听数=%d；日志: %s" % (gw2.pid(), up2,
                                    [l for l in gw2.text().split("\n") if "断点重投" in l][:2]))
    sink2 = []
    stop2 = [False]
    th2 = threading.Thread(target=poll_out, args=(gw2, sink2, stop2), daemon=True)
    th2.start()
    deadline = time.time() + 180
    while time.time() < deadline:
        rows_now, _h = read_ledger(prof, "A-r%d-afterrestart" % attempt)
        if rows_now and all(v["state"] == "delivered" for v in rows_now.values()) \
                and len(sink2) >= len(open_rows):
            break
        time.sleep(1.0)
    th2.join(timeout=60)
    stop2[0] = True
    rows_after, how_after = read_ledger(prof, "A-converged")
    gw2.terminate()
    if rows_after is None:
        check("A6 重启后台账全部收敛成 delivered", False, how_after)
        return None
    by_chat_after = rows_after
    # 注：产品侧的 HTTP /bot/status 与 z-bot status 都**不**报台账计数（只有进程内
    # Gateway.status() 有），所以这一段没有第二个产品读数可对拍 ⇒ 量具是 sqlite3 一种，
    # 已记进 EVIDENCE 的「这一项没做什么」。
    check("A6 重启后台账全部收敛成 delivered 且行数不减（sqlite3 独立量具）",
          all(v["state"] == "delivered" for v in rows_after.values())
          and len(rows_after) >= len(rows_at_kill),
          "%s；kill 前 %d 行 → 现在 %d 行 状态分布=%s"
          % (how_after, len(rows_at_kill), len(rows_after), states(rows_after)))
    received_after = {}
    for m in sink2:
        cid = m.get("conversationId")
        if cid in open_rows:
            received_after.setdefault(cid, []).append(m.get("text") or "")
    check("A7 未结清的每一条都被重投且只投一次（双实例的对照面：条数不多不少）",
          len(received_after) == len(open_rows)
          and all(len(v) == 1 for v in received_after.values()),
          "未结清 %d 行 / 收到 %d 条（各条次数=%s）" % (
              len(open_rows), sum(len(v) for v in received_after.values()),
              sorted(set(len(v) for v in received_after.values())) or "-"))
    bad_pending, bad_marker, stacked = [], [], []
    for cid, r in open_rows.items():
        got = (received_after.get(cid) or [""])[0]
        want_body = r["content"]
        if r["state"] == "pending":
            if got != want_body:
                bad_pending.append("%s:%s" % (cid, r["state"]))
        else:  # attempting / failed ⇒ 歧义窗口
            if got != MARKER + want_body:
                bad_marker.append("%s(%s) got=%.24s want=%s" % (
                    cid, r["state"], got, MARKER[:12] + want_body[:12]))
            if got.count(MARKER) > 1:
                stacked.append(cid)
    check("A8 pending 行（发送根本没开始）重投不带「可能重复」前缀", not bad_pending,
          "违规=%s（pending 行数=%d）" % (bad_pending or "无",
                                    sum(1 for v in open_rows.values() if v["state"] == "pending")))
    check("A9 attempting/failed 行重投必须带且只带一层 ♻️ 前缀", not bad_marker and not stacked,
          "前缀错=%s 叠加=%s（歧义窗口行数=%d）" % (bad_marker[:3] or "无", stacked or "无",
                                          sum(1 for v in open_rows.values()
                                              if v["state"] != "pending")))
    grew = [(cid, rows_at_kill[cid]["attempts"], by_chat_after[cid]["attempts"])
            for cid in open_rows
            if by_chat_after[cid]["attempts"] != rows_at_kill[cid]["attempts"] + 1]
    check("A10 每条重投只烧一次重投预算（attempts 恰 +1）", not grew,
          "烧了双份/没烧=%s" % (grew[:4] or "无"))
    already = [cid for cid, v in rows_at_kill.items() if v["state"] == "delivered"
               and cid in received_after]
    check("A11 kill 时已结清的行没有被再投一遍", not already, "重复结清=%s" % (already or "无"))
    LLM_DELAY[0] = 0.0
    return open_rows


# ================= B =================

def section_b(root, base_url):
    print("\n===== B 双实例同刻只有一个真发（两个 JVM + 同一 profile） =====", flush=True)
    LLM_DELAY[0] = 6.0
    convs = ["b-conv-%03d" % i for i in range(120)]
    at_kill, open_rows, prof, dead_seed_pid = {}, {}, None, -1
    for seed_try in range(1, 4):
        prof = os.path.join(root, "B-profile-r%d" % seed_try)
        os.makedirs(prof)
        seed = Gateway("B-seed-r%d" % seed_try, prof, base_url, free_port(), free_port())
        seed.start()
        if not seed.listening(seed.webhook_port):
            check("B0 造现场的那个 gateway 起来了", False, "pid=%s 没监听；日志尾=%s"
                  % (seed.pid(), seed.text()[-300:]))
            seed.kill9()
            return
        sink = []
        stop = [False]
        th = threading.Thread(target=poll_out, args=(seed, sink, stop), daemon=True)
        th.start()
        storm(seed, convs)
        dead_seed_pid, seen_live, samples = kill_on_live_window(
            prof, seed, min_open=2, tag="B-r%d-live" % seed_try)
        stop[0] = True
        th.join(timeout=40)
        at_kill, _h = read_ledger(prof, "B-r%d-atkill" % seed_try) or ({}, "")
        open_rows = {k: v for k, v in (at_kill or {}).items() if v["state"] != "delivered"}
        log("B 造现场第 %d 次：活盘采样 %d 次（最后一次状态=%s）→ SIGKILL pid=%d；"
            "杀前抽到 %d 条回执；杀后盘上 %d 行，未结清 %d 行"
            % (seed_try, samples, seen_live, dead_seed_pid, len(sink),
               len(at_kill or {}), len(open_rows)))
        if len(open_rows) >= 2:
            break
    check("B1 造出未结清的现场（双实例要有活才干）", len(open_rows) >= 2,
          "3 次 SIGKILL 最多只抓到 %d 行未结清 ⇒ 这一段无从判定" % len(open_rows))
    if len(open_rows) < 2:
        return
    g1 = Gateway("B-gw1", prof, base_url, free_port(), free_port())
    g2 = Gateway("B-gw2", prof, base_url, free_port(), free_port())
    g1.start()
    g2.start()
    l1 = g1.listening(g1.webhook_port)
    l2 = g2.listening(g2.webhook_port)
    check("B2 两个真 JVM 同刻都活着且各自在听自己的端口（按 pid 过滤 lsof）",
          bool(l1) and bool(l2) and g1.alive() and g2.alive(),
          "pid %d→%d 条 lsof 监听 / pid %d→%d 条" % (g1.pid(), l1, g2.pid(), l2))
    s1, s2 = [], []
    st1, st2 = [False], [False]
    t1 = threading.Thread(target=poll_out, args=(g1, s1, st1, 12), daemon=True)
    t2 = threading.Thread(target=poll_out, args=(g2, s2, st2, 12), daemon=True)
    t1.start()
    t2.start()
    deadline = time.time() + 200
    while time.time() < deadline:
        if len(s1) + len(s2) >= len(open_rows):
            break
        time.sleep(1.0)
    st1[0] = st2[0] = True
    t1.join(timeout=60)
    t2.join(timeout=60)
    total = len(s1) + len(s2)
    check("B3 同一条义务两边加起来只发了一次（没有双发）",
          total == len(open_rows) and (bool(s1) != bool(s2) or total == 0),
          "未结清 %d 行 / gw1 收 %d 条 / gw2 收 %d 条" % (len(open_rows), len(s1), len(s2)))
    dup = {}
    for m in s1 + s2:
        dup[m.get("conversationId")] = dup.get(m.get("conversationId"), 0) + 1
    check("B4 收到的会话 id 没有一条出现两次", all(v == 1 for v in dup.values()),
          "重复=%s" % ([k for k, v in dup.items() if v > 1] or "无"))
    after, _ha = read_ledger(prof, "B-afterboth")
    burns = []
    for cid, r in open_rows.items():
        now = (after or {}).get(cid)
        if now is None or now["attempts"] != r["attempts"] + 1:
            burns.append("%s %d→%s" % (cid, r["attempts"], now and now["attempts"]))
    check("B5 盘上每条义务的 attempts 只被烧 1 次（两个 sweeper 没有各烧一份）", not burns,
          "异常=%s（共 %d 行）" % (burns[:4] or "无", len(open_rows)))
    converged = after and all(v["state"] == "delivered" for v in after.values())
    check("B6 台账最终收敛（双实例打完之后没有行留在半路）", bool(converged),
          "状态分布=%s" % (states(after or {})))
    lt1, lt2 = g1.text(), g2.text()
    refused = [t.tag for t, tx in (("B-gw1", lt1), ("B-gw2", lt2)) if "拒绝再起一个实例" in tx]
    check("B7 实例锁在真双开时判死了一个（活实例锁的进程侧实据）", len(refused) == 1,
          "拒启日志出现在=%s；B-gw1 命中=%s B-gw2 命中=%s"
          % (refused or "两边都没有", "拒绝再起一个实例" in lt1, "拒绝再起一个实例" in lt2))
    g1.terminate()
    g2.terminate()
    LLM_DELAY[0] = 0.0
    log("两边日志里与台账/锁相关的行：")
    for t, tx in (("B-gw1", lt1), ("B-gw2", lt2)):
        for line in [l for l in tx.split("\n") if "锁" in l or "断点重投" in l][:6]:
            print("   %s | %s" % (t, line.strip()), flush=True)


# ================= C =================

def section_c(root, base_url):
    print("\n===== C 干净退出删自己的锁 / 崩退出留锁并被下一个实例自愈 =====", flush=True)

    def lock_file(prof):
        return os.path.join(prof, "gateway", "gateway.lock")

    def lock_body(prof):
        p = lock_file(prof)
        if not os.path.isfile(p):
            return None
        with io.open(p, encoding="utf-8") as fh:
            return fh.read()

    # C1 干净退出 ⇒ 自己的锁被删掉
    profC = os.path.join(root, "C-clean")
    os.makedirs(profC)
    c1 = Gateway("C-clean", profC, base_url, free_port(), free_port())
    c1.start()
    ok_up = bool(c1.listening(c1.webhook_port))
    body_held = lock_body(profC)
    check("C1 起来时锁真在盘上且写着自己的 pid", ok_up and body_held is not None
          and ('"pid":%d' % c1.pid()) in (body_held or ""),
          "lock=%s 内容=%s" % (lock_file(profC).replace(profC, "<profile>"),
                                (body_held or "无")[:120]))
    c1.terminate()
    check("C2 干净退出（SIGTERM 走 shutdown hook）把**自己那把**锁删了",
          not os.path.isfile(lock_file(profC)),
          "pid=%d 退出后 lock 文件存在=%s" % (c1.pid(), os.path.isfile(lock_file(profC))))

    # C3 崩退出 ⇒ 删不掉锁；下一个实例判陈旧并自愈接管
    profD = os.path.join(root, "C-crash")
    os.makedirs(profD)
    d1 = Gateway("C-crash-1", profD, base_url, free_port(), free_port())
    d1.start()
    d1.listening(d1.webhook_port)
    dead_pid = d1.pid()
    body_before = lock_body(profD)
    d1.kill9()
    body_left = lock_body(profD)
    check("C3 崩退出（SIGKILL）删不掉锁：文件还在且仍写着死掉的 pid",
          body_left is not None and ('"pid":%d' % dead_pid) in body_left,
          "SIGKILL 前=%s 后=%s" % ((body_before or "无")[:80], (body_left or "无")[:80]))
    d2 = Gateway("C-crash-2", profD, base_url, free_port(), free_port())
    d2.start()
    healed = d2.wait_log("陈旧网关锁", 90) and bool(d2.listening(d2.webhook_port))
    body_taken = lock_body(profD)
    check("C4 下一个实例把陈旧锁判出来并自愈接管（日志 + 锁内容换主，两边都给）",
          healed and body_taken is not None and ('"pid":%d' % d2.pid()) in (body_taken or "")
          and ('"pid":%d' % dead_pid) not in (body_taken or ""),
          "pid %d 的日志有『陈旧网关锁』=%s；锁现在=%s" % (d2.pid(), healed,
                                                (body_taken or "无")[:120]))
    # C5 没拿到锁的那个实例，收尾时不许删别人的锁
    d3 = Gateway("C-crash-3", profD, base_url, free_port(), free_port())
    d3.start()
    d3.listening(d3.webhook_port)
    loser_alive = d3.alive()
    body_of_owner = lock_body(profD)
    d3.terminate()
    body_after_loser = lock_body(profD)
    owner_still_there = d2.alive()
    check("C5 双开里没拿到锁的那个退出时，没把别人（真活着的实例）的锁删掉",
          loser_alive and owner_still_there and body_after_loser == body_of_owner
          and body_after_loser is not None,
          "第三个实例 pid=%d 拒启后 terminate；锁内容变了=%s；持锁的 pid=%d 还活着=%s"
          % (d3.pid(), body_after_loser != body_of_owner, d2.pid(), owner_still_there))
    d2.terminate()
    check("C6 真持锁的那个干净退出后锁被删（不是被拒启者删的）",
          not os.path.isfile(lock_file(profD)),
          "pid=%d terminate 后 lock 存在=%s" % (d2.pid(), os.path.isfile(lock_file(profD))))


# ================= D + X =================

def section_d(before):
    print("\n===== D 杠④：~/.zbot 一个字节都不许多（只算清单与摘要，不读内容） =====", flush=True)
    after = home_snapshot()
    if not before.get("exists"):
        check("D0 ~/.zbot 存在（否则这条无从判定）", False, "不存在 ⇒ 未覆盖")
        return after
    added = sorted(set(after["listing"]) - set(before["listing"]))
    removed = sorted(set(before["listing"]) - set(after["listing"]))
    check("D1 ~/.zbot 顶层项数不变（没被写过新文件）",
          after["top_level"] == before["top_level"] and not added and not removed,
          "前 %d 项 / 后 %d 项；新增=%s 消失=%s"
          % (before["top_level"], after["top_level"], [a for a in added][:6] or "无",
             [r for r in removed][:6] or "无"))
    same = all(before["digests"][k] == after["digests"][k] for k in before["digests"])
    pre = lambda d: {k: (v[:8] if v else None) for k, v in d.items()}
    check("D2 config.properties / state.db 的整文件 md5 前后一致（只打 8 位前缀，永不打印内容）",
          same and all(before["digests"].values()),
          "前=%s 后=%s 一致=%s" % (json.dumps(pre(before["digests"])),
                            json.dumps(pre(after["digests"])), same))
    return after


def hygiene():
    print("\n===== X 凭证卫生（负向断言必须带猎物） =====", flush=True)

    def blobs(base):
        out = []
        for dirpath, _dirs, files in os.walk(base):
            for fn in files:
                p = os.path.join(dirpath, fn)
                try:
                    with io.open(p, encoding="utf-8", errors="replace") as fh:
                        out.append((p, fh.read()))
                except Exception:
                    pass
        return out

    # 只认「像凭证的串」：>=20 个连续非空白可见字符 + 键名 nearby；短词、mime、md5 前缀一律不算，
    # 免得把 allowlist 写成"看到什么红就排除什么"。
    key_shape = re.compile(
        r'(?:api[_.-]?key|apikey|authorization|access[_.-]?token|secret)["\']?\s*[:=]\s*'
        r'["\']?([A-Za-z0-9_\-\.]{20,})', re.I)

    def offenders(text):
        bad = []
        for v in key_shape.findall(text):
            v = v.strip().strip('"\'')
            if v and v != STUB_KEY:
                bad.append(v[:10] + "…(len=%d)" % len(v))
        return bad

    scan_dirs = [OUT, LOGS]
    # X0 探测器自证：往扫描面里放一把同量级的假 key，它必须被抓到
    sentinel = "SENTINELkeyNOTreal" + ("s" * 100)
    prey = os.path.join(OUT, "_hygiene_prey.txt")
    with io.open(prey, "w", encoding="utf-8") as fh:
        fh.write("minimax.api.key=%s\nAuthorization: %s\n" % (sentinel, sentinel))
    caught = {os.path.relpath(p, OUT): v for p, b in blobs(OUT) for v in offenders(b)}
    os.remove(prey)
    check("X0 泄漏探测器读得到产物（哨兵 key 必须被抓到，否则 X1 是空跑）",
          any(sentinel[:10] in v for vals in caught.values() for v in vals),
          "抓到 %d 处：%s" % (len(caught), list(caught)[:3]))
    scan = [(p, b) for d in scan_dirs for p, b in blobs(d)]
    hits = [(os.path.relpath(p, HERE), v) for p, b in scan for v in offenders(b)]
    check("X1 全部产物里出现过的 key 值只有 stub-key-not-real（非 stub 的一律算泄漏）",
          not hits, "命中=%s（扫描 %d 个文件，含 logs/）" % (hits[:5] or "无", len(scan)))
    stub_in_artifacts = sorted({os.path.relpath(p, HERE) for p, b in scan if STUB_KEY in b})
    check("X2 stub key 真的进了产物（同一条负向检查的反面钉子：扫描面不是空的）",
          bool(stub_in_artifacts), "带 stub key 的产物=%s" % (stub_in_artifacts[:4] or "无"))
    hdrs = [" ".join(h["headers"].values()) for h in LLM_HITS]
    stub_hits = sum(1 for s in hdrs if STUB_KEY in s)
    nonstub = sum(1 for s in hdrs if "earer" in s and STUB_KEY not in s)
    check("X3 每一次出口 LLM 请求带的都是 stub 凭证（这条同时钉住「真有请求发生」）",
          bool(LLM_HITS) and stub_hits == len(LLM_HITS) and nonstub == 0,
          "出口 %d 次 / 带 stub %d 次 / 带别的凭证 %d 次" % (len(LLM_HITS), stub_hits, nonstub))
    runtime = os.path.join(OUT, "runtime")
    cfgs = [os.path.join(dp, f) for dp, _d, fs in os.walk(runtime)
            for f in fs if f == "config.properties"]
    check("X4 临时 profile 里没有 config.properties（真 key 没有被复制进来的落盘面）", not cfgs,
          "命中=%s" % (cfgs or "无"))
    wrong_home = [g.tag for g in SPAWNED
                  if REAL_HOME in " ".join(g.cmd) or g.env.get("ZBOT_HOME") != g.profile]
    check("X5 每个真 JVM 的 --config-dir / ZBOT_HOME 都指在自己的临时 profile（没有一个指向 ~/.zbot）",
          not wrong_home, "起过 %d 个 JVM，违规=%s" % (len(SPAWNED), wrong_home or "无"))


def main():
    if not os.path.isfile(JAR):
        print("FATAL: 先跑 mvn -o -pl z-bot-core package -DskipTests（缺 %s）" % JAR, flush=True)
        return 2
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    os.makedirs(LOGS, exist_ok=True)
    root = os.path.join(OUT, "runtime")
    os.makedirs(root)
    print("marker 从被测源码取到，长度=%d 字符" % len(MARKER), flush=True)
    home_before = home_snapshot()
    print("杠④ 基线：~/.zbot 顶层 %s 项，md5 前缀=%s"
          % (home_before.get("top_level"), json.dumps(
              {k: (v[:8] if v else None) for k, v in (home_before.get("digests") or {}).items()})),
          flush=True)
    srv, base_url = start_stub()
    print("stub LLM: %s  临时根: %s" % (base_url, root), flush=True)
    only = os.environ.get("P16_ONLY", "").strip().upper()
    try:
        if only in ("", "A"):
            section_a(root, base_url)
        if only in ("", "B"):
            section_b(root, base_url)
        if only in ("", "C"):
            section_c(root, base_url)
    finally:
        srv.shutdown()
    section_d(home_before)
    if not only:
        hygiene()
    passed = sum(1 for _n, ok, _d in RESULTS if ok)
    print("\n== E2E: %d/%d 通过（%s） ==" % (passed, len(RESULTS),
                                        "整跑" if not only else "只跑 %s 段，其余不作数" % only))
    for name, ok, detail in RESULTS:
        if not ok:
            print("   FAIL %s | %s" % (name, detail))
    print("   日志/产物: %s" % OUT, flush=True)
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
