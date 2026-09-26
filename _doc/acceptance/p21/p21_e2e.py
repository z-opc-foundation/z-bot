#!/usr/bin/env python3
"""P21 杠③ —— 真进程 E2E（z-bot ↔ 官方 MCP Python SDK，双向）。

覆盖工单 §11 的 (a)–(e)：

  (a) 官方 stdio server（`p21_ref_mcp_server.py --transport stdio`）
  (b) 官方 StreamableHTTP/SSE server（uvicorn + starlette，真 http）
  (c) **外部 actor** 改工具表 ⇒ 注册表真注销（外加 `p21_pipe_relay.py` 作 client 之外的观察点）
  (d) 反向 `z-bot mcp serve`：官方 SDK 的**真 client** 连 z-bot
  (e) 父死 watchdog：`kill -9` z-bot JVM ⇒ MCP 子进程不成孤儿（含"不理 stdin 的孩子"对照）

量具口径（工单"量具纪律"逐条对上）：

* z-bot 侧的行为一律由 `McpE2eDriver` 这个真 JVM 走**生产装配入口**
  （`McpClientFactory.create(BotConfig.McpServerEntry)`）产出 `FACT` 行，
  python 只做编排 + 独立观察点（server 自己的 `P21_SERVER_LOG`、中继日志、sqlite 回读、`ps`）。
* 空输入必 FATAL：某一节一条读数都没拿到 ⇒ 判红，不判"没东西可错"。
* 负向断言必带猎物："不含 X" 旁边同一条里钉 "X 真进得来"。
* 计数与内容进同一条断言（条数对但名字错 = 红）。
* 日志不当扳机：等的是因果那一边（注册表名字集变了 / 子进程真没了 / 文件副作用出现）。
* 收尾有上限：每个子进程都带 timeout，超时是 SIGKILL + FATAL。
* 凭据红线：全程 `--config-dir` 指临时根，key 只用 `stub-key-not-real`，
  绝不读 `~/.zbot/config.properties` 的值（只在 杠④ 里量 md5 前 8 位与长度）。

用法：
    python3 p21_e2e.py --label R1                # 整跑一遍 (a)-(e)
    python3 p21_e2e.py --label M --only c,e      # 变异脚本按节复核用
退出码：0 = 全绿；非 0 = 有 FAIL/FATAL（读数一律在 stdout，同时也落盘）。
"""

import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent            # _doc/acceptance/p21
REPO = HERE.parents[2]                            # 仓库根
CACHE = Path.home() / ".cache" / "zbot-p21"
REF_SERVER = HERE / "p21_ref_mcp_server.py"
RELAY = HERE / "p21_pipe_relay.py"
STUB_KEY = "stub-key-not-real"
SECTIONS = ("a", "b", "c", "d", "e")

DRIVER = "com.zifang.z.bot.mcp.McpE2eDriver"
ZBOT_MAIN = "com.zifang.z.bot.ZBot"
PROBE = "com.zifang.z.bot.mcp.McpWatchdogProbe"

EXPECTED_STDIO_TOOLS = ["mcp-e2e-p21_alpha", "mcp-e2e-p21_apply_table", "mcp-e2e-p21_beta",
                        "mcp-e2e-p21_echo", "mcp-e2e-p21_stall"]


class Gauge:
    """一条读数一行账；分节计数，空节 FATAL。"""

    def __init__(self, out):
        self.out = out
        self.rows = []
        self.section = "-"
        self.section_counts = {}
        self.failed = []

    def start(self, section):
        self.section = section
        self.section_counts.setdefault(section, 0)

    def chk(self, name, ok, reading):
        self.section_counts[self.section] = self.section_counts.get(self.section, 0) + 1
        tag = "PASS" if ok else "FAIL"
        if not ok:
            self.failed.append("%s/%s" % (self.section, name))
        line = "[%s] %s/%s :: %s" % (tag, self.section, name, reading)
        print(line, flush=True)
        self.out.write(line + "\n")
        self.out.flush()
        return bool(ok)

    def need(self, facts, key, section=None):
        """取一条 FACT；缺了就是 FATAL（空输入不许当满分）。"""
        if key not in facts:
            self.chk("fact:" + key, False, "驱动没交出这条读数（keys=%s）" % sorted(facts))
            raise MissingFact(key)
        return facts[key]


class MissingFact(Exception):
    pass


def run(argv, env=None, timeout=180, cwd=None, logfile=None):
    """跑一个子进程，返回 (rc, stdout, stderr)。超时 = SIGKILL + rc=-9。"""
    e = dict(os.environ)
    if env:
        e.update(env)
    log = None
    if logfile:
        log = open(logfile, "w", encoding="utf-8")
    p = subprocess.Popen(argv, env=e, cwd=cwd, stdin=subprocess.DEVNULL,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         text=True, encoding="utf-8", errors="replace")
    try:
        out, err = p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        out, err = p.communicate()
        if log:
            log.write("=== TIMEOUT after %ss ===\n" % timeout)
            log.write("STDOUT\n" + (out or "") + "\nSTDERR\n" + (err or ""))
            log.close()
        raise Timeout("子进程超时被 SIGKILL: %s" % " ".join(argv[:6]))
    if log:
        log.write("STDOUT\n" + (out or "") + "\nSTDERR\n" + (err or ""))
        log.close()
    return p.returncode, out or "", err or ""


class Timeout(Exception):
    pass


def facts_of(stdout_text):
    f = {}
    for line in stdout_text.splitlines():
        if line.startswith("FACT\t"):
            parts = line.split("\t", 2)
            if len(parts) == 3:
                f[parts[1]] = parts[2]
            else:
                f[parts[1]] = ""
    return f


# ----------------------------------------------------------------- 布尔 FACT 口径
# 缺陷 B（工单 §2）：java 侧 `fact(k, Boolean.valueOf(...))` 经 `String.valueOf` 打出来是
# **小写** `true`/`false`，而本脚本原来在 :262/:263/:296 比的是 Python 的 `str(True)`
# 口径 `"True"`/`"False"` ⇒ 两条断言假红。这里定一个**唯一**口径，别处不再各写各的。
TRUE_WORDS = ("true", "1", "yes")
FALSE_WORDS = ("false", "0", "no")


def flag(f, key):
    """取一条布尔 FACT 并归一化成 Python bool。

    口径纪律（三条都不许松）：
      * 键缺失 ⇒ MissingFact（"读不到"永远不等于"读到 false"）
      * 值认不出来（空串、`True`、乱码）⇒ 直接抛，**不**当 False
      * 只接受 true/1/yes 与 false/0/no（大小写无关）
    """
    if key not in f:
        raise MissingFact(key)
    s = (f[key] or "").strip().lower()
    if s in TRUE_WORDS:
        return True
    if s in FALSE_WORDS:
        return False
    raise ValueError("布尔 FACT %r 的值 %r 无法归一化（口径只认 %s / %s）"
                     % (key, f[key], TRUE_WORDS, FALSE_WORDS))


