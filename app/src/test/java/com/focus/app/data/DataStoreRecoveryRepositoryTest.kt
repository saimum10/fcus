package com.focus.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.focus.app.domain.PinCredential
import com.focus.app.domain.RecoveryConfig
import com.focus.app.domain.RecoveryRecord
import com.focus.app.domain.TrustedPerson
import com.focus.app.domain.WaitTimer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The recovery record against a real DataStore file on the JVM. Written in Phase 6, run in Phase 8. */
class DataStoreRecoveryRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun <T> withRepository(
        block: suspend (DataStoreRecoveryRepository, DataStore<Preferences>) -> T,
    ): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "recovery.preferences_pb") }
            block(DataStoreRecoveryRepository(store), store)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private val credential = PinCredential(310_000, "00ff10", "ab12cd")
    private val lock = WaitTimer(1L, 2L, 3, 60_000L)
    private val removal = WaitTimer(10L, 20L, null, 86_400_000L)

    @Test
    fun startsEmpty() {
        withRepository { repo, _ -> assertEquals(RecoveryRecord(), repo.load()) }
    }

    @Test
    fun saveAndLoad_everything() {
        withRepository { repo, _ ->
            val record = RecoveryRecord(
                config = RecoveryConfig(TrustedPerson("Sam", "+442079460958"), credential, removal),
                failedAttempts = 4,
                lock = lock,
            )
            repo.save(record)
            assertEquals(record, repo.load())
        }
    }

    @Test
    fun saveAndLoad_personWithoutPhoneAndWithoutPin() {
        withRepository { repo, _ ->
            val record = RecoveryRecord(config = RecoveryConfig(TrustedPerson("Sam", null), credential = null))
            repo.save(record)
            assertEquals(record, repo.load())
        }
    }

    @Test
    fun saveReplacesEverythingThatWasThere() {
        withRepository { repo, _ ->
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Sam", "5551234"), credential, removal), 2, lock))
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Alex", null), null)))
            assertEquals(RecoveryRecord(RecoveryConfig(TrustedPerson("Alex", null), null)), repo.load())
        }
    }

    @Test
    fun savingAnEmptyRecordClearsTheStore() {
        withRepository { repo, store ->
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Sam", null), credential)))
            repo.save(RecoveryRecord())
            assertEquals(RecoveryRecord(), repo.load())
            assertTrue(store.data.first().asMap().isEmpty())
        }
    }

    @Test
    fun theStoredValuesNeverContainAPin() {
        withRepository { repo, store ->
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Sam", null), credential)))
            val values = store.data.first().asMap().values.map { it.toString() }
            assertFalse(values.any { it.contains("482915") })
        }
    }

    @Test
    fun anIncompletePinIsReportedAsUnreadable_notAsNoPin() {
        withRepository { repo, store ->
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Sam", null), credential)))
            store.edit { it.remove(stringPreferencesKey("pin_hash")) }
            try {
                repo.load()
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun aDamagedTimerIsReportedAsUnreadable() {
        withRepository { repo, store ->
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Sam", null), credential), 3, lock))
            store.edit { it[stringPreferencesKey("attempt_lock")] = "garbage" }
            try {
                repo.load()
                fail("expected IOException")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun theCounterIsKeptNextToThePerson() {
        withRepository { repo, store ->
            repo.save(RecoveryRecord(RecoveryConfig(TrustedPerson("Sam", null), credential), failedAttempts = 5))
            assertEquals(5, repo.load().failedAttempts)
            assertEquals(5, store.data.first()[intPreferencesKey("failed_attempts")])
        }
    }
}
