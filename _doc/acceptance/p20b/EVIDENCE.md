# P20b —— EVIDENCE（四杠读数 + 产生读数的命令）

> 工单：`~/.cache/zbot-p17/dispatch_p20b.md`（范围）＋ `~/.cache/zbot-p17/dispatch_p20c.md`（本棒续工）。
> 工作树 `/private/tmp/zbot-wt-p20b`，分支 `w2-p20b`，接手 HEAD `c3ab4da`。
> 本文只记**能一条命令复算**的读数；每个数字旁边就是产生它的命令。
> `.gitignore:5` 是 `*.log` ⇒ 日志不进版本库，凡结论依赖的读数**原文粘进本文**。

## 0. 起手（命令与读数）

本棒开工先实测三条基准，**不采信简报**：

| 项 | 命令 | 读数 |
| --- | --- | --- |
| 工作树/分支/HEAD | `git -C /private/tmp/zbot-wt-p20b log --oneline -1 && git -C /private/tmp/zbot-wt-p20b rev-parse --abbrev-ref HEAD` | `c3ab4da wip(p20b): 封存 w2-p20b 在途产物…` / `w2-p20b` ⇒ 与简报逐字一致 |
| 在途状态 | `git -C /private/tmp/zbot-wt-p20b status --porcelain` | 空（干净） |
| `~/.zbot` 活的 | `ls -A ~/.zbot \| wc -l` ; `md5 -q ~/.zbot/config.properties \| cut -c1-8` ; `md5 -q ~/.zbot/state.db \| cut -c1-8` | `8` / `2dadaed0` / `690ddbc0` ⇒ 三项与简报一致，本棒全程只算哈希不读内容 |
| 锁的落点 | `git rev-parse --git-common-dir` | `/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git` ⇒ 锁文件 `…/.git/zbot-mutlock`（跨 worktree 才叫锁） |

### 0.1 交付物盘点（简报说"三件套一个都没有"，实测确认）

```
$ ls -la _doc/acceptance/p20b/
P20bToolDriver.java  mcp_stub_server.py  p20b_e2e.py  logs/      # 只有这三份脚本 + 日志
$ git diff --stat 13027f4 HEAD | tail -17
```
⇒ `p20b_mutation.py` / `LEDGER.tsv` / `EVIDENCE.md` **确实一个都没有**（本棒就是把这三件补出来）。
产品码在盘：`tool/Toolsets.java` 266 行、`Toolkit.java` 相对 `13027f4` +473、`McpBridge.java` +88、
`BuiltinTools.java` +33；7 个新测试类共 1,767 行（同一命令可复算，见 §8）。

### 0.2 工单"第 0 步"那五条（判断 `7043e91` 是半成品还是从 0 重写）

前四条是上一棒留在 `logs/` 的原始读数（`.gitignore:5` 是 `*.log`，故把判据行原文粘进来）；
第五条本棒自己重跑过。**归属写清楚，别当成这一棒做的**：

1. 基线全量：`logs/step0-1_baseline_full_test.log`（上一棒 02:28 那一跑）
   ```
   [INFO] Tests run: 465, Failures: 0, Errors: 0, Skipped: 0
   [INFO] BUILD SUCCESS
   ```
   本棒静态复算同一件事（不依赖那份日志）：
   ```
   $ git grep -c '@Test' 13027f4 -- 'z-bot-core/src/test/**' | awk -F: '{s+=$NF} END{print s}'
   465
   $ git grep -c '@Test' $(git rev-parse --short HEAD) -- 'z-bot-core/src/test/**' | awk -F: '{s+=$NF} END{print s}'
   525
   ```
   ⇒ 分母 465 → 525，净增 60 条，全在 tool/mcp 两面。
2. `7043e91` 只取两个文件后的原始编译读数：`logs/step0-2_7043e91_raw_build.log`（上一棒 02:29 那一跑）
   原文（该日志第 334 / 337 / 339 行）：
   ```
   [INFO] Tests run: 465, Failures: 0, Errors: 0, Skipped: 0
   [INFO] BUILD SUCCESS
   [INFO] Total time:  16.086 s
   ```
   ⇒ 结论与工单的猜测相反：`7043e91` 那两文件**能编译、且基线 465 条全绿**，不是"根本编译不过的草稿"；
   它是"能编译但没有任何测试覆盖它的新语义"的半成品 —— 上一棒是在它基础上重写语义，不是从零。
   （旁证：新写的第一批测试自己就编不过，`logs/newtests-first-try.log:48` 起有
   `[ERROR] COMPILATION ERROR :` 数条（`assertFalse(String,String,boolean)` 重载不存在、
   `Clock2 cannot be converted to FakeClock`、`cannot find symbol` ×2），是**测试**写错 ——
   不是产品码。）该日志里另两条含 `SQLITE_ERROR` 字样的行是 StateStore 的自愈提示，不是构建错误。
3. 现有 tool 面测试：
   ```
   $ git ls-tree -r --name-only 13027f4 | grep -c 'src/test/java/com/zifang/z/bot/tool/'   # ⇒ 5
   $ git ls-tree -r --name-only HEAD   | grep -c 'src/test/java/com/zifang/z/bot/tool/'   # ⇒ 9
   ```
   ⇒ 本期新增 4 个 tool 类（`ToolkitProbeTest` / `ToolkitRegistryTest` / `ToolkitResultCapTest`
   / `ToolsetsManifestTest`）+ 2 个 mcp 类 + 1 个测试替身。工单点名的
   `KernelToolRegistryBridgeTest` 依旧不存在：`git grep -l 'KernelToolRegistryBridge' HEAD` ⇒ rc=1、0 命中。
4. MCP 的 stub 覆盖 hack：`git grep -c 'stubTool' 13027f4 -- z-bot-core/src/main/java/com/zifang/z/bot/mcp/McpBridge.java` ⇒ `2`
   （`stubTool(name)` + 注销处 `toolkit.register(stubTool(name))`），HEAD 上
   `git grep -n 'stubTool' HEAD -- z-bot-core` ⇒ rc=1、0 命中 ⇒ 旧 hack 连函数一起删掉了。
   §2 的 MB3 就是把这段旧写法原样注回去，看测试是不是真的判红。
5. 她的 toolset 顶层计数（红线 2 的参照面），本棒亲测（`~/.hermes/hermes-agent`，只读、未 pull）：
   ```
   $ cd /Users/zifang/.hermes/hermes-agent && git rev-parse --short HEAD
   cbc1054e2
   $ grep -cE '^\s+"[a-z0-9_]+":\s*\{' toolsets.py
   33
   $ awk 'NR>=96 && /^\}/ {exit} NR>=96' toolsets.py | grep -cE '^\s+"[a-z0-9_]+":\s*\{'   # 只数 TOOLSETS 那一段
   33
   ```
   ⇒ **33 与 roadmap 一致**，本仓只列 4 个能力子集 + 1 兜底槽 + 1 动态前缀族（`Toolsets.java:106-118`）。
   两处路径级事实修正：她的定义在**仓根** `toolsets.py`（不是 `tools/toolsets.py`，我们代码注释里的路径写歪了）；
   `_CHECK_FN_TTL_SECONDS = 30.0` 在 `tools/registry.py:143`、`_CHECK_FN_FAILURE_GRACE_SECONDS = 60.0` 在 `:147`
   —— 两个数都不是假设了，且与内核 0.2.1 的 `PROBE_TTL_MS=30000 / PROBE_FAILURE_GRACE_MS=60000` 同值
   （单测 `ToolkitProbeTest.kernelProbeConstantsMatchTheHermesNumbersThisTicketCopies` 钉的就是这条）。


## 1. 杠① —— `mvn -o test` 连续 3 跑（本棒自跑，不引用主编那一跑）

命令（三跑都是同一条，跑前删报告以免拿旧 XML 充数）：
```
rm -rf z-bot-core/target/surefire-reports && mvn -o test      # 日志落 _doc/acceptance/p20b/logs/gate1_run{1,2,3}.log
```

