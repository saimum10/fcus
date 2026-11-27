package com.focus.app.session

import com.focus.app.domain.Capability
import com.focus.app.domain.DraftProblem
import com.focus.app.domain.EndReason
import com.focus.app.domain.EnforcementResult
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RunningSession
import com.focus.app.domain.ScheduleEntry
import com.focus.app.domain.ScheduleWindow
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.SessionSource
import com.focus.app.domain.SessionState
import com.focus.app.domain.StartCheck
import com.focus.app.domain.StartResult
import com.focus.app.domain.UnavailableReason
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine against fakes: start rules, expiry, recovery after process death and
 * restart, schedules, failures. 2026-11-23 09:00 UTC is a Monday. Written in Phase 4, run in Phase 8.
 */
class SessionEngineTest {
    private val minute = 60_000L
    private val monday9 = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()

    private class Rig(startWall: Long) {
        val store = FakeSessionStore()
        val history = FakeHistory()
        val schedules = FakeSchedules()
        val enforcer = FakeEnforcer()
        val time = FakeTime(startWall)
        val waker = FakeWaker()
        private var ids = 0

        fun engine(): SessionEngine = SessionEngine(
            store = store,
            history = history,
            scheduleRepository = schedules,
            enforcer = enforcer,
            time = time,
            waker = waker,
            timerScope = null,
            zone = { ZoneOffset.UTC },
            newId = { "id${++ids}" },
        )
    }

    private fun rig(wall: Long = monday9) = Rig(wall)

    private fun full(minutes: Int = 60) = SessionPlan(FocusMode.FullFocus, emptySet(), minutes)

    private fun mondayWindow(startHour: Int, startMinute: Int = 0, minutes: Int = 60) =
        ScheduleWindow(setOf(DayOfWeek.MONDAY), startHour * 60 + startMinute, minutes)

    private fun atMonday(hour: Int, minute: Int = 0): Long = monday9 + (hour - 9) * 60 * this.minute + minute * this.minute

    /** A stored running session that began [agoMinutes] ago on this rig's clocks. */
    private fun Rig.runningSince(agoMinutes: Int, durationMinutes: Int = 60, boot: Int? = 1) = RunningSession(
        id = "stored",
        plan = full(durationMinutes),
        source = SessionSource.Manual,
        startedAtWallMillis = time.wall - agoMinutes * minute,
        endAtWallMillis = time.wall - agoMinutes * minute + durationMinutes * minute,
        durationMillis = durationMinutes * minute,
        startedAtElapsedMillis = time.elapsed - agoMinutes * minute,
        bootCount = boot,
        lastSeenWallMillis = time.wall,
    )

    // ---- Starting ---------------------------------------------------------------------

    @Test
    fun emptyStorage_startsIdle() = runBlocking {
        val engine = rig().engine()
        engine.reconcile()
        assertEquals(SessionState.Idle, engine.state.value)
    }

    @Test
    fun beforeRestore_theStateIsRestoring_notIdle() {
        assertEquals(SessionState.Restoring, rig().engine().state.value)
    }

    @Test
    fun start_appliesRestrictions_thenShowsActive_storesIt_andArmsTheWakeUp() = runBlocking {
        val r = rig()
        val engine = r.engine()
        assertEquals(StartResult.Started, engine.start(full(60)))

        val active = engine.state.value as SessionState.Active
        assertEquals(60 * minute, active.session.durationMillis)
        assertEquals(monday9 + 60 * minute, active.session.endAtWallMillis)
        assertEquals(listOf(full(60)), r.enforcer.applied)
        assertEquals(active, r.store.stored)
        assertEquals(60 * minute, r.waker.lastDelayMillis)
        assertEquals(60 * minute, engine.remainingMillis(active.session))
    }

    @Test
    fun start_isRefusedWhenTheModeCannotBeEnforced_andNothingIsApplied() = runBlocking {
        val r = rig()
        r.enforcer.capability = Capability.Unavailable(UnavailableReason.EngineMissing)
        val engine = r.engine()
        assertEquals(
            StartResult.Rejected(StartCheck.Unavailable(UnavailableReason.EngineMissing)),
            engine.start(full()),
        )
        assertEquals(SessionState.Idle, engine.state.value)
        assertTrue(r.enforcer.applied.isEmpty())
    }

