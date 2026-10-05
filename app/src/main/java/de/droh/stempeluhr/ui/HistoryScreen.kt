package de.droh.stempeluhr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ScreenHeader("Verlauf", "${days.size} Tage") }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Gelöschte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = showDeleted, onCheckedChange = { settings.showDeleted = it })
                }
            }
        }
        if (days.isEmpty()) {
            item {
                EmptyState(
                    "Noch keine Einträge",
                    "Sobald etwas erfasst oder importiert ist, erscheint es hier – nach Monaten sortiert.",
                )
            }
        }
        var index = 0
        months.forEach { (month, monthDays) ->
            val i = index++
            item(key = "m$month") {
                Appear(i) { MonthHeader(month, monthDays.map { it.summary }, target) }
            }
            items(monthDays, key = { it.summary.date.toString() }) { entry ->
                val key = entry.summary.date.toString()
                Box(Modifier.animateItem()) {
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
    }
    EventDialogsHost(dialogs)
}

@Composable
private fun MonthHeader(month: YearMonth, days: List<DaySummary>, target: Int) {
    val total = Summary.total(days, target)
    AppCard(container = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(TimeFormat.month(month.atDay(1)), style = MaterialTheme.typography.titleLarge)
                Text(
                    "${total.days} Tage · Soll ${TimeFormat.hm(target.toLong())} h/Tag",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${TimeFormat.hm(total.minutes)} h",
                    style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers),
                )
                Pill(
                    "Saldo ${TimeFormat.signedHm(total.saldoMinutes)}",
                    if (total.saldoMinutes >= 0) App.colors.good else App.colors.bad,
                )
            }
        }
    }
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
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    AppCard(onClick = onToggle, padding = 14.dp, modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(TimeFormat.weekday(d.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${d.date.dayOfMonth}", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${d.kommen?.let { TimeFormat.time(it.ts, zone) } ?: "–"}  →  ${d.gehen?.let { TimeFormat.time(it.ts, zone) } ?: "–"}",
                    style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    d.kommen?.let { Pill(sourceShort(it.source), sourceColor(it.source)) }
                    if (d.gehen != null && d.gehen.source != d.kommen?.source) Pill(sourceShort(d.gehen.source), sourceColor(d.gehen.source))
                    d.imported?.let { Pill(if (it.isAbsence) (it.note ?: "Import") else "Import", MaterialTheme.colorScheme.primary) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val duration = d.durationMinutes
                Text(
                    duration?.let { "${TimeFormat.hm(it)} h" } ?: "offen",
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    color = if (duration == null) App.colors.warn else MaterialTheme.colorScheme.onSurface,
                )
                d.saldoMinutes(target)?.let {
                    Text(
                        TimeFormat.signedHm(it),
                        style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                        color = if (it >= 0) App.colors.good else App.colors.bad,
                    )
                }
            }
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation),
            )
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SmallLabel("Alle Quellen")
                StampSource.entries.mapNotNull { src -> d.bySource[src]?.let { src to it } }.forEach { (src, t) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceBadge(src, 28.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${t.firstIn?.let { TimeFormat.time(it, zone) } ?: "–"} → " +
                                (t.lastOut?.let { TimeFormat.time(it, zone) } ?: if (t.open) "offen" else "–"),
                            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                        )
                    }
                }
                d.imported?.let { imp ->
                    InfoRow(
                        "Import (Firma)" + (imp.note?.let { " · $it" } ?: ""),
                        "${TimeFormat.hm(imp.minutes)} h" + if (d.durationFromImport) " · gewertet" else "",
                    )
                }
                if (entry.shownEvents.isNotEmpty()) {
                    SmallLabel("Ereignisse")
                    entry.shownEvents.forEach { ev ->
                        EventRow(
                            event = ev,
                            onEdit = { dialogs.editing = ev },
                            onDelete = { dialogs.deleting = ev },
                            onRestore = { onRestore(ev) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialogs.addingFor = d.date to StampType.IN }) { Text("+ Kommen") }
                    OutlinedButton(onClick = { dialogs.addingFor = d.date to StampType.OUT }) { Text("+ Gehen") }
                }
                if (d.imported != null) {
                    TextButton(onClick = onDeleteImport) { Text("Import dieses Tages entfernen") }
                }
            }
        }
    }
}
