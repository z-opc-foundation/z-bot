# p28a 验收证据 —— web 控制台 / HTTP 通道 / 终端 UI 对位面（不做 React 移植）

> 写手：p28a。工作树 `/private/tmp/zbot-wt-p28`，分支 `w13-p28`，基线 `main = 02ff231`。
> 本文件里的每个数字都是**本机跑过命令的原始输出**，未跑过的不写。
> `*.log` 被 `.gitignore` 吃掉 ⇒ 四杠的决定性读数一律**原样粘进本文件**。

---

## §0 第 0 步：基线复算（工单正文数字 vs 我的实测）

复算时间窗：09-26 17:35—17:38。全部命令在工作树根 `/private/tmp/zbot-wt-p28` 执行。

### 0.1 工单口径逐条对表

| 项 | 工单/主编读数 | 我的实测 | 判定 |
|---|---|---|---|
| 基线 commit | `main = 02ff231`（17:34 更正；正文 §首行写 `53222e1`） | `git rev-parse --short HEAD` ⇒ `02ff231` | **以 17:34 更正为准** |
| `@Test` 数 | 874 / 83 文件（更正；正文写 735 / 71） | **874 / 83** | 与更正一致，正文作废 |
| TUI 面 | `ui/` 4 文件 / 1,132 行 | 4 文件 / **1,132** 行（MarkdownRenderer 233 / TerminalUI 372 / RawTerminalReader 261 / LineEditor 266） | 一致 |
| `TerminalUI` public 成员 | 40 | **40** | 一致 |
| TUI 测试面 | 只有 `LineEditorTest.java` | 只有 `LineEditorTest.java`（`MarkdownRenderer` / `RawTerminalReader` / `TerminalUI` 零测试） | 一致 |
| HTTP 面 | `HttpChannel.java` 741 行 | **741** 行 | 一致 |
| 路由条数 | 主编"实测 20 条"（承认无机械口径、自陈尺是坏的） | **22 条**（机械导出，见 0.2） | **工单数字作废**，分母改用 0.2 |
| `index.html` | 1,489 行；命令名硬编码 0 命中；`fetch(` 16 处 | **1,489** 行 / `grep -oE '"/[a-z]+"'` **0** 命中 / `fetch(` **16** 处（覆盖 **13** 个不同端点） | 一致（16 与更正一致，正文 13 是"至少"） |
| 她的体量（只作口径） | `ui-tui/src` + `web/src` `.ts/.tsx` = 103,696 行 | **103,696**（`~/.hermes/hermes-agent` HEAD `cbc1054e2`、`status --porcelain` 0 行） | 一致 |
| 她的 slash 命令表 | 拆在 6 个 `commands/*.ts` | 实为 **7** 个（`core debug ops session setup subscription` + **`topup.ts`**）；`grep -rhoE "^\s*(name\|command): '<path>'"` ⇒ **62** 条 | 差 1 个文件，记进对位表 |

基线 `mvn -o test` 第 0 跑（未改动任何源码，只作计时与绿灯底账）：

```
BASELINE_START 17:36:19
RC=0
BASELINE_END 17:37:22
[INFO] Tests run: 874, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:01 min
```

### 0.2 路由清单：机械导出（这才是分母）

主编给的"20 条"没有留口径且与盘面不符。我的导出规则：**`dispatch()` 方法体里出现的每一个 `"<path>".equals(path)` 字面量 = 一条路由**。
一条命令可复算：

```
$ awk '/private void dispatch\(HttpExchange/,/^    \}$/' \
    z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java \
  | grep -oE '"/[A-Za-z0-9_./-]*"[[:space:]]*\.equals\(path\)' \
  | sed -E 's|[[:space:]]*\.equals\(path\)$||; s|"(.*)"|\1|' | sort -u | nl
```

输出（**22 条**，`awk` 切片 75 行 = dispatch 全体）：

```
     1	/
     2	/api/agent/register
     3	/api/cron
     4	/api/models
     5	/api/session/delete
     6	/api/session/messages
     7	/api/session/switch
     8	/api/sessions
     9	/api/skill/list
    10	/api/skill/push
    11	/api/skill/sync
    12	/bot/chat
    13	/bot/chat/stream
    14	/bot/clear
    15	/bot/confirm
    16	/bot/status
    17	/bot/steer
    18	/bot/stop
    19	/bot/tools
    20	/console
    21	/index.html
    22	/web
```

**与主编 20 条的差 = 2**：她漏了 `/` 与 `/index.html`（这两条恰恰是 `consoleUrl()` 真正打印给用户的那条，见 `HttpChannel:91`）。
`grep -oE '"/[a-z_/]+"'` 会多算 `"/web/index.html"`（那是 `CONSOLE_RESOURCE` 常量，不是路由），**少算** 大写字母的路径段 ⇒ 两把尺都不对，本期一律以 0.2 的 dispatch 口径为准。

之后所有断言的分母 = `HttpChannel.routes()`（生产代码里的单源表）= 上面 22 条 = `_doc/005_testing/acceptance/p28/ROUTES.tsv`。三者由 §1 的漂移卫兵钉死，不许任一方单独改。

## §1 靶子 1：路由台账单源 + 每条路由的真进程断言

### 1.0 单源放在哪、为什么

写域只允许 `src/main` 里的 `channel/HttpChannel.java`，所以台账做成该类里的嵌套 public 结构，
不新开生产文件：

| 构件 | 位置 | 作用 |
|---|---|---|
| `enum BodyShape{CONSOLE_HTML,PLAIN_TEXT,JSON_ARRAY,JSON_OBJECT,SSE_STREAM}` | `HttpChannel` | 五档形状，dispatch 之外唯一的形状口径 |
| `final class Route` | `HttpChannel` | method / path / shape / auth / bindScope / fields(形状契约原子串) / rowCountFrom(行数从哪来) / defect |
| `ROUTES` + `routes()` / `paths()` / `routesOf(path)` | `HttpChannel` | **分母**。`routes()` = 25 行（方法粒度），`paths()` = 22 条路径 |
| `AUTH_NONE_LOOPBACK_ONLY` / `BIND_LOOPBACK_DEFAULT` | `HttpChannel` | 两个常量被每一行复用，`auth` 列不可能被逐行改成别的 |
| dispatch 头部 `routesOf(path).isEmpty() ⇒ 404` / `!ledgerAllows ⇒ 405 + Allow` | `HttpChannel` | 运行时行为由台账决定，不是并行的一份 if 链 |

形状契约原子串语法（ atoms 用 `;` 分隔，列表用 `,` ）：
`nonempty` / `literal:X` / `key:X[,Y]` / `resource:P` / `array` / `object` / `fields:a,b` /
`count-of:k` / `rowfields:<容器>:a,b` / `sse-events:<词表>`。求值器在
`HttpRouteShapeTest#assertShape`，**测试里不重抄任何一份清单**（清单只在台账）。

三把漂移锁（都在 `HttpRouteLedgerTest`）：
1. `ledgerPathsMatchDispatchLiterals` —— 从 `dispatch()` 方法体里正则抠 `"/x".equals(path)` 字面量集合，
   与 `HttpChannel.paths()` 双向做集合差；台账多一条或少一条都红。
2. `routesTsvIsInSyncWithLedger` —— 提交的 `_doc/005_testing/acceptance/p28/ROUTES.tsv` 与 `routes()` 渲染结果
   **逐字节**对账（再生成：`mvn -o -pl z-bot-core test -Dtest=HttpRouteLedgerTest -Dp28.routes.write=true`）。
   ⇒ Python 侧 E2E 的分母从这个文件读，不再有人手抄。
3. `everyBookedDefectIsNamedInEvidence` —— 台账 `defect` 列出现的每个 `D-P28-*` 必须在本文件被点名，
   否则红（防"哑巴缺陷"）。

台账自检：`ledgerRowsAreUniqueMethodPathPairs`（无重复方法+路径）、
`everyRowCarriesTheLoopbackOnlyAuthAndBindScope`（25 行全部带缺省回环口径）。

### 1.1 每条路由的断言 = 真进程 + 状态码之后再判形状

`HttpRouteShapeTest#everyLedgerRouteAnswersItsDeclaredShape`：真 `HttpChannel` 起在 127.0.0.1 的
空闲口（`bind(0)`），对 `routes()` 的**每一行**发一次真请求，先 `assertEquals(期望状态码)`，
再把该行 `fields()` 的原子串逐个求值；最后 `assertEquals(25, checked)` 钉住分母不许缺。
状态码断言在形状断言之前 ⇒ 别的服务应答错口也算不得证据。

实数据对拍（"库里 N 行 ⇒ 接口 N 条"，不是 200 就算过）：

| 断言 | 对拍的两侧 |
|---|---|
| `sessionsRowsEqualTheStoreRows` | `POST /api/sessions` 建 3 条 ⇄ `sessions/_index.json` 行数 ⇄ `GET /api/sessions` 数组长度与 id 集合；并钉列序 `id,title,createdAt,messageCount` |
| `sessionMessagesRowsEqualTheSessionFileRows` | 一次带 `tool_call` 的真对话 ⇄ `sessions/<id>.json` 行数 ⇄ `GET /api/session/messages` 长度、7 列、行序 |
| `cronCountEqualsJobsOnDisk` | `POST /api/cron` add 3 条 ⇄ `cron/jobs.json` 行数 ⇄ `GET /api/cron` 的 `count` 与 `jobs[]` 长度、7 列 |
| `toolsRowsEqualTheToolkit` | `agent.getToolkit()` 的工具名集合 ⇄ `GET /bot/tools` 的 `name` 集合 |
| `skillListCountEqualsSkillsOnDisk` | 写 2 个 `SKILL.md` 技能目录 ⇄ `GET /api/skill/list` 的 `count` 与 `skillCodes[]` |
| `modelsCountEqualsProviderCatalog` | 供应商桩的 2 个模型 ⇄ `GET /api/models` 的 `count`、`models[]` 6 列、`fetchedAt>0`、`stale=false` |
| `countOrTotalFieldsNeverLie` | **全表普查**：任何 JSON 响应里名字叫 `count/total/size/…` 的字段，值必须等于它旁边那个数组的长度（主编在别的项目踩过 `IPage.total` 恒 0 的坑）。先断言 `countBearingEndpoints >= 3` 防止尺空跑 |
| `switchAndDeleteTellTheTruthAboutUnknownIds` | 未知 id 的 `POST /api/session/switch|delete` 必须 **404**，不许回 `ok:true` |
| `missingIdOnWriteEndpointsStaysFourHundred` | 缺 `id` 的写口必须 400 |
| `fixtureNeverTouchesARealProvider` | providerCode + `getProvider().name()` + 桩的调用计数 ⇒ 证明以上所有断言没打到厂商 |

**404/405 也是台账决定的**：`unknownPathStillFourOhFourWithJsonErrorShape`、
`unadvertisedMethodGetsFourOhFiveWithAllowHeader`（405 带 `Allow: GET, OPTIONS`，OPTIONS 预检回
`Access-Control-Allow-Methods: GET, POST, OPTIONS`）。

### 1.2 缺陷账（本棒实测到的，不在本棒写域内的按 §WIRING 交主编）

- **D-P28-1｜`/api/skill/sync` 有两个方法面，语义没对齐。** 复算：
  `grep -n "api/skill/sync" z-bot-core/src/main/resources/web/index.html` ⇒ 控制台用 **GET**（`index.html:1368`）；
  `grep -n 'POST", "/api/skill/sync"' z-bot-core/src/test/java/com/zifang/z/bot/channel/HttpChannelTest.java` ⇒ 既有单测用 **POST**。
  两处都是真调用方，所以台账把 GET/POST 两行都登记（缺一个另一个就把 405 打进来）。
  本棒**不当它是"新方法"**，只把口径不一致记在这。
