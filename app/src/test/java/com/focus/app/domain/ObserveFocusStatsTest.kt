package com.focus.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live statistics flow: window query, updates, day change. Run in Phase 8. */
class ObserveFocusStatsTest {
    private val utc = ZoneOffset.UTC
    private val hour = 3_600_000L

    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(utc).toInstant().toEpochMilli()

    private fun finished(id: String, start: Long, active: Long) = FinishedSession(
        id, FocusMode.FullFocus, 0, 60, start, start + active, active, SessionOutcome.Completed, null,
    )

    /** Only the two statistics queries do anything; the rest of the port is unused here. */
    private class StatsHistory(
        private val totals: Flow<HistoryTotals>,
        private val recent: Flow<List<FinishedSession>>,
    ) : SessionHistoryRepository {
        val requestedSince = mutableListOf<Long>()

        override suspend fun add(entry: FinishedSession) = Unit

        override fun observeRecent(limit: Int): Flow<List<FinishedSession>> = flowOf(emptyList())

        override fun observeTotals(): Flow<HistoryTotals> = totals

        override fun observeEndedSince(sinceMillis: Long): Flow<List<FinishedSession>> {
            requestedSince += sinceMillis
            return recent
        }

        override suspend fun clear() = Unit
    }

    @Test
    fun firstValue_usesTheStatsWindowAndTheStoredRows() = runBlocking {
        val now = at("2026-11-25T12:00:00")
        val history = StatsHistory(
            totals = flowOf(HistoryTotals(1, 0, 0, hour)),
            recent = flowOf(listOf(finished("a", at("2026-11-25T09:00:00"), hour))),
        )
        val stats = withTimeout(5_000) {
            observeFocusStats(history, now = { now }, zone = { utc }, dayChanges = flow { }).first()
        }
        assertEquals(hour, stats.days.first().activeMillis)
        assertEquals(1, stats.totals.completed)
        assertEquals(StatsCalculator.windowStartMillis(now, utc), history.requestedSince.first())
    }

    @Test
    fun changedHistory_producesNewStats() = runBlocking {
        val now = at("2026-11-25T12:00:00")
        val old = HistoryTotals(1, 0, 0, hour)
        val updated = HistoryTotals(2, 0, 0, 2 * hour)
        val history = StatsHistory(
            totals = flow {
                emit(old)
                emit(updated)
            },
            recent = flowOf(emptyList()),
        )
        val stats = withTimeout(5_000) {
            observeFocusStats(history, now = { now }, zone = { utc }, dayChanges = flow { })
                .first { it.totals.completed == 2 }
        }
        assertEquals(updated, stats.totals)
    }

    @Test
    fun afterMidnight_todayMovesOn() = runBlocking {
        var nowMillis = at("2026-11-25T23:59:00")
        val history = StatsHistory(totals = flowOf(HistoryTotals.EMPTY), recent = flowOf(emptyList()))
        val stats = withTimeout(5_000) {
            observeFocusStats(
                history,
                now = { nowMillis },
                zone = { utc },
                // The clock passes midnight, then the day-change signal arrives.
                dayChanges = flow {
                    nowMillis = at("2026-11-26T00:00:05")
                    emit(Unit)
                },
            ).first { it.days.first().date == LocalDate.parse("2026-11-26") }
        }
        assertTrue(stats.days.first().date == LocalDate.parse("2026-11-26"))
    }
}
