package com.focus.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.focus.app.domain.InterruptionSnapshot
import com.focus.app.domain.RestrictionJournalEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The write-ahead journal against a real DataStore file on the JVM. Written in Phase 5, run in Phase 8. */
class DataStoreRestrictionJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun <T> withJournal(block: suspend (DataStoreRestrictionJournal) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "journal.preferences_pb") }
            block(DataStoreRestrictionJournal(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun startsEmpty() {
        withJournal { assertNull(it.load()) }
    }

    @Test
    fun saveAndLoad() {
        withJournal { journal ->
            val entry = RestrictionJournalEntry(setOf("com.a", "com.b"), lockTaskSet = true, interruption = InterruptionSnapshot(1, 2, 3, 4, 5, 6))
            journal.save(entry)
            assertEquals(entry, journal.load())
        }
    }

    @Test
    fun saveReplacesThePreviousEntry() {
        withJournal { journal ->
            journal.save(RestrictionJournalEntry(setOf("com.a"), true, InterruptionSnapshot(1, 2, 3, 4, 5, 6)))
            journal.save(RestrictionJournalEntry(setOf("com.b")))
            assertEquals(RestrictionJournalEntry(setOf("com.b")), journal.load())
        }
    }

    @Test
    fun clearEmptiesIt() {
        withJournal { journal ->
            journal.save(RestrictionJournalEntry(setOf("com.a")))
            journal.clear()
            assertNull(journal.load())
        }
    }

    @Test
    fun uninstallBlockedFlag_survivesSaveAndLoad() {
        withJournal { journal ->
            journal.save(RestrictionJournalEntry(emptySet(), uninstallBlocked = true))
            assertEquals(RestrictionJournalEntry(emptySet(), uninstallBlocked = true), journal.load())
        }
    }
}
