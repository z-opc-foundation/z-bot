#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P25b 杠③ —— 真 java 子进程 / 真 stdio 往返的 ACP E2E（自带全部判词，不 import 别的战役脚本）。

一次调用 = 一整跑：
  1. `mvn -o -pl z-bot-core compile` + 依赖 classpath（离线；缺件 ⇒ NO-RUN，绝不写成 skip/passed）
  2. 起**自写假 LLM**（`socket` + `bind(0)` + `Connection: close`，只绑 127.0.0.1；
     不用 `com.sun.net.httpserver`，也不用 python 的 http.server —— 它 keep-alive 会挂收尾）
  3. 写一个**临时 profile**到 `~/.cache/zbot-p25-lead/e2e/<tag>/cfg`，key 用
     `stub-key-not-real`（真 `~/.zbot` 一个字节都不碰，跑前跑后各量一次杠④三时点并原样打印）
  4. 起**真 java 子进程** `ZBot acp --config-dir CFG`：stdin 发帧、stdout 读帧，
     逐条断言 id/method 对得上（initialize / session/new / session/list / 未知方法 -32601 /
     session/prompt 真走假 LLM）
  5. 关 stdin ⇒ 等真退出码 ⇒ `ps` 复扫证明子进程被回收（不是只看日志尾巴）
  6. 再起第二个真进程跑 `acp --check`（装配自拍路径，同一套临时 profile）