| 跑 | `Tests run:` 汇总行原文（行号） | BUILD | rc | Total time |
| --- | --- | --- | --- | --- |
| run1 | `[INFO] Tests run: 525, Failures: 0, Errors: 0, Skipped: 0`（第 372 行） | `[INFO] BUILD SUCCESS`（第 389 行） | 0 | `14.956 s` |
| run2 | 同上（第 372 行） | 同上（第 389 行） | 0 | `14.575 s` |
| run3 | 同上（第 373 行） | 同上（第 390 行） | 0 | `14.736 s` |

复算（`.log` 不进版本库，所以判据原文已粘在上面；下面这条不依赖日志）：
```
$ rm -rf z-bot-core/target/surefire-reports && mvn -o test | grep -E 'Tests run: [0-9]+, Fail.*Skipped: [0-9]+$' | tail -1
[INFO] Tests run: 525, Failures: 0, Errors: 0, Skipped: 0
```

静态对拍（同一件事的另一个真源，见 §0.1）：`@Test` 求和 = **525**，与 surefire 汇总逐字相同；
基线 `13027f4` 是 **465** ⇒ 本期净增 **60** 条。

这 3 跑跑在本棒动任何文件之前（当时 HEAD = `c3ab4da`）。本棒全程**没有改过 `src/main` 与 `src/test`
下任何一个文件**（改动只在 `_doc/acceptance/p20b/` 内），复算：
```
$ git diff --name-only c3ab4da HEAD -- '*/src/*' | wc -l
0
```
收工前另按同一命令再跑 3 次（§1.2），读数一致才算交付态。

### 1.1 杠① 期间机器上有别人的 mvn

`ps` 抓到过 `/bin/sh -c cd '/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-lc…'` 这类
**别的仓**的 maven 进程（不是编队里那三支 z-bot worktree）。它与我不同 reactor、不同 `target/`，
只共享 CPU；三跑都在 15s 内完成，没有被迫放宽任何东西。

### 1.2 交付态三跑（杠②③ 全部改动落库之后，HEAD `84ca7b9`）

同一命令，跑前删报告；日志 `logs/gate1_run{4,5,6}.log`：
```
rm -rf z-bot-core/target/surefire-reports && mvn -o test
```

| 跑 | 汇总行原文（行号） | BUILD SUCCESS | rc | Total time |
| --- | --- | --- | --- | --- |
| run4 | `392:[INFO] Tests run: 525, Failures: 0, Errors: 0, Skipped: 0` | 1 | 0 | `15.681 s` |
| run5 | `373:[INFO] Tests run: 525, Failures: 0, Errors: 0, Skipped: 0` | 1 | 0 | `14.753 s` |
| run6 | `373:[INFO] Tests run: 525, Failures: 0, Errors: 0, Skipped: 0` | 1 | 0 | `14.486 s` |

⇒ 6 跑（§1 的 3 跑 + 交付态 3 跑）读数为 525/0/0/0，中间夹着 22 次注入跑与 2 次全量 E2E，
**产品码与测试码本棒一字未动**：
```
$ git diff --name-only c3ab4da HEAD -- '*/src/*' | wc -l
0
```


## 2. 杠② —— 注入自证（p20b_mutation.py → LEDGER.tsv）

命令（全量 21 个变异体，一条；约 7 分钟）：
```
P20B_MVN_WAIT=600 python3 -u _doc/acceptance/p20b/p20b_mutation.py     # console 留档 logs/mutation_console2.log
```
交付态那一跑的**原始读数行原文**（含它自己打的两条"期望集为空"警告 —— 跑前就写死，不是跑完补的）：
```
== 变异体 21 个，基线 git rev = a2b5e49dd7b64ad504e113309498b65f0f57814c，锁 = /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock
  注：TK9 溢出目录解析不看 $ZBOT_HOME 的期望集为空 —— 该守卫在单测层没有活的猎物，脚本会把它判成 GREEN-BUT-MUTATED 并记'未覆盖'
  注：MB2 桥按'记住的名字'逐个注销 的期望集为空 —— 该守卫在单测层没有活的猎物，脚本会把它判成 GREEN-BUT-MUTATED 并记'未覆盖'
  预检过：锚点次数 + 盘上原文 == git show + 无漂移
== 控制跑（不注入）
  控制跑 rc=0 跑了 525 条 红=无 名字集=525 条 日志=_doc/acceptance/p20b/logs/mut_control.log
== 台账 ==
  RED-OK             14
  PARTIAL            4
  GREEN-BUT-MUTATED  3
  BROKEN             0
  LEDGER.tsv 21 行 / LEDGER_RESTORE.tsv 21 行（均为脚本产物）
  SRC_MD5_STABLE=yes
ATTEMPT_1 rc=0 04:07:26
```
- **控制跑的意义**：不注入先确认 525 条全绿，并**收全部 testcase 名**；期望集里点到一个不存在的名字
  就 FATAL 拒绝注入（防"拿一条不存在的用例当猎物"）。
- **期望集是跑前写死的**：21 个变异体连同各自的具名期望用例在 `438ea50`（本棒第二次提交）就进库了，
  两次全量跑用的都是**同一份**期望集，一字未回改；台账里 3 条 `GREEN-BUT-MUTATED` 与 4 条 `PARTIAL`
  全部照原样记账，**没有一条被洗成 RED-OK**。
- **两轮全量跑的判定逐字一致**（复算见 §8：把两轮台账的 `id/verdict/named_expected/named_hit` 四列 diff，
  输出为空 ⇒ `TWO_RUNS_IDENTICAL=yes`）。第一轮台账（`9059ca7`）与本轮的唯一差别是 §6(7) 那条标签修正。
- 判定分布不靠"mvn 退出码"：只认 surefire XML 里的**具名 testcase**（`BROKEN 0` ⇒ 全程没有编译不过的注入）。

### 2.1 逐条的账

下表由下面这条命令直接生成（本文只是把它的答案粘进来；`LEDGER.tsv` 是脚本产物，人一行没敲）：
```
python3 -c "import io,csv;rows=list(csv.reader(io.open('_doc/acceptance/p20b/LEDGER.tsv',encoding='utf-8'),delimiter='\t'));
h,rs=rows[0],[r for r in rows[1:] if r]
[print('| %s | %s | %s/%s | %d |'%(dict(zip(h,r))['id'],dict(zip(h,r))['verdict'],dict(zip(h,r))['named_hit'],dict(zip(h,r))['named_expected'],len([x for x in dict(zip(h,r))['tests_that_went_red'].split(',') if x]) if '全绿' not in dict(zip(h,r))['tests_that_went_red'] else 0) for r in rs]"
```

| 变异体（判据） | 判定 | 点名红/点名期望 | 真红总条数 |
| --- | --- | --- | --- |
| TS1 `emptyCapabilityToolsets` 写死空表 | RED-OK | 1/1 | 1 |
| TS2 `undeclaredToolsets` 过滤反了 | RED-OK | 2/2 | 2 |
| TS3 `manifestToolsNeverRegistered` 过滤反了 | RED-OK | 2/2 | 2 |
| TS4 `toolsetForTool` 一律退回兜底槽 | PARTIAL | 4/5 | 4 |
| TS5 `mcpToken` 放过中划线 | RED-OK | 2/2 | 2 |
| TS6 `capabilityNames` 把兜底槽/动态族也算能力 | RED-OK | 3/3 | 3 |
| TK1 快照键丢掉不可用名单 | RED-OK | 4/4 | 4 |
| TK2 `unavailableToolNames` 恒空 | RED-OK | 6/6 | 6 |
| TK3 代际计数不外露（钉死 0） | PARTIAL | 3/7 | 3 |
| TK4 上限不走注册表 | RED-OK | 1/1 | 1 |
| TK5 `UNBOUNDED` 哨兵失效 | GREEN-BUT-MUTATED | 0/1 | 0 |
| TK6 预览不受上限约束 | RED-OK | 1/1 | 1 |
| TK7 溢出落盘被摘掉 | RED-OK | 3/3 | 3 |
| TK8 上限边界 `<=` 改成 `<` | RED-OK | 1/1 | 1 |
| TK9 溢出目录解析不看 `$ZBOT_HOME` | GREEN-BUT-MUTATED | 0/0 | 0 |
| TK10 溢出文件名净化失效 | RED-OK | 1/1 | 1 |
| TK11 `deregisterToolset` 的 owner 被顶成内建 owner | PARTIAL | 13/13 | 16 |
| MB1 桥不接可用性探测 | RED-OK | 1/1 | 1 |
| MB2 桥按"记住的名字"逐个注销 | GREEN-BUT-MUTATED | 0/0 | 0 |
| MB3 退回同名 stub 覆盖（P20 要杀的旧实现） | PARTIAL | 8/8 | 10 |
| E1 `stopAll` 只断连不注销 | RED-OK | 3/3 | 3 |

