package de.droh.stempeluhr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.droh.stempeluhr.R
import de.droh.stempeluhr.core.StudentReport
import de.droh.stempeluhr.core.StudentRule
import de.droh.stempeluhr.core.Summary
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.core.WeekStat
import java.time.LocalDate
import java.time.temporal.IsoFields

/** Berechnet die 26-Wochen-Auswertung aus eigener Erfassung und Import. */
@Composable
fun rememberStudentReport(): StudentReport {
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    val (settings, settingsVersion) = rememberSettings()
    val today = Summary.localDate(rememberNow(), zone)
    return remember(events, imported, settingsVersion, today) {
        val days = Summary.byDay(events, zone, settings.priority, imported, settings.evalOptions)
        StudentRule.report(days, today, settings.studentLimitWeeks, settings.studentLimitMinutes)
    }
}

fun isoWeek(date: LocalDate): Int = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

fun kw(w: WeekStat): String = "KW ${isoWeek(w.weekStart)}"

fun weekRange(w: WeekStat): String = "${TimeFormat.shortDate(w.weekStart)}–${TimeFormat.shortDate(w.weekEnd)}${w.weekEnd.year}"

// ---------------------------------------------------------------------------------------------
// Laufende Woche, Tag für Tag
// ---------------------------------------------------------------------------------------------

/** Ein Tag der laufenden Woche. */
data class DayBar(
    val date: LocalDate,
    val minutes: Long,
    /** Heute, noch anwesend: [minutes] ist die bisher gelaufene Zeit. */
    val live: Boolean,
    /** Kommen ohne Gehen an einem früheren Tag – zählt mit 0 h, bis es nachgetragen ist. */
    val open: Boolean,
)

/** Die laufende Woche Mo–So. Heute zählt die bereits gelaufene Zeit mit, auch wenn noch kein Gehen erfasst ist. */
data class CurrentWeek(val monday: LocalDate, val today: LocalDate, val bars: List<DayBar>) {
    val totalMinutes: Long get() = bars.sumOf { it.minutes }
    val liveMinutes: Long get() = bars.filter { it.live }.sumOf { it.minutes }
    val openDays: Int get() = bars.count { it.open }
}

@Composable
fun rememberCurrentWeek(): CurrentWeek {
    val events by rememberAllEvents()
    val imported by rememberImportedDays()
    val (settings, settingsVersion) = rememberSettings()
    val now = rememberNow()
    val today = Summary.localDate(now, zone)
    val monday = StudentRule.monday(today)
    val days = remember(events, imported, settingsVersion, monday) {
        Summary.byDay(events, zone, settings.priority, imported, settings.evalOptions)
            .filter { !it.date.isBefore(monday) && !it.date.isAfter(monday.plusDays(6)) }
            .associateBy { it.date }
    }
    return remember(days, today, now) {
        CurrentWeek(
            monday = monday,
            today = today,
            bars = (0..6).map { i ->
                val date = monday.plusDays(i.toLong())
                val d = days[date]
                val unfinished = d != null && d.kommen != null && d.gehen == null && d.durationMinutes == null
                val live = unfinished && date == today
                DayBar(
                    date = date,
                    minutes = if (live) ((now - d!!.kommen!!.ts) / 60_000).coerceAtLeast(0) else d?.durationMinutes ?: 0L,
                    live = live,
                    open = unfinished && !live,
                )
            },
        )
    }
}

