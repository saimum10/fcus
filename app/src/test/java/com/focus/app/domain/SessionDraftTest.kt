package com.focus.app.domain

import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draft validation and schedule-window math. Written in Phase 2, run in Phase 8. */
class SessionDraftTest {

    @Test
    fun emptyDraft_reportsModeAndDuration() {
        assertEquals(
            listOf(DraftProblem.ModeMissing, DraftProblem.DurationMissing),
            SessionDraft().problems(),
        )
    }

    @Test
    fun selectedApps_needsAtLeastOneApp() {
        val draft = SessionDraft(mode = FocusMode.SelectedApps, durationMinutes = 30)
        assertEquals(listOf(DraftProblem.NoAppsSelected), draft.problems())
        assertTrue(draft.copy(selectedPackages = setOf("com.example.app")).isReady)
    }

    @Test
    fun fullFocus_doesNotNeedApps() {
        assertTrue(SessionDraft(mode = FocusMode.FullFocus, durationMinutes = 60).isReady)
    }

    @Test
    fun durationLimits_areReported() {
        val short = SessionDraft(mode = FocusMode.CallOnly, durationMinutes = 1)
        val long = SessionDraft(mode = FocusMode.CallOnly, durationMinutes = 1441)
        assertEquals(listOf(DraftProblem.DurationTooShort), short.problems())
        assertEquals(listOf(DraftProblem.DurationTooLong), long.problems())
    }

    @Test
    fun scheduledFocus_needsDays() {
        val draft = SessionDraft(mode = FocusMode.ScheduledFocus, durationMinutes = 60)
        assertEquals(listOf(DraftProblem.NoScheduleDays), draft.problems())
        assertNull(draft.scheduleWindow())
        val ready = draft.copy(scheduleDays = setOf(DayOfWeek.MONDAY))
        assertTrue(ready.isReady)
        assertEquals(
            ScheduleWindow(setOf(DayOfWeek.MONDAY), SessionDraft.DEFAULT_SCHEDULE_START, 60),
            ready.scheduleWindow(),
        )
    }

    @Test
    fun scheduleWindow_isNullOutsideScheduledMode() {
        val draft = SessionDraft(
            mode = FocusMode.FullFocus,
            durationMinutes = 60,
            scheduleDays = setOf(DayOfWeek.FRIDAY),
        )
        assertNull(draft.scheduleWindow())
    }

    @Test
    fun scheduleWindow_endMath() {
        val sameDay = ScheduleWindow(setOf(DayOfWeek.MONDAY), 9 * 60, 60)
        assertEquals(10 * 60, sameDay.endMinuteOfDay)
        assertFalse(sameDay.endsNextDay)

        val overnight = ScheduleWindow(setOf(DayOfWeek.MONDAY), 23 * 60, 120)
        assertEquals(60, overnight.endMinuteOfDay)
        assertTrue(overnight.endsNextDay)

        val toMidnight = ScheduleWindow(setOf(DayOfWeek.MONDAY), 22 * 60, 120)
        assertEquals(0, toMidnight.endMinuteOfDay)
        assertTrue(toMidnight.endsNextDay)
    }
}
