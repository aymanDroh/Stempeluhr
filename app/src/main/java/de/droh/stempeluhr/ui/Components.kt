package de.droh.stempeluhr.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.ImportedDay
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.data.StampDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

val zone: ZoneId get() = ZoneId.systemDefault()

// ---------------------------------------------------------------------------------------------
// Daten für die Oberfläche
// ---------------------------------------------------------------------------------------------

/** Stand der Datenbank, einmal geladen und von allen Bildschirmen gemeinsam genutzt. */
private class AppData(val events: List<StampEvent>, val imported: List<ImportedDay>)

private val LocalAppData = compositionLocalOf<AppData?> { null }

/**
 * Lädt Ereignisse und importierte Tage einmal für die ganze Oberfläche und bei jeder Änderung der Datenbank neu.
 * Der Inhalt erscheint erst, wenn die Daten da sind (wenige Millisekunden) – so zeigt die Statuskarte beim Start
 * nicht erst „nicht eingestempelt“ und springt dann um.
 */
@Composable
fun ProvideAppData(content: @Composable () -> Unit) {
    val db = StampDb.get(LocalContext.current)
    val version by db.changes.collectAsState()
    val data by produceState<AppData?>(initialValue = null, version) {
        value = withContext(Dispatchers.IO) { AppData(db.all(includeDeleted = true), db.importedDays()) }
    }
    data?.let { loaded -> CompositionLocalProvider(LocalAppData provides loaded, content = content) }
}

/** Alle Ereignisse (inkl. gelöschter); aktualisiert sich bei jeder Änderung der Datenbank. */
@Composable
fun rememberAllEvents(): State<List<StampEvent>> {
    LocalAppData.current?.let { return rememberUpdatedState(it.events) }
    // Ohne gemeinsamen Stand (z. B. einzelner Baustein in einem Test) selbst laden.
    val db = StampDb.get(LocalContext.current)
    val version by db.changes.collectAsState()
    return produceState<List<StampEvent>>(initialValue = emptyList(), version) {
        value = withContext(Dispatchers.IO) { db.all(includeDeleted = true) }
    }
}

/** Alle importierten Tage; aktualisiert sich bei jeder Änderung der Datenbank. */
@Composable
fun rememberImportedDays(): State<List<ImportedDay>> {
    LocalAppData.current?.let { return rememberUpdatedState(it.imported) }
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

/**
 * Aktuelle Zeit, die sich regelmäßig aktualisiert (Standard: jede halbe Minute) – aber nur, solange die App
 * sichtbar ist. Im Hintergrund läuft keine Uhr mit; das spart Akku.
 */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(periodMs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                // Auf die nächste volle Periode warten, damit Sekunden gleichmäßig weiterspringen.
                delay(periodMs - now % periodMs)
            }
        }
    }
    return now
}

// ---------------------------------------------------------------------------------------------
// Kurze Rückmeldungen am unteren Rand (statt Toasts), auf Wunsch mit „Rückgängig“
// ---------------------------------------------------------------------------------------------

