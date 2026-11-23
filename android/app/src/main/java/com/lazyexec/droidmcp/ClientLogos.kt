package com.lazyexec.droidmcp

import androidx.annotation.DrawableRes

/**
 * The logo of the AI client that sent a request, from the name in its MCP initialize clientInfo
 * ("claude-code", "cursor-vscode", ...). Logos are Simple Icons (CC0 only), res/drawable/ic_client_*.xml.
 * No logo (generic droid): OpenAI Codex and VS Code (not in Simple Icons), GitHub Copilot (MIT there, not CC0).
 */
object ClientLogos {
    /** [tinted]: a single-colour logo drawn in the surrounding text colour; otherwise it keeps its brand colour. */
    data class Logo(@DrawableRes val res: Int, val tinted: Boolean)

    /** Shown for clients without a logo here, and for events saved without a client. */
    val generic = Logo(R.drawable.ic_client_generic, tinted = true)

    /**
     * Name fragments, matched case-insensitively, first match wins. Fragments of 4 letters or fewer must be a
     * whole word of the name ("Zed", not "customized"). Order matters: Cursor sends "cursor-vscode".
     */
    private val table = listOf(
        "claude-code" to Logo(R.drawable.ic_client_claudecode, false), // Claude Code
        "claude code" to Logo(R.drawable.ic_client_claudecode, false),
        "claude" to Logo(R.drawable.ic_client_claude, false),        // claude-ai (Desktop, claude.ai)
        "gemini" to Logo(R.drawable.ic_client_gemini, false),        // gemini-cli-mcp-client
        "cursor" to Logo(R.drawable.ic_client_cursor, true),         // cursor-vscode
        "windsurf" to Logo(R.drawable.ic_client_windsurf, true),
        "vscodium" to Logo(R.drawable.ic_client_vscodium, false),    // VSCodium (VS Code's nameLong)
        "zed" to Logo(R.drawable.ic_client_zed, true),               // Zed
        "trae" to Logo(R.drawable.ic_client_trae, true),
        "cline" to Logo(R.drawable.ic_client_cline, true),           // Cline
        "opencode" to Logo(R.drawable.ic_client_opencode, true),
        "jetbrains" to Logo(R.drawable.ic_client_jetbrains, true),
        "warp" to Logo(R.drawable.ic_client_warp, false),
        "lm studio" to Logo(R.drawable.ic_client_lmstudio, true),
        "lmstudio" to Logo(R.drawable.ic_client_lmstudio, true),
        "raycast" to Logo(R.drawable.ic_client_raycast, false),
        "qwen" to Logo(R.drawable.ic_client_qwen, false),            // qwen-code-mcp-client (Qwen Code)
    )

    /** The client's logo, or null if [name] has none here (callers show [generic]). */
    fun of(name: String?): Logo? {
        val n = name?.lowercase()?.trim().orEmpty()
        if (n.isEmpty()) return null
        val words by lazy { n.split(Regex("[^a-z0-9]+")).toSet() }
        return table.firstOrNull { (key, _) -> if (key.length <= 4) key in words else key in n }?.second
    }

    fun orGeneric(name: String?): Logo = of(name) ?: generic
}