退出码：0=判词全过 / 1=有 CHECK 失败 / 2=环境或构建缺失（NO-RUN）/ 3=超时兜底
"""

import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time

REPO = "/private/tmp/zbot-wt-p25"
LEAD = os.path.expanduser("~/.cache/zbot-p25-lead")
E2E = os.path.join(LEAD, "e2e")
RUN_TAG = sys.argv[1] if len(sys.argv) > 1 else time.strftime("run%H%M%S")
OUT = os.path.join(E2E, RUN_TAG)
CFG = os.path.join(OUT, "cfg")
MVN_TIMEOUT = 900
LAUNCH_TIMEOUT = 120
FRAME_TIMEOUT = 45

CHECKS = []


def chk(name, ok, detail=""):
    CHECKS.append((name, bool(ok), detail))
    print("CHECK|%-44s %s %s" % (name, "PASS" if ok else "FAIL", detail), flush=True)
    return ok


def no_run(why):
    print("NO-RUN %s" % why, flush=True)
    print("E2E|run=%s result=NO-RUN reason=%s" % (RUN_TAG, why))
    sys.exit(2)


def sh(args, timeout=180, cwd=REPO):
    p = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         preexec_fn=os.setsid)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, (out or b"").decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(p.pid), signal_all_kill())
        except OSError:
            pass
        return 124, "TIMEOUT"


def signal_all_kill():
    import signal
    return signal.SIGKILL


# ---------------- 杠④：只量不变量，永不开 key 的值 ----------------

def bar4(tag):
    home = os.path.expanduser("~/.zbot")
    rc, n = sh(["zsh", "-c", "ls -A '%s' | wc -l | tr -d ' '" % home], timeout=30)
    rc2, cfg = sh(["zsh", "-c", "md5 -q '%s/config.properties' | cut -c1-8" % home], timeout=30)
    rc3, db = sh(["zsh", "-c", "md5 -q '%s/state.db' | cut -c1-8" % home], timeout=30)
    # 只量长度，绝不打印值
    rc4, klen = sh(["zsh", "-c",
                    "awk -F= '/^minimax\\.api\\.key=/{print length($2)}' '%s/config.properties'" % home],
                   timeout=30)
    line = ("BAR4|tag=%s dir_count=%s cfg_md5_8=%s db_md5_8=%s key_len_only=%s"
            % (tag, n.strip(), cfg.strip(), db.strip(), klen.strip()))
    print(line, flush=True)
    return line


# ---------------- 自写假 LLM（OpenAI 兼容，一次性、bind(0)、Connection: close） ----------------

class FakeLlm(threading.Thread):
    def __init__(self):
        threading.Thread.__init__(self)
        self.daemon = True
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(16)
        self.port = self.sock.getsockname()[1]
        self.hits = []
        self.stop = False

    def run(self):
        while not self.stop:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        try:
            conn.settimeout(10)
            buf = b""
            while b"\r\n\r\n" not in buf:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                buf += chunk
            head, _, rest = buf.partition(b"\r\n\r\n")
            m = re.search(rb"Content-Length: (\d+)", head, re.I)
            want = int(m.group(1)) if m else 0
            while len(rest) < want:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                rest += chunk
            streamed = b'"stream":true' in rest or b'"stream": true' in rest
            self.hits.append({"bytes": len(rest), "stream": streamed})
            body_obj = {
                "id": "cc-fake-1", "object": "chat.completion", "created": 1,
                "model": "MiniMax-Text-01",
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": "ACP-E2E-OK"}}],
                "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5},
            }
            if streamed:
                first = {"id": "cc-fake-1", "object": "chat.completion.chunk", "created": 1,
                         "model": "MiniMax-Text-01",
                         "choices": [{"index": 0, "finish_reason": None,
                                      "delta": {"role": "assistant", "content": "ACP-E2E-OK"}}]}
                last = {"id": "cc-fake-1", "object": "chat.completion.chunk", "created": 1,
                        "model": "MiniMax-Text-01",
                        "choices": [{"index": 0, "finish_reason": "stop", "delta": {}}]}
                sse = ("data: %s\n\ndata: %s\n\ndata: [DONE]\n\n"
                       % (json.dumps(first), json.dumps(last))).encode("utf-8")
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                             b"Content-Length: %d\r\nConnection: close\r\n\r\n" % len(sse) + sse)
            else:
                body = json.dumps(body_obj).encode("utf-8")
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                             b"Content-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
        except Exception as e:
            print("FAKELLM|err=%r" % e, flush=True)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def shutdown(self):
        self.stop = True
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------- 帧读线程 ----------------

class Frames(threading.Thread):
    def __init__(self, stream, sink):
        threading.Thread.__init__(self)
        self.daemon = True
        self.stream = stream
        self.q = queue.Queue()
        self.sink = sink  # 原始行也存一份，便于排错

    def run(self):
        for line in self.stream:
            if not line:
                break
            s = line.decode("utf-8", "replace").rstrip("\n")
            self.sink.append(s)
            if s.strip().startswith("{"):
                self.q.put(s)

    def await_frame(self, pred, timeout=FRAME_TIMEOUT):
        deadline = time.time() + timeout
        seen = []
        while time.time() < deadline:
            try:
                line = self.q.get(timeout=0.5)
            except queue.Empty:
                continue
            seen.append(line)
            try:
                node = json.loads(line)
            except ValueError:
                continue
            if pred(node):
                return node, seen
        return None, seen


def frame_for_id(want_id):
    return lambda node: node.get("id") == want_id and ("result" in node or "error" in node)


def main():
    if not os.path.isdir(REPO):
        no_run("repo missing: %s" % REPO)
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(CFG)
    print("E2E|run=%s out=%s" % (RUN_TAG, OUT), flush=True)
    bar4("t0_before_build")

    rc, out = sh(["mvn", "-o", "-pl", "z-bot-core", "compile"], timeout=MVN_TIMEOUT)
    classes = os.path.join(REPO, "z-bot-core", "target", "classes",
                           "com", "zifang", "z", "bot", "acp", "AcpAgentServer.class")
    print("BUILD|compile_rc=%d acp_class=%s exists=%s" % (rc, classes, os.path.isfile(classes)),
          flush=True)
    if not os.path.isfile(classes):
        no_run("AcpAgentServer.class 不存在（compile_rc=%d）" % rc)

    cp_file = os.path.join(OUT, "cp.txt")
    rc, out = sh(["mvn", "-o", "-q", "-pl", "z-bot-core", "dependency:build-classpath",
                  "-Dmdep.outputFile=" + cp_file], timeout=MVN_TIMEOUT)
    size = os.path.getsize(cp_file) if os.path.isfile(cp_file) else -1
    print("BUILD|classpath_file=%s size=%d rc=%d" % (cp_file, size, rc), flush=True)
    if size <= 0:
        no_run("dependency:build-classpath 产出 0 字节/缺文件 —— 没量到依赖，不算通过")
    with open(cp_file) as f:
        dep_cp = f.read().strip()
    cp = os.path.join(REPO, "z-bot-core", "target", "classes") + os.pathsep + dep_cp

    bar4("t1_after_build")

    fake = FakeLlm()
    fake.start()
    print("FAKELLM|port=%d" % fake.port, flush=True)
    with open(os.path.join(CFG, "config.properties"), "w") as f:
        f.write("provider=minimax\n"
                "minimax.type=openai\n"
                "minimax.api.key=stub-key-not-real\n"
                "minimax.base.url=http://127.0.0.1:%d/v1\n"
                "minimax.model=MiniMax-Text-01\n" % fake.port)

    java = shutil.which("java") or "java"
    raw_stderr = []
    raw_stdout = []
    err_path = os.path.join(OUT, "acp_stderr.log")
    err_file = open(err_path, "w")
    proc = subprocess.Popen([java, "-cp", cp, "com.zifang.z.bot.ZBot", "acp",
                             "--config-dir", CFG],
                            cwd=REPO, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=err_file)
    frames = Frames(proc.stdout, raw_stdout)
    frames.start()
    pid = proc.pid
    print("PROC|acp_pid=%d" % pid, flush=True)

    def send(obj):
        proc.stdin.write((json.dumps(obj) + "\n").encode("utf-8"))
        proc.stdin.flush()

    try:
        # ---- 1. initialize：id 必须原样回，protocolVersion 必须是 1 ----
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
              "params": {"protocolVersion": 1, "clientInfo": {"name": "p25b-e2e"}}})
        node, seen = frames.await_frame(frame_for_id(1))
        chk("initialize_answers_id1", node is not None, "frames=%d" % len(seen))
        got_version = bool(node and node.get("result", {}).get("protocolVersion") == 1)
        chk("initialize_protocolVersion_is_1", got_version,
            "" if got_version else str(node))
        agent_name = node.get("result", {}).get("agentInfo", {}).get("name", "") if node else ""
        chk("initialize_names_the_agent", "z-bot" in str(node), "agentInfo.name=%s" % agent_name)

        # ---- 2. session/new ----
        send({"jsonrpc": "2.0", "id": 2, "method": "session/new", "params": {"cwd": CFG}})
        node, seen = frames.await_frame(frame_for_id(2))
        session_id = (node or {}).get("result", {}).get("sessionId", "")
        chk("session_new_answers_id2_with_sessionId", bool(session_id),
            "sessionId=%s" % session_id)

        # ---- 3. session/list 要看得见刚建的那条 ----
        send({"jsonrpc": "2.0", "id": 3, "method": "session/list", "params": {}})
        node, seen = frames.await_frame(frame_for_id(3))
        listed = json.dumps(node) if node else ""
        chk("session_list_sees_the_live_session", session_id in listed,
            "payload=%s" % listed[:160])

        # ---- 4. 表外方法必须大声 -32601（并且带上已知方法表） ----
        send({"jsonrpc": "2.0", "id": 4, "method": "session/teleport", "params": {}})
        node, seen = frames.await_frame(frame_for_id(4))
        err = (node or {}).get("error", {})
        chk("unknown_method_is_minus_32601", err.get("code") == -32601, str(err)[:160])
        known = json.dumps(err.get("data", {})) if err else ""
        chk("unknown_method_carries_known_method_table", "session/prompt" in known,
            "data=%s" % known[:160])

        # ---- 5. session/prompt：真走假 LLM，id/method 都对得上 ----
        update_seen = []

        def is_prompt_reply(n):
            if n.get("id") == 5 and ("result" in n or "error" in n):
                return True
            if n.get("method") == "session/update":
                update_seen.append(n)
            return False

        send({"jsonrpc": "2.0", "id": 5, "method": "session/prompt",
              "params": {"sessionId": session_id,
                         "prompt": [{"type": "text", "text": "说一句 ACP-E2E-OK"}]}})
        node, seen = frames.await_frame(is_prompt_reply, timeout=70)
        chk("prompt_answers_id5", node is not None, "updates_seen=%d" % len(update_seen))
        stop = (node or {}).get("result", {}).get("stopReason", "")
        chk("prompt_stopReason_is_end_turn", stop == "end_turn", "stopReason=%s err=%s"
            % (stop, json.dumps((node or {}).get("error", {}))[:200]))
        chunks = [u for u in update_seen
                  if u.get("params", {}).get("update", {}).get("sessionUpdate") == "agent_message_chunk"]
        text = "".join(c.get("params", {}).get("update", {}).get("content", {}).get("text", "")
                       for c in chunks)
        chk("prompt_streamed_agent_message_chunk", "ACP-E2E-OK" in text,
            "chunks=%d text=%s" % (len(chunks), text[:120]))
        chk("fake_llm_was_actually_called", len(fake.hits) >= 1,
            "hits=%s" % fake.hits[:3])
        # stdout 只能放协议：每一行都得是"一条完整 JSON 帧且带 jsonrpc"，
        # 有半帧/混进日志就解析不回来（IDE 侧就是这么读的）。解析不动算 FAIL，不让量具自己崩。
        unparsable = []
        for s in raw_stdout:
            if not s.strip():
                continue
            try:
                if not str(json.loads(s).get("jsonrpc", "")).startswith("2."):
                    unparsable.append(s[:60])
            except ValueError:
                unparsable.append(s[:60])
        chk("every_stdout_line_is_a_complete_jsonrpc_frame", not unparsable and len(raw_stdout) >= 6,
            "frames=%d bad=%d first_bad=%s" % (len(raw_stdout), len(unparsable),
                                               unparsable[:1]))

        # ---- 6. 关 stdin ⇒ 真退出；ps 复扫证明回收 ----
        proc.stdin.close()
        try:
            rc_exit = proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            rc_exit = "STILL_ALIVE"
            proc.kill()
            proc.wait(timeout=10)
        chk("acp_process_exits_on_stdin_eof", rc_exit == 0, "exit_code=%s" % rc_exit)
        rc_ps, ps_out = sh(["zsh", "-c", "ps -p %d -o pid=,stat= || true" % pid], timeout=30)
        chk("acp_process_reaped_per_ps", ps_out.strip() == "", "ps_says=%r" % ps_out.strip())
        err_file.close()
        with open(err_path) as f:
            err_txt = f.read()
        chk("stdout_held_only_protocol_logs_went_stderr",
            "[acp]" in err_txt and all(not s.startswith("[acp]") for s in raw_stdout),
            "stderr_has_banner=%s stdout_clean=%s" % ("[acp]" in err_txt,
                                                      all(not s.startswith("[acp]") for s in raw_stdout)))
    finally:
        fake.shutdown()
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)

    # ---- 7. 第二个真进程：acp --check（装配自拍，不接 stdin） ----
    check_cp = [java, "-cp", cp, "com.zifang.z.bot.ZBot", "acp", "--check", "--config-dir", CFG]
    rc_chk, out_chk = sh(check_cp, timeout=LAUNCH_TIMEOUT)
    with open(os.path.join(OUT, "acp_check.log"), "w") as f:
        f.write(out_chk)
    print("CHECKMODE|rc=%d tail=%s" % (rc_chk, out_chk.strip().splitlines()[-1][:150]
                                        if out_chk.strip() else ""), flush=True)
    chk("acp_check_selfcheck_exit_zero", rc_chk == 0, "rc=%s" % rc_chk)
    chk("acp_check_reports_no_missing_frame", "没有任何回帧" not in out_chk,
        "seen_missing=%s" % ("没有任何回帧" in out_chk))
    chk("acp_check_verifies_the_32601_step", "装配自检通过" in out_chk,
        "tail=%s" % out_chk.strip().splitlines()[-1][:120] if out_chk.strip() else "empty")

    # ---- 8. 临时 profile 确实生效：state.db 落在临时目录、真 ~/.zbot 没动 ----
    temp_state = os.path.isfile(os.path.join(CFG, "state.db"))
    chk("temp_profile_used_for_state_db", temp_state,
        "files_in_cfg=%s" % sorted(os.listdir(CFG)))

    bar4("t2_after_e2e")
    fake_hits = len(fake.hits)
    fails = [c for c in CHECKS if not c[1]]
    print("E2E|run=%s checks=%d pass=%d fail=%d llm_hits=%d result=%s"
          % (RUN_TAG, len(CHECKS), len(CHECKS) - len(fails), len(fails), fake_hits,
             "OK" if not fails else "HAS_FAILURE"), flush=True)
    for name, ok, detail in fails:
        print("FAILED_CHECK|%s|%s" % (name, detail), flush=True)
    sys.exit(0 if not fails else 1)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as exc:
        print("E2E-EXC %r" % (exc,), flush=True)
        import traceback
        traceback.print_exc()
        sys.exit(3)
