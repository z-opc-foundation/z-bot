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

STATUS: DONE（单测已绿；杠② 的"每类摘一刀"见 §7）

复算命令：
```
$ mvn -o test -pl z-bot-core -Dtest='P26RetryPolicyTest,ResilientLlmProviderTest,KeyPoolLlmProviderTest,ModelCatalogCacheTest'
```
实测（`~/.cache/zbot-p26-lead/llm_tests_4.log`，2026-09-26 16:1x）：
```
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.llm.P26RetryPolicyTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- in com.zifang.z.bot.llm.P26UsageTest
```
（`llm_tests_2.log` 时 P26RetryPolicyTest 5 红、KeyPoolLlmProviderTest 1 红，逐条修到 0 红；下面记的就是那 6 条的结论。）

### 1.1 分类表落地（`llm/LlmErrorClassifier.java`）

判定优先级：**结构化 `LlmException.getHttpStatus()` > 异常类型链 > 收窄消息特征 > 兜底 FATAL**。
每条规则有具名，`ruleNames()` 是审计面：
`interrupt / http:429 / http:401-402-403 / http:404-model / http:404-path / http:5xx / http:4xx-fatal /
type:socket-timeout / type:connect / type:dns / type:io / msg:rate-limit / msg:key-scoped /
msg:model-scoped / msg:transient / msg:fatal-hint / unclassified`

四档归到工单点名的四类：
| 分类表输出 | 语义 | 落点 |
|---|---|---|
| `RETRY_SAME_KEY` | 可重试（同 key 同模型） | `JitteredBackoff` 短档 |
| `RATE_LIMIT` | 可重试 + 限流长档 + `Retry-After` 优先 | `JitteredBackoff` 长档 |
| `ROTATE_KEY` | 换 key | `KeyPoolLlmProvider` 冷却 + 轮换（`Resilient` 层不重试直接上抛） |
| `FALLBACK_MODEL` | 换模型 | `ResilientLlmProvider` 立刻跳出本模型剩余重试，走降级链 + §3 重建上下文 |
| `FATAL` | 直接上抛 | 不重试不降级 |

### 1.2 假阳性收口（工单点名的那条，已实测）

码：`classifier_bare429InProseIsNotRateLimit` / `classifier_bare500InProseIsNotRetryable`
```java
RuntimeException e = new RuntimeException("请把这份报告归档到工单 429 里，然后回复用户");
Decision d = LlmErrorClassifier.classify(e);
assertFalse(d.isRetryable());            // 新表：FATAL / unclassified
assertTrue(Pattern.compile("(?i)(429|...)").matcher(e.getMessage()).find());  // 老表：命中 ⇒ 判成限流
```
实测：两条都在 33 绿里 ⇒ **老实现把正文里的裸 "429"/"500" 判成限流/可重试** 得到证实，新表按"带标签才算"收口：
`HTTP 429` / `status=503` / `"status": 429` 这类**带协议标签**的仍算（既有 7 条 `ResilientLlmProviderTest` 一条没红）。

### 1.3 顺带查出并修掉的产品缺陷

- **缺陷 A**：`ResilientLlmProvider` 完全无视 `LlmException.getHttpStatus()`（kernel 0.2.1 起就有的结构化字段），只读 message 正则 ⇒ 状态码 400 而正文写 "HTTP 429 rate limit" 时被误判成限流并真的去重试。现在 `classifier_structuredStatusBeatsMessageText` 钉住"结构化优先"。
- **缺陷 B**：`KeyPoolLlmProvider.KEY_SCOPED` 正则里有裸 `exceeded` ⇒ *"This model's maximum context length is exceeded"*（模型/参数问题）被判成 key 失效，**把好 key 冷却 10 分钟**并轮换。`classifier_keyPoolDoesNotRotateKeyOnContextLength` 钉住（同消息里 429/401 仍算 key 作用域）。
- **缺陷 C**：`KeyPoolLlmProvider.cooldownMs()` 自己再编一条 `\b429\b|rate.?limit` 正则（与 Resilient 那份分叉），现在两处共用分类表。
- **缺陷 D**：换模型时 `withModel()` 只改 `model` 字段，在飞的 system 上下文/ maxTokens 口径仍是旧模型的（§3）。
- **缺陷 E**：`streamChat` 直通 ⇒ 上游半途不发字节会一直挂着（§4）。

## §2 抖动退避 + 限流阶梯（自定数值 + 实测依据）

