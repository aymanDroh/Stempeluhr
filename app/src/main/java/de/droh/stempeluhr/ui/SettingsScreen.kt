package de.droh.stempeluhr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.droh.stempeluhr.core.CsvExport
import de.droh.stempeluhr.core.ImportParser
import de.droh.stempeluhr.core.ImportResult
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.ImportReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.data.StampDb
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.geo.GeofenceManager
import de.droh.stempeluhr.service.MonitorService
import java.time.LocalDate
import java.util.Locale

/** Prüft, was für die automatische Erfassung noch fehlt. */
object SetupCheck {
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsOk(c: Context) = Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)
    fun fineLocationOk(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocationOk(c: Context) = granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun batteryOk(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    fun locationServiceOk(c: Context) = c.getSystemService(LocationManager::class.java).isLocationEnabled
    fun nfcOk(c: Context) = NfcAdapter.getDefaultAdapter(c)?.isEnabled == true

    fun issues(c: Context): List<String> {
        val s = Settings.get(c)
        val auto = s.wifiEnabled || s.geoEnabled
        return buildList {
            if (!s.wifiEnabled && !s.geoEnabled && !s.nfcEnabled) add("Keine Erfassungsart eingeschaltet")
            if (s.wifiEnabled && s.wifiSsidSet.isEmpty()) add("Kein Arbeits-WLAN eingetragen")
            if (s.geoEnabled && (s.geoLat == null || s.geoLon == null)) add("Kein Arbeitsort festgelegt")
            if (auto && !fineLocationOk(c)) add("Standort-Berechtigung fehlt")
            if (auto && !backgroundLocationOk(c)) add("Standort \"Immer erlauben\" fehlt")
            if (auto && !locationServiceOk(c)) add("Standortdienst (GPS) ist ausgeschaltet")
            if (auto && !batteryOk(c)) add("Akku-Optimierung ist noch aktiv")
            if (s.nfcEnabled && NfcAdapter.getDefaultAdapter(c) != null && !nfcOk(c)) add("NFC ist ausgeschaltet")
        }
    }
}

@Composable
fun SettingsScreen(
    modifier: Modifier,
    resumeCount: Int,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
) {
    val context = LocalContext.current
    val (settings, settingsVersion) = rememberSettings()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SetupSection(resumeCount, settingsVersion)
        WifiSection(settings, settingsVersion)
        GeoSection(settings, settingsVersion)
        NfcSection(settings, settingsVersion, tagWriteMode, onStartTagWrite, onCancelTagWrite)
        EvaluationSection(settings, settingsVersion)
        StudentSection(settings, settingsVersion)
        ImportSection(settings, settingsVersion)
        ExportSection(settings)
        Text(
            "Hinweis: Alle Daten liegen nur auf diesem Handy. Vor dem Deinstallieren oder Handywechsel " +
                "bitte die Rohdaten exportieren.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    // Einstellungen geändert -> Dienst/Geofence neu starten.
    LaunchedEffect(settingsVersion) { MonitorService.start(context) }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Bullet(ok)
        Text(label, Modifier.weight(1f))
        if (!ok) TextButton(onClick = onAction) { Text(action) }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun SetupSection(resumeCount: Int, settingsVersion: Long) {
    val context = LocalContext.current
    // resumeCount/settingsVersion sorgen dafür, dass der Status nach Rückkehr aus den Systemeinstellungen neu geprüft wird.
    val key = resumeCount to settingsVersion
    var refresh by remember { mutableStateOf(0) }
    val notifOk = remember(key, refresh) { SetupCheck.notificationsOk(context) }
    val fineOk = remember(key, refresh) { SetupCheck.fineLocationOk(context) }
    val bgOk = remember(key, refresh) { SetupCheck.backgroundLocationOk(context) }
    val batteryOk = remember(key, refresh) { SetupCheck.batteryOk(context) }
    val locOk = remember(key, refresh) { SetupCheck.locationServiceOk(context) }
    val hasNfc = remember { NfcAdapter.getDefaultAdapter(context) != null }
    val nfcOk = remember(key, refresh) { SetupCheck.nfcOk(context) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok) openAppSettings(context)
    }

    Section("Einrichtung") {
        Text(
            "Damit die Erfassung ohne Entsperren und ohne Zutun funktioniert, müssen alle Punkte einen Haken haben.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            CheckRow("Benachrichtigungen erlaubt", notifOk, "Erlauben") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        CheckRow("Standort (genau) erlaubt", fineOk, "Erlauben") {
            fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        CheckRow("Standort \"Immer erlauben\"", bgOk, "Öffnen") {
            if (!fineOk) {
                Toast.makeText(context, "Bitte zuerst \"Standort (genau)\" erlauben.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Bitte \"Immer erlauben\" auswählen.", Toast.LENGTH_LONG).show()
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        CheckRow("Standortdienst eingeschaltet", locOk, "Öffnen") {
            context.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        CheckRow("Akku-Optimierung aus", batteryOk, "Ausschalten") { requestBatteryExemption(context) }
        if (hasNfc) {
            CheckRow("NFC eingeschaltet", nfcOk, "Öffnen") {
                context.startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS))
            }
        }
        HorizontalDivider()
        Text(
            "Samsung: Zusätzlich unter Einstellungen → Akku → Hintergrundnutzungslimits die App bei " +
                "\"Nie in Standby-Modus versetzte Apps\" eintragen. Die dauerhafte Benachrichtigung " +
                "\"Stempeluhr läuft\" kann in den App-Benachrichtigungen ausgeblendet werden.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { openAppSettings(context) }) { Text("App-Einstellungen öffnen") }
    }
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    } catch (_: Exception) {
        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun WifiSection(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    var ssids by remember(settingsVersion) { mutableStateOf(settings.wifiSsids) }
    Section("WLAN (automatisch)") {
        Text(
            "Kommen = Verbindung mit dem Arbeits-WLAN, Gehen = Verbindung getrennt. " +
                "Funktioniert bei gesperrtem Handy. Benötigt Standort \"Immer erlauben\" und eingeschalteten Standortdienst " +
                "(Android gibt den WLAN-Namen sonst nicht heraus).",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("WLAN-Erfassung", settings.wifiEnabled) { settings.wifiEnabled = it }
        OutlinedTextField(
            value = ssids,
            onValueChange = { ssids = it },
            label = { Text("WLAN-Name(n), mehrere mit Komma") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { settings.wifiSsids = ssids.trim() }) { Text("Speichern") }
            OutlinedButton(onClick = {
                val current = currentSsid(context)
                if (current == null) {
                    Toast.makeText(
                        context,
                        "WLAN-Name nicht lesbar. Mit dem Arbeits-WLAN verbunden? Standort erlaubt und eingeschaltet?",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    val set = settings.wifiSsidSet + current
                    settings.wifiSsids = set.joinToString(", ")
                    Toast.makeText(context, "\"$current\" übernommen.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Aktuelles WLAN übernehmen") }
        }
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun currentSsid(context: Context): String? {
    if (!SetupCheck.fineLocationOk(context)) return null
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    return MonitorService.cleanSsid(wifi.connectionInfo?.ssid)
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun GeoSection(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    var lat by remember(settingsVersion) { mutableStateOf(settings.geoLat?.let { "%.6f".format(Locale.US, it) }.orEmpty()) }
    var lon by remember(settingsVersion) { mutableStateOf(settings.geoLon?.let { "%.6f".format(Locale.US, it) }.orEmpty()) }
    var radius by remember(settingsVersion) { mutableStateOf(settings.geoRadius.toString()) }
    var locating by remember { mutableStateOf(false) }
    val status by GeofenceManager.status.collectAsState()

    Section("Standort / Geofence (automatisch)") {
        Text(
            "Kommen = Arbeitsort betreten, Gehen = verlassen. Ungenauer als WLAN (meist 1–5 Minuten), " +
                "funktioniert aber auch ohne WLAN. Radius mindestens 100–150 m.",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("Standort-Erfassung", settings.geoEnabled) { settings.geoEnabled = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat,
                onValueChange = { lat = it },
                label = { Text("Breitengrad") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lon,
                onValueChange = { lon = it },
                label = { Text("Längengrad") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = radius,
            onValueChange = { radius = it.filter(Char::isDigit) },
            label = { Text("Radius in Metern") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val la = lat.replace(',', '.').toDoubleOrNull()
                val lo = lon.replace(',', '.').toDoubleOrNull()
                val r = radius.toIntOrNull()
                if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
                    Toast.makeText(context, "Ungültige Koordinaten.", Toast.LENGTH_SHORT).show()
                } else {
                    settings.geoLat = la
                    settings.geoLon = lo
                    settings.geoRadius = (r ?: 150).coerceIn(50, 5000)
                    Toast.makeText(context, "Arbeitsort gespeichert.", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Speichern") }
            OutlinedButton(enabled = !locating, onClick = {
                locating = true
                GeofenceManager.currentLocation(context) { loc ->
                    locating = false
                    if (loc == null) {
                        Toast.makeText(context, "Standort nicht verfügbar. Berechtigung und GPS prüfen.", Toast.LENGTH_LONG).show()
                    } else {
                        settings.geoLat = loc.latitude
                        settings.geoLon = loc.longitude
                        Toast.makeText(
                            context,
                            "Aktueller Standort übernommen (Genauigkeit ca. ${loc.accuracy.toInt()} m).",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }) { Text(if (locating) "Suche …" else "Aktuellen Standort übernehmen") }
        }
        Text("Status: $status", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------------------------------------

// settingsVersion ist nur Schlüssel fürs Neuzeichnen (Settings-Instanz bleibt gleich).
@Suppress("UNUSED_PARAMETER")
@Composable
private fun NfcSection(
    settings: Settings,
    settingsVersion: Long,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
) {
    Section("NFC-Tag (genau, Handy muss entsperrt sein)") {
        Text(
            "Einen NFC-Aufkleber neben die Stechuhr kleben und hier beschreiben. Danach genügt es, das " +
                "entsperrte Handy an den Tag zu halten – die App muss nicht geöffnet sein. " +
                "\"Umschalten\" erfasst abwechselnd Kommen und Gehen; alternativ zwei Tags (Kommen / Gehen).",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("NFC-Stempeln", settings.nfcEnabled) { settings.nfcEnabled = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStartTagWrite(StampEngine.Mode.TOGGLE) }) { Text("Umschalten") }
            OutlinedButton(onClick = { onStartTagWrite(StampEngine.Mode.IN) }) { Text("Kommen") }
            OutlinedButton(onClick = { onStartTagWrite(StampEngine.Mode.OUT) }) { Text("Gehen") }
        }
        Text("Tag beschreiben: Knopf wählen, dann Tag an die Handy-Rückseite halten.", style = MaterialTheme.typography.bodySmall)
    }
    if (tagWriteMode != null) {
        AlertDialog(
            onDismissRequest = onCancelTagWrite,
            title = { Text("Tag beschreiben") },
            text = {
                val label = when (tagWriteMode) {
                    StampEngine.Mode.TOGGLE -> "Umschalten (Kommen/Gehen abwechselnd)"
                    StampEngine.Mode.IN -> "nur Kommen"
                    StampEngine.Mode.OUT -> "nur Gehen"
                }
                Text("Jetzt den NFC-Tag an die Rückseite des Handys halten.\n\nModus: $label")
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelTagWrite) { Text("Abbrechen") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun EvaluationSection(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    var delay by remember(settingsVersion) { mutableStateOf(settings.leaveDelayMinutes.toString()) }
    var target by remember(settingsVersion) {
        mutableStateOf(settings.targetMinutes.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" })
    }
    val priority = remember(settingsVersion) { settings.priority }

    Section("Auswertung") {
        SwitchRow("Benachrichtigung bei jedem Stempel", settings.notifyOnStamp) { settings.notifyOnStamp = it }
        OutlinedTextField(
            value = delay,
            onValueChange = { delay = it.filter(Char::isDigit) },
            label = { Text("Verzögerung fürs Gehen (Minuten)") },
            supportingText = {
                Text("WLAN/Standort muss so lange weg sein, bevor ein Gehen gespeichert wird (mit dem Zeitpunkt des Wegseins).")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Soll-Arbeitszeit pro Tag (h:mm)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val d = delay.toIntOrNull()
            val parts = target.split(':')
            val t = if (parts.size == 2) {
                val h = parts[0].trim().toIntOrNull()
                val m = parts[1].trim().toIntOrNull()
                if (h != null && m != null && m in 0..59) h * 60 + m else null
            } else {
                target.replace(',', '.').toDoubleOrNull()?.let { (it * 60).toInt() }
            }
            if (d == null || t == null || d > 120 || t !in 0..24 * 60) {
                Toast.makeText(context, "Ungültige Eingabe.", Toast.LENGTH_SHORT).show()
            } else {
                settings.leaveDelayMinutes = d
                settings.targetMinutes = t
                Toast.makeText(context, "Gespeichert.", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Speichern") }

        HorizontalDivider()
        Text("Welche Quelle zählt für Kommen/Gehen?", fontWeight = FontWeight.SemiBold)
        Text(
            "Für jeden Tag wird die erste Quelle in dieser Liste genommen, die eine Zeit hat. " +
                "Alle Quellen bleiben trotzdem gespeichert und sichtbar.",
            style = MaterialTheme.typography.bodySmall,
        )
        priority.forEachIndexed { index, src ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${src.label}", Modifier.weight(1f))
                IconButton(enabled = index > 0, onClick = {
                    settings.priority = priority.toMutableList().apply { add(index - 1, removeAt(index)) }
                }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Nach oben") }
                IconButton(enabled = index < priority.lastIndex, onClick = {
                    settings.priority = priority.toMutableList().apply { add(index + 1, removeAt(index)) }
                }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Nach unten") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun ExportSection(settings: Settings) {
    val context = LocalContext.current
    val rawLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val text = CsvExport.rawEvents(StampDb.get(context).all(includeDeleted = true), zone)
            writeText(context, uri, text)
        }
    }
    val dailyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val db = StampDb.get(context)
            val days = Summary.byDay(db.all(includeDeleted = false), zone, settings.priority, db.importedDays(), settings.evalOptions)
            writeText(context, uri, CsvExport.daily(days, settings.targetMinutes, zone))
        }
    }
    Section("Export (CSV für Excel)") {
        Text(
            "Rohdaten: jedes einzelne Ereignis inkl. geänderter und gelöschter Einträge. " +
                "Tagesübersicht: gewertete Zeiten, Dauer, Saldo und die Zeiten jeder Quelle.",
            style = MaterialTheme.typography.bodySmall,
        )
        val today = LocalDate.now(zone)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { rawLauncher.launch("Stempeluhr-Rohdaten-$today.csv") }) { Text("Rohdaten") }
            Button(onClick = { dailyLauncher.launch("Stempeluhr-Tage-$today.csv") }) { Text("Tagesübersicht") }
        }
    }
}

private fun writeText(context: Context, uri: Uri, text: String) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Toast.makeText(context, "Export gespeichert.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Export fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun StudentSection(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    var weeks by remember(settingsVersion) { mutableStateOf(settings.studentLimitWeeks.toString()) }
    var hours by remember(settingsVersion) {
        mutableStateOf(settings.studentLimitMinutes.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" })
    }
    Section("Werkstudent (26-Wochen-Regel)") {
        Text(
            "Zählt rollierend über die letzten 52 Kalenderwochen (Mo–So), wie viele Wochen über der Stundengrenze lagen " +
                "(Brutto-Dauer, eigene Erfassung und Import, auch Semesterferien).",
            style = MaterialTheme.typography.bodySmall,
        )
        SwitchRow("Anzeige auf \"Heute\"", settings.studentEnabled) { settings.studentEnabled = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = weeks,
                onValueChange = { weeks = it.filter(Char::isDigit) },
                label = { Text("Max. Wochen") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = hours,
                onValueChange = { hours = it },
                label = { Text("Grenze (h:mm)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = {
            val w = weeks.toIntOrNull()
            val m = ImportParser.parseHm(hours) ?: hours.toIntOrNull()?.let { it * 60L }
            if (w == null || w !in 1..52 || m == null || m !in 60..7 * 24 * 60) {
                Toast.makeText(context, "Ungültige Eingabe.", Toast.LENGTH_SHORT).show()
            } else {
                settings.studentLimitWeeks = w
                settings.studentLimitMinutes = m.toInt()
                Toast.makeText(context, "Gespeichert.", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Speichern") }
    }
}

// ---------------------------------------------------------------------------------------------

private class ImportPreview(val fileName: String, val result: ImportResult, val replaced: Int, val existingRaw: Int)

@Composable
private fun ImportSection(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported by rememberImportedDays()
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) {
                    val db = StampDb.get(context)
                    val name = ImportReader.fileName(context, uri)
                    val result = ImportParser.parse(ImportReader.readText(context, uri), zone)
                    val existingDates = db.importedDays().map { it.date }.toSet()
                    val existingRaw = db.all(includeDeleted = true).map { Triple(it.ts, it.type, it.source) }.toSet()
                    ImportPreview(
                        fileName = name,
                        result = result,
                        replaced = result.days.count { it.date in existingDates },
                        existingRaw = result.rawRows.count { Triple(it.ts, it.type, it.source) in existingRaw },
                    )
                }
                if (p.result.isEmpty) {
                    Toast.makeText(context, "In der Datei wurden keine Arbeitszeiten gefunden.", Toast.LENGTH_LONG).show()
                } else {
                    preview = p
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Section("Import") {
        Text(
            "Stundenzettel als PDF oder CSV (Datum + Dauer je Tag, optional Bemerkung wie \"krank\") oder der " +
                "Rohdaten-Export dieser App (Wiederherstellung). Ein erneuter Import ersetzt Tage mit gleichem Datum.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(enabled = !busy, onClick = {
            openLauncher.launch(arrayOf("application/pdf", "text/*", "application/csv", "application/vnd.ms-excel"))
        }) { Text(if (busy) "Lese Datei …" else "Datei importieren") }
        Text(
            if (imported.isEmpty()) {
                "Noch keine importierten Tage."
            } else {
                "${imported.size} importierte Tage (${TimeFormat.date(imported.first().date)} – " +
                    "${TimeFormat.date(imported.last().date)}), Summe ${TimeFormat.hm(imported.sumOf { it.minutes })} h"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        SwitchRow("Bei Überschneidung zählt der Import (statt eigener Erfassung)", settings.preferImport) {
            settings.preferImport = it
        }
        SwitchRow("Krank-/Urlaubstage aus dem Import mitzählen", settings.countAbsence) { settings.countAbsence = it }
        if (imported.isNotEmpty()) {
            TextButton(onClick = { confirmDeleteAll = true }) { Text("Alle importierten Tage löschen") }
        }
    }

    preview?.let { p -> ImportPreviewDialog(p, onDismiss = { preview = null }) }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Alle importierten Tage löschen?") },
            text = { Text("Eigene Erfassungen (WLAN, Standort, NFC, manuell) bleiben erhalten.") },
            confirmButton = {
                TextButton(onClick = {
                    StampDb.get(context).deleteAllImported()
                    confirmDeleteAll = false
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ImportPreviewDialog(p: ImportPreview, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val r = p.result
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import prüfen") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(p.fileName, style = MaterialTheme.typography.bodySmall)
                if (r.days.isNotEmpty()) {
                    Text(
                        "${r.days.size} Arbeitstage von ${TimeFormat.date(r.days.first().date)} bis " +
                            "${TimeFormat.date(r.days.last().date)}, Summe ${TimeFormat.hm(r.days.sumOf { it.minutes })} h",
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (p.replaced > 0) Text("${p.replaced} davon ersetzen bereits importierte Tage.")
                    val absences = r.days.filter { it.isAbsence }
                    if (absences.isNotEmpty()) {
                        Text("${absences.size} Tage mit Krank/Urlaub: " + absences.joinToString { "${TimeFormat.shortDate(it.date)} ${it.note}" })
                    }
                    HorizontalDivider()
                    SmallLabel("Kontrolle je Monat (Summe laut Datei / gelesen)")
                    r.checks.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Bullet(c.ok != false)
                            Text(
                                "${TimeFormat.month(c.month.atDay(1))}: " +
                                    "${c.statedMinutes?.let { TimeFormat.hm(it) } ?: "–"} / ${TimeFormat.hm(c.parsedMinutes)} h" +
                                    if (c.ok == null) " (keine Summe in Datei)" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (r.rawRows.isNotEmpty()) {
                    Text(
                        "${r.rawRows.size} Ereignisse aus Rohdaten-Export, davon ${p.existingRaw} schon vorhanden " +
                            "(werden übersprungen).",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (r.skippedLines.isNotEmpty()) {
                    HorizontalDivider()
                    SmallLabel("Nicht verstandene Zeilen (${r.skippedLines.size})")
                    r.skippedLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val db = StampDb.get(context)
                if (r.days.isNotEmpty()) db.upsertImported(r.days, p.fileName)
                val added = if (r.rawRows.isNotEmpty()) db.restoreRaw(r.rawRows) else 0
                Toast.makeText(
                    context,
                    "Importiert: ${r.days.size} Tage" + if (r.rawRows.isNotEmpty()) ", $added Ereignisse" else "",
                    Toast.LENGTH_LONG,
                ).show()
                onDismiss()
            }) { Text("Importieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
