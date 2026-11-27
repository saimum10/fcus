package com.focus.app.data

import com.focus.app.domain.EndReason
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.FocusMode
import com.focus.app.domain.RunningSession
import com.focus.app.domain.SessionOutcome
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.SessionSource
import com.focus.app.domain.SessionState
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Text form of a [SessionState] for DataStore. Plain `key=value` lines via
 * `java.util.Properties` (escaping included), so it needs no extra library and runs in
 * plain JVM tests. [decode] returns null for anything unreadable; the engine then
 * starts from Idle, which is the safe direction (nothing stays restricted).
 *
 * In-memory-only states (Restoring, Configuring) are stored as Idle.
 */
object SessionStateCodec {
    private const val VERSION = "1"

    fun encode(state: SessionState): String {
        val p = Properties()
        p.setProperty("v", VERSION)
        when (state) {
            SessionState.Idle, SessionState.Restoring, SessionState.Configuring -> p.setProperty("kind", "idle")
            is SessionState.Starting -> {
                p.setProperty("kind", "starting")
                p.setProperty("id", state.id)
                putPlan(p, state.plan)
                putSource(p, state.source)
            }
            is SessionState.Active -> {
                p.setProperty("kind", "active")
                putRunning(p, state.session)
            }
            is SessionState.RecoveryPending -> {
                p.setProperty("kind", "recovery")
                p.setProperty("recoveryRequestedAt", state.requestedAtWallMillis.toString())
                putRunning(p, state.session)
            }
            is SessionState.Completed -> putFinished(p, "completed", state.result)
            is SessionState.Interrupted -> putFinished(p, "interrupted", state.result)
            is SessionState.Failed -> putFinished(p, "failed", state.result)
        }
        return StringWriter().also { p.store(it, null) }.toString()
    }

    fun decode(text: String): SessionState? = try {
        val p = Properties().apply { load(StringReader(text)) }
        if (p.getProperty("v") != VERSION) {
            null
        } else {
            when (p.getProperty("kind")) {
                "idle" -> SessionState.Idle
                "starting" -> SessionState.Starting(p.str("id"), readPlan(p), readSource(p))
                "active" -> SessionState.Active(readRunning(p))
                "recovery" -> SessionState.RecoveryPending(readRunning(p), p.long("recoveryRequestedAt"))
                "completed" -> SessionState.Completed(readFinished(p))
                "interrupted" -> SessionState.Interrupted(readFinished(p))
                "failed" -> SessionState.Failed(readFinished(p))
                else -> null
            }
        }
    } catch (_: IllegalArgumentException) {
        // Missing key, bad number, unknown enum name, or a failed invariant.
        null
    }

    // ---- write -------------------------------------------------------------------------

    private fun putPlan(p: Properties, plan: SessionPlan) {
        p.setProperty("mode", plan.mode.name)
        p.setProperty("packages", plan.packages.sorted().joinToString(","))
        p.setProperty("plannedMinutes", plan.durationMinutes.toString())
    }

    private fun putSource(p: Properties, source: SessionSource) {
        when (source) {
            SessionSource.Manual -> p.setProperty("source", "manual")
            is SessionSource.Scheduled -> {
                p.setProperty("source", "scheduled")
                p.setProperty("scheduleId", source.scheduleId)
                p.setProperty("windowStart", source.windowStartMillis.toString())
            }
        }
    }

    private fun putRunning(p: Properties, s: RunningSession) {
        p.setProperty("id", s.id)
        putPlan(p, s.plan)
        putSource(p, s.source)
        p.setProperty("startedWall", s.startedAtWallMillis.toString())
        p.setProperty("endWall", s.endAtWallMillis.toString())
        p.setProperty("durationMs", s.durationMillis.toString())
        p.setProperty("startedElapsed", s.startedAtElapsedMillis.toString())
        p.setProperty("bootCount", s.bootCount?.toString() ?: "")
        p.setProperty("lastSeenWall", s.lastSeenWallMillis.toString())
    }

    private fun putFinished(p: Properties, kind: String, r: FinishedSession) {
        p.setProperty("kind", kind)
        p.setProperty("id", r.id)
        p.setProperty("mode", r.mode.name)
        p.setProperty("appCount", r.appCount.toString())
        p.setProperty("plannedMinutes", r.plannedMinutes.toString())
        p.setProperty("startedWall", r.startedAtWallMillis.toString())
        p.setProperty("endedWall", r.endedAtWallMillis.toString())
        p.setProperty("activeMs", r.activeMillis.toString())
        p.setProperty("outcome", r.outcome.name)
        p.setProperty("reason", r.reason?.name ?: "")
    }

    // ---- read --------------------------------------------------------------------------

    private fun readPlan(p: Properties): SessionPlan = SessionPlan(
        mode = FocusMode.valueOf(p.str("mode")),
        packages = p.getProperty("packages").orEmpty().split(",").filter { it.isNotEmpty() }.toSet(),
        durationMinutes = p.int("plannedMinutes"),
    )

    private fun readSource(p: Properties): SessionSource = when (p.str("source")) {
        "manual" -> SessionSource.Manual
        "scheduled" -> SessionSource.Scheduled(p.str("scheduleId"), p.long("windowStart"))
        else -> throw IllegalArgumentException("source")
    }

    private fun readRunning(p: Properties) = RunningSession(
        id = p.str("id"),
        plan = readPlan(p),
        source = readSource(p),
        startedAtWallMillis = p.long("startedWall"),
        endAtWallMillis = p.long("endWall"),
        durationMillis = p.long("durationMs"),
        startedAtElapsedMillis = p.long("startedElapsed"),
        bootCount = p.getProperty("bootCount").orEmpty().takeIf { it.isNotEmpty() }?.toInt(),
        lastSeenWallMillis = p.long("lastSeenWall"),
    )

    private fun readFinished(p: Properties) = FinishedSession(
        id = p.str("id"),
        mode = FocusMode.valueOf(p.str("mode")),
        appCount = p.int("appCount"),
        plannedMinutes = p.int("plannedMinutes"),
        startedAtWallMillis = p.long("startedWall"),
        endedAtWallMillis = p.long("endedWall"),
        activeMillis = p.long("activeMs"),
        outcome = SessionOutcome.valueOf(p.str("outcome")),
        reason = p.getProperty("reason").orEmpty().takeIf { it.isNotEmpty() }?.let { EndReason.valueOf(it) },
    )

    private fun Properties.str(key: String): String =
        getProperty(key) ?: throw IllegalArgumentException("missing $key")

    // NumberFormatException is an IllegalArgumentException.
    private fun Properties.long(key: String): Long = str(key).toLong()

    private fun Properties.int(key: String): Int = str(key).toInt()
}
