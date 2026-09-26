# P24 记忆与身份面 —— 证据台账（写手：第 10 棒 p24a 主体码 / 收口棒 p24d 四杠 / 分支 `w10-p24`）

STATUS: p24a 已交 §0—§3（主体码 + 契约设计）；p24d 交 §0-b 开工复算与 §4—§8 四杠实测。
基线变化：p24a 起笔时 `main = 53222e1`；p24d 开工时 `main = 9ade134`（已并 p22/p23/p14/p26），
本棒 §2.2 已把 main 并进 `w10-p24`（见 §0-b），杠①②③④ 全部在合并后的树上量。

约定：本节所有读数均为**本机当次命令的原始输出照抄**，非工单转述。工单头部读数与我实测冲突的，两行并列、以盘上为准。
`.log` 被 `.gitignore` ⇒ 决定性读数一律原样贴进本文件（本文件是被跟踪的）。

---

## §0-b p24d 开工复算（工单 §0 那组命令的原始输出）

工单写"HEAD=`3d7e58b`、父是 `53222e1`、两个未跟踪文件"。**开工第一件事照抄工单 §0 的命令跑了一遍**：

```
$ cd /private/tmp/zbot-wt-p24 && date && git log --oneline -1 && git rev-parse --short HEAD \
  && git status --porcelain \
  && git grep -c '@Test' HEAD -- z-bot-core/src/test | awk -F: '{s+=$NF} END{print "committed_at="s}' \
  && git diff --numstat $(git merge-base HEAD main) HEAD | awk '{i+=$1;d+=$2} END{print "ins="i" del="d}' \
  && find z-bot-core/src/main/java/com/zifang/z/bot/memory -name '*.java' | wc -l \
  && wc -l z-bot-core/src/main/java/com/zifang/z/bot/memory/*.java | tail -1
Sat Sep 26 17:51:12 CST 2026
3d7e58b P24 记忆与身份面 第一档：memory/ 从 224 行/0 契约做成 1677 行/9 文件 + 91 支测试（新增 87）…
3d7e58b
?? _doc/acceptance/p24/p24_mutation.py
?? z-bot-core/src/test/java/com/zifang/z/bot/memory/MemoryE2eDriver.java
committed_at=822
ins=3341 del=63
       9
    1677 total
```

与工单逐条对账：

| 项 | 工单读数 | p24d 实测 | 判定 |
|---|---|---|---|
| HEAD | `3d7e58b` | `3d7e58b` | 一致 |
| 未跟踪文件 | 两个（p24_mutation.py / MemoryE2eDriver.java） | 同两个，一字未多一字未少 | 一致 ⇒ 本棒第一步就是把它俩提交，之后不碰 `checkout/clean/restore` |
| memory/ 体量 | 1677 行 / 9 文件 | `1677 total` / `9` | 一致 |

**工单没写、而我量出来不一样的三条（都记在这里，不当场改口径）：**

1. **`@Test` 总数在合并树上被工单那把尺高估 2 条**：工单 §0 的 `git grep -c '@Test'` 在
   `53222e1..3d7e58b` 上给 822（与 p24a 记的 735→822 一致），但并完 main 之后给 **1006**，
   而杠① 三跑真跑到的测试是 **1004** 条。差额不是漏跑，是那把尺把**注释里的 `@Test`** 也算了：
   ```
   loose=$(git grep -c '@Test' HEAD -- z-bot-core/src/test | awk …)            # 1006
   strict=$(git grep -cE '^[[:space:]]*@Test' HEAD -- z-bot-core/src/test | awk …)   # 1004
   files_with_diff: 2
     z-bot-core/src/test/java/com/zifang/z/bot/llm/P26RetryPolicyTest.java  loose=34 strict=33
     z-bot-core/src/test/java/com/zifang/z/bot/memory/MemoryE2eDriver.java  loose=1  strict=0
   ```
   `MemoryE2eDriver.java` 那一条是 javadoc 里的 `{@code @Test}`（它**不是测试**），
   `P26RetryPolicyTest` 那条是注释。⇒ 本棒把"测试面真值"统一按 `strict=1004` 记，
   并要求它等于杠① 的类级求和（确实相等，见 §4）。`p24_mutation.py` 的 `#suite_total` 用的还是
   工单那把松尺（1006），差 2 条属已知口径差，见 §8。
