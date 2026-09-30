#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P30（入站鉴真：飞书 SHA-256 验签 + `{"encrypt":…}` AES-256-CBC 解密；钉钉入站 sign + 时间窗）
变异检验：把本期每一条守卫逐条改坏，看有没有**具名 testcase** 判红。

口径照 `_doc/005_testing/acceptance/p18/p18_mutation.py`（同一套四杠纪律），两点不同：
  * 每支变异体分「点名杀手」`named`（必须红的机制级 testcase）与「连带红」`allow_extra`
    （机制一改就必然跟着红的其余用例，**跑前一并写死**）。判据：
      RED-OK   点名全红，且没有 `named ∪ allow_extra` 之外的红
      PARTIAL  点名一部分红 / 红了预期之外的人
      GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
      BROKEN   编译不过   NO-RUN  阳性对照没进猎物 / ran=0
    分这两层的理由：一支真变异会同时打掉多条正向用例，把它们混进"点名"里，
    RED-OK 就退化成"红了任意几条"，机制级杀手到底存不存在就又被糊掉了。
  * 只收 p18 没覆盖的机制。p18 的 D10（飞书签名恒真）与 D11（摘掉验签接线）继续归它管；
    本期把 SHA-1 实现换成 SHA-256 之后 D10 的锚点(found=0)已随之重锚，两边不重复计账。

纪律（一条都不许破）：
  * **预期红集先写死在本文件里**，跑之前随脚本一起 commit ⇒ 不许事后凑；
  * 注入前逐条校验锚点出现次数（锚点漂了 = 量具坏了，直接 FATAL，不收读数）；
  * 每支只点名它自己的 testcase（`-Dtest=Class#m1+m2`），不跑全量；
  * **阳性对照**：每族先跑一次 `injection=NONE`，`named ∪ allow_extra` 必须真跑到(ran>0)且全绿，
    否则该族全部记 NO-RUN —— 拿空跑当满分就是这一条；
  * 还原只从**本次运行开始时读进的原文**逐字节写回（绝不 `git checkout` —— 那会连本期未提交的
    被测改动一起抹掉），每支跑完 md5 对账，收尾再对全量 md5 + `git status --porcelain`；
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲。

明确不注入的（等价变异，注了只会产出假读数）：
  * `decryptEvent` 里 `blob.length < 32 || blob.length % 16 != 0` 这道形状守卫：摘掉之后
    短密文走的仍是 `catch → return null`，两条臂都返回 null ⇒ 输出不可分辨。真要做判别，
    得先让"形状不对"与"解密失败"在 HTTP 层可区分（现在都是 400），本期按**未覆盖**记账。
  * `messageText` 的 `!"text".equals(message_type)` 早退：换成别的类型判据时，非文本消息
    的 content 里没有 `text` 键 ⇒ 仍然投不出消息，`nonTextMessageType…` 那支看不出差别。

安全：注入只动 `channel/FeishuChannel.java`、`channel/DingTalkChannel.java`、
`channel/ChannelRegistry.java` 三个本期写域文件；全程进程内，不碰 `~/.zbot`（红线 1），
不读任何真凭据，不给真域名发一个包。

复算: python3 -u _doc/005_testing/acceptance/p30/p30_mutation.py [id 子串...]
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
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir, os.pardir))
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
    "din": "z-bot-core/src/main/java/com/zifang/z/bot/channel/DingTalkChannel.java",
    "reg": "z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelRegistry.java",
}

FEI_POSTIVES = [  # 飞书：凡是"带正确签名进来且要走到业务"的用例，摘要一错就全红
    "FeishuChannelTest#postEventWithValidTokenDeliversToBus",
    "FeishuChannelTest#postEventWithBadTokenReturns401",
    "FeishuChannelTest#encryptedV2EventIsVerifiedDecryptedAndDelivered",
    "FeishuChannelTest#v2EventWithHeaderTokenIsDeliveredUnencrypted",
    "FeishuChannelTest#nonTextMessageTypeIsNotDeliveredAsEmptyMessage",
    "FeishuChannelTest#encryptedEventThatCannotBeDecryptedGets400AndNeverReachesBus",
    "FeishuChannelTest#encryptedUrlVerificationEchoesDecryptedChallenge",
    "FeishuChannelTest#signatureHelperAcceptsCorrectDigest",
]

