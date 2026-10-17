package hu.orajegyzet.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import hu.orajegyzet.data.AppSettings
import hu.orajegyzet.data.Mode
import org.json.JSONObject

/** A feldolgozás kimenete. */
data class NoteResult(
    val transcript: String,
    val summary: String,
    val structured: String,
    val keywords: List<String>,
    /** Mi készítette az összefoglalót: GEMMA | GEMINI | EXTRACTIVE | TEMPLATE (1.0.4). */
    val engine: String? = null,
    /** Felhasználónak szóló figyelmeztetés (pl. miért nem futott a Gemma). */
    val warning: String? = null
)

/** Az összefoglaló forrásának azonosítói (a Note.summaryEngine mezőbe kerülnek). */
object SummaryEngine {
    const val GEMMA = "GEMMA"
    const val GEMINI = "GEMINI"
    const val EXTRACTIVE = "EXTRACTIVE"
    const val TEMPLATE = "TEMPLATE"

    fun label(engine: String?): String? = when (engine) {
        GEMMA -> "📱 Gemma"
        GEMINI -> "☁ Gemini"
        EXTRACTIVE -> "Kivonat (nem AI)"
        TEMPLATE -> "Offline vázlat"
        else -> null
    }
}

/** Egy hangfájl → jegyzet feldolgozó. Két implementáció: Offline és Gemini. */
interface NotePipeline {
    /** @param audioPath M4A fájl (16 kHz mono AAC). Hosszú futás — Worker-szálon hívandó.
     *  @param isLesson igaz, ha órarendi órából indult (tanár/óra megfogalmazás),
     *         hamis, ha általános esemény (előadó/esemény megfogalmazás). */
    suspend fun process(audioPath: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult

    /** Újra-összefoglalás meglévő leiratból (hang nélkül), ha a felhasználónak
     *  nem tetszik az eredmény. A leiratot változatlanul visszaadja. */
    suspend fun resummarize(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult
}

/** Eldönti, hogy az adott körülmények között melyik pipeline fusson. */
object ModeSelector {

    /**
     * true = Gemini (online), false = helyi (Whisper + Gemma / kivonat).
     * 1.1.3: a döntést az alap elemző + adatvédelmi zár + hálózat adja (EngineRouting);
     * a régi „Mód” beállítás már nem számít.
     */
    fun choose(ctx: Context, s: AppSettings): Boolean /* true = online */ =
        EngineRouting.decide(
            preferGemini = s.preferGemini,
            privacyLock = s.privacyLock,
            hasApiKey = s.geminiApiKey.isNotBlank(),
            gemmaInstalled = GemmaModel.file(ctx) != null,
            network = !s.privacyLock && hasNetwork(ctx, s.wifiOnly)
        ) == EngineRouting.Route.GEMINI

    /** Interaktív (felhasználó által indított) AI-hívásokhoz – ugyanaz a döntés. */
    fun canUseGeminiNow(ctx: Context, s: AppSettings): Boolean = choose(ctx, s)

    fun hasNetwork(ctx: Context, wifiOnly: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return if (wifiOnly) caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) else true
    }
}

/** Közös segéd: a modellek JSON-válaszának tűrőképes feldolgozása. */
internal fun parseModelJson(raw: String, fallbackTranscript: String = ""): NoteResult {
    val cleaned = raw.trim()
        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    // 1) Először a szabályos JSON (ha a modell tisztán adta)
    runCatching {
        val o = JSONObject(cleaned)
        val kw = mutableListOf<String>()
        o.optJSONArray("keywords")?.let { arr -> for (i in 0 until arr.length()) kw += arr.getString(i) }

        val summary = o.optString("summary")
        if (summary.isNotBlank()) {
            return NoteResult(
                transcript = o.optString("transcript", fallbackTranscript).ifBlank { fallbackTranscript },
                summary = summary,
                structured = o.optString("notes"),
                keywords = kw
            )
        }
    }

    // 2) Megengedő, mezőnkénti kinyerés (működik hiányzó vesszők / hibás JSON esetén is)
    fun unescape(s: String) = s
        .replace("\\n", "\n").replace("\\t", "\t")
        .replace("\\\"", "\"").replace("\\\\", "\\")
    fun field(key: String): String {
        val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
            .find(cleaned) ?: return ""
        return unescape(m.groupValues[1]).trim()
    }
    val summary = field("summary")
    val notes = field("notes")
    val tr = field("transcript")
    val kw = Regex("\"keywords\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
        .find(cleaned)?.groupValues?.get(1)
        ?.let { inner -> Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(inner).map { unescape(it.groupValues[1]) }.toList() }
        ?: emptyList()

    return if (summary.isBlank() && notes.isBlank()) {
        NoteResult(fallbackTranscript.ifBlank { tr }, cleaned.take(2000), "", emptyList())
    } else {
        NoteResult(fallbackTranscript.ifBlank { tr }, summary, notes, kw)
    }
}

internal fun buildNotePrompt(isLesson: Boolean): String {
    val role = if (isLesson) "tanár/előadó" else "projektvezető/résztvevő"
    val event = if (isLesson) "tanóra/előadás" else "projekt megbeszélés/egyeztetés"
    return """Te egy vezető Projekt Asszisztens és hivatalos jegyzetelő AI vagy.
A bemenet egy $event hanganyaga vagy leirata magyarul.
STÍLUS ÉS TÓNUS: Az összefoglaló ("summary") és a jegyzet KIZÁRÓLAG asszertív hivatali, hivatalos és határozott vezetői stílusban készüljön! Tényszerű, tiszta, professzionális megfogalmazást használj, kertelés és bizonytalan kifejezések nélkül.

Válaszolj KIZÁRÓLAG érvényes JSON-nal, markdown nélkül, pontosan ezekkel a kulcsokkal:
{"transcript": "teljes leirat (ha hangot kaptál; leirat-bemenetnél hagyd üresen)",
 "summary": "8-12 mondatos, asszertív hivatali és hivatalos vezetői stílusban megírt tömör összefoglaló magyarul",
 "notes": "strukturált jegyzet markdown formátumban: ## Hivatalos Témakörök, **Hivatali Fogalmak**, határozott döntések és kötelezettségek",
 "keywords": ["5-10 kulcsfogalom"]}
Ha a beszélőre hivatkozol, használd a(z) "$role" megfogalmazást.
Csak az elhangzottakra támaszkodj, ne találj ki nem létező információt."""
}