- **D-P28-2｜`POST /api/skill/push` 推的是拉，且 `ok` 恒真。** 复算三条：
  `HttpChannel.java:412` 是 `"/api/skill/push".equals(path) || "/api/skill/sync".equals(path)` ⇒ 两条走同一个
  `skillSyncResult()`（`:685`）；`skillSyncResult()` 第一行就是 `resp.put("ok", true)`（硬编码，不看结果）；
  `grep -n "pushSkill" z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java` ⇒ **0 命中**，
  `BotAgent` 只有 `syncSkillsFromCenter()`（`:974`，下行方向），根本没有上行能力 ⇒ 这个端点把"推送技能到 center"
  广告成了一个拉取动作。而 `syncSkillsFromCenter()` 在 center 未配置时返 0、抛异常时也返 0 ⇒
  **`installed:0` + `ok:true` 三种情形（无事可同步 / 未配置 / 同步失败）在响应里不可区分**。
  本棒的处理：台账把 `defect=D-P28-2` 挂在行上并让守卫强制它出现在本文件；**不改 `BotAgent`**（禁改），
  也不写一个"刚好能过当前断言"的最小实现来糊。
- **D-P28-3｜SSE 词汇表在页面侧不闭合（本棒已修）。** 服务端 `HttpChannel` 发 10 个事件名，
  旧 `index.html#processEvent` 只 `if/else` 了 8 个 —— `steer` / `compact` 两帧被**静默丢弃**
  （`type` 命中不了任何分支就什么都不做）。修法：补齐两个分支 + 加 `else` 兜底显式报"未处理的事件"，
  并把"页面处理的事件名集合 == 台账 `sse-events:` 词表"钉成守卫（§2）。
- **`/api/agent/register` 的顶层字段随状态分叉。** center 未配置时只回 `{ok,error}`，配置了才多
  `{instanceCode,timestamp}` ⇒ 台账只钉"必有那一列"（`object;fields:ok`），分叉本身记在这里，
  等主编裁定该端点在未注册实例上应否 501 而不是 200。
- **`/api/models` 的缓存在 `configDir == null` 时会落到 `java.io.tmpdir/zbot-models-cache.json`**
  （共享路径，两个 JVM 互相看得见；既有 `HttpChannelTest` 为此要先删那个文件）。本棒只在台账
  `row_count_from` 记真实来源 `ModelCatalogCache#catalog()`，改缓存归口留给主编。

（§2 起边做边补。）

---

# P28b —— 收口棒四杠实测 + 前棒在途断点定位

工作树 `/private/tmp/zbot-wt-p28`，分支 `w13-p28`，收口 HEAD 见 P28b-8。
本节只写 p28b 亲量到的东西；上文 §0/§1（p28a 的台账与 D-P28-1..3）未改一字。

## P28b-0 开工实测 vs 工单"已知"（不符处以实测为准）

工单 §0 的开工命令真跑了，逐条对账：

| 工单"已知" | 实测 | 处理 |
|---|---|---|
| 前棒未提交 **3** 个路径 | `git status --porcelain` 出 **8** 行：`channel/HttpChannel.java`、`ui/RawTerminalReader.java`、`_doc/005_testing/acceptance/p28/{EVIDENCE.md,ROUTES.tsv,HttpRouteLedgerTest.java,HttpRouteShapeTest.java,HttpSseContractTest.java,P28HttpFixture.java}` | 8 个全按显式路径提交：`311b970`（7 个）+ `406a542`（`web/index.html` 单独封存，P19 的地盘） |
| @Test **1060 / 97** | 收口树 `grep -rho "@Test" --include='*.java' \| wc -l` = **1090**，文件 **101**；全仓 == `z-bot-core`（其余模块无测试源）；surefire 实跑 **1087 / 99 类** | 报告用实测量；差额（1090 标注 vs 1087 执行）是 `*Test` 之外的类里的注解，不计入分母 |
| minimax key | 只允许量长度：`awk -F= '/^minimax\.api\.key=/{print length($2)}'` ⇒ **125**，值全程未读未印未拷 | — |
| 杠④ 三格 `8 / 2dadaed0 / 690ddbc0` | 三时点各量一次，三次都是这三格（P28b-5） | 未动、未"调" |

一处**量尺错误当场纠掉**：`ls ~/.zbot | wc -l` = **7**（`ls` 缺省不列点文件），而杠④ 的尺是
`len(os.listdir())` = **8**（隐藏的 `.stty.bak` 计一项）。拿 7 那把尺去对 8 就会凭空造一条
"少了 1 项"的假缺陷。`.stty.bak` 是不变量的一部分，**不许删**（删了才是动杠④）。

## P28b-1 前棒死于 150 轮留下的两处硬断点（都定位到行）

1. **全 reactor 编译不过**（工单说基线可编译，是过期读数）。`~/.cache/zbot-p28-lead/bar1_a0_prefix_fail_raw.log:66,68` 逐字：

   ```
   [ERROR] COMPILATION ERROR :
   [ERROR] /private/tmp/zbot-wt-p28/z-bot-core/src/test/java/com/zifang/z/bot/channel/HttpSseContractTest.java:[377,23] constructor SseStream in class com.zifang.z.bot.channel.HttpSseContractTest.SseStream cannot be applied to given types;
   ```

   p28a 改 `HttpSseContractTest` 改到一半轮次用尽，`SseStream(HttpURLConnection)` 没写 ⇒ testCompile 直接挂，
   四杠一条都跑不了。补回构造器：`c0b693a`。
2. **一条恒红断言在结构上不可能绿**，而且红因在测试自己身上。HEAD=`c0b693a` 的三跑（`bar1_pre_ssefix_{a,b,c}.log`）
   每次都红在同一条，`bar1_pre_ssefix_b.log:919` 逐字：

   ```
   [ERROR]   HttpSseContractTest.frameGrammarHoldsForEveryFrameOfARun:100 SSE 帧必须逐字节等于 "event: X\ndata: Y\n\n"，实到 event: step\ndata: Step 1\n
   ```

   服务端 `HttpChannel#frame()`（`HttpChannel.java:581`）写上线的是 `"event: X\ndata: Y\n\n"`，
   而测试自己的切帧器 `next()` 写的是 `new String(b, 0, b.length - 1, ...)` —— 把帧分隔符的最后一个 `\n`
   当"切分开销"砍掉了。砍掉之后 `:100` 的逐字节等式**恒不成立**，恒红的恰好是
   "帧到底有没有以空行结束"这件最该被测的事。修完 `HttpSseContractTest` 9/9 绿（`e449dc6`，
   收口三跑逐字见 P28b-2）。
   这一条留给下一支写手：判"生产有缺陷"之前，先确认**断言自己的分帧没吃掉被测的那个字节**。
3. 附带一处负载抖动，不是本棒引入的：`bar1_pre_ssefix_c.log:310-313`

   ```
   [ERROR] com.zifang.z.bot.mcp.McpRealStdioServerTest.zbotTransportHonoursItsOwnDeadlineWhileKernelBlocksInReadLine -- Time elapsed: 2.053 s <<< ERROR!
       at ...McpRealStdioServerTest.java:366
   ```

   单独复跑 3/3 绿 ⇒ 判为负载相关的 900ms 档 deadline（P21 地盘，本棒不动）。
   收口三跑该类 **3/3 全绿**（20.90 / 19.46 / 21.38 s），两次读数都留在这里。

## P28b-2 杠①：全 reactor `mvn -o test` ×3（收口树 `e449dc6`）

尺：`bash ~/.cache/zbot-p28-lead/bar1_run.sh <a|b|c>`，内部就是工单那一条
`rm -rf z-bot-core/target/surefire-reports && mvn -o test`；判据 `python3 ~/.cache/zbot-integrate/b1parse.py`。
三跑逐字：

```
BAR1PARSE .../bar1_a.log module_lines=1 class_lines=99 module_sum=1087 class_sum=1087 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE .../bar1_b.log module_lines=1 class_lines=99 module_sum=1087 class_sum=1087 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE .../bar1_c.log module_lines=1 class_lines=99 module_sum=1087 class_sum=1087 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
```

`BAR1_HEAD=e449dc690aa4b888e84d3ff7e19de23885e0fe5f` ×3，`BAR1_MVN_RC=0` ×3，
`[INFO] Tests run: 1087, Failures: 0, Errors: 0, Skipped: 0` ×3；
`HttpSseContractTest` `Tests run: 9, Failures: 0` ×3（就是 P28b-1(2) 那条，修完三跑都绿）。
时间片：a 19:28:27→19:29:31，b 19:29:31→19:30:31，c 19:30:31→19:31:37。

## P28b-3 杠②：`LEDGER.tsv` 10 条，记号分布 **RED-OK 6 / SURVIVED 4**

harness `~/.cache/zbot-p28-lead/p28b_mutation.py`（mtime 19:24:20）机器生成
`_doc/005_testing/acceptance/p28/LEDGER.tsv`（mtime **19:26:38**，晚于 harness）；台账字段
`id/name/file/gate/marker/evidence/bytecode_proof`，记号只用了允许集里的两种。闸门逐字：

```
LOCK_ACQUIRED /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock
BACKUP _doc/005_testing/acceptance/p28/ROUTES.tsv md5=1ceb6af8 -> .../mutbak/_doc__acceptance__p28__ROUTES.tsv
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java md5=e4bd2a3a -> ...
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java md5=9c6683ff -> ...
RESTORED_ALL {'ROUTES.tsv': '1ceb6af8', 'HttpChannel.java': 'e4bd2a3a', 'RawTerminalReader.java': '9c6683ff'}
LEDGER=/private/tmp/zbot-wt-p28/_doc/005_testing/acceptance/p28/LEDGER.tsv mtime=19:26:38 harness_mtime=19:24:20
TALLY {'RED-OK': 6, 'SURVIVED': 4} TOTAL=10
```

- **注入进了字节码**：9 条源码级变异每条带 `class_md5` "前值->后值"，前值取的是**上一次构建出的那个 class**
  （M1 `68a621bf->26d6a60d`，M2 `26d6a60d->65219b43`，M3 `65219b43->80ebb1c3`，M4 `80ebb1c3->c123a988`，
  M5 `c123a988->70c7e768`；HttpChannel 一支 M6 `2358ca09->ef33842d`→M8 `43d3b31e->fac00b66`）——
  相邻两值必不相同 ⇒ 每次注入都真的重新编译并换了字节，不是"改了源码没进 class"那种假注入。
  另一侧的自证是 **M10 的前值回落到 `68a621bf`（= M1 的前值 = 基线）**：说明 M1..M5 之后源码确实被还原回了基线，
  否则 M10 量不到同一个前值。M9 注的是台账文件本身，不涉及字节码 ⇒ 老实记 `file_md5=8af6e08c`，不冒充 class_md5。
- **还原**：三处 BACKUP md5 与 RESTORED_ALL md5 逐格相同；本棒另用 `git diff --stat HEAD` 对这三个文件
  **独立复算**了一次为空（不只信 harness 自报）。全程没有用 `checkout/restore/reset`。
- **负向断言带阳性对照，两支**：
  M2 把 `EXTERNAL_WAIT_TIMEOUT_MS`（`RawTerminalReader.java:79`）抬成 `Long.MAX` 并在 PATH 前面摆一个
  永不退出的假 `stty` ⇒ `verdict raw=None rc=KILLED wall=40.00`（被 harness 的墙钟砍掉），
  证明"有界等待"这支尺真有牙；M5 是**故意做的等价变异**（`isSttyAvailable()` 直接 `return true`，
  macOS 上恒真 ⇒ 等价）判 `SURVIVED`，用来钉住"全绿不等于杀得掉"。
