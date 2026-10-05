package de.droh.stempeluhr.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ein importierter Tag, von dem nur die Dauer bekannt ist (z. B. aus dem Stundenzettel der Firma). */
data class ImportedDay(
    val date: LocalDate,
    val minutes: Long,
    val note: String?,
) {
    /** Krank, Urlaub, Feiertag o. Ä. laut Bemerkung. */
    val isAbsence: Boolean
        get() = note?.lowercase()?.let { n -> ABSENCE_WORDS.any { it in n } } == true

    companion object {
        val ABSENCE_WORDS = listOf("krank", "urlaub", "feiertag")
    }
}

/** Vergleich der gelesenen Tage mit der Monatssumme, die in der Datei steht. */
data class MonthCheck(val month: YearMonth, val statedMinutes: Long?, val parsedMinutes: Long) {
    val ok: Boolean? get() = statedMinutes?.let { it == parsedMinutes }
}

/** Eine Zeile aus dem eigenen Rohdaten-Export (für Wiederherstellung). */
data class RawRow(
    val ts: Long,
    val type: StampType,
    val source: StampSource,
    val note: String?,
    val originalTs: Long?,
    val deleted: Boolean,
)

data class ImportResult(
    val days: List<ImportedDay>,
    val checks: List<MonthCheck>,
    val rawRows: List<RawRow>,
    /** Zeilen mit Datum, die nicht verstanden wurden (zur Kontrolle angezeigt). */
    val skippedLines: List<String>,
) {
    val isEmpty: Boolean get() = days.isEmpty() && rawRows.isEmpty()
}

/**
 * Liest Arbeitszeiten aus Text (aus PDF extrahiert) oder CSV.
 *
 * Unterstützt:
 * 1. Stundenzettel: Zeilen mit Datum (TT.MM.JJJJ), dahinter die Dauer (h:mm) und optional eine Bemerkung.
 *    Eine Dauer vor dem ersten Datum eines Monats wird als Monatssumme zur Kontrolle verwendet.
 * 2. CSV mit Kopfzeile, die eine Spalte "Datum" und eine Spalte mit "Dauer" enthält (z. B. die eigene Tagesübersicht).
 * 3. Den eigenen Rohdaten-Export der App (stellt alle Ereignisse wieder her).
 */
