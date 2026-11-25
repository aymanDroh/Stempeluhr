package de.droh.stempeluhr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.CsvExport
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.BackupStore
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.data.StampDb
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.geo.GeofenceManager
import de.droh.stempeluhr.service.MonitorService
import de.droh.stempeluhr.tile.StampTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Einrichtung
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.SetupPage(resumeCount: Int, settingsVersion: Long) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    // resumeCount/settingsVersion sorgen dafür, dass der Status nach Rückkehr aus den Systemeinstellungen neu geprüft wird.
    val key = resumeCount to settingsVersion
    var refresh by remember { mutableIntStateOf(0) }
    val notifOk = remember(key, refresh) { SetupCheck.notificationsOk(context) }
    val fineOk = remember(key, refresh) { SetupCheck.fineLocationOk(context) }
    val bgOk = remember(key, refresh) { SetupCheck.backgroundLocationOk(context) }
    val batteryOk = remember(key, refresh) { SetupCheck.batteryOk(context) }
    val locOk = remember(key, refresh) { SetupCheck.locationServiceOk(context) }
    val hasNfc = remember { NfcAdapter.getDefaultAdapter(context) != null }
    val nfcOk = remember(key, refresh) { SetupCheck.nfcOk(context) }
    val issues = remember(key, refresh) { SetupCheck.issues(context) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refresh++
        if (!ok) openAppSettings(context)
    }

    val showNotif = Build.VERSION.SDK_INT >= 33
    val checks = listOfNotNull(notifOk.takeIf { showNotif }, fineOk, bgOk, locOk, batteryOk, nfcOk.takeIf { hasNfc })
    val done = checks.count { it }
    val allOk = done == checks.size

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                if (allOk) R.drawable.ic_check_circle_fill else R.drawable.ic_shield,
                if (allOk) App.colors.good else App.colors.warn,
                size = 48.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (allOk) "Alles erlaubt" else "$done von ${checks.size} erledigt",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (allOk) {
                        "Die Erfassung funktioniert ohne Entsperren und ohne Zutun."
                    } else {
                        "Damit die Erfassung ohne Entsperren und ohne Zutun funktioniert, müssen alle Punkte einen Haken haben. " +
                            "Offene Punkte antippen."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ProgressBar(
            done / checks.size.toFloat(),
            if (allOk) App.colors.good else App.colors.warn,
            modifier = Modifier.describedAs("$done von ${checks.size} Punkten erledigt"),
        )
    }

    RowGroup {
        if (showNotif) {
            CheckRow("Benachrichtigungen", "Für die Meldung bei jedem Stempel und für Hinweise.", notifOk, "Erlauben") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            RowDivider()
        }
        CheckRow("Standort (genau)", "Android gibt den WLAN-Namen nur mit Standort-Berechtigung heraus.", fineOk, "Erlauben") {
            fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        RowDivider()
        CheckRow("Standort „Immer erlauben“", "Damit WLAN und Standort auch bei gesperrtem Handy erfasst werden.", bgOk, "Öffnen") {
            if (!fineOk) {
                messenger.show("Bitte zuerst „Standort (genau)“ erlauben.")
            } else {
                messenger.show("Bitte „Immer erlauben“ auswählen.")
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        RowDivider()
        CheckRow("Standortdienst eingeschaltet", "Der Standort (GPS) des Handys muss an sein.", locOk, "Öffnen") {
            context.startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        RowDivider()
        CheckRow("Akku-Optimierung aus", "Sonst beendet Android die Erfassung im Hintergrund.", batteryOk, "Ausschalten") {
            requestBatteryExemption(context)
        }
        if (hasNfc) {
            RowDivider()
            CheckRow("NFC eingeschaltet", "Zum Stempeln mit einem NFC-Aufkleber.", nfcOk, "Öffnen") {
                context.startActivity(Intent(AndroidSettings.ACTION_NFC_SETTINGS))
            }
        }
    }

    if (issues.isNotEmpty()) {
        AppCard(container = App.colors.warnContainer) {
            CardTitle("Noch offen")
            issues.forEach {
                Row {
                    AppIcon(R.drawable.ic_warning, tint = App.colors.warn, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    AppCard {
        CardTitle("Samsung: zusätzlich nötig")
        Text(
            "Einstellungen → Akku → Hintergrundnutzungslimits → „Nie in Standby-Modus versetzte Apps“ → Stempeluhr hinzufügen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Hint(
            "Die dauerhafte Benachrichtigung „Stempeluhr läuft“ hält die Erfassung am Leben. Sie lässt sich in den " +
                "App-Benachrichtigungen ausblenden (Kanal „Hintergrund-Erfassung“).",
        )
        QuietButton(
            "App-Einstellungen öffnen",
            icon = R.drawable.ic_open_in_new,
            onClick = { openAppSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Ein Punkt der Einrichtung. Offene Punkte sind als ganze Zeile antippbar und führen direkt zur passenden Stelle. */
@Composable
private fun CheckRow(title: String, why: String, ok: Boolean, action: String, onAction: () -> Unit) {
    ListRow(
        title = title,
        subtitle = if (ok) "Erledigt · $why" else why,
        icon = if (ok) R.drawable.ic_check_circle_fill else R.drawable.ic_warning_fill,
        iconColor = if (ok) App.colors.good else App.colors.warn,
        onClick = if (ok) null else onAction,
        onClickLabel = action,
    ) {
        if (!ok) Chevron()
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
// WLAN
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.WifiPage(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val ssids = remember(settingsVersion) { settings.wifiSsidSet.toList() }
    var input by rememberSaveable { mutableStateOf("") }

    fun save(names: Collection<String>) {
        settings.wifiSsids = names.joinToString(", ")
    }

    RowGroup {
        SwitchRow(
            "WLAN-Erfassung",
            subtitle = "Kommen beim Verbinden mit dem Arbeits-WLAN, Gehen beim Trennen",
            checked = settings.wifiEnabled,
            onChange = { settings.wifiEnabled = it },
            icon = R.drawable.ic_wifi,
            iconColor = App.colors.info,
        )
    }

    SectionLabel("Arbeits-WLAN")
    RowGroup {
        if (ssids.isEmpty()) {
            ListRow("Noch kein WLAN eingetragen", subtitle = "Mit dem Arbeits-WLAN verbinden und unten übernehmen.")
        }
        ssids.forEachIndexed { i, ssid ->
            if (i > 0) RowDivider(16.dp)
            ListRow(ssid) {
                IconButton(onClick = {
                    save(ssids - ssid)
                    messenger.show("„$ssid“ entfernt", "Rückgängig") { save(settings.wifiSsidSet + ssid) }
                }) { AppIcon(R.drawable.ic_close, "$ssid entfernen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    PrimaryButton(
        "Aktuelles WLAN übernehmen",
        icon = R.drawable.ic_wifi,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            val current = currentSsid(context)
            when {
                current == null -> messenger.show(
                    "WLAN-Name nicht lesbar. Mit dem Arbeits-WLAN verbunden? Standort erlaubt und eingeschaltet?",
                    long = true,
                )
                current in ssids -> messenger.show("„$current“ ist schon eingetragen.")
                else -> {
                    save(ssids + current)
                    messenger.show("„$current“ übernommen.")
                }
            }
        },
    )
    val addTyped = {
        // Mehrere Namen dürfen mit Komma getrennt eingegeben werden.
        save(ssids + input.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in ssids })
        input = ""
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("WLAN-Name eintippen") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) addTyped() }),
        trailingIcon = {
            IconButton(onClick = addTyped, enabled = input.isNotBlank()) { AppIcon(R.drawable.ic_add, "WLAN-Name hinzufügen") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(
        "Funktioniert auch bei gesperrtem Handy. Nötig sind Standort „Immer erlauben“ und ein eingeschalteter " +
            "Standortdienst – Android gibt den WLAN-Namen sonst nicht heraus.",
    )
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun currentSsid(context: Context): String? {
    if (!SetupCheck.fineLocationOk(context)) return null
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    return MonitorService.cleanSsid(wifi.connectionInfo?.ssid)
}

// ---------------------------------------------------------------------------------------------
// Standort
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.GeoPage(settings: Settings, settingsVersion: Long) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val savedLat = remember(settingsVersion) { settings.geoLat }
    val savedLon = remember(settingsVersion) { settings.geoLon }
    var lat by remember(settingsVersion) { mutableStateOf(savedLat?.let { "%.6f".format(Locale.US, it) }.orEmpty()) }
    var lon by remember(settingsVersion) { mutableStateOf(savedLon?.let { "%.6f".format(Locale.US, it) }.orEmpty()) }
    var radius by remember(settingsVersion) { mutableFloatStateOf(settings.geoRadius.toFloat()) }
    var radiusText by remember(settingsVersion) { mutableStateOf(settings.geoRadius.toString()) }
    var locating by remember { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    val status by GeofenceManager.status.collectAsState()

    RowGroup {
        SwitchRow(
            "Standort-Erfassung",
            subtitle = "Kommen beim Betreten des Arbeitsorts, Gehen beim Verlassen",
            checked = settings.geoEnabled,
            onChange = { settings.geoEnabled = it },
            icon = R.drawable.ic_location,
            iconColor = App.colors.good,
        )
    }

    SectionLabel("Arbeitsort")
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(R.drawable.ic_gps, App.colors.good, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (savedLat != null && savedLon != null) {
                        "%.5f, %.5f".format(Locale.US, savedLat, savedLon)
                    } else {
                        "Noch nicht festgelegt"
                    },
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumbers),
                )
                Text("Überwachung: $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PrimaryButton(
            if (locating) "Suche Standort …" else "Aktuellen Standort übernehmen",
            icon = R.drawable.ic_gps,
            enabled = !locating,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                locating = true
                GeofenceManager.currentLocation(context) { loc ->
                    locating = false
                    if (loc == null) {
                        messenger.show("Standort nicht verfügbar. Berechtigung und GPS prüfen.", long = true)
                    } else {
                        settings.geoLat = loc.latitude
                        settings.geoLon = loc.longitude
                        messenger.show("Standort übernommen (Genauigkeit ca. ${loc.accuracy.toInt()} m).")
                    }
                }
            },
        )
        TextButton(onClick = { manual = !manual }) {
            Text(if (manual) "Eingabe von Hand ausblenden" else "Koordinaten und Radius von Hand eingeben")
        }
        AnimatedVisibility(manual, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text("Breitengrad") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text("Längengrad") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Radius in Metern (50–5000)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                TonalButton(
                    "Speichern",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val la = lat.replace(',', '.').toDoubleOrNull()
                        val lo = lon.replace(',', '.').toDoubleOrNull()
                        val r = radiusText.toIntOrNull()
                        if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) {
                            messenger.show("Ungültige Koordinaten – z. B. 51.2277 und 6.7735.", long = true)
                        } else if (r == null || r !in 50..5000) {
                            messenger.show("Der Radius muss zwischen 50 und 5000 Metern liegen.", long = true)
                        } else {
                            settings.geoLat = la
                            settings.geoLon = lo
                            settings.geoRadius = r
                            messenger.show("Arbeitsort gespeichert.")
                        }
                    },
                )
            }
        }
    }

    SectionLabel("Umkreis")
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Radius um den Arbeitsort", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${radius.roundToInt()} m", style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = radius,
            onValueChange = { radius = (it / 10f).roundToInt() * 10f },
            valueRange = 50f..maxOf(1000f, radius),
            onValueChangeFinished = { settings.geoRadius = radius.roundToInt().coerceIn(50, 5000) },
            colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier.describedAs("Radius in Metern"),
        )
        Hint("150 m ist ein guter Startwert. Unter 100 m wird die Erkennung unzuverlässig.")
    }
    Hint("Ungenauer als WLAN (meist 1–5 Minuten), funktioniert dafür auch ohne WLAN.")
}

// ---------------------------------------------------------------------------------------------
// NFC
// ---------------------------------------------------------------------------------------------

// settingsVersion ist nur Schlüssel fürs Neuzeichnen (die Settings-Instanz bleibt gleich).
@Suppress("UNUSED_PARAMETER")
@Composable
fun ColumnScope.NfcPage(
    settings: Settings,
    settingsVersion: Long,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
) {
    RowGroup {
        SwitchRow(
            "NFC-Stempeln",
            subtitle = "Sekundengenau – das Handy muss dafür entsperrt sein",
            checked = settings.nfcEnabled,
            onChange = { settings.nfcEnabled = it },
            icon = R.drawable.ic_nfc,
            iconColor = App.colors.violet,
        )
    }

    SectionLabel("Tag beschreiben")
    RowGroup {
        ListRow(
            "Umschalten",
            subtitle = "Ein Tag für beides: abwechselnd Kommen und Gehen",
            icon = R.drawable.ic_sync,
            iconColor = App.colors.violet,
            onClick = { onStartTagWrite(StampEngine.Mode.TOGGLE) },
        ) { Chevron() }
        RowDivider()
        ListRow(
            "Nur Kommen",
            subtitle = "Eigener Tag für den Eingang",
            icon = R.drawable.ic_in,
            iconColor = App.colors.good,
            onClick = { onStartTagWrite(StampEngine.Mode.IN) },
        ) { Chevron() }
        RowDivider()
        ListRow(
            "Nur Gehen",
            subtitle = "Eigener Tag für den Ausgang",
            icon = R.drawable.ic_out,
            iconColor = App.colors.bad,
            onClick = { onStartTagWrite(StampEngine.Mode.OUT) },
        ) { Chevron() }
    }
    Hint(
        "Einen NFC-Aufkleber (NTAG213/215) neben die Stechuhr kleben und hier beschreiben. Danach genügt es, das entsperrte " +
            "Handy an den Tag zu halten – die App muss nicht geöffnet sein.",
    )

    if (tagWriteMode != null) {
        AlertDialog(
            onDismissRequest = onCancelTagWrite,
            icon = { IconBubble(R.drawable.ic_nfc, App.colors.violet, size = 56.dp) },
            title = { Text("Tag an die Rückseite halten", textAlign = TextAlign.Center) },
            text = {
                val label = when (tagWriteMode) {
                    StampEngine.Mode.TOGGLE -> "Umschalten (Kommen und Gehen abwechselnd)"
                    StampEngine.Mode.IN -> "nur Kommen"
                    StampEngine.Mode.OUT -> "nur Gehen"
                }
                Text(
                    "Der NFC-Tag wird beschrieben, sobald er die Rückseite des Handys berührt.\n\nModus: $label",
                    textAlign = TextAlign.Center,
                )
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelTagWrite) { Text("Abbrechen") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Kachel & manuell
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.ManualPage() {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(R.drawable.ic_tile, App.colors.warn, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            CardTitle("Schnellkachel „Stempeln“")
        }
        Text(
            "Ein Tipp in den Schnelleinstellungen stempelt Kommen bzw. Gehen – auch auf dem Sperrbildschirm, " +
                "ohne die App zu öffnen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            PrimaryButton(
                "Kachel hinzufügen",
                icon = R.drawable.ic_add,
                modifier = Modifier.fillMaxWidth(),
                onClick = { requestTile(context, messenger) },
            )
        }
        Hint("Von Hand: Schnelleinstellungen aufziehen → Stift/Bearbeiten → Kachel „Stempeln“ nach oben ziehen.")
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(R.drawable.ic_manual, App.colors.warn, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            CardTitle("In der App")
        }
        Text(
            "Auf „Heute“ stehen immer beide Knöpfe bereit: Kommen und Gehen. Der naheliegende ist hervorgehoben. " +
                "Vergessene Zeiten lassen sich mit „Nachtragen“ ergänzen, vorhandene durch Antippen ändern.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(R.drawable.ic_bolt, App.colors.warn, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            CardTitle("Tipp: Seitentaste")
        }
        Text(
            "Samsung: Einstellungen → Erweiterte Funktionen → Seitentaste → „Zweimal drücken“ → App öffnen → Stempeluhr.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fragt Android, die Kachel direkt in die Schnelleinstellungen zu legen (ab Android 13). */
@RequiresApi(33)
private fun requestTile(context: Context, messenger: Messenger) {
    val manager = context.getSystemService(StatusBarManager::class.java)
    if (manager == null) {
        messenger.show("Die Kachel lässt sich hier nur von Hand hinzufügen.")
        return
    }
    try {
        manager.requestAddTileService(
            ComponentName(context, StampTileService::class.java),
            "Stempeln",
            Icon.createWithResource(context, R.drawable.ic_stamp),
            context.mainExecutor,
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> messenger.show("Kachel hinzugefügt.")
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> messenger.show("Die Kachel ist schon vorhanden.")
            }
        }
    } catch (_: Exception) {
        messenger.show("Die Kachel lässt sich hier nur von Hand hinzufügen.")
    }
}

// ---------------------------------------------------------------------------------------------
// Arbeitszeit & Wertung
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.RulesPage(settings: Settings, settingsVersion: Long) {
    val target = remember(settingsVersion) { settings.targetMinutes }
    val delay = remember(settingsVersion) { settings.leaveDelayMinutes }
    val priority = remember(settingsVersion) { settings.priority }
    var editTarget by remember { mutableStateOf(false) }
    var editDelay by remember { mutableStateOf(false) }

    RowGroup {
        ListRow(
            "Soll-Arbeitszeit pro Tag",
            subtitle = "Für den Saldo und den Ring auf „Heute“",
            icon = R.drawable.ic_flag,
            onClick = { editTarget = true },
        ) { RowValue("${TimeFormat.hm(target.toLong())} h") }
        RowDivider()
        ListRow(
            "Verzögerung fürs Gehen",
            subtitle = "So lange müssen WLAN oder Standort weg sein, bevor ein Gehen gespeichert wird",
            icon = R.drawable.ic_timer,
            onClick = { editDelay = true },
        ) { RowValue("$delay min") }
        RowDivider()
        SwitchRow(
            "Benachrichtigung bei jedem Stempel",
            checked = settings.notifyOnStamp,
            onChange = { settings.notifyOnStamp = it },
            icon = R.drawable.ic_notifications,
        )
    }

    SectionLabel("Welche Quelle zählt für Kommen und Gehen?")
    RowGroup {
        priority.forEachIndexed { index, src ->
            if (index > 0) RowDivider()
            ListRow("${index + 1}. ${sourceName(src)}", icon = sourceIcon(src), iconColor = sourceColor(src)) {
                IconButton(enabled = index > 0, onClick = {
                    settings.priority = priority.toMutableList().apply { add(index - 1, removeAt(index)) }
                }) { AppIcon(R.drawable.ic_arrow_up, "${sourceName(src)} nach oben") }
                IconButton(enabled = index < priority.lastIndex, onClick = {
                    settings.priority = priority.toMutableList().apply { add(index + 1, removeAt(index)) }
                }) { AppIcon(R.drawable.ic_arrow_down, "${sourceName(src)} nach unten") }
            }
        }
    }
    Hint(
        "Für jeden Tag zählt die erste Quelle in dieser Liste, die eine Zeit hat. Alle Quellen bleiben trotzdem " +
            "gespeichert und sichtbar.",
    )

    if (editTarget) {
        DurationDialog(
            title = "Soll-Arbeitszeit pro Tag",
            minutes = target,
            range = 0..24 * 60,
            onDismiss = { editTarget = false },
            onSave = { settings.targetMinutes = it },
        )
    }
    if (editDelay) {
        NumberDialog(
            title = "Verzögerung fürs Gehen",
            text = "Kurze WLAN- oder GPS-Aussetzer werden so überbrückt. Das Gehen erhält trotzdem den Zeitpunkt, " +
                "an dem die Verbindung zuerst weg war.",
            value = delay,
            range = 0..120,
            unit = "min",
            onDismiss = { editDelay = false },
            onSave = { settings.leaveDelayMinutes = it },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Werkstudent
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.StudentPage(settings: Settings, settingsVersion: Long) {
    val weeks = remember(settingsVersion) { settings.studentLimitWeeks }
    val minutes = remember(settingsVersion) { settings.studentLimitMinutes }
    var editWeeks by remember { mutableStateOf(false) }
    var editHours by remember { mutableStateOf(false) }

    RowGroup {
        SwitchRow(
            "Auf „Heute“ anzeigen",
            subtitle = "Stand der Woche und freie Wochen in der Karte „Diese Woche“",
            checked = settings.studentEnabled,
            onChange = { settings.studentEnabled = it },
            icon = R.drawable.ic_school,
        )
        RowDivider()
        ListRow(
            "Stundengrenze pro Woche",
            subtitle = "Wochen mit mehr Stunden zählen",
            icon = R.drawable.ic_timer,
            onClick = { editHours = true },
        ) { RowValue("${TimeFormat.hm(minutes.toLong())} h") }
        RowDivider()
        ListRow(
            "Erlaubte Wochen über der Grenze",
            subtitle = "Innerhalb von 52 Wochen",
            icon = R.drawable.ic_weeks,
            onClick = { editWeeks = true },
        ) { RowValue("$weeks") }
    }
    Hint(
        "Gezählt wird rollierend über die letzten 52 Kalenderwochen (Mo–So), in wie vielen Wochen die Brutto-Dauer über der " +
            "Grenze lag – aus eigener Erfassung und Import, auch in den Semesterferien.",
    )

    if (editHours) {
        DurationDialog(
            title = "Stundengrenze pro Woche",
            minutes = minutes,
            range = 60..7 * 24 * 60,
            onDismiss = { editHours = false },
            onSave = { settings.studentLimitMinutes = it },
        )
    }
    if (editWeeks) {
        NumberDialog(
            title = "Erlaubte Wochen über der Grenze",
            text = "Üblich sind 26 Wochen innerhalb von 12 Monaten.",
            value = weeks,
            range = 1..52,
            unit = "Wochen",
            onDismiss = { editWeeks = false },
            onSave = { settings.studentLimitWeeks = it },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Auf neues Handy übertragen
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.TransferPage(importer: ImportController, onOpenExport: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val scope = rememberCoroutineScope()
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    var working by remember { mutableStateOf(false) }
    // Vorbelegung: Ein Handy ohne Daten ist vermutlich das neue.
    var isNew by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val newPhone = isNew ?: (events.isEmpty() && imported.isEmpty())

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { BackupStore.write(context, uri, BackupStore.snapshot(context)) }
                    messenger.show("Sicherung gespeichert.")
                } catch (e: Exception) {
                    messenger.show("Speichern fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}", long = true)
                } finally {
                    working = false
                }
            }
        }
    }

    AppCard(container = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(R.drawable.ic_phone_send, MaterialTheme.colorScheme.primary, size = 48.dp, container = MaterialTheme.colorScheme.surfaceContainerLow)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Alles mitnehmen", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Eine Sicherung enthält alle Ereignisse, die importierten Stundenzettel und die Einstellungen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }

    SectionLabel("Dieses Handy ist …")
    SegmentedChoice(
        options = listOf(false, true),
        selected = newPhone,
        onSelect = { isNew = it },
        label = { if (it) "das neue" else "das alte" },
        icon = { if (it) R.drawable.ic_phone_receive else R.drawable.ic_phone_send },
    )

    if (!newPhone) {
        AppCard {
            CardTitle("Sicherung senden")
            Text(
                "Enthält ${TimeFormat.plural(events.size, "Ereignis", "Ereignisse")}, " +
                    "${TimeFormat.plural(imported.size, "importierten Tag", "importierte Tage")} und die Einstellungen dieses Handys.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(
                if (working) "Erstelle Sicherung …" else "Sicherung senden",
                icon = R.drawable.ic_share,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            val intent = withContext(Dispatchers.IO) { BackupStore.shareIntent(context, BackupStore.snapshot(context)) }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            messenger.show("Senden fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}", long = true)
                        } finally {
                            working = false
                        }
                    }
                },
            )
            QuietButton(
                "Als Datei speichern",
                icon = R.drawable.ic_download,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                onClick = { saveLauncher.launch(de.droh.stempeluhr.core.Backup.fileName(LocalDate.now())) },
            )
            Hint("Am einfachsten: im Teilen-Dialog „Quick Share“ wählen und das neue Handy antippen.")
        }
    } else {
        AppCard {
            CardTitle("Sicherung einlesen")
            Text(
                "Die empfangene Datei antippen und „Stempeluhr“ wählen – oder sie hier auswählen. " +
                    "Vor dem Übernehmen siehst du genau, was dazukommt.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(
                if (importer.busy) "Lese Datei …" else "Sicherung auswählen",
                icon = R.drawable.ic_file_open,
                enabled = !importer.busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = importer::pick,
            )
            Hint("Quick Share legt empfangene Dateien unter „Downloads → Quick Share“ ab.")
        }
    }

    SectionLabel("Gut zu wissen")
    AppCard(spacing = 12.dp) {
        Fact(R.drawable.ic_shield, "Nichts geht verloren", "Einlesen ergänzt nur. Was auf dem Handy schon erfasst ist, bleibt; doppelte Einträge werden übersprungen. Die Sicherung kann beliebig oft eingelesen werden.")
        Fact(R.drawable.ic_settings, "Berechtigungen neu erteilen", "Standort, Benachrichtigungen und Akku-Ausnahme gelten nur für ein Handy. Auf dem neuen bitte unter „Einrichtung“ prüfen.")
        Fact(R.drawable.ic_info, "Nur an eigene Geräte senden", "Die Datei enthält deine Arbeitszeiten, den WLAN-Namen und den Arbeitsort.")
    }
    AppCard {
        CardTitle("Altes Handy hat noch die alte App-Version?")
        Text(
            "Dann gibt es dort diese Seite noch nicht. Zwei Wege:\n" +
                "1. Auf dem alten Handy die App aktualisieren und danach die Sicherung senden.\n" +
                "2. Oder dort unter Optionen → Export die „Rohdaten“ speichern und die CSV-Datei hier unter " +
                "„Sicherung auswählen“ einlesen. Das überträgt alle Ereignisse – Einstellungen und importierte " +
                "Stundenzettel kommen nur mit der Sicherung herüber.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenExport) { Text("Zum Export auf diesem Handy") }
    }
}

@Composable
private fun Fact(@DrawableRes icon: Int, title: String, text: String) {
    Row {
        AppIcon(icon, tint = MaterialTheme.colorScheme.primary, size = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stundenzettel importieren
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.ImportPage(
    settings: Settings,
    settingsVersion: Long,
    importer: ImportController,
    onShowReconcile: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val imported by rememberImportedDays()
    val tolerance = remember(settingsVersion) { settings.reconcileToleranceMinutes }
    var editTolerance by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    AppCard {
        Text(
            "Stundenzettel der Firma als PDF oder CSV (Datum und Dauer je Tag, optional eine Bemerkung wie „krank“). " +
                "Ein erneuter Import ersetzt Tage mit gleichem Datum.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            if (importer.busy) "Lese Datei …" else "Datei auswählen",
            icon = R.drawable.ic_upload,
            enabled = !importer.busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = importer::pick,
        )
        Hint(
            if (imported.isEmpty()) {
                "Noch keine importierten Tage. Vor dem Speichern zeigt eine Vorschau, was gelesen wurde."
            } else {
                "${TimeFormat.plural(imported.size, "importierter Tag", "importierte Tage")} " +
                    "(${TimeFormat.date(imported.first().date)} – ${TimeFormat.date(imported.last().date)}), " +
                    "Summe ${TimeFormat.hm(imported.sumOf { it.minutes })} h"
            },
        )
    }

    SectionLabel("Wertung")
    RowGroup {
        SwitchRow(
            "Bei Überschneidung zählt der Import",
            subtitle = "Sonst zählt die eigene Erfassung",
            checked = settings.preferImport,
            onChange = { settings.preferImport = it },
        )
        RowDivider(16.dp)
        SwitchRow(
            "Krank- und Urlaubstage mitzählen",
            subtitle = "Mit der Dauer aus dem Stundenzettel",
            checked = settings.countAbsence,
            onChange = { settings.countAbsence = it },
        )
        RowDivider(16.dp)
        ListRow(
            "Toleranz beim Abgleich",
            subtitle = "Bis zu dieser Abweichung gilt ein Tag als übereinstimmend",
            onClick = { editTolerance = true },
        ) { RowValue("±$tolerance min") }
    }

    if (imported.isNotEmpty()) {
        SectionLabel("Importierte Daten")
        RowGroup {
            ListRow(
                "Abgleiche ansehen",
                subtitle = "Auch bereits bestätigte Monate",
                icon = R.drawable.ic_reconcile,
                onClick = { onShowReconcile(true) },
            ) { Chevron() }
            RowDivider()
            ListRow(
                "Alle importierten Tage löschen",
                subtitle = "Eigene Erfassungen bleiben erhalten",
                icon = R.drawable.ic_delete,
                iconColor = MaterialTheme.colorScheme.error,
                onClick = { confirmDeleteAll = true },
            )
        }
    }

    if (editTolerance) {
        NumberDialog(
            title = "Toleranz beim Abgleich",
            text = "Gleicht Rundungen der Firma aus. Abweichungen bis zu diesem Wert gelten als „stimmt“.",
            value = tolerance,
            range = 0..120,
            unit = "min",
            onDismiss = { editTolerance = false },
            onSave = { settings.reconcileToleranceMinutes = it },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Alle importierten Tage löschen?") },
            text = {
                Text(
                    "${TimeFormat.plural(imported.size, "importierter Tag wird", "importierte Tage werden")} entfernt. " +
                        "Eigene Erfassungen (WLAN, Standort, NFC, manuell) bleiben erhalten.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val removed = imported
                    StampDb.get(context).deleteAllImported()
                    confirmDeleteAll = false
                    messenger.show("Importierte Tage gelöscht", "Rückgängig") {
                        StampDb.get(context).upsertImported(removed, null)
                    }
                }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") } },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------------------------

@Composable
fun ColumnScope.ExportPage(settings: Settings) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    val today = LocalDate.now(zone)

    fun rawCsv(): String = CsvExport.rawEvents(StampDb.get(context).all(includeDeleted = true), zone)
    fun dailyCsv(): String {
        val db = StampDb.get(context)
        val days = Summary.byDay(db.all(includeDeleted = false), zone, settings.priority, db.importedDays(), settings.evalOptions)
        return CsvExport.daily(days, settings.targetMinutes, zone)
    }

    fun write(uri: Uri, text: String) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            messenger.show("Export gespeichert.")
        } catch (e: Exception) {
            messenger.show("Export fehlgeschlagen: ${e.message}", long = true)
        }
    }

    fun share(name: String, text: String) {
        try {
            context.startActivity(BackupStore.shareTextFile(context, name, "text/csv", text, "Export senden"))
        } catch (e: Exception) {
            messenger.show("Senden fehlgeschlagen: ${e.message}", long = true)
        }
    }

    val rawLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) write(uri, rawCsv())
    }
    val dailyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) write(uri, dailyCsv())
    }

    ExportCard(
        icon = R.drawable.ic_database,
        title = "Rohdaten",
        text = "Jedes einzelne Ereignis mit Quelle – inklusive geänderter und gelöschter Einträge.",
        onSave = { rawLauncher.launch("Stempeluhr-Rohdaten-$today.csv") },
        onShare = { share("Stempeluhr-Rohdaten-$today.csv", rawCsv()) },
    )
    ExportCard(
        icon = R.drawable.ic_table,
        title = "Tagesübersicht",
        text = "Je Tag die gewerteten Zeiten, Dauer und Saldo sowie die Zeiten jeder Quelle.",
        onSave = { dailyLauncher.launch("Stempeluhr-Tage-$today.csv") },
        onShare = { share("Stempeluhr-Tage-$today.csv", dailyCsv()) },
    )
    Hint("CSV für Excel: Semikolon als Trenner, Dezimalkomma, Umlaute bleiben erhalten.")
    Hint(
        "Für den Wechsel auf ein anderes Handy ist „Auf neues Handy übertragen“ besser: Die Sicherung nimmt auch " +
            "Einstellungen und importierte Stundenzettel mit.",
        icon = R.drawable.ic_phone_send,
    )
}

@Composable
private fun ExportCard(@DrawableRes icon: Int, title: String, text: String, onSave: () -> Unit, onShare: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, MaterialTheme.colorScheme.primary, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                CardTitle(title)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalButton("Speichern", icon = R.drawable.ic_download, onClick = onSave, modifier = Modifier.weight(1f))
            QuietButton("Senden", icon = R.drawable.ic_share, onClick = onShare, modifier = Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Eingabe-Dialoge für Zahlen und Dauern
// ---------------------------------------------------------------------------------------------

/** Ganze Zahl mit Minus/Plus wählen. */
@Composable
fun NumberDialog(
    title: String,
    text: String,
    value: Int,
    range: IntRange,
    unit: String,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var current by remember { mutableIntStateOf(value.coerceIn(range)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text)
                Stepper(
                    value = "$current $unit",
                    onMinus = { current = (current - 1).coerceIn(range) },
                    onPlus = { current = (current + 1).coerceIn(range) },
                    canMinus = current > range.first,
                    canPlus = current < range.last,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(current)
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Dauer in Stunden und Minuten eingeben. */
@Composable
fun DurationDialog(title: String, minutes: Int, range: IntRange, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var hours by remember { mutableStateOf((minutes / 60).toString()) }
    var mins by remember { mutableStateOf((minutes % 60).toString().padStart(2, '0')) }
    val h = hours.toIntOrNull()
    val m = mins.toIntOrNull()
    val total = if (h != null && m != null && m in 0..59) h * 60 + m else null
    val valid = total != null && total in range
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(3) },
                        label = { Text("Stunden") },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = mins,
                        onValueChange = { mins = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minuten") },
                        singleLine = true,
                        isError = !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (valid) {
                        "Erlaubt: ${TimeFormat.hm(range.first.toLong())} bis ${TimeFormat.hm(range.last.toLong())} h"
                    } else {
                        "Bitte einen Wert von ${TimeFormat.hm(range.first.toLong())} bis ${TimeFormat.hm(range.last.toLong())} h " +
                            "eingeben (Minuten 0–59)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(total!!)
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
