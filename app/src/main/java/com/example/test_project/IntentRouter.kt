package com.example.test_project

/**
 * Decides which of the three lanes a press belongs to.
 */
object IntentRouter {

    enum class Route {
        /** OCR text straight to the voice, word for word. No model, no GPU */
        READ_VERBATIM,

        /** The model answers, given the image, the OCR block, the clip and the transcript. */
        ASK_MODEL,
    }

    data class Decision(val route: Route, val because: String)

    const val TAP_THRESHOLD_MS = 400L


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

        return Decision(Route.ASK_MODEL, "question: \"$spoken\"")
    }
}
