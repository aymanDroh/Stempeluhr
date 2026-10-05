package de.droh.stempeluhr.core

import java.time.ZoneId

/**
 * Zustand einer automatischen Quelle (WLAN oder Geofence).
 * 0 bedeutet jeweils "nicht gesetzt".
 */
data class AutoState(
    /** Zeitpunkt des Kommens, solange die Quelle "anwesend" meldet. */
    val presentSince: Long = 0,
    /** Zeitpunkt, an dem die Quelle "weg" gemeldet hat; wird erst nach der Verzögerung zum Gehen. */
    val pendingLeaveAt: Long = 0,
) {
    val present: Boolean get() = presentSince != 0L || pendingLeaveAt != 0L
}

/** Ein zu speicherndes Ereignis. */
data class PlannedRecord(val type: StampType, val ts: Long, val note: String? = null)

data class AutoResult(val state: AutoState, val records: List<PlannedRecord>)

/**
 * Reine Entscheidungslogik für automatische Quellen, ohne Android-Abhängigkeiten.
 *
 * - Kurze Aussetzer (WLAN-Abbruch, GPS-Ungenauigkeit am Geofence-Rand) werden über
 *   [debounceMs] geglättet: Ein "weg" wird erst zum Gehen, wenn innerhalb dieser Zeit
 *   kein erneutes "da" kommt. Das Gehen erhält dann den Zeitpunkt des ersten "weg".
 * - Es werden niemals Zeiten erfunden: Fehlt ein Gehen (z. B. Handy war aus), bleibt
 *   die Lücke sichtbar und kann manuell korrigiert werden.
 */
object AutoLogic {

    fun onArrive(state: AutoState, ts: Long, debounceMs: Long, zone: ZoneId): AutoResult {
        val records = mutableListOf<PlannedRecord>()
        var s = state
        if (s.pendingLeaveAt != 0L) {
            if (ts - s.pendingLeaveAt <= debounceMs) {
                // Nur ein kurzer Aussetzer – weiterhin anwesend, nichts speichern.
                return AutoResult(s.copy(pendingLeaveAt = 0), records)
            }
            records += PlannedRecord(StampType.OUT, s.pendingLeaveAt)
            s = AutoState()
        }
        if (s.presentSince != 0L) {
            if (Summary.localDate(s.presentSince, zone) == Summary.localDate(ts, zone)) {
                // Schon anwesend (z. B. wiederholte Meldung derselben Verbindung).
                return AutoResult(s, records)
            }
            // Veralteter Zustand von einem früheren Tag: Gehen wurde verpasst.
            // Lücke bleibt sichtbar, es wird kein Gehen erfunden.
            s = AutoState()
        }
        records += PlannedRecord(StampType.IN, ts)
        return AutoResult(AutoState(presentSince = ts), records)
    }

    fun onLeave(state: AutoState, ts: Long, debounceMs: Long): AutoResult {
        if (state.pendingLeaveAt != 0L) return AutoResult(state, emptyList())
        if (debounceMs <= 0L) {
            return AutoResult(AutoState(), listOf(PlannedRecord(StampType.OUT, ts)))
        }
        return AutoResult(state.copy(pendingLeaveAt = ts), emptyList())
    }

    /** Macht ein vorgemerktes "weg" zum Gehen, sobald die Verzögerung abgelaufen ist. */
    fun onTick(state: AutoState, now: Long, debounceMs: Long): AutoResult {
        if (state.pendingLeaveAt != 0L && now - state.pendingLeaveAt >= debounceMs) {
            return AutoResult(AutoState(), listOf(PlannedRecord(StampType.OUT, state.pendingLeaveAt)))
        }
        return AutoResult(state, emptyList())
    }
}

/** Logik für die "genauen" Quellen NFC und Manuell (Kachel / Button). */
object PreciseLogic {
    val PRECISE_SOURCES = setOf(StampSource.NFC, StampSource.MANUELL)

    /**
     * Welche Art ein Umschalt-Stempel als Nächstes erzeugt: Nach einem Kommen (NFC oder manuell)
     * am selben Tag folgt Gehen, sonst Kommen.
     */
    fun nextToggleType(todayEvents: List<StampEvent>): StampType {
        val last = todayEvents
            .filter { !it.deleted && it.source in PRECISE_SOURCES }
            .maxWithOrNull(compareBy({ it.ts }, { it.id }))
        return if (last?.type == StampType.IN) StampType.OUT else StampType.IN
    }
}
