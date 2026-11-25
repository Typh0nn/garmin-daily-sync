package pl.localagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import org.json.JSONArray

/**
 * Popupy agenta. Gadgetbridge przekazuje je na zegarek (wibracja).
 * W trybie „Nie przeszkadzać” / „Pora snu” wiadomości są wstrzymywane i wysyłane później.
 */
object Notifier {

    const val ID_STATUS = 1
    private const val ID_STOPPED = 2
    private const val CH_STATUS = "status"
    private const val CH_NUDGE = "nudge"

    const val ACTION_STOP = "pl.localagent.STOP"
    const val ACTION_REPLY = "pl.localagent.REPLY"
    const val ACTION_LATER = "pl.localagent.LATER"
    const val KEY_REPLY = "reply"
    const val EXTRA_ID = "id"

    private const val HELD = "held_messages"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Status agenta", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NUDGE, "Podpowiedzi agenta", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun status(ctx: Context, detail: String): Notification =
        Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_agent)
            .setContentTitle("Agent działa")
            .setContentText(detail)
            .setOngoing(true)
            .setContentIntent(openApp(ctx))
            .addAction(action(ctx, "Wyłącz", broadcast(ctx, ACTION_STOP, ID_STATUS)))
            .build()

    fun stopped(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).notify(
            ID_STOPPED,
            Notification.Builder(ctx, CH_STATUS)
                .setSmallIcon(R.drawable.ic_agent)
                .setContentTitle("Agent zatrzymany")
                .setContentText("Dotknij, żeby otworzyć i włączyć ponownie")
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Tryb „Nie przeszkadzać” (w tym „Pora snu”) jest aktywny. */
    fun isQuiet(ctx: Context): Boolean {
        val f = ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL &&
            f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Wiadomość agenta: zawsze trafia do czatu, a popup pojawia się od razu albo po ciszy. */
    fun nudge(ctx: Context, text: String) {
        val db = AgentDb.get(ctx)
        db.log("chat", "agent", text)
        if (isQuiet(ctx)) {
            val prefs = AgentControl.prefs(ctx)
            val held = JSONArray(prefs.getString(HELD, "[]")).put(text)
            prefs.edit().putString(HELD, held.toString()).apply()
            db.log("notify", "held", text)
        } else {
            post(ctx, text)
        }
    }

    fun flushHeld(ctx: Context) {
        val prefs = AgentControl.prefs(ctx)
        val held = JSONArray(prefs.getString(HELD, "[]"))
        prefs.edit().remove(HELD).apply()
        for (i in 0 until held.length()) post(ctx, held.getString(i))
    }

    private fun post(ctx: Context, text: String) {
        val id = 100 + (System.currentTimeMillis() % 100_000).toInt()
        val reply = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_agent), "Odpowiedz",
            broadcast(ctx, ACTION_REPLY, id, mutable = true)
        ).addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Odpowiedź").build()).build()

        ctx.getSystemService(NotificationManager::class.java).notify(
            id,
            Notification.Builder(ctx, CH_NUDGE)
                .setSmallIcon(R.drawable.ic_agent)
                .setContentTitle("Agent")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .addAction(reply)
                .addAction(action(ctx, "Czat", openApp(ctx)))
                .addAction(action(ctx, "Później", broadcast(ctx, ACTION_LATER, id)))
                .build()
        )
        AgentDb.get(ctx).log("notify", "shown", text)
    }

    fun cancel(ctx: Context, id: Int) =
        ctx.getSystemService(NotificationManager::class.java).cancel(id)

    private fun action(ctx: Context, title: String, pi: PendingIntent) =
        Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_agent), title, pi).build()

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun broadcast(ctx: Context, action: String, id: Int, mutable: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id * 10 + action.length,
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE) or
                PendingIntent.FLAG_UPDATE_CURRENT
        )
}
