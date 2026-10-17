package hu.orajegyzet.ai

import android.content.Context
import android.util.Log
import hu.orajegyzet.data.AppSettings
import org.json.JSONObject

/** Átírási módok – a kimenet mindig magyar (Fordító 0.1). */
enum class TrStyle(val label: String, val instruction: String) {
    FAITHFUL(
        "Hű fordítás",
        "Fordítsd le pontosan, hűen az eredetihez: a szerkezet, a bekezdések és a tartalom maradjon meg, " +
            "semmit ne hagyj ki és ne tegyél hozzá."
    ),
    NATURAL(
        "Természetes",
        "Fordítsd le gördülékeny, természetes magyar nyelvre, ahogy egy anyanyelvi beszélő mondaná. " +
            "A tartalom ne változzon, de a szórend és a kifejezések legyenek magyarosak."
    ),
    FORMAL(
        "Hivatalos",
        "Fordítsd le, és fogalmazd meg hivatalos, udvarias, választékos magyar stílusban (pl. hivatalos levélhez, " +
            "dokumentumhoz). A tartalom ne változzon."
    ),
    SIMPLE(
        "Egyszerű",
        "Fordítsd le, és fogalmazd meg egyszerű, könnyen érthető magyar nyelven: rövid mondatok, hétköznapi szavak, " +
            "a szakszavakat magyarázd meg röviden. A tartalom ne változzon."
    ),
    SUMMARY(
        "Összefoglaló",
        "Készíts belőle rövid magyar összefoglalót: a lényeg néhány mondatban vagy pontokban. Csak a szövegben " +
            "szereplő tényeket használd."
    );

    companion object {
        fun of(name: String?): TrStyle = entries.firstOrNull { it.name == name } ?: NATURAL
    }
}

/** Egy fordítás eredménye. */
data class TrResult(
    val sourceLang: String,
    val text: String,
    val engine: String,
    val warning: String? = null
)

/** Hangból: átirat (eredeti nyelven) + fordítás. */
data class TrAudioResult(
    val transcript: String,
    val result: TrResult
)

/**
 * Fordítás / átírás bármilyen nyelvről magyarra.
 *  - Gemma 4 (helyben): rövid blokkokban, mert a modell tokenkerete kicsi (4000 token bemenet + válasz).
 *  - Gemini (felhő): nagy blokkokban, hangból egyetlen hívásban átirat + fordítás.
 * A motorválasztás ugyanaz, mint az AssiSten-s-ben (EngineRouting + adatvédelmi zár).
 */
object Translator {

    const val SYSTEM =
        "Profi fordító és szerkesztő vagy. Bármilyen nyelvű szöveget magyarra fordítasz, illetve magyar szöveget " +
            "a kért stílusban átírsz. A neveket, számokat, dátumokat pontosan megtartod, semmit nem találsz ki. " +
            "Bevezető, magyarázat és megjegyzés nélkül, csak a kért kimenetet adod."

    /** Gemma: kb. 1600 karakter bemenet fér el kényelmesen a válasszal együtt. */
    const val GEMMA_BLOCK_CHARS = 1600
    const val GEMINI_BLOCK_CHARS = 20_000
    private const val GEMMA_CALL_TIMEOUT_MS = 180_000L

    /** Melyik motor fut most (null = nincs használható motor). */
    fun engineFor(ctx: Context, s: AppSettings): String? =
        when (EngineRouting.decide(
            preferGemini = s.preferGemini,
            privacyLock = s.privacyLock,
            hasApiKey = s.geminiApiKey.isNotBlank(),
            gemmaInstalled = GemmaModel.file(ctx) != null,
            network = !s.privacyLock && ModeSelector.hasNetwork(ctx, s.wifiOnly)
        )) {
            EngineRouting.Route.GEMINI -> SummaryEngine.GEMINI
            EngineRouting.Route.GEMMA -> SummaryEngine.GEMMA
            EngineRouting.Route.EXTRACTIVE -> null
        }

