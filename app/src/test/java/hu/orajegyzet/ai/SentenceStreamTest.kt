package hu.orajegyzet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fordító 0.2.2: élő feliratok mondatokra bontása. */
class SentenceStreamTest {

    private fun feed(vararg chunks: String): Pair<List<String>, String> {
        val s = SentenceStream()
        val out = mutableListOf<String>()
        chunks.forEach { out += s.append(it) }
        return out to s.partial
    }

    @Test fun splitsStreamedGermanIntoSentences() {
        val (out, partial) = feed("Möchten Sie viel", "leicht einen Ventilator? Oh ja, das wäre",
            " sehr gut. Kein Prob", "lem, Dr. Müller kommt um 3.5 Uhr! Danke")
        assertEquals(listOf("Möchten Sie vielleicht einen Ventilator?", "Oh ja, das wäre sehr gut.",
            "Kein Problem, Dr. Müller kommt um 3.5 Uhr!"), out)
        assertEquals("Danke", partial)
    }

    @Test fun waitsWhenPunctuationIsAtTheEnd() {
        val (out, partial) = feed("Szia, hogy vagy?")
        assertEquals(emptyList<String>(), out)
        assertEquals("Szia, hogy vagy?", partial)
    }

    @Test fun hungarianAbbreviationsAndMonogram() {
        val (out, _) = feed("Ez pl. egy példa, amit A. Kovács írt. Következő ")
        assertEquals(listOf("Ez pl. egy példa, amit A. Kovács írt."), out)
    }

    @Test fun multiplePunctuationIsOneEnd() {
        val (out, partial) = feed("Wirklich?! Ja... Gut ")
        assertEquals(listOf("Wirklich?!", "Ja..."), out)
        assertEquals("Gut ", partial)
    }

    @Test fun flushClosesPartial() {
        val s = SentenceStream()
        s.append("ohne Punkt")
        assertEquals("ohne Punkt", s.flush())
        assertNull(s.flush())
    }

    @Test fun overlongTextIsCutAtComma() {
        val s = SentenceStream(maxChars = 40)
        val out = s.append("eins zwei drei vier fünf sechs sieben, acht neun zehn elf zwölf")
        assertEquals(1, out.size)
        assertEquals("eins zwei drei vier fünf sechs sieben,", out[0])
    }
}