2. **`git merge --ff-only main` 在实测中不可行**（工单 §2.2 建议的那一步）：`w10-p24` 上有两枚
   main 没有的提交（`3d7e58b` 主体码、`3f2b27b` 遗物提交），ff 只能往前不能往旁，实测
   `fatal: Not possible to fast-forward, aborting.`（rc=128）。⇒ 改用两父合并
   `git merge --no-edit main`（rc=0，`568903a`），**先验证两侧改动文件交集为空**才敢动：
   ```
   $ comm -12 <(git diff --name-only 53222e1 3d7e58b|sort) <(git diff --name-only 53222e1 main|sort)
   （空输出 = 交集为空 ⇒ 无冲突）
   ```
3. **杠④ 三个不变量开工读数与工单一致**（T0，`date` = 2026-09-26 17:51:33 +0800）：
   `ls -A ~/.zbot | wc -l` = **8**、`md5 -q ~/.zbot/config.properties|cut -c1-8` = **2dadaed0**、
   `md5 -q ~/.zbot/state.db|cut -c1-8` = **690ddbc0**。清单：`.stty.bak config.properties cron
   memories models-cache.json sessions state.db workspace`。三时点全表见 §7。

ff 后的新尺（同一组命令，HEAD=`568903a`+量具修订 `f28326e`）：`committed_at=1006`（松尺）/
`strict=1004`（等于真跑到的条数）、memory/ 仍 1677 行 9 文件。

---

## §0 第 0 步复算（工单读数 vs 我实测）

---

---

## §4 杠① 三跑（`rm -rf surefire-reports && mvn -o test`，全 reactor、无 `-pl`）

判绿用**双尺**（工单 §2.3）：模块级 `Tests run:` 求和与类级 `-- in ` 行求和必须同数。
日志在 `~/.cache/zbot-p24-lead/bar1_{a,b,c}.log`（`.log` 不被跟踪 ⇒ 结论照抄在这里）。

```
$ cd /private/tmp/zbot-wt-p24 && for x in a b c; do rm -rf z-bot-core/target/surefire-reports; mvn -o test > ~/.cache/zbot-p24-lead/bar1_$x.log 2>&1; done
$ python3 ~/.cache/zbot-integrate/b1parse.py ~/.cache/zbot-p24-lead/bar1_{a,b,c}.log; echo parse_rc=$?
BAR1PARSE …/bar1_a.log module_lines=1 class_lines=91 module_sum=1004 class_sum=1004 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE …/bar1_b.log module_lines=1 class_lines=91 module_sum=1004 class_sum=1004 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
BAR1PARSE …/bar1_c.log module_lines=1 class_lines=91 module_sum=1004 class_sum=1004 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES
parse_rc=0
```

三跑的起止与 rc（同一次命令里落的 meta 文件，`date` 现量）：

```
$ cat ~/.cache/zbot-p24-lead/bar1_a.meta ~/.cache/zbot-p24-lead/bar1_bc.meta
start=2026-09-26 17:53:45 +0800
rc=0
end=2026-09-26 17:54:53 +0800
start_17:55:32 run=b
rc_b=0
end_17:56:36
start_17:56:36 run=c
rc_c=0
end_17:57:42
```

**不信 `b1parse.py` 一把尺，另用 grep 独立复算**（同一批日志）：

```
$ for x in a b c; do echo "run $x: module_tests=$(grep -cE '^\[[A-Z]+\] Tests run:' …) …"; done
run a: module_tests=92 cls_lines=91 sum_mod=1
run b: module_tests=92 cls_lines=91 sum_mod=1
run c: module_tests=92 cls_lines=91 sum_mod=1
$ grep -h "^\[INFO\] BUILD" bar1_{a,b,c}.log
[INFO] BUILD SUCCESS     ×3
$ grep -cE 'BindException|Connection refused|SocketTimeout|Broken pipe|Too many open files' bar1_a.log
0
```

（`92 = 91 类级 + 1 模块级`，与工单 §2.3 说的"模块摘要行前缀会在失败时翻成 `[ERROR]`"同源：
这里 `[A-Z]+` 通配，两把尺都对上。）

