package com.focus.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focus.app.domain.FocusStats
import com.focus.app.domain.SessionHistoryRepository
import com.focus.app.domain.observeFocusStats
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the statistics screen knows. */
sealed interface StatsUiState {
    data object Loading : StatsUiState

    /** The stored history could not be read. */
    data object Failed : StatsUiState

    data class Ready(val stats: FocusStats) : StatsUiState
}

/**
 * Statistics calculated from the local session history. Nothing extra is stored.
 * [source] is a parameter so tests can replace the clock and the history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    history: SessionHistoryRepository,
    source: () -> Flow<FocusStats> = { observeFocusStats(history) },
) : ViewModel() {
    private val attempt = MutableStateFlow(0)

    /** Collected only while the screen is started: no query or timer runs in the background. */
    val state: StateFlow<StatsUiState> = attempt.flatMapLatest {
        source()
            .map<FocusStats, StatsUiState> { StatsUiState.Ready(it) }
            .onStart { emit(StatsUiState.Loading) }
            .catch { emit(StatsUiState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), StatsUiState.Loading)

    fun retry() {
        attempt.update { it + 1 }
    }
}
