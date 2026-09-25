#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P11 验收③ 第二轮：把第一轮量错的三处补正，再加两条真进程证据。

E1 顺序正确的 ask→approve→execute（FS 快照必须落在 /confirm 之前）
E2 新待批取代旧待批：/confirm 只跑当前活着的那条(./second)，被取代的 ./first 永不补跑
E3 always 档经 POST /bot/confirm 落盘 → 杀进程 → 重启后同条命令免问（真持久化闭环）
"""
import fcntl
import json
import os
import pty
import re
import select
import shutil
import signal
import struct
import subprocess
import sys
import termios
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

# 上溯三级 = 仓根（_doc/acceptance/p11 → z-bot）；复算命令不依赖任何人的绝对路径
ZBOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir, os.pardir))
JAR = os.path.join(ZBOT, "z-bot-core/target/z-bot-core.jar")
ROOT = "/tmp/zbot-e2e-p11b"
CFG = os.path.join(ROOT, "cfg")
WS = os.path.join(ROOT, "workspace")
LLM_PORT = 18097
BOT_PORT = 18098

SCRIPT = []
REQUESTS = []


def tc(name, args, call_id="call_1"):
    return {"choices": [{"index": 0, "finish_reason": "tool_calls",
                         "message": {"role": "assistant", "content": None,
                                     "tool_calls": [{"id": call_id, "type": "function",
                                                     "function": {"name": name,
                                                                  "arguments": json.dumps(args)}}]}}],
            "usage": {"prompt_tokens": 20, "completion_tokens": 8, "total_tokens": 28}}


def say(text):
    return {"choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": text}}],
            "usage": {"prompt_tokens": 20, "completion_tokens": 4, "total_tokens": 24}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        REQUESTS.append(self.rfile.read(n).decode("utf-8", "replace"))
        body = SCRIPT.pop(0) if SCRIPT else say("stub: 无脚本应答")
        payload = json.dumps(body).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.send_response(404)
        self.end_headers()


def clean_cfg(extra=""):
    shutil.rmtree(ROOT, ignore_errors=True)
    os.makedirs(CFG)
    os.makedirs(WS)
    with open(os.path.join(CFG, "config.properties"), "w", encoding="utf-8") as f:
        f.write("agent.exec.confirm=dangerous\n"
                "agent.exec.confirm.whitelist=git status\n" + extra)


def mkdir(name):
    d = os.path.join(WS, name)
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "file.txt"), "w").write("x")
    return d


ENV = {"TERM": "xterm-256color",
       "Z_BOT_API_KEY": "stub-key-not-real",
       "Z_BOT_BASE_URL": "http://127.0.0.1:%d/v1" % LLM_PORT,
       "Z_BOT_MODEL": "stub-model"}


def repl(steps, wait_start=12.0):
    """steps: [("send", line, wait) | ("check", fn)] —— 检查必须落在它该发生的时间点上。"""
    cmd = "java -jar %s repl --config-dir %s --sandbox %s ; echo CHILD_EXIT=$?" % (JAR, CFG, WS)
    env = dict(os.environ)
    env.update(ENV)
    pid, fd = pty.fork()
    if pid == 0:
        os.execvpe("/bin/sh", ["/bin/sh", "-c", cmd], env)
        os._exit(127)
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 30, 140, 0, 0))
    buf = []

    def drain(sec):
        end = time.time() + sec
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], 0.2)
            if not r:
                continue
            try:
                data = os.read(fd, 65536)
            except OSError:
                return
            if not data:
                return
            buf.append(data)

    def text():
        raw = b"".join(buf).decode("utf-8", "replace")
        return re.sub(r"\x1b\[[0-9;?]*[a-zA-Z]|\x1b[()][A-Z0-9]|[\r\x00-\x08\x0b-\x1f]", "", raw)

    drain(wait_start)
    report = []
    for step in steps:
        if step[0] == "send":
            try:
                os.write(fd, (step[1] + "\r").encode("utf-8"))
            except OSError:
                break
            drain(step[2])
        else:
            report.append(step[1](text()))
    drain(1.0)
    try:
        os.close(fd)
    except OSError:
        pass
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass
    return text(), report


RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print("%s %s%s" % ("PASS  " if ok else "FAIL  ", name, "" if ok else "   << " + str(detail)[:500]))


def main():
    srv = HTTPServer(("127.0.0.1", LLM_PORT), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    global SCRIPT

    # ---------- E1 ask → 快照 → /confirm → executed ----------
    clean_cfg()
    d = mkdir("gone")
    SCRIPT = [tc("exec", {"command": "rm -rf ./gone"}), say("收尾")]
    text, snaps = repl([
        ("send", "rm the gone dir", 5.0),
        ("check", lambda t: ("asked", "高危命令需要确认" in t)),
        ("check", lambda t: ("queue_visible", "exec" in t and ("待确认" in t or "待审批" in t or "需要确认" in t))),
        ("check", lambda t: ("not_yet_run", os.path.isdir(d))),
        ("send", "/confirm", 6.0),
        ("check", lambda t: ("ran_after_confirm", not os.path.isdir(d))),
        ("check", lambda t: ("approved_marker", "已确认" in t)),
        ("send", "/exit", 2.0),
    ])
    s = dict(snaps)
    check("E1 危险命令回抛待批", s["asked"], text[-500:])
    check("E1 待批提示里能看到命令", s["queue_visible"], text[-500:])
    check("E1 /confirm 前未执行", s["not_yet_run"], "目录已不存在=闸门旁路")
    check("E1 /confirm 后真执行", s["ran_after_confirm"], "目录还在=放行没落地")
    check("E1 放行回执可见", s["approved_marker"], text[-400:])

    # ---------- E2 新待批取代旧待批（活的那条才被执行）----------
    clean_cfg()
    first = mkdir("first")
    second = mkdir("second")
    SCRIPT = [tc("exec", {"command": "rm -rf ./first"}, "c1"),
              tc("exec", {"command": "rm -rf ./second"}, "c2"),
              say("收尾1"), say("收尾2")]
    text, snaps = repl([
        ("send", "drop first", 5.0),
        ("send", "drop second", 5.0),
        ("check", lambda t: ("both_asked", "rm -rf ./first" in t and "rm -rf ./second" in t)),
        ("check", lambda t: ("both_queued", os.path.isdir(first) and os.path.isdir(second))),
        ("send", "/confirm", 7.0),
        ("check", lambda t: ("live_ask_ran", not os.path.isdir(second))),
        ("check", lambda t: ("abandoned_never_runs", os.path.isdir(first))),
        ("send", "/exit", 2.0),
    ])
    s = dict(snaps)
    check("E2 两条命令都进了待批提示", s["both_asked"], text[-600:])
    check("E2 两条待批都没偷跑", s["both_queued"], "FS 已变=闸门旁路")
    check("E2 /confirm 执行当前活待批(./second)", s["live_ask_ran"], text[-500:])
    check("E2 被取代的旧待批(./first)永不补跑", s["abandoned_never_runs"],
          "旧账被执行=账实分离")

    # ---------- E3 always 落盘 → 重启免问 ----------
    clean_cfg()
    d3 = mkdir("always-gone")
    SCRIPT = [tc("exec", {"command": "rm -rf ./always-gone"}), say("stub 收尾")]
    env = dict(os.environ)
    env.update(ENV)
    serve = subprocess.Popen(["java", "-jar", JAR, "serve", "--port", str(BOT_PORT),
                              "--config-dir", CFG, "--sandbox", WS],
                             env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        time.sleep(10.0)

        def post(path, payload, timeout=40):
            req = urllib.request.Request("http://127.0.0.1:%d%s" % (BOT_PORT, path),
                                         data=json.dumps(payload).encode("utf-8"),
                                         headers={"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    return r.status, r.read().decode("utf-8", "replace")
            except Exception as exc:
                body = getattr(exc, "read", lambda: b"")()
                return getattr(exc, "code", 0), (body.decode("utf-8", "replace") if body else str(exc))

        st1, chat1 = post("/bot/chat", {"message": "rm the always-gone dir"})
        check("E3 HTTP chat 回抛待批", "高危命令需要确认" in chat1, "status=%s body=%s" % (st1, chat1[:400]))
        st2, conf = post("/bot/confirm", {"toolName": "exec",
                                          "argsJson": json.dumps({"command": "rm -rf ./always-gone",
                                                                   "__approval_resolution__": "always"})})
        check("E3 always 放行后真执行", not os.path.isdir(d3), "status=%s body=%s" % (st2, conf[:300]))
        cfg_text = open(os.path.join(CFG, "config.properties"), encoding="utf-8").read()
        check("E3 always 名单落盘", "agent.exec.approval.always=rm -rf ./always-gone" in cfg_text,
              cfg_text[:400])
        check("E3 落盘没写到 ~/.zbot", "always-gone" not in open(
            os.path.expanduser("~/.zbot/config.properties"), encoding="utf-8", errors="replace").read(), "")
    finally:
        serve.send_signal(signal.SIGTERM)
        try:
            serve.wait(10)
        except Exception:
            serve.kill()

    # 重启：同条命令必须免问（真持久化生效）
    SCRIPT = [tc("exec", {"command": "rm -rf ./always-gone"}), say("stub 收尾")]
    os.makedirs(d3, exist_ok=True)
    open(os.path.join(d3, "file.txt"), "w").write("x")
    text3, snaps3 = repl([
        ("send", "again after restart", 6.0),
        ("check", lambda t: ("no_ask", "高危命令需要确认" not in t)),
        ("check", lambda t: ("ran_immediately", not os.path.isdir(d3))),
        ("send", "/exit", 2.0),
    ])
    s3 = dict(snaps3)
    check("E3 重启后 always 免问", s3["no_ask"], text3[-500:])
    check("E3 重启后 always 真执行", s3["ran_immediately"], text3[-300:])

    n_real = sum(1 for r in REQUESTS if "stub-key-not-real" not in r)
    print("\nstub 收到 LLM 请求 %d 次；~/.zbot 项数=%d" % (
        len(REQUESTS), len(os.listdir(os.path.expanduser("~/.zbot")))))
    print("== E2E round2 %d/%d 通过 ==" % (sum(1 for _, o in RESULTS if o), len(RESULTS)))
    srv.shutdown()
    return 0 if all(o for _, o in RESULTS) else 1


if __name__ == "__main__":
    sys.exit(main())
