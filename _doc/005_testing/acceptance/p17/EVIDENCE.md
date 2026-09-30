# P17 cron 投递闭环 — 验收证据

日期 2026-09-26 · 分支 `w2-p17`（产品代码封在 `88900a2`，本文件与 E 段量具在其后）· 分叉点 `d71657d`
· **四道杠全部主编亲自实测**，代理自述一律不作数（本期写手在 150 轮被截停，见 `898bf05` 的封盘说明）。

## 0. 这一项在做什么

对齐 hermes 的 cron 语义（`~/.hermes/hermes-agent` @ `cbc1054e2`，路线图 §1 P17）：

| 守卫 | 落在哪 |
| --- | --- |
| **先落账再跑**（副作用之前认领已写盘） | `cron/CronRunClaim.java` + `CronScheduler.execute()` |
| 心跳保鲜（compare-and-refresh，别人抢不走也续不了） | `CronScheduler.heartbeat()` |
| 一次性任务 **at-most-once**（额度用完即摘除，不重跑） | `CronScheduler` + `CronSchedule` 的 `once <ISO>` |
| **双层锁**：进程内监视器 + 跨进程 `flock`（重入计数，30s 等待上限后降级） | `CronScheduler.acquireJobsLock()`，`JOBS_LOCK_TIMEOUT_MILLIS = 30_000L` |
| tick 读盘而不是读内存旧表 | `CronScheduler.jobsOnDisk()` |
| 结果真投递：`local` 打印 / `origin` / 指定通道；无来源降级 `local` 不报错；拉模式 HTTP 控制台剔除 | `cron/ChannelCronDelivery.java`、`LocalCronDelivery.java` |
| gateway 把通道表接进投递口 | `cli/GatewayCommand.java` |

测试账：`@Test` 从基线 **399 → 453**（+54 = `CronClaimTest` 25 + `CronDeliveryTest` 25 + `CronScheduleTest` 4，
`CronSchedulerTest` 13→13 只改不增）。复算：
`git grep -c '@Test' d71657d -- 'z-bot-core/src/test/**' | awk -F: '{s+=$NF} END{print s}'` ⇒ 399；同命令换 `HEAD` ⇒ 453。
**surefire XML 自己求和也是 453**（见杠①），两边对得上才敢用这个数。

> 关于本文里的 `logs/*.log` 与 `out/`：**按仓库约定不入库**（根 `.gitignore` 有 `*.log`；
> `git ls-files '*.log'` 全仓 0 个，`p11b`/`p11c` 只入库 `EVIDENCE.md` + `LEDGER.tsv` + 两支脚本）。
> 它们是"复算即重生成"的原始读数落点，写在本文里是为了口径可查，不是当作随提交交付的证据；
> 本文引用它们时同时给了产生它的那条命令，重跑就能拿到同名文件。

## 1. 杠① 全量 3 连绿

复算：`rm -rf z-bot-core/target/surefire-reports && mvn -o test`（连续 3 跑，日志 `_doc/005_testing/acceptance/p17/logs/bar1_r{1,2,3}.log`）

```
run1 rc=0 | Tests run: 453, Failures: 0, Errors: 0, Skipped: 0 | socket类行数=0 | 00:30:00
run2 rc=0 | Tests run: 453, Failures: 0, Errors: 0, Skipped: 0 | socket类行数=0 | 00:30:17
run3 rc=0 | Tests run: 453, Failures: 0, Errors: 0, Skipped: 0 | socket类行数=0 | 00:30:33
```

不轻信 `[INFO] Tests run` 这一行，另用量具对账（逐份 surefire XML 求和；下面这条已实跑过，
读的是杠① 第 3 跑留在盘上的报告）：

```
cd /private/tmp/zbot-wt-p17 && python3 -c '
import glob, xml.etree.ElementTree as ET
t=f=s=0
for p in glob.glob("z-bot-core/target/surefire-reports/*.xml"):
    r=ET.parse(p).getroot()
    t+=int(r.get("tests",0)); f+=int(r.get("failures",0))+int(r.get("errors",0)); s+=int(r.get("skipped",0))
print("XML 份数=%d 求和 tests=%d fail+err=%d skipped=%d" % (len(glob.glob("z-bot-core/target/surefire-reports/*.xml")), t, f, s))'
=> XML 份数=45 求和 tests=453 fail+err=0 skipped=0
```