覆盖面对照工单点名的每一条守卫：toolset 声明/清单三查 = TS1/TS2/TS3（外加 TS4 归属真源、TS5 拼法、TS6 能力名口径）；
探测 TTL 与宽限窗 = TK1/TK2/TK3 + MB1；结果上限（全局 / 单工具声明 / UNBOUNDED）与溢出落盘 = TK4/TK5/TK6/TK7/TK8/TK9/TK10；
桥级注销与 `reload()` 改名不留旧名 = TK11/MB1/MB2/MB3；`stopAll` 清空注册表与外发清单 = E1。

### 2.2 非 RED-OK 的那 7 条：红/绿在哪、为什么如实记

点名/漏点名的差集也是机器算的（把 `MUTANTS` 用 `ast.literal_eval` 取出来与台账的 `tests_that_went_red` 求差），
所以下面每条都能复算，不是我读出来的印象：

1. **TS4 `PARTIAL` 4/5** —— 漏的那条是 `readOnlyToolsDeclareParallelSafetyAndWriteToolsDoNot`。
   原因在**测试自己**：`ToolsetsManifestTest.java:128` 写的是
   `assertEquals(name, Toolsets.toolsetForTool(name), tk.toolsetOf(name))`，
   而注册侧 `BuiltinTools.java:82` 也是 `toolkit.register(tool, Toolsets.toolsetForTool(...))` ——
   **expected 与 actual 同源**：把 `toolsetForTool` 摘成恒回兜底槽，两边一起退化，等式照样成立。
   ⇒ 这是一条真洞（该用例判不出归属真源丢失），修法是把期望换成清单常量而不是同一个函数。本棒没改测试。
2. **TK3 `PARTIAL` 3/7** —— 漏的 4 条（`registerAndDeregisterEachInvalidateTheSchemaSnapshot`、
   `exposedNamesTrackTheRegistryAfterNukeAndRepave`、`unregisteringIsNotStubOverwrite`、
   `reloadDropsTheDeadServersToolNamesFromGetToolNames`）不红的理由是**注入面偏窄**：
   锚点 `return registry.generation();` 全仓只 1 处，即外露 accessor `Toolkit.generation()`（`:221`）；
   快照键用的是 `snapshot()` 里的 `long generation = registry.generation();`（`:310`），没被顶掉。
   ⇒ 期望集写宽了（我把读快照行为的用例也算成该 accessor 的猎物）。要真打"代际不外露"这条守卫，
   下一棒的注点在 `:310` 那一行（或把键改成常量）。这是**量具的账**，不是产品的红。
3. **TK5 `GREEN-BUT-MUTATED` 0/1** —— 这条判下来是**等价变异**，不是"测试没猎物"：
   `javap -constants … ToolDescriptor` ⇒ `UNBOUNDED_RESULT_CHARS = 9223372036854775807L`。
   摘掉 `cap == UNBOUNDED_RESULT_CHARS ||` 之后剩下的 `content.length() <= cap` 里
   `int` 提升为 `long` 与 `Long.MAX_VALUE` 比 ⇒ **恒真**，两种写法逐值等价 ⇒ 没有任何测试能把它判红。
   语义的外部读数在杠③ C7（`CAP3_UNBOUNDED_INTACT=true`，PASS）。想让它可判，得换成**不等价**的注入
   （例如把 `NO_MAX_RESULT_CHARS=-1` 与 UNBOUNDED 对调），本期没做（§7）。
4. **TK9 `GREEN-BUT-MUTATED` 0/0（期望集跑前就空）** —— 单测层结构上打不到：
   唯一碰这一级的 `ToolkitResultCapTest.overflowDirResolvesInjectedFirstThenSystemProperty` 自己写着
   `if (System.getenv(Toolkit.ZBOT_HOME_ENV) == null) { …assertNull… }` —— JUnit 里改不了本进程 env，
   所以 surefire 下这一支**永远不被断言**；把 `$ZBOT_HOME` 那一摘，测试照常绿。
   ⇒ 记"未覆盖"，不算过。它唯一有猎物的地方是真进程：杠③ C1（`ZBOT_HOME=<临时目录>` 下
   `CAP_RESOLVED_OVERFLOW_DIR=<tmp>/tool-results`，PASS）。
5. **MB2 `GREEN-BUT-MUTATED` 0/0（期望集跑前就空）** —— 把 `deregisterToolset(toolset(), owner())`
   换成"按桥自己记住的 `registered` 列表逐个 `deregister(name, owner())`"，525 条全绿。
   原因：看着最像猎物的 `deregisterToolsetCleansNamesTheBridgeNoLongerKnowsAbout`（`ToolkitRegistryTest.java:134`）
   是**注册表层**的用例（裸 `tk.register` + `tk.deregisterToolset`，根本不经过 `McpBridge`），
   而桥层用例里 `registered` 与注册表在替身上永远一致 ⇒ 造不出"注册表有、桥没记住"的那个分歧。
   ⇒ 记"未覆盖"：缺一条**桥级**用例（server 改名后老名字仍挂在注册表上，而桥的记忆里只有新名字）。
   本期没补。真进程侧这条有读数：杠③ M11（`reload()` 后只剩 `mcp-alpha2-renamed`）与 M12。
6. **TK11 `PARTIAL` 13/13 + 3 条未点名红** —— 点名的 13 条全红，另外
   `bridgeRegistersAnAvailabilityProbeBackedByTheConnection`、`reloadDropsTheDeadServersToolNamesFromGetToolNames`、
   `repavingWithTheSameToolNameWorksAfterUnregister` 也红了：owner 参数被顶成内建 owner 之后，
   桥级 nuke 变静默 no-op，连带把探测/reload 路径一起打断。⇒ 期望集写窄了（不是产品问题，红是好事）。
7. **MB3 `PARTIAL` 8/8 + 2 条未点名红** —— 这条是工单点名的**反向断言**（"旧实现注回去必须先红一次"）：
   把 `stubTool(name)` 同名覆盖那套注回去，10 条红，其中 `unregisteringIsNotStubOverwrite`、
   `oldServerToolNamesAreGoneAfterUnregisterInsideTheSameJvm`、`unregisterLeavesNoPlaceholderBehindInAnyListView`
   三条正是"不许留桩"的正面判据 ⇒ 说明现在的产品码真的在判这件事。
   未点名的 2 条（`bridgeOnlyNukesItsOwnToolset`、`serverNamesWithDashesGetUnambiguousToolsets`）是 toolset 拼法连带。

### 2.3 测试替身还原的独立取证

`LEDGER_RESTORE.tsv` 也是脚本产物（21 行），每行记：
`id / file / git_show_md5_baseline / disk_md5_during_injection / injection_landed / disk_md5_after_restore / restored`。
判据不吃脚本自己那句"restored=ok"，而是**每一行都拿 `git show <基线 rev>:<path>` 的 md5 当独立权威**：

```
$ cut -f5 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c
  21 yes(disk!=git show)
$ cut -f7 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c
  21 ok
$ git status --porcelain      # 全量跑完（含控制跑）之后：四个被测源文件一字未变，只剩本棒的 _doc 改动
```
⇒ 21/21「注入确实改了盘」且 21/21「还原后与 `git show` 逐字节同」。基线 rev 由脚本自己在第一列写死
（`a2b5e49…`），不是我口头指定的；`injection_landed` 这一列的第一版标签写反了，见 §6(7)。

### 2.4 锁的双向实测（`flock(LOCK_EX|LOCK_NB)`，落在 git 公共目录）

