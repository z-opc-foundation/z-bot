#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P30b（入站 body 上限：门在分配之前）变异检验：把 `InboundLimits` 与其四处接线逐条改坏，
看有没有**具名 testcase** 判红。

口径照 `_doc/acceptance/p30/p30_mutation.py`（同一套四杠纪律），判据一字不动：
  RED-OK   点名全红，且没有 `named ∪ allow_extra` 之外的红
  PARTIAL  点名一部分红 / 红了预期之外的人
  GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
  BROKEN   编译不过   NO-RUN  阳性对照没进猎物 / ran=0

三层互补（工单 #47 点名的三层，各有一支只有它能红的变异）：
  * **常量层** B1：上限放宽 1000 倍。边界那四对用例全从 `MAX_BODY_BYTES` 取数，改了常量它们
    照样绿（p30 的 I4 就是同一个坑）⇒ 只有钉死字面值的那支能红。
  * **接线层** B3：某一个面退回自己拼的无界读取。上限是共享的，但"接没接"是每个面各自的，
    所以四对边界用例 + 一支结构守卫都要在。
  * **"只信 Content-Length"层** B2：摘掉按字节累加那道门，只留头检。带声明长度的请求仍会被
    头检挡下 ⇒ 四对边界用例全绿，只有不带那个头（分块传输）打进去的那支能红。
  另有 B4/B5/B6：某个面少了 413 映射 ⇒ 落进通用 `catch (Exception)` 变成 500。

纪律（一条都不许破）：
  * **预期红集先写死在本文件里**，跑之前随脚本一起 commit ⇒ 不许事后凑；
  * 注入前逐条校验锚点出现次数（锚点漂了 = 量具坏了，直接 FATAL，不收读数）；
  * 每支只点名它自己的 testcase（`-Dtest=Class#m1+m2`），不跑全量；
  * **阳性对照**：每族先跑一次 `injection=NONE`，`named ∪ allow_extra` 必须真跑到(ran>0)且全绿；
  * 还原只从**本次运行开始时读进的原文**逐字节写回（绝不 `git checkout`），每支跑完 md5 对账；
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲。

明确不注入的（等价变异，注了只会产出假读数，按**未覆盖**记账）：
  * **摘掉 `declaredLength(ex) > MAX_BODY_BYTES` 那半道门**：按字节累加那道还在，超尺寸的请求
    照样吃 413，HTTP 层没有任何可观测量能区分"读满 64 KiB 才拒"和"看一眼头就拒"——
    差别只在少读一段字节，测试量不到内存。它省下的是分配，不是判定，所以本期只由
    `InboundLimits` 的注释与代码形状看守。
  * **`readBody(String)` 这层包装**：它只是 `new String(readBodyBytes(ex), UTF_8)`，摘掉它把三个面
    改成直接调 `readBodyBytes` ⇒ 行为逐字节相同，没有用例能红，注了只会多一条假读数。

安全：注入只动 `channel/InboundLimits.java`、`FeishuChannel.java`、`DingTalkChannel.java`、
`WebhookChannel.java`、`HttpChannel.java` 五个本期写域文件；全程只连 127.0.0.1，
不碰 `~/.zbot`（红线 1），不读任何真凭据，不给真域名发一个包。

复算: python3 -u _doc/acceptance/p30b/p30b_mutation.py [id 子串...]
锁:   $(git rev-parse --git-common-dir)/zbot-mutlock —— 只 try-lock，抢不到就 rc=4 退出
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


