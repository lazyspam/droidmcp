package com.lazyexec.droidmcp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lazyexec.droidmcp.AiEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything about one AI action: what, when, how long, who, exact arguments and the full result. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSheet(e: AiEvent, state: AppState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val color = when (e.ok) { true -> StatusColors.live; false -> StatusColors.error; null -> StatusColors.busy }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Headline: the human description, with an unmistakable outcome.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(e.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(
                when (e.ok) { true -> "Succeeded"; false -> "Failed"; null -> "Running" },
                color = color, style = MaterialTheme.typography.labelLarge,
            )
            // Facts as a compact grid.
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.medium)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact("When", SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(e.time)))
                Fact("Duration", duration(e.ms))
                Fact("Tool", e.tool)
                if (e.client.isNotBlank()) Fact("AI client", e.client)
            }
            if (e.args.isNotBlank() && e.args != "{}") Block("Arguments", e.args) { state.copy("arguments", e.args) }
            if (e.result.isNotBlank()) Block(if (e.ok == false) "Error" else "Result", e.result) { state.copy("result", e.result) }
            if (e.args.isBlank() && e.result.isBlank()) {
                Text("Details weren't recorded for this older entry.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Block(title: String, body: String, onCopy: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onCopy) { Text("Copy") }
        }
        SelectionContainer {
            Text(
                body, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(Radius.medium))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

private fun duration(ms: Long) = when {
    ms <= 0 -> "–"
    ms < 1000 -> "$ms ms"
    ms < 60_000 -> "%.1f s".format(ms / 1000.0)
    else -> "${ms / 60000} min ${(ms % 60000) / 1000} s"
}