    @Test
    fun start_whenApplyFails_endsAsFailed_releases_andIsRecorded() = runBlocking {
        val r = rig()
        r.enforcer.applyResult = EnforcementResult.Failed(EndReason.PolicyFailure)
        val engine = r.engine()

        assertEquals(StartResult.Failed(EndReason.PolicyFailure), engine.start(full()))

        val failed = engine.state.value as SessionState.Failed
        assertEquals(EndReason.PolicyFailure, failed.result.reason)
        assertEquals(1, r.enforcer.releaseCount)
        assertEquals(failed, r.store.stored)
        assertEquals(listOf(SessionOutcome.Failed), r.history.entries.values.map { it.outcome })
    }

    @Test
    fun start_whenApplyThrowsSecurityException_failsWithPermissionLost() = runBlocking {
        val r = rig()
        r.enforcer.applyThrows = SecurityException("revoked")
        val engine = r.engine()
        assertEquals(StartResult.Failed(EndReason.PermissionLost), engine.start(full()))
        assertTrue(engine.state.value is SessionState.Failed)
    }

    @Test
    fun start_whenActiveCannotBeStored_isNeverShownAsActive() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.reconcile()
        r.store.failSave = true

        assertEquals(StartResult.Failed(EndReason.InternalError), engine.start(full()))

