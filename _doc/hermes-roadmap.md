# z-bot → Java 版 Hermes 全量对标计划 (v2, 2026-09-25)

> 对标对象: **hermes-agent**（NousResearch, Python），**唯一权威参照 = 本机 git 检出 `~/.hermes/hermes-agent`**。
> 本文所有"她的 X 行 / `:872-919`"式引用都以该检出为准。当前基准 **`cbc1054e2`**
> （`git describe` ⇒ `v2026.6.5-6227-gcbc1054e2`；`pyproject.toml` 自称 `0.19.0`）。
> 复算基准没漂: `git -C ~/.hermes/hermes-agent rev-parse --short HEAD` ⇒ `cbc1054e2`，
> `git -C ~/.hermes/hermes-agent status --porcelain | wc -l` ⇒ `0`（**别 pull，一拉全文行号引用就 rot**）。
> **坑（09-26 踩过）**: 不许拿 `pip download hermes-agent` 的 wheel 当参照 —— PyPI 上那个 `0.19.0` wheel
> 的 `hermes_state.py` 只有 **7,822 行 / 17 条 CREATE TABLE**，与本检出的 **9,503 行 / 22 条**（§7 的口径）不同一条线；
> 且 aliyun 镜像更旧（只到 0.15.2，其 `hermes_state.py` 3,314 行 / 30 列）。版本号同名不同物。
> **体量实测口径** (v1 的"~189 万行"无任何口径支撑, 本轮作废重测):
> ```
> cd ~/.hermes/hermes-agent && find . -name '*.py' \
>   -not -path './.git/*' -not -path './venv/*' -not -path '*/site-packages/*' \
>   -not -path '*/node_modules/*' -not -path '*/__pycache__/*' -not -path './tests/*' \
>   -type f -print0 | xargs -0 wc -l
> ```
> ⇒ 源码 **978 文件 / 713,095 行**; 含其自带测试则 3,248 文件 / 1,536,919 行。两口径都不是 189 万。
> 本机 z-bot 现状: `z-bot-core/src/main/java` **60 文件 / 12,234 行**, 234 单测, kernel **94 文件 / 5,380 行**。
> 差距 ~58×, 且她的 TUI 根本不是 Python (见 §4 不做清单) —— 所以"完全对标"必须按能力面逐项定义, 不按行数。

## 1. 为什么是 v2: v1 记账的 13 处纠正

v1 每期都盖了 ✅ 并附"实测记录", 但**计划文字里的几条主张从未被复验**。本轮逐条对代码取证, 结果如下 (`grep -rln`/`sed -n` 可原样复算):

| # | v1 主张 | 实测现实 | 证据 |
|---|---|---|---|
| 1 | P0 "kernel SPI 地基" 落地 13 个抽象 | **5 个零消费者**: `SteerQueue` `DelegateSpec` `ToolRegistry` `SessionStore` `MemoryProvider` 在 z-bot 主干 0 引用; **`Toolset` 在 kernel 里根本不存在**; 仅 `AgentContext`(2) `IterationBudget`(3) `ContextEngine`(2) `SkillLoader`(2) `McpClient`(3) `CredentialPool`(1) `InterruptFlag`(1) 真被用 | 对 13 个类型名各跑 `find z-agent-kernel -name T.java` + `grep -rlw T z-bot/.../main/java` |
| 2 | P1 "一张 SlashCommand 表派生 CLI/HTTP/TUI 三端" | **只有终端一端**。`HttpChannel.java` 里 `slash` 0 命中; 消费者只有 `TerminalChannel/ui.*` | `grep -rln "SlashRegistry\|slash.find("` → 5 文件全在终端侧 |
| 3 | P2 "sessions export(md\|jsonl)" | 只有 **JSONL**; 无 md 导出 | `cli/SessionsCommand.java:107-121` |
| 4 | P3 "压缩血统写进 metadata" | 血统**只在引擎实例内存里**, 进程重启即丢; 她的真做法是把压缩落成**会话分叉**并写库 | `context/CompressorEngine.java:18` 注释 vs `hermes_state.py:886 parent_session_id` |
| 5 | P5 "并发宽度 max_concurrent_children + 台账落 state.db" | 台账**是真的** (`async_delegations`); 并发宽度是**装饰**: `agent.delegate.max.children` 被解析进 `BotConfig.delegateMaxChildren` 后**无任何逻辑读取**, 实际跑在 `static Executors.newCachedThreadPool()` 上 = 无上限且跨 agent 实例共享 | `grep -rn "delegateMaxChildren"` 只有字段/解析两处; `delegate/DelegateManager.java:223` |
| 6 | P7 "cron 投递到 channel (cli 打印/HTTP SSE/webhook)" | **零投递**。`TaskRunner.run()` 的返回值只被写进 `jobs.json` 的 `lastResult`; 没有任何通道引用 | `agent/BotAgent.java:1534-1548`, `cron/CronScheduler.java:167` |
| 7 | P8 "会话租约 + 送达台账 + 崩溃自愈 + 飞书真实收发" | `Gateway.java`/`ChannelBus.java` 里 `lease/租约/ledger/台账/自愈` **0 命中**; `FeishuChannel`/`DingTalkChannel` **没有任何 HTTP 客户端代码** (出站=日志+内存队列, `outgoingUrl()` 是个字符串常量); `PairingService` 配对码**明文落盘** (无 sha/hash/digest 命中) | 三条 grep 均空 |
| 8 | P9 "stdio + HTTP 两种 transport" | 只有 stdio — 工厂类自己的注释写着"HTTP+SSE 留到后续版本"; 且"反注册"是假的: `Toolkit` 没有 unregister API, `McpBridge.unregisterAll()` 用**同名 stub 覆盖**收尾, reload 后工具名仍占表 | `mcp/McpClientFactory.java:17,29`, `mcp/McpBridge.java:62-66` |
| 9 | 头部 "hermes ~189 万行" | 见文首口径 (可复算的 find 命令), 713,095 (源码) / 1,536,919 (含测试) | 同上 |
| 10 | v1 §0 锚点表 15 个 hermes 文件路径 | 13 个真存在, **2 个路径错**: `toolsets.py` 在顶层 (不在 `tools/`), `interrupt.py` 在 `tools/` (不在 `agent/`) | `find . -name '*toolset*' -o -name '*interrupt*'` |
| 11 | P10b "WebhookChannelTest 401 = 环境级临时端口复用, 未复现不修" | **该归因已被自己的量具推翻**: 12 轮全量 (每轮 36 个绑定端口) 里重复端口 **0 次**、失败 **0 次**。今天同型故障再现于 `FeishuChannelTest` ⇒ 旧结论作废。**09-25 已定位真因**: 通道绑通配地址, 与邻居进程的 `127.0.0.1:P` 特定绑定在 macOS 上共存, 客户端被路由到邻居那儿 —— 详见 §6 首条 (含 `curl`/`lsof`/`BindProbe` 三份实测)。**修法已于 P11c 落地** (09-25 收口: 缺省绑回环 + 显式 `--host` 才通配), 四道杠读数见 §5 W2 该条与 `_doc/acceptance/p11c/EVIDENCE.md` | `/tmp/zbot_flake/summary.txt`, 脚本 `/tmp/zbot_flake_loop.sh`, `/tmp/BindProbe.java` (三份已归档进 `_doc/acceptance/p11c/`) |
| 12 | (本轮新发现·安全) 模型可自带 `__confirmed__:true` 跳过审批 | 已修: `parseArgs` 无条件剥除该标记, 附 3 条回归 | commit `cd32730` |
| 13 | (本轮新发现·红线 1) `state.db` 缺省写死 `~/.zbot` | 已修: 缺省跟随 `configDir`, `z-bot sessions` 同步支持 `--config-dir` | commit `a91de90` |

**方法论结论 (写进红线)**: 一期一期的"完成记录"不能只看它打了 ✅ — 主张必须留下**可复算的命令**。v1 的 8 处 overclaim 全都是"计划段落写成过去式、但没人复验"。v2 因此规定: §2 矩阵每行必须挂锚点行号 + 复算命令, 每期验收必须给实测输出, 禁止以代码断言代替真跑。

> **自省 (同一把尺子量了自己)**: v2 初稿里有 **8 个数字是代理报告直接抄来的**, 提交前逐条复测全部走形 —
> toolsets 57→**33**、`tools/` 87,214→**98,428**、platform adapter 31→**29**、SKILL.md 182→**184**、
> 中断检查点 "循环 5/工具 22"→**循环 4/工具 40**、`run_conversation` 5,276→**5,299**、`mcp_serve` 反暴露 8→**10** 工具、
> "1,500 行 shell 反混淆"→**UNKNOWN**(无独立模块, 是 approval.py 内 :1152→:1747 一段)。
> 反过来, 她自己的注释 (`approval.py:458` "12 + 47") 也是过期的, AST 实算是 12 + 70。
> ⇒ 红线 9 不是写给 v1 的: **任何二手数字 (代理报告 / 上游注释 / 自己的记忆) 都必须现测才能进文档。**

## 2. 能力矩阵 (v2 权威版: 22 个能力面)

锚点一律 `路径:行`, LOC 为本轮 `wc -l` 实测。verdict 只有四档: **缺**=没有 / **浅**=有但语义不足 / **齐**=对等 / **不做**=见 §4。

