package com.focus.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/**
 * Counts over everything stored. [activeMillis] is the time restrictions were really in
 * force; sessions that never started ([failed]) contribute none.
 */
data class HistoryTotals(
    val completed: Int,
    val interrupted: Int,
    val failed: Int,
    val activeMillis: Long,
) {
    /** Sessions that actually ran (completed or ended early). */
    val started: Int get() = completed + interrupted

    val isEmpty: Boolean get() = completed == 0 && interrupted == 0 && failed == 0

    companion object {
        val EMPTY = HistoryTotals(0, 0, 0, 0L)
    }
}

/** Restricted time on one local calendar day. */
data class DayStat(val date: LocalDate, val activeMillis: Long)

/** Restricted time in the Monday-to-Sunday week that starts on [weekStart]. */
data class WeekStat(val weekStart: LocalDate, val activeMillis: Long)

/** Everything the statistics screen shows. [days] and [weeks] are newest first. */
data class FocusStats(
    val totals: HistoryTotals,
    val days: List<DayStat>,
    val weeks: List<WeekStat>,
)

/**
 * Turns stored history into statistics. Pure: no clock, no storage; the caller passes
 * "now" and the time zone, so DST and midnight are testable.
 *
 * Time is attributed to the days it was really spent: a session that runs across midnight
 * is split at local midnight. A session counts from its start for [FinishedSession.activeMillis]
 * (the time restrictions were in force), not until the moment the app noticed it had ended.
 * Weeks start on Monday, like the schedule screen. Nothing here needs any data that
 * history does not already hold.
 */
object StatsCalculator {
    const val DAYS = 7
    const val WEEKS = 8

    /** The earliest instant [compute] can need: the start of the oldest of the [WEEKS] weeks. */
    fun windowStartMillis(nowMillis: Long, zone: ZoneId): Long =
        weekStart(today(nowMillis, zone)).minusWeeks((WEEKS - 1).toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * [recent] must hold every session that ended at or after [windowStartMillis]
     * (more is fine; time outside the window is ignored).
     */
    fun compute(
        totals: HistoryTotals,
        recent: List<FinishedSession>,
        nowMillis: Long,
        zone: ZoneId,
    ): FocusStats {
        val perDay = HashMap<LocalDate, Long>()
        for (session in recent) {
            if (session.outcome == SessionOutcome.Failed || session.activeMillis <= 0L) continue
            val start = session.startedAtWallMillis
            spread(start, start + session.activeMillis, zone, perDay)
        }

        val today = today(nowMillis, zone)
        val days = (0 until DAYS).map { back ->
            val date = today.minusDays(back.toLong())
            DayStat(date, perDay[date] ?: 0L)
        }
        val thisWeek = weekStart(today)
        val weeks = (0 until WEEKS).map { back ->
            val start = thisWeek.minusWeeks(back.toLong())
            var sum = 0L
            for (offset in 0 until 7) sum += perDay[start.plusDays(offset.toLong())] ?: 0L
            WeekStat(start, sum)
        }
        return FocusStats(totals, days, weeks)
    }

    /** Splits [startMillis, endMillis) at local midnights and adds each slice to its day. */
    private fun spread(startMillis: Long, endMillis: Long, zone: ZoneId, into: MutableMap<LocalDate, Long>) {
        var cursor = startMillis
        while (cursor < endMillis) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            if (nextMidnight <= cursor) {
                // Cannot happen with a sane zone; never loop forever on a broken one.
                into.merge(date, endMillis - cursor, Long::plus)
                return
            }
            val sliceEnd = minOf(endMillis, nextMidnight)
            into.merge(date, sliceEnd - cursor, Long::plus)
            cursor = sliceEnd
        }
    }

    private fun today(nowMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

    private fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

/** Emits once at every local midnight (and never earlier than a second from now). */
fun midnightTicks(now: () -> Long, zone: () -> ZoneId): Flow<Unit> = flow {
    while (true) {
        val nowMillis = now()
        val z = zone()
        val next = Instant.ofEpochMilli(nowMillis).atZone(z).toLocalDate().plusDays(1)
            .atStartOfDay(z).toInstant().toEpochMilli()
        delay((next - nowMillis).coerceAtLeast(MIN_TICK_DELAY_MILLIS))
        emit(Unit)
    }
}

private const val MIN_TICK_DELAY_MILLIS = 1_000L

/**
 * Live statistics. Re-queries only the window that is needed, and again after each
 * local midnight so "today" and the week rows move on while the screen stays open.
 * Collect it only while the screen is visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeFocusStats(
    history: SessionHistoryRepository,
    now: () -> Long = System::currentTimeMillis,
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    dayChanges: Flow<Unit> = midnightTicks(now, zone),
): Flow<FocusStats> = dayChanges.onStart { emit(Unit) }.flatMapLatest {
    val nowMillis = now()
    val z = zone()
    combine(
        history.observeTotals(),
        history.observeEndedSince(StatsCalculator.windowStartMillis(nowMillis, z)),
    ) { totals, recent -> StatsCalculator.compute(totals, recent, nowMillis, z) }
}
