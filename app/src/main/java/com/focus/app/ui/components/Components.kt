package com.focus.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.focus.app.R
import com.focus.app.ui.theme.spacing

/**
 * Standard screen frame: system insets, optional text "Back", scrolling content and an
 * optional pinned action area. No app bar, no icons, no shadows. Pass `scrollable = false`
 * when the content hosts its own lazy list (which must not sit inside a scroll container).
 *
 * On wide windows (tablets, foldables, landscape) the content is centered and limited to
 * [com.focus.app.ui.theme.FocusSpacing.contentMaxWidth] instead of stretching text lines
 * across the whole screen; on a phone in portrait nothing changes. The scroll area itself
 * still spans the full width, so scrolling works from the margins too.
 */
@Composable
fun FocusScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val maxWidth = spacing.contentMaxWidth
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxWidth()) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = spacing.s).heightIn(min = spacing.touchTarget),
                ) { Text(stringResource(R.string.action_back)) }
            } else {
                Spacer(Modifier.height(spacing.l))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    // A lazy list inside needs a bounded height; a scrolling column must not be given one.
                    .then(if (scrollable) Modifier else Modifier.fillMaxHeight())
                    .padding(horizontal = spacing.l),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = spacing.m).semantics { heading() },
                )
                content()
                Spacer(Modifier.height(spacing.l))
            }
        }
        if (actions != null) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.l, vertical = spacing.m),
                verticalArrangement = Arrangement.spacedBy(spacing.s),
            ) { actions() }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = MaterialTheme.spacing.touchTarget),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Small section heading inside a screen. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(top = MaterialTheme.spacing.l, bottom = MaterialTheme.spacing.s)
            .semantics { heading() },
    )
}

/** Body paragraph. [secondary] uses the lower-emphasis text color. */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, secondary: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(bottom = MaterialTheme.spacing.s),
    )
}

/** Splits [text] on blank lines and renders one paragraph each. */
@Composable
fun Paragraphs(text: String, secondary: Boolean = false) {
    text.split("\n\n").forEach { BodyText(it, secondary = secondary) }
}

@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = MaterialTheme.spacing.s),
    )
}

@Composable
fun Hairline() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Single-choice row with a radio button; the whole row is the touch target. */
@Composable
fun SelectableRow(
    title: String,
    supporting: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = spacing.s, end = spacing.m))
        Column(Modifier.weight(1f).padding(vertical = spacing.s)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }
    }
}

/** Label/value line used on the confirmation and settings screens. */
@Composable
fun InfoRow(label: String, value: String) {
    val spacing = MaterialTheme.spacing
    Column(Modifier.fillMaxWidth().padding(vertical = spacing.s)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Tappable settings-style row (text only, no chevron icon). */
@Composable
fun NavRow(title: String, supporting: String?, onClick: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Column(
        Modifier
            .fillMaxWidth()
            .selectable(selected = false, role = Role.Button, onClick = onClick)
            .heightIn(min = spacing.touchTarget)
            .padding(vertical = spacing.m),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/** Selectable chip. The selected state is filled with the primary color. */
@Composable
fun FocusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.heightIn(min = MaterialTheme.spacing.touchTarget),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) { content() }
}

/** Single-line search field; the keyboard's search key just dismisses the keyboard. */
@Composable
fun FocusSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = modifier.fillMaxWidth(),
    )
}
