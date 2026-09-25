package com.zifang.z.bot.cron;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简化版调度表达式（不引 cron 库，对齐 hermes 的常用粒度）：
 * <ul>
 *   <li>{@code every 30s} / {@code every 5m} / {@code every 2h} — 固定间隔；</li>
 *   <li>{@code hourly} — 每小时整点；</li>
 *   <li>{@code daily 09:30} — 每天 HH:MM；</li>
 *   <li>{@code once 2026-09-25T21:40:00+08:00} — 一次性任务（绝对时刻，不带时区按本机）。
 *       只有一性任务走 {@code claimDispatch} 的"至多一次"记账。</li>
 * </ul>
 * {@link #due(ZonedDateTime, ZonedDateTime)} 由 scheduler 每个 tick 调用：
 * lastTick 到 now 之间是否跨过了下一个触发点。
 */
public final class CronSchedule {

    private static final Pattern EVERY = Pattern.compile("every\\s+(\\d+)\\s*(s|sec|seconds|m|min|minutes|h|hr|hours)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY = Pattern.compile("daily\\s+(\\d{1,2}):(\\d{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONCE = Pattern.compile("once\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    enum Kind { INTERVAL, HOURLY, DAILY, ONCE }

    final Kind kind;
    final long intervalSeconds;
    final int hour;
    final int minute;
    /** 仅 {@link Kind#ONCE} 非空：到点后即可触发（绝对时刻，落盘可被别的进程读到）。 */
    final Instant onceAt;

    private CronSchedule(Kind kind, long intervalSeconds, int hour, int minute, Instant onceAt) {
        this.kind = kind;
        this.intervalSeconds = intervalSeconds;
        this.hour = hour;
        this.minute = minute;
        this.onceAt = onceAt;
    }

    public static CronSchedule parse(String expr) {
        String e = expr == null ? "" : expr.trim().toLowerCase(Locale.ROOT);
        Matcher m = EVERY.matcher(e);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            long seconds;
            switch (m.group(2).charAt(0)) {
                case 's': seconds = n; break;
                case 'm': seconds = n * 60; break;
                default:  seconds = n * 3600; break;
            }
            if (seconds <= 0) {
                throw new IllegalArgumentException("间隔必须为正: " + expr);
            }
            return new CronSchedule(Kind.INTERVAL, seconds, -1, -1, null);
        }
        if ("hourly".equals(e)) {
            return new CronSchedule(Kind.HOURLY, 3600, -1, 0, null);
        }
        m = DAILY.matcher(e);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h > 23 || min > 59) {
                throw new IllegalArgumentException("非法时刻: " + expr);
            }
            return new CronSchedule(Kind.DAILY, 86400, h, min, null);
        }
        m = ONCE.matcher(e);
        if (m.matches()) {
            return new CronSchedule(Kind.ONCE, 0, -1, -1, parseInstant(m.group(1), expr));
        }
        throw new IllegalArgumentException(
                "无法解析的调度表达式: " + expr + "（支持 every Ns/Nm/Nh | hourly | daily HH:MM | once <ISO-8601>）");
    }

    /** 接受 {@code 2026-09-25T21:40:00+08:00} / {@code ...Z} / 不带时区（按本机时区）。 */
    private static Instant parseInstant(String raw, String expr) {
        String v = raw;
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(v).atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(v).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException("一次性任务的时刻无法解析: " + expr
                    + "（要 once 2026-09-25T21:40:00+08:00 这类 ISO-8601）");
        }
    }

    /**
     * 上个 tick（exclusive）到本 tick（inclusive）之间是否触发了调度。
     * 首次运行（lastTick 为 null）视为不触发，等下一个周期。
     */
    public boolean due(ZonedDateTime lastTick, ZonedDateTime now) {
        if (lastTick == null || !now.isAfter(lastTick)) {
            return false;
        }
        switch (kind) {
            case INTERVAL:
                long firstFire = lastTick.toEpochSecond() - lastTick.toEpochSecond() % intervalSeconds
                        + intervalSeconds;
                return now.toEpochSecond() >= firstFire;
            case HOURLY:
                return crossedHourly(lastTick, now);
            case DAILY:
                LocalTime target = LocalTime.of(hour, minute);
                return crossedDaily(lastTick, now, target);
            case ONCE:
                // 到点之后恒为 due：真正的"至多一次"由 claimDispatch 的持久记账保证
                // （跑之前先把 dispatches 落盘，第二个进程读到就拒），不靠调度表达式失忆。
                return !now.toInstant().isBefore(onceAt);
            default:
                return false;
        }
    }

    private static boolean crossedHourly(ZonedDateTime last, ZonedDateTime now) {
        ZonedDateTime t = last.truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime end = now.truncatedTo(ChronoUnit.MINUTES);
        while (t.isBefore(end)) {
            t = t.plusMinutes(1);
            if (t.getMinute() == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean crossedDaily(ZonedDateTime last, ZonedDateTime now, LocalTime target) {
        ZonedDateTime t = last.truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime end = now.truncatedTo(ChronoUnit.MINUTES);
        while (t.isBefore(end)) {
            t = t.plusMinutes(1);
            if (t.toLocalTime().truncatedTo(ChronoUnit.MINUTES).equals(target)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        switch (kind) {
            case INTERVAL: return "every " + intervalSeconds + "s";
            case HOURLY:   return "hourly";
            case ONCE:     return "once " + onceAt;
            default:       return "daily " + LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm"));
        }
    }
}
