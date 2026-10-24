package com.example.test_project.ondevice

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import com.example.test_project.contract.ImageSpeechProcessor
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.ProcessingMetrics
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ReadingEvent
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.processing.voice.SentenceChunker
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Answers and reads with Gemma on the phone. No OCR runs first.
 *
 * One live conversation is kept per subject so follow-ups reuse the images the model has already
 * encoded. The completed turns are also kept here, so a conversation that was interrupted or grew
 * too long can be rebuilt from them.
 */
class GemmaImageSpeechProcessor(
    private val model: OnDeviceModel,
    private val speak: suspend (String) -> SpokenAudio,
    private val encode: (Bitmap) -> ByteArray = ::encodeJpeg,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val contextTokens: Int = GemmaConfig.MAX_CONTEXT_TOKENS,
    private val outputTokens: Int = GemmaConfig.MAX_OUTPUT_TOKENS,
    /** Wraps every load and generation, so a low-memory kill can be recognised on the next launch. */
    private val whileBusy: suspend (suspend () -> Unit) -> Unit = { it() },
) : ImageSpeechProcessor {
    /**
     * One completed exchange. [images] are only the photos this turn introduced: photos enter the
     * conversation in their own turn, which the model answers before any request is asked.
     */
    private class Turn(val images: List<Bitmap>, val audioWav: ByteArray?, val text: String, val reply: String) {
        val tokens: Int get() = estimate(images.size, audioWav, text.length + reply.length)
    }

    private val generation = Mutex()
    private val guard = Any()
    private val history = ArrayDeque<Turn>()
    private val encoded = LinkedHashMap<Bitmap, ByteArray>()
    private var live: ModelSession? = null
    private var liveImages = emptySet<Bitmap>()
    private var liveTokens = 0

    /**
     * A request was interrupted on the live conversation. Text requests carry on fine afterwards
     * (measured on device), but recorded audio sent next was not heard, so audio rebuilds.
     */
    private var liveInterrupted = false

    /**
     * Opening a conversation processes the photos, which takes most of the wait on a phone CPU.
     * It runs here rather than in the request, so a request cancelled meanwhile (Ask during the
     * automatic read) does not throw that work away: the next request waits for it instead.
     */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var opening: Deferred<Unit>? = null

    /** Bumped by [resetConversation] so a turn finishing afterwards cannot write stale history. */
    private var subject = 0

    fun resetConversation() {
        val old = synchronized(guard) {
            subject++
            history.clear()
            encoded.clear()
            takeLive()
        }
        old?.close()
    }

    /** Waits for any photo processing in flight, so the engine can be closed safely afterwards. */
    suspend fun awaitIdle() {
        synchronized(guard) { opening }?.let { runCatching { it.join() } }
        generation.withLock { }
    }

    override fun process(request: ProcessingRequest): Flow<ReadingEvent> = channelFlow {
        val question = request.question?.trim().orEmpty()
        if (request.action == ProcessingAction.ASK && question.isEmpty() && request.audio?.hasSpeech != true) {
            send(ReadingEvent.Failure("I did not hear a question. Hold the button, speak, then let go.", false, false))
            return@channelFlow
        }
        // A replaced request may still be unwinding; one generation at a time keeps the session sane.
        generation.withLock { whileBusy busy@{
            val started = clock()
            if (!model.isLoaded) send(ReadingEvent.Status("Loading Gemma on this phone…"))
            else send(ReadingEvent.Status(working(request.action)))

            val images = (listOf(request.image) + request.additionalImages).distinct().takeLast(GemmaConfig.MAX_IMAGES)
            val text = instruction(request.action, question)
            val audioWav = request.audio?.takeIf { it.hasSpeech }?.toWavBytes()
            val mySubject = synchronized(guard) { subject }

            val bytes = withContext(Dispatchers.Default) { images.map(::jpegFor) }
            val byImage = images.zip(bytes).toMap()

            val session: ModelSession
            val newImages: List<Bitmap>
            try {
                val prepared = prepareSession(images, audioWav, text) {
                    send(ReadingEvent.Status("Looking at the photo on this phone…"))
                }
                session = prepared.first
                newImages = prepared.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Gemma could not open a conversation", error)
                send(ReadingEvent.Failure(describe(error), canRetry = error !is GemmaUnavailableException, canReadLocally = true))
                return@busy
            }

            var inputBytes = 0
            if (newImages.isNotEmpty()) {
                // Another view on a live conversation. It goes in as its own exchange, like the
                // first photo, so the question that follows is never in the same turn as a photo.
                val photos = ModelPrompt(newImages.map { byImage.getValue(it) }, null, ANOTHER_VIEW_TEXT)
                inputBytes += photos.images.sumOf { it.size }
                val ok = try {
                    val reply = StringBuilder()
                    session.send(photos).collect { reply.append(it) }
                    val turn = Turn(newImages, null, ANOTHER_VIEW_TEXT, reply.toString().trim().ifEmpty { PREFACE_REPLY })
                    commitPhotos(mySubject, session, turn)
                    true
                } catch (cancelled: CancellationException) {
                    discardLive(session)
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Gemma could not take the new photo", error)
                    discardLive(session)
                    send(ReadingEvent.Failure(describe(error), canRetry = true, canReadLocally = true))
                    false
                }
                if (!ok) return@busy
            }

            if (request.action == ProcessingAction.PREPARE) {
                // The photo is processed and waiting; nothing is read until the user asks for it.
                send(ReadingEvent.Status(PHOTO_READY))
                send(ReadingEvent.Complete(emptyList(), "", ProcessingMetrics(null, null, clock() - started, 0)))
                return@busy
            }

            val prompt = ModelPrompt(emptyList(), audioWav, text)
            inputBytes += audioWav?.size ?: 0
            val sentences = Channel<String>(SPEECH_LOOKAHEAD)
            var firstTextMs: Long? = null
            var firstAudioMs: Long? = null
            val speech = launch {
                for (sentence in sentences) {
                    val audio = speak(sentence)
                    if (firstAudioMs == null) firstAudioMs = clock() - started
                    send(ReadingEvent.Audio(audio.copy(readingStartedAtMs = started)))
                }
            }
            // Gemma's line breaks are meaningful, and speech should start with the first phrase.
            val chunker = SentenceChunker(lineBreaksEndSentences = true, quickFirstPiece = true)
            val reply = StringBuilder()
            var completed = false
            var interrupted = false
            var failure: Exception? = null
            try {
                session.send(prompt).collect { delta ->
                    if (firstTextMs == null) firstTextMs = clock() - started
                    reply.append(delta)
                    send(ReadingEvent.TextDelta(delta))
                    chunker.offer(delta).forEach { sentences.send(it) }
                }
                completed = reply.isNotBlank()
            } catch (cancelled: CancellationException) {
                interrupted = true
                throw cancelled
            } catch (error: Exception) {
                failure = error
            } finally {
                if (interrupted) {
                    // Stopped by the user or a newer request. The conversation, with its processed
                    // photos, stays usable; keeping it is what makes the next question fast.
                    keepInterrupted(session, audioWav, text.length + reply.length)
                } else if (!completed) {
                    // A failed or empty turn leaves the native conversation in an unknown state.
                    // Rebuild next time.
                    discardLive(session)
                }
            }

            if (!completed) {
                // Sentences already queued are complete and were already shown, so they finish;
                // the unfinished tail is not spoken.
                sentences.close()
                speech.join()
                if (failure != null) Log.w(TAG, "Gemma generation failed", failure)
                send(
                    ReadingEvent.Failure(
                        message = if (failure == null) "Gemma did not produce an answer. Try again or take a closer photo." else describe(failure),
                        canRetry = true,
                        canReadLocally = true,
                        partialText = reply.toString(),
                    ),
                )
                return@busy
            }

            chunker.flush()?.let { sentences.send(it) }
            sentences.close()
            speech.join()
            val answer = reply.toString().trim()
            commit(mySubject, Turn(emptyList(), audioWav, text, answer), images)
            val total = clock() - started
            // Off unless enabled for troubleshooting: adb shell setprop log.tag.GemmaAnswers DEBUG
            if (Log.isLoggable(ANSWER_LOG_TAG, Log.DEBUG)) {
                Log.d(ANSWER_LOG_TAG, "prompt=${prompt.text.take(200)} | answer=${answer.take(300).replace('\n', ' ')}")
            }
            Log.i(TAG, "gemma firstTextMs=$firstTextMs firstAudioReadyMs=$firstAudioMs totalMs=$total inputBytes=$inputBytes")
            send(ReadingEvent.Complete(emptyList(), answer, ProcessingMetrics(firstTextMs, firstAudioMs, total, inputBytes)))
        } }
    }.buffer(8)

    /**
     * Reuses the live conversation when the new turn fits; otherwise opens one from as many
     * completed turns as fit, with the photos they lack processed up front. Returns the session
     * and the images it has not seen yet (only ever non-empty for a reused conversation).
     */
    private suspend fun prepareSession(
        images: List<Bitmap>,
        audioWav: ByteArray?,
        text: String,
        onWaiting: suspend () -> Unit,
    ): Pair<ModelSession, List<Bitmap>> {
        var waited = false
        while (true) {
            // Photo processing started by an earlier, possibly cancelled, request: finish it.
            synchronized(guard) { opening }?.let { pending ->
                if (!waited) onWaiting()
                waited = true
                try {
                    pending.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }

            val (reusable, stale) = synchronized(guard) {
                val unseen = images.filterNot { it in liveImages }
                val cost = estimate(unseen.size, audioWav, text.length)
                val audioAfterInterruption = audioWav != null && liveInterrupted
                if (live != null && !audioAfterInterruption && liveTokens + cost + outputTokens <= contextTokens) {
                    (live!! to unseen) to null
                } else {
                    null to takeLive()
                }
            }
            stale?.close()
            reusable?.let { return it }

            if (!waited) onWaiting()
            waited = true
            startOpening(images, audioWav, text)
        }
    }

    private fun startOpening(images: List<Bitmap>, audioWav: ByteArray?, text: String) {
        synchronized(guard) {
            if (opening != null) return
            val mySubject = subject
            val kept = fitHistory(images, audioWav, text)
            val carried = kept.flatMap { it.images }.toSet()
            // Photos the kept turns do not hold get their own exchange with a short reply. A photo
            // left as the last user message, or sent in the same message as the question, made
            // Gemma repeat the question back instead of answering it (measured on device).
            val photoTurn = images.filterNot { it in carried }.takeIf { it.isNotEmpty() }
                ?.let { Turn(it, null, PREFACE_TEXT, PREFACE_REPLY) }
            val turns = kept + listOfNotNull(photoTurn)
            val modelTurns = turns.map { turn ->
                ModelTurn(ModelPrompt(turn.images.mapNotNull { encoded[it] }, turn.audioWav, turn.text), turn.reply)
            }
            val job = background.async {
                whileBusy {
                    val opened = model.open(modelTurns)
                    val adopted = synchronized(guard) {
                        if (subject != mySubject) return@synchronized false
                        live = opened
                        liveImages = turns.flatMap { it.images }.toSet()
                        liveTokens = turns.sumOf { it.tokens }
                        liveInterrupted = false
                        photoTurn?.let { history.addLast(it) }
                        true
                    }
                    // A new subject was started meanwhile; this conversation is about the old one.
                    if (!adopted) opened.close()
                }
            }
            opening = job
            job.invokeOnCompletion { synchronized(guard) { if (opening === job) opening = null } }
        }
    }

    /**
     * Oldest turns go first. Turns holding a photo the request still uses are never dropped, so
     * trimming never loses the photo the conversation is about.
     */
    private fun fitHistory(images: List<Bitmap>, audioWav: ByteArray?, text: String): List<Turn> {
        val turns = history.toMutableList()
        fun total(): Int {
            val carried = turns.flatMap { it.images }.toSet()
            val newCost = estimate(images.count { it !in carried }, audioWav, text.length)
            return turns.sumOf { it.tokens } + newCost
        }
        while (total() + outputTokens > contextTokens) {
            val droppable = turns.indexOfFirst { turn -> turn.images.none { it in images } }
            if (droppable < 0) break
            turns.removeAt(droppable)
        }
        return turns
    }

    /** Records a completed request. [images] are the photos the request was about. */
    private fun commit(mySubject: Int, turn: Turn, images: List<Bitmap>) {
        synchronized(guard) {
            if (mySubject != subject) return
            history.addLast(turn)
            // Photos no longer part of the subject are not carried into a rebuilt conversation.
            history.removeAll { old -> old.images.isNotEmpty() && old.images.none { it in images } }
            while (history.count { it.images.isEmpty() } > GemmaConfig.MAX_COMPLETED_TURNS) {
                history.removeAt(history.indexOfFirst { it.images.isEmpty() })
            }
            liveTokens += turn.tokens
            val referenced = history.flatMap { it.images }.toSet()
            encoded.keys.retainAll(referenced)
        }
    }

    /** Records a photo added to the live conversation [session]. */
    private fun commitPhotos(mySubject: Int, session: ModelSession, turn: Turn) {
        synchronized(guard) {
            if (mySubject != subject || live !== session) return
            history.addLast(turn)
            liveImages = liveImages + turn.images
            liveTokens += turn.tokens
        }
    }

    private fun jpegFor(image: Bitmap): ByteArray =
        synchronized(guard) { encoded[image] } ?: encode(image).also { bytes -> synchronized(guard) { encoded[image] = bytes } }

    private fun takeLive(): ModelSession? {
        val old = live
        live = null
        liveImages = emptySet()
        liveTokens = 0
        liveInterrupted = false
        return old
    }

    private fun keepInterrupted(session: ModelSession, audioWav: ByteArray?, chars: Int) {
        synchronized(guard) {
            if (live !== session) return
            liveInterrupted = true
            liveTokens += estimate(0, audioWav, chars)
        }
    }

    private fun discardLive(session: ModelSession) {
        synchronized(guard) { if (live === session) takeLive() }
        session.close()
    }

    override fun read(text: String): Flow<SpokenAudio> = flow {
        val chunker = SentenceChunker()
        chunker.offer(text).forEach { emit(speak(it)) }
        chunker.flush()?.let { emit(speak(it)) }
    }

    fun close() = resetConversation()

    companion object {
        private const val TAG = "ReadingTiming"
        private const val SPEECH_LOOKAHEAD = 4
        private const val AUDIO_BYTES_PER_SECOND = 32_000
        private const val ESTIMATED_TOKENS_PER_AUDIO_SECOND = 25

        private const val ANSWER_LOG_TAG = "GemmaAnswers"

        const val PHOTO_READY = "Photo ready. Ask a question, Summarize, or Read all text."

        private fun working(action: ProcessingAction): String = when (action) {
            ProcessingAction.PREPARE -> "Looking at the photo on this phone…"
            ProcessingAction.ASK -> "Thinking on this phone…"
            ProcessingAction.SUMMARIZE -> "Summarizing on this phone…"
            ProcessingAction.READ, ProcessingAction.READ_ALL -> "Reading on this phone…"
        }

        /**
         * Photos processed ahead of any request go in as this exchange, with a written reply.
         * Measured on device with a real photo: with the photo left as the last user message, or
         * a bare "OK." reply, some questions came back repeated instead of answered.
         */
        const val PREFACE_TEXT = "Here is a photo."
        const val PREFACE_REPLY = "I can see the photo. What would you like to know?"

        /** Adds a photo to a live conversation; the model's short reply becomes part of it. */
        const val ANOTHER_VIEW_TEXT = "Here is another photo of the same thing. Reply only: I can see it."

        /**
         * The request, separate from the system prompt. Questions go in exactly as asked: wrapping
         * them in a label such as "User question:" made Gemma repeat the question back.
         */
        fun instruction(action: ProcessingAction, question: String): String = when (action) {
            ProcessingAction.PREPARE -> ""
            ProcessingAction.SUMMARIZE -> "Briefly say what this is and the most important information in it."
            ProcessingAction.READ -> "Read the meaningful visible text faithfully in logical order."
            ProcessingAction.READ_ALL -> "Read all visible text, including peripheral text, page numbers, running headers, labels, and other visible wording."
            ProcessingAction.ASK ->
                question.ifEmpty { "Answer the spoken question in the attached audio." }
        }

        fun estimate(images: Int, audioWav: ByteArray?, chars: Int): Int {
            val audioSeconds = (audioWav?.size ?: 0).toDouble() / AUDIO_BYTES_PER_SECOND
            return images * GemmaConfig.ESTIMATED_TOKENS_PER_IMAGE +
                (audioSeconds * ESTIMATED_TOKENS_PER_AUDIO_SECOND).roundToInt() +
                chars / GemmaConfig.ESTIMATED_CHARS_PER_TOKEN + 16
        }

        fun describe(error: Throwable): String {
            if (error is GemmaUnavailableException) return error.message ?: "Gemma is not available."
            val detail = (error.message ?: "").lowercase()
            return when {
                "token" in detail && ("exceed" in detail || "limit" in detail || "too long" in detail) ->
                    "This conversation is too long for Gemma. Take a new photo to start fresh."
                error is OutOfMemoryError || "memory" in detail ->
                    "The phone ran out of memory for Gemma. Close other apps and try again."
                else -> "Gemma stopped before finishing. Try again, or read with local OCR."
            }
        }

        fun encodeJpeg(source: Bitmap): ByteArray {
            val scale = minOf(1f, GemmaConfig.IMAGE_LONG_EDGE.toFloat() / maxOf(source.width, source.height))
            val bitmap = if (scale < 1f) {
                source.scale((source.width * scale).roundToInt(), (source.height * scale).roundToInt())
            } else source
            return try {
                ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, GemmaConfig.JPEG_QUALITY, out))
                    out.toByteArray()
                }
            } finally {
                if (bitmap !== source) bitmap.recycle()
            }
        }
    }
}
