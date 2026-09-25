#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P17（cron 投递闭环）验收③：真进程 E2E。

四段都是**真跑**，没有一段是"读代码代替执行"：

  A 双 JVM 同刻不双跑 + 一次真 60s 级任务的投递实据
    两个真 `java -jar z-bot-core.jar gateway --config-dir <同一个 profile>` 打同一个
    cron 目录，盘上先埋一条 60s 级的一次性任务；两边的日志原文都落到 out/ 下。
    通道投递走真 WebhookChannel：谁跑赢了，出站队列里就该有一条真消息，
    而另一个必须一句"跳过"。顺带杀掉变异检验的 M9（gateway 不把通道表接进 cron）。
  B local / origin 降级 / 拉模式 http 不作为投递目标 —— 都从真 gateway 的 stdout 与 jobs.json 读。
  C 跑到一半 kill -9：账还在盘上（先落账），TTL 过后重启也不复活成"没跑过"（at-most-once）。
  D 红线 1：只给 ZBOT_HOME，cron 目录就得跟着 profile 走，且 ~/.zbot 一个字节都不许多。

凭证纪律：临时目录里只允许 stub-key-not-real；真 minimax key 在 ~/.zbot/config.properties，
任何情况下不进临时目录、不出现在任何输出里（收尾会 grep 一遍所有产物）。

