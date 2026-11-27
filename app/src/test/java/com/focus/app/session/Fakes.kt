package com.focus.app.session

import com.focus.app.domain.Capability
import com.focus.app.domain.EndReason
import com.focus.app.domain.EnforcementResult
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.FocusMode
import com.focus.app.domain.HistoryTotals
import com.focus.app.domain.RestrictionEnforcer
import com.focus.app.domain.ScheduleEntry
import com.focus.app.domain.ScheduleRepository
import com.focus.app.domain.SessionHistoryRepository
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.SessionState
import com.focus.app.domain.SessionStore
import com.focus.app.domain.TimeSnapshot
import com.focus.app.domain.TimeSource
import com.focus.app.domain.WakeScheduler
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** In-memory fakes for the engine tests. They record calls so tests can check what really happened. */

class FakeSessionStore(var stored: SessionState? = null) : SessionStore {
    var failLoad = false
    var failSave = false

    override suspend fun load(): SessionState? {
        if (failLoad) throw IOException("load")
        return stored
    }

    override suspend fun save(state: SessionState) {
        if (failSave) throw IOException("save")
        stored = state
    }
}

class FakeHistory : SessionHistoryRepository {
    val entries = linkedMapOf<String, FinishedSession>()

    override suspend fun add(entry: FinishedSession) {
        entries[entry.id] = entry
    }

    override fun observeRecent(limit: Int): Flow<List<FinishedSession>> = emptyFlow()

    override fun observeTotals(): Flow<HistoryTotals> = emptyFlow()

    override fun observeEndedSince(sinceMillis: Long): Flow<List<FinishedSession>> = emptyFlow()

    override suspend fun clear() = entries.clear()
}

class FakeSchedules(initial: List<ScheduleEntry> = emptyList()) : ScheduleRepository {
    private val items = MutableStateFlow(initial)
    val markedWindows = mutableListOf<Pair<String, Long>>()

    val current: List<ScheduleEntry> get() = items.value

    override fun observeAll(): Flow<List<ScheduleEntry>> = items.map { it }

    override suspend fun getAll(): List<ScheduleEntry> = items.value

    override suspend fun upsert(entry: ScheduleEntry) {
        items.value = items.value.filterNot { it.id == entry.id } + entry
    }

    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun markWindowStarted(id: String, windowStartMillis: Long) {
        markedWindows += id to windowStartMillis
        items.value = items.value.map { if (it.id == id) it.copy(lastStartedWindowStartMillis = windowStartMillis) else it }
    }
}

class FakeEnforcer : RestrictionEnforcer {
    var capability: Capability = Capability.Available
    var applyResult: EnforcementResult = EnforcementResult.Applied
    var applyThrows: Exception? = null
    val applied = mutableListOf<SessionPlan>()
    var releaseCount = 0
    override val losses: Flow<EndReason> = emptyFlow()

    override suspend fun capability(mode: FocusMode): Capability = capability

    override suspend fun apply(plan: SessionPlan): EnforcementResult {
        applyThrows?.let { throw it }
        applied += plan
        return applyResult
    }

    override suspend fun release() {
        releaseCount++
    }
}

class FakeTime(var wall: Long, var elapsed: Long = 100_000L, var boot: Int? = 1) : TimeSource {
    override fun now() = TimeSnapshot(wall, elapsed, boot)

    /** Time passes on both clocks. */
    fun advance(millis: Long) {
        wall += millis
        elapsed += millis
    }
}

class FakeWaker : WakeScheduler {
    var lastDelayMillis: Long? = null
    var cancelled = false

    override fun scheduleReconcile(delayMillis: Long) {
        lastDelayMillis = delayMillis
        cancelled = false
    }

    override fun cancelReconcile() {
        lastDelayMillis = null
        cancelled = true
    }
}
