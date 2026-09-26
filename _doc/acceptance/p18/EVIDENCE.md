# P18 验收 EVIDENCE — 通道注册表 SPI + 飞书/钉钉真出站

- 工作树 `/private/tmp/zbot-wt-p18`，分支 `w4-p18`，起点 `926b8b5`
- 工单 `/Users/zifang/.cache/zbot-p17/dispatch_p18.md`（p18a）→ 收口棒工单 `/Users/zifang/.cache/zbot-p17/dispatch_p18b.md`（本棒：只补四杠与验收工件，产品码为 p18a 封存的 `fbb5f86`）
- 现场/日志根 `~/.cache/zbot-p18/`（`.gitignore:5` = `*.log` ⇒ 决定性读数原样贴本文）

## 0. 第 0 步实测（复算工单假设）

| # | 假设 | 复算命令 | 实测 | 判定 |
|---|---|---|---|---|
| 0-1 | channel 面 16 个 .java | `ls z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java \| wc -l` | `16` | 成立 |
| 0-2 | 合计 5032 行 | `cat z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java \| wc -l` | `5032` | 成立 |
| 0-2b | 头部规模 | `wc -l .../channel/*.java \| sort -rn \| head -8` | `DeliveryLedger 831 / HttpChannel 741 / Supervisor 569 / ChannelBus 397 / TurnLease 339 / TerminalChannel 338 / Gateway 336 / FeishuChannel 331`（DingTalkChannel 265） | 成立；工单没提 DeliveryLedger 才是最大的那个 |
| 0-3 | 飞书/钉钉 0 生产构造点 | `git grep -n "new FeishuChannel\|new DingTalkChannel" -- 'z-bot-core/src/main/java'` | `rc=1`，0 命中 | **成立**（工单②）⇒ 注册表就是给它们接生产线路 |
| 0-3b | 别的通道有构造点 | `git grep -n "new HttpChannel\|new WebhookChannel\|new TerminalChannel" -- 'z-bot-core/src/main/java'` | `cli/GatewayCommand.java:61`（HttpChannel）、`:63`（WebhookChannel）、`cli/ReplCommand.java:23`、`cli/ServeCommand.java:30` | 成立 |
| 0-4 | 出站是 stub | `grep -n "stub" .../channel/FeishuChannel.java`、`.../DingTalkChannel.java`（改动前） | Feishu `:42 没有真发 HTTP 给飞书 / :152 仅日志 / :153 [feishu:stub] / :235 token 永远 static 或 "stub" / :304 URL 只算不发`（工单写 306，实测 304）；DingTalk `:28 / :134 [dingtalk:stub]` | 成立（工单③） |
| 0-5 | okhttp 已在依赖 | `grep -n "okhttp" z-bot-core/pom.xml pom.xml` | `pom.xml:71 <okhttp.version>4.12.0`、`pom.xml:164-166` depMgmt、`z-bot-core/pom.xml:84-85` 直接依赖 | 成立 ⇒ 本期零新依赖 |
| 0-6 | kernel-app 是空模块 | `grep -n "z-agent-kernel" z-bot-core/pom.xml \| head -20`；`unzip -l ~/.m2/repository/io/github/yuku123/z-agent-kernel-app/0.2.1/*.jar \| grep '\.class$'` | 直依赖 12 个 kernel 模块（工单说 11，实测数出 12 行 artifactId），**其中没有 kernel-app**；jar 里只有 `com/zifang/z/agent/kernel/app/package-info.class`（135 字节） | 成立 ⇒ "kernel-app SPI" 不存在，注册表落在 z-bot 这侧，内核仓一个字节未动 |
| 0-7 | z-bot 从无 ServiceLoader | `git grep -n "ServiceLoader" -- 'z-bot-core/src/main'` | `rc=1`，0 命中 | 成立 |
| 0-8 | 无第三方 IM 凭据 | `grep -o '^[a-zA-Z0-9._-]*=' ~/.zbot/config.properties`（只列键名）；`awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties` | 键名只有 `minimax.api.key / minimax.base.url / minimax.model`（3 行）；api.key 值**长度 125**（未打印/未复制/未入日志）；**无任何 feishu/dingtalk 键** | 成立 ⇒ **不做真发验收**，见 §未做 |
| 0-9 | @Test 起点 619 | `git grep -c '@Test' 926b8b5 -- 'z-bot-core/src/test' \| awk -F: '{s+=$NF} END {print s}'` | `sum @Test = 619`（58 个测试文件；`channel/` 下 12 个类） | 成立 |
| 0-10 | BotConfig 与通道配置无关 | `grep -cnE "feishu\|dingtalk" .../config/BotConfig.java`；`grep -n rawProps .../BotConfig.java \| head -1` | `0`；`114:    private Properties rawProps = new Properties();`（私有、无公开 getter） | 成立 ⇒ 通道声明走自己的 manifest；`BotConfig.java` 本期未被修改 |