`socket类行数` 的口径：`grep -cE "BindException|Address already in use|Connection reset|SocketException|Socket closed"` = **0**，
即 P11c §6 那条通道监听抖动在本期 3 跑里 0 复现（**不主张归零**，只是这 3 跑没见到）。

## 2. 杠② 变异检验：27 支注入，0 支 BROKEN

复算：`python3 _doc/005_testing/acceptance/p17/p17_mutation.py`（单支：`... p17_mutation.py M8`）。
台账 `LEDGER.tsv` 由脚本机械输出（`ledger_generated_by p17_mutation.py at 2026-09-25T22:59:12+0800`），禁止手敲。

```
tally   RED-OK=20   PARTIAL=5   GREEN-BUT-MUTATED=2   BROKEN=0
mutants_injected=27     restored=True 27/27     cron_tests_ran_per_round=84     expected_total_after_p17=453
```

* **20 支 RED-OK**：注入后点名的那条具名 testcase 判红，没牵连别人。
* **5 支 PARTIAL 不洗成 RED-OK**（`M1 2/3` + `M17/M20/M22/M23` 点名全命中）：
  这五支的共同形状是"点名的红了，另外还红了别人"。例如 `M22 出关键区不还重入计数`
  除点名的 `depthReturnsToZeroWhenTheBodyThrows` 外还红了 `everyDispatchGoesThroughBothLockLayers`
  与 `nestedStoreCallsTakeTheFlockOnce` —— 那是**同一道守卫被两处测试看着**（互补面，不是覆盖缺失），
  但按纪律仍记 PARTIAL：期望集是推出来的，不是照着红单回填的。
* **2 支 GREEN-BUT-MUTATED（M8 / M9）**：进程内单测这一层**结构性看不见**跨进程锁等待和真通道装配。
  它们的证据改由真进程 E2E 出，且**已按支实测**：
  * **M8**（`JOBS_LOCK_TIMEOUT_MILLIS` 30_000→0）⇒ E2E 的 E 段两支判红：
    ```
    FAIL E2 攥锁期间写请求 20s 没返回，且盘上没多出这条   20s 后仍在跑=False 盘上=['E-free','E-held','E-seed']
    FAIL E4 整个对撞期间没有出现过一次「降级为只用进程内锁」  降级行=[… 等 .jobs.lock 的跨进程锁超过 0ms …降级为只用进程内锁…]
    ```
    复算（注入串由脚本自己的定义提供，不手敲；收尾按 `git show HEAD:` 逐字节还原）：
    ```
    python3 - <<'PY'   # 读 _doc/005_testing/acceptance/p17/p17_mutation.py 里 M8 的 old/new，预检锚点数=1，替换一次
    mvn -o -pl z-bot-core package -DskipTests -q
    P17_ONLY=E python3 _doc/005_testing/acceptance/p17/p17_e2e.py     # logs/e2e_E_only_M8.log，EXIT=1
    git show HEAD:z-bot-core/src/main/java/com/zifang/z/bot/cron/CronScheduler.java > 同路径
    md5 -q 该文件   ⇒ 0519435ddd1edc448903c5b867e36d23（与注入前逐字节相同）
    P17_ONLY=E python3 ...                                # logs/e2e_E_only_r6_restored.log，7/7 EXIT=0
    ```
    **E4 的红消息里带着"0ms"** —— 那是被注入的那个常量本身，读数能反证"量的确实是这一支变异"。
  * **M9**（gateway 不把通道表接进 cron，即把装配线摘掉）⇒ **整跑 E2E 判红 4 条**（`logs/e2e_full_M9.log`，
    `PASS=28 FAIL=4 EXIT=1`）：
    ```
    FAIL A3 结果真投递到 webhook 通道，且只投了一次            gw1 收到 0 条 / gw2 收到 0 条   （官方那一跑是 0/1）
    FAIL A4 投递正文是真跑出来的模型回复                      conversationId=None text=None
    FAIL B2 deliver=origin 没有来源时降级 local 而不是报错      (没有这行)
    FAIL B3 拉模式 HTTP 控制台被剔出投递目标（报 unknown channel 而不是假装 ok）  lastDelivery='ok'
    ```
    **B3 的红就是这一支的形状**：摘掉装配后任务落回 `LocalCronDelivery`，它永远回"成功"，
    于是"投给了一个根本不该当投递目标的拉模式控制台"被记成 `ok` —— 静默假成功回来了。
    复算（注入串取自脚本自己的定义、锚点预检=1；跑完 `git show HEAD:` 逐字节还原）：
    ```
    python3 - <<'PY'   # 读 _doc/005_testing/acceptance/p17/p17_mutation.py 里 M9 的 old/new 并替换一次
    mvn -o -pl z-bot-core package -DskipTests -q
    python3 _doc/005_testing/acceptance/p17/p17_e2e.py                      # 整跑；00:42:23→00:49:19
    git show HEAD:z-bot-core/src/main/java/com/zifang/z/bot/cli/GatewayCommand.java > 同路径
    md5 -q 该文件 ⇒ 2b5d7efbae79da240a6bbac439378e69（与注入前逐字节相同，equal=YES）
    ```
    还原后重打包 + 官方 E2E 重跑（§3）⇒ 32/32，四条全回到 PASS。（**当时的口径是 32 条**：
    C1b 是后来查 §5.5 那次假红时才加的，加完是 33 条 —— 两处条数差一条不是有检查被删。）
