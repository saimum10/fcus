package com.focus.app.recovery

import com.focus.app.domain.AuditType
import com.focus.app.domain.EndReason
import com.focus.app.domain.FocusMode
import com.focus.app.domain.InputProblem
import com.focus.app.domain.RecoveryResult
import com.focus.app.domain.RecoveryStatus
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.SessionState
import com.focus.app.domain.StartResult
import com.focus.app.domain.TrustedPerson
import com.focus.app.session.FakeEnforcer
import com.focus.app.session.FakeHistory
import com.focus.app.session.FakeSchedules
import com.focus.app.session.FakeSessionStore
import com.focus.app.session.FakeTime
import com.focus.app.session.FakeWaker
import com.focus.app.session.SessionEngine
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery rules against the REAL session engine (with fakes underneath), so every path
 * through RecoveryPending is exercised end to end: PIN right and wrong, lockout, restart,
 * permission loss, expiry, unreadable storage, failing audit. The PIN hash is a fake here;
 * `Pbkdf2PinHasherTest` covers the real one. Written in Phase 6, run in Phase 8.
 */
class RecoveryServiceTest {
    private val minute = 60_000L
    private val monday9 = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()
    private val pin = "482915"
    private val sam = TrustedPerson("Sam", null)

    private class Rig(startWall: Long) {
        val store = FakeSessionStore()
        val history = FakeHistory()
        val enforcer = FakeEnforcer()
        val time = FakeTime(startWall)
        val waker = FakeWaker()
        val repo = FakeRecoveryRepository()
        val audit = FakeAuditLog()
        private var sessionIds = 0
        private var auditIds = 0

        fun newEngine() = SessionEngine(
            store = store,
            history = history,
            scheduleRepository = FakeSchedules(),
            enforcer = enforcer,
            time = time,
            waker = waker,
            timerScope = null,
            zone = { ZoneOffset.UTC },
            newId = { "id${++sessionIds}" },
        )

        var engine = newEngine()

        fun newService(sessions: SessionEngine = engine) = RecoveryService(
            repository = repo,
            hasher = FakeHasher(),
            audit = audit,
            sessions = sessions,
            time = time,
            newId = { "a${++auditIds}" },
            hashContext = EmptyCoroutineContext,
        )

        var service = newService()

        val types get() = audit.events.map { it.type }
    }

    private fun rig() = Rig(monday9).also { runBlocking { it.engine.reconcile() } }

    private suspend fun Rig.startSession(minutes: Int = 60) {
        assertEquals(StartResult.Started, engine.start(SessionPlan(FocusMode.FullFocus, emptySet(), minutes)))
    }

    private suspend fun Rig.trustSam() {
        assertEquals(RecoveryResult.Done, service.setUp(sam, pin))
    }

    private val Rig.state get() = engine.state.value

    // ---- Setup and validation ----------------------------------------------------------

