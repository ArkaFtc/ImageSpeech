package com.example.test_project

import com.example.test_project.processing.IntentRouter

import com.example.test_project.processing.IntentRouter.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class IntentRouterTest {

    private fun route(holdMs: Long, hasSpeech: Boolean, transcript: String?) =
        IntentRouter.route(holdMs, hasSpeech, transcript).route

    @Test
    fun `a tap reads verbatim without consulting the audio at all`() {
        assertEquals(Route.READ_VERBATIM, route(holdMs = 120, hasSpeech = true, transcript = "what is this"))
    }

    /**
     * The failure mode duration alone could not catch: a long fumbling press with nothing spoken
     * into it must not reach the model, and the user cannot see that it did.
     */
    @Test
    fun `a long hold with no speech in it still reads verbatim`() {
        assertEquals(Route.READ_VERBATIM, route(holdMs = 1_200, hasSpeech = false, transcript = null))
    }

    /** The other half: a crisp short question must not be discarded for being brief. */
    @Test
    fun `a short spoken question reaches the model`() {
        assertEquals(Route.ASK_MODEL, route(holdMs = 800, hasSpeech = true, transcript = "what colour is this"))
    }

    /** Re-emitting text OCR already has costs minutes of GPU and paraphrases where fidelity matters. */
    @Test
    fun `asking for a page to be read never reaches the model`() {
        val phrasings = listOf(
            "read this",
            "Read it out loud",
            "read the page",
            "please read this to me",
            "what does it say",
            "what does this say?",
            "what's it say",
            "read",
        )
        phrasings.forEach { phrase ->
            assertEquals(
                "\"$phrase\" should have read verbatim",
                Route.READ_VERBATIM,
                route(holdMs = 900, hasSpeech = true, transcript = phrase),
            )
        }
    }

    /** Matching is narrow on purpose: swallowing a real question is worse than a slower read. */
    @Test
    fun `questions that merely mention reading still reach the model`() {
        val questions = listOf(
            "what does the third line say about refunds",
            "read me only the prices",
            "is this safe to read in the dark",
        )
        questions.forEach { question ->
            assertEquals(
                "\"$question\" should have gone to the model",
                Route.ASK_MODEL,
                route(holdMs = 900, hasSpeech = true, transcript = question),
            )
        }
    }

    /**
     * On devices without the file-source recognizer this is the normal path, so a missing
     * transcript must not silently disable the whole feature - trust the VAD instead.
     */
    @Test
    fun `speech with no transcript available still reaches the model`() {
        assertEquals(Route.ASK_MODEL, route(holdMs = 900, hasSpeech = true, transcript = null))
        assertEquals(Route.ASK_MODEL, route(holdMs = 900, hasSpeech = true, transcript = "   "))
    }

    @Test
    fun `the tap threshold is the only place duration decides anything`() {
        val justUnder = IntentRouter.TAP_THRESHOLD_MS - 1
        assertEquals(Route.READ_VERBATIM, route(justUnder, hasSpeech = true, transcript = "what is this"))
        assertEquals(
            Route.ASK_MODEL,
            route(IntentRouter.TAP_THRESHOLD_MS, hasSpeech = true, transcript = "what is this"),
        )
    }
}
