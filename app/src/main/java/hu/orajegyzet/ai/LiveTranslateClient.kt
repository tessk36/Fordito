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

    fun connect() {
        if (apiKey.isBlank()) { listener.onError("Hiányzó Gemini API-kulcs (Beállítások → Online)."); return }
        val req = Request.Builder()
            .url("$WS_URL?key=${apiKey.trim()}")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(model, targetLanguage).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (!closedByUs && code != 1000) listener.onError(closeMessage(code, reason))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                listener.onClosed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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

        fun setupMessage(model: String, target: String): JSONObject {
            val m = if (model.startsWith("models/")) model else "models/$model"
            val gen = JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put("translationConfig", JSONObject()
                    .put("targetLanguageCode", target)
                    .put("echoTargetLanguage", false))
            return JSONObject().put("setup", JSONObject().put("model", m).put("generationConfig", gen))
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

        fun closeMessage(code: Int, reason: String): String = when {
            reason.contains("API key", ignoreCase = true) -> "Érvénytelen Gemini API-kulcs ($code)."
            reason.contains("not found", ignoreCase = true) -> "A modell nem érhető el: $reason"
            reason.isNotBlank() -> "A szerver bontotta a kapcsolatot ($code): ${reason.take(200)}"
            else -> "A szerver bontotta a kapcsolatot ($code)."
        }
    }
}
