# release-021 EVIDENCE —— 四杠跑在**发布字节**上（2026-10-01 20:4x）

这笔不是新功能，是**把证据钉到要发布的那份字节上**。19:1x–19:4x 那批读数（`recheck-0930` 之后抬号那一轮）
里有三支跑在**提交之前的工作树**上；事后对账证明差的全是 XML 注释，但对账不等于重测，
所以本轮在 `git archive b3e32a2` 解出的副本上把杠①②③④ 各重跑一遍。

一句话结论：**`b3e32a2`（`<revision>0.2.1</revision>`）这份字节上，四杠全部成立**；
对外可解析的仍是 0.2.0 —— `deploy -Pcentral` 没跑（等单独点头）。

---

## §0 被量的到底是哪份字节

| 项 | 读数 | 现读命令 |
|---|---|---|
| 本机 `HEAD` = `origin/main` | 都是 `b3e32a2a37d0d4bb7c66a8d026af97b86b10489e`，`ahead 0` | `git rev-parse HEAD` / `git ls-remote origin refs/heads/main` |
| 版本面值 | `<revision>0.2.1</revision>` | `grep -m1 '<revision>' pom.xml` |
| 被跟踪文件数 | 469 | `git ls-tree -r -z --name-only b3e32a2 \| python3 -c "import sys;print(len(sys.stdin.buffer.read().split(b'\x00'))-1)"` |

（这条命令的形状有来头：`-z` 下文件名以 NUL 分隔、没有换行，接 `tr -d '\0' | wc -l` 实测印 **0** ——
"469 个文件"会被读成"一个都没有"。加 `-z` 是因为不带它时中文路径会被引号转义（本仓历史雷）。）
| 副本（本机） | `~/builds/zbot_021_mut`，`git archive --format=tar b3e32a2 \| tar -x`；**跑完四杠之后**再对账仍是 `清单条数=469 逐条相等=469 不等=0 缺失=0` rc=0 | `python3 ~/.cache/zbot_release_1001/tree_manifest_check.py ~/.cache/zbot_release_1001/zbot_b3e32a2.manifest ~/builds/zbot_021_mut` |
| 副本（250） | `~/builds/rel1001/zbot_b3e32a2`：`host=zifang001 清单条数=469 逐条相等=469 不等=0 缺失=0`（20:47 复跑，不是 19:50 那次的旧账） | `ssh 250 'cd ~/builds/rel1001 && python3 tree_manifest_check.py zbot_b3e32a2.manifest zbot_b3e32a2'` |
| 清单自身字节 | 本机现生成与 250 上那份 md5 相同：`bfc14b1c65050b0f460acee877cb014a`（470 行 = 1 行来历注释 + 469 条） | `md5 -q` / `md5sum` |

清单尺 `tree_manifest.py` + `tree_manifest_check.py` 的牙：改一字节→报 DIFF 且 rc=1；删一文件→报 MISS；
空清单→FATAL（不印"全部一致"）；原样副本→rc=0。

## §1 杠① + 杠④：全量测试跑在副本上，且 `~/.zbot` 一字未动

命令（副本目录，离线，带上 MCP SDK）：

```
cd ~/builds/zbot_021_mut && P21_PYTHON=~/.cache/zbot_release_1001/mcpvenv/bin/python \
  python3 ~/.cache/zbot_release_1001/bar4_mac.py -- mvn -o clean test
```

读数（日志 `~/.cache/zbot_release_1001/bar14_on_release_tree/bar14.log`）：

* `Tests run: 1240, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`，`suite rc=0`
* `Building z-bot (Local Agent App, cc/Hermes style) 0.2.1 [1/3]` / `Building z-bot-core 0.2.1 [2/3]`
  —— 版本面值出现在日志里，所以这跑量的不是 0.2.0 那份旧字节
* 端口类命中（`BindException|Address already in use`）**0** 条
* 4 条 `P21-READ` 腿，`serverInfoVersion=1.27.1`（官方 MCP Python SDK）、`serverProtocolVersion=2025-06-18`
* `[bar4] 杠④ PASS: /Users/zifang/.zbot 27 项逐字节未变（mtime/size/md5 三列全等）`，`after: 27 个文件`

