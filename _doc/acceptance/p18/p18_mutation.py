#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P18（通道注册表 SPI + 飞书/钉钉真出站）变异检验：把本期每一条守卫逐条改坏，
看有没有**具名 testcase**（`Class#method`）判红。

纪律（照 _doc/acceptance/p17/p17_mutation.py 的口径，工单 p18b §杠② 的六族要求）：
  * **预期红集先写死在本文件里**，跑之前随脚本一起 commit ⇒ 不许事后凑；
  * 注入前逐条校验锚点出现次数（锚点漂了 = 量具坏了，直接 FATAL，不收读数）；
  * 每支变异体只点名它自己的 testcase（`-Dtest=Class#m1+m2`），不跑全量；
  * 判定只认 surefire XML 里的 testcase 名：
      RED-OK             点名的全红、没有别的红
      PARTIAL            点名的一部分红 / 红了别人
      GREEN-BUT-MUTATED  全绿 ⇒ 断言缺口（如实记账，不改判据凑绿）
      BROKEN             编译不过
      NO-RUN             阳性对照进不来（配不到猎物），写明原因，不算分
  * **阳性对照**：每族先跑一次 `injection=NONE`，点名的 testcase 必须全绿且真跑到
    （ran>0），否则该族所有变异体记 NO-RUN —— 拿空跑当满分就是这一条；
  * 每个变异体跑完按内存里的原文逐字节还原，收尾对全量 md5 + `git status --porcelain`；
  * LEDGER.tsv 只由本脚本机械输出，禁止手敲。

安全：注入只动 `channel/*` 与 `channel/channels.builtin.properties`（本期写域），
不动 `HttpChannel.java`/`BotConfig.java`/`agent`/`tool`；出站一律指向进程内假端点
（`FakeImEndpoint`，`bind(0)`），全程不对真域名发包；本脚本不读 `~/.zbot` 任何凭据。

复算: python3 -u _doc/acceptance/p18/p18_mutation.py [id 子串...]
锁:   $(git rev-parse --git-common-dir)/zbot-mutlock  —— 只 try-lock，抢不到就退出
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
BASELINE_TOTAL = 668          # 杠① 三跑的起点（fbb5f86 原树，本棒未改产品码）
PER_RUN_TIMEOUT = 900         # 单支变异体的 mvn 上限（秒）

SRC = {
    "reg": "z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelRegistry.java",
    "fei": "z-bot-core/src/main/java/com/zifang/z/bot/channel/FeishuChannel.java",
    "din": "z-bot-core/src/main/java/com/zifang/z/bot/channel/DingTalkChannel.java",
    "con": "z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpConsoleChannel.java",
    "mani": "z-bot-core/src/main/resources/com/zifang/z/bot/channel/channels.builtin.properties",
}