**本棒（p18b）补记 —— 0-1 / 0-2 两行量的是"改动前"的树，交付后的规模要另记**：
`ls z-bot-core/src/main/java/com/zifang/z/bot/channel/*.java | wc -l` = `19`（16 + `ChannelRegistry` + `ChannelConfigException` + `HttpConsoleChannel`），`cat .../channel/*.java | wc -l` = `6261`。
`git ls-tree 926b8b5 -r --name-only | grep 'main/java/com/zifang/z/bot/channel/.*\.java' | wc -l` = `16` ⇒ 上表 0-1/0-2 是 `main` 侧的旧数，判定"成立"仅对改动前成立。
`git diff --name-status 926b8b5 -- z-bot-core/src/main/java/com/zifang/z/bot/channel/` = `A ChannelConfigException / A ChannelRegistry / M DingTalkChannel / M FeishuChannel / A HttpConsoleChannel` —— **`HttpChannel.java` 不在改动清单里**（工单②"绕开 P19 写域"成立）。

## 1. 注册表落地形态：**自建声明式 manifest（不是 ServiceLoader）**
落地文件（来源按序合并，后写覆盖）：

- `z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelRegistry.java`（新）
- `z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelConfigException.java`（新）
- `z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpConsoleChannel.java`（新：原 `GatewayCommand` 私有内部类 `HttpChannelAdapter` 提到 `channel/` 下，注册表才产得出 `http` 这一路；`send()` 依旧是刻意空转，语义逐字保留）
- `z-bot-core/src/main/resources/com/zifang/z/bot/channel/channels.builtin.properties`（新：缺省档，四种 kind 的形状；IM 两条 `enabled=false`）
- 用户侧：`<configDir>/channels.properties`（`--config-dir` / `ZBOT_HOME` 指向的数据根）；`--channel-manifest <FILE>` 是第四层
- 装配点：`cli/GatewayCommand.java`、`cli/ServeCommand.java`

| 主张 | 复算命令 | 实测 |
|---|---|---|
| 不用 ServiceLoader、不做反射批量加载（只算代码行，注释里的类名提及不计） | `grep -E "Class\.forName\|ServiceLoader\|getDeclaredConstructor\|java\.lang\.reflect" z-bot-core/src/main/java/com/zifang/z/bot/channel/ChannelRegistry.java \| grep -vc '^[[:space:]]*\*'` | `0`。**推翻上一版本文的读数**：同一条命令不带 `grep -vc` 的裸 `grep -c` 是 **`3`**（`:27` `:37` `:38` 三行 javadoc，全是"本类里没有 Class.forName"这类自述），不是本文原先写的 `1`；代码行判据仍为 `0`，结论不变、账要改对 |
| 惰性：加载 + 查声明都不构造实例 | `ChannelRegistryTest.loadingSpecsConstructsNothing`（量 `registry.createCount()`，见 §杠② B 族） | 杠② B1 注入前：`OK`（实测见 §杠② 表 B1 行） |
| 后写覆盖三层 | `ChannelRegistryTest.profileManifestOverridesBuiltinAndCliBeatsProfile` / `explicitManifestLayerBeatsProfileAndMissingFileFailsLoudly` / `laterPropertiesLayerWinsFieldByField`（见 §杠② A 族） | 杠② A1 注入前：`OK` |
| 接进生产线路（不是 0 消费者抽象） | `grep -n "ChannelRegistry.load" z-bot-core/src/main/java/com/zifang/z/bot/cli/GatewayCommand.java z-bot-core/src/main/java/com/zifang/z/bot/cli/ServeCommand.java` | `GatewayCommand.java:69`（`load(configDir, extraManifest)`）、`ServeCommand.java:43`（`load(configDir)`）—— 工单②成立；feishu/dingtalk 的唯一生产构造点在 `ChannelRegistry.registerBuiltInFactories()` 的两个工厂体内 |

**为什么不选 ServiceLoader**：provider 注册文件只能落 `src/test/resources/META-INF/services/`，而本期写域只到 `z-bot-core/src/test/**/channel/**` —— 一个"证不了它真被加载过"的 SPI 就是红线 2 的另一个候选。manifest 的加载证据在杠③ 真进程 stdout 里（`通道声明来源: [...]`）。

## 2. 真出站（Feishu token 换取 + 发送 + 错误分类）

