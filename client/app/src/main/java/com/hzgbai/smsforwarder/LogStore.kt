package com.hzgbai.smsforwarder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minimal ring-buffer log written to a file, capped at MAX_LINES. */
object LogStore {

    private const val FILE_NAME = "forwarder.log"
    private const val MAX_LINES = 300
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE_NAME)

    fun add(ctx: Context, message: String) {
        synchronized(lock) {
            try {
                val f = file(ctx)
                val line = "${fmt.format(Date())}  $message"
                f.appendText(line + "\n", Charsets.UTF_8)
                trimIfNeeded(f)
            } catch (_: Throwable) {
                // A logging failure must never affect the main flow
            }
        }
    }

    private fun trimIfNeeded(f: File) {
        if (f.length() < 64 * 1024) return
        val lines = f.readLines(Charsets.UTF_8)
        if (lines.size <= MAX_LINES) return
        f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    /** Newest entries first. */
    fun read(ctx: Context, limit: Int = 120): String {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return "(no log yet)"
            return try {
                val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    "(no log yet)"
                } else {
                    lines.takeLast(limit).reversed().joinToString("\n")
                }
            } catch (t: Throwable) {
                "Failed to read the log: ${t.message}"
            }
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            runCatching { file(ctx).writeText("", Charsets.UTF_8) }
        }
    }
}
