# P30 验收证据 —— 飞书入站（SHA-256 验签 + `{"encrypt":…}` AES-256-CBC 解密）与钉钉入站验签

> 主编亲测（不是代理自述）。工作树 = 主树 `z-bot`，基线 `HEAD = 916cfe7`（`main`）。
> 本文件里每个数字都是**本机跑过命令的原始输出**；`*.log` 被 `.gitignore` 吃掉 ⇒ 决定性读数一律原样粘进来。
> 四杠的测量窗口：09-27 **05:55:24–06:22:43**（`date` 逐轮现量，见各节时间戳）。

---

## §0 被测的是哪一版树（先说清，别让"提交树口径"变成空话）

四杠跑完、本节落笔时，工作树相对 `916cfe7` 的全部差异（`git status --porcelain` @ 06:23:53）：

```
 M README.md                                        ← §7 那两条"没做的"改口 + 新增两条真缺口
 M _doc/acceptance/p18/LEDGER.tsv                   ← 脚本产出台账（p18 族复跑的原地覆写）
 M _doc/acceptance/p30/p30_mutation.py              ← 两支点名单按机制改写（见 §2.1—§2.2）
 M _doc/hermes-roadmap.md                           ← §8.14 + 四处旧主张的〔订正〕标注
 M z-bot-core/src/test/java/.../FeishuChannelTest.java  ← +7 行，G2 的猎物臂（见 §2.2）
?? _doc/acceptance/p30/EVIDENCE.md                  ← 本文件
?? _doc/acceptance/p30/LEDGER.tsv                   ← 复跑台账（脚本独占）
?? _doc/acceptance/p30/LEDGER-run1.tsv              ← 首跑台账（PARTIAL 那两行留证）
```

**`src/main` 一个字节都没动**，而杠②/杠①量的就是它。三个生产文件与 `HEAD` 逐字节相同（注入批跑完后独立复核，不靠脚本自报的"还原=True"）：

```
$ for f in FeishuChannel DingTalkChannel ChannelRegistry; do
    p=z-bot-core/src/main/java/com/zifang/z/bot/channel/$f.java
    echo "$(md5 -q $p)  vs  $(git show HEAD:$p | md5 -q)"
  done
a9c683e52e4363c5ba712a01c37df51e  vs  a9c683e52e4363c5ba712a01c37df51e   FeishuChannel.java
43863b6ab1310bedf635489ef950a2b6  vs  43863b6ab1310bedf635489ef950a2b6   DingTalkChannel.java
1282717f59a9ae1f8c32c1c4bff8c018  vs  1282717f59a9ae1f8c32c1c4bff8c018   ChannelRegistry.java
```

### 0.1 规模尺对账（三把独立量具，谁也不套谁）

| 量具 | 命令 | 读数 |
|---|---|---|
| 文本尺 `@Test`（工作树） | `git grep -c '@Test' -- 'z-bot-core/src/test' \| awk -F: '{s+=$NF} END {print s}'` | **1188**（112 个文件有命中） |
| 文本尺 `@Test`（HEAD） | `git grep -c '@Test' HEAD -- 'z-bot-core/src/test' \| …` | **1188**（本轮 src/test 的改动是在既有方法体内加断言 ⇒ 不增 `@Test`） |
| surefire（执行数） | `mvn -o test` 全量 reactor | **1185** |
| surefire XML 求和 | `python3` 遍历 `*/target/surefire-reports/TEST-*.xml` | `tests=1185 failures=0 errors=0 skipped=0 files=110` |

1188 − 1185 = **3**，且这 3 处命中全在注释里（z-bot 已知的那类干扰源，所以文本尺不能单独用）：

```
$ grep -rn '@Test' z-bot-core/src/test --include='*.java' | grep -E '//|\*'
ui/RawTerminalVerdictProbe.java:7          ← "不是测试类（名字不以 Test 结尾 ⇒ surefire 不跑它、不占 @Test 分母）"
llm/P26RetryPolicyTest.java:37             ← "每个 @Test 的名字里带它钉的规则名"
memory/MemoryE2eDriver.java:16             ← "不是测试，没有 {@code @Test}，surefire 不收"
```

112 个命中文件 − 2 个"只有注释命中"的非测试类（`RawTerminalVerdictProbe`、`MemoryE2eDriver`）= **110** == XML 文件数 **110**。两把尺闭合。

---

## §1 杠① 全量 reactor ×3 串行（同机，跑期间一个字没碰）

