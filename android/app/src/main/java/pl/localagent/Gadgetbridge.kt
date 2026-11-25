package pl.localagent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Dane z fenix 7 przez Gadgetbridge (Bluetooth, bez chmury).
 *
 * Cykl: ACTIVITY_SYNC → (ACTIVITY_SYNC_FINISH) → TRIGGER_EXPORT → (DATABASE_EXPORT_SUCCESS)
 * → kopia wyeksportowanej bazy → import nowych próbek do osi dnia (source = "watch").
 * Plik eksportu wskazuje użytkownik (ten sam, który ustawił w Gadgetbridge jako „Auto export”).
 */
object Gadgetbridge {

    const val PKG = "nodomain.freeyourgadget.gadgetbridge"
    private const val CMD_SYNC = "$PKG.command.ACTIVITY_SYNC"
    private const val CMD_EXPORT = "$PKG.command.TRIGGER_EXPORT"
    const val EV_SYNC_DONE = "$PKG.action.ACTIVITY_SYNC_FINISH"
    const val EV_EXPORT_OK = "$PKG.action.DATABASE_EXPORT_SUCCESS"
    const val EV_EXPORT_FAIL = "$PKG.action.DATABASE_EXPORT_FAIL"

    private const val KEY_URI = "gb_export_uri"
    private const val KEY_LAST_SYNC = "gb_last_sync_request"
    private const val KEY_IMPORTED_MOD = "gb_imported_modified"
    const val KEY_LAST_IMPORT = "gb_last_import"
    const val KEY_LAST_COUNT = "gb_last_count"
    const val KEY_MISSING = "gb_missing_tables"

    private class Table(
        val name: String,
        val column: String,
        val kind: String,
        val seconds: Boolean = false,
        val valid: (Double) -> Boolean = { it > 0 },
    )

    // Struktura bazy: docs/agent-plan.md → „Notatki techniczne”.
    private val TABLES = listOf(
        Table("GARMIN_STRESS_SAMPLE", "STRESS", "stress", valid = { it in 0.0..100.0 }),
        Table("GARMIN_BODY_ENERGY_SAMPLE", "ENERGY", "body_battery", valid = { it in 0.0..100.0 }),
        Table("GARMIN_ACTIVITY_SAMPLE", "HEART_RATE", "hr", seconds = true, valid = { it in 25.0..250.0 }),
        Table("GARMIN_ACTIVITY_SAMPLE", "STEPS", "steps", seconds = true),
        Table("GARMIN_SLEEP_STAGE_SAMPLE", "STAGE", "sleep_stage", valid = { it >= 0 }),
        Table("GARMIN_SLEEP_STATS_SAMPLE", "SLEEP_SCORE", "sleep_score"),
        Table("GARMIN_HRV_SUMMARY_SAMPLE", "LAST_NIGHT_AVERAGE", "hrv_night"),
        Table("GARMIN_HEART_RATE_RESTING_SAMPLE", "HEART_RATE", "rhr"),
        Table("GARMIN_SPO2_SAMPLE", "SPO2", "spo2", valid = { it in 50.0..100.0 }),
        Table("GARMIN_RESPIRATORY_RATE_SAMPLE", "RESPIRATORY_RATE", "resp"),
    )

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun exportUri(ctx: Context): Uri? =
        AgentControl.prefs(ctx).getString(KEY_URI, null)?.let(Uri::parse)

    fun setExportUri(ctx: Context, uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AgentControl.prefs(ctx).edit().putString(KEY_URI, uri.toString()).remove(KEY_IMPORTED_MOD).apply()
    }

    fun isConfigured(ctx: Context) = isInstalled(ctx) && exportUri(ctx) != null

    fun requestSyncIfDue(ctx: Context, intervalMs: Long): Boolean {
        val last = AgentControl.prefs(ctx).getLong(KEY_LAST_SYNC, 0)
        if (!isConfigured(ctx) || System.currentTimeMillis() - last < intervalMs) return false
        requestSync(ctx)
        return true
    }

    fun requestSync(ctx: Context) {
        AgentControl.prefs(ctx).edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
        send(ctx, CMD_SYNC)
        AgentDb.get(ctx).log("watch_sync", "requested")
    }

    fun requestExport(ctx: Context) = send(ctx, CMD_EXPORT)

    private fun send(ctx: Context, action: String) = ctx.sendBroadcast(Intent(action).setPackage(PKG))

    /** Importuje nowe próbki, jeśli plik eksportu zmienił się od ostatniego importu. */
    fun importIfChanged(ctx: Context, db: AgentDb): Int {
        val uri = exportUri(ctx) ?: return 0
        val prefs = AgentControl.prefs(ctx)
        val modified = lastModified(ctx, uri)
        if (modified != null && modified <= prefs.getLong(KEY_IMPORTED_MOD, 0)) return 0
        // Gdy dostawca plików nie podaje daty zmiany: importuj najwyżej co 15 min.
        if (modified == null && System.currentTimeMillis() - prefs.getLong(KEY_LAST_IMPORT, 0) < 15 * 60_000L) return 0
        val n = import(ctx, db, uri)
        prefs.edit()
            .putLong(KEY_IMPORTED_MOD, modified ?: System.currentTimeMillis())
            .putLong(KEY_LAST_IMPORT, System.currentTimeMillis())
            .apply()
        return n
    }

    private fun lastModified(ctx: Context, uri: Uri): Long? = try {
        ctx.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun import(ctx: Context, db: AgentDb, uri: Uri): Int {
        val prefs = AgentControl.prefs(ctx)
        val copy = File(ctx.cacheDir, "gadgetbridge-export.db")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { input.copyTo(it) }
            } ?: return 0
            var total = 0
            val missing = ArrayList<String>()
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { gb ->
                for (t in TABLES) {
                    val key = "gb_cursor_${t.kind}"
                    val cursorMs = prefs.getLong(key, 0)
                    val since = if (t.seconds) cursorMs / 1000 else cursorMs
                    val rows = ArrayList<Event>()
                    var maxTs = cursorMs
                    try {
                        gb.rawQuery(
                            "SELECT TIMESTAMP, ${t.column} FROM ${t.name} " +
                                "WHERE TIMESTAMP > ? AND ${t.column} IS NOT NULL ORDER BY TIMESTAMP",
                            arrayOf(since.toString())
                        ).use { c ->
                            while (c.moveToNext()) {
                                val ts = if (t.seconds) c.getLong(0) * 1000 else c.getLong(0)
                                val v = c.getDouble(1)
                                if (t.valid(v)) rows += Event(ts, "watch", t.kind, format(v))
                                if (ts > maxTs) maxTs = ts
                            }
                        }
                    } catch (e: SQLiteException) {
                        missing += "${t.name}.${t.column}" // nie ma w tej wersji Gadgetbridge
                        continue
                    }
                    db.insertAll(rows)
                    total += rows.size
                    if (maxTs > cursorMs) prefs.edit().putLong(key, maxTs).apply()
                }
            }
            db.log("watch_sync", "imported", total.toString())
            prefs.edit()
                .putInt(KEY_LAST_COUNT, total)
                .putString(KEY_MISSING, missing.joinToString(", "))
                .apply()
            return total
        } finally {
            copy.delete() // kopia z danymi zdrowotnymi nie zostaje na dysku
        }
    }

    private fun format(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
}