锁路径：`$(git rev-parse --git-common-dir)/zbot-mutlock` =
`/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock`（跨 worktree 才叫锁）。

- **攥锁侧**（`python3 -u … p20b_mutation.py --hold-lock 24`）：
  ```
  LOCK HELD pid=18053 24s
  LOCK RELEASED pid=18053
  ```
- **邻居侧（攥锁期间真跑全量注入）**：
  ```
  预检过：锚点次数 + 盘上原文 == git show + 无漂移
  FATAL 锁被占（/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock）: [Errno 35] Resource temporarily unavailable ⇒ 本棒不硬跑，按工单记'杠②未跑完：锁被占'
  rc_busy=5
  ```
  当场核对四个被测源文件（python 算的 md5，逐字节比 `git show HEAD:<path>`）：
  ```
  Toolsets.java   f6785851f188d681c531a377249f9cdf True
  Toolkit.java    4ae1a491cd73b0f1c1ac869db59723ad True
  McpBridge.java  2938f85b32213e0bd7d7041e478d35b2 True
  McpManager.java 37a141454ca06331bfdb3341e0af3c83 True
  ```
  ⇒ **拒跑且源码一字不变**（预检只读、`acquire_lock` 在任何写盘之前）。
  这一跑还留下 `LEDGER.tsv` 21 行 `BROKEN / 未跑：锁被占`（正是"拿不到锁就记账"那条要求）；
  记账完成后本棒用 `git show HEAD:_doc/acceptance/p20b/LEDGER.tsv` 的字节写回 + `fsync` + md5 比对
  （`restored equal= True`）把台账还原成真实那一版 ⇒ 现在库里的 `LEDGER.tsv` 是 14/4/3/0 那份。
  （这条兜底路径的第一版会 `NameError` 崩溃，见 §6(8)。）
- **松开后**（`--want-lock`）：
  ```
  LOCK ACQUIRED-THEN-RELEASED pid=18679
  rc_free=0
  ```
- 顺带一条真实撞车：本棒 03:5x 那一次就是在别人攥着锁时被这条挡住（`rc_busy` 当场 5），
  重试到 04:0x 拿到锁才跑完全量 ⇒ 不是只在测试里好用。
- 跑前还会 `ps` 等别人的 maven 真身（`P20B_MVN_WAIT`），等待期间不碰源码；
  这一判据的第一版错把含 "mvn" 字样的自家 shell 当邻居，见 §6(5)。

### 2.5 未覆盖的守卫（注入不出的，说明为什么不可观测）

| 守卫 | 为什么单测层注入不出 | 唯一有读数的一面 |
| --- | --- | --- |
| 溢出目录第三级解析 `$ZBOT_HOME`（TK9） | JUnit 进程改不了自己的 env，唯一相关用例自带 `if (getenv==null)` 跳过 ⇒ 结构上没有猎物 | 杠③ C1（真进程 `ZBOT_HOME=<tmp>`）PASS |
| 桥"不拿记住的名字当结论"（MB2） | 最像猎物的用例是注册表层的，不经过 `McpBridge`；桥级替身造不出"注册表有、桥没记住"的分歧 | 杠③ M11/M12（改名 server + stopAll）PASS |
| `UNBOUNDED` 哨兵（TK5） | 这条根本不是"没猎物"，而是**等价变异**（`cap=Long.MAX_VALUE` 时两个写法逐值同） | 杠③ C7 PASS；想判红要换不等价的注入 |
| `check_fn` 真时间（TTL 30 s / 宽限 60 s 的**时序**） | 单测用的是注入的 `LongSupplier` 假钟，能判语义判不了真时间 | 杠③ M4–M7（`first_hidden=60281ms`、`wall=60.6s`）PASS |

⇒ 上表四条按工单口径都算"杠② 未覆盖"，**不算过**；它们都由杠③ 的真进程读数兜着，两边不是一把尺。


## 3. 杠③ —— 真进程 E2E（三阶段全量一次跑完 + M4/M5 裁决）

命令（全量三阶段，一条；它自己会 `mvn -o -pl z-bot-core package -DskipTests` 起真产物、`javac` 驱动、
再起**真 JVM + 真 stdio MCP server 子进程**）：
```
python3 -u _doc/acceptance/p20b/p20b_e2e.py        # console 原文留档 logs/e2e_full_console2.log
```
交付态那一跑（HEAD `a2b5e49`，2026-09-26 04:0x）的**汇总行原文**：
```
== 合计 38 条判定，PASS=38 FAIL=0
```
脚本 rc=0（`main()` 有 FAIL 就返回 1）。38 = 前置 4（B1–B4）+ toolsets 段 8（P0+T1–T7）
+ cap 段 8（P0+C1–C7）+ mcp 段 13（P0+M1–M12）+ 收尾 5（R1 R2 K0 K1 K2）。
**M4/M5 的裁决见 §3.4**（那两条在 03:5x 的那一跑还是 1 红 1 空跑，原文一并留着）。

### 3.1 前置与 toolsets 段（红线 2 的三查在真装配上跑）

```
  B1 mvn package -DskipTests 退出码 0                           PASS rc=0
  B2 产物 jar 存在                                               PASS z-bot-core.jar
  B3 依赖 classpath 取到                                         PASS rc=0 len=3044
  B4 驱动 javac 通过                                             PASS rc=0
  P0 toolsets 阶段驱动退出码 0                                      PASS rc=0 TRACE	PHASE_RETURNED=toolsets +27ms
  T1 装配后无空的能力子集（红线 2）                                        PASS []
  T2 注册表里没有清单外的 toolset                                      PASS []
  T3 清单里的工具名全部真被注册                                           PASS []
  T4 core/file/exec/net 四个子集成员符合清单                           PASS [echo, time, counter, health, sysinfo] [exec, mvn_build] [read_file, write_file, search] [curl_test]
  T5 只读工具声明可并行、写/执行/出网不可                                     PASS echo=ro time=ro counter=rw health=ro read_file=ro write_file=rw exec=rw search=ro sysinfo=ro mvn_build=rw curl_test=rw
  T6 摘掉 exec 子集后审计把它点名（审计不是写死的）                              PASS [exec]
  T7 内建工具没落进兜底槽                                              PASS [echo, time, counter, health, sysinfo] [exec, mvn_build] [read_file, write_file,
```
T1/T2/T3 都是"审计恒返回 `[]` 就算过"那种最容易自欺的形状，所以 **T6 是同一条尺的反向猎物**：
驱动在同一个真注册表上摘掉 `exec` 子集之后，审计必须点名 `[exec]`（读数就是 `[exec]`）。
杠② 的 TS1/TS2/TS3 打的正是这三查的判据，两边不是同一把尺（这边真进程、那边单测）。

### 3.2 cap 段（上限走注册表 + 溢出落盘跟着 `$ZBOT_HOME`）

```
  P0 cap 阶段驱动退出码 0                                           PASS rc=0 TRACE	PHASE_RETURNED=cap +48ms
  C1 溢出目录来自 $ZBOT_HOME（不是写死的 ~/.zbot）                        PASS /var/folders/m9/2z3syv390gz9ctbr9q8r5j880000gn/T/zbot-p20b-e2e-cfncbsa6/tool-results
  C2 上下文里只留预览（120 字符）                                        PASS 120
  C3 回执说明走了注册表上限                                             PASS \n\n[结果 4000 字符，超单工具上限 1000，上下文只保留 120 字符预览；全文已存 /var/folders/m9/2z3syv390gz9ctbr9q8r5j880
  C4 落盘文件是全文且 md5 与原文一致                                      PASS 65156065ca7d373ef473fe837afad54a vs 65156065ca7d373ef473fe837afad54a len=4000
  C5 回执里的路径就是那个文件                                            PASS big_default-anon.txt
  C6 工具自声明的上限压过全局值（注册表口径）                                    PASS 300/300
  C7 声明 UNBOUNDED 的结果一字不改                                    PASS true
```
C1 的读数是**临时目录**里的 `tool-results`（红线 1：`$ZBOT_HOME` 优先，产物里不出现 `~/.zbot`）；
C4 是"落盘那份就是全文"的独立算法对拍（两边各算 md5，`len=4000`）。
⇒ 杠② 的 TK5（UNBOUNDED 哨兵）与 TK9（`$ZBOT_HOME` 分支）在单测层没有活的猎物，
C7/C1 是本棒找到的**唯一一对真进程读数**（详见 §2.5）。

