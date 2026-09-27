# P27c EVIDENCE —— delegate 三条"未做"收口 + 本期撞出来的两条（09-27，主编亲测）

范围 = §W5 P27c 那三条（子代理审批自动 deny / 结果摘要上限 + 溢出落文件 / `static` 线程池随 agent 生命周期关闭），
外加本期实测撞出的两条（异步读路径每拉一次重写一份全文、未知 provider 代号静默换出口）。
战略结论与差距叙述在 `_doc/hermes-roadmap.md` §8.17；这一份只管**读数、复算命令、以及我自己错在哪**。

复算入口（三行都能直接贴）：

```bash
git rev-parse HEAD                                  # 3d57571899acbda17cf59277d0dea5620fb2ceb8
bash ~/.cache/zbot-p27c-lead/bar1_final.sh          # 杠① ×3 + 三把尺（log 落 ~/.cache，*.log 被 gitignore）
P27C_ROUNDS=3 python3 -u _doc/acceptance/p27c/p27c_e2e.py   # 杠③ 三轮（需要 jar 重建，勿与杠② 并跑）
```

## §0 树身份：四根杠量的是同一份字节

- 提交链：`9b7c125`（08:39:08 起手，三条"未做"落地）→ `3a577dd`（09:23:58 异步出口改成"收工时裁一次"+ 量具三笔自证）
  → `7f0fe4a`（09:28:02 E2 改成有牙的一支）→ `9951947`（09:41:58 杠② 台账）→ `3d57571`（10:0x 文档笔：§8.17 + 三处出网归因订正）。
  杠① ×3 起跑于 10:00:56，测的是 `3d57571` 这一版**含文档笔的最终树**；本文档是收口步补的，**没有任何测试会打开它**
  （复算：`grep -rn "p27c/EVIDENCE" z-bot-core/src/test/java` = 2 处命中，`SummaryBudgetTest.java:26`、
  `SubagentApprovalDenialTest.java:39`，两处都在 javadoc 注释里；真会被测试打开的只有
  `HttpRouteLedgerTest:145` 读的 `_doc/acceptance/p28/EVIDENCE.md`）。
- md5 对账（左 = 盘上，右 = `git show HEAD:<path>`，全部 SAME ⇒ 读数与被提交的字节一致）：

```
SAME 4609ecb714c7eff83fabe59d48b97b07  z-bot-core/src/main/java/com/zifang/z/bot/delegate/DelegateManager.java
SAME bd1b8e2cd4dfcbd86b7fb52ae8473530  z-bot-core/src/main/java/com/zifang/z/bot/delegate/SummaryBudget.java
SAME 65e48e4788f2fa5e17b412a5a6a0e173  z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java
SAME 8bd1473d1606d3a052e2ca42eb2b145b  z-bot-core/src/main/java/com/zifang/z/bot/config/BotConfig.java
SAME cc6bab095e9df33bd6001be987b72a31  .../test/java/com/zifang/z/bot/delegate/DelegateSummaryWiringTest.java
SAME 14750d2101aaa0b803f01b64a41a0d5e  .../test/java/com/zifang/z/bot/delegate/SummaryBudgetTest.java
SAME 6854e4287877a368b6bd4c56052e42cc  .../test/java/com/zifang/z/bot/delegate/SubagentApprovalDenialTest.java
SAME a93812fa05bd0d1ed585565ef2ed0583  .../test/java/com/zifang/z/bot/delegate/DelegatePoolLifecycleTest.java
SAME dfd01c03aed1deb771450599f597d546  .../test/java/com/zifang/z/bot/delegate/P27cDelegateDriver.java
SAME ced6751f627e3a46b4d5c182b55c71d0  .../test/java/com/zifang/z/bot/ReadmeClaimsTest.java
SAME f126e6e929b2896cb771ce1924bd9d26  _doc/acceptance/p27c/p27c_mutation.py
SAME 319105d851def18e3405da5c14b7317a  _doc/acceptance/p27c/p27c_e2e.py
SAME 08e05c8bb24a9e6324a52543e2d505a6  _doc/acceptance/p27/p27_mutation.py
```

