package de.droh.stempeluhr.ui

import android.graphics.Color as AndroidColor
import android.os.Bundle
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
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.engine.Notifications
import de.droh.stempeluhr.engine.StampEngine
import de.droh.stempeluhr.service.MonitorService
import java.time.YearMonth

class MainActivity : ComponentActivity() {

    /** Zählt bei jedem onResume hoch, damit Berechtigungs-Status neu geprüft werden. */
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.createChannels(this)
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
                AppRoot(resumeCount = resumeCount.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StampEngine.tick(this)
        MonitorService.start(this)
        resumeCount.intValue++
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
private fun AppRoot(resumeCount: Int) {
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val messenger = remember { Messenger(snackbars, scope) }
    CompositionLocalProvider(LocalMessenger provides messenger) {
        // Die Oberfläche erscheint, sobald die Daten geladen sind (wenige Millisekunden).
        ProvideAppData {
            AppContent(snackbars, resumeCount)
        }
    }
}

/** Tabs, Navigation und Rückmeldungen. */
@Composable
private fun AppContent(snackbars: SnackbarHostState, resumeCount: Int) {
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

    // Ändert sich die automatische Erfassung (WLAN-Namen, Arbeitsort, an/aus), werden Hintergrunddienst und
    // Geofence neu eingerichtet.
    val captureSetup = remember(settingsVersion) {
        listOf(settings.wifiEnabled, settings.wifiSsids, settings.geoEnabled, settings.geoLat, settings.geoLon, settings.geoRadius)
    }
    LaunchedEffect(captureSetup) { MonitorService.start(context) }

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
                                // NFC wird nicht mehr erfasst; die Kachel gibt es auf „Heute“ nicht mehr.
                                StampSource.NFC, StampSource.MANUELL -> SettingsPage.MANUAL
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
                    importer = importer,
                    onShowReconcile = { confirmed -> openReconcile(confirmed) },
                )
            }
        }
    }

    ImportHost(
        controller = importer,
        onTimesImported = { month -> openReconcile(confirmed = false, month = month) },
    )
}
