package com.focus.app.domain

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedule windows, DST, midnight crossing, conflicts. 2026-11-23 is a Monday. Run in Phase 8. */
class ScheduleMathTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun window(vararg days: DayOfWeek, start: Int, minutes: Int) =
        ScheduleWindow(days.toSet(), start, minutes)

    @Test
    fun activeAt_insideAndOutsideAWindow() {
        val w = window(DayOfWeek.MONDAY, start = 9 * 60, minutes = 60)
        val inside = ScheduleMath.activeAt(w, utc, at(utc, "2026-11-23T09:30:00"))
        assertEquals(at(utc, "2026-11-23T09:00:00"), inside?.startMillis)
        assertEquals(at(utc, "2026-11-23T10:00:00"), inside?.endMillis)
        assertNull(ScheduleMath.activeAt(w, utc, at(utc, "2026-11-23T10:00:00"))) // end is exclusive
        assertNull(ScheduleMath.activeAt(w, utc, at(utc, "2026-11-24T09:30:00"))) // Tuesday
    }

    @Test
    fun activeAt_windowCrossingMidnightBelongsToTheDayItStartedOn() {
        val w = window(DayOfWeek.MONDAY, start = 22 * 60, minutes = 8 * 60)
        val instance = ScheduleMath.activeAt(w, utc, at(utc, "2026-11-24T02:00:00")) // Tuesday 02:00
        assertNotNull(instance)
        assertEquals(at(utc, "2026-11-23T22:00:00"), instance!!.startMillis)
        assertEquals(at(utc, "2026-11-24T06:00:00"), instance.endMillis)
    }

    @Test
    fun nextStart_isStrictlyAfter_andWrapsToNextWeek() {
        val w = window(DayOfWeek.MONDAY, start = 9 * 60, minutes = 60)
        assertEquals(at(utc, "2026-11-30T09:00:00"), ScheduleMath.nextStart(w, utc, at(utc, "2026-11-23T09:00:00")))
        assertEquals(at(utc, "2026-11-23T09:00:00"), ScheduleMath.nextStart(w, utc, at(utc, "2026-11-22T12:00:00")))
        assertNull(ScheduleMath.nextStart(window(start = 9 * 60, minutes = 60), utc, 0L)) // no days
    }

    @Test
    fun dst_springForward_windowKeepsItsWallClockStartAndAbsoluteLength() {
        // US clocks jump 02:00 -> 03:00 on 2027-03-14 (a Sunday). Window 01:00, 2 h long.
        val w = window(DayOfWeek.SUNDAY, start = 60, minutes = 120)
        val instance = ScheduleMath.activeAt(w, newYork, at(newYork, "2027-03-14T01:30:00"))
        assertNotNull(instance)
        assertEquals(at(newYork, "2027-03-14T01:00:00"), instance!!.startMillis)
        assertEquals(120 * 60_000L, instance.endMillis - instance.startMillis)
    }

    @Test
    fun dst_startTimeInTheGapMovesForward() {
        // 02:30 does not exist on 2027-03-14 in New York; it resolves to 03:30.
        val w = window(DayOfWeek.SUNDAY, start = 2 * 60 + 30, minutes = 30)
        val next = ScheduleMath.nextStart(w, newYork, at(newYork, "2027-03-13T12:00:00"))
        assertEquals(at(newYork, "2027-03-14T03:30:00"), next)
    }

    @Test
    fun dueInstance_skipsHandledWindows_andDisabledEntries() {
        val w = window(DayOfWeek.MONDAY, start = 9 * 60, minutes = 60)
        val now = at(utc, "2026-11-23T09:10:00")
        val windowStart = at(utc, "2026-11-23T09:00:00")
        assertEquals(windowStart, ScheduleMath.dueInstance(ScheduleEntry("a", w), utc, now)?.startMillis)
        assertNull(ScheduleMath.dueInstance(ScheduleEntry("a", w, lastStartedWindowStartMillis = windowStart), utc, now))
        assertNull(ScheduleMath.dueInstance(ScheduleEntry("a", w, enabled = false), utc, now))
        assertNull(ScheduleMath.dueInstance(ScheduleEntry("a", w), utc, at(utc, "2026-11-23T11:00:00")))
    }

    @Test
    fun overlaps_sameDayAndTouchingWindows() {
        val a = window(DayOfWeek.MONDAY, start = 9 * 60, minutes = 60) // 09:00-10:00
        assertTrue(ScheduleMath.overlaps(a, window(DayOfWeek.MONDAY, start = 9 * 60 + 30, minutes = 60)))
        assertFalse(ScheduleMath.overlaps(a, window(DayOfWeek.MONDAY, start = 10 * 60, minutes = 60))) // touching
        assertFalse(ScheduleMath.overlaps(a, window(DayOfWeek.TUESDAY, start = 9 * 60, minutes = 60)))
    }

    @Test
    fun overlaps_acrossMidnight_andAcrossTheEndOfTheWeek() {
        val sundayNight = window(DayOfWeek.SUNDAY, start = 23 * 60, minutes = 120) // Sun 23:00 - Mon 01:00
        assertTrue(ScheduleMath.overlaps(sundayNight, window(DayOfWeek.MONDAY, start = 30, minutes = 60)))
        assertFalse(ScheduleMath.overlaps(sundayNight, window(DayOfWeek.MONDAY, start = 60, minutes = 60)))
        assertTrue(ScheduleMath.overlaps(sundayNight, window(DayOfWeek.SUNDAY, start = 22 * 60, minutes = 120)))
    }

    @Test
    fun firstConflict_findsTheEarliestEnabledWindowInRange() {
        val entries = listOf(
            ScheduleEntry("off", window(DayOfWeek.MONDAY, start = 9 * 60, minutes = 60), enabled = false),
            ScheduleEntry("on", window(DayOfWeek.MONDAY, start = 11 * 60, minutes = 60)),
        )
        val from = at(utc, "2026-11-23T08:00:00")
        assertNull(ScheduleMath.firstConflict(from, at(utc, "2026-11-23T10:59:00"), entries, utc))
        assertEquals(
            at(utc, "2026-11-23T11:00:00"),
            ScheduleMath.firstConflict(from, at(utc, "2026-11-23T11:30:00"), entries, utc)?.startMillis,
        )
    }
}
