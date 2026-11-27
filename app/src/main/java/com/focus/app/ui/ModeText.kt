package com.focus.app.ui

import androidx.annotation.StringRes
import com.focus.app.R
import com.focus.app.domain.DraftProblem
import com.focus.app.domain.EndReason
import com.focus.app.domain.FocusMode
import com.focus.app.domain.ProtectionReason
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.UnavailableReason

@StringRes
fun FocusMode.titleRes(): Int = when (this) {
    FocusMode.SelectedApps -> R.string.mode_selected_apps
    FocusMode.FullFocus -> R.string.mode_full_focus
    FocusMode.CallOnly -> R.string.mode_call_only
    FocusMode.ScheduledFocus -> R.string.mode_scheduled
}

@StringRes
fun FocusMode.descriptionRes(): Int = when (this) {
    FocusMode.SelectedApps -> R.string.mode_selected_apps_desc
    FocusMode.FullFocus -> R.string.mode_full_focus_desc
    FocusMode.CallOnly -> R.string.mode_call_only_desc
    FocusMode.ScheduledFocus -> R.string.mode_scheduled_desc
}

@StringRes
fun DraftProblem.messageRes(): Int = when (this) {
    DraftProblem.ModeMissing -> R.string.problem_mode_missing
    DraftProblem.NoAppsSelected -> R.string.problem_no_apps
    DraftProblem.DurationMissing -> R.string.problem_duration_missing
    DraftProblem.DurationTooShort -> R.string.problem_duration_short
    DraftProblem.DurationTooLong -> R.string.problem_duration_long
    DraftProblem.NoScheduleDays -> R.string.problem_no_days
}

@StringRes
fun ProtectionReason.textRes(): Int = when (this) {
    ProtectionReason.ThisApp -> R.string.protection_this_app
    ProtectionReason.Launcher -> R.string.protection_launcher
    ProtectionReason.Phone -> R.string.protection_phone
    ProtectionReason.Emergency -> R.string.protection_emergency
    ProtectionReason.Settings -> R.string.protection_settings
    ProtectionReason.SystemCore -> R.string.protection_system
}

@StringRes
fun EndReason.textRes(): Int = when (this) {
    EndReason.PermissionLost -> R.string.end_reason_permission
    EndReason.PolicyFailure -> R.string.end_reason_policy
    EndReason.DeviceUnsupported -> R.string.end_reason_unsupported
    EndReason.EnforcementLost -> R.string.end_reason_enforcement_lost
    EndReason.RecoveryUsed -> R.string.end_reason_recovery
    EndReason.InternalError -> R.string.end_reason_internal
}

@StringRes
fun UnavailableReason.textRes(): Int = when (this) {
    UnavailableReason.EngineMissing -> R.string.unavail_engine
    UnavailableReason.DeviceUnsupported -> R.string.unavail_device
    UnavailableReason.PermissionMissing -> R.string.unavail_permission
    UnavailableReason.PolicyRejected -> R.string.unavail_policy
    UnavailableReason.StorageError -> R.string.unavail_storage
}

@StringRes
fun SessionOutcome.textRes(): Int = when (this) {
    SessionOutcome.Completed -> R.string.outcome_completed
    SessionOutcome.Interrupted -> R.string.outcome_interrupted
    SessionOutcome.Failed -> R.string.outcome_failed
}
