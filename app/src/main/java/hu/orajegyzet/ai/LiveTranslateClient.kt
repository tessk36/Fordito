package hu.orajegyzet.ai

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Élő tolmács, online (Fordító 0.2): Gemini Live API, a `gemini-3.5-live-translate-preview` modellel.
 * WebSocketen 100 ms-os 16 kHz-es PCM-darabokat küld, és folyamatosan kapja:
 *  - az eredeti beszéd átiratát (inputTranscription),
 *  - a magyar fordítás szövegét (outputTranscription),
 *  - a magyar fordítás hangját (24 kHz PCM, modelTurn.parts[].inlineData).
 * Leírás: https://ai.google.dev/gemini-api/docs/live-api/live-translate
 */
class LiveTranslateClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val targetLanguage: String = "hu",
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onSourceText(text: String, languageCode: String?)
        fun onTranslatedText(text: String)
        fun onTranslatedAudio(pcm24k: ByteArray)
        fun onTurnComplete()
        fun onError(message: String)
        fun onClosed()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // folyamatos kapcsolat
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var ready = false
        private set
    @Volatile private var closedByUs = false
    /** A setup előtt érkező darabok (legfeljebb ~3 mp), hogy az eleje ne vesszen el. */
    private val pending = ArrayDeque<ByteArray>()

    /** A setup-üzenet mezőelrendezése (lásd [setupMessage]); a működőt megjegyezzük. */
    @Volatile private var layout = workingLayout
    private val attempts = mutableListOf<String>()

    fun connect() {
        if (apiKey.isBlank()) { listener.onError("Hiányzó Gemini API-kulcs (Beállítások → Online)."); return }
        open()
    }

    private fun open() {
        val req = Request.Builder()
            .url("$WS_URL?key=${apiKey.trim()}")
            .build()
        val myLayout = layout
        ws = http.newWebSocket(req, object : WebSocketListener() {
            /** Csak az aktuális kapcsolat eseményei számítanak (elrendezés-váltáskor a régi még zárhat). */
            private fun current(w: WebSocket) = w === ws

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(model, targetLanguage, myLayout).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (current(webSocket)) handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (current(webSocket)) handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!current(webSocket)) return
                if (!ready && !closedByUs) attempts += "setup$myLayout: " + shortReason(reason)
                // a szerver nem ismerte a setup egy mezőjét → a következő elrendezéssel újrapróbáljuk
                if (!ready && !closedByUs && code == 1007 && reason.contains("Unknown name") &&
                    myLayout + 1 < LAYOUT_COUNT) {
                    Log.i("Fordito", "Live setup elrendezés $myLayout elutasítva: $reason")
                    layout = myLayout + 1
                    open()
                    return
                }
                if (!closedByUs && code != 1000) listener.onError(
                    closeMessage(code, reason) + if (attempts.size > 1) "\n(Próbák: ${attempts.joinToString("; ")})" else "")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!current(webSocket)) return
                ready = false
                listener.onClosed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!current(webSocket)) return
                ready = false
                if (!closedByUs) {
                    val body = runCatching { response?.body?.string() }.getOrNull().orEmpty().take(300)
                    val code = response?.code
                    listener.onError(
                        when (code) {
                            401, 403 -> "Gemini API-kulcs / engedély hiba ($code)."
                            404 -> "Az élő fordító modell ($model) nem érhető el a kulcsoddal (404)."
                            429 -> "Gemini kvóta túllépés (429). Próbáld később."
                            null -> "Kapcsolati hiba: ${t.message ?: t.javaClass.simpleName}"
                            else -> "Élő kapcsolat hiba ($code): $body"
                        }
                    )
                }
                listener.onClosed()
            }
        })
    }

    private fun handle(raw: String) {
        val msg = parseServerMessage(raw)
        if (msg.setupComplete) {
            ready = true
            workingLayout = layout
            synchronized(pending) { while (pending.isNotEmpty()) sendNow(pending.removeFirst()) }
            listener.onReady()
        }
        msg.sourceText?.let { listener.onSourceText(it, msg.sourceLanguage) }
        msg.translatedText?.let { listener.onTranslatedText(it) }
        msg.audio.forEach { listener.onTranslatedAudio(it) }
        if (msg.turnComplete) listener.onTurnComplete()
        msg.error?.let { listener.onError(it) }
        if (msg.goAway) Log.i("Fordito", "Live goAway: a szerver hamarosan bontja a kapcsolatot")
    }

    /** Egy 100 ms-os, 16 kHz mono PCM darab küldése. */
    fun sendPcm(chunk: ByteArray) {
        if (!ready) {
            synchronized(pending) {
                pending.addLast(chunk)
                while (pending.size > 30) pending.removeFirst()
            }
            return
        }
        sendNow(chunk)
    }

    private fun sendNow(chunk: ByteArray) {
        ws?.send(audioMessage(chunk).toString())
    }

    fun close() {
        closedByUs = true
        runCatching { ws?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()) }
        runCatching { ws?.close(1000, "vége") }
        ws = null
        ready = false
    }

    /** A szerverüzenet érdemi részei (tiszta adat, unit teszt). */
    data class ServerMessage(
        val setupComplete: Boolean = false,
        val sourceText: String? = null,
        val sourceLanguage: String? = null,
        val translatedText: String? = null,
        val audio: List<ByteArray> = emptyList(),
        val turnComplete: Boolean = false,
        val goAway: Boolean = false,
        val error: String? = null
    )

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-live-translate-preview"
        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Ahány elrendezést kipróbálunk (a leírás szerinti, hibás 2-est nem). */
        const val LAYOUT_COUNT = 2
        /** Az utoljára elfogadott elrendezés (a következő indításnál ezzel kezd). */
        @Volatile var workingLayout = 0

        /**
         * A setup-üzenet. A Google leírása és a szerver nem egyezik abban, hova kerülnek a mezők
         * (a szerver a generationConfig alatt nem ismerte az inputAudioTranscription-t), ezért:
         *  0: átiratok a setup alatt, translationConfig a generationConfig alatt;
         *  1: átiratok és translationConfig is a setup alatt;
         *  2: minden a generationConfig alatt (a leírás szerinti; a szerver elutasítja, csak tesztben).
         * A 0 működik (fejlesztői fórum + kkdai/gemini-live-translate-macos, 2026. 10.).
         */
        fun setupMessage(model: String, target: String, layout: Int = 0): JSONObject {
            val m = if (model.startsWith("models/")) model else "models/$model"
            val translation = JSONObject()
                .put("targetLanguageCode", target)
                .put("echoTargetLanguage", false)
            val gen = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            val setup = JSONObject().put("model", m)
            when (layout) {
                0 -> {
                    setup.put("inputAudioTranscription", JSONObject())
                    setup.put("outputAudioTranscription", JSONObject())
                    gen.put("translationConfig", translation)
                }
                1 -> {
                    setup.put("inputAudioTranscription", JSONObject())
                    setup.put("outputAudioTranscription", JSONObject())
                    setup.put("translationConfig", translation)
                }
                else -> {
                    gen.put("inputAudioTranscription", JSONObject())
                    gen.put("outputAudioTranscription", JSONObject())
                    gen.put("translationConfig", translation)
                }
            }
            return JSONObject().put("setup", setup.put("generationConfig", gen))
        }

        fun audioMessage(chunk: ByteArray): JSONObject =
            JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                .put("data", Base64.encodeToString(chunk, Base64.NO_WRAP))
                .put("mimeType", "audio/pcm;rate=16000")))

        fun parseServerMessage(raw: String): ServerMessage {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return ServerMessage()
            o.optJSONObject("error")?.let { e ->
                return ServerMessage(error = "Gemini hiba: " + (e.optString("message").ifBlank { e.toString().take(200) }))
            }
            val sc = o.optJSONObject("serverContent")
            var src: String? = null; var lang: String? = null; var tr: String? = null
            val audio = mutableListOf<ByteArray>()
            var turn = false
            if (sc != null) {
                sc.optJSONObject("inputTranscription")?.let {
                    src = it.optString("text").takeIf { t -> t.isNotEmpty() }
                    lang = it.optString("languageCode").takeIf { l -> l.isNotEmpty() }
                }
                sc.optJSONObject("outputTranscription")?.let {
                    tr = it.optString("text").takeIf { t -> t.isNotEmpty() }
                }
                sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                    for (i in 0 until parts.length()) {
                        val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                        if (!data.isNullOrEmpty()) runCatching { audio += Base64.decode(data, Base64.DEFAULT) }
                    }
                }
                turn = sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")
            }
            return ServerMessage(
                setupComplete = o.has("setupComplete"),
                sourceText = src, sourceLanguage = lang, translatedText = tr,
                audio = audio, turnComplete = turn, goAway = o.has("goAway")
            )
        }

        /** A rövidített (123 bájtos) close-okból a lényeg: melyik mező, hol. */
        fun shortReason(reason: String): String =
            Regex("Unknown name \"([^\"]+)\" at '([^']+)'").find(reason)
                ?.let { "${it.groupValues[1]} @ ${it.groupValues[2]}" } ?: reason.take(60)

        fun closeMessage(code: Int, reason: String): String = when {
            reason.contains("API key", ignoreCase = true) -> "Érvénytelen Gemini API-kulcs ($code)."
            reason.contains("not found", ignoreCase = true) -> "A modell nem érhető el: $reason"
            reason.isNotBlank() -> "A szerver bontotta a kapcsolatot ($code): ${reason.take(400)}"
            else -> "A szerver bontotta a kapcsolatot ($code)."
        }
    }
}
