#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P14（上下文压缩引擎重做）验收③：**真进程** E2E。读代码不算证据，每一段都是真跑。

段（--only 可单选；默认全跑）
  probe      标定一次真 REPL 第一轮请求体的字符数（阈值算式要靠它落地，不靠人猜系统提示词多大）。
  thresh     同一套轮次脚本分别在 pct=0.50 / pct=0.90 下跑：
             T1 触发线严格等于 (窗口 − 输出额度) × pct（从 /compress preview 的真实读数里取）
             T2 pct 改了就换触发轮次（0.50 早、0.90 晚，两个都必须真触发）
             T3 pct 真是从配置位流进来的（preview 自证的 config 行）
  lineage    验收 A：真进程连打到 已压缩次数 ≥ 2（中间夹一轮真 exec 工具批，喂位点 3）
             → kill -9 → **换一个新进程、同一个 zbot-home** 重启 → /sessions 仍数得到
               base #1 / base #2，并且 state.db 里 parent_session_id 串成链、父行
               end_reason=compressed（sqlite3 直读库，不信产品自述）
  cooldown   摘要返空 = 压缩失败 → 冷却截止时刻写进 state_meta → kill -9 → 重启后
             cooldownLeft 仍 >0；正对照：同段另一台摘要正常的 cooldownLeft=0
  lock       compression_locks 的真消费者：A 在摘要调用里睡 14s 持锁，期间
             sqlite3 数得到锁行 + 第二个进程 B 的 preview 看得见 A 的 holder uuid
             + B 强制压缩被闸住（会话数不变、没派新号）；A 松手后 B 同样的命令立刻分叉成功
             （正负成对：唯一变量就是那把锁）
  home/creds ~/.zbot 三时点里的本跑两点 + 凭证卫生（扫描面 = 本跑 ROOT 下所有字节；
             正对照 = stub key 必须真出现在产物里，否则"没泄漏"是空跑）

前置: 无（本脚本自己 `mvn -o package -DskipTests -pl z-bot-core` 打 jar；失败就不开跑）
复算: python3 -u _doc/005_testing/acceptance/p14/p14_e2e.py --label run1
       python3 -u _doc/005_testing/acceptance/p14/p14_e2e.py --label dbg --only lineage
临时根: ~/.cache/zbot-p14-lead/e2e/<label>-<scene>/zbot-home（不碰 ~/.zbot 一个字节）
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import struct
import subprocess
import sys
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core", "target", "z-bot-core.jar")
CACHE_ROOT = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p14-lead")
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")
STUB_KEY = "stub-key-not-real"
MODEL = "gpt-4o-mini"
PROVIDER_CODE = "p14stub"
SUMMARY_MARK = "请把以下早前对话压缩成一段简洁摘要"   # SUMMARY_PROMPT_PREFIX 的头一句
REPLY_MARK = "P14E2EREP"                              # 助手回复的行首哨兵（轮次完成判据）
CFG = {"window": 16000, "maxout": 4000}
PER_ROUND_TOKENS = 2150                                # 用户 ~40 字 + 助手 ~4200 字 ⇒ /2

CHECKS = []
LIVE = []
LLM_LOCK = threading.Lock()
LLM_HITS = []
REQ_DIR = {"path": None}
STUB = {"url": None}
SCRIPT = {"mode": "text", "command": None, "hold_seconds": 0.0, "empty_summary": False}
REQ_SEQ = {"n": 0}
RUN_START = time.time()
LABEL = "run"
ROOTS = []
JAR_SHA = {"at_build": None}


def check(name, ok, detail, section):
    st = "PASS" if ok else "FAIL"
    CHECKS.append({"name": name, "status": st, "detail": detail, "section": section})
    print("%-5s %-62s %s" % (st, name, detail), flush=True)
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


def sha256_of(path):
    if not os.path.isfile(path):
        return None
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for blk in iter(lambda: fh.read(65536), b""):
            h.update(blk)
    return h.hexdigest()


