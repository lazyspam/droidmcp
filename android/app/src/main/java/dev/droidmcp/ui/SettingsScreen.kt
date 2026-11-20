package dev.droidmcp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidmcp.Prefs
import dev.droidmcp.Status

@Composable
fun SettingsScreen(state: AppState, restart: () -> Unit) {
    val ctx = LocalContext.current
    var relay by remember { mutableStateOf(Prefs.relayUrl(ctx)) }
    var key by remember { mutableStateOf(Prefs.agentKey(ctx)) }
    var device by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    var localOn by remember { mutableStateOf(Prefs.localEnabled(ctx)) }
    var port by remember { mutableStateOf(Prefs.localPort(ctx).toString()) }
    var shellOn by remember { mutableStateOf(Prefs.allowShell(ctx)) }
    var boot by remember { mutableStateOf(Prefs.autostart(ctx)) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // Inline validation: show problems next to the field, as you type.
    val relayErr = if (relay.isNotBlank() && !relay.trim().matches(Regex("^(https?|wss?)://[^\\s/]+.*", RegexOption.IGNORE_CASE)))
        "Must start with https://" else null
    val deviceErr = if (!device.trim().matches(Regex("[A-Za-z0-9_-]{3,64}"))) "3–64 letters, digits, - or _" else null
    val portNum = port.toIntOrNull()
    val portErr = if (localOn && (portNum == null || portNum !in 1024..65535)) "1024–65535" else null
    val dirty = relay.trim() != Prefs.relayUrl(ctx) || key.trim() != Prefs.agentKey(ctx) || device.trim() != Prefs.deviceName(ctx) ||
        localOn != Prefs.localEnabled(ctx) || portNum != Prefs.localPort(ctx) || shellOn != Prefs.allowShell(ctx) || boot != Prefs.autostart(ctx)
    val valid = relayErr == null && deviceErr == null && portErr == null

    fun save() {
        Prefs.save(ctx, relay.trim(), key.trim(), device.trim(), localOn, portNum ?: 8765, shellOn, boot)
        if (Status.running) restart()
        state.refresh()
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp))

        Section("Connection") {
            OutlinedTextField(relay, { relay = it }, label = { Text("Relay URL") }, singleLine = true,
                isError = relayErr != null, supportingText = { Text(relayErr ?: "Where your AI reaches this phone") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text("Agent key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = { Text("Only for private relays. Leave empty for public ones.") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(device, { device = it }, label = { Text("Device ID") }, singleLine = true,
                isError = deviceErr != null, supportingText = { Text(deviceErr ?: "Part of your URL") }, modifier = Modifier.fillMaxWidth())
        }

        IslandSection(state)

        Section("Direct access") {
            SwitchRow("Local server", "Reach the phone on Wi-Fi, Tailscale or adb without the relay", localOn, padded = false) { localOn = it }
            if (localOn) {
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                    isError = portErr != null, supportingText = { portErr?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        }

        Section("Security") {
            SwitchRow("Allow shell commands", "Lets the AI run any command. Turn off to limit it to the safer tools.", shellOn, padded = false) { shellOn = it }
            HorizontalDivider()
            NavRow("New token", "Disconnects every AI client until you paste the new one") {
                confirm = "Create a new token? Every connected AI client stops working until you give it the new token." to {
                    Prefs.newToken(ctx); state.refresh()
                }
            }
            NavRow("New device ID", "Changes your public URL") {
                confirm = "Create a new device ID? Your URL changes, so clients need the new config." to {
                    device = Prefs.newDeviceId(ctx); if (Status.running) restart(); state.refresh()
                }
            }
        }

        Section("Without root") {
            Text("Root unlocks everything. Without it, allow these instead.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            NavRow("Accessibility", status(state.ui.a11y)) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            NavRow("Notification access", status(state.ui.notif)) { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            NavRow("All files access", status(state.ui.files)) {
                if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")))
            }
            NavRow("Display over other apps", status(state.ui.overlay)) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
        }

        Section("General") {
            SwitchRow("Start on boot", "Starts the server when the phone turns on", boot, padded = false) { boot = it }
        }

        Text("DroidMCP ${state.version}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(72.dp))
    }

    // The save button only appears when there is something to save.
    if (dirty) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
            Spacer(Modifier.weight(1f))
            Button(onClick = ::save, enabled = valid, modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp)) {
                Text(if (Status.running) "Save and restart" else "Save")
            }
        }
    }

    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun status(ok: Boolean) = if (ok) "Allowed" else "Not allowed"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