| 主张 | 复算命令 | 实测 |
|---|---|---|
| 飞书真发 HTTP（token + send 两处） | `grep -c "http\.newCall" z-bot-core/src/main/java/com/zifang/z/bot/channel/FeishuChannel.java` | `2` |
| 钉钉真发 HTTP | `grep -c "http\.newCall" z-bot-core/src/main/java/com/zifang/z/bot/channel/DingTalkChannel.java` | `1` |
| 两个文件不再有"没真发"的自述 | `grep -n "没有真发" z-bot-core/src/main/java/com/zifang/z/bot/channel/{Feishu,DingTalk}Channel.java` | `rc=1`（0 命中） |
| 编译过 | `mvn -o test -pl z-bot-core`（有无 `[ERROR]`／`BUILD`） | `BUILD SUCCESS`、`MVN_RC=0` 三跑一致（见 §杠①） |
| 出站"发了什么字节"可断言（不是只断言日志） | `z-bot-core/src/test/java/com/zifang/z/bot/channel/FakeImEndpoint.java`（`bind(0)` 假端点，记录 method/path/query/header/body） | 断言面：`tokenExchangeIsRealPostWithAppIdAndSecret`（POST 路径 + `app_id`/`app_secret` 请求体）、`messagePostCarriesBearerTokenAndFeishuBodyShape`（`Authorization: Bearer`、`msg_type`、`content` 字符串化、`receive_id`）、`receiveIdTypeIsHonouredFromConfig`（query `receive_id_type`）、`sendPostsTextBodyToSignedWebhookUrl`/`rawWireIsARealSignedPostAndReplyIsJson`（钉钉 `timestamp`+`sign` query 与 `msgtype`/`text.content` 体）；逐条判定见 §杠② D/E 族 |

真域名的正确性只按构造出的 URL 字符串断言（`outgoingUrlDefaultsToRealFeishuDomainButNeverSendsThere`、`realDomainUrlIsKeptVerbatimButIsNeverDialed`），全程没有对 `open.feishu.cn`/`oapi.dingtalk.com` 发出一个字节。

## 3. 显式降级（缺键必须报出来）

| 主张 | 复算命令 | 实测 |
|---|---|---|
| 注册表层：`requires` 缺键 ⇒ 抛 `ChannelConfigException` 且点名键 | `ChannelRegistryTest.requiresMissingKeysAreNamedExplicitly`（阳性对照）+ `createAllCollectsFailuresInsteadOfSilentlyDegrading` | 报错原文含 `channel.feishu（kind=feishu）缺配置键: app-id, app-secret`；`createAll` 两条失败条目分别含 `webhook-url` / `app-id, app-secret`，`created=0` |
| 通道层：缺凭据 ⇒ send() 抛并点名键 | `FeishuOutboundTest.missingCredentialsThrowsNamingKeysAndSendsNothing` + `halfConfiguredCredentialsNameOnlyTheMissingKey`、`DingTalkOutboundTest.missingWebhookUrlThrowsNamingKeyAndSendsNothing` + `blankWebhookUrlIsTreatedAsMissingNotAsEmptyTarget` | 实测见 §杠③(c) 的进程 stderr 原文 |
| 缺键时**一个字节都不发**（不是"发了再报错"） | 上述四支测试里的 `fake.requestCount()==0` 断言（假端点侧计数，不是日志） | `0` 请求（杠② C3/C4 注入"摘掉显式降级"后同一断言判红，见 LEDGER） |
| 送达判定不被放宽：HTTP 2xx 且平台 `code/errcode==0` 才写 `delivered=true` | `FeishuOutboundTest.successfulSendQueuesDeliveredPayloadAndFailureDoesNot`、`DingTalkOutboundTest.deliveredFlagIsOnlyWrittenOnRealSuccess`、`httpLevelFailureIsReportedWithStatus*` | 见 §杠② D4/E3（把 `!= 0` 改成 `== 0`/恒真必须判红） |


## 杠① 单测 ×3 顺序独立

命令（三跑各自独立、日志只在 `~/.cache/zbot-p18/`）：

```bash
cd /private/tmp/zbot-wt-p18
rm -rf z-bot-core/target/surefire-reports && mvn -o test -pl z-bot-core > ~/.cache/zbot-p18/bar1_runN.log 2>&1
grep -E '^\[INFO\] Tests run:.*Failures' ~/.cache/zbot-p18/bar1_runN.log | tail -1
grep -c 'BindException\|Connection refused\|SocketTimeout' ~/.cache/zbot-p18/bar1_runN.log
```

| 跑 | mvn 汇总行（扳机） | F/E/S | socket 计数 | RC |
|---|---|---|---|---|
| run1 | `Tests run: 668, Failures: 0, Errors: 0, Skipped: 0` | 0/0/0 | `0` | `MVN_RC=0` |
| run2 | `Tests run: 668, Failures: 0, Errors: 0, Skipped: 0` | 0/0/0 | `0` | `MVN_RC=0` |
| run3 | `Tests run: 668, Failures: 0, Errors: 0, Skipped: 0` | 0/0/0 | `0` | `MVN_RC=0` |