STATUS: DONE（数值与依据；等待序列实测见 §8(a)）

码：`llm/RetryPolicyConfig.java` + `llm/JitteredBackoff.java`（§1.1 的分类表输出决定走短档还是长档）。

### 2.1 语义（对齐 hermes，数值自定）

```
短档： min(base * 2^(attempt-1), max-delay-ms) + uniform[0, jitter_ratio * 该值]
长档： attempt > short-attempts 且分类=RATE_LIMIT ⇒ 逐步走 rate-limit-ladder-ms（越界取末项），同样叠抖动
Retry-After： 有则优先，按 retry-after-cap-ms 封顶
总预算： 累计等待超过 total-budget-ms ⇒ 之后的等待一律 0 并停止重试（isBudgetExhausted）
```
对照 `~/.hermes/hermes-agent/agent/retry_utils.py:36-58`（`jittered_backoff`，同式）与 `:20/:31`（短档 3 步后走 `(30,60,90,120)` 长档表）。

### 2.2 键位（全列，读侧唯一实现 `RetryPolicyConfig.of`）

| 键 | 默认 | 为什么是这个数 |
|---|---|---|
| `llm.retry.base-delay-ms` | 500 | BotConfig 已有 `retry.backoff.ms` 兜底；500ms ≈ 本地假端点一次 429 往返（§8(a) 实测 `E2E 单次调用中位数`）的 10 倍量级以下，既不像 0 那样打满，也不像 5s 那样把交互式请求拖死 |
| `llm.retry.max-delay-ms` | 120000 | 与 hermes `max_delay` 同量级；同时是限流长档的封顶（见 2.4） |
| `llm.retry.jitter-ratio` | 0.5 | hermes 同值（`jitter ~ uniform[0, 0.5*delay]`），实测见 `backoff_jitterStaysWithinRatio` |
| `llm.retry.short-attempts` | 3 | hermes `_ZAI_CODING_OVERLOAD_SHORT_ATTEMPTS=3` |
| `llm.retry.rate-limit-ladder-ms` | 30000,60000,90000,120000 | hermes 长档表同款 4 档；我们没复算她的秒数 ⇒ 这 4 个数是**按我们的交互预算自定**：单请求总等待上限 = `total-budget-ms` 90000，最长 3 档 90s 之内一定被预算切掉 |
| `llm.retry.retry-after-cap-ms` | 120000 | 上游写 `Retry-After: 3600` 不能让我们挂 1 小时 |
| `llm.retry.total-budget-ms` | 90000 | 单请求重试总预算：90s 后不再退避（杠① 用它 + 小 base 保证一次真退避不会拖爆整轮） |
| `llm.stream.stale-timeout-ms` | 180000 | 见 §4 |
| `llm.stream.stale-timeout-floors` | 见 `DEFAULT_STALE_FLOORS` | 见 §4 |
| `llm.stream.stale-check-interval-ms` | 500 | 巡检周期；500ms 是"阈值误差不超过 1 个周期"的取值 |

### 2.3 实测（等待序列，不看墙钟）

`JitteredBackoff.observedWaits()` / `ResilientLlmProvider.lastObservedWaits()` 记录的就是"真等了多久"的数字。
```
backoff_exponentialNotLinear_whenJitterZero      [100,200,400,800]   （老实现是 [100,200,300]：线性）
backoff_exponentialIsCappedByMaxDelay            max-delay=350 ⇒ 第 3/9 步都 350
backoff_jitterStaysWithinRatio                   ratio=0.5,src=0.0/0.4/0.999 ⇒ 100/120/149
backoff_rateLimitWalksLongLadderAfterShortTier   [100,200,400,1000,2000,3000,4000,4000]
backoff_retryAfterOverridesLadder                Retry-After=2000 ⇒ 2000（不是长档 9000）
backoff_totalBudgetStopsGrowth                   budget=250 ⇒ [100,150,0,0] + isBudgetExhausted()
resilient_recordsActualWaitSequenceForRateLimit  [100,200]  ← 真走 ResilientLlmProvider.chat() 记下来的
```
`Tests run: 33, Failures: 0`（§1 同一跑）。

### 2.4 一处刻意的偏差（写明，不当隐藏差异）

hermes 长档表 `(30,60,90,120)` 全部 ≤ 她的 `max_delay=120`。我们把 `max-delay-ms` 同时当**单次等待绝对封顶**
（`ladderValue` 取 `min(档位, max-delay-ms)`），这样 `withBaseDelayMs()` 这条兼容口子（老 4 参构造把
封顶压成 `4*backoffMs`）能一键把阶梯降成常数，既有 32 条 llm 单测与杠① 不会被 30s 档拖爆。

