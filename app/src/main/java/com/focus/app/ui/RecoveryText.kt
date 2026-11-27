package com.focus.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.domain.AuditType
import com.focus.app.domain.InputProblem
import com.focus.app.domain.RecoveryResult
import com.focus.app.ui.format.formatDuration

@StringRes
fun InputProblem.messageRes(): Int = when (this) {
    InputProblem.NameEmpty -> R.string.problem_name_empty
    InputProblem.NameTooLong -> R.string.problem_name_long
    InputProblem.PhoneInvalid -> R.string.problem_phone
    InputProblem.PinTooShort -> R.string.problem_pin_short
    InputProblem.PinTooLong -> R.string.problem_pin_long
    InputProblem.PinNotDigits -> R.string.problem_pin_digits
    InputProblem.PinTooSimple -> R.string.problem_pin_simple
}

@StringRes
fun AuditType.textRes(): Int = when (this) {
    AuditType.TrustedPersonSet -> R.string.audit_trusted_set
    AuditType.TrustedPersonChanged -> R.string.audit_trusted_changed
    AuditType.PinReplaced -> R.string.audit_pin_replaced
    AuditType.TrustedPersonRemoved -> R.string.audit_trusted_removed
    AuditType.RemovalRequested -> R.string.audit_removal_requested
    AuditType.RemovalCancelled -> R.string.audit_removal_cancelled
    AuditType.RecoveryRequested -> R.string.audit_recovery_requested
    AuditType.RecoveryCancelled -> R.string.audit_recovery_cancelled
    AuditType.RecoverySucceeded -> R.string.audit_recovery_succeeded
    AuditType.RecoveryFailed -> R.string.audit_recovery_failed
    AuditType.RecoveryLocked -> R.string.audit_recovery_locked
    AuditType.ConfigPinFailed -> R.string.audit_config_pin_failed
}

/** Whole minutes, rounded up, at least one: "Try again in 1 min" never reads "0 min". */
private fun ceilMinutes(millis: Long): Int = ((millis + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

/** The text for a result that is not [RecoveryResult.Done]; null for Done. */
@Composable
fun RecoveryResult.messageText(): String? = when (this) {
    RecoveryResult.Done -> null
    is RecoveryResult.InvalidInput -> stringResource(problem.messageRes())
    RecoveryResult.SessionRunning -> stringResource(R.string.result_session_running)
    RecoveryResult.NoSession -> stringResource(R.string.recovery_no_session)
    RecoveryResult.NotConfigured -> stringResource(R.string.result_not_configured)
    RecoveryResult.AlreadyConfigured -> stringResource(R.string.result_already_configured)
    is RecoveryResult.WrongPin ->
        pluralStringResource(R.plurals.recovery_tries_left, freeAttemptsLeft, freeAttemptsLeft)
    is RecoveryResult.Locked -> stringResource(R.string.recovery_locked, formatDuration(ceilMinutes(remainingMillis)))
    is RecoveryResult.Waiting -> stringResource(R.string.result_waiting, formatDuration(ceilMinutes(remainingMillis)))
    RecoveryResult.StorageError -> stringResource(R.string.recovery_storage_error)
}
