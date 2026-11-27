package com.focus.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How much control fOCUS has over this device. Detected, never assumed. */
enum class DeploymentLevel {
    /** A normal app: no way to block other apps. */
    Regular,

    /** fOCUS is the Device Owner of a properly provisioned, managed device. */
    DeviceOwner,

    /**
     * A normal app that the person allowed to use Shizuku. Apps are suspended through the
     * system's own `pm suspend` command, run with shell rights. No Lock Task, no Device Owner.
     */
    Shizuku,
}

/** What fOCUS can tell about Shizuku right now. Read from the device every time, never assumed. */
enum class ShizukuState {
    /** Shizuku is not running (or too old, or not installed): nothing can be run with shell rights. */
    NotRunning,

    /** Shizuku is running but the person has not allowed fOCUS yet. */
    NeedsPermission,

    /** Shizuku is running and fOCUS is allowed to use it. */
    Granted,
}

/** What this device lets fOCUS do right now. Read again whenever the person returns from system settings. */
data class DeviceCapabilities(
    val level: DeploymentLevel,
    /** Suspending other apps (`DevicePolicyManager.setPackagesSuspended`): Device Owner only. */
    val canSuspendApps: Boolean,
    /** Lock Task allowlist and features: Device Owner on Android 9+. */
    val canUseLockTask: Boolean,
    /** The device has a screen where the person can grant notification-policy (Do Not Disturb) access. */
    val notificationControlAvailable: Boolean,
    /** That access has been granted to fOCUS. */
    val notificationControlGranted: Boolean,
    /** Shizuku status. Does not change [level] by itself: only [ShizukuState.Granted] makes it Shizuku. */
    val shizuku: ShizukuState = ShizukuState.NotRunning,
    /** What the last Shizuku steps really did, for the guide screen. Empty if none ran yet in this process. */
    val shizukuDetails: String = "",
)

/** What a mode can do on a regular device when it is only partly enforceable. */
enum class CapabilityLimit {
    /** Silences notifications except calls. Apps can still be opened. */
    NotificationsOnly,
}

/**
 * The Do Not Disturb state fOCUS found before it changed it, so it can put it back.
 * The numbers are opaque to `domain`; only `device` knows what they mean.
 */
data class InterruptionSnapshot(
    val filter: Int,
    val categories: Int,
    val callSenders: Int,
    val messageSenders: Int,
    val visualEffects: Int,
    val conversationSenders: Int,
) {
    fun toStorage(): String =
        listOf(filter, categories, callSenders, messageSenders, visualEffects, conversationSenders).joinToString(",")

    companion object {
        /** Null if [text] is not something [toStorage] wrote. */
        fun fromStorage(text: String): InterruptionSnapshot? {
            val parts = text.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 6) return null
            return InterruptionSnapshot(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
        }
    }
}

/**
 * Write-ahead record of everything fOCUS may have changed on the device. It is saved
 * BEFORE a change is made and cleared only after every change was confirmed undone, so a
 * crash or restart can never leave apps suspended with nobody who knows about it.
 */
data class RestrictionJournalEntry(
    val suspended: Set<String> = emptySet(),
    val lockTaskSet: Boolean = false,
    /** The original Do Not Disturb state; non-null while fOCUS may have changed it. */
    val interruption: InterruptionSnapshot? = null,
    /** True while fOCUS may have blocked uninstalling apps; cleared only after that was confirmed undone. */
    val uninstallBlocked: Boolean = false,
) {
    val isEmpty: Boolean get() = suspended.isEmpty() && !lockTaskSet && interruption == null && !uninstallBlocked
}

interface RestrictionJournal {
    /** The stored entry, or null if nothing is stored. Throws IOException if unreadable. */
    suspend fun load(): RestrictionJournalEntry?

    suspend fun save(entry: RestrictionJournalEntry)

    suspend fun clear()
}

