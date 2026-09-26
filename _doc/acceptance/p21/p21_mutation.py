#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P21（MCP 对齐）杠② —— 变异检验：把本期每一条守卫逐条改坏，看有没有**具名 testcase** 判红。

结构照 `_doc/acceptance/p18/p18_mutation.py` 的口径（工单 p21c §3-C 明写），纪律：

  * **预期红集先写死在本文件里**，跑之前随脚本一起提交 ⇒ 不许事后凑；
  * 注入前逐条校验锚点出现次数（锚点漂了 = 量具坏了，直接 FATAL，不收读数）；
  * 点名的 testcase 也必须存在（`Class#method` 在测试源码里搜不到 ⇒ FATAL）：
    指向已删测试的变异体只会崩出一个假红，那不是在量产品；
  * 每支变异体只跑它自己点名的 testcase（`-Dtest=Class#m1+m2`），不跑全量；
  * 判定只认 surefire XML 里的 testcase 名：
      RED-OK             点名的全红、没有别的红
      PARTIAL            点名的一部分红 / 红了别人
      GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
      BROKEN             编译不过
      NO-RUN             阳性对照进不来（配不到猎物），写明原因，不算分
  * **阳性对照**：每族先跑一次 `injection=NONE`，点名的 testcase 必须全绿且真跑到
    （ran>0），否则该族所有变异体记 NO-RUN —— 拿空跑当满分就是这一条；
  * 还原**只从内存里的原文 cp 回去**（不用 `git checkout --`：本机工作树里还有别的在途改动，
    git 基线是 HEAD 而不是"我开始测量那一刻"）；每支跑完立刻 md5 对账，对不上就停；
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲。

写域：注入只动 `mcp/*.java`、`config/BotConfig.java`（P21 守卫面），
不碰 `channel/**`、`agent/**`、内核仓。本脚本不读 `~/.zbot` 任何凭据，
不发外网包（`McpRealStdioServerTest` 那一族只起本机官方 SDK server）。

复算: python3 -u _doc/acceptance/p21/p21_mutation.py            # 全量一轮
      python3 -u _doc/acceptance/p21/p21_mutation.py --check    # 只验锚点与点名，不跑 mvn
锁:   $(git rev-parse --git-common-dir)/zbot-mutlock —— 抢不到就退避重试（同机别人在跑），
      重试到上限仍拿不到 ⇒ rc=4（**这不是失败**，是"现在不该由我占着 target/"）
