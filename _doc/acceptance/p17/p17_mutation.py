#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P17（cron 投递闭环）变异检验：把本期新加的每一条守卫逐条改坏，看有没有**具名测试**判红。

纪律照抄 _doc/acceptance/p11b/p11b_mutation.py：
  * 注入前逐条校验锚点出现次数（锚点漂了 = 量具坏了，不是代码坏了，直接 FATAL 不收读数）；
  * 每个变异体跑完按内存里的原文逐字节还原，收尾再核对全量 md5；
  * 判定只认 surefire XML 里的 testcase 名：点名的那条红了才算 RED-OK；
    红了别的算 PARTIAL；全绿算 GREEN-BUT-MUTATED（如实记账，不改期望集凑数）；
    编译不过算 BROKEN；
  * LEDGER.tsv 由本脚本机械输出，禁止手敲。

点名集为空、预期就是 GREEN-BUT-MUTATED 的两条（M8/M9）：跨进程 flock 的等待上限
和 gateway 那条装配线，都要**真两个进程 / 真通道**才显形，进程内单测造不出那个现场 ——
它们的证据在 p17_e2e.py，不在这里。
  * M8 的那份证据已实测（2026-09-26）：`python3 _doc/acceptance/p17/p17_mutation.py` 的
    注入串原样打进 `JOBS_LOCK_TIMEOUT_MILLIS`、重打 jar 后单跑 E 段
    （`P17_ONLY=E python3 _doc/acceptance/p17/p17_e2e.py`）⇒ **E2 FAIL**（邻居正攥着
    `.jobs.lock`，写请求却当场落盘：`盘上=['E-free','E-held','E-seed']`）+ **E4 FAIL**
    （日志出现"等 .jobs.lock 的跨进程锁超过 0ms … 降级为只用进程内锁"），其余 5 条仍绿；
    还原后（md5 与基线逐字节相同）重跑 E 段 7/7 全绿。两跑日志见 logs/。
    ⇒ M8 在本表里记 GREEN-BUT-MUTATED 是"单测这一层看不见"，不是"没人能杀"。
  * M9（gateway 的 cron 装配线）已实测（2026-09-26）：同样注入后**整跑** p17_e2e.py
    ⇒ `PASS=28 / FAIL=4`，红的正是 A3（webhook 一条没收到，官方那一跑是 1 条）、A4（正文 None）、
    B2（没有"降级 local"那行）、B3（`lastDelivery='ok'` —— 装配摘掉后落回 LocalCronDelivery，
    "投给拉模式控制台"被记成静默成功）。还原后重跑 32/32 全绿。日志 logs/e2e_full_M9.log。
    ⇒ M9 在本表里同样记 GREEN-BUT-MUTATED：单测层看不见装配，**但 E2E 层杀得死**。