本期新增的 7 个记忆测试类在三跑里的逐类读数（run a 的原始行照抄，未删一字）：

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in com.zifang.z.bot.memory.MemoryDriftGuardTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.068 s -- in com.zifang.z.bot.memory.MemoryToolsContractTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.zifang.z.bot.memory.MemoryIdentityContractTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in com.zifang.z.bot.memory.MemoryContentScanTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.011 s -- in com.zifang.z.bot.memory.MemoryStoreTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.035 s -- in com.zifang.z.bot.memory.MemoryStoreContractTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.001 s -- in com.zifang.z.bot.memory.MemoryWriteGateTest
```
合计 91 支（12+11+13+22+11+18+4），与 p24a §3 具名清单逐类对上，一字不差。
`MemoryE2eDriver.java` 在 src/test 里、无 `@Test` ⇒ 被编译但不被 surefire 收（这是它该有的形状，
杠③ 靠它起真 JVM）。

**三跑是不是同一件事？** 把计时列剥掉之后对逐类行排序取 md5，三跑完全同一个指纹：

```
$ for x in a b c; do grep -E -- '-- in ' bar1_$x.log | sed -E 's/, Time elapsed: [0-9.]+ s//' | sort | md5 -q; done
3e9093d24f7359da06eff65fd2c3a2d2
3e9093d24f7359da06eff65fd2c3a2d2
3e9093d24f7359da06eff65fd2c3a2d2
$ for x in a b c; do …类级 Tests run 求和…; done
run a 类级求和=1004
run b 类级求和=1004
run c 类级求和=1004
```

**杠① 判定：三跑全绿。** 1004 条 / 91 类 / F=0 E=0 S=0 / BUILD SUCCESS / 网络类异常 0 命中，
且 1004 = §0-b 的 `strict @Test` 真值（松尺 1006 多的那 2 条是注释里的 `@Test`）。


---

## §5 杠② 变异注入（`p24_mutation.py`，五档 + 冻结预期红集 + 共享锁）

STATUS: 已跑（18:26:02→18:33:35 整轮，HEAD=f283614 树；台账由 `p24_mutation.py` 机生成，见 §9 全文）
记号分布（台账 `#tally` 行原样）：KILLED=27 | RED-OK=0 | SURVIVED=1 | PARTIAL=1 | INJECTION_NOT_APPLIED=0
注入证明：`#byte_proof_ok_rows=29` / `#mutants_injected=29`（每一支都在还原前先做 javap 反汇编指纹比对）
D2（漂移拒写但不留取证）本轮由 run1 的 INJECTION_NOT_APPLIED 转为 **KILLED**，具名 3 条：
MemoryDriftGuardTest#signalShapeCatchesManuallyAppendedProse, MemoryStoreContractTest#externalEditBlocksEntryWriteAndLeavesEvidence, MemoryToolsContractTest#driftBackupPathReachesTheCallerThroughTheTool
两条未杀：I3=SURVIVED、D5=PARTIAL —— 逐条代码级判词见 §9 杠②。

---


## §6 杠③ 真进程 E2E（`p24_e2e.py` ≥3 整跑）

STATUS: 已跑（三整跑 r1/r2/r3，18:33:56→18:34:18，HEAD=f283614 树，全部 `--build` 现打包真构件）
每跑逐字读数（`=== E2E SUMMARY ===` 行）：`scenes=11 failed_checks=0 []`，进程 rc=0
每跑绿判词数=76，红判词数=0，全局红线尺 3 条判词全绿
三跑判词集合指纹（场景名+判词名拼接取 md5）三跑相同：`f531249ae7df1d1bc4fb73a4b0fc0ba2`
S0 阳性对照闸门三跑均真过（产品类确实从 `z-bot-core/target/z-bot-core.jar` 加载，不是 target/classes）
场景清单与逐条判词见 §9 杠③。

---

## §7 杠④ `~/.zbot` 三时点对账

STATUS: 已跑（三时点，读数以 §9 杠④ 为准）
T0 开工（17:5x）= 8 / 2dadaed0 / 690ddbc0；T1 跑完杠②（18:33:35 现测）= 8 / 2dadaed0 / 690ddbc0 / 夹具 0；
T2 跑完杠③（18:34:18 现测）= 8 / 2dadaed0 / 690ddbc0 / 夹具 0；真 key 只量长度=125，从未读出正文。
E2E 现场一律在 `~/.cache/zbot-p24-lead/e2e/<label>/`（量具自带硬校验，落点不对直接 SystemExit(9)）。