"""
import fcntl
import hashlib
import io
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
TEST_SRC = os.path.join(CORE, "src", "test", "java", "com", "zifang", "z", "bot", "mcp")
PKG = "com.zifang.z.bot.mcp"

LOCK_RETRIES = 20            # 抢锁重试次数
LOCK_BACKOFF = 30            # 每次退避秒数
PER_RUN_TIMEOUT = 900        # 单支变异体的 mvn 上限（秒）


def measure_suite_total():
    """全量-suite 用例总数只认机械量：`git grep -c '@Test' HEAD` 求和。不许手敲常量。"""
    proc = subprocess.run(["git", "-C", ZBOT, "grep", "-c", "@Test", "HEAD", "--",
                           "z-bot-core/src/test"], stdout=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("FATAL: git grep 量具本身失败 rc=%d，不收读数" % proc.returncode)
    total, files = 0, 0
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        try:
            total += int(line.rsplit(":", 1)[1])
            files += 1
        except ValueError:
            raise SystemExit("FATAL: git grep 输出行形状不对: %r" % line)
    if files == 0 or total <= 0:
        raise SystemExit("FATAL: 量到 suite 总数 %d（文件 %d 个）= 空输入，不收读数" % (total, files))
    return total


SUITE_TOTAL = measure_suite_total()

SRC = {
    "fac": "z-bot-core/src/main/java/com/zifang/z/bot/mcp/McpClientFactory.java",
    "brg": "z-bot-core/src/main/java/com/zifang/z/bot/mcp/McpBridge.java",
    "stdio": "z-bot-core/src/main/java/com/zifang/z/bot/mcp/ZBotStdioMcpTransport.java",
    "http": "z-bot-core/src/main/java/com/zifang/z/bot/mcp/StreamableHttpMcpTransport.java",
    "serve": "z-bot-core/src/main/java/com/zifang/z/bot/mcp/ZBotMcpServe.java",
    "red": "z-bot-core/src/main/java/com/zifang/z/bot/mcp/SecretRedaction.java",
    "cfg": "z-bot-core/src/main/java/com/zifang/z/bot/config/BotConfig.java",
}

# (id, family, 文件键, old, new, 锚点应出现次数, 点名期望红的 testcase, 说明)
MUTANTS = [
    # ============ A 族：transport 选择与注入缝 ============
    ("A1 http 条目静默降级成 stdio", "A-transport选择", "fac",
     "            return createHttp(entry);\n",
     "            return createStdioZBot(entry);\n", 1,
     ["McpTransportConfigDiscriminationTest#urlAsValueIsDiscriminatedAsHttpWithoutAnyOverrideKey"],
     "配了 http 却悄悄跑成 stdio：用户以为在连远端，实际在本地拉起一个不存在的命令"),

    ("A2 未知 transport 退成 stdio（不报错）", "A-transport选择", "fac",
     "        throw new IllegalArgumentException(\"mcp server '\" + entry.getName()\n"
     "                + \"' 的 transport 不认识: \" + t + \"（可用值: stdio / http）\");",
     "        return createStdioZBot(entry);", 1,
     ["McpTransportConfigDiscriminationTest#unknownTransportFailsLoudlyInsteadOfFallingBackToStdio"],
     "拼错的 transport 值被吞：一个连不上的 server 会被报成'起来了但 0 工具'"),

    ("A3 注入缝断掉（transportOf 恒返回 null）", "A-transport选择", "fac",
     "        if (client instanceof StdIoMcpClient) {\n"
     "            return ((StdIoMcpClient) client).transportHandle();\n"
     "        }\n        return null;",
     "        return null;", 1,
     ["McpRealStdioServerTest#zbotTransportHandshakesListsAndCallsAgainstOfficialSdk",
      "McpListChangedInProcessTest#pushedListChangedReallyDeregistersAndReplacesTheToolset"],
     "bridge 拿不到 transport 句柄 ⇒ 挂不上通知监听器，list_changed 整条链路静默失效"),

    # ============ B 族：list_changed ⇒ 真注销 + 重注册 ============
    ("B1 收到 list_changed 不刷新", "B-list_changed兑现", "brg",
     "                LOG.info(\"[mcp:{}] 收到 {}，重列工具表\", client.name(), method);\n"
     "                refreshTools();",
     "                LOG.info(\"[mcp:{}] 收到 {}（吞掉不刷新）\", client.name(), method);", 1,
     ["McpListChangedInProcessTest#pushedListChangedReallyDeregistersAndReplacesTheToolset",
      "McpListChangedInProcessTest#droppedToolsShrinkTheToolkitCountByExactlyTheirNumber",
      "McpRealStdioServerTest#officialServerPushesListChangedAndZbotTransportReallySwapsToolkit"],
     "本期主命题的反面：收到了但什么都不做 ⇒ 工具表永远停在握手那一秒"),

    ("B2 换血时不注销旧 toolset（只覆盖）", "B-list_changed兑现", "brg",
     "            if (!firstTime) {\n"
     "                toolkit.deregisterToolset(toolset(), owner());\n"
     "                registered.clear();\n            }",
     "            if (!firstTime) {\n                registered.clear();\n            }", 1,
     ["McpListChangedInProcessTest#pushedListChangedReallyDeregistersAndReplacesTheToolset",
      "McpListChangedInProcessTest#droppedToolsShrinkTheToolkitCountByExactlyTheirNumber"],
     "回到 P20b 之前的'同名 stub 覆盖'错法：被 server 摘掉的工具名仍然留在注册表里"),

    ("B3 不分 method 一律刷新", "B-list_changed兑现", "brg",
     "                if (!McpNotificationListener.TOOLS_LIST_CHANGED.equals(method)) {\n"
     "                    return;\n                }",
     "                if (false) {\n                    return;\n                }", 1,
     ["McpListChangedInProcessTest#otherNotificationMethodsDoNotTouchTheRegistry"],
     "progress/roots 之类的通知也去重列工具表：把无关推送变成一轮网络往返 + 换血"),

    # ============ W 族：父死 watchdog 真杀 ============
    ("W1 监护脚本发现父死但不杀子", "W-watchdog真杀", "stdio",
     "        sb.append(\"  [ \\\"$pp\\\" != \\\"\").append(jvmPid())"
     ".append(\"\\\" ] && kill -9 $me 2>/dev/null && exit 0\").append('\\n');",
     "        sb.append(\"  [ \\\"$pp\\\" != \\\"\").append(jvmPid())"
     ".append(\"\\\" ] && exit 0\").append('\\n');", 1,
     ["McpParentWatchdogTest#killedParentTakesTheChildWithIt",
      "McpParentWatchdogTest#watchdogTakesEvenAnEofInsensitiveChildWithIt"],
     "kill -9 z-bot 之后子进程成孤儿：server 端长期占着端口/文件句柄"),

    ("W2 忽略 parentWatchdog 开关（永远开）", "W-watchdog真杀", "stdio",
     "        if (!options.parentWatchdog || !isPosix()) {",
     "        if (!isPosix()) {", 1,
     ["McpParentWatchdogTest#withoutWatchdogAnEofInsensitiveChildReallyBecomesAnOrphan"],
     "开关失效 ⇒ '关 watchdog 会留孤儿'这条对照就永远量不到东西，positive control 变自证"),

    # ============ R 族：反向 mcp_serve 的工具表与协议版本 ============
    ("R1 广告 listChanged=true（兑现不了的承诺）", "R-反向serve", "serve",
     "        tools.put(\"listChanged\", Boolean.FALSE);",
     "        tools.put(\"listChanged\", Boolean.TRUE);", 1,
     ["McpServeProtocolTest#initializeEchoesTheSupportedVersionAndAdvertisesNoListChanged"],
     "工具表是编译期常量却广告会变：对端会一直等一个永远不会来的通知"),

    ("R2 读端口子里塞一个写端工具", "R-反向serve", "serve",
     "        tools.add(tool(TOOL_MESSAGES,",
     "        tools.add(tool(\"zbot_messages_append\",\n"
     "                \"往会话里追加一条消息\", null));\n"
     "        tools.add(tool(TOOL_MESSAGES,", 1,
     ["McpServeProtocolTest#toolTableIsExactlyTheTwoReadTools"],
     "只读面被悄悄扩大 ⇒ 一条 `mcp serve` 就能改写本地状态（红线）"),

    ("R3 对端提的协议版本照单全收", "R-反向serve", "serve",
     "        if (!SUPPORTED_PROTOCOL_VERSIONS.contains(negotiated)) {",
     "        if (!java.util.Collections.singletonList(\"9999-99-99\").contains(negotiated)) {", 1,
     ["McpServeProtocolTest#initializeEchoesTheSupportedVersionAndAdvertisesNoListChanged"],
     "规范：不支持的版本要回自己的版本让对端决定断不断；照单全收 = 双方都以为对上了"),

    # ============ C 族：BotConfig transport 判别 ============
    ("C1 URL 前缀判别拿掉", "C-配置判别", "cfg",
     "        boolean looksLikeUrl = cmdline.regionMatches(true, 0, \"http://\", 0, 7)\n"
     "                || cmdline.regionMatches(true, 0, \"https://\", 0, 8);",
     "        boolean looksLikeUrl = false;", 1,
     ["McpTransportConfigDiscriminationTest#urlAsValueIsDiscriminatedAsHttpWithoutAnyOverrideKey"],
     "值直接写 URL 这条新形态失效 ⇒ 老配置没事、新配置必错"),

    ("C2 headers 分隔符从分号退回逗号", "C-配置判别", "cfg",
     "        for (String pair : raw.split(\";\")) {",
     "        for (String pair : raw.split(\",\")) {", 1,
     ["McpTransportConfigDiscriminationTest#perServerOverrideKeysSetTransportUrlHeadersAndTimeout",
      "McpTransportConfigDiscriminationTest#headerValueWithSecondEqualsKeepsTheTail"],
     "逗号已被 mcp.servers 当 server 分隔符占用 ⇒ 换分隔符会把两个 server 拆成一堆坏条目"),

    ("C3 非数字 timeout 不再退回默认（丢整段解析）", "C-配置判别", "cfg",
     "        if (!timeoutRaw.isEmpty()) {\n            timeout = parseInt(timeoutRaw, 0);\n        }",
     "        if (!timeoutRaw.isEmpty() && false) {\n            timeout = parseInt(timeoutRaw, 0);\n        }", 1,
     ["McpTransportConfigDiscriminationTest#perServerOverrideKeysSetTransportUrlHeadersAndTimeout"],
     "配的超时被静默丢弃 ⇒ 挂死的 server 又变成无界等待（P21 §1.2 那条老病从配置侧回来）"),

    # ============ S 族：SecretRedaction 双向 ============
    ("S1 mask 原样返回（不脱敏）", "S-脱敏双向", "red",
     "        return value.substring(0, 2) + \"********\" + value.substring(value.length() - 2);",
     "        return value;", 1,
     ["SecretRedactionBidirectionalTest#maskKeepsFixedLengthStarsSoTheSecretLengthDoesNotLeak",
      "SecretRedactionBidirectionalTest#sentinelIsPinnedIntoTheConfigAndOutOfEveryRenderedProduct"],
     "红线：真 key 进 toString()/日志 ⇒ 一次 `z-bot status` 就泄了"),

    ("S2 scrub 先短后长（长值前缀被吃掉）", "S-脱敏双向", "red",
     "                return lb - la;",
     "                return la - lb;", 1,
     ["SecretRedactionBidirectionalTest#scrubReplacesEveryKnownSecretEvenWhenOneIsAPrefixOfAnother"],
     "短 secret 先替会把长 secret 劈成'已替 + 漏网尾巴' ⇒ 半边泄漏看起来像处理过了"),

    ("S3 maskUrl 不砍 query", "S-脱敏双向", "red",
     "        int q = s.indexOf('?');\n        if (q >= 0) {\n            s = s.substring(0, q);\n        }",
     "        int q = s.indexOf('#');\n        if (q >= 0) {\n            s = s.substring(0, q);\n        }", 1,
     ["SecretRedactionBidirectionalTest#maskUrlDropsQueryAndUserInfoButKeepsThePath"],
     "url 的 ?key= 就是凭据，而它出现在'连不上'的异常文本里"),

    # ============ T 族：超时/挂死面（本期与内核的分水岭）============
    ("T1 请求不等自己的 timeoutMillis（deadline 形同虚设）", "T-超时与挂死", "stdio",
     "            return future.get(options.timeoutMillis, TimeUnit.MILLISECONDS);",
     "            return future.get(60_000L, TimeUnit.MILLISECONDS);", 1,
     ["McpRealStdioServerTest#zbotTransportHonoursItsOwnDeadlineWhileKernelBlocksInReadLine"],
     "§1.2 那条内核错法的正面：等一个跟配置无关的常量上限 ⇒ 900ms 的 deadline 不再成立，"
     "不回话的 server 把 bridge 挂死到 60s。（曾用 future.get() 无参式：javac 直接拒绝 —— "
     "catch (TimeoutException) 变成不可达 ⇒ 那支变异体根本编不出来，记 BROKEN 不算杀掉的）"),

    ("T2 收到通知不派发（listener 恒 null）", "T-超时与挂死", "stdio",
     "                final McpNotificationListener l = listener;",
     "                final McpNotificationListener l = null;", 1,
     ["McpRealStdioServerTest#officialServerPushesListChangedAndZbotTransportReallySwapsToolkit"],
     "读线程数了数但谁也不告诉 ⇒ 计数全绿而工具表永不换血（只看计数器就会被骗）"),
]

# 每族的阳性对照点名集 = 该族所有变异体点名 testcase 的并集（机械算，不手敲第二份）
FAMILY_PREY = {}
for _m in MUTANTS:
    FAMILY_PREY.setdefault(_m[1], set()).update(_m[6])


def abspath(key):
    return os.path.join(ZBOT, SRC[key])


def read(key):
    with io.open(abspath(key), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with io.open(abspath(key), "w", encoding="utf-8") as fh:
        fh.write(text)


def md5(path):
    h = hashlib.md5()
    with io.open(path, "rb") as fh:
        h.update(fh.read())
    return h.hexdigest()


def check_named_tests():
    """点名的 Class#method 必须真的存在于测试源码里（指向已删测试的变异体只会崩出假红）。"""
    missing = []
    cache = {}
    for m in MUTANTS:
        for full in m[6]:
            cls, meth = full.split("#", 1)
            if cls not in cache:
                p = os.path.join(TEST_SRC, cls + ".java")
                cache[cls] = open(p, encoding="utf-8").read() if os.path.isfile(p) else ""
            if ("void " + meth + "(") not in cache[cls]:
                missing.append("%s（%s 里找不到 `void %s(`）" % (full, cls + ".java", meth))
    return sorted(set(missing))


