package de.droh.stempeluhr.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lokale SQLite-Datenbank mit allen Stempel-Ereignissen. */
class StampDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "stempel.db", null, 1) {

    private val _changes = MutableStateFlow(0L)

    /** Zählt bei jeder Änderung hoch, damit die Oberfläche neu laden kann. */
    val changes: StateFlow<Long> = _changes

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                type TEXT NOT NULL,
                source TEXT NOT NULL,
                note TEXT,
                created_at INTEGER NOT NULL,
                original_ts INTEGER,
                deleted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(ts: Long, type: StampType, source: StampSource, note: String? = null): Long {
        val values = ContentValues().apply {
            put("ts", ts)
            put("type", type.name)
            put("source", source.name)
            put("note", note)
            put("created_at", System.currentTimeMillis())
            put("deleted", 0)
        }
        val id = writableDatabase.insert("events", null, values)
        notifyChanged()
        return id
    }

    /** Ändert Zeit, Art und Notiz. Die ursprüngliche Zeit bleibt in original_ts erhalten. */
    @Synchronized
    fun update(id: Long, ts: Long, type: StampType, note: String?) {
        val old = get(id) ?: return
        val values = ContentValues().apply {
            put("ts", ts)
            put("type", type.name)
            put("note", note)
            if (old.originalTs == null && old.ts != ts) put("original_ts", old.ts)
        }
        writableDatabase.update("events", values, "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    /** Löschen markiert nur – im Rohdaten-Export bleibt der Eintrag nachvollziehbar. */
    @Synchronized
    fun setDeleted(id: Long, deleted: Boolean) {
        val values = ContentValues().apply { put("deleted", if (deleted) 1 else 0) }
        writableDatabase.update("events", values, "id = ?", arrayOf(id.toString()))
        notifyChanged()
    }

    @Synchronized
    fun get(id: Long): StampEvent? =
        readableDatabase.query("events", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toEvent() else null
        }

    @Synchronized
    fun all(includeDeleted: Boolean = true): List<StampEvent> = query(
        if (includeDeleted) null else "deleted = 0",
        null,
    )

    /** Ereignisse mit fromMs <= ts < toMs. */
    @Synchronized
    fun between(fromMs: Long, toMs: Long, includeDeleted: Boolean = false): List<StampEvent> = query(
        "ts >= ? AND ts < ?" + if (includeDeleted) "" else " AND deleted = 0",
        arrayOf(fromMs.toString(), toMs.toString()),
    )

    /** Letztes nicht gelöschtes Ereignis einer Quelle. */
    @Synchronized
    fun lastOf(source: StampSource): StampEvent? =
        readableDatabase.query(
            "events", null, "source = ? AND deleted = 0", arrayOf(source.name),
            null, null, "ts DESC, id DESC", "1",
        ).use { c -> if (c.moveToFirst()) c.toEvent() else null }

    private fun query(selection: String?, args: Array<String>?): List<StampEvent> =
        readableDatabase.query("events", null, selection, args, null, null, "ts ASC, id ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toEvent()) }
        }

    private fun Cursor.toEvent(): StampEvent {
        val originalIdx = getColumnIndexOrThrow("original_ts")
        val noteIdx = getColumnIndexOrThrow("note")
        return StampEvent(
            id = getLong(getColumnIndexOrThrow("id")),
            ts = getLong(getColumnIndexOrThrow("ts")),
            type = StampType.valueOf(getString(getColumnIndexOrThrow("type"))),
            source = StampSource.valueOf(getString(getColumnIndexOrThrow("source"))),
            note = if (isNull(noteIdx)) null else getString(noteIdx),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            originalTs = if (isNull(originalIdx)) null else getLong(originalIdx),
            deleted = getInt(getColumnIndexOrThrow("deleted")) != 0,
        )
    }

    private fun notifyChanged() {
        _changes.value = _changes.value + 1
    }

    companion object {
        @Volatile
        private var instance: StampDb? = null

        fun get(context: Context): StampDb =
            instance ?: synchronized(this) {
                instance ?: StampDb(context).also { instance = it }
            }
    }
}
