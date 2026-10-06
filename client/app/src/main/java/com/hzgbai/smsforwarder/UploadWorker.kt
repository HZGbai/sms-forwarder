package com.hzgbai.smsforwarder

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Posts queued messages to the Worker one by one, with exponential backoff and
 * a cap of MAX_ATTEMPTS retries so a misconfiguration cannot retry forever.
 *
 * No NetworkType constraint is set on purpose: the target may be an intranet
 * address, so "has validated internet access" is not a valid precondition.
 * Without the constraint the job runs immediately in-process and retries cover
 * failures.
 */
class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        LogStore.add(ctx, "Starting upload (attempt ${runAttemptCount + 1})")

        if (!prefs.enabled) {
            LogStore.add(ctx, "Forwarding is disabled, skipping upload")
            return Result.success()
        }
        if (!prefs.isConfigured()) {
            LogStore.add(ctx, "Server URL or password not configured; keeping the queue")
            return Result.failure()
        }

        val pending = PendingQueue.size(ctx)
        if (pending == 0) {
            LogStore.add(ctx, "Queue is empty, nothing to upload")
            return Result.success()
        }

        val sent = PendingQueue.drain(ctx, MAX_PER_RUN) { payload ->
            ApiClient.send(ctx, prefs, payload)
        }

        val remaining = PendingQueue.size(ctx)
        LogStore.add(ctx, "Sent $sent this round, $remaining remaining")

        if (remaining == 0) return Result.success()

        if (runAttemptCount >= MAX_ATTEMPTS) {
            LogStore.add(ctx, "Still failing after $runAttemptCount retries; pausing until the next SMS or restart")
            return Result.failure()
        }
        return Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "sms-upload"
        private const val MAX_PER_RUN = 50
        private const val MAX_ATTEMPTS = 15

        fun enqueue(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // APPEND_OR_REPLACE appends to a running chain so newly queued messages
            // are handled, and replaces a finished or cancelled one so it cannot
            // get stuck.
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
