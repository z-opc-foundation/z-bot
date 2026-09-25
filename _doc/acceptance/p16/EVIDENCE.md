# P16 验收证据（杠①②③④）

* 分支 / worktree：`w2-p16ev` @ `/private/tmp/zbot-wt-p16ev`，基线 `235a9a7`（= `w2-p16`，未动）
* 产出人：杠①② = 写手代理 `p16ev`（工单 `dispatch_p16evidence.md`，被 150 轮截断，产物由主编封存成
  `ea538b9`）；杠③④ + 杠① 真三跑 + M07 处置 = 续棒 `p16ev2`（工单 `dispatch_p16ev2.md`）。
  日期 2026-09-26（本机 darwin 25.5，java 1.8/25 混用无碍，mvn -o 离线，python 3.14）
* **两棒加起来一条产品代码都没改**：`git diff --name-only 235a9a7..HEAD` 只有 `_doc/acceptance/p16/`
  里的四个交付物 + 一支 `.gitignore`（见「全树未被改动」那节的 md5 与 `git log` 对拍）；
  `_doc/hermes-roadmap.md` 一个字没动（共享文件，避开撞车）

本文件里每个数字都跟着产生它的那条命令。判定只认 surefire XML 里的具名 testcase，
`mvn` 退出码非 0 一律不算证据（照 p11b/p17 的纪律）。
`*.log` 被根 `.gitignore:5` 挡着 ⇒ 凡结论依赖的读数**都粘了原文**在本文件里，日志目录丢了也能复算。

---

## 杠① 全量单测（p16ev2 续棒：串行真三跑，不复用上一棒那两条只差 2 秒的日志）

命令（worktree 根目录，**串行** for 循环，每跑前 `rm -rf surefire-reports`；驱动脚本
`~/.cache/zbot-p17/bar1_three_runs.sh`，全量 stdout 在 `logs/bar1_seq_r{1,2,3}.log`）：

```
for i in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; mvn -o test; done
```

三跑原文读数（每条都取自它自己那支日志）：

```
logs/bar1_seq_r1.log:  [INFO] Tests run: 481, Failures: 0, Errors: 0, Skipped: 0
                       [INFO] BUILD SUCCESS   MVN_EXIT_RUN_1=0   03:30:18 start → 03:30:37 end
logs/bar1_seq_r2.log:  [INFO] Tests run: 481, Failures: 0, Errors: 0, Skipped: 0
                       [INFO] BUILD SUCCESS   MVN_EXIT_RUN_2=0   03:30:37 start → 03:30:55 end
logs/bar1_seq_r3.log:  [INFO] Tests run: 481, Failures: 0, Errors: 0, Skipped: 0
                       [INFO] BUILD SUCCESS   MVN_EXIT_RUN_3=0   03:30:55 start → 03:31:13 end
```

三跑**首尾相接、没有重叠**（r1 end 03:30:37 == r2 start 03:30:37；r2 end == r3 start 03:30:55），
每跑 ~18 s ⇒ 上一棒那两条 mtime 只差 2 秒的日志（`bar1_full_test.log` / `bar1_full_test_r1.log`）
确实不构成独立复跑，本节的三条才算。跑前 `ps -o pid,etime,command -S -o command= -e | grep -c '[m]vn '` = **0**
（没有邻居的 mvn 在同刻抢编译）。

每跑另用一把独立尺数 surefire XML（不认 mvn 的汇总行）：

```
python3 -c "import glob,xml.etree.ElementTree as ET; ... print('classes=%d tests=%d failures=%d errors=%d skipped=%d')"
→ 三跑各自：classes=48 tests=481 failures=0 errors=0 skipped=0
```

账也对得上（`@Test` 静态计数，不靠跑）：

```
for f in $(git ls-tree -r --name-only d71657d | grep 'Test\.java$'); do git show d71657d:$f | grep -c "@Test"; done | awk '{s+=$1} END{print s}'   → 399
同上取 235a9a7                                                                                                                              → 481
本期五支新类（235a9a7）@Test 数：DeadTargetsTest 15 / DeliveryLedgerTest 22 / GatewayDeliveryP16Test 18 / SupervisorTest 16 / TurnLeaseTest 11 = 82
399 + 82 = 481 ✓
```