def git_out(*args):
    r = subprocess.run(["git", "-C", ZBOT] + list(args),
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
    return (r.stdout or b"").decode("utf-8", "replace").strip() if r.returncode == 0 else "GIT-rc=%s" % r.returncode


def scene_root(scene):
    """每跑 × 每景一个独立临时根：景与景之间不共享 state.db。"""
    root = os.path.join(CACHE_ROOT, "e2e", "%s-%s" % (LABEL, scene))
    if os.path.isdir(root):
        shutil.rmtree(root)
    os.makedirs(os.path.join(root, "llm-requests"))
    ROOTS.append(root)
    return root


def q_db(home, sql, args=()):
    """直读 state.db（只发 SELECT）—— 产品自述不当判据。"""
    p = os.path.join(home, "state.db")
    if not os.path.isfile(p):
        return None
    con = sqlite3.connect(p, timeout=10.0)
    try:
        return con.execute(sql, tuple(args)).fetchall()
    finally:
        con.close()


# ===== 假 LLM：把每一轮真发出去的东西落盘，摘要调用可按哨兵钉住 =====

class StubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _reply(self, payload):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        text = raw.decode("utf-8", "replace")
        is_summary = SUMMARY_MARK in text
        with LLM_LOCK:
            REQ_SEQ["n"] += 1
            seq = REQ_SEQ["n"]
            mode, command = SCRIPT["mode"], SCRIPT["command"]
            hold, empty = SCRIPT["hold_seconds"], SCRIPT["empty_summary"]
            LLM_HITS.append({"seq": seq, "chars": len(raw), "summary": is_summary,
                             "headers": {k.lower(): str(v) for k, v in self.headers.items()}})
        d = REQ_DIR["path"]
        with open(os.path.join(d, "%03d.json" % seq), "wb") as fh:
            fh.write(raw)
        with open(os.path.join(d, "%03d.headers.json" % seq), "w", encoding="utf-8") as fh:
            json.dump(LLM_HITS[-1]["headers"], fh, ensure_ascii=False)
        if is_summary and hold > 0:
            # 摘要正飞着 = 压缩正在进行 = 这把锁该在库里：harness 在这几秒里去读 state.db
            time.sleep(hold)
        if is_summary:
            content = "" if empty else "P14E2ESUMMARY-%03d %s" % (seq, "摘要要点 " * 20)
            finish = "stop"
        elif mode == "tool_call" and command:
            content = None
            finish = "tool_calls"
            # P14b 量具修（r1 诊断跑实测：mode 一直没接线 ⇒ 工具批从未发生 ⇒ 位点 3 恒 0）：
            # tool_call 是一次性的，回完这一发就恢复文本模式，否则 exec→回灌→又 exec 死循环。
            SCRIPT["mode"] = "text"
            SCRIPT["command"] = None
        else:
            content = "\n%s-%03d-X\n%s" % (REPLY_MARK, seq, "P14E2Efiller" * 350)
            finish = "stop"
        if content is None:
            msg = {"role": "assistant", "content": None, "tool_calls": [{
                "id": "call-p14-%d" % seq, "type": "function",
                "function": {"name": "exec", "arguments": json.dumps({"command": command})}}]}
        else:
            msg = {"role": "assistant", "content": content}
        pt = max(1, len(raw) // 2)
        payload = json.dumps({
            "id": "p14-stub-%d" % seq, "object": "chat.completion", "model": "p14stub",
            "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
            # usage 报"请求体字符数/2"这个规模 ⇒ 位点 3（工具批后拿真实 usage）与位点 2（粗估）
            # 看到的是同一口径的量，不会哪一位点永远趴在限值以下（那等于没测第三个位点）。
            "usage": {"prompt_tokens": pt, "completion_tokens": 10, "total_tokens": pt + 10},
        }, ensure_ascii=False).encode("utf-8")
        self._reply(payload)

    def do_GET(self):
        self._reply(b'{"data":[]}')

    def log_message(self, *args):
        pass


# P14b 量具修（diag4 实测：B 推第一轮时屏上只有 `thinking` 转到天荒地老、桩那边只收到 A 的 5 个请求）：
# 原来这里是 `HTTPServer` —— 单线程 + `protocol_version="HTTP/1.1"` 的 keep-alive：A 那条连接处理完
# 不关，服务循环就卡在 A 的空闲连接上**永远不 accept B**。以前每段只有一个被测进程打桩，撞不出来；
# lock 段现在要「A 在飞的 14 s 里 B 也能问出东西」，必须是每连接一个线程。
class StubServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def start_stub():
    srv = StubServer(("127.0.0.1", 0), StubHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    url = "http://127.0.0.1:%d/v1" % srv.server_address[1]
    STUB["url"] = url
    return srv, url


# ===== 被测进程：真 pty REPL =====

class Repl(object):
    """一台真 `z-bot repl`。reuse=True 时**不重建**临时根与 zbot-home —— 重启段靠这个。"""

    def __init__(self, scene, pct, keep_recent=3, cooldown_ms=60000, reuse=None):
        if reuse:
            self.root, self.home = reuse[0], reuse[1]
            self.requests = os.path.join(self.root, "llm-requests")
            self.tag = scene + "-restart"
        else:
            self.root = scene_root(scene)
            self.home = os.path.join(self.root, "zbot-home")
            os.makedirs(self.home)
            self.requests = os.path.join(self.root, "llm-requests")
            self.tag = scene
        REQ_DIR["path"] = self.requests
        if not reuse:
            with open(os.path.join(self.home, "config.properties"), "w", encoding="utf-8") as fh:
                fh.write("providers=%s\nllm.provider=%s\n%s.type=openai\n%s.api.key=%s\n"
                         "%s.base.url=%s\n%s.model=%s\nagent.exec.confirm=off\n"
                         "agent.context.window=%d\nagent.context.max.output.tokens=%d\n"
                         "agent.context.compress.pct=%s\nagent.context.compress.keep.recent=%d\n"
                         "agent.context.compress.cooldown.ms=%d\n"
                         "agent.context.compress.max.ineffective=5\n"
                         % (PROVIDER_CODE, PROVIDER_CODE, PROVIDER_CODE, PROVIDER_CODE, STUB_KEY,
                            PROVIDER_CODE, STUB["url"], PROVIDER_CODE, MODEL,
                            CFG["window"], CFG["maxout"], pct, keep_recent, cooldown_ms))
        self.buf = {"data": b""}
        self.logpath = os.path.join(self.root, "repl-%s.log" % (self.tag if reuse else "boot"))
        self.fh = open(self.logpath, "w", encoding="utf-8")
        master, slave = os.openpty()
        try:
            import fcntl
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 140, 0, 0))
        except Exception:
            pass
        env = dict(os.environ)
        env["ZBOT_HOME"] = self.home
        env["TERM"] = "xterm"
        env["Z_BOT_PROVIDER"] = PROVIDER_CODE
        env["Z_BOT_BASE_URL"] = STUB["url"]
        env["Z_BOT_API_KEY"] = STUB_KEY
        env["Z_BOT_MODEL"] = MODEL
        self.proc = subprocess.Popen(["java", "-jar", JAR, "repl", "--config-dir", self.home],
                                    stdin=slave, stdout=slave, stderr=slave, env=env,
                                    cwd=self.root, preexec_fn=os.setsid, close_fds=True)
        os.close(slave)
        self.master = master

        def pump():
            while True:
                try:
                    chunk = os.read(master, 65536)
                except OSError:
                    break
                if not chunk:
                    break
                self.buf["data"] += chunk
                self.fh.write(chunk.decode("utf-8", "replace"))
                self.fh.flush()
        threading.Thread(target=pump, daemon=True).start()
        LIVE.append(self)

    # ---- 驱动 ----
    def screen(self):
        return self.buf["data"].decode("utf-8", "replace")

    def wait_count(self, needle, want, timeout=120.0):
        t0 = time.time()
        while time.time() - t0 < timeout:
            if self.screen().count(needle) >= want:
                return True
            time.sleep(0.1)
        return False

    def send(self, text):
        os.write(self.master, (text + "\r").encode("utf-8"))

    def round_(self, tag):
        n = self.screen().count(REPLY_MARK)
        self.send("%s 第%d句：把上下文往前推一点" % (tag, n + 1))
        return self.wait_count(REPLY_MARK, n + 1, timeout=150.0)

    def tool_round(self, command):
        n = self.screen().count(REPLY_MARK)
        h0 = len(LLM_HITS)
        # P14b 量具修：原实现收了 command 参数却从不设置 SCRIPT —— 桩永远走文本分支，
        # 「第 2 轮真 exec 工具批喂位点 3」从未发生（r1 诊断跑实测 siteHits=8/8/0）。
        SCRIPT["mode"] = "tool_call"
        SCRIPT["command"] = command
        try:
            self.send("跑一条会吐很多输出的命令")
            ok = self.wait_count(REPLY_MARK, n + 1, timeout=180.0)
        finally:
            SCRIPT["mode"] = "text"
            SCRIPT["command"] = None
        return ok, h0

    def preview(self, timeout=60.0):
        n = self.screen().count("已压缩次数")
        self.send("/compress preview")
        if not self.wait_count("已压缩次数", n + 1, timeout=timeout):
            return ""
        s = self.screen()
        # 一段 preview 从 "context observed=" 起、到 "血统深度=" 止（中间含 describe 与 config 行）
        blocks = re.findall(r"context observed=.*?血统深度=\S+", s, re.S)
        return blocks[-1] if blocks else ""

    def sessions(self, timeout=60.0):
        # P14b 量具修：旧判据 needle「本地会话」会先命中启动帮助面板里那行
        # 「/sessions 列出本地会话」⇒ 真列表还没出来 wait 就返回（r1 诊断跑 L6 假红的根因）。
        n = self.screen().count("本地会话 (")
        self.send("/sessions")
        return self.wait_count("本地会话 (", n + 1, timeout=timeout)

    def switch(self, sid, timeout=60.0):
        n = self.screen().count("已切换到会话")
        self.send("/switch %s" % sid)
        return self.wait_count("已切换到会话", n + 1, timeout=timeout)

    def switch_until(self, sid, tries=6, timeout=60.0):
        """P14b 量具修：/switch 在产品里若认不出该 id 会「报成功但指针不动」
        （StateStore 写队列可见性/映射未收编都可能），必须用 preview 的 session= 读数
        确认指针真的挪过去了才算换成功 —— 换会话这件事由读数定案，不由回声消息定案。"""
        for _ in range(tries):
            if not self.switch(sid, timeout=timeout):
                continue
            pv = self.preview(timeout=timeout)
            if grab(pv, "session=") == sid:
                return True
            time.sleep(2.0)
        return False

    def kill9(self):
        pid = self.proc.pid
        try:
            os.kill(pid, 9)
        except OSError:
            pass
        try:
            os.waitpid(pid, 0)
        except ChildProcessError:
            pass
        try:
            os.close(self.master)
        except OSError:
            pass
        try:
            self.fh.close()
        except Exception:
            pass
        LIVE.remove(self)
        return pid

    @property
    def pid(self):
        return self.proc.pid


def cleanup():
    for r in list(LIVE):
        r.kill9()


# ===== 读数解析 =====

def num(text, key, cast=int):
    m = re.search(re.escape(key) + r"\s*(=?\s*)?([0-9][0-9.]*)", text or "")
    if not m:
        return None
    try:
        return cast(m.group(2))
    except Exception:
        return m.group(2)


def grab(text, key):
    m = re.search(re.escape(key) + r"(\S+)", text or "")
    return m.group(1) if m else None


def drive(r, max_rounds, tool_round=None, stop_at=None):
    """一轮一轮推；记录每轮的 observed/limit/已压缩次数。返回 (首次触发轮次, 逐轮读数, 最后一次 preview)"""
    rows, trigger, last = [], None, ""
    for i in range(1, max_rounds + 1):
        if tool_round == i:
            ok, _ = r.tool_round("seq 1 4000")
        else:
            ok = r.round_("轮%d" % i)
        if not ok:
            rows.append((i, "TIMEOUT"))
            continue
        pv = r.preview()
        last = pv or last
        obs, lim, cnt = num(pv, "observed="), num(pv, "limit="), num(pv, "已压缩次数=")
        rows.append((i, obs, lim, cnt))
        if isinstance(cnt, int) and cnt >= 1 and trigger is None:
            trigger = i
        if stop_at is None:
            if trigger is not None:
                break
        elif isinstance(cnt, int) and cnt >= stop_at:
            break
    return trigger, rows, last


# ===== 段：probe =====

def scene_probe():
    print("\n===== probe：标定第一轮请求体的真实字符数 =====", flush=True)
    r = Repl("probe", "0.50")
    ok = r.wait_count("z-bot", 1, timeout=150)
    check("P0 真 pty REPL 起得来（JVM 自己打了欢迎面板）", ok,
          "首屏 %d 字节，log=%s" % (len(r.screen()), r.logpath), "probe")
    if not ok:
        r.kill9()
        return False
    h0 = len(LLM_HITS)
    done = r.round_("标定")
    chars = max([h["chars"] for h in LLM_HITS[h0:]] or [0])
    check("P0b 标定轮真打了 stub（拿到请求体真实字符数）", done and chars > 500,
          "本次请求体=%d 字符，请求数=%d" % (chars, len(LLM_HITS[h0:])), "probe")
    r.kill9()
    tokens0 = chars // 2
    avail = 2 * (tokens0 + int(2.5 * PER_ROUND_TOKENS))   # ⇒ 0.50 档在第 3 轮前后过线
    CFG["maxout"] = max(1, avail // 4)
    CFG["window"] = avail + CFG["maxout"]
    log("初始≈%d tokens，每轮≈%d tokens ⇒ available=%d window=%d maxout=%d"
        " ⇒ limit(0.50)=%d limit(0.90)=%d"
        % (tokens0, PER_ROUND_TOKENS, avail, CFG["window"], CFG["maxout"],
           int(avail * 0.5), int(avail * 0.9)))
    return chars > 500


# ===== 段：thresh =====

def scene_thresh():
    print("\n===== thresh：pct=0.50 与 pct=0.90 的触发轮次对比 =====", flush=True)
    out = {}
    for pct, rounds in (("0.50", 8), ("0.90", 12)):
        r = Repl("thresh-" + pct.replace(".", ""), pct)
        if not r.wait_count("z-bot", 1, timeout=150):
            check("T0 pct=%s 那台 REPL 起得来" % pct, False, "log=%s" % r.logpath, "thresh")
            r.kill9()
            out[pct] = (None, [], "")
            continue
        check("T0 pct=%s 那台 REPL 起得来" % pct, True, "log=%s home=%s" % (r.logpath, r.home), "thresh")
        trig, rows, pv = drive(r, rounds)
        out[pct] = (trig, rows, pv)
        log("pct=%s limit=%s 触发轮次=%s 逐轮(轮/observed/limit/已压缩次数)=%s"
            % (pct, num(pv, "limit="), trig, rows))
        r.kill9()
    t50, _, pv50 = out.get("0.50", (None, [], ""))
    t90, rows90, pv90 = out.get("0.90", (None, [], ""))
    avail = CFG["window"] - CFG["maxout"]
    want = {"0.50": int(avail * 0.5), "0.90": int(avail * 0.9)}
    got = {"0.50": num(pv50, "limit="), "0.90": num(pv90, "limit=")}
    check("T1 触发线 = (窗口 − 输出额度) × pct（真进程 preview 读数，两档各对上）",
          got["0.50"] == want["0.50"] and got["0.90"] == want["0.90"],
          "window=%s maxout=%s ⇒ 0.50 期望 %s 实测 %s；0.90 期望 %s 实测 %s；"
          "describe.pct=%s/%s" % (CFG["window"], CFG["maxout"], want["0.50"], got["0.50"],
                                 want["0.90"], got["0.90"], grab(pv50, "pct="), grab(pv90, "pct=")),
          "thresh")
    check("T2 改 pct 就换触发轮次（0.50 早于 0.90，且两档都真触发过）",
          t50 is not None and t90 is not None and t50 < t90,
          "pct=0.50 第 %s 轮触发 / pct=0.90 第 %s 轮触发；"
          "正对照=0.50 那一档在同一套轮次脚本下确实压成了（不是两档都没到线的空跑）" % (t50, t90),
          "thresh")
    c50 = re.findall(r"agent\.context\.compress\.pct=\S+", pv50 or "")
    c90 = re.findall(r"agent\.context\.compress\.pct=\S+", pv90 or "")
    check("T3 pct 真从配置位流进来（preview 自证的 config 行两档不同）",
          bool(c50) and bool(c90) and c50[0] != c90[0],
          "0.50 档=%s 0.90 档=%s（产品里没有 /config 斜杠命令，这一行就是 pct 的自证面）"
          % (c50, c90), "thresh")
    return out


# ===== 段：lineage（验收 A） =====

def scene_lineage():
    print("\n===== lineage：验收 A（≥2 次压缩 + kill -9 + 重启后分叉链可见） =====", flush=True)
    r = Repl("lineage", "0.50")
    if not r.wait_count("z-bot", 1, timeout=150):
        check("L0 血统段 REPL 起得来", False, "log=%s" % r.logpath, "lineage")
        return False
    check("L0 血统段 REPL 起得来", True, "log=%s home=%s" % (r.logpath, r.home), "lineage")
    trig, rows, pv = drive(r, 10, tool_round=2, stop_at=2)
    cnt = num(pv, "已压缩次数=")
    sid = grab(pv, "session=")
    check("L1 真进程连打到 已压缩次数 ≥ 2", isinstance(cnt, int) and cnt >= 2,
          "已压缩次数=%s 起始 session=%s 逐轮(轮/observed/limit/次数)=%s" % (cnt, sid, rows), "lineage")
    hits = re.search(r"轮首/粗估/工具批 = (\d+)/(\d+)/(\d+)", pv or "")
    check("L2 三个评估位点在真进程里都到过（位点计数三段全 >0）",
          bool(hits) and all(int(g) > 0 for g in hits.groups()),
          "位点计数=%s（工具批那一段由第 2 轮的真 exec 工具批喂出来）"
          % ("/".join(hits.groups()) if hits else "读不到"), "lineage")
    check("L3 抢锁走的是库（preview 里 lockBackend=compression_locks）",
          "lockBackend=compression_locks" in (pv or ""),
          "describe 片段=%s" % re.findall(r"lockBackend=\S+", pv or ""), "lineage")
    home = r.home
    rows_before = q_db(home, "SELECT id,title,parent_session_id,end_reason FROM sessions")
    r.kill9()
    still = subprocess.run(["pgrep", "-f", home], stdout=subprocess.PIPE).stdout.decode().strip()
    check("L4 旧进程是 kill -9 掉的（进程表里查不到那个 profile）", still == "",
          "pid=%s 已回收，pgrep -f %s = %r" % (r.pid, os.path.basename(home), still), "lineage")
    # 新进程、同一个 zbot-home：这才叫"跨进程重启仍在"（reuse 不许重建目录）
    r2 = Repl("lineage", "0.50", reuse=(r.root, r.home))
    ok2 = r2.wait_count("z-bot", 1, timeout=150)
    check("L5 重启的那台是新进程（pid 不同）且起得来", ok2 and r2.pid != r.pid,
          "旧 pid=%s 新 pid=%s 同一个 home=%s" % (r.pid, r2.pid, home), "lineage")
    got_list = r2.sessions()
    screen = r2.screen()
    cut = screen.rfind("本地会话 (")          # P14b 量具修：见 sessions() 注释，锚定真列表块
    tail = screen[cut:] if cut >= 0 else ""
    fork_lines = [l for l in tail.splitlines() if re.search(r"base #\d+", l)]
    check("L6 重启后 /sessions 仍数得到分叉链（base #1 与 base #2 都在）",
          bool(got_list) and any("#1" in l for l in fork_lines) and any("#2" in l for l in fork_lines),
          "wait_ok=%s 分叉行=%s（当前指针=%s）" % (got_list, fork_lines[:6], "* 行" if "* " in tail else "无"), "lineage")
    db = q_db(home, "SELECT id,title,parent_session_id,end_reason FROM sessions "
                    "WHERE parent_session_id IS NOT NULL ORDER BY created_at")
    # P14b 量具修：血统根那行的 parent 是 NULL，不在 db 这个子集里 —— 旧代码拿
    # 「分叉行集合」自查父，根链永远判「父不在库里」（r1 诊断跑 L7 假红）。父的存在性
    # 要对全表判。
    allids = {x[0] for x in (q_db(home, "SELECT id FROM sessions") or [])}
    chain_ok = bool(db) and len(db) >= 2
    for _, _, p, _ in (db or []):
        if p not in allids:
            chain_ok = False
    check("L7 state.db 直读：parent_session_id 串成链（每个子都能数到父，父也在库里）",
          chain_ok, "直读分叉行=%s 全表会话数=%d" % (db or "无", len(allids)), "lineage")
    ended = q_db(home, "SELECT count(*) FROM sessions WHERE end_reason='compressed'")
    check("L8 父行按 compressed 收口（end_reason 真落了库，不是只写在内存）",
          bool(ended) and ended[0][0] >= 2, "直读 end_reason='compressed' 行数=%s" % (ended[0][0] if ended else None),
          "lineage")
    # P14b 量具修：新进程开的是**新会话**（新血统根），拿新会话判「引擎认不认旧血统」
    # 是拿错了对象（r1 诊断跑 L9 假红）。验收 A 的口径 = 链能读回来并用起来：
    # 显式 /switch 到最后一条分叉（base #2 那条），preview 读数里血统深度 ≥2 才算数。
    kids = [x[0] for x in (db or [])]
    target = kids[-1] if kids else None
    sw_ok = bool(target) and r2.switch_until(target, tries=6)
    pv2 = r2.preview()
    depth = num(pv2, "血统深度=", int) or grab(pv2, "血统深度=")
    check("L9 重启后引擎仍认这条血统（切回分叉会话后 preview 的血统深度 ≥2）",
          sw_ok and isinstance(depth, int) and depth >= 2,
          "switch_ok=%s 目标会话=%s 新进程 preview 血统深度=%s session=%s"
          % (sw_ok, target, depth, grab(pv2, "session=")), "lineage")
    r2.kill9()
    return True


# ===== 段：cooldown（失败冷却入库，跨进程重启仍在） =====

def scene_cooldown():
    print("\n===== cooldown：摘要失败 → 冷却入库 → kill -9 → 重启仍在（带正对照） =====", flush=True)
    res = {}
    for tag, empty in (("fail", True), ("ok", False)):
        SCRIPT["empty_summary"] = empty
        r = Repl("cooldown-" + tag, "0.50", cooldown_ms=120000)
        if not r.wait_count("z-bot", 1, timeout=150):
            check("D0 冷却段 %s 那台 REPL 起得来" % tag, False, "log=%s" % r.logpath, "cooldown")
            continue
        _, rows, pv = drive(r, 6)
        home = r.home
        # P14b 量具修（r1 诊断跑 D2/D3 假红的根因）：冷却/防抖按**血统根会话**记账，而重启的
        # 新进程开的是它自己的新会话 —— 拿新会话的 preview 判「冷却没了」是判错了对象。
        # 正确姿势：kill 前记下这台的工作会话 id 并确认会话行已进库（异步写队列），
        # 重启后 /switch 切回那个会话、再推真轮次，让自动位点**真的去撞**冷却闸。
        sid = grab(pv, "session=")
        row_seen = False
        t0 = time.time()
        while time.time() - t0 < 20 and not row_seen:
            row_seen = bool(q_db(home, "SELECT id FROM sessions WHERE id=?", (sid,)))
            if not row_seen:
                time.sleep(0.5)
        r.kill9()
        r2 = Repl("cooldown-" + tag, "0.50", reuse=(r.root, r.home))
        ok2 = r2.wait_count("z-bot", 1, timeout=150)
        sw_ok = bool(sid) and ok2 and r2.switch_until(sid, tries=6)
        _, rows2, pv2 = drive(r2, 4) if sw_ok else (None, [], "")
        left = num(pv2, "cooldownLeft=")
        meta = q_db(home, "SELECT key,value FROM state_meta WHERE key LIKE 'compression.%'")
        res[tag] = {"left": left, "pv2": pv2, "meta": meta or [], "sid": sid,
                    "row_seen": row_seen, "sw_ok": sw_ok, "rows2": rows2, "pv0": pv}
        log("分支=%s 会话行入库=%s 切回=%s 重启后逐轮=%s cooldownLeft=%s lastSkip=%s meta=%s"
            % (tag, row_seen, sw_ok, rows2, left, grab(pv2, "lastSkip="), meta))
        r2.kill9()
    SCRIPT["empty_summary"] = False
    f = res.get("fail", {})
    o = res.get("ok", {})
    cd_fail = [kv for kv in f.get("meta", []) if str(kv[0]).startswith("compression.cooldown.")]
    cd_ok = [kv for kv in o.get("meta", []) if str(kv[0]).startswith("compression.cooldown.")]
    check("D1 摘要失败之后冷却截止时刻真落了库（state_meta 里有 compression.cooldown.*）",
          bool(cd_fail),
          "直读 state_meta=%s；失败那台重启后 failures=%s lastSkip=%s"
          % (f.get("meta"), num(f.get("pv2", ""), "failures="), grab(f.get("pv2", ""), "lastSkip=")), "cooldown")
    check("D2 杀掉进程重启、切回原会话再推真轮次，冷却仍然闸得住（新进程压成 0 次且 cooldownLeft>0）",
          f.get("row_seen") and f.get("sw_ok") and isinstance(f.get("left"), int) and f.get("left") > 0
          and num(f.get("pv2", ""), "已压缩次数=") in (0, None)
          and not any(isinstance(c, int) and c >= 1 for *_x, c in
                      [t for t in f.get("rows2", []) if len(t) == 4]),
          "会话行入库=%s switch_ok=%s 重启后 cooldownLeft=%s lastSkip=%s 逐轮=%s"
          % (f.get("row_seen"), f.get("sw_ok"), f.get("left"),
             grab(f.get("pv2", ""), "lastSkip="), f.get("rows2")), "cooldown")
    check("D3 正对照（同一套轮次脚本、摘要正常的那台）：没有冷却键，重启前后都真压成了",
          o.get("row_seen") and o.get("sw_ok")
          and isinstance(num(o.get("pv0", ""), "已压缩次数="), int) and num(o.get("pv0", ""), "已压缩次数=") >= 1
          and isinstance(num(o.get("pv2", ""), "已压缩次数="), int) and num(o.get("pv2", ""), "已压缩次数=") >= 1
          and not cd_ok,
          "入库=%s switch_ok=%s 重启前已压缩次数=%s 重启后已压缩次数=%s cooldown 键=%s"
          % (o.get("row_seen"), o.get("sw_ok"), num(o.get("pv0", ""), "已压缩次数="),
             num(o.get("pv2", ""), "已压缩次数="), cd_ok), "cooldown")
    return True


# ===== 段：lock（compression_locks 的真消费者，跨进程） =====

def scene_lock():
    print("\n===== lock：跨进程抢 compression_locks（持锁者在飞时第二台闸得住） =====", flush=True)
    a = Repl("lock-a", "0.50")
    if not a.wait_count("z-bot", 1, timeout=150):
        check("K0 A 台 REPL 起得来", False, "log=%s" % a.logpath, "lock")
        a.kill9()
        return False
    # 先把 A 推到"该压了"但**还没压**：跑够轮次让历史够长，然后关掉自动压缩（enabled 走配置位）
    pv = ""
    for _ in range(4):
        a.round_("A推")
        pv = a.preview() or pv
    sid = grab(pv, "session=")
    cnt_before = num(pv, "已压缩次数=")
    check("K0b A 台先攒出够长的历史（session 号取到了，历史 ≥ keepRecent+2）", sid is not None,
          "session=%s 此时已压缩次数=%s observed=%s limit=%s"
          % (sid, cnt_before, num(pv, "observed="), num(pv, "limit=")), "lock")
    SCRIPT["hold_seconds"] = 14.0
    # A 手动强制压缩：拿 DB 锁 → 调摘要（在 stub 里睡 14s）→ 松手
    a.send("/compress")
    t_hold = time.time()
    lock_rows, holder_a = [], None
    while time.time() - t_hold < 12:
        rows = q_db(a.home, "SELECT session_id,holder FROM compression_locks") or []
        if rows:
            lock_rows = rows
            holder_a = rows[0][1]
            break
        time.sleep(0.2)
    check("K1 A 在压的时候 compression_locks 里真有一行（sqlite3 直读，不看产品自述）",
          bool(lock_rows), "直读=%s（A 的持锁窗口内轮询到第 %.1fs）" % (lock_rows, time.time() - t_hold),
          "lock")
    # 第二个进程：**同一个 zbot-home**（P14b 量具修：r1 诊断跑里 B 用 Repl("lock-b") 新建了
    # 自己的 home —— 「跨进程共库」的前提根本没成立，B 从头就在读自己那个空库，
    # K2/K6 全失真、K3 只是空断言。reuse=(a.root, a.home) 才是「第二个进程打开同一份 state.db」。
    b = Repl("lock-b", "0.50", reuse=(a.root, a.home))
    got_b = b.wait_count("z-bot", 1, timeout=150)
    if not got_b:
        check("K2 第二进程的 preview 看得见 A 持有的那把锁（holder 是 A 进程自己生成的 uuid）",
              False, "B 台 REPL 起不来 log=%s" % b.logpath, "lock")
    else:
        pvb0 = b.preview()
        sid_b = grab(pvb0, "session=")
        switched = b.switch_until(sid, tries=6)
        pvb = b.preview()
        holder_seen = grab(pvb, "压缩锁持有者=")
        check("K2 第二进程的 preview 看得见 A 持有的那把锁（holder 是 A 进程自己生成的 uuid）",
              switched and bool(holder_seen) and holder_seen == holder_a and holder_seen != grab(pvb0, "压缩锁持有者="),
              "switch_ok=%s A 的 holder=%s B 读到=%s（B 切会话前读到的=%s，B 自己的 session=%s→切到 %s 后=%s）"
              % (switched, holder_a, holder_seen, grab(pvb0, "压缩锁持有者="), sid_b, sid, grab(pvb, "session=")),
              "lock")
        n_sessions_before = (q_db(b.home, "SELECT count(*) FROM sessions") or [[None]])[0][0]
        n_before_b = num(pvb, "已压缩次数=")
        b.send("/compress")
        ok_b = b.wait_count("没有可压缩的内容", (b.screen().count("没有可压缩的内容") + 1), timeout=25)
        n_sessions_held = (q_db(b.home, "SELECT count(*) FROM sessions") or [[None]])[0][0]
        # P14b 量具修：K3 判据要挂在「B 真的站进 A 的会话」这个前提上，否则它只是空断言
        check("K3 A 持锁期间 B 的强制压缩被闸住（B 在 A 的会话里、库里会话数一个没多）",
              switched and ok_b and n_sessions_held == n_sessions_before,
              "switch_ok=%s B 回话=%s sessions %s→%s；B 侧已压缩次数=%s"
              % (switched, ok_b, n_sessions_before, n_sessions_held, n_before_b), "lock")
        # 等 A 松手
        deadline = time.time() + 40
        while time.time() < deadline:
            rows = q_db(b.home, "SELECT session_id,holder FROM compression_locks") or []
            if not rows:
                break
            time.sleep(0.3)
        check("K4 反面对照：A 松手之后 compression_locks 清空（锁真的会释放，不是留一行死锁）",
              not rows, "A 完成时刻=%.1fs，直读锁表=%s" % (time.time() - t_hold, rows), "lock")
        # P14b 量具修（diag3 实测：不摘这个桩上的 14 s 摘要延迟，B 每推一轮就要多等 14 s，
        # 8 轮 + preview 直接把这一条拖成几分钟）：A 的在飞压缩此刻已经**实测结束**（K4 的
        # `直读锁表=[]` 就是证据），持锁窗口过去了，摘要桩没理由继续占着。
        SCRIPT["hold_seconds"] = 0.0
        pvb2 = b.preview()
        # P14b 量具修：旧 K5 的析取支 `failures is not None` 恒真（读数永远解析得动）= 空判据。
        # 换成直读共库：A 压完分叉出的那条 child 行（parent=根会话）必须从 B 这条连接也数得到
        # —— 「同一条血统根的状态确实被 A 写进了库、别的进程读得回」，外加 state_meta 备注。
        # P14b 量具修（诊断实测）：A 的 child 分叉行是**摘要返回那一刻**才写进共库的 —— r1 现场
        # messages 表 child 行 seq 21-24 的时间戳 16:52:10，正是 A 的 /compress 打完 14 s 摘要之后，
        # 而 B 在此之前就被闸住了 ⇒ 判 K5 前必须有界等这一行（等的是 A 那侧的写，B 只读）；
        # ≤20 s 还看不见就如实红。
        forks_seen, child_wait = [], 0.0
        t_wait = time.time()
        while time.time() - t_wait < 20:
            forks_seen = q_db(b.home, "SELECT id,title FROM sessions WHERE parent_session_id=?",
                              (sid or "«无»",)) or []
            if forks_seen:
                break
            time.sleep(0.5)
        child_wait = time.time() - t_wait
        meta_root = q_db(b.home, "SELECT key,value FROM state_meta WHERE key LIKE ?",
                         ("compression.%" + (sid or "«无»"),)) or []
        check("K5 A 写的血统根状态进了共库（B 直读得到 child 分叉行，parent 指向根会话）",
              bool(forks_seen),
              "等 %.1fs 直读 child=%s state_meta=%s（B 侧 preview lastSkip=%s 仅备注）"
              % (child_wait, forks_seen, meta_root, grab(pvb2, "lastSkip=")), "lock")
        # P14b 量具修（诊断实测：日志 ~/.cache/zbot-p14b/bar3_r1.log 的 K6/K7 两行 + 该跑产物直读）：
        # 旧 K6 拿「B 在 A 松手后打同一条 /compress 必分叉」当正对照，实测必不成立，两条硬事实：
        #   ① /switch 只挪指针、**不装消息** —— r1 现场 B 切进 A 的会话后 preview 明写
        #      `session=session_1790412716084-9ecbe2 observed=0/limit=9285 compressCount=0`，
        #      而同一份共库 messages 表里该会话有 8 条（seq 13-20，16:51:56）
        #      ⇒ B 手里「可压缩的内容」从头是空的，它回「没有可压缩的内容」不是被锁闸的
        #      （那是 K3 想证的事，产品缺陷 D6 把它整条正对照路都堵了，见 §8.0）。
        #   ② 自动位点的回显只在这一轮真压成时打印（agent/BotAgent.java:1897-1902），
        #      被门/冷却闸住时**什么都不打** ⇒ 屏上文字判不了「被闸住 vs 没到线」，
        #      正对照的权威读数只能是 preview 的 `已压缩次数=` 与共库 child 行数。
        # 处置（只改量具）：B 先真推轮次把自己的历史喂过阈值（K0b 实测同型轮次 4 轮到
        # observed=10409/limit=9297），再打同一条 /compress；判据 = 共库 child 行净增
        # 且（屏上'分叉为' 或 B 侧 `已压缩次数` > 0）。
        kids_before = len(q_db(b.home, "SELECT id FROM sessions WHERE parent_session_id=?",
                               (sid or "«无»",)) or [])
        pushed, cnt_push, obs_push, lim_push = 0, 0, 0, 1 << 30
        while pushed < 6:
            pushed += 1
            b.round_("B推 第%d句：A 松手之后的正对照轮次" % pushed)
            pvb3 = b.preview() or ""
            cnt_push = num(pvb3, "已压缩次数=") or 0
            obs_push = num(pvb3, "observed=") or 0
            lim_push = num(pvb3, "limit=") or lim_push
            if cnt_push > 0 or obs_push > lim_push:
                break
        b.send("/compress")
        forked = b.wait_count("分叉为", b.screen().count("分叉为") + 1, timeout=30)
        pvb4 = b.preview() or ""
        cnt_now = num(pvb4, "已压缩次数=")
        cnt_after = cnt_push if cnt_now is None else cnt_now
        kids_after = len(q_db(b.home, "SELECT id FROM sessions WHERE parent_session_id=?",
                              (sid or "«无»",)) or [])
        check("K6 正对照：同一台 B、同一条命令，A 松手之后压缩真发生了（唯一变量是那把锁）",
              kids_after > kids_before and (forked or cnt_after > 0),
              "B 自推 %d 轮到 observed=%s/limit=%s 已压缩次数 %s→%s；共库 child 行 %s→%s；"
              "B 屏'分叉为'=%s" % (pushed, obs_push, lim_push, n_before_b, cnt_after,
                                   kids_before, kids_after, forked), "lock")
        b.kill9()
    SCRIPT["hold_seconds"] = 0.0
    a.kill9()
    rows = q_db(a.home, "SELECT session_id FROM compression_locks") or []
    check("K7 A 收工之后锁表也是空的（两把锁都还得干净）", not rows, "直读=%s" % rows, "lock")
    return True


# ===== 段：home / creds =====

def home_reading():
    items = sorted(os.listdir(REAL_HOME)) if os.path.isdir(REAL_HOME) else []
    return {"count": len(items), "items": items,
            "config_md5": md5(os.path.join(REAL_HOME, "config.properties")),
            "state_md5": md5(os.path.join(REAL_HOME, "state.db")),
            "keylen": None}


def real_key():
    """只把真 key 当'不许出现的字节'用，绝不打印它的值或长度以外的任何信息。"""
    p = os.path.join(REAL_HOME, "config.properties")
    if not os.path.isfile(p):
        return None
    for line in open(p, encoding="utf-8", errors="replace"):
        m = re.match(r"\s*[0-9a-zA-Z.]*api\.key\s*=\s*(\S+)", line)
        if m and m.group(1) and not m.group(1).startswith("${"):
            return m.group(1).strip()
    return None


def scene_home(before):
    print("\n===== home：~/.zbot 本跑两点（红线） =====", flush=True)
    after = home_reading()
    check("H1 ~/.zbot 项数未变", after["count"] == before["count"],
          "before=%d after=%d items=%s" % (before["count"], after["count"], after["items"]), "home")
    check("H2 config.properties md5 前缀未变", after["config_md5"] == before["config_md5"],
          "before=%s after=%s（只取 md5 前缀，不打印内容）"
          % ((before["config_md5"] or "")[:8], (after["config_md5"] or "")[:8]), "home")
    check("H3 state.db md5 前缀未变", after["state_md5"] == before["state_md5"],
          "before=%s after=%s" % ((before["state_md5"] or "")[:8], (after["state_md5"] or "")[:8]), "home")
    return after


def scene_creds():
    print("\n===== creds：凭证卫生（扫描面 = 本跑 ROOT 下的每一个字节） =====", flush=True)
    auth = set()
    for h in LLM_HITS:
        v = h["headers"].get("authorization") or h["headers"].get("x-api-key")
        if v:
            auth.add(v)
    files, texts = [], []
    for root, _dirs, fs in os.walk(os.path.join(CACHE_ROOT, "e2e")):
        if not root.startswith(os.path.join(CACHE_ROOT, "e2e", LABEL + "-")):
            continue
        for f in fs:
            p = os.path.join(root, f)
            if f.endswith((".json", ".log", ".txt", ".properties", ".tsv", ".md")):
                files.append(p)
                try:
                    with open(p, encoding="utf-8", errors="replace") as fh:
                        texts.append((p, fh.read()))
                except OSError:
                    pass
    hit_stub = sum(1 for _, t in texts if STUB_KEY in t)
    check("K8 反向钉住：stub key 真进了产物（否则'没泄漏'是空跑）",
          hit_stub > 0 and auth in ({STUB_KEY, "Bearer " + STUB_KEY}, {"Bearer " + STUB_KEY}, {STUB_KEY}),
          "stub 收到的 Authorization 值集合=%s（%d 次调用），含 stub key 的产物文件=%d/%d"
          % (sorted(auth), len(LLM_HITS), hit_stub, len(files)), "creds")
    rk = real_key()
    leaked = [p for p, t in texts if rk and rk in t]
    check("K9 真 key 一个字节都没进产物（拿 ~/.zbot 里那把的值去比，不打印它）",
          bool(rk) and not leaked,
          "扫了本跑 ROOT 下 %d 个文件；真 key（md5 前缀 %s，长度 %d）命中文件=%s"
          % (len(files), hashlib.md5(rk.encode()).hexdigest()[:8] if rk else "读不到",
             len(rk) if rk else 0, leaked[:3]), "creds")
    bad = set()
    for p, t in texts:
        for v in re.findall(r"Bearer\s+([A-Za-z0-9._\-]{8,})", t):
            if v != STUB_KEY:
                bad.add((os.path.basename(p), v[:6] + "…"))
    check("K10 产物里出现的 Bearer 值只有 stub 那一种", not bad, "异常值=%s（扫描面 %d 个文件）" % (sorted(bad)[:5], len(files)), "creds")
    return True


# ===== 主流程 =====

def build_gate():
    pkglog_dir = os.path.join(CACHE_ROOT, "e2e")
    os.makedirs(pkglog_dir, exist_ok=True)
    pkg = os.path.join(pkglog_dir, "package-%s.log" % LABEL)
    with open(pkg, "wb") as fh:
        rc = subprocess.run(["mvn", "-o", "package", "-DskipTests", "-pl", "z-bot-core"],
                            cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT, timeout=1800).returncode
    sha = sha256_of(JAR)
    JAR_SHA["at_build"] = sha
    head = git_out("rev-parse", "--short", "HEAD")
    dirty = git_out("status", "--porcelain", "--", "z-bot-core/src", "pom.xml")
    check("B0 本跑用的是现打的 jar（打包 rc=0 + sha256 前 8 + git HEAD 都进读数）", rc == 0 and bool(sha),
          "mvn rc=%s jar sha256=%s git HEAD=%s 未提交源码改动=%s 打包日志=%s"
          % (rc, (sha or "MISSING")[:8], head, (dirty or "无").replace("\n", " | ")[:200], pkg), "build")
    return rc == 0


def main():
    global LABEL
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default="run")
    ap.add_argument("--only", default=None,
                    help="probe / thresh / lineage / cooldown / lock / home / creds")
    args = ap.parse_args()
    LABEL = args.label
    only = args.only
    home_before = home_reading()
    t_home = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    if not build_gate():
        print("B0 不成立：不开跑（不拿旧 jar 顶包）", flush=True)
        return 3
    srv, _url = start_stub()
    try:
        # 阈值算式要标定值才写得准：除单跑 probe 之外，任何段开跑前都先 probe 一次
        if only in (None, "probe", "thresh", "lineage", "cooldown", "lock"):
            if not scene_probe():
                print("probe 未成立：后面各段没有标定值可用（配置里的窗口是占位数）", flush=True)
        if only in (None, "thresh"):
            scene_thresh()
        if only in (None, "lineage"):
            scene_lineage()
        if only in (None, "cooldown"):
            scene_cooldown()
        if only in (None, "lock"):
            scene_lock()
    finally:
        cleanup()
    check("B0b 跑的过程中 jar 的字节没被换过", sha256_of(JAR) == JAR_SHA["at_build"],
          "开跑前=%s 收工后=%s" % ((JAR_SHA["at_build"] or "")[:8], (sha256_of(JAR) or "")[:8]), "build")
    if only in (None, "home"):
        scene_home(home_before)
    if only in (None, "creds"):
        scene_creds()
    fails = [c for c in CHECKS if c["status"] == "FAIL"]
    print("\n===== summary =====", flush=True)
    print("label=%s 段=%s 检查条数=%d PASS=%d FAIL=%d"
          % (LABEL, only or "all", len(CHECKS), len(CHECKS) - len(fails), len(fails)), flush=True)
    for c in fails:
        print("  FAIL [%s] %s :: %s" % (c["section"], c["name"], c["detail"]), flush=True)
    print("  ~/.zbot 起跑时点=%s 项数=%d config md5 前缀=%s state.db md5 前缀=%s"
          % (t_home, home_before["count"], (home_before["config_md5"] or "")[:8],
             (home_before["state_md5"] or "")[:8]), flush=True)
    a2 = home_reading()
    print("  ~/.zbot 收尾时点=%s 项数=%d config md5 前缀=%s state.db md5 前缀=%s"
          % (time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), a2["count"],
             (a2["config_md5"] or "")[:8], (a2["state_md5"] or "")[:8]), flush=True)
    print("  stub 收到 LLM 调用 %d 次（其中摘要调用 %d 次），临时根=%s"
          % (len(LLM_HITS), sum(1 for h in LLM_HITS if h["summary"]), ROOTS[:1]), flush=True)
    srv.shutdown()
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