DIN_GATE_NEG = [  # 钉钉：入站门一失效就该红的四支负例
    "DingTalkInboundVerifyTest#foreignKeysSignatureIsRejectedWith401AndNeverReachesBus",
    "DingTalkInboundVerifyTest#missingSignHeadersAreRejectedWhenSecretConfigured",
    "DingTalkInboundVerifyTest#nonNumericTimestampIsRejectedWith401AndNeverReachesBus",
    "DingTalkInboundVerifyTest#staleTimestampIsRejectedEvenWithValidSign",
    "DingTalkInboundVerifyTest#futureTimestampBeyondWindowIsRejected",
    "DingTalkInboundVerifyTest#inboundSecretOverridesWebhookSecret",
    "DingTalkInboundVerifyTest#registryManifestWiresInboundSecretKey",
]

# (id, family, 文件键, old, new, 锚点次数, named 杀手, allow_extra 连带, 说明)
MUTANTS = [
    # ===== G 族：飞书签名摘要（P30 订正的那处算法错就是这个）=====
    ("G1 摘要退回 SHA-1（P18 的错算法）", "G-飞书摘要", "fei",
     '            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");',
     '            MessageDigest sha256 = MessageDigest.getInstance("SHA-1");', 1,
     ["FeishuChannelTest#signatureHelperMatchesFeishuKnownAnswer",
      "FeishuChannelTest#sha1SignedEventIsRejectedWith401AndNeverReachesBus"] + [],
     FEI_POSTIVES,
     "本期修掉的正是这一条：飞书事件订阅的签名是 SHA-256，实现里写的是 SHA-1 ⇒ 每一条真事件都 401，"
     "而 P18 那 12 支绿测试一条都没发现（它们拿实现自己的算法当预期）。两支杀手各司其职："
     "helper 层拿 python 的已知答案钉'认对的'，HTTP 层那支钉'代 SHA-1 摘要的签名必须被拒'。"),

    ("G2 原始 body 不进摘要", "G-飞书摘要", "fei",
     "            sha256.update(rawBody);\n", "", 1,
     ["FeishuChannelTest#signatureHelperMatchesFeishuKnownAnswer",
      "FeishuChannelTest#signatureHelperRejectsWrongDigest"] + [],
     FEI_POSTIVES,
     "签名只算 ts+nonce+key ⇒ 正文随便改都过。首跑（05:48）这一条判 PARTIAL：`RejectsWrongDigest` "
     "里'内容改一个字节'那一臂**结构上杀不动** —— 它的预期签名是测试自己按'含 body'算的，"
     "实现不算 body 时两边照样不相等 ⇒ 巧合绿，不是断到了。已补一臂'只算 ts+nonce+key 的签名必须拒'"
     "（攻击者形状，G2 的猎物）。留下的教训同 I2：一条负例能不能杀某个变异，取决于它期望值的算法，"
     "不取决于它的标题写的是不是'内容被改'。"),

    ("G3 签名比对照搬大小写敏感（去掉 toLowerCase）", "G-飞书摘要", "fei",
     "            byte[] theirs = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);",
     "            byte[] theirs = signature.trim().getBytes(StandardCharsets.US_ASCII);", 1,
     ["FeishuChannelTest#signatureHelperMatchesFeishuKnownAnswer"],
     [],
     "飞书文档明说十六进制签名大小写不敏感 ⇒ 判红就是丢单。这一支同时证明'文档里那句大写也行'"
     "不是随手写的注释，它有且只有一支用例在兑现。"),

    # ===== H 族：飞书 {"encrypt":…} 解密与 v2 事件形状 =====
    ("H1 handleEvent 摘掉解密分支", "H-飞书解密", "fei",
     '            String cipher = str(parsed.get("encrypt"));\n            if (!cipher.isEmpty()) {',
     '            String cipher = str(parsed.get("encrypt"));\n            if (false) {', 1,
     ["FeishuChannelTest#encryptedV2EventIsVerifiedDecryptedAndDelivered",
      "FeishuChannelTest#encryptedUrlVerificationEchoesDecryptedChallenge",
      "FeishuChannelTest#encryptedEventThatCannotBeDecryptedGets400AndNeverReachesBus"],
     [],
     "P30 之前加密模式是死路：外层信封没有顶层 token ⇒ 配了 verification-token 就每一条 401。"
     "摘掉解密分支 = 退回那个形状，三条加密用例同时红。"),

    ("H2 解不开不再 400（拿密文原文继续走）", "H-飞书解密", "fei",
     "                String plain = decryptEvent(cipher);\n                if (plain == null) {",
     "                String plain = decryptEvent(cipher);\n                if (false) {", 1,
     ["FeishuChannelTest#encryptedEventThatCannotBeDecryptedGets400AndNeverReachesBus"],
     [],
     "'解密失败'被降级成'字段缺失'（后面 parseObject(null) ⇒ 空 map ⇒ token 门 401）：既错报了原因，"
     "也让'过签名门就把剩下当可信'这个坏习惯留在代码里。"),

    ("H4 AES 密钥不取 SHA-256（直接拿 encrypt_key 字节）", "H-飞书解密", "fei",
     "            byte[] key = MessageDigest.getInstance(\"SHA-256\")\n"
     "                    .digest(encryptKey.getBytes(StandardCharsets.UTF_8));",
     "            byte[] key = encryptKey.getBytes(StandardCharsets.UTF_8);", 1,
     ["FeishuChannelTest#decryptEventMatchesOpensslKnownAnswer",
      "FeishuChannelTest#encryptedV2EventIsVerifiedDecryptedAndDelivered",
      "FeishuChannelTest#encryptedUrlVerificationEchoesDecryptedChallenge"],
     [],
     "飞书的密钥派生是 sha256(encrypt_key)。fixture 那把 key 只有 15 字节 ⇒ 换实现后连"
     "SecretKeySpec 都建不起来，三条一起红（红得干脆，不是靠运气）。"),

    ("H5 IV 不再从密文头 16 字节取（换成全零 IV）", "H-飞书解密", "fei",
     "            byte[] iv = Arrays.copyOfRange(blob, 0, 16);",
     "            byte[] iv = new byte[16];", 1,
     ["FeishuChannelTest#decryptEventMatchesOpensslKnownAnswer",
      "FeishuChannelTest#encryptedV2EventIsVerifiedDecryptedAndDelivered",
      "FeishuChannelTest#encryptedUrlVerificationEchoesDecryptedChallenge"],
     [],
     "IV 位置取错 ⇒ 首块全乱。已知答案那支是逐字符比 500 字节明文，一个字符都不许多；"
     "另两支证的是'解出来还能投递'。"),

    ("H6 v2 事件的 header.token 不读", "H-飞书解密", "fei",
     '            String token = nestedStr(parsed.get("header"), "token");',
     '            String token = "";', 1,
     ["FeishuChannelTest#v2EventWithHeaderTokenIsDeliveredUnencrypted",
      "FeishuChannelTest#encryptedV2EventIsVerifiedDecryptedAndDelivered",
      "FeishuChannelTest#nonTextMessageTypeIsNotDeliveredAsEmptyMessage"],
     [],
     "只看顶层 token 是 v1 的形状；v2.0 的真事件 token 在 header 里 ⇒ 每一条都会被 401 掉，"
     "而本地那几条 v1 用例照样绿（这就是为什么要点名 v2 形状的用例）。"),

    ("H7 message.content 不再拆字符串化 JSON", "H-飞书解密", "fei",
     '        return nestedStr(parseObject(str(message.get("content"))), "text");',
     '        return str(message.get("content"));', 1,
     ["FeishuChannelTest#v2EventWithHeaderTokenIsDeliveredUnencrypted",
      "FeishuChannelTest#encryptedV2EventIsVerifiedDecryptedAndDelivered"],
     [],
     "飞书把正文塞在 content 这个**字符串化**的 JSON 里；不拆就把 `{\"text\":\"加密正文\"}` 整串"
     "当用户消息投进总线 —— 用户在飞书里看到一条 JSON，而测试只要不比对正文就看不见。"),

    # ===== I 族：钉钉入站验签（P30 之前这个端点一条测试都没有）=====
    ("I1 handleInbound 摘掉验签门", "I-钉钉入站", "din",
     "            if (!verifyInbound(ex.getRequestHeaders().getFirst(HEADER_TIMESTAMP),\n"
     "                    ex.getRequestHeaders().getFirst(HEADER_SIGN))) {",
     "            if (false && !verifyInbound(ex.getRequestHeaders().getFirst(HEADER_TIMESTAMP),\n"
     "                    ex.getRequestHeaders().getFirst(HEADER_SIGN))) {", 1,
     DIN_GATE_NEG, [],
     "本期补的那个洞本身：`POST /dingtalk/in` 过去是'广告里有验签、代码里没有'，"
     "任何能连到端口的人都能伪造成用户消息 ⇒ 7 支负例全红才算门在。"),

    ("I2 入站签名比对恒真", "I-钉钉入站", "din",
     "            byte[] expect = inboundSign(key, raw).getBytes(StandardCharsets.US_ASCII);",
     "            byte[] expect = sign.trim().getBytes(StandardCharsets.US_ASCII);", 1,
     ["DingTalkInboundVerifyTest#foreignKeysSignatureIsRejectedWith401AndNeverReachesBus",
      "DingTalkInboundVerifyTest#inboundSecretOverridesWebhookSecret",
      "DingTalkInboundVerifyTest#registryManifestWiresInboundSecretKey"],
     [],
     "打的是比对而不是门。首跑（05:48）判 PARTIAL：我当时按主题把 6 支负例都点进来了，但 "
     "`nonNumericTimestamp`（DingTalkChannel.java:419 parseLong 就 return false）、"
     "`staleTimestamp` / `futureTimestampBeyondWindow`（:424 时间窗 return false）"
     "都**在比对之前**就出局 ⇒ 恒真比对结构上看不见。`missingSignHeaders…` 同理（:414 null 守卫）。"
     "预期集只能按'谁读这个值'派生，不能按'这条话题相关的用例'派生 —— 这是同一个错的第二现"
     "（第一现是 p27 的按主题点名单）。杀到的 3 支已覆盖两层：通道层 2 支 + 工厂接线层 1 支。"),

    ("I3 时间窗改成单向（只挡过去）", "I-钉钉入站", "din",
     "        if (Math.abs(System.currentTimeMillis() - ts) > INBOUND_MAX_SKEW_MS) {",
     "        if (System.currentTimeMillis() - ts > INBOUND_MAX_SKEW_MS) {", 1,
     ["DingTalkInboundVerifyTest#futureTimestampBeyondWindowIsRejected"], [],
     "超前的时间戳带着合法签名可以长期有效（客户端时钟偏一点就中招）⇒ 窗口必须是 |now-ts|。"),

    ("I4 时间窗放宽到一天", "I-钉钉入站", "din",
     "    static final long INBOUND_MAX_SKEW_MS = 3_600_000L;",
     "    static final long INBOUND_MAX_SKEW_MS = 86_400_000L;", 1,
     ["DingTalkInboundVerifyTest#inboundWindowIsTheOneHourTheDocAllows"], [],
     "行为层那几支负例的偏移是**相对常量**取的（各超出 2 分钟），把常量改成一天它们照样绿 ⇒"
     "这一刀只有钉数字的那支能红。留下的教训：任何『窗口 = N』式的判据，只要用例按 N 取偏移，"
     "N 本身就是无人看守的常量 —— 要么钉死数字，要么负例改用绝对时刻。"),

    ("I5 工厂不传 inbound-secret", "I-钉钉入站", "reg",
     "                        ctx.value(spec, DingTalkChannel.KEY_INBOUND_SECRET));",
     "                        null);", 1,
     ["DingTalkInboundVerifyTest#registryManifestWiresInboundSecretKey"], [],
     "键声明了、构造器也有参数，唯独工厂不传 ⇒ manifest 里的 inbound-secret 是个装饰。"
     "直接调构造器的用例结构上看不见这一刀，所以必须有一支走 ChannelRegistry 的接线用例。"),

    ("I6 inboundKey 恒退回 webhook secret（显式密钥被忽略）", "I-钉钉入站", "din",
     "        if (inboundSecret != null && !inboundSecret.trim().isEmpty()) {\n"
     "            return inboundSecret.trim();\n        }",
     "        if (false) {\n            return inboundSecret.trim();\n        }", 1,
     ["DingTalkInboundVerifyTest#inboundSecretOverridesWebhookSecret",
      "DingTalkInboundVerifyTest#registryManifestWiresInboundSecretKey"], [],
     "企业内部机器人的 appSecret 与 webhook 加签密钥不是同一把；优先级写反 = 换了入站密钥后"
     "所有入站都在拿旧密钥验，全线 401（`blank…FallsBackToWebhookSecret` 那支反向对照保证它不红，"
     "证的是'回退'这一半还在）。"),
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
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "", "p30_mutation.py",
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
