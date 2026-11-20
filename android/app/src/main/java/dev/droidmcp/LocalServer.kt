package dev.droidmcp

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Direct HTTP endpoint on the phone (LAN, Tailscale, adb forward, or any third-party tunnel). */
class LocalServer(port: Int, private val mcp: Mcp) : NanoHTTPD("0.0.0.0", port) {

    override fun serve(session: IHTTPSession): Response = try {
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (len > MAX_BODY) {
            reply(HttpReply(413, err("Request body too large (max ${MAX_BODY / 1024 / 1024} MB).")))
        } else {
            val body = readBody(session, len.toInt())
            reply(mcp.handle(session.method.name, session.uri, session.headers, body))
        }
    } catch (e: Throwable) {
        Log.e(Mcp.TAG, "local request failed", e)
        reply(HttpReply(500, err("Internal error: ${e.javaClass.simpleName}: ${e.message}")))
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
