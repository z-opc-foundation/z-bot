# P15b —— hermes `sessions` 46 列 逐列理由表（要 / 不要 / 占位）

> 还的是 roadmap §8 那条已入账的欠账：P15 只给 `sessions` 加了 4 列（15 列），
> 而"她 46 列、逐列注明 要/不要/占位"这张表一直没有，`store/SchemaMigrations.java`
> 类注释里那句"理由表见本期 notes"指向一个不存在的东西。本期把表补上，并配一把不让它烂掉的尺。
>
> 工单：`~/.cache/zbot-p17/dispatch_p15b.md`。尺：`_doc/acceptance/p15b/p15b_check.py`。
> 四杠读数：`_doc/acceptance/p15b/EVIDENCE.md`。

## 0. 一句话结论

46 列逐列判完：**要 14 / 不要 14 / 占位 18**（计数由尺现算，别信这句人话，命令见 §4）。
**本期新增 0 列**：14 个"要"全部落在我们已有的 15 列之内，剩下 32 列要么被 P12/P14/P16 的前置卡住
（占位，各自点名了哪一期），要么实测下来我们**根本没有那个数据源**（不要）。
在 `store/` 边界内本期进产品代码的改动只有一处：把 `SchemaMigrations` 那句指向空气的注释
改成指向本文件（杠② 注入的正是这一处，见 §6）。

## 1. 这张表怎么复算（列名不许手敲）

**参照仓（只读，不许 `git pull`）** = `~/.hermes/hermes-agent`，钉在 `cbc1054e2`（`pyproject.toml` 自称 0.19.0）。
动笔前复算的三条，与路线图 §7 对账：

| 复算命令（均在 `~/.hermes/hermes-agent` 下） | 钉住的读数 | 本期实测 |
| --- | --- | --- |
| `wc -l hermes_state.py` | 9503 | 9503 ✅ |
| `grep -cE 'CREATE (VIRTUAL )?TABLE' hermes_state.py` | 22 | 22 ✅ |
| `grep -nE '^CREATE TABLE IF NOT EXISTS sessions' hermes_state.py` | 起始 872 | `872:CREATE TABLE IF NOT EXISTS sessions (` ✅ |
| 数 `sessions` 的列声明（下面这段正则） | 46 | 46 ✅ |

**坑（工单点名，本期复核过）**：PyPI 的 `hermes-agent` 0.19.0 **wheel** 里 `hermes_state.py` 只有
7,822 行 / 17 张 `CREATE TABLE`，比这个 git 检出旧；aliyun 镜像更旧。**本表一律以 git 检出为准**，
尺里三条锚点任何一条对不上就 **FATAL 退出**，不会"跳过算通过"。

### 摘列名的机械规则（与尺里 `COL_RE` / `DDL_START_RE` 完全一致）

1. 定位匹配 `^\s*CREATE TABLE IF NOT EXISTS sessions\s*\(` 的行号 `S`（实测 `S=872`）；
2. 从 `S+1` 起逐行，遇到 `strip()` 以 `)` 开头的行即语句结束（实测停在 `:920`）；
3. 每行 `strip()` 后非空、且不以 `FOREIGN KEY|PRIMARY KEY|UNIQUE|CHECK` 开头的算一条列声明，
   用 `^([a-z_]+)\s+([A-Z]+)` 取列名 —— 所以 `:919` 那条 `FOREIGN KEY (parent_session_id)` **不算列**。

复算入口。前两条是**纯 shell、不依赖行号**的独立复算（在 `~/.hermes/hermes-agent` 下跑）：

