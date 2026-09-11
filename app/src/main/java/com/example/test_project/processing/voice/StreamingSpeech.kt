package com.example.test_project.processing.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map

/**
 * OCR may prepare four passages while synthesis runs; only one audio clip is queued ahead.
 * Cancellation propagates upstream through both buffers and releases the active native call when it
 * returns. Unlike a detached worker, this cannot keep reading after Stop.
 */
fun <T> streamingSpeech(passages: Flow<String>, synthesize: suspend (String) -> T): Flow<T> =
    passages.buffer(4).map { synthesize(it) }.buffer(1)
