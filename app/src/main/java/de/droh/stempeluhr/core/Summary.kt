package de.droh.stempeluhr.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Erstes Kommen und letztes Gehen einer Quelle an einem Tag. */
data class SourceTimes(
    val firstIn: Long?,
    /** Letztes Gehen – nur gesetzt, wenn danach kein Kommen derselben Quelle mehr folgt. */
    val lastOut: Long?,
    /** true, wenn das letzte Ereignis dieser Quelle ein Kommen ist (noch anwesend / Gehen fehlt). */
    val open: Boolean,
)

data class Stamp(val ts: Long, val source: StampSource)

data class DaySummary(
    val date: LocalDate,
    /** Nicht gelöschte Ereignisse des Tages, aufsteigend sortiert. */
    val events: List<StampEvent>,
    val bySource: Map<StampSource, SourceTimes>,
    /** Gewertetes Kommen (erste Quelle in der Prioritätsliste, die ein Kommen hat). */
    val kommen: Stamp?,
    /** Gewertetes Gehen (erste Quelle in der Prioritätsliste, die ein abgeschlossenes Gehen hat). */
    val gehen: Stamp?,
    /** Importierter Tag (nur Dauer bekannt), falls vorhanden. */
    val imported: ImportedDay? = null,
    val options: EvalOptions = EvalOptions(),
) {
    /** Brutto-Anwesenheit aus der eigenen Erfassung (Gehen minus Kommen). */
    val ownMinutes: Long?
        get() = if (kommen != null && gehen != null && gehen.ts > kommen.ts) {
            (gehen.ts - kommen.ts) / 60_000
        } else {
            null
        }

    /** Dauer aus dem Import; Krank-/Urlaubstage zählen je nach Einstellung mit 0. */
    val importMinutes: Long?
        get() = imported?.let { if (it.isAbsence && !options.countAbsence) 0L else it.minutes }

    /** Gewertete Dauer: je nach Einstellung Import vor eigener Erfassung oder umgekehrt. */
    val durationMinutes: Long?
        get() = if (options.preferImport) importMinutes ?: ownMinutes else ownMinutes ?: importMinutes

    /** Woher die gewertete Dauer stammt. */
    val durationFromImport: Boolean
        get() = importMinutes != null && (options.preferImport || ownMinutes == null)

    fun saldoMinutes(targetMinutes: Int): Long? = durationMinutes?.let { it - targetMinutes }
}

/** Einstellungen für die Auswertung importierter Tage. */
data class EvalOptions(
    /** Bei Überschneidung zählt der Import (Firmendaten) statt der eigenen Erfassung. */
    val preferImport: Boolean = true,
    /** Krank-/Urlaubstage aus dem Import mit ihrer Dauer mitzählen. */
    val countAbsence: Boolean = true,
)

data class PeriodTotal(val days: Int, val minutes: Long, val saldoMinutes: Long)

object Summary {

    fun localDate(ts: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    /** Fasst alle nicht gelöschten Ereignisse nach Tagen zusammen, neuester Tag zuerst. */
    fun byDay(
        events: List<StampEvent>,
        zone: ZoneId,
        priority: List<StampSource>,
        imported: List<ImportedDay> = emptyList(),
        options: EvalOptions = EvalOptions(),
    ): List<DaySummary> {
        val byDate = events.filter { !it.deleted }.groupBy { localDate(it.ts, zone) }
        val importByDate = imported.associateBy { it.date }
        return (byDate.keys + importByDate.keys)
            .map { date -> forDay(date, byDate[date].orEmpty(), priority, importByDate[date], options) }
            .sortedByDescending { it.date }
    }

    /** Auswertung eines einzelnen Tages. [events] dürfen unsortiert sein; gelöschte werden ignoriert. */
    fun forDay(
        date: LocalDate,
        events: List<StampEvent>,
        priority: List<StampSource>,
        imported: ImportedDay? = null,
        options: EvalOptions = EvalOptions(),
    ): DaySummary {
        val sorted = events.filter { !it.deleted }.sortedWith(compareBy({ it.ts }, { it.id }))
        val bySource = sorted.groupBy { it.source }.mapValues { (_, list) ->
            val firstIn = list.firstOrNull { it.type == StampType.IN }?.ts
            val last = list.last()
            val lastOut = if (last.type == StampType.OUT) last.ts else null
            SourceTimes(firstIn = firstIn, lastOut = lastOut, open = last.type == StampType.IN)
        }
        val kommen = priority.firstNotNullOfOrNull { src -> bySource[src]?.firstIn?.let { Stamp(it, src) } }
        val gehen = priority.firstNotNullOfOrNull { src -> bySource[src]?.lastOut?.let { Stamp(it, src) } }
        return DaySummary(date, sorted, bySource, kommen, gehen, imported, options)
    }

    fun total(days: List<DaySummary>, targetMinutes: Int): PeriodTotal {
        val counted = days.filter { it.durationMinutes != null }
        val minutes = counted.sumOf { it.durationMinutes!! }
        return PeriodTotal(
            days = counted.size,
            minutes = minutes,
            saldoMinutes = minutes - counted.size.toLong() * targetMinutes,
        )
    }
}
