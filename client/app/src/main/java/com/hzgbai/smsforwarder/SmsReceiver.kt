package com.hzgbai.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import org.json.JSONObject

/**
 * Entry point for the SMS_RECEIVED event.
 *
 * This is an ordered broadcast, so onReceive must not do network I/O. It queues
 * the message and hands off to WorkManager; goAsync() keeps the process alive
 * until the background thread finishes.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.enabled) {
            LogStore.add(ctx, "SMS received, but forwarding is disabled")
            return
        }

        val pendingResult = goAsync()
        Thread(
            Runnable {
                try {
                    handle(ctx, prefs, intent)
                } catch (t: Throwable) {
                    LogStore.add(ctx, "Failed to handle SMS: ${t.message}")
                } finally {
                    pendingResult.finish()
                }
            },
            "sms-receiver",
        ).start()
    }

    private fun handle(ctx: Context, prefs: Prefs, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
        if (parts.isEmpty()) {
            LogStore.add(ctx, "Got SMS_RECEIVED but could not parse any content")
            return
        }

        // Long messages arrive split into parts; merge them per sender before uploading
        val bodies = LinkedHashMap<String, StringBuilder>()
        var timestamp = 0L
        for (part in parts) {
            val address = part.displayOriginatingAddress
                ?: part.originatingAddress
                ?: "(Unknown)"
            bodies.getOrPut(address) { StringBuilder() }
                .append(part.displayMessageBody ?: part.messageBody ?: "")
            if (timestamp == 0L) {
                timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    part.timestampMillis
                } else {
                    System.currentTimeMillis()
                }
            }
        }

        for ((address, body) in bodies) {
            val text = body.toString()
            val payload = JSONObject()
                .put("from", address)
                .put("content", text)
                .put("deviceTs", timestamp)
                .put("device", prefs.effectiveDeviceName())
                // Idempotency key: the server deduplicates on it, so retries cannot duplicate
                .put("clientId", "sms:$address:$timestamp:${text.hashCode()}")
            PendingQueue.add(ctx, payload)
        }

        LogStore.add(ctx, "Received ${bodies.size} message(s) from ${parts.size} part(s), queued")
        UploadWorker.enqueue(ctx)
    }
}
