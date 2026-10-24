package com.example.test_project.contract

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

enum class ReadingMode {
    TEXT,
    DOCUMENT,
    BOOK_SPREAD,
}

data class ProcessingRequest(
    val image: Bitmap,
    val additionalImages: List<Bitmap> = emptyList(),
    val audio: RecordedAudio? = null,
    val mode: ReadingMode = ReadingMode.DOCUMENT,
    val action: ProcessingAction = ProcessingAction.READ,
    val question: String? = null,
)

enum class ProcessingAction {
    /** Get the photo ready (process it, find its text) without reading anything aloud. */
    PREPARE,
    READ,
    READ_ALL,
    SUMMARIZE,
    ASK,
}

data class SpokenAudio(val text: String, val wav: ByteArray, val readingStartedAtMs: Long? = null)

/** One collection owns recognition, speech and cancellation for a reading session. */
sealed interface ReadingEvent {
    data class Status(val text: String) : ReadingEvent

    data class Block(val block: TextBlock) : ReadingEvent

    /** Model text has no honest camera-space geometry, so it is streamed separately. */
    data class TextDelta(val text: String) : ReadingEvent

    data class Audio(val audio: SpokenAudio) : ReadingEvent

    data class Complete(
        val blocks: List<TextBlock>,
        val responseText: String = blocks.joinToString("\n\n") { it.text },
        val metrics: ProcessingMetrics? = null,
    ) : ReadingEvent

    data class Failure(
        val message: String,
        val canRetry: Boolean = true,
        val canReadLocally: Boolean = true,
        val partialText: String = "",
    ) : ReadingEvent
}

data class ProcessingMetrics(
    val firstTextMs: Long?,
    val firstAudioReadyMs: Long?,
    val totalMs: Long,
    val inputBytes: Int,
)

interface ImageSpeechProcessor {
    fun process(request: ProcessingRequest): Flow<ReadingEvent>

    fun read(text: String): Flow<SpokenAudio>
}
