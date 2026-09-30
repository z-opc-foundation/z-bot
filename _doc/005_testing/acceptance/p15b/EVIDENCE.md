# P15b —— EVIDENCE（四杠读数 + 产生读数的命令）

写手：`p15b2`（收口写手，接手被 150 轮截断的 `p15b`）。
站姿：`w1-p15b` @ **`2af9174`**（`git log --oneline -2` 的第一行；父 `13027f4`）。
本文档里**凡是能自己重跑的数（杠①、保鲜尺、参照仓三数、杠④、产品代码面 diff、硬规则逐行对账、以及杠②台账的每一列）都是本写手在本机重跑过的**；
不能重跑的两处（杠② 的 8 支注入、杠③ 的 32 条真进程 E2E —— 工单明令不许再抢那把锁）核的是台账/日志**原文**，
并为它们自立了独立证据（§2 的 md5 还原证明、§3 的条数与负向对照），这类地方一律写明"仅线索"。
与主编（工单附带的复算读数）**全部一致，无冲突**；冲突发生在**上一棒留下的表内措辞**与实测之间，见 §6。

> 为什么读数必须原样贴进来：本仓 `.gitignore:5` 是 `*.log`（实测 `sed -n '5p' .gitignore` ⇒ `*.log`）
> ⇒ `logs/` 下这 27 个日志（`ls _doc/005_testing/acceptance/p15b/logs/*.log | wc -l` = 27，其中 5 个是本轮 `recalc_*`）**只活在这台机器上**，
> 推上去的仓里没有它们。下面每一段引用块就是"推上去之后仍然可核"的那一份。

---

## 0. 起手（命令与读数）

```bash
$ cd /private/tmp/zbot-wt-p15b && git log --oneline -2
2af9174 wip(P15b): 封存被 150 轮截断的写手工作区（四杠读数已在盘，缺 EVIDENCE.md）
13027f4 docs(roadmap): 记内核 0.2.1 与抬 pin 的四杠实测，订正 3ed5bf4 里两处数（11 个模块、阳性对照 43 行）

$ git rev-parse --abbrev-ref HEAD
w1-p15b

$ git status --short            # 收口前：只有一个未跟踪的 __pycache__（脚本产物，已删）
?? _doc/005_testing/acceptance/p15b/__pycache__/
```

原始工单：`~/.cache/zbot-p17/dispatch_p15b.md`（四杠口径、"要/不要/占位"硬规则、红线 1/2）。

---

## 1. 杠① —— `mvn -o test` 连续 3 跑

**命令**（本写手重跑，日志落仓库内不写 `/tmp`；与工单杠① `rm -rf z-bot-core/target/surefire-reports && mvn -o test` 逐字同口径，只是把三跑的日志分别落盘）：

```bash
cd /private/tmp/zbot-wt-p15b
for r in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; \
  mvn -o test > _doc/005_testing/acceptance/p15b/logs/recalc_gang1_test_r$r.log 2>&1; \
  echo "r$r RC=$?" >> _doc/005_testing/acceptance/p15b/logs/recalc_gang1_loop.log; done
```

三跑退出码：

```bash
$ cat _doc/005_testing/acceptance/p15b/logs/recalc_gang1_loop.log
r1 RC=0
r2 RC=0
r3 RC=0
```

**决定性读数**（命令：`grep -E 'Tests run: 475|BUILD SUCCESS' _doc/005_testing/acceptance/p15b/logs/recalc_gang1_test_r1.log …r2.log …r3.log`，三跑原文）：

```
recalc_gang1_test_r1.log:[INFO] Tests run: 475, Failures: 0, Errors: 0, Skipped: 0
recalc_gang1_test_r1.log:[INFO] BUILD SUCCESS
recalc_gang1_test_r2.log:[INFO] Tests run: 475, Failures: 0, Errors: 0, Skipped: 0
recalc_gang1_test_r2.log:[INFO] BUILD SUCCESS
recalc_gang1_test_r3.log:[INFO] Tests run: 475, Failures: 0, Errors: 0, Skipped: 0
recalc_gang1_test_r3.log:[INFO] BUILD SUCCESS
```

本期新增的那 10 条守卫**真在 475 里**（不是只写在盘上没接进 suite）：

```bash
$ grep -n 'Tests run.*SessionsColumnAlignmentTest' _doc/005_testing/acceptance/p15b/logs/recalc_gang1_test_r1.log
307:[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.050 s -- in com.zifang.z.bot.store.SessionsColumnAlignmentTest
```

- 475 = 三跑同值、BUILD SUCCESS 三跑同现 ⇒ 无间歇。
- **475 与另一支在飞的 P12 分支条数相同纯属巧合**；这 475 是 `w1-p15b` @ `2af9174` 的测试数，不是"P12 的测试"。
- 基线对照（上一棒留的进树前读数，仅线索，未重跑）：`logs/gang1_baseline_r1.log`。

