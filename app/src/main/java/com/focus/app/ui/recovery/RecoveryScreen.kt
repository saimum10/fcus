package com.focus.app.ui.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.focus.app.R
import com.focus.app.domain.PinPolicy
import com.focus.app.domain.RecoveryResult
import com.focus.app.domain.RecoveryStatus
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.Paragraphs
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.components.TextAction
import com.focus.app.ui.emergency.EmergencyIntents
import com.focus.app.ui.emergency.startSafely
import com.focus.app.ui.messageText
import com.focus.app.ui.state.RecoveryUiState
import com.focus.app.ui.theme.spacing

/**
 * End the running session early with the trusted person's PIN. Only reachable while a
 * session runs. There is no other way to end it early from here: a wrong or missing PIN
 * changes nothing, and the session keeps running (the restrictions never stopped).
 *
 * Leaving the screen without success (back button, "Keep the session running") cancels
 * recovery. Emergency calling stays one tap away.
 */
@Composable
fun RecoveryScreen(
    ui: RecoveryUiState,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onEmergency: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    SecureWindow()
    BackHandler(onBack = onCancel)

    val status = ui.status
    val entry = ui.entry
    val setUp = status as? RecoveryStatus.SetUp
    val canEnter = entry == RecoveryResult.Done && setUp != null && setUp.hasPin
    val locked = (ui.failure as? RecoveryResult.Locked) != null || (setUp?.lockRemainingMillis ?: 0L) > 0L

    FocusScreen(
        title = stringResource(R.string.recovery_title),
        onBack = onCancel,
        actions = {
            if (canEnter) {
                PrimaryButton(
                    stringResource(R.string.recovery_end_button),
                    onClick = { onSubmit(pin) },
                    enabled = !ui.busy && !locked && pin.length >= PinPolicy.MIN_LENGTH,
                )
            }
            SecondaryButton(stringResource(R.string.emergency_title), onEmergency)
            TextAction(stringResource(R.string.recovery_cancel), onCancel)
        },
    ) {
        when {
            entry == null || status == null -> NoteText(stringResource(R.string.recovery_checking))
            status is RecoveryStatus.Unreadable -> NoteText(stringResource(R.string.recovery_unreadable))
            entry is RecoveryResult.NotConfigured -> NoteText(stringResource(R.string.recovery_not_set_up))
            entry != RecoveryResult.Done -> NoteText(entry.messageText().orEmpty())
            setUp == null -> NoteText(stringResource(R.string.recovery_not_set_up))
            else -> {
                Paragraphs(stringResource(R.string.recovery_intro, setUp.person.name))
                setUp.person.phone?.let { phone ->
                    SecondaryButton(
                        stringResource(R.string.recovery_call, setUp.person.name),
                        onClick = { startSafely(context, EmergencyIntents.dial(phone)) },
                    )
                    Spacer(Modifier.height(spacing.s))
                }
                PinField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.recovery_pin_label),
                    imeAction = ImeAction.Done,
                    onDone = { if (!ui.busy && !locked && pin.length >= PinPolicy.MIN_LENGTH) onSubmit(pin) },
                )
                if (ui.busy) NoteText(stringResource(R.string.recovery_checking))
                val failure = ui.failure
                if (failure != null) {
                    Text(
                        failure.messageText().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.s),
                    )
                } else if (setUp.lockRemainingMillis > 0L) {
                    Text(
                        RecoveryResult.Locked(setUp.lockRemainingMillis).messageText().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = spacing.s),
                    )
                }
                SectionLabel(stringResource(R.string.recovery_what_label))
                Paragraphs(stringResource(R.string.recovery_what_body), secondary = true)
            }
        }
    }
}