# (id, family, 文件键, old, new, 点名期望红的 testcase(Class#method), 说明)
MUTANTS = [
    # ===== A 族：后写覆盖语义 =====
    ("A1 --channel-manifest 第四层根本不参与合并", "A-后写覆盖", "reg",
     "            r.read(explicit);\n", "", 1,
     ["ChannelRegistryTest#explicitManifestLayerBeatsProfileAndMissingFileFailsLoudly"],
     "CLI 显式给的 manifest 被静默忽略：用户以为覆盖了，实际按 profile 装配"),

    ("A2 config 字段改成先写赢（putIfAbsent）", "A-后写覆盖", "reg",
     '            s.config.put(tail.substring("config.".length()), value);',
     '            if (!s.config.containsKey(tail.substring("config.".length()))) {\n'
     '                s.config.put(tail.substring("config.".length()), value);\n'
     '            }', 1,
     ["ChannelRegistryTest#laterPropertiesLayerWinsFieldByField",
      "ChannelRegistryTest#profileManifestOverridesBuiltinAndCliBeatsProfile"],
     "后写覆盖变先写赢：profile 里改 api-base/键值永远不生效"),

    ("A3 缺省档根本不读（只认 profile）", "A-后写覆盖", "reg",
     "        r.readBuiltin();\n", "", 1,
     ["ChannelRegistryTest#builtinManifestDeclaresFourKindsWithExpectedFlags",
      "ChannelRegistryTest#namesAreSortedNotHashtableOrder"],
     "三层来源少一层：requires/default-port/outbound 的形状全部消失"),

    ("A4 CLI 覆盖层被吞（override 直接返回）", "A-后写覆盖", "reg",
     "        public Context override(String name, String key, String value) {\n"
     "            if (value == null) {\n                return this;\n            }\n",
     "        public Context override(String name, String key, String value) {\n"
     "            if (value != null) {\n                return this;\n            }\n", 1,
     ["ChannelRegistryTest#profileManifestOverridesBuiltinAndCliBeatsProfile"],
     "--port/--webhook-port 不再是最高优先级，现有命令行语义回归"),

    # ===== B 族：惰性构造（数出来的，不是读注释）=====
    ("B1 registerFactory 时预热该 kind 的全部声明", "B-惰性", "reg",
     "    public ChannelRegistry registerFactory(String kind, ChannelFactory factory) {\n"
     "        factories.put(kind, factory);\n        return this;\n    }",
     "    public ChannelRegistry registerFactory(String kind, ChannelFactory factory) {\n"
     "        factories.put(kind, factory);\n"
     "        for (String n : new ArrayList<String>(specs.keySet())) {\n"
     "            Spec s = specs.get(n);\n"
     "            if (kind.equals(s.kind()) && s.enabled()) {\n"
     "                try {\n                    create(n, new Context(null, null, null));\n"
     "                } catch (Exception ignored) {\n                }\n"
     "            }\n"
     "        }\n        return this;\n    }", 1,
     ["ChannelRegistryTest#loadingSpecsConstructsNothing"],
     "注册工厂即构造通道：启动期反射/实例化所有平台类，工单第 0 步证伪 ⑤ 要防的就是这个"),

    ("B2 读声明（specs()）就顺手构造", "B-惰性", "reg",
     "    public List<Spec> specs() {\n"
     "        return new ArrayList<Spec>(specs.values());\n    }",
     "    public List<Spec> specs() {\n"
     "        for (String n : new ArrayList<String>(specs.keySet())) {\n"
     "            try {\n                create(n, new Context(null, null, null));\n"
     "            } catch (Exception ignored) {\n            }\n"
     "        }\n"
     "        return new ArrayList<Spec>(specs.values());\n    }", 1,
     ["ChannelRegistryTest#loadingSpecsConstructsNothing"],
     "`z-bot status` 这类只读声明的调用方会把所有通道都拉起来"),

    # ===== C 族：显式降级（缺键必须点名）=====
    ("C1 create() 的缺键抛点整个摘掉", "C-显式降级", "reg",
     '        if (!lacking.isEmpty()) {\n'
     '            throw new ChannelConfigException("channel." + name + "（kind=" + s.kind()\n'
     '                    + "）缺配置键: " + String.join(", ", lacking)\n'
     '                    + " —— 来源 " + s.declaredBy(), lacking);\n'
     "        }\n", "", 1,
     ["ChannelRegistryTest#requiresMissingKeysAreNamedExplicitly",
      "ChannelRegistryTest#createAllCollectsFailuresInsteadOfSilentlyDegrading"],
     "红线：没凭据也照样产出通道 ⇒ 后面每一条投递都变成『看起来成功了』的坑"),

    ("C2 抛了但不点名缺哪个键", "C-显式降级", "reg",
     '                    + "）缺配置键: " + String.join(", ", lacking)\n',
     '                    + "）缺配置键（未点名）"\n', 1,
     ["ChannelRegistryTest#requiresMissingKeysAreNamedExplicitly",
      "ChannelRegistryTest#createAllCollectsFailuresInsteadOfSilentlyDegrading"],
     "报错有、键名没有：用户对着 manifest 猜哪个键没配"),

    ("C3 飞书缺凭据改抛无名 IOException", "C-显式降级", "fei",
     '            throw new ChannelConfigException("feishu 出站缺配置键: " + String.join(", ", lacking)\n'
     '                    + " —— 要么给 " + KEY_APP_ID + "+" + KEY_APP_SECRET + "（换取 tenant_access_token），"\n'
     '                    + "要么直接给 " + KEY_STATIC_TOKEN, lacking);',
     '            throw new IOException("feishu 出站不可用");', 1,
     ["FeishuOutboundTest#missingCredentialsThrowsNamingKeysAndSendsNothing",
      "FeishuOutboundTest#halfConfiguredCredentialsNameOnlyTheMissingKey"],
     "降级还是降级，但从『缺 app-id』变成一句废话；半配置那支要求只点名缺的那一个"),

    ("C4 钉钉缺 webhook-url 的检查摘掉", "C-显式降级", "din",
     "        List<String> lacking = missingCredentialKeys();\n"
     "        if (!lacking.isEmpty()) {\n"
     '            throw new ChannelConfigException("dingtalk 出站缺配置键: " + String.join(", ", lacking)\n'
     '                    + " —— 群机器人需要一个带 access_token 的 webhook-url"\n'
     '                    + (secret == null || secret.trim().isEmpty()\n'
     '                            ? "（可选：再配 " + KEY_SECRET + " 开加签）" : ""), lacking);\n'
     "        }\n", "", 1,
     ["DingTalkOutboundTest#missingWebhookUrlThrowsNamingKeyAndSendsNothing",
      "DingTalkOutboundTest#blankWebhookUrlIsTreatedAsMissingNotAsEmptyTarget"],
     "没 URL 也往下走：请求打到 null，异常文案里可能带出 access_token"),

    ("C5 缺省档把 feishu 打开（enabled=false→true）", "C-显式降级", "mani",
     "channel.feishu.enabled=false", "channel.feishu.enabled=true", 1,
     ["ChannelRegistryTest#builtinManifestDeclaresFourKindsWithExpectedFlags",
      "ChannelRegistryTest#createOnDisabledSpecIsAnExplicitRefusal"],
     "不随包发凭据却随包起通道：默认安装第一次 createAll 就报一堆缺键"),

    # ===== D 族：飞书 token 换取 + im/v1/messages 的请求字节 =====
    ("D1 token 请求体键名 app_id→appid", "D-飞书协议", "fei",
     '        req.put("app_id", appId);', '        req.put("appid", appId);', 1,
     ["FeishuOutboundTest#tokenExchangeIsRealPostWithAppIdAndSecret"],
     "真域名会当场 400；假端点断言的是发出去的字节，不是本地对象"),

    ("D2 Authorization 头换成非 Bearer 形状", "D-飞书协议", "fei",
     '                .header("Authorization", "Bearer " + token)',
     '                .header("X-Feishu-Token", token)', 1,
     ["FeishuOutboundTest#messagePostCarriesBearerTokenAndFeishuBodyShape"],
     "飞书只认 Bearer：头名/头值写错就是永远 401，而本地单测看不出来除非断言线上字节"),

    ("D3 发送路径 /im/v1/messages→/im/v1/message", "D-飞书协议", "fei",
     '    public static final String MESSAGE_PATH = "/im/v1/messages";',
     '    public static final String MESSAGE_PATH = "/im/v1/message";', 1,
     ["FeishuOutboundTest#messagePostCarriesBearerTokenAndFeishuBodyShape",
      "FeishuChannelTest#outgoingUrlIsStable",
      "ChannelRegistryTest#profileManifestOverridesBuiltinAndCliBeatsProfile"],
     "URL 单复数：广告『发得出消息』靠的就是这条路径"),

    ("D4 token 路径写错", "D-飞书协议", "fei",
     '    public static final String TOKEN_PATH = "/auth/v3/tenant_access_token/internal";',
     '    public static final String TOKEN_PATH = "/auth/v3/tenant_access_token";', 1,
     ["FeishuOutboundTest#tokenExchangeIsRealPostWithAppIdAndSecret"],
     "换不到 token ⇒ 后面每一条发送都是『没凭据的重试』"),

    ("D5 忽略平台 code（HTTP 200 就当送达）", "D-飞书协议", "fei",
     "        if (r.status < 200 || r.status >= 300 || r.platformCode != 0) {",
     "        if (r.status < 200 || r.status >= 300) {", 1,
     ["FeishuOutboundTest#successfulSendQueuesDeliveredPayloadAndFailureDoesNot",
      "FeishuOutboundTest#chatLevelNotFoundIsClassifiedAsDeadTarget"],
     "P16 红线 8 的正脸：平台回 code≠0 也记 delivered=true，死目标再也判不出来"),

    ("D6 token 提前续期余量归零", "D-飞书协议", "fei",
     "    static final long TOKEN_REFRESH_MARGIN_MS = 60_000L;",
     "    static final long TOKEN_REFRESH_MARGIN_MS = 0L;", 1,
     ["FeishuOutboundTest#shortExpiryForcesRefetchBecauseOfRefreshMargin"],
     "卡在过期边界上用废 token，随机 401"),

    ("D7 token 被判废不再作废缓存重发", "D-飞书协议", "fei",
     "        return r.status == 401 || r.status == 403\n"
     "                || r.platformCode == 99991661 || r.platformCode == 99991663 || r.platformCode == 99991664;",
     "        return false;", 1,
     ["FeishuOutboundTest#authRejectionInvalidatesCacheRefetchesAndRetriesOnce"],
     "缓存里的废 token 会被一直用到天荒地老"),

    ("D8 content 不再字符串化", "D-飞书协议", "fei",
     '        body.put("content", JSON.writeValueAsString(content));',
     '        body.put("content", content);', 1,
     ["FeishuOutboundTest#messagePostCarriesBearerTokenAndFeishuBodyShape"],
     "飞书要求 content 是字符串化的 JSON；给对象就是一条 400"),

    ("D9 入站 verificationToken 比对形同虚设", "D-飞书协议", "fei",
     "                    && !verificationToken.equals(token)) {",
     "                    && !verificationToken.equals(verificationToken)) {", 1,
     ["FeishuChannelTest#postEventWithBadTokenReturns401"],
     "任何人都能伪造成飞书事件打进总线"),

    ("D10 verifySignature 恒真（无错签名负例可杀）", "D-飞书协议", "fei",
     "            return hex.toString().equals(signature);",
     "            return hex.toString().equals(hex.toString());", 1,
     ["FeishuChannelTest#signatureHelperRejectsWrongDigest",
      "FeishuChannelTest#tamperedSignatureIsRejectedWith401AndNeverReachesBus",
      "FeishuChannelTest#missingSignatureHeadersAreRejectedWhenEncryptKeyConfigured"],
     "p18b 那两跑这一支是 GREEN-BUT-MUTATED（LEDGER D10 行原样记着）：当时 verifySignature 在 main 里"
     "零调用方、也没有一支断言『错签名必须被拒』。p18c 把它接进 handleEvent 并补了负例 ⇒ 期望集"
     "随代码变更**重声明**（不是事后凑）：算法层 1 支 + 接线层 2 支，恒真实现三红。"),

    ("D11 handleEvent 摘掉验签守卫（配了 key 也不验）", "D-飞书协议", "fei",
     "            if (encryptKey != null && !encryptKey.isEmpty()) {",
     "            if (false) {", 1,
     ["FeishuChannelTest#tamperedSignatureIsRejectedWith401AndNeverReachesBus",
      "FeishuChannelTest#missingSignatureHeadersAreRejectedWhenEncryptKeyConfigured"],
     "这一支打的是『接线』而不是算法：签名函数照旧正确但没人调 ⇒ 只有 HTTP 层负例能红，"
     "helper 层那几支结构上看不见（D10 与 D11 因此互补，缺一层就是空档）。"),

    # ===== E 族：钉钉 webhook 签名 =====
    ("E1 签名原文丢掉换行", "E-钉钉签名", "din",
     '        String stringToSign = ts + "\\n" + secret;',
     '        String stringToSign = ts + secret;', 1,
     ["DingTalkOutboundTest#sendPostsTextBodyToSignedWebhookUrl"],
     "钉钉服务端重算签名 ⇒ 每条都 INVALID_SIGN，而本地只会觉得发出去了"),

    ("E2 URL 上不拼 sign", "E-钉钉签名", "din",
     '                    + "timestamp=" + ts + "&sign=" + sign;',
     '                    + "timestamp=" + ts;', 1,
     ["DingTalkOutboundTest#rawWireIsARealSignedPostAndReplyIsJson",
      "DingTalkChannelSignTest#signedUrlHasTimestampAndSignQuery"],
     "加签等于没加：过线字节里没有 sign"),

    ("E3 忽略 errcode（HTTP 200 就当送达）", "E-钉钉签名", "din",
     "        if (status < 200 || status >= 300 || errcode != 0) {",
     "        if (status < 200 || status >= 300) {", 1,
     ["DingTalkOutboundTest#errcodeNonZeroIsFailureAndIsClassifiedForDeadTargets",
      "DingTalkOutboundTest#deliveredFlagIsOnlyWrittenOnRealSuccess"],
     "钉钉的错误全在 errcode 里；忽略它就是『投递成功』的谎"),

    ("E4 scrubUrl 不再洗 sign", "E-钉钉签名", "din",
     '                .replaceAll("(?i)(sign=)[^&#]*", "$1***");',
     '                ;', 1,
     ["DingTalkOutboundTest#scrubUrlCoversTokenAndSignShapes"],
     "签名值进日志/异常文案（同一串在有效期内可重放）"),

    ("E5 没配 secret 也硬拼签名参数", "E-钉钉签名", "din",
     "        if (secret == null || secret.isEmpty()) {\n            return webhookUrl;\n        }",
     "        if (secret == null || secret.isEmpty()) {\n"
     '            return webhookUrl + "?timestamp=0&sign=none";\n        }', 1,
     ["DingTalkOutboundTest#unsignedRobotSendsWithoutTimestampAndSign"],
     "未开加签的机器人被塞了个假 sign ⇒ 直接被钉钉拒掉"),

    # ===== F 族：HttpConsoleChannel 拆分后的 web 面 =====
    ("F1 控制台通道改名", "F-web面", "con",
     '    public String name() {\n        return "http";\n    }',
     '    public String name() {\n        return "http-console";\n    }', 1,
     ["ChannelRegistryTest#outboundFlagIsRecordedForCreatedChannels"],
     "通道名是投递路由的键：改名之后 cron/fan-out 找不到控制台"),

    ("F2 start() 不再委托真控制台", "F-web面", "con",
     "    public void start() throws Exception {\n        inner.start();\n    }",
     "    public void start() throws Exception {\n        // 变异：不委托\n    }", 1,
     [],
     "工单②的『拆分没剥掉 web 面』要证的正是这条委托；进程内单测里没有一支真去 GET 控制台 "
     "⇒ 预期判绿，杀它的证据在 p18_e2e.py 的 A 段（真 JVM 起 gateway 之后 GET /index.html）"),

    ("F3 outbound 映射写死 true", "F-web面", "reg",
     "        outboundByChannelName.put(ch.name(), Boolean.valueOf(s.outbound()));",
     "        outboundByChannelName.put(ch.name(), Boolean.TRUE);", 1,
     ["ChannelRegistryTest#outboundFlagIsRecordedForCreatedChannels"],
     "manifest 的 outbound 声明位失去兑现路径：拉模式控制台又变回『投递目标』"),

    ("F4 缺省档把 http.outbound 写成 true", "F-web面", "mani",
     "channel.http.outbound=false", "channel.http.outbound=true", 1,
     ["ChannelRegistryTest#builtinManifestDeclaresFourKindsWithExpectedFlags",
      "ChannelRegistryTest#outboundFlagIsRecordedForCreatedChannels"],
     "上面那条是代码不读声明，这条是声明本身写错：两刀都要判红"),
]

