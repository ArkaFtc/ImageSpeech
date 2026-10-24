package com.example.test_project.ondevice

import kotlinx.coroutines.flow.Flow

/** One user turn as the model sees it: JPEG images, an optional WAV question, and instructions. */
class ModelPrompt(
    val images: List<ByteArray>,
    val audioWav: ByteArray?,
    val text: String,
)

class ModelTurn(val prompt: ModelPrompt, val reply: String)

/**
 * The on-device language model, kept behind an interface so conversation, speech and cancellation
 * logic can be tested without native code.
 */
interface OnDeviceModel {
    /** True once the engine is in memory, so the first request can say it is loading. */
    val isLoaded: Boolean

    /**
     * Opens a conversation already holding [history] and, when given, a final user message
     * [preface] (typically the photo) with no reply yet. Both are processed before this returns,
     * so the next request only pays for its own text. Loads the engine first if needed.
     */
    suspend fun open(history: List<ModelTurn>, preface: ModelPrompt? = null): ModelSession

    fun close()
}

interface ModelSession : AutoCloseable {
    /**
     * Streams only answer text, never thinking content, as fresh fragments. Cancelling the
     * collection must stop generation, not just stop listening.
     */
    fun send(prompt: ModelPrompt): Flow<String>
}

class GemmaUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The runtime may report either fragments or the growing full message. This turns both into
 * fragments that were not seen before.
 */
class StreamDeltas {
    private val seen = StringBuilder()

    fun fresh(text: String): String {
        if (text.isEmpty()) return ""
        val fresh = if (seen.isNotEmpty() && text.startsWith(seen)) text.substring(seen.length) else text
        seen.append(fresh)
        return fresh
    }
}