判据复判（把已落盘日志喂给带牙的 judger，不再碰 mvn）：

```
python3 ~/.cache/zbot_release_1001/zscript_gate.py --reparse <日志> --min-test-modules 1 --expected 1240
→ z-bot-core: run=1240 fail=0 err=0 skip=0 | BUILD SUCCESS | 签名 8f7dfceb60dfc2fe | EXPECTED=1240 命中 | reparse PASS
```

`--min-test-modules 1` **不是可选项**：那支尺的默认下限是 2（z-script 有两个含测试模块），
直接套到 z-bot 上会印 `RED: 含测试模块 1 个 < 下限 2` ×3 —— 我第一遍就踩了这个，
读数明明是 1240/0/0/0 却被判红。跨仓复用判据时，下限要按被量对象的模块数现读。

## §2 杠②：三支具名变异打在副本上（尺的旧 verdict 是坏的，这里用改好的）

```
python3 ~/.cache/zbot_release_1001/mutate_drift_copy.py ~/builds/zbot_021_mut
→ ~/.cache/zbot_release_1001/bar2_on_release_tree/mutation_on_release_tree.log
```

| 支 | 变异 | 读数 | 具名红 |
|---|---|---|---|
| — | 变异前 | rc=0，`Tests run: 3, F=0, E=0, S=0` | 无（阴性对照） |
| M1 | `BuildInfo.REVISION` 0.2.1→0.2.0（pom 不动） | rc=1，3/1/0/0 | `revisionInPomAndSourceAreTheSameNumber` |
| M2 | `ZBotStdioMcpTransport` 里 `clientVersion` 退回字面量 `"0.2.0"` | rc=1，3/1/0/0 | `noStaleVersionLiteralOutsideBuildInfo` |
| M3 | 根 pom `<revision>` 0.2.1→0.9.9（源码不动） | rc=1，3/1/0/0 | `revisionInPomAndSourceAreTheSameNumber` |
| — | 还原对账 + 复跑 | 三文件 md5 与基线相同；`Tests run: 3, F=0, E=0, S=0` | 无 |

判定 = `PASS(红在该红的格上)` ×3，脚本末尾 `== 杠② 复跑判定: PASS ==`。

两处不是美化的改动：

1. 19:18 那版 `mutate_drift.py` 的 verdict 拿**裸方法名**去比**全名**列表，所以三支明明红对了也各印 `FAIL`
   —— 旧日志里那三行 `FAIL` 是尺的 bug，不是结果的 bug（旧日志尾部我贴过"判定重算"块，就是这个原因）。
   新脚本两侧同口径，并把"没红 / 红了但没点名 / 红错了格"分成三种发言。
2. 副本字节对不上发布树就 `FATAL` 直接拒跑（`expected_bytes` 三条硬写），而不是"差不多就跑"。
   本轮实读：pom `832fdb68…`、BuildInfo `7a3bacb0…`、Transport `73c74eda…` 三条全等才继续。

## §3 杠③：官方 MCP Python SDK 那 9 条也打在副本上

```
cd ~/builds/zbot_021_mut && P21_PYTHON=~/.cache/zbot_release_1001/mcpvenv/bin/python \
  mvn -o -pl z-bot-core test -Dtest='McpRealStdioServerTest,McpParentWatchdogTest' -DfailIfNoTests=true
→ ~/.cache/zbot_release_1001/bar3_on_release_tree/bar3_rerun.log
```

