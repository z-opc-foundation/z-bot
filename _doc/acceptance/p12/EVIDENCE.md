# P12 验收证据（预算台账 / 中断收口 / steer 排空 / system prompt 快照冻结）

棒：p12c（续 p12b / p12）。分支 `w2-p12`。基线 commit：`809b927`（起）。
本文件是**唯一入仓的证据载体**——`*.log` 被 `.gitignore:5` 排除，所有决定性读数原文粘在下面，并附产生它的命令。

> 状态：骨架（写作中）。每一节填完后当场 commit；未填一节写 `UNKNOWN`，不写推测数字。

---

## 0. 起讫与盘上状态

- 起：`809b927`
- 讫：UNKNOWN（收尾时回填 `git rev-parse --short HEAD`）
- `git status --porcelain`：UNKNOWN（收尾时须为空）

---

## 1. 杠①：全量单测串行三跑

命令（HEAD `6998e68`，与上一棒的 `logs/bar1_final_run*.log` 同一形态；串行，无并发）：

```
cd /private/tmp/zbot-wt-p12
for i in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; \
  mvn -o test > _doc/acceptance/p12/logs/p12c_bar1_run$i.log 2>&1; echo "rc=$?"; done
```

三跑逐字读数（`grep -nE "Tests run: 483, Failures: 0, Errors: 0, Skipped: 0$|BUILD SUCCESS|Total time|rc=0$" logs/p12c_bar1_run<i>.log`）：

```
run1  [INFO] Tests run: 483, Failures: 0, Errors: 0, Skipped: 0     [INFO] BUILD SUCCESS   Total time: 30.414 s   rc=0
run2  [INFO] Tests run: 483, Failures: 0, Errors: 0, Skipped: 0     [INFO] BUILD SUCCESS   Total time: 26.168 s   rc=0
run3  [INFO] Tests run: 483, Failures: 0, Errors: 0, Skipped: 0     [INFO] BUILD SUCCESS   Total time: 21.358 s   rc=0
```

- 每跑的 `-- in <类>` 行数：**49**（`grep -c 'Tests run:.*-- in ' logs/p12c_bar1_run<i>.log`，三跑同）。
- 上一棒欠的两样本棒补齐：**`BUILD SUCCESS` 行**与 **shell rc**（简报点名"没贴 BUILD SUCCESS 与 rc"）。
- 分母对账：`483` = 简报里的 `481`（`809b927`）+ 本棒补活的 2 条
  （`P12RefundAndSteerGuardTest.steerIsDrainedExactlyOnce` / `abortedTurnReportsTheCallsActuallyMade`
  —— 上一棒留的未提交半成品**编译不过**（`countIn` 未定义），所以它从没进过任何一次 `Tests run` 计数）。
  复算：`mvn -o test -pl z-bot-core -Dtest=P12RefundAndSteerGuardTest` ⇒ `Tests run: 6, Failures: 0`
  （`logs/p12c_guardsolo.log`，rc=0），该类在 `809b927` 上是 4 条。
- 与 `b64d294` 那一版的关系（简报点名的 `475 … Failures: 1`）：本棒没重跑 `b64d294`，
  只复算它那条红的性质没变——见 §2.1 的 M1/M1b 两支注入仍能把 `newSessionAndSwitchRestoreHistory` 判红。

---

## 2. 杠②：注入自证（`p12_mutation.py`）与 LEDGER.tsv

### 2.1 全量一轮（期望集在跑之前就已写死在 `MUTANTS` 里，本棒没回填过）

命令与 rc（HEAD `e69709a` 的前一笔 `6998e68`；整轮 19 支，22 分钟，独占 flock）：

```
cd /private/tmp/zbot-wt-p12 && python3 -u _doc/acceptance/p12/p12_mutation.py
    → _doc/acceptance/p12/logs/p12c_mut_full_run1.log   rc=0
```

原始 tally 行（同上文件末尾，逐字）：

