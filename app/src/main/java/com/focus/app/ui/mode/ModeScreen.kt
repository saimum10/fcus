package com.focus.app.ui.mode

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.domain.Capability
import com.focus.app.domain.CapabilityLimit
import com.focus.app.domain.FocusMode
import com.focus.app.domain.UnavailableReason
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.descriptionRes
import com.focus.app.ui.titleRes
import com.focus.app.ui.components.SelectableRow

@Composable
fun ModeScreen(
    selected: FocusMode?,
    support: Map<FocusMode, Capability>,
    onSelect: (FocusMode) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    FocusScreen(
        title = stringResource(R.string.mode_title),
        onBack = onBack,
        actions = {
            PrimaryButton(
                stringResource(R.string.action_continue),
                onContinue,
                enabled = selected != null && support[selected] !is Capability.Unavailable,
            )
        },
    ) {
        FocusMode.entries.forEach { mode ->
            SelectableRow(
                title = stringResource(mode.titleRes()),
                supporting = listOfNotNull(
                    stringResource(mode.descriptionRes()),
                    when (val cap = support[mode]) {
                        is Capability.Unavailable -> stringResource(
                            if (cap.reason == UnavailableReason.PermissionMissing) R.string.mode_support_needs_access else R.string.mode_support_unsupported,
                        )
                        is Capability.Limited -> when (cap.limit) {
                            CapabilityLimit.NotificationsOnly -> stringResource(R.string.mode_support_limited)
                        }
                        Capability.Available, null -> null
                    },
                ).joinToString("\n"),
                selected = mode == selected,
                onClick = { onSelect(mode) },
            )
        }
        NoteText(stringResource(R.string.mode_enforcement_note))
    }
}
