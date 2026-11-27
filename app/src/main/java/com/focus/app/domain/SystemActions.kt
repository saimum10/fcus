package com.focus.app.domain

/**
 * Intent action strings that the public Android SDK does not expose as constants.
 * The same string is declared in the manifest's `<queries>` so the action can be resolved.
 */
object SystemActions {
    /**
     * The system's emergency-information screen. `TelephonyManager.ACTION_EMERGENCY_ASSISTANCE`
     * is not part of the public SDK, so the string value is used directly. Devices without
     * an app for it simply resolve to nothing, and callers handle that.
     */
    const val EMERGENCY_ASSISTANCE = "android.telephony.action.EMERGENCY_ASSISTANCE"
}
