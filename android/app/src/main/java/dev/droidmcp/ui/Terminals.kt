package dev.droidmcp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.droidmcp.LinuxEnv
import dev.droidmcp.Mcp
import dev.droidmcp.Shell
import java.io.File

enum class TermKind(val label: String) { ALPINE("Alpine"), ANDROID("Android"), TERMUX("Termux") }

/** One terminal tab. */
class TermTab(val id: Int, val kind: TermKind, val session: TerminalSession) {
    var title by mutableStateOf("${kind.label} $id")
    var exited by mutableStateOf(false)
}

/**
 * All terminal sessions. Real PTYs via Termux's terminal emulator, so interactive programs
 * (vim, top, python REPL, Ctrl+C) work. Sessions live as long as the app process.
 */
object Terminals {
    val tabs = mutableStateListOf<TermTab>()
    var current by mutableIntStateOf(0)
    private var nextId = 1

    /** The TerminalView currently showing a session; it gets redrawn when output arrives. */
    var onScreenUpdate: (() -> Unit)? = null

    val active: TermTab? get() = tabs.getOrNull(current)

    private fun client(ctx: Context) = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { onScreenUpdate?.invoke() }
        override fun onTitleChanged(s: TerminalSession) {
            tabs.firstOrNull { it.session === s }?.let { t -> s.title?.takeIf { it.isNotBlank() }?.let { t.title = it } }
        }
        override fun onSessionFinished(s: TerminalSession) {
            tabs.firstOrNull { it.session === s }?.exited = true
            onScreenUpdate?.invoke()
        }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(s: TerminalSession?) {
            val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip ?: return
            val text = clip.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
            s?.emulator?.paste(text)
        }
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) { onScreenUpdate?.invoke() }
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Log.e(Mcp.TAG, "term: $message") }
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(Mcp.TAG, "term: $message", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(Mcp.TAG, "term", e) }
    }

    private const val TERMUX = "com.termux"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /** Termux sessions need Termux installed (and set up) plus root, to run as Termux's own user. */
    fun termuxAvailable(ctx: Context): Boolean = Shell.rootState == true && try {
        ctx.packageManager.getApplicationInfo(TERMUX, 0); true
    } catch (_: Exception) { false }

    /** Opens a new session and makes it current. Alpine requires [LinuxEnv.installed]. */
    fun open(ctx: Context, kind: TermKind): TermTab {
        val id = nextId++
        val session = when (kind) {
            TermKind.ALPINE -> linuxSession(ctx)
            TermKind.ANDROID -> androidSession(ctx)
            TermKind.TERMUX -> termuxSession(ctx)
        }
        val tab = TermTab(id, kind, session)
        tabs.add(tab)
        current = tabs.lastIndex
        return tab
    }

    fun close(tab: TermTab) {
        tab.session.finishIfRunning()
        val i = tabs.indexOf(tab)
        tabs.remove(tab)
        if (current >= tabs.size) current = (tabs.size - 1).coerceAtLeast(0)
        else if (i < current) current--
    }

    private const val WELCOME = "printf '\\033[32m> Welcome to DroidMCP Terminal\\033[0m\\n" +
        "\\033[90mAlpine Linux · apk add <package> installs anything · your files are in /sdcard\\033[0m\\n\\n'"

    private fun linuxSession(ctx: Context): TerminalSession {
        val env = LinuxEnv(ctx)
        // Prompt: blue working dir, then $; set after /etc/profile via $ENV (read by interactive ash/bash).
        val rc = File(env.rootfs, "root/.droidmcp_rc")
        if (!rc.exists()) {
            rc.parentFile?.mkdirs()
            rc.writeText(
                "export PS1='\\[\\033[38;5;75m\\]\\w\\[\\033[0m\\] \\$ '\n" +
                    "alias ls='ls --color=auto'\nalias ll='ls -la'\n"
            )
        }
        val script = "$WELCOME; export ENV=/root/.droidmcp_rc; " +
            "if command -v bash >/dev/null; then exec bash --rcfile /root/.droidmcp_rc -i; else exec /bin/sh -l; fi"
        val (argv, vars) = env.invocation(script)
        val envList = vars.map { "${it.key}=${it.value}" } + listOf("PATH=/system/bin", "TERM=xterm-256color")
        return TerminalSession(argv[0], ctx.filesDir.path, argv.toTypedArray(), envList.toTypedArray(), 5000, client(ctx))
    }

    /** Termux's own environment (packages, home, shell), run as Termux's user via su. */
    private fun termuxSession(ctx: Context): TerminalSession {
        val uid = ctx.packageManager.getApplicationInfo(TERMUX, 0).uid
        val home = "/data/data/com.termux/files/home"
        val p = TERMUX_PREFIX
        val script = "if [ ! -x $p/bin/login ]; then echo 'Termux is not set up yet: open the Termux app once, then try again.'; exit 1; fi; " +
            "cd $home && exec env -i HOME=$home PREFIX=$p TMPDIR=$p/tmp PATH=$p/bin LANG=en_US.UTF-8 TERM=xterm-256color " +
            "LD_PRELOAD=$p/lib/libtermux-exec.so SHELL=$p/bin/login $p/bin/login"
        val env = arrayOf("PATH=/system/bin:/system/xbin", "TERM=xterm-256color")
        val su = Shell.suArgv()
        return TerminalSession("/system/bin/su", "/", (su + listOf(uid.toString(), "-c", script)).toTypedArray(), env, 5000, client(ctx))
    }

    private fun androidSession(ctx: Context): TerminalSession {
        val root = Shell.rootState == true
        val shell = if (root) "/system/bin/su" else "/system/bin/sh"
        val home = if (File("/sdcard").canRead()) "/sdcard" else ctx.filesDir.path
        val env = arrayOf(
            "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin",
            "HOME=$home", "TERM=xterm-256color", "TMPDIR=${ctx.cacheDir.path}",
            "ANDROID_DATA=/data", "ANDROID_ROOT=/system",
        )
        val argv = if (root) Shell.suArgv().toTypedArray() else arrayOf("sh")
        return TerminalSession(shell, home, argv, env, 5000, client(ctx))
    }
}
