package com.focus.app.ui.duration

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.domain.DurationCheck
import com.focus.app.domain.DurationPolicy
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.format.endTimeLabel
import com.focus.app.ui.format.rememberNowMillis
import com.focus.app.ui.theme.spacing

@Composable
fun DurationScreen(
    durationMinutes: Int?,
    onChange: (Int?) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val valid = durationMinutes != null && DurationPolicy.check(durationMinutes) == DurationCheck.Valid
    val now by rememberNowMillis(60_000L)
    FocusScreen(
        title = stringResource(R.string.duration_title),
        onBack = onBack,
        actions = { PrimaryButton(stringResource(R.string.action_continue), onContinue, enabled = valid) },
    ) {
        DurationPicker(durationMinutes, onChange)
        if (valid && durationMinutes != null) {
            SectionLabel(stringResource(R.string.duration_end_heading))
            Text(
                endTimeLabel(DurationPolicy.endAtMillis(now, durationMinutes), now),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.duration_end_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            )
        }
    }
}
