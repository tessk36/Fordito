package hu.orajegyzet.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractiveTest {

    @Test
    fun testExtractiveSummaryDoesNotContainPrompt() {
        val transcript = "Egy kettő három. Négy öt hat. A megbeszélés sikeresen lezárult. Határozat született a költségvetés elfogadásáról."
        val result = Extractive.summarize(transcript, 6)

        assertFalse(result.contains("Készíts"))
        assertTrue(result.contains("költségvetés"))
    }
}
