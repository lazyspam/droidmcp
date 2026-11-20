package dev.droidmcp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidmcp.Prefs
import dev.droidmcp.R
import dev.droidmcp.Status

private enum class Page(val title: String) {
    CONNECTION("Connection"), ISLAND("Bubble"), LINUX("Linux"), SECURITY("Security"),
    PERMISSIONS("Permissions"), GENERAL("General"), ABOUT("About"),
}

@Composable
fun SettingsScreen(state: AppState, restart: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    BackHandler(enabled = page != null) { page = null }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        label = "settings",
    ) { p ->
        if (p == null) SettingsHome(state) { page = it }
        else SubPage(p.title, onBack = { page = null }) {
            when (p) {
                Page.CONNECTION -> ConnectionPage(state, restart)
                Page.ISLAND -> IslandSection(state)
                Page.LINUX -> LinuxPage()
                Page.SECURITY -> SecurityPage(state, restart)
                Page.PERMISSIONS -> PermissionsPage(state)
                Page.GENERAL -> GeneralPage()
                Page.ABOUT -> AboutPage(state)
            }
        }
    }
}

/** Category list. Each row shows its current state, so most questions are answered without opening anything. */
@Composable
private fun SettingsHome(state: AppState, open: (Page) -> Unit) {
    val ctx = LocalContext.current
    val ui = state.ui
    val island = Prefs.islandStyle(ctx)
    val missing = listOf(ui.a11y, ui.notif, ui.sms, ui.files, ui.overlay).count { !it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp))
        Group {
            CategoryRow({ IconTile(Icons.Outlined.Link) }, "Connection",
                (if (ui.connected) "Connected" else if (ui.running) "Not connected" else "Off") + " · ${ui.deviceId}") { open(Page.CONNECTION) }
            Divider()
            CategoryRow({ IconTile(Icons.Outlined.Terminal) }, "Linux", linuxSummary(ctx)) { open(Page.LINUX) }
            Divider()
            CategoryRow({ IconTile(Icons.Outlined.ChatBubbleOutline) }, "Bubble",
                if (!Prefs.island(ctx)) "Off" else "${if (island.position == "left") "Left" else "Right"} edge · ${(island.opacity * 100).toInt()}%") { open(Page.ISLAND) }
        }
        Group {
            CategoryRow({ IconTile(Icons.Outlined.Shield) }, "Security",
                if (Prefs.allowShell(ctx)) "Shell allowed · token" else "Shell off · token") { open(Page.SECURITY) }
            Divider()
            CategoryRow({ IconTile(Icons.Outlined.Key) }, "Permissions",
                if (ui.root) "Root: everything allowed" else if (missing == 0) "All allowed" else "$missing to allow") { open(Page.PERMISSIONS) }
        }
        Group {
            CategoryRow({ IconTile(Icons.Outlined.Tune) }, "General",
                if (Prefs.autostart(ctx)) "Starts on boot" else "Manual start") { open(Page.GENERAL) }
            Divider()
            CategoryRow({ IconTile(Icons.Outlined.Info) }, "About", "Version ${state.version}") { open(Page.ABOUT) }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(Radius.large), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical = 6.dp)) { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** Tonal tile: colours come from the theme (and the user's wallpaper), so every icon sits in one calm family. */
@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.small)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LogoTile() {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.small)).background(Color(0xFF14161C)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_logo_mark), null, Modifier.size(40.dp))
    }
}

