package de.droh.stempeluhr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.StampDb
import de.droh.stempeluhr.engine.StampEngine
import java.time.LocalDate

@Composable
fun TodayScreen(modifier: Modifier, resumeCount: Int, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val events by rememberAllEvents()
    val (settings, settingsVersion) = rememberSettings()
    val autoVersion = rememberAutoStateVersion()
    val now = rememberNow()
    val today = LocalDate.now(zone)
    val priority = remember(settingsVersion) { settings.priority }
    val todayEvents = events.filter { Summary.localDate(it.ts, zone) == today }
    val summary = Summary.forDay(today, todayEvents, priority)
    val dialogs = remember { EventDialogs() }
    val setupIssues = remember(resumeCount, settingsVersion) { SetupCheck.issues(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Heute, ${TimeFormat.weekday(today)} ${TimeFormat.date(today)}",
            style = MaterialTheme.typography.titleLarge,
        )

        if (setupIssues.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Text("  Einrichtung unvollständig", fontWeight = FontWeight.Bold)
                    }
                    setupIssues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = onOpenSettings) { Text("Zu den Einstellungen") }
                }
            }
        }

        // ---------- Gewertete Zeiten ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallLabel("Gewertet (nach Priorität)")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BigTime("Kommen", summary.kommen?.let { TimeFormat.time(it.ts, zone) }, summary.kommen?.source?.label)
                    BigTime("Gehen", summary.gehen?.let { TimeFormat.time(it.ts, zone) }, summary.gehen?.source?.label)
                    val duration = summary.durationMinutes
                    val running = summary.kommen?.takeIf { summary.gehen == null }?.let { (now - it.ts) / 60_000 }
                    BigTime(
                        "Dauer",
                        when {
                            duration != null -> TimeFormat.hm(duration)
                            running != null && running >= 0 -> TimeFormat.hm(running)
                            else -> null
                        },
                        if (duration == null && running != null) "läuft" else null,
                    )
                }
            }
        }

        // ---------- Alle Quellen ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallLabel("Alle Quellen")
                StampSource.entries.forEach { src ->
                    val t = summary.bySource[src]
                    val status = sourceStatus(src, settingsVersion, autoVersion)
                    Row(Modifier.fillMaxWidth()) {
                        Text(src.label, Modifier.weight(0.3f), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${t?.firstIn?.let { TimeFormat.time(it, zone) } ?: "–"}  →  " +
                                (t?.lastOut?.let { TimeFormat.time(it, zone) } ?: "–"),
                            Modifier.weight(0.35f),
                        )
                        Text(status, Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // ---------- Manuell stempeln ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { StampEngine.precise(context, StampSource.MANUELL, StampEngine.Mode.IN) },
                modifier = Modifier.weight(1f),
            ) { Text("Kommen jetzt") }
            Button(
                onClick = { StampEngine.precise(context, StampSource.MANUELL, StampEngine.Mode.OUT) },
                modifier = Modifier.weight(1f),
            ) { Text("Gehen jetzt") }
        }
        OutlinedButton(
            onClick = { dialogs.addingFor = today to StampType.IN },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Eintrag mit anderer Uhrzeit nachtragen") }

        // ---------- Ereignisse ----------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                SmallLabel("Alle Ereignisse heute")
                val visible = if (settings.showDeleted) todayEvents else todayEvents.filter { !it.deleted }
                if (visible.isEmpty()) {
                    Text("Noch keine Einträge.", style = MaterialTheme.typography.bodyMedium)
                }
                visible.sortedBy { it.ts }.forEach { ev ->
                    EventRow(
                        event = ev,
                        onEdit = { dialogs.editing = ev },
                        onDelete = { dialogs.deleting = ev },
                        onRestore = { StampDb.get(context).setDeleted(ev.id, false) },
                    )
                }
            }
        }
    }
    EventDialogsHost(dialogs)
}

@Composable
private fun BigTime(label: String, value: String?, sub: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value ?: "–", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(sub ?: " ", style = MaterialTheme.typography.bodySmall)
    }
}

/** Kurzer Live-Status einer Quelle. */
@Composable
private fun sourceStatus(source: StampSource, settingsVersion: Long, autoVersion: Long): String {
    val settings = rememberSettings().first
    return remember(settingsVersion, autoVersion, source) {
        when (source) {
            StampSource.WLAN, StampSource.GEOFENCE -> {
                val enabled = if (source == StampSource.WLAN) settings.wifiEnabled else settings.geoEnabled
                val s = settings.autoState(source)
                when {
                    !enabled -> "aus"
                    s.pendingLeaveAt != 0L -> "weg seit ${TimeFormat.time(s.pendingLeaveAt, zone)}?"
                    s.presentSince != 0L -> "da"
                    else -> "nicht da"
                }
            }
            StampSource.NFC -> if (settings.nfcEnabled) "an" else "aus"
            StampSource.MANUELL -> ""
        }
    }
}