```bash
# ⇒ 46（数列声明；表约束被排除）
sed -n '/^CREATE TABLE IF NOT EXISTS sessions (/,/^);/p' hermes_state.py \
  | grep -vE '^[[:space:]]*(FOREIGN KEY|PRIMARY KEY|UNIQUE|CHECK)' \
  | grep -E '^[[:space:]]*[a-z_]+ [A-Z]+' | wc -l

# ⇒ id source user_id session_key ... archived（46 个，按声明序）
sed -n '/^CREATE TABLE IF NOT EXISTS sessions (/,/^);/p' hermes_state.py \
  | grep -oE '^[[:space:]]+[a-z_]+ [A-Z]+' | awk '{print $1}'

# 钉到本文件那张表上（逐行按序比对 + 三类判定各自的规则）
python3 _doc/acceptance/p15b/p15b_check.py
# 用参照仓重新生成 §3 的表体（幂等，只动哨兵之间）
python3 _doc/acceptance/p15b/p15b_check.py --emit-table
```

> 刻意**不**在文档里再抄一份 Python 解析实现：两张嘴就会各说各话。文档只写规则文字 + 上面两条
> shell 交叉验证；唯一权威实现是 `p15b_check.py` 里的 `COL_RE` / `DDL_START_RE`。
> 实测过：本尺抓到过我手写理由格里的裸 `|` 把表格劈成 7 格（`git_repo_root` 那行），
> 所以表格体必须由 `--emit-table` 生成，人不许碰。

## 2. 我们侧的基准（实测，不是读代码）

真进程开一个全新库（`mvn -o -pl z-bot-core package -DskipTests` 之后的 jar）：

```bash
java -jar z-bot-core/target/z-bot-core.jar sessions --config-dir <tmp>/cfg version
sqlite3 <tmp>/cfg/state.db 'PRAGMA table_info(sessions);'      # ⇒ 15 列，见下
sqlite3 <tmp>/cfg/state.db 'SELECT version FROM schema_version;'  # ⇒ 3
```

`PRAGMA table_info(sessions)` 实测 15 列，按序：

`id, title, source, model, provider, message_count, tokens, api_calls, created_at, updated_at, metadata, parent_session_id, ended_at, end_reason, archived`

与 `SchemaMigrations.requiredColumns()` 的 head 声明逐字一致（尺的 B 方向就是钉这个）。
其中 `parent_session_id / ended_at / end_reason / archived` 是 P15 加的 4 列。

**本期实测抓出的两条存量事实**（都影响判定口径，如实记）：

- `metadata TEXT`（P2 v1 阶梯建的列）**全仓 0 处读写**：`git grep -n metadata -- 'z-bot-core/src/main/java'`
  在 store 里只命中 `SchemaMigrations.java` 自己的声明与 head 清单，写路径一次都没有。
  ⇒ 它是我们自己违反红线 2 的存量列。**本期不删**：删列要动 `requiredColumns` 与新阶梯，
  而工单只准"加判定=要的列"，DROP 一条可能存过用户数据的列不属于本期授权（见 §7）。
- `sessions` 的 `model / provider / tokens / api_calls` 四列，生产三个写点
  （`SessionManager.java:99 / :164 / :224`）全部传 `null, null, …, 0, 0` ⇒ 列在、读方在（`list`/`stats`），
  **真值来源不在**（只有单测传真值）。这既支撑了 §3 里它们判"要"（语义我们已经收了），
  也把"填上真值"明确推到 P12。

## 3. 逐列理由表（46 行，机械生成）

判定口径：

- **要** = 这个语义我们收在 schema 里（本期或已入账），且消费者格能写出**本期盘上就有**的 Java 类名；
- **不要** = 我们不收，理由必须落到我们语义里为什么不需要，且带**实测**证据（不许凭印象）；
- **占位** = 语义要，但被某个**点名了期号**的前置卡住；写"以后再说"直接判红。

<!-- BEGIN TABLE：以下 46 行由 `python3 _doc/acceptance/p15b/p15b_check.py --emit-table` 从 hermes_state.py 机械生成，列名勿手改 -->

