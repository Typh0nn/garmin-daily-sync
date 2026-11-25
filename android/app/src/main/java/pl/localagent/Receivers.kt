package pl.localagent

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}

/** Przyciski w powiadomieniach: Wyłącz / Odpowiedz / Później. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Notifier.EXTRA_ID, -1)
        when (intent.action) {
            Notifier.ACTION_STOP -> AgentControl.stop(ctx)
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                // Rozmowy z agentem są zapamiętywane w całości.
                if (!text.isNullOrBlank()) AgentDb.get(ctx).log("chat", "user", text)
                Notifier.cancel(ctx, id)
            }
            Notifier.ACTION_LATER -> {
                AgentDb.get(ctx).log("notify", "later")
                Notifier.cancel(ctx, id)
            }
        }
    }
}

/** Kafelek w szybkich ustawieniach: wł./wył. agenta. */
class AgentTileService : TileService() {

    override fun onStartListening() = update()

    override fun onClick() {
        if (AgentControl.isEnabled(this)) {
            AgentControl.stop(this)
            update()
        } else {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = AgentControl.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Agent"
        tile.subtitle = if (on) "Włączony" else "Wyłączony"
        tile.updateTile()
    }

    companion object {
        fun refresh(ctx: Context) =
            requestListeningState(ctx, ComponentName(ctx, AgentTileService::class.java))
    }
}

/** Niewidoczna aktywność: agent startuje z pierwszego planu, potem okno znika. */
class StartActivity : Activity() {
    override fun onResume() {
        super.onResume()
        AgentControl.start(this)
        finish()
    }
}
