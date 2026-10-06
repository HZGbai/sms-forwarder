package com.hzgbai.smsforwarder

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** Reads the local SMS inbox, used for the initial full backfill. */
object SmsInbox {

    data class Sms(val id: Long, val address: String, val body: String, val date: Long)

    /**
     * Reads the inbox newest first.
     * @param limit maximum number of messages to read
     * @param sinceId only return records whose _id is greater than this (incremental backfill)
     */
    fun read(ctx: Context, limit: Int = 500, sinceId: Long = 0L): List<Sms> {
        val result = ArrayList<Sms>()
        val projection = arrayOf("_id", "address", "body", "date")
        val cursor = try {
            ctx.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                projection,
                null,
                null,
                "date DESC",
            )
        } catch (t: Throwable) {
            LogStore.add(ctx, "Failed to read the inbox: ${t.message}")
            null
        } ?: return result

        cursor.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val addrIdx = c.getColumnIndex("address")
            val bodyIdx = c.getColumnIndex("body")
            val dateIdx = c.getColumnIndex("date")
            while (c.moveToNext() && result.size < limit) {
                val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                if (id <= sinceId) continue
                result.add(
                    Sms(
                        id = id,
                        address = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else "",
                        body = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else "",
                        date = if (dateIdx >= 0) c.getLong(dateIdx) else 0L,
                    ),
                )
            }
        }
        return result
    }

    /** Queues local messages for upload and returns how many were added. */
    fun enqueueAll(ctx: Context, items: List<Sms>): Int {
        val prefs = Prefs(ctx)
        var count = 0
        var maxId = prefs.lastBackfillId
        for (item in items) {
            val payload = JSONObject()
                .put("from", item.address.ifBlank { "(Unknown)" })
                .put("content", item.body)
                .put("deviceTs", item.date)
                .put("device", prefs.effectiveDeviceName())
                .put("clientId", "inbox:${item.id}")
            PendingQueue.add(ctx, payload)
            if (item.id > maxId) maxId = item.id
            count++
        }
        if (maxId > prefs.lastBackfillId) prefs.lastBackfillId = maxId
        return count
    }
}
