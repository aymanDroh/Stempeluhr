package de.droh.stempeluhr.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
private fun statusColor(s: MatchStatus): Color = when (s) {
    MatchStatus.OK -> App.colors.good
    MatchStatus.ABSENCE -> App.colors.info
    MatchStatus.DIFF, MatchStatus.ONLY_OWN -> App.colors.bad
    MatchStatus.ONLY_IMPORT, MatchStatus.OWN_INCOMPLETE -> App.colors.warn
}

/** Alle Tage (eigene Erfassung + Import) – für Abgleich und Badge. */
@Composable
private fun rememberAllDaySummaries(): Pair<List<de.droh.stempeluhr.core.DaySummary>, Long> {
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    val (settings, settingsVersion) = rememberSettings()
    val days = remember(events, imported, settingsVersion) {
        Summary.byDay(events, zone, settings.priority, imported, settings.evalOptions)
    }
    return days to settingsVersion
}

/** Monate, deren Abgleich noch nicht bestätigt ist (neuester zuerst). */
@Composable
fun rememberOpenReconcileMonths(): List<YearMonth> {
    val (days, settingsVersion) = rememberAllDaySummaries()
    val settings = rememberSettings().first
    return remember(days, settingsVersion) {
        Reconcile.openMonths(days, settings.reconcileToleranceMinutes, settings.confirmedReconciles)
    }
}