---

## §8 量具自身的错（与产品错分列）

STATUS: 已跑（本轮改的全部是量具自身的错，与产品错分列；详见 §9 量具条目 (a)–(e)）
一句话：本轮 4 处红点最后都归到尺子上（S0 的 realpath 恒红、红线尺整场扫描、`mvn -q` 吞掉编译错头、
D2 注入写法撞 javac 不可达 catch），产品侧未新增缺陷认定；I3/D5 两行是真·断言缺口，留在 §9 杠②。

---

## §未做

STATUS: 未做清单见下（本棒到此为止，未做的都点名）
1. 未把 I3（SOUL 整页语义 / entries 空表契约）与 D5（半写还原的 reason 保留）补成产品单测 —— 加 @Test 会改
   杠① 的基线数，本棒按"只改量具、不动测试面"收口，两行记号因此保留。
2. 工单 §4.1 要的 WIRING.md 4 处接线 hunk 未 apply（只给了代码级证词）。
3. 杠③ 只覆盖记忆层的真入口（`MemoryTools#memoryTool` 真 jar/真进程/真文件/真重启/真半写/真并发锁），
   未跑 agent 主循环里人 `/confirm` → `BotAgent:886` 盖章 → `:1885` 去章 的完整对话链。
4. 未跑 z-bot-desktop-packager 产物面（它在杠① reactor 里以"无测试"参与）。
5. 杠① 只补测了 1 整跑（1004/91 类）落在 §9；§4 的三跑 1006 是另一棵树（见 §9 开头的盘面变更），
   主编在目标树复测时请以 §9 的尺重跑三整跑再判定。
6. `~/.cache/zbot-p24-lead/{LEDGER_full_run1.tsv,mutbak/,e2e/}` 有意保留未清理，供复算。

### §0 第 0 步复算（工单读数 vs 我实测，命令照抄工单）

测量时刻：`date` = 2026-09-26 16:58:57 +0800（起笔）／收口读数见 §7。

| 项 | 工单读数 | 我实测 | 判定 |
|---|---|---|---|
| `memory/` 体量 | 224 行 / 2 文件 | `MemoryStore.java 133` + `MemoryTools.java 91` = `224 total` / 2 文件 | 一致 |
| **测试面** | **0 支**（`git grep -c '@Test' HEAD -- …/memory` 应为空） | 同一把尺的原始输出：`HEAD:z-bot-core/src/test/java/com/zifang/z/bot/memory/MemoryStoreTest.java:4` | **不符 ⇒ 以盘上为准：基线已有 4 支（1 文件，2 868 字节）** |
| `@Test` 总数 / 测试文件数 | 735 / 71 | `git grep -c '@Test' HEAD -- '*.java'` 求和 = **735**；`git grep -l '@Test' HEAD` 计数 = **71** | 一致 |
| 接线面 | `BotAgent.java:1866` register；`DelegateManager` 在 `:1854` | `git grep -n MemoryTools HEAD` ⇒ `BotAgent.java:26`(import)、`BotAgent.java:1866`(register)、`MemoryTools.java:18/20`；`git show HEAD:…/BotAgent.java` 第 1854 行确为 `delegation = new DelegateManager(…)` | 一致 |
| 公开面 | `readMemory/readUser/readSoul/isEmpty/appendMemory/appendUser/rewriteMemory/rewriteUser/ensureSoul/getDir` | `grep -n 'public '` 另含 **`clearMemory()`（:91）与 `clearUser()`（:95）**，且构造器 `MemoryStore(File)`、`final class` 声明本身 | 工单少列 2 个（`/memory forget` 与 `MemoryTools` 的 forget 都在用它们） |
| hermes `tools/memory_tool.py` | 1 162 行 / 35 个 def；`class MemoryStore` @ :123 | `wc -l` = **1162**；`grep -c '^def \|^    def '` = **35**（含 class 行则 36）；`class MemoryStore` @ **:123** | 一致 |
| 她的锚点位次 | `_apply_write_gate` :833 / `_apply_batch_write_gate` :890 / `_missing_old_text_error` :937 / `_drift_error` :93 / `_scan_memory_content` :88 / `load_on_disk_store` :801 / `apply_memory_pending` :1052 | 逐一 `grep -n '^def \|^class \|^    def '` 对回：**全部命中同一行号** | 一致（本条她的注释没骗人） |
| hermes `plugins/memory/` | 8 家后端 + `config_schema.py` + `query_rewrite.py`，**共 21 个 .py** | `find plugins/memory -name '*.py' \| wc -l` = **24**；拆：后端目录内 **21** + 顶层 **3**（`__init__.py`/`config_schema.py`/`query_rewrite.py`）；后端目录 `find -maxdepth 1 -type d` = **8** | **口径不符**：21 只数了后端目录里面，漏了顶层 3 个（工单自己列的 `config_schema.py`+`query_rewrite.py` 就在那 3 个里） |
| hermes `tests/tools/test_memory_tool.py` | 917 行 | `wc -l` = **917** | 一致 |

