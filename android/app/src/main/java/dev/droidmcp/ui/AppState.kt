package dev.droidmcp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.droidmcp.ActivityLog
import dev.droidmcp.AiEvent
import dev.droidmcp.App
import dev.droidmcp.BuildInfo
import dev.droidmcp.DroidAccessibility
import dev.droidmcp.NotifListener
import dev.droidmcp.Prefs
import dev.droidmcp.Shell
import dev.droidmcp.Status

/** Snapshot of everything the screens show. Refreshed from the service's static state. */
data class UiSnapshot(
    val running: Boolean = false,
    val relay: String = "off",
    val local: String = "off",
    val root: Boolean = false,
    val rootText: String = "not checked",
    val error: String? = null,
    val publicUrl: String? = null,
    val token: String = "",
    val deviceId: String = "",
    val events: List<AiEvent> = emptyList(),
    val a11y: Boolean = false,
    val notif: Boolean = false,
    val sms: Boolean = false,
    val files: Boolean = false,
    val overlay: Boolean = false,
    val crash: String? = null,
    val startedAt: Long = 0,
    val now: String? = null,
    val model: String = "",
    val minute: Long = 0,
) {
    val connected get() = running && relay == "connected"
    val connecting get() = running && !connected && publicUrl != null
}

class AppState(private val ctx: Context) {
    var ui by mutableStateOf(UiSnapshot())
        private set

    private var eventsVersion = -1L
    private var cachedEvents: List<AiEvent> = emptyList()

    fun refresh() {
        val crashFile = App.crashFile(ctx)
        if (ActivityLog.version != eventsVersion) { eventsVersion = ActivityLog.version; cachedEvents = ActivityLog.events() }
        ui = UiSnapshot(
            running = Status.running,
            relay = Status.relay,
            local = Status.local,
            root = Shell.rootState == true,
            rootText = if (Shell.rootState == true) "granted" else Status.root,
            error = Status.error,
            publicUrl = Prefs.publicUrl(ctx),
            token = Prefs.token(ctx),
            deviceId = Prefs.deviceName(ctx),
            events = cachedEvents,
            a11y = DroidAccessibility.instance != null,
            notif = NotifListener.enabled(ctx),
            sms = ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            files = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            overlay = Settings.canDrawOverlays(ctx),
            crash = if (crashFile.exists()) runCatching { crashFile.readText().take(3000) }.getOrNull() else null,
            startedAt = Status.startedAt,
            now = Status.current,
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            minute = System.currentTimeMillis() / 60_000,
        )
    }

    fun clientJson(): String {
        val url = ui.publicUrl ?: "http://PHONE_IP:${Prefs.localPort(ctx)}/mcp"
        if (!Prefs.requireToken(ctx)) return """{"mcpServers":{"android":{"type":"http","url":"$url"}}}"""
        return """{"mcpServers":{"android":{"type":"http","url":"$url","headers":{"Authorization":"Bearer ${ui.token}"}}}}"""
    }

    fun claudeCommand(): String {
        val url = ui.publicUrl ?: "http://PHONE_IP:${Prefs.localPort(ctx)}/mcp"
        if (!Prefs.requireToken(ctx)) return "claude mcp add --transport http android $url"
        return "claude mcp add --transport http android $url --header \"Authorization: Bearer ${ui.token}\""
    }

    fun copy(label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }

    val version = BuildInfo.VERSION
}