def measure_suite_total():
    """全量-suite 用例总数只认机械量：`git grep -c '@Test' HEAD` 求和（不认手敲常量）。
    空输入 / 解析不出形状一律 FATAL，不收读数。"""
    proc = subprocess.run(["git", "-C", ZBOT, "grep", "-c", "@Test", "HEAD", "--",
                           "z-bot-core/src/test"], stdout=subprocess.PIPE)
    if proc.returncode != 0:
        raise SystemExit("FATAL: git grep 量具本身失败 rc=%d，不收读数" % proc.returncode)
    total, files = 0, 0
    for line in proc.stdout.decode("utf-8", "replace").splitlines():
        try:
            total += int(line.rsplit(":", 1)[1]); files += 1
        except ValueError:
            raise SystemExit("FATAL: git grep 输出行形状不对: %r" % line)
    if files == 0 or total <= 0:
        raise SystemExit("FATAL: 量到 suite 总数 %d（文件 %d 个）= 空输入，不收读数" % (total, files))
    return total


SUITE_TOTAL = measure_suite_total()
PER_RUN_TIMEOUT = 900

SRC = {
    "lim": "z-bot-core/src/main/java/com/zifang/z/bot/channel/InboundLimits.java",
    "fei": "z-bot-core/src/main/java/com/zifang/z/bot/channel/FeishuChannel.java",
    "din": "z-bot-core/src/main/java/com/zifang/z/bot/channel/DingTalkChannel.java",
    "web": "z-bot-core/src/main/java/com/zifang/z/bot/channel/WebhookChannel.java",
    "http": "z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java",
}

T = "InboundBodyLimitTest#"

PIN = [T + "maxBodyBytesIsPinnedToTheHermesWecomLimit"]
FEI_B = [T + "feishuAcceptsExactlyMaxAndRejectsOneByteOver"]
DIN_B = [T + "dingtalkAcceptsExactlyMaxAndRejectsOneByteOver"]
WEB_B = [T + "webhookAcceptsExactlyMaxAndRejectsOneByteOver"]
HTTP_B = [T + "httpConsoleAcceptsExactlyMaxAndRejectsOneByteOver"]
CHUNK = [T + "chunkedOversizedBodyWithNoContentLengthIsStillCapped"]
NOBUS = [T + "oversizedBodyNeverReachesTheBus"]
ORDER = [T + "bodyCapDoesNotPreemptTheSignatureGate"]
GUARD = [T + "everyInboundBodyReadGoesThroughTheSingleSource"]

# "门形同虚设"时除了字面值那支，其余尺寸臂会一起红 —— 一并写死，别让它变成"红了预期之外的人"。
EVERYTHING_BUT_PIN_AND_GUARD = FEI_B + DIN_B + WEB_B + HTTP_B + CHUNK + NOBUS + ORDER

