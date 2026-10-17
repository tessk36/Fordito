package hu.orajegyzet.ai

/**
 * Folyamatosan érkező szövegdarabokból kész mondatokat vág le (Fordító 0.2.2, élő tolmács).
 * Mondatvég: . ! ? … (és a CJK 。！？), utána szóköz vagy sorvég. A rövidítéseket
 * (pl. „Dr.”, „z.B.”, „u.a.”) és a számokat („3.5”) nem tekinti mondatvégnek.
 * A még nem lezárt rész a [partial]; szünetnél vagy fordulóvégnél a [flush] zárja le.
 * Tiszta Kotlin (unit teszt).
 */
class SentenceStream(
    /** Ilyen hosszú mondatvég nélküli szöveget vesszőnél / szóköznél kényszerből lezár. */
    private val maxChars: Int = 220
) {
    var partial: String = ""
        private set

    /** Új darab hozzáadása; a lezárt mondatokat adja vissza (lehet üres). */
    fun append(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        partial += chunk
        val out = mutableListOf<String>()
        while (true) {
            val cut = findCut(partial) ?: break
            val sentence = partial.substring(0, cut).trim()
            partial = partial.substring(cut).trimStart()
            if (sentence.isNotEmpty()) out += sentence
        }
        if (partial.length > maxChars) {
            val p = partial.lastIndexOf(", ", maxChars).takeIf { it > maxChars / 2 }?.plus(1)
                ?: partial.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 }
                ?: maxChars
            out += partial.substring(0, p).trim()
            partial = partial.substring(p).trimStart()
        }
        return out
    }

    /** A nyitott rész lezárása (szünet, fordulóvég, leállítás). */
    fun flush(): String? {
        val s = partial.trim()
        partial = ""
        return s.ifEmpty { null }
    }

    private fun findCut(text: String): Int? {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in CJK_END) return i + 1
            if (c in END) {
                // több írásjel egymás után („?!”, „...”) egy mondatvég
                var j = i + 1
                while (j < text.length && (text[j] in END || text[j] == '"' || text[j] == '\'' ||
                        text[j] == '”' || text[j] == '»' || text[j] == ')')) j++
                if (j >= text.length) return null            // a vége még jöhet: várunk
                if (!text[j].isWhitespace()) { i = j; continue }  // pl. 3.5 vagy z.B.
                if (c == '.' && isAbbreviation(text, i)) { i = j; continue }
                return j
            }
            i++
        }
        return null
    }

    private fun isAbbreviation(text: String, dot: Int): Boolean {
        var s = dot - 1
        while (s >= 0 && text[s].isLetter()) s--
        val word = text.substring(s + 1, dot)
        if (word.length == 1 && word[0].isUpperCase()) return true     // monogram: „A. Kovács”
        return word.lowercase() in ABBREVIATIONS
    }

    companion object {
        private val END = setOf('.', '!', '?', '…')
        private val CJK_END = setOf('。', '！', '？')
        private val ABBREVIATIONS = setOf(
            "dr", "mr", "mrs", "ms", "prof", "st", "nr", "bzw", "ca", "vgl", "usw", "etc",
            "pl", "kb", "stb", "ill", "ún", "vö", "u", "z", "b", "d"
        )
    }
}