### 3.3 mcp 段（真 stdio server、真时间、同一个 JVM 内注销）

```
  P0 mcp 阶段驱动退出码 0                                           PASS rc=0 TRACE	PHASE_RETURNED=mcp +60561ms
  M1 两个真 stdio server 都注册上了                                  PASS [mcp-alpha-t1, mcp-alpha-t2, mcp-bravo-u1]
  M2 server 工具各归各的 mcp-<server> toolset                      PASS mcp-alpha
  M3 kill 命令真退出且传输层确认进程没了                                    PASS rc=0 wait=59ms
  M4 首探的出处可指：取一次 schema ⇒ 该桥每个槽位探一次（alpha 发了 2 个工具就是 2 次），TTL 窗内再取一次一条都不许多（真时间） PASS alpha_tools=2 anchor=2 second_read=2 samples=[(64, True, 2), (1567, True, 2)]
  M5 越过 TTL 后每轮都重探（失败不写缓存，斜率恰为该桥槽位数）、但宽限窗内一条都不摘（真时间）         PASS 30-60s 样本=20 条 probes=[4, 6, 8, 10, 12, 14] 每轮增量=[2] exposed={True}
  M6 持续失败过了 60s 宽限窗必须把工具从外发清单摘掉（真时间读数）                       PASS first_hidden=60281ms
  M7 摘掉的是外发，不是槽位：注册清单仍可见、执行报调用失败而非未找到                        PASS reg=true err=true nf=false
  M8 没被动过的第二 server 全程可用                                     PASS true
  M9 注销发生在同一个 JVM 的同一个 Toolkit 实例里                           PASS pid=2842→2842 id=480971771→480971771
  M10 反向断言（桥级）：unregister 之后旧工具名从 getToolNames 消失            PASS removed=[mcp-alpha-t1, mcp-alpha-t2] after=[mcp-bravo-u1]
  M11 反向断言（生产入口 reload）：改名后的 server 不留旧名字                    PASS before=[mcp-alpha2-t1, mcp-alpha2-t2, mcp-bravo2-u1] after=[mcp-alpha2-renamed, mcp-bravo2-u1] total=2
  M12 stopAll 之后注册表与外发清单都清空                                  PASS [] / []
```
`wall=60.6s`（原文在 `logs/e2e_mcp.log` 第 2 行：`cwd=/private/tmp/zbot-wt-p20b exit=0 wall=60.6s`）
⇒ M6 的 `first_hidden=60281ms` 是**真等出来的**，不是把 timeout 调大蒙的；
M9 用 pid + `System.identityHashCode` 两个读数钉"同一个 JVM 的同一个 Toolkit 实例"，
M8/M10/M11 的 `bravo` 全程可用钉"不是靠重启进程糊过去的"（重启会把第二台 server 的连接一起弄没）。

### 3.4 M4/M5 的裁决（改前读数 / 改后读数 / 三选一结论）

**改前读数（两条，都不是本棒编的）**

1. 主编那一跑（`python3 -u p20b_e2e.py --only=mcp`，原文照抄 `dispatch_p20c.md` 第 29–31 行）：
   合计 21 条判定 PASS=19 FAIL=2，两条红：
   ```
   FAIL M4 首探发生且 TTL 窗内吃缓存（真时间）  anchor=2 samples=[(55, True, 2), (1566, True, 2)]
   FAIL M5 越过 TTL 后重探、但宽限窗内不摘工具（抖动不写缓存）  30-60s 样本=[]
   ```
2. 本棒把观察面修好之后、M5 期望还没改之前那一跑（`logs/e2e_full_console.log`，03:5x，rc=1）：
   ```
     M4 首探的出处可指：…（真时间） PASS alpha_tools=2 anchor=2 second_read=2 samples=[(57, True, 2), (1569, True, 2)]
     M5 越过 TTL 后重探、但宽限窗内不摘工具（30–60s 窗必须真有样本；重探是步进的、不是每轮都探）      FAIL 30-60s 样本=20 条 probes=[4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 34, 36, 38, 40, 42] exposed={True}
   ```
   （这一跑的 M4 断言已经改成关系式了，所以它绿了；M5 还带着我自己写的那条上界。）

**改后读数**：§3.3 里 M4/M5 两条 PASS 的原文（同一份量具、同一驱动、只改断言文本与判据）。

**结论（三选一，逐条给出处）**

- **M4 = 「断言无出处」**。上一棒那条写的是 `samples[0][2] == 1`（首样本期望 1 次探测），
  1 这个数在仓里没有任何出处：alpha 桥真注册了 **2** 个工具（M1 读数 `[mcp-alpha-t1, mcp-alpha-t2]`），
  取一次 schema 就是每个槽位各探一次 ⇒ 2 次。`anchor=2` 与 `second_read=2`（TTL 窗内第二次读一条不许多）
  说明**产品行为是对的**，红在期望值。
  现在这条断言的期望是从读数推出来的关系式：`SEG1_ALPHA_TOOL_COUNT == 首探次数 == 第二次读的次数`，
  出处：`ToolDescriptor.PROBE_TTL_MS=30000`（内核 0.2.1）+ 单测
  `ToolkitProbeTest.probeResultIsCachedForTheWholeTtlWindow`（TTL 窗内吃缓存）。
- **M5 的第一层红 = 「观察面缺口」**（工单猜对了这一半）：上一棒驱动是
  `if (samples < 4 || !exposed) p("SEG1_SAMPLE", …)`，1.5 s 一轮 ⇒ 打出来的 4 条全落在 0–6 s，
  30–60 s 那扇窗里**一条样本都没有** ⇒ `mid` 天然为空，那条断言从来没在判任何东西。
  修法不是放宽断言而是把观察面补上：整个 80 s 窗**每轮都打印**（`P20bToolDriver.java:157-177`）
  ⇒ 同一窗立刻出来 20 条真样本。
- **M5 的第二层红 = 「断言无出处」，而且是本棒自己新写的**：我第一版给它加了
  `max(probes) <= anchor*3`（"重探该是步进的、不是每轮都探"），这个上界同样没出处，
  而且**与产品语义相反** —— 探测**失败**不写缓存是钉在单测里的行为
  （`ToolkitProbeTest.transientFailureInsideGraceWindowKeepsTheToolAndIsNotCached`），
  server 一直死着时每读一次外发清单就重探该桥每个槽位一次 ⇒ 斜率恒等于槽位数 2（读数 `每轮增量=[2]`）。
  现在按精确斜率判（`每轮增量 == SEG1_ALPHA_TOOL_COUNT` 且 `mid[0].probes > anchor` 且窗内 `exposed` 全 True），
  这条比原不等式**更严**，不是放宽。行为本身的代价另记 §5.3(a) 交主编定。


## 4. 杠④ —— `~/.zbot` 红线（本写手亲测）

复算命令（全程只用这三条，**从不 `cat`/`head`/编辑器打开 `~/.zbot` 里任何东西**）：
```
ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties | cut -c1-8 ; md5 -q ~/.zbot/state.db | cut -c1-8
```

| 时点 | 读数 |
| --- | --- |
| 开工（§0 表，03:2x） | `8` / `2dadaed0` / `690ddbc0` |
| 杠③ 全量 E2E 跑前（04:0x，原文） | `== ~/.zbot 跑前: 8 项 {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}` |
| 杠② 全量注入跑到第 9 个变异体时（04:03:00，即 ~10×525 条单测已经跑过之后） | `n=8 cfg=2dadaed0 db=690ddbc0` |
| 杠③ 全量 E2E 跑后（同一次跑的收尾，原文） | `== ~/.zbot 跑后: 8 项 {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}` |
| 收工前（全部门跑完、`git status --porcelain` 已空之后） | `n=8 cfg=2dadaed0 db=690ddbc0` |

