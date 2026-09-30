#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P30c（飞书面 GET 门外回显拆掉 + 平铺 v1 容错划边界）变异检验。

口径照 `_doc/acceptance/p30b/p30b_mutation.py`（同一套四杠纪律），判据一字不动：
  RED-OK   点名全红，且没有 `named ∪ allow_extra` 之外的红
  PARTIAL  点名一部分红 / 红了预期之外的人
  GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
  BROKEN   编译不过   NO-RUN  阳性对照没进猎物 / ran=0

本期两条裁定各自要有牙（"拆掉了"与"没加"都是负向主张，负向主张必须有猎物）：
  * **D-P30-1（GET ?echostr= 是企微形状，不是飞书的）** —— C1 把旧的那半轴原样装回去
    （门外回显查询参数），C2 只把 405 松成 200 但不回显。两支打的是同一条用例的两个断言：
    C1 证"回显不许留下 attacker 那半轴"这句有牙，C2 证"405"这句不是空话 —— 少了 C2，
    一个"200 + 空 body"的实现也能过，而那仍然是"GET 有人应答"。
  * **D-P30-2（平铺容错只认代码里已经读过的键）** —— C3 摘掉平铺 `event.text` 的读取，
    证那条"多认一手"确有消费者（否则它是死代码，注释里的"两种形状都得认"就是空话）；
    C5 反过来给**没有出处**的 `event.content` 开一个读取点，证新那支边界用例真能拦住它。
  * C4 是 P30 那条门序（先 token 后回显 challenge）在新树里的复测：拆掉 GET 之后
    这一面唯一的回显口就是它，不能因为"本期没碰它"就不量。

纪律（一条都不许破）：
  * **预期红集先写死在本文件里**，跑之前随脚本一起 commit ⇒ 不许事后凑；
  * 注入前逐条校验锚点出现次数，并校验"点名的 testcase 真实存在"（名字抄错一个字母，
    "没红"会被读成"变异体活了"）；
  * 每支只点名它自己的 testcase，不跑全量；
  * **阳性对照**：每族先跑一次 `injection=NONE`，`named ∪ allow_extra` 必须真跑到(ran>0)且全绿；
  * 还原只从**本次运行开始时读进的原文**逐字节写回（绝不 `git checkout` —— git 基线是 HEAD，
    不是我开始测量那一刻），每支跑完 md5 对账；
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲。

明确不注入的（行为不可区分，注了只产出假读数，按**未覆盖**记账）：
  * **类 javadoc / 行内注释里那段出处对照**：注释不是可观察行为，测试结构上读不到它。
    "容错要写明它是容错"这句话由 `FeishuChannel.java:450-456` 的文本与 review 看守。
  * **删掉 `queryParams` helper 之后再加回去**：它当时已零调用点，加了也不改变任何响应，
    不会有用例红（这正是本期把它删掉的理由 —— 留着就是一条没人调的死代码）。

安全：注入只动 `channel/FeishuChannel.java` 一个文件；全程只连 127.0.0.1，
不碰 `~/.zbot`（红线 1），不读任何真凭据，不给真域名发一个包。

复算: python3 -u _doc/acceptance/p30c/p30c_mutation.py [id 子串...]
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
    "fei": "z-bot-core/src/main/java/com/zifang/z/bot/channel/FeishuChannel.java",
}

T = "FeishuChannelTest#"
GET_ARM = [T + "getEchostrIsNotAnsweredOnTheFeishuFace"]
FLAT_ARM = [T + "postEventWithValidTokenDeliversToBus"]
BOUNDARY = [T + "flatV1ToleranceStopsAtTheKeysAlreadyReadThere"]
CHAL_GATE = [T + "urlVerificationChallengeIsNotEchoedWithoutValidToken"]