```
== 台账 ==
  RED-OK             14
  PARTIAL            2
  GREEN-BUT-MUTATED  3
  BROKEN             0
  SRC_MD5_STABLE=yes
  exec 读输出循环的逐行检查点、mvn_build 入口检查点：摘掉之后看门狗仍在 50ms 内端掉进程树，从「多久断」这一面量不出差别，属于第二道保险；如实记未覆盖，不假装注入过。
  台账已机械写出: _doc/acceptance/p12/LEDGER.tsv
```

`LEDGER.tsv`（脚本产物，19 行 + 表头）逐行原文的浓缩版；整表可用
`column -t -s $'\t' _doc/acceptance/p12/LEDGER.tsv` 复算：

| id | verdict | 点名 | 红在（具名 testcase） | mvn rc | 还原 | vs_git |
|---|---|---|---|---|---|---|
| M1 上下文块搬回 system prompt（附加式） | RED-OK | 2/3 | newSessionAndSwitchRestoreHistory,volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt | 1 | ok | base=HEAD before=ok after=ok |
| M1b 上下文块搬回 system prompt（替换式：user 只剩原文） | PARTIAL | 3/3 | midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt,newSessionAndSwitchRestoreHistory,soulStaysInSystemPromptAndMemoryGoesToUserMessage,volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt | 1 | ok | base=HEAD before=ok after=ok |
| M2 自动压缩不退款 | RED-OK | 1/1 | compressionRefundsBudgetAndKeepsTheLoopRunning | 1 | ok | base=HEAD before=ok after=ok |
| M3 手动 /compress 不退款 | RED-OK | 1/1 | manualCompressRefundsIntoTheLedger | 1 | ok | base=HEAD before=ok after=ok |
| M4 净占用不扣退款（账记了但不参与判定） | RED-OK | 1/1 | compressionRefundsBudgetAndKeepsTheLoopRunning | 1 | ok | base=HEAD before=ok after=ok |
| M5 硬中断不清 pending steer | RED-OK | 1/1 | hardStopDropsPendingSteerSoItCannotResurfaceNextTurn | 1 | ok | base=HEAD before=ok after=ok |
| M5b steer 不再是单槽 | RED-OK | 1/1 | queueKeepsEveryEntryWhileSteerKeepsOnlyTheLast | 1 | ok | base=HEAD before=ok after=ok |
| M5c /queue 被写成单槽（前一条被悄悄吃掉） | RED-OK | 1/1 | queueKeepsEveryEntryWhileSteerKeepsOnlyTheLast | 1 | ok | base=HEAD before=ok after=ok |
| M7 steer 退回新插一条 user 消息 | RED-OK | 1/1 | steerDuringRunIsInjectedAtToolGap | 1 | ok | base=HEAD before=ok after=ok |
| M6 摘掉工具子进程看门狗 | RED-OK | 1/1 | execAbortsInFlightAndTakesTheWholeProcessTreeDown | 1 | ok | base=HEAD before=ok after=ok |
| M8 摘掉 exec 入口检查点（置位后仍起进程） | **GREEN-BUT-MUTATED** | 0/1 | 全绿（跑了 67 条） | 0 | ok | base=HEAD before=ok after=ok |
| M9 摘掉文件读检查点 | **GREEN-BUT-MUTATED** | 0/1 | 全绿（跑了 67 条） | 0 | ok | base=HEAD before=ok after=ok |
| M10 摘掉文件写检查点 | RED-OK | 1/1 | writeFileAbortsBeforeTouchingTheSandbox | 1 | ok | base=HEAD before=ok after=ok |
| M11 摘掉 delegate 入口检查点 | RED-OK | 1/1 | delegateRefusesToSpawnAChildAfterStopWasRequested | 1 | ok | base=HEAD before=ok after=ok |
| M12 system prompt 快照解冻（每步按盘重建） | RED-OK | 1/2 | twoChatsInOneSessionSendByteIdenticalSystemPrompt | 1 | ok | base=HEAD before=ok after=ok |
| M16 中断检查点退化成空操作 | **PARTIAL** | 5/6 | checkpointIsNoOpWithoutFlagAndThrowsAfterRequest,delegateRefusesToSpawnAChildAfterStopWasRequested,execAbortsInFlightAndTakesTheWholeProcessTreeDown,execWithFlagAlreadySetStartsNoChildProcessAtAll,flagsArePerThreadSoConcurrentSessionsDoNotCrossFire,readFileAbortsInsteadOfReadingTheWholeFile,writeFileAbortsBeforeTouchingTheSandbox | 1 | ok | base=HEAD before=ok after=ok |
| M17 中断旗子从线程作用域退化成全局 | **GREEN-BUT-MUTATED** | 0/1 | 全绿（跑了 67 条） | 0 | ok | base=HEAD before=ok after=ok |
| M18 steer 排了不空（drain 之后塞回队列） | RED-OK | 1/2 | steerIsDrainedExactlyOnce | 1 | ok | base=HEAD before=ok after=ok |
| M19 中断收口把这一轮的调用账吞掉 | RED-OK | 1/1 | abortedTurnReportsTheCallsActuallyMade | 1 | ok | base=HEAD before=ok after=ok |

