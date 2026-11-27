package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Duration math and validation. Written in Phase 2, run in Phase 8. */
class DurationPolicyTest {

    @Test
    fun presets_matchSpec() {
        assertEquals(listOf(15, 30, 45, 60, 120, 180, 360, 720, 1440), DurationPolicy.presetMinutes)
    }

    @Test
    fun everyPreset_isValid() {
        DurationPolicy.presetMinutes.forEach {
            assertEquals("preset $it", DurationCheck.Valid, DurationPolicy.check(it))
        }
    }

    @Test
    fun check_enforcesLimits() {
        assertEquals(DurationCheck.TooShort, DurationPolicy.check(DurationPolicy.MIN_MINUTES - 1))
        assertEquals(DurationCheck.Valid, DurationPolicy.check(DurationPolicy.MIN_MINUTES))
        assertEquals(DurationCheck.Valid, DurationPolicy.check(DurationPolicy.MAX_MINUTES))
        assertEquals(DurationCheck.TooLong, DurationPolicy.check(DurationPolicy.MAX_MINUTES + 1))
        assertEquals(DurationCheck.TooShort, DurationPolicy.check(0))
    }

    @Test
    fun endAt_addsExactMinutes() {
        assertEquals(1_000L + 90 * 60_000L, DurationPolicy.endAtMillis(1_000L, 90))
    }

    @Test
    fun parseCustom_emptyAndInvalid() {
        assertEquals(CustomDuration.Empty, DurationPolicy.parseCustom("", ""))
        assertEquals(CustomDuration.Invalid, DurationPolicy.parseCustom("1a", ""))
        assertEquals(CustomDuration.Invalid, DurationPolicy.parseCustom("", "-5"))
        assertEquals(CustomDuration.Invalid, DurationPolicy.parseCustom("1234", ""))
        assertEquals(CustomDuration.Invalid, DurationPolicy.parseCustom("", "12345"))
    }

    @Test
    fun parseCustom_combinesHoursAndMinutes() {
        assertEquals(CustomDuration.Parsed(90), DurationPolicy.parseCustom("1", "30"))
        assertEquals(CustomDuration.Parsed(120), DurationPolicy.parseCustom("2", ""))
        assertEquals(CustomDuration.Parsed(45), DurationPolicy.parseCustom("", "45"))
        assertEquals(CustomDuration.Parsed(1500), DurationPolicy.parseCustom("24", "60"))
    }
}