# (id, family, 文件键, old, new, 锚点次数, named 杀手, allow_extra 连带, 说明)
MUTANTS = [
    # ===== C-GET形状（D-P30-1）=====
    ("C1 把 GET ?echostr= 门外回显原样装回去", "C-GET形状", "fei",
     '            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {\n'
     '                text(ex, 405, "method not allowed");\n',
     '            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {\n'
     '                String q = ex.getRequestURI().getRawQuery();\n'
     '                String echostr = "";\n'
     '                if (q != null) {\n'
     '                    for (String pair : q.split("&")) {\n'
     '                        int eq = pair.indexOf(\'=\');\n'
     '                        if (eq > 0 && "echostr".equals(pair.substring(0, eq))) {\n'
     '                            echostr = java.net.URLDecoder.decode(pair.substring(eq + 1),\n'
     '                                    StandardCharsets.UTF_8);\n'
     '                        }\n'
     '                    }\n'
     '                }\n'
     '                text(ex, 200, echostr);\n'
     '                return;\n'
     '            }\n'
     '            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {\n'
     '                text(ex, 405, "method not allowed");\n', 1,
     GET_ARM, [],
     "这就是被拆掉的那半轴：`text(ex, 200, echostr)` 排在验签/token 门**之前**，且内容是攻击者"
     "可控的查询参数原文。装回去 ⇒ 点名那支的两条断言（405、不回显 attacker 串）都该红。"
     "注入体全用 FQN + 只依赖 `text()`/`StandardCharsets`，因为原 `queryParams` helper 已随收口删掉。"),

    ("C2 405 松成 200（形状门失效但不回显）", "C-GET形状", "fei",
     '                text(ex, 405, "method not allowed");',
     '                text(ex, 200, "{\\"ok\\":true}");', 1,
     GET_ARM, [],
     "与 C1 打同一条用例的**另一条断言**。少了这一支，'不回显'也可以是'整个 GET 门变成 200 空应答'"
     "读出来的 —— 而 GET/PUT/DELETE 一律 405 才是本期钉的形状合同（类 javadoc :57）。"),

    # ===== C-平铺读取点（D-P30-2）=====
    ("C3 摘掉平铺 v1 的 event.text 读取", "C-平铺读取点", "fei",
     '                String text = firstNonEmpty(messageText(msg), str(evt.get("text")));',
     '                String text = firstNonEmpty(messageText(msg), "");', 1,
     FLAT_ARM, [],
     "注释说'两种形状都得认'。这一刀证那句不是空话：平铺那半支确有消费者（`sender_id`/`chat_id`/"
     "`chat_type`/`text` 四键里正文这一路）。若它红了说明容错是活的；若它全绿，那段注释就该删。"),

    ("C5 给无出处的 event.content 开一个读取点", "C-平铺读取点", "fei",
     '                String text = firstNonEmpty(messageText(msg), str(evt.get("text")));',
     '                String text = firstNonEmpty(messageText(msg), str(evt.get("text")),\n'
     '                        str(evt.get("content")));', 1,
     BOUNDARY, [],
     "本期裁定'不照它加读取点'。光写在注释里，'没加'与'加了但没人管'在测试里长得一模一样 ⇒ "
     "新增那支边界用例必须能拦住这一刀：注入了它就该红（无出处键产出了一条投递）。"
     "v2 夹具的 `content` 在 `event.message.content` 上、不在 `event.content`，所以连带应当为空。"),

    # ===== C-token门序（P30 主张在新树里复测）=====
    ("C4 challenge 抢在 token 门之前回显", "C-token门序", "fei",
     '            if (verificationToken != null && !verificationToken.isEmpty()\n'
     '                    && !verificationToken.equals(token)) {',
     '            if ("url_verification".equals(str(parsed.get("type")))) {\n'
     '                Map<String, Object> pre = new HashMap<String, Object>();\n'
     '                pre.put("challenge", str(parsed.get("challenge")));\n'
     '                byte[] pb = JSON.writeValueAsBytes(pre);\n'
     '                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");\n'
     '                ex.sendResponseHeaders(200, pb.length);\n'
     '                ex.getResponseBody().write(pb);\n'
     '                return;\n'
     '            }\n'
     '            if (verificationToken != null && !verificationToken.isEmpty()\n'
     '                    && !verificationToken.equals(token)) {', 1,
     CHAL_GATE, [],
     "拆掉 GET 之后，这一面唯一的'把请求内容原样吐回去'的口就是 challenge。它必须仍在 token 门之后"
     "（hermes 同一顺序：`adapter.py:3552-3569` 先比 token 再回显）。点名只这一支：`postEventWithBadToken"
     "Returns401` 的 body 没有 `type` 字段、`encryptedUrlVerificationEchoesDecryptedChallenge` 的 token 是对的，"
     "两者结构上都看不见这一刀 —— 这就是它要单列一支的理由，不是覆盖重叠。"),
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
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "", "p30c_mutation.py",
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
        print("[对照] %-14s %-6s ran=%d 点名=%d %.1fs %s" % (fam, verdict, ran, len(named), secs,
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
