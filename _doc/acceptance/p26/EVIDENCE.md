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

STATUS: DONE —— 连续 3 次全绿（`a`/`b`/`c`），三次 `Tests run: 736, Failures: 0, Errors: 0, Skipped: 0`，
独立尺（surefire 目录 67 个 `.txt` / 求和 736）与 @Test 面（736 个可执行 `@Test` 方法）三方对账一致。

### 6.1 旧那批 736 的读数为什么作废（不是"数字不对"，是"归属不到这棵树"）

`~/.cache/zbot-p26-lead/bar1_{1,2,3}.log`（16:12:02 / 16:12:32 / 16:13:01）的 `Tests run: 736` 我原样复算过一遍
（骨架节里第 7 行），它数字没错，但**不能当本期交付的读数**，三条理由：

1. **`target/` 已被杠② 污染过**：16:59–17:02 的变异台账把 26 支变异编进 `z-bot-core/target/classes`
   （每支都 `mvn -o test -pl z-bot-core -Dtest=…`），所以 17:02 之后任何"沿用旧 `target/`"的读数都无法声明
   "测的是 HEAD 那棵树"。⇒ 收口前重建：`mvn -o -q package -DskipTests -pl z-bot-core` RC=0（17:06:39），
   且被测 7 个文件先经 §7.7 的逐字节对账确认等于 HEAD。
2. **协议要求的 `rm -rf z-bot-core/target/surefire-reports` 当时没做**（旧批是"目录里追加"跑法），
   旧文件会替新文件说话。本轮三次每一支开头都先删（删完 `ls` 当场报 `No such file or directory`）。
3. **旧批的 socket 尺是脏的**：`pgrep -f 'zbot-wt-p26'` 会把别的进程的命令行也圈进来，
   量到的"非回环 socket"其实是别人的出网。本轮改成"只圈 command 含 java/maven/surefire 的 PID 再 lsof"，
   并把 `(CLOSED)`（无地址的已关闭残留项）单列，见 §6.3。

### 6.2 三次串行（每次先删报告目录，全 reactor，无 `-pl`）

```
$ cd /private/tmp/zbot-wt-p26 && rm -rf z-bot-core/target/surefire-reports && mvn -o test
#  （socket 尺另起后台采样器，0.2s 一次，只圈 java/maven/surefire 的 PID）
```

| 支 | mvn RC | `Tests run:` 行原样 | `[ERROR]` 行数 | socket 峰值（总 / 非回环） | 独立尺：`ls *.txt \| wc -l` | 独立尺：`grep -h "Tests run:" *.txt` 求和 |
|---|---|---|---|---|---|---|
| `a` | 0 | `[INFO] Tests run: 736, Failures: 0, Errors: 0, Skipped: 0` | 0 | 51 / 0 | 67 | 736 |
| `b` | 0 | `[INFO] Tests run: 736, Failures: 0, Errors: 0, Skipped: 0` | 0 | 57 / 0 | 67 | 736 |
| `c` | 0 | `[INFO] Tests run: 736, Failures: 0, Errors: 0, Skipped: 0` | 0 | 57 / 0 | 67 | 736 |

日志原样在 `~/.cache/zbot-p26-lead/bar1_{a,b,c}.log`（采样 `sock_{a,b,c}.txt`）。三次连续、中间不改动任何东西。
"非回环 socket"这一列：全 reactor 跑完 111–115 个采样点里最大 **0**；
（旧尺在 `b` 支之前测到的 2 条"非回环"实为 `java 76530 … TCP *:* (CLOSED)`——无地址的已关闭残留，
不是出网；把它从尺里剔掉的理由就是这个字符串形状。）

### 6.3 与 @Test 面对账（工单给的 737 需要一处更正）

```
$ git grep -c -E "^[[:space:]]*@Test" -- 'z-bot-core/src/test/java/**/*.java' | awk -F: '{s+=$NF;n++}END{print s" "n}'
736 67
$ ls z-bot-core/target/surefire-reports/*.txt | wc -l ; grep -h "Tests run:" .../*.txt | awk … 
67 ; 736
$ git grep -o -E "@Test" -- 'z-bot-core/src/test/**/*.java' | wc -l
737
$ grep -n "@Test" z-bot-core/src/test/java/com/zifang/z/bot/llm/P26RetryPolicyTest.java | grep -v "^[0-9]*: * *@Test"
37: * <p>每个 @Test 的名字里带它钉的**规则名/档位名**，杠② 变异按这些名字点期望红的用例。</p>
```

⇒ **737 这个数里含 1 条 javadoc 正文里的 "@Test" 字样**（`P26RetryPolicyTest.java:37`，是注释不是注解）。
可执行 @Test 面 = **736**，与 reactor 汇总（736）、逐类求和（736）、报告文件数（67）三把尺全对齐。
工单那句"737 或改动后的新数"按实测更正为 736，并在这里写明差的那 1 条出自哪一行——
**没有为此改任何测试代码**：改尺（用 `^[[:space:]]*@Test`）而不是改被测面。

## §7 杠② 变异注入 `p26_mutation.py`（LEDGER 五档）

STATUS: DONE —— 从 0 起跑，26 支 / 9 族一次跑完，`LEDGER.tsv` 机械落盘 26 行：
**RED-OK 23 / PARTIAL 3 / GREEN-BUT-MUTATED 0 / BROKEN 0 / NO-RUN 0**，
跑前 4 道静态前置 + 跑中 9 条族级阳性对照全 `ran==want`，跑完 7 个被注文件逐字节回到 HEAD（三值对账见 §7.7）。

### 7.1 五档判词与"跑前写死的期望红集"确在脚本里（不是跑完补的）

```
p26_mutation.py:14   期望红集在跑之前写死在 MUTANTS 表里；RED-OK / PARTIAL / GREEN-BUT-MUTATED / BROKEN / NO-RUN
p26_mutation.py:39   RUN_TIMEOUT = 420          # 单支硬上限，超了记 BROKEN
p26_mutation.py:428  rows.append((m, "NO-RUN", …))          # 族级阳性对照不过 ⇒ 整族 NO-RUN
p26_mutation.py:449  return ("BROKEN", "anchor-count=%d" …) # 锚点不唯一 ⇒ 不注入，记 BROKEN
p26_mutation.py:456  return ("NO-RUN", "注入后字节没变（等价改写…）")
p26_mutation.py:466  if len(hit)==len(expected) and expected: verdict="RED-OK"
p26_mutation.py:471  else: verdict="GREEN-BUT-MUTATED"
p26_mutation.py:497  if not rows: print("!! 台账 0 行 ⇒ 不写 …（拒绝产出空台账）")
```
`LEDGER.tsv` 只由 `write_ledger()`（:494）以 `open(LEDGER,"w")` 写，全文没有第二处写它；表头 7 列
`mutant family verdict file prey note reason`，首行是 `# generated-by … stamp= … mutants=26`。

### 7.2 保质期四行（跑前 / 跑后各 `ls -lT LEDGER.tsv p26_mutation.py`，原样粘）

```
# 跑前（17:02 开跑之前，16:5x 拍）：
ls: LEDGER.tsv: No such file or directory
-rw-r--r--@ 1 zifang  wheel  27188 Sep 26 16:59:00 2026 p26_mutation.py
# 跑后：
-rw-r--r--@ 1 zifang  wheel   7535 Sep 26 17:02:38 2026 LEDGER.tsv
-rw-r--r--@ 1 zifang  wheel  27188 Sep 26 16:59:00 2026 p26_mutation.py
```
⇒ 台账 mtime `17:02:38` **晚于**脚本 mtime `16:59:00`，即"这份台账是最新脚本产出的"。
`16:59:00` 就是杠② 开跑前对 `p26_mutation.py` 的最后一次改动（新增 4 支 + `parse_prey` + `mvn()` 三处修法），
之后**没有再动过生成这张台账的脚本**（`md5 -q p26_mutation.py` 现在仍是那次改动的产物，跑前跑后同一 mtime）。
需要点明的一条：17:0x 之后我改过的是**杠③ 的量具** `p26_e2e.py`（判词措辞 `D-1`→`缺陷 F`），
它不参与台账生成，所以保质期尺不被它污染。

### 7.3 注入面覆盖（工单点名的每一面至少一刀）

| 工单点名的注入面 | 变异名（LEDGER 里的行） | 判词 |
|---|---|---|
| 每个错误分类各摘一刀 | `cls_drop_http429` / `cls_drop_rotate_key` / `cls_drop_fallback_model` / `cls_unclassified_becomes_retryable` / `cls_bare_digits_match_again` / `cls_structured_status_ignored` / `keypool_regex_exceeded_returns` / `cls_http5xx_becomes_fatal` | 7 RED-OK + 2 PARTIAL（见 §7.4） |
| 指数 → 线性 | `backoff_exponential_to_linear`、`backoff_ladder_to_linear` | RED-OK ×2 |
| 去掉抖动项 | `backoff_jitter_off` | RED-OK |
| 限流阶梯档位写死 | `backoff_ladder_pinned_first_rung`、`backoff_total_budget_off` | PARTIAL、RED-OK |
| 看门狗 `max(default,floor)` → `default` | `watchdog_floor_dropped_to_default`（另两刀 `watchdog_floor_becomes_ceiling`、`watchdog_explicit_config_ignored`） | RED-OK ×3 |
| usage 归一不加 cache | `usage_openai_cache_read_dropped`、`usage_cache_write_dropped`、`usage_clamp_off`、`usage_billable_no_discount`、`cache_columns_never_written` | RED-OK ×5 |
| 换模型后不重置压缩计数 | `fallback_no_counter_reset`（另 `fallback_no_context_rebuild`、`resilient_no_wait_between_models`、`modelSwitch` 族 `retry-after`/`fallback`） | RED-OK ×3 |

新增 4 支（`cls_http5xx_becomes_fatal`、`backoff_ladder_pinned_first_rung`、
`watchdog_floor_dropped_to_default`、`usage_billable_no_discount`）是 §0 复算时发现的"只有一刀能砍到"的空档，
跑前静态自检通过：`$ python3 p26_mutation.py --check-anchors` ⇒ `ANCHOR-CHECK mutants=26 bad=0 … CHECK_RC=0`。
每族开跑前的阳性对照（不注入也必须真跑到点名的方法）：
```
CONTROL classification rc=0 ran=10 want=10 fails=[] prey_ok=True
CONTROL jitter rc=0 ran=1 want=1 fails=[] prey_ok=True
CONTROL ladder rc=0 ran=4 want=4 fails=[] prey_ok=True
CONTROL retry-after rc=0 ran=1 want=1 fails=[] prey_ok=True
CONTROL fallback rc=0 ran=3 want=3 fails=[] prey_ok=True
CONTROL stale-threshold rc=0 ran=3 want=3 fails=[] prey_ok=True
CONTROL stale-trip rc=0 ran=1 want=1 fails=[] prey_ok=True
CONTROL usage-cache rc=0 ran=4 want=4 fails=[] prey_ok=True
CONTROL usage-store rc=0 ran=1 want=1 fails=[] prey_ok=True
```

### 7.4 三行 PARTIAL 原样（不升档、不掩盖）

