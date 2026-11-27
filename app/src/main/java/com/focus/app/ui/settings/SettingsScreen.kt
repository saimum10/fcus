package com.focus.app.ui.settings

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NavRow
import com.focus.app.ui.components.Paragraphs
import com.focus.app.ui.components.SectionLabel

/** Settings, privacy and about in one screen. No toggles that would do nothing. */
@Composable
fun SettingsScreen(
    onOpenGuide: () -> Unit,
    onOpenIntro: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenEmergency: () -> Unit,
    onOpenTrusted: () -> Unit,
    onOpenAudit: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        info?.versionName ?: "—"
    }
    FocusScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
        SectionLabel(stringResource(R.string.settings_activity))
        NavRow(stringResource(R.string.history_title), stringResource(R.string.settings_history_sub), onOpenHistory)
        Hairline()
        NavRow(stringResource(R.string.stats_title), stringResource(R.string.settings_stats_sub), onOpenStats)

        SectionLabel(stringResource(R.string.settings_safety))
        NavRow(stringResource(R.string.emergency_title), stringResource(R.string.settings_emergency_sub), onOpenEmergency)
        Hairline()
        NavRow(stringResource(R.string.trusted_title), stringResource(R.string.settings_trusted_sub), onOpenTrusted)
        Hairline()
        NavRow(stringResource(R.string.audit_title), stringResource(R.string.settings_audit_sub), onOpenAudit)

        SectionLabel(stringResource(R.string.settings_setup))
        NavRow(stringResource(R.string.guide_title), stringResource(R.string.settings_guide_sub), onOpenGuide)
        Hairline()
        NavRow(stringResource(R.string.settings_intro), stringResource(R.string.settings_intro_sub), onOpenIntro)

        SectionLabel(stringResource(R.string.settings_appearance))
        Paragraphs(stringResource(R.string.settings_appearance_body), secondary = true)

        SectionLabel(stringResource(R.string.settings_privacy))
        Paragraphs(stringResource(R.string.settings_privacy_body))

        SectionLabel(stringResource(R.string.settings_about))
        InfoRow(stringResource(R.string.settings_version), versionName)
        InfoRow(stringResource(R.string.settings_package), context.packageName)
        Paragraphs(stringResource(R.string.settings_about_body), secondary = true)
    }
}
