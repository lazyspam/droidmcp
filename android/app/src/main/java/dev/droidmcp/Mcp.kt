package dev.droidmcp

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

    fun handle(method: String, path: String, headers: Map<String, String>, body: String): HttpReply {
        val h = headers.mapKeys { it.key.lowercase() }
        if (!path.substringBefore('?').trimEnd('/').endsWith("/mcp")) return HttpReply(404, """{"error":"not found, use /mcp"}""")
        if (!authorized(h["authorization"])) {
            return HttpReply(401, """{"error":"unauthorized"}""", mapOf("WWW-Authenticate" to "Bearer"))
        }
        return when (method.uppercase()) {
            "POST" -> post(body)
            "DELETE" -> HttpReply(200, "{}")
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
                    params.optJSONObject("clientInfo")?.let { ci ->
                        Status.client = listOf(ci.optString("name"), ci.optString("version")).filter { it.isNotBlank() }.joinToString(" ")
                    }
                    result(id, JSONObject()
                        .put("protocolVersion", if (asked in supportedVersions) asked else supportedVersions.first())
                        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                        .put("serverInfo", JSONObject().put("name", "droid-mcp").put("version", BuildInfo.VERSION))
                        .put("instructions", INSTRUCTIONS))
                }
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", tools.definitions()))
                "tools/call" -> {
                    val name = params.opt("name") as? String
                        ?: return error(id, -32602, "Invalid params: tools/call needs a string 'name'.")
                    val args = when (val a = params.opt("arguments")) {
                        null, JSONObject.NULL -> JSONObject()
                        is JSONObject -> a
                        else -> return error(id, -32602, "Invalid params: 'arguments' must be an object.")
                    }
                    Log.i(TAG, "tool call: $name")
                    ActivityLog.add("$name ${args.toString().take(200)}")
                    Island.start(name, args)
                    Status.current = Island.describe(name, args)
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
                            args.toString(2).take(4000), summary.take(8000), System.currentTimeMillis() - started, Status.client))
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
        const val INSTRUCTIONS =
            "You control a rooted Android phone. Typical loop: screenshot or ui_tree to see the screen, " +
                "then tap/swipe/type_text/key to act, then look again. ui_tree gives exact tap coordinates. " +
                "shell runs as root and can do anything else (pm, am, settings, svc, content, dumpsys, cmd). " +
                "linux_exec runs commands in a full Alpine Linux (apk packages: python, git, node, gcc, …) without needing root."
    }
}

object BuildInfo { const val VERSION = "0.12.1" }
