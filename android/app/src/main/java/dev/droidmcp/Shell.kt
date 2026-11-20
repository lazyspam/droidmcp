package dev.droidmcp

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
) {
    val out: String get() = String(stdout, Charsets.UTF_8)
}

/** Thrown when root is missing or the root manager refused this app. */
class RootUnavailable(msg: String) : Exception(msg)

/** Runs shell commands, as root via `su` by default. The script goes in on stdin, so it needs no extra quoting. */
object Shell {
    private const val MAX_OUTPUT = 32 * 1024 * 1024

    /** Last known root state: null = not checked yet. */
    @Volatile var rootState: Boolean? = null
        private set

    fun run(script: String, root: Boolean = true, timeoutSec: Long = 30): ShellResult {
        val proc = try {
            ProcessBuilder(if (root) "su" else "sh").start()
        } catch (e: IOException) {
            if (root) {
                rootState = false
                throw RootUnavailable("Root is not available: `su` was not found. Install Magisk/KernelSU/APatch, or call shell with root=false.")
            }
            throw e
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val tOut = pump(proc.inputStream, out)
        val tErr = pump(proc.errorStream, err)
        try {
            // Kill the whole command tree on timeout: `timeout` is in toybox on Android 7+.
            // Without it, destroying `su` can leave the command running as root.
            val wrapped = "if command -v timeout >/dev/null 2>&1; then exec timeout -s KILL $timeoutSec sh -c ${q(script)}; " +
                "else $script\nfi"
            proc.outputStream.use { it.write((wrapped + "\n").toByteArray()) }
        } catch (_: IOException) {
            // su exited before reading stdin (usually: access denied). Exit code and stderr below say why.
        }
        val finished = proc.waitFor(timeoutSec + 5, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        tOut.join(2000); tErr.join(2000)
        val code = if (finished) proc.exitValue() else -1
        val stderr = err.toString(Charsets.UTF_8.name())
        if (root && looksDenied(code, stderr, out.size())) {
            rootState = false
            throw RootUnavailable("Root access was denied. Open your root manager (Magisk/KernelSU/APatch) and grant superuser to DroidMCP.")
        }
        if (root && code != -1) rootState = true
        return ShellResult(
            exitCode = code,
            stdout = out.toByteArray(),
            stderr = stderr,
            // 124/137 = killed by `timeout`
            timedOut = !finished || code == 124 || code == 137,
            truncated = out.size() >= MAX_OUTPUT,
        )
    }

    fun hasRoot(): Boolean = try {
        val r = run("id -u", root = true, timeoutSec = 20)
        (r.exitCode == 0 && r.out.trim() == "0").also { rootState = it }
    } catch (_: Exception) {
        false
    }

    /** Single-quote a string for sh. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun looksDenied(code: Int, stderr: String, outSize: Int): Boolean {
        if (code == 0 || outSize > 0) return false
        val s = stderr.lowercase()
        return s.contains("permission denied") && (s.contains("su:") || s.trim() == "permission denied") ||
            s.contains("not allowed to su") || s.contains("access denied")
    }

    private fun pump(input: InputStream, sink: ByteArrayOutputStream) = Thread {
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (sink.size() < MAX_OUTPUT) sink.write(buf, 0, minOf(n, MAX_OUTPUT - sink.size()))
            }
        } catch (_: Exception) {
        }
    }.apply { isDaemon = true; start() }
}
