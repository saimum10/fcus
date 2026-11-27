package com.focus.app.session

import com.focus.app.domain.DeploymentLevel
import com.focus.app.domain.DeviceCapabilities
import com.focus.app.domain.DeviceControl
import com.focus.app.domain.DiscoveredApp
import com.focus.app.domain.InstalledAppsSource
import com.focus.app.domain.InterruptionSnapshot
import com.focus.app.domain.RestrictionJournal
import com.focus.app.domain.RestrictionJournalEntry
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** In-memory fakes for the restriction tests. They keep the "device" state so tests can inspect what really happened. */

class FakeApps(var apps: List<DiscoveredApp>) : InstalledAppsSource {
    var fail = false

    override suspend fun loadApps(): List<DiscoveredApp> {
        if (fail) throw IllegalStateException("apps")
        return apps
    }

    override fun changes(): Flow<Unit> = emptyFlow()
}

class FakeJournal(var stored: RestrictionJournalEntry? = null) : RestrictionJournal {
    var failLoad = false
    var failSave = false

    override suspend fun load(): RestrictionJournalEntry? {
        if (failLoad) throw IOException("load")
        return stored
    }

    override suspend fun save(entry: RestrictionJournalEntry) {
        if (failSave) throw IOException("save")
        stored = entry
    }

    override suspend fun clear() {
        if (failSave) throw IOException("clear")
        stored = null
    }
}

class FakeDevice(var caps: DeviceCapabilities) : DeviceControl {
    /** What the "system" really has suspended. */
    val suspended = mutableSetOf<String>()
    var refuse: Set<String> = emptySet()
    var refuseUnsuspend: Set<String> = emptySet()
    var throwOnSuspend = false
    var lockTaskAllowlist: Set<String>? = null
    var lockTaskRefuses = false

    var interruption = InterruptionSnapshot(filter = 1, categories = 0, callSenders = 0, messageSenders = 0, visualEffects = 0, conversationSenders = 0)
    var silenced = false
    var dndGranted = true
    var userChangedDnd = false
    var ownershipCleared = false
    var uninstallBlocked = false
    /** Order of the calls that matter: "stop:<packages>", "suspend", "unsuspend". */
    val log = mutableListOf<String>()
    var refuseUninstallBlock = false
    var refuseUninstallUnblock = false
    val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override fun current(): DeviceCapabilities = caps

    override fun forceStop(packages: Set<String>) {
        log += "stop:" + packages.sorted().joinToString(",")
    }

    override fun setSuspended(packages: Set<String>, suspended: Boolean): Set<String> {
        log += if (suspended) "suspend" else "unsuspend"
        if (throwOnSuspend) throw SecurityException("not owner")
        val refused = packages.filterTo(HashSet()) { it in (if (suspended) refuse else refuseUnsuspend) }
        packages.filter { it !in refused }.forEach { if (suspended) this.suspended += it else this.suspended -= it }
        return refused
    }

    override fun suspendedAmong(packages: Set<String>): Set<String> = packages.filterTo(HashSet()) { it in suspended }

    override fun setLockTaskAllowlist(packages: Set<String>?): Boolean {
        if (packages != null && lockTaskRefuses) return false
        lockTaskAllowlist = packages
        return true
    }

    override fun currentInterruption(): InterruptionSnapshot? = if (dndGranted) interruption else null

    override fun silenceExceptCalls(): Boolean {
        if (!dndGranted) return false
        silenced = true
        return true
    }

    override fun isSilenced(): Boolean = silenced && !userChangedDnd

    override fun restoreInterruption(snapshot: InterruptionSnapshot): Boolean {
        if (!dndGranted) return false
        if (userChangedDnd) return true // the person's own change is left alone
        silenced = false
        interruption = snapshot
        return true
    }

    override fun setUninstallBlocked(blocked: Boolean): Boolean {
        if (blocked && refuseUninstallBlock) return false
        if (!blocked && refuseUninstallUnblock) return false
        uninstallBlocked = blocked
        return true
    }

    override fun relinquishOwnership(): Boolean {
        if (caps.level != DeploymentLevel.DeviceOwner) return false
        ownershipCleared = true
        caps = caps.copy(level = DeploymentLevel.Regular, canSuspendApps = false, canUseLockTask = false)
        return true
    }

    override fun changes(): Flow<Unit> = changeEvents
}