/**
 * The few platform operations the restriction engine needs (implemented in `device`).
 * Every method is blocking and must not be called on the main thread.
 */
interface DeviceControl {
    fun current(): DeviceCapabilities

    /** Suspends or un-suspends [packages]. Returns the packages the system refused. */
    fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String>

    /**
     * Force-stops (kills) [packages] before they are suspended, best effort. The default does nothing:
     * on a Device Owner, suspending an app already stops it.
     */
    fun forceStop(packages: Set<String>) {}

    /** Which of [packages] the system really reports as suspended. Uninstalled packages are not. */
    fun suspendedAmong(packages: Set<String>): Set<String>

    /** Sets the Lock Task allowlist (fOCUS itself is always included); null removes it. False if the system refused. */
    fun setLockTaskAllowlist(packages: Set<String>?): Boolean

    /** The current Do Not Disturb state, or null if fOCUS may not read it. */
    fun currentInterruption(): InterruptionSnapshot?

    /** Do Not Disturb with calls (and alarms) allowed from anyone. False if not permitted. */
    fun silenceExceptCalls(): Boolean

    /** Whether the Do Not Disturb state fOCUS sets is still in force. */
    fun isSilenced(): Boolean

    /**
     * Puts the state back. True when nothing is left to do, which includes the person
     * having changed Do Not Disturb themselves meanwhile (their change is never overwritten).
     */
    fun restoreInterruption(snapshot: InterruptionSnapshot): Boolean

    /** Gives up Device Owner status. False if not the owner or refused. */
    fun relinquishOwnership(): Boolean

    /** Asks Shizuku to show its permission dialog for fOCUS. False if Shizuku could not be asked. Not blocking. */
    fun requestShizukuPermission(): Boolean = false

    /**
     * Blocks (or un-blocks) uninstalling apps, so fOCUS cannot simply be removed during a session.
     * True only when the system confirmed the requested state. The default is for a device that
     * cannot do it: blocking fails, and there is nothing to undo.
     */
    fun setUninstallBlocked(blocked: Boolean): Boolean = !blocked

    /** Emits when something that matters changed: ownership, notification access, Do Not Disturb, installed apps. */
    fun changes(): Flow<Unit>
}

/** What is really enforced right now, for the screens. Nothing here is a promise, only a report. */
data class RestrictionStatus(
    val active: Boolean = false,
    /** Apps confirmed suspended by the system. */
    val suspendedCount: Int = 0,
    /** Apps that should have been suspended but could not be. */
    val notRestrictedCount: Int = 0,
    val notificationsSilenced: Boolean = false,
    /** Lock Task was requested; it only takes effect while fOCUS is open. */
    val lockTaskRequested: Boolean = false,
    val limit: CapabilityLimit? = null,
    /** Uninstalling apps (so also fOCUS) is blocked while the session runs. False if that could not be confirmed. */
    val uninstallBlocked: Boolean = false,
    /** Blocking went through Shizuku and Shizuku has stopped: blocked apps stay blocked, the session keeps running. */
    val shizukuStopped: Boolean = false,
)

/** What the UI may ask about the device. Implemented by `session.RestrictionCoordinator`. */
interface DeviceManagement {
    fun capabilities(): DeviceCapabilities

    val status: StateFlow<RestrictionStatus>

    /** Removes all restrictions and gives up Device Owner status. Refused while a session runs. */
    suspend fun relinquishDeviceOwner(): Boolean

    /** Asks Shizuku to show its permission dialog for fOCUS. False if Shizuku could not be asked. */
    fun requestShizukuPermission(): Boolean = false
}

/** Lets the activity know when Lock Task should be on (it can only be started from a visible activity). */
interface LockTaskSignal {
    val wanted: StateFlow<Boolean>
}

class MutableLockTaskSignal : LockTaskSignal {
    private val state = MutableStateFlow(false)
    override val wanted: StateFlow<Boolean> = state

    fun request(on: Boolean) {
        state.value = on
    }
}