这三跑是在**杠② 那一轮注入（02:43 结束）之后**跑的，且跑前/跑后五支被测源文件的 md5 与 LEDGER.tsv 里
`# md5 ... before=` 一字不差（见「杠③ 前置核对」与「全树未被改动」两节）⇒ 它们同时充当
"注入逐字节还原不是空话"的全量复核。

---

## 杠② 注入自证（`_doc/acceptance/p16/p16_mutation.py`）

命令：

```
python3 -u _doc/acceptance/p16/p16_mutation.py            > logs/mutation_full_r2.log
```

读数（LEDGER.tsv 由脚本机械写出）：

| 项 | 读数 |
| --- | --- |
| 变异体总数 | 24 |
| 每轮跑的用例数 | 82（五支测试类，分母钉成 82 条**标题清单**常量 `EXPECTED_TESTS`，标题不齐 ⇒ 该轮判 NO-RUN 而不是收数） |
| RED-OK | **22** |
| PARTIAL | **1**（M09，见下） |
| GREEN-BUT-MUTATED | **1**（M07，**故意**留的空点名集，见「未覆盖」） |
| BROKEN | 0 |
| NO-RUN | 0 |
| SRC_MD5_STABLE | **yes**（五支被测源文件注入前/后 md5 逐字节相同，LEDGER.tsv 里每支都打了 before/after 对拍行） |

### 工单要求的 8 条闸 ⇒ 变异体 ⇒ 判红的具名用例

| 闸 | 变异体 | 点名的测试（真判红） |
| --- | --- | --- |
| ① 先落账再发 | M01 send 前不落 attempting / M02 整段不落账 | `obligationIsOnTheLedgerBeforeTheSendSideEffectStarts`、`deliveredObligationConvergesAndASecondSweepDoesNotResend`、`statusExposesTheSelfHealingReadings`、`chatLevelNotFound…`、`subChatLevel…`、`unregisteredSourceChannel…`（M02 六条全红） |
| ② attempting 崩溃重投带「可能重复」前缀 | M13 `needsMarker` 写死 false | `crashMidAttemptIsRedeliveredWithDuplicateWarning`、`failedObligationIsRedeliveredWithMarker`、`attemptingRowOwnedByADeadProcessIsRedeliveredWithDuplicateWarning`、`markerIsNotStackedWhenARowIsClaimedTwice` |
| ③ 同一条被认领两次时前缀不叠加 | M14 摘掉 `startsWith(RECOVERED_MARKER)` 幂等护栏 | `markerIsNotStackedOnReDelivery` |
| ④ 404 注册死目标 + 后续短路 | M04 不登记 / M05 登记了不短路 / M06 重投成功不清标记 / M18 `not_found` 不算死 / M19 不读 cause 链 | `chatLevelNotFoundRegistersTheTargetAndShortCircuitsLaterSends`、`bootRedeliveryIsTheSelfHealPathForADeadTarget`、`unclassifiableFailuresAreUnknownAndNeverDead`、`classificationLooksAtExceptionMessageToo`、`causeChainIsReadUpToADepthCapAndNeverLoops` |
| ⑤ 子会话级 404 不得把整个 chat 注册成死目标 | M17 子会话标记返回 `NOT_FOUND` | `chatLevelNotFoundIsDeadButSubChatLevelIsNot`、`subChatLevelNotFoundNeverRegistersTheWholeChat` |
| ⑥ 来源通道未在册记 failed 而非 silently delivered | M03 那行改成 `STATE_DELIVERED` | `unregisteredSourceChannelIsRecordedFailedNotSilentlyDelivered` |
| ⑦ 3 次/60s 熔断跳过自动续跑 | M08 只记账不跳过 / M09 空启动也攒账 / M20 阈值 off-by-one / M21 窗口拉到 1 小时 | `thirdInterruptedBootWithinTheWindowSkipsAutoContinuation`、`cleanBootWithNothingToResumeChargesNothing`、`thirdInterruptedBootInSixtySecondsTripsTheBreaker`、`bootsOutsideTheWindowAreForgotten` |
| ⑧ 活实例锁拒绝双启 + 干净退出删掉自己那把锁 | M10 stop 不 release / M22 存活判定反过来 / M23 release 不看归属 / M24 无 profile 也落盘 | `cleanStopRemovesOurOwnInstanceLock`、`liveInstanceLockRefusesADoubleBootAndOwnsWhatItWrote`、`staleLockIsTakenOverAndLiveLockRefusesADoubleBoot`、`releaseRemovesOnlyOurOwnLock`、`lockIsNotPersistedWithoutAProfile` |

