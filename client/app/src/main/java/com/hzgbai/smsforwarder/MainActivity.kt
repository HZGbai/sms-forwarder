package com.hzgbai.smsforwarder

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings screen. The UI is built in code rather than with XML layouts or
 * Compose, since it is small enough that this is less work.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var urlInput: EditText
    private lateinit var pwdInput: EditText
    private lateinit var deviceInput: EditText
    private lateinit var accessInput: EditText
    private lateinit var enabledBox: CheckBox
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        loadValues()
        requestSmsPermissions()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        fun addRow(v: View, topMargin: Int = 0) {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(topMargin)
            root.addView(v, lp)
        }

        fun caption(text: String) {
            addRow(
                TextView(this).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(Color.parseColor("#888888"))
                },
                topMargin = 12,
            )
        }

        fun note(text: String) {
            addRow(
                TextView(this).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(Color.parseColor("#AAAAAA"))
                },
                topMargin = 2,
            )
        }

        fun input(hint: String, password: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setSingleLine(true)
            e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (password) {
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addRow(e, topMargin = 2)
            return e
        }

        fun action(text: String, onClick: () -> Unit) {
            addRow(
                Button(this).apply {
                    this.text = text
                    isAllCaps = false
                    setOnClickListener { onClick() }
                },
                topMargin = 8,
            )
        }

        addRow(
            TextView(this).apply {
                text = "SMS Forwarder"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
            },
        )

        enabledBox = CheckBox(this).apply { text = "Enable forwarding" }
        addRow(enabledBox, topMargin = 12)

        caption("Server URL")
        urlInput = input("https://example.com")

        caption("ADMIN password (the ADMIN secret on the server)")
        pwdInput = input("Password", password = true)

        caption("Access field (WAF whitelist, access_ plus 32 characters)")
        note("The edge WAF rule requires it on every request, otherwise the request is blocked.")
        note("You may enter only the 32-character suffix; the prefix is added automatically. Leave empty for local debugging.")
        accessInput = input("access_...")

        caption("Device name (optional, defaults to the model)")
        deviceInput = input("Device name")

        action("Save settings") { save() }
        action("Test connection") { testConnection() }
        action("Request SMS permissions") { requestSmsPermissions() }
        action("Read local messages (preview)") { previewInbox() }
        action("Upload local messages (max 500)") { backfillInbox() }
        action("Upload queue now") {
            UploadWorker.enqueue(this)
            toast("Upload triggered")
            refresh()
        }
        action("Clear pending queue") {
            PendingQueue.clear(this)
            toast("Queue cleared")
            refresh()
        }
        action("Refresh status / log") { refresh() }
        action("Clear log") {
            LogStore.clear(this)
            refresh()
        }

        caption("Status")
        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
        }
        addRow(statusView, topMargin = 4)

        caption("Log (newest first)")
        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(Typeface.MONOSPACE)
            setTextIsSelectable(true)
        }
        addRow(logView, topMargin = 4)

        val scroll = ScrollView(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // targetSdk 35+ enforces edge-to-edge on Android 15, so the content needs insets
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        return scroll
    }

    // -------------------------------------------------------- Configuration

    private fun loadValues() {
        enabledBox.isChecked = prefs.enabled
        urlInput.setText(prefs.serverUrl)
        pwdInput.setText(prefs.password)
        accessInput.setText(prefs.accessField)
        deviceInput.setText(prefs.deviceName)
    }

    private fun save() {
        prefs.enabled = enabledBox.isChecked
        prefs.serverUrl = urlInput.text.toString()
        prefs.password = pwdInput.text.toString()
        prefs.accessField = accessInput.text.toString()
        prefs.deviceName = deviceInput.text.toString()
        // Write the normalized value back so the user sees what actually takes effect
        accessInput.setText(prefs.accessField)
        LogStore.add(this, "Settings saved")
        toast("Saved")
        refresh()
    }

    // --------------------------------------------------------------- Actions

    private fun testConnection() {
        save()
        toast("Testing...")
        Thread {
            val result = ApiClient.probe(this, prefs)
            LogStore.add(this, "Connectivity test: $result")
            runOnUiThread { refresh() }
        }.start()
    }

    private fun previewInbox() {
        if (!hasSmsPermission()) {
            toast("SMS permission missing")
            return
        }
        Thread {
            val items = SmsInbox.read(this, limit = 20)
            val head = items.take(5).joinToString("\n") {
                "[${dateFmt.format(Date(it.date))}] ${it.address}\n  ${it.body.take(60)}"
            }
            LogStore.add(this, "Read ${items.size} messages from the local inbox (showing the latest 5)\n$head")
            runOnUiThread { refresh() }
        }.start()
    }

    private fun backfillInbox() {
        if (!hasSmsPermission()) {
            toast("SMS permission missing")
            return
        }
        Thread {
            val items = SmsInbox.read(this, limit = 500)
            if (items.isEmpty()) {
                LogStore.add(this, "No local messages to upload")
            } else {
                val n = SmsInbox.enqueueAll(this, items)
                LogStore.add(this, "Queued $n local messages")
                UploadWorker.enqueue(this)
            }
            runOnUiThread { refresh() }
        }.start()
    }

    // ------------------------------------------------------------ Status UI

    private fun refresh() {
        val readOk = hasPermission(Manifest.permission.READ_SMS)
        val receiveOk = hasPermission(Manifest.permission.RECEIVE_SMS)
        statusView.text = buildString {
            append("Forwarding: ").append(if (prefs.enabled) "on" else "off").append('\n')
            append("Server: ").append(prefs.serverUrl.ifBlank { "(not configured)" }).append('\n')
            append("Auth: ADMIN password ").append(if (prefs.password.isNotBlank()) "set" else "missing")
            append('\n')
            append("Access field: ").append(prefs.accessField.ifBlank { "(unset, fine for local debugging)" }).append('\n')
            append("Permissions: READ_SMS=").append(if (readOk) "yes" else "no")
            append(", RECEIVE_SMS=").append(if (receiveOk) "yes" else "no").append('\n')
            append("Pending queue: ").append(PendingQueue.size(this@MainActivity)).append(" messages")
        }
        logView.text = LogStore.read(this)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasSmsPermission(): Boolean =
        hasPermission(Manifest.permission.READ_SMS) && hasPermission(Manifest.permission.RECEIVE_SMS)

    private fun requestSmsPermissions() {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.READ_SMS)) missing.add(Manifest.permission.READ_SMS)
        if (!hasPermission(Manifest.permission.RECEIVE_SMS)) missing.add(Manifest.permission.RECEIVE_SMS)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_SMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_SMS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            LogStore.add(this, if (granted) "SMS permission granted" else "SMS permission denied; forwarding cannot work")
            refresh()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_SMS = 1001
    }
}