前置: mvn -o -pl z-bot-core package -DskipTests
复算: python3 _doc/acceptance/p17/p17_e2e.py
"""
import fcntl
import hashlib
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")
REAL_CRON = os.path.join(REAL_HOME, "cron")
OUT = os.path.join(HERE, "out")
STUB_KEY = "stub-key-not-real"

RESULTS = []
SPAWNED = []           # 本场 E2E 起过的每一个真 JVM（凭证检查要看它们的环境）
LLM_HITS = []          # 每次 stub LLM 被调用记一条（用来数"到底跑了几回"）
LLM_LOCK = threading.Lock()
LLM_DELAY = [0.0]      # 让 stub 故意慢答，好在 runner 在飞的时候 kill -9


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


# ===== 假 LLM 网关（OpenAI 兼容；只为把"真跑一次 agent"这件事变成可观测的） =====

class StubServer(HTTPServer):
    """一次 LLM 调用会占住连接若干秒（故意的慢答）；单线程服务器会让别的请求排队，
    从而把"没跑第二次"这件事测成排队假象。多线程才配得上并发 JVM 的场景。"""

    def server_bind(self):
        self.daemon_threads = True
        HTTPServer.server_bind(self)

    def process_request_thread(self, request, client_address):
        # 默认实现里异常只打 stderr；这里让它冒出来，好知道是不是我的桩自己崩了
        threading.Thread(target=self.finish_thread, args=(request, client_address),
                         daemon=True).start()

    def finish_thread(self, request, client_address):
        try:
            self.finish_request(request, client_address)
        except Exception:
            self.handle_error(request, client_address)
        finally:
            self.shutdown_request(request)


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
        delay = LLM_DELAY[0]
        with LLM_LOCK:
            LLM_HITS.append({"t": time.time(), "path": self.path, "stream": stream,
                             "model": req.get("model"), "n_msgs": len(req.get("messages") or []),
                             # 出口凭证只在这儿可观测：key 从不落盘，所以"没漏"必须靠这一面来证
                             "headers": {k.lower(): str(v)
                                         for k, v in self.headers.items()}})
            seq = len(LLM_HITS)
        body_text = "P17-E2E-REPLY-%03d" % seq
        if delay:
            time.sleep(delay)
        if stream:
            chunks = ("data: %s\n\n" % json.dumps({
                "id": "stub-%d" % seq, "object": "chat.completion.chunk", "model": req.get("model"),
                "choices": [{"index": 0, "delta": {"role": "assistant", "content": body_text},
                             "finish_reason": None}]})
                + "data: %s\n\n" % json.dumps({
                "id": "stub-%d" % seq, "object": "chat.completion.chunk", "model": req.get("model"),
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]})
                + "data: [DONE]\n\n")
            payload = chunks.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
        else:
            payload = json.dumps({
                "id": "stub-%d" % seq, "object": "chat.completion", "model": req.get("model"),
                "choices": [{"index": 0, "message": {"role": "assistant", "content": body_text},
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
    """一个真 JVM：java -jar z-bot-core.jar gateway ...，日志分别落文件。"""

    def __init__(self, tag, profile, base_url, http_port, webhook_port, env_extra=None):
        self.tag = tag
        self.profile = profile
        self.http_port = http_port
        self.webhook_port = webhook_port
        self.logpath = os.path.join(OUT, "%s.log" % tag)
        self.logfile = open(self.logpath, "w", encoding="utf-8")
        env = dict(os.environ)
        env.pop("ZBOT_HOME", None)
        env.pop("ZBOT_MODEL", None)
        env.update({
            "Z_BOT_BASE_URL": base_url,
            "Z_BOT_API_KEY": STUB_KEY,
            "Z_BOT_MODEL": "stub-model",
            "Z_BOT_PROVIDER": "stub",
            "HOME": os.environ["HOME"],      # 不劫持真实 HOME，只靠 ZBOT_HOME/--config-dir
        })
        env.update(env_extra or {})
        self.env = env
        SPAWNED.append(self)
        self.cmd = ["java", "-jar", JAR, "gateway",
                    "--config-dir", profile,
                    "--port", str(http_port), "--webhook-port", str(webhook_port)]
        self.proc = None

    def start(self):
        self.proc = subprocess.Popen(self.cmd, stdout=self.logfile, stderr=subprocess.STDOUT,
                                     env=self.env, cwd=OUT)
        log("%s 已启动 pid=%s profile=%s http=%d webhook=%d"
            % (self.tag, self.proc.pid, os.path.basename(self.profile),
               self.http_port, self.webhook_port))

    def text(self):
        # kill() 之后 logfile 已经关了：日志落盘由内核负责，读文件就够，别再 flush 一个_closed_ 句柄
        if not self.logfile.closed:
            self.logfile.flush()
        try:
            with open(self.logpath, encoding="utf-8") as fh:
                return fh.read()
        except Exception:
            return ""

    def wait_for(self, needle, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if needle in self.text():
                return True
            if self.proc.poll() is not None:
                return False
            time.sleep(0.25)
        return False

    def kill(self, hard=False):
        if self.proc is None or self.proc.poll() is not None:
            return
        if hard:
            os.kill(self.proc.pid, signal.SIGKILL)
        else:
            self.proc.terminate()
        try:
            self.proc.wait(timeout=15)
        except Exception:
            try:
                os.kill(self.proc.pid, signal.SIGKILL)
            except Exception:
                pass
        self.logfile.close()


def poll_webhook(gw, seconds, sink):
    """GET /webhook/out：服务端最多阻塞 20s，空手而归时 text 是 ""。"""
    import urllib.request
    url = "http://127.0.0.1:%d/webhook/out" % gw.webhook_port
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                payload = json.loads(r.read().decode("utf-8"))
        except Exception as e:
            time.sleep(0.5)
            continue
        text = (payload or {}).get("text") or ""
        if text.strip():
            sink.append(payload)
            return
        time.sleep(0.2)


def seed_jobs(cron_dir, jobs):
    os.makedirs(cron_dir, exist_ok=True)
    with open(os.path.join(cron_dir, "jobs.json"), "w", encoding="utf-8") as fh:
        json.dump(jobs, fh, ensure_ascii=False, indent=2)


def read_jobs(cron_dir):
    path = os.path.join(cron_dir, "jobs.json")
    if not os.path.isfile(path):
        return []
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def job(jid, name, schedule, deliver, prompt="说一句你好", origin=None):
    j = {"id": jid, "name": name, "prompt": prompt, "schedule": schedule,
         "enabled": True, "lastRun": None, "lastResult": "", "deliver": deliver,
         "dispatches": 0, "runClaim": None, "lastDelivery": ""}
    if origin:
        j["origin"] = origin
    return j


def tail(text, needle, count=1, width=190):
    lines = [l.strip() for l in text.split("\n") if needle in l]
    return " ⏎ ".join(l[-width:] for l in lines[:count]) or "(没有这行)"


# ---- E 段用：跨进程锁的"邻居"与写请求 ----
# Darwin 的 fcntl 不导出 F_TLOCK 这个名字，但 fcntl(2) 的数值语义照旧（2 = 非阻塞攥锁）。
F_TLOCK = 2

# 邻居探针为什么必须是 JVM 而不是 Python：本机实测（2026-09-26，darwin 25.5 +
# CPython 3.14 + APFS）—— 别的进程正攥着 0 字节锁文件时，Python 自己的
# lockf(fd, F_TEST) 和 lockf(fd, F_TLOCK) **一律回 ACQUIRED**（探不到邻居锁，
# 拿它当"猎物进陷阱"的凭证就是空跑）；同一现场真 JVM 的 tryLock 回 null。
# 下面这个形状与被测代码 CronScheduler.acquireJobsLock 逐字一致，等于用产品
# 自己的尺量产品。前提双向取证见同目录 probe_lock_namespace.py。
LOCK_PROBE_JAVA = """
import java.nio.channels.*;
import java.nio.file.*;