- **时刻对账**（防"量具自己把 mtime 作废掉"）：`p27c_mutation.py` mtime `09:27:44` < `bar2-run5.log` `09:29:41`（台账落账 `#generated_by 2026-09-27T09:41:33+0800`）；
  `p27c_e2e.py` mtime `09:53:08`（那次只动注释的订正）< `bar3-rounds4.log` birth `09:53:44`；
  `BotAgent.java` 在牙口探针后 mtime 未变且 md5 与 HEAD 相同（`65e48e47…`，`git status --porcelain -- z-bot-core/src` 空）。
- **本期读数用的测试规模**：4 个新增测试类共 **28** 支（run3 的 XML `tests` 属性现读：
  `SummaryBudgetTest` 11 / `DelegateSummaryWiringTest` 6 / `SubagentApprovalDenialTest` 6 / `DelegatePoolLifecycleTest` 5；
  四个类在 `9b7c125` 都是 `A`（新增））。数规模请读 XML 的 `tests`，别数源码 `@Test` 字样。
- **hermes 权威出处**（`~/.hermes/hermes-agent` @ `cbc1054e2387c51b51f128b24a507481dc5b221d`，`wc -l tools/delegate_tool.py` = **3655**，09:5x 现测）：

| 出处行 | 原文（截断到 118 字符） | 我们落到哪 |
| --- | --- | --- |
| `:57-73` | `# Subagent approval callbacks` 说明块 | 问题定义：子代理不该把申请留给没人等的队列 |
| `:74-85` | `def _subagent_auto_deny(command, description, **kwargs) -> str:` … `"""Auto-deny dangerous commands in subagent threads (safe default).` | `BotAgent.java:700` 的 `nonInteractive` 判定 + `[auto-denied]` 回灌 |
| `:88` | `def _subagent_auto_approve(…)` | **故意不抄**（自动放行 = 把 `agent.exec.confirm=off` 藏进委托面） |
| `:587-590` | `# …0 disables the ceiling.` / `DEFAULT_MAX_SUMMARY_CHARS = 24000` | `SummaryBudget.java:37`（`0` 关掉上限那支由 D2/E1 钉） |
| `:591-595` | `# The per-summary budget is this slice divided across the batch, so N children can't collectively blow the parent's window` / `_SUMMARY_HEADROOM_FRACTION = 0.5` | **不抄**：我们的 delegate 面一次只回一条（`delegate_task` 单个 `task` 参数） |
| `:598` | `_MIN_SUMMARY_CHARS = 2000` | 同上，批分摊的地板，没有批就没有地板 |
| `:1615-1637` | `def _spill_summary_to_file(task_index, summary) -> Optional[str]` + `"""Write a subagent's full summary … and return path.` | `SummaryBudget.java:111 spill`（best-effort，写不成只裁） |
| `:1640-1646` | `def _trim_summary_with_footer(…)` + `# keep a head+tail window (~75% head / ~25% tail, snapped to line boundaries)` | `SummaryBudget.java:66 trim`（75/25 + 行边界吸附 = D1） |
| `:1695-1733` | `def _parent_summary_char_budget(parent_agent, n_summaries) -> Optional[int]:` | 不抄，理由见 §8.17 与 README"明确没做的" |

## §1 杠①：全 reactor `mvn -o test` ×3 串行（最终树 `3d57571`）

命令（先清 core 与 packager 的 `surefire-reports`，无 `-pl`，离线）：

```bash
rm -rf z-bot-core/target/surefire-reports z-bot-desktop-packager/target/surefire-reports && mvn -o test
```

三跑逐字读数（原始日志 `~/.cache/zbot-p27c-lead/bar1-final-{1,2,3}.log`，`*.log` 被 gitignore ⇒ 原样贴）：