    const val NO_ENGINE_MESSAGE =
        "Nincs használható fordítómotor. Importálj Gemma-modellt (Beállítások → Modellek), vagy add meg a " +
            "Gemini API-kulcsot, és kapcsold ki az adatvédelmi zárat."

    // ---------------------------------------------------------------- szöveg

    suspend fun translateText(
        ctx: Context,
        s: AppSettings,
        text: String,
        style: TrStyle,
        onProgress: (suspend (String) -> Unit)? = null
    ): TrResult {
        val clean = text.trim()
        if (clean.isEmpty()) error("Üres a szöveg, nincs mit fordítani.")
        return when (engineFor(ctx, s)) {
            SummaryEngine.GEMINI -> viaGemini(s, clean, style, onProgress)
            SummaryEngine.GEMMA -> viaGemma(ctx, clean, style, onProgress)
            else -> error(NO_ENGINE_MESSAGE)
        }
    }

    private suspend fun viaGemini(s: AppSettings, text: String, style: TrStyle, onProgress: (suspend (String) -> Unit)?): TrResult {
        val gemini = GeminiPipeline(s.geminiApiKey, s.geminiModel)
        val blocks = blocksFor(text, style, GEMINI_BLOCK_CHARS)
        var lang = ""
        val parts = mutableListOf<String>()
        blocks.forEachIndexed { i, b ->
            onProgress?.invoke(if (blocks.size == 1) "Fordítás: Gemini" else "Fordítás: ${i + 1}/${blocks.size} rész (Gemini)")
            val raw = gemini.generateFreeText(textPrompt(style, b, askLanguage = i == 0), system = SYSTEM)
            val p = parseOutput(raw)
            if (i == 0) lang = p.first
            parts += p.second
        }
        return TrResult(lang, joinParts(parts, style), SummaryEngine.GEMINI)
    }

    private suspend fun viaGemma(ctx: Context, text: String, style: TrStyle, onProgress: (suspend (String) -> Unit)?): TrResult {
        val file = GemmaModel.file(ctx) ?: error(NO_ENGINE_MESSAGE)
        onProgress?.invoke("Gemma 4 betöltése…")
        OfflinePipeline.releaseWarmWhisper() // memória: a Whisper ne maradjon mellette
        val blocks = blocksFor(text, style, GEMMA_BLOCK_CHARS)
        var lang = ""
        val parts = mutableListOf<String>()
        var warning: String? = null
        GemmaSession.open(ctx, file).use { session ->
            blocks.forEachIndexed { i, b ->
                onProgress?.invoke(if (blocks.size == 1) "Fordítás: Gemma 4 (${session.backendName})"
                    else "Fordítás: ${i + 1}/${blocks.size} rész (Gemma 4, ${session.backendName})")
                val raw = session.generate(textPrompt(style, b, askLanguage = i == 0), GEMMA_CALL_TIMEOUT_MS)
                val p = parseOutput(raw)
                if (i == 0) lang = p.first
                if (p.second.isBlank()) {
                    warning = "A Gemma egy részre üres választ adott; ott az eredeti szöveg maradt."
                    parts += b
                } else parts += p.second
            }
        }
        if (style == TrStyle.SUMMARY && parts.size > 1) {
            // több rész összefoglalóját még egyszer összevonjuk, ha belefér
            val joined = parts.joinToString("\n")
            if (joined.length <= GEMMA_BLOCK_CHARS * 2) {
                onProgress?.invoke("Összefoglaló összevonása (Gemma 4)…")
                runCatching {
                    GemmaSession.open(ctx, file).use { sess ->
                        sess.generate(mergeSummaryPrompt(joined), GEMMA_CALL_TIMEOUT_MS)
                    }
                }.onSuccess { merged ->
                    val m = stripLanguageLine(merged).trim()
                    if (m.isNotBlank()) return TrResult(lang, m, SummaryEngine.GEMMA, warning)
                }
            }
        }
        Log.i("Fordito", "gemma blocks=${blocks.size} lang=$lang")
        return TrResult(lang, joinParts(parts, style), SummaryEngine.GEMMA, warning)
    }