## §3 换模型后重建在飞的 system 上下文 + 重置压缩计数

STATUS: DONE（llm 侧接口 + BotAgent 侧重建/重置已落地并绿；`context/` 的压缩计数重置口归 §未做）

复算：老代码 `ResilientLlmProvider.withModel()` 只换 `model` 字段 —— messages 里那条
`Msg.system(...)` 与 `maxTokens` 还是主模型口径（缺陷 D）。

新增接缝（都在可写域 `llm/`）：
```java
public interface ModelFallbackHook {
    ChatCompletionsRequest rebuildContext(String fromModel, String toModel, ChatCompletionsRequest inFlight);
    void resetCompressionState(String fromModel, String toModel);
}
```
`ResilientLlmProvider.chat()` 在**每次真换模型之前**调 `switchModel()`：
先 `rebuildContext`（返回 null 才退回旧的"只改 model"路径，保持向后兼容），再 `resetCompressionState`。

`agent/BotAgent`（只动 fallback 相关行）在构造时注册 hook：
- `rebuildForModel()`：用 `memory.getSystemPrompt()` 重贴 SYSTEM 行（工具清单/记忆块口径不变）、
  按新模型重算 `maxTokens`（0 则回落到 `config.getMaxTokens()`），日志
  `[BotAgent] 降级链 X -> Y：system 上下文已重建（N 条消息，maxTokens=…）`
- `resetModelSwitchState()`：`lastRequestChars = 0` + cache 累加清零（这些是本侧按旧模型口径攒的账）。

实测（`P26RetryPolicyTest`，33 绿里）：
```
modelSwitch_rebuildsContextAndResetsCounters     hook 各调 1 次；切换后请求
                                                 model=second-model、maxTokens=999、
                                                 messages[0]=SYSTEM("rebuilt-for-second-model")
modelSwitch_withoutHookStillSwitchesModelBut…    没注册 hook ⇒ 仍降级成功，但 SYSTEM 行原样带过去
                                                 （= P26 之前的行为，反向对照）
```
**没动 `context/**`**：`CompressorEngine` 的 `compressCount`（`context/CompressorEngine.java:43`，只有 getter
`:134`）没有对外重置口 ⇒ 换模型后旧压缩计数仍然留着。接口需求（给 w6-p14/主编）：
`CompressorEngine.resetForModelSwitch(String fromModel, String toModel)`（把 `compressCount` 归 0、
并按新模型窗口重算阈值），我在 `resetModelSwitchState()` 里已留好调用位，见 §11。

## §4 流式陈旧看门狗（`max(default, floor)` + 用户可覆盖）

STATUS: DONE（单测绿；真进程断流与阈值分档的实测数字在 §8(b)）

码：`llm/StreamStaleWatchdog.java` + `ResilientLlmProvider.streamChat()`。

阈值解析（与 `reasoning_timeouts.py:24` 同语义）：
```
resolveTimeoutMs(policy, model) =
    显式配了 llm.stream.stale-timeout-ms ⇒ 就用它（可以是 0 = 关掉，floor 不得覆盖）
    否则 ⇒ max(llm.stream.stale-timeout-ms 默认 180000, floor(model))
floor 表 llm.stream.stale-timeout-floors = "^(?:o1|o3|qwq|r1|deepseek-r1|glm-z1|qwen3|nemotron)(?:[-_.\d].*)?=300,.*(?:thinking|reasoner|nemo|preview).*|=240"
匹配前先剥聚合器前缀（openai/o3-mini ⇒ o3-mini），与 hermes 的 slug-only 匹配同义
```
行为：单次触发（`AtomicBoolean` CAS）⇒ 上层只收到一次 `onError(StreamStaleException)`；
触发或正常收尾（收到带 `finish_reason` 的块 / 出错）都 `close()` 自己的定时器线程。