杠① 跑了**两遍 ×3**：06:07–06:10 那遍之后我还改了 README 与 roadmap（README 里的数字主张由 `ReadmeClaimsTest` 机器核，所以文档也算被量的一部分）⇒ 提交前在**最终树**上重跑一遍，下面粘的是最终那遍（06:19:23–06:22:43）。两遍的模块级读数逐字相同（`1185/0/0/0`），差别只有每轮耗时 64–70s。

```
$ for i in 1 2 3; do find . -type d -name surefire-reports -prune -exec rm -rf {}; mvn -o test; done
scale: working-tree @Test sum=1188   HEAD @Test sum=1188  files=112
--- ROUND 1  rc=0  70s  06:20:33
[INFO] Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
--- ROUND 2  rc=0  64s  06:21:38
[INFO] Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
--- ROUND 3  rc=0  65s  06:22:43
[INFO] Tests run: 1185, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
xml_sum tests=1185 failures=0 errors=0 skipped=0 files=110
```

三跑逐字相同（1185/0/0/0 + `BUILD SUCCESS` + `rc=0`）。**无 `-pl`**，reactor 三模块都过（另外两个是 pom 打包模块，结构上不产测试，所以模块级 `Tests run:` 行只有 1 条 —— 这一条不是我筛掉了别人：`grep -cE 'Tests run:.*Failures:.*Skipped: *[0-9]+ *$'` = 1，而 Reactor Summary 里 `z-bot-core … SUCCESS [01:03 min]` 与 `z-bot-desktop-packager … SUCCESS [  0.022 s]` 并列可见）：

```
[INFO] z-bot (Local Agent App, cc/Hermes style) ........... SUCCESS [  0.080 s]
[INFO] z-bot-core ......................................... SUCCESS [01:03 min]
[INFO] z-bot-desktop-packager ............................. SUCCESS [  0.022 s]
```

---

## §2 杠② 具名变异（`p30_mutation.py`，15 支 = G 3 + H 6 + I 6）

> H 族实际编号是 `H1/H2/H4/H5/H6/H7` —— **`H3` 是编号空洞，不是一支"没跑的变异"**（总数以 LEDGER 的 `#mutants_injected=15` 为准，不按编号区间推）。

### 2.1 首跑（05:48）：13 RED-OK + **2 PARTIAL**，两支都归因到我自己的点名单

```
#tally   RED-OK=13|PARTIAL=2|GREEN-BUT-MUTATED=0|BROKEN=0|NO-RUN=0        ← _doc/acceptance/p30/LEDGER-run1.tsv
G2 原始 body 不进摘要   PARTIAL  杀手 1/2 ran=10 rc=1   还原=True
I2 入站签名比对恒真     PARTIAL  杀手 3/6 ran=6 rc=1   还原=True
```

两支都不是产品缺陷，是**预期红集按主题点将、没按"谁读这个值"派生**（同一个错的第二现，第一现是 p27 的名单）：

- **I2**：我把 6 支"钉钉入站负例"全点进去了，但 `nonNumericTimestamp`（`DingTalkChannel.java:419` `parseLong` 就 `return false`）、`staleTimestamp` / `futureTimestampBeyondWindow`（`:424` 时间窗 `return false`）都**在比对之前**出局，恒真比对（`:431`）结构上看不见它们；`missingSignHeaders…`（`:414` null 守卫）同理。真正杀到的 3 支已覆盖两层：通道层 2 支 + 工厂接线层 1 支 ⇒ 洞有牙，只是名字点多了。
- **G2**：`signatureHelperRejectsWrongDigest` 里"内容改一个字节"那一臂**巧合绿** —— 它的预期签名是测试自己按"**含** body"算的（`sha256Hex((ts+nonce+key), bytes(body))`），实现不算 body 时两边照样不相等 ⇒ 它从没断过"摘要必须含 body"这句话，只是标题像。

### 2.2 修法：补一支真猎物 + 按机制改写两支名单

补在 `FeishuChannelTest.signatureHelperRejectsWrongDigest` 里的一臂（G2 的正主猎物）：

```java
// 签名只算 ts+nonce+key（攻击者形状），body 随你改 ⇒ 必须拒
assertFalse("不含 body 的摘要必须拒（G2 的猎物）",
        ch.verifySignature("1700000000", "n-1", bytes(body),
                sha256Hex(("1700000000" + "n-1" + "my-secret").getBytes(StandardCharsets.UTF_8),
                        new byte[0])));
```

新臂**无注入时必须绿**（否则它"杀掉"每一支变异都不算数）—— 单独跑整类对账：

