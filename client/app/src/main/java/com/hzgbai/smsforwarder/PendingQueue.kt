package com.hzgbai.smsforwarder

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Pending upload queue, persisted as a JSONL file on disk. Survives network
 * failures, process death and reboots; UploadWorker retries whatever is left.
 */
object PendingQueue {

    private const val FILE_NAME = "pending.jsonl"
    private const val MAX_ITEMS = 2000
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE_NAME)

    /** Adds one message to the queue. */
    fun add(ctx: Context, payload: JSONObject) {
        synchronized(lock) {
            try {
                val f = file(ctx)
                if (f.exists() && countItems(f) >= MAX_ITEMS) {
                    dropOldest(f, countItems(f) - MAX_ITEMS + 1)
                    LogStore.add(ctx, "Queue is full ($MAX_ITEMS), dropped the oldest message")
                }
                f.appendText(payload.toString() + "\n", Charsets.UTF_8)
            } catch (t: Throwable) {
                LogStore.add(ctx, "Failed to enqueue: ${t.message}")
            }
        }
    }

    /**
     * Tries to send items in order. The handler returns true when the item was
     * sent and can be removed. maxItems caps one run so it cannot be cut short
     * by WorkManager. Returns the number sent.
     */
    fun drain(ctx: Context, maxItems: Int = 50, handler: (JSONObject) -> Boolean): Int {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return 0

            val lines = try {
                f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            } catch (t: Throwable) {
                LogStore.add(ctx, "Failed to read the queue: ${t.message}")
                return 0
            }
            if (lines.isEmpty()) return 0

            val keep = ArrayList<String>(lines.size)
            var processed = 0
            var sent = 0

            for (line in lines) {
                if (processed >= maxItems) {
                    keep.add(line)
                    continue
                }
                val obj = runCatching { JSONObject(line) }.getOrNull()
                if (obj == null) {
                    LogStore.add(ctx, "Dropped a corrupted queue entry")
                    continue
                }
                processed++
                if (handler(obj)) sent++ else keep.add(line)
            }

            try {
                val text = if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n")
                writeAtomically(f, text)
            } catch (t: Throwable) {
                LogStore.add(ctx, "Failed to write the queue back: ${t.message}")
            }
            return sent
        }
    }

    fun size(ctx: Context): Int = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) 0 else countItems(f)
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            runCatching { writeAtomically(file(ctx), "") }
        }
    }

    /** Writes to a temp file and renames it, so a mid-write kill cannot corrupt the queue. */
    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            // renameTo fails on some ROMs; fall back to overwriting in place
            f.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun countItems(f: File): Int = try {
        f.readLines(Charsets.UTF_8).count { it.isNotBlank() }
    } catch (_: Throwable) {
        0
    }

    private fun dropOldest(f: File, count: Int) {
        val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val kept = lines.drop(count)
        writeAtomically(f, if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }
}