---

## 2. 杠② —— 注入自证（8 支）

**尺的读数**（不重跑注入脚本 —— 工单明令，且锁是别人的；只机械读台账）：

```bash
$ python3 -u -c "
import csv,collections
rows=list(csv.DictReader(open('_doc/005_testing/acceptance/p15b/LEDGER.tsv'),delimiter='\t'))
print('N ROWS =',len(rows))
print('verdict =',dict(collections.Counter(r['verdict'] for r in rows)))
print('restored =',dict(collections.Counter(r['restored'] for r in rows)))"
N ROWS = 8
verdict = {'PARTIAL': 3, 'RED-OK': 5}
restored = {'ok': 8}
```

⇒ **RED-OK 5 / PARTIAL 3 / 0 BROKEN / 0 GREEN-BUT-MUTATED**，8 行 `restored` 全 `ok`。与主编读数一致。
（工单警告成立：`awk -F'\t' '{print $NF}'` 会数到 `restored` 列而不是 `verdict`；这里用 `csv.DictReader` 按列名取。）

**"逐字节还原"我另立了一条独立证据**（不信脚本自己的 `还原=True`）：两支被测文件在**注入之后**的工作区 md5
与 `2af9174` 提交树里的 blob md5 逐一相等 ⇒ 8 支注入确实一个字节都没留下：

```bash
$ for f in _doc/005_testing/acceptance/p15b/sessions_column_alignment.md \
           z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java; do
    printf '%s local=%s committed=%s\n' "$f" "$(md5 -q $f)" "$(git show 2af9174:$f | md5 -q)"; done
_doc/005_testing/acceptance/p15b/sessions_column_alignment.md local=718b807b826e76377db831b537adb6bc committed=718b807b826e76377db831b537adb6bc
z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java local=a536513e6ec9ec57843ccfbbbbe443c1 committed=a536513e6ec9ec57843ccfbbbbe443c1

$ git diff --stat 2af9174 -- <同样两个路径>     # 空输出 = 无差异
$ git status --short                            # 收口时只剩已删的 __pycache__
```

锁的口径（台账原文，`logs/gang2_mutation_final.log` 头两行；"仅线索"，但路径与纪律可核）：

```
锁文件: /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock（git 公共目录 .../.git）
ACQUIRED（等了 0s）
```
⇒ 锁在 **git 公共目录**（不是 worktree 内），跨编队有效。加锁口径我实读到代码行：

```bash
$ grep -n 'flock\|lockf' _doc/005_testing/acceptance/p15b/p15b_mutation.py
21:  worktree 里的路径跨不了编队；只准 `fcntl.flock(LOCK_EX|LOCK_NB)`。
22:  **不许用 `fcntl.lockf`**：主编 09-26 实测，真 JVM 攥着 tryLock 时 python 的 lockf 一律回
167:    """git 公共目录下的 flock(LOCK_EX|LOCK_NB)。拿不到 ⇒ 每 60s 重试，累计 30 分钟后放弃。"""
184:                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
189:                    print("FATAL: flock 异常 %r" % (e,))
315:            fcntl.flock(fd, fcntl.LOCK_UN)
```
⇒ 代码里只有 `flock`（`lockf` 只出现在"不许用"的注释里），且是 `LOCK_EX|LOCK_NB` —— 与工单纪律一致。

### 2.1 八支逐条的账

点名口径：**`named_expected` = 该支预定要红的具名 testcase 数；`named_hit` = 其中真红的条数**；
`tests_that_went_red` 是**实际全红清单**（含多红的）。守卫方法名可在
`z-bot-core/src/test/java/com/zifang/z/bot/store/SessionsColumnAlignmentTest.java` 里 `grep -n` 打到（本写手核过 10 条方法名全在，见 §1 的 10 tests）。

