package com.lazyexec.droidmcp.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lazyexec.droidmcp.BuildInfo
import com.lazyexec.droidmcp.Updater
import com.lazyexec.droidmcp.Updater.Phase

/** Shows the update sheet whenever [Updater.sheet] is set, and [Updater.message] as a toast. Place once at the app root. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetHost() {
    val ctx = LocalContext.current
    Updater.message?.let { msg ->
        LaunchedEffect(msg) { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); Updater.message = null }
    }
    val r = Updater.sheet ?: return
    val phase = Updater.phase
    val busy = phase is Phase.Downloading || phase is Phase.Installing
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) Updater.later() }, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Update available", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("DroidMCP ${r.version}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "You have ${BuildInfo.VERSION}" + (if (r.apkSize > 0) " · download ${sizeText(r.apkSize)}" else ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (r.notes.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp).clip(RoundedCornerShape(Radius.medium))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { Markdown(r.notes) }
            }
            when (phase) {
                is Phase.Downloading -> {
                    if (phase.total > 0) LinearProgressIndicator(progress = { (phase.done.toFloat() / phase.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Downloading… ${sizeText(phase.done)}" + (if (phase.total > 0) " of ${sizeText(phase.total)}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Phase.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Installing… DroidMCP will restart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Phase.Handed -> Text("Finish the update in the installer. If it asks, allow DroidMCP to install apps.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is Phase.Failed -> Text(phase.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Phase.Idle -> {}
            }
            Button(onClick = { Updater.update(ctx) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(when (phase) { is Phase.Failed -> "Try again"; Phase.Handed -> "Open installer again"; else -> "Update" })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Updater.skip(ctx) }, enabled = !busy) { Text("Skip this version") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { Updater.later() }, enabled = !busy) { Text("Later") }
            }
        }
    }
}

private fun sizeText(b: Long) = when {
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "${b shr 10} KB"
    else -> "$b B"
}

/**
 * A small Markdown renderer for release notes: # headings, - * + and 1. lists, > quotes, ``` code blocks,
 * **bold**, *italic* / _italic_, `code`, [links](url) and bare https:// URLs. Anything else is plain text.
 */
@Composable
fun Markdown(src: String) {
    val ctx = LocalContext.current
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val open: (String) -> Unit = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    val lines = src.replace("\r\n", "\n").lines()
    var i = 0
    val para = StringBuilder()
    @Composable fun flush() {
        if (para.isNotEmpty()) {
            Text(inline(para.toString().trim(), link, codeBg, open), style = MaterialTheme.typography.bodyMedium)
            para.clear()
        }
    }
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        when {
            line.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                Text(code.toString().trimEnd(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(codeBg).padding(8.dp))
            }
            line.isEmpty() -> flush()
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                val text = line.drop(level).trim().trimEnd('#').trim()
                val style = when (level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall }
                Text(inline(text, link, codeBg, open), style = style, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            line.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> flush()
            line.startsWith(">") -> {
                flush()
                Text(inline(line.removePrefix(">").trim(), link, codeBg, open), style = MaterialTheme.typography.bodyMedium,
                    color = muted, fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 12.dp))
            }
            Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(line) -> {
                flush()
                val m = Regex("^([-*+]|\\d+[.)])\\s+").find(line)!!
                val marker = m.groupValues[1].let { if (it[0].isDigit()) it.trimEnd(')').trimEnd('.') + "." else "•" }
                val indent = (raw.length - raw.trimStart().length) / 2
                Row(Modifier.padding(start = (indent * 16).dp)) {
                    Text(marker, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
                    Text(inline(line.substring(m.range.last + 1), link, codeBg, open), style = MaterialTheme.typography.bodyMedium)
                }
            }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
        }
        i++
    }
    flush()
}

private val inlineRe = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)|(https?://[^\s)<>]+)""")

private fun inline(s: String, link: TextLinkStyles, codeBg: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var pos = 0
    for (m in inlineRe.findAll(s)) {
        append(s.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(g[1].ifEmpty { g[2] }, link, codeBg, open)) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[3]) }
            g[4].isNotEmpty() -> withLink(LinkAnnotation.Url(g[5], link) { open(g[5]) }) { append(g[4]) }
            g[6].isNotEmpty() || g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[6].ifEmpty { g[7] }, link, codeBg, open)) }
            g[8].isNotEmpty() -> withLink(LinkAnnotation.Url(g[8], link) { open(g[8]) }) { append(g[8]) }
        }
        pos = m.range.last + 1
    }
    append(s.substring(pos))
}

/** Settings → About: "Check for updates" with the current version. Long-press shows the latest release's sheet even if it isn't newer. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UpdateCheckRow() {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small))
            .combinedClickable(enabled = !Updater.checking, onLongClick = { Updater.manualCheck(ctx, preview = true) }) { Updater.manualCheck(ctx) }
            .padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Check for updates", style = MaterialTheme.typography.bodyLarge)
            Text(if (Updater.checking) "Checking…" else "Version ${BuildInfo.VERSION}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (Updater.checking) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}
