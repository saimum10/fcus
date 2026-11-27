package com.focus.app.domain

import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------------------
// Phase 6: emergency access and trusted-person recovery. Pure Kotlin, no Android imports.
//
// Design in one paragraph: a trusted person is someone the user names. They choose a PIN on
// this device (the user hands them the phone), and only a hash of it is stored. To end a
// running session early, the trusted person enters that PIN. Nothing is ever sent anywhere,
// nobody is contacted by fOCUS. Each PIN works once. Emergency calling and information are a
// separate path that needs no PIN, is never rate-limited and is deliberately not logged.
// ---------------------------------------------------------------------------------------

/** Something the person typed that cannot be accepted. */
enum class InputProblem {
    NameEmpty,
    NameTooLong,
    PhoneInvalid,
    PinTooShort,
    PinTooLong,
    PinNotDigits,
    PinTooSimple,
}

/** The person who can end a session early. [phone] is optional and only used for a "Call" button. */
data class TrustedPerson(val name: String, val phone: String?)

/**
 * A salted PBKDF2 hash of the PIN. Only `recovery` knows how it is made; `domain` just
 * carries the three values. The iteration count is stored so it can be raised later.
 */
data class PinCredential(val iterations: Int, val saltHex: String, val hashHex: String)

/**
 * A time that has to pass, measured like [SessionTiming]: within one boot on the monotonic
 * clock (changing the wall clock or time zone cannot shorten it), after a restart on the
 * wall clock (the only option then; a clock set forward while the device was off cannot
 * be detected). Used for the wrong-PIN lockout and the "forgot the PIN" waiting period.
 */
data class WaitTimer(
    val startedAtWallMillis: Long,
    val startedAtElapsedMillis: Long,
    val bootCount: Int?,
    val durationMillis: Long,
) {
    /** Time left, in `[0, durationMillis]`. */
    fun remainingMillis(now: TimeSnapshot): Long {
        val raw = if (sameBoot(now)) {
            durationMillis - (now.elapsedMillis - startedAtElapsedMillis)
        } else {
            startedAtWallMillis + durationMillis - now.wallMillis
        }
        return raw.coerceIn(0L, durationMillis)
    }

    private fun sameBoot(now: TimeSnapshot): Boolean {
        val anchor = bootCount
        val current = now.bootCount
        return if (anchor != null && current != null) {
            anchor == current
        } else {
            // No boot counter: the monotonic clock only goes backwards across a restart.
            now.elapsedMillis >= startedAtElapsedMillis
        }
    }

    fun toStorage(): String =
        listOf(startedAtWallMillis, startedAtElapsedMillis, bootCount?.toString() ?: NO_BOOT, durationMillis).joinToString(",")

    companion object {
        private const val NO_BOOT = "n"

        fun start(now: TimeSnapshot, durationMillis: Long) =
            WaitTimer(now.wallMillis, now.elapsedMillis, now.bootCount, durationMillis)

        /** Null if [text] is not something [toStorage] wrote. */
        fun fromStorage(text: String): WaitTimer? {
            val parts = text.split(',')
            if (parts.size != 4) return null
            val wall = parts[0].trim().toLongOrNull() ?: return null
            val elapsed = parts[1].trim().toLongOrNull() ?: return null
            val boot = if (parts[2].trim() == NO_BOOT) null else (parts[2].trim().toIntOrNull() ?: return null)
            val duration = parts[3].trim().toLongOrNull() ?: return null
            if (duration < 0L) return null
            return WaitTimer(wall, elapsed, boot, duration)
        }
    }
}

/** Rules for the trusted person's PIN. Digits only, so it can be typed on a numeric keypad. */
object PinPolicy {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    fun check(pin: String): InputProblem? {
        if (pin.any { it !in '0'..'9' }) return InputProblem.PinNotDigits
        if (pin.length < MIN_LENGTH) return InputProblem.PinTooShort
        if (pin.length > MAX_LENGTH) return InputProblem.PinTooLong
        if (pin.all { it == pin[0] }) return InputProblem.PinTooSimple
        val steps = pin.zipWithNext { a, b -> b - a }
        if (steps.all { it == 1 } || steps.all { it == -1 }) return InputProblem.PinTooSimple
        return null
    }
}

/** Rules for the trusted person's name and optional phone number. */
object TrustedPersonPolicy {
    const val MAX_NAME_LENGTH = 40
    const val MIN_PHONE_DIGITS = 3
    const val MAX_PHONE_DIGITS = 15

    /** How long a removal request without the PIN must wait before it can be completed. */
    const val REMOVAL_WAIT_MILLIS: Long = 24L * 60L * 60L * 1000L

    fun checkName(raw: String): InputProblem? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> InputProblem.NameEmpty
            name.length > MAX_NAME_LENGTH -> InputProblem.NameTooLong
            else -> null
        }
    }

    /** Blank is fine (no phone). Otherwise digits with optional separators and a leading plus. */
    fun checkPhone(raw: String): InputProblem? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.forEachIndexed { index, c ->
            val allowed = c in '0'..'9' || c in " -().\u00A0" || (c == '+' && index == 0)
            if (!allowed) return InputProblem.PhoneInvalid
        }
        val digits = text.count { it in '0'..'9' }
        return if (digits in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) null else InputProblem.PhoneInvalid
    }

    /** The number as the dialer wants it (digits, optional leading plus), or null when blank. */
    fun dialable(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val digits = text.filter { it in '0'..'9' }
        return if (text.startsWith("+")) "+$digits" else digits
    }
}