派单点名要覆盖的四类判据落点：预算台账 token 退还 = M2/M3/M4；中断收口不吞账 = M16/M17/M19 + M6/M8/M9/M10/M11；
`context.steer().drain()/clear()` = M5（`:914` 那面 `clear()`）/M7/M18（`drain()` 的排空侧）；
**system prompt 快照冻结** = M12（这条必须能判红 —— 实测 `twoChatsInOneSessionSendByteIdenticalSystemPrompt` 当场红了）。

### 2.2 非 RED-OK 的 5 条：逐条落到断言行 + 因果（不洗）

1. **M8 摘掉 `bash()` 入口检查点 ⇒ 全绿**（GREEN-BUT-MUTATED）。因果链：入口那次
   `InterruptScope.checkpoint()` 在 `BuiltinTools.java:445`；摘掉后子进程真被 `pb.start()` 拉起（`:451`），
   但紧接着 `InterruptScope.watch(p)`（`:452`）的看门狗在 ~50 ms 内把进程树端掉，
   而 `sleep <token> & wait` 这种命令一个字都不吐 ⇒ 读循环里的 `checkpoint()`（`:459`）一次都没执行到，
   抛点是 `waitFor()` 之后那一次（`:470`）。被测那条 `ToolSideInterruptTest.java:241-251`
   的两个量都还成立：异常照抛（来自 `:470`）、`assertEquals(0, countProcessesMatching(token))`（`:250`）
   也成立 —— 进程**存在过**、只是在断言之前被看门狗清掉了。
   ⇒ "已经按了停止就不该再把子进程拉起来"这条守卫**没有任何可观测的断言**钉住；
   要钉它得让"起没起过"留下痕迹（同一命令里先 `touch <token>.marker`，入口检查点在就不该有 marker）。本棒未加，见 §7。
2. **M9 摘掉文件读入口检查点 ⇒ 全绿**（GREEN-BUT-MUTATED）。因果链：`read_file` 有两检查点
   （入口 `BuiltinTools.java:154`、每读到一行 `:167`），而 `readFileAbortsInsteadOfReadingTheWholeFile`
   （`ToolSideInterruptTest.java:182-205`）是**先置位再读**一个 300 行的文件 ⇒ 循环里第一次
   `checkpoint()` 就在第 1 行抛出，入口那次本来就不参与判定 ⇒ 对这条用例是**等价变异**。
   分得出两者的现场只有一个：0 行的文件（循环体一次都不进），入口检查点在才判红、摘掉就静默返回空串。本棒未加，见 §7。