- surefire 逐文件对账（`run3` 之后立刻量；**同目录会被同机别人的 mvn 覆盖 ⇒ 只认 mvn 自己的汇总行做扳机**）：`ls z-bot-core/target/surefire-reports/*.txt | wc -l` = `61`；`grep -h '^Tests run:' *.txt | awk -F'[:,]' '{t+=$2;f+=$4;e+=$6;s+=$8} END{print t,f,e,s}'` = `668 0 0 0` —— 与 mvn 汇总行一致。
- `@Test` 计数对账（工单假设）：`git grep -c '@Test' fbb5f86 -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END {print s}'` = `668`，`926b8b5`（main）= `619` ⇒ 上一棒净增 `49` 条；`channel/` 下测试文件 `16` 个。
- 起点即 `fbb5f86` 未改动产品码，三跑数字与工单"主编亲测"一致（复算成立，未推翻）。

## 杠② 变异注入

量具：`python3 -u _doc/acceptance/p18/p18_mutation.py`（台账输出 `_doc/acceptance/p18/LEDGER.tsv`，**只能由该脚本生成**）。

LEDGER 列（逐字）：`id	family	target	testcase	injection	expected_red_set	verdict	detail`

五档判据：`RED-OK`（点名 testcase 全红、且阳性对照能进猎物）/ `PARTIAL`（部分被杀）/ `GREEN-BUT-MUTATED`（注入存活 ⇒ 断言缺口，记账不粉饰）/ `BROKEN`（编译不过，注掉）/ `NO-RUN`（配不到猎物，写明原因）。

族 ↔ 注入点（每族的阳性对照 = 该族"猎物真进得来"那支 testcase 在未注入时必须绿，见 LEDGER `injection=NONE` 行）：

| 族 | 覆盖面 | 注入点（文件:符号） |
|---|---|---|
| A | 后写覆盖语义 | `ChannelRegistry.loadWithoutBuiltinFactories` 来源顺序、`ChannelRegistry.merge` 的 config 覆盖改 putIfAbsent |
| B | 惰性构造（**数**出来不是读注释） | `ChannelRegistry.loadWithoutBuiltinFactories` 末尾加"对每条声明真调一次 `create()`" |
| C | 显式降级（缺键点名） | `ChannelRegistry.create` 的 `lacking` 抛点、`missing()` 文案里的键名、`FeishuChannel.resolveAccessToken`/`DingTalkChannel.send` 的缺键抛点 |
| D | 飞书 token + `im/v1/messages` 请求字节 | `TOKEN_PATH`/`MESSAGE_PATH`、token 请求体键名、`Authorization: Bearer`、`platformCode != 0` 判定、`TOKEN_REFRESH_MARGIN_MS`、401 重取重发、`verifySignature` |
| E | 钉钉 webhook 签名 | 签名原文 `timestamp + "\n" + secret`、URL 上 `timestamp`/`sign` 拼接、`errcode != 0` 判定、`scrubUrl` |
| F | `HttpConsoleChannel` 拆分后 web 面不回归 | `HttpConsoleChannel.name()`、`start()` 的委托、`send()` 的空转 |

实测读数（每跑一次的 `#tally` 行都逐字抄，LEDGER 只在脚本里生成）：

| 跑 | 命令 | 汇总（逐字） |
|---|---|---|
| run1（13:56，`~/.cache/zbot-p18/bar2_run1.log`） | `python3 -u _doc/acceptance/p18/p18_mutation.py` | `#tally … RED-OK=27\|PARTIAL=0\|GREEN-BUT-MUTATED=1\|BROKEN=1\|NO-RUN=1`；`#mutants_injected 30`、`#baseline_total_full_tests 668`；6 行 `CTRL-*` 全 `OK`；30 行逐条尾部 `还原=True`；跑完 `git diff --name-only` 为空 |
| run3（run1 之后我只动过 C2 的注入串和杠③ 的量具，预期红集一字未动） | 同一命令 | 见下（跑完即贴） |

`wc -l _doc/acceptance/p18/LEDGER.tsv` = `41` = 1 表头 + 30 变异体 + 6 阳性对照 + 4 汇总行（`#tally`/`#mutants_injected`/`#baseline_total_full_tests`/`#generated_by`）。

