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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val today = LocalDate.now(zone)
    return remember(events, imported, settingsVersion, today) {
        val days = Summary.byDay(events, zone, settings.priority, imported, settings.evalOptions)
        StudentRule.report(days, today, settings.studentLimitWeeks, settings.studentLimitMinutes)
    }
}

fun kw(w: WeekStat): String = "KW ${w.weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

fun weekRange(w: WeekStat): String = "${TimeFormat.shortDate(w.weekStart)}–${TimeFormat.shortDate(w.weekEnd)}${w.weekEnd.year}"

@Composable
fun WeeksScreen(modifier: Modifier) {
    val report = rememberStudentReport()
    val limitH = TimeFormat.hm(report.limitMinutes.toLong())
    val remaining = report.remainingWeeks.coerceAtLeast(0)
    val ringColor = when {
        report.remainingWeeks <= 0 -> App.colors.bad
        report.remainingWeeks <= 3 -> App.colors.warn
        else -> App.colors.good
    }
    var showAll by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Wochen", "Werkstudent · 26-Wochen-Regel") }

        item {
            Appear(0) {
                AppCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        ProgressRing(
                            progress = remaining / report.limitWeeks.toFloat().coerceAtLeast(1f),
                            color = ringColor,
                            size = 180.dp,
                            stroke = 16.dp,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RollingText("$remaining", style = MaterialTheme.typography.displayLarge, color = ringColor)
                                Text(
                                    "von ${report.limitWeeks} frei",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (report.remainingWeeks <= 0) "Grenze erreicht" else "Wochen dürfen noch über $limitH h liegen",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (report.remainingWeeks <= 0) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${report.overWeeks} Wochen über $limitH h seit ${TimeFormat.date(report.windowStart)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Appear(1) {
                val w = report.currentWeek
                AppCard {
                    CardTitle("Diese Woche · ${kw(w)}")
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingText(TimeFormat.hm(w.minutes), style = MaterialTheme.typography.displayMedium)
                        Text(
                            " / $limitH h",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    ProgressBar(
                        w.minutes / report.limitMinutes.toFloat().coerceAtLeast(1f),
                        if (w.over) App.colors.bad else MaterialTheme.colorScheme.primary,
                        height = 12.dp,
                    )
                    Text(
                        if (w.over) {
                            "Diese Woche zählt bereits als Woche über $limitH h."
                        } else {
                            "Noch ${TimeFormat.hm(report.currentWeekMinutesLeft)} h, bis diese Woche zählt."
                        },
                        color = if (w.over) App.colors.bad else MaterialTheme.colorScheme.onSurface,
                    )
                    if (w.incompleteDays > 0) {
                        Pill("${w.incompleteDays} Tag(e) ohne Gehen – zählen mit 0 h", App.colors.warn)
                    }
                    Text(
                        "Der heutige Tag zählt, sobald ein Gehen erfasst ist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Appear(2) {
                AppCard {
                    CardTitle("Letzte 52 Wochen")
                    WeekChart(report)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(App.colors.bad, "über $limitH h")
                        Spacer(Modifier.width(16.dp))
                        Legend(MaterialTheme.colorScheme.primary, "bis $limitH h")
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
                        Text("Keine Woche über $limitH h im Zeitraum.")
                    }
                    releases.forEachIndexed { i, w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(TimeFormat.date(w.dropsOutOn), style = MaterialTheme.typography.titleMedium)
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
                        Text(
                            "Gerechnet ohne weitere Wochen über der Grenze.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Appear(4) {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardTitle("Alle 52 Wochen", Modifier.weight(1f))
                        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Ausblenden" else "Anzeigen") }
                    }
                    AnimatedVisibility(showAll, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            report.weeks.forEach { w -> WeekRow(w, limitH) }
                        }
                    }
                    Text(
                        "Gezählt wird die Brutto-Dauer je Kalenderwoche (Mo–So) aus eigener Erfassung und Import – " +
                            "auch in den Semesterferien.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekRow(w: WeekStat, limitH: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(kw(w), Modifier.width(64.dp), fontWeight = FontWeight.SemiBold)
        Text(weekRange(w), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${TimeFormat.hm(w.minutes)} h",
            style = MaterialTheme.typography.bodyMedium.merge(TabularNumbers),
            fontWeight = if (w.over) FontWeight.Bold else FontWeight.Normal,
            color = if (w.over) App.colors.bad else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(76.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                w.over -> Pill("> $limitH h", App.colors.bad)
                w.incompleteDays > 0 -> Pill("unvollst.", App.colors.warn)
            }
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Balkendiagramm der 52 Wochen (älteste links), mit gestrichelter Grenzlinie. */
@Composable
private fun WeekChart(report: StudentReport) {
    val weeks = report.weeks.reversed()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(report.weeks) { anim.snapTo(0f); anim.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val over = App.colors.bad
    val normal = MaterialTheme.colorScheme.primary
    val limitColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxMinutes = maxOf(weeks.maxOfOrNull { it.minutes } ?: 0L, report.limitMinutes * 3L / 2).toFloat()
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (weeks.size - 1)) / weeks.size
        weeks.forEachIndexed { i, w ->
            val h = size.height * (w.minutes / maxMinutes) * anim.value
            if (w.minutes > 0) {
                drawRoundRect(
                    color = if (w.over) over else normal.copy(alpha = 0.55f),
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2, barW / 2),
                )
            }
        }
        val y = size.height - size.height * (report.limitMinutes / maxMinutes)
        drawLine(
            limitColor,
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}
