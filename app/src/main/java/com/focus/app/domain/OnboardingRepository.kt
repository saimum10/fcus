package com.focus.app.domain

import kotlinx.coroutines.flow.Flow

/** Persists whether the introduction (including the limitations screen) was completed. */
interface OnboardingRepository {
    val completed: Flow<Boolean>

    suspend fun markCompleted()
}
