package dev.droidmcp

import android.content.Context
import android.os.Build
import android.system.Os
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * A full Alpine Linux userland inside the app, run with proot. Needs neither root nor Termux.
 *
 * The proot binaries ship in the APK as lib*.so (from Termux's proot package), because since Android 10
 * apps may only execute files from their native library directory, not from their data directory.
 * proot's loader then runs the Alpine binaries from the data directory on its behalf.
 */
class LinuxEnv(private val ctx: Context) {
    private val base = File(ctx.filesDir, "linux")
    val rootfs = File(base, "rootfs")
    private val tmp = File(base, "tmp")
    private val ready = File(base, ".installed")
    private val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)

    // Don't test bin/sh: it's an absolute symlink to /bin/busybox, which Java resolves against Android's own /bin.
    val installed: Boolean get() = ready.isFile && File(rootfs, "etc/alpine-release").isFile

    private fun alpineArch(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "x86_64" -> return "x86_64"
                "armeabi-v7a" -> return "armv7"
            }
        }
        throw ToolError("Unsupported CPU (${Build.SUPPORTED_ABIS.joinToString()}). Alpine needs arm64, armv7 or x86_64.")
    }

    /** Downloads and unpacks Alpine. Safe to call again; does nothing if already installed unless [force]. */
    @Synchronized
    fun setup(force: Boolean = false, progress: (String) -> Unit = {}): String {
        if (installed && !force) return "Alpine Linux is already installed."
        val proot = File(nativeDir, "libproot.so")
        if (!proot.exists()) throw ToolError("proot is missing from this build (${nativeDir.path}). Reinstall the app.")

        val arch = alpineArch()
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val mirror = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch"

        progress("Finding the latest Alpine release for $arch…")
        val yaml = get(http, "$mirror/latest-releases.yaml").use { it.bufferedReader().readText() }
        val block = yaml.split("\n-").firstOrNull { it.contains("flavor: alpine-minirootfs") }
            ?: throw ToolError("Couldn't find a minirootfs in Alpine's release list.")
        val file = Regex("file:\\s*(\\S+)").find(block)?.groupValues?.get(1) ?: throw ToolError("Malformed Alpine release list.")
        val sha = Regex("sha256:\\s*([0-9a-f]{64})").find(block)?.groupValues?.get(1)
        val version = Regex("version:\\s*(\\S+)").find(block)?.groupValues?.get(1) ?: "?"

        progress("Downloading $file…")
        base.mkdirs()
        val archive = File(base, "rootfs.tar.gz.part")
        val digest = MessageDigest.getInstance("SHA-256")
        get(http, "$mirror/$file").use { input ->
            archive.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != null && got != sha) {
            archive.delete()
            throw ToolError("Download corrupted: SHA-256 mismatch for $file. Try again.")
        }

        progress("Unpacking…")
        ready.delete()
        if (rootfs.exists()) rootfs.deleteRecursively()
        rootfs.mkdirs()
        try {
            archive.inputStream().use { Tar.extract(GZIPInputStream(BufferedInputStream(it)), rootfs) }
        } catch (e: Exception) {
            rootfs.deleteRecursively()
            throw ToolError("Unpacking Alpine failed: ${e.message}")
        } finally {
            archive.delete()
        }

        // Android has no /etc/resolv.conf, so Alpine needs its own DNS settings.
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").mkdirs()
        tmp.mkdirs()

        progress("Checking that it runs…")
        val check = run("cat /etc/alpine-release", 60)
        if (check.exitCode != 0) {
            throw ToolError("Alpine unpacked but won't run under proot (exit ${check.exitCode}): ${check.stderr.take(1500)}")
        }
        ready.writeText(version)
        return "Installed Alpine Linux ${check.out.trim()} ($arch). Install packages with `apk add python3 git` etc."
    }

    /** Runs a shell command inside Alpine. */
    fun run(command: String, timeoutSec: Long): ShellResult {
        tmp.mkdirs()
        val argv = mutableListOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
        )
        // Share phone storage when the app can see it.
        for (p in listOf("/sdcard", "/storage")) if (File(p).exists()) argv += listOf("-b", p)
        argv += listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "/bin/sh", "-lc", command,
        )
        val env = mutableMapOf(
            "PROOT_TMP_DIR" to tmp.path,
            "PROOT_LOADER" to File(nativeDir, "libproot-loader.so").path,
            "LD_LIBRARY_PATH" to nativeDir.path,
        )
        File(nativeDir, "libproot-loader32.so").takeIf { it.exists() }?.let { env["PROOT_LOADER_32"] = it.path }
        return Shell.exec(argv, env, timeoutSec, removeEnv = listOf("LD_PRELOAD"))
    }

    private fun get(http: OkHttpClient, url: String): InputStream {
        val resp = try {
            http.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: java.net.UnknownHostException) {
            throw ToolError("No internet: can't resolve ${e.message}.")
        } catch (e: java.io.IOException) {
            throw ToolError("Download failed for $url: ${e.message}")
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw ToolError("Download failed: HTTP ${resp.code} for $url")
        }
        return resp.body!!.byteStream()
    }
}

