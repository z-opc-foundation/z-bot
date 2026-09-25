package com.zifang.z.bot.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;

/**
 * {@code deliver=local} 的落地：把结果打印到标准输出（缺省）/ 注入的流。
 *
 * <p>hermes 的 local 档同样"不发任何消息"（{@code scheduler.py:1459} 直接
 * {@code return None}），差别在于她总是把完整输出落进 {@code last_output} 供
 * {@code cron list} 看。我们这里等价物是 jobs.json 的 {@link CronJob#lastResult}
 * （200 字截断，沿用既有形状）+ 这一行的打印。没有通道时任务结果不再静默蒸发。</p>
 */
public final class LocalCronDelivery implements CronDelivery {

    private static final Logger LOG = LoggerFactory.getLogger(LocalCronDelivery.class);

    private final PrintStream out;

    public LocalCronDelivery() {
        this(System.out);
    }

    /** 单测/E2E 注入自己的流，好对"到底打没打"下断言。 */
    public LocalCronDelivery(PrintStream out) {
        this.out = out == null ? System.out : out;
    }

    @Override
    public String deliver(CronJob job, String text) {
        String body = text == null ? "" : text;
        out.println("[cron] " + job.name + " (" + job.id + ") @ local\n" + body);
        out.flush();
        LOG.info("[cron] {} 结果已按 local 投递（{} 字符）", job.id, body.length());
        return null;
    }
}