一次本棒自己造成的中断（不藏）：14:07 杠② 的第二次重跑刚过 6 族阳性对照（LEDGER 只写到 `CTRL-D`）就被我用 `kill -9` 打断 —— 因为同一 worktree 里不能同时有两件改 `src` 的事在飞（我要做 F2 对撞实验）。那份半成品**没有**提交（`git checkout -- _doc/acceptance/p18/LEDGER.tsv`），被打断的注入也随 `git checkout` 还原（`md5 -q ChannelRegistry.java` = `d59b339c0404e6104b2262ed416c2233` 与 HEAD 同值，`git status --porcelain` 对 `z-bot-core/src` 干净）。下表汇总是随后完整跑出的那一份。

### 阳性对照（每族先跑 `injection=NONE`，猎物进不来就不给分）

`LEDGER.tsv` 里 `CTRL-*` 六行：点名 testcase 在未注入时 **ran>0 且全绿** 才算该族有猎物，否则该族全部变异体记 `NO-RUN`。

| 族 | 点名条数 | 未注入实跑 |
|---|---|---|
| A-后写覆盖 / B-惰性 / C-显式降级 / D-飞书协议 / E-钉钉签名 / F-web面 | 见 LEDGER `expected_red_set` 列 | `OK`（六族各自 ran 与耗时见 LEDGER 第 8 列，形如 `ran=6 rc=0 4.1s 红=-`） |

### "惰性"是数出来的，不是读注释

两支变异体都靠**构造计数器**判，不靠 javadoc：

- `B1 registerFactory 时预热该 kind 的全部声明` —— 在工厂注册处真调 `create()`，猎物 `ChannelRegistryTest#loadingSpecsConstructsNothing` 里的 `built[0]`（工厂自增计数）与 `registry.createCount()` 当场从 `0` 变非零 ⇒ 判红。
- `B2 读声明（specs()）就顺手构造` —— 把构造挂到只读接口 `specs()` 上，同一支猎物按同样的两个计数判红。
- 未注入时该 testcase 的读数：`built[0]=0`、`createCount()=0`、`materializedNames()` 为空，且 `createAll` 之后才变成 `2`（阳性对照，见 `CTRL-B-惰性`）。
- 复算命令：`python3 -u _doc/acceptance/p18/p18_mutation.py B1 B2`

### manifest 每个声明位 ↔ 它的兑现路径（不许广告没兑现路径的能力）

| 声明位 | 兑现代码 | 读它的断言 | 杀掉它的变异体 |
|---|---|---|---|
| `requires` | `ChannelRegistry.create()` 逐键检查后抛 `ChannelConfigException` | `requiresMissingKeysAreNamedExplicitly`、`createAllCollectsFailuresInsteadOfSilentlyDegrading` | C1（摘掉抛点）、C2（抛了但不点名） |
| `default-port` | `Context.port()` 在 CLI/config 都没给时用它 | `defaultPortIsHonoredWhenNobodyGivesPort`（起真 `DingTalkChannel` 后读 `getPort()`）、`builtinManifestDeclaresFourKindsWithExpectedFlags` | A3（不读缺省档 ⇒ 端口形状消失） |
| `outbound` | `ChannelRegistry.isOutbound()`，消费者在 `cli/GatewayCommand.java:126`（cron 投递口筛掉拉模式控制台） | `outboundFlagIsRecordedForCreatedChannels`（产出前 TRUE / 产出后 FALSE 双向）、`builtinManifestDeclaresFourKindsWithExpectedFlags` | F3（代码不读声明）、F4（声明本身写错） |
| `enabled` | `create()` 拒绝 + `createAll()` 记 skipped | `createOnDisabledSpecIsAnExplicitRefusal`、`loadingSpecsConstructsNothing` 的 `skipped` 断言 | C5（缺省档把 feishu 打开） |
| `kind` | 工厂表查表，查不到抛 | `unknownKindIsExplicitNotSilent` | A3 |
| `config.<key>`（api-base / receive-id-type / app-id / app-secret / verification-token / webhook-url / secret / port / host） | `Context.value()` → 构造参数 → 真发出去的字节 | `FeishuOutboundTest` / `DingTalkOutboundTest` 全部按假端点收到的请求断言；杠③ A7/A11/A16 再按真进程断言一遍 | A2（config 后写不覆盖）、D1–D8、E1–E5 |

### 两处不粉饰的账