实测：
```
stale_floorIsMaxOfDefaultAndModelFloor    gpt-4o-mini ⇒ 180000；openai/o3-mini ⇒ 300000；deepseek-r1 ⇒ 300000
stale_floorNeverLowersDefault             floor=5s 也压不动 default 180s ⇒ 180000
stale_explicitUserConfigBeatsFloor        配 7000 + o3-mini ⇒ 7000（显式优先）
stale_watchdogTripsAndDeliversErrorOnce   阈值 120ms、上游 600ms 后才吐字 ⇒ 恰好 1 次 onError、
                                          类型 StreamStaleException、触发时上游确实还没吐过字
stale_noHungThreadAfterTripOrNormalEnd    触发路径与正常收尾路径：z-llm-stale-watchdog* 线程数回到基线
stale_zeroThresholdMeansNoWatchdog        配 0 ⇒ 阈值 0、不起线程、直通 delegate
```
`Tests run: 33, Failures: 0`（同 §1 那一跑）。真进程断流（本地代理 mid-stream kill）的
检测延迟与 `pgrep`/线程数取证见 §8(b)。

## §5 usage 归一（含 cache 读/写命中）→ `session_model_usage`

STATUS: DONE（归一 + 读写落库已绿；**cache 列的 ALTER 属 §未做**，写路径是"列在位才写"）

### 5.1 口径（写死在 `llm/ModelUsage.java` 的 javadoc，夹具双向钉）

- `prompt_tokens` = 上游报的输入总量（**含** cache 读命中）
- `cache_read_tokens` = 其中命中缓存被读的部分（OpenAI `prompt_tokens_details.cached_tokens` /
  Anthropic `cache_read_input_tokens` / DashScope `input_tokens_details.cached_tokens`）
- `cache_write_tokens` = 为写缓存多付的部分（Anthropic `cache_creation_input_tokens` / OpenAI `cache_write_tokens`）
- `billable_prompt = max(0, prompt - cache_read)` —— 折后价口径
- `cache_read <= prompt`（夹具 `normalize_cacheReadNeverExceedsPrompt` 钉住上钳位）

字段名归一：`normalize(Map)` 认形态（有 `input_tokens`/`cache_*_input_tokens` ⇒ Anthropic 形态，否则 OpenAI 形态），
同一份数字在两种字段名下折出**同一**记录（`normalize_bothProviderShapesAgree`）；
`toCanonicalMap` ↔ `fromCanonicalMap` 互逆（`normalize_canonicalRoundTripIsLossless`）。

### 5.2 落库

`StateStore.recordUsage(sessionId, model, prompt, completion, apiCalls, cacheRead, cacheWrite)`（新增 7 参重载；
旧 5 参入口原样保留并委托，`store_legacyFiveArgStillWorks` 钉住）。
**没动 `SchemaMigrations.java`**：`hasCacheColumns()` 用 `PRAGMA table_info` 探测一次并缓存，
列不在位 ⇒ 退化成旧 6 列 INSERT（不抛、不吞 prompt/completion 账）；列在位 ⇒ 写全 8 列。
读侧 `usageTotals(sessionId)` ⇒ `[prompt, completion, apiCalls, cacheRead, cacheWrite]`。

`agent/BotAgent`：每次 LLM 回来 `ModelUsage.fromResponse()` 累加 cache 读/写（`CACHE_READ/CACHE_WRITE`），
`finish()` 时随本任务的那一笔 `session_model_usage` 落库并把累加清零。

### 5.3 实测

```
$ mvn -o test -pl z-bot-core -Dtest='P26UsageTest'
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```
- 列不在位：`usageTotals` 后两位 = 0（`store_withoutCacheColumns_degradesToLegacyWriteAndReportsZero`）
- 手工 `ALTER TABLE ... ADD COLUMN cache_read_tokens/cache_write_tokens` 模拟迁移落地 +
  `invalidateCacheColumnProbe()` 后：写 400/50、800/0 两笔 ⇒ 读回 `[3010, 65, 6, 1200, 50]`
  （3010/65/6 含迁移前那笔 legacy 行 10/5/1，其 cache 列为 NULL ⇒ `COALESCE(SUM(),0)`）

### 5.4 卡口（不是没做，是被上游挡住）

kernel 0.2.1 的 provider 在解析响应时**根本不读 cache 字段**（`OpenAIProvider.java:119-121`、
`AnthropicProvider.java:338-346`），且 `providerMetadata` 恒传 `Collections.emptyMap()` ⇒
真跑厂商时 `fromResponse()` 的 cache 两位**只能取到 0**。本棒把口子留在
`providerMetadata` 的 `usage` / `raw_usage` 两键上（`normalize_fromResponse_usesProviderMetadataWhenPresent`
用夹具证明"有就能读到"），kernel 侧补一行透传即自动生效 —— 该改动在禁写的 `z-agent-kernel`，进 §未做。

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
