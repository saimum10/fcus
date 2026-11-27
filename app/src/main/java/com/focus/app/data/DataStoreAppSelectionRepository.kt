package com.focus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focus.app.domain.AppSelectionRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Its own file, separate from the onboarding flag: one DataStore instance per file.
private val Context.appSelectionStore: DataStore<Preferences> by preferencesDataStore(name = "app_selection")

/**
 * Local-only storage of the chosen package names. A small string set is all this phase
 * needs, so no database is added; Room arrives with session history in Phase 4.
 * The constructor takes a [DataStore] so tests can use a temporary file.
 */
class DataStoreAppSelectionRepository(private val store: DataStore<Preferences>) : AppSelectionRepository {

    constructor(context: Context) : this(context.applicationContext.appSelectionStore)

    override val selectedPackages: Flow<Set<String>> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[KEY_SELECTED] ?: emptySet() }

    override suspend fun setSelected(packageName: String, selected: Boolean) {
        store.edit { prefs ->
            val current = prefs[KEY_SELECTED] ?: emptySet()
            write(prefs, if (selected) current + packageName else current - packageName)
        }
    }

    override suspend fun retainOnly(allowed: Set<String>) {
        store.edit { prefs ->
            val current = prefs[KEY_SELECTED] ?: emptySet()
            val kept = current.intersect(allowed)
            if (kept.size != current.size) write(prefs, kept)
        }
    }

    override suspend fun clear() {
        store.edit { prefs -> prefs.remove(KEY_SELECTED) }
    }

    private fun write(prefs: MutablePreferences, value: Set<String>) {
        if (value.isEmpty()) prefs.remove(KEY_SELECTED) else prefs[KEY_SELECTED] = value
    }

    private companion object {
        val KEY_SELECTED = stringSetPreferencesKey("selected_packages")
    }
}
