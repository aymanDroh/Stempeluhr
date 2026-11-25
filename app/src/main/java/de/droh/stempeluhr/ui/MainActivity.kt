package de.droh.stempeluhr.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.engine.Notifications
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.nfc.NfcTags
import de.droh.stempeluhr.service.MonitorService
import java.time.YearMonth

class MainActivity : ComponentActivity() {

    /** Zählt bei jedem onResume hoch, damit Berechtigungs-Status neu geprüft werden. */
    private val resumeCount = mutableIntStateOf(0)

    /** Gesetzt, solange auf einen NFC-Tag zum Beschreiben gewartet wird. */
    private val tagWriteMode = mutableStateOf<StampEngine.Mode?>(null)

    /** Datei, die über „Öffnen mit“ oder „Teilen“ an die App gegeben wurde (Sicherung vom alten Handy). */
    private val incomingFile = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
        // Nur beim ersten Start auswerten – nach einer Drehung ist die Datei schon in Arbeit.
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            val (settings, _) = rememberSettings()
            val dark = isAppDark(settings.themeMode)
            // Symbole der Systemleisten an das gewählte Design anpassen (nicht nur an das des Handys).
            DisposableEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            StempelTheme(dark) {
                AppRoot(
                    resumeCount = resumeCount.intValue,
                    tagWriteMode = tagWriteMode.value,
                    onStartTagWrite = ::startTagWrite,
                    onCancelTagWrite = ::cancelTagWrite,
                    incomingFile = incomingFile.value,
                    onIncomingHandled = { incomingFile.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) incomingFile.value = uri
    }

    override fun onResume() {
        super.onResume()
        StampEngine.tick(this)
        MonitorService.start(this)
        resumeCount.intValue++
        if (tagWriteMode.value != null) enableReader()
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun startTagWrite(mode: StampEngine.Mode) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "NFC ist nicht verfügbar oder ausgeschaltet.", Toast.LENGTH_LONG).show()
            return
        }
        tagWriteMode.value = mode
        enableReader()
    }

    private fun cancelTagWrite() {
        tagWriteMode.value = null
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun enableReader() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        adapter.enableReaderMode(this, { tag ->
            val mode = tagWriteMode.value ?: return@enableReaderMode
            val error = NfcTags.write(tag, NfcTags.message(packageName, mode))
            runOnUiThread {
                // Bewusst als Toast: Der Hinweis muss auch über dem geöffneten Dialog sichtbar sein.
                if (error == null) {
                    Toast.makeText(this, "Tag beschrieben – ab jetzt einfach mit entsperrtem Handy scannen.", Toast.LENGTH_LONG).show()
                    cancelTagWrite()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
            }
        }, flags, null)
    }
}

private enum class Tab(val label: String, @DrawableRes val icon: Int, @DrawableRes val iconSelected: Int) {
    HEUTE("Heute", R.drawable.ic_today, R.drawable.ic_today_fill),
    VERLAUF("Verlauf", R.drawable.ic_history, R.drawable.ic_history_fill),
    WOCHEN("Wochen", R.drawable.ic_weeks, R.drawable.ic_weeks_fill),
    ABGLEICH("Abgleich", R.drawable.ic_reconcile, R.drawable.ic_reconcile_fill),
    OPTIONEN("Optionen", R.drawable.ic_settings, R.drawable.ic_settings_fill),
}

@Composable
private fun AppRoot(
    resumeCount: Int,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
    incomingFile: Uri?,
    onIncomingHandled: () -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger = remember { Messenger(snackbars, scope) }
    CompositionLocalProvider(LocalMessenger provides messenger) {
        // Die Oberfläche erscheint, sobald die Daten geladen sind (wenige Millisekunden).
        ProvideAppData {
            AppContent(snackbars, resumeCount, tagWriteMode, onStartTagWrite, onCancelTagWrite, incomingFile, onIncomingHandled)
        }
    }
}

