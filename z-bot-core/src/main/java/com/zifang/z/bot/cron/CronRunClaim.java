package com.zifang.z.bot.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 一条任务"正在被谁跑"的持久认领记录（对齐 hermes {@code job["run_claim"]} = {@code {"at","by"}}，
 * 见 {@code cron/jobs.py:2138} 打认领、{@code :1943} 判新鲜度、{@code :1673} 心跳保鲜）。
 *
 * <p>为什么要写进 jobs.json 而不只靠 {@link java.nio.channels.FileLock}：
 * FileLock 只覆盖"进程还活着"的窗口，进程一旦死掉锁自动释放，
 * 而 jobs.json 里的认领记录仍在 —— 于是"另一个进程重启后能不能重跑这条任务"
 * 变成一个可以按时间判定的问题，而不是"上次那个进程是不是死了"这种猜不准的问题。</p>
 *
 * <p>过期语义严格等于"认领的那个进程死了"（工单原话）：{@link #isLive} 只认
 * {@code 0 <= age < ttl}。年龄为负（对端时钟超前 / 跨重启的时钟漂移）判<b>不</b>新鲜 ——
 * 否则一条未来时间戳的认领会把任务永久卡死（hermes #60703 踩过的同一个洞）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CronRunClaim {

    /** 所有者标识：{@code pid@进程启动时间/随机后缀}，见 {@link CronScheduler#ownerId()}。 */
    public String by;
    /** 最近一次认领/心跳时间（ISO-8601 instant）。 */
    public String at;

    public CronRunClaim() {
    }

    public CronRunClaim(String by, Instant at) {
        this.by = by;
        this.at = at == null ? null : at.toString();
    }

    /** 认领是否还"活着"：新鲜（{@code 0 <= age < ttlMillis}）。缺字段 / 时间戳坏掉一律判死，让任务能被重新认领。 */
    public boolean isLive(Instant now, long ttlMillis) {
        if (by == null || by.isEmpty() || at == null || at.isEmpty()) {
            return false;
        }
        final Instant claimedAt;
        try {
            claimedAt = Instant.parse(at);
        } catch (DateTimeParseException e) {
            return false;
        }
        long ageMillis = Duration.between(claimedAt, now).toMillis();
        return ageMillis >= 0 && ageMillis < ttlMillis;
    }

    /** 是不是这条认领的主人（心跳保鲜要 compare-and-refresh，防止睡一觉醒来的旧跑者给别人的认领续命）。 */
    public boolean heldBy(String owner) {
        return by != null && by.equals(owner);
    }

    /** 心跳续期：只改时间戳，不换主人。 */
    public void beat(Instant now) {
        this.at = now.toString();
    }

    @Override
    public String toString() {
        return "runClaim{" + by + " @ " + at + "}";
    }
}