class Recorder:
    """同一个断言函数的**可重放**记账口：只收结果不落总账。

    用来做"这条断言到底杀不杀得死某种坏法"的自证 —— 拿真读数当基线，
    逐个键注入坏值重放，看它是否真的变红。
    """

    def __init__(self):
        self.rows = []

    def start(self, section):
        pass

    def chk(self, name, ok, reading):
        self.rows.append((name, bool(ok), reading))
        return bool(ok)

    def failed(self):
        return [n for n, ok, _ in self.rows if not ok]

    def passed(self):
        return [n for n, ok, _ in self.rows if ok]


def need_fact(f, key):
    if key not in f:
        raise MissingFact(key)
    return f[key]


def java_bin():
    home = os.environ.get("JAVA_HOME")
    cand = Path(home) / "bin" / "java" if home else None
    if cand and cand.is_file():
        return str(cand)
    return "java"


def classpath(workdir):
    """离线取依赖树：优先读缓存的 cp.txt，没有就现算一次。"""
    cached = CACHE / "cp.txt"
    cp = None
    if cached.is_file() and cached.stat().st_size > 100:
        cp = cached.read_text(encoding="utf-8").strip()
    if cp is None:
        CACHE.mkdir(parents=True, exist_ok=True)
        rc, out, err = run(["mvn", "-o", "-q", "-pl", "z-bot-core", "dependency:build-classpath",
                            "-Dmdep.outputFile=" + str(cached), "-DincludeScope=test"],
                           cwd=str(REPO), timeout=420)
        if not cached.is_file():
            raise SystemExit("拿不到 classpath（mvn rc=%s）:\n%s\n%s" % (rc, out[-2000:], err[-2000:]))
        cp = cached.read_text(encoding="utf-8").strip()
    for d in ("z-bot-core/target/classes", "z-bot-core/target/test-classes"):
        if not (REPO / d).is_dir():
            raise SystemExit("没有 %s —— 先跑 mvn -o test-compile" % (REPO / d))
    return "%s:%s:%s" % (REPO / "z-bot-core" / "target" / "classes",
                         REPO / "z-bot-core" / "target" / "test-classes", cp)


def driver(cp, mode, workdir, args, env=None, timeout=180, tag="driver"):
    full = [java_bin(), "-cp", cp, DRIVER, mode]
    for k, v in args:
        full += ["--" + k, v]
    log = workdir / (tag + ".log")
    rc, out, err = run(full, env=env, timeout=timeout, logfile=workdir / (tag + ".out"))
    f = facts_of(out)
    if not f:
        raise RuntimeError("%s 一条 FACT 都没有（rc=%s）\nstderr 尾巴:\n%s"
                           % (tag, rc, err[-1500:]))
    if "error" in f or rc != 0:
        raise RuntimeError("%s 失败 rc=%s error=%s\nstderr 尾巴:\n%s"
                           % (tag, rc, f.get("error", ""), err[-1500:]))
    return rc, f, err


def child_pids(ppid):
    """直接子进程 pid（只认 pid/ppid，不用命令行 pattern —— 同机有别人的 mvn/python）。"""
    rc, out, _ = run(["/bin/sh", "-c", "ps -eo pid=,ppid= | awk -v p=%d '$2==p{print $1}'" % ppid])
    return [int(x) for x in out.split()]


def alive(pid):
    rc, _, _ = run(["/bin/sh", "-c", "ps -o pid= -p %d" % pid])
    return rc == 0


def ppid_of(pid):
    _, out, _ = run(["/bin/sh", "-c", "ps -o ppid= -p %d" % pid])
    return out.strip()


def wait_until(fn, budget, every=0.1):
    t0 = time.time()
    while time.time() - t0 < budget:
        if fn():
            return True
        time.sleep(every)
    return False


def fresh_workdir(workroot, label, auto):
    """缺陷 C 的收口：现场目录**必须**每跑一个新路径。

    原来的 `default=time.strftime("R%%H%%M%%S")` 里那两个 `%%` 让 strftime 原样吐出
    `%H%M%S` ⇒ label 是个字面常量 ⇒ 所有跑都写进同一个目录，
    而 `shutil.rmtree` 又把上一跑的现场整个抹掉：
      * 杠③ 要"≥3 次整跑"，可 3 次只留得下最后一次的现场 ⇒ 读数无从对账；
      * 同机并发/复跑时上一跑的 `a-server-notes.txt`、`*-driver.out` 会被下一读回当真凭据。
    所以：自动 label 带上 PID 保证唯一；显式 label 允许覆盖（`--label R1` 是有意复用一个名字，
    但旧目录不存在时才算"新现场"，存在则先改名留档而不是删掉 —— 留档比删掉更符合证据要求）。
    """
    root = Path(workroot)
    root.mkdir(parents=True, exist_ok=True)
    wd = root / label
    if wd.exists():
        if not auto:
            stale = root / (label + ".stale-" + time.strftime("%Y%m%d-%H%M%S") + "-p%d" % os.getpid())
            wd.rename(stale)
            print("NOTE: 旧现场 %s 已改名为 %s（不删，留作对账）" % (wd, stale.name), flush=True)
        else:
            raise SystemExit("FATAL: 自动 label 撞车了（%s 已存在）—— 现场目录唯一性坏了" % wd)
    wd.mkdir(parents=True)
    return wd


def prove_fresh_workdir(g, workroot, label):
    """自证：连开两次现场，两次的**路径必须不同**，且第二跑**看不见**第一跑的文件。"""
    probe_root = Path(workroot) / "_fresh_probe"
    probe_root.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    seen = []
    for i in range(2):
        time.sleep(1.05)          # 跨过一秒，让 %H%M%S 真的变（不然就是在测 PID 那一半）
        w = fresh_workdir(probe_root, "PROBE-%d-%s-p%d" % (i, time.strftime("%H%M%S"), os.getpid()), True)
        seen.append(w)
    first_marker = seen[0] / "run1-only-marker.txt"
    first_marker.write_text("run1\n", encoding="utf-8")
    cross = list(seen[1].glob("*"))
    g.chk("fresh_workdir_two_runs_are_distinct_paths",
          seen[0] != seen[1] and seen[0].is_dir() and seen[1].is_dir(),
          "run1=%s run2=%s" % (seen[0].name, seen[1].name))
    g.chk("fresh_workdir_second_run_cannot_see_first_run_files",
          not cross and not (seen[1] / "run1-only-marker.txt").exists()
          and first_marker.is_file(),
          "第二跑目录里的文件=%s 第一跑的哨兵仍在=%s" % (cross, first_marker.is_file()))
    g.chk("fresh_workdir_default_label_is_formatted",
          "%H" not in label and "%M" not in label and "%S" not in label and label.startswith("R"),
          "本次 label=%s" % label)
    shutil.rmtree(probe_root, ignore_errors=True)


# --------------------------------------------------------------------- 起服务