| # | 注入什么（文件 / 锚点） | 期望哪条具名 testcase 红 | 实际红了哪些 | 判定 |
| --- | --- | --- | --- | --- |
| M1 | `doc`：整行删除表里 `chat_id`（判定=占位）那一行 | `T46` `alignmentTableHoldsExactlyHermesFortySixSessionsColumns` | `T46` + `TLEAK`（**多红 1**） | **PARTIAL** |
| M2 | `doc`：`| 有 \`provider\` | 要 |` → `| 有 \`provider\` | 不要 |`（`billing_provider` 改判） | `TLEAK` `rejectedAndDeferredHermesColumnsAreAllAbsentFromOurSchema` | `T46` + `TORPHAN` + `TLEAK`（**多红 2**） | **PARTIAL** |
| M3 | `doc`：整行删除表里 `archived`（判定=要）那一行 | `TORPHAN` + `T46` | `T46` + `TORPHAN`（点名 2/2） | RED-OK |
| M4 | `doc`：`id` 行消费者 `StateStore` → `StateStor`（不存在的类） | `TWANT` + `everyWantedRowConsumerClassIsGreppableInMainSource` | 只有 `everyWantedRowConsumerClassIsGreppableInMainSource`（点名 1/2） | RED-OK |
| M5 | `doc`：`user_id` 行期号 `P16 解锁（通道身份与路由）` → `以后再说` | `TPHASE` `everyPlaceholderRowNamesThePhaseThatUnlocksIt` | `TPHASE`（1/1） | RED-OK |
| M6 | `doc`：`cwd` 行的实测串 → `大概我们不需要因为` | `TEMP` `everyRejectedRowCarriesAnEmpiricalMarker` | `TEMP`（1/1） | RED-OK |
| M7 | `sm`：**两段式偷渡** —— `SESSIONS_ADDED.put("session_key","TEXT")` + head 声明尾部加 `"session_key"` | `TORPHAN` + `TLEAK` | `TORPHAN` + `TLEAK` + `preP15DatabaseUpgrades…` + `reopeningAnUpgradedDatabaseAddsNothingFurther`（**多红 2**） | **PARTIAL** |
| M8 | `doc`：`| 有 \`id\` | 要 |` → `| 有 \`identifier\` | 要 |`（表承诺一个 head 里没有的列） | `TWANT` + `TORPHAN` | `TWANT` + `TORPHAN`（2/2） | RED-OK |

`T46 / TWANT / TORPHAN / TLEAK / TPHASE / TEMP` 的实名以 `p15b_mutation.py:71-76` 的常量为准。
所有 8 支 `rc=1`（`logs/gang2_mutation_final.log` 每行末尾的 `| rc=1 |`）⇒ 没有一支"注入后仍全绿"。

### 2.2 三支 PARTIAL：多红的那条是什么、为什么合理（**不改写成 RED-OK**）

先给尺的定义（本写手实读 `SessionsColumnAlignmentTest.java:319-340`，不是转述）：
`TLEAK` 有两半 —— ① `assertEquals("要核的 不要/占位 行应是 32 行", 32, checked)`，② 任何判 `不要/占位` 的 hermes 列若出现在我们 head 里就算偷渡。

1. **M1 多红 `rejectedAndDeferredHermesColumnsAreAllAbsentFromOurSchema`。**
   删掉 `chat_id`（占位）那一行，`不要+占位` 的行数从 32 掉到 31 ⇒ 撞上 `TLEAK` 的第①半（`checked==32`）。
   **合理**：这张表是一笔差额账（`46 = 14 要 + 32 不要/占位`），`T46` 钉总数、`TLEAK` 钉差额分母；
   少一行必须**两边同时红**才是对的 —— 只有 `T46` 红意味着差额账没人对账。这不是过度波及，是守卫真的成对。

2. **M2 多红 `alignmentTableHoldsExactlyHermesFortySixSessionsColumns` 与 `headDeclaresNoSessionsColumnThatTheTableDoesNotAccountFor`。**
   `billing_provider` 对应的我们侧列 `provider` **确实在 head 的 15 列里**，把它从"要"改成"不要"：
   ⇒ `T46` 红（判定分布从 要14/不要14/占位18 变成 13/15/18，分布是被钉死的）；
   ⇒ `TORPHAN` 红（head 里的 `provider` 从此没有"要"行认领 = 无人认领的列，正是红线 2 的形态）；
   ⇒ `TLEAK` 红（预定那条，`provider` 判"不要"却活在 schema 里 + 分母变 33）。
   **合理**：一支改判必须惊动"分布 / 孤儿 / 偷渡"三层；少红任何一层都说明有一层是死的。

3. **M7 多红 `preP15DatabaseUpgradesWithoutLosingRowsAndItsColumnsMatchHead` 与 `reopeningAnUpgradedDatabaseAddsNothingFurther`。**
   M7 是**唯一动产品代码**的一支，真往 `sessions` 偷渡了 `session_key`（既进 `SESSIONS_ADDED` 又进 head 声明）。
   ⇒ 前者红（`test:425-428` 把加列台账硬钉成那 4 列 `parent_session_id/ended_at/end_reason/archived`，偷渡后变 5 列）；
   ⇒ 后者红（`test:477` 硬断言 `assertEquals(15, pragmaColumns(f).size())`，偷渡后是 16）。
   **合理且正是这一支的目的**：工单要求"正证/反空跑"，即**必须真加一列**来证明守卫不是空转；
   升级/幂等两条守卫跟着红，恰好证明"0 消费者的列一塞进 schema，就会在迁移层也被当场抓住"。

