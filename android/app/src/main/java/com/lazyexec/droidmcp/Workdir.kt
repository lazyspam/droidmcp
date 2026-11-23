package com.lazyexec.droidmcp

import org.json.JSONObject

/**
 * Working-directory support for the shell tools: wraps a command so it starts in a folder and reports
 * where it ended up, and describes a project folder (its type, how to build/run it, its AGENTS.md)
 * the first time the AI works in it, so it doesn't have to `cd …` and look around on every call.
 */
object Workdir {
    private const val CWD_MARK = "__DROIDMCP_CWD__"

    /** Runs [cmd] in [cwd] (if set) and prints the final directory on stderr, so `cd` sticks between calls. */
    fun wrap(cmd: String, cwd: String?): String {
        val cd = if (cwd.isNullOrBlank()) "" else
            "cd -- ${Shell.q(cwd)} || { echo \"cwd '${cwd.replace("'", "")}' is not usable; pass another cwd\" >&2; exit 1; }\n"
        // A brace group keeps the command in this shell, so its own `cd` moves the directory we record.
        return "$cd{\n$cmd\n}\n__rc=\$?\nprintf '\\n$CWD_MARK%s\\n' \"\$PWD\" >&2\nexit \$__rc"
    }

    /** Splits the recorded directory off stderr. Returns (clean stderr, final dir or null if the command exited early). */
    fun unwrap(stderr: String): Pair<String, String?> {
        val i = stderr.lastIndexOf(CWD_MARK)
        if (i < 0) return stderr to null
        val dir = stderr.substring(i + CWD_MARK.length).trim().ifEmpty { null }
        return stderr.substring(0, i).trimEnd('\n') to dir
    }

    /** Script that lists the files that identify a project, plus package.json, Makefile targets and AGENTS.md. */
    fun probeScript(dir: String): String = """
        cd -- ${Shell.q(dir)} 2>/dev/null || exit 0
        for f in AGENTS.md CLAUDE.md .git package.json bun.lock bun.lockb pnpm-lock.yaml yarn.lock package-lock.json \
          pyproject.toml uv.lock poetry.lock requirements.txt setup.py go.mod Cargo.toml gradlew build.gradle build.gradle.kts \
          settings.gradle settings.gradle.kts pom.xml Makefile CMakeLists.txt composer.json Gemfile pubspec.yaml \
          Dockerfile docker-compose.yml compose.yaml .env.example; do [ -e "${'$'}f" ] && echo "F:${'$'}f"; done
        [ -f package.json ] && { echo '@@PKG'; head -c 65536 package.json; echo; echo '@@END'; }
        [ -f Makefile ] && { echo '@@MAKE'; grep -oE '^[A-Za-z0-9_.-]+:' Makefile | grep -v '^\.' | head -20; echo '@@END'; }
        for n in AGENTS.md CLAUDE.md; do [ -f "${'$'}n" ] && { echo "@@DOC ${'$'}n"; head -c 4096 "${'$'}n"; echo; echo '@@END'; break; }; done
        exit 0
    """.trimIndent()

    /** Turns [probeScript]'s output into a short "how to work here" note, or null if nothing was recognized. */
    fun describe(dir: String, probe: String): String? {
        val files = probe.lineSequence().filter { it.startsWith("F:") }.map { it.removePrefix("F:") }.toSet()
        fun section(tag: String): String? {
            val start = probe.indexOf("@@$tag")
            if (start < 0) return null
            val body = probe.indexOf('\n', start).takeIf { it >= 0 } ?: return null
            val end = probe.indexOf("\n@@END", body).takeIf { it >= 0 } ?: probe.length
            return probe.substring(body + 1, end)
        }
        val lines = mutableListOf<String>()

        if ("package.json" in files) {
            val pm = when {
                "bun.lock" in files || "bun.lockb" in files -> "bun"
                "pnpm-lock.yaml" in files -> "pnpm"
                "yarn.lock" in files -> "yarn"
                else -> "npm"
            }
            val pkg = try { JSONObject(section("PKG") ?: "{}") } catch (_: Exception) { JSONObject() }
            val scripts = pkg.optJSONObject("scripts")?.keys()?.asSequence()?.toList().orEmpty()
            val run = if (pm == "npm") "npm run" else pm
            lines += "Node project (${pkg.optString("name").ifEmpty { "package.json" }}), package manager: $pm. " +
                "Install: `$pm install`." +
                if (scripts.isEmpty()) "" else " Scripts (`$run <name>`): ${scripts.take(15).joinToString()}."
        }
        if (files.any { it in setOf("pyproject.toml", "requirements.txt", "setup.py") }) {
            lines += when {
                "uv.lock" in files -> "Python project (uv): `uv sync`, then `uv run <cmd>`."
                "poetry.lock" in files -> "Python project (poetry): `poetry install`, then `poetry run <cmd>`."
                "requirements.txt" in files -> "Python project: `python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt`."
                else -> "Python project: `pip install -e .` (in a venv)."
            }
        }
        if ("go.mod" in files) lines += "Go module: `go build ./...`, `go test ./...`."
        if ("Cargo.toml" in files) lines += "Rust crate: `cargo build`, `cargo test`."
        if (files.any { it.startsWith("build.gradle") || it.startsWith("settings.gradle") }) {
            lines += "Gradle project: `${if ("gradlew" in files) "./gradlew" else "gradle"} build` (Android: `assembleDebug`)."
        }
        if ("pom.xml" in files) lines += "Maven project: `mvn package`."
        if ("CMakeLists.txt" in files) lines += "CMake project: `cmake -B build && cmake --build build`."
        if ("composer.json" in files) lines += "PHP project: `composer install`."
        if ("Gemfile" in files) lines += "Ruby project: `bundle install`."
        if ("pubspec.yaml" in files) lines += "Dart/Flutter project: `dart pub get` / `flutter pub get`."
        section("MAKE")?.lines()?.map { it.trim().removeSuffix(":") }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let {
            lines += "Makefile targets: ${it.joinToString()}."
        }
        if (files.any { it in setOf("Dockerfile", "docker-compose.yml", "compose.yaml") }) lines += "Has Docker files."
        if (".env.example" in files) lines += "Copy .env.example to .env before running."
        if (".git" in files) lines += "Git repo."

        val docTag = probe.lineSequence().firstOrNull { it.startsWith("@@DOC ") }?.removePrefix("@@DOC ")
        val doc = docTag?.let { section("DOC $it")?.trim() }
        if (lines.isEmpty() && doc == null) return null
        val sb = StringBuilder("project ($dir):\n")
        lines.forEach { sb.append("- ").append(it).append('\n') }
        if (doc != null) {
            sb.append("$docTag (the project's own instructions; follow them):\n").append(doc)
            if (doc.length >= 4000) sb.append("\n[truncated; read the whole $docTag]")
            sb.append('\n')
        }
        return sb.toString()
    }
}
