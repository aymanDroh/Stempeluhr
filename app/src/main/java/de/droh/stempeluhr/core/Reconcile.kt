package de.droh.stempeluhr.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Ergebnis des Abgleichs eines Tages: Firmendaten (Import) gegen eigene Erfassung. */
enum class MatchStatus(val label: String, val problem: Boolean) {
    OK("stimmt", false),
    DIFF("Abweichung", true),
    ONLY_OWN("fehlt bei Firma", true),
    ONLY_IMPORT("nur bei Firma", true),
    OWN_INCOMPLETE("eigene Erfassung unvollständig", true),
    ABSENCE("krank/Urlaub laut Firma", false),
}

data class DayComparison(
    val date: LocalDate,
    val importMinutes: Long?,
    val importNote: String?,
    /** Eigene Brutto-Dauer (Kommen bis Gehen), unabhängig von der Import-Einstellung. */
    val ownMinutes: Long?,
    val kommen: Stamp?,
    val gehen: Stamp?,
    val status: MatchStatus,
) {
    /** Eigene Dauer minus Firmen-Dauer (positiv = Firma hat weniger). */
    val diffMinutes: Long? get() = if (ownMinutes != null && importMinutes != null) ownMinutes - importMinutes else null
}

data class MonthComparison(
    val month: YearMonth,
    /** Alle Tage des Monats mit Import oder eigener Erfassung, aufsteigend. */
    val days: List<DayComparison>,
    val toleranceMinutes: Int,
) {
    val importTotal: Long get() = days.sumOf { it.importMinutes ?: 0L }
    val ownTotal: Long get() = days.sumOf { it.ownMinutes ?: 0L }
    val countByStatus: Map<MatchStatus, Int> get() = MatchStatus.entries.associateWith { s -> days.count { it.status == s } }
    val problems: Int get() = days.count { it.status.problem }
}

/**
 * Gleicht importierte Firmendaten (nur Dauer je Tag) mit der eigenen Erfassung ab.
 * Abweichungen bis [toleranceMinutes] gelten als übereinstimmend (Rundung der Firma).
 */
object Reconcile {

    fun compare(days: List<DaySummary>, month: YearMonth, toleranceMinutes: Int): MonthComparison {
        val list = days.filter { YearMonth.from(it.date) == month }
            .filter { it.imported != null || it.events.isNotEmpty() }
            .sortedBy { it.date }
            .map { d ->
                val imp = d.imported
                val own = d.ownMinutes
                val status = when {
                    imp != null && own != null ->
                        if (kotlin.math.abs(own - imp.minutes) <= toleranceMinutes) MatchStatus.OK else MatchStatus.DIFF
                    imp != null && d.kommen != null -> MatchStatus.OWN_INCOMPLETE
                    imp != null && imp.isAbsence -> MatchStatus.ABSENCE
                    imp != null -> MatchStatus.ONLY_IMPORT
                    own != null -> MatchStatus.ONLY_OWN
                    else -> MatchStatus.OWN_INCOMPLETE
                }
                DayComparison(d.date, imp?.minutes, imp?.note, own, d.kommen, d.gehen, status)
            }
        return MonthComparison(month, list, toleranceMinutes)
    }

    /**
     * Kurzer Fingerabdruck eines Monats-Abgleichs. Ändern sich die Daten des Monats
     * (neuer Import, Korrektur), ändert sich der Fingerabdruck und der Monat gilt wieder als offen.
     */
    fun fingerprint(c: MonthComparison): String =
        c.days.joinToString("|") { "${it.date}:${it.importMinutes}:${it.ownMinutes}:${it.status.name}" }
            .hashCode().toUInt().toString(16)

    /** Monate mit Import, die noch nicht (oder mit anderen Daten) bestätigt wurden, neuester zuerst. */
    fun openMonths(days: List<DaySummary>, toleranceMinutes: Int, confirmed: Map<YearMonth, String>): List<YearMonth> =
        importedMonths(days).filter { m -> confirmed[m] != fingerprint(compare(days, m, toleranceMinutes)) }

    /** Monate mit importierten Daten, neuester zuerst. */
    fun importedMonths(days: List<DaySummary>): List<YearMonth> =
        days.filter { it.imported != null }.map { YearMonth.from(it.date) }.distinct().sortedDescending()

    /** CSV (für Excel) mit allen Tagen des Abgleichs. */
    fun csv(c: MonthComparison, zone: ZoneId): String {
        val sb = StringBuilder("﻿")
        fun row(vararg cells: String) {
            sb.append(cells.joinToString(";") { v -> if (v.contains(';') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v })
            sb.append("\r\n")
        }
        row(
            "Datum", "Wochentag", "Firma (h:mm)", "Bemerkung Firma", "Eigene (h:mm)",
            "Kommen", "Quelle Kommen", "Gehen", "Quelle Gehen", "Differenz eigene - Firma", "Status",
        )
        for (d in c.days) {
            row(
                TimeFormat.date(d.date),
                TimeFormat.weekday(d.date),
                d.importMinutes?.let { TimeFormat.hm(it) }.orEmpty(),
                d.importNote.orEmpty(),
                d.ownMinutes?.let { TimeFormat.hm(it) }.orEmpty(),
                d.kommen?.let { TimeFormat.time(it.ts, zone) }.orEmpty(),
                d.kommen?.source?.label.orEmpty(),
                d.gehen?.let { TimeFormat.time(it.ts, zone) }.orEmpty(),
                d.gehen?.source?.label.orEmpty(),
                d.diffMinutes?.let { TimeFormat.signedHm(it) }.orEmpty(),
                d.status.label,
            )
        }
        row(
            "Summe", "", TimeFormat.hm(c.importTotal), "", TimeFormat.hm(c.ownTotal),
            "", "", "", "", TimeFormat.signedHm(c.ownTotal - c.importTotal), "${c.problems} Auffälligkeiten",
        )
        return sb.toString()
    }
}
