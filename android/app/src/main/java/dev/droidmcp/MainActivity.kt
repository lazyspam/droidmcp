package dev.droidmcp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var relayUrl: EditText
    private lateinit var agentKey: EditText
    private lateinit var deviceName: EditText
    private lateinit var localPort: EditText
    private lateinit var localEnabled: CheckBox
    private lateinit var allowShell: CheckBox
    private lateinit var autostart: CheckBox
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var log: TextView
    private lateinit var toggle: Button
    private lateinit var crashView: TextView
    private lateinit var perms: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun label(s: String) = TextView(this).apply { text = s; setPadding(0, pad / 2, 0, 0) }.also { col.addView(it) }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; isSingleLine = true
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        fun check(s: String, v: Boolean) = CheckBox(this).apply { text = s; isChecked = v }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }
            .also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        col.addView(TextView(this).apply { text = "DroidMCP"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        status = TextView(this).also { col.addView(it) }
        crashView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 10f; setTextColor(0xFFE57373.toInt())
            setOnClickListener { App.crashFile(this@MainActivity).delete(); refresh() }
        }.also { col.addView(it) }
        toggle = button("Start") { onToggle() }

        label("Relay URL (default: the public relay; or your own)")
        relayUrl = field(Prefs.DEFAULT_RELAY, Prefs.relayUrl(this), InputType.TYPE_TEXT_VARIATION_URI)
        label("Agent key (only for private relays; leave empty for the public one)")
        agentKey = field("optional", Prefs.agentKey(this), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        label("Device ID (part of your URL; random by default)")
        deviceName = field("p-xxxxxxxxxxxxxx", Prefs.deviceName(this))
        localEnabled = check("Local HTTP server (LAN / Tailscale / adb forward)", Prefs.localEnabled(this))
        localPort = field("8765", Prefs.localPort(this).toString(), InputType.TYPE_CLASS_NUMBER)
        allowShell = check("Allow the root shell tool", Prefs.allowShell(this))
        autostart = check("Start on boot", Prefs.autostart(this))
        CheckBox(this).apply {
            text = "Show activity island (floating pill showing what the AI does)"
            isChecked = Prefs.island(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setIsland(this@MainActivity, on)
                if (on && Status.running) Island.attach(this@MainActivity) else Island.detach()
            }
        }.also { col.addView(it) }
        button("Save") { if (save()) toast(if (Status.running) "Saved. Stop and start the server to apply." else "Saved.") }

        info = TextView(this).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE; setPadding(0, pad, 0, 0) }
        col.addView(info)
        button("Copy public URL") { copy(Prefs.publicUrl(this) ?: "") }
        button("Copy token") { copy(Prefs.token(this)) }
        button("Copy MCP client config (JSON)") { copy(clientConfig()) }
        button("New token (disconnects clients)") { Prefs.newToken(this); refresh() }
        button("New device ID (changes your URL)") {
            deviceName.setText(Prefs.newDeviceId(this))
            toast(if (Status.running) "New ID saved. Stop and start the server to apply." else "New device ID saved.")
            refresh()
        }
        button("Check root") {
            Thread {
                val msg = try {
                    val r = Shell.run("id -u", timeoutSec = 20)
                    if (r.out.trim() == "0") "Root OK" else "su ran but not as root: ${r.out.trim()} ${r.stderr.trim()}"
                } catch (e: Exception) {
                    e.message ?: "No root"
                }
                Status.root = if (msg == "Root OK") "granted" else msg
                runOnUiThread { toast(msg); refresh() }
            }.start()
        }

        label("Without root: permissions")
        perms = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        col.addView(perms)
        button("Accessibility (screen, taps, typing)") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Find DroidMCP and turn it on. Greyed out? App info → ⋮ → Allow restricted settings.")
        }
        button("Notification access") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        button("Grant SMS permission") {
            requestPermissions(arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS), 2)
        }
        button("Allow all files access") {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            } else {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 3)
            }
        }
        button("Display over other apps (for the island)") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")))
        }

        label("Activity")
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }

    override fun onResume() {
        super.onResume()
        ActivityLog.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        ActivityLog.listener = null
        super.onPause()
    }

    private fun onToggle() {
        if (Status.running) {
            McpService.stop(this)
        } else {
            if (!save()) return
            McpService.start(this)?.let { toast(it) }
        }
        toggle.postDelayed({ refresh() }, 600)
    }

    /** Validates the form, marks bad fields, and saves only if everything is valid. */
    private fun save(): Boolean {
        var ok = true
        val url = relayUrl.text.toString().trim()
        relayUrl.error = null; agentKey.error = null; deviceName.error = null; localPort.error = null
        if (url.isNotEmpty() && !url.matches(Regex("^(https?|wss?)://[^\\s/]+.*", RegexOption.IGNORE_CASE))) {
            relayUrl.error = "Must start with https:// (or http:// for testing)"; ok = false
        }
        val name = deviceName.text.toString().trim()
        if (!name.matches(Regex("[A-Za-z0-9_-]{3,64}"))) {
            deviceName.error = "3-64 letters, digits, - or _"; ok = false
        }
        val port = localPort.text.toString().toIntOrNull()
        if (localEnabled.isChecked && (port == null || port !in 1024..65535)) {
            localPort.error = "Port must be 1024-65535"; ok = false
        }
        if (url.isEmpty() && !localEnabled.isChecked) {
            toast("Enable the local server or set a relay URL, or nothing can reach the phone."); ok = false
        }
        if (!ok) { toast("Fix the highlighted fields."); return false }
        doSave(url, name, port ?: 8765)
        return true
    }

    private fun doSave(url: String, name: String, port: Int) = Prefs.save(
        this, url, agentKey.text.toString().trim(), name,
        localEnabled.isChecked, port, allowShell.isChecked, autostart.isChecked,
    )

    private fun refresh() {
        toggle.text = if (Status.running) "Stop server" else "Start server"
        status.text = buildString {
            append("Server: ${if (Status.running) "running" else "stopped"}\n")
            // Any successful root call since start (including tool calls) proves root, even if the startup check ran before the grant.
            append("Root: ${if (Shell.rootState == true) "granted" else Status.root}\n")
            append("Relay: ${Status.relay}\n")
            append("Local: ${Status.local}")
            Status.error?.let { append("\n⚠ $it") }
        }
        val crash = App.crashFile(this)
        crashView.visibility = if (crash.exists()) android.view.View.VISIBLE else android.view.View.GONE
        if (crash.exists()) crashView.text = "Last crash (tap to dismiss):\n" + runCatching { crash.readText().take(3000) }.getOrDefault("")
        val ip = lanIp()
        info.text = buildString {
            append("Public URL:\n  ${Prefs.publicUrl(this@MainActivity) ?: "(set a relay URL)"}\n")
            if (Prefs.localEnabled(this@MainActivity)) append("Local URL:\n  http://${ip ?: "<phone-ip>"}:${Prefs.localPort(this@MainActivity)}/mcp\n")
            append("Token (send as 'Authorization: Bearer <token>'):\n  ${Prefs.token(this@MainActivity)}")
        }
        log.text = ActivityLog.text()
        fun mark(b: Boolean) = if (b) "✓" else "✗"
        val sms = checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val files = if (Build.VERSION.SDK_INT >= 30) android.os.Environment.isExternalStorageManager()
            else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        perms.text = buildString {
            append("${mark(Shell.rootState == true)} root (covers everything below)\n")
            append("${mark(DroidAccessibility.instance != null)} accessibility\n")
            append("${mark(NotifListener.enabled(this@MainActivity))} notification access\n")
            append("${mark(sms)} SMS\n")
            append("${mark(files)} all files access\n")
            append("${mark(android.provider.Settings.canDrawOverlays(this@MainActivity))} display over other apps")
        }
    }

    private fun clientConfig(): String {
        val url = Prefs.publicUrl(this) ?: "http://${lanIp() ?: "PHONE_IP"}:${Prefs.localPort(this)}/mcp"
        return """{"mcpServers":{"android":{"type":"http","url":"$url","headers":{"Authorization":"Bearer ${Prefs.token(this)}"}}}}"""
    }

    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address }?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("droidmcp", s))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

}
