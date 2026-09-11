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
    val audio: RecordedAudio? = null,
    val mode: ReadingMode = ReadingMode.DOCUMENT,
)

data class SpokenAudio(val text: String, val wav: ByteArray, val readingStartedAtMs: Long? = null)

/** One collection owns recognition, speech and cancellation for a reading session. */
sealed interface ReadingEvent {
    data class Status(val text: String) : ReadingEvent

    data class Block(val block: TextBlock) : ReadingEvent

    data class Audio(val audio: SpokenAudio) : ReadingEvent

    data class Complete(val blocks: List<TextBlock>) : ReadingEvent
}

interface ImageSpeechProcessor {
    fun process(request: ProcessingRequest): Flow<ReadingEvent>

    fun read(text: String): Flow<SpokenAudio>
}
