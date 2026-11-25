package pl.localagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekran główny: wł./wył., uprawnienia, oś dzisiejszego dnia, ostatnie wiadomości czatu. */
class MainActivity : Activity() {

    private lateinit var content: LinearLayout
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        // Edge-to-edge (Android 15+): odsuń treść od pasków systemowych.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        content.removeAllViews()
        val on = AgentControl.isEnabled(this)

        header("Agent")
        text(if (on) "Status: włączony" else "Status: wyłączony")
        button(if (on) "Wyłącz agenta" else "Włącz agenta") {
            if (on) AgentControl.stop(this) else AgentControl.start(this)
            render()
        }
        text("Szybki dostęp: dodaj kafelek „Agent” w szybkich ustawieniach.")

        header("Uprawnienia")
        permission("Powiadomienia", granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        permission("Kalendarz", CalendarReader.hasAccess(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
        }
        permission("Historia aplikacji", UsageCollector.hasAccess(this)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        if (!UsageCollector.hasAccess(this)) {
            text(
                "Jeśli Android pokaże „Aplikacja nie otrzymała dostępu”: " +
                    "Informacje o aplikacji → ⋮ → „Zezwól na ograniczone ustawienia”, potem wróć tutaj."
            )
            button("Informacje o aplikacji") {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
        permission(
            "Praca w tle bez ograniczeń baterii",
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }

        watchSection()

        header("Test")
        button("Wyślij testowy popup") {
            Notifier.nudge(this, "Test: tak będą wyglądać podpowiedzi agenta. Odpowiedz, żeby sprawdzić czat.")
        }

        header("Dziś: kalendarz")
        val dayStart = startOfToday()
        val now = System.currentTimeMillis()
        val meetings = CalendarReader.between(this, dayStart, dayStart + 86_400_000L)
        if (meetings.isEmpty()) text("—")
        meetings.forEach { text("${hm.format(Date(it.begin))}–${hm.format(Date(it.end))}  ${it.title}") }

        header("Dziś: aplikacje")
        val usage = UsageCollector.durations(AgentDb.get(this), dayStart, now).take(12)
        if (usage.isEmpty()) text("Brak danych (włącz agenta i nadaj dostęp do historii aplikacji).")
        usage.forEach { (pkg, ms) -> text("${minutes(ms)}  ${label(pkg)}") }

        header("Czat (ostatnie)")
        val chat = AgentDb.get(this).latest("chat", 10)
        if (chat.isEmpty()) text("—")
        chat.forEach { text("${hm.format(Date(it.ts))} ${if (it.kind == "user") "Ty" else "Agent"}: ${it.data}") }
    }

    @Deprecated("Activity API frameworku; wystarczy do jednego wyboru pliku.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PICK_GB_EXPORT && resultCode == RESULT_OK && uri != null) {
            Gadgetbridge.setExportUri(this, uri)
            render()
        }
    }

    private fun watchSection() {
        header("Zegarek (Gadgetbridge)")
        if (!Gadgetbridge.isInstalled(this)) {
            text("Gadgetbridge nie jest zainstalowany.")
            return
        }
        if (Gadgetbridge.exportUri(this) == null) {
            text(
                "W Gadgetbridge: Ustawienia → Opcje programisty → Intent API (włącz synchronizację i eksport bazy), " +
                    "oraz Zarządzanie danymi → Auto export (ustaw plik eksportu). Potem wskaż ten sam plik tutaj."
            )
            button("1. Zleć eksport w Gadgetbridge (tworzy plik)") {
                Gadgetbridge.requestExport(this)
                text("Wysłano. Za kilka sekund plik powinien być w wybranej lokalizacji eksportu.")
            }
            button("2. Wskaż plik eksportu Gadgetbridge") {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    PICK_GB_EXPORT
                )
            }
            return
        }
        val prefs = AgentControl.prefs(this)
        val lastImport = prefs.getLong(Gadgetbridge.KEY_LAST_IMPORT, 0)
        text(
            "Ostatni import: " + if (lastImport > 0) {
                "${hm.format(Date(lastImport))} (${prefs.getInt(Gadgetbridge.KEY_LAST_COUNT, 0)} nowych próbek)"
            } else "jeszcze nie było"
        )
        prefs.getString(Gadgetbridge.KEY_MISSING, "").takeUnless { it.isNullOrEmpty() }
            ?.let { text("Nie znaleziono w eksporcie: $it") }
        button("Synchronizuj teraz") {
            Gadgetbridge.requestSync(this)
            text("Wysłano. Dane pojawią się po synchronizacji i eksporcie (do kilku minut).")
        }

        val today = AgentDb.get(this).since("watch", startOfToday())
        if (today.isEmpty()) {
            text("Brak danych z zegarka z dzisiaj.")
            return
        }
        fun last(kind: String) = today.lastOrNull { it.kind == kind }
        last("stress")?.let { text("Stres: ${it.data} (${hm.format(Date(it.ts))})") }
        today.filter { it.kind == "stress" }.mapNotNull { it.data.toDoubleOrNull() }
            .takeIf { it.isNotEmpty() }?.let { text("Stres dziś średnio: ${it.average().toInt()}") }
        last("body_battery")?.let { text("Body Battery: ${it.data} (${hm.format(Date(it.ts))})") }
        last("hr")?.let { text("Tętno: ${it.data} (${hm.format(Date(it.ts))})") }
        today.filter { it.kind == "steps" }.sumOf { it.data.toLongOrNull() ?: 0L }
            .takeIf { it > 0 }?.let { text("Kroki dziś: $it") }
        last("sleep_score")?.let { text("Wynik snu: ${it.data}") }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    private fun header(t: String) = content.addView(TextView(this).apply {
        text = t
        textSize = 20f
        setPadding(0, dp(16), 0, dp(4))
    })

    private fun text(t: String) = content.addView(TextView(this).apply {
        text = t
        textSize = 15f
    })

    private fun button(t: String, onClick: () -> Unit) = content.addView(Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    })

    private fun permission(name: String, ok: Boolean, request: () -> Unit) {
        if (ok) text("✓ $name") else button("✗ $name — nadaj", request)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val PICK_GB_EXPORT = 10
    }
}
