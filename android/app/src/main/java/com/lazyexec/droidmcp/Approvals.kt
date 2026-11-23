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
 */
object Approvals {
    /** [client]: the clientInfo name (what "Always" trusts); [label]: name and version, for the prompt. */
    data class Pending(val key: String, val client: String, val label: String)

    private const val CHANNEL = "approvals"
    private const val TIMEOUT_MS = 120_000L
    private val main = Handler(Looper.getMainLooper())
    private val decisions = ConcurrentHashMap<String, Boolean>()
    private val waiters = ConcurrentHashMap<String, CountDownLatch>()
    private val clients = ConcurrentHashMap<String, String>()

    /** The request the in-app dialog should show, if any. */
    var pending by mutableStateOf<Pending?>(null)
        private set

    /** Blocks until the owner decides (or [TIMEOUT_MS]). Returns null when allowed, else a reason. */
    /** [label]: the client's raw "name version" ([client] plus version), shown in the prompt. */
    fun check(ctx: Context, key: String, client: String, label: String = client): String? {
        if (!Prefs.approveClients(ctx)) return null
        decisions[key]?.let { return if (it) null else denied(client) }
        if (client in Prefs.trustedClients(ctx)) { decisions[key] = true; return null }
        clients[key] = client
        val latch = waiters.computeIfAbsent(key) { CountDownLatch(1).also { ask(ctx, key, client, label) } }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return when (decisions[key]) {
            true -> null
            false -> denied(client)
            null -> { waiters.remove(key); clearUi(ctx, key)
                "Waiting for approval on the phone timed out. Ask the owner to tap Allow on their phone, then try again." }
        }
    }

    private fun denied(client: String) = "The phone's owner denied $client. Ask them to allow it in DroidMCP before trying again."

    fun decide(ctx: Context, key: String, allow: Boolean, always: Boolean = false) {
        decisions[key] = allow
        if (allow && always) clients[key]?.let { Prefs.addTrustedClient(ctx, it) }
        ActivityLog.add("${if (allow) "allowed" else "denied"} ${clients[key] ?: "client"}${if (always) " (always)" else ""}")
        clearUi(ctx, key)
        waiters.remove(key)?.countDown()
    }

    private fun clearUi(ctx: Context, key: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
        main.post { if (pending?.key == key) pending = null }
    }

    private fun ask(ctx: Context, key: String, client: String, label: String) {
        main.post { pending = Pending(key, client, label) }
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
            .setContentTitle("Allow ${ClientLogos.name(label)} to control this phone?")
            .setContentText("It wants to run tools (screen, input, shell…). Only allow AI clients you started.")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(action("Allow", true, false, 1))
            .addAction(action("Always allow", true, true, 2))
            .addAction(action("Deny", false, false, 3))
            .build()
        nm.notify(key.hashCode(), n)
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val key = i.getStringExtra("key") ?: return
        Approvals.decide(c, key, i.getBooleanExtra("allow", false), i.getBooleanExtra("always", false))
    }
}