```
$ mvn -o -q test -pl z-bot-core -Dtest=FeishuChannelTest      rc=0
class tally: tests=21 fail=0 err=0 skip=0
signatureHelperAcceptsCorrectDigest green
signatureHelperRejectsMismatchWithoutEncryptKey green
signatureHelperRejectsWrongDigest green      ← 新臂在这里，未注入时绿
signatureHelperMatchesFeishuKnownAnswer green
```

### 2.3 复跑（05:57，同一批 15 支，改后名单）：**15/15 RED-OK，零空跑**

```
[对照] G-飞书摘要       OK     ran=11 点名=11 5.2s 全绿
[对照] H-飞书解密       OK     ran=6  点名=6  2.0s 全绿
[对照] I-钉钉入站       OK     ran=8  点名=8  2.9s 全绿
#tally RED-OK=15|PARTIAL=0|GREEN-BUT-MUTATED=0|BROKEN=0|NO-RUN=0     ← LEDGER.tsv
#mutants_injected=15        #suite_total_at_HEAD=1188      #generated_by=p30_mutation.py 2026-09-27T05:57:24+0800
注入后 src 有差异的文件: 无   （每支逐条 `还原=True`）
```

G2 复跑读数：`RED-OK 杀手 2/2 ran=10 rc=1 4.2s 还原=True`，红名单里同时出现 `signatureHelperMatchesFeishuKnownAnswer`（正方向：python 的已知答案必须认）与 `signatureHelperRejectsWrongDigest`（负方向：不含 body 的签名必须拒）⇒ 两个半轴各钉一层。I2 复跑：`RED-OK 杀手 3/3 ran=6`。

各族杀手的机制层归属（完整逐支见 `_doc/acceptance/p30/LEDGER.tsv`，脚本独占产出，手敲不算台账）：

| 族 | 支数 | 打的是什么 | 至少一层是"结构上别的用例看不见"的 |
|---|---|---|---|
| G 摘要算法 | 3 | 摘退回 SHA-1 / 摘掉 body 进摘要 / 大小写敏感 | G3 只有钉 KAT 的那支能红 |
| H 解密与 v2 形状 | 6 | 摘解密分支 / 解不开不 400 / 密钥不取 SHA-256 / IV 位置 / 不读 `header.token` / 不拆字符串化 `content` | H6 只有 v2 形状的用例能红（v1 用例看不见） |
| I 入站门 | 6 | 摘门 / 比对恒真 / 窗口单向 / 窗口放宽一天 / 工厂不传键 / 显式密钥被忽略 | I4 只有钉 `3_600_000L` 数字的那支能红；I5 只有走 `ChannelRegistry` 的那支能红 |

### 2.4 p18 族在改后树上整批复跑（SHA-256 改的就是它量的那段，欠不得）

```
#tally RED-OK=30|PARTIAL=0|GREEN-BUT-MUTATED=0|BROKEN=0|NO-RUN=1   #mutants_injected=31  2026-09-27T06:03:00+0800
D10 verifySignature 恒真（无错签名负例可杀）    RED-OK  点名 3/3 ran=3 rc=1 4.9s 还原=True
D11 handleEvent 摘掉验签守卫（配了 key 也不验）  RED-OK  点名 2/2 ran=2 rc=1 4.2s 还原=True
```

唯一 `NO-RUN` 的 `F2 start() 不再委托真控制台`（`红=全绿 ran=0 rc=0 69.8s`）是历史那条：surefire 在该变异下不收这个类 ⇒ 记未覆盖，不记通过。

p18 的 E2E 也在新树复跑 3 轮：`PASS=41 FAIL=0`、`###ROUND1/2/3 rc=0`（06:03:51 / 06:03:57 / 06:04:03），其 `feishu_inbound` 现在按 **SHA-256** 签、`ding_inbound` 现在带签名头 —— 这两处原先都是"照着实现的错算法抄一份"的替身。

---

## §3 杠③ 真进程 E2E（`p30_e2e.py`，3 轮，假 IM/假 LLM 都在 127.0.0.1）

```
== 汇总 ==
PASS=53 FAIL=0 轮数=3 用时 73.3s
     · 现场: /Users/zifang/.cache/zbot-p30/e2e ；~/.zbot 条目 before/after=8/8
```

分母对账（不是"看着像 53"）：`grep -c '^PASS R1/'`=16、`R2/`=16、`R3/`=16、`^PASS H`=5 ⇒ **16×3+5=53** == 汇总行。

关键几支（原文照贴）：