    /** Összefoglalónál nagyobb blokk is mehet (a kimenet rövid), a többinél a beállított méret. */
    private fun blocksFor(text: String, style: TrStyle, max: Int): List<String> =
        if (text.length <= max) listOf(text)
        else OfflinePipeline.splitBlocks(text, if (style == TrStyle.SUMMARY) (max * 3 / 2) else max)

    private fun joinParts(parts: List<String>, style: TrStyle): String =
        parts.map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(if (style == TrStyle.SUMMARY) "\n" else "\n\n")

    // ---------------------------------------------------------------- hang

    /**
     * Hang → átirat + fordítás. Gemini esetén egy hívásban (a hang a felhőbe kerül, utána törlődik);
     * különben Whisper helyben (automatikus nyelvfelismeréssel), majd szövegfordítás.
     */
    suspend fun translateAudio(
        ctx: Context,
        s: AppSettings,
        audioPath: String,
        mimeType: String,
        style: TrStyle,
        onProgress: (suspend (String) -> Unit)? = null,
        onTranscript: (suspend (String) -> Unit)? = null
    ): TrAudioResult {
        val engine = engineFor(ctx, s)
        if (engine == SummaryEngine.GEMINI) {
            onProgress?.invoke("Átirat és fordítás: Gemini (${s.geminiModel})")
            val raw = GeminiPipeline(s.geminiApiKey, s.geminiModel)
                .generateFromAudio(audioPath, mimeType, audioPrompt(style), SYSTEM, jsonResponse = true)
            val parsed = parseAudioJson(raw)
            if (parsed != null && parsed.second.isNotBlank()) {
                onTranscript?.invoke(parsed.first.third)
                return TrAudioResult(parsed.first.third,
                    TrResult(parsed.first.first, parsed.second, SummaryEngine.GEMINI))
            }
            // Ha a Gemini nem adott használható választ: helyben folytatjuk, ha lehet
            Log.w("Fordito", "Gemini hang-válasz nem értelmezhető, helyi átirat következik")
        }
        val whisperModel = java.io.File(ctx.filesDir, "models/whisper.bin")
        if (!whisperModel.isFile) error(
            if (engine == null) NO_ENGINE_MESSAGE
            else "Nincs Whisper-modell a helyi átirathoz (Beállítások → Modellek), és a Gemini nem érhető el.")

        val pipeline = OfflinePipeline(
            ctx, useGemma = false,
            whisperThreads = s.whisperThreads, keepWarm = s.keepModelWarm, warmMinutes = s.warmMinutes,
            chunkSec = s.whisperChunkSec, overlapSec = s.whisperOverlapSec,
            onProgress = onProgress,
            whisperPrompt = glossaryPrompt(s.whisperGlossary),
            whisperAccurate = s.whisperAccurate
        )
        val transcript = pipeline.transcribeOnly(audioPath) { partial -> onTranscript?.invoke(partial) }.trim()
        if (transcript.isBlank()) error("Az átirat üres: a felvételen nem hallatszott beszéd.")
        onTranscript?.invoke(transcript)
        val res = translateText(ctx, s, transcript, style, onProgress)
        return TrAudioResult(transcript, res)
    }

    /**
     * A Whisper „szótára”: csak a nevek és szakszavak, nyelvi utalás nélkül, hogy ne terelje
     * a nyelvfelismerést a magyar felé.
     */
    fun glossaryPrompt(glossary: String): String {
        val terms = glossary.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return terms.joinToString(", ").take(WhisperPrompt.MAX_CHARS)
    }

    // ---------------------------------------------------------------- promptok

