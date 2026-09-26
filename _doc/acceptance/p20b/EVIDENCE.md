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

### 9.0.1 本棒接手时是"第二棒实例"（简报说的 HEAD 与 status 都不成立，实测为准）

§9.0 那张表是 p20d **第一个实例**量的。它写完 §9 骨架（`9f400b3`）与四条补网（`3ffe03f`）之后，
在 10:22 的一次**子集诊断跑**之后死在权威跑之前。本棒（p20d 第二个实例）接手实测：

| 项 | 命令 | 读数 |
| --- | --- | --- |
| 接手 HEAD / 分支 | `git rev-parse --short HEAD` / `--abbrev-ref HEAD` | `3ffe03f` / `w2-p20b` ⇒ **不是简报写的 `84ca7b9`**（第一个实例已提交两笔） |
| 在途状态 | `git status --porcelain` | ` M LEDGER.tsv` ` M LEDGER_RESTORE.tsv` ` M p20b_mutation.py` ⇒ **不是空**：那两份是 10:20-10:22 的 8 行子集诊断跑产物 |
| 诊断跑留档 | `cp LEDGER*.tsv logs/…` + `md5 -q` | `logs/LEDGER_subset_1022.tsv`（9 行 = 表头 + TS4/TK3/TK4/TK5/TK7/TK9/TK11/MB2，md5 `2a3b3828`）⇒ **原样留档不覆盖**；交付位 `LEDGER.tsv`/`LEDGER_RESTORE.tsv` 用 `git show HEAD:` 字节写回等值校验（`cf7d680d` / `f5ef5586`，`equal=True`）后交还给脚本重生成 |
| 四个被测源文件 | `python3 -c "…disk_md5 vs git_md5(HEAD)…"` | `Toolsets f1d8195f / Toolkit 4ae1a491 / McpBridge 2938f85b / McpManager 37a14145`，四个 `True` ⇒ 第一个实例死时没留已 mutate 的源文件（§6(6) 那个坑没重犯） |
| `~/.zbot` 三数 | 同 §9.0 | `8` / `2dadaed0` / `690ddbc0` ⇒ 红线仍在 |
| 锁与邻居 | `--want-lock` / `foreign_mvn_running()` | 10:41 当场 `FATAL 锁被占`（兄弟 worktree 的 mutator，锁文件里 `pid=53499 tag=mutator`）；本机今晚六棒同跑，`ps` 里 maven 真身常在 3-15 个 ⇒ 本棒所有注入都走"拿不到锁就不跑"的路径，等待期间只写 `_doc`，一个源文件都不碰 |


### 9.0.2 本棒接手时是"第三棒实例"（前两棒都把交付跑死在收尾前，以实测为准）

| 项 | 命令 | 读数 |
| --- | --- | --- |
| 接手 HEAD / 分支 | `git rev-parse --short HEAD` / `--abbrev-ref HEAD` | `1067b77` / `w2-p20b` ⇒ 简报写的 `84ca7b9` 与"status 应为空"都不成立 |
| 在途状态 | `git status --porcelain` | ` M LEDGER.tsv` ` M LEDGER_RESTORE.tsv`（第二棒 R1 全量取证跑的产物，md5 `7258d957…`/`86f21d52…`）+ `?? logs/LEDGER_R1.tsv` `?? logs/LEDGER_RESTORE_R1.tsv`（同字节留档，md5 逐字同）+ `?? __pycache__/` ⇒ 本棒第一笔把 R1 台账与留档一起收下，不覆盖历史 |
| R1 是否真跑完 | `tail ~/.cache/zbot-p17/p20d/chain.log` + `~/.cache/zbot-p17/p20d/r1_console.log` | `[r1] FINAL rc=0`、`CHAIN_DONE probe_rc=0 r1_rc=0 2026-09-26 11:08:50`、tally `RED-OK 17 / PARTIAL 4 / GREEN-BUT-MUTATED 0 / BROKEN 0`、`SRC_MD5_STABLE=yes` ⇒ **R1 是全量 21 个的真跑，不是子集**；第二棒死在 R1 之后、R2 之前 |
| 四个被测源文件 | `python3 -c "…disk_md5 vs git_md5(HEAD)…"` | 与 §9.0.1 同一口径，四个 `True`（`Toolsets f1d8195f / Toolkit 4ae1a491 / McpBridge 2938f85b / McpManager 37a14145`）⇒ 接手时盘上没有残留注入 |
| `~/.zbot` 三数 | 同 §9.0 | `8` / `2dadaed0` / `690ddbc0` ⇒ 红线仍未被破坏 |
| 锁与邻居 | `python3 -u _doc/acceptance/p20b/p20b_mutation.py --check` 起手的锁格 + `ps` | 11:2x 当场锁被兄弟 worktree 的 mutator 攥着 ⇒ 本棒的 R2 走"拿不到锁就等（60 s × 30 轮）"的重试路径；`--check` 在等锁之前完成：`预检过：锚点次数 + 盘上原文 == git show + 无漂移`（基线 rev `1067b775…`） |
| R1 的 25 份 mvn 原文 | `mv logs/r1_mut_*.log ~/.cache/zbot-p17/p20d/r1_mut/` | 仓内只留台账（mvn 原文按本仓惯例不入库，§8.1 只列五件交付物），原文 25 份在缓存位；本棒把要引用的行**原文粘进本节**，不写"见日志"就算了 |



## 9.1 七行总账（本棒前 ⇒ 本棒后）

> "本棒后"分两栏记，是因为 §9.9.1 的两轮口径：**R1 是取证跑**（期望集 = `cde236b` 那份，用来看现状与差集），
> **R2 才是交付跑**（期望集 = R1 之后按 §9.9.2 机械补集/收窄的那一份）。两栏都是脚本台账原文，不是转述。

| 行 | 本棒前 | 本棒后（R1 取证跑，11:08:50） | 本棒后（R2 交付跑） | 本棒动作（一句话） | 支撑读数在 |
| --- | --- | --- | --- | --- | --- |
| TS4 | PARTIAL 4/5 | **RED-OK 5/5** | **RED-OK 5/5** | 期望换成测试侧字面量表，不再与 `toolsetForTool` 同源 | §9.2 / §9.9 |
| TK3 | PARTIAL 3/7 | PARTIAL 4/5（点名=4/5，红的正是那 4 条；没出处的那 1 条 R2 前摘掉） | **RED-OK 4/4** | 注点从 accessor 挪到快照键那一行，期望集按"先暖过快照"的出处收窄 | §9.3 / §9.9 |
| TK5 | GREEN-BUT-MUTATED 0/1 | **RED-OK 1/1** | **RED-OK 1/1** | 实测确认原注点是等价变异 ⇒ 换不等价注点 + 用例补两把钥匙（正反腿） | §9.4 / §9.9 |
| TK9 | GREEN-BUT-MUTATED 0/0 | **RED-OK 1/1** | **RED-OK 1/1** | 起真带 `$ZBOT_HOME` 的子 JVM 探针（带假 `user.home` 笼子），正反两把 | §9.5 / §9.9 |
| MB2 | GREEN-BUT-MUTATED 0/0 | **RED-OK 1/1** | **RED-OK 1/1** | 桥级"注册表有、桥没记住"现场（同名第二个桥实例 + 僵尸槽 + 旁观 server） | §9.6 / §9.9 |
| MB3 | PARTIAL 8/8 | PARTIAL 8/8（+3 条多红未点名，**故意不回填**） | **PARTIAL 8/8（+3 条多红未点名）** | **不动**（工单明写"保留 PARTIAL、不硬凑"），只把因果写明 | §9.7 / §9.9 |
| TK11 | PARTIAL 13/13 | **RED-OK 17/17** | **RED-OK 17/17** | 差集机械补进期望集（4 条，每条注明是哪一跑钉的） | §9.8 / §9.9 |