# (id, family, 文件键, old, new, 锚点次数, named 杀手, allow_extra 连带, 说明)
MUTANTS = [
    # ===== B-上限常量 =====
    ("B1 上限放宽 1000 倍", "B-上限常量", "lim",
     "    static final int MAX_BODY_BYTES = 65_536;",
     "    static final int MAX_BODY_BYTES = 65_536_000;", 1,
     PIN, EVERYTHING_BUT_PIN_AND_GUARD,
     "四对边界用例的取样值是 `InboundLimits.MAX_BODY_BYTES ± 1`（跟着常量走），把常量本身改掉时"
     "它们会整体平移、照样绿 —— p30 的 I4 就是同一个坑。所以字面值必须有独立一支钉住。"
     "连带红的其余七支是同一刀的副作用，不是第二层守卫。"),

    # ===== B-累加门（"只信头"那一层）=====
    ("B2 摘掉按字节累加的门，只留 Content-Length 头检", "B-累加门", "lim",
     '                if (total > MAX_BODY_BYTES) {\n'
     '                    throw new BodyTooLargeException("body 超过上限 " + MAX_BODY_BYTES + " 字节");\n'
     "                }\n", "", 1,
     CHUNK, [],
     "头可以撒谎、分块传输根本没有这个头。这一刀之后带声明长度的请求仍被头检挡下 ⇒ 四对边界用例"
     "**全绿**，只有 `setChunkedStreamingMode` 那支（不带 Content-Length）会红。它就是为这一层造的猎物。"),

    # ===== B-单源接线 =====
    ("B3 webhook 退回自己拼的无界读取", "B-单源接线", "web",
     "    private static String readBody(HttpExchange ex) throws IOException {\n"
     "        return InboundLimits.readBody(ex);\n"
     "    }",
     "    private static String readBody(HttpExchange ex) throws IOException {\n"
     "        try (java.io.InputStream is = ex.getRequestBody()) {\n"
     "            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();\n"
     "            byte[] buf = new byte[4096];\n"
     "            int len;\n"
     "            while ((len = is.read(buf)) != -1) {\n"
     "                baos.write(buf, 0, len);\n"
     "            }\n"
     "            return new String(baos.toByteArray(), StandardCharsets.UTF_8);\n"
     "        }\n"
     "    }", 1,
     GUARD + WEB_B, CHUNK + NOBUS,
     "上限是共享的，接不接是每个面各自的事：这一刀只让 webhook 一个面裸奔（飞书/钉钉/控制台仍绿），"
     "所以点名里必须有'走 webhook 的尺寸臂' + '全仓扫描的结构守卫'两层。结构守卫自己带阳性对照"
     "（`PREY_INLINE_READER`），但它证的是判据认得出形状；'真有个面漂了'要由尺寸臂红来证 —— 两支各司其职。"
     "注意注入体全用 FQN：三个通道的 `java.io.InputStream/ByteArrayOutputStream` import 已随收口删掉，"
     "用短名会编译不过 ⇒ 读数会变成 BROKEN 而不是 killer。"),

    # ===== B-413 映射（四个 catch 点各一刀）=====
    ("B4 飞书少了 413 映射（掉进通用 catch ⇒ 500）", "B-413映射", "fei",
     '        } catch (BodyTooLargeException e) {\n'
     '            LOG.warn("[feishu] 入站超限: {}", e.getMessage());\n'
     '            text(ex, 413, "{\\"ok\\":false,\\"error\\":\\"request body too large\\"}");\n',
     "", 1,
     FEI_B, [],
     "超限读取抛的是 RuntimeException；某个面没在通用 `catch (Exception)` **之前**接住它，"
     "就会把'太大'报成'服务器坏了'。客户端据此决定要不要重发 —— 500 与 413 不可混。"),

    ("B5 钉钉少了 413 映射", "B-413映射", "din",
     '        } catch (BodyTooLargeException e) {\n'
     '            LOG.warn("[dingtalk] 入站超限: {}", e.getMessage());\n'
     '            text(ex, 413, "{\\"ok\\":false,\\"error\\":\\"request body too large\\"}");\n',
     "", 1,
     DIN_B, ORDER,
     "同 B4。`bodyCapDoesNotPreemptTheSignatureGate` 的后半句点的就是这一面（签对了的超限必须 413），"
     "所以它也在连带里。"),

    ("B6 控制台少了 413 映射", "B-413映射", "http",
     '        } catch (BodyTooLargeException e) {\n'
     '            LOG.warn("{} {} body 超限: {}", method, path, e.getMessage());\n'
     "            try {\n"
     '                json(ex, 413, error("请求体超过上限 " + InboundLimits.MAX_BODY_BYTES + " 字节"));\n'
     "            } catch (IOException ignored) {\n"
     "            }\n",
     "", 1,
     HTTP_B, [],
     "控制台那八个 `readBody(ex)` 调用点共用一个分派器 catch —— 这一刀同时摘掉八个面的映射，"
     "而点名只有一支：因为它们是同一个映射点，不需要八支用例。"),
]

FAMILY_PREY = {}
for _m in MUTANTS:
    FAMILY_PREY.setdefault(_m[1], set()).update(_m[6])
    FAMILY_PREY[_m[1]].update(_m[7])


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def abspath(key):
    return os.path.join(ZBOT, SRC[key])


