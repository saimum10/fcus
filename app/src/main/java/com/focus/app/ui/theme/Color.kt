package com.focus.app.ui.theme

/**
 * Raw palette from the product spec (prompt.txt, "Design"). Plain ARGB longs so
 * the values can be unit-tested (contrast) without Compose.
 *
 * Tokens marked "derived" are not in the spec; they are the minimum needed to
 * fill a Material 3 color scheme and are chosen to keep the UI flat and calm.
 */
internal object FocusPalette {

    object Dark {
        const val Background: Long = 0xFF111311
        const val Surface: Long = 0xFF1B1E1B
        const val Primary: Long = 0xFFC7E88B
        const val Text: Long = 0xFFF2F3EC
        const val TextSecondary: Long = 0xFF969C91

        /** Derived: text drawn on [Primary]. */
        const val OnPrimary: Long = 0xFF111311

        /** Derived: hairline dividers / outlines. */
        const val Hairline: Long = 0xFF2A2E29
    }

    object Light {
        const val Background: Long = 0xFFF5F6F0
        const val Surface: Long = 0xFFFFFFFF
        const val Primary: Long = 0xFF536D36
        const val Text: Long = 0xFF20241E
        const val TextSecondary: Long = 0xFF747B70

        /**
         * Derived (Phase 2): the specified [TextSecondary] measures only ~4.0:1 on the
         * light background, below the AA 4.5:1 minimum for normal text. Small secondary
         * text in the light theme uses this slightly darker tone of the same hue
         * (~5.5:1). [TextSecondary] itself stays for non-text and large-text use.
         */
        const val TextSecondaryAccessible: Long = 0xFF5F6659

        /** Derived: text drawn on [Primary]. */
        const val OnPrimary: Long = 0xFFFFFFFF

        /** Derived: hairline dividers / outlines. */
        const val Hairline: Long = 0xFFDFE1D6
    }
}
