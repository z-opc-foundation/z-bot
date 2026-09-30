#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""p26a 杠②：LLM 失败恢复/用量核算 变异注入.

写法借鉴已并网的 p12 与 w3-p21（阳性对照 + prey_ok 结构 + 逐字节还原 + 三值对账 + 五档台账），
**MUTANTS 表与实现全部自写**，不 import 任何战役脚本。

纪律：
  * 独占 flock：$(git rev-parse --git-common-dir)/zbot-mutlock，只准 LOCK_EX|LOCK_NB；
    抢不到 ⇒ 立刻 rc=4 退出，一个源文件都不碰，**不 sleep 重试**。
  * 每族先跑阳性对照（injection=NONE）：点名的 testcase 必须真跑到且全绿；对照不 OK ⇒ 整族 NO-RUN。
  * 注入前记录 (disk md5, git show HEAD:<path> md5)；注入后逐字节还原，再核 (disk md5, git md5) 三值。
    还原不上 ⇒ 该行记 BROKEN 并在 REASON 里写明。
  * 期望红集在跑之前写死在 MUTANTS 表里；RED-OK / PARTIAL / GREEN-BUT-MUTATED / BROKEN / NO-RUN
    由本脚本机械判定，LEDGER.tsv 只由本脚本写出。
  * 重试类测试一律走 RetryPolicyConfig 的小 base + jitter=0，绝不让真退避把整轮拖爆（超时上限见 RUN_TIMEOUT）。

用法：
  python3 p26_mutation.py --check-anchors         # 只校验锚点，不碰任何文件（不要锁）
  python3 p26_mutation.py                          # 全量（拿不到锁 rc=4）
  python3 p26_mutation.py --only=family_name       # 只跑某族
