package hu.orajegyzet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.orajegyzet.data.SecretCipher
import hu.orajegyzet.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsMigrationTest {

    @Test
    fun testSecretCipherEncryptAndDecrypt() {
        val plainText = "test_gemini_api_key_12345"
        val encrypted = SecretCipher.encrypt(plainText)

        assertTrue(encrypted.startsWith("enc1:"))

        val decrypted = SecretCipher.decrypt(encrypted)
        assertEquals(plainText, decrypted)
    }

    @Test
    fun testSettingsMigrationAndGet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialSettings = Settings.get(context)

        val testKey = "AIzaSyTestKeyForMigrationCheck123"
        Settings.save(context, initialSettings.copy(geminiApiKey = testKey))

        Settings.migrateSecretsIfNeeded(context)

        val retrieved = Settings.get(context)
        assertEquals(testKey, retrieved.geminiApiKey)
    }
}
