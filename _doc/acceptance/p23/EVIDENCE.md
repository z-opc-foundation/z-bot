# P23a EVIDENCE — 技能体系对齐（frontmatter / 技能→斜杠命令 / sync+origin_hash / guard 子集）

- 工单：`~/.cache/zbot-p23-lead/dispatch_p23a.md`
- 工作树：`/private/tmp/zbot-wt-p23`　分支 `w7-p23`　起点 commit `d23cd0d`
- 对标：`~/.hermes/hermes-agent`（只读）
- 本文档所有数字均为本代理本轮实测贴回，未跑一律写 `STATUS: 未跑`。

STATUS: 骨架已落，逐节填充中

---

## §-1 开工时间戳（date 现取）

```
$ date -u +%Y-%m-%dT%H:%M:%SZ   # 开工
2026-09-26T07:35:43Z
```

STATUS: 已实测

---

## §0 复算工单 §0 量具（动笔前）

STATUS: 待填

---

## §1 frontmatter 补齐与行为落地（platforms / environments / prerequisites / metadata.hermes.*）

STATUS: 待填

---

## §2 技能 → 斜杠命令（本棒主战场）

STATUS: 待填

---

## §3 sync + origin_hash（改过不覆盖 / 删过不复活）

STATUS: 待填

---

## §4 安装期安全扫描（guard 规则子集：做了 / 不做两张表）

STATUS: 待填

---

## §5 杠① 全量单测 ×3 串行

STATUS: 待填

---

## §6 杠② 变异注入（p23_mutation.py）

STATUS: 待填

---

## §7 杠③ 真进程 E2E（p23_e2e.py，含验收 A）

STATUS: 待填

---

## §8 杠④ `~/.zbot` 三时点一字未动

STATUS: 待填

---

## §9 安全红线自查

STATUS: 待填

---

## §10 明确不做（要动 X / 因为 Y / 会撞 Z）+ 未做清单

STATUS: 待填

## §0 复算工单 §0 量具（动笔前，实测）

复算命令：
```
cd /private/tmp/zbot-wt-p23
wc -l z-bot-core/src/main/java/com/zifang/z/bot/skill/*.java
ls -R z-bot-core/src/main/java/com/zifang/z/bot/skill/
git grep -n '\.slash' -- 'z-bot-core/src'
wc -l z-bot-core/src/main/java/com/zifang/z/bot/slash/*.java
git grep -n 'origin_hash\|prerequisites\|environments\|skills_sync\|platforms' -- 'z-bot-core/src/main'
git grep -c '@Test' HEAD -- 'z-bot-core/src/test/java/com/zifang/z/bot/skill'
git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'
git grep -c '@Test' HEAD | awk -F: '{s+=$NF} END{print s}'
```

实测：
```
     143 z-bot-core/src/main/java/com/zifang/z/bot/skill/SkillLoader.java
skill/ 目录仅 SkillLoader.java 一个文件
\.slash 生产侧命中：
  BotAgent.java:842  + "  " + (s.slash.isEmpty() ? "" : "(slash: " + s.slash + ")")
  SkillLoader.java:137  this.slash = slash
slash/: 25 SlashCommand.java + 445 SlashRegistry.java = 470 行
origin_hash / prerequisites / environments / skills_sync ⇒ src/main 零命中
  （platforms 仅命中 channel/Gateway.java:139-140 bus.deliverablePlatforms()，与技能无关）
@Test: skill 目录 = 3（全在 SkillLoaderTest.java）
@Test: z-bot-core/src/test = 693
@Test: 全仓 = 739（core 693 + 其余模块 46）
```

判词：工单 §0 逐条**证实** —— `SkillLoader` 143 行、`slash` 字段在生产侧只有"打印"与"自赋值"两处消费者，
**从未注册成任何命令**，javadoc"供通道层把技能注册成斜杠命令"是空头承诺；四个新字段全零命中。
注意工单写"全仓 @Test = 693"，实测 **693 是 `z-bot-core/src/test` 口径**，全仓（含 desktop-packager 等）是 **739**。

STATUS: 已实测（全条证实 + 一处口径差异已记录）

## §1 frontmatter 补齐与行为落地

STATUS: 已实现 + 单测已跑绿（真进程读数在 §7）

复算命令：
```
wc -l z-bot-core/src/main/java/com/zifang/z/bot/skill/*.java
mvn -o test -Dtest='SkillFrontmatterTest' -DfailIfNoSpecifiedTests=false
```

实测（2026-09-26T07:56:18Z 量到的行数；测试是 07:54 那一跑）：
```
     107 z-bot-core/src/main/java/com/zifang/z/bot/skill/Frontmatter.java
     306 z-bot-core/src/main/java/com/zifang/z/bot/skill/SkillCommands.java
     332 z-bot-core/src/main/java/com/zifang/z/bot/skill/SkillGuard.java
     493 z-bot-core/src/main/java/com/zifang/z/bot/skill/SkillSync.java
     493 z-bot-core/src/main/java/com/zifang/z/bot/skill/SkillLoader.java

[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.zifang.z.bot.skill.SkillFrontmatterTest
```

