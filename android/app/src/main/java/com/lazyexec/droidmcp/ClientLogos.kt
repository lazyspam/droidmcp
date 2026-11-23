package com.lazyexec.droidmcp

import androidx.annotation.DrawableRes

/**
 * The AI client that sent a request, from the name in its MCP initialize clientInfo ("claude-code",
 * "cursor-vscode", ...): its display name ("Claude") and logo, from one table so the two always agree.
 * Logos are Simple Icons (CC0 only), res/drawable/ic_client_*.xml.
 * No logo (generic droid): ChatGPT, OpenAI Codex and VS Code (not in Simple Icons), GitHub Copilot (MIT there, not CC0).
 */
object ClientLogos {
    /** [tinted]: a single-colour logo drawn in the surrounding text colour; otherwise it keeps its brand colour. */
    data class Logo(@DrawableRes val res: Int, val tinted: Boolean)

    /** A known client: the name shown for it and its logo (null: the [generic] droid). */
    private data class Client(val name: String, val logo: Logo?)

    /** Shown for clients without a logo here, and for events saved without a client. */
    val generic = Logo(R.drawable.ic_client_generic, tinted = true)

    /** Shown when a client sent no name. */
    const val UNNAMED = "AI client"

    private val claudeCode = Client("Claude", Logo(R.drawable.ic_client_claudecode, false))
    private val chatGpt = Client("ChatGPT", null)
    private val vsCode = Client("VS Code", null)
    private val lmStudio = Client("LM Studio", Logo(R.drawable.ic_client_lmstudio, true))

    /**
     * Name fragments, matched case-insensitively, first match wins. Fragments of 4 letters or fewer must be a
     * whole word of the name ("Zed", not "customized"). Order matters: Cursor sends "cursor-vscode".
     */
    private val table = listOf(
        "claude-code" to claudeCode,                                                  // Claude Code
        "claude code" to claudeCode,
        "claude" to Client("Claude", Logo(R.drawable.ic_client_claude, false)),       // claude-ai (Desktop, claude.ai)
        "openai-mcp" to chatGpt,                                                      // ChatGPT connectors / Apps SDK
        "chatgpt" to chatGpt,
        "codex" to Client("Codex", null),                                             // codex-mcp-client (Codex CLI)
        "gemini" to Client("Gemini", Logo(R.drawable.ic_client_gemini, false)),       // gemini-cli-mcp-client
        "cursor" to Client("Cursor", Logo(R.drawable.ic_client_cursor, true)),        // cursor-vscode
        "windsurf" to Client("Windsurf", Logo(R.drawable.ic_client_windsurf, true)),
        "vscodium" to Client("VSCodium", Logo(R.drawable.ic_client_vscodium, false)), // VSCodium (VS Code's nameLong)
        "visual studio code" to vsCode,                                               // VS Code (nameLong), Copilot in it
        "vscode" to vsCode,
        "copilot" to vsCode,
        "zed" to Client("Zed", Logo(R.drawable.ic_client_zed, true)),                 // Zed
        "trae" to Client("Trae", Logo(R.drawable.ic_client_trae, true)),
        "cline" to Client("Cline", Logo(R.drawable.ic_client_cline, true)),           // Cline
        "opencode" to Client("OpenCode", Logo(R.drawable.ic_client_opencode, true)),
        "jetbrains" to Client("JetBrains", Logo(R.drawable.ic_client_jetbrains, true)),
        "warp" to Client("Warp", Logo(R.drawable.ic_client_warp, false)),
        "lm studio" to lmStudio,
        "lmstudio" to lmStudio,
        "raycast" to Client("Raycast", Logo(R.drawable.ic_client_raycast, false)),
        "qwen" to Client("Qwen", Logo(R.drawable.ic_client_qwen, false)),             // qwen-code-mcp-client (Qwen Code)
    )

    private fun find(name: String?): Client? {
        val n = name?.lowercase()?.trim().orEmpty()
        if (n.isEmpty()) return null
        val words by lazy { n.split(Regex("[^a-z0-9]+")).toSet() }
        return table.firstOrNull { (key, _) -> if (key.length <= 4) key in words else key in n }?.second
    }

    /** The client's logo, or null if [name] has none here (callers show [generic]). */
    fun of(name: String?): Logo? = find(name)?.logo

    fun orGeneric(name: String?): Logo = of(name) ?: generic

    /**
     * The name to show for a client, from its clientInfo name or the saved "name version" label:
     * the brand for known clients ("Claude"), otherwise the name without its version, capitalised
     * ("my-agent 1.2" -> "My Agent"); [UNNAMED] without a name.
     */
    fun name(raw: String?): String {
        val r = raw?.trim().orEmpty()
        if (r.isEmpty()) return UNNAMED
        find(r)?.let { return it.name }
        val words = r.split(Regex("\\s+")).toMutableList()
        if (words.size > 1 && words.last().matches(Regex("v?\\d[\\w.+-]*", RegexOption.IGNORE_CASE))) words.removeAt(words.lastIndex)
        return words.joinToString(" ").split(Regex("[\\s_-]+")).filter { it.isNotEmpty() }
            .joinToString(" ") { w -> if (w.any { it.isUpperCase() }) w else w.replaceFirstChar { it.uppercase() } }
            .ifEmpty { UNNAMED }
    }
}
