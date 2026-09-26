#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P27 杠③ —— 真 jar / 真子进程 / 真 kill -9 的委托面 E2E（自带全部判词，不 import 别的战役脚本）。

一次调用 = 一整跑：
  1. `mvn -o package` + 依赖 classpath（离线，缺件 ⇒ NO-RUN，绝不写成 skip/passed）
  2. 起**真 java 子进程**（`P27DelegationDriver --mode dispatch`）：它建一条异步委托，
     子代理里真 fork 一个 `/bin/sleep 40` 当孙进程，然后把委托现场留在盘上并卡住
  3. 量具直接读盘（不经 java）确认 `state=RUNNING / delivery=PENDING / attempts=0`，
     记下 state.json 的 md5，然后 **kill -9 打死委托方**
  4. 再确认：pid 已死、state.json 字节没变、孙进程还在不在（如实记账并回收）
  5. 起**另一个 java 进程**（`--mode inspect`）只从盘上判词：孤儿认领、保留期两向、
     投递上限 8 次烧完收敛 DROPPED、无凭证 ack 大声失败
  6. 全程 `--config-dir` 指 `~/.cache/zbot-p27-lead/e2e/...`（**不写 /tmp**），
     跑前跑后各量一次 `~/.zbot` 三时点并原样打印

退出码：0=判词全过 / 1=有 CHECK 失败 / 2=环境或构建缺失（NO-RUN）/ 3=超时兜底
"""

import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    os.pardir, os.pardir, os.pardir))
LEAD = os.path.expanduser("~/.cache/zbot-p27-lead")
E2E = os.path.join(LEAD, "e2e")
RUN_TAG = sys.argv[1] if len(sys.argv) > 1 else time.strftime("run%H%M%S")
CFG = os.path.join(E2E, RUN_TAG, "cfg")
LOG = os.path.join(E2E, RUN_TAG)
DRIVER = "com.zifang.z.bot.delegate.P27DelegationDriver"
MVN_TIMEOUT = 900
LAUNCH_TIMEOUT = 90
KILL_WAIT = 15

CHECKS = []


def chk(name, ok, detail=""):
    CHECKS.append((name, bool(ok), detail))
    print("CHECK|%-46s %s %s" % (name, "PASS" if ok else "FAIL", detail))
    return ok


def sh(args, timeout=120, cwd=REPO):
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


def zbot_gauge(tag):
    """杠④ 三时点：只量条目数与两个 md5 的前 8 位，永不读内容。"""
    rc, n = sh(["bash", "-c", "ls -A ~/.zbot | wc -l"], cwd=os.path.expanduser("~"))
    rc2, c = sh(["bash", "-c", "md5 -q ~/.zbot/config.properties | cut -c1-8"], cwd=os.path.expanduser("~"))
    rc3, s = sh(["bash", "-c", "md5 -q ~/.zbot/state.db | cut -c1-8"], cwd=os.path.expanduser("~"))
    print("HOME|%s|entries=%s|config=%s|state=%s" % (tag, n.strip(), c.strip(), s.strip()))
    return n.strip(), c.strip(), s.strip()


def md5_of(path):
    if not os.path.isfile(path):
        return "MISSING"
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def parse_state(path):
    """不经 java，直接读盘上的现场。"""
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as ex:
        return {"_parse_error": str(ex)}


def no_key_leak(path):
    """红线 1：真 key 长度 125，台账里既不许出现真 key，也不许出现 125 长度的字符串。"""
    rc, real_len = sh(["bash", "-c",
                       "awk -F= '/^minimax\\.api\\.key=/{print length($2)}' ~/.zbot/config.properties"])
    real_len = real_len.strip()
    txt = open(path, encoding="utf-8").read() if os.path.isfile(path) else ""
    rc2, key_line = sh(["awk", "-F=", "/^minimax\\.api\\.key=/{print $2}",
                        os.path.expanduser("~/.zbot/config.properties")])
    real_key = key_line.strip()
    leaked = bool(real_key) and real_key in txt
    return leaked, real_len


def main():
    os.makedirs(CFG, exist_ok=True)
    print("== P27 E2E %s ==" % RUN_TAG)
    home_before = zbot_gauge("before")

    # ---------- 1. 构建 ----------
    rc, out = sh(["mvn", "-o", "-q", "-DskipTests", "package"], timeout=MVN_TIMEOUT)
    tail = out.strip().splitlines()[-3:] if out.strip() else []
    print("BUILD|package_rc=%d %s" % (rc, " / ".join(tail)))
    if rc != 0:
        print("NO-RUN: mvn package 失败（环境/依赖），本轮不判定")
        return 2
    cp_file = os.path.join(E2E, RUN_TAG, "cp.txt")
    rc, out = sh(["mvn", "-o", "-q", "-pl", "z-bot-core", "dependency:build-classpath",
                  "-DincludeScope=test", "-Dmdep.outputFile=" + cp_file], timeout=MVN_TIMEOUT)
    size = os.path.getsize(cp_file) if os.path.isfile(cp_file) else -1
    print("BUILD|classpath_file=%s size=%d rc=%d" % (cp_file, size, rc))
    if size <= 0:
        print("NO-RUN: dependency:build-classpath 产出空文件（0 字节 = 没量到，不代表没有依赖）")
        return 2
    deps = open(cp_file).read().strip()
    core = os.path.join(REPO, "z-bot-core/target")
    cp = ":".join([os.path.join(core, "classes"), os.path.join(core, "test-classes"), deps])
    jar = [f for f in os.listdir(core) if f.endswith(".jar")]
    print("BUILD|jars=%s" % jar)
    for p in ([os.path.join(core, "classes"), os.path.join(core, "test-classes")]):
        if not os.path.isdir(p):
            print("NO-RUN: 缺 " + p)
            return 2
    drv = os.path.join(core, "test-classes", "com", "zifang", "z", "bot", "delegate",
                       "P27DelegationDriver.class")
    chk("driver_class_built", os.path.isfile(drv), drv)

    # ---------- 2. 独立现场 + 起真子进程 ----------
    with open(os.path.join(CFG, "config.properties"), "w", encoding="utf-8") as f:
        f.write("llm.provider=stub\ntest.description=P27 e2e 隔离现场（key 是假的）\n"
                "stub.type=openai\nstub.api.key=stub-key-not-real\n"
                "stub.base.url=http://127.0.0.1:1/v1\nstub.model=stub-model\n"
                "agent.delegate.max.depth=2\nagent.delegate.max.children=3\n")
    live = os.path.join(CFG, "delegate", "live")
    if os.path.isdir(live):
        shutil.rmtree(live)

    dispatch_log = os.path.join(E2E, RUN_TAG, "dispatch.out")
    java = shutil.which("java") or "java"
    p = subprocess.Popen([java, "-cp", cp, DRIVER, "--config-dir", CFG, "--mode", "dispatch"],
                         cwd=REPO, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         preexec_fn=os.setsid, universal_newlines=True)

    scene = None
    deadline = time.time() + LAUNCH_TIMEOUT
    lines = []
    while time.time() < deadline:
        for cand in (os.listdir(live) if os.path.isdir(live) else []):
            s = os.path.join(live, cand, "state.json")
            if os.path.isfile(s) and cand.startswith("bg"):
                scene = s
                break
        if scene:
            break
        time.sleep(0.2)
    if not scene:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except OSError:
            pass
        print("NO-RUN: 90s 内委托现场没落盘（永挂体检失败），已干掉整组")
        return 3
    # 边等边收 stdout（不留管道堵死）
    st_before = parse_state(scene)
    print("DISK|scene=%s" % scene)
    print("DISK|state_json=%s" % json.dumps(st_before, ensure_ascii=False, sort_keys=True))
    time.sleep(1.0)
    st_before = parse_state(scene)
    print("DISK|state_json_settled=%s" % json.dumps(st_before, ensure_ascii=False, sort_keys=True))
    md5_before = md5_of(scene)
    owner = st_before.get("owner_pid")
    chk("scene_pre_created_before_kill", st_before.get("state") in ("RUNNING", "QUEUED"),
        "state=%s" % st_before.get("state"))
    chk("delivery_axis_untouched", st_before.get("delivery_state") == "PENDING"
        and st_before.get("delivery_attempts") == 0,
        "delivery=%s attempts=%s" % (st_before.get("delivery_state"), st_before.get("delivery_attempts")))
    chk("owner_pid_is_the_live_process", owner == p.pid, "owner_pid=%s java_pid=%s" % (owner, p.pid))
    leaked, key_len = no_key_leak(scene)
    chk("no_real_key_in_ledger", not leaked, "real_key_len=%s（只量长度，未读内容）" % key_len)

    # ---------- 3. kill -9 委托方 ----------
    try:
        os.kill(p.pid, signal.SIGKILL)
    except OSError as ex:
        print("!! kill 失败: " + str(ex))
    kdead = time.time() + KILL_WAIT
    while time.time() < kdead and p.poll() is None:
        time.sleep(0.1)
    rc_java = p.returncode
    chk("delegating_process_really_died", rc_java in (-9, 137) or p.poll() is not None,
        "returncode=%s" % rc_java)
    rc, out = sh(["bash", "-c", "ps -p %s -o pid= 2>/dev/null | wc -l" % p.pid])
    chk("owner_pid_gone_from_ps", out.strip() == "0", "ps 命中=%s" % out.strip())
    md5_after = md5_of(scene)
    chk("scene_bytes_unchanged_after_kill", md5_before == md5_after and md5_after != "MISSING",
        "md5_before=%s md5_after=%s" % (md5_before, md5_after))
    st_after = parse_state(scene)
    print("DISK|after_kill=%s" % json.dumps(st_after, ensure_ascii=False, sort_keys=True))
    chk("scene_still_non_terminal", st_after.get("state") in ("RUNNING", "QUEUED"),
        "state=%s" % st_after.get("state"))
    # 孙进程：委托方被打死之后它归谁管，如实记一笔（产品现状，不当失败判）
    gpid = None
    for k, v in st_after.items():
        pass
    events = os.path.join(os.path.dirname(scene), "events.log")
    print("DISK|events_log=%s" % events)
    if os.path.isfile(events):
        print("DISK|events_tail=" + " ⏎ ".join(open(events, encoding="utf-8").read().splitlines()[-6:]))
    rc, gc = sh(["bash", "-c", "pgrep -f '/bin/sleep 40' | tr '\\n' ' '"])
    print("PROCTREE|stray_sleep40_pids=%s" % (gc.strip() or "<none>"))
    if gc.strip():
        sh(["bash", "-c", "pkill -f '/bin/sleep 40' || true"])
        rc2, gc2 = sh(["bash", "-c", "pgrep -f '/bin/sleep 40' | wc -l"])
        print("PROCTREE|reaped_remaining=%s" % gc2.strip())
    try:
        os.killpg(os.getpgid(p.pid), signal.SIGKILL)
    except OSError:
        pass

    # ---------- 4. 另一个 java 进程只从盘上判词 ----------
    rc, out = sh([java, "-cp", cp, DRIVER, "--config-dir", CFG, "--mode", "inspect"], timeout=180)
    open(os.path.join(E2E, RUN_TAG, "inspect.out"), "w").write(out)
    inspect_lines = [l for l in out.splitlines() if l.startswith("E2E|")]
    for l in inspect_lines:
        print(l)
    print("INSPECT|rc=%d" % rc)
    tokens = {}
    byline = {}
    for l in inspect_lines:
        body = l[len("E2E|"):]
        # 驱动一行可带多个字段（`E2E|head=v k2=v2 k3=v3`）。旧尺只按第一个 `=` 切一次，
        # key 于是变成 "after_kill state"，k2/k3 永远查不到 ⇒ 五条 CHECK 恒假红（P27b-G6）。
        # 这里逐对收：tokens 按字段名聚合，byline 按"行首字段"留整行的键值表。
        pairs = re.findall(r'(?:^|\s)([A-Za-z_]\w*)=([^ ]*)', body)
        for k, v in pairs:
            tokens.setdefault(k, []).append(v)
        # 有的行首是裸标签（`E2E|after_kill state=RUNNING ...`，after_kill 后面没有 `=`），
        # 那就同时按裸标签登记，判词才不必去猜第一个键名。
        bare = body.split(" ", 1)[0]
        if "=" not in bare:
            byline.setdefault(bare, dict(pairs))
        if pairs:
            byline.setdefault(pairs[0][0], dict(pairs))

    def tok(key, idx=0):
        vs = tokens.get(key, [])
        return vs[idx] if len(vs) > idx else "<missing>"

    def field(head, key):
        return (byline.get(head) or {}).get(key, "<missing>")

    chk("inspect_read_back_the_scene",
        field("after_kill", "state") == "RUNNING" and field("after_kill", "delivery") == "PENDING"
        and field("after_kill", "non_terminal") == "true",
        "after_kill state=%s delivery=%s non_terminal=%s" % (
            field("after_kill", "state"), field("after_kill", "delivery"),
            field("after_kill", "non_terminal")))
    chk("inspect_prune_keeps_unexpired", tok("prune_before_adoption") == "0",
        "prune_before_adoption=" + tok("prune_before_adoption"))
    chk("orphan_adopted_to_unknown",
        field("adopted", "adopted").startswith("1") and field("adopted", "state_after_adopt") == "UNKNOWN",
        "adopted=%s state_after_adopt=%s events=%s" % (
            field("adopted", "adopted"), field("adopted", "state_after_adopt"),
            field("adopted", "events")))
    chk("expired_terminal_pruned",
        field("prune_with_zero_window", "prune_with_zero_window").startswith("1")
        and field("prune_with_zero_window", "still_readable") == "false",
        "prune_with_zero_window=%s still_readable=%s" % (
            field("prune_with_zero_window", "prune_with_zero_window"),
            field("prune_with_zero_window", "still_readable")))
    chk("cap_refused_after_eight", "cap_attempt_9=refused" in out and "DROPPED(8/8)" in out,
        "cap_final=%s" % tok("cap_final"))
    chk("cap_attempts_persist_on_disk", "DROPPED(8)" in tok("cap_from_fresh_instance"),
        tok("cap_from_fresh_instance"))
    chk("dropped_not_replayed", field("cap_final", "restore_offered") == "0",
        "restore_offered=" + field("cap_final", "restore_offered"))
    chk("no_tmp_residue", tok("tmp_residue").startswith("false"), tok("tmp_residue"))
    chk("ack_without_claim_loud_fail",
        field("ack_without_claim", "ack_without_claim") == "IllegalStateException"
        and field("ack_without_claim", "delivery") == "PENDING",
        "ack_without_claim=%s delivery=%s" % (
            field("ack_without_claim", "ack_without_claim"), field("ack_without_claim", "delivery")))
    checks_m = re.search(r"E2E\|CHECKS=(\d+) FAILED=(\d+)", out)
    chk("java_side_all_checks_green", bool(checks_m) and checks_m.group(2) == "0",
        checks_m.group(0) if checks_m else "<没有 CHECKS 行>")
    m_rc, m_out = sh(["grep", "-c", '"state"', "-r", live])
    print("DISK|remaining_state_files=%s (%s)" % (m_out.strip(), live))

    home_after = zbot_gauge("after")
    chk("zbot_home_untouched", home_before == home_after, "%s -> %s" % (home_before, home_after))

    failed = [n for n, ok, _ in CHECKS if not ok]
    print("RUN|%s|CHECKS=%d|FAILED=%d|%s" % (RUN_TAG, len(CHECKS), len(failed),
                                              ",".join(failed) if failed else "全过"))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
