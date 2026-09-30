#!/usr/bin/env python3
"""P23a 杠②：技能体系（frontmatter / 技能→斜杠命令 / sync+origin_hash / guard 子集）变异注入。

- 逐字节备份 + 还原；注入前/后 md5 与 `git show HEAD:<path>` 三值对账。
- 预期红集在跑之前写死（EXPECTED 表），跑完只能机械分五档。
- LEDGER.tsv 只由本脚本写出。
- 独占 flock：$(git rev-parse --git-common-dir)/zbot-mutlock，抢不到 rc=4 直接退出。
"""
import hashlib
import os
import re
import subprocess
import sys
import fcntl

REPO = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                      cwd=os.path.dirname(os.path.abspath(__file__)),
                      capture_output=True, text=True).stdout.strip()
SKILL = "z-bot-core/src/main/java/com/zifang/z/bot/skill"
OUT_DIR = os.path.join(REPO, "_doc", "acceptance", "p23")
LEDGER = os.path.join(OUT_DIR, "LEDGER.tsv")
RAW = os.path.join(OUT_DIR, "mutation-raw")

GUARD_IDS = [
    "env_exfil_curl", "env_exfil_wget", "ssh_dir_access", "zbot_config_secret_read",
    "dump_all_env", "hardcoded_secret", "embedded_private_key", "prompt_injection_ignore",
    "sys_prompt_override", "leak_system_prompt", "deception_hide", "translate_execute",
    "destructive_root_rm", "destructive_home_rm", "truncate_system", "reverse_shell",
    "tunnel_service", "curl_pipe_shell", "echo_pipe_exec", "base64_decode_pipe",
    "eval_string", "path_traversal_deep", "persistence_cron", "shell_rc_mod",
    "sudo_usage", "crypto_mining",
]

# name -> (path, old_literal, new_literal, expected_red_testclasses)
MUTANTS = {}


def m(name, path, old, new, expect):
    assert name not in MUTANTS, name
    MUTANTS[name] = (path, old, new, expect)


# ── §2 技能→斜杠命令的三条硬规则 + slug + 记账 ────────────────────────────────
# P23c/D-1 之后 `reserved` 的入参是**命令全名（含斜杠）**，归一只在 SkillCommands 一处。
# 下面三支是这一条口径的守卫（工单 §1.3 要求的双向变异 + 一支"两边都容错"）：
# 杀不掉任何一支就说明口径没统一，要回去改码，不许记 SURVIVED。
m("core_name_collision_skip_removed", f"{SKILL}/SkillCommands.java",
  "if (reserved != null && reserved.test(key)) {",
  "if (false && reserved != null && reserved.test(key)) {",
  ["SkillCommandsTest", "SlashRegistrySkillCommandTest",
   "SkillCommandPlanProductionWiringTest"])
m("reserved_arity_normalized_away", f"{SKILL}/SkillCommands.java",
  "if (reserved != null && reserved.test(key)) {",
  "if (reserved != null && reserved.test(slug)) {",
  ["SkillCommandsTest", "SlashRegistrySkillCommandTest",
   "SkillCommandPlanProductionWiringTest"])
m("caller_side_normalization_reintroduced",
  "z-bot-core/src/main/java/com/zifang/z/bot/slash/SlashRegistry.java",
  "SkillCommands.plan(skills, name -> find(name) != null);",
  "SkillCommands.plan(skills, slug -> find(\"/\" + slug) != null);",
  ["SlashRegistrySkillCommandTest", "SkillCommandPlanProductionWiringTest"])
m("duplicate_slug_first_wins_removed", f"{SKILL}/SkillCommands.java",
  "if (plan.byKey.containsKey(key)) {",
  "if (false && plan.byKey.containsKey(key)) {",
  ["SkillCommandsTest", "SlashRegistrySkillCommandTest"])
m("stacked_limit_raised_to_99", f"{SKILL}/SkillCommands.java",
  "public static final int MAX_STACKED_SKILLS = 5;",
  "public static final int MAX_STACKED_SKILLS = 99;",
  ["SkillCommandsTest"])
m("slug_normalization_removed", f"{SKILL}/SkillCommands.java",
  "String s = raw.trim().toLowerCase(Locale.ROOT);",
  "String s = raw.trim();",
  ["SkillCommandsTest"])
m("hidden_skill_accounting_removed", f"{SKILL}/SkillCommands.java",
  "            if (!s.offerable()) {",
  "            if (false && !s.offerable()) {",
  ["SkillCommandsTest", "SlashRegistrySkillCommandTest"])
m("stacked_split_never_consumes", f"{SKILL}/SkillCommands.java",
  "while (keys.size() < MAX_STACKED_SKILLS - 1) {",
  "while (false && keys.size() < MAX_STACKED_SKILLS - 1) {",
  ["SkillCommandsTest"])