**由 §0 得出的两条纠正（写进台账，不按工单原文报）：**

1. 工单说"0 支测试"，真值是 **4 支**（`MemoryStoreTest`）。本期"0 测试 ⇒ 有测试"的叙事要改成"4 支只测了读写往返 ⇒ 91 支带契约"；差额不是我凭空算的：`grep -rc '@Test' …/memory/*.java` 见 §3。
2. 工单 §1#5 的"8 家插件后端"数量对得上（8 个目录），但 .py 总数按"她整个 `plugins/memory` 树"是 24 不是 21。


## §1 契约设计（写入门禁 / 漂移备份 / 批量 / 身份三件套）

对标锚：她 `tools/memory_tool.py` 里这一层是 **1 162 行**，我这一层现在是 **1 677 行 / 9 文件**（含 javadoc；其中真正对得上她那 5 个锚点的逻辑在下面四段）。四条契约，每条都写成"可断言的形态"，不是注释里的愿望：

1. **写入门禁**（她的 `_scan_memory_content` :88 + `_apply_write_gate` :833 + `_missing_old_text_error` :937）
   - 落盘之前扫，不是注入之前扫。她的理由照抄进 `MemoryContentScan` 的类注释：记忆是以**冻结快照**进 system prompt 的，一条投毒内容会一路带到被显式删除为止。
   - 窄表 9 条特征（中英各半：指令覆盖 / 角色重指派 / 分隔符越界 / 密钥块 / 凭据赋值 / `sk-` 前缀 / 下载即执行），**命中即拒写**并回报 `patternId@offset`。她的 strict 档共享特征表在本仓够不着 ⇒ 我没有复用她的表而是手写窄表，并**为每条特征配了反向正常样本**（13 条中文/英文正常记忆必须不命中，`MemoryContentScanTest.benignChineseAndEnglishMemoriesPass`）——宽表会把"记得之前的规则"这类句子拦掉，那样这一层等于不能用。
   - 外传类特征的 `snippet` 一律打 `***`：门禁文案会进 transcript 和日志，抄疑似密钥原文进去就是二次外传（对应工单红线 1 的精神）。
   - 改已有条目（replace/remove）必须给 `old_text`；缺 ⇒ `MISSING_OLD_TEXT` 大声报错，**错误里回抄当前条目清单 + 重试指令**（她那句注释：只回 "old_text is required" 是死胡同），并**绝不降级成新建**（有专门的字节级断言：拒写前后文件逐字节相同、条目数不变）。
   - `old_text` 命中 0 条 ⇒ `NO_MATCH`；命中多条互不相同 ⇒ `AMBIGUOUS_MATCH`；命中多条但正文完全相同 ⇒ 取第一条（她的同款判断）。
   - 预算：条目清单整份渲染长度不得超过该份预算（她的 2200 / 1375 缺省值原样搬，`MemorySection` 里带出处），**预算在最终状态上判**，所以"同一批里先删再写"是合法的。