> R2 那一栏是交付台账（`LEDGER.tsv`，md5 `90dda63c…`）的 `named_hit/named_expected` 原文。
> **另外 14 行（本棒没碰的）在 R2 全部仍是 RED-OK 且点名全中**（TS1 1/1、TS2 2/2、TS3 2/2、TS5 2/2、TS6 3/3、
> TK1 4/4、TK2 6/6、TK6 1/1、TK8 1/1、TK10 1/1、MB1 1/1、E1 3/3）⇒ 本棒补的网没有把上一棒的网碰坏。

tally 并排（原账不许覆盖）：
```
本棒前：RED-OK 14 / PARTIAL 4 / GREEN-BUT-MUTATED 3 / BROKEN 0        （21 个变异体，§2 交付态那一跑）
R1 取证跑：RED-OK 17 / PARTIAL 4 / GREEN-BUT-MUTATED 0 / BROKEN 0     （11:08:50，logs/LEDGER_R1.tsv，md5 7258d957…；
              仍 PARTIAL 的 4 条 = TK3（期望集宽了 1 条，§9.3）+ TK4/TK7（本棒新用例的多红未点名，§9.1.1）+ MB3（不回填，§9.7））
本棒后：RED-OK 20 / PARTIAL 1 / GREEN-BUT-MUTATED 0 / BROKEN 0        （R2 交付跑 11:34:53，LEDGER.tsv md5 90dda63c…，
              剩下那 1 条 PARTIAL = MB3，工单明写保留；原文核对见 §9.9.6）
```

### 9.1.1 连带影响：本棒新写的用例让另外两条从 RED-OK 变成"多红未点名"

本棒往 `unboundedSentinelMeansNoTruncationAtAll` 里加的反向腿、以及新写的
`zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot`，让 **TK4 / TK7** 这两条上一棒记 RED-OK 的行
出现了"红了但没点名"（它们本来的猎物照样红）。处理口径与 TK11 一致：按实跑读数**机械补集**，
不改判定文本、不改注点，逐条注明是哪一跑钉的 ⇒ 见 §9.9.2。


## 9.2 TS4 —— 期望与实测同源（`ToolsetsManifestTest.java:128`）

**改法**（只动 `src/test`，产品码一行没动）：`readOnlyToolsDeclareParallelSafetyAndWriteToolsDoNot` 的归属回读
从 `assertEquals(name, Toolsets.toolsetForTool(name), tk.toolsetOf(name))` 换成

```java
assertEquals("常量表必须正好覆盖注册表里的工具，否则下面这个循环是空跑",
        new java.util.TreeSet<String>(tk.getToolNames()),
        new java.util.TreeSet<String>(EXPECTED_TOOLSET_BY_TOOL.keySet()));
for (String name : tk.getToolNames()) {
    assertEquals(name, EXPECTED_TOOLSET_BY_TOOL.get(name), tk.toolsetOf(name));
}
```

- `EXPECTED_TOOLSET_BY_TOOL`（`ToolsetsManifestTest.java:213-236`）是**测试侧字面量表**：core 5 个
  （echo/time/counter/health/sysinfo）、file 3 个（read_file/write_file/search）、exec 2 个（exec/mvn_build）、
  net 1 个（curl_test）。逐条与 `Toolsets.java:106-114` 的 `declare(...)` 表对过（本棒手工核，不是调函数生成）。
- 前面那条"键集 == 注册表工具名集"是**防空跑的笼子**：字面量表若漏了一个工具，循环就少判一条，
  所以先把表本身钉成全覆盖，再谈期望值。
- 为什么这样就判得出：TS4 注入把 `toolsetForTool` 摘成"一律退回 `Toolkit.DEFAULT_TOOLSET`"，
  注册侧 `BuiltinTools.java:82` 调的正是同一个函数 ⇒ 槽位全落 builtin；期望若同源，两边一起漂、等式照样成立。
  期望换成常量之后，注入当场把 `tk.toolsetOf(name)` 打到 builtin，与常量表逐条不相等 ⇒ 红。

双向：还原态这条绿（控制跑，见 §9.9），注入态这条红（判定行 `TS4 … RED-OK 5/5`，红名单含本条）。
R1 实跑原文（`logs/LEDGER_R1.tsv` TS4 行 + `r1_mut_TS4.log`）：`TS4 … RED-OK 5/5`，
`tests_that_went_red` 里含 `readOnlyToolsDeclareParallelSafetyAndWriteToolsDoNot` ⇒ 换常量表之后这一刀真判得红。

### 9.2.1 "写读共用同一归一化"的自查（工单要求：凡是这种形状都按 TS4 这一条过一遍）

机械扫描（不靠印象），把**断言行里同一个生产侧/注册表函数出现两次以上**的都捞出来：

```
cd z-bot-core/src/test/java && python3 - <<'PY'   # 断言行内 PROD/METH 调用去重计数，>1 即嫌疑
PROD = r'\b(Toolsets|Toolkit|McpBridge|McpManager|ToolDescriptor|BuiltinTools|Config)\.([A-Za-z_]+)\s*\('
METH = r'\b(tk|toolkit|bridge|manager|second|reg|d|decl)\.([A-Za-z_]+)\s*\('
PY
```
读数：`TOTAL_SUSPECT_LINES=6`，逐条看完**全是假嫌疑**（函数第二次出现只是失败消息串，不是期望值）：

| 嫌疑行 | 为什么不是同源 |
| --- | --- |
| `mcp/McpBridgeDeregisterTest.java:57` | `assertTrue(tk.getToolNames().toString(), …contains("mcp-legacy-alpha"))` —— 第一个参数是消息串，期望是字面量 |
| `mcp/McpBridgeReloadReverseAssertionTest.java:47` | 同上，字面量 `"mcp-dying-alpha"` |
| `tool/ExecGuardHardlineTest.java:26` | 消息串 + `startsWith(ExecGuard.HARDLINE_PREFIX)`，前缀是常量不是函数 |
| `tool/ToolkitRegistryTest.java:141` / `:194` / `:210` | 消息串，contains 的参数是字面量 `"mcp-srv-old"` / `"mcp-x-1"` / `"ghost"` |

另外两处"期望取自函数"的形状，本棒逐条对过，**结论不同**：
- `ToolsetsManifestTest.java:163/:174/:181-187`：期望是**字面量**（`"mcp-fs"`、`"mcp-a_b_c"`、`Toolkit.DEFAULT_TOOLSET`），
  实测才是 `Toolsets.toolsetForTool/mcpToolset/mcpOwner` ⇒ 不同源（TS5 判红的正是这一族）。
- `ToolsetsManifestTest.java:150-151`：`emptyCapabilityToolsets` 的期望取自 `Toolsets.capabilityNames()`。
  这**不是** TS4 那种同源漂移 —— 这两个函数读同一张 `MANIFEST` 声明表，声明表就是真源，测试要判的是
  "审计有没有把清单里的空能力报出来"而不是"清单写得对不对"（后者由 `everyCapabilityToolsetDeclaresConsumerAndMembers`
  与字面量表那条判）。诚实记账：这条腿对 **TS6**（`capabilityNames` 自己漂）确实是**一起漂**的 ⇒
  TS6 不靠它判红，靠的是另外三条（台账 `TS6 … RED-OK 3/3`，红名单三条都在）⇒ 网还在，只是不挂在这一条上。
