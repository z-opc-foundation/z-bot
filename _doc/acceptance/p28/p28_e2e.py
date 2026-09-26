#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P28c 杠③ —— 真 JVM 子进程 / 真 HTTP 客户端 / 真 PTY 与控制台字节面的 E2E。

一次调用 = 一整跑（自带全部判词，不 import 别的战役脚本，不读 ~/.cache 里的私货）：

  1. `mvn -o -pl z-bot-core test-compile` + 依赖 classpath（离线；缺件 ⇒ NO-RUN，绝不写 skip/passed）
  2. 起**自写假 LLM**（`socket` + `bind(0)` + `Connection: close`，只绑 127.0.0.1；
     不用 `com.sun.net.httpserver`，也不用 python 的 http.server —— 它 keep-alive 会挂收尾）
  3. 写一个**临时 profile** 到 `~/.cache/zbot-p28-lead/e2e_p28c/<tag>/cfg`，key 用
     `stub-key-not-real`。真 `~/.zbot` 一个字节都不写，杠④ 在三个时点各量一次并原样打印
  4. A 组：**真 PTY + 真管道**各起一个探针 JVM，量 `runBounded` 是否真把 fd 0 继承给 `stty`
  5. B 组：**真 `z-bot serve` 子进程** + 自写裸 socket HTTP 客户端，把 `/bot/chat/stream` 的
     **线上原始字节**（含 chunked 框架）落盘，再逐帧判 SSE 语法与换行转义
  6. C 组：命令面三张表（`SlashRegistry` / `LOCAL_COMMANDS` / web `index.html`）与
     `ROUTES.tsv`、`WIRING.md` 里写死的条数与成员，**按 WIRING.md 自述的口径重算一遍并比对**
  7. D 组：杠④ 三时点 + 卫生（假 LLM 实际收到的 Authorization 只能是 stub）

每条判据都写明了"坏实现长什么样"；写不出坏实现的判据不放进来的纪律见 EVIDENCE.md `## p28c 杠③`。

