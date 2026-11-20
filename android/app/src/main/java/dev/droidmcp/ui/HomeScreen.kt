package dev.droidmcp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidmcp.AiEvent

@Composable
fun HomeScreen(state: AppState, onStart: () -> Unit, onStop: () -> Unit, onSeeActivity: () -> Unit) {
    val ui = state.ui
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { StatusHero(ui) }
        item {
            // Primary action: biggest target on the screen, in the thumb zone. Start is the accent;
            // Stop is deliberately quieter so it isn't hit by accident.
            if (ui.running) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Stop server") }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text("Start", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (ui.crash != null) item { CrashCard(ui.crash) }
        if (ui.running) item { ConnectCard(state) }
        item { SetupCard(ui) }
        item { ActivityHeader(ui.events.size, onSeeActivity) }
        if (ui.events.isEmpty()) {
            item {
                Text(
                    "Nothing yet. When your AI connects, every action it takes shows up here, and in the island at the top of your screen.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            items(ui.events.take(3), key = { it.time.toString() + it.tool }) { EventRow(it) }
        }
    }
}

@Composable
private fun StatusHero(ui: UiSnapshot) {
    val (color, title, subtitle) = when {
        ui.error != null -> Triple(StatusColors.error, "Problem", ui.error)
        ui.connected -> Triple(StatusColors.live, "Live", "Your AI can reach this phone from anywhere.")
        ui.connecting -> Triple(StatusColors.warn, "Connecting…", ui.relay.replaceFirstChar { it.uppercase() })
        ui.running -> Triple(StatusColors.live, "Running", "Reachable on your local network.")
        else -> Triple(StatusColors.off, "Off", "Start the server to let your AI use this phone.")
    }
    val animated by animateColorAsState(color, tween(400), label = "status")
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(animated, pulsing = ui.running)
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ui.running) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (ui.root) "Root" else "No root", if (ui.root) StatusColors.live else StatusColors.warn)
                    Chip("Relay", if (ui.connected) StatusColors.live else if (ui.connecting) StatusColors.warn else StatusColors.off)
                    Chip("Local", if (ui.local.startsWith("listening")) StatusColors.live else StatusColors.off)
                }
            }
        }
    }
}

@Composable
fun PulseDot(color: Color, pulsing: Boolean, size: Int = 18) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, if (pulsing) 1.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")
    val a by t.animateFloat(1f, if (pulsing) 0.55f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size((size * 1.6).dp)) {
        Box(Modifier.size(size.dp).scale(s).alpha(a * 0.35f).clip(CircleShape).background(color))
        Box(Modifier.size((size * 0.62).dp).clip(CircleShape).background(color))
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
private fun ConnectCard(state: AppState) {
    val ui = state.ui
    var reveal by rememberSaveable { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Connect your AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Paste this into Claude Code, Cursor or any MCP client.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            // One accent button (the thing most people need); alternatives are tonal.
            Button(onClick = { state.copy("mcp config", state.clientJson()) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Copy MCP config")
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = { state.copy("claude command", state.claudeCommand()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Copy Claude Code command")
            }
            Spacer(Modifier.height(14.dp))
            LabeledValue("URL", ui.publicUrl ?: "Local only (no relay set)") { ui.publicUrl?.let { state.copy("url", it) } }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    LabeledValue("Token", if (reveal) ui.token else "•".repeat(16) + ui.token.takeLast(4)) { state.copy("token", ui.token) }
                }
                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("Anyone with this token can control your phone. Keep it private.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class Step(val title: String, val why: String, val done: Boolean, val open: (Context) -> Unit)

@Composable
private fun SetupCard(ui: UiSnapshot) {
    val ctx = LocalContext.current
    if (ui.root) {
        // Root covers everything: one reassuring row instead of a checklist.
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(StatusColors.live.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = StatusColors.live)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Everything unlocked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Root access covers all tools. No extra permissions needed.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    val steps = listOf(
        Step("Screen control", "See the screen, tap, swipe and type", ui.a11y) {
            it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        Step("Notifications", "Read your notifications", ui.notif) {
            it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        Step("SMS", "Read and send text messages", ui.sms) { c ->
            (c as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS), 2)
        },
        Step("Files", "Read and write your storage", ui.files) { c ->
            if (Build.VERSION.SDK_INT >= 30) c.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${c.packageName}")))
        },
        Step("Island", "Show what the AI is doing", ui.overlay || ui.a11y) { c ->
            c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
        },
    )
    val done = steps.count { it.done }
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Column(Modifier.weight(1f)) {
                    Text(if (done == steps.size) "Setup complete" else "Finish setup",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("$done of ${steps.size} done · no root needed",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Goal-gradient: never show an empty bar, so the first step feels already started.
            LinearProgressIndicator(
                progress = { (done + 0.5f) / (steps.size + 0.5f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            )
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    // Unfinished first: the next action is always at the top.
                    steps.sortedBy { it.done }.forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable(enabled = !s.done) { s.open(ctx) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Box(
                                Modifier.size(26.dp).clip(CircleShape)
                                    .background(if (s.done) StatusColors.live else MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { if (s.done) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Color.White) }
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
                        Text("Switch greyed out? Open App info → ⋮ → Allow restricted settings, then try again.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader(count: Int, onSeeAll: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp)) {
        Text("Latest from your AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (count > 3) TextButton(onClick = onSeeAll) { Text("See all $count") }
    }
}

@Composable
fun EventRow(e: AiEvent) {
    val color = when (e.ok) { true -> StatusColors.live; false -> StatusColors.error; null -> StatusColors.busy }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(e.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
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

@Composable
private fun CrashCard(text: String) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("The app crashed last time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text.lines().take(4).joinToString("\n"), fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 6)
            TextButton(onClick = { dev.droidmcp.App.crashFile(ctx).delete() }) { Text("Dismiss") }
        }
    }
}