- **4 个 SURVIVED 的成色分三类，如实写、不糊**：
  - **M5**：设计上就该活（等价变异阳性对照）。
  - **M6 摘掉台账的 404 门 / M7 摘掉 405 的 `Allow` 头**：`mvn` 仍 `rc=0` ⇒ **真覆盖缺口**。
    本期新增的 `HttpRouteLedgerTest`/`HttpSseContractTest` 钉住了台账同源与帧语法，没钉 404/405 的响应形状。
    进 P28b-7 未做清单，不写成"已过"。
  - **M10 终端私有表（`RawTerminalReader.java:36` 那 9 条）里删 `/theme`**：唯一能抓它的是杠③ S4c
    （真 REPL 帮助表，走 shaded jar），本棒没把 `mvn -o package` 接进变异回路 ⇒ 记"未覆盖"，不记"杀不掉"。

## P28b-4 杠③：真进程 / 真 PTY，3 次独立跑，每次 **27/27**

尺：`python3 ~/.cache/zbot-p28-lead/p28b_pty_e2e.py <tag>`。七支各自测一件事：
S1 真 PTY（`os.openpty()`，子进程 stdin 接 sfd，探针主体 `src/test/java/com/zifang/z/bot/ui/RawTerminalVerdictProbe.java`
放在同包里以吃 package-private），S2 无 tty（管道），S3 PATH 下摆挂死的假 `stty`，S4 真 REPL（shaded jar），
S5 `z-bot serve` 真进程 HTTP（只绑 `127.0.0.1` 空闲口 + `--config-dir` 临时 profile + `stub-key-not-real`），
S6 杠④ 跑前跑后，S7 key 卫生。三跑读数：

```
CHECKS=27 FAILED=0 TAG=b
CHECKS=27 FAILED=0 TAG=c
CHECKS=27 FAILED=0 TAG=f
```

run `f` 是在收口树（`E2E_HEAD=e449dc6…`，`E2E_DIRTY_ROWS=0`，先 `mvn -o package -DskipTests` 且
`PREFLIGHT jar_mtime=19:32:26 stale_src=0`）重跑的，b/c 是同一棵生产树上的前两跑；跑完 `git status --porcelain` 仍为空。
关键几条逐字（run f，run b/c 同判）：

```
PASS   S1b stty raw 真切过去（判词 DONE 且 rawMode=true） | raw_verdict=DONE raw_mode=true
PASS   S1c 真 stdin 字节往返：Tab 把 /stat 补成 /status | PROBE_LINE=/status\n
PASS   S2a 无 tty 时判词是 NONZERO_EXIT（而不是 DONE/超时） | raw=NONZERO_EXIT raw_mode=true
PASS   S3a 阳性对照：假 stty 真被子进程用到（构造耗时贴着 5s 档） | construct_ms=5025
PASS   S3b 有界等待起作用：整跑 60s 内回来（旧无参 waitFor 会挂满 60s） | rc=0 wall=5.27
PASS   S3c 超时后仍把真字节读回来（不挂、不吞输入） | raw_mode=true line=/after-poison\n raw_verdict=TIMED_OUT
PASS   S4b 注册表派生命令真出现在 TUI 帮助里（7 个抽样全到） | hit=7/7 missing=[]
PASS   S5b 接缝如实记账：/api/commands 现在还不存在（404） | code=404 body=b'{"ok":false,"error":"not found: /api/commands"}'
```

**可测性边界（NO-RUN，不当 passed）**：本环境是无终端的后台会话，脚本能给判据的只到
"raw 真切过去 + 真 stdin 字节往返 + 有界等待 + 帮助表可见"这一层。真 tty 下的人机行为
——光标/选择区重绘、Ctrl-R 历史、SIGWINCH 自适应、肉眼比对回显是否单份—— **NO-RUN**，
理由：这些行为没有可信的机读判据（要人在环），硬凑断言只会造出一条永远绿的假尺。
另有一处读法教训记在这里以免被误读：S4 抽样第一版报"7 条只中 4 条"，追下去是**我的读法
在表格中段提前 break**，不是命令表缺项；改成 `drain_quiet()` 整表读完 ⇒ 7/7。

## P28b-5 杠④：三时点各量一次，三格未动

| 时点 | entries | `config.properties` md5[:8] | `state.db` md5[:8] | 是否落盘 |
|---|---|---|---|---|
| 开工 T1 | 8 | 2dadaed0 | 690ddbc0 | 只打在会话控制台，**未 tee** ⇒ 本节里它是最弱的一格，只作参考 |
| E2E 在飞 T2 | 8 | 2dadaed0 | 690ddbc0 | `bar3_run_{b,c}.log:2,38` 各打 BEFORE/AFTER 一对，run f 再一对（共 3 对 6 行） |
| 收尾 T3 | 8 | 2dadaed0 | 690ddbc0 | `~/.cache/zbot-p28-lead/bar4_t3_final.log`（19:29:09） |

逐字（T3）：

```
BAR4_T3 entries=8 md5={"config.properties": "2dadaed0", "state.db": "690ddbc0"}
BAR4_T3 names=[".stty.bak", "config.properties", "cron", "memories", "models-cache.json", "sessions", "state.db", "workspace"]
```

即：E2E（真 PTY + 真 serve + 真 REPL）跑完不新增项（S1e），`stty` 备份落在本次私有路径
`/var/folders/.../zbot-stty-*.bak` 且 close 后 `PROBE_BACKUP_EXISTS=false`（S1d），
`~/.zbot` 那 8 项一个字节都没被这期的改动动过（S6 断言 + T2/T3 六个读数互证）。

## P28b-6 本棒新增缺陷与观察（p28a 的 D-P28-1..3 在上文）

- **D-P28b-1（生产缺陷，已修 `5e3b6e1`）：raw 模式在真终端上其实从没切成功过。**
  `runBounded` 用 `ProcessBuilder` 起 `stty`，缺省 stdin 是**管道**；BSD `stty` 拿 **fd 0 这个对象**
  做切换 ⇒ 真终端上也永远 `not a tty`，判词恒 `NONZERO_EXIT`。可观测证据（改前的真 PTY 探针）：
  父端同时收到 tty 回显 **与应用回显的双份字节**（raw 没切走的指纹），且 `PROBE_RAW_VERDICT=NONZERO_EXIT`。
  加 `.redirectInput(ProcessBuilder.Redirect.INHERIT)`（`RawTerminalReader.java:121`）之后判词 `DONE`、
  `PROBE_RAW_MODE=true`、回显单份（S1a/S1b）。
  为什么全绿的旧基线看不见它：单测只测字符串方法，够不到 **fd 继承语义** ⇒ 必须真 PTY（杠③）。
  这条也是杠② M1 的靶子：把这一行摘掉，M1 立刻 `NONZERO_EXIT` 判红（`RED-OK`）。
- **D-P28b-2（观察，本棒未改）：判词说"没切成"时 `rawMode` 仍是 `true`。**
  S3c 实测：假 `stty` 挂死 ⇒ `raw_verdict=TIMED_OUT` 而 `raw_mode=true`，字节照样读回来（不挂不吞）。
  也就是上层可见的标志位说的是"我以为切了"，判词说的是"它到底切没切"，两者不一致。
  未改的理由：要在超时分支补回滚，回滚本身就得再叫一次正挂死的那支 `stty`；
  "不回滚更安全"还是"必须回滚"要主编裁，本棒不猜着改。
- **P28b-W1（web 侧，归 P19，本棒按约未碰 `index.html`）**：`newSession()` 在 `web/index.html`
  里**声明了两次**（`:911` 与 `:1243`；JS 函数声明提升 ⇒ 后一个覆盖前一个，生效的是 `:1243`，
  已用 node 单独实证"后声明赢"）。真进程侧从服务端吐出的字节再量一次同样 `dups=['newSession']`（S5g），
  两个口径各成立一次。连带 web 的 `/new`、`/clear` 是否真在服务端建/清会话未裁（见 `WIRING.md` §3.6）。

## P28b-7 未做 / 未覆盖（点名，不留白）

1. **M6/M7 是真缺口**：`HttpChannel` 的 404 门与 405 `Allow` 头没有任何断言钉着 —— 杠② 那 2/10 杀不掉就是它俩。
2. **M10 未接进变异回路**：终端私有 9 条命令表要能被杀，得把 `mvn -o package` 塞进 harness（本棒没做，代价已写明）。
3. `/status`、`/confirm` 的跨端字段口径未对账（`WIRING.md` §5 已把问题原样交给 P19）。
4. `/api/commands` 本棒**只记接缝不实现**：真进程 404（S5b 逐字）+ 全仓字符串双口径零命中（`WIRING.md` §2）。
5. 真 tty 人机体验类行为：NO-RUN，理由见 P28b-4 末段。
6. `McpRealStdioServerTest:366` 的 900ms 抖动：P21 地盘，本棒只留两次读数（改前 1/3 红、收口 3/3 绿）。

## P28b-8 现场保全与红线自证

- **第一动作是保存现场**：`311b970`（7 个显式路径）→ `406a542`（`web/index.html` 单独封存给 P19），
  之后才有第二次动作。此后全程**未用** `checkout/clean/restore/stash/reset --hard`。
- 注入还原只用本次运行前 cp 的副本 + md5 对账，并独立复算（P28b-3）。
- 未 push；未把本分支往 main 上并（`114b37f` 只把 `main`=`f237a25` 并进来，反向零操作）；
  未改 `_doc/001_arch/hermes-roadmap.md`；未改 `slash/SlashRegistry.java`、`web/index.html`。
- 持久产物全在 `~/.cache/zbot-p28-lead/`（`.log` 被 `.gitignore` ⇒ 决定性读数原样贴进本文件），未写 `/tmp`。
- 假 HTTP 走自写 `ServerSocket`+`bind(0)`+`Connection: close`，未用 `com.sun.net.httpserver`；
  无 `pip install`/`npm i`，无守护进程，未打真厂商 API。
- 收口提交序列（自旧到新）：`311b970` → `406a542` → `114b37f`(merge main) → `c0b693a` → `5e3b6e1` →
  `d8f914e`(WIRING.md) → `e449dc6`(SSE 帧切分修 + LEDGER.tsv)。

---

# P28-lead（主编亲测）—— 杠③ 从 NO-RUN 变成有数

本节只写我在**目标树 main** 上量到的东西。上文 §0/§1（p28a 的台账与 D-P28-1..3）、
P28b 的 p28b-0..8 未改一字。

## P28-lead-0 身份与现场（逐轮重打，不是一次性声明）

```
ROUND_START|1|20:59:38|head=9884059|harness_md5=9e783bbc|tree_dirty=0
ROUND_END  |1|rc=0|21:11:06|head=9884059|harness_md5=9e783bbc|tree_dirty=0
ROUND_START|2|21:11:06|head=9884059|harness_md5=9e783bbc|tree_dirty=0
ROUND_END  |2|rc=0|21:22:34|head=9884059|harness_md5=9e783bbc|tree_dirty=0
ROUND_START|3|21:22:34|head=9884059|harness_md5=9e783bbc|tree_dirty=0
ROUND_END  |3|rc=0|21:34:02|head=9884059|harness_md5=9e783bbc|tree_dirty=0
```

`tree_dirty` 是 `git status --porcelain | wc -l` ⇒ 三轮全程量的是提交树；`harness_md5` 相同 ⇒ 三轮跑的是
同一份尺的字节。每轮墙钟 688s（三轮到秒相同）。

## P28-lead-1 三轮 tally（原文）

```
E2E|run=lead_v3_r1 checks=30 pass=30 fail=0 llm_hits=1 serve_port=50709 result=OK
E2E|run=lead_v3_r2 checks=30 pass=30 fail=0 llm_hits=1 serve_port=59260 result=OK
E2E|run=lead_v3_r3 checks=30 pass=30 fail=0 llm_hits=1 serve_port=63989 result=OK
```

30 条的构成按名字前缀数：A 组 7（真 tty/fd0 面）+ S 组 12（SSE 线字节面）+ T 组 7（台账/路由/文档派生面）
+ U 组 4（不变量与卫生）。`llm_hits=1` 是假供应商真收到过那一轮请求的计数，不是"打了真 API"。

## P28-lead-2 有牙性复核（跨轮 + 离线）

