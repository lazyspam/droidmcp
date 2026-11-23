package com.lazyexec.droidmcp

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object Prefs {
    const val DEFAULT_RELAY = "https://droid.lazyexec.dev"

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("droidmcp", Context.MODE_PRIVATE)

    /** How AI clients reach the phone: through the relay, the local server, or both. */
    const val MODE_RELAY = "relay"
    const val MODE_LOCAL = "local"
    const val MODE_BOTH = "both"

    /** Not set yet (new install or older version): relay, or local when there is no relay URL. */
    fun connectionMode(c: Context): String =
        p(c).getString("connection_mode", null)?.takeIf { it == MODE_RELAY || it == MODE_LOCAL || it == MODE_BOTH }
            ?: if (relayUrl(c).isEmpty()) MODE_LOCAL else MODE_RELAY
    fun setConnectionMode(c: Context, mode: String) = p(c).edit().putString("connection_mode", mode).apply()
    fun relayEnabled(c: Context) = connectionMode(c) != MODE_LOCAL

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
    /** Local server runs in "local" and "both" connection modes. */
    fun localEnabled(c: Context) = connectionMode(c) != MODE_RELAY
    fun localPort(c: Context) = p(c).getInt("local_port", 8765)
    fun allowShell(c: Context) = p(c).getBoolean("allow_shell", true)
    fun autostart(c: Context) = p(c).getBoolean("autostart", false)

    /**
     * The server was running in this boot when the app last saw it. Cleared when the service stops normally, but not
     * when the process is killed (pm replacing the app does that), so [UpdateReceiver] can bring the server back.
     */
    fun serverWasOn(c: Context) = p(c).getInt("server_on_boot", -1).let { it != -1 && it == bootCount(c) }
    fun setServerOn(c: Context, on: Boolean) = p(c).edit().putInt("server_on_boot", if (on) bootCount(c) else -1).apply()
    private fun bootCount(c: Context) =
        android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    /** Clients must send the bearer token. On by default; turning it off is the owner's choice and risk. */
    fun requireToken(c: Context) = p(c).getBoolean("require_token", true)
    fun setRequireToken(c: Context, on: Boolean) = p(c).edit().putBoolean("require_token", on).apply()

    /** Ask on the phone before a new AI client may run tools. */
    fun approveClients(c: Context) = p(c).getBoolean("approve_clients", false)
    fun setApproveClients(c: Context, on: Boolean) = p(c).edit().putBoolean("approve_clients", on).apply()

    /**
     * Older versions trusted "Always" clients by their self-reported clientInfo name, which any program holding the
     * token could claim. Those rules are dropped, so the owner is asked again (and "Always" then binds to a session).
     */
    fun dropNameTrust(c: Context) {
        if (p(c).contains("trusted_clients")) p(c).edit().remove("trusted_clients").apply()
    }
    /** User hid the server notification (root: snoozed indefinitely while the server keeps running). */
    fun notifHidden(c: Context) = p(c).getBoolean("notif_hidden", false)
    fun setNotifHidden(c: Context, on: Boolean) = p(c).edit().putBoolean("notif_hidden", on).apply()

    fun island(c: Context) = p(c).getBoolean("island", true)
    fun setIsland(c: Context, on: Boolean) = p(c).edit().putBoolean("island", on).apply()

    /** Island look and placement. position: left | center | right | custom (custom uses x/y). */
    data class IslandStyle(
        val position: String = "right",
        val x: Int = 0,
        val y: Int = -1,
        val locked: Boolean = true,
        val size: Int = 1,            // 0 small, 1 medium, 2 large
        val opacity: Float = 0.92f,
        val hideWhenIdle: Boolean = false,
    )

    fun islandStyle(c: Context) = p(c).let {
        IslandStyle(
            it.getString("island_pos", "right")!!, it.getInt("island_x", 0), it.getInt("island_y", -1),
            it.getBoolean("island_locked", true), it.getInt("island_size", 1),
            it.getFloat("island_opacity", 0.92f), it.getBoolean("island_hide_idle", false),
        )
    }

    fun setIslandStyle(c: Context, s: IslandStyle) = p(c).edit()
        .putString("island_pos", s.position).putInt("island_x", s.x).putInt("island_y", s.y)
        .putBoolean("island_locked", s.locked).putInt("island_size", s.size)
        .putFloat("island_opacity", s.opacity).putBoolean("island_hide_idle", s.hideWhenIdle)
        .apply()

    /** Distro used by linux_exec and new terminals when none is named. */
    fun defaultDistro(c: Context): Distro = Distro.of(p(c).getString("default_distro", "alpine")) ?: Distro.ALPINE
    fun setDefaultDistro(c: Context, d: Distro) = p(c).edit().putString("default_distro", d.id).apply()

    fun terminalLinux(c: Context) = p(c).getBoolean("term_linux", true)
    fun setTerminalLinux(c: Context, on: Boolean) = p(c).edit().putBoolean("term_linux", on).apply()

    fun save(
        c: Context, relayUrl: String, agentKey: String, deviceName: String,
        localEnabled: Boolean, localPort: Int, allowShell: Boolean, autostart: Boolean,
    ) = p(c).edit()
        .putString("relay_url", relayUrl).putString("agent_key", agentKey).putString("device_name", deviceName)
        .putBoolean("local_enabled", localEnabled).putInt("local_port", localPort)
        .putBoolean("allow_shell", allowShell).putBoolean("autostart", autostart)
        .apply()

    private const val MAX_SESSIONS = 64
    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("mcp_sessions", Context.MODE_PRIVATE)

    /** A saved MCP session: its client, when it was issued, and whether the owner tapped "Always" for it. */
    data class SavedSession(val sid: String, val client: McpClient, val time: Long, val trusted: Boolean)

    private fun savedSessions(c: Context): List<SavedSession> = sp(c).all.mapNotNull { (sid, v) ->
        runCatching { org.json.JSONObject(v as String) }.getOrNull()?.let {
            SavedSession(sid, McpClient(it.optString("n"), it.optString("v")), it.optLong("t"), it.optBoolean("a"))
        }
    }

    /** MCP sessions issued so far (session id -> client), so a request keeps its client across service restarts. */
    fun sessions(c: Context): Map<String, McpClient> = savedSessions(c).associate { it.sid to it.client }

    private fun entry(s: SavedSession) = org.json.JSONObject().put("n", s.client.name).put("v", s.client.version)
        .put("t", s.time).apply { if (s.trusted) put("a", true) }.toString()

    @Synchronized fun saveSession(c: Context, sid: String, client: McpClient) {
        val all = savedSessions(c)
        val e = sp(c).edit()
        if (all.size >= MAX_SESSIONS) {
            // Drop the oldest, so the list stays small; sessions the owner trusts go last.
            all.sortedWith(compareBy<SavedSession> { it.trusted }.thenBy { it.time })
                .take(all.size - MAX_SESSIONS + 1).forEach { e.remove(it.sid) }
        }
        e.putString(sid, entry(SavedSession(sid, client, System.currentTimeMillis(), false))).apply()
    }

    fun removeSession(c: Context, sid: String) = sp(c).edit().remove(sid).apply()

    /** Sessions the owner chose "Always" for, newest first. */
    fun trustedSessions(c: Context): List<SavedSession> {
        dropNameTrust(c)
        return savedSessions(c).filter { it.trusted }.sortedByDescending { it.time }
    }

    /** True only for a session id this server issued and the owner trusted ("Always"). */
    fun sessionTrusted(c: Context, sid: String): Boolean {
        dropNameTrust(c)
        val v = sp(c).getString(sid, null) ?: return false
        return runCatching { org.json.JSONObject(v).optBoolean("a") }.getOrDefault(false)
    }

    /** Marks a session as trusted (or not). [client] rebuilds the entry if it was dropped meanwhile. */
    @Synchronized fun setSessionTrusted(c: Context, sid: String, trusted: Boolean, client: McpClient? = null) {
        val cur = savedSessions(c).firstOrNull { it.sid == sid }
            ?: SavedSession(sid, client ?: return, System.currentTimeMillis(), false)
        sp(c).edit().putString(sid, entry(cur.copy(trusted = trusted))).apply()
    }

    /** The last client that connected, for requests without a (known) session id. */
    fun lastClient(c: Context) = McpClient(p(c).getString("last_client", "")!!, p(c).getString("last_client_version", "")!!)
    fun setLastClient(c: Context, client: McpClient) =
        p(c).edit().putString("last_client", client.name).putString("last_client_version", client.version).apply()

    fun publicUrl(c: Context): String? {
        val r = relayUrl(c)
        if (r.isEmpty() || !relayEnabled(c)) return null
        val http = r.replaceFirst(Regex("^wss://"), "https://").replaceFirst(Regex("^ws://"), "http://")
        return "$http/d/${deviceName(c)}/mcp"
    }
}