```
cls_drop_http429                   rc=1 ran=2 missing=['P26RetryPolicyTest.classifier_keyPoolDoesNotRotateKeyOnContextLength'] extra=[] | md5=disk:e367e30a/git:e367e30a/same=True
cls_unclassified_becomes_retryable rc=1 ran=2 missing=['P26RetryPolicyTest.classifier_rule_unclassifiedIsFatal_andInterruptToo'] extra=[] | md5=disk:e367e30a/git:e367e30a/same=True
backoff_ladder_pinned_first_rung   rc=1 ran=2 missing=['P26RetryPolicyTest.backoff_retryAfterOverridesLadder']        extra=[] | md5=disk:091b7765/git:091b7765/same=True
```
读法：三支变异都把测试打红了（`rc=1`，`extra=[]` 没有意外红），但**期望红集里有一条仍绿** ⇒ 按脚本口径只能记
PARTIAL，不升成 RED-OK。三条缺的那一条我都回到源码/测试里点过名，共同结论是**我的期望红集写宽了**（量具口径），
不是产品没被砍到：

1. `cls_drop_http429` 摘的是 `if (status == 429) return RATE_LIMIT;`（`LlmErrorClassifier` 的状态码分支）。
   缺的 `classifier_keyPoolDoesNotRotateKeyOnContextLength:180` 断言的是
   `isKeyScoped(new RuntimeException("429 rate limit exceeded"))` —— **正文文本**路径（`:154` 那条正则），
   不经过被摘掉的分支 ⇒ 这一支变异在结构上不可能打红它。
2. `cls_unclassified_becomes_retryable` 只改 `FailureClass`（`FATAL`→`RETRY_SAME_KEY`，见 `p26_mutation.py:86-87`），
   rule 标签仍是 `"unclassified"`；缺的 `classifier_rule_unclassifiedIsFatal_andInterruptToo:156-159`
   三条断言全部只读 `getRule()` ⇒ **测不出类变了**。⇒ 这是一条**真被发现的测试断言弱点**
   （名字叫 `IsFatal` 却没钉 FATAL），进 §11 记账；本期不改测试（改了会让已收的杠① / 杠② 两杠读数一起作废，
   且"把红涨档"不是验收）。
3. `backoff_ladder_pinned_first_rung` 把阶梯档位写死成第一档，缺的 `backoff_retryAfterOverridesLadder`
   走的正是"服务端给了 Retry-After ⇒ 覆盖阶梯"这条路（§8.3 实测 `retryAfter=900ms` 采信），
   档位写死被覆盖 ⇒ 该条绿是行为正确，不是漏杀。

按红线"不改为让量具变绿去动被测代码"：我**没有**为了让这三行升档去碰 `src/main`、没碰测试、
也没事后收窄 `prey` 列表（收窄=把宽了的期望改成能对上的答案，那才是迁就量具）。
台账里就留 PARTIAL，并在上面把"宽在哪一条、为什么结构上不可能红"写清。

### 7.5 NO-RUN 通道（本期 0 行，但通道在、且不许用改标来掩盖）

工单要求"不可注入等价体记 NO-RUN，绝不删行、绝不改标 skip"。本期 26 支全部注入成功（没有"等价体"残项），
所以 `NO-RUN=0` 是实测值而不是漏填；两个 NO-RUN 入口都还在代码里（:428 族级对照不过 ⇒ 整族 NO-RUN；
:456 注入后字节没变 ⇒ 该支 NO-RUN 并带三方 md5），空台账也被 :497 拒写。
**曾经真用过 NO-RUN**：修 `prey` 解析器（`Class#m1+m2` 被当成一个方法名 ⇒ `-Dtest` 选择器无效 ⇒ `ran=0`）
之前，那几族的对照拿不到分母，走的就是 :428 这条路 ⇒ 所以这条通道不是装饰，是有过一次实报的。

### 7.6 变异锁：跨工作树共享 + 只 `LOCK_EX|LOCK_NB` + 撞锁实测 rc=4

```
$ git rev-parse --git-common-dir
/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git
p26_mutation.py:251  path = os.path.join(common_dir(), "zbot-mutlock")     # 公共 .git ⇒ 所有 worktree 同一把
p26_mutation.py:254  fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)         # 非阻塞，绝不 sleep 等
p26_mutation.py:257  print("LOCK-BUSY … ⇒ rc=4（不 sleep 重试）") ; main() → return 4
```
真跑那一轮的取证行（`~/.cache/zbot-p26-lead/mut_p26b_run1.log`）：
```
LOCK-ACQUIRED /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot/.git/zbot-mutlock (LOCK_EX|LOCK_NB)
```
**阳性对照（rc=4 这条路径真能走到）**：17:17:16 我用一个自持进程 `flock(LOCK_EX)` 占住同一把锁，
17:17:18 再跑一次脚本：
```
NOW=17:17:18
CLASH_RC=4
ANCHOR-CHECK mutants=26 bad=0 families=…            ← 静态前置在拿锁之前，撞锁时不碰源文件
LOCK-BUSY path=/Users/zifang/…/.git/zbot-mutlock ⇒ rc=4（不 sleep 重试）
$ lsof …/zbot-mutlock        （撞锁时刻）
Python  76718 … REG 1,16 49 133841886 …/zbot-mutlock   ← 我的一次性对照持锁者（用完我自己 kill 掉了）
Python  94799 … REG 1,16 49 133841886 …/zbot-mutlock   ← **别的棒（p23b 的 p23_mutation.py）的在途变异跑**
```
⇒ 两件事同时被证实：① rc=4 分支可达；② 这把锁真的跨工作树共享——`lsof -p 94799` 显示
其 `cwd=/private/tmp/zbot-wt-p23`、在本工作树打开的文件数 **0**（`lsof -p 94799 | grep -c zbot-wt-p26` ⇒ `0`），
它和我在不同工作树却抢同一把锁。按红线：那支外棒进程**我没有碰**（只 `kill` 了自己 76718，17:18 已回收，
`lsof` 复扫只剩它一个 fd）。我也**没有**因为撞锁去 sleep 重试：本期只重试了 0 次，真台账（16:59–17:02）
一次拿到锁，没撞上任何人。
副作用记账：我这次对照占锁约 40s（17:17:16→17:18:0x），如果 p23b 正好在这个窗口试锁，它会按同一协议拿 rc=4
自行避让——这正是把对照做在真跑之前、且只占一把有主钥匙的原因。

### 7.7 字节级还原 + 三值 md5 对账

* 机械侧（每支跑完自己写一行）：`LEDGER.tsv` 26 行 `reason` 里 `md5=disk:X/git:Y/same=Z` ⇒ **26/26 `same=True`**，
  按文件抽第一行核对：`LlmErrorClassifier e367e30a / JitteredBackoff 091b7765 / KeyPoolLlmProvider 79aa0c08 /
  ModelUsage 95cc1b27 / ResilientLlmProvider 3ab81471 / StateStore 38b16e78 / StreamStaleWatchdog a501c5be`。
* 独立侧（我在盘外重算，不读脚本的任何自报值）：
```
$ git status --porcelain            # 全部 7 个被测文件都不在改动列表里
 M _doc/acceptance/p26/EVIDENCE.md
?? _doc/acceptance/p26/LEDGER.tsv / P26E2eDriver.java / __pycache__/ / p26_e2e.py / p26_mutation.py
$ for f in <7 files>; do md5 -q $f ; git show HEAD:$f | md5 -q ; git hash-object $f ; git rev-parse HEAD:$f ; done
JitteredBackoff.java      disk_md5=091b7765 head_md5=091b7765 blob_sha1=d9e86f8d…=HEAD ⇒ match=YES
KeyPoolLlmProvider.java   disk_md5=79aa0c08 head_md5=79aa0c08 blob_sha1=1990f0ae…=HEAD ⇒ match=YES
LlmErrorClassifier.java   disk_md5=e367e30a head_md5=e367e30a blob_sha1=e4e627ac…=HEAD ⇒ match=YES
ModelUsage.java           disk_md5=95cc1b27 head_md5=95cc1b27 blob_sha1=0d17f83d…=HEAD ⇒ match=YES
ResilientLlmProvider.java disk_md5=3ab81471 head_md5=3ab81471 blob_sha1=5e93c9b9…=HEAD ⇒ match=YES
StateStore.java           disk_md5=38b16e78 head_md5=38b16e78 blob_sha1=9d1e9079…=HEAD ⇒ match=YES
StreamStaleWatchdog.java  disk_md5=a501c5be head_md5=a501c5be blob_sha1=c10727ee…=HEAD ⇒ match=YES
```
⇒ 三值 = 盘上 md5 / `git show HEAD:` md5 / git blob SHA-1（两侧同值），7/7 全等；
与 §7 台账自报的 8 位前缀逐个对上（disk==自报），杠① 的 736 绿读数因此可归属到 HEAD 那棵树。

### 7.8 开跑前的无界等待扫描（`\.await()`）

```
$ git grep -n "\.await()" -- 'z-bot-core/src/test/**/*.java'
z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:421:                        go.await();
z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:434:        ready.await();
z-bot-core/src/test/java/com/zifang/z/bot/context/CompressorEngineTest.java:110:                release.await();
z-bot-core/src/test/java/com/zifang/z/bot/context/CompressorEngineTest.java:119:        started.await();
（阳性对照：同两文件 grep -c "await" = 2 和 3 ⇒ 尺看得见字，只有"无界 + 可达"才算病）
```
判定：**4 处都在杠② 的射程之外**——台账只跑 `RUNNERS`（`p26_mutation.py:59` =
`P26RetryPolicyTest, P26UsageTest, ResilientLlmProviderTest, KeyPoolLlmProviderTest, ModelCatalogCacheTest`）
这 5 个具名类，`-Dtest=` 也只选它们；且这两个类 `import com.zifang.z.bot.llm` 命中数 **0/0**（不碰被测面）。
⇒ **不改**（工单只允许把"可达的无界等待"改成有界 + 显式判词；把不可达的改掉等于凭空动测试代码）。
兜底不依赖这个判断：`mvn()` 用 `start_new_session=True` + `os.killpg(…SIGKILL)` + `RUN_TIMEOUT=420`
（:283/:287/:290），即使某支变异真把测试挂住，也是整进程组被杀 + 该行 `BROKEN`，不会拖走整张台账。
杠③ 侧的无界等待本来就有界：`P26E2eDriver.java` 的 `latch.await(25, TimeUnit.SECONDS)`，
实测 S6 `waitedMs=25033` 到点自退、`rc=0`。

## §8 杠③ 真进程 E2E ≥3 整跑（本地假端点 429 / 人为断流）

STATUS: DONE —— 3 次互不相同 label 的整跑全绿（`r3`/`r4`/`r5`，每次 `scenes=10 failed_checks=0 rc=0`）。
25 条红的归因结论：**(a) 量具自身缺陷 25 条全部**（其中 1 条根因吃掉 21 条），
**(b) 被测真缺陷 1 条**（新增缺陷 F，见 §8.4；它是被上游 kernel 挡住的，本期修不动，已进 §11 并照实降低该判词）。
**没有改过一行被测 `src/main`**（`git_src_dirty=''` 由量具每轮自证，见 §8.1 三跑头部行）。

### 8.0 第一嫌疑人成立：25 条红量的是空气

`e2e_run1.log`（P26a，16:30:22 起）里 8 个场景的驱动侧第一行**全是同一句**：

```
drv P26 CFG provider=openai url=null model=gpt-4o-mini keys=0 retries=2 backoff=1000
srv
SERVER n=0 gaps=[] rc=0
drv P26 RESULT err type=...LlmException msg=[https://api.openai.com/v1/chat/completions] HTTP 401 {...}
```
`url=null keys=0` ⇒ `BotConfig` 没认出 `openai` 这个 provider code（run1 时 `write_config()` 里没有
`providers=openai` 这行；盘上 `~/.cache/zbot-p26-lead/e2e/s1-429-body/config.properties` 11 行、
首行是 `llm.provider=openai`，`od -c` 证实没有 `providers=`）。于是 kernel 用它自己的默认域
**真出了网**（8 发全部打到 `https://api.openai.com/v1/chat/completions` 拿 401），本地 stub **一发没收到**。
⇒ 分类表/退避/看门狗/usage 归一**一条都没被量到**；`DECISION ROTATE_KEY/http:401/402/403` 是
真 API 的 401 喂出来的，与场景设定无关。

