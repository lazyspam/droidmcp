package dev.droidmcp.ui

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.droidmcp.LinuxEnv
import dev.droidmcp.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TermBg = Color(0xFF000000)
private val Pill = Color(0xFF1C1C1E)
private val PillText = Color(0xFFE5E5EA)

/** Sticky modifier keys shared by the extra-keys bar and the TerminalView. */
private object Mods {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val linux = remember { LinuxEnv(ctx) }
    var installed by remember { mutableStateOf(linux.installed) }

    // Open the first session on first visit: Alpine if ready, otherwise wait for setup.
    LaunchedEffect(installed) {
        if (Terminals.tabs.isEmpty() && installed) Terminals.open(ctx, linux = true)
    }

    Column(Modifier.fillMaxSize().background(TermBg)) {
        TopBar(onNew = { asLinux ->
            if (asLinux && !linux.installed) return@TopBar
            Terminals.open(ctx, asLinux)
        }, linuxReady = installed)
        val tab = Terminals.active
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab != null -> TerminalPane(tab)
                !installed -> SetupLinux(linux) { installed = true }
                else -> EmptyState { Terminals.open(ctx, true) }
            }
        }
        // Shortcuts belong to typing: only show them while the keyboard is up.
        if (tab != null && WindowInsets.isImeVisible) ExtraKeys(tab.session)
    }
}

@Composable
private fun TopBar(onNew: (Boolean) -> Unit, linuxReady: Boolean) {
    var sessionsOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val tab = Terminals.active
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Session switcher pill.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Pill)
                    .clickable { sessionsOpen = true }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(
                    if (tab == null) StatusColors.off else if (tab.exited) StatusColors.error else StatusColors.live))
                Spacer(Modifier.width(8.dp))
                Text(tab?.title ?: "Terminal", color = PillText, fontSize = 14.sp, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null, tint = PillText, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = sessionsOpen, onDismissRequest = { sessionsOpen = false }) {
                if (Terminals.tabs.isEmpty()) DropdownMenuItem(text = { Text("No sessions") }, onClick = {}, enabled = false)
                Terminals.tabs.forEachIndexed { i, t ->
                    DropdownMenuItem(
                        text = { Text(t.title + if (t.exited) "  (ended)" else "") },
                        leadingIcon = { if (i == Terminals.current) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                        trailingIcon = { IconButton(onClick = { Terminals.close(t) }) { Icon(Icons.Filled.Close, "Close") } },
                        onClick = { Terminals.current = i; sessionsOpen = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            RoundIcon(onClick = { newOpen = true }) { Icon(Icons.Filled.Add, "New session", tint = PillText, modifier = Modifier.size(20.dp)) }
            DropdownMenu(expanded = newOpen, onDismissRequest = { newOpen = false }) {
                DropdownMenuItem(
                    text = { Column { Text("Alpine Linux"); Text("apk, git, python…", style = MaterialTheme.typography.bodySmall) } },
                    enabled = linuxReady, onClick = { newOpen = false; onNew(true) },
                )
                DropdownMenuItem(
                    text = { Column {
                        Text(if (Shell.rootState == true) "Android root shell" else "Android shell")
                        Text("The phone's own shell", style = MaterialTheme.typography.bodySmall)
                    } },
                    onClick = { newOpen = false; onNew(false) },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            RoundIcon(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, "More", tint = PillText, modifier = Modifier.size(20.dp)) }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                DropdownMenuItem(text = { Text("Paste") }, enabled = tab != null, onClick = {
                    moreOpen = false; tab?.session?.onPasteTextFromClipboard()
                })
                DropdownMenuItem(text = { Text("Clear screen") }, enabled = tab != null, onClick = {
                    moreOpen = false; tab?.session?.write("clear\n")
                })
                DropdownMenuItem(text = { Text("Bigger text") }, onClick = { moreOpen = false; TermFont.change(+2) })
                DropdownMenuItem(text = { Text("Smaller text") }, onClick = { moreOpen = false; TermFont.change(-2) })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Close session") }, enabled = tab != null, onClick = {
                    moreOpen = false; tab?.let { Terminals.close(it) }
                })
            }
        }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Pill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        content()
    }
}

private object TermFont {
    var sp by mutableIntStateOf(12)
    fun change(d: Int) { sp = (sp + d).coerceIn(8, 32) }
}

@Composable
private fun TerminalPane(tab: TermTab) {
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.scaledDensity
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        factory = { c ->
            TerminalView(c, null).apply {
                // The renderer is created by setTextSize; it must exist before the view lays out or draws.
                setTextSize((TermFont.sp * density).toInt())
                setTerminalViewClient(ViewClient(this, c))
                setTypeface(Typeface.MONOSPACE)
                isFocusable = true
                isFocusableInTouchMode = true
                Terminals.onScreenUpdate = { post { onScreenUpdated() } }
            }
        },
        update = { v ->
            val px = (TermFont.sp * density).toInt()
            if (v.tag != px) { v.setTextSize(px); v.tag = px }
            if (v.currentSession !== tab.session) v.attachSession(tab.session)
            v.requestFocus()
        },
    )
}

private fun TerminalSession.write(s: String) { val b = s.toByteArray(); write(b, 0, b.size) }

/** Glue between the Termux view and our sticky Ctrl/Alt keys and soft keyboard. */
private class ViewClient(private val view: TerminalView, private val ctx: Context) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) { TermFont.change(if (scale > 1f) 1 else -1); return 1f }
        return scale
    }
    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        ctx.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(e: MotionEvent?) = false
    // One-shot modifiers: consumed by the next key, like a phone's shift key.
    override fun readControlKey(): Boolean = Mods.ctrl.also { if (it) Mods.ctrl = false }
    override fun readAltKey(): Boolean = Mods.alt.also { if (it) Mods.alt = false }
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

@Composable
private fun ExtraKeys(session: TerminalSession) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF0C0C0E)).horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("Ctrl", active = Mods.ctrl) { Mods.ctrl = !Mods.ctrl }
        Key("Alt", active = Mods.alt) { Mods.alt = !Mods.alt }
        Box(Modifier.width(1.dp).height(20.dp).background(Color(0xFF3A3A3C)))
        Key("Tab") { session.write("\t") }
        Key("Esc") { session.write("\u001b") }
        Key("↑") { session.write("\u001b[A") }
        Key("↓") { session.write("\u001b[B") }
        Key("←") { session.write("\u001b[D") }
        Key("→") { session.write("\u001b[C") }
        Key("/") { session.write("/") }
        Key("-") { session.write("-") }
        Key("|") { session.write("|") }
        Key("~") { session.write("~") }
        Key("⏎") { session.write("\r") }
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Pill)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else PillText, fontSize = 13.sp)
    }
}

@Composable
private fun SetupLinux(linux: LinuxEnv, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(">_", color = StatusColors.live, fontFamily = Mono, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("A full Linux, in your pocket", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Alpine Linux with apk, git, python, node and thousands more packages. No root or Termux needed.",
            color = Color(0xFFAEAEB2), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        if (busy) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(progress, color = Color(0xFFAEAEB2), style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onClick = {
                busy = true; error = null
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        try { linux.setup { p -> scope.launch { progress = p } }; null } catch (e: Exception) { e.message ?: e.toString() }
                    }
                    busy = false
                    if (err == null) onDone() else error = err
                }
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Set up Linux · 3 MB") }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = StatusColors.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No open sessions", color = Color(0xFFAEAEB2))
        TextButton(onClick = onOpen) { Text("Open Alpine session") }
    }
}
