package com.example.test_project.processing.model

import android.graphics.Bitmap
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The model lane, split into the two halves of the prompt contract. */
interface SceneAnswerer {

    /** False when the model is missing, or the device cannot run it at usable speed. */
    val isAvailable: Boolean

    /**
     * Opens a turn and starts encoding [image] and [ocr]. Suspends until the prefill is under way.
     * Returns null when the model could not be brought up; the caller falls back to reading.
     */
    suspend fun beginTurn(image: Bitmap, ocr: LocalPPOCRv6Runner.OcrResult): SceneTurn?

    fun close()
}

/** One opened turn, holding a prefilled context. */
interface SceneTurn : AutoCloseable {

    /**
     * Streams the answer. The collector is expected to be slow because it is feeding a voice, and
     * that backpressure is load-bearing rather than incidental - see the processor audio stream. Cancelling the
     * collection must stop generation rather than merely stop listening to it.
     */
    fun ask(audio: RecordedAudio?, transcript: String?): Flow<String>
}

/** Stands in when the model is absent, and remains the permanent fallback on devices that cannot run */
class UnavailableSceneAnswerer(private val reason: String) : SceneAnswerer {

    override val isAvailable = false

    override suspend fun beginTurn(
        image: Bitmap,
        ocr: LocalPPOCRv6Runner.OcrResult,
    ): SceneTurn = object : SceneTurn {
        override fun ask(audio: RecordedAudio?, transcript: String?): Flow<String> =
            flow { emit(reason) }

        override fun close() = Unit
    }

    override fun close() = Unit
}

/**
 * Builds the text half of the prompt.
 *
 * Split out from the runtime so it can be read, reviewed and tested without a 1.5 GB model present.
 */
object ScenePrompt {

    const val SYSTEM = """You answer questions about what the camera sees, for a listener who cannot see the screen.
Answer in plain spoken sentences. Be specific and brief unless asked for detail.
Prefer the OCR block for anything quoted verbatim."""

    /**
     * The OCR block is labelled and explicitly trusted. Without that instruction a vision model will
     * cheerfully "correct" a specialised detector that was right - PP-OCRv6 is smaller than the
     * model but far better at this one job.
     */
    fun ocrBlock(result: LocalPPOCRv6Runner.OcrResult): String {
        if (!result.hasText) {
            return "OCR: no text detected in this frame."
        }
        val body = result.regions.joinToString("\n") { region ->
            "  [${region.left},${region.top},${region.right},${region.bottom}] ${region.text}"
        }
        return buildString {
            appendLine("OCR (PP-OCRv6, reading order, [left,top,right,bottom]):")
            appendLine(body)
            append(
                "This OCR is more accurate than your own reading of the image. " +
                    "Treat it as ground truth for exact text; use the coordinates to reason about " +
                    "columns, tables and layout."
            )
        }
    }

    /** The question turn, assembled after the button is released. */
    fun question(transcript: String?, hasAudio: Boolean): String = when {
        !transcript.isNullOrBlank() -> transcript
        hasAudio -> "Answer the question in the attached audio."
        else -> "Describe what you can see."
    }
}