/** Rate limit for wrong PINs. Applies to recovery and to changing the trusted-person settings, never to emergency access. */
object AttemptPolicy {
    const val FREE_ATTEMPTS = 3
    private const val MINUTE = 60_000L

    /** How long to wait after the [failedAttempts]-th wrong PIN since the last success. */
    fun lockMillisAfter(failedAttempts: Int): Long = when {
        failedAttempts < FREE_ATTEMPTS -> 0L
        failedAttempts == FREE_ATTEMPTS -> 1 * MINUTE
        failedAttempts == FREE_ATTEMPTS + 1 -> 5 * MINUTE
        failedAttempts == FREE_ATTEMPTS + 2 -> 15 * MINUTE
        failedAttempts == FREE_ATTEMPTS + 3 -> 30 * MINUTE
        else -> 60 * MINUTE
    }

    fun freeAttemptsLeft(failedAttempts: Int): Int = (FREE_ATTEMPTS - failedAttempts).coerceAtLeast(0)
}

/**
 * The saved trusted person. [credential] is null after the PIN was used (each PIN works
 * once): the person is remembered but a new PIN is needed before they can end a session.
 * [removalWait] is set while a removal without the PIN was requested.
 */
data class RecoveryConfig(
    val person: TrustedPerson,
    val credential: PinCredential?,
    val removalWait: WaitTimer? = null,
)

/** Everything stored for recovery, saved as one atomic value. */
data class RecoveryRecord(
    val config: RecoveryConfig? = null,
    /** Wrong PINs since the last success. */
    val failedAttempts: Int = 0,
    /** Set while the person has to wait after wrong PINs. */
    val lock: WaitTimer? = null,
)

interface RecoveryRepository {
    /** The stored record (an empty one if nothing is stored). Throws IOException if it cannot be read or is damaged. */
    suspend fun load(): RecoveryRecord

    suspend fun save(record: RecoveryRecord)
}

interface PinHasher {
    fun hash(pin: String): PinCredential

    fun verify(pin: String, credential: PinCredential): Boolean
}

/** What the local audit log records. Never a PIN, never an emergency call. */
enum class AuditType {
    TrustedPersonSet,
    TrustedPersonChanged,
    PinReplaced,
    TrustedPersonRemoved,
    RemovalRequested,
    RemovalCancelled,
    RecoveryRequested,
    RecoveryCancelled,
    RecoverySucceeded,
    RecoveryFailed,
    RecoveryLocked,
    ConfigPinFailed,
}

data class AuditEvent(
    val id: String,
    val atWallMillis: Long,
    val type: AuditType,
    /** The session involved, if any. */
    val sessionId: String?,
)

interface AuditLog {
    suspend fun append(event: AuditEvent)

    /** Newest first. */
    fun observeRecent(limit: Int): Flow<List<AuditEvent>>

    suspend fun clear()
}

/** What the screens show about the trusted person. */
sealed interface RecoveryStatus {
    /** The stored data cannot be read. Nothing is assumed about it. */
    data object Unreadable : RecoveryStatus

    data object NotSetUp : RecoveryStatus

    data class SetUp(
        val person: TrustedPerson,
        /** False after the PIN was used: a new one is needed. */
        val hasPin: Boolean,
        val lockRemainingMillis: Long,
        /** Null when no removal was requested; 0 when the waiting time is over. */
        val removalRemainingMillis: Long?,
    ) : RecoveryStatus
}

/** Answer to every recovery action. Failures say why; nothing fails silently. */
sealed interface RecoveryResult {
    data object Done : RecoveryResult
    data class InvalidInput(val problem: InputProblem) : RecoveryResult

    /** Not allowed while a session is running (changing the trusted person, clearing the log). */
    data object SessionRunning : RecoveryResult

    /** There is no session in the state this action needs. */
    data object NoSession : RecoveryResult

    /** No trusted person with a usable PIN. */
    data object NotConfigured : RecoveryResult
    data object AlreadyConfigured : RecoveryResult
    data class WrongPin(val freeAttemptsLeft: Int) : RecoveryResult

    /** Too many wrong PINs; nothing is checked until the time has passed. */
    data class Locked(val remainingMillis: Long) : RecoveryResult

    /** A waiting period has not passed yet. */
    data class Waiting(val remainingMillis: Long) : RecoveryResult
    data object StorageError : RecoveryResult
}

/** The only way the UI sets up the trusted person, ends a session through recovery or reads the audit log. */
interface RecoveryController {
    suspend fun status(): RecoveryStatus

    /** First setup, or a new PIN after the previous one was used. */
    suspend fun setUp(person: TrustedPerson, newPin: String): RecoveryResult

    /** Changes details and optionally the PIN. Needs the current PIN. */
    suspend fun change(person: TrustedPerson, newPin: String?, currentPin: String): RecoveryResult

    /** Removes the trusted person. Needs the current PIN unless it was already used. */
    suspend fun remove(currentPin: String): RecoveryResult

    /** Starts the 24-hour wait for removing the trusted person without the PIN. */
    suspend fun requestRemoval(): RecoveryResult
    suspend fun cancelRemoval(): RecoveryResult
    suspend fun completeRemoval(): RecoveryResult

    /** Opens recovery for the running session (Active -> RecoveryPending). Restrictions stay until a PIN is accepted. */
    suspend fun requestRecovery(): RecoveryResult

    /** Checks the PIN; if right, ends the session and releases its restrictions. */
    suspend fun submitPin(pin: String): RecoveryResult

    /** Back to Active; the restrictions never stopped. */
    suspend fun cancelRecovery()

    fun auditEvents(limit: Int): Flow<List<AuditEvent>>

    suspend fun clearAudit(): RecoveryResult
}
