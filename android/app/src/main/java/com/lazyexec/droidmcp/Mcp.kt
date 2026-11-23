package com.lazyexec.droidmcp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class HttpReply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/**
 * Minimal MCP server over Streamable HTTP (JSON responses only, no SSE stream).
 * Both the local HTTP server and the relay tunnel feed requests through [handle].
 */
class Mcp(private val ctx: Context, private val tools: Tools) {

    private val supportedVersions = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    /**
     * Session id -> the client that opened it (its initialize clientInfo). Saved (see [Prefs.saveSession]), because
     * clients keep their session id when the service restarts or the app updates and don't initialize again.
     */
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, McpClient>(Prefs.sessions(ctx))
    private val reqSession = ThreadLocal<String?>()
    private val newSession = ThreadLocal<String?>()

    /**
     * The 404/401 reply a request gets before its body matters, or null if it may go on. Lets the local server
     * turn away requests without a valid token before it reads (and buffers) their body.
     */
    fun reject(path: String, headers: Map<String, String>): HttpReply? {
        if (!path.substringBefore('?').trimEnd('/').endsWith("/mcp")) return HttpReply(404, """{"error":"not found, use /mcp"}""")
        val auth = headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }?.value
        if (Prefs.requireToken(ctx) && !authorized(auth)) {
            return HttpReply(401, """{"error":"unauthorized"}""", mapOf("WWW-Authenticate" to "Bearer"))
        }
        return null
    }

    fun handle(method: String, path: String, headers: Map<String, String>, body: String): HttpReply {
        val h = headers.mapKeys { it.key.lowercase() }
        reject(path, headers)?.let { return it }
        reqSession.set(h["mcp-session-id"]); newSession.set(null)
        return when (method.uppercase()) {
            "POST" -> post(body).let { r ->
                newSession.get()?.let { sid -> HttpReply(r.status, r.body, r.headers + ("Mcp-Session-Id" to sid)) } ?: r
            }
            "DELETE" -> { h["mcp-session-id"]?.let { sessions.remove(it); Prefs.removeSession(ctx, it) }; HttpReply(200, "{}") }
            // No server-initiated stream; spec allows 405 for GET.
            else -> HttpReply(405, """{"error":"method not allowed"}""", mapOf("Allow" to "POST, DELETE"))
        }
    }

    private fun authorized(header: String?): Boolean {
        val token = Prefs.token(ctx)
        val given = header?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun post(body: String): HttpReply {
        if (body.isBlank()) return json(error(null, -32700, "Parse error: empty request body (expected a JSON-RPC message)."))
        val parsed = try {
            if (body.trimStart().startsWith("[")) JSONArray(body) else JSONObject(body)
        } catch (e: Exception) {
            return json(error(null, -32700, "Parse error: ${e.message}"))
        }
        if (parsed is JSONArray) {
            if (parsed.length() == 0) return json(error(null, -32600, "Invalid Request: empty batch."))
            val out = JSONArray()
            for (i in 0 until parsed.length()) {
                val item = parsed.opt(i)
                if (item !is JSONObject) out.put(error(null, -32600, "Invalid Request: batch item $i is not an object."))
                else dispatch(item)?.let { out.put(it) }
            }
            return if (out.length() == 0) HttpReply(202, "") else json(out.toString())
        }
        val res = dispatch(parsed as JSONObject) ?: return HttpReply(202, "")
        return json(res.toString())
    }

    private fun json(s: Any) = HttpReply(200, s.toString(), mapOf("Content-Type" to "application/json"))

    /** Returns null for notifications and client responses. */
    private fun dispatch(msg: JSONObject): JSONObject? {
        val method = msg.opt("method") as? String
        val hasId = msg.has("id") && !msg.isNull("id")
        if (method == null) {
            // A response from the client (we never send requests) or garbage.
            return if (hasId && (msg.has("result") || msg.has("error"))) null
            else error(if (hasId) msg.get("id") else null, -32600, "Invalid Request: missing 'method'.")
        }
        if (!hasId) return null // notification (initialized, cancelled, ...)
        val id = msg.get("id")
        if (id !is String && id !is Number) return error(null, -32600, "Invalid Request: 'id' must be a string or number.")
        val params = when (val p = msg.opt("params")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> p
            else -> return error(id, -32602, "Invalid params: 'params' must be an object.")
        }
        return try {
            when (method) {
                "initialize" -> {
                    val asked = params.optString("protocolVersion")
                    val ci = params.optJSONObject("clientInfo")
                    val client = McpClient(ci?.optString("name")?.trim().orEmpty(), ci?.optString("version")?.trim().orEmpty())
                    if (client.name.isNotEmpty()) Prefs.setLastClient(ctx, client)
                    val sid = java.util.UUID.randomUUID().toString()
                    sessions[sid] = client
                    Prefs.saveSession(ctx, sid, client)
                    newSession.set(sid)
                    result(id, JSONObject()
                        .put("protocolVersion", if (asked in supportedVersions) asked else supportedVersions.first())
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "droid-mcp").put("version", BuildInfo.VERSION))
                        .put("instructions", instructions(ctx)))
                }
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", tools.definitions()))
                "tools/call" -> {
                    val name = params.opt("name") as? String
                        ?: return error(id, -32602, "Invalid params: tools/call needs a string 'name'.")
                    // On-phone approval (if enabled): per session, or per client name for clients without sessions.
                    // This request's own client: with several connected, each action is credited to the one that sent it.
                    // No (or an unknown) session id: the client that connected last.
                    val sid = reqSession.get()
                    val who = sid?.let { sessions[it] } ?: Prefs.lastClient(ctx)
                    val client = who.name.ifBlank { "An AI client" }
                    Approvals.check(ctx, sid ?: "client:$client", client, who.label)?.let { reason ->
                        ActivityLog.event(AiEvent(System.currentTimeMillis(), name, "Blocked: not approved", false, "", reason, 0, who.label))
                        return result(id, JSONObject().put("isError", true)
                            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", reason))))
                    }
                    val args = when (val a = params.opt("arguments")) {
                        null, JSONObject.NULL -> JSONObject()
                        is JSONObject -> a
                        else -> return error(id, -32602, "Invalid params: 'arguments' must be an object.")
                    }
                    Log.i(TAG, "tool call: $name")
                    ActivityLog.add("$name ${args.toString().take(200)}")
                    Island.start(name, args, who.name)
                    Status.current = Doing(Island.describe(name, args), Island.icon(ctx, name, args))
                    ActivityLog.listener?.invoke()
                    var ok = false
                    var summary = ""
                    val started = System.currentTimeMillis()
                    try {
                        val r = tools.call(name, args)
                        ok = !r.optBoolean("isError")
                        summary = summarize(r)
                        result(id, r)
                    } catch (e: InvalidParams) {
                        summary = "Invalid arguments: ${e.message}"
                        throw e
                    } catch (e: Throwable) {
                        summary = "Internal error: ${e.message}"
                        throw e
                    } finally {
                        Island.finish(name, ok)
                        Status.current = null
                        ActivityLog.event(AiEvent(started, name, Island.describe(name, args), ok,
                            args.toString(2).take(4000), summary.take(8000), System.currentTimeMillis() - started, who.label))
                    }
                }
                "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
                "resources/templates/list" -> result(id, JSONObject().put("resourceTemplates", JSONArray()))
                "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
                "logging/setLevel" -> result(id, JSONObject())
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: InvalidParams) {
            ActivityLog.add("✗ invalid params: ${e.message?.take(200)}")
            error(id, -32602, e.message ?: "Invalid params")
        } catch (e: Throwable) {
            Log.e(TAG, "dispatch failed", e)
            ActivityLog.add("✗ internal error: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            error(id, -32603, "Internal error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Text of a tool result for the activity log; images are noted, not stored. */
    private fun summarize(r: JSONObject): String {
        val content = r.optJSONArray("content") ?: return ""
        val parts = ArrayList<String>()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> parts += c.optString("text")
                "image" -> parts += "[image, ${c.optString("data").length * 3 / 4 / 1024} KB]"
            }
        }
        return parts.joinToString("\n")
    }

    private fun result(id: Any, r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)
    private fun error(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))

    companion object {
        const val TAG = "DroidMCP"
        /** What the AI is told on connect, based on what this phone can actually do right now. */
        fun instructions(ctx: android.content.Context): String {
            val root = Shell.rootState ?: Shell.hasRoot()
            val loop = "Typical loop: screenshot or ui_tree to see the screen, then tap/swipe/type_text/key to act, then look " +
                "again. ui_tree gives exact tap coordinates. linux_exec runs commands in Alpine or Ubuntu inside the app " +
                "(no root needed), in a shared terminal the user can watch. shell and linux_exec keep their working directory " +
                "between calls: set cwd once instead of starting every command with `cd …`."
            if (root) {
                return "You control an Android phone with root access. $loop shell runs as root and can do anything else " +
                    "(pm, am, settings, svc, content, dumpsys, cmd)."
            }
            val missing = buildList {
                if (DroidAccessibility.instance == null) add("screen and input tools (the user hasn't enabled DroidMCP's accessibility service)")
                if (!NotifListener.enabled(ctx)) add("notifications (notification access not granted)")
            }
            return "You control an Android phone WITHOUT root. $loop Screen, input and keys go through Android's accessibility " +
                "service; only HOME, BACK, RECENTS, NOTIFICATION, POWER, LOCK and ENTER keys work. shell runs as the app, not root, " +
                "so system commands and other apps' data are off limits. read_file/write_file reach shared storage (/sdcard) only." +
                if (missing.isEmpty()) "" else " Not available until the user allows it: ${missing.joinToString("; ")}. " +
                    "If a tool says so, ask the user to finish setup in the DroidMCP app."
        }
    }
}

object BuildInfo { const val VERSION = "0.14.0" }
