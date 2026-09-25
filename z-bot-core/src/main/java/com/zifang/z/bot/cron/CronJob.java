package com.zifang.z.bot.cron;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * 一条定时任务。持久化在 {@code <configDir>/cron/jobs.json}（数组）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CronJob {

    /** 一次性任务（{@code once <ISO>}）的可投递次数上限 —— 只跑一回（hermes 的 repeat.times=1）。 */
    public static final int ONESHOT_DISPATCH_LIMIT = 1;

    public String id;
    public String name;
    /** 到点后交给 agent 执行的任务描述。 */
    public String prompt;
    /** 调度表达式（every Ns/Nm/Nh | hourly | daily HH:MM | once &lt;ISO-8601&gt;）。 */
    public String schedule;
    public boolean enabled = true;
    /** 最近一次触发时间（ISO-8601），纯台账。 */
    public String lastRun;
    /** 最近一次执行结果摘要，供 /cron list 查看。 */
    public String lastResult = "";
    /**
     * 投递路由（对齐 hermes {@code job["deliver"]}）：{@code local}（缺省，打印）/
     * {@code origin}（回到建任务的那个会话）/ {@code <通道名>[:会话]} / {@code all}，
     * 可用逗号组合。解析与降级在 {@link ChannelCronDelivery} 里。
     */
    public String deliver = "local";
    /**
     * 建任务那一刻的会话来源，形如 {@code feishu:oc_123}；拿不到就是 {@code null}。
     *
     * <p>诚实记账：z-bot 现在<b>没有任何</b>地方能填它 —— {@code BotAgent} 不持有会话/通道身份
     * （{@code ChannelBus.handle} 只把 conversationId 传给 {@code forkFor}，来源通道名进了 agent
     * 就丢），{@code state.db} 的 {@code gateway_routing} 表也还没有生产写入方（只有单测在写）。
     * 所以 {@code deliver=origin} 目前必然走"降级 local"分支 —— 这正是 hermes 对 CLI 建的任务
     * 的既有行为（{@code cron/scheduler.py:1461-1473}，工单引的 #43014 结论），
     * 不是把 origin 装出来。等 P16 的通道身份落地后由建任务的一方填这个字段。</p>
     */
    public String origin;
    /** 已认领的投递次数（先落账再跑，红线 8）。只有一性任务会累加。 */
    public int dispatches;
    /** 正在执行时的认领记录；{@code null} = 不在跑。 */
    public CronRunClaim runClaim;
    /** 最近一次投递的结论：{@code ok} / 错误说明 / {@code ""}（没投过）。 */
    public String lastDelivery = "";

    public CronJob() {
    }

    public CronJob(String id, String name, String prompt, String schedule) {
        this.id = id;
        this.name = name;
        this.prompt = prompt;
        this.schedule = schedule;
    }

    /** 解析并校验调度表达式；坏表达式抛 IllegalArgumentException。 */
    public transient CronSchedule parsed;

    public CronSchedule schedule() {
        if (parsed == null) {
            parsed = CronSchedule.parse(schedule);
        }
        return parsed;
    }

    /**
     * 一次性任务？表达式坏掉时按"不是"处理（坏任务本来就被 load() 禁用了），
     * 免得一次解析异常把整轮投递判定带崩。
     *
     * <p>{@code @JsonIgnore}：它是 {@link #schedule} 的派生值，不进 jobs.json
     * （少了 {@code is} 前缀污染，也免得读到一半的盘上状态被当成事实）。</p>
     */
    @JsonIgnore
    public boolean isOneShot() {
        try {
            return schedule().kind == CronSchedule.Kind.ONCE;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void markRun(String result) {
        this.lastRun = Instant.now().toString();
        this.lastResult = result == null ? "" :
                (result.length() > 200 ? result.substring(0, 200) + "…" : result);
    }
}
