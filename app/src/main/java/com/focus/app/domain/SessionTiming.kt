package com.focus.app.domain

/** Remaining-time math for a [RunningSession]. Pure, so clock and reboot cases are unit tested. */
object SessionTiming {
    /** Wall-clock drift smaller than this is not worth saving again. */
    const val CLOCK_TOLERANCE_MS: Long = 2_000L

    /** How often the "last seen" wall time is refreshed in storage. */
    const val CHECKPOINT_MS: Long = 60_000L

    /** True if the device has not restarted since the session's monotonic anchor was set. */
    fun sameBoot(session: RunningSession, now: TimeSnapshot): Boolean {
        val anchor = session.bootCount
        val current = now.bootCount
        return if (anchor != null && current != null) {
            anchor == current
        } else {
            // No boot counter: the monotonic clock only goes backwards across a restart.
            now.elapsedMillis >= session.startedAtElapsedMillis
        }
    }

    /**
     * Time left, in `[0, durationMillis]`. Within one boot it comes from the monotonic
     * clock, so changing the wall clock or time zone cannot shorten or extend it. After a
     * restart it comes from the wall clock, never earlier than the last time fOCUS saw it,
     * and never more than the full duration.
     */
    fun remainingMillis(session: RunningSession, now: TimeSnapshot): Long {
        val raw = if (sameBoot(session, now)) {
            session.durationMillis - (now.elapsedMillis - session.startedAtElapsedMillis)
        } else {
            session.endAtWallMillis - maxOf(now.wallMillis, session.lastSeenWallMillis)
        }
        return raw.coerceIn(0L, session.durationMillis)
    }

    fun elapsedMillis(session: RunningSession, now: TimeSnapshot): Long =
        session.durationMillis - remainingMillis(session, now)

    fun isExpired(session: RunningSession, now: TimeSnapshot): Boolean = remainingMillis(session, now) == 0L

    /**
     * True if the stored anchors should be refreshed: after a restart (new monotonic
     * anchor needed), when the wall clock moved relative to the monotonic clock, or when
     * the "last seen" checkpoint is old.
     */
    fun needsRebase(session: RunningSession, now: TimeSnapshot): Boolean {
        if (!sameBoot(session, now)) return true
        val remaining = remainingMillis(session, now)
        val drift = kotlin.math.abs((now.wallMillis + remaining) - session.endAtWallMillis)
        return drift > CLOCK_TOLERANCE_MS || now.wallMillis - session.lastSeenWallMillis > CHECKPOINT_MS
    }

    /**
     * Re-anchors the session at [now] without changing how much time is left: the
     * monotonic anchor and boot counter move to the current boot, and the displayed end
     * time is recomputed from the clock. Call this after a restart or a clock change.
     */
    fun rebased(session: RunningSession, now: TimeSnapshot): RunningSession {
        val remaining = remainingMillis(session, now)
        val sameBoot = sameBoot(session, now)
        val wall = if (sameBoot) now.wallMillis else maxOf(now.wallMillis, session.lastSeenWallMillis)
        return session.copy(
            startedAtElapsedMillis = now.elapsedMillis - (session.durationMillis - remaining),
            bootCount = now.bootCount,
            endAtWallMillis = wall + remaining,
            lastSeenWallMillis = wall,
        )
    }
}
