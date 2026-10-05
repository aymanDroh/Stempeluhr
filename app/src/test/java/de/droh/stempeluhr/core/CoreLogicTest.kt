package de.droh.stempeluhr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CoreLogicTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val min = 60_000L
    private var nextId = 1L

    private fun ts(day: Int, h: Int, m: Int): Long =
        LocalDateTime.of(2026, 10, day, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ev(t: Long, type: StampType, src: StampSource, deleted: Boolean = false) =
        StampEvent(nextId++, t, type, src, null, t, null, deleted)

    // ---------- Tagesauswertung ----------

    @Test
    fun prioritaetBestimmtGewerteteZeiten() {
        val events = listOf(
            ev(ts(5, 7, 52), StampType.IN, StampSource.GEOFENCE),
            ev(ts(5, 7, 56), StampType.IN, StampSource.WLAN),
            ev(ts(5, 7, 59), StampType.IN, StampSource.NFC),
            ev(ts(5, 16, 40), StampType.OUT, StampSource.NFC),
            ev(ts(5, 16, 43), StampType.OUT, StampSource.WLAN),
            ev(ts(5, 16, 50), StampType.OUT, StampSource.GEOFENCE),
        )
        val day = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY).single()
        assertEquals(StampSource.NFC, day.kommen!!.source)
        assertEquals(ts(5, 7, 59), day.kommen!!.ts)
        assertEquals(StampSource.NFC, day.gehen!!.source)
        assertEquals(8 * 60 + 41L, day.durationMinutes)
        assertEquals(3, day.bySource.size)

        val wlanFirst = Summary.byDay(events, zone, listOf(StampSource.WLAN)).single()
        assertEquals(StampSource.WLAN, wlanFirst.kommen!!.source)
        assertEquals(8 * 60 + 47L, wlanFirst.durationMinutes)
    }

    @Test
    fun mittagspauseUndOffenerTag() {
        val events = listOf(
            ev(ts(5, 8, 0), StampType.IN, StampSource.WLAN),
            ev(ts(5, 12, 0), StampType.OUT, StampSource.WLAN),
            ev(ts(5, 12, 40), StampType.IN, StampSource.WLAN),
        )
        val day = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY).single()
        assertEquals(ts(5, 8, 0), day.kommen!!.ts)
        assertNull("Tag ist noch offen, das Mittags-Gehen darf nicht gewertet werden", day.gehen)
        assertTrue(day.bySource[StampSource.WLAN]!!.open)
        assertNull(day.durationMinutes)
    }

    @Test
    fun fehlendeQuelleFaelltAufNaechstePrioritaetZurueck() {
        val events = listOf(
            ev(ts(5, 7, 59), StampType.IN, StampSource.NFC),
            ev(ts(5, 16, 43), StampType.OUT, StampSource.WLAN),
            ev(ts(5, 16, 0), StampType.OUT, StampSource.MANUELL, deleted = true),
        )
        val day = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY).single()
        assertEquals(StampSource.NFC, day.kommen!!.source)
        assertEquals(StampSource.WLAN, day.gehen!!.source)
        assertEquals(2, day.events.size)
    }

    @Test
    fun summeUndSaldo() {
        val events = listOf(
            ev(ts(5, 8, 0), StampType.IN, StampSource.NFC),
            ev(ts(5, 17, 0), StampType.OUT, StampSource.NFC),
            ev(ts(6, 8, 0), StampType.IN, StampSource.NFC),
            ev(ts(6, 15, 30), StampType.OUT, StampSource.NFC),
            ev(ts(7, 8, 0), StampType.IN, StampSource.NFC),
        )
        val days = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY)
        assertEquals(listOf(7, 6, 5), days.map { it.date.dayOfMonth })
        val total = Summary.total(days, 8 * 60)
        assertEquals(2, total.days)
        assertEquals(9 * 60 + 7 * 60 + 30L, total.minutes)
        assertEquals(60 - 30L, total.saldoMinutes)
    }

    @Test
    fun prioritaetParsen() {
        assertEquals(StampSource.DEFAULT_PRIORITY, StampSource.parsePriority(null))
        assertEquals(
            listOf(StampSource.WLAN, StampSource.NFC, StampSource.MANUELL, StampSource.GEOFENCE),
            StampSource.parsePriority("WLAN,NFC,UNSINN,WLAN"),
        )
    }

    // ---------- Automatische Quellen ----------

    @Test
    fun kurzerAussetzerWirdGeglaettet() {
        val debounce = 5 * min
        var r = AutoLogic.onArrive(AutoState(), ts(5, 8, 0), debounce, zone)
        assertEquals(listOf(PlannedRecord(StampType.IN, ts(5, 8, 0))), r.records)
        r = AutoLogic.onLeave(r.state, ts(5, 10, 0), debounce)
        assertTrue(r.records.isEmpty())
        r = AutoLogic.onArrive(r.state, ts(5, 10, 3), debounce, zone)
        assertTrue("Aussetzer < 5 min erzeugt keine Ereignisse", r.records.isEmpty())
        assertEquals(AutoState(presentSince = ts(5, 8, 0)), r.state)
    }

    @Test
    fun gehenBekommtZeitpunktDesErstenWeg() {
        val debounce = 5 * min
        var r = AutoLogic.onArrive(AutoState(), ts(5, 8, 0), debounce, zone)
        r = AutoLogic.onLeave(r.state, ts(5, 16, 40), debounce)
        val early = AutoLogic.onTick(r.state, ts(5, 16, 43), debounce)
        assertTrue(early.records.isEmpty())
        val done = AutoLogic.onTick(r.state, ts(5, 16, 46), debounce)
        assertEquals(listOf(PlannedRecord(StampType.OUT, ts(5, 16, 40))), done.records)
        assertFalse(done.state.present)
    }

    @Test
    fun wiederkommenNachVerzoegerungSchreibtGehenUndKommen() {
        val debounce = 5 * min
        var r = AutoLogic.onArrive(AutoState(), ts(5, 8, 0), debounce, zone)
        r = AutoLogic.onLeave(r.state, ts(5, 12, 0), debounce)
        r = AutoLogic.onArrive(r.state, ts(5, 12, 40), debounce, zone)
        assertEquals(
            listOf(PlannedRecord(StampType.OUT, ts(5, 12, 0)), PlannedRecord(StampType.IN, ts(5, 12, 40))),
            r.records,
        )
    }

    @Test
    fun doppeltesKommenWirdIgnoriertVeralteterZustandNicht() {
        val debounce = 5 * min
        val r1 = AutoLogic.onArrive(AutoState(), ts(5, 8, 0), debounce, zone)
        val r2 = AutoLogic.onArrive(r1.state, ts(5, 9, 0), debounce, zone)
        assertTrue(r2.records.isEmpty())
        // Am nächsten Tag (Gehen wurde verpasst) wird wieder ein Kommen erfasst, aber kein Gehen erfunden.
        val r3 = AutoLogic.onArrive(r2.state, ts(6, 7, 58), debounce, zone)
        assertEquals(listOf(PlannedRecord(StampType.IN, ts(6, 7, 58))), r3.records)
    }

    @Test
    fun ohneVerzoegerungSofortGehen() {
        val r = AutoLogic.onLeave(AutoState(presentSince = ts(5, 8, 0)), ts(5, 17, 0), 0)
        assertEquals(listOf(PlannedRecord(StampType.OUT, ts(5, 17, 0))), r.records)
    }

    // ---------- NFC / Manuell ----------

    @Test
    fun umschaltenRichtetSichNachGenauenQuellen() {
        assertEquals(StampType.IN, PreciseLogic.nextToggleType(emptyList()))
        val list = mutableListOf(
            ev(ts(5, 7, 56), StampType.IN, StampSource.WLAN),
        )
        assertEquals("WLAN zählt nicht für das Umschalten", StampType.IN, PreciseLogic.nextToggleType(list))
        list += ev(ts(5, 7, 59), StampType.IN, StampSource.NFC)
        assertEquals(StampType.OUT, PreciseLogic.nextToggleType(list))
        list += ev(ts(5, 16, 40), StampType.OUT, StampSource.MANUELL)
        assertEquals(StampType.IN, PreciseLogic.nextToggleType(list))
    }

    // ---------- Formatierung / CSV ----------

    @Test
    fun formatierung() {
        assertEquals("8:44", TimeFormat.hm(524))
        assertEquals("+0:44", TimeFormat.signedHm(44))
        assertEquals("-1:05", TimeFormat.signedHm(-65))
        assertEquals("8,73", TimeFormat.decimalHours(524))
        assertEquals("Mo", TimeFormat.weekday(LocalDate.of(2026, 10, 5)))
        assertEquals("Oktober 2026", TimeFormat.month(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun csvExport() {
        val events = listOf(
            ev(ts(5, 7, 59), StampType.IN, StampSource.NFC),
            ev(ts(5, 16, 40), StampType.OUT, StampSource.NFC),
            StampEvent(99, ts(5, 12, 0), StampType.OUT, StampSource.MANUELL, "Notiz; mit \"Zeichen\"", ts(5, 12, 0), ts(5, 11, 0), true),
        )
        val raw = CsvExport.rawEvents(events, zone)
        assertTrue(raw.startsWith("﻿ID;Datum;"))
        assertTrue(raw.contains("99;05.10.2026;Mo;12:00:00;Gehen;Manuell;\"Notiz; mit \"\"Zeichen\"\"\";ja;05.10.2026;11:00:00;ja;"))

        val days = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY)
        val daily = CsvExport.daily(days, 8 * 60, zone)
        val line = daily.lines()[1]
        assertTrue(line, line.startsWith("05.10.2026;Mo;07:59;NFC;16:40;NFC;8:41;8,68;8:00;+0:41;07:59;16:40;"))
    }
}
