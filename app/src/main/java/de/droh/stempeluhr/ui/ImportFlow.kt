package de.droh.stempeluhr.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.Backup
import de.droh.stempeluhr.core.BackupData
import de.droh.stempeluhr.core.BackupFormatException
import de.droh.stempeluhr.core.EventKey
import de.droh.stempeluhr.core.ImportResult
import de.droh.stempeluhr.core.RestorePlan
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.BackupStore
import de.droh.stempeluhr.data.ImportReader
import de.droh.stempeluhr.data.ImportedFile
import de.droh.stempeluhr.data.RestoreResult
import de.droh.stempeluhr.data.StampDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** Was eine gewählte Datei bewirken würde – wird vor dem Speichern angezeigt. */
sealed interface ImportPreview {
    val fileName: String

    /** Sicherung eines anderen Handys. */
    class BackupFile(override val fileName: String, val data: BackupData, val plan: RestorePlan) : ImportPreview

    /** Stundenzettel (PDF/CSV) oder Rohdaten-Export (CSV). */
    class Times(override val fileName: String, val result: ImportResult, val replaced: Int, val existingRaw: Int) : ImportPreview
}

/**
 * Liest eine Datei ein – egal ob sie über die Dateiauswahl, über „Öffnen mit“ oder über „Teilen“ kommt –
 * und erkennt selbst, ob es eine Sicherung, ein Stundenzettel oder ein Rohdaten-Export ist.
 */
class ImportController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val messenger: Messenger,
) {
    var busy by mutableStateOf(false)
        private set
    var preview by mutableStateOf<ImportPreview?>(null)
        private set

    internal var launchPicker: (() -> Unit)? = null

    /** Öffnet die Dateiauswahl des Handys. */
    fun pick() {
        launchPicker?.invoke()
    }

    fun open(uri: Uri) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { buildPreview(uri) }
                if (p == null) {
                    messenger.show("In der Datei wurden keine Arbeitszeiten gefunden.", long = true)
                } else {
                    preview = p
                }
            } catch (e: BackupFormatException) {
                messenger.show(e.message ?: "Die Sicherung ist nicht lesbar.", long = true)
            } catch (e: Exception) {
                messenger.show("Die Datei konnte nicht gelesen werden: ${e.message ?: e.javaClass.simpleName}", long = true)
            } finally {
                busy = false
            }
        }
    }

    fun dismiss() {
        preview = null
    }

    private fun buildPreview(uri: Uri): ImportPreview? {
        val db = StampDb.get(context)
        return when (val file = ImportReader.read(context, uri, zone)) {
            is ImportedFile.BackupFile ->
                ImportPreview.BackupFile(file.fileName, file.data, Backup.plan(file.data, db.eventKeys(), db.importedDates()))
            is ImportedFile.Times -> {
                if (file.result.isEmpty) return null
                val dates = db.importedDates()
                val keys = db.eventKeys()
                ImportPreview.Times(
                    fileName = file.fileName,
                    result = file.result,
                    replaced = file.result.days.count { it.date in dates },
                    existingRaw = file.result.rawRows.count { EventKey.of(it.ts, it.type, it.source) in keys },
                )
            }
        }
    }

    companion object {
        /** Sicherung (JSON), Stundenzettel (PDF/CSV/Text) und Rohdaten (CSV); manche Apps melden nur „Datei“. */
        val MIME_TYPES = arrayOf(
            "application/json", "application/pdf", "text/*", "application/csv",
            "application/vnd.ms-excel", "application/octet-stream",
        )
    }
}

@Composable
fun rememberImportController(): ImportController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val messenger = LocalMessenger.current
    return remember { ImportController(context, scope, messenger) }
}

/**
 * Gehört einmal an die Wurzel der Oberfläche: stellt die Dateiauswahl bereit und zeigt die Vorschau.
 * @param onTimesImported nach dem Import eines Stundenzettels (mit dem zuletzt importierten Monat).
 */
@Composable
fun ImportHost(
    controller: ImportController,
    onTimesImported: (YearMonth?) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.open(uri)
    }
    DisposableEffect(controller) {
        controller.launchPicker = { launcher.launch(ImportController.MIME_TYPES) }
        onDispose { controller.launchPicker = null }
    }
    when (val p = controller.preview) {
        is ImportPreview.BackupFile -> BackupPreviewSheet(
            p,
            onDismiss = controller::dismiss,
            onOpenSetup = {
                controller.dismiss()
                onOpenSetup()
            },
            onOpenHistory = {
                controller.dismiss()
                onOpenHistory()
            },
        )
        is ImportPreview.Times -> TimesPreviewSheet(
            p,
            onDismiss = controller::dismiss,
            onImported = { month ->
                controller.dismiss()
                onTimesImported(month)
            },
        )
        null -> Unit
    }
}

