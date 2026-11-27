package com.focus.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.focus.app.ui.navigation.FocusNavHost
import com.focus.app.ui.navigation.FocusRoutes
import com.focus.app.ui.state.AppViewModel
import com.focus.app.ui.state.SessionDraftViewModel
import com.focus.app.ui.theme.FocusTheme

/** Root composable: theme, background, state holders, navigation. */
@Composable
fun FocusApp(dependencies: FocusDependencies) {
    FocusTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            val appViewModel: AppViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { AppViewModel(dependencies.onboarding, dependencies.sessions) }
                },
            )
            val draftViewModel: SessionDraftViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { SessionDraftViewModel(dependencies.appSelection) }
                },
            )
            val onboardingCompleted by appViewModel.onboardingCompleted.collectAsStateWithLifecycle()

            // Every time the app comes to the foreground: end an expired session, re-check
            // restrictions and start a due schedule. Cheap and safe to repeat.
            LifecycleResumeEffect(Unit) {
                appViewModel.reconcile()
                onPauseOrDispose { }
            }

            // Wait for the stored flag so returning users never see the intro flash by.
            // The start destination is fixed once the flag is known.
            onboardingCompleted?.let { completed ->
                val start = remember { if (completed) FocusRoutes.HOME else FocusRoutes.ONBOARDING }
                FocusNavHost(
                    appViewModel = appViewModel,
                    draftViewModel = draftViewModel,
                    dependencies = dependencies,
                    startDestination = start,
                )
            }
        }
    }
}