> ⚠ 这一条同时是**红线 3 的既往违反**（前一棒的 run1 出了网，虽然无凭据、只拿到 401）。
> 详见 §10.3；本轮起量具里有两道闸（驱动侧 GUARD + 全局 URL 尺），见 §8.5。

25 条红的逐条落点（`grep -c FAIL` = 25，全部 `SERVER n=0`）：

| 场景 | 红条数 | 直接原因（全部指向同一处接线） |
|---|---|---|
| S1 | 4 | stub 0 发；`waits=[]`；分类读的是真 401 |
| S2 | 4 | 同上 |
| S3 | 3 | 403 从没发生（真 API 是 401），池子没轮到 |
| S4 | 3 | 404 从没发生 |
| S5 | 3 | 首块 0 个（`chunks=0`），看门狗压根没被喂 |
| S6 | 2 + 首块到达 1 | 同上 |
| S7 | 首块到达 1 | 同上（4 条判词反而"过"了——见下面空集恒真） |
| S8 | 2 + 首块到达 1 | 同上 |

**另有 6 条判词在空集上恒真**（这是同一根因的第二面，比红更危险）：
`S1 全部对端=127.0.0.1`（`all()` over 空 log）、`S2 单调不回退`（`[] == sorted([])`）、
`S2 总预算内`（`sum([]) <= 12000`）、`S4 换模型不重跑退避`（`parse_waits([])==[]`）、
`S7 全部 4 条`（错误来自真 API 却长得像"断流立刻上抛"）、`S8 收尾块到齐即结束`
（`waitedMs=1243` 其实是 401 快速失败）。⇒ 原样保留这些判词的话，"绿"和"红"都不成立。

### 8.1 驱动先编译起来 + 最小阳性对照

```
$ javac -encoding UTF-8 -cp z-bot-core/target/z-bot-core.jar -d ~/.cache/zbot-p26-lead/e2e/classes \
        _doc/acceptance/p26/P26E2eDriver.java > ~/.cache/zbot-p26-lead/javac_p26b.log 2>&1
$ echo $?
0                       ← javac rc=0（P26a 死前 14 秒写的那份是**能编译**的，不是半截码）
```
（量具每轮自己再编一次，日志里 `javac rc=0` 三跑各一次，见 §8.5。）

单场景 S0 阳性对照（`--label pc1 --only s0-positive-control`，修通接线后的第一发）：

```
  cfg providers=openai
  drv P26 GUARD baseUrl=http://127.0.0.1:55376/v1 keys=1 loopback=true
  drv P26 RESULT ok content=pong elapsed=215ms
  srv req#1 t=2.448s peer=127.0.0.1 mode=ok path=/v1/chat/completions model=p26-model-ok key=stub-k stream=False
  SERVER n=1 gaps=[] rc=0
  PASS S0 阳性对照：stub 真收到 1 发
```
⇒ stub 真收得到请求。**在这条成立之前，§8.0 那种读数一律不判**（S0 现在每轮必跑，红了整轮 rc=4 作废）。

### 8.2 (a) 类：量具自身缺陷改了什么（逐条：哪行 / 为什么 / 改前 → 改后）

文件都是 `_doc/acceptance/p26/p26_e2e.py`（量具），**`src/main` 一行没动**。

| # | 改动（行） | 为什么 | 改前读数 → 改后读数 |
|---|---|---|---|
| a1 | `write_config()` 的 `lines` 首行 `providers=openai` | `BotConfig.fromProperties` 只对 `LEGACY_PROVIDERS`(minimax/spark) 和 `providers=` 显式声明的 code 建 provider；缺这行 ⇒ `activeProvider()` 走 `providers.get(null)` 兜底 ⇒ `baseUrl=null keys=0` | stub `SERVER n=0`（8 场景）→ `SERVER n=1`（S0）且 `url=http://127.0.0.1:PORT/v1 keys=1` |
| a2 | 新增 `exp_ok()` + `main()` 里的 S0 闸门（`--only` 也必跑，`force=True`） | 工单 §1.2 的"最小阳性对照"；收到 0 就不许讨论分类对不对 | 无此判词 → `S0 阳性对照…PASS ×5`，stub 收不到时 `rc=4` 整轮作废 |
| a3 | `run_java()` 的 `-Dhttp(s).proxyHost=127.0.0.1 -Dhttp.proxyPort=9` + `-Dhttp.nonProxyHosts` | P26a 16:33 加的"死代理兜底"；真出网时被丢进 discard 端口 ⇒ 立刻被拒 | run1 真出网 8 发 → 本轮全局 URL 尺 `0 命中非回环`（§8.5） |
| a4 | 驱动 `P26 GUARD` 前置检查（`loopback` 且 `keyCount>0` 否则 `exit(4)`） | 任何 HTTP 之前掐掉非回环端点 | run1 无此行 → `P26 GUARD baseUrl=http://127.0.0.1:… loopback=true` |
| a5 | `S1 全部对端=127.0.0.1`：加 `len(srv.log) > 0` 前提 | 空集恒真 | `PASS n=0`（作废）→ `PASS n=3` |
| a6 | `S2 单调不回退` → `S2 档位不减且抖动只上加`，判 `w[i]∈[rung,1.5×rung+150]`，`rung=[100,200,400,800,1600,1600]` | 抖动是加性随机项，长档第 5/6 次**同属 1600 档** ⇒ 裸序列谁大谁小是掷硬币（r2 实测 `2306→1713` 被判红，纯属量具写错模型）；产品性质是"档位不减 + 抖动只往上加" | r2 `FAIL [121,202,483,923,2306,1713]` → r3/r4/r5 `PASS` |
| a7 | `S2 总预算内`：加 `len(w)==6` | 空集恒真（`sum([])<=12000`） | `PASS sum=0`（作废）→ `PASS sum=6322` |
| a8 | `S4 换模型不重跑退避`：加 `P26 RESULT ok` 前提 | 空气上 `waits==[]` 恒真 | `PASS []`（作废）→ `PASS waits=[] ok=True` |
| a9 | `S8 收尾块到齐即结束`：加"无 ONERROR"前提 | `waitedMs=1243` 其实是真 API 的 401 快速失败，错误路径冒充"快速收尾" | `PASS waitedMs=1243`（作废）→ `PASS waitedMs=626 onerror=False` |
| a10 | 每个流式场景补 `Sn stub 真收到这一发` | S7 在 run1 里 4 条全"过"却是 0 发 | 无 → `S5/S6/S7/S8/S9 n=1` |
| a11 | `S3` 判词从读 `ResilientLlmProvider.lastDecision()` 改成读 **KeyPool 的具名轮换日志**（`KeyPoolLlmProvider.java:128`，只有分类=ROTATE_KEY 才走到）+ stub 侧 BAD→GOOD | 403 在 `KeyPoolLlmProvider` 层就被消化（§1.1 设计：ROTATE_KEY 由 KeyPool 轮换，Resilient 层看不见异常）⇒ `lastDecision` **按设计** 恒为 null。观测点接错，不是分类表判错 | r1 `FAIL S3 分类=ROTATE_KEY（403）  P26 DECISION null` → r3+ `PASS S3 分类=ROTATE_KEY ⇒ KeyPool 层消化（403）  [main] WARN …KeyPoolLlmProvider - [KeyPool] key#0 失败进入冷却 600000ms，换下一个 key: … HTTP 403 …` |
| a12 | `S7` 判词改成设计口径（看门狗在 `阈值+巡检 150ms+余量` 内检出、`chunks=1`、`leaked=0`、进程不永挂），删掉"立刻 I/O 错且非 StreamStale" | 见 §8.3 的 kernel 字节码实测：FIN 收尾对 bot 不可见 ⇒ 能兜住断流的**只有**看门狗，原期望与 §4 交付语义互相矛盾 | r1/r3 `FAIL S7 … t=4254ms class=…StreamStaleException` → r3+ `PASS S7 4000ms 档 ⇒ 4.0~5.5s 内报错 t=4254ms` |
| a13 | `S6` 判词改成 `FIN 截断静默：无 ONERROR 且驱动自带上界到点`（并标【缺陷 F 记账】） | 同上；对照组本来要证的正是"没有看门狗就什么都看不见" | r1 `FAIL S6 只有 SIGKILL 断流后 java 才拿到错误` → r3+ `PASS S6【缺陷 F 记账】… done=false` |
| a14 | 新增 stub 模式 `broken_chunked` + 场景 S9（`exp_stream_break`，关看门狗） | 双向对照：块长宣告 `0xc8` 只写 10 字节 ⇒ 协议级破断 ⇒ okhttp 必抛 IOException。用来证明"a12/a13 不是因为 bot 压根不上抛 I/O 错" | 无此场景 → `PASS S9 协议级破断 ⇒ 即时上抛非 stale 的 I/O 错`（r3/r4/r5，t 实测见 §8.5） |
| a15 | `main()` 末尾全局尺：现场日志里出现的**每个 URL** 必须回环，否则红 | 把"量具自己先出网"变成会红的判词而不是事后发现 | 无 → `PASS 全局：本轮所有 URL 均为 127.0.0.1 []` |
| a16 | `--label` / `--only` 落地（docstring 早就广告过、`main()` 里没实现）+ `scenes=` 由计数器给（原来硬写 8） | "广告的能力就是承诺"；且工单要求 ≥3 个互不相同 label 的整跑 | 无（重跑覆盖同一目录）→ `run_label=g1/g2/g3`，`scenes=10` |

**读表口径**：上表"改后读数"里的墙钟数字取自归因期的 `r2`—`r5`（作"改前 vs 改后"对照用）；
收口的三次整跑换到 `g1`/`g2`/`g3`（§8.5），**判词条数与名字逐条复现**（每次 65 条 PASS / 0 条 FAIL），
只有墙钟类读数会随 run 变。收口那三次里同一行的实际数字（取 `g2`）：
`a7 → PASS S2 总预算内 sum=6111`、`a9 → PASS S8 收尾块到齐即结束 waitedMs=407 onerror=False`、
`a14 → PASS S9 … t=51ms`。

### 8.3 两条互相冲突的 `Retry-After` 期望：以 kernel 真做了什么为准

工单点名让我复核（不是照抄）"正文标签被采信"(S1) 与 "kernel 丢头 ⇒ 不采信"(S2) 是否冲突。实测：**不冲突，两条都成立**，
因为采信路径只有一条——**从异常 message 里读**：

