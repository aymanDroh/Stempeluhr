package de.droh.stempeluhr.data

import android.content.Context
import android.content.SharedPreferences
import de.droh.stempeluhr.core.AutoState
import de.droh.stempeluhr.core.EvalOptions
import de.droh.stempeluhr.core.PrefValue
import de.droh.stempeluhr.core.StudentRule
import de.droh.stempeluhr.core.StampSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.YearMonth

/** Einstellungen und der Zustand der automatischen Quellen (SharedPreferences). */
class Settings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state: SharedPreferences =
        context.applicationContext.getSharedPreferences("state", Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0L)

    /** Zählt bei jeder Änderung der Einstellungen hoch. */
    val changes: StateFlow<Long> = _changes

    private val _stateChanges = MutableStateFlow(0L)

    /** Zählt bei jeder Änderung des Zustands der automatischen Quellen hoch. */
    val stateChanges: StateFlow<Long> = _stateChanges

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _changes.value = _changes.value + 1
    }

    // ---------- WLAN ----------
    var wifiEnabled: Boolean
        get() = prefs.getBoolean("wifi_enabled", false)
        set(v) = edit { putBoolean("wifi_enabled", v) }

    /** WLAN-Namen (SSID) der Arbeit, mehrere durch Komma getrennt. */
    var wifiSsids: String
        get() = prefs.getString("wifi_ssids", "").orEmpty()
        set(v) = edit { putString("wifi_ssids", v) }

    val wifiSsidSet: Set<String>
        get() = wifiSsids.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // ---------- Geofence ----------
    var geoEnabled: Boolean
        get() = prefs.getBoolean("geo_enabled", false)
        set(v) = edit { putBoolean("geo_enabled", v) }

    var geoLat: Double?
        get() = prefs.getString("geo_lat", null)?.toDoubleOrNull()
        set(v) = edit { putString("geo_lat", v?.toString()) }

    var geoLon: Double?
        get() = prefs.getString("geo_lon", null)?.toDoubleOrNull()
        set(v) = edit { putString("geo_lon", v?.toString()) }

    var geoRadius: Int
        get() = prefs.getInt("geo_radius", 150)
        set(v) = edit { putInt("geo_radius", v) }

    // ---------- NFC ----------
    var nfcEnabled: Boolean
        get() = prefs.getBoolean("nfc_enabled", true)
        set(v) = edit { putBoolean("nfc_enabled", v) }

    // ---------- Allgemein ----------
    /** Verzögerung in Minuten, bevor ein "weg" von WLAN/Geofence als Gehen gilt. */
    var leaveDelayMinutes: Int
        get() = prefs.getInt("leave_delay_min", 5)
        set(v) = edit { putInt("leave_delay_min", v) }

    val leaveDelayMs: Long get() = leaveDelayMinutes * 60_000L

    var priority: List<StampSource>
        get() = StampSource.parsePriority(prefs.getString("priority", null))
        set(v) = edit { putString("priority", StampSource.formatPriority(v)) }

    /** Soll-Arbeitszeit pro Tag in Minuten (für den Saldo). */
    var targetMinutes: Int
        get() = prefs.getInt("target_minutes", 8 * 60)
        set(v) = edit { putInt("target_minutes", v) }

    var notifyOnStamp: Boolean
        get() = prefs.getBoolean("notify_on_stamp", true)
        set(v) = edit { putBoolean("notify_on_stamp", v) }

    /** Bei Überschneidung zählt der Import (Firmendaten) statt der eigenen Erfassung. */
    var preferImport: Boolean
        get() = prefs.getBoolean("prefer_import", true)
        set(v) = edit { putBoolean("prefer_import", v) }

    /** Krank-/Urlaubstage aus dem Import mit ihrer Dauer mitzählen. */
    var countAbsence: Boolean
        get() = prefs.getBoolean("count_absence", true)
        set(v) = edit { putBoolean("count_absence", v) }

    val evalOptions: EvalOptions get() = EvalOptions(preferImport, countAbsence)

    /** Abweichungen bis zu dieser Minutenzahl gelten beim Abgleich als übereinstimmend. */
    var reconcileToleranceMinutes: Int
        get() = prefs.getInt("reconcile_tolerance_min", 5)
        set(v) = edit { putInt("reconcile_tolerance_min", v) }

    /** Bestätigte Abgleiche: Monat -> Fingerabdruck der Daten zum Zeitpunkt der Bestätigung. */
    var confirmedReconciles: Map<YearMonth, String>
        get() = parseConfirmed(prefs.getStringSet(KEY_CONFIRMED, emptySet()).orEmpty())
        set(v) = edit { putStringSet(KEY_CONFIRMED, formatConfirmed(v)) }

    fun confirmReconcile(month: YearMonth, fingerprint: String) {
        confirmedReconciles = confirmedReconciles + (month to fingerprint)
    }

    fun unconfirmReconcile(month: YearMonth) {
        confirmedReconciles = confirmedReconciles - month
    }

    // ---------- Werkstudent ----------
    var studentEnabled: Boolean
        get() = prefs.getBoolean("student_enabled", true)
        set(v) = edit { putBoolean("student_enabled", v) }

    var studentLimitWeeks: Int
        get() = prefs.getInt("student_limit_weeks", StudentRule.DEFAULT_LIMIT_WEEKS)
        set(v) = edit { putInt("student_limit_weeks", v) }

    var studentLimitMinutes: Int
        get() = prefs.getInt("student_limit_minutes", StudentRule.DEFAULT_LIMIT_MINUTES)
        set(v) = edit { putInt("student_limit_minutes", v) }

    var showDeleted: Boolean
        get() = prefs.getBoolean("show_deleted", false)
        set(v) = edit { putBoolean("show_deleted", v) }

    // ---------- Darstellung ----------
    /** Helles oder dunkles Design: "system" (wie das Handy), "light" oder "dark". */
    var themeMode: String
        get() = prefs.getString("theme_mode", THEME_SYSTEM).takeIf { it in THEME_MODES } ?: THEME_SYSTEM
        set(v) = edit { putString("theme_mode", if (v in THEME_MODES) v else THEME_SYSTEM) }

    // ---------- Sicherung (Umzug auf ein anderes Handy) ----------

    /** Alle gespeicherten Einstellungen für die Sicherung. Der Zustand der automatischen Quellen gehört nicht dazu. */
    fun exportPrefs(): Map<String, PrefValue> {
        val stored = prefs.all
        return buildMap {
            for ((key, spec) in BACKUP_PREFS) {
                val value: PrefValue? = when (val raw = stored[key]) {
                    is Boolean -> PrefValue.Bool(raw)
                    is Int -> PrefValue.Number(raw)
                    is String -> PrefValue.Text(raw)
                    is Set<*> -> PrefValue.TextSet(raw.filterIsInstance<String>().toSet())
                    else -> null
                }
                if (value != null && spec.accepts(value)) put(key, value)
            }
        }
    }

    /**
     * Übernimmt die Einstellungen aus einer Sicherung, so dass dieses Handy danach wie das alte eingestellt ist:
     * Bekannte Einstellungen werden ersetzt; was in der Sicherung fehlt oder ungültig ist, fällt auf den
     * Standardwert zurück. Bestätigte Abgleiche werden zusammengeführt statt ersetzt.
     * @return Anzahl der übernommenen Werte.
     */
    fun importPrefs(values: Map<String, PrefValue>): Int {
        var applied = 0
        val confirmedHere = confirmedReconciles
        edit {
            for ((key, spec) in BACKUP_PREFS) {
                val value = values[key]?.takeIf { spec.accepts(it) }
                if (key == KEY_CONFIRMED) {
                    // Zusammenführen: Was hier schon bestätigt ist, bleibt; fehlende Monate kommen dazu.
                    val fromBackup = parseConfirmed((value as? PrefValue.TextSet)?.value.orEmpty())
                    putStringSet(key, formatConfirmed(fromBackup + confirmedHere))
                    if (fromBackup.isNotEmpty()) applied++
                    continue
                }
                when (value) {
                    null -> remove(key)
                    is PrefValue.Bool -> putBoolean(key, value.value)
                    is PrefValue.Number -> putInt(key, value.value)
                    is PrefValue.Text -> putString(key, value.value)
                    is PrefValue.TextSet -> putStringSet(key, value.value)
                }
                if (value != null) applied++
            }
        }
        return applied
    }

    // ---------- Zustand der automatischen Quellen ----------
    fun autoState(source: StampSource): AutoState = AutoState(
        presentSince = state.getLong("present_${source.name}", 0L),
        pendingLeaveAt = state.getLong("pending_${source.name}", 0L),
    )

    fun setAutoState(source: StampSource, s: AutoState) {
        state.edit()
            .putLong("present_${source.name}", s.presentSince)
            .putLong("pending_${source.name}", s.pendingLeaveAt)
            .commit()
        _stateChanges.value = _stateChanges.value + 1
    }

    /** Welche Werte eine Einstellung in einer Sicherung haben darf. */
    private fun interface PrefSpec {
        fun accepts(value: PrefValue): Boolean
    }

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        private val THEME_MODES = setOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

        private const val KEY_CONFIRMED = "confirmed_reconciles"

        private val bool = PrefSpec { it is PrefValue.Bool }
        private fun number(range: IntRange) = PrefSpec { it is PrefValue.Number && it.value in range }
        private fun text(valid: (String) -> Boolean) = PrefSpec { it is PrefValue.Text && it.value.length <= 2000 && valid(it.value) }
        private fun decimal(range: ClosedFloatingPointRange<Double>) = text { v -> v.toDoubleOrNull()?.let { it in range } == true }

        /**
         * Einstellungen, die in die Sicherung gehören – mit den Grenzen, die auch die Oberfläche einhält.
         * So kann eine beschädigte oder von Hand geänderte Datei die App nicht in einen ungültigen Zustand bringen.
         */
        private val BACKUP_PREFS: Map<String, PrefSpec> = linkedMapOf(
            "wifi_enabled" to bool,
            "wifi_ssids" to text { true },
            "geo_enabled" to bool,
            "geo_lat" to decimal(-90.0..90.0),
            "geo_lon" to decimal(-180.0..180.0),
            "geo_radius" to number(50..5000),
            "nfc_enabled" to bool,
            "leave_delay_min" to number(0..120),
            "priority" to text { true },
            "target_minutes" to number(0..24 * 60),
            "notify_on_stamp" to bool,
            "prefer_import" to bool,
            "count_absence" to bool,
            "reconcile_tolerance_min" to number(0..120),
            KEY_CONFIRMED to PrefSpec { it is PrefValue.TextSet },
            "student_enabled" to bool,
            "student_limit_weeks" to number(1..52),
            "student_limit_minutes" to number(60..7 * 24 * 60),
            "show_deleted" to bool,
            "theme_mode" to text { it in THEME_MODES },
        )

        private fun parseConfirmed(entries: Set<String>): Map<YearMonth, String> = entries.mapNotNull { e ->
            val parts = e.split('=', limit = 2)
            if (parts.size == 2) runCatching { YearMonth.parse(parts[0]) to parts[1] }.getOrNull() else null
        }.toMap()

        private fun formatConfirmed(map: Map<YearMonth, String>): Set<String> = map.map { (m, fp) -> "$m=$fp" }.toSet()

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
