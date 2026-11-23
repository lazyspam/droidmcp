package com.lazyexec.droidmcp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazyexec.droidmcp.AiEvent
import com.lazyexec.droidmcp.Doing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Power { OFF, CONNECTING, LIVE, LOCAL, ERROR }

private fun power(ui: UiSnapshot) = when {
    ui.error != null -> Power.ERROR
    !ui.running -> Power.OFF
    ui.connected -> Power.LIVE
    ui.connecting -> Power.CONNECTING
    else -> Power.LOCAL
}

@Composable
fun HomeScreen(
    state: AppState, snackbar: SnackbarHostState,
    onStart: () -> Unit, onStop: () -> Unit, onSeeActivity: () -> Unit,
) {
    val ui = state.ui
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val p = power(ui)
    var detail by remember { mutableStateOf<AiEvent?>(null) }
    detail?.let { EventSheet(it, state) { detail = null } }

    fun toggle() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (!ui.running) { onStart(); return }
        // Undo instead of a confirmation dialog: one tap to act, easy to reverse.
        onStop()
        scope.launch {
            val r = snackbar.showSnackbar("Server stopped", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onStart()
        }
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(ui) }
        item { PowerHero(p, ui, ::toggle) }
        item {
            val (now, last) = steadyNow(ui.now)
            AnimatedVisibility(now != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                NowCard(now ?: last)
            }
        }
        if (ui.running) item { Stats(ui) }
        if (ui.crash != null) item { CrashCard(ui.crash) }
        if (ui.running) item { ConnectCard(state) }
        item { SetupCard(ui) }
        if (ui.events.isNotEmpty()) {
            item { SectionHeader("Recent", if (ui.events.size > 3) "See all" else null, onSeeActivity) }
            itemsIndexed(ui.events.take(3), key = { i, e -> "${e.time}-$i" }) { _, e -> EventRow(e) { detail = e } }
        }
    }
}

/**
 * Debounced "current action": quick actions don't flash the card; once shown it stays at least a second.
 * Returns (action to show or null, last action shown), the latter for the card's exit animation.
 */
@Composable
private fun steadyNow(raw: Doing?): Pair<Doing?, Doing> {
    var shown by remember { mutableStateOf<Doing?>(null) }
    var last by remember { mutableStateOf(Doing("")) }
    var since by remember { mutableLongStateOf(0L) }
    LaunchedEffect(raw) {
        if (raw != null) {
            if (shown == null) delay(400)
            shown = raw; last = raw; since = System.currentTimeMillis()
        } else if (shown != null) {
            val left = 1000 - (System.currentTimeMillis() - since)
            if (left > 0) delay(left)
            shown = null
        }
    }
    return shown to last
}

@Composable
private fun Header(ui: UiSnapshot) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF14161C)), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.lazyexec.droidmcp.R.drawable.ic_logo_mark), "DroidMCP",
                Modifier.size(42.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("DroidMCP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(ui.model, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (ui.running) Chip(if (ui.root) "Root" else "No root", if (ui.root) StatusColors.live else StatusColors.warn)
    }
}

