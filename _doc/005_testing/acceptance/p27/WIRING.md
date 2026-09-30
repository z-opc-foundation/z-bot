# P27 委托面 —— 待主编下手的接线（本棒一行都没改 `agent/BotAgent.java` / `config/BotConfig.java` / `session/**`）

本期产品代码全部落在写域 `z-bot-core/src/main/java/com/zifang/z/bot/delegate/**` 内，
**台账定址不需要新配置项、也不需要动 `BotAgent`**：
`DelegateManager.ledgerRootFor(config, childSessionDir)` 从装配点已经传进来的
`childSessionDir`（= `<configDir>/delegate/children`，`BotAgent.java:1852-1854`）推父目录，
落 `<configDir>/delegate/live`。`config == null` 且推不出父目录时返回 `null`，台账整体降级 no-op。

下面是**只有你有权改的文件**里的建议 hunk。行号是本棒在 `w11-p27`（基线 `53222e1`）上实测的，
`BotAgent.java` 正被 p14/p23/p26 三支在写，你下手前请重新定位。

---

## 接线 1 —— 启动时扫一次委托现场（孤儿认领 + 保留期回收）

不接的后果：`<configDir>/delegate/live` 只增不减，被 `kill -9` 的委托现场永远停在 `RUNNING`，
`adoptOrphans` / `pruneStale` 两个能力在产品里没人调（单测/E2E 会调，真进程不会）。

`z-bot-core/src/main/java/com/zifang/z/bot/agent/BotAgent.java` 构造器内，`delegation.attach(this)` 之后：

```java
        if (delegation != null) {
            delegation.attach(this);
+           // P27：把上一次进程留下的委托现场判一遍孤儿并按保留期回收（写盘旁路，不抛）。
+           delegation.sweepAtStartup();
        }
```

（`sweepAtStartup()` 是 `DelegateManager` 本期新增的薄壳，等价于
`System.err` 打一行 `sweepLiveLedger()` 的 `adopted=N pruned=M` 判词；
只在 `liveLedger().enabled()` 时才打，测试桩 `config == null` 推不出根目录时一行都不打。）

## 接线 2 —— 重启后把"结果在盘上但从没被接走"的委托回投一次

她的对应物是 `async_delegation.py:287 restore_undelivered_completions`
（`WHERE state != 'running' AND delivery_state='pending'`）。z-bot 侧读侧 API 本期已经建好：

```java
    /** 只捞 PENDING：DROPPED / DELIVERED 都不参与重放（上限的全部意义）。 */
    public List<DelegationLedger.Entry> undeliveredTerminalResults()
```

建议接在 REPL 首轮之前（`z-bot-core/src/main/java/com/zifang/z/bot/cli/ReplCommand.java`
里 `agent` 构造完成、进 loop 之前），把这批结果作为**一条新的用户/内部轮**打印出来并顺手
`claim + complete`；或者只做提示、由用户 `/background result <id>` 自取。

**注意**：`asyncResult(id)` 已经内置 claim+ack，所以接 2 只该"提示"，
不要在启动时用 `claim()` 去预领（领了不 ack 就是白烧一次投递配额，8 次之后被判 `DROPPED`）。

## 接线 3 —— `/agents` 想再加一行投递汇总（可选，本期不改也能看到每行的投递格）

`describeAsync()` 每条已经带 `投递=PENDING(0/8)` 这样的列；如果还想在末尾加总量，
`z-bot-core/src/main/java/com/zifang/z/bot/slash/SlashRegistry.java:419-421`：

```java
            @Override
            public String execute(BotAgent agent, String args) {
                return agent.describeAgents();
            }
```

需要 `BotAgent` 上加一个 `describeDeliveries()` 转发（本棒没权加）；
`DelegateManager.describeDeliveries()` 已就绪，返回 `delivery.summary()`
（`delegations=N pending=x claimed=y delivered=z dropped=w maxAttempts=k/8`）。

## 接线 4 —— 配置项（本棒没动 `BotConfig.java`，若要可调请由你决定）

| 常量 | 现值 | 位置 | 该不该可调 |
|---|---|---|---|
| `DelegationDelivery.MAX_DELIVERY_ATTEMPTS` | 8 | `delegate/DelegationDelivery.java` | **不建议**：它是取证锚（她的 `async_delegation.py:84` 同值），漂了就对不上参照 |
| `DelegationLedger.LIVE_RETENTION_DAYS` | 7 | `delegate/DelegationLedger.java` | 她那边明确 "No config knobs"（`delegation_live_log.py` 头注释），建议跟齐：写死 |
| `DelegationLedger.ORPHAN_STALE_MILLIS` | 30 min | 同上 | 若以后补心跳线程再考虑 |
| `DelegationDelivery.CLAIM_LEASE_MILLIS` | 300 s | 同上 | 她那边是 `now - 300` 硬编码，建议跟齐 |
