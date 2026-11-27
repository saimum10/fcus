package com.focus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focus.app.domain.InterruptionSnapshot
import com.focus.app.domain.RestrictionJournal
import com.focus.app.domain.RestrictionJournalEntry
import kotlinx.coroutines.flow.first

private val Context.restrictionJournalStore: DataStore<Preferences> by preferencesDataStore(name = "restriction_journal")

/**
 * The write-ahead record of what fOCUS may have changed on the device, as one atomic
 * DataStore value set. Local only; holds package names and the previous Do Not Disturb
 * numbers, nothing personal. The constructor takes a [DataStore] so tests can use a temp file.
 */
class DataStoreRestrictionJournal(private val store: DataStore<Preferences>) : RestrictionJournal {

    constructor(context: Context) : this(context.applicationContext.restrictionJournalStore)

    override suspend fun load(): RestrictionJournalEntry? {
        val prefs = store.data.first() // an unreadable file throws IOException; the caller refuses to change anything
        val entry = RestrictionJournalEntry(
            suspended = prefs[KEY_SUSPENDED] ?: emptySet(),
            lockTaskSet = prefs[KEY_LOCK_TASK] ?: false,
            interruption = prefs[KEY_INTERRUPTION]?.let { InterruptionSnapshot.fromStorage(it) },
            uninstallBlocked = prefs[KEY_UNINSTALL_BLOCKED] ?: false,
        )
        return if (entry.isEmpty) null else entry
    }

    override suspend fun save(entry: RestrictionJournalEntry) {
        store.edit { prefs ->
            prefs[KEY_SUSPENDED] = entry.suspended
            prefs[KEY_LOCK_TASK] = entry.lockTaskSet
            prefs[KEY_UNINSTALL_BLOCKED] = entry.uninstallBlocked
            val snapshot = entry.interruption
            if (snapshot == null) prefs.remove(KEY_INTERRUPTION) else prefs[KEY_INTERRUPTION] = snapshot.toStorage()
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private companion object {
        val KEY_SUSPENDED = stringSetPreferencesKey("suspended")
        val KEY_LOCK_TASK = booleanPreferencesKey("lock_task_set")
        val KEY_INTERRUPTION = stringPreferencesKey("interruption")
        val KEY_UNINSTALL_BLOCKED = booleanPreferencesKey("uninstall_blocked")
    }
}
