package com.example.test_project.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Hold-to-talk speech to text. Words are reported as they are recognised so the question can be
 * shown while it is spoken; releasing the button asks for the final text.
 *
 * The phone's default recognizer (Google Speech Services on the test phone) is used, asked to
 * prefer offline recognition. The separate on-device recognizer is only a fallback: on the test
 * phone it often reported no speech for a whole press while the default one heard it.
 * Must be used from the main thread.
 */
class SpeechInput(
    context: Context,
    /** The microphone is open. Words spoken before this are not heard. */
    private val onReady: () -> Unit = {},
    private val onPartial: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Whether the microphone opened during this press. It takes about half a second after the
     * press (measured on device), and a press released before then heard nothing.
     */
    var isReady = false
        private set
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = !SpeechRecognizer.isRecognitionAvailable(appContext) &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    /** Text recognised so far in this press, across any restarts. */
    private val committed = StringBuilder()
    private var partial = ""
    private var listening = false
    private var held = false
    private var restarts = 0
    /** Loudest level the recognizer reported this press, logged to tell silence from a missed question. */
    private var loudestDb = Float.NEGATIVE_INFINITY
    private var finish: ((String) -> Unit)? = null

    /** False on phones with no speech recognizer; the caller then records audio instead. */
    val isAvailable: Boolean
        get() = onDevice || SpeechRecognizer.isRecognitionAvailable(appContext)

    /** Starts listening. The caller must already hold RECORD_AUDIO. */
    fun start() {
        cancel()
        committed.setLength(0)
        partial = ""
        held = true
        restarts = 0
        isReady = false
        loudestDb = Float.NEGATIVE_INFINITY
        listen()
    }

    /**
     * Stops listening and delivers the final text (possibly empty) once the recognizer has it.
     * [result] is called exactly once.
     */
    fun finish(result: (String) -> Unit) {
        held = false
        if (!listening) {
            result(text())
            return
        }
        finish = result
        // The last word is often still being said as the button is let go: keep listening a
        // moment. If the recognizer never answers, deliver what was heard rather than wait forever.
        main.postDelayed(stopAfterRelease, RELEASE_TAIL_MS)
        main.postDelayed(giveUp, RELEASE_TAIL_MS + FINISH_TIMEOUT_MS)
    }

    private val stopAfterRelease = Runnable { if (listening) recognizer?.stopListening() }

    private val giveUp = Runnable {
        val done = finish ?: return@Runnable
        Log.w(TAG, "Speech recognizer gave no final result; using what was heard")
        finish = null
        listening = false
        recognizer?.cancel()
        done(text())
    }

    fun cancel() {
        main.removeCallbacks(stopAfterRelease)
        main.removeCallbacks(giveUp)
        held = false
        finish = null
        listening = false
        recognizer?.cancel()
    }

    fun destroy() {
        cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun text(): String = listOf(committed.toString(), partial).filter { it.isNotBlank() }.joinToString(" ").trim()

    private fun listen() {
        val active = recognizer ?: create().also { recognizer = it }
        listening = true
        active.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // The button decides when the question ends, not a pause for breath.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, HOLD_SILENCE_MS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, HOLD_SILENCE_MS)
            },
        )
    }

    private fun create(): SpeechRecognizer {
        val created = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        Log.i(TAG, "Using ${if (onDevice) "on-device" else "system"} speech recognizer")
        created.setRecognitionListener(listener)
        return created
    }

    private fun settle() {
        listening = false
        if (held && restarts < MAX_RESTARTS) {
            // The recognizer stopped on its own while the button is still down; keep listening.
            restarts++
            listen()
            return
        }
        held = false
        val done = finish ?: return
        finish = null
        Log.i(TAG, "Press finished: loudest level ${loudestDb} dB, heard ${text().length} characters")
        main.removeCallbacks(stopAfterRelease)
        main.removeCallbacks(giveUp)
        done(text())
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(results: Bundle) {
            val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (heard.isBlank()) return
            partial = heard
            onPartial(text())
        }

        override fun onResults(results: Bundle) {
            val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            partial = ""
            if (heard.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(heard.trim())
            }
            onPartial(text())
            settle()
        }

        override fun onError(error: Int) {
            Log.w(TAG, "Speech recognizer error $error")
            // A session this class cancelled reports ERROR_CLIENT on this phone. It is not a
            // failure and must not switch recognizers (measured on device).
            if (!listening) return
            if (onDevice && error in UNSUPPORTED_ON_DEVICE) {
                // This phone's on-device recognizer lacks the language; use the system one instead.
                onDevice = false
                recognizer?.destroy()
                recognizer = null
            }
            // Keep whatever partial text was heard; an error at release usually just means silence.
            if (partial.isNotBlank()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(partial.trim())
                partial = ""
            }
            if (held && error in RETRYABLE_WHILE_HELD) {
                settle()
            } else {
                held = false
                settle()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (isReady) return
            isReady = true
            onReady()
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > loudestDb) loudestDb = rmsdB
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "SpeechInput"
        const val HOLD_SILENCE_MS = 10_000L
        const val RELEASE_TAIL_MS = 600L
        const val FINISH_TIMEOUT_MS = 4_000L
        const val MAX_RESTARTS = 3
        val UNSUPPORTED_ON_DEVICE = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
        val RETRYABLE_WHILE_HELD = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
        )
    }
}
