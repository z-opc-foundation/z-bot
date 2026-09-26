# P27 委托面对齐 —— EVIDENCE

STATUS: 进行中（§0 已实测）

写域：`z-bot-core/src/main/java/com/zifang/z/bot/delegate/**`、`z-bot-core/src/test/java/com/zifang/z/bot/delegate/**`、`_doc/acceptance/p27/**`、roadmap 自己那一行。
禁止改动：`agent/BotAgent.java`、`config/BotConfig.java`、`session/**`（p14/p23/p26 在写）。

## §0 第 0 步实测（本棒 2026-09-26 17:01 +0800 复算；工单读数并排列出）

工作树 `/private/tmp/zbot-wt-p27`，分支 `w11-p27`，`HEAD = 53222e1`（与工单基线一致）。

| 项 | 工单读数 | 本棒实测 | 裁定 |
|---|---|---|---|
| z-bot 现状体量 | `DelegateManager.java` 295 行 / 1 文件 | `295 z-bot-core/.../delegate/DelegateManager.java`，同目录只有这一个文件 | 一致 |
| `CHILD_BUDGET_FRACTION` | `= 0.25` @ :40 | `:40 public static final double CHILD_BUDGET_FRACTION = 0.25;` | 一致 |
| 公开面 | `attach(71)/delegateTool(76)/stopChildren(135)/inFlightCount(145)/submitAsync(176)/describeAsync(229)/asyncResult(243)` | 同一批行号逐条对上（另有构造器 `:60`、包级字段 `lastChild :52`） | 一致 |
| **测试面** | **0 支** | **5 支**：`git grep -c '@Test' HEAD -- 'z-bot-core/src/test/java/com/zifang/z/bot/delegate'` ⇒ `HEAD:.../delegate/DelegateTaskTest.java:5` | **推翻工单**：测试面不是 0；但 5 支全盯"子代理构建/深度剥离/预算裁切/中断入口"，**盯投递上限与台账落盘的是 0 支** |
| 全量 `@Test` 基线 | 735 / 71 文件 | `git grep -c '@Test' HEAD -- 'z-bot-core/src/test'` ⇒ `tests=735 files=71`（全仓含 z-bot-app 侧另算：`tests=796 files=81`） | 一致 |
| 接线面 | 只有 `agent/BotAgent.java`：`:157` 字段、`:214` `attach`、`:1854` 构造、`:1154` getter | `git grep -n 'DelegateManager' HEAD -- '*.java'` ⇒ BotAgent 5 处（23 import / 157 / 1154 / 1605 / 1854）+ 自身文件 3 处；`delegation.attach(this)` 实测 `:214`，`toolkit.register(delegation.delegateTool())` 实测 `:1855` | 一致 |
| hermes `tools/delegate_tool.py` | 3,655 行 / **63 个 def** / `class DelegateEvent` @ :624 | `wc -l` = **3655**；`class DelegateEvent(str, enum.Enum)` @ **:624**；def 计数 = **61**（`grep -cE '(^\|[[:space:]])def '`） | 行数/枚举一致；**def 数推翻工单：63 → 实测 61** |
| hermes `tools/async_delegation.py` | 990 行、`_MAX_DELIVERY_ATTEMPTS = 8` @ :84、claim/投递重试 @ :358/:364 | `wc -l` = **990**；`_MAX_DELIVERY_ATTEMPTS = 8` @ **:84**；上限取证的两条 SQL 在 **:354-:364**（`delivery_state='dropped' ... delivery_attempts>=?`） | 一致（工单只给了 :358/:364 两个参数行，真正的置 dropped 语句起于 :354） |
| hermes `tools/delegation_live_log.py` | 424 行、`LIVE_RETENTION_DAYS = 7` @ :44、`LiveTranscriptWriter` @ :112、`prune_stale_live_dirs` @ :404 | 全对上：424 / `:44` / `:112` / `:404` | 一致 |
| 三处合计 | 5,069 行 | `wc -l` 三文件 total = **5069** | 一致 |
| 她谁在用 delegate | "10 处引用" | `grep -rln 'delegate_tool\|async_delegation' ~/.hermes/hermes-agent --include='*.py' \| wc -l` = **55**（工单的 10 是 `\| head` 截断所致） | **推翻工单**：55 文件引用（其中 `tests/` 占 9+） |
| `~/.zbot` 三时点首测 | `8 / 2dadaed0 / 690ddbc0` | `ls -A ~/.zbot \| wc -l` = **8**；config md5 前 8 = **2dadaed0**；state.db 前 8 = **690ddbc0**；`awk` 量 key 长度 = 125（未读取内容） | 一致 |

