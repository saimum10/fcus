package com.focus.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.focus.app.R
import com.focus.app.domain.FocusStats
import com.focus.app.ui.components.FocusScreen
import com.focus.app.ui.components.Hairline
import com.focus.app.ui.components.InfoRow
import com.focus.app.ui.components.NoteText
import com.focus.app.ui.components.SecondaryButton
import com.focus.app.ui.components.SectionLabel
import com.focus.app.ui.format.formatDateShort
import com.focus.app.ui.format.formatDayShort
import com.focus.app.ui.format.formatDurationMillis
import com.focus.app.ui.state.StatsUiState
import com.focus.app.ui.theme.spacing
import java.time.temporal.ChronoUnit

/**
 * Real numbers from the session history, nothing else. Time restricted, how many sessions
 * completed or ended early, and per-day and per-week totals as plain bars with the value
 * written out next to each (the text carries the meaning; the bar is only a glance).
 */
@Composable
fun StatsScreen(
    state: StatsUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    FocusScreen(title = stringResource(R.string.stats_title), onBack = onBack) {
        when (state) {
            StatsUiState.Loading -> NoteText(stringResource(R.string.home_loading))
            StatsUiState.Failed -> {
                NoteText(stringResource(R.string.stats_error))
                SecondaryButton(stringResource(R.string.action_retry), onRetry)
            }
            is StatsUiState.Ready ->
                if (state.stats.totals.isEmpty) {
                    NoteText(stringResource(R.string.stats_empty))
                } else {
                    StatsContent(state.stats)
                }
        }
    }
}

@Composable
private fun StatsContent(stats: FocusStats) {
    val totals = stats.totals

    SectionLabel(stringResource(R.string.stats_total_heading))
    InfoRow(stringResource(R.string.stats_total_time), formatDurationMillis(totals.activeMillis))
    InfoRow(stringResource(R.string.outcome_completed), totals.completed.toString())
    InfoRow(stringResource(R.string.outcome_interrupted), totals.interrupted.toString())
    if (totals.failed > 0) {
        InfoRow(stringResource(R.string.outcome_failed), totals.failed.toString())
    }

    SectionLabel(stringResource(R.string.stats_days_heading))
    val todayDate = stats.days.firstOrNull()?.date
    val dayMax = stats.days.maxOfOrNull { it.activeMillis } ?: 0L
    stats.days.forEach { day ->
        val label = when (if (todayDate == null) -1L else ChronoUnit.DAYS.between(day.date, todayDate)) {
            0L -> stringResource(R.string.stats_today)
            1L -> stringResource(R.string.stats_yesterday)
            else -> formatDayShort(day.date)
        }
        StatBarRow(label, formatDurationMillis(day.activeMillis), fractionOf(day.activeMillis, dayMax))
        Hairline()
    }

    SectionLabel(stringResource(R.string.stats_weeks_heading))
    val weekMax = stats.weeks.maxOfOrNull { it.activeMillis } ?: 0L
    stats.weeks.forEachIndexed { index, week ->
        val label = when (index) {
            0 -> stringResource(R.string.stats_this_week)
            1 -> stringResource(R.string.stats_last_week)
            else -> stringResource(
                R.string.stats_week_range,
                formatDateShort(week.weekStart),
                formatDateShort(week.weekStart.plusDays(6)),
            )
        }
        StatBarRow(label, formatDurationMillis(week.activeMillis), fractionOf(week.activeMillis, weekMax))
        Hairline()
    }

    NoteText(stringResource(R.string.stats_note), modifier = Modifier.padding(top = MaterialTheme.spacing.m))
}

/** Share of the longest bar in the list; 0 for no time at all. */
private fun fractionOf(value: Long, max: Long): Float = if (max <= 0L || value <= 0L) 0f else value.toFloat() / max

/**
 * One label, its value as text, and a thin bar under them. Label and value sit on one
 * line but the label wraps, so large text sizes and narrow screens still read in full.
 * The row is announced as one item ("Today: 1 h 20 min").
 */
@Composable
private fun StatBarRow(label: String, value: String, fraction: Float) {
    val spacing = MaterialTheme.spacing
    val spoken = stringResource(R.string.stats_bar_row, label, value)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.s)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(end = spacing.s),
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.xs)
                .height(BAR_HEIGHT)
                .clip(BAR_SHAPE)
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(MIN_VISIBLE_FRACTION, 1f))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private val BAR_HEIGHT = 6.dp
private val BAR_SHAPE = RoundedCornerShape(3.dp)

/** A tiny but real value still gets a visible sliver. */
private const val MIN_VISIBLE_FRACTION = 0.02f
