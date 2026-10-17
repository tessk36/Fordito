package hu.orajegyzet.ai

import kotlin.math.sqrt

/**
 * Kivonatos összefoglaló: a leirat VALÓDI, legfontosabb mondatait emeli ki
 * (frekvencia-alapú pontozással). Nem generál szöveget, ezért SOHA nem hallucinál
 * és nem ragad ismétlésbe — a kimenet mindig a leiratban ténylegesen elhangzott.
 */
object Extractive {

    // Gyakori magyar funkciószavak — ezeket nem számítjuk a "fontosság"-ba.
    val STOP = setOf(
        "hogy", "nem", "egy", "csak", "már", "vagy", "mint", "meg", "még", "majd",
        "volt", "lesz", "van", "vannak", "lehet", "kell", "lett", "lenne", "lehetne",
        "azt", "ezt", "ami", "amely", "amelyek", "aki", "akik", "ahol", "amikor",
        "akkor", "ezek", "azok", "ennek", "annak", "ezzel", "azzal", "ezért", "azért",
        "így", "úgy", "ott", "itt", "ide", "oda", "innen", "onnan", "fel", "le",
        "ki", "be", "rá", "el", "át", "is", "ha", "de", "és", "se", "sem", "ne",
        "the", "and", "for", "are", "was", "this", "that", "with", "from",
        "ez", "az", "egyik", "másik", "minden", "semmi", "valami", "néhány", "sok",
        "olyan", "ilyen", "amilyen", "ugyanaz", "tehát", "illetve", "pedig", "hanem",
        "vagyis", "szóval", "persze", "igen", "nincs", "voltak", "leszünk", "legyen"
    )

    private val WORD = Regex("[\\p{L}\\p{Nd}]+")

    fun summarize(transcript: String, maxSentences: Int = 8): String {
        val sentences = splitSentences(transcript)
        if (sentences.size <= maxSentences) return sentences.joinToString(" ").trim()
        val freq = wordFrequencies(transcript)
        val topIdx = sentences.mapIndexed { idx, s -> idx to score(s, freq) }
            .sortedByDescending { it.second }
            .take(maxSentences)
            .map { it.first }
            .toSet()
        // eredeti sorrendben fűzzük össze
        return sentences.filterIndexed { i, _ -> i in topIdx }.joinToString(" ").trim()
    }

    fun bulletNotes(transcript: String, maxBullets: Int = 6): String {
        val sentences = splitSentences(transcript)
        if (sentences.isEmpty()) return ""
        val freq = wordFrequencies(transcript)
        val top = sentences.mapIndexed { idx, s -> idx to s }
            .sortedByDescending { score(it.second, freq) }
            .take(maxBullets)
            .sortedBy { it.first }
        return top.joinToString("\n") { "- ${it.second.trim()}" }
    }

    fun keywords(transcript: String, n: Int = 8): List<String> =
        wordFrequencies(transcript).entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }

    private fun splitSentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length >= 15 }

    private fun wordFrequencies(text: String): Map<String, Int> {
        val freq = HashMap<String, Int>()
        WORD.findAll(text).forEach {
            val w = it.value.lowercase()
            if (w.length >= 4 && w !in STOP) freq[w] = (freq[w] ?: 0) + 1
        }
        return freq
    }

    private fun score(sentence: String, freq: Map<String, Int>): Double {
        val words = WORD.findAll(sentence).map { it.value.lowercase() }.toList()
        if (words.isEmpty()) return 0.0
        val sum = words.sumOf { (freq[it] ?: 0).toDouble() }
        // sqrt-tel normalizálunk, hogy ne csak a leghosszabb mondat nyerjen
        return sum / sqrt(words.size.toDouble())
    }
}