```
PASS R3/A1 外部 key 签的飞书事件 401 且零出站        status=401 body={"error":"signature mismatch"} 出站=0
PASS R2/C3 只配 secret 时按 secret 验签必须收         status=200 body={"ok":true}
PASS R2/C2 未配任何钉钉密钥：未签名入站照样进得来      status=200 body={"ok":true}
PASS H2 我占过的端口全部释放（lsof 复扫）             残留=无
PASS H3 ps -o lstart 复核：没有我还活着的本次 JVM      还活着=无（9 个 pid 全部退出）
PASS H4 真数据根 ~/.zbot 未被触碰                     n=8 config=2dadaed0 state=690ddbc0
PASS H5 现场文件里没有 100+ 字符的类 key 长串          扫了 128 份，命中=无；尺的阳性对照=125 字符合成串命中
```

C2 那半轴证的是"门不是恒关"：`inbound-secret` 与 `secret` 都没配的第三个 JVM（`with_keys=False, with_ding_secret=False`）。这一支首跑判红过，红的是我的前提（`secret` 本身就是入站回退密钥 ⇒ 只关一把关不掉），改的是量具的 JVM 配置、**没有放松任何断言**。

---

## §4 杠④ `~/.zbot` 不变量（四杠全程采样）

| 采样点 | 条目数 | `config.properties` md5 前 8 | `state.db` md5 前 8 | `minimax.api.key` 只量长度 |
|---|---|---|---|---|
| 杠② 每轮（p30/p18 脚本内） | 8 | `2dadaed0` | `690ddbc0` | 125 |
| 杠① 三轮 round1/2/3 | 8 | `2dadaed0` | `690ddbc0` | 125 |
| 杠③ 内嵌 H4（before/after） | 8 | `2dadaed0` | `690ddbc0` | — |
| 杠① 收尾 | 8 | `2dadaed0` | `690ddbc0` | — |

命令：`ls -A ~/.zbot | wc -l`、`md5 -q ~/.zbot/{config.properties,state.db}`、`awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties`。那把 key 是真 token，全程只量长度、没读没印没进任何日志。

---

## §5 权威出处（这次改的是"照抄实现"的错，所以每个字节都要有外部来源）

| 主张 | 出处（本机安装的源码，不是二手转述） |
|---|---|
| 飞书事件订阅签名 = `sha256((timestamp+nonce+encrypt_key) 的 UTF-8 字节 + 原始 body 字节)` 的 hex，大小写不敏感 | `lark_oapi` **1.5.3**（`pip3 show lark_oapi` 实量）`event/dispatcher_handler.py:173-182`，关键两行 `:179` `bs = (timestamp + nonce + self._encrypt_key).encode(UTF_8) + request.body` / `:180` `h = hashlib.sha256(bs)`；调用点 `:91` |
| `encrypt_key` 为空 ⇒ SDK **直接 return**（不校验）—— 我们"配了 key 才关门"的口径出处 | 同上 `:174-175` |
| 解密：key = `sha256(encrypt_key).digest()`，IV = 密文前 16 字节，`AES/CBC/PKCS7`，先 base64 解码 | `lark_oapi` `core/utils/decryptor.py:10-24`（`:14` 取摘要当 key、`:17` `iv = enc[:AES.block_size]`、`:18` `MODE_CBC`、末尾 `s[:-ord(s[-1:])]` 剥 PKCS7、`decrypt_str` 先 `base64.b64decode`） |
| 钉钉机器人**接收**消息：`sign = base64(HMAC-SHA256(secret, ts + "\n" + secret))` **且** `\|now-ts\| ≤ 1 小时`，两个条件都要 | 钉钉开放平台《接收消息》原文 + `DingTalkChannel.java:409-438` 逐条兑现（`:424` 窗口、`:431-433` 比对） |
| 对标侧：hermes 的飞书适配是**字符串级** `sha256(f"{ts}{nonce}{key}{body_str}")`（`errors="replace"` 解码）+ `hmac.compare_digest` | `~/.hermes/hermes-agent` @ `cbc1054e2`：`plugins/platforms/feishu/adapter.py:3606-3621`（`computed = hashlib.sha256(content.encode("utf-8")).hexdigest()` 在 `:3621`）；token 比对 `:3555-3557` / `:3622-3624`；入站限速 `:3505` 调 `:3629` |
| hermes 的钉钉走 **`dingtalk-stream`、根本没有 webhook 入站面** ⇒ 我们那 `POST /dingtalk/in` 是自己多的，只能自己按文档钉 | `plugins/platforms/dingtalk/adapter.py:4`（"without webhooks"）、`:40-41` `import dingtalk_stream` |
| **SHA-1 属于 wecom 不属于飞书/钉钉** —— P18 那版写错的摘要十有八九是从这里串了台 | `plugins/platforms/wecom/wecom_crypto.py:61-63`（`_sha1_signature`），用它比对的在 `:89` |

