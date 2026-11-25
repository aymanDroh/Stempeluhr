package de.droh.stempeluhr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.DayComparison
import de.droh.stempeluhr.core.DaySummary
import de.droh.stempeluhr.core.MatchStatus
import de.droh.stempeluhr.core.Reconcile
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import java.time.YearMonth
import kotlin.math.abs

@Composable
private fun statusColor(s: MatchStatus): Color = when (s) {
    MatchStatus.OK -> App.colors.good
    MatchStatus.ABSENCE -> App.colors.info
    MatchStatus.DIFF, MatchStatus.ONLY_OWN -> App.colors.bad
    MatchStatus.ONLY_IMPORT, MatchStatus.OWN_INCOMPLETE -> App.colors.warn
}

private fun statusIcon(s: MatchStatus): Int = when (s) {
    MatchStatus.OK -> R.drawable.ic_check_circle
    MatchStatus.ABSENCE -> R.drawable.ic_event_busy
    MatchStatus.DIFF, MatchStatus.ONLY_OWN -> R.drawable.ic_error
    MatchStatus.ONLY_IMPORT, MatchStatus.OWN_INCOMPLETE -> R.drawable.ic_warning
}

/** Alle Tage (eigene Erfassung + Import) – für Abgleich und Badge. */
@Composable
private fun rememberAllDaySummaries(): Pair<List<DaySummary>, Long> {
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
@OptIn(ExperimentalLayoutApi::class)
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
    val messenger = LocalMessenger.current
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
                messenger.show("Abgleich gespeichert")
            } catch (e: Exception) {
                messenger.show("Export fehlgeschlagen: ${e.message}", long = true)
            }
        }
    }

    if (selected == null || comparison == null) {
        Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScreenHeader("Abgleich", "Stundenzettel der Firma gegen eigene Erfassung")
            Appear {
                if (allMonths.isEmpty()) {
                    EmptyState(
                        R.drawable.ic_reconcile,
                        "Noch nichts abzugleichen",
                        "Importiere den Stundenzettel (PDF) eines Monats – er wird dann Tag für Tag mit deiner eigenen Erfassung verglichen.",
                    ) { PrimaryButton("Stundenzettel importieren", icon = R.drawable.ic_upload, onClick = onImport) }
                } else {
                    EmptyState(
                        R.drawable.ic_task,
                        "Alles abgeglichen",
                        "Alle importierten Monate sind bestätigt. Beim nächsten Import erscheint der Abgleich wieder.",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuietButton("Bestätigte ansehen", onClick = { onShowConfirmedChange(true) })
                            PrimaryButton("Fertig", onClick = onDone)
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                "Abgleich",
                if (showConfirmed) {
                    "Alle importierten Monate"
                } else {
                    "${TimeFormat.plural(openMonths.size, "offener Monat", "offene Monate")} zu prüfen"
                },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(months, key = { it.toString() }) { m ->
                    FilterChip(
                        selected = m == selected,
                        onClick = { onMonthChange(m) },
                        label = { ChipLabel(TimeFormat.month(m.atDay(1))) },
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
                val tone = if (problems > 0) App.colors.bad else App.colors.good
                AppCard(spacing = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(if (problems > 0) R.drawable.ic_error_fill else R.drawable.ic_check_circle_fill, tone, size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (problems == 0) "Alles stimmt" else TimeFormat.plural(problems, "Auffälligkeit", "Auffälligkeiten"),
                                style = MaterialTheme.typography.headlineSmall,
                                color = tone,
                            )
                            Text(
                                TimeFormat.month(c.month.atDay(1)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isConfirmed) Pill("bestätigt", App.colors.good, icon = R.drawable.ic_check)
                    }
                    CompareBars(c.importTotal, c.ownTotal)
                    InfoRow(
                        "Differenz eigene − Firma",
                        "${TimeFormat.signedHm(diff)} h",
                        valueColor = if (problems > 0 && abs(diff) > c.toleranceMinutes) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                        bold = true,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MatchStatus.entries.forEach { s ->
                            val count = c.countByStatus[s] ?: 0
                            // Kategorien ohne Treffer bleiben sichtbar, treten aber zurück.
                            Pill(
                                "${s.label}: $count",
                                if (count > 0) statusColor(s) else MaterialTheme.colorScheme.onSurfaceVariant,
                                container = if (count > 0) statusColor(s).copy(alpha = 0.12f) else App.colors.inset,
                            )
                        }
                    }
                    Hint("Toleranz ±${c.toleranceMinutes} min. Die Firmendaten enthalten nur die Dauer je Tag, keine Uhrzeiten.")
                }
            }
        }

        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !onlyProblems, onClick = { onlyProblems = false }, label = { ChipLabel("Alle Tage (${comparison.days.size})") })
                FilterChip(selected = onlyProblems, onClick = { onlyProblems = true }, label = { ChipLabel("Auffällige (${comparison.problems})") })
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    "Keine Tage in dieser Auswahl.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        items(shown, key = { "${selected}_${it.date}" }) { d ->
            Box(Modifier.animateItem()) { ComparisonRow(d) }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!isConfirmed) {
                    PrimaryButton(
                        "Geprüft – Abgleich bestätigen",
                        icon = R.drawable.ic_check,
                        container = App.colors.good,
                        content = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            settings.confirmReconcile(selected, fingerprint)
                            val next = openMonths.firstOrNull { it != selected }
                            onMonthChange(next)
                            messenger.show("${TimeFormat.month(selected.atDay(1))} bestätigt", "Rückgängig") {
                                settings.unconfirmReconcile(selected)
                            }
                        },
                    )
                    if (!showConfirmed && openMonths.size > 1) {
                        TextButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Alle ${openMonths.size} offenen Monate bestätigen")
                        }
                    }
                } else {
                    QuietButton(
                        "Bestätigung aufheben",
                        icon = R.drawable.ic_undo,
                        onClick = { settings.unconfirmReconcile(selected) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                QuietButton(
                    "Abgleich als CSV exportieren",
                    icon = R.drawable.ic_download,
                    onClick = { exportLauncher.launch("Stempeluhr-Abgleich-$selected.csv") },
                    modifier = Modifier.fillMaxWidth(),
                )
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
        Spacer(Modifier.padding(top = 2.dp))
        InfoRow("Eigene Erfassung", "${TimeFormat.hm(ownTotal)} h")
        ProgressBar(ownTotal / max, App.colors.good)
    }
}

@Composable
private fun ComparisonRow(d: DayComparison) {
    val color = statusColor(d.status)
    AppCard(padding = 12.dp, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateBlock(d.date)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Caption("Firma")
                Text(
                    d.importMinutes?.let { "${TimeFormat.hm(it)} h" } ?: "–",
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                )
            }
            Column(Modifier.weight(1f)) {
                Caption("Eigene")
                Text(
                    d.ownMinutes?.let { "${TimeFormat.hm(it)} h" } ?: "–",
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                )
            }
            Column(Modifier.weight(0.9f), horizontalAlignment = Alignment.End) {
                Caption("Differenz")
                Text(
                    d.diffMinutes?.let { TimeFormat.signedHm(it) } ?: "–",
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    color = if (d.status == MatchStatus.DIFF) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(d.status.label, color, icon = statusIcon(d.status))
            Spacer(Modifier.width(8.dp))
            val detail = buildList {
                d.importNote?.let { add(it) }
                if (d.kommen != null || d.gehen != null) {
                    add(
                        "${d.kommen?.let { TimeFormat.time(it.ts, zone) } ?: "–"} → ${d.gehen?.let { TimeFormat.time(it.ts, zone) } ?: "–"}" +
                            (d.kommen ?: d.gehen)?.let { " (${sourceName(it.source)})" }.orEmpty(),
                    )
                }
            }.joinToString(" · ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
