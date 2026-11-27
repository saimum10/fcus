package com.focus.app.ui.recovery

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.focus.app.R
import com.focus.app.domain.PinPolicy
import com.focus.app.domain.RecoveryStatus
import com.focus.app.domain.TrustedPerson
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.Paragraphs
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.components.TextAction
import com.focus.app.ui.format.formatDuration
import com.focus.app.ui.messageText
import com.focus.app.ui.state.TrustedUiState
import com.focus.app.ui.theme.spacing

private enum class Page { Overview, Setup, Change, Remove }

/**
 * Sets up, changes and removes the trusted person. The whole feature is explained here
 * BEFORE anything is activated: who the person is, that fOCUS never contacts them, how the
 * PIN works (chosen by them, hashed, used once), what recovery can and cannot do, and the
 * waiting period when the PIN is forgotten. While a session runs nothing here can be changed.
 */
@Composable
fun TrustedPersonScreen(
    ui: TrustedUiState,
    sessionRunning: Boolean,
    onSetUp: (TrustedPerson, String, () -> Unit) -> Unit,
    onChange: (TrustedPerson, String?, String, () -> Unit) -> Unit,
    onRemove: (String, () -> Unit) -> Unit,
    onRequestRemoval: () -> Unit,
    onCancelRemoval: () -> Unit,
    onCompleteRemoval: () -> Unit,
    onDismissMessage: () -> Unit,
    onBack: () -> Unit,
) {
    var page by remember { mutableStateOf(Page.Overview) }
    val toOverview = {
        onDismissMessage()
        page = Page.Overview
    }
    val status = ui.status
    val current = status as? RecoveryStatus.SetUp

    when (page) {
        Page.Overview -> OverviewPage(
            ui = ui,
            sessionRunning = sessionRunning,
            onSetup = { onDismissMessage(); page = Page.Setup },
            onChange = { onDismissMessage(); page = Page.Change },
            onRemove = { onDismissMessage(); page = Page.Remove },
            onRemoveWithoutPin = { onRemove("") { } },
            onRequestRemoval = onRequestRemoval,
            onCancelRemoval = onCancelRemoval,
            onCompleteRemoval = onCompleteRemoval,
            onBack = onBack,
        )
        Page.Setup -> PersonForm(
            page = page,
            initial = current?.person,
            ui = ui,
            onSubmit = { person, newPin, _ -> onSetUp(person, newPin.orEmpty(), toOverview) },
            onCancel = toOverview,
        )
        Page.Change -> PersonForm(
            page = page,
            initial = current?.person,
            ui = ui,
            onSubmit = { person, newPin, currentPin -> onChange(person, newPin, currentPin, toOverview) },
            onCancel = toOverview,
        )
        Page.Remove -> PersonForm(
            page = page,
            initial = current?.person,
            ui = ui,
            onSubmit = { _, _, currentPin -> onRemove(currentPin, toOverview) },
            onCancel = toOverview,
        )
    }
}

@Composable
private fun OverviewPage(
    ui: TrustedUiState,
    sessionRunning: Boolean,
    onSetup: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
    onRemoveWithoutPin: () -> Unit,
    onRequestRemoval: () -> Unit,
    onCancelRemoval: () -> Unit,
    onCompleteRemoval: () -> Unit,
    onBack: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    FocusScreen(title = stringResource(R.string.trusted_title), onBack = onBack) {
        when (val status = ui.status) {
            null -> NoteText(stringResource(R.string.home_loading))
            RecoveryStatus.Unreadable -> NoteText(stringResource(R.string.trusted_unreadable))
            RecoveryStatus.NotSetUp -> {
                Paragraphs(stringResource(R.string.trusted_explain_body))
                if (sessionRunning) {
                    NoteText(stringResource(R.string.trusted_session_running))
                } else {
                    PrimaryButton(stringResource(R.string.trusted_setup_button), onSetup, enabled = !ui.busy)
                }
            }
            is RecoveryStatus.SetUp -> {
                InfoRow(stringResource(R.string.trusted_row_name), status.person.name)
                status.person.phone?.let { InfoRow(stringResource(R.string.trusted_row_phone), it) }
                InfoRow(
                    stringResource(R.string.trusted_row_pin),
                    stringResource(if (status.hasPin) R.string.trusted_pin_set else R.string.trusted_pin_used),
                )
                Hairline()
                if (sessionRunning) {
                    NoteText(stringResource(R.string.trusted_session_running))
                } else if (status.hasPin) {
                    Spacer(Modifier.height(spacing.m))
                    SecondaryButton(stringResource(R.string.trusted_change), onChange)
                    Spacer(Modifier.height(spacing.s))
                    SecondaryButton(stringResource(R.string.trusted_remove), onRemove)
                    SectionLabel(stringResource(R.string.trusted_forgot))
                    Paragraphs(stringResource(R.string.trusted_forgot_body), secondary = true)
                    val remaining = status.removalRemainingMillis
                    when {
                        remaining == null ->
                            SecondaryButton(stringResource(R.string.trusted_request_removal), onRequestRemoval)
                        remaining > 0L -> {
                            NoteText(
                                stringResource(
                                    R.string.trusted_removal_pending,
                                    formatDuration(((remaining + 59_999L) / 60_000L).toInt().coerceAtLeast(1)),
                                ),
                            )
                            TextAction(stringResource(R.string.trusted_cancel_removal), onCancelRemoval)
                        }
                        else -> {
                            NoteText(stringResource(R.string.trusted_removal_ready))
                            PrimaryButton(stringResource(R.string.trusted_complete_removal), onCompleteRemoval, enabled = !ui.busy)
                            TextAction(stringResource(R.string.trusted_cancel_removal), onCancelRemoval)
                        }
                    }
                } else {
                    // The PIN was used up: a new one is needed; nothing to guess or wait for.
                    NoteText(stringResource(R.string.trusted_pin_used_note))
                    PrimaryButton(stringResource(R.string.trusted_new_pin), onSetup, enabled = !ui.busy)
                    Spacer(Modifier.height(spacing.s))
                    SecondaryButton(stringResource(R.string.trusted_remove), onRemoveWithoutPin)
                }
            }
        }
        ui.message?.messageText()?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.s),
            )
        }
        NoteText(stringResource(R.string.trusted_privacy_note))
    }
}

