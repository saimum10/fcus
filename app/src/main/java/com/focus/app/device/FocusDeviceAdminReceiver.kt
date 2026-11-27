package com.focus.app.device

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The component Android needs to make fOCUS a Device Owner. It declares no policies and
 * does nothing by itself: fOCUS cannot promote itself, ownership is only granted by the
 * system during provisioning (see the in-app guide). It only reports ownership changes so
 * the restriction engine can re-check right away.
 */
class FocusDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = DeviceChangeBus.notifyChanged()

    override fun onDisabled(context: Context, intent: Intent) = DeviceChangeBus.notifyChanged()
}

/** In-process signal that device ownership changed. Carries no data. */
object DeviceChangeBus {
    private val mutableEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Unit> = mutableEvents

    fun notifyChanged() {
        mutableEvents.tryEmit(Unit)
    }
}