1. **`C2` 第一跑判 `BROKEN`，是我的量具写坏了**：注入串漏了一个收尾引号（`+ "）缺配置键（未点名）` 少了 `"`），编译不过。已修 `p18_mutation.py` 的 `new` 字段，**预期红集一字未动**（`requiresMissingKeysAreNamedExplicitly` + `createAllCollectsFailuresInsteadOfSilentlyDegrading`），复算：`git log -1 --stat` 与 `python3 -u _doc/acceptance/p18/p18_mutation.py C2`。
2. **`D10` 判 `GREEN-BUT-MUTATED`（真缺口，不是量具坏了）**：把 `verifySignature()` 改成恒真，没有任何测试变红 —— 因为没有任何一支测试断言"错签名必须被拒"（`signatureHelperAcceptsCorrectDigest` 只断言正例、`signatureHelperRejectsMismatchWithoutEncryptKey` 走的是"未配 encryptKey ⇒ 关闭校验"那条），且 `verifySignature` 在生产码里**没有调用方**：`git grep -c verifySignature -- 'z-bot-core/src'` = `FeishuChannel.java:1 / FeishuChannelTest.java:4`，main 里那一处命中就是它自己的定义（`:663`）。⇒ 这是一条"广告了但没接线的入站签名能力"，进 §未做 第 8 条，不在这里替它圆场。真被接线的那道门是 `verificationToken` 比对，它有自己的猎物（`D9` 判红：`postEventWithBadTokenReturns401`）。
3. **`F2` 判 `NO-RUN`（单测层没有猎物）**：`HttpConsoleChannel.start()` 不委托 `inner.start()` 时，进程内没有任何一支测试真去 GET 过控制台 ⇒ 不硬凑一个点名集，改由杠③ 的 `A2`（真 JVM 起 gateway 后 `GET /index.html` 判状态码 + 形状）当杀手，并实测"注入 F2 ⇒ A2 判红"（见 §杠③ 末尾的 F2 对撞实验）。

## 杠③ 真进程 E2E

量具：`python3 -u _doc/acceptance/p18/p18_e2e.py`，整跑 ×3。

- (a) 真 JVM 起 gateway：`--config-dir` 指临时根（`tempfile.mkdtemp`，绝不碰 `~/.zbot`），manifest 把 `api-base`/`webhook-url` 指向进程内 `bind(0)` 的假 IM 端点 ⇒ 断言假端点**收到的**请求逐字段（method / path / query / header / body），不是断言日志。
- (b) 出站途中 `kill -9` ⇒ 与已并入 main 的 P16 `DeliveryLedger` 语义对账（不另发明重试），重启后回读台账文件判定"没有假装送达"。扳机用 `waitpid` + 文件回读，不用日志行。
- (c) 无凭据路径 ⇒ 进程 stderr 原文进本文 §3。
- 收尾：`lsof -nP -iTCP -sTCP:LISTEN` 复扫 + `ps -o lstart` 分清每个 pid 是不是本次跑的。

实测读数（`python3 -u _doc/acceptance/p18/p18_e2e.py` 整跑 ×3 + 还原后复核 ×1；日志 `~/.cache/zbot-p18/bar3_full_r{1,2,3,4}.log`，`.gitignore` 里 `*.log` 收不走，故决定性读数逐字抄在这里）：

| 跑 | 范围 | 汇总行（逐字） |
|---|---|---|
| r1 | A+B+C+H | `PASS=38 FAIL=0 用时 14.5s` |
| r2 | A+B+C+H | `PASS=38 FAIL=0 用时 11.3s` |
| r3 | A+B+C+H | `PASS=38 FAIL=0 用时 5.9s` |
| r4 | A+B+C+H（F2 还原、jar 与 HEAD 源码一致后复跑） | `PASS=38 FAIL=0 用时 5.3s`，脚本 `R4_RC=0` |

38 支全部有名（逐条点名，不数行数）：

- **A 段 20 支**（真 JVM + 进程内假 IM 端点，按**服务端收到**的字节断言）：A1 控制台端口在听（`lsof` 按 pid 过滤）、A2 `GET /index.html` 状态码+形状、A3 第四层 manifest 真进 `通道声明来源`、A4 feishu/dingtalk 真被产出、A5 入站 200 `{"ok":true}`、A6 假端点收到 token 换取、A7 token 请求体 `app_id`/`app_secret` 逐键＝manifest 声明值、A8 `Content-Type: application/json`、A9 假端点收到 `/im/v1/messages`、A10 `Authorization: Bearer <刚换来的那个 token>`、A11 `receive_id_type=chat_id`（声明值，不是缺省 `open_id`）、A12 发送体三字段 `receive_id`/`msg_type`/`content`、A13 过线原文（请求行/Host/Content-Length）、A14 两次发送只换一次 token（缓存真生效）、A15 钉钉入站 200 且假端点收到 `/robot/send`、A16 query 带 `access_token`+`timestamp`+`sign`、A17 `sign` 独立重算比对、A18 钉钉体 `msgtype=text` 且 `text.content` 就是回复串、A19 真域名零外连、A20 A 段 JVM 已收尸。
- **B 段 7 支**（投递途中 `kill -9` × P16 `DeliveryLedger` 对账）：B1 单路 gateway 起来、B2 `rc=-9 在飞请求=1`、B3 kill 时刻无 `delivered`、B4 同一数据根重启后又起来、B5 重启后 `delivered` 行能在假端点收到的 body 里找到同文、B6 恢复语义仍走 P16（标记串从被测源码读）、B7 未送达义务没被抹掉。
- **C 段 6 支**（无凭据路径与第四层不许静默）：C1 报错点名 `app-id, app-secret`、C2 点名到实例名 `feishu`、C3 显式降级而不是崩（进程仍活、控制台仍可服务）、C4 缺凭据时假端点零请求、C5 stdout 有"不是静默降级"这一行、C6 `--channel-manifest` 指向不存在文件 ⇒ `rc=1` 且点名文件。
- **H 段 5 支**（收尾卫生）：H1 JVM 全部收尸、H2 端口全部释放、H3 `ps -o lstart` 复核、H4 真数据根三条读数不变、H5 现场文件里没有 100+ 字符令牌串（正控＝125 字符合成串能命中）。

