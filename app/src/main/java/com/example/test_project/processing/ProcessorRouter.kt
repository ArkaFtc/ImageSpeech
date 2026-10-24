package com.example.test_project.processing

import android.content.Context
import com.example.test_project.contract.*
import com.example.test_project.ondevice.GemmaHealth
import com.example.test_project.ondevice.GemmaImageSpeechProcessor
import com.example.test_project.ondevice.GemmaModelStore
import com.example.test_project.ondevice.GemmaProfile
import com.example.test_project.ondevice.LiteRtGemmaModel
import com.example.test_project.processing.voice.SpeechSynthesizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Chooses the lane when a request starts, without touching the other lane: a downloaded Gemma
 * model never waits for OCR startup, and local reading never loads Gemma.
 */
class ProcessorRouter(
    context: Context,
    val modelStore: GemmaModelStore = GemmaModelStore(context.applicationContext),
) : ImageSpeechProcessor {
    private val voice = SpeechSynthesizer(context.applicationContext)
    private val localDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalImageSpeechProcessor(context.applicationContext, voice, ownsVoice = false)
    }
    private val local by localDelegate
    val gemmaProfile = GemmaProfile.forDevice(context.applicationContext)
    val gemmaHealth = GemmaHealth(context.applicationContext).also {
        // Before anything can load Gemma: was the last run killed for memory while using it?
        it.checkLastExit(context.applicationContext)
    }
    private val gemmaModel = LiteRtGemmaModel(context.applicationContext, modelStore, gemmaProfile)
    private val gemma = GemmaImageSpeechProcessor(
        model = gemmaModel,
        speak = voice::synthesize,
        contextTokens = gemmaProfile.contextTokens,
        outputTokens = gemmaProfile.outputTokens,
        whileBusy = { work -> gemmaHealth.whileBusy { work() } },
    )

    val isGemmaReady: Boolean
        get() = modelStore.isPresent && !gemmaModel.unusable && !gemmaHealth.isDisabled

    suspend fun prepareSpeech() = voice.prepare()
    suspend fun prepareLocal() = local.awaitReady()

    /**
     * Loads Gemma ahead of the first press on phones with room for it next to the camera. Smaller
     * phones load it after the photo is taken, when the camera has been released.
     */
    suspend fun prepareGemma() {
        if (isGemmaReady && gemmaProfile.loadAtStartup) gemmaHealth.whileBusy { gemmaModel.warmUp() }
    }

    /** Lets the user try Gemma again after it was turned off for running out of memory. */
    fun enableGemma() = gemmaHealth.enable()

    private val cacheDir = context.applicationContext.cacheDir

    override fun process(request: ProcessingRequest): Flow<ReadingEvent> {
        // Troubleshooting only (adb shell setprop log.tag.GemmaAnswers DEBUG): keep the photo
        // Gemma is shown, so a bad answer can be reproduced on the device.
        if (android.util.Log.isLoggable("GemmaAnswers", android.util.Log.DEBUG)) {
            runCatching { java.io.File(cacheDir, "last-photo.jpg").writeBytes(GemmaImageSpeechProcessor.encodeJpeg(request.image)) }
        }
        return when (ProcessingSelector.select(isGemmaReady, request.action)) {
            ProcessingLane.GEMMA -> gemma.process(request)
            ProcessingLane.LOCAL -> local.process(request)
            ProcessingLane.GEMMA_REQUIRED -> flow {
                val message =
                    if (modelStore.isPresent && gemmaHealth.isDisabled) "Gemma was turned off because this phone ran out of memory while using it. Local reading is still available."
                    else "Download the on-device Gemma model in Processing settings to ask questions or summarize. Local reading is still available."
                emit(ReadingEvent.Failure(message, false, false))
            }
        }
    }

    fun processLocally(request: ProcessingRequest): Flow<ReadingEvent> =
        local.process(request.copy(action = ProcessingAction.READ, question = null, audio = null))

    fun resetConversation() = gemma.resetConversation()

    /** Frees the model's memory, for example before deleting its file. */
    suspend fun releaseGemma() {
        gemma.resetConversation()
        // Photo processing cannot be interrupted natively; let it finish before closing the engine.
        gemma.awaitIdle()
        gemma.resetConversation()
        gemmaModel.close()
    }

    override fun read(text: String): Flow<SpokenAudio> = gemma.read(text)

    fun close() {
        gemma.resetConversation()
        gemmaModel.close()
        if (localDelegate.isInitialized()) local.close()
        voice.close()
    }
}
