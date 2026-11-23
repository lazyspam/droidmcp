package com.lazyexec.droidmcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Keeps an outbound WebSocket to the relay server. The relay turns public HTTPS requests to
 * /d/<device>/mcp into frames on this socket, and our replies back into HTTP responses.
 */
class RelayClient(
    private val ctx: Context,
    private val relayUrl: String,
    private val agentKey: String,
    private val device: String,
    private val deviceSecret: String,
    private val mcp: Mcp,
    private val onStatus: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val workers = Executors.newFixedThreadPool(4)
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var connected = false
    private var backoffSec = 1L
    private var pendingRetry: ScheduledFuture<*>? = null
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // The phone switched network (Wi-Fi ↔ mobile) or came back online: reconnect now, not after the backoff.
        override fun onAvailable(network: Network) {
            if (!connected && !stopped) {
                backoffSec = 1
                reconnectNow()
            }
        }
    }

    /** Returns an error message if the configuration can't work, else null. */
    fun start(): String? {
        val err = validate()
        if (err != null) {
            onStatus("not started: $err")
            return err
        }
        try {
            ctx.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        } catch (e: Exception) {
            Log.w(Mcp.TAG, "network callback unavailable", e)
        }
        connect()
        return null
    }

    fun stop() {
        stopped = true
        try { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        ws?.close(1000, "bye")
        scheduler.shutdownNow()
        workers.shutdownNow()
        client.dispatcher.executorService.shutdown()
    }

    private fun wsUrl() = relayUrl.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
        .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")

    private fun validate(): String? {
        if (!relayUrl.matches(Regex("^(https?|wss?)://.+", RegexOption.IGNORE_CASE))) {
            return "relay URL must start with https:// (got '$relayUrl')"
        }
        val http = wsUrl().replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
        if ("$http/agent".toHttpUrlOrNull() == null) return "relay URL '$relayUrl' is not a valid URL"
        if (!device.matches(Regex("[A-Za-z0-9_-]{3,64}"))) return "device ID must be 3-64 letters, digits, - or _"
        return null
    }

    @Synchronized private fun reconnectNow() {
        pendingRetry?.cancel(false)
        try { scheduler.execute { connect() } } catch (_: Exception) {}
    }

    @Synchronized private fun connect() {
        if (stopped || connected) return
        ws?.cancel()
        val req = try {
            Request.Builder()
                .url("${wsUrl()}/agent?device=$device")
                .header("Authorization", "Bearer $deviceSecret")
                .apply { if (agentKey.isNotEmpty()) header("X-Agent-Key", agentKey) }
                .build()
        } catch (e: IllegalArgumentException) {
            onStatus("invalid relay URL: ${e.message}")
            return
        }
        onStatus("connecting…")
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                connected = true
                backoffSec = 1
                onStatus("connected")
                ActivityLog.add("relay connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    workers.execute { handle(webSocket, text) }
                } catch (_: Exception) {
                    // Shutting down.
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                connected = false
                when (code) {
                    4000 -> retry("another connection with this device ID replaced this one", minDelay = 30)
                    else -> retry("connection closed ($code${if (reason.isNotEmpty()) " $reason" else ""})")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                val wasConnected = connected
                connected = false
                val code = response?.code
                response?.close()
                when {
                    code == 401 -> retry(if (agentKey.isEmpty()) "this relay is private: enter its agent key (HTTP 401)"
                        else "relay rejected the agent key (HTTP 401). Check it matches AGENT_KEY on the relay", minDelay = 60)
                    code == 403 -> retry("device ID '$device' belongs to another phone. Tap 'New device ID' (HTTP 403)", minDelay = 60)
                    code == 400 -> retry("relay rejected the device ID '$device' (HTTP 400)", minDelay = 60)
                    code == 404 -> retry("relay URL is wrong: no /agent endpoint there (HTTP 404)", minDelay = 60)
                    code != null -> retry("relay answered HTTP $code instead of upgrading to WebSocket (proxy not passing WebSockets?)", minDelay = 30)
                    t is UnknownHostException -> retry("can't resolve relay host (no internet, or wrong URL)")
                    t is ConnectException -> retry("can't connect to the relay (server down or port blocked)")
                    t is SocketTimeoutException -> retry(if (wasConnected) "connection to relay timed out" else "relay did not respond in time")
                    t is SSLException -> retry("TLS error talking to the relay: ${t.message}", minDelay = 30)
                    else -> retry(if (wasConnected) "connection lost: ${t.message}" else "error: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        })
    }

    @Synchronized private fun retry(why: String, minDelay: Long = 0) {
        if (stopped) return
        val delay = maxOf(backoffSec, minDelay)
        onStatus("$why. Retrying in ${delay}s")
        ActivityLog.add("relay: $why")
        Log.w(Mcp.TAG, "relay: $why")
        try {
            pendingRetry?.cancel(false)
            pendingRetry = scheduler.schedule({ connect() }, delay, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        backoffSec = (delay * 2).coerceAtMost(60)
    }

    /** Always answers a frame that carries an id, so the relay never waits for its timeout. */
    private fun handle(socket: WebSocket, frame: String) {
        var id: String? = null
        val reply = try {
            val msg = JSONObject(frame)
            if (msg.optString("type") == "ping") return
            id = msg.optString("id").ifEmpty { null } ?: return
            val headers = HashMap<String, String>()
            msg.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.optString(it) } }
            mcp.handle(msg.optString("method", "POST"), msg.optString("path", "/mcp"), headers, msg.optString("body"))
        } catch (e: Throwable) {
            Log.e(Mcp.TAG, "relay frame failed", e)
            if (id == null) id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(frame)?.groupValues?.get(1)
            if (id == null) return
            HttpReply(500, LocalServer.err("Phone failed to handle the request: ${e.javaClass.simpleName}: ${e.message}"))
        }
        val out = try {
            JSONObject().put("id", id).put("status", reply.status)
                .put("headers", JSONObject(reply.headers as Map<*, *>)).put("body", reply.body).toString()
        } catch (e: OutOfMemoryError) {
            JSONObject().put("id", id).put("status", 500)
                .put("body", LocalServer.err("Reply too large for the phone's memory.")).toString()
        }
        if (!socket.send(out)) {
            Log.w(Mcp.TAG, "relay socket closed before reply $id could be sent")
        }
    }
}
