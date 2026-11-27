package com.focus.app.ui.guide

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.focus.app.R
import com.focus.app.domain.DeploymentLevel
import com.focus.app.domain.DeviceManagement
import com.focus.app.domain.ProtectionPolicy
import com.focus.app.domain.ShizukuState
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.Paragraphs
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Explains permissions and managed-device (Device Owner) setup, and shows what this device
 * really allows. The capability level is read from the device every time the screen is
 * shown again (for example after returning from system settings); nothing is assumed.
 */
@Composable
fun PermissionGuideScreen(
    management: DeviceManagement,
    canRelinquish: Boolean,
    onBack: () -> Unit,
) {
    var caps by remember { mutableStateOf(management.capabilities()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { caps = management.capabilities() }
    // While Shizuku is not yet usable, look again every second: it can start, or be allowed, while this screen is open.
    LaunchedEffect(caps.shizuku) {
        if (caps.shizuku != ShizukuState.Granted) {
            while (true) {
                delay(1_000)
                caps = management.capabilities()
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var releaseFailed by remember { mutableStateOf(false) }

    FocusScreen(title = stringResource(R.string.guide_title), onBack = onBack) {
        SectionLabel(stringResource(R.string.guide_status_heading))
        val owner = caps.level == DeploymentLevel.DeviceOwner
        val viaShizuku = caps.level == DeploymentLevel.Shizuku
        InfoRow(
            stringResource(R.string.guide_level_label),
            stringResource(
                when {
                    owner -> R.string.guide_level_owner
                    viaShizuku -> R.string.guide_level_shizuku
                    else -> R.string.guide_level_regular
                },
            ),
        )
        InfoRow(stringResource(R.string.guide_cap_apps), yesNo(caps.canSuspendApps))
        InfoRow(stringResource(R.string.guide_cap_lock_task), yesNo(caps.canUseLockTask))
        InfoRow(
            stringResource(R.string.guide_cap_notifications),
            stringResource(
                when {
                    !caps.notificationControlAvailable -> R.string.guide_value_no
                    caps.notificationControlGranted -> R.string.guide_value_allowed
                    else -> R.string.guide_value_needs_access
                },
            ),
        )
        if (!owner) {
            InfoRow(
                stringResource(R.string.guide_cap_shizuku),
                stringResource(
                    when (caps.shizuku) {
                        ShizukuState.Granted -> R.string.guide_shizuku_allowed
                        ShizukuState.NeedsPermission -> R.string.guide_shizuku_needs_permission
                        ShizukuState.NotRunning -> R.string.guide_shizuku_not_running
                    },
                ),
            )
        }
        Paragraphs(
            stringResource(
                when {
                    owner -> R.string.guide_owner_note
                    viaShizuku -> R.string.guide_shizuku_note
                    else -> R.string.guide_regular_note
                },
            ),
        )
        if (caps.notificationControlAvailable && !caps.notificationControlGranted) {
            NoteText(stringResource(R.string.guide_notification_note))
            SecondaryButton(stringResource(R.string.guide_notification_action), { openNotificationAccess(context) })
        }

        if (!owner) {
            Hairline()
            SectionLabel(stringResource(R.string.guide_shizuku_heading))
            Paragraphs(stringResource(R.string.guide_shizuku_body))
            when (caps.shizuku) {
                ShizukuState.NotRunning -> {
                    NoteText(stringResource(R.string.guide_shizuku_note_not_running))
                    SecondaryButton(stringResource(R.string.guide_shizuku_open_action), { openShizuku(context) })
                }
                ShizukuState.NeedsPermission -> {
                    SecondaryButton(
                        stringResource(R.string.guide_shizuku_action),
                        {
                            management.requestShizukuPermission()
                            caps = management.capabilities()
                        },
                    )
                    NoteText(stringResource(R.string.guide_shizuku_note_permission))
                }
                ShizukuState.Granted -> Unit
            }
        }

        if (!owner && caps.shizukuDetails.isNotEmpty()) {
            SectionLabel(stringResource(R.string.guide_shizuku_details_heading))
            NoteText(caps.shizukuDetails)
        }

        Hairline()
        SectionLabel(stringResource(R.string.guide_permissions_heading))
        Paragraphs(stringResource(R.string.guide_permissions_body))
        SectionLabel(stringResource(R.string.guide_managed_heading))
        Paragraphs(stringResource(R.string.guide_managed_body))
        if (!owner) {
            SectionLabel(stringResource(R.string.guide_setup_heading))
            Paragraphs(stringResource(R.string.guide_setup_body))
        } else {
            SectionLabel(stringResource(R.string.guide_release_heading))
            Paragraphs(stringResource(R.string.guide_release_body))
            if (canRelinquish) {
                SecondaryButton(
                    stringResource(R.string.guide_release_action),
                    {
                        scope.launch {
                            releaseFailed = !management.relinquishDeviceOwner()
                            caps = management.capabilities()
                        }
                    },
                )
            } else {
                NoteText(stringResource(R.string.guide_release_blocked))
            }
            if (releaseFailed) NoteText(stringResource(R.string.guide_release_failed))
        }
    }
}

@Composable
private fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.guide_value_yes else R.string.guide_value_no)

private fun openShizuku(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(ProtectionPolicy.SHIZUKU_PACKAGE) ?: return
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Shizuku is not installed or cannot be opened; the note above already says to install it.
    }
}

private fun openNotificationAccess(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // The device has no such screen; capability detection already reports it as not available.
    }
}