```
LlmErrorClassifier.java:276  static long retryAfterMs(String msg, RetryPolicyConfig policy) { … Matcher m = RETRY_AFTER.matcher(msg) … }
LlmErrorClassifier.java:131  return new Decision(cls, rule, Evidence.HTTP_STATUS, status, retryAfterMs(msg, policy));
```
kernel 侧（`z-agent-kernel-llm-0.2.1.jar`，`pom.xml:69` pin 的版本）：
```
$ javap -p …/support/LlmHttp.class
  public java.lang.String postJson(String, okhttp3.Headers, Object)      ← 只返回 body 字符串
$ unzip -p … LlmHttp.class | grep -i 'Retry-After'  →  0 命中（Headers 只出现在 Request$Builder.headers 的**发送**侧）
```
⇒ 响应**头**里的 `Retry-After` 到不了 bot（kernel 不读响应头）；响应**正文**里的 `retry_after 900ms`
会随 `[url] HTTP 429 <body>` 进 message ⇒ 被采信。这条期望是 P26a 写在 §2.1 的（"有则优先"），
按上面的实测落成"S1 采信正文标签 / S2 不采信 HTTP 头"，实测：
```
S1  PASS 分类=RATE_LIMIT/http:429   P26 DECISION RATE_LIMIT/http:429 (HTTP_STATUS, HTTP 429, retryAfter=900ms)
S1  PASS 等待=[900, 900]           服务端 gaps=[909, 908]（服务端墙钟，不是 bot 自报）
S1  PASS 服务端实测间隔≈900ms                                     [909, 908]
S2  PASS 无正文标签 ⇒ 不采信 Retry-After 头（kernel 丢头）   waits=[111, 215, 586, 1144, 2192, 1863]
S2  PASS 长档=限流阶梯 800/1600/1600（第 4~6 次失败）                  [111, 215, 586, 1144, 2192, 1863]
S2  PASS 档位不减且抖动只上加（w∈[rung,1.5×rung+150]）  rung=[100, 200, 400, 800, 1600, 1600]
（以上 6 行取自收口 run `g2`：~/.cache/zbot-p26-lead/e2e_p26b_g2.log；归因期 r5 的等价读数是 gaps=[912,903]、
 waits=[104,249,491,1109,2009,2360]，两批数字的**形状**一致：采信 Retry-After 时等待被钉在 900ms，
 不采信时回到 100/200/400 短指数 + 800/1600/1600 长阶梯）
```
判：S1/S2 两条期望**按 kernel 实际行为各自成立**；`retryAfter=900ms` 与服务端墙钟 `gaps=[909,908]`
是两个独立来源的读数对上了（一个 bot 记账、一个 stub 侧墙钟）。
写这条期望的人是 **P26a**（§2.1"有则优先"），kernel 真做的是"丢响应头、只把正文塞进 message"，
所以判词最终落成的样子 = S1 只信正文标签 + S2 明确"不信 HTTP 头"（把 kernel 的丢头行为**也**钉成断言）。

### 8.4 断流为什么"没有错"：kernel 字节码级实测（新增缺陷 F）

```
$ javap -p -c …/provider/OpenAIProvider.class | grep -A4 'postJsonStream'
  48: aconst_null          ← 第三个回调 Runnable（onComplete）传的是 null
  49: invokevirtual …LlmHttp.postJsonStream:(String,Headers,Object,Consumer,Consumer,Runnable)V
$ javap -p …/support/LlmHttp$1.class
  class … LlmHttp$1 extends okhttp3.sse.EventSourceListener
    public void onClosed(okhttp3.sse.EventSource)   → if (onComplete != null) onComplete.run()   ⇒ 什么都不做
    public void onFailure(okhttp3.sse.EventSource, Throwable, Response) → onError.accept(t)
$ javap -cp …jar com.zifang.z.agent.kernel.llm.LlmProvider
  public abstract void streamChat(ChatCompletionsRequest, Consumer<ChatCompletionsResponse>, Consumer<Throwable>)
    ← 接口只有 onChunk / onError 两个回调，**没有**"流结束"这一路
```
实测两侧（同一个 bot、同一份 jar，只差 stub 怎么断）：
```
S6（中转进程 SIGKILL ⇒ FIN，stale=0 关看门狗）
  drv P26 CHUNK n=1 t=81ms text=ping finish=null
  drv P26 STREAM done=false chunks=1 waitedMs=25057 err=NONE errMsg=
S9（协议级破断 ⇒ 真 IOException，stale=0 关看门狗）
  drv P26 ONERROR t=… class=java.io.IOException（非 StreamStale） …
```
结论：
- **bot 会上抛真 I/O 错**（S9），所以 S6/S7 的静默不是 bot 漏了 onError；
- 但 **FIN/EOF 收尾在 kernel 里被当成"正常完成"**（`onClosed` → 空 onComplete），
  且 `LlmProvider` 接口**没有** completion 回调 ⇒ bot 层拿不到"流结束了但没收到 finish_reason"这件事，
  连"截断"这个事实都无法在 bot 侧判定 —— 只有看门狗靠**时间**间接兜住（S5 1.67s / S7 4.25s）。

> **缺陷 F（被测真缺陷，本期修不动，交主编）**
> 场景：S6（`--label r*` 现场 `s6-stream-nowatchdog`）。
> 期望：流被中途截断时，调用方要么收到错误、要么至少知道"没有收尾块"。
> 实际：`llm.stream.stale-timeout-ms=0`（关看门狗）时，中转到 `slow_stream` 上游的 TCP 被 SIGKILL
> 后发出 FIN，bot **静默**——`P26 STREAM done=false chunks=1 err=NONE`，驱动等了 25057ms 自己上界退出；
> 上层拿到的最终状态和"正常收尾"唯一的区别只是没有 `finish_reason` 块，而 bot 不检查这一条。
> 复算命令：
> ```
> python3 _doc/acceptance/p26/p26_e2e.py --label f --only s6   # 看 drv P26 STREAM … err=NONE
> javap -p -cp ~/.m2/repository/io/github/yuku123/z-agent-kernel-llm/0.2.1/z-agent-kernel-llm-0.2.1.jar \
>   'com.zifang.z.agent.kernel.llm.support.LlmHttp$1'          # onClosed → onComplete（OpenAIProvider 传 null）
> ```
> 本期为什么不修：要动的是 `z-agent-kernel`（4 参 `streamChat(req,onChunk,onError,onComplete)`
> 或在 `OpenAIProvider` 里校验 `[DONE]` 尾哨兵），不在可写域；bot 侧任何"小改"都只能靠
> 时间阈值间接判，改不出"截断可判定"这条性质。⇒ 进 §11 未做，`S6` 那条判词按实测改写并已标
> 【缺陷 F 记账】，§8 的整体判词**降低**为"断流检测=看门狗兜底，非即时 I/O 错"。

### 8.5 三次整跑（互不相同 label，三个数原样贴）+ 现场复扫

```
$ python3 _doc/acceptance/p26/p26_e2e.py --label r3   # ~/.cache/zbot-p26-lead/e2e_p26b_r3.log
  started=2026-09-26 16:49:48 +0800  run_label=r3  javac rc=0  git_head=55cfcb1…  git_src_dirty=''
scenes=10 failed_checks=0 []
rc=0
$ python3 _doc/acceptance/p26/p26_e2e.py --label r4   # e2e_p26b_r4.log
  started=2026-09-26 16:51:07 +0800  run_label=r4  javac rc=0  git_head=55cfcb1…  git_src_dirty=''
scenes=10 failed_checks=0 []
rc=0
$ python3 _doc/acceptance/p26/p26_e2e.py --label r5   # e2e_p26b_r5.log
  started=2026-09-26 16:52:02 +0800  run_label=r5  javac rc=0  git_head=55cfcb1…  git_src_dirty=''
scenes=10 failed_checks=0 []
rc=0
```
`scenes=10` = S0 闸门 + S1…S9（原 8 场景 + 新增 S9；S0 每轮必跑）。
归因过程中另有两笔**不作数**的跑：`e2e_p26b_r1.log`（`scenes=9 failed_checks=4 rc=1`，
a11/a12/a13 未改）、`e2e_p26b_r2.log`（`scenes=10 failed_checks=1 rc=1`，a6 未改），保留作改前读数。

判词（按工单口径逐条）：分类表（S1/S2/S3/S4 四类各一发真进程）、抖动+阶梯（S2 六次等待的墙钟与记账）、
换 key（S3 BAD→GOOD）、换模型（S4 `p26-model-gone`→`p26-model-ok`）、
陈旧看门狗（S5 阈值 1500ms 实测 1672ms / S7 阈值 4000ms 实测 4254ms / S6 对照组 / S8 正常流不误判 /
S9 真 I/O 错）——**全部在真进程 + 真 jar + 真 okhttp + 只绑 127.0.0.1 的假端点上跑到**。
"检测延迟 = 阈值 + ≤1 个巡检周期"这条 §4 声称在真进程里同样成立（1500→1672、4000→4254，
巡检周期配的是 150ms，多出来的 170/250ms 是 okhttp 回调线程调度余量，量具把上限放到 3.5s/5.5s）。

现场复扫（不留活口）：
```
$ ps -eo pid,command | awk '/P26E2eDriver/ && !/awk/' | wc -l
0
$ ps -eo pid,command | awk '/p26_e2e\.py/ && !/awk/' | wc -l
0
（同一条尺的阳性对照：awk '/awk/' 数到 5 ⇒ 尺不是坏的）
$ lsof -nP -iTCP -sTCP:LISTEN | awk '/Python|java/'
Python 20494 … TCP 127.0.0.1:61530 (LISTEN)     ← ps -p 20494 = "Python p23_e2e.py p23b-f1"（**别的棒的**，不碰）
java   90731 … TCP *:18090 (LISTEN)             ← z-lc 服务，不是本单起的
```
⇒ 我起的 stub / driver / proxy **0 个残留**；唯一在听的 Python 进程经 `ps -o pid,ppid,command -p` 点名是 p23b 的
现场，按红线不动。

STATUS 判词（杠③）：**DONE-with-documented-gap** —— 4 杠意义上"跑通且可复算"，
但"半路断流的即时可观测性"这一条按 §8.4 的实测**降级**为"仅看门狗兜住（缺陷 F，上游挡住，本期未做）"，
没有把它改判成产品达标。

## §9 杠④ `~/.zbot` 三时点一字未动

STATUS: DONE（p26c）—— T1/T2 都是**当场采样**得到的，不是回忆。三次整跑（本棒 label `c1`/`c2`/`c3`）
全绿，杠④ 三时点九个格子**逐格相同** = `8 / 2dadaed0 / 690ddbc0`，与 T0 对上，与工单 §1 期望对上。

### 9.0 本棒三次整跑（T1/T2 的来源，读数原样）