⇒ 结论：**8 支里 5 支 RED-OK、3 支 PARTIAL 且多红的每一条都能落到守卫的具体断言行**，
0 BROKEN、0 GREEN-BUT-MUTATED、0 支被"改期望集"洗过。三支 PARTIAL 保留原判读，未改写。

---

## 3. 杠③ —— 真进程 E2E

**命令**（重跑要建 jar 且抢锁，工单禁止；本写手取盘上最终日志并核其自证结构）：

```bash
$ tail -n 6 _doc/005_testing/acceptance/p15b/logs/gang3_e2e_final.log
```

**决定性读数（原样）：**

```
PASS   S5a stub 真在被测 profile 目录里（钉住'S5 不是空跑'）                         只比对存在性，不打印值；cfg 文件数=4
PASS   S5b 产物里没有任何真 key 的痕迹（无 125 字符值、无 sk- 前缀）                       cfg 字节数=72
红线1 跑后: entries=8 md5={'config.properties': '2dadaed0', 'state.db': '690ddbc0'}
PASS   S6 红线1：~/.zbot 跑前后一模一样（8 项 + 两个 md5 前缀未变）                      {'entries': 8, 'md5': {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}} → {'entries': 8, 'md5': {'config.properties': '2dadaed0', 'state.db': '690ddbc0'}}

== P15b 杠③ 真进程 E2E: 32/32 通过 ==
```

⇒ **32/32**。条数核对：

```bash
$ grep -c '^PASS' _doc/005_testing/acceptance/p15b/logs/gang3_e2e_final.log
32
```

**"二次开库不重复迁移/不丢行/台账字节一致"那四条（S4a–S4d，原样贴）：**

```
PASS   S4a 第二次开库列数不变（15→15）                                           15→15
PASS   S4b 第二次开库那行只报 3->3、不再报加了列                                      本次开库迁移: schema 3->3
PASS   S4c 二次开库仍不丢行（sessions=2）                                       实测 sessions=2
PASS   S4d 二次开库没改写迁移台账（state_meta.schema_last_migration 字节一致 ⇒ 真没再跑阶梯） 台账 497 字节未变
```

**反向断言（钉住 S5 不是空跑，原样）：** S5a 那行就是 —— 先证明 stub key 真在被测 profile 目录里
（`cfg 文件数=4`，只比对存在性、不打印值），S5b 才有资格说"产物里无真 key 痕迹"。
没有 S5a，S5b 就是空跑。

**S3k（FTS 那条）原文，边界要按它自己说的读：**

```
PASS   S3k stats 的 sessions= 与盘上行数一致（真读了这张表）                          盘上=2 | CLI=sessions=2 messages=2 tokens=0 api_calls=0 / db=... fts=on
```
⇒ 它证的是"**CLI 真读了 `sessions` 这张表**（盘上 2 行 ↔ `sessions=2`）"；`fts=on` 只是那行 CLI 输出的附带状态串，**不是**对 FTS 索引内容/召回量的断言（见 §5）。

**E2E 自己的负向对照**（`logs/gang3_e2e_negative.log`，证明这 32 条不是恒绿）：

```
N1 head 少钉一列             rc=1  FAIL=['S2d 升级后 PRAGMA 列**与顺序**都 == head 的 15 列']
N2 谎报盘上 archived         rc=1  FAIL=['S3e `sessions archive` 真写盘上 archived=1']
N3 CLI 空跑                rc=1  FAIL=['S2b 日志里本次跑了 3 步并点名加了 4 列（仅作线索，判定看 S2c/S2d 盘上）', 'S2c ...', ... 共 18 条，见下面命令]
NEGATIVE-CONTROL PASS: rc=1/1/1（期望全非 0）
```

N3 牵出的条数我是**量**出来的，不是数省略号（列表里混用单/双引号，肉眼数会数错 —— 我第一遍就数成 19）：

```bash
$ python3 -u -c "
import ast
line=[l for l in open('_doc/005_testing/acceptance/p15b/logs/gang3_e2e_negative.log') if l.startswith('N3')][0]
lst=ast.literal_eval(line.split('FAIL=',1)[1].strip())
print('N3 FAIL item count =', len(lst)); print('first =',lst[0]); print('last =',lst[-1])"
N3 FAIL item count = 18
first = S2b 日志里本次跑了 3 步并点名加了 4 列（仅作线索，判定看 S2c/S2d 盘上）
last = S4d 二次开库没改写迁移台账（state_meta.schema_last_migration 字节一致 ⇒ 真没再跑阶梯）
```
⇒ 三种"造假"（少列、谎报写盘、CLI 空跑）都被抓住，N3 一次牵出 **18** 条 ⇒ 判定链是连在盘上事实上的。

---

## 4. 杠④ —— `~/.zbot` 红线（本写手亲测）

**命令（只取 `ls -A | wc -l` 与两个 md5 前缀 8 位；不读内容、不打印任何值）：**

