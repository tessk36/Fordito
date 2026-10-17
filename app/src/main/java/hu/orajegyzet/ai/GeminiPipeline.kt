package hu.orajegyzet.ai

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GeminiHttpException(
    val code: Int,
    message: String,
    val isInvalidKey: Boolean = false,
    val rawBody: String = ""
) : RuntimeException(message)

/**
 * Online mód: a hangfájl a Gemini Files API-ra kerül, megvárja az ACTIVE állapotot,
 * majd egyetlen generateContent hívás készíti el a leiratot + jegyzetet + összefoglalót.
 * A feldolgozás után a fájl törlődik a felhőből.
 */
class GeminiPipeline(
    private val apiKey: String,
    private val model: String = "gemini-3.1-flash-lite",
    private val baseUrl: String = BASE
) : NotePipeline {

    private val http = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("x-goog-api-key", apiKey.trim())
                    .build()
            )
        }
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    override suspend fun process(audioPath: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult {
        validateApiKey()
        val file = File(audioPath)
        val raw = if (file.length() in 1..INLINE_MAX_BYTES) {
            try {
                generateInlineWithFallback(file, isLesson)
            } catch (e: GeminiHttpException) {
                if (!isPayloadTooLarge(e)) throw e
                generateViaUpload(file, isLesson)
            }
        } else {
            generateViaUpload(file, isLesson)
        }
        return parseModelJson(raw)
    }

    private fun isPayloadTooLarge(e: GeminiHttpException): Boolean {
        if (e.isInvalidKey || e.code == 401 || e.code == 403) return false
        return e.code == 413 ||
            (e.code == 400 && e.rawBody.let {
                it.contains("size", ignoreCase = true) ||
                it.contains("too large", ignoreCase = true) ||
                it.contains("exceeds", ignoreCase = true)
            })
    }

    private fun generateViaUpload(file: File, isLesson: Boolean): String {
        val fileUri = uploadFile(file)
        return try {
            waitForFileActive(fileUri)
            generateWithFallback(fileUri, isLesson)
        } finally {
            runCatching { deleteRemote(fileUri) }
        }
    }

    private fun validateApiKey() {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            throw RuntimeException("Hiányzó Gemini API-kulcs! Add meg a Beállítások képernyőn.")
        }
    }

    private fun modelsToTry(): List<String> = (listOf(model) + SUPPORTED_MODELS).distinct()

    private fun encodeBase64(bytes: ByteArray): String {
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun generateInlineWithFallback(file: File, isLesson: Boolean): String {
        val b64 = encodeBase64(file.readBytes())
        var lastError: Exception? = null

        for (m in modelsToTry()) {
            try {
                val parts = JSONArray()
                parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/mp4").put("data", b64)))
                parts.put(JSONObject().put("text", "Ez egy hangfelvétel. Készítsd el a JSON-választ a megadott séma szerint."))

                val userContent = JSONObject()
                userContent.put("role", "user")
                userContent.put("parts", parts)

                val sysInst = JSONObject()
                sysInst.put("parts", JSONArray().put(JSONObject().put("text", buildNotePrompt(isLesson))))

                val body = JSONObject()
                body.put("systemInstruction", sysInst)
                body.put("contents", JSONArray().put(userContent))
                body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

                val req = Request.Builder()
                    .url("$baseUrl/v1beta/models/$m:generateContent")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                return http.newCall(req).execute().use { r ->
                    val text = r.body?.string() ?: ""
                    if (!r.isSuccessful) {
                        throw errorForHttpCode(r.code, text, "generateContent", m)
                    }
                    parseCandidateText(text)
                }
            } catch (e: GeminiHttpException) {
                lastError = e
                if (e.code == 404) continue
                throw e
            } catch (e: Exception) {
                lastError = e
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el.")
    }

    override suspend fun resummarize(transcript: String, subject: String, dateLabel: String, isLesson: Boolean): NoteResult {
        val raw = generateFromTextWithFallback(transcript, isLesson)
        return parseModelJson(raw, fallbackTranscript = transcript).copy(transcript = transcript)
    }

    /**
     * Szabad formátumú szöveggenerálás (1.1.1): a megadott utasítást és szöveget KÖZVETLENÜL küldi,
     * a jegyzet-JSON séma nélkül. Ezt használják a stílusos összefoglalók és a projektjelentések,
     * hogy a kért szerkezet (vezetői, jegyzőkönyv, teendők, részletes) ténylegesen érvényesüljön.
     */
    fun generateFreeText(instruction: String, text: String = "", system: String = SummaryStyles.SYSTEM): String {
        validateApiKey()
        val userText = if (text.isBlank()) instruction else "$instruction\n\nSzöveg:\n$text"
        var lastError: Exception? = null
        for (m in modelsToTry()) {
            try {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userText)))))
                    .put("generationConfig", JSONObject().put("temperature", 0.3))
                val req = Request.Builder()
                    .url("$baseUrl/v1beta/models/$m:generateContent")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                return http.newCall(req).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    if (!r.isSuccessful) throw errorForHttpCode(r.code, t, "generateContent", m)
                    parseCandidateText(t).trim()
                }
            } catch (e: GeminiHttpException) {
                lastError = e
                if (e.code == 404) continue
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el (404).")
    }

    /**
     * Fordító 0.1: tetszőleges utasítás egy hangfájlra (pl. átirat + fordítás JSON-ban).
     * 10 MB alatt beágyazva küldi, fölötte (vagy ha a Gemini túl nagynak találja) a Files API-n át;
     * a feltöltött fájl a végén törlődik a felhőből. 404-nél a tartalék modellekkel próbálkozik.
     */
    fun generateFromAudio(
        audioPath: String,
        mimeType: String,
        instruction: String,
        system: String,
        jsonResponse: Boolean = true
    ): String {
        validateApiKey()
        val file = File(audioPath)
        if (!file.isFile || file.length() == 0L) error("A hangfájl hiányzik vagy üres.")
        val mime = mimeType.ifBlank { "audio/mp4" }

        fun body(audioPart: JSONObject): JSONObject {
            val gen = JSONObject().put("temperature", 0.2)
            if (jsonResponse) gen.put("responseMimeType", "application/json")
            return JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray()
                        .put(audioPart)
                        .put(JSONObject().put("text", instruction)))))
                .put("generationConfig", gen)
        }

        fun call(audioPart: JSONObject): String {
            var lastError: Exception? = null
            for (m in modelsToTry()) {
                try {
                    val req = Request.Builder()
                        .url("$baseUrl/v1beta/models/$m:generateContent")
                        .post(body(audioPart).toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    return http.newCall(req).execute().use { r ->
                        val t = r.body?.string() ?: ""
                        if (!r.isSuccessful) throw errorForHttpCode(r.code, t, "generateContent", m)
                        parseCandidateText(t)
                    }
                } catch (e: GeminiHttpException) {
                    lastError = e
                    if (e.code == 404) continue
                    throw e
                }
            }
            throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el (404).")
        }

        fun viaUpload(): String {
            val fileUri = uploadFile(file, mime)
            return try {
                waitForFileActive(fileUri)
                call(JSONObject().put("fileData", JSONObject().put("mimeType", mime).put("fileUri", fileUri)))
            } finally {
                runCatching { deleteRemote(fileUri) }
            }
        }

        if (file.length() > INLINE_MAX_BYTES) return viaUpload()
        return try {
            call(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", mime).put("data", encodeBase64(file.readBytes()))))
        } catch (e: GeminiHttpException) {
            if (!isPayloadTooLarge(e)) throw e
            viaUpload()
        }
    }

    /**
     * A kulccsal elérhető modellek lekérése (ListModels, lapozással), szűrve a jegyzetkészítésre
     * alkalmasakra (1.1.6). Hibánál érthető üzenettel dob.
     */
    fun listModels(): List<String> {
        validateApiKey()
        val entries = mutableListOf<GeminiModels.Entry>()
        var pageToken: String? = null
        var guard = 0
        do {
            val url = "$baseUrl/v1beta/models?pageSize=1000" + (pageToken?.let { "&pageToken=$it" } ?: "")
            val req = Request.Builder().url(url).get().build()
            val body = http.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: ""
                if (!r.isSuccessful) throw errorForHttpCode(r.code, t, "Modellista")
                t
            }
            val json = JSONObject(body)
            val arr = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList()
                entries += GeminiModels.Entry(
                    id = m.optString("name").removePrefix("models/"),
                    displayName = m.optString("displayName"),
                    methods = methods
                )
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null && ++guard < 10)
        return GeminiModels.usable(entries)
    }

    private fun generateWithFallback(fileUri: String, isLesson: Boolean): String {
        var lastError: Exception? = null
        for (m in modelsToTry()) {
            try {
                return generate(fileUri, isLesson, m)
            } catch (e: GeminiHttpException) {
                lastError = e
                if (e.code == 404) continue
                throw e
            } catch (e: Exception) {
                lastError = e
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el (404).")
    }

    private fun generateFromTextWithFallback(transcript: String, isLesson: Boolean): String {
        var lastError: Exception? = null
        for (m in modelsToTry()) {
            try {
                return generateFromText(transcript, isLesson, m)
            } catch (e: GeminiHttpException) {
                lastError = e
                if (e.code == 404) continue
                throw e
            } catch (e: Exception) {
                lastError = e
                throw e
            }
        }
        throw lastError ?: RuntimeException("A Gemini modellek egyike sem érhető el (404).")
    }

    private fun generateFromText(transcript: String, isLesson: Boolean, targetModel: String = model): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", buildNotePrompt(isLesson)))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("text",
                        "Ez egy meglévő leirat. Készíts belőle ÚJ, jobb összefoglalót és jegyzetet a megadott séma szerint. A \"transcript\" mezőbe másold vissza változatlanul a leiratot.\n\nLeirat:\n$transcript")))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val req = Request.Builder()
            .url("$baseUrl/v1beta/models/$targetModel:generateContent")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "generateContent", targetModel)
            }
            parseCandidateText(text)
        }
    }

    /** Resumable feltöltés a Files API-ra; a file_uri-t adja vissza. */
    private fun uploadFile(file: File, mimeType: String = "audio/mp4"): String {
        val meta = JSONObject().put("file", JSONObject().put("display_name", file.name))
        val startReq = Request.Builder()
            .url("$baseUrl/upload/v1beta/files")
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(meta.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val uploadUrl = http.newCall(startReq).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "Files API indítás")
            }
            r.header("X-Goog-Upload-URL") ?: error("Hiányzó upload URL a Google-től")
        }

        val upReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Command", "upload, finalize")
            .header("X-Goog-Upload-Offset", "0")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        return http.newCall(upReq).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "Files API feltöltés")
            }
            JSONObject(text).getJSONObject("file").getString("uri")
        }
    }

    /** Megvárja, amíg a feltöltött fájl állapota ACTIVE lesz a Google szerverén. */
    private fun waitForFileActive(fileUri: String) {
        val id = fileUri.substringAfter("/files/")
        val req = Request.Builder()
            .url("$baseUrl/v1beta/files/$id")
            .get()
            .build()

        var attempts = 0
        while (attempts < 30) {
            runCatching {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val state = JSONObject(r.body?.string() ?: "").optString("state")
                        if (state == "ACTIVE") return
                        if (state == "FAILED") error("A fájl feldolgozása meghiúsult a Gemini szerverén.")
                    }
                }
            }
            Thread.sleep(1000)
            attempts++
        }
    }

    private fun generate(fileUri: String, isLesson: Boolean, targetModel: String = model): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", buildNotePrompt(isLesson)))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("fileData", JSONObject()
                        .put("mimeType", "audio/mp4").put("fileUri", fileUri)))
                    .put(JSONObject().put("text",
                        "Ez egy hangfelvétel. Készítsd el a JSON-választ a megadott séma szerint.")))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val req = Request.Builder()
            .url("$baseUrl/v1beta/models/$targetModel:generateContent")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return http.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) {
                throw errorForHttpCode(r.code, text, "generateContent", targetModel)
            }
            parseCandidateText(text)
        }
    }

    private fun parseCandidateText(text: String): String {
        val json = JSONObject(text)
        val candidates = json.optJSONArray("candidates")
            ?: error("A Gemini nem adott vissza választ (candidates hiányzik).")
        if (candidates.length() == 0) error("Üres válasz érkezett a Geminitől.")
        val cand = candidates.getJSONObject(0)
        val content = cand.optJSONObject("content")
            ?: error("A Gemini válasz nem tartalmaz tartalmat (lehet, hogy a biztonsági szűrő letiltotta).")
        val parts = content.optJSONArray("parts")
            ?: error("A Gemini válasz nem tartalmaz szöveges elemeket.")
        return parts.getJSONObject(0).getString("text")
    }

    private fun deleteRemote(fileUri: String) {
        val id = fileUri.substringAfter("/files/")
        val req = Request.Builder()
            .url("$baseUrl/v1beta/files/$id")
            .delete()
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    private fun errorForHttpCode(code: Int, bodyText: String, action: String, targetModel: String = model): GeminiHttpException {
        val invalidKey = isInvalidKey(code, bodyText)
        val shortBody = if (bodyText.length > 300) bodyText.take(300) + "…" else bodyText
        val msg = when {
            code == 401 || code == 403 || invalidKey ->
                "Gemini API-kulcs / engedély hiba ($code). Ellenőrizd az API-kulcsot a Beállításokban!"
            code == 400 -> "Gemini kérés hiba ($code): $shortBody"
            code == 404 -> "Gemini modell hiba ($code). A megadott modell ($targetModel) nem érhető el."
            code == 429 -> "Gemini API kvóta túllépés ($code). Próbáld újra kicsit később!"
            else        -> "$action hiba ($code): $shortBody"
        }
        return GeminiHttpException(code, msg, isInvalidKey = invalidKey, rawBody = bodyText)
    }

    private fun isInvalidKey(code: Int, body: String) =
        code == 400 && (body.contains("API_KEY_INVALID") ||
                        body.contains("API key not valid", ignoreCase = true))

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com"
        private const val INLINE_MAX_BYTES = 10L * 1024 * 1024
        /** 404-nél kipróbált tartalék modellek (1.1.6: frissítve; a 3.5-flash és 3.7-flash kivezetve). */
        val SUPPORTED_MODELS = GeminiModels.FALLBACK
    }
}
