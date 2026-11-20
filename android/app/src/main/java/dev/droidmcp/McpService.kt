package dev.droidmcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class McpService : Service() {
    private var local: LocalServer? = null
    private var relay: RelayClient? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.e(Mcp.TAG, "cannot start foreground service", e)
            ActivityLog.add("✗ could not start: ${e.message}. Open the app and press Start.")
            Status.error = "Android blocked starting in the background. Open the app and press Start."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Status.running) {
            try {
                startServers()
            } catch (e: Exception) {
                Log.e(Mcp.TAG, "startServers failed", e)
                Status.error = "Start failed: ${e.javaClass.simpleName}: ${e.message}"
                ActivityLog.add("✗ ${Status.error}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startServers() {
        Status.running = true
        Status.error = null
        val mcp = Mcp(this, Tools(this))

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droidmcp:server").apply { acquire() }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "droidmcp:wifi").apply { acquire() }

        // With root, exempt ourselves from Doze/battery optimisation so the tunnel stays up with the screen off.
        Thread {
            try {
                Shell.run("dumpsys deviceidle whitelist +$packageName; " +
                    "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow; " +
                    "pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null")
                Status.root = "granted"
            } catch (e: RootUnavailable) {
                Status.root = "NOT available: ${e.message}"
                ActivityLog.add("✗ ${e.message} Most tools need root.")
            } catch (e: Exception) {
                Status.root = "check failed: ${e.message}"
            }
            ActivityLog.listener?.invoke()
        }.start()

        if (Prefs.localEnabled(this)) {
            try {
                local = LocalServer(Prefs.localPort(this), mcp).also { it.start(30_000, false) }
                Status.local = "listening on :${Prefs.localPort(this)}"
            } catch (e: java.net.BindException) {
                Status.local = "port ${Prefs.localPort(this)} is already in use. Pick another port"
                ActivityLog.add("✗ local server: ${Status.local}")
            } catch (e: Exception) {
                Status.local = "failed: ${e.message}"
                ActivityLog.add("✗ local server: ${e.message}")
                Log.e(Mcp.TAG, "local server", e)
            }
        }

        val url = Prefs.relayUrl(this)
        if (url.isNotEmpty()) {
            relay = RelayClient(this, url, Prefs.agentKey(this), Prefs.deviceName(this), Prefs.deviceSecret(this), mcp) { s ->
                Status.relay = s
                ActivityLog.listener?.invoke()
            }.also { r -> r.start()?.let { ActivityLog.add("✗ relay: $it") } }
        } else {
            Status.relay = "not configured"
        }
        ActivityLog.add("service started")
    }

    override fun onDestroy() {
        try { relay?.stop() } catch (e: Exception) { Log.w(Mcp.TAG, "relay stop", e) }
        relay = null
        try { local?.stop() } catch (e: Exception) { Log.w(Mcp.TAG, "local stop", e) }
        local = null
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        Status.running = false
        Status.relay = "off"
        Status.local = "off"
        ActivityLog.add("service stopped")
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "MCP server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Remote control is ON")
            .setContentText("MCP clients with your token can control this phone.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        const val CHANNEL = "server"
        const val ACTION_STOP = "dev.droidmcp.STOP"

        /** Returns an error message, or null on success. */
        fun start(c: Context): String? = try {
            c.startForegroundService(Intent(c, McpService::class.java)); null
        } catch (e: Exception) {
            Log.e(Mcp.TAG, "start service", e)
            "Android refused to start the service: ${e.message}"
        }

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, McpService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                c.stopService(Intent(c, McpService::class.java))
            }
        }
    }
}

class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.autostart(context)) {
            McpService.start(context)?.let { ActivityLog.add("✗ autostart: $it") }
        }
    }
}
