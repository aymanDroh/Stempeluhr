package de.droh.stempeluhr.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.core.DaySummary
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.StampDb
import java.time.YearMonth

private data class DayEntry(val summary: DaySummary, val shownEvents: List<StampEvent>)

@Composable
fun HistoryScreen(modifier: Modifier) {
    val context = LocalContext.current
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    val (settings, settingsVersion) = rememberSettings()
    val priority = remember(settingsVersion) { settings.priority }
    val target = remember(settingsVersion) { settings.targetMinutes }
    val showDeleted = remember(settingsVersion) { settings.showDeleted }
    val options = remember(settingsVersion) { settings.evalOptions }
    val dialogs = remember { EventDialogs() }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    val days = remember(events, imported, priority, showDeleted, options) {
        val byDate = events.groupBy { Summary.localDate(it.ts, zone) }
        val importByDate = imported.associateBy { it.date }
        (byDate.keys + importByDate.keys)
            .map { date ->
                val list = byDate[date].orEmpty()
                val summary = Summary.forDay(date, list, priority, importByDate[date], options)
                DayEntry(summary, if (showDeleted) list.sortedBy { it.ts } else summary.events)
            }
            .filter { it.shownEvents.isNotEmpty() || it.summary.imported != null }
            .sortedByDescending { it.summary.date }
    }
    val months = remember(days) { days.groupBy { YearMonth.from(it.summary.date) } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gelöschte Einträge anzeigen", Modifier.weight(1f))
                Switch(checked = showDeleted, onCheckedChange = { settings.showDeleted = it })
            }
        }
        if (days.isEmpty()) {
            item { Text("Noch keine Einträge vorhanden.") }
        }
        months.forEach { (month, monthDays) ->
            item(key = "m$month") {
                val total = Summary.total(monthDays.map { it.summary }, target)
                Column(Modifier.padding(top = 8.dp)) {
                    Text(TimeFormat.month(month.atDay(1)), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${total.days} Tage mit Dauer · Summe ${TimeFormat.hm(total.minutes)} h · " +
                            "Saldo ${TimeFormat.signedHm(total.saldoMinutes)} h (Soll ${TimeFormat.hm(target.toLong())}/Tag)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(monthDays, key = { it.summary.date.toString() }) { entry ->
                val key = entry.summary.date.toString()
                DayCard(
                    entry = entry,
                    target = target,
                    expanded = key in expanded,
                    onToggle = { expanded = if (key in expanded) expanded - key else expanded + key },
                    dialogs = dialogs,
                    onRestore = { StampDb.get(context).setDeleted(it.id, false) },
                    onDeleteImport = { StampDb.get(context).deleteImported(entry.summary.date) },
                )
            }
        }
    }
    EventDialogsHost(dialogs)
}

@Composable
private fun DayCard(
    entry: DayEntry,
    target: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    dialogs: EventDialogs,
    onRestore: (StampEvent) -> Unit,
    onDeleteImport: () -> Unit,
) {
    val d = entry.summary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onToggle).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeFormat.weekday(d.date)} ${TimeFormat.shortDate(d.date)}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.25f),
                )
                Text(
                    "${d.kommen?.let { TimeFormat.time(it.ts, zone) } ?: "–"} – ${d.gehen?.let { TimeFormat.time(it.ts, zone) } ?: "–"}",
                    modifier = Modifier.weight(0.35f),
                )
                Column(Modifier.weight(0.4f), horizontalAlignment = Alignment.End) {
                    val duration = d.durationMinutes
                    Text(duration?.let { "${TimeFormat.hm(it)} h" } ?: "unvollständig", fontWeight = FontWeight.SemiBold)
                    d.saldoMinutes(target)?.let {
                        Text(TimeFormat.signedHm(it), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                listOfNotNull(
                    d.kommen?.let { "Kommen: ${it.source.label}" },
                    d.gehen?.let { "Gehen: ${it.source.label}" },
                    d.imported?.let { imp ->
                        "Import: ${TimeFormat.hm(imp.minutes)} h" +
                            (imp.note?.let { " ($it)" } ?: "") +
                            (if (d.durationFromImport) " – gewertet" else "")
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SmallLabel("Alle Quellen")
                StampSource.entries.mapNotNull { src -> d.bySource[src]?.let { src to it } }.forEach { (src, t) ->
                    Text(
                        "${src.label}: ${t.firstIn?.let { TimeFormat.time(it, zone) } ?: "–"} → " +
                            "${t.lastOut?.let { TimeFormat.time(it, zone) } ?: if (t.open) "offen" else "–"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SmallLabel("Ereignisse")
                entry.shownEvents.forEach { ev ->
                    EventRow(
                        event = ev,
                        onEdit = { dialogs.editing = ev },
                        onDelete = { dialogs.deleting = ev },
                        onRestore = { onRestore(ev) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialogs.addingFor = d.date to StampType.IN }) { Text("+ Kommen") }
                    OutlinedButton(onClick = { dialogs.addingFor = d.date to StampType.OUT }) { Text("+ Gehen") }
                }
                if (d.imported != null) {
                    TextButton(onClick = onDeleteImport) { Text("Importierten Eintrag dieses Tages entfernen") }
                }
            }
        }
    }
}
