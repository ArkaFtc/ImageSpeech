package com.example.test_project

import com.example.test_project.settings.GeminiKeyChangeFlow
import com.example.test_project.settings.GeminiQrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiKeyChangeFlowTest {
    @Test fun importedKeyUsesTheSameTrimmedSaveAndCancellationFlow() {
        var stored = "old-fake-credential"
        val calls = mutableListOf<String>()
        val flow = GeminiKeyChangeFlow(
            store = { candidate ->
                val normalized = candidate.trim()
                if (normalized == stored) false else {
                    stored = normalized
                    calls += "store"
                    true
                }
            },
            cancelUi = { calls += "cancelUi" },
            clearConversation = { calls += "clearConversation" },
            cancelProcessing = { calls += "cancelProcessing" },
        )
        val payload = GeminiQrPayload.encode("new-fake-credential")
        val scanned = GeminiQrPayload.decode(payload) as GeminiQrPayload.DecodeResult.Success

        assertTrue(flow.apply("  ${scanned.key}  "))
        assertEquals("new-fake-credential", stored)
        assertEquals(
            listOf("store", "cancelUi", "clearConversation", "cancelProcessing"),
            calls,
        )
    }

    @Test fun reviewingAnUnchangedKeyDoesNotCancelActiveWork() {
        val calls = mutableListOf<String>()
        val flow = GeminiKeyChangeFlow(
            store = { false },
            cancelUi = { calls += "cancelUi" },
            clearConversation = { calls += "clearConversation" },
            cancelProcessing = { calls += "cancelProcessing" },
        )

        assertFalse(flow.apply("same-fake-credential"))
        assertTrue(calls.isEmpty())
    }

    @Test fun clearingAStoredKeyUsesTheSameCancellationFlow() {
        var stored = "old-fake-credential"
        var cancellations = 0
        val flow = GeminiKeyChangeFlow(
            store = { candidate ->
                val changed = candidate.trim() != stored
                stored = candidate.trim()
                changed
            },
            cancelUi = { cancellations++ },
            clearConversation = { cancellations++ },
            cancelProcessing = { cancellations++ },
        )

        assertTrue(flow.apply("  "))
        assertEquals("", stored)
        assertEquals(3, cancellations)
    }
}
