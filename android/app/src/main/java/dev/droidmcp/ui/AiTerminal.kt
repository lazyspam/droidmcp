package dev.droidmcp.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.droidmcp.Distro
import dev.droidmcp.LinuxEnv
import dev.droidmcp.ShellResult
import dev.droidmcp.ToolError
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The AI's own visible terminal session. AI commands are typed into one persistent shell per distro, so
 * `cd`, env vars and virtualenvs carry over, the user can watch live, answer prompts, press Ctrl+C, or carry on
 * from where the AI left off. Output is also captured to files so the AI gets clean text and the exit code.
 */
object AiTerminal {
    /**
     * Bash helper the app types `␠__dmcp <id>` to run. It erases that typed line, shows the real command, runs it in
     * the current shell (so state persists), tees output to a file and writes the exit code last.
     */
    const val HELPER = """
__dmcp() {
  local id="${'$'}1" d=/tmp/.dmcp cmd rc
  cmd=${'$'}(cat "${'$'}d/${'$'}id.cmd")
  printf '\033[1A\033[2K\r\033[38;5;75mAI ❯\033[0m %s\n' "${'$'}cmd"
  exec 9> >(tee "${'$'}d/${'$'}id.out")
  { eval "${'$'}cmd"; } >&9 2>&9
  rc=${'$'}?
  exec 9>&-
  sleep 0.05
  echo "${'$'}rc" > "${'$'}d/${'$'}id.rc"
}
"""

    private val main = Handler(Looper.getMainLooper())
    private val locks = HashMap<Distro, Any>()

