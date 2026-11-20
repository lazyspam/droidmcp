package dev.droidmcp.ui

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.foundation.layout.imeAnimationTarget
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
import androidx.compose.ui.draw.alpha
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val default = remember { dev.droidmcp.Prefs.defaultDistro(ctx) }
    // A distro the user asked for that still needs downloading (shown over the terminal until done or cancelled).
    var setupFor by remember { mutableStateOf<dev.droidmcp.Distro?>(null) }

    // First visit: open the default distro if it's ready, otherwise offer to download it.
    LaunchedEffect(Unit) {
        if (Terminals.tabs.isEmpty()) {
            if (LinuxEnv(ctx, default).installed) Terminals.open(ctx, TermKind.of(default)) else setupFor = default
        }
    }

    Column(Modifier.fillMaxSize().background(TermBg)) {
        TopBar(onNew = { kind ->
            val d = kind.distro
            if (d != null && !LinuxEnv(ctx, d).installed) setupFor = d else { setupFor = null; Terminals.open(ctx, kind) }
        })
        val tab = Terminals.active
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val pending = setupFor
            when {
                pending != null -> SetupLinux(pending, canCancel = tab != null,
                    onCancel = { setupFor = null }) { setupFor = null; Terminals.open(ctx, TermKind.of(pending)) }
                tab != null -> Column {
                    AiStrip(tab)
                    Box(Modifier.weight(1f)) { TerminalPane(tab) }
                }
                else -> EmptyState { setupFor = default }
            }
        }
        // Shortcuts belong to typing: shown as soon as the keyboard starts opening, in the same single resize.
        val keyboardComing = WindowInsets.imeAnimationTarget.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (tab != null && keyboardComing) ExtraKeys(tab.session)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TopBar(onNew: (TermKind) -> Unit) {
    val ctx = LocalContext.current
    var sessionsOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TermTab?>(null) }
    val tab = Terminals.active
    renaming?.let { t -> RenameDialog(t) { renaming = null } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Session switcher pill.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Pill)
                    .combinedClickable(onClick = { sessionsOpen = true }, onLongClick = { renaming = tab })
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                if (tab != null) SessionIcon(tab.kind, dimmed = tab.exited, size = 18)
                else Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.off))
                Spacer(Modifier.width(8.dp))
                Text(tab?.title ?: "Terminal", color = PillText, fontSize = 14.sp, maxLines = 1)
                Icon(Icons.Filled.ArrowDropDown, null, tint = PillText, modifier = Modifier.size(20.dp))
            }
            AppMenu(expanded = sessionsOpen, onDismiss = { sessionsOpen = false }) {
                if (Terminals.tabs.isEmpty()) AppMenuItem(text = { Text("No sessions") }, onClick = {}, enabled = false)
                Terminals.tabs.forEachIndexed { i, t ->
                    AppMenuItem(
                        text = { Text(t.title + if (t.exited) "  (ended)" else "") },
                        leadingIcon = { SessionIcon(t.kind, dimmed = t.exited) },
                        modifier = if (i == Terminals.current) Modifier.padding(horizontal = 6.dp)
                            .clip(RoundedCornerShape(Radius.small)).background(MaterialTheme.colorScheme.secondaryContainer) else Modifier,
                        trailingIcon = { IconButton(onClick = { Terminals.close(t) }) { Icon(Icons.Filled.Close, "Close") } },
                        onClick = { Terminals.current = i; sessionsOpen = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box {
            RoundIcon(onClick = { newOpen = true }) { Icon(Icons.Filled.Add, "New session", tint = PillText, modifier = Modifier.size(20.dp)) }
            AppMenu(expanded = newOpen, onDismiss = { newOpen = false }) {
                // Every distro is listed; ones not downloaded yet say so (recognition over recall).
                dev.droidmcp.Distro.entries.forEach { d ->
                    LinuxDownloads.changed[d] // re-read installed state after a download
                    val ready = LinuxEnv(ctx, d).installed
                    AppMenuItem(
                        leadingIcon = { SessionIcon(TermKind.of(d)) },
                        text = { Column {
                            Text(d.label)
                            Text(if (ready) d.blurb else if (LinuxDownloads.busy(d)) "Downloading…" else "Download · ${d.sizeHint}",
                                style = MaterialTheme.typography.bodySmall)
                        } },
                        onClick = { newOpen = false; onNew(TermKind.of(d)) },
                    )
                }
                if (Terminals.termuxAvailable(ctx)) {
                    AppMenuItem(
                        leadingIcon = { SessionIcon(TermKind.TERMUX) },
                        text = { Column { Text("Termux"); Text("Your Termux packages and home", style = MaterialTheme.typography.bodySmall) } },
                        onClick = { newOpen = false; onNew(TermKind.TERMUX) },
                    )
                }
                AppMenuItem(
                    leadingIcon = { SessionIcon(TermKind.ANDROID) },
                    text = { Column {
                        Text(if (Shell.rootState == true) "Android root shell" else "Android shell")
                        Text("The phone's own shell", style = MaterialTheme.typography.bodySmall)
                    } },
                    onClick = { newOpen = false; onNew(TermKind.ANDROID) },
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            RoundIcon(onClick = { moreOpen = true }) { Icon(Icons.Filled.MoreVert, "More", tint = PillText, modifier = Modifier.size(20.dp)) }
            AppMenu(expanded = moreOpen, onDismiss = { moreOpen = false }) {
                AppMenuItem(text = { Text("Rename session") }, enabled = tab != null, onClick = {
                    moreOpen = false; renaming = tab
                })
                AppMenuItem(text = { Text("Paste") }, enabled = tab != null, onClick = {
                    moreOpen = false; tab?.session?.onPasteTextFromClipboard()
                })
                AppMenuItem(text = { Text("Clear screen") }, enabled = tab != null, onClick = {
                    moreOpen = false; tab?.session?.write("clear\n")
                })
                AppMenuItem(text = { Text("Bigger text") }, onClick = { moreOpen = false; TermFont.change(+2) })
                AppMenuItem(text = { Text("Smaller text") }, onClick = { moreOpen = false; TermFont.change(-2) })
                HorizontalDivider()
                AppMenuItem(text = { Text("Close session") }, enabled = tab != null, onClick = {
                    moreOpen = false; tab?.let { Terminals.close(it) }
                })
            }
        }
    }
}

/** Shown above the AI session while the AI's command runs: what it's doing, and a Stop (Ctrl+C) button. */
@Composable
private fun AiStrip(tab: TermTab) {
    val run = AiTerminal.running
    androidx.compose.animation.AnimatedVisibility(run != null && run.first == tab.id) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(Radius.small))
                .background(StatusColors.busy.copy(alpha = 0.18f)).padding(start = 12.dp, end = 4.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = StatusColors.busy, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("AI ❯ ${run?.second?.lineSequence()?.firstOrNull().orEmpty()}", color = PillText, fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontFamily = Mono, modifier = Modifier.weight(1f))
            TextButton(onClick = { AiTerminal.interrupt() }) { Text("Stop") }
        }
    }
}

@Composable
private fun RenameDialog(tab: TermTab, onDone: () -> Unit) {
    var name by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(tab.title,
        androidx.compose.ui.text.TextRange(0, tab.title.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDone(); Terminals.refocus() },
        title = { Text("Rename session") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                name, { name = it }, singleLine = true, placeholder = { Text("${tab.kind.label} ${tab.id}") },
                supportingText = { Text("Leave empty to use the default name") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { tab.rename(name.text); onDone() }),
                modifier = Modifier.focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { tab.rename(name.text); onDone(); Terminals.refocus() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(); Terminals.refocus() }) { Text("Cancel") } },
    )
}

@Composable
fun SessionIcon(kind: TermKind, dimmed: Boolean = false, size: Int = 24) {
    val res = when (kind) {
        TermKind.ALPINE -> dev.droidmcp.R.drawable.ic_session_alpine
        TermKind.UBUNTU -> dev.droidmcp.R.drawable.ic_session_ubuntu
        TermKind.ANDROID -> dev.droidmcp.R.drawable.ic_session_android
        TermKind.TERMUX -> dev.droidmcp.R.drawable.ic_session_termux
    }
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), kind.label,
        Modifier.size(size.dp).then(if (dimmed) Modifier.alpha(0.4f) else Modifier))
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
                Terminals.view = java.lang.ref.WeakReference(this)
                requestFocus()
            }
        },
        onRelease = { v ->
            // Screen went away: stop sessions from redrawing (and referencing) the old view.
            if (Terminals.onScreenUpdate != null) Terminals.onScreenUpdate = null
        },
        update = { v ->
            val px = (TermFont.sp * density).toInt()
            if (v.tag != px) { v.setTextSize(px); v.tag = px }
            // Only grab focus when switching sessions, not on every recomposition.
            if (v.currentSession !== tab.session) { v.attachSession(tab.session); v.requestFocus() }
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
        // The insets controller is the reliable way to show the keyboard on Android 11+.
        if (android.os.Build.VERSION.SDK_INT >= 30) view.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
        else ctx.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
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
        Modifier.height(34.dp).clip(RoundedCornerShape(Radius.small))
            .background(if (active) MaterialTheme.colorScheme.primary else Pill)
            // Keep the keyboard attached to the terminal after a key tap.
            .clickable { onClick(); Terminals.refocus() }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimary else PillText, fontSize = 13.sp)
    }
}

