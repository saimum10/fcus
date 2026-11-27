package com.focus.app.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focus.app.domain.AppIconSource
import com.focus.app.domain.AppListLogic
import com.focus.app.domain.AppSelectionRepository
import com.focus.app.domain.DiscoveredApp
import com.focus.app.domain.InstalledAppsSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AppListUiState {
    data object Loading : AppListUiState

    /** The installed-app list could not be read. Nothing is shown as selectable. */
    data object Failed : AppListUiState

    data class Loaded(
        val selectable: List<DiscoveredApp>,
        val protectedApps: List<DiscoveredApp>,
        /** Selected packages that are still installed and selectable. */
        val selected: Set<String>,
        /** Changes whenever the list was reloaded, so icons are fetched again. */
        val generation: Int,
        val saveFailed: Boolean,
    ) : AppListUiState
}

/**
 * Loads the app list while the screen is visible, reloads it (debounced) when apps are
 * installed, removed or updated, and keeps the persisted selection consistent with it:
 * uninstalled apps and apps that became protected are dropped from the selection.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AppSelectionViewModel(
    private val source: InstalledAppsSource,
    private val icons: AppIconSource,
    private val selection: AppSelectionRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val retryTrigger = MutableStateFlow(0)
    private val saveFailed = MutableStateFlow(false)
    private var generation = 0

    @Volatile
    private var knownSelectable: Set<String> = emptySet()

    private sealed interface Catalog {
        data object Failed : Catalog
        data class Ready(val apps: List<DiscoveredApp>, val generation: Int) : Catalog
    }

    /** null = loading. Restarts on retry; listens for package changes only while collected. */
    private val catalog: Flow<Catalog?> = retryTrigger.flatMapLatest {
        flow<Catalog?> {
            emit(null)
            emitAll(
                flow {
                    emit(Unit)
                    emitAll(source.changes().debounce(REFRESH_DEBOUNCE_MS))
                }.mapLatest { load() },
            )
        }
    }

    val uiState: StateFlow<AppListUiState> = combine(
        catalog,
        selection.selectedPackages,
        _query,
        saveFailed,
    ) { catalog, selected, query, saveFailed ->
        when (catalog) {
            null -> AppListUiState.Loading
            Catalog.Failed -> AppListUiState.Failed
            is Catalog.Ready -> {
                val (selectable, protectedApps) = AppListLogic.split(catalog.apps)
                AppListUiState.Loaded(
                    selectable = AppListLogic.filter(selectable, query),
                    protectedApps = AppListLogic.filter(protectedApps, query),
                    selected = selected.intersect(AppListLogic.selectablePackages(catalog.apps)),
                    generation = catalog.generation,
                    saveFailed = saveFailed,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AppListUiState.Loading)

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSelected(packageName: String, selected: Boolean) {
        // Protected or unknown apps can never be added, even if the UI were to offer them.
        if (selected && packageName !in knownSelectable) return
        persist { selection.setSelected(packageName, selected) }
    }

    fun clearSelection() = persist { selection.clear() }

    fun retry() {
        retryTrigger.value += 1
    }

    private suspend fun load(): Catalog {
        val apps = try {
            source.loadApps()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Catalog.Failed
        }
        val sorted = AppListLogic.sorted(apps)
        val selectable = AppListLogic.selectablePackages(sorted)
        knownSelectable = selectable
        icons.invalidate()
        // Every device has at least fOCUS itself in the list; an empty result means the
        // query did not work, so the stored selection is left alone rather than wiped.
        if (sorted.isNotEmpty()) {
            try {
                selection.retainOnly(selectable)
            } catch (_: IOException) {
                // Retried on the next refresh; the screen already hides stale entries.
            }
        }
        return Catalog.Ready(sorted, ++generation)
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                saveFailed.value = false
            } catch (_: IOException) {
                saveFailed.value = true
            }
        }
    }

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 500L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
