package de.droh.stempeluhr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val danger = report.remainingWeeks <= 0
    val warnColor = Color(0xFFC62828)
    val okColor = Color(0xFF2E7D32)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = if (danger) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SmallLabel("Werkstudent: Wochen über $limitH h")
                    Text(
                        "${report.remainingWeeks.coerceAtLeast(0)}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (danger) warnColor else okColor,
                    )
                    Text(
                        if (danger) {
                            "Grenze erreicht oder überschritten: ${report.overWeeks} von ${report.limitWeeks} Wochen"
                        } else {
                            "Wochen dürfen noch über $limitH h liegen"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${report.overWeeks} von ${report.limitWeeks} Wochen über $limitH h im Zeitraum " +
                            "${TimeFormat.date(report.windowStart)} – ${TimeFormat.date(report.today)} (52 Kalenderwochen rückwärts)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            val w = report.currentWeek
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallLabel("Diese Woche (${kw(w)}, ${weekRange(w)})")
                    Text("${TimeFormat.hm(w.minutes)} h gearbeitet", style = MaterialTheme.typography.titleLarge)
                    if (w.over) {
                        Text("Zählt bereits als Woche über $limitH h.", color = warnColor, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Noch ${TimeFormat.hm(report.currentWeekMinutesLeft)} h, bis diese Woche über $limitH h zählt.")
                    }
                    if (w.incompleteDays > 0) {
                        Text(
                            "${w.incompleteDays} Tag(e) ohne Gehen zählen mit 0 h – bitte nachtragen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = warnColor,
                        )
                    }
                    Text(
                        "Der heutige Tag zählt erst, wenn ein Gehen erfasst ist.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SmallLabel("Wann wird wieder eine Woche frei?")
                    val releases = report.upcomingReleases
                    if (releases.isEmpty()) {
                        Text("Keine Woche über $limitH h im Zeitraum.")
                    } else {
                        Text(
                            "Jede Woche über $limitH h fällt 52 Wochen nach ihrem Montag aus dem Zeitraum heraus (ohne weitere Wochen über der Grenze gerechnet):",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        releases.forEachIndexed { i, w ->
                            Row {
                                Text("ab ${TimeFormat.date(w.dropsOutOn)}", Modifier.weight(0.4f), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${kw(w)} (${weekRange(w)}): ${TimeFormat.hm(w.minutes)} h → dann ${report.remainingWeeks + i + 1} frei",
                                    Modifier.weight(0.6f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Alle 52 Wochen im Zeitraum",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Gezählt wird die Brutto-Dauer je Kalenderwoche (Mo–So), aus eigener Erfassung und Import. " +
                    "Auch Wochen in den Semesterferien zählen mit.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(report.weeks, key = { it.weekStart.toString() }) { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kw(w), Modifier.weight(0.18f), fontWeight = FontWeight.SemiBold)
                Text(weekRange(w), Modifier.weight(0.42f), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${TimeFormat.hm(w.minutes)} h",
                    Modifier.weight(0.2f),
                    color = if (w.over) warnColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (w.over) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    when {
                        w.over -> "> $limitH h"
                        w.incompleteDays > 0 -> "unvollst."
                        else -> ""
                    },
                    Modifier.weight(0.2f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (w.over) warnColor else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
