#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
p20d 取证探针 —— 只为杠② TK5 的一条断言出读数：

    "上一棒(p20b)的 TK5 注点（摘掉 `cap == UNBOUNDED_RESULT_CHARS ||` 这个析取项）是**等价变异**，
     任何用例都判不出来" —— 这句话本棒要拿**实跑**证实，而不是照抄上一棒的推理。

跑三把，每把都：拿锁 → 注入 → `mvn -o test` 全量 → 从 surefire XML 收**具名红** → 还原 →
独立取证（`md5 -q` vs `git show <基线>:<path> | md5 -q`），脚本自报的 restored 不算。

  V1 = 上一棒的原注入（把析取项摘掉）
  V2 = 本棒换上的注入（哨兵当成零上限）
  V0 = 不注入（控制跑，必须全绿）

用法：
  python3 -u _doc/acceptance/p20b/p20d_tk5_equiv_probe.py            # 三把全跑
  python3 -u _doc/acceptance/p20b/p20d_tk5_equiv_probe.py --only=V1  # 只跑一把
产物：logs/p20d_tk5_probe_console.log（由调用方 tee），logs/mut_p20d_tk5_<V>.log（mvn 原文）
"""
import io
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import p20b_mutation as m  # noqa: E402  复用同一套锁/ps/还原取证口径

REL = m.SRC["toolkit"]
ANCHOR = "if (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS || content.length() <= cap) {"
VARIANTS = [
    ("V0", None, "控制跑（不注入）"),
    ("V1", "if (content.length() <= cap) {",
     "上一棒原注点：摘掉析取项（推理上恒真 ⇒ 预期 0 条红）"),
    ("V2", "if (content.length() <= (cap == ToolDescriptor.UNBOUNDED_RESULT_CHARS ? 0L : cap)) {",
     "本棒注点：哨兵当成零上限（预期 unboundedSentinelMeansNoTruncationAtAll 红）"),
]


def out(msg=""):
    sys.stdout.write(str(msg) + "\n")
    sys.stdout.flush()


def main():
    only = None
    for a in sys.argv[1:]:
        if a.startswith("--only="):
            only = a.split("=", 1)[1].split(",")
    base = os.environ.get("P20B_BASE", "HEAD")
    fd = m.acquire_lock(True)
    if fd == "BUSY":
        out("PROBE rc=5 锁被占，本棒不硬跑（不碰任何源文件）")
        return 5
    out("== TK5 等价变异取证  基线=%s  文件=%s" % (base, REL))
    try:
        gitb = m.git_bytes(base, REL)
        with io.open(os.path.join(m.ZBOT, REL), encoding="utf-8") as fh:
            original = fh.read()
        if original != gitb.decode("utf-8"):
            out("FATAL 盘上原文 != git show %s:%s（代码已漂，别信读数）" % (base, REL))
            return 3
        n = original.count(ANCHOR)
        out("  锚点 %s 出现 %d 次（要 1 次）" % ("cap==UNBOUNDED 那一行", n))
        if n != 1:
            return 2
        rc_all = 0
        for tag, new, why in VARIANTS:
            if only and tag not in only:
                continue
            out("-- %s  %s" % (tag, why))
            if not m.wait_for_quiet(int(os.environ.get("P20B_MVN_WAIT", "900"))):
                out("   邻居的 maven 真身没停，本棒不跑（等待期间没碰源码）")
                return 6
            before = m.disk_md5(REL)
            if new is not None:
                if not m.write_and_verify(REL, original.replace(ANCHOR, new, 1)):
                    out("   FATAL 注入未落定")
                    return 3
            during = m.disk_md5(REL)
            rc, failing, names, ran, log = m.run_mvn("p20d_tk5_" + tag)
            if new is not None:
                if not m.write_and_verify(REL, original):
                    out("   FATAL 还原未落定")
                    return 3
            after = m.disk_md5(REL)
            bmd5 = m.git_md5(base, REL)
            landed = "yes(disk!=git show)" if during != bmd5 else "NO(注入没进盘!)"
            restored = "ok" if after == bmd5 else "RESTORE-FAILED"
            out("   rc=%s ran=%s 红=%s" % (rc, ran, ",".join(sorted(failing)) or "无"))
            out("   取证 before=%s during=%s landed=%s after=%s git_show=%s restored=%s 日志=%s"
                % (before, during, landed, after, bmd5, restored, log))
            if new is None and (failing or rc != 0):
                out("   !! 控制跑不绿 ⇒ 后面两把的读数没有意义")
                rc_all = 4
            if tag == "V1" and failing:
                out("   !! V1 有红 ⇒ '等价变异'这句话被证伪（本棒得换说法）")
            if tag == "V2" and "unboundedSentinelMeansNoTruncationAtAll" not in failing:
                out("   !! V2 没点名 ⇒ 新注点也没有活猎物")
        still = [k for k, v in m.SRC.items() if m.disk_md5(v) != m.git_md5(base, v)]
        out("== 收尾 SRC_MD5_STABLE=%s" % ("yes" if not still else "no:" + ",".join(still)))
        return 3 if still else rc_all
    finally:
        m.fcntl.flock(fd, m.fcntl.LOCK_UN)
        m.os.close(fd)
        out("LOCK RELEASED pid=%d" % os.getpid())


if __name__ == "__main__":
    sys.exit(main())