def acquire_lock():
    top = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--git-common-dir"],
                         stdout=subprocess.PIPE)
    common = top.stdout.decode("utf-8", "replace").strip()
    if not os.path.isabs(common):
        common = os.path.join(ZBOT, common)
    lock_path = os.path.join(common, "zbot-mutlock")
    for attempt in range(LOCK_RETRIES):
        fh = io.open(lock_path, "a+")
        try:
            fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fh, lock_path
        except IOError:
            fh.close()
            print("[锁] 第 %d 次没抢到 %s —— %ds 后重试（同机别人在跑，这不算失败）"
                  % (attempt + 1, lock_path, LOCK_BACKOFF), flush=True)
            time.sleep(LOCK_BACKOFF)
    return None, lock_path


def selector(named):
    by_class = {}
    for full in named:
        cls, meth = full.split("#", 1)
        by_class.setdefault(cls, []).append(meth)
    return ",".join("%s#%s" % (c, "+".join(sorted(set(ms)))) for c, ms in sorted(by_class.items())),\
        sorted(by_class)


def run_named(named):
    sel, classes = selector(named)
    for name in os.listdir(REPORTS) if os.path.isdir(REPORTS) else []:
        os.remove(os.path.join(REPORTS, name))
    cmd = ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + sel,
           "-DfailIfNoTests=false"]
    t0 = time.time()
    try:
        proc = subprocess.run(cmd, cwd=ZBOT, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=PER_RUN_TIMEOUT)
        rc, out = proc.returncode, proc.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return -1, set(), 0, "TIMEOUT after %ds" % PER_RUN_TIMEOUT, time.time() - t0, sel
    failing, ran = set(), 0
    for cls in classes:
        xml = os.path.join(REPORTS, "TEST-%s.%s.xml" % (PKG, cls))
        if not os.path.isfile(xml):
            continue
        try:
            root = ET.parse(xml).getroot()
        except Exception:
            continue
        ran += int(root.get("tests") or 0)
        for tc in root.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                failing.add("%s#%s" % (tc.get("classname").split(".")[-1],
                                       tc.get("name").split("[")[0]))
    return rc, failing, ran, out, time.time() - t0, sel


