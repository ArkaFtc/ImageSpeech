package com.example.test_project

import com.example.test_project.settings.GeminiQrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiQrPayloadTest {
    @Test fun fakeCredentialRoundTripsAndIsTrimmedForSharing() {
        val encoded = GeminiQrPayload.encode("  fake-test-credential-with-no-provider-prefix  ")

        assertEquals(
            GeminiQrPayload.DecodeResult.Success("fake-test-credential-with-no-provider-prefix"),
            GeminiQrPayload.decode(encoded),
        )
    }

    @Test fun malformedAndUnrelatedPayloadsAreRejected() {
        assertEquals(GeminiQrPayload.DecodeResult.Malformed, GeminiQrPayload.decode("not json"))
        assertEquals(
            GeminiQrPayload.DecodeResult.Malformed,
            GeminiQrPayload.decode("""{"app":"ImageSpeech","service":"Gemini","version":1}"""),
        )
        assertEquals(
            GeminiQrPayload.DecodeResult.Unsupported,
            GeminiQrPayload.decode("""{"app":"AnotherApp","service":"Gemini","version":1,"key":"fake"}"""),
        )
        assertEquals(
            GeminiQrPayload.DecodeResult.Malformed,
            GeminiQrPayload.decode("""{"app":"ImageSpeech","service":"Gemini","version":"1","key":"fake"}"""),
        )
    }

    @Test fun unsupportedVersionIsReportedWithoutExposingTheKey() {
        assertEquals(
            GeminiQrPayload.DecodeResult.UnsupportedVersion(2),
            GeminiQrPayload.decode(
                """{"app":"ImageSpeech","service":"Gemini","version":2,"key":"fake-version-test"}""",
            ),
        )
    }

    @Test fun oversizedPayloadIsRejectedBeforeParsing() {
        val oversized = "x".repeat(GeminiQrPayload.MAX_PAYLOAD_BYTES + 1)
        assertTrue(GeminiQrPayload.decode(oversized) is GeminiQrPayload.DecodeResult.Malformed)
    }
}
