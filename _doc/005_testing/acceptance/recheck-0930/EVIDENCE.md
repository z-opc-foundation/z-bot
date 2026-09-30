# recheck-0930 EVIDENCE —— 杠① 第一次真跑在"当前树"上（09-30）

本期不是新功能，是**还账**：`README.md` 里那句"最近一次记录在案的杠① 读数不是当前树的"挂了三天 ——
它记的是 `p27c/EVIDENCE.md` §1 在最终树 `3d57571`（全 SHA `3d57571899acbda17cf59277d0dea5620fb2ceb8`，
`git merge-base --is-ancestor 3d57571 HEAD` rc=0）上跑的 `Tests run: 1223 ×3` 全绿；那之后 `3d57571..HEAD` 整段区间 10 笔，
其中动到 `z-bot-core/src` 的是 3 笔（`dc1d31a` / `5985a10` / `f687f20`，逐笔见 §0）。
本期把杠① 真跑在 `f687f20` 上，把红集逐条归因，并把**四支常红 + 一支间歇红**的机制取证到底。

三句话结论：

1. **杠① 跑完了，三批共十二跑都是红**：§1 那批四跑 `Tests run: 1236, Failures: 4|4|5|4, Errors: 0, Skipped: 0`，
   §1.4 的两批（README 改写后各四跑）都是 `Failures=4` ×4；**三批的红集 sha 同值 `939599c76c08`**，`socket_hits=0`。
   三把尺对着读成立（surefire 合计 **1236** == 116 份 XML 求和 **1236** == 文本 `@Test` **1239** − 注释 **3**），
   所以红不是"数不对"，是**四支真红**：`ToolSideInterruptTest` ×1、`ExecEnvironmentSpiTest` ×1、`PasteFolderTest` ×2。
2. **`5985a10` 把进程树收尾写死了**：`ProcessTree.pidOf()` 在现代 JDK 上**恒返 0**，`killTree` 因此每次退化成"只端根"，
   孙进程活下来。这不是测试的期望过严 —— 本期用两个独立探针量到 `root=0 / descendants=[] / killed_returned=0 / survivors=2`
   （那 2 个活口就是探针里那两条 `sleep 30`，计数口径见 §3.1），
   并在同一台机器上量到 `Process.class.getMethod("pid")` 能拿到真 pid（`37194`）。这是**产品缺陷**，改动等点头。
3. **`dc1d31a` 入库的 P31 粘贴折叠是个没跑过的半成品**：impl 与它自己的测试互相矛盾两处，一处 impl 对（预览头 16 字，
   与 hermes `lib/text.ts:85 edgePreview(s, head = 16, tail = 28)` 同源），一处 **测试对**（行数应为 6，
   hermes 在数行之前先 `stripTrailingPasteNewlines`，`useComposerState.ts:144` → `:191`；我们没剥，多数一行）。

**杠②③ 本期没跑**（见 §7），所以这不是一次四杠收口，是一次杠① 复账 + 取证。

---

## §0 树身份：§1 那批四跑与 §1.4 两批复核跑各量的是哪份字节

**§1 四跑**跑在 `HEAD = f687f2049040abfa5b0bf2736b1dea3de5dd0593`，起跑前 `git status --porcelain | wc -l` = **0**
（`identity.txt` 第二、三行）。四跑期间没有任何源文件被动过 —— 这条不是我保证，是跑完立刻现读：

```
$ find z-bot-core/src -name '*.java' -newer ~/.cache/zbot-recheck-0930/bar1-0.log
（空）
```

（这条今天 21:22 又跑了一次，仍空、命中数 0；注意那个 `bar1-0.log` 是**活动日志**，已被 §1.4 的批次覆盖成更晚的时刻，
所以现在跑它比的是"有没有源文件比最后一跑还新"，比 §1 当时那条更严。）

那四跑测量的四份字节，工作树与 `git show HEAD:` 逐字节相同（`md5 -q` 两侧同值）：

| 被测量的源 | 工作树 md5 | `git show HEAD:` md5 | |
|---|---|---|---|
| `tool/env/ProcessTree.java` | `30ea59a6d316bccbc408e9cf27c7269b` | 同 | SAME |
| `ui/PasteFolder.java` | `6eddaf60af3dea901e17522c5f81c65f` | 同 | SAME |
| `ReadmeClaimsTest.java` | `ced6751f627e3a46b4d5c182b55c71d0` | 同 | SAME |
| `README.md` | `02aae84d3944ea140642490476ccc788` | 同 | SAME |

上表是**§1 那批当时**的工作树 ↔ HEAD 对账。21:22 复测现在的盘上状态：前三份仍与 `git show HEAD:` 逐字节同值
（`30ea59a6… / 6eddaf60… / ced6751f…`，本期一个字没动过 `src`），
`README.md` 则**工作树 `0e03fc22…` ≠ HEAD `02aae84d…`** —— 这正是本期要改的那一支，
`git status --porcelain` 因此从 §1 时的 0 行变成 2 行（见 §1.4）。

**`README.md` 的 mtime = 2026-09-30 19:17:28**，早于第一跑的 20:20:01 ⇒ 唯一会被测试运行时打开的那份文档，
在 §1 那批读数产生之前一个字没动。这一条是补 p30c §0 自己记的那笔破形（"文档笔赶在杠① 之前落"）。

