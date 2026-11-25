package de.droh.stempeluhr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BackupTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun ts(day: Int, h: Int, m: Int, s: Int = 0, ms: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, h, m, s).atZone(zone).toInstant().toEpochMilli() + ms

    private val events = listOf(
        BackupEvent(ts(5, 7, 59, 12, 345), StampType.IN, StampSource.WLAN, null, ts(5, 7, 59, 12, 400), null, false),
        BackupEvent(ts(5, 16, 40), StampType.OUT, StampSource.MANUELL, "Notiz mit \"Zeichen\"; und ß", ts(5, 18, 0), ts(5, 16, 30), false),
        BackupEvent(ts(6, 8, 0, 1, 1), StampType.IN, StampSource.NFC, null, ts(6, 8, 0, 1, 1), null, true),
    )
    private val days = listOf(
        BackupImportedDay(LocalDate.of(2026, 9, 1), 441, null, "Stundenzettel September.pdf", ts(1, 9, 0)),
        BackupImportedDay(LocalDate.of(2026, 9, 2), 240, "krank", null, ts(1, 9, 0)),
    )
    private val settings = mapOf(
        "wifi_enabled" to PrefValue.Bool(true),
        "wifi_ssids" to PrefValue.Text("Firma-Intern, Firma Gast"),
        "geo_lat" to PrefValue.Text("51.2277"),
        "geo_radius" to PrefValue.Number(150),
        "confirmed_reconciles" to PrefValue.TextSet(setOf("2026-09=1a2b3c", "2026-08=ffff")),
    )
    private val backup = BackupData(ts(7, 12, 0), "1.0.42", "Samsung SM-S901B", events, days, settings)

    @Test
    fun sicherungUeberstehtSpeichernUndLesenUnveraendert() {
        val text = Backup.encode(backup)
        assertTrue(Backup.looksLikeBackup(text))
        assertEquals(backup, Backup.decode(text))
        // Auch mit BOM und Leerraum davor (z. B. nach einem Umweg über einen Editor).
        assertTrue(Backup.looksLikeBackup("\uFEFF\n  $text"))
        assertEquals(backup, Backup.decode("\uFEFF$text"))
    }

    @Test
    fun andereDateienSindKeineSicherung() {
        assertFalse(Backup.looksLikeBackup("ID;Datum;Wochentag;Uhrzeit;Art;Quelle;Notiz"))
        assertFalse(Backup.looksLikeBackup("{\"irgendwas\": 1}"))
        assertFalse(Backup.looksLikeBackup("Muster Max 20:30 01.07.2026 7:21"))
        assertThrows(BackupFormatException::class.java) { Backup.decode("kein json") }
        assertThrows(BackupFormatException::class.java) { Backup.decode("{\"format\":\"etwas-anderes\",\"version\":1}") }
        assertThrows(BackupFormatException::class.java) { Backup.decode("{\"format\":\"${Backup.FORMAT}\"}") }
    }

    @Test
    fun sicherungAusNeuererVersionWirdAbgelehnt() {
        val e = assertThrows(BackupFormatException::class.java) {
            Backup.decode("{\"format\":\"${Backup.FORMAT}\",\"version\":${Backup.VERSION + 1},\"events\":[]}")
        }
        assertTrue(e.message!!.contains("neueren App-Version"))
    }

    @Test
    fun unlesbareEintraegeWerdenGezaehltStattAllesAbzubrechen() {
        val text = """
            {"format":"${Backup.FORMAT}","version":1,"createdAt":5,
             "events":[
               {"ts":1000,"type":"IN","source":"WLAN","createdAt":1000},
               {"ts":2000,"type":"IN","source":"BLUETOOTH","createdAt":2000},
               {"type":"OUT","source":"WLAN"},
               "kaputt",
               {"ts":3000,"type":"OUT","source":"NFC","note":null,"originalTs":null,"deleted":true}
             ],
             "importedDays":[{"date":"2026-09-01","minutes":60},{"date":"01.09.2026","minutes":60},{"date":"2026-09-02"}],
             "settings":{"wifi_enabled":true,"geo_radius":150,"zahl_mit_komma":1.5,"objekt":{},"leer":null,"liste":["a",1,"b"]}}
        """.trimIndent()
        val data = Backup.decode(text)
        assertEquals(listOf(1000L, 3000L), data.events.map { it.ts })
        // JSON-null darf nicht zum Text "null" werden.
        assertNull(data.events[1].note)
        assertNull(data.events[1].originalTs)
        assertTrue(data.events[1].deleted)
        assertEquals(1000L, data.events[0].createdAt)
        assertEquals(listOf(LocalDate.of(2026, 9, 1)), data.importedDays.map { it.date })
        assertEquals(5, data.unreadable)
        assertEquals(
            mapOf(
                "wifi_enabled" to PrefValue.Bool(true),
                "geo_radius" to PrefValue.Number(150),
                "liste" to PrefValue.TextSet(setOf("a", "b")),
            ),
            data.settings,
        )
        assertNull(data.device)
        assertNull(data.appVersion)
    }

    @Test
    fun wiederherstellenErgaenztNurFehlendes() {
        // Auf dem neuen Handy gibt es schon das erste Ereignis und den ersten importierten Tag.
        val existingEvents = setOf(events[0].key)
        val existingDays = setOf(days[0].date)
        val plan = Backup.plan(backup, existingEvents, existingDays)
        assertEquals(listOf(events[1], events[2]), plan.newEvents)
        assertEquals(1, plan.knownEvents)
        assertEquals(listOf(days[1]), plan.newDays)
        assertEquals(1, plan.knownDays)
        assertFalse(plan.nothingNew)

        // Dieselbe Sicherung ein zweites Mal einzulesen ändert nichts mehr.
        val again = Backup.plan(backup, events.map { it.key }.toSet(), days.map { it.date }.toSet())
        assertTrue(again.nothingNew)
        assertEquals(3, again.knownEvents)
        assertEquals(2, again.knownDays)
    }

    @Test
    fun ereignisseWerdenSekundengenauWiedererkannt() {
        // Der Rohdaten-Export (CSV) kennt keine Millisekunden: dasselbe Ereignis kommt dort gerundet an.
        val fromCsv = EventKey.of(ts(5, 7, 59, 12), StampType.IN, StampSource.WLAN)
        assertEquals(fromCsv, events[0].key)
        val plan = Backup.plan(backup, setOf(fromCsv), emptySet())
        assertEquals(1, plan.knownEvents)
        // Andere Art oder Quelle zur selben Sekunde ist ein anderes Ereignis.
        assertFalse(EventKey.of(ts(5, 7, 59, 12), StampType.OUT, StampSource.WLAN) == events[0].key)
        assertFalse(EventKey.of(ts(5, 7, 59, 12), StampType.IN, StampSource.NFC) == events[0].key)
        assertFalse(EventKey.of(ts(5, 7, 59, 13), StampType.IN, StampSource.WLAN) == events[0].key)
    }

    @Test
    fun doppelteEintraegeInDerSicherungWerdenNurEinmalUebernommen() {
        val twice = backup.copy(events = events + events[0].copy(ts = events[0].ts + 200), importedDays = days + days[0])
        val plan = Backup.plan(twice, emptySet(), emptySet())
        assertEquals(events, plan.newEvents)
        assertEquals(1, plan.knownEvents)
        assertEquals(days, plan.newDays)
        assertEquals(1, plan.knownDays)
    }

    @Test
    fun rohdatenExportUndSicherungErgebenDieselbenSchluessel() {
        // Ereignisse erst als CSV (alter Weg), dann als Sicherung: nichts darf doppelt entstehen.
        val stamps = events.mapIndexed { i, e -> StampEvent(i + 1L, e.ts, e.type, e.source, e.note, e.createdAt, e.originalTs, e.deleted) }
        val rows = ImportParser.parse(CsvExport.rawEvents(stamps, zone), zone).rawRows
        assertEquals(3, rows.size)
        val restoredFromCsv = rows.map { EventKey.of(it.ts, it.type, it.source) }.toSet()
        assertTrue(Backup.plan(backup, restoredFromCsv, emptySet()).newEvents.isEmpty())
    }

    @Test
    fun dateiname() {
        assertEquals("Stempeluhr-Sicherung-2026-11-25.json", Backup.fileName(LocalDate.of(2026, 11, 25)))
    }
}
