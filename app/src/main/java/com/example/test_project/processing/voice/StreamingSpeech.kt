package com.example.test_project.processing.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.transform

/**
 * OCR may prepare four passages while synthesis runs; only one audio clip is queued ahead.
 * Cancellation propagates upstream through both buffers and releases the active native call when it
 * returns. Unlike a detached worker, this cannot keep reading after Stop.
 */
fun <T> streamingSpeech(
    passages: Flow<String>,
    onSkipped: (Exception) -> Unit = {},
    synthesize: suspend (String) -> T,
): Flow<T> =
    passages.buffer(4).transform { passage ->
        // A passage the voice cannot say is skipped; the rest are still spoken.
        val audio = try {
            synthesize(passage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onSkipped(error)
            return@transform
        }
        emit(audio)
    }.buffer(1)
