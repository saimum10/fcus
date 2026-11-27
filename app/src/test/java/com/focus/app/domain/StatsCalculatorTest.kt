package com.focus.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Statistics math: day and week buckets, midnight and week-end splits, DST, ignored rows.
 * 2026-11-23 is a Monday; "now" below is Wednesday 2026-11-25 12:00. Run in Phase 8.
 */
class StatsCalculatorTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val min = 60_000L
    private val hour = 60 * min

    private fun at(zone: ZoneId, text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val now = at(utc, "2026-11-25T12:00:00")

    private fun session(
        id: String,
        start: Long,
        active: Long,
        outcome: SessionOutcome = SessionOutcome.Completed,
    ) = FinishedSession(
        id = id,
        mode = FocusMode.FullFocus,
        appCount = 0,
        plannedMinutes = 60,
        startedAtWallMillis = start,
        endedAtWallMillis = start + active,
        activeMillis = active,
        outcome = outcome,
        reason = when (outcome) {
            SessionOutcome.Completed -> null
            SessionOutcome.Interrupted -> EndReason.RecoveryUsed
            SessionOutcome.Failed -> EndReason.InternalError
        },
    )

    private fun compute(vararg sessions: FinishedSession, nowMillis: Long = now, zone: ZoneId = utc) =
        StatsCalculator.compute(HistoryTotals.EMPTY, sessions.toList(), nowMillis, zone)

    private fun FocusStats.dayMillis(date: String): Long =
        days.first { it.date == LocalDate.parse(date) }.activeMillis

    private fun FocusStats.weekMillis(monday: String): Long =
        weeks.first { it.weekStart == LocalDate.parse(monday) }.activeMillis

    @Test
    fun noHistory_givesSevenDaysAndEightWeeks_newestFirst_allZero() {
        val stats = compute()
        assertEquals(7, stats.days.size)
        assertEquals(LocalDate.parse("2026-11-25"), stats.days.first().date)
        assertEquals(LocalDate.parse("2026-11-19"), stats.days.last().date)
        assertEquals(8, stats.weeks.size)
        assertEquals(LocalDate.parse("2026-11-23"), stats.weeks.first().weekStart)
        assertEquals(LocalDate.parse("2026-10-05"), stats.weeks.last().weekStart)
        assertTrue(stats.days.all { it.activeMillis == 0L })
        assertTrue(stats.weeks.all { it.activeMillis == 0L })
    }

    @Test
    fun sessionInsideOneDay_countsOnThatDayAndWeek() {
        val stats = compute(session("a", at(utc, "2026-11-25T09:00:00"), 90 * min))
        assertEquals(90 * min, stats.dayMillis("2026-11-25"))
        assertEquals(90 * min, stats.weekMillis("2026-11-23"))
        assertEquals(0L, stats.weekMillis("2026-11-16"))
    }

    @Test
    fun sessionAcrossMidnight_isSplitAtLocalMidnight() {
        val stats = compute(session("a", at(utc, "2026-11-24T23:00:00"), 2 * hour))
        assertEquals(hour, stats.dayMillis("2026-11-24"))
        assertEquals(hour, stats.dayMillis("2026-11-25"))
        assertEquals(2 * hour, stats.weekMillis("2026-11-23"))
    }

    @Test
    fun sessionAcrossSundayMidnight_isSplitBetweenTwoWeeks() {
        val stats = compute(session("a", at(utc, "2026-11-22T23:30:00"), hour))
        assertEquals(30 * min, stats.dayMillis("2026-11-22"))
        assertEquals(30 * min, stats.dayMillis("2026-11-23"))
        assertEquals(30 * min, stats.weekMillis("2026-11-16"))
        assertEquals(30 * min, stats.weekMillis("2026-11-23"))
    }

    @Test
    fun failedSessions_neverCountAsFocusTime_evenWithAStrayValue() {
        val stats = compute(
            session("a", at(utc, "2026-11-25T09:00:00"), 0L, SessionOutcome.Failed),
            session("b", at(utc, "2026-11-25T10:00:00"), hour, SessionOutcome.Failed),
        )
        assertTrue(stats.days.all { it.activeMillis == 0L })
    }

    @Test
    fun interruptedSessions_countForTheTimeTheyReallyRan() {
        val stats = compute(session("a", at(utc, "2026-11-25T09:00:00"), 20 * min, SessionOutcome.Interrupted))
        assertEquals(20 * min, stats.dayMillis("2026-11-25"))
    }

    @Test
    fun severalSessionsOnOneDay_add_up() {
        val stats = compute(
            session("a", at(utc, "2026-11-25T08:00:00"), 30 * min),
            session("b", at(utc, "2026-11-25T14:00:00"), 45 * min),
        )
        assertEquals(75 * min, stats.dayMillis("2026-11-25"))
    }

    @Test
    fun timeBeyondTheDayWindow_stillCountsInItsWeek_andOlderThanEightWeeksIsIgnored() {
        val stats = compute(
            session("old-week", at(utc, "2026-11-10T09:00:00"), hour), // outside 7 days, inside 8 weeks
            session("ancient", at(utc, "2026-09-01T09:00:00"), hour), // outside everything
        )
        assertTrue(stats.days.none { it.date == LocalDate.parse("2026-11-10") })
        assertEquals(hour, stats.weekMillis("2026-11-09"))
        assertEquals(hour, stats.weeks.sumOf { it.activeMillis })
    }

    @Test
    fun dstSpringForward_dayIsCountedInRealElapsedTime() {
        // New York, 2026-03-08: 02:00 -> 03:00. 00:30 EST plus three real hours is 04:30 EDT.
        val start = at(newYork, "2026-03-08T00:30:00")
        val stats = compute(session("a", start, 3 * hour), nowMillis = at(newYork, "2026-03-09T12:00:00"), zone = newYork)
        assertEquals(3 * hour, stats.dayMillis("2026-03-08"))
    }

    @Test
    fun dstFallBack_longDayIsSplitAtTheRealMidnight() {
        // New York, 2026-11-01 has 25 hours. 22:00 EDT on Oct 31 plus 24 real hours is 21:00 EST on Nov 1.
        val start = at(newYork, "2026-10-31T22:00:00")
        val stats = compute(session("a", start, 24 * hour), nowMillis = at(newYork, "2026-11-02T12:00:00"), zone = newYork)
        assertEquals(2 * hour, stats.dayMillis("2026-10-31"))
        assertEquals(22 * hour, stats.dayMillis("2026-11-01"))
    }

    @Test
    fun windowStart_isMondayOfTheOldestWeek_andCoversEverythingCompute_needs() {
        assertEquals(at(utc, "2026-10-05T00:00:00"), StatsCalculator.windowStartMillis(now, utc))
        // Sunday: still the same week as the Monday before.
        val sunday = at(utc, "2026-11-29T23:59:00")
        assertEquals(at(utc, "2026-10-05T00:00:00"), StatsCalculator.windowStartMillis(sunday, utc))
        // Monday 00:00 starts a new week, so the window moves on by one week.
        val nextMonday = at(utc, "2026-11-30T00:00:00")
        assertEquals(at(utc, "2026-10-12T00:00:00"), StatsCalculator.windowStartMillis(nextMonday, utc))
    }

    @Test
    fun totals_arePassedThroughUntouched() {
        val totals = HistoryTotals(completed = 3, interrupted = 1, failed = 2, activeMillis = 5 * hour)
        val stats = StatsCalculator.compute(totals, emptyList(), now, utc)
        assertEquals(totals, stats.totals)
        assertEquals(4, totals.started)
        assertFalse(totals.isEmpty)
        assertTrue(HistoryTotals.EMPTY.isEmpty)
    }
}
