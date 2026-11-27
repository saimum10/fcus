package com.focus.app.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focus.app.R
import com.focus.app.domain.DurationCheck
import com.focus.app.domain.DurationPolicy
import com.focus.app.domain.SessionDraft
import com.focus.app.ui.components.ChipFlow
import com.focus.app.ui.components.FocusChip
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.PrimaryButton
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.duration.DurationPicker
import com.focus.app.ui.format.formatMinuteOfDay
import com.focus.app.ui.format.is24Hour
import com.focus.app.ui.format.weekdayShort
import com.focus.app.ui.format.weekdaysInOrder
import com.focus.app.ui.theme.spacing
import java.time.DayOfWeek

/** Scheduled Focus: days, start time and duration. Only configures; nothing is scheduled with the OS yet. */
@Composable
fun ScheduleScreen(
    draft: SessionDraft,
    onToggleDay: (DayOfWeek) -> Unit,
    onStartChange: (Int) -> Unit,
    onDurationChange: (Int?) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val durationValid = draft.durationMinutes?.let { DurationPolicy.check(it) == DurationCheck.Valid } == true
    val canContinue = draft.scheduleDays.isNotEmpty() && durationValid

    FocusScreen(
        title = stringResource(R.string.schedule_title),
        onBack = onBack,
        actions = { PrimaryButton(stringResource(R.string.action_continue), onContinue, enabled = canContinue) },
    ) {
        SectionLabel(stringResource(R.string.schedule_days))
        ChipFlow {
            weekdaysInOrder.forEach { day ->
                FocusChip(
                    label = weekdayShort(day),
                    selected = day in draft.scheduleDays,
                    onClick = { onToggleDay(day) },
                )
            }
        }

        SectionLabel(stringResource(R.string.schedule_start))
        SecondaryButton(formatMinuteOfDay(draft.scheduleStartMinuteOfDay), { showPicker = true })

        DurationPicker(draft.durationMinutes, onDurationChange)
    }

    if (showPicker) {
        StartTimeDialog(
            initialMinuteOfDay = draft.scheduleStartMinuteOfDay,
            onConfirm = {
                onStartChange(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartTimeDialog(initialMinuteOfDay: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    val state = rememberTimePickerState(
        initialHour = initialMinuteOfDay / 60,
        initialMinute = initialMinuteOfDay % 60,
        is24Hour = is24Hour(),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(spacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.schedule_start),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = spacing.m),
                )
                TimePicker(state = state)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            }
        }
    }
}