object ImportParser {
    private val DATE = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})$""")
    private val HM = Regex("""^(\d{1,3}):(\d{2})$""")
    private val MONEY = Regex("""^[\d.]+,\d{2}$""")
    private val dateFmt = DateTimeFormatter.ofPattern("d.M.yyyy")

    fun parse(text: String, zone: ZoneId): ImportResult {
        val lines = text.removePrefix("﻿").lines().map { it.trimEnd('\r') }
        val header = lines.firstOrNull { it.isNotBlank() }.orEmpty()
        if (header.startsWith("ID;Datum;Wochentag;Uhrzeit;Art;Quelle")) return parseRaw(lines, zone)
        val csvCols = splitCsv(header).map { it.trim().lowercase() }
        val dateCol = csvCols.indexOf("datum")
        val durCol = csvCols.indexOfFirst { it.startsWith("dauer") }
        if (header.contains(';') && dateCol >= 0 && durCol >= 0) {
            val noteCol = csvCols.indexOfFirst { it.startsWith("bemerkung") || it.startsWith("notiz") }
            return parseCsvColumns(lines.drop(lines.indexOf(header) + 1), dateCol, durCol, noteCol)
        }
        return parseTimesheet(lines)
    }

    fun parseHm(s: String): Long? = HM.matchEntire(s.trim())?.let { m ->
        val min = m.groupValues[2].toLong()
        if (min > 59) null else m.groupValues[1].toLong() * 60 + min
    }

    private fun parseDate(s: String): LocalDate? =
        if (DATE.matches(s.trim())) runCatching { LocalDate.parse(s.trim(), dateFmt) }.getOrNull() else null

    // ---------- 1. Stundenzettel (Text aus PDF) ----------

    private fun parseTimesheet(lines: List<String>): ImportResult {
        val days = linkedMapOf<LocalDate, ImportedDay>()
        val stated = mutableMapOf<YearMonth, Long>()
        val skipped = mutableListOf<String>()
        for (line in lines) {
            val tokens = line.split(Regex("""[\s;\t]+""")).filter { it.isNotEmpty() }
            val dateIdx = tokens.indexOfFirst { parseDate(it) != null }
            if (dateIdx < 0) continue
            val date = parseDate(tokens[dateIdx])!!
            // Dauer vor dem Datum = Monatssumme (erste Zeile eines Monatszettels).
            val month = YearMonth.from(date)
            if (month !in stated) {
                tokens.take(dateIdx).firstNotNullOfOrNull { parseHm(it) }?.let { stated[month] = it }
            }
            val after = tokens.drop(dateIdx + 1)
            val minutes = after.firstOrNull()?.let { parseHm(it) }
            if (minutes == null) {
                // Datum ohne Dauer = nicht gearbeitet. Steht dahinter etwas anderes als Geldbeträge, zur Kontrolle melden.
                if (after.any { !MONEY.matches(it) && it != "€" && it != "?" && it != "EUR" }) skipped += line.trim()
                continue
            }
            val note = after.drop(1)
                .takeWhile { !MONEY.matches(it) && it != "€" && it != "?" }
                .joinToString(" ")
                .ifBlank { null }
            days[date] = ImportedDay(date, minutes, note)
        }
        return result(days.values.toList(), stated, emptyList(), skipped)
    }

    // ---------- 2. CSV mit Spalten ----------

    private fun parseCsvColumns(lines: List<String>, dateCol: Int, durCol: Int, noteCol: Int): ImportResult {
        val days = linkedMapOf<LocalDate, ImportedDay>()
        val skipped = mutableListOf<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            val cells = splitCsv(line)
            val date = cells.getOrNull(dateCol)?.let { parseDate(it) } ?: continue
            val durText = cells.getOrNull(durCol)?.trim().orEmpty()
            if (durText.isEmpty()) continue
            val minutes = parseHm(durText)
            if (minutes == null) {
                skipped += line
                continue
            }
            val note = if (noteCol >= 0) cells.getOrNull(noteCol)?.trim()?.ifEmpty { null } else null
            days[date] = ImportedDay(date, minutes, note)
        }
        return result(days.values.toList(), emptyMap(), emptyList(), skipped)
    }

    // ---------- 3. Eigener Rohdaten-Export ----------

    private fun parseRaw(lines: List<String>, zone: ZoneId): ImportResult {
        val rows = mutableListOf<RawRow>()
        val skipped = mutableListOf<String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val c = splitCsv(line)
            val row = runCatching {
                val ts = LocalDate.parse(c[1], dateFmt).atTime(LocalTime.parse(c[3])).atZone(zone).toInstant().toEpochMilli()
                val type = StampType.entries.first { it.label == c[4] }
                val source = StampSource.entries.first { it.label == c[5] }
                val original = if (c[7] == "ja" && c[8].isNotEmpty()) {
                    LocalDate.parse(c[8], dateFmt).atTime(LocalTime.parse(c[9])).atZone(zone).toInstant().toEpochMilli()
                } else {
                    null
                }
                RawRow(ts, type, source, c[6].ifEmpty { null }, original, c[10] == "ja")
            }.getOrNull()
            if (row == null) skipped += line else rows += row
        }
        return ImportResult(emptyList(), emptyList(), rows, skipped)
    }

    private fun result(
        days: List<ImportedDay>,
        stated: Map<YearMonth, Long>,
        raw: List<RawRow>,
        skipped: List<String>,
    ): ImportResult {
        val sorted = days.sortedBy { it.date }
        val months = (sorted.map { YearMonth.from(it.date) } + stated.keys).distinct().sorted()
        val checks = months.map { m ->
            MonthCheck(m, stated[m], sorted.filter { YearMonth.from(it.date) == m }.sumOf { it.minutes })
        }
        return ImportResult(sorted, checks, raw, skipped)
    }

    /** Teilt eine CSV-Zeile mit ; (oder , falls kein ; vorkommt) und berücksichtigt Anführungszeichen. */
    fun splitCsv(line: String): List<String> {
        val sep = if (line.contains(';')) ';' else ','
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> {
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
