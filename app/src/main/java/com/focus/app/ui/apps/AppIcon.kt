package com.focus.app.ui.apps

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.focus.app.domain.AppIconBitmap
import com.focus.app.domain.AppIconSource

/**
 * The app's launcher icon, loaded off the main thread when the row first appears. Until
 * it arrives (or if the app has no icon) the first letter of the label is shown.
 * [generation] changes when the list was reloaded so updated icons are fetched again.
 */
@Composable
fun AppIcon(
    packageName: String,
    label: String,
    generation: Int,
    iconSource: AppIconSource,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val image by produceState<ImageBitmap?>(initialValue = null, packageName, generation, sizePx) {
        value = iconSource.loadIcon(packageName, sizePx)?.toImageBitmap()
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            // The label next to the icon already names the app.
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Text(
                text = label.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun AppIconBitmap.toImageBitmap(): ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
