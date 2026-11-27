package com.focus.app.domain

import kotlinx.coroutines.flow.Flow

/** Why an app can never be restricted. Each value maps to one user-facing explanation. */
enum class ProtectionReason {
    /** fOCUS itself. */
    ThisApp,

    /** A home-screen (launcher) app: needed to get back to the device. */
    Launcher,

    /** The default dialer or a system dialer: needed for calls. */
    Phone,

    /** Emergency information / emergency assistance. */
    Emergency,

    /** The system settings app. */
    Settings,

    /** Other essential system component. */
    SystemCore,
}

/** An installed app that has a launcher entry. [protection] is non-null if it can never be restricted. */
data class DiscoveredApp(
    val packageName: String,
    val label: String,
    val protection: ProtectionReason? = null,
) {
    val isProtected: Boolean get() = protection != null
}

/** Raw ARGB pixels of an app icon, so `domain` stays free of Android imports. */
class AppIconBitmap(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "Icon size must be positive" }
        require(argb.size == width * height) { "Pixel count does not match icon size" }
    }
}

/** Where the list of launchable apps comes from (implemented in `device`). */
interface InstalledAppsSource {
    /** Launchable apps of the current user profile, each already classified for protection. */
    suspend fun loadApps(): List<DiscoveredApp>

    /**
     * Emits once whenever an app is installed, removed, updated or changed. Only listens
     * while collected, so collect it only while the list is on screen.
     */
    fun changes(): Flow<Unit>
}

/** Loads app icons on demand (implemented in `device`). */
interface AppIconSource {
    /** The icon scaled to [sizePx] x [sizePx], or null if the app or its icon is unavailable. */
    suspend fun loadIcon(packageName: String, sizePx: Int): AppIconBitmap?

    /** Drops cached icons, e.g. after an app update may have changed one. */
    fun invalidate()
}
