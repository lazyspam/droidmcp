package com.lazyexec.droidmcp.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import com.lazyexec.droidmcp.Distro
import com.lazyexec.droidmcp.LinuxEnv

/**
 * Distro downloads, owned by the app (not a screen), so they keep going when you switch tabs or rotate,
 * and every screen shows the same progress.
 */
object LinuxDownloads {
    /** Distro -> latest progress line, while downloading. */
    val progress = mutableStateMapOf<Distro, String>()
    /** Distro -> last error, if the last attempt failed. */
    val errors = mutableStateMapOf<Distro, String>()
    /** Bumped when something is installed or removed, so screens re-check. */
    val changed = mutableStateMapOf<Distro, Long>()
    private val main = Handler(Looper.getMainLooper())

    fun busy(d: Distro) = progress.containsKey(d)

    fun start(ctx: Context, d: Distro, onDone: (() -> Unit)? = null) {
        if (busy(d)) return
        val app = ctx.applicationContext
        progress[d] = "Starting…"
        errors.remove(d)
        Thread {
            val err = try {
                LinuxEnv(app, d).setup { p -> main.post { if (busy(d)) progress[d] = p } }
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            main.post {
                progress.remove(d)
                if (err != null) errors[d] = err else { changed[d] = System.currentTimeMillis(); onDone?.invoke() }
            }
        }.start()
    }

    fun uninstall(ctx: Context, d: Distro, onDone: () -> Unit) {
        val app = ctx.applicationContext
        Thread {
            // Close any open sessions of this distro first.
            main.post { Terminals.tabs.filter { it.kind.distro == d }.forEach { Terminals.close(it) } }
            LinuxEnv(app, d).uninstall()
            main.post { changed[d] = System.currentTimeMillis(); onDone() }
        }.start()
    }
}