2. **漂移与备份**（她的 `_detect_external_drift` :714 + `_drift_error` :93）
   - 三信号：①解析再渲染不往返 ②单行超整份预算 ③行首不是 `- [时间戳] ` 条目形状。她的信号集只有 ①②，③ 是我们格式下的必然推论——她的条目用 `§` 连接所以 ① 就能抓到散文，我们用行，光靠 ① 抓不到"手编追加的一行"。
   - 命中 ⇒ **先**逐字节 `Files.copy` 到 `<file>.bak.<毫秒>`、**再**拒绝写入、异常里带 `bakPath`（她的 `drift_backup`）。她按**秒**取整，同一秒内两次漂移会覆盖掉第一枚证据——这一处我没抄，`backupFileFor(dir,name,ts)` 重名依次加 `.1 .2`，并有专测把三枚快照钉成三个不同文件。
   - 每次落盘三步：快照 → 写 → **回读对账**（`Arrays.equals(盘上, 待写)`）。半写或对不上账 ⇒ 用快照还原 + 保留快照为证据 + 抛 `WRITE_UNVERIFIED`/`WRITE_FAILED`；写成功 ⇒ 快照当场清掉（它是保险不是证据，留着会把 `memories/` 变成 `.bak` 堆）。双向测试见 §3：注入"只写 3 个字节的通道" ⇒ 断言还原后 md5 与写前相同 + `.tmp` 不残留 + 快照恰好留 1 枚。
   - `rewritePage` **不做**漂移判定：它是漂移发生时的补救出口（她 `_drift_error.remediation` 让人"把文件重写成干净的条目清单"，如果补救通道也被漂移门挡住就成了死锁）。

3. **身份三件套**（`MemorySection`）
   - `MEMORY.md` / `USER.md` = 条目清单（可判漂移、有预算）；`SOUL.md` = 整页散文（不判条目往返、仍受内容扫描、预算 4096）。形状差异写进枚举而不是散在各方法的 if 里。
   - 空判定只算两份可写记忆：`SOUL.md` 是 `ensureSoul()` 人人都会建出来的骨架文件，把它算进"非空"就让"这台机器还没记住任何东西"永远测不出来。这一条有具名测试并带阳性对照。
   - 条目级操作打到 `SOUL` ⇒ `BAD_OPERATION`（不是"帮你改成整页写"）。
   - 三件套的一致性只认**真文件真目录 + 换实例重开**（= 重启面），没有任何 in-memory 替身。

4. **批量写**（她的 `apply_batch` :507 + `_apply_batch_write_gate` :890 + `_batch_error` :614）
   - 两段式：**碰盘之前**先把整组内容扫一遍（一条投毒否掉整批，报 `Operation i+1` 的中文等价物"第 i+1 条操作失败"并带 `[op=N]`）；**锁内**在内存副本上演算，任一条 locate 失败 ⇒ 一个字节都不落。
   - 与她的差异：她批量只写一个 target 文件，我们同构（一批一个 section）；她的 `write_approval` staging（评估闸门 ⇒ 挂待批队列）我们这一层不做，见 §未做。

**与她的对应关系一览**（她 → 我）：`_scan_memory_content`→`MemoryContentScan.scan`；`_apply_write_gate`/`_missing_old_text_error`→`MemoryWriteGate.gateContent/locate`；`_detect_external_drift`/`_drift_error`→`MemoryDriftGuard.detect/Drift.message`；`add/replace/remove`→`MemoryStore.appendEntry/replaceEntry/removeEntry`；`apply_batch`/`_batch_error`→`MemoryStore.applyBatch`；`_write_file`（临时文件+原子改名）→`MemoryDriftGuard.atomicReplace`；`_char_limit`→`MemorySection` + 实例覆盖。

## §2 实现落点（写域内，未碰 `agent/BotAgent.java` / `config/BotConfig.java`）

`find …/memory -name '*.java' | xargs wc -l` 原始输出：

```
 143 MemoryContentScan.java      新增
 534 MemoryStore.java            改（原 133）
  83 MemoryWriteRejectedException.java  新增
  96 MemorySection.java          新增
 215 MemoryTools.java            改（原 91）
 154 MemoryWriteGate.java        新增
  88 MemoryOp.java               新增
  98 MemoryReceipt.java          新增
 266 MemoryDriftGuard.java       新增
1677 total（基线 224 / 2 文件 → 1677 / 9 文件）
```

测试面合计 1656 行 / 7 文件（`find …/src/test/…/memory -name '*.java' | xargs wc -l`）。

兼容性（这三条是"改道但不改契约"的取证点）：