`McpRealStdioServerTest` 5 + `McpParentWatchdogTest` 4 = `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，
`BUILD SUCCESS`；`P21-READ` 腿 4 条（`1.1-kernel-vs-official-compact-json` / `a-handshake` / `c-swap` /
`c-unregisterAll`），SDK 现读 `mcp 1.27.1`（`mcpvenv/bin/python -> python3.13`）。
协议要求 E2E ≥3，这里是 4 条腿 + 9 条测试，且这 9 条同样包含在 §1 那次全量里跑过。

`P21_PYTHON` 是硬前提：`RealMcpHarness.python()` 在没有该环境变量时退回系统 `python3`，
而系统 python 没装 `mcp` ⇒ 那 9 条会**红**而不是跳过（这是好事：漏设环境变量不会糊出满分）。

## §4 这批与 19:1x–19:4x 那批的关系（为什么非重跑不可）

机械对账（先算清楚"差的是什么"，再决定要不要重跑）：

* 19:18 变异基线里的 `pom.xml` 是 `77d675bf8fae938954751343fde59259`，提交树是 `832fdb681ffd91bf057ee650f61ab081` —— **不同**。
* 差异定位：两版各有 12 个 XML 注释块；剥掉注释后两版 md5 同为 `95d721c7a3bd8dd5a6582d18713d1e83`，
  `<revision>` 两侧都是 `0.2.1` ⇒ 差的全是注释文本（那段 fleet 旧读数被改成 fleet 1.0.1 的现读）。
* `BuildInfo.java` 与 `ZBotStdioMcpTransport.java` 当时已经等于提交字节（`7a3bacb0…` / `73c74eda…`）。
* Mac 那三轮（19:36:13 / 19:37:19 / 19:38:24，`mac_round{1,2,3}_*.log`）同样跑在提交前的树上，
  签名与 250 相同 ⇒ 保留为数据点，但**记作"提交前树（pom 仅注释差）"**，不记作发布字节。

所以 §1–§3 的读数才是"发布字节上的读数"。这条区分本身是这批证据里最容易糊掉的一格。

## §5 跨机同形（z-bot 侧）

| 跑 | 树 | 每跑读数 | 签名 |
|---|---|---|---|
| Mac ×3（19:36–19:39） | 提交前工作树 | 1240/0/0/0 BUILD SUCCESS | `8f7dfceb60dfc2fe` |
| Mac ×1（20:4x，§1） | 发布字节副本 | 1240/0/0/0 BUILD SUCCESS | `8f7dfceb60dfc2fe` |
| 250 ×3（19:50，`gate_logs/250_zbot/gate_20261001_195000_r{1,2,3}.log`） | 发布字节副本 | 1240/0/0/0 BUILD SUCCESS，端口命中 0 | `8f7dfceb60dfc2fe` |

七份日志的模块级 tally 行去重后都只剩一行，归一化（剥掉 `Time elapsed`）后 md5 全等：
`93172b81f927aaf6616b18b0b0d2311c` = `Tests run: 1240, Failures: 0, Errors: 0, Skipped: 0`。

**杠④ 在两机上不对称**，这是诚实的形状而不是缺陷：Mac 27 个文件逐字节未变判 PASS；
250 的 `/home/zifang/.zbot` 只有 0 个文件，`bar4_gauge.py` 印
`杠④ UNMEASURED: 空集相等不等于未被写动，拒绝判 PASS`（`gate_logs/250_zbot/*.log` 里逐跑都有）。

1240 这个面值不是抄来的，是**三把独立尺在同一份字节上现读对上**（都在 §1 那次全量之后取）：

| 尺 | 读数 | 取法 |
|---|---|---|
| reactor tally | `Tests run: 1240, Failures: 0, Errors: 0, Skipped: 0` | `bar14.log` 里的模块级行 |
| surefire XML 求和 | 117 份 `TEST-*.xml`，`tests=1240 failures=0 errors=0 skipped=0` | `python3 - <副本>/z-bot-core/target/surefire-reports`（`ET.parse` 取根元素四个属性求和，0 份判 FATAL） |
| 源码结构计数 | 行首 `@Test` 注解 1240 条 | `git grep -h -E '^[[:space:]]*@Test' HEAD -- "z-bot-core/src/test/**/*.java" \| wc -l` |

**上一版在这里拒绝写"1237 + 3 = 1240"，理由是区间里还夹着 `1227299`、而 grep 口径分几种。
现在两处都用同一把尺在 `git archive` 解出的三棵树上量完了，算式可以写，但写的不是那个：**