- **BAR4 九行**（3 轮 × t0/t1/t2）去掉 `tag=` 后 `sort | uniq -c` ⇒ `9` 条逐字节同一串：
  `dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125 names=[…]`（names 列实测就是那 8 个，
  `.stty.bak` 与空 `cron` 都在，一个没删）。
- **BUCKET 九格**：r1 vs r2、r1 vs r3 的 `diff` 都为空 ⇒ 文档派生尺的读数跨轮稳定，不是碰巧绿。
- **CHECK 名集合**三轮 `md5 -q` 相同（`59706288…`）⇒ 没有"这轮少跑几条"。
- **离线自证**（`~/.cache/zbot-p28-lead/selfcheck_v2.py`，直接喂三轮落盘的 `SSE_raw_wire.bin` 与真 `index.html`）：
  `SELFCHECK_DONE|lines=21 rc=0`。其中 5 条是**正向对照**（证明"修完"没有把守卫修哑）：
  `S4_regex_has_teeth_on_bare_newline`、`S4_regex_has_teeth_on_trailing_newline`、
  `S0b_accepts_both_loopbacks`、`S0b_rejects_wildcard_and_lan`、`T7_expectation_still_rejects_a_wrong_header`。

## P28-lead-3 前一轮 `lead_r1` 那 10 条红的归属

结论先说：**10 条全是量具自身的失真，没有一条指到产品行为**；而且其中 3 条我第一版给的成因是**编的**
（见第 7/8/12 行）。"当时读数"抄自 `~/.cache/zbot-integrate/p28r1.log`。

| # | CHECK | 当时读数（原文片段） | 复算判定 | 修法 |
|---|-------|--------------------|---------|------|
| 1 | S0b | `sockets=31 offenders=['rapportd 632 … TCP *:49236 (LISTEN)']` | `lsof` 没加 `-a` ⇒ `-p` 与 `-iTCP` 成 OR，把**别人进程**的监听塞进被审集（`sockets=31` 里 30 条不是我的） | `lsof -nP -a -p %d -iTCP -sTCP:LISTEN`；违规只看地址列 |
| 2 | S4 | `frames=3 解析出=0 … has_cr=False` | 切帧正则用 `$`（python 里它 additionally 匹配"最后一个 `\n` 之前"）⇒ 合法帧被判不匹配 | 锚 `\Z`；另加两条对照：data 里出裸换行必不匹配、结尾多一个 `\n` 必不匹配 |
| 3 | S5 | `vocabulary=10 seen=[] unknown=[]` | 依赖 #2 的解析 ⇒ 输入为空；"空输入判无违规"是假绿形状，它当时报红是另一处口径 | 先修 #2；解析数为 0 时 FATAL，不再 PASS |
| 4 | S6 | `final=[] 帧数=3` | 同因（同一个解析） | 同上 |
| 5 | S7 | `done='' step_frames=0 final_len=0` | done 帧的数字当时拿**我自己拼的字符串**当被测对象，且正则组索引取错 | `served_text = fake.hits[-1]["content"]`，断言 `count/total` 等于**服务端真吐出的字节**的长度 |
| 6 | T1 | `disk C=['/clear', '/confirm', '/help', '/new', '/status', …]` | 抓盘上清单的函数在收尾行 **break 在 append 之前** ⇒ 广告清单整段丢掉 `/exit`（最后一条广告和 `` `) `` 同在 `index.html:1197` 一行） | `p28_e2e.py:448-453`：遇 `` `) `` 先 `seg.append(line)` 再 `break`（先收进行再停） |
| 7 | T2 | `drift=['3.4 web 广告了但没有分支接住: WIRING=1 重算=0', …]` | **文档是对的、尺错**（09-26 21:38 复算）：`grep -n "exit" web/index.html` 全文件只 1 处命中＝`:1197` 的 `/help` 文案；`git log -S"cmd === '/exit'" -- <该文件>` **0 命中** ⇒ 历史上任何一版都没有 `/exit` 分支 | 修 #6 后 `wiring=1 recomputed=1 members=/exit` 自然对上 |
| 8 | T3 | `drift=["3.4 …: 文档=['/exit'] 重算=[]"]` | 同因（同一次抓取坏） | 同上 |
| 9 | T7 | `offenders=["/bot/stop code=405 allow='POST, OPTIONS' declared=['POST']", …]` 9 条 | 期望值当时用 `endswith` 贴在**状态行**上比 ⇒ 方法集合真一致也判红 | `allow == sorted(set(declared) | {"OPTIONS"})`；再加逐路径 `OPTIONS` 必须 204 的探针（`opt_offenders`） |
| 10 | U4 | `cfg=/Users/zifang/.cache/…/lead_r1/cfg files=['config.properties', 'cron', …]` | 判定式靠"临时 profile 里没有 `state.db`"这种偶然形状 ⇒ 换成真家目录同样能过，等于没测 | `real_cfg != real_home and not real_cfg.startswith(real_home + os.sep)` ＋ stub-key/isfile/isdir 合取 |

另外两条不在那 10 条里、但同属量具缺陷，一并记账：

- **11｜崩在起跑线**：`p28e2e_lead_r1.log` 尾部 `E2E-EXC NameError("name 'free_port' is not defined")`
  （`:614`）⇒ A 组之后 S/T/U 一条都没跑。这条不是"红"，是**整跑没发生**，比红更该记。
- **12｜我上一轮的归因是编的**：我把 T2/T3 的红写成"`web/index.html` 里 `/exit` 的分支被补上了 ⇒ 文档漂"，
  并把这句话转进了 p19 的票。复算见第 7 行：那个分支从来不存在。错因已在 p19 第二张票的 §1′ 里撤回。

## P28-lead-4 这尺**没有**量到的（不许把 30/30 读成"P28 没问题"）

- **D-P28-2 未裁定，本尺故意不钉**：钉它要先定"`POST /api/skill/push` 应当做什么"，那是裁定不是测量。
  复算三条（09-26 21:38，HEAD `9884059`）：`HttpChannel.java:412-413` 两条路由共用同一个 `skillSyncResult()`；
  `:685-692` 里 `resp.put("ok", true)` 在 `:687` **无条件**写死，`:688` 的 `installed` 来自
  `agent.syncSkillsFromCenter()`（下行拉取）；`grep -c pushSkill …/agent/BotAgent.java` = `0` ⇒ 没有上行能力。
- **`/api/commands` 仍是"不存在"**：`grep -rn "api/commands" --include='*.java' --include='*.html' z-bot-core/src | wc -l` = `0`。
  归 P19（写手在 `w14-p19` 已落 `147389f` 补 WEB/ACP 消费端 + 一致性守卫、`11e2d4f` 让 ACP 只对已 initialize 的连接发）。
- **D-P28-1 的双方法面没闭合**：`index.html:1391` 用 GET、`HttpChannelTest.java:427` 用 POST、
  `HttpChannel.java:278/283` 两行都登记。本尺 T5/T6/T7 只保证"登记的都活着、方法不匹配必 405＋Allow"，
  不裁哪一个口径是对的。
- **真 tty 人机体验**：NO-RUN（沿用 P28b-4 的理由：A 组是 `openpty` 驱动的机器判词，不是人眼验收）。
- **688s/轮的构成没有逐项时间戳**。我上一轮口头归因"非 chunked 响应一律等到 socket 超时 ⇒ 慢"，
  本轮被自己的读数证伪：r3 全部 `wall=` 里最大只有 `12.32s`（A7 那条假 stty），不存在 60s 级等待 ⇒
  慢的成因**未归因**，不许作为下一次归因的输入。

## P28-lead-5 环境账

- 三轮各自 `U3 serve_process_reaped PASS`，收口时 `ps -eo args | grep "[j]ar serve"` **0 命中** ⇒ 本尺没留 serve。
- 此刻仍在跑的 5 支**不是本尺漏的**：`3657/3689/3699/12799`（gateway，profile 分别指向
  `_doc/005_testing/acceptance/p16/out/runtime/A-profile-r1`、`/tmp/p17-e2e-*`、`zbot-p18-e2e-*`、p16 profile）、
  `4026`（repl，profile 指向 `~/.cache/zbot-p23-lead/e2e/…`）；全部 `ppid=1`、etime 2h30m—3h49m、
  **全部只绑 127.0.0.1**（`lsof -nP -a -iTCP -sTCP:LISTEN` 逐条可查：61003/61004/61006/61007/61014/61015/61016/62618/62619）。
  它们是 p16/p17/p18/p23 那几支 E2E 收尾缺陷留下的，属于别的会话 ⇒ **只记账，不杀**（杀别人的进程不在我的授权里）。
- 持久产物在 `~/.cache/zbot-p28-lead/e2e_p28c/lead_v3_r{1,2,3}/` 与 `~/.cache/zbot-integrate/p28v3r{1,2,3}.log`；
  `.log` 被 gitignore ⇒ 决定性读数原样贴进本节。

## P28-lead-6 控制台接线的运行时兑现（新增 T9 死函数守卫 + T1 改判字节）

**动因**：杠③ 那 31 条点名的全是"名字在不在分支里"，没有一条问"这个函数有没有被接住"。
浏览器层实测出三处断接线（能力写好了、页面上一条请求都不发 / 假实现静默覆盖真实现），
JVM 侧任何一把尺都看不见 ⇒ 加 T9（第 32 条点名判据），并把 T1 从"派生表相等"改成"字节相等"。

### 这一节的量具身份（先钉住"哪把尺量的哪棵树"）

- 提交树 `696c632`；本轮在途未提交的项：`M _doc/005_testing/acceptance/p28/__pycache__/p28_e2e.cpython-314.pyc`, `M _doc/005_testing/acceptance/p28/p28_e2e.py`（四杠读数按**盘面字节**记账，md5 见下）。
- 量具 `p28_e2e.py` md5 = `2e8134bb`；被测控制台 `src` md5 = `bb3ff2e6`（53093 B）。
- **修复前的参照字节不是固定名副本，而是现取的** `git show 93d27cd:z-bot-core/src/main/resources/web/index.html`。
  上一轮我把一份名叫 `index_pre_fix.html` 的缓存当"修复前"用，那实际是修复**之后**的字节 ⇒ 得出过
  "T9 没有牙"的错判（教训已折进量具纪律台账：对照组的输入身份要用**被注入的那个特征字面量的出现次数**验，
  不能信文件名）。

### 杠①（合并树 + 控制台修复之后，`rm -rf z-bot-core/target/surefire-reports && mvn -o test` ×3 串行）