@Composable
private fun SetupLinux(distro: dev.droidmcp.Distro, canCancel: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val progress = LinuxDownloads.progress[distro]
    val error = LinuxDownloads.errors[distro]
    LinuxDownloads.changed[distro]
    // Finished (here or from Settings): open the session.
    LaunchedEffect(LinuxDownloads.changed[distro]) { if (LinuxEnv(ctx, distro).installed) onDone() }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        SessionIcon(TermKind.of(distro), size = 56)
        Spacer(Modifier.height(16.dp))
        Text(distro.label, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            when (distro) {
                dev.droidmcp.Distro.ALPINE -> "A tiny, fast Linux with apk: git, python, node and thousands more packages."
                dev.droidmcp.Distro.UBUNTU -> "Full Ubuntu with apt. Runs prebuilt Linux software that Alpine can't (official Node, Bun, Python wheels, VS Code server)."
            } + " No root or Termux needed.",
            color = Color(0xFFAEAEB2), style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (progress != null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(progress, color = Color(0xFFAEAEB2), style = MaterialTheme.typography.bodySmall)
            Text("You can leave this screen; the download keeps going.", color = Color(0xFF6E6E73),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        } else {
            Button(onClick = { LinuxDownloads.start(ctx, distro) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (error != null) "Try again" else "Download · ${distro.sizeHint}")
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = StatusColors.error, style = MaterialTheme.typography.bodySmall) }
        }
        if (canCancel) TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text("Back to terminal") }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No open sessions", color = Color(0xFFAEAEB2))
        TextButton(onClick = onOpen) { Text("Open a session") }
    }
}