- 字面量表 `EXPECTED_TOOLSET_BY_TOOL`（`ToolsetsManifestTest.java:222` 起，方法体 224-237）本棒**重新手工对过**
  `Toolsets.java:106-114` 的 `declare(...)` 表：core 5（echo/time/counter/health/sysinfo）、
  file 3（read_file/write_file/search）、exec 2（exec/mvn_build）、net 1（curl_test）= 11 个，逐字相同；
  且 `:132-134` 那条"键集 == 注册表工具名集"的笼子保证这张表不会漏工具而空跑。

**第二轮扫（另一种形状：期望位是生产侧 getter/常量）**，命令与读数：
```
grep -rn "assertEquals(\s*[A-Za-z_.]*\.\(toolsetOf\|namesOfToolset\|getToolNames\|resultCapFor\|generation\|schemaFingerprint\|toolsetsInUse\|registeredNames\|snapshot\)(" z-bot-core/src/test/java   ⇒ 0 行
grep -rn "assert[A-Za-z]*(Toolsets\.\|assert[A-Za-z]*(Toolkit\.\|assert[A-Za-z]*(BuiltinTools\." z-bot-core/src/test/java                                        ⇒ 12 行，逐条看完
```
12 行命中逐条看完：**4 行**是布尔谓词断言（`:79/:81/:83/:84` 的 `assertTrue/assertFalse(Toolsets.isDeclared(…))`，
期望是真值不是同源值）、**2 行**是 `null`/兜底常量期望（`:164/:174`）、**3 行**是枚举常量期望
（`:50/:75/:78`，其中 `:50` 见下表）、**3 行**是本表后两行。
另：`ToolsetsManifestTest.java:96-99` 不在这两个 grep 的命中里（期望位写作 `Arrays.asList(…)`），
本棒手工补进来一起判 ⇒ 下面这张表共四行。

| 行 | 形状 | 判定 |
| --- | --- | --- |
| `ToolsetsManifestTest.java:45-50` | 遍历 `capabilityNames()`，逐条 `assertEquals(Kind.CAPABILITY, d.kind())` | **同义反复**（`capabilityNames` 本身就是按 `kind==CAPABILITY` 过滤的）⇒ 这一条腿只能检出"**多报**"（TS6 判红的正是它，台账 `TS6 … 3/3`），**检不出"少报"**。"少报"由下一行那把常量尺兜着，不是靠它 |
| `ToolsetsManifestTest.java:96-99` | `assertEquals(asList(Toolsets.CORE, EXEC, FILE, NET), capabilityNames())` | 期望取自**常量**不是函数 ⇒ `capabilityNames` 少一个名字就红，形状正确。诚实记一笔残留：若 `Toolsets.NET` 这个**常量本身**漂成 `"network"`，这一条两侧一起漂 ⇒ 判不出来；但它被 `:132-136` 那张字面量表（写死 `"core"/"file"/"exec"/"net"`）间接钉住了 —— 常量漂了 ⇒ MANIFEST 的键漂了 ⇒ `tk.toolsetOf(name)` 与字面量不相等 ⇒ 红。⇒ **网还在**，交下一棒的动作是把 `:99` 的四个常量直接写成字面量（一行的事，本期不做，理由见 §9.10(9)） |
| `ToolkitRegistryTest.java:205` | `:203` 用 `Toolsets.CORE` 注册，`:205` 用同一个常量断言 `tk.toolsetOf("a")` | 这是**存取往返**测试（判的是"注册表有没有把给它的 toolset 存下来"），期望与写入同源是**语义要求**，不是 TS4 那种漂移 ⇒ 保留；TS4 的账在"清单归属"那一族，不在这一条 |
| `McpBridgeDeregisterTest.java:140/:187` | `assertEquals(Toolsets.MCP_PREFIX + "mine"/"deep_kb", …)` | 期望 = 前缀常量 + **手写的归一化名**（`deep-kb` → `deep_kb` 是本棒/上一棒手工算的，不是调 `mcpToken` 算的）⇒ 不同源，TS5 判红的正是这一族 |



## 9.3 TK3 —— 注点从 accessor 改到快照键

**注点改了**（`p20b_mutation.py:136-145`）：

| | 上一棒 | 本棒 |
| --- | --- | --- |
| 锚点 | `return registry.generation();`（= `Toolkit.generation()` accessor，`Toolkit.java:221-223`） | `long generation = registry.generation();`（= `snapshot()` 里那行缓存键，`Toolkit.java:310`） |
| 替换 | `return 0L;` | `long generation = 0L;` |
| 锚点次数 | 1 | 1（预检当场核） |

**期望集按"有没有出处"重列**：出处 = 该用例必须在**注册表变化之前暖过一次快照**
（`snapshot()` 的缓存命中路径就是 `current.generation == generation`，键被钉死 0 才会让下一次读到旧快照）。

| 期望用例 | 出处（先暖后读的那两行） |
| --- | --- |
| `registerAndDeregisterEachInvalidateTheSchemaSnapshot` | `ToolkitRegistryTest.java:170` 取 `schemaFingerprint()` 暖快照 → `:173` 注册 `b` → `:174-175` 比指纹/重建数 |
| `exposedNamesTrackTheRegistryAfterNukeAndRepave` | `ToolkitRegistryTest.java:191` `namesOf(tk)`（走 `getAllTools()`→`snapshot().tools`）→ `:192` `deregisterToolset` → `:193` 再读 |
| `unregisteringIsNotStubOverwrite` | 桥级：注册→读外发清单→`unregisterAll`→再读（`McpBridgeDeregisterTest.java:97` 是断言行） |
| `reloadDropsTheDeadServersToolNamesFromGetToolNames` | 桥级：注册→reload 换名→读外发清单（`McpBridgeDeregisterTest.java:78` 是断言行） |

**摘掉的一条**：`deregisterActuallyRemovesTheSlotFromEveryView`（`ToolkitRegistryTest.java:42`）。
依据两句话 + 一个实跑读数：
1. 它在注册表变化**之前**从没读过任何快照视图 —— `with("a","b")` 只调 `register`，
   `:50` `namesOf(tk)`、`:51` `getToolsDescription()` 都在 `:45` 的 `deregister` **之后**，
   第一次 `snapshot()` 就是新状态 ⇒ 缓存键对不对它都看不出来；
2. 它读的是 `tk.generation()`（`:53`）那个 accessor，而 accessor 已经**不是**本变异体的注点；
3. 读数：R1 全量跑 `TK3 … 点名=4/5`，红的正是上面那 4 条
   （`~/.cache/zbot-p17/p20d/r1_mut/r1_mut_TK3.log` 原文，逐字粘在下面这 7 行）：

   ```
   [ERROR] Tests run: 11, Failures: 2, … -- in com.zifang.z.bot.mcp.McpBridgeDeregisterTest
   [ERROR] com.zifang.z.bot.mcp.McpBridgeDeregisterTest.reloadDropsTheDeadServersToolNamesFromGetToolNames … <<< FAILURE!
   [ERROR] com.zifang.z.bot.mcp.McpBridgeDeregisterTest.unregisteringIsNotStubOverwrite … <<< FAILURE!
   [ERROR] Tests run: 16, Failures: 2, … -- in com.zifang.z.bot.tool.ToolkitRegistryTest
   [ERROR] com.zifang.z.bot.tool.ToolkitRegistryTest.registerAndDeregisterEachInvalidateTheSchemaSnapshot … <<< FAILURE!
   [ERROR] com.zifang.z.bot.tool.ToolkitRegistryTest.exposedNamesTrackTheRegistryAfterNukeAndRepave … <<< FAILURE!
   [ERROR] Tests run: 527, Failures: 4, Errors: 0, Skipped: 0
   ```

   ⇒ `deregisterActuallyRemovesTheSlotFromEveryView` **一条都没红**（它不在上面任何一行里），
   而 `Tests run: 527, Failures: 4` 的 4 条正好等于本表那 4 条有出处的期望。
