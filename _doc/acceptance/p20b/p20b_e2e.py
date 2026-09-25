#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P20b 真进程 E2E：起真 JVM + 真 MCP server 子进程，跑三件事并把读数判红/判绿：

  1. toolsets 段：z-bot 侧声明层装配后审计必须干净（红线 2）。
  2. cap 段：单工具结果上限 + 溢出落盘（上限走注册表，全文落 $ZBOT_HOME/tool-results）。
  3. mcp 段：真 stdio server 被 kill 之后 ——
       * check_fn 的 TTL/宽限用<b>真时间</b>量（不许把 timeout 调大当通过）；
       * 注销在<b>同一个 JVM、同一个 Toolkit 实例</b>内完成，并且现场留了一台
         "没被动过的第二 server"（拿重启进程糊过去的做法会把第二台一起弄没）；
       * 生产入口 McpManager.reload() 之后旧 server 的工具名真的从 getToolNames() 消失。

红线 1：~/.zbot 只读计数与 md5，不读内容；本轮所有配置都指临时目录，
        key 只用 stub-key-not-real，并在<b>同一条</b>判定里反向钉住"stub key 真进了产物"。

复算: python3 -u _doc/acceptance/p20b/p20b_e2e.py [--only=toolsets,cap,mcp]
"""
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
LOGS = os.path.join(HERE, "logs")
DRIVER = os.path.join(HERE, "P20bToolDriver.java")
SERVER = os.path.join(HERE, "mcp_stub_server.py")
STUB_KEY = "stub-key-not-real"
HOME_REAL = os.path.expanduser("~/.zbot")
RESULTS = []


def out(msg=""):
    sys.stdout.write(str(msg) + "\n")
    sys.stdout.flush()


def run(cmd, cwd=ZBOT, env=None, log=None, timeout=1_800):
    t0 = time.time()
    proc = subprocess.run(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)
    text = proc.stdout.decode("utf-8", "replace")
    if log:
        if not os.path.isdir(LOGS):
            os.makedirs(LOGS)
        with io.open(os.path.join(LOGS, log), "w", encoding="utf-8") as fh:
            fh.write("$ %s\n  cwd=%s exit=%d wall=%.1fs\n%s\n"
                     % (" ".join(cmd), cwd, proc.returncode, time.time() - t0, text))
    return proc.returncode, text


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    out("  %-58s %s %s" % (name, "PASS" if ok else "FAIL", detail))


def kv_of(text):
    """驱动打印的是 KEY<TAB>VALUE；同名取最后一次。"""
    kv = {}
    for line in text.splitlines():
        if "\t" in line:
            k, _, v = line.partition("\t")
            kv[k.strip()] = v.strip()
    return kv


def is_true(v):
    """驱动是 Java，布尔值打印成小写 true/false；缺失/空串一律判假（不放宽）。"""
    return str(v).strip().lower() == "true"


def is_false(v):
    return str(v).strip().lower() == "false"


def zbot_home_state():
    items = sorted(os.listdir(HOME_REAL)) if os.path.isdir(HOME_REAL) else []
    md = {}
    for f in ("config.properties", "state.db"):
        p = os.path.join(HOME_REAL, f)
        # 只算哈希，不读出来打印，也不复制走（红线 1）
        md[f] = hashlib.md5(open(p, "rb").read()).hexdigest()[:8] if os.path.exists(p) else "MISSING"
    return len(items), md


def build():
    rc, t = run(["mvn", "-o", "-pl", "z-bot-core", "package", "-DskipTests"],
                log="build_package.log")
    check("B1 mvn package -DskipTests 退出码 0", rc == 0, "rc=%d" % rc)
    jars = [f for f in os.listdir(os.path.join(ZBOT, "z-bot-core", "target"))
            if f.endswith(".jar") and "sources" not in f and not f.startswith("original-")]
    check("B2 产物 jar 存在", bool(jars), ",".join(jars))
    # original-*.jar 是 shade 插件留的薄壳备份，不作为被测产物
    jar = os.path.join(ZBOT, "z-bot-core", "target", sorted(jars)[0]) if jars else ""
    cpfile = os.path.join(ZBOT, "z-bot-core", "target", "p20b-classpath.txt")
    rc2, _ = run(["mvn", "-o", "-q", "-pl", "z-bot-core", "dependency:build-classpath",
                  "-Dmdep.outputFile=" + cpfile], log="build_classpath.log")
    deps = io.open(cpfile, encoding="utf-8").read().strip() if os.path.exists(cpfile) else ""
    check("B3 依赖 classpath 取到", rc2 == 0 and len(deps) > 50, "rc=%d len=%d" % (rc2, len(deps)))
    cp = os.pathsep.join([p for p in [jar, deps] if p])
    rc3, t3 = run(["javac", "-cp", cp, "-d", os.path.join(ZBOT, "z-bot-core", "target",
                                                          "p20b-driver-classes"), DRIVER],
                  log="build_driver.log")
    check("B4 驱动 javac 通过", rc3 == 0, "rc=%d" % rc3)
    if rc3 != 0:
        out(t3[-2000:])
    return os.pathsep.join([os.path.join(ZBOT, "z-bot-core", "target", "p20b-driver-classes"), cp])


def make_home():
    home = tempfile.mkdtemp(prefix="zbot-p20b-e2e-")
    with io.open(os.path.join(home, "config.properties"), "w", encoding="utf-8") as fh:
        fh.write("provider=minimax\nminimax.api.key=%s\nminimax.model=MiniMax-M2\n" % STUB_KEY)
    os.makedirs(os.path.join(home, "workspace"))
    os.makedirs(os.path.join(home, "tool-results"))
    return home


def judge_toolsets(kv):
    check("T1 装配后无空的能力子集（红线 2）",
          kv.get("EMPTY_CAPABILITY_AUDIT") == "[]", kv.get("EMPTY_CAPABILITY_AUDIT", ""))
    check("T2 注册表里没有清单外的 toolset",
          kv.get("UNDECLARED_TOOLSETS") == "[]", kv.get("UNDECLARED_TOOLSETS", ""))
    check("T3 清单里的工具名全部真被注册",
          kv.get("MANIFEST_NEVER_REGISTERED") == "[]", kv.get("MANIFEST_NEVER_REGISTERED", ""))
    flat = [v for k, v in sorted(kv.items()) if k.startswith("TOOLSET_MEMBERS_")]
    blob = " ".join(flat)
    check("T4 core/file/exec/net 四个子集成员符合清单",
          blob.count("echo") == 1 and "read_file" in blob and "mvn_build" in blob
          and "curl_test" in blob, blob[:150])
    check("T5 只读工具声明可并行、写/执行/出网不可",
          "echo=ro" in kv.get("PARALLEL_SAFE", "") and "write_file=rw" in kv.get("PARALLEL_SAFE", "")
          and "exec=rw" in kv.get("PARALLEL_SAFE", "") and "curl_test=rw" in kv.get("PARALLEL_SAFE", ""),
          kv.get("PARALLEL_SAFE", "")[:120])
    check("T6 摘掉 exec 子集后审计把它点名（审计不是写死的）",
          kv.get("AUDIT_AFTER_DROP_EXEC") == "[exec]", kv.get("AUDIT_AFTER_DROP_EXEC", ""))
    check("T7 内建工具没落进兜底槽", "builtin=" not in blob, blob[:80])


def judge_cap(kv):
    resolved = kv.get("CAP_RESOLVED_OVERFLOW_DIR", "")
    check("C1 溢出目录来自 $ZBOT_HOME（不是写死的 ~/.zbot）",
          resolved == os.path.join(HOME, "tool-results")
          and HOME_REAL not in os.path.expanduser(resolved),
          resolved)
    check("C2 上下文里只留预览（120 字符）",
          kv.get("CAP_PREVIEW_LEN") == "120", kv.get("CAP_PREVIEW_LEN", ""))
    check("C3 回执说明走了注册表上限", "超单工具上限 1000" in kv.get("CAP_NOTE", ""),
          kv.get("CAP_NOTE", "")[:90])
    check("C4 落盘文件是全文且 md5 与原文一致",
          is_true(kv.get("CAP_FILE_IS_FULL_BODY"))
          and kv.get("CAP_FILE_MD5") == kv.get("CAP_BODY_MD5"),
          "%s vs %s len=%s" % (kv.get("CAP_FILE_MD5"), kv.get("CAP_BODY_MD5"), kv.get("CAP_FILE_LEN")))
    check("C5 回执里的路径就是那个文件",
          is_true(kv.get("CAP_PATH_HINTED_IN_RESULT")), kv.get("CAP_FILE_NAME", ""))
    check("C6 工具自声明的上限压过全局值（注册表口径）",
          kv.get("CAP2_DECLARED") == "300" and kv.get("CAP2_PREVIEW_LEN") == "300",
          "%s/%s" % (kv.get("CAP2_DECLARED"), kv.get("CAP2_PREVIEW_LEN")))
    check("C7 声明 UNBOUNDED 的结果一字不改",
          is_true(kv.get("CAP3_UNBOUNDED_INTACT")), kv.get("CAP3_UNBOUNDED_INTACT", ""))


def i(v, d=-1):
    try:
        return int(v)
    except (TypeError, ValueError):
        return d


def judge_mcp(kv, logtext):
    samples = []
    for line in logtext.splitlines():
        if line.startswith("SEG1_SAMPLE\t"):
            e, ex, pb = line.partition("\t")[2].split("|")
            samples.append((int(e), is_true(ex), int(pb.replace("probes=", ""))))
    check("M1 两个真 stdio server 都注册上了",
          "mcp-alpha-t1" in kv.get("SEG1_NAMES_AFTER_START", "")
          and "mcp-bravo-u1" in kv.get("SEG1_NAMES_AFTER_START", ""),
          kv.get("SEG1_NAMES_AFTER_START", ""))
    check("M2 server 工具各归各的 mcp-<server> toolset",
          kv.get("SEG1_TOOLSET_OF_T1") == "mcp-alpha", kv.get("SEG1_TOOLSET_OF_T1", ""))
    check("M3 kill 命令真退出且传输层确认进程没了",
          kv.get("SEG1_KILL_RC") == "0" and is_true(kv.get("SEG1_DEAD_CONFIRMED")),
          "rc=%s wait=%sms" % (kv.get("SEG1_KILL_RC"), kv.get("SEG1_DEAD_WAIT_MS")))
    check("M4 首探发生且 TTL 窗内吃缓存（真时间）",
          i(kv.get("SEG1_PROBE_CALLS_AT_ANCHOR")) == 1
          and len(samples) >= 1 and samples[0][2] == 1 and samples[0][0] < 30_000,
          "anchor=%s samples=%s" % (kv.get("SEG1_PROBE_CALLS_AT_ANCHOR"), samples[:2]))
    mid = [s for s in samples if 30_000 <= s[0] < 60_000]
    check("M5 越过 TTL 后重探、但宽限窗内不摘工具（抖动不写缓存）",
          bool(mid) and all(s[1] for s in mid) and max(s[2] for s in mid) >= 2,
          "30-60s 样本=%s" % (mid[:3],))
    hidden = i(kv.get("SEG1_FIRST_HIDDEN_MS"))
    check("M6 持续失败过了 60s 宽限窗必须把工具从外发清单摘掉（真时间读数）",
          60_000 <= hidden <= 90_000, "first_hidden=%dms" % hidden)
    check("M7 摘掉的是外发，不是槽位：注册清单仍可见、执行报调用失败而非未找到",
          is_true(kv.get("SEG1_HIDDEN_STILL_REGISTERED"))
          and is_true(kv.get("SEG1_EXECUTE_WHILE_HIDDEN_ERR"))
          and is_false(kv.get("SEG1_EXECUTE_WHILE_HIDDEN_NOT_FOUND")),
          "reg=%s err=%s nf=%s" % (kv.get("SEG1_HIDDEN_STILL_REGISTERED"),
                                   kv.get("SEG1_EXECUTE_WHILE_HIDDEN_ERR"),
                                   kv.get("SEG1_EXECUTE_WHILE_HIDDEN_NOT_FOUND")))
    check("M8 没被动过的第二 server 全程可用",
          is_true(kv.get("SEG1_BRAVO_STILL_EXPOSED")), kv.get("SEG1_BRAVO_STILL_EXPOSED", ""))
    check("M9 注销发生在同一个 JVM 的同一个 Toolkit 实例里",
          kv.get("JVM_PID") == kv.get("SEG1_JVM_PID_AT_END")
          and kv.get("SEG1_TK_IDENTITY") == kv.get("SEG1_TK_IDENTITY_AT_END")
          and kv.get("JVM_PID") == kv.get("SEG2_JVM_PID"),
          "pid=%s→%s id=%s→%s" % (kv.get("JVM_PID"), kv.get("SEG1_JVM_PID_AT_END"),
                                  kv.get("SEG1_TK_IDENTITY"), kv.get("SEG1_TK_IDENTITY_AT_END")))
    check("M10 反向断言（桥级）：unregister 之后旧工具名从 getToolNames 消失",
          is_true(kv.get("SEG1_ALPHA_GONE")) and is_true(kv.get("SEG1_BRAVO_INTACT"))
          and "mcp-alpha" not in kv.get("SEG1_NAMES_AFTER_UNREGISTER", ""),
          "removed=%s after=%s" % (kv.get("SEG1_REMOVED"), kv.get("SEG1_NAMES_AFTER_UNREGISTER")))
    check("M11 反向断言（生产入口 reload）：改名后的 server 不留旧名字",
          is_true(kv.get("SEG2_OLD_GONE")) and is_true(kv.get("SEG2_NEW_PRESENT"))
          and is_true(kv.get("SEG2_BRAVO_INTACT")),
          "before=%s after=%s total=%s" % (kv.get("SEG2_NAMES_BEFORE"),
                                           kv.get("SEG2_NAMES_AFTER"), kv.get("SEG2_RELOAD_TOTAL")))
    check("M12 stopAll 之后注册表与外发清单都清空",
          kv.get("SEG2_NAMES_AFTER_STOP") == "[]" and kv.get("SEG2_EXPOSED_AFTER_STOP") == "[]",
          "%s / %s" % (kv.get("SEG2_NAMES_AFTER_STOP"), kv.get("SEG2_EXPOSED_AFTER_STOP")))


def key_leak_scan():
    """产物里不许出现"像真 key"的长串（真 key 125 字符，stub 只有 19）；同一条反向钉住 stub 真进了产物。"""
    hits = []
    stub_seen = 0
    for root, dirs, files in os.walk(HERE):
        dirs[:] = [d for d in dirs if d != "__pycache__"]
        for fn in files:
            p = os.path.join(root, fn)
            try:
                data = io.open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            if re.search(r"api\.key[=\" :]+[A-Za-z0-9_\-]{60,}", data):
                hits.append(os.path.relpath(p, ZBOT))
            stub_seen += data.count(STUB_KEY)
    for root, dirs, files in os.walk(HOME):
        for fn in files:
            data = io.open(os.path.join(root, fn), encoding="utf-8", errors="replace").read()
            if re.search(r"api\.key[=\" :]+[A-Za-z0-9_\-]{60,}", data):
                hits.append(os.path.join("TMPHOME", fn))
            stub_seen += data.count(STUB_KEY)
    check("K1 产物里没有任何长 api.key（真 key 125 字符）泄漏", not hits, ",".join(hits[:3]))
    check("K2 同一条反向钉：stub key 真进了产物（临时配置 + 驱动读数）",
          stub_seen >= 3, "stub 出现 %d 次" % stub_seen)


def main():
    global HOME
    only = ["toolsets", "cap", "mcp"]
    for arg in sys.argv[1:]:
        if arg.startswith("--only="):
            only = arg.split("=", 1)[1].split(",")
    if not os.path.isdir(LOGS):
        os.makedirs(LOGS)
    before_n, before_md = zbot_home_state()
    out("== ~/.zbot 跑前: %d 项 %s" % (before_n, before_md))
    cp = build()
    HOME = make_home()
    env = dict(os.environ)
    env["ZBOT_HOME"] = HOME
    env["PYTHONUTF8"] = "1"
    try:
        for phase in only:
            out("\n== 阶段 %s (home=%s)" % (phase, HOME))
            logname = "e2e_%s.log" % phase
            rc, text = run(["java", "-cp", cp, "P20bToolDriver", phase, HOME, HERE],
                           env=env, log=logname, timeout=600)
            kv = kv_of(text)
            check("P0 %s 阶段驱动退出码 0" % phase, rc == 0,
                  "rc=%d %s" % (rc, text.strip().splitlines()[-1][:120] if text.strip() else ""))
            if "toolsets" == phase:
                judge_toolsets(kv)
            elif "cap" == phase:
                judge_cap(kv)
            elif "mcp" == phase:
                judge_mcp(kv, text)
    finally:
        after_n, after_md = zbot_home_state()
        out("\n== ~/.zbot 跑后: %d 项 %s" % (after_n, after_md))
        check("R1 ~/.zbot 项数未变（应为 8）", after_n == before_n == 8,
              "%d → %d" % (before_n, after_n))
        check("R2 ~/.zbot/config.properties md5 前缀未变", after_md == before_md, str(after_md))
        key_leak_scan()
        out("  临时目录留档: %s" % HOME)
    fails = [r for r in RESULTS if not r[1]]
    out("\n== 合计 %d 条判定，PASS=%d FAIL=%d" % (len(RESULTS), len(RESULTS) - len(fails), len(fails)))
    for n, ok, d in fails:
        out("   FAIL %s  %s" % (n, d))
    return 1 if fails else 0


HOME = None

if __name__ == "__main__":
    sys.exit(main())
