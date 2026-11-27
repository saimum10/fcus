package com.focus.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focus.app.domain.AuditEvent
import com.focus.app.domain.RecoveryController
import com.focus.app.domain.RecoveryResult
import com.focus.app.domain.RecoveryStatus
import com.focus.app.domain.TrustedPerson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the "end session early" screen knows. */
data class RecoveryUiState(
    val status: RecoveryStatus? = null,
    /** Result of opening recovery; null until known. Anything but Done means no PIN can be entered. */
    val entry: RecoveryResult? = null,
    val busy: Boolean = false,
    /** Why the last PIN attempt did not end the session. */
    val failure: RecoveryResult? = null,
)

/**
 * Drives the "end session early" screen. Opening the screen asks the service to open
 * recovery (the session becomes RecoveryPending, restrictions unchanged). Leaving it
 * without success cancels recovery. Both the PIN check and the cancel run in
 * [NonCancellable], so leaving the screen at the wrong moment cannot cut a recovery in half.
 */
class RecoveryViewModel(private val recovery: RecoveryController) : ViewModel() {
    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val entry = recovery.requestRecovery()
            val status = recovery.status()
            _state.update { it.copy(entry = entry, status = status) }
        }
    }

    /** [onDone] runs once if the PIN was right and the session has ended. */
    fun submit(pin: String, onDone: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch(NonCancellable) {
            val result = recovery.submitPin(pin)
            val status = recovery.status()
            _state.update {
                it.copy(busy = false, status = status, failure = if (result == RecoveryResult.Done) null else result)
            }
            if (result == RecoveryResult.Done) onDone()
        }
    }

    /** Back to Active. Harmless if the session already ended. */
    fun cancel() {
        viewModelScope.launch(NonCancellable) { recovery.cancelRecovery() }
    }
}

/** What the trusted-person screen knows. */
data class TrustedUiState(
    val status: RecoveryStatus? = null,
    val busy: Boolean = false,
    /** Why the last action was not done; null when it worked or nothing was tried. */
    val message: RecoveryResult? = null,
)

/** Reads and changes the trusted person. Every rule lives in the service; this only forwards. */
class TrustedPersonViewModel(private val recovery: RecoveryController) : ViewModel() {
    private val _state = MutableStateFlow(TrustedUiState())
    val state: StateFlow<TrustedUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val status = recovery.status()
            _state.update { it.copy(status = status) }
        }
    }

    fun setUp(person: TrustedPerson, pin: String, onDone: () -> Unit) = act(onDone) { recovery.setUp(person, pin) }

    fun change(person: TrustedPerson, newPin: String?, currentPin: String, onDone: () -> Unit) =
        act(onDone) { recovery.change(person, newPin, currentPin) }

    fun remove(currentPin: String, onDone: () -> Unit) = act(onDone) { recovery.remove(currentPin) }

    fun requestRemoval() = act { recovery.requestRemoval() }

    fun cancelRemoval() = act { recovery.cancelRemoval() }

    fun completeRemoval() = act { recovery.completeRemoval() }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun act(onDone: () -> Unit = {}, block: suspend () -> RecoveryResult) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = block()
            val status = recovery.status()
            _state.update {
                it.copy(busy = false, status = status, message = if (result == RecoveryResult.Done) null else result)
            }
            if (result == RecoveryResult.Done) onDone()
        }
    }
}

/** The audit log, newest first. */
class AuditViewModel(private val recovery: RecoveryController) : ViewModel() {
    /** null while loading. */
    val events: StateFlow<List<AuditEvent>?> = recovery.auditEvents(EVENT_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun clear() {
        viewModelScope.launch { recovery.clearAudit() }
    }

    private companion object {
        const val EVENT_LIMIT = 200
    }
}
