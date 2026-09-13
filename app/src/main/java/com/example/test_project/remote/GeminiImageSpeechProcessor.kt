package com.example.test_project.remote

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.test_project.contract.*
import com.example.test_project.processing.voice.SentenceChunker
import com.example.test_project.processing.voice.SpeechSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GeminiImageSpeechProcessor(
    context: Context,
    private val client: GeminiClient,
    private val apiKey: () -> String?,
    private val voice: SpeechSynthesizer,
) : ImageSpeechProcessor {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val history = ArrayDeque<ConversationTurn>()
    private val encoded = LinkedHashMap<android.graphics.Bitmap, EncodedImage>()

    fun resetConversation() = synchronized(lock) {
        history.clear()
        encoded.clear()
    }

    override fun process(request: ProcessingRequest): Flow<ReadingEvent> = channelFlow {
        val key = apiKey() ?: run {
            send(ReadingEvent.Failure("Add a Gemini API key in Settings to ask questions.", false, false))
            return@channelFlow
        }
        if (request.action == ProcessingAction.ASK && request.question.isNullOrBlank() && request.audio?.hasSpeech != true) {
            send(ReadingEvent.Failure("I did not hear a question. Hold the button, speak, then let go.", false, false))
            return@channelFlow
        }
        val started = SystemClock.elapsedRealtime()
        send(ReadingEvent.Status(if (request.action == ProcessingAction.ASK) "Asking Gemini…" else "Reading with Gemini…"))
        val images = (listOf(request.image) + request.additionalImages).takeLast(GeminiConfig.MAX_IMAGES)
        val encodedImages = withContext(Dispatchers.Default) {
            images.map { image -> synchronized(lock) { encoded[image] } ?: ImageEncoder.encode(image).also {
                synchronized(lock) {
                    encoded[image] = it
                    while (encoded.size > GeminiConfig.MAX_IMAGES) encoded.remove(encoded.keys.first())
                }
            } }
        }
        val uploadBytes = encodedImages.sumOf { it.bytes.size } + (request.audio?.toWavBytes()?.size ?: 0)
        val userInstruction = instruction(request)
        val body = GeminiRequestFactory.build(
            userInstruction,
            encodedImages,
            request.audio,
            synchronized(lock) { history.toList() },
        )
        val sentenceChannel = Channel<String>(4)
        var firstTextMs: Long? = null
        var firstSpeechMs: Long? = null
        val chunker = SentenceChunker()
        val speech = launch {
            for (sentence in sentenceChannel) {
                currentCoroutineContext().ensureActive()
                val audio = voice.synthesize(sentence)
                if (firstSpeechMs == null) firstSpeechMs = SystemClock.elapsedRealtime() - started
                send(ReadingEvent.Audio(audio.copy(readingStartedAtMs = started)))
            }
        }
        val result = try {
            client.stream(key, body) { delta ->
                currentCoroutineContext().ensureActive()
                if (firstTextMs == null) firstTextMs = SystemClock.elapsedRealtime() - started
                send(ReadingEvent.TextDelta(delta))
                chunker.offer(delta).forEach { sentenceChannel.send(it) }
            }
        } finally {
            // The unfinished tail is spoken only after a successful terminal response below.
        }
        when (result) {
            is GeminiResult.Complete -> {
                chunker.flush()?.let { sentenceChannel.send(it) }
                sentenceChannel.close()
                speech.join()
                synchronized(lock) {
                    history.addLast(ConversationTurn(userInstruction, result.text))
                    while (history.size > GeminiConfig.MAX_COMPLETED_TURNS) history.removeFirst()
                }
                val total = SystemClock.elapsedRealtime() - started
                Log.i("ReadingTiming", "gemini firstTextMs=$firstTextMs firstAudioReadyMs=$firstSpeechMs totalMs=$total uploadBytes=$uploadBytes")
                send(ReadingEvent.Complete(emptyList(), result.text, ProcessingMetrics(firstTextMs, firstSpeechMs, total, uploadBytes)))
            }
            is GeminiResult.Failed -> {
                sentenceChannel.close()
                speech.join()
                send(ReadingEvent.Failure(result.error.message, result.error.retryable, true, result.partialText))
            }
        }
    }.buffer(8)

    private fun instruction(request: ProcessingRequest): String {
        val mode = "general image"
        return when (request.action) {
            ProcessingAction.READ -> "Action: Read. Context: $mode. Read the meaningful visible text faithfully in logical order."
            ProcessingAction.READ_ALL -> "Action: Read all visible text. Context: $mode. Include peripheral text, page numbers, running headers, labels, and other visible wording."
            ProcessingAction.ASK -> {
                val question = request.question?.trim().orEmpty()
                if (question.isNotEmpty()) "Action: Ask. Context: $mode. User question: $question"
                else "Action: Ask. Context: $mode. Listen to the attached audio and answer that spoken request directly."
            }
        }
    }

    override fun read(text: String): Flow<SpokenAudio> = kotlinx.coroutines.flow.flow {
        val chunker = SentenceChunker()
        chunker.offer(text).forEach { emit(voice.synthesize(it)) }
        chunker.flush()?.let { emit(voice.synthesize(it)) }
    }
}
