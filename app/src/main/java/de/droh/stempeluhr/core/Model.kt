package de.droh.stempeluhr.core

/** Art eines Stempel-Ereignisses. */
enum class StampType(val label: String) {
    IN("Kommen"),
    OUT("Gehen"),
}

/** Woher ein Ereignis stammt. */
enum class StampSource(val label: String, val automatic: Boolean) {
    NFC("NFC", false),
    WLAN("WLAN", true),
    GEOFENCE("Geofence", true),
    MANUELL("Manuell", false),
    ;

    companion object {
        /** Standard-Reihenfolge, nach der Kommen/Gehen eines Tages gewertet werden. */
        val DEFAULT_PRIORITY = listOf(MANUELL, NFC, WLAN, GEOFENCE)

        fun parsePriority(value: String?): List<StampSource> {
            val parsed = value.orEmpty().split(',')
                .mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }
                .distinct()
            // Fehlende Quellen hinten anhängen, damit die Liste immer vollständig ist.
            return parsed + DEFAULT_PRIORITY.filter { it !in parsed }
        }

        fun formatPriority(list: List<StampSource>): String = list.joinToString(",") { it.name }
    }
}

/** Ein gespeichertes Ereignis. Zeitstempel in Millisekunden seit 1970 (UTC). */
data class StampEvent(
    val id: Long,
    val ts: Long,
    val type: StampType,
    val source: StampSource,
    val note: String?,
    val createdAt: Long,
    /** Ursprüngliche Zeit, falls das Ereignis nachträglich geändert wurde. */
    val originalTs: Long?,
    val deleted: Boolean,
) {
    val edited: Boolean get() = originalTs != null && originalTs != ts
}