* 说明一句量具的边界：`z-bot-core.jar` **不是可复现构建**（同一份源码两次打包 md5 不同），
  所以 md5 只能标识"哪一次构建"，不能标识"量的哪棵源码树"。源码归属另用两条钉住：
  `git status --porcelain` 里 `z-bot-core/src` **为空**（本期只有 `_doc/005_testing/acceptance/p17/*` 是脏的），
  以及被注入文件按 `git show HEAD:<path>` 还原后与基线 md5 逐字节相同。

## 3. 杠③ 真进程 E2E：**33/33**

复算：`mvn -o -pl z-bot-core package -DskipTests && python3 _doc/005_testing/acceptance/p17/p17_e2e.py`
（`w2-p17` 上那一跑 00:30:59→00:37:47 = **6 分 48 秒**，`EXIT=0`，日志 `logs/e2e_full_official.log`；产物 `out/`。
并入 main 之后在**合并树**上重跑 3 整跑，逐跑读数见 §5.5 末：`logs/e2e_c1b_r{1,2,3}.log` = 33/33、33/33、33/33，
单跑 6 分 49 秒 / 6 分 49 秒 / 6 分 49 秒）

真 JVM ×7、真 cron 目录、真 60s 级任务、真 `kill -9`、真两个进程抢同一把锁；LLM 一律 stub（`stub-key-not-real`）。
决定性的几条原文：

