package com.focus.app.domain

/**
 * Packages found on this device for each protected role. Detected in `device` (the
 * device decides who the launcher or dialer is); the decision itself lives here.
 */
data class ProtectedRoles(
    val launchers: Set<String> = emptySet(),
    val phone: Set<String> = emptySet(),
    val emergency: Set<String> = emptySet(),
    val settings: Set<String> = emptySet(),
)

/**
 * Decides which apps can never be restricted: fOCUS itself, emergency functions, the
 * phone, the home screen, settings and core system components. Role detection on the
 * device is the primary signal; the small AOSP list is only a fallback because
 * manufacturers rename packages.
 */
object ProtectionPolicy {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val aospBaseline: Map<String, ProtectionReason> = mapOf(
        "com.android.settings" to ProtectionReason.Settings,
        "com.android.systemui" to ProtectionReason.SystemCore,
        "com.android.phone" to ProtectionReason.Phone,
        "com.android.server.telecom" to ProtectionReason.Phone,
        "com.android.emergency" to ProtectionReason.Emergency,
        // The Shizuku manager must stay openable: it is how the person restarts Shizuku, and
        // without Shizuku fOCUS cannot un-suspend anything.
        SHIZUKU_PACKAGE to ProtectionReason.SystemCore,
    )

    fun classify(packageName: String, ownPackage: String, roles: ProtectedRoles): ProtectionReason? = when {
        packageName == ownPackage -> ProtectionReason.ThisApp
        packageName in roles.emergency -> ProtectionReason.Emergency
        packageName in roles.phone -> ProtectionReason.Phone
        packageName in roles.launchers -> ProtectionReason.Launcher
        packageName in roles.settings -> ProtectionReason.Settings
        else -> aospBaseline[packageName]
    }
}