```
$ python3 _doc/acceptance/p26/p26_e2e.py --label c1 > ~/.cache/zbot-p26-lead/e2e_p26c_c1.log 2>&1   # 后台跑
$ sed -n '1,8p' e2e_p26c_c1.log ; echo '   [...]' ; tail -3 e2e_p26c_c1.log
P26 E2E harness  started=2026-09-26 17:26:18 +0800  run_label=c1  only='ALL'
  jar=/private/tmp/zbot-wt-p26/z-bot-core/target/z-bot-core.jar
  jar_sha8=7cba0899
  git_head=55cfcb1f75e6e73f93c873c0d7fbfd006aa55501
  git_src_dirty=''
  java=java
  root=/Users/zifang/.cache/zbot-p26-lead/e2e/c1
  javac rc=0 
   [...]
=== E2E SUMMARY ===
scenes=10 failed_checks=0 []
rc=0
$ sed -n '1,8p' e2e_p26c_c2.log ; echo '   [...]' ; tail -3 e2e_p26c_c2.log      # --label c2 started=17:27:24
P26 E2E harness  started=2026-09-26 17:27:24 +0800  run_label=c2  only='ALL'
  jar=/private/tmp/zbot-wt-p26/z-bot-core/target/z-bot-core.jar
  jar_sha8=7cba0899
  git_head=55cfcb1f75e6e73f93c873c0d7fbfd006aa55501
  git_src_dirty=''
  java=java
  root=/Users/zifang/.cache/zbot-p26-lead/e2e/c2
  javac rc=0 
   [...]
=== E2E SUMMARY ===
scenes=10 failed_checks=0 []
rc=0
$ sed -n '1,8p' e2e_p26c_c3.log ; echo '   [...]' ; tail -3 e2e_p26c_c3.log      # --label c3 started=17:28:26
P26 E2E harness  started=2026-09-26 17:28:26 +0800  run_label=c3  only='ALL'
  jar=/private/tmp/zbot-wt-p26/z-bot-core/target/z-bot-core.jar
  jar_sha8=7cba0899
  git_head=55cfcb1f75e6e73f93c873c0d7fbfd006aa55501
  git_src_dirty=''
  java=java
  root=/Users/zifang/.cache/zbot-p26-lead/e2e/c3
  javac rc=0 
   [...]
=== E2E SUMMARY ===
scenes=10 failed_checks=0 []
rc=0
```
三跑的归属指纹完全同型：同一个 jar（sha256 前 8 位 `7cba0899`，17:06:39 由 §7.7 的"7 文件逐字节 == HEAD"那次
`mvn -o package -DskipTests` 产出）、同一个 `git_head=55cfcb1…`、`git_src_dirty=''` ⇒ 读数可归到交付树。
**S0 阳性对照闸门没有绕**（它不受 `--only` 影响，必跑；stub 收不到就 `rc=4` 废轮）。闸门现场原样：
```
# c1 的 S0 段开头（e2e_p26c_c1.log 第 9—11 行）
=== [s0-positive-control] mode=ok keys=1 model=p26-model-ok ===
  base=http://127.0.0.1:62992/v1
  cfg providers=openai
# c2 的 S0 判词（e2e_p26c_c2.log）
  drv P26 GUARD baseUrl=http://127.0.0.1:63325/v1 keys=1 loopback=true
  PASS S0 阳性对照：stub 真收到 1 发                                 server n=1
  PASS S0 阳性对照：驱动 GUARD 放行 loopback                         P26 GUARD baseUrl=http://127.0.0.1:63325/v1 keys=1 loopback=true
```
⇒ 闸门真在环节里、且三跑都没走到废轮出口：
```
$ for f in e2e_p26c_c1.log e2e_p26c_c2.log e2e_p26c_c3.log; do echo -n "$f: rc=4 "; echo -n $(grep -ac 'rc=4' $f); echo -n " 次; E2E ABORT "; echo -n $(grep -ac 'E2E ABORT' $f); echo -n " 次; PASS S0 阳性对照 "; grep -ac 'PASS S0 阳性对照' $f; done
e2e_p26c_c1.log: rc=4 0 次; E2E ABORT 0 次; PASS S0 阳性对照 5
e2e_p26c_c2.log: rc=4 0 次; E2E ABORT 0 次; PASS S0 阳性对照 5
e2e_p26c_c3.log: rc=4 0 次; E2E ABORT 0 次; PASS S0 阳性对照 5
```
本棒未重跑杠① / 杠② 的根据：我没动 `z-bot-core/src/**`，也没动 `p26_mutation.py`（复算见 §11.6）。

### 9.1 三时点表（尺 = 工单 §1/§3 那三条，三时点同一条）

| 时点 | `ls -A ~/.zbot \| wc -l` | `md5 -q config.properties \| cut -c1-8` | `md5 -q state.db \| cut -c1-8` | 取自 |
|---|---|---|---|---|
| T0 开工 | 8 | 2dadaed0 | 690ddbc0 | `date` = 2026-09-26 15:48:09 +0800（p26b） |
| T1 在飞 | 8 | 2dadaed0 | 690ddbc0 | `date` = 2026-09-26 17:26:46 +0800，`c1` 正在跑 s6 |
| T1′ 在飞复采 | 8 | 2dadaed0 | 690ddbc0 | `date` = 2026-09-26 17:29:03 +0800，`c3` 在飞 |
| T2 收尾 | 8 | 2dadaed0 | 690ddbc0 | `date` = 2026-09-26 17:29:42 +0800，三跑全完成之后 |

### 9.2 "在飞"的取证（T1 时刻：收尾行还没打印、被测 JVM 还活着）

```
$ date "+%Y-%m-%d %H:%M:%S %z"
2026-09-26 17:26:46 +0800
$ grep -c 'scenes=' ~/.cache/zbot-p26-lead/e2e_p26c_c1.log      # 本轮收尾行尚未出现 ⇒ 真在飞
0
$ ps -eo pid,etime,command | awk '/P26E2eDriver|p26_e2e\.py/ && !/awk/'
63325       00:28 /bin/zsh -c …（本棒的 shell 包装行，内含本次 harness 命令行 "python3 _doc/acceptance/p26/p26_e2e.py --label c1"；整行很长，中段省略）…
63327       00:28 /Library/Frameworks/Python.framework/Versions/3.14/Resources/Python.app/Contents/MacOS/Python _doc/acceptance/p26/p26_e2e.py --label c1
63860       00:12 /Library/Frameworks/Python.framework/Versions/3.14/Resources/Python.app/Contents/MacOS/Python /private/tmp/zbot-wt-p26/_doc/acceptance/p26/p26_e2e.py --proxy 127.0.0.1:63075 /Users/zifang/.cache/zbot-p26-lead/e2e/c1/s6-stream-nowatchdog/proxy.port
63862       00:12 /usr/bin/java -Djava.net.preferIPv4Stack=true -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9 -Dhttp.nonProxyHosts=localhost|127.*|[::1] -cp /private/tmp/zbot-wt-p26/z-bot-core/target/z-bot-core.jar:/Users/zifang/.cache/zbot-p26-lead/e2e/classes P26E2eDriver stream /Users/zifang/.cache/zbot-p26-lead/e2e/c1/s6-stream-nowatchdog
$ tail -3 ~/.cache/zbot-p26-lead/e2e_p26c_c1.log
  cfg llm.retry.max=0
  PASS s6-stream-nowatchdog 首块到达
  PROXY pid=63860 STOP ps=[63860 T    00:01]
$ ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties | cut -c1-8 ; md5 -q ~/.zbot/state.db | cut -c1-8
       8
2dadaed0
690ddbc0
```
T1′ 复采（`c3` 在飞，17:29:03；`ps` 输出当时被我自己的 `cut -c1-190` 截过，已在下面标出）：
```
2026-09-26 17:29:03 +0800   grep -c 'scenes=' e2e_p26c_c3.log = 0
67619 00:38 /bin/zsh -c …（shell 包装行，截断）
67622 00:38 …Python _doc/acceptance/p26/p26_e2e.py --label c3
68853 00:22 /usr/bin/java -Djava.net.preferIPv4Stack=true -Dhttp.proxyHost=127.0.0.1 …（此行被我截到 190 字符）
⇒ A=8  B=2dadaed0  C=690ddbc0
```

### 9.3 T2（收尾）+ 不留活口尺

```
$ date "+%Y-%m-%d %H:%M:%S %z" ; ls -A ~/.zbot | wc -l ; md5 -q ~/.zbot/config.properties | cut -c1-8 ; md5 -q ~/.zbot/state.db | cut -c1-8
2026-09-26 17:29:42 +0800
       8
2dadaed0
690ddbc0
$ ps -eo pid,command | awk '/P26E2eDriver|p26_e2e\.py/ && !/awk/' | wc -l      # 我起的还剩几个
0
$ ps -eo pid,etime,command | grep -c '[z]bot-wt-p26'                           # 另一把尺：本树路径 0 命中
0
$ ps -eo pid,command | grep -c '[j]ava'                                        # 同尺的阳性对照（尺看得见字）
9
⇒ 第一把尺有个自己的坑：`[z]…` 这种自避写法只躲开 grep 本身，
  躲不开"命令行里出现裸路径"——我把这条命令和 `cd /private/tmp/zbot-wt-p26` 写在同一次 shell 调用里时它会自匹配成 1，
  所以上面这个 0 是在**不含该路径的工作目录**（`pwd` = /Users/zifang/workplace/ceo_workplace/z-opc-foundation）下拍的。
$ lsof -nP -iTCP -sTCP:LISTEN | awk '/Python|java/'
java      90731 zifang   13u  IPv6 0xf9596b517ad65ee5      0t0  TCP *:18090 (LISTEN)
⇒ 唯一在听的 java 是 90731（z-lc 的服务，不是本单起的，按红线不碰）；Python 侧 0 个 listener
  ⇒ 我这三跑起的 stub / proxy / driver 全部自然退出，端口 bind(0) 随进程释放。
$ ls -A ~/.zbot
.stty.bak
config.properties
cron
memories
models-cache.json
sessions
state.db
workspace
⇒ 8 项名单三时点未变；`.stty.bak` 与空 `cron` 本期**一个都没删**（工单红线），它们本来就在 8 里。
```
判（杠④）：**DONE** —— 真实 `~/.zbot` 在 15:48:09 / 17:26:46（在飞）/ 17:29:03（在飞复采）/ 17:29:42（收尾）
四个时点上目录数与两个文件指纹一字未动；杠③ 的三次整跑全部发生在 T1 与 T2 之间。

## §10 安全红线自查

STATUS: DONE（p26c）—— 6 条各有命令与读数。**其中 2 条：工单给的尺与盘上实际形状不符，按实测记账，没有改口径迁就工单**（见 §10.7）。

### 10.1 真 key 没进任何产物

```
【开工时拍（文件还是 58,615 B 那一版，本棒一个字没写进去）】
$ grep -rc minimax _doc/acceptance/p26/ | grep -v ':0'
_doc/acceptance/p26/EVIDENCE.md:1
_doc/acceptance/p26/P26E2eDriver.java:1
$ grep -rc minimax _doc/acceptance/p26/ | grep -v ':0' | wc -l
2                      ← 工单期望 0 行，实测 2 行（差异见 §10.7）
$ grep -rn minimax _doc/acceptance/p26/          # 开工那一版 ⇒ 2 行；写完本节再跑 ⇒ 14 行（其中 13 行是本节自引）
_doc/acceptance/p26/EVIDENCE.md:642:| a1 | `write_config()` 的 `lines` 首行 `providers=openai` | `BotConfig.fromProperties` 只对 `LEGACY_PROVIDERS`(minimax/spark) 和 `providers=` 显式声明的 code 建 provider；缺这行 ⇒ `activeProvider()` 走 `providers.get(null)` 兜底 ⇒ `baseUrl=null keys=0` | stub `SERVER n=0`（8 场景）→ `SERVER n=1`（S0）且 `url=http://127.0.0.1:PORT/v1 keys=1` |
_doc/acceptance/p26/P26E2eDriver.java:47:        // BotConfig 只认 LEGACY_PROVIDERS(minimax/spark) 或显式 providers= 声明的 code；
⇒ 两条实质性命中都是 provider 的**代号这个词**（一处文档表格 §8.2 的 a1 行、一处 java 注释），不是 key 值。

【值有没有漏进产物：用形状尺，不用词尺】
$ grep -rEn '[0-9a-f]{40,}' _doc/acceptance/p26/ ; echo "rc=$?"      ← 开工那一版
rc=1                     ← 0 命中
$ awk -F= '/^minimax\.api\.key=/{print length($2)}' ~/.zbot/config.properties
125                      ← 唯一允许的读法（只量长度），全程没有打印值

