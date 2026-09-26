#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P27 委托面 —— 杠② 变异量具（自带 MUTANTS 表，不 import 任何别的战役脚本）。

五档判定（一条不欠）：
  KILLED                 注入后 mvn 变红，且红的是预期红集里的用例
  SURVIVED               注入后仍然全绿（必须在报告里点名 + 说为什么）
  BASELINE_NOT_GREEN     跑之前基线就不绿 —— 本轮一律不出判定
  INJECTION_NOT_APPLIED  被替换的原文在本文件里不是唯一一处（注入本身不可信）
  RESTORE_FAILED         逐字节还原后 md5 对不上 —— 立刻停机，不许继续测量

共享锁：$(git rev-parse --path-format=absolute --git-common-dir)/zbot-mutlock
抢不到 ⇒ rc=4 直接退出（不 sleep 死等、不 kill 别人）。
动笔前先做无界 await()/waitFor() 体检：委托这一路天生等子进程，无界等待会把全编队的锁占死。
"""

import errno
import hashlib
import os
import re
import shutil
import signal
import subprocess
import sys
import time

REPO = "/private/tmp/zbot-wt-p27"
LEAD = os.path.expanduser("~/.cache/zbot-p27-lead")
BAK = os.path.join(LEAD, "mutbak")
OUT = os.path.join(LEAD, "mutation")
LEDGER = os.path.join(REPO, "_doc/acceptance/p27/LEDGER.tsv")
MAIN_DIR = "z-bot-core/src/main/java/com/zifang/z/bot/delegate"
TEST_DIR = "z-bot-core/src/test/java/com/zifang/z/bot/delegate"
SCOPE = "com.zifang.z.bot.delegate.*Test,BotAgentTest"   # 具名范围（含被波及的 BotAgentTest）
MVN_TIMEOUT = 420

# ---------------------------------------------------------------------------
# MUTANTS 表：每条 = (编号, 文件, 原文, 变异文, 预期红集, 这支在测什么)
# 原文必须在文件里唯一命中，否则判 INJECTION_NOT_APPLIED。
# ---------------------------------------------------------------------------
D = lambda f: MAIN_DIR + "/" + f

MUTANTS = [
    dict(id="M01-cap-anchor-bumped",
         file=D("DelegationDelivery.java"),
         old="public static final int MAX_DELIVERY_ATTEMPTS = 8;",
         new="public static final int MAX_DELIVERY_ATTEMPTS = 9;",
         expect=["attemptCapIsEightLikeTheHermesAnchor", "exhaustedAttemptsConvergeToTerminalDropped"],
         why="上限锚点被挪动一格（8→9）：取证价值当场归零"),
    dict(id="M02-cap-unbounded",
         file=D("DelegationDelivery.java"),
         old="if (e.deliveryAttempts >= MAX_DELIVERY_ATTEMPTS) {",
         new="if (e.deliveryAttempts >= Integer.MAX_VALUE) {",
         expect=["exhaustedAttemptsConvergeToTerminalDropped", "theCapEvidenceSurvivesARestart",
                 "droppedRowsAreNotOfferedForRestoreButPendingRowsAre"],
         why="把上限摘成事实无界 ⇒ 没人接的完成事件永远回到 PENDING，重启无限重放"),
    dict(id="M03-ack-without-claim-allowed",
         file=D("DelegateTransitions.java"),
         old="putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);",
         new=("putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DROPPED, DeliveryState.DROPPED);\n"
              "        putDeliv(DeliveryState.PENDING, DelegateEvent.RESULT_DELIVERED, DeliveryState.DELIVERED);"),
         expect=["deliveryMatrixIsExhaustive", "ackWithoutClaimIsIllegal", "ackWithoutClaimFailsLoudly"],
         why="给 PENDING 开一条到 DELIVERED 的口子 = 把 P16 那个同型洞重新焊回去"),
    dict(id="M04-claim-does-not-burn",
         file=D("DelegationDelivery.java"),
         old="""        e.deliveryAttempts++;
        e.claimToken = token;""",
         new="""        e.deliveryAttempts += 0;
        e.claimToken = token;""",
         expect=["eachClaimBurnsExactlyOneAttempt", "exhaustedAttemptsConvergeToTerminalDropped",
                 "theCapEvidenceSurvivesARestart", "claimLeaseBlocksOtherConsumersUntilItExpires"],
         why="claim 不烧尝试数 ⇒ 上限变成不会走动的表"),
    dict(id="M05-prune-age-inverted",
         file=D("DelegationLedger.java"),
         old="if (ageBase + maxAgeMillis > now) {",
         new="if (ageBase + maxAgeMillis < now) {",
         expect=["pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse",
                 "pruneHonoursTheRetentionWindowBoundary", "unknownScenesAreTerminalAndThusPrunable"],
         why="年龄判定反了：窗口内的删、过期的留"),
    dict(id="M06-prune-eats-inflight",
         file=D("DelegationLedger.java"),
         old="""        for (Entry e : list()) {
            if (!e.state.terminal()) {
                continue;
            }
            long ageBase""",
         new="""        for (Entry e : list()) {
            if (false && !e.state.terminal()) {
                continue;
            }
            long ageBase""",
         expect=["pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse"],
         why="回收不看终态 ⇒ 在飞委托的唯一现场会被顺手删掉"),
    dict(id="M07-retention-window-bloated",
         file=D("DelegationLedger.java"),
         old="public static final int LIVE_RETENTION_DAYS = 7;",
         new="public static final int LIVE_RETENTION_DAYS = 70;",
         expect=["retentionConstantMatchesHermesSevenDays"],
         why="保留窗口漂走一个数量级（她的锚是 7 天）"),
    dict(id="M08-age-basis-dispatch-time",
         file=D("DelegationLedger.java"),
         old="long ageBase = e.finishedAt > 0L ? e.finishedAt : e.updatedAt;",
         new="long ageBase = e.dispatchedAt;",
         expect=["pruneDeletesExpiredTerminalScenesAndKeepsEverythingElse",
                 "pruneHonoursTheRetentionWindowBoundary"],
         why="拿建账时刻当年龄基准 ⇒ 刚完成的老事件永不回收（盘只涨不落）"),
    dict(id="M09-orphan-adoption-ignores-age",
         file=D("DelegationLedger.java"),
         old="""            if (e.updatedAt + staleOlderThanMillis > now) {
                continue;
            }""",
         new="""            if (false && e.updatedAt + staleOlderThanMillis > now) {
                continue;
            }""",
         expect=["orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes"],
         why="认领孤儿不看心跳年龄 ⇒ 真在飞的委托被判死"),
    dict(id="M10-orphan-adoption-eats-terminal",
         file=D("DelegationLedger.java"),
         old="""        for (Entry e : list()) {
            if (e.state.terminal()) {
                continue;
            }
            if (e.updatedAt""",
         new="""        for (Entry e : list()) {
            if (false && e.state.terminal()) {
                continue;
            }
            if (e.updatedAt""",
         expect=["orphanAdoptionMarksStaleNonTerminalScenesUnknownAndKeepsLiveOnes",
                 "asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery"],
         why="收工的现场也被再过一遍孤儿流程 ⇒ DONE 被改判 UNKNOWN"),
    dict(id="M11-done-means-delivered",
         file=D("DelegateManager.java"),
         old='advanceQuiet(live, DelegateEvent.TASK_COMPLETED, "异步收工");',
         new=('live.delivery = DeliveryState.DELIVERED;\n'
              '                    advanceQuiet(live, DelegateEvent.TASK_COMPLETED, "异步收工");'),
         expect=["syncDelegationWritesASceneThatIsReadableWithoutTheCaller",
                 "asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",
                 "unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached"],
         why="★ 本期主攻的那一格：子代理自己给自己签收条（P16 同型洞的原形状）"),
    dict(id="M12-scene-never-created",
         file=D("DelegationLedger.java"),
         old="""                File d = dirOf(e.id);
                if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                    throw new IOException("mkdirs 失败: " + d);
                }
                writeHeaderOnce(d, e);""",
         new="""                File d = dirOf(e.id);
                if (false && !d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                    throw new IOException("mkdirs 失败: " + d);
                }
                writeHeaderOnce(d, e);""",
         expect=["createWritesTheSceneBeforeAnythingRuns",
                 "asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery",
                 "unclampedChildBudgetIsRecordedOnDiskWhenParentIsNotAttached"],
         why="建账时不建目录 ⇒ 现场只在内存里，进程死了就没了（靶子二的正面）"),
    dict(id="M13-non-atomic-state-write",
         file=D("DelegationLedger.java"),
         old="""                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);""",
         new="""                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);""",
         expect=["atomicWriteLeavesNoTempFileAndNoHalfState"],
         why="丢掉 ATOMIC_MOVE：单进程读写序下这条不可观测（预期可能 SURVIVED，见 EVIDENCE §6）"),
    dict(id="M14-legacy-status-silent-default",
         file=D("DelegateState.java"),
         old='throw new IllegalArgumentException("未知委托状态字面量: " + raw);',
         new='return UNKNOWN;',
         expect=["legacyStatusLiteralsAreNormalizedOrRejected"],
         why="认不出的状态字面量被静默吞成 UNKNOWN ⇒ 拼错不报错，回到改造前的裸字符串病"),
    dict(id="M15-redaction-off",
         file=D("DelegationLedger.java"),
         old='String s = KEYISH.matcher(text).replaceAll("sk-***REDACTED***");',
         new="String s = text;",
         expect=["secretsAreMaskedBeforeHittingDisk"],
         why="落盘前不遮密钥（她的 _redact 同位被摘）"),
    dict(id="M16-id-validation-off",
         file=D("DelegationLedger.java"),
         old="if (!ID_OK.matcher(s).matches()) {",
         new="if (false && !ID_OK.matcher(s).matches()) {",
         expect=["pathEscapingIdsAreRejected"],
         why="id 不再校验 ⇒ `../` 能把台账写出根目录（现场台账变任意目录删除器）"),
    dict(id="M17-gate-counts-running-only",
         file=D("DelegateManager.java"),
         old="""            long flying = async.values().stream()
                    .filter(d -> !"DONE".equals(d.status) && !"FAILED".equals(d.status))
                    .count();""",
         new="""            long flying = async.values().stream()
                    .filter(d -> "RUNNING".equals(d.status))
                    .count();""",
         expect=["concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth"],
         why="并发闸退回只数 RUNNING（修之前的写法）⇒ 连发可越过 width"),
    dict(id="M18-poll-burns-delivery",
         file=D("DelegateManager.java"),
         old='if (!"DONE".equals(d.status) && !"FAILED".equals(d.status)) {',
         new="if (false) {",
         expect=["asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery"],
         why="还没收工就去 claim ⇒ 看进度本身烧投递配额，8 次轮询后结果被记成 dropped"),
    dict(id="M19-stale-copy-overwrites-scene",
         file=D("DelegationLedger.java"),
         old="if (disk.state.terminal()) {",
         new="if (false && disk.state.terminal()) {",
         expect=["stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins"],
         why="关掉 reconcile ⇒ 后写的陈旧副本覆盖先落的终态：按了停止、盘上却是 DONE"),
    dict(id="M20-stop-leaves-no-scene",
         file=D("DelegateManager.java"),
         old="if (liveId != null) {",
         new="if (false && liveId != null) {",
         expect=["stopOnFlyingChildWritesStoppedSceneAndFirstTerminalVerdictWins"],
         why="/stop 不再落 STOPPED ⇒ 台账分不清\"死了\"和\"被停了\""),
    dict(id="M21-agents-hides-delivery",
         file=D("DelegateManager.java"),
         old='.append("  投递=").append(delivery.describe(d.id))',
         new='.append("").append("")',
         expect=["asyncSceneExistsBeforeTheChildFinishesAndDoneIsNotDelivery"],
         why="/agents 不再显示投递格 ⇒ 界面全绿而账上是空的（对操作员撒谎）"),
]

# ---------------------------------------------------------------------------


def sh(args, cwd=REPO, timeout=None):
    """跑一条命令，返回 (rc, 合并输出)。超时则整组干掉，绝不留孤儿占着锁。"""
    p = subprocess.Popen(args, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         close_fds=False, preexec_fn=os.setsid)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, (out or b"").decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        except OSError:
            pass
        out, _ = p.communicate()
        return 124, (out or b"").decode("utf-8", "replace") + "\n<<TIMEOUT>>"


def lock_path():
    rc, out = sh(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"])
    if rc != 0:
        die("git rev-parse 失败: " + out[-400:])
    return os.path.join(out.strip(), "zbot-mutlock")


def die(msg, code=1):
    print("!! " + msg)
    sys.exit(code)


def pid_alive(pid):
    """只问"这个 pid 还在不在"，绝不发信号（0 号信号是探活不是杀）。"""
    try:
        os.kill(pid, 0)
        return True
    except OSError as e:
        return e.errno == errno.EPERM      # 活着，只是不是我放的
    except Exception:
        return False


def acquire_lock():
    """锁文件语义：owner 进程死了留下的锁是**死锁**，接管（先备份原读数）。

    为什么要接管而不是 rc=4 走人：p14 那一棒 16:29 崩在半路，锁文件留在原地，
    此后全编队每一棒都会 rc=4 —— 死锁过期不等于可以白跑一整轮四杠。
    红线仍然守住：不 sleep 死等、不 kill 别人、别人的锁若**主人还活着**一律不碰。
    """
    lp = lock_path()
    for _ in range(3):
        stale = None
        try:
            fd = os.open(lp, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        except OSError as e:
            if e.errno != errno.EEXIST:
                raise
            try:
                with open(lp) as f:
                    stale = f.read().strip()
            except OSError:
                stale = "<读不到>"
            hp = None
            try:
                hp = int(stale.split()[0])
            except (ValueError, IndexError):
                hp = None
            if hp is not None and pid_alive(hp):
                print("rc=4 LOCK_BUSY 锁被别人占着（主人还活着）：" + stale)
                print("P27 杠② NO-RUN（不 sleep 死等、不 kill 别人），回头整批重跑。")
                sys.exit(4)
            bak = os.path.join(OUT, "stale-lock-owner.txt")
            try:
                with open(bak, "w") as f:
                    f.write(stale + "\n")
            except OSError:
                pass
            print("== 锁是死锁（owner 已不在）：原读数 %s ⇒ 备份到 %s 后接管 ==" % (stale, bak))
            try:
                os.unlink(lp)
            except OSError:
                pass
            time.sleep(1)
            continue
        os.write(fd, ("%d p27a-mutation %s\n" % (os.getpid(), time.strftime("%Y-%m-%dT%H:%M:%S"))).encode())
        os.close(fd)
        return lp
    die("锁始终取不到（连续 3 次）", 4)
    return None


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def forever_wait_health_check():
    """杠② 前置体检：测试里不许出现无界 await()/waitFor()（P14a 死法）。"""
    rc, out = sh(["grep", "-rn", "-E", r"\.await\(|\.waitFor\(", TEST_DIR])
    hits = [l for l in out.splitlines() if l.strip()]
    print("== 永挂体检：await()/waitFor() 命中 %d 处 ==" % len(hits))
    bad = []
    for line in hits:
        bounded = ("TimeUnit.SECONDS" in line) or ("TimeUnit.MILLISECONDS" in line) \
                  or re.search(r"await\(\s*[0-9_]+\s*,", line) or re.search(r"waitFor\(\s*[0-9_]+\s*,", line)
        print(("  OK   " if bounded else "  UNBOUND ") + line)
        if not bounded:
            bad.append(line)
    if bad:
        die("发现 %d 处无界等待，变异跑起来会占死全编队的锁：%s" % (len(bad), bad[0]), 5)
    print("== 体检通过：0 处无界等待 ==")
    return len(hits)


def failed_tests_from_surefire():
    """从 surefire XML 里取具名红用例（比抠控制台稳）。"""
    d = os.path.join(REPO, "z-bot-core/target/surefire-reports")
    out = set()
    if not os.path.isdir(d):
        return out
    for fn in sorted(os.listdir(d)):
        if not fn.startswith("TEST-") or not fn.endswith(".xml"):
            continue
        xml = open(os.path.join(d, fn), encoding="utf-8", errors="replace").read()
        for block in re.split(r"(?=<testcase )", xml):
            if not block.startswith("<testcase"):
                continue
            head = block.split("</testcase>")[0]
            m = re.search(r"<testcase[^>]*name=\"([^\"]+)\"", head)
            if m and ("<failure" in head or "<error" in head):
                out.add(m.group(1))
    return out


def run_suite(tag):
    """跑一套具名范围的用例；每次先把 surefire-reports 清空，否则上一支的红会漏进这一支。"""
    log = os.path.join(OUT, tag + ".log")
    reports = os.path.join(REPO, "z-bot-core/target/surefire-reports")
    if os.path.isdir(reports):
        shutil.rmtree(reports)
    rc, out = sh(["mvn", "-o", "test", "-pl", "z-bot-core",
                  "-Dtest=" + SCOPE, "-DfailIfNoTests=false",
                  "-Dsurefire.failIfNoSpecifiedTests=false"], timeout=MVN_TIMEOUT)
    with open(log, "w") as f:
        f.write(out)
    failed = failed_tests_from_surefire()
    if not failed:
        for m in re.finditer(r"^\[ERROR\]\s+[\w.]*?\.([\w$]+)\s+--\s+Time elapsed.*<<< (?:FAILURE|ERROR)!", out, re.M):
            failed.add(m.group(1))
    classes = re.findall(r"<<< (?:FAILURE|ERROR)! -- in ([\w.$]+)", out)
    summary = re.findall(r"^\[INFO\] Tests run:.*$", out, re.M)
    return rc, failed, classes, summary, log


def restore(path, backup, want_md5):
    shutil.copyfile(backup, path)
    return md5(path) == want_md5


def main():
    os.makedirs(BAK, exist_ok=True)
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    if os.path.isdir(BAK):
        shutil.rmtree(BAK)
    os.makedirs(BAK, exist_ok=True)

    try:
        lp = acquire_lock()
    except OSError:
        rc, who = sh(["cat", lock_path()])
        print("rc=4 LOCK_BUSY 锁被别人占着：" + (who or "").strip())
        print("P27 杠② NO-RUN（不 sleep 死等、不 kill 别人），回头整批重跑。")
        sys.exit(4)
    print("== 锁已取: " + lp + " ==")
    try:
        code = run_all()
    finally:
        try:
            os.unlink(lp)
            print("== 锁已释放 ==")
        except OSError:
            pass
    sys.exit(code)


def run_all():
    hits = forever_wait_health_check()
    rows = []
    header = ["mutant", "file", "outcome", "mvn_rc", "killed_by_or_red_tests", "expected_red",
              "unexpected", "elapsed_s", "md5_restored"]

    # ---- 基线必须绿 ----
    print("== 基线（未注入）==")
    t0 = time.time()
    rc, failed, classes, summary, log = run_suite("baseline")
    print("   rc=%d elapsed=%.1fs %s" % (rc, time.time() - t0, summary[-1] if summary else "<无汇总>"))
    baseline_green = (rc == 0)
    if not baseline_green:
        print("!! 基线不绿 ⇒ 全部判 BASELINE_NOT_GREEN，本轮不出判定（日志 " + log + "）")
        for m in MUTANTS:
            rows.append([m["id"], m["file"], "BASELINE_NOT_GREEN", str(rc), "", ";".join(m["expect"]),
                         "n/a", "0", "n/a"])
        write_ledger(rows, header, 0, hits, baseline_green, [])
        return 1

    # ---- 逐支注入 ----
    counts = {}
    survivors = []
    for m in MUTANTS:
        path = os.path.join(REPO, m["file"])
        rel = m["id"]
        backup = os.path.join(BAK, m["id"].replace("/", "_"))
        shutil.copyfile(path, backup)
        want = md5(path)
        src = open(path, encoding="utf-8").read()
        n = src.count(m["old"])
        if n != 1:
            outcome = "INJECTION_NOT_APPLIED"
            detail = "原文命中 %d 次（要求恰好 1 次）" % n
            rc_i, failed_i, classes_i, summary_i, log_i = None, set(), [], [], None
            elapsed = 0.0
            restored = "n/a"
        else:
            open(path, "w", encoding="utf-8").write(src.replace(m["old"], m["new"], 1))
            if md5(path) == want:
                outcome = "INJECTION_NOT_APPLIED"
                detail = "注入后文件字节未变"
                rc_i, failed_i, elapsed = None, set(), 0.0
                restored = "n/a"
            else:
                t0 = time.time()
                rc_i, failed_i, classes_i, summary_i, log_i = run_suite(m["id"])
                elapsed = time.time() - t0
                restored = "OK"
                if rc_i == 124:
                    outcome = "ERROR"
                    detail = "mvn 超时（永挂嫌疑），日志 " + log_i
                elif rc_i == 0:
                    outcome = "SURVIVED"
                    detail = "全绿：没有任何用例咬住这支变异"
                elif not failed_i and not classes_i:
                    # 编译不过 / 压根没跑到用例：这类"红"不携带任何判据信息，
                    # 记成 NONINFORMATIVE 而不是 KILLED，免得拿假绿冒充杀变异。
                    outcome = "NONINFORMATIVE"
                    detail = "rc=%d 但既无具名红也无类级红（编译断或没跑到用例），日志 %s" % (rc_i, log_i)
                else:
                    outcome = "KILLED"
                    detail = ",".join(sorted(failed_i)) or ("类级红:" + ",".join(classes_i))
                ok = restore(path, backup, want)
                if not ok:
                    outcome = "RESTORE_FAILED"
                    detail += " / 还原后 md5 不符（立刻停机）"
                    counts[outcome] = counts.get(outcome, 0) + 1
                    rows.append([m["id"], m["file"], outcome, str(rc_i), detail,
                                 ";".join(m["expect"]), "n/a", "%.1f" % elapsed, "BAD"])
                    write_ledger(rows, header, counts, hits, baseline_green, survivors)
                    die("还原失败，现场不可信，停机: " + m["file"], 3)
        # 预期红集对账
        unexpected = ""
        if outcome == "KILLED":
            missed = [t for t in m["expect"] if t not in failed_i]
            extra = sorted(x for x in failed_i if x not in m["expect"])
            bits = []
            if missed:
                bits.append("预期红却没红:" + ",".join(missed))
            if extra:
                bits.append("预期外变红:" + ",".join(extra))
            unexpected = " | ".join(bits) if bits else "预期内"
        elif outcome == "SURVIVED":
            survivors.append((m["id"], m["why"]))
        counts[outcome] = counts.get(outcome, 0) + 1
        rows.append([m["id"], m["file"], outcome, str(rc_i), detail, ";".join(m["expect"]),
                     unexpected, "%.1f" % elapsed, restored])
        print("%-34s %-22s rc=%-4s %s" % (m["id"], outcome, rc_i, unexpected or detail[:90]))
        sys.stdout.flush()
        # 每支都落一次台账：整批 22 跑要一个多小时，中途掉线也留得下已判定的部分
        write_ledger(rows, header, counts, hits, baseline_green, survivors)

    write_ledger(rows, header, counts, hits, baseline_green, survivors)

    # ---- 收尾：src/main 必须干净 ----
    rc, out = sh(["git", "status", "--porcelain", "--", MAIN_DIR])
    dirty = [l for l in out.splitlines() if l.strip() and " M " not in l]
    rc2, out2 = sh(["git", "diff", "--name-only", "--", MAIN_DIR])
    print("== 收尾 src/main 洁净检查 ==")
    print("   git diff --name-only (src/main/delegate): " + (out2.strip() or "<空>"))
    print("   git status --porcelain 里的非预期条目: " + (("\n     " + "\n     ".join(dirty)) if dirty else "<无>"))
    if out2.strip():
        die("变异之后 src/main 没有还原干净: " + out2.strip(), 3)
    print("== 计数: " + ", ".join("%s=%d" % (k, counts[k]) for k in sorted(counts)) + " ==")
    if survivors:
        print("== SURVIVED 点名 ==")
        for sid, why in survivors:
            print("   " + sid + " —— " + why)
    return 0


def write_ledger(rows, header, counts, hits, baseline_green, survivors):
    """LEDGER.tsv 只由本脚本自写；#generated_by 必晚于 .py 的 mtime。"""
    tmp = LEDGER + ".part"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write("# p27 杠② 变异台账（脚本自写，勿手改）\n")
        f.write("# repo=%s scope=%s mutants=%d\n" % (REPO, SCOPE, len(MUTANTS)))
        f.write("# forever_wait_hits_bounded=%d baseline_green=%s\n" % (hits, "yes" if baseline_green else "NO"))
        if isinstance(counts, dict):
            f.write("# counts " + " ".join("%s=%d" % (k, counts[k]) for k in sorted(counts)) + "\n")
        if survivors:
            f.write("# survivors " + ",".join(s for s, _ in survivors) + "\n")
        f.write("\t".join(header) + "\n")
        for r in rows:
            f.write("\t".join(str(x).replace("\t", " ") for x in r) + "\n")
        f.write("#generated_by %s pid=%d\n" % (time.strftime("%Y-%m-%dT%H:%M:%S%z"), os.getpid()))
    shutil.move(tmp, LEDGER)


if __name__ == "__main__":
    main()