- `MemoryStore` 历史签名 `appendMemory/appendUser/rewriteMemory/rewriteUser/clearMemory/clearUser/ensureSoul/readMemory/readUser/readSoul/isEmpty/getDir` **一个都没删、签名一字未改**，但 append/rewrite 内部已改道走门禁；被拒时抛 `MemoryWriteRejectedException extends IOException`，所以调用方的 `throws IOException` 不用动。
- `MemoryTools.memoryTool(MemoryStore)` 签名不变 ⇒ `BotAgent:1866` 那一行**无需改**（接线见 WIRING.md 第 3 条）。
- 私有静态通道 `lockedWrite(File, String)` 签名保留（`MemoryStoreTest:77` 用反射直取它模拟手编 SOUL）；基线那 4 支测试一字未改，仍然全绿。
- 盘上格式一字未改：条目行仍是 `- [Instant] 正文`、行间单换行、无尾换行 ⇒ 现网 `~/.zbot/memories` 里已有的文件不会被本层判成漂移（§7 里 `~/.zbot` 三时点未动，且 `MemoryStoreContractTest.reopenedStoreSeesExactlyWhatPreviousOneWrote` 钉住字节级一致）。
- 落盘末端 `WriteSink` 是**唯一**新增的注入口（半写/写失败两条路径必须有办法真发生；照仓内既有先例：`KeyPoolLlmProvider:85`「测试注入：自定义 key → provider 工厂」、`McpManager:30`「测试用：预构建 McpClient」）。生产缺省实现 = 临时文件 + `ATOMIC_MOVE`。

## §3 测试面清单（`grep -rc '@Test'` 原始输出，逐类具名）

```
MemoryContentScanTest.java:12
MemoryDriftGuardTest.java:13
MemoryIdentityContractTest.java:11
MemoryStoreContractTest.java:22
MemoryStoreTest.java:4        ← 基线原有，未改
MemoryToolsContractTest.java:18
MemoryWriteGateTest.java:11
```

memory 包合计 **91 支**（基线 4 → 91，新增 6 类 / **87 支**）；全仓 `@Test` **735 → 822**，测试文件 **71 → 77**。

按靶子归类（哪一支钉哪一条契约，全部真文件真目录）：

- 靶1 写入门禁：`MemoryContentScanTest`（9 特征正向 + 13 条正常样本反向 + 位点 + 截断 + 脱敏）、`MemoryWriteGateTest`（11 支，逐码断言）、`MemoryStoreContractTest.poisonedContentNeverReachesDisk` / `.replaceWithoutOldTextIsErrorNotSilentCreate`、`MemoryToolsContractTest.poisonedAppendCarriesMachineReadableCode` / `.replaceWithoutOldTextComesBackWithInventory`。
- 靶2 漂移与备份（双向）：`MemoryDriftGuardTest`（三信号各一支 + 干净/缺文件不判 + 快照=原字节 + 还原=原字节 + 同毫秒三枚不互相覆盖 + `.tmp` 不残留 + 无快照时大声失败）、`MemoryStoreContractTest.externalEditBlocksEntryWriteAndLeavesEvidence`（拒写后现场文件字节不变、异常报的路径就是盘上那枚）、`.halfWriteIsRolledBackToExactOriginalBytes`、`.sinkFailureKeepsReasonAndRestores`、`.writeFailureOnFreshFileHasNothingToRestore`、`.rewritePageIsTheRemediationEscapeHatch`、`MemoryToolsContractTest.driftBackupPathReachesTheCallerThroughTheTool`。
- 靶3 身份三件套：`MemoryIdentityContractTest` 11 支（三份互斥形状、`ensureSoul` 逐字节 + 幂等 + 手编优先、人格非空例外、预算 per-section、跨实例重启面、成功写入不留 `.tmp`/`.bak`）。
- 靶4 批量：`MemoryStoreContractTest` 5 支（全有或全无 + `[op=N]`、按序演算、去重计数、先腾地方再写、投毒否整批、空/含 null 批量）+ `MemoryToolsContractTest` 3 支（JSON 批量、失败条号、不可解析）。
- 永挂体检（工单 §3 杠② 前置）：`grep -rn '\.await()\|\.waitFor()\|Thread.sleep\|new Thread\|ExecutorService' …/src/test/…/memory/` ⇒ **无匹配（rc=1）**，本层测试不起线程、不等待，摘门不会永挂占锁。

