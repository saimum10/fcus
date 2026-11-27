package com.focus.app.ui.duration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.focus.app.R
import com.focus.app.domain.CustomDuration
import com.focus.app.domain.DurationCheck
import com.focus.app.domain.DurationPolicy
import com.focus.app.ui.components.ChipFlow
import com.focus.app.ui.components.FocusChip
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.format.formatDuration
import com.focus.app.ui.theme.spacing

/**
 * Presets plus a custom hours/minutes entry. Reports the chosen minutes (or null when
 * nothing usable is entered) through [onChange]; validation text is shown here, and the
 * caller decides when Continue is allowed via [DurationPolicy.check].
 */
@Composable
fun DurationPicker(durationMinutes: Int?, onChange: (Int?) -> Unit) {
    val spacing = MaterialTheme.spacing
    var hours by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableStateOf("") }
    val customActive = hours.isNotEmpty() || minutes.isNotEmpty()

    fun updateCustom(newHours: String, newMinutes: String) {
        hours = newHours
        minutes = newMinutes
        onChange(
            when (val parsed = DurationPolicy.parseCustom(newHours, newMinutes)) {
                is CustomDuration.Parsed -> parsed.minutes
                CustomDuration.Empty, CustomDuration.Invalid -> null
            },
        )
    }

    SectionLabel(stringResource(R.string.duration_presets))
    ChipFlow {
        DurationPolicy.presetMinutes.forEach { preset ->
            FocusChip(
                label = formatDuration(preset),
                selected = !customActive && durationMinutes == preset,
                onClick = {
                    hours = ""
                    minutes = ""
                    onChange(preset)
                },
            )
        }
    }

    SectionLabel(stringResource(R.string.duration_custom))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.m)) {
        OutlinedTextField(
            value = hours,
            onValueChange = { v -> updateCustom(v.filter { it in '0'..'9' }.take(3), minutes) },
            label = { Text(stringResource(R.string.duration_hours)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = minutes,
            onValueChange = { v -> updateCustom(hours, v.filter { it in '0'..'9' }.take(4)) },
            label = { Text(stringResource(R.string.duration_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }

    val check = durationMinutes?.let(DurationPolicy::check)
    val limits = stringResource(
        R.string.duration_limits,
        formatDuration(DurationPolicy.MIN_MINUTES),
        formatDuration(DurationPolicy.MAX_MINUTES),
    )
    val error = when {
        customActive && durationMinutes == null -> stringResource(R.string.duration_invalid)
        check == DurationCheck.TooShort || check == DurationCheck.TooLong -> limits
        else -> null
    }
    Text(
        text = error ?: limits,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = spacing.s),
    )
}