`02aae84d…` 那份 README 里写的还是"新树还没跑过杠①"，而本期跑的就是这一句 —— 所以它**必须**被改写，
而改写后的 README 才是 §1.4 两批复核跑的被测量（两版字节不同，`767bd3ac…` 与 `0e03fc22…`，见 §1.4）。三批读数的分界清楚写在这里，
不是因为 README 改动会影响 1236 这个数（`ReadmeClaimsTest` 核的是 9 条定量主张 + 子命令名单，
`grep -c 'new Claim('` 现数 = **9**（`:63/64/66/68/70/72/76/78/80`），本期改的那两段一个字都不在里面 ——
21:18:44 拿定稿字节 `0e03fc22…` 单跑该类：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` rc=0，
日志 `~/.cache/zbot-recheck-0930/readmeclaims-final.log`），
而是因为**"跑在哪份字节上"这句话本身必须逐批对得上**。

工具身份（`identity.txt` 全文 8 行逐字，`wc -l` = 8）：

```
IDENTITY|start|2026-09-30 20:20:00+0800
IDENTITY|head=f687f2049040abfa5b0bf2736b1dea3de5dd0593
IDENTITY|status_lines=0
IDENTITY|mvn=Apache Maven 3.9.14 (996c630dbc656c76214ce58821dcc58be960875b)
IDENTITY|java=openjdk version "25.0.2" 2026-01-20 LTS
IDENTITY|settings=682dc214ac03258d2c0bb28f765a86eb -s /Users/zifang/.cache/zbot-recheck-0930/settings-offline.xml
IDENTITY|P21_PYTHON=/usr/local/bin/python3.14 -> ?
IDENTITY|ps=/bin/ps
```

那行末尾的 `-> ?` 不是"读不到"，是 `bar1.sh:22` 的内联探针打的是自己写的兜底字面量 ——
现测 `hasattr(mcp,"__version__")=False`、`hasattr(mcp,"mcpver")=False`，两级 getattr 都落到 `"?"`。
python/mcp 的权威版本以 §5 那条 `importlib.metadata` 现读（**1.27.1**）为准，别引这行。

`3d57571` 是 HEAD 的祖先（`git merge-base --is-ancestor` rc=0），区间内 **10** 笔，动了 **199** 个路径：
`z-bot-core/src/main` 的 `.java` **7**、`src/test` 的 `.java` **8**、`pom.xml` + `.flattened-pom.xml` **6**（真 pom 3 份）、
`README.md` **1**、`_doc/` **177**。（工单/我先前口径里"12 个 src + 3 个 pom"是错的，这组数是 `git diff --name-only` 现算的。）

**本期量具的字节**（下表 md5 = 21:1x 对仓内文件现读；除 `bar1.sh` 外，入库的字节与跑时用的字节是同一份）：
`bar1.sh` = **两版** —— §1 那批跑的是 v1 `6ab125a46720ade1e4b060dc88650b7a`（as-run 副本已归档，见 §1.4），
§1.4 两批跑的是 v2 `9e0d5ff9def27194511e61990994238c`，**仓内入库的是 v2**；
`census.py` = `9d885a32274bba07b900a56898fad73d`、`flake_loop.py` = `3214ddf7d71afbf6bffbb7edae060c12`、
`settings-offline.xml` = `682dc214ac03258d2c0bb28f765a86eb`、`probe/TreeProbe.java` = `66ed2ff740f8236ba87f34545574338b`、
`probe/PidWhy.java` = `ef82067ccaa96930f5dec4be3e95c090`、`probe/FlakeMechanism.java` = `d50a48733ea804e3aadf5960e7efcbb2`。
`bar1.sh` 里 `REPO` / `OUT` 是绝对路径（`~/.cache/zbot-recheck-0930`），日志不进库（根 `.gitignore` 挡 `*.log`）；
换机器要改这两行，改完就不是本期跑的那份字节了 —— 所以照 p30c 的写法，这里给的是**复算命令**而不是"你也能跑"的承诺。

---

## §1 杠①：全 reactor `mvn -o test`，串行四跑（1 次 clean 预跑 + 3 次正式）

`rm -rf */target/surefire-reports` 后 `mvn -o -s <settings> [clean] test`，**不带 `-pl`**，reactor 三模块全跑，
同机串行、不并发。逐跑读数（`~/.cache/zbot-recheck-0930/driver.log` + 四份单跑 log，都在 `keep-f687f20/` 留了副本）：

| tag | 起 — 止（`+0800`） | goal | rc | mvn 自报 Total time | 合计行 | socket_hits | `<<<` 行数 |
|---|---|---|---|---|---|---|---|
| 0 | 20:20:01 — 20:21:15 | `clean test` | 1 | 01:13 min | `Tests run: 1236, Failures: 4, Errors: 0, Skipped: 0` | 0 | 7 |
| 1 | 20:21:15 — 20:22:26 | `test` | 1 | 01:10 min | 同上（逐字相同） | 0 | 7 |
| 2 | 20:22:26 — 20:23:38 | `test` | 1 | 01:10 min | `Tests run: 1236, Failures: **5**, Errors: 0, Skipped: 0` | 0 | 9 |
| 3 | 20:23:38 — 20:24:50 | `test` | 1 | 01:10 min | `Tests run: 1236, Failures: 4, Errors: 0, Skipped: 0` | 0 | 7 |

`census.py` 的逐跑红集（把红集名字排序取 sha1 前 12 位，防"看着一样"）：

```
CENSUS|tag=0 total=(1236, 4, 0, 0) n_red=4 set_sha=939599c76c08
CENSUS|tag=1 total=(1236, 4, 0, 0) n_red=4 set_sha=939599c76c08
CENSUS|tag=2 total=(1236, 5, 0, 0) n_red=5 set_sha=4ceb00fd8827   ← 多一支 MemoryStoreContractTest（§4）
CENSUS|tag=3 total=(1236, 4, 0, 0) n_red=4 set_sha=939599c76c08
```

⇒ **杠① 的字面判据（3 跑逐字相同 + `Failures=0`）不成立**：三跑红集相同但都不为 0，且第 2 跑多一支。
四支常红的名字（`bar1-0.log` 的 `Results:` 块逐字）：

```
[ERROR]   ToolSideInterruptTest.execAbortsInFlightAndTakesTheWholeProcessTreeDown:178 子进程没被收干净（只杀 bash 不杀后代就是这个数） expected:<0> but was:<1>
[ERROR]   ExecEnvironmentSpiTest.localExec_timeoutTakesTheWholeProcessTreeDown:145 超时收尾必须真的端掉后代，实测 killedDescendants=0
[ERROR]   PasteFolderTest.previewKeepsHeadAndTailAcrossLongText:68 0123456789abcdef.. xxxxxxxxxxxxTAILTAILTAILTAIL
[ERROR]   PasteFolderTest.tokenCarriesLineCountAndMatchesItsOwnPattern:43 [[ alpha bravo charlie delta echo foxtrot [7 lines] ]]
```

**规模尺三把对着读**（`bar1.sh` 里的 python 段现算，四跑每跑同值）：

- surefire 聚合行：**1236**；
- 跑后盘上 `*/target/surefire-reports/*.xml`：**116** 份，`tests` 求和 **1236**，`skipped` 求和 **0**；
- 文本 `@Test` **1239** − 注释里的字样 **3** = **1236**。那 3 处逐条点名：
  `ui/RawTerminalVerdictProbe.java:7`、`llm/P26RetryPolicyTest.java:37`、`memory/MemoryE2eDriver.java:16`；
- `src/main` 里 `@Test` 命中 **0**（防"把主源码里的字样算进分母"）；`find z-bot-core/src/test -name '*Test.java' | wc -l` = **116**。

聚合行的前缀在失败跑里是 `[ERROR]` 而不是 `[INFO]` —— 我第一版尺只认 `[INFO]`，于是四跑都打印 `reactor=None`。
`census.py` 是修好的那一版（`^\[(?:INFO|ERROR)\] Tests run: …$`，且排除带 `Time elapsed` 的逐类行）。
这条写在这里是因为 **"reactor=None" 长得像"没跑到"，而真相是尺瞎**：`bar1-0.log:1059` 一直有那行 `Tests run: 1236, Failures: 4`。

### §1.4 复核跑：README 改写之后再量一遍（两批，共八跑，红集同值）

为什么还要再跑：§1 那四跑测的是**旧 README**（`02aae84d…`，内容还是"新树还没跑过杠①"），而 `README.md`
是本期唯一会被测试运行时打开的文档（`ReadmeClaimsTest` 里读 `README.md` 的三处 = `:211`（`Files.readAllBytes`）、
`:223` 与 `:262`（两次 `readRepoFile`，后者是"不许冤枉健康页"那支）；`grep -n 'README\.md"' ReadmeClaimsTest.java` 现数只有这三处）⇒ 每改一次本页，"当前树的读数"这句话
就失去一次测量支撑。所以复核跑分两批：**批次 A** 测第一版改写（`767bd3ac…`），批 A 之后我又往本页补了
"两批/三批"式的计数句 —— 那句话本身让字节再变一次，于是有了**批次 B** 测最终版（`0e03fc22…`）。
这条链条的教训写进 §7.4：**口径不许在文档里数自己跑了几批**，否则每加一句计数就要重跑一遍。

每批起跑前的快照（`prerun-snapshot.txt` / `prerun-snapshot2.txt`，逐字）：

```
SNAPSHOT |20:53:03  head=f687f20…  status_lines=2  README=767bd3ac…  bar1.sh=9e0d5ff9…
SNAPSHOT2|21:05:00  head=f687f20…  status_lines=2  README=0e03fc22…  bar1.sh=9e0d5ff9…
两批的 src 侧字节与 §0 那张表逐字相同：ProcessTree=30ea59a6… PasteFolder=6eddaf60… ReadmeClaimsTest=ced6751f…
```

`status_lines=2` 与 §1 的 `0` 不同，**这 2 行全是文档面**（改写中的 `README.md` + 本期新增的 `recheck-0930/`）；
两批跑完都立刻复采：`find z-bot-core/src -name '*.java' -newer <快照>` = **空**，四个 md5 一字未动。

量具是 `bar1.sh` 的 **v2**（`9e0d5ff9…`）。v1 的字节不是凭记忆重建的：跑 §1 用的那份脚本在改尺之前先整份抄了存档
—— `~/.cache/zbot-recheck-0930/bar1.sh.v1-as-run-for-section1`，`md5 -q` = `6ab125a46720ade1e4b060dc88650b7a`
（**与 §0 记的 v1 md5 逐字相同**），源文件 `bar1.sh` 的 mtime = **20:14:28**、§1 首跑起点 **20:20:01**
（`ls -lT` + `identity.txt` 现读）⇒ §1 那四跑期间脚本没被碰过，抄下来的就是-as-run 的那份。
两版差多少也是机械数出来的，`diff -u`（v1 归档 ↔ 仓内这份 v2）= **3 个 hunk，删 5 行、加 7 行**，
加的 7 行里有 2 行本身就是注释（文件头那行、和解释 `[ERROR]` 前缀那行），余下 5 行全在尺上：
`grep -E '^\[INFO\] Tests run…'` 那两条（改成 `^\[(INFO|ERROR)\]`）、python 里 `line.startswith('[INFO] Tests run: ')`
那两行（改成前缀两认 + `'Tests run: ' in line` + `'Failures' in line`，取值改用 `split('Tests run:')`）。
**`mvn` 那一行两版同为 `:33` 且逐字相同**（`grep -n 'mvn -o -s' 两版` 各一行、内容一致）
⇒ "三批读数只差在尺、不差在跑法"是量出来的，不是我保证的。

批次 A 逐跑（`keep-rerun-767bd3ac/driver-rerun.log`）：

| tag | 起 — 止（`+0800`） | goal | rc | 合计行 | socket_hits | `<<<` 行数 |
|---|---|---|---|---|---|---|
| 0 | 20:53:12 — 20:54:26 | `clean test` | 1 | `Tests run: 1236, Failures: 4, Errors: 0, Skipped: 0` | 0 | 7 |
| 1 | 20:54:27 — 20:55:37 | `test` | 1 | 同上（逐字相同） | 0 | 7 |
| 2 | 20:55:37 — 20:56:48 | `test` | 1 | 同上 | 0 | 7 |
| 3 | 20:56:48 — 20:57:59 | `test` | 1 | 同上 | 0 | 7 |

批次 B 逐跑（最终 README，`keep-final-0e03fc22/driver-final.log`）：

| tag | 起 — 止（`+0800`） | goal | rc | 合计行 | socket_hits | `<<<` 行数 |
|---|---|---|---|---|---|---|
| 0 | 21:05:05 — 21:06:18 | `clean test` | 1 | `Tests run: 1236, Failures: 4, Errors: 0, Skipped: 0` | 0 | 7 |
| 1 | 21:06:18 — 21:07:31 | `test` | 1 | 同上 | 0 | 7 |
| 2 | 21:07:31 — 21:08:42 | `test` | 1 | 同上 | 0 | 7 |
| 3 | 21:08:42 — 21:09:53 | `test` | 1 | 同上 | 0 | 7 |

两批 `census.py` 的判定完全一致：`runs=4 identical_red_set=True sets=['939599c76c08']`、
`totals=[(1236,4,0,0)]×4`，红集成员逐字仍是 §1 点名的四支；`939599c76c08` 与 §1 里三跑同值的那份红集 **是同一个 sha**
⇒ 三批十二跑的红集没有漂过一次。三把尺逐跑的读数（`grep -h RULER <批>/driver*.log` 现数，十二行全列）：

```
§1 批（v1 尺）  tag=0..3  reactor=None   test_files=129  xml_files=116  xml_sum=1236  xml_skipped=0
批 A（v2 尺）   tag=0..3  reactor=1236   test_files=129  xml_files=116  xml_sum=1236  xml_skipped=0
批 B（v2 尺）   tag=0..3  reactor=1236   test_files=129  xml_files=116  xml_sum=1236  xml_skipped=0
每跑还各打一行静态尺（十二行逐字相同）RULER|text_at_test=1239 comment_lines=3 minus=1236 main_src_hits=0
```

`reactor=None` 只出现在 §1 那批（v1 尺的前缀判据瞎，见上节）；`xml_files=116 / xml_sum=1236` 十二跑逐跑同值。
**归档目录里只有日志，没有那 116 份 XML**（`keep-*/` 内容 = 四份 `bar1-?.log` + `driver*.log` + `identity.txt`，
批 B 另带 `prerun-snapshot2.txt`，§1 批另带两份 targeted 日志）⇒ 盘上 XML 那份计数是**跑当时现算、之后由 RULER 行留存**的，
不是"你现在去 `target/` 还能数出 116 份"（21:18 我单跑 `ReadmeClaimsTest` 就覆盖了其中一份 `.xml`，虽然当时盘上仍是 116 份）。

⇒ **杠① 的字面判据（`Failures=0`）在最终定稿字节上不成立**；成立的是"红集稳定、分母三把尺对得上、socket 类命中 0"。
间歇红在十二跑里出现 **1** 次（只有 §1 的 tag=2；批 A、批 B 各 0 次）—— 复核**没有**把它复现出来，
也不是它被修好了（§4 那处一行代码都没动）。

**§3.1 那条产品缺陷在两批复核里逐跑复现**：批 A 跑后数出 **4** 个 `sleep 9xxxxxxxx` 孤儿（`ppid=1`，
`lstart` 20:53:24 / 20:54:36 / 20:55:47 / 20:56:58），批 B 又是 **4** 个（21:05:17 / 21:06:30 / 21:07:41 / 21:08:52），
每一个都落在自己那一跑的起止窗口内 ⇒ "四跑 = 四个泄漏"是因果对上号的，不是相关性猜测。
八个 pid（39683/40630/41559/42910 与 44780/45603/46474/47279）本期按 pid `/bin/kill -9` 清掉，
复扫 `remaining=0`（尺的阳性对照是同一条 `ps` 管道里的正则形状本身）。

**杠④ 复采**（与 §6 同口径）：批 A 后 20:59:44、批 B 后 21:11:31 两次都是 `entries=8`、
`cfg=2dadaed05e0cefe7a53f48baba3c403a`、`state=690ddbc0e0e35f3a9dc183302f1d5182`，与 §6 逐字相同。

**本节落笔的时序**（结构性欠账，写清楚别靠解释）：两批的数字都是**跑完之后**才填的 —— 没有"预先量到"这条路。
成立的部分是：每一批起跑前 README 已是那一批的定稿（两份快照 md5 与跑后复采逐字相同），
跑完只改这份**没有任何测试会打开**的 EVIDENCE（现测 `grep -rn "recheck-0930" z-bot-core/src/test --include='*.java'` =
0 命中，阳性对照是同一条 grep 语法下 `p28` 有命中；全仓测试运行时打开的 `_doc/` 目标只有
`p21/`、`p28/ROUTES.tsv`、`p28/EVIDENCE.md`、`p15b/sessions_column_alignment.md` 四个）。
不成立的部分也写出来：批 A 之后 README 又变过一次（`767bd3ac` → `0e03fc22`），**所以批 A 的读数测的是一份
已经不存在的页**；本页最终字节只有批 B 那四跑支撑。
---

## §2 18 支路径红 → 0：`9fdf220` 到 `f687f20` 的账

四跑之前先在 `9fdf220` 上跑过同一串（副本 `bar1-{0..3}.log.pathred-9fdf220`），那批红**大部分不是产品问题**，
是 09-30 的文档收口 `367dc70`（作者 zifang.zch，"散落文件收口到 `_doc/` 编号桶"）把运行时 fixture 从测试脚下搬走了：

```
[ERROR] Tests run: 1236, Failures: 12, Errors: 10, Skipped: 0
$ <Results 块按类计数>
  SessionsColumnAlignmentTest  7    ← _doc/acceptance/p15b/sessions_column_alignment.md 找不到
  McpRealStdioServerTest       5    ← RealMcpHarness 找 _doc/acceptance/p21
  HttpRouteLedgerTest          2    ← p28/ROUTES.tsv / p28/EVIDENCE.md
  McpHandshakeTimeoutTest      2    ← 同 p21
  McpParentWatchdogTest        2    ← 同 p21
  PasteFolderTest              2    ← 真红（§3.2/§3.3）
  ToolSideInterruptTest        1    ← 真红（§3.1）
  ExecEnvironmentSpiTest       1    ← 真红（§3.1）
