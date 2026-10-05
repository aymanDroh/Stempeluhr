package de.droh.stempeluhr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.core.PreciseLogic
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.StampDb
import de.droh.stempeluhr.engine.StampEngine
import java.time.LocalDate

@Composable
fun TodayScreen(
    modifier: Modifier,
    resumeCount: Int,
    onOpenSettings: () -> Unit,
    onOpenWeeks: () -> Unit,
    onOpenReconcile: () -> Unit,
) {
    val context = LocalContext.current
    val events by rememberAllEvents()
    val (settings, settingsVersion) = rememberSettings()
    val autoVersion = rememberAutoStateVersion()
    val now = rememberNow()
    val today = LocalDate.now(zone)
    val priority = remember(settingsVersion) { settings.priority }
    val todayEvents = remember(events, today) { events.filter { Summary.localDate(it.ts, zone) == today } }
    val summary = Summary.forDay(today, todayEvents, priority)
    val dialogs = remember { EventDialogs() }
    val setupIssues = remember(resumeCount, settingsVersion) { SetupCheck.issues(context) }
    val openReconcile = rememberOpenReconcileMonths()
    val nextType = remember(todayEvents) { PreciseLogic.nextToggleType(todayEvents) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("Heute", "${TimeFormat.weekday(today)}, ${TimeFormat.date(today)}")

        AnimatedVisibility(setupIssues.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            AppCard(container = App.colors.warnContainer, onClick = onOpenSettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = App.colors.warn)
                    Spacer(Modifier.width(10.dp))
                    Text("Einrichtung unvollständig", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("Öffnen", color = App.colors.warn, fontWeight = FontWeight.Bold)
                }
                setupIssues.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        AnimatedVisibility(openReconcile.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            AppCard(container = MaterialTheme.colorScheme.primaryContainer, onClick = onOpenReconcile) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Abgleich bereit", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${openReconcile.size} Monat(e) mit dem Stundenzettel vergleichen",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Prüfen", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }

        Appear(0) {
            HeroCard(
                kommen = summary.kommen?.ts,
                kommenSource = summary.kommen?.source,
                gehen = summary.gehen?.ts,
                gehenSource = summary.gehen?.source,
                durationMinutes = summary.durationMinutes,
                now = now,
                nextType = nextType,
                onStamp = { StampEngine.precise(context, StampSource.MANUELL, StampEngine.Mode.TOGGLE) },
                onAdd = { dialogs.addingFor = today to nextType },
            )
        }

        Appear(1) {
            AppCard {
                CardTitle("Quellen")
                Row(Modifier.fillMaxWidth()) {
                    StampSource.entries.forEach { src ->
                        val t = summary.bySource[src]
                        val (status, active) = sourceStatus(src, settingsVersion, autoVersion)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            SourceBadge(src, 44.dp, active)
                            Text(
                                t?.firstIn?.let { TimeFormat.time(it, zone) } ?: "–",
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                t?.lastOut?.let { TimeFormat.time(it, zone) } ?: "–",
                                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = sourceColor(src).copy(alpha = if (active) 1f else 0.5f),
                            )
                        }
                    }
                }
            }
        }

        if (settings.studentEnabled) {
            Appear(2) { StudentMiniCard(onOpenWeeks) }
        }

        Appear(3) {
            AppCard(modifier = Modifier.animateContentSize()) {
                CardTitle("Verlauf heute")
                val visible = (if (settings.showDeleted) todayEvents else todayEvents.filter { !it.deleted }).sortedBy { it.ts }
                if (visible.isEmpty()) {
                    Text(
                        "Noch keine Einträge. Sobald du ins Arbeits-WLAN kommst, den Arbeitsort betrittst oder stempelst, erscheint hier alles.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                visible.forEachIndexed { i, ev ->
                    TimelineItem(
                        event = ev,
                        isLast = i == visible.lastIndex,
                        onEdit = { dialogs.editing = ev },
                        onDelete = { dialogs.deleting = ev },
                        onRestore = { StampDb.get(context).setDeleted(ev.id, false) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    EventDialogsHost(dialogs)
}

@Composable
private fun HeroCard(
    kommen: Long?,
    kommenSource: StampSource?,
    gehen: Long?,
    gehenSource: StampSource?,
    durationMinutes: Long?,
    now: Long,
    nextType: StampType,
    onStamp: () -> Unit,
    onAdd: () -> Unit,
) {
    val present = kommen != null && gehen == null
    val c = App.colors
    val start by animateColorAsState(if (present) c.heroStart else c.heroIdleStart, tween(600), label = "heroStart")
    val end by animateColorAsState(if (present) c.heroEnd else c.heroIdleEnd, tween(600), label = "heroEnd")
    val running = if (kommen != null && gehen == null) ((now - kommen) / 60_000).coerceAtLeast(0) else null
    val shownMinutes = durationMinutes ?: running
    val haptics = LocalHapticFeedback.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(start, end)))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (present) {
                PulsingDot(Color.White)
            } else {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    kommen != null && gehen == null -> "Anwesend seit ${TimeFormat.time(kommen, zone)}"
                    gehen != null -> "Feierabend"
                    else -> "Noch nicht eingestempelt"
                },
                color = c.onHero,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column {
            Text(
                if (present) "ARBEITSZEIT LÄUFT" else "ARBEITSZEIT HEUTE",
                color = c.onHero.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
            RollingText(
                "${TimeFormat.hm(shownMinutes ?: 0L)} h",
                style = MaterialTheme.typography.displayLarge,
                color = c.onHero,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            HeroTime("KOMMEN", kommen, kommenSource)
            HeroTime("GEHEN", gehen, gehenSource)
        }

        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
        val buttonText by animateColorAsState(if (nextType == StampType.IN) c.heroStart else c.bad, label = "btn")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .scale(scale)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(interactionSource = interaction, indication = null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStamp()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (nextType == StampType.IN) "Kommen stempeln" else "Gehen stempeln",
                    color = buttonText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Add, contentDescription = "Eintrag nachtragen", tint = Color.White) }
        }
    }
}

@Composable
private fun HeroTime(label: String, ts: Long?, source: StampSource?) {
    val c = App.colors
    Column {
        Text(label, color = c.onHero.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
        RollingText(ts?.let { TimeFormat.time(it, zone) } ?: "––:––", style = MaterialTheme.typography.headlineSmall, color = c.onHero)
        Text(source?.label ?: " ", color = c.onHero.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StudentMiniCard(onOpenWeeks: () -> Unit) {
    val report = rememberStudentReport()
    val limitH = TimeFormat.hm(report.limitMinutes.toLong())
    val w = report.currentWeek
    val remaining = report.remainingWeeks.coerceAtLeast(0)
    val ringColor = when {
        report.remainingWeeks <= 0 -> App.colors.bad
        report.remainingWeeks <= 3 -> App.colors.warn
        else -> App.colors.good
    }
    AppCard(onClick = onOpenWeeks) {
        CardTitle("Werkstudent")
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(
                progress = remaining / report.limitWeeks.toFloat().coerceAtLeast(1f),
                color = ringColor,
                size = 84.dp,
                stroke = 9.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$remaining", style = MaterialTheme.typography.headlineSmall, color = ringColor)
                    Text("frei", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Noch $remaining von ${report.limitWeeks} Wochen über $limitH h", style = MaterialTheme.typography.titleMedium)
                InfoRow("Diese Woche", "${TimeFormat.hm(w.minutes)} / $limitH h")
                ProgressBar(
                    w.minutes / report.limitMinutes.toFloat().coerceAtLeast(1f),
                    if (w.over) App.colors.bad else MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (w.over) "Diese Woche zählt bereits." else "Noch ${TimeFormat.hm(report.currentWeekMinutesLeft)} h bis zur Grenze",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (w.over) App.colors.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimelineItem(
    event: StampEvent,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
) {
    val dot = if (event.type == StampType.IN) App.colors.good else App.colors.bad
    Row {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(20.dp)) {
            Box(
                Modifier
                    .padding(top = 14.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (event.deleted) MaterialTheme.colorScheme.outline else dot),
            )
            if (!isLast) {
                Box(Modifier.width(2.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            EventRow(event = event, onEdit = onEdit, onDelete = onDelete, onRestore = onRestore)
        }
    }
}

/** Kurzer Live-Status einer Quelle und ob sie aktiv ist. */
@Composable
private fun sourceStatus(source: StampSource, settingsVersion: Long, autoVersion: Long): Pair<String, Boolean> {
    val settings = rememberSettings().first
    return remember(settingsVersion, autoVersion, source) {
        when (source) {
            StampSource.WLAN, StampSource.GEOFENCE -> {
                val enabled = if (source == StampSource.WLAN) settings.wifiEnabled else settings.geoEnabled
                val s = settings.autoState(source)
                when {
                    !enabled -> "aus" to false
                    s.pendingLeaveAt != 0L -> "weg?" to true
                    s.presentSince != 0L -> "da" to true
                    else -> "nicht da" to true
                }
            }
            StampSource.NFC -> (if (settings.nfcEnabled) "bereit" else "aus") to settings.nfcEnabled
            StampSource.MANUELL -> "Kachel" to true
        }
    }
}
