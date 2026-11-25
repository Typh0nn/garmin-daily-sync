package pl.localagent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId

/** Włączanie / wyłączanie agenta jednym ruchem (kafelek, powiadomienie, ekran aplikacji). */
object AgentControl {

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    /** Słuchanie otoczenia (mikrofon) — osobny przełącznik w aplikacji. */
    fun micEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("mic_enabled", true)

    fun setMicEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("mic_enabled", on).apply()

    /** Wywoływać tylko z widocznej aktywności: usługa pierwszoplanowa musi startować z pierwszego planu. */
    fun start(ctx: Context) {
        prefs(ctx).edit().putBoolean("enabled", true).apply()
        ctx.startForegroundService(Intent(ctx, AgentService::class.java))
        AgentTileService.refresh(ctx)
    }

    fun stop(ctx: Context) {
        prefs(ctx).edit().putBoolean("enabled", false).apply()
        ctx.stopService(Intent(ctx, AgentService::class.java))
        AgentTileService.refresh(ctx)
    }
}

fun startOfToday(): Long =
    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
