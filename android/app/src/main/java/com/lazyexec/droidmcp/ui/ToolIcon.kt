package com.lazyexec.droidmcp.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lazyexec.droidmcp.AiEvent
import com.lazyexec.droidmcp.Island
import org.json.JSONObject

/** The icon drawn before a tool call's description (see [Island.icon]), with a small gap after it. */
@Composable
fun ToolIcon(@DrawableRes res: Int, size: Int) {
    Image(painterResource(res), null, Modifier.size(size.dp))
    Spacer(Modifier.width(6.dp))
}

/** This event's icon, from its tool and the arguments it was called with. */
@Composable
fun AiEvent.icon(): Int? {
    val ctx = LocalContext.current
    return remember(this) {
        // Saved arguments are pretty-printed and may be cut off: fall back to none (the default distro).
        val a = runCatching { JSONObject(args) }.getOrElse { JSONObject() }
        Island.icon(ctx, tool, a)
    }
}

/** The description to show. Entries saved before icons replaced the penguin emoji still have it in their text. */
val AiEvent.label: String get() = text.removePrefix("\uD83D\uDC27 ")
