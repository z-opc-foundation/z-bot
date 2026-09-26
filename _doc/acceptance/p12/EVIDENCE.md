# P12 验收证据（预算台账 / 中断收口 / steer 排空 / system prompt 快照冻结）

棒：p12c（续 p12b / p12）。分支 `w2-p12`。基线 commit：`809b927`（起）。
本文件是**唯一入仓的证据载体**——`*.log` 被 `.gitignore:5` 排除，所有决定性读数原文粘在下面，并附产生它的命令。

> 状态：四杠读数、台账、缺陷与"没做的"都已落地（不再有 UNKNOWN 节）。每一节填完当场 commit。

---

## 0. 起讫与盘上状态

- 起：`809b927`（主编封存的 p12b 在途产物：+16 条守卫 + 两支量具，未验收）。
- 讫：**`9acc05a`**（本棒最后一笔含读数/量具/台账的改动）。**本节自己的回填在它的下一笔里**，
  那一笔只动 `EVIDENCE.md` 的 §0 与文件头三行文字，不动任何判据、量具、台账、Java
  （复算：`git show --stat HEAD -- _doc/acceptance/p12/EVIDENCE.md` 且
  `git diff 9acc05a..HEAD --name-only` ⇒ 只有本文件）。
- 本棒在 `809b927..9acc05a` 之间动过的文件（**产品码 `src/main` 一字节未动**）：
  `git log --format="" --name-only 809b927..9acc05a | sort | uniq -c | sort -rn` ⇒

  ```
    10 _doc/acceptance/p12/EVIDENCE.md
     4 _doc/acceptance/p12/p12_mutation.py
     2 _doc/acceptance/p12/p12_e2e.py
     2 _doc/acceptance/p12/LEDGER.tsv
     1 z-bot-core/src/test/java/com/zifang/z/bot/agent/P12RefundAndSteerGuardTest.java
     1 _doc/acceptance/p12/p12_k2_probe.py
  ```

- `git status --porcelain`（收尾实测）⇒ **无已跟踪文件的改动**，只剩两个跑产目录未跟踪：

  ```
  ?? _doc/acceptance/p12/logs/
  ?? _doc/acceptance/p12/out/
  ```

  `logs/` 里的 `*.log` 被 `.gitignore:5` 挡住（决定性读数已全部粘进本文件），
  非 `.log` 的索引文件（`*_index.txt`、`lock_probe.txt`）与本棒故意留在盘上的跑产一起不入仓；
  `out/` 是 stub 落盘请求 / profile / E2E 与注入的 JSON 读数，同理。⇒ **人留下的只有本文件与 `LEDGER.tsv`（脚本产物）**。
- 四杠在交付字节上的落点：杠① §1+§1b（`483/0/0/0`×6 跑、`BUILD SUCCESS`、rc=0），
  杠② §2.1b/§2.1c（三遍全量 19 支、判定列零漂移、`SRC_MD5_STABLE=yes`、锁双向 PASS §2.4），
  杠③ §3.2/§3.3b（K2 改后 20/20、交付字节 5 连跑 25/25 rc=0），杠④ §4（8 / `2dadaed0` / `690ddbc0`）。

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
- **这三跑的被测字节 = 最终交付字节**：三跑之后本棒只动过 python 量具与文档，没动过一行 Java。复算
  `git diff --stat 6998e68 HEAD -- z-bot-core` ⇒ 空输出（本棒收尾时复算过，仍是空）。

### 1b 最终字节上的复核三跑（HEAD `5965f80`，本棒第二笔全量重跑之后）

同一形态的串行三跑，在交付 HEAD 上再量一遍（不靠"字节没动所以读数不变"这句推理交差）：

```
cd /private/tmp/zbot-wt-p12
for i in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; \
  mvn -o test > _doc/acceptance/p12/logs/p12c_bar1_final_run$i.log 2>&1; echo "run$i rc=$?"; done
→ run1 rc=0 / run2 rc=0 / run3 rc=0
```

逐字读数（`grep -E "Tests run: 483, Failures: 0, Errors: 0, Skipped: 0|BUILD SUCCESS|Total time" logs/p12c_bar1_final_run<i>.log`）：

```
run1  [INFO] Tests run: 483, Failures: 0, Errors: 0, Skipped: 0   [INFO] BUILD SUCCESS   Total time: 17.017 s   rc=0
run2  [INFO] Tests run: 483, Failures: 0, Errors: 0, Skipped: 0   [INFO] BUILD SUCCESS   Total time: 16.301 s   rc=0
run3  [INFO] Tests run: 483, Failures: 0, Errors: 0, Skipped: 0   [INFO] BUILD SUCCESS   Total time: 15.432 s   rc=0
```

`-- in <类>` 行数三跑都是 **49**（`grep -c -- '-- in ' logs/p12c_bar1_final_run<i>.log`）。
等价性取证（不是推理，是量）：`git diff --stat 6998e68 HEAD -- z-bot-core` ⇒ **空输出**
⇒ §1 那三跑与 §1b 这三跑跑的是同一份 Java 字节，合计 **6 跑 × 483 条 0 红**。

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

### 2.1b 全量第二遍（带真进程层，HEAD `5965f80` 之前一笔的字节）—— 补上"没有第二遍对拍"这笔账

上一版的 §7 第 4 条记着"全量只跑了一轮，单轮内的偶发漏判分不出来"。本棒重跑了一整轮 19 支，
并且这次带 `--with-e2e`（上一轮全量没带 ⇒ M6/M12 只有单测层读数）：

```
cd /private/tmp/zbot-wt-p12 && python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e \
    → _doc/acceptance/p12/logs/p12c_mut_full_run2.log   rc=0（11:17:46 拿锁 → 11:24 收工，19 支）
```

原始 tally 行（同上文件末尾，逐字；与第一遍 `p12c_mut_full_run1.log` 末尾**一字不差**）：

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

**逐支对拍**（把两遍 LEDGER 的 `verdict/named_hit/named_expected/mvn_rc/tests_that_went_red/restore_forensics_vs_git`
六个字段逐行比；复算命令见 §8）⇒ `run1 rows=19 run2 rows=19 / 逐支可比字段全同的支数=19/19`，
差异只有一处：**`e2e_rc` 这一列在第一遍全空，这一遍两支非空**：

```
run2 e2e_rc 非空的支: [('M6', '1'), ('M12', '1')]
```

⇒ 两遍全量互相印证（同一期望集、同一判据规则、零漂移），**不是**改期望集洗出来的。
`LEDGER.tsv` 现在盘上那一版就是这个脚本产物（19 行 + 表头，人一行都没敲）。

两支 `mvn+e2e` 的**真进程层红在哪一条**（`out/mutation-M6.json`、`out/mutation-M12.json` 原文，
这层是单测抓不到的东西）：

```
M6 摘掉工具子进程看门狗 ⇒
  FAIL S2 发 /stop 后工具子进程从内核进程表消失（pgrep -f token = 0 命中）
       复算 pgrep=['61239','61240']；停之前 ps=[61239 SN bash -c sleep 92759786 & wait / 61240 SN sleep 92759786]
  FAIL S3 发 /stop → 工具子进程真退出 ≤ 2000ms（os.waitpid 自己 spawn 的 waiter）
       elapsed_ms=-1，waiter pid=61243 由本进程 spawn、退出条件只有 pgrep 空集这一条
  FAIL S4 那一轮以中止收尾（辅助读数）  chat 回包='(没回包)'

M12 system prompt 快照解冻 ⇒
  FAIL C1 两次 chat 实际发出的 system prompt 逐字节相同（比 stub 落盘的请求原文）
       len1=2345 len2=2372 md5_1=7cf85e3c md5_2=af2f6803
       sha256_1=8b38a41dc2ff8d9737d9fff928ac346701c1249bcaa2c0358f0e640d93d81eec
       sha256_2=2b4de93591fd6dff41a71f39245f8317b1fc500744b8bc408486c9f330a8b216
  FAIL C5 中途写进 SOUL.md 的那一行不进 system prompt  盘上 SOUL.md 含哨兵=True；两轮 system 含哨兵=False/True
```

⇒ §6 G8 那条"进程层等价变异盲点"已经补成**两层都判红**：摘掉冻结 ⇒ 真 JVM 第二发的 prompt 里
真长出了中途写的 SOUL 那一行（`False/True`），摘掉看门狗 ⇒ 工具子进程在进程表里数得到、`/stop` 断不掉。
M12 那对 sha256 同时就是 §9 的阳性对照（冻结在位时两轮都是 `8b38a41d…`，摘掉后第二发变成 `2b4de935…`）。

### 2.1c 全量第三遍（**入库的 `LEDGER.tsv` 就是这一遍的产物**，脚本 = HEAD `4dfce89`）

第二遍之后本棒改了探针（§6 G11），为了让"入库台账出自现脚本"这条复算成立，又跑了一整遍：

```
cd /private/tmp/zbot-wt-p12 && python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e \
    → _doc/acceptance/p12/logs/p12c_mut_full_run3.log   rc=0（首行 LOCK-ACQUIRED，19 支跑完）
```

tally 与前两遍**一字不差**（`RED-OK 14 / PARTIAL 2 / GREEN-BUT-MUTATED 3 / BROKEN 0 / SRC_MD5_STABLE=yes`），
逐支比对三遍的 19 行 × 11 列 ⇒ **只有 2 个单元格不同，都在 `e2e_detail` 这一列的自由文本里**：

```
M6  e2e_detail: run2 的 G0「ready=True 耗时=1116ms」 → run3「耗时=1087ms」
M12 e2e_detail: run2 的 C0「ready=True 耗时=1146ms」 → run3「耗时=1115ms」
```

⇒ 三遍里 `verdict / named_expected / named_hit / tests_that_went_red / mvn_rc / e2e_rc / restored /
restore_forensics_vs_git` **零漂移**，唯一会变的是 JVM 启动耗时这种本来就不该钉的量。
上面 M6/M12 的两层判红读数（S2 `pgrep=['18906','18907']`、S3 `elapsed_ms=-1`、
C1 `sha256_1=8b38a41d… sha256_2=2b4de935…`、C5 `False/True`）在第三遍里**逐字复现**（只有 pid 换人），
复算：`python3 -c "import json;print(*[c['status']+' '+c['name'][:40]+' | '+c['detail'][:150] for c in json.load(open('_doc/acceptance/p12/out/mutation-M12.json'))['checks'] if c['status']!='PASS'],sep='\n')"`。
复算三遍对拍（第一遍在 `git show e69709a:…LEDGER.tsv`，第二遍在
`git show 5965f80:…LEDGER.tsv`，第三遍在盘上）：见 §8 的那段 python。

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
- **flock 真的跨 worktree 互斥（本棒新取证，不是自造探针）**：本棒杠② 第二遍全量在 `11:17:46` 拿到锁、
  `11:24` 收工放锁；`11:24:02` 锁就被**另一个 worktree 的进程**攥走了。复算：

  ```
  $ lsof /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock
    COMMAND   PID   USER  FD TYPE DEVICE NAME
    Python  73653 zifang  3u REG  1,16   .../.git/zbot-mutlock
  $ lsof -p 73653 -a -d cwd -Fn | grep ^n
    n/private/tmp/zbot-wt-p20b                     ← 邻居在**另一个工作树**里，抢的是同一把锁
  $ ps -p 73653 -o lstart,command | tail -1
    Sat Sep 26 11:24:01 2026  Python -u _doc/acceptance/p20b/p20b_mu…
  ```

  这段时间本棒跑 `--lock-probe` ⇒ 探针自己的"邻居进程"也拿不到锁，原文：

  ```
  探针对象: /Users/zifang/…/z-bot/.git/zbot-mutlock（git 公共目录，跨 worktree 有效）
  源码全量 md5 基线: 120 个 .java 文件
    邻居进程: LOCK-BUSY holder：另一支注入脚本攥着 …（[Errno 35] Resource temporarily unavailable）⇒ 本次不跑，一个源文件都没碰 (pid=75367)
  FATAL 邻居没攥住锁          rc=2
  ```

  这一跑**恰好是"拒跑方向"的又一次实测**（被真邻居拒，且 `git status --porcelain` 之后仍只剩
  `logs/`、`out/` ⇒ 一个源文件都没碰），但它不是探针设计的那次双向读数。
- **双向实测（本棒跑成了，两跑都 PASS）**：`--lock-probe` 先用一个自己的子进程 `flock(LOCK_EX|LOCK_NB)`
  攥住 25 s，期间跑一遍本脚本的注入路径 ⇒ 必须拒跑且全量源码 md5 不变；子进程松手后再跑
  `--try-lock-then-exit` ⇒ 必须照常拿到锁。

  ```
  $ python3 -u _doc/acceptance/p12/p12_mutation.py --lock-probe     # rc=0（第二跑 logs/p12c_lock_probe2.log 同读数）
  探针对象: /Users/zifang/…/z-bot/.git/zbot-mutlock（git 公共目录，跨 worktree 有效）
  源码全量 md5 基线: 120 个 .java 文件
    邻居进程: LOCK-HELD 25.0s (pid=9101)
    ① 攥住时跑注入: rc=5
       | LOCK-BUSY mutator：另一支注入脚本攥着 /Users/zifang/…/.git/zbot-mutlock（[Errno 35] Resource temporarily unavailable）⇒ 本次不跑，一个源文件都没碰
    ① 拒跑后全量源码 md5 未变: yes
    ② 松开后跑同一条命令: rc=0
       | LOCK-ACQUIRED probe flock=LOCK_EX|LOCK_NB path=/Users/zifang/…/.git/zbot-mutlock
  == flock 双向实测: 拒跑=True 放行=True ⇒ PASS
  ```

  脚本自己落的机读记录（`logs/lock_probe.txt`）：
  `java_files=120 / refuse_ok=True / acquire_ok=True`。
  **注意这条不是"放宽断言凑绿"**：本棒给 `--hold-lock` 加了一个**有界等待**（`--hold-lock-wait`，缺省 300 s，
  见 §6 G11），因为原来那一版在邻居攥锁时子进程直接放弃 ⇒ 探针自己 `FATAL` 退出、双向读数拿不到
  （11:25 / 11:31 / 11:41 三次就是这么失败的）。等待只发生在**探针的"邻居"子进程**上，
  `MUTANTS` 期望集、锚点预检、四类判定规则、真注入路径的 fail-fast 行为**一行都没动**。复算两条：

  ```
  # ① 本棒这一次改动落在哪些函数上（只有常量 / acquire 对儿 / --hold-lock 分支 / lock_probe）
  $ git diff -- _doc/acceptance/p12/p12_mutation.py | grep -E "^@@"
  @@ -47,6 +47,8 @@ REPORTS = …            ← HOLDER_WAIT 常量
  @@ -306,6 +308,24 @@ def lock_path():  ← 新增 acquire_quiet()
  @@ -405,9 +425,20 @@ def main():      ← --hold-lock 的等待循环
  @@ -549,14 +580,24 @@ def lock_probe(): ← 跳过信息行、LOCK-NOT-OBTAINED 分支
  # ② 改动行里有没有碰过判定/期望集/锚点（应为 0）
  $ git diff -- _doc/acceptance/p12/p12_mutation.py | grep -E "^[+-]" | grep -vE "^(\+\+|---)" \
      | grep -cE "MUTANTS|RED-OK|PARTIAL|GREEN-BUT-MUTATED|BROKEN|锚点|expected|want"
  0
  ```

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
**本棒把这条从"mtime 推断"升级成"git 字节证据"**（mtime 已经被后续跑动覆盖过，不作数）：
封存笔 `809b927` 里那份 `p12_e2e.py` 就是 p12b 临终的工作文件，它**已经带着修复**
（`git show 809b927:_doc/acceptance/p12/p12_e2e.py | grep -n "yield m.group"` ⇒
`744: yield m.group(1) if m.groups() else m.group(0)`）。把同一套双向实测跑在这份封存字节上
（`python3 -u ~/.cache/zbot-p17/probe_k2/two_way_sealed.py`，rc=0，逐字）：

```
被测字节: …/probe_k2/e2e_sealed_809b927.py（= git show 809b927:_doc/acceptance/p12/p12_e2e.py）
A  两形态并存（封存字节）   期望=PASS 实测=PASS OK | 扫了 4 个运行期产物；见到的 key 值=['stub-key-not-real']
B  混入别的 key 值（封存字节） 期望=FAIL 实测=FAIL OK | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
D  配不到任何 key（封存字节）  期望=FAIL 实测=FAIL OK | 见到的 key 值=[]
```