落地的四处字段与<b>行为</b>：
- `platforms: [macos, linux]`（`SkillLoader.matchesPlatform`）⇒ 不匹配的技能<b>既不进命令表也不进 prompt</b>，
  并写可读理由：`platforms=[windows] 与当前平台 macos 不匹配`。缺省 = 全平台（向后兼容）。
  OR 语义、`darwin→macos`/`android→linux` 别名，都对齐她 `skill_utils.py:175-220`。
- `environments: [kanban|docker|s6]`（`matchesEnvironment`）⇒ <b>offer 面</b>相关性门；
  未知标签 fail-open（她 :315-317）；显式 `/skill <name>` 绕过该门（她 :296-301"显式加载就是显式同意"）。
  本机探测：`/.dockerenv`、`/proc/1/cgroup`、`/package/admin/s6`、`/proc/1/cmdline`；
  量测口子 `-Dzbot.skills.environments=`（真进程 E2E 用它）。
- `prerequisites.env_vars` / `.commands` ⇒ <b>不丢技能</b>：缺 env_var 照样供货，
  但 `setupNote` 写"继续加载、功能降级"（她 `skill_commands.py:258-262` 的
  `Required environment setup was skipped…`）；缺 commands 只算建议性提示。
  `BotAgent.skillContextBlock()` 会把这条降级说明一并贴进 prompt 块。
- `metadata.hermes.tags` / `.related_skills` / `.slash` ⇒ 摊平成点号键（`hermes.tags`），
  技能注入消息里会带 `[相关技能: …]`；历史的 `meta.<key>` 约定原样保留（老单测没改就过）。

判词：四个字段都不是"解析出来打印一下"，而是各自改变了<b>命令表 / prompt / 注入消息</b>三条出口。

---

## §2 技能 → 斜杠命令

STATUS: 已实现 + 单测已跑绿（真进程读数在 §7）

复算命令：
```
mvn -o test -Dtest='SkillCommandsTest,SlashRegistrySkillCommandTest,SlashRegistryTest' -DfailIfNoSpecifiedTests=false
git grep -n 'registerSkillCommands\|defaultSkillsForCommands' -- 'z-bot-core/src/main'
```

实测：
```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.skill.SkillCommandsTest
[INFO] Tests run:  9, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.slash.SlashRegistrySkillCommandTest
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.slash.SlashRegistryTest
```

兑现的语义（`SkillCommands` + `SlashRegistry.registerSkillCommands`）：
- slug 化：小写 → 空格/下划线转 `-` → 剥非法字符（`+ / . : , ; ( ) [ ] * # @ & % $ ! ? = ~ \` ' " < > | { }`）
  → 并横线 → 去首尾横线；空 slug 跳过并记账。`git_helper`/`git-helper`/`Git Helper` 归一到同一个 `git-helper`。
- <b>撞核心命令名 ⇒ 跳过并记账</b>：判据用 `registry.find("/"+slug) != null`，
  也就是<b>拿这张表本身当保留名清单</b>（她 `resolve_command()` 同款），不另立第二份名单。
  账本：`skills -> /skills: 与核心命令同名，跳过自动注册；用 /skill skills 加载`。
- <b>同名撞车保第一个</b>：`git_helper`（先到）留，`git-helper`/`Git Helper` 跳，
  理由里点名保的是谁（她 :399 的 "keeping the first and skipping this one"）。
- <b>最多叠 5 个</b>：`MAX_STACKED_SKILLS = 5`，`/a /b /c /d /e 办事` 装前 5 个，
  第 6 个令牌留在指令里；撞到重复令牌或非技能令牌即停（她 `:585-605` 逐条对齐）。
- 注册路径只<b>增</b>不改：`withBuiltinCommands()` 末尾追加技能命令；核心 20 条（19 条旧的 + 新增 `/skill`）
  由 `coreCommandNames()` 单测钉死；`all().size() == coreCommandNames().size() + 技能数` 断言单源。
- 终端真执行：`SlashCommand.execute(agent,args)` ⇒ `agent.invokeSkills(...)` ⇒ `agent.chat(注入消息)`，
  读的是<b>当前</b>这张表（`resolveSkillCommandKey`），不再扫盘。
- `/skills` 列表口径：每条技能后面直接标 `-> /命令`，被门藏掉的标 `-> 不进命令表: <理由>`，
  末尾附"命令表账本"。`/skill <name> [指令]` 是显式加载入口（绕过相关性门并说明为什么它没进表）。

判词：`slash` 从零消费者变成有注册、有执行、有账本的一条路。

---

## §3 sync + origin_hash