E2E 自己的两条判定（`p20b_e2e.py` 的 `finally` 里，跑没跑红都要量）：
```
  R1 ~/.zbot 项数未变（应为 8）                                      PASS 8 → 8
  R2 ~/.zbot/config.properties md5 前缀未变                      PASS {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}
```

凭证那三条（**同一条里反向钉住活的猎物**，工单红线）：
```
  K0 尺的自证：125 字符合成诱饵被同一条正则抓到                                 PASS 抓到 1 个文件
  K1 产物/临时 profile 里没有任何 60+ 字符 api.key（真 key 125 字符）泄漏      PASS 
  K2 同一条反向钉住活的猎物：stub key 就是 config.properties 里 minimax.api.key 的值（该文件确实在扫描面里，长度只报不印） PASS 临时 profile 命中 1 次/1 个 key 行，长度=17，产物侧 7 次/1 个 key 行
```
- K0 是**尺的自证**：`LONG_KEY_RE = api\.key[=\" :]+[A-Za-z0-9_\-]{60,}` 必须抓到一份现造的
  125 字符合成诱饵（`"SYNTHETICDECOYKEY"*9` 截 125），抓不到则 K1 的零命中一文不值。
- K1 扫的是 `_doc/acceptance/p20b/`（本棒全部产物）+ 本轮临时 profile，**不扫 `~/.zbot`**（红线：不读）。
- K2 才是"活的猎物"：临时 profile 里 `minimax.api.key` 的值逐字等于 `stub-key-not-real`（17 字符，
  只报长度不印值），该文件确实在 K1 的扫描面里（`stub_home=1`、`lines_home=1`）。
  ⇒ 没有 K2 的 K1 就是空跑；三条缺一不算过。
- 本轮 E2E 一律 `ZBOT_HOME=<临时目录>`，`make_home()` 造的临时目录才是被测进程读的 profile；
  §3.2 的 C1 读数是临时目录里的 `tool-results`，不是 `~/.zbot/tool-results`。


## 5. 本期发现的产品级缺陷（**未修**，只记账 + 留可复现开关）

这两条都是杠③ 的 mcp 阶段跑出来的**量具外事实**，由主编 `c3ab4da` 那一跑亲测；
本棒复核了它们的可复现性，**没有顺手改**（内核 0.2.1 已发本机，抬版归主编；
`StdioMcpTransport` 不在本仓，改它就等于改内核）。

### 5.1 内核：非紧凑 JSON-RPC 回执会把 z-bot 永久挂住（产品级）

- 现象：真 stdio MCP server 握手挂死 600 s。取证方式（主编跑）：`jcmd <pid> Thread.print`
  ⇒ main 卡在 `com.zifang.z.agent.kernel.mcp.StdioMcpTransport.request:75` 的 `readLine`。
- 根因：内核用 `trimmed.contains("\"id\":" + id)` 认回执，而 `json.dumps` 缺省写成 `"id": 1`
  （冒号后带空格）⇒ 任何不做紧凑序列化的真 server（官方 SDK / Python / Node 多数是带空格这一侧）
  都会让 z-bot **永不超时**。
- 本棒能给的独立侧证（只读构件、没碰内核仓）：
  ```
  $ unzip -p ~/.m2/repository/io/github/yuku123/z-agent-kernel-mcp/0.2.1/z-agent-kernel-mcp-0.2.1.jar \
      com/zifang/z/agent/kernel/mcp/StdioMcpTransport.class | strings | grep '"id"'
  47:{"jsonrpc":"2.0","id":
  70:"id":
  ```
  ⇒ 常量池里那条 `"id":` 就是拿来做 `contains` 的子串，形状与主编说的判据一致。
- 可复现开关（在 `c3ab4da` 里，本棒复核 `git show c3ab4da:_doc/acceptance/p20b/mcp_stub_server.py | grep -c STUB_PRETTY` ⇒ `2`）：
  ```
  $ sed -n '83,86p' _doc/acceptance/p20b/mcp_stub_server.py
          # 而 json.dumps 默认写成 "id": 1（带空格）—— 真实 server（官方 MCP SDK / 任何非手写
          # 序列化的实现）都是带空格这一侧，故 STUB_PRETTY=1 保留该形状作缺陷复现开关。
          seps = (", ", ": ") if os.environ.get("STUB_PRETTY") else (",", ":")
  ```
  复现（**主编那一跑**）：`STUB_PRETTY=1 python3 -u _doc/acceptance/p20b/p20b_e2e.py --only=mcp`
  ⇒ mcp 段 600 s 超时（`jcmd Thread.print` 见 §5.3(a) 里本棒的同类取证）；
  只改这一个变量（其余一字不动）⇒ 同一驱动、同一 jar，main 能走到 `P20bToolDriver.mcpPhase:160` 的真时间探测。
- **本棒的独立复现（有界 50 s，没跑满 600 s）**：不起 `p20b_e2e.py`，直接拿杠③ 已编好的产物起真 JVM，
  只把 `STUB_PRETTY=1` 这一个变量换掉：
  ```
  CP="z-bot-core/target/p20b-driver-classes:z-bot-core/target/z-bot-core.jar:$(cat z-bot-core/target/p20b-classpath.txt)"
  STUB_PRETTY=1 ZBOT_HOME=$H java -cp "$CP" P20bToolDriver mcp "$H" "$PWD/_doc/acceptance/p20b" &
  sleep 50 ; jcmd <pid> Thread.print        # 原文留档 logs/stubpretty_thread.log，驱动 stdout 留档 logs/stubpretty_hang.log
  ```
  驱动 stdout 停在 `TRACE	seg1.bridge.a.registerAll +95ms` 之后再没有第二行（50 s 时仍只有这一行）；
  50.26 s 时的 main 栈（原文，`stubpretty_thread.log` 第 13–37 行，节选）：
  ```
  13:"main" #3 [5891] prio=5 os_prio=31 cpu=106.05ms elapsed=50.26s nid=5891 runnable
     java.lang.Thread.State: RUNNABLE
  27:	at java.io.BufferedReader.readLine(java.base@25.0.2/BufferedReader.java:333)
  30:	at com.zifang.z.agent.kernel.mcp.StdioMcpTransport.request(StdioMcpTransport.java:75)
  32:	at com.zifang.z.agent.kernel.mcp.StdioMcpTransport.open(StdioMcpTransport.java:61)
  34:	at com.zifang.z.bot.mcp.McpClientFactory$StdIoMcpClient.connect(McpClientFactory.java:62)
  35:	at com.zifang.z.bot.mcp.McpBridge.registerAll(McpBridge.java:65)
  36:	at P20bToolDriver.mcpPhase(P20bToolDriver.java:107)
  ```
  ⇒ 与主编描述的挂点逐字一致（`StdioMcpTransport.request:75` 的 `readLine`），且这是**本棒自己量到的**，
  不是照抄。（本棒只跑到 50 s 就 `kill` 了驱动并回收其子进程，没跑满 600 s ⇒ 600 s 那个数仍是主编的读数。）

### 5.2 内核：`request()` 的 30 s deadline 对端不回就永不生效（次生）

`StdioMcpTransport.request` 只在 `readLine()` **返回之间**检查 deadline，`readLine` 自身不超时
⇒ 对端一声不吭就永久阻塞。与 5.1 是同一条调用链上的两个洞，同样记账不修。

本棒的量化旁证就是 §5.1 那份栈：**elapsed=50.26 s 仍在 `readLine` 里**，而 deadline 是 30 s
⇒ 30 s 那道线在这条路径上确实一次都没能拦住（拦得住的话 20 s 后就该在别处看到异常/超时返回）。

### 5.3 本棒新发现的量具外事实（属 z-bot 侧，交主编定，本棒一律没动产品码）

1. **`McpBridge.registerAll:65` 的 `client.connect()` 是同步且无超时的**
   （`grep -n "connect" z-bot-core/src/main/java/com/zifang/z/bot/mcp/McpBridge.java` ⇒ 65 行 `client.connect();`）。
   根因在内核（§5.1/§5.2），但 z-bot 侧"给 connect 包一层超时/丢后台线程"是可做的缓解。
   **本棒没做**：不在本期范围，且会动 `src/main` ⇒ 杠② 的 21 个变异体锚点全要重核。
   现实代价：一台不做紧凑序列化的真 server 会把 `reload()`/启动路径整个挂住（§5.1 的 50 s 是实测下界，主编量到 600 s）。