// ---------------------------------------------------------------------------------------------
// Vorschau: Sicherung
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupPreviewSheet(
    p: ImportPreview.BackupFile,
    onDismiss: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = p.data
    var withSettings by remember { mutableStateOf(data.settings.isNotEmpty()) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RestoreResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    PreviewSheet(onDismiss) {
        val done = result
        if (done != null) {
            // ---------- Ergebnis ----------
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconBubble(R.drawable.ic_check_circle_fill, App.colors.good, size = 64.dp)
                Spacer(Modifier.height(14.dp))
                Text("Übertragung abgeschlossen", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            }
            RowGroup(Modifier.padding(top = 4.dp)) {
                StatRow(R.drawable.ic_today, "Ereignisse", "${done.plan.newEvents.size} ergänzt" + skipped(done.plan.knownEvents))
                RowDivider()
                StatRow(R.drawable.ic_import, "Importierte Tage", "${done.plan.newDays.size} ergänzt" + skipped(done.plan.knownDays))
                RowDivider()
                StatRow(
                    R.drawable.ic_settings,
                    "Einstellungen",
                    if (done.settingsApplied) "übernommen" else "nicht übernommen – dieses Handy bleibt, wie es ist",
                )
            }
            Hint(
                "Berechtigungen (Standort, Benachrichtigungen, Akku) gelten immer nur für ein Handy. " +
                    "Bitte unter „Einrichtung“ prüfen, ob auf diesem Handy alles einen Haken hat.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("Zum Verlauf", onClick = onOpenHistory, modifier = Modifier.weight(1f))
                PrimaryButton("Einrichtung prüfen", onClick = onOpenSetup, modifier = Modifier.weight(1.2f))
            }
        } else {
            // ---------- Vorschau ----------
            SheetTitle("Sicherung einlesen", p.fileName)
            val created = buildList {
                if (data.createdAt > 0) add("Erstellt am ${TimeFormat.date(data.createdAt, zone)} um ${TimeFormat.time(data.createdAt, zone)} Uhr")
                data.device?.let { add("auf $it") }
                data.appVersion?.let { add("App-Version $it") }
            }
            if (created.isNotEmpty()) {
                Text(created.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RowGroup {
                val range = data.events.takeIf { it.isNotEmpty() }?.let { evs ->
                    val first = Summary.localDate(evs.minOf { it.ts }, zone)
                    val last = Summary.localDate(evs.maxOf { it.ts }, zone)
                    "${TimeFormat.date(first)} – ${TimeFormat.date(last)} · "
                }.orEmpty()
                StatRow(
                    R.drawable.ic_today,
                    TimeFormat.plural(data.events.size, "Ereignis", "Ereignisse"),
                    "$range${p.plan.newEvents.size} neu" + skipped(p.plan.knownEvents),
                )
                RowDivider()
                StatRow(
                    R.drawable.ic_import,
                    TimeFormat.plural(data.importedDays.size, "importierter Tag", "importierte Tage"),
                    "Aus Stundenzetteln · ${p.plan.newDays.size} neu" + skipped(p.plan.knownDays),
                )
                RowDivider()
                if (data.settings.isEmpty()) {
                    StatRow(R.drawable.ic_settings, "Einstellungen", "nicht in der Sicherung enthalten")
                } else {
                    SwitchRow(
                        title = "Einstellungen übernehmen",
                        subtitle = "WLAN-Namen, Arbeitsort, Soll-Zeit und Regeln vom anderen Handy ersetzen die Einstellungen hier.",
                        checked = withSettings,
                        onChange = { withSettings = it },
                        icon = R.drawable.ic_settings,
                    )
                }
            }
            if (data.unreadable > 0) {
                Hint(
                    "${TimeFormat.plural(data.unreadable, "Eintrag", "Einträge")} der Datei konnten nicht gelesen werden und werden ausgelassen.",
                    icon = R.drawable.ic_warning,
                )
            }
            Hint("Es wird nichts gelöscht oder überschrieben: Was auf diesem Handy schon erfasst ist, bleibt erhalten.")
            error?.let { Hint(it, icon = R.drawable.ic_error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietButton("Abbrechen", onClick = onDismiss, enabled = !working, modifier = Modifier.weight(1f))
                PrimaryButton(
                    if (working) "Übertrage …" else "Einlesen",
                    icon = R.drawable.ic_phone_receive,
                    enabled = !working,
                    modifier = Modifier.weight(1.2f),
                    onClick = {
                        working = true
                        error = null
                        scope.launch {
                            try {
                                result = withContext(Dispatchers.IO) { BackupStore.restore(context, data, withSettings) }
                            } catch (e: Exception) {
                                error = "Übertragung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
                            } finally {
                                working = false
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun skipped(n: Int): String = if (n > 0) " · $n schon vorhanden" else ""

// ---------------------------------------------------------------------------------------------
// Vorschau: Stundenzettel / Rohdaten
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimesPreviewSheet(p: ImportPreview.Times, onDismiss: () -> Unit, onImported: (YearMonth?) -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val r = p.result
    PreviewSheet(onDismiss) {
        SheetTitle("Import prüfen", p.fileName)
        if (r.days.isNotEmpty()) {
            RowGroup {
                StatRow(
                    R.drawable.ic_history,
                    "${TimeFormat.plural(r.days.size, "Arbeitstag", "Arbeitstage")} · ${TimeFormat.hm(r.days.sumOf { it.minutes })} h",
                    "${TimeFormat.date(r.days.first().date)} – ${TimeFormat.date(r.days.last().date)}" +
                        if (p.replaced > 0) " · ${p.replaced} ersetzen bereits importierte Tage" else "",
                )
                val absences = r.days.filter { it.isAbsence }
                if (absences.isNotEmpty()) {
                    RowDivider()
                    StatRow(
                        R.drawable.ic_event_busy,
                        "${TimeFormat.plural(absences.size, "Tag", "Tage")} mit Krank/Urlaub",
                        absences.joinToString { "${TimeFormat.shortDate(it.date)} ${it.note}" },
                    )
                }
            }
            Caption("Kontrolle je Monat: Summe laut Datei / gelesen")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.checks.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ok = c.ok != false
                        AppIcon(
                            if (ok) R.drawable.ic_check_circle_fill else R.drawable.ic_error_fill,
                            if (ok) "stimmt" else "weicht ab",
                            tint = if (ok) App.colors.good else App.colors.bad,
                            size = 20.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            TimeFormat.month(c.month.atDay(1)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${c.statedMinutes?.let { TimeFormat.hm(it) } ?: "–"} / ${TimeFormat.hm(c.parsedMinutes)} h",
                            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                            color = if (ok) MaterialTheme.colorScheme.onSurface else App.colors.bad,
                        )
                    }
                }
                if (r.checks.any { it.ok == null }) {
                    Text(
                        "„–“ bedeutet: In der Datei steht keine Monatssumme zum Vergleichen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (r.rawRows.isNotEmpty()) {
            RowGroup {
                StatRow(
                    R.drawable.ic_today,
                    "${TimeFormat.plural(r.rawRows.size, "Ereignis", "Ereignisse")} aus Rohdaten-Export",
                    "${r.rawRows.size - p.existingRaw} neu" + skipped(p.existingRaw),
                )
            }
            Hint(
                "Der Rohdaten-Export enthält nur die Ereignisse. Einstellungen und importierte Stundenzettel kommen " +
                    "mit einer Sicherung herüber (Optionen → Auf neues Handy übertragen).",
            )
        }
        if (r.skippedLines.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Caption("Nicht verstandene Zeilen (${r.skippedLines.size})")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                r.skippedLines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietButton("Abbrechen", onClick = onDismiss, modifier = Modifier.weight(1f))
            PrimaryButton(
                "Importieren",
                icon = R.drawable.ic_check,
                modifier = Modifier.weight(1.2f),
                onClick = {
                    val db = StampDb.get(context)
                    if (r.days.isNotEmpty()) db.upsertImported(r.days, p.fileName)
                    val added = if (r.rawRows.isNotEmpty()) db.restoreRaw(r.rawRows) else 0
                    messenger.show(
                        "Importiert: " + listOfNotNull(
                            TimeFormat.plural(r.days.size, "Tag", "Tage").takeIf { r.days.isNotEmpty() },
                            TimeFormat.plural(added, "Ereignis", "Ereignisse").takeIf { r.rawRows.isNotEmpty() },
                        ).joinToString(", "),
                    )
                    if (r.days.isNotEmpty()) onImported(YearMonth.from(r.days.last().date)) else onDismiss()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Gemeinsame Bausteine
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun SheetTitle(title: String, fileName: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (fileName.isNotBlank()) {
            Text(fileName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatRow(icon: Int, title: String, text: String) {
    ListRow(title = title, subtitle = text, icon = icon)
}