```
RUN 1  BAR1_START|2026-09-27 10:00:56+0800  BAR1_END|2026-09-27 10:02:03+0800  rc=0
[INFO] Tests run: 1223, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS   [INFO] Total time:  01:06 min
socket_hits=0   fail_or_err_lines=0
RUN 2  BAR1_START|2026-09-27 10:02:03+0800  BAR1_END|2026-09-27 10:03:08+0800  rc=0
[INFO] Tests run: 1223, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS   [INFO] Total time:  01:03 min
socket_hits=0   fail_or_err_lines=0
RUN 3  BAR1_START|2026-09-27 10:03:08+0800  BAR1_END|2026-09-27 10:04:12+0800  rc=0
[INFO] Tests run: 1223, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS   [INFO] Total time:  01:04 min
socket_hits=0   fail_or_err_lines=0
```

三把尺对着读（每跑各算一次，三跑输出逐字相同）：

```
RULER|reactor=1223 xml_files=115 xml_sum=1223
RULER|text_at_test=1226 comment_lines=3 minus=1223 main_src_hits=0
RULER|comment_hits=z-bot-core/src/test/java/com/zifang/z/bot/ui/RawTerminalVerdictProbe.java:7,
                    z-bot-core/src/test/java/com/zifang/z/bot/llm/P26RetryPolicyTest.java:37,
                    z-bot-core/src/test/java/com/zifang/z/bot/memory/MemoryE2eDriver.java:16
```

即 surefire 合计 **1223** == 跑后 **115** 份 `*/target/surefire-reports/*.xml` 的 `tests` 求和 **1223**
== 文本 `@Test` **1226** − 注释里的 **3** 处字样（那三处逐条点名，`src/main` 命中 0）。
`xml_files` 只有 `z-bot-core` 一个模块（`z-bot-desktop-packager` 无测试）⇒ 115 份 XML = 115 个测试类。
`socket_hits` 的判据是 `grep -cE 'BindException|Connection refused|SocketTimeout'`，三跑均 0；
`fail_or_err_lines` 另数 `<<< FAILURE|<<< ERROR`，三跑均 0 —— 这两个 0 不是同一个 grep，别混引。

## §2 杠②：`p27c_mutation.py` 21 支（定稿全 RED-OK）+ `p27_mutation.py` 22 支整批重跑

**五跑才定稿**，每跑都持 flock（`.git/zbot-mutlock`），台账 `LEDGER.tsv` 由脚本生成、旧跑归档 `LEDGER-runN.tsv`：

| 跑 | `generated_by` | 非 RED-OK 的成员 | 这一跑改了什么 |
| --- | --- | --- | --- |
| run1 | `08:41:08` | `D3`、`E2` 均 `GREEN-BUT-MUTATED` | D3 的 fixture 只拿"是个文件"当障碍 ⇒ 压根走不到 `catch` |
| run2 | `08:47:24` | `D5`、`E2` | D3 修好（`setWritable(false)`），同一刀型在 D5 又露一次：`mkdirs()` 返回 false 那支没有对应形状 |
| run3 | `08:55:11` | `E2` | D5 修好（`spillDirectoryUnbuildableStillTrimsButSaysSo`）⇒ 只剩 E2 |
| run4 | `09:25:59` | `E2` | 确认 E2 的红不是时序，是**形状不可分**（见 §5 (c)） |
| run5（定稿） | `09:41:33` | 无 | 补一支绕开 `build()` 的守卫 ⇒ E2 转 RED-OK |

定稿读数（`bar2-run5.log`，逐字）：

```
SAMPLER_TEETH|fails=无
BAR4_BEFORE|/Users/zifang/.zbot=8/2dadaed0/690ddbc0/125
[对照] C-子代理审批        OK     ran=5 点名=5 2.0s 全绿
[对照] D-摘要预算         OK     ran=8 点名=8 1.4s 全绿
[对照] E-委托出口裁切       OK     ran=6 点名=6 1.5s 全绿
[对照] F-池生命周期        OK     ran=5 点名=5 1.5s 全绿
[对照] G-缺省值对账        OK     ran=2 点名=2 1.3s 全绿
…（21 支全部 `RED-OK … 还原=True`，逐支原文见同一份 log）…
BAR4_AFTER |/Users/zifang/.zbot=8/2dadaed0/690ddbc0/125
BAR4_DRIFT|无（整跑没碰真 profile）
== 台账 ==
  RED-OK             21
  PARTIAL            0
  GREEN-BUT-MUTATED  0
  BROKEN             0
  NO-RUN             0
  注入后 src 有差异的文件: 无
  git diff --name-only : （空）
```