另外三条盯的是台账侧的"别把预算烧双份/别白烧"（工单没点名，属于本期同一批守卫）：
M11（活 owner 的行也抢 ⇒ `rowsOwnedByALiveProcessAreLeftAlone`、`secondSweeperBacksOffOnceALiveOwnerHoldsTheRow` 红）、
M12（本次发不出去的也认领 ⇒ `sweepOnlyClaimsRowsThisBootCanActuallyDeliver` 红）、
M15（尝试预算不封顶 ⇒ `exhaustedAttemptBudgetTurnsAbandonedInsteadOfSpinning`、`staleObligationIsAbandonedNotReplayed` 红）、
M16（把 `delivered` 也放进认领范围 ⇒ `deliveredRowIsNeverClaimedAgain` 红）。

### PARTIAL 如实记账（没洗）

`M09 闸⑦ 干净启动也给熔断器攒账` — 点名的 2 条红了，另有第 3 条一起红：
`markerIsNotStackedWhenARowIsClaimedTwice`。
因果判断：摘掉 `claimed.isEmpty() ⇒ return 0` 之后，**setUp 那次无活可续的启动也向熔断器记了一笔**，
于是该用例里第三次带活启动的 `recoverPendingDeliveries()` 被熔断挡下（返回 -1 而不是 1）。
这不是测试互相污染的假红，正是"干净启动攒账"的下游后果 —— 但按纪律**仍然记 PARTIAL**，
不把它挪进期望集洗成 RED-OK。r1/r2 两次整跑读到的红集合完全一致（⇒ 不是抖动）。

### 量具自己坏过的一条（记下来，免得后人以为那是"测到了"）

M16 第一版只往 SQL 里多加了一个 `?` 而没有配 `setString(4, DELIVERED)` ⇒ SQLException 被
`sweepRecoverable` 自己吞成空表 ⇒ 82 条全绿。r1 里它被如实记成 GREEN-BUT-MUTATED
（`logs/LEDGER_r1_superseded.tsv`）。那是**注入没注进去**，不是守卫守住了 ⇒
按工单"必须换成能编译的等价旧写法重做"改成 SQL + 绑定参数一起改，r2 里 M16 = RED-OK。

### 互斥锁（flock，跨编队）双向实测

锁文件 = `$(git rev-parse --git-common-dir)/zbot-mutlock`
= `/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock`（主树的 git 目录，
各 worktree 算出来是同一个 ⇒ 能跨编队）；实现只用 `fcntl.flock(fd, LOCK_EX|LOCK_NB)`，**没有用 `fcntl.lockf`**。

① 邻居真攥着时本脚本拒跑且一个字节都不改：

```
python3 -u _doc/acceptance/p16/p16_mutation.py --hold-lock 100     # 真进程 pid=9165 攥住 flock
    → logs/locktest_holder.log: "LOCK-HELD pid=9165 攥住 100.0s（不碰任何源文件）"
python3 -u _doc/acceptance/p16/p16_mutation.py                      # 另起一支真跑
    → EXIT_WHEN_BUSY=4
    → "FATAL 互斥锁被别的注入脚本攥着（[Errno 35] Resource temporarily unavailable）⇒ 本轮不跑，一个源文件都不碰"
diff logs/locktest_md5_before.txt logs/locktest_md5_after.txt   → MD5_IDENTICAL=yes（五支被测源文件）
diff logs/locktest_status_before.txt logs/locktest_status_after.txt → STATUS_IDENTICAL=yes（全树 git status --porcelain）
```

