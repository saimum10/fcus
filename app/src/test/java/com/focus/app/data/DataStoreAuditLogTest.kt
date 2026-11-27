package com.focus.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.focus.app.domain.AuditEvent
import com.focus.app.domain.AuditType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The audit log against a real DataStore file on the JVM. Written in Phase 6, run in Phase 8. */
class DataStoreAuditLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun <T> withLog(block: suspend (DataStoreAuditLog) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "audit.preferences_pb") }
            block(DataStoreAuditLog(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private fun event(n: Int, type: AuditType = AuditType.RecoveryFailed, session: String? = null) =
        AuditEvent("e$n", 1_000L * n, type, session)

    @Test
    fun startsEmpty() {
        withLog { log -> assertTrue(log.observeRecent(10).first().isEmpty()) }
    }

    @Test
    fun newestComesFirst() {
        withLog { log ->
            log.append(event(1, AuditType.TrustedPersonSet))
            log.append(event(2, AuditType.RecoveryRequested, "s1"))
            log.append(event(3, AuditType.RecoverySucceeded, "s1"))
            assertEquals(
                listOf(event(3, AuditType.RecoverySucceeded, "s1"), event(2, AuditType.RecoveryRequested, "s1"), event(1, AuditType.TrustedPersonSet)),
                log.observeRecent(10).first(),
            )
        }
    }

    @Test
    fun limitKeepsTheNewest() {
        withLog { log ->
            (1..5).forEach { log.append(event(it)) }
            assertEquals(listOf("e5", "e4"), log.observeRecent(2).first().map { it.id })
        }
    }

    @Test
    fun onlyTheLast200AreKept() {
        withLog { log ->
            (1..DataStoreAuditLog.MAX_EVENTS + 25).forEach { log.append(event(it)) }
            val all = log.observeRecent(1_000).first()
            assertEquals(DataStoreAuditLog.MAX_EVENTS, all.size)
            assertEquals("e${DataStoreAuditLog.MAX_EVENTS + 25}", all.first().id)
            assertEquals("e26", all.last().id)
        }
    }

    @Test
    fun clearEmptiesIt() {
        withLog { log ->
            log.append(event(1))
            log.clear()
            assertTrue(log.observeRecent(10).first().isEmpty())
        }
    }

    @Test
    fun everyTypeSurvivesTheRoundTrip() {
        withLog { log ->
            AuditType.entries.forEachIndexed { i, type -> log.append(event(i, type)) }
            assertEquals(AuditType.entries.reversed(), log.observeRecent(1_000).first().map { it.type })
        }
    }
}
