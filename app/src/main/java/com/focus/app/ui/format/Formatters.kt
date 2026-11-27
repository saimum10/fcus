package com.focus.app.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.focus.app.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Wall-clock "now", refreshed on every [tickMillis] boundary (minute or second).
 *
 * The timer runs only while the screen is started. A composition stays alive while the
 * app is in the background, so without this the once-a-second loop would keep waking the
 * process for a clock nobody can see. On return the loop starts again and sets the
 * current time first, so nothing stale is shown.
 */
@Composable
fun rememberNowMillis(tickMillis: Long): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tickMillis, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = System.currentTimeMillis()
                now.longValue = current
                delay(tickMillis - current % tickMillis)
            }
        }
    }
    return now
}

@Composable
fun is24Hour(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

/** Minute-of-day (0..1439) in the user's 12/24-hour preference. */
@Composable
fun formatMinuteOfDay(minuteOfDay: Int): String {
    val pattern = if (is24Hour()) "HH:mm" else "h:mm a"
    return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** "30 min", "2 h", "1 h 30 min". */
@Composable
fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> stringResource(R.string.duration_min, m)
        m == 0 -> stringResource(R.string.duration_h, h)
        else -> stringResource(R.string.duration_h_min, h, m)
    }
}

/** "Ends today at 14:30", "Ends tomorrow at 02:00", or with a date for later days. */
@Composable
fun endTimeLabel(endMillis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val end = Instant.ofEpochMilli(endMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = formatMinuteOfDay(end.hour * 60 + end.minute)
    return when (ChronoUnit.DAYS.between(today, end.toLocalDate())) {
        0L -> stringResource(R.string.end_today, time)
        1L -> stringResource(R.string.end_tomorrow, time)
        else -> stringResource(
            R.string.end_on_date,
            end.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            time,
        )
    }
}

/** Short local date and time, e.g. "Nov 22, 2026, 9:00 AM". */
fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** Whole minutes of [millis] as "12 min" / "1 h 5 min"; under a minute reads "0 min". */
@Composable
fun formatDurationMillis(millis: Long): String = formatDuration((millis / 60_000L).toInt())

/** HH:MM:SS countdown text. */
fun formatRemaining(remainingMillis: Long): String {
    val total = remainingMillis / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
}

fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** Monday-first order, used for the day chips. */
val weekdaysInOrder: List<DayOfWeek> = DayOfWeek.entries.toList()

/** "Tue, Nov 17" in the device language; falls back to a medium date if the pattern is unusable. */
fun formatDayShort(date: LocalDate): String = formatWithSkeleton(date, "EEEMMMd")

/** "Nov 17" in the device language. */
fun formatDateShort(date: LocalDate): String = formatWithSkeleton(date, "MMMd")

private fun formatWithSkeleton(date: LocalDate, skeleton: String): String {
    val locale = Locale.getDefault()
    return try {
        date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale))
    } catch (_: IllegalArgumentException) {
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}
