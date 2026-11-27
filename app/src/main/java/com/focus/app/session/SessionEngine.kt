package com.focus.app.session

import com.focus.app.domain.Capability
import com.focus.app.domain.DraftProblem
import com.focus.app.domain.DurationCheck
import com.focus.app.domain.DurationPolicy
import com.focus.app.domain.EndReason
import com.focus.app.domain.EnforcementResult
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RestrictionEnforcer
import com.focus.app.domain.RunningSession
import com.focus.app.domain.ScheduleEntry
import com.focus.app.domain.ScheduleMath
import com.focus.app.domain.ScheduleRepository
import com.focus.app.domain.ScheduleWindow
import com.focus.app.domain.SessionController
import com.focus.app.domain.SessionEvent
import com.focus.app.domain.SessionHistoryRepository
import com.focus.app.domain.SessionMachine
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.SessionSource
import com.focus.app.domain.SessionState
import com.focus.app.domain.SessionStore
import com.focus.app.domain.SessionTiming
import com.focus.app.domain.StartCheck
import com.focus.app.domain.StartResult
import com.focus.app.domain.TimeSnapshot
import com.focus.app.domain.TimeSource
import com.focus.app.domain.UnavailableReason
import com.focus.app.domain.WakeScheduler
import com.focus.app.domain.allowsNewSession
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session engine: owns the state machine, persistence, timing, schedules and history.
 *
 * Rules it enforces:
 * - A session is shown as Active only after the [RestrictionEnforcer] reported that the
 *   restriction was applied *and* the Active state was stored. Anything else ends in Failed
 *   and releases whatever was applied.
 * - Only one session at a time, and a session never overlaps a scheduled window.
 * - Everything runs under one mutex, so the worker, the UI and the in-process timer cannot interleave.
 * - After process death or a restart the stored state is restored, expiry is re-evaluated
 *   with [SessionTiming], and restrictions are re-applied before the session is trusted again.
 *
 * [timerScope] is optional: with it the engine also ends a session on time while the
 * process is alive (and listens for enforcement losses); without it only [reconcile] calls do.
 */