"""

import argparse
import collections
import fcntl
import hashlib
import os
import re
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ZBOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
LEDGER = os.path.join(HERE, "LEDGER.tsv")
RAWS = os.path.join(HERE, "mutation_logs")
RUN_TIMEOUT = 420  # 秒：单支变异（含 mvn）的硬上限，超了记 BROKEN

LLM = "z-bot-core/src/main/java/com/zifang/z/bot/llm"
AGENT = "z-bot-core/src/main/java/com/zifang/z/bot/agent"
STORE = "z-bot-core/src/main/java/com/zifang/z/bot/store"

CLS = LLM + "/LlmErrorClassifier.java"
BACKOFF = LLM + "/JitteredBackoff.java"
POLICY = LLM + "/RetryPolicyConfig.java"
RESILIENT = LLM + "/ResilientLlmProvider.java"
KEYPOOL = LLM + "/KeyPoolLlmProvider.java"
WATCHDOG = LLM + "/StreamStaleWatchdog.java"
USAGE = LLM + "/ModelUsage.java"
BOTAGENT = AGENT + "/BotAgent.java"
STATESTORE = STORE + "/StateStore.java"

R_CLS = "P26RetryPolicyTest"
R_USG = "P26UsageTest"

# 台账只认这几个具名 runner 类里的失败（-Dtest 也只选这些类，别的类根本没起跑）。
RUNNERS = ("P26RetryPolicyTest", "P26UsageTest", "ResilientLlmProviderTest",
           "KeyPoolLlmProviderTest", "ModelCatalogCacheTest")

# name, family, file, old(唯一), new, prey(期望红的 testcase 方法), note
MUTANTS = [
    dict(name="cls_drop_http429", family="classification", file=CLS,
         old='        if (status == 429) {\n            return FailureClass.RATE_LIMIT;\n        }\n'
             '        if (status == 401 || status == 402 || status == 403) {',
         new='        if (status == 401 || status == 402 || status == 403) {',
         prey=[R_CLS + "#classifier_rule_http429_structuredStatusIsRateLimit+"
               + R_CLS + "#classifier_keyPoolDoesNotRotateKeyOnContextLength"],
         note="摘掉 429 这一类"),
    dict(name="cls_drop_rotate_key", family="classification", file=CLS,
         old='        if (status == 401 || status == 402 || status == 403) {\n'
             '            return FailureClass.ROTATE_KEY;\n        }\n'
             '        if (status == 404 || status == 410) {',
         new='        if (status == 404 || status == 410) {',
         prey=[R_CLS + "#classifier_rule_http401_402_403_isKeyScoped"],
         note="摘掉换 key 这一类"),
    dict(name="cls_drop_fallback_model", family="classification", file=CLS,
         old='        if (status == 404 || status == 410) {\n'
             '            return mentionsModel(msg) ? FailureClass.FALLBACK_MODEL : FailureClass.FATAL;\n'
             '        }',
         new='        if (status == 404 || status == 410) {\n            return FailureClass.FATAL;\n        }',
         prey=[R_CLS + "#classifier_rule_http404Model_vs_404Path"],
         note="摘掉换模型这一类（404 模型不存在）"),
    dict(name="cls_unclassified_becomes_retryable", family="classification", file=CLS,
         old='        return new Decision(FailureClass.FATAL, "unclassified", Evidence.NONE, null, 0L);\n    }',
         new='        return new Decision(FailureClass.RETRY_SAME_KEY, "unclassified", Evidence.NONE, null, 0L);\n    }',
         prey=[R_CLS + "#classifier_rule_unclassifiedIsFatal_andInterruptToo+"
               + R_CLS + "#classifier_bare429InProseIsNotRateLimit"],
         note="兜底档摘掉：判不准也去重试"),
    dict(name="cls_bare_digits_match_again", family="classification", file=CLS,
         old='            "(?i)\\\\b(?:http|https|status(?:[\\\\s_]+code)?|error[\\\\s_]+code|response[\\\\s_]+code'
             '|status[\\\\s_]*code)"',
         new='            "(?i)(?:[^0-9]|\\\\A)"',
         prey=[R_CLS + "#classifier_bare429InProseIsNotRateLimit+" + R_CLS + "#classifier_bare500InProseIsNotRetryable"],
         note="把'带标签才算'摘回去 ⇒ 老假阳性回归"),
    dict(name="cls_structured_status_ignored", family="classification", file=CLS,
         old='            if (t instanceof LlmException) {\n                Integer s = ((LlmException) t).getHttpStatus();\n'
             '                if (s != null) {\n                    return s;\n                }\n            }',
         new='            if (false) {\n                return null;\n            }',
         prey=[R_CLS + "#classifier_structuredStatusBeatsMessageText+"
               + R_CLS + "#classifier_rule_http429_structuredStatusIsRateLimit"],
         note="退化回'只读 message 正则'"),
    dict(name="keypool_regex_exceeded_returns", family="classification", file=KEYPOOL,
         old='        LlmErrorClassifier.FailureClass cls = LlmErrorClassifier.classify(e).getFailureClass();',
         new='        LlmErrorClassifier.FailureClass cls = (e.getMessage() != null && e.getMessage().toLowerCase()'
             '.contains("exceeded")) ? LlmErrorClassifier.FailureClass.ROTATE_KEY'
             ' : LlmErrorClassifier.classify(e).getFailureClass();',
         prey=[R_CLS + "#classifier_keyPoolDoesNotRotateKeyOnContextLength"],
         note="缺陷 B 回归：上下文超长被误判成 key 失效"),
    dict(name="backoff_jitter_off", family="jitter", file=BACKOFF,
         old='        long jitter = (long) (this.policy.getJitterRatio() * raw * clamp01(jitterSource.getAsDouble()));',
         new='        long jitter = 0L;',
         prey=[R_CLS + "#backoff_jitterStaysWithinRatio"],
         note="抖动摘成固定值"),
    dict(name="backoff_exponential_to_linear", family="ladder", file=BACKOFF,
         old='        long v = base * (1L << exponent);',
         new='        long v = base * (exponent + 1);',
         prey=[R_CLS + "#backoff_exponentialNotLinear_whenJitterZero+" + R_CLS + "#backoff_rateLimitWalksLongLadderAfterShortTier"],
         note="指数退化成线性（P26 之前的行为）"),
    dict(name="backoff_ladder_to_linear", family="ladder", file=BACKOFF,
         old='        int i = Math.max(1, step) - 1;\n        long v = ladder.get(Math.min(i, ladder.size() - 1));',
         new='        int i = Math.max(1, step) - 1;\n        long v = ladder.get(0) * i;',
         prey=[R_CLS + "#backoff_rateLimitWalksLongLadderAfterShortTier"],
         note="限流长档表退化成等差"),
    dict(name="backoff_retry_after_ignored", family="retry-after", file=BACKOFF,
         old='        if (retryAfterMs > 0) {\n            raw = retryAfterMs;',
         new='        if (false && retryAfterMs > 0) {\n            raw = retryAfterMs;',
         prey=[R_CLS + "#backoff_retryAfterOverridesLadder"],
         note="上游 Retry-After 不采信"),
    dict(name="backoff_total_budget_off", family="ladder", file=BACKOFF,
         old='        if (policy.getTotalBudgetMs() > 0 && spentMs + wait > policy.getTotalBudgetMs()) {',
         new='        if (false) {',
         prey=[R_CLS + "#backoff_totalBudgetStopsGrowth"],
         note="总预算不生效（一次真退避能拖爆整轮）"),
    dict(name="resilient_no_wait_between_models", family="fallback", file=RESILIENT,
         old='                    if (attempt >= maxRetries + 1) {',
         new='                    if (false) {',
         prey=[R_CLS + "#resilient_recordsActualWaitSequenceForRateLimit+"
               + R_CLS + "#resilient_legacyCtorKeepsWaitsTiny"],
         note="最后一次尝试后仍空等一轮退避"),
    dict(name="fallback_no_context_rebuild", family="fallback", file=RESILIENT,
         old='                rebuilt = h.rebuildContext(from, to, origin);',
         new='                rebuilt = null;',
         prey=[R_CLS + "#modelSwitch_rebuildsContextAndResetsCounters"],
         note="换模型不重建上下文（P26 之前的行为）"),
    dict(name="fallback_no_counter_reset", family="fallback", file=RESILIENT,
         old='                h.resetCompressionState(from, to);',
         new='                // 摘掉：换模型后不重置压缩计数',
         prey=[R_CLS + "#modelSwitch_rebuildsContextAndResetsCounters"],
         note="换模型不重置压缩计数"),
    dict(name="watchdog_floor_becomes_ceiling", family="stale-threshold", file=WATCHDOG,
         old='        return Math.max(policy.getStreamStaleTimeoutMs(), floor);',
         new='        return Math.min(policy.getStreamStaleTimeoutMs(), floor > 0 ? floor : policy.getStreamStaleTimeoutMs());',
         prey=[R_CLS + "#stale_floorNeverLowersDefault"],
         note="max(default,floor) 摘成 min ⇒ floor 会压低阈值"),
    dict(name="watchdog_explicit_config_ignored", family="stale-threshold", file=WATCHDOG,
         old='        if (policy.isStreamStaleTimeoutExplicit()) {\n'
             '            return Math.max(0L, policy.getStreamStaleTimeoutMs());\n        }',
         new='        if (false) {\n            return 0L;\n        }',
         prey=[R_CLS + "#stale_explicitUserConfigBeatsFloor"],
         note="用户显式配置被 floor 覆盖"),
    dict(name="watchdog_never_trips", family="stale-trip", file=WATCHDOG,
         old='                if (idle >= limit && tripped.compareAndSet(false, true)) {',
         new='                if (false && idle >= limit) {',
         prey=[R_CLS + "#stale_watchdogTripsAndDeliversErrorOnce"],
         note="看门狗端不掉流（挂死）"),
    dict(name="usage_openai_cache_read_dropped", family="usage-cache", file=USAGE,
         old='        if (read == 0L) {\n            read = firstLong(details, "cached_tokens", "cache_read_tokens");\n        }',
         new='        // 摘掉：OpenAI 形态的 cached_tokens 不读',
         prey=[R_USG + "#normalize_openAiShape_readsCachedTokens+" + R_USG + "#normalize_bothProviderShapesAgree"],
         note="cache 读命中不归一"),
    dict(name="usage_cache_write_dropped", family="usage-cache", file=USAGE,
         old='                firstLong(usage, "cache_creation_input_tokens", "cache_write_tokens"), "anthropic");',
         new='                0L, "anthropic");',
         prey=[R_USG + "#normalize_anthropicShape_readsCacheReadAndWrite"],
         note="cache 写命中不归一"),
    dict(name="usage_clamp_off", family="usage-cache", file=USAGE,
         old='            return prompt > 0L ? Math.min(v, prompt) : v;',
         new='            return v;',
         prey=[R_USG + "#normalize_cacheReadNeverExceedsPrompt"],
         note="cache 读命中不钳位到 prompt 以内"),
    dict(name="cache_columns_never_written", family="usage-store", file=STATESTORE,
         old='        final int calls = apiCalls;\n        final boolean withCache = hasCacheColumns();',
         new='        final int calls = apiCalls;\n        final boolean withCache = false;',
         prey=[R_USG + "#store_withCacheColumns_writtenAndReadBack"],
         note="cache 命中不入库（列在位也不写）"),
    # ---- p26b 补齐工单点名的注入面（每一条都是 P26a 表里缺的）----
    dict(name="cls_http5xx_becomes_fatal", family="classification", file=CLS,
         old='        if (status >= 500 && status <= 504 || status == 522 || status == 524) {\n'
             '            return FailureClass.RETRY_SAME_KEY;\n        }',
         new='        if (status >= 500 && status <= 504 || status == 522 || status == 524) {\n'
             '            return FailureClass.FATAL;\n        }',
         prey=[R_CLS + "#classifier_rule_http5xx_isRetrySameKey+"
               + R_CLS + "#resilient_doesNotRetryFatalAndRetriesTransient"],
         note="RETRY_SAME_KEY 这一类摘一刀。为什么不写成「删掉整个 if」：classOfStatus 末尾还有 "
              "`return status >= 500 ? RETRY_SAME_KEY : FATAL` 兜底 ⇒ 删分支是等价变异（尺子不可能红），"
              "改成 5xx→FATAL 才是真摘类"),
    dict(name="backoff_ladder_pinned_first_rung", family="ladder", file=BACKOFF,
         old='        int i = Math.max(1, step) - 1;\n'
             '        long v = ladder.get(Math.min(i, ladder.size() - 1));',
         new='        int i = Math.max(1, step) - 1;\n        long v = ladder.get(0);',
         prey=[R_CLS + "#backoff_rateLimitWalksLongLadderAfterShortTier+"
               + R_CLS + "#backoff_retryAfterOverridesLadder"],
         note="限流阶梯档位写死（永远走第一档）"),
    dict(name="watchdog_floor_dropped_to_default", family="stale-threshold", file=WATCHDOG,
         old='        return Math.max(policy.getStreamStaleTimeoutMs(), floor);',
         new='        return policy.getStreamStaleTimeoutMs();',
         prey=[R_CLS + "#stale_floorIsMaxOfDefaultAndModelFloor"],
         note="max(default, floor) 摘成 default（floor 整条不生效；与 becomes_ceiling 互补）"),
    dict(name="usage_billable_no_discount", family="usage-cache", file=USAGE,
         old='        public long getBillablePromptTokens() {\n'
             '            return Math.max(0L, promptTokens - cacheReadTokens);',
         new='        public long getBillablePromptTokens() {\n            return promptTokens;',
         prey=[R_USG + "#normalize_openAiShape_readsCachedTokens+"
               + R_USG + "#normalize_anthropicShape_readsCacheReadAndWrite+"
               + R_USG + "#normalize_cacheReadNeverExceedsPrompt"],
         note="usage 归一里 cache 命中不加（折后价口径退回原价）"),
]

# 每族的阳性对照：注入=NONE 时必须真跑到且全绿
FAMILIES = collections.OrderedDict()
for m in MUTANTS:
    FAMILIES.setdefault(m["family"], []).append(m)


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def git_md5(rel):
    pr = subprocess.run(["git", "-C", ZBOT, "show", "HEAD:" + rel], stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE)
    if pr.returncode != 0:
        return None
    return hashlib.md5(pr.stdout).hexdigest()


def src(rel):
    return os.path.join(ZBOT, rel)


def common_dir():
    pr = subprocess.run(["git", "-C", ZBOT, "rev-parse", "--git-common-dir"], stdout=subprocess.PIPE)
    d = pr.stdout.decode().strip()
    return d if os.path.isabs(d) else os.path.join(ZBOT, d)


def acquire():
    path = os.path.join(common_dir(), "zbot-mutlock")
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        print("LOCK-BUSY path=%s ⇒ rc=4（不 sleep 重试）" % path, flush=True)
        return None
    print("LOCK-ACQUIRED %s (LOCK_EX|LOCK_NB)" % path, flush=True)
    return fd


def mvn(testsel, tag):
    """跑一轮定向测试；返回 (rc, 失败方法集合, 跑到的方法数, log 路径)。

    p26b 修的三处量具缺陷（都会把"真红"记成别的东西）：
      * 原 `ran` 只认 `^\\[INFO\\] Tests run:` —— surefire 在**有失败**时把这行打成
        `[ERROR] Tests run: 65, Failures: 2, ...`（实测 llm_tests_2.log:163）⇒ ran 恒 0 ⇒
        run_one 走 "ran==0 ⇒ BROKEN" 分支，凡是把测试打红的变异都被记成 BROKEN 而不是 RED-OK。
      * 原失败行正则 `^\\[ERROR\\]\\s+(\\w+)\\.(\\w+)` 会把 FQN 形式
        `[ERROR] com.zifang.z.bot.llm.P26RetryPolicyTest.foo -- Time elapsed` 拆成 cls="com"，
        靠白名单丢掉 ⇒ 只吃"缩进汇总块"那一种形状；两种形状现在都能解析。
      * 原超时只 `pr.kill()` 杀 mvn，surefire 的 fork JVM 会成为孤儿继续跑（下一支注入撞上
        半个在跑的 target/ ⇒ 读数不可信）。改成进程组一起杀。
    """
    if not os.path.isdir(RAWS):
        os.makedirs(RAWS)
    log = os.path.join(RAWS, tag + ".log")
    cmd = ["mvn", "-o", "test", "-pl", "z-bot-core", "-Dtest=" + testsel, "-DfailIfNoTests=false"]
    with open(log, "w") as fh:
        try:
            pr = subprocess.Popen(cmd, cwd=ZBOT, stdout=fh, stderr=subprocess.STDOUT,
                                  start_new_session=True)
        except OSError as e:
            return -1, set(), 0, log
        try:
            rc = pr.wait(timeout=RUN_TIMEOUT)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(os.getpgid(pr.pid), signal.SIGKILL)
            except Exception:
                pr.kill()
            pr.wait()
            return -9, set(), 0, log
    txt = open(log, errors="replace").read()
    fails = set()
    simple = set(RUNNERS)
    for mm in re.finditer(r"^\[ERROR\]\s+(?:[\w.$]*\.)?([\w$]+)\.([\w$]+)(?::|\s)", txt, re.M):
        cls, meth = mm.group(1), mm.group(2)
        if cls in simple:
            fails.add(cls + "." + meth)
    ran = 0
    # 取最后一条汇总行，级别不限（INFO/ERROR）；只要带 "Tests run:" 且没有 " -- in " 的就是总计行
    for mm in re.finditer(r"^\[(?:INFO|ERROR|WARNING)\]\s+Tests run: (\d+), Failures: (\d+), "
                          r"Errors: (\d+), Skipped: (\d+)\s*$", txt, re.M):
        ran = int(mm.group(1))
    if "COMPILATION ERROR" in txt:
        rc = rc if rc != 0 else 2
    return rc, fails, ran, log


def parse_prey(prey):
    """把 prey 串拆成 (类, 方法) 集合。

    p26b 修的量具缺陷：表里多条 prey 写成 ``R_CLS + "#m1+" + R_CLS + "#m2"``，
    拼出来是 ``P26RetryPolicyTest#m1+P26RetryPolicyTest#m2``。原来的
    ``partition("#")`` + ``split("+")`` 只认前一半，后半被当成**方法名**
    ``P26RetryPolicyTest#m2`` ⇒ ①`-Dtest` 选择器里带第二个 ``#``，surefire 匹配不到任何用例
    （ran=0 ⇒ 整族被记 NO-RUN/BROKEN，而不是预期的红），②期望红集里那条永远是"不可能命中"的串。
    这里两种写法（``A#m1+A#m2`` 与 ``A#m1+m2``）都吃。
    """
    out = []
    for p in prey:
        for seg in p.split("+"):
            seg = seg.strip()
            if not seg:
                continue
            cls, _, meth = seg.partition("#")
            out.append((cls, meth or None))
    return out


def testsel_of(prey):
    """-Dtest 语法：A#m1+m2,B#m3 （同类合并，跨类用逗号）。"""
    bycls = {}
    for cls, meth in parse_prey(prey):
        bycls.setdefault(cls, set())
        if meth:
            bycls[cls].add(meth)
    parts = []
    for cls in sorted(bycls):
        ms = bycls[cls]
        parts.append(cls + "#" + "+".join(sorted(ms)) if ms else cls)
    return ",".join(parts)


