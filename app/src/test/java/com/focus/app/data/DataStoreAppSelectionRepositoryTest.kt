package com.focus.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * App-selection persistence against a real DataStore file on the JVM (no Android needed).
 * Written in Phase 3, run in Phase 8.
 */
class DataStoreAppSelectionRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Opens the store on one shared file and closes it fully before returning. */
    private fun <T> withRepository(block: suspend (DataStoreAppSelectionRepository) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                File(folder.root, "selection.preferences_pb")
            }
            block(DataStoreAppSelectionRepository(store))
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun startsEmpty() {
        withRepository { assertEquals(emptySet<String>(), it.selectedPackages.first()) }
    }

    @Test
    fun selectAndDeselect() {
        withRepository { repo ->
            repo.setSelected("com.a", true)
            repo.setSelected("com.b", true)
            assertEquals(setOf("com.a", "com.b"), repo.selectedPackages.first())
            repo.setSelected("com.a", false)
            assertEquals(setOf("com.b"), repo.selectedPackages.first())
        }
    }

    @Test
    fun selectingTwice_isIdempotent() {
        withRepository { repo ->
            repo.setSelected("com.a", true)
            repo.setSelected("com.a", true)
            assertEquals(setOf("com.a"), repo.selectedPackages.first())
        }
    }

    @Test
    fun selectionSurvivesReopeningTheStore() {
        withRepository { it.setSelected("com.a", true) }
        withRepository { assertEquals(setOf("com.a"), it.selectedPackages.first()) }
    }

    @Test
    fun retainOnly_dropsUninstalledOrProtectedApps() {
        withRepository { repo ->
            repo.setSelected("com.keep", true)
            repo.setSelected("com.gone", true)
            repo.retainOnly(setOf("com.keep", "com.other"))
            assertEquals(setOf("com.keep"), repo.selectedPackages.first())
        }
    }

    @Test
    fun retainOnly_withEmptyAllowedSet_clearsSelection() {
        withRepository { repo ->
            repo.setSelected("com.a", true)
            repo.retainOnly(emptySet())
            assertEquals(emptySet<String>(), repo.selectedPackages.first())
        }
    }

    @Test
    fun clear_removesEverything() {
        withRepository { repo ->
            repo.setSelected("com.a", true)
            repo.setSelected("com.b", true)
            repo.clear()
            assertEquals(emptySet<String>(), repo.selectedPackages.first())
        }
    }
}
