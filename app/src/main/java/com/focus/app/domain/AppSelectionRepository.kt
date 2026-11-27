package com.focus.app.domain

import kotlinx.coroutines.flow.Flow

/** Persists which apps the person chose for "Selected apps". Local storage only. */
interface AppSelectionRepository {
    val selectedPackages: Flow<Set<String>>

    suspend fun setSelected(packageName: String, selected: Boolean)

    /** Removes every stored package that is not in [allowed] (uninstalled or now protected apps). */
    suspend fun retainOnly(allowed: Set<String>)

    suspend fun clear()
}
