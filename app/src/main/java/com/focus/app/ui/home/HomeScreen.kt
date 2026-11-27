package com.focus.app.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.focus.app.R
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.RunningSession
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.SessionState
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.TextAction
import com.focus.app.ui.format.endTimeLabel
import com.focus.app.ui.format.formatDurationMillis
import com.focus.app.ui.format.formatRemaining
import com.focus.app.ui.format.rememberNowMillis
import com.focus.app.ui.textRes
import com.focus.app.ui.theme.spacing
import com.focus.app.ui.titleRes

/** Dashboard. Shows only what is true: loading, idle, running, or how the last session ended. */
@Composable
fun HomeScreen(
    state: SessionState,
    remainingMillis: (RunningSession) -> Long,
    onNewSession: () -> Unit,
    onViewSession: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onEmergency: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusScreen(
        title = stringResource(R.string.app_name),
        onBack = null,
        modifier = modifier,
        actions = {
            when (state) {
                SessionState.Restoring, is SessionState.Starting -> Unit
                SessionState.Idle, SessionState.Configuring ->
                    PrimaryButton(stringResource(R.string.home_new_session), onNewSession)
                is SessionState.Active, is SessionState.RecoveryPending ->
                    PrimaryButton(stringResource(R.string.home_view_session), onViewSession)
                is SessionState.Finished -> {
                    PrimaryButton(stringResource(R.string.home_new_session), onNewSession)
                    TextAction(stringResource(R.string.home_dismiss), onDismiss)
                }
            }
            SecondaryButton(stringResource(R.string.home_emergency), onEmergency)
            SecondaryButton(stringResource(R.string.settings_title), onSettings)
        },
    ) {
        when (state) {
            SessionState.Restoring -> NoteText(stringResource(R.string.home_loading))
            SessionState.Idle, SessionState.Configuring -> {
                Headline(stringResource(R.string.home_status_idle))
                NoteText(stringResource(R.string.home_idle_note))
            }
            is SessionState.Starting -> Headline(stringResource(R.string.home_status_starting))
            is SessionState.Active -> RunningContent(
                heading = stringResource(R.string.home_status_active),
                session = state.session,
                remainingMillis = remainingMillis,
            )
            is SessionState.RecoveryPending -> {
                RunningContent(
                    heading = stringResource(R.string.home_status_recovery),
                    session = state.session,
                    remainingMillis = remainingMillis,
                )
                NoteText(stringResource(R.string.home_recovery_note))
            }
            is SessionState.Finished -> {
                val headline = when (state.result.outcome) {
                    SessionOutcome.Completed -> R.string.home_status_completed
                    SessionOutcome.Interrupted -> R.string.home_status_interrupted
                    SessionOutcome.Failed -> R.string.home_status_failed
                }
                Headline(stringResource(headline))
                FinishedDetails(state.result)
            }
        }
    }
}

@Composable
private fun Headline(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun RunningContent(
    heading: String,
    session: RunningSession,
    remainingMillis: (RunningSession) -> Long,
) {
    val spacing = MaterialTheme.spacing
    val now by rememberNowMillis(1_000L)
    // Reading `now` makes this recompute every second; the remaining time itself comes
    // from the monotonic clock, so changing the wall clock cannot change the countdown.
    val remaining = remainingMillis(session)
    Headline(heading)
    Spacer(Modifier.height(spacing.m))
    Text(
        formatRemaining(remaining),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        endTimeLabel(now + remaining, now),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.xs),
    )
}

@Composable
internal fun FinishedDetails(result: FinishedSession) {
    InfoRow(stringResource(R.string.result_mode), stringResource(result.mode.titleRes()))
    if (result.outcome != SessionOutcome.Failed) {
        InfoRow(stringResource(R.string.result_active), formatDurationMillis(result.activeMillis))
    }
    result.reason?.let { InfoRow(stringResource(R.string.result_reason), stringResource(it.textRes())) }
}