五支族内阳性对照的"点名=ran"是双向的：既证注入前这些用例全绿，也证脚本点名的 testcase **真实存在**
（这道"点名的用例必须真实存在"的闸是 P28 那笔"按主题点将"之后加的，§8.16 记过它拦下一次手误；本期没再拦到什么）。21 支的杀手名单（定稿跑，逐字摘录）：

```
C1 摘掉 deny 分支（子代理退回'抛暂停'）        杀手 3/3 | asyncChildAlsoDeniesInsteadOfStalling, childApprovalIsDeniedInlineAndTurnKeepsGoing, deniedApprovalLeavesNoPendingRowInThePrivateQueue
C2 摘掉队列结清                          杀手 2/2 | asyncChildAlsoDeniesInsteadOfStalling, deniedApprovalLeavesNoPendingRowInThePrivateQueue
C3 摘掉 buildChild 的接线                杀手 4/4 | 上述三支 + delegatedChildIsDeclaredNonInteractive
C4 deny 分支对所有 agent 打开              杀手 1/1 | interactiveParentStillAsksAndQueues
D1 摘掉头尾吸附到行边界                     杀手 1/1 | SummaryBudgetTest#footerOffsetPointsAtTheOmittedMiddleAndIsOneIndexed
E2 溢出目录不再按 configDir 推导            杀手 1/1 | DelegateSummaryWiringTest#configDirWinsOverWhereverTheChildSessionsSit
E3 同步出口整份照回                        杀手 2/2 | syncExitTrimsIntoParentContextAndSpillsUnderProfileDir, SummaryBudgetTest#capKeyIsActuallyReadByTheDelegateExits
E4 摘掉 config==null 的 fallback 推导      杀手 1/1 | noConfigSummariesRootDerivesFromTheChildSessionsParent
E5 取回时现裁（读路径每次拉重写一份全文）        杀手 1/1 | repeatPullsRenderOnceAndShareOneSpillFile
F1 池改回进程共用一口（static）              杀手 2/2 ran=5 | asyncChildIsShutDownWhenItFinishes, poolFieldIsAnInstanceFieldNotAStatic, shutdownClosesThisAgentsPoolOnly
F2 shutdown() 不再把池交下去               杀手 2/2 | shutdownClosesThisAgentsPoolOnly, submitAfterShutdownRefusesAndDoesNotLeakASlot
F3 异步那条路收工不 shutdown 子代理           杀手 1/1 | asyncChildIsShutDownWhenItFinishes
F4 池已关时的拒收兜底摘掉                    杀手 1/1 | submitAfterShutdownRefusesAndDoesNotLeakASlot
G1 BotConfig 摘要缺省 24000 → 24001        杀手 2/2 | ReadmeClaimsTest#everyQuantitativeReadmeClaimMatchesTheRecomputation, SummaryBudgetTest#defaultCapIsTheHermesNumberAndTheConfigDefaultAgrees
G2 BotConfig 宽度缺省 3 → 4               杀手 1/1 | ReadmeClaimsTest#everyQuantitativeReadmeClaimMatchesTheRecomputation
```

`p27_mutation.py`（22 支，被测文件本期变了 ⇒ **整批重跑**，run4 = `09:41` 前后落账）：

```
== 计数: RED-OK=21, SURVIVED=1 ==
== SURVIVED 点名 ==
   M13-non-atomic-state-write —— 丢掉 ATOMIC_MOVE：单进程读写序下这条不可观测（预期可能 SURVIVED，见 EVIDENCE §6）
   git diff --name-only (src/main/delegate): <空>
   git status --porcelain 里的非预期条目: <无>
BAR4|end|dir=/Users/zifang/.zbot|entries=8|cfg_md5=2dadaed0|db_md5=690ddbc0|key_len_only=125
BAR4_VERDICT|same=YES|start=(8, '2dadaed0', '690ddbc0', '125')|end=(8, '2dadaed0', '690ddbc0', '125')|diff=无|problems=无/无
```

