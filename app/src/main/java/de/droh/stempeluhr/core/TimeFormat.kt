package de.droh.stempeluhr.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Deutsche Formatierung ohne Abhängigkeit von Geräte-Locale. */
object TimeFormat {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val timeSecFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val shortDateFmt = DateTimeFormatter.ofPattern("dd.MM.")

    private val weekdays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    private val months = listOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )

    fun time(ts: Long, zone: ZoneId): String = timeFmt.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun timeSec(ts: Long, zone: ZoneId): String = timeSecFmt.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun date(ts: Long, zone: ZoneId): String = dateFmt.format(Instant.ofEpochMilli(ts).atZone(zone))
    fun date(d: LocalDate): String = dateFmt.format(d)
    fun shortDate(d: LocalDate): String = shortDateFmt.format(d)
    fun weekday(d: LocalDate): String = weekdays[d.dayOfWeek.value - 1]
    fun month(d: LocalDate): String = "${months[d.monthValue - 1]} ${d.year}"

    /** Minuten als h:mm, z. B. 524 -> "8:44". */
    fun hm(minutes: Long): String {
        val m = abs(minutes)
        val s = "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
        return if (minutes < 0) "-$s" else s
    }

    /** Minuten als h:mm mit Vorzeichen, z. B. "+0:44" oder "-1:05". */
    fun signedHm(minutes: Long): String = if (minutes >= 0) "+${hm(minutes)}" else hm(minutes)

    /** Minuten als Dezimalstunden mit Komma, z. B. 524 -> "8,73". */
    fun decimalHours(minutes: Long): String {
        val hundredths = Math.round(minutes * 100.0 / 60.0)
        val sign = if (hundredths < 0) "-" else ""
        val h = abs(hundredths)
        return "$sign${h / 100},${(h % 100).toString().padStart(2, '0')}"
    }
}
