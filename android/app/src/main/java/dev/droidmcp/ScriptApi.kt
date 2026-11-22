package dev.droidmcp

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lets scripts inside the Linux distros call every tool, so the AI can do many steps in one linux_exec
 * (loops, waiting for a button, filtering ui_tree) instead of one round trip per step.
 *
 * A loopback-only HTTP server with a random port and a token made fresh each time the service starts.
 * Both are written to /etc/droidmcp/api in each distro, which only the app (and so the distro) can read.
 * Inside Linux the `droid` command, `import droid` (Python) and `require("droid")` (Node) use it.
 *
 *   GET  /tools                 tool definitions (JSON array; ?format=text for a readable list)
 *   POST /call/<tool>           body: arguments object. Reply: the MCP tool result.
 *                               ?format=text replies "ok\n<text>" or "error\n<text>", for shell scripts.
 *
 * Calls skip the on-phone approval: only the AI's own linux_exec commands and the user's terminals run here.
 * They show up in the bubble and the activity log like any other call, from the client "script".
 */
object ScriptApi {
    private const val VERSION = "1"
    private const val CLIENT = "script"
    private const val MAX_BODY = 50L * 1024 * 1024

    @Volatile private var server: Server? = null
    @Volatile private var token = ""

    val running get() = server != null

    fun start(ctx: Context, mcp: Mcp) {
        stop()
        token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        server = try {
            Server(mcp).also { it.start(30_000, true) }
        } catch (e: Exception) {
            Log.e(Mcp.TAG, "script api", e)
            ActivityLog.add("✗ script api: ${e.message}")
            null
        }
        // Refresh the address in the distros that are already installed.
        for (d in Distro.entries) LinuxEnv(ctx, d).takeIf { it.installed }?.let { runCatching { install(ctx, it.rootfs) } }
    }

    fun stop() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
    }

    /** Writes the address file and the client helpers into a distro. Cheap; called before each Linux command. */
    fun install(ctx: Context, rootfs: File) {
        val s = server ?: return
        if (!File(rootfs, "etc").isDirectory) return
        writeIfChanged(File(rootfs, "etc/droidmcp/api"), "http://127.0.0.1:${s.listeningPort}\n$token\n", 0x180)
        val marker = File(rootfs, "etc/droidmcp/helpers")
        val stamp = "$VERSION ${BuildInfo.VERSION}"
        if (marker.isFile && marker.readText() == stamp) return
        fun asset(name: String) = ctx.assets.open("droid/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        writeIfChanged(File(rootfs, "usr/local/bin/droid"), asset("droid.sh"), 0x1ed)
        writeIfChanged(File(rootfs, "usr/local/lib/droidmcp/droid.py"), asset("droid.py"), 0x1a4)
        // At the filesystem root, so both require() and ESM import find it from any folder.
        writeIfChanged(File(rootfs, "node_modules/droid/index.js"), asset("droid.js"), 0x1a4)
        writeIfChanged(File(rootfs, "node_modules/droid/package.json"),
            """{"name":"droid","version":"${BuildInfo.VERSION}","main":"index.js"}""" + "\n", 0x1a4)
        marker.writeText(stamp)
    }

    private fun writeIfChanged(f: File, text: String, mode: Int) {
        if (f.isFile && runCatching { f.readText() }.getOrNull() == text) return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        runCatching { android.system.Os.chmod(tmp.path, mode) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private class Server(private val mcp: Mcp) : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = try {
            route(session)
        } catch (e: Throwable) {
            Log.e(Mcp.TAG, "script api request failed", e)
            reply(500, JSONObject().put("error", "Internal error: ${e.javaClass.simpleName}: ${e.message}").toString())
        }

        private fun route(session: IHTTPSession): Response {
            val given = session.headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim().orEmpty()
            if (!MessageDigest.isEqual(given.toByteArray(), token.toByteArray()) || token.isEmpty()) {
                return reply(401, """{"error":"unauthorized: read the token from /etc/droidmcp/api"}""")
            }
            val text = session.parameters["format"]?.firstOrNull() == "text"
            val path = session.uri.trimEnd('/')
            if (path == "/tools" && session.method == Method.GET) {
                val defs = mcp.toolDefinitions()
                if (!text) return reply(200, defs.toString())
                val sb = StringBuilder("ok\n")
                for (i in 0 until defs.length()) {
                    val d = defs.getJSONObject(i)
                    sb.append(d.getString("name")).append(argsLine(d.optJSONObject("inputSchema"))).append('\n')
                    sb.append("    ").append(d.optString("description")).append('\n')
                }
                return reply(200, sb.toString(), "text/plain; charset=utf-8")
            }
            val name = path.removePrefix("/call/").takeIf { path.startsWith("/call/") && session.method == Method.POST }
                ?: return reply(404, """{"error":"use GET /tools or POST /call/<tool>"}""")
            val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
            if (len > MAX_BODY) return reply(413, """{"error":"body too large"}""")
            val body = readBody(session, len.toInt()).trim()
            val args = try {
                if (body.isEmpty()) JSONObject() else JSONObject(body)
            } catch (e: Exception) {
                return fail(text, 400, "The arguments must be a JSON object: ${e.message}")
            }
            // A script usually runs inside the shared AI terminal, which is busy until it ends.
            if (name == "linux_exec" && !args.has("session")) args.put("session", false)
            val r = try {
                mcp.runTool(name, args, CLIENT)
            } catch (e: InvalidParams) {
                return fail(text, 400, e.message ?: "invalid arguments")
            }
            if (!text) return reply(200, r.toString())
            return reply(200, (if (r.optBoolean("isError")) "error\n" else "ok\n") + textOf(r), "text/plain; charset=utf-8")
        }

        private fun fail(text: Boolean, status: Int, msg: String): Response =
            // Text mode always answers 200 so busybox wget still hands the message to the script.
            if (text) reply(200, "error\n$msg", "text/plain; charset=utf-8")
            else reply(status, JSONObject().put("error", msg).toString())

        private fun textOf(r: JSONObject): String {
            val content = r.optJSONArray("content") ?: JSONArray()
            return (0 until content.length()).mapNotNull { content.optJSONObject(it) }.joinToString("\n") { c ->
                when (c.optString("type")) {
                    "text" -> c.optString("text")
                    "image" -> "[image, ${c.optString("data").length * 3 / 4 / 1024} KB: use `droid --json` or the Python/Node library to get it]"
                    else -> ""
                }
            }
        }

        private fun argsLine(schema: JSONObject?): String {
            val props = schema?.optJSONObject("properties") ?: return ""
            val req = schema.optJSONArray("required")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val parts = props.keys().asSequence().map { k ->
                val t = props.optJSONObject(k)?.optString("type").orEmpty()
                if (k in req) "$k=<$t>" else "[$k=<$t>]"
            }.toList()
            return if (parts.isEmpty()) "" else " " + parts.joinToString(" ")
        }

        private fun readBody(session: IHTTPSession, len: Int): String {
            if (len <= 0) return ""
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = session.inputStream.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }

        private fun reply(status: Int, body: String, type: String = "application/json"): Response {
            val bytes = body.toByteArray()
            return newFixedLengthResponse(Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR, type,
                ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }
}