def expand_expected(prey):
    return set("%s.%s" % (c, m) for c, m in parse_prey(prey) if m)


def check_anchors(only=None):
    """跑之前的三道静态前置：锚点唯一、prey 测试方法真存在、prey 类真在 RUNNERS 白名单里。

    p26b 加了 prey 校验：锚点齐但 prey 方法名写错 ⇒ 阳性对照 `ran>=len(prey)` 会假过
    （surefire 只跑到少数几个），整族判 NO-RUN 之外还会给出误导性的 reason。
    """
    bad = []
    tests_by_cls = {}
    for m in MUTANTS:
        if only and m["family"] != only and m["name"] != only:
            continue
        p = src(m["file"])
        if not os.path.isfile(p):
            bad.append((m["name"], "missing-file"))
            continue
        body = open(p, errors="replace").read()
        if body.count(m["old"]) != 1:
            bad.append((m["name"], "anchor-count=%d" % body.count(m["old"])))
        if m["new"] == m["old"]:
            bad.append((m["name"], "no-op-mutant"))
        for cls, meth in parse_prey(m["prey"]):
            if cls not in RUNNERS:
                bad.append((m["name"], "prey-class-not-runner:%s" % cls))
                continue
            if meth is None:
                continue
            if cls not in tests_by_cls:
                path = None
                for root, _d, fs in os.walk(os.path.join(ZBOT, "z-bot-core/src/test")):
                    if (cls + ".java") in fs:
                        path = os.path.join(root, cls + ".java")
                        break
                tests_by_cls[cls] = open(path, errors="replace").read() if path else ""
            if not re.search(r"\bvoid\s+" + re.escape(meth) + r"\s*\(", tests_by_cls[cls]):
                bad.append((m["name"], "prey-method-missing:%s#%s" % (cls, meth)))
    for b in bad:
        print("ANCHOR-BAD %s %s" % b)
    print("ANCHOR-CHECK mutants=%d bad=%d families=%s" % (
        len(MUTANTS), len(bad), ",".join(FAMILIES.keys())))
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only")
    ap.add_argument("--check-anchors", action="store_true")
    args = ap.parse_args()
    if args.check_anchors:
        return check_anchors(args.only)
    # 跑之前先静态自检（不碰任何文件、不要锁）：锚点/prey 名不对 ⇒ 直接 rc=5，一个字节都不改。
    if check_anchors(args.only) != 0:
        print("PRE-CHECK FAILED ⇒ 拒绝开跑（不碰源文件、不建台账）", flush=True)
        return 5

    fd = acquire()
    if fd is None:
        return 4

    rows = []
    try:
        for fam, mutants in FAMILIES.items():
            if args.only and fam != args.only:
                continue
            prey_union = []
            for m in mutants:
                prey_union += m["prey"]
            prey_union = sorted(set(prey_union)) or [R_CLS + "#backoff_jitterStaysWithinRatio"]
            ctl_sel = testsel_of(prey_union)
            rc0, f0, ran0, _ = mvn(ctl_sel, "control_" + fam)
            # p26b：对照的分母按**展开后的方法数**算（原来按 "Class#m1+m2" 这种打包串算，
            # 会把参照集算小 ⇒ 少跑到也能 prey_ok=True）。
            want = len(expand_expected(prey_union))
            prey_ok = (rc0 == 0 and not f0 and ran0 >= want)
            print("CONTROL %s sel=%s rc=%s ran=%s want=%s fails=%s prey_ok=%s" % (
                fam, ctl_sel, rc0, ran0, want, sorted(f0)[:3], prey_ok), flush=True)
            if not prey_ok:
                for m in mutants:
                    rows.append((m, "NO-RUN",
                                 "control-not-ok rc=%s ran=%s fails=%s | %s" % (
                                     rc0, ran0, sorted(f0)[:4], md5_triple(m, src(m["file"])))))
                continue
            for m in mutants:
                verdict, reason = run_one(m, prey_ok)
                rows.append((m, verdict, "%s | %s" % (reason, md5_triple(m, src(m["file"])))))
        write_ledger(rows)
        print("ROWS=%d" % len(rows), flush=True)
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)
    return 0