/** One AI action, for the activity feed. */
data class AiEvent(
    val time: Long, val tool: String, val text: String, val ok: Boolean?,
    val args: String = "", val result: String = "", val ms: Long = 0, val client: String = "",
)

/**
 * AI activity, kept for 30 days in filesDir/activity.jsonl (one JSON object per line, append-only).
 * Older entries are dropped when the app starts.
 */
object ActivityLog {
    private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private const val MAX_IN_MEMORY = 5_000
    private const val MAX_ARGS = 1_500
    private const val MAX_RESULT = 3_000
    /** Bumped on every change, so the UI copies the list only when something actually changed. */
    @Volatile var version = 0L
        private set
    private val events = ArrayDeque<AiEvent>()
    private var file: java.io.File? = null

    @Synchronized fun init(ctx: android.content.Context) {
        if (file != null) return
        val f = java.io.File(ctx.filesDir, "activity.jsonl")
        file = f
        if (!f.exists()) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val kept = ArrayList<AiEvent>()
        var dropped = false
        try {
            f.forEachLine { line ->
                try {
                    val o = org.json.JSONObject(line)
                    val e = AiEvent(o.getLong("t"), o.getString("tool"), o.optString("text"),
                        if (o.has("ok")) o.getBoolean("ok") else null,
                        o.optString("args"), o.optString("result"), o.optLong("ms"), o.optString("client"))
                    if (e.time >= cutoff) kept += e else dropped = true
                } catch (_: Exception) { dropped = true }
            }
        } catch (_: Exception) {}
        kept.sortByDescending { it.time }
        kept.take(MAX_IN_MEMORY).forEach { events.addLast(it) }
        // Rewrite without expired or broken lines.
        if (dropped || kept.size > MAX_IN_MEMORY) {
            try { f.writeText(events.reversed().joinToString("") { toLine(it) }) } catch (_: Exception) {}
        }
    }