⇒ 封存字节在"两种形态并存"的现场上只收**裸 key 值**、判绿；它**算不出** run5/run6 原文里那两个
带前缀的取值 ⇒ run5/run6 必是更早的字节，run7 才是这份封存字节的第一跑。"同一把尺连跑 3 次红 2 次"
这个前提不成立，但**结论（测量不足）成立**：封存字节到手后也只跑过 1 次。
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
`~/.cache/zbot-p12-k2-probe/harness_*`，仓里的 `out/`、`logs/` 一个字节都没动）。
**这一支本棒已经搬进入仓交付物**：`_doc/acceptance/p12/p12_k2_probe.py`
（原来只在 `~/.cache` 里，仓外 ⇒ 复算式对未来读者不成立；现在 `python3 -u _doc/acceptance/p12/p12_k2_probe.py` 一条命令自解释，
它同时跑**工作树字节**与 `git show 809b927:…p12_e2e.py` 的**封存字节**两套）。
复算：`cd /private/tmp/zbot-wt-p12 && python3 -u _doc/acceptance/p12/p12_k2_probe.py`，rc=0（`logs/p12c_k2_probe.log`）：
```
成因对照（同一现场，两种取值方式算出的 key 值集合）:
  坏语义 group(0)          = ['Bearer stub-key-not-real', 'api.key=stub-key-not-real']
  run5/run6 报错原文的取值 = ['Bearer stub-key-not-real', 'api.key=stub-key-not-real']
  现版语义（有组取值）     = ['stub-key-not-real']
== 被测字节=工作树 p12_e2e.py ==
  A  两形态并存（都只含 stub）        期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
  B  混入别的 key 值                 期望=FAIL 实测=FAIL OK  | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
  B2 混入别的 Bearer 值              期望=FAIL 实测=FAIL OK  | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
  D  产物里配不到任何 key            期望=FAIL 实测=FAIL OK  | 见到的 key 值=[]
  A2 同现场再跑一遍（稳定性）         期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
  C  只有 stub 一种形态              期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
  F  api.key=not-configured（假红侧） 期望=FAIL 实测=FAIL OK  | 见到的 key 值=['not-configured', 'stub-key-not-real']
== 被测字节=git show 809b927:_doc/acceptance/p12/p12_e2e.py（上一棒临终工作文件）==
  同样 7 支，逐项 OK（⇒ 封存字节也只收裸 key 值、判绿）
双向实测结论：全部符合期望 ⇒ (a) 两形态并存判绿 与 (b) 混入别的值判红 两边都成立，判据没有被调松；
              封存字节也算不出带前缀的取值 ⇒ run5/run6 是更早的字节
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
| 改后·批次 p12c（cache 段补 C5/C6 之后，25 条） | 同上，`logs/p12c_e2e_run{1..5}.log` | 5 | 5 | **5/5**，逐跑 25/25、`rc=0`（§3.3） |
| 改后·批次 final（交付字节 HEAD `5965f80`） | 同上，`logs/p12c_e2e_final_run{1..5}.log` | 5 | 5 | **5/5**，逐跑 25/25、`rc=0`，逐跑取值集合都是 `['stub-key-not-real']` |

c1/c2 逐跑 rc 取自索引文件（`logs/e2e_c1_index.txt`、`logs/e2e_c2_index.txt`）：

```
run1 rc=0 / run2 rc=0 / run3 rc=0 / run4 rc=0 / run5 rc=0     （两批各五条，共 10 条）
```

⇒ **run7 的绿不是运气，但只跑过一次确实是测量不足**；连同 §3.3 的两批（各 5 跑）本棒共补到 **20 跑**，
K2 零复发。裁决一句话：**这条间歇红是量具 self-bug（已修，修在 run7 之前 28 秒），不是产品缺陷；
判据本身不动。**

### 3.3 其余段的读数（HEAD `3be6fbd` 上的本棒自跑批次，5 连跑）

前置（jar 必须不旧于源码，否则"测的是旧字节"）：

```
cd /private/tmp/zbot-wt-p12
mvn -o -q package -DskipTests -pl z-bot-core        # logs/p12c_package_clean.log ⇒ package rc=0
for i in 1 2 3 4 5; do python3 -u _doc/acceptance/p12/p12_e2e.py \
  --json _doc/acceptance/p12/out/p12c_e2e_run$i.json \
  > _doc/acceptance/p12/logs/p12c_e2e_run$i.log 2>&1; echo "run$i rc=$?"; done
```

逐跑 summary + rc（`logs/p12c_e2e_index.txt` 原文）：

```
run1 10:56:15 rc=0 段=all 检查条数=25 PASS=25 FAIL=0
run2 10:56:44 rc=0 段=all 检查条数=25 PASS=25 FAIL=0
run3 10:57:14 rc=0 段=all 检查条数=25 PASS=25 FAIL=0
run4 10:57:45 rc=0 段=all 检查条数=25 PASS=25 FAIL=0
run5 10:58:16 rc=0 段=all 检查条数=25 PASS=25 FAIL=0
```

⇒ **K2 在本棒这一批 5 跑里 0 复发**；加上上一棒已有的 `e2e_c1/c2` 两批各 5 跑（§3.2）与本棒交付字节上的
`p12c_e2e_final_*` 5 跑（下面 §3.3b），K2 的"改后"通过率是 **20/20**，改前 0/6。
检查条数从 23 变 25 是本棒给 cache 段补的 C5/C6（§9），
不是把任何一条判据换松或删掉。

run1 全 25 条逐字（`sed -n '/===== summary/,$p' logs/p12c_e2e_run1.log`）：

```
段=all 检查条数=25 PASS=25 FAIL=0
  PASS  [stop]  G0 主 JVM 起来并且 stub LLM 真收到了它的请求
  PASS  [guard] G1 请求没出本机（JVM 日志里不出现任何非 127.0.0.1 的 LLM host）
  PASS  [stop]  S1 工具子进程真的在飞（bash + sleep 两个 pid 数得到）
  PASS  [stop]  S2 发 /stop 后工具子进程从内核进程表消失（pgrep -f token = 0 命中）
  PASS  [stop]  S3 发 /stop → 工具子进程真退出 ≤ 2000ms（os.waitpid 自己 spawn 的 waiter）
  PASS  [stop]  S4 那一轮以中止收尾（辅助读数；主判据是 S2/S3 的进程表）
  PASS  [repl]  R0 真 pty REPL 起得来（JVM 自己打了欢迎面板）
  PASS  [repl]  R2a REPL 这次真的把长命令跑起来了（进程表数得到）
  PASS  [repl]  R2b 反面对照：chat 在飞时写进 pty 的 /stop 不会中断工具（进程还在）
  PASS  [repl]  R1 REPL 自己收得掉（/exit → harness waitpid 拿到退出码）
  PASS  [cache] C0 缓存段用的那台真 JVM 起来了（stub 收到过它的请求）
  PASS  [guard] G1 请求没出本机（JVM 日志里不出现任何非 127.0.0.1 的 LLM host）
  PASS  [cache] C0c 两次 chat 各打了一次 LLM（stub 收到 2 条请求）
  PASS  [cache] C1 两次 chat 实际发出的 system prompt 逐字节相同（比 stub 落盘的请求原文）
  PASS  [cache] C2 运行时上下文（时钟）确实走 user 消息，system 里没有它
  PASS  [cache] C3 两轮之间那个易变时钟确实变了（否则 C1 的「相同」可以是两轮都没注入）
  PASS  [cache] C4 用户原话仍然逐字出现在 user 消息末尾（上下文头没盖过正事）
  PASS  [cache] C5 中途写进 SOUL.md 的那一行不进 system prompt（快照冻结；盘上真有那一行）
  PASS  [cache] C6 反空跑：中途写的那行记忆下一轮真到了模型（在 user 消息里，不在 system 里）
  PASS  [home]  H1 ~/.zbot 项数未变（`ls -A ~/.zbot | wc -l`）
  PASS  [home]  H2 config.properties md5 未变
  PASS  [home]  H3 state.db md5 未变
  PASS  [creds] K1 反向钉住：stub key 真进了产物（出站请求头 = 它，且落盘了）
  PASS  [creds] K2 产物里出现的每一种 key 值都只有 stub-key-not-real
  PASS  [creds] K3 运行期产物里不出现真配置的痕迹（grep minimax 或真 ~/.zbot 绝对路径）
  stub 落盘请求 2 份: …/out/llm-requests
  ~/.zbot 跑前跑后: 项数 8→8, config md5 前缀 2dadaed0, state.db md5 前缀 690ddbc0
```

真进程层的计时读数（**判据全部落在进程表与 `os.waitpid`，没有一条来自 repl 日志行**）：

```
run1 S3 elapsed_ms=33   waiter pid=93195（harness 自己 spawn，退出条件只有 pgrep 空集这一条）
run2 S3 elapsed_ms=92   run3 elapsed_ms=69   run4 elapsed_ms=55   run5 elapsed_ms=71
```

K2 逐跑的扫描面与取值集合（这是"间歇红"那一条的直接复算）：

```
run1  扫了 69 个运行期产物；见到的 key 值=['stub-key-not-real']
run2  扫了 70 个运行期产物；见到的 key 值=['stub-key-not-real']
run3  扫了 70 个运行期产物；见到的 key 值=['stub-key-not-real']
run4  扫了 70 个运行期产物；见到的 key 值=['stub-key-not-real']
run5  扫了 70 个运行期产物；见到的 key 值=['stub-key-not-real']
```

杠④ 的逐跑读数就在每跑 summary 末行（`8→8 / 2dadaed0 / 690ddbc0`，五跑逐字相同），§4 另有独立三数的直接量法。

### 3.3b 交付字节上的第五批 5 连跑（HEAD `5965f80`，杠② 第二遍全量之后重打）

上面 §3.3 那批跑在 `3be6fbd`；本棒把杠② 重跑了一遍并入库了 K2 探针，交付 HEAD 已经不是 `3be6fbd`，
所以整跑在**最终字节**上再打一遍（前置 `mvn -o -q package -DskipTests -pl z-bot-core` ⇒ rc=0，
`find z-bot-core/src/main/java -name '*.java' -newer z-bot-core/target/z-bot-core.jar` ⇒ 空，jar 不旧于源码）：

```
cd /private/tmp/zbot-wt-p12
for i in 1 2 3 4 5; do python3 -u _doc/acceptance/p12/p12_e2e.py \
  --json _doc/acceptance/p12/out/p12c_e2e_final_run$i.json \
  > _doc/acceptance/p12/logs/p12c_e2e_final_run$i.log 2>&1; echo "run$i rc=$?"; done
```

逐跑 summary + rc + K2 取值集合（`logs/p12c_e2e_final_index.txt` 原文，11:27:03–11:28:59）：

```
run1 11:27:03 rc=0 段=all 检查条数=25 PASS=25 FAIL=0 | 见到的 key 值=['stub-key-not-real']
run2 11:27:32 rc=0 段=all 检查条数=25 PASS=25 FAIL=0 | 见到的 key 值=['stub-key-not-real']
run3 11:28:02 rc=0 段=all 检查条数=25 PASS=25 FAIL=0 | 见到的 key 值=['stub-key-not-real']
run4 11:28:31 rc=0 段=all 检查条数=25 PASS=25 FAIL=0 | 见到的 key 值=['stub-key-not-real']
run5 11:28:59 rc=0 段=all 检查条数=25 PASS=25 FAIL=0 | 见到的 key 值=['stub-key-not-real']
```

真进程层计时与杠④ 的逐跑读数（同一批；`grep -hE "S3 |~/.zbot 跑前跑后" logs/p12c_e2e_final_run*.log`）：

```
S3 elapsed_ms=77 (run1) / 76 (run2) / 81 (run3) / 79 (run4) / 50 (run5)   阈值 2000ms，判据是 pgrep 空集 + 自 spawn 的 waitpid
~/.zbot 跑前跑后: 项数 8→8, config md5 前缀 2dadaed0, state.db md5 前缀 690ddbc0     （五跑逐字相同）
```

⇒ 杠③ 在**交付 HEAD** 上的通过率 5/5，K2 零复发；每一跑都跑在 `ZBOT_HOME=<仓内 out/profile-*>`
（`p12_e2e.py:308` 就是这句 `env["ZBOT_HOME"] = profile`），**没有一跑指向 `~/.zbot`**。

---

## 4. 杠④：`~/.zbot` 零污染

三个数（本棒亲测，命令原文；**没读、没打印、没复制任何 key 值**）：

```
$ ls -A ~/.zbot | wc -l
       8