```bash
$ cd ~/.zbot && ls -A | wc -l
       8
$ md5 -q config.properties | cut -c1-8
2dadaed0
$ md5 -q state.db | cut -c1-8
690ddbc0
```

**与日志读数逐字一致**（`cat _doc/005_testing/acceptance/p15b/logs/gang4_redline_final.log`）：

```
=== 杠④ ===
       8
2dadaed05e0cefe7a53f48baba3c403a
690ddbc0e0e35f3a9dc183302f1d5182
```
⇒ `entries=8`、`config.properties` md5 前缀 `2dadaed0`、`state.db` md5 前缀 `690ddbc0` 三项**由本写手独立复现**。
杠③ 日志里"跑前 / 跑后"两次读数亦为同一组（§3 引用块）⇒ 真进程 E2E 没碰过 `~/.zbot`。
`minimax.api.key` 的值**从未被读、未被打印、未进任何产物**（S5b 反向钉住）。

---

## 5. 硬规则的对账（尺是 `p15b_check.py`，不是我手敲）

**命令与读数（本写手重跑）：**

```bash
$ python3 -u _doc/005_testing/acceptance/p15b/p15b_check.py; echo "rc=$?"
参照: /Users/zifang/.hermes/hermes-agent @ cbc1054e2 ⇒ 机械摘到 46 列（DDL 起始 hermes_state.py:872，末列 :918）
文档: _doc/005_testing/acceptance/p15b/sessions_column_alignment.md ⇒ 表 46 行
我们: head sessions 15 列 = id,title,source,model,provider,message_count,tokens,api_calls,created_at,updated_at,metadata,parent_session_id,ended_at,end_reason,archived
判定分布: 要=14 不要=14 占位=18（合计 46）
要 行的消费者类: SessionsCommand StateStore（2 个）

PASSED: yes
rc=0
```

**参照仓三数（工单要求动手前复算，本写手在 `~/.hermes/hermes-agent` 实测）：**

```bash
$ git rev-parse --short HEAD      → cbc1054e2        # 与工单/路线图钉的一致
$ wc -l < hermes_state.py         → 9503            # 工单预期 9503 ✅
$ grep -cE 'CREATE (VIRTUAL )?TABLE' hermes_state.py → 22   # 工单预期 22 ✅
$ git status --porcelain | wc -l  → 0               # 参照仓干净（没被 pull / 没被写）
$ grep -n 'CREATE TABLE IF NOT EXISTS sessions (' hermes_state.py
872:CREATE TABLE IF NOT EXISTS sessions (
```
末列位置也核了：`sed -n '918p'` = `archived INTEGER NOT NULL DEFAULT 0,`，`:919` 起才是 `FOREIGN KEY` ⇒ **列声明 872–918，46 列**。
（`logs/gang4_redline_final.log` 末尾"参照仓三数"里那个 `8` 是 `~/.zbot` 的条目数，不是 CREATE TABLE 数 —— 别拿它当 22 的矛盾。）

**三类判定我逐行独立复算过**（自带解析器，按 `p15b_check.py:487` 的 `split_row` 同一条规则处理转义 `\|`）：

```bash
# 表体 = 104..149 行，每行 6 格；判定分布 要14 / 占位18 / 不要14，合计 46（与尺逐字相同）
```

- **判定=`要` 的 14 行，每行的消费者类都 `git grep`/`git ls-files` 打得到**（缺失数 = 0）：
  `id, source, model, parent_session_id, started_at, ended_at, end_reason, message_count, input_tokens, output_tokens, billing_provider, title, api_call_count, archived`
  用到的类只有两个，且都在 `src/main/java` 下真存在：

  ```bash
  $ git ls-files -- '*src/main/java/*' | grep -E '/(StateStore|SessionsCommand)\.java$'
  z-bot-core/src/main/java/com/zifang/z/bot/store/StateStore.java
  z-bot-core/src/main/java/com/zifang/z/bot/cli/SessionsCommand.java
  ```
  （`end_reason` 一行只点名 `StateStore`；其余 13 行点名 `StateStore`+`SessionsCommand`。）

- **判定=`占位` 的 18 行，每行都点名了 `P\d+`，无一写"以后再说"**（无期号数 = 0）。
  按期号聚合 = **P12 = 7、P14 = 4、P16 = 7**（合计 18）：
  * P16（通道身份与路由，7）：`user_id, session_key, chat_id, chat_type, thread_id, display_name, origin_json`
  * P12（token/成本分档 6 + prompt cache 冻结 1，7）：`system_prompt, billing_mode, estimated_cost_usd, actual_cost_usd, cost_status, cost_source, pricing_version`
  * P14（血统/压缩，4）：`model_config, compression_failure_cooldown_until, compression_failure_error, compression_fallback_streak`
  ⇒ 与 `SchemaMigrations` 新类注释里那句"P12 7 列、P14 4 列、P16 7 列"逐字对得上（不是我对出来的巧合，是两处必须同步，M 系注入改一处会红）。

