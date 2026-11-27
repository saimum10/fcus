package com.focus.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Result of asking "could this start / be saved right now?" without doing it. */
sealed interface StartCheck {
    data object Ok : StartCheck
    data class Invalid(val problems: List<DraftProblem>) : StartCheck

    /** Another session is already running or starting. */
    data object SessionRunning : StartCheck
    data class Unavailable(val reason: UnavailableReason) : StartCheck

    /** Overlaps a scheduled window (or a running session) at [atMillis]. */
    data class ScheduleConflict(val atMillis: Long) : StartCheck
}

sealed interface StartResult {
    data object Started : StartResult
    data object Saved : StartResult
    data class Rejected(val check: StartCheck) : StartResult

    /** The start was attempted and failed; nothing is shown as restricted. */
    data class Failed(val reason: EndReason) : StartResult
}

/** The only way the UI starts, ends or observes sessions. Implemented by `session.SessionEngine`. */
interface SessionController {
    val state: StateFlow<SessionState>
    val schedules: Flow<List<ScheduleEntry>>

    /**
     * Brings everything up to date: restores stored state, ends an expired session,
     * re-applies restrictions after a restart, starts a due scheduled session and
     * re-arms the wake-up. Safe and cheap to call often (app start, resume, worker).
     */
    suspend fun reconcile()

    suspend fun beginConfiguring()
    suspend fun cancelConfiguring()

    suspend fun checkStart(plan: SessionPlan): StartCheck
    suspend fun start(plan: SessionPlan): StartResult

    suspend fun checkSchedule(window: ScheduleWindow): StartCheck
    suspend fun addSchedule(window: ScheduleWindow): StartResult
    suspend fun removeSchedule(id: String)

    /** Dismisses an end-of-session result and returns to Idle. */
    suspend fun acknowledge()

    /** Time left on [session], read from the monotonic clock. */
    fun remainingMillis(session: RunningSession): Long

    // Recovery hooks, driven by RecoveryService (Phase 6): the only caller, so the rules live there.
    suspend fun beginRecovery(): Boolean
    suspend fun cancelRecovery(): Boolean
    suspend fun finishRecovery(): Boolean
}
