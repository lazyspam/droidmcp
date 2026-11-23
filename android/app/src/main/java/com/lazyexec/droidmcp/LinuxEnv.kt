package com.lazyexec.droidmcp

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
/** Linux distributions DroidMCP can download and run. */
enum class Distro(val id: String, val label: String, val sizeHint: String, val blurb: String) {
    ALPINE("alpine", "Alpine Linux", "3 MB", "Tiny and fast · apk"),
    UBUNTU("ubuntu", "Ubuntu 26.04", "30 MB", "Runs most prebuilt Linux software · apt");

    companion object {
        fun of(id: String?): Distro? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}

class LinuxEnv(private val ctx: Context, val distro: Distro = Distro.ALPINE) {
    // Alpine keeps its original location so existing installs carry over.
    private val base = File(ctx.filesDir, if (distro == Distro.ALPINE) "linux" else "linux-${distro.id}")
    val rootfs = File(base, "rootfs")
    private val tmp = File(base, "tmp")
    private val ready = File(base, ".installed")
    private val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    private val lock = locks.getValue(distro)

    // Check real directories, not symlinks like bin/sh (absolute links resolve against Android's own /bin).
    val installed: Boolean get() = ready.isFile && File(rootfs, "etc").isDirectory && File(rootfs, "usr/bin").isDirectory

    /** Version recorded at install time. */
    val version: String get() = runCatching { ready.readText().trim() }.getOrDefault("")

    /** Disk used, in bytes (walks the tree; call off the main thread). */
    fun sizeBytes(): Long = if (!base.exists()) 0 else base.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }

    /** Removes the distro and everything installed in it. */
    fun uninstall() = synchronized(lock) {
        ready.delete()
        base.deleteRecursively()
    }