def read(key):
    with io.open(abspath(key), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with io.open(abspath(key), "w", encoding="utf-8") as fh:
        fh.write(text)


def acquire_lock():
    top = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=ZBOT,
                         stdout=subprocess.PIPE)
    common = top.stdout.decode("utf-8", "replace").strip()
    if not os.path.isabs(common):
        common = os.path.join(ZBOT, common)
    lock_path = os.path.join(common, "zbot-mutlock")
    fh = io.open(lock_path, "a+")
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except IOError:
        print("FATAL: 变异锁被别的写手占着（%s）—— 本脚本不带等待，直接退出" % lock_path)
        return None, None
    return fh, lock_path


def selector(named):
    by_class = {}
    for full in named:
        cls, meth = full.split("#", 1)
        by_class.setdefault(cls, []).append(meth)
    return ",".join("%s#%s" % (c, "+".join(ms)) for c, ms in sorted(by_class.items())), sorted(by_class)


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
        xml = os.path.join(REPORTS, "TEST-com.zifang.z.bot.channel.%s.xml" % cls)
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
    if len(sys.argv) > 1:
        mutants = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not mutants:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:])
            return 2

    if not os.path.isdir(REPORTS):
        os.makedirs(REPORTS)
    bad = []
    for mid, fam, key, old, new, want, named, extra, note in mutants:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2

    # 点名的 testcase 必须真实存在：抄错一个字母，"没红"会被读成"变异体活了"
    known = set()
    src_dir = os.path.join(CORE, "src", "test", "java", "com", "zifang", "z", "bot", "channel")
    for fn in os.listdir(src_dir):
        if not fn.endswith(".java"):
            continue
        with io.open(os.path.join(src_dir, fn), encoding="utf-8") as fh:
            txt = fh.read()
        cls = fn[:-5]
        for line in txt.split("\n"):
            s = line.strip()
            if s.startswith("public void "):
                known.add("%s#%s" % (cls, s[len("public void "):].split("(")[0].strip()))
    typos = []
    for mid, fam, key, old, new, want, named, extra, note in mutants:
        for t in list(named) + list(extra):
            if t not in known:
                typos.append("%s → %s" % (mid, t))
    if typos:
        print("FATAL 点名了不存在的 testcase（名字抄错，读数会全假）:")
        for t in typos:
            print("  " + t)
        return 2

    lock_fh, lock_path = acquire_lock()
    if lock_fh is None:
        return 4

    baseline = {k: md5(abspath(k)) for k in SRC}
    rows = []
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0, "NO-RUN": 0}

    def flush_ledger():
        with io.open(os.path.join(HERE, "LEDGER.tsv"), "w", encoding="utf-8") as fh:
            fh.write("\t".join(["id", "family", "target", "testcase", "injection",
                                "named_kills", "allow_extra", "verdict", "detail"]) + "\n")
            for r in rows:
                fh.write("\t".join(r) + "\n")
            fh.write("\t".join(["#tally", "", "", "", "",
                                "RED-OK=%d|PARTIAL=%d|GREEN-BUT-MUTATED=%d|BROKEN=%d|NO-RUN=%d"
                                % (tally["RED-OK"], tally["PARTIAL"],
                                   tally["GREEN-BUT-MUTATED"], tally["BROKEN"], tally["NO-RUN"]),
                                "", "", ""]) + "\n")
            fh.write("\t".join(["#mutants_injected", "", "", "", "", "", "",
                                str(len([r for r in rows if not r[0].startswith("CTRL-")])), ""]) + "\n")
            fh.write("\t".join(["#suite_total_at_HEAD", "", "", "", "", "", "",
                                str(SUITE_TOTAL), ""]) + "\n")
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "", "p30b_mutation.py",
                                time.strftime("%Y-%m-%dT%H:%M:%S%z")]) + "\n")

    # ===== 阳性对照：每族 injection=NONE，点名的 testcase 必须真跑到且全绿 =====
    prey_ok = {}
    for fam in sorted(FAMILY_PREY):
        named = sorted(FAMILY_PREY[fam])
        if not named:
            prey_ok[fam] = False
            rows.append(["CTRL-" + fam, fam, "-", "(无点名 testcase)", "NONE", "-", "-", "NO-RUN",
                         "该族没有任何点名 testcase"])
            tally["NO-RUN"] += 1
            flush_ledger()
            continue
        rc, failing, ran, out, secs, sel = run_named(named)
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0:
            verdict = "NO-RUN"
        elif failing:
            verdict = "NO-RUN"
        else:
            verdict = "OK"
        prey_ok[fam] = verdict == "OK"
        rows.append(["CTRL-" + fam, fam, "-", sel, "NONE", ",".join(named), "-", verdict,
                     "ran=%d rc=%s %.1fs 红=%s" % (ran, rc, secs,
                                                   ",".join(sorted(failing)) or "-")])
        print("[对照] %-12s %-6s ran=%d 点名=%d %.1fs %s" % (fam, verdict, ran, len(named), secs,
                                                            ",".join(sorted(failing)) or "全绿"),
              flush=True)
        flush_ledger()

    # ===== 逐支注入 =====
    for mid, fam, key, old, new, want, named, extra, note in mutants:
        if not prey_ok.get(fam, False):
            rows.append([mid, fam, SRC[key], "-", "SKIPPED", ",".join(named), ",".join(extra),
                         "NO-RUN", "阳性对照没进猎物：CTRL-%s 判 NO-RUN/BROKEN" % fam])
            tally["NO-RUN"] += 1
            print("%-46s NO-RUN（阳性对照失败）" % mid, flush=True)
            flush_ledger()
            continue
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, out, secs, sel = run_named(sorted(set(named) | set(extra)))
        if "COMPILATION ERROR" in out:
            # 同机别人的 mvn 会跟我抢同一个 target/ —— 编译类失败重跑一次再定性
            time.sleep(20)
            rc, failing, ran, out, secs, sel = run_named(sorted(set(named) | set(extra)))
        write(key, original)
        restored = md5(abspath(key)) == baseline[key]
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        elif ran == 0:
            verdict = "NO-RUN"
        else:
            hit = set(named) & failing
            unexpected = failing - set(named) - set(extra)
            if hit == set(named) and not unexpected:
                verdict = "RED-OK"
            elif hit or failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        who = ",".join(sorted(failing)) if failing else "全绿"
        print("%-46s %-18s 杀手 %d/%d ran=%d rc=%s %.1fs 还原=%s | %s"
              % (mid, verdict, len(set(named) & failing), len(named), ran, rc, secs, restored, who),
              flush=True)
        rows.append([mid, fam, SRC[key], sel,
                     "\\n".join(new.split("\n"))[:90] or "(删掉锚点)",
                     ",".join(named), ",".join(extra) or "-", verdict,
                     "红=%s ran=%d rc=%s %.1fs 还原=%s" % (who, ran, rc, secs, restored)])
        flush_ledger()
        if not restored:
            print("FATAL: %s 之后没还原成基线，停在这里（后面读数不可信）" % mid)
            break

    diff = subprocess.run(["git", "diff", "--name-only"], cwd=ZBOT,
                          stdout=subprocess.PIPE).stdout.decode("utf-8", "replace").strip()
    untracked = subprocess.run(["git", "status", "--porcelain"], cwd=ZBOT,
                               stdout=subprocess.PIPE).stdout.decode("utf-8", "replace")
    src_changed = [k for k in SRC if md5(abspath(k)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-18s %d" % (k, tally[k]))
    print("  注入后 src 有差异的文件: %s" % (src_changed or "无"))
    print("  git diff --name-only : %s" % (diff or "（空）"))
    print("  git status --porcelain : %s" % (untracked.strip().replace("\n", " | ") or "（空）"))
    return 0 if not src_changed else 3


if __name__ == "__main__":
    sys.exit(main())
