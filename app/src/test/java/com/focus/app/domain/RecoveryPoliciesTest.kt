package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PIN, name, phone, lockout schedule and the two-clock wait timer. Written in Phase 6, run in Phase 8. */
class RecoveryPoliciesTest {

    // ---- PinPolicy -------------------------------------------------------------------

    @Test
    fun acceptsOrdinaryPins() {
        assertNull(PinPolicy.check("482915"))
        assertNull(PinPolicy.check("90172635"))
        assertNull(PinPolicy.check("123450")) // a run that is broken at the end
    }

    @Test
    fun rejectsWrongLengthAndNonDigits() {
        assertEquals(InputProblem.PinTooShort, PinPolicy.check("48291"))
        assertEquals(InputProblem.PinTooLong, PinPolicy.check("4829153847261"))
        assertEquals(InputProblem.PinNotDigits, PinPolicy.check("48291a"))
        assertEquals(InputProblem.PinNotDigits, PinPolicy.check("48 915"))
    }

    @Test
    fun rejectsObviousPins() {
        assertEquals(InputProblem.PinTooSimple, PinPolicy.check("000000"))
        assertEquals(InputProblem.PinTooSimple, PinPolicy.check("123456"))
        assertEquals(InputProblem.PinTooSimple, PinPolicy.check("654321"))
        assertEquals(InputProblem.PinTooSimple, PinPolicy.check("0123456789"))
    }

    // ---- TrustedPersonPolicy ---------------------------------------------------------

    @Test
    fun nameIsTrimmedAndBounded() {
        assertNull(TrustedPersonPolicy.checkName("  Sam "))
        assertEquals(InputProblem.NameEmpty, TrustedPersonPolicy.checkName("   "))
        assertEquals(InputProblem.NameTooLong, TrustedPersonPolicy.checkName("x".repeat(41)))
        assertNull(TrustedPersonPolicy.checkName("x".repeat(40)))
    }

    @Test
    fun phoneIsOptionalAndValidated() {
        assertNull(TrustedPersonPolicy.checkPhone(""))
        assertNull(TrustedPersonPolicy.checkPhone("   "))
        assertNull(TrustedPersonPolicy.checkPhone("+44 (0) 20-7946.0958"))
        assertEquals(InputProblem.PhoneInvalid, TrustedPersonPolicy.checkPhone("12"))
        assertEquals(InputProblem.PhoneInvalid, TrustedPersonPolicy.checkPhone("call me"))
        assertEquals(InputProblem.PhoneInvalid, TrustedPersonPolicy.checkPhone("12+345678"))
        assertEquals(InputProblem.PhoneInvalid, TrustedPersonPolicy.checkPhone("1".repeat(16)))
    }

    @Test
    fun dialableKeepsOnlyDigitsAndLeadingPlus() {
        assertNull(TrustedPersonPolicy.dialable(" "))
        assertEquals("+442079460958", TrustedPersonPolicy.dialable("+44 (0) 20-7946 0958".replace("(0) ", "")))
        assertEquals("5551234567", TrustedPersonPolicy.dialable("(555) 123-4567"))
    }

    // ---- AttemptPolicy ---------------------------------------------------------------

    @Test
    fun threeFreeAttemptsThenGrowingLocks() {
        assertEquals(0L, AttemptPolicy.lockMillisAfter(1))
        assertEquals(0L, AttemptPolicy.lockMillisAfter(2))
        assertEquals(60_000L, AttemptPolicy.lockMillisAfter(3))
        assertEquals(5 * 60_000L, AttemptPolicy.lockMillisAfter(4))
        assertEquals(15 * 60_000L, AttemptPolicy.lockMillisAfter(5))
        assertEquals(30 * 60_000L, AttemptPolicy.lockMillisAfter(6))
        assertEquals(60 * 60_000L, AttemptPolicy.lockMillisAfter(7))
        assertEquals(60 * 60_000L, AttemptPolicy.lockMillisAfter(500))
    }