STATUS: 已实现 + 单测已跑绿（真进程 mtime/md5 读数在 §7）

复算命令：
```
mvn -o test -Dtest='SkillSyncTest' -DfailIfNoSpecifiedTests=false
git grep -n 'origin_hash' -- 'z-bot-core/src/main' | head
```

实测：
```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.skill.SkillSyncTest
```
`git grep -n origin_hash -- '*/main/*'` 在 §0 是【零命中】，现在有命中（清单键名 + 报告字段 + `/skills check` 输出）。

落点：`<skillsRoot>/.bundled_manifest`，每行 `技能名:origin_hash`（v1 无 hash 自动补基线）。
`dirHash` = 排序后的"相对路径 + 文件字节" MD5，与她 `_dir_hash` 同构。四条规则都钉了单测：
新增记 hash、<b>用户改过 ⇒ 不覆盖且基线不漂</b>、<b>用户删过 ⇒ 不复活且条目保留</b>（连跑三次验）、
未改动且上游变了 ⇒ 更新并推进 hash；上游删掉 ⇒ 清条目；
本地同名但内容不同 ⇒ 保留本地且<b>不</b>把 bundled hash 记进清单（她 :595 的"毒化更新判定"坑）。
入口：`/skills sync [源目录]`（源 = `skills.bundled.dir` > `<configDir>/skills-bundled`）、
`/skills install <目录>`、`/skills check <name>`（回显 origin_hash 与"本地已改动，sync 不会覆盖它"）。

---

## §4 安装期安全扫描（guard 规则子集）

STATUS: 已实现 28 条 + 单测 30 跑绿（真装真拦的实据在 §7）

复算命令：
```
mvn -o test -DsetClass -Dtest='SkillGuardTest' -DfailIfNoSpecifiedTests=false
```

实测：
```
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.skill.SkillGuardTest
```

### 做了（28 条，pattern_id 与 hermes 逐字同名；`implementedRules()` 有等值单测钉住）

| pattern_id | severity | category | 具名单测 |
|---|---|---|---|
| env_exfil_curl / env_exfil_wget | critical | exfiltration | envExfilCurlRule / envExfilWgetRule |
| ssh_dir_access | high | exfiltration | sshDirAccessRule |
| zbot_config_secret_read | critical | exfiltration | zbotConfigSecretReadRule（z-bot 自己的对位规则，替她的 hermes_env_access） |
| dump_all_env | high | exfiltration | dumpAllEnvRule |
| hardcoded_secret / embedded_private_key | critical | credential_exposure | hardcodedSecretRule / embeddedPrivateKeyRule |
| prompt_injection_ignore / sys_prompt_override / deception_hide / translate_execute | critical | injection | 各自同名 |
| leak_system_prompt | high | injection | leakSystemPromptRule |
| destructive_root_rm / destructive_home_rm / truncate_system | critical | destructive | destructiveRmRules / truncateSystemRule |
| reverse_shell | critical | network | reverseShellRule |
| tunnel_service | high | network | tunnelServiceRule |
| curl_pipe_shell | critical | supply_chain | curlPipeShellRule |
| echo_pipe_exec | critical | obfuscation | echoPipeExecRule |
| base64_decode_pipe / eval_string | high | obfuscation | base64DecodePipeRule / evalStringRule |
| path_traversal_deep | high | traversal | pathTraversalDeepRule |
| persistence_cron / shell_rc_mod | medium | persistence | persistenceRules |
| sudo_usage | high | privilege_escalation | sudoUsageRule |
| crypto_mining | critical | mining | cryptoMiningRule |
| invisible_unicode | high | injection | invisibleUnicodeIsDetectedAtDirectoryLevel |
| structural_limits | medium | structural | binaryAndHugeFilesAreNotScannedButStructuralLimitFires |

每条都是"正向必命中 + 反向不误伤"两口气；判定与策略：critical⇒dangerous、high/medium⇒caution；
`bundled` 放行 caution 拦 dangerous，`community` caution 就拦（她 `INSTALL_POLICY` 的社区列）。
被拦下的技能<b>一个字节都不落盘</b>（`SkillSyncTest.poisonedSkillIsNeverWrittenToDisk`、
`installRecordsHashAndBlocksDangerous`：暂存目录 `.staging-*` 也要清干净）。

### 不做（一律进 §10，不默默省略）

她的 category 里 exfiltration/injection/destructive/persistence/network/obfuscation/
traversal/mining/supply_chain/privilege_escalation/credential_exposure 都有代表；
<b>整类没碰</b>的是：agent_config_mod/hermes_config_mod 这一支"改她自己配置"的对位规则里，
凡是要读 `~/.zbot` 真配置的都算越界（红线 1）；另外 shebang 白名单、`.skillignore` 语义、
`TRUSTED_REPOS` 联网查仓、`scan_provenance` 台账、`--json` 报告体、终端配色渲染都本期不做，理由见 §10。