⇒ 这是**量具的账**（期望集写宽了），不是产品的红；判定文本与注点语义一字未改，只把没出处的那条摘掉。
**摘名发生在 R1 之后、R2（交付跑）之前**，见 §9.9.1 的两轮口径。

判定：`PARTIAL 3/7`（上一棒，注点还在 accessor 上）⇒ R1 `PARTIAL 4/5` ⇒ **R2 交付跑 `RED-OK 4/4`**
（`LEDGER.tsv` TK3 行：`named_expected=4 / named_hit=4`、`tests_that_went_red` = 上面那 4 条、note 无"多红未点名"；
mvn 原文 `logs/mut_TK3.log`：`Tests run: 527, Failures: 4`；还原取证 `injection_landed=yes(disk!=git show)`、`restored=ok`）。
⇒ 这一条的红是**量具的账**，不是产品的问题：产品侧代际进缓存键这件事，R2 之后由 4 条有出处的用例判得住。

## 9.4 TK5 —— 等价变异的实测确认 + 换成不等价注点 + 用例补活猎物

**先把上一棒那句"这是等价变异，不是测试没猎物"拿实跑证实**（不照抄推理）。
量具：`_doc/acceptance/p20b/p20d_tk5_equiv_probe.py`（同一套 `flock(LOCK_EX|LOCK_NB)` 锁、
同一套 `git show` 还原取证、跑前 `ps` 等邻居的 maven 真身），三把：

```
V0 不注入（控制跑）
V1 上一棒原注点：if (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS || content.length() <= cap) {
                    → if (content.length() <= cap) {
V2 本棒注点：        → if (content.length() <= (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS ? 0L : cap)) {
```

读数（三把，10:53:14-10:55:06，控制台 `~/.cache/zbot-p17/p20d/probe_console.log`，mvn 原文 `logs/mut_p20d_tk5_{V0,V1,V2}.log`）：
```
-- V0  控制跑（不注入）
   rc=0 ran=527 红=无
   取证 before=4ae1a491cd73b0f1c1ac869db59723ad during=4ae1a491…(同) landed=NO(注入没进盘!) after=4ae1a491… git_show=4ae1a491… restored=ok
-- V1  上一棒原注点：摘掉析取项（推理上恒真 ⇒ 预期 0 条红）
   rc=0 ran=527 红=无
   取证 before=4ae1a491cd73b0f1c1ac869db59723ad during=3819a417c36b6c709628b200b7df5bc5 landed=yes(disk!=git show) after=4ae1a491… git_show=4ae1a491… restored=ok
-- V2  本棒注点：哨兵当成零上限（预期 unboundedSentinelMeansNoTruncationAtAll 红）
   rc=1 ran=527 红=unboundedSentinelMeansNoTruncationAtAll
   取证 before=4ae1a491cd73b0f1c1ac869db59723ad during=aaf853dbff45c9221ce2aa1029d3228e landed=yes(disk!=git show) after=4ae1a491… git_show=4ae1a491… restored=ok
== 收尾 SRC_MD5_STABLE=yes
```
三把各要点：
- **V1 全量 527 条 0 红 ⇒ "等价变异"这句话被实测证实**（不是照抄上一棒推理）：
  本棒已经把活猎物补进用例之后（V2 能红就是证明），摘掉析取项那一种写法**仍然一条都判不出来**。
  这不是"测试没猎物"，是"这个写法根本不改变行为" ⇒ 补测试救不了它，唯一正确的动作是换**不等价**的注点。
- **V2 的 `during` md5 `aaf853db…` 与 §9.0.1 留档的 `logs/LEDGER_subset_1022.tsv` TK5 行
  `disk_md5_injection` 逐字同** ⇒ 本棒探针注入的字节与第一个实例诊断跑的字节是同一份，两把尺对上了。
- 还原取证：V1/V2 两把 `landed=yes(disk!=git show)`、`after == git_show`、`restored=ok`，
  收尾 `SRC_MD5_STABLE=yes`；V0 那把 `landed=NO` 是**故意不注入**，那一格读作"没进盘"正是期望值。
⇒ 换成不等价的形式：把"哨兵 = 不设限"顶成"哨兵 = 零上限"，
   这一改只影响声明 UNBOUNDED 的那批结果，别的工具一律不动（窄注入，锚点次数仍 1、判定文本没动）。


**用例补活猎物（双向，同一条里两把钥匙）**：`ToolkitResultCapTest.unboundedSentinelMeansNoTruncationAtAll`
（`z-bot-core/src/test/java/com/zifang/z/bot/tool/ToolkitResultCapTest.java:86-115`）——
- 正向腿：同一个 `Toolkit`、全局上限 `setMaxResultChars(1_000L)`、`previewChars=200`，
  声明 `UNBOUNDED_RESULT_CHARS` 的 `reader` 吐 50_000 字符正文 ⇒ 必须**一字不改**原样回，
  且溢出目录**必须 0 个文件**；
- 反向腿（缺了它就是空跑）：同一份正文换 `ToolDescriptor.NO_MAX_RESULT_CHARS` 的 `reader_bounded` ⇒
  必须被截、回执第一行必须点名"超单工具上限 1000"、溢出目录必须**恰有 1 个文件**（全文真落过盘）；
- 两把唯一的差别就是那条声明：`tk.resultCapFor("reader")==UNBOUNDED` / `tk.resultCapFor("reader_bounded")==1000`。
⇒ 上一棒那条"原样返回"再也不是"正文本来就没超上限"蒙出来的。

判定：`GREEN-BUT-MUTATED 0/1` ⇒ **R1 取证跑 `RED-OK 1/1`**（`logs/LEDGER_R1.tsv` TK5 行：
`named_expected=1 / named_hit=1 / tests_that_went_red=unboundedSentinelMeansNoTruncationAtAll / restored=ok`；
mvn 原文 `r1_mut_TK5.log`：`Tests run: 527, Failures: 1`，红的就是这一条），R2 交付跑（11:34:53，`LEDGER.tsv` md5 `90dda63c…`）判同 **RED-OK**，点名全中且零多红；核对原文见 §9.9.6。
记账口径要留一句：**这条现在判的是"哨兵被当成零上限"这一种坏法**；
"摘掉析取项"那一种仍是等价变异、仍不可判（§9.10(1)）。

## 9.5 TK9 —— `$ZBOT_HOME` 那一级要有真猎物（起带 env 的子 JVM）

上一棒记的是结构问题：JUnit 进程改不了自己的 env，唯一相关用例自带 `if (getenv==null) 跳过`。
本棒不去改本进程 env，而是**起一个真带 `ZBOT_HOME` 的子 JVM**：

- 探针类 `z-bot-core/src/test/java/com/zifang/z/bot/tool/OverflowDirEnvProbe.java`（`main` 打八行
  `PROBE_*=值`，任何一行缺失即 `assertNotNull` 判红，`PROBE_FATAL` 存在即判红）；
