package com.hzgbai.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Requeues anything still pending after a boot or an app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val ctx = context.applicationContext
                val pending = PendingQueue.size(ctx)
                if (pending > 0) {
                    LogStore.add(ctx, "Found $pending pending messages on startup, requeueing the upload")
                    UploadWorker.enqueue(ctx)
                }
            }
        }
    }
}
