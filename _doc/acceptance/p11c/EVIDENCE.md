# P11c 通道监听收口 — 验收证据

日期 2026-09-25 · 基线 `d71657d`(P11b) · 分支 main · 四道杠逐条实测，代理自述为零（本项主编自己做）

## 0. 这一项在修什么

`_doc/hermes-roadmap.md` §6"`*.channel` HTTP 抖动真因"：四个通道的 `start()` 一律
`HttpServer.create(new InetSocketAddress(port), 0)` —— 那是**通配**地址 (0.0.0.0)。两个后果：

1. macOS 上通配监听能和别的服务已经占住的 `127.0.0.1:P` **共存**：进程自己印"已启动"，
   而打到 `127.0.0.1:P` 的请求被那个更具体的邻居应答 ⇒ 全量套件里 1/9 的间歇 400；
2. 无鉴权的 `/bot/chat`、`/api/*` 缺省就摊到整个局域网。

改法：`channel/ChannelBind.java` 统一解析监听地址 —— host 为空 ⇒ `InetAddress.getLoopbackAddress()`，
显式写 `0.0.0.0` 才通配；`HttpChannel`/`WebhookChannel`/`FeishuChannel`/`DingTalkChannel`
的 `start()` 全走它，各加一个末位 `host` 构造重载；CLI 侧 `serve --host` / `gateway --host`
（两通道共用一个）。横幅不再写死 `127.0.0.1`，改印**实际**地址，非回环时追加一行 LAN 警告。

顺带（同一处 `--help` 读出来的，属 P11b 漏网的用户可见文案，M8 钉住）：
`AgentOptions` / `SessionsCommand` 的 `--config-dir` 说明由"默认 ~/.zbot"改成 profile 口径；
缺 API key 的报错从写死 `~/.zbot/config.properties` 改成**打印实际加载的那个 profile 路径**；
`ZBot` 类文档同步。另把 `HttpChannelTest` 的 23 处状态断言加厚成打印 `url + code + ct + body`
（P11b 记账的诊断面，随本批一起落）。

## 1. 杠① 全量 3 连绿

```
run=1 rc=0 | Tests run: 410, Failures: 0, Errors: 0, Skipped: 0
run=2 rc=0 | Tests run: 410, Failures: 0, Errors: 0, Skipped: 0
run=3 rc=0 | Tests run: 410, Failures: 0, Errors: 0, Skipped: 0
```

410 = 基线 399 + `ChannelBindTest` 11 条。复算：
`rm -rf z-bot-core/target/surefire-reports && mvn -o test`（×3）

**提交前在最终树重跑**（那之后又给 `ProfileIsolationTest` 加了第 9 条 `missingKeyHintNamesTheProfileItWasLoadedFrom`，
用来钉 §0 那条"顺带"的文案改动 —— 410 那三跑量不到它，所以按最终形态重测）：

```
final=1 rc=0 sockErr=0 | Tests run: 411, Failures: 0, Errors: 0, Skipped: 0
final=2 rc=0 sockErr=0 | Tests run: 411, Failures: 0, Errors: 0, Skipped: 0
final=3 rc=0 sockErr=0 | Tests run: 411, Failures: 0, Errors: 0, Skipped: 0
=== ~/.zbot 复位核对 ===
entries=8 2dadaed0 690ddbc0
```

411 = 410 + 1。`sockErr=0` 是每跑对 `Unexpected end of file from server|SocketException` 的 `grep -c`，
口径见 §5。

## 2. 杠② 变异检验：8 个变异体，0 个 GREEN-BUT-MUTATED

复算 `python3 _doc/acceptance/p11c/p11c_mutation.py`（跑 `ChannelBindTest,ProfileIsolationTest`）；
台账 `LEDGER.tsv` 由脚本自己写（不手抄）。

| 变异体 | 判定 | 点名红 |
|---|---|---|
| M1 Http 改回通配绑定 | RED-OK | 4/4 |
| M2 Webhook 改回通配绑定 | RED-OK | 2/2 |
| M3 飞书改回通配绑定 | RED-OK | 1/1 |
| M4 钉钉改回通配绑定 | RED-OK | 1/1 |
| M5 `resolve()` 永远通配 | RED-OK | 9/9 |
| M6 `resolve()` 永远回环（host 被忽略） | **PARTIAL** | 4/4 命中，另多红 1 条 |
| M7 横幅写死 `127.0.0.1` | RED-OK | 1/1 |
| M8 缺 key 提示写死 `~/.zbot` | RED-OK | 1/1 |