3. **M17 旗子退化成全局 ⇒ 全绿**（GREEN-BUT-MUTATED）。因果链：
   `flagsArePerThreadSoConcurrentSessionsDoNotCrossFire`（`ToolSideInterruptTest.java:87-123`）里
   B 线程 `bind(flagB) → request → restore(null)` 之后 `join` 收工，**A 才** `bind(flagA)`
   —— 两次绑定在时间上不重叠。换成全局单槽后，A 绑定那一刻槽里就是 `flagA`：
   `:110` 的第一次 checkpoint 看到的是没置位的 A 自己的旗 ⇒ `aSawInterrupted=FALSE`（`:122` 成立），
   A 自己置位后照样抛（`:123` 成立）⇒ 串台这件事在这个时序下量不出来。
   ⇒ 派单原文"按执行线程定向，防并发会话串台"目前**只有实现、没有能判红的观测**：
   要重叠绑定（A 卡在工具里、B 在同刻 bind+request）才分得出。本棒未加，见 §7。
4. **M1b ⇒ PARTIAL**：点名 3/3 全中，另外多红了一条
   `midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt`（`SystemPromptCacheFreezeTest.java:155-156`
   断 user 消息里必须含中途写入的记忆）。M1b 把上下文头从 user 消息里整个摘掉 ⇒ 记忆随头一起消失 ⇒
   这条**判红是对的**，只是当初没写进期望集。脚本规则把"多红"记 PARTIAL（`hit and not extra` 才算 RED-OK），
   本棒**没有**为它改期望集。
5. **M16 ⇒ PARTIAL**：点名 5/6，多红 2 条（`execAbortsInFlightAndTakesTheWholeProcessTreeDown`、
   `flagsArePerThreadSoConcurrentSessionsDoNotCrossFire`，都是抓对了没写进期望集）。
   **没中的那条有信息量**：`interruptInsideParallelToolBatchAbortsRunNotFeedsModelError` 在
   `InterruptScope.checkpoint()` 整个掏空之后仍然全绿。因果：主循环与并发批次用的是
   `context.interrupt().checkpoint()`（`BotAgent.java:292`、`:608` —— 直接打在 `InterruptFlag` 实例上），
   M16 注的是线程作用域那层静态壳（`InterruptScope.CURRENT.get()` + `f.checkpoint()`），
   两层是**不同的调用面**，所以这条测试钉的是"中止不喂错误给模型"的接线，不是静态壳。
   要让它判红得注入 `InterruptFlag.checkpoint()`，那个类在 `z-agent-kernel`（本期红线：不动内核仓）⇒ 记未覆盖，见 §2.6。

### 2.3 判 RED-OK、但点名没打满的 3 条（同一枚硬币的另一面，也如实报）

脚本规则 `if hit and not extra: RED-OK` 不看"漏了哪几条没红"，所以这三条的 2/3、1/2、1/2 都记成了 RED-OK：

- **M1 2/3**：没红的是 `soulStaysInSystemPromptAndMemoryGoesToUserMessage`。M1 只往 system 前缀里加
  *时钟头*（`VOLATILE_CONTEXT_HEADER`），没把记忆塞进去 ⇒ 那条断言（"记忆不许进 system"）本来就不该被触犯，属合理漏。
- **M12 1/2**：没红的是 `everyStepOfAMultiStepTurnReplaysTheSameSystemBytes`（`:109-110` 比同一轮两步的字节）。
  该用例一轮之内没有写盘动作 ⇒ "每步按盘重建"重算出来的字节与快照逐字相同 ⇒ 对这条是**等价变异**。
  真正能分开的是"跨两轮 + 中途写盘"，即红了的那条 `twoChatsInOneSessionSendByteIdenticalSystemPrompt`。
- **M18 1/2**：没红的是 `steerDuringRunIsInjectedAtToolGap` —— 它只断"插话到了工具结果里"，
  把 `drain()` 退化成"读一眼再塞回队列"时第一遍注入照样成立 ⇒ 必须靠本棒新补的
  `steerIsDrainedExactlyOnce`（队列真空 + 同一请求里只许出现一次）才判得红，这条正是上一棒"注入跑第一遍时无人可抓"的账。

