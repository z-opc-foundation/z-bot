#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P15b —— `sessions_column_alignment.md` 的保鲜尺（兼表格生成器）。

这张表是 roadmap §8 那条已入账欠账的还账物（"她 46 列、逐列注明 要/不要/占位"）。
能手抄维持的东西一定会烂掉，所以 **列名、行序、声明行号由本脚本从权威参照仓机械摘出**，
只有语义/判定/消费者/理由是人写的。

三个方向的钉法：

  A. 上游（她）：从 `~/.hermes/hermes-agent/hermes_state.py` 的
     `CREATE TABLE IF NOT EXISTS sessions` 机械摘列声明，与文档表格**逐行按序**比对。
     多一列 / 少一列 / 列名漂了 / 语义格没引用自己的声明行号 ⇒ 退出码非 0。
  B. 我们侧（schema）：凡"我们现有对应列"写 `有 X` 的行，X 必须在
     `SchemaMigrations.requiredColumns()` 的 head 声明里（Java 源码机械解析）。
  C. 我们侧（消费者）：
     · `判定=要`   ⇒ "消费者"格里的每个 Java 类名必须在 `src/main/java` 下真存在
                     （`git ls-files` 打到同名 .java）；§6 的类名清单必须与表内一致。
     · `判定=占位` ⇒ 整行必须带 `P\\d+` 期号，且该期号在 `_doc/001_arch/hermes-roadmap.md` §5 真排了期，
                     禁写"以后再说/待定/TBD"。
     · `判定=不要` ⇒ 理由必须带"实测"串（不许凭印象判"我们真没有"）。

参照仓不在 / HEAD 漂了 / 锚点读数变了 / 任一参照集摘出来是空 ⇒ **FATAL(2)**，绝不"跳过算通过"。

用法：
    python3 _doc/005_testing/acceptance/p15b/p15b_check.py              # 只校（杠① 复跑用这个）
    python3 _doc/005_testing/acceptance/p15b/p15b_check.py --emit-table # 重生成表格块（幂等，不动散文）