2. **持续失败期外发清单会把探测放大成"每轮 × 槽位数"**（§3.3 M5 的精确斜率）：
   alpha 桥 2 个槽位 ⇒ 驱动每 1.5 s 读一次 `getExposedToolNames()`，80 s 窗里探测计数从 2 涨到 42（`每轮增量=[2]`）。
   语义本身有出处（失败不写缓存，`transientFailureInsideGraceWindowKeepsTheToolAndIsNotCached`），
   但在真 MCP 场景里 `check_fn` 是一次真 RPC ⇒ 一个卡住的对端能把"组装 prompt"这条路变成同步 RPC 风暴。
   要不要在宽限窗内缓存失败结果（她 `registry.py` 的 grace 语义到底缓存不缓存），**归主编定**，本棒只留读数。
3. **杠② 有三条守卫在单测层没有活的猎物**：TK5（`UNBOUNDED` 哨兵）、TK9（溢出目录 `$ZBOT_HOME` 分支）、
   MB2（"不拿记住的名字当结论"）⇒ 判 `GREEN-BUT-MUTATED`，按工单口径算**未覆盖**，不算过；
   账在 §2.2/§2.5。本棒按纪律**没有**回填期望、也**没有**补测试。
4. **`Toolsets.java:16` 的注释路径写歪**：写的是 `tools/toolsets.py`，实测她的定义在**仓根** `toolsets.py`
   （§0.2 第五条）。属文档级瑕疵，改它要动 `src/main`（虽不影响任何断言），本棒没动。



## 6. 量具自己坏过的记录（本棒全部点名，不留"悄悄修好"）

按发生顺序。**这些都是尺坏了，不是产品坏了**；每一件都写清"坏在哪、怎么发现、改完留了什么读数"。

1. **杠③ 观察面空跑**（上一棒留的，本棒接手时用它当 M5 的判据）：
   驱动 `if (samples < 4 || !exposed) p("SEG1_SAMPLE", …)` ⇒ 4 条样本全落在 0–6 s，30–60 s 窗**零样本**
   ⇒ M5 的 `mid` 天然为空。发现方式：把驱动每轮都打印，窗内立刻 20 条（§3.4）。
2. **`report_dirs()` 的目录过滤写错**：`os.walk` 的判据把 `surefire-reports` 那一层筛没了 ⇒ 解析不到 XML。
   改成 `glob` 匹配 `*/target/surefire-reports`。
3. **`broken`（编译不过）检测表达式自相矛盾**：写成永远不成立的形式 ⇒ BROKEN 类判定形同虚设。
   改成读一次 mvn 日志判 `"COMPILATION ERROR" in text`。
4. **`selected` 选择逻辑退化**：带 id 参数时仍然全量跑。改成显式 if/else。
5. **`foreign_mvn_running()` 的判据用 `"mvn" in line`**（工单要求跑前 `ps` 看别人的 mvn）：
   把**本棒自己**命令行里含 "mvn" 的监控 shell 甚至脚本自己都当成邻居 ⇒ 第一次注入跑死等 900 s。
   改成只认真身标记 `("classworlds","surefirebooter","maven.conf","plexus")`，并排除含本树路径 /
   `p20b_mutation.py` 的行；smoke test 返回 `[]`。现场核对：本棒自己的 maven launcher 命令行里带
   `/private/tmp/zbot-wt-p20b` ⇒ 被排除，等到的那几个确实是别人仓的。
6. **中途 kill 注入跑 ⇒ 盘上留了一份已 mutate 的源文件**（第一次注入跑，本棒杀的）：
   `Toolkit.java` 盘上 md5 `379275e7f368110083b2dda625e7e48c` ≠ git `4ae1a491cd73b0f1c1ac869db59723ad`。
   工单禁用 `git checkout --` ⇒ 用 python 把 `git show HEAD:<path>` 的字节写回 + `fsync` + md5 逐字节比，
   输出 `equal=True` 才算还原。**教训**：注入跑不能中途 kill，要等它自己走完还原。
   （这份坏事的 md5 正好就是 §2.3 台账里 TK9 那行的 `disk_md5_during_injection` —— 同一份已 mutate 内容，
   现在由脚本自己产出、自己比对、自己还原。）
7. **`LEDGER_RESTORE.tsv` 的 `injection_landed` 标签三元写反**：
   差异成立（注入真进盘）时反而打印 `no(注入确实改了盘)` ⇒ 台账字面与它自己的数据自相矛盾。
   发现方式：本棒一次 `Read` 到的文本与盘上不一致，于是改用直接统计复核：
   ```
   $ cut -f5 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | sort | uniq -c
      1 injection_landed
     21 no(注入确实改了盘)
   ```
   修成 `"yes(disk!=git show)" if during != bmd5 else "NO(注入没进盘!)"`，并**全量重跑 21 个变异体**
   重出两份台账（人不敲一行）。⇒ 本文引用的所有台账数字都以 `cut`/`grep -c` 的直接统计为准。
8. **锁忙兜底路径自己崩溃**：`rows = [[m[0], …]]` 里 `m` 未定义 ⇒ 拿不到锁时 `NameError`，
   记账文件写不出来（恰好是"不许伪造读数"要求最硬的那条路径）。
   崩溃发生在打印 `FATAL 锁被占` **之后、碰任何源文件之前**，现场 `git status --porcelain` 里
   四个被测源文件一字未变。修成逐变异体各记一行 `BROKEN/未跑：锁被占`，实测见 §2.4。
9. **杠③ 的期望值写错两处**（不是尺坏，是刻度错）：M4 的 `==1`（上一棒）、M5 的 `<=anchor*3`（本棒自己加的）。
   两条都在 §3.4 点名并换成有出处的判据，**没有下调任何一条**。
10. **E2E 注释自述 stub key 长度写 19，实为 17**（`len("stub-key-not-real")`，K2 报的 `长度=17`）。
    已改注释。这类"自述与盘不符"正是上一棒的死因，凡出现就地订正并留此条。

## 7. 本期没做的（别当成做了）

1. **两个内核缺陷一个都没修**（§5.1/§5.2），也没碰内核仓与 `~/.m2` 的内核构件；抬版与修法是主编的决定。
2. **`McpBridge` 的 connect 没加超时/后台化**（§5.3(1)），`Toolsets.java:16` 的注释路径没改（§5.3(4)）
   —— 两件都会动 `src/main`，动了杠② 的 21 个锚点就得全部重核，本棒选择只记账。
3. **杠② 的三处"单测层无猎物"（TK5/TK9/MB2）没补单测**：期望集是跑前写死的，跑完不许回填；
   补测试属于新一轮的活，本棒只给出"唯一有读数的一面是杠③ 的 C7/C1/M10/M12"。
4. **杠② 只打到单测层**：没有把变异体注入到 E2E 真进程跑（`p20b_e2e.py` 与注入器不相干；
   注入期间跑 E2E 也会互相踩 `target/`）。⇒ "注入 × 真进程"这个乘积本期没有。
5. **没量多桥并发抖动 / 一台慢（而非死）的 server**：M4–M7 只测了一个桥两个槽位、对端直接 kill。
   慢响应与 TTL 的交互（会不会两边同时探）没有读数。
6. **溢出落盘的生命周期没做**：文件留多久、谁回收、目录盘满怎么办 —— 一律没有实现也没有测试。
7. **工单范围里"并行安全从注册表移到 dispatch helper"这一条本棒没单独取证**：
   声明面的读数只有杠③ T5 的 `ro/rw` 标记与杠② 的 TS4；z-bot 侧 `src/main` 里 `parallelSafe` 0 命中，
   串行/并行分组到底落在哪一层（内核 `ToolRegistry` 还是 hermes 式 helper）本棒没查清 ⇒ 归主编。
