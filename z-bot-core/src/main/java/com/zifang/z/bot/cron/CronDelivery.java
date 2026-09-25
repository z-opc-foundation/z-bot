package com.zifang.z.bot.cron;

/**
 * cron 结果的投递口（对齐 hermes {@code cron/scheduler.py:1445 _deliver_result}）。
 *
 * <p>hermes 的语义本子里被 §1#6 指着打脸的那句是"投递到 channel"：她的实现里
 * {@code _deliver_result} 是真发消息，而我们原先只把 {@code TaskRunner} 的返回值写进
 * jobs.json 的 lastResult。这个接口就是那个缺失的出口，路由在 {@link ChannelCronDelivery}
 * （通道投递）与 {@link LocalCronDelivery}（打印）两个实现里。</p>
 *
 * <p>约定同 hermes：返回 {@code null} 表示投递成功，非 {@code null} 是给人看的失败原因，
 * 由 {@link CronScheduler} 写进 {@link CronJob#lastDelivery}。投递异常<b>不</b>回滚任务结果，
 * 也<b>不</b>静默吞掉（红线 8 的另一半：宁可标注重复也不静默重发）。</p>
 */
public interface CronDelivery {

    /** 成功返回 null；失败返回一句能进 jobs.json 的原因。 */
    String deliver(CronJob job, String text);
}
