package com.focus.app.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import com.focus.app.domain.DiscoveredApp
import com.focus.app.domain.InstalledAppsSource
import com.focus.app.domain.ProtectedRoles
import com.focus.app.domain.ProtectionPolicy
import com.focus.app.domain.SystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Lists launchable apps with `PackageManager`, without `QUERY_ALL_PACKAGES`: package
 * visibility on Android 11+ comes from the `<queries>` block in the manifest (launcher
 * entries plus the few intents used to find the home, phone, emergency and settings apps).
 *
 * Only the current user profile is covered; apps in a work or private profile are not listed.
 */
class PackageInstalledAppsSource(context: Context) : InstalledAppsSource {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager

    override suspend fun loadApps(): List<DiscoveredApp> = withContext(Dispatchers.IO) {
        val roles = detectRoles()
        val own = appContext.packageName
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        queryActivities(launcherIntent)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                val pkg = info.packageName
                DiscoveredApp(
                    packageName = pkg,
                    label = info.loadLabel(packageManager).toString().ifBlank { pkg },
                    protection = ProtectionPolicy.classify(pkg, own, roles),
                )
            }
    }

    override fun changes(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(Unit)
            }
        }
        // Protected system broadcasts, so no export flag is needed on API 33+.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        appContext.registerReceiver(receiver, filter)
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    /**
     * Finds who currently plays each protected role. If a lookup throws, the whole load
     * fails and the screen shows an error, rather than listing apps with partial protection.
     */
    private fun detectRoles(): ProtectedRoles {
        val launchers = queryActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)).packageNames()
        val settings = queryActivities(Intent(Settings.ACTION_SETTINGS)).packageNames()
        val emergency = queryActivities(Intent(SystemActions.EMERGENCY_ASSISTANCE)).packageNames()

        // Default dialer, plus system apps that handle dialing (the stock dialer stays
        // protected even if a third-party dialer is the default).
        val dialIntents = listOf(Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        val systemDialers = dialIntents
            .flatMap { queryActivities(it) }
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { (it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 }
            .mapTo(HashSet()) { it.packageName }
        val defaultDialer = appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage

        return ProtectedRoles(
            launchers = launchers,
            phone = systemDialers + setOfNotNull(defaultDialer),
            emergency = emergency,
            settings = settings,
        )
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

    private fun List<ResolveInfo>.packageNames(): Set<String> =
        mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
}
