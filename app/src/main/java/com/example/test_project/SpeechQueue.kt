package com.example.test_project

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks streamed text, and throttles whoever is producing it.
 *
 * The model decodes at roughly 25 tokens/sec while speech consumes 3-4, so an unthrottled
 * generation loop runs the GPU flat out for minutes and cooks the phone. [speak] suspends once
 * [MAX_PENDING] utterances are waiting to start, which pushes that backpressure up through the
 * token Flow to the decoder: it generates a few sentences ahead of the voice and then idles.
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

    /**
     * Utterances that still owe a permit back.
     *
     * A healthy utterance produces both onStart and onDone, a barge-in retires ids wholesale, and
     * a rejected one produces nothing at all - so "has this id already paid" cannot be inferred
     * from the callback alone. Removing from this set is what makes every path return exactly one
     * permit, no more and no less.
     */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Bumped by [stop]; utterances from an older generation are dropped rather than spoken. */
    @Volatile
    private var generation = 0

    @Volatile
    private var tts: TextToSpeech? = null

    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * Speech is an accessibility stream, not media. Tagging it says so to the routing policy - it
     * follows the accessibility volume rather than the music one, which is the difference between
     * the app being audible and not on a phone that was muted for a video.
     */
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { change ->
                // Only a permanent loss. A transient one - a notification chime - is worth talking
                // over, and stopping for it would silently swallow the answer the user asked for.
                if (change == AudioManager.AUDIOFOCUS_LOSS) stop()
            }
            .build()

    @Volatile
    private var holdingFocus = false

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
            engine.setAudioAttributes(audioAttributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                // The permit comes back when an utterance *starts*, not when it finishes. Waiting
                // for onDone means the producer only wakes once the buffer is down to its last
                // utterance, so every decode stall lands as an audible gap; releasing at onStart
                // refills the tail of the queue while there is still audio playing over it.
                override fun onStart(utteranceId: String?) = release(utteranceId)
                override fun onDone(utteranceId: String?) = release(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) =
                    release(utteranceId)

                @Deprecated("Required by the base class; the API 21+ overload delegates here.")
                override fun onError(utteranceId: String?) = release(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = release(utteranceId)
            })
            readySignal.complete(true)
        }
    }

    /** Resolves once the engine has initialised. False means no voice is available on this device. */
    suspend fun awaitReady(): Boolean = readySignal.await()

    /**
     * Queues one utterance, suspending while [MAX_PENDING] utterances are already waiting to be
     * spoken. Returns false when the utterance was dropped - either the engine is unavailable, or
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

        acquireFocus()

        val id = "g$queuedGeneration-u${sequence.incrementAndGet()}"
        pending.add(id)
        val result = engine.speak(body, TextToSpeech.QUEUE_ADD, null, id)
        if (result != TextToSpeech.SUCCESS) {
            // No completion callback will arrive for a rejected utterance, so free the slot here.
            Log.w(TAG, "speak() rejected utterance $id")
            release(id)
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

        // Retire the outstanding ids and hand back exactly their permits. Draining the channel
        // blindly would also swallow a permit belonging to a speak() that raced past the
        // generation check above - that utterance still has callbacks coming, and taking its
        // permit here would leave the queue one slot narrower for the rest of the session.
        var orphaned = 0
        while (true) {
            val id = pending.firstOrNull() ?: break
            if (pending.remove(id)) orphaned++
        }
        tts?.stop()
        repeat(orphaned) { slots.tryReceive() }

        abandonFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun release(utteranceId: String?) {
        val id = utteranceId ?: return
        if (pending.remove(id)) slots.tryReceive()
    }

    /**
     * Ducks whatever else is playing for the length of the batch.
     *
     * Focus is held until the next [stop] rather than being dropped the moment the queue empties.
     * The queue runs dry between sentences whenever the model stalls, and unducking and reducking
     * the music on every stall is worse than holding the duck across the whole answer. Every route
     * into speech stops the previous one first, so the release is never far away.
     */
    private fun acquireFocus() {
        if (holdingFocus) return
        val manager = audioManager ?: return
        if (manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            holdingFocus = true
        }
    }

    private fun abandonFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        private const val TAG = "SpeechQueue"

        /**
         * Utterances the producer may leave waiting to start. With the permit released at onStart
         * this is the queue *behind* the one being spoken, so the voice has three sentences of
         * cover while the decoder works on the fourth. Two was too shallow, and released too late:
         * the refill only began once the buffer was empty, so any decode stall was audible.
         */
        const val MAX_PENDING = 3
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
        var i = 0
        scan@ while (i < buffer.length) {
            when (buffer[i]) {
                '\n' -> when (classifyNewline(i)) {
                    Newline.BOUNDARY -> return i + 1
                    // A wrapped line rather than a boundary. Fold it to a space in place, so the
                    // rest of the sentence joins on and the scan never reconsiders it.
                    Newline.WRAP -> buffer.setCharAt(i, ' ')
                    // Cannot tell without seeing what follows. Fall through to the soft limit,
                    // which is what stops a pathological line from buffering forever.
                    Newline.UNDECIDED -> break@scan
                }

                // Sentence terminators are not unambiguous either. Mid-stream a trailing '.' may
                // yet turn out to be a decimal point or an abbreviation, so wait for the next
                // character to prove it was the end; flush() catches a genuine final sentence.
                '.', '!', '?' -> {
                    val terminated = i + 1 < buffer.length && buffer[i + 1].isWhitespace()
                    if (terminated && i + 1 >= MIN_SEGMENT) return i + 1
                }
            }
            i++
        }
        if (buffer.length >= SOFT_LIMIT) {
            val boundary = buffer.lastIndexOf(" ", SOFT_LIMIT)
            if (boundary >= MIN_SEGMENT) return boundary + 1
        }
        return null
    }

    /**
     * Decides what the newline at [index] means.
     *
     * OCR hands back the line breaks of the printed page, and those are two different things
     * wearing the same character. On a sign or a menu the break *is* the structure, and speaking
     * each line as its own utterance is what lets the listener follow it. In a paragraph the break
     * is only where the column ran out, and honouring it drops a falling full stop into the middle
     * of a clause - a gap between "the quick brown fox" and "jumps over the lazy dog" every time
     * the text wraps, which is what makes a page of body text sound stuttery.
     *
     * The signals are the ones a sighted reader uses without thinking: real punctuation, a blank
     * line, a continuation that starts mid-sentence in lower case, and a line short enough to be a
     * label rather than a column of prose. It is tuned to split, for the same reason [TextBlocks]
     * is - an early cut costs a pause, a missed one costs a mangled sentence.
     */
    private fun classifyNewline(index: Int): Newline {
        // Too little in hand to be worth an utterance of its own - an initial, a stray bullet, a
        // one-character column. It rides along with the line below instead.
        if (index < MIN_SEGMENT) return Newline.WRAP

        val previous = lastVisible(index)
        if (previous != null && previous in TERMINATORS) return Newline.BOUNDARY

        if (index + 1 >= buffer.length) {
            // Nothing after it yet. A short line is a label and can go now; a long one is probably
            // a wrap, and guessing costs more than waiting for the next fragment.
            return if (index <= LABEL_LIMIT) Newline.BOUNDARY else Newline.UNDECIDED
        }

        val next = buffer[index + 1]
        // A blank line is a paragraph break in any layout.
        if (next == '\n') return Newline.BOUNDARY
        // A continuation of the same sentence, still in lower case.
        if (next.isLowerCase()) return Newline.WRAP
        return if (index <= LABEL_LIMIT) Newline.BOUNDARY else Newline.WRAP
    }

    /** The last non-blank character before [index], or null when there is none. */
    private fun lastVisible(index: Int): Char? {
        for (i in index - 1 downTo 0) {
            if (!buffer[i].isWhitespace()) return buffer[i]
        }
        return null
    }

    private enum class Newline { BOUNDARY, WRAP, UNDECIDED }

    private companion object {
        /**
         * Guards against splitting "A." style initials and one-character columns into unspeakable
         * clips. It is also the floor on what a newline may cut loose, so it cannot go far up -
         * "Gate 14" has to survive as an utterance of its own.
         */
        const val MIN_SEGMENT = 6

        /**
         * The longest run of text a newline may still cut on when nothing else says it should.
         * Above this the text reads as a wrapped column rather than a label, and only punctuation
         * or a blank line ends it.
         */
        const val LABEL_LIMIT = 24

        const val SOFT_LIMIT = 220

        val TERMINATORS = charArrayOf('.', '!', '?', ':', ';')
    }
}
