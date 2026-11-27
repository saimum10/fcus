package com.focus.app.device

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.focus.app.domain.AppIconBitmap
import com.focus.app.domain.AppIconSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders app icons (including adaptive icons) to raw pixels, with a small in-memory cache.
 * The cache is limited by memory ([CACHE_BYTES]), not by entry count: an icon rendered for
 * a large screen density is several times bigger than one for a small screen, and a count
 * limit would let the cache grow with the density.
 */
class PackageAppIconSource(context: Context) : AppIconSource {
    private val packageManager: PackageManager = context.applicationContext.packageManager
    private val cache = object : LruCache<String, AppIconBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: AppIconBitmap): Int = value.argb.size * BYTES_PER_PIXEL
    }

    override suspend fun loadIcon(packageName: String, sizePx: Int): AppIconBitmap? {
        if (sizePx <= 0) return null
        val key = "$packageName@$sizePx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val drawable = try {
                packageManager.getApplicationIcon(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext null
            }
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            try {
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(Canvas(bitmap))
                val pixels = IntArray(sizePx * sizePx)
                bitmap.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
                AppIconBitmap(sizePx, sizePx, pixels).also { cache.put(key, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun invalidate() {
        cache.evictAll()
    }

    private companion object {
        const val CACHE_BYTES = 6 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4
    }
}