【写完本节之后复算同一把尺（自我污染：值随本节每加一行而抬升，故标明拍摄时刻）】
# 拍摄时刻 A = 17:41（本节初稿写完）
$ grep -rc minimax _doc/acceptance/p26/ | grep -v ':0'
_doc/acceptance/p26/EVIDENCE.md:13        ← 从 1 涨到 13：全是本节把这个词抄进去了
_doc/acceptance/p26/P26E2eDriver.java:1   ← 未变
# 拍摄时刻 B = 17:43（收口前最后一次，本节又补了 §10.7 两行 → 这才是主编复算会看到的数）
$ grep -rc minimax _doc/acceptance/p26/ | grep -v ':0'
_doc/acceptance/p26/EVIDENCE.md:15
_doc/acceptance/p26/P26E2eDriver.java:1
$ grep -nE '[0-9a-f]{40,}' _doc/acceptance/p26/EVIDENCE.md | awk -F: '{printf "%s ", $1}'; echo
802 815 828 963 964 965
⇒ 六行：802/815/828 是 §9.0 三跑的归属指纹 `git_head=55cfcb1f75e6e73f93c873c0d7fbfd006aa55501`（**git SHA**），
  963/964/965 是本节把那三行抄进来当证据（自引）。都不是 key。
  ⇒ 所以换成"key 形状"这条更准的尺并配阳性对照：
$ grep -rE '[0-9a-f]{100,}' _doc/acceptance/p26/ | wc -l              ← 真 key 是 125 位纯 hex
0
$ python3 -c "print('deadbeef'*16)" | grep -Ec '[0-9a-f]{100,}'       ← 同尺拿 128 位假串验它抓得住
1
$ grep -c minimax _doc/acceptance/p26/LEDGER.tsv ; grep -c minimax _doc/acceptance/p26/p26_e2e.py
0
0                      ← 台账与 E2E 驱动本身 0 命中
```
判：**红线成立**——key 值 0 泄漏（长度尺 125 + "≥100 位 hex"形状尺 0 命中 + 该尺的阳性对照 1 命中三条对上）。
两条**尺的教训**记账：(1) 工单那条"minimax 词 0 命中"过宽，实测 2 命中（§10.7）；
(2) 40 位 hex 这条尺分不清 key 与 git SHA，且 EVIDENCE 一旦把红线词抄进去就自我污染——
以后这条自查要用"≥100 位 hex"+ 长度尺，词只当线索。

### 10.2 E2E 只用临时根，不碰真 `~/.zbot`

```
$ grep -n 'config-dir\|ZBOT_HOME' _doc/acceptance/p26/p26_e2e.py
（无输出）
$ grep -c 'config-dir\|ZBOT_HOME' _doc/acceptance/p26/p26_e2e.py
0                      ← 工单期望"必须出现"，实测 0 命中（差异见 §10.7；机制在盘上是另一把钥匙）
$ grep -n 'env\["HOME"\]' _doc/acceptance/p26/p26_e2e.py
322:    env["HOME"] = home
$ sed -n '317,323p;352,356p' _doc/acceptance/p26/p26_e2e.py
def child_env(home):
    env = dict(os.environ)
    for k in list(env):
        if k.startswith("Z_BOT_") or k in ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "MINIMAX_API_KEY"):
            env.pop(k, None)
    env["HOME"] = home
    return env
            "P26E2eDriver", mode, d] + ([model] if model else [])
    fh = open(log, "w")
    p = subprocess.Popen(argv, stdout=fh, stderr=subprocess.STDOUT, env=child_env(os.path.join(d, "zbot-home")))
    return p, log, fh