def start_ref_http(workdir, log_name="ref-http", extra_argv=None):
    """官方 StreamableHTTP server；端口由 uvicorn bind(0)，从 port-file 回读。"""
    port_file = workdir / (log_name + ".port")
    log = workdir / (log_name + ".txt")
    if port_file.exists():
        port_file.unlink()
    env = {"P21_SERVER_LOG": str(log)}
    argv = [sys.executable, "-u", str(REF_SERVER), "--transport", "http",
            "--port", "0", "--port-file", str(port_file)]
    if extra_argv:
        argv += list(extra_argv)
    p = subprocess.Popen(argv,
                         env=dict(os.environ, **env),
                         stdout=open(workdir / (log_name + ".out"), "w"),
                         stderr=subprocess.STDOUT)
    url = None
    deadline = time.time() + 30
    while time.time() < deadline:
        if port_file.is_file() and port_file.stat().st_size > 0:
            s = port_file.read_text(encoding="utf-8").strip()
            if s.startswith("http://127.0.0.1:"):
                url = s
                break
        if p.poll() is not None:
            break
        time.sleep(0.05)
    if not url:
        p.kill()
        raise RuntimeError("官方 http server 起不来，exit=%s 现场 %s / %s（尾巴: %s）"
                           % (p.returncode, log, workdir / (log_name + ".out"),
                              tail_of(workdir / (log_name + ".out"))))
    return p, url, log


