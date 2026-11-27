package com.focus.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Paragraphs
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.TextAction

private class Step(@StringRes val title: Int, @StringRes val body: Int)

private val steps = listOf(
    Step(R.string.onboarding_1_title, R.string.onboarding_1_body),
    Step(R.string.onboarding_2_title, R.string.onboarding_2_body),
    Step(R.string.onboarding_3_title, R.string.onboarding_3_body),
)

/** Three short steps: purpose, limitations, privacy. The limitations step cannot be skipped. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = index > 0) { index -= 1 }
    val step = steps[index]
    val last = index == steps.lastIndex

    FocusScreen(
        title = stringResource(step.title),
        onBack = null,
        actions = {
            PrimaryButton(
                text = stringResource(if (last) R.string.onboarding_done else R.string.action_next),
                onClick = { if (last) onFinish() else index += 1 },
            )
            if (index > 0) TextAction(stringResource(R.string.action_back), { index -= 1 })
        },
    ) {
        Text(
            stringResource(R.string.onboarding_progress, index + 1, steps.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Paragraphs(stringResource(step.body))
    }
}
