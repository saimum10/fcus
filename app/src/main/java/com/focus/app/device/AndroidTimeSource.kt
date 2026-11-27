package com.focus.app.device

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.focus.app.domain.TimeSnapshot
import com.focus.app.domain.TimeSource

/**
 * Both clocks plus the boot counter. `Settings.Global.BOOT_COUNT` needs no permission;
 * if a device does not report it, the engine falls back to comparing the monotonic clock.
 */
class AndroidTimeSource(context: Context) : TimeSource {
    private val resolver = context.applicationContext.contentResolver

    override fun now(): TimeSnapshot = TimeSnapshot(
        wallMillis = System.currentTimeMillis(),
        elapsedMillis = SystemClock.elapsedRealtime(),
        bootCount = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 },
    )
}
