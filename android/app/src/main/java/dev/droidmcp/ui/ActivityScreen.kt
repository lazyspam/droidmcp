package dev.droidmcp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Everything the AI did this session, newest first. Tool usage is summarised so patterns are easy to spot. */
@Composable
fun ActivityScreen(state: AppState) {
    val events = state.ui.events
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 errors
    val shown = if (filter == 1) events.filter { it.ok == false } else events
    val errors = events.count { it.ok == false }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        }
        if (events.isNotEmpty()) {
            item {
                // Summary in three numbers: easy to take in at a glance.
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Actions", events.size.toString(), Modifier.weight(1f))
                    Stat("Errors", errors.toString(), Modifier.weight(1f), if (errors > 0) StatusColors.error else null)
                    Stat("Last", ago(events.first().time), Modifier.weight(1f))
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
                    Text(if (filter == 1) "No errors. Nice." else "No activity yet",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (filter == 1) "Failed actions will show up here."
                        else "Connect an AI from Home. Every screenshot, tap and command it runs will appear here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
        } else {
            items(shown, key = { it.time.toString() + it.tool + it.text }) { EventRow(it) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    androidx.compose.material3.Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = color ?: MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