public class LockProbe {
    public static void main(String[] args) throws Exception {
        FileChannel ch = FileChannel.open(Paths.get(args[0]),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock = ch.tryLock(0L, Long.MAX_VALUE, false);
        System.out.println(lock == null ? "NULL" : "GRANTED");
        if (lock != null) {
            lock.release();
        }
        ch.close();
    }
}
"""


def java_lock_probe(lock_path, timeout=60):
    """邻居 JVM 抢一次锁：'NULL' = 有人攥着，'GRANTED' = 抢得到。其余字符串 = 量具自己飞了。"""
    classes = os.path.join(OUT, "lockprobe")
    if not os.path.isfile(os.path.join(classes, "LockProbe.class")):
        os.makedirs(classes, exist_ok=True)
        src = os.path.join(classes, "LockProbe.java")
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(LOCK_PROBE_JAVA)
        c = subprocess.run(["javac", "-d", classes, src],
                           capture_output=True, text=True, timeout=240)
        if c.returncode != 0:
            return "JAVAC-FAIL:" + (c.stderr or "").strip()[-160:]
    r = subprocess.run(["java", "-cp", classes, "LockProbe", lock_path],
                       capture_output=True, text=True, timeout=timeout)
    out = (r.stdout or "").strip()
    if out not in ("NULL", "GRANTED"):
        return "NO-READING:rc=%d %s" % (r.returncode, ((r.stderr or out).strip()[-160:]))
    return out


def cron_add_cmd(port, name):
    body = json.dumps({"action": "add", "name": name, "prompt": "E 探针",
                       "schedule": "once 2031-01-01T00:00:00Z"})
    return ["curl", "-s", "--max-time", "90", "-X", "POST",
            "-H", "Content-Type: application/json", "-d", body,
            "http://127.0.0.1:%d/api/cron" % port]


def cron_add(port, name, timeout=20):
    r = subprocess.run(cron_add_cmd(port, name), capture_output=True, text=True,
                       timeout=timeout)
    return r.returncode, (r.stdout or r.stderr or "")


def main():
    if not os.path.isfile(JAR):
        print("FATAL: 先跑 mvn -o -pl z-bot-core package -DskipTests（缺 %s）" % JAR)
        return 2
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    root = os.path.join("/tmp", "p17-e2e-%d" % int(time.time()))
    os.makedirs(root)

    home_before = (len(os.listdir(REAL_HOME)) if os.path.isdir(REAL_HOME) else -1,
                   md5(os.path.join(REAL_HOME, "config.properties")),
                   md5(os.path.join(REAL_HOME, "state.db")))
    real_cron_listing = sorted(os.listdir(REAL_CRON)) if os.path.isdir(REAL_CRON) else None

    srv, base_url = start_stub()
    print("stub LLM: %s   临时根: %s" % (base_url, root), flush=True)

    # P17_ONLY=E 是给"注入 M8 后看 E 段会不会红"这种反复取证留的快通道：
    # 整跑一趟 12 分钟，而 M8 只在 E 段显形。部分模式下 X 段卫生与总账一律不作数。
    only = os.environ.get("P17_ONLY", "").strip().upper()
    if only and only != "E":
        print("FATAL: P17_ONLY 目前只支持 E（其余节请整跑，别拆）")
        return 2
    try:
        if not only:
            run_cases(root, base_url, home_before, real_cron_listing)
        section_e(root, base_url)
    finally:
        srv.shutdown()
    if only:
        print("\nPARTIAL MODE（P17_ONLY=%s）: 只跑了 %d 条，A–D 与 X 段这一跑不作数"
              % (only, len(RESULTS)), flush=True)
        return 0 if all(ok for _n, ok, _d in RESULTS) else 1

    # ---- 凭证卫生：所有产物里不许出现真 key ----
    real_key = None
    try:
        with open(os.path.join(REAL_HOME, "config.properties"), encoding="utf-8") as fh:
            for line in fh:
                if line.strip().startswith("minimax.api.key"):
                    real_key = line.split("=", 1)[1].strip() or None
    except Exception:
        pass
    def blobs(base):
        found = []
        for dirpath, _dirs, files in os.walk(base):
            for fn in files:
                p = os.path.join(dirpath, fn)
                try:
                    with open(p, encoding="utf-8", errors="replace") as fh:
                        found.append((p, fh.read()))
                except Exception:
                    pass
        return found

    # X0 先自证探测器：放一个与真 key 同量级的哨兵进扫描面，它必须被抓到。
    #     没这一步，下面那条"零命中"可能只是因为根本没读到文件（负向断言要有猎物）。
    sentinel = "SENTINEL-NOT-A-KEY-" + ("s" * 98)
    prey = os.path.join(OUT, "_hygiene_prey.txt")
    with open(prey, "w", encoding="utf-8") as fh:
        fh.write("minimax.api.key=%s\n" % sentinel)
    caught = [p for p, blob in blobs(OUT) if sentinel in blob]
    os.remove(prey)
    check("X0 泄漏探测读得到产物（哨兵必须被抓到）", bool(caught),
          "目录=%s 抓到 %d 处" % (OUT, len(caught)))

    leaked = [p for p, blob in blobs(OUT) + blobs(root) if real_key and real_key in blob]
    check("X1 真 key 没进任何输出产物或临时 profile", not leaked,
          "泄漏=%s（真 key 长度 %s，未打印）" % (leaked or "无", len(real_key or "")))
    if real_key:
        bad_env = [g.tag for g in SPAWNED
                   if any(real_key in str(v) for v in g.env.values())]
        check("X2 每个真 JVM 的环境里都不带真 key", not bad_env,
              "起过 %d 个 JVM，违规=%s" % (len(SPAWNED), bad_env or "无"))
    else:
        check("X2 每个真 JVM 的环境里都不带真 key", False,
              "读不到真 key ⇒ 这条没有猎物，按未覆盖记账（不许默认通过）")
    joined_hdr = [" ".join(h["headers"].values()) for h in LLM_HITS]
    stub_hits = sum(1 for s in joined_hdr if STUB_KEY in s)
    real_hits = sum(1 for s in joined_hdr if real_key and real_key in s)
    check("X3 每一次出口请求带的都是 stub 凭证（这条同时钉住"
          "「真有请求」，免得 X1 的反面是空跑）",
          bool(LLM_HITS) and stub_hits == len(LLM_HITS) and real_hits == 0,
          "请求 %d 次 / 带 stub %d 次 / 带真 key %d 次" % (len(LLM_HITS), stub_hits, real_hits))
    cfgs = [p for p, _b in blobs(root) if p.endswith("config.properties")]
    check("X4 临时 profile 从不落 config.properties（key 无落盘面）", not cfgs,
          "临时根=%s 命中=%s" % (root, cfgs or "无"))

    passed = sum(1 for _n, ok, _d in RESULTS if ok)
    print("\n== E2E: %d/%d 通过 ==" % (passed, len(RESULTS)))
    for name, ok, detail in RESULTS:
        if not ok:
            print("   FAIL %s | %s" % (name, detail))
    print("   日志/产物: %s" % OUT)
    return 0 if passed == len(RESULTS) else 1


def run_cases(root, base_url, home_before, real_cron_listing):
    # ================= A：双 JVM 同刻不双跑 + 60s 级真投递 =================
    print("\n===== A 双 gateway（两个真 JVM）+ 同一 cron 目录 + 60s 级一次性任务 =====", flush=True)
    profA = os.path.join(root, "profileA")
    cronA = os.path.join(profA, "cron")
    due = time.time() + 50
    seed_jobs(cronA, [job("cron_e2eA", "e2e-A", "once " + time.strftime(
        "%Y-%m-%dT%H:%M:%SZ", time.gmtime(due)), "webhook:cron-e2e")])
    # 让跑赢的那一方在 run.lock 里多待一会儿：两个 JVM 的 tick 各自从"自己启动"起算
    # 60s，启动有几秒先后是常态 —— 慢答 12s 才能把这段偏差稳稳盖住，
    # 否则"没双跑"可能只是运气好错开了 tick，日志里连一句跳过都留不下。
    LLM_DELAY[0] = 12.0
    a1 = Gateway("A-gw1", profA, base_url, free_port(), free_port())
    a2 = Gateway("A-gw2", profA, base_url, free_port(), free_port())
    t0 = time.time()
    a1.start()
    a2.start()
    both_up = a1.wait_for("cron 投递口", 90) and a2.wait_for("cron 投递口", 90)
    check("A1 两个 gateway 都起了并把通道表接进 cron 投递口", both_up,
          "两边日志里都有『cron 投递口: 已接通道表』")
    sink1, sink2 = [], []
    th1 = threading.Thread(target=poll_webhook, args=(a1, 200, sink1), daemon=True)
    th2 = threading.Thread(target=poll_webhook, args=(a2, 200, sink2), daemon=True)
    th1.start()
    th2.start()
    th1.join()
    th2.join()
    elapsed = time.time() - t0
    with LLM_LOCK:
        hits_A = len(LLM_HITS)
    got = (bool(sink1) + bool(sink2))
    check("A2 60s 级任务真跑了一次（%0.0fs）" % elapsed, hits_A == 1,
          "假 LLM 被调用 %d 次（>1 = 双跑，0 = 没跑）" % hits_A)
    check("A3 结果真投递到 webhook 通道，且只投了一次", got == 1,
          "gw1 收到 %d 条 / gw2 收到 %d 条" % (len(sink1), len(sink2)))
    winner = sink1[0] if sink1 else (sink2[0] if sink2 else {})
    check("A4 投递正文是真跑出来的模型回复", "P17-E2E-REPLY" in (winner.get("text") or ""),
          "conversationId=%s text=%.90s" % (winner.get("conversationId"), winner.get("text")))
    loser, loser_tag = (a2, "A-gw2") if bool(sink1) else (a1, "A-gw1")
    lt = loser.text()
    skipped = ("正在被其他进程执行" in lt) or ("认领且仍新鲜" in lt) or ("摘除陈旧任务" in lt)
    check("A5 另一个 JVM 同刻没跑，日志里有跳过原因", skipped,
          "%s: %s" % (loser_tag, tail(lt, "跳过", 2) if "跳过" in lt else tail(lt, "摘除", 2)))
    check("A6 跑赢的一方留下了落账日志", "已落账认领" in (a1.text() if bool(sink1) else a2.text()),
          tail(a1.text() if bool(sink1) else a2.text(), "已落账认领", 1))
    left = [j["id"] for j in read_jobs(cronA)]
    check("A7 一次性任务跑完即从盘上摘除", "cron_e2eA" not in left, "jobs.json 剩余=%s" % left)
    LLM_DELAY[0] = 0.0
    a1.kill()
    a2.kill()
    # 两边日志原文（报告要贴的就是这个）
    for gw in (a1, a2):
        print("\n----- %s 日志原文（cron 相关行） -----" % gw.logpath, flush=True)
        for line in gw.text().split("\n"):
            if "cron" in line or "投递口" in line:
                print(line, flush=True)

    # ================= B：local / origin 降级 / 拉模式 http 不作目标 =================
    print("\n===== B 三种路由的落地（真 gateway） =====", flush=True)
    profB = os.path.join(root, "profileB")
    cronB = os.path.join(profB, "cron")
    past = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 10))
    seed_jobs(cronB, [
        job("cron_local", "B-local", "every 5s", "local"),
        job("cron_origin", "B-origin", "every 5s", "origin"),
        job("cron_http", "B-http", "every 5s", "http:c-1"),
    ])
    b1 = Gateway("B-gw", profB, base_url, free_port(), free_port())
    b1.start()
    ok_up = b1.wait_for("cron 投递口", 90)
    check("B0 gateway 起来了", ok_up, "profile=%s" % os.path.basename(profB))
    # 三条任务在<b>同一个 tick</b> 里按顺序跑。上一版一看见 "@ local" 就 kill，
    # 于是排在后面的 origin/http 两条根本没轮到 —— B3/B4 红的是量具，不是产品。
    # 现在等"三条都留下投递结论"再收尾，并且单独核对假 LLM 真被打了 3 次（出口那一面）。
    hits_at_b_start = len(LLM_HITS)
    deadline = time.time() + 220
    while time.time() < deadline:
        snap = {j["id"]: j for j in read_jobs(cronB)}
        if all((snap.get(k) or {}).get("lastDelivery")
               for k in ("cron_local", "cron_origin", "cron_http")):
            break
        time.sleep(1)
    bt = b1.text()
    print("\n----- B-gw 日志原文（cron 相关行） -----", flush=True)
    for line in bt.split("\n"):
        if "cron" in line:
            print(line, flush=True)
    b1.kill()
    jobs_b = {j["id"]: j for j in read_jobs(cronB)}
    http_rec = jobs_b.get("cron_http") or {}
    check("B1 deliver=local 真的打印出了结果",
          "B-local (cron_local) @ local" in bt,
          tail(bt, "B-local (cron_local) @ local", 1, 240))
    check("B2 deliver=origin 没有来源时降级 local 而不是报错",
          ("deliver=origin" in bt and "按 local 投递" in bt
           and "B-origin (cron_origin) @ local" in bt and "no delivery target" not in bt),
          tail(bt, "deliver=origin", 1, 240))
    check("B3 拉模式 HTTP 控制台被剔出投递目标（报 unknown channel 而不是假装 ok）",
          "unknown channel 'http'" in (http_rec.get("lastDelivery") or ""),
          "jobs.json 里 cron_http.lastDelivery=%r" % http_rec.get("lastDelivery"))
    check("B4 三条任务的执行结果都落了台账",
          all((jobs_b.get(k) or {}).get("lastResult") for k in ("cron_local", "cron_origin", "cron_http")),
          " ".join("%s=%r" % (k, (jobs_b.get(k) or {}).get("lastResult"))
                   for k in ("cron_local", "cron_origin", "cron_http")))
    b_hits = len(LLM_HITS) - hits_at_b_start
    check("B5 B 段真跑了三次（出口计数，与 lastResult 不同的一面）",
          b_hits == 3, "B 段 stub LLM 被调用 %d 次（期望 3：local/origin 降级/http 报错各一次）" % b_hits)

    # ================= C：kill -9 在飞 → 账还在 → 不复活 =================
    print("\n===== C 跑到一半 kill -9：先落账 + at-most-once =====", flush=True)
    profC = os.path.join(root, "profileC")
    cronC = os.path.join(profC, "cron")
    dueC = time.time() + 50
    seed_jobs(cronC, [job("cron_e2eC", "e2e-C",
                          "once " + time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(dueC)), "local")])
    LLM_DELAY[0] = 30.0
    c1 = Gateway("C-gw-before", profC, base_url, free_port(), free_port())
    c1.start()
    claimed = c1.wait_for("已落账认领", 120)
    check("C1 副作用之前认领已落账（日志）", claimed, tail(c1.text(), "已落账认领", 1))
    pid = c1.proc.pid if claimed else None
    c1.kill(hard=True)
    LLM_DELAY[0] = 0.0
    oncrash = {j["id"]: j for j in read_jobs(cronC)}.get("cron_e2eC") or {}
    check("C2 进程被 kill -9 之后，盘上的账还在（dispatches=1 + runClaim 未清）",
          oncrash.get("dispatches") == 1 and bool(oncrash.get("runClaim")),
          "dispatches=%r runClaim=%r" % (oncrash.get("dispatches"), oncrash.get("runClaim")))
    # 让"认领已过期"这件事等价于"那个进程死了很久"：只改盘，不改代码
    if oncrash.get("runClaim"):
        oncrash["runClaim"]["at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 7200))
        seed_jobs(cronC, [oncrash])
    with LLM_LOCK:
        before_recover = len(LLM_HITS)
    c2 = Gateway("C-gw-after", profC, base_url, free_port(), free_port())
    c2.start()
    deadline = time.time() + 120
    while time.time() < deadline and "额度已用完" not in c2.text():
        time.sleep(1)
    ct = c2.text()
    with LLM_LOCK:
        after_recover = len(LLM_HITS)
    check("C3 重启后不复活成没跑过：陈旧一次性任务被摘除而不是重跑",
          "额度已用完" in ct and after_recover == before_recover,
          "%s | 假 LLM 新增调用 %d 次" % (tail(ct, "额度已用完", 1, 220), after_recover - before_recover))
    check("C4 摘除之后盘上不再有这条", "cron_e2eC" not in [j["id"] for j in read_jobs(cronC)],
          "剩余=%s" % [j["id"] for j in read_jobs(cronC)])
    c2.kill()
    if pid:
        log("被 kill -9 的 pid=%s" % pid)

    # ================= D：红线 1，只给 ZBOT_HOME =================
    print("\n===== D cron 目录跟着 ZBOT_HOME 走 =====", flush=True)
    profD = os.path.join(root, "profileD")
    d1 = Gateway("D-gw", profD, base_url, free_port(), free_port(),
                 env_extra={"ZBOT_HOME": profD})
    # 只留 gateway：profile 完全由 ZBOT_HOME 决定，不传 --config-dir
    d1.cmd = ["java", "-jar", JAR, "gateway",
              "--port", str(d1.http_port), "--webhook-port", str(d1.webhook_port)]
    d1.start()
    ok_d = d1.wait_for("cron 投递口", 90)
    d1.kill()
    lockfile = os.path.join(profD, "cron", ".jobs.lock")
    check("D1 只给 ZBOT_HOME 时 cron 目录落在该 profile 里", ok_d and os.path.exists(lockfile),
          "ZBOT_HOME=%s → %s%s" % (profD, os.path.relpath(lockfile, profD),
                                 "" if os.path.exists(lockfile) else "（不存在）"))
    home_after = (len(os.listdir(REAL_HOME)) if os.path.isdir(REAL_HOME) else -1,
                  md5(os.path.join(REAL_HOME, "config.properties")),
                  md5(os.path.join(REAL_HOME, "state.db")))
    check("D2 真实 ~/.zbot 没被动过", home_before == home_after,
          "before=%s after=%s" % (home_before, home_after))
    cron_now = sorted(os.listdir(REAL_CRON)) if os.path.isdir(REAL_CRON) else None
    check("D3 真实 ~/.zbot/cron 没多出东西", cron_now == real_cron_listing,
          "before=%s after=%s" % (real_cron_listing, cron_now))


def section_e(root, base_url):
    """E 段：两个真进程抢同一把 `.jobs.lock`。

