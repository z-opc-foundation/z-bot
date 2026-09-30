#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P15b 变异检验（杠②）—— 把"逐列理由表 ⟷ 我们 schema"的守卫逐个改坏，看有没有**具名测试**判红。

本期进产品代码的东西只有两类：
  (a) `SchemaMigrations.java` 的 sessions 列声明区注释（把"理由表见本期 notes"这句指向空气的话
      改成指向真文件）—— 注释本身杀不死，如实记；
  (b) 新守卫单测 `SessionsColumnAlignmentTest`（把表钉在 head 声明上）。
所以注入面是**被守卫的那两样东西**：理由表的 markdown，和 `SchemaMigrations` 的列声明。

纪律沿用 `_doc/acceptance/p11c/p11c_mutation.py`（不 import 它，免得两批结论互相拖累）：
  * 注入前预检锚点出现次数（锚点漂了是量具坏了，不是代码坏了）；
  * 每个变异体跑完按内存里的原文**逐字节**还原，收尾核对 md5；
  * 判定只认 surefire XML 里的 testcase 名：点名那条红了才算 RED-OK，
    红了别的算 PARTIAL，全绿算 GREEN-BUT-MUTATED，编译不过算 BROKEN；
  * **不许为了跑绿改期望集**；LEDGER.tsv 由本脚本自己机械写出。

互斥锁（编队协议：同一时刻只允许一支注入脚本在飞）：
  锁文件放 **git 公共目录**（`$(git rev-parse --git-common-dir)/zbot-mutlock`），
  worktree 里的路径跨不了编队；只准 `fcntl.flock(LOCK_EX|LOCK_NB)`。
  **不许用 `fcntl.lockf`**：主编 09-26 实测，真 JVM 攥着 tryLock 时 python 的 lockf 一律回
  ACQUIRED ⇒ 空跑。拿不到锁时每 60s 重试，累计 30 分钟仍拿不到 ⇒ 打
  "杠②未跑完：锁被占"并以 rc=3 退出，**绝不伪造读数**。

