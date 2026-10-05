package de.droh.stempeluhr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class ReconcileTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private var id = 1L

    private fun stamp(date: LocalDate, h: Int, m: Int, type: StampType, src: StampSource = StampSource.WLAN): StampEvent {
        val ts = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        return StampEvent(id++, ts, type, src, null, ts, null, false)
    }

    @Test
    fun alleFaelleWerdenErkannt() {
        val d = { day: Int -> LocalDate.of(2026, 9, day) }
        val events = listOf(
            // 01.: stimmt (8:00 vs. 8:03, Toleranz 5)
            stamp(d(1), 8, 0, StampType.IN), stamp(d(1), 16, 3, StampType.OUT),
            // 02.: Abweichung (eigene 8:00, Firma 7:00)
            stamp(d(2), 8, 0, StampType.IN), stamp(d(2), 16, 0, StampType.OUT),
            // 03.: fehlt bei Firma
            stamp(d(3), 9, 0, StampType.IN, StampSource.NFC), stamp(d(3), 13, 0, StampType.OUT, StampSource.NFC),
            // 04.: eigene Erfassung unvollständig (kein Gehen)
            stamp(d(4), 8, 0, StampType.IN),
            // Anderer Monat: darf nicht auftauchen
            stamp(LocalDate.of(2026, 10, 1), 8, 0, StampType.IN), stamp(LocalDate.of(2026, 10, 1), 9, 0, StampType.OUT),
        )
        val imported = listOf(
            ImportedDay(d(1), 8 * 60, null),
            ImportedDay(d(2), 7 * 60, null),
            ImportedDay(d(4), 6 * 60, null),
            ImportedDay(d(5), 5 * 60, null), // nur bei Firma
            ImportedDay(d(6), 8 * 60, "krank"),
        )
        val days = Summary.byDay(events, zone, StampSource.DEFAULT_PRIORITY, imported)
        assertEquals(listOf(YearMonth.of(2026, 9)), Reconcile.importedMonths(days))

        val c = Reconcile.compare(days, YearMonth.of(2026, 9), 5)
        assertEquals(
            listOf(
                MatchStatus.OK, MatchStatus.DIFF, MatchStatus.ONLY_OWN,
                MatchStatus.OWN_INCOMPLETE, MatchStatus.ONLY_IMPORT, MatchStatus.ABSENCE,
            ),
            c.days.map { it.status },
        )
        assertEquals(3L, c.days[0].diffMinutes)
        assertEquals(60L, c.days[1].diffMinutes)
        assertEquals(4, c.problems)
        assertEquals((8 + 7 + 6 + 5 + 8) * 60L, c.importTotal)
        assertEquals(8 * 60 + 3 + 8 * 60 + 4 * 60L, c.ownTotal)

        val csv = Reconcile.csv(c, zone)
        assertTrue(csv.contains("02.09.2026;Mi;7:00;;8:00;08:00;WLAN;16:00;WLAN;+1:00;Abweichung"))
        assertTrue(csv.contains("Summe;"))
    }
}
