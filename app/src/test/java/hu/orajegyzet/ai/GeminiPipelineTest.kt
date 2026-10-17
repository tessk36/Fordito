package hu.orajegyzet.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

class GeminiPipelineTest {

    private lateinit var server: MockWebServer
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempFile = File.createTempFile("test_audio", ".m4a").apply {
            writeBytes("fake audio content".toByteArray())
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempFile.delete()
    }

    @Test
    fun testHttp403DoesNotTriggerUpload() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val pipeline = GeminiPipeline(apiKey = "test_key", baseUrl = server.url("").toString().removeSuffix("/"))

        try {
            runBlocking {
                pipeline.process(tempFile.absolutePath, "Subject", "2026-01-01", false)
            }
            fail("Expected GeminiHttpException")
        } catch (e: GeminiHttpException) {
            assertEquals(403, e.code)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun testHttp429DoesNotTriggerUpload() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Quota exceeded"))

        val pipeline = GeminiPipeline(apiKey = "test_key", baseUrl = server.url("").toString().removeSuffix("/"))

        try {
            runBlocking {
                pipeline.process(tempFile.absolutePath, "Subject", "2026-01-01", false)
            }
            fail("Expected GeminiHttpException")
        } catch (e: GeminiHttpException) {
            assertEquals(429, e.code)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun testHttp400InvalidKeyDoesNotTriggerUpload() {
        val errorJson = """{"error": {"code": 400, "message": "API key not valid. Please pass a valid API key.", "status": "INVALID_ARGUMENT", "details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_INVALID"}]}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorJson))

        val pipeline = GeminiPipeline(apiKey = "invalid_key", baseUrl = server.url("").toString().removeSuffix("/"))

        try {
            runBlocking {
                pipeline.process(tempFile.absolutePath, "Subject", "2026-01-01", false)
            }
            fail("Expected GeminiHttpException")
        } catch (e: GeminiHttpException) {
            assertEquals(400, e.code)
            assertEquals(true, e.isInvalidKey)
            assertEquals("Gemini API-kulcs / engedély hiba (400). Ellenőrizd az API-kulcsot a Beállításokban!", e.message)
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun testHttp413TriggersUpload() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("Payload Too Large"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("Upload failed"))

        val pipeline = GeminiPipeline(apiKey = "test_key", baseUrl = server.url("").toString().removeSuffix("/"))

        try {
            runBlocking {
                pipeline.process(tempFile.absolutePath, "Subject", "2026-01-01", false)
            }
            fail("Expected GeminiHttpException on upload attempt")
        } catch (e: GeminiHttpException) {
            assertEquals(400, e.code)
        }

        assertEquals(2, server.requestCount)
    }
}
