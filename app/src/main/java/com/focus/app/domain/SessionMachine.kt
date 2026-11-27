package com.focus.app.domain

/** Everything that can move the session state machine. */
sealed interface SessionEvent {
    data object BeginConfiguring : SessionEvent
    data object CancelConfiguring : SessionEvent
    data class StartRequested(val id: String, val plan: SessionPlan, val source: SessionSource) : SessionEvent

    /** Restrictions were applied for real; only then does a session count as active. */
    data class StartSucceeded(val session: RunningSession) : SessionEvent
    data class StartFailed(val reason: EndReason, val nowWallMillis: Long) : SessionEvent

    /** The planned time ran out. */
    data class Expired(val nowWallMillis: Long) : SessionEvent

    /** The restriction stopped working, or could not be re-applied. */
    data class Lost(val reason: EndReason, val nowWallMillis: Long, val activeMillis: Long) : SessionEvent
    data class RecoveryRequested(val nowWallMillis: Long) : SessionEvent
    data object RecoveryCancelled : SessionEvent
    data class RecoveryFinished(val nowWallMillis: Long, val activeMillis: Long) : SessionEvent

    /** The person dismissed the end-of-session result. */
    data object Acknowledged : SessionEvent
}

/**
 * The legal transitions, as one pure function. Anything not listed is illegal and
 * returns null, so the engine can never end up in a state the table does not allow:
 *
 * ```
 * Idle | Configuring | Completed | Interrupted | Failed -> Configuring        (BeginConfiguring)
 * Configuring                                          -> Idle               (CancelConfiguring)
 * Idle | Configuring | Completed | Interrupted | Failed -> Starting           (StartRequested)
 * Starting         -> Active | Failed                                         (StartSucceeded | StartFailed)
 * Active           -> Completed | Interrupted | RecoveryPending               (Expired | Lost | RecoveryRequested)
 * RecoveryPending  -> Active | Completed | Interrupted                        (RecoveryCancelled | Expired | Lost, RecoveryFinished)
 * Completed | Interrupted | Failed -> Idle                                    (Acknowledged)
 * ```
 */
object SessionMachine {
    fun reduce(state: SessionState, event: SessionEvent): SessionState? = when (event) {
        SessionEvent.BeginConfiguring ->
            if (state.allowsNewSession) SessionState.Configuring else null

        SessionEvent.CancelConfiguring ->
            if (state is SessionState.Configuring) SessionState.Idle else null

        is SessionEvent.StartRequested ->
            if (state.allowsNewSession) SessionState.Starting(event.id, event.plan, event.source) else null

        is SessionEvent.StartSucceeded ->
            if (state is SessionState.Starting && state.id == event.session.id) {
                SessionState.Active(event.session)
            } else {
                null
            }

        is SessionEvent.StartFailed ->
            if (state is SessionState.Starting) {
                val plan = state.plan
                SessionState.Failed(
                    FinishedSession(
                        id = state.id,
                        mode = plan.mode,
                        appCount = plan.packages.size,
                        plannedMinutes = plan.durationMinutes,
                        startedAtWallMillis = event.nowWallMillis,
                        endedAtWallMillis = event.nowWallMillis,
                        activeMillis = 0L,
                        outcome = SessionOutcome.Failed,
                        reason = event.reason,
                    ),
                )
            } else {
                null
            }

        is SessionEvent.Expired -> runningSession(state)?.let { session ->
            SessionState.Completed(finished(session, SessionOutcome.Completed, null, event.nowWallMillis, session.durationMillis))
        }

        is SessionEvent.Lost -> runningSession(state)?.let { session ->
            SessionState.Interrupted(finished(session, SessionOutcome.Interrupted, event.reason, event.nowWallMillis, event.activeMillis))
        }

        is SessionEvent.RecoveryRequested ->
            if (state is SessionState.Active) SessionState.RecoveryPending(state.session, event.nowWallMillis) else null

        SessionEvent.RecoveryCancelled ->
            if (state is SessionState.RecoveryPending) SessionState.Active(state.session) else null

        is SessionEvent.RecoveryFinished ->
            if (state is SessionState.RecoveryPending) {
                SessionState.Interrupted(
                    finished(state.session, SessionOutcome.Interrupted, EndReason.RecoveryUsed, event.nowWallMillis, event.activeMillis),
                )
            } else {
                null
            }

        SessionEvent.Acknowledged ->
            if (state is SessionState.Finished) SessionState.Idle else null
    }

    private fun runningSession(state: SessionState): RunningSession? = when (state) {
        is SessionState.Active -> state.session
        is SessionState.RecoveryPending -> state.session
        else -> null
    }

    private fun finished(
        session: RunningSession,
        outcome: SessionOutcome,
        reason: EndReason?,
        endedAtWallMillis: Long,
        activeMillis: Long,
    ) = FinishedSession(
        id = session.id,
        mode = session.plan.mode,
        appCount = session.plan.packages.size,
        plannedMinutes = session.plan.durationMinutes,
        startedAtWallMillis = session.startedAtWallMillis,
        endedAtWallMillis = endedAtWallMillis,
        activeMillis = activeMillis.coerceIn(0L, session.durationMillis),
        outcome = outcome,
        reason = reason,
    )
}