$ md5 -q ~/.zbot/config.properties | cut -c1-8
2dadaed0
$ md5 -q ~/.zbot/state.db | cut -c1-8
690ddbc0
```

- 那 8 项逐字：`.stty.bak config.properties cron memories models-cache.json sessions state.db workspace`
  （`ls -A ~/.zbot`）。与简报里"上一棒的账"给的三个数**逐字相同** ⇒ 本期所有跑动（杠①②③）都没碰过真家目录。
- 真 key 的长度只量了长度：`python3 -c` 取 `minimax.api.key` 的 value 打印 `len()` ⇒ `125`
  （值本身一个字节都没落到任何输出/文件里；这条读数的用途只是核对简报说的"125 字符"）。
- E2E 侧的同一件事由 `p12_e2e.py` 的 home 段自己判（H1/H2/H3，跑前跑后各取一次）；
  每一次 E2E 都用 `--config-dir <仓内 out/profile-*>`，**没有一处指向 `~/.zbot`**，逐跑读数见 §3.3 末行
  `~/.zbot 跑前跑后: 项数 8→8, config md5 前缀 2dadaed0, state.db md5 前缀 690ddbc0`。
- 复算：`python3 -u _doc/acceptance/p12/p12_e2e.py --only home`（只跑 home 段，同样不动真目录）。

---

## 5. 本期发现的产品级缺陷（未修）

只报**量得出来**的；杠② 那三条 GREEN-BUT-MUTATED 属"判据缺口"不是产品缺陷，记在 §2.2，不在这里混。

- **D1｜中止那一轮与预算耗尽那一轮的 token 账根本不上报、也不落库**（apiCalls 报了，token 没报）。
  字节层读数：`BotAgent.java:262`（`catch (AgentInterruptedException)` 分支）与 `:352`（预算耗尽分支）
  都是 `new StreamEvent.Done(…, context.budget().apiCalls(), null, null)` —— 后两个 `null` 就是
  prompt/completion token；而 `recordSessionUsage(steps, prompt, completion)` 全仓只有
  **一处**调用点（`BotAgent.java:1515`，正常收尾路径）。
  复算：`grep -n "recordSessionUsage" z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java`
  ⇒ `1515:        recordSessionUsage(steps, prompt, completion);` 与 `1521` 的定义，没有第三处。
  后果：用户按 `/stop` 打断一长串工具调用之后，那一轮实际烧掉的 token 在 Done 事件里是空的、
  在 session 用量里也没入账 —— M19（`abortedTurnReportsTheCallsActuallyMade`）钉住的是 apiCalls 那一半，
  token 这一半**没有任何断言**。本棒没动产品码，交主编定口径。
- **D2｜中止那一轮不走 post-chat 钩子**（记忆沉淀/后续动作那一类）。
  `chat()` 的正常路径是 `runReActLoop → persistSession → firePostChat`（`BotAgent.java:252-254`），
  中止路径只有 `persistSession()`（`:260-263`），确认等待路径（`:256-258`）也没有。
  复算：`grep -n "firePostChat" z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java`。
  这是"有意还是漏"本棒判不了（没有测试、也没有注入钉它），只把形状记下来。

---

## 6. 量具自己坏过的记录

| # | 哪一条 | 坏法 | 后果（为什么上一棒会误判） | 现状 |
|---|---|---|---|---|
| G1 | 杠③ K2 | `re_iter()` 早期"有捕获组也取 `group(0)`" ⇒ 把整串 `Bearer stub-key-not-real` / `api.key=stub-key-not-real` 当成 key 值收进集合，与 `STUB_KEY` 永远对不上 | run5/run6 各红 1 条，读数长得像"产物里混进了两种形态"；主编据这两条读数写下"同一形态的量具连跑 3 次里 2 次红"，但 6 次红跑用的是**旧字节**、run7 用的是**新字节**，两批不是同一把尺 | 已修（03:47:05 那版）。本棒用 `two_way.py` 双向实测复算，判据不动 |
| G2 | 杠③ K2 | 先把文本截成 80 字符窗口再去配 key ⇒ 窗口切在串中间配出半截值 `stub-key-` | run2 假红（`见到的 key 值=['stub-key-','stub-key-not-real']`） | 已修（改在整篇文本上配） |
| G3 | 杠③ 脚本本体 | run3/run4 那两版正则括号写坏 ⇒ `SyntaxError`，整跑 0 条检查 | 读数里出现"检查条数=0"，容易被当"没跑"或"跑了全绿" | 已修；两跑原文记在 §3.2 |
| G4 | 杠③ K3/K2 扫描面 | 扫描根 `os.walk(HERE)` 会把 **harness 自己的读数文件**（`out/e2e_full.json`、`logs/e2e_full_run1.log`）当运行期产物，而里面逐字写着检查项名字（含 `minimax`）⇒ 哨兵吃自己 | run1 的 `K3 … 命中文件=[…/p12_e2e.py]`、run2 的 `K3 全目录命中=3；其中运行期产物命中=[…/out/e2e_full.json, …/logs/e2e_full_run1.log]` 都是这一条 | 已加排除（`e2e_full`/`e2e_stop_only`/basename 含 `_run`）；**残余风险**见 §5 |
| G5 | 本棒自己的探针 | `two_way.py` 的 D 场景第一版把 `001.headers.json`（内含 stub key）也铺进了现场 ⇒ "产物里一个 key 都配不到"这个前提根本不成立，实测 PASS 被我期望成 FAIL | 差点反过来冤枉 K2"吃空跑"。复测（`key_header=False`）后 K2 判 FAIL，与期望一致 | 已修探针；教训按"坏读数也要复测"记这一条 |
| G6 | 杠② LEDGER | 上一版只跟"本次运行开始时的内存快照"对账；如果邻居留了未提交的脏改动，内存快照本身就是脏的 | 还原取证会给出 `SRC_MD5_STABLE=yes` 却仍是脏盘 | 本棒加了第二把尺：逐支注入前后各比一次 `git show HEAD:<path> | md5`，LEDGER 第 11 列 `restore_forensics_vs_git`（全 19 行 `before=ok after=ok`） |
| G7 | 杠② 判定规则 | `if hit and not extra: RED-OK` —— 只看"点名的红了没 + 有没有多红"，**不看有没有点名没打满** | M1 2/3、M12 1/2、M18 1/2 都被记成 RED-OK，读者会以为"点名的三条全红了" | 本棒**没改这条规则**（改了就跟上一棒的台账不可比），改为在 §2.3 把这三条"没打满的那一半"逐条落地给因果 |
| G8 | 杠③ cache 段 C1 | 只连打两次 chat、**两次之间不写盘** ⇒ "每步按盘重建"与"冻结快照"算出同一份字节 | `--with-e2e M12` 第一跑 `e2e_rc=0`（`logs/p12c_mut_withe2e_M6_M12.log`）：把冻结整个摘掉，真进程层照样全绿 ⇒ C1 在进程层是等价变异盲点 | 本棒补 C5（中途写 `SOUL.md` 那一行不许进 prompt，且先证盘上真有那一行）+ C6（中途写的记忆下一轮在 **user** 消息里，反 C5 的空跑），并把 M12 的 kind 改成 `mvn+e2e` 走 cache 段 ⇒ 阳性对照见 §9 |
| G9 | 杠③ K2/K3 扫描面 | 排除规则用 basename 含 `_run` 来挡 harness 自己的读数文件 | 万一**真**运行期产物名字里带 `_run`，它会被排除在凭证扫描之外（哨兵看不见它） | 本棒没放宽、也没重写这条启发式；排除清单在读数里逐条报出（`K3 … 未纳入扫描的 harness 读数文件=[…]`），残余风险只记在这里 |
| G10 | 杠② LEDGER 写出方式 | `LEDGER.tsv` 每次运行**整体重写**，且只写本次跑到的那些支 ⇒ 跑子集（`--with-e2e M12` 这种单支复打）会静默把 19 行台账压成 1 行 | 本棒接手时盘上就是这样：`git status` 显示 `M LEDGER.tsv`，diff 里 18 行被删、只剩表头 + M12 一行。要不是它有未提交的 `M` 标记，这份"1 支的台账"就会被当成"全量台账"入库 ⇒ **子集跑不是不能跑，是不能拿它的产物当交付物** | 本棒没有改脚本的写出方式（改了台账格式就没法跟上一棒比），而是**重跑一遍 19 支全量**（`--with-e2e`）让 `LEDGER.tsv` 重新成为全量脚本产物（§2.1b），并把上一棒那 18 行的读数用 `git show e69709a:…LEDGER.tsv` 留住做逐支对拍 |
| G11 | 杠② `--lock-probe` 的"邻居"子进程 | `--hold-lock` 一次 `LOCK_NB` 拿不到就直接 `return 4` ⇒ 只要邻居（别的 worktree 的注入脚本）在飞，探针自己的攥锁步骤就失败，`lock_probe()` 打一句 `FATAL 邻居没攥住锁` rc=2/3 退出，**双向读数永远拿不到**（上一棒与本作前面的 4 次失败全是这个形状，不是锁坏） | 现象长得像"锁不可用/脚本坏了"，很容易被记成"锁这条杠没法自证" | 本棒给 `--hold-lock` 加 `--hold-lock-wait`（缺省 300 s，每 0.5 s 重试一次，等不到就报 `LOCK-NOT-OBTAINED` 且不碰任何源文件），探针读输出时跳过 `HOLDER-WAIT` 这类信息行 ⇒ 同一天 12:0x / 12:1x 两跑都是 `拒跑=True 放行=True ⇒ PASS`（§2.4）。**只动了探针的等待，没动判定规则与期望集**（复算见 §2.4 末） |

## 7. 本期没做的（别当成做了）

1. **杠② 的 `--lock-probe` 双向实测本棒跑成了**（两跑 `rc=0`、`拒跑=True 放行=True ⇒ PASS`，原文 §2.4），
   上一棒记的这条 UNKNOWN 已经落地。仍然**没做**的是两件相邻的事：
   (a) 探针的"邻居"是**本棒自己 spawn 的持锁子进程**，不是与真兄弟棒（`zbot-wt-p20b` 的
   `p20b_mutation.py`）**同一时刻**对拍 —— 真邻居那一面只有"我们互相拒跑"的散点读数
   （`attempt1..13 rc=5` / 11:24:02 的 `lsof` 交接，§2.4），没有编队层的"两支同时开跑、必有一支等到"的实验；
   (b) 没测**阻塞式**等待（本脚本一律 `LOCK_NB`，这是刻意的：拿不到就退出记账，但"排队拿锁"这条路径不存在）。
   另外 G11 那个 `--hold-lock-wait` 是本棒为了跑成 (a) 里"能开始"而加的，
   它改的是探针的等待，不是判定 ⇒ 但**这一改之后 `--lock-probe` 与上一棒的 4 次失败不再是同一把尺**，
   用旧脚本复算的人要先把这个差异知道。
2. **M8 / M9 / M17 三支 GREEN-BUT-MUTATED 只给了因果，没补判据**。改法本棒已经想清楚但没落地：
   M8 ⇒ 让命令先 `touch <token>.marker`，"起没起过进程"才留得下痕迹；M9 ⇒ 读一个 **0 行**文件
   （循环体一次都不进，只有入口检查点会断）；M17 ⇒ 两次绑定必须**时间重叠**
   （A 卡在工具里、B 在同刻 `bind+request`），现在这个"B 收工 A 才上"的时序永远量不出来。
   ⇒ 结论：**"已经按了停止就不该再起进程"、"文件读入口检查点"、"旗子按线程定向"这三条守卫
   今天仍然只有实现、没有能判红的观测**，别当成验收了。
3. **M16 想钉的那条测试（`interruptInsideParallelToolBatch…`）判不了红**，要抓它得注入
   `InterruptFlag.checkpoint()` 本体 —— 那个类在 `z-agent-kernel`，本期红线不许动内核仓 ⇒ 未覆盖。
4. **杠② 全量已经跑到第三遍**（`e69709a` 的一遍 + 本棒 `--with-e2e` 的两遍，见 §2.1b/§2.1c：
   三遍 19 支的判定列零漂移）。仍然没做的是：
   (a) 三遍都在**同一台机器、同一段负载窗口**里，没有"换机/换负载/并发邻居下"的重复；
   (b) 真进程层（`e2e_rc`）只有 **M6/M12 两支**有值 —— 另外 17 支的 kind 是 `mvn`，设计上不进 E2E，
   所以"摘掉 steer 的 `clear()` 之后真进程 REPL 会不会露出来"这类问题今天仍然没有第二层读数；
   (c) 判定规则 `if hit and not extra`（§6 G7）没改 ⇒ "RED-OK 但点名没打满"这种半覆盖还得靠人读 §2.3。
5. **D1/D2（§5 的 token 账与 post-chat）没修**，本棒一行产品码都没动（只动测试与量具）。
6. **E2E 的 `STOP_LIMIT_MS=2000`、R2b 那条 pty 反面对照的语义**沿用上一棒设定，本棒没重估；
   实测余量很大（§3.3 五跑 33–92 ms、§3.3b 五跑 50–81 ms），但没有多机分布数据支撑这个阈值。
7. **`--with-e2e` 只覆盖 stop / cache 两段**：repl 段（真 pty）与 home/creds 段没进注入回路，
   也就是说"摘掉某个检查点之后 REPL 那条 pty 反面对照会不会变红"今天没有读数。
8. **没做集成**：不 push、不合 `main`、不动兄弟 worktree（`w2-p16ev` / `w2-p20b` / `w1-p15b`）、不动内核仓。
   `_doc/acceptance/p12/logs/`、`out/` 是跑动产物，`*.log` 被 `.gitignore:5` 排除 ⇒ 没进仓，
   决定性读数已全部粘进本文件。

---

## 8. 复算命令清单

```
# 起讫与盘上状态
git -C /private/tmp/zbot-wt-p12 log --oneline -6 && git -C /private/tmp/zbot-wt-p12 status --porcelain

# 杠①（三跑，每跑 ~17–30 s；本棒跑了两批：logs/p12c_bar1_run*（HEAD 6998e68）与 p12c_bar1_final_run*（HEAD 5965f80））
cd /private/tmp/zbot-wt-p12
for i in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; \
  mvn -o test > _doc/acceptance/p12/logs/p12c_bar1_final_run$i.log 2>&1; echo "rc=$?"; done
grep -hE "Tests run: [0-9]+, Failures|BUILD (SUCCESS|FAILURE)|Total time" _doc/acceptance/p12/logs/p12c_bar1_final_run*.log
grep -c -- '-- in ' _doc/acceptance/p12/logs/p12c_bar1_final_run1.log          # 49 个测试类
git diff --stat 6998e68 HEAD -- z-bot-core                                     # 空 ⇒ 两批跑的是同一份 Java 字节

# 杠②（全量一轮 19 支；独占 flock。第一遍不带 e2e ≈4 分钟，第二遍带 --with-e2e ≈7 分钟）
python3 -u _doc/acceptance/p12/p12_mutation.py                 # → logs/p12c_mut_full_run1.log
python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e      # → logs/p12c_mut_full_run2.log（入库的 LEDGER.tsv 出自这一遍）
column -t -s $'\t' _doc/acceptance/p12/LEDGER.tsv              # 只读台账，不许手改
python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e M6 M12   # 单独复打真进程层两支（stop / cache）
python3 -u _doc/acceptance/p12/p12_mutation.py --lock-probe    # flock 双向实测：本棒两跑 rc=0 ⇒ PASS（§2.4）
# 三遍全量逐支对拍（本棒读数：19/19 判定列全同，只有 2 个 e2e_detail 单元格的耗时数字不同）
python3 - <<'PY'
import csv, io, subprocess
def load(spec, path='_doc/acceptance/p12/LEDGER.tsv'):
    raw = subprocess.check_output(['git','show','%s:%s'%(spec,path)]).decode() if spec \
          else open(path).read()
    return {r['id']: r for r in csv.DictReader(io.StringIO(raw), delimiter='\t')}
runs = {'run1(e69709a)': load('e69709a'), 'run2(5965f80)': load('5965f80'), 'run3(盘上)': load(None)}
cols = list(next(iter(runs['run3(盘上)'].values())).keys())   # 取"列名"，不是行 id
for a, b in [('run1(e69709a)','run2(5965f80)'), ('run2(5965f80)','run3(盘上)')]:
    diff = [(i, c) for i in runs[a] for c in cols if runs[a][i][c] != runs[b].get(i, {}).get(c)]
    print('%s vs %s: rows=%d/%d 不同的单元格=%s' % (a, b, len(runs[a]), len(runs[b]),
          diff or '无（逐字节全同）'))
print('e2e_rc 非空:', [(r['id'].split()[0], r['e2e_rc']) for r in runs['run3(盘上)'].values() if r['e2e_rc']])
PY

# 杠③（真进程 E2E，不带 --only 就是全 25 条；约 30 s/跑）
mvn -o -q package -DskipTests -pl z-bot-core
find z-bot-core/src/main/java -name '*.java' -newer z-bot-core/target/z-bot-core.jar   # 必须空
for i in 1 2 3 4 5; do python3 -u _doc/acceptance/p12/p12_e2e.py \
  --json _doc/acceptance/p12/out/p12c_e2e_final_run$i.json \
  > _doc/acceptance/p12/logs/p12c_e2e_final_run$i.log 2>&1; echo "run$i rc=$?"; done
grep -h "^段=all" _doc/acceptance/p12/logs/p12c_e2e_final_run*.log

# 杠③ K2 的双向实测（**已入仓**：跑的是仓里那份 section_creds()，只把扫描根指到 ~/.cache/zbot-p12-k2-probe/）
python3 -u _doc/acceptance/p12/p12_k2_probe.py                 # rc=0 ⇒ (a)判绿 (b)判红 两边都成立；含封存字节 809b927 一套

# 杠④（三个数，不读不打印 key 值）
ls -A ~/.zbot | wc -l; md5 -q ~/.zbot/config.properties | cut -c1-8; md5 -q ~/.zbot/state.db | cut -c1-8

# §5 D1/D2 的字节层复算
grep -n "recordSessionUsage\|firePostChat" z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java

# §9 P24 交接件（C1/C5 的 sha256 读数）
grep -h "^PASS  C1 \|^PASS  C5 " _doc/acceptance/p12/logs/p12c_e2e_final_run1.log
python3 -c "import json;d=json.load(open('_doc/acceptance/p12/out/mutation-M12.json'));print(*['%s %s | %s'%(c['status'],c['name'][:44],c['detail'][:150]) for c in d['checks'] if c['status']!='PASS'],sep='\n')"
# 阳性对照：摘掉冻结之后真进程层哪几条判红（M6 那支看 out/mutation-M6.json 的 S2/S3）
```

---

## 9. 与 P24 的交接件：system prompt 冻结回归

**这条回归的语义**：会话一旦起建，`system` 那段就是一份冻结快照（`BotAgent.java:175`
`this.memory = new ConversationMemory(buildSystemPrompt(…))` 只在构造期走一次）；
中途往盘上写 SOUL/记忆/技能，只能改道进 **user** 消息，绝不能改 system 的字节。
P24 要把"记忆/血统/center 召回"继续往外挪，靠的就是这块不动的地基。

### 9.1 命令（P24 直接复用，一条）

```
cd /private/tmp/zbot-wt-p12
mvn -o -q package -DskipTests -pl z-bot-core && python3 -u _doc/acceptance/p12/p12_e2e.py --only cache
```

它做的是：真 JVM（`java -jar z-bot-core.jar gateway --config-dir <仓内 out/profile-cache>`，
key 只用 `stub-key-not-real`）→ 第一次 chat → **中途写盘**（`memories/SOUL.md` 追加一行哨兵、
`memories/MEMORY.md` 覆写一行哨兵）→ 第二次 chat → 从 **stub 落盘的请求原文**
（`out/llm-requests/00N.json`，不是被测自述）取两轮实际发出去的 system 字节比 sha256。

### 9.2 冻结在位时：两轮 sha256 相同（本棒五跑逐字相同）

```
run1  C1 … len1=2345 len2=2345 md5_1=7cf85e3c md5_2=7cf85e3c
      sha256_1=8b38a41dc2ff8d9737d9fff928ac346701c1249bcaa2c0358f0e640d93d81eec
      sha256_2=8b38a41dc2ff8d9737d9fff928ac346701c1249bcaa2c0358f0e640d93d81eec
