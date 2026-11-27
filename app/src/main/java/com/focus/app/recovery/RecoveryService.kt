package com.focus.app.recovery

import com.focus.app.domain.AttemptPolicy
import com.focus.app.domain.AuditEvent
import com.focus.app.domain.AuditLog
import com.focus.app.domain.AuditType
import com.focus.app.domain.InputProblem
import com.focus.app.domain.PinCredential
import com.focus.app.domain.PinHasher
import com.focus.app.domain.PinPolicy
import com.focus.app.domain.RecoveryConfig
import com.focus.app.domain.RecoveryController
import com.focus.app.domain.RecoveryRecord
import com.focus.app.domain.RecoveryRepository
import com.focus.app.domain.RecoveryResult
import com.focus.app.domain.RecoveryStatus
import com.focus.app.domain.SessionController
import com.focus.app.domain.SessionState
import com.focus.app.domain.TimeSource
import com.focus.app.domain.TrustedPerson
import com.focus.app.domain.TrustedPersonPolicy
import com.focus.app.domain.WaitTimer
import com.focus.app.domain.allowsNewSession
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Trusted-person recovery. Pure Kotlin over `domain` ports; the session itself is only ever
 * touched through [SessionController], so the state machine stays the single authority on
 * what may happen to a session.
 *
 * Rules (covered by `RecoveryServiceTest`):
 * - A session can be ended early only with the trusted person's PIN. There is no other
 *   early-unlock path here. (Emergency calling and information are separate, need no PIN
 *   and never pass through this class.)
 * - Recovery is opened only if a usable PIN exists, so a session is never put into
 *   RecoveryPending when nothing could finish it.
 * - Wrong PINs are counted in storage (they survive restarts) and lock further attempts for
 *   a growing time, measured on two clocks. The counter is shared by recovery and by
 *   changing the settings, so the settings screen is no way around it.
 * - The session is ended FIRST and the PIN is used up afterwards. If the process dies in
 *   between, the person is not trapped; the worst case is a PIN that works once more.
 * - Trusted-person settings and the audit log cannot be changed while a session runs.
 * - An audit write that fails never blocks recovery. Nothing here ever logs a PIN.
 * - Everything runs under one mutex.
 *
 * [hashContext] is where the slow PBKDF2 work runs (a background dispatcher in the app).
 */