② 邻居松开后本脚本照常拿得到锁：`kill 9165` 之后再跑 ⇒ 24 轮全部执行完，
`logs/mutation_full_r2.log` 末行 `EXIT=0`，`SRC_MD5_STABLE=yes`。

（附：第一次"拒跑"读数差点被记错 —— 那条命令里用了 `timeout 60`，本机没有这个命令，
`EXIT_WHEN_BUSY=127` 是"命令没跑成"而不是"被锁挡下"。已复测成上面的 4。这条按
"坏读数也要复测"记账。）

---

## 全树未被改动（"产品代码一行都不许改"的核对）

```
$ git diff --name-only 235a9a7          # 从基线到当前工作树，改过的路径
_doc/acceptance/p16/.gitignore
_doc/acceptance/p16/EVIDENCE.md
_doc/acceptance/p16/LEDGER.tsv
_doc/acceptance/p16/p16_e2e.py
_doc/acceptance/p16/p16_mutation.py     ← 只有验收工件，没有一支 .java

$ git diff --name-only 235a9a7 HEAD -- z-bot-core        → （空）
```

五支被测源文件"基线 blob vs 盘上字节"逐支对拍（`logs/` 里那一轮之后仍然是这个结果）：

```
$ for f in ChannelBus Gateway DeliveryLedger DeadTargets Supervisor; do
    p=z-bot-core/src/main/java/com/zifang/z/bot/channel/$f.java
    b=$(git show 235a9a7:$p | md5 -q); w=$(md5 -q $p); ...
  done
ChannelBus     235a9a7=7929741fc6206d82b62ad7f0f39c206e disk=7929741fc6206d82b62ad7f0f39c206e SAME
Gateway        235a9a7=cf258ce1496dccfa3a2039a42cb0fb02 disk=cf258ce1496dccfa3a2039a42cb0fb02 SAME
DeliveryLedger 235a9a7=8318d1f95eff1342845917cda5c078f4 disk=8318d1f95eff1342845917cda5c078f4 SAME
DeadTargets    235a9a7=f9f227fedd8bba6e8451736b63b4d7a6 disk=f9f227fedd8bba6e8451736b63b4d7a6 SAME
Supervisor     235a9a7=9edd95d3fc7ce1c070894668e3d53a93 disk=9edd95d3fc7ce1c070894668e3d53a93 SAME
```

这五个值同时就是 LEDGER.tsv 里 5 条 `# md5 … before=… after=…`、也是杠③ 整跑前后各量一次的那五个
（见「杠③ 前置核对」）⇒ 注入还原、E2E、杠① 三跑量的是**同一份字节**。

本目录的运行捕获（`logs/`、`out/`）按 `_doc/acceptance/p16/.gitignore` 不入版本库
（`*.log` 本来就被根 `.gitignore:5` 挡着，`out/` 是 2.7MB+ 的 db/jvm 捕获）⇒
`git status --porcelain` 收尾是空的，而结论读数全在本文件里。

### LEDGER.tsv 的复算式（`#` 注释尾会让裸 `csv.DictReader` 出垃圾行）

