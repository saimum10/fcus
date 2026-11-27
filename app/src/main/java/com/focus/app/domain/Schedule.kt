package com.focus.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A saved recurring window. [lastStartedWindowStartMillis] is the start of the latest
 * window already handled, so one window never starts twice (for example after the
 * session in it was interrupted while the window is still open).
 */
data class ScheduleEntry(
    val id: String,
    val window: ScheduleWindow,
    val enabled: Boolean = true,
    val lastStartedWindowStartMillis: Long = 0L,
)

/** One concrete occurrence of a window on the timeline. */
data class WindowInstance(val startMillis: Long, val endMillis: Long)

/** Pure schedule math. Zone-aware (DST-safe) except [overlaps], which works on a week of minutes. */
object ScheduleMath {
    private const val MINUTES_PER_WEEK: Int = 7 * 24 * 60
    private const val MILLIS_PER_MINUTE: Long = 60_000L
    private const val MILLIS_PER_DAY: Long = 24 * 60 * MILLIS_PER_MINUTE

    /** Occurrences of [window] that overlap `[fromMillis, toMillis)`, in time order. */
    fun instances(window: ScheduleWindow, zone: ZoneId, fromMillis: Long, toMillis: Long): List<WindowInstance> {
        if (window.days.isEmpty() || window.durationMinutes <= 0) return emptyList()
        // Windows last at most 24 h, so one that is still open can have started up to a day
        // earlier; two days back and one forward is a safe margin for zone offsets.
        var date = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate().minusDays(2)
        val lastDate = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate().plusDays(1)
        val time = LocalTime.of(window.startMinuteOfDay / 60, window.startMinuteOfDay % 60)
        val result = ArrayList<WindowInstance>()
        while (!date.isAfter(lastDate)) {
            if (date.dayOfWeek in window.days) {
                // A start time that does not exist (DST gap) moves forward to the next valid time.
                val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                val end = start + window.durationMinutes * MILLIS_PER_MINUTE
                if (start < toMillis && end > fromMillis) result += WindowInstance(start, end)
            }
            date = date.plusDays(1)
        }
        return result
    }

    /** The occurrence that contains [nowMillis], if any. */
    fun activeAt(window: ScheduleWindow, zone: ZoneId, nowMillis: Long): WindowInstance? =
        instances(window, zone, nowMillis, nowMillis + 1)
            .firstOrNull { it.startMillis <= nowMillis && nowMillis < it.endMillis }

    /** Start of the first occurrence strictly after [afterMillis], or null for an empty window. */
    fun nextStart(window: ScheduleWindow, zone: ZoneId, afterMillis: Long): Long? =
        instances(window, zone, afterMillis, afterMillis + 8 * MILLIS_PER_DAY)
            .map { it.startMillis }
            .filter { it > afterMillis }
            .minOrNull()

    /** The currently open occurrence if it has not been handled yet. */
    fun dueInstance(entry: ScheduleEntry, zone: ZoneId, nowMillis: Long): WindowInstance? {
        if (!entry.enabled) return null
        val active = activeAt(entry.window, zone, nowMillis) ?: return null
        return active.startMillis.takeIf { it > entry.lastStartedWindowStartMillis }?.let { active }
    }

    /**
     * True if two recurring windows can ever overlap. Compared on a circular week of
     * minutes, so windows that cross midnight or the end of the week are handled and
     * the answer does not depend on the time zone.
     */
    fun overlaps(a: ScheduleWindow, b: ScheduleWindow): Boolean {
        for (dayA in a.days) {
            val startA = minuteOfWeek(dayA, a.startMinuteOfDay)
            val endA = startA + a.durationMinutes
            for (dayB in b.days) {
                val startB = minuteOfWeek(dayB, b.startMinuteOfDay)
                val endB = startB + b.durationMinutes
                for (shift in -1..1) {
                    val offset = shift * MINUTES_PER_WEEK
                    if (startA < endB + offset && startB + offset < endA) return true
                }
            }
        }
        return false
    }

    /** First occurrence of an enabled schedule that overlaps `[startMillis, endMillis)`. */
    fun firstConflict(
        startMillis: Long,
        endMillis: Long,
        entries: List<ScheduleEntry>,
        zone: ZoneId,
    ): WindowInstance? = entries
        .filter { it.enabled }
        .flatMap { instances(it.window, zone, startMillis, endMillis) }
        .minByOrNull { it.startMillis }

    private fun minuteOfWeek(day: DayOfWeek, minuteOfDay: Int): Int = (day.value - 1) * 24 * 60 + minuteOfDay
}