"""

import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
DOC = os.path.join(HERE, "sessions_column_alignment.md")
ROADMAP = os.path.join(REPO, "_doc", "001_arch", "hermes-roadmap.md")
SCHEMA_JAVA = os.path.join(REPO, "z-bot-core", "src", "main", "java",
                           "com", "zifang", "z", "bot", "store", "SchemaMigrations.java")

# ===== 参照仓锚点（09-26 实测钉死；漂了就说明对账的不是同一份东西）=====
REF_CANDIDATES = [os.path.expanduser("~/.hermes/hermes-agent")]
REF_EXPECTED_HEAD = "cbc1054e2"
REF_EXPECTED_LINES = 9503
REF_EXPECTED_CREATE_TABLE = 22
REF_EXPECTED_SESSIONS_START = 872
REF_EXPECTED_SESSION_COLS = 46

BEGIN = ("<!-- BEGIN TABLE：以下 46 行由 `python3 _doc/005_testing/acceptance/p15b/p15b_check.py --emit-table` "
         "从 hermes_state.py 机械生成，列名勿手改 -->")
END = "<!-- END TABLE -->"

# 摘列名的机械规则（文档 §1 引用同一段文字，两处必须一致）：
#   1) 定位 `^   *CREATE TABLE IF NOT EXISTS sessions   *(` 的行号 S；
#   2) 从 S+1 起逐行，遇 strip() 以 `)` 开头的行即语句结束；
#   3) 每行 strip() 后非空、且不以 FOREIGN KEY/PRIMARY KEY/UNIQUE/CHECK 开头，
#      用 ^([a-z_]+)   ([A-Z]+) 取列名 —— 表约束不算列。
COL_RE = re.compile(r"^([a-z_]+)\s+([A-Z]+)")
CONSTRAINT_RE = re.compile(r"^(FOREIGN KEY|PRIMARY KEY|UNIQUE|CHECK)\b", re.I)
DDL_START_RE = re.compile(r"^\s*CREATE TABLE IF NOT EXISTS sessions\s*\(")
ROW_RE = re.compile(r"^\|\s*([a-z_]+)\s*\|")
PLACEHOLDER_RE = re.compile(r"\bP\d+\b")
VERDICTS = ("要", "不要", "占位")
BANNED = ("以后再说", "待定", "TBD", "看情况")
JAVA_TOKEN_RE = re.compile(r"\b([A-Z][A-Za-z0-9]{2,})\b")
JAVA_NOISE = {"HDB", "SQL", "JSON", "TEXT", "INTEGER", "REAL", "NULL", "FTS", "DDL", "DML", "EVIDENCE"}
SIX_LIST_RE = re.compile(r"\*\*本期被点名\*\*[^`]*((?:`[A-Za-z]+`[、））]*[^`]*)+)")

FATAL = []
RED = []


def out(*args):
    print(*args, flush=True)


# ==================================================== A. 上游：机械摘她的列声明

def reference_repo():
    for p in REF_CANDIDATES:
        if os.path.isfile(os.path.join(p, "hermes_state.py")):
            return p
    FATAL.append("参照仓 hermes_state.py 找不到（试过：%s）" % ", ".join(REF_CANDIDATES))
    return None


def ref_head(path):
    r = subprocess.run(["git", "-C", path, "rev-parse", "--short", "HEAD"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        FATAL.append("参照仓 %s 不是 git 检出：%s"
                     % (path, r.stderr.decode("utf-8", "replace").strip()))
        return None
    return r.stdout.decode().strip()


def extract_hermes_sessions(path):
    """[(列名, 声明行号, 原声明行)] —— 任一锚点不对即 FATAL 并返回 None。"""
    f = os.path.join(path, "hermes_state.py")
    try:
        with open(f, encoding="utf-8") as fh:
            src = fh.read()
    except OSError as e:
        FATAL.append("读不了 %s：%s" % (f, e))
        return None

    lines = src.split("\n")
    n_lines = len(lines) - 1 if (lines and lines[-1] == "") else len(lines)
    if n_lines != REF_EXPECTED_LINES:
        FATAL.append("hermes_state.py 行数 %d != 钉住的 %d ⇒ 参照已漂（不许拿 wheel 当基准）"
                     % (n_lines, REF_EXPECTED_LINES))
        return None
    n_create = len(re.findall(r"CREATE (?:VIRTUAL )?TABLE", src))
    if n_create != REF_EXPECTED_CREATE_TABLE:
        FATAL.append("CREATE (VIRTUAL )?TABLE 命中 %d != 钉住的 %d" % (n_create, REF_EXPECTED_CREATE_TABLE))
        return None

    start = None
    for i, l in enumerate(lines, start=1):
        if DDL_START_RE.match(l):
            start = i
            break
    if start is None:
        FATAL.append("找不到 `CREATE TABLE IF NOT EXISTS sessions (`")
        return None
    if start != REF_EXPECTED_SESSIONS_START:
        FATAL.append("sessions DDL 起始行 %d != 钉住的 %d" % (start, REF_EXPECTED_SESSIONS_START))
        return None

    cols = []
    i = start
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith(")"):
            break
        if stripped and not CONSTRAINT_RE.match(stripped):
            m = COL_RE.match(stripped)
            if not m:
                FATAL.append("无法解析列声明 %s:%d: %r" % ("hermes_state.py", i + 1, stripped))
                return None
            cols.append((m.group(1), i + 1, stripped))
        i += 1

    if not cols:
        FATAL.append("从 hermes_state.py:%d 摘到 0 列 ⇒ 空参照集会比对出假满分，绝不判通过" % start)
        return None
    if len(cols) != REF_EXPECTED_SESSION_COLS:
        FATAL.append("摘到 %d 列 != 钉住的 %d 列" % (len(cols), REF_EXPECTED_SESSION_COLS))
        return None
    return cols


# ==================================================== 表的内容（机器出列名，人出判定）

V = {}


def _set(name, sem, ours, verdict, consumer, why):
    V[name] = (sem, ours, verdict, consumer, why)


SS = "`StateStore`"
SS_SC = "`StateStore`、`SessionsCommand`"

_set("id",
     "会话主键；`messages.session_id` 与 `parent_session_id` 都指向它",
     "有 `id`", "要", SS_SC,
     "P2 起就在，是一切外键的锚。实测 head 声明与 `PRAGMA table_info(sessions)` 里都有它")
_set("source",
     "会话来自哪个入口（`cli`/平台名）；她的复合索引把它放在最左（`hermes_state.py:1033`）",
     "有 `source`", "要", SS_SC,
     "有真消费者：`PruneCriteria.where()` 拼 `AND source = ?`，CLI 侧 `--source` 直通。"
     "实测如实记一处缺口 —— 生产三处 `upsertSession` 都走 7 参重载（`SessionManager.java:99/164/224`）"
     "⇒ 落库恒为 DDL 缺省 `cli`，传真值的只有单测（`StateStoreRetentionTest.java:123-125`）；填真值归 P16")
_set("user_id",
     "平台侧用户身份；她 `hermes_state.py:3186` 明写\"agent 存在之前先把 user_id 落进去\"",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "她按 (source,user_id,chat_id,chat_type,thread_id) 建索引做多人路由。实测我们主源码里唯一的用户身份是 "
     "`ChannelBus.Entry.senderId`（内存环形日志，不入库），sessions 无对应列 ⇒ 随 P16 的送达模型一起判")
_set("session_key",
     "跨端稳定的路由键（她的 `idx_sessions_session_key` `hermes_state.py:1030`）",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "`gateway_routing` 表 P15 就建了 `session_key` 列，但实测全仓生产写入方为 0 —— "
     "`CronJob.java:39` 自己承认\"`gateway_routing` 表也还没有生产写入方（只有单测在写）\"⇒ 会话侧再挂一列就是 0 消费者列")
_set("chat_id",
     "消息侧的会话/群 id（`hermes_state.py:3195` 记 \"the chat/room\"）",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "实测 store/session 层 0 命中，只有通道入站报文里的临时字段 ⇒ 落库要等 P16 决定\"一个 state.db 存几个对端\"")
_set("chat_type",
     "单聊/群聊等会话形态（她的复合索引第 4 位，`hermes_state.py:1033`）",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "同上：形态只在通道入站报文里活一次。实测我们没有任何按形态分支的代码 ⇒ 现在加列没有读方")
_set("thread_id",
     "话题/楼层 id（`hermes_state.py:3195` 与 chat_id 并列为 messaging origin）",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "实测主源码 0 处 thread 语义；她自己的 telegram 主题也是另建表（`telegram_dm_topic_bindings` "
     "`hermes_state.py:8712`）⇒ 随 P16")
_set("display_name",
     "对端展示名；`hermes_state.py:2878/3212` 说明它随血统继承",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "实测 `displayName` 只出现在 `ModelCatalogCache.java:202/222` 与 `HttpChannel.java:552`，"
     "都是**模型**的展示名不是人的 ⇒ 我们侧无对应语义；P16 引入对端身份时一并判")
_set("origin_json",
     "消息 Origin 的 JSON 快照（`hermes_state.py:3212` 随子会话继承、`3300` 用 COALESCE 回填）",
     "无", "占位", "P16 解锁（通道身份与路由）",
     "实测主源码 0 处。我们的同类兜底位 `metadata TEXT` 实测**从未被写过**（全仓 0 处读写）⇒ "
     "先让 P16 说清 origin 存什么，否则就是把 JSON blob 当架构")
_set("expiry_finalized",
     "\"到期是否已定稿\"的幂等旗（`set_expiry_finalized` `hermes_state.py:3365-3368`）",
     "无", "不要", "无",
     "她的两步式（先到期→再定稿）是为多端并发收尾。实测我们的终态只有一条路径："
     "`StateStore.java:826` 的 `UPDATE ... WHERE id=? AND ended_at IS NULL` 守卫 + `:844` 的 resume，"
     "幂等已由 WHERE 完成 ⇒ 再加一旗就是两处真相，prune 反而可能不一致")
_set("model",
     "会话用的模型名",
     "有 `model`", "要", SS_SC,
     "head 声明有，`listSessions`/`lineage` 的 SELECT 里真读（`StateStore.java:799/946`），CLI 打印。"
     "实测生产写方传 null（`SessionManager.java:99/164/224`）⇒ 真值接线属 P12，列本身不缺")
_set("model_config",
     "模型参数 JSON blob；她还塞了血统标记 `json_extract(...,'$._branched_from')`（`hermes_state.py:65/77/2855`）",
     "无", "占位", "P14 解锁（血统/压缩）",
     "实测我们没有任何会话级模型参数，参数全在进程配置里。她这一列的第二用途（`_branched_from` 分叉标记）"
     "正撞 P14 的血统交付 ⇒ 归 P14 判，别提前半个")
_set("system_prompt",
     "会话锁定的 system prompt 原文",
     "无", "占位", "P12 解锁（prompt cache 冻结）",
     "实测 system prompt 在 `BotAgent.buildSystemPrompt()`(:1154) 每次现拼、只在内存与 messages 里；"
     "P12 的硬交付是\"两次 chat 的 system prompt 必须 byte-identical\"，那时才需要落库做逐字节对账 ⇒ 现在加列没有读方")
_set("parent_session_id",
     "父会话（分叉/子代理血统），她带 `FOREIGN KEY ... REFERENCES sessions(id)`（`hermes_state.py:919`）",
     "有 `parent_session_id`", "要", SS_SC,
     "P15 四列之一，本期真被读：`StateStore.java:896` 写、`:928/:972` 读血统、`:1466` 孤儿子会话连带、"
     "索引 `idx_sessions_parent`，CLI `sessions lineage` 消费。注：她建了 FK 我们只有索引 —— 见 §5")
_set("started_at",
     "开始时间（她是 REAL epoch 秒）",
     "有 `created_at`", "要", SS_SC,
     "同一语义、不同表示：我们全库统一 `TEXT 'yyyy-MM-dd HH:mm:ss'`（P15 v3 阶梯专门做了归一）。"
     "实测 `listSessions` 按 `updated_at` 排序、prune 按字符串 cutoff 比较，都吃这一列 ⇒ 判定=要（不改名不改型）")
_set("ended_at",
     "结束时间，NULL = 在飞",
     "有 `ended_at`", "要", SS_SC,
     "P15 四列之一。实测真被读：`StateStore.java:859`（`ended_at IS NOT NULL` 判已结束）、"
     "prune 的 cutoff、`stats` 的 `SUM(CASE WHEN ended_at IS NOT NULL ...)`、索引 `idx_sessions_ended`")
_set("end_reason",
     "为什么结束（文本原因）",
     "有 `end_reason`", "要", SS,
     "P15 四列之一。实测 `StateStore.java:826` 写、`:972` 与血统链一起读，CLI `sessions end` 消费")
_set("message_count",
     "会话消息数；她 `hermes_state.py:5478` 随追加自增",
     "有 `message_count`", "要", SS_SC,
     "实测 `StateStore.java:1114` 每次追加带 `AND message_count<?` 单调更新，"
     "prune 的\"在飞/0 消息\"闸门与 `stats` 的 `SUM(message_count)` 都读它")
_set("tool_call_count",
     "工具调用计数；她 `hermes_state.py:5571` 显式 `tool_call_count = tool_call_count + ?`",
     "无", "不要", "无",
     "反范式计数，真相在 `messages.tool_name`。实测我们写路径已把 tool_name/tool_call_id 落库"
     "（`StateStore.java:1040/1095`），要数就 `COUNT(*) ... WHERE tool_name IS NOT NULL GROUP BY session_id`；"
     "且实测主源码**没有任何**按会话数工具调用的读方 ⇒ 加了既无消费者又要背计数一致性")
_set("input_tokens",
     "输入 token 档",
     "有 `tokens`（input/output 合并一档）", "要", SS_SC,
     "我们只有合并档 `sessions.tokens`，实测 `stats` 读 `SUM(tokens)`（`StateStore.java:1253`）。"
     "语义要、表示合并；拆两档的增量属 P12 记账口径")
_set("output_tokens",
     "输出 token 档",
     "有 `tokens`（input/output 合并一档）", "要", SS_SC,
     "同上。实测写路径 `StateStore.java:711` 真写这一列，生产调用恒传 0 ⇒ 真值来源属 P12")
_set("cache_read_tokens",
     "prompt cache 命中读档",
     "无", "不要", "无",
     "实测**数据源不存在**：`javap com.zifang.z.agent.kernel.types.TokenUsage` 只给 "
     "`getPromptTokens/getCompletionTokens/getTotalTokens` 三个 getter ⇒ 加了必是恒 0 列（红线 2）。"
     "要它得先在内核加字段，那是 P12 的前置，不是本期的列")
_set("cache_write_tokens",
     "prompt cache 写入档",
     "无", "不要", "无",
     "同 `cache_read_tokens`：实测内核 `TokenUsage` 无此档 ⇒ 无数据源，恒 0 列（红线 2）")
_set("reasoning_tokens",
     "推理 token 档",
     "无", "不要", "无",
     "实测内核 `TokenUsage` 无 reasoning 档，且主源码 `git grep -in 'reasoning'` 0 命中 ⇒ 无数据源")
_set("cwd",
     "会话工作目录（她的沙箱/仓库定位）",
     "无", "不要", "无",
     "实测 `git grep -in '\\bcwd\\b' z-bot-core/src/main/java` 0 命中；我们的工作目录是**进程级**由 profile 派生"
     "（`BotConfig.resolveWorkspaceDir`，见 `AgentOptions.java:99-101`、`BotAgent.java:1594`）⇒ 会话级列恒等，0 消费者")
_set("git_branch",
     "会话所在 git 分支名",
     "无", "不要", "无",
     "实测主源码唯一的 git 调用是 `CheckpointManager.java:70/139` 的 `rev-parse HEAD`，取的是 commit sha "
     "且落在 checkpoint 自己的目录里，不属会话语义 ⇒ 无数据源也无读方")
_set("git_repo_root",
     "会话所在仓库根路径",
     "无", "不要", "无",
     "实测 `git grep -in 'repoRoot|git_repo_root'` 主源码 0 命中；仓库根由 checkpoint 子系统按需解析，"
     "与会话生命周期不同轴 ⇒ 0 消费者")
_set("billing_provider",
     "计费侧供应商",
     "有 `provider`", "要", SS_SC,
     "同一语义、我们的列名是 `provider`（P2 起就在），实测在 `listSessions`/`lineage` 的 SELECT 里被读"
     "（`StateStore.java:799/946`）⇒ 判定=要；生产写方传 null，真值接线归 P12")
_set("billing_base_url",
     "计费侧的 endpoint",
     "无", "不要", "无",
     "实测 `git grep -in 'baseUrl|base_url'` 命中的全是**配置项**（`BotConfig`）而非会话事实："
     "一个 profile 一个 endpoint ⇒ 会话级列恒等，0 消费者")
_set("billing_mode",
     "计费模式（订阅/按量等）",
     "无", "占位", "P12 解锁（token/成本分档）",
     "实测主源码 0 处 billing 语义；模式与成本台账是同一次记账的产物，单拆一列既无数据源也无读方 ⇒ 与成本五列同归 P12")
_set("estimated_cost_usd",
     "估算成本",
     "无", "占位", "P12 解锁（token/成本分档）",
     "实测 store/session 层 `cost|usd` 0 命中；成本依赖定价表与 token 分档，二者都在 P12 交付 ⇒ 硬堆就是红线 2")
_set("actual_cost_usd",
     "实际成本",
     "无", "占位", "P12 解锁（token/成本分档）",
     "同上：真实账单回填的前提是 P12 的记账闭环 + 供应商回执，本期两者皆无（实测 0 命中）")
_set("cost_status",
     "成本可信度状态（估算/已核实/缺失）",
     "无", "占位", "P12 解锁（token/成本分档）",
     "这一列是 `estimated vs actual` 的派生旗；P12 之前两个值都不存在 ⇒ 旗也无从置位（实测 0 命中）")
_set("cost_source",
     "成本从哪来（她 `hermes_state.py:962` 在 model_usage 侧另有一份）",
     "无", "占位", "P12 解锁（token/成本分档）",
     "实测她同时出现在 sessions(:906) 与 session_model_usage(:962)，我们只有后者一半"
     "（`StateStore.java:1544` 真写）⇒ 会话侧归 P12 与台账一起判")
_set("pricing_version",
     "定价表版本（她 `hermes_state.py:4191` 用 COALESCE 保留旧值）",
     "无", "占位", "P12 解锁（token/成本分档）",
     "实测主源码 0 处 pricing 语义；她存这一列是为了\"定价改了之后老账能重算\"，前提是已经有账 ⇒ 归 P12")
_set("title",
     "会话标题",
     "有 `title`", "要", SS_SC,
     "P2 起就在。实测真被读写：`StateStore.java:699` 写（非空缺省\"新会话\"）、CLI `sessions list` 打印。"
     "她的\"base #N 派号\"是 P14 分叉时的标题规则，属语义扩展不加列")
_set("api_call_count",
     "会话累计 API 调用数",
     "有 `api_calls`", "要", SS_SC,
     "同一语义、我们的列名是 `api_calls`。实测 `stats` 读 `SUM(api_calls)`（`StateStore.java:1254`）；"
     "生产写方恒传 0 ⇒ 真值来源属 P12")
_set("handoff_state",
     "跨端转交的进行中状态（她有专索引 `idx_sessions_handoff_state` `hermes_state.py:1034`）",
     "无", "不要", "无",
     "实测 `git grep -in 'handoff' z-bot-core/src/main/java` 只有 1 处命中，且是 `SchemaMigrations.java` "
     "的注释自己点名\"没加\"—— 代码 0 处；我们也没有\"把在飞会话搬到另一平台\"的功能。"
     "§5 的 P15 计划点名过这三列，实测无处可接，且路线图 §5 没有任何一期排\"跨端转交\"⇒ 判不要，不是后移")
_set("handoff_platform",
     "转交目标平台",
     "无", "不要", "无",
     "同 `handoff_state`：实测主源码 0 处，无一期排它 ⇒ 不是后移，是不做")
_set("handoff_error",
     "转交失败原因",
     "无", "不要", "无",
     "同上：没有转交动作就没有\"转交失败原因\"可存（实测 0 命中）")
_set("compression_failure_cooldown_until",
     "压缩失败后的冷却截止时间",
     "无", "占位", "P14 解锁（血统/压缩）",
     "实测压缩现在是**进程内**事实：`CompressorEngine` 只有 `compressCount/lastSummary` 两个字段"
     "（`context/CompressorEngine.java:43-44`），没有失败冷却；P14 的硬交付里写着\"失败冷却入库\"⇒ 归 P14")
_set("compression_failure_error",
     "压缩失败原因快照",
     "无", "占位", "P14 解锁（血统/压缩）",
     "同上：实测 `BotAgent.applyCompression`(:367) 失败只进日志、跨进程看不见 ⇒ 与冷却期同属 P14 一次交付")
_set("compression_fallback_streak",
     "连续无效压缩计数（防抖；她 `hermes_state.py:3878-3887` 读）",
     "无", "占位", "P14 解锁（血统/压缩）",
     "实测主源码 `git grep -in 'streak'` 0 命中；防抖计数与冷却是同一件事的两半，拆开加会造出恒 0 列 ⇒ 归 P14")
_set("profile_name",
     "这一行属于哪个 profile（她一库多 profile）",
     "无", "不要", "无",
     "我们是**一 profile 一库**：`state.db` 由 `--config-dir`（= profile）推导（实测 "
     "`SessionsCommand.java:46-49` 的解析优先级 `--db > agent.state.db > -Dzbot.state.db > <configDir>/state.db`）"
     "⇒ 列内值恒等于库名本身，0 消费者")
_set("rewind_count",
     "回退次数；她 `hermes_state.py:6548` 无条件 `+1`（:6505 注明\"回退了也加\"）",
     "无", "不要", "无",
     "实测主源码 `git grep -in 'rewind'` 0 命中：我们没有\"把会话倒回第 N 轮\"的语义，"
     "最接近的是 `checkpoint/CheckpointManager`（按 commit sha 恢复工作区，不属会话）⇒ 无数据源，判不要")
_set("archived",
     "软归档旗（列在 `hermes_state.py:918`；她的 prune 与 list 都按它过滤）",
     "有 `archived`", "要", SS_SC,
     "P15 四列之一。实测真被读：`StateStore.java:1014` 写、`:1380` 的 `AND archived = 0` 是 list 缺省隐藏、"
     "`:1401` 批量归档、`stats` 的 `SUM(CASE WHEN archived=1 ...)`，CLI `sessions archive/--all` 消费")


# ==================================================== B. 我们侧的机械事实

def our_sessions_columns():
    try:
        with open(SCHEMA_JAVA, encoding="utf-8") as fh:
            java = fh.read()
    except OSError as e:
        FATAL.append("读不了 SchemaMigrations.java：%s" % e)
        return None
    m = re.search(r'm\.put\("sessions",\s*Arrays\.asList\((.*?)\)\)', java, re.S)
    if not m:
        FATAL.append("SchemaMigrations.java 里找不到 head 的 sessions 列声明块")
        return None
    cols = re.findall(r'"([a-z_]+)"', m.group(1))
    if not cols:
        FATAL.append("head 的 sessions 列声明块解析出 0 列 ⇒ 空参照集不判通过")
        return None
    return cols


def java_class_exists(cls):
    """`src/main/java` 下有没有同名 .java —— 用 git ls-files 打，不看 mtime 也不猜。"""
    r = subprocess.run(["git", "-C", REPO, "ls-files"],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        FATAL.append("git ls-files 失败：%s" % r.stderr.decode("utf-8", "replace").strip())
        return False
    files = [f for f in r.stdout.decode().split("\n") if f]
    return any(f.endswith("/" + cls + ".java") and "/src/main/java/" in f for f in files)


def roadmap_phases():
    if not os.path.isfile(ROADMAP):
        FATAL.append("路线图不存在：%s（占位期号无处对账）" % ROADMAP)
        return None
    with open(ROADMAP, encoding="utf-8") as fh:
        text = fh.read()
    ph = set(re.findall(r"\*\*P(\d+)[a-z]?\b", text))
    if not ph:
        FATAL.append("路线图 §5 里一个期号都摘不出来 ⇒ 空参照集不判通过")
        return None
    return ph


def consumer_classes(rows):
    """要 行消费者格里的 Java 类名（去重排序）。"""
    got = set()
    for r in rows:
        if r[3] == "要":
            for c in JAVA_TOKEN_RE.findall(r[4]):
                if c not in JAVA_NOISE:
                    got.add(c)
    return got


# ==================================================== C. 表格生成 / 校验

def render_table(hermes_cols):
    def esc(s):
        # 单元格里的裸 `|` 会把 markdown 行劈成 7 格（实测被本尺的格数检查抓到过）
        return s.replace("|", "\\|")

    lines = [
        "| 她的列名 | 她的语义（引 hermes_state.py:行号） | 我们现有对应列 | 判定 | 消费者 | 理由 |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for name, ln, _decl in hermes_cols:
        if name not in V:
            FATAL.append("判决表里没有 %s 这一列的条目（生成器缺项）" % name)
            return None
        sem, ours, verdict, consumer, why = V[name]
        cell_sem = "%s（声明 `hermes_state.py:%d`）" % (sem, ln)
        lines.append("| %s | %s | %s | %s | %s | %s |"
                     % (name, esc(cell_sem), esc(ours), verdict, esc(consumer), esc(why)))
    return lines


def emit_table(hermes_cols):
    if not os.path.isfile(DOC):
        FATAL.append("文档不存在，无法只替换表格块：%s" % DOC)
        return 2
    with open(DOC, encoding="utf-8") as fh:
        text = fh.read()
    if BEGIN not in text or END not in text:
        FATAL.append("文档里找不到 BEGIN/END TABLE 哨兵 ⇒ 不许整篇重写")
        return 2
    body = render_table(hermes_cols)
    if body is None:
        return 2
    pre, rest = text.split(BEGIN, 1)
    _old, post = rest.split(END, 1)
    new = pre + BEGIN + "\n\n" + "\n".join(body) + "\n\n" + END + post
    with open(DOC, "w", encoding="utf-8") as fh:
        fh.write(new)
    out("已机械生成 %d 行 ⇒ %s" % (len(hermes_cols), os.path.relpath(DOC, REPO)))
    return 0


def split_row(s):
    """按**未转义**的 `|` 切格；`\\|` 是格内文字。"""
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", s.strip().strip("|"))]


def parse_doc_table():
    """[(列名, 语义, 我们现有对应列, 判定, 消费者, 理由)] —— 只认 6 格表体行。"""
    if not os.path.isfile(DOC):
        FATAL.append("文档不存在：%s" % DOC)
        return None
    with open(DOC, encoding="utf-8") as fh:
        text = fh.read()
    rows = []
    for ln, line in enumerate(text.split("\n"), start=1):
        s = line.strip()
        if not ROW_RE.match(s):
            continue
        cells = split_row(s)
        if len(cells) != 6:
            FATAL.append("%s:%d 表格行有 %d 格，不是 6 格：%r"
                         % (os.path.relpath(DOC, REPO), ln, len(cells), cells[0]))
            return None
        if cells[0] == "她的列名":
            continue
        rows.append(cells)
    if not rows:
        FATAL.append("文档里一张表都没解析到 ⇒ 空参照集不判通过")
        return None
    return rows


def check(hermes_cols, rows, our_cols, phases):
    names_doc = [r[0] for r in rows]
    names_ref = [c[0] for c in hermes_cols]

    if len(names_doc) != len(names_ref):
        RED.append("行数不符：文档 %d 行 vs 参照 %d 列" % (len(names_doc), len(names_ref)))
    set_doc, set_ref = set(names_doc), set(names_ref)
    missing = [c for c in names_ref if c not in set_doc]
    extra = [c for c in names_doc if c not in set_ref]
    if missing:
        RED.append("文档少列（她有、表里没有）：%s" % ", ".join(missing))
    if extra:
        RED.append("文档多列（表里凭空多出）：%s" % ", ".join(extra))
    dup = sorted({c for c in names_doc if names_doc.count(c) > 1})
    if dup:
        RED.append("文档列名重复：%s" % ", ".join(dup))
    for idx, (a, b) in enumerate(zip(names_doc, names_ref)):
        if a != b:
            RED.append("第 %d 行列名/行序漂了：文档=%r 参照=%r" % (idx + 1, a, b))
            break

    line_of = dict((n, ln) for n, ln, _ in hermes_cols)
    counts = {}
    for r in rows:
        col, sem, ours_cell, verdict, consumer, why = r
        tag = "%s[%s]" % (col, verdict)
        counts[verdict] = counts.get(verdict, 0) + 1
        if verdict not in VERDICTS:
            RED.append("%s 判定 %r 不在 要/不要/占位 之内" % (tag, verdict))
            continue

        cited = set(int(x) for x in re.findall(r"hermes_state\.py:(\d+)", sem))
        if col in line_of and line_of[col] not in cited:
            RED.append("%s 没引用自己的声明行 hermes_state.py:%d（防抄串行）" % (tag, line_of[col]))

        if ours_cell.startswith("有"):
            named = re.findall(r"`([a-z_]+)`", ours_cell)
            if not named:
                RED.append("%s 声称『我们现有对应列=%s』却没写出列名" % (tag, ours_cell))
            elif not [n for n in named if n in our_cols]:
                RED.append("%s 声称的列 %s 不在 head 的 sessions 声明里（%s）"
                           % (tag, "/".join(named), ",".join(our_cols)))

        if verdict == "要":
            cls = [c for c in JAVA_TOKEN_RE.findall(consumer) if c not in JAVA_NOISE]
            if not cls:
                RED.append("%s 判定=要 但消费者格里没有 Java 类名：%r" % (tag, consumer))
            for c in cls:
                if not java_class_exists(c):
                    RED.append("%s 消费者 %s 在 src/main/java 下不存在（表里写了个不存在的类）" % (tag, c))
        elif verdict == "占位":
            got = PLACEHOLDER_RE.findall(" ".join(r))
            if not got:
                RED.append("%s 判定=占位 但整行没有 P\\d+ 期号" % tag)
            for ph in got:
                if ph[1:] not in phases:
                    RED.append("%s 点名的 %s 在路线图 §5 里没有排期" % (tag, ph))
            for b in BANNED:
                if b in consumer or b in why:
                    RED.append("%s 判定=占位 却写了禁用语 %r" % (tag, b))
        elif verdict == "不要":
            if "实测" not in why:
                RED.append("%s 判定=不要 但理由里没有『实测』证据串" % tag)

    # §6 的类名清单必须与表内一致（防"改表不改清单"）
    with open(DOC, encoding="utf-8") as fh:
        text = fh.read()
    m = SIX_LIST_RE.search(text)
    table_classes = consumer_classes(rows)
    if not m:
        RED.append("文档 §6 找不到『本期被点名』的类名清单行")
    else:
        listed = set(re.findall(r"`([A-Za-z]+)`", m.group(1)))
        if listed != table_classes:
            RED.append("§6 类名清单与表内不一致：只出现在 §6=%s 只出现在表里=%s"
                       % ("/".join(sorted(listed - table_classes)) or "-",
                          "/".join(sorted(table_classes - listed)) or "-"))
    return counts, table_classes


def main():
    argv = sys.argv[1:]
    emit = "--emit-table" in argv

    ref = reference_repo()
    hermes_cols = None
    if ref is not None:
        head = ref_head(ref)
        if head is not None:
            if head != REF_EXPECTED_HEAD:
                FATAL.append("参照仓 HEAD=%s != 钉住的 %s ⇒ 参照已漂，本表全部结论需重算"
                             % (head, REF_EXPECTED_HEAD))
            else:
                hermes_cols = extract_hermes_sessions(ref)

    if emit:
        if hermes_cols is None:
            out("===== FATAL（失去前提，拒绝生成表格）=====")
            for x in FATAL:
                out("  FATAL: %s" % x)
            return 2
        return emit_table(hermes_cols)

    rows = parse_doc_table()
    our_cols = our_sessions_columns()
    phases = roadmap_phases()
    if FATAL:
        out("===== FATAL（失去前提，不判通过）=====")
        for x in FATAL:
            out("  FATAL: %s" % x)
        return 2
    if hermes_cols is None or rows is None or our_cols is None or phases is None:
        out("===== FATAL（参照集解析失败）=====")
        for x in FATAL:
            out("  FATAL: %s" % x)
        return 2

    out("参照: %s @ %s ⇒ 机械摘到 %d 列（DDL 起始 hermes_state.py:%d，末列 :%d）"
        % (ref, REF_EXPECTED_HEAD, len(hermes_cols), REF_EXPECTED_SESSIONS_START, hermes_cols[-1][1]))
    out("文档: %s ⇒ 表 %d 行" % (os.path.relpath(DOC, REPO), len(rows)))
    out("我们: head sessions %d 列 = %s" % (len(our_cols), ",".join(our_cols)))
    counts, table_classes = check(hermes_cols, rows, our_cols, phases)
    out("判定分布: %s（合计 %d）"
        % (" ".join("%s=%d" % (v, counts.get(v, 0)) for v in VERDICTS), sum(counts.values())))
    out("要 行的消费者类: %s（%d 个）" % (" ".join(sorted(table_classes)), len(table_classes)))

    if RED:
        out("\n===== RED（表与实测不一致）=====")
        for x in RED:
            out("  RED: %s" % x)
        out("\nPASSED: no")
        return 1
    out("\nPASSED: yes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