```
PASS A2 60s 级任务真跑了一次（203s）              假 LLM 被调用 1 次（>1 = 双跑，0 = 没跑）
PASS A3 结果真投递到 webhook 通道，且只投了一次    gw1 收到 0 条 / gw2 收到 1 条
PASS A5 另一个 JVM 同刻没跑，日志里有跳过原因      [cron] cron_e2eA 正在被其他进程执行，跳过
PASS A6 跑赢的一方留下了落账日志                  [cron] cron_e2eA 已落账认领（owner=83012@…/fd251e52，dispatches=1）→ 这才开始跑副作用
PASS A7 一次性任务跑完即从盘上摘除                jobs.json 剩余=[]
PASS B2 deliver=origin 没有来源时降级 local 而不是报错   … 但没捕获到来源通道 … 按 local 投递，结果同时记在 lastResult
PASS B3 拉模式 HTTP 控制台被剔出投递目标          jobs.json 里 cron_http.lastDelivery="unknown channel 'http'"
PASS B5 B 段真跑了三次（出口计数）                B 段 stub LLM 被调用 3 次（期望 3）
PASS C1b 副作用真开跑（stub 收到这条请求）时，盘上的账已可见（先落账再跑）  stub 新增请求=True dispatches=1 runClaim={'by': '36178@1790358000815/8ff96f63', 'at': '2026-09-25T17:41:01.688599Z'}
PASS C2 进程被 kill -9 之后，盘上的账还在          dispatches=1 runClaim={'by': '36178@…', …}
PASS C3 重启后不复活成没跑过                     一次性投递额度已用完（1/1）—— 摘除陈旧任务，不重跑 | 假 LLM 新增调用 0 次
PASS D1 只给 ZBOT_HOME 时 cron 目录落在该 profile 里   ZBOT_HOME=/tmp/p17-e2e-…/profileD → cron/.jobs.lock
PASS E1 不攥锁时写请求秒回且真落盘                耗时 0.0s rc=0
PASS E0b 邻居（真 JVM，tryLock 形状与被测代码逐字一致）确实抢不到这把锁   NULL
PASS E2 攥锁期间写请求 20s 没返回，且盘上没多出这条   20s 后仍在跑=True 盘上=['E-free','E-seed']
PASS E3 释放后请求落定、任务真写进盘              从发起到落定 20.0s ⇒ rc=0 盘上有=True
PASS E0c 关掉描述符后邻居又抢得到                GRANTED
PASS E4 整个对撞期间没有出现过一次「降级为只用进程内锁」   降级行=无
PASS X1 真 key 没进任何输出产物或临时 profile      泄漏=无（真 key 长度 125，未打印）
PASS X3 每一次出口请求带的都是 stub 凭证           请求 4 次 / 带 stub 4 次 / 带真 key 0 次
PASS X4 临时 profile 从不落 config.properties      命中=无
```

E 段是本期新加的"两个真进程抢同一把 `.jobs.lock`"，也是 M8 的唯一现场；
`P17_ONLY=E` 能单跑（变异取证要反复验这一节，不该陪 A 段那 200 多秒一起等）。
恢复被拦：`P17_ONLY` 只认 `E`，其余节整跑（拆跑会丢掉跨段共用的出口计数）。

## 4. 杠④ 单测与 E2E 都不碰 `~/.zbot`

```
复算: ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties ; md5 -q ~/.zbot/state.db
实测: entries=8
      config.properties 2dadaed05e0cefe7a53f48baba3c403a  (期望前缀 2dadaed0 ⇒ 全串一致)
      state.db          690ddbc0e0e35f3a9dc183302f1d5182  (同上)
```
E2E 内部另有一道同口径的自检（跑前/跑后各量一次）：
`PASS D2 真实 ~/.zbot 没被动过  before=(8, '2dadaed0…', '690ddbc0…') after=(8, '2dadaed0…', '690ddbc0…')`、
`PASS D3 真实 ~/.zbot/cron 没多出东西  before=[] after=[]`。
`~/.zbot/.stty.bak` 与空的 `~/.zbot/cron` 目录**是本项之前就有的遗留**（`cron` 目录 mtime 15:40:39，早于本期所有跑动），
不是本期漏出去的写入 —— 要不要删由用户定，代理不动。

## 5. E 段的前提取证：探针自己先要被验

E 段的全部结论压在"邻居真的攥住了这把锁、而且确实看得见"这一条上。**上一版它是空跑的**，
过程如实记在这里（量具的账也要还）：

1. 原先 E0b 用"再起一个 Python 子进程 `fcntl.lockf(fd, 3)`（F_TEST）探一探"当凭证。
   09-26 实测本机（darwin 25.5.0 / CPython 3.14.0 / APFS）：**别的进程正攥着 0 字节锁文件时，
   `lockf(fd, F_TEST)` 和 `lockf(fd, F_TLOCK)` 一律回 ACQUIRED** ⇒ 探不到邻居，E0b 是假凭证。
   对照表（`probe_lockf_semantics.py` 当时实测，用完已删，结论留在本文件）：
   ```
   无人攥锁: cmd=3 ACQUIRED / cmd=2 ACQUIRED / cmd=1 ACQUIRED
   有人攥锁: cmd=3 ACQUIRED / cmd=2 ACQUIRED / cmd=1 ACQUIRED   ⇒ 两行一模一样 = 尺是坏的
   ```
