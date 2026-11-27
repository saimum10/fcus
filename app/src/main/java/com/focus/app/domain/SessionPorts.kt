package com.focus.app.domain

import kotlinx.coroutines.flow.Flow

/** Persists the session state so it survives process death and restarts. */
interface SessionStore {
    /** The stored state, or null if nothing valid is stored. Throws IOException if unreadable. */
    suspend fun load(): SessionState?

    suspend fun save(state: SessionState)
}

interface SessionHistoryRepository {
    /** Idempotent: adding the same session id again replaces the entry. */
    suspend fun add(entry: FinishedSession)

    fun observeRecent(limit: Int): Flow<List<FinishedSession>>

    /** Counts and restricted time of everything stored (not only the newest entries). */
    fun observeTotals(): Flow<HistoryTotals>

    /** Sessions that ended at or after [sinceMillis], newest first. For statistics. */
    fun observeEndedSince(sinceMillis: Long): Flow<List<FinishedSession>>

    suspend fun clear()
}

interface ScheduleRepository {
    fun observeAll(): Flow<List<ScheduleEntry>>

    suspend fun getAll(): List<ScheduleEntry>

    suspend fun upsert(entry: ScheduleEntry)

    suspend fun delete(id: String)

    suspend fun markWindowStarted(id: String, windowStartMillis: Long)
}

interface TimeSource {
    fun now(): TimeSnapshot
}

/** Asks the system to call [SessionController.reconcile] at about the given time, even if the app is not running. */
interface WakeScheduler {
    /** Replaces any earlier request. The system may deliver it later than asked. */
    fun scheduleReconcile(delayMillis: Long)

    fun cancelReconcile()
}

/** Implemented by the Application so background workers can reach the engine. */
interface SessionControllerHolder {
    val sessionController: SessionController
}

/** Why a mode cannot be enforced right now. */
enum class UnavailableReason {
    /** This build contains no restriction engine (only [NoRestrictionEnforcer], used by tests). */
    EngineMissing,
    DeviceUnsupported,
    PermissionMissing,
    PolicyRejected,

    /** fOCUS could not read its own stored schedules, so it cannot check for conflicts. */
    StorageError,
}

sealed interface Capability {
    data object Available : Capability

    /** Works, but only partly (for example notifications only). The person is told before starting. */
    data class Limited(val limit: CapabilityLimit) : Capability

    data class Unavailable(val reason: UnavailableReason) : Capability
}

sealed interface EnforcementResult {
    data object Applied : EnforcementResult
    data class Failed(val reason: EndReason) : EnforcementResult
}

/**
 * Applies and removes the real restrictions (Phase 5). The session engine only ever
 * shows a session as active after [apply] returned [EnforcementResult.Applied].
 */
interface RestrictionEnforcer {
    suspend fun capability(mode: FocusMode): Capability

    /** Must be idempotent: the engine calls it again after a restart to re-establish restrictions. */
    suspend fun apply(plan: SessionPlan): EnforcementResult

    /** Removes every restriction. Must be safe to call at any time, also when nothing is applied. */
    suspend fun release()

    /** Emits when restrictions stop working while a session runs (permission withdrawn, policy removed). */
    val losses: Flow<EndReason>
}
