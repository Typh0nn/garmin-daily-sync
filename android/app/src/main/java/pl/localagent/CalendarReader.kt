package pl.localagent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Instances

/** Spotkania z kalendarza w telefonie (lokalny dostawca, bez chmury). */
object CalendarReader {

    data class Item(val title: String, val begin: Long, val end: Long)

    fun hasAccess(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun between(ctx: Context, from: Long, to: Long): List<Item> {
        if (!hasAccess(ctx)) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = ArrayList<Item>()
        ctx.contentResolver.query(
            uri,
            arrayOf(Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY),
            null, null, "${Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) == 1) continue
                out += Item(c.getString(0) ?: "(bez tytułu)", c.getLong(1), c.getLong(2))
            }
        }
        return out
    }
}