class SessionEngine(
    private val store: SessionStore,
    private val history: SessionHistoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val enforcer: RestrictionEnforcer,
    private val time: TimeSource,
    private val waker: WakeScheduler,
    private val timerScope: CoroutineScope? = null,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SessionController {

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)

    override val state: StateFlow<SessionState> = mutableState.asStateFlow()
    override val schedules: Flow<List<ScheduleEntry>> = scheduleRepository.observeAll()

    /** Whether restrictions were (re-)applied by this process. False after a restart until [reconcile] runs. */
    private var restrictionsApplied = false
    private var timerJob: Job? = null
    private var lossJob: Job? = null

    // ---- Public API ---------------------------------------------------------------------

    override suspend fun reconcile() = mutex.withLock {
        ensureRestoredLocked()
        tendRunningSessionLocked()
        startDueScheduleLocked()
        armWakeLocked()
    }

    override suspend fun beginConfiguring() = mutex.withLock {
        ensureRestoredLocked()
        transitionLocked(SessionEvent.BeginConfiguring)
        Unit
    }

    override suspend fun cancelConfiguring() = mutex.withLock {
        ensureRestoredLocked()
        transitionLocked(SessionEvent.CancelConfiguring)
        Unit
    }

    override suspend fun checkStart(plan: SessionPlan): StartCheck = mutex.withLock {
        ensureRestoredLocked()
        checkStartLocked(plan)
    }

    override suspend fun start(plan: SessionPlan): StartResult = mutex.withLock {
        ensureRestoredLocked()
        val check = checkStartLocked(plan)
        if (check != StartCheck.Ok) return@withLock StartResult.Rejected(check)
        val result = startLocked(plan, SessionSource.Manual, plan.durationMinutes * MILLIS_PER_MINUTE)
        armWakeLocked()
        result
    }

    override suspend fun checkSchedule(window: ScheduleWindow): StartCheck = mutex.withLock {
        ensureRestoredLocked()
        checkScheduleLocked(window)
    }

    override suspend fun addSchedule(window: ScheduleWindow): StartResult = mutex.withLock {
        ensureRestoredLocked()
        val check = checkScheduleLocked(window)
        if (check != StartCheck.Ok) return@withLock StartResult.Rejected(check)
        val now = time.now()
        // A window that is already open when it is saved starts next time, not immediately.
        val openWindowStart = ScheduleMath.activeAt(window, zone(), now.wallMillis)?.startMillis ?: 0L
        try {
            scheduleRepository.upsert(ScheduleEntry(newId(), window, enabled = true, lastStartedWindowStartMillis = openWindowStart))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock StartResult.Rejected(StartCheck.Unavailable(UnavailableReason.StorageError))
        }
        armWakeLocked()
        StartResult.Saved
    }

    override suspend fun removeSchedule(id: String) = mutex.withLock {
        ensureRestoredLocked()
        scheduleRepository.delete(id)
        armWakeLocked()
    }

    override suspend fun acknowledge() = mutex.withLock {
        ensureRestoredLocked()
        val current = mutableState.value
        if (current is SessionState.Finished) {
            // Idempotent: makes sure the result is in history even if the earlier write failed.
            recordHistory(current)
            transitionLocked(SessionEvent.Acknowledged)
        }
        Unit
    }

    override fun remainingMillis(session: RunningSession): Long = SessionTiming.remainingMillis(session, time.now())

    override suspend fun beginRecovery(): Boolean = mutex.withLock {
        ensureRestoredLocked()
        transitionLocked(SessionEvent.RecoveryRequested(time.now().wallMillis))
    }

    override suspend fun cancelRecovery(): Boolean = mutex.withLock {
        ensureRestoredLocked()
        transitionLocked(SessionEvent.RecoveryCancelled)
    }

    override suspend fun finishRecovery(): Boolean = mutex.withLock {
        ensureRestoredLocked()
        val session = runningSession(mutableState.value) ?: return@withLock false
        if (mutableState.value !is SessionState.RecoveryPending) return@withLock false
        val now = time.now()
        val ended = endLocked(SessionEvent.RecoveryFinished(now.wallMillis, SessionTiming.elapsedMillis(session, now)))
        armWakeLocked()
        ended
    }

    /** Called when the enforcer reports that restrictions stopped working. Public so Phase 5 and tests can drive it. */
    suspend fun onEnforcementLost(reason: EndReason) = mutex.withLock {
        val session = runningSession(mutableState.value) ?: return@withLock
        val now = time.now()
        endLocked(SessionEvent.Lost(reason, now.wallMillis, SessionTiming.elapsedMillis(session, now)))
        armWakeLocked()
    }

    // ---- Restore and reconcile ----------------------------------------------------------

    private suspend fun ensureRestoredLocked() {
        if (mutableState.value !is SessionState.Restoring) return
        val now = time.now()
        val loaded = try {
            store.load()
        } catch (e: IOException) {
            // Cannot tell whether a session was running. Say so instead of showing Idle.
            mutableState.value = SessionState.Failed(
                FinishedSession(
                    id = newId(),
                    mode = FocusMode.FullFocus,
                    appCount = 0,
                    plannedMinutes = 0,
                    startedAtWallMillis = now.wallMillis,
                    endedAtWallMillis = now.wallMillis,
                    activeMillis = 0L,
                    outcome = SessionOutcome.Failed,
                    reason = EndReason.InternalError,
                ),
            )
            return
        }
        when (loaded) {
            null, SessionState.Idle, SessionState.Restoring, SessionState.Configuring ->
                mutableState.value = SessionState.Idle

            is SessionState.Starting -> {
                // The process died while restrictions were being applied; the start never completed.
                releaseSafely()
                val failed = SessionMachine.reduce(loaded, SessionEvent.StartFailed(EndReason.InternalError, now.wallMillis))
                if (failed != null) {
                    commit(failed)
                    recordHistory(failed)
                } else {
                    mutableState.value = SessionState.Idle
                }
            }

            is SessionState.Active, is SessionState.RecoveryPending, is SessionState.Finished ->
                mutableState.value = loaded
        }
    }

    private suspend fun tendRunningSessionLocked() {
        val current = mutableState.value
        val session = runningSession(current) ?: return
        val now = time.now()

        if (SessionTiming.isExpired(session, now)) {
            endLocked(SessionEvent.Expired(now.wallMillis))
            return
        }
        if (!restrictionsApplied) {
            when (val result = applySafely(session.plan)) {
                EnforcementResult.Applied -> {
                    restrictionsApplied = true
                    watchLossesLocked()
                }
                is EnforcementResult.Failed -> {
                    endLocked(SessionEvent.Lost(result.reason, now.wallMillis, SessionTiming.elapsedMillis(session, now)))
                    return
                }
            }
        }
        if (SessionTiming.needsRebase(session, now)) {
            val rebased = SessionTiming.rebased(session, now)
            commit(
                when (current) {
                    is SessionState.RecoveryPending -> current.copy(session = rebased)
                    else -> SessionState.Active(rebased)
                },
            )
        }
    }

    private suspend fun startDueScheduleLocked() {
        if (!mutableState.value.allowsNewSession) return
        val now = time.now()
        val z = zone()
        val entries = try {
            scheduleRepository.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val (entry, instance) = entries
            .mapNotNull { e -> ScheduleMath.dueInstance(e, z, now.wallMillis)?.let { e to it } }
            .minByOrNull { it.second.startMillis } ?: return
        // Mark first: whatever happens next, this window is never started a second time.
        try {
            scheduleRepository.markWindowStarted(entry.id, instance.startMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val remaining = instance.endMillis - now.wallMillis
        if (remaining < DurationPolicy.MIN_MINUTES * MILLIS_PER_MINUTE) return
        val plan = SessionPlan(FocusMode.ScheduledFocus, emptySet(), entry.window.durationMinutes)
        startLocked(plan, SessionSource.Scheduled(entry.id, instance.startMillis), remaining)
    }

    // ---- Start --------------------------------------------------------------------------

    private suspend fun startLocked(plan: SessionPlan, source: SessionSource, durationMillis: Long): StartResult {
        val id = newId()
        val starting = SessionMachine.reduce(mutableState.value, SessionEvent.StartRequested(id, plan, source))
            ?: return StartResult.Rejected(StartCheck.SessionRunning)
        commit(starting)

        val capability = capabilitySafely(plan.mode)
        if (capability is Capability.Unavailable) return failStartLocked(capability.reason.toEndReason())

        val result = applySafely(plan)
        if (result is EnforcementResult.Failed) return failStartLocked(result.reason)

        val now = time.now()
        val session = RunningSession(
            id = id,
            plan = plan,
            source = source,
            startedAtWallMillis = now.wallMillis,
            endAtWallMillis = now.wallMillis + durationMillis,
            durationMillis = durationMillis,
            startedAtElapsedMillis = now.elapsedMillis,
            bootCount = now.bootCount,
            lastSeenWallMillis = now.wallMillis,
        )
        val active = SessionMachine.reduce(mutableState.value, SessionEvent.StartSucceeded(session))
            ?: return failStartLocked(EndReason.InternalError)
        try {
            // Active is only published once it is safely stored, so a restart can recover it.
            commit(active, required = true)
        } catch (_: IOException) {
            return failStartLocked(EndReason.InternalError)
        }
        restrictionsApplied = true
        watchLossesLocked()
        return StartResult.Started
    }

    private suspend fun failStartLocked(reason: EndReason): StartResult {
        releaseSafely()
        restrictionsApplied = false
        val failed = SessionMachine.reduce(mutableState.value, SessionEvent.StartFailed(reason, time.now().wallMillis))
        if (failed != null) {
            commit(failed)
            recordHistory(failed)
        }
        return StartResult.Failed(reason)
    }

    // ---- Checks -------------------------------------------------------------------------

    private suspend fun checkStartLocked(plan: SessionPlan): StartCheck {
        val problems = buildList {
            if (plan.mode == FocusMode.ScheduledFocus) add(DraftProblem.ModeMissing)
            if (plan.mode == FocusMode.SelectedApps && plan.packages.isEmpty()) add(DraftProblem.NoAppsSelected)
            when (DurationPolicy.check(plan.durationMinutes)) {
                DurationCheck.TooShort -> add(DraftProblem.DurationTooShort)
                DurationCheck.TooLong -> add(DraftProblem.DurationTooLong)
                DurationCheck.Valid -> Unit
            }
        }
        if (problems.isNotEmpty()) return StartCheck.Invalid(problems)
        if (!mutableState.value.allowsNewSession) return StartCheck.SessionRunning
        val capability = capabilitySafely(plan.mode)
        if (capability is Capability.Unavailable) return StartCheck.Unavailable(capability.reason)

        val entries = try {
            scheduleRepository.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return StartCheck.Unavailable(UnavailableReason.StorageError)
        }
        val now = time.now()
        val end = now.wallMillis + plan.durationMinutes * MILLIS_PER_MINUTE
        ScheduleMath.firstConflict(now.wallMillis, end, entries, zone())?.let {
            return StartCheck.ScheduleConflict(it.startMillis)
        }
        return StartCheck.Ok
    }

    private suspend fun checkScheduleLocked(window: ScheduleWindow): StartCheck {
        val problems = buildList {
            if (window.days.isEmpty()) add(DraftProblem.NoScheduleDays)
            when (DurationPolicy.check(window.durationMinutes)) {
                DurationCheck.TooShort -> add(DraftProblem.DurationTooShort)
                DurationCheck.TooLong -> add(DraftProblem.DurationTooLong)
                DurationCheck.Valid -> Unit
            }
        }
        if (problems.isNotEmpty()) return StartCheck.Invalid(problems)
        val capability = capabilitySafely(FocusMode.ScheduledFocus)
        if (capability is Capability.Unavailable) return StartCheck.Unavailable(capability.reason)

        val now = time.now()
        val z = zone()
        runningSession(mutableState.value)?.let { running ->
            val until = now.wallMillis + SessionTiming.remainingMillis(running, now)
            ScheduleMath.instances(window, z, now.wallMillis, until).firstOrNull()?.let {
                return StartCheck.ScheduleConflict(it.startMillis)
            }
        }
        val entries = try {
            scheduleRepository.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return StartCheck.Unavailable(UnavailableReason.StorageError)
        }
        entries.firstOrNull { it.enabled && ScheduleMath.overlaps(window, it.window) }?.let { other ->
            return StartCheck.ScheduleConflict(ScheduleMath.nextStart(other.window, z, now.wallMillis) ?: now.wallMillis)
        }
        return StartCheck.Ok
    }

    // ---- Ending, persistence, history ---------------------------------------------------

    /** Applies an ending event: releases restrictions, stores and publishes the result, records history. */
    private suspend fun endLocked(event: SessionEvent): Boolean {
        val next = SessionMachine.reduce(mutableState.value, event) ?: return false
        releaseSafely()
        restrictionsApplied = false
        // Never cancel the coroutine that is running this very call (the loss listener).
        if (lossJob !== currentCoroutineContext()[Job]) {
            lossJob?.cancel()
            lossJob = null
        }
        commit(next)
        recordHistory(next)
        return true
    }

    private suspend fun transitionLocked(event: SessionEvent): Boolean {
        val next = SessionMachine.reduce(mutableState.value, event) ?: return false
        commit(next)
        return true
    }

    /**
     * Stores [next] (except the in-memory-only states) and then publishes it. With
     * [required] a storage failure is thrown and nothing is published.
     */
    private suspend fun commit(next: SessionState, required: Boolean = false) {
        if (next !is SessionState.Restoring && next !is SessionState.Configuring) {
            try {
                store.save(next)
            } catch (e: IOException) {
                if (required) throw e
            }
        }
        mutableState.value = next
    }

    private suspend fun recordHistory(state: SessionState) {
        val finished = (state as? SessionState.Finished)?.result ?: return
        try {
            history.add(finished)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The result stays in the stored state and is added again on acknowledge().
        }
    }

    // ---- Enforcer calls, never allowed to throw into the state machine ------------------

    private suspend fun capabilitySafely(mode: FocusMode): Capability = try {
        enforcer.capability(mode)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SecurityException) {
        Capability.Unavailable(UnavailableReason.PermissionMissing)
    } catch (_: Exception) {
        Capability.Unavailable(UnavailableReason.PolicyRejected)
    }

    private suspend fun applySafely(plan: SessionPlan): EnforcementResult = try {
        enforcer.apply(plan)
    } catch (e: CancellationException) {
        throw e
    } catch (_: SecurityException) {
        EnforcementResult.Failed(EndReason.PermissionLost)
    } catch (_: Exception) {
        EnforcementResult.Failed(EndReason.PolicyFailure)
    }

    /** Best effort. A real enforcer must retry removal on its own (Phase 5), because a failure here cannot be reported further. */
    private suspend fun releaseSafely() {
        try {
            enforcer.release()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // ---- Wake-ups and timers ------------------------------------------------------------

    private fun watchLossesLocked() {
        val scope = timerScope ?: return
        if (lossJob?.isActive == true) return
        lossJob = scope.launch { enforcer.losses.collect { reason -> onEnforcementLost(reason) } }
    }

    /**
     * Arms one system wake-up (survives process death) and, if a scope was given, one
     * in-process timer (precise while the process lives) for the next moment that matters:
     * the end of the running session, or the next scheduled window.
     */
    private suspend fun armWakeLocked() {
        val now = time.now()
        val running = runningSession(mutableState.value)
        val delayMillis: Long? = if (running != null) {
            SessionTiming.remainingMillis(running, now)
        } else if (mutableState.value.allowsNewSession) {
            nextScheduleStart(now)?.let { it - now.wallMillis }
        } else {
            null
        }

        if (delayMillis == null) {
            waker.cancelReconcile()
        } else {
            waker.scheduleReconcile(maxOf(delayMillis, MIN_WAKE_MILLIS))
        }

        if (timerJob !== currentCoroutineContext()[Job]) {
            timerJob?.cancel()
            timerJob = null
        }
        val scope = timerScope
        if (scope != null && delayMillis != null) {
            timerJob = scope.launch {
                delay(maxOf(delayMillis, MIN_WAKE_MILLIS))
                reconcile()
            }
        }
    }

    private suspend fun nextScheduleStart(now: TimeSnapshot): Long? {
        val entries = try {
            scheduleRepository.getAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val z = zone()
        return entries.filter { it.enabled }.mapNotNull { ScheduleMath.nextStart(it.window, z, now.wallMillis) }.minOrNull()
    }

    private fun runningSession(state: SessionState): RunningSession? = when (state) {
        is SessionState.Active -> state.session
        is SessionState.RecoveryPending -> state.session
        else -> null
    }

    private fun UnavailableReason.toEndReason(): EndReason = when (this) {
        UnavailableReason.PermissionMissing -> EndReason.PermissionLost
        UnavailableReason.PolicyRejected -> EndReason.PolicyFailure
        UnavailableReason.StorageError -> EndReason.InternalError
        UnavailableReason.EngineMissing, UnavailableReason.DeviceUnsupported -> EndReason.DeviceUnsupported
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val MIN_WAKE_MILLIS = 1_000L
    }
}
