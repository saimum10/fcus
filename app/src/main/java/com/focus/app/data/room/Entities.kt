package com.focus.app.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One finished session. Enums are stored by name so the schema survives reordering.
 * The index on [endedAtMillis] serves the newest-first list and the statistics window.
 */
@Entity(tableName = "session_history", indices = [Index(value = ["endedAtMillis"])])
data class SessionHistoryEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val appCount: Int,
    val plannedMinutes: Int,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val activeMillis: Long,
    val outcome: String,
    val reason: String?,
)

/** A recurring schedule. [daysMask] has bit 0 = Monday ... bit 6 = Sunday. */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val daysMask: Int,
    val startMinuteOfDay: Int,
    val durationMinutes: Int,
    val enabled: Boolean,
    val lastStartedWindowStartMillis: Long,
)