def main():
    mutants = MUTANTS
    check_only = "--check" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if args:
        mutants = [m for m in MUTANTS if any(a in m[0] for a in args)]
        if not mutants:
            print("FATAL: 选择器没命中任何变异体 id: %s" % args)
            return 2

    # ===== 注入前的两组预检：锚点唯一性 + 点名存在性 =====
    bad = []
    for mid, fam, key, old, new, want, expected, note in mutants:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    missing = check_named_tests()
    if missing:
        bad.append("点名的 testcase 在测试源码里找不到: " + ", ".join(missing))
    if bad:
        print("FATAL 预检失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2
    print("预检: 锚点 %d 支全部唯一命中；点名 testcase %d 个全部存在；suite 总数=%d（机械量得）"
          % (len(mutants), len({x for m in mutants for x in m[6]}), SUITE_TOTAL), flush=True)
    if check_only:
        return 0

    if not os.path.isdir(REPORTS):
        os.makedirs(REPORTS)

    lock_fh, lock_path = acquire_lock()
    if lock_fh is None:
        print("FATAL: 变异锁拿不到（%s）—— 别人正占着 target/，本脚本不带等待" % lock_path)
        return 4

    baseline = {k: md5(abspath(k)) for k in SRC}
    rows = []
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0, "NO-RUN": 0}
    ledger = os.path.join(HERE, "LEDGER.tsv")

    def flush_ledger():
        with io.open(ledger, "w", encoding="utf-8") as fh:
            fh.write("\t".join(["id", "family", "target", "testcase", "injection",
                                "expected_red_set", "verdict", "detail"]) + "\n")
            for r in rows:
                fh.write("\t".join(r) + "\n")
            fh.write("\t".join(["#tally", "", "", "", "",
                                "RED-OK=%d|PARTIAL=%d|GREEN-BUT-MUTATED=%d|BROKEN=%d|NO-RUN=%d"
                                % (tally["RED-OK"], tally["PARTIAL"], tally["GREEN-BUT-MUTATED"],
                                   tally["BROKEN"], tally["NO-RUN"]), "", ""]) + "\n")
            fh.write("\t".join(["#mutants_injected", "", "", "", "", "",
                                str(len([r for r in rows if not r[0].startswith("CTRL-")])), ""]) + "\n")
            fh.write("\t".join(["#suite_total_full_tests_measured", "", "", "", "", "",
                                str(SUITE_TOTAL), ""]) + "\n")
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "p21_mutation.py",
                                time.strftime("%Y-%m-%dT%H:%M:%S%z")]) + "\n")

    families = sorted({m[1] for m in mutants})
    prey_ok = {}
    # ===== 阳性对照：每族 injection=NONE，点名的 testcase 必须真跑到且全绿 =====
    for fam in families:
        named = sorted(FAMILY_PREY.get(fam, set()))
        if not named:
            prey_ok[fam] = False
            rows.append(["CTRL-" + fam, fam, "-", "(无点名 testcase)", "NONE", "-", "NO-RUN",
                         "该族没有任何点名 testcase"])
            tally["NO-RUN"] += 1
            flush_ledger()
            continue
        rc, failing, ran, out, secs, sel = run_named(named)
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0 or failing:
            verdict = "NO-RUN"
        else:
            verdict = "OK"
        prey_ok[fam] = verdict == "OK"
        rows.append(["CTRL-" + fam, fam, "-", sel, "NONE", ",".join(named), verdict,
                     "ran=%d rc=%s %.1fs 红=%s" % (ran, rc, secs, ",".join(sorted(failing)) or "-")])
        print("[对照] %-16s %-6s ran=%d 点名=%d %.1fs %s"
              % (fam, verdict, ran, len(named), secs, ",".join(sorted(failing)) or "全绿"), flush=True)
        flush_ledger()

    # ===== 逐支注入 =====
    for mid, fam, key, old, new, want, expected, note in mutants:
        if not prey_ok.get(fam, False):
            rows.append([mid, fam, SRC[key], ",".join(expected), "SKIPPED", ",".join(expected),
                         "NO-RUN", "阳性对照进不来猎物：CTRL-%s 不是 OK" % fam])
            tally["NO-RUN"] += 1
            print("%-44s NO-RUN（阳性对照失败）" % mid, flush=True)
            flush_ledger()
            continue
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, out, secs, sel = run_named(expected)
        if "COMPILATION ERROR" in out:
            # 同机别人的 mvn 会跟我抢同一个 target/ —— 编译类失败重跑一次再定性
            time.sleep(20)
            rc, failing, ran, out, secs, sel = run_named(expected)
        write(key, original)
        restored = md5(abspath(key)) == baseline[key]
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0:
            verdict = "NO-RUN"
        else:
            hit = set(expected) & failing
            if hit and not (failing - set(expected)) and len(hit) == len(expected):
                verdict = "RED-OK"
            elif hit or failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        who = ",".join(sorted(failing)) if failing else "全绿"
        print("%-44s %-18s 点名 %d/%d ran=%d rc=%s %.1fs 还原=%s | %s"
              % (mid, verdict, len(set(expected) & failing), len(expected), ran, rc, secs,
                 restored, who), flush=True)
        rows.append([mid, fam, SRC[key], sel,
                     "\\n".join(new.split("\n"))[:90] or "(删掉锚点)", ",".join(expected), verdict,
                     "红=%s ran=%d rc=%s %.1fs 还原=%s 说明=%s" % (who, ran, rc, secs, restored, note)])
        flush_ledger()
        if not restored:
            print("FATAL: %s 之后没还原成基线，停在这里（后面读数不可信）" % mid)
            break

    # ===== 还原对账：md5 + git status =====
    untracked = subprocess.run(["git", "-C", ZBOT, "status", "--porcelain"],
                               stdout=subprocess.PIPE).stdout.decode("utf-8", "replace")
    src_changed = [k for k in SRC if md5(abspath(k)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-18s %d" % (k, tally[k]))
    print("  注入后 src 与基线 md5 有差异的文件: %s" % (src_changed or "无（逐字节还原）"))
    print("  git status --porcelain: %s" % (untracked.strip().replace("\n", " | ") or "（空）"))
    return 0 if not src_changed else 3


if __name__ == "__main__":
    sys.exit(main())