def run_one(m, prey_ok):
    p = src(m["file"])
    before_disk = md5(p)
    base_git = git_md5(m["file"])
    body = open(p, errors="replace").read()
    if body.count(m["old"]) != 1:
        return ("BROKEN", "anchor-count=%d" % body.count(m["old"]))
    with open(p, "w") as fh:
        fh.write(body.replace(m["old"], m["new"], 1))
    inj_disk = md5(p)
    if inj_disk == before_disk:
        with open(p, "w") as fh:
            fh.write(body)
        return ("NO-RUN", "注入后字节没变（等价改写，mvn 不会跑到新形态）md5=%s/%s/%s" % (
            before_disk[:8], inj_disk[:8], (base_git or "?")[:8]))
    try:
        sel = testsel_of(m["prey"])
        rc, fails, ran, log = mvn(sel, m["name"])
        txt = open(log, errors="replace").read()
        expected = expand_expected(m["prey"])
        hit = expected & fails
        if ran == 0 or rc == -9 or "COMPILATION ERROR" in txt:
            return ("BROKEN", "rc=%s ran=0 log=%s" % (rc, os.path.basename(log)))
        if len(hit) == len(expected) and expected:
            verdict = "RED-OK"
        elif hit:
            verdict = "PARTIAL"
        else:
            verdict = "GREEN-BUT-MUTATED"
        extra = sorted(fails - expected)
        return (verdict, "rc=%s ran=%s missing=%s extra=%s" % (
            rc, ran, sorted(expected - hit)[:4], extra[:4]))
    finally:
        with open(p, "w") as fh:
            fh.write(body)
        restored = md5(p) == before_disk
        same_git = git_md5(m["file"]) == base_git
        if not (restored and same_git):
            print("!! RESTORE-FAIL %s restored=%s git=%s (%s/%s)" % (
                m["name"], restored, same_git, before_disk[:8], (base_git or "?")[:8]), flush=True)
            with open(os.path.join(RAWS, "RESTORE-FAIL-" + m["name"] + ".txt"), "w") as fh:
                fh.write("%s %s %s\n" % (before_disk, inj_disk, base_git))


