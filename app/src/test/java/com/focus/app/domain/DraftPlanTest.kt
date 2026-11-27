package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Conversion from the setup draft to a startable plan. Run in Phase 8. */
class DraftPlanTest {
    @Test
    fun selectedApps_carriesPackagesAndDuration() {
        val draft = SessionDraft(mode = FocusMode.SelectedApps, durationMinutes = 45, selectedPackages = setOf("a", "b"))
        assertEquals(SessionPlan(FocusMode.SelectedApps, setOf("a", "b"), 45), draft.toPlan())
    }

    @Test
    fun otherModes_ignoreAnyRememberedAppSelection() {
        val draft = SessionDraft(mode = FocusMode.FullFocus, durationMinutes = 60, selectedPackages = setOf("a"))
        assertEquals(SessionPlan(FocusMode.FullFocus, emptySet(), 60), draft.toPlan())
    }

    @Test
    fun invalidOrScheduledDrafts_haveNoPlan() {
        assertNull(SessionDraft().toPlan())
        assertNull(SessionDraft(mode = FocusMode.SelectedApps, durationMinutes = 30).toPlan()) // no apps
        assertNull(SessionDraft(mode = FocusMode.FullFocus, durationMinutes = 1).toPlan()) // too short
        assertNull(SessionDraft(mode = FocusMode.ScheduledFocus, durationMinutes = 60).toPlan())
    }
}
