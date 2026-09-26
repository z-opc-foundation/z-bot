# EVIDENCE — p26a  LLM 用量核算与失败恢复

- 工单：`~/.cache/zbot-p26-lead/dispatch_p26a.md`
- 仓：`/private/tmp/zbot-wt-p26`  分支 `w8-p26`  起点 `d23cd0d`
- 对标（只读）：`~/.hermes/hermes-agent` @ `cbc1054e2`
- 开工时间戳（`date` 现取）：`2026-09-26 15:48:09 +0800`（`~/.zbot` T0 同批）
- 本文档规则：每节 `复算命令` → `实测`（原样输出）→ 判词。`STATUS: 未跑` 合法，编读数非法。

> 注：`.gitignore:5` = `*.log` ⇒ 所有决定性读数原样贴在本文档内，日志文件只作过程件。

---

## §0 第 0 步：对工单假设的逐条复算

STATUS: DONE（见下）

### 0.1 仓/分支/工作树起点

```
$ git rev-parse --abbrev-ref HEAD ; git rev-parse --short HEAD ; git status --porcelain | head -30
w8-p26
d23cd0d
(空)
```
判：起点即工单所述 `d23cd0d`，工作树干净。

### 0.2 `llm/` 面积与文件清单

```
$ wc -l z-bot-core/src/main/java/com/zifang/z/bot/llm/*.java
     236 .../KeyPoolLlmProvider.java
      61 .../LlmRouter.java
     226 .../ModelCatalogCache.java
     172 .../ResilientLlmProvider.java
     695 total
```
判：工单 4 文件 695 行 ⇒ **证实**（逐文件行数一致）。

### 0.3 决定性负向：无抖动 / 无陈旧看门狗 / 无 cache 维度

```
$ git grep -n -E 'jitter|Random' -- 'z-bot-core/src/main/java/com/zifang/z/bot/llm' ; rc=1
$ git grep -n -E 'stale|idle watchdog|陈旧' -- 'z-bot-core/src/main/java/com/zifang/z/bot/llm' ; rc=1
$ git grep -n -E 'cache_read|cached_tokens|cacheRead|prompt_cache' -- 'z-bot-core/src/main' ; rc=1
```
判：三条全 **零命中（rc=1）** ⇒ 工单"没有抖动 / 流式没有陈旧看门狗 / usage 归一里 cache 一条都没有"**证实**。

### 0.4 退避是线性（不是指数）

实测码：`ResilientLlmProvider.java:94`
```java
long wait = retryAfterMs(e) > 0 ? retryAfterMs(e) : backoffMs * (attempt + 1);
```
判：`base*(n+1)` = **线性** ⇒ 工单"退避是 backoffMs * 尝试序号（线性）"**证实**（这条负向不必跑，读的是字节；其后果在第 1 轮单测里用等待序列 `[b,2b,3b]` 钉死）。

### 0.5 测试面基线

```
$ git grep -c '@Test' -- 'z-bot-core/src/test/java/com/zifang/z/bot/llm'
KeyPoolLlmProviderTest.java:15
ModelCatalogCacheTest.java:10
ResilientLlmProviderTest.java:7
$ git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF} END{print s}'
693
```
判：llm 32（15+10+7）/ 全仓 693 ⇒ **证实**。

### 0.6 hermes 侧锚（自算，未抄）

```
$ (cd ~/.hermes/hermes-agent && git rev-parse --short HEAD && wc -l agent/error_classifier.py agent/retry_utils.py agent/reasoning_timeouts.py)
cbc1054e2
    1698 agent/error_classifier.py
     154 agent/retry_utils.py
     226 agent/reasoning_timeouts.py
```
- `retry_utils.jittered_backoff(attempt, base_delay=5.0, max_delay=120.0, jitter_ratio=0.5)`
  ⇒ `min(base*2^(attempt-1), max_delay) + uniform[0, 0.5*delay]`（docstring 原文，:36-58）
- `retry_utils.py:20` `_ZAI_CODING_OVERLOAD_LONG_BACKOFF = (30.0, 60.0, 90.0, 120.0)`，`:31` `_ZAI_CODING_SHORT_ATTEMPTS = 3` ⇒ **限流长/短两档**存在
- `reasoning_timeouts.py:7/:9` STREAM 180s / API_CALL 90s；`:24` 明确 "apply as `max(default, floor)`"、"**FLOOR**"、"Never overrides explicit user config"
判：工单 hermes 锚 **全部证实**（行数 1698/154/226 逐字一致）。

