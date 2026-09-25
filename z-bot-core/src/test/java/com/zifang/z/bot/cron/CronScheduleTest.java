package com.zifang.z.bot.cron;

import org.junit.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** {@link CronSchedule} 表达式解析 + due 边界单测。 */
public class CronScheduleTest {

    @Test
    public void parseIntervalEverySeconds() {
        CronSchedule s = CronSchedule.parse("every 30s");
        assertEquals(CronSchedule.Kind.INTERVAL, s.kind);
        assertEquals(30L, s.intervalSeconds);
    }

    @Test
    public void parseIntervalEveryMinutesAndHours() {
        assertEquals(300L, CronSchedule.parse("every 5m").intervalSeconds);
        assertEquals(7200L, CronSchedule.parse("every 2h").intervalSeconds);
        assertEquals(60L, CronSchedule.parse("every 1min").intervalSeconds);
    }

    @Test
    public void parseHourly() {
        CronSchedule s = CronSchedule.parse("hourly");
        assertEquals(CronSchedule.Kind.HOURLY, s.kind);
    }

    @Test
    public void parseDaily() {
        CronSchedule s = CronSchedule.parse("daily 09:30");
        assertEquals(CronSchedule.Kind.DAILY, s.kind);
        assertEquals(9, s.hour);
        assertEquals(30, s.minute);
        assertEquals("daily 09:30", s.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsUnknown() {
        CronSchedule.parse("whenever");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsZeroInterval() {
        CronSchedule.parse("every 0s");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseRejectsBadTime() {
        CronSchedule.parse("daily 25:00");
    }

    @Test
    public void intervalDueWhenCrossesBoundary() {
        CronSchedule s = CronSchedule.parse("every 10s");
        ZonedDateTime last = ZonedDateTime.of(2026, 9, 25, 9, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = last.plusSeconds(11);
        assertTrue(s.due(last, now));
    }

    @Test
    public void intervalNotDueWithinOnePeriod() {
        CronSchedule s = CronSchedule.parse("every 10s");
        ZonedDateTime last = ZonedDateTime.of(2026, 9, 25, 9, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = last.plusSeconds(9);
        assertFalse(s.due(last, now));
    }

    @Test
    public void intervalNotDueOnFirstTick() {
        CronSchedule s = CronSchedule.parse("every 10s");
        ZonedDateTime now = ZonedDateTime.now();
        assertFalse(s.due(null, now));
    }

    @Test
    public void dailyDueWhenCrossesDailyTime() {
        CronSchedule s = CronSchedule.parse("daily 09:30");
        ZonedDateTime last = ZonedDateTime.of(2026, 9, 25, 9, 29, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = last.plusMinutes(2);
        assertTrue(s.due(last, now));
    }

    @Test
    public void dailyNotDueWhenStillSameDayBefore() {
        CronSchedule s = CronSchedule.parse("daily 09:30");
        ZonedDateTime last = ZonedDateTime.of(2026, 9, 25, 9, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = last.plusMinutes(15);
        assertFalse(s.due(last, now));
    }

    @Test
    public void hourlyDueWhenCrossesTopOfHour() {
        CronSchedule s = CronSchedule.parse("hourly");
        ZonedDateTime last = ZonedDateTime.of(2026, 9, 25, 9, 59, 30, 0, ZoneId.of("UTC"));
        ZonedDateTime now = last.plusMinutes(2);
        assertTrue(s.due(last, now));
    }

    @Test
    public void intervalDueExactBoundary() {
        CronSchedule s = CronSchedule.parse("every 10s");
        ZonedDateTime last = ZonedDateTime.of(2026, 9, 25, 9, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime now = last.plusSeconds(10);
        assertTrue(s.due(last, now));
    }

    @Test
    public void toStringForHourly() {
        assertEquals("hourly", CronSchedule.parse("hourly").toString());
    }

    @Test
    public void toStringForInterval() {
        assertEquals("every 60s", CronSchedule.parse("every 1m").toString());
    }

    @Test
    public void localTimeHelperSanity() {
        assertEquals(LocalTime.of(9, 30), LocalTime.of(9, 30));
    }

    // ===== once（P17 的一次性任务：at-most-once 的判定入口） =====

    @Test
    public void parseOnceWithOffsetAndWithZ() {
        CronSchedule s = CronSchedule.parse("once 2026-09-26T09:00:00+08:00");
        assertEquals(CronSchedule.Kind.ONCE, s.kind);
        assertEquals(java.time.Instant.parse("2026-09-26T01:00:00Z"), s.onceAt);
        assertEquals(java.time.Instant.parse("2026-09-26T01:00:00Z"),
                CronSchedule.parse("ONCE 2026-09-26T01:00:00Z").onceAt);
    }

    @Test
    public void parseOnceWithoutZoneUsesLocalZone() {
        CronSchedule s = CronSchedule.parse("once 2026-09-26T09:00:00");
        assertEquals(java.time.LocalDateTime.parse("2026-09-26T09:00:00")
                        .atZone(ZoneId.systemDefault()).toInstant(),
                s.onceAt);
    }

    @Test
    public void onceIsDueOnlyAtAndAfterTheInstant() {
        CronSchedule s = CronSchedule.parse("once 2026-09-26T09:00:00Z");
        ZonedDateTime before = ZonedDateTime.of(2026, 9, 26, 8, 59, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime after = ZonedDateTime.of(2026, 9, 26, 9, 0, 0, 0, ZoneId.of("UTC"));
        assertFalse("没到点不许触发", s.due(before.minusHours(1), before));
        assertTrue("到点即触发", s.due(before, after));
        // 到点之后恒为 due —— 真正的"至多一次"由 claimDispatch 的落账记账收口，
        // 不靠调度表达式在第二个 tick 上失忆（那样只是把重跑换成撞运气）。
        assertTrue(s.due(after, after.plusHours(5)));
    }

    @Test
    public void onceRoundTripsThroughToStringAndRejectsGarbageInstant() {
        assertTrue(CronSchedule.parse("once 2026-09-26T09:00:00Z").toString().startsWith("once "));
        try {
            CronSchedule.parse("once 明天上午");
            org.junit.Assert.assertFalse("坏时刻必须当场拒", true);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("明天上午"));
        }
    }
}