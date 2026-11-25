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
    private var importResult: String? = null
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
        // Agent włączony, ale usługa nie działa (np. po restarcie telefonu) albo bez mikrofonu:
        // otwarcie aplikacji to moment, w którym Android pozwala ją (ponownie) uruchomić.
        if (AgentControl.isEnabled(this)) AgentControl.start(this)
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
        permission("Mikrofon (rozpoznawanie otoczenia, bez nagrywania)", AmbientListener.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3)
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
        ambientSection()
        screenSection()

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
        button("Importuj teraz (z obecnego pliku)") {
            importResult = "Importuję…"
            render()
            Thread {
                val result = try {
                    "Zaimportowano: ${Gadgetbridge.importIfChanged(this, AgentDb.get(this), force = true)} próbek"
                } catch (e: Exception) {
                    "Błąd importu: $e"
                }
                runOnUiThread { importResult = result; render() }
            }.start()
        }
        importResult?.let { text(it) }

        // Diagnostyka: stan pliku eksportu i ostatnie zdarzenia synchronizacji.
        val info = Gadgetbridge.exportUri(this)?.let { Gadgetbridge.fileInfo(this, it) }
        text(
            if (info == null) "Plik eksportu: brak dostępu (wskaż go ponownie)"
            else "Plik eksportu: ${info.first?.let { "${it / 1024} KB" } ?: "?"}, zmieniony " +
                (info.second?.let { hm.format(Date(it)) } ?: "?")
        )
        val db = AgentDb.get(this)
        (db.latest("watch_sync", 6) + db.latest("error", 3)).sortedBy { it.ts }.forEach {
            text("· ${hm.format(Date(it.ts))} ${it.source}/${it.kind} ${it.data.take(120)}")
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

    private fun ambientSection() {
        header("Otoczenie (mikrofon)")
        val micOn = AgentControl.micEnabled(this)
        button(if (micOn) "Wyłącz słuchanie otoczenia" else "Włącz słuchanie otoczenia") {
            AgentControl.setMicEnabled(this, !micOn)
            if (AgentControl.isEnabled(this)) {
                AgentControl.stop(this)
                AgentControl.start(this)
            }
            render()
        }
        text("Dźwięk jest analizowany na bieżąco w pamięci; zapisywana jest tylko etykieta otoczenia.")

        val events = AgentDb.get(this).since("audio", startOfToday())
        val totals = LinkedHashMap<String, Long>()
        var current: String? = null
        var since = 0L
        fun close(at: Long) {
            current?.let { totals[it] = (totals[it] ?: 0L) + (at - since) }
            current = null
        }
        for (e in events) {
            when {
                e.kind == "state" -> { close(e.ts); current = e.data; since = e.ts }
                e.kind == "listening" && e.data == "off" -> close(e.ts)
            }
        }
        current?.let { text("Teraz: $it (od ${hm.format(Date(since))})") }
        close(System.currentTimeMillis())
        if (totals.isEmpty()) text("Brak danych z dzisiaj.")
        totals.entries.sortedByDescending { it.value }.forEach { (label, ms) -> text("${minutes(ms)}  $label") }
    }

    private fun screenSection() {
        header("Ekran (odczyt treści)")
        if (!ScreenReader.isEnabled(this)) {
            text(
                "Wyłączony. Ustawienia → Ułatwienia dostępu → Pobrane aplikacje → „Agent: odczyt ekranu” → włącz. " +
                    "Bank i menedżer haseł są pomijane."
            )
            button("Otwórz ułatwienia dostępu") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        } else {
            text("Włączony. Tekst zostaje tylko w pamięci; zapisywane jest samo przewijanie.")
        }

        // Przewijanie dziś (podstawa do wykrywania doomscrollingu).
        val scrolls = HashMap<String, Int>()
        AgentDb.get(this).since("screen", startOfToday()).filter { it.kind == "scroll" }.forEach {
            val (pkg, n) = it.data.split('|').let { p -> p[0] to (p.getOrNull(1)?.toIntOrNull() ?: 0) }
            scrolls[pkg] = (scrolls[pkg] ?: 0) + n
        }
        scrolls.entries.sortedByDescending { it.value }.take(5)
            .forEach { (pkg, n) -> text("Przewinięcia: $n  ${label(pkg)}") }

        // Czarna lista: dzisiejsze aplikacje z przełącznikiem „czytaj / nie czytaj”.
        text("Czarna lista (dotknij, aby zmienić):")
        UsageCollector.durations(AgentDb.get(this), startOfToday(), System.currentTimeMillis())
            .map { it.first }.filter { it != packageName }.take(10).forEach { pkg ->
                when {
                    Blocklist.isDefaultBlocked(pkg) -> text("⛔ ${label(pkg)} (zawsze pomijana)")
                    Blocklist.isBlocked(this, pkg) -> button("⛔ ${label(pkg)}: nie czytam") {
                        Blocklist.toggle(this, pkg); render()
                    }
                    else -> button("👁 ${label(pkg)}: czytam") { Blocklist.toggle(this, pkg); render() }
                }
            }
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
