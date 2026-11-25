package de.droh.stempeluhr.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/**
 * Woran ein Ereignis beim Zusammenführen wiedererkannt wird: Sekunde, Art und Quelle.
 *
 * Sekundengenau, weil der Rohdaten-Export (CSV) keine Millisekunden enthält – so werden Ereignisse
 * auch dann als „schon vorhanden“ erkannt, wenn sie einmal per CSV und einmal per Sicherung ankommen.
 */
data class EventKey(val second: Long, val type: StampType, val source: StampSource) {
    companion object {
        fun of(ts: Long, type: StampType, source: StampSource) = EventKey(Math.floorDiv(ts, 1000L), type, source)
    }
}

/** Ein Ereignis in einer Sicherung (ohne die lokale Datenbank-ID). */
data class BackupEvent(
    val ts: Long,
    val type: StampType,
    val source: StampSource,
    val note: String?,
    val createdAt: Long,
    val originalTs: Long?,
    val deleted: Boolean,
) {
    val key: EventKey get() = EventKey.of(ts, type, source)
}

/** Ein importierter Tag in einer Sicherung, inklusive Herkunft (Dateiname) und Import-Zeitpunkt. */
data class BackupImportedDay(
    val date: LocalDate,
    val minutes: Long,
    val note: String?,
    val source: String?,
    val importedAt: Long,
)

/** Wert einer Einstellung in einer Sicherung. */
sealed interface PrefValue {
    data class Bool(val value: Boolean) : PrefValue
    data class Number(val value: Int) : PrefValue
    data class Text(val value: String) : PrefValue
    data class TextSet(val value: Set<String>) : PrefValue
}

/** Vollständige Sicherung für den Umzug auf ein anderes Handy. */
data class BackupData(
    /** Zeitpunkt der Sicherung (ms seit 1970). */
    val createdAt: Long,
    val appVersion: String?,
    /** Name des Geräts, auf dem die Sicherung erstellt wurde (nur zur Anzeige). */
    val device: String?,
    val events: List<BackupEvent>,
    val importedDays: List<BackupImportedDay>,
    val settings: Map<String, PrefValue>,
    /** Einträge der Datei, die nicht gelesen werden konnten (z. B. unbekannte Quelle aus neuerer Version). */
    val unreadable: Int = 0,
)

/** Was eine Wiederherstellung auf diesem Handy bewirken würde (für die Vorschau und zum Ausführen). */
data class RestorePlan(
    /** Ereignisse, die noch fehlen und ergänzt werden. */
    val newEvents: List<BackupEvent>,
    /** Ereignisse der Sicherung, die es hier schon gibt (werden übersprungen). */
    val knownEvents: Int,
    /** Importierte Tage, die noch fehlen und ergänzt werden. */
    val newDays: List<BackupImportedDay>,
    /** Importierte Tage, für deren Datum es hier schon einen Import gibt (bleiben unverändert). */
    val knownDays: Int,
) {
    val nothingNew: Boolean get() = newEvents.isEmpty() && newDays.isEmpty()
}

class BackupFormatException(message: String) : Exception(message)

/**
 * Sicherung aller Daten als eine JSON-Datei – für den Umzug auf ein neues Handy.
 *
 * Wiederherstellen führt immer zusammen und löscht nichts: Was auf dem Zielgerät schon vorhanden ist,
 * bleibt unverändert, Fehlendes wird ergänzt. Dieselbe Sicherung mehrfach einzulesen ist unschädlich.
 */
object Backup {
    const val FORMAT = "stempeluhr-sicherung"
    const val VERSION = 1
    const val MIME = "application/json"

    fun fileName(date: LocalDate): String = "Stempeluhr-Sicherung-$date.json"

    /** Schneller Test, ob ein Text eine Sicherung ist (statt CSV oder Stundenzettel). */
    fun looksLikeBackup(text: String): Boolean {
        val t = text.removePrefix("\uFEFF").trimStart()
        return t.startsWith("{") && t.contains("\"$FORMAT\"")
    }

    fun encode(data: BackupData): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("createdAt", data.createdAt)
        data.appVersion?.let { root.put("appVersion", it) }
        data.device?.let { root.put("device", it) }

        val events = JSONArray()
        for (e in data.events) {
            val o = JSONObject()
            o.put("ts", e.ts)
            o.put("type", e.type.name)
            o.put("source", e.source.name)
            e.note?.let { o.put("note", it) }
            o.put("createdAt", e.createdAt)
            e.originalTs?.let { o.put("originalTs", it) }
            if (e.deleted) o.put("deleted", true)
            events.put(o)
        }
        root.put("events", events)

