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
