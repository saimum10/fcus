package com.focus.app.ui.emergency

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.focus.app.R
import com.focus.app.domain.SystemActions
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.Paragraphs
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.theme.spacing

/**
 * Intents for emergency access. They only open the phone's own apps: fOCUS holds no call
 * permission, places no call itself, and records nothing about their use.
 */
object EmergencyIntents {
    /**
     * 112 is accepted as an emergency number by phones in most countries (GSM standard).
     * It only fills the dialer; the person presses call.
     */
    const val FALLBACK_NUMBER = "112"

    fun dialer(): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", FALLBACK_NUMBER, null))

    /** The system's emergency-information screen, or null if this device has no app for it. */
    fun information(packageManager: PackageManager): Intent? {
        val intent = Intent(SystemActions.EMERGENCY_ASSISTANCE)
        return if (intent.resolveActivity(packageManager) != null) intent else null
    }

    /** The dialer with [number] filled in (used for the optional "Call" button of the trusted person). */
    fun dial(number: String): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
}

/** True if the activity was started; false if this device has no app that can handle it. */
fun startSafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/**
 * The emergency entry. Reachable from the dashboard, the active session and settings,
 * whether or not a session is running. It needs no PIN, has no waiting time and is not
 * logged. It does not end the session: calling and the emergency information are never
 * blocked in the first place (phone, emergency and settings apps are never restricted).
 */
@Composable
fun EmergencyScreen(
    sessionRunning: Boolean,
    onRecover: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val spacing = MaterialTheme.spacing
    var failed by remember { mutableStateOf(false) }
    val infoIntent = remember(context) { EmergencyIntents.information(context.packageManager) }

    FocusScreen(title = stringResource(R.string.emergency_title), onBack = onBack) {
        Paragraphs(stringResource(R.string.emergency_intro))
        PrimaryButton(
            stringResource(R.string.emergency_open_dialer),
            onClick = { failed = !startSafely(context, EmergencyIntents.dialer()) },
        )
        NoteText(stringResource(R.string.emergency_dialer_note))
        if (infoIntent != null) {
            Spacer(Modifier.height(spacing.s))
            SecondaryButton(
                stringResource(R.string.emergency_info),
                onClick = { failed = !startSafely(context, infoIntent) },
            )
        }
        if (failed) {
            Text(
                stringResource(R.string.emergency_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = spacing.s),
            )
        }
        if (sessionRunning) {
            SectionLabel(stringResource(R.string.emergency_session_label))
            Paragraphs(stringResource(R.string.emergency_session_body))
            SecondaryButton(stringResource(R.string.emergency_open_recovery), onRecover)
        }
        NoteText(stringResource(R.string.emergency_privacy_note))
    }
}