def md5_triple(m, p):
    """三值对账：盘上当前 md5 / git HEAD md5 / 二者是否同 —— 每支跑完都机械记一行。"""
    disk = md5(p)
    git = git_md5(m["file"])
    return "md5=disk:%s/git:%s/same=%s" % (disk[:8], (git or "?")[:8], disk == git)


def write_ledger(rows):
    # p26b：空台账不落盘 —— 否则"脚本跑挂了/一族没进循环"也会留下一个带表头的文件，
    # 看上去像跑过（P21/P23 两期的台账保质期问题就是这个）。
    if not rows:
        print("!! 台账 0 行 ⇒ 不写 %s（拒绝产出空台账）" % LEDGER, flush=True)
        return
    with open(LEDGER, "w", encoding="utf-8") as fh:
        fh.write("# generated-by p26_mutation.py  stamp=%s  mutants=%d\n" % (
            time.strftime("%Y-%m-%d %H:%M:%S %z"), len(rows)))
        fh.write("mutant\tfamily\tverdict\tfile\tprey\tnote\treason\n")
        for m, verdict, reason in rows:
            fh.write("%s\t%s\t%s\t%s\t%s\t%s\t%s\n" % (
                m["name"], m["family"], verdict, os.path.basename(m["file"]),
                ";".join(m["prey"]), m["note"], reason))
    counts = collections.Counter(v for _, v, _ in rows)
    print("LEDGER %s rows=%d" % (os.path.relpath(LEDGER, ZBOT), len(rows)))
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN", "NO-RUN"):
        print("  %-20s %d" % (k, counts.get(k, 0)))


if __name__ == "__main__":
    sys.exit(main())