复算: python3 _doc/acceptance/p15b/p15b_mutation.py [id 子串...]
"""
import errno
import fcntl
import hashlib
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
TESTS = "SessionsColumnAlignmentTest"
LEDGER = os.path.join(HERE, "LEDGER.tsv")
LOCK_NAME = "zbot-mutlock"
LOCK_RETRY_SECONDS = 60
LOCK_GIVEUP_SECONDS = 30 * 60

DOC_REL = "_doc/acceptance/p15b/sessions_column_alignment.md"
SM_REL = "z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java"
SRC = {"doc": DOC_REL, "sm": SM_REL}

# 表里真实存在的行（注入前用 count 预检；漂了就 FATAL，别信下面的读数）
ROW_CHAT_ID = "| chat_id | 消息侧的会话/群 id"
ROW_ARCHIVED = "| archived | 软归档旗"
ROW_BILLING_PROVIDER = "| billing_provider | 计费侧供应商"
ROW_CACHE_READ = "| cache_read_tokens | prompt cache 命中读档"
CONSUMER_ID = "| 有 `id` | 要 | `StateStore`、`SessionsCommand` |"
# user_id 那行独有的锚点（表格是"一行一列"，锚点必须落在同一行内）
ANCHOR_USER_ID_PHASE = "P16 解锁（通道身份与路由） | 她按 (source"
MEASURED_MARK = "实测 `git grep -in '\\bcwd\\b'"

SM_ADDED_ANCHOR = '        SESSIONS_ADDED.put("archived", "INTEGER NOT NULL DEFAULT 0");'
SM_HEAD_ANCHOR = '"parent_session_id", "ended_at", "end_reason", "archived"));'

# M7 的"两段式偷渡"：既真建列（SESSIONS_ADDED）又声称有（head 声明），
# 只改一处会让别的守卫先炸（那是对的，但我要的是"偷渡列"这一件事被精确抓住）。
PAIRS_M7 = [
    (SM_ADDED_ANCHOR, SM_ADDED_ANCHOR + '\n        SESSIONS_ADDED.put("session_key", "TEXT");'),
    (SM_HEAD_ANCHOR, SM_HEAD_ANCHOR[:-4] + '", "session_key"));'),
]

T46 = "alignmentTableHoldsExactlyHermesFortySixSessionsColumns"
TWANT = "everyWantedColumnActuallyExistsInHeadDeclaration"
TORPHAN = "headDeclaresNoSessionsColumnThatTheTableDoesNotAccountFor"
TLEAK = "rejectedAndDeferredHermesColumnsAreAllAbsentFromOurSchema"
TPHASE = "everyPlaceholderRowNamesThePhaseThatUnlocksIt"
TEMP = "everyRejectedRowCarriesAnEmpiricalMarker"

# (id, 文件键, old, new, 期望锚点数, 点名期望红的测试, 说明)
MUTANTS = [
    ("M1 删一行(占位 chat_id)", "doc", None, None, 1, [T46],
     "行数与判定分布都从这一行上掉：少一列必红"),
    ("M2 要→不要(billing_provider)", "doc",
     "| 有 `provider` | 要 |", "| 有 `provider` | 不要 |", 1, [TLEAK],
     "把已经收下的列改判成不收 ⇒ 它的名字会同时出现在 head 与 32 行差额里"),
    ("M3 删一行(要 archived)", "doc", None, None, 1, [TORPHAN, T46],
     "head 里的 archived 从此无人认领 ⇒ 红线 2 守门人必红；行数同时掉"),
    ("M4 消费者类名漂", "doc", CONSUMER_ID,
     CONSUMER_ID.replace("StateStore", "StateStor"), 1, [TWANT, "everyWantedRowConsumerClassIsGreppableInMainSource"],
     "表里写一个不存在的 Java 类 ⇒ 消费者可 grep 那层必红"),
    ("M5 占位去掉期号", "doc", ANCHOR_USER_ID_PHASE,
     "以后再说 | 她按 (source", 1, [TPHASE],
     "占位不许写\"以后再说\"这类无期号措辞"),
    ("M6 不要行删掉实测串", "doc", MEASURED_MARK, "大概我们不需要因为", 1, [TEMP],
     "判\"不要\"必须留实测证据；抹掉证据就该红"),
    ("M7 偷渡一列 0 消费者的 hermes 列", "sm", PAIRS_M7, None, 2, [TORPHAN, TLEAK],
     "正证/反空跑：真往 sessions 加一个表里判\"占位\"的 session_key（既进 SESSIONS_ADDED 又进 head 声明），"
     "守卫必须当场抓住 ⇒ 守卫不是空转"),
    ("M8 表承诺 head 里没有的列", "doc", "| 有 `id` | 要 |", "| 有 `identifier` | 要 |", 1,
     [TWANT, TORPHAN],
     "表不许承诺一个 schema 里没有的列；同时我们的 id 变成无人认领的孤儿列 ⇒ 两条守卫都该红"),
]


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def read(key):
    with open(os.path.join(ZBOT, SRC[key]), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    # 实测教训：崩在这里会把文件截成 0 字节（open("w") 先截断再写）⇒ 类型必须在这里钉死
    if not isinstance(text, str):
        raise AssertionError("拒绝写 %s：内容是 %r，不是 str" % (SRC[key], type(text)))
    with open(os.path.join(ZBOT, SRC[key]), "w", encoding="utf-8") as fh:
        fh.write(text)


def apply_mutation(key, text):
    """返回 (mutated_text, 命中的锚点数)。M1/M3 是整行删除，其余是字面替换。"""
    if key == "M1 删一行(占位 chat_id)":
        return _drop_line_containing(text, ROW_CHAT_ID)
    if key == "M3 删一行(要 archived)":
        return _drop_line_containing(text, ROW_ARCHIVED)
    raise AssertionError("未知的变异体: " + key)


def _drop_line_containing(text, needle):
    kept = []
    hit = 0
    for ln in text.split("\n"):
        if needle in ln and ln.strip().startswith("|"):
            hit += 1
            continue
        kept.append(ln)
    return "\n".join(kept), hit


def run_tests():
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    proc = subprocess.run(
        ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + TESTS,
         "-DfailIfNoTests=false"],
        cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    failing, ran = set(), 0
    if os.path.isdir(REPORTS):
        for name in sorted(os.listdir(REPORTS)):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(REPORTS, name)).getroot()
            except Exception:
                continue
            ran += int(root.get("tests") or 0)
            for tc in root.iter("testcase"):
                if tc.find("failure") is not None or tc.find("error") is not None:
                    failing.add(tc.get("name").split("[")[0])
    return proc.returncode, failing, ran, proc.stdout.decode("utf-8", "replace")


def acquire_lock():
    """git 公共目录下的 flock(LOCK_EX|LOCK_NB)。拿不到 ⇒ 每 60s 重试，累计 30 分钟后放弃。"""
    r = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--git-common-dir"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        print("FATAL: git rev-parse --git-common-dir 失败: %s"
              % r.stderr.decode("utf-8", "replace").strip(), flush=True)
        return None, None
    common = r.stdout.decode().strip()
    if not os.path.isabs(common):
        common = os.path.abspath(os.path.join(ZBOT, common))
    path = os.path.join(common, LOCK_NAME)
    print("锁文件: %s（git 公共目录 %s）" % (path, common), flush=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    started = time.time()
    try:
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                print("ACQUIRED（等了 %.0fs）" % (time.time() - started), flush=True)
                return fd, path
            except OSError as e:
                if e.errno not in (errno.EACCES, errno.EAGAIN):
                    print("FATAL: flock 异常 %r" % (e,))
                    os.close(fd)
                    return None, None
                waited = time.time() - started
                if waited + LOCK_RETRY_SECONDS > LOCK_GIVEUP_SECONDS:
                    print("杠②未跑完：锁被占（已等 %.0fs >= %ds，按纪律不伪造读数）"
                          % (waited, LOCK_GIVEUP_SECONDS), flush=True)
                    os.close(fd)
                    return None, None
                print("锁被占，%ds 后重试（已等 %.0fs）" % (LOCK_RETRY_SECONDS, waited), flush=True)
                time.sleep(LOCK_RETRY_SECONDS)
    except BaseException:
        os.close(fd)
        raise


def precheck(selected):
    bad = []
    for mid, key, old, new, want, _e, _n in selected:
        text = read(key)
        if isinstance(old, list):
            got = sum(1 for a, _b in old if text.count(a) == 1)
        elif mid.startswith(("M1", "M3")):
            needle = ROW_CHAT_ID if mid.startswith("M1") else ROW_ARCHIVED
            got = sum(1 for ln in text.split("\n")
                      if needle in ln and ln.strip().startswith("|"))
        else:
            got = text.count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（盘上已漂，别信下面的读数）:", flush=True)
        for line in bad:
            print("  " + line, flush=True)
        return False
    return True


def main():
    selected = MUTANTS
    if len(sys.argv) > 1:
        selected = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not selected:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:], flush=True)
            return 2
    for k, rel in SRC.items():
        if os.path.getsize(os.path.join(ZBOT, rel)) < 200:
            print("FATAL: %s 小得可疑（%d 字节）⇒ 先确认盘上文件完好"
                  % (rel, os.path.getsize(os.path.join(ZBOT, rel))), flush=True)
            return 2
    if "BEGIN TABLE" not in read("doc"):
        print("FATAL: 理由表缺 BEGIN TABLE 哨兵 ⇒ 注入会把表格块整行删掉，先修文档", flush=True)
        return 2
    if not precheck(selected):
        return 2

    fd, _lock_path = acquire_lock()
    if fd is None:
        return 3

    try:
        baseline = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
        originals = {k: read(k) for k in SRC}
        rows, tally = [], {}
        for mid, key, old, new, want, expected, note in selected:
            original = originals[key]
            if old is None:
                mutated, hits = apply_mutation(mid, original)
            elif isinstance(old, list):
                mutated, hits = original, 0
                for a, b in old:
                    if mutated.count(a) != 1:
                        print("%s: 两段式注入的锚点不唯一 (%d) ⇒ 停机" % (mid, mutated.count(a)),
                              flush=True)
                        return 2
                    mutated = mutated.replace(a, b, 1)
                    hits += 1
            else:
                mutated, hits = original.replace(old, new, 1), original.count(old)
            if hits != want:
                print("%s: 注入时锚点数变了吧? %d != %d ⇒ 停机" % (mid, hits, want), flush=True)
                return 2
            write(key, mutated)
            try:
                rc, failing, ran, out = run_tests()
            finally:
                write(key, original)
            restored = md5(os.path.join(ZBOT, SRC[key])) == baseline[key]
            if "COMPILATION ERROR" in out:
                verdict = "BROKEN"
            else:
                hit = sorted(set(expected) & failing)
                if hit and not (failing - set(expected)):
                    verdict = "RED-OK"
                elif hit or failing:
                    verdict = "PARTIAL"
                else:
                    verdict = "GREEN-BUT-MUTATED"
            tally[verdict] = tally.get(verdict, 0) + 1
            who = ",".join(sorted(failing)) if failing else "全绿（跑了 %d 条）" % ran
            print("%-34s %-18s 点名=%d/%d ⇒ %s | rc=%s | 还原=%s"
                  % (mid, verdict, len(set(expected) & failing), len(expected), who, rc, restored),
                  flush=True)
            if verdict != "RED-OK":
                print("     %s" % note, flush=True)
            rows.append([mid, verdict, str(len(expected)), str(len(set(expected) & failing)),
                         who, note, "ok" if restored else "RESTORE-FAILED"])
            if not restored:
                print("FATAL: %s 之后 %s 未逐字节还原 ⇒ 停机，后面不再跑"
                      % (mid, SRC[key]), flush=True)
                break

        still = [k for k, v in SRC.items() if md5(os.path.join(ZBOT, v)) != baseline[k]]
        print("\n== 台账 ==")
        for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
            print("  %-18s %d" % (k, tally.get(k, 0)))
        print("  final md5 ok: %s" % (not still))
        with open(LEDGER, "w", encoding="utf-8") as fh:
            fh.write("\t".join(["id", "verdict", "named_expected", "named_hit",
                                "tests_that_went_red", "note", "restored"]) + "\n")
            for r in rows:
                fh.write("\t".join(c.replace("\t", " ") for c in r) + "\n")
        print("  台账已机械写出: %s" % os.path.relpath(LEDGER, ZBOT))
        return 0 if not still else 4
    finally:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


if __name__ == "__main__":
    sys.exit(main())