复算: python3 _doc/acceptance/p17/p17_mutation.py [id 子串...]
"""
import hashlib
import io
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ZBOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    os.pardir, os.pardir, os.pardir))
CORE = os.path.join(ZBOT, "z-bot-core")
REPORTS = os.path.join(CORE, "target", "surefire-reports")
TESTS = "CronClaimTest,CronDeliveryTest,CronScheduleTest,CronSchedulerTest"
BASELINE_TOTAL = 453   # 本期收口后的全量条数（399 起步 + 本期新增），收尾核对用

SRC = {
    "sched": "z-bot-core/src/main/java/com/zifang/z/bot/cron/CronScheduler.java",
    "claim": "z-bot-core/src/main/java/com/zifang/z/bot/cron/CronRunClaim.java",
    "ch": "z-bot-core/src/main/java/com/zifang/z/bot/cron/ChannelCronDelivery.java",
    "local": "z-bot-core/src/main/java/com/zifang/z/bot/cron/LocalCronDelivery.java",
    "tools": "z-bot-core/src/main/java/com/zifang/z/bot/cron/CronTools.java",
    "expr": "z-bot-core/src/main/java/com/zifang/z/bot/cron/CronSchedule.java",
    "gw": "z-bot-core/src/main/java/com/zifang/z/bot/cli/GatewayCommand.java",
}

# (id, 文件键, old, new, 期望锚点数, 点名期望红的测试, 说明)
MUTANTS = [
    ("M1 先落账再跑被改成先跑再落账", "sched",
     "            if (!claimDispatch(jobId)) {\n"
     "                return;\n"
     "            }\n"
     "            Thread heartbeat = startClaimHeartbeat(jobId);\n",
     "            Thread heartbeat = startClaimHeartbeat(jobId);\n", 1,
     ["claimIsOnDiskBeforeTheRunnerRuns", "oneShotQuotaIsOnDiskBeforeTheRunnerRuns",
      "oneShotIsNotRerunAfterARestart"],
     "红线 8 本体：跑之前盘上没有账，掉电重启就等于这一枪没开过"),

    ("M2 一次性额度判定放过第二次", "sched",
     "if (job.isOneShot() && job.dispatches >= CronJob.ONESHOT_DISPATCH_LIMIT) {",
     "if (job.isOneShot() && job.dispatches > CronJob.ONESHOT_DISPATCH_LIMIT) {", 1,
     ["oneShotQuotaRemovesTheStaleJobInsteadOfRerunningIt"],
     "at-most-once 变成 at-most-twice（hermes #38758 的一-shot 无限重发同型）"),

    ("M3 负年龄的认领也算活着", "claim",
     "        return ageMillis >= 0 && ageMillis < ttlMillis;",
     "        return ageMillis < ttlMillis;", 1,
     ["futureStampedClaimIsNotTreatedAsLive", "corruptClaimTimestampIsTreatedAsDead"],
     "对端时钟超前时，一条未来时间戳的认领会把任务永久楔死"),

    ("M4 心跳去掉 compare-and-refresh", "claim",
     "    public void beat(Instant now) {\n        this.at = now.toString();\n    }",
     "    public void beat(Instant now) {\n        // 只记账不续期\n    }", 1,
     ["heartbeatKeepsTheClaimLivePastItsTtl", "executeBeatsHeartbeatWhileRunnerIsStillGoing"],
     "心跳保鲜成了空话，长跑任务在 ttl 之后会被别人抢走"),

    ("M5 心跳谁都能续", "sched",
     "if (job == null || job.runClaim == null || !job.runClaim.heldBy(expectedOwner)) {",
     "if (job == null || job.runClaim == null) {", 1,
     ["heartbeatAfterATakeoverIsRejected"],
     "睡一觉醒来的旧跑者能给已被接管的认领续命，于是谁也抢不走"),

    ("M6 重入计数被摘掉（嵌套也去抢 flock）", "sched",
     "        Integer depth = jobsDepth.get();\n"
     "        if (depth != null && depth.intValue() > 0) {\n"
     "            jobsDepth.set(Integer.valueOf(depth.intValue() + 1));\n"
     "            return;\n"
     "        }\n",
     "", 1,
     ["nestedStoreCallsTakeTheFlockOnce"],
     "同一 JVM 里对同一文件区域二次 tryLock 不是排队，是直接抛"),

    ("M7 进程内监视器挂回实例上", "sched",
     "        this.jobsMonitor = monitorFor(jobsLockFile.getAbsolutePath());",
     "        this.jobsMonitor = new java.util.concurrent.locks.ReentrantLock(true);", 1,
     ["twoInstancesOnOneDirNeverDegradeTheirLock"],
     "同进程两个实例打同一个 cron 目录时，第二个当场降级成只锁自己 —— 全量跑里真见过这行 ERROR"),

    ("M8 跨进程 flock 的等待上限归零", "sched",
     "    static final long JOBS_LOCK_TIMEOUT_MILLIS = 30_000L;",
     "    static final long JOBS_LOCK_TIMEOUT_MILLIS = 0L;", 1,
     [],
     "只有真有一个邻居正攥着锁时才显形，进程内单测造不出那个现场 ⇒ 证据在 p17_e2e.py 的 E 段（邻居攥锁对撞，实测 E2/E4 判红）"),

    ("M9 gateway 不把通道表接进 cron", "gw",
     "        if (agent.getCronScheduler() != null) {\n"
     "            agent.getCronScheduler().deliverViaChannels(",
     "        if (false) {\n            ((Object) null).equals(", 1,
     [],
     "红线 2 的装配线：单测里没有 GatewayCommand 这一站 ⇒ 证据在 p17_e2e.py 的 webhook 真投递（实测 A3/A4/B2/B3 判红）"),

    ("M10 local 档不打印（结果又静默蒸发）", "local",
     "        out.println(\"[cron] \" + job.name + \" (\" + job.id + \") @ local\\n\" + body);\n"
     "        out.flush();\n",
     "", 1,
     ["localDeliveryPrintsTheResultToTheGivenStream", "localDeliveryDefaultsToStandardOut"],
     "§1#6 打脸的那句原样回来：没有通道时任务结果蒸发掉"),

    ("M11 origin 没有来源时改成报错", "ch",
     "                LOG.info(\"[cron] {} deliver=origin 但没捕获到来源通道（也没有可回退的默认通道）\"\n"
     "                        + \" —— 按 local 投递，结果同时记在 lastResult\", job.id);\n"
     "                originDegraded = true;\n"
     "                continue;\n",
     "                return \"no origin for deliver=origin\";\n", 1,
     ["originWithoutASourceDegradesToLocalInsteadOfErroring",
      "malformedOriginDegradesInsteadOfTargetingSomething"],
     "她 #43014 的结论被反着改：常态被当成错误"),

    ("M12 unknown channel 被静默吞掉", "ch",
     "                String msg = \"unknown channel '\" + t.channel + \"'\";\n"
     "                LOG.warn(\"[cron] {} {}\", job.id, msg);\n"
     "                errors.add(msg);\n",
     "                String msg = \"unknown channel '\" + t.channel + \"'\";\n"
     "                LOG.warn(\"[cron] {} {}\", job.id, msg);\n", 1,
     ["unknownChannelIsAnErrorNotASilentSuccess"],
     "投了个寂寞还记 ok —— 比报错坏得多"),

    ("M13 组合路由的重复目标不再去重", "ch",
     "                addUnique(targets, new Target(part.substring(0, colon), part.substring(colon + 1)));",
     "                targets.add(new Target(part.substring(0, colon), part.substring(colon + 1)));", 1,
     ["duplicateTargetsFromACombinedRouteAreSentOnce"],
     "all,webhook:x 这种组合会把同一条消息发两遍"),

    ("M14 投递口抛异常被当成成功", "ch",
     "            } catch (Exception e) {\n"
     "                String msg = \"send to \" + t.channel + \" failed: \" + e.getMessage();",
     "            } catch (Exception e) {\n"
     "                String msg = null;", 1,
     ["sendFailureIsCollectedButOtherTargetsStillGetIt"],
     "拼出来的错误串变 null，最后 return null = 成功"),

    ("M15 cronjob 工具丢掉 deliver 参数", "tools",
     "CronJob job = scheduler.add(name, prompt, schedule, str(args, \"deliver\"));",
     "CronJob job = scheduler.add(name, prompt, schedule);", 1,
     ["cronToolCarriesTheDeliverArgumentIntoTheJob"],
     "路由入口断在工具这一层，所有任务都退回 local"),

    ("M16 空输出也记成投递 ok", "sched",
     "            return \"skipped: 没有可投递的输出\";",
     "            return null;", 1,
     ["emptyResultIsNotDeliveredAtAll"],
     "台账说谎：一条消息都没发出去却记 ok"),

    ("M17 一次性任务的 due 判定恒假", "expr",
     "                return !now.toInstant().isBefore(onceAt);",
     "                return false;", 1,
     ["onceIsDueOnlyAtAndAfterTheInstant"],
     "once 任务永远不触发（反向：改成恒真会立刻跑，所以这条只测到点前那一半）"),

    ("M18 路由白名单放过 origin:xxx 这种写法", "ch",
     "                if (\"local\".equals(name) || \"origin\".equals(name) || \"all\".equals(name)) {\n"
     "                    return \"deliver 的 local/origin/all 不带 :<会话>: \" + part;\n"
     "                }\n",
     "", 1,
     ["routeSyntaxIsChecked"],
     "关键字被当成通道名去查表，用户拿到一句看不懂的 unknown channel"),

    ("M19 validateRoute 用回会丢尾空段的 split", "ch",
     "        for (String raw : v.split(\",\", -1)) {",
     "        for (String raw : v.split(\",\")) {", 1,
     ["routeSyntaxIsChecked"],
     "\"local,,\" 静默放过，说法和实现又不一致了"),

    ("M20 ownerId 丢掉随机后缀", "sched",
     "        return pid + \"@\" + born + \"/\" + UUID.randomUUID().toString().substring(0, 8);",
     "        return pid + \"@\" + born;", 1,
     ["claimOfFreshForeignOwnerBlocksDispatch", "heartbeatAfterATakeoverIsRejected"],
     "同 pid 复用（容器里 fork 出来的第二个 scheduler）会互认认领，跨进程防护当场失效"),

    ("M21 tick 改回看内存里的旧任务表", "sched",
     "    private void tickAgainst(ZonedDateTime last, ZonedDateTime now) {\n"
     "        for (CronJob j : jobsOnDisk()) {",
     "    private void tickAgainst(ZonedDateTime last, ZonedDateTime now) {\n"
     "        for (CronJob j : jobs) {", 1,
     ["tickDoesNotRerunAOneShotThatAnotherProcessAlreadyFinished"],
     "隔壁跑完摘除之后，本进程手里那张旧单子会变成\"无账可落\"的放行条 ⇒ 双实例又双跑"),

    ("M22 出关键区不还重入计数", "sched",
     "        jobsDepth.remove();\n        try {\n            FileLock lock = jobsLockHandle;",
     "        try {\n            FileLock lock = jobsLockHandle;", 1,
     ["depthReturnsToZeroWhenTheBodyThrows"],
     "计数留在 1，之后每一次落盘都走\"已经在锁里\"的快路 ⇒ 监视器和 flock 一起形同虚设"),

    ("M23 建任务时不校验路由语法", "sched",
     "        String routeErr = ChannelCronDelivery.validateRoute(route);\n"
     "        if (routeErr != null) {\n"
     "            throw new IllegalArgumentException(routeErr);\n"
     "        }\n",
     "", 1,
     ["addRejectsABrokenRouteAtCreationTime"],
     "\"feishu:\" 这种硬错要等到凌晨三点第一次真投递才冒出来"),

    ("M24 跑完不清认领", "sched",
     "                job.runClaim = null;   // 她的 mark_job_run 同样在这儿清 run_claim（jobs.py:1542）",
     "                // 不清账（观察残留认领的后果）", 1,
     ["finishRunClearsTheClaimAndMarksDeliveryOk"],
     "周期性任务从此每轮都被自己上一轮的残留认领挡住，或者反过来被当成\"没人认领\"重跑"),

    ("M25 投递结论写死 ok", "sched",
     "                job.lastDelivery = conclusion == null ? \"ok\" : conclusion;",
     "                job.lastDelivery = \"ok\";", 1,
     ["deliveryFailureIsRecordedButTheResultSurvives",
      "deliveryThrowingIsCaughtIntoTheLedger",
      "emptyResultIsNotDeliveredAtAll"],
     "台账这一列退化成常量，投没投出去、投给谁失败了，全看不出来"),

    ("M26 投递口吞掉投递返回的错误串", "sched",
     "            String err = delivery.deliver(job, text);\n"
     "            if (err != null) {\n"
     "                LOG.warn(\"[cron] {} 投递失败: {}\", job.id, err);\n"
     "                return err;\n"
     "            }\n",
     "            delivery.deliver(job, text);\n", 1,
     ["deliveryFailureIsRecordedButTheResultSurvives"],
     "通道说\"投不出去\"，调用方却当成成功"),

    ("M27 forChannels 改成大小写敏感查表", "ch",
     "                    if (c != null && name.equalsIgnoreCase(c.name())) {",
     "                    if (c != null && name.equals(c.name())) {", 1,
     ["forChannelsLooksUpIgnoreCaseAgainstALiveList"],
     "deliver 串统一转了小写再去查，通道名带大写（Webhook）就永远 unknown channel"),
]


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def read(key):
    with io.open(os.path.join(ZBOT, SRC[key]), encoding="utf-8") as fh:
        return fh.read()


def write(key, text):
    with io.open(os.path.join(ZBOT, SRC[key]), "w", encoding="utf-8") as fh:
        fh.write(text)


def run_tests():
    if os.path.isdir(REPORTS):
        for name in os.listdir(REPORTS):
            os.remove(os.path.join(REPORTS, name))
    cmd = ["mvn", "-o", "-q", "test", "-pl", "z-bot-core", "-Dtest=" + TESTS,
           "-DfailIfNoTests=false"]
    t0 = time.time()
    proc = subprocess.run(cmd, cwd=ZBOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
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
    return proc.returncode, failing, ran, proc.stdout.decode("utf-8", "replace"), time.time() - t0


def main():
    mutants = MUTANTS
    if len(sys.argv) > 1:
        mutants = [m for m in MUTANTS if any(a in m[0] for a in sys.argv[1:])]
        if not mutants:
            print("FATAL: 选择器没命中任何变异体 id: %s" % sys.argv[1:])
            return 2
    bad = []
    for mid, key, old, new, want, expected, note in mutants:
        got = read(key).count(old)
        if got != want:
            bad.append("%s: 锚点出现 %d 次，期望 %d 次（%s）" % (mid, got, want, SRC[key]))
    if bad:
        print("FATAL 锚点校验失败（代码已漂，别信下面的读数）:")
        for line in bad:
            print("  " + line)
        return 2

    baseline = {k: md5(os.path.join(ZBOT, v)) for k, v in SRC.items()}
    tally = {"RED-OK": 0, "PARTIAL": 0, "GREEN-BUT-MUTATED": 0, "BROKEN": 0}
    rows = []
    for mid, key, old, new, want, expected, note in mutants:
        original = read(key)
        write(key, original.replace(old, new, 1))
        rc, failing, ran, out, secs = run_tests()
        restored = False
        write(key, original)
        try:
            restored = md5(os.path.join(ZBOT, SRC[key])) == baseline[key]
        except Exception:
            restored = False
        if "COMPILATION ERROR" in out:
            verdict = "BROKEN"
        else:
            hit = sorted(set(expected) & failing)
            others = sorted(failing - set(expected))
            if hit and not others:
                verdict = "RED-OK"
            elif hit:
                verdict = "PARTIAL"
            elif failing:
                verdict = "PARTIAL"
            else:
                verdict = "GREEN-BUT-MUTATED"
        tally[verdict] += 1
        who = ",".join(sorted(failing)) if failing else ("全绿（跑了 %d 条）" % ran)
        print("%-34s %-18s 点名=%d/%d ⇒ %s | rc=%s | %.1fs | 还原=%s"
              % (mid, verdict, len(set(expected) & failing), len(expected), who, rc, secs, restored),
              flush=True)
        if verdict != "RED-OK":
            print("     %s" % note)
        rows.append((mid, verdict, "%d/%d" % (len(set(expected) & failing), len(expected)),
                     who, str(rc), str(restored)))
        if not restored:
            print("FATAL: %s 之后文件没还原成基线，停在这里（后面读数不可信）" % mid)
            break

    here = os.path.dirname(os.path.abspath(__file__))
    ledger = os.path.join(here, "LEDGER.tsv")
    with io.open(ledger, "w", encoding="utf-8") as fh:
        fh.write("id\tverdict\tnamed_expected_red\ttest_that_went_red\tsurefire_rc\trestored\n")
        for r in rows:
            fh.write("\t".join(r) + "\n")
        fh.write("# tally\t%s\n" % "\t".join("%s=%d" % (k, tally[k])
                                            for k in ("RED-OK", "PARTIAL",
                                                      "GREEN-BUT-MUTATED", "BROKEN")))
        fh.write("# mutants_injected\t%d\n" % len(rows))
        fh.write("# cron_tests_ran_per_round\t%d\t(只跑 cron 四支测试类，全量 %d 条见验收①)\n"
                 % (ran, BASELINE_TOTAL))
        fh.write("# expected_total_after_p17\t%d\n" % BASELINE_TOTAL)
        fh.write("# ledger_generated_by\tp17_mutation.py\tat\t%s\n"
                 % time.strftime("%Y-%m-%dT%H:%M:%S%z"))

    still = [k for k, v in SRC.items() if md5(os.path.join(ZBOT, v)) != baseline[k]]
    print("\n== 台账 ==")
    for k in ("RED-OK", "PARTIAL", "GREEN-BUT-MUTATED", "BROKEN"):
        print("  %-18s %d" % (k, tally[k]))
    print("  LEDGER -> %s" % ledger)
    print("  final md5 ok: %s" % (not still))
    return 0 if not still else 3


if __name__ == "__main__":
    sys.exit(main())
