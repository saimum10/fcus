package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What gets suspended, and what never does. Written in Phase 5, run in Phase 8. */
class RestrictionPlannerTest {
    private val owner = DeviceCapabilities(DeploymentLevel.DeviceOwner, true, true, true, false)
    private val ownerWithDnd = owner.copy(notificationControlGranted = true)
    private val regular = DeviceCapabilities(DeploymentLevel.Regular, false, false, true, true)

    private val apps = listOf(
        DiscoveredApp("com.focus.app", "fOCUS", ProtectionReason.ThisApp),
        DiscoveredApp("com.home", "Home", ProtectionReason.Launcher),
        DiscoveredApp("com.dialer", "Phone", ProtectionReason.Phone),
        DiscoveredApp("com.game", "Game"),
        DiscoveredApp("com.social", "Social"),
        DiscoveredApp("com.mail", "Mail"),
    )

    @Test
    fun selectedApps_suspendsOnlyTheChosenRestrictableApps() {
        val t = RestrictionPlanner.plan(FocusMode.SelectedApps, setOf("com.game", "com.dialer", "com.gone"), apps, owner)
        assertEquals(setOf("com.game"), t.suspend) // protected and uninstalled packages are dropped
        assertTrue(t.lockTask.isEmpty())
        assertFalse(t.silence)
    }

    @Test
    fun fullFocus_suspendsEverythingExceptProtected_andAllowsProtectedInLockTask() {
        val t = RestrictionPlanner.plan(FocusMode.FullFocus, emptySet(), apps, owner)
        assertEquals(setOf("com.game", "com.social", "com.mail"), t.suspend)
        assertEquals(setOf("com.focus.app", "com.home", "com.dialer"), t.lockTask)
    }

    @Test
    fun scheduledFocus_behavesLikeFullFocus() {
        assertEquals(
            RestrictionPlanner.plan(FocusMode.FullFocus, emptySet(), apps, owner),
            RestrictionPlanner.plan(FocusMode.ScheduledFocus, emptySet(), apps, owner),
        )
    }

    @Test
    fun callOnly_onDeviceOwner_addsNotificationsOnlyWhenAccessIsGranted_andNeverAsCore() {
        assertFalse(RestrictionPlanner.plan(FocusMode.CallOnly, emptySet(), apps, owner).silence)
        val t = RestrictionPlanner.plan(FocusMode.CallOnly, emptySet(), apps, ownerWithDnd)
        assertTrue(t.silence)
        assertFalse(t.silenceIsCore)
        assertEquals(3, t.suspend.size)
    }

    @Test
    fun lockTask_isNotUsedWhenUnavailable() {
        val noLock = owner.copy(canUseLockTask = false)
        assertTrue(RestrictionPlanner.plan(FocusMode.FullFocus, emptySet(), apps, noLock).lockTask.isEmpty())
    }

    @Test
    fun regularDevice_callOnly_isOnlyNotificationSilencing() {
        val t = RestrictionPlanner.plan(FocusMode.CallOnly, emptySet(), apps, regular)
        assertTrue(t.suspend.isEmpty())
        assertTrue(t.silence)
        assertTrue(t.silenceIsCore)
        assertFalse(t.isEmpty)
    }

    @Test
    fun regularDevice_otherModes_haveNothingToEnforce() {
        FocusMode.entries.filter { it != FocusMode.CallOnly }.forEach {
            assertTrue(RestrictionPlanner.plan(it, setOf("com.game"), apps, regular).isEmpty)
        }
    }

    @Test
    fun shizuku_fullFocus_suspendsEverythingExceptProtected_withoutLockTask() {
        val viaShizuku = DeviceCapabilities(DeploymentLevel.Shizuku, true, false, true, false, ShizukuState.Granted)
        val t = RestrictionPlanner.plan(FocusMode.FullFocus, emptySet(), apps, viaShizuku)
        assertEquals(setOf("com.game", "com.social", "com.mail"), t.suspend)
        assertTrue(t.lockTask.isEmpty())
        assertFalse(t.silence)
    }
}