    /** Command the AI is running right now in the visible session (for the "AI is running" strip), or null. */
    var running by mutableStateOf<Pair<Int, String>?>(null)
        private set

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var r: Result<T>? = null
        main.post { r = runCatching(block); latch.countDown() }
        if (!latch.await(10, TimeUnit.SECONDS)) throw ToolError("The app's UI thread is busy; try again.")
        return r!!.getOrThrow()
    }

    /** The live AI tab for [d], opening one if needed (it starts even with no screen attached). */
    private fun tab(ctx: Context, d: Distro): TermTab = onMain {
        Terminals.tabs.firstOrNull { it.ai && it.kind.distro == d && !it.exited }
            ?: Terminals.open(ctx, TermKind.of(d), ai = true, makeCurrent = false).also { t ->
                // With no TerminalView yet, start the shell ourselves; the view resizes it when shown.
                t.session.updateSize(100, 32, 10, 20)
            }
    }

    /** The shell is at its prompt (no foreground job), so typing a command won't go into someone else's program. */
    private fun idle(tab: TermTab): Boolean {
        val root = tab.session.pid
        if (root <= 0) return false
        // proot's child is the interactive shell (bash); find it among proot's descendants.
        val procs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return true
        val stats = procs.mapNotNull { f -> runCatching { f.name.toInt() to File(f, "stat").readText() }.getOrNull() }
        fun field(stat: String, i: Int) = stat.substringAfterLast(") ").split(' ').getOrNull(i - 3)?.toIntOrNull()
        val shell = stats.filter { (_, st) -> field(st, 4) == root && st.contains("(bash)") }.minByOrNull { it.first } ?: return true
        val pgrp = field(shell.second, 5)
        val tpgid = field(shell.second, 8)
        return pgrp != null && pgrp == tpgid
    }

    /** Runs [command] in the AI session of [d]. Waits for idle, then for completion or [timeoutSec]. */
    fun run(ctx: Context, d: Distro, command: String, timeoutSec: Long): ShellResult {
        val env = LinuxEnv(ctx, d)
        ensureBash(env)
        val lock = synchronized(locks) { locks.getOrPut(d) { Any() } }
        synchronized(lock) {
            var tab = tab(ctx, d)
            // Wait briefly for the shell to be at its prompt (the user may be running something).
            val waitUntil = System.currentTimeMillis() + 15_000
            Thread.sleep(300)
            while (!idle(tab)) {
                if (tab.exited) tab = tab(ctx, d)
                if (System.currentTimeMillis() > waitUntil) {
                    throw ToolError("The AI terminal is busy with another program (maybe yours). Finish or Ctrl+C it in the " +
                        "Terminal tab, or run with session:false for a separate shell.")
                }
                Thread.sleep(250)
            }
            val dir = File(env.rootfs, "tmp/.dmcp").apply { mkdirs() }
            val id = UUID.randomUUID().toString().take(8)
            val cmdFile = File(dir, "$id.cmd"); val out = File(dir, "$id.out"); val rc = File(dir, "$id.rc")
            cmdFile.writeText(command)
            running = tab.id to command
            try {
                // Ctrl+U clears anything half-typed; the leading space keeps it out of history.
                onMain { write(tab, "\u0015 __dmcp $id\r") }
                val deadline = System.currentTimeMillis() + timeoutSec * 1000
                var timedOut = false
                while (!rc.exists()) {
                    if (tab.exited) throw ToolError("The AI terminal closed while running the command. ${tail(out)}")
                    if (System.currentTimeMillis() > deadline) {
                        timedOut = true
                        onMain { write(tab, "\u0003") } // Ctrl+C, like a person would
                        val grace = System.currentTimeMillis() + 3000
                        while (!rc.exists() && System.currentTimeMillis() < grace) Thread.sleep(100)
                        break
                    }
                    Thread.sleep(120)
                }
                Thread.sleep(60)
                val code = rc.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: -1
                val bytes = if (out.exists()) out.readBytes() else ByteArray(0)
                return ShellResult(code, clean(bytes), "", timedOut, bytes.size > 2_000_000)
            } finally {
                running = null
                listOf(cmdFile, out, rc).forEach { it.delete() }
            }
        }
    }

    /** What's on screen in the AI session (or the session shown in the app), for the AI to read. */
    fun read(which: String, lines: Int): String = onMain {
        val tab = when (which) {
            "current" -> Terminals.active
            else -> Terminals.tabs.lastOrNull { it.ai && !it.exited } ?: Terminals.active
        } ?: throw ToolError("No terminal session is open.")
        val text = tab.session.emulator?.screen?.transcriptText ?: ""
        "[${tab.title}]\n" + text.lines().dropLastWhile { it.isBlank() }.takeLast(lines).joinToString("\n")
    }

    /** Types into the AI session (newest live one). */
    fun type(text: String) = onMain {
        val tab = Terminals.tabs.lastOrNull { it.ai && !it.exited } ?: throw ToolError("The AI terminal isn't open yet.")
        write(tab, text)
    }

    /** Ctrl+C in the AI session that is currently running something. */
    fun interrupt() {
        val id = running?.first ?: return
        main.post { Terminals.tabs.firstOrNull { it.id == id }?.let { write(it, "\u0003") } }
    }

    private fun write(tab: TermTab, s: String) { val b = s.toByteArray(); tab.session.write(b, 0, b.size) }

    private fun tail(f: File) = if (f.exists()) "Last output: " + String(f.readBytes()).takeLast(500) else ""

    /** Strip terminal colour/cursor codes and carriage-return redraws (progress bars) from captured output. */
    private fun clean(b: ByteArray): ByteArray {
        val s = String(b, Charsets.UTF_8)
            .replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
            .split('\n').joinToString("\n") { line -> line.substringAfterLast('\r') }
        return s.toByteArray()
    }

    /** The helper needs bash. Ubuntu has it; Alpine gets it once (~1 MB). */
    private fun ensureBash(env: LinuxEnv) {
        val has = File(env.rootfs, "bin/bash").exists() || File(env.rootfs, "usr/bin/bash").exists()
        if (has) return
        if (env.distro != Distro.ALPINE) throw ToolError("bash is missing in ${env.distro.label}.")
        val r = env.run("apk add -q --no-progress bash", 180)
        if (r.exitCode != 0) throw ToolError("Couldn't install bash for the AI terminal: ${r.stderr.take(500)}")
    }
}
