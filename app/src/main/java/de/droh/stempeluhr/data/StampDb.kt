package de.droh.stempeluhr.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.droh.stempeluhr.core.ImportedDay
import de.droh.stempeluhr.core.RawRow
import de.droh.stempeluhr.core.StampEvent
import de.droh.stempeluhr.core.StampSource
import de.droh.stempeluhr.core.StampType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** Lokale SQLite-Datenbank mit allen Stempel-Ereignissen. */
class StampDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "stempel.db", null, 2) {

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
        createImportTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createImportTable(db)
    }

    private fun createImportTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS imported_days (
                date TEXT PRIMARY KEY,
                minutes INTEGER NOT NULL,
                note TEXT,
                source TEXT,
                imported_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    // ---------- Importierte Tage (nur Dauer bekannt) ----------

    /** Speichert importierte Tage; vorhandene Tage mit gleichem Datum werden ersetzt. */
    @Synchronized
    fun upsertImported(days: List<ImportedDay>, sourceName: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (d in days) {
                val values = ContentValues().apply {
                    put("date", d.date.toString())
                    put("minutes", d.minutes)
                    put("note", d.note)
                    put("source", sourceName)
                    put("imported_at", now)
                }
                db.insertWithOnConflict("imported_days", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    @Synchronized
    fun importedDays(): List<ImportedDay> =
        readableDatabase.query("imported_days", null, null, null, null, null, "date ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    val noteIdx = c.getColumnIndexOrThrow("note")
                    add(
                        ImportedDay(
                            date = LocalDate.parse(c.getString(c.getColumnIndexOrThrow("date"))),
                            minutes = c.getLong(c.getColumnIndexOrThrow("minutes")),
                            note = if (c.isNull(noteIdx)) null else c.getString(noteIdx),
                        ),
                    )
                }
            }
        }

    @Synchronized
    fun deleteImported(date: LocalDate) {
        writableDatabase.delete("imported_days", "date = ?", arrayOf(date.toString()))
        notifyChanged()
    }

    @Synchronized
    fun deleteAllImported() {
        writableDatabase.delete("imported_days", null, null)
        notifyChanged()
    }

    /** Stellt Ereignisse aus dem eigenen Rohdaten-Export wieder her. Bereits vorhandene werden übersprungen. */
    @Synchronized
    fun restoreRaw(rows: List<RawRow>): Int {
        val existing = all(includeDeleted = true).map { Triple(it.ts, it.type, it.source) }.toHashSet()
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                if (Triple(r.ts, r.type, r.source) in existing) continue
                val values = ContentValues().apply {
                    put("ts", r.ts)
                    put("type", r.type.name)
                    put("source", r.source.name)
                    put("note", r.note)
                    put("created_at", System.currentTimeMillis())
                    if (r.originalTs != null) put("original_ts", r.originalTs)
                    put("deleted", if (r.deleted) 1 else 0)
                }
                db.insert("events", null, values)
                added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        return added
    }

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