    fun textPrompt(style: TrStyle, text: String, askLanguage: Boolean): String {
        val langLine = if (askLanguage)
            "Az első sorba írd: NYELV: <a szöveg eredeti nyelve magyarul, pl. angol, német, magyar>\n" +
                "A második sorba írd: ---\nUtána jöjjön a kimenet.\n"
        else "Csak a kimenetet add, nyelvmegjelölés nélkül.\n"
        return """
Feladat: ${style.instruction}
Ha a szöveg már magyar, ne fordítsd, hanem írd át a fenti módon, magyarul.
${langLine}Ne írj semmit a kimeneten kívül, ne ismételd meg az utasítást.

Szöveg:
$text
""".trim()
    }

    private fun mergeSummaryPrompt(parts: String): String = """
Az alábbi részösszefoglalókból készíts egyetlen rövid, ismétlés nélküli magyar összefoglalót.
Csak a bennük szereplő tényeket használd. Csak az összefoglalót add.

Részösszefoglalók:
$parts
""".trim()

    fun audioPrompt(style: TrStyle): String = """
Ez egy hangfelvétel. Bármilyen nyelven szólhat.
1. Készíts pontos átiratot az ELHANGZOTT nyelven (ne fordítsd).
2. Ismerd fel a nyelvet.
3. ${style.instruction} A kimenet magyar legyen. Ha a felvétel magyar, írd át a kért módon.
Válaszolj KIZÁRÓLAG érvényes JSON-nal, markdown nélkül, pontosan ezekkel a kulcsokkal:
{"language": "az eredeti nyelv magyarul, pl. angol", "transcript": "átirat az eredeti nyelven", "translation": "a magyar kimenet"}
""".trim()

    // ---------------------------------------------------------------- értelmezés

    /**
     * A modell kimenetéből kiveszi a „NYELV: …” sort és a „---” elválasztót.
     * @return (nyelv, szöveg) – a nyelv üres, ha nem volt megadva. Tiszta függvény (unit teszt).
     */
    fun parseOutput(raw: String): Pair<String, String> {
        var t = raw.trim().removePrefix("```").removeSuffix("```").trim()
        var lang = ""
        val lines = t.lines().toMutableList()
        // a nyelvsor az első pár sorban lehet (a modell néha üres sort tesz elé)
        val idx = lines.take(3).indexOfFirst { LANG_RE.containsMatchIn(it) }
        if (idx >= 0) {
            lang = LANG_RE.find(lines[idx])!!.groupValues[1].trim().trim('*', '.', ' ').lowercase()
            repeat(idx + 1) { lines.removeAt(0) }
            while (lines.isNotEmpty() && (lines[0].isBlank() || lines[0].trim().matches(SEP_RE))) lines.removeAt(0)
            t = lines.joinToString("\n").trim()
        } else if (lines.isNotEmpty() && lines[0].trim().matches(SEP_RE)) {
            t = lines.drop(1).joinToString("\n").trim()
        }
        return lang to stripPreamble(t)
    }

    private fun stripLanguageLine(raw: String): String = parseOutput(raw).second

    /** Gyakori bevezetők levágása („Íme a fordítás:” stb.). */
    private fun stripPreamble(t: String): String {
        val first = t.lineSequence().firstOrNull()?.trim() ?: return t
        return if (PREAMBLE_RE.matches(first)) t.substringAfter('\n', "").trim().ifBlank { t } else t
    }

    /** Gemini hang-JSON → ((nyelv, _, átirat), fordítás); null, ha nem értelmezhető. */
    fun parseAudioJson(raw: String): Pair<Triple<String, String, String>, String>? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching {
            val o = JSONObject(cleaned)
            Triple(o.optString("language").trim().lowercase(), "", o.optString("transcript").trim()) to
                o.optString("translation").trim()
        }.getOrNull()
    }

    private val LANG_RE = Regex("^\\s*\\**\\s*(?:NYELV|Nyelv|nyelv|LANGUAGE|Language)\\s*\\**\\s*:\\s*(.+)$")
    private val SEP_RE = Regex("^-{3,}$")
    private val PREAMBLE_RE = Regex("^(Íme|Itt van|Itt a|Fordítás|A fordítás|Átírás|Here is)[^\\n]{0,60}:\\s*$", RegexOption.IGNORE_CASE)
}
