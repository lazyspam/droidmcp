package dev.droidmcp

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.view.WindowManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Base64
import android.util.Log
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A failure the client should see as a readable tool error (isError=true), not a crash. */
class ToolError(msg: String) : Exception(msg)

/** Unknown tool or malformed arguments: reported as a JSON-RPC error, per the MCP spec. */
class InvalidParams(msg: String) : Exception(msg)

/** Typed, validated access to tool arguments with clear error messages. */
class Args(private val o: JSONObject) {
    private fun missing(k: String, type: String) = InvalidParams("Missing required argument '$k' ($type).")
    private fun wrong(k: String, type: String) = InvalidParams("Argument '$k' must be $type, got: ${o.opt(k)}")

    fun str(k: String): String {
        if (!o.has(k) || o.isNull(k)) throw missing(k, "string")
        return o.opt(k) as? String ?: o.opt(k).toString()
    }

    fun str(k: String, def: String): String = if (!o.has(k) || o.isNull(k)) def else str(k)

    fun int(k: String): Int {
        if (!o.has(k) || o.isNull(k)) throw missing(k, "integer")
        return when (val v = o.opt(k)) {
            is Number -> if (v.toDouble() % 1.0 == 0.0) v.toInt() else v.toDouble().let { Math.round(it).toInt() }
            is String -> v.trim().toDoubleOrNull()?.let { Math.round(it).toInt() } ?: throw wrong(k, "an integer")
            else -> throw wrong(k, "an integer")
        }
    }

    fun int(k: String, def: Int, range: IntRange? = null): Int {
        val v = if (!o.has(k) || o.isNull(k)) def else int(k)
        if (range != null && v !in range) throw InvalidParams("Argument '$k' must be between ${range.first} and ${range.last}, got $v.")
        return v
    }

    fun bool(k: String, def: Boolean): Boolean {
        if (!o.has(k) || o.isNull(k)) return def
        return when (val v = o.opt(k)) {
            is Boolean -> v
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw wrong(k, "a boolean")
            }
            is Number -> v.toInt() != 0
            else -> throw wrong(k, "a boolean")
        }
    }
}

class Tools(private val ctx: Context) {

    private class Tool(
        val name: String,
        val description: String,
        val schema: JSONObject,
        val run: (Args) -> JSONObject,
    )

    private val uiLock = Any()
    private fun linuxFor(a: Args): LinuxEnv {
        val name = a.str("distro", "")
        val d = if (name.isBlank()) Prefs.defaultDistro(ctx)
            else Distro.of(name) ?: throw InvalidParams("Unknown distro '$name'. Use: ${Distro.entries.joinToString { it.id }}.")
        return LinuxEnv(ctx, d)
    }
    @Volatile private var screenSize: Pair<Int, Int>? = null

