package com.focus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focus.app.domain.AuditEvent
import com.focus.app.domain.AuditLog
import com.focus.app.domain.AuditType
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.auditStore: DataStore<Preferences> by preferencesDataStore(name = "audit_log")

/**
 * The local audit log: one line per event (id, time, type, session id), newest [MAX_EVENTS]
 * kept, as one atomic DataStore value. It records that something happened and when, never a
 * PIN, a name or a phone number. Emergency calls and the emergency screen are deliberately
 * not recorded at all. The constructor takes a [DataStore] so tests can use a temp file.
 */
class DataStoreAuditLog(private val store: DataStore<Preferences>) : AuditLog {

    constructor(context: Context) : this(context.applicationContext.auditStore)

    override suspend fun append(event: AuditEvent) {
        store.edit { prefs ->
            val lines = (prefs[KEY_EVENTS] ?: "").lineSequence().filter { it.isNotBlank() }.toList()
            prefs[KEY_EVENTS] = (lines + encode(event)).takeLast(MAX_EVENTS).joinToString("\n")
        }
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEvent>> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs ->
            (prefs[KEY_EVENTS] ?: "").lineSequence().mapNotNull(::decode).toList().takeLast(limit).reversed()
        }

    override suspend fun clear() {
        store.edit { it.remove(KEY_EVENTS) }
    }

    private fun encode(event: AuditEvent): String =
        listOf(event.id, event.atWallMillis.toString(), event.type.name, event.sessionId.orEmpty()).joinToString(SEPARATOR)

    /** Null for a line that is not one [encode] wrote; such lines are skipped, never fatal. */
    private fun decode(line: String): AuditEvent? {
        val parts = line.split(SEPARATOR)
        if (parts.size != 4) return null
        val at = parts[1].toLongOrNull() ?: return null
        val type = AuditType.entries.firstOrNull { it.name == parts[2] } ?: return null
        return AuditEvent(parts[0], at, type, parts[3].ifEmpty { null })
    }

    companion object {
        const val MAX_EVENTS = 200
        private const val SEPARATOR = "\t"
        private val KEY_EVENTS = stringPreferencesKey("events")
    }
}
