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

UNKNOWN —— 含 K2（凭证卫生）间歇红的裁决与 ≥5 连跑通过率。

---

## 4. 杠④：`~/.zbot` 零污染

UNKNOWN —— 三个数：`ls -A ~/.zbot | wc -l`=8、config md5 前 8 位=`2dadaed0`、state.db 前 8 位=`690ddbc0`。

---

## 5. 本期发现的产品级缺陷（未修）

UNKNOWN

---

## 6. 量具自己坏过的记录

UNKNOWN

---

## 7. 本期没做的（别当成做了）

UNKNOWN

---

## 8. 复算命令清单

UNKNOWN

---

## 9. 与 P24 的交接件：system prompt 冻结回归

UNKNOWN
