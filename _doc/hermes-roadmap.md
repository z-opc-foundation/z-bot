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

### P3 上下文引擎 — Hermes #5
- kernel-agent `ContextEngine` SPI 默认实现 `CompressorEngine`: tokens > max×0.85 触发; 保留 system + 最近 N 轮原文, 中段用辅助模型摘要; 摘要 prompt 强制保留文件路径/代码/决策等"事实承诺"
- 压缩锁(同会话并发压缩只跑一次); 压缩血统写进 metadata
- token 记账: 从 kernel-llm usage 累计到 AgentContext
- prompt-cache 稳定化: 工具 schema 按 name 字典序 + JSON sort_keys 输出
- `/compress here [N] | focus <topic> | --preview`
- 验收: 单测(触发阈值/头尾保护/锁); 真实长会话 E2E 触发一次压缩并继续对话

### P4 checkpoint 影子 git — Hermes #12
- 破坏性操作(write_file/patch/exec 写命令)前对沙箱目录做影子 git 快照(`~/.zbot/checkpoints/store/`, GIT_DIR/GIT_WORK_TREE 分离, blob 去重)
- `/rollback [id]` 恢复; checkpoints 列表/prune
- 验收: 单测(mock git 或真 git); E2E: 写文件→改坏→rollback 恢复

### P5 delegate 子代理 — Hermes #6
- `delegate_task` 工具: spawn 隔离上下文子 BotAgent(共享 provider/凭据, 独立 budget≤父 1/4, 禁 child-only 工具防递归)
- 深度限制(max_spawn_depth) + 并发宽度(max_concurrent_children, 线程池)
- 异步委托: `/background <task>` 落 async_delegations 表, 完成后回投消息; `/agents` 查看在跑子代理
- 验收: 单测(预算限制/深度限制/工具剥离); E2E: 父 agent 委派子 agent 完成两步任务并汇总

### P6 记忆 + 技能 + 人格 — Hermes #8 #13
- `~/.zbot/memories/MEMORY.md` + `USER.md`(.lock 并发保护); `memory` 工具(agent 主动读写) + 系统提示注入
- 记忆写审批: `/memory pending|approve|reject`
- SKILL.md 加载: frontmatter(name/description/version/metadata) + 正文; `~/.zbot/skills/<category>/<name>/SKILL.md`; 对齐 z-opc center 已有的 skill 下发格式
- `/skills` list/view/install; 技能可注册为 slash 命令
- `SOUL.md` 人格文件(默认生成, 可编辑), 注入 system prompt 头部
- 验收: 单测(frontmatter 解析/注入/审批流); E2E: 安装一个 skill 并让 LLM 调用其指引

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
