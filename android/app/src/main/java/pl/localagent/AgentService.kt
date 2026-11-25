package pl.localagent

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager

/**
 * Serce agenta: usługa pierwszoplanowa, która co minutę (w trybie oszczędzania
 * energii co 5 minut) zbiera dane do lokalnej osi dnia.
 */
class AgentService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var db: AgentDb

    private val tick = object : Runnable {
        override fun run() {
            collect()
            handler.postDelayed(this, if (powerSave()) 5 * 60_000L else 60_000L)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    db.log("system", "power_save", powerSave().toString())
                    getSystemService(NotificationManager::class.java)
                        .notify(Notifier.ID_STATUS, Notifier.status(ctx, powerSave()))
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val quiet = Notifier.isQuiet(ctx)
                    db.log("system", "quiet", quiet.toString())
                    if (!quiet) Notifier.flushHeld(ctx)
                }
            }
        }
    }

    // Zdarzenia z Gadgetbridge (inna aplikacja, więc odbiornik musi być eksportowany).
    // Najgorsze, co obca aplikacja może tu wywołać, to dodatkowy eksport/import.
    private val watchReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Gadgetbridge.EV_SYNC_DONE -> {
                    db.log("watch_sync", "sync_done")
                    Gadgetbridge.requestExport(ctx)
                }
                Gadgetbridge.EV_EXPORT_OK -> {
                    db.log("watch_sync", "export_ok")
                    handler.post { importWatch() }
                }
                Gadgetbridge.EV_EXPORT_FAIL -> db.log("error", "watch_export")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AgentDb.get(this)
        try {
            startForeground(
                Notifier.ID_STATUS,
                Notifier.status(this, powerSave()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            // Np. restart przez system z tła — Android może na to nie pozwolić.
            db.log("error", "start_foreground", e.toString())
            Notifier.stopped(this)
            AgentControl.prefs(this).edit().putBoolean("enabled", false).apply()
            stopSelf()
            return
        }
        thread = HandlerThread("agent").apply { start() }
        handler = Handler(thread.looper)
        registerReceiver(
            systemReceiver,
            IntentFilter().apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            Context.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            watchReceiver,
            IntentFilter().apply {
                addAction(Gadgetbridge.EV_SYNC_DONE)
                addAction(Gadgetbridge.EV_EXPORT_OK)
                addAction(Gadgetbridge.EV_EXPORT_FAIL)
            },
            Context.RECEIVER_EXPORTED
        )
        db.log("agent", "started")
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (::thread.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            unregisterReceiver(systemReceiver)
            unregisterReceiver(watchReceiver)
            thread.quitSafely()
            db.log("agent", "stopped")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun powerSave(): Boolean = getSystemService(PowerManager::class.java).isPowerSaveMode

    private fun collect() {
        try {
            UsageCollector.collect(this, db)
        } catch (e: Exception) {
            db.log("error", "usage", e.toString())
        }
        // Zegarek: synchronizacja co 15 min (oszczędzanie energii: co godzinę).
        // Gdyby Gadgetbridge nie zgłosił końca synchronizacji, eksport i tak ruszy po 3 min.
        if (Gadgetbridge.requestSyncIfDue(this, if (powerSave()) 60 * 60_000L else 15 * 60_000L)) {
            handler.postDelayed({ Gadgetbridge.requestExport(this) }, 3 * 60_000L)
        }
        importWatch()
    }

    private fun importWatch() {
        try {
            Gadgetbridge.importIfChanged(this, db)
        } catch (e: Exception) {
            db.log("error", "watch_import", e.toString())
        }
    }
}
