package pl.localagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Event(val ts: Long, val source: String, val kind: String, val data: String)

/**
 * Jedna lokalna tabela zdarzeń (oś dnia). Leży w prywatnym katalogu aplikacji,
 * szyfrowanym przez Androida (FBE). Nic stąd nie wychodzi poza telefon.
 *
 * source: usage | calendar | chat | notify | system | agent | error
 */
class AgentDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "agent.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE events(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL, " +
                "data TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX events_source_ts ON events(source, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun log(source: String, kind: String, data: String = "", ts: Long = System.currentTimeMillis()) {
        insertAll(listOf(Event(ts, source, kind, data)))
    }

    fun insertAll(rows: List<Event>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                db.insert("events", null, ContentValues().apply {
                    put("ts", r.ts)
                    put("source", r.source)
                    put("kind", r.kind)
                    put("data", r.data)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun since(source: String, fromTs: Long): List<Event> =
        query("source = ? AND ts >= ?", arrayOf(source, fromTs.toString()), "ts ASC", null)

    fun latest(source: String, limit: Int): List<Event> =
        query("source = ?", arrayOf(source), "ts DESC", limit.toString()).reversed()

    private fun query(where: String, args: Array<String>, order: String, limit: String?): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.query(
            "events", arrayOf("ts", "source", "kind", "data"),
            where, args, null, null, order, limit
        ).use { c ->
            while (c.moveToNext()) out += Event(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))
        }
        return out
    }

    companion object {
        @Volatile private var instance: AgentDb? = null

        fun get(ctx: Context): AgentDb =
            instance ?: synchronized(this) {
                instance ?: AgentDb(ctx.applicationContext).also { instance = it }
            }
    }
}