        val days = JSONArray()
        for (d in data.importedDays) {
            val o = JSONObject()
            o.put("date", d.date.toString())
            o.put("minutes", d.minutes)
            d.note?.let { o.put("note", it) }
            d.source?.let { o.put("source", it) }
            o.put("importedAt", d.importedAt)
            days.put(o)
        }
        root.put("importedDays", days)

        val settings = JSONObject()
        for ((key, value) in data.settings) {
            when (value) {
                is PrefValue.Bool -> settings.put(key, value.value)
                is PrefValue.Number -> settings.put(key, value.value)
                is PrefValue.Text -> settings.put(key, value.value)
                is PrefValue.TextSet -> settings.put(key, JSONArray().apply { value.value.sorted().forEach { put(it) } })
            }
        }
        root.put("settings", settings)
        return root.toString()
    }

    /** @throws BackupFormatException mit einer Meldung für den Nutzer, wenn die Datei nicht passt. */
    fun decode(text: String): BackupData {
        val root = try {
            JSONObject(text.removePrefix("\uFEFF"))
        } catch (_: JSONException) {
            throw BackupFormatException("Die Datei ist keine lesbare Stempeluhr-Sicherung.")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupFormatException("Die Datei ist keine Stempeluhr-Sicherung.")
        }
        val version = root.optInt("version", 0)
        if (version < 1) throw BackupFormatException("Die Sicherung hat kein gültiges Format.")
        if (version > VERSION) {
            throw BackupFormatException(
                "Die Sicherung stammt aus einer neueren App-Version. Bitte zuerst die App auf diesem Handy aktualisieren.",
            )
        }

        var unreadable = 0
        val events = mutableListOf<BackupEvent>()
        root.optJSONArray("events")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i)?.let(::readEvent)
                if (e == null) unreadable++ else events += e
            }
        }
        val days = mutableListOf<BackupImportedDay>()
        root.optJSONArray("importedDays")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i)?.let(::readDay)
                if (d == null) unreadable++ else days += d
            }
        }
        val settings = linkedMapOf<String, PrefValue>()
        root.optJSONObject("settings")?.let { o ->
            for (key in o.keys()) readPref(o.opt(key))?.let { settings[key] = it }
        }
        return BackupData(
            createdAt = root.optLong("createdAt", 0L),
            appVersion = text(root, "appVersion"),
            device = text(root, "device"),
            events = events,
            importedDays = days,
            settings = settings,
            unreadable = unreadable,
        )
    }

    /**
     * Vergleicht eine Sicherung mit dem, was auf diesem Handy schon vorhanden ist.
     * Doppelte Einträge innerhalb der Sicherung selbst werden ebenfalls nur einmal übernommen.
     */
    fun plan(data: BackupData, existingEvents: Set<EventKey>, existingDays: Set<LocalDate>): RestorePlan {
        val seenEvents = existingEvents.toHashSet()
        val newEvents = data.events.filter { seenEvents.add(it.key) }
        val seenDays = existingDays.toHashSet()
        val newDays = data.importedDays.filter { seenDays.add(it.date) }
        return RestorePlan(
            newEvents = newEvents,
            knownEvents = data.events.size - newEvents.size,
            newDays = newDays,
            knownDays = data.importedDays.size - newDays.size,
        )
    }

    private fun readEvent(o: JSONObject): BackupEvent? {
        val ts = o.optLong("ts", -1L)
        if (ts <= 0L) return null
        val type = StampType.entries.firstOrNull { it.name == o.optString("type") } ?: return null
        val source = StampSource.entries.firstOrNull { it.name == o.optString("source") } ?: return null
        return BackupEvent(
            ts = ts,
            type = type,
            source = source,
            note = text(o, "note"),
            createdAt = o.optLong("createdAt", ts),
            originalTs = if (o.isNull("originalTs")) null else o.optLong("originalTs", -1L).takeIf { it > 0L },
            deleted = o.optBoolean("deleted", false),
        )
    }

    private fun readDay(o: JSONObject): BackupImportedDay? {
        val date = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return null
        val minutes = o.optLong("minutes", -1L)
        if (minutes < 0L) return null
        return BackupImportedDay(
            date = date,
            minutes = minutes,
            note = text(o, "note"),
            source = text(o, "source"),
            importedAt = o.optLong("importedAt", 0L),
        )
    }

    private fun readPref(value: Any?): PrefValue? = when (value) {
        is Boolean -> PrefValue.Bool(value)
        is Number -> {
            val d = value.toDouble()
            if (d == Math.rint(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) PrefValue.Number(d.toInt()) else null
        }
        is String -> PrefValue.Text(value)
        is JSONArray -> PrefValue.TextSet((0 until value.length()).mapNotNull { value.opt(it) as? String }.toSet())
        else -> null
    }

    /** Liest einen optionalen Text. (isNull vorab, weil Android bei JSON-null sonst den Text "null" liefert.) */
    private fun text(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).ifEmpty { null }
}
