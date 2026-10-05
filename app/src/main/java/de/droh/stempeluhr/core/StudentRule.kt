package de.droh.stempeluhr.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Brutto-Stunden einer Kalenderwoche (Mo–So). */
data class WeekStat(
    /** Montag der Woche. */
    val weekStart: LocalDate,
    val minutes: Long,
    /** Mehr als die Grenze (Standard 20 h) gearbeitet. */
    val over: Boolean,
    /** Tage mit Kommen, aber ohne Gehen (zählen mit 0 h – bitte nachtragen). */
    val incompleteDays: Int,
) {
    val weekEnd: LocalDate get() = weekStart.plusDays(6)

    /** Datum, ab dem diese Woche nicht mehr im rollierenden 12-Monats-Zeitraum liegt. */
    val dropsOutOn: LocalDate get() = weekStart.plusWeeks(StudentRule.WINDOW_WEEKS.toLong())
}

data class StudentReport(
    val today: LocalDate,
    /** Montag der ältesten Woche im Zeitraum. */
    val windowStart: LocalDate,
    /** Alle 52 Wochen des Zeitraums, neueste zuerst (auch Wochen ohne Einträge). */
    val weeks: List<WeekStat>,
    val overWeeks: Int,
    val limitWeeks: Int,
    val limitMinutes: Int,
) {
    /** Wie viele Wochen über der Grenze noch erlaubt sind (kann negativ sein = überschritten). */
    val remainingWeeks: Int get() = limitWeeks - overWeeks
    val currentWeek: WeekStat get() = weeks.first()

    /** Minuten, die in der aktuellen Woche noch gearbeitet werden können, ohne dass sie zählt. */
    val currentWeekMinutesLeft: Long get() = (limitMinutes - currentWeek.minutes).coerceAtLeast(0)

    /** Wochen über der Grenze, sortiert nach dem Datum, an dem sie aus dem Zeitraum fallen. */
    val upcomingReleases: List<WeekStat>
        get() = weeks.filter { it.over }.sortedBy { it.dropsOutOn }
}

/**
 * 26-Wochen-Regel für Werkstudenten: Innerhalb von 12 Monaten (rollierend, rückwärts ab heute)
 * dürfen höchstens 26 Wochen mit mehr als 20 Stunden liegen.
 * Gezählt werden Kalenderwochen (Mo–So) und die Brutto-Dauer (Kommen bis Gehen) je Tag.
 */
object StudentRule {
    const val WINDOW_WEEKS = 52
    const val DEFAULT_LIMIT_WEEKS = 26
    const val DEFAULT_LIMIT_MINUTES = 20 * 60

    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun report(
        days: List<DaySummary>,
        today: LocalDate,
        limitWeeks: Int = DEFAULT_LIMIT_WEEKS,
        limitMinutes: Int = DEFAULT_LIMIT_MINUTES,
    ): StudentReport {
        val currentMonday = monday(today)
        val windowStart = currentMonday.minusWeeks((WINDOW_WEEKS - 1).toLong())
        val byWeek = days.filter { !it.date.isBefore(windowStart) && !it.date.isAfter(today) }
            .groupBy { monday(it.date) }
        val weeks = (0 until WINDOW_WEEKS).map { i ->
            val start = currentMonday.minusWeeks(i.toLong())
            val list = byWeek[start].orEmpty()
            val minutes = list.sumOf { it.durationMinutes ?: 0L }
            WeekStat(
                weekStart = start,
                minutes = minutes,
                over = minutes > limitMinutes,
                incompleteDays = list.count { it.kommen != null && it.durationMinutes == null },
            )
        }
        return StudentReport(
            today = today,
            windowStart = windowStart,
            weeks = weeks,
            overWeeks = weeks.count { it.over },
            limitWeeks = limitWeeks,
            limitMinutes = limitMinutes,
        )
    }
}
