#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""K2（凭证卫生哨兵）的双向实测 —— 判据一字未改，只把**扫描根**挪出仓外造现场。

为什么需要这个文件（p12c T1 的裁决件）
------------------------------------
上一棒 7 次整跑里 run5/run6 各红 1 条，红的都是同一条 K2，报错原文：

    FAIL  K2 … 见到的 key 值=['Bearer stub-key-not-real', 'api.key=stub-key-not-real']

两种形态**都只含 stub** 却判红 ⇒ 只有两种可能，必须分清：
  (i)  量具坏：取值方式把"整串"当成了 key 值（`re_iter` 有捕获组仍取 `group(0)`），
       于是 `Bearer stub-key-not-real` 这种带前缀的整串进了集合，与 `STUB_KEY` 永远对不上；
  (ii) 产品坏：运行期产物里真混进了别处来的 key 形态 ⇒ 红线缺陷。

裁决要**双向**才不作数：
  (a) 构造"两种形态并存"的现场 ⇒ 必须判绿（否则就是把判据调松凑绿）；
  (b) 往现场里塞一个**别的** key 值 ⇒ 必须判红（否则"判绿"只是因为眼睛瞎）；
  (c) 空跑（一个 key 形态都配不到）⇒ 必须判红（零命中不许当满分）。

本探针跑的是**仓里那份 `section_creds()` 原函数**（`importlib` 载入 `p12_e2e.py`，
只改 `m.HERE` / `m.REQ_SUBDIR` 两个全局），所以判据与被测对象一字不差；
仓里的 `out/`、`logs/` 一个字节都不动，现场铺在 `~/.cache/zbot-p12-k2-probe/`（不放 /tmp）。

另外还跑一遍**封存字节**（`git show 809b927:_doc/acceptance/p12/p12_e2e.py`，即上一棒临终
的工作文件）：如果封存字节在"两形态并存"现场上只收裸 key 值、判绿，它就**算不出** run5/run6
原文里那两个带前缀的取值 ⇒ run5/run6 跑的是更早的字节，"同一把尺连跑 3 次红 2 次"的前提不成立。
这一支把"不是同一把尺"从 mtime 推断升级成 git 字节证据。

复算：
    cd /private/tmp/zbot-wt-p12 && python3 -u _doc/acceptance/p12/p12_k2_probe.py   # rc=0 ⇒ 双向全符合期望
