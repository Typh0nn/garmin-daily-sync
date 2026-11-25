package pl.localagent

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** Historia aplikacji: przełączenia aplikacji, ekran wł./wył., odblokowania. */
object UsageCollector {

    private const val CURSOR = "usage_cursor"

    fun hasAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun collect(ctx: Context, db: AgentDb) {
        if (!hasAccess(ctx)) return
        val prefs = AgentControl.prefs(ctx)
        val now = System.currentTimeMillis()
        val from = prefs.getLong(CURSOR, startOfToday())

        val events = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val e = UsageEvents.Event()
        val rows = ArrayList<Event>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val kind = when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> "resumed"
                UsageEvents.Event.ACTIVITY_PAUSED -> "paused"
                UsageEvents.Event.SCREEN_INTERACTIVE -> "screen_on"
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "screen_off"
                UsageEvents.Event.KEYGUARD_HIDDEN -> "unlocked"
                else -> null
            } ?: continue
            rows += Event(e.timeStamp, "usage", kind, e.packageName.orEmpty())
        }
        db.insertAll(rows)
        prefs.edit().putLong(CURSOR, rows.maxOfOrNull { it.ts + 1 } ?: now).apply()
    }

    /** Czas na pierwszym planie per aplikacja, od [from] do [to], malejąco. */
    fun durations(db: AgentDb, from: Long, to: Long): List<Pair<String, Long>> {
        val totals = HashMap<String, Long>()
        var current: String? = null
        var start = 0L
        fun close(at: Long) {
            current?.let { totals[it] = (totals[it] ?: 0L) + (at - start) }
            current = null
        }
        for (ev in db.since("usage", from)) {
            when (ev.kind) {
                "resumed" -> { close(ev.ts); current = ev.data; start = ev.ts }
                "paused" -> if (current == ev.data) close(ev.ts)
                "screen_off" -> close(ev.ts)
            }
        }
        close(to)
        return totals.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