原文（`~/.cache/zbot-integrate/bar1_x3.log`）：

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_262304 start=2026-09-26 23:04:46 HEAD=696c632
BAR1_SRC_MD5_START|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
ROUND_START|r=1 ts=2026-09-26 23:04:46 bar4=8/2dadaed0/690ddbc0
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1151, Failures: 0, Errors: 0, Skipped: 0] class_sum=1151 f=0 e=0 s=0 files=107 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-26 23:06:07
ROUND_START|r=2 ts=2026-09-26 23:06:07 bar4=8/2dadaed0/690ddbc0
ROUND|r=2 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1151, Failures: 0, Errors: 0, Skipped: 0] class_sum=1151 f=0 e=0 s=0 files=107 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-26 23:07:31
ROUND_START|r=3 ts=2026-09-26 23:07:31 bar4=8/2dadaed0/690ddbc0
ROUND|r=3 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1151, Failures: 0, Errors: 0, Skipped: 0] class_sum=1151 f=0 e=0 s=0 files=107 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-26 23:08:49
BAR1_SRC_MD5_END|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
BAR1_CLASSES|src=bb3ff2e6 classes=bb3ff2e6 src_len=53093 cls_len=53093
BAR1_DONE|end=2026-09-26 23:08:49 dir=/Users/zifang/.cache/zbot-integrate/bar1_262304
```

读数：**三跑逐字相同 = Tests run 1151 / F 0 / E 0 / S 0，class_sum=1151（107 个测试类，两把尺同数），
socket_hits=0，BUILD SUCCESS**；`bar4=8/2dadaed0/690ddbc0` 在 4 个采样点逐格相同（杠④ 未破）；
`BAR1_CLASSES` 那行说明 `mvn -o test` 之后 `target/classes` 与 `src` 已是同一份字节
（此前那对 `416774b7 ≠ bb3ff2e6` 的差就是"被伺服的字节比源码旧"这一类缺陷的形状，正是新 T1 要抓的那一层）。

### T9 的有牙性对照（九行判据，注入全在内存副本上，工作树一个字节未动）

原文（`python3 -u ~/.cache/zbot-p28-lead/t9_control.py`，exit 0）：

```
CTRL|N1_prefix_red                      PASS | top=36 dup=['newSession'] unref=['autoResize', 'deleteSession', 'loadCommands']
CTRL|N1_prefix_really_has_two_newSession PASS | count=2
CTRL|N2_fixed_green                     PASS | top=36 dup=[] unref=[]
CTRL|J1_loadCommands_call_removed       PASS | top=36 dup=[] unref=['loadCommands']
CTRL|J2_deleteSession_wiring_removed    PASS | top=36 dup=[] unref=['deleteSession']
CTRL|J3_autoResize_listener_removed     PASS | top=36 dup=[] unref=['autoResize']
CTRL|J4_duplicate_newSession_back       PASS | top=36 dup=['newSession'] unref=[] 搬回的行=async function newSession() {
CTRL|J5_stripper_keeps_strings_cuts_comments PASS | lines=["fetch('/a//b')  ", ' code();', '']
CTRL|J6_no_script_block_is_empty_not_green PASS | top=0
CTRL_DONE|lines=9 rc=0
```

口径：`N1` 用修复前的真字节判红并点名三处断接线；`N2` 用修复后的盘面字节判绿；
`J1–J3` 各摘掉一处接线**且保留那句解释性注释** ⇒ 各自只点名被摘的那一个名字 —— 这正是"注释不算调用点"
这条规则存在的理由（尺若按整份文件数字符出现，这三支全会读成绿，而缺陷长得和修复前一模一样）；
`J4` 把被删掉的假 `newSession` 搬回脚本尾部 ⇒ `dup=['newSession']`；
`J5` 是 stripper 的自证（字符串里的 `//` 必须原样留着，否则"抹注释"会吃掉代码）；
`J6` 把 `<script>` 标签拿掉 ⇒ `top=0`，配合判据里的 `bool(fns)` 让"尺什么都没量到"蒙不成绿。

顺带两条尺自己的更正（都是被阳性对照逼出来的，不是顺手改的）：
① `js_functions` 原来按 `<script>` 字符串配对取块 —— 修复后的注释里就写着"同一个 `<script>` 里后声明者胜出"
这个**字面量**，配对解析会把注释里的字当标签；改成只认**独占一行**的标签。
② 调用点判定原来要求 `NAME(`，会把 `addEventListener('input', autoResize)` 这种**当回调传出去**的正当接线
判成孤儿（假红）；改成"去注释后的 JS 里出现这个名字本身"，HTML 那半仍只认调用式。

---

## P28-lead-7 · 三跑逐字对账（把 §8.11 那句"名字集合 md5 相同"换成一把更硬的尺）+ T1 篡改对照 v1 判作废

### 一、三跑身份与读数（23:09–23:45，同一棵 main 树、同一份尺字节）

```
ROUND_START|lead_t9_r2|2026-09-26 23:21:34+0800|head=696c632|harness_md5=2e8134bb|src_md5=bb3ff2e6|tree_dirty=2
ROUND_END|lead_t9_r2|rc=0|2026-09-26 23:33:41+0800
E2E|run=lead_t9_r2 checks=32 pass=32 fail=0 llm_hits=1 serve_port=58116 result=OK
ROUND_START|lead_t9_r3|2026-09-26 23:33:41+0800|head=d001ecf|harness_md5=2e8134bb|src_md5=bb3ff2e6|tree_dirty=0
ROUND_END|lead_t9_r3|rc=0|2026-09-26 23:45:47+0800
E2E|run=lead_t9_r3 checks=32 pass=32 fail=0 llm_hits=1 serve_port=54379 result=OK
E2E|run=smoke_t9  checks=32 pass=32 fail=0 llm_hits=1 serve_port=51512 result=OK   （23:09 起跑的那一跑）
```

三跑的 `head` 不同（`696c632`→`d001ecf`）而 `harness_md5`/`src_md5` 相同：中间那一笔是这台机器上
一个游离的"代提交"会话把我未提交的工作树改动原样提交并推送了（`d001ecf chore(代提交): 收妥本仓未提交的工作树改动`），
被提交的 EVIDENCE/p28_e2e.py/index.html 与当时盘上字节逐字节一致 ⇒ **量的还是同一份字节**，但"钉 commit 号"
这件事在这台机器上不能只靠提交信息判断，得看字节。

### 二、跨轮一致性：第一版哈希是错的，第二版才成立

链条脚本 `t9_series.sh:17` 写的是 `for t in "$SMOKE:smoke_t9" …`：**zsh 会把 `$名字:字母…` 当历史式修饰符解析**
（`:s` 是替换修饰符、必须带分隔符），于是整支脚本从第 17 行起 `bad substitution` 直接退出 ⇒ **那条 CROSS 从未产出**
（`CHAIN|series rc=1`）。病因不是"zsh 少了什么功能"，而是**未加花括号的展开后面紧跟了个冒号**；
修法就是 `"${SMOKE}:smoke_t9"`（3 行最小复现实测：改前 rc=1、改后 rc=0）。
我手算第一版时又把三跑的判决行原文拼起来取 md5，得到三个**互不相同**的哈希——
那是尺的错，不是三跑真有差异：`CHECK|` 行里合法地嵌着墙钟、`bind(0)` 端口、每跑随机 token、tty 母设备字节 md5、
临时 profile 绝对路径。把数字全抹掉又会被指责"抹太狠"。所以第二版**只点名、不泛指**（量具
`~/.cache/zbot-integrate/norm_verdicts.py`，`VOLATILE` 表就是这张点名清单）：

```
BYTES_ID|run=smoke_t9|bytes=8485|len(run)=8|bytes-4*len=8453
BYTES_ID|run=lead_t9_r2|bytes=8493|len(run)=10|bytes-4*len=8453
BYTES_ID|run=lead_t9_r3|bytes=8493|len(run)=10|bytes-4*len=8453
CROSS|bytes_residual_constant=8453|unique=YES（⇒ 载荷长度差全部由跑名长度解释，不是回归）
NORM|smoke_t9.log|lines=55|md5=6536e35fcaf511026bb34b6b88845c4e
NORM|p28_lead_t9_r2.log|lines=55|md5=6536e35fcaf511026bb34b6b88845c4e
NORM|p28_lead_t9_r3.log|lines=55|md5=6536e35fcaf511026bb34b6b88845c4e
PAIR|smoke_t9.log vs p28_lead_t9_r2.log|differing_lines=0 IDENTICAL
PAIR|p28_lead_t9_r2.log vs p28_lead_t9_r3.log|differing_lines=0 IDENTICAL
CROSS|three_round_verdict_identical=YES
```

**55 行 = 三跑各自的全部 `CHECK|`＋`TABLES`/`SERVED`/`JS_FNS`/`BUCKET`/`WIRING_EMIT`/`HAND_COPIED` 结构读数**，
归一后逐字节相同。`checks=32`、`tools=14`、`9 格`、`rows=26`、被伺服字节的 md5 这些**定值一个都没被抹**——
它们在三跑里本来就同，抹了才叫自欺。

`S10` 那条的 `bytes` 是三跑里唯一一个"不能凭点名抹平"的数（请求体长度真会随代码变），所以我给它单独一条
可算的恒等式而不是直接归一：拿这台机器上留存的 6 个不同长度跑名（7/8/10/13/14）拟合，
`bytes = 8453 + 4·len(run)` 全部命中，唯一偏离点 `8439` 属于 p25 那一代量具（scratch 根目录本身更短，
`bar3all/p25.all*.log`）⇒ 那 8 字节的差是路径算术，不是载荷回归。系数 4 是从数据点拟出来的
（尺只存了 `len(rest)` 没存原文），我**没有**直接数过请求体里跑名出现几次——这条要写成结论就只能写成"长度差
由跑名解释"，不能写成"我看见了 4 处"。

### 三、T1 注入阳性对照 v1：判作废，病在注入时刻

```
T1_CTRL|START 2026-09-26 23:45:47 dir=/Users/zifang/.cache/zbot-integrate/t1_tamper_262345
T1_CTRL|MD5_PRE classes=bb3ff2e6 snapshot=bb3ff2e6 src=bb3ff2e6
T1_CTRL|TAMPER +1byte classes=bf6d3919 len=53094
T1_CTRL|RUN_B rc=0 E2E|run=t1_tamper checks=32 pass=32 fail=0 llm_hits=1 serve_port=53713 result=OK
SERVED|status='HTTP/1.1 200 OK' served=53093B/bb3ff2e6 disk=53093B/bb3ff2e6 classpath=53093B/bb3ff2e6
T1_CTRL|RUN_B 其他红项（应只剩 T1）: 0 条红 / 红名=
T1_CTRL|MD5_THREE_WAY classes=bb3ff2e6b03ed524c92dd4d877adfe9b snapshot=bb3ff2e6b03ed524c92dd4d877adfe9b src=bb3ff2e6b03ed524c92dd4d877adfe9b same=YES
```

注入后我实测到的两条形迹可疑的读数（也正是判作废的依据）：
`target/classes/web/index.html` 在 23:45:49（注入后 2 s）自己变回 `53093` 字节、md5 回到 `bb3ff2e6`，
mtime 却是新的。原因在尺的开头：每一跑第一件事就是 `mvn -o -pl z-bot-core test-compile`（`p28_e2e.py:674`），
resources 插件把 `src/main/resources` 又拷了一遍 ⇒ **篡改被构建抹掉，从未进入伺服路径**。
所以 `RUN_B` 的 32/32 全绿不能读成"T1 没牙"，也不能读成"T1 抓到了篡改"——它是**零猎物**的一次跑。
还原步本身没问题（三方 md5 `same=YES`，长度回到 53093）。

v2 的改法（`~/.cache/zbot-integrate/t1_tamper_v2.sh`，读数待下一节）：先起这一跑，**等它自己打印
`BUILD|classpath_file=`**（这一行之后本跑再无 mvn）才注入那 1 字节；`HttpChannel.serveConsole()` 是
每次请求 `getResourceAsStream`（`HttpChannel.java:890-891`），所以注入必然读得到。还原参照仍是
注入前自己 cp 的那一份，且 `trap … EXIT` 保证半途退出也还原。

### 四、杠④ 与一条我自己留下的环境账

三跑全程 `~/.zbot` 三值不动（`dir=8 cfg=2dadaed0 db=690ddbc0`，`U1` 九时点同读数），真 key 只量长度（125）。
本节点掉一个**我自己**这一会话留下的孤儿：`pid 90799 ppid=1`，argv
`com.zifang.z.bot.ZBot serve --port 59384 --config-dir /Users/zifang/.cache/zbot-p28-browser/cfg`
（23:24:01 起，P28-lead-6 浏览器取证那一跑没收线）⇒ `kill` 后 `lsof` 复扫 0 个 LISTEN，
`~/.zbot` 三值复测不变。别的会话那 5 支 gateway/repl 仍只记账不杀。


## P28-lead-8 · 控制台接线的 JUnit 层守卫 + 杠② 量具搬进仓，在目标树重测（09-27 00:5x–01:05，主编亲测）

### 1. 量具身份（先钉"哪把尺量的哪棵树"）

旧台账是**写手树**的产物：`P28-lead-3` 那批由 `~/.cache/zbot-p28-lead/p28b_mutation.py` 生成，
而那支尺第 25 行硬 `WT = "/private/tmp/zbot-wt-p28"` —— 与 p25/p26/p27 三支同一个病（工单里记作"未跟踪 + 指错树"）。
写手树被扫掉后台账在目标树上没法重生成 ⇒ 本轮把尺搬进仓：`_doc/005_testing/acceptance/p28/p28_mutation.py`，
`REPO` 从 `__file__` 派生 + 双重目标树自证（"尺与被量的树同仓" + "那棵树真的是仓根"），读数行：

```
p28_mut_final_270057.log
TARGET|repo=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot head=10c74d8 dirty_lines=7
LOCK_ACQUIRED /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock
BACKUP _doc/005_testing/acceptance/p28/ROUTES.tsv md5=809b33e7 -> /Users/zifang/.cache/zbot-p28-mutation/mutbak/_doc__acceptance__p28__ROUTES.tsv
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java md5=20cc3f4a -> /Users/zifang/.cache/zbot-p28-mutation/mutbak/z-bot-core__src__main__java__com__zifang__z__bot__channel__HttpChannel.java
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/channel/TerminalChannel.java md5=10b4f9a4 -> /Users/zifang/.cache/zbot-p28-mutation/mutbak/z-bot-core__src__main__java__com__zifang__z__bot__channel__TerminalChannel.java
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java md5=7e4c33c5 -> /Users/zifang/.cache/zbot-p28-mutation/mutbak/z-bot-core__src__main__java__com__zifang__z__bot__ui__RawTerminalReader.java
BACKUP z-bot-core/src/main/resources/web/index.html md5=bb3ff2e6 -> /Users/zifang/.cache/zbot-p28-mutation/mutbak/z-bot-core__src__main__resources__web__index.html
RESTORED_ALL {'ROUTES.tsv': '809b33e7', 'HttpChannel.java': '20cc3f4a', 'TerminalChannel.java': '10b4f9a4', 'RawTerminalReader.java': '7e4c33c5', 'index.html': 'bb3ff2e6'}
LEDGER=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/_doc/005_testing/acceptance/p28/LEDGER.tsv mtime=01:00:36 harness_mtime=00:57:35
TALLY {'RED-OK': 13, 'SURVIVED': 1} TOTAL=14
```

### 2. 14 支逐支（`LEDGER.tsv` 字节，本轮 marker 分布 `{'RED-OK': 13, 'SURVIVED': 1} TOTAL=14`）

```
M1   RED-OK    摘掉 stty 子进程的 fd 0 继承       verdict raw=NONZERO_EXIT restore=DONE rc=0 wall=0.21
M2   RED-OK    把有界等待的时限抬成 Long.MAX        verdict raw=None restore=None rc=KILLED wall=40.14
M3   RED-OK    非零退出也报 DONE（判词揉成一团）        verdict raw=DONE restore=DONE rc=0 wall=0.00
M4   RED-OK    waitFor 返回真值时反判成超时         verdict raw=<null> restore=<null> rc=0 wall=0.11
M5   SURVIVED  【阳性对照·等价变异】isSttyAvailable verdict raw=DONE restore=DONE rc=0 wall=0.17
M6   RED-OK    摘掉台账的 404 门                mvn rc=1 ran=1 具名红=['[ERROR]   HttpRouteLedgerTest.unknownPathStillFourOhFourWithJsonErrorShape:176 expected:<404> but was:<405>']
M7   RED-OK    摘掉 405 的 Allow 头           mvn rc=1 ran=1 具名红=['[ERROR]   HttpRouteLedgerTest.unadvertisedMethodGetsFourOhFiveWithAllowHeader:186 expected:<GET, OPTIONS> but was:<null>']
M8   RED-OK    SSE 帧里裸换行不再转义              mvn rc=1 ran=9 具名红=['[ERROR]   HttpSseContractTest.newlineInsideModelTextIsEscapedAndDoesNotBreakFraming:147 正文里的裸换行必须被转义成 \\\\n，实到 event: final\\ndat
M9   RED-OK    台账文件与代码漂移一个字               mvn rc=1 ran=1 具名红=['[ERROR]   HttpRouteLedgerTest.routesTsvIsInSyncWithLedger:133 ROUTES.tsv 与 HttpChannel.routes() 不同源了。逐字节差异已写到 target/p28/ROUTES.t
M10  RED-OK    终端 /theme 派发分串漂移（台账说它有、代码里 mvn rc=1 ran=1 具名红=['[ERROR]   CommandSurfaceConsistencyTest.tuiHandlesExactlyTheChannelLocalSegment:220->assertSameNames:299 TerminalChannel 分支 ⇄ Com
M11  RED-OK    摘掉启动时的 loadCommands() 接线（0 mvn rc=1 ran=1 具名红=['[ERROR]   WebConsoleWiringTest.everyDeclaredConsoleFunctionHasACallSite:196 这些函数定义了却没有任何第二个名字出现点（当年 loadCommands/autoResize 就是这个形
M12  RED-OK    在真 newSession 之前塞一份同名空壳（09 mvn rc=1 ran=1 具名红=['[ERROR]   WebConsoleWiringTest.noFunctionIsDeclaredTwiceOnTheConsolePage:189 同名函数声明了两次 ⇒ 后一份静默赢、前一份变死代码，而界面照常渲染。重复声明=[newSession]
M13  RED-OK    把输入框 autoResize 的监听摘掉（09-2 mvn rc=1 ran=1 具名红=['[ERROR]   WebConsoleWiringTest.everyDeclaredConsoleFunctionHasACallSite:196 这些函数定义了却没有任何第二个名字出现点（当年 loadCommands/autoResize 就是这个形
M14  RED-OK    内联处理器指向一个不存在的函数            mvn rc=1 ran=1 具名红=['[ERROR]   WebConsoleWiringTest.everyInlineHandlerNamesAnExistingConsoleFunction:203 内联 on* 处理器点名了不存在的函数 ⇒ 点下去就是 ReferenceError。重复
```

要点三条，都是可复核的：
- **新增 M11–M14 = 控制台页"接线"面的四支阳性对照**，全部 RED-OK，且各自点名的方法就是
  `WebConsoleWiringTest` 里对应那条（M11/M13 打孤儿、M12 打同名重复声明、M14 打内联处理器指向不存在的函数）。
  这三类正是 P28-lead-6 在浏览器层实测出的病灶形状。
- **M6/M7 从假 SURVIVED 翻成 RED-OK**：旧选择器点名的 `HttpRouteShapeTest#unknownPath…` /
  `#unadvertisedMethodGets405WithAllowHeader` 在合并树上**不存在**（前者实际住在 `HttpRouteLedgerTest`，
  后者真名是 `…FourOhFive…`）。`-Dtest=` 匹配不到 ⇒ `mvn rc=0`、0.6 s 收工，旧版把"没跑"记成"检不出"。
  现在加了 `ran==0 ⇒ NO-RUN` 分档（读 surefire 的 `-- in <类>` 汇总行，读不到就不许出检出结论）+ 修选择器，
  两支各自红在所点名的方法上（`expected:<404> but was:<405>`、`Allow` 头变 `null`）。
- **M10 的锚点随 P19 单源化搬走了**：旧锚 `'"/status", "/theme", …"'` 在 `RawTerminalReader.java` 出现 **0 次**
  ⇒ 旧版只能记 INJECTION_NOT_APPLIED。重锚到 `TerminalChannel` 的 `/theme` 派发分串后判 RED-OK，
  红在 `CommandSurfaceConsistencyTest.tuiHandlesExactlyTheChannelLocalSegment`（分支名 ⇄ 台账不同名）。

### 3. 与 `P28-lead-6` 的 T9 是什么关系（重叠要说清，别装作是新增覆盖）

- T9 住在 `p28_e2e.py`，量的是**盘上 `src` 那份 HTML**，要起真 serve、整跑约 10 分钟，只在杠③ 里跑。
- `WebConsoleWiringTest` 住在 `z-bot-core/src/test`，量的是 **classpath 里真被 serve 的那份字节**
  （`HttpChannel.class.getResourceAsStream("/web/index.html")`），随 `mvn test` 每次杠① 都跑。
- 两条判据（同名重复、声明了没人接）**故意重叠** —— 一份在 CI、一份在真进程；JS 逻辑没法跨语言共用，
  所以这里必然有两份实现，重叠是设计而不是疏忽。**只有第三条（内联 `on*` 点名的函数必须存在）是 JUnit 层新增的**。
- 判据自身带四份合成页做牙口对照（`auditToolRejectsTheThreeHistoricalDefects`）：三种病灶各一支，
  外加"健康页不许被冤枉"与"只写在注释里的接线不许让尺闭嘴"两支阴性/边界对照。

### 4. 杠①（`7dc51ba`，`rm -rf z-bot-core/target/surefire-reports && mvn -o test` ×3 串行）

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270101 start=2026-09-27 01:01:19 HEAD=7dc51ba
BAR1_TREE|dirty_total=3 dirty_tracked=1 src_md5_delegate=fd1652b3
BAR1_SRC_MD5_START|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
ROUND_START|r=1 ts=2026-09-27 01:01:20 bar4=8/2dadaed0/690ddbc0
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1158, Failures: 0, Errors: 0, Skipped: 0] class_sum=1158 f=0 e=0 s=0 files=108 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:02:44
ROUND_START|r=2 ts=2026-09-27 01:02:44 bar4=8/2dadaed0/690ddbc0
ROUND|r=2 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1158, Failures: 0, Errors: 0, Skipped: 0] class_sum=1158 f=0 e=0 s=0 files=108 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:04:00
ROUND_START|r=3 ts=2026-09-27 01:04:00 bar4=8/2dadaed0/690ddbc0
ROUND|r=3 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1158, Failures: 0, Errors: 0, Skipped: 0] class_sum=1158 f=0 e=0 s=0 files=108 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:05:09
BAR1_SRC_MD5_END|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
BAR1_CLASSES|src=bb3ff2e6 classes=bb3ff2e6 src_len=53093 cls_len=53093
BAR1_DONE|end=2026-09-27 01:05:09 dir=/Users/zifang/.cache/zbot-integrate/bar1_270101 HEAD=7dc51ba dirty_tracked=1 src_md5_delegate=fd1652b3
```

`1154 → 1158` 恰好等于新增的 4 条 `@Test`，`files=107 → 108` 恰好等于新增的那一个测试文件；
三轮 `F=E=S=0`、`socket_hits=0`、`build_success_rows=1`。跑期间唯一受跟踪的在途改动是
`_doc/001_arch/hermes-roadmap.md`（文档，不参与编译），读数行里 `dirty_tracked=1` 就是它。

### 5. 杠④ 与还原

- `index.html` 本轮被注入过 4 次，收尾 `RESTORED_ALL` 打印 `bb3ff2e6` == 本轮开工前的 md5；
  `TerminalChannel.java 10b4f9a4`、`HttpChannel.java 20cc3f4a`、`RawTerminalReader.java 7e4c33c5`、
  `ROUTES.tsv 809b33e7` 同样逐支回到本次运行前 `cp` 的那份字节（还原只从副本 `cp`，未用 `git checkout/restore`）。
- 共享锁 `zbot-mutlock` 由尺自己 `LOCK_UN + close(fd)`，未 `unlink`；杠② 全程与杠③ 串行走（没有并发）。
- 杠④ 在杠① 的 6 个采样点逐格 `8 / 2dadaed0 / 690ddbc0`；真 minimax key 全程只量长度（125），值未被读取。

_再生成本节两支读数：`python3 _doc/005_testing/acceptance/p28/p28_mutation.py`（要抢锁）与 `bash ~/.cache/zbot-integrate/bar1_x3.sh`；原始日志 `~/.cache/zbot-integrate/p28_mut_final_270057.log`、`bar1_7dc51ba.log`。_


## P28-lead-9 · T1 注入阳性对照 v2：这次是真有牙（09-27 01:11:10–01:35:16，主编亲测）

### 1. 量具身份

- 驱动 `~/.cache/zbot-integrate/t1_tamper_v2.sh`（74 行，md5 前缀 `8d41fdc1`，写于 00:32），
  输出目录 `~/.cache/zbot-integrate/t1_tamper_v2_270111/`（`run_B.log`、`run_D.log`、注入前副本 `index.classes.pre`）。
- 被调的 E2E 尺仍是仓内 `_doc/005_testing/acceptance/p28/p28_e2e.py`（`2e8134bb`，与 §P28-lead-8 同一份字节）。
- 注入方式（v1 病灶的修法）：先起跑，**等本跑自己打印 `BUILD|classpath_file=`** 之后
  （这一行之后本跑再无 mvn，不可能再把资源重拷一遍）才向
  `z-bot-core/target/classes/web/index.html` 尾部追加 1 字节；还原只从注入前 `cp` 的那份字节 `cp` 回去，
  `trap … EXIT` 保证半途退出也还原。全程 `--config-dir` 临时根 + `stub-key-not-real`，`llm_hits` 是打到我自写假 LLM 的次数。

### 2. 外部日志逐字（`t1_tamper_v2_outer.log`，17 行）

```
T1V2|START 2026-09-27 01:11:10 dir=/Users/zifang/.cache/zbot-integrate/t1_tamper_v2_270111
T1V2|MD5_PRE classes=bb3ff2e6 snapshot=bb3ff2e6 src=bb3ff2e6
T1V2|LAUNCHED pid=19666
T1V2|TAMPER_AFTER_BUILD 2026-09-27 01:11:15 classes=bf6d3919 len=53094
T1V2|RUN_B rc=1 E2E|run=t1_tamper_v2 checks=32 pass=31 fail=1 llm_hits=1 serve_port=55115 result=HAS_FAILURE
SERVED|status='HTTP/1.1 200 OK' served=53094B/bf6d3919 disk=53093B/bb3ff2e6 classpath=53094B/bf6d3919
T1V2|红项条数=1 红名=T1_served_console_bytes_equal_disk_html
T1V2|注入被构建抹掉的反证（v1 病灶）：classes mtime=01:11:15 注入时刻见上一行
T1V2|ORDER_PROOF served=bf6d3919 tampered=bf6d3919 disk=bb3ff2e6 prey_reached_measured_path=YES
T1V2|VERDICT t1_red=YES prey_hit=YES ⇒ 有牙（篡改进了 served，且 T1 点名红了）
T1V2|RESTORE classes=bb3ff2e6 len=53093
T1V2|MD5_THREE_WAY classes=bb3ff2e6b03ed524c92dd4d877adfe9b snapshot=bb3ff2e6b03ed524c92dd4d877adfe9b src=bb3ff2e6b03ed524c92dd4d877adfe9b same=YES
T1V2|RUN_D rc=0 E2E|run=t1_restored_v2 checks=32 pass=32 fail=0 llm_hits=1 serve_port=61395 result=OK
SERVED|status='HTTP/1.1 200 OK' served=53093B/bb3ff2e6 disk=53093B/bb3ff2e6 classpath=53093B/bb3ff2e6
T1V2|DONE 2026-09-27 01:35:16
```

### 3. 牙口三段论，每段一条独立读数

1. **猎物确实进了被测量那条路** —— `ORDER_PROOF served=bf6d3919 tampered=bf6d3919 disk=bb3ff2e6`：
   HTTP 响应体的 md5 等于**被篡改后**的那份，而不等于盘上 `src/main/resources` 那份。这一步单独成立，
   与 T1 红不红无关（所以 v1 那种"注入被构建抹掉"的跑会在这里就露出来：它 `prey_reached_measured_path=NO`）。
2. **尺点名咬住，且只咬这一处** —— `RUN_B rc=1 … checks=32 pass=31 fail=1`，`红项条数=1 红名=T1_served_console_bytes_equal_disk_html`。
   `FAILED_CHECK|T1_…|… served=53094B md5=bf6d3919 | disk=53093B md5=bb3ff2e6 (…) | classpath=53094B md5=bf6d3919 (…)`
   —— 消息里三方尺寸/哈希全打出来，红了能直接判断是哪一层分叉（这一条是"被 serve 的字节 ≠ 盘上那份"，不是"服务挂了"）。
   其余 31 条不受影响，说明这 1 字节没顺手把别的判据撞红（也就不是"整跑崩了所以全红"那种假阳性）。
3. **还原对照** —— `RUN_D rc=0 … 32 pass=32 fail=0`，且 `MD5_THREE_WAY … same=YES`、`RESTORE … len=53093`：
   同一棵树、同一份尺，只有"注入 / 不注入"这一个变量，判据跟着从红回绿。

### 4. 与 v1 的对比（为什么上一节判作废是对的）

| | 注入时刻 | served | disk | 结果 | 判读 |
|---|---|---|---|---|---|
| v1 `t1_tamper_262345` | E2E 起跑**之前** | `53093B/bb3ff2e6` | `53093B/bb3ff2e6` | 32/32 全绿 | 零猎物（`test-compile` 把篡改重拷抹掉） |
| v2 `t1_tamper_v2_270111` | `BUILD\|classpath_file=` 之后 | `53094B/bf6d3919` | `53093B/bb3ff2e6` | 31  pass / 1 红（T1） | 有牙 |

v1 那一行 `SERVED|… served=53093B/bb3ff2e6` 与盘上完全一致，正是"猎物根本没上场"的形状；
所以 §P28-lead-7·三 的结论（"32/32 全绿既不能读成没牙、也不能读成抓到篡改"）成立，v2 才是它等的那一次。

### 5. 这次证到了什么、没证到什么

- **证到**：`HttpChannel.serveConsole()` 每次请求 `getResourceAsStream` 读的是 classpath 上那份，
  且 T1 那条判据比较的是"HTTP 响应体 vs 盘上 src"，两者一旦分叉它必红；
  尺对"被 serve 的字节"是逐字节的，不是只看状态码或长度。
- **没证到**：① 内容正确性（T1 只比 md5/长度， served 里若是"合法但错版"的 HTML 它不管，那是文档派生尺那几层的活）；
  ② 资源过滤链路里**其它**资源（只动了 `web/index.html`）；③ 浏览器渲染层（DOM 拿到之后）—— 那仍是
  `P28-lead-6` 记的未完项（`/help` 实输出的浏览器层对照）。

### 6. 树身份与归属（这轮读数属于哪棵树）

跑窗 01:11:10–01:35:16 之内工作树被提交过两次（`5aeacda` 01:13:16、`443744b` 01:20:55），所以"跑时 HEAD"不是一个值。
按字节把归属钉死：`git diff --name-only 7dc51ba..6f6b21f` 只有 6 个文件

```
README.md
_doc/005_testing/acceptance/p27/p27_mutation.py
_doc/005_testing/acceptance/p28/EVIDENCE.md
_doc/005_testing/acceptance/p28/p28_mutation.py
_doc/001_arch/hermes-roadmap.md
z-bot-core/src/test/java/com/zifang/z/bot/ReadmeClaimsTest.java
```

`z-bot-core/src/main` 那一段是 **0 个文件**，被 serve 的那条路上两个关键点逐字节对得上：
`index.html` 在 `7dc51ba / 5aeacda / 443744b / b8b1918 / f4ad57d / 6f6b21f` 六个提交上都是 `bb3ff2e6`；
`HttpChannel.java` 在 `7dc51ba` 与 `6f6b21f` 上都是 `20cc3f4a`。⇒ **T1 v2 量的是 `serveConsole` 的实现，
这份实现在那六个提交之间没动过一个字节**，所以这轮的有牙结论对当前树（`6f6b21f`）依然成立；
变的只有测试/文档/README。两轮 `BUILD|classpath_file=…/cp.txt size=3044 rc=0` 尺寸一致，也说明两边喂给 JVM 的
classpath 是同一份产物。

### 7. 还原与杠④（诚实记账）

- 还原：`RESTORE classes=bb3ff2e6 len=53093` + `MD5_THREE_WAY same=YES`（全 md5 `bb3ff2e6b03ed524c92dd4d877adfe9b`），
  未用任何 `git checkout/restore`。盘上那份此后两次被独立量具复核为 `bb3ff2e6`：
  `bar1_7dc51ba.log` 的 `BAR1_SRC_MD5_START|index.html=bb3ff2e6`（01:01，跑在 T1 之前）与
  `bar1_6f6b21f.log` 的同一行（01:53:36，跑在 T1 之后）⇒ 篡改没有残留到工作树。
- 收线：`lsof` 复扫 55115 / 61395 两个端口都不在 LISTEN，`ps` 里 `java … ZBot` 一条不剩。
  （第一次探活我用 `ps | grep "[j]ar serve\|[Z]Bot serve"` 匹到了两条，那是**我自己那条 grep 命令行**，
  换成 `grep -E "java .*ZBot" | grep -v grep` 后为 0。）
- **杠④：这一支驱动自己没有采样 `~/.zbot`** —— 它的三值证据是借来的：由杠① 那 6 个采样点
  （`bar1_x3.sh` 每轮 `ROUND_START` 各打一次）与 `p28_e2e.py` 内部的三个时点承担，逐格 `8 / 2dadaed0 / 690ddbc0`；
  真 minimax key 全程只量长度（125），值未被读取。这一条要写清，别把"整轮杠④ 绿"记成"这支驱动量过杠④"。

_再生成本节读数：`bash ~/.cache/zbot-integrate/t1_tamper_v2.sh`（它会注入 `target/classes`，跑完自还原；与任何 mvn/杠② 串行）；
原始日志 `~/.cache/zbot-integrate/t1_tamper_v2_outer.log`、`~/.cache/zbot-integrate/t1_tamper_v2_270111/run_{B,D}.log`。_

## P28-lead-10 · 杠③ 在合并树 `0b47cc8` 上 ×3 真进程整跑 + 一把会数出"4"的跨轮尺被修掉（09-27 03:21:26–03:57:32，主编亲测）

驱动 `~/.cache/zbot-integrate/bar3_x3_v2.sh`，日志 `~/.cache/zbot-integrate/bar3_0b47cc8.log`（22 行，逐字）：

```
BAR3_SERIES|start=2026-09-27 03:21:26 dir=/Users/zifang/.cache/zbot-integrate/bar3_x3_270321 HEAD=0b47cc8 harness_md5=2e8134bb src_md5=bb3ff2e6 tree_dirty=2
BAR3_SRC_MD5_START|index.html=bb3ff2e6b03ed524c92dd4d877adfe9b harness=2e8134bbcb69b0e7bc615c9124d72b8a
ROUND_START|bar3_x3_r1|2026-09-27 03:21:26|bar4=8/2dadaed0/690ddbc0/key_len_only=125
ROUND|bar3_x3_r1|rc=0|2026-09-27 03:33:28|E2E|run=bar3_x3_r1 checks=32 pass=32 fail=0 llm_hits=1 serve_port=54102 result=OK
ROUND|bar3_x3_r1|pass=32 fail=0|bar4=8/2dadaed0/690ddbc0/key_len_only=125
ROUND|bar3_x3_r1|SERVED|status='HTTP/1.1 200 OK' served=53093B/bb3ff2e6 disk=53093B/bb3ff2e6 classpath=53093B/bb3ff2e6
ROUND_START|bar3_x3_r2|2026-09-27 03:33:28|bar4=8/2dadaed0/690ddbc0/key_len_only=125
ROUND|bar3_x3_r2|rc=0|2026-09-27 03:45:30|E2E|run=bar3_x3_r2 checks=32 pass=32 fail=0 llm_hits=1 serve_port=59626 result=OK
ROUND|bar3_x3_r2|pass=32 fail=0|bar4=8/2dadaed0/690ddbc0/key_len_only=125
ROUND|bar3_x3_r2|SERVED|status='HTTP/1.1 200 OK' served=53093B/bb3ff2e6 disk=53093B/bb3ff2e6 classpath=53093B/bb3ff2e6
ROUND_START|bar3_x3_r3|2026-09-27 03:45:30|bar4=8/2dadaed0/690ddbc0/key_len_only=125
ROUND|bar3_x3_r3|rc=0|2026-09-27 03:57:32|E2E|run=bar3_x3_r3 checks=32 pass=32 fail=0 llm_hits=1 serve_port=55054 result=OK
ROUND|bar3_x3_r3|pass=32 fail=0|bar4=8/2dadaed0/690ddbc0/key_len_only=125
ROUND|bar3_x3_r3|SERVED|status='HTTP/1.1 200 OK' served=53093B/bb3ff2e6 disk=53093B/bb3ff2e6 classpath=53093B/bb3ff2e6
BAR3_SRC_MD5_END|index.html=bb3ff2e6b03ed524c92dd4d877adfe9b harness=2e8134bbcb69b0e7bc615c9124d72b8a
ROUNDNAMESET|p28_bar3_x3_r1.log|n=32|c60453c818ed7ad2568d4c3a88138022
ROUNDNAMESET|p28_bar3_x3_r2.log|n=32|c60453c818ed7ad2568d4c3a88138022
ROUNDNAMESET|p28_bar3_x3_r3.log|n=32|c60453c818ed7ad2568d4c3a88138022
CROSS|check_names_across_rounds=8abab1541f5f849c0fb2108ce57959ec
CROSS|e2e_verdict=1 unique_distinct_readings(1=三轮逐字相同)
CROSS_CTRL|注入一轮 fail=1 应当=2，实测=2
BAR3_DONE|end=2026-09-27 03:57:32 dir=/Users/zifang/.cache/zbot-integrate/bar3_x3_270321 HEAD=0b47cc8 harness_md5=2e8134bb src_md5=bb3ff2e6 tree_dirty=2
```

- **量的是当前树**：驱动开头钉 `HEAD=0b47cc8 harness_md5=2e8134bb src_md5=bb3ff2e6`，`BAR3_SRC_MD5_END` 与 START 逐字节相同
  ⇒ 这 36 m 06 s 里 `index.html` 与 `p28_e2e.py` 都没被碰过（`tree_dirty=2` 从头到尾是那两个未跟踪的 `__pycache__/*.pyc`，
  受跟踪件为 0）；每轮 `SERVED` 三方（served / disk / classpath）都是 `53093B/bb3ff2e6`。
- 判据分母：三轮都是 `checks=32 pass=32 fail=0`（`pass=`/`fail=` 由驱动现数 `^CHECK|.* PASS/FAIL`，不是抄判决行），
  `llm_hits=1`（打的是自写假 LLM，`Authorization` 里只有 `Bearer stub-key-not-real`）；
  三轮 `CHECK` 名字集合的 md5 都是 `c60453c8…`（`n=32` 三行）⇒ 每轮跑的是同一套 32 项。
- **跨轮一致性两把尺**：`CROSS|check_names_across_rounds=8abab154…`（三轮名字拼起来一把哈希）一轮都不许变；
  `CROSS|e2e_verdict=1` = 三轮判决行去标识后**逐字相同**。
- **旧尺在这里会数出 4，而 4 不是"三轮不一致"**：它把每轮驱动开跑时那行
  `E2E|run=… out=~/.cache/…/bar3_x3_rN` 头行一起算进比对，而 `sed` 只剥 `run=` 与 `serve_port=`，
  `out=` 里的 `rN` 每轮不同 ⇒ 三轮判决完全相同也数出 4。改成只取含 `checks=` 的判决行，并配**阳性对照**
  （人造一轮把 `fail=0` 改 `fail=1`，必须数出 2）。两批各量一遍，读数在
  `~/.cache/zbot-integrate/cross_gauge_recheck_270207.log`（`bash ~/.cache/zbot-integrate/cross_gauge_recheck.sh <目录>` 可复算）：
  旧批 `bar3_x3_270207/` 上 `OLD|count=4`、`FIXED|count=1`、`CTRL|expect=2 got=2`、`CTRL_OLD|同一份对照用旧剥法=5`；
  本批由驱动自己收口：`CROSS|e2e_verdict=1` + `CROSS_CTRL|注入一轮 fail=1 应当=2，实测=2`。
  **教训**：任何"N 轮相同吗"的尺都要问一句"被比对的字段里有没有每轮必然漂移的身份项"，
  并且要有一支"故意不同"的对照证明它数得出 >1；只报 1 而不报对照，读数没有意义。
  这与 `P28-lead-7` 是同一个病的第二次现形（那次我手算三跑判决行 md5 得到三个互不相同的哈希，成因也是行内嵌着墙钟/端口/随机 token）
  ——⇒ 同族的尺应当合并：那条链已产出 `CROSS|three_round_verdict_identical=YES`，本节的 `e2e_verdict` 只该作为它的独立复核，不该各写一套剥法。
- 杠④ 与真 profile：每轮首尾各一条 `bar4=8/2dadaed0/690ddbc0/key_len_only=125`（每轮首尾各一次）；`U1`（三时点不变）/
  `U2`（真 key 未被读、线上只有 stub）/ `U3`（serve 子进程被回收）/ `U4`（用的是临时 profile）四支 PASS 原文见上块。
- ~~这一节**没有**证的：浏览器渲染层（DOM 拿到之后）的 `/help` 实输出对照 —— 仍是未完项，见 roadmap §8.13.6。~~
  → 由 **`P28-lead-11`** 补上（`VERDICT|same=YES`，且它自己的边界写在那一节末段）。

## P28-lead-11 · 浏览器层 `/help` 实输出对照：DOM 那段文字 == `/api/commands` 的 web 段（09-27 04:16:01–04:18:47，主编亲测）

补的是 `P28-lead-10` 末行那条未完项，也是 roadmap §8.13.6 剩下的"浏览器渲染层未做"。
量具在 `~/.cache/zbot-help-browser/`（`serve_up.py` 起真 serve + 落 `commands.json`、`dom_compare.py` 判等、`kill.py` 收尾），
不在仓内 ⇒ 下面每条读数都原样贴出，复算步骤写在末段。

**真进程侧**（`ZBOT_HOME` 指向临时根、config 只有 `stub-key-not-real`；端口由 `bind(0)` 取，不猜固定号）：

```
BAR4|tag=before dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
PORT|chosen=64461
SERVE|pid=69107 port=64461
COMMANDS|rows=29 web_rows=6 web_names=["/status", "/confirm", "/help", "/new", "/clear", "/tools"]
CTRL|web_equals_all=False (期望 False；True 就说明这项对照没有区分力)
READY|url=http://127.0.0.1:64461/
```

`/api/commands` 判的是 **200 + 非空数组 + 行字段含 `name`/`endpoints`** 三件事同时成立才认 UP（`curl` 对 404 也返回 0，
"端口有人听"不等于"是我的服务"）。

**浏览器侧**：真页面 `http://127.0.0.1:64461/` 加载后先读一次结构 —— `title="z-bot 控制台"`、
`WEB_COMMANDS.length=6`、`#quick-commands` 渲出的正是那 6 条 ⇒ `loadCommands()` 的 fetch 真的接到了这棵树。
然后在 `#msg-input` 里填入 `/help` 并触发 Enter，页面自己那条 `keydown` 监听（`index.html:1538`）跑起来，
DOM 里出现的那条 assistant 气泡逐字：

```
📋 命令列表（来自 /api/commands 的 web 段）：
  /status  — 显示当前状态（模型 / 工具 / 技能）
  /confirm — 确认上次等待中的危险命令
  /help    — 显示此帮助
  /new     — 新建会话
  /clear   — 清空当前会话记忆
  /tools   — 列出已注册工具
```

**判等尺的读数**（`dom_compare.py`，它按 `index.html:1196` **同一条**过滤规则从 `commands.json` 现算期望集，
不是我另抄一份）：

```
A|help_blocks=1 url=http://127.0.0.1:64461/
B|dom=6 api_web_no_alias=6 api_web_with_alias=6 api_all_no_alias=26
B|dom=["/status", "/confirm", "/help", "/new", "/clear", "/tools"]
B|api=["/status", "/confirm", "/help", "/new", "/clear", "/tools"]
B|missing_in_dom=[] extra_in_dom=[] order_same=True alias_rows_in_web=0
C|web_equals_all=False (期望 False；True ⇒ 这项对照无区分力)
VERDICT|same=YES
```

- **A 段防"空跑"**：先要求那条 `/help` 气泡**在场**，否则"没有差异"会因为页面压根没渲染而成立。
- **C 段防"过定"**：web 段（6）≠ 全量（26）。另两把运行时对照同样落在"必须 False"那一侧：
  `CTRL_RUNTIME|dom_vs_all: dom=6 all=26 equal=False`、`dom_vs_tui: tui=26 equal=False` ⇒ DOM 渲染的确实是被筛过的那一段，
  不是整张表（若相等，本节这句话就没有内容）。
- **尺自己先被验过才敢用**：`dom_compare.py` 拿四份合成 DOM 自证 —— 齐 ⇒ `same=YES`、少一条 ⇒ `NO`、
  多一条（`/exit`）⇒ `NO`、整块缺失 ⇒ `NO_HELP_BLOCK`。**第一遍就把一个真 bug 抓出来了**：
  我最初用 `line[3:]` 剥 `  /`，把前导斜杠一起吃掉 ⇒ "齐"那份也报 `NO`。修成 `line[2:]` 后四份判定才各自对。
  （别名行的坑是改尺时顺手避掉的：`serve_up.py` 的 `web_names` 只按"endpoints 含 web"滤，会连别名行一起留着，
  而 DOM 按 `!aliasOf` 滤 ⇒ 直接拿它当期望集会造出假不一致，所以 `dom_compare` 自己按 DOM 规则重算并打印
  `api_web_with_alias` 与 `api_web_no_alias` 两个数，二者相等（这里都是 6）才说明这次没有别名混进来。）

**这一节没有证的（说清边界）**：
触发 Enter 用的不是 OS 级按键。`browser-use` 的点击/按键在本机报
`NATIVE_BROWSER_VIEWPORT_UNAVAILABLE … viewport=0x0, visible=false, visibilityState=hidden`
⇒ 我改用页面内 `dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', bubbles:true, cancelable:true}))`，
并且**以"handler 真跑了"为读数**：`dispatchEvent` 返回 `false`（`index.html:1540` 的 `e.preventDefault()` 生效）
且气泡随后出现在 DOM 里。所以这节证到的是"**生产页的 keydown 监听 → `sendMessage()` → `handleSlash('/help')` →
`appendMsg` → 文本进 DOM**"这条真链路，且它渲染的内容等于 API 的 web 段；
**没有**证：物理按键/窗口焦点行为、视觉样式与换行布局（`innerText` 把 `<br>` 还原成 `\n` 就被我用文本比掉了）、
以及"窗口可见时点击路径同不同"。要把这三样补上，需要在浏览器面板真在前台时重跑一遍（复算：
`python3 serve_up.py` → 页面内 `fill` + 真键盘 Enter → `python3 dom_compare.py dom_capture.json` → `python3 kill.py`）。

**收尾**（`kill.py`，只杀自己起的那个 pid，且先证明它是本棒起的 serve）：

```
KILL_TARGET|pid=69107 port=64461 lstart=Sun Sep 27 04:16:01 2026     /usr/bin/java -cp …/z-bot/z-bot-core/t
KILLED|pid_gone=True listener_on_port=''
BAR4|tag=after dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125
BAR4_ASSERT|cfg_same=True db_same=True (都该 True；False⇒这轮 E2E 写花了真 profile)
```

⇒ 真 `~/.zbot` 在这一整轮里三值未变，key 只被量长度；serve 进程回收、端口无残留 listener。
