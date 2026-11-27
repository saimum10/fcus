package com.focus.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionHistoryEntity)

    @Query("SELECT * FROM session_history ORDER BY endedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionHistoryEntity>>

    @Query("SELECT * FROM session_history WHERE endedAtMillis >= :sinceMillis ORDER BY endedAtMillis DESC")
    fun observeEndedSince(sinceMillis: Long): Flow<List<SessionHistoryEntity>>

    /** One small row per outcome; the database does the counting, no session rows are loaded. */
    @Query(
        "SELECT outcome AS outcome, COUNT(*) AS sessions, COALESCE(SUM(activeMillis), 0) AS activeMillis " +
            "FROM session_history GROUP BY outcome",
    )
    fun observeOutcomeTotals(): Flow<List<OutcomeTotalRow>>

    @Query("DELETE FROM session_history")
    suspend fun clear()
}

/** Result row of [SessionHistoryDao.observeOutcomeTotals]. */
data class OutcomeTotalRow(
    val outcome: String,
    val sessions: Int,
    val activeMillis: Long,
)

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY startMinuteOfDay, id")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules ORDER BY startMinuteOfDay, id")
    suspend fun getAll(): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE schedules SET lastStartedWindowStartMillis = :windowStartMillis WHERE id = :id")
    suspend fun markWindowStarted(id: String, windowStartMillis: Long)
}
