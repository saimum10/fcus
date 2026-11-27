package com.focus.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Flat design: every container role maps to the single Surface color, so no
// default Material tint can leak in. Dynamic (wallpaper) color is intentionally
// not used; the palette is fixed by the product spec.

private val DarkColors: ColorScheme = with(FocusPalette.Dark) {
    darkColorScheme(
        primary = Color(Primary),
        onPrimary = Color(OnPrimary),
        primaryContainer = Color(Surface),
        onPrimaryContainer = Color(Text),
        inversePrimary = Color(Primary),
        secondary = Color(TextSecondary),
        onSecondary = Color(Background),
        secondaryContainer = Color(Surface),
        onSecondaryContainer = Color(Text),
        tertiary = Color(Primary),
        onTertiary = Color(OnPrimary),
        tertiaryContainer = Color(Surface),
        onTertiaryContainer = Color(Text),
        background = Color(Background),
        onBackground = Color(Text),
        surface = Color(Surface),
        onSurface = Color(Text),
        surfaceVariant = Color(Surface),
        onSurfaceVariant = Color(TextSecondary),
        surfaceTint = Color(Surface),
        inverseSurface = Color(Text),
        inverseOnSurface = Color(Background),
        outline = Color(TextSecondary),
        outlineVariant = Color(Hairline),
        surfaceBright = Color(Surface),
        surfaceDim = Color(Background),
        surfaceContainerLowest = Color(Background),
        surfaceContainerLow = Color(Surface),
        surfaceContainer = Color(Surface),
        surfaceContainerHigh = Color(Surface),
        surfaceContainerHighest = Color(Surface),
    )
}

private val LightColors: ColorScheme = with(FocusPalette.Light) {
    lightColorScheme(
        primary = Color(Primary),
        onPrimary = Color(OnPrimary),
        primaryContainer = Color(Surface),
        onPrimaryContainer = Color(Text),
        inversePrimary = Color(Primary),
        secondary = Color(TextSecondaryAccessible),
        onSecondary = Color(Surface),
        secondaryContainer = Color(Surface),
        onSecondaryContainer = Color(Text),
        tertiary = Color(Primary),
        onTertiary = Color(OnPrimary),
        tertiaryContainer = Color(Surface),
        onTertiaryContainer = Color(Text),
        background = Color(Background),
        onBackground = Color(Text),
        surface = Color(Surface),
        onSurface = Color(Text),
        surfaceVariant = Color(Surface),
        onSurfaceVariant = Color(TextSecondaryAccessible),
        surfaceTint = Color(Surface),
        inverseSurface = Color(Text),
        inverseOnSurface = Color(Background),
        outline = Color(TextSecondary),
        outlineVariant = Color(Hairline),
        surfaceBright = Color(Surface),
        surfaceDim = Color(Background),
        surfaceContainerLowest = Color(Surface),
        surfaceContainerLow = Color(Surface),
        surfaceContainer = Color(Surface),
        surfaceContainerHigh = Color(Surface),
        surfaceContainerHighest = Color(Surface),
    )
}

/** Follows the system light/dark setting. Tokens: [FocusTypography], [FocusSpacing], [FocusShapes]. */
@Composable
fun FocusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFocusSpacing provides FocusSpacing()) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = FocusTypography,
            shapes = FocusShapes,
            content = content,
        )
    }
}
