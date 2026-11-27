package com.focus.app.ui

import com.focus.app.domain.AppIconSource
import com.focus.app.domain.AppSelectionRepository
import com.focus.app.domain.DeviceManagement
import com.focus.app.domain.InstalledAppsSource
import com.focus.app.domain.LockTaskSignal
import com.focus.app.domain.OnboardingRepository
import com.focus.app.domain.RecoveryController
import com.focus.app.domain.SessionController
import com.focus.app.domain.SessionHistoryRepository

/**
 * Everything the UI needs from outside, as `domain` interfaces only. Built once by the
 * composition root ([com.focus.app.FocusApplication]); the UI never sees `data`,
 * `device` or `session` classes.
 */
class FocusDependencies(
    val onboarding: OnboardingRepository,
    val sessions: SessionController,
    val sessionHistory: SessionHistoryRepository,
    val installedApps: InstalledAppsSource,
    val appIcons: AppIconSource,
    val appSelection: AppSelectionRepository,
    val management: DeviceManagement,
    val lockTask: LockTaskSignal,
    val recovery: RecoveryController,
)