    @Test
    fun setUp_storesAHashNeverThePin_andLogsIt() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        val config = r.repo.record.config!!
        assertEquals("Sam", config.person.name)
        assertNotNull(config.credential)
        assertTrue(config.credential!!.hashHex != pin)
        assertEquals(listOf(AuditType.TrustedPersonSet), r.types)
        assertTrue(r.audit.events.none { it.toString().contains(pin) })
        assertEquals(RecoveryStatus.SetUp(sam, hasPin = true, lockRemainingMillis = 0L, removalRemainingMillis = null), r.service.status())
    }

    @Test
    fun setUp_rejectsBadInput_andSavesNothing() = runBlocking<Unit> {
        val r = rig()
        assertEquals(RecoveryResult.InvalidInput(InputProblem.NameEmpty), r.service.setUp(TrustedPerson(" ", null), pin))
        assertEquals(RecoveryResult.InvalidInput(InputProblem.PhoneInvalid), r.service.setUp(TrustedPerson("Sam", "call me"), pin))
        assertEquals(RecoveryResult.InvalidInput(InputProblem.PinTooShort), r.service.setUp(sam, "1234"))
        assertEquals(RecoveryResult.InvalidInput(InputProblem.PinTooSimple), r.service.setUp(sam, "111111"))
        assertEquals(0, r.repo.saves)
        assertTrue(r.audit.events.isEmpty())
    }

    @Test
    fun setUp_normalizesNameAndPhone() = runBlocking<Unit> {
        val r = rig()
        assertEquals(RecoveryResult.Done, r.service.setUp(TrustedPerson("  Sam  ", "+44 20 7946 0958"), pin))
        assertEquals(TrustedPerson("Sam", "+442079460958"), r.repo.record.config!!.person)
    }

    @Test
    fun setUp_whileAPinExists_isRefused() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertEquals(RecoveryResult.AlreadyConfigured, r.service.setUp(sam, "907261"))
    }

    @Test
    fun settingsAndLogCannotBeChangedWhileASessionRuns() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        assertEquals(RecoveryResult.SessionRunning, r.service.setUp(sam, "907261"))
        assertEquals(RecoveryResult.SessionRunning, r.service.change(sam, null, pin))
        assertEquals(RecoveryResult.SessionRunning, r.service.remove(pin))
        assertEquals(RecoveryResult.SessionRunning, r.service.requestRemoval())
        assertEquals(RecoveryResult.SessionRunning, r.service.clearAudit())
        assertTrue(r.audit.events.isNotEmpty())
        assertNotNull(r.repo.record.config?.credential)
    }

    @Test
    fun change_needsTheCurrentPin_andCanReplaceDetailsAndPin() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertEquals(RecoveryResult.WrongPin(2), r.service.change(TrustedPerson("Alex", null), null, "000001"))
        assertEquals("Sam", r.repo.record.config!!.person.name)

        assertEquals(RecoveryResult.Done, r.service.change(TrustedPerson("Alex", null), null, pin))
        assertEquals("Alex", r.repo.record.config!!.person.name)

        assertEquals(RecoveryResult.Done, r.service.change(TrustedPerson("Alex", null), "907261", pin))
        assertEquals("h907261", r.repo.record.config!!.credential!!.hashHex)
        assertEquals(
            listOf(AuditType.TrustedPersonSet, AuditType.ConfigPinFailed, AuditType.TrustedPersonChanged, AuditType.PinReplaced),
            r.types,
        )
    }

    // ---- Opening recovery --------------------------------------------------------------

    @Test
    fun requestRecovery_withoutATrustedPerson_changesNothing() = runBlocking<Unit> {
        val r = rig()
        r.startSession()
        assertEquals(RecoveryResult.NotConfigured, r.service.requestRecovery())
        assertTrue(r.state is SessionState.Active)
        assertEquals(RecoveryResult.NoSession, r.service.submitPin(pin))
        assertTrue(r.state is SessionState.Active)
    }

    @Test
    fun requestRecovery_withNoRunningSession_isRefused() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertEquals(RecoveryResult.NoSession, r.service.requestRecovery())
        assertEquals(RecoveryResult.NoSession, r.service.submitPin(pin))
    }

    @Test
    fun requestRecovery_keepsTheRestrictions_andIsLoggedOnce() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        assertEquals(RecoveryResult.Done, r.service.requestRecovery())
        assertTrue(r.state is SessionState.RecoveryPending)
        assertEquals(0, r.enforcer.releaseCount)
        assertEquals(RecoveryResult.Done, r.service.requestRecovery())
        assertEquals(1, r.types.count { it == AuditType.RecoveryRequested })
    }

    @Test
    fun cancelRecovery_goesBackToActive_andIsLogged() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        r.service.cancelRecovery()
        assertTrue(r.state is SessionState.Active)
        assertEquals(0, r.enforcer.releaseCount)
        assertEquals(AuditType.RecoveryCancelled, r.types.last())
        r.service.cancelRecovery() // nothing to cancel: no second entry
        assertEquals(1, r.types.count { it == AuditType.RecoveryCancelled })
    }

    // ---- The PIN -----------------------------------------------------------------------

    @Test
    fun rightPin_endsTheSession_releases_records_andUsesThePinUp() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.time.advance(10 * minute)
        r.service.requestRecovery()

        assertEquals(RecoveryResult.Done, r.service.submitPin(pin))

        val ended = r.state as SessionState.Interrupted
        assertEquals(SessionOutcome.Interrupted, ended.result.outcome)
        assertEquals(EndReason.RecoveryUsed, ended.result.reason)
        assertEquals(1, r.enforcer.releaseCount)
        assertEquals(listOf(ended.result), r.history.entries.values.toList())
        assertEquals(AuditType.RecoverySucceeded, r.types.last())
        // Single use: the person stays, the PIN is gone.
        assertEquals(RecoveryStatus.SetUp(sam, hasPin = false, lockRemainingMillis = 0L, removalRemainingMillis = null), r.service.status())
        // And the same PIN cannot end the next session.
        r.engine.acknowledge()
        r.startSession()
        assertEquals(RecoveryResult.NotConfigured, r.service.requestRecovery())
    }

    @Test
    fun wrongPins_areCounted_thenLocked_andTheSessionKeepsRunning() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()

        assertEquals(RecoveryResult.WrongPin(2), r.service.submitPin("000001"))
        assertEquals(RecoveryResult.WrongPin(1), r.service.submitPin("000002"))
        assertEquals(RecoveryResult.Locked(minute), r.service.submitPin("000003"))

        assertTrue(r.state is SessionState.RecoveryPending)
        assertEquals(0, r.enforcer.releaseCount)
        assertEquals(3, r.repo.record.failedAttempts)
        assertEquals(
            listOf(AuditType.RecoveryFailed, AuditType.RecoveryFailed, AuditType.RecoveryFailed, AuditType.RecoveryLocked),
            r.types.filter { it == AuditType.RecoveryFailed || it == AuditType.RecoveryLocked },
        )
    }

    @Test
    fun duringALock_evenTheRightPinIsRefused_andNotCounted() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        repeat(3) { r.service.submitPin("00000$it") }
        val failedBefore = r.repo.record.failedAttempts

        r.time.advance(30_000L)
        assertEquals(RecoveryResult.Locked(30_000L), r.service.submitPin(pin))
        assertEquals(failedBefore, r.repo.record.failedAttempts)
        assertTrue(r.state is SessionState.RecoveryPending)

        r.time.advance(30_000L)
        assertEquals(RecoveryResult.Done, r.service.submitPin(pin))
    }

    @Test
    fun theLockGrowsWithEveryFurtherWrongPin() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        repeat(3) { r.service.submitPin("00000$it") }
        r.time.advance(minute)
        assertEquals(RecoveryResult.Locked(5 * minute), r.service.submitPin("000009"))
        r.time.advance(5 * minute)
        assertEquals(RecoveryResult.Locked(15 * minute), r.service.submitPin("000009"))
    }

    @Test
    fun theLockSurvivesARestartOfTheAppProcess() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        repeat(3) { r.service.submitPin("00000$it") }

        // The process dies; a new engine and a new service read what was stored.
        r.engine = r.newEngine()
        r.service = r.newService()
        r.engine.reconcile()
        r.time.advance(20_000L)

        val status = r.service.status() as RecoveryStatus.SetUp
        assertEquals(40_000L, status.lockRemainingMillis)
        assertEquals(RecoveryResult.Locked(40_000L), r.service.submitPin(pin))
    }

    @Test
    fun changingTheClockDoesNotShortenTheLock() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        repeat(3) { r.service.submitPin("00000$it") }

        r.time.wall += 24 * 60 * minute // the wall clock is set a day forward; the monotonic clock is not
        assertEquals(RecoveryResult.Locked(minute), r.service.submitPin(pin))
        assertTrue(r.state is SessionState.RecoveryPending)
    }

    @Test
    fun wrongPinsInTheSettingsShareTheSameCounter() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertEquals(RecoveryResult.WrongPin(2), r.service.change(sam, null, "000001"))
        assertEquals(RecoveryResult.WrongPin(1), r.service.remove("000002"))
        assertEquals(RecoveryResult.Locked(minute), r.service.change(sam, null, "000003"))
        assertEquals(RecoveryResult.Locked(minute), r.service.remove(pin))
        assertNotNull(r.repo.record.config)
    }

    // ---- Crash, restart, permission loss, expiry ---------------------------------------

    @Test
    fun processDeathDuringRecovery_keepsThePendingState_andThePinStillWorks() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()

        r.engine = r.newEngine()
        r.service = r.newService()
        r.engine.reconcile()

        assertTrue(r.state is SessionState.RecoveryPending)
        assertEquals(2, r.enforcer.applied.size) // restrictions re-applied after the restart
        assertEquals(RecoveryResult.Done, r.service.requestRecovery())
        assertEquals(RecoveryResult.Done, r.service.submitPin(pin))
        assertEquals(EndReason.RecoveryUsed, (r.state as SessionState.Interrupted).result.reason)
    }

    @Test
    fun permissionLossDuringRecovery_endsTheSession_andThePinIsNotUsedUp() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()

        r.engine.onEnforcementLost(EndReason.PermissionLost)

        assertEquals(EndReason.PermissionLost, (r.state as SessionState.Interrupted).result.reason)
        assertEquals(RecoveryResult.NoSession, r.service.submitPin(pin))
        assertNotNull(r.repo.record.config?.credential) // the PIN is still valid for a later session
    }

    @Test
    fun theSessionEndingByTimeDuringRecovery_winsOverThePin() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession(minutes = 30)
        r.service.requestRecovery()

        r.time.advance(31 * minute)
        r.engine.reconcile()

        assertEquals(SessionOutcome.Completed, (r.state as SessionState.Completed).result.outcome)
        assertEquals(RecoveryResult.NoSession, r.service.submitPin(pin))
        assertNotNull(r.repo.record.config?.credential)
    }

    // ---- Storage failures --------------------------------------------------------------

    @Test
    fun unreadableData_blocksRecovery_andSaysSo() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.repo.failLoad = true

        assertEquals(RecoveryStatus.Unreadable, r.service.status())
        assertEquals(RecoveryResult.StorageError, r.service.requestRecovery())
        assertTrue(r.state is SessionState.Active)

        r.repo.failLoad = false
        r.service.requestRecovery()
        r.repo.failLoad = true
        assertEquals(RecoveryResult.StorageError, r.service.submitPin(pin))
        assertTrue(r.state is SessionState.RecoveryPending)
        assertEquals(0, r.enforcer.releaseCount)
    }

    @Test
    fun aWrongPinThatCannotBeCounted_isReportedAsAStorageError() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        r.repo.failSave = true
        assertEquals(RecoveryResult.StorageError, r.service.submitPin("000001"))
        assertTrue(r.state is SessionState.RecoveryPending)
    }

    @Test
    fun ifThePinCannotBeUsedUp_theSessionStillEnds() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        r.repo.failSave = true

        assertEquals(RecoveryResult.Done, r.service.submitPin(pin))

        assertTrue(r.state is SessionState.Interrupted)
        assertEquals(1, r.enforcer.releaseCount)
        assertNotNull(r.repo.record.config?.credential) // the safe side: the PIN works once more
    }

    @Test
    fun aFailingAuditLog_neverBlocksRecovery() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.audit.failAppend = true
        assertEquals(RecoveryResult.Done, r.service.requestRecovery())
        assertEquals(RecoveryResult.Done, r.service.submitPin(pin))
        assertTrue(r.state is SessionState.Interrupted)
    }

    // ---- Used-up PIN, re-arming, removal -----------------------------------------------

    @Test
    fun afterUse_aNewPinCanBeSet_andThePersonCanBeRemovedWithoutAPin() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.startSession()
        r.service.requestRecovery()
        r.service.submitPin(pin)
        r.engine.acknowledge()

        assertEquals(RecoveryResult.Done, r.service.setUp(sam, "907261"))
        assertEquals(AuditType.PinReplaced, r.types.last())
        assertTrue((r.service.status() as RecoveryStatus.SetUp).hasPin)

        r.startSession()
        r.service.requestRecovery()
        r.service.submitPin("907261")
        r.engine.acknowledge()

        assertEquals(RecoveryResult.Done, r.service.remove(""))
        assertEquals(RecoveryStatus.NotSetUp, r.service.status())
        assertEquals(AuditType.TrustedPersonRemoved, r.types.last())
    }

    @Test
    fun remove_withThePin_removesEverything() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertEquals(RecoveryResult.WrongPin(2), r.service.remove("000001"))
        assertEquals(RecoveryResult.Done, r.service.remove(pin))
        assertNull(r.repo.record.config)
        assertEquals(0, r.repo.record.failedAttempts)
        assertEquals(RecoveryStatus.NotSetUp, r.service.status())
    }

    @Test
    fun forgottenPin_removalWaits24Hours_onTheMonotonicClock() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertEquals(RecoveryResult.Done, r.service.requestRemoval())
        assertEquals(24 * 60 * minute, (r.service.status() as RecoveryStatus.SetUp).removalRemainingMillis)

        r.time.advance(23 * 60 * minute)
        assertEquals(RecoveryResult.Waiting(60 * minute), r.service.completeRemoval())

        // Moving the wall clock forward does not help within one boot.
        r.time.wall += 48 * 60 * minute
        assertEquals(RecoveryResult.Waiting(60 * minute), r.service.completeRemoval())

        r.time.advance(60 * minute)
        assertEquals(RecoveryResult.Done, r.service.completeRemoval())
        assertEquals(RecoveryStatus.NotSetUp, r.service.status())
        assertEquals(
            listOf(AuditType.TrustedPersonSet, AuditType.RemovalRequested, AuditType.TrustedPersonRemoved),
            r.types,
        )
    }

    @Test
    fun removalCanBeCancelled_andAChangeWithThePinCancelsIt() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.service.requestRemoval()
        assertEquals(RecoveryResult.Done, r.service.cancelRemoval())
        assertNull((r.service.status() as RecoveryStatus.SetUp).removalRemainingMillis)

        r.service.requestRemoval()
        assertEquals(RecoveryResult.Done, r.service.change(sam, null, pin))
        assertNull((r.service.status() as RecoveryStatus.SetUp).removalRemainingMillis)
    }

    @Test
    fun aRemovalThatIsDue_cannotBeCompletedWhileASessionRuns() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        r.service.requestRemoval()
        r.startSession(minutes = 60)
        r.time.advance(24 * 60 * minute) // the removal is due, but no reconcile ended the session yet
        assertEquals(RecoveryResult.SessionRunning, r.service.completeRemoval())
    }

    @Test
    fun theLogCanBeClearedWhenIdle() = runBlocking<Unit> {
        val r = rig()
        r.trustSam()
        assertFalse(r.audit.events.isEmpty())
        assertEquals(RecoveryResult.Done, r.service.clearAudit())
        assertTrue(r.audit.events.isEmpty())
    }
}
