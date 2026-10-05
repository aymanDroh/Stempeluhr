package de.droh.stempeluhr.data

import android.content.Context
import android.content.SharedPreferences
import de.droh.stempeluhr.core.AutoState
import de.droh.stempeluhr.core.StampSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    var showDeleted: Boolean
        get() = prefs.getBoolean("show_deleted", false)
        set(v) = edit { putBoolean("show_deleted", v) }

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

    companion object {
        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(context).also { instance = it }
            }
    }
}
