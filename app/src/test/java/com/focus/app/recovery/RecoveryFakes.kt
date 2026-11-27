package com.focus.app.recovery

import com.focus.app.domain.AuditEvent
import com.focus.app.domain.AuditLog
import com.focus.app.domain.PinCredential
import com.focus.app.domain.PinHasher
import com.focus.app.domain.RecoveryRecord
import com.focus.app.domain.RecoveryRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory fakes for the recovery tests. */

class FakeRecoveryRepository(var record: RecoveryRecord = RecoveryRecord()) : RecoveryRepository {
    var failLoad = false
    var failSave = false
    var saves = 0

    override suspend fun load(): RecoveryRecord {
        if (failLoad) throw IOException("load")
        return record
    }

    override suspend fun save(record: RecoveryRecord) {
        if (failSave) throw IOException("save")
        saves++
        this.record = record
    }
}

class FakeAuditLog : AuditLog {
    val events = mutableListOf<AuditEvent>()
    var failAppend = false

    override suspend fun append(event: AuditEvent) {
        if (failAppend) throw IOException("append")
        events += event
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = flowOf(events.reversed().take(limit))

    override suspend fun clear() {
        events.clear()
    }
}

/** Instant, readable "hash" so the service logic is tested without PBKDF2's cost. The real hasher has its own test. */
class FakeHasher : PinHasher {
    override fun hash(pin: String) = PinCredential(1, "00", "h$pin")

    override fun verify(pin: String, credential: PinCredential) = credential.hashHex == "h$pin"
}
