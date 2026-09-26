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

之后所有断言的分母 = `HttpChannel.routes()`（生产代码里的单源表）= 上面 22 条 = `_doc/acceptance/p28/ROUTES.tsv`。三者由 §1 的漂移卫兵钉死，不许任一方单独改。

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
2. `routesTsvIsInSyncWithLedger` —— 提交的 `_doc/acceptance/p28/ROUTES.tsv` 与 `routes()` 渲染结果
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
| 前棒未提交 **3** 个路径 | `git status --porcelain` 出 **8** 行：`channel/HttpChannel.java`、`ui/RawTerminalReader.java`、`_doc/acceptance/p28/{EVIDENCE.md,ROUTES.tsv,HttpRouteLedgerTest.java,HttpRouteShapeTest.java,HttpSseContractTest.java,P28HttpFixture.java}` | 8 个全按显式路径提交：`311b970`（7 个）+ `406a542`（`web/index.html` 单独封存，P19 的地盘） |
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
`_doc/acceptance/p28/LEDGER.tsv`（mtime **19:26:38**，晚于 harness）；台账字段
`id/name/file/gate/marker/evidence/bytecode_proof`，记号只用了允许集里的两种。闸门逐字：

```
LOCK_ACQUIRED /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock
BACKUP _doc/acceptance/p28/ROUTES.tsv md5=1ceb6af8 -> .../mutbak/_doc__acceptance__p28__ROUTES.tsv
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/channel/HttpChannel.java md5=e4bd2a3a -> ...
BACKUP z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java md5=9c6683ff -> ...
RESTORED_ALL {'ROUTES.tsv': '1ceb6af8', 'HttpChannel.java': 'e4bd2a3a', 'RawTerminalReader.java': '9c6683ff'}
LEDGER=/private/tmp/zbot-wt-p28/_doc/acceptance/p28/LEDGER.tsv mtime=19:26:38 harness_mtime=19:24:20
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
  未改 `_doc/hermes-roadmap.md`；未改 `slash/SlashRegistry.java`、`web/index.html`。
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
  `_doc/acceptance/p16/out/runtime/A-profile-r1`、`/tmp/p17-e2e-*`、`zbot-p18-e2e-*`、p16 profile）、
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

- 提交树 `696c632`；本轮在途未提交的项：`M _doc/acceptance/p28/__pycache__/p28_e2e.cpython-314.pyc`, `M _doc/acceptance/p28/p28_e2e.py`（四杠读数按**盘面字节**记账，md5 见下）。
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

