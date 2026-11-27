package com.focus.app.session

import com.focus.app.domain.CapabilityLimit
import com.focus.app.domain.DeploymentLevel
import com.focus.app.domain.DeviceCapabilities
import com.focus.app.domain.DiscoveredApp
import com.focus.app.domain.EndReason
import com.focus.app.domain.EnforcementResult
import com.focus.app.domain.FocusMode
import com.focus.app.domain.InterruptionSnapshot
import com.focus.app.domain.ProtectionReason
import com.focus.app.domain.RestrictionJournalEntry
import com.focus.app.domain.SessionPlan
import com.focus.app.domain.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restriction logic against fakes: what is suspended, what is never touched, honest
 * reporting of partial results, write-ahead journal, release that retries, restart safety,
 * loss detection. Written in Phase 5, run in Phase 8. The Android calls behind
 * `DeviceControl` (DevicePolicyManager, NotificationManager) are verified on a device in Phase 8.
 */
class RestrictionCoordinatorTest {
    private val ownerCaps = DeviceCapabilities(DeploymentLevel.DeviceOwner, true, true, true, false)
    private val regularCaps = DeviceCapabilities(DeploymentLevel.Regular, false, false, true, true)
    private val shizukuCaps = DeviceCapabilities(DeploymentLevel.Shizuku, true, false, true, false, ShizukuState.Granted)

    private val appList = listOf(
        DiscoveredApp("com.focus.app", "fOCUS", ProtectionReason.ThisApp),
        DiscoveredApp("com.home", "Home", ProtectionReason.Launcher),
        DiscoveredApp("com.game", "Game"),
        DiscoveredApp("com.social", "Social"),
        DiscoveredApp("com.mail", "Mail"),
    )

    private class Rig(caps: DeviceCapabilities, apps: List<DiscoveredApp>) {
        val device = FakeDevice(caps)
        val journal = FakeJournal()
        val apps = FakeApps(apps)
        val time = FakeTime(wall = 1_000_000L)

        fun coordinator() = RestrictionCoordinator(device, journal, apps, time, io = Dispatchers.Unconfined)
    }

    private fun owner() = Rig(ownerCaps, appList)
    private fun regular() = Rig(regularCaps, appList)

    private fun plan(mode: FocusMode, vararg packages: String) = SessionPlan(mode, packages.toSet(), 30)

    // ---- apply: device owner ------------------------------------------------------------

