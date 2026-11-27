package com.focus.app.device

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focus.app.domain.SessionControllerHolder
import com.focus.app.domain.WakeScheduler
import java.util.concurrent.TimeUnit

/**
 * One unique, replaceable WorkManager request that calls the session engine's
 * `reconcile()` at about the requested time. WorkManager keeps it across process death
 * and restarts, but delivers "at or after", not exactly on time: the in-process timer and
 * every app start / resume cover the gap, and the session end itself is always decided
 * from the stored end time, never from when the worker happened to run.
 */
class WorkManagerWakeScheduler(context: Context) : WakeScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun scheduleReconcile(delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelReconcile() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "session-reconcile"
    }
}

/** Runs [com.focus.app.domain.SessionController.reconcile] from the background. */
class ReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val holder = applicationContext as? SessionControllerHolder ?: return Result.failure()
        holder.sessionController.reconcile()
        return Result.success()
    }
}