```

18 = 7+5+2+2+2（路径红），22 − 18 = 4 正是本期剩下的常红。报错原文一支（`bar1-0.log.pathred-9fdf220` 逐字）：

```
[ERROR]   SessionsColumnAlignmentTest.alignmentTableHoldsExactlyHermesFortySixSessionsColumns:160->readRows:76->locateDoc:72
          找不到理由表 _doc/acceptance/p15b/sessions_column_alignment.md（从 …/z-bot/z-bot-core 往上找 5 层）
```

`f687f20` 只做机械改道（三个类里的 9 处路径常量 → `_doc/005_testing/acceptance/…`；`RealMcpHarness.python()` 认 `P21_PYTHON`），
**没动任何断言**。定案复跑（`targeted-f687f20.log` / `targeted-mcp-f687f20.log`，都带 `P21_PYTHON`）：

```
-Dtest=SessionsColumnAlignmentTest,HttpRouteLedgerTest,ReadmeClaimsTest → Tests run: 22, Failures: 0, Errors: 0  rc=0
-Dtest=McpRealStdioServerTest,McpHandshakeTimeoutTest,McpParentWatchdogTest → Tests run: 11, Failures: 0, Errors: 0  rc=0
```

⇒ **18 → 0** 有独立两跑点名，不靠"整体红集少了 14 支"推断。`ReadmeClaimsTest` 一起进去是因为它同读 `_doc/`，
本期 README 改动必须让它当场绿（它核的是 9 条定量主张 + 子命令名单，见 §5 那把尺的边界）。

---

## §3 四支常红的根因（每支给复现命令）

### §3.1 `ToolSideInterruptTest:178` + `ExecEnvironmentSpiTest:145`：`ProcessTree` 在现代 JDK 上恒退化

**这是产品缺陷，不是测试期望过严。** 链路：`LocalExecEnvironment:189/209` 与 `InterruptScope:164` 调
`ProcessTree.killTree(p)`，`killTree` 先 `pidOf(p)`，`root <= 0` 就**不枚举后代**、只 `destroyForcibly()` 根，
返回值 0（`ProcessTree.java:126-143`、`176-201`）。而 `pidOf()` 的第一条路是

```java
Long viaMethod = (Long) p.getClass().getMethod("pid").invoke(p);   // ProcessTree.java:182
```

`p.getClass()` 是 `java.lang.ProcessImpl` —— 它在 `java.base` 里**不是 public**，模块不把它导出给未命名模块，
于是"公开方法"也抛 `IllegalAccessException`；第二条路读私有字段抛 `InaccessibleObjectException`；两条都失败 ⇒ `return 0L`。

复现（`probe/PidWhy.java`，JDK 25 同一 JVM 内三条路各打一枪）：

```
impl_class=java.lang.ProcessImpl modifiers=final
via_impl_class_THROWS=java.lang.IllegalAccessException: class PidWhy cannot access a member of class java.lang.ProcessImpl (in module java.base) with modifiers "public"
via_Process_class=37194                     ← 换成 Process.class.getMethod("pid") 就拿到了
field@java.lang.ProcessImpl_THROWS=InaccessibleObjectException: … module java.base does not "opens java.lang"
field@java.lang.Process_THROWS=NoSuchFieldException: pid
```

复现（`probe/TreeProbe.java`，复刻 `ExecEnvironmentSpiTest:132-145` 的进程形状 `/bin/sh -c "sleep 30 & sleep 30 & wait"`）：

```
PROBE|root=0 descendants=[]|sleep_procs_base=12_during=14
PROBE|killed_returned=0|sleep_procs_after=14|survivors=2
```

存活数不是读 `ProcessTree` 自己的返回值，是另起 `/usr/bin/pgrep -x sleep` 独立口径、并减掉起跑前的基线（12）。

**这条红的真实代价：孤儿进程。** 首跑那 12 次（含 §2 的 `9fdf220` 那串）之后盘上数得出 **12** 个
`sleep 9xxxxxxx` 孤儿（`ppid=1`，`lstart` 全落在 20:09:38 — 20:23:49 即我的测量窗口内），
形状与 `ToolSideInterruptTest:134` 的 `final String token = "9" + (1_000_000 + (int)(System.nanoTime() % 8_000_000L))`
+ `:146 "sleep " + token + " & wait"` 逐字对得上 ⇒ 每次跑泄漏一个约 **27 年**才自杀的进程。
本期把这 12 个按 pid 逐个 `/bin/kill -9` 清掉了（复扫 `remaining_long_sleeps=0`，阳性对照 `any_sleep_procs=2` ⇒ 尺看得见 sleep）。
   **§1.4 的两批复跑把这件事逐跑复现了一遍：8 跑 = 8 个孤儿，`lstart` 各自落在自己那一跑窗口内**（逐条 pid 见 §1.4）。

**回归来源**：`5985a10`（09-28 22:04，作者 zifang.zch）之前那一版是直接 `p.descendants().forEach(…)`（
`git show 5985a10~1:…ProcessTree.java` 的 `:32/:56/:60`），在现代 JDK 上是**通**的 —— 所以这不是"新测试挑了个老毛病"，
是那笔 JDK 8 回移把一条活路换成了死路。该笔自己的提交信息就写着 **"本会话未撰写这些改动、也未跑构建复验"**、
"复验欠账：corretto-1.8 下 `mvn -o test-compile` 未跑"。本期把它跑出来了。

**修法（未做，等点头）**：`pidOf()` 第一枪换成 `Process.class.getMethod("pid").invoke(p)`（公开 API，9+ 必通），
私有字段那条只留给 JDK 8；并且把"降级成只端根"从静默改成可观察（现在 `killedDescendants=0` 与"树上本来没后代"同值）。

### §3.2 `PasteFolderTest:68`：预览头 16 字 —— **impl 对，测试错**

`PasteFolder.java:27 PREVIEW_HEAD = 16`，`edgePreview()` 取 `substring(0, 16)`；
测试期望的是 `"0123456789abcd.. "`（14 字）。权威对照取 hermes 原件：

```
$ grep -n "edgePreview" ~/.hermes/hermes-agent/ui-tui/src/lib/text.ts
85:export const edgePreview = (s: string, head = 16, tail = 28) => {
```

⇒ 16 是契约里的数，**测试的 14 是凭印象写的**（`dc1d31a` 那笔是 `chore(sync): 收口工作树未提交改动`，
提交信息自己写着"本笔未逐项验证构建/测试，只保证盘上内容入库"）。修的话改测试，不动 impl。

### §3.3 `PasteFolderTest:43`：行数 7 vs 6 —— **测试对，impl 少了剥尾换行**

`SIX_LINES = "alpha\n…foxtrot\n"`（结尾带换行）。`PasteFolder.java:44` 的 `lineCount` 注释明说
"与 JS `split('\n').length` 同形：结尾换行也算出一行空尾" ⇒ 它给 7，并且**这个表达式本身没错**。
错在**没有把 hermes 的前一步搬过来**：

```
$ grep -n "stripTrailingPasteNewlines" ~/.hermes/hermes-agent/ui-tui/src/app/useComposerState.ts
144:      const cleanedText = stripTrailingPasteNewlines(text)
191:      const lineCount = cleanedText.split('\n').length      ← 数的是剥过尾换行的那份
$ grep -n "export const stripTrailingPasteNewlines" …/lib/text.ts
189:export const stripTrailingPasteNewlines = (text) => (/[^\n]/.test(text) ? text.replace(/\n+$/, '') : text)
```

我们这一侧 `ZBotLineReader:87/90` 直接把 `buffer.substring(from, to)` 交给 `shouldFold/token`，
**全仓 `src/main` 里搜不到任何剥尾换行的动作**（`grep -rn "stripTrailing|replaceAll(\"\\n+\$\"" z-bot-core/src/main` = 0 命中，
阳性对照是同一条命令里 `lineCount` 的必然命中）。终端粘贴几乎总带一个尾换行 ⇒ 我们**每次多算一行**，
并且 `shouldFold` 的阈值（5 行）也一并偏。修的话改 impl（补 `stripTrailingPasteNewlines` 同形），测试那句 `[6 lines]` 是对的。

一处**本期没判**的余地：`token()` 里 `[7 lines]` 与 `TOKEN` 的 `[[…]]` 形状自洽（`[7 lines]` 不含 `]]`），
所以"少一行"只影响数字、不影响可展开性 —— 要不要连 `COLLAPSE_LINES` 的边界用例一起补，等产品口径。

---

## §4 间歇红：`MemoryStoreContractTest.replaceAndRemoveHitExactlyOneEntry` —— 机制已证，产品锐面更大

§1 那批四跑里只有**第 2 跑**多这一支（§1.4 的两批八跑各 0 次）。出处是这台机器上的归档
`~/.cache/zbot-recheck-0930/keep-f687f20/bar1-2.log:1025` —— **活动日志 `bar1-2.log` 已被后面的批次覆盖，
它第 1025 行处现在是一行空的 `[INFO] `**。逐份日志数该测试名的命中数
（`for f in ~/.cache/zbot-recheck-0930/keep-*/bar1-?.log; do printf '%s %s\n' "$(grep -c replaceAndRemoveHitExactlyOneEntry $f)" $f; done`）：
`keep-f687f20` 是 `0 / 0 / 3 / 0`（tag 0→3），另两批八跑全 0。那 3 处字样是同一跑的同一支失败的三行
（`:113` 的 `<<< FAILURE!` 行、`:115` 的 `at …MemoryStoreContractTest.java:139` 栈行、`:1025` 的失败清单行）：

```
[ERROR]   MemoryStoreContractTest.replaceAndRemoveHitExactlyOneEntry:139 旧正文要被换掉
```

那支断言是 `assertFalse("旧正文要被换掉", store.readMemory().contains("250"))`，
而条目行的形状是 `- [Instant.now()] 正文`（`MemoryDriftGuard.java:128-129`、`MemoryStore.java:292/297`）——
**断言在整页文本上找 `250`，页里每行都带一个墙钟微秒位**。

机制不是猜的，用真 API 造一条时间戳带 `250` 的页（`probe/FlakeMechanism.java`）：

```
A_real_api|bodies_have_251=true|page_contains_250=false          ← 正常情形
B_crafted|body_has_250=false|page_contains_250=true             ← 正文一个字不含 250，页面照样含 250
```

率也量了两个口径：
① 单把尺数时间戳 —— 同一 JDK 里连采 `Instant.now().toString()` **20000** 次，含 `250` 的 **44** 次 = **0.22%**/条
（形态是 `.439250` 这种尾三位；精度分布 `len6=19980 len3=20 len9=0`）；一页两条时间戳 ⇒ **≈0.44%**/跑。
② 真编译产物 + 真 classpath 起独立 JVM 跑整个测试类 **200** 次（`flake_loop.py`，
阳性对照：同一套解析先跑 `PasteFolderTest` 必须报出 2 支红 ⇒ 尺看得见失败）：**命中 1 次 = 0.50%**，与①对得上。

⇒ 本树的 reactor 全量跑共 **12** 次（§1 四跑 + §1.4 批 A 四跑 + 批 B 四跑；§2 那四跑在 `9fdf220`，不计入），
间歇红出现 **1** 次 = 8.3%。**先前我在这里写的是"比机制预测的 0.44% 高一个量级，我解释不了这部分差"—— 那句是把
1/12 当点估计读了**：1/12 的 Clopper-Pearson 95% 区间是 **[0.21%, 38.48%]**（现算：
`lo = 1 - 0.975**(1/12)`，`hi` 由 `P(X≤1 | Bin(12,p)) = 0.025` 二分解出），**区间本身就盖住 0.44% 与 0.50%**
⇒ 观测与机制不矛盾，本期没有"解释不了的差"要解释，要解释的是我自己那次读数的错法。
测试侧的修法是把断言挪到 `entryBodies()`（剥过前缀的正文）上，而不是整页文本。

**顺带证出的产品锐面（比这条红更值钱）**：`MemoryWriteGate.locate()` 是在**整行**（含时间戳）上 `contains(oldText)`：

```java
for (int i = 0; i < entries.size(); i++) {
    if (entries.get(i).contains(oldText)) { hits.add(i); }     // MemoryWriteGate.java:70-71
}
```

而它自己的报错文案写的契约是"要改/删的那一条**正文**里的一段唯一子串"（`:65-66`）。复现（`FlakeMechanism` 的 C 臂，
阳性对照 D 臂是同一次运行里的正文命中）：

```
C_misdelete|removed_without_error=true|entries_now=0|body_still_had_250=false   ← 删掉的是"正文里没有 250"的那条
D_control|entries_after=0                                                       ← 正文里真有 250 的正常路径
```

⇒ 一次 `remove_entry(old_text="250")` 会因为某条目的**时间戳**尾三位而**静默删错条目**；两位数字（`"12"`）
几乎能命中每一条目的时间戳 ⇒ 要么误删、要么假 `AMBIGUOUS_MATCH`。这是产品面，改动等点头。

---

## §5 环境漂移：三件事让"`mvn -o test` 跑不起来"看起来像代码坏了

这一段的存在理由：下期有人拿同一棵树跑不出 1236，第一反应会是"树坏了"。三条都有现场读数。

1. **provenance id 冲突**。`~/.m2/repository/io/github/yuku123/z-boot-parent/1.0.21/_remote.repositories` 记的是
   `z-boot-parent-1.0.21.pom>maven-central=`，而 `~/.m2/settings.xml` 里 `<id>` 只有 `central`（`grep -c maven-central` = **0**）
   ⇒ 离线模式判"present, but unavailable"（`bar1-0.log.attempt1-provenance-fail` 原文）：
   `Non-resolvable parent POM … io.github.yuku123:z-boot-parent:pom:1.0.21 (present, but unavailable): Cannot access central … in offline mode`。
   反向也堵：`org/junit/junit-bom/5.9.3/_remote.repositories` 记的是 `central=` ⇒ 拿一个 id=`maven-central` 的 mirror
   去救第一条，就把第二条顶死。**我没动共享 `~/.m2/settings.xml`**（那里有一份 `<server id="central">` 明文口令，不是我该改的文件），
   改成仓内这份 `settings-offline.xml`：一个默认激活的 profile 里**追加** `<repository><id>maven-central</id>` + 同名 `pluginRepository`，
   两个 provenance id 都认。
2. **在线路径这台机器上不可用**。`~/.m2/…/z-boot-parent-1.0.21.pom.lastUpdated`（09-30 20:02:10，是我那次在线尝试留下的）原文：
   `Could not transfer artifact io.github.yuku123:z-boot-parent:pom:1.0.21 from/to central (https://repo.maven.apache.org/maven2): (certificate_unknown) PKIX path building failed … unable to find valid certification path`
   —— 同一条 URL 用 `curl` 取是 200 ⇒ 这是 **JVM 信任链**断了（本机 TLS 被中间人），不是仓库没有这个件。
3. **surefire 从 3.2.5 掉到 2.22.2，provider 件本地仓里没有**。抬 `z-boot-parent:1.0.21`（`e2b7540`）之后父链下发
   `<maven-surefire-plugin.version>2.22.2</maven-surefire-plugin.version>`（现读 `~/.m2/…/z-boot-parent-1.0.21.pom`），
   而抬版之前 pom 没有 `<parent>` ⇒ Maven 3.9.14 用默认 3.2.5，所以 2.22.2 的 provider 一条都没被下载过
   （`bar1-0.log.attempt2-missing-provider`：`1 required artifact is missing`）。
   我按 sha1 从 repo1 补了 4 件并逐件对账（`shasum -a 1` 与 `$URL.sha1` 现比）：
   `surefire-junit4` `ac953b744c812b8c047f4a050e4fdc610877842b`、`common-junit4` `7fea04550ad83e6c87ee94952dadb3973a715e8e`、
   `common-junit3` `45a65177c235069a5c91115fa48fdd9468d90ab2`、`surefire-api` `273caddf446705ccceada91064e7266eb8517608` —— 4/4 MATCH。
   另：`-Dtest` 的分隔符在 2.22.2 是**逗号**，写 `A+B+C` 会 `No tests were executed!`（本期踩过一次）。

**`P21_PYTHON` 是硬前提**，不是可选：`/usr/bin/python3` 是 3.9.6 且 `import mcp` 报 `ModuleNotFoundError`，
装了 mcp 的是 `/usr/local/bin/python3.14`（`importlib.metadata` 现读 **1.27.1**）。不设这个变量时，
9 支 MCP 用例外挂在"官方 MCP Python SDK 不可用"上 —— 那 9 支后来证明还是 §2 的路径问题，
但**报错文案会把人带偏到"环境没装 SDK"**，所以 `bar1.sh:13` 把它显式导出了。

---

## §6 杠④：`~/.zbot` 一个字没动（收口时现采）

```
$ ls -1A ~/.zbot | wc -l      → 8
$ md5 -q ~/.zbot/config.properties → 2dadaed05e0cefe7a53f48baba3c403a
$ md5 -q ~/.zbot/state.db      → 690ddbc0e0e35f3a9dc183302f1d5182
```

条目清单（`.stty.bak config.properties cron memories models-cache.json sessions state.db workspace`，`cron` 仍是空目录）与
两个 md5 都和 `p30c/EVIDENCE.md` §4 记的基线**逐字相同** ⇒ §1 的四跑与 §1.4 两批的八跑（合计十二跑）加我这几个探针，
都没写过真数据根。批 A 之后、批 B 之后、以及本期收口前各采一次，三次同值：

```
RESAMPLE|2026-09-30 20:59:44+0800|entries=8|cfg=2dadaed0…|state=690ddbc0…
RESAMPLE|2026-09-30 21:11:31+0800|entries=8|cfg=2dadaed0…|state=690ddbc0…
RESAMPLE|2026-09-30 21:24:11+0800|entries=8|cfg=2dadaed0…|state=690ddbc0…   ← 21:18 那次单类跑之后复采
```

**这一杠的口径限制要写出来**：基线是**从仓内文档读来的**、不是我跑之前采的（我漏了跑前那次采样，
`prerun-snapshot.txt` / `prerun-snapshot2.txt` 里都没有 `~/.zbot` 这一项），所以它证的是"到今天 21:24:11 为止仍是那份字节"，
证不了"跑的过程中一个字节没变过又变回来"。下期把这三项并进 prerun 快照，跑前跑后各一刀。
本期所有量具（`bar1.sh`/`flake_loop.py`/三个 probe）只写 `java.io.tmpdir` 与 `~/.cache`。这句话的取证口径**不是**
"代码里没有 `~/.zbot` 字面量"（那句我先前写了，是错的 —— 现测 `grep -rn zbot` 在四个非文档文件里有 7 处命中），
而是逐处点名：

```
$ grep -n "zbot" bar1.sh census.py flake_loop.py probe/*.java
bar1.sh:7:OUT=$HOME/.cache/zbot-recheck-0930
flake_loop.py:4:不碰 mvn，不碰 ~/.zbot，只在 java.io.tmpdir 里建 TemporaryFolder。"""   ← docstring 声明，非代码路径
flake_loop.py:8:OUT = os.path.expanduser('~/.cache/zbot-recheck-0930')
FlakeMechanism.java:15/30/40/54:  Files.createTempDirectory("zbot-mem-probe{,2,3,4}")
```

⇒ 全期唯一一处 `~/.zbot` 字样在 `flake_loop.py` 的 **docstring**（它声明的正是"不碰"），执行路径里 0 处；
其余 6 处都是 `~/.cache/…` 输出根或临时目录前缀。`census.py` 对 `zbot` 零命中。

---

## §7 本期仍然欠的（别读成"已收口"）

1. **杠②③ 一期没跑**。本期只补了杠① + 根因取证 ⇒ 这不构成四杠收口；`p30`/`p30b`/`p30c` 那几族的杠② 台账量的也不是 `f687f20`。
2. **四支常红 + 一支间歇红没修**，因为三处是**产品面**（`ProcessTree.pidOf`、`stripTrailingPasteNewlines`、
   `MemoryWriteGate.locate` 在整行上匹配），一处是**测试面**（`PasteFolderTest:68` 的 14 字前缀）。
   改产品代码等点头；改测试期望也要点头 —— 因为它是"哪一侧才是契约"的裁定，不是笔误。
3. **`bar1.sh` 的判据本身不含"红集必须为 0"**：它只打印 rc/socket_hits/合计行，红集清点靠 `census.py`。
   下期要么把两者并成一个尺，要么把"红集非空即 FATAL"写进脚本 —— 现在这十二跑 `rc=1` 是**脚本没退化、树确实坏**。
   本期已修的是那条"**尺瞎**"（v1 只认 `[INFO]` 前缀 ⇒ §1 那批四跑全打 `reactor=None`，看着像没跑到），v2 现读 `reactor=1236`；
   v1/v2 的差全在尺上（`diff -u` = 3 hunk、删 5 加 7，含 2 行注释；`mvn` 行两版同为 `:33` 未动），
   v1 的 as-run 字节是**改尺前整份抄走**的副本，md5 == §0 记录值（细节见 §1.4）。
4. **本期新立的一条纪律（自己踩出来的）**：**验收页里不许出现"跑了几批/几遍"这类自计数**。
   踩的过程：批 A 测的是第一版改写（`767bd3ac`），批 A 之后我把它的读数往 README 里填时，顺手写了
   "串行跑了两批 … 两批各四跑 … 第二批 `Failures=4` ×4" —— 也就是**把"还要再跑一批"这件事预先写进了页里**，
   而那句话本身让字节再变一次（`767bd3ac` → `0e03fc22`），于是批 A 的读数成了"测一份已经不存在的页"，
   只能再跑批 B 收尾，并把页面上的自计数整个去掉。
   **这一段里没法机械复验的是中间那份字节的原文**：`767bd3ac` 与那句"两批"的中间版我都没归档，
   盘上只剩 `prerun-snapshot.txt:4` / `prerun-snapshot2.txt` 的两条 md5 ⇒ "批 A 测的是哪份字节"成立（跑前 md5 已记），
   "那一版页面上写了什么"靠的是我抄回的原文，不是盘上证据。
   读数必然是跑完才填（没有"预先量到"这条路），但**页面上引用的必须是跑之前的字节** —— 所以口径句要写成与批次数量
   无关的形状（现在 README 引的是"§1 逐跑读数 + 红集 sha"，全文不含"跑了几批"字样，
   `grep -c '两批\|三批\|各四跑' README.md` = 0，阳性对照是同一条 pattern 在本页有 22 处命中），
   跑完只改这份没有任何测试会打开的 EVIDENCE
   （现测 `grep -rn "recheck-0930" z-bot-core/src/test --include='*.java'` = 0 命中，阳性对照同语法下 `p28` 命中）。
5. **CI 没接成第五把尺**（p30c §6.5 原样搬）：本期十二跑全在同机串行，另一台机器/CI 上从没跑过 `f687f20`。
6. **旧账里两处"已推过的明文凭证"是我凭印象写的，现测不成立**（09-30 复算，命令与读数一起给）：
   `find` 全仓 —— `z-bot/_doc/008_misc/ai-docs/AGENTS.md` **不存在**（`ls` rc=1），`_code_temp/` 只在
   `z-opc/_code_temp`（不在 z-bot）。z-bot 跟踪件的凭证形状扫描 `git grep -nEI
   "ghp_…|AKIA…|x86…" HEAD` 只命中 1 处，且是 `SkillGuardTest.java:74` 里 AWS 文档的**示例串**
   `AKIAIOSFODNN7EXAMPLE`（那是守卫用例自己的猎物，不是泄漏）。⇒ 这条从"已推的凭证"改成真实的两笔：
   (a) **本机** `~/.m2/settings.xml` 有一个 `<server id="central">` + 1 处 `<password>` 明文，从没进过任何仓；
   (b) `z-cache/.build-context/settings.xml` 有明文 GitHub PAT，被 `.gitignore:24 /.build-context/` 挡着（已验 0 跟踪路径）。
   另有一笔与本期无关但压着"本地不能藏代码"这条规则的：`z-opc/_code_temp` 盘上 2871 份、跟踪 2778 份，
   差的 93 份全被 `.gitignore:135 **/store/` 一类规则吞掉；逐名查其中 20 份源样文件，18 份在跟踪树里有同名件，
   2 份没有 —— `PageResult.java` 的真实出处是 **z-util** 的 `com.zifang.util.core.meta.page.PageResult`
   （z-team 的 19 处引用 import 的正是它），`fe.config.js` 只是被同步快照自己的 README 提到。
   ⇒ 结论是"这次没藏住"，不是"这条规则安全"：`**/store/` 仍在，下期动 `store/` 目录还得用逐扩展名对账尺。
7. **本机 `ps`/`date` 不在 `/usr/bin`** —— 本期现测（不靠回忆）：`/usr/bin/ps` ABSENT、`/bin/ps` EXISTS、
   `/usr/bin/date` ABSENT、`/bin/date` EXISTS，`command -v` 两者都解析到 `/bin`。
   本期因此误判过一次"进程死了"（真相是尺调了个不存在的绝对路径）。写量具时一律 `/bin/ps`、`/bin/date`，或靠 PATH。
8. **随期入库那 8 份文件推前扫过一遍凭证形状**（21:2x，`grep -rnE 'ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|<password>[^<]{3,}</password>'`
   打在 `recheck-0930/` 全树）：唯一命中是本页 `:507` 抄回的那条 AWS 示例串 `AKIAIOSFODNN7EXAMPLE`，
   `settings-offline.xml` 全文 43 行只有 `<id>/<url>/<profile>`，无 `<server>`、无口令。
   **这条负向读数的阳性对照**是同三条正则打在 `~/.m2/settings.xml` = 1 行命中（`<password>` 那行）、
   打在 `z-bot-core/src/test/…/skill/SkillGuardTest.java` = 1 行命中（`:74` 的示例串）⇒ 尺看得见这两类形状。