run2–run5 同（logs/p12c_e2e_run{2..5}.log 的 C1 行逐字相同）
C5 中途写进 SOUL.md 的那一行不进 system prompt … 盘上 SOUL.md 含哨兵=True；两轮 system 含哨兵=False/False
C6 反空跑：中途写的那行记忆下一轮真到了模型 … user2 含记忆哨兵=True；system2 含记忆哨兵=False；user1 含记忆哨兵=False
```

### 9.3 阳性对照（把冻结摘掉 ⇒ 哈希必须变）

- 单测层（已实测）：`python3 -u _doc/acceptance/p12/p12_mutation.py` 里的 **M12**
  （`SystemPromptCacheFreezeTest.java:83` 的 `assertArrayEquals(first, second)` 当场判红，
  红在 `twoChatsInOneSessionSendByteIdenticalSystemPrompt`）。
- **真进程层（本棒已实测，不再是 UNKNOWN）**：杠② 全量第二遍带 `--with-e2e` 时 M12 拿到锁跑完了，
  `e2e_rc=1`，红在 C1 与 C5（`out/mutation-M12.json` 原文）：

  ```
  FAIL C1 两次 chat 实际发出的 system prompt 逐字节相同（比 stub 落盘的请求原文）
       len1=2345 len2=2372 md5_1=7cf85e3c md5_2=af2f6803
       sha256_1=8b38a41dc2ff8d9737d9fff928ac346701c1249bcaa2c0358f0e640d93d81eec   ← 冻结在位时两轮都是这个
       sha256_2=2b4de93591fd6dff41a71f39245f8317b1fc500744b8bc408486c9f330a8b216   ← 摘掉冻结后第二发变了
  FAIL C5 中途写进 SOUL.md 的那一行不进 system prompt（快照冻结；盘上真有那一行）
       盘上 SOUL.md 含哨兵=True；两轮 system 含哨兵=False/True
  ```

  复算命令：`python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e M12`（要独占 flock；
  拿到锁后期望 `e2e_rc≠0`、C1 的 `sha256_1 != sha256_2`、C5 的 `False/True`）。
  同一支在 `10:52` 那一跑（§6 G8）还是 `e2e_rc=0`，因为当时 cache 段没有"中途写盘"这个动作；
  本棒补了 C5/C6 之后这一支才有真读数。
- **P24 必须知道的反面事实**：只复用 C1 是**无效回归** —— 旧版 cache 段（只连打两次 chat、中间不写盘）
  在摘掉冻结之后仍然全绿（`logs/p12c_mut_withe2e_M6_M12.log` 里 `e2e_rc=0`），因为"每步按盘重建"
  与"冻结快照"在那个现场算出同一份字节。必须连 C5/C6 那种"中途真写盘 + 先证盘上那一行真在"
  的动作一起用（§6 G8）。

### 9.4 现字节上的接线行号（简报里 `:273/:465/:914` 是 `b64d294` 的数，这里给复算后的）

```
$ git grep -n 'context.steer()' -- z-bot-core/src/main/java
BotAgent.java:273  List<String> queued = context.steer().drain();      # /queue 侧（mergeQueued）
BotAgent.java:465  List<String> pending = context.steer().drain();     # 工具缝侧（injectSteer）
BotAgent.java:914  context.steer().clear();                            # 硬中断丢弃 pending
BotAgent.java:933/934/943/948/1145 …                                  # steer() 单槽写入端与 hasPending
$ git grep -n 'refundTokens' -- z-bot-core/src/main/java
BotAgent.java:407  long refunded = budgetLedger.refundTokens(freedTokensOfCompression(history, compressed));
BotAgent.java:979  long refunded = budgetLedger.refundTokens(freedTokensOfCompression(history, out));
```

---

# §10 p12d 棒：合并树四杠收口（本节起为本棒产出）

> 本节由写手棒 p12d 产出。被测对象＝**合并树** `f56724d5b9ed6f1aa9f05065db34ac1f3b368910`
> （= `git merge-tree --write-tree 926b8b5 e74f49c` 的单行输出，单行⇒无冲突）。
> 上一棒（§0–§9）的读数全部跑在 `e74f49c` 基线上，`@Test` 面只有 483；本棒把四杠重跑到 637 面上。
> 骨架先落盘再逐杠填数——每杠填完即 commit。

## 10.0 第 0 步实测（证伪主编假设）

| 假设 | 复算命令 | 实测 |
|---|---|---|
| `merge-tree` 单行=无冲突 | `git merge-tree --write-tree 926b8b5 e74f49c \| wc -l` | **1 行**，树 `f56724d5b9ed6f1aa9f05065db34ac1f3b368910` ⇒ **证实** |
| 合并树 `@Test`=637 | `git grep -c '@Test' f56724d… -- 'z-bot-core/src/test' \| awk -F: '{s+=$NF} END{print s}'` | **637** ⇒ 证实 |
| main `@Test`=619 / 本分支 `@Test`=483 | 同命令分别跑 `926b8b5` / `e74f49c` | **619 / 483** ⇒ 证实 |
| LEDGER verdict 在第 2 列、19 行 | `head -1 LEDGER.tsv`（列序）＋ `awk -F'\t' 'NR>1{c[$2]+=1} END{for(k in c) print k,c[k]}'` | 表头第 2 列名就是 `verdict`；`NF=11` 全行一致；行数 **19** ⇒ 证实。旧 LEDGER 分档：RED-OK 14 / PARTIAL 2 / GREEN-BUT-MUTATED 3（跑在 483 面上，本棒 §10.2 重算） |
| K2 探针跑不跑 | `python3 _doc/acceptance/p12/p12_k2_probe.py; echo rc=$?` | **rc=0**，7/7 case 符合期望 ⇒ **K2 判据本身可信**（详见 §10.3 原文） |
| 合并树=预览树 | `git diff --name-only f56724d… HEAD` | 只差 `_doc/acceptance/p12/EVIDENCE.md`（本棒骨架，纯文档）⇒ **Java 字节与预览树逐一同源** |

**结论：主编的四条假设全部证实，无一被证伪。**

## 10.1 杠① 全量单测串行三跑（合并树，61 个测试类 / 637 条）

复算：`bash ~/.cache/zbot-p12d/bar1.sh`（每跑前 `rm -rf z-bot-core/target/surefire-reports`；三跑**串行**，非并行凑数）。
日志：`~/.cache/zbot-p17/p12d_bar1_r{1,2,3}.log`（`.gitignore:5` 是 `*.log` ⇒ 日志不入仓，决定性读数原样贴在下面）。

| 项 | 复算命令 | 实测 |
|---|---|---|
| 三跑 `Tests run:` | `grep -hE '^Tests run: .*Skipped' <log>`（surefire `Results:` 汇总行） | r1=**637, Failures: 1, Errors: 0, Skipped: 0** / r2=**同** / r3=**同** ⇒ **数字三跑一致，但一致地不绿** |
| BUILD 结果 | `grep -c 'BUILD SUCCESS' <log>` | 三跑均 **0**（`MVN_EXIT_RUN_{1,2,3}=1`，三跑同时刻的墙钟 13:02:56 / 13:03:15 / 13:03:34） |
| 逐类求和交叉核对 | 解析 `-- in <class>` 行求和 | 61 个类 / tests=637 / failures=1 / errors=0 / skipped=0（与汇总行自洽） |
| **`@Test`(git grep) 与 surefire 对账** | `git grep -l '@Test' HEAD -- 'z-bot-core/src/test' \| wc -l` = 61 文件；surefire 类数 = 61；surefire tests = 637 = `git grep -c '@Test'` 的 637 | **逐字相等 ⇒ 无漏收测试类、`z-bot-desktop-packager` 模块贡献 0 个测试类（`classes=0`）**。注意：本棒合并前旧基线是 483(@Test 行) vs 481(surefire) 的 2 条差，合并后归零 ⇒ 差值来自 §上一棒的量具口径，不是本面 |
| socket 类错误计数 | `grep -c 'java.net.BindException\|Connection refused\|SocketTimeout' <log>` | r1/r2/r3 均 **0** ⇒ 红与端口/网络无关，不是环境抖动 |
| 红的那一条 | `grep -hE '^\[ERROR\] .*(FAILURE|ERROR)$' <log> \| sort -u` | 三跑**同一条且唯一一条**：`com.zifang.z.bot.channel.GatewayDeliveryP16Test.graftedChatsShareOneSessionAndSerializeWithoutWedging` |

### 10.1.1 这条红是判决性的：P12 的上下文前缀撞掉了 main 的 channel 断言

`GatewayDeliveryP16Test.java:381`（该文件由 main `926b8b5` 带入，**不在本棒写域内**）：

```java
assertTrue("回显对不上说明两条会话串味了: " + m.text, m.text.startsWith("echo: A"));
```

失败原文（`p12d_bar1_r1.log:313`，逐字）：

```
java.lang.AssertionError:
回显对不上说明两条会话串味了: echo: [z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）
当前时间: 2026-09-26 13:02:45 +08:00 GMT+08:00
---
A0
```

- 断言挂在 `startsWith("echo: A")`，而 reply 现在是 `echo: <P12 的运行时上下文头>\n---\nA0` —— **不是串味，是 P12 把上下文头注进了 user 消息**，main 那条回显断言没按 P12 的协议剥头。
- 判据"串味"本身没坏：同一次跑里 `assertEquals(4, chan.sent.size())`、`leaseTimeoutCount()=0` 等都过了，只有回显前缀这一条挂。

## 10.2–10.5 骨架小节的去处（主编 09-26 标注，避免"骨架未填"被读成"这一杠没跑"）

> 本棒按工单要求"骨架先落盘再逐杠填数"，填完的读数写在各小节号下（本节末尾的 §10.2 两层表、
> §10.3.1、§10.3.2、§10.3.3、§10.4、§10.5、§10.6），上面这几张空骨架表因此一直留着 `（待填）` 没回收。

| 骨架行 | 实际读数在哪 |
|---|---|
| LEDGER 各档计数 | 本节末 §10.2（`--with-e2e` 两层，637 面）；**并入 main@dd0d9c5 之后（693 面）由主编重跑，见 §12.2** |
| E2E 三跑条数 | §10.3.1（27 条 ×3；run2 那 1 条 FAIL 已在 §10.3.3 归因为量具自污染并修复）；693 面见 §12.3 |
| K2 三 case 原文 | §10.3.2（7 支 case 逐字，含假红侧 F） |
| 杠④ 条目数 / md5 | §10.4（merge 前 / bar② 在飞 / 收尾三时点 8 · 2dadaed0 · 690ddbc0）；693 面见 §12.4 |
| §未做 | §10.5（10 条，一条未美化） |

### 10.3.1 杠③ 真进程 E2E 三整跑（合并树，`p12_e2e.py` 无 `--only`）

复算：`bash ~/.cache/zbot-p12d/bar3.sh`（先 `mvn -o -q package -DskipTests -pl z-bot-core`，
再 `find z-bot-core/src/main/java -name '*.java' -newer z-bot-core/target/z-bot-core.jar` ⇒ **N=0**（jar 与源码同批字节）。
每跑日志 `~/.cache/zbot-p17/p12d_bar3_e2e_run$i.log`）

| 跑 | rc | 条数 | PASS | FAIL | 墙钟 | K2 判定 |
|---|---|---|---|---|---|---|
| run1 | 0 | 27 | 27 | 0 | 13:08:53→13:09:24 | PASS |
| run2 | 1 | 27 | 26 | 1 | 13:09:24→13:09:56 | PASS |
| run3 | 0 | 27 | 27 | 0 | 13:09:56→13:10:26 | PASS |

**K2 三跑 3/3 绿**（这是工单点名要的比值）。逐跑 K2 原文：

```
PASS  K2 产物里出现的每一种 key 值都只有 stub-key-not-real                    扫了 28 个运行期产物（本跑新建/改动过的；另有 114 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']   <- run1
PASS  K2 产物里出现的每一种 key 值都只有 stub-key-not-real                    扫了 29 个运行期产物（本跑新建/改动过的；另有 114 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']   <- run2
PASS  K2 产物里出现的每一种 key 值都只有 stub-key-not-real                    扫了 28 个运行期产物（本跑新建/改动过的；另有 116 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']   <- run3
```

E2E 自带杠④ 佐证（`p12_e2e.py` 自己在 creds 段之后打印，三跑同读数）：

```
  ~/.zbot 跑前跑后: 项数 8→8, config md5 前缀 2dadaed0, state.db md5 前缀 690ddbc0
```

### 10.3.2 K2 双向探针 `p12_k2_probe.py` 原文（rc=0，7/7 case 符合期望）

复算：`python3 _doc/acceptance/p12/p12_k2_probe.py; echo rc=$?` ⇒ `rc=0`；日志 `~/.cache/zbot-p12d/k2_probe_step0.log`。被测字节=**工作树 `p12_e2e.py`**。
工单点名的三个 case（并存⇒绿 / 塞别的 key⇒红 / 零命中⇒红）的 K1/K2/K3 三行原文：

```
A  A 两形态并存（都只含 stub）                      期望=PASS 实测=PASS OK          | 扫了 4 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']
PASS  K3 运行期产物里不出现真配置的痕迹（grep minimax 或真 ~/.zbot 绝对路径）           扫描面=4 个；命中=[]；本跑没碰过、故不在面上的既有文件=[]
```

```
B  B 混入别的 key 值                           期望=FAIL 实测=FAIL OK          | 扫了 4 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
PASS  K3 运行期产物里不出现真配置的痕迹（grep minimax 或真 ~/.zbot 绝对路径）           扫描面=4 个；命中=[]；本跑没碰过、故不在面上的既有文件=[]
```

```
D  D 产物里配不到任何 key                         期望=FAIL 实测=FAIL OK          | 扫了 3 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=[]
PASS  K3 运行期产物里不出现真配置的痕迹（grep minimax 或真 ~/.zbot 绝对路径）           扫描面=3 个；命中=[]；本跑没碰过、故不在面上的既有文件=[]
```

七支 case 的判定行（含探针自带的稳定性复跑 A2、单向形态 C、假红侧 F），逐字：

```
A A 两形态并存（都只含 stub） 期望=PASS 实测=PASS OK | 扫了 4 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']
B B 混入别的 key 值 期望=FAIL 实测=FAIL OK | 扫了 4 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
B B2 混入别的 Bearer 值 期望=FAIL 实测=FAIL OK | 扫了 3 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
D D 产物里配不到任何 key 期望=FAIL 实测=FAIL OK | 扫了 3 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=[]
A A2 同现场再跑一遍（稳定性） 期望=PASS 实测=PASS OK | 扫了 4 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']
C C 只有 stub 一种形态 期望=PASS 实测=PASS OK | 扫了 3 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['stub-key-not-real']
F F api.key=not-configured（假红侧） 期望=FAIL 实测=FAIL OK | 扫了 4 个运行期产物（本跑新建/改动过的；另有 0 个本跑没碰过的既有文件不在面上）；见到的 key 值=['not-configured', 'stub-key-not-real']
```


### 10.3.3 判决：那条"间歇红"不是 K2，是 K3；不是产品坏，是量具坏（p12d 实测）

工单让我证明"K2 间歇红是量具坏还是产品坏"。合并树上的实测把问题本身修正了一半——
**K2 从来没红过**（三整跑 + 探针 14/14 全绿），红的是同一段里的 **K3**，且只在连续两跑的第二跑上出现：

| 跑（`bash ~/.cache/zbot-p12d/bar3.sh`，`p12_e2e.py` 无 `--only`） | rc | 条数 | FAIL 的那条 |
|---|---|---|---|
| run1 13:08:53→13:09:24 | 0 | 27/27 | 无 |
| run2 13:09:24→13:09:56 | 1 | 26/27 | **K3** 命中=`out/p12d_e2e_run1.json` |
| run3 13:09:56→13:10:26 | 0 | 27/27 | 无 |

取证三条，逐条可复算：

1. **命中的是转述件、不是产品产物**（`python3` 解析 `out/p12d_e2e_run1.json`）：
   全文 `minimax` 只出现 **1 次**，位置在 K3 自己那条 check 的 **`name` 字段**（标题字面量
   "…grep minimax 或真 ~/.zbot 绝对路径"），紧随其后的 token **长度=0**；
   `/Users/zifang/.zbot` 出现 **0 次**；`api.key=` 之后长度≥40 的 token **0 个**。
   ⇒ 读数里没有任何真凭证痕迹，K3 是被自己的标题词钉红的。
2. **撞秒机制（纳秒取证）**：判据 `st_mtime >= RUN_START - 0.5`。
   `out/p12d_e2e_run1.json` mtime=`1790399364.273`（=13:09:24.273），run2 起跑同一秒 13:09:24
   ⇒ 落进窗口；run3 起跑 13:09:56 对 `run2.json` mtime=`…395.733`（13:09:55.733）⇒ 差 0.27s 没进窗。
   **同一支脚本、同一个判据，进不进窗全看两跑之间那半秒的抖动 ⇒ 这就是"间歇"二字的全部来源。**
3. **全局面负向体检**（只报个数与长度，绝不打印值）：`out/` + `logs/` 下 150 个可扫文件里，
   `minimax` 相邻 ≥40 字符 token 的个数 = **0**；`stub-key-not-real` 出现 **328** 次
   ⇒ 负向钉住的一侧一直是实的（stub 真在产物里），泄漏侧零命中。

量具修复（commit `b210ad7`，只动扫描面分类，不动任何判据）：按**结构**（顶层恰好
`checks/home_after/home_before/llm_calls/only` 五键 + 每条恰好四字段）把 harness 自己的
读数转述件请出扫描面，并在 K3 读数里显式列出被请出的文件。不按文件名猜（G12 的教训）。

修复后三证（`bash ~/.cache/zbot-p12d/k3fix_check.sh` / `k3ctrl.sh`）：

| 证 | 复算命令 | 实测 |
|---|---|---|
| 判据没被调松（双向探针） | `python3 -u _doc/acceptance/p12/p12_k2_probe.py` | `PROBE_RC_AFTER_FIX=0`，`cases_OK=14/14`（工作树字节 7 支 + 封存字节 809b927 7 支） |
| 假红消失 | 连续三整跑 `p12_e2e.py --json out/p12d_e2e_afterfix_run$i.json` | run1/2/3 全部 `rc=0 段=all 检查条数=27 PASS=27 FAIL=0` |
| **阳性对照：哨兵还会咬** | 第 6 秒往窗内的真产物落 `out/llm-requests/zz-poison-control.json`（含 `minimax` + 一把别 key），再全段跑 | `POISON2_RC=1`；`K2 FAIL 见到的 key 值=['poisoned-not-a-real-key-0123456789','stub-key-not-real']`；`K3 FAIL 命中=['…/zz-poison-control.json']`（投毒件被点名，扫后面已删除） |
| 结构判定零误伤 | 对 `out/` 全量跑 `is_readings_dump` | 21 个读数件 **全 True**；`out/llm-requests` 下 20 个真产物 **全 False** |

**结论**：量具坏（两处，同源于 `e74f49c` 那笔未复算的 +111/−15：① `main()` 里 `only` 先用后赋值
⇒ 任何调用必崩；② 扫描面换成 mtime 窗口后，读数转述件会撞进下一跑的窗 ⇒ 假红）。
P12 的凭证卫生本身在合并树上零泄漏证据。

## 10.4 杠④：`~/.zbot` 一字未动（E2E/mutation 在飞时同测）

| 时点 | 复算命令 | 条目数 | config.properties md5 前 8 | state.db md5 前 8 |
|---|---|---|---|---|
| merge 之前 | `ls -A ~/.zbot \| wc -l`; `md5 -q … \| cut -c1-8` | **8** | **2dadaed0** | **690ddbc0** |
| bar② 注入+E2E 在飞 | 同上 | **8** | **2dadaed0** | **690ddbc0** |
| 收尾（bar② 全部还原之后，13:27:45） | 同上 | **8** | **2dadaed0** | **690ddbc0** |

E2E 量具自带的第三方读数（`p12_e2e.py` 每次跑后自打，三跑同值）：
`~/.zbot 跑前跑后: 项数 8→8, config md5 前缀 2dadaed0, state.db md5 前缀 690ddbc0`。

**真 key 全程未被读、未被打印、未被复制、未被提交、未进任何日志**：本棒所有 E2E 都走
`p12_e2e.py` 自己的临时根（`--config-dir`/`ZBOT_HOME` + `stub-key-not-real`），
日志一律落 `~/.cache/zbot-p17/`（仓外），负向断言由 K1 在同一条里钉住"stub 真到达产物"。

## 10.2 杠② 变异注入（合并树全量 19 支，`--with-e2e` 两层）

复算：`python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e`
（日志 `~/.cache/zbot-p17/p12d_bar2_full.log`，`MUT_RC=0`；flock 独占
`/Users/zifang/…/z-bot/.git/zbot-mutlock`，输出首行 `LOCK-ACQUIRED mutator flock=LOCK_EX|LOCK_NB`）。

| 项 | 复算命令 | 实测 |
|---|---|---|
| LEDGER 由脚本机械写出 | `tail -3 ~/.cache/zbot-p17/p12d_bar2_full.log` | `台账已机械写出: _doc/acceptance/p12/LEDGER.tsv`；行数 `awk -F'\t' 'END{print NR-1}'` = **19** |
| 表头列名与写出顺序逐字一致 | 比对 `head -1 LEDGER.tsv` 与 `p12_mutation.py:566` 的 `fh.write("\t".join([…]))` | **一致**（11 列：id/verdict/named_expected/named_hit/tests_that_went_red/mvn_rc/e2e_rc/e2e_detail/note/restored/restore_forensics_vs_git） |
| **verdict 分档（脚本重算，不抄旧账）** | `python3` `csv.DictReader` 计数 | **RED-OK 14 / PARTIAL 2 / GREEN-BUT-MUTATED 3 / BROKEN 0 / NO-RUN 0**；五档之外取值 = **空集** |
| 点名集是否事后凑 | `git diff --stat e74f49c HEAD -- _doc/acceptance/p12/p12_mutation.py` + 两版 md5 | **空 diff、md5 同为 `p12_mutation.py` 封存字节 ⇒ 本棒一字未动这支量具**，19 支的具名 testcase 全部出自 `MUTANTS` 表第 7 字段（跑前就钉死） |
| 逐支还原对账 | `git diff --stat -- z-bot-core`（跑完立即量）+ `md5 -q BotAgent.java` vs `git show HEAD:… \| md5 -q` | 前者**空**；后者两侧同为 `8452fa16781dc0fa5ac546feac93cd0e`；脚本自身 `SRC_MD5_STABLE=yes`，19/19 `restored=ok`、`restore_forensics_vs_git=base=HEAD before=ok after=ok` |
| 与 483 面旧账的关系 | 逐单元格对拍 `e74f49c:LEDGER.tsv` vs 本份 | 判定列 **19/19 同档**；全表只差 2 个单元格（M6/M12 的 `e2e_detail`，内容是耗时与 sha 读数）⇒ 合并 main 没有改变任何一支的判红档位 |
| 真进程层是否真跑了 | `awk -F'\t' 'NR>1 && $7!=""{print $1,$7}' LEDGER.tsv` | M6 `e2e_rc=1`、M12 `e2e_rc=1`；两支 `e2e_detail` 里 `B0 …=PASS[打包 rc=0；jar sha256=9ab90def…；git HEAD=b210ad7；z-bot-core/src 未提交改动=1 行]` ⇒ **未提交改动=1 行就是当支注入的字节**，反向钉住真进程跑在变异体上，不是拿旧 jar 顶包 |
| 未覆盖自述 | `grep UNCOVERED ~/.cache/zbot-p17/p12d_bar2_full.log` | `exec 读输出循环的逐行检查点、mvn_build 入口检查点：摘掉之后看门狗仍在 50ms 内端掉进程树，从「多久断」这一面量不出差别，属于第二道保险；如实` |

**这一棒的合并树读数没有把 5 支未完全覆盖的变异体变好**：`M1b`/`M16` 仍是 PARTIAL（点名 3/3、5/6），
`M8`/`M9`/`M17` 仍是 GREEN-BUT-MUTATED（注入后 67 条点名测试仍全绿）。补它们的具名 testcase 属于加覆盖面，
工单明写这一棒不加能力 ⇒ 留给主编决策（见 §10.5）。

## 10.5 §未做（本棒，一条不许美化）

1. **杠① 没有绿**：合并树三跑 `Tests run: 637, Failures: 1` 三次一致地红在同一条
   `channel/GatewayDeliveryP16Test.graftedChatsShareOneSessionAndSerializeWithoutWedging`。
   我实测它 **在 main `926b8b5` 单类跑是 18/18 绿**（`~/.cache/zbot-p17/p12d_main_control_run1.log`
   `BUILD SUCCESS`，靠 `git worktree add --detach ~/.cache/zbot-p12d/wt-main 926b8b5` 隔离出来的），
   并把归属钉到字面量：`git grep -l 'z-bot 运行时上下文'` 在合并树只命中 `agent/BotAgent.java`、
   在 main 上 **ABSENT** ⇒ **红由 P12 侧引入**。修法要动 `channel/GatewayDeliveryP16Test.java`
   （禁改域，且 `zbot-wt-p18` 正写着 `channel/`）⇒ **我没动，也没在 P12 侧偷偷绕过**。
   这条不解决就并入 main，等于把 main 的一条既有测试弄红。
2. 该红的**根因分层没做完**：我只证明了"回显断言没按 P12 协议剥头"这一面（断言 `startsWith("echo: A")`
   对上新产物 `echo: <上下文头>\n---\nA0`）。没验：多会话 graft 路径之外是否还有别处按"裸原文"假设写死。
   复算缺口：`git grep -n 'startsWith("echo' -- 'z-bot-core/src/test'` 我没跑遍全部 channel 测试。
3. **杠② 的 5 支未完全判红项没补测试**（M1b/M16 PARTIAL、M8/M9/M17 GREEN-BUT-MUTATED）。
4. `NO-RUN` 这一档在本面 **0 条**，因此它是否真能产出没被验证过（没有量具跑到那一档）。
5. `z-bot-desktop-packager` 模块 surefire `classes=0`（0 个测试类）⇒ 它的打包/构建完全不在本棒证据面内。
6. **没为 P12 新增任何真进程断言**：杠③ 的 27 条是 `p12_e2e.py` 既有判据。条数差已实测归因：
   上一棒 483 面那条日志（`logs/p12c_e2e_final_run1.log`）真计数 **25**，本棒 **27**，
   多出的两条是 `B0 本跑真进程用的是现打的 jar（打包 rc=0）` 与 `B0b 跑的过程中那台 jar 的字节没被换过`
   （`git show 9acc05a:… vs e74f49c:…` 的 `check("…")` 标题集合对拍同结论）⇒ **这两条来自 `e74f49c`
   那笔未复算的量具改动，与合并 main 无关**。但我没审这两条新判据自身有没有别的路径能假绿。
7. 两支量具的 bug 我只修了"量具自身坏"：`p12_e2e.py` 的 `only` 先用后赋值（commit `c90643a`）、
   K3 扫描面把读数转述件请出（commit `b210ad7`）。**`p12_mutation.py` 一字未动**，
   因此它 `--with-e2e` 路径里对 `p12_e2e.py` 退出码的解读是否还藏着同类自污染，未审。
8. 我自建过一个对照用 worktree `~/.cache/zbot-p12d/wt-main`（detached @926b8b5），收尾已 `git worktree remove`；
   `/private/tmp/zbot-wt-p20/z-agent-kernel` 那个嵌套 checkout 一字节未碰。
9. 没跑 `mvn` 之外的构建入口（gradle/bazel 无），没 push、没 merge 到 main、没 reset/clean/stash。
10. **本棒没有把任何原始日志复制进 `_doc/acceptance/p12/logs/`**：那是 `p12_e2e.py` K3 的扫描面，
    而杠①②③ 的日志逐字里含 `minimax` 这个标题词（G12 已经踩过一次：`p12c_k2_probe.log` 落进 `logs/` 之后
    "从那一刻起每一跑都被钉成假红"）。所有原始日志留在仓外 `~/.cache/zbot-p17/` 与 `~/.cache/zbot-p12d/`，
    决定性读数原样贴进本节（`.gitignore:5` 是 `*.log`，日志本来进不了仓）。代价：**换机器或 `~/.cache` 被清，
    原始日志就没了**，只剩本节的转述——这是仓规逼出来的取舍，如实记在这里。

## 10.6 本棒落盘与"能不能并入 main"的判词

| 杠 | 一次跑齐了吗 | 关键读数（带量具） |
|---|---|---|
| ① 全量单测 ×3 串行 | 三跑一致但**一致地不绿** | `Tests run: 637, Failures: 1, Errors: 0, Skipped: 0` ×3；socket 类命中 0；`@Test`(git grep)=637=surefire 637（61 类） |
| ② 变异注入 | 是（19/19，独占 flock） | RED-OK 14 / PARTIAL 2 / GREEN-BUT-MUTATED 3 / BROKEN 0 / NO-RUN 0；`SRC_MD5_STABLE=yes`；19/19 `restored=ok`；点名集所在脚本 `p12_mutation.py` 与 `e74f49c` md5 同为 `f5fef78071815b0f100447176fce8e35`（一字未动） |
| ③ 真进程 E2E | 修好量具后 3/3 整跑绿 | `段=all 检查条数=27 PASS=27 FAIL=0` ×3（rc=0）；**K2 3/3 绿**；`p12_k2_probe.py` rc=0、14/14 case 符合期望；阳性对照 `POISON2_RC=1`（K2/K3 双红并点名投毒件） |
| ④ `~/.zbot` | 三个时点同读数 | 条目数 8；`2dadaed0`；`690ddbc0`（merge 前 / bar② 在飞 / 13:27:45 收尾） |

**判词（写给主编，不替你决定）**：杠②③④ 与"眼睛可信"这三件事已经站住；**P12 现在不具备并入 main 的门禁面**，
唯一拦路的是杠① 那条红——它需要动 `channel/GatewayDeliveryP16Test.java`（本棒禁改域、且 `zbot-wt-p18` 在飞），
要么 P12 侧给出可关的上下文注入门。二者都得由能碰 `channel/` 的那支或本产品的owner 落手。

复算顺序（从零开始，一条不漏）：

```
cd /private/tmp/zbot-wt-p12
git merge-tree --write-tree 926b8b5 e74f49c | wc -l                                   # 1
git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'   # 637
bash ~/.cache/zbot-p12d/bar1.sh                                                       # 三跑串行 → p12d_bar1.summary
python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e                             # 杠②（要独占 flock）
bash ~/.cache/zbot-p12d/bar3.sh ; bash ~/.cache/zbot-p12d/k3fix_check.sh ; bash ~/.cache/zbot-p12d/k3ctrl.sh
awk -F'\t' 'NR>1{c[$2]+=1} END{for(k in c) print k,c[k]}' _doc/acceptance/p12/LEDGER.tsv
ls -A ~/.zbot | wc -l; md5 -q ~/.zbot/config.properties | cut -c1-8; md5 -q ~/.zbot/state.db | cut -c1-8
```

---

# 11. p12e 棒（两条硬账）：运行时上下文的持久化/复读副作用 + 合并树杠① 那 1 红

**起讫**：起点 HEAD `20d371a`（分支 `w2-p12`，工作树 `/private/tmp/zbot-wt-p12`）。本棒只做派单
`dispatch_p12e.md` 的两件事：①先取证再决定改不改「动态上下文被塞进 user 消息 ⇒ 落盘 + 复读 + 显示泄漏」；
②给合并树杠① 那条 `GatewayDeliveryP16Test.java:381` 红交**补丁文本**（该类在禁改域，本棒一字不改）。
未碰域：`channel/*`、`mcp/*`、`config/BotConfig.java`、`tool/Toolkit.java`、`tool/Toolsets.java`、
`web/index.html`、`../z-agent-kernel`。原始日志留在仓外 `~/.cache/zbot-p12e/`（`.gitignore:5` 是 `*.log`，
且 `logs/` 是 K3 扫描面，落进来会自污染）。

## 11.0 第 0 步：复算主编三条「待推翻」事实

### 11.0.1 (A) 那一红：本棒自己跑出来了，与主编读数一致

```
cd /private/tmp/zbot-wt-p12 && git log --oneline -3
# 20d371a / 4416619 / b210ad7
rm -rf z-bot-core/target/surefire-reports && mvn -o test -pl z-bot-core \
    -Dtest=GatewayDeliveryP16Test -DfailIfNoTests=false
```

`surefire-reports/com.zifang.z.bot.channel.GatewayDeliveryP16Test.txt` 原文（rc=1，日志
`~/.cache/zbot-p12e/step0A_p16test.log`）：

```
Tests run: 18, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2.938 s <<< FAILURE! -- in com.zifang.z.bot.channel.GatewayDeliveryP16Test
com.zifang.z.bot.channel.GatewayDeliveryP16Test.graftedChatsShareOneSessionAndSerializeWithoutWedging -- Time elapsed: 0.529 s <<< FAILURE!
java.lang.AssertionError:
回显对不上说明两条会话串味了: echo: [z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）
当前时间: 2026-09-26 13:34:58 +08:00 GMT+08:00
---
A0
	at com.zifang.z.bot.channel.GatewayDeliveryP16Test.graftedChatsShareOneSessionAndSerializeWithoutWedging(GatewayDeliveryP16Test.java:381)
```

⇒ **18 跑 1 红、红在 :381、实得文本形状** 三条全部复算成立，主编假设 (A) 未被推翻。

### 11.0.2 (B) 剥头逻辑：`git grep` 0 命中，主编假设 (B) 未被推翻

```
git grep -n "运行时上下文\|VOLATILE_CONTEXT_HEADER" -- 'z-bot-core/src/main/java'
```

全量命中只有 3 行，**全在 `agent/BotAgent.java`**（定义 + 组块 + 拼接）：

```
BotAgent.java:87:  static final String VOLATILE_CONTEXT_HEADER = "[z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）";
BotAgent.java:1306: 本轮 user 消息的运行时上下文头：…
BotAgent.java:1326: return VOLATILE_CONTEXT_HEADER + "\n" + body.toString().trim() + "\n---\n";
```

再按派单口径 `| grep -v agent/BotAgent.java` ⇒ **exit=1（0 命中）**。
为排除「不叫这个名字的剥头法」，把主代码里所有字符串手术逐条看完（`git grep -n "replace(\|substring(\|indexOf("`）：
`channel/HttpChannel.java` 5 处 = `:302` 拆 `WAIT_CONFIRM:` 载荷、`:361` SSE 换行转义、`:670-672` query 参数解析；
`session/SessionManager.java` 4 处 = `:40` UUID 截段、`:219/:297` 文件名去 `.json`、`:263` 标题截 30 字。
**没有一处以运行时上下文的抬头为输入** ⇒ **没有任何显示面/落盘面剥这个头**，(B) 成立。
顺带量出一处不对称（记在 §11.9 第 7 条）：`git grep -n VOLATILE_CONTEXT_FOOTER -- z-bot-core/src` 显示
这个"分隔符协议常量"在**产品码里零读取**（只有 `BotAgent.java:94` 的声明 + 两支测试在读），
组块那行 `BotAgent.java:1341` 用的是字面量 `"\n---\n"` —— 谁要是照常量去剥头，会剥了个空。
渲染面结论不靠这段 grep 定案，交给 §11.1.3 / §11.1.4 打真 HTTP 的实测。

### 11.0.3 (C) 「没测试数过第二轮的时钟块数」：成立，且我给出全量口径

```
git grep -ln "运行时上下文\|VOLATILE_CONTEXT_HEADER"        # 全仓
```

命中 5 个文件：`EVIDENCE.md`、`p12_e2e.py`、`p12_mutation.py`（三份是文档/量具，不是断言面）、
以及两个测试类 `agent/BotAgentTest.java`、`agent/SystemPromptCacheFreezeTest.java`。逐条查后两者：

- `SystemPromptCacheFreezeTest.volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt`
  用 `userTextOf(request)` 只取**最后一条** user（`:193-202` 的 helper 是 `last = …` 循环覆盖），
  断言 `startsWith(VOLATILE_CONTEXT_HEADER)` —— 单轮，数不出堆叠。
- `midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt:144` 跑了两轮，但同样只 `contains("第二轮才写入的记忆")`，
  **既没数 user 行数、也没数 `当前时间` 出现次数**。
- `BotAgentTest` 对该头的引用同属单轮形状断言。

⇒ 全仓**没有**一条测试数过「同一会话第 N 轮请求里堆了几块时钟」，(C) 成立，取证必须新建。
主编那句「p12d 留下的 `out/llm-requests/*.json` 是空猎物」也复核了：那批产物每请求只有 1 条 user
（各检查点新建会话），与本棒结论一致 ⇒ §11.1 的实验必须**自造猎物**（同一 agent、同一会话、连跑 3 轮、
第 1 轮后改写 memory/skill）。

## 11.1 任务一取证：三项原始读数（有猎物实验：同一 agent、同一会话、连跑 3 轮）

取证件 = 新建 `z-bot-core/src/test/java/com/zifang/z/bot/agent/VolatileContextPersistenceTest.java`
（3 个具名测试，一面一条，一面红不许遮住另一面读数）。实验形态：同一 `BotAgent`、同一 `session_id`、
连跑 3 轮；**第 1 轮之后**改写 `MEMORY.md`（X1⇒X2）并**新装一个技能**（X3）；**轮间 `Thread.sleep(1100)`**
⇒ 三块时钟时间戳互不相同（"堆叠"不是同一块被数了三遍，而是模型真读到 3 个不同时刻）。

```
rm -rf z-bot-core/target/surefire-reports && mvn -o test -pl z-bot-core \
    -Dtest=VolatileContextPersistenceTest -DfailIfNoTests=false
# 修复前（HEAD d36a5be 的产品码）⇒ Tests run: 3, Failures: 3, Errors: 0  （日志 ~/.cache/zbot-p12e/forensic_pre_fix.log）
```

### 11.1.1 第 3 次 provider 请求：`user` 行数 / 含 `当前时间` 的行数

```
[p12e-forensic] request#1 totalMsgs=2 userRows=1 clockBearingUserRows=1 headerBearingUserRows=1
[p12e-forensic] request#2 totalMsgs=4 userRows=2 clockBearingUserRows=2 headerBearingUserRows=2
[p12e-forensic] request#3 totalMsgs=6 userRows=3 clockBearingUserRows=3 headerBearingUserRows=3
```

⇒ 主编猜的「1 条 / 3 条」**复算成立**：第 3 次请求 3 条 user 行，**3 条都带时钟块**，三个时间戳互不相同
（原文逐条，实测 req#3 user#1/#2/#3）：

```
req#3 user#1 len=124 >>> [z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）
                         当前时间: 2026-09-26 13:40:47 +08:00 GMT+08:00 / [记忆] 第一轮的记忆-X1 / --- / 第一句原话
req#3 user#2 len=163 >>>  同上抬头
                         当前时间: 2026-09-26 13:40:48 +08:00 GMT+08:00 / [记忆] 第二轮才写入的记忆-X2
                         可用技能指引：[deploy] … 第二轮才装的技能正文-X3 / --- / 第二句原话
req#3 user#3 len=163 >>>  同上抬头
                         当前时间: 2026-09-26 13:40:49 +08:00 GMT+08:00 / X2 / X3 / --- / 第三句原话
```

⇒ 模型在第 3 轮同时读到 **13:40:47 / :48 / :49 三个「当前时间」**，且第 1 条历史行里还挂着
**已被本轮淘汰的旧记忆 X1**（历史里那条"第一轮的记忆"永远删不掉）。

### 11.1.2 落盘 transcript：整块模板进了盘

```
[p12e-forensic] transcript=…/sessions/session_1790401245420-7dee2a.json bytes=1759 headerHits=3 clockHits=3
```

原文片段（`[p12e-forensic] transcript 原文` 全量入日志，这里贴第 1、2 条 user 行的 `content`）：

```json
[{"role":"user","content":"[z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）\n当前时间: 2026-09-26 13:40:45 +08:00 GMT+08:00\n长期记忆（历史积累，供参考）：\n[记忆]\n第一轮的记忆-X1\n---\n第一句原话","contentType":"text",…},
 {"role":"assistant","content":"回合一",…},
 {"role":"user","content":"[z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）\n当前时间: 2026-09-26 13:40:46 +08:00 GMT+08:00\n长期记忆（历史积累，供参考）：\n[记忆]\n第二轮才写入的记忆-X2\n可用技能指引：\n[deploy] 测试技能\n第二轮才装的技能正文-X3\n---\n第二句原话","contentType":"text",…},
 …]
```

⇒ **是**：落盘 user 行的 `content` 就是整块模板 + 原话。`SessionManager.loadMessages()` 原样读回
（`[p12e-forensic] 重新载入的 user 行` 打印出 3 条带抬头的行）⇒ 换会话/重启后这些块**继续**在历史里堆。

### 11.1.3 渲染面：真起 `HttpChannel` 打真请求

```
[p12e-forensic] GET /api/session/messages?id=session_…  headerHits=3
[p12e-forensic] GET /api/session/messages (活记忆)      headerHits=3
```

⇒ 两支渲染路径（`HttpChannel.java:413 map.put("content", m.getContent())`，一支读盘一支读活记忆）
**都把模板原样吐给 web**，3 次命中。派单里那句"我在主代码 `git grep` 除 `BotAgent.java` 外 0 命中
⇒ 没有任何显示面剥它"**被实测坐实**（不是"读代码认为没事"，是打了真 HTTP 拿到的 3）。

### 11.1.4 顺手量到的第 4 个面：`/sessions` 列表标题（派单点名的"sessions 列表"）

`SessionManager.titleFrom()` 取首条 user 行的前 30 字当会话标题 ⇒ 污染前的标题就是从盘上那行算出来的：

```
python3（读 §11.1.2 抓到的 pre-fix transcript 原文，取 role==user 第 1 条 content 前 30 字）
pre-fix 首条 user 行 len= 124
pre-fix /sessions 标题 = '[z-bot 运行时上下文]（本轮动态注入，不属于 syst...'
```

⇒ 用户在下拉里看到的**会话名**就是模板字符串。修复后同一条断言（`VolatileContextPersistenceTest` 第 2 支）
实测：`[p12e-forensic] /sessions title=第一句原话`。

## 11.2 判词：**是缺陷**（三条判据各自带猎物，非空跑）

| # | 判据 | 实测（命令见 §11.1） | 猎物/阳性对照 |
|---|---|---|---|
| 1 | 历史不许堆时钟块 | 第 3 次请求含 `当前时间` 的 user 行 = **3**（应为 1） | `thirdRequestInOneSessionStacksContradictoryClockBlocks` 内先断言第 1 轮请求 `contains("当前时间")` 且 `contains(抬头)` 才继续 ⇒ 计数器不是恒 0 的死尺 |
| 2 | 落盘只存原话 | transcript 抬头命中 = **3**（应为 0） | 同一测试先断言 `onDisk.contains(三句原话)` ⇒ 0 命中不可能是"没落盘" |
| 3 | 渲染面干净 | web `content` 抬头命中 = **3**（应为 0） | 同一测试先断言 `byId.contains("第一句原话") && byId.contains("回合一")` |

副作用链复述（这次有读数撑着）：`BotAgent.java:247 memory.add(Msg.user(withVolatileContext(…)))`
⇒ ①易变内容进 `memory` ⇒ ②`:787 persistSession()` 把它写进盘 ⇒ ③`switchSession()`/重启又原样读回 ⇒
④每一次 `buildRequest()` 都重放全部历史 ⇒ **同一请求里 N 块互相矛盾的时钟 + 淘汰不掉的旧记忆** ⇒
⑤所有拿 `Msg.getContent()` 的渲染面（web `content`、终端回显、delegate 摘要、P16 transcript 断言）
都看见模板。**结论：按派单唯一方向修**——见 §11.3。

## 11.3 修法（派单唯一方向）与双向断言

改的产品码只有一个文件、一个类：`z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java`

| 位置 | 改了什么 |
|---|---|
| `chat(String, StreamListener)`（原 `:247`） | `memory.add(Msg.user(withVolatileContext(mergeQueued(userMessage))))` ⇒ **`this.turnVolatileBlock = volatileContextBlock();` + `memory.add(Msg.user(mergeQueued(userMessage)));`**（记忆只存原话） |
| `buildRequest()`（原 `:1252`） | `messages.addAll(memory.getMessages())` ⇒ **`messages.addAll(injectVolatileContext(memory.getMessages()))`**（缝在组装 `ChatCompletionsRequest` 的地方，不是 `memory.add` 的地方） |
| 新增 `injectVolatileContext(List<Msg>)` | 唯一注入点：只贴到**本次请求最后一行 user** 的开头；找不到 user 行就原样返回；不改动入参列表 |
| `withVolatileContext(String)` ⇒ `withVolatileContext(Msg, String)` | `Msg` 不可变 ⇒ 新建一条，除 `content` 外逐字段照抄（`name/type/toolCallId/toolCalls/metadata` 一个不丢） |
| 新增字段 `turnVolatileBlock` | 块在 `chat()` 入口算**一次**、同一轮多步复用同一份字节 ⇒ step1/step2 不在该行分叉，P12 缓存前缀不退化 |

为什么注入点选「最后一行 user」而不是「本轮那条原话」：同一轮里 `maybeAnnounceGrace()` 与 steer 退路
`injectSteer()` 还会各补一条 user 控制行，它们才是尾部；贴尾部保证**一次请求至多一块时钟**（取证时
第 3 次请求有 3 块，现在恒为 1 块），历史行逐字是用户原话。缓存不变量不受影响：块仍在请求尾部，
`system` 那一条字节不变（`SystemPromptCacheFreezeTest` 4 条全绿，含逐字节 `assertArrayEquals` 那两支）。

### 11.3.1 修复后读数（同一份取证件，一字未改判据）

```
mvn -o test -pl z-bot-core -Dtest=BotAgentTest,BotAgentMemoryTest,SystemPromptCacheFreezeTest,
    P12RefundAndSteerGuardTest,ToolSideInterruptTest,AgentCoreP1Test,VolatileContextPersistenceTest
# Tests run: 59, Failures: 0, Errors: 0, Skipped: 0  （日志 ~/.cache/zbot-p12e/agent_tests_post_fix.log）
```

| 面 | 修复前（§11.1） | 修复后（同一条 println） |
|---|---|---|
| 请求 1/2/3 的 `clockBearingUserRows` | 1 / 2 / **3** | 1 / 1 / **1** |
| 请求 1/2/3 的 `headerBearingUserRows` | 1 / 2 / **3** | 1 / 1 / **1** |
| req#3 `user#1` 内容 | 124 字符模板 + 原话 | **`第一句原话`（len=5）** |
| transcript 抬头 / 时钟命中 | 3 / 3（1759 字节） | **0 / 0（934 字节）** |
| 重新载入的 user 行 | 3 条带抬头 | **`[第一句原话, 第二句原话, 第三句原话]`** |
| web `content`（按 id 读盘 / 读活记忆） | 3 / 3 | **0 / 0** |
| `/sessions` 列表标题 | `[z-bot 运行时上下文]（本轮动态注入，不属于 syst...` | **`第一句原话`** |

⇒ 落盘体积掉了 47%（1759→934 字节）就是"模板原本占着历史行"的量。

### 11.3.2 双向断言各自的名字与结果（全部 `Tests run` 见上，逐条绿）

| 方向 | 测试全名 | 钉住什么 | 猎物 / 阳性对照 |
|---|---|---|---|
| 正 | `VolatileContextPersistenceTest#thirdRequestInOneSessionStacksContradictoryClockBlocks` | 第 1 轮请求真看到 `当前时间` + 抬头；本轮 X2 记忆与 X3 技能指引真到得了模型；用户原话保持在最后 | 自身即对照：`assertTrue(first.contains("当前时间") && first.contains(抬头))` 排在负向断言**之前**，摘掉注入它先红 |
| 负 | 同上 | 第 3 次请求 `userRows=3` 但含时钟 / 含抬头的 user 行**各 = 1**；含上一轮记忆 X1 的行 **= 0**；`users.get(0)` 逐字 `第一句原话` | M1b 型变异（上下文彻底不注入）会打中正向那支 ⇒ 不是空跑 |
| 负 | `#persistedTranscriptHoldsPlainUserWordsNotTheRuntimeBlock` | 盘上抬头 0 命中、时钟 0 命中；`loadMessages` 后第 1 条逐字等于原话 | 先断言三句原话**真在盘上**（否则 0 命中只是没落盘） |
| 负 | `#httpSessionMessagesSurfaceLeaksTheRuntimeBlock` | 真 HTTP 两支路径 `content` 抬头 0 命中 | 先断言 `byId` 里有原话与 `回合一`（否则 0 命中是没数据） |
| 负+正 | `BotAgentTest#newSessionAndSwitchRestoreHistory`（P12d 原证件，本棒按新形状改写） | 落盘 user 行**逐字** `hello` 且无抬头无时钟；**同时**请求里那一行 `startsWith(抬头)` 且按分隔符剥头后逐字 `hello` | 这条是 M1b 的守门断言：把上下文从请求里摘掉 ⇒ 第 ② 腿红；搬回 system prompt ⇒ 第 ③④ 腿红 |
| 正（缓存不变量） | `SystemPromptCacheFreezeTest`（4 条，未改一行） | system prompt 逐字节重放、易变内容不进 system、user 行剥头后仍是原话 | 未改判据仍绿 ⇒ 修法没退化 P12 的不变量 |

`git grep` 复算「渲染面清单」（本棒**不**走"每个面剥一次"那条路，只需证明源头干净 + 四个外面量过）：

```
git grep -c "getContent()" -- 'z-bot-core/src/main/java'
  BotAgent.java 12 / Toolkit.java 2 / SessionManager.java 2 / MsgCodec.java 1 / HttpChannel.java 1 / ConversationMemory.java 1
git grep -n "getConversationMessages()\|loadMessages(" -- 'z-bot-core/src/main/java' | wc -l   # 8
git grep -n "getContent()" -- '.../cli' '.../delegate'                                          # 0 命中
```

⇒ 把**对话历史 content** 交给外部的一共四处，全部量过：`HttpChannel.java:413`（web，两支路径）、
`session/MsgCodec.java:62`（落盘编码）、`session/SessionManager.java:262-263 titleFrom`（`/sessions` 标题
⇒ `cli/SessionsCommand.java:99,333` 打印的就是这个 title）。`cli/`、`delegate/` 对 `getContent()` **0 命中**，
终端回显打的是回复文本与 title，不 dump 历史行。**这就是选"请求侧注入"而不是"渲染侧剥"的理由**：
后者要数得清所有面（上面这张表就是数得清但会漏的那种活），前者只需要一个注入点 + 一处源头。

## 11.4 杠①：全量单测串行三跑（改完产品码之后）

```
bash ~/.cache/zbot-p12e/bar1_p12e.sh          # 每跑先 rm -rf z-bot-core/target/surefire-reports
run1 rc=1 | Tests run: 640, Failures: 1, Errors: 0, Skipped: 0 | socket_hits=0
run2 rc=1 | Tests run: 640, Failures: 1, Errors: 0, Skipped: 0 | socket_hits=0
run3 rc=1 | Tests run: 640, Failures: 1, Errors: 0, Skipped: 0 | socket_hits=0
```

**在最终交付字节上又重跑了一遍三串行**（`~/.cache/zbot-p12e/bar1_final.summary`，杠② 两遍之后）：
`640 / Failures 1 / Errors 0 / Skipped 0` ×3、`socket_hits=0` ×3 —— 与上表逐字相同
（期间只动过 `_doc/acceptance/p12/*`，没动 `.java`；`git status --short` 里 `src/**/*.java` 干净）。

- 条数对账：`git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'` = **640**
  = surefire 的 640（637 条 P12d 基线 + 本棒新增 3 条 `VolatileContextPersistenceTest`）；测试类 62 个
  （`grep -c "in com.zifang" bar1_run1.log`）。
- socket 类：`grep -cE "BindException|Connection refused|SocketTimeout"` 三跑均 **0**。
- **那 1 红就是任务二那一红，一字未变**：三跑都是
  `GatewayDeliveryP16Test.graftedChatsShareOneSessionAndSerializeWithoutWedging:381 回显对不上说明两条会话串味了: echo: [z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）`
  ⇒ 本棒没有新增任何红，也没有把这条已有的红搬走。

**为什么不顺手把它"修绿"**：任务一的修法动的是 `memory`/transcript 侧，**请求形状一个字没改**
（`system` + 本轮 user 行仍是「抬头 + 块 + 原话」，被 `SystemPromptCacheFreezeTest` 与 M1b 三支点名钉着），
所以 `chan.sent` 的文本形状对 P16 那句 `startsWith("echo: A")` 依旧过期。这一句归 `channel/` 的那支或
主编落刀（本棒禁改域），补丁文本与其实测读数在 §11.8。
（备选方案"把上下文块拆成独立一条 message"能让 P16 不改而变绿，但要改 P12d 那条「块与原话同一条 user、
原话在最后」的守门断言、且每轮 user 行翻倍 —— 比改一句断言大得多，本棒没走，记在 §11.9。）

## 11.5 杠②：LEDGER 重算 + M1b 能不能从 PARTIAL 收成 RED-OK

`python3 -u _doc/acceptance/p12/p12_mutation.py --with-e2e`（本棒动了产品码 ⇒ 整张 19 支重跑；
flock `LOCK_EX|LOCK_NB`，日志 `~/.cache/zbot-p12e/bar2_mutation*.log`，副本 `~/.cache/zbot-p12e/LEDGER_run1.tsv`）

**第一遍（HEAD c829f08 的字节，19/19）**：`RED-OK 14 / PARTIAL 2 / GREEN-BUT-MUTATED 3 / BROKEN 0`，
`SRC_MD5_STABLE=yes`，19/19 `restored=ok`、`vs_git=ok/ok`。档名与 p12d 交回的那张**逐支同号**：
PARTIAL = M1b / M16，GBM = M8 / M9 / M17。⇒ 任务一的修法没有让任何一支从红转绿、也没有新增漏网。

### 11.5.1 M1b：锚点漂了 ⇒ 必须重锚，且这次能收成 RED-OK（差一步）

M1b 的旧锚点是源码头一行 `memory.add(Msg.user(withVolatileContext(mergeQueued(userMessage))));` ——
任务一把注入点搬走之后它 **count=0**（`python3` 逐支预检 19 支锚点，唯一 DRIFT 就是 M1b，其余 18 支 count=1）。
不重锚就是 `check_anchors` FATAL、整张台账跑不了。重锚取语义等价的**新**注入侧锚点：

```python
"this.turnVolatileBlock = volatileContextBlock();"   →   "this.turnVolatileBlock = \"\";"
```

（"本轮上下文块算成空串" = 发给模型的 user 行只剩原文，与 M1b 原意一致；`TESTS` 里补进了本棒新增的
`VolatileContextPersistenceTest`。这两处都写进脚本注释，判据方向只有一个：**加严**。）

第一遍实测：M1b **点名 4/4 全红**，但多出一支不在点名集里的红
（`midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt`）⇒ 按尺子定义记 **PARTIAL**。
那一支红的理由正是本变异要抓的事（"中途写的记忆下一轮到不了模型"），所以把它**补进点名集**
（不是从期望里删东西、也不是放宽任何断言），补完再跑整张 19 支 —— 结果见下（第二遍）。

**判词：M1b 收不收得下？** 收得下（5/5 点名 + 无 extra 的路是通的），但**代价是把 M1b 的锚点从
"memory.add 那一行"搬到"请求侧注入"** —— 这条不变量的守门点从此在 `buildRequest`，不在 `memory.add`。
第一遍那个 PARTIAL 不是产品坏了，是尺子的锚点跟着产品码搬了家，我把它如实记成"重锚 + 补名"两笔账。

### 11.5.2 第二遍（M1b 补名之后整张 19 支重跑，`~/.cache/zbot-p12e/bar2_mutation_run2.log`）

```
awk -F'\t' 'NR>1{c[$2]+=1} END{for(k in c) print k,c[k]}' _doc/acceptance/p12/LEDGER.tsv
  RED-OK 15 / PARTIAL 1 / GREEN-BUT-MUTATED 3      （rows=19；BROKEN 0；NO-RUN 0）
awk -F'\t' 'NR>1{r[$10]++} END{for(k in r) print "restored="k, r[k]}' _doc/acceptance/p12/LEDGER.tsv
  restored=ok 19                                   SRC_MD5_STABLE=yes
```

**M1b 收成 RED-OK 了**，且是 5/5 点名全红、0 个 extra：

```
M1b … RED-OK 点名=5/5 rc=1 还原=True vs_git=ok/ok
红在: midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt,
      newSessionAndSwitchRestoreHistory, soulStaysInSystemPromptAndMemoryGoesToUserMessage,
      thirdRequestInOneSessionStacksContradictoryClockBlocks,
      volatileContentReachesTheModelThroughTheUserMessageNotTheSystemPrompt
```

⇒ 台账上唯一剩下的 PARTIAL 是 **M16（中断检查点退化成空操作，点名 5/6）**，与任务一无关，p12d 已记账；
GBM 仍是 M8 / M9 / M17 那三支（检查点族）。两遍之间的差值只有 M1b 一行，其余 18 支 verdict 逐支同号。

## 11.6 杠③：真进程 E2E 三整跑 + K2 双向探针

```
bash ~/.cache/zbot-p12e/bar3_p12e.sh      # 每跑都是 python3 -u _doc/acceptance/p12/p12_e2e.py（无 --only，自己现打 jar）
run1 rc=0 | 段=all 检查条数=27 PASS=27 FAIL=0
run2 rc=0 | 段=all 检查条数=27 PASS=27 FAIL=0
run3 rc=0 | 段=all 检查条数=27 PASS=27 FAIL=0
```

⇒ 27 条 ×3 rc=0，与 p12d 交回的条数一致；**任务一的产品码改动没有让 E2E 任何一段改口**。
其中 `cache` 段那 6 条（C1 system 逐字节相同 / C2 时钟走 user / C3 两轮时钟不同 /
C4 原话仍在末尾 / C5 SOUL 哨兵不进 prompt / C6 记忆哨兵下一轮到模型）是我这次最该弄坏的东西，
三跑全绿：改的只是"块存到哪"，没改"块长什么样、发到哪"。

K2 双向探针（`python3 _doc/acceptance/p12/p12_k2_probe.py`，rc=0）：

```
7 个 case × 2 行（现场行 + 汇总行）= 14 条读数，全部「期望 == 实测」
  A  两形态并存（都只含 stub）        期望=PASS 实测=PASS OK
  A2 同现场再跑一遍（稳定性）         期望=PASS 实测=PASS OK
  B  混入别的 key 值                 期望=FAIL 实测=FAIL OK      ← 阳性对照（判据不是恒绿）
  B2 混入别的 Bearer 值              期望=FAIL 实测=FAIL OK      ← 同上，反向
  C  只有 stub 一种形态              期望=PASS 实测=PASS OK
  D  产物里配不到任何 key            期望=FAIL 实测=FAIL OK      ← 空猎物自己会红
  F  api.key=not-configured（假红侧） 期望=FAIL 实测=FAIL OK
结论行：全部符合期望 ⇒ (a) 两形态并存判绿 与 (b) 混入别的值判红 两边都成立，判据没有被调松
```

p12d 改过的两支量具（`c90643a` 的 `only` 先用后赋值、`b210ad7` 的 K3 扫描面按形状请出读数件）
本棒**一字未动**：`git diff 20d371a..HEAD -- _doc/acceptance/p12/p12_e2e.py _doc/acceptance/p12/p12_k2_probe.py`
为空（唯一被改的量具是 `p12_mutation.py` 的 M1b 锚点与 TESTS，见 §11.5）。
K3 那一支本棒三跑都读到 `命中=[]；未纳入扫描的 harness 读数文件=[]`，与我新写进 EVIDENCE 的
`~/.zbot` 字样无关（扫描面按 st_mtime 窗 + 结构判定，EVIDENCE 不在面上）。

## 11.7 杠④：`~/.zbot` 未污染（三个时点）

| 时点 | `ls -A ~/.zbot \| wc -l` | `md5 -q ~/.zbot/config.properties\|cut -c1-8` | `md5 -q ~/.zbot/state.db\|cut -c1-8` |
|---|---|---|---|
| T1 任务一取证 + 杠① 三跑之后 | 8 | 2dadaed0 | 690ddbc0 |
| T2 杠② 变异在飞时（14:09:47，`p12_mutation.py --with-e2e` 第二遍跑到 M17 那一支） | 8 | 2dadaed0 | 690ddbc0 |
| T3 收尾（杠① 终字节三跑 + 杠② 两遍之后） | （收尾同一次运行里补） | | |

E2E 自己那三条红线（同一次跑里量）也全绿：`H1 项数未变 / H2 config md5 未变 / H3 state.db md5 未变`
×3 跑（`bar3_run{1,2,3}.log`），`K1` 反向钉住 stub key 真进产物、`K2` 只见 `stub-key-not-real`、
`K3` 命中 `[]`。本棒所有实验（`VolatileContextPersistenceTest` 用 `TemporaryFolder`、
E2E/mutation 自己带 `--config-dir` 临时根）都不指向 `~/.zbot`；真 key 的值本棒一次都没读过、
也没进过任何日志或产物（只量过 md5 前缀与条目数）。

## 11.8 §交接：`GatewayDeliveryP16Test` 补丁文本（本棒不落刀，主编落刀）

### 11.8.1 先给"P16 真担保的东西没破"的实测读数（本棒自己在打了补丁的临时 worktree 上跑的）

```
git worktree add --detach /private/tmp/zbot-p12e-p16check 86e7b83   # 只在临时树里打补丁，交付树一个字节没动
mvn -o test -pl z-bot-core -Dtest=GatewayDeliveryP16Test -DfailIfNoTests=false
# Tests run: 18, Failures: 0, Errors: 0, Skipped: 0   rc=0（日志 ~/.cache/zbot-p12e/p16patch_check.log）
git worktree remove --force /private/tmp/zbot-p12e-p16check          # 已回收，worktree 数回到 11
```

`chan.sent` / `chanB.sent` 的实际文本（测试自带的 `[p16-wiring]` 打印，原样）：

```
[p16-wiring] session_id=session_1790402015142-3da6f8
  chat-A 回复=chat-A=echo: [z-bot 运行时上下文]（本轮动态注入，不属于 system prompt）\n当前时间: 2026-09-26 13:53:35 +08:00 GMT+08:00\n---\nA0
                … 同上抬头 … \n---\nA1
                … 同上抬头 … \n---\nA2
                … 同上抬头 … \n---\nA3
  chat-B 回复=chat-B=echo: …\n---\nB0 / …\n---\nB1 / …\n---\nB2 / …\n---\nB3
[p16-wiring] 共享 transcript 角色序列 = [user, assistant]×8
```

⇒ 主编那句因果链复算成立：4+4 条回复各归各家、无 `Error:`、一个 session 一把租约、
`leaseTimeoutCount()==0`、transcript user/assistant 各 8 且交替 —— **P16 真担保的都没破**，
破的只有 `startsWith("echo: A")` 这一句字符串形状假设。

### 11.8.2 补丁文本（两处，直接套用）

**① `z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:377-385`**

```diff
         for (OutboundMessage m : chan.sent) {
             assertFalse("串行化失败的症状就是 Error: BotAgent 正在运行中 —— 实测 " + m.text,
                     m.text.startsWith("Error:"));
-            assertTrue("回显对不上说明两条会话串味了: " + m.text, m.text.startsWith("echo: A"));
         }
-        for (OutboundMessage m : chanB.sent) {
-            assertTrue("回显对不上说明两条会话串味了: " + m.text, m.text.startsWith("echo: B"));
-        }
+        // P12e：P12 之后回显文本是 "echo: " + <user 行>，而 user 行开头可能挂着运行时上下文块
+        // （抬头 + 时钟 + 记忆 + 技能 + "\n---\n"）—— 那是**请求侧的字符串形状**，不该由投递测试来钉。
+        // 钉的是**内容归属**：① 回显真发生了、② 结尾是自己的编号、③ 全文不出现对方编号、④ 四条覆盖 0..3。
+        assertOwnedBy(chan.sent, "A", "B");
+        assertOwnedBy(chanB.sent, "B", "A");
+        // 阳性对照（缺了它上面两支就是空跑）：把 chat-B 的一条真回复塞进 A 的视野 ⇒ 必须判红
+        List<OutboundMessage> poisoned = new ArrayList<OutboundMessage>(chan.sent);
+        poisoned.set(0, chanB.sent.get(0));
+        boolean crossTalkCaught = false;
+        try {
+            assertOwnedBy(poisoned, "A", "B");
+        } catch (AssertionError expectedRed) {
+            crossTalkCaught = true;
+        }
+        assertTrue("串味判据抓不到被塞进来的 chat-B 回复 ⇒ assertOwnedBy 是一把死尺", crossTalkCaught);
```

**② 同一个文件，`describe(List<OutboundMessage>)` 之前插入这个 private static 方法**

```java
    /**
     * 投递归属判据（替换掉 P12e 之前那句过期的 {@code startsWith("echo: A")}）：
     *  ① 每条回复都以 {@code "echo: "} 开头 —— 回显这一步真发生了；
     *  ② 每条的结尾必须是自己的编号 {@code <who>0..<who>3}（前面挂什么模板都不管）；
     *  ③ 全文里不许出现对方会话的任何编号 {@code <other>0..<other>3} —— 这才是"串味"本身；
     *  ④ 四条合起来必须覆盖 0..3 —— 缺一条就是漏投，形状断言抓不到这件事。
     * 四条都不认识 P12 的上下文协议，所以 P12 再怎么改模板也不会把它们顶歪。
     */
    private static void assertOwnedBy(List<OutboundMessage> replies, String who, String other) {
        assertEquals("先要凑够 4 条才谈归属", 4, replies.size());
        boolean[] seen = new boolean[4];
        for (OutboundMessage m : replies) {
            assertTrue("回显根本没发生（不是 echo 开头）: " + m.text, m.text.startsWith("echo: "));
            int mine = -1;
            for (int i = 0; i < 4; i++) {
                if (m.text.endsWith(who + i)) {
                    mine = i;
                }
                assertFalse("串味：" + who + " 的回复里出现了 " + other + i + " —— 原文 " + m.text,
                        m.text.contains(other + i));
            }
            assertTrue("回显结尾不是自己的编号 " + who + "0..3，实得 " + m.text, mine >= 0);
            seen[mine] = true;
        }
        for (int i = 0; i < 4; i++) {
            assertTrue("漏投：" + who + " 少了第 " + i + " 条", seen[i]);
        }
    }
```

**为什么这套判据"保留串味猎物"**：② 与 ③ 合起来就是原断言真正想说的东西（A 只会拿到 A、拿不到 B），
而且比原断言多抓两类原来漏网的事故 —— 重复投同一条（`seen` 覆盖不满 4）与漏投（`assertEquals(4,size)`）。
它**不认识** P12 的模板协议（不 import、不硬编码抬头文本），所以 P12 侧今后再改块内容也不会顶歪它。
本棒在临时 worktree 上实测：打了补丁 ⇒ 18/18 绿（同一支测试里那个"塞进 B 回复"的对照必须判红才拿得到绿，
所以这个绿不是把判据放宽换来的）。**该文件在禁改域，交付树里我没有落这一刀。**

## 11.9 §未做（一条不许美化）

1. **P16 那一句我没落刀**（`channel/*` 是禁改域、`p18a` 在飞）⇒ 交付树杠① 仍是 **640 / 1 红**，
   不是 637/0。补丁文本 + 临时 worktree 的实测读数在 §11.8，刀在主编手里。
2. **修复只保证"从现在起不再新增"，不追认历史**：改动之前落盘的那些 transcript（包括真 `~/.zbot/sessions/*.json`）
   里带抬头的 user 行**没有被清洗**，`switchSession()` 会原样读回 ⇒ 那种老会话再跑一轮时，请求里
   仍然带着旧的几块时钟（它们在 `content` 里，不归注入点管）。本棒**既没做迁移、也没为这件事写断言**
   （写了必红）。要治有两条路：`loadMessages` 侧一次性剥头（又回到"渲染面剥"那条被派单劝退的路），
   或者接受历史随会话自然淘汰。**这一条是"未覆盖"，不是"没问题"。**
3. **grace / steer 控制行的形状没钉**：注入点是"最后一行 user"⇒ 收尾轮（`maybeAnnounceGrace`）和
   steer 退路轮（`injectSteer` 走到 `memory.add(Msg.user(...))` 那一支）里，块挂在**控制行**上而不是原话行上。
   只数"一次请求一块时钟"是够的（我的断言量的就是这个），但"块必须挂在原话那一行"没测。
   钉它需要 identity 追踪 + 压缩后回落，比现在这版多一个状态机，我没做。
4. **M16 仍 PARTIAL、M8/M9/M17 仍 GREEN-BUT-MUTATED**：中断/检查点族本棒一字未碰，没有把它们往前推
   （p12d §2.2/§2.3 已记账，账还在）。
5. **M1 点名 2/3**（第三支 `soulStaysInSystemPromptAndMemoryGoesToUserMessage` 在 M1 下没红）：
   它与任务一同一族（记忆/SOUL 各归各位），本可以顺手补一层断言把 3/3 打满，**没补**。
6. **没走"拆独立 message"那条备选路**：把上下文块拆成单独一条 user 消息，能让 `chan.sent` 的最后一行
   回到纯 `A_i`、P16 一字不改就绿 —— 但代价是改 P12d 那条「块与原话同一条 user、原话在最后」的守门断言、
   每轮 user 行翻倍、且不同 provider 对连续同 role 消息的处理不一致。本棒判断"改一句过期断言"比
   "改一次请求契约"小，选了前者；这个取舍写在 §11.4，等主编复核。

---

# §12 主编收口棒：P12 并入 main 之前的四杠（被测树 = `bf9099c`，`@Test` 面 693）

> §10（p12d）与 §11（p12e）都停在同一个结论上：**杠②③④ 站住，杠① 差一条红**，
> 而那条红需要动 `channel/GatewayDeliveryP16Test.java`（两棒的禁改域）。
> 那一刀由我落（commit `f88c838`，判据改量尾巴 + 同条测试里补尺子自证），
> 之后 `main` 又前进了两格（P18 并入 `066d766` + 文档 `dd0d9c5`）⇒ **§10/§11 的读数已经不在"真要并入的那版字节"上了**，
> 所以本棒把四杠在 `main@dd0d9c5` × `w2-p12@f88c838` 的合并树上重打一遍。
> 本节所有数字来自我这一轮亲自跑的命令，日志一律在仓外 `~/.cache/zbot-p12-lead/`（`.gitignore:5` 是 `*.log` ⇒ 决定性读数原样贴下面）。

## 12.0 第 0 步实测（先证我自己这条链的前提）

| 假设 | 复算命令 | 实测 |
|---|---|---|
| 合并干净、无冲突 | `git merge-tree --write-tree f88c838 dd0d9c5 \| wc -l` | **1 行**，树 `2202d95a6ef43d1803baa6902c2eb9864753f9fc` |
| 我实际合出来的树 = 预览树 | `git rev-parse bf9099c^{tree}` | `2202d95a6ef43d1803baa6902c2eb9864753f9fc` ⇒ **与预览树逐字相同**（合并没夹带任何额外改动） |
| 两父提交 | `git rev-parse bf9099c^1 bf9099c^2` | `f88c838`（w2-p12 顶端，含我那一刀）× `dd0d9c5`（main 顶端，含 P18） |
| `@Test` 面可加 | `git grep -c '@Test' <rev> -- 'z-bot-core/src/test' \| awk -F: '{s+=$NF} END{print s}'` | main `dd0d9c5`=**672**（61 文件）／p12 顶端 `f88c838`=**640**（62 文件）／合并树=**693**（65 文件）⇒ **640 + 53（main 自 619 起的净增）= 693**，与"§11.4 的 640/1 红"面同一批 P12 用例 ⇒ 无互相吞并 |
| 我那一刀只动判据、没动产品 | `git show f88c838 --stat` | 唯一文件 `channel/GatewayDeliveryP16Test.java`，**+19 −2**；`git branch --contains f88c838` 只回 `w2-p12` ⇒ 尚未进 main |
| 新判据不是空跑（尺子自证） | 读 `f88c838` 的 diff 正文 | 同一条测试里前置两行：`assertTrue(echoesRoute("echo: 前缀\\n---\\nA2","A"))` 与 `assertFalse(echoesRoute("echo: 前缀\\n---\\nB2","A"))` ⇒ 判据必须分辨得了一条 A 尾、一条 B 尾，否则后面两个循环是空跑 |

## 12.1 杠① 全量 reactor 串行三跑（`rm -rf surefire-reports` + `mvn -o test`，无 `-pl`）

复算：`bash ~/.cache/zbot-p12-lead/bar1.sh`；日志 `~/.cache/zbot-p12-lead/merge_bar1_r{1,2,3}.log`。
循环自打的三行（逐字）：

```
run1 MVN_RC=0 [INFO] Tests run: 693, Failures: 0, Errors: 0, Skipped: 0 socket=0 build=1
run2 MVN_RC=0 [INFO] Tests run: 693, Failures: 0, Errors: 0, Skipped: 0 socket=0 build=1
run3 MVN_RC=0 [INFO] Tests run: 693, Failures: 0, Errors: 0, Skipped: 0 socket=0 build=1
BAR1_LOOP_DONE
```

| 项 | 复算命令 | 实测 |
|---|---|---|
| 三跑汇总行 | `grep -E 'Tests run: [0-9]+, Failures' <log> \| tail -1` | `Tests run: 693, Failures: 0, Errors: 0, Skipped: 0` ×3 ⇒ **§10.1/§11.4 那条唯一红（`GatewayDeliveryP16Test:381`）在这里消失** |
| 逐类求和交叉核对 | `python3` 解析 `-- in <class>` 行 | 三跑各 **65 类 / tests=693 / failures=0 / errors=0 / skipped=0** ⇒ 与汇总行自洽；**surefire 693 = `git grep -c '@Test'` 693**（§10.1 那个 483-vs-481 的 2 条差在本面不存在） |
| socket 类错误 | `grep -c 'BindException\|Connection refused\|SocketTimeout' <log>` | 三跑各 **0** |
| BUILD SUCCESS | `grep -c 'BUILD SUCCESS' <log>` | 三跑各 **1** |
| 每跑墙钟 | 日志内 `Total time` + `Finished at` | 28.831s / 26.678s / 26.666s（14:58:20 / 14:58:47 / 14:59:14）⇒ 三跑**串行**，不是并行凑数 |
| 三模块都进了 reactor | `grep -n 'z-bot-desktop-packager' <log>` | `7:` `[pom]`、`713-719:` `Building z-bot-desktop-packager 0.2.0 [3/3]` + `flatten` ⇒ 该模块仍**贡献 0 个测试类**（65 类全在 `z-bot-core`），与 §10.1 同口径 |

## 12.2 杠② 具名变异注入（合并树 19 支，跑了两遍：单层 + 两层）

复算：
`bash ~/.cache/zbot-p12-lead/bar2_e2e.sh`（先等单层那跑松手 ⇒ `SINGLE_CLEAN=0`，再打两层），
日志 `~/.cache/zbot-p12-lead/merge_bar2.log`（单层）与 `merge_bar2_e2e.log`（`--with-e2e` 两层，`MUT_RC=0`）。
两遍都独占同一把 flock：`LOCK-ACQUIRED mutator flock=LOCK_EX|LOCK_NB path=…/z-bot/.git/zbot-mutlock`。

| 项 | 复算命令 | 实测 |
|---|---|---|
| 五档分布（两遍一致） | 脚本自打 `== 台账 ==` + `python3 csv.DictReader` 独立复算 | **RED-OK 15 / PARTIAL 1 / GREEN-BUT-MUTATED 3 / BROKEN 0 / NO-RUN 0**；单层层与两层遍**同分布**；LEDGER 行数 `awk -F'\t' 'END{print NR-1}'` = **19**，列数 11 |
| 与 §10.2（p12d，637 面）的差额 | 同上比对 14/2/3/0 | **M1b 从 PARTIAL 收成 RED-OK**（点名 5/5）：p12e 把它第一遍跑里以 `extra` 出现的那支 `midRunMemoryWriteIsVisibleNextTurnWithoutTouchingThePrompt` 归入点名集（`p12_mutation.py:102` 写明了理由），**并入后判红能力没变**——本脚本的 RED-OK 规则是"点名有命中且无 extra"，那支测试在两遍里都真红了，变的只是记账口径不是检出力 |
| 逐支还原 | 脚本自打 `还原=` / `vs_git=` 两列 + 我独立量 `git status --porcelain -- z-bot-core` | 19/19 `还原=True vs_git=ok/ok`；`SRC_MD5_STABLE=yes`；跑完我另量一次 **`WORKTREE_DIRTY_CORE=0`**（15:16:52）与 **15:17:57 再量仍 0** |
| 两层（真进程层）是否真跑了 | `python3` 读 LEDGER 的 `e2e_rc` 列 | **M6 `e2e_rc=1`、M12 `e2e_rc=1`**，其余支 `e2e_rc=` 空（只有这两支是 `mvn+e2e` 型） |
| 两层跑在变异体上而不是旧 jar | 同两行的 `e2e_detail` 里 `B0` | M6：`打包 rc=0；jar sha256=788d96a2b6f295da；git HEAD=bf9099c；z-bot-core/src 未提交改动=1 行`；M12：`jar sha256=b3f3b7f730781733 … 未提交改动=1 行`；**干净树那一跑**（§12.3）是 `96fc4a58ad0d0238 … 未提交改动=无` ⇒ 三个 sha 各不相同，"未提交改动=1 行"就是当支注入的字节 |
| M6 摘掉看门狗之后真进程层的形状 | `python3` 读 `out/mutation-M6.json` 的非 PASS 行 | `S2 FAIL 复算 pgrep=['97773','97775']`（bash + sleep 两个 pid 停完之后还在进程表里）、`S3 FAIL elapsed_ms=-1`（waiter 的退出条件只有"pgrep 空集"一条 ⇒ 永远等不到）、`S4 FAIL chat 回包='(没回包)'` ⇒ **单测层那 1 支点名红不是唯一一层**，进程层同一条链也断 |
| M12 解冻之后真进程层的形状 | `python3` 读 `out/mutation-M12.json` 的非 PASS 行 | `C1 FAIL len1=2345 len2=2372 sha256_1=8b38a41d… sha256_2=2b4de935…`（同一会话两轮的 system prompt 逐字节漂）、`C5 FAIL 两轮 system 含哨兵=False/True` ⇒ 冻结不变量在进程层可量 |
| 未覆盖自述 | 脚本 `UNCOVERED_NOTE` | `exec 读输出循环的逐行检查点、mvn_build 入口检查点：摘掉之后看门狗仍在 50ms 内端掉进程树，从「多久断」这一面量不出差别，属于第二道保险；如实记未覆盖，不假装注入过。` ⇒ **§10.5 第 3 条那 5 支未完全判红项一支没被本棒补上**（M8/M9/M17 三支 GBM + M16 PARTIAL + M1 点名 2/3），补它们要加断言=加覆盖面，属于下一根棒 |

## 12.3 杠③ 真进程 E2E 三整跑 + K2 双向探针（693 面）

复算：`bash ~/.cache/zbot-p12-lead/bar3.sh`；日志 `merge_bar3_r{1,2,3}.log` + `merge_k2.log`。
循环自打（逐字）：

```
PKG_RC=0 jar=20159727 src_newer=0
run1 rc=0 段=all 检查条数=27 PASS=27 FAIL=0|
run2 rc=0 段=all 检查条数=27 PASS=27 FAIL=0|
run3 rc=0 段=all 检查条数=27 PASS=27 FAIL=0|
K2_RC=0 14 行含 OK
15:01:15
bar4: 8 2dadaed0 690ddbc0
BAR3_LOOP_DONE
```

| 项 | 复算命令 | 实测 |
|---|---|---|
| 三跑条数 | `grep -E '段=all 检查条数' <log> \| tail -1` | 三跑各 **27 条 / PASS=27 / FAIL=0**，rc 全 0；三份日志 md5 各不相同（`889010a7… / cbf54a5a… / dbc392cf…`）⇒ 不是同一份文件被读了三遍 |
| 判据不是靠 mtime 混过去的 | `p12_e2e.py` 的 B0/B0b 两行自打 | `B0 … jar sha256=96fc4a58ad0d0238；git HEAD=bf9099c；z-bot-core/src 未提交改动=无` / `B0b 开跑前 sha256=96fc4a58ad0d0238 / 收工后 sha256=96fc4a58ad0d0238` ⇒ 认的是**字节哈希**（`src_newer=0` 只当辅助，本战役已经两次被 mtime 骗过） |
| 段构成 | `grep -E '^\s+PASS' <log> \| wc -l` + 段标签计数 | 27 条：`[stop]` 5（G0/S1/S2/S3/S4）+ `[cache]` 8（C0/C0c/C1/C2/C3/C4/C5/C6）+ `[build]` 2（B0/B0b）+ `[home]` 3（H1/H2/H3）+ `[creds]` 3（K1/K2/K3）+ 其余 steer/R 族对照 |
| **计划里那句"2s 内断"** | 读 S3 原文 | `PASS S3 发 /stop → 工具子进程真退出 ≤ 2000ms … elapsed_ms=54，waiter pid=69038 由本进程 spawn、退出条件只有 pgrep 空集这一条` ⇒ **判据是"≤2000 ms"而不是"很快"**，实测 54 ms |
| pty 那条路能不能中断（反向对照） | 读 `R2b` 原文 | `PASS R2b 反面对照：chat 在飞时写进 pty 的 /stop 不会中断工具（进程还在） … 2s 后 pgrep 仍=['69082','69083'] ⇒ TerminalChannel.run() 在 agent.chat() 里同步阻塞、这段时间根本不读 stdin（实测出来的）` ⇒ **中断只走 `/bot/stop`**；roadmap 的验收措辞要改口径，见 §12.5 第 1 条 |
| K2 双向探针 | `python3 _doc/acceptance/p12/p12_k2_probe.py; echo rc=$?` | `rc=0`，7 支 case × 工作树/封存两份字节 = **14 行 `OK`**；含假红侧 F（`api.key=not-configured` ⇒ 期望 FAIL 实测 FAIL）；结论行：`双向实测结论：全部符合期望 ⇒ (a) 两形态并存判绿 与 (b) 混入别的值判红 两边都成立，判据没有被调松` |

## 12.4 杠④ `~/.zbot` 一字未动（本棒四个时点）

| 时点 | 复算命令 | 条目数 | config.properties 前 8 | state.db 前 8 |
|---|---|---|---|---|
| 杠③ 收尾自打（15:01:15） | 同上（在 `bar3.sh` 最后一行） | **8** | **2dadaed0** | **690ddbc0** |
| 杠② 两层跑完之后（15:16:52） | 同上（在 `bar2_e2e.sh` 倒数第二行） | **8** | **2dadaed0** | **690ddbc0** |
| 我另起一次复核（15:17:57） | 同上 | **8** | **2dadaed0** | **690ddbc0** |

**一处缺读，写在这里而不是编上**：杠① 起跑前（14:57）我这一轮**没有单独量过杠④** ——
`bar1.sh` 里没带这一行，所以那一时点没有我的读数可贴。跨时点的连续性由 §10.4（p12d 在 merge 前量的
同一组三时点）+ 本节 15:01:15 起三点接上；如果 14:57 那一刻被改过，本面量不出来。

外加 E2E 量具在每一跑里自带的第三方读数（三跑同值）：
`~/.zbot 跑前跑后: 项数 8→8, config md5 前缀 2dadaed0, state.db md5 前缀 690ddbc0`。
真 key 全程未被读、未被打印、未被复制、未进任何日志：所有真进程跑都走 `p12_e2e.py` 自己的临时根 + `stub-key-not-real`。

## 12.5 §未做（本棒，一条不许美化）

1. **roadmap 里 P12 的验收措辞和实测量不是一件事，我按实测改口径**：计划写的是"真 pty/管道 REPL 跑一次
   '长命令执行中 /stop 能在 2s 内断'"，而 S3 断的是**走 `/bot/stop` 这条路**；`R2b` 反过来实测出
   **pty 里敲 /stop 根本不生效**（`TerminalChannel.run()` 同步阻塞在 `agent.chat()` 里，这段时间不读 stdin）。
   ⇒ 中断能力本身站住了（工具侧检查点 9 处：`git grep -cF "InterruptScope.checkpoint()"` =
   `BuiltinTools.java:7` + `DelegateManager.java:2`，达成计划里"≥6 处"），但**"在 REPL 里边跑边按停止"这条路仍未通**，
   要做的是给 TUI 加一个能读的旁路（新能力，本棒没做，也没为它写断言）。
2. **杠② 的 5 支未完全判红项一支没补**：M8/M9/M17 仍 GREEN-BUT-MUTATED、M16 仍 PARTIAL（点名 5/6）、
   M1 仍点名 2/3（`soulStaysInSystemPromptAndMemoryGoesToUserMessage` 在 M1 下不红）。
   这些账从 §10.5 第 3 条原样结转，本棒只重跑不补面。
3. **`UNCOVERED` 那两处依旧没有量具**：`exec 读输出循环的逐行检查点`、`mvn_build 入口检查点`——
   摘掉之后看门狗仍在 50ms 内端掉进程树，从"多久断"这一面量不出差别 ⇒ 属于第二道保险，
   如实记未覆盖（不是"已经检不出问题"，是"这一面检不了"）。
4. **§11.9 第 2 条那条历史包袱没治**：`86e7b83` 只保证"从现在起上下文块不再落进持久化转录"，
   改动之前已经落盘的 transcript（含真 `~/.zbot/sessions/*.json`）里带抬头的 user 行**没被清洗**，
   `switchSession()` 原样读回 ⇒ 老会话再跑一轮，请求里仍带着旧的那几块时钟。本棒**没做迁移、没写断言**（写了必红）。
5. **§11.9 第 3 条"块挂在控制行还是原话行"没钉**：注入点是"最后一行 user"，收尾轮（grace）与
   steer 退路轮里块挂在控制行上；只数"一次请求一块时钟"够，"必须挂在原话那一行"没测。
6. `z-bot-desktop-packager` 仍贡献 **0 个测试类**（65 类全在 `z-bot-core`）⇒ 该模块的打包/构建不在证据面内。
7. 本棒**没 push、没 merge 到 main、没 reset/clean/stash**；只在 `w2-p12` 上落文档一笔（本节）。
   与 `w3-p21`（p21c 在飞）互不重叠：我这两跑全程独占 `zbot-mutlock`，p21c 若要打杠② 会拿 `rc=4` 退避。

## 12.6 判词：P12 现在具备并入 main 的门禁面

| 杠 | 一次跑齐了吗 | 关键读数 |
|---|---|---|
| ① 全量 reactor ×3 串行 | 是 | `Tests run: 693, Failures: 0, Errors: 0, Skipped: 0` ×3（MVN_RC=0 / BUILD SUCCESS=1 / socket 类 0）；surefire 693 = `git grep -c '@Test'` 693 = 65 类逐类求和 693 |
| ② 具名变异 19 支 | 是（两遍：单层 + 两层） | RED-OK 15 / PARTIAL 1 / GBM 3 / BROKEN 0 / NO-RUN 0；`SRC_MD5_STABLE=yes`；`WORKTREE_DIRTY_CORE=0`；M6/M12 两层 `e2e_rc=1` 且变异体 jar sha 各不相同 |
| ③ 真进程 E2E | 是（3/3 整跑） | `段=all 检查条数=27 PASS=27 FAIL=0` ×3；K2 双向探针 `rc=0` / 14 行 OK（含假红侧）；阳性对照见 §10.3.3 |
| ④ `~/.zbot` | 是（四时点同读数） | 8 / `2dadaed0` / `690ddbc0` |

§10.6 那句"P12 不具备并入 main 的门禁面，唯一拦路的是杠① 那条红"已经不再成立：
那条红的判据由我在 `f88c838` 改量尾巴（带尺子自证），合并树 `bf9099c` 的杠① 三跑 **693/0 全绿**。