"""
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CURRENT_SRC = os.path.join(HERE, "p12_e2e.py")
# 上一棒（p12b）临终封存笔；探针用 git 取字节，不依赖任何手抄副本
SEALED_REV = "809b927"
SEALED_PATH = "_doc/acceptance/p12/p12_e2e.py"
# 现场根目录：仓外、非 /tmp（同机其他会话会扫空 /tmp）
BASE_ROOT = os.path.expanduser("~/.cache/zbot-p12-k2-probe")

# run5/run6 报错原文里的取值集合（逐字抄自 logs/e2e_full_run5.log、run6.log，用于成因对照）
RUN56_OBSERVED = ["Bearer stub-key-not-real", "api.key=stub-key-not-real"]


def load(src, tag):
    spec = importlib.util.spec_from_file_location("p12e2e_probe_%s" % tag, src)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def run_scenario(m, base, name, files, expect, key_header=True, stale_files=None,
                 k3_expect=None, roll_window=True):
    """在干净的 base 上铺 out/ 现场，叫被测那份 section_creds()，看 K2（和 K3）的判定。

    key_header=False 时 001.headers.json 里不含任何可配的取值
    （C/D 场景要的就是"产物里一个 key 形态都配不到"这个前提）。
    stale_files= 先铺、然后把本跑窗口推到它之后 —— 模拟"上一跑/别的工具留下的读数"。
    """
    if os.path.isdir(base):
        shutil.rmtree(base)
    os.makedirs(os.path.join(base, "out", "llm-requests"))
    # K1 需要 001.json / 001.headers.json 存在（K2 不读它们的内容形状，只按同一套正则扫）
    with open(os.path.join(base, "out", "llm-requests", "001.json"), "w") as fh:
        fh.write('{"messages":[{"role":"user","content":"hi"}]}')
    with open(os.path.join(base, "out", "llm-requests", "001.headers.json"), "w") as fh:
        if key_header:
            fh.write('{"authorization":"Bearer %s"}' % m.STUB_KEY)
        else:
            fh.write('{"x-placeholder":"none-of-these-match"}')
    for fn, body in sorted((stale_files or {}).items()):
        with open(os.path.join(base, "out", fn), "w") as fh:
            fh.write(body)
    if stale_files and roll_window and hasattr(m, "RUN_START"):
        # 把"本跑起点"推到这些文件之后：它们就成了"上一跑没被本跑碰过的既有产物"
        m.RUN_START = time.time() + 1.0
    for fn, body in sorted(files.items()):
        with open(os.path.join(base, "out", fn), "w") as fh:
            fh.write(body)
    del m.CHECKS[:]
    m.HERE = base                                   # 扫描根挪进仓外临时目录
    m.REQ_SUBDIR["path"] = os.path.join(base, "out", "llm-requests")
    m.LLM_HITS.append({"headers": {"authorization": "Bearer " + m.STUB_KEY}})
    m.section_creds()
    k2 = [c for c in m.CHECKS if c["name"].startswith("K2")][0]
    k3 = [c for c in m.CHECKS if c["name"].startswith("K3")][0]
    ok = (k2["status"] == expect)
    detail = k2["detail"]
    if k3_expect:
        ok = ok and k3["status"] == k3_expect
        detail = "K2=%s / K3=%s（期望 %s/%s）%s" % (k2["status"], k3["status"], expect, k3_expect,
                                                   ("；K3 命中=" + k3["detail"][:150])
                                                   if k3["status"] != "PASS" else "")
    print("  %-2s %-38s 期望=%-4s 实测=%-4s %-11s | %s"
          % (name[0], name, expect, k2["status"], "OK" if ok else "!!! MISMATCH", detail),
          flush=True)
    return ok, k2


# 与 p12_e2e.py:749-756 逐字同形的两条模式（只用于"同一现场两种取值方式"的成因对照；
# 真正的判定走 section_creds() 本体，不在这里复刻判据）
PROBE_PATTERNS = (r"(?i)bearer\s+([A-Za-z0-9._\-]{8,})",
                  r"(?ix)(?:x-api-key|api[._-]?key)\s*[=:]\s*[\"']?"
                  r"([A-Za-z0-9._\-]{8,})(?![A-Za-z0-9._\-])")


def _match_sets(text, take_group0_always):
    out = set()
    for pat in PROBE_PATTERNS:
        for mm in re.finditer(pat, text):
            out.add(mm.group(0) if take_group0_always else (mm.group(1) if mm.groups() else mm.group(0)))
    return sorted(out)


def legacy_semantics(text):
    """坏掉的那一版语义：不管有没有捕获组，一律收整串（早期 re_iter 就是这么写的）。"""
    return _match_sets(text, True)


def current_semantics(text):
    """现版语义：有捕获组就取值，没有才取整串（= p12_e2e.py 的 re_iter）。"""
    return _match_sets(text, False)


def scenarios(m, base, label, S):
    print("== %s ==" % label, flush=True)
    allok = True
    # (a) 两种形态并存、值同一个 —— 判绿才算修对
    allok &= run_scenario(m, base, "A 两形态并存（都只含 stub）",
                          {"a.log": "Authorization: Bearer %s\n" % S,
                           "b.log": "upstream api.key=%s saved\n" % S}, "PASS")[0]
    # (b) 混入别的 key 值 —— 必须判红，否则 (a) 的绿是眼睛瞎
    allok &= run_scenario(m, base, "B 混入别的 key 值",
                          {"a.log": "Authorization: Bearer %s\n" % S,
                           "leak.log": "fallback api.key=FAKELEAKEDVALUE999\n"}, "FAIL")[0]
    allok &= run_scenario(m, base, "B2 混入别的 Bearer 值",
                          {"a.log": "Authorization: Bearer FAKELEAKEDVALUE999\n"}, "FAIL")[0]
    # (c) 空跑 —— 零命中不许当满分
    allok &= run_scenario(m, base, "D 产物里配不到任何 key",
                          {"a.log": "nothing here\n"}, "FAIL", key_header=False)[0]
    # 稳定性 + 假红方向（宁可假红，不放宽）
    allok &= run_scenario(m, base, "A2 同现场再跑一遍（稳定性）",
                          {"a.log": "Authorization: Bearer %s\n" % S,
                           "b.log": "upstream api.key=%s saved\n" % S}, "PASS")[0]
    allok &= run_scenario(m, base, "C 只有 stub 一种形态",
                          {"a.log": "Authorization: Bearer %s\n" % S}, "PASS")[0]
    allok &= run_scenario(m, base, "F api.key=not-configured（假红侧）",
                          {"a.log": "Authorization: Bearer %s\n" % S,
                           "c.log": "api.key=not-configured\n"}, "FAIL")[0]
    return allok


def main():
    both = "Authorization: Bearer stub-key-not-real\napi.key=stub-key-not-real\n"
    print("成因对照（同一现场，两种取值方式算出的 key 值集合）:", flush=True)
    legacy = legacy_semantics(both)
    now = current_semantics(both)
    print("  坏语义 group(0)          = %s" % legacy, flush=True)
    print("  run5/run6 报错原文的取值 = %s" % RUN56_OBSERVED, flush=True)
    print("  现版语义（有组取值）     = %s" % now, flush=True)
    if legacy == RUN56_OBSERVED:
        print("  ⇒ 坏语义与 run5/run6 原文逐字相同 ⇒ 那条红是「整串被当成 key 值」造成的，"
              "不是产物里混进了别处来的 key", flush=True)
    else:
        print("  ⇒ 两者不同，「整串说」不成立，得另找成因（本探针不解释这条红）", flush=True)
    print("", flush=True)

    cur = load(CURRENT_SRC, "cur")
    S = cur.STUB_KEY
    allok = scenarios(cur, os.path.join(BASE_ROOT, "harness_current"),
                      "被测字节=工作树 p12_e2e.py（%s）" % CURRENT_SRC, S)

    # 封存字节：上一棒临终那一份，从 git 取，不看盘
    if not os.path.isdir(BASE_ROOT):
        os.makedirs(BASE_ROOT)
    sealed_src = os.path.join(BASE_ROOT, "e2e_sealed_%s.py" % SEALED_REV)
    with open(sealed_src, "wb") as fh:
        fh.write(subprocess.check_output(["git", "-C", os.path.abspath(os.path.join(HERE, "..", "..", "..")),
                                          "show", "%s:%s" % (SEALED_REV, SEALED_PATH)]))
    sealed = load(sealed_src, "sealed")
    print("", flush=True)
    allok &= scenarios(sealed, os.path.join(BASE_ROOT, "harness_sealed"),
                       "被测字节=git show %s:%s（上一棒临终工作文件）" % (SEALED_REV, SEALED_PATH),
                       sealed.STUB_KEY)
    print("\n双向实测结论：%s" % ("全部符合期望 ⇒ (a) 两形态并存判绿 与 (b) 混入别的值判红 两边都成立，"
                                  "判据没有被调松；封存字节也算不出带前缀的取值 ⇒ run5/run6 是更早的字节"
                                  if allok else "有不符期望项，见上（探针 rc=1）"), flush=True)
    return 0 if allok else 1


if __name__ == "__main__":
    sys.exit(main())