```
$ python3 -c "import csv;rs=[r for r in csv.DictReader(open('LEDGER.tsv'),delimiter='\t') if (r.get('verdict') or '') in ('RED-OK','PARTIAL','GREEN-BUT-MUTATED','BROKEN','NO-RUN')];print(len(rs))"
24
$ python3 -c "import csv;print(len(list(csv.DictReader(open('LEDGER.tsv'),delimiter='\t'))))"
35      ← 裸读会把 11 行 `#` 注释也当成记录（多出垃圾行），所以复算必须带 verdict 白名单
```

## 杠③ 真实进程 E2E（`_doc/acceptance/p16/p16_e2e.py`，p16ev2 续棒跑成）

### 上一棒三次全段都崩在量具自己的 Python bug（如实记账，不算"测到了"）

| 跑次 | 崩在哪 | traceback 里的异常名（原文） |
| --- | --- | --- |
| `logs/e2e_full_r1.log` | `section_b` | `AttributeError: 'str' object has no attribute 'tag'` |
| `logs/e2e_full_r2.log` | `section_a` 收尾 | `NameError: name 'best' is not defined. Did you mean: 'burst'?` |
| `logs/e2e_full_r3.log` | `section_b` 的 B7 | `AttributeError: 'str' object has no attribute 'tag'`（行号 676 与本棒接手时的字节完全对得上 ⇒ **这一条在盘上还没修**） |

三段都没跑到结尾 ⇒ 上一棒没有"一次调用跑完 A/B/C"的读数，只有分段冒烟（`e2e_A_smoke3.log` 13/13、
`e2e_C_smoke.log` 8/8）⇒ 按工单口径 **杠③ 当时未成**。

### 本棒对量具做的事（只改 `p16_e2e.py`，产品代码一行未动）

1. `refused = [t.tag for t, tx in (("B-gw1", lt1), …)]` —— 元组第一个元素本来就是字符串标签 ⇒ 改回 `t`。
2. `section_a` 的 `best` 早就是 `best_all`（那条 NameError 在盘上已被上一棒的最后一次编辑改掉；
   本棒以**当前字节**重跑取证，不再引用它任何旧读数）。
3. **杀人时机换成盘上因果量具**（这部分是上一棒最后一次编辑留下的，本棒复跑确认它真咬得住）：
   不再拿"我抽到第 N 条回执"当杀人时机（那量的是我自己抽水的速度），而是读 state.db 副本，
   直到同时看到「未结清」与「已结清」两类行才 `SIGKILL`；`kill9()` 内部 `proc.wait()` ⇒ 等到真退出才读盘。
4. **B 段双开不再掷硬币**：`tryAcquireInstanceLock()` 只看 pid+启动时刻，两个 JVM 同时读到
   "归属进程已死"的那一瞬间谁都拒启 ⇒ B7 时好时坏。改成
   起 g1 → **读锁文件确认已写到 g1 名下**（新增 B1b 一条判定）→ 再起 g2。
   B7 的判据同时加上"锁内容仍在 g1 名下"（盘上对账），不再只看日志。
5. **C4 的判据从日志行换成盘上状态**：`Supervisor.java:497` 那行『发现陈旧网关锁』是先打的，
   锁文件在 `:502-523` 才落盘（P17 实测日志→落盘 0.1–8.8 ms 窗口，10 次里 2 次读到旧值）⇒
   原来 `wait_log(...) and 立刻读锁内容` 是撞运气。改成 `wait_until(锁内容 == 新实例的 pid)` 判"已接管"，
   日志行降级为旁证一起打。C1 同样换成读盘轮询。
6. **X0 恒 FAIL 的死 bug**：`caught = {path: v for … for v in offenders(b)}` 的 value 是**单个字符串**，
   判定式 `for v in vals` 于是按**字符**迭代 ⇒ 哨兵永远判不到、X1 实际是空跑。改成 value 为 list、按元素判。
7. **X3 收紧**（不是放松）：原来只认 header 里有没有 `earer` 子串，换个凭证头名（`x-api-key` 等）就漏；
   改成按**头名**逐个核：出口请求必须有凭证头、且值必须是 stub。实测凭证头名 = `['authorization']`。
8. **段内抛异常不再吞掉整跑**：每段包一层 `try/except`（异常记成一条 FAIL 并打 traceback 首行），
   结尾恒打 `== E2E: N/M 通过 ==` 与分段计数 —— 上一棒"崩在半路、结尾什么都没有"这种读数不再可能出现。

### 前置与命令（`~/.cache/zbot-p17/run_e2e_twice.sh` 一次串起 jar 重建 + 两次整跑）

```
mvn -o -pl z-bot-core package -DskipTests                    → logs/jar_package_r2.log（PACKAGE_RC=0，
                                                               jar 20,071,959 B @ 03:35）