**§0 结论（可达性实量，回答工单靶子四的怀疑）**：

```
$ grep -rn 'CHILD_BUDGET_FRACTION\|maxDepth' z-bot-core/src/main/java/
z-bot-core/.../delegate/DelegateManager.java:40   (声明)
z-bot-core/.../delegate/DelegateManager.java:47   private final int maxDepth;
z-bot-core/.../delegate/DelegateManager.java:61   构造器形参
z-bot-core/.../delegate/DelegateManager.java:67   赋值
z-bot-core/.../delegate/DelegateManager.java:89   if (depth + 1 > maxDepth)   ← delegateTool 拒委托
z-bot-core/.../delegate/DelegateManager.java:90   ToolResult.error(...)
z-bot-core/.../delegate/DelegateManager.java:164  b.budget(... childBudget(CHILD_BUDGET_FRACTION))
z-bot-core/.../delegate/DelegateManager.java:177  if (depth + 1 > maxDepth)   ← submitAsync 拒委托
z-bot-core/.../delegate/DelegateManager.java:178  return "已达到委托深度上限 ..."
z-bot-core/.../agent/BotAgent.java:1847,1849,1854 maxDepth 由 config.getDelegateMaxDepth() 喂进来
```

⇒ 工单"0 引用的常量只能误导门禁"这一条**不成立**：`CHILD_BUDGET_FRACTION` 有 1 个真实读点（`:164`，且 `DelegateTaskTest:70-71` 已断言其效果 8→2 / 40000→10000）。
但 **`:164` 在 `if (p != null)` 分支里**：`parent` 未回填时子代理拿**父的全额预算**，这条静默旁路**零测试**，本期补。
`maxDepth` 同理可达（`:89/:177`），只是 `maxDepth` 的守卫只挡"再往下委托"，**不挡已经在飞的子代理数**——并发闸是 `config.getDelegateMaxChildren()`（默认 3，`:181`）。

## §0.1 现场与写域自查

- `~/.cache/zbot-p27-lead/` 已存在，E2E 的 `--config-dir` 一律指它下面，不写 `/tmp`。
- 本棒未动 `agent/BotAgent.java`、`config/BotConfig.java`、`session/**`、`store/**`（`StateStore` 只在读侧引用）。

## §1 靶子一：投递有上限、且上限可取证

STATUS: 未跑

## §2 靶子二：live 台账落盘（进程死了还在盘上）

STATUS: 未跑

## §3 靶子三：事件枚举与状态机（穷举 + 非法迁移大声失败）

STATUS: 未跑

## §4 靶子四：深度与预算的可达性

STATUS: 未跑

## §5 杠① `mvn -o test` 串行三跑

STATUS: 未跑

## §6 杠② 变异测试（p27_mutation.py）

STATUS: 未跑

## §7 杠③ 真进程 E2E（p27_e2e.py，≥3 整跑）

STATUS: 未跑

## §8 杠④ `~/.zbot` 三时点

STATUS: 未跑

## §9 量具的错 / 产品的错（分两节）

STATUS: 未跑

## §未做（明确不做 + 理由）

STATUS: 未跑

STATUS: 已跑（§1—§4）

