package com.focus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focus.app.domain.SessionState
import com.focus.app.domain.SessionStore
import kotlinx.coroutines.flow.first

private val Context.sessionStateStore: DataStore<Preferences> by preferencesDataStore(name = "session_state")

/**
 * The current session state as one atomic DataStore value (so a crash can never leave a
 * half-written session). Local only. The constructor takes a [DataStore] so tests can use a temp file.
 */
class DataStoreSessionStore(private val store: DataStore<Preferences>) : SessionStore {

    constructor(context: Context) : this(context.applicationContext.sessionStateStore)

    override suspend fun load(): SessionState? {
        // An unreadable file throws IOException; the engine reports that instead of guessing.
        val text = store.data.first()[KEY_STATE] ?: return null
        return SessionStateCodec.decode(text)
    }

    override suspend fun save(state: SessionState) {
        store.edit { prefs -> prefs[KEY_STATE] = SessionStateCodec.encode(state) }
    }

    private companion object {
        val KEY_STATE = stringPreferencesKey("state")
    }
}