## §3 杠③：`p27c_e2e.py` 真 jar / 真子进程 / 只绑 127.0.0.1 的假端点，三轮 ×42

重跑原因写在 §5(g)：**这一跑的字节是改过注释之后的 `p27c_e2e.py`（`319105d8…`）**，
log `~/.cache/zbot-p27c-lead/bar3-rounds4.log`（birth 09:53:44、mtime 09:53:52，含 9 次 `JAVA|` 子进程调用与一次 jar 重建）。

```
BUILD|main rc=0
BUILD|classpath=/Users/zifang/.cache/zbot-p27c/cp-main.txt size=3044 rc=0
JAR_IDENTITY|SummaryBudget=1 DelegationLedger=0
CHECK|K0 构件身份：本期新增的类里有那句 footer                    PASS SummaryBudget.class 命中=1
CHECK|K0b 反向对照：没碰过的类里数不出那句                         PASS DelegationLedger.class 命中=0
（R1 16 支 + R2 11 支 + R3 11 支 + K0/K0b 2 支 + 零真发/整跑一致 2 支 = 42）
CHECK|零真发：一个包都不出本机（假端点只绑 127.0.0.1）                PASS 三进程 stdout 里无非回环 http(s) 端点
CHECK|整跑 ~/.zbot 三点一致                              PASS 8/2dadaed0/690ddbc0/125 / 8/2dadaed0/690ddbc0/125 / 8/2dadaed0/690ddbc0/125
RUN|CHECKS=42|FAILED=0|全过
```

三轮 = 同一个键的三种配置，"配置项不是装饰"的进程外证据：

- **R1 `agent.delegate.max.summary.chars=300`（必裁）**，16 支全 PASS，关键几条逐字：

```
CHECK|R1-1 A4 进父上下文的这份被裁                           PASS 长度=793 含原文总长=True
CHECK|R1-1 A5 溢出文件字节 == 假端点发出的全文                   PASS spill=['subagent-summary-dlg1790474029120-1-20260927_095349_217.txt']
CHECK|R1-1 A6 溢出目录由 --config-dir 推导（红线 1）          PASS spill_dir=…/R1-1/cfg-deny/delegate/summaries cfg=…/R1-1/cfg-deny
CHECK|R1-1 A7 台账 detail 里那条全文路径与实际文件同一个            PASS detail=dlg1790474029120-1 delegate.dispatch task=去删一个目录并回报 depth=0 pid=96924
CHECK|R1-1 A8 异步出口过同一把尺（裁 + 唯一溢出）                  PASS len=804 spill=1
CHECK|R1-1 A8c 再拉一次不落第二份全文、指针仍是同一个文件               PASS spill=1 一次含指针=True 二次含指针=True
CHECK|R1-1 A8d 异步溢出落在 async 那一份 profile 里          PASS …
CHECK|R1-1 A8e 异步台账那条 task_completed 里带同一个全文指针     PASS detail=bg1790474029493-1…
CHECK|R1-1 A9 池收掉后提交被当场拒且台账落 FAILED                PASS refused=异步委托未提交：委托池已关闭，任务未提交 states=['FAILED']
CHECK|R1-1 A10 真 profile 三点不变、底下没有 delegate/summaries PASS before=8/2dadaed0/690ddbc0/125 after=8/2dadaed0/690ddbc0/125
```

  A9 那行里的 `states=['FAILED']` 是 **detail 文本**，不是判定：`grep FAIL` 在这份 log 命中 3 行，全是这一条臂的 detail，
  真判据是行首的 `PASS` 与末行 `FAILED=0`。
- **R2 缺省 24000**（回复 2020 字符 ⇒ 不该裁）与 **R3 `=0`**（关掉上限）各 11 支，同一形状：

