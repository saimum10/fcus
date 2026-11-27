package com.focus.app.data.room

import com.focus.app.domain.EndReason
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.FocusMode
import com.focus.app.domain.ScheduleEntry
import com.focus.app.domain.ScheduleWindow
import com.focus.app.domain.SessionOutcome
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Entity mapping that does not need a database. Run in Phase 8. */
class MappersTest {
    @Test
    fun dayMask_roundTripsEverySubsetBoundary() {
        assertEquals(0, emptySet<DayOfWeek>().toMask())
        assertEquals(0b0000001, setOf(DayOfWeek.MONDAY).toMask())
        assertEquals(0b1000000, setOf(DayOfWeek.SUNDAY).toMask())
        assertEquals(0b1111111, DayOfWeek.entries.toSet().toMask())
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
        assertEquals(days, days.toMask().toDays())
    }

    @Test
    fun scheduleEntry_roundTrips() {
        val entry = ScheduleEntry(
            id = "x",
            window = ScheduleWindow(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 22 * 60, 480),
            enabled = false,
            lastStartedWindowStartMillis = 123L,
        )
        assertEquals(entry, entry.toEntity().toDomain())
    }

    @Test
    fun historyEntry_roundTrips_andUnknownRowsAreSkipped() {
        val finished = FinishedSession("h", FocusMode.CallOnly, 0, 30, 1L, 2L, 1L, SessionOutcome.Interrupted, EndReason.EnforcementLost)
        assertEquals(finished, finished.toEntity().toDomainOrNull())
        assertNull(finished.toEntity().copy(mode = "RemovedMode").toDomainOrNull())
        assertNull(finished.toEntity().copy(reason = null).toDomainOrNull()) // violates the invariant
    }

    @Test
    fun outcomeTotals_sumPerOutcome_andIgnoreUnknownOutcomes() {
        val rows = listOf(
            OutcomeTotalRow("Completed", 3, 7_200_000L),
            OutcomeTotalRow("Interrupted", 2, 1_800_000L),
            OutcomeTotalRow("Failed", 1, 0L),
            OutcomeTotalRow("FromANewerVersion", 5, 99_000_000L),
        )
        val totals = rows.toTotals()
        assertEquals(3, totals.completed)
        assertEquals(2, totals.interrupted)
        assertEquals(1, totals.failed)
        assertEquals(9_000_000L, totals.activeMillis)
    }

    @Test
    fun outcomeTotals_ofNothing_areEmpty() {
        assertEquals(com.focus.app.domain.HistoryTotals.EMPTY, emptyList<OutcomeTotalRow>().toTotals())
    }
}