8. **桌面打包侧（`z-bot-desktop-packager`）没跑**：本期只 `-pl z-bot-core package -DskipTests` 起过 core 产物。
9. **没有主循环节律 / 上下文压缩面的任何验证**（工单明写不在本期）。
10. **没集成、没合并、没 push**：`w2-p20b` 停在本地，`main` 一字未动。
11. **STUB_PRETTY 那条只复现到 50 s**（§5.1），没跑满 600 s；"600 s 超时"这个数仍是主编那一跑的读数。

## 8. 复算命令清单

```
# 杠①（三跑，每跑约 15 s）
rm -rf z-bot-core/target/surefire-reports && mvn -o test | tail -20
git grep -c '@Test' $(git rev-parse --short HEAD) -- 'z-bot-core/src/test/**' | awk -F: '{s+=$NF} END{print s}'   # 525
git grep -c '@Test' 13027f4 -- 'z-bot-core/src/test/**' | awk -F: '{s+=$NF} END{print s}'                          # 465
git diff --name-only c3ab4da HEAD -- '*/src/*' | wc -l                                                               # 0（本棒没动 src）

# 杠②（只读台账；台账是脚本产物，不许手改）
python3 -u _doc/acceptance/p20b/p20b_mutation.py --check          # 只做锚点/漂移/期望名存在性预检
cut -f2 _doc/acceptance/p20b/LEDGER.tsv        | tail -n +2 | sort | uniq -c      # 判定分布
cut -f5 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c     # 注入是否真进盘
cut -f7 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c     # 还原是否逐字节等于 git 原文
python3 -u _doc/acceptance/p20b/p20b_mutation.py --hold-lock 25   # 攥锁侧；另开一壳跑全量应 rc=5 且 src 一字不变

# 杠③（全量三阶段一次；约 2 分钟，含 mvn package + javac + 60 s 真时间窗）
python3 -u _doc/acceptance/p20b/p20b_e2e.py                        # 期望末行：== 合计 38 条判定，PASS=38 FAIL=0
python3 -u _doc/acceptance/p20b/p20b_e2e.py --only=mcp             # 只跑 mcp 段

# 杠④
ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties | cut -c1-8 ; md5 -q ~/.zbot/state.db | cut -c1-8

# §0.2 的 hermes 侧（只读）
cd /Users/zifang/.hermes/hermes-agent && git rev-parse --short HEAD && grep -cE '^\s+"[a-z0-9_]+":\s*\{' toolsets.py

# §5.1 的开关与栈取证（有界 50 s；命令原文见 §5.1）
git show c3ab4da:_doc/acceptance/p20b/mcp_stub_server.py | grep -c STUB_PRETTY     # 2
```

### 8.1 交付物五件（工单口径）在库状态

```
$ git ls-files _doc/acceptance/p20b/
```
1. 产品代码 + 单测：`tool/Toolsets.java`（本期新建）+ `Toolkit.java` / `McpBridge.java` / `McpManager.java`
   + 7 个新测试类，525 条全绿（§1）。**本棒对这些文件一字未改**（§1.2 的 `git diff` 读数为 0）。
2. `_doc/acceptance/p20b/p20b_mutation.py` —— 21 个变异体，期望集跑前写死（`438ea50` 入库）。
3. `_doc/acceptance/p20b/p20b_e2e.py`（+ `P20bToolDriver.java`、`mcp_stub_server.py`）—— 真进程三阶段 38 条判定。
4. `_doc/acceptance/p20b/LEDGER.tsv`（21 行）+ `LEDGER_RESTORE.tsv`（21 行，还原取证）—— 均为脚本产物。
5. 本文 `EVIDENCE.md`。


# 9. 本棒 p20d —— 把 §2.2 那 7 条非 RED-OK 逐条落地

> 工单：`~/.cache/zbot-p17/dispatch_p20d.md`。接手 HEAD `84ca7b9`（工作树里另有上一棒 p20c
> 没提交的 EVIDENCE 增补 §1.2/§8.1，本棒第一件事就是把那 33 行**原样提交**、不覆盖历史）。
> 写域：`z-bot-core/src/test/**` + `_doc/acceptance/p20b/**` + 一行授权（`Toolsets.java:16` 注释里的路径）。
> **产品行为一字未动。** 上面 §0–§8 是"本棒前"的账，一律不改写；本节只往后加。

## 9.0 起手实测（不采信简报）

| 项 | 命令 | 读数 |
| --- | --- | --- |
| 接手 HEAD / 分支 | `git rev-parse --short HEAD` / `rev-parse --abbrev-ref HEAD` | 接手时 `84ca7b9` / `w2-p20b`；量这张表时本棒已把上一棒那 33 行增补提交为 `15a07ac` ⇒ 读数行 HEAD=`15a07ac` |
| 在途状态 | `git status --porcelain` | 接手时 **不为空**：` M _doc/acceptance/p20b/EVIDENCE.md`（上一棒 p20c 死在收尾前，那 33 行没提交）⇒ 本棒第一笔提交 `15a07ac` 原样收下，不改写 |
| `~/.zbot` 三数 | `ls -A ~/.zbot \| wc -l`; `md5 -q ~/.zbot/config.properties \| cut -c1-8`; `md5 -q ~/.zbot/state.db \| cut -c1-8` | `8` / `2dadaed0` / `690ddbc0` ⇒ 与 §0 逐字同，红线未被上一棒破坏 |
| 邻居的 maven 真身 | `python3 -c "…import p20b_mutation as m; print(m.foreign_mvn_running())"`（**不用 `grep mvn`**，§6(5) 的坑） | 当场 `FOREIGN_MVN_HITS=4`（别的仓的 maven 真身，pid 85209 起）⇒ 本棒先把测试写完，注入跑等窗口；锁当场 `FREE` |
| 锁的落点 | `git rev-parse --git-common-dir` | `/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git` ⇒ 锁文件同 §0 |
| 起点静态对拍 | `git diff --name-only c3ab4da HEAD -- '*/src/*' \| wc -l` / `git grep -c '@Test' HEAD -- 'z-bot-core/src/test/**' \| awk -F: '{s+=$NF} END{print s}'` | `0` / `525` ⇒ 与 §1.2 一致，本棒从 525 起加用例 |

## 9.1 七行总账（本棒前 ⇒ 本棒后）

| 行 | 本棒前 | 本棒后 | 本棒动作（一句话） | 支撑读数在 |
| --- | --- | --- | --- | --- |
| TS4 | PARTIAL 4/5 | 待填 | 待填 | §9.2 |
| TK3 | PARTIAL 3/7 | 待填 | 待填 | §9.3 |
| TK5 | GREEN-BUT-MUTATED 0/1 | 待填 | 待填 | §9.4 |
| TK9 | GREEN-BUT-MUTATED 0/0 | 待填 | 待填 | §9.5 |
| MB2 | GREEN-BUT-MUTATED 0/0 | 待填 | 待填 | §9.6 |
| MB3 | PARTIAL 8/8 | 待填 | 待填 | §9.7 |
| TK11 | PARTIAL 13/13 | 待填 | 待填 | §9.8 |

tally 并排（原账不许覆盖）：
```
本棒前：RED-OK 14 / PARTIAL 4 / GREEN-BUT-MUTATED 3 / BROKEN 0
本棒后：待填
```

## 9.2 TS4 —— 期望与实测同源（`ToolsetsManifestTest.java:128`）
待填：改法 / 命令 / 读数。

## 9.3 TK3 —— 注点从 accessor 改到快照键
待填。

## 9.4 TK5 —— 等价变异的实测确认 + 换成不等价注点 + 用例补活猎物
待填。

## 9.5 TK9 —— `$ZBOT_HOME` 那一级要有真猎物（起带 env 的子 JVM）
待填。

## 9.6 MB2 —— 桥级"注册表有、桥没记住"的现场
待填。

## 9.7 MB3 —— 保留 PARTIAL 的因果
待填。

## 9.8 TK11 —— 差集机械补进期望集
待填。

## 9.9 收尾重出（杠② 全量 21 个 / 杠① 串行三跑 / 杠③ 全量三阶段 / 杠④ 三数）
待填。

## 9.10 本棒仍未覆盖的
待填。

## 9.11 本棒复算命令
待填。