```
CHECK|R2-2 A4 这份整页照回、未被裁                           PASS 父侧行数=2
CHECK|R2-2 A5 不裁就不往 profile 里写文件                   PASS spill_dir=…/R2-2/cfg-deny/delegate/summaries 个数=0
CHECK|R2-2 A8 异步出口整页照回且零溢出                         PASS len=2095 spill=0
（R3-3 同三条，路径里的 `R3-3` 不同）
```

- **牙口**（`P27C_TEETH_PROBE=1`，持变异锁；log `bar3-teeth-4.log` birth 09:54:06）：把 `.nonInteractive(true)` 摘掉、重建、跑一轮 deny：

```
== P27C_TEETH_PROBE：旧形状装回去，病症必须显形 ==
JAVA|deny|tag=TEETH rc=0 lines=17 facts=17
CHECK|TEETH 旧形状下父侧真的收到过 WAIT_CONFIRM 假成功           PASS saw_wait_confirm=True rc=0 sentinel=True
CHECK|TEETH 旧形状下 [auto-denied] 确实还没诞生              PASS saw_auto_denied=False
CHECK|TEETH 这一支自己没出网                               PASS 
TEETH|病症显形=True 判据红=无
TEETH|还原 md5 对账=True
```

  判据是**一对**（旧形状下 `WAIT_CONFIRM` 显形 **且** `[auto-denied]` 尚未诞生），不是"有 CHECK 变红"——
  前者只在旧形状成立、后者只在旧形状成立，两支同时红才说明牙咬在 `.nonInteractive(true)` 那一句上。
  还原只从注入前读进内存的字节写回，`git status --porcelain -- z-bot-core/src` 事后为空。

## §4 杠④：`~/.zbot` 不变量（真 key 只量长度，从未读/印/提交）

| 时刻 | 来源 | 读数 |
| --- | --- | --- |
| 09:53:36 | 量具外手工复采（重跑杠③ 前） | `8 / 2dadaed0 / 690ddbc0 / keylen 125` |
| 09:53:44–09:53:52 | `bar3-rounds4.log` 每轮 `HOME\|*.before/after` + `整跑一致` | 三轮 before/after 全同，`8/2dadaed0/690ddbc0/125` |
| 09:54:32 | 量具外手工复采（牙口探针后） | `8 / 2dadaed0 / 690ddbc0 / keylen 125` |
| 10:00:51 | 量具外手工复采（杠① ×3 前） | `8 / 2dadaed0 / 690ddbc0 / keylen 125` |
| 10:04:29 | 量具外手工复采（杠① ×3 后） | `8 / 2dadaed0 / 690ddbc0 / keylen 125` |
| 杠② 两族首尾 | `BAR4_BEFORE/AFTER` + `BAR4_DRIFT|无`、`BAR4_VERDICT|same=YES` | 同上，且 `p27` 族自己会打印 `problems=无/无` |

复算命令（`ls ~/.zbot | wc -l` 会少算点文件，本期实测 `7` ≠ 量具的 `8` ⇒ 一律用 `-a` 再剔 `.`/`..`）：

```bash
echo "entries=$(ls -a ~/.zbot | grep -vE '^\.\.?$' | wc -l | tr -d ' ')/cfg=$(md5 -q ~/.zbot/config.properties | cut -c1-8)/state=$(md5 -q ~/.zbot/state.db | cut -c1-8)/keylen=$(awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties)"
```

另加一条红线：杠③ 的 A10 每轮都判 `~/.zbot` 底下**不许**出现 `delegate/summaries`（本期全 PASS）；
E2E 全部用 `--config-dir` 指 `~/.cache/zbot-p27c/e2e/<tag>/…` 临时根，key 写的是 `stub-key-not-real`。

## §5 逐条裁定（含我自己两处失实的订正）

- **(a) D3 `GREEN-BUT-MUTATED` → RED-OK**：run1 的 fixture 拿"目录里是个文件"当"写不进去"，
  而生产那支走的是 `catch`，`mkdirs`/`isFile` 两道都在它之前返回 ⇒ 注入体根本没被执行到。
  改成 `dir.setWritable(false)` 并让测试自己断言 fixture 成立（`assertTrue("fixture 没成立：目录改不成只读", …)`）。
