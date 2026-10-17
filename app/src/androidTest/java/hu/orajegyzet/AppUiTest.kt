package hu.orajegyzet

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import hu.orajegyzet.ai.GeminiPipeline
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUiTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testWelcomeScreenAndNavigationToToday() {
        // Welcome Screen displays title and enter button
        composeTestRule.onNodeWithText("FÜLELŐ").assertExists()
        composeTestRule.onNodeWithText("Belépés az Alkalmazásba ➔").assertExists().performClick()

        // Navigates to Today Screen
        composeTestRule.onNodeWithText("Mai órák & Projektek").assertExists()
    }

    @Test
    fun testNavigationToAllMainScreens() {
        // Enter app
        composeTestRule.onNodeWithText("Belépés az Alkalmazásba ➔").performClick()

        // Open Project Assistant
        composeTestRule.onNodeWithText("Projekt AI").performClick()
        composeTestRule.onNodeWithText("Projekt Asszisztens").assertExists()
        composeTestRule.onNodeWithContentDescription("Vissza").performClick()

        // Open History
        composeTestRule.onNodeWithText("Korábbi").performClick()
        composeTestRule.onNodeWithText("Előzmények (Témák & Projektek)").assertExists()
        composeTestRule.onNodeWithContentDescription("Vissza").performClick()

        // Open Schedule
        composeTestRule.onNodeWithText("Órarend").performClick()
        composeTestRule.onNodeWithText("Órarend").assertExists()
        composeTestRule.onNodeWithContentDescription("Vissza").performClick()

        // Open Settings
        composeTestRule.onNodeWithText("Beállítások").performClick()
        composeTestRule.onNodeWithText("Beállítások").assertExists()
    }

    @Test
    fun testSettingsApiKeyAndModelsDisplay() {
        composeTestRule.onNodeWithText("Belépés az Alkalmazásba ➔").performClick()
        composeTestRule.onNodeWithText("Beállítások").performClick()

        // Verify Supported Gemini Models exist in Settings UI
        GeminiPipeline.SUPPORTED_MODELS.forEach { model ->
            composeTestRule.onNodeWithText(model).assertExists()
        }

        // Fill API key field
        composeTestRule.onNodeWithText("Gemini API-kulcs").performTextInput("AIza-teszt")
        composeTestRule.onNodeWithText("Mentés").performClick()

        // Verify "nem olvasható" error does not appear after valid save
        composeTestRule.onNodeWithText("⚠️ A tárolt API-kulcs nem olvasható, kérlek add meg újra.").assertDoesNotExist()
    }
}