### 2.4 互斥锁：`flock(LOCK_EX|LOCK_NB)`，落在 git 公共目录

- 本脚本自己拿锁的读数：`p12c_mut_full_run1.log` 首行
  `LOCK-ACQUIRED mutator flock=LOCK_EX|LOCK_NB path=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock`
- **兄弟会话真把锁攥住过那一次**（不是自造探针，是同机另一棒在飞）：
  `python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e M6 M12` ⇒

  ```
  LOCK-BUSY mutator：另一支注入脚本攥着 /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock（[Errno 35] Resource temporarily unavailable）⇒ 本次不跑，一个源文件都没碰
  rc=5
  ```

  当时攥锁的是 `/private/tmp/zbot-wt-p20b` 那棒的 `p20d_tk5_equiv_probe.py`（PID 64902，10:44:28 起），
  复算：`lsof /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock` ⇒ 只列出该 PID；
  本棒源码 md5 未变（`git status --porcelain` 之后仍只剩 `logs/`、`out/`）。
- **双向实测**（`--lock-probe`：攥住 flock ⇒ 本脚本必须拒跑且全量源码 md5 不变；松开 ⇒ 照常拿得到）：
  UNKNOWN —— 本轮次截止前未跑到（见 §7），命令已备：
  `python3 -u _doc/acceptance/p12/p12_mutation.py --lock-probe`。

### 2.5 还原独立取证

- 每支注入前后各比一次磁盘 md5 与内存原文：LEDGER 第 10 列 `restored` 全 19 行 `ok`。
- 第二把尺（不采信"日志说还原了"）：`md5 -q <path>` vs `git show HEAD:<path> | md5 -q`，注入前后各一次，
  落在 LEDGER 第 11 列 ⇒ 全 19 行 `base=HEAD before=ok after=ok`。
- 收尾全量对拍：`SRC_MD5_STABLE=yes`（`snapshot()` 覆盖 7 个被注入文件 + 全仓 `.java`）。

### 2.6 未覆盖的守卫（注入不出的，说明为什么）

- `exec` 读输出循环的**逐行**检查点、`mvn_build` 入口检查点：摘掉之后看门狗仍在 ~50 ms 内端掉进程树，
  从"多久断"这一面量不出差别（第二道保险）⇒ 如实记未覆盖，不假装注入过。
- `InterruptFlag.checkpoint()` 本体（kernel `z-agent-kernel`）：M16 想抓的那条测试其实钉在这一层，
  但内核仓本期红线不许动 ⇒ 无锚点，未注入。
- `InterruptScope.watch` 的**超时**参数（50 ms 那一档）：没有能判红的时长断言，注入只能改时长、测不到"有没有"。

### 2.7 实现完成度盘点（四条各自：做没做 / 被哪条测试钉 / 注入抓不抓得住）

