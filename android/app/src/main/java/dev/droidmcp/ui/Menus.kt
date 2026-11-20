package dev.droidmcp.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** One rounding scale for the whole app, so every surface feels like the same family. */
object Radius {
    val small = 10.dp   // keys, chips
    val medium = 16.dp  // menus, list rows, inputs
    val large = 24.dp   // cards
    val xl = 32.dp      // hero surfaces
}

/** The app's menu: rounded, offset slightly below its anchor, consistent surface and padding. */
@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 6.dp),
        shape = RoundedCornerShape(Radius.medium),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        content = content,
    )
}

@Composable
fun AppMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = text, onClick = onClick, leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled,
        modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    )
}