| 树（`git archive <sha> \| tar -x`，只读副本） | 行首 `@Test` | 逐行 `count('@Test')` 原始 | 注释行里的字样 | 有注解的文件 |
|---|---|---|---|---|
| `bb94d114`（recheck-0930 那个头） | **1236** | 1239 | 3 | 118 |
| `1227299` | **1237** | 1240 | 3 | 118 |
| `b3e32a2`（发布字节） | **1240** | 1243 | 3 | 119 |

逐文件差集（尺 `~/.cache/zbot_release_1001/test_count_three_rulers.py`，两两全枚举，不是抽样）：
`bb94d114 → 1227299` 只有 `memory/MemoryWriteGateTest.java` 从 `(11,11)` 到 `(12,12)`；
`1227299 → b3e32a2` 只有多出的 `BuildInfoDriftTest.java` `(—,3)`。所以真实的增量链是
**1236 + 1（`MemoryWriteGateTest`，`1227299`）+ 3（`BuildInfoDriftTest`，`b3e32a2`）= 1240**，
每一格都点到具体文件，`+3` 那种把两段增量混成一段的写法是错的。

⇒ **基线不是 1237**。`recheck-0930/EVIDENCE.md:613-616` 印的是 `head=bb94d114b30ea…  Tests run: 1237`、
`:623-624` 印的是 `reactor=1237 text_at_test=1240 minus=1237`，而 `bb94d114` 这棵**提交树**上是
1236/1239 —— 那一跑读的是**当时的工作树**，里面已经带着后来才进仓的第 12 条 `@Test`。
三条独立证据同向：`git log -S oldTextMatchesBodyNotTimestamp -- z-bot-core/src/test/java/com/zifang/z/bot/memory/MemoryWriteGateTest.java`
只点名 `1227299`；`git show --stat bb94d114` 动了 9 个文件、没有一个测试源码；
`bb94d114` 的提交标题自己写的就是"1236 例"，与它正文里的 1237 不同。
这是记忆里那条"`report()` 读盘上字节非提交树"的形状在**我自己的台账里**现形，不是别人埋的雷。
旧正文已推、不 amend；这里记正。

复算（三棵树各自解出后跑同一支脚本，0 命中判 FATAL）：

```
for s in bb94d114 1227299 b3e32a2; do git archive $s | tar -x -C <空目录>/$s; done
python3 ~/.cache/zbot_release_1001/test_count_three_rulers.py <空目录>/bb94d114 <空目录>/1227299 <空目录>/b3e32a2
```

口径对齐说明（行号一律 `awk 'NR>=56 && NR<=76'` 现取，不是记忆）：`bar1.sh:63` 的 `text_at_test` 是
`line.count('@Test')` 逐行累加（原始值，含注释里的字样），`bar1.sh:68-69` 把以 `*` / `//` / `/*` 开头的行
记成 `comment_hits`，`bar1.sh:76` 的 `minus` 就是 `text_hits - len(comment_hits)`。
**"原始 − 注释"与我这张表的"行首 `@Test`"是两把不同的尺，它们的相等是量出来的、不是结构保证的**：
三棵树逐树相等（1239−3=1236、1240−3=1237、1243−3=1240），意味着这批源码里没有"一行两条注解"或
"`@Test` 不排行首"的形状；真出现那种形状时这两把尺会分家，届时以 reactor 那把为准。
`1240` 那格里 1243 − 3 = 1240，与 §1 的 reactor 1240 和上一张表"源码结构计数"那行的行首 1240 三者同值。
两把尺的作用域也是对得上的：发布树里带行首 `@Test` 的 117 个文件**全部**在 `z-bot-core/src` 下
（`grep -rlE '^[[:space:]]*@Test' <树> --include='*.java' | cut -d/ -f1 | sort | uniq -c` 只出一行 `117 z-bot-core`），
而 `bar1.sh:58` 的 glob 本来就限定 `z-bot-core/src/test/java/**/*.java` —— 所以"我这把全树走的尺"
与"它那把只走 core 的尺"读的是同一批文件，差值不是作用域造成的。
上面那张三尺表说的是"此刻这份字节上三把尺各读到什么"，现在它还与"从 `bb94d114` 逐级推到 1240"接上了。