### 0.7 【新增·工单未提】kernel 已提供结构化状态码，bot 侧却没用

```
$ javap -cp ~/.m2/repository/io/github/yuku123/z-agent-kernel-llm/0.2.1/...jar com.zifang.z.agent.kernel.llm.support.LlmException
public class LlmException extends RuntimeException {
  public LlmException(String, String);
  public LlmException(String, String, Throwable);
  public LlmException(String, int, String);
  public String getProvider();
  public java.lang.Integer getHttpStatus();
}
```
（pin 版本读自 `pom.xml:69` `<z-agent-kernel.version>0.2.1</z-agent-kernel.version>`）

判：**产品级发现** —— `LlmException` 带 `Integer httpStatus` 结构化字段，
`LlmHttp.postJson` 非 2xx 时抛 `new LlmException(url, resp.code(), text)`（⇒ 消息形如 `[url] HTTP 429 <body>`），
但 `ResilientLlmProvider.isRetryable()` 只读 `e.getMessage()` 正则，**把结构化字段扔掉了**。⇒ §1 的"分类要能从异常类型/结构化字段判"有现成落点。

### 0.8 【新增·卡口】cache 维度在 kernel 里就被丢了，且 schema 无 spare 列

```
$ javap ... com.zifang.z.agent.kernel.types.TokenUsage
  public TokenUsage(long, long, long); getPromptTokens/getCompletionTokens/getTotalTokens
$ grep -n 'usage.path\|prompt_tokens\|Collections.emptyMap()' z-agent-kernel-llm/.../provider/*.java
OpenAIProvider.java:119-121  prompt_tokens/completion_tokens/total_tokens   （无 prompt_tokens_details）
AnthropicProvider.java:346   new ChatCompletionsResponse(..., Collections.emptyMap())
```
```
$ sed -n '177,181p' z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java
CREATE TABLE IF NOT EXISTS session_model_usage (
  id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL,
  model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER, api_calls INTEGER, ts TEXT NOT NULL)
```
判：两点硬约束 —— ①kernel provider 解析时**不读 cache 字段**且 `providerMetadata` 恒为 `emptyMap()`；
②`session_model_usage` 无 spare 列，加列要动 `SchemaMigrations.java`（工单禁写）。
⇒ §5 的落法：归一函数 + 写路径 + **列在位才写**的探测式持久化，cache 列的 ALTER 进 §未做并给迁移方案。

---

## §1 错误分类表（两条正则 → 可审计表）

STATUS: 未跑

## §2 抖动退避 + 限流阶梯（自定数值 + 实测依据）

STATUS: 未跑

## §3 换模型后重建在飞 system 上下文 + 重置压缩计数

STATUS: 未跑

## §4 流式陈旧看门狗（`max(default, floor)` + 用户可覆盖）

STATUS: 未跑

## §5 usage 归一（含 cache 读/写命中）→ `session_model_usage`

STATUS: 未跑

## §6 杠① 全量单测 ×3 串行

STATUS: 未跑

## §7 杠② 变异注入 `p26_mutation.py`（LEDGER 五档）

STATUS: 未跑

## §8 杠③ 真进程 E2E ≥3 整跑（本地假端点 429 / 人为断流）

STATUS: 未跑

## §9 杠④ `~/.zbot` 三时点一字未动

STATUS: T0 已跑，T1/T2 未跑

| 时点 | `ls -A ~/.zbot \| wc -l` | `md5 -q config.properties \| cut -c1-8` | `md5 -q state.db \| cut -c1-8` | 取自 |
|---|---|---|---|---|
| T0 开工 | 8 | 2dadaed0 | 690ddbc0 | `date` = 2026-09-26 15:48:09 +0800 |
| T1 在飞 | 未跑 | 未跑 | 未跑 | |
| T2 收尾 | 未跑 | 未跑 | 未跑 | |

## §10 安全红线自查

STATUS: 未跑

## §11 明确不做 / §未做

STATUS: 未跑