关键逐字读数（r1/r2/r3 三次相同，说明不是靠撞运气）：

- (a) 过线原文（A13，服务端视角）：`first_line=POST /open-apis/im/v1/messages?receive_id_type=chat_id HTTP/`；A10 `authorization=Bearer t-fake-002`；A12 `receive_id=p2p:oc_a1 msg_type=text content={"text":"P18-E2E-REPLY-001"}`；A19 `JVM TCP 行=6 其中非回环=0；假端点收到=4 对端=['127.0.0.1']（阳性对照：收到数>=4）`。
- (b) 断点重投：B2 `rc=-9 在飞请求=1` ⇒ kill 确实落在投递途中；B3 `rows=1 状态分布={'attempting': 1} 在飞=1` ⇒ 此刻台账里**没有** `delivered`；B5（重启后）`rows=1 状态分布={'delivered': 1} 假端点 send=2` ⇒ 收敛由 P16 的重投完成；B6 `RECOVERED_MARKER 前 24='♻️ 断点重投 —— 网关在投递途中重启过，这条'`；B7 `仍非 delivered 的行=0`。台账只在 `state.db` 的**副本**上用 `sqlite3` 读。
- (c) 无凭据路径 stderr 原文（C1 两行）已逐字进 §3。
- 收尾：H4 `n=8 config=2dadaed0 state=690ddbc0`，脚本自身末尾再报一次 `数据根条目 before/after=8/8`。

一处不粉饰的账（量具的，不是产品的）：B3 第一版判 `FAIL`（kill 时刻读到 `{'delivered': 1}`）—— 因为我的假端点**答完才入账**，`kill -9` 于是落在投递完成之后，B2/B3 的"途中"是假的。改成"到达即入账"并给 B3 自带正控（`rows>=1 且 delivered==0`，同时 B2 断言在飞数=1）之后才拿到 `{'attempting': 1}`。复算：`git log --oneline -4 -- _doc/acceptance/p18/p18_e2e.py`（`14dad64` 到达即入账、`64beebc` A2 不许量具自己栈炸）。

### F2 对撞实验（杠② 的 `NO-RUN` 行到底有没有杀手）

杠② 里 `F2`（`HttpConsoleChannel.start()` 不委托 `inner.start()`）在单测层配不到猎物 ⇒ 判 `NO-RUN`。杀手实测在杠③：

```bash
# 1) 逐字注入 p18_mutation.py 的 F2 串：inner.start(); → // 变异：不委托
# 2) mvn -o -DskipTests package -pl z-bot-core        # BUILD_RC=0
# 3) P18_ONLY=A python3 -u _doc/acceptance/p18/p18_e2e.py   # A 段 + H 段 = 25 支
```

读数（`~/.cache/zbot-p18/f2_xp_A2.log`）：`FAIL A1 ... pid=33941 listening=0 alive=True`、`FAIL A2 ... GET 直接被拒（端口没人听）: URLError(ConnectionRefusedError(61, 'Connection refused'))`，而 A3–A19、A20、H1–H5 全 `PASS`（出站面一点没坏），汇总 `PASS=23 FAIL=2 用时 49.3s`、脚本 rc=1。⇒ F2 打掉的恰好只有 web 面，A1/A2 是有牙的。

还原复核：`md5 -q z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpConsoleChannel.java` = `79f01adf3aeff05e4cf04b5e0ba84fea`，与 `git show HEAD:...HttpConsoleChannel.java | md5 -q` 同值；`git status --porcelain` 对 `z-bot-core/src` 干净，重建 jar（`~/.cache/zbot-p18/cleanbuild.log` `BUILD SUCCESS`）后整跑 r4 复验。