def temp_config_root(workdir, extra_lines=None):
    """临时 ZBOT_HOME：里面只有 stub key，真 key 永不被读。"""
    cfg = workdir / "zbot-home"
    cfg.mkdir(parents=True, exist_ok=True)
    lines = ["model=minimax-minimax-m2", "minimax.api.key=" + STUB_KEY]
    if extra_lines:
        lines += extra_lines
    (cfg / "config.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return cfg


# --------------------------------------------------------------------- (a) stdio

def a_handshake_and_caps(g, f):
    """握手三元（协议版本 / 对端是否真广告 listChanged / 通知通道是否可用）。

    拆成独立函数只为缺陷 B 的自证：同一份代码要能在"真读数"与"注入坏值"两种输入上重放。
    """
    g.chk("handshake_version_and_peer_caps",
          need_fact(f, "server_protocol_version") == "2025-06-18"
          and flag(f, "peer_advertises_list_changed") is True
          and flag(f, "notification_capable") is True,
          "server_protocol=%s peer_advertises_list_changed=%s notification_capable=%s server_info=%s"
          % (f.get("server_protocol_version"), f.get("peer_advertises_list_changed"),
             f.get("notification_capable"), f.get("server_info")))


def a_disconnect_is_final(g, f):
    """断开必须是真的：transport 关了 **且** 注册表被清空（两条同时成立才算）。"""
    g.chk("disconnect_is_final",
          flag(f, "open_after_disconnect") is False
          and need_fact(f, "toolkit_size_after_unregister") == "0",
          "open_after_disconnect=%s toolkit_size_after_unregister=%s"
          % (f.get("open_after_disconnect"), f.get("toolkit_size_after_unregister")))


def boolflag_selfproof(g, f):
    """缺陷 B 的收口证据：上面两条断言在真读数上过，在两种坏法下必须红。

    基线**取本次真跑**的 FACT（不另造常量），再逐键注入坏值重放，
    所以"改完仍杀得死'真断开后又打开'与'对端根本不推 listChanged'"是可复算的。
    """
    r0 = Recorder()
    a_handshake_and_caps(r0, f)
    a_disconnect_is_final(r0, f)
    g.chk("gauge/bool_baseline_passes_on_real_readings",
          len(r0.rows) == 2 and not r0.failed(),
          "真读数下两条都过=%s（红=%s）" % (r0.passed(), r0.failed()))
    mutants = [
        # (名字, 键, 注入值, 该杀的坏法, 受影响的断言)
        ("peer_never_advertises_listChanged", "peer_advertises_list_changed", "false",
         "对端根本不推 listChanged", "handshake_version_and_peer_caps"),
        ("reopen_after_disconnect", "open_after_disconnect", "true",
         "真断开后又打开", "disconnect_is_final"),
        ("toolkit_not_emptied", "toolkit_size_after_unregister", "5",
         "断开没真 deregister", "disconnect_is_final"),
        ("unparseable_bool_value", "peer_advertises_list_changed", "TRUEISH",
         "读不出的布尔值必须抛错，不许悄悄当 false", None),
        ("missing_fact_key", "notification_capable", None,
         "读不到不许当满分", None),
    ]
    for name, key, value, why, target in mutants:
        fm = dict(f)
        if value is None:
            fm.pop(key, None)
        else:
            fm[key] = value
        r = Recorder()
        raised = ""
        try:
            a_handshake_and_caps(r, fm)
            a_disconnect_is_final(r, fm)
        except (MissingFact, ValueError) as e:
            raised = "%s: %s" % (type(e).__name__, e)
        killed = bool(r.failed()) or raised != ""
        g.chk("gauge_kills_" + name, killed,
              "注入 %s=%r（%s）⇒ 红=%s 异常=%s" % (key, value, why, r.failed(), raised[:120]))


def section_a(cp, g, wd):
    srv_log = wd / "a-server-notes.txt"
    env = {"P21_SERVER_LOG": str(srv_log)}
    rc, f, _ = driver(cp, "stdio", wd,
                      [("python", sys.executable), ("server", str(REF_SERVER))],
                      env=env, timeout=120, tag="a-driver")
    (wd / "a-driver-facts.json").write_text(json.dumps(f, ensure_ascii=False, indent=1,
                                                       sort_keys=True), encoding="utf-8")
    g.chk("transport_is_zbot_side_not_kernel",
          f.get("transport_class") == "com.zifang.z.bot.mcp.ZBotStdioMcpTransport",
          "transport_class=%s client=%s" % (f.get("transport_class"), f.get("client_class")))
    a_handshake_and_caps(g, f)
    g.chk("client_self_version_is_not_the_hardcoded_kernel_one",
          f.get("self_reported_client_version") not in ("", "null", "0.2.0")
          or "dev" in f.get("self_reported_client_version", ""),
          "self_reported_client_version=%s" % f.get("self_reported_client_version"))
    names = [x for x in f.get("toolset_names", "").split(",") if x]
    g.chk("tool_table_is_exactly_5_from_the_real_server",
          names == EXPECTED_STDIO_TOOLS and f.get("registered") == "5"
          and f.get("toolkit_size") == "5",
          "registered=%s toolkit_size=%s names=%s" % (f.get("registered"),
                                                     f.get("toolkit_size"), names))
    g.chk("three_tools_really_execute",
          "echo:p21-e2e-ping" in f.get("call_p21_echo", "")
          and "beta:q-42" in f.get("call_p21_beta", "")
          and "alpha-ok" in f.get("call_p21_alpha", ""),
          "echo=%s beta=%s alpha=%s" % (f.get("call_p21_echo"), f.get("call_p21_beta"),
                                        f.get("call_p21_alpha")))
    # 外部观察点：server 自己的记事本（不是 z-bot 的日志）
    notes = srv_log.read_text(encoding="utf-8") if srv_log.is_file() else ""
    heard = [json.loads(x) for x in notes.splitlines() if x.strip().startswith("{")]
    called = sorted(set(e.get("tool") for e in heard if e.get("event") == "call"))
    g.chk("server_side_notes_agree_on_the_three_calls",
          len(heard) >= 4 and called == ["p21_alpha", "p21_beta", "p21_echo"],
          "server_notes=%d tools_called_per_server=%s" % (len(heard), called))
    g.chk("wrong_type_and_unknown_tool_come_back_as_errors_not_hangs",
          "isError=true" in f.get("call_p21_echo_wrong_type", "").lower()
          or "Error" in f.get("call_p21_echo_wrong_type", ""),
          "wrong_type=%s || unknown_tool=%s" % (f.get("call_p21_echo_wrong_type"),
                                                f.get("call_unknown_tool")))
    a_disconnect_is_final(g, f)
    boolflag_selfproof(g, f)
    return f


# --------------------------------------------------------------------- (b) http

def section_b(cp, g, wd):
    proc, url, srv_log = start_ref_http(wd)
    try:
        hdr = "Authorization=Bearer " + STUB_KEY
        rc, f, _ = driver(cp, "http", wd,
                          [("url", url), ("header", hdr)], timeout=120, tag="b-driver")
        g.chk("http_transport_class_and_url",
              f.get("transport_class") == "com.zifang.z.bot.mcp.StreamableHttpMcpTransport",
              "transport_class=%s url=%s" % (f.get("transport_class"), url))
        g.chk("session_id_from_initialize_response_header",
              flag(f, "session_id_present") is True and f.get("negotiated_protocol_version") == "2025-06-18",
              "session_id_present=%s prefix=%s negotiated=%s peer_advertises_list_changed=%s"
              % (f.get("session_id_present"), f.get("session_id_prefix"),
                 f.get("negotiated_protocol_version"), f.get("peer_advertises_list_changed")))
        shapes_ok = int(f.get("json_shape_responses", "-1")) + int(f.get("sse_shape_responses", "-1"))
        shapes_after = (int(f.get("json_shape_after", "-1")) + int(f.get("sse_shape_after", "-1")))
        g.chk("response_shape_counted_and_nonzero",
              shapes_ok >= 1 and shapes_after > shapes_ok,
              "json=%s sse=%s（registerAll 期间）→ json_after=%s sse_after=%s（三次 call 之后）"
              % (f.get("json_shape_responses"), f.get("sse_shape_responses"),
                 f.get("json_shape_after"), f.get("sse_shape_after")))
        # 会话头必须真的每发都挂上：wire 取证逐 POST 行数 + 前缀与 session_id_prefix 对账
        posts = [x for x in f.get("wire_log", "").split(",") if x.startswith("POST ")]
        carried = [x for x in posts if "mcp-session-id=" in x and "mcp-session-id=<none>" not in x]
        g.chk("wire_log_shows_session_header_carried_on_posts",
              len(posts) >= 5 and len(carried) >= 2
              and ("mcp-session-id=" + f.get("session_id_prefix", "\x00")) in f.get("wire_log", ""),
              "POST 取证 %d 行，其中带会话头 %d 行，前缀=%s 首行=%s"
              % (len(posts), len(carried), f.get("session_id_prefix"),
                 posts[0][:120] if posts else "无"))
        names = [x for x in f.get("toolset_names", "").split(",") if x]
        g.chk("http_tool_table_matches_stdio",
              names == EXPECTED_STDIO_TOOLS and f.get("registered") == "5",
              "registered=%s names=%s" % (f.get("registered"), names))
        g.chk("http_calls_execute",
              "echo:p21-e2e-ping" in f.get("call_p21_echo", "")
              and "alpha-ok" in f.get("call_p21_alpha", ""),
              "echo=%s alpha=%s" % (f.get("call_p21_echo"), f.get("call_p21_alpha")))
        # 凭据红线在真进程里的形状：stub 真的进了产物（prey），产物里是打码位（negative）
        rendered = f.get("entry_render", "") + " " + f.get("entry_safe_map", "")
        g.chk("stub_credential_enters_but_only_masked_renders",
              STUB_KEY not in rendered
              and "Be********al" in f.get("entry_render", "")
              and "authorization" in f.get("entry_safe_map", "").lower(),
              "entry_render=%s || safe_map=%s" % (f.get("entry_render"), f.get("entry_safe_map")))
        g.chk("http_close_is_final",
              flag(f, "open_after_disconnect") is False and f.get("toolkit_size_after_unregister") == "0",
              "open_after_disconnect=%s toolkit_size_after_unregister=%s"
              % (f.get("open_after_disconnect"), f.get("toolkit_size_after_unregister")))
        # 独立观察点：python 自己直接 POST，确认会话头是真从 server 来的而不是我这侧编的
        ext = probe_session_header_outside_jvm(url)
        g.chk("independent_http_probe_sees_same_session_header",
              ext.get("status") == 200 and ext.get("header") not in (None, "")
              and ext.get("advertises_list_changed") is True,
              "python_probe=%s" % json.dumps(ext, ensure_ascii=False))
        # 上条要不再是空断言：会话头必须**承重** —— 假头与不带头都得被真 server 拒
        good = post_tools_list(url, ext.get("header"), "good")
        bogus = post_tools_list(url, "deadbeef0123456", "bogus")
        nope = post_tools_list(url, None, "none")
        g.chk("real_server_enforces_the_session_header",
              good.get("status") == 200 and good.get("n_tools") == 5
              and bogus.get("status") in (400, 404) and nope.get("status") in (400, 404),
              "真会话=%s(工具 %s) 假会话=%s(%s) 不带头=%s(%s)"
              % (good.get("status"), good.get("n_tools"), bogus.get("status"),
                 str(bogus.get("body", ""))[:70], nope.get("status"), str(nope.get("body", ""))[:70]))
        # 缺陷 A 的对照臂：摘掉 _advertise_list_changed ⇒ 能力位必须翻成 false
        http_list_changed_negative_control(cp, g, wd, ext.get("advertises_list_changed"))
        g.chk("accept_header_on_notifications_post_is_both_media_types",
              all("-> 202" in x or "-> 200" in x for x in
                  [w for w in f.get("wire_log", "").split(",") if "notifications/" in w])
              and any("notifications/initialized -> 202" in w
                      for w in f.get("wire_log", "").split(",")),
              "notifications 那几发的状态码=%s（406 就是 Accept 少了一个媒体类型）"
              % [w.split(" -> ")[-1] for w in f.get("wire_log", "").split(",")
                 if "notifications/" in w])
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
    return f


def tail_of(path, n=600):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")[-n:].replace("\n", " ⏎ ")
    except Exception as e:
        return "读不到 %s: %s" % (path, e)


def initialize_probe(url):
    """python 侧**独立**走一遍 initialize：不借 z-bot 的解析器，也不借它的口。"""
    import urllib.request  # noqa: F401
    body = json.dumps({"jsonrpc": "2.0", "id": 777, "method": "initialize",
                       "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                                  "clientInfo": {"name": "p21-python-probe", "version": "0"}}}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(req, timeout=15) as r:
        raw = r.read(8192).decode("utf-8", "replace")
    payload = raw
    if "data:" in raw:                       # SSE 形状：取第一个 data: 帧
        payload = [x[5:].strip() for x in raw.splitlines() if x.startswith("data:")][0]
    doc = json.loads(payload)
    result = doc.get("result") or {}
    tools_cap = (result.get("capabilities") or {}).get("tools") or {}
    out = {"status": r.status, "header": r.headers.get("mcp-session-id"),
           "content_type": r.headers.get("content-type"),
           "protocolVersion": result.get("protocolVersion"),
           "advertises_list_changed": tools_cap.get("listChanged"),
           "tools_cap_raw": tools_cap,
           "body_head": raw[:120].replace("\n", "\\n")}
    # 规范的握手还差一步 notifications/initialized —— 不发的话后面每一发都不算
    # "一个真客户端的会话"，拿它去试会话头是否承重会误判（实测：无这一步时好会话那发拿不到响应）
    _post_raw(url, {"jsonrpc": "2.0", "method": "notifications/initialized"}, out["header"], want_body=False)
    return out


def _post_raw(url, doc, sid, want_body=True):
    import urllib.request
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream",
               "mcp-protocol-version": "2025-06-18"}
    if sid is not None:
        headers["mcp-session-id"] = sid
    req = urllib.request.Request(url, data=json.dumps(doc).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return {"status": r.status, "sid": r.headers.get("mcp-session-id"),
                    "body": r.read(65536).decode("utf-8", "replace") if want_body else ""}
    except urllib.error.HTTPError as e:
        return {"status": e.code, "sid": None,
                "body": e.read(400).decode("utf-8", "replace")}
    except Exception as e:
        return {"status": "EXC:" + type(e).__name__, "sid": None, "body": str(e)[:200]}


def probe_session_header_outside_jvm(url):
    d = initialize_probe(url)
    return {"status": d["status"], "header": d["header"], "content_type": d["content_type"],
            "advertises_list_changed": d["advertises_list_changed"],
            "body_head": d["body_head"]}


def post_tools_list(url, session_id, tag):
    """带/不带/带假会话头各打一发 tools/list：看**真 server** 认不认这个头。"""
    r = _post_raw(url, {"jsonrpc": "2.0", "id": 778, "method": "tools/list", "params": {}}, session_id)
    out = {"tag": tag, "status": r["status"], "body": r["body"][:200]}
    if r["status"] == 200:
        try:
            out["n_tools"] = len((json.loads(_first_frame(r["body"])).get("result") or {}).get("tools", []))
        except Exception as e:
            out["n_tools"] = "解析失败 %s" % e
    return out


def _first_frame(raw):
    for line in raw.splitlines():
        if line.startswith("data:"):
            return line[5:].strip()
    return raw


def http_list_changed_negative_control(cp, g, wd, patched_reading=None):
    """缺陷 A 的对照臂：同一个 server 文件，只差 `--no-list-changed`。

    三个观察点必须一起成立，否则这条能力位是空断言：
      1. 真 server 自己（python 裸 initialize）报的 listChanged 翻了；
      2. **z-bot 的解析器**（走生产 transport）读到的也翻了；
      3. 这台"摘了广告"的 server 仍是活的（工具表照样 5 条）——
         否则"读到 false"只是因为 server 根本没起来。
    """
    proc, url, _ = start_ref_http(wd, log_name="b-negref", extra_argv=["--no-list-changed"])
    try:
        py = initialize_probe(url)
        rc, f, _ = driver(cp, "http", wd, [("url", url)], timeout=120, tag="b-neg-driver")
        names = [x for x in f.get("toolset_names", "").split(",") if x]
        g.chk("negative_control_server_still_serves_five_tools",
              py["status"] == 200 and names == EXPECTED_STDIO_TOOLS and f.get("registered") == "5",
              "摘掉广告后 server 仍活着：registered=%s names=%s" % (f.get("registered"), names))
        g.chk("advertise_patch_flips_python_observed_cap",
              py["advertises_list_changed"] is False and patched_reading is True,
              "python 裸 initialize 看到 tools.cap=%s（打了补丁的那台 listChanged=%s）"
              % (json.dumps(py["tools_cap_raw"], ensure_ascii=False), patched_reading))
        g.chk("advertise_patch_flips_zbot_observed_cap",
              flag(f, "peer_advertises_list_changed") is False
              and flag(f, "notification_capable") is True,
              "z-bot 读到的 peer_advertises_list_changed=%s（notification_capable=%s 是我方通道状态，不随对端广告变）"
              % (f.get("peer_advertises_list_changed"), f.get("notification_capable")))
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()


# --------------------------------------------------------------------- (c) 换血

def write_control(path, add, drop):
    path.write_text(json.dumps({"add": add, "drop": drop}, ensure_ascii=False), encoding="utf-8")


def check_swap(g, f, tag, relay_log=None):
    base = [x for x in f.get("snapshot_base_names", "").split(",") if x]
    one = [x for x in f.get("snapshot_after1_names", "").split(",") if x]
    two = [x for x in f.get("snapshot_after2_names", "").split(",") if x]
    g.chk(tag + "_table_is_not_empty_first",
          base == EXPECTED_STDIO_TOOLS and f.get("snapshot_base_size") == "5",
          "base_size=%s base=%s" % (f.get("snapshot_base_size"), base))
    want1 = [x for x in EXPECTED_STDIO_TOOLS if x != "mcp-e2e-p21_beta"] + ["mcp-e2e-p21_dyn_a"]
    g.chk(tag + "_drop_is_real_deregistration_not_hidden",
          sorted(one) == sorted(want1) and "mcp-e2e-p21_beta" not in one
          and f.get("snapshot_after1_size") == "5",
          "after1_size=%s after1=%s（一增一减：条数不变，只有名字集能判）"
          % (f.get("snapshot_after1_size"), one))
    want2 = [x for x in want1 if x not in ("mcp-e2e-p21_dyn_a", "mcp-e2e-p21_stall")]
    g.chk(tag + "_pure_drop_shrinks_the_count_by_exactly_two",
          sorted(two) == sorted(want2) and f.get("snapshot_after2_size") == "3",
          "after2_size=%s after2=%s" % (f.get("snapshot_after2_size"), two))
    g.chk(tag + "_notification_drove_the_swap",
          f.get("bridge_refresh_count") == "2" and f.get("bridge_notifications_seen") == "2"
          and "2" in (f.get("apply1_refresh_count"), f.get("apply2_refresh_count")),
          "notifications=%s refresh=%s waited_ms=%s/%s transport_notif=%s"
          % (f.get("bridge_notifications_seen"), f.get("bridge_refresh_count"),
             f.get("apply1_waited_ms"), f.get("apply2_waited_ms"),
             f.get("transport_notification_count")))
    if relay_log is not None:
        lines = relay_log.read_text(encoding="utf-8").splitlines() if relay_log.is_file() else []
        s2c = [x for x in lines if x.startswith("S2C")]
        c2s = [x for x in lines if x.startswith("C2S")]
        # 只认"通知那条 method"，不能认裸的 list_changed 子串：
        # 工具描述里就写着"推 list_changed"，tools/list 的**响应体**照样命中这个子串（实测被它骗过一次）
        compact = [x.replace(" ", "") for x in s2c]
        pushed = [x for x, c in zip(s2c, compact)
                  if '"method":"notifications/tools/list_changed"' in c]
        g.chk(tag + "_relay_as_external_observer_saw_both_directions",
              len(c2s) >= 6 and len(s2c) >= 6 and len(pushed) >= 1 and len(lines) > 0,
              "relay_lines=%d C2S=%d S2C=%d list_changed_lines=%d"
              % (len(lines), len(c2s), len(s2c), len(pushed)))
        g.chk(tag + "_relay_pushed_line_is_a_notification_not_a_response",
              len(pushed) >= 2 and '"method":"notifications/tools/list_changed"'
              in pushed[0].replace(" ", "")
              and '"result"' not in pushed[0] and '"id":' not in pushed[0],
              "命中 %d 条；第一条=%s（同一条里钉死：它没有 result/id ⇒ 是 server 主动推的 notification，"
              "不是我们发出去那发的响应体）" % (len(pushed), pushed[0][:180] if pushed else "无"))
        g.chk(tag + "_relay_has_no_empty_body",
              all(x.strip() for x in (c2s[-1], s2c[-1])),
              "last_C2S_len=%d last_S2C_len=%d" % (len(c2s[-1]), len(s2c[-1])))


def section_c(cp, g, wd):
    ctl1 = wd / "control1.json"
    ctl2 = wd / "control2.json"
    write_control(ctl1, ["p21_dyn_a"], ["p21_beta"])
    write_control(ctl2, [], ["p21_dyn_a", "p21_stall"])
    relay_log = wd / "c-relay.txt"
    srv_log = wd / "c-server-notes.txt"
    env = {"P21_RELAY_LOG": str(relay_log), "P21_SERVER_LOG": str(srv_log)}
    rc, f, _ = driver(cp, "stdioswap", wd,
                      [("python", sys.executable), ("server", str(REF_SERVER)),
                       ("relay", str(RELAY)), ("control1", str(ctl1)), ("control2", str(ctl2))],
                      env=env, timeout=150, tag="c-driver")
    check_swap(g, f, "stdio", relay_log)
    # server 侧记事本：表是它改的，且它确实广播了
    notes = srv_log.read_text(encoding="utf-8") if srv_log.is_file() else ""
    events = [json.loads(x) for x in notes.splitlines() if x.strip().startswith("{")]
    changed = [e for e in events if e.get("event") == "table_changed"]
    sent = [e for e in events if e.get("event") == "notification_sent"]
    g.chk("stdio_server_actually_changed_and_broadcast",
          len(changed) == 2 and len(sent) >= 1 and relay_log.stat().st_size > 0,
          "table_changed=%d notification_sent=%d events=%d" % (len(changed), len(sent), len(events)))


def section_c_http(cp, g, wd):
    ctl1 = wd / "c2-control1.json"
    ctl2 = wd / "c2-control2.json"
    write_control(ctl1, ["p21_dyn_b"], ["p21_alpha"])
    write_control(ctl2, [], ["p21_dyn_b", "p21_echo"])
    proc, url, _ = start_ref_http(wd, log_name="c2-ref-http")
    try:
        rc, f, _ = driver(cp, "httpswap", wd,
                          [("url", url), ("control1", str(ctl1)), ("control2", str(ctl2))],
                          timeout=150, tag="c2-driver")
        base = EXPECTED_STDIO_TOOLS
        want1 = [x for x in base if x != "mcp-e2e-p21_alpha"] + ["mcp-e2e-p21_dyn_b"]
        want2 = [x for x in want1 if x not in ("mcp-e2e-p21_dyn_b", "mcp-e2e-p21_echo")]
        one = [x for x in f.get("snapshot_after1_names", "").split(",") if x]
        two = [x for x in f.get("snapshot_after2_names", "").split(",") if x]
        g.chk("http_drop_is_real_deregistration",
              sorted(one) == sorted(want1) and f.get("snapshot_after1_size") == "5",
              "after1_size=%s after1=%s" % (f.get("snapshot_after1_size"), one))
        g.chk("http_pure_drop_shrinks_by_two",
              sorted(two) == sorted(want2) and f.get("snapshot_after2_size") == "3",
              "after2_size=%s after2=%s" % (f.get("snapshot_after2_size"), two))
        g.chk("http_swap_was_driven_by_the_get_stream",
              f.get("bridge_refresh_count") == "2" and f.get("transport_notification_count") == "2"
              and flag(f, "notification_capable") is True,
              "refresh=%s transport_notif=%s capable=%s sse_shape=%s json_shape=%s"
              % (f.get("bridge_refresh_count"), f.get("transport_notification_count"),
                 f.get("notification_capable"), f.get("sse_shape_responses"),
                 f.get("json_shape_responses")))
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()


# --------------------------------------------------------------------- (d) 反向 serve

def section_d(cp, g, wd):
    cfg = temp_config_root(wd)
    db = cfg / "state.db"
    rc, f, _ = driver(cp, "seed", wd, [("db", str(db))], timeout=90, tag="d-seed")
    g.chk("seed_really_wrote_the_db",
          f.get("sessions") == "e2e-arch-1,e2e-live-1,e2e-live-2"
          and f.get("messages_e2e-live-1") == "2" and db.is_file(),
          "sessions=%s msgs=%s db=%s" % (f.get("sessions"), f.get("messages_e2e-live-1"), db))

    # picocli 的形状：--config-dir/--db 挂在父命令 mcp 上，必须写在子命令 serve 之前
    # （与 `z-bot sessions --db X list` 同一套，不发明第二种调用法）
    argv = [java_bin(), "-cp", cp, ZBOT_MAIN, "mcp",
            "--config-dir", str(cfg), "--db", str(db), "serve"]
    official_sdk_client_roundtrip(g, wd, argv, db)
    raw_line_checks(g, wd, argv)


def official_sdk_client_roundtrip(g, wd, argv, db):
    script = r'''
import asyncio, json, os, sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

argv = json.loads(os.environ["P21_ARGV"])
DUMP = os.environ["P21_DUMP"]

async def main():
    out = {}
    params = StdioServerParameters(command=argv[0], args=argv[1:], env=dict(os.environ))
    async with stdio_client(params) as (r, w):
        async with ClientSession(r, w) as s:
            init = await s.initialize()
            out["initialize"] = init.model_dump(mode="json")
            tools = await s.list_tools()
            out["tools/list"] = tools.model_dump(mode="json")
            out["call_conversations_default"] = (await s.call_tool(
                "zbot_conversations_list", {"limit": 50})).model_dump(mode="json")
            out["call_conversations_archived"] = (await s.call_tool(
                "zbot_conversations_list", {"include_archived": True, "limit": 50})).model_dump(mode="json")
            out["call_messages"] = (await s.call_tool(
                "zbot_messages_read", {"conversation_id": "e2e-live-1"})).model_dump(mode="json")
            out["call_messages_unknown"] = (await s.call_tool(
                "zbot_messages_read", {"conversation_id": "no-such-session"})).model_dump(mode="json")
            try:
                out["call_missing_required_arg"] = (await s.call_tool(
                    "zbot_messages_read", {})).model_dump(mode="json")
            except Exception as e:
                rec = {"raised": type(e).__name__, "text": str(e)}
                err = getattr(e, "error", None)
                if err is not None:
                    rec["code"] = getattr(err, "code", None)
                    rec["message"] = getattr(err, "message", None)
                out["call_missing_required_arg"] = rec
            out["second_call_after_all"] = (await s.call_tool(
                "zbot_conversations_list", {"limit": 1})).model_dump(mode="json")
    with open(DUMP, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1, sort_keys=True)
    print("OK", flush=True)

asyncio.run(main())
'''
    dump = wd / "d-official-client-session.json"
    env = {"P21_ARGV": json.dumps(argv), "P21_DUMP": str(dump)}
    rc, out, err = run([sys.executable, "-u", "-c", script], env=env, timeout=150,
                       logfile=wd / "d-official-client.log")
    if rc != 0 or not dump.is_file():
        g.chk("official_sdk_client_can_talk_to_zbot", False,
              "rc=%s out=%s err 尾巴=%s" % (rc, out[-300:], err[-900:]))
        return None
    g.chk("official_sdk_client_can_talk_to_zbot", True, "官方 SDK client 全程无异常，会话现场=%s" % dump)
    d = json.loads(dump.read_text(encoding="utf-8"))
    init = d.get("initialize", {})
    caps = (init.get("capabilities") or {})
    g.chk("advertises_only_tools_and_no_listChanged_promise",
          init.get("protocolVersion") in ("2025-06-18", "2025-03-26", "2024-11-05")
          and (init.get("serverInfo") or {}).get("name") == "z-bot"
          and "listChanged" in json.dumps(caps.get("tools") or {})
          and (caps.get("tools") or {}).get("listChanged") is False,
          "protocolVersion=%s serverInfo=%s tools_caps=%s"
          % (init.get("protocolVersion"), init.get("serverInfo"), caps.get("tools")))
    tl = [t.get("name") for t in (d.get("tools/list") or {}).get("tools", [])]
    banned = [x for x in tl if any(k in x.lower() for k in
                                   ("send", "write", "delete", "prune", "run", "exec", "chat",
                                    "append", "set", "archive", "abort"))]
    g.chk("read_only_surface_two_tools_and_no_write_capable_name",
          tl == ["zbot_conversations_list", "zbot_messages_read"] and not banned,
          "tools=%s banned=%s（猎物：两条读端工具名确实在这张表里）" % (tl, banned))

    def rows(key):
        res = (d.get(key) or {})
        sc = res.get("structuredContent") or {}
        r = sc.get("result") or sc
        return res, (r.get("conversations") or r.get("messages") or [])

    res0, conv0 = rows("call_conversations_default")
    res1, conv1 = rows("call_conversations_archived")
    import sqlite3
    con = sqlite3.connect(str(db))
    archived = [x for x in con.execute("select id, archived from sessions").fetchall()]
    con.close()
    g.chk("archived_row_really_exists_in_the_db",
          ("e2e-arch-1", 1) in archived or ("e2e-arch-1", True) in archived,
          "sqlite 回读 sessions=%s（这是'归档项真在库里'的猎物）" % archived)
    g.chk("default_list_hides_archived_and_archived_list_shows_all_three",
          len(conv0) == 2 and len(conv1) == 3 and not res0.get("isError")
          and sorted(x.get("conversation_id") for x in conv0) == ["e2e-live-1", "e2e-live-2"]
          and "e2e-arch-1" in [x.get("conversation_id") for x in conv1],
          "default=%s archived=%s" % ([x.get("conversation_id") for x in conv0],
                                       [x.get("conversation_id") for x in conv1]))
    _, msgs = rows("call_messages")
    g.chk("messages_read_returns_the_seeded_two_with_roles",
          len(msgs) == 2 and [m.get("idx") for m in msgs] == [0, 1]
          and msgs[0].get("content") == "第一条用户消息"
          and "第一条用户消息" in json.dumps(d.get("call_messages"), ensure_ascii=False),
          "messages=%s" % json.dumps(msgs, ensure_ascii=False)[:260])
    bad = d.get("call_messages_unknown") or {}
    g.chk("unknown_conversation_is_a_tool_error_not_a_crash",
          bad.get("isError") is True
          and "未知会话 id" in json.dumps(bad, ensure_ascii=False),
          "isError=%s text=%s" % (bad.get("isError"),
                                  json.dumps(bad.get("content"), ensure_ascii=False)[:200]))
    missing = d.get("call_missing_required_arg") or {}
    g.chk("missing_required_argument_is_jsonrpc_32602",
          "-32602" in json.dumps(missing, ensure_ascii=False)
          and "conversation_id" in json.dumps(missing, ensure_ascii=False),
          "对端拿到的原文=%s" % json.dumps(missing, ensure_ascii=False)[:240])
    after = d.get("second_call_after_all") or {}
    sc = (after.get("structuredContent") or {})
    g.chk("server_still_answers_after_the_error_paths_and_honours_limit",
          not after.get("isError") and sc.get("conversationCount") == 1
          and sc.get("limit") == 1 and len(sc.get("conversations") or []) == 1,
          "错误路径之后最后一发（limit=1）=%s" % json.dumps(after, ensure_ascii=False)[:260])
    return d


def raw_line_checks(g, wd, argv):
    """裸线取证：五条进 ⇒ 恰好四条出（notification 不许有响应），且 EOF 后收尾。"""
    lines = ['{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":'
             '"2025-06-18","capabilities":{},"clientInfo":{"name":"raw-python","version":"0"}}}',
             '{"jsonrpc":"2.0","method":"notifications/initialized"}',
             "\x7f not json at all",
             '{"jsonrpc":"2.0","id":"abc","method":"no/such/method"}',
             '{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{}}']
    p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=open(wd / "d-raw-wire.err.txt", "w"),
                         text=True, encoding="utf-8", env=dict(os.environ))
    got = []
    try:
        for ln in lines:
            p.stdin.write(ln + "\n")
        p.stdin.flush()
        p.stdin.close()               # EOF ⇒ serveLoop 必须自己收尾（不无界读）
        deadline = time.time() + 30
        while time.time() < deadline:
            r = p.stdout.readline()
            if not r:
                break
            got.append(r.rstrip("\n"))
        rc = p.wait(timeout=10)
    except Exception as e:
        rc = "异常 %s" % e
    finally:
        if p.poll() is None:
            p.kill()
            p.wait(timeout=5)
    joined = "\n".join(got)
    (wd / "d-raw-wire-session.txt").write_text(
        "\n".join("IN  " + x for x in lines) + "\n"
        + "\n".join("OUT " + x for x in got) + "\n", encoding="utf-8")
    g.chk("raw_wire_one_response_per_request_and_none_for_notifications",
          len(got) == 4 and '"id":1' in got[0] and "-32700" in got[1]
          and '"id":"abc"' in got[2] and "-32601" in got[2] and '"id":5' in got[3],
          "进了 %d 条 (1 条是 notification) ⇒ 出了 %d 条；依次=%s"
          % (len(lines), len(got), [x[:120] for x in got]))
    g.chk("raw_wire_junk_line_and_unknown_method_both_answered",
          "非 JSON" in joined and "未知 method" in joined,
          "原文片段=%s" % joined[:300].replace("\n", " ⏎ "))
    errtxt = (wd / "d-raw-wire.err.txt").read_text(encoding="utf-8", errors="replace") \
        if (wd / "d-raw-wire.err.txt").is_file() else ""
    g.chk("raw_wire_exits_at_eof_and_reports_a_bounded_summary",
          rc == 0 and "处理 5 行" in errtxt and "拒绝 2 行" in errtxt,
          "exit=%s 收尾行=%s" % (rc, [x for x in errtxt.splitlines()
                                     if "收尾" in x or "就绪" in x]))


# --------------------------------------------------------------------- (e) watchdog

def section_e(cp, g, wd):
    cases = [("on-mcp", "true", "mcp"), ("on-sleeper", "true", "sleeper"),
             ("off-sleeper", "false", "sleeper")]
    results = {}
    for tag, wd_flag, mode in cases:
        results[tag] = watchdog_once(cp, g, wd, tag, wd_flag, mode)
    on = results["on-mcp"]
    g.chk("e_watchdog_production_stdio_child_goes_with_the_parent",
          on["children_before"] == 1 and not on["child_alive_after"] and on["gone_ms"] <= 5000,
          "before=%s pgrep-P-after=%s child_alive=%s gone_ms=%s"
          % (on["children_before"], on["children_after"], on["child_alive_after"], on["gone_ms"]))
    sl = results["on-sleeper"]
    g.chk("e_watchdog_takes_an_eof_insensitive_child_too",
          sl["children_before"] == 1 and not sl["child_alive_after"] and sl["gone_ms"] <= 5000,
          "before=%s child_alive=%s gone_ms=%s"
          % (sl["children_before"], sl["child_alive_after"], sl["gone_ms"]))
    off = results["off-sleeper"]
    g.chk("e_without_watchdog_that_same_child_really_survives_as_orphan",
          off["children_before"] == 1 and off["child_alive_after"]
          and off["child_ppid"] != off["parent_pid"],
          "child_alive=%s child_ppid=%s parent=%s（对照失效就说明'带走了'不是 watchdog 干的）"
          % (off["child_alive_after"], off["child_ppid"], off["parent_pid"]))
    cleanup = 0
    for tag, r in results.items():
        if r.get("child_alive_after") and r.get("child"):
            run(["/bin/kill", "-9", str(r["child"])])
            if not wait_until(lambda: not alive(r["child"]), 8):
                cleanup += 1
    g.chk("e_no_orphan_left_behind_by_this_gauge",
          cleanup == 0, "本节点完后还在的孤儿数=%d" % cleanup)


def watchdog_once(cp, g, wd, tag, watchdog_flag, mode):
    ready = wd / ("e-%s.ready" % tag)
    if ready.exists():
        ready.unlink()
    p = subprocess.Popen([java_bin(), "-cp", cp, PROBE, str(ready), watchdog_flag, mode],
                         stdout=open(wd / ("e-%s.probe.out" % tag), "w"),
                         stderr=subprocess.STDOUT, text=True)
    deadline = time.time() + 60
    while time.time() < deadline and not (ready.is_file() and ready.stat().st_size > 0):
        if p.poll() is not None:
            break
        time.sleep(0.1)
    if not (ready.is_file() and ready.stat().st_size > 0):
        g.chk("e_probe_ready_" + tag, False, "探针没交 ready（exit=%s）" % p.poll())
        return {"children_before": 0, "child_alive_after": False, "gone_ms": -1,
                "children_after": 0, "child": None, "child_ppid": "", "parent_pid": -1}
    parts = ready.read_text(encoding="utf-8").split()[0:200]
    parent = int(parts[1])
    kids = [int(x) for x in parts[2:]]
    res = {"children_before": len(kids), "parent_pid": parent, "child": kids[0] if kids else None,
           "child_alive_after": False, "gone_ms": -1, "children_after": 0, "child_ppid": ""}
    run(["/bin/kill", "-9", str(parent)])
    t0 = time.time()
    if kids:
        kid = kids[0]
        while time.time() - t0 < 12:
            if not alive(kid):
                res["child_alive_after"] = False
                res["gone_ms"] = int((time.time() - t0) * 1000)
                break
            res["child_ppid"] = ppid_of(kid)
            time.sleep(0.1)
        else:
            res["child_alive_after"] = alive(kid)
            res["child_ppid"] = ppid_of(kid) if res["child_alive_after"] else ""
    res["children_after"] = len(child_pids(parent))
    try:
        p.wait(timeout=5)
    except subprocess.TimeoutExpired:
        p.kill()
    return res


# --------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", default=None,
                    help="现场目录名；不给就自动生成 R<时分秒>-p<pid>（每跑必新，见缺陷 C）")
    ap.add_argument("--only", default=",".join(SECTIONS))
    ap.add_argument("--workroot", default=str(CACHE / "e2e"))
    args = ap.parse_args()
    auto_label = args.label is None
    label = args.label if not auto_label else \
        "R" + time.strftime("%H%M%S") + "-p%d" % os.getpid()
    wd = fresh_workdir(args.workroot, label, auto_label)
    only = [x.strip() for x in args.only.split(",") if x.strip()]
    bad = [x for x in only if x not in SECTIONS]
    if bad:
        raise SystemExit("不认识的小节: %s（可用 %s）" % (bad, SECTIONS))

    for f in (REF_SERVER, RELAY):
        if not f.is_file():
            raise SystemExit("FATAL: 缺少验收脚本 %s" % f)
    rc, ver, err = run([sys.executable, "-c",
                        "from importlib.metadata import version; import mcp.server.fastmcp;"
                        " print(version('mcp'))"], timeout=60)
    if rc != 0:
        print("NO-RUN: 官方 MCP Python SDK 不可用（%s）—— 按工单不许 skip、不许 pip install"
              % err.strip()[-300:])
        return 3
    print("官方 SDK mcp 版本=%s python=%s 工作根=%s" % (ver.strip(), sys.executable, wd))
    cp = classpath(wd)

    log = open(wd / "p21_e2e_readings.txt", "w", encoding="utf-8")
    g = Gauge(log)
    t0 = time.time()
    try:
        # 现场目录自证挂在 requested 清单的第一节里 ⇒ 任何 `--only` 组合法都必做（缺陷 C）
        g.start(only[0])
        prove_fresh_workdir(g, args.workroot, label)
        if "a" in only:
            g.start("a")
            section_a(cp, g, wd)
        if "b" in only:
            g.start("b")
            section_b(cp, g, wd)
        if "c" in only:
            g.start("c")
            section_c(cp, g, wd)
            section_c_http(cp, g, wd)
        if "d" in only:
            g.start("d")
            section_d(cp, g, wd)
        if "e" in only:
            g.start("e")
            section_e(cp, g, wd)
    except Exception as e:
        g.start("fatal")
        g.chk("unhandled", False, "%s: %s" % (type(e).__name__, e))
    finally:
        log.close()

    counts = g.section_counts
    total = sum(counts.values())
    print("\n== P21 杠③ 读数汇总 label=%s 用时=%.1fs ==" % (label, time.time() - t0))
    for s in SECTIONS:
        print("  (%s) 断言 %d 条" % (s, counts.get(s, 0)))
    print("  合计 %d 条，失败 %d 条: %s" % (total, len(g.failed), g.failed))
    empty = [s for s in only if counts.get(s, 0) == 0]
    if empty:
        print("FATAL: 以下小节一条断言都没产出（空输入不许当满分）: %s" % empty)
        return 2
    if g.failed:
        return 1
    print("ALL GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
