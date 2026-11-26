package de.droh.stempeluhr.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.Settings

/** Prüft, was für die automatische Erfassung noch fehlt. */
object SetupCheck {
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsOk(c: Context) = Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)
    fun fineLocationOk(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocationOk(c: Context) = granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun batteryOk(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    fun locationServiceOk(c: Context) = c.getSystemService(LocationManager::class.java).isLocationEnabled

    fun issues(c: Context): List<String> {
        val s = Settings.get(c)
        val auto = s.wifiEnabled || s.geoEnabled
        return buildList {
            if (!s.wifiEnabled && !s.geoEnabled) add("Keine automatische Erfassung eingeschaltet")
            if (s.wifiEnabled && s.wifiSsidSet.isEmpty()) add("Kein Arbeits-WLAN eingetragen")
            if (s.geoEnabled && (s.geoLat == null || s.geoLon == null)) add("Kein Arbeitsort festgelegt")
            if (auto && !fineLocationOk(c)) add("Standort-Berechtigung fehlt")
            if (auto && !backgroundLocationOk(c)) add("Standort „Immer erlauben“ fehlt")
            if (auto && !locationServiceOk(c)) add("Standortdienst (GPS) ist ausgeschaltet")
            if (auto && !batteryOk(c)) add("Akku-Optimierung ist noch aktiv")
        }
    }
}

/** Seiten innerhalb des Tabs „Optionen“. */
enum class SettingsPage(val title: String) {
    HOME("Optionen"),
    SETUP("Einrichtung"),
    WIFI("WLAN"),
    GEO("Standort"),
    MANUAL("Kachel & manuell"),
    RULES("Arbeitszeit & Wertung"),
    STUDENT("Werkstudent"),
    IMPORT("Stundenzettel importieren"),
    EXPORT("Export"),
}

@Composable
fun SettingsScreen(
    modifier: Modifier,
    page: SettingsPage,
    onPage: (SettingsPage) -> Unit,
    resumeCount: Int,
    importer: ImportController,
    onShowReconcile: (showConfirmed: Boolean) -> Unit,
) {
    val (settings, settingsVersion) = rememberSettings()
    val back = { onPage(SettingsPage.HOME) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // Hinein nach rechts, zurück nach links – so bleibt klar, wo man ist.
            val forward = targetState != SettingsPage.HOME
            (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { w -> if (forward) w / 8 else -w / 8 }) togetherWith fadeOut(tween(120))
        },
        label = "settingsPage",
        modifier = modifier,
    ) { current ->
        when (current) {
            SettingsPage.HOME -> SettingsHome(settings, settingsVersion, resumeCount, onPage, onShowReconcile)
            SettingsPage.SETUP -> SubPage(current, back) { SetupPage(resumeCount, settingsVersion) }
            SettingsPage.WIFI -> SubPage(current, back) { WifiPage(settings, settingsVersion) }
            SettingsPage.GEO -> SubPage(current, back) { GeoPage(settings, settingsVersion) }
            SettingsPage.MANUAL -> SubPage(current, back) { ManualPage() }
            SettingsPage.RULES -> SubPage(current, back) { RulesPage(settings, settingsVersion) }
            SettingsPage.STUDENT -> SubPage(current, back) { StudentPage(settings, settingsVersion) }
            SettingsPage.IMPORT -> SubPage(current, back) { ImportPage(settings, settingsVersion, importer, onShowReconcile) }
            SettingsPage.EXPORT -> SubPage(current, back) { ExportPage(settings) }
        }
    }
}

