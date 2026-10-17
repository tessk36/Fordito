package hu.orajegyzet.ai

/**
 * A Gemini-modellek listája (1.1.6). Az app a felhasználó kulcsával lekéri az elérhető modelleket
 * (ListModels), ezekből a szöveg-/hangelemzésre alkalmasakat mutatja. Hálózat nélkül a beépített lista él.
 */
object GeminiModels {

    data class Entry(val id: String, val displayName: String = "", val methods: List<String> = emptyList())

    /** Beépített tartalék lista (2026. október), ha a lekérés még nem futott vagy nem sikerült. */
    val FALLBACK = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-3.1-pro-preview"
    )

    /** Nem jegyzetkészítésre való modellek (hang-kimenet, kép, élő, beágyazás, robotika stb.). */
    private val EXCLUDE = listOf(
        "tts", "image", "live", "embedding", "robotics", "transcribe", "native-audio",
        "computer-use", "aqa", "banana", "veo", "imagen", "lyria", "deep-research", "antigravity"
    )

    fun isUsable(e: Entry): Boolean {
        val id = e.id.lowercase()
        return id.startsWith("gemini-") &&
            (e.methods.isEmpty() || "generateContent" in e.methods) &&
            EXCLUDE.none { it in id }
    }

    /** Rendezés: verzió szerint csökkenő, azon belül flash-lite, flash, pro; a „latest” álnevek a végén. */
    fun sort(ids: List<String>): List<String> = ids.distinct().sortedWith(
        compareBy<String> { if ("latest" in it) 1 else 0 }
            .thenByDescending { version(it) }
            .thenBy { tier(it) }
            .thenBy { it }
    )

    private fun version(id: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun tier(id: String): Int = when {
        "flash-lite" in id -> 0
        "flash" in id -> 1
        "pro" in id -> 2
        else -> 3
    }

    /** A lekért lista szűrve és rendezve. */
    fun usable(entries: List<Entry>): List<String> = sort(entries.filter(::isUsable).map { it.id })

    /** A választóban megjelenő lista: a lekért (vagy tartalék) modellek + a jelenleg kiválasztott. */
    fun forPicker(cached: List<String>, selected: String): List<String> {
        val base = cached.ifEmpty { FALLBACK }
        return sort(if (selected.isNotBlank() && selected !in base) base + selected else base)
    }

    fun encode(ids: List<String>): String = ids.joinToString(",")
    fun decode(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Rövid megjegyzés a modell mellé. */
    fun hint(id: String): String = when {
        "flash-lite" in id -> "gyors, olcsó"
        "pro" in id -> "legerősebb, lassabb, fizetős lehet"
        "latest" in id -> "mindig a legújabb Flash"
        "flash" in id -> "kiegyensúlyozott"
        else -> ""
    }
}