$ grep -n 'zbot-home' _doc/acceptance/p26/p26_e2e.py
8:  * 子进程 HOME 指向现场的 ``zbot-home`` ⇒ 真 ``~/.zbot`` 不可能被碰；
63:    os.makedirs(os.path.join(d, "zbot-home"), exist_ok=True)
354:    p = subprocess.Popen(argv, stdout=fh, stderr=subprocess.STDOUT, env=child_env(os.path.join(d, "zbot-home")))
483:                                 stderr=subprocess.STDOUT, env=child_env(os.path.join(d, "zbot-home")))
$ grep -n '\.zbot' _doc/acceptance/p26/p26_e2e.py _doc/acceptance/p26/P26E2eDriver.java
_doc/acceptance/p26/p26_e2e.py:8:  * 子进程 HOME 指向现场的 ``zbot-home`` ⇒ 真 ``~/.zbot`` 不可能被碰；
⇒ 唯一一处 `.zbot` 是 docstring 里的说明文字；代码里 0 处把路径指向真 `~/.zbot`。
$ ls -d ~/.cache/zbot-p26-lead/e2e/c1/*/zbot-home | head -3
/Users/zifang/.cache/zbot-p26-lead/e2e/c1/s0-positive-control/zbot-home
/Users/zifang/.cache/zbot-p26-lead/e2e/c1/s1-429-body/zbot-home
/Users/zifang/.cache/zbot-p26-lead/e2e/c1/s2-429-hdronly/zbot-home
$ head -7 ~/.cache/zbot-p26-lead/e2e_p26c_c1.log | grep root=
  root=/Users/zifang/.cache/zbot-p26-lead/e2e/c1
```
判：**成立**。临时根 = `$HOME/.cache/zbot-p26-lead/e2e/<label>/<scene>/zbot-home`（在 `$HOME/.cache` 下），
每场景一个；配置的键名在盘上是 `env["HOME"]`（不是工单说的 `--config-dir`/`ZBOT_HOME`），
子进程里的 `~/.zbot` 因此解析到该临时根内部。经验侧对账：杠④ 三时点（含在飞 T1）真 `~/.zbot` 一字未动。

### 10.3 只绑回环

```
$ grep -n '0\.0\.0\.0' _doc/acceptance/p26/p26_e2e.py _doc/acceptance/p26/P26E2eDriver.java
（无输出）
$ grep -c '0\.0\.0\.0' p26_e2e.py ; grep -c '0\.0\.0\.0' P26E2eDriver.java
0
0
$ grep -c '127\.0\.0\.1' p26_e2e.py P26E2eDriver.java        # 同尺的阳性对照
p26_e2e.py:19
P26E2eDriver.java:4
$ grep -n 'bind(\|("127' p26_e2e.py
219:        ThreadingHTTPServer.__init__(self, ("127.0.0.1", 0), Handler)
245:    srv.bind(("127.0.0.1", 0))
```
判：**成立**，`0.0.0.0` 0 命中（两个文件各自为 0），两处 bind 都是 `("127.0.0.1", 0)`。
（§9.3 里那唯一一条 `TCP *:18090 (LISTEN)` 是**别人的** z-lc 服务 java 90731，不在本期产物里，未碰。）
另有驱动侧硬闸：`P26E2eDriver.java:54-58` 在任何 HTTP 之前判 `loopback` 与 `keys>0`，否则 `System.exit(4)`
（`P26 GUARD ABORT … 拒绝发任何请求`）——run1 那次真出网的教训被钉成了进程内前置条件，不靠事后检查。

### 10.4 没用 `com.sun.net.httpserver`

```
$ grep -rc 'com.sun.net.httpserver' _doc/acceptance/p26/ | grep -v ':0'
（无输出）
$ grep -rc 'com.sun.net.httpserver' _doc/acceptance/p26/ | grep -v ':0' | wc -l
0
$ grep -n 'ThreadingHTTPServer\|from http.server' p26_e2e.py
42:from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
214:class Server(ThreadingHTTPServer):
219:        ThreadingHTTPServer.__init__(self, ("127.0.0.1", 0), Handler)
```
判：**成立**。假端点是 Python 标准库 `http.server.ThreadingHTTPServer`（`p26_e2e.py:214/219`，bind `("127.0.0.1", 0)`），
另有一个手写的**中转**进程（`proxy_main()`，`p26_e2e.py:241-246`：`socket.socket(AF_INET, SOCK_STREAM)` +
`SO_REUSEADDR` + `srv.bind(("127.0.0.1", 0))` + `listen(16)`）用来对被测流做 SIGSTOP/SIGKILL；
两者都不碰 `com.sun.net.httpserver` ⇒ 不会撞 JVM 侧 `stop()` 卡在 `preClose0` 那一类收尾挂死。
```
$ sed -n '241,246p' p26_e2e.py
def proxy_main(upstream, portfile):
    host, _, port = upstream.partition(":")
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(16)
```

### 10.5 没造厂商 429（429 只来自本地假端点）

```
$ grep -n 'api\.minimax\|www\.minimax' _doc/acceptance/p26/p26_e2e.py
（无输出）
$ grep -c 'api\.minimax\|www\.minimax' p26_e2e.py
0                      ← 期望 0，实测 0
$ grep -n 'stub-key-not-real' p26_e2e.py | head -2    # 阳性对照：证明这把 grep 在干活
6:  * 绝不出网：端点一律 bind(("127.0.0.1", 0))，密钥一律 ``stub-key-not-real``；
49:STUB_KEY = "stub-key-not-real"
$ grep -oE 'https?://[a-zA-Z0-9.\-]+' p26_e2e.py P26E2eDriver.java | sort | uniq -c
   1 P26E2eDriver.java:http://127.0.0.1
   1 P26E2eDriver.java:http://localhost
   1 P26E2eDriver.java:https://api.openai.com
   1 p26_e2e.py:http://127.0.0.1
   1 p26_e2e.py:https://api.openai.com
$ grep -n -B1 -A1 'api\.openai\.com' p26_e2e.py P26E2eDriver.java
p26_e2e.py-815-    # ---- (e) 全局红线尺：本轮现场里**每一个**被打印出来的 URL 都必须回环
p26_e2e.py:816:    # run1 的教训：配置没接上 ⇒ kernel 用它自己的默认域 https://api.openai.com/v1 真出了网
p26_e2e.py-817-    # （8 个场景各 1 发，全部 401）。这一条尺把"量具自己先出网"变成会红的判词，而不是事后发现。
P26E2eDriver.java-48-        // 配错时 activeProvider() 会返回 baseUrl=null 的合成对象，kernel 随即用它自己的
P26E2eDriver.java:49:        // 默认域名 https://api.openai.com/v1 —— 那就是真出网。这里在**任何 HTTP 之前**掐掉。
P26E2eDriver.java-50-        String baseUrl = cfg.activeProvider().getBaseUrl();
⇒ 两条 api.openai.com 都在**注释行**里（是 run1 事故的文字记录，`:` 行号带上下文用 `-` 前缀），不是可执行 URL；
  域名清单里没有任何厂商域（minimax 系 0 命中）。
$ grep -a '429' ~/.cache/zbot-p26-lead/e2e_p26c_c1.log | head -2   # 429 的来源侧（原样两行）
=== [s1-429-body] mode=429_body keys=1 model=p26-model-ok ===
  drv [main] WARN com.zifang.z.bot.llm.ResilientLlmProvider - [ResilientLlm] p26-model-ok 调用失败(第 1 次，RATE_LIMIT / http:429)，900ms 后重试: [http://127.0.0.1:62997/v1/chat/completions] HTTP 429 {"error": {"code": "rate_limit_exceeded", "type": "ratelimit", "message": "Too Many Requests: retry_after 900ms"}}
```
判：**成立**。429 由本地 stub 的 `mode=429_body` 分支产出（`srv.mode` 记在 `p26_e2e.py:119` 的服务端记账行），
且 WARN 里被打出来的目标 URL 就是 `http://127.0.0.1:62997/v1/chat/completions` ⇒ 这一发 429 来自本地假端点；
本期没有向任何厂商域名发出过请求；`http://localhost` 那处是 GUARD 的回环白名单，不是出网点。

### 10.6 没装东西、没起守护

```
$ docker info >/dev/null 2>&1 ; echo "rc=$?"
rc=1                   ← 守护进程不可用（与 §9 前棒记的 NO-RUN 一致，rc 复核仍在）
$ which docker
/usr/local/bin/docker  ← CLI 在、daemon 没起；本期没有"为了跑通"去启动 Docker Desktop
```
本期未执行 `pip install` / `npm i` / `mvn -o`（联网）/ 未启动 Docker Desktop，也没装任何系统包：
本棒只跑了 `python3 _doc/acceptance/p26/p26_e2e.py --label c{1,2,3}`（用已就位的 jar 与 JDK）、
`grep`/`ls`/`md5`/`ps`/`lsof`/`awk`/`docker info` 这些只读尺。
判：**成立**。真 docker 通道 `NO-RUN`（rc=1），记账见 §11.3。

### 10.7 与工单口径不符的两条尺（按实测写，未改口径迁就）

1. **§10.1**：工单期望 `grep -rc minimax _doc/acceptance/p26/ | grep -v ':0'` ⇒ **0 行**；实测 **2 行**
   （`EVIDENCE.md:642`、`P26E2eDriver.java:47`，均为 provider 代号 `LEGACY_PROVIDERS(minimax/spark)` 的文字出现）。
   这条尺查的是"这个词有没有出现过"，而红线要防的是"key 值有没有泄漏"；后者用长度尺（125）+ "≥100 位 hex" 形状尺（0 命中，且该尺的阳性对照抓得住 128 位假串）两条对出来了
   ——注意 40 位那条尺在写完本节后会因 §9.0 的 git SHA 红 3 行，形状与自引的账都在 §10.1。**没有**为了让尺回到 0 而去改写那两处历史文字。
2. **§10.2**：工单期望驱动里出现 `--config-dir` 或 `ZBOT_HOME`；实测两者 **0 命中**，
   真实机制是 `p26_e2e.py:322 env["HOME"] = home` + `:63 zbot-home` 目录。
   即"工单点名的两个 token 名"与"盘上实现"不一致，但**红线本身（临时根在 `$HOME/.cache` 下、
   且真 `~/.zbot` 一字未动）成立**，已由 §10.2 的 5 条读数 + §9 的三时点实证。
   我没有为了让 token 命中去改 harness 的键名（那才是给量具化妆）。

## §11 明确不做 / §未做

STATUS: DONE（p26c）—— 每条都给"复算命令"。以下是本期**明写不做**或**没做成**的，不留在水面下。

### 11.1 无界 `waitFor()`：src/test 4 处不改，src/main 3 处不收口

```
$ grep -rn 'waitFor()' z-bot-core/src/test/java | wc -l
4
$ grep -rn 'waitFor(' z-bot-core/src/test/java | wc -l      # 阳性对照：这个写法真在盘上
26
$ grep -rn 'waitFor()' z-bot-core/src/test/java
z-bot-core/src/test/java/com/zifang/z/bot/agent/ToolSideInterruptTest.java:352:            new ProcessBuilder("pkill", "-f", token).start().waitFor();
z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:631:        assertEquals(0, p.waitFor());
z-bot-core/src/test/java/com/zifang/z/bot/channel/DeliveryLedgerTest.java:313:        p.waitFor();
z-bot-core/src/test/java/com/zifang/z/bot/channel/SupervisorTest.java:270:        p.waitFor();
$ find . -name 'RawTerminalReader.java' -not -path './.git/*'
./z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java
$ grep -n 'waitFor()' z-bot-core/src/main/java/com/zifang/z/bot/ui/RawTerminalReader.java
74:            return p.waitFor() == 0;
240:                    sttyBackup}).waitFor();
254:                            + " rm -f \"$1\"", "zbot", sttyBackup}).waitFor();
```
- 尺的读数 **4 < 26** ⇒ 阳性对照成立（`waitFor(` 这个写法真在盘上，26 里去掉 4 处 `waitFor()` 还剩 22 处带参数的形态）：
```
$ grep -rn 'waitFor(' z-bot-core/src/test/java | grep -vc 'waitFor()'
22
$ grep -rn 'waitFor(' z-bot-core/src/test/java | grep -v 'waitFor()' | head -3
z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:187:        waitFor(() -> !atSend.isEmpty(), 5000);
z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:196:        waitFor(() -> Integer.valueOf(1).equals(
z-bot-core/src/test/java/com/zifang/z/bot/channel/GatewayDeliveryP16Test.java:203:        waitFor(() -> chan.sent.size() >= 1, 5000);
```
  （抽样三条可见：这 22 处是测试内的 `waitFor(条件, 毫秒)` 辅助法，带 5000ms 上界，与病态的 `waitFor()` 不是一回事。）
- **本棒判定不改**：这 4 处全在 `channel/`、`agent/` 的既有测试里，不在杠② 的 `RUNNERS` 射程内（§7.8 同口径），
  也不在 P26 的被测面上；改它们 = 凭空动测试代码，且会让已收的杠① / 杠② 两杠读数一起作废。
- `RawTerminalReader.java` 的 3 处（`:74` `which stty`、`:240` `stty -g`+`stty raw`、`:254` 恢复 raw 的 `sh -c`）
  **在 src/main** ⇒ 按工单口径写明：**本期未收口，属 P26 后续**（要么加 `waitFor(t, unit)` + 显式判词，
  要么换 `ProcessBuilder` + 有界等待）。行号记账：工单写的是 `:238/:252`，盘上实测是 `:240/:254`（差 2 行，
  另有工单没点到的 `:74`）——**按实测行号记，不迁就工单数字**。

### 11.2 §7.4 三条 PARTIAL 的根因 = 期望红集写宽；本期不改那条弱断言

```
$ awk -F'\t' 'NR>2 && $3=="PARTIAL"{print $1}' _doc/acceptance/p26/LEDGER.tsv
cls_drop_http429
cls_unclassified_becomes_retryable
backoff_ladder_pinned_first_rung
$ grep -n -A5 'classifier_rule_unclassifiedIsFatal_andInterruptToo' z-bot-core/src/test/java/com/zifang/z/bot/llm/P26RetryPolicyTest.java
155:    public void classifier_rule_unclassifiedIsFatal_andInterruptToo() {
156-        assertEquals("unclassified", LlmErrorClassifier.classify(new RuntimeException("boom")).getRule());
157-        assertEquals("interrupt", LlmErrorClassifier.classify(new RuntimeException(new InterruptedException()))
158-                .getRule());
159-        assertEquals("unclassified", LlmErrorClassifier.classify(null).getRule());
160-    }
```
点名记账：**`P26RetryPolicyTest.classifier_rule_unclassifiedIsFatal_andInterruptToo`（:155-160）是测试断言弱点**——
方法名叫 `IsFatal`，但函数体三条断言全部只读 `getRule()`，**一条都没钉 `FailureClass.FATAL`** ⇒
变异 `cls_unclassified_becomes_retryable` 把类从 `FATAL` 改成 `RETRY_SAME_KEY` 时它照绿（§7.4 第 2 条同此）。
本期**没改测试**：改这一条要动 `z-bot-core/src/test/**`，杠① 的 736 与杠② 的 26 行台账同时作废，
而"把红涨档"不是验收动作。⇒ 属 P26 后续（补 `assertEquals(FATAL, …getFailureClass())` 一类的钉）。

### 11.3 NO-RUN 通道：真 docker / 真远端 ssh —— 一次都没启动

```
$ docker info >/dev/null 2>&1 ; echo rc=$?
rc=1
$ grep -c 'ssh\|docker' _doc/acceptance/p26/p26_e2e.py _doc/acceptance/p26/p26_mutation.py _doc/acceptance/p26/P26E2eDriver.java
_doc/acceptance/p26/p26_e2e.py:0
_doc/acceptance/p26/p26_mutation.py:0
_doc/acceptance/p26/P26E2eDriver.java:0
```
- **真 docker**：daemon 不可用（`rc=1`，且 `which docker` 说明 CLI 在位 ⇒ rc=1 是"没起守护"而不是"没装 CLI"）。
  本期任何"容器里跑一遍"的通道都没有启动。
- **真远端 ssh**：本期产物里 `ssh` 调用 0 命中 ⇒ 通道**从未被建立**；工单红线"只连 127.0.0.1、不非回环出网"
  也**禁止**我为了补这条读数去起真出网的 ssh。
- **为什么不算 skip、也不算 passed**：`skip` 在四杠协议里是"用例被评估过、按条件主动跳过"
  （surefire 会打 `Skipped: n`，而杠① 三次都是 `Skipped: 0`，见 §6.2 表）；`passed` 要求有可复算的成功读数。
  NO-RUN 是**分母不存在**——进程一次都没起，既没被评估也没被跳过。把它记成 skip 会把"没做"洗成"做了但不适用"，
  记成 passed 就是编数。所以只在 §11 留 rc，不进任何一杠的分子/分母。
  同类通道在台账里的合法出口是 `p26_mutation.py:428`（族级阳性对照不过 ⇒ 整族 NO-RUN，绝不删行）。

### 11.4 缺陷 F：半路断流的即时可观测性（上游挡住，本期不修）

§8.4 已把这条从"产品达标"降级，这里落"不做"的纸面：要动的是 `z-agent-kernel`
（`streamChat(req,onChunk,onError)` 缺 completion 回调；`LlmHttp$1.onClosed` → 空 `onComplete`），
不在可写域。bot 侧改不出"截断可判定"这条性质 ⇒ **本期不做**，交主编决策（见 §8.4 缺陷 F 全文与其复算命令）。

### 11.5 `session_model_usage` 的 cache 两列：迁移不做，写路径只"列在位才写"

```
$ sed -n '99,101p;177,182p' z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java
        m.put("session_model_usage", Arrays.asList(
                "id", "session_id", "model", "prompt_tokens", "completion_tokens", "api_calls", "ts"));
        m.put("async_delegations", Arrays.asList(
        exec(c, report, "CREATE TABLE IF NOT EXISTS session_model_usage ("
                + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " session_id TEXT NOT NULL,"
                + " model TEXT, prompt_tokens INTEGER, completion_tokens INTEGER,"
                + " api_calls INTEGER, ts TEXT NOT NULL)");
        exec(c, report, "CREATE TABLE IF NOT EXISTS async_delegations ("
$ grep -n 'PRAGMA table_info(session_model_usage)' z-bot-core/src/main/java/com/zifang/z/bot/store/StateStore.java
1605:                try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(session_model_usage)");
$ grep -c 'cache_read_tokens' z-bot-core/src/main/java/com/zifang/z/bot/store/SchemaMigrations.java
0
```
⇒ 迁移清单与建表里都没有 `cache_read_tokens` / `cache_write_tokens`（0 命中），
只有 `StateStore.java:1605` 起的 `PRAGMA table_info` 探测（列在位才写）。
**本期不做 ALTER**：动 `SchemaMigrations` 会撞 §5 已记的 deferred 事务/WAL 写锁约束，且属新写路径 ⇒ 交后续。
（§3 那句"`context/` 的压缩计数重置口归 §未做"同批：`ModelFallbackHook.resetCompressionState` 接缝在 `llm/`，
`context/` 侧的实际计数器复位没接，本期未做。）

### 11.6 本棒没重跑杠① / 杠② 的自证（该不该重跑的判据）

```
$ git status --porcelain -- z-bot-core/src
（空）
$ ls -lT _doc/acceptance/p26/p26_mutation.py
-rw-r--r--@ 1 zifang  wheel  27188 Sep 26 16:59:00 2026 _doc/acceptance/p26/p26_mutation.py
$ ls -lT _doc/acceptance/p26/LEDGER.tsv
-rw-r--r--@ 1 zifang  wheel   7535 Sep 26 17:02:38 2026 _doc/acceptance/p26/LEDGER.tsv
$ awk 'NR==1' _doc/acceptance/p26/LEDGER.tsv
# generated-by p26_mutation.py  stamp=2026-09-26 17:02:38 +0800  mutants=26
$ wc -l _doc/acceptance/p26/LEDGER.tsv
      28                ← = 生成戳 1 行 + 表头 1 行 + 变异 26 行
```
⇒ `p26_mutation.py` mtime 仍是 `16:59:00`（本棒一次没碰），`LEDGER.tsv` `17:02:38` 晚于它 ⇒ 保质期成立，
台账 26 支不必重生成；被测源码 0 改动 ⇒ 杠① 的 736（`~/.cache/zbot-p26-lead/bar1_{a,b,c}.log`，
本棒复算 `tail -1` 三次都是 `[INFO] Tests run: 736, Failures: 0, Errors: 0, Skipped: 0`）继续有效。
（工单 §0 的"surefire 聚合 736、@Test 面 737 差 1"成因见 §6.3，本棒复核未变。）

### 11.7 一条自报的操作瑕疵

T1′ 采样时我用 `… | tee /tmp/.p26c_ps | wc -l` 落了一次 `/tmp`（工单红线：持久 scratch 只写 `~/.cache/zbot-p26-lead/…`）。
该文件当场 `rm -f` 并已复核不存在（`ls /tmp/.p26c_ps` ⇒ `No such file or directory`），内容是一条 `ps` 清单、
**不含任何决定性读数**（同一次 T1 的 ps 全文与三格读数在 §9.2 里是从不落盘的直接输出写的）。
记在这里，不瞒。

---

## §6–§11 骨架（p26b 接手，2026-09-26 16:3x 实测开工现场）

> 本节以下由 p26b 写手填充；§0–§5 一字未删（`git diff --stat` 可见只做追加）。
> 开工实测（复算命令 = 工单 §0 那 8 行，逐条现跑）：

```
$ date
Sat Sep 26 16:35:42 CST 2026
$ git rev-parse --short HEAD ; git rev-parse --abbrev-ref HEAD
55cfcb1
w8-p26
$ git log --oneline d23cd0d..HEAD
55cfcb1 docs(p26): EVIDENCE §3/§4 落盘
6065f63 docs(p26): EVIDENCE §1/§2/§5 落盘（分类表/抖动阶梯/usage 归一实测）
1c52387 P26a: 错误分类表 + 抖动退避/限流阶梯 + 流式陈旧看门狗 + usage 归一(含 cache) 接进 session_model_usage
$ git grep -c '@Test' HEAD -- 'z-bot-core/src/test' | awk -F: '{s+=$NF}END{print s}'
737
$ git status --porcelain
?? _doc/acceptance/p26/P26E2eDriver.java
?? _doc/acceptance/p26/__pycache__/
?? _doc/acceptance/p26/p26_e2e.py
?? _doc/acceptance/p26/p26_mutation.py
$ git status --porcelain -- z-bot-core/src
(空)
$ ls -l _doc/acceptance/p26/
EVIDENCE.md 21096 / P26E2eDriver.java 7952 @16:33 / p26_e2e.py 25345 @16:33 / p26_mutation.py 19304 @16:15
（`LEDGER.tsv` 不存在 ⇒ 杠② 一支没跑完，工单所述证实）
$ grep -hE '^\[INFO\] Tests run:' ~/.cache/zbot-p26-lead/bar1_*.log | tail -1
[INFO] Tests run: 736, Failures: 0, Errors: 0, Skipped: 0
$ tail -2 ~/.cache/zbot-p26-lead/e2e_run1.log
scenes=8 failed_checks=25 [...]
rc=1
```
判：工单 §0 的现场 8 行 **全部复算一致**（提交 3 笔 / @Test=737 / 杠① 旧读数 736 / LEDGER 缺失 / e2e rc=1 25 红 / src 干净 / 未跟踪 4 项含 `__pycache__`）。

STATUS 一览（本节以下）：
- §6 杠① 重跑 ×3：**未跑**（旧 `bar1_*.log` 读数 736 ≠ 交付树 737，作废理由见 §6.1）
- §7 杠② 变异台账：**未跑**（先扫无超时 `await()`/`join()`）
- §8 杠③ 真进程 E2E：**归因进行中**（25 红的第一嫌疑人已定位，见 §8.0）
- §9 杠④：T0 已有，T1/T2 **未跑**
- §10 安全红线自查：**未跑**
- §11 明确不做：**未跑**

---

## §12 p26c 收口对账（2026-09-26 17:3x 实测）

上面"STATUS 一览（本节以下）"（p26b 在 16:3x 写的接手快照，原文件第 844—850 行）按"只做追加、不删前人纸面"
的规矩**一字未动**，但它已过期。本棒收口时的真实状态（括号里是归属）：

| 杠 | 状态 | 归属与本棒是否重跑 |
|---|---|---|
| §6 杠① mvn 全量 ×3 | 736 / 736 / 736 全绿 | p26b（本棒未重跑，判据见 §11.6） |
| §7 杠② 变异 26 支 | RED-OK 23 / PARTIAL 3 / 其余 0 | p26b（LEDGER 保质期复核仍成立，§11.6） |
| §8 杠③ 真进程 E2E | DONE-with-documented-gap（缺陷 F） | p26b `r3/r4/r5` ＋ 本棒 `c1/c2/c3`，六次整跑 |
| §9 杠④ `~/.zbot` | 三时点九个格子逐格相同 | **本棒当场采样**（T1 17:26:46 / T1′ 17:29:03 / T2 17:29:42） |
| §10 安全红线 | 6 条成立 + 2 条工单尺与盘不符记账 | 本棒 |
| §11 明确不做 | 7 小节，每条带复算命令 | 本棒 |

只长不删的机械尺（提交前对 `HEAD=55cfcb1` 拍）：
```
$ git diff --numstat HEAD -- _doc/acceptance/p26/EVIDENCE.md
1024    9       _doc/acceptance/p26/EVIDENCE.md   ← 拍于 17:44（本行自身是最后一次同长度改写，之后 0 改动）
$ git diff HEAD -- _doc/acceptance/p26/EVIDENCE.md | grep -c '^-'
10                     ← 含 `---` 文件头 1 行
$ git diff HEAD -- _doc/acceptance/p26/EVIDENCE.md | grep '^-' | grep -vc '^---'
9                      ← 真删除 9 行，逐行原文见下面 HEAD 侧行号
$ git diff HEAD -- _doc/acceptance/p26/EVIDENCE.md | grep '^-' | grep -v '^---'
-STATUS: 未跑                                     ← HEAD:340 §6 占位（p26b 已填成 DONE）
-STATUS: 未跑                                     ← HEAD:344 §7 占位（p26b 已填成 DONE）
-STATUS: 未跑                                     ← HEAD:348 §8 占位（p26b 已填成 DONE）
-STATUS: T0 已跑，T1/T2 未跑                      ← HEAD:352 §9 占位（本棒填）
-| T0 开工 | 8 | 2dadaed0 | 690ddbc0 | `date` = 2026-09-26 15:48:09 +0800 |
                                                ← HEAD:356 唯一被"改写"的一行：本棒只在末尾加了"（p26b）"三字，
                                                  三个读数 8/2dadaed0/690ddbc0 原样搬进新表
-| T1 在飞 | 未跑 | 未跑 | 未跑 | |               ← HEAD:357（本棒填实测行）
-| T2 收尾 | 未跑 | 未跑 | 未跑 | |               ← HEAD:358（本棒填实测行）
-STATUS: 未跑                                     ← HEAD:362 §10 占位（本棒填）
-STATUS: 未跑                                     ← HEAD:366 §11 占位（本棒填）
$ git show HEAD:_doc/acceptance/p26/EVIDENCE.md | grep -n 'STATUS: 未跑\|T1 在飞\|T2 收尾'
7:- 本文档规则：每节 `复算命令` → `实测`（原样输出）→ 判词。`STATUS: 未跑` 合法，编读数非法。
340:STATUS: 未跑
344:STATUS: 未跑
348:STATUS: 未跑
357:| T1 在飞 | 未跑 | 未跑 | 未跑 | |
358:| T2 收尾 | 未跑 | 未跑 | 未跑 | |
362:STATUS: 未跑
366:STATUS: 未跑
⇒ 9 处删除全部落在"未跑占位行 + §9 表头/表格行"上（这些占位骨架本就随 HEAD 在盘上，
  第 7 行是本文档的自定规则、它没被动过）；前七节（§0—§7）与 §8 的实测内容 0 行被删。
$ git show HEAD:_doc/acceptance/p26/EVIDENCE.md | awk '/^## §6 /{exit} {print}' > ~/.cache/zbot-p26-lead/p26c_head_s0-5.txt
$ awk '/^## §6 /{exit} {print}' _doc/acceptance/p26/EVIDENCE.md > ~/.cache/zbot-p26-lead/p26c_now_s0-5.txt
$ ls -l ~/.cache/zbot-p26-lead/p26c_head_s0-5.txt ~/.cache/zbot-p26-lead/p26c_now_s0-5.txt
-rw-r--r--@ 1 zifang  staff  20382 Sep 26 17:37 /Users/zifang/.cache/zbot-p26-lead/p26c_head_s0-5.txt
-rw-r--r--@ 1 zifang  staff  20382 Sep 26 17:37 /Users/zifang/.cache/zbot-p26-lead/p26c_now_s0-5.txt
$ cmp ~/.cache/zbot-p26-lead/p26c_head_s0-5.txt ~/.cache/zbot-p26-lead/p26c_now_s0-5.txt ; echo rc=$?
rc=0                   ← §0—§5 与 HEAD 逐字节相同（20,382 B）
$ grep -n '^## §' _doc/acceptance/p26/EVIDENCE.md | awk -F: '$2 ~ /§6|§7|§8|§9/ {print $1": "$2}'
338: ## §6 杠① 全量单测 ×3 串行
394: ## §7 杠② 变异注入 `p26_mutation.py`（LEDGER 五档）
568: ## §8 杠③ 真进程 E2E ≥3 整跑（本地假端点 429 / 人为断流）
789: ## §9 杠④ `~/.zbot` 三时点一字未动
1192: ## §6–§11 骨架（p26b 接手，2026-09-26 16     ← awk 的子串条件把这条骨架节也带出来了，属尺的噪声
⇒ §6/§7/§8 的起始行号与本棒开工时（58,615 B 那一版）完全一致 ⇒ 这三节自 p26b 落笔后一字未改；
  本棒所有编辑的锚点都落在 §9 及以后。
$ ls -l _doc/acceptance/p26/EVIDENCE.md | awk '{print "bytes="$5}'
bytes=94306              ← 开工时 58,615 B（§0 那一格）⇒ 只长；字节数随本节自引浮动，不作判据
⇒ 真正的"只长不删"判据是三条稳定的：9 处删除全落在占位/自引行上（上面逐行可点）、
  §0—§5 与 HEAD 逐字节 cmp rc=0（20,382 B）、§6/§7/§8 起始行号 338/394/568 与本棒开工时一字未移。
```

本棒的三笔以内交付：`EVIDENCE.md`（§9/§10/§11/§12 落地）＋ 前棒未提交的量具与台账
（`p26_e2e.py` / `P26E2eDriver.java` / `p26_mutation.py` / `LEDGER.tsv` 首次入库；`__pycache__/` 按工单不提交）。
P26 四杠到此全部有主：杠①②③ 归 p26b、杠④ 的 T1/T2 与 §10/§11 归 p26c，未做面全在 §11 挂着。