ZBOT_HOME=$HOME/.cache/zbot-p17/p16ev-home \
  python3 -u _doc/acceptance/p16/p16_e2e.py                  → logs/e2e_full_r4.log（03:35:17→03:37:03）
ZBOT_HOME=$HOME/.cache/zbot-p17/p16ev-home \
  python3 -u _doc/acceptance/p16/p16_e2e.py                  → logs/e2e_full_r5.log（03:37:03→03:38:49）
```

### 总判定行原文（两次独立整跑，同字节）

```
logs/e2e_full_r4.log:  == E2E: 33/33 通过（整跑：A/B/C/D/X 全段一次进程级运行）==   E2E_RC_R4=0
logs/e2e_full_r5.log:  == E2E: 33/33 通过（整跑：A/B/C/D/X 全段一次进程级运行）==   E2E_RC_R5=0
```

分段判定条数（同一次进程级运行内跑完全部五段）：

```
---- 段 A：11/11 通过 ----   ---- 段 B：8/8 通过 ----   ---- 段 C：6/6 通过 ----
---- 段 D：2/2 通过 ----     ---- 段 X：6/6 通过 ----   （r4、r5 两次一字一样）
```

两次结论是否一致，按**判定名清单**逐条 diff（不只看总分）：

```
diff <(grep -E "^(PASS|FAIL)" logs/e2e_full_r4.log | cut -c1-70) \
     <(grep -E "^(PASS|FAIL)" logs/e2e_full_r5.log | cut -c1-70)      → 无输出（TWO_RUN_CHECKLIST_IDENTICAL）