**M6 为什么记 PARTIAL 而不改期望集**：M6 让 `host` 失效，凡是"依赖 host 真生效"的断言都该红，
`consoleUrlReportsTheActualBindNotAHardcodedLoopback` 的红是**对的红** —— 它是我推期望集时漏掉的
一格（我只算了 opt-in 那 2 条 + 双向 2 条）。按纪律不回头改期望集把它洗成 RED-OK，留在台账里。

M6 本身是反空跑探针：只有它能证明"显式 `0.0.0.0` 真能绑到通配"这层断言不是空转 ——
缺省那 4 条守卫如果只靠"代码里绑不出通配"就会永远绿，那是假安全。
M8 是"负向断言要有猎物"的兑现：`assertFalse(消息含 ~/.zbot)` 同一条测试里也钉了
`assertTrue(消息含该 profile 路径)`，且 M8 一注入就红 —— 两半都验过。

## 3. 杠③ 真进程 E2E：22/22

复算 `mvn -o -pl z-bot-core package -DskipTests && python3 _doc/acceptance/p11c/p11c_e2e.py`。
所有监听判定**按 pid 过滤** lsof —— 邻居占着同一个口时，不按 pid 过滤会把"邻居在监听"读成
"z-bot 起来了"，那正是本项要抓的现象，不能拿它当量具。

首跑在 21:59（jar 于 21:59:38 打包）。**提交前按最终树重跑了一次**：重打包 jar 后 `== E2E 22/22 ==`，
且同一次命令里对 `channel/*.java` + `cli/*.java` 共 21 个文件跑了前后 md5 比对 ⇒ `SRC_MD5_STABLE=yes`
（防的就是"mtime 变了以为内容变了"或反过来 —— 变异量具按字节还原过这些文件，mtime 不再可信，
只有 md5 + 一次新的实跑能说明杠③ 量的是提交的那棵树）。

关键读数（首跑，端口当次动态分配）：

```
PASS E1b lsof(本 pid): 缺省只监听回环                    ['127.0.0.1:57733']
PASS E1c 回环上真应答 200                                code=200 body=Bot running, model=stub-model, tools=14
PASS E1e 缺省不打 LAN 警告
PASS E2b lsof(本 pid): 确实摊开通配                      ['*:57750']
PASS E2c 横幅如实写 0.0.0.0                              [HTTP] z-bot 已启动: http://0.0.0.0:57750/index.html
PASS E2d 打了 LAN 警告                                   [HTTP] 警告: 监听在 0.0.0.0，同一网络内的其他机器可直接访问这些端点
PASS E3a 缺省 serve 撞邻居 ⇒ 退出非 0                    rc=1 就绪=False
PASS E3b 报错说清是端口被占                              java.net.BindException: Address already in use
PASS E3c 不得先报「已启动」
PASS E4a 通配 opt-in 与邻居共存成功(旧缺省处境)           addrs=['*:57773']
PASS E4b 请求被邻居抢答而非 z-bot                        code=200 body=FOREIGN-NEIGHBOR-OWNED-THIS-PORT
PASS E5b gateway 两通道都只绑回环                        ['127.0.0.1:57793'] / ['127.0.0.1:57794']
PASS E5c webhook 口有应答(非 404/无响应)                 code=405
PASS E6 一个 --host 同时管两通道                         ['*:57813'] / ['*:57814']
```

E4 是这一项的**必要性**证据，不是回归证据：邻居占住 `127.0.0.1:P` 时，`--host 0.0.0.0`
那侧照样绑定成功（`*:57773`），而 curl 打到 `127.0.0.1:P` 拿到的是邻居的盖章串
`FOREIGN-NEIGHBOR-OWNED-THIS-PORT` —— 一句真话都没说、却把请求全交出去的"哑巴成功"，
就是修好之前 serve 的缺省处境。E3 则证明同一处境下现在的缺省路径是 rc=1 + `BindException`。

最终树那一跑（同上，`== E2E 22/22 ==`）里的决定性读数：

