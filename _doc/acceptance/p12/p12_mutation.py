#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P12（主循环节律包：中断 / steer / 预算 refund / prompt cache 冻结）变异检验。

骨架与纪律照 _doc/acceptance/p11c/p11c_mutation.py（不 import 它，免得两批结论互相拖累）：
  * 入口 `if __name__ == "__main__": sys.exit(main())`，import 不执行任何事；
  * 注入前逐条校验锚点出现次数；漂了 FATAL 退出，一个源文件都不碰；
  * **互斥锁在 git 公共目录**（`$(git rev-parse --git-common-dir)/zbot-mutlock`），
    跨 worktree 才有效；**只用 fcntl.flock(LOCK_EX|LOCK_NB)**。
    为什么不用 fcntl.lockf：主编 09-26 本机实测 —— 真 JVM 攥着同一文件的 tryLock 时，
    python 的 lockf(F_TEST)/lockf(F_TLOCK) 一律回 ACQUIRED，拿它当互斥就是空跑。
    这条在本脚本里是**双向实测**的：`--lock-probe` 会攥住 flock 再跑一遍本脚本，
    要求它当场拒跑且全量源码 md5 不变，松手后要求它照常拿得到；
  * 判定只认 surefire XML 里的**具名 testcase**；mvn 退出码非 0 不算证据；编译不过算 BROKEN；
  * 每个变异体跑完按内存里的原文逐字节还原，收尾核对全量 md5（SRC_MD5_STABLE=yes 才收）；
  * LEDGER.tsv 由本脚本机械写出；PARTIAL / GREEN-BUT-MUTATED 如实记，不回头改期望集。

本期特别要求（派单里点名的那条红线）：
  M1/M1b 是「把运行时上下文搬回 system prompt」的两个方向 —— 杠① 里那条被设计变更撞红的
  旧断言（BotAgentTest.newSessionAndSwitchRestoreHistory）改修成「用户原文逐字仍在 user 消息里
  + 上下文块不在 system prompt 里」两半之后，**必须**被这两支打得判红。搬回去还全绿，
  那条守卫就是空的。
  M6 是工具侧检查点：把看门狗（`InterruptScope.watch`）摘掉之后，
  ① 具名单测 `execAbortsInFlightAndTakesTheWholeProcessTreeDown` 必红，
  ② **真进程层** p12_e2e.py 的 stop 段也必须变红/变慢（`--with-e2e`，代价是多一次 package）。

复算:
  python3 -u _doc/acceptance/p12/p12_mutation.py               # 全量
  python3 -u _doc/acceptance/p12/p12_mutation.py M2 M6         # 按 id 子串选
  python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e M6 # 连真进程层一起判
  python3 -u _doc/acceptance/p12/p12_mutation.py --lock-probe  # 互斥锁双向实测