    private val tools = listOf(
        Tool("device_info", "Model, Android version, battery, screen size, IP addresses, storage, uptime, root status.",
            schema()) { deviceInfo() },
        Tool("shell",
            "Run a shell command on the phone (as root when the phone is rooted, otherwise as the app). Use it for anything not covered by another tool, e.g. " +
                "`svc wifi enable`, `settings put system screen_brightness 100`, `pm install /sdcard/x.apk`, " +
                "`am start -a android.intent.action.CALL -d tel:123`, `content query --uri content://contacts/phones`, " +
                "`dumpsys battery`, `cmd location set-location-enabled true`, `reboot`.",
            schema(
                "command" to prop("string", "Shell command or script"),
                "root" to prop("boolean", "Run as root (default: true if the phone is rooted)"),
                "timeout_sec" to prop("integer", "Timeout in seconds (default 30, max 600)"),
                required = listOf("command"),
            )) { a ->
            val cmd = a.str("command")
            if (cmd.isBlank()) throw InvalidParams("Argument 'command' is empty.")
            val timeout = a.int("timeout_sec", 30, 1..600)
            val r = Shell.run(cmd, a.bool("root", root()), timeout.toLong())
            val sb = StringBuilder()
            sb.append("exit_code: ${r.exitCode}")
            if (r.timedOut) sb.append(" (killed after ${timeout}s timeout; raise timeout_sec or run it in the background with `nohup … &`)")
            sb.append('\n')
            if (r.out.isNotEmpty()) {
                sb.append("stdout:\n").append(r.out.take(100_000))
                if (r.out.length > 100_000 || r.truncated) sb.append("\n[stdout truncated; redirect to a file and use read_file]")
                sb.append('\n')
            }
            if (r.stderr.isNotEmpty()) sb.append("stderr:\n").append(r.stderr.take(20_000))
            if (r.out.isEmpty() && r.stderr.isEmpty()) sb.append("(no output)")
            text(sb.toString(), isError = r.exitCode != 0)
        },
        Tool("linux_exec",
            "Run a command in a full Linux environment on the phone (no root needed). distro: \"alpine\" (tiny; apk add …) or " +
                "\"ubuntu\" (26.04, glibc: use it for prebuilt Linux software like official Node/Bun builds, Python wheels, " +
                "VS Code server; apt install -y …). Defaults to the user's chosen distro. Home is /root; phone storage is at /sdcard. " +
                "A distro is downloaded automatically on first use (Alpine ~3 MB, Ubuntu ~30 MB).",
            schema(
                "command" to prop("string", "Shell command or script"),
                "distro" to prop("string", "alpine or ubuntu (default: the user's default)"),
                "session" to prop("boolean", "Run in the shared, visible AI terminal (default true): state like cd/env/venv " +
                    "persists between calls and the user can watch, answer prompts or Ctrl+C. false = a fresh separate shell " +
                    "(use for parallel or background work)."),
                "timeout_sec" to prop("integer", "Timeout in seconds (default 120, max 1800)"),
                required = listOf("command"),
            )) { a ->
            val cmd = a.str("command")
            if (cmd.isBlank()) throw InvalidParams("Argument 'command' is empty.")
            val env = linuxFor(a)
            val timeout = a.int("timeout_sec", 120, 1..1800)
            val setupNote = if (!env.installed) env.setup { ActivityLog.add("${env.distro.id}: $it") } + "\n" else ""
            var shared = a.bool("session", true)
            var note = ""
            val r = if (shared) {
                try {
                    dev.droidmcp.ui.AiTerminal.run(ctx, env.distro, cmd, timeout.toLong())
                } catch (e: ToolError) {
                    if (e.message?.contains("busy") == true) throw e
                    shared = false
                    note = "(shared terminal unavailable: ${e.message}; ran in a separate shell)\n"
                    env.run(cmd, timeout.toLong())
                }
            } else env.run(cmd, timeout.toLong())
            val sb = StringBuilder(setupNote).append(note)
            sb.append("[${env.distro.label}${if (shared) ", AI terminal" else ""}] exit_code: ${r.exitCode}")
            if (r.timedOut) sb.append(" (killed after ${timeout}s timeout; raise timeout_sec)")
            sb.append('\n')
            if (r.out.isNotEmpty()) {
                sb.append("stdout:\n").append(r.out.take(100_000))
                if (r.out.length > 100_000 || r.truncated) sb.append("\n[stdout truncated; redirect to a file]")
                sb.append('\n')
            }
            if (r.stderr.isNotEmpty()) sb.append("stderr:\n").append(r.stderr.take(20_000))
            if (r.out.isEmpty() && r.stderr.isEmpty()) sb.append("(no output)")
            text(sb.toString(), isError = r.exitCode != 0)
        },
        Tool("terminal_read",
            "Read what's on a terminal screen in the app: the shared AI terminal (default) or the session the user has open " +
                "(which=current). Use it when the user says they ran something themselves, or to see a prompt waiting for input.",
            schema(
                "which" to prop("string", "ai (default) or current"),
                "lines" to prop("integer", "How many last lines (default 60, max 500)"),
            )) { a ->
            text(dev.droidmcp.ui.AiTerminal.read(a.str("which", "ai"), a.int("lines", 60, 1..500)))
        },
        Tool("terminal_input",
            "Type into the shared AI terminal, e.g. answer a prompt (\"y\\n\"), or send ctrl_c. Use after terminal_read shows a waiting prompt.",
            schema(
                "text" to prop("string", "Text to type; \\n presses Enter"),
                "ctrl_c" to prop("boolean", "Send Ctrl+C instead"),
            )) { a ->
            dev.droidmcp.ui.AiTerminal.type(if (a.bool("ctrl_c", false)) "\u0003" else a.str("text").replace("\n", "\r"))
            text("Sent.")
        },
        Tool("linux_setup", "Install, reinstall or list the Linux distros used by linux_exec (alpine, ubuntu).",
            schema(
                "distro" to prop("string", "alpine or ubuntu (default: the user's default)"),
                "reinstall" to prop("boolean", "Wipe and reinstall, deleting installed packages and files in /root (default false)"),
                "list" to prop("boolean", "Only list distros and whether they are installed"),
            )) { a ->
            if (a.bool("list", false)) {
                val def = Prefs.defaultDistro(ctx)
                text(Distro.entries.joinToString("\n") { d ->
                    val e = LinuxEnv(ctx, d)
                    "${d.id}: ${if (e.installed) "installed ${e.version}" else "not installed (${d.sizeHint} download)"}" +
                        if (d == def) " [default]" else ""
                })
            } else {
                val env = linuxFor(a)
                text(env.setup(force = a.bool("reinstall", false)) { ActivityLog.add("${env.distro.id}: $it") })
            }
        },
        Tool("screenshot",
            "Capture the screen as an image. The reply states the real screen size; tap/swipe coordinates are always real screen pixels.",
            schema("max_width" to prop("integer", "Downscale the image to this width (default 720, 100-4000)"))) { a ->
            screenshot(a.int("max_width", 720, 100..4000))
        },
        Tool("ui_tree",
            "List the on-screen UI elements (text, content description, resource id, clickable state) with their tap center coordinates. " +
                "Often better than a screenshot for finding what to tap.",
            schema("all" to prop("boolean", "Include layout containers with no text/id (default false)"))) { a ->
            text(uiTree(a.bool("all", false)))
        },
        Tool("tap", "Tap at screen coordinates.", schema(
            "x" to prop("integer", "X in screen pixels"), "y" to prop("integer", "Y in screen pixels"),
            required = listOf("x", "y"))) { a ->
            val (x, y) = point(a, "x", "y")
            if (root()) input("input tap $x $y", "Tapped ($x,$y).")
            else { gesture("Tapping", 60, x to y); text("Tapped ($x,$y).") }
        },
        Tool("long_press", "Long-press at screen coordinates.", schema(
            "x" to prop("integer", "X"), "y" to prop("integer", "Y"),
            "duration_ms" to prop("integer", "Default 800"), required = listOf("x", "y"))) { a ->
            val (x, y) = point(a, "x", "y")
            val ms = a.int("duration_ms", 800, 100..10_000)
            if (root()) input("input swipe $x $y $x $y $ms", "Long-pressed ($x,$y).")
            else { gesture("Long-pressing", ms.toLong(), x to y); text("Long-pressed ($x,$y).") }
        },
        Tool("swipe", "Swipe/drag from (x1,y1) to (x2,y2). To scroll down, swipe upward.", schema(
            "x1" to prop("integer", ""), "y1" to prop("integer", ""), "x2" to prop("integer", ""), "y2" to prop("integer", ""),
            "duration_ms" to prop("integer", "Default 300"), required = listOf("x1", "y1", "x2", "y2"))) { a ->
            val (x1, y1) = point(a, "x1", "y1")
            val (x2, y2) = point(a, "x2", "y2")
            val ms = a.int("duration_ms", 300, 10..10_000)
            if (root()) input("input swipe $x1 $y1 $x2 $y2 $ms", "Swiped ($x1,$y1) → ($x2,$y2).")
            else { gesture("Swiping", ms.toLong(), x1 to y1, x2 to y2); text("Swiped ($x1,$y1) → ($x2,$y2).") }
        },
        Tool("type_text", "Type text into the focused field. Newlines press Enter. Non-ASCII text (emoji, other scripts) is pasted via the clipboard.",
            schema("text" to prop("string", "Text to type"), required = listOf("text"))) { a ->
            val t = a.str("text")
            if (root()) typeText(t)
            else {
                if (t.isEmpty()) throw InvalidParams("Argument 'text' is empty.")
                DroidAccessibility.require("Typing").type(t)
                text("Typed ${t.length} characters.")
            }
        },
        Tool("key",
            "Press a key: HOME, BACK, ENTER, RECENTS, POWER, WAKEUP, SLEEP, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, DEL, TAB, " +
                "MENU, SEARCH, CAMERA, MEDIA_PLAY_PAUSE, NOTIFICATION, or any KEYCODE_* name or number.",
            schema("key" to prop("string", "Key name or code"), required = listOf("key"))) { a ->
            val code = keyCode(a.str("key"))
            if (root()) input("input keyevent $code", "Pressed $code.") else globalKey(code)
        },
        Tool("launch_app", "Launch an app by package name (see list_apps).",
            schema("package" to prop("string", "e.g. com.android.chrome"), required = listOf("package"))) { a ->
            launchApp(a.str("package").trim())
        },
        Tool("list_apps", "List installed apps: package name and label.", schema(
            "filter" to prop("string", "Substring of the package name or label"),
            "include_system" to prop("boolean", "Include system apps (default false)"))) { a ->
            val pm = ctx.packageManager
            val sys = a.bool("include_system", false)
            val f = a.str("filter", "").lowercase()
            val list = pm.getInstalledApplications(0)
                .filter { sys || it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 ||
                    it.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
                .map { it.packageName to (try { pm.getApplicationLabel(it).toString() } catch (_: Exception) { "" }) }
                .filter { (p, l) -> f.isEmpty() || p.lowercase().contains(f) || l.lowercase().contains(f) }
                .sortedBy { it.first }
                .map { (p, l) -> if (l.isNotEmpty() && l != p) "$p  ($l)" else p }
            text(if (list.isEmpty()) "No apps match '$f'." else list.joinToString("\n"))
        },
        Tool("open_url", "Open a URL or deep link (http, https, tel:, mailto:, geo:, market:, app links).",
            schema("url" to prop("string", "URL"), required = listOf("url"))) { a ->
            val url = a.str("url").trim()
            if (!url.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+"))) throw InvalidParams("'$url' is not a URL (it needs a scheme like https: or tel:).")
            if (!root()) return@Tool startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opened $url", "No installed app can open '$url'.")
            val r = Shell.run("am start -W -a android.intent.action.VIEW -d ${Shell.q(url)}")
            val combined = r.out + r.stderr
            when {
                combined.contains("unable to resolve Intent") -> throw ToolError("No installed app can open '$url'.")
                r.exitCode != 0 || combined.contains("Error:") -> throw ToolError("Could not open '$url': ${combined.trim().take(1000)}")
                else -> text("Opened $url")
            }
        },
        Tool("notifications", "Read current notifications (package, title, text).", schema()) {
            if (root()) notifications()
            else text((NotifListener.instance ?: throw ToolError(
                "Reading notifications needs root or notification access. On the phone: DroidMCP → Notification access → allow it."
            )).read())
        },
        Tool("sms_list", "Read SMS messages.", schema(
            "box" to prop("string", "inbox (default), sent, or all"),
            "limit" to prop("integer", "Default 20, max 500"))) { a ->
            val box = when (val b = a.str("box", "inbox").lowercase()) {
                "inbox" -> "inbox"; "sent" -> "sent"; "all" -> ""
                else -> throw InvalidParams("Argument 'box' must be inbox, sent or all, got '$b'.")
            }
            val limit = a.int("limit", 20, 1..500)
            if (!root()) return@Tool smsQuery(box, limit)
            val r = Shell.run("content query --uri content://sms/$box --projection address:date:type:read:body " +
                "--sort \"date DESC LIMIT $limit\"")
            when {
                r.out.contains("No result found") || (r.exitCode == 0 && r.out.isBlank()) -> text("No messages.")
                r.exitCode != 0 || r.stderr.isNotBlank() && r.out.isBlank() ->
                    throw ToolError("Reading SMS failed: ${r.stderr.ifBlank { r.out }.take(1000)}")
                else -> text(r.out)
            }
        },
        Tool("send_sms", "Send an SMS and wait for the radio to confirm it was sent.", schema(
            "to" to prop("string", "Phone number"), "message" to prop("string", "Text"),
            required = listOf("to", "message"))) { a -> sendSms(a.str("to").trim(), a.str("message")) },
        Tool("read_file", "Read a file (any file with root; otherwise shared storage like /sdcard). Text is returned as-is, binary as base64.", schema(
            "path" to prop("string", "Absolute path"),
            "max_bytes" to prop("integer", "Default 1000000, max 20000000"), required = listOf("path"))) { a ->
            val path = absPath(a.str("path")); val max = a.int("max_bytes", 1_000_000, 1..20_000_000)
            if (root()) readFile(path, max) else readFilePlain(path, max)
        },
        Tool("write_file", "Write a file (any path with root; otherwise shared storage like /sdcard). Creates parent directories.", schema(
            "path" to prop("string", "Absolute path"),
            "content" to prop("string", "File content"),
            "base64" to prop("boolean", "Content is base64-encoded binary (default false)"),
            required = listOf("path", "content"))) { a ->
            val path = absPath(a.str("path"))
            if (root()) writeFile(path, a.str("content"), a.bool("base64", false))
            else writeFilePlain(path, a.str("content"), a.bool("base64", false))
        },
    ).associateBy { it.name }

    fun definitions(): JSONArray = JSONArray().apply {
        tools.values.forEach {
            put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.schema))
        }
    }