| 分期条目 | 做没做（字节层证据） | 钉它的具名用例 | 注入抓得住吗 |
|---|---|---|---|
| 中断：线程作用域旗子 | 做了（`InterruptScope.java` 166 行，`CURRENT` 是 `ThreadLocal`） | `checkpointIsNoOpWithoutFlagAndThrowsAfterRequest`、`flagsArePerThreadSoConcurrentSessionsDoNotCrossFire` | **半边抓不住**：M16 抓得住（5 条红），M17"退化成全局"全绿 ⇒ "按线程定向"这一半没有可观测断言（§2.2 第 3 条） |
| 中断：工具侧检查点（exec 读输出循环 / 文件读写 / `mvn_build` / delegate 子循环） | 做了（`BuiltinTools` 9 处、`DelegateManager` 2 处、`BotAgent` 4 处） | `execAbortsInFlightAndTakesTheWholeProcessTreeDown`、`readFileAborts…`、`writeFileAborts…`、`delegateRefusesToSpawnAChild…` | 看门狗（M6）/文件写（M10）/delegate（M11）**抓得住**；exec 入口（M8）、文件读入口（M9）**抓不住**；逐行检查点与 `mvn_build` 入口属"第二道保险"，量不出差别 ⇒ 记未覆盖（§2.6） |
| steer：单槽 + 追加到最后一条 tool 结果 + 硬中断丢弃 pending | 做了（`context.steer().drain()` 有真消费者；`:914` 硬中断 `clear()`；**0 消费者抽象已不存在**，复算 `git grep -n 'context.steer()' -- z-bot-core/src/main/java`） | `steerDuringRunIsInjectedAtToolGap`、`hardStopDropsPendingSteerSoItCannotResurfaceNextTurn`、`queueKeepsEveryEntryWhileSteerKeepsOnlyTheLast`、本棒新增 `steerIsDrainedExactlyOnce` | 全抓得住：M5/M5b/M5c/M7 各 1/1；M18"排了不空"由本棒新用例判红（上一棒这一支是空的） |
| 预算：压缩后 refund | 做了（`BudgetLedger.java` 107 行；`BotAgent` 两处 `refundTokens(…)`） | `compressionRefundsBudgetAndKeepsTheLoopRunning`、`manualCompressRefundsIntoTheLedger`、`abortedTurnReportsTheCallsActuallyMade` | 全抓得住：M2/M3/M4 各 1/1；M19（中断吞账）1/1 |
| prompt cache 冻结 | 做了（构造期一次 `buildSystemPrompt` 快照，易变内容改道 user 消息） | `twoChatsInOneSessionSendByteIdenticalSystemPrompt`、`everyStepOf…`、`volatileContentReaches…`、`midRunMemoryWrite…`、`BotAgentTest.newSessionAndSwitchRestoreHistory` | M12 当场判红（1/2，另一半是等价变异）；M1/M1b 双向都判红 ⇒ 上一棒那条"逐字相等"断言的**形状**是真守住的，不是换成 `contains` 混过去的 |

---

## 3. 杠③：真进程 E2E（`p12_e2e.py`）

### 3.1 命令与形态

派单里写的 `--段=all` 与本文件里的**参数名以文件为准**这条冲突，实测结果：
`p12_e2e.py` 的开关是 `--only <stop|repl|cache|home|creds>`，**不带参数就是全段跑**（`main()` 里
`only in (None, …)`）。所以我用的整跑命令是

```
cd /private/tmp/zbot-wt-p12 && python3 -u _doc/acceptance/p12/p12_e2e.py
```

前置 `mvn -o package -DskipTests -pl z-bot-core`（jar 在 `z-bot-core/target/z-bot-core.jar`，
`find z-bot-core/src/main/java -name '*.java' -newer …jar` 空 ⇒ 被测字节不旧于 jar）。

### 3.2 K2 那条间歇红的裁决：**量具自己坏过，不是产品混进别处来的 key，也不是"按形态数判"**

上一棒 7 次整跑（`logs/e2e_full_run{1..7}.log`，03:39–03:47）的原始 summary 行：

```
段=all 检查条数=21 PASS=12 FAIL=9      ← run1 03:39:19
段=all 检查条数=23 PASS=21 FAIL=2      ← run2 03:43:53
（run3 03:44:32 / run4 03:44:40 无 summary：SyntaxError，脚本压根没起来）
段=all 检查条数=23 PASS=22 FAIL=1      ← run5 03:45:33
段=all 检查条数=23 PASS=22 FAIL=1      ← run6 03:46:29
段=all 检查条数=23 PASS=23 FAIL=0      ← run7 03:47:33
```

run5/run6 唯一那条红就是 K2，报错原文（两次逐字相同）：

```
FAIL  K2 产物里出现的每一种 key 值都只有 stub-key-not-real                    扫了 38 个运行期产物；见到的 key 值=['Bearer stub-key-not-real', 'api.key=stub-key-not-real']
```

