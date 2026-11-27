package com.focus.app.ui.active

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.domain.CapabilityLimit
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RestrictionStatus
import com.focus.app.domain.RunningSession
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.format.endTimeLabel
import com.focus.app.ui.format.formatRemaining
import com.focus.app.ui.format.rememberNowMillis
import com.focus.app.ui.theme.spacing
import com.focus.app.ui.titleRes

/**
 * Shows the running session. Reachable only while a session is Active or RecoveryPending.
 * There is deliberately no early-unlock button. Two entries are always visible: Emergency
 * (calling and information, no PIN) and, only if a trusted person with a usable PIN exists,
 * "End with trusted person". [recoveryAvailable] is null until it is known.
 */
@Composable
fun ActiveSessionScreen(
    session: RunningSession,
    recoveryPending: Boolean,
    status: RestrictionStatus,
    remainingMillis: (RunningSession) -> Long,
    recoveryAvailable: Boolean?,
    onEmergency: () -> Unit,
    onEndWithTrustedPerson: () -> Unit,
    onBack: () -> Unit,
) {
    val now by rememberNowMillis(1_000L)
    // The countdown reads the monotonic clock; `now` only drives the once-a-second refresh.
    val remaining = remainingMillis(session)
    FocusScreen(
        title = stringResource(R.string.active_title),
        onBack = onBack,
        actions = {
            PrimaryButton(stringResource(R.string.active_emergency), onEmergency)
            if (recoveryAvailable == true) {
                SecondaryButton(stringResource(R.string.active_end_with_trusted), onEndWithTrustedPerson)
            }
        },
    ) {
        Text(
            formatRemaining(remaining),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = MaterialTheme.spacing.s),
        )
        InfoRow(stringResource(R.string.confirm_ends), endTimeLabel(now + remaining, now))
        InfoRow(stringResource(R.string.confirm_mode), stringResource(session.plan.mode.titleRes()))
        if (session.plan.mode == FocusMode.SelectedApps) {
            val count = session.plan.packages.size
            InfoRow(
                stringResource(R.string.confirm_apps),
                pluralStringResource(R.plurals.apps_selected_count, count, count),
            )
        }
        if (!status.active) {
            // E.g. right after a restart, before the device has confirmed the restrictions again.
            NoteText(stringResource(R.string.active_status_checking))
        } else {
            if (status.suspendedCount > 0) {
                InfoRow(
                    stringResource(R.string.active_restricted),
                    pluralStringResource(R.plurals.apps_restricted_count, status.suspendedCount, status.suspendedCount),
                )
            }
            if (status.shizukuStopped) NoteText(stringResource(R.string.active_shizuku_stopped_note))
            if (status.suspendedCount > 0 && !status.uninstallBlocked) {
                NoteText(stringResource(R.string.active_uninstall_note))
            }
            if (status.notRestrictedCount > 0) {
                NoteText(pluralStringResource(R.plurals.apps_not_restricted_count, status.notRestrictedCount, status.notRestrictedCount))
            }
            if (status.notificationsSilenced) {
                InfoRow(stringResource(R.string.active_notifications), stringResource(R.string.active_notifications_silenced))
            }
            if (status.lockTaskRequested) NoteText(stringResource(R.string.active_lock_task_note))
            if (status.limit == CapabilityLimit.NotificationsOnly) NoteText(stringResource(R.string.active_limited_note))
        }
        if (recoveryPending) NoteText(stringResource(R.string.active_recovery_note))
        if (recoveryAvailable == false) NoteText(stringResource(R.string.active_no_trusted_note))
        NoteText(stringResource(R.string.active_note))
    }
}
