package com.focus.app.ui

import android.app.ActivityManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focus.app.FocusApplication
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FocusApplication
        val dependencies = app.dependencies
        // Lock Task can only be started from a visible activity, so it is followed only while
        // fOCUS is on screen. The Device Owner allowlist (see AndroidDeviceControl) keeps phone,
        // home and emergency apps reachable; removing the allowlist ends it at any time.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dependencies.lockTask.wanted.collect { wanted -> followLockTask(wanted) }
            }
        }
        setContent {
            FocusApp(dependencies = dependencies)
        }
    }

    private fun followLockTask(wanted: Boolean) {
        val manager = getSystemService(ActivityManager::class.java) ?: return
        val locked = manager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        try {
            if (wanted && !locked) startLockTask() else if (!wanted && locked) stopLockTask()
        } catch (_: RuntimeException) {
            // Not permitted right now (not on the allowlist, or ownership is gone): nothing is shown as locked.
        }
    }
}