**成因取证（不是推测）**：`p12_e2e.py` 的 mtime 是 03:47:05 ⇒ run7 是修好之后的**第一跑**，
run1–run6 跑的是**别的字节**（简报把它们当成"同一形态的量具连跑三次"，这一句与实测不符）。
坏的地方在取值方式，`re_iter()` 的 docstring 已经写着，本棒把它复算成实机读数：
对同一现场（`Bearer stub-key-not-real` 与 `api.key=stub-key-not-real` 并存）

```
旧语义(group(0)，不管有没有捕获组都收整串)算出的取值集合
  = ['Bearer stub-key-not-real', 'api.key=stub-key-not-real']
run5/run6 报错原文里的取值集合
  = ['Bearer stub-key-not-real', 'api.key=stub-key-not-real']
```

两者逐字相同 ⇒ **红是"整串被当成 key 值"造成的**，不是产物里真混进了别处来的 key 形态，
也不是断言按"取值只能有一种形态"判（现判据 `bearer == set([STUB_KEY]) and bool(bearer)`
在"取值=裸 key 值"前提下与"每一种取值都必须等于 stub"逻辑等价，一个都不许多）。
run2 那次是同一族另一种坏法（先截 80 字符窗口再配 ⇒ 配出半截值 `stub-key-`）：

```
FAIL  K2 产物里出现的每一种 Bearer/api-key 值都只有 stub-key-not-real         扫了 44 个产物文件；见到的 key 值=['stub-key-', 'stub-key-not-real']
```

**双向实测**（跑的是仓里那份 `section_creds()` 原函数，只把扫描根 `HERE` 指到
`~/.cache/zbot-p17/probe_k2/harness`，仓里的 `out/`、`logs/` 一个字节都没动；
复算：`python3 -u ~/.cache/zbot-p17/probe_k2/two_way.py`，rc=0）：

```
A  两形态并存（都只含 stub）        期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
A2 同现场再跑一遍（稳定性）         期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
B  混入别的 key 值                 期望=FAIL 实测=FAIL OK  | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
B2 混入别的 Bearer 值              期望=FAIL 实测=FAIL OK  | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
C  只有 stub 一种形态              期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
D  产物里配不到任何 key（空跑）      期望=FAIL 实测=FAIL OK  | 见到的 key 值=[]
F  api.key=not-configured         期望=FAIL 实测=FAIL OK  | 见到的 key 值=['not-configured', 'stub-key-not-real']
```

(a) "两形态并存⇒判绿"与 (b) "塞进别的 key 值⇒判红" **两边都成立** ⇒ 判据没有被调松凑绿；
D 那一支证明它也不吃空跑（零命中⇒FAIL，不是"满分"）。
F 是**假红方向**的残余脆弱（`api.key=<任何 8 字符以上的非 key 串>` 会被当成一种 key 值）：
安全哨兵宁可假红，本棒**不放宽**，如实记在 §5。

**改前/改后通过率（≥5 连跑）**：

| 量具字节 | 命令 | 跑数 | 通过 | 通过率 |
|---|---|---|---|---|
| 改前（run1–run6，03:39–03:46） | `python3 -u _doc/acceptance/p12/p12_e2e.py` | 6 | 0 | **0/6**（其中 2 跑 SyntaxError 崩在脚本自己头上） |
| 改后·批次 c1（03:47 字节，本棒复跑） | 同上，`logs/e2e_c1_run{1..5}.log` | 5 | 5 | **5/5**，逐跑 `段=all 检查条数=23 PASS=23 FAIL=0`、`rc=0` |
| 改后·批次 c2（本棒加了 sha256 读数之后重跑） | 同上，`logs/e2e_c2_run{1..5}.log` | 5 | 5 | **5/5**，逐跑 23/23、`rc=0` |

c1/c2 逐跑 rc 取自索引文件（`logs/e2e_c1_index.txt`、`logs/e2e_c2_index.txt`）：

```
run1 rc=0 / run2 rc=0 / run3 rc=0 / run4 rc=0 / run5 rc=0     （两批各五条，共 10 条）
```

