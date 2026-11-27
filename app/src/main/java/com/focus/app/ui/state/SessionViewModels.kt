package com.focus.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focus.app.domain.EndReason
import com.focus.app.domain.FinishedSession
import com.focus.app.domain.SessionController
import com.focus.app.domain.SessionDraft
import com.focus.app.domain.SessionHistoryRepository
import com.focus.app.domain.StartCheck
import com.focus.app.domain.StartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the review screen knows about starting. [check] is null until the first answer arrives. */
data class ConfirmUiState(
    val check: StartCheck? = null,
    val busy: Boolean = false,
    /** Set when a start was attempted and failed. */
    val failure: EndReason? = null,
)

/**
 * Asks the session engine whether the draft can start (or be saved as a schedule), and
 * does it. The engine decides; this class only forwards and keeps the screen state.
 */
class ConfirmViewModel(private val sessions: SessionController) : ViewModel() {
    private val _state = MutableStateFlow(ConfirmUiState())
    val state: StateFlow<ConfirmUiState> = _state.asStateFlow()

    fun refresh(draft: SessionDraft) {
        viewModelScope.launch {
            val check = checkFor(draft)
            _state.value = _state.value.copy(check = check)
        }
    }

    /** [onDone] runs once after a session started or a schedule was saved. */
    fun submit(draft: SessionDraft, onDone: () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, failure = null)
        viewModelScope.launch {
            val window = draft.scheduleWindow()
            val plan = draft.toPlan()
            val result = when {
                window != null -> sessions.addSchedule(window)
                plan != null -> sessions.start(plan)
                else -> StartResult.Rejected(StartCheck.Invalid(draft.problems()))
            }
            when (result) {
                StartResult.Started, StartResult.Saved -> {
                    _state.value = _state.value.copy(busy = false)
                    onDone()
                }
                is StartResult.Rejected -> _state.value = ConfirmUiState(check = result.check)
                is StartResult.Failed -> _state.value = ConfirmUiState(check = StartCheck.Ok, failure = result.reason)
            }
        }
    }

    private suspend fun checkFor(draft: SessionDraft): StartCheck {
        val problems = draft.problems()
        if (problems.isNotEmpty()) return StartCheck.Invalid(problems)
        val window = draft.scheduleWindow()
        val plan = draft.toPlan()
        return when {
            window != null -> sessions.checkSchedule(window)
            plan != null -> sessions.checkStart(plan)
            else -> StartCheck.Invalid(problems)
        }
    }
}

/** What the history screen knows. */
sealed interface HistoryUiState {
    data object Loading : HistoryUiState

    /** The stored history could not be read. */
    data object Failed : HistoryUiState

    data class Loaded(val sessions: List<FinishedSession>) : HistoryUiState
}

/**
 * Recent finished sessions for the history screen. A storage error becomes a state the
 * screen can show; it never reaches the view-model scope, where it would end the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val history: SessionHistoryRepository) : ViewModel() {
    private val attempt = MutableStateFlow(0)
    private val _clearFailed = MutableStateFlow(false)

    /** Collected only while the screen is started, so no database observer runs in the background. */
    val state: StateFlow<HistoryUiState> = attempt.flatMapLatest {
        history.observeRecent(HISTORY_LIMIT)
            .map<List<FinishedSession>, HistoryUiState> { HistoryUiState.Loaded(it) }
            .onStart { emit(HistoryUiState.Loading) }
            .catch { emit(HistoryUiState.Failed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HistoryUiState.Loading)

    /** True after a clear that did not work; reset by the next attempt. */
    val clearFailed: StateFlow<Boolean> = _clearFailed.asStateFlow()

    fun retry() {
        attempt.update { it + 1 }
    }

    fun clear() {
        viewModelScope.launch {
            _clearFailed.value = try {
                history.clear()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 200
    }
}