/** Unterseite mit Zurück-Leiste und scrollbarem Inhalt. */
@Composable
private fun SubPage(page: SettingsPage, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(page.title, onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Übersicht
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsHome(
    settings: Settings,
    settingsVersion: Long,
    resumeCount: Int,
    onPage: (SettingsPage) -> Unit,
    onShowReconcile: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val issues = remember(resumeCount, settingsVersion) { SetupCheck.issues(context) }
    val imported by rememberImportedDays()
    val openMonths = rememberOpenReconcileMonths()
    val on = "an"
    val off = "aus"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("Optionen", "Erfassung, Auswertung und Daten")

        if (issues.isNotEmpty()) {
            Banner(
                icon = R.drawable.ic_warning_fill,
                title = "Einrichtung unvollständig",
                text = issues.joinToString(" · "),
                color = App.colors.warn,
                container = App.colors.warnContainer,
                onClick = { onPage(SettingsPage.SETUP) },
            )
        } else {
            RowGroup {
                ListRow(
                    "Einrichtung",
                    subtitle = "Alles eingerichtet – die Erfassung läuft im Hintergrund.",
                    icon = R.drawable.ic_check_circle_fill,
                    iconColor = App.colors.good,
                    onClick = { onPage(SettingsPage.SETUP) },
                ) { Chevron() }
            }
        }

        SectionLabel("Erfassung")
        RowGroup {
            ListRow(
                "WLAN",
                subtitle = when {
                    !settings.wifiEnabled -> off
                    settings.wifiSsidSet.isEmpty() -> "$on · noch kein Arbeits-WLAN eingetragen"
                    else -> "$on · ${settings.wifiSsidSet.joinToString(", ")}"
                },
                icon = R.drawable.ic_wifi,
                iconColor = App.colors.info,
                onClick = { onPage(SettingsPage.WIFI) },
            ) { Chevron() }
            RowDivider()
            ListRow(
                "Standort",
                subtitle = when {
                    !settings.geoEnabled -> off
                    settings.geoLat == null || settings.geoLon == null -> "$on · noch kein Arbeitsort festgelegt"
                    else -> "$on · Umkreis ${settings.geoRadius} m"
                },
                icon = R.drawable.ic_location,
                iconColor = App.colors.good,
                onClick = { onPage(SettingsPage.GEO) },
            ) { Chevron() }
            RowDivider()
            ListRow(
                "Kachel & manuell",
                subtitle = "Stempeln aus den Schnelleinstellungen",
                icon = R.drawable.ic_manual,
                iconColor = App.colors.warn,
                onClick = { onPage(SettingsPage.MANUAL) },
            ) { Chevron() }
        }

        SectionLabel("Auswertung")
        RowGroup {
            ListRow(
                "Arbeitszeit & Wertung",
                subtitle = "Soll ${TimeFormat.hm(settings.targetMinutes.toLong())} h · Verzögerung ${settings.leaveDelayMinutes} min · " +
                    "zuerst zählt ${sourceName(settings.priority.first { it != StampSource.NFC })}",
                icon = R.drawable.ic_flag,
                onClick = { onPage(SettingsPage.RULES) },
            ) { Chevron() }
            RowDivider()
            ListRow(
                "Werkstudent",
                subtitle = "Höchstens ${settings.studentLimitWeeks} Wochen über ${TimeFormat.hm(settings.studentLimitMinutes.toLong())} h",
                icon = R.drawable.ic_school,
                onClick = { onPage(SettingsPage.STUDENT) },
            ) { Chevron() }
        }

        SectionLabel("Daten")
        RowGroup {
            ListRow(
                "Stundenzettel importieren",
                subtitle = if (imported.isEmpty()) {
                    "PDF oder CSV der Firma"
                } else {
                    "${TimeFormat.plural(imported.size, "Tag", "Tage")} importiert · ${TimeFormat.hm(imported.sumOf { it.minutes })} h"
                },
                icon = R.drawable.ic_upload,
                onClick = { onPage(SettingsPage.IMPORT) },
            ) { Chevron() }
            if (imported.isNotEmpty()) {
                RowDivider()
                ListRow(
                    "Abgleich",
                    subtitle = if (openMonths.isEmpty()) {
                        "Alle Monate bestätigt"
                    } else {
                        "${TimeFormat.plural(openMonths.size, "Monat", "Monate")} offen"
                    },
                    icon = R.drawable.ic_reconcile,
                    onClick = { onShowReconcile(openMonths.isEmpty()) },
                ) { Chevron() }
            }
            RowDivider()
            ListRow(
                "Export",
                subtitle = "Rohdaten und Tagesübersicht als CSV für Excel",
                icon = R.drawable.ic_download,
                onClick = { onPage(SettingsPage.EXPORT) },
            ) { Chevron() }
        }

        SectionLabel("Darstellung")
        SegmentedChoice(
            options = listOf(Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK),
            selected = settings.themeMode,
            onSelect = { settings.themeMode = it },
            label = {
                when (it) {
                    Settings.THEME_LIGHT -> "Hell"
                    Settings.THEME_DARK -> "Dunkel"
                    else -> "Wie Handy"
                }
            },
        )

        Text(
            "Stempeluhr ${appVersionName(context)}\nAlle Daten liegen nur auf diesem Handy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

private fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty()
