package com.focus.app.domain

import com.focus.app.domain.SessionTestData.MIN
import com.focus.app.domain.SessionTestData.running
import com.focus.app.domain.SessionTestData.time
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clock, time-zone and restart behavior of the session timer. Written in Phase 4, run in Phase 8. */
class SessionTimingTest {
    private val session = running() // 60 min, wall 1_000_000, elapsed 5_000, boot 3

    @Test
    fun sameBoot_countsDownOnTheMonotonicClock() {
        val now = time(wall = 1_000_000L + 10 * MIN, elapsed = 5_000L + 10 * MIN)
        assertEquals(50 * MIN, SessionTiming.remainingMillis(session, now))
        assertEquals(10 * MIN, SessionTiming.elapsedMillis(session, now))
    }

    @Test
    fun sameBoot_wallClockMovedForward_doesNotShortenTheSession() {
        // Person sets the clock 5 hours ahead after 10 minutes.
        val now = time(wall = 1_000_000L + 5 * 60 * MIN, elapsed = 5_000L + 10 * MIN)
        assertEquals(50 * MIN, SessionTiming.remainingMillis(session, now))
        assertFalse(SessionTiming.isExpired(session, now))
    }

    @Test
    fun sameBoot_wallClockMovedBack_doesNotExtendTheSession() {
        val now = time(wall = 1_000_000L - 3 * 60 * MIN, elapsed = 5_000L + 30 * MIN)
        assertEquals(30 * MIN, SessionTiming.remainingMillis(session, now))
    }

    @Test
    fun expiresExactlyAtTheEnd_andNeverGoesNegative() {
        val atEnd = time(wall = 0L, elapsed = 5_000L + 60 * MIN)
        val after = time(wall = 0L, elapsed = 5_000L + 90 * MIN)
        assertEquals(0L, SessionTiming.remainingMillis(session, atEnd))
        assertTrue(SessionTiming.isExpired(session, atEnd))
        assertEquals(0L, SessionTiming.remainingMillis(session, after))
        assertEquals(60 * MIN, SessionTiming.elapsedMillis(session, after))
    }

    @Test
    fun afterRestart_usesTheWallClock() {
        // Boot #4, wall clock says 20 minutes after the start.
        val now = time(wall = 1_000_000L + 20 * MIN, elapsed = 2_000L, boot = 4)
        assertFalse(SessionTiming.sameBoot(session, now))
        assertEquals(40 * MIN, SessionTiming.remainingMillis(session, now))
    }

    @Test
    fun afterRestart_clockBeforeStart_cannotExtendBeyondTheDuration() {
        val now = time(wall = 1_000_000L - 10 * 60 * MIN, elapsed = 2_000L, boot = 4)
        assertEquals(60 * MIN, SessionTiming.remainingMillis(session, now))
    }

    @Test
    fun afterRestart_unsyncedClock_isNeverEarlierThanTheLastSeenTime() {
        // Device booted with the clock at 1970; fOCUS last saw wall time 30 min into the session.
        val seen = running(lastSeenWall = 1_000_000L + 30 * MIN)
        val now = time(wall = 5_000L, elapsed = 2_000L, boot = 4)
        assertEquals(30 * MIN, SessionTiming.remainingMillis(seen, now))
    }

    @Test
    fun afterRestart_endedWhileOff_isExpired() {
        val now = time(wall = 1_000_000L + 2 * 60 * MIN, elapsed = 2_000L, boot = 4)
        assertTrue(SessionTiming.isExpired(session, now))
    }

    @Test
    fun unknownBootCount_fallsBackToComparingTheMonotonicClock() {
        val noBoot = running(bootCount = null)
        // elapsed >= anchor: same boot.
        assertTrue(SessionTiming.sameBoot(noBoot, time(1_000_000L, 5_000L + MIN, boot = null)))
        // elapsed < anchor: the clock restarted, so this is a new boot.
        assertFalse(SessionTiming.sameBoot(noBoot, time(1_000_000L + 20 * MIN, 1_000L, boot = null)))
    }

    @Test
    fun rebased_afterRestart_keepsTheRemainingTime_andMovesToTheNewBoot() {
        val now = time(wall = 1_000_000L + 20 * MIN, elapsed = 2_000L, boot = 4)
        val rebased = SessionTiming.rebased(session, now)
        assertEquals(4, rebased.bootCount)
        assertEquals(40 * MIN, SessionTiming.remainingMillis(rebased, now))
        // And the monotonic clock now governs: 10 minutes later in the new boot.
        val later = time(wall = 0L, elapsed = 2_000L + 10 * MIN, boot = 4)
        assertEquals(30 * MIN, SessionTiming.remainingMillis(rebased, later))
        assertEquals(now.wallMillis + 40 * MIN, rebased.endAtWallMillis)
    }

    @Test
    fun rebased_afterClockChange_correctsTheDisplayedEndTime() {
        val now = time(wall = 1_000_000L + 5 * 60 * MIN, elapsed = 5_000L + 10 * MIN)
        val rebased = SessionTiming.rebased(session, now)
        assertEquals(now.wallMillis + 50 * MIN, rebased.endAtWallMillis)
        assertEquals(now.wallMillis, rebased.lastSeenWallMillis)
        assertEquals(50 * MIN, SessionTiming.remainingMillis(rebased, now))
    }

    @Test
    fun needsRebase_onlyWhenSomethingChanged() {
        val calm = time(wall = 1_000_000L + MIN / 2, elapsed = 5_000L + MIN / 2)
        assertFalse(SessionTiming.needsRebase(session, calm))
        // A new boot always needs a new anchor.
        assertTrue(SessionTiming.needsRebase(session, time(1_000_000L, 1_000L, boot = 4)))
        // Wall clock jumped relative to the monotonic clock.
        assertTrue(SessionTiming.needsRebase(session, time(1_000_000L + 3 * 60 * MIN, 5_000L + MIN)))
        // Old checkpoint.
        assertTrue(SessionTiming.needsRebase(session, time(1_000_000L + 2 * MIN, 5_000L + 2 * MIN)))
    }
}
