package com.focus.app.domain

import java.time.DayOfWeek

/** Why a draft cannot be started yet. Each maps to one user-facing message. */
enum class DraftProblem {
    ModeMissing,
    NoAppsSelected,
    DurationMissing,
    DurationTooShort,
    DurationTooLong,
    NoScheduleDays,
}

/** A recurring window for Scheduled Focus. */
data class ScheduleWindow(
    val days: Set<DayOfWeek>,
    val startMinuteOfDay: Int,
    val durationMinutes: Int,
) {
    val endMinuteOfDay: Int get() = (startMinuteOfDay + durationMinutes) % MINUTES_PER_DAY

    /** True when the window ends on the day after it starts (including exactly at midnight). */
    val endsNextDay: Boolean get() = startMinuteOfDay + durationMinutes >= MINUTES_PER_DAY

    companion object {
        const val MINUTES_PER_DAY: Int = 24 * 60
    }
}

/**
 * What the person has configured so far in the "new session" flow. This is a draft
 * only: nothing here starts or restricts anything. The running session lives in the session engine.
 */
data class SessionDraft(
    val mode: FocusMode? = null,
    val selectedPackages: Set<String> = emptySet(),
    val durationMinutes: Int? = null,
    val scheduleDays: Set<DayOfWeek> = emptySet(),
    val scheduleStartMinuteOfDay: Int = DEFAULT_SCHEDULE_START,
) {
    fun problems(): List<DraftProblem> = buildList {
        if (mode == null) add(DraftProblem.ModeMissing)
        if (mode == FocusMode.SelectedApps && selectedPackages.isEmpty()) {
            add(DraftProblem.NoAppsSelected)
        }
        val minutes = durationMinutes
        if (minutes == null) {
            add(DraftProblem.DurationMissing)
        } else {
            when (DurationPolicy.check(minutes)) {
                DurationCheck.TooShort -> add(DraftProblem.DurationTooShort)
                DurationCheck.TooLong -> add(DraftProblem.DurationTooLong)
                DurationCheck.Valid -> Unit
            }
        }
        if (mode == FocusMode.ScheduledFocus && scheduleDays.isEmpty()) {
            add(DraftProblem.NoScheduleDays)
        }
    }

    val isReady: Boolean get() = problems().isEmpty()

    /** The recurring window, or null when this is not a valid Scheduled Focus draft. */
    fun scheduleWindow(): ScheduleWindow? {
        val minutes = durationMinutes ?: return null
        if (mode != FocusMode.ScheduledFocus || scheduleDays.isEmpty()) return null
        if (DurationPolicy.check(minutes) != DurationCheck.Valid) return null
        return ScheduleWindow(scheduleDays, scheduleStartMinuteOfDay, minutes)
    }

    /**
     * The one-off session this draft describes, or null if it is not a startable,
     * non-scheduled draft. Scheduled Focus goes through [scheduleWindow] instead.
     */
    fun toPlan(): SessionPlan? {
        val chosen = mode ?: return null
        val minutes = durationMinutes ?: return null
        if (chosen == FocusMode.ScheduledFocus || !isReady) return null
        return SessionPlan(
            mode = chosen,
            packages = if (chosen == FocusMode.SelectedApps) selectedPackages else emptySet(),
            durationMinutes = minutes,
        )
    }

    companion object {
        const val DEFAULT_SCHEDULE_START: Int = 9 * 60
    }
}
