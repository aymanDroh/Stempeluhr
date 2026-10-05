package de.droh.stempeluhr.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.core.ImportedDay
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.data.StampDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val zone: ZoneId get() = ZoneId.systemDefault()

/** Alle Ereignisse (inkl. gelöschter); lädt bei jeder Änderung der Datenbank neu. */
@Composable
fun rememberAllEvents(): State<List<StampEvent>> {
    val db = StampDb.get(LocalContext.current)
    val version by db.changes.collectAsState()
    return produceState<List<StampEvent>>(initialValue = emptyList(), version) {
        value = withContext(Dispatchers.IO) { db.all(includeDeleted = true) }
    }
}

/** Alle importierten Tage; lädt bei jeder Änderung der Datenbank neu. */
@Composable
fun rememberImportedDays(): State<List<ImportedDay>> {
    val db = StampDb.get(LocalContext.current)
    val version by db.changes.collectAsState()
    return produceState<List<ImportedDay>>(initialValue = emptyList(), version) {
        value = withContext(Dispatchers.IO) { db.importedDays() }
    }
}

/** Liefert die Einstellungen und sorgt für Neuzeichnen bei Änderungen. */
@Composable
fun rememberSettings(): Pair<Settings, Long> {
    val settings = Settings.get(LocalContext.current)
    val version by settings.changes.collectAsState()
    return settings to version
}

/** Version des Zustands der automatischen Quellen (für Live-Status). */
@Composable
fun rememberAutoStateVersion(): Long {
    val version by Settings.get(LocalContext.current).stateChanges.collectAsState()
    return version
}

/** Aktuelle Zeit, die sich jede halbe Minute aktualisiert. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun EventRow(
    event: StampEvent,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    val color = if (event.type == StampType.IN) Color(0xFF2E7D32) else Color(0xFFC62828)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${TimeFormat.time(event.ts, zone)}  ${event.type.label}",
                fontWeight = FontWeight.SemiBold,
                color = if (event.deleted) MaterialTheme.colorScheme.outline else color,
                textDecoration = if (event.deleted) TextDecoration.LineThrough else null,
            )
            val details = buildList {
                add(event.source.label)
                if (event.edited) add("geändert, vorher ${TimeFormat.time(event.originalTs!!, zone)}")
                if (event.deleted) add("gelöscht")
                event.note?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        if (event.deleted) {
            IconButton(onClick = onRestore) { Icon(Icons.Filled.Refresh, contentDescription = "Wiederherstellen") }
        } else {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Löschen") }
        }
    }
}

/**
 * Dialog zum Bearbeiten ([existing] != null) oder Nachtragen eines Eintrags.
 * Neue Einträge erhalten die Quelle "Manuell".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEventDialog(
    existing: StampEvent?,
    defaultDate: LocalDate,
    defaultType: StampType = StampType.IN,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val db = StampDb.get(context)
    val initial = existing?.let { Instant.ofEpochMilli(it.ts).atZone(zone) }
    var date by remember { mutableStateOf(initial?.toLocalDate() ?: defaultDate) }
    var time by remember {
        mutableStateOf(initial?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.now().withSecond(0).withNano(0))
    }
    var type by remember { mutableStateOf(existing?.type ?: defaultType) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Eintrag nachtragen" else "Eintrag bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    Text("Quelle: ${existing.source.label}", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Quelle: ${StampSource.MANUELL.label}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StampType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> date = LocalDate.of(y, m + 1, d) },
                            date.year,
                            date.monthValue - 1,
                            date.dayOfMonth,
                        ).show()
                    }) { Text("${TimeFormat.weekday(date)} ${TimeFormat.date(date)}") }
                    OutlinedButton(onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, min -> time = LocalTime.of(h, min) },
                            time.hour,
                            time.minute,
                            true,
                        ).show()
                    }) { Text("%02d:%02d".format(time.hour, time.minute)) }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Notiz (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ts = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                val cleanNote = note.trim().ifEmpty { null }
                if (existing == null) {
                    db.insert(ts, type, StampSource.MANUELL, cleanNote)
                } else {
                    // Sekunden der Originalzeit behalten, wenn Datum und Minute unverändert sind.
                    val keepTs = existing.ts.takeIf { it / 60_000 == ts / 60_000 } ?: ts
                    db.update(existing.id, keepTs, type, cleanNote)
                }
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmDeleteDialog(event: StampEvent, onDismiss: () -> Unit) {
    val db = StampDb.get(LocalContext.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Eintrag löschen?") },
        text = {
            Text(
                "${event.type.label} um ${TimeFormat.time(event.ts, zone)} (${event.source.label}) wird als gelöscht markiert. " +
                    "Er bleibt im Rohdaten-Export sichtbar und kann im Verlauf wiederhergestellt werden.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                db.setDeleted(event.id, true)
                onDismiss()
            }) { Text("Löschen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Hält Bearbeiten-/Löschen-/Nachtragen-Dialoge für eine Liste von Ereignissen. */
class EventDialogs {
    var editing by mutableStateOf<StampEvent?>(null)
    var deleting by mutableStateOf<StampEvent?>(null)
    var addingFor by mutableStateOf<Pair<LocalDate, StampType>?>(null)
}

@Composable
fun EventDialogsHost(state: EventDialogs) {
    state.editing?.let { ev ->
        EditEventDialog(existing = ev, defaultDate = LocalDate.now(zone), onDismiss = { state.editing = null })
    }
    state.addingFor?.let { (date, type) ->
        EditEventDialog(existing = null, defaultDate = date, defaultType = type, onDismiss = { state.addingFor = null })
    }
    state.deleting?.let { ev ->
        ConfirmDeleteDialog(event = ev, onDismiss = { state.deleting = null })
    }
}

@Composable
fun SmallLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun Bullet(ok: Boolean) {
    Text(
        if (ok) "✓" else "✗",
        color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.size(20.dp),
    )
}
