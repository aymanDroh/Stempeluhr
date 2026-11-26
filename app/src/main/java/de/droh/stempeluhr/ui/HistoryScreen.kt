package de.droh.stempeluhr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.DaySummary
import de.droh.stempeluhr.core.MonthFold
import de.droh.stempeluhr.core.PeriodTotal
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.StampDb
import java.time.LocalDate
import java.time.YearMonth

private data class DayEntry(val summary: DaySummary, val shownEvents: List<StampEvent>) {
    /** Kommen ohne Gehen (und kein Import, der die Dauer liefert). */
    val incomplete: Boolean get() = summary.kommen != null && summary.durationMinutes == null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(modifier: Modifier) {
    val context = LocalContext.current
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    val (settings, settingsVersion) = rememberSettings()
    val messenger = LocalMessenger.current
    val priority = remember(settingsVersion) { settings.priority }
    val target = remember(settingsVersion) { settings.targetMinutes }
    val showDeleted = remember(settingsVersion) { settings.showDeleted }
    val options = remember(settingsVersion) { settings.evalOptions }
    val today = Summary.localDate(rememberNow(), zone)
    val dialogs = remember { EventDialogs() }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var onlyOpen by rememberSaveable { mutableStateOf(false) }

    val allDays = remember(events, imported, priority, showDeleted, options) {
        val byDate = events.groupBy { Summary.localDate(it.ts, zone) }
        val importByDate = imported.associateBy { it.date }
        (byDate.keys + importByDate.keys)
            .map { date ->
                val list = byDate[date].orEmpty()
                val summary = Summary.forDay(date, list, priority, importByDate[date], options)
                DayEntry(summary, if (showDeleted) list.sortedWith(compareBy({ it.ts }, { it.id })) else summary.events)
            }
            .filter { it.shownEvents.isNotEmpty() || it.summary.imported != null }
            .sortedByDescending { it.summary.date }
    }
    // „Offen“ meint Tage, an denen ein Gehen fehlt – der heutige, noch laufende Tag gehört nicht dazu.
    val openDays = remember(allDays, today) { allDays.filter { it.incomplete && it.summary.date != today } }
    val days = if (onlyOpen) openDays else allDays
    val months = remember(days) { days.groupBy { YearMonth.from(it.summary.date) } }
    // Die Monatssummen zählen immer alle Tage des Monats – auch wenn der Filter nur einzelne Tage zeigt.
    val monthTotals = remember(allDays, target) {
        allDays.groupBy { YearMonth.from(it.summary.date) }
            .mapValues { (_, list) -> MonthTotal(list.size, Summary.total(list.map { it.summary }, target)) }
    }
    val folds = remember(settingsVersion) { settings.monthFolds }
    val newestMonth = monthTotals.keys.maxOrNull()
    // Beim Filter „Gehen fehlt“ geht es um einzelne Tage – dort ist immer alles aufgeklappt.
    fun collapsed(month: YearMonth) = !onlyOpen && MonthFold.isCollapsed(month, newestMonth, folds)
    val allCollapsed = monthTotals.keys.all { MonthFold.isCollapsed(it, newestMonth, folds) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenHeader("Verlauf", TimeFormat.plural(allDays.size, "Tag", "Tage") + " erfasst") {
                    TonalButton("Nachtragen", icon = R.drawable.ic_add, compact = true, onClick = { dialogs.addingFor = today to StampType.IN })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !onlyOpen, onClick = { onlyOpen = false }, label = { ChipLabel("Alle") })
                    FilterChip(
                        selected = onlyOpen,
                        onClick = { onlyOpen = true },
                        label = { ChipLabel("Gehen fehlt (${openDays.size})") },
                    )
                    FilterChip(
                        selected = showDeleted,
                        onClick = { settings.showDeleted = !showDeleted },
                        label = { ChipLabel("Gelöschte") },
                        leadingIcon = {
                            AppIcon(if (showDeleted) R.drawable.ic_visibility else R.drawable.ic_visibility_off, size = 18.dp)
                        },
                    )
                }
                if (!onlyOpen && monthTotals.isNotEmpty()) {
                    SectionLabel(TimeFormat.plural(monthTotals.size, "Monat", "Monate")) {
                        TextButton(onClick = { settings.monthFolds = MonthFold.all(monthTotals.keys, collapsed = !allCollapsed) }) {
                            Text(if (allCollapsed) "Alle aufklappen" else "Alle zuklappen")
                        }
                    }
                }
            }
        }
        if (days.isEmpty()) {
            item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (onlyOpen) {
                        EmptyState(R.drawable.ic_task, "Nichts offen", "An keinem Tag fehlt ein Gehen.")
                    } else {
                        EmptyState(
                            R.drawable.ic_history,
                            "Noch keine Einträge",
                            "Sobald etwas erfasst oder importiert ist, erscheint es hier – nach Monaten sortiert.",
                        )
                    }
                }
            }
        }
        months.forEach { (month, monthDays) ->
            val isCollapsed = collapsed(month)
            val total = monthTotals.getValue(month)
            stickyHeader(key = "m$month") {
                MonthHeader(
                    month = month,
                    total = total.period,
                    subtitle = if (onlyOpen) {
                        "${monthDays.size} von ${TimeFormat.plural(total.dayCount, "Tag", "Tagen")} ohne Gehen"
                    } else {
                        "${TimeFormat.plural(total.period.days, "Tag", "Tage")} · Soll ${TimeFormat.hm(target.toLong())} h/Tag"
                    },
                    collapsed = isCollapsed,
                    onToggle = if (onlyOpen) null else ({ settings.monthFolds = folds + (month to !isCollapsed) }),
                )
            }
            if (isCollapsed) return@forEach
            items(monthDays, key = { it.summary.date.toString() }) { entry ->
                val key = entry.summary.date.toString()
                Box(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    DayCard(
                        entry = entry,
                        target = target,
                        isToday = entry.summary.date == today,
                        expanded = key in expanded,
                        onToggle = { expanded = if (key in expanded) expanded - key else expanded + key },
                        dialogs = dialogs,
                        onRestore = { StampDb.get(context).setDeleted(it.id, false) },
                        onDeleteImport = {
                            val removed = entry.summary.imported
                            StampDb.get(context).deleteImported(entry.summary.date)
                            if (removed != null) {
                                messenger.show("Import vom ${TimeFormat.date(removed.date)} entfernt", "Rückgängig") {
                                    StampDb.get(context).upsertImported(listOf(removed), null)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    EventDialogsHost(dialogs)
}

/** Summe eines Monats über alle seine Tage. */
private class MonthTotal(val dayCount: Int, val period: PeriodTotal)

/**
 * Monatszeile mit Stunden und Saldo. Antippen klappt die Tage des Monats auf oder zu; zugeklappt stehen die Monate
 * direkt untereinander. Beim Scrollen bleibt die Zeile oben stehen.
 */
@Composable
private fun MonthHeader(month: YearMonth, total: PeriodTotal, subtitle: String, collapsed: Boolean, onToggle: (() -> Unit)?) {
    val rotation by animateFloatAsState(if (collapsed) 0f else 180f, label = "monthChevron")
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .then(
                    if (onToggle != null) {
                        Modifier
                            .clickable(
                                onClickLabel = if (collapsed) "Tage anzeigen" else "Tage ausblenden",
                                role = Role.Button,
                                onClick = onToggle,
                            )
                            .semantics { stateDescription = if (collapsed) "zugeklappt" else "aufgeklappt" }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 16.dp, end = if (onToggle != null) 10.dp else 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val content = MaterialTheme.colorScheme.onSecondaryContainer
            Column(Modifier.weight(1f)) {
                Text(
                    TimeFormat.month(month.atDay(1)),
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = content.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${TimeFormat.hm(total.minutes)} h", style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), color = content, maxLines = 1)
                Text(
                    "Saldo ${TimeFormat.signedHm(total.saldoMinutes)}",
                    style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                    color = content,
                    maxLines = 1,
                )
            }
            if (onToggle != null) {
                AppIcon(R.drawable.ic_expand, tint = content, modifier = Modifier.padding(start = 6.dp).rotate(rotation), size = 24.dp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCard(
    entry: DayEntry,
    target: Int,
    isToday: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    dialogs: EventDialogs,
    onRestore: (StampEvent) -> Unit,
    onDeleteImport: () -> Unit,
) {
    val d = entry.summary
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val running = isToday && entry.incomplete
    AppCard(
        onClick = onToggle,
        onClickLabel = if (expanded) "Zuklappen" else "Aufklappen",
        padding = 12.dp,
        spacing = 0.dp,
        modifier = Modifier.animateContentSize().semantics { stateDescription = if (expanded) "aufgeklappt" else "zugeklappt" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateBlock(d.date, highlight = isToday)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    if (d.kommen == null && d.gehen == null) {
                        d.imported?.note?.takeIf { d.imported!!.isAbsence } ?: "Nur Import"
                    } else {
                        "${d.kommen?.let { TimeFormat.time(it.ts, zone) } ?: "–"}  →  ${d.gehen?.let { TimeFormat.time(it.ts, zone) } ?: "–"}"
                    },
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    d.kommen?.let { SourcePill(it.source) }
                    if (d.gehen != null && d.gehen.source != d.kommen?.source) SourcePill(d.gehen.source)
                    d.imported?.let {
                        Pill(if (it.isAbsence) (it.note ?: "Import") else "Import", MaterialTheme.colorScheme.primary, icon = R.drawable.ic_import)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val duration = d.durationMinutes
                when {
                    duration != null -> Text("${TimeFormat.hm(duration)} h", style = MaterialTheme.typography.titleMedium.merge(TabularNumbers))
                    running -> Pill("läuft", App.colors.good)
                    else -> Pill("Gehen fehlt", App.colors.warn)
                }
                d.saldoMinutes(target)?.let {
                    Text(
                        TimeFormat.signedHm(it),
                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                        color = if (it >= 0) App.colors.good else App.colors.bad,
                    )
                }
            }
            AppIcon(
                R.drawable.ic_expand,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp).rotate(rotation),
                size = 22.dp,
            )
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val sources = StampSource.entries.mapNotNull { src -> d.bySource[src]?.let { src to it } }
                if (sources.isNotEmpty()) {
                    Caption("Zeiten je Quelle")
                    sources.forEach { (src, t) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(100.dp)) { SourcePill(src) }
                            Text(
                                "${t.firstIn?.let { TimeFormat.time(it, zone) } ?: "–"}  →  " +
                                    (t.lastOut?.let { TimeFormat.time(it, zone) } ?: if (t.open) "offen" else "–"),
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            // Welche Zeit dieser Quelle in die Tageswertung eingeht.
                            val counts = when {
                                d.kommen?.source == src && d.gehen?.source == src -> "zählt"
                                d.kommen?.source == src -> "Kommen zählt"
                                d.gehen?.source == src -> "Gehen zählt"
                                else -> null
                            }
                            if (counts != null) {
                                AppIcon(R.drawable.ic_check, tint = App.colors.good, size = 16.dp)
                                Spacer(Modifier.width(4.dp))
                                Text(counts, style = MaterialTheme.typography.labelSmall, color = App.colors.good, maxLines = 1)
                            }
                        }
                    }
                }
                d.imported?.let { imp ->
                    InfoRow(
                        "Import (Firma)" + (imp.note?.let { " · $it" } ?: ""),
                        "${TimeFormat.hm(imp.minutes)} h" + if (d.durationFromImport) " · gewertet" else "",
                    )
                }
                if (entry.shownEvents.isNotEmpty()) {
                    Caption("Einträge – zum Bearbeiten antippen", Modifier.padding(top = 4.dp))
                    Column {
                        entry.shownEvents.forEach { ev ->
                            EventRow(event = ev, onEdit = { dialogs.editing = ev }, onRestore = { onRestore(ev) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    TonalButton("Kommen", icon = R.drawable.ic_add, onClick = { dialogs.addingFor = d.date to StampType.IN }, modifier = Modifier.weight(1f))
                    TonalButton("Gehen", icon = R.drawable.ic_add, onClick = { dialogs.addingFor = d.date to StampType.OUT }, modifier = Modifier.weight(1f))
                }
                if (d.imported != null) {
                    TextButton(onClick = onDeleteImport) {
                        AppIcon(R.drawable.ic_delete, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Import dieses Tages entfernen")
                    }
                }
            }
        }
    }
}

/** Wochentag und Tag des Monats als kleiner Block. */
@Composable
fun DateBlock(date: LocalDate, highlight: Boolean = false) {
    val container = if (highlight) MaterialTheme.colorScheme.primaryContainer else App.colors.inset
    val content = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .size(width = 46.dp, height = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .semantics(mergeDescendants = true) { contentDescription = "${TimeFormat.weekdayLong(date)}, ${TimeFormat.date(date)}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            TimeFormat.weekday(date),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) content else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), color = content)
    }
}
