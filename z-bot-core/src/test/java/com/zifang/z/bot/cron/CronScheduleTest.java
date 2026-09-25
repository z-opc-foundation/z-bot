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
}