/** Abgleich der Firmendaten (Import) mit der eigenen Erfassung. Bestätigte Monate verschwinden. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReconcileScreen(
    modifier: Modifier,
    month: YearMonth?,
    showConfirmed: Boolean,
    onShowConfirmedChange: (Boolean) -> Unit,
    onMonthChange: (YearMonth?) -> Unit,
    onImport: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val (days, settingsVersion) = rememberAllDaySummaries()
    val settings = rememberSettings().first
    val openMonths = rememberOpenReconcileMonths()
    val allMonths = remember(days) { Reconcile.importedMonths(days) }
    val months = if (showConfirmed) allMonths else openMonths
    val selected = month?.takeIf { it in months } ?: months.firstOrNull()
    val comparison = remember(days, selected, settingsVersion) {
        selected?.let { Reconcile.compare(days, it, settings.reconcileToleranceMinutes) }
    }
    var onlyProblems by rememberSaveable { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }

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
        Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Abgleich")
            Appear {
                if (allMonths.isEmpty()) {
                    EmptyState(
                        "Noch nichts abzugleichen",
                        "Importiere den Stundenzettel (PDF) eines Monats – er wird dann Tag für Tag mit deiner eigenen Erfassung verglichen.",
                    ) { Button(onClick = onImport) { Text("Zum Import") } }
                } else {
                    EmptyState(
                        "Alles abgeglichen",
                        "Alle importierten Monate sind bestätigt. Beim nächsten Import erscheint der Abgleich wieder.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onShowConfirmedChange(true) }) { Text("Bestätigte ansehen") }
                            Button(onClick = onDone) { Text("Fertig") }
                        }
                    }
                }
            }
        }
        return
    }

    val confirmedFp = settings.confirmedReconciles[selected]
    val fingerprint = remember(comparison) { Reconcile.fingerprint(comparison) }
    val isConfirmed = confirmedFp == fingerprint
    val shown = if (onlyProblems) comparison.days.filter { it.status.problem } else comparison.days

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Abgleich",
                if (showConfirmed) "Alle importierten Monate" else "${openMonths.size} offene(r) Monat(e) zu prüfen",
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(months, key = { it.toString() }) { m ->
                    FilterChip(
                        selected = m == selected,
                        onClick = { onMonthChange(m) },
                        label = { Text(TimeFormat.month(m.atDay(1))) },
                    )
                }
            }
        }

        item {
            AnimatedContent(
                targetState = comparison,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "summary",
            ) { c ->
                val problems = c.problems
                val diff = c.ownTotal - c.importTotal
                AppCard(container = if (problems > 0) App.colors.badContainer else App.colors.goodContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (problems == 0) "Alles stimmt" else "$problems Auffälligkeit${if (problems == 1) "" else "en"}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (problems > 0) App.colors.bad else App.colors.good,
                            )
                            Text(
                                TimeFormat.month(c.month.atDay(1)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isConfirmed) Pill("bestätigt", App.colors.good)
                    }
                    CompareBars(c.importTotal, c.ownTotal)
                    InfoRow(
                        "Differenz eigene − Firma",
                        "${TimeFormat.signedHm(diff)} h",
                        valueColor = if (kotlin.math.abs(diff) > c.toleranceMinutes) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                        bold = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MatchStatus.entries.forEach { s ->
                            Pill("${s.label}: ${c.countByStatus[s] ?: 0}", statusColor(s))
                        }
                    }
                    Text(
                        "Toleranz ±${c.toleranceMinutes} min. Die Firmendaten enthalten nur die Dauer je Tag, keine Uhrzeiten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !onlyProblems, onClick = { onlyProblems = false }, label = { Text("Alle Tage (${comparison.days.size})") })
                FilterChip(selected = onlyProblems, onClick = { onlyProblems = true }, label = { Text("Nur Auffälligkeiten (${comparison.problems})") })
            }
        }
        if (shown.isEmpty()) {
            item { Text("Keine Tage in dieser Auswahl.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(shown, key = { "${selected}_${it.date}" }) { d ->
            Box(Modifier.animateItem()) { ComparisonRow(d) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!isConfirmed) {
                    Button(
                        onClick = {
                            settings.confirmReconcile(selected, fingerprint)
                            val next = openMonths.firstOrNull { it != selected }
                            onMonthChange(next)
                            Toast.makeText(context, "${TimeFormat.month(selected.atDay(1))} bestätigt.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = App.colors.good, contentColor = Color.White),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Geprüft – Abgleich bestätigen", fontWeight = FontWeight.Bold)
                    }
                    if (!showConfirmed && openMonths.size > 1) {
                        TextButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Alle ${openMonths.size} offenen Monate bestätigen")
                        }
                    }
                } else {
                    TextButton(onClick = { settings.unconfirmReconcile(selected) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Bestätigung aufheben (wieder als offen anzeigen)")
                    }
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("Stempeluhr-Abgleich-$selected.csv") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Abgleich als CSV exportieren") }
                if (showConfirmed) {
                    TextButton(onClick = { onShowConfirmedChange(false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Nur offene Monate anzeigen")
                    }
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Alle offenen Monate bestätigen?") },
            text = {
                Text(
                    "${openMonths.size} Monate werden als geprüft markiert und verschwinden aus dem Abgleich: " +
                        openMonths.joinToString { TimeFormat.month(it.atDay(1)) } +
                        ". Ändern sich die Daten eines Monats später, erscheint er wieder.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val tol = settings.reconcileToleranceMinutes
                    settings.confirmedReconciles = settings.confirmedReconciles +
                        openMonths.associateWith { Reconcile.fingerprint(Reconcile.compare(days, it, tol)) }
                    confirmAll = false
                    onMonthChange(null)
                }) { Text("Alle bestätigen") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Abbrechen") } },
        )
    }
}

/** Zwei Balken: Firma vs. eigene Erfassung. */
@Composable
private fun CompareBars(importTotal: Long, ownTotal: Long) {
    val max = maxOf(importTotal, ownTotal, 1L).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoRow("Firma (Stundenzettel)", "${TimeFormat.hm(importTotal)} h")
        ProgressBar(importTotal / max, MaterialTheme.colorScheme.primary)
        InfoRow("Eigene Erfassung", "${TimeFormat.hm(ownTotal)} h")
        ProgressBar(ownTotal / max, App.colors.good)
    }
}

@Composable
private fun ComparisonRow(d: DayComparison) {
    val color = statusColor(d.status)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .animateContentSize(),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeFormat.weekday(d.date)} ${TimeFormat.shortDate(d.date)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Pill(d.status.label, color)
            }
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Firma", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (d.importMinutes?.let { "${TimeFormat.hm(it)} h" } ?: "–") + (d.importNote?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Eigene", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        d.ownMinutes?.let { "${TimeFormat.hm(it)} h" } ?: "–",
                        style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                d.diffMinutes?.takeIf { it != 0L }?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Diff.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            TimeFormat.signedHm(it),
                            style = MaterialTheme.typography.bodyLarge.merge(TabularNumbers),
                            fontWeight = FontWeight.Bold,
                            color = if (d.status == MatchStatus.DIFF) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            if (d.kommen != null || d.gehen != null) {
                Text(
                    "Kommen ${d.kommen?.let { "${TimeFormat.time(it.ts, zone)} (${it.source.label})" } ?: "–"}  ·  " +
                        "Gehen ${d.gehen?.let { "${TimeFormat.time(it.ts, zone)} (${it.source.label})" } ?: "–"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
