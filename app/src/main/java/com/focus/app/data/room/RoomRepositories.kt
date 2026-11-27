package com.focus.app.data.room

import com.focus.app.domain.FinishedSession
import com.focus.app.domain.HistoryTotals
import com.focus.app.domain.ScheduleEntry
import com.focus.app.domain.ScheduleRepository
import com.focus.app.domain.SessionHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSessionHistoryRepository(private val dao: SessionHistoryDao) : SessionHistoryRepository {
    override suspend fun add(entry: FinishedSession) = dao.insert(entry.toEntity())

    override fun observeRecent(limit: Int): Flow<List<FinishedSession>> =
        dao.observeRecent(limit).map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override fun observeTotals(): Flow<HistoryTotals> = dao.observeOutcomeTotals().map { it.toTotals() }

    override fun observeEndedSince(sinceMillis: Long): Flow<List<FinishedSession>> =
        dao.observeEndedSince(sinceMillis).map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override suspend fun clear() = dao.clear()
}

class RoomScheduleRepository(private val dao: ScheduleDao) : ScheduleRepository {
    override fun observeAll(): Flow<List<ScheduleEntry>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getAll(): List<ScheduleEntry> = dao.getAll().map { it.toDomain() }

    override suspend fun upsert(entry: ScheduleEntry) = dao.upsert(entry.toEntity())

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun markWindowStarted(id: String, windowStartMillis: Long) =
        dao.markWindowStarted(id, windowStartMillis)
}
