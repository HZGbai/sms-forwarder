package com.hzgbai.smsforwarder

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Upload client. Uses HttpURLConnection rather than pulling in OkHttp.
 *
 * Each request carries the WAF whitelist field, sent as both a query parameter
 * and a custom request header, and the ADMIN password, sent under both
 * X-Admin-Password and AccessToken.
 *
 * 403 is returned by more than one layer; [classify403] tells the cases apart.
 */
object ApiClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Uploads a single message. Returns true on success (2xx with our JSON). */
    fun send(ctx: Context, prefs: Prefs, payload: JSONObject): Boolean {
        val base = prefs.serverUrl.trim().trimEnd('/')
        if (base.isEmpty()) {
            LogStore.add(ctx, "Server URL is not configured")
            return false
        }

        val field = prefs.accessField
        return try {
            val url = "$base/api/sms" + accessQuery(field)
            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                // Redirects stay disabled: a blocked POST would otherwise be
                // downgraded to GET and answered with an HTML page under a 200.
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("User-Agent", "SmsForwarder-Android/1.0")
                applyAuth(conn, prefs.password, field)

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = readBody(conn, code)
                val looksJson = body.trimStart().startsWith("{")

                when {
                    code in 300..399 -> {
                        val loc = conn.getHeaderField("Location").orEmpty()
                        LogStore.add(
                            ctx,
                            "Upload blocked by the edge ($code): redirected to ${loc.take(80)}. " +
                                "Check that the access field matches the WAF rule",
                        )
                        false
                    }

                    code in 200..299 -> {
                        // A 2xx is not enough; the body has to be our JSON.
                        if (!looksJson) {
                            LogStore.add(
                                ctx,
                                "Upload failed: response is not JSON ($code), " +
                                    "an intermediary may have replaced it. First 120 chars: ${body.take(120)}",
                            )
                            false
                        } else {
                            val dup = body.contains("\"duplicate\": true")
                            LogStore.add(
                                ctx,
                                "Uploaded ($code)${if (dup) " [already on server]" else ""}: " +
                                    payload.optString("from"),
                            )
                            true
                        }
                    }

                    code == 401 -> {
                        LogStore.add(ctx, "Upload failed (401): ADMIN password is missing")
                        false
                    }

                    code == 403 -> {
                        LogStore.add(ctx, "Upload failed (403): ${classify403(conn, body, looksJson)}")
                        false
                    }

                    else -> {
                        LogStore.add(ctx, "Upload failed ($code): ${body.take(160)}")
                        false
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            LogStore.add(ctx, "Upload error: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Connectivity check for the settings screen. Hits /api/health, which lives
     * under /api/ so the WAF Skip rule covers it.
     */
    fun probe(ctx: Context, prefs: Prefs): String {
        val base = prefs.serverUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "Server URL is empty"

        val field = prefs.accessField
        return try {
            val url = "$base/api/health" + accessQuery(field)
            val conn = (URL(url).openConnection() as HttpURLConnection)
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "SmsForwarder-Android/1.0")
                if (field.isNotEmpty()) conn.setRequestProperty(field, "1")

                val code = conn.responseCode
                val body = readBody(conn, code)
                val looksJson = body.trimStart().startsWith("{")

                when {
                    code in 300..399 ->
                        "Blocked by the edge ($code, redirect). " +
                            "Check that the access field matches the WAF rule"
                    code in 200..299 && looksJson -> "HTTP $code  ${body.take(200)}"
                    code in 200..299 ->
                        "HTTP $code but the response is not JSON, possibly intercepted: ${body.take(120)}"
                    code == 403 -> "HTTP 403: ${classify403(conn, body, looksJson)}"
                    else -> "HTTP $code  ${body.take(200)}"
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /**
     * A 403 can come from more than one layer and the status code alone cannot
     * tell them apart. Check the response header first, then the body: a
     * managed challenge is domain-level bot protection unrelated to the
     * credential, JSON means our own ADMIN check rejected the password, and
     * anything else is the WAF.
     */
    private fun classify403(conn: HttpURLConnection, body: String, looksJson: Boolean): String {
        val mitigated = conn.getHeaderField("Cf-Mitigated").orEmpty()
        val isChallenge = mitigated.contains("challenge", ignoreCase = true) ||
            body.contains("Just a moment", ignoreCase = true) ||
            body.contains("challenges.cloudflare.com", ignoreCase = true)

        return when {
            isChallenge ->
                "Blocked by the Cloudflare managed challenge (Cf-Mitigated: challenge). " +
                    "The access field is fine; add a Skip rule for this path"
            looksJson -> "Wrong ADMIN password"
            else -> "Blocked by the WAF. Check that the access field matches the WAF rule"
        }
    }

    /**
     * Sends the WAF whitelist field and the ADMIN password. The password goes
     * out under both X-Admin-Password and AccessToken.
     */
    private fun applyAuth(conn: HttpURLConnection, password: String, field: String) {
        if (field.isNotEmpty()) {
            conn.setRequestProperty(field, "1")
        }
        if (password.isNotEmpty()) {
            conn.setRequestProperty("X-Admin-Password", password)
            conn.setRequestProperty("AccessToken", password)
        }
    }

    /** Appends the WAF whitelist field as a query parameter; omitted when empty. */
    private fun accessQuery(field: String): String =
        if (field.isEmpty()) "" else "?" + URLEncoder.encode(field, "UTF-8") + "=1"

    /** Reads inputStream for 2xx and errorStream otherwise; 3xx bodies land in errorStream. */
    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}
