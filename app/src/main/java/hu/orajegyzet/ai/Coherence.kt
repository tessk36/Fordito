package hu.orajegyzet.ai

enum class CoherenceVerdict { OK, WARN, REJECT }

/**
 * A Gemma-szöveg és a forrás egyezése (1.1.2).
 *  - ratio: az összefoglaló ≥4 betűs, nem töltelék szavainak hányada (5 betűs tővel), amely
 *    a forrásban vagy az utasításban is szerepel;
 *  - unknownNames / unknownNumbers: a forrásban NEM szereplő nevek és számok – ezek a valódi
 *    kitalálás jelei, ezért külön jelezzük őket.
 */
data class Coherence(val ratio: Double, val unknownNames: List<String>, val unknownNumbers: List<String>) {
    val percent: Int get() = (ratio * 100).toInt()
    val verdict: CoherenceVerdict get() = when {
        ratio < REJECT_RATIO -> CoherenceVerdict.REJECT
        ratio < WARN_RATIO || unknownNames.isNotEmpty() || unknownNumbers.isNotEmpty() -> CoherenceVerdict.WARN
        else -> CoherenceVerdict.OK
    }

    companion object {
        const val REJECT_RATIO = 0.20
        const val WARN_RATIO = 0.35
    }

    fun warning(): String? {
        if (verdict == CoherenceVerdict.OK) return null
        val parts = mutableListOf<String>()
        if (ratio < WARN_RATIO) parts += "Az AI-összefoglaló szóhasználata csak ${percent}%-ban egyezik a leirattal."
        if (unknownNames.isNotEmpty()) parts += "A leiratban nem szereplő név: ${unknownNames.joinToString(", ")}."
        if (unknownNumbers.isNotEmpty()) parts += "A leiratban nem szereplő szám: ${unknownNumbers.joinToString(", ")}."
        parts += "Ellenőrizd a neveket, számokat és dátumokat."
        return parts.joinToString(" ")
    }
}
