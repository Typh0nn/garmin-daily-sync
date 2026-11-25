package pl.localagent

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Odczyt ekranu (usługa ułatwień dostępu).
 *
 * Prywatność:
 * - aplikacje z czarnej listy (bank, menedżer haseł itp.) są całkowicie pomijane,
 * - pola haseł są pomijane zawsze,
 * - tekst z ekranu NIE trafia do bazy: ostatni zrzut per aplikacja jest tylko w pamięci
 *   (dla modelu w Etapie 3); do bazy idą minutowe liczniki przewijania (doomscrolling).
 */
class ScreenReader : AccessibilityService() {

    private val scrolls = HashMap<String, Int>()
    private var minuteStart = System.currentTimeMillis()
    private var lastSnapshot = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || Blocklist.isBlocked(this, pkg)) return
        val now = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scrolls[pkg] = (scrolls[pkg] ?: 0) + 1
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> snapshot(pkg, now)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (now - lastSnapshot > 10_000) snapshot(pkg, now)
        }
        if (now - minuteStart >= 60_000) flush(now)
    }

    private fun snapshot(pkg: String, now: Long) {
        lastSnapshot = now
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collect(root, sb)
        ScreenContext.update(pkg, sb.toString(), now)
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int = 0) {
        if (sb.length >= ScreenContext.MAX_CHARS || depth > 40) return
        if (!node.isPassword) {
            node.text?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                ?: node.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, sb, depth + 1) }
    }

    private fun flush(now: Long) {
        if (scrolls.isNotEmpty()) {
            AgentDb.get(this).insertAll(scrolls.map { (pkg, n) -> Event(minuteStart, "screen", "scroll", "$pkg|$n") })
            scrolls.clear()
        }
        minuteStart = now
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        flush(System.currentTimeMillis())
        super.onDestroy()
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, ScreenReader::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Ostatni tekst z ekranu — tylko w pamięci procesu, nigdy na dysku. */
object ScreenContext {
    const val MAX_CHARS = 2_000

    @Volatile var pkg: String? = null
        private set
    @Volatile var text: String = ""
        private set
    @Volatile var at: Long = 0
        private set

    fun update(pkg: String, text: String, at: Long) {
        this.pkg = pkg
        this.text = text.take(MAX_CHARS)
        this.at = at
    }
}

/** Aplikacje, których agent nie czyta: domyślne (banki, hasła, uwierzytelnianie) + wybrane przez użytkownika. */
object Blocklist {
    private const val KEY = "screen_blocklist"
    private val DEFAULT_HINTS = listOf(
        "bank", "pko", "mbank", "pl.ing.", "santander", "pekao", "millennium", "alior", "citibank", "revolut",
        "paypal", "blik", "1password", "bitwarden", "lastpass", "keepass", "dashlane", "proton.pass",
        "authenticator", "authy", "google.android.apps.walletnfcrel"
    )

    fun isDefaultBlocked(pkg: String) = DEFAULT_HINTS.any { pkg.contains(it, ignoreCase = true) }

    fun userBlocked(ctx: Context): Set<String> =
        AgentControl.prefs(ctx).getStringSet(KEY, emptySet()).orEmpty()

    fun isBlocked(ctx: Context, pkg: String) = isDefaultBlocked(pkg) || pkg in userBlocked(ctx)

    fun toggle(ctx: Context, pkg: String) {
        val set = userBlocked(ctx).toMutableSet()
        if (!set.remove(pkg)) set.add(pkg)
        AgentControl.prefs(ctx).edit().putStringSet(KEY, set).apply()
    }
}
