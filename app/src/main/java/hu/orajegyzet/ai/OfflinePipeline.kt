package hu.orajegyzet.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.redravencomputing.whispercore.Whisper
import com.redravencomputing.whispercore.WhisperCpuConfig
import com.redravencomputing.whispercore.WhisperDecodeConfig
import com.redravencomputing.whispercore.WhisperDelegate
import com.redravencomputing.whispercore.WhisperOperationError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Offline mód — a hang soha nem hagyja el a készüléket.
 *  1. M4A → 16 kHz mono PCM dekódolás (MediaCodec)
 *  2. Beszédfelismerés: Whisper (large-v3-turbo) a whisper.cpp wrapperrel
 *     (filesDir/models/whisper.bin). A Vosk 1.0.4-től kikerült.
 *  3. Összefoglaló: Gemma 4 a LiteRT-LM motorral
 *     (filesDir/models/gemma.litertlm, lásd GemmaModel)
 */
class OfflinePipeline(
    private val ctx: Context,
    private val useGemma: Boolean = true,
    private val whisperThreads: Int = 6,
    private val keepWarm: Boolean = true,
    private val warmMinutes: Int = 5,
    private val summaryBlockChars: Int = 2000,
    private val chunkSec: Int = 27,
    private val overlapSec: Int = 3,
    /** Valódi feldolgozási lépések kiírása (pl. „Leirat: 3/12 darab (Whisper)"). */
    private val onProgress: (suspend (String) -> Unit)? = null,
    /** 1.1.4: a Whisper „szótára” (WhisperPrompt.build), üres = nincs. */
    private val whisperPrompt: String = "",
    /** 1.1.4: pontosabb átírás (beam search, 5 változat). */
    private val whisperAccurate: Boolean = false
) : NotePipeline {

    override suspend fun process(audioPath: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult {
        val transcript = runTranscription(audioPath, null)
        return finishSummary(transcript, subject, dateLabel, isLesson)
    }

    /** Csak a leirat — a szakaszos megjelenítéshez (előbb a leirat, az összefoglaló utólag). */
    suspend fun transcribeOnly(audioPath: String, onPartial: (suspend (String) -> Unit)? = null): String =
        runTranscription(audioPath, onPartial)

    /**
     * Fordító 0.2 (élő tolmács, helyben): egy rövid, 16 kHz mono 16 bites PCM szakasz átirata.
     * A modell melegen marad a szakaszok között (keepWarm).
     */
    suspend fun transcribePcm16k(pcm: ByteArray): String {
        val whisperModel = File(ctx.filesDir, "models/whisper.bin")
        if (!whisperModel.isFile) error("Nincs Whisper-modell (Beállítások → Modellek).")
        if (pcm.size < 16_000) return "" // fél mp alatti darab: nincs mit átírni
        val wav = File(ctx.cacheDir, "live_${System.nanoTime()}.wav")
        return try {
            writeWav(wav, pcm, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
            val text = transcribeWhisper(wav, whisperModel.absolutePath).trim()
            if (isWhisperHallucination(text)) "" else text
        } finally {
            wav.delete()
        }
    }

    /** Összefoglaló a kész leiratból. */
    suspend fun finishSummary(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult =
        buildOfflineResult(transcript)

    /**
     * Kivonatos alap (sosem hallucinál) + opcionális Gemma-összefoglaló védőhálóval.
     * A Gemma hibáját NEM nyeli el: az ok a NoteResult.warning mezőbe kerül.
     */
    private suspend fun buildOfflineResult(transcript: String): NoteResult {
        val clean = transcript.trim()
        if (clean.isBlank()) return NoteResult(transcript, "(Üres leirat.)", "", emptyList(),
            engine = SummaryEngine.EXTRACTIVE, warning = "A leirat üres, nincs mit összefoglalni.")
        val notes = Extractive.bulletNotes(clean, 6)
        val keywords = Extractive.keywords(clean, 8)
        val gemmaFile = GemmaModel.file(ctx)

        val tooShort = clean.length < 400 || countSentences(clean) < 4
        val skipReason: String? = when {
            !useGemma -> "A Gemma ki van kapcsolva a Beállításokban, kivonat készült."
            gemmaFile == null -> "Nincs telepítve Gemma-modell (Beállítások → Modellek), kivonat készült."
            else -> null // rövid szövegnél is kivonat készül, de az nem hiba
        }
        if (gemmaFile == null || skipReason != null || tooShort) {
            Log.i("FuleloAI", "summary engine=EXTRACTIVE reason=${skipReason ?: "short transcript"}")
            return NoteResult(transcript, Extractive.summarize(clean, 8), notes, keywords,
                engine = SummaryEngine.EXTRACTIVE, warning = skipReason)
        }

        val style = gemmaSummarize(
            instruction = "Foglald össze a megbeszélés lényegét: témák, döntések, felelősök, határidők.",
            clean = clean,
            gemmaFile = gemmaFile
        )
        return NoteResult(transcript, style.text, notes, keywords, engine = style.engine, warning = style.warning)
    }

    /** Stílusgomb / blokkos összefoglaló eredménye. */
    data class StyleResult(val text: String, val engine: String, val warning: String? = null)

    /** Stílusgombok offline összefoglalója (hosszú leiratnál blokkonként, map-reduce). */
    suspend fun gemmaStyle(instruction: String, transcript: String): StyleResult {
        val clean = transcript.trim()
        if (clean.isBlank()) return StyleResult("(Üres leirat.)", SummaryEngine.EXTRACTIVE, "A leirat üres.")
        if (clean.length < 400 || countSentences(clean) < 4) {
            return StyleResult(Extractive.summarize(clean, 6), SummaryEngine.EXTRACTIVE,
                "A leirat túl rövid az AI-összefoglalóhoz, kivonat készült.")
        }
        val gemmaFile = GemmaModel.file(ctx)
            ?: return StyleResult(Extractive.summarize(clean, 6), SummaryEngine.EXTRACTIVE,
                "Nincs telepítve Gemma-modell (Beállítások → Modellek), kivonat készült.")
        val result = gemmaSummarize(instruction, clean, gemmaFile, styled = true)
        if (result.engine == SummaryEngine.GEMMA && echoesPrompt(result.text, instruction)) {
            return StyleResult(Extractive.summarize(clean, 6), SummaryEngine.EXTRACTIVE,
                "A Gemma a kérést ismételte vissza, kivonat készült. Próbáld újra.")
        }
        return result
    }

    /**
     * Gemma-összefoglaló a 4000 tokenes keret figyelembevételével:
     *  - ha a teljes szöveg belefér: egyetlen hívás;
     *  - különben blokkonként részösszefoglaló, majd ezekből a végső (map-reduce).
     * A modell a teljes folyamat alatt EGYSZER töltődik be.
     */
    private suspend fun gemmaSummarize(
        instruction: String, clean: String, gemmaFile: File, styled: Boolean = false
    ): StyleResult {
        val fallback = { why: String ->
            Log.w("FuleloAI", "summary engine=EXTRACTIVE reason=$why")
            StyleResult(Extractive.summarize(clean, 8), SummaryEngine.EXTRACTIVE, why)
        }
        releaseWarmWhisper()
        System.gc()
        val deadline = System.currentTimeMillis() + TOTAL_SUMMARY_MS
        val session = try {
            onProgress?.invoke("Összefoglaló: Gemma betöltése…")
            GemmaSession.open(ctx, gemmaFile)
        } catch (e: Exception) {
            return fallback("A Gemma nem futott: ${e.message}. Kivonat látható.")
        }
        try {
            val single = finalPrompt(instruction, clean, styled)
            var text: String? = null
            if (fitsInOneCall(single, styled)) {
                onProgress?.invoke("Összefoglaló: Gemma")
                text = session.generate(single, BLOCK_TIMEOUT_MS)
            } else {
                val blockChars = summaryBlockChars.coerceIn(1500, 6000)
                var parts = splitBlocks(clean, blockChars)
                var round = 1
                while (text == null) {
                    val partials = mutableListOf<String>()
                    for ((i, block) in parts.withIndex()) {
                        if (System.currentTimeMillis() > deadline)
                            throw GemmaTimeoutException("Az összefoglaló 10 perc alatt nem készült el.")
                        onProgress?.invoke("Összefoglaló: Gemma (${i + 1}/${parts.size} blokk" +
                            (if (round > 1) ", $round. kör" else "") + ")")
                        val raw = session.generate(blockPrompt(block, styled), BLOCK_TIMEOUT_MS)
                        val cleaned = cleanLoops(raw)
                        if (cleaned.isNotBlank()) partials += cleaned
                    }
                    if (partials.isEmpty()) throw IllegalStateException("a Gemma üres választ adott")
                    val joined = partials.joinToString("\n")
                    val prompt = finalPrompt(instruction, joined, styled)
                    if (fitsInOneCall(prompt, styled) || round >= 3) {
                        onProgress?.invoke("Összefoglaló: Gemma (végső összegzés)")
                        text = session.generate(prompt, BLOCK_TIMEOUT_MS)
                    } else {
                        parts = splitBlocks(joined, blockChars)
                        round++
                    }
                }
            }
            val finalText = text ?: ""
            val processed = cleanLoops(finalText)
            if (processed.length < (if (styled) 20 else 40))
                return fallback("A Gemma válasza üres vagy túl rövid volt. Kivonat látható.")
            // 1.1.2: a kért szerkezet szavai (pl. „Határozatok", „Felelős") nem számítanak kitaláltnak;
            // a Gemma szövege csak nagyon alacsony egyezésnél vész el, különben figyelmeztetéssel marad.
            val check = checkCoherence(processed, clean, allowedText = instruction)
            Log.i("FuleloAI", "summary coherence=${check.percent}% names=${check.unknownNames} numbers=${check.unknownNumbers}")
            if (check.verdict == CoherenceVerdict.REJECT) {
                return fallback("A Gemma válasza alig kapcsolódott a leirathoz (egyezés: ${check.percent}%), ezért elvetettem. Kivonat látható.")
            }
            Log.i("FuleloAI", "summary engine=GEMMA load=${session.loadMillis}ms")
            return StyleResult(processed, SummaryEngine.GEMMA, check.warning())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return fallback("A Gemma nem futott: ${e.message}. Kivonat látható.")
        } finally {
            session.close()
        }
    }

    /** A stílusos (jegyzőkönyv, részletes) válasz hosszabb, ezért ott nagyobb helyet hagyunk a kimenetnek. */
    private fun fitsInOneCall(prompt: String, styled: Boolean = false): Boolean =
        GemmaSession.estimateTokens(prompt) + (if (styled) STYLED_RESPONSE_TOKENS else RESPONSE_TOKENS) <=
            GemmaSession.MAX_TOKENS

    private fun finalPrompt(instruction: String, text: String, styled: Boolean = false): String {
        // Stílusos kérésnél a stílus saját szerkezete és szabályai érvényesek (SummaryStyles).
        if (styled) return "$instruction\n\nSzöveg:\n$text"
        val n = countSentences(text).coerceIn(2, 8)
        return """
            $instruction
            Foglald össze magyarul, hivatalos, tárgyilagos stílusban, legfeljebb $n mondatban.
            KIZÁRÓLAG az alábbi szövegben szereplő tényeket használd. Ne találj ki nevet, dátumot vagy számot.
            Ha kevés az információ, írj rövidebbet. Ne ismételj mondatot. Ne írj semmit a szövegen kívül.

            Szöveg:
            $text
        """.trimIndent()
    }

    private fun blockPrompt(block: String, styled: Boolean = false): String =
        if (styled) "${SummaryStyles.BLOCK_FACTS_PROMPT}\n\nRészlet:\n$block" else """
        Foglald össze magyarul legfeljebb 4 mondatban az alábbi leirat-részlet tényeit
        (témák, döntések, felelősök, határidők). Csak a szövegben szereplő tényeket használd.

        Részlet:
        $block
    """.trimIndent()

    private suspend fun runTranscription(audioPath: String, onPartial: (suspend (String) -> Unit)?): String {
        val whisperModel = File(ctx.filesDir, "models/whisper.bin")
        return when {
            whisperModel.isFile && whisperModel.length() > 0 -> {
                val bigWav = File(ctx.cacheDir, "tr_${System.currentTimeMillis()}.wav")
                try {
                    try { m4aToWav(audioPath, bigWav) }
                    catch (e: Exception) { throw RuntimeException("[Hang→WAV] ${e.message}", e) }
                    if (!bigWav.exists() || bigWav.length() <= 44L)
                        error("[Hang→WAV] üres WAV keletkezett")
                    try { transcribeChunked(bigWav, whisperModel.absolutePath, onPartial) }
                    catch (e: Exception) {
                        if (e is RuntimeException && e.message?.startsWith("[Whisper]") == true) throw e
                        throw RuntimeException("[Whisper] ${e.message}", e)
                    }
                } finally {
                    bigWav.delete()
                }
            }
            else -> error("Nincs offline beszédfelismerő modell: importáld a Whisper modellt (Beállítások → Modellek).")
        }
    }

    /** A nagy WAV-ot darabokban írja át csendvágással és átfedés-szűréssel. */
    private suspend fun transcribeChunked(bigWav: File, modelPath: String, onPartial: (suspend (String) -> Unit)?): String {
        val sr = 16_000
        val actualChunkSec = chunkSec.coerceIn(10, 28)
        val actualOverlapSec = overlapSec.coerceIn(0, 5).coerceAtMost(30 - actualChunkSec)
        val totalLenSec = bigWav.length() / (sr * 2)

        // 25 másodperc alatti felvételnél egyetlen hívással írjuk át (nincs darabolás)
        if (totalLenSec < 25L) {
            onProgress?.invoke("Leirat: 1/1 darab (Whisper)")
            val text = transcribeWhisper(bigWav, modelPath)
            if (isWhisperHallucination(text)) return ""
            onPartial?.invoke(text)
            return text
        }

        val chunkBytes = actualChunkSec * sr * 2
        val overlapBytes = actualOverlapSec * sr * 2
        var currentText = ""
        val totalChunks = ((totalLenSec + actualChunkSec - 1) / actualChunkSec).coerceAtLeast(1)
        var chunkNo = 0

        FileInputStream(bigWav).use { fis ->
            var skipped = 0L
            while (skipped < 44L) { val s = fis.skip(44L - skipped); if (s <= 0) break; skipped += s }
            var tail = ByteArray(0)
            val buf = ByteArray(chunkBytes)
            while (true) {
                val read = readFully(fis, buf)
                if (read <= 0) break
                val pcm = if (tail.isEmpty()) buf.copyOf(read) else tail + buf.copyOf(read)

                val chunkWav = File(ctx.cacheDir, "chunk_${System.nanoTime()}.wav")
                chunkNo++
                onProgress?.invoke("Leirat: ${chunkNo.coerceAtMost(totalChunks.toInt())}/$totalChunks darab (Whisper)")
                try {
                    writeWav(chunkWav, pcm, sampleRate = sr, channels = 1, bitsPerSample = 16)
                    val text = try { transcribeWhisper(chunkWav, modelPath) }
                        catch (e: Exception) { throw RuntimeException("[Whisper] ${e.message}", e) }

                    if (text.isNotBlank() && !isWhisperHallucination(text)) {
                        currentText = if (currentText.isEmpty()) text else mergeOverlap(currentText, text)
                        onPartial?.invoke(currentText)
                    }
                } finally {
                    chunkWav.delete()
                }
                tail = if (overlapBytes in 1 until read) buf.copyOfRange(read - overlapBytes, read) else ByteArray(0)
                if (read < buf.size) break
            }
        }
        return currentText.trim()
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    // ---------- 1a) STT (Whisper, large-v3-turbo) ----------

    private suspend fun transcribeWhisper(wav: File, modelPath: String): String {
        WhisperCpuConfig.override = whisperThreads.coerceIn(2, 8)
        return warmMutex.withLock {
            // a dekódolási beállítások a zár alatt kerülnek be, hogy párhuzamos futás ne keverje őket
            WhisperDecodeConfig.initialPrompt = whisperPrompt.ifBlank { null }
            WhisperDecodeConfig.beamSize = if (whisperAccurate) 5 else 1
            val whisper = acquireWhisper(modelPath)
            try {
                withTimeout(6 * 60_000L) {
                    suspendCancellableCoroutine { cont ->
                        whisper.delegate = object : WhisperDelegate {
                            override fun didTranscribe(text: String) { if (cont.isActive) cont.resume(text.trim()) }
                            override fun failedToTranscribe(error: WhisperOperationError) { if (cont.isActive) cont.resumeWithException(error) }
                            override fun recordingFailed(error: WhisperOperationError) {}
                            override fun permissionRequestNeeded() {}
                            override fun didStartRecording() {}
                            override fun didStopRecording() {}
                        }
                        whisper.transcribeAudioFile(wav)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw RuntimeException("átírás időtúllépés (a modell betöltése vagy a feldolgozás elakadt)", e)
            } finally {
                if (keepWarm) scheduleRelease(warmMinutes) else releaseWarmNow()
            }
        }
    }

    private suspend fun acquireWhisper(modelPath: String): Whisper {
        if (keepWarm && warmWhisper != null && warmModelPath == modelPath) {
            cancelRelease()
            return warmWhisper!!
        }
        runCatching { warmWhisper?.cleanup() }
        warmWhisper = null; warmModelPath = null
        val w = Whisper(ctx.applicationContext)
        withTimeout(6 * 60_000L) { w.initializeModel(modelPath) }
        warmWhisper = w; warmModelPath = modelPath
        return w
    }

    private fun cancelRelease() { releaseJob?.cancel(); releaseJob = null }

    private fun scheduleRelease(mins: Int) {
        releaseJob?.cancel()
        releaseJob = warmScope.launch {
            delay(mins.coerceAtLeast(1) * 60_000L)
            warmMutex.withLock {
                runCatching { warmWhisper?.cleanup() }
                warmWhisper = null; warmModelPath = null
            }
        }
    }

    private fun releaseWarmNow() {
        runCatching { warmWhisper?.cleanup() }
        warmWhisper = null; warmModelPath = null
    }

    override suspend fun resummarize(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult =
        buildOfflineResult(transcript)

    private fun m4aToWav(srcPath: String, wavOut: File) {
        RandomAccessFile(wavOut, "rw").use { f ->
            f.setLength(0)
            writeWavHeader(f, dataLen = 0, sampleRate = 16_000, channels = 1, bitsPerSample = 16)
            var dataLen = 0
            decodePcm16k(srcPath) { bytes, len -> f.write(bytes, 0, len); dataLen += len }
            f.seek(4); f.writeIntLE(36 + dataLen)
            f.seek(40); f.writeIntLE(dataLen)
        }
    }

    private fun writeWavHeader(f: RandomAccessFile, dataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        f.writeBytes("RIFF"); f.writeIntLE(36 + dataLen); f.writeBytes("WAVE")
        f.writeBytes("fmt "); f.writeIntLE(16); f.writeShortLE(1)
        f.writeShortLE(channels); f.writeIntLE(sampleRate); f.writeIntLE(byteRate)
        f.writeShortLE(blockAlign); f.writeShortLE(bitsPerSample)
        f.writeBytes("data"); f.writeIntLE(dataLen)
    }

    private fun writeWav(out: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            writeWavHeader(f, pcm.size, sampleRate, channels, bitsPerSample)
            f.write(pcm)
        }
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }
    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }

    private fun decodePcm16k(path: String, onChunk: (ByteArray, Int) -> Unit) {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i); trackFormat = f; break
            }
        }
        val format = trackFormat ?: error("Nincs hangsáv a felvételben")
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        // Fordító 0.1: importált hangfájl (pl. 44,1 kHz sztereó) → 16 kHz mono a Whispernek
        fun fmtInt(f: MediaFormat, key: String, def: Int) =
            if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrDefault(def) else def
        var resampler = Pcm16kConverter(
            fmtInt(format, MediaFormat.KEY_SAMPLE_RATE, 16_000),
            fmtInt(format, MediaFormat.KEY_CHANNEL_COUNT, 1))
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var produced = 0L
        val deadline = System.currentTimeMillis() + 120_000L
        try {
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline)
                    error("Hang dekódolása túl sokáig tartott (időtúllépés)")
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        resampler = Pcm16kConverter(
                            fmtInt(of, MediaFormat.KEY_SAMPLE_RATE, resampler.srcRate),
                            fmtInt(of, MediaFormat.KEY_CHANNEL_COUNT, resampler.channels))
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { }
                    else -> if (outIdx >= 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            out.order(ByteOrder.LITTLE_ENDIAN)
                            val raw = ByteArray(info.size)
                            out.get(raw)
                            val bytes = resampler.convert(raw, raw.size)
                            if (bytes.isNotEmpty()) {
                                produced += bytes.size
                                onChunk(bytes, bytes.size)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        if (produced == 0L) error("A felvételből nem sikerült hangot kinyerni (üres dekódolás)")
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 24_000
        private const val RESPONSE_TOKENS = 500
        private const val STYLED_RESPONSE_TOKENS = 1200
        private const val BLOCK_TIMEOUT_MS = 120_000L
        private const val TOTAL_SUMMARY_MS = 10 * 60_000L

        /** A meleg Whisper-példány elengedése (pl. a Gemma betöltése előtt, memória miatt). */
        fun releaseWarmWhisper() {
            releaseJob?.cancel(); releaseJob = null
            runCatching { warmWhisper?.cleanup() }
            warmWhisper = null; warmModelPath = null
        }

        /**
         * A szöveg mondathatáron vágott, legfeljebb [maxChars] hosszú blokkokra bontása.
         * Egy túl hosszú mondatot keményen vág. Tiszta függvény (unit teszt).
         */
        fun splitBlocks(text: String, maxChars: Int): List<String> {
            val limit = maxChars.coerceAtLeast(200)
            val sentences = text.trim().split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
            val blocks = mutableListOf<String>()
            val cur = StringBuilder()
            for (sent in sentences) {
                var piece = sent
                while (piece.length > limit) {
                    if (cur.isNotEmpty()) { blocks += cur.toString(); cur.clear() }
                    blocks += piece.take(limit)
                    piece = piece.drop(limit)
                }
                if (cur.isNotEmpty() && cur.length + 1 + piece.length > limit) {
                    blocks += cur.toString(); cur.clear()
                }
                if (piece.isNotEmpty()) { if (cur.isNotEmpty()) cur.append(' '); cur.append(piece) }
            }
            if (cur.isNotEmpty()) blocks += cur.toString()
            return blocks
        }
        private val warmMutex = Mutex()
        private val warmScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default)
        private var warmWhisper: Whisper? = null
        private var warmModelPath: String? = null
        private var releaseJob: Job? = null

        fun countSentences(text: String): Int =
            text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.length >= 15 }.size

        fun hasLoop(text: String): Boolean {
            val sents = text.split(Regex("(?<=[.!?])\\s+"))
                .map { it.lowercase().replace(Regex("[^\\p{L}\\p{Nd} ]"), "").trim() }
                .filter { it.length > 15 }
            return sents.size != sents.toSet().size
        }

        fun removeLoopSentences(text: String): String {
            val rawSents = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
            val seen = mutableSetOf<String>()
            val result = mutableListOf<String>()
            for (s in rawSents) {
                val norm = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd} ]"), "").trim()
                if (norm.length > 15) {
                    if (seen.contains(norm)) break
                    seen.add(norm)
                }
                result.add(s)
            }
            return result.joinToString(" ")
        }

        /**
         * Ismétlés-szűrő, amely MEGŐRZI a sortöréseket (markdown: címsorok, felsorolás, táblázat).
         * Egysoros szövegnél a régi mondatalapú szűrő fut. Többsorosnál az ismétlődő sor kimarad,
         * a 3. ismétlés után pedig (valódi hurok) a szöveg ott lezárul.
         */
        fun cleanLoops(text: String): String {
            if (!text.contains('\n')) return if (hasLoop(text)) removeLoopSentences(text) else text
            val seen = mutableSetOf<String>()
            val out = mutableListOf<String>()
            var repeats = 0
            for (line in text.lines()) {
                val norm = line.lowercase().replace(Regex("[^\\p{L}\\p{Nd} ]"), "").trim()
                if (norm.length > 15 && !seen.add(norm)) {
                    if (++repeats >= 3) break
                    continue
                }
                out += line
            }
            return out.joinToString("\n").trimEnd()
        }

        const val REJECT_RATIO = Coherence.REJECT_RATIO
        const val WARN_RATIO = Coherence.WARN_RATIO

        private val WORD = Regex("[\\p{L}\\p{Nd}]+")
        private val LIST_MARKER = Regex("^\\s*(\\|\\s*\\d+\\s*\\||\\d+[.)])")

        private fun stems(text: String): Set<String> = WORD.findAll(text)
            .map { it.value.lowercase() }
            .filter { it.length >= 4 && it !in Extractive.STOP }
            .map { it.take(5) }
            .toSet()

        fun checkCoherence(summaryText: String, sourceText: String, allowedText: String = ""): Coherence {
            val sourceStems = stems("$sourceText $allowedText")
            val summaryStems = WORD.findAll(summaryText)
                .map { it.value.lowercase() }
                .filter { it.length >= 4 && it !in Extractive.STOP }
                .map { it.take(5) }
                .toList()
            val ratio = if (sourceStems.isEmpty() || summaryStems.isEmpty()) 1.0
                else summaryStems.count { it in sourceStems }.toDouble() / summaryStems.size

            // Nevek: nagybetűs szó, amely nem mondat/sor/cella elején áll, és a forrásban (kisbetűsen) sincs.
            val names = linkedSetOf<String>()
            for (line in summaryText.lines()) {
                val sentences = line.split(Regex("(?<=[.!?:])\\s+|\\|"))
                for (sent in sentences) {
                    val words = WORD.findAll(sent.replace("*", " ")).map { it.value }.toList()
                    words.drop(1).filter { w ->
                        w.length >= 3 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() } &&
                            w.lowercase().take(5) !in sourceStems
                    }.forEach { names += it }
                }
            }

            // Számok: a lista-/táblázatsorszámokat kihagyjuk.
            val sourceNumbers = Regex("\\d+").findAll("$sourceText $allowedText").map { it.value }.toSet()
            val numbers = linkedSetOf<String>()
            for (line in summaryText.lines()) {
                Regex("\\d+").findAll(line.replaceFirst(LIST_MARKER, " ")).map { it.value }
                    .filter { it !in sourceNumbers }.forEach { numbers += it }
            }
            return Coherence(ratio, names.take(5), numbers.take(5))
        }

        fun verifySourceCoherence(summaryText: String, sourceText: String, allowedText: String = ""): Boolean {
            val wordRegex = Regex("[\\p{L}\\p{Nd}]+")
            val sourceStems = wordRegex.findAll("$sourceText $allowedText")
                .map { it.value.lowercase() }
                .filter { it.length >= 4 && it !in Extractive.STOP }
                .map { it.take(5) }
                .toSet()
            if (sourceStems.isEmpty()) return true

            val summaryStems = wordRegex.findAll(summaryText)
                .map { it.value.lowercase() }
                .filter { it.length >= 4 && it !in Extractive.STOP }
                .map { it.take(5) }
                .toList()
            if (summaryStems.isEmpty()) return true

            val matches = summaryStems.count { it in sourceStems }
            val ratio = matches.toDouble() / summaryStems.size.toDouble()
            return ratio >= 0.60
        }

        fun echoesPrompt(result: String, prompt: String): Boolean {
            val start = prompt.take(40).trim()
            return start.isNotBlank() && result.contains(start, ignoreCase = true)
        }

        fun mergeOverlap(prev: String, next: String, maxWords: Int = 20): String {
            val a = prev.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val b = next.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (a.isEmpty()) return next.trim()
            if (b.isEmpty()) return prev.trim()
            fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]"), "")
            for (k in minOf(maxWords, a.size, b.size) downTo 2) {
                if ((0 until k).all { norm(a[a.size - k + it]) == norm(b[it]) }) {
                    return (a + b.drop(k)).joinToString(" ")
                }
            }
            return (a + b).joinToString(" ")
        }

        fun isWhisperHallucination(text: String): Boolean {
            val clean = text.trim().lowercase()
            val phrases = listOf(
                "feliratozta", "köszönöm, hogy megnézték", "köszönöm a figyelmet",
                "amara.org", "[zene]", "(zene)", "feliratok"
            )
            return phrases.any { clean.contains(it) }
        }
    }
}

