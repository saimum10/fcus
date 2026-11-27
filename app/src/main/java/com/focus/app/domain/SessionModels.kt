package com.focus.app.domain

/** Why a session ended other than by running out. Stored in history. */
enum class EndReason {
    /** A permission the restriction needed was withdrawn. */
    PermissionLost,

    /** The system or a device policy refused or removed the restriction. */
    PolicyFailure,

    /** This device (or this mode on it) cannot be enforced. */
    DeviceUnsupported,

    /** The restriction stopped working while the session was running. */
    EnforcementLost,

    /** Ended through the emergency / trusted-person recovery path (Phase 6). */
    RecoveryUsed,

    /** fOCUS could not keep its own state (storage error, interrupted start). */
    InternalError,
}

enum class SessionOutcome { Completed, Interrupted, Failed }

/** Who started a session. */
sealed interface SessionSource {
    data object Manual : SessionSource

    /** Started by a recurring schedule for the window that began at [windowStartMillis]. */
    data class Scheduled(val scheduleId: String, val windowStartMillis: Long) : SessionSource
}

/** What was asked for. [packages] is only used by Selected Apps. */
data class SessionPlan(
    val mode: FocusMode,
    val packages: Set<String>,
    val durationMinutes: Int,
)

/**
 * A session that is running. Time is tracked on two clocks:
 * - monotonic (`elapsedRealtime`), anchored by [startedAtElapsedMillis] and [bootCount]:
 *   immune to the person changing the wall clock or time zone while the device stays on;
 * - wall clock ([endAtWallMillis]): what is shown, and the only option after a restart.
 * [lastSeenWallMillis] is the latest wall time fOCUS saw; after a restart the clock is
 * never trusted to be earlier than it (some devices boot with an unsynced clock).
 * [durationMillis] is the real length; it is shorter than the plan for a schedule
 * window that was already under way.
 */
data class RunningSession(
    val id: String,
    val plan: SessionPlan,
    val source: SessionSource,
    val startedAtWallMillis: Long,
    val endAtWallMillis: Long,
    val durationMillis: Long,
    val startedAtElapsedMillis: Long,
    val bootCount: Int?,
    val lastSeenWallMillis: Long,
)

/** A finished session as shown in history. Also the payload of the three end states. */
data class FinishedSession(
    val id: String,
    val mode: FocusMode,
    val appCount: Int,
    val plannedMinutes: Int,
    val startedAtWallMillis: Long,
    val endedAtWallMillis: Long,
    /** How long restrictions were really in force. 0 if the session never started. */
    val activeMillis: Long,
    val outcome: SessionOutcome,
    /** Null exactly when [outcome] is Completed. */
    val reason: EndReason?,
) {
    init {
        require((outcome == SessionOutcome.Completed) == (reason == null)) {
            "A reason is required for interrupted or failed sessions, and only for them"
        }
    }
}

/** One reading of both clocks plus the boot counter (null when the device does not report it). */
data class TimeSnapshot(
    val wallMillis: Long,
    val elapsedMillis: Long,
    val bootCount: Int?,
)

/**
 * The session state machine (see [SessionMachine]). Only Starting, Active,
 * RecoveryPending and the three end states are ever persisted.
 */
sealed interface SessionState {
    /** Placeholder until stored state has been read. Never persisted; shown as "loading". */
    data object Restoring : SessionState

    data object Idle : SessionState

    /** The person is setting up a session. In memory only. */
    data object Configuring : SessionState

    /** Restrictions are being applied. Not yet shown as active. */
    data class Starting(val id: String, val plan: SessionPlan, val source: SessionSource) : SessionState

    data class Active(val session: RunningSession) : SessionState

    /** A recovery was requested (Phase 6, see RecoveryService); restrictions still apply until it completes. */
    data class RecoveryPending(val session: RunningSession, val requestedAtWallMillis: Long) : SessionState

    sealed interface Finished : SessionState {
        val result: FinishedSession
    }

    data class Completed(override val result: FinishedSession) : Finished

    data class Interrupted(override val result: FinishedSession) : Finished

    data class Failed(override val result: FinishedSession) : Finished
}

/** States from which a new session may be started or a new setup begun. */
val SessionState.allowsNewSession: Boolean
    get() = this is SessionState.Idle ||
        this is SessionState.Configuring ||
        this is SessionState.Finished
