#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P27c 杠③ —— 真 jar / 真子进程 / 真 HTTP 假端点的委托面 E2E（自带全部判词，不 import 别的战役脚本）。

为什么单测那一层不够（`SubagentApprovalDenialTest` / `DelegateSummaryWiringTest` 已经钉过形状）：
  那两支塞的是脚本化 {@code LlmProvider} **替身** ⇒ 进程边界以下全没走：{@code LlmRouter} 造的
  真 HTTP 客户端、`BotConfig.load(--config-dir)` 的装配、沙箱与溢出目录的落盘、线程池那条真线程。
  这里**不注入 provider** ⇒ 每一次模型调用都是量具收到的一个真 POST，判据读的是
  **假端点收到的字节** + **盘上的文件**，不是被测自己的自述。

一轮 = 三个真 java 子进程（互不共享状态）：
  deny   父 → delegate_task → 子 → exec `rm -rf ./target-cache`
           ⇒ A1 沙箱里那枚诱饵文件必须还活着（申请被当场 deny，一个字节都没执行）
           ⇒ A2 子的第二次请求里必须看得见 `[auto-denied]` + 闸门给的原因
           ⇒ A3 全程不许出现 `WAIT_CONFIRM`（那是修之前"假成功"的形状）
           ⇒ A4/A5/A6 摘要尺按本轮配置决定裁不裁；裁了就**必须**有溢出文件，
              且文件字节 == 假端点发出的那份长回复原文，且落在 `--config-dir` 之下
           ⇒ A7 委托台账 events.log 里那句"摘要已裁切 全文=<路径>"与 A5 是同一个文件
  async  submitBackground 走线程池那条路 ⇒ A8 取回的回复过同一把尺、子代理收工被 shutdown；
           A8c 再拉一次**不产生第二个全文文件**（读路径不写盘），两次指针同一个；
           A8d 异步的溢出目录跟着 async 那一份 profile；A8e 异步台账也带同一个指针
  closed agent.shutdown() 之后再提交 ⇒ A9 当场拒收（"委托池已关闭"）且台账落 FAILED
  A10 每一轮的 `~/.zbot` 三点采样（条目数 / 两个 md5 前 8 位 / 真 key 只量长度）逐轮不变，
      且 `~/.zbot` 底下**不许**出现 `delegate/summaries`（红线 1：写盘位置由 profile 推导）

三轮的差别只在摘要尺的配置，判据形状同一套：
  R1 `agent.delegate.max.summary.chars=300`  ⇒ 裁 + 溢出文件
  R2 不写这一行（缺省 24000）                ⇒ 不裁、零溢出
  R3 `=0`（"0 disables the ceiling"）        ⇒ 不裁、零溢出
⇒ 同一个"键"在真进程里改一次就换一次行为，这才是"配置项不是装饰"的进程外证据。

驱动打的是 `E2E|key=value`，本脚本按"= 到下一个空格"取值 ⇒ 带空格的原文会被劈掉一半
（run1 那句 footer 就是这么"看不见"的）。要送整份原文只能转码：footer/指针一律读
`*_b64` 那几行，不读 `oneLine` 的截断版。

`P27C_TEETH_PROBE=1` 那一支（需要变异锁）：把 `.nonInteractive(true)` 那行摘掉、重建、跑一轮 deny，
要求 A1/A2/A3 里至少"父侧看见 WAIT_CONFIRM"这一条**真的红** —— 负向判据没红过一次就是空跑。
还原只从注入前读进的原文字节写回 + md5 对账（绝不 `git checkout`）。

红线：数据根 = `~/.cache/zbot-p27c/e2e/round-<n>/`（不写 /tmp：同机别的机会扫空 /tmp），
`--config-dir` 指它；LLM 假端点只绑 127.0.0.1、端口一律 `bind(0)`；key 写 `stub-key-not-real`，
真 key 一个字节都不进本脚本（只量长度）；不给真域名发一个包。

复算: python3 -u _doc/005_testing/acceptance/p27c/p27c_e2e.py
      P27C_ROUNDS=1 python3 -u _doc/005_testing/acceptance/p27c/p27c_e2e.py
      P27C_TEETH_PROBE=1 python3 -u _doc/005_testing/acceptance/p27c/p27c_e2e.py   # 需要变异锁
