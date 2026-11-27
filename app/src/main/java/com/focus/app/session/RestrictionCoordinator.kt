package com.focus.app.session

import com.focus.app.domain.Capability
import com.focus.app.domain.DeploymentLevel
import com.focus.app.domain.DeviceControl
import com.focus.app.domain.DeviceManagement
import com.focus.app.domain.EndReason
import com.focus.app.domain.EnforcementResult
import com.focus.app.domain.FocusMode
import com.focus.app.domain.InstalledAppsSource
import com.focus.app.domain.InterruptionSnapshot
import com.focus.app.domain.DeviceCapabilities
import com.focus.app.domain.DiscoveredApp
import com.focus.app.domain.RestrictionEnforcer
import com.focus.app.domain.RestrictionJournal
import com.focus.app.domain.RestrictionJournalEntry
import com.focus.app.domain.RestrictionPlanner
import com.focus.app.domain.RestrictionStatus
import com.focus.app.domain.RestrictionTargets
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.TimeSource
import com.focus.app.domain.UnavailableReason
import com.focus.app.domain.ModeSupport
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The real [RestrictionEnforcer]. Pure Kotlin: every platform call goes through
 * [DeviceControl], so the logic here is covered by JVM tests with fakes.
 *
 * Rules it keeps:
 * - **Write-ahead journal.** Everything that may be changed is saved to [journal] first and
 *   removed from it only after the undo was confirmed. A crash can leave apps suspended, but
 *   never without a record that lets the next start undo it ([releaseIfNotApplied]).
 * - **Honesty.** [apply] returns Applied only if the system confirmed the restriction
 *   (`suspendedAmong`), never because a call merely returned. Partial results are reported in [status].
 * - **Idempotent.** Applying twice, or after a restart, converges on the same state.
 * - **Loss detection.** [losses] re-checks on system events and once a minute.
 * - **Never overwrites the person's own changes** (Do Not Disturb) and never touches protected apps.
 */