    /**
     * Runs a tool. Failures while running become isError results the model can read and react to.
     * Unknown tools and bad arguments throw [InvalidParams], which Mcp turns into a JSON-RPC error.
     */
    fun call(name: String, rawArgs: JSONObject): JSONObject {
        val tool = tools[name] ?: throw InvalidParams("Unknown tool '$name'. Available: ${tools.keys.joinToString()}.")
        if (name == "shell" && !Prefs.allowShell(ctx)) {
            return fail(name, "The shell tool is turned off in the DroidMCP app settings.")
        }
        return try {
            tool.run(Args(rawArgs))
        } catch (e: InvalidParams) {
            throw e
        } catch (e: ToolError) {
            fail(name, e.message ?: "failed")
        } catch (e: RootUnavailable) {
            fail(name, e.message ?: "root unavailable")
        } catch (e: OutOfMemoryError) {
            System.gc()
            fail(name, "Out of memory. Try a smaller request (e.g. lower max_width or max_bytes).")
        } catch (e: Exception) {
            Log.e(Mcp.TAG, "tool $name crashed", e)
            fail(name, "Unexpected error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun fail(tool: String, msg: String): JSONObject {
        ActivityLog.add("✗ $tool: ${msg.take(200)}")
        return text(msg, isError = true)
    }

    // --- implementations ---

    /** Runs an input/UI command; failure becomes a ToolError with the command's own output. */
    private fun input(cmd: String, ok: String): JSONObject {
        val r = Shell.run(cmd)
        val out = (r.out + r.stderr).trim()
        if (r.exitCode != 0 || out.contains("Exception") || out.startsWith("Error")) {
            throw ToolError("`$cmd` failed (exit ${r.exitCode}): ${out.ifEmpty { "no output" }.take(2000)}")
        }
        return text(ok)
    }

    private fun screen(): Pair<Int, Int>? {
        screenSize?.let { return it }
        return try {
            val wm = ctx.getSystemService(WindowManager::class.java)
            val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }.let { it.x to it.y }
            }
            Pair(minOf(w, h), maxOf(w, h)).also { screenSize = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun point(a: Args, kx: String, ky: String): Pair<Int, Int> {
        val x = a.int(kx); val y = a.int(ky)
        if (x < 0 || y < 0) throw InvalidParams("Coordinates must be non-negative, got ($x,$y).")
        screen()?.let { (w, h) ->
            // `wm size` is reported in portrait; allow either orientation.
            val maxX = maxOf(w, h); val maxY = maxOf(w, h)
            if (x >= maxX || y >= maxY) {
                throw InvalidParams("($x,$y) is outside the ${w}x$h screen. Coordinates are real screen pixels; " +
                    "if you measured them on a screenshot, multiply by the scale factor it reported.")
            }
        }
        return Pair(x, y)
    }

    private fun deviceInfo(): JSONObject {
        val head = "model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
            "android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}\n"
        val script = "echo \"screen: $(wm size | tail -1)\"; echo \"density: $(wm density | tail -1)\"; " +
            "dumpsys battery | grep -E 'level|status|plugged|temperature' ; " +
            "ip -4 -o addr show 2>/dev/null | awk '{print \"ip:\", $2, $4}'; df -h /data 2>/dev/null | tail -1; uptime"
        return try {
            text(head + "root: yes\n" + Shell.run(script).out)
        } catch (e: RootUnavailable) {
            text(head + "root: NO (${e.message})\n" + Shell.run(script, root = false).out)
        }
    }

    private fun screenshot(maxWidth: Int): JSONObject {
        if (!root()) {
            val svc = DroidAccessibility.require("Screenshots")
            val bmp = Island.hiddenDuring { svc.screenshot() }
            val w = bmp.width; val h = bmp.height
            screenSize = Pair(minOf(w, h), maxOf(w, h))
            return encodeShot(bmp, w, h, maxWidth)
        }
        val r = Island.hiddenDuring { Shell.run("screencap -p", timeoutSec = 20) }
        if (r.stdout.isEmpty()) throw ToolError("screencap returned nothing: ${r.stderr.ifBlank { "exit ${r.exitCode}" }.take(500)}")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(r.stdout, 0, r.stdout.size, bounds)
        if (bounds.outWidth <= 0) throw ToolError("screencap output is not an image: ${r.stderr.ifBlank { r.out.take(200) }}")
        val w = bounds.outWidth; val h = bounds.outHeight
        screenSize = Pair(minOf(w, h), maxOf(w, h))
        // Decode at reduced resolution first to keep memory low on high-res screens.
        var sample = 1
        while (w / (sample * 2) >= maxWidth) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(r.stdout, 0, r.stdout.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ToolError("Could not decode the screenshot.")
        return encodeShot(bmp, w, h, maxWidth)
    }

    /** Scales [bmp] (of a [w]x[h] screen) to [maxWidth], JPEG-encodes it, and recycles it. */
    private fun encodeShot(bmp: Bitmap, w: Int, h: Int, maxWidth: Int): JSONObject {
        val scaled = if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val sw = scaled.width; val sh = scaled.height
        return JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", data))
            .put(JSONObject().put("type", "text").put("text",
                "Screen is ${w}x$h px. Image is ${sw}x$sh; multiply image coordinates by " +
                    "%.3f to get screen coordinates for tap/swipe. ".format(w.toDouble() / sw) +
                    "A black image usually means the app blocks screenshots (FLAG_SECURE).")))
            .put("isError", false)
    }

    private fun uiTree(all: Boolean): String {
        if (!root()) return DroidAccessibility.require("Reading the screen").tree(all)
        return uiTreeRoot(all)
    }

    private fun uiTreeRoot(all: Boolean): String = synchronized(uiLock) {
        val path = "/data/local/tmp/droidmcp_ui.xml"
        var xml = ""
        var lastErr = ""
        for (attempt in 0..2) {
            val r = Shell.run("uiautomator dump $path 2>&1; cat $path 2>/dev/null; rm -f $path", timeoutSec = 30)
            val start = r.out.indexOf("<?xml").takeIf { it >= 0 } ?: r.out.indexOf("<hierarchy")
            if (start >= 0) { xml = r.out.substring(start); break }
            lastErr = r.out.lines().firstOrNull { it.isNotBlank() && !it.startsWith("UI hierchary") }.orEmpty()
            Thread.sleep(600)
        }
        if (xml.isEmpty()) {
            throw ToolError("uiautomator dump failed${if (lastErr.isNotBlank()) " ($lastErr)" else ""}. " +
                "The screen may be animating, playing video, or protected (lock screen, banking apps). Use screenshot instead.")
        }
        val sb = StringBuilder()
        try {
            val p = Xml.newPullParser().apply { setInput(StringReader(xml)) }
            var depth = 0
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.END_TAG && p.name == "node") depth--
                if (p.eventType != XmlPullParser.START_TAG || p.name != "node") continue
                depth++
                val t = p.getAttributeValue(null, "text").orEmpty()
                val d = p.getAttributeValue(null, "content-desc").orEmpty()
                val id = p.getAttributeValue(null, "resource-id").orEmpty().substringAfter(":id/")
                val cls = p.getAttributeValue(null, "class").orEmpty().substringAfterLast('.')
                val pkg = p.getAttributeValue(null, "package").orEmpty()
                val flags = listOf("clickable", "long-clickable", "scrollable", "checked", "focused", "selected")
                    .filter { p.getAttributeValue(null, it) == "true" }
                val editable = cls.contains("EditText")
                if (!all && t.isEmpty() && d.isEmpty() && id.isEmpty() && flags.isEmpty() && !editable) continue
                val m = Regex("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]").find(p.getAttributeValue(null, "bounds").orEmpty())
                val center = m?.destructured?.let { (x1, y1, x2, y2) ->
                    "(${(x1.toInt() + x2.toInt()) / 2},${(y1.toInt() + y2.toInt()) / 2})"
                } ?: "?"
                if (sb.isEmpty()) sb.append("package: $pkg\n")
                sb.append("  ".repeat((depth - 1).coerceIn(0, 12))).append(cls)
                if (t.isNotEmpty()) sb.append(" text=\"").append(t.take(200)).append('"')
                if (d.isNotEmpty()) sb.append(" desc=\"").append(d.take(200)).append('"')
                if (id.isNotEmpty()) sb.append(" id=").append(id)
                if (flags.isNotEmpty()) sb.append(" [").append(flags.joinToString(",")).append(']')
                sb.append(" @").append(center).append('\n')
            }
        } catch (e: Exception) {
            if (sb.isEmpty()) throw ToolError("Could not parse the UI dump (${e.message}). Use screenshot instead.")
            sb.append("[UI dump was cut off: ${e.message}]\n")
        }
        sb.toString().ifEmpty { "No elements found (the screen may be blank or a game/video surface). Use screenshot." }
    }

    private fun typeText(text: String): JSONObject {
        if (text.isEmpty()) throw InvalidParams("Argument 'text' is empty.")
        val ascii = text.all { it == '\n' || it.code in 0x20..0x7e }
        if (!ascii) {
            pasteViaClipboard(text)
            return text("Pasted ${text.length} characters via the clipboard.")
        }
        val parts = text.split("\n")
        val script = StringBuilder()
        parts.forEachIndexed { i, part ->
            if (part.isNotEmpty()) script.append("input text ").append(Shell.q(part.replace(" ", "%s"))).append(" && ")
            if (i < parts.lastIndex) script.append("input keyevent KEYCODE_ENTER && ")
        }
        script.append("true")
        return input(script.toString(), "Typed ${text.length} characters.")
    }

    private fun pasteViaClipboard(text: String) {
        val latch = CountDownLatch(1)
        var err: Exception? = null
        Handler(Looper.getMainLooper()).post {
            try {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("droidmcp", text))
            } catch (e: Exception) {
                err = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw ToolError("Timed out setting the clipboard.")
        err?.let { throw ToolError("Could not set the clipboard for non-ASCII text: ${it.message}") }
        input("input keyevent KEYCODE_PASTE", "pasted")
    }

    private fun keyCode(key: String): String {
        val k = key.trim().uppercase().removePrefix("KEYCODE_")
        if (k.isEmpty()) throw InvalidParams("Argument 'key' is empty.")
        if (k.toIntOrNull() != null) return k
        val alias = mapOf("RECENTS" to "APP_SWITCH", "BACKSPACE" to "DEL", "DELETE" to "FORWARD_DEL",
            "RETURN" to "ENTER", "LOCK" to "SLEEP", "ESC" to "ESCAPE")
        val name = alias[k] ?: k
        if (!name.matches(Regex("[A-Z0-9_]+"))) throw InvalidParams("'$key' is not a valid key name.")
        return "KEYCODE_$name"
    }

    private fun launchApp(pkg: String) : JSONObject {
        if (!pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            throw InvalidParams("'$pkg' is not a valid package name (expected something like com.android.chrome).")
        }
        if (!root()) {
            try { ctx.packageManager.getPackageInfo(pkg, 0) } catch (_: Exception) {
                throw ToolError("Package '$pkg' is not installed. Use list_apps to find the right name.")
            }
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: throw ToolError("'$pkg' is installed but has no launcher activity.")
            return startActivity(intent, "Launched $pkg.", "Could not launch '$pkg'.")
        }
        val installed = Shell.run("pm path $pkg")
        if (installed.exitCode != 0 || installed.out.isBlank()) {
            throw ToolError("Package '$pkg' is not installed. Use list_apps to find the right name.")
        }
        val r = Shell.run("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        val out = r.out + r.stderr
        if (out.contains("No activities found") || out.contains("aborted")) {
            throw ToolError("'$pkg' is installed but has no launcher activity. Try shell with `am start -n $pkg/<Activity>`.")
        }
        if (r.exitCode != 0) throw ToolError("Launching '$pkg' failed: ${out.trim().take(1000)}")
        return text("Launched $pkg.")
    }

    private fun notifications(): JSONObject {
        val r = Shell.run("dumpsys notification --noredact")
        if (r.exitCode != 0 && r.out.isBlank()) throw ToolError("dumpsys notification failed: ${r.stderr.take(500)}")
        val blocks = r.out.split("NotificationRecord(").drop(1)
        val seen = HashSet<String>()
        val sb = StringBuilder()
        for (b in blocks) {
            val pkg = Regex("pkg=(\\S+)").find(b)?.groupValues?.get(1) ?: continue
            fun extra(key: String) = Regex("$key=\\w+ \\((.*)\\)").find(b)?.groupValues?.get(1)?.takeIf { it != "null" }
            val title = extra("android\\.title").orEmpty()
            val body = (extra("android\\.bigText") ?: extra("android\\.text")).orEmpty()
            if (title.isEmpty() && body.isEmpty()) continue
            val line = "[$pkg] $title: $body"
            if (seen.add(line)) sb.append(line.take(1000)).append('\n')
        }
        return text(sb.toString().ifEmpty { "No notifications." })
    }

    @Suppress("DEPRECATION")
    private fun sendSms(to: String, message: String): JSONObject {
        if (!to.matches(Regex("^\\+?[0-9 ()\\-]{3,20}$"))) throw InvalidParams("'$to' doesn't look like a phone number.")
        if (message.isEmpty()) throw InvalidParams("Argument 'message' is empty.")
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (root()) Shell.run("pm grant ${ctx.packageName} android.permission.SEND_SMS")
            if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw ToolError("Sending SMS needs the SMS permission. On the phone: DroidMCP → Grant SMS permission.")
            }
        }
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val parts = sms.divideMessage(message)
        val action = "dev.droidmcp.SMS_SENT.${System.nanoTime()}"
        val latch = CountDownLatch(parts.size)
        val firstError = AtomicInteger(Activity.RESULT_OK)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (resultCode != Activity.RESULT_OK) firstError.compareAndSet(Activity.RESULT_OK, resultCode)
                latch.countDown()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            val sent = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sent += PendingIntent.getBroadcast(ctx, i, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)
            }
            try {
                sms.sendMultipartTextMessage(to, null, parts, sent, null)
            } catch (e: Exception) {
                throw ToolError("The phone refused to send the SMS: ${e.message}")
            }
            if (!latch.await(60, TimeUnit.SECONDS)) {
                return text("SMS to $to was handed to the radio but not confirmed within 60s; it may still send.")
            }
            val code = firstError.get()
            if (code != Activity.RESULT_OK) {
                val why = when (code) {
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile service"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "the radio is off (airplane mode?)"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "the message could not be encoded"
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure (no SIM, no credit, or carrier rejected it)"
                    else -> "error code $code"
                }
                throw ToolError("SMS to $to failed: $why.")
            }
            return text("SMS sent to $to (${parts.size} part(s)).")
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    private fun absPath(p: String): String {
        val path = p.trim()
        if (!path.startsWith("/")) throw InvalidParams("Path must be absolute (start with /), got '$p'.")
        return path
    }

    private fun readFile(path: String, maxBytes: Int): JSONObject {
        val q = Shell.q(path)
        val check = Shell.run("if [ -d $q ]; then echo DIR; elif [ -e $q ]; then stat -c %s $q; else echo MISSING; fi")
        when (val s = check.out.trim()) {
            "DIR" -> throw ToolError("'$path' is a directory. Use shell with `ls -la $path`.")
            "MISSING" -> throw ToolError("'$path' does not exist.")
            else -> {
                val size = s.toLongOrNull()
                val r = Shell.run("head -c $maxBytes $q", timeoutSec = 60)
                if (r.exitCode != 0) throw ToolError("Could not read '$path': ${r.stderr.ifBlank { "exit ${r.exitCode}" }.trim()}")
                val note = if (size != null && size > maxBytes) "\n[truncated: showing $maxBytes of $size bytes; raise max_bytes]" else ""
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                return try {
                    text(decoder.decode(ByteBuffer.wrap(r.stdout)).toString() + note)
                } catch (_: CharacterCodingException) {
                    text("base64 (${r.stdout.size} bytes):\n" + Base64.encodeToString(r.stdout, Base64.NO_WRAP) + note)
                }
            }
        }
    }

    private fun writeFile(path: String, content: String, isBase64: Boolean): JSONObject {
        val bytes = if (isBase64) {
            try {
                Base64.decode(content, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                throw InvalidParams("Argument 'content' is not valid base64.")
            }
        } else content.toByteArray()
        val tmp = File.createTempFile("w", ".tmp", ctx.cacheDir)
        try {
            tmp.writeBytes(bytes)
            val q = Shell.q(path)
            val r = Shell.run("mkdir -p \"$(dirname $q)\" && cat ${Shell.q(tmp.absolutePath)} > $q")
            if (r.exitCode != 0) {
                val why = r.stderr.trim()
                throw ToolError("Could not write '$path': ${why.ifEmpty { "exit ${r.exitCode}" }}" +
                    if (why.contains("Read-only")) " (system partitions are read-only; use a Magisk module to change them)" else "")
            }
        } finally {
            tmp.delete()
        }
        return text("Wrote ${bytes.size} bytes to $path")
    }

    // --- no-root helpers ---

    /** Root if available (checked once, then cached by Shell). */
    private fun root(): Boolean = Shell.rootState ?: Shell.hasRoot()

    private fun gesture(what: String, durationMs: Long, vararg pts: Pair<Int, Int>) {
        val svc = DroidAccessibility.require(what)
        val path = Path().apply {
            moveTo(pts[0].first.toFloat(), pts[0].second.toFloat())
            if (pts.size == 1) lineTo(pts[0].first.toFloat() + 0.5f, pts[0].second.toFloat())
            for (p in pts.drop(1)) lineTo(p.first.toFloat(), p.second.toFloat())
        }
        svc.gesture(path, durationMs)
    }

    private fun globalKey(code: String): JSONObject {
        val name = code.removePrefix("KEYCODE_")
        if (name == "ENTER") {
            DroidAccessibility.require("Pressing Enter").type("\n")
            return text("Pressed ENTER.")
        }
        val action = when (name) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "APP_SWITCH" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATION" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "POWER" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "SLEEP" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else null
            "SYSRQ" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else null
            else -> null
        } ?: throw ToolError("Without root, only these keys work: HOME, BACK, RECENTS, NOTIFICATION, POWER (menu), " +
            "SLEEP/LOCK, ENTER. '$name' needs root.")
        if (!DroidAccessibility.require("Pressing keys").global(action)) throw ToolError("Android refused the $name action.")
        return text("Pressed $name.")
    }

    /** Starts an activity from the accessibility service if it runs (exempt from background-launch limits), else the app. */
    private fun startActivity(intent: Intent, ok: String, noHandler: String): JSONObject {
        if (intent.resolveActivity(ctx.packageManager) == null) throw ToolError(noHandler)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launcher: Context = DroidAccessibility.instance ?: ctx
        try {
            launcher.startActivity(intent)
        } catch (e: Exception) {
            throw ToolError("$noHandler ${e.message}")
        }
        return text(if (launcher === ctx) "$ok (If nothing appeared, Android blocked a background launch: " +
            "turn on DroidMCP's accessibility service.)" else ok)
    }

    private fun smsQuery(box: String, limit: Int): JSONObject {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw ToolError("Reading SMS needs the SMS permission. On the phone: DroidMCP → Grant SMS permission.")
        }
        val uri = Uri.parse("content://sms/$box")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val sb = StringBuilder()
        ctx.contentResolver.query(uri, arrayOf("address", "date", "type", "read", "body"), null, null, "date DESC")?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val type = when (c.getInt(2)) { 1 -> "in"; 2 -> "sent"; 3 -> "draft"; else -> "other" }
                sb.append("${fmt.format(java.util.Date(c.getLong(1)))} [$type${if (c.getInt(3) == 0) ",unread" else ""}] " +
                    "${c.getString(0)}: ${c.getString(4)}\n")
            }
        } ?: throw ToolError("SMS storage is not readable on this phone.")
        return text(sb.toString().ifEmpty { "No messages." })
    }

    private fun storageHint(path: String) = if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager())
        " Without root, allow 'All files access' (DroidMCP → Allow all files access) for shared storage." else
        " Without root, only shared storage (/sdcard) and the app's own files are reachable."

    private fun readFilePlain(path: String, maxBytes: Int): JSONObject {
        val f = File(path)
        if (f.isDirectory) throw ToolError("'$path' is a directory. Use shell with root=false: `ls -la $path`.")
        if (!f.exists()) throw ToolError("'$path' does not exist, or this app can't see it.${storageHint(path)}")
        val bytes = try {
            f.inputStream().use { input ->
                val buf = ByteArray(minOf(f.length(), maxBytes.toLong()).toInt())
                var read = 0
                while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
                buf.copyOf(read)
            }
        } catch (e: Exception) {
            throw ToolError("Can't read '$path': ${e.message}.${storageHint(path)}")
        }
        val note = if (f.length() > maxBytes) "\n[truncated: showing $maxBytes of ${f.length()} bytes; raise max_bytes]" else ""
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        return try {
            text(decoder.decode(ByteBuffer.wrap(bytes)).toString() + note)
        } catch (_: CharacterCodingException) {
            text("base64 (${bytes.size} bytes):\n" + Base64.encodeToString(bytes, Base64.NO_WRAP) + note)
        }
    }

    private fun writeFilePlain(path: String, content: String, isBase64: Boolean): JSONObject {
        val bytes = if (isBase64) {
            try { Base64.decode(content, Base64.DEFAULT) } catch (_: IllegalArgumentException) {
                throw InvalidParams("Argument 'content' is not valid base64.")
            }
        } else content.toByteArray()
        try {
            File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        } catch (e: Exception) {
            throw ToolError("Can't write '$path': ${e.message}.${storageHint(path)}")
        }
        return text("Wrote ${bytes.size} bytes to $path")
    }

    // --- helpers ---

    private fun text(s: String, isError: Boolean = false) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", s)))
        .put("isError", isError)

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(required))
    }
}