## §1 靶子一：投递有上限、且上限可取证

### 1.1 现状量法（先把洞量出来，再决定补什么）

现状探针 `P27BaselineProbeTest`（**只在修之前跑**，跑完删除，不进最终测试面）：
默认 JSON 会话模式下 `SessionManager(dir).getStore()` 取数、`submitBackground` 一条异步委托、
`backgroundResult` 连拉 12 次。原始输出：

```
$ mvn -o test -Dtest=P27BaselineProbeTest -DfailIfNoTests=false   # rc=0
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.169 s -- in com.zifang.z.bot.delegate.P27BaselineProbeTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
PROBE|submitted_line=已提交异步委托 bg1790413451432-1（/agents 查看进度，/background result bg1790413451432-1 取回结果）
PROBE|id=bg1790413451432-1
PROBE|describeAgents_nobody_has_read_yet=异步委托 (1)   bg1790413451432-1  DONE  bg probe task
PROBE|store_from_json_mode_sessionmanager=null
PROBE|pulls_returning_reply=12/12
PROBE|last_pull=[DONE] bg probe task probe-reply-body
PROBE|session_dir_files_after_kill_sim=
PROBE|delegate_dir_files=delegate-children(d) sandbox(d) sessions(d)
```

配套静态读数（修之前，同一棵树 `HEAD = 53222e1`）：

```
$ git grep -n 'ATTEMPT\|MAX_DELIVERY\|retention\|RETENTION' HEAD -- 'z-bot-core/src/main/java/com/zifang/z/bot/delegate'
grep_rc=1        # 0 命中：投递上限这根轴压根不存在
```

### 1.2 判词（现状到底是无界还是静默成功 —— 两个都是）

| 问 | 实测答复 | 证据 |
|---|---|---|
| "对端没接住"会不会被记成成功？ | **会，而且是默认**。子代理线程自己写 `d.status="DONE"`，`/agents` 当场显示 `DONE`，此时**没有任何人接过**（`nobody_has_read_yet` 那一行就是没拉过一次的状态） | `PROBE\|describeAgents_nobody_has_read_yet` |
| 拉模式有没有上限/计数？ | **无界**：连拉 12 次全返回回复，无尝试计数、无终态、无 `dropped` | `PROBE\|pulls_returning_reply=12/12` |
| 这套旗子在不在盘上？ | **一行都不在**：默认 JSON 模式下 `getStore()` 返 `null` ⇒ `upsertDelegation` 整段被跳过；会话目录里 0 个文件 | `PROBE\|store_from_json_mode_sessionmanager=null`、`PROBE\|session_dir_files_after_kill_sim=`（空） |

⇒ 这正是 P16 抓过的 `lastDelivery='ok'` 同型洞的第二份实现：**把"我这边跑完了"当成"对方收到了"**。
所以本期补的不是"重试逻辑"，是**投递轴本身**：`DONE` 与 `DELIVERED` 分家、claim 烧尝试数、
到 8 次收敛成终态 `DROPPED`、尝试数落 `state.json`（可取证）。

### 1.3 补完之后（同一批判据的反向断言）

- 锚点常量：`DelegationDelivery.MAX_DELIVERY_ATTEMPTS = 8`（与 `async_delegation.py:84` 同值，测试钉死）；
  租约 `CLAIM_LEASE_MILLIS = 300_000`（她 `claim_completion_delivery` 的 `now - 300`）。
- 收工 ≠ 送达：`DelegateManager.submitAsync` 的 worker 只推进生命周期轴，投递轴留在 `PENDING`
  （`DelegationDeliveryTest#readyResult` 里那句 `assertEquals("收工不等于送达", PENDING, e.delivery)`）。
- 无凭证的 ack 大声抛 `IllegalStateException`（`ackWithoutClaimFailsLoudly`），
  底层同一条规矩是**非法迁移**：`PENDING --RESULT_DELIVERED-->` 抛（`DelegateStateMachineTest#ackWithoutClaimIsIllegal`）。
