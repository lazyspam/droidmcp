package dev.droidmcp.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.droidmcp.Island
import dev.droidmcp.Prefs

@Composable
fun IslandSection(state: AppState) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.island(ctx)) }
    var style by remember { mutableStateOf(Prefs.islandStyle(ctx)) }

    // Every change applies instantly to the real island: direct manipulation, no Save button.
    fun update(s: Prefs.IslandStyle) {
        style = s
        Prefs.setIslandStyle(ctx, s)
        if (enabled && dev.droidmcp.Status.running) Island.reattach(ctx)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Island", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp))
        Text("A small pill at the top of your screen that shows what your AI is doing, live.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp))

        PhonePreview(style, enabled)

        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                SwitchRow("Show island", "Visible while the server runs", enabled) {
                    enabled = it
                    Prefs.setIsland(ctx, it)
                    if (it && dev.droidmcp.Status.running) Island.attach(ctx) else Island.detach()
                }
                if (!state.ui.overlay && !state.ui.a11y && !state.ui.root) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Needs “Display over other apps”.", style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.warn, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Allow") }
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Label("Position")
                val positions = listOf("left" to "Left", "center" to "Center", "right" to "Right")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    positions.forEachIndexed { i, (key, name) ->
                        SegmentedButton(
                            selected = style.position == key,
                            onClick = { update(style.copy(position = key, y = -1)) },
                            shape = SegmentedButtonDefaults.itemShape(i, positions.size),
                        ) { Text(name) }
                    }
                }
                if (style.position == "custom") {
                    Text("Custom spot (dragged)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow(
                    "Lock position",
                    if (style.locked) "Touches pass through the island" else "Drag the island on your screen to place it, then lock",
                    style.locked, padded = false,
                ) { update(style.copy(locked = it)) }

                Spacer(Modifier.height(16.dp))
                Label("Size")
                val sizes = listOf("Small", "Medium", "Large")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    sizes.forEachIndexed { i, name ->
                        SegmentedButton(
                            selected = style.size == i, onClick = { update(style.copy(size = i)) },
                            shape = SegmentedButtonDefaults.itemShape(i, sizes.size),
                        ) { Text(name) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Label("Opacity · ${(style.opacity * 100).toInt()}%")
                Slider(
                    value = style.opacity, onValueChange = { style = style.copy(opacity = it) },
                    onValueChangeFinished = { update(style) }, valueRange = 0.4f..1f,
                )
                SwitchRow("Hide when idle", "Only appears while the AI is acting", style.hideWhenIdle, padded = false) {
                    update(style.copy(hideWhenIdle = it))
                }
            }
        }

        androidx.compose.material3.FilledTonalButton(onClick = { Island.preview(ctx) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Show me on screen") }
    }
}

/** A tiny phone mock that mirrors the settings, so changes are visible before looking at the real screen. */
@Composable
private fun PhonePreview(style: Prefs.IslandStyle, enabled: Boolean) {
    val scale = when (style.size) { 0 -> 0.85f; 2 -> 1.2f; else -> 1f }
    Box(
        Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF101012)).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("9:41", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("▂▄▆  100%", color = Color.White, fontSize = 11.sp)
        }
        Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(10.dp).clip(CircleShape).background(Color(0xFF2C2C2E)))
        if (enabled) {
            val align = when (style.position) {
                "left" -> Alignment.TopStart
                "right" -> Alignment.TopEnd
                else -> Alignment.TopCenter
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(align).padding(top = 26.dp).alpha(style.opacity)
                    .clip(RoundedCornerShape(50)).background(Color.Black)
                    .padding(horizontal = (10 * scale).dp, vertical = (6 * scale).dp),
            ) {
                Box(Modifier.size((8 * scale).dp).clip(CircleShape).background(StatusColors.busy))
                Spacer(Modifier.width((8 * scale).dp))
                Text("Tap 540, 1200", color = Color.White, fontSize = (12 * scale).sp)
            }
        }
        Text(
            if (style.locked) "Locked" else "Unlocked: drag it on your real screen",
            color = Color(0xFF8E8E93), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, padded: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (padded) 20.dp else 0.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