| 她的列名 | 她的语义（引 hermes_state.py:行号） | 我们现有对应列 | 判定 | 消费者 | 理由 |
| --- | --- | --- | --- | --- | --- |
| id | 会话主键；`messages.session_id` 与 `parent_session_id` 都指向它（声明 `hermes_state.py:873`） | 有 `id` | 要 | `StateStore`、`SessionsCommand` | P2 起就在，是一切外键的锚。实测 head 声明与 `PRAGMA table_info(sessions)` 里都有它 |
| source | 会话来自哪个入口（`cli`/平台名）；她的复合索引把它放在最左（`hermes_state.py:1033`）（声明 `hermes_state.py:874`） | 有 `source` | 要 | `StateStore`、`SessionsCommand` | 有真消费者：`PruneCriteria.where()` 拼 `AND source = ?`，CLI 侧 `--source` 直通。实测如实记一处缺口 —— 生产三处 `upsertSession` 都走 7 参重载（`SessionManager.java:99/164/224`）⇒ 落库恒为 DDL 缺省 `cli`，传真值的只有单测（`StateStoreRetentionTest.java:123-125`）；填真值归 P16 |
| user_id | 平台侧用户身份；她 `hermes_state.py:3186` 明写"agent 存在之前先把 user_id 落进去"（声明 `hermes_state.py:875`） | 无 | 占位 | P16 解锁（通道身份与路由） | 她按 (source,user_id,chat_id,chat_type,thread_id) 建索引做多人路由。实测我们主源码里唯一的用户身份是 `ChannelBus.Entry.senderId`（内存环形日志，不入库），sessions 无对应列 ⇒ 随 P16 的送达模型一起判 |
| session_key | 跨端稳定的路由键（她的 `idx_sessions_session_key` `hermes_state.py:1030`）（声明 `hermes_state.py:876`） | 无 | 占位 | P16 解锁（通道身份与路由） | `gateway_routing` 表 P15 就建了 `session_key` 列，但实测全仓生产写入方为 0 —— `CronJob.java:39` 自己承认"`gateway_routing` 表也还没有生产写入方（只有单测在写）"⇒ 会话侧再挂一列就是 0 消费者列 |
| chat_id | 消息侧的会话/群 id（`hermes_state.py:3195` 记 "the chat/room"）（声明 `hermes_state.py:877`） | 无 | 占位 | P16 解锁（通道身份与路由） | 实测 store/session 层 0 命中，只有通道入站报文里的临时字段 ⇒ 落库要等 P16 决定"一个 state.db 存几个对端" |
| chat_type | 单聊/群聊等会话形态（她的复合索引第 4 位，`hermes_state.py:1033`）（声明 `hermes_state.py:878`） | 无 | 占位 | P16 解锁（通道身份与路由） | 同上：形态只在通道入站报文里活一次。实测我们没有任何按形态分支的代码 ⇒ 现在加列没有读方 |
| thread_id | 话题/楼层 id（`hermes_state.py:3195` 与 chat_id 并列为 messaging origin）（声明 `hermes_state.py:879`） | 无 | 占位 | P16 解锁（通道身份与路由） | 实测主源码 0 处 thread 语义；她自己的 telegram 主题也是另建表（`telegram_dm_topic_bindings` `hermes_state.py:8712`）⇒ 随 P16 |
| display_name | 对端展示名；`hermes_state.py:2878/3212` 说明它随血统继承（声明 `hermes_state.py:880`） | 无 | 占位 | P16 解锁（通道身份与路由） | 实测 `displayName` 只出现在 `ModelCatalogCache.java:202/222` 与 `HttpChannel.java:552`，都是**模型**的展示名不是人的 ⇒ 我们侧无对应语义；P16 引入对端身份时一并判 |
| origin_json | 消息 Origin 的 JSON 快照（`hermes_state.py:3212` 随子会话继承、`3300` 用 COALESCE 回填）（声明 `hermes_state.py:881`） | 无 | 占位 | P16 解锁（通道身份与路由） | 实测主源码 0 处。我们的同类兜底位 `metadata TEXT` 实测**从未被写过**（全仓 0 处读写）⇒ 先让 P16 说清 origin 存什么，否则就是把 JSON blob 当架构 |
| expiry_finalized | "到期是否已定稿"的幂等旗（`set_expiry_finalized` `hermes_state.py:3365-3368`）（声明 `hermes_state.py:882`） | 无 | 不要 | 无 | 她的两步式（先到期→再定稿）是为多端并发收尾。实测我们的终态只有一条路径：`StateStore.java:826` 的 `UPDATE ... WHERE id=? AND ended_at IS NULL` 守卫 + `:844` 的 resume，幂等已由 WHERE 完成 ⇒ 再加一旗就是两处真相，prune 反而可能不一致 |
| model | 会话用的模型名（声明 `hermes_state.py:883`） | 有 `model` | 要 | `StateStore`、`SessionsCommand` | head 声明有，`listSessions`/`lineage` 的 SELECT 里真读（`StateStore.java:799/946`），CLI 打印。实测生产写方传 null（`SessionManager.java:99/164/224`）⇒ 真值接线属 P12，列本身不缺 |
| model_config | 模型参数 JSON blob；她还塞了血统标记 `json_extract(...,'$._branched_from')`（`hermes_state.py:65/77/2855`）（声明 `hermes_state.py:884`） | 无 | 占位 | P14 解锁（血统/压缩） | 实测我们没有任何会话级模型参数，参数全在进程配置里。她这一列的第二用途（`_branched_from` 分叉标记）正撞 P14 的血统交付 ⇒ 归 P14 判，别提前半个 |
| system_prompt | 会话锁定的 system prompt 原文（声明 `hermes_state.py:885`） | 无 | 占位 | P12 解锁（prompt cache 冻结） | 实测 system prompt 在 `BotAgent.buildSystemPrompt()`(:1154) 每次现拼、只在内存与 messages 里；P12 的硬交付是"两次 chat 的 system prompt 必须 byte-identical"，那时才需要落库做逐字节对账 ⇒ 现在加列没有读方 |
| parent_session_id | 父会话（分叉/子代理血统），她带 `FOREIGN KEY ... REFERENCES sessions(id)`（`hermes_state.py:919`）（声明 `hermes_state.py:886`） | 有 `parent_session_id` | 要 | `StateStore`、`SessionsCommand` | P15 四列之一，本期真被读：`StateStore.java:896` 写、`:928/:972` 读血统、`:1466` 孤儿子会话连带、索引 `idx_sessions_parent`，CLI `sessions lineage` 消费。注：她建了 FK 我们只有索引 —— 见 §5 |
| started_at | 开始时间（她是 REAL epoch 秒）（声明 `hermes_state.py:887`） | 有 `created_at` | 要 | `StateStore`、`SessionsCommand` | 同一语义、不同表示：我们全库统一 `TEXT 'yyyy-MM-dd HH:mm:ss'`（P15 v3 阶梯专门做了归一）。实测 `listSessions` 按 `updated_at` 排序、prune 按字符串 cutoff 比较，都吃这一列 ⇒ 判定=要（不改名不改型） |
| ended_at | 结束时间，NULL = 在飞（声明 `hermes_state.py:888`） | 有 `ended_at` | 要 | `StateStore`、`SessionsCommand` | P15 四列之一。实测真被读：`StateStore.java:859`（`ended_at IS NOT NULL` 判已结束）、prune 的 cutoff、`stats` 的 `SUM(CASE WHEN ended_at IS NOT NULL ...)`、索引 `idx_sessions_ended` |
| end_reason | 为什么结束（文本原因）（声明 `hermes_state.py:889`） | 有 `end_reason` | 要 | `StateStore` | P15 四列之一。实测 `StateStore.java:826` 写、`:972` 与血统链一起读，CLI `sessions end` 消费 |
| message_count | 会话消息数；她 `hermes_state.py:5478` 随追加自增（声明 `hermes_state.py:890`） | 有 `message_count` | 要 | `StateStore`、`SessionsCommand` | 实测 `StateStore.java:1114` 每次追加带 `AND message_count<?` 单调更新，prune 的"在飞/0 消息"闸门与 `stats` 的 `SUM(message_count)` 都读它 |
| tool_call_count | 工具调用计数；她 `hermes_state.py:5571` 显式 `tool_call_count = tool_call_count + ?`（声明 `hermes_state.py:891`） | 无 | 不要 | 无 | 反范式计数，真相在 `messages.tool_name`。实测我们写路径已把 tool_name/tool_call_id 落库（`StateStore.java:1040/1095`），要数就 `COUNT(*) ... WHERE tool_name IS NOT NULL GROUP BY session_id`；且实测主源码**没有任何**按会话数工具调用的读方 ⇒ 加了既无消费者又要背计数一致性 |
| input_tokens | 输入 token 档（声明 `hermes_state.py:892`） | 有 `tokens`（input/output 合并一档） | 要 | `StateStore`、`SessionsCommand` | 我们只有合并档 `sessions.tokens`，实测 `stats` 读 `SUM(tokens)`（`StateStore.java:1253`）。语义要、表示合并；拆两档的增量属 P12 记账口径 |
| output_tokens | 输出 token 档（声明 `hermes_state.py:893`） | 有 `tokens`（input/output 合并一档） | 要 | `StateStore`、`SessionsCommand` | 同上。实测写路径 `StateStore.java:711` 真写这一列，生产调用恒传 0 ⇒ 真值来源属 P12 |
| cache_read_tokens | prompt cache 命中读档（声明 `hermes_state.py:894`） | 无 | 不要 | 无 | 实测**数据源不存在**：`javap com.zifang.z.agent.kernel.types.TokenUsage` 只给 `getPromptTokens/getCompletionTokens/getTotalTokens` 三个 getter ⇒ 加了必是恒 0 列（红线 2）。要它得先在内核加字段，那是 P12 的前置，不是本期的列 |
| cache_write_tokens | prompt cache 写入档（声明 `hermes_state.py:895`） | 无 | 不要 | 无 | 同 `cache_read_tokens`：实测内核 `TokenUsage` 无此档 ⇒ 无数据源，恒 0 列（红线 2） |
| reasoning_tokens | 推理 token 档（声明 `hermes_state.py:896`） | 无 | 不要 | 无 | 实测内核 `TokenUsage` 无 reasoning 档，且主源码 `git grep -in 'reasoning'` 0 命中 ⇒ 无数据源 |
| cwd | 会话工作目录（她的沙箱/仓库定位）（声明 `hermes_state.py:897`） | 无 | 不要 | 无 | 实测 `git grep -in '\bcwd\b' z-bot-core/src/main/java` 0 命中；我们的工作目录是**进程级**由 profile 派生（`BotConfig.resolveWorkspaceDir`，见 `AgentOptions.java:99-101`、`BotAgent.java:1594`）⇒ 会话级列恒等，0 消费者 |
| git_branch | 会话所在 git 分支名（声明 `hermes_state.py:898`） | 无 | 不要 | 无 | 实测主源码唯一的 git 调用是 `CheckpointManager.java:70/139` 的 `rev-parse HEAD`，取的是 commit sha 且落在 checkpoint 自己的目录里，不属会话语义 ⇒ 无数据源也无读方 |
| git_repo_root | 会话所在仓库根路径（声明 `hermes_state.py:899`） | 无 | 不要 | 无 | 实测 `git grep -in 'repoRoot\|git_repo_root'` 主源码 0 命中；仓库根由 checkpoint 子系统按需解析，与会话生命周期不同轴 ⇒ 0 消费者 |
| billing_provider | 计费侧供应商（声明 `hermes_state.py:900`） | 有 `provider` | 要 | `StateStore`、`SessionsCommand` | 同一语义、我们的列名是 `provider`（P2 起就在），实测在 `listSessions`/`lineage` 的 SELECT 里被读（`StateStore.java:799/946`）⇒ 判定=要；生产写方传 null，真值接线归 P12 |
| billing_base_url | 计费侧的 endpoint（声明 `hermes_state.py:901`） | 无 | 不要 | 无 | 实测 `git grep -in 'baseUrl\|base_url'` 命中的全是**配置项**（`BotConfig`）而非会话事实：一个 profile 一个 endpoint ⇒ 会话级列恒等，0 消费者 |
| billing_mode | 计费模式（订阅/按量等）（声明 `hermes_state.py:902`） | 无 | 占位 | P12 解锁（token/成本分档） | 实测主源码 0 处 billing 语义；模式与成本台账是同一次记账的产物，单拆一列既无数据源也无读方 ⇒ 与成本五列同归 P12 |
| estimated_cost_usd | 估算成本（声明 `hermes_state.py:903`） | 无 | 占位 | P12 解锁（token/成本分档） | 实测 store/session 层 `cost\|usd` 0 命中；成本依赖定价表与 token 分档，二者都在 P12 交付 ⇒ 硬堆就是红线 2 |
| actual_cost_usd | 实际成本（声明 `hermes_state.py:904`） | 无 | 占位 | P12 解锁（token/成本分档） | 同上：真实账单回填的前提是 P12 的记账闭环 + 供应商回执，本期两者皆无（实测 0 命中） |
| cost_status | 成本可信度状态（估算/已核实/缺失）（声明 `hermes_state.py:905`） | 无 | 占位 | P12 解锁（token/成本分档） | 这一列是 `estimated vs actual` 的派生旗；P12 之前两个值都不存在 ⇒ 旗也无从置位（实测 0 命中） |
| cost_source | 成本从哪来（她 `hermes_state.py:962` 在 model_usage 侧另有一份）（声明 `hermes_state.py:906`） | 无 | 占位 | P12 解锁（token/成本分档） | 实测她同时出现在 sessions(:906) 与 session_model_usage(:962)，我们只有后者一半（`StateStore.java:1544` 真写）⇒ 会话侧归 P12 与台账一起判 |
| pricing_version | 定价表版本（她 `hermes_state.py:4191` 用 COALESCE 保留旧值）（声明 `hermes_state.py:907`） | 无 | 占位 | P12 解锁（token/成本分档） | 实测主源码 0 处 pricing 语义；她存这一列是为了"定价改了之后老账能重算"，前提是已经有账 ⇒ 归 P12 |
| title | 会话标题（声明 `hermes_state.py:908`） | 有 `title` | 要 | `StateStore`、`SessionsCommand` | P2 起就在。实测真被读写：`StateStore.java:699` 写（非空缺省"新会话"）、CLI `sessions list` 打印。她的"base #N 派号"是 P14 分叉时的标题规则，属语义扩展不加列 |
| api_call_count | 会话累计 API 调用数（声明 `hermes_state.py:909`） | 有 `api_calls` | 要 | `StateStore`、`SessionsCommand` | 同一语义、我们的列名是 `api_calls`。实测 `stats` 读 `SUM(api_calls)`（`StateStore.java:1254`）；生产写方恒传 0 ⇒ 真值来源属 P12 |
| handoff_state | 跨端转交的进行中状态（她有专索引 `idx_sessions_handoff_state` `hermes_state.py:1034`）（声明 `hermes_state.py:910`） | 无 | 不要 | 无 | 实测 `git grep -in 'handoff' z-bot-core/src/main/java` 只有 1 处命中，且是 `SchemaMigrations.java` 的注释自己点名"没加"—— 代码 0 处；我们也没有"把在飞会话搬到另一平台"的功能。§5 的 P15 计划点名过这三列，实测无处可接，且路线图 §5 没有任何一期排"跨端转交"⇒ 判不要，不是后移 |
| handoff_platform | 转交目标平台（声明 `hermes_state.py:911`） | 无 | 不要 | 无 | 同 `handoff_state`：实测主源码 0 处，无一期排它 ⇒ 不是后移，是不做 |
| handoff_error | 转交失败原因（声明 `hermes_state.py:912`） | 无 | 不要 | 无 | 同上：没有转交动作就没有"转交失败原因"可存（实测 0 命中） |
| compression_failure_cooldown_until | 压缩失败后的冷却截止时间（声明 `hermes_state.py:913`） | 无 | 占位 | P14 解锁（血统/压缩） | 实测压缩现在是**进程内**事实：`CompressorEngine` 只有 `compressCount/lastSummary` 两个字段（`context/CompressorEngine.java:43-44`），没有失败冷却；P14 的硬交付里写着"失败冷却入库"⇒ 归 P14 |
| compression_failure_error | 压缩失败原因快照（声明 `hermes_state.py:914`） | 无 | 占位 | P14 解锁（血统/压缩） | 同上：实测 `BotAgent.applyCompression`(:367) 失败只进日志、跨进程看不见 ⇒ 与冷却期同属 P14 一次交付 |
| compression_fallback_streak | 连续无效压缩计数（防抖；她 `hermes_state.py:3878-3887` 读）（声明 `hermes_state.py:915`） | 无 | 占位 | P14 解锁（血统/压缩） | 实测主源码 `git grep -in 'streak'` 0 命中；防抖计数与冷却是同一件事的两半，拆开加会造出恒 0 列 ⇒ 归 P14 |
| profile_name | 这一行属于哪个 profile（她一库多 profile）（声明 `hermes_state.py:916`） | 无 | 不要 | 无 | 我们是**一 profile 一库**：`state.db` 由 `--config-dir`（= profile）推导（实测 `SessionsCommand.java:46-49` 的解析优先级 `--db > agent.state.db > -Dzbot.state.db > <configDir>/state.db`）⇒ 列内值恒等于库名本身，0 消费者 |
| rewind_count | 回退次数；她 `hermes_state.py:6548` 无条件 `+1`（:6505 注明"回退了也加"）（声明 `hermes_state.py:917`） | 无 | 不要 | 无 | 实测主源码 `git grep -in 'rewind'` 0 命中：我们没有"把会话倒回第 N 轮"的语义，最接近的是 `checkpoint/CheckpointManager`（按 commit sha 恢复工作区，不属会话）⇒ 无数据源，判不要 |
| archived | 软归档旗（列在 `hermes_state.py:918`；她的 prune 与 list 都按它过滤）（声明 `hermes_state.py:918`） | 有 `archived` | 要 | `StateStore`、`SessionsCommand` | P15 四列之一。实测真被读：`StateStore.java:1014` 写、`:1380` 的 `AND archived = 0` 是 list 缺省隐藏、`:1401` 批量归档、`stats` 的 `SUM(CASE WHEN archived=1 ...)`，CLI `sessions archive/--all` 消费 |