    @Test
    fun locksNeverShrink() {
        var previous = 0L
        for (n in 0..20) {
            val lock = AttemptPolicy.lockMillisAfter(n)
            assertTrue("lock after $n attempts", lock >= previous)
            previous = lock
        }
    }

    @Test
    fun freeAttemptsLeftCountsDown() {
        assertEquals(3, AttemptPolicy.freeAttemptsLeft(0))
        assertEquals(1, AttemptPolicy.freeAttemptsLeft(2))
        assertEquals(0, AttemptPolicy.freeAttemptsLeft(3))
        assertEquals(0, AttemptPolicy.freeAttemptsLeft(9))
    }

    // ---- WaitTimer -------------------------------------------------------------------

    private val minute = 60_000L

    @Test
    fun sameBootUsesTheMonotonicClock() {
        val timer = WaitTimer.start(TimeSnapshot(wallMillis = 1_000_000L, elapsedMillis = 50_000L, bootCount = 3), 10 * minute)
        assertEquals(10 * minute, timer.remainingMillis(TimeSnapshot(1_000_000L, 50_000L, 3)))
        assertEquals(6 * minute, timer.remainingMillis(TimeSnapshot(1_000_000L + 4 * minute, 50_000L + 4 * minute, 3)))
        assertEquals(0L, timer.remainingMillis(TimeSnapshot(1_000_000L + 11 * minute, 50_000L + 11 * minute, 3)))
    }

    @Test
    fun changingTheWallClockWithinOneBootChangesNothing() {
        val timer = WaitTimer.start(TimeSnapshot(1_000_000L, 50_000L, 3), 10 * minute)
        // The wall clock jumps a day forward, the monotonic clock moved only a minute.
        val now = TimeSnapshot(1_000_000L + 24 * 60 * minute, 50_000L + minute, 3)
        assertEquals(9 * minute, timer.remainingMillis(now))
        // ... and a day back.
        val back = TimeSnapshot(1_000_000L - 24 * 60 * minute, 50_000L + minute, 3)
        assertEquals(9 * minute, timer.remainingMillis(back))
    }

    @Test
    fun afterARestartTheWallClockIsUsedAndClamped() {
        val timer = WaitTimer.start(TimeSnapshot(1_000_000L, 50_000L, 3), 10 * minute)
        assertEquals(5 * minute, timer.remainingMillis(TimeSnapshot(1_000_000L + 5 * minute, 1_000L, 4)))
        assertEquals(0L, timer.remainingMillis(TimeSnapshot(1_000_000L + 20 * minute, 1_000L, 4)))
        // A clock set far back after the restart can never make it longer than the full time.
        assertEquals(10 * minute, timer.remainingMillis(TimeSnapshot(1_000_000L - 5 * minute, 1_000L, 4)))
    }

    @Test
    fun withoutABootCounterABackwardsMonotonicClockMeansRestart() {
        val timer = WaitTimer.start(TimeSnapshot(1_000_000L, 50_000L, null), 10 * minute)
        assertEquals(9 * minute, timer.remainingMillis(TimeSnapshot(1_000_000L + minute, 50_000L + minute, null)))
        assertEquals(4 * minute, timer.remainingMillis(TimeSnapshot(1_000_000L + 6 * minute, 2_000L, null)))
    }

    @Test
    fun storageRoundTrip() {
        val withBoot = WaitTimer(1L, 2L, 3, 4L)
        val withoutBoot = WaitTimer(10L, 20L, null, 40L)
        assertEquals(withBoot, WaitTimer.fromStorage(withBoot.toStorage()))
        assertEquals(withoutBoot, WaitTimer.fromStorage(withoutBoot.toStorage()))
    }

    @Test
    fun damagedStorageIsRejected() {
        assertNull(WaitTimer.fromStorage(""))
        assertNull(WaitTimer.fromStorage("1,2,3"))
        assertNull(WaitTimer.fromStorage("a,2,3,4"))
        assertNull(WaitTimer.fromStorage("1,2,x,4"))
        assertNull(WaitTimer.fromStorage("1,2,3,-4"))
    }
}