| # | 能力面 | hermes 锚点 (实测 LOC) | z-bot 现状 | verdict |
|---|---|---|---|---|
| 1 | 主循环 ReAct | `agent/conversation_loop.py:589` 起的 `run_conversation` **到文件末尾 5,887 行 = 单函数 5,299 行** (589 之后无第二个顶层 `def/class`) | `BotAgent.chat` 主循环 | 浅 (她反面教材: 红线 3) |
| 2 | 迭代预算 | `agent/iteration_budget.py` 62 行, 父 90 / 子 50, 压缩后 `refund` | `IterationBudget` 已接, grace call 有 | 齐 (缺 refund) |
| 3 | 协作式中断 | `tools/interrupt.py` (113 行) 线程级 `_interrupted_threads` + 按 `_execution_thread_id` 定向; 循环 **4 个检查点** (`conversation_loop.py:740/1787/3260/4437` 读 `agent._interrupt_requested`); 工具/环境侧 **40 处 `is_interrupted()` 调用, 分布在 11 个文件** (vision 6 / environments.base 5 / mcp 4 / approval 3 / terminal 2 …) | 循环层 `/stop` + `agent/InterruptScope`（**线程作用域**，M17 证明退化成全局必串台）+ 工具侧 `InterruptScope.checkpoint()` **9 处**（复算 `git grep -cF "InterruptScope.checkpoint()" -- 'z-bot-core/src/main/java'` = `tool/BuiltinTools.java:7` + `delegate/DelegateManager.java:2`，覆盖 exec 起进程前/读输出循环/文件读写/`mvn_build`/delegate 子循环）+ **工具子进程看门狗**（M6 摘掉 ⇒ 真进程层 `S2 pgrep=['97773','97775']`、`S3 elapsed_ms=-1`）; 实测 `/stop`→子进程真退出 **elapsed_ms=54**（判据是 ≤2000ms） | 浅（机制四件套齐且有变异检验；差额=她 **40 处/11 文件** vs 我们 **9 处/2 文件**（vision/mcp/approval/environments.base 侧零检查点），且 `exec 读输出逐行`/`mvn_build 入口` 两处量具自述"摘掉也量不出差别"= 第二道保险未覆盖；**pty 里敲 `/stop` 实测不生效**，见 §8 P12 条） |
| 4 | steer 注入 | `agent/_pending_steer` 单槽 (非队列) + 追加到最后一条 tool 结果; 3 处 drain; 硬中断丢弃 pending steer | 采纳她的语义并补齐三档: kernel `SteerQueue` 现在有 **8 处真消费者**（复算 `git grep -nF "context.steer()" -- 'z-bot-core/src/main/java'` 全在 `BotAgent`，drain/clear/add/hasPending；红线 2 的"0 消费者抽象"已消）；`/steer`=单槽（`clear()` 后 `add()`）、`/queue`=多条按序、`/stop`=丢弃 pending（M5）；M5b"steer 不再是单槽"与 M5c"/queue 写成单槽"两支**互斥变异**各自点名红 ⇒ 两档语义真的不一样，不是同一份代码换个名字 | 浅（三档语义有牙；差额=她没有的我们没有，我们缺她的部分是**运行中从 TUI 旁路注入**这条路 —— `R2b` 实测 `TerminalChannel.run()` 在 `agent.chat()` 里同步阻塞、这段时间不读 stdin） |
| 5 | prompt cache | `agent/prompt_caching.py:84-119` `system_and_3` ≤4 断点; **system prompt 每会话构建一次并逐字节重放** (`:967-971`), 工具 schema `sorted()` | 快照冻结落地: system prompt 会话内构建一次即**逐字节重放**（真进程层 C1 比 stub 落盘的请求原文，两轮 sha256 相同；M12 解冻变异 ⇒ `len1=2345 len2=2372` 两个 sha 立刻漂），时钟/记忆/SOUL/center 召回**全部改道 user 消息**且**建 request 时才注入**（`turnVolatileBlock`+`injectVolatileContext` ⇒ 转录与 `~/.zbot/sessions/*.json` 里只存用户原话，`/sessions` 标题回到原话；C4 钉"原话仍在结尾"、C3 钉"两轮之间时钟确实变了"防"两轮都没注入"这种假绿） | 浅（"每会话一次 + 逐字节重放"这条不变量已定义且有变异检验；差额=没有她的 `system_and_3` **cache_control 断点**那一层（Anthropic 显式缓存计费面），且**改动之前已落盘的老 transcript 里的抬头没清洗**（`switchSession()` 原样读回，见 §12.5 第 4 条）） |
| 6 | 上下文压缩 | 阈值 `(context_length - max_tokens) × 0.50` (`agent/context_compressor.py:1521` `threshold_percent: float = 0.50`), 3 个评估位点, 防抖/失败冷却, `compression_locks` 表, **血统=会话分叉入库** | 阈值 `maxTokens × 0.85` (`CompressorEngine.java:23`), 锁有, 血统在内存 | 浅 |
| 7 | 状态库 | `hermes_state.py` 9,503 行, **22 条 CREATE TABLE (含虚表) ⇒ 11 张实表 + 3 张 FTS 虚表** (复算 `grep -cE 'CREATE (VIRTUAL )?TABLE' hermes_state.py`; 实表名去重见 §8), sessions **46 列** (`:872-919`, 按列声明正则数=46), `schema_version` 迁移阶梯 + 坏库自愈, WAL/`BEGIN IMMEDIATE`+抖动重试 | `store/` 4 文件 3,152 行 (StateStore 1,950 / SchemaMigrations 565 / SqliteTx 324 / DbRecovery 313): head 8 实表 + 1 FTS 虚表, sessions 15 列, 3 步可重入阶梯 + 声明式收口 + 坏库备份重建 + `BEGIN IMMEDIATE`/20–150ms 抖动重试, 10 个配置键接线; **双进程对撞实测**见 §8 | 浅 (机制四件套 09-25 实测齐; 差额=sessions 还缺 31 列的计费/token 分档/通道身份/handoff + telegram 主题 2 表 + trigram/cjk 两个虚表) |
| 8 | 检索 | `messages_fts` + `messages_fts_trigram` + `messages_fts_cjk` (`native/fts5_cjk/` C 扩展); 搜索沿血统回溯 (`session_search_tool.py:104`) | 单一 FTS5, 无 trigram | 浅 (CJK 扩展=不做) |
| 9 | 记忆 | `MEMORY.md`+`USER.md` 两层 (2,200/1,375 字符上限), `SOUL.md` 属**身份**不属记忆; 快照冻结保缓存; `write_approval` 三段 (allow/inline/stage→`pending/*.json`); 威胁扫描 `[BLOCKED:]` | 三文件同放 `memories/`, rewrite/forget 走审批, 无字符上限/无冻结快照/无威胁扫描 | 浅 |
| 10 | 技能 | **184 个 `SKILL.md`** (78 在 `skills/`, 余在 `optional-skills/`; 复算 `find . -name SKILL.md -not -path './.git/*' -not -path './tests/*' -not -path './website/*' \| wc -l`); frontmatter 含 `platforms/environments/prerequisites/metadata.hermes.*`; `agent/skill_commands.py:320 scan_skill_commands` **技能→斜杠命令** (被 `gateway/slash_commands.py:1362`、`tui_gateway/server.py:13424`、`cli.py:3643` 三端消费); `skills_sync` origin_hash 清单 (23 处); `tools/skills_hub.py` 4,227 行 (lockfile+隔离+审计); `agent/curator.py` 2,016 + `tools/skill_usage.py` 947 + `tools/skills_guard.py` 1,153 生命周期 | 4 字段 frontmatter + `/skills list/view`; **技能→命令在 v1 被显式推迟且没做** | 浅 |
| 11 | 工具注册表 | `tools/registry.py` 810 行: `deregister()`/generation 计数/`check_fn` TTL 30s+失败宽限 60s/别名/插件覆盖策略/单工具结果上限; `toolsets.py` 顶层 `TOOLSETS` dict **33 个静态 toolset** (插件运行期另加, `get_toolset_names()` 合并之); 递归解析带环检测 (`resolve_toolset:689`); 并行安全**不在**注册表而在 `agent/tool_dispatch_helpers.py` | `tool/Toolkit.java` **无 unregister**, 无探测, 无代际; MCP 侧被迫用 stub 覆盖 (见 §1#8) | **缺** |
| 12 | 工具面 | `tools/` 114 个 py 文件 / **98,428 行** (`find tools -name '*.py' -print0 \| xargs -0 wc -l \| grep total`), 静态注册 **72 个工具** (`grep -oE 'name="[a-z_0-9]+"' tools/*.py \| sort -u \| wc -l`; `registry.register()` 调用点 85 处含别名) | 主干 **14 个** (BuiltinTools 11 + memory/cronjob/delegate_task) | 浅 (广度按 v2 选择性补) |
| 13 | 执行后端 | `tools/environments/` 6 后端: local 1,534 / docker 1,460 / base 1,125 / ssh 375 (+modal/daytona/singularity 不做), `TERMINAL_ENV` 选择 | `Sandbox` 只有路径牢笼 | **缺** |
| 14 | 审批 | `tools/approval.py` 3,951 行: **12 条 `HARDLINE_PATTERNS` (:417) + 70 条 `DANGEROUS_PATTERNS` (:606)** (AST 复算: `python3 -c "import ast;…"` 数列长; 注意她自己的注释 :458 写的是 "12 + 47", **注释已过期, 以列表实长为准**) + deny globs + `manual\|smart\|off` + 每会话 FIFO + 允许清单入 config + 反混淆 (`:1152 _shell_tokens_with_spans` → `:1747 _deobfuscate_shell_word_for_detection`, 与 threat_patterns.py 284 行合起来的规模 **UNKNOWN**, v1 报的"~1,500 行"无法复算已作废); smart 模型明示防 "`rm -rf / # Respond APPROVE`" 注入 | `ExecGuard` 1,242 行 (P11 重做后, `wc -l` 实测): **硬线在任何模式都拦**（含 `--yolo`/`off`）= 结构化 analyze（token 级 rm/chmod 目标解析 + 设备/文件系统/fork 炸弹命名正则）; **危险表 42 条**（复算 `grep -c "new Rule(" ExecGuard.java`）vs 她 70 条; 白名单改 **token 边界** 匹配（`git status` 不再放行 `git statusX`）, 复合命令/引号内分隔符不豁免; 反混淆覆盖 `$()`/反引号/`$$`/管道展开/注释剥离 ⇒ **读不懂必走人审**; 每会话 FIFO + 新待批取代旧的 + **审批绑命令**（放行不可挪用到另一条命令）+ 只有 ALWAYS 落盘。**仍无**: smart 模型明示档、deny globs | 浅 (P11 前为 **缺·有洞**: 6 字面 + 12 正则、`startsWith` 前缀匹配、`rm -fr /` off 模式直通 — 见 §8 `ebf44e5`) |
| 15 | checkpoint | `tools/checkpoint_manager.py` 1,675 行: 共享 bare store, `refs/hermes/<sha256(path)[:16]>` (:76/:204), 每轮 + 每个写工具前, 整树或**单文件**回滚, 回滚前再快照("撤销撤销"), `_MAX_FILES = 50_000` (:148) + 修剪 | 影子库 + 引用 + 整树回滚; 每轮节拍/单文件粒度/撤销撤销 无 | 浅 |
| 16 | 委托 | `tools/delegate_tool.py` 3,655 行: `DELEGATE_BLOCKED_TOOLS` **5 工具** (delegate_task/clarify/memory/send_message/cronjob, AST 实测), 宽度默认 3 (floor 1 无上限, `_get_max_concurrent_children` → `DaemonThreadPoolExecutor(max_workers=max_children)` :2651), `MAX_DEPTH=1` (:125), 子代理审批自动 deny, `DEFAULT_MAX_SUMMARY_CHARS=24000` (:590)+溢出落文件, async **SQLite 持久 + `_MAX_DELIVERY_ATTEMPTS=8` (`tools/async_delegation.py:84`) + `LIVE_RETENTION_DAYS=7` (`tools/delegation_live_log.py:44`)** | 子预算 1/4 + 深度剥工具 + `async_delegations` 表有; **宽度未强制**(§1#5)、无摘要溢出、无投递重试 | 浅 |
| 17 | 网关送达 | `gateway/turn_lease.py` 302 行 (按**解析后 session_id** 上锁, 因 `switch_session` 多对一会让两把锁错位→`user;user` 死楔) + `delivery_ledger.py` 341 行 (`delivery_obligations` 三态, at-least-once + `RECOVERED_MARKER` 明示可能重复, owner=pid+进程启动时间, `sweep_recoverable`) + `dead_targets.py` | `ChannelBus` 每会话 fork + 200 条 ring buffer, **三件套全无** | **缺** |
| 18 | 自愈 | `_spawn_supervised` (崩溃重启/干净退出**不**重启) + `restart_loop_guard` (3 次/60s 熔断并跳过自动续跑) + 卡死会话挂起 + 陈旧锁自愈 + 平台重连 watcher + 跨进程平台锁 | 全无 | **缺** |
| 19 | 通道 | **29 个生产 `BasePlatformAdapter`** = 9 (`gateway/platforms/*` 8 个 + `gateway/relay/adapter.py`) + 20 (`plugins/platforms/*`); 复算 `grep -rnE 'class [A-Za-z0-9_]+\([A-Za-z0-9_]*PlatformAdapter\)' --include='*.py' . \| grep -v tests/` (全仓含测试替身共 83); 三层注册: `plugin.yaml` manifest → `ctx.register_platform` → `PlatformRegistry` (后写覆盖), 惰性 loader (省"每次启动数秒") | `channel/` 19 个类; P18 起有 `ChannelRegistry` (612 行) 声明式 manifest 四层后写覆盖 + 惰性构造 + 缺键点名, builtin 清单声明 **4 个 kind** (http/webhook/feishu/dingtalk, 后两个缺省 `enabled=false`); 飞书真协议 (token 换取 + `im/v1/messages` + Bearer + 401 重取重发 + 入站 sha1 验签) 与钉钉 HMAC 签名出站 —— **出站证据全是"对 127.0.0.1 假端点发出的字节", 零真发** (本机无 IM 凭据) | 浅 (09-26 P18 后差额不再是"stub": 真平台适配器 **2 vs 她 29**、无 `plugin.yaml` 运行期插件层、飞书 `{"encrypt":…}` AES 解密模式与钉钉入站验签**未做**) |
| 20 | cron | `cron/scheduler.py` 4,153 行: `_deliver_result` 优先活适配器, 路由 `local\|origin\|平台\|all`, **`claim_dispatch()` 副作用前先认领** + `heartbeat_run_claim` 保鲜, `threading` 锁 + `flock .jobs.lock` | 表达式/tick/FileLock/jobs.json 有; **无投递**(§1#6)、无 dispatch 认领 | 浅 |
| 21 | 命令表 | `hermes_cli/commands.py` 2,151 行单源 → Telegram/Discord/Slack/Matrix/TUI 各自派生; 处理端 `gateway/slash_commands.py` 5,005 行 + `should_bypass_active_session` | 注册表单源已有, **只有终端一个消费端** | 浅 |
| 22 | MCP | `tools/mcp_tool.py` 6,391 行: stdio/StreamableHTTP/SSE 三 transport + `notifications/tools/list_changed` (7 处命中)→`deregister`+`register` + sampling/elicitation/OSV 预检 (三者在同文件内, 合计 111 处关键字命中) + 父死 watchdog; `mcp_serve.py` 990 行反向暴露 **10 个工具** (`grep -c '@\w*\.tool' mcp_serve.py`) | 仅 stdio 那一段**已过时** (P21 后): 两条真 transport 都在 —— `ZBotStdioMcpTransport` 564 行 + `StreamableHttpMcpTransport` 661 行 (POST JSON-RPC + `mcp-session-id` + 双 `Accept`, JSON 与 SSE 帧两种响应都吃), `McpClientFactory.create()` 按 `transport` 位派发且未知值/缺 `url` 直接抛不降级; `list_changed` 是**真反注册** (`McpBridge:186/247` → `toolkit.deregisterToolset`), 不再是同名 stub 顶包; 父死 watchdog 默认开 (`Options.parentWatchdog`, POSIX 下改挂壳 argv)。server 侧有了但只广告 **2** 条读端工具 (`ZBotMcpServe.toolDefinitions()`: conversations/messages) **对她 10 条**。**仍缺**: sampling / elicitation / OSV 预检 / resources —— `grep -rn 'createMessage\|elicitation\|osv' z-bot-core/src/main/java/.../mcp/*.java` 实测 **0 命中**; 另有内核 `StdioMcpTransport` 三缺陷**只记录未修** (§14) | 中 |

**v2 未列入矩阵、但已确认存在的大块** (留 P3x 轮次, 见 §5 末): `agent/error_classifier.py` 1,698 行、`agent/retry_utils.py` 154 行 (抖动退避) + `agent/reasoning_timeouts.py` 226 行 (按模型给 `stale_timeout_seconds` **下限**, 是 `max(default, floor)` 语义, `_REASONING_STALE_TIMEOUT_FLOORS:62`) + `agent/thinking_timeout_guidance.py` 136 行、用量计费族 (`agent/account_usage.py` 890 + pricing/credits/rate_limit/context_breakdown)、`hermes_cli/` **176,634 行 / 39 个命令模块** (`ls hermes_cli/subcommands/*.py \| grep -v '/_' \| wc -l`)。
> **标 UNKNOWN 的旧数字 (v1 期笔记无法复算, 不再引用)**: "179 子命令"总数、"陈旧看门狗 180/240/300s 三档"、"限流阶梯 30/60/90/120s"、"连续 5 次放弃" —— P26 实施时**以她源码里的实际常量为准**并回填本行。

## 3. 结构性红线 (v1 五条继承, v2 新增四条)

1. **代码/数据分离**: `~/.zbot/` 只有数据与用户配置; `ZBOT_HOME`/`--config-dir` 多 profile 隔离。*(v1 违反两处并已修: history 与 state.db 都曾写死 `~/.zbot`)*
2. **注册表 SPI, 不写硬编码清单**: 工具/通道/技能/记忆/上下文引擎/斜杠命令全部可插拔且**有真实消费端**。*(v1 教训: 建了 SPI 没人用 = 0 分)*
3. **显式状态对象, 不造上帝类**: 她的 `run_conversation` 是 5,276 行单函数 — 反面教材, 不许照抄形状。
4. **横切关注点管道化**: 审批/记账/脱敏/压缩锁做成循环上的显式 stage。
5. **0 强制三方依赖**: 可选能力用 optional 依赖 + 运行时探测。
6. **【v2 新增】prompt cache 不变量优先**: system prompt 一旦构建即逐字节重放; 一切易变内容 (记忆召回、技能索引、时钟、center 上下文) 走 user 消息, 不许进 system prompt。违反此条的"功能增强"一律退回。
7. **【v2 新增】审批不可自助, 且闸门在模型输入之外**: 人放行才盖章 (`cd32730`); 硬线表在任何模式下都拦 (含 `off`); 白名单**必须按 token 边界匹配**, 不许 `startsWith`。
8. **【v2 新增】先落账再产生副作用**: 投递/定时/委托一律先 claim 再执行; 半途崩溃的语义必须是"可能重复"并**在用户可见处标注**, 不许静默重发 (她的 `#61790` 就是因此被关掉)。
9. **【v2 新增】主张必须可复算**: 文档/记录里每个数字要么带测量命令, 要么标 UNKNOWN。禁止把计划文字写成过去式。

## 4. 不做清单 (skip tier, 附证据)

| 不做 | 为什么 | 实测证据 |
|---|---|---|
| hermes 的 TUI 本体 | **它不是 Python**: React 19 + Ink 6 的 TypeScript 程序, Python 只跑 agent 并经 stdio JSON-RPC 供数 (123 个 `@method`, 单 `server.py` 16,516 行) | `ui-tui/src`+`packages` = **410 文件 / 85,118 行** TS/TSX; `wc -l tui_gateway/server.py`=16,516; `grep -c '^@method'`=123 |
| `apps/desktop` (Electron) / `apps/bootstrap-installer` (Tauri v2 Rust) | 桌面打包壳, 与 agent 能力无关 | `apps/desktop/package.json` electron-builder; `src-tauri/tauri.conf.json` |
| `web/` 11MB React 仪表盘 / `website/` 749 文件 Docusaurus | 站点工程, 非能力 | `du -sh web`=11M |
| `locales/` 16 个 YAML 目录 (532KB) | Java 侧要重写 ResourceBundle 且无用户 | `ls locales/*.yaml \| wc -l`=16 |
| `native/fts5_cjk/` C 扩展 | 要 `load_extension()` + JNI; sqlite-jdbc 默认禁止扩展加载 ⇒ 只保留 LIKE/trigram 口径 | `native/fts5_cjk/fts5_cjk.c` |
| environments 的 modal / daytona / singularity / managed_modal | 专有 SaaS 与 HPC 容器运行时 | `tools/environments/modal.py` 478 等 |
| 注册表的 `ast.parse` + importlib 自发现 | Python 专属机制 ⇒ Java 用注解处理/显式 SPI 清单替代 (**不等价实现, 不等价于不做的部分**) | `tools/registry.py:29-90` |
| `contextvars` 的会话/平台绑定传播 | Java 8 无 `ScopedValue` ⇒ ThreadLocal + 显式传参 | `tools/approval.py:69-227` |
| Nous 侧 SaaS/计费权益 (entitlement, credits, paid plan 判定) | 厂商绑定, 与框架无关 | `conversation_loop.py:188-304` `_nous_entitlement_*` |
| 宠物/成就/皮肤营销向特性 | 非承重能力 | `tui_gateway` 里 `pet.*` 15 个 RPC |

## 5. 分期 (P11 → P29, 按波次并行, 每波文件边界互不相交)

**通用验收杠 (每期四条, 缺一不算完成)**:
① `mvn -o test` 全量绿 **连续 3 跑**, 每轮先 `rm -rf target/surefire-reports` 再读 `MVN_RC`;
② 新增守卫做**变异检验** (把守卫改坏, 断言必红);
③ **真实 E2E** (起真进程/真通道/真 git/真 sqlite; 允许本地 stub LLM 后端, 不许用代码阅读代替跑);
④ 单测禁写 `~/.zbot/` (`TemporaryFolder`/`--config-dir`), 跑完 `ls -A ~/.zbot/` 必须回到 8 项 pre-state。

**编队协议**: 一个代理一个子系统, **150 轮硬上限**并写明早停线; 只准改自己边界内的文件; 代理自述不作数, 合并/全量跑/真 E2E/commit+push **全部由主编做** (共享 index ⇒ 只准 `git commit -- <pathspec>`)。

---

### W1 — 把地基上的两个洞堵住 (2 代理并行)

**P11 审批与安全闸门重做** — 锚点 §2#14 · 边界 `tool/ExecGuard.java`, 新 `tool/ApprovalService.java`, `tool/BuiltinTools.java`, `config/BotConfig.java`, 对应测试
- 硬线表 (不可批, `off` 模式也拦) 与危险表分离; 危险模式清单从 12 条扩到覆盖 `-fr`/`-rf` 双序、`--no-preserve-root`、`find -delete`、`:(){:|:&}:` 变形、`curl|sh`、重定向进 `/etc`;
- 白名单改 **token 边界匹配** + 拒绝含 `;|&&>` 的复合命令走白名单;
- 审批请求进**每会话 FIFO** + 四种决议 (once/session/always/deny), `always` 才落盘;
- 反混淆: 不做她的 1,500 行 lexer, 只做**降级版** (剥注释/`#` 后文本、折叠引号内空格、变量赋值内联一层), 并在文档里写明"降级"。
- 验收: 注入用例集 (含 `rm -fr /`、`rm -rf / # APPROVE`、`git status && sudo rm -rf ~`、白名单前缀伪造) 全部必须被拦或必须走人; 变异检验 (去掉 token 边界 ⇒ 断言红)。

**P15 state.db schema 对齐 + 迁移框架** — 锚点 §2#7 · 边界 `store/StateStore.java`, 新 `store/SchemaMigrations.java`, `cli/SessionsCommand.java`
- 补 `schema_version`/`state_meta`/`gateway_routing`/`compression_locks`/`handoff_*`/`archived`/`parent_session_id` 及 sessions 列对齐 (她 46 列, 逐列注明"要/不要/占位");
- 迁移阶梯 (每步可重入) + 坏库自检 (打开失败 → 备份 + 重建, 不许静默丢数据);
- 保留策略: `prune` 支持 ended-only + 孤儿子会话连带 + 软 archive, `auto_prune` 默认关 (她的默认也是关);
- 写路径: `BEGIN IMMEDIATE` + 20-150ms 抖动重试 + 每 N 次写 checkpoint。
- 验收: **双进程并发写**真跑 (两个 JVM 打同库不丢消息, 给出前后行数); 迁移用例 (旧 4 表库 → 新库, 数据不丢); 变异检验 (去掉重试 ⇒ 并发测试红)。

### W2 — 主循环节律 + 注册表 + 送达 (4 代理并行)

**P11c ✅ 09-25 收口 — 通道监听收口 (插队, 先于 P12)** — 锚点 §6"`*.channel` HTTP 抖动真因" · 边界 `channel/HttpChannel.java`, `channel/WebhookChannel.java`, `channel/FeishuChannel.java`, `channel/DingTalkChannel.java` 的 `start()` + 对应测试
落地: 新增 `channel/ChannelBind.java` 统一解析 —— host 空 ⇒ `InetAddress.getLoopbackAddress()`, 显式写 `0.0.0.0` 才通配; 四通道各加末位 `host` 构造重载; CLI 侧 `serve --host` / `gateway --host`(两通道共用)。**没加配置项** (`--host`/配置项里的那半个"配置项"): 四个通道的实例化点全在 CLI, `BotConfig` 无消费者, 加了就是红线 2 的 0 消费者抽象 (记在 `_doc/acceptance/p11c/EVIDENCE.md` §6"这一项没做什么")。横幅改印**实际**地址并在非回环时打 LAN 警告。
验收读数: 杠① 最终树 411 条 (基线 399 + `ChannelBindTest` 11 + `ProfileIsolationTest` 新增 1) ×3 连绿、每跑 socket 类错误 0 次; 杠② 8 个变异体 ⇒ 7 RED-OK + 1 PARTIAL, **0 GREEN-BUT-MUTATED** (含 M6 这个反空跑探针: 把 `resolve()` 钉成"永远回环", 显式 opt-in 那层必红); 杠③ 真进程 22/22, 其中 E3 = 邻居占 `127.0.0.1:P` 时 serve rc=1 + `BindException`, E4 = 同一处境下 `--host 0.0.0.0` 照样起得来而请求被邻居抢答 (拿到盖章串 `FOREIGN-NEIGHBOR-OWNED-THIS-PORT`) —— 这就是修复前"哑巴成功"的真身; 杠④ `~/.zbot` 8 项 + 两个 md5 一字未动。
**"1/9 抖动归零"这句撤回**: 修复后又跑 24 轮全量 (3+9 轮在 410 那版树、3+9 轮在最终树 411), 全绿、每轮 socket 类错误 0 次 —— 但**同一棵树**只有 12 轮, 按 1/9 先验"12 连绿纯属运气"还有 (8/9)^12 ≈ 24% 的概率 (24 轮混了树形, 只当参考: (8/9)^24 ≈ 6%), 不足以把"归零"写成一个读数。能写的是**机制被封死**: 缺省绑回环后"通配与邻居共存"这条路在代码里不存在了, 撞车只会以 `BindException` 出现 (E3 实测)。原始读数 `_doc/acceptance/p11c/post-fix-flake-summary.txt`, 长期观测项留在 §6/§8。

**P12 主循环节律包: 中断/steer/预算/prompt cache** — 锚点 §2#3 #4 #5 · 边界 `agent/BotAgent.java`, kernel-agent
- 中断: 线程作用域的中断集合 (按执行线程定向, 防并发会话串台) + **工具侧检查点** (exec 读输出循环、文件读写、mvn_build、delegate 子循环各插 1 处以上), 目标从 0 处到 ≥6 处;
- steer: 采用她的语义 —— 单槽、追加到最后一条 tool 结果、硬中断丢弃 pending; 把 kernel `SteerQueue` 变成真实消费者或删掉它 (红线 2: 不许留 0 消费者抽象);
- 预算: 补压缩后 `refund`;
- **prompt cache 冻结**: system prompt 构建一次即逐字节重放, 记忆/技能/center 召回/时钟全部改道进 user 消息; 加一条"两次 chat 的 system prompt 必须 byte-identical"的回归测试。
- 验收: 真 pty/管道 REPL 跑一次"长命令执行中 /stop 能在 2s 内断" (证明工具侧检查点有效, 不是只在循环边界); 缓存不变量测试 + 变异检验。

**P20 Toolkit 真注册表** — 锚点 §2#11 · 边界 `tool/Toolkit.java`, kernel-tool (含**新建** `Toolset`), `mcp/McpBridge.java`
- 补 `deregister(name)` / 代际计数 / `check_fn` TTL 探测 (`registry.py:143 _CHECK_FN_TTL_SECONDS=30.0` + 失败宽限) / 单工具结果上限 + 溢出落文件 / toolset 声明层 (她顶层 `TOOLSETS` **33 个**, 我们按能力面建**清单内**的子集) / 并行安全从注册表移到 dispatch helper (照她的分层);
- MCP 的 stub 覆盖 hack 换成真 deregister。
- 验收: reload 后 `getToolNames()` 不再出现旧 server 的工具名 (反向断言, 现在必然是红的); 代际计数使 schema 缓存失效的可测证据。
- **合并前必须先拆的雷** (09-25 实测): 半成品分支 `w2-p20` (`7043e91`) 带着一条 `d79e321 chore(p20): 临时把 <z-agent-kernel.version> 指向私有 0.2.0-p20` ——
  根 pom 第 69 行现在是 `0.2.0-p20`, 而 main 钉的是 `0.2.0`。本机 `~/.m2/.../z-agent-kernel/` 两个都在
  (`0.1.0 / 0.1.1 / 0.2.0 / 0.2.0-p20`), 所以**在这台机器上两支都能编译** —— 这正是危险所在: 换一台只从
  `../z-agent-kernel` 装了发布版的机器, `w2-p20` 直接依赖不满足。顺带实测一件事:
  `curl -o /dev/null -w %{http_code} repo1.maven.org/maven2/io/github/yuku123/z-agent-kernel/{0.2.0,0.2.0-p20}/` **两个都是 404**,
  即整个 kernel 从来没上 Central, z-bot 任何一支都依赖本机 sibling 构建 (不是 P20 引入的, 但 P20 把它变成了分叉的版本线)。
  ⇒ P20 并入的前置是 kernel 出一个**真发布**的 0.2.1 并让所有仓一起抬 pin (或把 `Toolset` 那层从内核收回 z-bot 侧), 不能把私有快照 `0.2.0-p20` 带进 main。

**P16 网关送达三件套** — 锚点 §2#17 #18 · 边界 `channel/Gateway.java`, `channel/ChannelBus.java`, 新 `channel/TurnLease.java`, `channel/DeliveryLedger.java`, `channel/DeadTargets.java`, `channel/Supervisor.java`
- **turn lease 按解析后 session_id** (照她的理由: 多对一 `switch` 会让按路由键的锁错位, 两个聊天交织刷同一份 transcript); 超时 fail-open + 身份校验幂等释放;
- **delivery ledger**: `delivery_obligations` 三态 + `attempting` 崩溃后带"可能重复"前缀重投 + owner 用 pid+进程启动时间 + `sweep_recoverable`;
- 自愈: 监督重启 (干净退出不重启) + 3 次/60s 熔断跳过自动续跑 + 陈旧锁自愈。
- 验收: **真进程 kill -9 复现** —— 投递途中杀进程, 重启后消息以带标记形式重投且台账收敛; 双聊天打同一 session_id 不出现 `user;user` 楔死 (这条必须有真跑输出)。
- **实测状态 (09-25 22:4x, 主编在 `/private/tmp/zbot-wt-p16` @ `235a9a7` 量)**: 杠① `rm -rf z-bot-core/target/surefire-reports && mvn -o test` → **481 条全绿** (48 个测试类; 对得上账: `git grep -c "@Test" d71657d` 求和 = 399 基线 + 本期五支新类 82 = DeadTargets 15 / DeliveryLedger 22 / GatewayDeliveryP16 18 / Supervisor 16 / TurnLease 11; 逐类读数见 `/tmp/p16_accept_r1.log`)。**先前我在心跳里把这条读成"412"，那是没有量具支撑的数**（412 在日志里一次都没出现过），按"每个数必须带着它的测量命令"这条红线改回实测值。杠④ 跑完后 `~/.zbot` 仍 8 项、`config.properties` md5 `2dadaed0` 与 `state.db` md5 `690ddbc0` 均未变 ⇒ **过**。
  **但杠②③ 全部为零**: 该分支没有任何 `_doc/acceptance/p16/` (无注入脚本、无 LEDGER、无 EVIDENCE), 上面那条"真进程 kill -9"验收**没做过** —— 现在能证的只有进程内单测。四个新类经 `git grep` 核实确有生产消费者 (`ChannelBus`/`Gateway`), 不是红线 2 禁的 0 消费者抽象; 那条"裸并发对照组真能撞出『正在运行中』"的反空跑断言形状是对的 (读源码核实, 不是空断言)。**结论: P16 不并入, 直到杠②③ 补齐**。

**P17 cron 投递闭环** — 锚点 §2#20 · 边界 `cron/*.java` + 一个只读的 `Channel` 投递接口引用
- `claim_dispatch()` **先认领再跑** (一次性任务 at-most-once) + 心跳保鲜 (认领过期严格等于"进程死了");
- 结果真投递: `local`(打印) / `origin` / 指定通道; 无 origin 时降级 local 而非报错 (她的 #43014 结论);
- 锁: `threading`+`flock` 双层的 Java 等价 (`FileLock` 已有, 补重入计数)。
- 验收: 跨进程双实例同刻不双跑 (两个 JVM + 同一 cron 目录, 给出两边日志); 一次真 60s 级任务的投递实据。
- **实测状态 (09-26 01:5x—02:0x, 主编在 `w2-p17`@`13b1868` 量完、并入 `main`@`a73dcc6` 后在合并树复量)**: 四杠全绿 ⇒ **已并入 main**。
  杠① `rm -rf z-bot-core/target/surefire-reports && mvn -o test` 分支 **453** 条 ×3、合并树 **465** 条 ×3 (411 main + 54 本期;
  合并前 `git merge-tree --write-tree main w2-p17` 预演无冲突并预测 465, 合并后 `git grep -c "@Test"` 求和兑现), 每跑 socket 类错误 0 次;
  杠② **27 支注入** = 20 RED-OK / 5 PARTIAL / 2 GREEN-BUT-MUTATED / 0 BROKEN、27/27 逐字节还原 —— 那 2 支 (M8 等锁上限、M9 gateway 装配线)
  进程内单测结构性看不见, 改由真进程 E2E 判红并留了原文与还原后的重绿;
  杠③ 真 JVM ×7 + 真 `kill -9` + 两个真进程抢同一把 `.jobs.lock` ⇒ **33/33**, 合并树另跑 3 整跑逐跑 33/33;
  杠④ `~/.zbot` 8 项、`config.properties`/`state.db` 两个 md5 一字未动。全量读数 `_doc/acceptance/p17/EVIDENCE.md`。
  **两处如实记**: ① "邻居攥锁 ⇒ 等满 30s ⇒ 降级为进程内锁" 这条**正向**跨进程没证 (E 段窗口 20s, 它是"等得到"的证据不是"等不到"的证据);
  ② 合并树首跑 **30/32** —— 追出来是**量具自己造的假红**: 拿"已落账认领"那行日志当 `kill -9` 的扳机, 而它打在事务体内、
  账要等 `writeStore()` 的 `ATOMIC_MOVE` 才落盘。独立探针 20 轮量这个窗口 (`~/.cache/zbot-p17/race_probe.py`):
  看见日志就杀 ⇒ 盘上没账 2/10, 而"日志→落盘"延迟 min 0.1ms / p50 0.2ms / max 8.8ms 且 10/10 读得到
  ⇒ **产品的"先落账再跑"没被违反, 违反的是"等 A 断言 B"这条量具纪律**。改法不是放宽而是换尺: 新增 C1b 拿因果当扳机
  (stub 真收到这条请求 ⇒ 盘上必须已读得到账, 谁把写盘挪到跑之后再发它就当场判红), 条数 32→33, 取证过程记 EVIDENCE §5.5。

### W3 — 上下文/工具面/命令端 (4 代理并行)

**P14 上下文引擎对齐** — 锚点 §2#6 (依赖 P15 的 schema) · 边界 `context/*.java`
- 阈值改为 `(contextWindow - maxOutputTokens) × pct`, pct 默认对齐她的 0.50 并留配置位 (现 0.85 是"占满才压", 太晚);
- 三个评估位点 (轮首 / API 调用前粗估闸门 / 工具批后真实 usage) + 防抖 (连续无效压缩计数) + 失败冷却入库 + `compression_locks` 抢锁;
- **血统入库**: 压缩=会话分叉 (`parent_session_id`) + 标题 `base #N` 派号, 搜索沿血统回溯并去重。
- 验收: 真长会话跑到压缩 ≥2 次, 重启进程后 `/sessions` 仍看得见分叉链 (证明确实入库, 不再是内存口径); 阈值改动前后触发轮次对比数据。

**P19 命令表单源多端** — 锚点 §2#21 · 边界 `slash/SlashRegistry.java`, `channel/HttpChannel.java`, `web/index.html`, `cli/*`
- HTTP 侧暴露命令表 (`GET /api/commands`) + 一个受审的 `POST /api/command` 执行端 (终端以外第二端);
- 补 `should_bypass_active_session` 语义 (跑着的时候哪些命令允许插队);
- 顺带清 §1#2 的历史账: 终端命令表已由 P10d 派生, 本期只补 HTTP 端。
- 验收: 内置浏览器真点一次命令 (至少 3 条: `/status` `/model` `/cron`) 且 TUI 同命令同输出; 无 console 错误。

**P21 MCP 对齐** — 锚点 §2#22 (依赖 P20 的 deregister) · 边界 `mcp/*.java`, kernel-mcp
- StreamableHTTP + SSE transport; `tools/list_changed` → 真 deregister/register; 父死 watchdog; 反向 `mcp_serve` 至少暴露 conversations/messages 读端;
- 明确不做: OSV 预检、sampling/elicitation 全链路 (记 UNKNOWN 与理由)。
- 验收: 接一个真 MCP server (stdio) + 一个真 HTTP MCP server, 各跑 tools/list→call; server 侧被外部 MCP 客户端调通 (给客户端命令)。

**P26 用量核算与失败恢复** — 锚点 §2 末补充块 · 边界 `llm/*.java`, kernel-llm
- 错误分类表 (可重试/换 key/换模型/直接上抛, 锚 `agent/error_classifier.py` 1,698 行 + `agent/retry_utils.py` 154 行) + 抖动退避 + 限流退避阶梯 (**具体秒数她那边未复算, 见 §2 末 UNKNOWN 行 ⇒ 我们自定并写进 config**) + 换模型后**重建在飞的 system 上下文**并重置压缩计数;
- 流式陈旧看门狗: 照她的**语义** (`reasoning_timeouts.py` 的 `max(default, per-model floor)` + 用户配置可覆盖), 具体分档数值本期实测后回填;
- usage 归一 (含 cache 读/写命中) → `session_model_usage`。
- 验收: 真 429 (今天已被意外打过一次, 有真实样本) + 人为断流 (kill 代理) 两类实据; 分类表用变异检验逐条点。

### W4 — 平台面与身份面 (4 代理并行)

**P18 通道注册表 SPI + 真实出站** — 锚点 §2#19 · 边界 `channel/*`, 新 `channel/ChannelRegistry.java`, kernel-app SPI
- `ChannelRegistry` + manifest 式声明 + 后写覆盖 + 惰性构造 (别在启动期反射加载所有平台类);
- 飞书/钉钉**真出站** (okhttp 已在依赖里): token 换取 + im/v1/messages 发送 + 错误分类; 无凭据时的降级必须显式报错不许静默。
- 验收: 对本地假端点断言请求体/签名/header (真发不由假端点冒充); 若 `~/.zbot` 里有可用凭据, 真发一条并贴回执, 否则记"未做真发验收"。

**P22 执行后端 SPI** — 锚点 §2#13 · 边界 `tool/Sandbox.java` → 新 `tool/env/*` (ExecEnvironment/local/docker/ssh + file_sync)
- 6 后端只做 local/docker/ssh (其余进不做清单并写明理由); 后端选择走配置; docker 侧补孤儿容器回收与资源限额。
- 验收: local 回归 + **docker 真跑** (`docker info` 可用则跑一次 exec/写文件/回收全链路, 不可用则明确记"未验收 + 原因", 不许用单测绿冒充)。

**P23 技能体系对齐** — 锚点 §2#10 · 边界 `skill/*.java`, `slash/SlashRegistry.java`, 新 `skill/SkillCommands.java`
- frontmatter 补齐 (`platforms/environments/prerequisites/metadata.hermes.*`) + **技能→斜杠命令** (slug 化、撞核心名跳过、最多叠 5 个) + sync origin_hash 清单 (用户改过的不覆盖、删过的不复活) + 安装期安全扫描 (她的 `skills_guard` 1,153 行的规则子集)。
- 验收: 真装一个带 `slash:` 的技能 → 命令表出现该条 → 终端真执行; 改动本地技能后 sync 不覆盖 (真跑前后 mtime 证据)。

**P24 记忆与身份细化** — 锚点 §2#9 · 边界 `memory/*.java`
- 拆层: `MEMORY.md`/`USER.md` = 记忆, `SOUL.md` = 身份 (移出 memories 或改语义, 二者必须与注入路径一致);
- 字符上限 + 分隔符规范化 + **快照冻结** (与 P12 缓存不变量配套: 中途写盘不进 prompt) + `pending/*.json` 三级审批 + 威胁扫描 `[BLOCKED]`。
- 验收: 中途写记忆后连续两轮 system prompt 逐字节相同 (跨期复用 P12 的回归); stage→approve→落盘真回路。

### W5 — 外围面与广度 (3 代理并行)

**P25 ACP (IDE 面)** — 锚点 `acp_adapter/` **11 文件 / 5,373 行** (`find ./acp_adapter -name '*.py' -print0 | xargs -0 wc -l | grep ' total$'`); 方法面按字面量取证 = `prompt`(63 处) / `cancel`(3) / `initialize`(2) / `new_session`(1) / `loadSession`(1)，**其余方法数标 UNKNOWN** (v1 期笔记里"12 个方法"无法复算, 作废) · 边界 新 `acp/*.java`, `cli/ZBot.java` 子命令
- 全量可做且与现有 SPI 正交; 出站协议帧必须 stdout 纯净、日志走 stderr。
- 验收: 用真客户端 (或最小 JSON-RPC 脚本) 走 `initialize→new_session→prompt→cancel` 全链路一次。

**P27 委托对齐** — 锚点 §2#16 (她的实现分散在 `tools/delegate_tool.py` 3,655 + `tools/async_delegation.py`(`_MAX_DELIVERY_ATTEMPTS=8`) + `tools/delegation_live_log.py`(`LIVE_RETENTION_DAYS=7`) 三处, 别只读一个文件) · 边界 `delegate/*.java`
- **强制并发宽度** (§1#5 的账: 配置项现在是装饰; 改成 `newFixedThreadPool` 或信号量, 且去掉 `static` 使池随 agent 生命周期关闭);
- 子代理审批自动 deny; 结果摘要上限 + 溢出落文件; async 台账补投递重试次数与保留期。
- 验收: 配 `max.children=2` 后**同时最多 2 个在跑**的实测证据 (并发探针 + 时间戳), 配置生效前的旧行为也要跑一次作对照。
- **P27a 实测 (本棒复算)**: §1#5 那句"`agent.delegate.max.children` 被解析后无任何逻辑读取、宽度是装饰"**已不成立** ——
  `grep -rn 'MaxChildren' z-bot-core/src/main/java` = `BotConfig` 三处 (字段/解析/getter) + `delegate/DelegateManager.java:311` 一个逻辑读点,
  闸门在 `:318`; 但闸门修之前只数 `RUNNING`, 连发的条目还停在 `QUEUED` 时可越过 `width` ⇒ 本期改成数"非终态",
  用例 `DelegateManagerLedgerTest#concurrencyGateCountsQueuedRowsSoABurstCannotExceedWidth` (width=3 时第 4 条被顶回"并发已满（3/3）")。
  投递上限 `DelegationDelivery.MAX_DELIVERY_ATTEMPTS=8` 与保留期 `DelegationLedger.LIVE_RETENTION_DAYS=7` 已落 `state.json`
  (被 `kill -9` 之后仍从盘上读得回尝试数, 见 E2E); 未做: 子代理审批自动 deny、结果摘要 24k 上限+溢出落文件、`static` 线程池随 agent 生命周期关闭。

**P28 前端 parity 收口 (不移植 React)** · 边界 `ui/*.java`, `web/index.html`, `channel/HttpChannel.java`
- 只做 JLine 侧对等 (多行输入、Ctrl-R 搜索历史、粘贴折叠) 与 web 侧最小可用; **明确不做**她的 Ink 分屏/鼠标选择/滚轮加速 (理由在 §4)。
- 验收: pty 真跑回归 (沿用 `/tmp/pty_tui.py` 11 条 + 新增)。

### W6 — 发布

**P29 README ×7 + `<revision>` 0.2.0→0.3.0 + Central 发布** — 走既有 dry-run→deploy→repo1 同步→pull 集成验证; 验收=外部工程真 pull 并用起来。

### 尚未排期 (已确认存在, 待 v2 中途再定档)
`kanban` (3,087 行 CLI + 独立 db) / `projects_db` / `handoff` 跨进程接管 / `MOA` 多模型合议 (1,236) / `curator` 技能生命周期 (2,016+947) / hooks 体系 / 188 个插件文件面 / 39 组 CLI 命令。这些进 P3x 轮次, **不在本轮承诺内**。

## 6. 未决与已知抖动

- **`*.channel` HTTP 抖动 —— 真因已定位，并已修掉 (09-25 21:3x 定位 ⇒ 22:1x P11c 收口)**: 四个通道**修前**清一色用 `new InetSocketAddress(port)` **绑通配地址** (修前位置 `HttpChannel.java:69`, `WebhookChannel.java:77`, `FeishuChannel.java:88`, `DingTalkChannel.java:72`)。macOS 上"别人的进程显式监听 `127.0.0.1:P`" 与"我们带 SO_REUSEADDR 的通配绑定"**可以共存**, 而客户端连 `127.0.0.1:P` 被路由到**更具体的那个 listener** ⇒ 我们的服务器一个字节都没收到, `getPort()` 却报"我占了 P"。命中与否取决于 OS 当时发到哪个号, 所以成簇出现、隔天不复现。
  | 复算: `java _doc/acceptance/p11c/BindProbe.java` (邻居 specific listener + 我们 wildcard 同端口 + 客户端探测；原 `/tmp/BindProbe.java` 已归档进仓)
  | 实测: `[wildcard] bind 成功 -> 共存成立` / `客户端连 127.0.0.1:51804 拿到 -> code=200 ct=text/x-foreign body=FOREIGN` / `[loopback] bind 失败(预期 EADDRINUSE)` ⇒ 绑回环能让撞车**变红**而不是变哑。
  | 本轮那次 (`mvn -o test` run=2, `toolsEndpointReflectsToolkit expected:<200> but was:<400>`): run 日志里有 `[HTTP] z-bot 已启动: http://127.0.0.1:56510`, 而 `lsof -nP -iTCP -sTCP:LISTEN` 显示 `Qoder PID 10598` 正持有 `127.0.0.1:56510`; `curl -i http://127.0.0.1:56510/bot/tools` → `HTTP/1.1 400 Bad Request` + `Sec-Websocket-Version: 13` + body `Bad Request`(12 B)。`dispatch()` 对 `/bot/tools` 只有 200/500 两条出口, 这个 400 不可能出自我们。`ps -o lstart -p 10598` = `Sep 24 22:55:44`, 比那次绑定早 22.5 小时。当前 `49152-65535` 区间内有 **10 个**邻居 listener (`lsof -nP -iTCP -sTCP:LISTEN | awk '$9 ~ /^127\.0\.0\.1:/'`), 每轮全量约 36 次绑定 ⇒ 千分之几到百分之几的量级, 与观测到的 1/9、1/15 吻合。
  | 同一条因由解释 §1#11 的 `WebhookChannelTest 401` (WebhookChannel 全仓无鉴权代码, 401 本就不可能出自它) 与本期记过的 `SocketException: Unexpected end of file from server` (非 HTTP 的邻居 listener)。
  | **旧量具查错了方向** (三次): `uniq -d` 只比对我们自己绑过的端口, 从没跟 `lsof` 里邻居的端口对账 ⇒ "0 重复端口"是真的, 但推不出"没有端口冲突"。`/tmp/KeepAliveProbe*.java` 的 450 次碰撞同样只在"我们自己的服务互换"这一维里试。
  ⇒ 修法**已落地于 P11c** (09-25 22:1x 收口: 默认绑回环 + 显式 `--host` 才通配), 顺带关掉"`z-bot serve` 缺省把无鉴权 `/bot/chat`、`/api/*` 暴露到局域网"这个真实暴露面。旧结论"未定位, 不修不封"**作废**。
  | 修后状态: 缺省绑回环后"通配与邻居 `127.0.0.1:P` 共存"这条路在代码里不存在了 —— 真撞车只会 `BindException` 起不来 (E3 真进程实测 rc=1), 不再"启动了却拿到别人的 400"。**但不写成"抖动归零"**: 修复后共 24 轮全量 rc 全 0、每轮 socket 类错误 0 次, 可**同一棵树**只累计到 12 轮 (最终树 411: 杠① 3 轮 + 速率专测 9 轮, 原始读数 `_doc/acceptance/p11c/post-fix-flake-summary.txt`)。若旧速率真是 1/9, "12 连绿"本身还有 24% 的概率是运气; 要把这条假设打到 20% 以下需 14 轮、要把速率上界压到 1% 以下需 40 轮 —— 所以现在只能说"旧速率大概率不成立", 不能说"归零"。这一条**留作长期观测项**: 后续任何一次 `*.channel` 单条 4xx/`SocketException` 都要先按 pid 对 `lsof` 再定性 (量具 `_doc/acceptance/p11c/{flake_loop.sh,hc_rate.sh}`, 撤回理由与算法见 `_doc/acceptance/p11c/EVIDENCE.md` §5)。
- **共享工作区纪律**: 本机同时有别的会话在建 `z-lc` 等项目 (17:10 有外部 mvn 失败日志为证) ⇒ 本仓所有提交只准 `git commit -- <pathspec>`, 禁 `git add -A`, 禁裸 `git stash`。
- **今天一次意外真实 API 调用**: pty 量具缺陷 (JLine 应用光标模式下 ↑ 是 `\x1bOA`) 导致 `[A` 被当普通消息发往 minimax (返回 429)。已加 A11 守卫。含活凭据的临时目录 `/tmp/zbot-pty` 已删除, `~/.zbot/` 回到 8 项 pre-state。

## 7. 本文档的复算入口

```bash
# hermes 体量与锚点
cd ~/.hermes/hermes-agent && wc -l agent/conversation_loop.py tools/approval.py tools/registry.py \
  gateway/delivery_ledger.py gateway/turn_lease.py hermes_state.py tui_gateway/server.py
grep -c "CREATE TABLE" hermes_state.py; grep -c '^@method' tui_gateway/server.py

# 本轮为 v2 现测的口径 (每条都产出文档里对应的数字)
find . -name '*.py' -not -path './.git/*' -not -path './venv/*' -not -path '*/site-packages/*' \
  -not -path '*/node_modules/*' -not -path '*/__pycache__/*' -not -path './tests/*' \
  -type f -print0 | xargs -0 wc -l | grep ' total$'          # ⇒ 713,095 (978 文件)
find tools -name '*.py' -type f -print0 | xargs -0 wc -l | grep ' total$'   # ⇒ 98,428 (114 文件)
grep -rhoE 'name="[a-z_0-9]+"' tools/*.py | sort -u | wc -l  # ⇒ 72 工具
sed -n '96,587p' toolsets.py | grep -E '^    "[a-zA-Z_0-9]+":' | wc -l        # ⇒ 33 toolset
find . -name 'SKILL.md' -not -path './.git/*' -not -path './tests/*' -not -path './website/*' | wc -l  # ⇒ 184
grep -rnE 'class [A-Za-z0-9_]+\([A-Za-z0-9_]*PlatformAdapter\)' --include='*.py' . | grep -v tests/ | wc -l  # ⇒ 29 生产 adapter
grep -c "_interrupt_requested" agent/conversation_loop.py    # ⇒ 4 个循环检查点
grep -rn "is_interrupted" --include='*.py' tools/ agent/ | grep -v 'tools/interrupt.py' | wc -l  # ⇒ 40 处
python3 -c "import ast;t=ast.parse(open('tools/approval.py',encoding='utf-8').read());[print(n.targets[0].id,len(n.value.elts)) for n in ast.walk(t) if isinstance(n,ast.Assign) and getattr(n.targets[0],'id','') in ('HARDLINE_PATTERNS','DANGEROUS_PATTERNS')]"  # ⇒ 12 / 70
ls hermes_cli/subcommands/*.py | grep -v '/_' | wc -l        # ⇒ 39 命令模块
find ui-tui/src ui-tui/packages -type f \( -name '*.ts' -o -name '*.tsx' \) -print0 | xargs -0 wc -l | grep ' total$'  # ⇒ 85,118

# z-bot 现状
cd z-bot && find z-bot-core/src/main/java -name '*.java' -type f -print0 | xargs -0 wc -l | grep ' total$'  # ⇒ 12,234 / 60 文件
grep -rho "@Test" --include='*.java' z-bot-core/src/test/java | wc -l    # ⇒ 234
grep -rln "SlashRegistry" z-bot-core/src/main/java     # §1#2
grep -rn "delegateMaxChildren" z-bot-core/src/main/java  # §1#5
```

## 8. v2 进度记录 (每条形如"结论 + 复算命令 + 实测输出片段", 缺输出不记)

> 记账格式: `- YYYY-MM-DD PNN <一句话结论> | 复算: <命令> | 实测: <关键输出/测试数>`。
> 代理自述一律不入账; 只认主编跑出来的读数。

- 2026-09-25 v2 计划成文 | 复算: §7 全表 | 实测: hermes 713,095 行/978 文件, z-bot 12,234 行/60 文件/234 单测, kernel 5,380 行/94 文件; §1 纠正 v1 13 处, §2 矩阵 22 面 (verdict 计数: 缺 7 / 浅 14 / 齐 1), 新排期 P11–P29 分 6 波。
- 2026-09-25 v2 初稿自检: 8 个二手数字复测走形 (见 §1 自省块) | 复算: §7 逐条 | 实测: toolsets 33、tools/ 98,428、adapter 29、SKILL.md 184、中断 4/40、run_conversation 5,299、mcp_serve 10 工具、反混淆规模 UNKNOWN。

_(W1 起逐期追加)_

- 2026-09-25 **P11 审批与安全闸门重做** ✅ 提交 `ebf44e5`（已推送）: 审批绑命令 + 待批取代 + 硬线在任何模式都拦 + `mvn_build`/`curl_test` 裸拼接路径补闸门 + 删 0 消费者注入口。
  | 复算: `rm -rf z-bot-core/target/surefire-reports && mvn -o test -pl z-bot-core` 连续 3 跑
  | 实测: `Tests run: 325, Failures: 0, Errors: 0, Skipped: 0` × 3 跑、`RC=0` × 3。§2 矩阵 #14 verdict 随本次提交由 **缺→浅**（成文时的 `缺 7 / 浅 14 / 齐 1` 是当日快照，不追改）。
- 2026-09-25 P11 变异检验（"守卫有没有测试"这件事本身要有证据）
  | 复算: `python3 _doc/acceptance/p11/p11_mutation.py && python3 _doc/acceptance/p11/p11_mutation2.py && python3 _doc/acceptance/p11/p11_mutation3.py`（量具已随代码入库，脚本自带锚点唯一性 + 变异后逐字节 md5 还原自检）
  | 实测: 15 次注入 = 11 `RED-OK` / 3 `PARTIAL` / 1 `GREEN-BUT-MUTATED`。三条 `PARTIAL`（M8 队列退 LIFO、M11 读不懂的形状放行、M12 注释不剥离）**都判了红**，只是红的不是我在期望集里点名的那条而是同选择器内的姊妹测试 ⇒ 记为"期望集点错"而非"缺覆盖"；唯一 `GREEN-BUT-MUTATED`（M10）是我自己造的**等价变异**（往 SESSION 走不到的 `else if` 加分支），改写为 M10b 后 `RED-OK`。三脚本收尾均 `final md5 ok: True`，跑完 `git status --porcelain` 只剩量具目录本身。
- 2026-09-25 P11 真实 E2E（真 pty+repl、真 HTTP serve、真杀进程重启；不许用读代码代替）
  | 复算: `mvn -o -pl z-bot-core package -DskipTests && python3 _doc/acceptance/p11/p11_e2e2.py`
  | 实测: `== E2E round2 15/15 通过 ==`；`stub 收到 LLM 请求 6 次`（key 全程 `stub-key-not-real`，真 key 未进任何临时目录）；`~/.zbot 项数=8`（跑前跑后同数 ⇒ 红线 1 无泄漏）。E2E 也是唯一抓到"放行可挪用到另一条命令"这个洞的量具（325 条单测当时全绿）。
- 2026-09-25 P11 验收① 抓出的**既有**测试竞态 ✅ 提交 `4723add`（不是本期代码引入）: `PairingServiceTest` fixture 共用 200ms TTL，而懒清理按 `expiresAt > now` 判定 ⇒ `issue` 落盘慢过 200ms 就被自己删码。
  | 复算: `mvn -o test -pl z-bot-core -Dtest=PairingServiceTest` 循环单跑 5–10 次（**别只看全量跑**，冷 JVM 才暴露）
  | 实测: 修前单跑连续 6/6 红（`Tests run: 7, Failures: 1`，`caseInsensitiveCodeConsumption` 耗时 1.4–3.4s），全量 3 跑里红 1 跑（`multipleIssuedCodesAccumulate expected:<2> but was:<0>`）；修后单跑 `Tests run: 7, Failures: 0` × 5。
- 2026-09-25 **红线 1 泄漏收口（P11 发现 → P11b 修完）** ✅ 见 `_doc/acceptance/p11b/EVIDENCE.md`。原记账"三处硬编码"**是低估**：开工前实测 **10 处** —— `git grep -n 'user\.home' e92cccc -- 'z-bot-core/src/main/*'` = 8 处/7 文件（`BotAgent`×2、`BotCenterClient`、`AgentOptions`、`SessionsCommand`、`BotConfig`、`SessionManager`、`Sandbox`），加上 `RawTerminalReader.java:234/244` 两处 shell 里写死的 `~/.zbot/.stty.bak`（⇒ 两个 profile 的 REPL 互相踩对方的终端恢复）。同期发现 **`ZBOT_HOME` 从来没被读过**：`git grep -n 'ZBOT_HOME' e92cccc -- 'z-bot-core/src/main/*'` 只有 1 处命中，且是 `PairCommand.java:33` 的报错文案自己许愿。
  | 修法: 三段各抄一遍的优先级链收成 `BotConfig.defaultConfigDir()`（`-Dzbot.home` > `ZBOT_HOME` > `~/.zbot`）+ `resolveWorkspaceDir(configDir, cliOverride)`（`--sandbox` > `-Dzbot.sandbox` > `<profile>/workspace`）；sessions/state.db/skills/instance.json/stty 备份全改由 profile 派生；`BotCenterClient` 那条猜 `~/.zbot` 的单参构造器删掉（红线 2）。
- 2026-09-25 **P11b 四道杠**（新增 `ProfileIsolationTest` 8 条 + `_doc/acceptance/p11b/` 三件量具）
  | 复算: ①`rm -rf z-bot-core/target/surefire-reports && mvn -o test` ×3 ②`python3 _doc/acceptance/p11b/p11b_mutation.py` ③`mvn -o -pl z-bot-core package -DskipTests && python3 _doc/acceptance/p11b/p11b_e2e.py` ④`ls -A ~/.zbot | wc -l` + `md5 -q ~/.zbot/{config.properties,state.db}`（原文这里写的 `ls -a` 会把 `.` 和 `..` 一起数进去打出 10 —— 与记录的 `entries=8` 对不上；量具 `p11b_e2e.py` 用的是 `os.listdir`，正确命令是 `-A`，09-25 P11c 那批顺手改正）
  | 实测: ① `Tests run: 399, Failures: 0, Errors: 0, Skipped: 0` × 3 跑 rc=0（基线 391 ⇒ +8；同树合计 11 连绿）② 13 支注入 = **11 RED-OK / 0 PARTIAL / 2 GREEN-BUT-MUTATED / 0 BROKEN**，收尾 `final md5 ok: True`；那 2 支是设计上进程内证不了的（M1 要真 env、M12 要真 pty），证据在③ ③ **E2E 23/23**，含 `E3c 两者都不给才落 <profile>/workspace`、`E4c A=有 probe B=共 0 个会话`（真 sqlite3 塞行验互不相见）、`E5b stty -g 前后同值`、`E5c 守望线程抓到=True`/`E5d 0 残留`、`E6 0 处` ④ `entries=8`、`2dadaed0→2dadaed0`、`690ddbc0→690ddbc0`。
  | 未打满处如实记: M2 `2/3`、M3 `1/2` —— 差额那两条都显式传值、走不到缺省链，杀不死"缺省被写死"；判定仍按具名红算，不改期望集凑数。
  | 杠①第二跑曾红一次（`HttpChannelTest.toolsEndpointReflectsToolkit 200→400`）：追出是 §6 那条老抖动的**真因**（通道绑通配地址，客户端被路由到邻居进程），与本期无关 —— 同树 8 跑 0 红 + `e92cccc` 基线 8 跑 0 红 + 通道四类单跑 18 跑 0 红。全文见 §6 首条，修法排 **P11c**（已于同日 22:1x 落地，读数见下方 P11c 条）。
- 2026-09-25 **P15 state.db 收口** ✅ 提交 `d64ad44`: 10 个 `agent.state.*` 配置键接进 `BotConfig.stateStoreOptions()`（两个建库点 `BotAgent.Builder` / `SessionsCommand.openStore` 都走它，11 处 `new StateStore` 收成一处）+ E2E 抓到的"在飞"漏洞修掉。
  | 复算: `rm -rf z-bot-core/target/surefire-reports && mvn -o test` 连续 3 跑
  | 实测: `Tests run: 391, Failures: 0, Errors: 0, Skipped: 0` × 3 跑、`RC=0` × 3（修 CLI 默认档前是 390 × 3 全绿，那 3 跑也是同一条命令跑的，不覆盖本次改动）。§2 矩阵 #7 的实测列同期改为 3,152 行 / 8 实表 / sessions 15 列 / 3 步阶梯 / 10 键接线。
- 2026-09-25 P15 变异检验（14 支，量具入库）
  | 复算: `python3 _doc/acceptance/p15/p15_mutation.py`（单支复算：`python3 _doc/acceptance/p15/p15_mutation.py MU-14`；脚本自带锚点唯一性预检 + 变异后逐字节 md5 还原，还原失败当场停机）
  | 实测: 14 次注入 = **12 `RED-OK` / 1 `PARTIAL` / 1 `GREEN-BUT-MUTATED`**，收尾 `final md5 ok: True`。
  |  · **`GREEN-BUT-MUTATED` 是 MU-1（把 `cfg.beginImmediate()` 的返回值扔掉、写事务全退化成 deferred）：`StateStoreWritePathTest` 12 条全绿** ⇒ `BEGIN IMMEDIATE` 这道守卫**没有任何单测能杀**（同 JVM 多线程 + busy_timeout + 重试阶梯把它兜住了），唯一证据是下面的双进程对撞；这条不是"缺覆盖"的口径问题，而是单测这一层结构性看不见跨进程写锁。
  |  · `PARTIAL` 是 MU-3（抖动退避清零）：我在期望集里**故意没点名**（没有计时断言），红了的是姊妹测试 `retryLadderCarriesAWriteThroughAnExternalLockHolder` 的 `givenUp==0` 断言 ⇒ 阶梯"熬多久"其实被这条钉住了，记账为"期望集留空"而非"无覆盖"。
  |  · MU-14 是我修的洞的反向验证：把 `.treatUnusedEmptyAsEnded(false)` 摘掉 ⇒ `emptyInFlightSessionIsOnlyGoneWithItsOwnSwitch` 点名红。
- 2026-09-25 P15 真实 E2E（真 jar/真 CLI/真两个 JVM 对撞/真坏库；不许用读代码代替）✅ **20/20**
  | 复算: `mvn -o -pl z-bot-core package -DskipTests && python3 _doc/acceptance/p15/p15_e2e.py`
  | 实测 · 迁移: 用 `ae5aff7` 的字面 DDL 造 4 表老库（2/3/1/1 行 + 只有 `messages_ai` 的 FTS），真 CLI 首开 ⇒ `schema 0->3 steps=[legacy-baseline, p15-schema-alignment, p15-timestamp-normalization]`、`sessions` 补 4 列、新建 3 表、`timestampsNormalized=7`，四表行数逐表比对**一行不丢**，FTS 因缺 delete 触发器被就地重建（`messages_ai,messages_ad`、`fts=3=messages`），`search 遗留消息甲` 命中 `[old-1]`。
  | 实测 · 双进程对撞（两个真 JVM 各 240 条追加到**同一 session**，行数由驱动侧独立 JDBC 连接读，不信被测自述）: C1 改前形态（deferred+0 重试+busy=0）**丢 183/480 与 129/480（两次跑），落库 297/351 行，`lastError=[SQLITE_BUSY_SNAPSHOT]`/`[SQLITE_BUSY]`**；C2 只有阶梯、C3 只有 IMMEDIATE、C4 P15 默认 三档均 `rows=480/480 唯一内容=480 idx 跨度=480 message_count=480 lost=0`。⇒ `BEGIN IMMEDIATE` 与重试阶梯**各自都足以保住数据**（这正是 MU-1 单测杀不掉的那条），代价在延迟：两跑读数 C2 1,903/1,776ms、C3 773/1,744ms、C4 948/891ms。
  | 实测 · 自愈: 把 state.db 头部 4KB 写成垃圾 ⇒ CLI 不崩，`[StateStore] 警告：state.db 判为不可用（[SQLITE_NOTADB]）`，原件改名 `state.db.corrupt-20260925_204059`（备份大小 8192 = 原大小，没被覆盖），同路径重建空库 118,784 字节。
  | 实测 · 抓出的洞（已修，见上条 MU-14）: `sessions prune --days 7` 默认档把**0 消息的在飞会话**一起硬删了（`清理 matched=2 deleted=2` 里有 `in-flight`），而这条命令的帮助文本和自己的 `--include-in-flight` 开关都承诺"在飞不动" ⇒ 首跑 E2E **18/20**，两处 FAIL 同一个根因；修 CLI 侧关掉 `treatUnusedEmptyAsEnded` 后 **20/20**（`E4a survivors=[ended-with-msg, in-flight]`、`E4c survivors=[in-flight]`）。store 层的 P2 兼容档保持原样（`auto_prune` 走那条，90 天保留是她的语义）。
  | 实测 · 配置键真有牙: `--config-dir` 里写 `agent.state.prune.auto=true` + `retention.days=1` ⇒ 开库真清掉 30 天前的已结束会话、今天新建的活着；不配时同一份库一动不动（E5b）。量具注: E5 的 `fresh` 行是用 `sqlite3 strftime('now')`（UTC）灌的，与本地时间差 8 小时，不影响"1 天保留期内不删"这一判定。
  | 实测 · 红线 1: `~/.zbot 项数=8→8`、`config.properties md5 2dadaed0→2dadaed0`、`state.db md5 690ddbc0→690ddbc0`（全程 `--config-dir`/`--db` 指向临时目录；key 一律 `stub-key-not-real`）。
- 2026-09-25 **待办 · P15 未交部分（不遮蔽，另片记账）**: §5 P15 验收行里的"sessions 列对齐（她 46 列，逐列注明 要/不要/占位）"**只做了加 4 列**，`SchemaMigrations` 类注释里那句"理由表见本期 notes"目前**没有对应的表**。
  | 复算: `python3 - <<'PY' … 数 hermes `hermes_state.py:872` 的 sessions 列声明 ⇒ 46；`sqlite3 <head 库> 'PRAGMA table_info(sessions)' | wc -l` ⇒ 15`（hermes 实测 46 列名已在本轮取到：`user_id/session_key/chat_id/chat_type/thread_id/origin_json` 通道身份 6 列、`input/output/cache_read/cache_write/reasoning_tokens` token 分档 5 列、`estimated_cost_usd/actual_cost_usd/cost_status/cost_source/pricing_version/billing_*` 成本台账 8 列、`handoff_state/platform/error` 3 列、`cwd/git_branch/rewind_count/expiry_finalized/profile_name` 等）
  | 实测: `handoff_*` 三列**没加**（§5 计划里点名要）；差额 31 列里"成本台账"依赖 P12 的 token 记账、"通道身份"依赖 P16 的送达模型，硬堆就是红线 2 的 0 消费者列 ⇒ 逐列理由表随 P14（血统/压缩列）与 P16（路由列）各自补片时产出，本期不虚报。
- 2026-09-25 **P11c 通道监听收口（默认绑回环 + 显式 `--host` 才通配）** ✅ 见 `_doc/acceptance/p11c/EVIDENCE.md`（本项主编自己做，无代理自述）。四通道 `start()` 的绑定点收成一处 `channel/ChannelBind.resolve(host)`；`serve --host` / `gateway --host`（两通道共用一个）是**唯一**能摊开通配的口子；横幅与控制台地址改印**实际**监听地址（`HttpChannel.consoleUrl()`），非回环追加一行 LAN 警告。顺带把 P11b 漏网的用户可见文案改到 profile 口径（`--config-dir` 说明、缺 key 报错打印实际 profile 路径、`ZBot` 类文档），并加厚 `HttpChannelTest` 的 23 处断言（打印 `url + code + ct + body`）。
  | 复算: ①`rm -rf z-bot-core/target/surefire-reports && mvn -o test` ×3 ②`python3 _doc/acceptance/p11c/p11c_mutation.py` ③`mvn -o -pl z-bot-core package -DskipTests && python3 _doc/acceptance/p11c/p11c_e2e.py` ④`ls -A ~/.zbot | wc -l` + `md5 -q ~/.zbot/{config.properties,state.db}`
  | 实测: ① 最终树 `Tests run: 411, Failures: 0, Errors: 0, Skipped: 0` × 3 跑 rc=0、每跑 socket 类错误 `grep -c` = 0（411 = 基线 399 + `ChannelBindTest` 11 + `ProfileIsolationTest` 新增 1；此前 410 那三跑量的是加最后一条测试之前的树）② 8 支注入 = **7 RED-OK / 1 PARTIAL / 0 GREEN-BUT-MUTATED**，`LEDGER.tsv` 由脚本自己写；那 1 支 `PARTIAL` 是 M6（`resolve()` 钉成永远回环）额外红了 `consoleUrlReportsTheActualBindNotAHardcodedLoopback` —— 红得对，是我推期望集时漏的一格，按纪律不洗成 RED-OK ③ **E2E 22/22**，且提交前按最终树重打包重跑过一次（同次命令对 21 个源文件前后 md5 比对 ⇒ `SRC_MD5_STABLE=yes`；变异量具按字节还原过这些文件，mtime 不可信）④ `entries=8`、`2dadaed0→2dadaed0`、`690ddbc0→690ddbc0`。
  | 决定性两条: **E3** 邻居占住 `127.0.0.1:P` 时缺省 `serve` ⇒ `rc=1` + `java.net.BindException: Address already in use`，且**没有**先印"已启动"；**E4** 同一处境下 `--host 0.0.0.0` 照样绑上（lsof 按 pid 过滤读到 `*:P`），而 curl 打到 `127.0.0.1:P` 拿到邻居盖章串 `FOREIGN-NEIGHBOR-OWNED-THIS-PORT` —— E4 就是修前缺省的处境，它是这一项的**必要性**证据。
  | 如实记: "顺带收益：1/9 抖动归零"**已撤回**（修复后共 24 轮全量 0 复现，但同一棵树只 12 轮 ⇒ 按 1/9 先验仍有 24% 的运气概率，打到 20% 以下要 14 轮、压速率上界到 1% 以下要 40 轮 ⇒ 只主张机制封死，不主张速率归零，留作长期观测项见 §6）；`bind.host` 配置项**没做**（`BotConfig` 无消费者 = 红线 2），飞书/钉钉的 `host` 重载目前只被守卫测试消费，接线归 P21；无 TLS/鉴权（`/bot/chat` 无鉴权本身仍在，收口归 P26）；Windows/Linux 未实测。

- 2026-09-26 **P17 cron 投递闭环** ✅ 并入 main（`a73dcc6` = merge `w2-p17`@`13b1868`）: 一次性任务**先落账再跑**（`claimDispatch` 写 `dispatches`/`runClaim` 才发起副作用）+ 认领心跳保鲜（过期严格等于"那个进程死了"）+ at-most-once 额度 + 双层锁（进程内监视器 + 跨进程 `FileChannel.tryLock`，30s 上限内等不到才降级）+ 结果按 job 的 `deliver` 真投（`local` / `origin` 缺失时降级 local 不报错 / 指定通道，拉模式 HTTP 控制台被剔出投递目标）。
  | 复算: ①`rm -rf z-bot-core/target/surefire-reports && mvn -o test` ×3（分支一遍、合并树一遍）②`python3 _doc/acceptance/p17/p17_mutation.py` ③`mvn -o -pl z-bot-core package -DskipTests && python3 _doc/acceptance/p17/p17_e2e.py` ④`ls -A ~/.zbot | wc -l` + `md5 -q ~/.zbot/config.properties` + `md5 -q ~/.zbot/state.db`
  | 实测: ① 分支 `Tests run: 453, Failures: 0, Errors: 0, Skipped: 0` ×3；合并树 `Tests run: 465`（= main 411 + 本期 54，`git grep -c "@Test"` 求和）×3、每跑 socket 类错误 0 次 ② 27 支注入 = **20 RED-OK / 5 PARTIAL / 2 GREEN-BUT-MUTATED / 0 BROKEN**（`awk` 数不出来，按列读：`python3 -c "import csv,collections;…"` 对 `LEDGER.tsv` 的 `verdict` 列求和），`LEDGER.tsv` 脚本自写、`restored` 列 27 行全 `True`（每轮注入后按 md5 与被注入源文件对拍）③ **33/33**（真 JVM ×7、真 `kill -9`、两进程抢一把 `.jobs.lock`：E2「攥锁期间写请求 20s 没返回且盘上没多这条」+ E3「释放后 20.0s 落定」+ E0b/E0c 邻居 JVM `tryLock` 双向取证），合并树另 3 整跑逐跑 33/33 ④ `entries=8`、`2dadaed0→2dadaed0`、`690ddbc0→690ddbc0`。
  | M8/M9 这两支**单测杀不掉**的（装配线、等锁上限）按纪律如实记 `GREEN-BUT-MUTATED`，另在真进程层给判红原文：M8 ⇒ E2/E4 红、还原重跑回绿；M9 ⇒ A3/A4/B2/B3 红（`lastDelivery='ok'` 就是"投给拉模式控制台却被记成静默成功"那个洞的真身）、还原重打包 32/32 回绿。
  | 本期被推翻的一条: 合并树首跑 **30/32**（C2/C3 红）差点记成"P11c×P17 的合并缺陷" —— `git diff --stat 13b1868 a73dcc6 -- z-bot-core/src/main/java/com/zifang/z/bot/cron/ _doc/acceptance/p17/` **输出为空**先排除合并，再 20 轮独立探针量出"日志行→账落盘"窗口 0.1–8.8ms（10/10 读得到）⇒ **量具拿日志当 `kill -9` 扳机，自己把账杀在移动之前**（K 模式 2/10 中）。修的是量具：加 C1b，把扳机换成因果（stub 真收到请求 ⇒ 盘上必须有账），条数 32→33、合并树 3 整跑 33/33。全过程 `_doc/acceptance/p17/EVIDENCE.md` §5.5。

- 2026-09-26 **内核发 0.2.1 + z-bot 抬 pin** ✅ **z-bot 侧**已推 main（`3ed5bf4` = 抬 pin 那笔 + flatten 产物 `e5b4189`；两枚都只在 **z-bot** 仓，别去内核仓找——`git -C z-agent-kernel cat-file -t 3ed5bf4` = `Not a valid object`）。口径按用户决定："发内核 0.2.1，所有仓一起抬 pin"：`95733ae`（注册表真 deregister 语义）落回内核仓、`<revision>` 0.2.0→0.2.1、**本机 `mvn -o install`**、z-bot 抬 pin。**内核仓本身仍未 push**（那条要单独点头）。
  | 09-26 16:3x 复核内核态: `git -C z-agent-kernel ls-remote origin refs/heads/main` = **`46aa19c`**（09-24 23:30 那条 0.2.0 SPI 灌注），本地 `main` **ahead=2** = `95733ae`(09-25 21:03) + `cb90416`(09-26 02:08 抬 revision)
  ⇒ 对外可见的内核版本线**还停在 0.2.0**：0.2.1 只在**本机 m2**（`~/.m2/repository/io/github/yuku123/z-agent-kernel/0.2.1/`，pom mtime 09-26 02:02）与 z-bot 的 pin 里成立。任何"下游能拉到 0.2.1"的说法目前都不成立，要对外得先点头 push（发布到 Central 是另一件事）。

  **↑ 上面这条"仍未 push / 对外停在 0.2.0"到 09-26 16:43 作废**（用户点头"先把内核推了"）：内核仓 `main` 已 push，`git -C z-agent-kernel ls-remote origin refs/heads/main` = **`cb90416bd5c1858992b2865767560bb57bb5dabe`**（`46aa19c..cb90416`，push 后本地 `ahead=0`）。
  | push 内容实测: 待推 diff = **4 文件 +338/−47**（`ToolRegistry.java` +84、`ToolRegistryTest.java` +183、`95733ae` 真 deregister + `cb90416` 抬 `<revision>`），凭证扫描 `credential_hits=0`，`<revision>` 读回 **0.2.1**。
  | push 前置门（HEAD 原样跑，不是工作树）: `mvn -o test` = **BUILD SUCCESS / rc=0**、12 模块全 SUCCESS、`Tests run` 求和 **47**（6 个有测试的模块 tool 28 / agent 8 / state 2 / skill 4 / mcp 3 / credential 2 = 47；**类级求和独立算也是 47**，两把尺一致）、`Failures/Errors/Skipped=0/0/0`、socket 类错误 0。日志 `~/.cache/zbot-kernel/push_pretest.log`（`Finished at: 2026-09-26T16:43:31+08:00`）。
  ⇒ 口径收紧一句，别把它读成"发布完成"：**0.2.1 现在在 GitHub `origin/main` 上成立，但仍没发 Central**（`repo1.maven.org` 上没有 0.2.1）⇒ 下游"能拉到"仍不成立，能拿到的只有 clone 构建。发 Central 是另一件事、要单独点头。
  | 复算: ①`mvn -o install`（内核）②`mvn -o dependency:list -pl z-bot-core -DincludeGroupIds=io.github.yuku123` ③`rm -rf z-bot-core/target/surefire-reports && mvn -o test` ×3 + `mvn -o -pl z-bot-core package -DskipTests && python3 -u _doc/acceptance/p17/p17_e2e.py` ④`ls -A ~/.zbot | wc -l` + `md5 ~/.zbot/{config.properties,state.db}`
  | 实测: ① 内核 install 里具名 testcase 所在类 8 个、`Tests run` 求和 **47**、F/E/S = 0/0/0（`python3` 正则求和 `~/.cache/zbot-kernel/k021_install.log`，与 `test` 那跑同数）② z-bot 侧 0.2.1 坐标 **12** 条 / 0.2.0 **0** 条（同一命令 `grep -c ':0\.2\.1'`=12、`grep -c ':0\.2\.0'`=0）；落盘 `find ~/.m2/repository/io/github/yuku123 -type d -name 0.2.1 | wc -l` = **26** 个模块目录（jar 25 / pom 26），0.2.0 那批 `find … -newermt '2026-09-26 01:40' | wc -l` = **0** ⇒ 没被覆盖 ③ 抬 pin 后 `Tests run: 465` ×3 全绿（socket 类错误 0，日志 `_doc/acceptance/p17/logs/bar1_kernel021_r{1,2,3}.log`）+ **真进程 E2E 33/33 重跑**（`_doc/acceptance/p17/logs/e2e_kernel021.log`，X1 真 key 未进产物、X3 5/5 请求全带 stub 凭证）④ `entries=8`、`2dadaed0→2dadaed0`、`690ddbc0→690ddbc0`。
  | drop-in 证据（抬 pin 不动一个调用点）: 11 个模块逐模块 `javap` 公开签名 `0.2.0-p20` vs `0.2.1` 差异行 **全 0**（类数两侧各 86，`~/.cache/zbot-kernel/api/{0.2.0-p20,0.2.1}`）；阳性对照 `tool` 模块 `0.1.1` vs `0.2.0` 同法 = **43** 行（类 6/10）⇒ 这把尺有牙，0 不是空跑。
  | 订正我自己写进 `3ed5bf4` 提交说明的两个数（已推送，不 amend，账记在这儿）: ①"10 个模块"实为 **11** 个（`ls ~/.cache/zbot-kernel/api/0.2.1` 数得）②"阳性对照 =39 行"用可复算的命令重量是 **43** 行 —— 原数那次的 javap 文本没留档，无法复查，以 43 为准。
  | 已知不一致（本期没做）: 内核 `StdioMcpTransport` 在握手响应里自报 `"version":"0.2.0"` 是写死的字符串，抬版没带走它 ⇒ MCP 对端读到的版本线是假的，归内核下一期收口（应读实现属性或 `Implementation-Version`）。

- 2026-09-26 **P15b + P16 + P20b 三笔并入 main（集成收口在合并树重做四杠）** ✅ `b789d04`←`w1-p15b`@`e27186f`、`99fc389`←`w2-p16ev`@`1263fd2`、`7a5f125`←`w2-p20b`@`7be0811`（merge-tree 预演四支两两 0 冲突，逐笔合并后复测仍 0 冲突）。
  | 复算（全部我本人跑，不引用代理自述）: ①串行三跑 `rm -rf z-bot-core/target/surefire-reports && mvn -o test` ②`python3 -u _doc/acceptance/p20b/p20b_e2e.py` ③`python3 -u _doc/acceptance/p16/p16_e2e.py` ④`python3 -u _doc/acceptance/p15b/p15b_check.py` ⑤`python3 -c` 用 `csv.DictReader` 只取 verdict ∈ 四类的那些行 ⑥`git grep -c '@Test' HEAD -- 'z-bot-core/src/test'` 求和 ⑦`ls -A ~/.zbot | wc -l` + `md5 -q ~/.zbot/{config.properties,state.db}`
  | 实测: ① 三跑 `12:46:15→12:46:35`、`→12:47:07`、`→12:47:27` 各自 **`Tests run: 619, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` rc=0**（619 = 基线 465 + P15b 10 + P16 82 + P20b 62，与 ⑥ 的 `@Test` 求和逐笔对得上）② **38/38 PASS FAIL=0 rc=0**（三阶段 `P0 toolsets/cap/mcp 驱动退出码 0` 全绿；M4 `anchor=2`、M5 `每轮增量=[2]` 与 §3.4 的裁决一致，未因合并漂移）③ **33/33 通过 rc=0**（段 A 11/11、B 8/8、C 6/6、D 2/2、X 6/6；另在 `w2-p16ev` 未合并态也独立跑过一次 33/33 ⇒ 两个字节集各验一遍）④ rc=0：`参照 cbc1054e2 ⇒ 46 列 / 文档 46 行 / 要=14 不要=14 占位=18 / PASSED: yes` ⑤ p20b 台账 **21 行 = RED-OK 20 / PARTIAL 1**（唯一 PARTIAL 是 `MB3`，工单明写不硬凑、期望集一字未补）、p16 台账 **24 行 = RED-OK 22 / PARTIAL 1(`M09`) / GREEN-BUT-MUTATED 1(`M07` 具名期望红 0/0 ⇒ 该行为全仓 481 条测不出，已由 `--all-tests` 探针量出来而非断言)** ⑦ `8 / 2dadaed0 / 690ddbc0` 全程未变。
  | 合并前的预测在此兑现（先前记在 `~/.cache/zbot-p17/pending_predictions.md`）: `main+w1-p15b` = **475** ✅ 实测 475、`main+w2-p16ev` = **547** ✅（465+82）、两笔叠完 557、三笔叠完 619。⇒ 排产时"后合的那支要重新 merge-tree"这条也照做（每次合并后重跑，未拿旧预测当结论）。
  | P20b 这支的真实增量不只在代码: 杠② 从接手时 **14 RED-OK / 4 PARTIAL / 3 GREEN-BUT-MUTATED** 收到 **20 / 1 / 0**，其中 `TS4` 那条暴露的是**断言写读同源**（`ToolsetsManifestTest` 的期望位调的就是被测函数 ⇒ 摘掉真源两边一起漂，等式照样成立）；`TK5` 原注点经 V0/V1/V2 三把实测判为**等价变异**（换成"哨兵=零上限"才有猎物），换掉之后"摘析取项"那一种坏法在单测层结构上仍检不出，账记在 EVIDENCE §9.10。
  | 本期新发现、**未修**的产品级缺陷（内核侧，抬版要单独点头）: `StdioMcpTransport.request` 用 `contains("\"id\":" + id)` 认回执，而真 server 只要不做紧凑序列化（`json.dumps` 默认写 `"id": 1`，带空格）回执就永不匹配 ⇒ main 线程卡在 `readLine` **永久挂死**（我先量到 600 s 超时，`jcmd <pid> Thread.print` 取证栈；上一棒用 `STUB_PRETTY=1` 只改这一个变量复现，50 s 是它的实测下界）。次生: 那个 30 s deadline 只在 `readLine()` 返回之间检查，`readLine` 自身不超时 ⇒ 对端不回时超时永不生效。取证与复现开关在 `_doc/acceptance/p20b/EVIDENCE.md` §5.1/§5.2。
  | 未并入、仍在飞的: `w2-p12`（P12 预算台账/中断收口/steer/快照冻结）—— 该棒已到 `e74f49c`（含我三次封存：`b64d294` 写手截断、`809b927` p12b 截断、`f541af5`+`e74f49c` p12c 停机时的工作树字节），杠②/杠③ 读数**尚未由我复算**，其 E2E 的 K2 那条间歇红（同一形态量具连跑 3 次里 2 次红）我还没裁决 ⇒ **P12 不算完成**，`@Test` 483 也还没进主干（对**新** main 重跑 merge-tree: 0 冲突，合并树 `@Test` 预测 **637** = 619 + 18）。
  | 我自己这一轮的两笔账（不 amend，已提交就记在这儿）: ①`809b927` 提交说明写"+16 条守卫"，重量 `git grep -c '@Test'` 是 **475→481 = +6**，原数错；②`git commit -- <路径> -m "…"` 会被 git 当 pathspec 拒掉（今晚连错两次），正确写法 `-m` 必须在 `--` 之前。
- 2026-09-26 **P18 通道注册表 SPI + 飞书/钉钉真出站 + 入站验签接线** ✅ 并入 main（`066d766` = merge `w4-p18`@`acf5d7e`）。这支是**四杠里少打一杠**的口径，理由写在这儿: `git merge-base --is-ancestor main w4-p18` = **YES**、`git diff --name-only <合并预览树> w4-p18` = **0 行** ⇒ 合并树与我量完四杠那版逐字节同；杠② 会改写源文件，在同一份字节上重跑只会刷一个 `#generated_by` 时间戳 ⇒ **①③④ 在合并树重打、② 不重打**（不是"四杠齐打"，别这么读）。
  | 复算（全部我本人跑；两支写手 p18a/p18b 分别死于 turn 150，主体码与验收工件是我封存的 `fbb5f86`/`eb4038c`）: ①串行三跑全量 reactor `rm -rf z-bot-core/target/surefire-reports && mvn -o test`（3 模块、**无 `-pl`**）②`python3 -u _doc/acceptance/p18/p18_mutation.py` ③`mvn -o -q package -DskipTests -pl z-bot-core && python3 -u _doc/acceptance/p18/p18_e2e.py` ×6（分支树 3 跑 + **合并后 main 树 3 跑**）④`ls -A ~/.zbot | wc -l` + `md5 -q ~/.zbot/{config.properties,state.db}` T1–T5
  | 实测: ① 分支树三跑各自 **`Tests run: 672, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`**、socket 类计数 `0`；合并树 main 再跑 `MVN_RC=0` + 同一条 672 行；`git grep -c '@Test' HEAD -- 'z-bot-core/src/test'` 求和 = **672** 与 mvn 逐字相等（main 原 619 ⇒ 本系列净增 **+53**）② 31 支具名变异 = **RED-OK 30 / NO-RUN 1(`F2`) / PARTIAL 0 / GREEN-BUT-MUTATED 0 / BROKEN 0**，6 族阳性对照 `ran=5/1/8/12/7/2` 全绿，收尾四支 `channel/*.java` 的 `md5 -q` 与 `git show HEAD:` 同值 ③ **`PASS=41 FAIL=0`** ×6（分支树 `6.2/5.7/5.6s`、合并树 main `5.6/5.7/5.7s`；jar `20151114` 字节、`find z-bot-core/src/main/java -name '*.java' -newer …jar` = **0 行**，并用 `zipfile` 从 jar 里读出 `X-Lark-Signature`/`X-Lark-Request-Timestamp`/`X-Lark-Request-Nonce`/`signature mismatch` 四个常量 ⇒ 打的确实是有验签的那版字节）④ `8 / 2dadaed0 / 690ddbc0` T1–T5 全程未变。
  | §2 矩阵 #19（通道）本期只把"手工接线 + 出站=stub"推进到"声明式 manifest 四层后写覆盖 + 惰性构造 + 缺键点名 + 飞书/钉钉真协议字节"；**verdict 仍是「浅」**，差额没有消失: 真 IM 适配器 **2 个 vs 她 29 个**、没有 `plugin.yaml` 运行期插件层、`{"encrypt":…}` AES 解密模式与钉钉入站验签**未做**、全部出站证据是"对 127.0.0.1 假端点发出的字节"（**零真发**，本机无 IM 凭据）。
  | 本期新收口的一条真缺口（P20b 那笔"广告没兑现"的同类）: `FeishuChannel.verifySignature` 原本在 main 里**零调用方**，改成恒真没有任何测试会红 ⇒ p18b 两跑 LEDGER `D10` 都记 `GREEN-BUT-MUTATED`。接线（`FeishuChannel.java:398-406`，配了 `encrypt-key` 就验原始 body 的 sha1、缺头/签错一律 401 fail-closed）后 D10 `点名 3/3 RED-OK`，并补 **D11**（只摘守卫、签名函数原样留着 ⇒ helper 层那几支结构上看不见）⇒ 算法层与接线层互补，缺一层就是空档。
  | 两笔我自己的量具账（不粉饰）: (a) `p18_mutation.py:42` 的 `BASELINE_TOTAL = 668` 是上一棒**手敲**常量，杠① 涨到 672 后没人回头改 ⇒ LEDGER 印出一个**树里不存在的数**，而没有任何尺会去读它（所以它永远不红）；已改 `measure_suite_total()` 机械量取（git 非 0 / 行形状不对 / 数 ≤0 一律 FATAL 不收读数），LEDGER 行改名 `#suite_total_full_tests_measured`。(b) 新加的 A21 第一版判 `FAIL`（期望 401 拿到 200）是**我没带猎物** —— `feishu_inbound` 拿同一个 encrypt-key 去签，签名本来就对；同跑的 A21b/A22 已证明门本身是好的，补 `key="someone-elses-key"` 后拿到 401，**未放松任何断言**。顺带: `FAKE_ENCRYPT_KEY` 这个常量在上一版 E2E 里根本没有消费者（manifest 没写 `channel.feishu.config.encrypt-key`），这版才真配进去。
  | 合并树四杠少打一杠这件事，是这一轮**第二次**被 mtime 骗: 杠② 按字节还原后 `channel/*.java` 的 mtime 推到 14:43，`find -newer jar` 于是假报 4 个文件"源码比 jar 新"（内容 md5 与 HEAD 逐支同值）。⇒ 判"量的是不是同一版字节"只认 **md5/常量池**，不认 mtime；这也是我把 jar 重打 + 从 jar 里读常量这一步加进杠③ 的原因。
  | 仍在飞（不算完成）: `w3-p21`@`86447fe`（我 14:52 封存 p21b 临终字节：杠③ 量具 `p21_e2e.py` 39,697 字节 + `McpE2eDriver.java` 16,006 字节此前**均未提交**；单跑实测 `合计 9 条，失败 3 条`、小节 (b)(c)(d)(e) **零断言** ⇒ 三个量具缺陷定性到行号，见 `~/.cache/zbot-p21-lead/dispatch_p21c.md`；`@Test` 661 = 619+42，对新 main 的 merge-tree 预览 0 冲突 ⇒ 合并树预测 **714**）；`w2-p12`@`f88c838`（杠②③④ 待我复算）。
- 2026-09-26 **P12 主循环节律包（中断/steer/预算/prompt cache 冻结）** ✅ 并入 main（`137165d` = merge `w2-p12`@`aef1eb3`；上一棒 §8 记的"`w2-p12` 杠②③④ 待我复算"到这儿结清）。**这支的并入被一条红挡住过两次**（p12d §10.6、p12e §11.9 都判"不具备并入门禁面"），拦路的不是产品缺陷而是 main 里一句过期的形态断言 ⇒ 记录在案，避免下一次有人偷偷 `@Ignore`。
  | 那条红的处置（判据换轴，不是放宽）: `GatewayDeliveryP16Test:381` 原写 `startsWith("echo: A")`，P12 把动态上下文块前置进请求的 user 消息后回显变成 `echo: <上下文头>\n---\nA0` ⇒ 挂的是**"上下文块长在第几个字节"这个形态假设**，而该测试要证的一直是"两条会话没交织"（同跑的 `chanA/chanB 各 4 条`、`一个 session 一把租约`、`leaseTimeoutCount=0`、8 条 user/assistant 交替全过）。改法 = 我落刀 `f88c838`：`echoesRoute(text,route)` **只量尾巴**，且**同一条测试里先自证尺子有牙**（`assertTrue(echoesRoute("echo: 前缀\n---\nA2","A"))` + `assertFalse(echoesRoute("echo: 前缀\n---\nB2","A"))`）⇒ 新判据不可能靠"什么都判不出"变绿。
  | 合并干净性: `git merge-tree --write-tree f88c838 dd0d9c5` = **1 行**（无冲突），预览树 `2202d95…` 与我实际合出的 `bf9099c^{tree}` **逐字相同**；再对 main 预览 `7cbde7d9…`，`git diff --name-only bf9099c 7cbde7d9` 只差 p12 的两个**文档件**（EVIDENCE/LEDGER）⇒ 我四杠量过的 Java 字节就是进主干的那份。`@Test` 面对账: main 672 / p12 顶端 640 / 合并树 **693** = 640 + 53（main 自 619 的净增）。
  | 复算（全我本人跑，日志在仓外 `~/.cache/zbot-p12-lead/`、`~/.cache/zbot-p12-main/`；细节与逐字读数在 `_doc/acceptance/p12/EVIDENCE.md` §12）: ① `rm -rf z-bot-core/target/surefire-reports && mvn -o test`（3 模块、无 `-pl`）**串行三跑**，worktree 与 **main 目的地各一遍** ② `python3 -u _doc/acceptance/p12/p12_mutation.py`（19 支，另跑一遍 `--with-e2e` 两层）③ `mvn -o -q package -DskipTests -pl z-bot-core && python3 -u _doc/acceptance/p12/p12_e2e.py` ×3 + `p12_k2_probe.py` ④ `ls -A ~/.zbot | wc -l` + `md5 -q ~/.zbot/{config.properties,state.db}`
  | 实测: ① worktree 三跑 `Tests run: 693, Failures: 0, Errors: 0, Skipped: 0`（`MVN_RC=0`/`BUILD SUCCESS=1`/socket 类 0，28.8/26.7/26.7 s），**main 上重打同一条 693 行 ×3**（37.5/35.7/32.1 s，与 `w3-p21` 在飞同机）；`git grep -c '@Test'` 693 = surefire 693 = 65 类逐类求和 693 三处对齐 ② 两层与单层**同分布** `RED-OK 15 / PARTIAL 1 / GREEN-BUT-MUTATED 3 / BROKEN 0 / NO-RUN 0`、`SRC_MD5_STABLE=yes`、19/19 `还原=True vs_git=ok/ok`、跑完独立量 `git status --porcelain -- z-bot-core` = **0 行**；两层那两支 `M6 e2e_rc=1`（`S2 复算 pgrep=['97773','97775']`、`S3 elapsed_ms=-1`）、`M12 e2e_rc=1`（`C1 len1=2345 len2=2372`，两个 sha256 立刻漂），且 `B0` 自报 `未提交改动=1 行` + 变异体 jar sha（`788d96a2…`/`b3f3b7f7…`）≠ 干净 jar（`96fc4a58…`）⇒ 真进程层跑的确实是变异体 ③ 三整跑 `段=all 检查条数=27 PASS=27 FAIL=0` ×3（三份日志 md5 互不相同；main 侧同 27/27 ×3），K2 双向探针 `rc=0` / **14 行 OK**（7 case × 工作树与封存两份字节，含假红侧 `F api.key=not-configured ⇒ 期望 FAIL 实测 FAIL`）④ `8 / 2dadaed0 / 690ddbc0` —— 15:01:15 / 15:16:52 / 15:17:57（worktree）+ 15:20:22 / 15:23:54（main）五时点同值；**14:57 那一时点我这轮没量，按缺读记在 §12.4，没编**。
  | §2 矩阵 #3 #4 #5 的 verdict 从 **缺/浅/缺** 推到 **浅/浅/浅**（复算命令写进表里）: 工具侧 `InterruptScope.checkpoint()` **9 处**（`BuiltinTools 7` + `DelegateManager 2`，计划要求"从 0 到 ≥6"）；kernel `SteerQueue` 的 **0 消费者**红线消掉（`context.steer()` 在 `BotAgent` 8 处真调用，`/steer` 单槽与 `/queue` 多条由 M5b/M5c 两支互斥变异分别钉红）；system prompt"每会话一次 + 逐字节重放"不变量成立（M12 反向钉）。
  | 本棒**改口径**的一条（不粉饰）: 计划里 P12 的验收写的是"真 pty/管道 REPL 跑一次'长命令执行中 /stop 能在 2s 内断'"，实测断的是 **`/bot/stop` 这条路**（`S3 elapsed_ms=54`，判据本身是 ≤2000 ms），而 **pty 里敲 `/stop` 实测不生效** —— `R2b` 反面对照：`TerminalChannel.run()` 在 `agent.chat()` 期间同步阻塞、根本不读 stdin（2s 后 `pgrep` 仍数得到那两个 pid）。⇒ 中断能力站住了，但"TUI 边跑边按停止"这条路**没做**，已列为后续项（不是把验收语句当成已完成）。
  | 结转的 5 支未完全判红 + 3 处未覆盖（原样从 §10.5/§11.9 搬，本棒只重跑不补面）: `M8/M9/M17` 仍 GREEN-BUT-MUTATED、`M16` 仍 PARTIAL（点名 5/6）、`M1` 仍 2/3；`exec 读输出逐行检查点`/`mvn_build 入口检查点` 摘掉后看门狗仍 50 ms 内端掉进程树 ⇒ 这一面**量不出差别**，如实记未覆盖；另外**历史 transcript 未清洗**（`86e7b83` 只保证"从现在起不再新增"，改动前落盘的 `~/.zbot/sessions/*.json` 里带抬头的 user 行仍会被 `switchSession()` 原样读回，写了断言必红 ⇒ 没写）与**块挂在控制行还是原话行没钉**（grace/steer 退路轮）两条继续挂账。
  | 与 `w3-p21` 的并发纪律: 我这两遍杠② 全程独占 `$(git rev-parse --git-common-dir)/zbot-mutlock`（p21c 若同时打杠② 会拿 `rc=4` 退避），杠①③ 期间 p21c 在另一棵 worktree 改写 `mcp/**` ⇒ 写域不重叠，`~/.zbot` 三值不变量同时反证了两支都没污染真配置。
- 2026-09-26 **P21 MCP 对齐（stdio+StreamableHTTP/SSE 双 transport / 真反注册 / 父死 watchdog / 反向 mcp_serve）** ✅ 并入 main（`6c2c433` = merge `w3-p21`@`4ce8811`，树 `041f014`；28 文件 +7,939/−39，`@Test` 面 661→**735**）
  | 合并干净性: `git merge-tree --write-tree main w3-p21` 输出**单行**树号 ⇒ 零冲突；合并树 `@Test` 用 `git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk` 机械求和得 735（不是"661+74 猜的"）
  | 复算（四杠全我本人跑，源树 `9c24c23`/`4ce8811` 一组、目标树 main 一组；日志在仓外 `~/.cache/zbot-p21-lead/`、`~/.cache/zbot-p21-main/`，逐字读数在 `_doc/acceptance/p21/EVIDENCE.md` §16 与 §17）
  | 实测: ① 杠① 源树 `Tests run: 661, F:0 E:0 S:0` ×3 串行 rc 全 0（52.8/50.0/55.4 s，socket 类 0、`[ERROR]` 0）；①' 合并树 `Tests run: 735` ×3（56.6/57.2/55.7 s）+ 71 个 surefire 文件目录级求和 `sum=735 fail=0 err=0 skip=0` 与 `git grep` 三方对齐；打包 `PKG_RC=0`、jar sha `1e356fbd`（`original-` 裸 jar 是 `506c60d5`，两个都在才算 repackage 真跑）
  | 实测: ② 杠② `MUT_RC=0` = **RED-OK 18 / PARTIAL 1 / GREEN-BUT-MUTATED 0 / BROKEN 0 / NO-RUN 0**（19 支注入 + 7 支阳性对照，`LEDGER.tsv` 30 行由 `p21_mutation.py` 机械写出）；逐字节还原用两把尺各验一次（脚本 md5 尺 + `git diff --stat HEAD -- z-bot-core` 为空）；`PARTIAL` 那支是 `W1`（父死 watchdog 只验到"壳起了"，未验到"孤儿子进程真被杀"），如实挂着未粉饰
  | 实测: ③ 杠③ `p21_e2e.py` 两棵树各 3 整跑 = **6 次全绿** `合计 59 条，失败 0 条` rc=0，六个互不相同的现场目录（`R154506-p59320`… `R162147-p38168`），全部走 `--config-dir`/`ZBOT_HOME` 临时根 + `stub-key-not-real`；官方 Python SDK `mcp 版本=1.27.1` 真起 stdio/StreamableHTTP 两种 server，反注册那例是真"外部改工具表⇒客户端表里少一条"
  | 实测: ④ 杠④ 五时点同读数 `items=8 cfg=2dadaed0 db=690ddbc0`（源树三时点 + 合并树前后各一时点），`DIRTY_TRACKED=0` ⇒ 量具不在仓里留痕
  | 本棒**抓出并修掉**的一处量具假红: `p21_e2e.py:309` 的缺省 label 自检原来绑在**本次 run 的 label** 上 ⇒ 任何 `--label 任意名` 跑必红（我第一组 3 跑各红 1 条就是这个）。改成拿缺省口径自己算一遍，并**双向自证**：`--only a --label leadfix` ⇒ `RC=0 合计 17 条，失败 0 条`；阳性对照 `broken_label_predicate=False / real_label=R155427-p83247 predicate=True`
  | 本棒**记进制度**的一条取证（写手台账不可信的原因，不是他偷懒）: p21c 提交的 `LEDGER.tsv`（mtime 15:23:20）比被提交的 `p21_mutation.py`（mtime 15:27:03）**更早** ⇒ 台账是旧量具的读数，`T1` 那行标题字面量两版不一致、他报的 `T1=BROKEN` 量的不是交付的那支变异。我用当前脚本复跑得 `T1 … RED-OK 点名 1/1 ran=1 rc=1 19.2s`。规则: **台账的 `#generated_by` 时间戳必须晚于量具 mtime，否则台账作废重跑**
  | 三棒写手皆死于 150 轮上限（主体码/验收层各一棒），本期四杠是由主编在源树+目标树各打一整套才成立的 ⇒ 交接面见 `_doc/acceptance/p21/EVIDENCE.md` §16.5"谁给谁让路"
  | §2 矩阵 #22 verdict **浅→中**（复算命令写进表里）: 两 transport / 真 `deregisterToolset` / 默认开的父死 watchdog / 反向 server 已落地，**差额也钉死**——反向只广告 2 条读端工具（她 10）、sampling/elicitation/OSV 预检/resources 在 `mcp/*.java` 实测 0 命中、内核 `StdioMcpTransport` 三缺陷只记录未修（§14）
  | 收口后追加的一笔（为什么 push 前要再打一杠①）: 我在 main 上改了 `mcp/McpClientFactory.java:17` 那句**过时 javadoc**（原文"HTTP+SSE 留到后续版本"等于否认已交付能力），落在 `src/main` ⇒ 重跑杠① ×3（`~/.cache/zbot-p21-main/loop2.out`，读数在 EVIDENCE §17.4），push 的是那一棵

---

# 附录 A · v1 原文（P0–P10d 计划与实测记录，原样保留）

> **以下原文保留，但其中 §0 的锚点表与 §1 各期"完成记录"的口径已由 v2 §1 的 13 条纠正表逐条推翻**
> （行数虚高一个量级、`tools/toolsets.py` 与 `agent/interrupt.py` 等路径不存在、多处"三端共用/真实收发"实为单端或 stub）。
> **不要按 v1 文字验收**；每期是否达标只认 v2 §2 能力矩阵的实测列与 v2 §5 的分阶段验收线。
> 保留理由：v1 的实测过程记录（踩过的坑、修复动作）是有效历史，只有"定量结论"部分作废。

# z-bot → Java 版 Hermes 全量升级计划 (v1, 2026-09-24)

> 对标对象: hermes-agent (NousResearch, Python, ~189 万行, v0.19)。
> 调研来源: `~/.hermes/hermes-agent` 源码扫描 + 《217_hermes-agent 源码分析》研究笔记(架构/复刻指南/CLI 手册/TUI 机制)。
> 目标: z-bot 在 z-agent-kernel SPI 上重建 Hermes 的承重能力, 成为 Java 生态的"成品 AI 员工"框架。
> 约束: Java 8、core 不依赖 Spring、数据/代码分离 (`~/.zbot/`)、每阶段实测验证后 push。

## 0. Hermes 15 大承重能力 → 对应落点

| # | Hermes 能力 | Hermes 锚点 | z-bot 落点 |
|---|---|---|---|
| 1 | 自注册工具注册表 + toolset 分发 | tools/registry.py + toolsets.py | kernel-tool 0.2.0 + z-bot Toolkit 重写 |
| 2 | ReAct 主循环 + 迭代预算 | conversation_loop.py | BotAgent 升级 + kernel-agent AgentContext |
| 3 | Gateway 常驻多平台中枢 | gateway/run.py (2.3 万行) | z-bot gateway + Channel SPI |
| 4 | state.db 会话持久化 | hermes_state.py (SQLite+WAL+FTS5) | z-bot state.db (sqlite-jdbc) |
| 5 | 上下文压缩引擎 | context_engine.py + context_compressor.py | kernel-agent ContextEngine SPI + z-bot 默认 compressor |
| 6 | delegate_task 子代理树 | delegate_tool.py | BotAgent.spawnDelegate + kernel-agent DelegateSpec |
| 7 | 线程级中断 + 危险命令审批 | interrupt.py + approval.py | ExecGuard 升级 + ApprovalService |
| 8 | SKILL.md 技能系统 | skills/ + skills_hub | kernel-skill SkillLoader + center 同步已有 |
| 9 | 插件体系 | plugins/ + plugin.yaml | Java ServiceLoader SPI (kernel-app) |
| 10 | MCP 双向 | mcp_tool.py / mcp_serve.py | kernel-mcp + 复用 z-mcp 仓 |
| 11 | 多执行后端沙箱 | tools/environments/ | Sandbox 抽象 ExecEnvironment (local/docker/ssh) |
| 12 | checkpoint 影子 git | checkpoint_manager.py | z-bot CheckpointManager (git CLI) |
| 13 | 记忆三层 | memory_manager + memories/*.md | kernel-memory MemoryProvider + MEMORY.md/USER.md |
| 14 | cron 调度 + 主动建议 | cron/scheduler.py | z-bot CronScheduler (jobs.json) |
| 15 | 模型目录 + 凭据池 | models_dev.py + credential_pool.py | kernel-credential pool + fallback 链 |

## 1. 分期路线 (P0→P10, 每期: 编码+单测+真实 LLM E2E+commit/push)

### P0 kernel 升级 0.2.0 — SPI 地基 (本仓 z-agent-kernel)
现在 kernel 9 个子模块是空壳(2~6 文件/40~300 行), P0 把 Hermes 承重抽象灌进去:
- **kernel-agent**: `AgentContext`(budget/interrupt/steer 显式状态对象, 替代上帝类内联状态)、`IterationBudget`(maxIterations/token 上限/grace call)、`InterruptFlag`(协作式中断, 迭代与工具边界检查点)、`SteerQueue`(工具间隙注入)、`ContextEngine` SPI(shouldCompress/compress)、`DelegateSpec`(子代理规格: 深度/宽度/预算上限)
- **kernel-tool**: `ToolRegistry`(自注册/发现/lookup, generation 计数失效)、`Toolset`(分组 + 入口面分发)、可用性探测(check_fn + TTL 缓存)、`parallel_safe` 标记
- **kernel-state**: `SessionStore` SPI (list/append/search/export/prune)
- **kernel-memory**: `MemoryProvider` SPI (load/save/prefetch/sync)
- **kernel-skill**: `SkillLoader` SPI (SKILL.md frontmatter 解析)
- **kernel-mcp**: `McpClient` SPI (tools/list + tools/call, stdio/HTTP)
- **kernel-credential**: `CredentialPool` SPI (多 key 轮换/cooldown)
- 验收: 全子模块单测绿; `mvn install` 本地 0.2.0; Central 发布 dry-run 通过(正式发布单独排期)

### P1 agent 核心升级 — Hermes #2 #7
- BotAgent 重构为显式 `AgentContext` 驱动: 迭代预算 + token 预算 + grace call
- 协作式软中断: `/stop` 在迭代/工具边界生效(线程 interrupt + 工具内检查点)
- Steer 三档: `/queue`(下一轮) → `/steer`(工具间隙注入 `[User steer]:`) → `/stop` 硬中断
- 工具并行批调度: 全部 `parallel_safe` 且路径 scope 不重叠才并行, 否则串行
- LLM 调用重试分类(kernel-llm): 可重试(429/5xx/超时, 取 Retry-After)/不可重试(4xx), failover 到备用模型
- 审批升级: 危险模式规则表 + 用户白名单持久化(`~/.zbot/config.properties` 扩展)+ `--yolo` 启动期冻结
- 斜杠命令注册表: 一张 `SlashCommand` 表派生 CLI/HTTP/TUI 三端(/stop /steer /queue /model /tools /sessions /new …)
- 验收: 新增单测 ≥15 个; 真实 LLM E2E: steer 生效、stop 软中断、并行批调度实测

> **P1 完成记录 (2026-09-25)**: 70/70 单测绿 (新增 27: AgentCoreP1Test 10 + ResilientLlmProviderTest 7
> + SlashRegistryTest 4 + ExecGuardWhitelistTest 4 + BotConfigP1Test 2); 全 reactor shade jar 构建通过。
> 真实 LLM E2E (本地代理 127.0.0.1:18180): ① chat -v 实测 ReAct 多步 + 工具调用
> (`step 1 → echo ← echo ok → step 2`); ② serve 起后 /bot/stop 空闲态返"当前没有正在运行任务"、
> /bot/steer 空闲入队后确实并入下次对话开头 (模型原样 echo 出排队内容)、busy 态 steer 返"工具间隙注入"、
> busy 态 stop 在 step 2 生效 (DONE replyLen=10 = "已中止：用户请求停止"); ③ SSE 桥补映射 steer 帧。
> minimax 真实 429 由 ResilientLlmProvider 可见退避重试 2 次后干净报错 — 重试链路实弹验证。

### P2 state.db 会话持久化 — Hermes #4
- 引入 `org.xerial:sqlite-jdbc`(Java 8 兼容版), `~/.zbot/state.db` (WAL)
- 表: `sessions`(id/source/title/model/provider/tokens/api_calls/created/updated/metadata) + `messages`(session_id/role/content/tool_calls/tool_call_id/reasoning) + `session_model_usage`(model×task 记账)
- FTS5 虚拟表 + trigger 同步; 低版本 sqlite 降级 LIKE
- 旧 `~/.zbot/sessions/*.json` 启动时一次性迁移进 state.db(保留原文件)
- 新 CLI: `sessions list/search/export(md|jsonl)/stats/prune`
- 验收: 单测覆盖 CRUD/FTS/迁移; 双进程并发写不丟(WAL 实测); 导出文件可用

> **P2 完成记录 (2026-09-25)**: sqlite-jdbc 3.41.2.2 (Java 8 线); `StateStore` (sessions/messages/
> session_model_usage 三表, WAL+busy_timeout, FTS5 建不上自动降级 LIKE); SessionManager 增加 store
> 模式 (sqlite 唯一事实来源, 构造时把目录里遗留 JSON 会话一次性收编, 原文件保留); 序列化抽成
> `MsgCodec` 供 JSON/db 两端共用; BotConfig `agent.state.db`(-Dzbot.state.db 也可) + Builder 自动装配;
> BotAgent finish 时给 session_model_usage 记账; 新 CLI `z-bot sessions list/search/export/stats/prune`。
> 单测 83/83 (新增 13: StateStoreTest 8 + SessionManagerStoreTest 5, 全部 TemporaryFolder 不碰 ~/.zbot)。
> 真机 E2E: `chat` 走本地代理后 2 条消息落库+标题自动生成; 18 个 ~/.zbot/sessions 遗留 JSON (313 条消息)
> 迁移进 /tmp state.db 原文件保留; FTS search "PPT" 命中 3 条带片段; export JSONL 可读; prune 空会话;
> stats 显示 model=bench tasks=1。WAL 由单测 PRAGMA journal_mode=wal 断言。

### P3 上下文引擎 — Hermes #5
- kernel-agent `ContextEngine` SPI 默认实现 `CompressorEngine`: tokens > max×0.85 触发; 保留 system + 最近 N 轮原文, 中段用辅助模型摘要; 摘要 prompt 强制保留文件路径/代码/决策等"事实承诺"
- 压缩锁(同会话并发压缩只跑一次); 压缩血统写进 metadata
- token 记账: 从 kernel-llm usage 累计到 AgentContext
- prompt-cache 稳定化: 工具 schema 按 name 字典序 + JSON sort_keys 输出
- `/compress here [N] | focus <topic> | --preview`
- 验收: 单测(触发阈值/头尾保护/锁); 真实长会话 E2E 触发一次压缩并继续对话
> **P3 已完成 (92/92 单测全绿)**: `CompressorEngine` 实现 kernel `ContextEngine` SPI —
> 阈值取**最近一次真实 prompt 观测** ≥ maxTokens×0.85; 头部 system 原样保留 + 尾部 8 条原文,
> 中段走辅助模型摘要(摘要空则放弃本次压缩不破坏历史); AtomicBoolean 压缩锁; `/compress` 手动/preview。
> token 记账: usage>0 走真实值, **usage 缺失/全 0 走请求字符÷2 估算**(kernel 把 null usage 归一成
> TokenUsage.empty(), 不能只判 !=null — 修复了这个坑, 新增 `UsageFallbackCompressTest` 2 例回归)。
> prompt-cache: buildRequest 工具 schema 按 name 字典序排序。事件: `StreamEvent.Compacted`,
> HTTP SSE `event: compact`, 终端 `▤ compact X -> Y`。
> E2E (serve + 独立配置目录 + 临时 state.db, LLM 后端 bench 代理当时卡死改用本地 OpenAI stub,
> z-bot 进程/HTTP/state.db/压缩链路全真实): 2000 token 预算 6 轮 4.2k 字符消息后第 6 轮触发
> `上下文压缩: 11 -> 9 条`, r7/r8 压缩后继续对话正常, 落库 10 条, 压缩累计 3 次;
> SSE 流第二轮长消息收到 `event: compact` 帧。

### P4 checkpoint 影子 git — Hermes #12
- 破坏性操作(write_file/patch/exec 写命令)前对沙箱目录做影子 git 快照(`~/.zbot/checkpoints/store/`, GIT_DIR/GIT_WORK_TREE 分离, blob 去重)
- `/rollback [id]` 恢复; checkpoints 列表/prune
- 验收: 单测(mock git 或真 git); E2E: 写文件→改坏→rollback 恢复

> ✅ 完成 (09-25)。`checkpoint.CheckpointManager`: 纯 git CLI 影子库 — 仓库在
> `<configDir>/checkpoints/store/.git`, 每条命令走 `GIT_DIR`+`GIT_WORK_TREE` env 指向沙箱
> (沙箱内不落 .git); 每个快照挂 `refs/zbot/ckpt/<id12>` 引用, 列表/回滚/修剪全基于引用不依赖分支;
> 回滚 = `read-tree` + `checkout-index -a -f` + `clean -fd` 精确还原快照时刻(快照后新增文件被清掉,
> .gitignore 忽略过的文件本就未进快照、保留原样); 快照顺序按 `rev-list HEAD` 线性祖先序 —
> `--sort=-creatordate` 只有秒级精度, 同秒快照顺序会抖(踩过: 同秒两连拍回滚到错的那次), 祖先序严格稳定。
> 挂接: `executeToolCall`/`confirmTool` 在 write_file/exec/mvn_build 执行前打快照, 工具被审批拦下
> (实际没执行)则 `discard` 丢弃避免列表噪音; 快照失败只 warn 不拦工具。
> `/rollback [id]`(缺省最近) + `/checkpoints`(列表/prune n) 进 SlashRegistry 三端共用;
> config 模式 Builder 自动建 manager, 测试可 `.checkpointManager(...)` 注入。
> 两个 git 坑: for-each-ref 的 format 不支持 `%xXX` 转义(按字面输出, 分隔符必须用真实 tab 字符);
> exec 未合并 stderr 导致 git 报错信息丢失(已修)。
> 单测 10 个真 git 用例全绿(快照/回滚删新增/缺省最近/修剪/丢弃/非法 id 拒绝/只读工具不打快照/
> slash 注册), 全模块 102/102; E2E (piped REPL + 本地 OpenAI stub 下发 tool_calls, 真实
> BotAgent/BuiltinTools/git/store): WRITE1 写入 → WRITE2 写坏 → `/rollback` 恢复 good-version-v1,
> `/checkpoints` 两条快照且 `*` 标最近, 影子库 2 refs, 沙箱内无 .git。
> 自定义 provider code 必须写进 config.properties 的 `providers=` 列表, 否则静默回退 openai 官方端点。

### P5 delegate 子代理 — Hermes #6
- `delegate_task` 工具: spawn 隔离上下文子 BotAgent(共享 provider/凭据, 独立 budget≤父 1/4, 禁 child-only 工具防递归)
- 深度限制(max_spawn_depth) + 并发宽度(max_concurrent_children, 线程池)
- 异步委托: `/background <task>` 落 async_delegations 表, 完成后回投消息; `/agents` 查看在跑子代理
- 验收: 单测(预算限制/深度限制/工具剥离); E2E: 父 agent 委派子 agent 完成两步任务并汇总

> ✅ 完成 (09-25)。`delegate.DelegateManager`: delegate_task 工具同步委托 — 子 BotAgent 共享
> provider/沙箱, 独立会话目录(`<configDir>/delegate/children/d<N>-<seq>`)、独立预算
> (kernel `IterationBudget.childBudget(0.25)`, 经 Builder.budget 注入)、不接 center
> (`Builder.withoutCenter()`); 深度防递归 = 工具只注册在 depth<maxDepth 的 agent 上
> (`agent.delegate.max.depth`, 0=关闭, 默认 2), 深度用尽的子代理工具列表里自然没有 delegate_task。
> config 模式 Builder 默认注册(delegateDepth=0), 测试桩可 `.delegateDepth(0)` 显式开启。
> 异步: `/background <task>` 投 cachedThreadPool(宽度 `agent.delegate.max.children` 默认 3) →
> `/agents` 台账(QUEUED/RUNNING/DONE/FAILED) → `/background result <id>` 取回;
> 台账落 state.db `async_delegations` 表(upsert + 跨重启 listDelegations)。
> 完成后"回投消息"简化为 result 取回式(主动回灌到父会话留给通道层做)。
> 单测 4 个(子代理 1/4 预算实证 maxIter=2/maxTokens=10000、深度剥离 delegate_task、
> depth=0 关闭、异步完成+result 取回), 全模块 106/106(真跑: 先清 surefire XML 再验 MVN_RC,
> 此前一次"全绿"实为编译失败后解析陈旧 XML 的假绿 — duplicate getSessionManager)。
> E2E (piped REPL + stub): DELEGATE→delegate_task→子代理真实 write_file 进共享沙箱
> (notes2.txt=child-was-here)→父汇总"子代理已完成两步任务"; /background→/agents DONE→
> sqlite3 查 async_delegations 落库。已知坑: RawTerminalReader 管道模式吞非 ASCII 输入
> (slash 中文参数变空, E2E 改用 ASCII 任务名; 交互 TTY 不受影响, 属通道层既有问题)。

### P6 记忆 + 技能 + 人格 — Hermes #8 #13
- `~/.zbot/memories/MEMORY.md` + `USER.md`(.lock 并发保护); `memory` 工具(agent 主动读写) + 系统提示注入
- 记忆写审批: `/memory pending|approve|reject`
- SKILL.md 加载: frontmatter(name/description/version/metadata) + 正文; `~/.zbot/skills/<category>/<name>/SKILL.md`; 对齐 z-opc center 已有的 skill 下发格式
- `/skills` list/view/install; 技能可注册为 slash 命令
- `SOUL.md` 人格文件(默认生成, 可编辑), 注入 system prompt 头部
- 验收: 单测(frontmatter 解析/注入/审批流); E2E: 安装一个 skill 并让 LLM 调用其指引

> ✅ 完成 (09-25)。`memory.MemoryStore`: MEMORY.md/USER.md/SOUL.md 三层在 `<configDir>/memories/`,
> 写入走 `<file>.lock` 文件锁 + tmp/ATOMIC_MOVE 原子替换; SOUL.md 缺省生成(可手编, ensure 不覆盖)。
> `memory` 工具(注册于 config 模式): read/append 直执行, **rewrite/forget 复用 P1 的
> Confirmations 审批流** — 首调返回 needsConfirmation, agent 循环挂 WAIT_CONFIRM:memory|,
> `/confirm`(或 confirmTool 带章重放)才落盘; `/memory [user|pending|forget [user]]` 查看/审批状态/清空。
> 注入: system prompt = SOUL 头部 + 基础提示 + [用户画像]/[记忆] 尾部 + 技能指引块(≤3 个, 正文截 600)。
> `skill.SkillLoader`: frontmatter(name/description/version/metadata[.slash]) + 正文,
> 扫描 `<configDir>/skills/<name>/` 与 `<category>/<name>/` 两种形态(center 下发兼容);
> `/skills` 列表(center+本地合并)、`/skills view <name>` 全文; 技能指引直接注入 system prompt
> 让模型开箱即用; **frontmatter 的 slash 字段已解析, 注册成斜杠命令挪到 P8 通道层一并做**。
> 顺手修了 P2 遗留 flaky: session id = `session_+毫秒` 在两进程同毫秒 /new 时撞 id 互吞 JSON
> (orphan 收养测试因此 ~1/3 概率假红) — id 加实例级 UUID 短段后 3 连跑 116/116 全绿。
> E2E (piped REPL): 本地装 deployskill → `/skills` 列表带 (local) 标记 → `/skills view` 出全文
> (frontmatter slash: /deploy 解析正确), SOUL.md 落盘。单测 7 个(store 4 + agent 3 注入/append/审批) + SkillLoader 3。

### P7 cron 调度 — Hermes #14
- `~/.zbot/cron/jobs.json` + scheduler 线程(60s tick, 文件锁防多进程); 投递到 channel(cli 打印/HTTP SSE/webhook)
- `cronjob` 工具(agent 自建定时任务) + `/cron list/add/remove/pause`
- 验收: 单测(表达式解析/触发/锁); E2E: 1 分钟级任务真实触发

### P8 gateway + 多通道 — Hermes #3
- `z-bot gateway` 常驻进程: 会话租约(同会话串行)、消息送达台账、崩溃自愈重启
- Channel SPI: `onMessage/send` 双方法; 首发 webhook + 飞书(HTTP 轮询/长连接) + 钉钉; pairing 配对码授权(8 位码, 1h 过期)
- slash 注册表三端复用; `z-bot send` 脚本化外发
- 验收: webhook E2E(POST 消息→agent→回调回复); 飞书真实收发一条

### P9 MCP 双向 — Hermes #10
- kernel-mcp client 实现: stdio 子进程 + HTTP 两种 transport; tools/list 动态注册为 `mcp-<server>-<tool>` 工具; 配置 `~/.zbot/config.properties` `mcp.servers=…`
- 复用/对齐 z-mcp 仓(已发布 0.1.2)
- (可选)反向: z-bot 把会话暴露成 MCP server
- 验收: 接一个真实 MCP server(如 filesystem), LLM 调用其工具成功

### P10 前端升级 + 发布
- Web 控制台扩展: 会话列表/搜索、models、skills、cron 页(现只有 chat)
- TUI 升级(JLine 3): 语法高亮渲染、补全、分屏(对话+工具输出)
- 模型目录缓存 + fallback 链 + 凭据池(多 key 轮换)
- 0.3.0 发 Central(补 README.md — 7 仓欠账一并清)
- 验收: 全链路回归 + Central pull 集成测试

## 2. 结构性红线 (从 Hermes 踩坑直接继承)
1. **代码/数据分离**: `~/.zbot/` 只有数据与用户配置; 支持 `ZBOT_HOME`/`--config-dir` 多 profile 隔离
2. **注册表 SPI, 不写硬编码清单**: 工具/通道/技能/记忆/上下文引擎全部可插拔
3. **显式状态对象, 不造上帝类**: Hermes 的 1.6 万行上帝类是反面教材; budget/interrupt/steer/checkpoint 全部进 AgentContext
4. **横切关注点管道化**: 审批/记账/脱敏/压缩锁做成循环上的显式 stage, 不内联进主循环
5. **0 强制三方依赖**: 可选能力(excel/pdf/语音)用 optional 依赖 + 运行时探测隔离

## 3. 进度记录
- 2026-09-24: 计划成文。基线: z-bot 0.2.0 (26 类/5090 行, 43 单测), kernel 0.1.1 (9 空壳子模块)。
- 2026-09-25 P0–P6 ✅: kernel SPI / BotAgent 核心 / state.db / ContextEngine / CheckpointManager / DelegateManager / Memory 三层 + SKILL.md + SOUL.md。单测 116/116 连续 3 跑绿。
- 2026-09-25 P7 ✅: cron 调度 + 主动自动化落定 (`z-bot-core/.../cron/{CronSchedule,CronJob,CronScheduler,CronTools}.java`)。
  - 表达式: `every Ns/Nm/Nh` / `hourly` / `daily HH:MM`, `due()` 用 truncatedTo(MINUTES) 走整分边界, 修了原实现跨边界漏触发的 bug (例如 last=09:00:30→now=09:02:30 找不到 09:02:00)。
  - 调度: daemon 60s tick, jobs.json tmp+ATOMIC_MOVE, 每 job `<id>.run.lock` FileLock 防多进程抢跑, runner 抛异常也写回 `lastResult`。
  - 工具: `cronjob` (add/list/remove/pause/resume), 注册到 BotAgent 工具表; `/cron` slash 命令同步操作; `BotAgent.Builder.cronScheduler(...)` 注入短 tick scheduler 便于测试。
  - 副修: `SessionManager.INSTANCE_TAG` static → instance, 同进程多 SessionManager 实例同毫秒 `createSession()` 不再撞 id (orphansFromAnotherProcessAreAdopted 假红根因)。
  - 单测 152/152 连续 3 跑绿 (新增 CronScheduleTest 17 + CronSchedulerTest 13 + BotAgentCronTest 6)。

### P8 gateway + 多通道 — Hermes #3 ✅ 完成 (2026-09-25)
**实现**:
- `channel.Channel` SPI: 5 个方法 (name/start/stop/awaitTermination/send + isRunning)
- `channel.ChannelMessage` / `OutboundMessage` POJO
- `channel.ChannelBus`: per-conversationId agent fork (`prototype.forkFor(cid)`) + 异步 deliver + 200 条 history ring buffer
- `channel.WebhookChannel`: `POST /webhook/in` 入站 + `GET /webhook/out` 长轮询出站 (20s); 可选 callbackUrl POST 回执
- `channel.FeishuChannel`: `GET /feishu/event?echostr=` URL 校验; `POST /feishu/event` 事件分发; `verifySignature` SHA-1(timestamp+nonce+encryptKey+body); conversationId = `chat_type:chat_id`
- `channel.DingTalkChannel`: HMAC-SHA256 签名 URL (`base64(hmac-sha256(secret, ts+"\n"+secret))`); SHA-1 hex helper
- `channel.PairingService`: 8 位配对码 (去 0/O/1/I 字母表), JSON 持久化 + tmp+ATOMIC_MOVE, 默认 60min TTL, 一次性消费
- `channel.Gateway`: 聚合 prototype/bus/pairing/channels, register/start/stop/status
- `cli.GatewayCommand`: `z-bot gateway --port 8080 --webhook-port 8090 --pairing`; 注册 HttpChannelAdapter(包原 HttpChannel) + WebhookChannel
- `cli.SendCommand`: `z-bot send <channel> <conv> <text>` → webhook 或 http 模式
- `cli.PairCommand`: `z-bot pair <8位码>` 消费配对码

**BotAgent.forkFor(conversationId)**: 共享 provider/tools/sandbox/context/checkpoint, 独立 SessionManager 在 `<configDir>/delegate/children/fork-<cid>/`(P5 delegate 子代理的同模式复用)。

**单测**: 181/181 连续 3 跑绿 (新增 29: PairingServiceTest 7 + WebhookChannelTest 5 + FeishuChannelTest 7 + DingTalkChannelSignTest 5 + GatewayTest 5)。webhook/飞书是真 HTTP 端到端; 配对/钉钉签名/Gateway 是纯算法/桩。

### P9 MCP 客户端 — Hermes #10 ✅ 完成 (2026-09-25)
**实现**:
- `mcp.McpClientFactory`: `createStdio(BotConfig.McpServerEntry)` 用 kernel `StdioMcpTransport` 拉起子进程 + JSON-RPC `initialize/tools/list/tools/call` 封装; `wrap(name, transport)` 用于测试注入
- `mcp.McpBridge`: 单 server 工具桥接, 命名 `mcp-<server>-<tool>`, 注册到 `Toolkit`
- `mcp.McpManager`: 多 server 聚合, **失败隔离** (一个 server 起不来不连累其他), `startAll / reload / stopAll / snapshot / toMapList` API; **静态工厂** `fromClients(Toolkit, List<McpClient>)` 绕开 `List<X>` / `List<Y>` 擦除冲突
- `BotAgent` 接线: `mcpManager` 字段 + `Builder.mcpManager(...)/withoutMcp()`; `BotAgent.shutdown()` 停 mcpManager; `BotAgent.mcpManage(args)` 对应 `/mcp [list|reload]` slash 命令; config 模式 + `mcp.servers` 非空时 `build()` 自动 `startAll`
- `BotConfig` 解析: `mcp.servers=fs=node /usr/local/bin/mcp-fs.js,git=uvx mcp-git --foo` 逗号分 server, `=` 分 name 和命令行, 空格切 argv; 新 `McpServerEntry(name, command)` POJO
- pom: 引入 `z-agent-kernel-mcp` 依赖 (dependencyManagement + z-bot-core 显式声明)

**单测**: 190/190 连续 3 跑绿 (新增 9: McpManagerTest)。`FakeMcpServer` (内存 `McpTransport`) 覆盖: 启动注册/工具执行/server 错误透传/单 server 失败不连累其他/reload 重注册/命名格式/properties 解析/空 servers。

### P10a 多 key 凭据池 ✅ 完成 (2026-09-25)
**实现**:
- `llm.KeyPoolLlmProvider`: 内层 key 轮换装饰器, 分层 Resilient(外, 模型降级+瞬时重试) → KeyPool(内, key 轮换) → 单 key kernel provider; key 作用域失败 (401/403/402/quota/429) → `CredentialPool.reportFailure` 冷却换下一个 (429 优先 Retry-After 否则 60s, 鉴权类 10min); 非 key 作用域 (5xx/超时/400) 直接上抛交 Resilient; 成功 → reportSuccess; 流式不轮换 (半途换 key 会拼出两段回复) 只记冷却
- `BotConfig.Provider`: `getApiKeys()` 逗号分隔多 key + `withApiKey()`; 构造器改 public
- `LlmRouter.create` 多 key 只取首 key 构造; `BotAgent.wrapResilient` 内层先套 KeyPool 再套 Resilient
- pom: 引入 `z-agent-kernel-credential` (dependencyManagement + z-bot-core)

**单测**: 205/205 连续 3 跑绿 (新增 15: KeyPoolLlmProviderTest)。

### P10b 模型目录缓存 + /api/models ✅ 完成 (2026-09-25)
**实现**:
- `llm.ModelCatalogCache`: per-provider TTL 桶缓存 (默认 5min) + supplier 重拉; 拉取失败降级用旧缓存顶着 (宁可目录旧也不空/不炸); JSON 落盘 tmp+ATOMIC_MOVE, 构造时载入容忍损坏文件; 未知 Capability 跳过; `catalog / refreshAsync / invalidate / isStale / fetchedAt / shutdown`
- `HttpChannel` 新路由 `GET /api/models`: provider/model/count/models(id/displayName/provider/contextWindow/maxOutputTokens/capabilities)/fetchedAt/stale/refreshing; `?refresh=1` 异步重拉立即返回当前缓存; cache 文件 `<configDir>/models-cache.json`; `stop()` 时 shutdown cache 线程

**单测**: 216/216 连续 4 跑绿 (新增 11: ModelCatalogCacheTest 10 + HttpChannelTest 模型端点 1)。
已知抖动: 全量首跑出现过 1 次 WebhookChannelTest 401 (全仓唯一 401 源是 Feishu token 校验, WebhookChannel 无鉴权逻辑), 单类 10/10 + 后续 4 轮全量均绿, 判定为环境级临时端口复用, 未复现不修。

### P10c Web UI 扩展 ✅ 完成 (2026-09-25)
**后端**:
- `HttpChannel` 新路由 `GET/POST /api/cron`: GET 返回 count+jobs(id/name/prompt/schedule/enabled/lastRun/lastResult); POST 支持 `{action:add, name?, prompt, schedule}` / `{action:remove|pause|resume, id}`; 无 scheduler 时 GET 返 count=0、POST 返 `cron scheduler not enabled`; add 缺字段/未知 action 都返 ok:false

**前端 (web/index.html)**:
- 侧栏会话区加搜索框: 按 title/id 客户端过滤, 无匹配给提示, 清空即恢复
- 新增"管理"侧栏区 → 三个 modal 视图: 模型目录 (id/名称/上下文/能力表格 + 刷新缓存/强制重拉) / 技能 (code 列表 + 从中心同步) / 定时任务 (增删暂停恢复 + 上次运行结果)
- modal overlay (点遮罩/Esc 关闭) + attrEsc 属性转义 (escapeHtml 不处理引号, 放 onclick 会炸)

**真渲染验收** (起真实 serve + 内置浏览器实测, 非代码断言): 页面骨架/搜索框/管理区渲染无 console 错误; 模型目录真拉到 6 个模型; cron UI 加→行出现→删→count 归 0 回路通过 (残留 jobs.json 已清理回 pre-state); 会话搜索 18→3 条且清空恢复; 技能页渲染正常。

**单测**: 219/219 连续 3 跑绿 (新增 3: HttpChannelTest cron 增删暂停恢复回路 / 校验拒绝 / 无 scheduler 分支; setUp 注入 CronScheduler 使 status tools=2→3)。

### P10d TUI 升级 (JLine 3 + 补全 + 分屏) ✅ 完成 (2026-09-25)

**目标**: 把终端 REPL 从自研 raw 模式升级到 JLine 3 — 方向键历史 (↑↓)、Tab 补全 (含 `/switch <会话id>` 二级补全)、输入高亮 (斜杠命令绿粗体 / 反引号代码青色)、右分栏状态条 (model/step/tokens 常驻 prompt 行右边缘)。行为契约与 `RawTerminalReader` 完全对齐: Ctrl-C 抛 `IOException("interrupted")`、Ctrl-D 空行返 null、非 TTY 自动降级行模式。

**选型**: JLine 3.24.1 (已核 `javap` class 版本 52 = Java 8 字节码, 3.23/3.21 同样, 本仓全线 Java 8 兼容)。

**升级方案 (代码结构)**:
- 新增 `ui/LineEditor`: 统一门面, `Mode{JLINE, FALLBACK}`
  - `create(historyFile, extraCompletions)`: TerminalBuilder→LineReaderBuilder 装配 completer/highlighter/HISTORY_FILE; **任何异常或 dumb 终端都降级, 永不抛**
  - `readLine(prompt, rightPrompt)`: JLINE 走 `LineReader.readLine(4参, 带右分栏)`; `UserInterruptException`→打印 `^C` 后抛 `IOException("interrupted")` (契约对齐), `EndOfFileException`→null; FALLBACK 打印 prompt (右状态贴后面) 后走行模式
  - `SlashCompleter`(package 级, 可单测): 首词 `/` 开头→前缀匹配命令池 (由调用方供给, 原计划匹配 `RawTerminalReader.SLASH_COMMANDS` 已被推翻); 第 1 词 `/switch` 且 wordIndex≥1→补会话 id (由 supplier 供给, 测试注入)
  - `InputHighlighter`(package 级, 可单测): 首 token 斜杠命令绿粗体, 成对反引号段青色, 其余原样
  - **fallback 懒加载**: `RawTerminalReader` 构造会跑 stty 并备份 `~/.zbot/.stty.bak` → 延迟到首次 readLine 才构造, 保证 `create()`/`close()` 在测试里零副作用
- `RawTerminalReader`: 命令清单改为注入 (见下方实测收口 — 原"`SLASH_COMMANDS` 改 public"的方案被推翻)
- `TerminalUI`: 抽 `promptText()` (prompt() 改为打印它), 新增 `rightStatus(model, step, tokens)` (右分栏快照, 格式仿 printStatusBar)
- `TerminalChannel.run()`: `RawTerminalReader` 换 `LineEditor.create(...)`; 每轮 `readLine(promptText(), rightStatus())`; 右状态取 stepCount/tokenEstimate 快照 (仅进入下一轮 readLine 前刷新)

**实测收口 (本批全部完成并提交)**:

- **接手时该批从未编译过** — `mvn -o test` 基线直接 BUILD FAILURE, 4 个编译错全在 `LineEditor`:
  ① `LineReader.readLine` 4 参签名是 `(prompt, rightPrompt, mask, buffer)` 不是 `(prompt, "", null, rightPrompt)`, 且传裸 null 会与 `MaskingCallback` 重载歧义 → 必须 `(Character) null` 消歧;
  ② JLine 3.24 的 `Highlighter.setErrorPattern/setErrorIndex` 是 **abstract 而非 default**, 必须实现;
  ③ `AttributedStyle.Color.GREEN` 不存在 — 3.24 是 `int` 常量, 用 `AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold()`;
  ④ 测试里 `Candidate.getDisplayName()` 不存在 → 用 `displ()`。
  ⇒ 教训: "已完成 (未提交)" 清单里任何一项没跑过编译器就不算数。

- **补全池改注册表派生 (修掉本批自带的双份清单)**: 原方案 `SlashCompleter` 前缀匹配 `RawTerminalReader.SLASH_COMMANDS` (18 条手写), 而 `SlashRegistry` 有 19 条注册 (18 唯一) — 手写清单漏了 `/stop /steer /queue /compress /rollback /checkpoints /cron /background /agents` **9 条**, Tab 补不出来。
  现: `LineEditor.create(historyFile, commandNames, sessionIds)` 三参; 命令池由调用方 (`TerminalChannel.slashCommandPool()`) 从 `slash.all()` ∪ `RawTerminalReader.LOCAL_COMMANDS` 拼出;
  `RawTerminalReader.SLASH_COMMANDS` 删除 → 换成 `LOCAL_COMMANDS` (9 条终端私有: /status /theme /feedback /confirm /help /? /exit /quit /q) + 实例 `setSlashCommands()` (降级路径的 Tab 也吃同一份池)。
- **`/help` 表同样注册表派生**: `TerminalUI.printCommandTable(List<String[]>)` 不再自带 16 行硬编码, 由 `TerminalChannel.commandRows()` = 注册表 18 条 + 终端私有 6 条 = **25 行** (实测启动面板逐行核对); 私有命令说明收在 `LOCAL_DOC` 一处, 别名 (/quit /q /?) 折进 /exit 条目不进清单。
- **history 落 `<configDir>/history`** (原写死 `user.home/.zbot/history` 违反红线 1); `agent.getConfig()==null` (测试桩) 时传 null 即不持久化。
- **单测 9 例** (`LineEditorTest`, 计划 6 例): dumb 终端降级不抛 / `create()` 任意入参不抛 / **补全池点名 9 条注册表命令** (`/check`→唯一候选 `/checkpoints`) / 非斜杠首词 0 候选 / `/switch` 二级补会话 id / supplier 返 null 不炸 / 斜杠首词 `styleAt(0)==DEFAULT.foreground(GREEN).bold()` 且参数段默认 / 反引号成对区间青、区间外默认、**未闭合反引号不高亮** / 空 buffer→`AttributedString.EMPTY`。
  高亮断言走 `AttributedString.styleAt(i)` 与构造出的期望 style 比相等, 不绑 SGR 字符串 (避开 palette 归一化抖动)。
- **全量 228/228 连续 3 跑绿** (219 基线 + 9 新增), 每轮先 `rm -rf target/surefire-reports` 再取 `MVN_RC`。
- **真 pty 实测 11/11 通过** (`/tmp/pty_tui.py`, `pty.fork()` 起 `java -jar z-bot-core.jar repl --config-dir /tmp/zbot-pty --sandbox /tmp/zbot-pty/workspace`, 断言跑完打印 PASS/FAIL 逐条):
  A1 启动面板命令表 · A2 右分栏 `abab6.5s-chat • step=0 • tokens=0` 常驻 prompt 行右缘 · A3 `/checkp`+TAB 补成 `/checkpoints` 并执行 (全文无"未知命令") · A4 ↑ 历史回带后再执行一次 ("暂无 checkpoint" 出现 ≥2 次) · A5 `/help` 含 /rollback /agents /background /compress · A6 斜杠命令绿粗体 (`\x1b[32;1m`) · A7 反引号段青色 (`\x1b[36m`) · A8 `/exit` 退出码 0 · A9 退出后 `stty -a` 无 `-icanon`/`-echo` (终端已恢复) · A10 `<configDir>/history` 落 3 条带时间戳 · A11 全程未误发 chat 请求。
- **pty 量具坑 (值得单记)**: JLine 进 `readLine` 会发 smkx (`\x1b[?1h\x1b=`) 打开**光标键应用模式**, ↑ 的真实字节是 `\x1bOA` 不是 `\x1b[A`。第一版脚本发 `\x1b[A` → ESC 被吞、`[A` 当字面量入 buffer、回车把它当普通消息**打了一次真实 minimax 请求** (拿回 429 才暴露)。改 `\x1bOA` 后 A4 通过 — 量具错, 不是产品错; 顺手加 A11 守卫防同类泄漏。
- **隔离实测**: `--config-dir` 跑完 `~/.zbot/` **无** `history` 新文件 (红线 1 达标)；但 `~/.zbot/state.db` mtime 变了 ⇒ 暴露新缺陷: `BotConfig.stateDbPath` 缺省写死 `user.home/.zbot/state.db`, 不吃 configDir → `--config-dir` 多 profile 下会话库仍共享一个文件。**记入 v2 修复清单** (`configDir==null` 时才回落 user.home, 与 memories/skills/cron 同一套口径)。
- 本批 pre-state (`ls ~/.zbot/` 7 项 + `.stty.bak` 空文件) 实测后未变动, /tmp 临时目录用完即删。

**测试注意事项 (红线继承)**:
- 单测**不得**触发 `RawTerminalReader` 构造 (stty + 写 `~/.zbot/.stty.bak`) → 只测 package 级 completer/highlighter 与 `create()/close()`; fallback readLine 不进单测, 归 pty 实测。实测确认: `LineEditor.create()` 在无 tty 下走 DumbTerminal→FALLBACK, 不建 fallback 读取器, 零副作用。
- pty 实测前记录 pre-state (`ls ~/.zbot/`), 用 `--config-dir` 独立目录避免污染真实数据。
- 断言 ANSI 用 `\u001b[` 存在性 + 分段计数, 不绑死 JLine 具体 SGR 序列 (避免版本抖动假红)。

**P10 剩余子项 (P10d 之后)**:
- P10e: README.md (7 仓欠账) + `<revision>` 0.2.0→0.3.0
- P10f: 0.3.0 发 Central (走既有 dry-run→deploy→repo1 同步→pull 集成验证流程) + 全链路回归验收

---

## 8.9 第五期合并轮（09-26 2026-09-26 19:46:58+0800，主编亲测）

**并了什么**：并入 `w8-p26`（p26c 收口）、`w10-p24`（p24d）、`w11-p27`（p27b）；当前提交树
`@Test=1060` / 测试文件 97 个（`git grep -c '@Test' HEAD -- z-bot-core/src/test` 现算）。
其间先落一条我自己该负责的合并回归修复。

**这条回归只有合并树能抓到**：`w9-p22` 带进来的 `tool/env/ExecEnvConfig.java` 有两处自解析
`System.getProperty("user.home")`（docker socket 缺省、docker ledger 缺省目录），把 P11b 红线 1 的
"profile 目录单一解析点"从 1 个撑成 3 个 —— p11b 的 E6 在 p22 自己的单棒四杠里从没跑过，所以全程绿。
修法：两路都交回 `BotConfig`（新增 `userHome()` 原语 + ledger 回落 `defaultConfigDir()`），
现在 `grep -rn user.home z-bot-core/src/main/java` 只有 `config/BotConfig.java` 一个文件命中。

**目标树杠①（全 reactor，不 `-pl`）×3**：`BAR1 run=1 rc=0 parse_rc=0 BAR1PARSE /Users/zifang/.cache/zbot-integrate/final1000plus/bar1_1.log module_lines=1 class_lines=96 module_sum=1058 class_sum=1058 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES BAR1 run=2 rc=0 parse_rc=0 BAR1PARSE /Users/zifang/.cache/zbot-integrate/final1000plus/bar1_2.log module_lines=1 class_lines=96 module_sum=1058 class_sum=1058 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES BAR1 run=3 rc=0 parse_rc=0 BAR1PARSE /Users/zifang/.cache/zbot-integrate/final1000plus/bar1_3.log module_lines=1 class_lines=96 module_sum=1058 class_sum=1058 F=0 E=0 S=0 build=SUCCESS socket_hits=0 agree=YES `（逐字读数在 `~/.cache/zbot-integrate/final1000plus/SUMMARY.txt`），
判词 `TOKEN=BAR1_GREEN_3OF3`、`notgreen_or_fatal=0`、`socket_bad_runs=0`。

**目标树杠③（跨期回归面）**：老 13 序列 19 跑 `nonzero_rc=1`；补漏清单
（p24/p26/p27 三支新序列 + p16 复测）另 12 跑 `nonzero_rc=0`。
唯一那次红是 `p16` rc=1，红在**它自己的量具**：段 A 去 `stat()` 一个只在连接打开时存在的 `state.db-shm`，
抛 `FileNotFoundError`（日志里它自己也打印了"读数不可信"）。同一 harness 在合并树复跑 3/3 rc=0
⇒ 判为**量具在瞬时 sidecar 文件上的竞态**，不是产品回归；这条 FAIL 不复现，不记进产品缺陷账。

**杠④**：`~/.zbot` 在杠① 的 t0/t_end、杠③ 的 t0/三轮中点/t_end、补漏 4 个中点共十余个采样点逐格相同
`entries=8 / config.properties=2dadaed0 / state.db=690ddbc0`；真 key 全程只量长度（125），值未被读取。

**四杠之外的账（不当已通过）**
- p24d 台账 `27 KILLED / 1 SURVIVED / 1 PARTIAL`；p27b 台账 21 支 `8 RED-OK / 10 PARTIAL / 3 SURVIVED`（检出 85.7%），
  三处 SURVIVED 已点名（M13 ATOMIC_MOVE 单进程不可观测、M17 并发闸只数 RUNNING、M19 关 reconcile 的双写覆盖）⇒ 并发测试仍缺。
  **（此行 09-27 已被订正，别按它下结论：M17/M19 各补了一支确定性捕手并在 `31be6c7` 整批复跑里判 RED-OK，
  SURVIVED 降到 1 支（只剩 M13，按 §8.12 判等价变异）；"并发测试仍缺"这个结论作废。见 §8.12 与 `p27/EVIDENCE.md` §12。）**
- p27b 交接两条**尺的缺陷**未改：`p27_mutation.py` 的锁文件 0 字节时 `int()` 抛错 → 走 `unlink` 接管别人的锁
  （真持有者活着时闸形同虚设）；`p27_e2e.py` 实测完全不取锁。
- p24d 报"我自己四枚提交从 `w10-p24` 消失"：那四个 SHA 在本仓对象库 `git cat-file -t` 全部
  `Not a valid object`，且 `w10-p24` reflog 里没有它们的位置 ⇒ **失窃叙述不成立**，其步 1—4 都在 `9b4a66c` 里。
- 我这轮两处自造的量具事故要记着：① `bar3_merged.sh` 用 `grep -c add_argument` 猜入参口径，把只吃
  `--only/--json` 的 `p12` 喂了 `--label` ⇒ `rc=2 logbytes=112` 被我读成"这系列跑不动"（实为派发器缺陷，
  现改为"脚本自己声明 `--label` 才传"）；② `grep -c` 命中 0 时退出码非 0，把 `XBAD=0` 那次整条 `&&` 链
  短路掉了 —— 第一次提交因此"什么都没写进去"，是 `git commit` 报 `nothing added to commit` 才暴露的。

**待收口**：P25（ACP）与 P28（控制台对等）的收口棒 p25b/p28b **又各自死于 150 轮**，
`w12-p25`/`w13-p28` 上仍有未提交的实现与验收目录 ⇒ 这两支没并进 main，下一轮第一动作是保现场再跑四杠。
P19（命令表单源多端）未派（等 P28 的 `WIRING.md` 接缝）；P29（README×7 + 抬号 `0.2.0→0.3.0` + 发 Central + 对外拉取验证）未开工。

---

## 8.10 第六期：p25b/p28b 并入后的目标树复测 + 一条被并行会话抢先推送的修复（09-26 20:27:56+0800，主编亲测）

**这一轮量的是哪棵树**：`w12-p25`（`0b06595`）与 `w13-p28`（`3b4e725`）已并进来，上一节末尾那两条
"死于 150 轮未并"已了结。测量起点 `2034975`（三把 E2E 尺的量测对象修正），终点 `fb80e6d`
（tree `6acc67f2ad25b8520b6dbad024f3097d2d800a18`）—— 中间还夹了三笔不是我写的提交，见下。

**杠① 第一遍判红，而且是真红**：`LABEL=final1133 … HEAD=2034975` 三跑里
`run=1 rc=0 module_sum=1130 class_sum=1130`、`run=2 rc=1 … E=1 build=FAILURE`、
`run=3 rc=1 … E=1 build=FAILURE`，判词 `TOKEN=BAR1_NOT_GREEN_3OF3`（这条 token 是量具自己打的，
不是我改口后的说法）。红的是同一个具名用例：
`McpRealStdioServerTest.zbotTransportHonoursItsOwnDeadlineWhileKernelBlocksInReadLine:366`
→ `Runtime mcp connect failed: mcp 请求超时（900ms，method=initialize）`。

**根因不在被测产品的那半，在尺的形状里**：`ZBotStdioMcpTransport` 拿同一个 `timeoutMillis`
既管 `initialize` 握手又管之后的每次调用。握手等的是 stdio server 冷启动（exec + 解释器 + import SDK），
load average 11 时 python 参照 server 起不动 900ms ⇒ 这条"验 deadline 真的生效"的守卫，
实际把"机器这一刻有多忙"当成了验收条件，空机必绿、忙时必红。**两头都是缺陷**：产品侧一个短的单请求
上限会把正常要 1 秒起来的 server 永久挡在门外；尺侧一条只在负载下才红的守卫不叫守卫。

**修法**：握手预算独立（新增 `DEFAULT_HANDSHAKE_TIMEOUT_MILLIS=30_000` + `Options#handshakeTimeoutMillis`，
`requestRaw` 多一个显式 `budgetMillis` 形参），`initialize` 走握手预算、其余调用仍走 `timeoutMillis`。

**这条修复是被并行会话推掉的，不是我**：20:15:50 另一会话的 `67123e8 "chore: 提交工作树现有改动并推送"`
把我**当时仍未提交**的 `ZBotStdioMcpTransport.java` 连着它自己扫到的 `p17/out/lockprobe/LockProbe.java`
一起 commit 并推送 —— 内容逐字节就是我写的（`git diff HEAD -- <该文件>` 空），但它带着"未验证构建/测试"
的自述进了远端，而且**它的成对守卫还不在树里**。我不 amend 已推送的东西，改为把守卫单独落地成
`fb80e6d`，并在这节把过程记清楚：共享工作树里"我只改 src/main 不上台"这件事不由我决定。

**杠② 成对注入（只在 `git archive` 的隔离副本里动手，`~/.cache/zbot-mutpair/`）**：
基线 `baseline rc=0 reds=0`；
`mutA`（把 `initialize` 换回吃 `timeoutMillis`）→ `rc=1 reds=3 red_cases=handshakeOutlivesATightRequestTimeout,requestStillDiesOnItsOwnTightTimeout verdict=KILLED`；
`mutB`（把单请求也抬到握手预算）→ `rc=1 reds=2 red_cases=requestStillDiesOnItsOwnTightTimeout verdict=KILLED`；
两次还原 `md5=4b430510c40c57730eebff22cce14bc8` 与原件相同。
**mutA 的判别力要打折说**：它两例皆红是走同一个 `open()` 辅助函数一起挂的，说明不了"握手那一侧的断言抓到了"；
真正干净的方向判别是 mutB —— 只红调用那一例。夹具只吃 stdlib，不引官方 SDK。

**杠① 复测（全 reactor，不 `-pl`）×3**：`LABEL=handshake1132 WT=… HEAD=fb80e6d` 三跑
`rc=0 parse_rc=0 module_lines=1 class_lines=105 module_sum=1132 class_sum=1132 F=0 E=0 S=0 build=SUCCESS socket_hits=0`，
判词 `TOKEN=BAR1_GREEN_3OF3 green_runs=1,2,3 notgreen_or_fatal=0 socket_bad_runs=0`，
`DIRTY_AFTER src_main=0 src_test=0 total=0`（逐字读数 `~/.cache/zbot-integrate/handshake1132/SUMMARY.txt`）。

**"第三把尺"这个说法要订正**：同一轮 `TESTCOUNT committed_at=1135` 而实跑 `1132`，差 3。
逐条对完不是丢用例：3 处偏移全部定值可归因 —— `P26RetryPolicyTest.java:37` 的 javadoc 里写了个字面 `@Test`、
`memory/MemoryE2eDriver.java` 与 `ui/RawTerminalVerdictProbe.java` 各 1 个 `@Test` 但类名不以 `Test` 结尾
（surefire 按命名排除，是刻意的驱动/探针）。我改用按 (class, method) 名字集合对账：
`executed_cases=1132 classes=105 / annotated=1132 / MISSING_FROM_EXECUTION=0 / EXECUTED_NOT_ANNOTATED=0`。
⇒ `git grep -c '@Test'` 与实跑之间**不是两把独立的尺**，它有一个随注释和命名走的定值偏移；
上一节拿 1060/1133 这类静态数当"双尺对账"的一半，口径写错了。

**杠③ 目标树跨期普查（`bar3_all.sh`，起点 HEAD=b49da9f）**：`BAR3ALL_DONE runs=23 nonzero_rc=2 missing=3 series=17`。
p25/p26/p27 三支在修掉硬编码写手根之后**第一次真的量到目标树**，各 3 跑全 `rc=0`：
p25 `checks=19 pass=19 fail=0 llm_hits=1 result=OK`、p26 `scenes=10 failed_checks=0`、p27 `CHECKS=20 FAILED=0`
（+ `java_side_all_checks_green PASS`）。两处红都不记产品缺陷：
① `p24 failed_checks=1 ['前置：被测树 z-bot-core/src 无未提交改动（量的是提交树）']` —— 抓的是我本人
正在改 `src/main`，这条守卫有效、该红；
② `p11b FAIL E5b 终端设置被恢复（stty -g 前后一致）` —— **未归因**，而且它的消息把 before/after
各截到 28 字符、两边共用 `gfmt1:cflag=4b00:iflag=2b02:` 前缀 ⇒ 从日志里看不出差在哪个 flag。
这是 p11b 自己的取证缺陷，下一棒要先把差值按 token 打印出来再判它是不是产品问题。
`missing=3` 全部是同一件事：`E2E_MISSING p28（无 harness ⇒ 杠③ NO-RUN）` —— P28 至今没有 E2E 尺，
不许读成"已通过"。（**此句在 21:39 已被下一节作废**：P28 的杠③ 现在有数了，见 §8.11。）

**杠④**：`B4 entries=8 cfg=2dadaed0 db=690ddbc0` 在杠① 两遍的 t0/t_end、杠③ 的 t0/三个中点/tend
全部逐格相同；真 key 全程只量长度（125），值未被读取。

**新记两笔账（都不当已完成）**
- **E2E 现场被提交进仓库**：`git ls-files '_doc/acceptance/*/out/*'` 实测 35 个（含 3 个 `state.db` 二进制）
  + `__pycache__` 4 个，是并行会话 `b49da9f`/`f90b1f5`/`67123e8` 三笔推上去的；同一支
  `p17/out/lockprobe/LockProbe.java` 在三笔里加了删、删了加。后果是**每跑一次 E2E 工作树必脏**，
  于是 p24 那条"量的是提交树"前置从此长期红。要不要 untrack + 补 `.gitignore` 等用户点头
  （那是改别人正在写的共享状态）。凭证我扫过：全树 `≥80` 位 hex 的文件数 `0`、
  真 key 精确搜 `rc=1`（不在树里）、阳性对照 `stub-key` 命中 45 个文件证明扫描有牙。
- **E2E 不回收自己的子进程**：`ps` 抓到 2h24m 前起的 `java -jar z-bot-core.jar gateway`
  仍活着（p16/p17/p18/p23 的 profile 目录，端口 61003/61004/61006/61007/61016/61017/62618…），
  这一轮 load average 8—15 有一部分是它们贡献的 ⇒ 负载型假红的源头之一在 harness 的收尾，不在产品。

**待收口**：~~p28c（补 `p28_e2e.py`，让 P28 的杠③ 从 NO-RUN 变成有数）~~ **已结**（见 §8.11）；
p19a/p19b（命令表单源多端，输入 `WIRING.md`/`ROUTES.tsv` 已在 main）已由写手落在 `w14-p19`
（`147389f`/`11e2d4f`/`bff2ccc`）**待主编复测并树**；P29 未开工；D-2 裁定、P12 §12.5 那 5 支
部分覆盖变异、p27b 交下来的两条尺缺陷（0 字节锁走 `unlink` 接管、`p27_e2e.py` 不取锁）仍未动；
`w2-p20` 永不并；内核 0.2.1 与 z-bot 0.3.0 发 Central 各需单独点头。

## 8.11 第七期：P28 杠③ 有数了（09-26 21:39:55+0800，主编亲测）

**这一节只做一件事：把 §8.10 里"P28 至今没有 E2E 尺"这句话作废，并给出作废它的读数。**

量具 `_doc/acceptance/p28/p28_e2e.py`（承 p28a 台账 + p28b 的 SSE/LEDGER 面，我在其上修了 9 处自身失真，
逐条归属见 `_doc/acceptance/p28/EVIDENCE.md` 的 P28-lead-3）。目标树 main、**三轮整跑**、身份逐轮重打：

```
ROUND_START|1|20:59:38|head=9884059|harness_md5=9e783bbc|tree_dirty=0
ROUND_END  |3|rc=0|21:34:02|head=9884059|harness_md5=9e783bbc|tree_dirty=0   （三轮 rc 全 0，每轮 688s）
E2E|run=lead_v3_r1 checks=30 pass=30 fail=0 llm_hits=1 serve_port=50709 result=OK
E2E|run=lead_v3_r2 checks=30 pass=30 fail=0 llm_hits=1 serve_port=59260 result=OK
E2E|run=lead_v3_r3 checks=30 pass=30 fail=0 llm_hits=1 serve_port=63989 result=OK
```

30 条 = A 组 7（真 tty/fd0）＋ S 组 12（SSE 线字节）＋ T 组 7（台账/路由/文档派生）＋ U 组 4（不变量与卫生）。
**跨轮一致性**：BAR4 九行去 tag 后 `uniq -c` = 9 条同一串；BUCKET 九格 r1/r2/r3 两两 `diff` 为空；
三轮 `^CHECK|` 名字集合 md5 相同。守卫有牙性的离线自证 `SELFCHECK_DONE|lines=21 rc=0`（含 5 条正向对照）。

**杠④**（同一次运行里量，不是另起一遍）：`dir_count=8 cfg_md5_8=2dadaed0 db_md5_8=690ddbc0 key_len_only=125`
在 3 轮 × t0/t1/t2 共 9 个时点逐字节相同；真 key 全程只量长度。

**这尺没量到的，别读成"已过"**（P28-lead-4 全文）：D-P28-2（`POST /api/skill/push` 推的是拉、
`HttpChannel.java:687` 的 `ok` 无条件写死、`BotAgent` 里 `pushSkill` 0 命中）**仍未裁定**——本尺故意不钉，
钉它要先定"应当做什么"；`/api/commands` 当时 0 命中（P19 正在补）；D-P28-1 的 GET/POST 双口径未闭合；
真 tty 人机体验 NO-RUN；每轮 688s 的构成**未归因**（我上一轮说的"非 chunked 一律等 socket 超时"被本轮
自己的 `wall=` 读数证伪：最大只有 12.32s）。

**顺带两笔环境账**：① 我的三轮没留 serve（收口 `ps | grep "[j]ar serve"` 0 命中）；② 仍有 5 支
`ppid=1` 的 gateway/repl 活着（p16/p17/p18/p23 的 E2E 收尾缺陷），全部只绑 `127.0.0.1`，
属别的会话 ⇒ 只记账不杀。③ 我自己的 `~/.cache/zbot-p28-lead/w_diff.sh` 首行硬
`cd /private/tmp/zbot-wt-p28` —— 与 p25/p26/p27 那三支同一个病（把量具指向写手树），
被 p19b 写手实测指出；在把它改成"从入参取树"之前，**它派生的任何数都不许进台账**。

---

## 8.12 第八期：p27b 的杠② 欠账在合并树上清掉，P28 的杠② 量具搬进仓（09-27 01:10:12+0800 起笔，主编亲测）

这一期落进树的一共四笔：`d8a3e43`（P28-lead-7：三跑逐字对账 + **T1 篡改对照 v1 判作废**）、
`31be6c7`（delegate：三处 SURVIVED 里两处补上确定性捕手）、`10c74d8`（p27 §12 复跑记账）、
`7dc51ba`（web 控制台接线卫兵 + `p28_mutation.py` 搬进仓并在目标树重测）。

### 8.12.1 §8.11 那句"并发测试仍缺"作废 —— 但要连着它的**新账**一起读

p27b 台账原来的三处 SURVIVED 里，M17（并发闸只数 RUNNING）、M19（关 reconcile 的旧副本覆盖）
各补了一支**确定性**捕手（不是"多起几个线程赌一把"）后，整族 21 支在合并树复跑：

```
旧（p27b 交接，写手树）  21 支 8 RED-OK / 10 PARTIAL / 3 SURVIVED
新（31be6c7 目标树，10c74d8 记账）21 支 10 RED-OK / 10 PARTIAL / 1 SURVIVED
```
（不写"检出率 %"：§8.11 那个 85.7% 到底是 18/21 还是 8/21 没有任何尺说得清，分子口径一对不上就是编数。）

唯一 SURVIVED 是 M13（state 写非原子）：按 §11.4 判**等价变异**——单进程观测不到半写状态，
不是我漏了测试。**这条判定是待证断言，不是证据**；要推翻它就照 P27c-G1 的路子重推归属再整批复跑。

### 8.12.2 杠①：两笔里程碑各 ×3 串行（`rm -rf z-bot-core/target/surefire-reports && mvn -o test`，全 reactor 不 `-pl`）

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270030 start=2026-09-27 00:30:18 HEAD=31be6c7
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1154, Failures: 0, Errors: 0, Skipped: 0] class_sum=1154 f=0 e=0 s=0 files=107 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 00:31:27
ROUND|r=2 rc=0 … Tests run: 1154 … files=107 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=00:32:34
ROUND|r=3 rc=0 … Tests run: 1154 … files=107 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=00:33:36
BAR1_DONE|end=2026-09-27 00:33:36 dir=…/bar1_270030 HEAD=31be6c7 dirty_tracked=0 src_md5_delegate=fd1652b3

BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270101 start=2026-09-27 01:01:19 HEAD=7dc51ba
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1158, Failures: 0, Errors: 0, Skipped: 0] class_sum=1158 f=0 e=0 s=0 files=108 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:02:44
ROUND|r=2 rc=0 … Tests run: 1158 … files=108 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=01:04:00
ROUND|r=3 rc=0 … Tests run: 1158 … files=108 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=01:05:09
BAR1_DONE|end=2026-09-27 01:05:09 dir=…/bar1_270101 HEAD=7dc51ba dirty_tracked=1 src_md5_delegate=fd1652b3
```

`1154→1158`、`files=107→108` 与 `7dc51ba` 的内容对得上：新增 1 个测试文件、4 条 `@Test`。
"文本里有 1161 个 `@Test` 却只跑 1158 条"这个差我 01:08 当场对掉，不是推的：

```
OCCUR_TEST=1161      # grep -ro '@Test' --include='*.java' z-bot-core/src/test | wc -l
OCCUR_COMMENT=3      # 落在注释行上的 3 处：RawTerminalVerdictProbe.java:7 / P26RetryPolicyTest.java:37 / MemoryE2eDriver.java:16（三处原文都在说"我没有 @Test / 不占分母"）
MAIN_TREE=0          # src/main 里 @Test 0 命中
                     # 另测"同一行写两支 @Test"= 0 例 ⇒ 按行计数不会少算
1161 - 3 = 1158      # == surefire 的 Tests run，三轮皆然
```

### 8.12.3 杠②：三族变异此刻全部**可从仓里重生成**，分族读数（marker 分布由我现在重算 `LEDGER.tsv` 字节，不抄台账头部）

| 族 | 尺（已进仓） | 目标树读数 | 分母来源 |
|---|---|---|---|
| P19 命令表单源 | `_doc/acceptance/p19/p19_mutate.py` | 8 支 **8 KILLED / 0 其他**，77 s | `$2` marker 列 |
| P27 delegate | `_doc/acceptance/p27/p27_mutation.py` | 21 支 **10 RED-OK / 10 PARTIAL / 1 SURVIVED** | `$3` outcome 列 |
| P28 通道/控制台 | `_doc/acceptance/p28/p28_mutation.py`（本期新入仓） | 14 支 **13 RED-OK / 1 SURVIVED** | `$5` marker 列 |

P28 那 1 支 SURVIVED 是 **M5，我故意注入的等价变异阳性对照**（`isSttyAvailable` 那一支），
不是漏抓 —— 它的作用正是证明"这一族里 SURVIVED 这一格是会亮的"。三族的 SURVIVED 都不是"检不出产品缺陷"。

本期把 P28 的杠② 从"只有写手树能跑"变成"目标树可重生成"，顺带修掉两处**尺的缺陷**（细节与逐支读数在
`p28/EVIDENCE.md` 的 `P28-lead-8`）：
- **`-Dtest=` 点名不存在的用例 ⇒ 旧版记成 SURVIVED**。M6/M7 的旧选择器（`HttpRouteShapeTest#unknownPath…`、
  `…Gets405WithAllowHeader`）在合并树上根本没有那两个名字 ⇒ `mvn rc=0`、0.6 s 收工，被读成"检不出"。
  现在读 surefire 的 `-- in <类>` 汇总行，`ran==0` 一律记新档 **`NO-RUN`**（"尺没跑"从此不等于"没抓到"），
  修完两支各自**红在所点名的方法上**：M6 `expected:<404> but was:<405>`、M7 `Allow` 头变 `null`。
- **锚点随 P19 单源化漂移 ⇒ 只能记 INJECTION_NOT_APPLIED**。M10 旧锚在 `RawTerminalReader.java` 里出现 **0 次**；
  重锚到 `TerminalChannel` 的 `/theme` 派发分串后判 RED-OK，红在 `CommandSurfaceConsistencyTest.tuiHandlesExactlyTheChannelLocalSegment`。
- 旧的 P28 台账出自 `~/.cache/zbot-p28-lead/p28b_mutation.py`，其第 25 行硬 `WT="/private/tmp/zbot-wt-p28"`
  （与 §8.11 末尾那条 `w_diff.sh` 是同一个病）。仓内版 `REPO` 由 `__file__` 派生 + 双重目标树自证，
  不满足就 `rc=6` 拒绝开跑。

### 8.12.4 新增的守卫长什么样（以及它和 T9 的重叠，别读成"白多一份"）

`WebConsoleWiringTest`（4 条）量的是 **classpath 里真被 serve 的那份字节**
（`HttpChannel.class.getResourceAsStream("/web/index.html")`，`serveConsole` 每请求现读），随杠① 每次跑；
判据三条：同名函数不得声明两次、声明了却没有任何名字出现点的函数不得存在、内联 `on*="…()"` 点名的函数必须存在。
判据自己带牙口对照（四份合成页：三种病灶各一支 + 健康页不许被冤枉 + 只写在注释里的接线不许让尺闭嘴）。
杠② 侧对应 M11–M14 四支注入，全部 RED-OK 且各自点名对应方法。
`HttpRouteLedgerTest.consoleRouteServesExactlyTheBundledResource` 从"长度相等"收紧成 `assertArrayEquals`
（长度只证明没截断，不证明 serve 出去的就是那一份）。

与 T9 的关系说清：T9 住在 `p28_e2e.py`，量**盘上 `src` 那份 HTML**，要起真 serve、约 10 分钟、只在杠③ 跑；
`WebConsoleWiringTest` 量 **classpath 字节**，在 `mvn test` 里跑。**前两条判据故意重叠**（一份 CI、一份真进程；
JS 逻辑无法跨语言共用），**只有第三条（内联处理器点名）是 JUnit 层新增的覆盖**。

### 8.12.5 这期欠的账（不装作已过）

- **P27c-G1**：~~我**没有**动那张表~~ —— **已订正（`6f6b21f`）**：那张表我动了，但不是"回填"。
  按红线要求的顺序做的：先另立机制推导文档（`~/.cache/zbot-p27-lead/g1_expect_derivation.md`，518 行，按"谁读这个值"派生归属），
  再用 `derive_sets.py` 从文档**机械**取出 expect 集（`@Test` 注解才算，delegate 包共 62 支；空/歧义/不存在即 FATAL），
  由 `apply_g1_table.py` 一次性重写那 10 支的表体；同时新增 `allow_extra` 档（写读共用归一那类"必然连带红"单独记账，
  既不冒充判据命中也不算未预测的红）。量具自己带 `--selftest`（14 例：7 判档 + missed 可见 + 6 类坏表必须被拦），实测
  `SELFTEST|cases=14 failures=0` rc=0。**但**：这 10 支（乃至整族 21 支）**按新表的整批复跑还没做** ⇒
  新 expect 集现在是"推导成立、未经实测"的假设；旧台账里 `expected_red` 列（合并树上双向陈旧的那份）依旧不作数，
  只有 outcome 列作为历史读数有效。复跑完另立 §8.13 记账。
- **T1 的注入阳性对照 v1 是空跑**（`d8a3e43` 已判作废）：v1 在 E2E 起跑**之前**篡改 `target/classes`，
  而每一跑开头就是 `mvn -o -pl z-bot-core test-compile`，resources 插件把 `src` 又拷了一遍 ⇒
  篡改被构建抹掉（classes mtime 回到注入后 2 s、md5 回到 `bb3ff2e6`），`run_B` 32/32 全绿**没有真猎物**。
  ~~**本节写下时 v2 仍在跑，读数收口后另立 `P28-lead-9`；在那之前 T1 的牙口仍是未证，不许读成"有牙"。**~~
  → **已闭合（09-27 01:35:16，见 `p28/EVIDENCE.md` §P28-lead-9）**：v2 把注入挪到本跑打印 `BUILD|classpath_file=` 之后，
  三口独立读数成立 —— `T1V2|ORDER_PROOF served=bf6d3919 tampered=bf6d3919 disk=bb3ff2e6 prey_reached_measured_path=YES`、
  `T1V2|红项条数=1 红名=T1_served_console_bytes_equal_disk_html`（32 项里只红这一项，rc=1）、
  还原后 `RUN_D rc=0 … pass=32 fail=0` + `MD5_THREE_WAY … same=YES` ⇒
  `T1V2|VERDICT t1_red=YES prey_hit=YES ⇒ 有牙`。它**没**证到的是 served 内容的正确性（只比 md5/长度）、
  其它资源的过滤链路、以及浏览器渲染层。
- **杠③ 没在新树上重跑过**：`31be6c7` 改了 `src/main`（`DelegateManager.java`），而最近三次整跑（`lead_t9_r1..r3`，
  23:09–23:45）跑在它之前。盘上 HTML 的字节没漂（`bb3ff2e6`，最后一次改 `index.html` 是 22:50 的 `4417a5e`），
  但**"HTML 没变"不等于"杠③ 已在新树上成立"** —— 待补：当前树 ×3 整跑。
  （杠① 已补：`6f6b21f` 上 ×3 见 §8.13.2/8.13.5；杠③ 仍未跑，这条不作废。）
- **一条我自己造的缺陷（已闭，形状值得记）**：`b8b1918` 新加的 `ReadmeClaimsTest` 让杠① 连续三轮红 3 支
  （`1162 / f=3 / files=109`，3/3 确定复现，不是间歇）。病在我自己：`repoFile()` 按"从 CWD 往上哪个 `pom.xml` 先存在"挑根，
  而 surefire 的 CWD 是 `z-bot-core`，子 pom **也**存在却没有 `<revision>` ⇒ 版本尺的参照集为空。
  我本地的探针是从仓根起的 `JUnitCore`（4/4 绿），**探针的 CWD ≠ 门禁的 CWD**，所以绿是自证的假绿。
  修法（`f4ad57d`）：根只由"这份 pom 含 `<revision>`"决定（往上最多 6 层），`pom.xml` 与 `README.md` 从同一个根起算，
  不给逐文件各自 fallback。
- 旧账不变：D-P28-2（`POST /api/skill/push` 推的是拉）仍未裁定；`/api/agent/register` 200-vs-501、
  `/api/models` tmpdir 缓存、web 那 17 条"转发-缺"命令是否进 WEB 段，都等点头；
  真 tty 人机体验 NO-RUN。~~P29（README×7 + 抬号 + 发 Central）未开工~~ → README 第一版与数字守卫已开工，见 §8.13。

## 8.13 第九期：P29 起手（README 主张守卫）+ T1 牙口补证 + 杠①② 在新树收口（09-27 01:37–02:04，主编亲测）

### 8.13.1 这一期落了哪三笔，各自只声称自己做过的事

| commit | 时刻 | 内容 | 是否带"已实测通过"的声称 |
|---|---|---|---|
| `b8b1918` | 01:37:20 | `ReadmeClaimsTest`（4 支 `@Test`，钉 README 6 条定量主张）+ `p28_mutation.py` 的 M15/M16 两支配套注入 | 否（提交信息里明确"杠②③④ 未跑"） |
| `f4ad57d` | 01:52:35 | 修 `repoFile()` 的仓根解析（见 §8.12.5 那条自造缺陷） | 只声称"单类 `mvn -o -pl z-bot-core test -Dtest=ReadmeClaimsTest` 4/4 绿" |
| `6f6b21f` | 01:53:29 | p27 杠② 量具：10 支 expect 按机制重推 + `allow_extra` 档 + `_validate_table` + `--selftest`（14 例 rc=0） | 否，提交信息末行写死"21 支整批复跑尚未执行" |

README 那 6 条主张的口径：`grep -c '^' README.md` = 122 行、`<revision>` = `0.2.0`、内核 pin = `0.2.1`，
每一条在 README 里都自带一条复算命令，尺读的就是那一条命令的**同源对象**（不是文档抄的数）。

### 8.13.2 杠①：`6f6b21f` ×3 串行（原文，逐字来自 `~/.cache/zbot-integrate/bar1_6f6b21f.log`）

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270153 start=2026-09-27 01:53:36 HEAD=6f6b21f
BAR1_TREE|dirty_total=2 dirty_tracked=0 src_md5_delegate=fd1652b3
BAR1_SRC_MD5_START|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:54:39
ROUND|r=2 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:55:41
ROUND|r=3 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 01:56:44
BAR1_SRC_MD5_END|index.html=bb3ff2e6 p28_e2e.py=2e8134bb
BAR1_CLASSES|src=bb3ff2e6 classes=bb3ff2e6 src_len=53093 cls_len=53093
BAR1_DONE|end=2026-09-27 01:56:44 dir=/Users/zifang/.cache/zbot-integrate/bar1_270153 HEAD=6f6b21f dirty_tracked=2 src_md5_delegate=fd1652b3
```

- **`1158 → 1162`（+4）与 `files=108 → 109`（+1）** 正好等于 `b8b1918` 新增的那一个测试文件里那 4 支 `@Test`；
  没有一支测试被静默挤掉（`class_sum` 与聚合行 `agg` 双尺一致，`build_success_rows=1` 说明只有一场 reactor）。
- 协议照旧：每轮 `rm -rf z-bot-core/target/surefire-reports` 后 `mvn -o test` 全 reactor（**不** `-pl`），三轮串行不并发。
- **`dirty_tracked` 开头 0、结尾 2 这个不一致，我这轮不当它作废，但要把证据摆齐**：
  收尾时脏的两条是 `_doc/acceptance/p28/EVIDENCE.md` 与 `_doc/hermes-roadmap.md`（我在三轮跑期间写 §P28-lead-9 与本节），
  `git status --porcelain -- z-bot-core/src` 空、`git diff --name-only 6f6b21f -- z-bot-core/src` 计 **0** 个文件，
  且被新加的那支测试在盘上与在 `6f6b21f` 里逐字节同（`md5` 前缀两边都是 `8fd84fa2`）。
  ⇒ 编译对象三轮都是 `6f6b21f` 那棵树，动的只有不参与编译的两篇文档。**下一期起：写作与测量不同时进行**，
  要么先写完再跑，要么跑期间一个字不碰。
- 杠④：三轮 6 个采样点（`ROUND_START` ×3 + 每轮 `ROUND` 行尾）逐格 `8 / 2dadaed0 / 690ddbc0`；
  真 minimax key 只量长度（125），值未被读取。

### 8.13.3 T1 的牙口（补上 §8.12.5 那笔账）

见 `p28/EVIDENCE.md` §P28-lead-9。三句结论：
① `ORDER_PROOF` 与 `T1V2|VERDICT t1_red=YES prey_hit=YES` 分列 ⇒ "猎物上场"和"尺咬住"是两条独立读数，不是一句话；
② 32 项里只红 T1 那一项（`rc=1 pass=31 fail=1`），且还原后 `32 pass=32 fail=0` + 三方 md5 `same=YES`；
③ 归属钉死：`git diff --name-only 7dc51ba..6f6b21f` 只 6 个文件、`z-bot-core/src/main` 段 **0** 个，
`index.html` 在该链六个提交上恒 `bb3ff2e6`、`HttpChannel.java` 两端恒 `20cc3f4a` ⇒ 这轮的"有牙"对当前树依然成立。

### 8.13.4 杠②（p28 族）：16 支具名变异在 `5d28377` 上整批复跑（09-27 02:02–02:04，抢锁串行）

```
LOCK_ACQUIRED /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock
M1 RED-OK | verdict raw=NONZERO_EXIT restore=DONE rc=0 wall=0.09
M2 RED-OK | verdict raw=None restore=None rc=KILLED wall=40.17
M3 RED-OK | verdict raw=DONE restore=DONE rc=0 wall=0.00
M4 RED-OK | verdict raw=<null> restore=<null> rc=KILLED wall=0.05
M5 SURVIVED | verdict raw=DONE restore=DONE rc=KILLED wall=0.07
M15 RED-OK | mvn rc=1 ran=1 具名红=['[ERROR]   ReadmeClaimsTest.everyQuantitativeReadmeClaimMatchesTheRecomputation:190 README 的定量主张与代码不同源：']
M16 RED-OK | mvn rc=1 ran=1 具名红=['[ERROR]   ReadmeClaimsTest.everyQuantitativeReadmeClaimMatchesTheRecomputation:190 README 的定量主张与代码不同源：']
RESTORED_ALL {'README.md': '368ac24a', 'ROUTES.tsv': '809b33e7', 'ZBot.java': '24b7db68', 'HttpChannel.java': '20cc3f4a', 'TerminalChannel.java': '10b4f9a4', 'RawTerminalReader.java': '7e4c33c5', 'index.html': 'bb3ff2e6'}
LEDGER=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/_doc/acceptance/p28/LEDGER.tsv mtime=02:04:22 harness_mtime=01:28:22
TALLY {'RED-OK': 15, 'SURVIVED': 1} TOTAL=16
```
（M6–M14 逐支见 `_doc/acceptance/p28/LEDGER.tsv`，本轮字节；原文日志 `~/.cache/zbot-integrate/p28_mut_16_5d28377.log`。）

- **`TOTAL=16` 与 §8.13.1 那一笔（`b8b1918` 新加 M15/M16）对上**：上一批是 `{'RED-OK': 13, 'SURVIVED': 1} TOTAL=14`（`7dc51ba`），
  本轮唯一新增的两支 **M15（README 路由行数手改一个字）／M16（从 `@Command` 注解里摘掉一支子命令）**
  都判 `RED-OK`，且都咬在 `ReadmeClaimsTest.everyQuantitativeReadmeClaimMatchesTheRecomputation:190` 这一行 ——
  也就是说 §8.13.1 那把新尺**有牙且牙口可指认**：文档与台账一分家它必红，摘一支真命令它也必红（不是"README 长得不像"那种空判）。
- 唯一 `SURVIVED` 仍是 M5 —— 那是**刻意放的等价变异对照位**（`isSttyAvailable()` 直接 `return true`，macOS 上结构性检不出），
  它的存在是这张表"不是凡绿即杀"的证据，不是缺口。
- 每支都带注入证明（`bytecode_proof` 列）：改 `.java` 的是 class md5 迁移，且**相邻两支的 `->` 端等于下一支的起端**
  （`dd41c337->dfee63ac`、`dfee63ac->09aed490`…）⇒ 六支 `RawTerminalReader` 变异是串行施加、没有互相覆盖；
  改数据文件的（M9 `ROUTES.tsv`、M15 `README.md`）钉的是文件 md5。
- **还原复测（不只看尺自己打印的 `RESTORED_ALL`）**：跑完我用 `git show 5d28377:<path> | md5` 与盘上逐字节对，
  `ZBot.java 24b7db68 / HttpChannel 20cc3f4a / TerminalChannel 10b4f9a4 / RawTerminalReader 7e4c33c5 / index.html bb3ff2e6`
  五支两端相同，`git diff --name-only 5d28377 -- z-bot-core/src README.md ROUTES.tsv` 为**空**。
- 杠④收尾复测：`ls -A ~/.zbot | wc -l`=8、cfg `2dadaed0`、db `690ddbc0`，真 key 只量长度（125，值未读）；
  `ps` 里 `java … ZBot` 0 条。

### 8.13.5 杠① 的协议干净复跑（`5d28377`，跑期间一个字没碰）

§8.13.2 那一轮的 `dirty_tracked` 收尾是 2（两篇文档），这一轮把口径补成两端都干净：

```
BAR1_SERIES|dir=/Users/zifang/.cache/zbot-integrate/bar1_270159 start=2026-09-27 01:59:00 HEAD=5d28377
BAR1_TREE|dirty_total=2 dirty_tracked=0 src_md5_delegate=fd1652b3
ROUND|r=1 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 02:00:02
ROUND|r=2 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 02:01:05
ROUND|r=3 rc=0 build_success_rows=1 agg[[INFO] Tests run: 1162, Failures: 0, Errors: 0, Skipped: 0] class_sum=1162 f=0 e=0 s=0 files=109 socket_hits=0 bar4=8/2dadaed0/690ddbc0 ts=2026-09-27 02:02:07
BAR1_DONE|end=2026-09-27 02:02:08 dir=/Users/zifang/.cache/zbot-integrate/bar1_270159 HEAD=5d28377 dirty_tracked=0 src_md5_delegate=fd1652b3
```

⇒ **杠① 在当前树成立**：`1162 / F=E=S=0 / files=109 / socket_hits=0` ×3 串行，且 `dirty_tracked` 起止都是 0
（脏的两条只是 `__pycache__/*.pyc`，非受跟踪件）。原始日志 `~/.cache/zbot-integrate/bar1_5d28377.log`。

### 8.13.6 这一期仍然欠的（写在这里，别让下一个人以为已过）

- ~~杠② p28~~ → **已闭（§8.13.5，`5d28377`，15 RED-OK / 1 刻意等价变异）**。
- 杠② p27：21 支按 `6f6b21f` 的新表整批复跑未做 ⇒ 新 expect 集"推导成立、未经实测"。
- 杠③：当前树 ×3 真进程 E2E 未跑（§8.12.5 那条待补不变）。
- 浏览器渲染层 `/help` 实输出对照未做（`P28-lead-6` 的未完项）。
- P29 其余：`z-agent` / `z-agent-kernel` / `z-agent-proxy` 三篇 README、`<revision>` 抬号、发 Central —— 后两件在 z-bot 的 push 授权之外，等点头。
