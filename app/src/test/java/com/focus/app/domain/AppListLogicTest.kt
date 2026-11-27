package com.focus.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sorting, search, protected/selectable split. Written in Phase 3, run in Phase 8. */
class AppListLogicTest {

    private fun app(pkg: String, label: String, protection: ProtectionReason? = null) =
        DiscoveredApp(packageName = pkg, label = label, protection = protection)

    @Test
    fun sorted_ignoresCase_andBreaksTiesByPackage() {
        val apps = listOf(
            app("com.c", "cherry"),
            app("com.b2", "banana"),
            app("com.a", "Apple"),
            app("com.b1", "Banana"),
        )
        assertEquals(
            listOf("com.a", "com.b1", "com.b2", "com.c"),
            AppListLogic.sorted(apps).map { it.packageName },
        )
    }

    @Test
    fun filter_blankQueryKeepsEverything() {
        val apps = listOf(app("com.a", "Apple"), app("com.b", "Banana"))
        assertEquals(apps, AppListLogic.filter(apps, ""))
        assertEquals(apps, AppListLogic.filter(apps, "   "))
    }

    @Test
    fun filter_matchesLabelCaseInsensitively_andTrims() {
        val apps = listOf(app("com.a", "Apple Music"), app("com.b", "Banana"))
        assertEquals(listOf("com.a"), AppListLogic.filter(apps, "  MUSIC ").map { it.packageName })
    }

    @Test
    fun filter_doesNotMatchPackageName() {
        val apps = listOf(app("com.example.banana", "Notes"))
        assertTrue(AppListLogic.filter(apps, "banana").isEmpty())
    }

    @Test
    fun split_separatesProtectedApps() {
        val notes = app("com.notes", "Notes")
        val phone = app("com.phone", "Phone", ProtectionReason.Phone)
        val (selectable, protectedApps) = AppListLogic.split(listOf(notes, phone))
        assertEquals(listOf(notes), selectable)
        assertEquals(listOf(phone), protectedApps)
    }

    @Test
    fun selectablePackages_excludesProtectedApps() {
        val apps = listOf(
            app("com.notes", "Notes"),
            app("com.focus.app", "fOCUS", ProtectionReason.ThisApp),
        )
        assertEquals(setOf("com.notes"), AppListLogic.selectablePackages(apps))
    }
}