/** Tabs, Navigation und Rückmeldungen. */
@Composable
private fun AppContent(
    snackbars: SnackbarHostState,
    resumeCount: Int,
    tagWriteMode: StampEngine.Mode?,
    onStartTagWrite: (StampEngine.Mode) -> Unit,
    onCancelTagWrite: () -> Unit,
    incomingFile: Uri?,
    onIncomingHandled: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HEUTE) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var reconcileMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var showConfirmed by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val (settings, settingsVersion) = rememberSettings()
    val openMonths = rememberOpenReconcileMonths()
    // Abgleich-Tab nur zeigen, solange es offene Monate gibt (oder er gerade geöffnet ist).
    val showReconcileTab = openMonths.isNotEmpty() || tab == Tab.ABGLEICH
    val tabs = Tab.entries.filter { it != Tab.ABGLEICH || showReconcileTab }
    val importer = rememberImportController()

    fun openSettings(page: SettingsPage) {
        settingsPage = page
        tab = Tab.OPTIONEN
    }

    fun openReconcile(confirmed: Boolean, month: YearMonth? = null) {
        if (month != null) reconcileMonth = month.toString()
        showConfirmed = confirmed
        tab = Tab.ABGLEICH
    }

    // Ändert sich die automatische Erfassung (WLAN-Namen, Arbeitsort, an/aus) – egal ob in den Optionen oder durch
    // eine eingelesene Sicherung –, werden Hintergrunddienst und Geofence neu eingerichtet.
    val captureSetup = remember(settingsVersion) {
        listOf(settings.wifiEnabled, settings.wifiSsids, settings.geoEnabled, settings.geoLat, settings.geoLon, settings.geoRadius)
    }
    LaunchedEffect(captureSetup) { MonitorService.start(context) }

    // Datei von außen (Sicherung antippen / teilen): direkt die Vorschau öffnen.
    LaunchedEffect(incomingFile) {
        if (incomingFile != null) {
            importer.open(incomingFile)
            onIncomingHandled()
        }
    }

    // Zurück: erst aus der Unterseite, dann zum Start-Tab, erst dann schließt die App.
    val inSubPage = tab == Tab.OPTIONEN && settingsPage != SettingsPage.HOME
    BackHandler(enabled = tab != Tab.HEUTE && !inSubPage) { tab = Tab.HEUTE }
    BackHandler(enabled = inSubPage) { settingsPage = SettingsPage.HOME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = MaterialTheme.shapes.medium,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        },
        bottomBar = {
            // Die Beschriftung wächst mit der Schriftgröße des Handys – aber nur so weit, wie das längste Wort
            // („Optionen“) noch in einen Tab passt. Sonst würden die Wörter abgeschnitten.
            val density = LocalDensity.current
            val tabWidth = LocalConfiguration.current.screenWidthDp / tabs.size.toFloat()
            val labelSize = minOf(12f * density.fontScale.coerceAtMost(1.15f), (tabWidth - 8f) / 5.1f)
            val labelStyle = MaterialTheme.typography.labelMedium.copy(
                fontSize = with(density) { labelSize.dp.toSp() },
                letterSpacing = 0.sp,
            )
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val selected = tab == t
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (t == Tab.ABGLEICH) showConfirmed = false
                                // Erneutes Antippen von „Optionen“ führt zurück zur Übersicht.
                                if (t == Tab.OPTIONEN && selected) settingsPage = SettingsPage.HOME
                                tab = t
                            },
                            icon = {
                                if (t == Tab.ABGLEICH && openMonths.isNotEmpty()) {
                                    val open = TimeFormat.plural(openMonths.size, "offener Monat", "offene Monate")
                                    BadgedBox(
                                        badge = { Badge { Text("${openMonths.size}") } },
                                        modifier = Modifier.semantics { contentDescription = open },
                                    ) { AppIcon(if (selected) t.iconSelected else t.icon) }
                                } else {
                                    AppIcon(if (selected) t.iconSelected else t.icon)
                                }
                            },
                            label = { Text(t.label, maxLines = 1, softWrap = false, style = labelStyle) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { w -> if (forward) w / 10 else -w / 10 }) togetherWith
                    fadeOut(tween(120))
            },
            label = "tabs",
            modifier = Modifier.padding(padding),
        ) { current ->
            val modifier = Modifier
            when (current) {
                Tab.HEUTE -> TodayScreen(
                    modifier,
                    resumeCount,
                    onOpenSetup = { openSettings(SettingsPage.SETUP) },
                    onOpenSource = { source ->
                        openSettings(
                            when (source) {
                                StampSource.WLAN -> SettingsPage.WIFI
                                StampSource.GEOFENCE -> SettingsPage.GEO
                                StampSource.NFC -> SettingsPage.NFC
                                StampSource.MANUELL -> SettingsPage.MANUAL
                            },
                        )
                    },
                    onOpenWeeks = { tab = Tab.WOCHEN },
                    onOpenReconcile = { openReconcile(confirmed = false) },
                )
                Tab.VERLAUF -> HistoryScreen(modifier)
                Tab.WOCHEN -> WeeksScreen(modifier)
                Tab.ABGLEICH -> ReconcileScreen(
                    modifier,
                    month = reconcileMonth?.let { YearMonth.parse(it) },
                    showConfirmed = showConfirmed,
                    onShowConfirmedChange = { showConfirmed = it },
                    onMonthChange = { reconcileMonth = it?.toString() },
                    onImport = { openSettings(SettingsPage.IMPORT) },
                    onDone = { tab = Tab.HEUTE },
                )
                Tab.OPTIONEN -> SettingsScreen(
                    modifier,
                    page = settingsPage,
                    onPage = { settingsPage = it },
                    resumeCount = resumeCount,
                    tagWriteMode = tagWriteMode,
                    onStartTagWrite = onStartTagWrite,
                    onCancelTagWrite = onCancelTagWrite,
                    importer = importer,
                    onShowReconcile = { confirmed -> openReconcile(confirmed) },
                )
            }
        }
    }

    ImportHost(
        controller = importer,
        onTimesImported = { month -> openReconcile(confirmed = false, month = month) },
        onOpenSetup = { openSettings(SettingsPage.SETUP) },
        onOpenHistory = { tab = Tab.VERLAUF },
    )
}
