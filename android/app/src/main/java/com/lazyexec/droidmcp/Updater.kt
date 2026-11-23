package com.lazyexec.droidmcp

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * In-app updates from GitHub releases (iput-object/droidmcp, tags v<version>, asset DroidMCP-<version>.apk).
 * Checks once each time the app comes to the foreground (skipped if the last check was under [MIN_GAP_MS] ago,
 * a plain timestamp comparison, no timers or scheduling) and on demand from Settings → About.
 * Requests send If-None-Match with the last ETag, so an unchanged release costs a 304 with no body.
 * The UI ([com.lazyexec.droidmcp.ui.UpdateSheetHost]) observes [sheet].
 */
object Updater {
    private const val API = "https://api.github.com/repos/iput-object/droidmcp/releases/latest"
    private const val MIN_GAP_MS = 5 * 60 * 1000L

    data class Release(val version: String, val notes: String, val apkUrl: String?, val apkName: String, val apkSize: Long, val pageUrl: String)

    sealed interface Phase {
        data object Idle : Phase
        data class Downloading(val done: Long, val total: Long) : Phase
        data object Installing : Phase
        /** Handed to the system installer: the user finishes there. */
        data object Handed : Phase
        data class Failed(val message: String) : Phase
    }

    /** The release shown in the sheet, or null when no sheet. */
    var sheet by mutableStateOf<Release?>(null)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    /** True while a manual check runs (Settings row shows "Checking…"). */
    var checking by mutableStateOf(false)
        private set
    /** One-off message for the UI (snackbar/toast): "You're on the latest version", errors of a manual check. */
    var message by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** App came to the foreground: one quiet request, unless the last check was under 5 minutes ago. Respects "Skip this version". */
    fun autoCheck(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val last = p.getLong("last_check", 0)
        if (now - last in 0 until MIN_GAP_MS || sheet != null) return
        p.edit().putLong("last_check", now).apply()
        val app = c.applicationContext
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { fetch(app) } }.getOrNull() ?: return@launch
            if (isNewer(r.version, BuildInfo.VERSION) && r.version != p.getString("skipped", null)) show(r)
        }
    }

    /**
     * From Settings. Shows the sheet for any newer version (even a skipped one) or a "latest" message.
     * [preview] shows the latest release even when it is not newer (long-press: lets you test the sheet).
     */
    fun manualCheck(c: Context, preview: Boolean = false) {
        if (checking) return
        checking = true
        prefs(c).edit().putLong("last_check", System.currentTimeMillis()).apply()
        val app = c.applicationContext
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { fetch(app) }
                if (preview || isNewer(r.version, BuildInfo.VERSION)) show(r)
                else message = "You're on the latest version (${BuildInfo.VERSION})"
            } catch (e: Exception) {
                message = e.message ?: "Couldn't check for updates"
            } finally {
                checking = false
            }
        }
    }

    private fun show(r: Release) { phase = Phase.Idle; sheet = r }

    fun later() { dismiss() }

    fun skip(c: Context) {
        sheet?.let { prefs(c).edit().putString("skipped", it.version).apply() }
        dismiss()
    }

    fun dismiss() {
        job?.cancel(); job = null
        sheet = null
        phase = Phase.Idle
    }

    /** Download the release APK into the cache, then install it (root: pm install; otherwise the system installer). */
    fun update(c: Context) {
        val r = sheet ?: return
        if (job?.isActive == true) return
        val app = c.applicationContext
        job = scope.launch {
            try {
                val url = r.apkUrl ?: throw IOException("This release has no ${r.apkName} to download")
                phase = Phase.Downloading(0, r.apkSize)
                val apk = withContext(Dispatchers.IO) { download(app, url, r) { d, t -> scope.launch { if (phase is Phase.Downloading) phase = Phase.Downloading(d, t) } } }
                phase = Phase.Installing
                val root = withContext(Dispatchers.IO) { Shell.rootState ?: Shell.hasRoot() }
                prefs(app).edit().putString("pending", r.version).putBoolean("pending_root", root).apply()
                if (root) {
                    // On success pm kills this process and the detached script starts us again; we only get here on failure.
                    val err = withContext(Dispatchers.IO) { rootInstall(app, apk) }
                    prefs(app).edit().remove("pending").apply()
                    ActivityLog.add("✗ update to ${r.version}: $err")
                    phase = Phase.Failed(err)
                } else {
                    // The installer kills us too; UpdateReceiver restarts the server (Prefs.serverWasOn).
                    systemInstall(app, apk)
                    phase = Phase.Handed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- network ----

    private fun fetch(c: Context): Release {
        val p = prefs(c)
        val cachedEtag = p.getString("etag", null)
        val cachedBody = p.getString("etag_body", null)
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "DroidMCP/${BuildInfo.VERSION}")
            .apply { if (cachedEtag != null && cachedBody != null) header("If-None-Match", cachedEtag) }
            .build()
        val res = try { client.newCall(req).execute() } catch (e: IOException) {
            throw IOException("No connection to GitHub. Check your network and try again.")
        }
        res.use {
            val body = if (it.code == 304 && cachedBody != null) cachedBody else it.body?.string().orEmpty()
            if (it.code == 403 || it.code == 429) {
                val reset = it.header("X-RateLimit-Reset")?.toLongOrNull()
                val mins = reset?.let { s -> ((s * 1000 - System.currentTimeMillis()) / 60000).coerceAtLeast(1) }
                throw IOException("GitHub rate limit reached." + (mins?.let { m -> " Try again in $m min." } ?: " Try again later."))
            }
            if (it.code == 404) throw IOException("No published release found")
            if (!it.isSuccessful && it.code != 304) throw IOException("GitHub answered ${it.code}")
            if (it.code == 200) it.header("ETag")?.let { e -> p.edit().putString("etag", e).putString("etag_body", body).apply() }
            val j = JSONObject(body)
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) throw IOException("No published release found")
            val version = j.getString("tag_name").removePrefix("v").removePrefix("V")
            val name = "DroidMCP-$version.apk"
            val assets = j.optJSONArray("assets")
            var url: String? = null
            var size = 0L
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name") == name) { url = a.optString("browser_download_url"); size = a.optLong("size") }
            }
            return Release(version, j.optString("body").trim(), url, name, size, j.optString("html_url"))
        }
    }

    private fun download(c: Context, url: String, r: Release, progress: (Long, Long) -> Unit): File {
        val dir = File(c.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val part = File(dir, "${r.apkName}.part")
        val req = Request.Builder().url(url).header("User-Agent", "DroidMCP/${BuildInfo.VERSION}").build()
        val res = try { client.newCall(req).execute() } catch (e: IOException) {
            throw IOException("Download failed: no connection. Check your network and try again.")
        }
        res.use {
            if (!it.isSuccessful) throw IOException("Download failed (HTTP ${it.code})")
            val body = it.body ?: throw IOException("Download failed: empty response")
            val total = body.contentLength().takeIf { n -> n > 0 } ?: r.apkSize
            var done = 0L
            var last = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = try { input.read(buf) } catch (e: IOException) { throw IOException("Download interrupted. Try again.") }
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) { last = done; progress(done, total) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download incomplete ($done of $total bytes). Try again.")
            progress(done, total)
        }
        val apk = File(dir, r.apkName)
        if (!part.renameTo(apk)) throw IOException("Couldn't save the download")
        return apk
    }

    // ---- install ----

    private const val TMP_APK = "/data/local/tmp/droidmcp-update.apk"
    private const val LOG = "/data/local/tmp/droidmcp-update.log"
    private const val INSTALL_TIMEOUT_MS = 180_000L

    /**
     * Installs with `pm install -r` from a detached root script: pm kills this process (and the MCP server with it)
     * when it replaces the app, so the script outlives us and then starts the server again if it was running, and the
     * app screen if it was open. pm's output goes to [LOG]; we poll it, and only return (with an error message) when
     * the install failed or didn't finish. On success we are killed first and [reportPending] notes it on next start.
     */
    private suspend fun rootInstall(c: Context, apk: File): String {
        val pkg = c.packageName
        val q = Shell::q
        val restart = buildString {
            if (Status.running) append("am start-foreground-service -n ${q("$pkg/${McpService::class.java.name}")} >/dev/null 2>&1; ")
            if (MainActivity.visible) append("am start -n ${q("$pkg/${MainActivity::class.java.name}")} >/dev/null 2>&1; ")
        }
        // Copy to /data/local/tmp so pm (system_server/shell) can read it regardless of our app's data permissions.
        val job = "pm install -r ${q(TMP_APK)} > ${q(LOG)} 2>&1; code=\$?; echo \"exit=\$code\" >> ${q(LOG)}; " +
            "rm -f ${q(TMP_APK)}; [ \$code -eq 0 ] && { $restart:; }; true"
        val script = "rm -f ${q(LOG)}; cp ${q(apk.absolutePath)} ${q(TMP_APK)} && chmod 644 ${q(TMP_APK)} || { echo 'copy failed'; exit 1; }\n" +
            "setsid nohup sh -c ${q(job)} </dev/null >/dev/null 2>&1 &"
        val r = try { Shell.run(script, root = true, timeoutSec = 60) } catch (e: Exception) {
            return "Root install failed: ${e.message}"
        }
        if (r.exitCode != 0) return "Root install failed: " + (r.out + "\n" + r.stderr).trim().ifBlank { "exit ${r.exitCode}" }
        apk.delete()
        val deadline = System.currentTimeMillis() + INSTALL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(1500)
            val log = readLog() ?: continue
            if (log.lineSequence().any { it.startsWith("exit=") }) return describeFailure(log)
        }
        return "Install timed out"
    }

    private fun readLog(): String? = runCatching { Shell.run("cat ${Shell.q(LOG)} 2>/dev/null", root = true, timeoutSec = 10).out }.getOrNull()

    private fun describeFailure(log: String): String {
        val out = log.lineSequence().filterNot { it.startsWith("exit=") }.joinToString("\n").trim()
        return when {
            out.contains("Success") -> "Installed. Restart DroidMCP to use the new version."
            out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
                "Install failed: the new APK is signed with a different key than this build (for example a debug build). Uninstall this one first, or install the APK by hand."
            out.contains("INSTALL_FAILED_VERSION_DOWNGRADE") -> "Install failed: the downloaded version is older than the installed one."
            else -> "Install failed: " + out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                .ifBlank { log.lineSequence().firstOrNull { it.startsWith("exit=") }.orEmpty() }
        }
    }

    /**
     * Called at app start: if an in-app update was under way when the old process died, log how it ended
     * ("Updated to X", or pm's error from the root install log).
     */
    fun reportPending(c: Context) {
        val p = prefs(c)
        val target = p.getString("pending", null) ?: return
        val root = p.getBoolean("pending_root", false)
        p.edit().remove("pending").remove("pending_root").apply()
        reported = true
        File(c.cacheDir, "update").deleteRecursively()
        if (compare(BuildInfo.VERSION, target) >= 0) {
            ActivityLog.add("updated to ${BuildInfo.VERSION}")
            if (root) Thread { runCatching { Shell.run("rm -f ${Shell.q(LOG)}", root = true, timeoutSec = 10) } }.start()
            return
        }
        Thread {
            val log = if (root) readLog() else null
            ActivityLog.add("✗ update to $target: " + if (log.isNullOrBlank()) "did not finish" else describeFailure(log))
        }.start()
    }

    @Volatile private var reported = false

    /** From [UpdateReceiver]: the app was replaced. Logs a manual install that [reportPending] didn't already cover. */
    fun onReplaced(c: Context) {
        if (!reported) ActivityLog.add("app replaced, now ${BuildInfo.VERSION}")
    }

    private fun systemInstall(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", apk)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (e: Exception) { throw IOException("No installer available on this phone") }
    }

    // ---- versions ----

    /** Semantic comparison of dotted versions ("1.2.10" > "1.2.9"); a pre-release suffix sorts before the plain version. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, String?> {
            val core = v.trim().removePrefix("v").substringBefore('+')
            val pre = core.substringAfter('-', "").ifEmpty { null }
            return core.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (na, pa) = split(a)
        val (nb, pb) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val d = (na.getOrElse(i) { 0 }).compareTo(nb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return when {
            pa == pb -> 0
            pa == null -> 1
            pb == null -> -1
            else -> pa.compareTo(pb)
        }
    }
}
