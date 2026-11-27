package com.focus.app.ui.theme

import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast checks for the palette in [FocusPalette].
 * Runs in Phase 8 (no tests are executed in Phases 1-7).
 */
class FocusPaletteContrastTest {

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertAtLeast(minimum: Double, foreground: Long, background: Long, label: String) {
        val ratio = contrast(foreground, background)
        assertTrue("$label: contrast %.2f is below %.1f".format(ratio, minimum), ratio >= minimum)
    }

    private val aaNormalText = 4.5
    private val aaLargeText = 3.0

    @Test
    fun dark_textAndPrimary_meetAaOnBackgroundAndSurface() = with(FocusPalette.Dark) {
        assertAtLeast(aaNormalText, Text, Background, "dark text/background")
        assertAtLeast(aaNormalText, Text, Surface, "dark text/surface")
        assertAtLeast(aaNormalText, Primary, Background, "dark primary/background")
        assertAtLeast(aaNormalText, Primary, Surface, "dark primary/surface")
        assertAtLeast(aaNormalText, OnPrimary, Primary, "dark onPrimary/primary")
    }

    @Test
    fun dark_secondaryText_meetsAa() = with(FocusPalette.Dark) {
        assertAtLeast(aaNormalText, TextSecondary, Background, "dark secondary/background")
        assertAtLeast(aaNormalText, TextSecondary, Surface, "dark secondary/surface")
    }

    @Test
    fun light_textAndPrimary_meetAaOnBackgroundAndSurface() = with(FocusPalette.Light) {
        assertAtLeast(aaNormalText, Text, Background, "light text/background")
        assertAtLeast(aaNormalText, Text, Surface, "light text/surface")
        assertAtLeast(aaNormalText, Primary, Background, "light primary/background")
        assertAtLeast(aaNormalText, Primary, Surface, "light primary/surface")
        assertAtLeast(aaNormalText, OnPrimary, Primary, "light onPrimary/primary")
    }

    /**
     * KNOWN SHORTFALL: the specified light secondary color
     * #747B70 measures about 4.0:1 on the background and 4.4:1 on white, below the
     * 4.5:1 AA minimum for normal-size text. It is only asserted at the 3:1 level
     * (large text / UI components). Since Phase 2 the light theme uses the derived
     * [FocusPalette.Light.TextSecondaryAccessible] for small secondary text (next test).
     */
    @Test
    fun light_secondary_meetsLargeTextLevelOnly() = with(FocusPalette.Light) {
        assertAtLeast(aaLargeText, TextSecondary, Background, "light secondary/background")
        assertAtLeast(aaLargeText, TextSecondary, Surface, "light secondary/surface")
    }

    @Test
    fun light_accessibleSecondary_meetsAa() = with(FocusPalette.Light) {
        assertAtLeast(aaNormalText, TextSecondaryAccessible, Background, "light accessible secondary/background")
        assertAtLeast(aaNormalText, TextSecondaryAccessible, Surface, "light accessible secondary/surface")
    }
}
