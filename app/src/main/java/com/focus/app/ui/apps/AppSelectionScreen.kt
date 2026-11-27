package com.focus.app.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.focus.app.R
import com.focus.app.domain.AppIconSource
import com.focus.app.domain.DiscoveredApp
import com.focus.app.ui.components.BodyText
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.FocusSearchField
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.textRes
import com.focus.app.ui.theme.spacing

private val IconSize = 40.dp

/**
 * Real app list: launcher apps with label and icon, search, select/deselect (persisted),
 * and the apps that can never be restricted shown separately with their reason.
 */
@Composable
fun AppSelectionScreen(
    state: AppListUiState,
    query: String,
    iconSource: AppIconSource,
    onQueryChange: (String) -> Unit,
    onToggle: (packageName: String, selected: Boolean) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val selectedCount = (state as? AppListUiState.Loaded)?.selected?.size ?: 0
    FocusScreen(
        title = stringResource(R.string.apps_title),
        onBack = onBack,
        scrollable = false,
        actions = {
            PrimaryButton(stringResource(R.string.action_continue), onContinue, enabled = selectedCount > 0)
        },
    ) {
        when (state) {
            AppListUiState.Loading -> NoteText(stringResource(R.string.apps_loading))
            AppListUiState.Failed -> {
                BodyText(stringResource(R.string.apps_failed))
                SecondaryButton(stringResource(R.string.apps_retry), onRetry)
            }
            is AppListUiState.Loaded -> LoadedContent(
                state = state,
                query = query,
                iconSource = iconSource,
                onQueryChange = onQueryChange,
                onToggle = onToggle,
                onClear = onClear,
            )
        }
    }
}

@Composable
private fun ColumnScope.LoadedContent(
    state: AppListUiState.Loaded,
    query: String,
    iconSource: AppIconSource,
    onQueryChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val count = state.selected.size
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            pluralStringResource(R.plurals.apps_selected_count, count, count),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = spacing.touchTarget)) {
                Text(stringResource(R.string.apps_clear), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    FocusSearchField(
        value = query,
        onValueChange = onQueryChange,
        label = stringResource(R.string.apps_search),
        modifier = Modifier.padding(vertical = spacing.s),
    )
    if (state.saveFailed) NoteText(stringResource(R.string.apps_save_failed))

    LazyColumn(modifier = Modifier.weight(1f)) {
        items(state.selectable, key = { it.packageName }) { app ->
            val checked = app.packageName in state.selected
            AppRow(
                app = app,
                generation = state.generation,
                iconSource = iconSource,
                modifier = Modifier.toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle(app.packageName, it) },
                ),
                trailing = { Checkbox(checked = checked, onCheckedChange = null) },
            )
        }
        if (state.selectable.isEmpty() && state.protectedApps.isEmpty()) {
            item {
                NoteText(
                    stringResource(if (query.isBlank()) R.string.apps_empty else R.string.apps_no_match),
                )
            }
        }
        if (state.protectedApps.isNotEmpty()) {
            item {
                Column {
                    SectionLabel(stringResource(R.string.apps_protected_heading))
                    NoteText(stringResource(R.string.apps_protected_body))
                }
            }
            items(state.protectedApps, key = { "protected:" + it.packageName }) { app ->
                AppRow(
                    app = app,
                    generation = state.generation,
                    iconSource = iconSource,
                    supporting = app.protection?.let { stringResource(it.textRes()) },
                )
            }
        }
        item { NoteText(stringResource(R.string.apps_scope_note)) }
    }
}

/** One app: icon, label, optional supporting text, optional trailing control. */
@Composable
private fun AppRow(
    app: DiscoveredApp,
    generation: Int,
    iconSource: AppIconSource,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.touchTarget)
            .padding(vertical = spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            packageName = app.packageName,
            label = app.label,
            generation = generation,
            iconSource = iconSource,
            size = IconSize,
        )
        Column(Modifier.weight(1f).padding(horizontal = spacing.m)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
        trailing?.invoke()
    }
}
