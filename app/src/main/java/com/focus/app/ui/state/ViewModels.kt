package com.focus.app.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focus.app.domain.AppSelectionRepository
import com.focus.app.domain.FocusMode
import com.focus.app.domain.OnboardingRepository
import com.focus.app.domain.RunningSession
import com.focus.app.domain.SessionController
import com.focus.app.domain.SessionDraft
import com.focus.app.domain.SessionState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-level state: onboarding flag and the session state. */
class AppViewModel(
    private val onboarding: OnboardingRepository,
    private val sessions: SessionController,
) : ViewModel() {

    /** null while the stored flag is still loading. */
    val onboardingCompleted: StateFlow<Boolean?> = onboarding.completed
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sessionState: StateFlow<SessionState> = sessions.state

    fun remainingMillis(session: RunningSession): Long = sessions.remainingMillis(session)

    /** Cheap and idempotent: ends an expired session, re-applies after a restart, starts due schedules. */
    fun reconcile() {
        viewModelScope.launch {
            // Runs on every return to the app; a failure must not end the app. The next one retries.
            try {
                sessions.reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun beginConfiguring() {
        viewModelScope.launch { sessions.beginConfiguring() }
    }

    fun cancelConfiguring() {
        viewModelScope.launch { sessions.cancelConfiguring() }
    }

    fun acknowledge() {
        viewModelScope.launch { sessions.acknowledge() }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            // If storage fails the intro is simply shown again next launch; never block the user.
            try {
                onboarding.markCompleted()
            } catch (_: IOException) {
            }
        }
    }
}

/**
 * Holds the "new session" draft across the configuration screens. Scoped to the
 * activity so it survives rotation; the draft itself is intentionally not persisted (a
 * half-built draft is not worth restoring after process death). The selected apps are
 * the exception: they come from [AppSelectionRepository], which persists them, so the
 * person's last choice is remembered and shown the next time.
 */
class SessionDraftViewModel(selection: AppSelectionRepository) : ViewModel() {
    private val base = MutableStateFlow(SessionDraft())

    val draft: StateFlow<SessionDraft> = combine(base, selection.selectedPackages) { draft, selected ->
        draft.copy(selectedPackages = selected)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionDraft())

    /** Starts a new draft. The remembered app selection is kept. */
    fun reset() = base.update { SessionDraft() }

    fun setMode(mode: FocusMode) = base.update { it.copy(mode = mode) }

    fun setDuration(minutes: Int?) = base.update { it.copy(durationMinutes = minutes) }

    fun toggleDay(day: DayOfWeek) = base.update {
        it.copy(scheduleDays = if (day in it.scheduleDays) it.scheduleDays - day else it.scheduleDays + day)
    }

    fun setScheduleStart(minuteOfDay: Int) = base.update { it.copy(scheduleStartMinuteOfDay = minuteOfDay) }
}