<!-- END TABLE -->

## 4. 判定分布（由尺现算，别信本文件里的人话）

```bash
python3 _doc/acceptance/p15b/p15b_check.py | grep -E '判定分布|消费者类'
```

实测输出（`w1-p15b` @ 本期树）：

```
判定分布: 要=14 不要=14 占位=18（合计 46）
要 行的消费者类: SessionsCommand StateStore（2 个）
```

- **要 = 14** 行，**本期被点名**的类：`StateStore`、`SessionsCommand`
  （尺会逐个 `git ls-files '*/src/main/java/**/<类>.java'` 验存在，写了不存在的类直接判红；
  改了表不改这一行也算红）。
- **不要 = 14** 行，每行理由都带"实测"串（尺强制），落在这三类事实上：
  内核没有数据源（`TokenUsage` 无 cache/reasoning 档）、进程级而非会话级（`cwd`/`billing_base_url`/`profile_name`）、
  功能根本不存在（`handoff_*`、`rewind_count`、`tool_call_count`、`expiry_finalized`、`git_branch`、`git_repo_root`）。
- **占位 = 18** 行，按期号聚合：`P12 = 7`、`P14 = 4`、`P16 = 7`。
  尺逐行检查期号**确实排在 roadmap §5 里**（参照集由 `grep -oE '\*\*P[0-9]+[a-z]? ' _doc/hermes-roadmap.md` 同一条规则摘出），
  不许写"以后再说"。

