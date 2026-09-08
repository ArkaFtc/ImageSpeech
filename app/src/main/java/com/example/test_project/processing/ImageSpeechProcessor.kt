package com.example.test_project.processing

import android.content.Context
import android.graphics.Bitmap
import com.example.test_project.R
import com.example.test_project.contract.ImageSpeechProcessor
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ProcessingResponse
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.processing.model.SceneAnswerer
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner
import com.example.test_project.processing.voice.SentenceChunker
import com.example.test_project.processing.voice.SpeechSynthesizer
import com.example.test_project.processing.voice.Transcriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns recognition, question routing and synthesis; never records or plays audio. */
class LocalImageSpeechProcessor(
    private val context: Context,
    private val answerer: () -> SceneAnswerer?,
) : ImageSpeechProcessor {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineLock = Mutex()
    private val engine = engineScope.async {
        runCatching { LocalPPOCRv6Runner(context.applicationContext) }.getOrNull()
    }
    suspend fun awaitReady() { engine.await() }

    private val transcriber = Transcriber(context)
    private val voice = SpeechSynthesizer(context)
    private var cachedImage: Bitmap? = null
    private var cachedOcr: LocalPPOCRv6Runner.OcrResult? = null

    override suspend fun process(request: ProcessingRequest): ProcessingResponse {
        val result = if (cachedImage === request.image) cachedOcr else null
        val recognized = result ?: engine.await()?.let { runner ->
            engineLock.withLock {
                withContext(Dispatchers.Default) { runner.runOcr(request.image) }
            }
        } ?: LocalPPOCRv6Runner.OcrResult(emptyList(), context.getString(R.string.ocr_unavailable))
        cachedImage = request.image
        cachedOcr = recognized
        val tokens = flow {
            val clip = request.audio
            val text = recognized.blocks.joinToString("\n\n") { it.text }
                .ifBlank { recognized.message ?: context.getString(R.string.no_text_found) }
            if (clip == null) {
                emit(text)
            } else if (!clip.hasSpeech) {
                emit(context.getString(if (clip.isSilent) R.string.mic_silent else R.string.nothing_heard))
            } else {
                val transcript = transcriber.transcribe(clip)
                if (IntentRouter.route(IntentRouter.TAP_THRESHOLD_MS, true, transcript).route == IntentRouter.Route.READ_VERBATIM) {
                    emit(text)
                } else {
                    val model = answerer()?.takeIf { it.isAvailable }
                    val turn = model?.beginTurn(request.image, recognized)
                    if (turn == null) {
                        emit(context.getString(R.string.model_unavailable))
                        emit(text)
                    } else try {
                        turn.ask(clip, transcript).collect { emit(it) }
                    } finally { turn.close() }
                }
            }
        }
        return ProcessingResponse(recognized.blocks, synthesize(tokens))
    }

    override fun read(text: String): Flow<SpokenAudio> = synthesize(flowOf(text))

    private fun synthesize(tokens: Flow<String>): Flow<SpokenAudio> = flow {
        val chunker = SentenceChunker()
        tokens.collect { token ->
            for (sentence in chunker.offer(token)) emit(voice.synthesize(sentence))
        }
        chunker.flush()?.let { emit(voice.synthesize(it)) }
    }.buffer(1)

    fun close() {
        voice.close()
        engineScope.launch {
            val runner = engine.await()
            engineLock.withLock { runner?.close() }
            engineScope.cancel()
        }
        cachedImage = null
        cachedOcr = null
    }
}
