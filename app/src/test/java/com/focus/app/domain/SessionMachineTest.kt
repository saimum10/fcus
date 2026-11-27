package com.focus.app.domain

import com.focus.app.domain.SessionTestData.plan
import com.focus.app.domain.SessionTestData.running
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every legal transition, and the illegal ones that must be refused. Written in Phase 4, run in Phase 8. */
class SessionMachineTest {
    private val session = running()
    private val starting = SessionState.Starting("s1", plan(), SessionSource.Manual)
    private val active = SessionState.Active(session)
    private val recovery = SessionState.RecoveryPending(session, requestedAtWallMillis = 1L)
    private val failed = SessionMachine.reduce(starting, SessionEvent.StartFailed(EndReason.PolicyFailure, 10L))!!

    private fun reduce(state: SessionState, event: SessionEvent) = SessionMachine.reduce(state, event)

    @Test
    fun configuring_canBeEnteredAndLeft() {
        assertEquals(SessionState.Configuring, reduce(SessionState.Idle, SessionEvent.BeginConfiguring))
        assertEquals(SessionState.Idle, reduce(SessionState.Configuring, SessionEvent.CancelConfiguring))
    }

    @Test
    fun configuring_isNotAllowedWhileRunning() {
        listOf(starting, active, recovery, SessionState.Restoring).forEach {
            assertNull(reduce(it, SessionEvent.BeginConfiguring))
        }
    }

    @Test
    fun startRequested_onlyFromStates_thatAllowANewSession() {
        val request = SessionEvent.StartRequested("n", plan(), SessionSource.Manual)
        assertTrue(reduce(SessionState.Idle, request) is SessionState.Starting)
        assertTrue(reduce(SessionState.Configuring, request) is SessionState.Starting)
        assertTrue(reduce(failed, request) is SessionState.Starting)
        listOf(starting, active, recovery, SessionState.Restoring).forEach { assertNull(reduce(it, request)) }
    }

    @Test
    fun startSucceeded_requiresTheMatchingStartingState() {
        assertEquals(SessionState.Active(session), reduce(starting, SessionEvent.StartSucceeded(session)))
        assertNull(reduce(starting, SessionEvent.StartSucceeded(running(id = "other"))))
        assertNull(reduce(SessionState.Idle, SessionEvent.StartSucceeded(session)))
        assertNull(reduce(active, SessionEvent.StartSucceeded(session)))
    }

    @Test
    fun startFailed_endsAsFailedWithTheReason_andZeroActiveTime() {
        val result = (failed as SessionState.Failed).result
        assertEquals(SessionOutcome.Failed, result.outcome)
        assertEquals(EndReason.PolicyFailure, result.reason)
        assertEquals(0L, result.activeMillis)
        assertNull(reduce(active, SessionEvent.StartFailed(EndReason.PolicyFailure, 1L)))
    }

    @Test
    fun expired_completesActiveAndRecoveryPending() {
        val a = reduce(active, SessionEvent.Expired(2_000_000L)) as SessionState.Completed
        assertEquals(SessionOutcome.Completed, a.result.outcome)
        assertNull(a.result.reason)
        assertEquals(session.durationMillis, a.result.activeMillis)
        assertTrue(reduce(recovery, SessionEvent.Expired(2_000_000L)) is SessionState.Completed)
        assertNull(reduce(SessionState.Idle, SessionEvent.Expired(1L)))
    }

    @Test
    fun lost_interruptsWithReason_andClampsActiveTime() {
        val s = reduce(active, SessionEvent.Lost(EndReason.PermissionLost, 5L, activeMillis = session.durationMillis * 2))
            as SessionState.Interrupted
        assertEquals(EndReason.PermissionLost, s.result.reason)
        assertEquals(session.durationMillis, s.result.activeMillis)
        assertTrue(reduce(recovery, SessionEvent.Lost(EndReason.EnforcementLost, 5L, 0L)) is SessionState.Interrupted)
        assertNull(reduce(starting, SessionEvent.Lost(EndReason.EnforcementLost, 5L, 0L)))
    }

    @Test
    fun recovery_canBeRequestedCancelledAndFinished() {
        assertEquals(recovery.copy(requestedAtWallMillis = 9L), reduce(active, SessionEvent.RecoveryRequested(9L)))
        assertEquals(active, reduce(recovery, SessionEvent.RecoveryCancelled))
        val finished = reduce(recovery, SessionEvent.RecoveryFinished(7L, 1_000L)) as SessionState.Interrupted
        assertEquals(EndReason.RecoveryUsed, finished.result.reason)
        assertNull(reduce(active, SessionEvent.RecoveryCancelled))
        assertNull(reduce(active, SessionEvent.RecoveryFinished(7L, 0L)))
        assertNull(reduce(recovery, SessionEvent.RecoveryRequested(9L)))
    }

    @Test
    fun acknowledged_onlyLeavesAnEndState() {
        val completed = reduce(active, SessionEvent.Expired(1L))!!
        val interrupted = reduce(active, SessionEvent.Lost(EndReason.PolicyFailure, 1L, 0L))!!
        listOf(completed, interrupted, failed).forEach {
            assertEquals(SessionState.Idle, reduce(it, SessionEvent.Acknowledged))
        }
        listOf(SessionState.Idle, starting, active, recovery).forEach {
            assertNull(reduce(it, SessionEvent.Acknowledged))
        }
    }

    @Test
    fun finishedSession_requiresAReasonExactlyWhenNotCompleted() {
        val base = (SessionMachine.reduce(active, SessionEvent.Expired(1L)) as SessionState.Completed).result
        assertNotNull(base)
        runCatching { base.copy(reason = EndReason.InternalError) }.let { assertTrue(it.isFailure) }
        runCatching { base.copy(outcome = SessionOutcome.Interrupted, reason = null) }.let { assertTrue(it.isFailure) }
    }
}
