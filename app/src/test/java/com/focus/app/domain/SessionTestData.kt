package com.focus.app.domain

/** Shared builders for the session tests. */
internal object SessionTestData {
    const val MIN = 60_000L

    fun plan(
        mode: FocusMode = FocusMode.FullFocus,
        packages: Set<String> = emptySet(),
        minutes: Int = 60,
    ) = SessionPlan(mode, packages, minutes)

    /** Started at wall = 1_000_000, elapsed = 5_000, boot #3, 60 minutes long. */
    fun running(
        durationMillis: Long = 60 * MIN,
        startedWall: Long = 1_000_000L,
        startedElapsed: Long = 5_000L,
        bootCount: Int? = 3,
        lastSeenWall: Long = startedWall,
        plan: SessionPlan = plan(minutes = (durationMillis / MIN).toInt()),
        id: String = "s1",
    ) = RunningSession(
        id = id,
        plan = plan,
        source = SessionSource.Manual,
        startedAtWallMillis = startedWall,
        endAtWallMillis = startedWall + durationMillis,
        durationMillis = durationMillis,
        startedAtElapsedMillis = startedElapsed,
        bootCount = bootCount,
        lastSeenWallMillis = lastSeenWall,
    )

    fun time(wall: Long, elapsed: Long, boot: Int? = 3) = TimeSnapshot(wall, elapsed, boot)
}
