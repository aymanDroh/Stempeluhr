package de.droh.stempeluhr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class ImportAndStudentTest {
    private val zone = ZoneId.of("Europe/Berlin")

    // Aufbau wie ein aus PDF extrahierter Monats-Stundenzettel (Testdaten).
    private val timesheet = """
        Vorname Nachname Dauer Saldo Datum Stundenlohn Auszahlung
        Muster Max 20:30 20:30:00 01.07.2026 7:21 20,00 ? 410,00 ?
        20,50 02.07.2026 7:53
        21:00 03.07.2026
        04.07.2026
        06.07.2026 1:16 krank
        07.07.2026 4:00
        Bemerkung
        Vorname Nachname Dauer Saldo Datum
        Muster Max 10:02 10:02 01.08.2026
        02.08.2026 10:02
        03.08.2026
    """.trimIndent()

    @Test
    fun stundenzettelWirdGelesenUndGeprueft() {
        val r = ImportParser.parse(timesheet, zone)
        assertEquals(5, r.days.size)
        assertEquals(ImportedDay(LocalDate.of(2026, 7, 1), 441, null), r.days[0])
        val krank = r.days.single { it.note != null }
        assertEquals("krank", krank.note)
        assertTrue(krank.isAbsence)
        assertEquals(2, r.checks.size)
        val july = r.checks.first { it.month == YearMonth.of(2026, 7) }
        assertEquals(20 * 60 + 30L, july.statedMinutes)
        assertEquals(20 * 60 + 30L, july.parsedMinutes)
        assertEquals(true, july.ok)
        assertEquals(true, r.checks[1].ok)
        assertTrue(r.skippedLines.isEmpty())
    }

    @Test
    fun csvMitSpaltenUndFehlerhafteZeilen() {
        val csv = "﻿Datum;Dauer (h:mm);Bemerkung\r\n05.10.2026;8:15;\r\n06.10.2026;Urlaub;\r\n07.10.2026;4:00;Urlaub\r\n"
        val r = ImportParser.parse(csv, zone)
        assertEquals(2, r.days.size)
        assertEquals(495L, r.days[0].minutes)
        assertTrue(r.days[1].isAbsence)
        assertEquals(1, r.skippedLines.size)
    }

    @Test
    fun rohdatenExportLaesstSichWiederherstellen() {
        val t = LocalDateTime.of(2026, 10, 5, 7, 59).atZone(zone).toInstant().toEpochMilli()
        val events = listOf(
            StampEvent(1, t, StampType.IN, StampSource.NFC, "mit; Semikolon", t, null, false),
            StampEvent(2, t + 3_600_000, StampType.OUT, StampSource.MANUELL, null, t, t + 60_000, true),
        )
        val csv = CsvExport.rawEvents(events, zone)
        val r = ImportParser.parse(csv, zone)
        assertTrue(r.days.isEmpty())
        assertEquals(
            listOf(
                RawRow(t, StampType.IN, StampSource.NFC, "mit; Semikolon", null, false),
                RawRow(t + 3_600_000, StampType.OUT, StampSource.MANUELL, null, t + 60_000, true),
            ),
            r.rawRows,
        )
    }

    @Test
    fun importUndEigeneErfassungZusammenfuehren() {
        val date = LocalDate.of(2026, 10, 5)
        val t = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val events = listOf(
            StampEvent(1, t, StampType.IN, StampSource.NFC, null, t, null, false),
            StampEvent(2, t + 8 * 3_600_000, StampType.OUT, StampSource.NFC, null, t, null, false),
        )
        val imp = ImportedDay(date, 7 * 60, null)
        val preferImport = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY, listOf(imp)).single()
        assertEquals(7 * 60L, preferImport.durationMinutes)
        assertTrue(preferImport.durationFromImport)
        val preferOwn = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY, listOf(imp), EvalOptions(preferImport = false)).single()
        assertEquals(8 * 60L, preferOwn.durationMinutes)
        assertFalse(preferOwn.durationFromImport)

        val sick = ImportedDay(date.plusDays(1), 8 * 60, "krank")
        val days = Summary.byDay(emptyList(), zone, StampSource.DEFAULT_PRIORITY, listOf(sick), EvalOptions(countAbsence = false))
        assertEquals(0L, days.single().durationMinutes)
    }

    // ---------- 26-Wochen-Regel ----------

    private fun day(date: LocalDate, minutes: Long) =
        Summary.forDay(date, emptyList(), StampSource.DEFAULT_PRIORITY, ImportedDay(date, minutes, null))

    @Test
    fun wochenUeberGrenzeImRollierendenZeitraum() {
        val today = LocalDate.of(2026, 10, 7) // Mittwoch
        val days = listOf(
            // Aktuelle Woche: 12 h
            day(LocalDate.of(2026, 10, 5), 6 * 60),
            day(LocalDate.of(2026, 10, 6), 6 * 60),
            // Letzte Woche: genau 20 h -> zählt NICHT (nur "mehr als")
            day(LocalDate.of(2026, 9, 28), 10 * 60),
            day(LocalDate.of(2026, 10, 4), 10 * 60), // Sonntag gehört zur Woche ab 28.09.
            // Vorletzte Woche: 20:01 -> zählt
            day(LocalDate.of(2026, 9, 21), 20 * 60 + 1),
            // Älteste Woche im Zeitraum (Montag 13.10.2025): zählt
            day(LocalDate.of(2025, 10, 13), 25 * 60),
            // Vor dem Zeitraum: zählt nicht mehr
            day(LocalDate.of(2025, 10, 12), 30 * 60),
        )
        val r = StudentRule.report(days, today)
        assertEquals(52, r.weeks.size)
        assertEquals(LocalDate.of(2025, 10, 13), r.windowStart)
        assertEquals(LocalDate.of(2026, 10, 5), r.currentWeek.weekStart)
        assertEquals(12 * 60L, r.currentWeek.minutes)
        assertEquals(8 * 60L, r.currentWeekMinutesLeft)
        assertEquals(20 * 60L, r.weeks[1].minutes)
        assertFalse(r.weeks[1].over)
        assertEquals(2, r.overWeeks)
        assertEquals(24, r.remainingWeeks)
        // Die älteste Woche wird am nächsten Montag frei.
        assertEquals(LocalDate.of(2026, 10, 12), r.upcomingReleases.first().dropsOutOn)
    }

    @Test
    fun unvollstaendigeTageWerdenMarkiert() {
        val date = LocalDate.of(2026, 10, 5)
        val t = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val open = Summary.forDay(date, listOf(StampEvent(1, t, StampType.IN, StampSource.WLAN, null, t, null, false)), StampSource.DEFAULT_PRIORITY)
        assertNull(open.durationMinutes)
        val r = StudentRule.report(listOf(open), date)
        assertEquals(1, r.currentWeek.incompleteDays)
        assertEquals(0L, r.currentWeek.minutes)
    }
}