- **判定=`不要` 的 14 行，理由格全部带"实测"串**（缺失数 = 0），且落在我能复现的零命中上（本写手实跑）：

  ```bash
  $ for p in rewind handoff reasoning repoRoot cache_read streak; do
      printf '%-12s files_with_hit=%s raw_hits=%s\n' "$p" \
        "$(git grep -icE "$p" -- z-bot-core/src/main/java | wc -l | tr -d ' ')" \
        "$(git grep -oiE "$p" -- z-bot-core/src/main/java | wc -l | tr -d ' ')"; done
  rewind       files_with_hit=0 raw_hits=0
  handoff      files_with_hit=0 raw_hits=0
  reasoning    files_with_hit=0 raw_hits=0
  repoRoot     files_with_hit=0 raw_hits=0
  cache_read   files_with_hit=0 raw_hits=0
  streak       files_with_hit=0 raw_hits=0
  ```
  ⇒ 两种口径都量了（`-c` 数文件、`-o` 数原始命中），**六个词在 `src/main/java` 都是 0 命中**。
  `handoff` 在 HEAD 是 0 命中（见 §6 第 2 条，它在 `2af9174` 的 javadoc 改写后从 1 变 0）。
  另核 `metadata`（我们自己的 0 消费者存量列）：`git grep -c 'metadata' -- z-bot-core/src/main/java` 只落在
  `SchemaMigrations.java`（DDL 声明 + head 清单，2 处）与两个无关文件（`SkillLoader`/`Confirmations`），
  `StateStore.java` **0 处** ⇒ "sessions.metadata 无写路径"成立。

**尺自己的负向对照**（`logs/gang1_ruler_negative.log`，证明这道保鲜尺不是摆设）：

```
R1 删一行(chat_id)  rc=1 首条=行数不符：文档 45 行 vs 参照 46 列
R2 占位行去掉期号     rc=1 首条=session_key[占位] 判定=占位 却写了禁用语 '以后再说'
R3 不要行删掉'实测'   rc=1 首条=rewind_count[不要] 判定=不要 但理由里没有『实测』证据串
R4 消费者类名漂      rc=1 首条=archived[要] 消费者 StateStoreDoesNotExist 在 src/main/java 下不存在（表里写了个不存在的类）
R5 表格混进裸 |     rc=2 首条=...表格行有 7 格，不是 6 格：'archived'
R6 参照仓不在（空集）  rc=2 首条=参照仓 hermes_state.py 找不到（试过：/private/tmp/does-not-exist-ref）
R7 参照仓 HEAD 漂了  rc=2 首条=参照仓 HEAD=deadbeef0 != 钉住的 cbc1054e2 ⇒ 参照已漂，本表全部结论需重算
RULER-NEGATIVE-CONTROL PASS: rc=[1, 1, 1, 1, 2, 2, 2]（期望全非 0；R6/R7 期望 2=FATAL）
```
⇒ R6/R7 的 FATAL 成立，"参照集为空 ⇒ 打满分跳过"这一类空跑被堵死。

---

## 6. 复算中发现的**表内瑕疵**（与主编读数不冲突，但必须点名；我无权改上一棒的表）

工单硬规则是"`不要` 的行要给实测理由"，尺只强制"理由格含『实测』二字"，**不复核那句实测的数值**。
本写手逐条复跑 `不要` 行的 grep，量出两处对不上：

1. **`cwd` 行（表第 128 行）说"实测 `git grep -in '\bcwd\b' z-bot-core/src/main/java` 0 命中"，实测是 6 命中：**

   ```bash
   $ git grep -in '\bcwd\b' -- z-bot-core/src/main/java | wc -l
   6
   ```
   6 处全文：`CheckpointManager.java:203`（注释）/`:211`（`private String run(File cwd, String[] cmd)`）/`:213`（`pb.directory(cwd)`）、
   `BuiltinTools.java:417`（`bash(Sandbox cwd, …)`）/`:419`/`:420`。
   ⇒ 全是**子进程工作目录的句柄**（git / bash 起进程用），不是会话级事实，所以**判定=不要 的结论与"会话级列恒等、0 消费者"不变**，
   但那一格里写的**数字（0 命中）措辞不成立**，应订正为"6 命中且全部是进程/子进程级 cwd，无会话级读写"。
2. **`handoff` 的实测串已漂**：尺的生成文本（`p15b_check.py:347`）与表体写"只有 1 处命中，且是 `SchemaMigrations.java`"，
   那是**改写前**的事实。本期把类注释重写后：`git grep -in 'handoff' -- z-bot-core/src/main/java | wc -l` 在 `2af9174` = **0**、在父提交 `13027f4` = **1**（本写手两值都跑过）。
   ⇒ 方向是"更保守"（命中更少），不影响判定，但同样需要主编收口时一并订正。