退出码：0=判词全过 / 1=有 CHECK 失败 / 2=环境或构建缺失（NO-RUN）/ 3=量具自己抛异常
"""

import atexit
import hashlib
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

HERE = os.path.dirname(os.path.abspath(__file__))          # <repo>/_doc/acceptance/p28
REPO = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))   # 仓库根
LEAD = os.path.expanduser("~/.cache/zbot-p28-lead")
RUN_TAG = re.sub(r"[^A-Za-z0-9_.-]", "_", sys.argv[1] if len(sys.argv) > 1
                 else time.strftime("run%H%M%S"))
OUT = os.path.join(LEAD, "e2e_p28c", RUN_TAG)
CFG = os.path.join(OUT, "cfg")
CLASSES = os.path.join(REPO, "z-bot-core", "target", "classes")
TESTCLASSES = os.path.join(REPO, "z-bot-core", "target", "test-classes")
PROBE = "com.zifang.z.bot.ui.RawTerminalVerdictProbe"
STUB_KEY = "stub-key-not-real"
ZBOT = "com.zifang.z.bot.ZBot"
MVN_TIMEOUT = 900
LLM_SLEEPS_MS = 400          # 假 LLM 故意慢，用来量"SSE 头不等答复就先发"
BAR4_HOME = os.path.expanduser("~/.zbot")
# 杠④ 的三格不变量（战役协议，写死在这里就是为了让它红）
BAR4_ENTRIES = 8
BAR4_CFG = "2dadaed0"
BAR4_DB = "690ddbc0"
# WIRING.md §1/§3 与 ROUTES.tsv 声称的数字：尺的重算必须与文档一致，漂了就红
WIRING_BUCKETS = {
    "服务端注册表（唯一单源）": 20,
    "终端私有命令": 9,
    "3.1 只在服务端注册表": 17,
    "3.2 只在 TUI 私有": 9,
    "3.3 只在 web 有分支": 0,
    "3.4 web 广告了但没有分支接住": 1,
    "3.5 web 有分支但没广告": 1,
    "3.6 两边都有、实现不同": 6,
    "合计点名条数": 34,
}
ROUTES_PATHS = 22
ROUTES_ROWS = 25

# 一帧的语法：`event: <name>\ndata: <单行正文>`，帧与帧之间由 \n\n 分隔（分隔符已被 split 吃掉，
# 所以帧内没有收尾 \n —— 原先内联写的 `\n$` 就是这个意思上的错，三帧全被判"语法坏"）。
# 用 \Z 而非 $：$ 会放过"帧尾多一个 \n"的坏形状。提到模块级是为了能被离线探针喂真字节自证。
FRAME_RE = re.compile(rb"^event: ([a-z_]+)\ndata: ([^\n]*)\Z")

CHECKS = []


def chk(name, ok, bad_impl="", detail=""):
    CHECKS.append((name, bool(ok), detail))
    print("CHECK|%-46s %s | bad_impl_would_be=%s | %s"
          % (name, "PASS" if ok else "FAIL", bad_impl or "-", detail[:400]), flush=True)
    return ok


def no_run(why):
    print("NO-RUN %s" % why, flush=True)
    print("E2E|run=%s result=NO-RUN reason=%s" % (RUN_TAG, why))
    sys.exit(2)


def sh(args, timeout=180, cwd=REPO, env=None):
    try:
        p = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=timeout, env=env)
        return p.returncode, (p.stdout or b"").decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return 124, "TIMEOUT"


def md5_of(data):
    return hashlib.md5(data).hexdigest()


def dump(relname, data):
    """把决定性字节落到本次运行目录（持久产物只写 ~/.cache，不写 /tmp）。"""
    path = os.path.join(OUT, relname)
    with open(path, "wb") as f:
        f.write(data if isinstance(data, bytes) else data.encode("utf-8", "replace"))
    return path


# ---------------- 杠④：只量不变量，永不开 key 的值 ----------------

def bar4(tag):
    names = sorted(os.listdir(BAR4_HOME)) if os.path.isdir(BAR4_HOME) else []
    st = {}
    for f in ("config.properties", "state.db"):
        p = os.path.join(BAR4_HOME, f)
        st[f] = md5_of(open(p, "rb").read())[:8] if os.path.exists(p) else "MISSING"
    # 真 key 只量长度，值全程不读
    rc, klen = sh(["zsh", "-c",
                   "awk -F= '/^minimax\\.api\\.key=/{print length($2)}' '%s/config.properties'"
                   % BAR4_HOME], timeout=30)
    line = ("BAR4|tag=%s dir_count=%s cfg_md5_8=%s db_md5_8=%s key_len_only=%s names=%s"
            % (tag, len(names), st["config.properties"], st["state.db"],
               klen.strip(), json.dumps(names)))
    print(line, flush=True)
    return line, names, st


# ---------------- 自写假 LLM（OpenAI 兼容；bind(0)；Connection: close） ----------------

class FakeLlm(threading.Thread):
    """一次性假供应商。记录：请求是否流式、**实际收到的 Authorization**、答复完成的时刻。"""

    def __init__(self):
        threading.Thread.__init__(self)
        self.daemon = True
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(16)
        self.port = self.sock.getsockname()[1]
        self.hits = []
        self.auth_seen = []
        self.first_reply_done = None
        self.script = ["SSE-P28C-OK"]
        self.stop = False

    def script_next(self):
        if len(self.script) > 1:
            return self.script.pop(0)
        return self.script[0]

    def run(self):
        while not self.stop:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        try:
            conn.settimeout(15)
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
            am = re.search(rb"Authorization: ([^\r\n]*)", head, re.I)
            self.auth_seen.append(am.group(1).decode("latin-1") if am else "<none>")
            streamed = b'"stream":true' in rest or b'"stream": true' in rest
            content = self.script_next()
            self.hits.append({"bytes": len(rest), "stream": streamed, "content": content})
            time.sleep(LLM_SLEEPS_MS / 1000.0)      # 故意慢：头必须在这之前就发出去
            if streamed:
                first = {"id": "cc-p28c", "object": "chat.completion.chunk", "created": 1,
                         "model": "MiniMax-Text-01",
                         "choices": [{"index": 0, "finish_reason": None,
                                      "delta": {"role": "assistant", "content": content}}]}
                last = {"id": "cc-p28c", "object": "chat.completion.chunk", "created": 1,
                        "model": "MiniMax-Text-01",
                        "choices": [{"index": 0, "finish_reason": "stop", "delta": {}}]}
                sse = ("data: %s\n\ndata: %s\n\ndata: [DONE]\n\n"
                       % (json.dumps(first), json.dumps(last))).encode("utf-8")
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                             b"Content-Length: %d\r\nConnection: close\r\n\r\n" % len(sse) + sse)
            else:
                body_obj = {"id": "cc-p28c", "object": "chat.completion", "created": 1,
                            "model": "MiniMax-Text-01",
                            "choices": [{"index": 0, "finish_reason": "stop",
                                         "message": {"role": "assistant", "content": content}}],
                            "usage": {"prompt_tokens": 3, "completion_tokens": 2,
                                      "total_tokens": 5}}
                body = json.dumps(body_obj).encode("utf-8")
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                             b"Content-Length: %d\r\nConnection: close\r\n\r\n" % len(body) + body)
            self.first_reply_done = time.time()
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


# ---------------- A 组：真 PTY / 真管道下的 fd 0 继承 ----------------

def probe_cmd(cp):
    return ["java", "-cp", cp, PROBE]


def pty_fd0_run(cmd, feed, env):
    """真 PTY 上起探针；父端**从 slave 设备名外部读取 termios**，不采信子进程自报。

    返回 (master 读到的字节, rc, 墙钟, raw 切换在外部可见的时刻或 None, 报错)。
    关键：喂字节之前先等"slave 上 ECHO/ICANON 真的关掉"——
      * `runBounded` 若丢了 `.redirectInput(INHERIT)`，`stty` 的 fd 0 就是管道
        ⇒ slave 的 termios 永远不变 ⇒ 这里超时、且回显是 tty + 应用双份。
    """
    mfd, sfd = os.openpty()
    import termios as _t
    slave = os.ttyname(sfd)
    t0 = time.time()
    err = ""
    raw_visible_at = None
    out = b""
    child = None
    try:
        before = _t.tcgetattr(os.open(slave, os.O_RDONLY | os.O_NOCTTY))[3]
        child = subprocess.Popen(cmd, stdin=sfd, stdout=sfd, stderr=subprocess.STDOUT,
                                 env=env, cwd=REPO, close_fds=True)
        os.close(sfd)
        sfd = None
        deadline = t0 + 12.0
        while time.time() < deadline:
            if child.poll() is not None:
                err = "child exited early rc=%s" % child.poll()
                break
            fd = os.open(slave, os.O_RDONLY | os.O_NOCTTY)
            try:
                flags = _t.tcgetattr(fd)[3]
            finally:
                os.close(fd)
            if not (flags & _t.ECHO) and not (flags & _t.ICANON):
                raw_visible_at = time.time() - t0
                break
            time.sleep(0.05)
        if raw_visible_at is None:
            err = (err + " RAW_NOT_VISIBLE_ON_SLAVE").strip()
        os.write(mfd, feed)
        token = feed.decode().strip().strip("/")
        while time.time() - t0 < 45:
            r, _, _ = select_read(mfd, 0.3)
            if r:
                try:
                    d = os.read(mfd, 4096)
                except OSError:
                    break
                if not d:
                    break
                out += d
            if b"PROBE_BACKUP_EXISTS" in out and child.poll() is not None:
                break
        rc = child.poll()
        if rc is None:
            child.kill()
            child.wait()
            rc = "KILLED"
            err = (err + " HARD-KILLED").strip()
        pre = out.decode("utf-8", "replace").split("PROBE_", 1)[0]
        echo_count = pre.count(token) if token else -1
    finally:
        if sfd is not None:
            os.close(sfd)
        try:
            os.close(mfd)
        except OSError:
            pass
    _ = before
    return out, rc, time.time() - t0, raw_visible_at, echo_count, err


def select_read(fd, timeout):
    import select
    return select.select([fd], [], [], timeout)


def parse_probe(blob):
    d = {}
    for m in re.finditer(r"PROBE_([A-Z_]+)=(\S*)", blob.decode("utf-8", "replace")):
        d["PROBE_" + m.group(1)] = m.group(2)
    return d


# ---------------- 裸 socket HTTP：把线上原始字节整份拿到 ----------------

def free_port():
    """让内核挑一个此刻真空闲的 127.0.0.1 端口（`bind(0)` 后立刻读回再关）。

    不这么做就是写死端口：并行量具之间互相抢，抢到别人的端点还会把"对端回了 200"
    当成自己的 serve 起了 —— 所以这里只负责拿号，起没起住由 S0 那两条
    （状态码 + `/bot/status` 的 body 形状）判，`curl` 对 404 也返回 0 不算证据。
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return int(port)


def raw_http(port, method, path, body=None, want_stream=False, hard_seconds=60):
    """真 HTTP 客户端。返回 (原始响应字节, 头部 dict, 状态行, 去 chunked 后的 body, 头到达时刻)。"""
    s = socket.create_connection(("127.0.0.1", port), timeout=hard_seconds)
    s.settimeout(hard_seconds)
    payload = body.encode("utf-8") if body is not None else b""
    lines = ["%s %s HTTP/1.1" % (method, path), "Host: 127.0.0.1:%d" % port,
             "Content-Length: %d" % len(payload), "Connection: keep-alive"]
    if body is not None:
        lines.append("Content-Type: application/json")
    if want_stream:
        lines.append("Accept: text/event-stream")
    req = ("\r\n".join(lines) + "\r\n\r\n").encode("latin-1") + payload
    s.sendall(req)
    raw = b""
    head_end = -1
    head_ts = None
    while True:
        try:
            chunk = s.recv(4096)
        except socket.timeout:
            break
        if not chunk:
            break
        raw += chunk
        if head_end < 0:
            head_end = raw.find(b"\r\n\r\n")
            if head_end >= 0:
                head_ts = time.time()
        if head_end >= 0 and raw.endswith(b"0\r\n\r\n"):
            break
    s.close()
    if head_end < 0:
        return raw, {}, "", b"", head_ts
    head = raw[:head_end].decode("latin-1", "replace")
    hs = head.split("\r\n")
    headers = {}
    for line in hs[1:]:
        k, _, v = line.partition(":")
        if v:
            headers[k.strip().lower()] = v.strip()
    body_bytes = dechunk(raw[head_end + 4:]) if headers.get("transfer-encoding", "").lower() \
        == "chunked" else raw[head_end + 4:]
    return raw, headers, hs[0] if hs else "", body_bytes, head_ts