/** Sieben Balken Mo–So mit Stunden darüber. Heute ist hervorgehoben. */
@Composable
fun WeekBars(week: CurrentWeek, scaleMinutes: Long, modifier: Modifier = Modifier, barHeight: Dp = 60.dp) {
    val max = maxOf(scaleMinutes, week.bars.maxOf { it.minutes }, 1L).toFloat()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        week.bars.forEach { b ->
            val isToday = b.date == week.today
            val description = TimeFormat.weekdayLong(b.date) + ": " + when {
                b.open -> "Gehen fehlt"
                b.live -> "${TimeFormat.hm(b.minutes)} Stunden, läuft"
                else -> "${TimeFormat.hm(b.minutes)} Stunden"
            }
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when {
                        b.open -> "offen"
                        b.minutes > 0 -> TimeFormat.hm(b.minutes)
                        else -> " "
                    },
                    style = MaterialTheme.typography.labelSmall.merge(TabularNumbers),
                    color = when {
                        b.open -> App.colors.warn
                        isToday -> primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(barHeight).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val h = if (b.minutes > 0) (barHeight * (b.minutes / max) * grow.value).coerceAtLeast(6.dp) else 6.dp
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(h)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    b.open -> App.colors.warn.copy(alpha = 0.4f)
                                    b.minutes <= 0 -> App.colors.inset
                                    isToday -> primary
                                    else -> primary.copy(alpha = 0.35f)
                                },
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    TimeFormat.weekday(b.date),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.ExtraBold else null,
                    color = if (isToday) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bildschirm „Wochen“
// ---------------------------------------------------------------------------------------------

@Composable
fun WeeksScreen(modifier: Modifier) {
    val report = rememberStudentReport()
    val week = rememberCurrentWeek()
    val (settings, _) = rememberSettings()
    val limitH = TimeFormat.hm(report.limitMinutes.toLong())
    val remaining = report.remainingWeeks.coerceAtLeast(0)
    val tone = when {
        report.remainingWeeks <= 0 -> App.colors.bad
        report.remainingWeeks <= 3 -> App.colors.warn
        else -> App.colors.good
    }
    var showAll by rememberSaveable { mutableStateOf(false) }
    // Ausgewählte Woche im Diagramm: 0 = älteste … 51 = aktuelle.
    var selected by rememberSaveable { mutableIntStateOf(StudentRule.WINDOW_WEEKS - 1) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Wochen", "Werkstudent · ${report.limitWeeks}-Wochen-Regel") }

        item {
            Appear(0) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressRing(
                            progress = remaining / report.limitWeeks.toFloat().coerceAtLeast(1f),
                            color = tone,
                            size = 116.dp,
                            stroke = 12.dp,
                            modifier = Modifier.describedAs("$remaining von ${report.limitWeeks} Wochen frei"),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RollingText("$remaining", style = MaterialTheme.typography.displayMedium, color = tone)
                                Text(
                                    "von ${report.limitWeeks} frei",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (report.remainingWeeks <= 0) "Grenze erreicht" else "Noch ${TimeFormat.plural(remaining, "Woche", "Wochen")} frei",
                                style = MaterialTheme.typography.titleLarge,
                                color = if (report.remainingWeeks <= 0) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${report.overWeeks} von ${report.limitWeeks} Wochen lagen über $limitH h (seit " +
                                    TimeFormat.date(report.windowStart) + ")",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            report.upcomingReleases.firstOrNull()?.let { next ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AppIcon(R.drawable.ic_lock_open, tint = App.colors.good, size = 16.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Nächste wird frei am ${TimeFormat.date(next.dropsOutOn)}",
                                        style = MaterialTheme.typography.labelMedium.merge(TabularNumbers),
                                        color = App.colors.good,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Appear(1) {
                val total = week.totalMinutes
                val limit = report.limitMinutes.toLong()
                val over = total > limit
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CardTitle("Diese Woche")
                            Text(
                                "KW ${isoWeek(week.monday)} · ${TimeFormat.shortDate(week.monday)}–${TimeFormat.shortDate(week.monday.plusDays(6))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            RollingText(TimeFormat.hm(total), style = MaterialTheme.typography.headlineMedium)
                            Text(
                                " / $limitH h",
                                style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                    WeekBars(week, scaleMinutes = maxOf(settings.targetMinutes.toLong(), 60L))
                    ProgressBar(
                        total / limit.toFloat().coerceAtLeast(1f),
                        if (over) App.colors.bad else MaterialTheme.colorScheme.primary,
                        height = 10.dp,
                        modifier = Modifier.describedAs("${TimeFormat.hm(total)} von $limitH Stunden"),
                    )
                    Text(
                        when {
                            // Gewertet ist die Woche erst, wenn auch der heutige Tag abgeschlossen ist.
                            over && !report.currentWeek.over -> "Über $limitH h – zählt, sobald heute ein Gehen erfasst ist."
                            over -> "Diese Woche zählt als Woche über $limitH h."
                            else -> "Noch ${TimeFormat.hm(limit - total)} h, bis diese Woche zählt."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (over) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                    )
                    if (week.liveMinutes > 0) {
                        Hint(
                            "Enthält ${TimeFormat.hm(week.liveMinutes)} h von heute, die noch laufen. " +
                                "Fest gewertet wird der Tag, sobald ein Gehen erfasst ist.",
                        )
                    }
                    if (week.openDays > 0) {
                        Pill(
                            "${TimeFormat.plural(week.openDays, "Tag", "Tage")} ohne Gehen – zählen mit 0 h",
                            App.colors.warn,
                            icon = R.drawable.ic_warning,
                        )
                    }
                }
            }
        }

        item {
            Appear(2) {
                val weeks = report.weeks.reversed()
                val index = selected.coerceIn(0, weeks.lastIndex)
                val w = weeks[index]
                // Für die laufende Woche dieselben Zahlen wie in der Karte darüber (inklusive der Zeit von heute).
                val isCurrent = index == weeks.lastIndex
                val minutes = if (isCurrent) week.totalMinutes else w.minutes
                val incomplete = if (isCurrent) week.openDays else w.incompleteDays
                val isOver = minutes > report.limitMinutes
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle("Letzte ${StudentRule.WINDOW_WEEKS} Wochen", Modifier.weight(1f))
                        Text(
                            "Woche antippen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${kw(w)} · ${weekRange(w)}", style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
                            Text(
                                "${TimeFormat.hm(minutes)} h" +
                                    (if (isCurrent) " · laufende Woche" else "") +
                                    if (incomplete > 0) " · ${TimeFormat.plural(incomplete, "Tag", "Tage")} ohne Gehen" else "",
                                style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isOver) Pill("über $limitH h", App.colors.bad) else Pill("bis $limitH h", MaterialTheme.colorScheme.primary)
                    }
                    WeekChart(report, currentMinutes = week.totalMinutes, selected = index, onSelect = { selected = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(App.colors.bad, "über $limitH h")
                        Spacer(Modifier.width(16.dp))
                        Legend(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), "bis $limitH h")
                    }
                }
            }
        }

        item {
            Appear(3) {
                AppCard {
                    CardTitle("Wann wird wieder eine Woche frei?")
                    val releases = report.upcomingReleases
                    if (releases.isEmpty()) {
                        Text(
                            "Keine Woche über $limitH h im Zeitraum.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    releases.forEachIndexed { i, w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(TimeFormat.date(w.dropsOutOn), style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
                                Text(
                                    "${kw(w)} (${weekRange(w)}) · ${TimeFormat.hm(w.minutes)} h",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Pill("dann ${report.remainingWeeks + i + 1} frei", App.colors.good)
                        }
                    }
                    if (releases.isNotEmpty()) {
                        Hint("Gerechnet ohne weitere Wochen über der Grenze.")
                    }
                }
            }
        }

        item {
            Appear(4) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle("Alle ${StudentRule.WINDOW_WEEKS} Wochen", Modifier.weight(1f))
                        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Ausblenden" else "Anzeigen") }
                    }
                    AnimatedVisibility(showAll, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            report.weeks.forEach { w -> WeekRow(w, limitH) }
                        }
                    }
                    Hint(
                        "Gezählt wird die Brutto-Dauer je Kalenderwoche (Mo–So) aus eigener Erfassung und Import – " +
                            "auch in den Semesterferien.",
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekRow(w: WeekStat, limitH: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kw(w), Modifier.width(58.dp), style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
        Text(
            weekRange(w),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.merge(TabularNumbers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            "${TimeFormat.hm(w.minutes)} h",
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            fontWeight = if (w.over) FontWeight.Bold else FontWeight.Normal,
            color = if (w.over) App.colors.bad else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                w.over -> Pill("> $limitH h", App.colors.bad)
                w.incompleteDays > 0 -> Pill("unvollst.", App.colors.warn)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Balkendiagramm der 52 Wochen (älteste links) mit gestrichelter Grenzlinie und Monaten als Achse.
 * Antippen oder waagerechtes Ziehen wählt eine Woche; die Einzelwerte stehen zusätzlich in „Alle 52 Wochen“.
 */
@Composable
private fun WeekChart(report: StudentReport, currentMinutes: Long, selected: Int, onSelect: (Int) -> Unit) {
    // Die laufende Woche (letzter Balken) zeigt den Stand inklusive der Zeit von heute.
    val weeks = report.weeks.reversed().let { list ->
        list.dropLast(1) + list.last().let { it.copy(minutes = currentMinutes, over = currentMinutes > report.limitMinutes) }
    }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val over = App.colors.bad
    val normal = MaterialTheme.colorScheme.primary
    val limitColor = MaterialTheme.colorScheme.outline
    val highlight = App.colors.inset
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.merge(TabularNumbers).copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val limitLabel = "${TimeFormat.hm(report.limitMinutes.toLong())} h"
    val maxMinutes = maxOf(weeks.maxOfOrNull { it.minutes } ?: 0L, report.limitMinutes * 3L / 2).toFloat()
    val overCount = weeks.count { it.over }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(168.dp)
            .describedAs("Diagramm der letzten ${weeks.size} Wochen, davon $overCount über $limitLabel")
            .pointerInput(weeks.size) {
                detectTapGestures { o -> onSelect((o.x / (size.width / weeks.size.toFloat())).toInt().coerceIn(0, weeks.lastIndex)) }
            }
            .pointerInput(weeks.size) {
                detectHorizontalDragGestures { change, _ ->
                    onSelect((change.position.x / (size.width / weeks.size.toFloat())).toInt().coerceIn(0, weeks.lastIndex))
                }
            },
    ) {
        val axis = 20.dp.toPx()
        val chartH = size.height - axis
        val step = size.width / weeks.size
        val gap = minOf(2.dp.toPx(), step * 0.3f)
        val barW = step - gap
        weeks.forEachIndexed { i, w ->
            val isSelected = i == selected
            if (isSelected) {
                drawRoundRect(highlight, Offset(i * step - gap, 0f), Size(step + gap, chartH), CornerRadius(4.dp.toPx()))
            }
            val x = i * step + gap / 2
            if (w.minutes > 0) {
                val h = (chartH * (w.minutes / maxMinutes) * anim.value).coerceAtLeast(barW)
                drawRoundRect(
                    color = when {
                        w.over -> over.copy(alpha = if (isSelected) 1f else 0.85f)
                        else -> normal.copy(alpha = if (isSelected) 1f else 0.5f)
                    },
                    topLeft = Offset(x, chartH - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            } else {
                drawCircle(limitColor.copy(alpha = 0.5f), radius = 1.dp.toPx(), center = Offset(x + barW / 2, chartH - 1.5.dp.toPx()))
            }
        }
        // Grenzlinie mit Beschriftung.
        val y = chartH - chartH * (report.limitMinutes / maxMinutes)
        drawLine(limitColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val limitText = measurer.measure(limitLabel, labelStyle)
        drawText(limitText, topLeft = Offset(size.width - limitText.size.width, (y - limitText.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
        // Monate als Achsenbeschriftung, jeweils an der ersten Woche des Monats.
        var lastEnd = Float.NEGATIVE_INFINITY
        weeks.forEachIndexed { i, w ->
            if (i == 0 || w.weekStart.month != weeks[i - 1].weekStart.month) {
                val t = measurer.measure(TimeFormat.monthShort(w.weekStart), labelStyle)
                val x = i * step
                if (x >= lastEnd + 4.dp.toPx() && x + t.size.width <= size.width) {
                    drawText(t, topLeft = Offset(x, chartH + 5.dp.toPx()))
                    lastEnd = x + t.size.width
                }
            }
        }
    }
}
