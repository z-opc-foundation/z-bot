# P11b 验收证据 — 红线 1（代码/数据分离）真落地

日期 2026-09-25 · 基线 `e92cccc` · 本期改动：10 个主源文件 + 新增 `ProfileIsolationTest`(8 条) + 本目录量具

## 0. 一句话结论

`ZBOT_HOME` 此前**只出现在一句报错文案里，从未被读过**；写死 `~/.zbot` 的地方实测是 **10 处**（不是记账里写的 4 处）。
本期把三段各自抄了一遍的优先级链收成 `BotConfig` 里的单一解析点，四道杠全绿，全量 **399**（原 391）。

## 1. 开工前实测：漏得到处都是（旧记账"三处/四处"不成立）

```
git grep -n 'user\.home' e92cccc -- 'z-bot-core/src/main/*'          → 8 处 / 7 文件
     BotAgent×2, BotCenterClient, AgentOptions, SessionsCommand, BotConfig, SessionManager, Sandbox
git grep -n 'stty' e92cccc -- '.../bot/ui/*' | grep '~/'             → 2 处 shell 里的 ~/.zbot/.stty.bak
     RawTerminalReader.java:234（写）/:244（读）⇒ 两个 profile 的 REPL 互相踩对方的终端恢复
git grep -n 'ZBOT_HOME' e92cccc -- 'z-bot-core/src/main/*'           → 1 处，且是 PairCommand.java:33 的报错文案
     ⇒ 环境变量这一档从来没人读
```

修法：`BotConfig.defaultConfigDir()`（`-Dzbot.home` > `ZBOT_HOME` > `~/.zbot`）+
`BotConfig.resolveWorkspaceDir(configDir, cliOverride)`（`--sandbox` > `-Dzbot.sandbox` > `<profile>/workspace`），
`sessions/`、`state.db`、`skills/`、`instance.json`、stty 备份全部改由 profile 派生；
`BotCenterClient` 那条"猜 `~/.zbot`"的一参构造器**删掉**（红线 2：不留兜底抽象）。

## 2. 四道杠读数（每条都带复算命令）

**① 全量连续 3 跑绿**
```
rm -rf z-bot-core/target/surefire-reports && mvn -o test
run=1 rc=0 | Tests run: 399, Failures: 0, Errors: 0, Skipped: 0
run=2 rc=0 | Tests run: 399, Failures: 0, Errors: 0, Skipped: 0
run=3 rc=0 | Tests run: 399, Failures: 0, Errors: 0, Skipped: 0
```
另：本目录量具落盘前还有一轮 8× 连绿（`/tmp/p11bmain_full*.log`），合计同树 11 连绿。

**② 变异检验 13 支**（`python3 _doc/005_testing/acceptance/p11b/p11b_mutation.py`，逐条台账见 `LEDGER.tsv`，由脚本输出机械生成）
```
RED-OK 11 / PARTIAL 0 / GREEN-BUT-MUTATED 2 / BROKEN 0        final md5 ok: True
```
两条 `GREEN-BUT-MUTATED` 是**设计上进程内证不了**的，证据在③：
`M1`（把 `ZBOT_HOME` 读取删掉）—— 测试进程改不了自己的环境变量；
`M12`（stty 备份改回共享 `~/.zbot/.stty.bak`）—— 要真 pty。
两处点名未打满，如实记：`M2 2/3`、`M3 1/2` —— 差额是 `agentBuilderKeepsEveryArtifactInsideTheProfile`
与 `workspaceResolutionIsCliThenSysPropThenProfile`，这两条都**显式传值**，走不到缺省链那一档，
所以杀不死"缺省被写死"这一支；判定仍按"具名那条红了才算 RED-OK"，不因此改期望集。

**③ 真进程 E2E 23/23**（`mvn -o -pl z-bot-core package -DskipTests && python3 _doc/005_testing/acceptance/p11b/p11b_e2e.py`）
```
E1a–E1e  ZBOT_HOME 真进程下 status 四行全落临时 profile；输出里真实 home 的 .zbot 命中 0 处
E2a–E2b  --config-dir 生效；未设 ZBOT_HOME 时不误捡真实 home
E3a–E3c  --sandbox > -Dzbot.sandbox > <profile>/workspace 阶梯逐条打
E4a–E4d  新 profile 自建 state.db；sqlite3 真塞一行"红线1探针"只有 homeA 看得见；真实库 md5 未动
E5a–E5e  真 pty（TERM=dumb 逼出 RawTerminalReader）跑通 REPL；stty -g 前后同值；
         守望线程抓到本进程 tmpdir 里的 zbot-stty-* 备份、退出后 0 残留；真实 .stty.bak mtime 未动
E6       主源码 user.home 只剩 BotConfig 一处解析点（0 处外泄）
E7a–E7c  ~/.zbot 项数 8 → 8；config.properties 2dadaed0→2dadaed0；state.db 690ddbc0→690ddbc0
```
临时 profile 里的 key 一律 `stub-key-not-real`，真 minimax key 未进任何临时目录。

**④ 单测不碰真实 home**：见 E7a–E7c 与本轮收尾复测（`entries=8` + 两个 md5 同上）。

## 3. 本期顺带挖出的一条真缺陷（另片修）

杠①的第二跑曾红一次：`HttpChannelTest.toolsEndpointReflectsToolkit expected:<200> but was:<400>`。
追下来不是本期代码引入，而是 §6 记了很久的 `*.channel` 抖动的**同一真因**：
四个通道都绑**通配地址**，macOS 上能与邻居进程的 `127.0.0.1:P` 特定绑定共存，客户端被路由到邻居
（那个 400 来自 Qoder 的 WebSocket 端口：`curl -i http://127.0.0.1:56510/bot/tools` →
`400 Bad Request` + `Sec-Websocket-Version: 13`，而 `dispatch()` 对 `/bot/tools` 只有 200/500 两条出口）。
取证：同树 8 跑 0 红、`e92cccc` 基线 8 跑 0 红、通道四类单跑 18 跑 0 红 ⇒ 与本期改动无关，是概率性撞车。
修法与完整实测记在 roadmap §6 首条 + §5 **P11c**（顺带关掉 `z-bot serve` 缺省把无鉴权 `/bot/chat`
摆到局域网这个暴露面）。诊断面本期已加厚：`HttpChannelTest` 23 处状态断言现在打印 `url + code + ct + body`。

## 4. 留账（不藏）

- `~/.zbot/.stty.bak`（204 B）是**旧代码**留下的死文件，现在没人写也没人读；没删（不是我的文件），下次统一清理。
- 真实 home 无 profile 时仍落 `~/.zbot`——这是缺省档，不是泄漏；`-Dzbot.home`/`ZBOT_HOME` 任一都能挪走（E1/E2 已证）。
- `ProfileIsolationTest` 的 `statusCommandReportsProfilePathsNotRealHome` 用真 picocli 跑 `status`，
  并逐字节比对 `~/.zbot` 目录清单前后一致 —— 这条是④的常驻守卫。
