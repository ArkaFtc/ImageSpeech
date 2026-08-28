package com.example.test_project

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks streamed text, and throttles whoever is producing it.
 *
 * The model decodes at roughly 25 tokens/sec while speech consumes 3-4, so an unthrottled
 * generation loop runs the GPU flat out for minutes and cooks the phone. [speak] suspends once
 * [MAX_PENDING] utterances are outstanding, which pushes that backpressure up through the token
 * Flow to the decoder: it generates a couple of sentences ahead of the voice and then idles.
 *
 * The permit bucket is a channel rather than a semaphore so that [stop] can drain it without
 * having to reason about how many completion callbacks are still in flight - a stray release
 * against an empty channel is a no-op, whereas a stray semaphore release would leak a permit and
 * quietly widen the queue on every barge-in.
 */
class SpeechQueue(context: Context) {

    private val slots = Channel<Unit>(MAX_PENDING)
    private val readySignal = CompletableDeferred<Boolean>()
    private val sequence = AtomicInteger(0)

    /** Bumped by [stop]; utterances from an older generation are dropped rather than spoken. */
    @Volatile
    private var generation = 0

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                Log.e(TAG, "TextToSpeech init failed with status $status")
                readySignal.complete(false)
                return@TextToSpeech
            }
            val languageStatus = engine.setLanguage(Locale.getDefault())
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "Locale ${Locale.getDefault()} unavailable, falling back to US English")
                engine.setLanguage(Locale.US)
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = release()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = release()

                @Deprecated("Required by the base class; the API 21+ overload delegates here.")
                override fun onError(utteranceId: String?) = release()
                override fun onError(utteranceId: String?, errorCode: Int) = release()
            })
            readySignal.complete(true)
        }
    }

    /** Resolves once the engine has initialised. False means no voice is available on this device. */
    suspend fun awaitReady(): Boolean = readySignal.await()

    /**
     * Queues one utterance, suspending while the voice is more than [MAX_PENDING] utterances
     * behind. Returns false when the utterance was dropped - either the engine is unavailable, or
     * [stop] fired while this call was waiting for a slot.
     */
    suspend fun speak(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        if (!readySignal.await()) return false
        val engine = tts ?: return false

        val queuedGeneration = generation
        slots.send(Unit)

        // A barge-in may have landed while this call was parked on send().
        if (queuedGeneration != generation) {
            slots.tryReceive()
            return false
        }

        val id = "u${sequence.incrementAndGet()}"
        val result = engine.speak(body, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            // No completion callback will arrive for a rejected utterance, so free the slot here.
            Log.w(TAG, "speak() rejected utterance $id")
            slots.tryReceive()
            return false
        }
        return true
    }

    /**
     * Cuts off playback and abandons everything queued. Safe to call from the main thread on every
     * button press - this is what makes barge-in free, since the producer was never allowed to run
     * more than [MAX_PENDING] utterances ahead of the voice.
     */
    fun stop() {
        generation++
        tts?.stop()
        @Suppress("ControlFlowWithEmptyBody")
        while (slots.tryReceive().isSuccess) {
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun release() {
        slots.tryReceive()
    }

    companion object {
        private const val TAG = "SpeechQueue"

        /**
         * Sentences the producer may run ahead of the voice. Two keeps playback gapless while
         * holding the GPU duty cycle near the ratio of decode speed to speech speed.
         */
        const val MAX_PENDING = 2
    }
}

/**
 * Reassembles streamed tokens into whole sentences.
 *
 * TTS must never be handed a partial word, and a token stream arrives split at arbitrary points,
 * so text is buffered until a terminator is followed by whitespace. Text with no punctuation at all
 * - a sign, a label, a wall of OCR output - would otherwise buffer forever, so [SOFT_LIMIT] forces
 * a cut at the last word boundary.
 */
class SentenceChunker {

    private val buffer = StringBuilder()

    /** Appends a fragment and returns whatever complete sentences that made available. */
    fun offer(fragment: String): List<String> {
        buffer.append(fragment)
        val ready = mutableListOf<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (piece.isNotEmpty()) ready.add(piece)
        }
        return ready
    }

    /** Returns the unterminated tail, if any, and clears the buffer. Call once the stream ends. */
    fun flush(): String? {
        val tail = buffer.toString().trim()
        buffer.setLength(0)
        return tail.ifEmpty { null }
    }

    private fun nextCut(): Int? {
        for (i in buffer.indices) {
            when (buffer[i]) {
                // A newline is unambiguous - it is the boundary, whatever follows it. Verbatim
                // reading relies on this to break per OCR region.
                '\n' -> if (i + 1 >= MIN_SEGMENT) return i + 1

                // Sentence terminators are not. Mid-stream a trailing '.' may yet turn out to be a
                // decimal point or an abbreviation, so wait for the next character to prove it was
                // the end; flush() catches a genuine final sentence.
                '.', '!', '?' -> {
                    val terminated = i + 1 < buffer.length && buffer[i + 1].isWhitespace()
                    if (terminated && i + 1 >= MIN_SEGMENT) return i + 1
                }
            }
        }
        if (buffer.length >= SOFT_LIMIT) {
            val boundary = buffer.lastIndexOf(" ", SOFT_LIMIT)
            if (boundary >= MIN_SEGMENT) return boundary + 1
        }
        return null
    }

    private companion object {
        /** Guards against splitting on "A." style initials into unspeakable one-character clips. */
        const val MIN_SEGMENT = 3
        const val SOFT_LIMIT = 220
    }
}
