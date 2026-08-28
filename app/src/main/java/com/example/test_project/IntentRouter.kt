package com.example.test_project

/**
 * Decides which of the three lanes a press belongs to.
 *
 * Two of the three never wake the model, and they are the common cases - that is where the thermal
 * budget is actually won, not in generation settings.
 */
object IntentRouter {

    enum class Route {
        /** OCR text straight to the voice, word for word. No model, no GPU, ~200 ms to first word. */
        READ_VERBATIM,

        /** The model answers, given the image, the OCR block, the clip and the transcript. */
        ASK_MODEL,
    }

    data class Decision(val route: Route, val because: String)

    /** A press shorter than this is a tap: the deliberate "just read it" gesture. */
    const val TAP_THRESHOLD_MS = 400L

    /**
     * Asking for a page to be read must not reach the model. Re-emitting text the OCR pass already
     * has costs minutes of GPU and paraphrases exactly where fidelity is wanted.
     *
     * Matching is deliberately narrow. A miss falls through to the model, which can also read a
     * page - slower and less verbatim, but not wrong - so the cost of being too strict here is far
     * lower than the cost of swallowing a real question.
     */
    private val READ_INTENT = listOf(
        // Both the object and the manner are optional, so a bare "read" matches while "read me
        // only the prices" does not - the trailing words have to be accounted for to the end.
        Regex("""^(please\s+)?read(\s+(this|it|that|the\s+(page|text|screen|sign|label)))?(\s+(out\s+loud|aloud|to\s+me|again))?[.?!]*$"""),
        Regex("""^what\s+does\s+(this|it|that)\s+say[.?!]*$"""),
        Regex("""^what'?s\s+(it|this|that)\s+say[.?!]*$"""),
        Regex("""^(read|say)\s+(it|this)\s+(out\s+loud|aloud|again)[.?!]*$"""),
    )

    /**
     * @param holdMs how long the button was down
     * @param hasSpeech whether the clip's VAD found voiced audio
     * @param transcript the recognizer's best guess, or null when recognition was unavailable
     */
    fun route(holdMs: Long, hasSpeech: Boolean, transcript: String?): Decision {
        if (holdMs < TAP_THRESHOLD_MS) {
            return Decision(Route.READ_VERBATIM, "tap")
        }
        if (!hasSpeech) {
            return Decision(Route.READ_VERBATIM, "held, but no speech detected")
        }

        val spoken = transcript?.trim()?.lowercase()
        if (spoken.isNullOrEmpty()) {
            // VAD heard something the recognizer could not resolve. On devices where recognition is
            // unavailable this is the normal path, so trust the VAD and let the model hear it.
            return Decision(Route.ASK_MODEL, "speech detected, no transcript")
        }
        if (READ_INTENT.any { it.matches(spoken) }) {
            return Decision(Route.READ_VERBATIM, "read intent: \"$spoken\"")
        }
        return Decision(Route.ASK_MODEL, "question: \"$spoken\"")
    }
}