class RecoveryService(
    private val repository: RecoveryRepository,
    private val hasher: PinHasher,
    private val audit: AuditLog,
    private val sessions: SessionController,
    private val time: TimeSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val hashContext: CoroutineContext = Dispatchers.Default,
) : RecoveryController {

    private val mutex = Mutex()

    // ---- Status -------------------------------------------------------------------------

    override suspend fun status(): RecoveryStatus = mutex.withLock {
        val record = load() ?: return@withLock RecoveryStatus.Unreadable
        val config = record.config ?: return@withLock RecoveryStatus.NotSetUp
        val now = time.now()
        RecoveryStatus.SetUp(
            person = config.person,
            hasPin = config.credential != null,
            lockRemainingMillis = record.lock?.remainingMillis(now) ?: 0L,
            removalRemainingMillis = config.removalWait?.remainingMillis(now),
        )
    }

    // ---- Trusted-person settings (never while a session runs) ---------------------------

    override suspend fun setUp(person: TrustedPerson, newPin: String): RecoveryResult = mutex.withLock {
        validate(person, newPin)?.let { return@withLock RecoveryResult.InvalidInput(it) }
        if (!isIdle()) return@withLock RecoveryResult.SessionRunning
        val record = load() ?: return@withLock RecoveryResult.StorageError
        // A PIN that is still valid is changed with `change`, which asks for it.
        if (record.config?.credential != null) return@withLock RecoveryResult.AlreadyConfigured
        val credential = hashPin(newPin)
        if (!save(RecoveryRecord(config = RecoveryConfig(normalized(person), credential)))) {
            return@withLock RecoveryResult.StorageError
        }
        log(if (record.config == null) AuditType.TrustedPersonSet else AuditType.PinReplaced)
        RecoveryResult.Done
    }

    override suspend fun change(person: TrustedPerson, newPin: String?, currentPin: String): RecoveryResult = mutex.withLock {
        validate(person, newPin)?.let { return@withLock RecoveryResult.InvalidInput(it) }
        if (!isIdle()) return@withLock RecoveryResult.SessionRunning
        val record = load() ?: return@withLock RecoveryResult.StorageError
        val config = record.config ?: return@withLock RecoveryResult.NotConfigured
        val credential = config.credential ?: return@withLock RecoveryResult.NotConfigured
        val attempt = attemptPin(record, credential, currentPin, AuditType.ConfigPinFailed, sessionId = null)
        if (attempt is Attempt.Rejected) return@withLock attempt.result
        val updated = if (newPin != null) hashPin(newPin) else credential
        if (!save(RecoveryRecord(config = RecoveryConfig(normalized(person), updated)))) {
            return@withLock RecoveryResult.StorageError
        }
        log(if (newPin != null) AuditType.PinReplaced else AuditType.TrustedPersonChanged)
        RecoveryResult.Done
    }

    override suspend fun remove(currentPin: String): RecoveryResult = mutex.withLock {
        if (!isIdle()) return@withLock RecoveryResult.SessionRunning
        val record = load() ?: return@withLock RecoveryResult.StorageError
        val config = record.config ?: return@withLock RecoveryResult.NotConfigured
        val credential = config.credential
        if (credential != null) {
            val attempt = attemptPin(record, credential, currentPin, AuditType.ConfigPinFailed, sessionId = null)
            if (attempt is Attempt.Rejected) return@withLock attempt.result
        }
        if (!save(RecoveryRecord())) return@withLock RecoveryResult.StorageError
        log(AuditType.TrustedPersonRemoved)
        RecoveryResult.Done
    }

    override suspend fun requestRemoval(): RecoveryResult = mutex.withLock {
        if (!isIdle()) return@withLock RecoveryResult.SessionRunning
        val record = load() ?: return@withLock RecoveryResult.StorageError
        val config = record.config ?: return@withLock RecoveryResult.NotConfigured
        // Without a usable PIN nothing needs to wait: the person can simply be removed.
        if (config.credential == null) return@withLock RecoveryResult.NotConfigured
        val now = time.now()
        config.removalWait?.let { return@withLock RecoveryResult.Waiting(it.remainingMillis(now)) }
        val started = config.copy(removalWait = WaitTimer.start(now, TrustedPersonPolicy.REMOVAL_WAIT_MILLIS))
        if (!save(record.copy(config = started))) return@withLock RecoveryResult.StorageError
        log(AuditType.RemovalRequested)
        RecoveryResult.Done
    }

    override suspend fun cancelRemoval(): RecoveryResult = mutex.withLock {
        val record = load() ?: return@withLock RecoveryResult.StorageError
        val config = record.config ?: return@withLock RecoveryResult.NotConfigured
        if (config.removalWait == null) return@withLock RecoveryResult.Done
        if (!save(record.copy(config = config.copy(removalWait = null)))) return@withLock RecoveryResult.StorageError
        log(AuditType.RemovalCancelled)
        RecoveryResult.Done
    }

    override suspend fun completeRemoval(): RecoveryResult = mutex.withLock {
        if (!isIdle()) return@withLock RecoveryResult.SessionRunning
        val record = load() ?: return@withLock RecoveryResult.StorageError
        val wait = record.config?.removalWait ?: return@withLock RecoveryResult.NotConfigured
        val remaining = wait.remainingMillis(time.now())
        if (remaining > 0L) return@withLock RecoveryResult.Waiting(remaining)
        if (!save(RecoveryRecord())) return@withLock RecoveryResult.StorageError
        log(AuditType.TrustedPersonRemoved)
        RecoveryResult.Done
    }

    // ---- Recovery of a running session --------------------------------------------------

    override suspend fun requestRecovery(): RecoveryResult = mutex.withLock {
        when (val state = sessions.state.value) {
            is SessionState.RecoveryPending -> RecoveryResult.Done
            is SessionState.Active -> {
                val record = load() ?: return@withLock RecoveryResult.StorageError
                if (record.config?.credential == null) return@withLock RecoveryResult.NotConfigured
                if (!sessions.beginRecovery()) return@withLock RecoveryResult.NoSession
                log(AuditType.RecoveryRequested, state.session.id)
                RecoveryResult.Done
            }
            else -> RecoveryResult.NoSession
        }
    }

    override suspend fun submitPin(pin: String): RecoveryResult = mutex.withLock {
        val pending = sessions.state.value as? SessionState.RecoveryPending ?: return@withLock RecoveryResult.NoSession
        val sessionId = pending.session.id
        val record = load() ?: return@withLock RecoveryResult.StorageError
        val config = record.config
        val credential = config?.credential ?: return@withLock RecoveryResult.NotConfigured

        val attempt = attemptPin(record, credential, pin, AuditType.RecoveryFailed, sessionId)
        if (attempt is Attempt.Rejected) return@withLock attempt.result

        // End the session first: restrictions are released and the result is stored by the engine.
        if (!sessions.finishRecovery()) return@withLock RecoveryResult.NoSession
        log(AuditType.RecoverySucceeded, sessionId)
        // Then use the PIN up. If this write fails the PIN stays valid once more; that is the
        // safe side of the trade (the person is never left stuck behind a half-finished recovery).
        save(record.copy(config = config.copy(credential = null, removalWait = null), failedAttempts = 0, lock = null))
        RecoveryResult.Done
    }

    override suspend fun cancelRecovery() {
        mutex.withLock {
            val state = sessions.state.value
            if (state is SessionState.RecoveryPending && sessions.cancelRecovery()) {
                log(AuditType.RecoveryCancelled, state.session.id)
            }
        }
    }

    // ---- Audit log ----------------------------------------------------------------------

    override fun auditEvents(limit: Int): Flow<List<AuditEvent>> = audit.observeRecent(limit)

    override suspend fun clearAudit(): RecoveryResult = mutex.withLock {
        if (!isIdle()) return@withLock RecoveryResult.SessionRunning
        try {
            audit.clear()
            RecoveryResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RecoveryResult.StorageError
        }
    }

    // ---- Internals ----------------------------------------------------------------------

    private sealed interface Attempt {
        data object Passed : Attempt
        data class Rejected(val result: RecoveryResult) : Attempt
    }

    /**
     * One PIN check with rate limiting. A check during a lockout is refused without being
     * counted. A wrong PIN is stored (counter and, from the third, a lockout) and logged
     * BEFORE the answer is given, so closing the app cannot reset the count.
     */
    private suspend fun attemptPin(
        record: RecoveryRecord,
        credential: PinCredential,
        pin: String,
        failureType: AuditType,
        sessionId: String?,
    ): Attempt {
        val now = time.now()
        val lockLeft = record.lock?.remainingMillis(now) ?: 0L
        if (lockLeft > 0L) return Attempt.Rejected(RecoveryResult.Locked(lockLeft))

        val correct = pin.isNotEmpty() && withContext(hashContext) { hasher.verify(pin, credential) }
        if (correct) return Attempt.Passed

        val failed = record.failedAttempts + 1
        val lockMillis = AttemptPolicy.lockMillisAfter(failed)
        val updated = record.copy(
            failedAttempts = failed,
            lock = if (lockMillis > 0L) WaitTimer.start(now, lockMillis) else null,
        )
        if (!save(updated)) return Attempt.Rejected(RecoveryResult.StorageError)
        log(failureType, sessionId)
        if (lockMillis > 0L) {
            log(AuditType.RecoveryLocked, sessionId)
            return Attempt.Rejected(RecoveryResult.Locked(lockMillis))
        }
        return Attempt.Rejected(RecoveryResult.WrongPin(AttemptPolicy.freeAttemptsLeft(failed)))
    }

    private fun isIdle(): Boolean = sessions.state.value.allowsNewSession

    private fun validate(person: TrustedPerson, newPin: String?): InputProblem? =
        TrustedPersonPolicy.checkName(person.name)
            ?: TrustedPersonPolicy.checkPhone(person.phone.orEmpty())
            ?: newPin?.let { PinPolicy.check(it) }

    private fun normalized(person: TrustedPerson) =
        TrustedPerson(person.name.trim(), TrustedPersonPolicy.dialable(person.phone.orEmpty()))

    private suspend fun hashPin(pin: String): PinCredential = withContext(hashContext) { hasher.hash(pin) }

    /** Null if the stored data cannot be read; callers then change nothing and say so. */
    private suspend fun load(): RecoveryRecord? = try {
        repository.load()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun save(record: RecoveryRecord): Boolean = try {
        repository.save(record)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun log(type: AuditType, sessionId: String? = null) {
        try {
            audit.append(AuditEvent(newId(), time.now().wallMillis, type, sessionId))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The audit log is a record, not a gate: a failed write never blocks recovery.
        }
    }
}
