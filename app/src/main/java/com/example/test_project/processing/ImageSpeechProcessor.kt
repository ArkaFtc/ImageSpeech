package com.example.test_project.processing

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.test_project.R
import com.example.test_project.contract.*
import com.example.test_project.processing.model.SceneAnswerer
import com.example.test_project.processing.ocr.DocumentReader
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner
import com.example.test_project.processing.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One structured session feeds UI passages and speech concurrently. Caches only complete reads. */
class LocalImageSpeechProcessor(
    private val context: Context,
    private val answerer: () -> SceneAnswerer?,
) : ImageSpeechProcessor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val engine = scope.async { DocumentReader(context.applicationContext) }
    private val transcriber = Transcriber(context)
    private val voice = SpeechSynthesizer(context)
    private var cached: Cache? = null

    private data class Cache(
        val image: Bitmap,
        val mode: ReadingMode,
        val blocks: List<TextBlock>,
        val result: LocalPPOCRv6Runner.OcrResult,
    )

    suspend fun awaitReady() {
        engine.await()
        try {
            voice.prepare()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ImageSpeech", "Speech not ready", error)
        }
    }

    override fun process(request: ProcessingRequest): Flow<ReadingEvent> =
        channelFlow {
                val started = SystemClock.elapsedRealtime()
                var firstAudio = true
                val blocks = mutableListOf<TextBlock>()
                val passages =
                    flow {
                            val clip = request.audio
                            val transcript =
                                if (clip?.hasSpeech == true) transcriber.transcribe(clip) else null
                            val readAloud =
                                clip == null ||
                                    (clip.hasSpeech &&
                                        IntentRouter.route(
                                                IntentRouter.TAP_THRESHOLD_MS,
                                                true,
                                                transcript,
                                            )
                                            .route == IntentRouter.Route.READ_VERBATIM)
                            suspend fun publish(block: TextBlock) {
                                blocks += block
                                send(ReadingEvent.Block(block))
                                if (readAloud) {
                                    val prefix =
                                        if (block.uncertain)
                                            context.getString(R.string.uncertain_passage) + " "
                                        else ""
                                    emit(prefix + block.lines.joinToString(" "))
                                }
                            }
                            val recognized =
                                lock.withLock {
                                    val previous =
                                        cached?.takeIf {
                                            it.image === request.image && it.mode == request.mode
                                        }
                                    if (previous != null) {
                                        previous.blocks.forEach { publish(it) }
                                        previous.result
                                    } else {
                                        val result =
                                            engine
                                                .await()
                                                .read(
                                                    request,
                                                    onStatus = { send(ReadingEvent.Status(it)) },
                                                    onBlock = { publish(it) },
                                                )
                                        currentCoroutineContext().ensureActive()
                                        cached =
                                            Cache(
                                                request.image,
                                                request.mode,
                                                blocks.toList(),
                                                result,
                                            )
                                        result
                                    }
                                }
                            Log.i(
                                "ReadingTiming",
                                "ocrCompleteMs=${SystemClock.elapsedRealtime()-started} blocks=${blocks.size}",
                            )
                            if (readAloud) {
                                if (blocks.isEmpty())
                                    emit(context.getString(R.string.no_text_found))
                            } else if (clip?.hasSpeech != true) {
                                emit(
                                    context.getString(
                                        if (clip?.isSilent == true) R.string.mic_silent
                                        else R.string.nothing_heard
                                    )
                                )
                            } else {
                                // Questions retain the full-image/full-OCR context; verbatim
                                // reading bypasses the LLM.
                                val turn =
                                    answerer()
                                        ?.takeIf { it.isAvailable }
                                        ?.beginTurn(request.image, recognized)
                                if (turn == null) {
                                    emit(context.getString(R.string.model_unavailable))
                                    blocks.forEach { emit(it.text) }
                                } else
                                    try {
                                        val chunker = SentenceChunker()
                                        turn.ask(clip, transcript).collect { token ->
                                            chunker.offer(token).forEach { emit(it) }
                                        }
                                        chunker.flush()?.let { emit(it) }
                                    } finally {
                                        turn.close()
                                    }
                            }
                        }
                        .flowOn(Dispatchers.Default)
                streamingSpeech(passages) { voice.synthesize(it) }
                    .collect { audio ->
                        if (firstAudio) {
                            Log.i(
                                "ReadingTiming",
                                "firstAudioReadyMs=${SystemClock.elapsedRealtime()-started}",
                            )
                            firstAudio = false
                        }
                        send(ReadingEvent.Audio(audio.copy(readingStartedAtMs = started)))
                    }
                send(ReadingEvent.Complete(blocks.toList()))
            }
            .buffer(1)

    override fun read(text: String): Flow<SpokenAudio> {
        val passages = flow {
            val chunker = SentenceChunker()
            chunker.offer(text).forEach { emit(it) }
            chunker.flush()?.let { emit(it) }
        }
        return streamingSpeech(passages) { voice.synthesize(it) }
    }

    fun close() {
        voice.close()
        scope.launch {
            try {
                lock.withLock {
                    runCatching { engine.await().close() }
                    cached = null
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
