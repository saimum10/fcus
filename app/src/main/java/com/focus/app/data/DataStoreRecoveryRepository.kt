package com.focus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focus.app.domain.PinCredential
import com.focus.app.domain.RecoveryConfig
import com.focus.app.domain.RecoveryRecord
import com.focus.app.domain.RecoveryRepository
import com.focus.app.domain.TrustedPerson
import com.focus.app.domain.WaitTimer
import java.io.IOException
import kotlinx.coroutines.flow.first

private val Context.recoveryStore: DataStore<Preferences> by preferencesDataStore(name = "recovery")

/**
 * The trusted person and the wrong-PIN counter, as one atomic DataStore value set. Local only.
 * It holds the person's name, an optional phone number, a salted PBKDF2 hash of the PIN and
 * two timers; never the PIN itself. The constructor takes a [DataStore] so tests can use a temp file.
 *
 * A record that is partly there or cannot be decoded is reported as unreadable (IOException)
 * instead of being treated as "no trusted person": silently dropping a PIN would weaken the
 * commitment, and the screens say plainly that the data cannot be read.
 */
class DataStoreRecoveryRepository(private val store: DataStore<Preferences>) : RecoveryRepository {

    constructor(context: Context) : this(context.applicationContext.recoveryStore)

    override suspend fun load(): RecoveryRecord {
        val prefs = store.data.first() // an unreadable file throws IOException
        val name = prefs[KEY_NAME] ?: return RecoveryRecord()

        val iterations = prefs[KEY_ITERATIONS]
        val salt = prefs[KEY_SALT]
        val hash = prefs[KEY_HASH]
        val present = listOf(iterations != null, salt != null, hash != null).count { it }
        val credential = when (present) {
            0 -> null
            3 -> PinCredential(iterations!!, salt!!, hash!!)
            else -> throw IOException("Stored PIN is incomplete")
        }

        val removalWait = prefs[KEY_REMOVAL]?.let { WaitTimer.fromStorage(it) ?: throw IOException("Stored removal timer is damaged") }
        val lock = prefs[KEY_LOCK]?.let { WaitTimer.fromStorage(it) ?: throw IOException("Stored lock timer is damaged") }
        val failed = prefs[KEY_FAILED] ?: 0

        return RecoveryRecord(
            config = RecoveryConfig(TrustedPerson(name, prefs[KEY_PHONE]), credential, removalWait),
            failedAttempts = failed,
            lock = lock,
        )
    }

    override suspend fun save(record: RecoveryRecord) {
        store.edit { prefs ->
            prefs.clear()
            val config = record.config ?: return@edit
            prefs[KEY_NAME] = config.person.name
            config.person.phone?.let { prefs[KEY_PHONE] = it }
            config.credential?.let {
                prefs[KEY_ITERATIONS] = it.iterations
                prefs[KEY_SALT] = it.saltHex
                prefs[KEY_HASH] = it.hashHex
            }
            config.removalWait?.let { prefs[KEY_REMOVAL] = it.toStorage() }
            prefs[KEY_FAILED] = record.failedAttempts
            record.lock?.let { prefs[KEY_LOCK] = it.toStorage() }
        }
    }

    private companion object {
        val KEY_NAME = stringPreferencesKey("person_name")
        val KEY_PHONE = stringPreferencesKey("person_phone")
        val KEY_ITERATIONS = intPreferencesKey("pin_iterations")
        val KEY_SALT = stringPreferencesKey("pin_salt")
        val KEY_HASH = stringPreferencesKey("pin_hash")
        val KEY_REMOVAL = stringPreferencesKey("removal_wait")
        val KEY_LOCK = stringPreferencesKey("attempt_lock")
        val KEY_FAILED = intPreferencesKey("failed_attempts")
    }
}