/**
 * 16 bites PCM → 16 kHz mono átalakító (Fordító 0.1). Csatornákat átlagol, majd lineáris
 * interpolációval mintavételez át; az állapot a darabok között megmarad. 16 kHz mono
 * bemenetnél változatlanul adja vissza a bájtokat. Tiszta Kotlin (unit teszt).
 */
class Pcm16kConverter(srcRate: Int, channels: Int) {
    val srcRate: Int = if (srcRate > 0) srcRate else 16_000
    val channels: Int = channels.coerceAtLeast(1)
    private val passthrough = this.srcRate == TARGET && this.channels == 1
    private val step = this.srcRate.toDouble() / TARGET
    /** A következő kimeneti minta helye a bemenetben (az előző darab utolsó mintájához képest). */
    private var pos = 0.0
    private var prev: Short = 0
    private var hasPrev = false
    private var leftover = ByteArray(0)

    fun convert(bytes: ByteArray, len: Int): ByteArray {
        if (passthrough) return if (len == bytes.size) bytes else bytes.copyOf(len)
        val data = if (leftover.isEmpty()) bytes.copyOf(len) else leftover + bytes.copyOf(len)
        val frameBytes = 2 * channels
        val frames = data.size / frameBytes
        leftover = data.copyOfRange(frames * frameBytes, data.size)
        if (frames == 0) return ByteArray(0)

        // 1) mono
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val i = f * frameBytes + c * 2
                sum += ((data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)).toShort().toInt()
            }
            mono[f] = (sum / channels).toShort()
        }

        // 2) átmintavételezés: a „virtuális” sor = [prev] + mono; a pos ehhez képest értendő
        val out = java.io.ByteArrayOutputStream(((frames / step) + 2).toInt() * 2)
        val offset = if (hasPrev) 1 else 0
        fun sample(i: Int): Int = if (hasPrev && i == 0) prev.toInt() else mono[i - offset].toInt()
        val last = frames - 1 + offset
        while (pos <= last - 1) {
            val i0 = Math.floor(pos).toInt()
            val frac = pos - i0
            val v = sample(i0) * (1 - frac) + sample(i0 + 1) * frac
            val sv = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.write(sv and 0xff); out.write((sv shr 8) and 0xff)
            pos += step
        }
        // a pos átvitele a következő darabra: az új sor 0. eleme a mostani utolsó minta
        pos -= last
        prev = mono[frames - 1]
        hasPrev = true
        return out.toByteArray()
    }

    companion object { const val TARGET = 16_000 }
}
