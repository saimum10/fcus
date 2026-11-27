package com.focus.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.focus.app.domain.FocusMode
import com.focus.app.domain.ModeSupport
import com.focus.app.domain.RecoveryController
import com.focus.app.domain.RecoveryStatus
import com.focus.app.domain.SessionState
import com.focus.app.domain.allowsNewSession
import com.focus.app.ui.active.ActiveSessionScreen
import com.focus.app.ui.FocusDependencies
import com.focus.app.ui.apps.AppSelectionScreen
import com.focus.app.ui.apps.AppSelectionViewModel
import com.focus.app.ui.confirm.ConfirmScreen
import com.focus.app.ui.duration.DurationScreen
import com.focus.app.ui.emergency.EmergencyScreen
import com.focus.app.ui.guide.PermissionGuideScreen
import com.focus.app.ui.history.HistoryScreen
import com.focus.app.ui.home.HomeScreen
import com.focus.app.ui.mode.ModeScreen
import com.focus.app.ui.onboarding.OnboardingScreen
import com.focus.app.ui.recovery.AuditScreen
import com.focus.app.ui.recovery.RecoveryScreen
import com.focus.app.ui.recovery.TrustedPersonScreen
import com.focus.app.ui.schedule.ScheduleScreen
import com.focus.app.ui.settings.SettingsScreen
import com.focus.app.ui.stats.StatsScreen
import com.focus.app.ui.state.AppViewModel
import com.focus.app.ui.state.AuditViewModel
import com.focus.app.ui.state.ConfirmViewModel
import com.focus.app.ui.state.HistoryViewModel
import com.focus.app.ui.state.RecoveryViewModel
import com.focus.app.ui.state.StatsViewModel
import com.focus.app.ui.state.SessionDraftViewModel
import com.focus.app.ui.state.TrustedPersonViewModel

/**
 * Route names. New-session flow:
 * home -> mode -> (apps | schedule | duration) -> confirm.
 */
object FocusRoutes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MODE = "mode"
    const val APPS = "apps"
    const val DURATION = "duration"
    const val SCHEDULE = "schedule"
    const val CONFIRM = "confirm"
    const val ACTIVE = "active"
    const val SETTINGS = "settings"
    const val GUIDE = "guide"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val EMERGENCY = "emergency"
    const val RECOVERY = "recovery"
    const val TRUSTED = "trusted"
    const val AUDIT = "audit"
}

/**
 * The trusted-person status, read again every time the screen comes back to the foreground
 * (for example after setting someone up). Null until the first answer.
 */
@Composable
private fun rememberRecoveryStatus(recovery: RecoveryController): State<RecoveryStatus?> {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return produceState<RecoveryStatus?>(initialValue = null, tick) { value = recovery.status() }
}

/** Whether a session could be ended early right now: null while unknown. */
private fun RecoveryStatus?.canEndEarly(): Boolean? = when (this) {
    null -> null
    RecoveryStatus.Unreadable, RecoveryStatus.NotSetUp -> false
    is RecoveryStatus.SetUp -> hasPin
}

private fun routeAfterMode(mode: FocusMode?): String? = when (mode) {
    FocusMode.SelectedApps -> FocusRoutes.APPS
    FocusMode.ScheduledFocus -> FocusRoutes.SCHEDULE
    FocusMode.FullFocus, FocusMode.CallOnly -> FocusRoutes.DURATION
    null -> null
}