grep -c "^FAIL" logs/e2e_full_r4.log logs/e2e_full_r5.log             → 0 / 0
```

### 每段的关键读数（全部引自 `logs/e2e_full_r4.log`；r5 的对应读数同形，行数/pid 不同）

真 JVM 数：**9 支**（`X5 … 起过 9 个 JVM`），全部 `--config-dir`/`ZBOT_HOME` 指临时 profile。
出口 LLM 请求 **240 次**（`X3`）⇒ 不是"看起来起来了"，是真跑了一轮 agent。

A 段（投递在飞 `kill -9` ⇒ 账还在 ⇒ 重启带标记重投并收敛）：

```
PASS A1  崩溃前真在投递（kill 前抽到 ≥1 条回执，不是空跑的现场）   kill -9 前抽到 47 条回执；pid=73646 已 SIGKILL
PASS A2  kill -9 之后盘上台账还在（sqlite3 独立量具读 state.db 副本） sqlite3 读到 60 行；kill 时未结清 16 行 状态分布={'delivered': 44, 'pending': 13, 'attempting': 3}
PASS A3  未结清的行不许被记成 delivered（账实相符）              未结清 16 + 已结清 44 = 总 60
PASS A4  kill 时同时抓到 pending 与 attempting/failed 两类窗口     kill 时在飞的状态集合=['attempting', 'pending']
PASS A5  重启的 gateway 起来了（同一 profile）                  pid=73918 lsof 监听数=1；日志: ['[gateway] 断点重投：认领 16 条，发出 16 条']
PASS A6  重启后台账全部收敛成 delivered 且行数不减               sqlite3 读到 60 行；kill 前 60 行 → 现在 60 行 状态分布={'delivered': 60}
PASS A7  未结清的每一条都被重投且只投一次                        未结清 16 行 / 收到 16 条（各条次数=[1]）
PASS A8  pending 行重投不带「可能重复」前缀                      违规=无（pending 行数=13）
PASS A9  attempting/failed 行重投必须带且只带一层 ♻️ 前缀          前缀错=无 叠加=无（歧义窗口行数=3）
PASS A10 每条重投只烧一次重投预算（attempts 恰 +1）               烧了双份/没烧=无
PASS A11 kill 时已结清的行没有被再投一遍                         重复结清=无
```

A 段不是空跑的两处硬钉子：`A4` 要求 `pending` 与 `attempting` 两半**同时**在场（缺哪半就当场记未覆盖），
`A8/A9` 的猎物数（13 行 pending / 3 行歧义窗口）写在读数里；`MARKER` 是从被测源码
`DeliveryLedger.RECOVERED_MARKER` 现场读出来的（32 字符），没抄第二份。
上一棒 `e2e_full_r2.log` 的 A 段是 20 行全 delivered、未结清 0 行 ⇒ A7–A11 全是"零猎物的 PASS"，
那一批读数本棒**一律不引用**。

B 段（两个真 JVM + 同一 profile，同刻只有一个真发）：

```
PASS B1  造出未结清的现场                        3 次 SIGKILL 最多只抓到 29 行未结清 ⇒ 这一段无从判定
PASS B1b 第一个实例起来后锁真写到它名下（读盘对账）   pid=74819；锁内容={"pid":74819,"started_at":1790364997000}
PASS B2  两个真 JVM 同刻都活着且各自在听自己的端口（按 pid 过滤 lsof） pid 74819→1 / pid 74872→1
PASS B3  同一条义务两边加起来只发了一次（没有双发）    未结清 29 行 / gw1 收 29 条 / gw2 收 0 条
PASS B4  收到的会话 id 没有一条出现两次              重复=无
PASS B5  盘上每条义务的 attempts 只被烧 1 次          异常=无（共 29 行）
PASS B6  台账最终收敛                              状态分布={'delivered': 55}
PASS B7  实例锁在真双开时判死了一个                   拒启日志出现在=['B-gw2']；锁仍在 B-gw1(pid=74819) 名下=True
```

C 段（干净退出删自己的锁 / 崩退出留锁并被下一个实例自愈 / 拒启者不许删别人的锁）：

```
PASS C1 起来时锁真在盘上且写着自己的 pid      lock=<profile>/gateway/gateway.lock 内容={"pid":75437,…}
PASS C2 干净退出（SIGTERM 走 shutdown hook）把**自己那把**锁删了   pid=75437 退出后 lock 文件存在=False
PASS C3 崩退出（SIGKILL）删不掉锁：文件还在且仍写着死掉的 pid   SIGKILL 前={"pid":75441,…} 后={"pid":75441,…}
PASS C4 下一个实例把陈旧锁判出来并自愈接管（锁内容换主=盘上判据；日志行作旁证） 盘上锁已换成 pid=75451=True；『陈旧网关锁』=True
PASS C5 双开里没拿到锁的那个退出时，没把别人的锁删掉   第三个实例 pid=75456 拒启后 terminate；锁内容变了=False；持锁的 pid=75451 还活着=True
PASS C6 真持锁的那个干净退出后锁被删（不是被拒启者删的） pid=75451 terminate 后 lock 存在=False
```

D 段、X 段的读数在「杠④」一节里逐条给（它们就是杠④的量具读数）。

### 杠③ 前置核对：跑 E2E 的字节与杠② 的基线字节是同一份

```
md5 -q z-bot-core/src/main/java/com/zifang/z/bot/channel/{ChannelBus,Gateway,DeliveryLedger,DeadTargets,Supervisor}.java
跑前（03:35）= 跑后（03:38）=
  7929741fc6206d82b62ad7f0f39c206e   (bus)
  cf258ce1496dccfa3a2039a42cb0fb02   (gw)
  8318d1f95eff1342845917cda5c078f4   (led)
  f9f227fedd8bba6e8451736b63b4d7a6   (dead)
  9edd95d3fc7ce1c070894668e3d53a93   (sup)
