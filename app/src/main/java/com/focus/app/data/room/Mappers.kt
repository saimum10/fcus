package com.focus.app.data.room

import com.focus.app.domain.EndReason
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.FocusMode
import com.focus.app.domain.HistoryTotals
import com.focus.app.domain.ScheduleEntry
import com.focus.app.domain.ScheduleWindow
import com.focus.app.domain.SessionOutcome
import java.time.DayOfWeek

/** Bit 0 = Monday ... bit 6 = Sunday. */
internal fun Set<DayOfWeek>.toMask(): Int = fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

internal fun Int.toDays(): Set<DayOfWeek> =
    DayOfWeek.entries.filter { this and (1 shl (it.value - 1)) != 0 }.toSet()

internal fun FinishedSession.toEntity() = SessionHistoryEntity(
    id = id,
    mode = mode.name,
    appCount = appCount,
    plannedMinutes = plannedMinutes,
    startedAtMillis = startedAtWallMillis,
    endedAtMillis = endedAtWallMillis,
    activeMillis = activeMillis,
    outcome = outcome.name,
    reason = reason?.name,
)

/** Null if a stored row no longer matches the app's enums or invariants; such rows are skipped. */
internal fun SessionHistoryEntity.toDomainOrNull(): FinishedSession? = try {
    FinishedSession(
        id = id,
        mode = FocusMode.valueOf(mode),
        appCount = appCount,
        plannedMinutes = plannedMinutes,
        startedAtWallMillis = startedAtMillis,
        endedAtWallMillis = endedAtMillis,
        activeMillis = activeMillis,
        outcome = SessionOutcome.valueOf(outcome),
        reason = reason?.let { EndReason.valueOf(it) },
    )
} catch (_: IllegalArgumentException) {
    null
}

/** Rows with an outcome this version does not know are ignored, like unreadable history rows. */
internal fun List<OutcomeTotalRow>.toTotals(): HistoryTotals {
    var completed = 0
    var interrupted = 0
    var failed = 0
    var active = 0L
    for (row in this) {
        when (row.outcome) {
            SessionOutcome.Completed.name -> completed += row.sessions
            SessionOutcome.Interrupted.name -> interrupted += row.sessions
            SessionOutcome.Failed.name -> failed += row.sessions
            else -> continue
        }
        active += row.activeMillis
    }
    return HistoryTotals(completed, interrupted, failed, active)
}

internal fun ScheduleEntry.toEntity() = ScheduleEntity(
    id = id,
    daysMask = window.days.toMask(),
    startMinuteOfDay = window.startMinuteOfDay,
    durationMinutes = window.durationMinutes,
    enabled = enabled,
    lastStartedWindowStartMillis = lastStartedWindowStartMillis,
)

internal fun ScheduleEntity.toDomain() = ScheduleEntry(
    id = id,
    window = ScheduleWindow(daysMask.toDays(), startMinuteOfDay, durationMinutes),
    enabled = enabled,
    lastStartedWindowStartMillis = lastStartedWindowStartMillis,
)