这两条**我没有改**：`sessions_column_alignment.md` 与 `p15b_check.py` 是上一棒的交付物，工单只授权我写 `EVIDENCE.md`
（且"不许碰别人的文件"）。**尺的缺口**也如实记在这里：`everyRejectedRowCarriesAnEmpiricalMarker` 只认串不认数 ⇒ 这类漂**不会被 mvn test 或 p15b_check 抓住**。

---

## 7. 产品代码面的账（工单第 3 件交付物的实测）

```bash
$ git diff --stat 13027f4 2af9174 -- z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java
 .../java/com/zifang/z/bot/store/SchemaMigrations.java  | 18 +++++++++++++-----
 1 file changed, 13 insertions(+), 5 deletions(-)
```
⇒ **+13/-5**，与主编读数一致。删掉的 5 行**逐行是 javadoc**（本写手全量核对，非抽样）：

```bash
$ git diff 13027f4 2af9174 -- <该文件> | grep '^-' | grep -v '^---'
- * <p>她 46 列（{@code hermes_state.py:872-919}）。<b>本期只加有消费者的 4 列</b>：
- * {@code parent_session_id}（P14 血统，硬交付）、{@code archived}（软归档）、
- * {@code ended_at} + {@code end_reason}（prune 的"已结束/在飞"闸门）。其余 30+ 列按
- * "计费 / 缓存 token 分档 / 平台对端标识 / handoff / telegram 主题" 逐列判"不要"或"占位后移"，
- * 理由表见本期 notes——无理由堆列等于把红线 2 的"0 消费者抽象"搬进 schema。</p>

# 反向筛：所有增删行里有没有非注释行？
$ git diff 13027f4 2af9174 -- <该文件> | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' \
    | grep -vE '^[+-] \* ' | grep -vE '^@@'
（无输出）⇒ 全部增删行都是 javadoc 注释行
$ git diff 13027f4 2af9174 -- <该文件> | grep -c '^@@'
1        ⇒ 唯一一个 hunk，全在类注释块内（迁移 step 的字节未动）
```
其中那句 **"理由表见本期 notes"正是本单要结的账** —— 新注释改为指向真文件
`_doc/005_testing/acceptance/p15b/sessions_column_alignment.md`（工单"欠账原文"点名的就是这一句）。

**本期进产品代码的新列 = 0 个**（不是"没敢加"，是判定=要的 14 列本来就全在已有 15 列之内）；
整棵树的产品代码改动面只有这一个文件（`git diff --stat 13027f4 2af9174` 共 7 个文件：4 个 `_doc/005_testing/acceptance/p15b/*` + 1 个 `LEDGER.tsv` + 1 个 `SchemaMigrations.java` 注释 + 1 个新单测）。

---

## 8. 本期没做的（别当成做了）

按本写手实测到的实情写，逐条给依据：

1. **没加任何列。** 判定=要 的 14 列**全部已在 head 的 15 列里**（`p15b_check.py` 打印的那 15 列），
   其中 4 列（`parent_session_id/ended_at/end_reason/archived`）是 **P15** 加的，不是本期。
   ⇒ 红线 2 在本期的正确执行方式就是"一个都不加"（§7 的 `+13/-5` 全是注释是它的实测）。
   **没做的事**：`要` 里的 `billing_provider / api_call_count / input_tokens / output_tokens` 只是"语义已收"，
   真值接线（生产写点恒传 `null/0`）**本期没做**，推 **P12**。三个写点我打到了：

   ```bash
   $ sed -n '99p;164p;224p' z-bot-core/src/main/java/com/zifang/z/bot/session/SessionManager.java
               store.upsertSession(id, sd.title, null, null, 0, 0, 0);
               store.upsertSession(sessionId, sd.title, null, null, sd.messageCount, 0, 0);
               store.upsertSession(id, title == null ? "新会话" : title, null, null,
   ```
2. **32 行没认领的列归别人，不归本期**：18 行"占位"分别挂 **P12=7 / P14=4 / P16=7**（§5 有逐列名单），
   14 行"不要"是**永久不认领**（内核无数据源 / 进程级事实 / 功能不存在）。本期没有为任何一期预建列、预写 getter。
   `metadata` 这条**我们自己违反红线 2 的 0 消费者存量列仍在库里**（实测 `StateStore.java` 0 处 `metadata`），
   **本期没删** —— 删它要一次授权明确的 `DROP COLUMN` 阶梯。
