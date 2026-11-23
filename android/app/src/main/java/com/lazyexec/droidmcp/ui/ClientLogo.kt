package com.lazyexec.droidmcp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lazyexec.droidmcp.ClientLogos

/** The AI client's logo from its clientInfo name (the droid for unknown or missing clients). */
@Composable
fun ClientLogo(client: String?, size: Int, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val logo = ClientLogos.orGeneric(client)
    Image(
        painterResource(logo.res), null, Modifier.size(size.dp),
        colorFilter = if (logo.tinted) ColorFilter.tint(tint) else null,
    )
}
