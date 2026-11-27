package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which apps can never be restricted. Written in Phase 3, run in Phase 8. */
class ProtectionPolicyTest {
    private val own = "com.focus.app"

    private fun classify(pkg: String, roles: ProtectedRoles = ProtectedRoles()) =
        ProtectionPolicy.classify(pkg, own, roles)

    @Test
    fun ownApp_isAlwaysProtected() {
        assertEquals(ProtectionReason.ThisApp, classify(own))
    }

    @Test
    fun detectedRoles_areProtected() {
        val roles = ProtectedRoles(
            launchers = setOf("com.vendor.home"),
            phone = setOf("com.vendor.dialer"),
            emergency = setOf("com.vendor.sos"),
            settings = setOf("com.vendor.settings"),
        )
        assertEquals(ProtectionReason.Launcher, classify("com.vendor.home", roles))
        assertEquals(ProtectionReason.Phone, classify("com.vendor.dialer", roles))
        assertEquals(ProtectionReason.Emergency, classify("com.vendor.sos", roles))
        assertEquals(ProtectionReason.Settings, classify("com.vendor.settings", roles))
    }

    @Test
    fun aospBaseline_appliesWithoutDetectedRoles() {
        assertEquals(ProtectionReason.Settings, classify("com.android.settings"))
        assertEquals(ProtectionReason.Emergency, classify("com.android.emergency"))
        assertEquals(ProtectionReason.Phone, classify("com.android.server.telecom"))
        assertEquals(ProtectionReason.SystemCore, classify("com.android.systemui"))
    }

    @Test
    fun ordinaryApp_isNotProtected() {
        assertNull(classify("com.example.game"))
    }

    @Test
    fun emergencyWinsOverOtherRoles() {
        val roles = ProtectedRoles(
            emergency = setOf("com.vendor.multi"),
            phone = setOf("com.vendor.multi"),
            launchers = setOf("com.vendor.multi"),
        )
        assertEquals(ProtectionReason.Emergency, classify("com.vendor.multi", roles))
    }

    @Test
    fun ownApp_winsOverEveryRole() {
        val roles = ProtectedRoles(launchers = setOf(own))
        assertEquals(ProtectionReason.ThisApp, classify(own, roles))
    }

    @Test
    fun shizukuApp_isProtected_soThePersonCanAlwaysRestartIt() {
        assertEquals(ProtectionReason.SystemCore, classify("moe.shizuku.privileged.api"))
    }
}
