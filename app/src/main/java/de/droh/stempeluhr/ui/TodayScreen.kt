package de.droh.stempeluhr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.DaySummary
import de.droh.stempeluhr.core.PreciseLogic
import de.droh.stempeluhr.core.SourceTimes
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.data.StampDb
import de.droh.stempeluhr.engine.StampEngine
import kotlin.math.cos
import kotlin.math.sin

private enum class DayState { IDLE, PRESENT, DONE }

@Composable
fun TodayScreen(
    modifier: Modifier,
    resumeCount: Int,
    onOpenSetup: () -> Unit,
    onOpenSource: (StampSource) -> Unit,
    onOpenWeeks: () -> Unit,
    onOpenReconcile: () -> Unit,
) {
    val context = LocalContext.current
    val events by rememberAllEvents()
    val (settings, settingsVersion) = rememberSettings()
    val autoVersion = rememberAutoStateVersion()
    val now = rememberNow()
    val today = Summary.localDate(now, zone)
    val priority = remember(settingsVersion) { settings.priority }
    val target = remember(settingsVersion) { settings.targetMinutes }
    val showDeleted = remember(settingsVersion) { settings.showDeleted }
    val todayEvents = remember(events, today) { events.filter { Summary.localDate(it.ts, zone) == today } }
    val summary = remember(todayEvents, priority, today) { Summary.forDay(today, todayEvents, priority) }
    val nextType = remember(todayEvents) { PreciseLogic.nextToggleType(todayEvents) }
    val setupIssues = remember(resumeCount, settingsVersion) { SetupCheck.issues(context) }
    val openReconcile = rememberOpenReconcileMonths()
    val dialogs = remember { EventDialogs() }
    val messenger = LocalMessenger.current
    val haptics = LocalHapticFeedback.current

    val week = rememberCurrentWeek()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Heute", "${TimeFormat.weekdayLong(today)}, ${TimeFormat.dayMonth(today)}")

        Appear(0) {
            HeroCard(
                summary = summary,
                now = now,
                targetMinutes = target,
                nextType = nextType,
                onStamp = { type ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val mode = if (type == StampType.IN) StampEngine.Mode.IN else StampEngine.Mode.OUT
                    val ev = StampEngine.preciseEvent(context, StampSource.MANUELL, mode)
                    if (ev != null) {
                        messenger.show("${ev.type.label} um ${TimeFormat.time(ev.ts, zone)} gestempelt", "Rückgängig") {
                            StampDb.get(context).setDeleted(ev.id, true)
                        }
                    }
                },
            )
        }

        AnimatedVisibility(setupIssues.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Banner(
                icon = R.drawable.ic_warning_fill,
                title = "Einrichtung unvollständig",
                text = setupIssues.joinToString(" · "),
                color = App.colors.warn,
                container = App.colors.warnContainer,
                onClick = onOpenSetup,
            )
        }
        AnimatedVisibility(openReconcile.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Banner(
                icon = R.drawable.ic_reconcile_fill,
                title = "Abgleich bereit",
                text = "${TimeFormat.plural(openReconcile.size, "Monat", "Monate")} mit dem Stundenzettel der Firma vergleichen",
                color = App.colors.info,
                container = App.colors.infoContainer,
                onClick = onOpenReconcile,
            )
        }

        Appear(1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Quellen")
                // Erst die automatischen Quellen, dann die, die man selbst auslöst.
                listOf(StampSource.WLAN, StampSource.GEOFENCE, StampSource.NFC, StampSource.MANUELL).chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { src ->
                            SourceTile(
                                source = src,
                                times = summary.bySource[src],
                                status = sourceStatus(src, settings, settingsVersion, autoVersion),
                                onClick = { onOpenSource(src) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        Appear(2) {
            WeekCard(week = week, targetMinutes = target, studentEnabled = settings.studentEnabled, onClick = onOpenWeeks)
        }

        Appear(3) {
            AppCard(modifier = Modifier.animateContentSize(), spacing = 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardTitle("Verlauf heute", Modifier.weight(1f))
                    TextButton(onClick = { dialogs.addingFor = today to nextType }) {
                        AppIcon(R.drawable.ic_add, size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Nachtragen")
                    }
                }
                val visible = (if (showDeleted) todayEvents else todayEvents.filter { !it.deleted })
                    .sortedWith(compareBy<StampEvent>({ it.ts }, { it.id }))
                if (visible.isEmpty()) {
                    Text(
                        "Noch keine Einträge. Sobald du ins Arbeits-WLAN kommst, den Arbeitsort betrittst oder stempelst, " +
                            "erscheint hier alles.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                visible.forEachIndexed { i, ev ->
                    EventRow(
                        event = ev,
                        onEdit = { dialogs.editing = ev },
                        onRestore = { StampDb.get(context).setDeleted(ev.id, false) },
                        connect = i != visible.lastIndex,
                    )
                }
            }
        }
    }
    EventDialogsHost(dialogs)
}

// ---------------------------------------------------------------------------------------------
// Statuskarte mit Zifferblatt
// ---------------------------------------------------------------------------------------------

@Composable
private fun HeroCard(
    summary: DaySummary,
    now: Long,
    targetMinutes: Int,
    nextType: StampType,
    onStamp: (StampType) -> Unit,
) {
    val c = App.colors
    val kommen = summary.kommen
    val gehen = summary.gehen
    val state = when {
        kommen != null && gehen == null -> DayState.PRESENT
        gehen != null -> DayState.DONE
        else -> DayState.IDLE
    }
    val gradient = when (state) {
        DayState.PRESENT -> c.heroPresent
        DayState.DONE -> c.heroDone
        DayState.IDLE -> c.heroIdle
    }
    val start by animateColorAsState(gradient[0], tween(600), label = "heroStart")
    val end by animateColorAsState(gradient[1], tween(600), label = "heroEnd")
    val soft = c.onHero.copy(alpha = 0.85f)

    val runningMinutes = if (state == DayState.PRESENT) ((now - kommen!!.ts) / 60_000).coerceAtLeast(0) else null
    val minutes = summary.durationMinutes ?: runningMinutes ?: 0L
    val progress = if (targetMinutes > 0) minutes / targetMinutes.toFloat() else 0f
    val statusText = when (state) {
        DayState.PRESENT -> "Anwesend seit ${TimeFormat.time(kommen!!.ts, zone)}"
        DayState.DONE -> "Feierabend seit ${TimeFormat.time(gehen!!.ts, zone)}"
        DayState.IDLE -> "Noch nicht eingestempelt"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .drawBehind {
                drawRect(Brush.linearGradient(listOf(start, end), Offset.Zero, Offset(size.width, size.height)))
                // Weicher Lichtschein oben rechts gibt der Fläche Tiefe.
                val glow = Offset(size.width * 0.92f, size.height * 0.06f)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), glow, size.width * 0.75f),
                    radius = size.width * 0.75f,
                    center = glow,
                )
            }
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state == DayState.PRESENT) {
                PulsingDot(c.onHero)
            } else {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.onHero.copy(alpha = 0.6f)))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(statusText, color = c.onHero, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        Dial(
            progress = progress,
            runningSince = kommen?.ts?.takeIf { state == DayState.PRESENT },
            modifier = Modifier
                .size(204.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Arbeitszeit heute ${TimeFormat.hm(minutes)} Stunden" +
                        if (targetMinutes > 0) ", Soll ${TimeFormat.hm(targetMinutes.toLong())} Stunden" else ""
                },
        ) {
            // Das Zifferblatt hat eine feste Größe – die Schrift darin wächst deshalb nur begrenzt mit.
            LimitFontScale(1.1f) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { }) {
                    Text(
                        if (state == DayState.PRESENT) "Arbeitszeit läuft" else "Arbeitszeit heute",
                        color = soft,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    RollingText(TimeFormat.hm(minutes), style = MaterialTheme.typography.displayLarge, color = c.onHero)
                    Text(
                        if (targetMinutes > 0) "von ${TimeFormat.hm(targetMinutes.toLong())} h" else "Stunden",
                        color = soft,
                        style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeroStat("Kommen", kommen?.let { TimeFormat.time(it.ts, zone) } ?: "–:–", kommen?.let { sourceName(it.source) })
            HeroDivider()
            HeroStat("Gehen", gehen?.let { TimeFormat.time(it.ts, zone) } ?: "–:–", gehen?.let { sourceName(it.source) })
            if (targetMinutes > 0) {
                HeroDivider()
                val saldo = minutes - targetMinutes
                when {
                    state == DayState.PRESENT && saldo < 0 -> HeroStat(
                        "Soll erreicht",
                        TimeFormat.time(kommen!!.ts + targetMinutes * 60_000L, zone),
                        "noch ${TimeFormat.hm(-saldo)} h",
                    )
                    state == DayState.PRESENT -> HeroStat("Über Soll", TimeFormat.signedHm(saldo), "Soll ${TimeFormat.hm(targetMinutes.toLong())} h")
                    state == DayState.DONE && summary.durationMinutes != null ->
                        HeroStat("Saldo", TimeFormat.signedHm(saldo), "Soll ${TimeFormat.hm(targetMinutes.toLong())} h")
                    else -> HeroStat("Soll", TimeFormat.hm(targetMinutes.toLong()), "Stunden")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StampButton(StampType.IN, primary = nextType == StampType.IN, onClick = { onStamp(StampType.IN) }, modifier = Modifier.weight(1f))
            StampButton(StampType.OUT, primary = nextType == StampType.OUT, onClick = { onStamp(StampType.OUT) }, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Zifferblatt: außen 60 Striche wie bei einer Uhr, innen ein Ring für den Fortschritt zum Soll.
 * Solange die Arbeitszeit läuft, wandert ein Punkt im Sekundentakt über die Striche.
 */
@Composable
private fun Dial(progress: Float, runningSince: Long?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        anim.animateTo(progress.coerceIn(0f, 1f), spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessVeryLow))
    }
    val on = App.colors.onHero
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val long = 7.dp.toPx()
            val short = 4.dp.toPx()
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val a = Math.toRadians(i * 6.0 - 90.0)
                val len = if (major) long else short
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                drawLine(
                    on.copy(alpha = if (major) 0.6f else 0.3f),
                    Offset(center.x + (r - len) * dx, center.y + (r - len) * dy),
                    Offset(center.x + r * dx, center.y + r * dy),
                    strokeWidth = (if (major) 2.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val stroke = 12.dp.toPx()
            val inset = long + 9.dp.toPx() + stroke / 2
            val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(on.copy(alpha = 0.2f), -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (anim.value > 0.001f) {
                drawArc(on, -90f, 360f * anim.value, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        if (runningSince != null) SecondHand(runningSince)
        content()
    }
}

/** Eigener kleiner Baustein, damit nur er jede Sekunde neu gezeichnet wird. */
@Composable
private fun SecondHand(since: Long) {
    val now = rememberNow(1_000L)
    // Fortlaufender Winkel (nicht modulo 360), damit der Punkt beim Minutenwechsel nicht zurückläuft.
    val angle by animateFloatAsState(
        ((now - since) / 1000L).coerceAtLeast(0L) * 6f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "second",
    )
    val on = App.colors.onHero
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2 - 3.5.dp.toPx()
        val a = Math.toRadians((angle % 360f) - 90.0)
        drawCircle(on, radius = 4.5.dp.toPx(), center = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
    }
}

@Composable
private fun RowScope.HeroStat(label: String, value: String, sub: String?) {
    val c = App.colors
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = c.onHero.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        RollingText(value, style = MaterialTheme.typography.titleLarge, color = c.onHero)
        Text(
            sub ?: " ",
            color = c.onHero.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HeroDivider() {
    Box(Modifier.width(1.dp).height(36.dp).background(App.colors.onHero.copy(alpha = 0.22f)))
}

/**
 * Stempel-Knopf in der Statuskarte. Beide Arten sind immer da; die naheliegende ist weiß hervorgehoben.
 * Die Position bleibt fest (Kommen links, Gehen rechts), damit der Daumen nicht umlernen muss.
 */
@Composable
private fun StampButton(type: StampType, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = App.colors
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(if (primary) Color.White else Color.White.copy(alpha = 0.16f), label = "stampContainer")
    val accent = if (type == StampType.IN) c.heroButtonIn else c.heroButtonOut
    val content by animateColorAsState(if (primary) accent else c.onHero, label = "stampContent")
    Row(
        modifier
            .pressScale(interaction)
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "${type.label} jetzt stempeln",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Zwei Knöpfe teilen sich die Breite: Die Beschriftung muss auch bei großer Schrift ganz lesbar bleiben.
        LimitFontScale(1.15f) {
            AppIcon(typeIcon(type), tint = content, size = 20.dp)
            Spacer(Modifier.width(6.dp))
            Text(type.label, color = content, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Quellen
// ---------------------------------------------------------------------------------------------

private data class SourceStatus(val text: String, val enabled: Boolean, val tone: Tone) {
    enum class Tone { GOOD, WAIT, NEUTRAL }
}

/** Kurzer Live-Status einer Quelle. */
@Composable
private fun sourceStatus(source: StampSource, settings: Settings, settingsVersion: Long, autoVersion: Long): SourceStatus =
    remember(settingsVersion, autoVersion, source) {
        when (source) {
            StampSource.WLAN, StampSource.GEOFENCE -> {
                val wifi = source == StampSource.WLAN
                val enabled = if (wifi) settings.wifiEnabled else settings.geoEnabled
                val s = settings.autoState(source)
                when {
                    !enabled -> SourceStatus("aus", false, SourceStatus.Tone.NEUTRAL)
                    // „weg“ gemeldet, die Verzögerung läuft noch.
                    s.pendingLeaveAt != 0L -> SourceStatus("prüft …", true, SourceStatus.Tone.WAIT)
                    s.presentSince != 0L -> SourceStatus(if (wifi) "verbunden" else "vor Ort", true, SourceStatus.Tone.GOOD)
                    else -> SourceStatus(if (wifi) "getrennt" else "entfernt", true, SourceStatus.Tone.NEUTRAL)
                }
            }
            StampSource.NFC ->
                if (settings.nfcEnabled) SourceStatus("bereit", true, SourceStatus.Tone.NEUTRAL) else SourceStatus("aus", false, SourceStatus.Tone.NEUTRAL)
            StampSource.MANUELL -> SourceStatus("bereit", true, SourceStatus.Tone.NEUTRAL)
        }
    }

@Composable
private fun SourceTile(source: StampSource, times: SourceTimes?, status: SourceStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = if (status.enabled) sourceColor(source) else MaterialTheme.colorScheme.outline
    val statusColor = when {
        !status.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        status.tone == SourceStatus.Tone.GOOD -> App.colors.good
        status.tone == SourceStatus.Tone.WAIT -> App.colors.warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val firstIn = times?.firstIn?.let { TimeFormat.time(it, zone) }
    val lastOut = times?.lastOut?.let { TimeFormat.time(it, zone) }
    Column(
        modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClickLabel = "${sourceName(source)} einrichten", role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(sourceIcon(source), color, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(sourceName(source), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine(status.text, statusColor)
            }
        }
        Text(
            if (firstIn == null && lastOut == null) "Heute noch nichts" else "${firstIn ?: "–"}  →  ${lastOut ?: "–"}",
            style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
            color = if (firstIn == null && lastOut == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Diese Woche
// ---------------------------------------------------------------------------------------------

@Composable
private fun WeekCard(week: CurrentWeek, targetMinutes: Int, studentEnabled: Boolean, onClick: () -> Unit) {
    val total = week.totalMinutes
    AppCard(onClick = onClick, onClickLabel = "Wochen öffnen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("Diese Woche")
                Text(
                    "KW ${isoWeek(week.monday)} · ${TimeFormat.shortDate(week.monday)}–${TimeFormat.shortDate(week.monday.plusDays(6))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("${TimeFormat.hm(total)} h", style = MaterialTheme.typography.headlineSmall.merge(TabularNumbers))
        }
        WeekBars(week, scaleMinutes = maxOf(targetMinutes.toLong(), 60L))
        if (studentEnabled) {
            val report = rememberStudentReport()
            val limit = report.limitMinutes.toLong()
            val over = total > limit
            ProgressBar(
                total / limit.toFloat().coerceAtLeast(1f),
                if (over) App.colors.bad else MaterialTheme.colorScheme.primary,
                modifier = Modifier.describedAs("${TimeFormat.hm(total)} von ${TimeFormat.hm(limit)} Stunden"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (over) {
                        "Über ${TimeFormat.hm(limit)} h – diese Woche zählt"
                    } else {
                        "Noch ${TimeFormat.hm(limit - total)} h bis ${TimeFormat.hm(limit)} h"
                    },
                    style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                    color = if (over) App.colors.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val free = report.remainingWeeks.coerceAtLeast(0)
                Pill(
                    "$free von ${report.limitWeeks} Wochen frei",
                    when {
                        report.remainingWeeks <= 0 -> App.colors.bad
                        report.remainingWeeks <= 3 -> App.colors.warn
                        else -> App.colors.good
                    },
                )
            }
        }
    }
}