    private fun arch(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            val a = when (distro) {
                Distro.ALPINE -> when (abi) { "arm64-v8a" -> "aarch64"; "x86_64" -> "x86_64"; "armeabi-v7a" -> "armv7"; else -> null }
                Distro.UBUNTU -> when (abi) { "arm64-v8a" -> "arm64"; "x86_64" -> "amd64"; "armeabi-v7a" -> "armhf"; else -> null }
            }
            if (a != null) return a
        }
        throw ToolError("Unsupported CPU (${Build.SUPPORTED_ABIS.joinToString()}) for ${distro.label}.")
    }

    /** Where to download from: (url, sha256 or null, version). */
    private fun locate(http: OkHttpClient, arch: String, progress: (String) -> Unit): Triple<String, String?, String> {
        progress("Finding the latest ${distro.label} release for $arch…")
        return when (distro) {
            Distro.ALPINE -> {
                val mirror = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch"
                val yaml = get(http, "$mirror/latest-releases.yaml").use { it.bufferedReader().readText() }
                val block = yaml.split("\n-").firstOrNull { it.contains("flavor: alpine-minirootfs") }
                    ?: throw ToolError("Couldn't find a minirootfs in Alpine's release list.")
                val file = Regex("file:\\s*(\\S+)").find(block)?.groupValues?.get(1) ?: throw ToolError("Malformed Alpine release list.")
                val sha = Regex("sha256:\\s*([0-9a-f]{64})").find(block)?.groupValues?.get(1)
                val version = Regex("version:\\s*(\\S+)").find(block)?.groupValues?.get(1) ?: "?"
                Triple("$mirror/$file", sha, version)
            }
            Distro.UBUNTU -> {
                val dir = "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release"
                val sums = get(http, "$dir/SHA256SUMS").use { it.bufferedReader().readText() }
                // Lines look like: "<sha> *ubuntu-base-26.04.1-base-arm64.tar.gz". Pick the newest point release.
                val re = Regex("^([0-9a-f]{64}) \\*?(ubuntu-base-(26\\.04(?:\\.(\\d+))?)-base-$arch\\.tar\\.gz)$", RegexOption.MULTILINE)
                val best = re.findAll(sums).maxByOrNull { it.groupValues[4].toIntOrNull() ?: 0 }
                    ?: throw ToolError("No Ubuntu Base image for $arch in Ubuntu's release list.")
                Triple("$dir/${best.groupValues[2]}", best.groupValues[1], best.groupValues[3])
            }
        }
    }

    /**
     * Downloads and unpacks the distro. Safe to call again; does nothing if already installed unless [force].
     * One setup per distro at a time, even across [LinuxEnv] instances (the AI and the Settings screen each make their own).
     */
    fun setup(force: Boolean = false, progress: (String) -> Unit = {}): String = synchronized(lock) { setupLocked(force, progress) }

    private fun setupLocked(force: Boolean, progress: (String) -> Unit): String {
        if (installed && !force) return "${distro.label} is already installed."
        val proot = File(nativeDir, "libproot.so")
        if (!proot.exists()) throw ToolError("proot is missing from this build (${nativeDir.path}). Reinstall the app.")

        val arch = arch()
        val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val (url, sha, version) = locate(http, arch, progress)
        val file = url.substringAfterLast('/')

        progress("Downloading $file…")
        base.mkdirs()
        val archive = File(base, "rootfs.tar.gz.part")
        val digest = MessageDigest.getInstance("SHA-256")
        get(http, url).use { input ->
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
            throw ToolError("Unpacking ${distro.label} failed: ${e.message}")
        } finally {
            archive.delete()
        }

        configure()

        progress("Checking that it runs…")
        val check = run(". /etc/os-release && echo \"\$PRETTY_NAME\"", 60)
        if (check.exitCode != 0) {
            throw ToolError("${distro.label} unpacked but won't run under proot (exit ${check.exitCode}): ${check.stderr.take(1500)}")
        }
        ready.writeText(version)
        val hint = if (distro == Distro.UBUNTU) "`apt update && apt install -y python3 git`" else "`apk add python3 git`"
        return "Installed ${check.out.trim()} ($arch). Install packages with $hint."
    }

    /** Fixes a fresh rootfs for life under proot on Android. */
    private fun configure() {
        // Android has no /etc/resolv.conf: give the distro its own DNS. (resolv.conf may be a dangling symlink.)
        File(rootfs, "etc/resolv.conf").let { it.delete(); it.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").mkdirs()
        tmp.mkdirs()
        // Name Android's supplementary groups (inet, net_raw, …) so tools don't warn about unknown group IDs.
        File(rootfs, "etc/group").let { g ->
            if (g.isFile && !g.readText().contains("aid_inet")) {
                g.appendText("aid_inet:x:3003:root\naid_net_raw:x:3004:root\naid_sdcard_rw:x:1015:root\naid_everybody:x:9997:root\n")
            }
        }
        if (distro == Distro.UBUNTU) {
            // apt drops to the _apt user for downloads; that can't work under proot's fake root.
            File(rootfs, "etc/apt/apt.conf.d").mkdirs()
            File(rootfs, "etc/apt/apt.conf.d/99droidmcp").writeText(
                "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Use-Pty \"0\";\n")
        }
    }

    /** Runs a shell command inside the distro. */
    fun run(command: String, timeoutSec: Long): ShellResult {
        val (argv, env) = invocation(command)
        return Shell.exec(argv, env, timeoutSec, removeEnv = listOf("LD_PRELOAD"))
    }

    /** The proot command line and environment that run [command] inside Alpine. */
    fun invocation(command: String, workdir: String = "/root"): Pair<List<String>, Map<String, String>> {
        tmp.mkdirs()
        val argv = mutableListOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            // Bash process substitution and friends expect these.
            "-b", "/proc/self/fd:/dev/fd", "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout", "-b", "/proc/self/fd/2:/dev/stderr",
        )
        // Share phone storage when the app can see it.
        for (p in listOf("/sdcard", "/storage")) if (File(p).exists()) argv += listOf("-b", p)
        argv += listOf(
            "-w", workdir,
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
        return argv to env
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

    private companion object {
        /** Per distro, shared by every instance: setup and uninstall of the same distro never overlap. */
        val locks: Map<Distro, Any> = Distro.entries.associateWith { Any() }
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

            val out = inside(root, name)
            when (type) {
                '5' -> { out.mkdirs(); chmod(out, mode) }
                '2' -> {
                    out.parentFile?.mkdirs()
                    out.delete()
                    Os.symlink(link, out.path)
                }
                '1' -> hardlinks += out to inside(root, link, resolveLast = false)
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
            // A hardlink to a symlink becomes the same symlink: copying would read through it, maybe outside the rootfs.
            if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                Os.symlink(Os.readlink(target.path), link.path)
            } else if (target.exists()) {
                target.copyTo(link, overwrite = true)
                chmod(link, Os.stat(target.path).st_mode and 0x1ff)
            }
        }
    }

    /**
     * [name] under [root]; refuses entries that escape it (../ tricks), for entries and hardlink targets alike.
     * With [resolveLast] false only the parent is resolved: a hardlink may name a symlink whose own target is outside
     * (it is recreated as a symlink, not read through).
     */
    private fun inside(root: File, name: String, resolveLast: Boolean = true): File {
        val plain = File(root, name.trimEnd('/'))
        val f = if (resolveLast || plain.name == "..") plain.canonicalFile else File(plain.parentFile!!.canonicalFile, plain.name)
        if (f != root && !f.path.startsWith(root.path + File.separator)) throw IllegalStateException("unsafe path in archive: $name")
        return plain
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
