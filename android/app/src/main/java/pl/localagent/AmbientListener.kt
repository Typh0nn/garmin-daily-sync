package pl.localagent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions

/**
 * Rozpoznawanie otoczenia z mikrofonu (YAMNet, na urządzeniu).
 *
 * Prywatność: dźwięk istnieje tylko w buforze w pamięci (~1 s). Nic nie jest nagrywane
 * ani transkrybowane. Do bazy trafia wyłącznie zmiana stanu, np. „rozmowa” od 14:02.
 *
 * Reakcja: okna ~1 s, stan zmienia się, gdy ta sama etykieta wygra 3 z 4 ostatnich okien (~3–4 s).
 */
class AmbientListener(private val ctx: Context, private val handler: Handler, private val db: AgentDb) {

    private var classifier: AudioClassifier? = null
    private var record: AudioRecord? = null
    private var audio: AudioData? = null
    private val recent = ArrayDeque<String>()
    var state: String? = null
        private set
    var stateSince: Long = 0
        private set

    private val loop = object : Runnable {
        override fun run() {
            try {
                classifyOnce()
            } catch (e: Exception) {
                db.log("error", "ambient", e.toString())
            }
            handler.postDelayed(this, WINDOW_MS)
        }
    }

    fun start(): Boolean {
        if (classifier != null) return true
        if (!hasPermission(ctx)) return false
        val c = AudioClassifier.createFromOptions(
            ctx,
            AudioClassifier.AudioClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
                .setRunningMode(RunningMode.AUDIO_CLIPS)
                .setMaxResults(5)
                .setScoreThreshold(0.2f)
                .build()
        )
        val rec = c.createAudioRecord(1, SAMPLE_RATE, SAMPLES)
        rec.startRecording()
        classifier = c
        record = rec
        audio = AudioData.create(
            AudioData.AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(SAMPLE_RATE.toFloat()).build(),
            SAMPLES
        )
        db.log("audio", "listening", "on")
        handler.postDelayed(loop, WINDOW_MS)
        return true
    }

    fun stop() {
        handler.removeCallbacks(loop)
        record?.run { stop(); release() }
        classifier?.close()
        record = null
        classifier = null
        audio = null
        recent.clear()
        if (state != null) db.log("audio", "listening", "off")
        state = null
    }

    private fun classifyOnce() {
        val rec = record ?: return
        val data = audio ?: return
        val label = when {
            inCall() -> "telefon"
            rec.activeRecordingConfiguration?.isClientSilenced == true -> "mikrofon zajęty"
            else -> {
                data.load(rec)
                val categories = classifier?.classify(data)
                    ?.classificationResults()?.firstOrNull()
                    ?.classifications()?.firstOrNull()
                    ?.categories().orEmpty()
                toLabel(categories.map { it.categoryName() })
            }
        }
        recent.addLast(label)
        if (recent.size > 4) recent.removeFirst()
        val winner = recent.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (winner != null && winner.value >= 3 && winner.key != state) {
            state = winner.key
            stateSince = System.currentTimeMillis()
            db.log("audio", "state", winner.key)
        }
    }

    private fun inCall(): Boolean {
        val mode = ctx.getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLES = 15_600 // okno YAMNet: 0,975 s
        private const val WINDOW_MS = 1_000L

        fun hasPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Klasy AudioSet (YAMNet) → etykiety agenta. Kolejność ma znaczenie. */
        fun toLabel(names: List<String>): String {
            fun has(vararg keys: String) = names.any { n -> keys.any { n.equals(it, ignoreCase = true) } }
            fun contains(key: String) = names.any { it.contains(key, ignoreCase = true) }
            return when {
                names.isEmpty() -> "inne"
                has("Television", "Radio") -> "TV/radio"
                has("Speech", "Conversation", "Narration, monologue", "Male speech, man speaking",
                    "Female speech, woman speaking", "Child speech, kid speaking", "Chatter") -> "rozmowa"
                has("Typing", "Computer keyboard", "Typewriter") -> "pisanie na klawiaturze"
                has("Dishes, pots, and pans", "Cutlery, silverware", "Chopping (food)") -> "kuchnia"
                has("Snoring") -> "chrapanie"
                contains("music") || has("Singing", "Musical instrument") -> "muzyka"
                has("Vehicle", "Car", "Traffic noise, roadway noise", "Bus", "Train", "Engine") -> "pojazd/ulica"
                has("Silence") -> "cisza"
                else -> names.first()
            }
        }
    }
}
