package de.droh.stempeluhr.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.core.DayComparison
import de.droh.stempeluhr.core.MatchStatus
import de.droh.stempeluhr.core.Reconcile
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import java.time.YearMonth

private val green = Color(0xFF2E7D32)
private val red = Color(0xFFC62828)
private val orange = Color(0xFFE65100)

private fun statusColor(s: MatchStatus): Color = when (s) {
    MatchStatus.OK -> green
    MatchStatus.ABSENCE -> Color(0xFF1565C0)
    MatchStatus.DIFF, MatchStatus.ONLY_OWN -> red
    MatchStatus.ONLY_IMPORT, MatchStatus.OWN_INCOMPLETE -> orange
}

/** Abgleich der Firmendaten (Import) mit der eigenen Erfassung, monatsweise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReconcileScreen(
    modifier: Modifier,
    month: YearMonth?,
    onMonthChange: (YearMonth) -> Unit,
    onImport: () -> Unit,
) {
    val context = LocalContext.current
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    val (settings, settingsVersion) = rememberSettings()
    var onlyProblems by rememberSaveable { mutableStateOf(false) }

    val days = remember(events, imported, settingsVersion) {
        Summary.byDay(events, zone, settings.priority, imported, settings.evalOptions)
    }
    val months = remember(days) { Reconcile.importedMonths(days) }
    val selected = month?.takeIf { it in months } ?: months.firstOrNull()
    val comparison = remember(days, selected, settingsVersion) {
        selected?.let { Reconcile.compare(days, it, settings.reconcileToleranceMinutes) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && comparison != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(Reconcile.csv(comparison, zone).toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Abgleich gespeichert.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (selected == null || comparison == null) {
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Abgleich", style = MaterialTheme.typography.titleLarge)
            Text(
                "Noch keine Firmendaten importiert. Sobald du den Stundenzettel (PDF) eines Monats importierst, " +
                    "wird er hier Tag für Tag mit deiner eigenen Erfassung verglichen.",
            )
            Button(onClick = onImport) { Text("Zum Import") }
        }
        return
    }

    val idx = months.indexOf(selected)
    val shown = if (onlyProblems) comparison.days.filter { it.status.problem } else comparison.days

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = idx < months.lastIndex, onClick = { onMonthChange(months[idx + 1]) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Vorheriger Monat")
                }
                Text(
                    TimeFormat.month(selected.atDay(1)),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = idx > 0, onClick = { onMonthChange(months[idx - 1]) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Nächster Monat")
                }
            }
        }
        item {
            val problems = comparison.problems
            Card(
                Modifier.fillMaxWidth(),
                colors = if (problems > 0) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (problems == 0) "Alles stimmt überein" else "$problems Auffälligkeit(en)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val diff = comparison.ownTotal - comparison.importTotal
                    Text("Firma: ${TimeFormat.hm(comparison.importTotal)} h · Eigene: ${TimeFormat.hm(comparison.ownTotal)} h")
                    Text(
                        "Differenz eigene − Firma: ${TimeFormat.signedHm(diff)} h",
                        fontWeight = FontWeight.SemiBold,
                        color = if (diff > comparison.toleranceMinutes) red else MaterialTheme.colorScheme.onSurface,
                    )
                    MatchStatus.entries.forEach { s ->
                        Text(
                            "${s.label}: ${comparison.countByStatus[s] ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor(s),
                        )
                    }
                    Text(
                        "Toleranz ±${comparison.toleranceMinutes} min (änderbar unter Optionen → Import). " +
                            "Die Firmendaten enthalten nur die Dauer je Tag, keine Uhrzeiten.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !onlyProblems, onClick = { onlyProblems = false }, label = { Text("Alle Tage") })
                FilterChip(selected = onlyProblems, onClick = { onlyProblems = true }, label = { Text("Nur Auffälligkeiten") })
            }
        }
        if (shown.isEmpty()) {
            item { Text("Keine Tage in dieser Auswahl.") }
        }
        items(shown, key = { it.date.toString() }) { d -> ComparisonRow(d) }
        item {
            OutlinedButton(
                onClick = { exportLauncher.launch("Stempeluhr-Abgleich-$selected.csv") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Abgleich als CSV exportieren") }
        }
    }
}

@Composable
private fun ComparisonRow(d: DayComparison) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeFormat.weekday(d.date)} ${TimeFormat.shortDate(d.date)}",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(d.status.label, color = statusColor(d.status), fontWeight = FontWeight.SemiBold)
            }
            Row {
                Text(
                    "Firma: ${d.importMinutes?.let { "${TimeFormat.hm(it)} h" } ?: "–"}" +
                        (d.importNote?.let { " ($it)" } ?: ""),
                    Modifier.weight(1f),
                )
                Text("Eigene: ${d.ownMinutes?.let { "${TimeFormat.hm(it)} h" } ?: "–"}", Modifier.weight(1f))
            }
            if (d.kommen != null || d.gehen != null) {
                Text(
                    "Kommen ${d.kommen?.let { "${TimeFormat.time(it.ts, zone)} (${it.source.label})" } ?: "–"} · " +
                        "Gehen ${d.gehen?.let { "${TimeFormat.time(it.ts, zone)} (${it.source.label})" } ?: "–"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            d.diffMinutes?.takeIf { it != 0L }?.let {
                Text(
                    "Differenz eigene − Firma: ${TimeFormat.signedHm(it)} h",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.status == MatchStatus.DIFF) red else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