## 杠④ 用户数据根未被触碰

```bash
ls -A ~/.zbot | wc -l
md5 -q ~/.zbot/config.properties | cut -c1-8
md5 -q ~/.zbot/state.db | cut -c1-8
```

| 时点 | `ls -A ~/.zbot \| wc -l` | config.properties md5 前 8 | state.db md5 前 8 |
|---|---|---|---|
| T1 开工（13:40:10） | `8` | `2dadaed0` | `690ddbc0` |
| T2 杠② 在飞（14:13 前后，含杠③ 全程与 F2 对撞） | `8` | `2dadaed0` | `690ddbc0` |
| T3 收尾 | （待填） | （待填） | （待填） |

T1 列出的 8 项：`.stty.bak`、`config.properties`、`cron`、`memories`、`models-cache.json`、`sessions`、`state.db`、`workspace` —— `cron` 是空目录、`.stty.bak` 均**未删**（工单红线）。

## §未做

1. **未做真发（飞书/钉钉真实域名零请求）** —— 原因：本机没有任何 IM 凭据。复算命令 `grep -o '^[a-zA-Z0-9._-]*=' ~/.zbot/config.properties` 只列键名，实测只有 `minimax.api.key / minimax.base.url / minimax.model` 三行（`minimax.api.key` 值长 125，本棒**未读取值/未打印/未复制/未入日志**）。所以本期所有出站正确性证据都是"对 127.0.0.1 假端点发出的字节"，**不得**读成"已在真飞书/真钉钉送达"；真域名只以构造出的 URL 字符串断言（`outgoingUrlDefaultsToRealFeishuDomainButNeverSendsThere`、`realDomainUrlIsKeptVerbatimButIsNeverDialed`）。
2. **未做飞书事件解密的完整实现**：`handleEvent` 里 `encryptKey` 只用于签名（代码注释 `// 简化 — 真接入时按 encryptKey 解密` 仍在），入站事件体按明文 JSON 解析 ⇒ 真接入加密模式时入站面不可用，本期没有对它写断言。
3. **未做 webhook 之外的入站回调鉴权端到端**（飞书 URL 校验 `echostr` 有实现与断言，钉钉入站签名未做，`handleInbound` 不校验 `sign`/`timestamp` 头）。
4. **未做 ServiceLoader/内核侧 SPI**：`z-agent-kernel-app:0.2.1` 是空模块（§0-6），本期注册表完全落在 z-bot 侧，内核仓一个字节未动。
5. **未接 `BotConfig`**：`config/BotConfig.java` 是禁改区（P21 在飞），通道配置入口只有 `<configDir>/channels.properties` + `--channel-manifest`；`rawProps` 无公开 getter，本期没有从 BotConfig 读通道键。
6. **未做出站重试/退避**：飞书只做"token 被判废 ⇒ 作废缓存重取一次并重发一次"，其余失败按 P16 语义记 `failed`，没有新增定时器或队列。
7. **杠② 台账里剩下的三笔**（`LEDGER.tsv` 里都在，不在这里圆场）：`D10` = `GREEN-BUT-MUTATED`（→ 第 8 条）；`F2` = `NO-RUN`（单测层配不到猎物，杀手改由杠③ 的 A1/A2 承担并做了对撞实验，见 §杠③ 末尾）；`C2` = 第一跑判 `BROKEN`（我的注入串漏了收尾引号 ⇒ 编译不过，属量具自坏，已修，由 run3 复验）。其余 27 笔 `RED-OK`、`PARTIAL` 0 笔。
8. **`FeishuChannel.verifySignature` 是"广告了但没接线"的入站签名能力**：生产码里没有调用方（`git grep -n verifySignature -- 'z-bot-core/src'` ⇒ main 侧唯一命中就是它自己的定义 `FeishuChannel.java:663`，test 侧 4 处），也没有一支断言"错签名必须被拒" ⇒ 把它改成恒真没有任何测试变红（LEDGER `D10` 行）。本期**未做**：把 `verifySignature` 接进 `handleEvent` 的入站鉴权路径并补负例断言；真被接线的那道门是 `verificationToken` 比对（`D9` 判红）。
9. **杠③ 只跑单实例 gateway**：没有做"两个 gateway 抢同一 `state.db` 时的并发投递"验证（那是 P16 `DeliveryLedger` 的锁语义，本期只在其上重投，未另加断言）；也没有覆盖"飞书真返回业务错误码时的分类"端到端 —— `DeadTargets` 分类只在单测层由假端点返回码驱动（`FeishuOutboundTest#chatLevelNotFoundIsClassifiedAsDeadTarget`），杠③ 的假端点全部返回 200 形状。

