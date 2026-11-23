package com.lazyexec.droidmcp.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight

private enum class Tab(val label: String) { HOME("Home"), ACTIVITY("Activity"), TERMINAL("Terminal"), SETTINGS("Settings") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DroidApp(state: AppState, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current
    var celebrated by rememberSaveable { mutableStateOf(false) }

    // Peak moment: the first time the phone goes live, celebrate it (once per app run).
    LaunchedEffect(state.ui.connected) {
        if (state.ui.connected && !celebrated) {
            celebrated = true
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            snackbar.showSnackbar("You're live. Your AI can use this phone now.")
        }
    }

    // On-phone approval while the app is open (the notification covers the rest of the time).
    com.lazyexec.droidmcp.Approvals.pending?.let { p ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            icon = { ClientLogo(p.client, 40, androidx.compose.material3.MaterialTheme.colorScheme.onSurface) },
            title = { Text("Allow ${p.client}?") },
            text = { Text("${p.client} wants to control this phone: screen, input, apps and shell. Only allow AI clients you started yourself.") },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.material3.TextButton(onClick = { com.lazyexec.droidmcp.Approvals.decide(ctx, p.key, true, always = true) }) { Text("Always") }
                    androidx.compose.material3.TextButton(onClick = { com.lazyexec.droidmcp.Approvals.decide(ctx, p.key, true) }) { Text("Allow") }
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { com.lazyexec.droidmcp.Approvals.decide(ctx, p.key, false) }) { Text("Deny") } },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // The keyboard simply covers the bar; removing it mid-animation made the layout jump.
            run {
                NavigationBar {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i; view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                            icon = {
                                when (t) {
                                    Tab.HOME -> Icon(Icons.Filled.Home, null)
                                    Tab.ACTIVITY -> androidx.compose.material3.BadgedBox(badge = {
                                        if (state.ui.events.any { it.ok == false } && tab != Tab.ACTIVITY.ordinal) androidx.compose.material3.Badge()
                                    }) { Icon(Icons.Filled.List, null) }
                                    Tab.TERMINAL -> Text(">_", fontFamily = Mono, fontWeight = FontWeight.Bold)
                                    Tab.SETTINGS -> Icon(Icons.Filled.Settings, null)
                                }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets stops the keyboard inset being added on top of the nav-bar inset (no gap above the keyboard).
        // Terminal: size to where the keyboard WILL end up, once, instead of every animation frame
        // (each resize makes the terminal re-flow and signal the shell, which looked jerky).
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .let { if (Tab.entries[tab] == Tab.TERMINAL) it.windowInsetsPadding(WindowInsets.imeAnimationTarget) else it }) {
            when (Tab.entries[tab]) {
                Tab.HOME -> HomeScreen(state, snackbar, onStart, onStop) { tab = Tab.ACTIVITY.ordinal }
                Tab.ACTIVITY -> ActivityScreen(state)
                Tab.TERMINAL -> TerminalScreen()
                Tab.SETTINGS -> SettingsScreen(state, onRestart)
            }
        }
    }
}
