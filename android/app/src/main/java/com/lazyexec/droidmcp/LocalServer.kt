package com.lazyexec.droidmcp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Direct HTTP endpoint on the phone (LAN, Tailscale, adb forward, or any third-party tunnel). */
class LocalServer(port: Int, private val mcp: Mcp) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response = try {
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val rejected = mcp.reject(session.uri, session.headers)
        when {
            // Answered without reading the body: close the connection so the unread body isn't parsed as the next request.
            rejected != null -> reply(rejected).apply { closeConnection(true) }
            len > MAX_BODY -> reply(HttpReply(413, err("Request body too large (max ${MAX_BODY / 1024 / 1024} MB).")))
            else -> {
                val body = readBody(session, len.toInt())
                reply(mcp.handle(session.method.name, session.uri, session.headers, body))
            }
        }
    } catch (e: Throwable) {
        Log.e(Mcp.TAG, "local request failed", e)
        reply(HttpReply(500, err("Internal error: ${e.javaClass.simpleName}: ${e.message}")))
    }

    /** Reads up to [len] bytes. Grows with what actually arrives, so a large Content-Length alone reserves no memory. */
    private fun readBody(session: IHTTPSession, len: Int): String {
        if (len <= 0) return ""
        val out = ByteArrayOutputStream(minOf(len, 64 * 1024))
        val buf = ByteArray(64 * 1024)
        var left = len
        while (left > 0) {
            val n = session.inputStream.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun reply(r: HttpReply): Response {
        val bytes = r.body.toByteArray()
        val resp = newFixedLengthResponse(
            Response.Status.lookup(r.status) ?: Response.Status.INTERNAL_ERROR,
            r.headers["Content-Type"] ?: "application/json",
            ByteArrayInputStream(bytes), bytes.size.toLong(),
        )
        r.headers.filterKeys { it != "Content-Type" }.forEach { (k, v) -> resp.addHeader(k, v) }
        return resp
    }

    companion object {
        const val MAX_BODY = 50L * 1024 * 1024
        fun err(msg: String): String = JSONObject().put("error", msg).toString()
    }
}