- **(b) D5 同型第二次**：修 D3 时没意识到 `mkdirs()` 返回 false 与 open 抛异常是**两支不同的臂**，
  run2 的 D5 因此又恒绿一次；补 `spillDirectoryUnbuildableStillTrimsButSaysSo`。
  教训：三条"落不成"的路要**各**有一支形状，一条 fixture 量不到两支。
- **(c) E2 从"判为等价的幸存者"改成有牙**：前四跑 E2 恒 `GREEN-BUT-MUTATED`，我差点按"等价"记账。
  现读码证明不是等价，是**真实接线上形状不可分**：`BotAgent.build()` 在 config 模式下把 `childSessions`
  定在 `<configDir>/delegate/children`（`DelegateManager.summariesRoot()` 现测 `:566-573`），
  于是"按 configDir 推"与"按 childSessions 的父目录推"算出**同一个路径** ⇒ 走真实接线的两支用例结构上分不开这两种修法。
  做法：另立一支**绕开 `build()`**、直接构造分岔形状的守卫 `DelegateSummaryWiringTest#configDirWinsOverWhereverTheChildSessionsSit`
  （children 放 profile 外，断言溢出目录仍等于 `<cfgDir>/delegate/summaries` 且不落到 children 底下），
  E2 立刻 `杀手 1/1 … RED-OK`；同时把 `syncExitTrims…`/`asyncExitIsTrimmed…` 从 E2 的点名单里**摘掉**
  —— 它们不可能满足那张表，留着等于虚增"预测连带红"。**注入体不回落到 `~/.zbot`**（杠② 期间一个字节都不许写进真 profile）。
- **(d) E5（本期新缺陷的牙）**：`asyncResult()` 原本每次拉都现裁，溢出文件名带毫秒戳 ⇒ 同一个委托拉 N 次落 N 份全文。
  改成收工时算一次（`DelegateManager.java:386` 用 `renderedReply(d)`，`:495-501` 把结果记进 `d.rendered`），
  行为守卫 `repeatPullsRenderOnceAndShareOneSpillFile` + E2E A8c 双钉（单测数指针、真进程数磁盘文件）。
- **(e) M18 的连带红名单按机制重订**：`M18-poll-burns-delivery` 连续两跑 PARTIAL，且**红的成员每次不一样** ——
  四支都轮询 `backgroundResult`，摘掉闸门后谁先输是竞态。前两版我只把当时看到的 2 支写进 `allow_extra`，
  于是又冒出新红。定稿：四支**全部**进 `allow_extra` 并带上 log 里的原文失败行（`~/.cache/zbot-p27-lead/mutation/M18-poll-burns-delivery.log`）：

```
[ERROR] Tests run: 5, Failures: 1 … <<< FAILURE! -- in com.zifang.z.bot.delegate.DelegateTaskTest
[ERROR] com.zifang.z.bot.delegate.DelegateTaskTest.asyncDelegationCompletesAndResultRetrievable -- Time elapsed: 0.151 s <<< FAILURE!
[ERROR] Tests run: 6, Failures: 2 … <<< FAILURE! -- in com.zifang.z.bot.delegate.DelegateSummaryWiringTest
[ERROR] com.zifang.z.bot.delegate.DelegateSummaryWiringTest.repeatPullsRenderOnceAndShareOneSpillFile -- Time elapsed: 0.067 s <<< FAILURE!
java.lang.AssertionError: 拉了三次就落三份全文 ⇒ 读路径在写盘: null expected:<1> but was:<0>
```

  **没有**靠扩 `expect` 去凑红：`expect` 里只留确定性判据 `q2_deliveryAxis…`（闸门还关着 ⇒ 子代理必定堵在 `chat()` 里 ⇒ "没收工就去 claim"那一格必被执行）。
