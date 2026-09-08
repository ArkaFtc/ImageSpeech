package com.example.test_project.contract

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

data class ProcessingRequest(val image: Bitmap, val audio: RecordedAudio? = null)
data class SpokenAudio(val text: String, val wav: ByteArray)
data class ProcessingResponse(val blocks: List<TextBlock>, val audio: Flow<SpokenAudio>)

interface ImageSpeechProcessor {
    suspend fun process(request: ProcessingRequest): ProcessingResponse
    fun read(text: String): Flow<SpokenAudio>
}