这一小节的现读时刻（`/bin/date` 各一次，不是推的）：**2026-10-01 20:58:55 +0800** 在解三棵树之前，
**21:05:07 +0800** 在写完上面这张表与逐文件差集之后。

## §6 对外可见到哪一版（20:4x 现读 repo1）

| 坐标 | 0.2.0 | 0.2.1 |
|---|---|---|
| `z-bot`（pom） | 200 | 404 |
| `z-bot-core`（pom） | 200 | 404 |
| `z-bot-desktop-packager`（pom） | 200 | 404 |

阳性对照：`z-bot-core-0.2.0.jar` = 200（同一次运行内读，否则"全 404"分不清是没发布还是没网）。
`z-bot/maven-metadata.xml` 的 `<latest>` 与 `<release>` 都是 **0.2.0**，`<versions>` 只有 0.1.0 / 0.2.0。
复算：`curl -s -o /dev/null -w %{http_code} https://repo1.maven.org/maven2/io/github/yuku123/<a>/<v>/<a>-<v>.pom`

⇒ **0.2.1 尚未发布**，这一格只有等 `deploy -Pcentral` 之后才可能变 200。

## §7 还没做的格子

* `deploy -Pcentral`：没跑，等 Central 单独点头。签名要用**默认 `~/.gnupg`** —— 现读对照：
  `GNUPGHOME=$PWD/.gnupg gpg --list-secret-keys | grep -c '^sec'` = **0**，
  同一次运行里 `gpg --list-secret-keys | grep -c '^sec'`（默认 HOME）= **1**。
  没有这个阳性对照，"仓内 0 条"完全可能是"gpg 坏了"而不是"这里没钥匙"。
  仓根 `.gnupg/` 是未跟踪目录（`git status --porcelain` 里就是那行 `?? .gnupg/`），不会被提交。
  凭证只从仓根 `.env` / `~/.m2/settings.xml` 取，不进仓、不进本文件。
* 假出口 dry run 的双向断言已在 `~/.cache/zbot_release_1001/dryrun/` 落盘并复跑（尺 `assert_bundle_zbot.py`
  19 条断言全绿，牙 `teeth_zbot.py` 7/7）。读数（20:4x 复跑原文）：
  `revision=0.2.1 坐标/打包: [('z-bot','pom'), ('z-bot-core','jar'), ('z-bot-desktop-packager','pom')]`；
  A（照 pom 原样）**36 条 = 全坐标期望 36**，B（`-DexcludeArtifacts=z-bot-desktop-packager`）**30 条**，
  消失的 6 条正好是被排除那一格的全部条目、且**非排除项一条没少**；两支都在
  `http://127.0.0.1:9` 处 `BUILD FAILURE`、红在 `Connection refused`，失败 goal 都是
  `central-publishing:publish … on project z-bot-desktop-packager`（末模块）。
  注意别把 `unzip -l` 的表头行数当条目数：旧尺就是这么把 36 读成 38 的，
  新尺第一条断言量的是"表头剔除只吃了 Name/---- 两类行"。
  **deploy 本身仍未跑**。
* 本仓**没有** `.github/workflows`（`git ls-files` 命中 0，`tag` 数 0）⇒ z-bot 这一侧不存在
  "推个 `v*` 标签就发一次中央仓库"的口子；那个口子在 z-script 仓（见
  `z-script/_doc/006_release/2026-10-01_1.0.2_发布前量具与读数.md` §5）。
* fleet 滞后：`z-boot-fleet:1.0.1` 里 `z-bot.version=0.2.0`、`z-script.version=1.0.1`（19:1x 现读，
  复算命令写在 `pom.xml` 的属性注释里）。抬它属于 z-boot 的发布决定，本轮没动。
