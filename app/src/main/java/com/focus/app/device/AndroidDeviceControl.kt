package com.focus.app.device

import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.focus.app.domain.DeploymentLevel
import com.focus.app.domain.DeviceCapabilities
import com.focus.app.domain.DeviceControl
import com.focus.app.domain.InterruptionSnapshot
import com.focus.app.domain.MutableLockTaskSignal
import com.focus.app.domain.ShizukuState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.merge
import rikka.shizuku.Shizuku

/**
 * The only class that talks to `DevicePolicyManager` and `NotificationManager` for restrictions.
 *
 * Three levels, detected on every call and never assumed:
 * - **Device Owner** (set up through provisioning, see the guide): suspends apps with
 *   `setPackagesSuspended` and can set a Lock Task allowlist.
 * - **Shizuku** (running, and the person allowed fOCUS): suspends apps by running `pm suspend`
 *   with shell rights. No Lock Task. Only works while Shizuku itself is running.
 * - **Regular app**: cannot block other apps. It can silence notifications through Do Not
 *   Disturb, but only if the person granted notification-policy access.
 *
 * All methods block on system services: call them off the main thread.
 */
class AndroidDeviceControl(
    context: Context,
    private val lockTask: MutableLockTaskSignal,
) : DeviceControl {
    private val app = context.applicationContext
    private val dpm: DevicePolicyManager? = app.getSystemService(DevicePolicyManager::class.java)
    private val nm: NotificationManager? = app.getSystemService(NotificationManager::class.java)
    private val admin = ComponentName(app, FocusDeviceAdminReceiver::class.java)

    private val shizuku = ShizukuShell()

    private fun isOwner(): Boolean = dpm?.isDeviceOwnerApp(app.packageName) == true

    override fun current(): DeviceCapabilities {
        val owner = isOwner()
        val shizukuState = shizuku.state()
        val viaShizuku = !owner && shizukuState == ShizukuState.Granted
        return DeviceCapabilities(
            level = when {
                owner -> DeploymentLevel.DeviceOwner
                viaShizuku -> DeploymentLevel.Shizuku
                else -> DeploymentLevel.Regular
            },
            canSuspendApps = owner || viaShizuku,
            canUseLockTask = owner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
            notificationControlAvailable = nm != null && policyAccessScreenExists(),
            notificationControlGranted = nm?.isNotificationPolicyAccessGranted == true,
            shizuku = shizukuState,
            shizukuDetails = shizuku.details(),
        )
    }

    override fun requestShizukuPermission(): Boolean = shizuku.requestPermission()

    @Suppress("DEPRECATION")
    private fun policyAccessScreenExists(): Boolean {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        return if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
        } else {
            app.packageManager.resolveActivity(intent, 0) != null
        }
    }

    // ---- Suspension: Device Owner, or Shizuku -------------------------------------------

    override fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String> {
        if (packages.isEmpty()) return emptySet()
        val manager = dpm
        if (manager != null && isOwner()) {
            return manager.setPackagesSuspended(admin, packages.toTypedArray(), suspended).toSet()
        }
        if (shizuku.state() == ShizukuState.Granted) return shizuku.setSuspended(packages, suspended)
        return packages
    }

    override fun forceStop(packages: Set<String>) {
        if (packages.isEmpty()) return
        // Only Shizuku can do this. On a Device Owner, setPackagesSuspended already stops the app.
        if (!isOwner() && shizuku.state() == ShizukuState.Granted) shizuku.forceStop(packages)
    }

    override fun suspendedAmong(packages: Set<String>): Set<String> {
        val manager = dpm
        if (manager == null || !isOwner()) {
            // With neither Device Owner nor a usable Shizuku the state cannot be read. Answering "none"
            // would let the engine clear its journal while apps may still be suspended, so this throws:
            // the engine then keeps the journal and tries again when Shizuku is back.
            check(shizuku.state() == ShizukuState.Granted) { "Suspension state cannot be read without Device Owner or Shizuku" }
            return shizuku.suspendedAmong(packages)
        }
        return packages.filterTo(HashSet()) { pkg ->
            try {
                manager.isPackageSuspended(admin, pkg)
            } catch (_: PackageManager.NameNotFoundException) {
                false // uninstalled: nothing to restrict
            }
        }
    }

    // ---- Blocking uninstall (so fOCUS cannot simply be removed during a session) --------

    override fun setUninstallBlocked(blocked: Boolean): Boolean {
        val manager = dpm
        if (manager != null && isOwner()) {
            return try {
                manager.setUninstallBlocked(admin, app.packageName, blocked)
                manager.isUninstallBlocked(admin, app.packageName) == blocked
            } catch (_: SecurityException) {
                false
            }
        }
        // Not confirmable without Device Owner or Shizuku, in either direction: the engine keeps its journal.
        if (shizuku.state() != ShizukuState.Granted) return false
        return shizuku.setUninstallBlocked(blocked)
    }

    // ---- Device Owner: Lock Task --------------------------------------------------------

    override fun setLockTaskAllowlist(packages: Set<String>?): Boolean {
        val manager = dpm
        if (manager == null || !isOwner() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            lockTask.request(false)
            return packages == null // clearing nothing is fine; setting is not possible
        }
        return try {
            if (packages == null) {
                lockTask.request(false)
                manager.setLockTaskPackages(admin, emptyArray())
                manager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            } else {
                // fOCUS itself must be allowed, or the activity could not enter Lock Task. Home stays
                // reachable (so the allowed phone and emergency apps can be opened), and the power
                // menu stays, so the person can always restart the device.
                manager.setLockTaskPackages(admin, (packages + app.packageName).toTypedArray())
                manager.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS,
                )
                lockTask.request(true)
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    // ---- Do Not Disturb -----------------------------------------------------------------

    override fun currentInterruption(): InterruptionSnapshot? {
        val manager = nm ?: return null
        if (!manager.isNotificationPolicyAccessGranted) return null
        val policy = manager.notificationPolicy
        return InterruptionSnapshot(
            filter = manager.currentInterruptionFilter,
            categories = policy.priorityCategories,
            callSenders = policy.priorityCallSenders,
            messageSenders = policy.priorityMessageSenders,
            visualEffects = policy.suppressedVisualEffects,
            conversationSenders = if (Build.VERSION.SDK_INT >= 29) policy.priorityConversationSenders else 0,
        )
    }

    override fun silenceExceptCalls(): Boolean {
        val manager = nm ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        val old = currentInterruption() ?: return false
        // Calls from anyone (and repeat callers) must get through, and alarms must still ring.
        var categories = NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
            NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
        if (Build.VERSION.SDK_INT >= 28) categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        manager.notificationPolicy = buildPolicy(categories, NotificationManager.Policy.PRIORITY_SENDERS_ANY, old.messageSenders, old.visualEffects, old.conversationSenders)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        return true
    }

    override fun isSilenced(): Boolean =
        nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY

    override fun restoreInterruption(snapshot: InterruptionSnapshot): Boolean {
        val manager = nm ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false
        // If the person turned Do Not Disturb off or changed it, that is their decision: leave it.
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) return true
        manager.notificationPolicy = buildPolicy(
            snapshot.categories, snapshot.callSenders, snapshot.messageSenders, snapshot.visualEffects, snapshot.conversationSenders,
        )
        manager.setInterruptionFilter(snapshot.filter)
        return true
    }

    private fun buildPolicy(categories: Int, callSenders: Int, messageSenders: Int, visualEffects: Int, conversationSenders: Int): NotificationManager.Policy =
        if (Build.VERSION.SDK_INT >= 29) {
            NotificationManager.Policy(categories, callSenders, messageSenders, visualEffects, conversationSenders)
        } else {
            NotificationManager.Policy(categories, callSenders, messageSenders, visualEffects)
        }

    // ---- Ownership ----------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun relinquishOwnership(): Boolean {
        val manager = dpm
        if (manager == null || !isOwner()) return false
        return try {
            manager.clearDeviceOwnerApp(app.packageName)
            DeviceChangeBus.notifyChanged()
            true
        } catch (_: SecurityException) {
            false
        }
    }

    // ---- Changes ------------------------------------------------------------------------

    override fun changes(): Flow<Unit> = merge(DeviceChangeBus.events, systemBroadcasts())

    private fun systemBroadcasts(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        val policyFilter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
        }
        // Shizuku starting, stopping or being allowed changes what fOCUS can do right away.
        val binderReceived = Shizuku.OnBinderReceivedListener { trySend(Unit) }
        val binderDead = Shizuku.OnBinderDeadListener { trySend(Unit) }
        val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> trySend(Unit) }
        Shizuku.addBinderReceivedListener(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, receiver, policyFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, receiver, packageFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose {
            app.unregisterReceiver(receiver)
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
    }
}