→ 与 LEDGER.tsv 里 5 条 `# md5 … before=… after=…` 逐支相同
```

### 本期发现的产品缺陷（杠③ 段）

**无。** 杠③ 两次整跑 33/33 全绿，且没有为了跑绿放宽任何断言（上面第 7 条反而是**收紧**）。
崩的三次全部是量具自身的 Python bug，异常名与文件行号已在上表钉死。

---

## 杠④ `~/.zbot` 未被污染（三个数一条命令 + E2E 的 D/X 段对账）

```
$ echo "ls -A ~/.zbot | wc -l = $(ls -A ~/.zbot | wc -l | tr -d ' ')"; \
  echo "config md5 prefix = $(md5 -q ~/.zbot/config.properties | cut -c1-8)"; \
  echo "state.db md5 prefix = $(md5 -q ~/.zbot/state.db | cut -c1-8)"; \
  echo "ls -A 清单 = $(ls -A ~/.zbot | tr '\n' ' ')"
ls -A ~/.zbot | wc -l = 8
config md5 prefix = 2dadaed0
state.db md5 prefix = 690ddbc0
ls -A 清单 = .stty.bak config.properties cron memories models-cache.json sessions state.db workspace
```

三个数与主编给的基线（8 项 / `2dadaed0` / `690ddbc0`）一致。E2E 自己前后各量一次（同一份量具、
每次整跑的首尾各一次）：

```
logs/e2e_full_r4.log:  杠④ 基线：~/.zbot 顶层 8 项，md5 前缀={"config.properties": "2dadaed0", "state.db": "690ddbc0"}
logs/e2e_full_r4.log:  PASS D1 ~/.zbot 顶层项数不变（没被写过新文件）                    前 8 项 / 后 8 项；新增=无 消失=无
logs/e2e_full_r4.log:  PASS D2 config.properties / state.db 的整文件 md5 前后一致（只打 8 位前缀，永不打印内容）
                        前={"config.properties": "2dadaed0", "state.db": "690ddbc0"} 后=同 一致=True
```

`D1` 量的不是"顶层数量"这么松的东西：它对 `~/.zbot` 整棵树做 `os.walk`，取**文件名清单 + 大小**
排序后前后对拍（新增/消失逐条列出），符号链接走 `lstat`；`D2` 只算 `md5`，
**一个字节的内容都没被读进产物、也没被打印过**（真 key 在 `minimax.api.key`，本棒没读过它）。

凭证卫生（X 段，负向断言带猎物）：

```
PASS X0 泄漏探测器读得到产物（哨兵 key 必须被抓到，否则 X1 是空跑）   命中文件=['_hygiene_prey.txt']
       —— 探测器先在 out/ 里放一把同量级的假哨兵，必须抓到才允许宣布"产物干净"
PASS X1 全部产物里出现过的 key 值只有 stub-key-not-real            命中=无（扫描 495 个文件，含 logs/）
PASS X2 stub key 真的进了产物（反面钉子：扫描面不是空的）             带 stub key 的产物=['out/env-A-gw-r1.txt', …]
PASS X3 每一次出口 LLM 请求都带且只带 stub 凭证                      出口 240 次 / 带 stub 240 / 无凭证头 0 / 带别的凭证 0；
       凭证头名=['authorization']（只列头名不列值）                  ⇒ 同一条里既钉住"真有请求发生"，也钉住"用的不是真 key"
PASS X4 临时 profile 里没有 config.properties（真 key 没有被复制进来的落盘面） 命中=无
PASS X5 每个真 JVM 的 --config-dir / ZBOT_HOME 都指在自己的临时 profile      起过 9 个 JVM，违规=无
```

`X1` 的判据形状：只认"键名像凭证（`api[_.-]?key|authorization|access[_.-]?token|secret`）且值 ≥20
个连续可见字符"的串，命中即红；`X0` 就是它的"必须有猎物"自证（本棒修掉的那条恒 FAIL bug 之前，
`X0` 是坏的 ⇒ 当时的 `X1` 属于空跑，不能当证据）。
父进程也带 `ZBOT_HOME=<临时目录>`（见上面命令），`out/env-*.txt` 里留下的
是每个 JVM 真正带出去的环境变量快照，`Z_BOT_API_KEY` 只有 `stub-key-not-real` 一种值。