2. 同一现场换成**真 JVM** 的 `FileChannel.tryLock(0L, Long.MAX_VALUE, false)`（与被测代码
   `CronScheduler.acquireJobsLock()` 逐字同形状）就有区分度。于是换尺，并把前提单独取证：
   复算 `python3 _doc/005_testing/acceptance/p17/probe_lock_namespace.py`（`rc=0`）：
   ```
   ① python 攥锁期间，邻居 JVM tryLock => ['NULL', 'NULL']
   ② python 关描述符（放锁）后，邻居 JVM tryLock => GRANTED
   ③ 重新攥锁，邻居 JVM tryLock => NULL
   ```
   ②③ 是**放锁方向**的取证：只有①的话，"放锁之后请求完成"仍然没有尺（这正是新加 E0c 的理由）。
3. 另一处坑记在这里免得再踩：**不能拿 `os.close(os.dup(fd))` 当"释放"** —— POSIX 记录锁按
   `(pid, inode)` 计，关掉该文件**任意一个** fd 就把整个进程的锁一起放了（macOS 的 `fcntl` 模块又拒绝
   `lockf(fd, F_ULOCK)`，报 `unrecognized lockf argument`，所以只能靠关描述符放锁）。
4. `r4` 那一跑整作废（盘上出现两行"降级"、量具自己还攥着锁），它的 stdout 恰好又被本机的
   `/tmp` 清理扫掉 ⇒ 那份读数不可复核，一律不引用；本期从 `r5/r6` 与官方那一跑重量。
   自此日志改落仓库内 `_doc/005_testing/acceptance/p17/logs/`，不再写 `/tmp`。

## 5.5 C2 那次红是**量具自己造的**，不是合并带进来的（09-26 合并树上翻出，全过程）

合并进 main 之后第一整跑（`logs/e2e_after_merge.log`）= **30/32**，红的两条：

```
FAIL C2 进程被 kill -9 之后，盘上的账还在（dispatches=1 + runClaim 未清） dispatches=0 runClaim=None
FAIL C3 重启后不复活成没跑过：陈旧一次性任务被摘除而不是重跑   (没有这行) | 假 LLM 新增调用 1 次
```

**先排除"合并改坏了"**：`git diff --stat 13b1868 a73dcc6 -- z-bot-core/src/main/java/com/zifang/z/bot/cron/ _doc/005_testing/acceptance/p17/`
⇒ **输出为空**（cron 产品代码与量具在两边逐字节相同），所以这不是 P11c/P15 与 P17 的相互作用。
C3 的红也是下游：`dispatches=0` ⇒ 量具那句 `if oncrash.get("runClaim")`（把认领时间戳改老）没执行 ⇒ 重启后那个 JVM
**照盘上的事实**（这任务从没跑过）又跑了一次。也就是说被测方一路按账办事，账本来就没落盘。

**真因**：`claimDispatch()` 里 `LOG.info("…已落账认领…")` 打在**事务体内**（`CronScheduler.java:454`），
而账要等 `withStore()` 走出事务后由 `writeStore()` 做 `jobs.json.tmp → ATOMIC_MOVE`（同文件 `:638` 起）。
量具拿"看见这行日志"当 `kill -9` 的扳机 ⇒ 落在几毫秒窗口里就是自己把账杀在移动之前。
（被杀那一跑的 JVM 日志里**没有** `保存 jobs.json 失败` ⇒ 不是写失败，是没轮到写。）

**窗口宽度是量出来的，不是推的**：`RACE_KILLS=10 RACE_DELAYS=10 python3 -u ~/.cache/zbot-p17/race_probe.py`
（探针独立成文，不复用战役脚本；LLM 端换成"接了连接永不回答"的真 TCP server，好让认领一直挂在飞）：

