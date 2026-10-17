package hu.orajegyzet.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaLogicTest {

    @Test
    fun testHasLoopAndRemoveLoopSentences() {
        val loopText = "A rendszer egyenletes és hatékony működésre törekedik. A rendszer egyenletes és hatékony működésre törekedik. A rendszer egyenletes és hatékony működésre törekedik."
        assertTrue(OfflinePipeline.hasLoop(loopText))

        val cleaned = OfflinePipeline.removeLoopSentences(loopText)
        assertEquals("A rendszer egyenletes és hatékony működésre törekedik.", cleaned)
    }

    @Test
    fun testVerifySourceCoherenceRejectsHallucinations() {
        val source = "A megbeszélésen a projekt költségvetését és a jövő heti határidőket tárgyaltuk meg. Péter vállalta a tesztelést."
        val hallucination = "A csillagászat szerint a Mars bolygó atmoszférája nitrogénből és szén-dioxidból áll."

        assertFalse(OfflinePipeline.verifySourceCoherence(hallucination, source))
    }

    @Test
    fun testVerifySourceCoherenceAcceptsValidSummary() {
        val source = "A megbeszélésen a projekt költségvetését és a jövő heti határidőket tárgyaltuk meg. Péter vállalta a tesztelést."
        val validSummary = "A megbeszélésen a projekt költségvetése és a határidők kerültek megtárgyalásra. Péter végzi a tesztelést."

        assertTrue(OfflinePipeline.verifySourceCoherence(validSummary, source))
    }
}
