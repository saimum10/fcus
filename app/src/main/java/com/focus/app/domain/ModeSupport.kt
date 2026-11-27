package com.focus.app.domain

/**
 * The single honest table of what each mode can do on each kind of device.
 *
 * - Device Owner, or a regular app allowed to use Shizuku: all four modes block apps
 *   (suspension). Scheduled Focus is Full Focus during its windows.
 * - Regular device: apps cannot be blocked, so Selected Apps, Full Focus and Scheduled
 *   Focus are unsupported. Call Only is available only in a limited form, silencing
 *   notifications except calls, and only after the person granted notification-policy access.
 */
object ModeSupport {
    fun capability(mode: FocusMode, caps: DeviceCapabilities): Capability = when (caps.level) {
        DeploymentLevel.DeviceOwner, DeploymentLevel.Shizuku ->
            if (caps.canSuspendApps) Capability.Available else Capability.Unavailable(UnavailableReason.DeviceUnsupported)

        DeploymentLevel.Regular -> when (mode) {
            FocusMode.CallOnly -> when {
                !caps.notificationControlAvailable -> Capability.Unavailable(UnavailableReason.DeviceUnsupported)
                !caps.notificationControlGranted -> Capability.Unavailable(UnavailableReason.PermissionMissing)
                else -> Capability.Limited(CapabilityLimit.NotificationsOnly)
            }
            FocusMode.SelectedApps, FocusMode.FullFocus, FocusMode.ScheduledFocus ->
                Capability.Unavailable(UnavailableReason.DeviceUnsupported)
        }
    }
}