class Messenger(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(text: String, action: String? = null, long: Boolean = false, onAction: (() -> Unit)? = null) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = text,
                actionLabel = action,
                duration = if (action != null || long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalMessenger = staticCompositionLocalOf<Messenger> { error("Messenger nicht bereitgestellt") }

// ---------------------------------------------------------------------------------------------
// Ereignis-Zeilen
// ---------------------------------------------------------------------------------------------

/** Runde Marke mit Pfeil: grün = Kommen, rot = Gehen, grau = gelöscht. */
@Composable
fun TypeMark(type: StampType, deleted: Boolean = false, size: Dp = 32.dp) {
    val color = if (deleted) MaterialTheme.colorScheme.outline else typeColor(type)
    IconBubble(typeIcon(type), color, size = size, shape = CircleShape)
}

/**
 * Ein Ereignis: Uhrzeit, Art, Quelle. Antippen öffnet das Bearbeiten; gelöschte Einträge lassen sich
 * wiederherstellen. Mit [connect] wird eine Linie zum nächsten Eintrag gezeichnet (Zeitstrahl).
 */
@Composable
fun EventRow(
    event: StampEvent,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    connect: Boolean = false,
) {
    val description = "${event.type.label} um ${TimeFormat.time(event.ts, zone)}, ${sourceName(event.source)}"
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (event.deleted) {
                    Modifier
                } else {
                    Modifier.clickable(onClickLabel = "$description bearbeiten", role = Role.Button, onClick = onEdit)
                },
            ),
    ) {
        Column(Modifier.fillMaxHeight().width(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            TypeMark(event.type, event.deleted)
            if (connect) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 32.dp)) {
                Text(
                    TimeFormat.time(event.ts, zone),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                    color = if (event.deleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (event.deleted) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    event.type.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SourcePill(event.source)
            }
            val details = buildList {
                if (event.deleted) add("gelöscht")
                if (event.edited) add("geändert, vorher ${TimeFormat.time(event.originalTs!!, zone)}")
                event.note?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (event.deleted) {
            IconButton(onClick = onRestore, modifier = Modifier.align(Alignment.CenterVertically)) {
                AppIcon(R.drawable.ic_restore, "Wiederherstellen", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Box(Modifier.fillMaxHeight().padding(start = 4.dp), contentAlignment = Alignment.Center) {
                AppIcon(R.drawable.ic_edit, tint = MaterialTheme.colorScheme.outline, size = 18.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Eintrag bearbeiten / nachtragen
// ---------------------------------------------------------------------------------------------

/** Hält, welcher Eintrag gerade bearbeitet bzw. für welchen Tag nachgetragen wird. */
class EventDialogs {
    var editing by mutableStateOf<StampEvent?>(null)
    var addingFor by mutableStateOf<Pair<LocalDate, StampType>?>(null)
}

@Composable
fun EventDialogsHost(state: EventDialogs) {
    state.editing?.let { ev ->
        EventEditorSheet(existing = ev, defaultDate = LocalDate.now(zone), defaultType = ev.type, onDismiss = { state.editing = null })
    }
    state.addingFor?.let { (date, type) ->
        EventEditorSheet(existing = null, defaultDate = date, defaultType = type, onDismiss = { state.addingFor = null })
    }
}

/**
 * Blatt von unten zum Bearbeiten ([existing] != null) oder Nachtragen eines Eintrags.
 * Neue Einträge erhalten die Quelle „Manuell“. Löschen markiert nur und lässt sich rückgängig machen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    existing: StampEvent?,
    defaultDate: LocalDate,
    defaultType: StampType,
    onDismiss: () -> Unit,
) {
    val db = StampDb.get(LocalContext.current)
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = existing?.let { Instant.ofEpochMilli(it.ts).atZone(zone) }
    var date by remember { mutableStateOf(initial?.toLocalDate() ?: defaultDate) }
    var time by remember {
        mutableStateOf(
            (initial ?: Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone)).toLocalTime().withSecond(0).withNano(0),
        )
    }
    var type by remember { mutableStateOf(existing?.type ?: defaultType) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    /** Fährt das Blatt herunter und meldet danach das Ergebnis. */
    fun close(message: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            if (message != null) messenger.show(message, action, onAction = onAction)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (existing == null) "Eintrag nachtragen" else "Eintrag bearbeiten",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (existing?.edited == true) {
                        Text(
                            "Ursprünglich ${TimeFormat.date(existing.originalTs!!, zone)}, " +
                                "${TimeFormat.time(existing.originalTs, zone)} Uhr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SourcePill(existing?.source ?: StampSource.MANUELL)
            }

            SegmentedChoice(
                options = StampType.entries,
                selected = type,
                onSelect = { type = it },
                label = { it.label },
                icon = ::typeIcon,
                selectedColor = { typeColor(it) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FieldButton(
                    icon = R.drawable.ic_event,
                    label = "Datum",
                    value = "${TimeFormat.weekday(date)}, ${TimeFormat.date(date)}",
                    onClick = { pickDate = true },
                    modifier = Modifier.weight(1.45f),
                )
                FieldButton(
                    icon = R.drawable.ic_today,
                    label = "Uhrzeit",
                    value = "%02d:%02d".format(time.hour, time.minute),
                    onClick = { pickTime = true },
                    modifier = Modifier.weight(1f),
                )
            }
            if (date.atTime(time).atZone(zone).toInstant().toEpochMilli() > System.currentTimeMillis() + 60_000) {
                Hint("Dieser Zeitpunkt liegt in der Zukunft.", icon = R.drawable.ic_warning)
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(200) },
                label = { Text("Notiz (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (existing != null) {
                    QuietButton(
                        "Löschen",
                        icon = R.drawable.ic_delete,
                        danger = true,
                        onClick = {
                            db.setDeleted(existing.id, true)
                            close("${existing.type.label} ${TimeFormat.time(existing.ts, zone)} gelöscht", "Rückgängig") {
                                db.setDeleted(existing.id, false)
                            }
                        },
                    )
                }
                PrimaryButton(
                    "Speichern",
                    icon = R.drawable.ic_check,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val ts = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                        val cleanNote = note.trim().ifEmpty { null }
                        if (existing == null) {
                            db.insert(ts, type, StampSource.MANUELL, cleanNote)
                            close("${type.label} ${TimeFormat.time(ts, zone)} nachgetragen")
                        } else {
                            // Sekunden der Originalzeit behalten, wenn Datum und Minute unverändert sind.
                            val keepTs = existing.ts.takeIf { it / 60_000 == ts / 60_000 } ?: ts
                            db.update(existing.id, keepTs, type, cleanNote)
                            close("Eintrag gespeichert")
                        }
                    },
                )
            }
        }
    }

    if (pickDate) DatePickerPopup(date, onPick = { date = it }, onDismiss = { pickDate = false })
    if (pickTime) TimePickerPopup(time, onPick = { time = it }, onDismiss = { pickTime = false })
}

/** Antippbares Feld mit Beschriftung und Wert (öffnet eine Auswahl). */
@Composable
private fun FieldButton(@DrawableRes icon: Int, label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(App.colors.inset)
            .clickable(onClickLabel = "$label ändern", role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Caption(label)
            Text(value, style = MaterialTheme.typography.titleMedium.merge(TabularNumbers), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Datum und Uhrzeit wählen (Material 3)
// ---------------------------------------------------------------------------------------------

/**
 * Rahmen für die Auswahl-Dialoge. Die Material-Auswahlen haben eine feste Mindestgröße; auf schmalen oder
 * quer gehaltenen Bildschirmen wird der Dialog deshalb als Ganzes verkleinert, statt abgeschnitten zu werden.
 */
@Composable
private fun PickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    designWidth: Dp,
    designHeight: Dp,
    confirmEnabled: Boolean = true,
    extraAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val fit = minOf(1f, maxWidth / designWidth, maxHeight / designHeight)
            // Antippen neben dem Dialog schließt ihn (der Dialog füllt hier das ganze Fenster).
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .requiredWidth(designWidth)
                    .wrapContentHeight(unbounded = true)
                    .graphicsLayer {
                        scaleX = fit
                        scaleY = fit
                    }
                    .clickable(interactionSource = null, indication = null, onClick = {}),
            ) {
                Column {
                    content()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        extraAction?.invoke()
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Abbrechen") }
                        TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text("OK") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerPopup(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // Der Material-Kalender rechnet in UTC-Mitternacht.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    PickerDialog(
        onDismiss = onDismiss,
        onConfirm = {
            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            onDismiss()
        },
        designWidth = 360.dp,
        designHeight = 640.dp,
        confirmEnabled = state.selectedDateMillis != null,
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerPopup(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    var keyboard by rememberSaveable { mutableStateOf(false) }
    PickerDialog(
        onDismiss = onDismiss,
        onConfirm = {
            onPick(LocalTime.of(state.hour, state.minute))
            onDismiss()
        },
        designWidth = 328.dp,
        designHeight = if (keyboard) 260.dp else 540.dp,
        extraAction = {
            IconButton(onClick = { keyboard = !keyboard }) {
                AppIcon(
                    if (keyboard) R.drawable.ic_today else R.drawable.ic_keyboard,
                    if (keyboard) "Zur Uhr wechseln" else "Zur Tastatur-Eingabe wechseln",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Text(
            if (keyboard) "Uhrzeit eingeben" else "Uhrzeit wählen",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 20.dp),
        )
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) {
            // Immer das hochkant-Layout: Quer verlangt die Material-Uhr sonst mehr Breite, als der Dialog hat.
            if (keyboard) TimeInput(state) else TimePicker(state, layoutType = TimePickerLayoutType.Vertical)
        }
    }
}