@Composable
fun FocusNavHost(
    appViewModel: AppViewModel,
    draftViewModel: SessionDraftViewModel,
    dependencies: FocusDependencies,
    startDestination: String,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val back: () -> Unit = { navController.popBackStack() }

    NavHost(navController = navController, startDestination = startDestination, modifier = modifier) {

        composable(FocusRoutes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    appViewModel.completeOnboarding()
                    navController.navigate(FocusRoutes.HOME) {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(FocusRoutes.HOME) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            // Coming back here from the setup flow ends the "configuring" state.
            LaunchedEffect(Unit) { appViewModel.cancelConfiguring() }
            HomeScreen(
                state = state,
                remainingMillis = appViewModel::remainingMillis,
                onNewSession = {
                    draftViewModel.reset()
                    appViewModel.beginConfiguring()
                    navController.navigate(FocusRoutes.MODE)
                },
                onViewSession = { navController.navigate(FocusRoutes.ACTIVE) },
                onDismiss = appViewModel::acknowledge,
                onSettings = { navController.navigate(FocusRoutes.SETTINGS) },
                onEmergency = { navController.navigate(FocusRoutes.EMERGENCY) },
            )
        }

        composable(FocusRoutes.MODE) {
            val draft by draftViewModel.draft.collectAsStateWithLifecycle()
            // What this device can really enforce, read from the device when the screen opens.
            val support = remember {
                val caps = dependencies.management.capabilities()
                FocusMode.entries.associateWith { ModeSupport.capability(it, caps) }
            }
            ModeScreen(
                selected = draft.mode,
                support = support,
                onSelect = draftViewModel::setMode,
                onContinue = { routeAfterMode(draft.mode)?.let { navController.navigate(it) } },
                onBack = back,
            )
        }

        composable(FocusRoutes.APPS) {
            // Scoped to this back-stack entry: the list is loaded and watched only while
            // this part of the flow exists.
            val appsViewModel: AppSelectionViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        AppSelectionViewModel(dependencies.installedApps, dependencies.appIcons, dependencies.appSelection)
                    }
                },
            )
            val state by appsViewModel.uiState.collectAsStateWithLifecycle()
            val query by appsViewModel.query.collectAsStateWithLifecycle()
            AppSelectionScreen(
                state = state,
                query = query,
                iconSource = dependencies.appIcons,
                onQueryChange = appsViewModel::setQuery,
                onToggle = appsViewModel::setSelected,
                onClear = appsViewModel::clearSelection,
                onRetry = appsViewModel::retry,
                onContinue = { navController.navigate(FocusRoutes.DURATION) },
                onBack = back,
            )
        }

        composable(FocusRoutes.DURATION) {
            val draft by draftViewModel.draft.collectAsStateWithLifecycle()
            DurationScreen(
                durationMinutes = draft.durationMinutes,
                onChange = draftViewModel::setDuration,
                onContinue = { navController.navigate(FocusRoutes.CONFIRM) },
                onBack = back,
            )
        }

        composable(FocusRoutes.SCHEDULE) {
            val draft by draftViewModel.draft.collectAsStateWithLifecycle()
            ScheduleScreen(
                draft = draft,
                onToggleDay = draftViewModel::toggleDay,
                onStartChange = draftViewModel::setScheduleStart,
                onDurationChange = draftViewModel::setDuration,
                onContinue = { navController.navigate(FocusRoutes.CONFIRM) },
                onBack = back,
            )
        }

        composable(FocusRoutes.CONFIRM) {
            val draft by draftViewModel.draft.collectAsStateWithLifecycle()
            val confirmViewModel: ConfirmViewModel = viewModel(
                factory = viewModelFactory { initializer { ConfirmViewModel(dependencies.sessions) } },
            )
            val ui by confirmViewModel.state.collectAsStateWithLifecycle()
            val support = remember(draft.mode) {
                draft.mode?.let { ModeSupport.capability(it, dependencies.management.capabilities()) }
            }
            val recoveryStatus by rememberRecoveryStatus(dependencies.recovery)
            // The engine answers whether this draft can start right now; ask again when it changes.
            LaunchedEffect(draft) { confirmViewModel.refresh(draft) }
            ConfirmScreen(
                draft = draft,
                ui = ui,
                support = support,
                recovery = recoveryStatus,
                onSetUpTrustedPerson = { navController.navigate(FocusRoutes.TRUSTED) },
                onStart = {
                    confirmViewModel.submit(draft) { navController.popBackStack(FocusRoutes.HOME, inclusive = false) }
                },
                onBack = back,
            )
        }

        composable(FocusRoutes.ACTIVE) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            val status by dependencies.management.status.collectAsStateWithLifecycle()
            val recoveryStatus by rememberRecoveryStatus(dependencies.recovery)
            when (val current = state) {
                is SessionState.Active -> ActiveSessionScreen(
                    session = current.session,
                    recoveryPending = false,
                    status = status,
                    remainingMillis = appViewModel::remainingMillis,
                    recoveryAvailable = recoveryStatus.canEndEarly(),
                    onEmergency = { navController.navigate(FocusRoutes.EMERGENCY) },
                    onEndWithTrustedPerson = { navController.navigate(FocusRoutes.RECOVERY) },
                    onBack = back,
                )
                is SessionState.RecoveryPending -> ActiveSessionScreen(
                    session = current.session,
                    recoveryPending = true,
                    status = status,
                    remainingMillis = appViewModel::remainingMillis,
                    recoveryAvailable = recoveryStatus.canEndEarly(),
                    onEmergency = { navController.navigate(FocusRoutes.EMERGENCY) },
                    onEndWithTrustedPerson = { navController.navigate(FocusRoutes.RECOVERY) },
                    onBack = back,
                )
                // The session ended (or never was): nothing to show here.
                else -> LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable(FocusRoutes.SETTINGS) {
            SettingsScreen(
                onOpenGuide = { navController.navigate(FocusRoutes.GUIDE) },
                onOpenIntro = { navController.navigate(FocusRoutes.ONBOARDING) },
                onOpenHistory = { navController.navigate(FocusRoutes.HISTORY) },
                onOpenStats = { navController.navigate(FocusRoutes.STATS) },
                onOpenEmergency = { navController.navigate(FocusRoutes.EMERGENCY) },
                onOpenTrusted = { navController.navigate(FocusRoutes.TRUSTED) },
                onOpenAudit = { navController.navigate(FocusRoutes.AUDIT) },
                onBack = back,
            )
        }

        composable(FocusRoutes.GUIDE) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            PermissionGuideScreen(
                management = dependencies.management,
                canRelinquish = state.allowsNewSession,
                onBack = back,
            )
        }

        composable(FocusRoutes.HISTORY) {
            val historyViewModel: HistoryViewModel = viewModel(
                factory = viewModelFactory { initializer { HistoryViewModel(dependencies.sessionHistory) } },
            )
            val historyState by historyViewModel.state.collectAsStateWithLifecycle()
            val clearFailed by historyViewModel.clearFailed.collectAsStateWithLifecycle()
            HistoryScreen(
                state = historyState,
                clearFailed = clearFailed,
                onClear = historyViewModel::clear,
                onRetry = historyViewModel::retry,
                onBack = back,
            )
        }

        composable(FocusRoutes.STATS) {
            // Scoped to this entry: the queries and the midnight timer exist only while it does
            // (and only while the screen is started, see StatsViewModel).
            val statsViewModel: StatsViewModel = viewModel(
                factory = viewModelFactory { initializer { StatsViewModel(dependencies.sessionHistory) } },
            )
            val statsState by statsViewModel.state.collectAsStateWithLifecycle()
            StatsScreen(state = statsState, onRetry = statsViewModel::retry, onBack = back)
        }

        composable(FocusRoutes.EMERGENCY) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            EmergencyScreen(
                sessionRunning = state is SessionState.Active || state is SessionState.RecoveryPending,
                onRecover = { navController.navigate(FocusRoutes.RECOVERY) },
                onBack = back,
            )
        }

        composable(FocusRoutes.RECOVERY) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            if (state is SessionState.Active || state is SessionState.RecoveryPending) {
                val recoveryViewModel: RecoveryViewModel = viewModel(
                    factory = viewModelFactory { initializer { RecoveryViewModel(dependencies.recovery) } },
                )
                val ui by recoveryViewModel.state.collectAsStateWithLifecycle()
                RecoveryScreen(
                    ui = ui,
                    onSubmit = { pin ->
                        recoveryViewModel.submit(pin) { navController.popBackStack(FocusRoutes.HOME, inclusive = false) }
                    },
                    onCancel = {
                        // Leaving without a PIN: the session simply carries on.
                        recoveryViewModel.cancel()
                        navController.popBackStack()
                    },
                    onEmergency = { navController.navigate(FocusRoutes.EMERGENCY) },
                )
            } else {
                // No running session (it ended, or never was): back to the dashboard, which shows the result.
                LaunchedEffect(Unit) { navController.popBackStack(FocusRoutes.HOME, inclusive = false) }
            }
        }

        composable(FocusRoutes.TRUSTED) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            val trustedViewModel: TrustedPersonViewModel = viewModel(
                factory = viewModelFactory { initializer { TrustedPersonViewModel(dependencies.recovery) } },
            )
            val ui by trustedViewModel.state.collectAsStateWithLifecycle()
            TrustedPersonScreen(
                ui = ui,
                sessionRunning = !state.allowsNewSession,
                onSetUp = trustedViewModel::setUp,
                onChange = trustedViewModel::change,
                onRemove = trustedViewModel::remove,
                onRequestRemoval = trustedViewModel::requestRemoval,
                onCancelRemoval = trustedViewModel::cancelRemoval,
                onCompleteRemoval = trustedViewModel::completeRemoval,
                onDismissMessage = trustedViewModel::dismissMessage,
                onBack = back,
            )
        }

        composable(FocusRoutes.AUDIT) {
            val state by appViewModel.sessionState.collectAsStateWithLifecycle()
            val auditViewModel: AuditViewModel = viewModel(
                factory = viewModelFactory { initializer { AuditViewModel(dependencies.recovery) } },
            )
            val events by auditViewModel.events.collectAsStateWithLifecycle()
            AuditScreen(
                events = events,
                sessionRunning = !state.allowsNewSession,
                onClear = auditViewModel::clear,
                onBack = back,
            )
        }
    }
}