class RestrictionCoordinator(
    private val device: DeviceControl,
    private val journal: RestrictionJournal,
    private val apps: InstalledAppsSource,
    private val time: TimeSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val checkIntervalMillis: Long = 60_000L,
) : RestrictionEnforcer, DeviceManagement {

    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(RestrictionStatus())

    /** What this process has applied. Null when nothing is applied (also right after a restart). */
    private var applied: Applied? = null

    private class Applied(
        val plan: SessionPlan,
        var suspended: Set<String>,
        var refused: Set<String>,
        val silenced: Boolean,
        val silenceIsCore: Boolean,
        val lockTask: Boolean,
        val uninstallBlocked: Boolean,
        /** The suspensions were made through Shizuku (not Device Owner), so losing Shizuku does not end the session. */
        val viaShizuku: Boolean,
        val limitNote: Capability,
        val appliedAtElapsed: Long,
    )

    override val status: StateFlow<RestrictionStatus> = mutableStatus

    override fun capabilities(): DeviceCapabilities = device.current()

    override fun requestShizukuPermission(): Boolean = device.requestShizukuPermission()

    /** Emits when something that matters on the device changed (ownership, Shizuku, Do Not Disturb, installed apps). */
    fun deviceChanges(): Flow<Unit> = device.changes()

    override suspend fun capability(mode: FocusMode): Capability = ModeSupport.capability(mode, device.current())

    // ---- apply --------------------------------------------------------------------------

    override suspend fun apply(plan: SessionPlan): EnforcementResult =
        mutex.withLock { withContext(io) { applyLocked(plan) } }

    private suspend fun applyLocked(plan: SessionPlan): EnforcementResult {
        val caps = device.current()
        val support = ModeSupport.capability(plan.mode, caps)
        if (support is Capability.Unavailable) return EnforcementResult.Failed(support.reason.toEndReason())

        val discovered: List<DiscoveredApp> = try {
            apps.loadApps()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return EnforcementResult.Failed(EndReason.PolicyFailure)
        }
        val wanted = RestrictionPlanner.plan(plan.mode, plan.packages, discovered, caps)
        if (wanted.isEmpty) return EnforcementResult.Failed(EndReason.PolicyFailure)

        val prior: RestrictionJournalEntry = try {
            journal.load() ?: RestrictionJournalEntry()
        } catch (e: IOException) {
            // Without a readable journal an undo cannot be guaranteed, so nothing is changed.
            return EnforcementResult.Failed(EndReason.InternalError)
        }

        // Write ahead: the journal lists everything this app may be about to change.
        val snapshot: InterruptionSnapshot? = prior.interruption ?: if (wanted.silence) device.currentInterruption() else null
        val entry = RestrictionJournalEntry(
            suspended = prior.suspended + wanted.suspend,
            lockTaskSet = prior.lockTaskSet || wanted.lockTask.isNotEmpty(),
            interruption = snapshot,
            uninstallBlocked = prior.uninstallBlocked || wanted.suspend.isNotEmpty(),
        )
        if (!saveJournal(entry)) return EnforcementResult.Failed(EndReason.InternalError)

        // Apps from an earlier apply that are not part of this plan any more are freed first.
        val stale = prior.suspended - wanted.suspend
        if (stale.isNotEmpty()) safely { device.setSuspended(stale, false) }

        // Running apps are stopped first, then suspended so they cannot start again. Protected apps (fOCUS, Shizuku,
        // home, phone, emergency, settings) are never in this set. A failed stop is not fatal: suspending stops the app too.
        if (wanted.suspend.isNotEmpty()) safely { device.forceStop(wanted.suspend) }

        val refused: Set<String> =
            if (wanted.suspend.isEmpty()) emptySet() else safelyOr(wanted.suspend) { device.setSuspended(wanted.suspend, true) }
        val confirmed: Set<String> =
            if (wanted.suspend.isEmpty()) emptySet() else safelyOr(emptySet<String>()) { device.suspendedAmong(wanted.suspend - refused) }
        val missing = wanted.suspend - confirmed
        val strict = plan.mode == FocusMode.SelectedApps
        if (wanted.suspend.isNotEmpty() && (confirmed.isEmpty() || (strict && missing.isNotEmpty()))) {
            releaseLocked()
            return EnforcementResult.Failed(EndReason.PolicyFailure)
        }

        var silenced = false
        if (wanted.silence) {
            silenced = safelyOr(false) { device.silenceExceptCalls() }
            if (!silenced && wanted.silenceIsCore) {
                releaseLocked()
                return EnforcementResult.Failed(EndReason.PermissionLost)
            }
        }
        val lockTask = wanted.lockTask.isNotEmpty() && safelyOr(false) { device.setLockTaskAllowlist(wanted.lockTask) }
        // Best effort: if the system refuses, the session still runs and the screen says fOCUS can be uninstalled.
        val uninstallBlocked = wanted.suspend.isNotEmpty() && safelyOr(false) { device.setUninstallBlocked(true) }

        val record = Applied(
            plan = plan,
            suspended = confirmed,
            refused = missing,
            silenced = silenced,
            silenceIsCore = wanted.silenceIsCore,
            lockTask = lockTask,
            uninstallBlocked = uninstallBlocked,
            viaShizuku = caps.level == DeploymentLevel.Shizuku,
            limitNote = support,
            appliedAtElapsed = time.now().elapsedMillis,
        )
        applied = record
        publish(record)
        return EnforcementResult.Applied
    }

    // ---- release ------------------------------------------------------------------------

    override suspend fun release() {
        mutex.withLock { withContext(io) { releaseLocked() } }
    }

    /**
     * Start-up safety net: if this process has applied nothing, undo whatever the journal
     * says an earlier process left behind (crash, restart, a failed earlier release).
     */
    suspend fun releaseIfNotApplied() {
        mutex.withLock {
            if (applied == null) {
                withContext(io) { releaseLocked() }
            }
        }
    }

    /** True when everything was confirmed undone and the journal was cleared. */
    private suspend fun releaseLocked(): Boolean {
        val entry: RestrictionJournalEntry? = try {
            journal.load()
        } catch (e: IOException) {
            null // Unreadable: undo what this process knows about; the rest cannot be found.
        }
        val inMemory = applied
        var clean = true

        val toFree = (entry?.suspended ?: emptySet()) + (inMemory?.suspended ?: emptySet()) + (inMemory?.refused ?: emptySet())
        var stillSuspended: Set<String> = emptySet()
        if (toFree.isNotEmpty()) {
            val refused = safelyOr(toFree) { device.setSuspended(toFree, false) }
            if (refused.isNotEmpty()) {
                stillSuspended = safelyOr(refused) { device.suspendedAmong(refused) }
                if (stillSuspended.isNotEmpty()) clean = false
            }
        }

        // Always asked: it also tells the activity to leave Lock Task. Harmless when nothing was set.
        val lockCleared = safelyOr(false) { device.setLockTaskAllowlist(null) }
        if (!lockCleared && (entry?.lockTaskSet == true || inMemory?.lockTask == true)) clean = false

        // Only asked when this app may have blocked uninstalling; failing to confirm keeps the journal entry.
        val blockMaybeSet = entry?.uninstallBlocked == true || inMemory?.uninstallBlocked == true
        val blockCleared = !blockMaybeSet || safelyOr(false) { device.setUninstallBlocked(false) }
        if (!blockCleared) clean = false

        var remainingSnapshot: InterruptionSnapshot? = null
        val snapshot = entry?.interruption
        if (snapshot != null && !safelyOr(false) { device.restoreInterruption(snapshot) }) {
            clean = false
            remainingSnapshot = snapshot
        }

        try {
            if (clean) {
                journal.clear()
            } else {
                journal.save(
                    RestrictionJournalEntry(
                        suspended = stillSuspended,
                        lockTaskSet = !lockCleared,
                        interruption = remainingSnapshot,
                        uninstallBlocked = !blockCleared,
                    ),
                )
            }
        } catch (e: IOException) {
            clean = false
        }
        applied = null
        mutableStatus.value = RestrictionStatus()
        return clean
    }

    // ---- losses -------------------------------------------------------------------------

    override val losses: Flow<EndReason> =
        merge(device.changes(), ticker()).mapNotNull { check() }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            delay(checkIntervalMillis)
            emit(Unit)
        }
    }

    /** Re-checks the running restrictions. Returns why they stopped working, or null if all is well. */
    suspend fun check(): EndReason? = mutex.withLock { withContext(io) { checkLocked() } }

    private suspend fun checkLocked(): EndReason? {
        val state = applied ?: return null
        val caps = device.current()

        if (!caps.canSuspendApps && state.suspended.isNotEmpty()) {
            // Shizuku stopped or killed: the suspensions are made by the system and stay in force, so the session
            // keeps running. Nothing can be checked or topped up until Shizuku is back; the screen says so.
            if (state.viaShizuku) {
                publish(state, shizukuStopped = true)
                return null
            }
            // Device Owner gone (removed by the system or the person): suspensions can no longer be held.
            return EndReason.PolicyFailure
        }
        if (caps.canSuspendApps && state.plan.mode != FocusMode.SelectedApps) topUp(state, caps)

        if (state.suspended.isNotEmpty()) {
            val still = safelyOr<Set<String>?>(null) { device.suspendedAmong(state.suspended) } ?: return null
            val missing = state.suspended - still
            if (missing.isNotEmpty()) {
                val installed = installedPackages() ?: return null
                if (missing.any { it in installed }) return EndReason.EnforcementLost
                state.suspended = state.suspended - missing // uninstalled by the person: nothing left to restrict
            }
        }

        if (state.silenced && state.silenceIsCore) {
            if (!caps.notificationControlGranted) return EndReason.PermissionLost
            val settled = time.now().elapsedMillis - state.appliedAtElapsed > SILENCE_GRACE_MILLIS
            if (settled && !safelyOr(true) { device.isSilenced() }) return EndReason.EnforcementLost
        }
        publish(state)
        return null
    }

    /** Apps installed during a Full Focus style session are restricted too. */
    private suspend fun topUp(state: Applied, caps: DeviceCapabilities) {
        val discovered = try {
            apps.loadApps()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val targets: RestrictionTargets = RestrictionPlanner.plan(state.plan.mode, state.plan.packages, discovered, caps)
        val fresh = targets.suspend - state.suspended - state.refused
        if (fresh.isEmpty()) return
        try {
            val entry = journal.load() ?: RestrictionJournalEntry()
            journal.save(entry.copy(suspended = entry.suspended + fresh)) // write ahead
        } catch (e: IOException) {
            return
        }
        val refused = safelyOr(fresh) { device.setSuspended(fresh, true) }
        state.suspended = state.suspended + (fresh - refused)
        state.refused = state.refused + refused
    }

    private suspend fun installedPackages(): Set<String>? = try {
        apps.loadApps().mapTo(HashSet()) { it.packageName }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ---- management ---------------------------------------------------------------------

    override suspend fun relinquishDeviceOwner(): Boolean = mutex.withLock {
        withContext(io) {
            if (applied != null) return@withContext false
            if (!releaseLocked()) return@withContext false
            safelyOr(false) { device.relinquishOwnership() }
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private fun publish(state: Applied, shizukuStopped: Boolean = false) {
        mutableStatus.value = RestrictionStatus(
            active = true,
            suspendedCount = state.suspended.size,
            notRestrictedCount = state.refused.size,
            notificationsSilenced = state.silenced,
            lockTaskRequested = state.lockTask,
            limit = (state.limitNote as? Capability.Limited)?.limit,
            uninstallBlocked = state.uninstallBlocked,
            shizukuStopped = shizukuStopped,
        )
    }

    private suspend fun saveJournal(entry: RestrictionJournalEntry): Boolean = try {
        journal.save(entry)
        true
    } catch (e: IOException) {
        false
    }

    /** Platform calls must never throw into the state machine; a failure is a failed restriction. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private inline fun <T> safelyOr(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    private fun UnavailableReason.toEndReason(): EndReason = when (this) {
        UnavailableReason.PermissionMissing -> EndReason.PermissionLost
        UnavailableReason.PolicyRejected -> EndReason.PolicyFailure
        UnavailableReason.StorageError -> EndReason.InternalError
        UnavailableReason.EngineMissing, UnavailableReason.DeviceUnsupported -> EndReason.DeviceUnsupported
    }

    private companion object {
        /** Do Not Disturb changes are applied asynchronously; do not read them back as lost right away. */
        const val SILENCE_GRACE_MILLIS = 5_000L
    }
}