/**
 * One form for the three actions that need typing. [onSubmit] gets the person, the new PIN
 * (null when the PIN is not being changed) and the current PIN (empty when not asked for).
 * PIN fields are masked, never saved across rotation, and the window is protected from
 * screenshots while the form is open.
 */
@Composable
private fun PersonForm(
    page: Page,
    initial: TrustedPerson?,
    ui: TrustedUiState,
    onSubmit: (TrustedPerson, String?, String) -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var repeatPin by remember { mutableStateOf("") }
    SecureWindow()

    val asksDetails = page == Page.Setup || page == Page.Change
    val asksCurrent = page == Page.Change || page == Page.Remove
    val needsNewPin = page == Page.Setup
    val mismatch = newPin.isNotEmpty() && newPin != repeatPin
    val ready = !ui.busy && !mismatch &&
        (!asksDetails || name.isNotBlank()) &&
        (!asksCurrent || currentPin.isNotEmpty()) &&
        (!needsNewPin || newPin.length >= PinPolicy.MIN_LENGTH)

    val title = when (page) {
        Page.Setup -> R.string.trusted_setup_button
        Page.Change -> R.string.trusted_change
        else -> R.string.trusted_remove
    }

    FocusScreen(
        title = stringResource(title),
        onBack = onCancel,
        actions = {
            PrimaryButton(
                stringResource(if (page == Page.Remove) R.string.trusted_remove else R.string.trusted_save),
                onClick = {
                    onSubmit(
                        TrustedPerson(name, phone.ifBlank { null }),
                        newPin.ifEmpty { null },
                        currentPin,
                    )
                },
                enabled = ready,
            )
            TextAction(stringResource(R.string.trusted_cancel), onCancel)
        },
    ) {
        if (page == Page.Remove) Paragraphs(stringResource(R.string.trusted_remove_body))
        if (asksDetails) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_INPUT) },
                singleLine = true,
                label = { Text(stringResource(R.string.trusted_name_label)) },
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.s))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.take(MAX_PHONE_INPUT) },
                singleLine = true,
                label = { Text(stringResource(R.string.trusted_phone_label)) },
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            NoteText(stringResource(R.string.trusted_phone_help))
        }
        if (asksCurrent) {
            PinField(
                value = currentPin,
                onValueChange = { currentPin = it },
                label = stringResource(R.string.trusted_current_pin_label),
            )
            Spacer(Modifier.height(spacing.s))
        }
        if (page == Page.Setup || page == Page.Change) {
            SectionLabel(stringResource(R.string.trusted_pin_section))
            Paragraphs(stringResource(R.string.trusted_hand_over))
            PinField(
                value = newPin,
                onValueChange = { newPin = it },
                label = stringResource(if (page == Page.Change) R.string.trusted_new_pin_optional else R.string.trusted_pin_label),
            )
            Spacer(Modifier.height(spacing.s))
            PinField(
                value = repeatPin,
                onValueChange = { repeatPin = it },
                label = stringResource(R.string.trusted_pin_confirm_label),
                imeAction = ImeAction.Done,
            )
            if (mismatch) {
                Text(
                    stringResource(R.string.trusted_pin_mismatch),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = spacing.s),
                )
            }
        }
        ui.message?.messageText()?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.s),
            )
        }
    }
}

private const val MAX_NAME_INPUT = 60
private const val MAX_PHONE_INPUT = 30
