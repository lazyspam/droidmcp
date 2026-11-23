package com.lazyexec.droidmcp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lazyexec.droidmcp.ClientLogos
import kotlinx.coroutines.launch

/** The AI client's logo from its clientInfo name (the droid for unknown or missing clients). */
@Composable
fun ClientLogo(client: String?, size: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val logo = ClientLogos.orGeneric(client)
    Image(
        painterResource(logo.res), null, Modifier.size(size.dp),
        colorFilter = if (logo.tinted) ColorFilter.tint(tint) else null,
    )
}

/** [content] (the client's logo and/or name) that shows the raw clientInfo ("claude-code 2.5.0") in a tooltip when tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientTooltip(client: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(client?.takeIf { it.isNotBlank() } ?: "No client name was sent") } },
        state = tip,
        modifier = modifier,
    ) {
        Box(Modifier.clickable { scope.launch { tip.show() } }) { content() }
    }
}