/** The one big control: a VPN-style power button. Familiar pattern, huge tap target, state you can read from across the room. */
@Composable
private fun PowerHero(p: Power, ui: UiSnapshot, onToggle: () -> Unit) {
    val color by animateColorAsState(
        when (p) {
            Power.OFF -> StatusColors.off
            Power.CONNECTING -> StatusColors.warn
            Power.LIVE, Power.LOCAL -> StatusColors.live
            Power.ERROR -> StatusColors.error
        }, tween(500), label = "power",
    )
    val (title, subtitle) = when (p) {
        Power.OFF -> "Off" to "Tap to let your AI use this phone"
        Power.CONNECTING -> "Connecting…" to ui.relay.substringBefore(". Retrying").replaceFirstChar { it.uppercase() }
        Power.LIVE -> "Live" to "Your AI can reach this phone from anywhere"
        Power.LOCAL -> "On" to "Reachable on your local network"
        Power.ERROR -> "Problem" to (ui.error ?: "")
    }
    Card(
        shape = RoundedCornerShape(Radius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PowerButton(color, p, onToggle)
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

@Composable
private fun PowerButton(color: Color, p: Power, onClick: () -> Unit) {
    val on = p == Power.LIVE || p == Power.LOCAL
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(168.dp)) {
        // Soft halo while live: calm, "breathing" motion signals it's working without demanding attention.
        if (on) Halo(color)
        if (p == Power.CONNECTING) Spinner(color)
        else Canvas(Modifier.size(132.dp)) {
            drawArc(color.copy(alpha = if (on) 1f else 0.35f), 0f, 360f, false, style = Stroke(7.dp.toPx()))
        }
        Box(
            Modifier.size(108.dp).clip(CircleShape)
                .background(if (on) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple(), onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            PowerGlyph(if (on || p == Power.CONNECTING) color else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Animated values are read inside graphicsLayer/draw lambdas, so they redraw without recomposing. */
@Composable
private fun Halo(color: Color) {
    val t = rememberInfiniteTransition(label = "halo")
    val glow = t.animateFloat(0.85f, 1.12f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")
    Box(Modifier.size(150.dp).graphicsLayer { scaleX = glow.value; scaleY = glow.value; alpha = 0.22f }
        .clip(CircleShape).background(Brush.radialGradient(listOf(color, Color.Transparent))))
}

@Composable
private fun Spinner(color: Color) {
    val t = rememberInfiniteTransition(label = "spin")
    val spin = t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    Canvas(Modifier.size(132.dp).graphicsLayer { rotationZ = spin.value }) {
        val stroke = 7.dp.toPx()
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(stroke))
        drawArc(color, 0f, 90f, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** The universal ⏻ symbol, drawn so it needs no icon font. */
@Composable
private fun PowerGlyph(color: Color) {
    Canvas(Modifier.size(40.dp)) {
        val w = 4.5.dp.toPx()
        drawArc(color, -50f, 280f, false, style = Stroke(w, cap = StrokeCap.Round),
            topLeft = androidx.compose.ui.geometry.Offset(w, w + 2.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(size.width - 2 * w, size.height - 2 * w))
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
            androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.45f), w, StrokeCap.Round)
    }
}

@Composable
private fun NowCard(now: Doing) {
    Card(
        shape = RoundedCornerShape(Radius.large),
        colors = CardDefaults.cardColors(containerColor = StatusColors.busy.copy(alpha = 0.14f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), color = StatusColors.busy, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("AI is working", style = MaterialTheme.typography.labelMedium, color = StatusColors.busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    now.icon?.let { ToolIcon(it, 18) }
                    Text(now.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Stats(ui: UiSnapshot) {
    val errors = ui.events.count { it.ok == false }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Uptime", uptime(ui.startedAt), Modifier.weight(1f))
        StatTile("Actions", ui.events.size.toString(), Modifier.weight(1f))
        StatTile("Errors", errors.toString(), Modifier.weight(1f), if (errors > 0) StatusColors.error else null)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Column(modifier.clip(RoundedCornerShape(Radius.large)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun uptime(start: Long): String {
    if (start == 0L) return "–"
    val m = (System.currentTimeMillis() - start) / 60000
    return when {
        m < 1 -> "<1m"
        m < 60 -> "${m}m"
        m < 1440 -> "${m / 60}h ${m % 60}m"
        else -> "${m / 1440}d ${(m % 1440) / 60}h"
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ConnectCard(state: AppState) {
    val ui = state.ui
    var more by rememberSaveable { mutableStateOf(false) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(Radius.large), modifier = Modifier.fillMaxWidth()) {
        // Less padding at the bottom: the "More options" button's 48dp touch target already
        // leaves space under its label, so the gap matches the one above the title.
        Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 10.dp)) {
            Text("Connect your AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!com.lazyexec.droidmcp.Prefs.requireToken(LocalContext.current)) {
                Text("Token is off: anyone with this URL can control your phone.", style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.warn, modifier = Modifier.padding(vertical = 4.dp))
            }
            Text(ui.mcpUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Button(onClick = { state.copy("mcp config", state.clientJson()) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Copy MCP config")
            }
            TextButton(onClick = { more = !more }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (more) "Fewer options" else "More options")
            }
            AnimatedVisibility(more) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                    CopyRow("Claude Code command", "claude mcp add …") { state.copy("claude command", state.claudeCommand()) }
                    CopyRow("URL", ui.mcpUrl) { state.copy("url", ui.mcpUrl) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            CopyRow("Token", if (reveal) ui.token else "•".repeat(16)) { state.copy("token", ui.token) }
                        }
                        TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("The token gives full control of this phone. Keep it private.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Copy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private data class Step(val title: String, val why: String, val done: Boolean, val open: (Context) -> Unit)

@Composable
private fun SetupCard(ui: UiSnapshot) {
    val ctx = LocalContext.current
    if (ui.root) return // Root covers everything; the header chip already says so.
    val steps = listOf(
        Step("Screen control", "See the screen, tap, swipe and type", ui.a11y) {
            it.startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Step("Notifications", "Read your notifications", ui.notif) {
            it.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        Step("SMS", "Read and send text messages", ui.sms) { c ->
            (c as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS), 2)
        },
        Step("Files", "Read and write your storage", ui.files) { c ->
            if (Build.VERSION.SDK_INT >= 30) c.startActivitySafely(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${c.packageName}")))
        },
        Step("Bubble", "Show what the AI is doing", ui.overlay || ui.a11y) { c ->
            c.startActivitySafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
        },
    )
    val done = steps.count { it.done }
    if (done == steps.size) return
    Card(shape = RoundedCornerShape(Radius.large), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("$done of ${steps.size} done · no root needed",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            // Goal-gradient: the bar never starts empty, so the first step feels already underway.
            LinearProgressIndicator(progress = { (done + 0.5f) / (steps.size + 0.5f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)))
            Spacer(Modifier.height(8.dp))
            steps.sortedBy { it.done }.forEach { s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium))
                        .clickable(enabled = !s.done) { s.open(ctx) }.padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Box(Modifier.size(26.dp).clip(CircleShape)
                        .background(if (s.done) StatusColors.live else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center) { if (s.done) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge,
                            color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(s.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!s.done) Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!ui.a11y) {
                Text("Switch greyed out? App info → ⋮ → Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EventRow(e: AiEvent, onClick: (() -> Unit)? = null) {
    val color = when (e.ok) { true -> StatusColors.live; false -> StatusColors.error; null -> StatusColors.busy }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        e.icon()?.let { ToolIcon(it, 16) }
        Text(e.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(ago(e.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun ago(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}

/** Kept for other screens that show a pulsing status dot. */
@Composable
fun PulseDot(color: Color, pulsing: Boolean, size: Int = 18) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s = t.animateFloat(1f, if (pulsing) 1.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size((size * 1.6).dp)) {
        Box(Modifier.size(size.dp).graphicsLayer { scaleX = s.value; scaleY = s.value; alpha = 0.35f }.clip(CircleShape).background(color))
        Box(Modifier.size((size * 0.62).dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun CrashCard(text: String) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(Radius.large), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("The app crashed last time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text.lines().getOrNull(1)?.take(90) ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2, fontSize = 11.sp)
            }
            TextButton(onClick = { com.lazyexec.droidmcp.App.crashFile(ctx).delete() }) { Text("Dismiss") }
        }
    }
}