- **(f) 未知 provider 代号的出网归因订正（我自己写错的，两处）**：我一度在 roadmap §5 与 README 写成
  "**本期杠③ run1 就是这么撞出去的**"，`p27c_e2e.py` 注释也写"（run1 就踩了这个）"。复算：
  `grep -rl 'api\.openai\.com' ~/.cache/zbot-p27c-lead/ ~/.cache/zbot-p27-lead/` ⇒ **无输出**
  （10:0x 现复算：这两目录共 **53** 份 `*.log`，`openai_hits=0`；§8.17 里写的"19+"是 09:5x 那次采样的下界，不是分母），
  `bar3-rounds4.log:66` 的"零真发…PASS"，真出过网的原始行在 `_doc/acceptance/p26/EVIDENCE.md:583/588`
  （P26 run1：`8 发全部打到 https://api.openai.com/v1/chat/completions 拿 401`、本地 stub 一发没收到）。
  阳性对照：同一条 grep 在 `p26/EVIDENCE.md` 命中 **7** 行。**机制站得住、事件归属站不住**
  （`BotConfig.java:27-32` 只认 `minimax`/`spark`；`:363-372` 查不到代号不报错，表非空交第一个、表空回落
  `new Provider(code, "openai", null, null, model)`；`LlmRouter.java:40` `baseUrl == null ? new OpenAIProvider(apiKey)` ⇒ kernel 默认域）。
  产品侧修法（未知代号 FATAL，还是允许回落但不许无 baseUrl 出站）**等裁定**。
- **(g) 改 harness 注释 ⇒ 杠③ 整跑重跑**：`p27c_e2e.py` 那段注释属于 (f) 的订正，只动 `#` 行
  （`git diff --unified=0 HEAD~1 HEAD -- _doc/acceptance/p27c/p27c_e2e.py` 数出来是 **−3/+7 全在注释行**，
  可执行字节逐字未变、`ast.parse` OK），但**台账引用的 md5 必须与读数是同一版字节**，
  所以重跑了三轮 42 支与牙口探针（`bar3-rounds4.log` / `bar3-teeth-4.log`），读数与订正前一致。
- **(h) 量具自己那三笔（`3a577dd` 一并修的，记在这里免得下一个人当成产品缺陷）**：
  A7 从 `state.json` 找事件 ⇒ 恒空（事件在 `events.log`）；A8 的事实值按"= 到下一个空格"截断 ⇒ 整份原文一律 base64 传；
  牙口探针没起假端点 ⇒ 子 JVM 直接报 URL 无 scheme，旧形状"病症"根本没显形，而判据把"有 CHECK 变红"当成了证明。

## §6 覆盖缺口与本期欠账

**按未覆盖记账的（不注入凑数）**：
① D1 只有 1 支点名单（吸附的差异全靠 footer 的 `offset=` 反推）；
② G2 只有 README 对账那支红 —— `DelegateManager` 宽度取法是 `config == null ? 3 : …`（现测 `:340`，闸门 `:347`），
连发用例 `DelegateManagerLedgerTest#concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth` 走的就是 `config==null` 那一支
⇒ 改 `BotConfig` 字段缺省**结构上碰不到它**；
③ `capZeroDeliversTheWholeReplyAndWritesNothing` 在 E1（写死 24000）下与"关掉裁切"对一份 2020 字符的回复**产出逐字相同**
⇒ 那一句只能由源码守卫 `capKeyIsActuallyReadByTheDelegateExits` 拦，不记成行为覆盖；
④ `p27` 族的 `M13-non-atomic-state-write` 仍是判等价的幸存者（单进程读写序不可观测）。

**欠账**：
① 真凭据握手零验证（一条没减，与 P30/P30b/P30c 同源）；
② 未知 provider 代号的修法等裁定（见 §5(f)）；
③ `_parent_summary_char_budget` 那把批分摊尺要接先得把批形状造出来；
④ D1/G2/M13 三处是记账不是覆盖；
⑤ `POST /api/skill/push` 语义（D-P28-2）、`/api/agent/register` 200-vs-501 未裁定；
⑥ `<revision>` 0.2.0→0.3.0 / 发 Central / 外部工程真 pull（P29）、p25 族预期集按机制复查、CI 当第五把尺、
真 tty 人机体验（NO-RUN）—— 都在 z-bot 推送授权之外，**等点头**。