- 烧完 ⇒ `DROPPED(8/8)`，第 9 次 `claim` 直接领不到、`drop` 返 false、终态判词事件只落一次
  （`exhaustedAttemptsConvergeToTerminalDropped`）。
- 上限可取证：换一个新 `DelegationLedger` 实例读同一个根（= 进程重启），
  `attempts` 仍是 8、`delivery_state` 仍是 `DROPPED`，且 `state.json` 里能 grep 到 `"delivery_attempts" : 8`
  （`theCapEvidenceSurvivesARestart`）。
- 重启恢复只捞 `PENDING`：`undeliveredTerminalResults()` 不含 `DROPPED`/`DELIVERED`
  （`droppedRowsAreNotOfferedForRestoreButPendingRowsAre`）⇒ 没人接的完成事件不会永远重放。

## §2 靶子二：live 台账落盘（进程死了还在盘上）

新增 `delegate/DelegationLedger.java`：每条委托一个目录 `<root>/<id>/{state.json,events.log}`，
形状抄她 `delegation_live_log.py` 的 `cache/delegation/live/<delegation_id>/task-<n>.log`。

| 她的约束 | 本期怎么落 | 断言在哪 |
|---|---|---|
| "pre-created with a header at dispatch time" | `DelegateManager.submitAsync`/`runChild` **在建账那一刻**就 `mkdirs + header + state.json`，之后才进线程池/建子代理 | `createWritesTheSceneBeforeAnythingRuns`、`asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery`（子代理被闸门挡住时，另起一个 `DelegationLedger` 实例已经读得到 `QUEUED/RUNNING`） |
| "Never raise into the agent loop" | 第一次写失败翻转 `ok=false`，之后全部 no-op；根目录推不出来（`ledgerRootFor` 返 `null`）时整本台账 disabled | `writeFailureDegradesToNoOpInsteadOfBreakingTheAgent`、`disabledLedgerIsHarmless` |
| "Survive child crashes"（一次写一次开合） | `state.json` 走 `tmp + ATOMIC_MOVE`，`events.log` 每次 `append + flush` 不留长句柄 | `atomicWriteLeavesNoTempFileAndNoHalfState`、`eventsLogCarriesWireNamesInOrder` |
| `LiveTranscriptWriter` 的 `_redact` 边界 | 落盘前统一遮 `sk-…` 与 `api_key=…`（任务文本、回复正文、事件详情三条口） | `secretsAreMaskedBeforeHittingDisk`、`syncDelegationWritesAScene…` |
| `LIVE_RETENTION_DAYS = 7` + `prune_stale_live_dirs` | 同名常量 + `pruneStale(maxAge, now)` 返回删除条数 | 见下两行 |
| 她只判 mtime、只判年龄 | **两处故意收紧**：① 年龄看盘上字段 `finishedAt`（不是 mtime）；② 非终态一律不删 | `pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse` —— 过期终态删 1 条、未过期终态留、超期在飞留，且测试里把留着的目录 `setLastModified` 敲老 400 天做 mtime 口径的证伪 |

回收的正反两问 + 边界（`§6` 变异里 M05/M06/M07/M08 四支都盯这一段）：

- `pruneHonoursTheRetentionWindowBoundary`：三条现场年龄分别为 `窗口-60s / +60s / +180s` ⇒ 删 2 留 1，第二次跑必须删 0。
- `unknownScenesAreTerminalAndThusPrunable`：被 `kill -9` 之后判成的 `UNKNOWN` 也是终态，进得了回收。

"进程死了"的判词入口是 `adoptOrphans(staleMillis, now)`：非终态且心跳超期 ⇒ 走
`DelegateEvent.ORPHAN_ADOPTED` 判成 `DelegateState.UNKNOWN`（她的 `state='unknown'`，
`async_delegation.py:262` 同位），并留一条 `delegate.orphan_adopted` 事件行。
正反两问：`orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes`
（超期的改判、在飞的不许动、已收工的不许再过一遍）。