# ── §1 frontmatter 的门控 ─────────────────────────────────────────────────────
m("platform_gate_removed", f"{SKILL}/SkillLoader.java",
  "if (!matchesPlatform(platforms, platform)) {",
  "if (false && !matchesPlatform(platforms, platform)) {",
  ["SkillFrontmatterTest", "SkillCommandsTest", "SlashRegistrySkillCommandTest"])
m("environment_gate_removed", f"{SKILL}/SkillLoader.java",
  "} else if (!matchesEnvironment(environments, activeEnvironments())) {",
  "} else if (false && !matchesEnvironment(environments, activeEnvironments())) {",
  ["SkillFrontmatterTest", "SlashRegistrySkillCommandTest"])
m("prerequisites_become_hard_gate", f"{SKILL}/SkillLoader.java",
  "        String setupNote = null;\n        if (!missingEnv.isEmpty()) {",
  "        String setupNote = null;\n        if (!missingEnv.isEmpty()) {\n            hidden = \"prerequisites 缺 env_var\";",
  ["SkillFrontmatterTest"])
m("hermes_metadata_namespace_removed", f"{SKILL}/SkillLoader.java",
  'Map<String, Object> hermes = asMap(metadata.get("hermes"));',
  'Map<String, Object> hermes = new LinkedHashMap<String, Object>();',
  ["SkillFrontmatterTest"])

# ── §3 sync + origin_hash ─────────────────────────────────────────────────────
m("origin_hash_always_overwrite", f"{SKILL}/SkillSync.java",
  "            if (!userHash.equals(originHash)) {",
  "            if (false && !userHash.equals(originHash)) {",
  ["SkillSyncTest"])
m("origin_hash_never_records", f"{SKILL}/SkillSync.java",
  "                    manifest.put(name, dirHash(dest));\n                    r.copied.add(name);",
  "                    r.copied.add(name);",
  ["SkillSyncTest"])
m("user_deleted_resurrected", f"{SKILL}/SkillSync.java",
  "        Map<String, String> manifest = readManifest(skillsRoot);\n        Map<String, File> bundled = bundledSkills(bundledDir);",
  "        Map<String, String> manifest = new LinkedHashMap<String, String>();\n        Map<String, File> bundled = bundledSkills(bundledDir);",
  ["SkillSyncTest"])
m("sync_guard_block_removed", f"{SKILL}/SkillSync.java",
  "SkillGuard.ScanResult scan = SkillGuard.scanSkill(src, source);\n            if (scan.blocked) {",
  "SkillGuard.ScanResult scan = SkillGuard.scanSkill(src, source);\n            if (!scan.blocked) {",
  ["SkillSyncTest"])
m("install_guard_block_removed", f"{SKILL}/SkillSync.java",
  "            if (scan.blocked) {\n                r.suppressed.add(inPlace.getName());",
  "            if (false && scan.blocked) {\n                r.suppressed.add(inPlace.getName());",
  ["SkillSyncTest"])
m("dir_hash_ignores_content", f"{SKILL}/SkillSync.java",
  "                md5.update(r.getBytes(StandardCharsets.UTF_8));\n"
  "                md5.update(Files.readAllBytes(f.toPath()));",
  "                md5.update(r.getBytes(StandardCharsets.UTF_8));\n"
  "                md5.update(Files.readAllBytes(f.toPath()).length == 0 ? new byte[0] : new byte[1]);",
  ["SkillSyncTest"])

# ── §4 guard：每条规则各摘一条 ─────────────────────────────────────────────────
for gid in GUARD_IDS:
    m(f"guard_rule_disabled__{gid}", f"{SKILL}/SkillGuard.java",
      f'add("{gid}",', f'add("disabled_{gid}",',
      ["SkillGuardTest"])
m("guard_invisible_unicode_removed", f"{SKILL}/SkillGuard.java",
  'new Finding("invisible_unicode", "high", "injection",',
  'new Finding("disabled_invisible_unicode", "high", "injection",',
  ["SkillGuardTest"])
m("guard_structural_limit_removed", f"{SKILL}/SkillGuard.java",
  "        if (count > MAX_FILE_COUNT) {",
  "        if (false && count > MAX_FILE_COUNT) {",
  ["SkillGuardTest"])
m("guard_dangerous_policy_weakened", f"{SKILL}/SkillGuard.java",
  '        if ("bundled".equals(trust) || "local".equals(trust)) {\n            blocked = "dangerous".equals(verdict);',
  '        if ("bundled".equals(trust) || "local".equals(trust)) {\n            blocked = false;',
  ["SkillGuardTest"])


def md5_bytes(b):
    return hashlib.md5(b).hexdigest()


def head_bytes(path):
    return subprocess.run(["git", "show", f"HEAD:{path}"], cwd=REPO,
                          capture_output=True).stdout


def git_common_dir():
    return subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=REPO,
                          capture_output=True, text=True).stdout.strip()


