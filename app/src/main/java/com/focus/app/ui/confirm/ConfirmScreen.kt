package com.focus.app.ui.confirm

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.domain.Capability
import com.focus.app.domain.DurationCheck
import com.focus.app.domain.DurationPolicy
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RecoveryStatus
import com.focus.app.domain.SessionDraft
import com.focus.app.domain.StartCheck
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.components.TextAction
import com.focus.app.ui.format.endTimeLabel
import com.focus.app.ui.format.formatDateTime
import com.focus.app.ui.format.formatDuration
import com.focus.app.ui.format.formatMinuteOfDay
import com.focus.app.ui.format.rememberNowMillis
import com.focus.app.ui.format.weekdayShort
import com.focus.app.ui.messageRes
import com.focus.app.ui.state.ConfirmUiState
import com.focus.app.ui.textRes
import com.focus.app.ui.theme.spacing
import com.focus.app.ui.titleRes

/**
 * Summary before starting, with the exact end time. The button is enabled only when the
 * session engine itself says the draft can start (or be saved as a schedule): valid,
 * no other session, no schedule conflict, and the mode can really be enforced. Whatever
 * stops it is stated here instead of offering a button that does nothing.
 */
@Composable
fun ConfirmScreen(
    draft: SessionDraft,
    ui: ConfirmUiState,
    support: Capability?,
    recovery: RecoveryStatus?,
    onStart: () -> Unit,
    onSetUpTrustedPerson: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val now by rememberNowMillis(60_000L)
    val problems = draft.problems()
    val canStart = problems.isEmpty() && ui.check == StartCheck.Ok && !ui.busy
    val isSchedule = draft.mode == FocusMode.ScheduledFocus

    FocusScreen(
        title = stringResource(R.string.confirm_title),
        onBack = onBack,
        actions = {
            PrimaryButton(
                stringResource(if (isSchedule) R.string.confirm_save_schedule else R.string.confirm_start),
                onStart,
                enabled = canStart,
            )
        },
    ) {
        val mode = draft.mode
        if (mode != null) InfoRow(stringResource(R.string.confirm_mode), stringResource(mode.titleRes()))
        if (mode == FocusMode.SelectedApps) {
            InfoRow(
                stringResource(R.string.confirm_apps),
                pluralStringResource(R.plurals.apps_selected_count, draft.selectedPackages.size, draft.selectedPackages.size),
            )
        }
        val minutes = draft.durationMinutes
        if (minutes != null) InfoRow(stringResource(R.string.confirm_duration), formatDuration(minutes))

        val window = draft.scheduleWindow()
        if (window != null) {
            InfoRow(
                stringResource(R.string.confirm_schedule),
                window.days.sorted().joinToString(", ") { weekdayShort(it) } + "\n" +
                    stringResource(
                        if (window.endsNextDay) R.string.confirm_window_next_day else R.string.confirm_window,
                        formatMinuteOfDay(window.startMinuteOfDay),
                        formatMinuteOfDay(window.endMinuteOfDay),
                    ),
            )
        } else if (minutes != null && DurationPolicy.check(minutes) == DurationCheck.Valid) {
            InfoRow(
                stringResource(R.string.confirm_ends),
                endTimeLabel(DurationPolicy.endAtMillis(now, minutes), now),
            )
            NoteText(stringResource(R.string.confirm_end_note))
        }

        Hairline()
        SectionLabel(stringResource(R.string.confirm_status_heading))
        if (problems.isNotEmpty()) {
            problems.forEach { problem ->
                Text(
                    stringResource(problem.messageRes()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = spacing.xs),
                )
            }
        }
        ui.failure?.let { reason ->
            Text(
                stringResource(R.string.confirm_start_failed, stringResource(reason.textRes())),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
        }
        when (val check = ui.check) {
            null, StartCheck.Ok, is StartCheck.Invalid -> Unit
            StartCheck.SessionRunning -> NoteText(stringResource(R.string.check_running))
            is StartCheck.Unavailable -> NoteText(stringResource(check.reason.textRes()))
            is StartCheck.ScheduleConflict -> Text(
                stringResource(R.string.check_conflict, formatDateTime(check.atMillis)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
        }
        when (support) {
            Capability.Available -> NoteText(stringResource(R.string.confirm_note_suspend))
            is Capability.Limited -> NoteText(stringResource(R.string.confirm_note_limited))
            is Capability.Unavailable, null -> Unit
        }
        NoteText(stringResource(R.string.confirm_limits))
        // Said BEFORE starting: whether anyone can end this session early.
        when (recovery) {
            null -> Unit
            RecoveryStatus.Unreadable -> NoteText(stringResource(R.string.confirm_recovery_unreadable))
            RecoveryStatus.NotSetUp -> {
                NoteText(stringResource(R.string.confirm_recovery_none))
                TextAction(stringResource(R.string.trusted_setup_button), onSetUpTrustedPerson)
            }
            is RecoveryStatus.SetUp -> if (recovery.hasPin) {
                NoteText(stringResource(R.string.confirm_recovery_ready, recovery.person.name))
            } else {
                NoteText(stringResource(R.string.confirm_recovery_none))
                TextAction(stringResource(R.string.trusted_new_pin), onSetUpTrustedPerson)
            }
        }
    }
}
