package com.focus.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.focus.app.domain.OnboardingRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.focusPreferences: DataStore<Preferences> by preferencesDataStore(name = "focus_preferences")

/** Local-only storage of the onboarding flag. Nothing leaves the device. */
class DataStoreOnboardingRepository(context: Context) : OnboardingRepository {
    private val store = context.applicationContext.focusPreferences

    override val completed: Flow<Boolean> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { prefs -> prefs[KEY_COMPLETED] ?: false }

    override suspend fun markCompleted() {
        store.edit { prefs -> prefs[KEY_COMPLETED] = true }
    }

    private companion object {
        val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
