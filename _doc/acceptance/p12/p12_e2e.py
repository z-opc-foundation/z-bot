#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P12（主循环节律包）验收③：**真进程** E2E。读代码不算证据，每一段都是真跑。

段（--only 可单选；默认全跑）
  stop   真 JVM gateway 里跑一条在飞的长命令（exec 起 bash + sleep），
         在这条命令**正飞着**的时候发 /bot/stop；判"真断了"落在内核进程表上：
         被 /stop 叫停之后，那个唯一 token 从 `pgrep -f` 里消失才算断。
         计时用 harness **自己 spawn 的 waiter 子进程 + os.waitpid(WNOHANG)** ——
         waiter 唯一的退出条件就是那个 token 从进程表里没了，
         所以"从发 /stop 到工具子进程真退出"的耗时不是从日志行推出来的。
         （工具子进程的父是那台 JVM，不是 python；POSIX 不允许对非子进程 waitpid。
          所以 waitpid 落在 harness 自己 spawn 的 waiter 与 JVM 上，
          工具进程本身由 pgrep/ps 这两把内核层的尺复算第二遍。）
  repl   真 pty（`os.openpty()`）跑一次 `z-bot repl`：
         R1 斜杠命令在 REPL 里路由得到、JVM 自己退得了（waitpid 收到退出码）；
         R2 反面对照：REPL 正跑长命令时往 pty 里写 `/stop\\n`，量工具子进程会不会因此消失。
            TerminalChannel.run() 是同步循环（chat 期间不读 stdin），预期**不会** ——
            这一条是"中断段为什么要走真进程 gateway 而不是 REPL"的实测依据，不是读代码读出来的。
  cache  同一会话连打两次 chat，从 stub 落盘的**请求原文**里取 system 消息字节比对；
         同时证明 user 块里那个易变时钟这两轮确实变了（否则"逐字节相同"可以是两轮都没注入）。
  home   ~/.zbot 跑前跑后不变（项数 + config.properties / state.db 的 md5 前缀）。
  creds  凭证卫生：stub key 真进了产物（反向钉住），且产物里出现的每一种 Bearer 值只有它。
         扫描面 = **本跑新建/改动过**的 out/ 与 logs/ 下的文件（按 mtime 定，不按文件名猜）。
  build  B0/B0b：本跑开跑前自己 `mvn -o package` 重打 jar，并把 git HEAD + jar sha256
         打进读数；收工后复核 sha256 没变。判"跑的是哪一版字节"不能靠人记得先打包
         （G13 实测：杠② 变异分支留下的被变异 jar，会把 C1/C5 红成"产品坏了"）。

为什么用 pty 而不是 `script`：`openpty()` 让 harness 直接握住 master fd，
写入时刻精确到 us（R2 要从"写 /stop 那一刻"起算），也免掉 `script` 那层缓冲与时序差。

前置: 无（本脚本自己打包；打包失败就不开跑，不拿旧 jar 顶包）
复算: python3 -u _doc/acceptance/p12/p12_e2e.py
       python3 -u _doc/acceptance/p12/p12_e2e.py --only stop
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import termios
import threading
import time
import tty
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
OUT = os.path.join(HERE, "out")
LOGS = os.path.join(HERE, "logs")
REQ_DIR = os.path.join(OUT, "llm-requests")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")
STUB_KEY = "stub-key-not-real"
MODEL = "gpt-4o-mini"        # 走 OpenAI 兼容协议；kernel 的 supportsModel 认 gpt- 前缀
PROVIDER_CODE = "p12stub"    # 自定义 code：靠 providers= 声明 + Z_BOT_PROVIDER 双保险
# 中途写盘的两个哨兵串（cache 段用；只含 ASCII 与汉字，不含任何 key 形状，免得撞 K2）
MIDRUN_SOUL_MARKER = "P12-MIDRUN-SOUL-LINE-9e3f"
MIDRUN_MEMORY_MARKER = "P12-MIDRUN-MEMORY-LINE-4b7a"
STOP_LIMIT_MS = 2000         # 工单要求的判据
STOP_PROBE_LIMIT_MS = 6000           # 量具的量程：超过它就记 FAIL（不是"再等等看"）
STUB = {"url": None}                 # 起 stub 之后才有；jvm_env 用它钉 base.url
REQ_SEQ = {"n": 0}                   # 每段 reset 一次：让 001.json 在每段里都真存在
REQ_SUBDIR = {"path": REQ_DIR}       # 当前段的落盘目录

CHECKS = []
SPAWNED = []
TOKENS = []
LLM_LOCK = threading.Lock()
LLM_HITS = []
SCRIPT = {"mode": "text", "command": None}

# 本跑的起点（ns 精度）：K2/K3 的扫描面按"这一跑新建或改动过的文件"来定，
# 不再按文件名猜（详见 G12：别的工具往 logs/ 里落一个读数，就把后面每一跑
# 都钉成假红 —— 哨兵吃到了别人的字，还自己续了一口）。
RUN_START = time.time()
JAR_SHA_AT_BUILD = {"sha": None}


def check(name, ok, detail, section, status=None):
    st = status or ("PASS" if ok else "FAIL")
    CHECKS.append({"name": name, "status": st, "detail": detail, "section": section})
    print("%-5s %-58s %s" % (st, name, detail), flush=True)
    return st


def log(msg):
    print("     · %s" % msg, flush=True)


def md5(path):
    if not os.path.isfile(path):
        return None
    h = hashlib.md5()
    with open(path, "rb") as fh:
        for blk in iter(lambda: fh.read(65536), b""):
            h.update(blk)
    return h.hexdigest()


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def sha256_of(path):
    if not os.path.isfile(path):
        return None
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for blk in iter(lambda: fh.read(65536), b""):
            h.update(blk)
    return h.hexdigest()