⇒ **run7 的绿不是运气，但只跑过一次确实是测量不足**；本棒补到 10 跑，K2 零复发。
裁决一句话：**这条间歇红是量具 self-bug（已修，修在 run7 之前 28 秒），不是产品缺陷；
判据本身不动。**

### 3.3 其余段的读数

UNKNOWN —— 待最终 HEAD 上再整跑一批，把 stop/repl/cache/home/creds 五段的逐条 PASS 行原文贴这里。

---

## 4. 杠④：`~/.zbot` 零污染

UNKNOWN —— 三个数：`ls -A ~/.zbot | wc -l`=8、config md5 前 8 位=`2dadaed0`、state.db 前 8 位=`690ddbc0`。

---

## 5. 本期发现的产品级缺陷（未修）

UNKNOWN

---

## 6. 量具自己坏过的记录

| # | 哪一条 | 坏法 | 后果（为什么上一棒会误判） | 现状 |
|---|---|---|---|---|
| G1 | 杠③ K2 | `re_iter()` 早期"有捕获组也取 `group(0)`" ⇒ 把整串 `Bearer stub-key-not-real` / `api.key=stub-key-not-real` 当成 key 值收进集合，与 `STUB_KEY` 永远对不上 | run5/run6 各红 1 条，读数长得像"产物里混进了两种形态"；主编据这两条读数写下"同一形态的量具连跑 3 次里 2 次红"，但 6 次红跑用的是**旧字节**、run7 用的是**新字节**，两批不是同一把尺 | 已修（03:47:05 那版）。本棒用 `two_way.py` 双向实测复算，判据不动 |
| G2 | 杠③ K2 | 先把文本截成 80 字符窗口再去配 key ⇒ 窗口切在串中间配出半截值 `stub-key-` | run2 假红（`见到的 key 值=['stub-key-','stub-key-not-real']`） | 已修（改在整篇文本上配） |
| G3 | 杠③ 脚本本体 | run3/run4 那两版正则括号写坏 ⇒ `SyntaxError`，整跑 0 条检查 | 读数里出现"检查条数=0"，容易被当"没跑"或"跑了全绿" | 已修；两跑原文记在 §3.2 |
| G4 | 杠③ K3/K2 扫描面 | 扫描根 `os.walk(HERE)` 会把 **harness 自己的读数文件**（`out/e2e_full.json`、`logs/e2e_full_run1.log`）当运行期产物，而里面逐字写着检查项名字（含 `minimax`）⇒ 哨兵吃自己 | run1 的 `K3 … 命中文件=[…/p12_e2e.py]`、run2 的 `K3 全目录命中=3；其中运行期产物命中=[…/out/e2e_full.json, …/logs/e2e_full_run1.log]` 都是这一条 | 已加排除（`e2e_full`/`e2e_stop_only`/basename 含 `_run`）；**残余风险**见 §5 |
| G5 | 本棒自己的探针 | `two_way.py` 的 D 场景第一版把 `001.headers.json`（内含 stub key）也铺进了现场 ⇒ "产物里一个 key 都配不到"这个前提根本不成立，实测 PASS 被我期望成 FAIL | 差点反过来冤枉 K2"吃空跑"。复测（`key_header=False`）后 K2 判 FAIL，与期望一致 | 已修探针；教训按"坏读数也要复测"记这一条 |
| G6 | 杠② LEDGER | 上一版只跟"本次运行开始时的内存快照"对账；如果邻居留了未提交的脏改动，内存快照本身就是脏的 | 还原取证会给出 `SRC_MD5_STABLE=yes` 却仍是脏盘 | 本棒加了第二把尺：逐支注入前后各比一次 `git show HEAD:<path> | md5`，LEDGER 第 11 列 `restore_forensics_vs_git` |

## 7. 本期没做的（别当成做了）

UNKNOWN

---

## 8. 复算命令清单

UNKNOWN

---

## 9. 与 P24 的交接件：system prompt 冻结回归

UNKNOWN
