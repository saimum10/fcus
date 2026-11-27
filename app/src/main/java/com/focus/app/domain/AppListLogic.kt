package com.focus.app.domain

import java.text.Collator
import java.util.Locale

/** Pure list rules for the app-selection screen, so they can be unit tested without Android. */
object AppListLogic {

    /** Locale-aware, case-insensitive order by label; package name breaks ties. */
    fun sorted(apps: List<DiscoveredApp>): List<DiscoveredApp> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.SECONDARY }
        val byLabel = Comparator<DiscoveredApp> { a, b -> collator.compare(a.label, b.label) }
        return apps.sortedWith(byLabel.thenBy { it.packageName })
    }

    /** Case-insensitive match on the visible label; a blank query keeps everything. */
    fun filter(apps: List<DiscoveredApp>, query: String): List<DiscoveredApp> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { it.label.contains(q, ignoreCase = true) }
    }

    /** First: apps that can be selected. Second: protected apps, shown only with their reason. */
    fun split(apps: List<DiscoveredApp>): Pair<List<DiscoveredApp>, List<DiscoveredApp>> {
        val (protectedApps, selectable) = apps.partition { it.isProtected }
        return selectable to protectedApps
    }

    fun selectablePackages(apps: List<DiscoveredApp>): Set<String> =
        apps.filterNot { it.isProtected }.mapTo(HashSet()) { it.packageName }
}