"""
import fcntl
import glob
import hashlib
import base64
import io
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zbot-p27c")
E2E = os.path.join(CACHE, "e2e")
CORE_TARGET = os.path.join(ZBOT, "z-bot-core", "target")
DRIVER = "com.zifang.z.bot.delegate.P27cDelegateDriver"
MUT_SRC = "z-bot-core/src/main/java/com/zifang/z/bot/delegate/DelegateManager.java"
MUT_ANCHOR = "\n                .nonInteractive(true)"
STUB_KEY = "stub-key-not-real"
REAL_HOME = os.path.join(os.path.expanduser("~"), ".zbot")
JAVA_TIMEOUT = 120
MVN_TIMEOUT = 900
LINES = 20          # 长回复 20 行 × 101 字符 = 2020 字符

CHECKS = []
LLM = {"url": None, "seq": 0, "dir": None, "script": [], "lock": threading.Lock()}
# 每一次 java 子进程 stdout 里出现的非回环端点都记这里；非空 ⇒ 整跑判 NO-RUN。
REDLINE = {"hits": []}


def chk(name, ok, detail=""):
    CHECKS.append((name, bool(ok), detail))
    print("CHECK|%-44s %s %s" % (name, "PASS" if ok else "FAIL", detail))
    return bool(ok)


def sh(args, timeout=180, cwd=ZBOT):
    p = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         preexec_fn=os.setsid)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, (out or b"").decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except OSError:
            pass
        out, _ = p.communicate()
        return 124, (out or b"").decode("utf-8", "replace") + "\n<<TIMEOUT>>"


# ---------------------------------------------------------------------------
# 假 LLM 端点：收到什么就落盘什么，按脚本顺序回
# ---------------------------------------------------------------------------
class StubHandler(BaseHTTPRequestHandler):

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(n)
        try:
            req = json.loads(raw.decode("utf-8"))
        except Exception:
            req = {}
        with LLM["lock"]:
            LLM["seq"] += 1
            seq = LLM["seq"]
            idx = min(seq - 1, len(LLM["script"]) - 1) if LLM["script"] else 0
            step = LLM["script"][idx] if LLM["script"] else {"kind": "text", "content": "STUB-NOTHING"}
        with io.open(os.path.join(LLM["dir"], "%03d.json" % seq), "wb") as fh:
            fh.write(raw)
        msg, finish = self.render(seq, step)
        payload = json.dumps({
            "id": "stub-%d" % seq, "object": "chat.completion", "model": req.get("model"),
            "choices": [{"index": 0, "message": msg, "finish_reason": finish}],
            "usage": {"prompt_tokens": 40, "completion_tokens": 60, "total_tokens": 100},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    @staticmethod
    def render(seq, step):
        if step["kind"] == "tool":
            return ({"role": "assistant", "content": None, "tool_calls": [{
                "id": "call-e2e-%d" % seq, "type": "function",
                "function": {"name": step["name"],
                             "arguments": json.dumps(step["args"], ensure_ascii=False)}}]},
                "tool_calls")
        return {"role": "assistant", "content": step["content"]}, "stop"

    def log_message(self, *args):
        pass


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubHandler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    LLM["url"] = "http://127.0.0.1:%d/v1" % srv.server_address[1]
    return srv


def long_reply(tag):
    """20 行 × 101 字符，每行可辨识；中间第 10 行带本轮唯一 marker（裁过就必须看不见它）。"""
    out = []
    for i in range(LINES):
        head = "L%03d|%s|" % (i, tag)
        out.append(head + ("x" * (101 - len(head))))
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------------------
# 杠④ 的四点采样：条目数 + 两个 md5 前 8 位 + 真 key 只量长度
# ---------------------------------------------------------------------------
def home_gauge(tag):
    rc, out = sh(["bash", "-c",
                  'printf "%s/%s/%s/%s" "$(ls -A ~/.zbot | wc -l | tr -d \' \')" '
                  '"$(md5 -q ~/.zbot/config.properties | cut -c1-8)" '
                  '"$(md5 -q ~/.zbot/state.db | cut -c1-8)" '
                  '"$(awk -F= \'/^minimax\\.api\\.key=/{print length($2)}\' ~/.zbot/config.properties)"'],
                 cwd=os.path.expanduser("~"))
    reading = out.strip()
    print("HOME|%s|%s" % (tag, reading))
    return reading


def build(rebuild_label):
    rc, out = sh(["mvn", "-o", "-q", "-DskipTests", "package"], timeout=MVN_TIMEOUT)
    tail = out.strip().splitlines()[-3:] if out.strip() else []
    print("BUILD|%s rc=%d %s" % (rebuild_label, rc, " / ".join(tail)))
    if rc != 0:
        return False
    cp_file = os.path.join(CACHE, "cp-%s.txt" % rebuild_label)
    rc, out = sh(["mvn", "-o", "-q", "-pl", "z-bot-core", "dependency:build-classpath",
                  "-DincludeScope=test", "-Dmdep.outputFile=" + cp_file], timeout=MVN_TIMEOUT)
    size = os.path.getsize(cp_file) if os.path.isfile(cp_file) else -1
    print("BUILD|classpath=%s size=%d rc=%d" % (cp_file, size, rc))
    if size <= 0:
        # 0 字节文件"存在"却没量到东西 —— 上一轮就是这么把空跑读成通过的
        print("NO-RUN: dependency:build-classpath 产出空文件")
        return False
    build.cp = ":".join([os.path.join(CORE_TARGET, "classes"),
                         os.path.join(CORE_TARGET, "test-classes"),
                         io.open(cp_file, encoding="utf-8").read().strip()])
    drv = os.path.join(CORE_TARGET, "test-classes", "com", "zifang", "z", "bot",
                       "delegate", "P27cDelegateDriver.class")
    if not os.path.isfile(drv):
        print("NO-RUN: 缺驱动字节码 " + drv)
        return False
    return True


def jar_identity():
    """构件身份：量的必须是含本期字节的那版类。

    `SummaryBudget.class` 里必须数得出 `[SUMMARY TRUNCATED]`（本期新增），
    同一趟扫描在 `DelegationLedger.class`（本期没碰）上必须数**不**出它 —— 反向对照，
    防"扫不到字符串"被读成"类是新的"。读不到文件一律 FATAL。
    """
    def count(cls, needle):
        path = os.path.join(CORE_TARGET, "classes", cls)
        if not os.path.isfile(path):
            return None
        with io.open(path, "rb") as fh:
            return fh.read().count(needle.encode("utf-8"))
    prey = count("com/zifang/z/bot/delegate/SummaryBudget.class", "[SUMMARY TRUNCATED]")
    ctrl = count("com/zifang/z/bot/delegate/DelegationLedger.class", "[SUMMARY TRUNCATED]")
    print("JAR_IDENTITY|SummaryBudget=%s DelegationLedger=%s" % (prey, ctrl))
    return prey, ctrl


def read_state_json(ledger_root):
    out = {}
    for scene in sorted(glob.glob(os.path.join(ledger_root, "*", "state.json"))):
        try:
            with io.open(scene, encoding="utf-8") as fh:
                out[os.path.basename(os.path.dirname(scene))] = json.load(fh)
        except Exception as ex:
            out[os.path.basename(os.path.dirname(scene))] = {"_parse_error": str(ex)}
    return out


def config_properties(cfg, extra):
    with io.open(os.path.join(cfg, "config.properties"), "w", encoding="utf-8") as fh:
        # `providers=stub` 不是可有可无：BotConfig 只自动识别 LEGACY_PROVIDERS 里的 code
        # (`BotConfig.java:27-32`)，自造 code 必须显式声明，否则 `llm.provider=stub` 落空、
        # activeProvider() 兜底成 "openai + baseUrl=null" (`BotConfig.java:363-372`)，
        # LlmRouter 那一跳就用 kernel 默认域 https://api.openai.com/v1 真发出去
        # (`LlmRouter.java:40`)。真踩过这一刀的是 P26 run1（8 发全 401、本地 stub 一发没收到，
        # `_doc/005_testing/acceptance/p26/EVIDENCE.md:583/588`）；本 harness 的日志里该域 0 命中
        # （复算：`grep -rl 'api\.openai\.com' ~/.cache/zbot-p27c-lead/` 无输出）。
        fh.write("llm.provider=stub\nproviders=stub\n"
                 "test.description=P27c e2e 隔离现场（key 是假的）\n"
                 "stub.type=openai\nstub.api.key=%s\n" % STUB_KEY +
                 "stub.base.url=%s\n" % LLM["url"] +
                 "stub.model=stub-model\n"
                 "agent.delegate.max.depth=2\nagent.delegate.max.children=3\n")
        for line in extra:
            fh.write(line + "\n")


def run_java(mode, cfg, log_dir, tag):
    """起一个真子进程，stdout 原样落盘并解析 E2E| 行。"""
    LLM["seq"] = 0
    LLM["dir"] = os.path.join(log_dir, "llm-" + mode)
    if not os.path.isdir(LLM["dir"]):
        os.makedirs(LLM["dir"])
    java = shutil.which("java") or "java"
    out_path = os.path.join(log_dir, "java-%s.out" % mode)
    p = subprocess.Popen([java, "-cp", build.cp, DRIVER, "--config-dir", cfg, "--mode", mode],
                         cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         preexec_fn=os.setsid, universal_newlines=True)
    deadline = time.time() + JAVA_TIMEOUT
    lines = []
    while p.poll() is None and time.time() < deadline:
        line = p.stdout.readline()
        if line:
            lines.append(line.rstrip("\n"))
        else:
            time.sleep(0.05)
    try:
        rest = p.stdout.read() or ""
    except Exception:
        rest = ""
    lines.extend(rest.splitlines())
    rc = p.returncode
    with io.open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    if rc is None:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except OSError:
            pass
        rc = -1
    facts = {}
    for line in lines:
        if not line.startswith("E2E|"):
            continue
        for k, v in re.findall(r'(?:^|\s)([A-Za-z_]\w*)=([^ ]*)', line[len("E2E|"):]):
            facts.setdefault(k, v)
    # 红线：一个包都不许出本机。子进程 stdout 里出现非回环的 http(s) 端点 = 本轮不判定。
    for url in re.findall(r'https?://[^\s"\')]+', "\n".join(lines)):
        if not re.match(r'https?://(127\.0\.0\.1|localhost)(:|/|$)', url):
            REDLINE["hits"].append("%s/%s → %s" % (tag, mode, url))
    print("JAVA|%s|tag=%s rc=%s lines=%d facts=%d" % (mode, tag, rc, len(lines), len(facts)))
    return rc, facts, lines


def deny_script(reply):
    return [
        {"kind": "tool", "name": "delegate_task", "args": {"task": "去删一个目录并回报", "label": "e2e"}},
        {"kind": "tool", "name": "exec", "args": {"command": "rm -rf ./target-cache"}},
        {"kind": "text", "content": reply},
        {"kind": "text", "content": "PARENT-SAW-DELEGATE-RESULT"},
    ]


def async_script(reply):
    return [{"kind": "text", "content": reply}]


def bodies(seq_dir):
    """假端点收到的请求原文里，所有 role=tool 的消息内容（按出现顺序）。"""
    rows = []
    for path in sorted(glob.glob(os.path.join(seq_dir, "[0-9]*.json"))):
        try:
            with io.open(path, encoding="utf-8") as fh:
                req = json.load(fh)
        except Exception:
            continue
        for m in req.get("messages") or []:
            if m.get("role") == "tool":
                content = m.get("content")
                rows.append((os.path.basename(path),
                             content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)))
    return rows


def all_recorded_text(seq_dir):
    blob = []
    for path in sorted(glob.glob(os.path.join(seq_dir, "[0-9]*.json"))):
        with io.open(path, encoding="utf-8") as fh:
            blob.append(fh.read())
    return "\n".join(blob)


def judge_round(tag, cfgs, log_dir, mode_facts, reply, expect_trim, home_now):
    """一轮的判词。expect_trim=True ⇒ 摘要必须被裁且全文落盘。"""
    deny_dir = os.path.join(log_dir, "llm-deny")
    rows = bodies(deny_dir)
    # 请求 3（子代理被 deny 之后那一问）里必须同时出现裁决句与闸门给的原因
    child_rows = [c for f, c in rows if "[auto-denied]" in c or "高危命令需要确认" in c]
    joined = "\n".join(c for _, c in rows)

    cfg = cfgs["deny"]
    sentinel = os.path.join(cfg, "workspace", "target-cache", "keep.txt")
    facts = mode_facts.get("deny", {})
    chk("%s A1 诱饵文件活着（申请被当场 deny）" % tag, os.path.isfile(sentinel)
        and facts.get("sentinel_after") == "true",
        "sentinel=%s after=%s" % (sentinel, facts.get("sentinel_after")))
    chk("%s A2 子模型看得见 [auto-denied]+闸门原因" % tag,
        any("[auto-denied]" in c for c in child_rows)
        and any("高危命令需要确认" in c for c in child_rows),
        "命中行数=%d" % len(child_rows))
    parent_reply = b64_val(facts.get("parent_reply_b64"))
    chk("%s A3 全程没有 WAIT_CONFIRM 假成功" % tag,
        "WAIT_CONFIRM" not in all_recorded_text(deny_dir)
        and "WAIT_CONFIRM" not in parent_reply and parent_reply != "",
        "父回复（转码回原样）=%s" % parent_reply[:70])
    chk("%s A3b 子代理计数恰好 1、两条队列都空" % tag,
        facts.get("child_auto_denied") == "1" and facts.get("child_pending") == "0"
        and facts.get("parent_pending") == "0" and facts.get("child_non_interactive") == "true",
        "denied=%s child_pending=%s parent_pending=%s non_interactive=%s"
        % (facts.get("child_auto_denied"), facts.get("child_pending"),
           facts.get("parent_pending"), facts.get("child_non_interactive")))

    delivered = [c for _, c in rows if c.find(reply[:40]) >= 0 or "[SUMMARY TRUNCATED]" in c]
    tail_row = delivered[-1] if delivered else ""
    middle = reply.split("\n")[LINES // 2]
    if expect_trim:
        chk("%s A4 进父上下文的这份被裁" % tag,
            "[SUMMARY TRUNCATED]" in tail_row and middle not in tail_row
            and reply[:40] in tail_row,
            "长度=%d 含原文总长=%s" % (len(tail_row), ("原文共 %d 字符" % len(reply)) in tail_row))
        spills = spill_files(facts.get("spill_dir"))
        chk("%s A5 溢出文件字节 == 假端点发出的全文" % tag,
            len(spills) == 1 and read_text(spills[0]) == reply,
            "spill=%s" % [os.path.basename(s) for s in spills])
        chk("%s A6 溢出目录由 --config-dir 推导（红线 1）" % tag,
            bool(spills) and os.path.abspath(facts.get("spill_dir", "")).startswith(os.path.abspath(cfg)),
            "spill_dir=%s cfg=%s" % (facts.get("spill_dir"), cfg))
        detail = delegate_detail(cfg)
        chk("%s A7 台账 detail 里那条全文路径与实际文件同一个" % tag,
            bool(spills) and spills[0] in detail,
            "detail=%s" % detail[:150])
    else:
        chk("%s A4 这份整页照回、未被裁" % tag,
            reply in "\n".join(c for _, c in rows) and "[SUMMARY TRUNCATED]" not in joined,
            "父侧行数=%d" % len(rows))
        chk("%s A5 不裁就不往 profile 里写文件" % tag,
            len(spill_files(facts.get("spill_dir"))) == 0,
            "spill_dir=%s 个数=%d" % (facts.get("spill_dir"),
                                      len(spill_files(facts.get("spill_dir")))))

    afacts = mode_facts.get("async", {})
    ares = b64_val(afacts.get("background_result_b64"))
    ares2 = b64_val(afacts.get("background_result2_b64"))
    aspills = spill_files(afacts.get("spill_dir"))
    if expect_trim:
        chk("%s A8 异步出口过同一把尺（裁 + 唯一溢出）" % tag,
            "[SUMMARY TRUNCATED]" in ares and len(aspills) == 1
            and read_text(aspills[0]) == reply,
            "len=%s spill=%d child_shutdown=%s" % (afacts.get("background_result_len"),
                                                   len(aspills), afacts.get("async_child_shutdown")))
        # 第二次拉取是这一支的猎物：修之前每次 /background result 都重新裁一遍，
        # 而溢出文件名带毫秒戳 ⇒ 同一个委托拉两次就多两个全文文件。
        chk("%s A8c 再拉一次不落第二份全文、指针仍是同一个文件" % tag,
            len(aspills) == 1 and "[SUMMARY TRUNCATED]" in ares2
            and bool(aspills) and aspills[0] in ares and aspills[0] in ares2,
            "spill=%d 一次含指针=%s 二次含指针=%s" % (len(aspills), bool(aspills) and aspills[0] in ares,
                                                     bool(aspills) and aspills[0] in ares2))
        # 三份 profile 各归各的：这一条红了说明"根"又共用了（数溢出文件会串味）
        chk("%s A8d 异步溢出落在 async 那一份 profile 里" % tag,
            bool(aspills)
            and os.path.abspath(afacts.get("spill_dir", "")).startswith(os.path.abspath(cfgs["async"]))
            and not os.path.abspath(afacts.get("spill_dir", "")).startswith(
                os.path.abspath(cfgs["deny"]) + os.sep),
            "spill_dir=%s async_cfg=%s" % (afacts.get("spill_dir"), cfgs["async"]))
        chk("%s A8e 异步台账那条 task_completed 里带同一个全文指针" % tag,
            bool(aspills) and aspills[0] in delegate_detail(cfgs["async"]),
            "detail=%s" % delegate_detail(cfgs["async"])[:160])
    else:
        chk("%s A8 异步出口整页照回且零溢出" % tag,
            len(aspills) == 0 and "[SUMMARY TRUNCATED]" not in ares,
            "len=%s spill=%d" % (afacts.get("background_result_len"), len(aspills)))
    # 同一个计数器在 deny 那一条读数是 1（A3b），这里读数是 0 ⇒ 0 不是"没接线"的默认值
    chk("%s A8b 异步子代理收工被 shutdown、审批计数器读得出且为 0" % tag,
        afacts.get("async_child_shutdown") == "true"
        and afacts.get("async_child_auto_denied") == "0",
        "shutdown=%s auto_denied=%s" % (afacts.get("async_child_shutdown"),
                                        afacts.get("async_child_auto_denied")))

    cfacts = mode_facts.get("closed", {})
    refused = cfacts.get("refused_line", "")
    states = read_state_json(os.path.join(cfgs["closed"], "delegate", "live"))
    chk("%s A9 池收掉后提交被当场拒且台账落 FAILED" % tag,
        "异步委托未提交" in refused and "委托池已关闭" in refused
        and any(str(v.get("state")) == "FAILED" for v in states.values()),
        "refused=%s states=%s" % (refused[:60], sorted(set(
            str(v.get("state")) for v in states.values()))))
    chk("%s A10 真 profile 三点不变、底下没有 delegate/summaries" % tag,
        home_now[0] == home_now[1] and not os.path.isdir(os.path.join(REAL_HOME, "delegate")),
        "before=%s after=%s" % (home_now[0], home_now[1]))


def spill_files(d):
    if not d or not os.path.isdir(d):
        return []
    return sorted(glob.glob(os.path.join(d, "*.txt")))


def read_text(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def delegate_detail(cfg):
    """台账的 events.log（append-only，每行 `<ts>|<kind>|<detail>`）。

    state.json 里没有 events 这个键 —— 第一版照着 state.json 找 detail，于是 A7 恒拿空串。
    """
    out = []
    for path in sorted(glob.glob(os.path.join(cfg, "delegate", "live", "*", "events.log"))):
        for line in read_text(path).splitlines():
            parts = line.split("|", 2)
            if len(parts) == 3:
                out.append("%s %s %s" % (os.path.basename(os.path.dirname(path)), parts[1], parts[2]))
    return "\n".join(out)


def b64_val(raw):
    """驱动那边整份原文只能转码送（facts 是按"=非空格串"切的，带空格会被劈掉）。"""
    if not raw or raw in ("null", "NULL"):
        return ""
    try:
        return base64.b64decode(raw).decode("utf-8", "replace")
    except Exception:
        return ""


def acquire_lock():
    top = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--git-common-dir"],
                         stdout=subprocess.PIPE)
    common = top.stdout.decode("utf-8", "replace").strip()
    if not os.path.isabs(common):
        common = os.path.join(ZBOT, common)
    path = os.path.join(common, "zbot-mutlock")
    fh = io.open(path, "a+")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except IOError:
        print("FATAL: 变异锁被别的写手占着（%s）—— 本脚本不带等待" % path)
        return None
    return fh


def teeth_probe(rounds):
    """把 `.nonInteractive(true)` 摘掉重建，跑一遍 deny 那一支，要求**旧形状确实带病**。

    这支量的是量具自己：A2/A3 那两条判据（一条正向"看得见 [auto-denied]"、一条负向
    "看不见 WAIT_CONFIRM"）如果在没修的形状下也全绿，那它们就是空跑，不能拿来当证据。
    所以判"有牙"的条件是两条**旧形状观察项都成立** ⇒ rc=0；任一不成立 ⇒ rc=5（这一支没牙）。

    第一版这里写反过：把"某条 CHECK 红了"当成有牙，而那条红其实是
    "旧形状下 WAIT_CONFIRM 没显形"（当时还没有假端点，`stub.base.url=None`，
    子进程连一次 HTTP 都没发出去）。现在两端都钉：显形才算有牙，且发不出包当场判 NO-RUN。
    """
    print("\n== P27C_TEETH_PROBE：旧形状装回去，病症必须显形 ==")
    src = os.path.join(ZBOT, MUT_SRC)
    original = read_text(src)
    if original.count(MUT_ANCHOR) != 1:
        print("FATAL: 探针锚点不是唯一命中（%d）" % original.count(MUT_ANCHOR))
        return 1
    lock = acquire_lock()
    if lock is None:
        return 4
    baseline = hashlib.md5(original.encode("utf-8")).hexdigest()
    restored = [False]
    try:
        with io.open(src, "w", encoding="utf-8") as fh:
            fh.write(original.replace(MUT_ANCHOR, "", 1))
        rc = teeth_body()
    finally:
        # 还原无条件发生；但"没还原成功"不许用 finally 里的 return 覆盖上面的判定，
        # 所以只记账，rc 在块外合成。
        with io.open(src, "w", encoding="utf-8") as fh:
            fh.write(original)
        restored[0] = hashlib.md5(read_text(src).encode("utf-8")).hexdigest() == baseline
        print("TEETH|还原 md5 对账=%s" % restored[0])
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
        except Exception:
            pass
        if not restored[0]:
            print("TEETH|FATAL: src 没还原成原样 —— 别再量，先修还原")
    return rc if restored[0] else 6


def teeth_body():
    if not build("teeth"):
        return 2
    srv = start_stub()          # 没有假端点，这支量的是"发不出包"，不是旧形状
    home_first = home_gauge("teeth.start")
    try:
        cfg = os.path.join(E2E, "teeth", "cfg")
        log = os.path.join(E2E, "teeth")
        if os.path.isdir(cfg):
            shutil.rmtree(cfg)
        os.makedirs(cfg)
        config_properties(cfg, ["agent.delegate.max.summary.chars=300"])
        tag = "TEETH"
        reply = long_reply(tag)
        LLM["script"] = deny_script(reply)
        rc, _facts, _ = run_java("deny", cfg, log, tag)
        sentinel = os.path.join(cfg, "workspace", "target-cache", "keep.txt")
        rows = bodies(os.path.join(log, "llm-deny"))
        joined = "\n".join(c for _, c in rows)
        saw_auto_denied = "[auto-denied]" in joined
        saw_wait_confirm = "WAIT_CONFIRM" in all_recorded_text(os.path.join(log, "llm-deny"))
        chk("TEETH 旧形状下父侧真的收到过 WAIT_CONFIRM 假成功", saw_wait_confirm,
            "saw_wait_confirm=%s rc=%s sentinel=%s" % (saw_wait_confirm, rc, os.path.isfile(sentinel)))
        chk("TEETH 旧形状下 [auto-denied] 确实还没诞生", not saw_auto_denied,
            "saw_auto_denied=%s" % saw_auto_denied)
        chk("TEETH 这一支自己没出网", not REDLINE["hits"], "; ".join(REDLINE["hits"][:3]))
        red = [n for n, ok, _ in CHECKS if not ok]
        CHECKS[:] = []
        sick = saw_wait_confirm and not saw_auto_denied
        print("TEETH|病症显形=%s 判据红=%s" % (sick, ",".join(red) or "无"))
        home_last = home_gauge("teeth.end")
        if home_first != home_last:
            print("TEETH|FATAL: 这一支动了真 profile %s → %s" % (home_first, home_last))
            return 7
        if REDLINE["hits"]:
            print("NO-RUN: 出网了，这一支不作数")
            return 3
        return 0 if sick else 5
    finally:
        srv.shutdown()


build.cp = ""


def main():
    rounds = int(os.environ.get("P27C_ROUNDS") or "3")
    if os.environ.get("P27C_TEETH_PROBE") == "1":
        return teeth_probe(rounds)
    if not os.path.isdir(E2E):
        os.makedirs(E2E)
    print("== P27c E2E %d 轮 ==" % rounds)
    home_first = home_gauge("start")
    if not build("main"):
        return 2
    prey, ctrl = jar_identity()
    chk("K0 构件身份：本期新增的类里有那句 footer", prey == 1, "SummaryBudget.class 命中=%s" % prey)
    chk("K0b 反向对照：没碰过的类里数不出那句", ctrl == 0, "DelegationLedger.class 命中=%s" % ctrl)
    srv = start_stub()
    plan = [("R1", ["agent.delegate.max.summary.chars=300"], True),
            ("R2", [], False),
            ("R3", ["agent.delegate.max.summary.chars=0"], False)]
    for i in range(rounds):
        tag, extra, expect_trim = plan[i % len(plan)]
        tag = "%s-%d" % (tag, i + 1)
        root = os.path.join(E2E, tag)
        log = root
        if os.path.isdir(root):
            shutil.rmtree(root)
        # 三个 mode 各一个 profile：共用的话，async 落的溢出文件会被 deny 那一条数进去，
        # 台账也会被 closed 那条 FAILED 污染（"三个子进程互不共享状态"是这轮的判词前提）。
        cfgs = {}
        for mode in ("deny", "async", "closed"):
            cfgs[mode] = os.path.join(root, "cfg-" + mode)
            os.makedirs(cfgs[mode])
            config_properties(cfgs[mode], extra)
        home_before = home_gauge("%s.before" % tag)
        reply = long_reply(tag)
        facts = {}
        LLM["script"] = deny_script(reply)
        rc_deny, facts["deny"], _ = run_java("deny", cfgs["deny"], log, tag)
        LLM["script"] = async_script(reply)
        rc_async, facts["async"], _ = run_java("async", cfgs["async"], log, tag)
        LLM["script"] = []
        rc_closed, facts["closed"], _ = run_java("closed", cfgs["closed"], log, tag)
        home_after = home_gauge("%s.after" % tag)
        chk("%s A0 三个子进程都正常退出" % tag, rc_deny == 0 and rc_async == 0 and rc_closed == 0,
            "rc=%s/%s/%s" % (rc_deny, rc_async, rc_closed))
        judge_round(tag, cfgs, log, facts, reply, expect_trim, (home_before, home_after))
        if REDLINE["hits"]:
            # 出网就是出网：本轮之后的判词全部作废，别把它写成"某条 CHECK 红了"
            print("NO-RUN: 子进程往本机以外发过包 ⇒ 红线破裂，判词不作数：")
            for h in REDLINE["hits"][:8]:
                print("   " + h)
            srv.shutdown()
            return 2
    home_last = home_gauge("end")
    chk("零真发：一个包都不出本机（假端点只绑 127.0.0.1）", not REDLINE["hits"],
        "; ".join(REDLINE["hits"][:3]) or "三进程 stdout 里无非回环 http(s) 端点")
    chk("整跑 ~/.zbot 三点一致", home_first == home_before == home_last,
        "%s / %s / %s" % (home_first, home_before, home_last))
    srv.shutdown()
    failed = [n for n, ok, _ in CHECKS if not ok]
    print("RUN|CHECKS=%d|FAILED=%d|%s" % (len(CHECKS), len(failed), ",".join(failed) or "全过"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