        assertTrue(engine.state.value is SessionState.Failed)
        assertEquals(1, r.enforcer.releaseCount)
    }

    @Test
    fun secondStart_isRejected_whileASessionRuns() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.start(full())
        assertEquals(StartResult.Rejected(StartCheck.SessionRunning), engine.start(full(30)))
        assertEquals(1, r.enforcer.applied.size)
    }

    @Test
    fun invalidPlans_areRejectedBeforeAnythingIsApplied() = runBlocking {
        val r = rig()
        val engine = r.engine()
        val noApps = SessionPlan(FocusMode.SelectedApps, emptySet(), 30)
        assertEquals(
            StartResult.Rejected(StartCheck.Invalid(listOf(DraftProblem.NoAppsSelected))),
            engine.start(noApps),
        )
        assertEquals(
            StartResult.Rejected(StartCheck.Invalid(listOf(DraftProblem.DurationTooShort))),
            engine.start(full(1)),
        )
        assertTrue(r.enforcer.applied.isEmpty())
    }

    // ---- Ending -----------------------------------------------------------------------

    @Test
    fun session_completesWhenTheTimeIsUp_andReleases() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.start(full(60))

        r.time.advance(30 * minute)
        engine.reconcile()
        assertTrue(engine.state.value is SessionState.Active)
        assertEquals(30 * minute, r.waker.lastDelayMillis)

        r.time.advance(30 * minute)
        engine.reconcile()

        val completed = engine.state.value as SessionState.Completed
        assertEquals(SessionOutcome.Completed, completed.result.outcome)
        assertEquals(60 * minute, completed.result.activeMillis)
        assertEquals(1, r.enforcer.releaseCount)
        assertEquals(completed, r.store.stored)
        assertEquals(1, r.history.entries.size)
        assertTrue(r.waker.cancelled)
    }

    @Test
    fun changingTheWallClock_doesNotShortenOrExtendARunningSession() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.start(full(60))
        r.time.elapsed += 10 * minute // 10 real minutes pass...
        r.time.wall += 5 * 60 * minute // ...but the clock is set 5 hours ahead

        engine.reconcile()

        val active = engine.state.value as SessionState.Active
        assertEquals(50 * minute, engine.remainingMillis(active.session))
        assertEquals(r.time.wall + 50 * minute, active.session.endAtWallMillis) // display corrected
    }

    @Test
    fun acknowledge_returnsToIdle_andKeepsHistory() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.start(full(30))
        r.time.advance(30 * minute)
        engine.reconcile()

        engine.acknowledge()

        assertEquals(SessionState.Idle, engine.state.value)
        assertEquals(SessionState.Idle, r.store.stored)
        assertEquals(1, r.history.entries.size)
    }

    @Test
    fun enforcementLoss_interruptsWithTheReason_andRecordsActiveTime() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.start(full(60))
        r.time.advance(10 * minute)

        engine.onEnforcementLost(EndReason.EnforcementLost)

        val interrupted = engine.state.value as SessionState.Interrupted
        assertEquals(EndReason.EnforcementLost, interrupted.result.reason)
        assertEquals(10 * minute, interrupted.result.activeMillis)
        assertEquals(1, r.enforcer.releaseCount)
        assertEquals(1, r.history.entries.size)

        engine.onEnforcementLost(EndReason.EnforcementLost) // nothing running: no effect
        assertEquals(1, r.history.entries.size)
    }

    // ---- Process death and restart ----------------------------------------------------

    @Test
    fun afterProcessDeath_theStoredSessionIsRestored_andRestrictionsReapplied() = runBlocking {
        val r = rig()
        r.store.stored = SessionState.Active(r.runningSince(agoMinutes = 10))
        val engine = r.engine()

        engine.reconcile()

        val active = engine.state.value as SessionState.Active
        assertEquals(50 * minute, engine.remainingMillis(active.session))
        assertEquals(1, r.enforcer.applied.size)
        assertEquals(50 * minute, r.waker.lastDelayMillis)

        engine.reconcile() // applied once per process, not on every call
        assertEquals(1, r.enforcer.applied.size)
    }

    @Test
    fun ifRestrictionsCannotBeReapplied_theSessionIsInterrupted_notShownAsActive() = runBlocking {
        val r = rig()
        r.store.stored = SessionState.Active(r.runningSince(agoMinutes = 10))
        r.enforcer.applyResult = EnforcementResult.Failed(EndReason.PermissionLost)
        val engine = r.engine()

        engine.reconcile()

        val interrupted = engine.state.value as SessionState.Interrupted
        assertEquals(EndReason.PermissionLost, interrupted.result.reason)
        assertEquals(10 * minute, interrupted.result.activeMillis)
        assertEquals(1, r.enforcer.releaseCount)
        assertEquals(1, r.history.entries.size)
    }

    @Test
    fun afterARestart_theSessionContinuesFromTheWallClock_andIsReanchored() = runBlocking {
        val r = rig()
        val stored = r.runningSince(agoMinutes = 0)
        r.store.stored = SessionState.Active(stored)
        // 20 minutes later the device has rebooted: new boot, monotonic clock reset.
        r.time.wall = stored.startedAtWallMillis + 20 * minute
        r.time.elapsed = 3_000L
        r.time.boot = 2
        val engine = r.engine()

        engine.reconcile()

        val active = engine.state.value as SessionState.Active
        assertEquals(2, active.session.bootCount)
        assertEquals(40 * minute, engine.remainingMillis(active.session))
        assertEquals(active, r.store.stored) // re-anchored copy was saved

        r.time.advance(10 * minute)
        assertEquals(30 * minute, engine.remainingMillis(active.session)) // monotonic again
    }

    @Test
    fun aSessionThatEndedWhileTheDeviceWasOff_isCompleted() = runBlocking {
        val r = rig()
        val stored = r.runningSince(agoMinutes = 0)
        r.store.stored = SessionState.Active(stored)
        r.time.wall = stored.startedAtWallMillis + 2 * 60 * minute
        r.time.elapsed = 3_000L
        r.time.boot = 2
        val engine = r.engine()

        engine.reconcile()

        assertTrue(engine.state.value is SessionState.Completed)
        assertTrue(r.enforcer.applied.isEmpty()) // nothing to re-apply for a finished session
        assertEquals(1, r.enforcer.releaseCount)
    }

    @Test
    fun aStartThatNeverFinished_isFailedAndReleased() = runBlocking {
        val r = rig()
        r.store.stored = SessionState.Starting("half", full(), SessionSource.Manual)
        val engine = r.engine()

        engine.reconcile()

        val failed = engine.state.value as SessionState.Failed
        assertEquals(EndReason.InternalError, failed.result.reason)
        assertEquals(1, r.enforcer.releaseCount)
        assertEquals("half", r.history.entries.values.single().id)
    }

    @Test
    fun anUnreadableStore_isReported_notHiddenAsIdle() = runBlocking {
        val r = rig()
        r.store.failLoad = true
        val engine = r.engine()

        engine.reconcile()

        val failed = engine.state.value as SessionState.Failed
        assertEquals(EndReason.InternalError, failed.result.reason)
    }

    @Test
    fun anUnfinishedResultSurvivesARestart() = runBlocking {
        val r = rig()
        val first = r.engine()
        first.start(full(30))
        r.time.advance(30 * minute)
        first.reconcile()

        val second = r.engine() // new process, same storage
        second.reconcile()

        assertTrue(second.state.value is SessionState.Completed)
    }

    // ---- Configuring and recovery hooks -----------------------------------------------

    @Test
    fun configuring_isOnlyPossibleWhenNothingRuns() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.beginConfiguring()
        assertEquals(SessionState.Configuring, engine.state.value)
        engine.cancelConfiguring()
        assertEquals(SessionState.Idle, engine.state.value)

        engine.start(full())
        engine.beginConfiguring()
        assertTrue(engine.state.value is SessionState.Active)
    }

    @Test
    fun startingFromConfiguring_works_andConfiguringIsNeverStored() = runBlocking {
        val r = rig()
        val engine = r.engine()
        engine.reconcile()
        engine.beginConfiguring()
        assertNull(r.store.stored?.takeIf { it == SessionState.Configuring })
        assertEquals(StartResult.Started, engine.start(full()))
    }

    @Test
    fun recoveryHooks_followTheStateMachine() = runBlocking {
        val r = rig()
        val engine = r.engine()
        assertFalse(engine.beginRecovery()) // nothing to recover
        engine.start(full())

        assertTrue(engine.beginRecovery())
        assertTrue(engine.state.value is SessionState.RecoveryPending)
        assertTrue(r.store.stored is SessionState.RecoveryPending)
        assertTrue(engine.cancelRecovery())
        assertTrue(engine.state.value is SessionState.Active)

        engine.beginRecovery()
        assertTrue(engine.finishRecovery())
        val interrupted = engine.state.value as SessionState.Interrupted
        assertEquals(EndReason.RecoveryUsed, interrupted.result.reason)
        assertEquals(1, r.enforcer.releaseCount)
    }

    // ---- Schedules --------------------------------------------------------------------

    @Test
    fun addSchedule_savesIt_andAWindowThatIsAlreadyOpenWaitsForNextWeek() = runBlocking {
        val r = rig(atMonday(9, 10))
        val engine = r.engine()

        assertEquals(StartResult.Saved, engine.addSchedule(mondayWindow(9)))

        assertEquals(atMonday(9), r.schedules.current.single().lastStartedWindowStartMillis)
        engine.reconcile()
        assertEquals(SessionState.Idle, engine.state.value) // not started mid-window
        assertEquals(7 * 24 * 60 * minute - 10 * minute, r.waker.lastDelayMillis) // next Monday 09:00
    }

    @Test
    fun addSchedule_rejectsOverlapsInvalidWindowsAndUnavailableModes() = runBlocking {
        val r = rig(atMonday(8))
        val engine = r.engine()
        assertEquals(StartResult.Saved, engine.addSchedule(mondayWindow(9)))

        assertEquals(
            StartResult.Rejected(StartCheck.ScheduleConflict(atMonday(9))),
            engine.addSchedule(mondayWindow(9, 30)),
        )
        assertEquals(
            StartResult.Rejected(StartCheck.Invalid(listOf(DraftProblem.NoScheduleDays))),
            engine.addSchedule(ScheduleWindow(emptySet(), 600, 60)),
        )
        r.enforcer.capability = Capability.Unavailable(UnavailableReason.PermissionMissing)
        assertEquals(
            StartResult.Rejected(StartCheck.Unavailable(UnavailableReason.PermissionMissing)),
            engine.addSchedule(ScheduleWindow(setOf(DayOfWeek.FRIDAY), 600, 60)),
        )
        assertEquals(1, r.schedules.current.size)
    }

    @Test
    fun aRunningSession_blocksAScheduleThatWouldOverlapIt() = runBlocking {
        val r = rig(atMonday(9, 10))
        val engine = r.engine()
        engine.start(full(60)) // until 10:10

        assertEquals(
            StartResult.Rejected(StartCheck.ScheduleConflict(atMonday(9, 30))),
            engine.addSchedule(mondayWindow(9, 30)),
        )
    }

    @Test
    fun aManualSession_cannotOverlapAScheduledWindow() = runBlocking {
        val r = rig(atMonday(9, 30))
        r.schedules.upsert(ScheduleEntry("s", mondayWindow(10)))
        val engine = r.engine()

        assertEquals(
            StartResult.Rejected(StartCheck.ScheduleConflict(atMonday(10))),
            engine.start(full(60)),
        )
        assertEquals(StartResult.Started, engine.start(full(20))) // ends 09:50, before the window
    }

    @Test
    fun aDueSchedule_startsOnReconcile_forTheRestOfItsWindow() = runBlocking {
        val r = rig(atMonday(9, 10))
        r.schedules.upsert(ScheduleEntry("sch", mondayWindow(9)))
        val engine = r.engine()

        engine.reconcile()

        val active = engine.state.value as SessionState.Active
        assertEquals(SessionSource.Scheduled("sch", atMonday(9)), active.session.source)
        assertEquals(FocusMode.ScheduledFocus, active.session.plan.mode)
        assertEquals(50 * minute, active.session.durationMillis)
        assertEquals(listOf("sch" to atMonday(9)), r.schedules.markedWindows)
    }

    @Test
    fun aWindowIsNeverStartedTwice_evenIfItsSessionWasInterrupted() = runBlocking {
        val r = rig(atMonday(9, 10))
        r.schedules.upsert(ScheduleEntry("sch", mondayWindow(9)))
        val engine = r.engine()
        engine.reconcile()
        engine.onEnforcementLost(EndReason.EnforcementLost)

        r.time.advance(minute)
        engine.reconcile()

        assertTrue(engine.state.value is SessionState.Interrupted)
        assertEquals(1, r.enforcer.applied.size)
    }

    @Test
    fun aWindowWithTooLittleTimeLeft_isSkippedAndMarkedHandled() = runBlocking {
        val r = rig(atMonday(9, 57)) // 3 minutes left, below the 5 minute minimum
        r.schedules.upsert(ScheduleEntry("sch", mondayWindow(9)))
        val engine = r.engine()

        engine.reconcile()

        assertEquals(SessionState.Idle, engine.state.value)
        assertEquals(1, r.schedules.markedWindows.size)
        assertTrue(r.enforcer.applied.isEmpty())
    }

    @Test
    fun aScheduledStart_thatCannotBeEnforced_failsVisibly() = runBlocking {
        val r = rig(atMonday(9, 10))
        r.schedules.upsert(ScheduleEntry("sch", mondayWindow(9)))
        r.enforcer.capability = Capability.Unavailable(UnavailableReason.PermissionMissing)
        val engine = r.engine()

        engine.reconcile()

        val failed = engine.state.value as SessionState.Failed
        assertEquals(EndReason.PermissionLost, failed.result.reason)
        assertTrue(r.enforcer.applied.isEmpty())
        assertEquals(1, r.history.entries.size)
    }

    @Test
    fun removingASchedule_cancelsItsWakeUp() = runBlocking {
        val r = rig(atMonday(8))
        val engine = r.engine()
        engine.addSchedule(mondayWindow(9))
        assertEquals(60 * minute, r.waker.lastDelayMillis)

        engine.removeSchedule(r.schedules.current.single().id)

        assertTrue(r.waker.cancelled)
    }
}
