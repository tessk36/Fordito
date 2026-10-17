package hu.orajegyzet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperChunkTest {

    @Test
    fun testMergeOverlap() {
        val merged1 = OfflinePipeline.mergeOverlap("a b c d e", "d e f g")
        assertEquals("a b c d e f g", merged1)

        val merged2 = OfflinePipeline.mergeOverlap("Szia hogy vagy", "Jó napot")
        assertEquals("Szia hogy vagy Jó napot", merged2)
    }

    @Test
    fun testIsWhisperHallucination() {
        assertTrue(OfflinePipeline.isWhisperHallucination("Köszönöm a figyelmet."))
        assertTrue(OfflinePipeline.isWhisperHallucination("Feliratozta amara.org"))
        assertFalse(OfflinePipeline.isWhisperHallucination("Ez egy normális mondat a megbeszélésről."))
    }
}