- 用例 `ToolkitResultCapTest.zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot`
  fork 两把（`fork()` 用 `ProcessBuilder`，`java.home` 的真身 + CodeSource 拼 classpath，120 s 有界退出）：
  1. **正向**：`ZBOT_HOME=<tmp>/profile-root` ⇒ `PROBE_ZBOT_HOME_ENV` 必须等于那个根（否则三行断言全是空跑）、
     `PROBE_OVERFLOW_DIR=<root>/tool-results`、`PROBE_TRUNCATED=yes`、`PROBE_DIR_COUNT=1`、
     `PROBE_SPILLED_BYTES=4000`（正文 4_000 字符全文真在那个文件里）；
  2. **反向**：`pb.environment().remove(ZBOT_HOME)` ⇒ `PROBE_ZBOT_HOME_ENV=NULL`、
     `PROBE_OVERFLOW_DIR=NULL`（三级全落空就不落盘，宁可截断也不凭空造 `~/.zbot`）、
     回执含"未配置溢出目录"且含"全文未落盘"；
  3. **杠④ 的笼子**：两把子 JVM 都带 `-Duser.home=<假 home>`（`PROBE_USER_HOME` 必须等于它），
     所以万一解析被改回写死 `~/.zbot`，那个 `~` 只会指到临时假 home，当场判红；
     反向腿还钉 `assertFalse(new File(fakeHome, ".zbot").exists())` 与"假 home 目录 0 项"。
     ⇒ **真的 `~/.zbot` 一个字节都不许多**（三数见 §9.9.5）。

判定：`GREEN-BUT-MUTATED 0/0` ⇒ **R1 取证跑 `RED-OK 1/1`**（`logs/LEDGER_R1.tsv` TK9 行；
mvn 原文 `r1_mut_TK9.log`：`Tests run: 527, Failures: 1`，红的正是
`ToolkitResultCapTest.zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot`）。
R1 之后 `~/.zbot` 三数当场复量 = `8` / `2dadaed0` / `690ddbc0`（§9.0.2 那行 + §9.9.5 注入前那一格）⇒
子 JVM 那把假 home 笼子真的把溢出关在了临时根里。R2 交付跑（11:34:53，`LEDGER.tsv` md5 `90dda63c…`）判同 **RED-OK**，点名全中且零多红；核对原文见 §9.9.6。

## 9.6 MB2 —— 桥级"注册表有、桥没记住"的现场

上一棒的因果是：看着最像猎物的 `deregisterToolsetCleansNamesTheBridgeNoLongerKnowsAbout` 是**注册表层**
用例（裸 `tk.register`/`tk.deregisterToolset`，不经过 `McpBridge`），而桥级用例里 `registered` 与注册表永远一致。
本棒就把那个分岔真造出来（`McpBridgeDeregisterTest.unregisterAllCleansSlotsTheBridgeNeverRecorded`）：

- **现场一**：同一个 server 名 `twice` 的**第二个桥实例**（reload/重连真会造出来），
  toolset 与 owner 与第一个桥**逐字相同**（用例里 `assertEquals(remembered.toolset()+"/"+remembered.owner(), …)` 先把这句钉住，
  不然这根本不是同一个命名空间）；桥 `second` 的 `registered` 只记着 `mcp-twice-two`；
- **现场二**：上一轮注册留下、这一轮 server 不再发的**僵尸槽** `mcp-twice-legacy`
  （同一个 `mcp-twice` / `mcp:twice`，桥的记忆里永远没有它）；
- **笼子**：第三台全程没被动过的 `bystander` server，防"顺手清空整张注册表"这种修法蒙过去。

活的猎物**先点名**（这两组不成立的话后面的反向断言全是空跑）：
`second.registeredNames()` 恰为 `[mcp-twice-two]`、不含 `one`/`legacy`；
`tk.namesOfToolset("mcp-twice")` 注销前恰为三个名字。
结论必须来自注册表：`removed == [mcp-twice-one, mcp-twice-two, mcp-twice-legacy]`、
`namesOfToolset` 空、`getToolNames()` 与外发清单都不含 `legacy`、`bystander` 完好（`tk.size()==1`）、
注销后 `second.registeredNames()` 也清空。

判定：`GREEN-BUT-MUTATED 0/0` ⇒ **R1 取证跑 `RED-OK 1/1`**（`logs/LEDGER_R1.tsv` MB2 行；
mvn 原文 `r1_mut_MB2.log`：`Tests run: 527, Failures: 1`，红的正是
`McpBridgeDeregisterTest.unregisterAllCleansSlotsTheBridgeNeverRecorded`，且**只有**这一条红 ⇒
"拿记住的名字当结论"这一刀被这一条真判红，没有靠别的用例蒙）。R2 交付跑（11:34:53，`LEDGER.tsv` md5 `90dda63c…`）判同 **RED-OK**，点名全中且零多红；核对原文见 §9.9.6。

## 9.7 MB3 —— 保留 PARTIAL 的因果（工单明写"不硬凑"）

注点没动：`deregisterAll()` 里的 `toolkit.deregisterToolset(toolset(), owner())` 换回 P20 要杀的旧实现
（逐个 `deregister(name, owner())` + 留同名 stub 覆盖）。

- 与 `unregisteringIsNotStubOverwrite` 的关系（工单点名的"反向断言在修之前必须先红一次"）：
  这一把注回去之后，"不许留桩"的正面判据必须红 —— 读数是 R1/R2 的 `MB3 …` 行里点名了
  `unregisteringIsNotStubOverwrite`、`oldServerToolNamesAreGoneAfterUnregisterInsideTheSameJvm`、
  `unregisterLeavesNoPlaceholderBehindInAnyListView` 三条（R1 台账逐条对过：三条都在 `tests_that_went_red` 里）。
  这三条红**恰恰证明现在的产品码真在判这件事**（不是"用例名字像"）。R1 台账 MB3 行
  `tests_that_went_red` 列逐字含这三条（另 5 条也是点名的），mvn 原文 `r1_mut_MB3.log`：`Tests run: 527, Failures: 11`
  ⇒ 8 条点名 + 3 条多红未点名 = 11，与台账自洽（R2 交付跑对核一次，读数见 §9.9.6）。
- 判定仍是 `PARTIAL`：`named_expected==named_hit` 之外另有"红了但没点名"的差集。
  本棒**故意不回填 MB3 的期望集** —— 工单对这一行的指令是"保留 PARTIAL、把因果写明、不硬凑"。
  这与 TK11/TK4/TK7 的机械补集是两种处理，**差别来自工单指令，不是来自读数**；
  哪些用例额外红了，R2 台账的 note 列原样挂着（`| 多红未点名: …`）。
- R2 交付跑复核（原文）：`MB3 … PARTIAL 点名=8/8`、`tests_that_went_red` 11 条 =
  8 条点名（含 `unregisteringIsNotStubOverwrite`、`oldServerToolNamesAreGoneAfterUnregisterInsideTheSameJvm`、
  `unregisterLeavesNoPlaceholderBehindInAnyListView`）+ 3 条未点名（`bridgeOnlyNukesItsOwnToolset`、
  `serverNamesWithDashesGetUnambiguousToolsets`、`unregisterAllCleansSlotsTheBridgeNeverRecorded`）；
  mvn 原文 `logs/mut_MB3.log`：`Tests run: 527, Failures: 11`；差集复算 `ROWS_WITH_DELTA=1`（全表只剩这一行有差集，§9.9.6）。
  ⇒ 记账口径不变：**这 3 条多红本棒知道是哪三条、为什么红（都是"留桩占名额/占清单"这一族），但按工单不回填。**

## 9.8 TK11 —— 差集机械补进期望集

TK11 的期望集从 13 条补到 17 条，**每一条补进来的都来自实跑读数**，逐条注明是哪一跑钉的：