def acquire_lock():
    lock_path = os.path.join(os.path.abspath(git_common_dir()), "zbot-mutlock")
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(fd)
        print(f"lock: 抢不到独占锁 {lock_path} ⇒ rc=4 退出（不重试）")
        sys.exit(4)
    print(f"lock: 已持有 {lock_path}")
    return fd


def run_mvn(test_classes, log_path):
    flt = ",".join(test_classes)
    cmd = ["mvn", "-o", "test", f"-Dtest={flt}", "-DfailIfNoSpecifiedTests=false", "-q"]
    with open(log_path, "w") as fh:
        rc = subprocess.call(cmd, cwd=REPO, stdout=fh, stderr=subprocess.STDOUT)
    with open(log_path) as fh:
        out = fh.read()
    # surefire 每个测试类打一行分账，最后一行是总计 —— 取最后一次匹配才是总口径
    runs = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)", out)
    if runs:
        total = int(runs[-1][0])
        red = int(runs[-1][1]) + int(runs[-1][2])
    else:
        total, red = 0, 0
    # 分隔符实测是 " <<< FAILURE! -- in com.zifang...Test"（两个短横，不是三个）
    failed_classes = set(re.findall(
        r"--\s+in com\.zifang\.z\.bot\.[\w.]+\.(\w+)", out))
    errored = [ln for ln in out.splitlines() if "COMPILATION ERROR" in ln]
    return rc, total, red, failed_classes, bool(errored), out


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    os.makedirs(RAW, exist_ok=True)
    lock_fd = acquire_lock()
    rows = []
    header = ["mutant", "path", "expected_red", "actual_red_classes", "bucket",
              "tests_run", "base_md5", "before_md5", "after_md5", "mvn_rc", "log"]
    for name in sorted(MUTANTS):
        path, old, new, expect = MUTANTS[name]
        if only and only not in name:
            continue
        full = os.path.join(REPO, path)
        with open(full, "rb") as fh:
            original = fh.read()
        base_md5 = md5_bytes(head_bytes(path))
        before_md5 = md5_bytes(original)
        if base_md5 != before_md5:
            rows.append([name, path, ",".join(expect), "", "BROKEN", 0,
                         base_md5, before_md5, "", -1, "工作树与 HEAD 不一致，拒绝注入"])
            continue
        if original.count(old.encode()) != 1:
            rows.append([name, path, ",".join(expect), "", "NO-RUN", 0,
                         base_md5, before_md5, "", -1,
                         f"锚点命中 {original.count(old.encode())} 次，不是唯一"])
            continue
        mutated = original.replace(old.encode(), new.encode(), 1)
        with open(full, "wb") as fh:
            fh.write(mutated)
        inj_md5 = md5_bytes(open(full, "rb").read())
        log = os.path.join(RAW, f"{name}.log")
        try:
            rc, total, red, failed, broken, out = run_mvn(expect, log)
        except FileNotFoundError:
            rows.append([name, path, ",".join(expect), "", "NO-RUN", 0,
                         base_md5, inj_md5, "", -1, "mvn 不可用"])
            failed, broken, rc, total = set(), True, -1, 0
            out = ""
        # 还原 + 三值对账
        with open(full, "wb") as fh:
            fh.write(original)
        after_md5 = md5_bytes(open(full, "rb").read())
        if after_md5 != base_md5:
            rows.append([name, path, ",".join(expect), "", "BROKEN", total,
                         base_md5, inj_md5, after_md5, rc, "还原后 md5 与 HEAD 不一致！"])
            continue
        hit = sorted(set(expect) & failed)
        unexpected = sorted(failed - set(expect))
        if broken and total == 0:
            bucket = "BROKEN"
        elif red == 0:
            bucket = "GREEN-BUT-MUTATED"
        elif len(hit) == len(expect) and not unexpected:
            bucket = "RED-OK"
        elif hit:
            bucket = "PARTIAL"
        else:
            bucket = "PARTIAL"
        rows.append([name, path, ",".join(expect), ",".join(sorted(failed)) or "-",
                     bucket, total, base_md5, inj_md5, after_md5, rc, os.path.basename(log)])
        print(f"{bucket:18s} {name:44s} red={','.join(sorted(failed)) or '-'}")
    with open(LEDGER, "w") as fh:
        fh.write("\t".join(header) + "\n")
        for r in rows:
            fh.write("\t".join(str(x) for x in r) + "\n")
    counts = {}
    for r in rows:
        counts[r[4]] = counts.get(r[4], 0) + 1
    print("LEDGER:", LEDGER)
    print("五档计数:", " ".join(f"{k}={v}" for k, v in sorted(counts.items())), "合计", len(rows))
    fcntl.flock(lock_fd, fcntl.LOCK_UN)
    os.close(lock_fd)


if __name__ == "__main__":
    main()
