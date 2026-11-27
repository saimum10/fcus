package com.focus.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (4dp grid). Use these instead of ad-hoc dp values in screens. */
@Immutable
class FocusSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 32.dp,
    /** Minimum touch target (accessibility). */
    val touchTarget: Dp = 48.dp,
    /** Widest a screen's content gets; on tablets, foldables and landscape it is centered instead of stretched. */
    val contentMaxWidth: Dp = 600.dp,
)

internal val LocalFocusSpacing = staticCompositionLocalOf { FocusSpacing() }

val MaterialTheme.spacing: FocusSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalFocusSpacing.current

/** Flat, modestly rounded shapes. */
internal val FocusShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
