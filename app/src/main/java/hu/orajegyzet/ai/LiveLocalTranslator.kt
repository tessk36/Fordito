package hu.orajegyzet.ai

import android.content.Context
import android.util.Log
import hu.orajegyzet.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Beszédszakaszoló (Fordító 0.2): a 100 ms-os PCM-darabokból szünetnél (vagy max. hossznál)
 * lezárt beszédszakaszokat ad. Egyszerű energia-alapú hangfelismerés, a zajszinthez igazodva.
 * Tiszta Kotlin, Android nélkül (unit teszt).
 */
class SpeechSegmenter(
    /** Ennyi csendes darab (×100 ms) után zárja le a szakaszt. */
    private val silenceFrames: Int = 7,
    /** Leghosszabb szakasz (×100 ms). */
    private val maxFrames: Int = 100,
    /** Legalább ennyi beszédes darab kell, különben eldobja (köhintés, kattanás). */
    private val minSpeechFrames: Int = 4,
    private val onSegment: (ByteArray) -> Unit
) {
    private val buf = ByteArrayOutputStream()
    private var frames = 0
    private var speechFrames = 0
    private var silentRun = 0
    private var inSpeech = false
    private var noiseFloor = 200.0
    /** Az utolsó csendes darab, hogy a szakasz eleje ne vágódjon le. */
    private var preRoll: ByteArray? = null

    /** Az utolsó darab hangereje (RMS), a kijelzőhöz. */
    var lastLevel = 0.0
        private set

    @Synchronized fun push(chunk: ByteArray) {
        val rms = rms(chunk)
        lastLevel = rms
        val threshold = maxOf(MIN_THRESHOLD, noiseFloor * 3.0)
        val speech = rms > threshold
        if (!inSpeech) {
            if (speech) {
                inSpeech = true
                preRoll?.let { buf.write(it) }
                buf.write(chunk); frames = 1; speechFrames = 1; silentRun = 0
            } else {
                // a zajszint lassan követi a csendet
                noiseFloor = noiseFloor * 0.95 + rms * 0.05
                preRoll = chunk
            }
            return
        }
        buf.write(chunk); frames++
        if (speech) { speechFrames++; silentRun = 0 } else silentRun++
        if (silentRun >= silenceFrames || frames >= maxFrames) close()
    }

    /** A még nyitott szakasz lezárása (pl. leállításkor). */
    @Synchronized fun flush() { if (inSpeech) close() }

    private fun close() {
        val data = buf.toByteArray()
        val ok = speechFrames >= minSpeechFrames
        buf.reset(); frames = 0; speechFrames = 0; silentRun = 0; inSpeech = false; preRoll = null
        if (ok) onSegment(data)
    }

    companion object {
        const val MIN_THRESHOLD = 350.0

        fun rms(pcm: ByteArray): Double {
            val n = pcm.size / 2
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) {
                val s = ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toDouble()
                sum += s * s
            }
            return Math.sqrt(sum / n)
        }
    }
}

/**
 * Élő tolmács, helyben (Fordító 0.2): a beszédszakaszokat sorban a Whisper írja át
 * (automatikus nyelvfelismeréssel), a Gemma 4 pedig mondatonként magyarra fordítja.
 * A Whisper és a Gemma a futás alatt betöltve marad. Semmi nem hagyja el a telefont.
 */
class LiveLocalTranslator(
    private val ctx: Context,
    private val settings: AppSettings,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onSegmentStarted()
        fun onSegment(source: String, sourceLang: String, translation: String)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<ByteArray>(capacity = 8)
    private var gemma: GemmaSession? = null
    private val segmenter = SpeechSegmenter { seg ->
        if (queue.trySend(seg).isFailure) listener.onStatus("Lemaradás: egy szakasz kimaradt (túl gyors beszéd).")
    }
    private val whisper = OfflinePipeline(
        ctx, useGemma = false,
        whisperThreads = settings.whisperThreads, keepWarm = true, warmMinutes = 10,
        whisperPrompt = Translator.glossaryPrompt(settings.whisperGlossary),
        whisperAccurate = false
    )

    val level: Double get() = segmenter.lastLevel

    fun start() {
        scope.launch {
            try {
                val gemmaFile = GemmaModel.file(ctx)
                    ?: run { listener.onError("Nincs Gemma-modell a helyi fordításhoz (Beállítások → Modellek)."); return@launch }
                if (!java.io.File(ctx.filesDir, "models/whisper.bin").isFile) {
                    listener.onError("Nincs Whisper-modell (Beállítások → Modellek)."); return@launch
                }
                listener.onStatus("Gemma 4 betöltése… (10–30 mp)")
                gemma = GemmaSession.open(ctx, gemmaFile)
                listener.onStatus("Kész – beszélhetsz (helyben, ${gemma?.backendName})")
                for (seg in queue) process(seg)
                listener.onStatus("Leállítva.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Fordito", "live local", e)
                listener.onError(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { gemma?.close() }
                gemma = null
            }
        }
    }

    /** Mikrofondarab (100 ms, 16 kHz mono PCM). */
    fun push(chunk: ByteArray) = segmenter.push(chunk)

    private suspend fun process(seg: ByteArray) {
        listener.onSegmentStarted()
        val src = try { whisper.transcribePcm16k(seg) } catch (e: Exception) {
            listener.onError("Whisper: ${e.message}"); return
        }
        if (src.isBlank()) return
        val g = gemma ?: return
        val raw = try {
            g.generate(Translator.textPrompt(TrStyle.NATURAL, src, askLanguage = true), 60_000L)
        } catch (e: Exception) {
            listener.onError("Gemma: ${e.message}"); return
        }
        val (lang, text) = Translator.parseOutput(raw)
        listener.onSegment(src, lang, text.ifBlank { src })
    }

    /** Mikrofon leállt: a nyitott szakasz és a sorban lévők még lefordulnak, utána zár a Gemma. */
    fun stop() {
        segmenter.flush()
        queue.close()
    }

    /** Végleges leállítás (a képernyő bezárásakor): a folyamatban lévő munka is megszakad. */
    fun release() {
        queue.close()
        scope.cancel()
        OfflinePipeline.releaseWarmWhisper()
    }
}
