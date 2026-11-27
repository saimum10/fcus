package com.focus.app

import android.app.Application
import com.focus.app.data.DataStoreAppSelectionRepository
import com.focus.app.data.DataStoreAuditLog
import com.focus.app.data.DataStoreOnboardingRepository
import com.focus.app.data.DataStoreRecoveryRepository
import com.focus.app.data.DataStoreRestrictionJournal
import com.focus.app.data.DataStoreSessionStore
import com.focus.app.data.room.FocusDatabase
import com.focus.app.data.room.RoomScheduleRepository
import com.focus.app.data.room.RoomSessionHistoryRepository
import com.focus.app.device.AndroidTimeSource
import com.focus.app.device.AndroidDeviceControl
import com.focus.app.device.PackageAppIconSource
import com.focus.app.device.PackageInstalledAppsSource
import com.focus.app.device.WorkManagerWakeScheduler
import com.focus.app.domain.MutableLockTaskSignal
import com.focus.app.domain.RecoveryController
import com.focus.app.domain.SessionController
import com.focus.app.domain.SessionControllerHolder
import com.focus.app.domain.SessionState
import com.focus.app.recovery.Pbkdf2PinHasher
import com.focus.app.recovery.RecoveryService
import com.focus.app.session.RestrictionCoordinator
import com.focus.app.session.SessionEngine
import com.focus.app.ui.FocusDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Composition root: the only place that picks concrete `data`/`device`/`session`
 * implementations. The UI receives `domain` interfaces and never imports the
 * implementations. Manual wiring on purpose: a handful of dependencies do not justify a
 * DI framework.
 */
class FocusApplication : Application(), SessionControllerHolder {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: FocusDatabase by lazy { FocusDatabase.create(this) }
    private val sessionHistory by lazy { RoomSessionHistoryRepository(database.historyDao()) }
    private val lockTaskSignal = MutableLockTaskSignal()
    private val installedApps by lazy { PackageInstalledAppsSource(this) }

    /** Applies and removes the real restrictions; also what the UI asks about device capabilities. */
    private val restrictions: RestrictionCoordinator by lazy {
        RestrictionCoordinator(
            device = AndroidDeviceControl(this, lockTaskSignal),
            journal = DataStoreRestrictionJournal(this),
            apps = installedApps,
            time = AndroidTimeSource(this),
        )
    }

    /** Shared with the WorkManager worker through [SessionControllerHolder]. */
    override val sessionController: SessionController by lazy {
        SessionEngine(
            store = DataStoreSessionStore(this),
            history = sessionHistory,
            scheduleRepository = RoomScheduleRepository(database.scheduleDao()),
            enforcer = restrictions,
            time = AndroidTimeSource(this),
            waker = WorkManagerWakeScheduler(this),
            timerScope = applicationScope,
        )
    }

    /** Trusted-person recovery. Talks to the session only through [SessionController]. */
    private val recoveryController: RecoveryController by lazy {
        RecoveryService(
            repository = DataStoreRecoveryRepository(this),
            hasher = Pbkdf2PinHasher(),
            audit = DataStoreAuditLog(this),
            sessions = sessionController,
            time = AndroidTimeSource(this),
        )
    }

    val dependencies: FocusDependencies by lazy {
        FocusDependencies(
            onboarding = DataStoreOnboardingRepository(this),
            sessions = sessionController,
            sessionHistory = sessionHistory,
            installedApps = installedApps,
            appIcons = PackageAppIconSource(this),
            appSelection = DataStoreAppSelectionRepository(this),
            management = restrictions,
            lockTask = lockTaskSignal,
            recovery = recoveryController,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Restore a stored session (process death, restart) as soon as the process exists.
        applicationScope.launch {
            // An uncaught error here would end the process on every launch (a crash loop, on an
            // app that must never leave the device unusable). The next foreground reconcile
            // retries, and the engine never shows a session as running that it could not confirm.
            try {
                sessionController.reconcile()
                // No session running after that: undo anything an earlier process left behind
                // (a crash, a restart, a release that failed). A running session re-applied its own.
                val state = sessionController.state.value
                if (state !is SessionState.Active && state !is SessionState.RecoveryPending) {
                    restrictions.releaseIfNotApplied()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        // A session can end while Shizuku is stopped, which leaves apps blocked and the journal open.
        // When Shizuku (or anything else that matters) comes back and no session is running, finish the cleanup.
        applicationScope.launch {
            try {
                restrictions.deviceChanges().collect {
                    val state = sessionController.state.value
                    if (state is SessionState.Idle || state is SessionState.Finished) {
                        try {
                            restrictions.releaseIfNotApplied()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
