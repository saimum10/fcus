package com.focus.app.data

import com.focus.app.domain.EndReason
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RunningSession
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.SessionSource
import com.focus.app.domain.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Storage format of the session state, including damaged input. Run in Phase 8. */
class SessionStateCodecTest {
    private val plan = SessionPlan(FocusMode.SelectedApps, setOf("com.a", "com.b"), 90)
    private val running = RunningSession(
        id = "s1",
        plan = plan,
        source = SessionSource.Scheduled("sched-1", 1_700_000_000_000L),
        startedAtWallMillis = 1_700_000_000_000L,
        endAtWallMillis = 1_700_000_005_400_000L,
        durationMillis = 5_400_000L,
        startedAtElapsedMillis = 12_345L,
        bootCount = 7,
        lastSeenWallMillis = 1_700_000_000_500L,
    )
    private val finished = FinishedSession(
        id = "s2",
        mode = FocusMode.FullFocus,
        appCount = 0,
        plannedMinutes = 30,
        startedAtWallMillis = 10L,
        endedAtWallMillis = 20L,
        activeMillis = 10L,
        outcome = SessionOutcome.Interrupted,
        reason = EndReason.PermissionLost,
    )

    private fun roundTrip(state: SessionState) = SessionStateCodec.decode(SessionStateCodec.encode(state))

    @Test
    fun everyPersistedState_roundTrips() {
        listOf(
            SessionState.Idle,
            SessionState.Starting("s1", plan, SessionSource.Manual),
            SessionState.Active(running),
            SessionState.Active(running.copy(bootCount = null, source = SessionSource.Manual)),
            SessionState.RecoveryPending(running, requestedAtWallMillis = 99L),
            SessionState.Completed(finished.copy(outcome = SessionOutcome.Completed, reason = null)),
            SessionState.Interrupted(finished),
            SessionState.Failed(finished.copy(outcome = SessionOutcome.Failed, reason = EndReason.DeviceUnsupported)),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun inMemoryOnlyStates_areStoredAsIdle() {
        assertEquals(SessionState.Idle, roundTrip(SessionState.Configuring))
        assertEquals(SessionState.Idle, roundTrip(SessionState.Restoring))
    }

    @Test
    fun emptyPackageSet_roundTrips() {
        val state = SessionState.Active(running.copy(plan = SessionPlan(FocusMode.FullFocus, emptySet(), 60)))
        assertEquals(state, roundTrip(state))
    }

    @Test
    fun damagedInput_decodesToNull() {
        assertNull(SessionStateCodec.decode(""))
        assertNull(SessionStateCodec.decode("garbage"))
        assertNull(SessionStateCodec.decode("v=2\nkind=idle"))
        assertNull(SessionStateCodec.decode("v=1\nkind=unknown"))
        val good = SessionStateCodec.encode(SessionState.Active(running))
        assertNull(SessionStateCodec.decode(good.replace("endWall=", "endWallX=")))
        assertNull(SessionStateCodec.decode(good.replace("mode=SelectedApps", "mode=Nope")))
        assertNull(SessionStateCodec.decode(good.replace(Regex("durationMs=\\d+"), "durationMs=abc")))
    }

    @Test
    fun inconsistentFinishedRecord_decodesToNull() {
        val text = SessionStateCodec.encode(SessionState.Interrupted(finished)).replace("reason=PermissionLost", "reason=")
        assertNull(SessionStateCodec.decode(text))
    }
}
