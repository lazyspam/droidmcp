package com.lazyexec.droidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-phone approval of AI clients. When enabled, a client's first tool call waits until the owner taps
 * Allow / Always allow / Deny (notification, or a dialog when the app is open).
 *
 * Approvals bind to the connection, never to the client's name: clientInfo is self-reported, so any program holding
 * the token could call itself "claude-code". "Allow" lasts for this connection until the service restarts; "Always"
 * trusts this MCP session (its server-issued, random Mcp-Session-Id) and is saved across restarts. A new session is
 * asked again, even with the same name, and requests without a session this server issued can't be trusted always.
 */
object Approvals {
    /**
     * [key]: the session id, or "client:<name>" for requests without one; [label]: name and version, for the prompt;
     * [session]: [key] is a session this server issued, so "Always" may trust it.
     */
    data class Pending(val key: String, val client: McpClient, val label: String, val session: Boolean)

    private const val CHANNEL = "approvals"
    private const val TIMEOUT_MS = 120_000L
    private val main = Handler(Looper.getMainLooper())
    private val decisions = ConcurrentHashMap<String, Boolean>()
    private val waiters = ConcurrentHashMap<String, CountDownLatch>()
    private val asked = ConcurrentHashMap<String, Pending>()

    /** The request the in-app dialog should show, if any. */
    var pending by mutableStateOf<Pending?>(null)
        private set

    /**
     * Blocks until the owner decides (or [TIMEOUT_MS]). Returns null when allowed, else a reason.
     * [session]: [key] is a session id this server issued (only those can be trusted "Always").
     */
    fun check(ctx: Context, key: String, client: McpClient, session: Boolean): String? {
        if (!Prefs.approveClients(ctx)) return null
        val name = client.name.ifBlank { "An AI client" }
        decisions[key]?.let { return if (it) null else denied(name) }
        if (session && Prefs.sessionTrusted(ctx, key)) { decisions[key] = true; return null }
        val latch = waiters.computeIfAbsent(key) {
            CountDownLatch(1).also { Pending(key, client, client.label.ifBlank { name }, session).let { p -> asked[key] = p; ask(ctx, p) } }
        }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return when (decisions[key]) {
            true -> null
            false -> denied(name)
            null -> { waiters.remove(key); asked.remove(key); clearUi(ctx, key)
                "Waiting for approval on the phone timed out. Ask the owner to tap Allow on their phone, then try again." }
        }
    }

    /** Revokes a trusted session: its next tool call is asked again. */
    fun revoke(ctx: Context, sid: String) {
        Prefs.setSessionTrusted(ctx, sid, false)
        decisions.remove(sid)
    }

    private fun denied(client: String) = "The phone's owner denied $client. Ask them to allow it in DroidMCP before trying again."

    fun decide(ctx: Context, key: String, allow: Boolean, always: Boolean = false) {
        val p = asked.remove(key)
        // "Always" only ever trusts a session this server issued, never a name.
        val keep = allow && always && p?.session == true
        decisions[key] = allow
        if (keep) Prefs.setSessionTrusted(ctx, key, true, p!!.client)
        ActivityLog.add("${if (allow) "allowed" else "denied"} ${p?.label ?: "client"}${if (keep) " (always, this connection)" else ""}")
        clearUi(ctx, key)
        waiters.remove(key)?.countDown()
    }

    private fun clearUi(ctx: Context, key: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
        main.post { if (pending?.key == key) pending = null }
    }

    private fun ask(ctx: Context, p: Pending) {
        val key = p.key
        main.post { pending = p }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI client approvals", NotificationManager.IMPORTANCE_HIGH))
        fun action(label: String, allow: Boolean, always: Boolean, code: Int): Notification.Action {
            val i = Intent(ctx, ApprovalReceiver::class.java).setPackage(ctx.packageName)
                .putExtra("key", key).putExtra("allow", allow).putExtra("always", always)
            val pi = PendingIntent.getBroadcast(ctx, key.hashCode() * 4 + code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, label, pi).build()
        }
        val open = PendingIntent.getActivity(ctx, key.hashCode(), Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_droid)
            .setContentTitle("Allow ${ClientLogos.name(p.label)} to control this phone?")
            .setContentText("For this connection only. Only allow AI clients you started.")
            .setStyle(Notification.BigTextStyle().bigText(promptText(p)))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(action("Allow", true, false, 1))
            .apply { if (p.session) addAction(action("Always allow", true, true, 2)) }
            .addAction(action("Deny", false, false, 3))
            .build()
        nm.notify(key.hashCode(), n)
    }
}

/** What the prompt (dialog and notification) says the answer applies to. */
fun promptText(p: Approvals.Pending): String {
    val name = ClientLogos.name(p.label)
    return "$name wants to control this phone: screen, input, apps and shell. Only allow AI clients you started yourself.\n\n" +
        if (p.session) "This applies to this connection only. Allow: until DroidMCP restarts. Always: this connection, " +
            "also after restarts. A new connection is asked again, even from the same app."
        else "This client connected without a session, so it can only be allowed until DroidMCP restarts."
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        Approvals.decide(c, key, i.getBooleanExtra("allow", false), i.getBooleanExtra("always", false))
    }
}
