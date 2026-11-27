package com.focus.app.domain

/** What the engine has to change on the device for one session. */
data class RestrictionTargets(
    /** Apps to suspend. Never contains a protected app. */
    val suspend: Set<String>,
    /** Lock Task allowlist (the protected apps); empty when Lock Task is not used. */
    val lockTask: Set<String>,
    val silence: Boolean,
    /** True when silencing is the only enforcement (regular device), so losing it ends the session. */
    val silenceIsCore: Boolean,
) {
    val isEmpty: Boolean get() = suspend.isEmpty() && !silence
}

/**
 * Turns a plan into concrete targets. Protected apps (fOCUS, home, phone, emergency,
 * settings, core system) are never suspended: the apps are re-classified on every call,
 * because roles can change between choosing and starting.
 */
object RestrictionPlanner {
    fun plan(
        mode: FocusMode,
        requested: Set<String>,
        apps: List<DiscoveredApp>,
        caps: DeviceCapabilities,
    ): RestrictionTargets {
        val restrictable = apps.filterNot { it.isProtected }.mapTo(HashSet()) { it.packageName }
        val protectedPackages = apps.filter { it.isProtected }.mapTo(HashSet()) { it.packageName }
        val lockTask = if (caps.canUseLockTask) protectedPackages else emptySet()

        return when (caps.level) {
            DeploymentLevel.DeviceOwner, DeploymentLevel.Shizuku -> when (mode) {
                FocusMode.SelectedApps -> RestrictionTargets(requested.intersect(restrictable), emptySet(), false, false)
                FocusMode.FullFocus, FocusMode.ScheduledFocus ->
                    RestrictionTargets(restrictable, lockTask, false, false)
                // Notifications are extra here: suspended apps are already silent.
                FocusMode.CallOnly ->
                    RestrictionTargets(restrictable, lockTask, caps.notificationControlGranted, false)
            }

            DeploymentLevel.Regular -> when (mode) {
                FocusMode.CallOnly -> RestrictionTargets(emptySet(), emptySet(), silence = true, silenceIsCore = true)
                else -> RestrictionTargets(emptySet(), emptySet(), silence = false, silenceIsCore = false)
            }
        }
    }
}