    @Test
    fun selectedApps_suspendsOnlyChosenApps_andReportsIt() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.SelectedApps, "com.game", "com.mail")))
        assertEquals(setOf("com.game", "com.mail"), rig.device.suspended)
        assertEquals(setOf("com.game", "com.mail"), rig.journal.stored!!.suspended)
        assertTrue(c.status.value.active)
        assertEquals(2, c.status.value.suspendedCount)
        assertEquals(0, c.status.value.notRestrictedCount)
    }

    @Test
    fun protectedApps_areNeverSuspended_evenIfSelected() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game", "com.home", "com.focus.app"))
        assertEquals(setOf("com.game"), rig.device.suspended)
    }

    @Test
    fun selectedApps_nothingRestrictableLeft_fails() = runBlocking {
        val rig = owner()
        val result = rig.coordinator().apply(plan(FocusMode.SelectedApps, "com.home"))
        assertEquals(EnforcementResult.Failed(EndReason.PolicyFailure), result)
        assertTrue(rig.device.suspended.isEmpty())
    }

    @Test
    fun selectedApps_ifTheSystemRefusesOne_theWholeStartFailsAndIsUndone() = runBlocking {
        val rig = owner()
        rig.device.refuse = setOf("com.game")
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Failed(EndReason.PolicyFailure), c.apply(plan(FocusMode.SelectedApps, "com.game", "com.mail")))
        assertTrue(rig.device.suspended.isEmpty())
        assertNull(rig.journal.stored)
        assertFalse(c.status.value.active)
    }

    @Test
    fun fullFocus_suspendsAllRestrictableApps_andRequestsLockTask() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.FullFocus)))
        assertEquals(setOf("com.game", "com.social", "com.mail"), rig.device.suspended)
        assertEquals(setOf("com.focus.app", "com.home"), rig.device.lockTaskAllowlist)
        assertTrue(c.status.value.lockTaskRequested)
    }

    @Test
    fun fullFocus_partialRefusal_isReported_notHidden() = runBlocking {
        val rig = owner()
        rig.device.refuse = setOf("com.social")
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.FullFocus)))
        assertEquals(2, c.status.value.suspendedCount)
        assertEquals(1, c.status.value.notRestrictedCount)
    }

    @Test
    fun fullFocus_nothingCouldBeSuspended_fails() = runBlocking {
        val rig = owner()
        rig.device.refuse = setOf("com.game", "com.social", "com.mail")
        assertEquals(EnforcementResult.Failed(EndReason.PolicyFailure), rig.coordinator().apply(plan(FocusMode.FullFocus)))
        assertNull(rig.journal.stored)
    }

    @Test
    fun lockTaskRefused_doesNotFakeIt_andDoesNotFailTheSession() = runBlocking {
        val rig = owner()
        rig.device.lockTaskRefuses = true
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.FullFocus)))
        assertFalse(c.status.value.lockTaskRequested)
    }

    @Test
    fun aThrowingSystemCall_isAFailedRestriction_notACrash() = runBlocking {
        val rig = owner()
        rig.device.throwOnSuspend = true
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Failed(EndReason.PolicyFailure), c.apply(plan(FocusMode.FullFocus)))
        assertFalse(c.status.value.active)
    }

    @Test
    fun unreadableJournal_meansNothingIsChanged() = runBlocking {
        val rig = owner()
        rig.journal.failLoad = true
        assertEquals(EnforcementResult.Failed(EndReason.InternalError), rig.coordinator().apply(plan(FocusMode.FullFocus)))
        assertTrue(rig.device.suspended.isEmpty())
    }

    @Test
    fun journalThatCannotBeWritten_meansNothingIsChanged() = runBlocking {
        val rig = owner()
        rig.journal.failSave = true
        assertEquals(EnforcementResult.Failed(EndReason.InternalError), rig.coordinator().apply(plan(FocusMode.FullFocus)))
        assertTrue(rig.device.suspended.isEmpty())
    }

    @Test
    fun appsListUnreadable_failsHonestly() = runBlocking {
        val rig = owner()
        rig.apps.fail = true
        assertEquals(EnforcementResult.Failed(EndReason.PolicyFailure), rig.coordinator().apply(plan(FocusMode.FullFocus)))
    }

    @Test
    fun apply_isIdempotent() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game"))
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.SelectedApps, "com.game")))
        assertEquals(setOf("com.game"), rig.device.suspended)
        assertEquals(setOf("com.game"), rig.journal.stored!!.suspended)
    }

    @Test
    fun reapplyWithAnotherSet_freesTheAppsThatLeftThePlan() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game"))
        c.apply(plan(FocusMode.SelectedApps, "com.mail"))
        assertEquals(setOf("com.mail"), rig.device.suspended)
    }

    // ---- regular device -----------------------------------------------------------------

    @Test
    fun regularDevice_cannotBlockApps_soThoseModesFail() = runBlocking {
        val rig = regular()
        val c = rig.coordinator()
        listOf(FocusMode.SelectedApps, FocusMode.FullFocus, FocusMode.ScheduledFocus).forEach {
            assertEquals(EnforcementResult.Failed(EndReason.DeviceUnsupported), c.apply(plan(it, "com.game")))
        }
        assertTrue(rig.device.suspended.isEmpty())
        assertFalse(c.status.value.active)
    }

    @Test
    fun regularDevice_callOnly_silencesNotifications_andRestoresThemAfterwards() = runBlocking {
        val rig = regular()
        val original = rig.device.interruption
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.CallOnly)))
        assertTrue(rig.device.silenced)
        assertEquals(original, rig.journal.stored!!.interruption) // saved before it was changed
        assertTrue(c.status.value.notificationsSilenced)
        assertEquals(CapabilityLimit.NotificationsOnly, c.status.value.limit)
        assertEquals(0, c.status.value.suspendedCount)

        c.release()
        assertFalse(rig.device.silenced)
        assertEquals(original, rig.device.interruption)
        assertNull(rig.journal.stored)
        assertFalse(c.status.value.active)
    }

    @Test
    fun regularDevice_callOnly_withoutAccess_failsInsteadOfPretending() = runBlocking {
        val rig = regular()
        rig.device.caps = regularCaps.copy(notificationControlGranted = false)
        assertEquals(EnforcementResult.Failed(EndReason.PermissionLost), rig.coordinator().apply(plan(FocusMode.CallOnly)))
        assertFalse(rig.device.silenced)
    }

    @Test
    fun release_neverOverwritesTheUsersOwnDoNotDisturbChange() = runBlocking {
        val rig = regular()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.CallOnly))
        rig.device.userChangedDnd = true
        c.release()
        assertTrue(rig.device.silenced) // untouched: the fake models the person's own setting
        assertNull(rig.journal.stored)
    }

    @Test
    fun deviceOwner_callOnly_silencingIsExtra_notRequired() = runBlocking {
        val rig = owner() // access not granted
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.CallOnly)))
        assertFalse(c.status.value.notificationsSilenced)
        assertEquals(3, c.status.value.suspendedCount)
    }

    // ---- release ------------------------------------------------------------------------

    @Test
    fun release_removesEverything_andClearsTheJournal() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        c.release()
        assertTrue(rig.device.suspended.isEmpty())
        assertNull(rig.device.lockTaskAllowlist)
        assertNull(rig.journal.stored)
        assertFalse(c.status.value.active)
    }

    @Test
    fun release_isSafeWhenNothingIsApplied() = runBlocking {
        val rig = owner()
        rig.coordinator().release()
        assertNull(rig.journal.stored)
    }

    @Test
    fun release_whenTheSystemRefuses_keepsTheJournalSoItCanBeRetried() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game", "com.mail"))
        rig.device.refuseUnsuspend = setOf("com.game")
        c.release()
        assertEquals(setOf("com.game"), rig.device.suspended)
        assertEquals(setOf("com.game"), rig.journal.stored!!.suspended) // still on record

        rig.device.refuseUnsuspend = emptySet()
        c.release()
        assertTrue(rig.device.suspended.isEmpty())
        assertNull(rig.journal.stored)
    }

    // ---- restart safety -----------------------------------------------------------------

    @Test
    fun afterACrash_theNextProcessUndoesWhatTheJournalRecords() = runBlocking {
        val rig = owner()
        rig.coordinator().apply(plan(FocusMode.FullFocus))
        assertTrue(rig.device.suspended.isNotEmpty())

        // The process died: a new coordinator starts with the same device and journal, nothing applied.
        val next = rig.coordinator()
        next.releaseIfNotApplied()
        assertTrue(rig.device.suspended.isEmpty())
        assertNull(rig.journal.stored)
    }

    @Test
    fun releaseIfNotApplied_leavesAnAppliedSessionAlone() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        c.releaseIfNotApplied()
        assertEquals(3, rig.device.suspended.size)
    }

    @Test
    fun afterARestart_applyingAgainConvergesOnTheSameState() = runBlocking {
        val rig = owner()
        rig.coordinator().apply(plan(FocusMode.SelectedApps, "com.game"))
        val next = rig.coordinator()
        assertEquals(EnforcementResult.Applied, next.apply(plan(FocusMode.SelectedApps, "com.game")))
        assertEquals(setOf("com.game"), rig.device.suspended)
    }

    @Test
    fun theOriginalDoNotDisturbState_isKeptAcrossARestart() = runBlocking {
        val rig = regular()
        val original = rig.device.interruption
        rig.coordinator().apply(plan(FocusMode.CallOnly))
        val next = rig.coordinator()
        next.apply(plan(FocusMode.CallOnly)) // re-applied: must not record "silenced" as the original
        assertEquals(original, rig.journal.stored!!.interruption)
    }

    // ---- loss detection -----------------------------------------------------------------

    @Test
    fun check_isQuietWhileEverythingHolds() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        assertNull(c.check())
    }

    @Test
    fun check_isQuietWhenNothingIsApplied() = runBlocking {
        assertNull(owner().coordinator().check())
    }

    @Test
    fun check_detectsAnAppThatWasUnsuspended() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game"))
        rig.device.suspended.remove("com.game")
        assertEquals(EndReason.EnforcementLost, c.check())
    }

    @Test
    fun check_ignoresAnAppThatWasUninstalled() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game", "com.mail"))
        rig.device.suspended.remove("com.game")
        rig.apps.apps = appList.filterNot { it.packageName == "com.game" }
        assertNull(c.check())
        assertEquals(1, c.status.value.suspendedCount)
    }

    @Test
    fun check_detectsLostDeviceOwnership() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        rig.device.caps = regularCaps
        assertEquals(EndReason.PolicyFailure, c.check())
    }

    @Test
    fun shizuku_fullFocus_suspendsApps_withoutLockTask() = runBlocking {
        val rig = Rig(shizukuCaps, appList)
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.FullFocus)))
        assertEquals(setOf("com.game", "com.social", "com.mail"), rig.device.suspended)
        assertEquals(null, rig.device.lockTaskAllowlist)
        assertFalse(c.status.value.lockTaskRequested)
    }

    @Test
    fun shizukuStoppingDuringASession_doesNotEndIt_andComesBackCleanly() = runBlocking {
        val rig = Rig(shizukuCaps, appList)
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        rig.device.caps = regularCaps.copy(shizuku = ShizukuState.NotRunning)
        assertNull(c.check())
        assertTrue(c.status.value.shizukuStopped)
        assertEquals(setOf("com.game", "com.social", "com.mail"), rig.device.suspended) // still blocked
        rig.device.caps = shizukuCaps
        assertNull(c.check())
        assertFalse(c.status.value.shizukuStopped)
    }

    @Test
    fun sessionEndingWhileShizukuIsStopped_keepsTheJournal_untilShizukuIsBack() = runBlocking {
        val rig = Rig(shizukuCaps, appList)
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        rig.device.caps = regularCaps.copy(shizuku = ShizukuState.NotRunning)
        rig.device.refuseUnsuspend = rig.device.suspended.toSet() // Shizuku cannot be reached
        rig.device.refuseUninstallUnblock = true
        c.release()
        assertEquals(setOf("com.game", "com.social", "com.mail"), rig.journal.stored!!.suspended)
        rig.device.caps = shizukuCaps
        rig.device.refuseUnsuspend = emptySet()
        rig.device.refuseUninstallUnblock = false
        c.releaseIfNotApplied()
        assertTrue(rig.device.suspended.isEmpty())
        assertNull(rig.journal.stored)
    }

    @Test
    fun selectedApps_areStoppedFirst_thenSuspended() = runBlocking {
        val rig = owner()
        rig.coordinator().apply(plan(FocusMode.SelectedApps, "com.game", "com.mail", "com.home"))
        assertEquals(listOf("stop:com.game,com.mail", "suspend"), rig.device.log)
    }

    @Test
    fun fullFocus_stopsEveryRestrictableApp_neverFocusOrProtectedOnes() = runBlocking {
        val rig = owner()
        rig.coordinator().apply(plan(FocusMode.FullFocus))
        assertEquals("stop:com.game,com.mail,com.social", rig.device.log.first())
        assertEquals("suspend", rig.device.log[1])
    }

    @Test
    fun suspendingApps_alsoBlocksUninstall_andReleaseUndoesIt() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        assertTrue(rig.device.uninstallBlocked)
        assertTrue(c.status.value.uninstallBlocked)
        assertTrue(rig.journal.stored!!.uninstallBlocked)
        c.release()
        assertFalse(rig.device.uninstallBlocked)
        assertNull(rig.journal.stored)
    }

    @Test
    fun uninstallBlockRefused_sessionStillRuns_andStatusSaysSo() = runBlocking {
        val rig = owner()
        rig.device.refuseUninstallBlock = true
        val c = rig.coordinator()
        assertEquals(EnforcementResult.Applied, c.apply(plan(FocusMode.FullFocus)))
        assertFalse(c.status.value.uninstallBlocked)
    }

    @Test
    fun uninstallBlockThatCannotBeUndone_keepsTheJournal_untilItCan() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        rig.device.refuseUninstallUnblock = true
        c.release()
        assertTrue(rig.journal.stored!!.uninstallBlocked)
        rig.device.refuseUninstallUnblock = false
        c.releaseIfNotApplied()
        assertFalse(rig.device.uninstallBlocked)
        assertNull(rig.journal.stored)
    }

    @Test
    fun check_restrictsAppsInstalledDuringFullFocus() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        rig.apps.apps = appList + DiscoveredApp("com.new", "New")
        assertNull(c.check())
        assertTrue("com.new" in rig.device.suspended)
        assertTrue("com.new" in rig.journal.stored!!.suspended)
        assertEquals(4, c.status.value.suspendedCount)
    }

    @Test
    fun check_doesNotAddAppsDuringSelectedApps() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.SelectedApps, "com.game"))
        rig.apps.apps = appList + DiscoveredApp("com.new", "New")
        c.check()
        assertEquals(setOf("com.game"), rig.device.suspended)
    }

    @Test
    fun check_regularCallOnly_detectsDoNotDisturbTurnedOff_afterTheGracePeriod() = runBlocking {
        val rig = regular()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.CallOnly))
        rig.device.userChangedDnd = true
        assertNull(c.check()) // Do Not Disturb changes land asynchronously: not read back as lost right away
        rig.time.advance(10_000L)
        assertEquals(EndReason.EnforcementLost, c.check())
    }

    @Test
    fun check_regularCallOnly_detectsWithdrawnAccess() = runBlocking {
        val rig = regular()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.CallOnly))
        rig.device.caps = regularCaps.copy(notificationControlGranted = false)
        assertEquals(EndReason.PermissionLost, c.check())
    }

    @Test
    fun check_deviceOwnerCallOnly_doesNotEndTheSessionWhenDoNotDisturbChanges() = runBlocking {
        val rig = owner()
        rig.device.caps = ownerCaps.copy(notificationControlGranted = true)
        val c = rig.coordinator()
        c.apply(plan(FocusMode.CallOnly))
        rig.device.userChangedDnd = true
        rig.time.advance(10_000L)
        assertNull(c.check()) // suspension is the enforcement; Do Not Disturb is extra
    }

    // ---- management ---------------------------------------------------------------------

    @Test
    fun relinquish_isRefusedWhileRestrictionsAreApplied() = runBlocking {
        val rig = owner()
        val c = rig.coordinator()
        c.apply(plan(FocusMode.FullFocus))
        assertFalse(c.relinquishDeviceOwner())
        assertFalse(rig.device.ownershipCleared)
        assertEquals(3, rig.device.suspended.size)
    }

    @Test
    fun relinquish_cleansUpFirst_thenGivesUpOwnership() = runBlocking {
        val rig = owner()
        // Leftovers from a crashed process: on record in the journal, still suspended.
        rig.device.suspended += "com.game"
        rig.journal.stored = RestrictionJournalEntry(setOf("com.game"))
        val c = rig.coordinator()
        assertTrue(c.relinquishDeviceOwner())
        assertTrue(rig.device.suspended.isEmpty())
        assertTrue(rig.device.ownershipCleared)
    }

    @Test
    fun relinquish_isRefusedIfCleanupFails() = runBlocking {
        val rig = owner()
        rig.device.suspended += "com.game"
        rig.device.refuseUnsuspend = setOf("com.game")
        rig.journal.stored = RestrictionJournalEntry(setOf("com.game"))
        assertFalse(rig.coordinator().relinquishDeviceOwner())
        assertFalse(rig.device.ownershipCleared)
    }

    @Test
    fun capability_followsTheDevice() = runBlocking {
        val rig = regular()
        val c = rig.coordinator()
        assertTrue(c.capability(FocusMode.FullFocus) is com.focus.app.domain.Capability.Unavailable)
        rig.device.caps = ownerCaps
        assertEquals(com.focus.app.domain.Capability.Available, c.capability(FocusMode.FullFocus))
    }
}