    private fun toLine(e: AiEvent) = org.json.JSONObject().put("t", e.time).put("tool", e.tool).put("text", e.text)
        .put("args", e.args).put("result", e.result).put("ms", e.ms).put("client", e.client)
        .apply { e.ok?.let { put("ok", it) } }.toString() + "\n"

    @Synchronized fun event(full: AiEvent) {
        val e = full.copy(args = full.args.take(MAX_ARGS), result = full.result.take(MAX_RESULT))
        version++
        events.addFirst(e)
        while (events.size > MAX_IN_MEMORY) events.removeLast()
        try { file?.appendText(toLine(e)) } catch (_: Exception) {}
        listener?.invoke()
    }

    @Synchronized fun events(): List<AiEvent> = events.toList()

    @Synchronized fun clear() {
        version++
        events.clear()
        try { file?.delete() } catch (_: Exception) {}
        listener?.invoke()
    }

    private val lines = ArrayDeque<String>()
    /** Set and cleared by the visible Activity on the main thread, invoked from server and tool threads. */
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun add(s: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.addFirst("$ts  $s")
        while (lines.size > 100) lines.removeLast()
        listener?.invoke()
    }

    @Synchronized fun text(): String = lines.joinToString("\n")
}

/** A tool call as shown to the user: its description and an icon drawn before it (see [Island.icon]), if any. */
data class Doing(val text: String, @androidx.annotation.DrawableRes val icon: Int? = null)

object Status {
    @Volatile var running = false
    @Volatile var relay = "off"
    @Volatile var local = "off"
    @Volatile var root = "not checked"
    @Volatile var error: String? = null
    @Volatile var startedAt: Long = 0
    /** The tool call in progress right now (for the Home "Now" card), or null. */
    @Volatile var current: Doing? = null
}

/** An MCP client as it named itself in initialize (clientInfo), e.g. "claude-code" "2.1.3". */
data class McpClient(val name: String, val version: String = "") {
    /** For the activity log: "claude-code 2.1.3". */
    val label: String get() = listOf(name, version).filter { it.isNotBlank() }.joinToString(" ")
}
