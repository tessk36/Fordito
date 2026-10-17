package hu.orajegyzet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fordító 0.2: beszédszakaszoló és a Gemini Live üzenetek értelmezése. */
class LiveTest {

    private fun tone(amplitude: Int): ByteArray {
        val b = ByteArray(3200)
        for (i in 0 until 1600) {
            val v = if (i % 2 == 0) amplitude else -amplitude
            b[2 * i] = (v and 0xff).toByte(); b[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return b
    }
    private val silence = ByteArray(3200)

    @Test fun rmsOfSquareWaveIsAmplitude() {
        assertEquals(1000.0, SpeechSegmenter.rms(tone(1000)), 0.5)
        assertEquals(0.0, SpeechSegmenter.rms(silence), 0.0)
    }

    @Test fun segmentClosesAfterSilenceAndKeepsPreRoll() {
        val out = mutableListOf<ByteArray>()
        val seg = SpeechSegmenter(silenceFrames = 3, onSegment = { out += it })
        repeat(5) { seg.push(silence) }
        repeat(6) { seg.push(tone(3000)) }
        repeat(3) { seg.push(silence) }
        assertEquals(1, out.size)
        // 1 előtag + 6 beszéd + 3 csend = 10 darab
        assertEquals(10 * 3200, out[0].size)
    }

    @Test fun shortClickIsDropped() {
        val out = mutableListOf<ByteArray>()
        val seg = SpeechSegmenter(silenceFrames = 3, minSpeechFrames = 4, onSegment = { out += it })
        seg.push(tone(3000)); seg.push(tone(3000))
        repeat(3) { seg.push(silence) }
        assertTrue(out.isEmpty())
    }

    @Test fun longSpeechIsCutAtMax() {
        val out = mutableListOf<ByteArray>()
        val seg = SpeechSegmenter(maxFrames = 20, onSegment = { out += it })
        repeat(45) { seg.push(tone(3000)) }
        seg.flush()
        assertEquals(3, out.size)
    }

    @Test fun parsesSetupComplete() {
        val m = LiveTranslateClient.parseServerMessage("""{"setupComplete":{}}""")
        assertTrue(m.setupComplete)
        assertNull(m.sourceText)
    }

    @Test fun parsesTranscriptions() {
        val m = LiveTranslateClient.parseServerMessage(
            """{"serverContent":{"inputTranscription":{"text":"Hello ","languageCode":"en"},
               "outputTranscription":{"text":"Szia "},"turnComplete":true}}""")
        assertEquals("Hello ", m.sourceText)
        assertEquals("en", m.sourceLanguage)
        assertEquals("Szia ", m.translatedText)
        assertTrue(m.turnComplete)
        assertFalse(m.setupComplete)
    }

    @Test fun parsesErrorAndGarbage() {
        val e = LiveTranslateClient.parseServerMessage("""{"error":{"message":"bad model"}}""")
        assertTrue(e.error!!.contains("bad model"))
        val g = LiveTranslateClient.parseServerMessage("nem json")
        assertFalse(g.setupComplete)
        assertNull(g.error)
    }

    @Test fun closeMessageIsReadable() {
        assertTrue(LiveTranslateClient.closeMessage(1008, "API key not valid").contains("API-kulcs"))
        assertTrue(LiveTranslateClient.closeMessage(1011, "").contains("1011"))
    }

    @Test fun setupLayoutsPlaceFieldsDifferently() {
        val l0 = LiveTranslateClient.setupMessage("gemini-3.5-live-translate-preview", "hu", 0).getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", l0.getString("model"))
        assertTrue(l0.has("inputAudioTranscription"))
        assertEquals("hu", l0.getJSONObject("generationConfig").getJSONObject("translationConfig").getString("targetLanguageCode"))
        val l1 = LiveTranslateClient.setupMessage("m", "hu", 1).getJSONObject("setup")
        assertTrue(l1.has("translationConfig"))
        assertFalse(l1.getJSONObject("generationConfig").has("translationConfig"))
        val l2 = LiveTranslateClient.setupMessage("m", "hu", 2).getJSONObject("setup")
        assertFalse(l2.has("inputAudioTranscription"))
        assertTrue(l2.getJSONObject("generationConfig").has("inputAudioTranscription"))
    }
}
