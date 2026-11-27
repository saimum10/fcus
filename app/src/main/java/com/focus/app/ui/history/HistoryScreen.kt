package com.focus.app.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.focus.app.R
import com.focus.app.domain.FinishedSession
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.format.formatDateTime
import com.focus.app.ui.format.formatDuration
import com.focus.app.ui.format.formatDurationMillis
import com.focus.app.ui.state.HistoryUiState
import com.focus.app.ui.textRes
import com.focus.app.ui.theme.spacing
import com.focus.app.ui.titleRes

/** Past sessions, newest first, with how each ended and why. Stored only on this device. */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    clearFailed: Boolean,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val hasSessions = state is HistoryUiState.Loaded && state.sessions.isNotEmpty()
    FocusScreen(
        title = stringResource(R.string.history_title),
        onBack = onBack,
        scrollable = false,
        actions = {
            if (hasSessions) SecondaryButton(stringResource(R.string.history_clear), onClear)
        },
    ) {
        if (clearFailed) NoteText(stringResource(R.string.history_clear_failed))
        when (state) {
            HistoryUiState.Loading -> NoteText(stringResource(R.string.home_loading))
            HistoryUiState.Failed -> {
                NoteText(stringResource(R.string.history_error))
                SecondaryButton(stringResource(R.string.action_retry), onRetry)
            }
            is HistoryUiState.Loaded ->
                if (state.sessions.isEmpty()) {
                    NoteText(stringResource(R.string.history_empty))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(state.sessions, key = { it.id }) { session ->
                            HistoryRow(session)
                            Hairline()
                        }
                    }
                }
        }
    }
}

@Composable
private fun HistoryRow(session: FinishedSession) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.m),
    ) {
        Text(
            stringResource(R.string.history_detail_header, stringResource(session.mode.titleRes()), stringResource(session.outcome.textRes())),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            formatDateTime(session.endedAtWallMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.history_detail,
                formatDuration(session.plannedMinutes),
                formatDurationMillis(session.activeMillis),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        session.reason?.let {
            Text(
                stringResource(it.textRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
