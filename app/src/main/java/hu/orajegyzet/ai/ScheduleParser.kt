package hu.orajegyzet.ai

import android.content.Context
import android.net.Uri
import android.util.Base64
import hu.orajegyzet.data.Lesson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Órarend/naptár fényképből → órarendi bejegyzések, Gemini képfelismeréssel.
 * Online funkció (API-kulcs kell). A kép a feldolgozás után nem tárolódik.
 */
object ScheduleParser {

    private const val BASE = "https://generativelanguage.googleapis.com"

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES)
        .build()

    private const val PROMPT = """Egy órarend vagy naptár fényképét kapod.
Olvasd ki belőle az órákat/eseményeket, és add vissza KIZÁRÓLAG érvényes JSON-t,
markdown nélkül, pontosan ebben a formában:
{"lessons":[
  {"subject":"tantárgy vagy esemény neve",
   "day":1,                 // 1=hétfő ... 7=vasárnap
   "start":"08:00",         // 24 órás formátum
   "end":"08:45",
   "room":"terem ha látszik, különben üres",
   "teacher":"tanár ha látszik, különben üres"}
]}
Ha egy óra több napon ismétlődik, minden naphoz külön bejegyzést készíts.
Ha a kép nem órarend, adj vissza üres listát: {"lessons":[]}."""

    /** @return a felismert órák listája (id nélkül, mentésre kész). */
    fun parse(ctx: Context, image: Uri, apiKey: String, model: String): List<Lesson> {
        val bytes = ctx.contentResolver.openInputStream(image)?.use { it.readBytes() }
            ?: error("Nem olvasható a kép")
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mime = ctx.contentResolver.getType(image) ?: "image/jpeg"

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("inlineData", JSONObject()
                        .put("mimeType", mime).put("data", b64)))
                    .put(JSONObject().put("text", PROMPT)))))
            .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))

        val req = Request.Builder()
            .url("$BASE/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = http.newCall(req).execute().use { r ->
            val text = r.body!!.string()
            check(r.isSuccessful) { "Képfelismerés hiba: ${r.code}" }
            JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getString("text")
        }
        return parseLessons(raw)
    }

    private fun parseLessons(raw: String): List<Lesson> {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val arr = JSONObject(cleaned).optJSONArray("lessons") ?: return emptyList()
        val out = mutableListOf<Lesson>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val day = o.optInt("day", 0).takeIf { it in 1..7 } ?: continue
            val start = parseMin(o.optString("start")) ?: continue
            val end = parseMin(o.optString("end")) ?: (start + 45)
            val subject = o.optString("subject").ifBlank { "Óra" }
            out += Lesson(
                subject = subject,
                teacher = o.optString("teacher"),
                room = o.optString("room"),
                dayOfWeek = day,
                startMin = start,
                endMin = if (end > start) end else start + 45
            )
        }
        return out
    }

    private fun parseMin(t: String): Int? {
        val p = t.trim().split(":", ".")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }
}