def dechunk(data):
    out = b""
    i = 0
    while i < len(data):
        j = data.find(b"\r\n", i)
        if j < 0:
            return out + data[i:]
        try:
            n = int(data[i:j].split(b";")[0], 16)
        except ValueError:
            return out + data[i:]
        if n == 0:
            return out
        out += data[j + 2:j + 2 + n]
        i = j + 2 + n + 2
    return out


# ---------------- C 组：命令面三张表的机械派生（口径照抄 WIRING.md，不自创） ----------------

def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def table_A(slash_src):
    """P19 之前的形状：`SlashRegistry` 里每条匿名命令的 `name()` 都是纯字面量。
    P19 把名字搬进 `CommandCatalog` 后这里恒为空 —— 现在只当**回归探测器**用
    （非空 ⇒ 有人绕过单源，又把名字硬抄回注册表）。"""
    out = []
    want = False
    for line in slash_src.splitlines():
        if re.search(r"public String name\(\)", line):
            want = True
            continue
        if want and re.match(r"^\s*return \"[^\"]*\";\s*$", line):
            out.append(re.search(r'"([^"]*)"', line).group(1))
            want = False
    return sorted(set(out))


def table_B(reader_src):
    """P19 之前的形状：`LOCAL_COMMANDS = Arrays.asList("/x", …)` 自己抄一份。
    同样只当回归探测器：非空 ⇒ 终端侧又出现了第二份清单。"""
    out = []
    inlist = False
    for line in reader_src.splitlines():
        if re.search(r"LOCAL_COMMANDS\s*=\s*Arrays\.asList\(", line):
            inlist = True
        if inlist:
            for m in re.finditer(r'"(/[^"]*)"', line):
                out.append(m.group(1))
            if re.search(r"\);", line):
                inlist = False
    return sorted(set(out))


def api_commands(port):
    """真进程读 `GET /api/commands`：P19 之后"命令面在每端各是哪几条"的唯一对外口径。
    返回 (状态码, rows 或 None)。rows 里每行的 `endpoints` 是它被广告的端点列表。"""
    raw, hd, st, body, _ = raw_http(port, "GET", "/api/commands", None, hard_seconds=20)
    toks = st.split(" ")
    code = toks[1] if len(toks) > 1 else "?"
    try:
        rows = json.loads(body.decode("utf-8", "replace"))
    except ValueError:
        return code, None
    return code, rows if isinstance(rows, list) else None


def segment(rows, key, without=None):
    """从 /api/commands 的字节里取某一段的名字集合。"""
    out = []
    for r in rows or []:
        eps = r.get("endpoints") or []
        if key in eps and (without is None or without not in eps):
            out.append(r.get("name"))
    return sorted(set(x for x in out if x))


def table_C(html):
    """WIRING §1 第 6 行 / w_diff.sh 的 C：web 控制台真有分支的（三种写法的并集）。"""
    out = set(re.findall(r"cmd === '(/[a-z-]+)'", html))
    out |= set(re.findall(r"cmd\.startsWith\('(/[a-z-]+)'\)", html))
    out |= set(re.findall(r"'(/[a-z-]+)' === cmd", html))
    return sorted(out)


def table_D(html):
    """WIRING §3.4 的尺：`命令列表：` 到下一个反引号+括号 之间的行首 /x。"""
    seg = []
    inside = False
    for line in html.splitlines():
        if not inside:
            if "命令列表：" in line:
                inside = True
                continue
            continue
        if "`)" in line:
            # 最后一条广告和收尾的 `) 在同一行（index.html:1197 的 /exit）。
            # 先收进行再停：早停一个字符，3.4 桶就会从 1 掉成 0（lead_r1 实测假红，
            # 而 WIRING.md 里那条 `/exit 广告了没分支` 其实是对的）。
            seg.append(line)
            break
        seg.append(line)
    body = "\n".join(seg)
    return sorted(set(re.findall(r"^(/[a-z-]+)", body, re.M)))


def wiring_number(md, key):
    """从 WIRING.md 的表行里取 `**N**`；找不到即 NO-RUN 级别的问题（由调用处判红）。"""
    for line in md.splitlines():
        if key in line:
            m = re.search(r"\*\*(\d+)\*\*", line)
            if m:
                return int(m.group(1)), line
    return None, ""


def wiring_members(md, key):
    for line in md.splitlines():
        if key in line:
            cells = [c.strip() for c in line.strip("|").split("|")]
            tokens = []
            for c in cells:
                for span in re.findall(r"`([^`]*)`", c):
                    for tok in span.split():
                        if tok.startswith("/"):
                            tokens.append(tok)
            return sorted(set(tokens)), line
    return [], ""


def routes_tsv(path):
    rows = []
    for line in read(path).splitlines():
        if line.startswith("#") or not line.strip() or line.startswith("method\t"):
            continue
        cols = line.split("\t")
        if len(cols) >= 6:
            rows.append(cols)
    return rows


def dispatch_literals(http_src):
    """EVIDENCE §0.2 的口径：dispatch() 方法体里出现的每个 `"<path>".equals(path)` 字面量。"""
    body = []
    depth = 0
    started = False
    for line in http_src.splitlines():
        if not started and re.search(r"private void dispatch\(HttpExchange", line):
            started = True
        if started:
            body.append(line)
            depth += line.count("{") - line.count("}")
            if depth <= 0 and len(body) > 1:
                break
    src = "\n".join(body)
    found = re.findall(r'"(/[A-Za-z0-9_./-]*)"\s*\.\s*equals\(\s*path\s*\)', src)
    return sorted(set(found)), len(body)


# ---------------- 主流程 ----------------