## §3 靶子三：事件枚举与状态机

- `DelegateEvent`（12 个成员，线名 `delegate.*`）对齐她 `delegate_tool.py:624` 的 `class DelegateEvent(str, enum.Enum)`
  与 `_LEGACY_EVENT_MAP`：入站裸字符串走 `fromLegacyStatus()`，**认不出来就抛**（M14 盯这条）。
- 两条正交轴：`DelegateState`（`QUEUED/RUNNING/DONE/FAILED/STOPPED/UNKNOWN`）
  与 `DeliveryState`（`PENDING/CLAIMED/DELIVERED/DROPPED`），迁移表在 `DelegateTransitions`。
- **穷举复算**：`DelegateStateMachineTest` 里手写一份期望表，对 `6×12` 与 `4×12` 全笛卡尔积逐格比对
  （`lifecycleMatrixIsExhaustive` 断言复算格子数 = 状态数 × 事件数，`deliveryMatrixIsExhaustive` 同理）；
  表里没有的边一律必须抛。另有 `everyEventHasADocumentedVerdict`（新加事件不登记就红）、
  `terminalStatesHaveNoExitAtAll`（终态出口数必须 0；非终态出口数不许 0，否则"永远推不动"也是缺陷）。
- 非法迁移的判词两侧都点名（`illegalTransitionsFailLoudWithBothSidesNamed`），
  且**抛之前不落盘**（`advanceRejectsIllegalTransitionBeforeTouchingDisk` 用 `saveCount()` 对账）。
- 并发下的第二次判决：`DelegationLedger.advance()` 先与盘上事实 reconcile，
  盘上已终态 ⇒ 拒写（`stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins`，M19/M20 盯这一段）。

## §4 靶子四：深度与预算的可达性

见 §0 结论段（`CHILD_BUDGET_FRACTION` 与 `maxDepth` 都可达，工单的"0 引用"怀疑不成立）。
本期补的是**取证面**而不是搬运：

1. `parent == null` 时 `:164` 不裁子预算这条旁路，以前零测试零痕迹 ⇒ 现在建账事件里多一条
   `delegate.budget_unclamped`（并 `LOG.warn`），断言在
   `unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached`（直接 `new DelegateManager(...)` 不 `attach`）。
2. 深度上限拒绝路径**不许建账**：`depthExceededWritesNoSceneAtAll`
   （同步 `delegateTool().execute` 与 `submitAsync` 两条口都试，断言 `list().size()==0` 且 `saveCount()==0`）。
3. 并发宽度不是装饰（推翻 §1#5 与工单的第 4 条怀疑）：读点 1 处 `DelegateManager.java:311`、
   闸门 `:318`；但只数 `RUNNING` 是漏的 ⇒ 改成数非终态 + `concurrencyGate…BurstCannotExceedWidth`。

## §4.1 开发期绿跑（不是杠①，杠①在 §5）

```
$ mvn -o test -Dtest='com.zifang.z.bot.delegate.*Test'
[INFO] Tests run: 8, ... -- in com.zifang.z.bot.delegate.DelegateStateMachineTest
[INFO] Tests run: 5, ... -- in com.zifang.z.bot.delegate.DelegateTaskTest
[INFO] Tests run: 14, ... -- in com.zifang.z.bot.delegate.DelegationDeliveryTest
[INFO] Tests run: 17, ... -- in com.zifang.z.bot.delegate.DelegationLedgerTest
[INFO] Tests run: 10, ... -- in com.zifang.z.bot.delegate.DelegateManagerLedgerTest
[INFO] Tests run: 54, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
新增 4 个测试类 / **49 支**（8+14+17+10），`@Test` 基线 735 → **784**（杠① 三跑读数见 §5）。
