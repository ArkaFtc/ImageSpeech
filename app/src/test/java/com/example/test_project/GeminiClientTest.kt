package com.example.test_project

import com.example.test_project.remote.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun streamsOnlyUserFacingTextAndUsesDocumentedHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hidden\",\"thought\":true},{\"text\":\"Hello \"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"wörld.\"}]},\"finishReason\":\"STOP\"}]}\n\n")
            .throttleBody(3, 1, TimeUnit.MILLISECONDS))
        val deltas = mutableListOf<String>()
        val result = client().stream("secret-test-key", sampleRequest(), deltas::add)
        assertEquals(GeminiResult.Complete("Hello wörld.", "STOP"), result)
        assertEquals(listOf("Hello ", "wörld."), deltas)
        val recorded = server.takeRequest()
        assertEquals("secret-test-key", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.path!!.contains("streamGenerateContent?alt=sse"))
        val json = recorded.body.readUtf8()
        assertTrue(json.contains("\"thinkingLevel\":\"low\""))
        assertFalse(json.contains("secret-test-key"))
    }

    @Test fun quotaAndInterruptedStreamsAreExplicit() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429)
            .setBody("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"limit\"}}"))
        val quota = client().stream("key", sampleRequest()) {}
        assertTrue((quota as GeminiResult.Failed).error.quota)

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Partial\"}]}}]}\n\n"))
        val interrupted = client().stream("key", sampleRequest()) {}
        assertEquals("Partial", (interrupted as GeminiResult.Failed).partialText)
        assertTrue(interrupted.error.message.contains("before the answer was complete"))
    }

    @Test fun invalidKeyAndTruncationAreUnderstandable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val denied = client().stream("test-key-not-real", sampleRequest()) {} as GeminiResult.Failed
        assertTrue(denied.error.message.contains("invalid"))

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Long answer\"}]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n"))
        val truncated = client().stream("test-key-not-real", sampleRequest()) {} as GeminiResult.Failed
        assertEquals("Long answer", truncated.partialText)
        assertTrue(truncated.error.message.contains("output limit"))
    }

    @Test fun cancellationStopsAStreamingCall() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"late\"}]},\"finishReason\":\"STOP\"}]}\n\n")
            .setBodyDelay(5, TimeUnit.SECONDS))
        try {
            withTimeout(150) { client().stream("test-key-not-real", sampleRequest()) {} }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Cancellation is the contract: no converted network failure and no late result.
        }
    }

    private fun client() = GeminiClient(baseUrl = server.url("v1beta/").toString())
    private fun sampleRequest() = GeminiRequest(
        GeminiContent(parts = listOf(GeminiPart(text = GeminiConfig.SYSTEM_PROMPT))),
        listOf(GeminiContent("user", listOf(GeminiPart(text = "Read")))),
        GenerationConfig(),
    )
}