def main():
    for p in (os.path.join(REPO, "z-bot-core", "src", "main", "java"),
              os.path.join(HERE, "ROUTES.tsv"), os.path.join(HERE, "WIRING.md")):
        if not os.path.exists(p):
            no_run("先决路径缺失: %s" % p)
    if os.path.exists(OUT):
        shutil.rmtree(OUT)
    os.makedirs(CFG)
    print("E2E|run=%s repo=%s out=%s" % (RUN_TAG, REPO, OUT), flush=True)

    _l0, home_names0, home_st0 = bar4("t0_before_build")

    t0 = time.time()
    rc_build, build_out = sh(["mvn", "-o", "-pl", "z-bot-core", "test-compile"],
                             timeout=MVN_TIMEOUT)
    dump("build_testcompile.log", build_out)
    probe_cls = os.path.join(TESTCLASSES, "com/zifang/z/bot/ui/RawTerminalVerdictProbe.class")
    main_cls = os.path.join(CLASSES, "com/zifang/z/bot/channel/HttpChannel.class")
    print("BUILD|rc=%d wall=%.1fs httpchannel_class=%s probe_class=%s"
          % (rc_build, time.time() - t0, os.path.isfile(main_cls), os.path.isfile(probe_cls)),
          flush=True)
    if not (os.path.isfile(main_cls) and os.path.isfile(probe_cls)):
        no_run("class 没产出来（rc=%d），量不了字节 ⇒ NO-RUN" % rc_build)

    cp_file = os.path.join(OUT, "cp.txt")
    rc_cp, _ = sh(["mvn", "-o", "-q", "-pl", "z-bot-core", "dependency:build-classpath",
                   "-Dmdep.outputFile=" + cp_file], timeout=MVN_TIMEOUT)
    size = os.path.getsize(cp_file) if os.path.isfile(cp_file) else -1
    print("BUILD|classpath_file=%s size=%d rc=%d" % (cp_file, size, rc_cp), flush=True)
    if size <= 0:
        no_run("dependency:build-classpath 产出 0 字节/缺文件 —— 没量到依赖，不算通过")
    dep_cp = read(cp_file).strip()
    cp = os.path.join(CLASSES, "") + os.pathsep + dep_cp
    cp_probe = TESTCLASSES + os.pathsep + CLASSES + os.pathsep + dep_cp

    _l1, names1, st1_map = bar4("t1_after_build")

    with open(os.path.join(CFG, "config.properties"), "w", encoding="utf-8") as f:
        f.write("provider=minimax\n"
                "minimax.type=openai\n"
                "minimax.api.key=%s\n"
                "minimax.base.url=http://127.0.0.1:%d/v1\n"
                "minimax.model=MiniMax-Text-01\n"
                "zhipu.api.key=%s\n" % (STUB_KEY, 0, STUB_KEY))

    fake = FakeLlm()
    fake.start()
    with open(os.path.join(CFG, "config.properties"), "w", encoding="utf-8") as f:
        f.write("provider=minimax\n"
                "minimax.type=openai\n"
                "minimax.api.key=%s\n"
                "minimax.base.url=http://127.0.0.1:%d/v1\n"
                "minimax.model=MiniMax-Text-01\n"
                "zhipu.api.key=%s\n" % (STUB_KEY, fake.port, STUB_KEY))
    print("FAKELLM|port=%d" % fake.port, flush=True)
    env = dict(os.environ)
    env.pop("ZBOT_HOME", None)

    # ================= A 组：runBounded 继承 fd 0 =================
    token = "zq7k%s" % int(time.time() % 100000)
    out_a, rc_a, wall_a, raw_at, echo_a, err_a = pty_fd0_run(
        probe_cmd(cp_probe), ("/" + token + "\n").encode(), env)
    pa = parse_probe(out_a)
    dump("A_pty_master.bin", out_a)
    print("A1|rc=%s wall=%.2f raw_visible_at=%s echo_count=%s verdict=%s raw_mode=%s err=%s"
          % (rc_a, wall_a, raw_at, echo_a, pa.get("PROBE_RAW_VERDICT"),
             pa.get("PROBE_RAW_MODE"), err_a), flush=True)
    chk("A1 fd0_stty_really_touched_child_stdin", raw_at is not None and pa.get("PROBE_RAW_MODE") == "true",
        "去掉 .redirectInput(Redirect.INHERIT)（stty 的 fd0 变成管道）",
        "父端在 slave 设备上外部读到 ECHO/ICANON 关闭于 %s s，探针自报 raw_mode=%s"
        % (("%.2f" % raw_at) if raw_at else "NEVER", pa.get("PROBE_RAW_MODE")))
    chk("A2 fd0_verdict_DONE_on_real_tty", pa.get("PROBE_RAW_VERDICT") == "DONE"
        and pa.get("PROBE_RESTORE_VERDICT") == "DONE" and rc_a == 0,
        "同上（M1 实测该判据即红：verdict NONZERO_EXIT）",
        "raw=%s restore=%s rc=%s wall=%.2f" % (pa.get("PROBE_RAW_VERDICT"),
                                               pa.get("PROBE_RESTORE_VERDICT"), rc_a, wall_a))
    chk("A3 fd0_single_echo_no_tty_replay", echo_a == 1,
        "raw -echo 没作用到 stdin 那个 fd ⇒ tty 回显 + 应用回显双份",
        "token=%s 出现在 PROBE_ 之前 %d 次（期望 1）master_md5=%s"
        % (token, echo_a, md5_of(out_a)[:8]))
    chk("A4 real_stdin_bytes_round_trip", pa.get("PROBE_LINE") == ("/" + token + "\\n"),
        "readLine 把继承来的 fd0 换成了别的源（读不回真字节）",
        "PROBE_LINE=%s expected=/%s\\n" % (pa.get("PROBE_LINE"), token))

    out_b, rc_b, wall_b = (b"", None, 0.0)
    tb = time.time()
    pb_run = subprocess.run(probe_cmd(cp_probe), input=("/pipe-line\n").encode(),
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            env=env, cwd=REPO, timeout=60)
    out_b, rc_b, wall_b = pb_run.stdout, pb_run.returncode, time.time() - tb
    pb = parse_probe(out_b)
    dump("B_pipe_stdout.bin", out_b)
    print("A5|rc=%s wall=%.2f verdict=%s raw_mode=%s line=%s"
          % (rc_b, wall_b, pb.get("PROBE_RAW_VERDICT"), pb.get("PROBE_RAW_MODE"),
             pb.get("PROBE_LINE")), flush=True)
    chk("A5 no_tty_pipe_fd0_is_NONZERO_EXIT", pb.get("PROBE_RAW_VERDICT") == "NONZERO_EXIT"
        and wall_b < 20.0,
        "把 stty 的判词恒报 DONE（M3 型）或干脆不看 fd 形态",
        "同一份代码、fd0 是管道 ⇒ raw=%s（与 A2 的 DONE 构成对偶，这条区分得出'继承'与'被管道替换'）"
        " 整跑 %.2fs" % (pb.get("PROBE_RAW_VERDICT"), wall_b))
    chk("A6 pipe_stdin_still_round_trips", pb.get("PROBE_LINE") == "/pipe-line\\n",
        "readLine 在 fd0 不是 tty 时吞输入",
        "PROBE_LINE=%s" % pb.get("PROBE_LINE"))

    poison = os.path.join(OUT, "poisonbin")
    os.makedirs(poison, exist_ok=True)
    with open(os.path.join(poison, "stty"), "w", encoding="utf-8") as f:
        f.write("#!/bin/sh\nsleep 60\n")
    os.chmod(os.path.join(poison, "stty"), 0o755)
    env3 = dict(env)
    env3["PATH"] = poison + os.pathsep + env3["PATH"]
    out_c, rc_c, wall_c, raw_at3, echo_c, err_c = pty_fd0_run(
        probe_cmd(cp_probe), ("/after-poison\n").encode(), env3)
    pc = parse_probe(out_c)
    dump("C_poison_master.bin", out_c)
    print("A7|rc=%s wall=%.2f raw_visible_at=%s verdict=%s construct_ms=%s err=%s"
          % (rc_c, wall_c, raw_at3, pc.get("PROBE_RAW_VERDICT"),
             pc.get("PROBE_CONSTRUCT_MS"), err_c), flush=True)
    chk("A7 bounded_wait_survives_poisoned_stty",
        rc_c == 0 and wall_c < 25.0 and pc.get("PROBE_RAW_VERDICT") == "TIMED_OUT"
        and pc.get("PROBE_LINE") == "/after-poison\\n",
        "把 EXTERNAL_WAIT_TIMEOUT_MS 抬成 Long.MAX（M2 型 ⇒ 整跑被墙钟砍掉）",
        "假 stty 睡 60s：整跑 %.2fs 回来、判词 %s、字节仍读回" % (wall_c, pc.get("PROBE_RAW_VERDICT")))

    # ================= 起真 serve =================
    port_s = free_port()
    srv_log = open(os.path.join(OUT, "serve_stdout.log"), "wb")
    srv = subprocess.Popen(["java", "-cp", cp, ZBOT, "serve", "--port", str(port_s),
                            "--config-dir", CFG],
                           cwd=REPO, stdout=srv_log, stderr=subprocess.STDOUT, env=env)

    def _reap(_srv=srv, _log=srv_log):
        # 量具自己在 B/C 组中途抛异常时（lead_r1 之前那一崩就是），回收步骤（下面 D 组那句
        # srv.terminate）根本走不到，serve 子进程就永久漏在机器上 —— 已实测漏出 3 个 JVM。
        # 挂在 atexit 上：正常路径重复 terminate 已退出的进程无害。
        if _srv.poll() is None:
            _srv.terminate()
            try:
                _srv.wait(timeout=5)
            except Exception:
                _srv.kill()
                _srv.wait()
        _log.close()

    atexit.register(_reap)
    up = ""
    t_up = time.time()
    while time.time() - t_up < 60:
        try:
            raw_h, hs, status_line, body, _ = raw_http(port_s, "GET", "/bot/status", hard_seconds=5)
        except ConnectionRefusedError:
            # 端口号是 bind(0) 现取的，serve 还没 listen 之前连不上是**正常**的。
            # 不接这一手的话：轮询第一次抢在 listen 之前 ⇒ 整跑以 rc=3 崩掉，
            # 而且崩在没有 finally 的路径上 ⇒ 那个 java serve 子进程永久漏在机器上。
            status_line, body = "", b""
        if status_line.startswith("HTTP/1.1 200") and body:
            up = body.decode("utf-8", "replace")
            break
        if srv.poll() is not None:
            break
        time.sleep(0.5)
    print("SERVE|pid=%d port=%d up=%s wall=%.2f" % (srv.pid, port_s, bool(up), time.time() - t_up),
          flush=True)
    chk("S0 serve_up_on_loopback_free_port", bool(up) and srv.poll() is None,
        "端口/通道没接线（拿不到 200 就什么都量不到）",
        "port=%d /bot/status=%r" % (port_s, up[:80]))
    # `-a` 是把 -p 与 -i 两个选择器**求交**的开关。少了它 lsof 取并集：
    # lead_r1 实测 sockets=31、offenders 里是 rapportd 的 *:49236（macOS 自己的守护进程），
    # 一条与 z-bot 无关的监听把这条"只绑回环"的守卫判成了红。
    rc_ls, ls_out = sh(["zsh", "-c",
                        "lsof -nP -a -p %d -iTCP -sTCP:LISTEN 2>/dev/null | tail -n +2" % srv.pid],
                       timeout=40)
    listen_lines = [l for l in ls_out.splitlines() if "LISTEN" in l]
    # 原写法在找 "(127.0.0.1:" —— 那个左括号是 lsof 打在 `*:49236 (LISTEN)` 里的，
    # 回环地址后面**不带**括号（`TCP 127.0.0.1:60942 (LISTEN)`），于是自家回环监听每一条
    # 都被当成违规（lead_v2_r1 实测：sockets=1 且唯一那条就是 offenders[0]，看着像"绑了 0.0.0.0"）。
    # 取地址字段自己比，别再用括号当锚。
    nonloop = [l for l in listen_lines
               if not (addr_of(l).startswith("127.") or addr_of(l).startswith("[::1]"))]
    chk("S0b listen_sockets_are_loopback_only", bool(listen_lines) and not nonloop,
        "缺省绑到 0.0.0.0（本机 --host 没给）",
        "sockets=%d addrs=%s offenders=%s"
        % (len(listen_lines), [addr_of(l) for l in listen_lines][:4], nonloop[:1]))

    # ================= B 组：SSE 线上字节 =================
    fake.script = ["第一行\n第二行\r\n第三行"]
    raw_wire, sse_headers, sse_status, sse_body, head_ts = raw_http(
        port_s, "POST", "/bot/chat/stream", json.dumps({"message": "p28c 帧切分"}),
        want_stream=True, hard_seconds=90)
    dump("SSE_raw_wire.bin", raw_wire)
    dump("SSE_body.bin", sse_body)
    done_ts = fake.first_reply_done
    print("SSE|status=%r ct=%r clen=%r te=%r body_bytes=%d md5=%s head_before_reply=%s"
          % (sse_status, sse_headers.get("content-type"), sse_headers.get("content-length"),
             sse_headers.get("transfer-encoding"), len(sse_body), md5_of(sse_body)[:8],
             (done_ts is not None and head_ts is not None and head_ts < done_ts)), flush=True)
    dump("SSE_body_od_c.txt", od_c(sse_body))
    print("SSE_ODC_FIRST120=%s" % od_c(sse_body)[:520].replace("\n", "\\n"), flush=True)

    sep = b"\n\n"
    parts = sse_body.split(sep)
    tail = parts[-1]
    frames = parts[:-1] if tail == b"" else []
    reassembled = sep.join(frames) + (sep if tail == b"" and frames else b"")
    lines_of = md5_of(sse_body)[:8]
    print("SSE_FRAMES=%d reassemble_equal=%s tail=%r" % (len(frames), reassembled == sse_body, tail[:20]),
          flush=True)

    chk("S1_wire_ends_with_double_newline", sse_body.endswith(sep) and tail == b"",
        "服务端把帧间/收尾的 \\n 少写一个（帧边界不闭合，前端收不到最后一帧）",
        "body_md5=%s tail=%r last16=%r" % (lines_of, tail[:16], sse_body[-16:]))
    chk("S2_gauge_framer_round_trips_wire_bytes", reassembled == sse_body and len(frames) >= 3,
        "量具自己的切帧器把分隔符最后一个 \\n 当开销砍掉（p28b 修之前的旧切法）⇒ 逐字节等式恒不成立",
        "frames=%d len(重拼)=%d len(线上)=%d" % (len(frames), len(reassembled), len(sse_body)))
    # 阳性对照：把"旧切法"（new String(b,0,b.length-1)）跑同一份线上字节，必须判出不一致
    old_style = [f[:-1] for f in frames]
    chk("S3_old_chopping_framer_positive_control",
        b"".join(x + sep for x in old_style) != sse_body and len(frames) >= 3,
        "（对照项）若线上帧真的不带收尾 \\n，这一条会绿——它存在的意义就是证明 S2 有牙",
        "旧切法重拼长度=%d vs 线上=%d" % (len(b"".join(x + sep for x in old_style)), len(sse_body)))

    vocabulary = set()
    for r in routes_tsv(os.path.join(HERE, "ROUTES.tsv")):
        if r[1] == "/bot/chat/stream" and "sse-events:" in r[5]:
            vocabulary = set(r[5].split("sse-events:")[1].split(";")[0].split(","))
    parsed = []
    grammar_ok = True
    grammar_bad = ""
    for f in frames:
        # frames 是 sse_body.split(b"\\n\\n") 去掉末段得来的 ⇒ 帧**自带**的那个 \n 已被当成分隔符吃掉，
        # 帧内不存在收尾 \n。原先的 `\\n$` 让三帧全部判"语法坏"，parsed 空 ⇒ S5/S6/S7 一起空跑红。
        # 用 \\Z 而不是 $：`$` 会放过"帧尾多一个 \\n"的坏形状。
        # 牙还在：data 里若漏出裸换行，帧里就多出第三行，[^\n]*$ 之后剩内容 ⇒ 整帧不匹配 ⇒ 红。
        m = FRAME_RE.match(f)
        if not m:
            grammar_ok = False
            grammar_bad = f[:60].decode("utf-8", "replace")
            break
        ev = m.group(1).decode()
        parsed.append((ev, m.group(2).decode("utf-8", "replace")))
    chk("S4_every_frame_is_event_line_plus_data_line",
        grammar_ok and len(parsed) == len(frames) and b"\r" not in sse_body,
        "data 里漏出裸换行 ⇒ 一帧被劈成两帧（M8 摘掉转义即红）",
        "frames=%d 解析出=%d 语法坏帧=%r has_cr=%s" % (len(frames), len(parsed), grammar_bad, b"\r" in sse_body))
    unknown_events = sorted({e for e, _ in parsed} - vocabulary)
    chk("S5_event_names_are_in_ledger_vocabulary", bool(parsed) and not unknown_events,
        "服务端发出台账之外的新事件名（前端会静默丢帧，D-P28-3 的成因）",
        "vocabulary=%d seen=%s unknown=%s" % (len(vocabulary),
                                              sorted({e for e, _ in parsed}), unknown_events))
    # 逐段判"没有裸换行"。原先写成 datas = "\n".join(...) 再问 datas 里有没有 \n ——
    # 那个 \n 是 join 自己塞进去的，≥2 帧时恒假（lead_r1 实测 frames=3 恒红）。
    final_data = [d for e, d in parsed if e == "final"]
    chk("S6_newline_inside_model_text_is_escaped",
        bool(parsed) and bool(final_data)
        and all("\n" not in d for _, d in parsed)
        and any("第一行" in d and "\\n" in d for d in final_data),
        "frame() 里去掉 .replace(\"\\n\", \"\\\\n\")（M8）⇒ 正文换行劈开帧",
        "final=%r 帧数=%d 含裸换行的段=%d" % (final_data[:1], len(frames),
                                             sum(1 for _, d in parsed if "\n" in d)))
    step_frames = len([1 for e, _ in parsed if e == "step"])
    finals = [unescape(d) for e, d in parsed if e == "final"]
    last_event = parsed[-1][0] if parsed else ""
    done_data = parsed[-1][1] if parsed else ""
    m_done = re.match(r"^\[DONE] steps=(\d+) replyLen=(\d+)$", done_data)
    # replyLen 的真值只能取"供应商实际被请求答复的那串"，不能取帧里正文反解出来的长度：
    # frame() 先 .replace("\r","") 再转义 \n，所以本跑（脚本里故意放了 \r\n）线上正文比原文短 1。
    # 两个方向都钉：摘转义 ⇒ S6 红；数字随手写 / 归一化偷偷变 ⇒ 这两条红。
    served_text = fake.hits[-1]["content"] if fake.hits else ""
    chk("S7_last_frame_is_done_and_its_numbers_are_real",
        m_done and last_event == "done"
        and int(m_done.group(1)) == step_frames
        and int(m_done.group(2)) == len(served_text)
        and finals == [served_text.replace("\r", "")],
        "done 帧随手写数字（台账 count/total 谎报的同型）",
        "done=%r step_frames=%d 原文长=%d 剥\\r后长=%d" % (done_data[:60], step_frames,
                                                        len(served_text),
                                                        sum(len(x) for x in finals)))
    chk("S8_headers_carry_event_stream_without_content_length",
        sse_status.startswith("HTTP/1.1 200")
        and (sse_headers.get("content-type") or "").startswith("text/event-stream")
        and "content-length" not in sse_headers,
        "把整段答复攒完再发（带 Content-Length 的伪流式）",
        "status=%r ct=%r clen=%s" % (sse_status, sse_headers.get("content-type"),
                                     sse_headers.get("content-length")))
    chk("S9_response_head_precedes_llm_reply",
        head_ts is not None and done_ts is not None and head_ts < done_ts,
        "sendResponseHeaders 挪到 agent.chat() 之后 ⇒ 头与帧一起到，握手判据红",
        "head_at=%.3f llm_replied_at=%.3f gap=%.3fs（假 LLM 故意睡 %dms）"
        % (head_ts or 0, done_ts or 0, (done_ts or 0) - (head_ts or 0), LLM_SLEEPS_MS))
    chk("S10_fake_llm_actually_served_this_turn",
        len(fake.hits) >= 1 and fake.hits[-1]["content"].startswith("第一行"),
        "流量根本没到供应商（那 SSE 断言就全是空跑）",
        "hits=%s" % fake.hits[-1:])

    # ================= C 组：三张表 + 台账一致性 =================
    slash_src = read(os.path.join(REPO, "z-bot-core/src/main/java/com/zifang/z/bot/slash/SlashRegistry.java"))
    reader_src = read(os.path.join(REPO, "z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java"))
    html_src = read(os.path.join(REPO, "z-bot-core/src/main/resources/web/index.html"))
    http_src = read(os.path.join(REPO, "z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java"))
    wiring_md = read(os.path.join(HERE, "WIRING.md"))
    # P19 之后：命令名的单源在 CommandCatalog，对外口径是 GET /api/commands 的 endpoints 分段。
    # 所以 A/B/D 三段从**真进程的响应字节**里取，C 从盘上 HTML 的分支里取 —— 差集比的正是
    # "服务端广告了这一段的字节" 与 "控制台真有一段分支接住"。旧的静态解析降级成回归探测器。
    code_cmd, cmd_rows = api_commands(port_s)
    A = segment(cmd_rows, "http")
    B = segment(cmd_rows, "tui", without="http")
    D = segment(cmd_rows, "web")
    C = table_C(html_src)
    stale_A, stale_B, stale_D = table_A(slash_src), table_B(reader_src), table_D(html_src)
    print("TABLES|A=%d B=%d C=%d D=%d api_status=%r rows=%s"
          % (len(A), len(B), len(C), len(D), code_cmd, "?" if cmd_rows is None else len(cmd_rows)),
          flush=True)
    print("TABLE_A=%s" % " ".join(A), flush=True)
    print("TABLE_B=%s" % " ".join(B), flush=True)
    print("TABLE_C=%s" % " ".join(C), flush=True)
    print("TABLE_D=%s" % " ".join(D), flush=True)
    print("HAND_COPIED|registry_name_literals=%s reader_literals=%s web_list_literals=%s"
          % (stale_A, stale_B, stale_D), flush=True)
    chk("T8_command_names_come_from_one_source_after_p19",
        code_cmd == "200" and cmd_rows and A and D and not stale_A and not stale_B and not stale_D,
        "P19 搬空的三处手抄清单里任何一处又长回来（注册表 name() 字面量 / LOCAL_COMMANDS.asList /"
        " web 侧 `命令列表：` 硬写），或 /api/commands 不再可达（那 A/B/D 三段全是空集）",
        "api=%s rows=%d A=%d D=%d | 回归探测器三路应全空：%s/%s/%s"
        % (code_cmd, 0 if cmd_rows is None else len(cmd_rows), len(A), len(D),
           stale_A, stale_B, stale_D))

    _raw, hs_i, status_i, body_i, _ = raw_http(port_s, "GET", "/console", hard_seconds=20)
    served_html = body_i.decode("utf-8", "replace")
    Cs, Ds = table_C(served_html), table_D(served_html)
    print("SERVED|status=%r bytes=%d C_from_served=%s D_from_served=%s"
          % (status_i, len(served_html), " ".join(Cs), " ".join(Ds)), flush=True)
    # 状态行是 "HTTP/1.1 200 OK"，endswith("200") 恒假（lead_r1：两份 html 的 C/D 逐条相同却判红）。
    code_i = status_i.split(" ")[1] if len(status_i.split(" ")) > 1 else "?"
    chk("T1_web_tables_from_disk_equal_from_served_bytes",
        Cs == C and Ds == D and code_i == "200",
        "resources 里的 index.html 与 classpath 里被 serve 出去的那份分叉（文档派生尺读的是盘上文件）",
        "status=%r disk C=%s D=%s served C=%s D=%s" % (status_i, C, D, Cs, Ds))

    diffs = {
        "3.1 只在服务端注册表": [x for x in A if x not in set(B) | set(C)],
        "3.2 只在 TUI 私有": [x for x in B if x not in set(A)],
        "3.3 只在 web 有分支": [x for x in C if x not in set(A) | set(B)],
        "3.4 web 广告了但没有分支接住": [x for x in D if x not in set(C)],
        "3.5 web 有分支但没广告": [x for x in C if x not in set(D)],
        "3.6 两边都有、实现不同": sorted(set(A) & set(C) | set(B) & set(C)),
    }
    diffs["3.6 两边都有、实现不同"] = [x for x in C if x in set(A) | set(B)]
    total_named = sum(len(v) for k, v in diffs.items() if k != "合计点名条数")
    drift = []
    for key, want in WIRING_BUCKETS.items():
        if key == "合计点名条数":
            got, line = total_named, "recomputed"
        elif key in diffs:
            got, line = len(diffs[key]), "recomputed"
        else:
            got, line = wiring_number(wiring_md, key)
            if key == "服务端注册表（唯一单源）":
                got_measured = len(A)
            elif key == "终端私有命令":
                got_measured = len(B)
            else:
                got_measured = None
            if got_measured is not None:
                got = got_measured
        declared, mdline = wiring_number(wiring_md, key)
        if declared != got:
            drift.append("%s: WIRING=%s 重算=%s" % (key, declared, got))
        # 三向对齐：文档 == 重算 == 本文件里钉的定值。定值不读出来就是死格（p28b 之后
        # 我把它只当 key 列表用了半轮），钉着的用意是"改命令面必须是一次刻意的改动"：
        # 只改文档、或只改代码，都会在这里红。
        if want != got:
            drift.append("%s: 尺内定值=%s 重算=%s（改命令面要连这一格一起改）" % (key, want, got))
        members = sorted(diffs[key]) if key in diffs else []
        # 派生输出：WIRING.md 的 §3 只准照抄这一行（手敲的数没有尺会去读，永远不变红）。
        print("WIRING_EMIT|%s|%d|%s" % (key, got, " ".join(members)), flush=True)
        print("BUCKET|%-34s wiring=%s recomputed=%s members=%s"
              % (key, declared, got, " ".join(map(str, sorted(set())
                                 if key not in diffs else diffs[key]))[:200]), flush=True)
    chk("T2_wiring_md_numbers_equal_recomputation", not drift,
        "改了命令面（CommandCatalog / index.html / 任何一段 endpoints）而 WIRING.md §3 或本文件"
        "的定值没跟着重算 —— 文档漂或刻意改动没留下痕迹",
        "drift=%s" % (drift[:4] if drift else "无（9 格三向相同：文档==重算==定值）"))

    mem_drift = []
    for key in ("3.1 只在服务端注册表", "3.2 只在 TUI 私有", "3.4 web 广告了但没有分支接住",
                "3.5 web 有分支但没广告"):
        declared_members, _ = wiring_members(wiring_md, key)
        mine = sorted(set(diffs[key]))
        if declared_members != mine:
            mem_drift.append("%s: 文档=%s 重算=%s" % (key, declared_members, mine))
    chk("T3_wiring_md_member_lists_equal_recomputation", not mem_drift,
        "条数没变但成员换了（比如 /theme 被换成 /foo）—— 只比条数抓不到",
        "drift=%s" % (mem_drift[:3] if mem_drift else "无（4 桶成员逐条相同）"))

    lit, dispatch_lines = dispatch_literals(http_src)
    tsv_rows = routes_tsv(os.path.join(HERE, "ROUTES.tsv"))
    tsv_paths = sorted({r[1] for r in tsv_rows})
    chk("T4_routes_tsv_equals_dispatch_literals",
        tsv_paths == lit and len(tsv_rows) == ROUTES_ROWS and len(tsv_paths) == ROUTES_PATHS,
        "ROUTES.tsv 手改一个字、或 dispatch 加了路由没登记（M9 型）",
        "tsv_paths=%d dispatch_literals=%d rows=%d awk_slice_lines=%d diff=%s"
        % (len(tsv_paths), len(lit), len(tsv_rows), dispatch_lines,
           sorted(set(tsv_paths) ^ set(lit))))

    not_found = []
    for r in tsv_rows:
        method, path = r[0], r[1]
        if path == "/bot/chat/stream":
            continue                      # 会真跑一轮对话，B 组已经量过
        _raw2, hs2, st2, body2, _ = raw_http(port_s, method, path, hard_seconds=15)
        code = st2.split(" ")[1] if len(st2.split(" ")) > 1 else "?"
        if code == "404":
            not_found.append("%s %s" % (method, path))
    chk("T5_every_ledger_route_is_live_in_the_real_jvm", not not_found,
        "台账登记了、dispatch 里没有（M6 摘 404 门的另一半：门与账不一致）",
        "checked=%d 404 的登记路由=%s" % (len(tsv_rows) - 1, not_found[:6]))
    ghost = "/p28c-not-a-route-%d" % int(time.time() % 100000)
    _r3, hs3, st3, body3, _ = raw_http(port_s, "GET", ghost, hard_seconds=15)
    code3 = st3.split(" ")[1] if len(st3.split(" ")) > 1 else "?"
    chk("T6_unknown_path_is_404_with_ledger_error_shape",
        code3 == "404" and b"not found" in body3 and b'"ok":false' in body3,
        "M6：摘掉 routesOf(path).isEmpty() 那道门 ⇒ 未知路径不再 404（mvn 那把尺看不见，实测 SURVIVED）",
        "code=%s body=%r" % (code3, body3[:90]))
    post_only = [r[1] for r in tsv_rows if r[0] == "POST" and
                 not any(x[0] == "GET" and x[1] == r[1] for x in tsv_rows)]
    wrong_method = []
    opt_offenders = []
    for path in post_only:
        _r4, hs4, st4, _b4, _ = raw_http(port_s, "GET", path, hard_seconds=15)
        code4 = st4.split(" ")[1] if len(st4.split(" ")) > 1 else "?"
        allow = sorted(x.strip() for x in (hs4.get("allow") or "").split(",") if x.strip())
        declared = sorted({r[0] for r in tsv_rows if r[1] == path})
        # 服务端 ledgerMethods() 的构造是"登记方法 + 恒附 OPTIONS"（OPTIONS 在 404/405 那道门
        # 之前就回 204），所以判据是 allow == declared ∪ {OPTIONS} 逐字相等。
        # 写成"等于 declared"会把 9 条 POST-only 全判红（lead_r1 实测）；写成"是超集"就没牙。
        if not (code4 == "405" and allow == sorted(set(declared) | {"OPTIONS"})):
            wrong_method.append("%s code=%s allow=%r declared=%s" % (path, code4, allow, declared))
        # Allow 广告了 OPTIONS 就得真答它：摘掉 dispatch 开头那个 OPTIONS 分支 ⇒ 落进 405 ⇒ 这条红。
        _r4o, _hs4o, st4o, _b4o, _ = raw_http(port_s, "OPTIONS", path, hard_seconds=15)
        code4o = st4o.split(" ")[1] if len(st4o.split(" ")) > 1 else "?"
        if code4o != "204":
            opt_offenders.append("%s %s" % (path, code4o))
    chk("T7_wrong_method_is_405_with_ledger_allow_header",
        post_only and not wrong_method and not opt_offenders,
        "M7：摘掉 Allow 头（mvn 实测 SURVIVED）/ Allow 与台账不一致 / 广告 OPTIONS 却不兑现",
        "post_only=%d offenders=%s options_未兑现=%s"
        % (len(post_only), wrong_method[:3], opt_offenders[:3]))

    # ================= D 组：杠④ 与卫生 =================
    srv.terminate()
    try:
        srv.wait(timeout=10)
    except subprocess.TimeoutExpired:
        srv.kill()
        srv.wait()
    srv_log.close()
    _l2, names2, st2_map = bar4("t2_after_e2e")
    _r5, out_kill = sh(["zsh", "-c", "ps -p %d -o pid= || true" % srv.pid], timeout=30)
    auths = list(fake.auth_seen)
    fake.shutdown()

    # 原先这里是 `names2, st2_map, _ = bar4(...)[1], None, None` + `st2_map_ok()` 恒 return True：
    # md5 那半边**从未参与判定**（细节行里印的就是 md5=None），一条把真 key 换掉的写盘也拦不住。
    # 现在三个时点的 (目录名, config/state 两个 md5) 都进等式，且不许 MISSING。
    # 三个时点彼此相等 **且** 等于工单钉的那两个常量 —— BAR4_CFG/BAR4_DB 在本文件里定义了
    # 却一处都没读（lead_r1 时是死常量），只比"前后一致"的话，一跑从一开始就读到坏 profile
    # 也会自洽地绿。
    chk("U1_bar4_three_timepoints_unchanged",
        home_names0 == names1 == names2 and home_st0 == st1_map == st2_map
        and st2_map.get("config.properties") == BAR4_CFG
        and st2_map.get("state.db") == BAR4_DB and len(names2) == 8,
        "任何一处往真 profile 写东西（比如把 ~/.zbot 当缺省 configDir）",
        "names %d->%d->%d md5 t0=%s t2=%s 期望 cfg=%s db=%s"
        % (len(home_names0), len(names1), len(names2), home_st0, st2_map, BAR4_CFG, BAR4_DB))
    chk("U2_real_key_never_read_and_stub_only_on_the_wire",
        all(STUB_KEY in a or a == "<none>" for a in auths) and auths,
        "把真 key 打进请求（Authorization 里出现非 stub 值）",
        "auth_headers=%d unique=%s" % (len(auths), sorted(set(auths))[:1]))
    chk("U3_serve_process_reaped", out_kill.strip() == "",
        "子进程没被回收（量具自己漏进程）",
        "ps_says=%r" % out_kill.strip()[:60])
    # 最后一支原先写成 `isdir(CFG/sessions) != isdir(BAR4_HOME)`：两个操作数都是 True，
    # 等式恒假 ⇒ 这条守卫在"用没用真 profile"上从来没有判据（lead_r1 实测 FAIL，
    # 而它红的原因和它想防的事毫无关系）。要问的是"这两个目录不是同一个、且真在临时根下"。
    real_cfg, real_home = os.path.realpath(CFG), os.path.realpath(BAR4_HOME)
    chk("U4_temp_profile_used_not_real_home",
        os.path.isfile(os.path.join(CFG, "config.properties"))
        and STUB_KEY in read(os.path.join(CFG, "config.properties"))
        and os.path.isdir(os.path.join(CFG, "sessions"))
        and real_cfg != real_home
        and not real_cfg.startswith(real_home + os.sep),
        "E2E 用了真 profile（杠④ 立刻会被写花）",
        "cfg=%s real=%s files=%s" % (CFG, real_cfg, sorted(os.listdir(CFG))[:8]))

    fails = [c for c in CHECKS if not c[1]]
    print("E2E|run=%s checks=%d pass=%d fail=%d llm_hits=%d serve_port=%d result=%s"
          % (RUN_TAG, len(CHECKS), len(CHECKS) - len(fails), len(fails), len(fake.hits),
             port_s, "OK" if not fails else "HAS_FAILURE"), flush=True)
    for name, ok, detail in fails:
        print("FAILED_CHECK|%s|%s" % (name, detail), flush=True)
    print("ARTIFACTS|%s" % sorted(os.listdir(OUT)), flush=True)
    sys.exit(0 if not fails else 1)


def addr_of(line):
    """取 lsof 那一行的地址字段（倒数第二个 token，最后一个就是 `(LISTEN)`）。"""
    toks = line.split()
    return toks[-2] if len(toks) >= 2 else ""


def unescape(s):
    return s.replace("\\n", "\n").replace("\\r", "\r")


def od_c(data):
    out = []
    for i in range(0, min(len(data), 512), 16):
        bs = data[i:i + 16]
        chars = " ".join(o for b in bs for o in [
            "\\n" if b == 10 else "\\t" if b == 9 else "\\r" if b == 13 else
            chr(b).decode("latin-1") if isinstance(chr(b), bytes) else chr(b)
            if 32 <= b < 127 else "%03o" % b])
        out.append("%07o  %-48s |%s|" % (i, "", chars))
    return "\n".join(out)


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