| 补进期望集的用例 | 是哪一跑钉的（读数） |
| --- | --- |
| `bridgeRegistersAnAvailabilityProbeBackedByTheConnection` | 上一棒 p20b 交付态全量跑 `84ca7b9`，`LEDGER.tsv` TK11 行 note 的 `多红未点名:` 段（§2.2(6)） |
| `reloadDropsTheDeadServersToolNamesFromGetToolNames` | 同上 |
| `repavingWithTheSameToolNameWorksAfterUnregister` | 同上 |
| `unregisterAllCleansSlotsTheBridgeNeverRecorded` | 本棒诊断跑 10:2x（`logs/mut_TK11.log`，留档 `logs/LEDGER_subset_1022.tsv` TK11 行）——本棒新写的桥级用例，owner 被顶成内建 owner 之后整组注销直接抛"不能注销" |

三条上一棒的差集在 `p20b_mutation.py:240-246`（现字节）就地注明了出处（"上一棒 84ca7b9 那一跑的实跑差集"）。
判定：`PARTIAL 13/13` ⇒ **R1 取证跑 `RED-OK 17/17`**（`logs/LEDGER_R1.tsv` TK11 行，`named_expected=17 / named_hit=17`、
note 里已无"多红未点名"；mvn 原文 `r1_mut_TK11.log`：`Tests run: 527, Failures: 5, Errors: 12` ⇒ 5+12=17，
与台账 17 条红逐一对上，`Errors` 是 owner 校验抛的 `IllegalStateException` 而不是断言失败，判定按具名 testcase 取，
不按 mvn 退出码取）。R2 交付跑（11:34:53，`LEDGER.tsv` md5 `90dda63c…`）判同 **RED-OK**，点名全中且零多红；核对原文见 §9.9.6。


## 9.9 收尾重出（杠② 全量 21 个 / 杠① 串行三跑 / 杠③ 全量三阶段 / 杠④ 三数）

### 9.9.1 两轮跑的口径（为什么有两轮，以及各自能拿来当什么证据）

| | R1 取证跑 | R2 交付跑 |
| --- | --- | --- |
| 期望集是哪一份 | `cde236b` 冻结的那一份（本棒第一笔把量具连同四条补网一起入库，**先提交再跑**） | R1 之后按 §9.9.2 补过差集的那一份 |
| 用来证什么 | 七条非 RED-OK 的现状 + "红了但没点名"的**差集取证** | 交付台账（`LEDGER.tsv` 21 行 + tally），本文所有"本棒后"的数都取自它 |
| 台账留档 | `logs/LEDGER_R1.tsv` / `logs/LEDGER_RESTORE_R1.tsv`（脚本产物原样复制） | `_doc/acceptance/p20b/LEDGER.tsv` / `LEDGER_RESTORE.tsv`（覆盖式重生成，历史账在 §2 与本节） |

纪律核对：
- **期望集先写死再跑**：R1 用的期望集在 `cde236b` 就进库了；R1→R2 之间只允许两类改动
  （① TK11/TK4/TK7 的差集**机械补集**，每条来自 R1 台账 `tests_that_went_red` 与 `MUTANTS` 的差；
  ② TK3 摘掉一条没出处的期望），**判定文本、判定逻辑（`p20b_mutation.py:582-601`）、注点语义、测试字节都没动**。
- **测试字节在 R1 与 R2 之间一字未改**（本棒第三实例 R2 之前现场量过，原文）：
  `git diff --name-only 3ffe03f..HEAD -- z-bot-core/src | wc -l` ⇒ **`0`**
  ⇒ R1 与 R2 之间只有 `_doc/**`（量具期望集 + 本文），`src/test` 与 `src/main` 字节 = `3ffe03f`。
  诚实记一笔：简报与 §9.0 用的 `'*/src/*'` 这个 pathspec 在本仓**匹配不到东西**（当场实测返回空，
  连 `Toolsets.java` 那处授权注释都没报出来）⇒ 本棒改成 `z-bot-core/src` 这种可直接匹配的形式，
  ⇒ 按 §6 的"量具自己坏过的记录"口径，这条账记在**本节**（§0–§8 的历史账本棒一字未改）。
- 中途 kill 过一次探针（本棒自己杀的，10:4x）：杀在 `wait_for_quiet` 阶段、**写盘之前**，
  事后四个被测源文件 md5 与 `git show HEAD:` 逐字节同（读数见 §9.0.1 最后一行）⇒ §6(6) 那个坑没重犯。

### 9.9.2 期望集的机械补集清单（每条注明是哪一跑钉的，不猜）

| 行 | 动作 | 补/摘的用例 | 是哪一跑钉的 |
| --- | --- | --- | --- |
| TK3 | 摘（收窄） | `deregisterActuallyRemovesTheSlotFromEveryView` | R1 `TK3 … 点名=4/5`；出处见 §9.3 的三行源码 |
| TK11 | 补 | `bridgeRegistersAnAvailabilityProbeBackedByTheConnection`、`reloadDropsTheDeadServersToolNamesFromGetToolNames`、`repavingWithTheSameToolNameWorksAfterUnregister` | 上一棒 p20b 交付态全量跑 `84ca7b9`（§2.2(6) 的差集） |
| TK11 | 补 | `unregisterAllCleansSlotsTheBridgeNeverRecorded` | 第一个实例 10:2x 子集诊断跑（`logs/LEDGER_subset_1022.tsv` TK11 行）+ R1 复核 |
| TK4 | 补 | `unboundedSentinelMeansNoTruncationAtAll` | R1 台账 TK4 行 note 原文：`… \| 多红未点名: unboundedSentinelMeansNoTruncationAtAll`（`r1_mut_TK4.log`：`Tests run: 527, Failures: 2` = 点名的 `perToolDeclarationBeatsTheGlobalDefault` + 这一条） |
| TK7 | 补 | `unboundedSentinelMeansNoTruncationAtAll`、`zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot` | R1 台账 TK7 行 note 原文：`… \| 多红未点名: unboundedSentinelMeansNoTruncationAtAll,zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot`（`r1_mut_TK7.log`：`Tests run: 527, Failures: 5` = 3 条点名 + 这 2 条） |
| MB3 | **不补** | —（工单明写保留 PARTIAL，见 §9.7） | R1 台账 MB3 行 note 原文：`… \| 多红未点名: bridgeOnlyNukesItsOwnToolset,serverNamesWithDashesGetUnambiguousToolsets,unregisterAllCleansSlotsTheBridgeNeverRecorded` —— 差集**原样挂在台账上**，本棒一个名字都没往期望里搬 |
| 其余 13 行 | 不补（无需补） | —（R1 全量 21 行里只有 TK4/TK7/MB3 三行 note 含"多红未点名"，机器数出来的：`awk -F'\t' 'NR>1 && $6 ~ /多红未点名/ {print $1, $2}' logs/LEDGER_R1.tsv` ⇒ 恰好这三行） | R1 |

补完之后 R2 的**期望集字节**在 `1c1737d`（先写死入库，再跑）；R2 用的 `MUTANTS` 与磁盘上那份逐字节同，
由脚本自己的"锚点次数 + 盘上原文 == git show + 无漂移"预检保证（原文读数在 §9.0.2 的"锁与邻居"那一行）。

### 9.9.3 杠① —— `mvn -o test` 串行三跑（本棒自跑，不引用别人那一跑）

