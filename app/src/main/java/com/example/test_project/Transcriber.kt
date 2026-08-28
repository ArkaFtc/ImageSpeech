package com.example.test_project

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Turns a recorded clip into text, without touching the microphone.
 *
 * The obvious approach - let [SpeechRecognizer] listen live while the button is held - cannot work
 * here, because [AudioCapture] already owns the mic and the model needs that raw clip for its own
 * audio input. So recognition is fed the recorded file instead, via the API 33 audio-source extras.
 *
 * On devices without that path the transcript is simply null. Nothing breaks: [IntentRouter] falls
 * back to the VAD signal, and a press with speech in it reaches the model regardless. The transcript
 * is a convenience for routing, logging and display - never a dependency.
 */
class Transcriber(private val context: Context) {

    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Returns the best transcript for [clip], or null if recognition is unavailable or failed. */
    suspend fun transcribe(clip: AudioCapture.Clip): String? {
        if (!isAvailable) return null

        val wav = withContext(Dispatchers.IO) {
            runCatching {
                File.createTempFile("question", ".wav", context.cacheDir).also(clip::writeWav)
            }.getOrNull()
        } ?: return null

        return try {
            withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) { recognize(wav) }
        } finally {
            withContext(Dispatchers.IO) { wav.delete() }
        }
    }

    /**
     * [SpeechRecognizer] is main-thread-only for both construction and calls, and reports through a
     * listener, so it is bridged into a suspending call here.
     */
    private suspend fun recognize(wav: File): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrElse {
                Log.w(TAG, "Could not create on-device recognizer", it)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val descriptor = runCatching {
                ParcelFileDescriptor.open(wav, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrElse {
                Log.w(TAG, "Could not open clip for recognition", it)
                recognizer.destroy()
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            // Guards against the listener firing twice - onResults after onError is legal.
            var settled = false
            fun settle(value: String?) {
                if (settled) return
                settled = true
                runCatching { descriptor.close() }
                recognizer.destroy()
                continuation.resume(value)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val best = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                    settle(best)
                }

                override fun onError(error: Int) {
                    Log.d(TAG, "Recognition error $error")
                    settle(null)
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            continuation.invokeOnCancellation { settle(null) }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptor)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioCapture.SAMPLE_RATE)
            }

            runCatching { recognizer.startListening(intent) }.onFailure {
                Log.w(TAG, "startListening failed", it)
                settle(null)
            }
        }
    }

    private companion object {
        const val TAG = "Transcriber"

        /** Recognition of a short held clip should be near-instant; this only catches a wedge. */
        const val RECOGNITION_TIMEOUT_MS = 5_000L
    }
}
