package de.droh.stempeluhr.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.droh.stempeluhr.core.AutoLogic
import de.droh.stempeluhr.core.AutoResult
import de.droh.stempeluhr.core.PreciseLogic
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import de.droh.stempeluhr.core.TimeFormat
import de.droh.stempeluhr.data.Settings
import de.droh.stempeluhr.data.StampDb
import java.time.LocalDate
import java.time.ZoneId

/**
 * Zentrale Stelle, über die alle Quellen Ereignisse melden.
 * Verbindet die reine Logik (core) mit Datenbank, Einstellungen und Benachrichtigungen.
 */
object StampEngine {
    private val handler = Handler(Looper.getMainLooper())

    /** NFC-Doppel-Lesungen innerhalb dieser Zeit werden ignoriert. */
    private const val NFC_REPEAT_MS = 30_000L

    // ---------- Automatische Quellen (WLAN, Geofence) ----------

    @Synchronized
    fun arrive(context: Context, source: StampSource, ts: Long = System.currentTimeMillis()) {
        val settings = Settings.get(context)
        val result = AutoLogic.onArrive(settings.autoState(source), ts, settings.leaveDelayMs, ZoneId.systemDefault())
        apply(context, source, result)
    }

    @Synchronized
    fun leave(context: Context, source: StampSource, ts: Long = System.currentTimeMillis()) {
        val settings = Settings.get(context)
        val result = AutoLogic.onLeave(settings.autoState(source), ts, settings.leaveDelayMs)
        apply(context, source, result)
        scheduleTick(context, settings.leaveDelayMs)
    }

    /** Macht abgelaufene "weg"-Meldungen zu Gehen-Ereignissen. Wird regelmäßig und beim Start aufgerufen. */
    @Synchronized
    fun tick(context: Context, now: Long = System.currentTimeMillis()) {
        val settings = Settings.get(context)
        for (source in StampSource.entries.filter { it.automatic }) {
            apply(context, source, AutoLogic.onTick(settings.autoState(source), now, settings.leaveDelayMs))
        }
    }

    /**
     * Setzt eine Quelle auf "nicht anwesend", ohne ein Gehen zu speichern
     * (z. B. wenn der Hintergrunddienst beendet war und das Gehen verpasst wurde).
     */
    @Synchronized
    fun markLost(context: Context, source: StampSource) {
        val settings = Settings.get(context)
        val s = settings.autoState(source)
        if (s.pendingLeaveAt != 0L) {
            // Ein vorgemerktes Gehen hat einen echten Zeitpunkt – das wird gespeichert.
            apply(context, source, AutoLogic.onTick(s, Long.MAX_VALUE, 0))
            return
        }
        if (s.presentSince == 0L) return
        settings.setAutoState(source, s.copy(presentSince = 0))
        Notifications.warning(
            context,
            source.ordinal,
            "${source.label}: Gehen nicht erfasst",
            "Die Verbindung zur Arbeit ist weg, der genaue Zeitpunkt ist aber unbekannt " +
                "(Handy oder Dienst war aus). Bitte das Gehen bei Bedarf manuell nachtragen.",
        )
    }

    private fun apply(context: Context, source: StampSource, result: AutoResult) {
        val settings = Settings.get(context)
        if (result.state != settings.autoState(source)) settings.setAutoState(source, result.state)
        val db = StampDb.get(context)
        for (r in result.records) {
            db.insert(r.ts, r.type, source, r.note)
            notifyStamp(context, r.type, source, r.ts)
        }
    }

    private fun scheduleTick(context: Context, delayMs: Long) {
        val app = context.applicationContext
        handler.postDelayed({ tick(app) }, delayMs + 1_000L)
    }

    // ---------- Genaue Quellen (NFC, Manuell) ----------

    enum class Mode { TOGGLE, IN, OUT }

    /**
     * Speichert ein NFC- oder manuelles Ereignis.
     * @return die gespeicherte Art oder null, wenn es als Doppel-Lesung ignoriert wurde.
     */
    @Synchronized
    fun precise(
        context: Context,
        source: StampSource,
        mode: Mode,
        ts: Long = System.currentTimeMillis(),
    ): StampType? {
        val db = StampDb.get(context)
        if (source == StampSource.NFC) {
            val last = db.lastOf(StampSource.NFC)
            if (last != null && ts - last.ts in 0 until NFC_REPEAT_MS) return null
        }
        val type = when (mode) {
            Mode.IN -> StampType.IN
            Mode.OUT -> StampType.OUT
            Mode.TOGGLE -> PreciseLogic.nextToggleType(todayEvents(context))
        }
        db.insert(ts, type, source)
        notifyStamp(context, type, source, ts)
        return type
    }

    fun nextToggleType(context: Context): StampType = PreciseLogic.nextToggleType(todayEvents(context))

    fun todayEvents(context: Context) = StampDb.get(context).let { db ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        db.between(from, to)
    }

    private fun notifyStamp(context: Context, type: StampType, source: StampSource, ts: Long) {
        if (!Settings.get(context).notifyOnStamp) return
        val time = TimeFormat.time(ts, ZoneId.systemDefault())
        Notifications.stamp(context, "${type.label} $time", "Erfasst über ${source.label}.")
    }
}
