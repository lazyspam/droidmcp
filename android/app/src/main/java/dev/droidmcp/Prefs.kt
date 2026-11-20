package dev.droidmcp

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object Prefs {
    const val DEFAULT_RELAY = "https://droid.xdtunnel.icu"

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("droidmcp", Context.MODE_PRIVATE)

    fun token(c: Context): String {
        p(c).getString("token", null)?.let { return it }
        return newToken(c)
    }

    fun newToken(c: Context): String {
        val t = randomHex(24)
        p(c).edit().putString("token", t).apply()
        return t
    }

    fun relayUrl(c: Context) = p(c).getString("relay_url", DEFAULT_RELAY)!!.trim().trimEnd('/')
    /** Only needed for private relays (those with AGENT_KEY set). */
    fun agentKey(c: Context) = p(c).getString("agent_key", "")!!.trim()

    /** Random per-install device ID: part of the public URL. */
    fun deviceName(c: Context): String {
        p(c).getString("device_name", null)?.takeIf { it.length >= 3 }?.let { return it }
        return newDeviceId(c)
    }

    /** Proves to the relay that this phone owns its device ID. Never leaves the phone except to the relay. */
    fun deviceSecret(c: Context): String {
        p(c).getString("device_secret", null)?.let { return it }
        return randomHex(32).also { p(c).edit().putString("device_secret", it).apply() }
    }

    fun newDeviceId(c: Context): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val id = "p-" + (1..14).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        p(c).edit().putString("device_name", id).putString("device_secret", randomHex(32)).apply()
        return id
    }

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
    fun localEnabled(c: Context) = p(c).getBoolean("local_enabled", true)
    fun localPort(c: Context) = p(c).getInt("local_port", 8765)
    fun allowShell(c: Context) = p(c).getBoolean("allow_shell", true)
    fun autostart(c: Context) = p(c).getBoolean("autostart", false)
    fun island(c: Context) = p(c).getBoolean("island", true)
    fun setIsland(c: Context, on: Boolean) = p(c).edit().putBoolean("island", on).apply()

    fun save(
        c: Context, relayUrl: String, agentKey: String, deviceName: String,
        localEnabled: Boolean, localPort: Int, allowShell: Boolean, autostart: Boolean,
    ) = p(c).edit()
        .putString("relay_url", relayUrl).putString("agent_key", agentKey).putString("device_name", deviceName)
        .putBoolean("local_enabled", localEnabled).putInt("local_port", localPort)
        .putBoolean("allow_shell", allowShell).putBoolean("autostart", autostart)
        .apply()

    fun publicUrl(c: Context): String? {
        val r = relayUrl(c)
        if (r.isEmpty()) return null
        val http = r.replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
        return "$http/d/${deviceName(c)}/mcp"
    }
}

/** In-memory recent activity shown in the app. */
object ActivityLog {
    private val lines = ArrayDeque<String>()
    var listener: (() -> Unit)? = null

    @Synchronized fun add(s: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$ts  $s")
        while (lines.size > 100) lines.removeLast()
        listener?.invoke()
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}

object Status {
    @Volatile var running = false
    @Volatile var relay = "off"
    @Volatile var local = "off"
    @Volatile var root = "not checked"
    @Volatile var error: String? = null
}