```
K 模式（看见日志立即杀）: 盘上有账=8 / 盘上没账=2 / 没等到日志=0  （共 10）
D 模式（日志→落盘延迟）: n=10  min=0.1ms  p50=0.2ms  max=8.8ms  未出现=0   (load 6.2→22.1 逐轮记在明细里)
明细: ~/.cache/zbot-p17/race-1790356429/
```

⇒ **账确实总在副作用之前落盘**（10 轮里 0 轮读不到，最慢 8.8ms），红线"先落账再跑"没有被违反；
被违反的是"等 A 断言 B"这条量具纪律。K 模式 2/10 是按 20ms 轮询打出来的（比量具自己的 250ms 更凶）；
量具在合并树 3 跑里中过 1 次 —— 样本太小，不据此主张任何速率。

**改法（收紧，不是放宽）**：新增 **C1b**，把扳机从"日志"换成**因果** ——
`claimDispatch()` 返回之后才会发起 LLM 请求，所以**等 stub 真收到这条请求**再去读盘：
那一刻盘上读不到账 = 真缺陷（谁把写盘挪到"跑起来之后再发"，C1b 当场判红）。
C2 的语义随之收窄成它本来该说的那件事：**已经落过盘的账，经得起一次 `kill -9`**。
两条现在各判一头，不再共用一个会抢跑的扳机。合并树按最终量具重跑 3 整跑：

```
复算: for i in 1 2 3; do python3 -u _doc/005_testing/acceptance/p17/p17_e2e.py > logs/e2e_c1b_r$i.log 2>&1; done   （串行，同一时刻只一跑）
实测: RUN1 01:35:36→01:42:25  == E2E: 33/33 通过 ==
      RUN2 01:42:25→01:49:14  == E2E: 33/33 通过 ==
      RUN3 01:49:14→01:56:03  == E2E: 33/33 通过 ==
```

## 6. 这一项没做什么（别当成做了）

* **30s 降级后的"活着"没有跨进程的正向证据**。E4 钉的是"不该出现降级"（反面），M8 钉的是"上限归零会红"；
  "邻居卡死 ⇒ 等满 30s ⇒ 降级为进程内锁并且调度器继续跑"这一整条正向，只有进程内单测
  `twoInstancesOnOneDirNeverDegradeTheirLock` 证过同型场景，跨进程没证。E 段的等待窗口是 20s，
  **不是**把 30s 上限跑满的边界实测。
* **M9 的证据是整跑 E2E 里"哪几条红了"，不是一条专门点名 M9 的断言**；期望红集没有为它单独扩过。
* **真第三方通道没接**：投递只测了 `local` 打印、`origin` 降级、指定通道（HTTP stub webhook）三档，
  飞书/钉钉的真实发送不在本期。
* **flock 语义只在 darwin 上量过**；Linux/Windows 的 `FileLock` 行为差异未实测。
* **`once` 只量了带 `Z` 的 ISO 瞬时**；本地时区/夏令时写法未测。
* 杠① 的 453 是 `w2-p17` 分支自己的数。**并入 main 后必须是 465**（main=411，本期 +54，
  两边改的文件不重叠）—— 合并前 `git merge-tree --write-tree main w2-p17` 预演：无冲突、
  合并树 `@Test` 求和 = **465**。
  | 兑现: `rm -rf z-bot-core/target/surefire-reports && mvn -o test` 在 `main`(`a73dcc6`) 上串行 3 跑
  | 实测: `Tests run: 465, Failures: 0, Errors: 0, Skipped: 0` × 3（`logs/bar1_after_merge_r{1,2,3}.log`，
    28.1s / 20.9s / 17.9s），每跑 socket 类错误 `grep -cE "SocketException|BindException|Address already in use"` = 0
* **"已落账认领"这行日志不等于账已落盘**：它打在事务体内，`ATOMIC_MOVE` 在事务外（实测窗口 0.1–8.8ms，见 §5.5）。
  本期只把**量具**改成不等这个扳机，**没有**改产品代码去消掉这个窗口 —— 崩溃现场取证时别拿它当盘据，
  真要"日志即盘据"的语义，得把日志移到 `writeStore()` 之后（那是另一期的事，本期不虚报为已做）。
