package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The honest per-mode support table. Written in Phase 5, run in Phase 8. */
class ModeSupportTest {
    private fun caps(
        level: DeploymentLevel,
        suspend: Boolean = level == DeploymentLevel.DeviceOwner,
        notifAvailable: Boolean = true,
        notifGranted: Boolean = false,
    ) = DeviceCapabilities(level, suspend, canUseLockTask = suspend, notifAvailable, notifGranted)

    @Test
    fun deviceOwner_supportsAllFourModes() {
        FocusMode.entries.forEach {
            assertEquals(Capability.Available, ModeSupport.capability(it, caps(DeploymentLevel.DeviceOwner)))
        }
    }

    @Test
    fun regularDevice_cannotBlockApps() {
        listOf(FocusMode.SelectedApps, FocusMode.FullFocus, FocusMode.ScheduledFocus).forEach {
            assertEquals(
                Capability.Unavailable(UnavailableReason.DeviceUnsupported),
                ModeSupport.capability(it, caps(DeploymentLevel.Regular, notifGranted = true)),
            )
        }
    }

    @Test
    fun regularDevice_callOnly_needsAccessThenIsLimited() {
        assertEquals(
            Capability.Unavailable(UnavailableReason.PermissionMissing),
            ModeSupport.capability(FocusMode.CallOnly, caps(DeploymentLevel.Regular)),
        )
        val granted = ModeSupport.capability(FocusMode.CallOnly, caps(DeploymentLevel.Regular, notifGranted = true))
        assertEquals(Capability.Limited(CapabilityLimit.NotificationsOnly), granted)
    }

    @Test
    fun regularDevice_callOnly_unsupportedWhenNoAccessScreenExists() {
        assertEquals(
            Capability.Unavailable(UnavailableReason.DeviceUnsupported),
            ModeSupport.capability(FocusMode.CallOnly, caps(DeploymentLevel.Regular, notifAvailable = false)),
        )
    }

    @Test
    fun deviceOwnerWithoutSuspension_isUnsupported() {
        val c = ModeSupport.capability(FocusMode.FullFocus, caps(DeploymentLevel.DeviceOwner, suspend = false))
        assertTrue(c is Capability.Unavailable)
    }

    private val viaShizuku = DeviceCapabilities(DeploymentLevel.Shizuku, true, false, true, false, ShizukuState.Granted)

    @Test
    fun shizuku_supportsAllFourModes() {
        FocusMode.entries.forEach {
            assertEquals(Capability.Available, ModeSupport.capability(it, viaShizuku))
        }
    }

    @Test
    fun shizukuNotYetAllowed_stillCannotBlockApps() {
        val waiting = DeviceCapabilities(DeploymentLevel.Regular, false, false, true, false, ShizukuState.NeedsPermission)
        assertEquals(
            Capability.Unavailable(UnavailableReason.DeviceUnsupported),
            ModeSupport.capability(FocusMode.FullFocus, waiting),
        )
    }
}