"""
import fcntl
import hashlib
import json
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
LOGS = os.path.join(HERE, "logs")
LEDGER = os.path.join(HERE, "LEDGER.tsv")
E2E = os.path.join(HERE, "p12_e2e.py")

AGENT = "z-bot-core/src/main/java/com/zifang/z/bot/agent/"
SRC = {
    "bot": AGENT + "BotAgent.java",
    "ledger": AGENT + "BudgetLedger.java",
    "mem": AGENT + "ConversationMemory.java",
    "scope": AGENT + "InterruptScope.java",
    "tools": "z-bot-core/src/main/java/com/zifang/z/bot/tool/BuiltinTools.java",
    "del": "z-bot-core/src/main/java/com/zifang/z/bot/delegate/DelegateManager.java",
    "slash": "z-bot-core/src/main/java/com/zifang/z/bot/slash/SlashRegistry.java",
}

# 只跑这些类（全量 480+ 条在注入循环里跑不动，判定面按点名来）
TESTS = ",".join([
    "BotAgentTest", "BotAgentMemoryTest", "SystemPromptCacheFreezeTest",
    "P12RefundAndSteerGuardTest", "ToolSideInterruptTest", "AgentCoreP1Test",
    "DelegateTaskTest", "SlashRegistryTest",
])

HDR = "VOLATILE_CONTEXT_HEADER"

# (id, kind, 文件键, old, new, 期望锚点数, 点名期望红的具名 testcase, 说明)
MUTANTS = [
    ("M1 上下文块搬回 system prompt（附加式）", "mvn", "bot",
     "this.memory = new ConversationMemory(buildSystemPrompt(b.systemPrompt, b.toolkit, b.memoryStore));",
     "this.memory = new ConversationMemory(buildSystemPrompt(b.systemPrompt, b.toolkit, b.memoryStore)\n"
     "                + VOLATILE_CONTEXT_HEADER + \"\\n当前时间: 被搬回前缀的那一行\\n\" + VOLATILE_CONTEXT_FOOTER);",
     1,
     ["newSessionAndSwitchRestoreHistory",
      "volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt",
      "soulStaysInSystemPromptAndMemoryGoesToUserMessage"],
     "缓存前缀里混进每轮都变的东⻄＝击穿缓存；杠① 修好的那条断言第二半（上下文不许进 system）必须抓到它"),

    ("M1b 上下文块搬回 system prompt（替换式：user 只剩原文）", "mvn", "bot",
     "memory.add(Msg.user(withVolatileContext(mergeQueued(userMessage))));",
     "memory.add(Msg.user(mergeQueued(userMessage)));",
     1,
     ["newSessionAndSwitchRestoreHistory",
      "volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt",
      "soulStaysInSystemPromptAndMemoryGoesToUserMessage"],
     "反方向：user 消息里没有上下文头了。第一条断言（剥头之后逐字相等）必须因为找不到分隔符而红 —— "
     "它同时证明我把 assertEquals 换成的是「按协议剥头再逐字相等」，不是 contains"),

    ("M2 自动压缩不退款", "mvn", "bot",
     "long refunded = budgetLedger.refundTokens(freedTokensOfCompression(history, compressed));",
     "long refunded = 0L;",
     1,
     ["compressionRefundsBudgetAndKeepsTheLoopRunning"],
     "refund 不扣：累计 token 顶过上限之后主循环只能吃 kernel 的 grace 通道，"
     "那条测试把 graceCalls 设成 0 就是为了把这件事露出来"),

    ("M3 手动 /compress 不退款", "mvn", "bot",
     "long refunded = budgetLedger.refundTokens(freedTokensOfCompression(history, out));",
     "long refunded = 0L;",
     1,
     ["manualCompressRefundsIntoTheLedger"],
     "两条退款路径各自独立接线，少一条就是「/compress 之后账面一秒都没回本」"),

    ("M4 净占用不扣退款（账记了但不参与判定）", "mvn", "ledger",
     "long net = budget.tokensUsed() - refundedTokens;",
     "long net = budget.tokensUsed();",
     1,
     ["compressionRefundsBudgetAndKeepsTheLoopRunning"],
     "只把退款记在账上、判定还是累计值 —— 这是 refund 最容易「写了等于没写」的形态"),

    ("M5 硬中断不清 pending steer", "mvn", "bot",
     "context.interrupt().request(\"用户请求停止\");\n        context.steer().clear();",
     "context.interrupt().request(\"用户请求停止\");",
     1,
     ["hardStopDropsPendingSteerSoItCannotResurfaceNextTurn"],
     "用户已经叫停了，那条没注入的插话不许在下一轮以 [User steer] 冒出来"),

    ("M5b steer 不再是单槽", "mvn", "bot",
     "        context.steer().clear();\n        context.steer().add(message);\n    }",
     "        context.steer().add(message);\n    }",
     1,
     ["queueKeepsEveryEntryWhileSteerKeepsOnlyTheLast"],
     "反空跑另一半：/queue 的多条与 /steer 的单槽共用同一个 SteerQueue，"
     "只钉住「单槽」这一侧才能证明不是 drain 顺序碰巧对了"),

    ("M5c /queue 被写成单槽（前一条被悄悄吃掉）", "mvn", "bot",
     "    public void enqueue(String message) {\n        context.steer().add(message);",
     "    public void enqueue(String message) {\n        context.steer().clear();\n        context.steer().add(message);",
     1,
     ["queueKeepsEveryEntryWhileSteerKeepsOnlyTheLast"],
     "派单点名「不许留 0 消费者抽象」的另一半：语义写反就是排队功能形同虚设"),

    ("M7 steer 退回新插一条 user 消息", "mvn", "bot",
     "if (!memory.appendToLastToolResult(\"\\n\" + sb)) {\n            memory.add(Msg.user(sb.toString()));\n        }",
     "memory.add(Msg.user(sb.toString()));",
     1,
     ["steerDuringRunIsInjectedAtToolGap"],
     "hermes 语义是「追加到最后一条 tool 结果」；新插 user 消息会把 assistant 的 tool_calls 与"
     " tool 结果拆成非法消息形状"),

    ("M6 摘掉工具子进程看门狗", "mvn+e2e", "tools",
     "        InterruptScope.watch(p);",
     "        // MUTANT: 不登记看门狗",
     1,
     ["execAbortsInFlightAndTakesTheWholeProcessTreeDown"],
     "sleep/mvn 这类几十秒不吐一行的命令，读输出循环里的检查点根本执行不到；"
     "只有看门狗能在「工具正飞着」的时候把进程树端掉。这一支同时要求真进程层（p12_e2e 的 stop 段）变红"),

    ("M8 摘掉 exec 入口检查点（置位后仍起进程）", "mvn", "tools",
     "    private static ProcResult bash(Sandbox cwd, String script, int maxLines, int maxChars) throws Exception {\n        InterruptScope.checkpoint();",
     "    private static ProcResult bash(Sandbox cwd, String script, int maxLines, int maxChars) throws Exception {",
     1,
     ["execWithFlagAlreadySetStartsNoChildProcessAtAll"],
     "已经按了停止就不该再把子进程拉起来 —— 这一支由「对照组」钉住：同一条命令没置位时必须在进程表里数得到"),

    ("M9 摘掉文件读检查点", "mvn", "tools",
     "                        // 文件读检查点：入口一次 + 每读到一行一次（大文件在循环里断）\n                        InterruptScope.checkpoint();\n",
     "",
     1,
     ["readFileAbortsInsteadOfReadingTheWholeFile"],
     "文件读写那一处：读到大文件中途也得能断"),

    ("M10 摘掉文件写检查点", "mvn", "tools",
     "                        // 文件写检查点：真落盘之前问一次， stop 之后不再改沙箱\n                        InterruptScope.checkpoint();\n",
     "",
     1,
     ["writeFileAbortsBeforeTouchingTheSandbox"],
     "中止的一刻不许留下半个文件"),

    ("M11 摘掉 delegate 入口检查点", "mvn", "del",
     "        com.zifang.z.bot.agent.InterruptScope.checkpoint();\n        BotAgent child;",
     "        BotAgent child;",
     1,
     ["delegateRefusesToSpawnAChildAfterStopWasRequested"],
     "delegate 子循环入口：父已被叫停就不该再把子代理拉起来烧预算"),

    # ===== p12c 补的四支：派单点名要覆盖、上一棒没注入的两条判据 =====

    ("M12 system prompt 快照解冻（每步按盘重建）", "mvn", "bot",
     "            listener.onEvent(new StreamEvent.StepStart(step));\n            context.interrupt().checkpoint();",
     "            listener.onEvent(new StreamEvent.StepStart(step));\n"
     "            // MUTANT: 冻结摘掉——每一步都按盘上的当前内容重建骨架（SOUL 是 buildSystemPrompt 读的盘）\n"
     "            memory.setSystemPrompt(buildSystemPrompt(null, toolkit, memoryStore));\n"
     "            context.interrupt().checkpoint();",
     1,
     ["twoChatsInOneSessionSendByteIdenticalSystemPrompt",
      "everyStepOfAMultiStepTurnReplaysTheSameSystemBytes"],
     "P24 的地基就这一条：中途写盘（SOUL/记忆）不许进 system prompt。"
     "这一支同时是杠④/P24 交接件里「摘掉冻结⇒两轮 prompt 哈希必须变」的阳性对照"),

    ("M16 中断检查点退化成空操作", "mvn", "scope",
     "    public static void checkpoint() {\n        InterruptFlag f = CURRENT.get();\n"
     "        if (f != null) {\n            f.checkpoint();\n        }\n    }",
     "    public static void checkpoint() {\n        // MUTANT: 机制本体不再抛，置位了也照样往下跑\n    }",
     1,
     ["checkpointIsNoOpWithoutFlagAndThrowsAfterRequest",
      "readFileAbortsInsteadOfReadingTheWholeFile",
      "writeFileAbortsBeforeTouchingTheSandbox",
      "execWithFlagAlreadySetStartsNoChildProcessAtAll",
      "delegateRefusesToSpawnAChildAfterStopWasRequested",
      "interruptInsideParallelToolBatchAbortsRunNotFeedsModelError"],
     "所有工具侧检查点共用的那只眼睛；摘掉之后「工具正飞着时能断」这件事只剩看门狗一条腿"),

    ("M17 中断旗子从线程作用域退化成全局", "mvn", "scope",
     "    private static final ThreadLocal<InterruptFlag> CURRENT = new ThreadLocal<InterruptFlag>();",
     "    private static final ThreadLocal<InterruptFlag> CURRENT = new ThreadLocal<InterruptFlag>() {\n"
     "        // MUTANT: 全局一面旗 —— A 会话按停止会把 B 会话正在跑的工具一起打断（串台）\n"
     "        private volatile InterruptFlag shared;\n"
     "        @Override public InterruptFlag get() { return shared; }\n"
     "        @Override public void set(InterruptFlag f) { shared = f; }\n"
     "        @Override public void remove() { shared = null; }\n"
     "    };",
     1,
     ["flagsArePerThreadSoConcurrentSessionsDoNotCrossFire"],
     "派单原文「按执行线程定向，防并发会话串台」的正面对照：写成全局就必然串台"),

    ("M18 steer 排了不空（drain 之后塞回队列）", "mvn", "bot",
     "        List<String> pending = context.steer().drain();",
     "        List<String> pending = context.steer().drain();\n"
     "        for (String back : pending) {\n"
     "            context.steer().add(back); // MUTANT: 排空退化成读一眼，同一条插话每步再注入一遍\n"
     "        }",
     1,
     ["steerDuringRunIsInjectedAtToolGap", "steerIsDrainedExactlyOnce"],
     "drain 的「排空」侧上一棒没人钉：注入跑第一遍时如果全绿，就说明守卫是空的（如实记，不回填）"),

    ("M19 中断收口把这一轮的调用账吞掉", "mvn", "bot",
     "listener.onEvent(new StreamEvent.Done(aborted, context.budget().apiCalls(), null, null));",
     "listener.onEvent(new StreamEvent.Done(aborted, 0, null, null)); // MUTANT: 中断那轮上报 0 次调用",
     1,
     ["abortedTurnReportsTheCallsActuallyMade"],
     "工具侧打断不吞账：按了停止之后 /usage 与 Done 事件里的 apiCalls 必须是真打出去的那几次，"
     "不是 0（0 的话用户这一轮的开销凭空消失）"),
]

# 只用于「未覆盖」记账：这几支是冗余层，任何时长判据都分不出它与看门狗
UNCOVERED_NOTE = (
    "exec 读输出循环的逐行检查点、mvn_build 入口检查点：摘掉之后看门狗仍在 50ms 内端掉进程树，"
    "从「多久断」这一面量不出差别，属于第二道保险；如实记未覆盖，不假装注入过。"
)


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


BASELINE = os.environ.get("P12_MUT_BASE", "HEAD")


def git_md5(key):
    """还原独立取证的第二把尺：`git show <基线>:<path> | md5`。

    只跟「本次运行开始时的内存快照」对账是不够的——上一棒如果留下未提交的脏改动，
    内存快照本身就是脏的。基线可用 P12_MUT_BASE 指定（默认 HEAD）。
    """
    pr = subprocess.run(["git", "show", "%s:%s" % (BASELINE, SRC[key])],
                        cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if pr.returncode != 0:
        return None
    return hashlib.md5(pr.stdout).hexdigest()


def all_sources():
    """锁的守卫范围 = 本脚本会碰的那几个文件；对拍用全量 src（防「只还原本支改的文件」那种残留）。"""
    out = []
    for root, dirs, files in os.walk(os.path.join(CORE, "src")):
        dirs[:] = [d for d in dirs if d != "target"]
        for f in files:
            if f.endswith(".java"):
                out.append(os.path.join(root, f))
    return sorted(out)


def snapshot():
    return {p: md5(p) for p in all_sources()}


def src_path(key):
    return os.path.join(ZBOT, SRC[key])


def read(key):
    with open(src_path(key), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with open(src_path(key), "w", encoding="utf-8") as fh:
        fh.write(text)


def common_dir():
    return subprocess.check_output(["git", "rev-parse", "--git-common-dir"],
                                   cwd=ZBOT).decode().strip()


def lock_path():
    d = common_dir()
    if not os.path.isabs(d):
        d = os.path.join(ZBOT, d)
    return os.path.join(d, "zbot-mutlock")


def acquire(label):
    """flock(LOCK_EX|LOCK_NB)。拿不到就返回 None，且一个源文件都不碰。"""
    path = lock_path()
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError as ex:
        os.close(fd)
        print("LOCK-BUSY %s：另一支注入脚本攥着 %s（%s）⇒ 本次不跑，一个源文件都没碰"
              % (label, path, ex), flush=True)
        return None
    try:
        os.truncate(fd, 0)
        os.write(fd, ("pid=%s tag=%s t=%s\n" % (os.getpid(), label, time.strftime("%F %T")))
                 .encode("utf-8"))
    except OSError:
        pass
    print("LOCK-ACQUIRED %s flock=%s path=%s" % (label, "LOCK_EX|LOCK_NB", path), flush=True)
    return fd


def check_anchors(selected):
    bad = []
    for mid, _kind, key, old, _new, want, _exp, _note in selected:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信任何读数）:", flush=True)
        for line in bad:
            print("  " + line, flush=True)
    return bad


def run_mvn(log_name, argv):
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    logpath = os.path.join(LOGS, log_name)
    with open(logpath, "wb") as fh:
        proc = subprocess.Popen(argv, cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT)
        rc = proc.wait()
    failing, ran = set(), 0
    if os.path.isdir(REPORTS):
        for name in sorted(os.listdir(REPORTS)):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(REPORTS, name)).getroot()
            except Exception:
                continue
            ran += int(root.get("tests") or 0)
            for tc in root.iter("testcase"):
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.add(tc.get("name").split("[")[0])
    with open(logpath, encoding="utf-8", errors="replace") as fh:
        out = fh.read()
    return rc, failing, ran, out, logpath


def run_e2e_stop_section(tag):
    """真进程层：返回 (是否判红, 读数详情)。mutated 的 jar 由 package 现打。"""
    jsonpath = os.path.join(HERE, "out", "mutation-%s.json" % tag)
    proc = subprocess.run([sys.executable, "-u", E2E, "--only", "stop", "--json", jsonpath],
                          cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = proc.stdout.decode("utf-8", "replace")
    detail = ""
    if os.path.isfile(jsonpath):
        try:
            with open(jsonpath, encoding="utf-8") as fh:
                data = json.load(fh)
            for row in data.get("checks", []):
                detail += "%s=%s[%s] " % (row.get("name"), row.get("status"), row.get("detail"))
        except Exception as ex:
            detail = "读数解析失败: %s" % ex
    tail = " | ".join(text.strip().splitlines()[-3:]) if text.strip() else "无输出"
    return proc.returncode, detail + " || tail=" + tail


def parse_ids(argv):
    return [a for a in argv if not a.startswith("--")]


def main():
    argv = sys.argv[1:]
    if not os.path.isdir(LOGS):
        os.makedirs(LOGS)

    if "--print-lock-path" in argv:
        print(lock_path(), flush=True)
        return 0

    if "--hold-lock" in argv:
        secs = float(argv[argv.index("--hold-lock") + 1])
        fd = acquire("holder")
        if fd is None:
            return 4
        print("LOCK-HELD %.1fs" % secs, flush=True)
        time.sleep(secs)
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        print("LOCK-RELEASED", flush=True)
        return 0

    if "--try-lock-then-exit" in argv:
        fd = acquire("probe")
        if fd is None:
            return 5
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
        return 0

    if "--lock-probe" in argv:
        return lock_probe()

    with_e2e = "--with-e2e" in argv
    ids = parse_ids(argv)
    selected = MUTANTS
    if ids:
        selected = [m for m in MUTANTS if any(i.lower() in m[0].lower() for i in ids)]
        if not selected:
            print("FATAL: 选择器没命中任何变异体 id: %s（现有 id 见文件头 MUTANTS）" % ids, flush=True)
            return 2

    # 锚点预检：读操作，先于加锁（漂了就连锁都不必占）
    if check_anchors(selected):
        return 2

    fd = acquire("mutator")
    if fd is None:
        return 5
    try:
        # 加锁之后重验一次：等锁这段时间邻居可能已经动过盘
        if check_anchors(selected):
            return 2
        return run_mutants(selected, with_e2e)
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def run_mutants(selected, with_e2e):
    before = snapshot()
    rows = []
    tally = {}
    for mid, kind, key, old, new, want, expected, note in selected:
        tag = mid.split()[0]
        original = read(key)
        gbase = git_md5(key)
        disk_eq_git_before = (gbase is not None and md5(src_path(key)) == gbase)
        write(key, original.replace(old, new, 1))
        verdict, hit, extra, rc, ran = "EXCEPTION", [], [], -1, 0
        e2e_rc, e2e_detail = "", ""
        try:
            rc, failing, ran, out, logpath = run_mvn(
                "mut-%s-mvn.log" % tag,
                ["mvn", "-o", "test", "-pl", "z-bot-core", "-Dtest=" + TESTS,
                 "-DfailIfNoTests=false"])
            broken = "COMPILATION ERROR" in out
            e2e_rc, e2e_detail = "", ""
            if with_e2e and kind == "mvn+e2e" and not broken:
                pkglog = os.path.join(LOGS, "mut-%s-package.log" % tag)
                with open(pkglog, "wb") as fh:
                    prc = subprocess.run(["mvn", "-o", "package", "-DskipTests", "-pl", "z-bot-core"],
                                         cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT,
                                         timeout=1800).returncode
                if prc != 0:
                    e2e_rc, e2e_detail = "PKG-BROKEN", "package rc=%s（见 logs/%s）" % (prc, pkglog)
                else:
                    e2e_rc, e2e_detail = run_e2e_stop_section(tag)
            hit = sorted(set(expected) & failing)
            extra = sorted(failing - set(expected))
            if broken:
                verdict = "BROKEN"
            elif kind == "mvn+e2e" and with_e2e:
                # 这一支要求两层都判红：单测具名红 + 真进程 stop 段非 0
                if hit and not extra and str(e2e_rc) != "0":
                    verdict = "RED-OK"
                elif hit or str(e2e_rc) != "0":
                    verdict = "PARTIAL"
                else:
                    verdict = "GREEN-BUT-MUTATED"
            else:
                if hit and not extra:
                    verdict = "RED-OK"
                elif hit or extra:
                    verdict = "PARTIAL"
                else:
                    verdict = "GREEN-BUT-MUTATED"
        finally:
            write(key, original)
        restored = md5(src_path(key)) == before[src_path(key)]
        # 独立取证第二把尺：还原后的磁盘字节 vs `git show <基线>:<path>`，注入前后各取一次
        disk_eq_git_after = (gbase is not None and md5(src_path(key)) == gbase)
        tally[verdict] = tally.get(verdict, 0) + 1
        who = ",".join(sorted(failing)) if failing else "全绿（跑了 %d 条）" % ran
        print("%-46s %-18s 点名=%d/%d rc=%s e2e_rc=%s 还原=%s vs_git=%s/%s"
              % (mid, verdict, len(hit), len(expected), rc, e2e_rc, restored,
                 "ok" if disk_eq_git_before else "DIRTY",
                 "ok" if disk_eq_git_after else "FAIL"), flush=True)
        print("     红在: %s" % who, flush=True)
        if e2e_detail:
            print("     真进程层: %s" % e2e_detail[:400], flush=True)
        if verdict != "RED-OK":
            print("     %s" % note, flush=True)
        rows.append([mid, verdict, str(len(expected)), str(len(hit)), who, str(rc),
                     str(e2e_rc), e2e_detail[:200].replace("\t", " "), note,
                     "ok" if restored else "RESTORE-FAILED",
                     "base=%s before=%s after=%s" % (BASELINE,
                                                     "ok" if disk_eq_git_before else "DIRTY",
                                                     "ok" if disk_eq_git_after else "FAIL")])

    after = snapshot()
    diff = [p for p in after if after[p] != before[p]]
    print("\n== 台账 ==", flush=True)
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
        print("  %-18s %d" % (k, tally.get(k, 0)), flush=True)
    print("  SRC_MD5_STABLE=%s" % ("yes" if not diff else "NO:%s" % diff), flush=True)
    print("  " + UNCOVERED_NOTE, flush=True)
    with open(LEDGER, "w", encoding="utf-8") as fh:
        fh.write("\t".join(["id", "verdict", "named_expected", "named_hit",
                            "tests_that_went_red", "mvn_rc", "e2e_rc", "e2e_detail",
                            "note", "restored", "restore_forensics_vs_git"]) + "\n")
        for r in rows:
            fh.write("\t".join(c.replace("\t", " ") for c in r) + "\n")
    print("  台账已机械写出: %s" % os.path.relpath(LEDGER, ZBOT), flush=True)
    return 0 if not diff else 3


def lock_probe():
    """双向实测：攥住 flock ⇒ 本脚本必须拒跑且全量源码 md5 不变；松开 ⇒ 照常拿得到。"""
    path = lock_path()
    before = snapshot()
    print("探针对象: %s（git 公共目录，跨 worktree 有效）" % path, flush=True)
    print("源码全量 md5 基线: %d 个 .java 文件" % len(before), flush=True)

    holder = subprocess.Popen([sys.executable, "-u", os.path.abspath(__file__),
                               "--hold-lock", "25"], cwd=ZBOT,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    first = holder.stdout.readline().decode("utf-8", "replace").strip()
    print("  邻居进程: %s (pid=%s)" % (first, holder.pid), flush=True)
    if not first.startswith("LOCK-HELD"):
        print("FATAL 邻居没攥住锁", flush=True)
        holder.kill()
        return 2

    refused = subprocess.run([sys.executable, "-u", os.path.abspath(__file__), "M2"],
                             cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    rtext = refused.stdout.decode("utf-8", "replace")
    print("  ① 攥住时跑注入: rc=%s" % refused.returncode, flush=True)
    for line in rtext.strip().splitlines()[:3]:
        print("     | %s" % line, flush=True)
    mid = snapshot()
    untouched = mid == before
    print("  ① 拒跑后全量源码 md5 未变: %s" % ("yes" if untouched else "NO %s" % mid), flush=True)
    ok_refused = refused.returncode != 0 and "LOCK-BUSY" in rtext and untouched \
        and "Tests run" not in rtext

    holder.terminate()
    holder.wait(timeout=30)
    print("  邻居已松手: %s" % holder.stdout.read().decode("utf-8", "replace").strip(), flush=True)
    got = subprocess.run([sys.executable, "-u", os.path.abspath(__file__), "--try-lock-then-exit"],
                         cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    gtext = got.stdout.decode("utf-8", "replace")
    print("  ② 松开后跑同一条命令: rc=%s" % got.returncode, flush=True)
    for line in gtext.strip().splitlines()[:2]:
        print("     | %s" % line, flush=True)
    ok_got = got.returncode == 0 and "LOCK-ACQUIRED" in gtext

    print("\n== flock 双向实测: 拒跑=%s 放行=%s ⇒ %s" % (ok_refused, ok_got,
          "PASS" if (ok_refused and ok_got) else "FAIL"), flush=True)
    with open(os.path.join(LOGS, "lock_probe.txt"), "w", encoding="utf-8") as fh:
        fh.write("lock_path=%s\njava_files=%d\nrefuse_ok=%s\nacquire_ok=%s\n"
                 "refuse_output=%s\nacquire_output=%s\n"
                 % (path, len(before), ok_refused, ok_got,
                    rtext.strip().replace("\n", " ⏎ "), gtext.strip().replace("\n", " ⏎ ")))
    return 0 if (ok_refused and ok_got) else 1


if __name__ == "__main__":
    sys.exit(main())