```
PASS E1b lsof(本 pid): 缺省只监听回环                    ['127.0.0.1:50955']
PASS E3a 缺省 serve 撞邻居 ⇒ 退出非 0                     rc=1 就绪=False
PASS E3b 报错说清是端口被占                              java.net.BindException: Address already in use
PASS E4a 通配 opt-in 与邻居共存成功(旧缺省处境)             addrs=['*:50973'] rc=143
PASS E4b 请求被邻居抢答而非 z-bot                        code=200 body=FOREIGN-NEIGHBOR-OWNED-THIS-PORT
PASS E5b 两通道都只绑回环                                ['127.0.0.1:50980'] / ['127.0.0.1:50981']
PASS E6 一个 --host 同时管两通道                          ['*:50988'] / ['*:50989']
```

## 4. 杠④ 单测与 E2E 都不碰 `~/.zbot`

全量 24 轮（§1、§5）+ 两次真进程 E2E 之后，最后一次复核在 22:33：

```
entries=8
config.properties  2dadaed0   (基线 2dadaed0)
state.db           690ddbc0   (基线 690ddbc0)
```

口径钉一句：项数用 `ls -A ~/.zbot | wc -l`。随手用 `ls -a` 会多算 `.` 和 `..` 打出 **10**，
本轮实测中就出现过一次（同一时刻 `ls -A` 仍是 8）—— 别把量具的口径差读成泄漏。

E2E 全程 `--config-dir` 指临时目录、config 里只有 `stub-key-not-real`；
E7 拿真 key 的长度（125）去临时目录里逐文件比对，0 处命中。

## 5. 那条 1/9 抖动：机制已封死，但"归零"不是一个读数

§5 计划里写过"附带收益：全量套件里那条 1/9 抖动归零"。**这句撤回**，理由是可复算性：

```
修复后全量：3 轮（杠①首跑，410）+ 9 轮（专测抖动，410）+ 3 轮（最终树杠①，411）
            + 9 轮（最终树重跑抖动速率，411）= 24 轮
每轮 rc=0、每轮 `grep -cE "Unexpected end of file|SocketException"` = 0
```

后 12 轮（411 那些）有原始档案：`post-fix-flake-summary.txt`（逐轮一行，含 dupPorts/uniqPorts，
复算 `zsh _doc/acceptance/p11c/flake_loop.sh 9`）；修复前的 12 轮在 `pre-fix-flake-summary.txt`。
中间那 9 轮 410 的原始输出随会话临时目录清掉了，只剩记账 —— 那一档不作为独立证据引用，
24 轮里可复算的是最终树这 12 轮 + 修复前 12 轮。

按修复前 1/9 的速率，"最终树 12 轮连绿纯属运气"的概率是 (8/9)^12 ≈ 24%，把早先 410 那几轮也算进来
（24 轮）是 (8/9)^24 ≈ 6% —— 前者是**同一棵树**能拿出的最强说法，后者混了树形，只当参考。
无论哪个都不足以支撑"归零"这种断言（那要几十轮，且这台机器上邻居是否存在本来就是外部条件）。

能确实写下来的只有机制：缺省绑回环之后，"通配监听与邻居的 `127.0.0.1:P` 共存"这条路在代码里不存在了；
端口真被撞时唯一出路是 `BindException`（E3 在真进程里实测到 rc=1）。也就是说抖动即便还有，
也只会以"起不来即红"的形式出现，不会再以"启动了却拿到别人的 400"这种假应答出现 —— 后者才是难查的那个。

## 6. 这一项没做什么（别当成做了）

* **没加 `bind.host` 配置项**。§5 计划里写了"`--host`/配置项"两个口子，实际只落 CLI 一个：
  四个通道的实例化点全在 CLI（`ServeCommand`/`GatewayCommand`），`BotConfig` 里没有任何
  消费者读它 —— 加了就是红线 2 禁的 0 消费者抽象。飞书/钉钉连 CLI 入口都还没有（只有测试构造），
  它们的 `host` 重载目前只被守卫测试消费，接线到配置归 P21（MCP/IM 通道装配那一批）。
* **没做 TLS / 鉴权 / 令牌**。E2d 那行警告只把暴露面说清楚，不改变它。
  `/bot/chat` 无鉴权这件事本身仍在（opt-in 才摊开，缺省只在回环），收口它归 P26 的控制面鉴权。
* **Windows/Linux 未实测**。"通配与 `127.0.0.1` 邻居共存"是 macOS 上实测的（E4），
  别的内核上通配绑定可能直接 `EADDRINUSE` —— 那只会让 E3 更严，不会放松缺省回环的结论。
