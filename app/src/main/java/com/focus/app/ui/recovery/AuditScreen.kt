package com.focus.app.ui.recovery

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
import com.focus.app.domain.AuditEvent
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.format.formatDateTime
import com.focus.app.ui.textRes
import com.focus.app.ui.theme.spacing

/**
 * The recovery log: what happened and when, newest first, stored only on this device. It
 * never holds a PIN. It can be cleared only while no session is running, so a recovery
 * attempt cannot be wiped from the record in the middle of a session.
 */
@Composable
fun AuditScreen(
    events: List<AuditEvent>?,
    sessionRunning: Boolean,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    FocusScreen(
        title = stringResource(R.string.audit_title),
        onBack = onBack,
        scrollable = false,
        actions = {
            if (!events.isNullOrEmpty() && !sessionRunning) SecondaryButton(stringResource(R.string.audit_clear), onClear)
        },
    ) {
        NoteText(stringResource(R.string.audit_note))
        if (sessionRunning) NoteText(stringResource(R.string.audit_session_running))
        when {
            events == null -> NoteText(stringResource(R.string.home_loading))
            events.isEmpty() -> NoteText(stringResource(R.string.audit_empty))
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(events, key = { it.id }) { event ->
                    AuditRow(event)
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun AuditRow(event: AuditEvent) {
    Column(Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.m)) {
        Text(
            stringResource(event.type.textRes()),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            formatDateTime(event.atWallMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