/** Minimal tar reader: files, directories, symlinks, hardlinks, GNU long names and pax paths. */
object Tar {
    fun extract(input: InputStream, dest: File) {
        val root = dest.canonicalFile
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        val hardlinks = mutableListOf<Pair<File, File>>()
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it.toInt() == 0 }) break
            var name = str(header, 0, 100)
            val prefix = str(header, 345, 155)
            if (prefix.isNotEmpty() && header[257].toInt() == 'u'.code) name = "$prefix/$name"
            val mode = oct(header, 100, 8).toInt()
            val size = oct(header, 124, 12)
            val type = header[156].toInt().toChar()
            var link = str(header, 157, 100)

            when (type) {
                'L' -> { longName = String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(readBytes(input, size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> {
                    val pax = parsePax(String(readBytes(input, size), Charsets.UTF_8))
                    pax["path"]?.let { longName = it }
                    pax["linkpath"]?.let { longLink = it }
                    continue
                }
                'g' -> { skip(input, size); continue }
            }
            longName?.let { name = it }; longName = null
            longLink?.let { link = it }; longLink = null

            val out = File(root, name).canonicalFile.let { f ->
                // Refuse entries that escape the target directory (../ tricks).
                if (f != root && !f.path.startsWith(root.path + File.separator)) throw IllegalStateException("unsafe path in archive: $name")
                File(root, name.trimEnd('/'))
            }
            when (type) {
                '5' -> { out.mkdirs(); chmod(out, mode) }
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Os.symlink(link, out.path)
                }
                '1' -> hardlinks += out to File(root, link)
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    out.outputStream().use { o -> copy(input, o, size) }
                    chmod(out, mode)
                }
                else -> skip(input, size) // devices, fifos: not needed in a rootfs
            }
            // Bodies are padded to 512-byte blocks.
            if (type == '0' || type == '\u0000' || type == '7' || type !in "512") skip(input, (512 - size % 512) % 512)
        }
        // Android forbids hardlinks for apps: copy instead.
        for ((link, target) in hardlinks) {
            link.parentFile?.mkdirs()
            link.delete()
            if (target.exists()) {
                target.copyTo(link, overwrite = true)
                chmod(link, Os.stat(target.path).st_mode and 0x1ff)
            }
        }
    }

    private fun chmod(f: File, mode: Int) = try { Os.chmod(f.path, mode and 0x1ff) } catch (_: Exception) {}

    private fun parsePax(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (line in s.split('\n')) {
            val sp = line.indexOf(' ')
            val eq = line.indexOf('=')
            if (sp in 0 until eq) m[line.substring(sp + 1, eq)] = line.substring(eq + 1)
        }
        return m
    }

    private fun str(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun oct(b: ByteArray, off: Int, len: Int): Long {
        val s = str(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw java.io.EOFException("truncated archive")
            }
            read += n
        }
        return true
    }

    /** Reads a body of [size] bytes plus its padding to 512. */
    private fun readBytes(input: InputStream, size: Long): ByteArray {
        val b = ByteArray(size.toInt())
        readFully(input, b)
        skip(input, (512 - size % 512) % 512)
        return b
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw java.io.EOFException("truncated archive")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw java.io.EOFException("truncated archive")
            left -= r
        }
    }
}
