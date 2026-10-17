package hu.orajegyzet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fordító 0.1: a modellkimenet értelmezése, promptok, átmintavételezés. */
class TranslatorTest {

    @Test fun parsesLanguageLineAndSeparator() {
        val (lang, text) = Translator.parseOutput("NYELV: angol\n---\nSzia, hogy vagy?")
        assertEquals("angol", lang)
        assertEquals("Szia, hogy vagy?", text)
    }

    @Test fun toleratesBoldBlankLinesAndCodeFence() {
        val (lang, text) = Translator.parseOutput("```\n\n**Nyelv:** Német.\n\n---\n\nJó reggelt!\nMásodik sor.\n```")
        assertEquals("német", lang)
        assertEquals("Jó reggelt!\nMásodik sor.", text)
    }

    @Test fun withoutLanguageLineKeepsWholeText() {
        val (lang, text) = Translator.parseOutput("Ez csak a fordítás.")
        assertEquals("", lang)
        assertEquals("Ez csak a fordítás.", text)
    }

    @Test fun stripsPreamble() {
        val (_, text) = Translator.parseOutput("NYELV: francia\n---\nÍme a fordítás:\nJó napot kívánok.")
        assertEquals("Jó napot kívánok.", text)
    }

    @Test fun promptAsksLanguageOnlyForFirstBlock() {
        assertTrue(Translator.textPrompt(TrStyle.NATURAL, "Hello", askLanguage = true).contains("NYELV:"))
        assertFalse(Translator.textPrompt(TrStyle.NATURAL, "Hello", askLanguage = false).contains("NYELV: <"))
        assertTrue(Translator.textPrompt(TrStyle.FORMAL, "Hello", true).contains(TrStyle.FORMAL.instruction))
    }

    @Test fun everyStyleHasDistinctInstructionAndLabel() {
        val all = TrStyle.entries
        assertEquals(all.size, all.map { it.instruction }.toSet().size)
        assertEquals(all.size, all.map { it.label }.toSet().size)
        assertEquals(TrStyle.NATURAL, TrStyle.of("nincs ilyen"))
        assertEquals(TrStyle.SUMMARY, TrStyle.of("SUMMARY"))
    }

    @Test fun audioJsonIsParsed() {
        val r = Translator.parseAudioJson("""{"language":"Angol","transcript":"Hello there","translation":"Szia"}""")
        assertNotNull(r)
        assertEquals("angol", r!!.first.first)
        assertEquals("Hello there", r.first.third)
        assertEquals("Szia", r.second)
        assertNull(Translator.parseAudioJson("nem json"))
    }

    @Test fun glossaryPromptHasNoLanguageHint() {
        val p = Translator.glossaryPrompt("Kovács Anna, ISO 27001; Kovács Anna")
        assertEquals("Kovács Anna, ISO 27001", p)
        assertFalse(p.contains("Magyar"))
    }

    // ---------- átmintavételezés (importált hangfájlok) ----------

    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = ((v shr 8) and 0xff).toByte() }
        return b
    }

    private fun samples(b: ByteArray): List<Int> =
        (0 until b.size / 2).map { ((b[2 * it].toInt() and 0xff) or (b[2 * it + 1].toInt() shl 8)).toShort().toInt() }

    @Test fun monoSixteenKhzIsPassthrough() {
        val c = Pcm16kConverter(16_000, 1)
        val inBytes = pcm(1, -2, 3)
        assertEquals(samples(inBytes), samples(c.convert(inBytes, inBytes.size)))
    }

    @Test fun stereoIsAveragedToMono() {
        val c = Pcm16kConverter(16_000, 2)
        val out = samples(c.convert(pcm(100, 300, -100, -300), 8))
        // 16 kHz-en a minták száma a keretek száma; az utolsó a következő darabbal készül el
        assertEquals(200, out.first())
    }

    @Test fun downsamplingKeepsRateAcrossChunks() {
        val c = Pcm16kConverter(48_000, 1)
        var total = 0
        repeat(48) { k ->
            val chunk = pcm(*IntArray(1000) { (k * 1000 + it) % 1000 })
            total += c.convert(chunk, chunk.size).size / 2
        }
        // 48 000 bemeneti minta → kb. 16 000 kimeneti (±2 a darabhatárok miatt)
        assertTrue("got $total", total in 15_998..16_000)
    }

    @Test fun oddByteCountIsCarriedOver() {
        val c = Pcm16kConverter(32_000, 1)
        val b = pcm(10, 20, 30, 40)
        val first = c.convert(b.copyOf(3), 3)   // másfél minta
        val second = c.convert(b.copyOfRange(3, 8), 5)
        // 32 kHz → minden második minta; a fél minta a következő darabbal együtt kerül feldolgozásra
        assertEquals(listOf(10, 30), samples(first + second))
    }
}