差额账：`46 = 14（要，全在已有 15 列内）+ 14（不要）+ 18（占位）`；已有 15 列里未被任何"要"行认领的只有
`metadata` 与 `updated_at` 两列（前者是 §2 记的 0 消费者存量列，后者是我们自己的时间戳，她没这一档）。

## 5. 三处结构性差异（不是"少列"，是表示不同）

1. **时间型**：她 `started_at/ended_at/compression_failure_cooldown_until` 是 `REAL`（epoch 秒），
   我们全库统一 `TEXT 'yyyy-MM-dd HH:mm:ss'`，并由 P15 v3 阶梯 `p15-timestamp-normalization` 把历史
   ISO `'T'` 格式归一。⇒ 对位成立，改名/改型都不在本期边界内。
2. **外键**：她 `parent_session_id` 带 `FOREIGN KEY … REFERENCES sessions(id)`；我们只有
   `idx_sessions_parent` 索引 + 应用层维护（`StateStore.java:1466` 的孤儿连带清理）。
   我们不建 FK 是刻意的：`DbRecovery` 的坏库重建路径要能在残库上跑，FK 会让"备份 + 重建"变复杂。
3. **合并档**：她的 `input_tokens/output_tokens` 两档我们合成 `tokens` 一档；三档 cache/reasoning
   **不是不想拆，是内核没给数据**（`javap …/TokenUsage` 只有 3 个 getter）。