已知答案（KAT）不是从代码里读的，是 `python3 hashlib` / `openssl` 现算后钉进测试常量的：`FeishuChannelTest` 的 `KAT_SIG` / `KAT_CJK_SIG`（含中文 body 那一臂管的是"字节序不是字符序"）、`decryptEventMatchesOpensslKnownAnswer`（逐字符比 500 字节明文）。

---

## §6 量的时候新撞出来的缺口（记账，不在本期动）

- **D-P30-1｜`GET /feishu/event?echostr=` 是一条门外的回显。** `FeishuChannel.java:388-399` 在 POST 的验签/token 门**之前**返回 `echostr` 原文，不看 token、不看签名；而真正的 `url_verification` 是 POST + challenge，那一条已经改到 token 门之后才回显（`:439-447`，P30 本期收的）。缺省只绑回环 ⇒ 暴露面有限，但它是"任何能连端口的人都能验证自己打到了这个回调"。**未裁定**：GET 那半轴到底是不是飞书会用的形状（`FeishuChannelTest.java:154` 自己就写着"形状存疑"）—— 要一份权威出处才动手，改判 401 会改掉那支测试钉的形状。
- **D-P30-2｜v1 平铺事件只找 `event.text`，不找 `event.content`。** `:461` 是 `firstNonEmpty(messageText(msg), str(evt.get("text")))`；`messageText` 走的是 v2 的 `message.content`（字符串化 JSON，`:712`），而 v1 把正文同样埋在 **`event.content`** 这个字符串化 JSON 里 —— 全文件 `grep 'evt.get("content")'` ⇒ 0 命中。后果是"解得开却投成空文本"，而 `text.isEmpty()` 那道守卫会让它静默丢弃（不是投空串）。**未裁定**：同样缺一份能说明 v1 平铺形状字段名的权威出处；先记账。
- **D-P30-3｜入站 body 无上限，四个入站面里只有一个把门放在读之前。** 尺：`grep -rn 'MAX_BODY\|content-length\|getContentLength\|maxBodySize' z-bot-core/src/main/java/com/zifang/z/bot/channel/` ⇒ **0 命中**（四个 reader 都是 `while (is.read(buf)) baos.write(...)` 全量进堆：`FeishuChannel.java:633-643`、`DingTalkChannel.java:489-497`、`WebhookChannel.java:261-269`、`HttpChannel.java:949-963`）。顺序逐面实测：

  | 面 | 读 body | 鉴权 | 门在读之前？ |
  |---|---|---|---|
  | 钉钉 `/dingtalk/in` | `:360` | `verifyInbound` `:355` | **是**（唯一一处；没配密钥时门本身是开的） |
  | 飞书 `/feishu/event` | `:405` | `verifySignature` `:408-416` | **否** —— 本期刚加的门排在分配之后 |
  | webhook `/webhook/in` | `:158` | 无（只判方法） | 否 |
  | http `/bot/*` | `:385,388,391,401,415,417,425,427` | 无 | 否 |

  hermes 在同位置是"先限量再谈签名"：`plugins/platforms/wecom/callback_adapter.py:57-59`（`# Cap pre-auth request bodies` + `_MAX_BODY = 65_536`）、`:147`（`# (413) before our handler — and before any signature work — runs.`）、`:294`（`web.Response(status=413, text="payload too large")`）。已开任务 #47（P30b：共享的有界读取 + 门在分配之前，配具名测试和两支互补变异：常量层 / 接线层）。

---

## §7 本期仍然欠的

1. **真凭据零验证**：所有出站证据仍是"对 127.0.0.1 假端点发出的字节"，本机没有飞书/钉钉凭据 ⇒ 真协议握手（token 换取、`im/v1/messages` 送达、真事件签名进得来）在本机**结构上量不到**。
2. **§2 的三处点名单是本轮才改对的**，说明"按主题点将"这个病还在别的族里：p25/p27 两族的预期集没做同样的"谁读这个值"复查（p27 在 P27c-G1 做过一次，p25 没做过）。
3. **D-P30-1 / D-P30-2 等权威出处**，拿到才改；不拿"我觉得"当出处。
4. 杠①②③④ 是**同机串行**的一遍；CI 那把尺（z-mcp 的先例：本机全绿而 CI 判红）本期没接。
5. `<revision>` 仍 `0.2.0`、未发 Central、外部工程真 pull 未验 —— 三件都在 z-bot 的推送授权之外，等点头。
