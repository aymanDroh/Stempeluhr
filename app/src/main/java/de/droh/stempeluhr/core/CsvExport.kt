package de.droh.stempeluhr.core

import java.time.ZoneId

/**
 * CSV-Export für deutsches Excel: Semikolon als Trenner, Dezimalkomma,
 * UTF-8 mit BOM (damit Umlaute in Excel korrekt angezeigt werden).
 */
object CsvExport {
    private const val BOM = "﻿"
    private const val NL = "\r\n"

    /** Alle Rohdaten-Ereignisse, inklusive gelöschter (markiert), aufsteigend nach Zeit. */
    fun rawEvents(events: List<StampEvent>, zone: ZoneId): String {
        val sb = StringBuilder(BOM)
        sb.append(
            row(
                "ID", "Datum", "Wochentag", "Uhrzeit", "Art", "Quelle", "Notiz",
                "Geändert", "Ursprüngliches Datum", "Ursprüngliche Uhrzeit", "Gelöscht", "Erfasst am",
            ),
        )
        for (e in events.sortedWith(compareBy({ it.ts }, { it.id }))) {
            val date = Summary.localDate(e.ts, zone)
            sb.append(
                row(
                    e.id.toString(),
                    TimeFormat.date(date),
                    TimeFormat.weekday(date),
                    TimeFormat.timeSec(e.ts, zone),
                    e.type.label,
                    e.source.label,
                    e.note.orEmpty(),
                    if (e.edited) "ja" else "nein",
                    if (e.edited) TimeFormat.date(e.originalTs!!, zone) else "",
                    if (e.edited) TimeFormat.timeSec(e.originalTs!!, zone) else "",
                    if (e.deleted) "ja" else "nein",
                    "${TimeFormat.date(e.createdAt, zone)} ${TimeFormat.timeSec(e.createdAt, zone)}",
                ),
            )
        }
        return sb.toString()
    }

    /** Tagesübersicht mit gewerteten Zeiten und den Zeiten jeder einzelnen Quelle, aufsteigend nach Datum. */
    fun daily(days: List<DaySummary>, targetMinutes: Int, zone: ZoneId): String {
        val sb = StringBuilder(BOM)
        val sourceHeaders = StampSource.entries.flatMap { listOf("${it.label} Kommen", "${it.label} Gehen") }
        sb.append(
            row(
                *(
                    listOf(
                        "Datum", "Wochentag", "Kommen", "Quelle Kommen", "Gehen", "Quelle Gehen",
                        "Dauer (h:mm)", "Dauer (Stunden)", "Soll (h:mm)", "Saldo (h:mm)",
                    ) + sourceHeaders
                    ).toTypedArray(),
            ),
        )
        for (d in days.sortedBy { it.date }) {
            val duration = d.durationMinutes
            val sourceCols = StampSource.entries.flatMap { src ->
                val t = d.bySource[src]
                listOf(
                    t?.firstIn?.let { TimeFormat.time(it, zone) }.orEmpty(),
                    t?.lastOut?.let { TimeFormat.time(it, zone) }.orEmpty(),
                )
            }
            sb.append(
                row(
                    *(
                        listOf(
                            TimeFormat.date(d.date),
                            TimeFormat.weekday(d.date),
                            d.kommen?.let { TimeFormat.time(it.ts, zone) }.orEmpty(),
                            d.kommen?.source?.label.orEmpty(),
                            d.gehen?.let { TimeFormat.time(it.ts, zone) }.orEmpty(),
                            d.gehen?.source?.label.orEmpty(),
                            duration?.let { TimeFormat.hm(it) }.orEmpty(),
                            duration?.let { TimeFormat.decimalHours(it) }.orEmpty(),
                            if (duration != null) TimeFormat.hm(targetMinutes.toLong()) else "",
                            d.saldoMinutes(targetMinutes)?.let { TimeFormat.signedHm(it) }.orEmpty(),
                        ) + sourceCols
                        ).toTypedArray(),
                ),
            )
        }
        return sb.toString()
    }

    private fun row(vararg cells: String): String = cells.joinToString(";") { escape(it) } + NL

    private fun escape(value: String): String =
        if (value.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