## 6. 本期进产品代码的东西

**新增列 0 个。** 判定=要的 14 列全部已在 head 声明里（§2 实测 15 列），
所以红线 2（0 消费者的列一律不许加）在本期的正确执行方式就是**一个都不加**。

实际改动只有一处，在工单允许的边界（`store/` 里 `sessions` 的列声明块）内：
`SchemaMigrations` 类注释里那句"理由表见本期 notes"**指向一个不存在的东西** ——
改成指向本文件，并把"哪些暂时不加、为什么"的口径与尺一起钉住。这条改动由
`_doc/acceptance/p15b/p15b_mutation.py` 的注入自证（杠②）；新增单测
`SessionsColumnAlignmentTest` 把"表 ⟷ head 声明 ⟷ 迁移台账"三个方向钉在 `mvn test` 里。

> 为什么不做一把"更大的尺"：把 46 行的表塞进 Java 单测（如 `SessionsColumnHoldsEveryHermesModuleColumn`）
> 会让单测依赖参照仓在场，`mvn -o test` 在别人的机器上就红了。所以分工：
> **Java 单测钉我们侧不变量**（列集、阶梯台账、无新列），**尺钉跨仓对齐**（`p15b_check.py`，需要参照仓）。

## 7. 已知不精确 / 本期没做的事

- 判定=不要的 15 行里，`git grep` 的口径是"主源码 `src/main/java`"；**没扫 kernel 之外**的其它模块
  （`z-bot-desktop-packager`）与前端 `web/index.html`。
- 她的 22 张表本期只对了 `sessions` 一张（工单边界）。`messages`/`session_model_usage` 的列对齐没做。
- `metadata` 这条 0 消费者存量列**仍在库里**（§2）。删它需要一次授权明确的 `DROP COLUMN` 阶梯。
- 本表**不改** roadmap：§8 那条"待办 · P15 未交部分"由主编收口时一并处理（共享文件，避免撞车）。
