package dev.droidmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidmcp.ActivityLog
import dev.droidmcp.AiEvent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The last 30 days of AI actions, grouped by day, newest first. */
@Composable
fun ActivityScreen(state: AppState) {
    val events = state.ui.events
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 errors
    var confirmClear by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<AiEvent?>(null) }
    detail?.let { EventSheet(it, state) { detail = null } }
    val shown = if (filter == 1) events.filter { it.ok == false } else events
    val todayStart = startOfDay(System.currentTimeMillis())
    val today = events.count { it.time >= todayStart }
    val errors = events.count { it.ok == false }
    val grouped = shown.groupBy { startOfDay(it.time) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Last 30 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (events.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (events.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Today", today.toString(), Modifier.weight(1f))
                    Stat("30 days", events.size.toString(), Modifier.weight(1f))
                    Stat("Errors", errors.toString(), Modifier.weight(1f), if (errors > 0) StatusColors.error else null)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All") })
                    FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Errors · $errors") })
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("👀", fontSize = 40.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (filter == 1) "No errors. Nice." else "No activity yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (filter == 1) "Failed actions will show up here."
                        else "Connect an AI from Home. Every screenshot, tap and command it runs is kept here for 30 days.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        } else {
            grouped.forEach { (day, list) ->
                item(key = "h$day") {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 2.dp)) {
                        Text(dayLabel(day, todayStart), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        Text("${list.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { "${it.time}${it.tool}${it.text.hashCode()}" }) { e -> TimedEventRow(e) { detail = e } }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text("Delete all saved activity? This can't be undone.") },
            confirmButton = { TextButton(onClick = { ActivityLog.clear(); state.refresh(); confirmClear = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TimedEventRow(e: AiEvent, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.time)),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) { EventRow(e, onClick) }
    }
}

private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(day: Long, today: Long): String = when (day) {
    today -> "Today"
    today - 86_400_000L -> "Yesterday"
    else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(day))
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color? = null) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
