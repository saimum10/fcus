package com.focus.app.domain

/** Result of validating a duration against [DurationPolicy] limits. */
enum class DurationCheck { Valid, TooShort, TooLong }

/** Result of parsing the custom hours/minutes text fields. */
sealed interface CustomDuration {
    /** Both fields are empty. */
    data object Empty : CustomDuration

    /** Not digits only, or implausibly long input. */
    data object Invalid : CustomDuration

    /** Parsed total; still has to pass [DurationPolicy.check]. */
    data class Parsed(val minutes: Int) : CustomDuration
}

/** Duration rules and math. Pure Kotlin so it can be unit tested without Android. */
object DurationPolicy {
    const val MIN_MINUTES: Int = 5
    const val MAX_MINUTES: Int = 24 * 60

    /** Presets from the product spec: 15m, 30m, 45m, 1h, 2h, 3h, 6h, 12h, 24h. */
    val presetMinutes: List<Int> = listOf(15, 30, 45, 60, 120, 180, 360, 720, 1440)

    fun check(minutes: Int): DurationCheck = when {
        minutes < MIN_MINUTES -> DurationCheck.TooShort
        minutes > MAX_MINUTES -> DurationCheck.TooLong
        else -> DurationCheck.Valid
    }

    /** Exact end timestamp for a session started at [startMillis]. */
    fun endAtMillis(startMillis: Long, minutes: Int): Long = startMillis + minutes * 60_000L

    /** Parses the custom fields. An empty field counts as 0 when the other has digits. */
    fun parseCustom(hours: String, minutes: String): CustomDuration {
        if (hours.isEmpty() && minutes.isEmpty()) return CustomDuration.Empty
        if (!hours.isAsciiDigits() || !minutes.isAsciiDigits()) return CustomDuration.Invalid
        if (hours.length > 3 || minutes.length > 4) return CustomDuration.Invalid
        val h = if (hours.isEmpty()) 0 else hours.toInt()
        val m = if (minutes.isEmpty()) 0 else minutes.toInt()
        return CustomDuration.Parsed(h * 60 + m)
    }

    private fun String.isAsciiDigits(): Boolean = all { it in '0'..'9' }
}