FAMILY_PREY = {}
for _m in MUTANTS:
    FAMILY_PREY.setdefault(_m[1], set()).update(_m[6])


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
    """Class#method+method,Class2#... —— 只点名 testcase，不跑全量。"""
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
    for m in mutants:
        mid, fam, key, old, new, want, expected, note = m
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
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
                                "expected_red_set", "verdict", "detail"]) + "\n")
            for r in rows:
                fh.write("\t".join(r) + "\n")
            fh.write("\t".join(["#tally", "", "", "", "",
                                "RED-OK=%d|PARTIAL=%d|GREEN-BUT-MUTATED=%d|BROKEN=%d|NO-RUN=%d"
                                % (tally["RED-OK"], tally["PARTIAL"],
                                   tally["GREEN-BUT-MUTATED"], tally["BROKEN"], tally["NO-RUN"]),
                                "", ""]) + "\n")
            fh.write("\t".join(["#mutants_injected", "", "", "", "", "",
                                str(len([r for r in rows if not r[0].startswith("CTRL-")])), ""]) + "\n")
            fh.write("\t".join(["#baseline_total_full_tests", "", "", "", "", "",
                                str(BASELINE_TOTAL), ""]) + "\n")
            fh.write("\t".join(["#generated_by", "", "", "", "", "", "p18_mutation.py",
                                time.strftime("%Y-%m-%dT%H:%M:%S%z")]) + "\n")

    # ===== 阳性对照：每族 injection=NONE，点名的 testcase 必须真跑到且全绿 =====
    prey_ok = {}
    for fam in sorted(FAMILY_PREY):
        named = sorted(FAMILY_PREY[fam])
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
        elif ran == 0:
            verdict = "NO-RUN"
        elif failing:
            verdict = "NO-RUN"
        else:
            verdict = "OK"
        prey_ok[fam] = verdict == "OK"
        rows.append(["CTRL-" + fam, fam, "-", sel, "NONE", ",".join(named), verdict,
                     "ran=%d rc=%s %.1fs 红=%s" % (ran, rc, secs,
                                                   ",".join(sorted(failing)) or "-")])
        print("[对照] %-12s %-6s ran=%d 点名=%d %.1fs %s" % (fam, verdict, ran, len(named), secs,
                                                        ",".join(sorted(failing)) or "全绿"),
              flush=True)
        flush_ledger()

    # ===== 逐支注入 =====
    for mid, fam, key, old, new, want, expected, note in mutants:
        if not expected:
            expected = []
        if not prey_ok.get(fam, False):
            rows.append([mid, fam, SRC[key], ",".join(expected), "SKIPPED",
                         ",".join(expected), "NO-RUN", "阳性对照没进猎物：CTRL-%s 判 %s"
                         % (fam, "NO-RUN")])
            tally["NO-RUN"] += 1
            print("%-46s NO-RUN（阳性对照失败）" % mid, flush=True)
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
            others = failing - set(expected)
            if hit and not others and len(hit) == len(expected):
                verdict = "RED-OK"
            elif hit:
                verdict = "PARTIAL"
            elif failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        who = ",".join(sorted(failing)) if failing else "全绿"
        print("%-46s %-18s 点名 %d/%d ran=%d rc=%s %.1fs 还原=%s | %s"
              % (mid, verdict, len(set(expected) & failing), len(expected), ran, rc, secs,
                 restored, who), flush=True)
        rows.append([mid, fam, SRC[key], sel,
                     "\\n".join(new.split("\n"))[:90] or "(删掉锚点)", ",".join(expected),
                     verdict, "红=%s ran=%d rc=%s %.1fs 还原=%s" % (who, ran, rc, secs, restored)])
        flush_ledger()
        if not restored:
            print("FATAL: %s 之后没还原成基线，停在这里（后面读数不可信）" % mid)
            break

    # ===== 还原对账：md5 + git status =====
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