3. **老库升级只在 darwin 的这一个 sqlite 上量过。** 实测环境：

   ```bash
   $ python3 -c "import sys,sqlite3,platform; print(sys.platform, platform.mac_ver()[0], sqlite3.sqlite_version)"
   darwin 26.5.1 3.50.4
   $ java -version | head -1   → openjdk version "25.0.2" 2026-01-20 LTS
   ```
   ⇒ S1/S2 那条 `11 列 → 15 列` 的升级链、以及 `p15-timestamp-normalization` 对 ISO `'T'` 的就地规范化，
   **只在 macOS + sqlite 3.50.4 + JDK25 这一组上实测过**；Linux/CI、别的 sqlite 版本**一次都没跑过**，
   不要当成跨平台已验证。（`requiredColumns`/阶梯是纯 SQL 文本，但 `ALTER TABLE` 与时间戳字典序比较的行为我没在第二套环境上证。）
4. **FTS：S3k 只证了"CLI 真读了 `sessions` 这张表"**（盘上 2 行 ↔ `sessions=2`）。
   那行输出末尾的 `fts=on` 是状态串，**本期没有**对 FTS 索引内容、召回、增量维护做任何断言；
   `messages` 侧的 FTS 对齐不在本单。
5. **只对了 `sessions` 一张表。** 参照仓实测有 **22 张表**（`grep -cE 'CREATE (VIRTUAL )?TABLE' hermes_state.py` ⇒ 22），
   本期按工单边界只做了 `sessions` 的 46 列；`messages / session_model_usage / gateway_routing` 等列对齐**没做**。
6. **没有 FK、没有改名改型。** 她 `parent_session_id` 带 `FOREIGN KEY … REFERENCES sessions(id)`（`hermes_state.py:919`），
   我们只有 `idx_sessions_parent` + 应用层孤儿清理；她的 `REAL` epoch 秒我们保持 `TEXT`。本期**没动**这两个表示差异（文档 §5 记为刻意）。
7. **Java 单测里没有跨仓对齐。** 46 行的表**没有**被塞进 `SessionsColumnAlignmentTest`（那会让 `mvn -o test` 依赖参照仓在场）。
   分工是：Java 单测钉**我们侧不变量**（列集/阶梯台账/无新列），`p15b_check.py` 钉**跨仓对齐**。
   ⇒ 在别人机器上 `mvn test` 全绿**不等于**表还对着参照仓 —— 必须另有 `python3 _doc/005_testing/acceptance/p15b/p15b_check.py`（rc=0）这一条。
8. **尺的实测串不复核数值**（§6）：`不要` 行里的 grep 命中数是**人写的字面量**，
   本期**没有**把"这些 grep 真跑一遍"接进尺 ⇒ `cwd`（0 vs 实测 6）、`handoff`（1 vs HEAD 实测 0）两处漂只有本报告这一层证据。
9. **没动 roadmap。** 工单"不要编辑 `_doc/001_arch/hermes-roadmap.md`"成立：`git diff --stat 13027f4 2af9174` 里**没有**该文件。
   §8 那条"待办 · P15 未交部分"的**销账由主编收口时处理**。
10. **`mvn -o test` 三跑之外我没有再跑注入或 E2E**（工单明令：锁是别人的、另有三支在飞）。
    杠② 的 8 支、杠③ 的 32/32 是本写手**对台账/日志的核对 + 独立可复现的那部分亲测**
    （还原用 md5 自立证据、E2E 用条数与负向对照结构），**不是一次重跑**。这一条别当成"四杠我都重跑了"。

---

## 9. 本写手留下的可复算命令清单

```bash
# 杠①（重跑，约 3×50s）
for r in 1 2 3; do rm -rf z-bot-core/target/surefire-reports; \
  mvn -o test > _doc/005_testing/acceptance/p15b/logs/recalc_gang1_test_r$r.log 2>&1; done
grep -H 'Tests run: 475\|BUILD SUCCESS' _doc/005_testing/acceptance/p15b/logs/recalc_gang1_test_r{1,2,3}.log

# 杠②（只读台账）
python3 -c "import csv,collections;rows=list(csv.DictReader(open('_doc/005_testing/acceptance/p15b/LEDGER.tsv'),delimiter='\t'));\
print(len(rows),dict(collections.Counter(r['verdict'] for r in rows)),dict(collections.Counter(r['restored'] for r in rows)))"

# 杠③ / 杠④
grep -c '^PASS' _doc/005_testing/acceptance/p15b/logs/gang3_e2e_final.log
cd ~/.zbot && ls -A | wc -l && md5 -q config.properties | cut -c1-8 && md5 -q state.db | cut -c1-8

# 保鲜尺 + 参照仓
python3 -u _doc/005_testing/acceptance/p15b/p15b_check.py; echo rc=$?
cd ~/.hermes/hermes-agent && git rev-parse --short HEAD && wc -l < hermes_state.py \
  && grep -cE 'CREATE (VIRTUAL )?TABLE' hermes_state.py
```