命令（本树，串行，不起并发 mvn；原文 `_doc/acceptance/p20b/logs/p20d_gate1_run{1,2,3}.log`）：
```
rm -rf z-bot-core/target/surefire-reports && mvn -o test
```
控制台原文（`~/.cache/zbot-p17/p20d/gate_console.log`，本棒第三实例）：
```
===== GATE1 串行三跑 start 2026-09-26 11:23:05 =====
-- run1 start 2026-09-26 11:23:05
-- run1 rc=0 end 2026-09-26 11:23:28
[INFO] Tests run: 527, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
-- run2 start 2026-09-26 11:23:28
-- run2 rc=0 end 2026-09-26 11:23:45
[INFO] Tests run: 527, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
-- run3 start 2026-09-26 11:23:45
-- run3 rc=0 end 2026-09-26 11:24:01
[INFO] Tests run: 527, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
== @Test 求和（HEAD / 基线 13027f4）
HEAD=527
BASE13027f4=465
```
- 三跑都是 **527**（上一棒 525 + 本棒两条：`zbotHomeEnvLevelResolvesTheSpillDirInsideTheProfileRoot`、
  `unregisterAllCleansSlotsTheBridgeNeverRecorded`）；`@Test` 求和同为 **527**，基线 `13027f4` 465 ⇒ 与 §1 同一口径。
- 这三跑跑在 R2 交付跑的**同一份字节**上（HEAD `1c1737d`；`1c1737d` 只动 `_doc`，`src/test` 字节 = `3ffe03f`）：
  `git diff --name-only 3ffe03f..HEAD -- z-bot-core/src` ⇒ 空（原文读数见 §9.9.1 最后一行）。
  ⇒ 杠① 的绿与杠② 的台账是同一批测试字节，不是两次不同的心跳。
- 产品行为对拍（本棒重量的路径，§9.0 那条 `*/src/*` pathspec 实测匹配不到东西，改用可匹配的形式）：
  `git diff --name-only c3ab4da HEAD -- z-bot-core/src` ⇒
  `z-bot-core/src/main/java/com/zifang/z/bot/tool/Toolsets.java`（唯一一处 `src/main` 改动 = 授权那一行注释）+
  四个 `src/test` 文件；`git diff c3ab4da HEAD -- .../Toolsets.java` = `1 file changed, 1 insertion(+), 1 deletion(-)`，
  且那一行是 `{@code tools/toolsets.py}` ⇒ `{@code toolsets.py}（她的仓根，不是 {@code tools/} 下）`，
  实测 hermes 仓根 `toolsets.py` 存在、`tools/toolsets.py` 不存在 ⇒ **产品行为一字未动**。


### 9.9.4 杠③ —— 真进程三阶段全量重跑一次（动了测试与一行注释 ⇒ 结论要在新字节上重出）

命令与位置（本树、`ZBOT_HOME` 指向临时 profile，真 `~/.zbot` 只读数不写）：
```
$ cd /private/tmp/zbot-wt-p20b && python3 -u _doc/acceptance/p20b/p20b_e2e.py   # 三阶段一次跑完
控制台：start 2026-09-26 11:34:53 → rc=0 end 2026-09-26 11:36:00（`~/.cache/zbot-p17/p20d/gate_console.log`）
原文日志：`_doc/acceptance/p20b/logs/p20d_e2e_full.log`
```
汇总行原文：
```
== ~/.zbot 跑前: 8 项 {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}
== 阶段 toolsets (home=/var/folders/…/zbot-p20b-e2e-tf627czi)
  P0 toolsets 阶段驱动退出码 0                                      PASS rc=0 TRACE	PHASE_RETURNED=toolsets +30ms
== 阶段 cap (home=…tf627czi)
  P0 cap 阶段驱动退出码 0                                           PASS rc=0 TRACE	PHASE_RETURNED=cap +50ms
== 阶段 mcp (home=…tf627czi)
  P0 mcp 阶段驱动退出码 0                                           PASS rc=0 TRACE	PHASE_RETURNED=mcp +60620ms
== ~/.zbot 跑后: 8 项 {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}
== 合计 38 条判定，PASS=38 FAIL=0
```
⇒ 三阶段 **38/38 PASS、0 FAIL**，跑在 HEAD `1c13c0e`/`1c1737d` 之后的同一批字节上（`src` 字节 = `3ffe03f`，§9.9.1 那条 `0` 读数）。

M4/M5 那两条（§3.4 已裁决完毕，**结论一字不改**，这里只重出"在新字节上仍然 PASS"的读数）：
```
  M4 首探的出处可指：取一次 schema ⇒ 该桥每个槽位探一次（alpha 发了 2 个工具就是 2 次），TTL 窗内再取一次一条都不许多（真时间） PASS alpha_tools=2 anchor=2 second_read=2 samples=[(58, True, 2), (1565, True, 2)]
  M5 越过 TTL 后每轮都重探（失败不写缓存，斜率恰为该桥槽位数）、但宽限窗内一条都不摘（真时间）         PASS 30-60s 样本=20 条 probes=[4, 6, 8, 10, 12, 14] 每轮增量=[2] exposed={True}
```
与 §3.3/§3.4 的读数量纲一致（`anchor=2` = 该桥槽位数；M5 每轮增量恰为槽位数 2）。

凭证红线三条（工单点名"负向断言要反向钉住 stub key 真进了产物"）原文：
```
  K0 尺的自证：125 字符合成诱饵被同一条正则抓到                                 PASS 抓到 1 个文件
  K1 产物/临时 profile 里没有任何 60+ 字符 api.key（真 key 125 字符）泄漏      PASS
  K2 同一条反向钉住活的猎物：stub key 就是 config.properties 里 minimax.api.key 的值（该文件确实在扫描面里，长度只报不印） PASS 临时 profile 命中 1 次/1 个 key 行，长度=17，产物侧 10 次/1 个 key 行
```
⇒ 真 key 一个字节都没读/没印/没复制；E2E 全程 `ZBOT_HOME=<临时目录>`。

### 9.9.5 杠④ —— `~/.zbot` 三数（跑后）

命令：`ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties | cut -c1-8 ; md5 -q ~/.zbot/state.db | cut -c1-8`
```
杠① 三跑之后 / 注入之前（11:24:01）：8 / 2dadaed0 / 690ddbc0
杠② R2 全量 21 个注入跑完之后        ：（脚本内 SRC_MD5_STABLE=yes，四个被测源文件与 git show 逐字节同）
杠③ 真进程三阶段之后（11:36:00）    ：8 / 2dadaed0 / 690ddbc0
```
⇒ 与 §0/§4/§9.0/§9.0.2 逐字同 ⇒ **本期四个实例加起来跑了 ~50 次注入 + 3 次真进程 E2E，真 `~/.zbot` 一个字节都没多**。

### 9.9.6 杠② tally 并排 + 台账自洽核对

**并排（原账在前，本棒账在后，都不覆盖）**：
```
本棒前（§2 交付态那一跑，HEAD 84ca7b9 的台账）：
  RED-OK 14 / PARTIAL 4 / GREEN-BUT-MUTATED 3 / BROKEN 0     （21 个变异体）
R1 取证跑（11:08:50，期望集 = cde236b 那份；logs/LEDGER_R1.tsv，md5 7258d957555c642b9a93b2194b2f60da）：
      RED-OK             17
      PARTIAL            4
      GREEN-BUT-MUTATED  0
      BROKEN             0
R2 交付跑（11:34:53，期望集 = 1c1737d 那份；LEDGER.tsv md5 90dda63ce5c8ae2ea6d9ca572602d779，
                 同字节留档 logs/LEDGER_R2.tsv）：
      RED-OK             20
      PARTIAL            1
      GREEN-BUT-MUTATED  0
      BROKEN             0
```
七条非 RED-OK 的落点：**TS4/TK3/TK5/TK9/TK11/MB2 六条进 RED-OK，只剩 MB3 一条 PARTIAL**
—— 而 MB3 这一条是工单明写"保留 PARTIAL、不硬凑"的那一条（§9.7），它的 8 条点名全红、0 条点名没红。

