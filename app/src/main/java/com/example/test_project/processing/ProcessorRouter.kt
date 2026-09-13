package com.example.test_project.processing

import android.content.Context
import com.example.test_project.contract.*
import com.example.test_project.processing.voice.SpeechSynthesizer
import com.example.test_project.remote.GeminiClient
import com.example.test_project.remote.GeminiImageSpeechProcessor
import com.example.test_project.settings.GeminiKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Snapshots the key at request start, then routes without ever invoking the other lane. */
class ProcessorRouter(
    context: Context,
    private val keyStore: GeminiKeyStore,
    client: GeminiClient = GeminiClient(),
) : ImageSpeechProcessor {
    private val voice = SpeechSynthesizer(context.applicationContext)
    private val localDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalImageSpeechProcessor(context.applicationContext, voice, ownsVoice = false)
    }
    private val local by localDelegate
    private val remote = GeminiImageSpeechProcessor(context.applicationContext, client, keyStore::get, voice)

    val isGemini: Boolean get() = keyStore.get() != null

    suspend fun prepareSpeech() = voice.prepare()
    suspend fun prepareLocal() = local.awaitReady()

    override fun process(request: ProcessingRequest): Flow<ReadingEvent> {
        return when (ProcessingSelector.select(keyStore.get() != null, request.action)) {
            ProcessingLane.GEMINI -> remote.process(request)
            ProcessingLane.LOCAL -> local.process(request)
            ProcessingLane.GEMINI_REQUIRED -> flow {
                emit(ReadingEvent.Failure("Add a Gemini API key in Settings to ask questions. Local reading is still available.", false, false))
            }
        }
    }

    fun processLocally(request: ProcessingRequest): Flow<ReadingEvent> =
        local.process(request.copy(action = ProcessingAction.READ, question = null, audio = null))

    fun resetConversation() = remote.resetConversation()

    override fun read(text: String): Flow<SpokenAudio> = remote.read(text)

    fun close() {
        if (localDelegate.isInitialized()) local.close()
        voice.close()
    }
}
