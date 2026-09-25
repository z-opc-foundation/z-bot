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


## 2. 杠② —— 注入自证（p20b_mutation.py → LEDGER.tsv）

TODO-2

### 2.1 逐条的账

TODO-2.1

### 2.2 非 RED-OK 的那几条：多红/少红是什么、为什么如实记（不改写成 RED-OK）

TODO-2.2

### 2.3 测试替身还原的独立取证

TODO-2.3

### 2.4 锁的双向实测

TODO-2.4

### 2.5 未覆盖的守卫（注入不出的，说明为什么不可观测）

TODO-2.5

## 3. 杠③ —— 真进程 E2E（三阶段全量 + M4/M5 裁决）

TODO-3

### 3.1 toolsets 阶段

TODO-3.1

### 3.2 probe 阶段

TODO-3.2

### 3.3 cap 阶段

TODO-3.3

### 3.4 mcp 阶段与 M4/M5 两条红的裁决（改前读数 / 改后读数 / 结论）

TODO-3.4

## 4. 杠④ —— `~/.zbot` 红线（本写手亲测）

TODO-4

## 5. 本期发现的产品级缺陷（未修，记账）

TODO-5

## 6. 量具自己坏过的记录

TODO-6

## 7. 本期没做的（别当成做了）

TODO-7

## 8. 复算命令清单

TODO-8