自洽核对（都是机器算的，命令见 §9.11）：
```
$ cut -f2 LEDGER.tsv | tail -n +2 | sort | uniq -c
   1 PARTIAL
  20 RED-OK
$ awk 'END{print NR-1}' LEDGER.tsv            ⇒ 21 行
$ awk 'END{print NR-1}' LEDGER_RESTORE.tsv    ⇒ 21 行
$ cut -f5 LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c   ⇒ 21 yes(disk!=git show)   （21 把注入全部真进盘，没有 no-op）
$ cut -f7 LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c   ⇒ 21 ok                     （21 次还原后与 git show 逐字节同）
控制台尾：SRC_MD5_STABLE=yes / rc=0（`[r2 attempt 1] rc=0 end 2026-09-26 11:34:53`）
差集复算（LEDGER.tsv 的 tests_that_went_red 与 p20b_mutation.py 的 MUTANTS 期望集做集合差）：
  MB3 PARTIAL 未点名红= bridgeOnlyNukesItsOwnToolset,serverNamesWithDashesGetUnambiguousToolsets,unregisterAllCleansSlotsTheBridgeNeverRecorded | 点名没红= -
  ROWS_WITH_DELTA=1
```
⇒ **除 MB3 之外，20 行都是"点名的全红 + 一个都没多红"**；TK3 收窄之后 4/4 全红、TK4 2/2、TK7 5/5 全对上。
还原取证列（`restored`）21 行全 `ok` ⇒ §2.3 那笔"标签反了"的账没在本期重犯。


## 9.10 本棒仍未覆盖的（别当成做了）

1. **TK5 只换了"能判的那一种坏法"**：`摘掉 `cap == UNBOUNDED ||` 这个析取项` 仍是**等价变异**
   （V1 那把的读数），单测层永远判不出来 ⇒ 想让它可判要么改产品语义、要么把它当"不可判"记账；
   本棒按后者记（§9.4）。
2. **MB3 仍是 `PARTIAL`**：工单明写不硬凑，本棒连它的期望集都没补（§9.7）。⇒ 账在台账 note 里挂着。
3. **注入 × 真进程这个乘积本期仍然没有**（§7(4)）：本棒没把变异体注入到 `p20b_e2e.py` 的跑里，
   两边仍是两把尺。
4. **杠② 的 21 个变异体仍然只打到单测层**：`check_fn` 的真时间（TTL 30 s / 宽限 60 s）依旧只有杠③ M4-M7 兜着（§2.5）。
5. **`McpBridge.registerAll:65` 的无超时 `connect()` 本棒没做**（主编已定"本棒不做，要另立一期"）。
6. **TK9 的补网是子 JVM 探针，不是进程内注入**：`-Duser.home=<假 home>` 只保证"改回写死 `~/.zbot`"会被判红，
   它不覆盖"第三级解析被改成读别的 env 名"这类形状（那种注点本棒没试）。
7. **MB2 的新用例是替身级**（`InMemoryMcpTransport`）：真进程侧仍只有 §3.3 的 M11/M12。
8. **本棒没集成、没合并、没 push**；`w2-p20b` 停在本地，`main` 一字未动。
9. **`ToolsetsManifestTest.java:96-99` 的期望仍取 `Toolsets.CORE/EXEC/FILE/NET` 四个常量，没换成字面量**（§9.2.1 表第二行）：
   本棒**没动**它，两个理由 —— ① 它不是 §2.2 那七条之一；② R1 与 R2 必须打在**同一批测试字节**上（§9.9.1 的口径），
   再往 `src/test` 加一个字节就得再跑第三轮全量台账。风险实测低：常量真漂了会被 `:132-136` 那张字面量表判红。
   交主编定：下一棒若动测试字节，就顺手把这四个常量写成 `"core","exec","file","net"`。
10. **第三实例没重跑 TK5 的 V0/V1/V2 等价变异探针**：那三把是第二实例 10:53:14-10:55:06 的实测（§9.4 原文读数），
    本棒核的是它的**结论落进了台账**（R1 `TK5 RED-OK 1/1`）与探针脚本仍在库（`p20d_tk5_equiv_probe.py`，复算命令 §9.11）。

## 9.11 本棒复算命令

```
# 起手对拍（§9.0.1）
git rev-parse --short HEAD && git status --porcelain
python3 -c "import sys;sys.path.insert(0,'_doc/acceptance/p20b');import p20b_mutation as m;\
for k,rel in sorted(m.SRC.items()):print(k,rel,m.disk_md5(rel)==m.git_md5('HEAD',rel))"

# TK5 等价变异取证（§9.4）
P20B_MVN_WAIT=420 python3 -u _doc/acceptance/p20b/p20d_tk5_equiv_probe.py          # V0/V1/V2 三把
P20B_MVN_WAIT=420 python3 -u _doc/acceptance/p20b/p20d_tk5_equiv_probe.py --only=V1

# 杠② R1（取证跑，期望集 = cde236b 那份）与 R2（交付跑）
P20B_MVN_WAIT=420 python3 -u _doc/acceptance/p20b/p20b_mutation.py                  # R1：console ~/.cache/zbot-p17/p20d/r1_console.log
P20B_MVN_WAIT=600 python3 -u _doc/acceptance/p20b/p20b_mutation.py                  # R2：同一份命令，期望集 = 1c1737d 那份；console …/r2_console.log
cut -f2 _doc/acceptance/p20b/LEDGER.tsv        | tail -n +2 | sort | uniq -c
cut -f5 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c
cut -f7 _doc/acceptance/p20b/LEDGER_RESTORE.tsv | tail -n +2 | sort | uniq -c
# 台账里"哪几行挂着多红未点名"（§9.9.2 的那三行就是这么数出来的，不是读出来的）
awk -F'\t' 'NR>1 && $6 ~ /多红未点名/ {print $1, $2}' _doc/acceptance/p20b/logs/LEDGER_R1.tsv
# 逐行点名/命中对照（§9.1 表里 R2 那一栏的取数命令）
awk -F'\t' 'NR>1 {print $1" | "$2" | "$4"/"$3}' _doc/acceptance/p20b/LEDGER.tsv

# 杠①/杠③/杠④ 一条链串行跑完（本棒第三实例用的就是这份，无并发 mvn）：
bash ~/.cache/zbot-p17/p20d/chain3.sh    # 控制台 …/gate_console.log（杠①③④）与 …/r2_console.log（杠②）

# 差集复算（哪一行了红、哪一条没被点名 —— 机器算，不是我读印象）
python3 - <<'PY'
import io,csv,ast,re
src=io.open('_doc/acceptance/p20b/p20b_mutation.py',encoding='utf-8').read()
mut=ast.literal_eval(re.search(r'MUTANTS\s*=\s*\[.*?\n\]',src,re.S).group(0).split('=',1)[1].strip())
exp={m[0].split()[0]:set(m[5]) for m in mut}
for r in list(csv.reader(io.open('_doc/acceptance/p20b/LEDGER.tsv',encoding='utf-8'),delimiter='\t'))[1:]:
    if not r: continue
    red=set(x for x in r[4].split(',') if x)
    print(r[0], r[1], '未点名红=', ','.join(sorted(red-exp[r[0].split()[0]])) or '-', '| 点名没红=', ','.join(sorted(exp[r[0].split()[0]]-red)) or '-')
PY

# 杠①（串行三跑）
rm -rf z-bot-core/target/surefire-reports && mvn -o test          # ×3，原文进 logs/p20d_gate1_run{1,2,3}.log

# 杠③ / 杠④
python3 -u _doc/acceptance/p20b/p20b_e2e.py
ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties | cut -c1-8 ; md5 -q ~/.zbot/state.db | cut -c1-8
```