    单列成函数是为了能 `P17_ONLY=E` 单跑 —— 变异体 M8 的取证要反复验这一节，
    不该陪着 A 段那 200 多秒一起等。
    """
    # 这一节是变异体 M8（`JOBS_LOCK_TIMEOUT_MILLIS` 30_000 → 0）唯一的现场：进程内单测造不出
    # "另一个进程正攥着跨进程锁"。前提有两条，都不许靠猜：
    #   1) python 的 lockf 与 JVM 的 FileChannel.tryLock 是同一套 POSIX 记录锁
    #      —— 由同目录 probe_lock_namespace.py 单独取证（实测 java 侧 tryLock=NULL）；
    #   2) 调度 tick 是 60s 一拍，拿它当"撞锁时机"不可靠 ⇒ 改走 HTTP 写路径（POST /api/cron
    #      → scheduler.add → withStore → 跨进程锁），什么时候抢锁由本脚本说了算。
    print("\n===== E 邻居攥住 .jobs.lock：等得到，才不算降级 =====", flush=True)
    if not shutil.which("curl"):
        check("E0 环境有 curl（E 段的写请求靠它）", False, "没找到 curl ⇒ E 段无法取证")
        return
    profE = os.path.join(root, "profileE")
    cronE = os.path.join(profE, "cron")
    seed_jobs(cronE, [job("cron_seed_e", "E-seed", "once 2030-01-01T00:00:00Z", "local")])
    egw = Gateway("E-gw", profE, base_url, free_port(), free_port())
    egw.start()
    ok_e = egw.wait_for("cron 投递口", 90)
    check("E0a gateway 起来了（E 段的写请求有人接）", ok_e,
          "profile=%s" % os.path.basename(profE))
    if not ok_e:
        egw.kill()
        return

    t0 = time.time()
    rc, out = cron_add(egw.http_port, "E-free")
    try:
        free_id = (json.loads(out).get("job") or {}).get("id", "")
    except Exception:
        free_id = ""
    t_free = time.time() - t0
    ids_free = [j.get("id") for j in read_jobs(cronE)]
    check("E1 不攥锁时写请求秒回且真落盘（这一条是后面几例的猎物：证明被抢的确实是这把锁）",
          # 时限放 20s 而不是 5s：这一条只负责"没邻居时写得动"，判别量在 E2 那 20s 的等待上，
          # 机器负载高时不该由它假红。
          rc == 0 and bool(free_id) and free_id in ids_free and t_free < 20,
          "耗时 %.1fs rc=%s id=%r 盘上=%s" % (t_free, rc, free_id, ids_free))

    e_lock = os.path.join(cronE, ".jobs.lock")
    lfd = os.open(e_lock, os.O_RDWR | os.O_CREAT, 0o644)
    held_by_us = True
    try:
        fcntl.lockf(lfd, F_TLOCK)
    except OSError as ex:
        held_by_us = False
        check("E1b 本脚本攥住了 .jobs.lock", False,
              "lockf 失败：%s —— 盘上这把锁已被谁持着？" % ex)
    if not held_by_us:
        os.close(lfd)
        egw.kill()
        return
    released = False
    try:
        seen = java_lock_probe(e_lock)
        check("E0b 邻居（真 JVM，tryLock 形状与被测代码逐字一致）确实抢不到这把锁",
              seen == "NULL", "邻居 JVM tryLock=%s（NULL 才说明本脚本真的挡住了它）" % seen)

        t_hold = time.time()
        held = subprocess.Popen(cron_add_cmd(egw.http_port, "E-held"),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        time.sleep(20)
        still_waiting = held.poll() is None
        names = sorted(j.get("name") for j in read_jobs(cronE))
        check("E2 攥锁期间写请求 20s 没返回，且盘上没多出这条（它在等，不是绕过锁写下去）",
              still_waiting and "E-held" not in names,
              "20s 后仍在跑=%s 盘上=%s" % (still_waiting, names))

        os.close(lfd)          # 关掉描述符即释放 POSIX 记录锁
        released = True
        t_release = time.time()
        try:
            so, se = held.communicate(timeout=90)
            hrc = held.returncode
        except subprocess.TimeoutExpired:
            held.kill()
            held.communicate()
            so, se, hrc = "", "", -1
        try:
            held_id = (json.loads(so or "").get("job") or {}).get("id", "")
        except Exception:
            held_id = ""
        ids_after = [j.get("id") for j in read_jobs(cronE)]
        check("E3 释放后请求落定、任务真写进盘（等待是以拿到锁结束的，不是超时了事）",
              hrc == 0 and bool(held_id) and held_id in ids_after,
              "从发起到落定 %.1fs ⇒ rc=%s id=%r 盘上有=%s 输出=%.60s"
              % (t_release - t_hold, hrc, held_id, held_id in ids_after, (so or se or "").strip()))

        # E3 只说"请求落了地"，不说"是我们放开的锁"。这一条把释放本身钉住：
        # r4 实跑收尾时 lsof 读到量具自己（Python 进程 fd 6u）还开着的 .jobs.lock，
        # 而盘上的 gateway 日志里已经有两行"降级" —— 放锁没有尺，那一跑就说不清
        # 锁到底掉没掉。所以放锁单独有一条。
        granted = ""
        for _ in range(16):
            granted = java_lock_probe(e_lock)
            if granted == "GRANTED":
                break
            time.sleep(0.5)
        check("E0c 关掉描述符后邻居又抢得到（E3 的「释放」真是放开了锁，E 段没把锁漏在盘上）",
              granted == "GRANTED", "邻居 JVM tryLock=%s" % granted)

        et = egw.text()
        print("\n----- E-gw 日志原文（cron 相关行） -----", flush=True)
        shown = [l for l in et.split("\n")
                 if "cron" in l or "跨进程锁" in l or "重入计数" in l]
        print(("\n".join(shown[-14:]) or "(没有这类行)"), flush=True)
        degraded = [l for l in et.split("\n")
                    if "跨进程锁超过" in l or "跨进程锁不可用" in l or "重入计数失衡" in l]
        check("E4 整个对撞期间没有出现过一次「降级为只用进程内锁」",
              not degraded,
              "降级行=%s（30s 上限内等到锁，所以不该有）" % (degraded[:1] or "无"))
    finally:
        if not released:
            os.close(lfd)
        egw.kill()



if __name__ == "__main__":
    sys.exit(main())