@Composable
private fun CategoryRow(icon: @Composable () -> Unit, title: String, summary: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                content()
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column {
        if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Card(shape = RoundedCornerShape(Radius.large), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

// ---------------------------------------------------------------- pages

@Composable
private fun ConnectionPage(state: AppState, restart: () -> Unit) {
    val ctx = LocalContext.current
    var relay by remember { mutableStateOf(Prefs.relayUrl(ctx)) }
    var key by remember { mutableStateOf(Prefs.agentKey(ctx)) }
    var device by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    var localOn by remember { mutableStateOf(Prefs.localEnabled(ctx)) }
    var port by remember { mutableStateOf(Prefs.localPort(ctx).toString()) }

    val relayErr = if (relay.isNotBlank() && !relay.trim().matches(Regex("^(https?|wss?)://[^\\s/]+.*", RegexOption.IGNORE_CASE)))
        "Must start with https://" else null
    val deviceErr = if (!device.trim().matches(Regex("[A-Za-z0-9_-]{3,64}"))) "3–64 letters, digits, - or _" else null
    val portNum = port.toIntOrNull()
    val portErr = if (localOn && (portNum == null || portNum !in 1024..65535)) "1024–65535" else null
    val dirty = relay.trim() != Prefs.relayUrl(ctx) || key.trim() != Prefs.agentKey(ctx) || device.trim() != Prefs.deviceName(ctx) ||
        localOn != Prefs.localEnabled(ctx) || (localOn && portNum != Prefs.localPort(ctx))
    val valid = relayErr == null && deviceErr == null && portErr == null

    Section("Relay") {
        OutlinedTextField(relay, { relay = it }, label = { Text("Relay URL") }, singleLine = true,
            isError = relayErr != null, supportingText = { Text(relayErr ?: "Where your AI reaches this phone") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("Agent key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("Only for private relays. Leave empty for public ones.") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(device, { device = it }, label = { Text("Device ID") }, singleLine = true,
            isError = deviceErr != null, supportingText = { Text(deviceErr ?: "Part of your URL") }, modifier = Modifier.fillMaxWidth())
    }
    Section("Direct access") {
        SwitchRow("Local server", "Reach the phone on Wi-Fi, Tailscale or adb without the relay", localOn, padded = false) { localOn = it }
        if (localOn) {
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true,
                isError = portErr != null, supportingText = { portErr?.let { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        }
    }
    // Only offered when something changed: no dead buttons.
    if (dirty) {
        Button(onClick = {
            Prefs.save(ctx, relay.trim(), key.trim(), device.trim(), localOn, portNum ?: 8765, Prefs.allowShell(ctx), Prefs.autostart(ctx))
            if (Status.running) restart()
            state.refresh()
        }, enabled = valid, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (Status.running) "Save and restart" else "Save")
        }
    }
}

@Composable
private fun SecurityPage(state: AppState, restart: () -> Unit) {
    val ctx = LocalContext.current
    var shellOn by remember { mutableStateOf(Prefs.allowShell(ctx)) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Section(null) {
        SwitchRow("Allow shell commands", "Lets the AI run any command. Turn off to limit it to the safer tools.", shellOn, padded = false) {
            shellOn = it
            Prefs.save(ctx, Prefs.relayUrl(ctx), Prefs.agentKey(ctx), Prefs.deviceName(ctx), Prefs.localEnabled(ctx),
                Prefs.localPort(ctx), it, Prefs.autostart(ctx))
        }
    }
    Section("Access") {
        NavRow("New token", "Disconnects every AI client until you paste the new one") {
            confirm = "Create a new token? Every connected AI client stops working until you give it the new token." to {
                Prefs.newToken(ctx); state.refresh()
            }
        }
        HorizontalDivider()
        NavRow("New device ID", "Changes your public URL") {
            confirm = "Create a new device ID? Your URL changes, so clients need the new config." to {
                Prefs.newDeviceId(ctx); if (Status.running) restart(); state.refresh()
            }
        }
    }
    Text("Anyone with your URL and token controls this phone. Only share them with AI clients you trust.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    confirm?.let { (msg, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PermissionsPage(state: AppState) {
    val ctx = LocalContext.current
    val ui = state.ui
    if (ui.root) {
        Section(null) {
            Text("Root is granted, so every tool works. The permissions below are only needed on phones without root.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    Section(if (ui.root) "Without root" else null) {
        NavRow("Accessibility", "Screen, taps, typing · ${status(ui.a11y)}") { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        HorizontalDivider()
        NavRow("Notification access", "Read notifications · ${status(ui.notif)}") { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        HorizontalDivider()
        NavRow("SMS", "Read and send texts · ${status(ui.sms)}") {
            (ctx as? android.app.Activity)?.requestPermissions(
                arrayOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS), 2)
        }
        HorizontalDivider()
        NavRow("All files access", "Read and write storage · ${status(ui.files)}") {
            if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")))
        }
        HorizontalDivider()
        NavRow("Display over other apps", "Needed for the bubble · ${status(ui.overlay)}") {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
    }
    if (!ui.a11y) {
        Text("Accessibility switch greyed out? Open App info → ⋮ → Allow restricted settings, then try again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun GeneralPage() {
    val ctx = LocalContext.current
    var boot by remember { mutableStateOf(Prefs.autostart(ctx)) }
    var shown by remember { mutableStateOf(!Prefs.notifHidden(ctx)) }
    Section(null) {
        SwitchRow("Start on boot", "Starts the server when the phone turns on", boot, padded = false) {
            boot = it
            Prefs.save(ctx, Prefs.relayUrl(ctx), Prefs.agentKey(ctx), Prefs.deviceName(ctx), Prefs.localEnabled(ctx),
                Prefs.localPort(ctx), Prefs.allowShell(ctx), it)
        }
        HorizontalDivider()
        SwitchRow("Show notification", "You can also tap Hide on the notification. The server keeps running.", shown, padded = false) { on ->
            shown = on
            if (!dev.droidmcp.McpService.setNotificationHidden(ctx, !on)) {
                ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, dev.droidmcp.McpService.CHANNEL))
            }
        }
    }
}

@Composable
private fun AboutPage(state: AppState) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(Color(0xFF14161C)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_logo_mark), null, Modifier.size(88.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("DroidMCP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Version ${state.version}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Section(null) {
        Text("Lets AI agents use this phone over MCP: screen, input, apps, messages, files, a root shell and a full Alpine Linux.",
            style = MaterialTheme.typography.bodyMedium)
    }
    Section("DroidMCP on GitHub") {
        LinkRow("Source code", "github.com/iput-object/droidmcp", "https://github.com/iput-object/droidmcp")
        HorizontalDivider()
        LinkRow("Releases", "Download the latest APK", "https://github.com/iput-object/droidmcp/releases")
        HorizontalDivider()
        LinkRow("Report a problem", "Open an issue", "https://github.com/iput-object/droidmcp/issues")
        HorizontalDivider()
        LinkRow("License", "MIT", "https://github.com/iput-object/droidmcp/blob/main/LICENSE")
    }
    Section("Built with") {
        LinkRow("Model Context Protocol", "The protocol AI clients speak", "https://modelcontextprotocol.io")
        HorizontalDivider()
        LinkRow("Termux terminal emulator", "termux/termux-app · Apache-2.0", "https://github.com/termux/termux-app")
        HorizontalDivider()
        LinkRow("PRoot", "termux/proot · GPL-2.0", "https://github.com/termux/proot")
        HorizontalDivider()
        LinkRow("talloc", "Samba · LGPL-3.0", "https://talloc.samba.org")
        HorizontalDivider()
        LinkRow("Alpine Linux", "Downloaded on first use", "https://alpinelinux.org")
    }
    Text("Android robot © Google, used under CC BY 3.0.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
}

@Composable
private fun LinkRow(title: String, subtitle: String, url: String) {
    val ctx = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.small))
            .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }.padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

private fun linuxSummary(ctx: android.content.Context): String {
    dev.droidmcp.Distro.entries.forEach { LinuxDownloads.changed[it] }
    val def = Prefs.defaultDistro(ctx)
    val installed = dev.droidmcp.Distro.entries.filter { dev.droidmcp.LinuxEnv(ctx, it).installed }
    return "Default ${def.label.substringBefore(' ')}" + when (installed.size) {
        0 -> " · nothing downloaded"
        else -> " · ${installed.size} installed"
    }
}

/** Download, delete and choose the default distro. */
@Composable
private fun LinuxPage() {
    val ctx = LocalContext.current
    var def by remember { mutableStateOf(Prefs.defaultDistro(ctx)) }
    var confirm by remember { mutableStateOf<dev.droidmcp.Distro?>(null) }
    Text("Linux runs inside the app with proot: no root or Termux needed. The default is used by new terminals and by the AI.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    dev.droidmcp.Distro.entries.forEach { d ->
        LinuxDownloads.changed[d]
        val env = remember(LinuxDownloads.changed[d]) { dev.droidmcp.LinuxEnv(ctx, d) }
        val installed = env.installed
        var size by remember(LinuxDownloads.changed[d]) { mutableStateOf<Long?>(null) }
        androidx.compose.runtime.LaunchedEffect(LinuxDownloads.changed[d], installed) {
            if (installed) size = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { env.sizeBytes() }
        }
        val progress = LinuxDownloads.progress[d]
        Card(shape = RoundedCornerShape(Radius.large), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SessionIcon(TermKind.of(d), size = 36)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (d == def) { Spacer(Modifier.width(8.dp)); Chip("Default", StatusColors.live) }
                        }
                        Text(
                            when {
                                progress != null -> progress
                                installed -> "Installed ${env.version}" + (size?.let { " · ${formatSize(it)}" } ?: "")
                                else -> "${d.blurb} · ${d.sizeHint} download"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LinuxDownloads.errors[d]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = StatusColors.error, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        progress != null -> androidx.compose.material3.LinearProgressIndicator(Modifier.weight(1f).padding(vertical = 12.dp))
                        !installed -> Button(onClick = { LinuxDownloads.start(ctx, d) }) { Text("Download") }
                        else -> androidx.compose.material3.OutlinedButton(onClick = { confirm = d }) { Text("Delete") }
                    }
                    if (d != def && progress == null) {
                        androidx.compose.material3.FilledTonalButton(onClick = { def = d; Prefs.setDefaultDistro(ctx, d) }) { Text("Make default") }
                    }
                }
            }
        }
    }
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text("Delete ${d.label}? Everything installed in it and its /root files are removed. Open ${d.label.substringBefore(' ')} sessions close.") },
            confirmButton = { TextButton(onClick = { LinuxDownloads.uninstall(ctx, d) {}; confirm = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private fun formatSize(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${b shr 20} MB"
    else -> "${b shr 10} KB"
}

private fun status(ok: Boolean) = if (ok) "allowed" else "not allowed"

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
