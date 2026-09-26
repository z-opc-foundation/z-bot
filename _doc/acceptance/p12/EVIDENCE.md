# P12 验收证据（预算台账 / 中断收口 / steer 排空 / system prompt 快照冻结）

棒：p12c（续 p12b / p12）。分支 `w2-p12`。基线 commit：`809b927`（起）。
本文件是**唯一入仓的证据载体**——`*.log` 被 `.gitignore:5` 排除，所有决定性读数原文粘在下面，并附产生它的命令。

> 状态：骨架（写作中）。每一节填完后当场 commit；未填一节写 `UNKNOWN`，不写推测数字。

---

## 0. 起讫与盘上状态

- 起：`809b927`
- 讫：UNKNOWN（收尾时回填 `git rev-parse --short HEAD`）
- `git status --porcelain`：UNKNOWN（收尾时须为空）

---

## 1. 杠①：全量单测串行三跑

UNKNOWN —— 需要 `rm -rf z-bot-core/target/surefire-reports && mvn -o test` 在最终 HEAD 上串行三跑，
每跑粘 `Tests run:` 行原文 + `BUILD SUCCESS`/`BUILD FAILURE` 行 + shell rc。

---

## 2. 杠②：注入自证（`p12_mutation.py`）与 LEDGER.tsv

UNKNOWN —— 期望注入集**先写死再跑**；`LEDGER.tsv` 只由脚本生成；非 RED-OK 逐条给因果。

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
`~/.cache/zbot-p17/probe_k2/harness`，仓里的 `out/`、`logs/` 一个字节都没动；
复算：`python3 -u ~/.cache/zbot-p17/probe_k2/two_way.py`，rc=0）：

```
A  两形态并存（都只含 stub）        期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
A2 同现场再跑一遍（稳定性）         期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
B  混入别的 key 值                 期望=FAIL 实测=FAIL OK  | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
B2 混入别的 Bearer 值              期望=FAIL 实测=FAIL OK  | 见到的 key 值=['FAKELEAKEDVALUE999', 'stub-key-not-real']
C  只有 stub 一种形态              期望=PASS 实测=PASS OK  | 见到的 key 值=['stub-key-not-real']
D  产物里配不到任何 key（空跑）      期望=FAIL 实测=FAIL OK  | 见到的 key 值=[]
F  api.key=not-configured         期望=FAIL 实测=FAIL OK  | 见到的 key 值=['not-configured', 'stub-key-not-real']
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

c1/c2 逐跑 rc 取自索引文件（`logs/e2e_c1_index.txt`、`logs/e2e_c2_index.txt`）：

```
run1 rc=0 / run2 rc=0 / run3 rc=0 / run4 rc=0 / run5 rc=0     （两批各五条，共 10 条）
```

⇒ **run7 的绿不是运气，但只跑过一次确实是测量不足**；本棒补到 10 跑，K2 零复发。
裁决一句话：**这条间歇红是量具 self-bug（已修，修在 run7 之前 28 秒），不是产品缺陷；
判据本身不动。**

### 3.3 其余段的读数

UNKNOWN —— 待最终 HEAD 上再整跑一批，把 stop/repl/cache/home/creds 五段的逐条 PASS 行原文贴这里。

---

## 4. 杠④：`~/.zbot` 零污染

UNKNOWN —— 三个数：`ls -A ~/.zbot | wc -l`=8、config md5 前 8 位=`2dadaed0`、state.db 前 8 位=`690ddbc0`。

---

## 5. 本期发现的产品级缺陷（未修）

UNKNOWN

---

## 6. 量具自己坏过的记录

| # | 哪一条 | 坏法 | 后果（为什么上一棒会误判） | 现状 |
|---|---|---|---|---|
| G1 | 杠③ K2 | `re_iter()` 早期"有捕获组也取 `group(0)`" ⇒ 把整串 `Bearer stub-key-not-real` / `api.key=stub-key-not-real` 当成 key 值收进集合，与 `STUB_KEY` 永远对不上 | run5/run6 各红 1 条，读数长得像"产物里混进了两种形态"；主编据这两条读数写下"同一形态的量具连跑 3 次里 2 次红"，但 6 次红跑用的是**旧字节**、run7 用的是**新字节**，两批不是同一把尺 | 已修（03:47:05 那版）。本棒用 `two_way.py` 双向实测复算，判据不动 |
| G2 | 杠③ K2 | 先把文本截成 80 字符窗口再去配 key ⇒ 窗口切在串中间配出半截值 `stub-key-` | run2 假红（`见到的 key 值=['stub-key-','stub-key-not-real']`） | 已修（改在整篇文本上配） |
| G3 | 杠③ 脚本本体 | run3/run4 那两版正则括号写坏 ⇒ `SyntaxError`，整跑 0 条检查 | 读数里出现"检查条数=0"，容易被当"没跑"或"跑了全绿" | 已修；两跑原文记在 §3.2 |
| G4 | 杠③ K3/K2 扫描面 | 扫描根 `os.walk(HERE)` 会把 **harness 自己的读数文件**（`out/e2e_full.json`、`logs/e2e_full_run1.log`）当运行期产物，而里面逐字写着检查项名字（含 `minimax`）⇒ 哨兵吃自己 | run1 的 `K3 … 命中文件=[…/p12_e2e.py]`、run2 的 `K3 全目录命中=3；其中运行期产物命中=[…/out/e2e_full.json, …/logs/e2e_full_run1.log]` 都是这一条 | 已加排除（`e2e_full`/`e2e_stop_only`/basename 含 `_run`）；**残余风险**见 §5 |
| G5 | 本棒自己的探针 | `two_way.py` 的 D 场景第一版把 `001.headers.json`（内含 stub key）也铺进了现场 ⇒ "产物里一个 key 都配不到"这个前提根本不成立，实测 PASS 被我期望成 FAIL | 差点反过来冤枉 K2"吃空跑"。复测（`key_header=False`）后 K2 判 FAIL，与期望一致 | 已修探针；教训按"坏读数也要复测"记这一条 |
| G6 | 杠② LEDGER | 上一版只跟"本次运行开始时的内存快照"对账；如果邻居留了未提交的脏改动，内存快照本身就是脏的 | 还原取证会给出 `SRC_MD5_STABLE=yes` 却仍是脏盘 | 本棒加了第二把尺：逐支注入前后各比一次 `git show HEAD:<path> | md5`，LEDGER 第 11 列 `restore_forensics_vs_git` |

## 7. 本期没做的（别当成做了）

UNKNOWN

---

## 8. 复算命令清单

UNKNOWN

---

## 9. 与 P24 的交接件：system prompt 冻结回归

UNKNOWN