def git_out(*args):
    try:
        r = subprocess.run(["git", "-C", ZBOT] + list(args),
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
        return (r.stdout or b"").decode("utf-8", "replace").strip() if r.returncode == 0 else "GIT-rc=%s" % r.returncode
    except Exception as e:
        return "GIT-ERR=%s" % e


def build_gate(tag):
    """杠③的"跑的是哪一版字节"闸门：每次都现打 jar，并把 provenance 打进读数。

    这里踩过一次（G13）：杠② 的 M12 分支会 `mvn -o package` 打出**被变异**的 jar，
    还原源码后 jar 不会自己还原；随后的 `mvn -o test` 也不重打 package。
    于是"全绿基线"的那次 E2E 跑的其实是 M12 的变异字节 —— C1/C5 红得像是产品坏了，
    而反过来（变异残留却没被抓到）更糟。判"跑了哪一版字节"不能靠人记得先打包。
    """
    pkglog = os.path.join(LOGS, "package-%s.log" % tag)
    with open(pkglog, "wb") as fh:
        rc = subprocess.run(["mvn", "-o", "package", "-DskipTests", "-pl", "z-bot-core"],
                            cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT,
                            timeout=1800).returncode
    sha = sha256_of(JAR)
    JAR_SHA_AT_BUILD["sha"] = sha
    head = git_out("rev-parse", "--short", "HEAD")
    dirty = git_out("status", "--porcelain", "--", "z-bot-core/src", "pom.xml", "z-bot-core/pom.xml")
    log("打包 rc=%s jar=%s head=%s 源码脏=%s 打包日志=logs/%s"
        % (rc, (sha or "MISSING")[:16], head, "无" if not dirty else dirty.replace("\n", " | "), tag))
    return rc, sha, head, dirty


# ===== 假 LLM（OpenAI 兼容；把每一轮实际发出的请求原文落盘） =====

class StubServer(HTTPServer):
    def server_bind(self):
        self.daemon_threads = True
        HTTPServer.server_bind(self)

    def process_request_thread(self, request, client_address):
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
        with LLM_LOCK:
            REQ_SEQ["n"] += 1
            seq = REQ_SEQ["n"]
            LLM_HITS.append({"t": time.time(), "seq": seq, "path": self.path,
                             "headers": {k.lower(): str(v) for k, v in self.headers.items()},
                             "n_msgs": len(req.get("messages") or [])})
            mode, command = SCRIPT["mode"], SCRIPT["command"]
        # 请求原文落盘：缓存不变量比的是**实际发出去的字节**，不是被测自述
        with open(os.path.join(REQ_SUBDIR["path"], "%03d.json" % seq), "wb") as fh:
            fh.write(raw)
        # 出口凭证只在这儿可观测：key 从不落盘，所以"没漏"必须靠这一面来证
        with open(os.path.join(REQ_SUBDIR["path"], "%03d.headers.json" % seq), "w", encoding="utf-8") as fh:
            json.dump(LLM_HITS[-1]["headers"], fh, ensure_ascii=False, indent=1)
        if mode == "tool_call" and command:
            msg = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call-e2e-%d" % seq, "type": "function",
                "function": {"name": "exec",
                             "arguments": json.dumps({"command": command})}}]}
            finish = "tool_calls"
        else:
            msg = {"role": "assistant", "content": "P12-E2E-REPLY-%03d" % seq}
            finish = "stop"
        payload = json.dumps({
            "id": "stub-%d" % seq, "object": "chat.completion", "model": req.get("model"),
            "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
            "usage": {"prompt_tokens": 40, "completion_tokens": 12, "total_tokens": 52},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        payload = b'{"data":[]}'
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
    url = "http://127.0.0.1:%d/v1" % srv.server_address[1]
    STUB["url"] = url
    return srv, url


# ===== 进程表：判"真断了"的那把尺 =====

def procs_matching(token):
    prc = subprocess.run(["pgrep", "-f", token], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL)
    return [p for p in prc.stdout.decode().split() if p.strip()]


def ps_rows(pids):
    if not pids:
        return ""
    prc = subprocess.run(["ps", "-o", "pid=,state=,command="] + list(pids),
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return prc.stdout.decode("utf-8", "replace").strip()


def spawn_waiter(token):
    """harness 自己 spawn 的 waiter：唯一退出条件 = token 从进程表里消失。"""
    body = ("while pgrep -f %s >/dev/null 2>&1; do sleep 0.01; done; exit 0" % token)
    return subprocess.Popen(["/bin/sh", "-c", body])


def wait_child_exited(pid, timeout_ms):
    """对自己 spawn 的进程用 os.waitpid(WNOHANG) 轮询；返回耗时 ms，超时返回 -1（并回收它）。"""
    t0 = time.time()
    while (time.time() - t0) * 1000 < timeout_ms:
        try:
            gone, _status = os.waitpid(pid, os.WNOHANG)
        except ChildProcessError:
            return int((time.time() - t0) * 1000)
        if gone == pid:
            return int((time.time() - t0) * 1000)
        time.sleep(0.01)
    try:
        os.kill(pid, 15)
        os.waitpid(pid, 0)
    except Exception:
        pass
    return -1


def reset_requests(tag):
    """每段一个独立落盘子目录，序号从 1 起（跨段共用全局序号会让 001.json 不存在）。"""
    d = os.path.join(REQ_DIR, tag)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    REQ_SUBDIR["path"] = d
    REQ_SEQ["n"] = 0
    return d


def stop_gateway(tag):
    """收掉本段 spawn 的 JVM 并等它真没了 —— 段的边界必须是干净的。"""
    for i in range(len(SPAWNED) - 1, -1, -1):
        t, proc, fh = SPAWNED[i]
        if not t.startswith(tag):
            continue
        try:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=20)
        except Exception:
            try:
                proc.kill()
                proc.wait(timeout=10)
            except Exception:
                pass
        try:
            fh.close()
        except Exception:
            pass
        SPAWNED.pop(i)
    time.sleep(0.4)


def http_post(url, body, timeout=120.0):
    req = urllib.request.Request(url, data=body.encode("utf-8"), method="POST")
    req.add_header("Content-Type", "text/plain; charset=utf-8")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, resp.read().decode("utf-8", "replace")


# ===== JVM =====

def make_profile(name, base_url):
    d = os.path.join(OUT, "profile-" + name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    # 【必读：这里踩过一次外发事故】BotConfig.fromProperties 读 <code>.* 的键只有两条路：
    #   ① code 在 LEGACY_PROVIDERS 里 —— 而它只有 minimax 和 spark 两项；
    #   ② code 出现在 providers=a,b,c 这一行里（BotConfig.java:164 splitCodes）。
    # 第一版写的是 llm.provider=openai + openai.base.url=<stub>，"openai" 既不在 LEGACY 里
    # 也没写进 providers= → 整段被忽略 → activeProvider() 走到兜底
    # `new Provider(code, "openai", null, null, model)`（BotConfig.java:348），baseUrl=null
    # → 内核用 OpenAIProvider.DEFAULT_BASE_URL=https://api.openai.com/v1 → **真打了外网**
    # （没带 key，401 弹回，但那是事故，不是"幸好没事"）。G1/guard_outbound 就是为这条加的哨兵。
    # 现在两条腿一起走：providers= 显式声明 + Z_BOT_* 环境变量（fromEnvironment 在
    # fromProperties 之后调用，会后写覆盖，是 p17 验过的路）。
    with open(os.path.join(d, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("providers=%s\n" % PROVIDER_CODE)
        fh.write("llm.provider=%s\n" % PROVIDER_CODE)
        fh.write("%s.type=openai\n" % PROVIDER_CODE)
        fh.write("%s.api.key=%s\n" % (PROVIDER_CODE, STUB_KEY))
        fh.write("%s.base.url=%s\n" % (PROVIDER_CODE, base_url))
        fh.write("%s.model=%s\n" % (PROVIDER_CODE, MODEL))
        fh.write("agent.exec.confirm=off\n")
    return d


def jvm_env(profile):
    """任何一台 JVM 拉起之前先把 base.url 钉死在本机 stub 上。

    STUB["url"] 为空就直接抛 —— 这条 assert 是外发事故的正面防线（G1 是事后哨兵）。
    """
    if not STUB["url"]:
        raise RuntimeError("stub LLM 还没起来就拉起 JVM：拒绝（否则会打到真外网）")
    env = dict(os.environ)
    env["ZBOT_HOME"] = profile
    env["TERM"] = "xterm"
    # 第二道腿：fromEnvironment() 用这四个把 provider 钉回本机 stub（见上面注释）。
    env["Z_BOT_PROVIDER"] = PROVIDER_CODE
    env["Z_BOT_BASE_URL"] = STUB["url"]
    env["Z_BOT_API_KEY"] = STUB_KEY
    env["Z_BOT_MODEL"] = MODEL
    return env


def start_gateway(profile, tag):
    port, webhook = free_port(), free_port()
    logpath = os.path.join(LOGS, "e2e-%s-gateway.log" % tag)
    fh = open(logpath, "w", encoding="utf-8")
    cmd = ["java", "-jar", JAR, "gateway", "--config-dir", profile,
           "--port", str(port), "--webhook-port", str(webhook)]
    proc = subprocess.Popen(cmd, stdout=fh, stderr=subprocess.STDOUT,
                            env=jvm_env(profile), cwd=OUT)
    SPAWNED.append((tag, proc, fh))
    base = "http://127.0.0.1:%d" % port
    t0 = time.time()
    ready, probe = False, ""
    while (time.time() - t0) < 90.0:
        before = len(LLM_HITS)
        try:
            status, body = http_post(base + "/bot/chat", "ready-probe", timeout=8.0)
            # 就绪 = HTTP 200 **且** stub 真收到了一次请求 **且** 回复不是 Error:
            # （只看 200 会把"provider 没配好、直接 Error 返回"当成就绪）
            if status == 200 and len(LLM_HITS) > before and not body.startswith("Error"):
                ready = True
                probe = body.strip()[:80]
                break
            probe = "status=%s body=%r hits=%d" % (status, body[:80], len(LLM_HITS))
            # 快速失败：HTTP 200 但 stub 一发都没收到 ⇒ 这台 JVM 在跟别处说话。
            # 第一版没有这一条，结果 profile 配错时 90s 里烧掉约 50 次真外部请求。
            if status == 200 and len(LLM_HITS) == before:
                probe = ("MISROUTE: /bot/chat 回了 200 但 stub 命中数没涨（hits=%d）"
                         " ⇒ base.url 没生效，立刻停手" % len(LLM_HITS))
                log(probe)
                break
        except Exception as ex:
            probe = repr(ex)[:120]
        time.sleep(0.5)
    return proc, base, ready, logpath, int((time.time() - t0) * 1000), probe


def guard_outbound(logpath):
    """红线守卫：这台机器上有一把真 minimax key，任何一次跑到本机之外的 LLM 请求都算事故。"""
    try:
        with open(logpath, encoding="utf-8", errors="replace") as fh:
            text = fh.read()
    except Exception as ex:
        check("G1 请求没出本机（JVM 日志里不出现任何非 127.0.0.1 的 LLM host）", False,
              "日志读不到: %r" % ex, "guard")
        return
    hosts = set(re.findall(r"https?://([0-9a-zA-Z.\-_]+)", text))
    outside = sorted(h for h in hosts if not h.startswith("127.0.0.1") and not h.startswith("localhost"))
    check("G1 请求没出本机（JVM 日志里不出现任何非 127.0.0.1 的 LLM host）", not outside,
          "见到的外部 host=%s（base.url=%s 是本机 stub）" % (outside, "127.0.0.1"), "guard")


# ===== 段：stop =====

def section_stop(base_url):
    print("\n===== stop：长命令在飞时发 /stop，判据在内核进程表 =====", flush=True)
    token = "9%d" % (int(time.time() * 1000) % 10000000)
    TOKENS.append(token)
    global SCRIPT
    reset_requests("stop")
    SCRIPT = {"mode": "tool_call", "command": "sleep %s & wait" % token}

    holder = {"body": None, "err": None, "done": threading.Event()}

    def chat():
        try:
            holder["body"] = http_post(base_url + "/bot/chat",
                                       "跑一条会飞很久的命令", timeout=240.0)[1]
        except Exception as ex:
            holder["err"] = repr(ex)
        holder["done"].set()

    th = threading.Thread(target=chat, daemon=True)
    th.start()
    deadline = time.time() + 40
    pids = []
    while time.time() < deadline and not holder["done"].is_set():
        pids = procs_matching(token)
        if len(pids) >= 2:
            break
        time.sleep(0.05)
    check("S1 工具子进程真的在飞（bash + sleep 两个 pid 数得到）", len(pids) >= 2,
          "token=%s pids=%s ps=[%s]" % (token, pids, ps_rows(pids[:2])[:200]), "stop")
    if len(pids) < 2:
        SCRIPT = {"mode": "text", "command": None}
        holder["done"].wait(60)
        log("S1 没成立 ⇒ S2–S4 无可测对象，整段记 FAIL（不是跳过）")
        for n in ("S2", "S3", "S4"):
            check("%s （因 S1 未成立而无对象）" % n, False, "S1 失败", "stop")
        return

    waiter = spawn_waiter(token)
    t_stop = time.time()
    try:
        status, body = http_post(base_url + "/bot/stop", "", timeout=30.0)
    except Exception as ex:
        status, body = "EXC", repr(ex)
    elapsed_ms = wait_child_exited(waiter.pid, STOP_PROBE_LIMIT_MS)
    after = procs_matching(token)
    check("S2 发 /stop 后工具子进程从内核进程表消失（pgrep -f token = 0 命中）",
          len(after) == 0, "复算 pgrep=%s；停之前 ps=[%s]" % (after, ps_rows(pids)[:200]), "stop")
    check("S3 发 /stop → 工具子进程真退出 ≤ %dms（os.waitpid 自己 spawn 的 waiter）"
          % STOP_LIMIT_MS, 0 <= elapsed_ms <= STOP_LIMIT_MS,
          "elapsed_ms=%s，waiter pid=%s 由本进程 spawn、退出条件只有 pgrep 空集这一条；"
          "/bot/stop 时刻=%.3f" % (elapsed_ms, waiter.pid, t_stop), "stop")
    log("/bot/stop 回包 %s: %s" % (status, body.strip()[:160]))
    got_reply = holder["done"].wait(180)
    chat_body = (holder["body"] or holder["err"] or "(没回包)").strip()
    check("S4 那一轮以中止收尾（辅助读数；主判据是 S2/S3 的进程表）",
          got_reply and "已中止" in chat_body, "chat 回包=%r" % chat_body[:200], "stop")
    SCRIPT = {"mode": "text", "command": None}


# ===== 段：repl（真 pty） =====

def open_pty_jvm(cmd, profile, tag):
    """真 pty：harness 握 master fd；JVM 的 stdin/stdout/stderr 都挂在 slave 上。"""
    master, slave = os.openpty()
    try:
        import fcntl
        winsz = struct.pack("HHHH", 40, 120, 0, 0)
        fcntl.ioctl(slave, termios.TIOCSWINSZ, winsz)
    except Exception as ex:
        log("winsize 设置失败（不影响 pty 本身）：%s" % ex)
    logpath = os.path.join(LOGS, "e2e-%s-repl.log" % tag)
    fh = open(logpath, "w", encoding="utf-8")
    proc = subprocess.Popen(cmd, stdin=slave, stdout=slave, stderr=slave,
                            env=jvm_env(profile), cwd=OUT, preexec_fn=os.setsid,
                            close_fds=True)
    os.close(slave)
    buf = {"data": b""}

    def pump():
        while True:
            try:
                chunk = os.read(master, 8192)
            except OSError:
                break
            if not chunk:
                break
            buf["data"] += chunk
            fh.write(chunk.decode("utf-8", "replace"))
            fh.flush()

    t = threading.Thread(target=pump, daemon=True)
    t.start()
    SPAWNED.append((tag + "-ptymaster", proc, fh))
    return proc, master, buf, t, logpath


def pty_write(master, text):
    """写入时刻精确返回（这是 R2 起算延迟的那个 t0）。

    行结束符必须是 \r 不是 \n：pty 在 cooked 模式下由 tty 线边界切行，
    键盘回车就是 CR；实测写 "\n" 时 JLine 的 LineReader 不认为一行结束，
    整条命令根本没送进 agent.chat()（R2a 因此量不到工具子进程）。
    """
    os.write(master, text.replace("\n", "\r").encode("utf-8"))
    return time.time()


def section_repl(base_url):
    print("\n===== repl：真 pty REPL（openpty）+ 反面对照 =====", flush=True)
    profile = make_profile("repl", base_url)
    cmd = ["java", "-jar", JAR, "repl", "--config-dir", profile]
    proc, master, buf, pump, logpath = open_pty_jvm(cmd, profile, "repl")
    t0 = time.time()
    while time.time() - t0 < 90 and "z-bot" not in buf["data"].decode("utf-8", "replace"):
        time.sleep(0.2)
    boot = buf["data"].decode("utf-8", "replace")
    check("R0 真 pty REPL 起得来（JVM 自己打了欢迎面板）", len(boot) > 200,
          "启动耗时=%dms，pty 首屏 %d 字节，log=%s"
          % (int((time.time() - t0) * 1000), len(boot), logpath), "repl")
    if len(boot) <= 200:
        check("R1 /stop 与 /exit 在 REPL 里路由得到", False, "R0 未成立，无 REPL 可驱动", "repl")
        check("R2 反面对照：REPL 跑长命令时写 /stop 不该断掉工具", False,
              "R0 未成立，无 REPL 可驱动", "repl")
        try:
            os.kill(proc.pid, 15)
            os.waitpid(proc.pid, 0)
        except Exception:
            pass
        return

    # R2：先起一条飞很久的命令，再往 pty 里写 /stop，看工具子进程会不会消失
    token = "8%d" % (int(time.time() * 1000) % 10000000)
    TOKENS.append(token)
    global SCRIPT
    SCRIPT = {"mode": "tool_call", "command": "sleep %s & wait" % token}
    pty_write(master, "跑一条飞很久的命令\n")
    deadline = time.time() + 40
    pids = []
    while time.time() < deadline:
        pids = procs_matching(token)
        if len(pids) >= 2:
            break
        time.sleep(0.05)
    screen = buf["data"].decode("utf-8", "replace")
    check("R2a REPL 这次真的把长命令跑起来了（进程表数得到）", len(pids) >= 2,
          "token=%s pids=%s；pty 屏末 %d 字节=%r" % (token, pids, len(screen[-200:]),
                                                  screen[-200:]), "repl")
    if len(pids) < 2:
        check("R2b 反面对照（因 R2a 未成立而无对象）", False, "REPL 里没起出长命令", "repl")
    else:
        t_write = pty_write(master, "/stop\n")
        time.sleep(2.0)
        still = procs_matching(token)
        check("R2b 反面对照：chat 在飞时写进 pty 的 /stop 不会中断工具（进程还在）",
              len(still) > 0 and len(pids) >= 2,
              "写入时刻=%.3f，2s 后 pgrep 仍=%s ⇒ TerminalChannel.run() 在 agent.chat() 里同步阻塞、"
              "这段时间根本不读 stdin（实测出来的，不是读代码读出来的）⇒ 中断段必须走真进程层"
              % (t_write, still), "repl")
    SCRIPT = {"mode": "text", "command": None}
    subprocess.run(["pkill", "-f", token], stdout=subprocess.DEVNULL)
    # 先按 Ctrl-C 让 JLine 从"命令在飞"的状态回到提示符，否则 REPL 不会退；
    # 回提示符本身就是"斜杠命令在 REPL 里路由得到"的证据（/help 也走同一条 dispatch）。
    try:
        os.write(master, b"\x03")
    except OSError:
        pass
    t_ctrlc = time.time()
    back_ms = -1
    while time.time() - t_ctrlc < 20:
        if "z-bot" in buf["data"].decode("utf-8", "replace")[-400:]:
            back_ms = int((time.time() - t_ctrlc) * 1000)
            break
        time.sleep(0.1)
    pty_write(master, "/exit\n")
    t_exit = time.time()
    gone, status, rc = -1, -1, None
    while time.time() - t_exit < 20:
        try:
            gone, status = os.waitpid(proc.pid, os.WNOHANG)
        except ChildProcessError:
            gone, status = proc.pid, -1
            break
        if gone == proc.pid:
            break
        time.sleep(0.1)
    if gone != proc.pid:
        try:
            os.kill(proc.pid, 15)
            gone, status = os.waitpid(proc.pid, 0)
        except Exception:
            pass
    rc = os.waitstatus_to_exitcode(status) if status >= 0 else None
    tail = buf["data"].decode("utf-8", "replace")[-400:]
    check("R1 REPL 自己收得掉（/exit → harness waitpid 拿到退出码）",
          gone == proc.pid and ("再见" in tail or "bye" in tail.lower()),
          "waitpid 回 %s exit=%s，屏末=%r，Ctrl-C 后回到提示符耗时=%sms"
          % (gone, rc, tail[-160:], back_ms), "repl")


# ===== 段：cache =====

def n_requests():
    return len([f for f in os.listdir(REQ_SUBDIR["path"])
                if f.endswith(".json") and not f.endswith(".headers.json")])


def req_messages(seq):
    path = os.path.join(REQ_SUBDIR["path"], "%03d.json" % seq)
    with open(path, encoding="utf-8") as fh:
        return json.load(fh).get("messages") or []


def section_cache(base_url):
    print("\n===== cache：实际发出去的 system 字节逐轮相同 =====", flush=True)
    profile = make_profile("cache", base_url)
    proc, base, ready, logpath, ms, probe = start_gateway(profile, "cache")
    check("C0 缓存段用的那台真 JVM 起来了（stub 收到过它的请求）", ready,
          "ready=%s 耗时=%dms probe 回包=%r log=%s" % (ready, ms, probe, logpath), "cache")
    guard_outbound(logpath)
    if not ready:
        for n in ("C1", "C2", "C3"):
            check("%s（因 C0 未成立而无对象）" % n, False, "JVM 没起来", "cache")
        return
    global SCRIPT
    reset_requests("cache")
    SCRIPT = {"mode": "text", "command": None}
    try:
        s1, b1 = http_post(base + "/bot/chat", "第一句：缓存不变量", timeout=120.0)
    except Exception as ex:
        check("C0b 第一次 chat 打通了", False, repr(ex), "cache")
        return
    time.sleep(1.2)          # 让时钟这一路真的走一秒以上
    # ===== 中途写盘（P24 交接件要的正是这个时刻）=====
    # 冻结的语义：SOUL.md 是 system prompt 的头，但**中途改它不许进 prompt**；
    # MEMORY.md 走 user 消息。两条一起写，C5/C6 才分得出"冻住了"与"根本没读到盘"。
    memdir = os.path.join(profile, "memories")
    os.makedirs(memdir, exist_ok=True)
    with open(os.path.join(memdir, "SOUL.md"), "a", encoding="utf-8") as fh:
        fh.write("\n%s\n" % MIDRUN_SOUL_MARKER)
    with open(os.path.join(memdir, "MEMORY.md"), "w", encoding="utf-8") as fh:
        fh.write("%s\n" % MIDRUN_MEMORY_MARKER)
    try:
        s2, b2 = http_post(base + "/bot/chat", "第二句：缓存不变量", timeout=120.0)
    except Exception as ex:
        s2, b2 = 0, repr(ex)
    n_req = n_requests()
    check("C0c 两次 chat 各打了一次 LLM（stub 收到 2 条请求）", n_req >= 2,
          "stub 落盘请求数=%d，回包 1=%r 2=%r" % (n_req, b1.strip()[:60], b2.strip()[:60]), "cache")
    if n_req < 2:
        for n in ("C1", "C2", "C3"):
            check("%s（因 stub 只收到 %d 条请求而无对象）" % (n, n_req), False, "无对象", "cache")
        return
    m1, m2 = req_messages(1), req_messages(2)
    sys1 = next((m["content"] for m in m1 if m.get("role") == "system"), None)
    sys2 = next((m["content"] for m in m2 if m.get("role") == "system"), None)
    u1 = next((m["content"] for m in reversed(m1) if m.get("role") == "user"), "")
    u2 = next((m["content"] for m in reversed(m2) if m.get("role") == "user"), "")
    b_sys1 = (sys1 or "").encode("utf-8")
    b_sys2 = (sys2 or "").encode("utf-8")
    check("C1 两次 chat 实际发出的 system prompt 逐字节相同（比 stub 落盘的请求原文）",
          sys1 is not None and sys2 is not None and b_sys1 == b_sys2,
          "len1=%d len2=%d md5_1=%s md5_2=%s sha256_1=%s sha256_2=%s"
          % (len(b_sys1), len(b_sys2),
             md5_of_bytes(b_sys1)[:8], md5_of_bytes(b_sys2)[:8],
             sha256_of_bytes(b_sys1), sha256_of_bytes(b_sys2)),
          "cache")
    clock1 = clock_line_of(u1)
    clock2 = clock_line_of(u2)
    check("C2 运行时上下文（时钟）确实走 user 消息，system 里没有它",
          "当前时间" in u1 and "当前时间" in u2 and "当前时间" not in (sys1 or ""),
          "user1 时钟行=%r user2 时钟行=%r；system 含「当前时间」=%s"
          % (clock1, clock2, "当前时间" in (sys1 or "")), "cache")
    check("C3 两轮之间那个易变时钟确实变了（否则 C1 的「相同」可以是两轮都没注入）",
          bool(clock1) and bool(clock2) and clock1 != clock2,
          "clock1=%r clock2=%r" % (clock1, clock2), "cache")
    check("C4 用户原话仍然逐字出现在 user 消息末尾（上下文头没盖过正事）",
          u1.endswith("第一句：缓存不变量") and u2.endswith("第二句：缓存不变量"),
          "user1 尾部=%r" % u1[-40:], "cache")
    # C5/C6 是本段给 P24 的交接件：冻结到底冻住了什么、改道到底改道到哪，两条互相反空跑。
    with open(os.path.join(memdir, "SOUL.md"), encoding="utf-8") as fh:
        soul_on_disk = fh.read()
    check("C5 中途写进 SOUL.md 的那一行不进 system prompt（快照冻结；盘上真有那一行）",
          MIDRUN_SOUL_MARKER in soul_on_disk and MIDRUN_SOUL_MARKER not in (sys2 or ""),
          "盘上 SOUL.md 含哨兵=%s；两轮 system 含哨兵=%s/%s；sha256_1=%s sha256_2=%s"
          % (MIDRUN_SOUL_MARKER in soul_on_disk, MIDRUN_SOUL_MARKER in (sys1 or ""),
             MIDRUN_SOUL_MARKER in (sys2 or ""),
             sha256_of_bytes(b_sys1), sha256_of_bytes(b_sys2)), "cache")
    check("C6 反空跑：中途写的那行记忆下一轮真到了模型（在 user 消息里，不在 system 里）",
          MIDRUN_MEMORY_MARKER in u2 and MIDRUN_MEMORY_MARKER not in (sys2 or ""),
          "user2 含记忆哨兵=%s；system2 含记忆哨兵=%s；user1 含记忆哨兵=%s（上一轮不许被回写）"
          % (MIDRUN_MEMORY_MARKER in u2, MIDRUN_MEMORY_MARKER in (sys2 or ""),
             MIDRUN_MEMORY_MARKER in u1), "cache")


def md5_of_bytes(bs):
    return hashlib.md5(bs).hexdigest()


def sha256_of_bytes(bs):
    """P24 交接件要的字节级指纹：同一会话两轮的 system prompt sha256（冻结不变量的直接读数）。"""
    return hashlib.sha256(bs).hexdigest()


def clock_line_of(user_text):
    for line in (user_text or "").splitlines():
        if line.startswith("当前时间"):
            return line.strip()
    return ""


# ===== 段：home / creds =====

def home_reading():
    items = sorted(os.listdir(REAL_HOME)) if os.path.isdir(REAL_HOME) else []
    return {
        "count": len(items),
        "items": items,
        "config_md5": md5(os.path.join(REAL_HOME, "config.properties")),
        "state_md5": md5(os.path.join(REAL_HOME, "state.db")),
    }


def section_home(before):
    print("\n===== home：~/.zbot 跑前跑后不变（红线） =====", flush=True)
    after = home_reading()
    check("H1 ~/.zbot 项数未变（`ls -A ~/.zbot | wc -l`）", after["count"] == before["count"],
          "before=%d after=%d items=%s" % (before["count"], after["count"], after["items"]), "home")
    check("H2 config.properties md5 未变", after["config_md5"] == before["config_md5"],
          "before=%s after=%s（只取 md5 前缀，不打印内容）"
          % ((before["config_md5"] or "")[:8], (after["config_md5"] or "")[:8]), "home")
    check("H3 state.db md5 未变", after["state_md5"] == before["state_md5"],
          "before=%s after=%s" % ((before["state_md5"] or "")[:8],
                                  (after["state_md5"] or "")[:8]), "home")
    return after


def section_creds():
    print("\n===== creds：凭证卫生（负向 + 反向钉住在同一条里） =====", flush=True)
    # 反向钉住：stub key 必须真进了产物（否则"没有真 key"那条是空跑）
    auth_values = set()
    for hit in LLM_HITS:
        v = hit["headers"].get("authorization") or hit["headers"].get("x-api-key")
        if v:
            auth_values.add(v)
    pinned = auth_values == set(["Bearer " + STUB_KEY]) or auth_values == set([STUB_KEY])
    # 同一条里反向钉住第二半：这把 key 不只到了 stub，还落进了产物文件
    hdr = os.path.join(REQ_SUBDIR["path"], "001.headers.json")
    req = os.path.join(REQ_SUBDIR["path"], "001.json")
    in_artifact = os.path.isfile(hdr) and os.path.isfile(req)
    check("K1 反向钉住：stub key 真进了产物（出站请求头 = 它，且落盘了）", pinned and in_artifact,
          "stub 收到的 Authorization 值集合=%s（%d 次调用），产物=%s/001.headers.json 存在=%s"
          % (sorted(auth_values), len(LLM_HITS), REQ_SUBDIR["path"], in_artifact), "creds")

    # 扫描面 = **本跑新建或本跑改动过的运行期产物**（按文件系统证据 mtime 定，不按文件名猜）。
    # 为什么不再按名字：老写法是"logs/ 与 out/ 底下的，但名字里带 _run / e2e_full /
    # e2e_stop_only 的排除"——那既挡不住别的工具往 logs/ 里落一个读数（G12：
    # p12c_k2_probe.log 里逐字写着 api.key=not-configured，从那一刻起每一跑都被
    # 钉成假红，且红跑自己又把这句话写回自己的日志/JSON，自我续命），
    # 也说不清"凭什么这个文件算产物"。现在判据只有一句：
    # **这一跑动过的字节里，不许出现第二把 key、不许出现真配置的痕迹。**
    # 排除的是"本跑没碰过的既有文件"（上一跑的 scratch、别的工具的读数）——
    # 它们不是本跑的产物；本跑要是去改写它，mtime 进窗，照样在面上。一条没藏。
    # 另：本跑的读数文件（--json 的 out/*.json、逐条 PASS/FAIL 行）是**扫描之后**
    # 才写的，所以扫不到它们自己的尾部 —— 那正是自锁的断点。真凭证要是泄了，
    # 一定是先落到本跑写的产物（out/llm-requests/、logs/e2e-*.log、out/profile-*/），
    # 那些都在面上；读数只是它们的转述，不当第二次扫描对象。
    def is_readings_dump(pth):
        """结构判定：这份 json 是不是本量具自己写的「读数转述」。

        只按形状认，不按文件名猜（G12 的教训：按名字排除既挡不住别的工具往
        logs/ 落读数，也会随命名漂移）。形状 = 顶层恰好 only/checks/llm_calls/
        home_before/home_after 五键，且 checks 每条恰好 name/status/detail/
        section 四字段 —— 这是本文件 check() + main(--json) 与 p12_mutation.py
        的写出形状；bot 运行期产物（out/llm-requests/*.headers.json、
        out/profile-*/）不长这样。
        """
        if not pth.endswith(".json"):
            return False
        try:
            with open(pth, encoding="utf-8") as fh:
                d = json.load(fh)
        except Exception:
            return False
        if not isinstance(d, dict) or sorted(d) != ["checks", "home_after", "home_before",
                                                   "llm_calls", "only"]:
            return False
        cs = d["checks"]
        return bool(cs) and all(isinstance(c, dict) and sorted(c) == ["detail", "name",
                                                                     "section", "status"]
                                for c in cs)

    artifacts, skipped, readings = [], [], []
    for root, dirs, files in os.walk(HERE):
        dirs[:] = [d for d in dirs if d not in (".git",)]
        for f in files:
            pth = os.path.join(root, f)
            if not f.endswith((".json", ".log", ".txt", ".tsv", ".md", ".py", ".properties", ".db")):
                continue
            rel = os.path.relpath(pth, HERE)
            if not (rel.startswith("out" + os.sep) or rel.startswith("logs" + os.sep)):
                continue
            try:
                touched_by_this_run = os.stat(pth).st_mtime >= RUN_START - 0.5
            except OSError:
                touched_by_this_run = False
            if not touched_by_this_run:
                skipped.append(pth)
            elif is_readings_dump(pth):
                # 连续两跑之间 mtime 只有 1s 粒度：上一跑收尾写的读数会和这一跑
                # 的起跑撞进同一秒（实测 run1.json mtime=13:09:24 == run2 起跑
                # 13:09:24，判据 st_mtime >= RUN_START-0.5 ⇒ 进窗），于是 K3 被
                # **自己标题里的字面词 "minimax"** 钉成间歇假红。读数是真产物的
                # 转述、且本跑是先扫后写（自锁断点），从来不是被扫对象 —— 这里把
                # 转述请出面，被扫的 bot 运行期产物一个没少，判据没放松。
                readings.append(pth)
            else:
                artifacts.append(pth)
    bearer, hits = set(), []
    for p in artifacts:
        try:
            with open(p, "rb") as fh:
                text = fh.read().decode("utf-8", "replace")
        except Exception:
            continue
        # key 值在**整篇文本**上配，别先截一个 80 字符窗口再配：
        # 窗口正好切在 "Bearer stub-key-not-real" 中间时，K2 会多出一个
        # "stub-key-" 这种半截值，把"每一种 key 值只有 stub"变成假红（实测过）。
        for m in re_iter(r"(?i)bearer\s+([A-Za-z0-9._\-]{8,})", text):
            bearer.add(m)   # re_iter 给的是**捕获组**，不是整串
        # 值用前后界限钉住整串再收：早先写成 .?.? 会把 "api.key=stub-key-not-real"
        # 连前缀一起当成"一种 key 值"，K2 于是假红（见到的 key 值里冒出
        # 'api.key=stub-key-not-real' 这种拼出来的串）。
        for m in re_iter(r"(?ix)(?:x-api-key|api[._-]?key)\s*[=:]\s*[\"']?([A-Za-z0-9._\-]{8,})(?![A-Za-z0-9._\-])", text):
            bearer.add(m)
        low = text.lower()
        if "minimax" in low or REAL_HOME.lower() in low:
            hits.append(p)
    check("K2 产物里出现的每一种 key 值都只有 stub-key-not-real",
          bearer == set([STUB_KEY]) and bool(bearer),
          "扫了 %d 个运行期产物（本跑新建/改动过的；另有 %d 个本跑没碰过的既有文件不在面上）；"
          "见到的 key 值=%s" % (len(artifacts), len(skipped), sorted(bearer)), "creds")
    check("K3 运行期产物里不出现真配置的痕迹（grep minimax 或真 ~/.zbot 绝对路径）",
          not hits, "扫描面=%d 个；命中=%s；本跑没碰过、故不在面上的既有文件=%s；"
          "未纳入扫描的 harness 读数文件=%s"
          % (len(artifacts), hits, [os.path.relpath(x, HERE) for x in skipped][:6],
             [os.path.relpath(x, HERE) for x in readings]), "creds")


def re_iter(pattern, text):
    """有捕获组时给组 1，没有才给整串。

    这里踩过一次：早期版本一律 group(0)，于是 K2 收到的"key 值"其实是
    "Bearer stub-key-not-real" / "api.key=stub-key-not-real" 这种带前缀的整串，
    跟 STUB_KEY 永远对不上 —— 哨兵自己假红。
    """
    import re
    for m in re.finditer(pattern, text):
        yield m.group(1) if m.groups() else m.group(0)


# ===== 收尾 =====

def cleanup():
    for tag, proc, fh in SPAWNED:
        try:
            if proc.poll() is None:
                proc.kill()
                proc.wait(timeout=15)
        except Exception:
            pass
        try:
            if not fh.closed:
                fh.close()
        except Exception:
            pass
    for token in TOKENS:
        subprocess.run(["pkill", "-f", token], stdout=subprocess.DEVNULL)


def summarize(only, home_before):
    fails = [c for c in CHECKS if c["status"] == "FAIL"]
    print("\n===== summary =====", flush=True)
    print("段=%s 检查条数=%d PASS=%d FAIL=%d"
          % (only or "all", len(CHECKS), len(CHECKS) - len(fails), len(fails)), flush=True)
    for c in CHECKS:
        print("  %-5s [%s] %s" % (c["status"], c["section"], c["name"]), flush=True)
    print("  其中真进程层读数来源: pgrep -f <token> / os.waitpid(harness 自己 spawn 的子进程) / "
          "stub 落盘请求原文；**没有一条判据来自 repl 日志行**", flush=True)
    if os.path.isdir(REQ_DIR):
        print("  stub 落盘请求 %d 份: %s" % (n_requests(), REQ_DIR), flush=True)
    print("  ~/.zbot 跑前跑后: 项数 %s→%s, config md5 前缀 %s, state.db md5 前缀 %s"
          % (home_before["count"], home_reading()["count"],
             (home_before["config_md5"] or "")[:8], (home_before["state_md5"] or "")[:8]), flush=True)
    return 1 if fails else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=None,
                    help="stop / repl / cache / home / creds（单选一段；默认全跑）")
    ap.add_argument("--json", default=None, help="把逐条读数写成 JSON")
    args = ap.parse_args()
    only = args.only
    if not os.path.isdir(LOGS):
        os.makedirs(LOGS)
    if not os.path.isdir(REQ_DIR):
        os.makedirs(REQ_DIR)
    if not os.path.isfile(JAR):
        print("FATAL 缺 %s —— 先跑 mvn -o package -DskipTests -pl z-bot-core" % JAR, flush=True)
        return 2
    home_before = home_reading()
    brc, bsha, bhead, bdirty = build_gate(only or "all")
    if brc != 0:
        check("B0 本跑真进程用的是现打的 jar（打包 rc=0）", False,
              "mvn -o package rc=%s（见 logs/package-%s.log）—— 不拿旧 jar 顶包，拒绝开跑"
              % (brc, only or "all"), "build")
        print("B0 不成立：不跑真进程（避免把上一位留下的字节当成本树的读数）。", flush=True)
        return 3
    check("B0 本跑真进程用的是现打的 jar（打包 rc=0）", True,
          "打包 rc=0；jar sha256=%s；git HEAD=%s；z-bot-core/src 未提交改动=%s"
          % ((bsha or "MISSING")[:16], bhead, "无" if not bdirty else "%d 行" % len(bdirty.splitlines())),
          "build")
    srv, base_url = start_stub()
    try:
        if only in (None, "stop", "repl"):
            profile = make_profile("main", base_url)
            proc, base, ready, logpath, ms, probe = start_gateway(profile, "main")
            check("G0 主 JVM 起来并且 stub LLM 真收到了它的请求", ready,
                  "ready=%s 耗时=%dms probe 回包=%r profile=%s log=%s"
                  % (ready, ms, probe, profile, logpath),
                  "stop" if only != "repl" else "repl")
            guard_outbound(logpath)
            if only in (None, "stop"):
                if ready:
                    section_stop(base)
                else:
                    for n in ("S1", "S2", "S3", "S4"):
                        check("%s（因 G0 未成立而无对象）" % n, False, "JVM 没起来", "stop")
            if only in (None, "repl"):
                # 上一段（stop）那台 JVM 必须先收掉：它要是还占着，下一段的
                # 就绪探测会被 busyGuard 一路 409（实测过，见 EVIDENCE）
                stop_gateway("main")
                section_repl(base_url)
        if only in (None, "cache"):
            stop_gateway("repl")
            section_cache(base_url)
    finally:
        cleanup()
    # 红线读数放在所有 JVM 收工之后取：跑前/跑后各一次，中间隔着真进程层的全部副作用
    check("B0b 跑的过程中那台 jar 的字节没被换过",
          sha256_of(JAR) == JAR_SHA_AT_BUILD["sha"],
          "开跑前 sha256=%s / 收工后 sha256=%s（防止别处在半途重打包）"
          % ((JAR_SHA_AT_BUILD["sha"] or "MISSING")[:16], (sha256_of(JAR) or "MISSING")[:16]),
          "build")
    if only in (None, "home"):
        section_home(home_before)
    if only in (None, "creds"):
        section_creds()
    if args.json:
        os.makedirs(os.path.dirname(args.json), exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"only": only, "checks": CHECKS,
                       "llm_calls": len(LLM_HITS),
                       "home_before": home_before, "home_after": home_reading()},
                      fh, ensure_ascii=False, indent=1)
        print("读数 JSON: %s" % args.json, flush=True)
    rc = summarize(only, home_before)
    srv.shutdown()
    return rc


if __name__ == "__main__":
    sys.exit(main())
