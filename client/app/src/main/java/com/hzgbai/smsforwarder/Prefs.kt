package com.hzgbai.smsforwarder

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** Configuration storage: server URL and credentials. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var serverUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    /** Must match the ADMIN secret on the server. */
    var password: String
        get() = sp.getString(KEY_PWD, "") ?: ""
        set(v) = sp.edit().putString(KEY_PWD, v).apply()

    /**
     * WAF whitelist field name. Every request must carry it or the edge blocks
     * it before it reaches the Worker. It is sent as a query parameter and as a
     * custom request header, so the rule matches either way.
     *
     * Empty means "send nothing", which is only useful against a local
     * `wrangler dev` instance. The getter normalizes as well as the setter, so a
     * stored value without the `access_` prefix still works.
     */
    var accessField: String
        get() = normalizeAccessField(sp.getString(KEY_ACCESS_FIELD, "") ?: "")
        set(v) = sp.edit().putString(KEY_ACCESS_FIELD, normalizeAccessField(v)).apply()

    /** Device name shown in the server-side list; falls back to the model. */
    var deviceName: String
        get() = sp.getString(KEY_DEVICE, "") ?: ""
        set(v) = sp.edit().putString(KEY_DEVICE, v.trim()).apply()

    /** Highest inbox _id already backfilled, to avoid sending duplicates. */
    var lastBackfillId: Long
        get() = sp.getLong(KEY_LAST_BACKFILL, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_BACKFILL, v).apply()

    fun effectiveDeviceName(): String =
        deviceName.ifBlank { "${Build.MANUFACTURER} ${Build.MODEL}".trim() }

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && password.isNotBlank()

    companion object {
        private const val NAME = "sms_forwarder"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "server_url"
        private const val KEY_PWD = "admin_password"
        private const val KEY_ACCESS_FIELD = "access_field"
        private const val KEY_DEVICE = "device_name"
        private const val KEY_LAST_BACKFILL = "last_backfill_id"

        const val ACCESS_PREFIX = "access_"

        /**
         * Accepts either the full field name or just the 32-character suffix and
         * adds the `access_` prefix when missing. An empty string means "send
         * nothing".
         */
        fun normalizeAccessField(raw: String): String {
            val v = raw.trim()
            if (v.isEmpty()) return ""
            return if (v.startsWith(ACCESS_PREFIX)) v else ACCESS_PREFIX + v
        }
    }
}
