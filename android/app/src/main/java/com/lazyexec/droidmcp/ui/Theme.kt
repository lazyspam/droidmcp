package com.lazyexec.droidmcp.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

/** Semantic status colours, used the same way everywhere (consistency builds trust). */
object StatusColors {
    val live = Color(0xFF34C759)
    val busy = Color(0xFF0A84FF)
    val warn = Color(0xFFFF9F0A)
    val error = Color(0xFFFF453A)
    val off = Color(0xFF8E8E93)
}

val Mono = FontFamily.Monospace

private val fallbackDark = darkColorScheme(
    primary = Color(0xFF7DD3A8), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F5240), onPrimaryContainer = Color(0xFFA5F2CC),
    secondary = Color(0xFFB3CCBF), background = Color(0xFF0F1512), surface = Color(0xFF0F1512),
    surfaceContainer = Color(0xFF1B211E), surfaceContainerHigh = Color(0xFF252B28),
)
private val fallbackLight = lightColorScheme(
    primary = Color(0xFF136B52), onPrimary = Color.White,
    primaryContainer = Color(0xFFA5F2CC), onPrimaryContainer = Color(0xFF002117),
)

@Composable
fun DroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Material You: follow the user's wallpaper colours when available, so the app feels like part of their phone.
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> fallbackDark
        else -> fallbackLight
    }
    MaterialTheme(colorScheme = colors, content = content)
}
