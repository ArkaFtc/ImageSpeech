package com.example.test_project.processing.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.test_project.contract.RecordedAudio
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Turns a recorded clip into text, without touching the microphone. */
class Transcriber(private val context: Context) {

    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Returns the best transcript for [clip], or null if recognition is unavailable or failed. */
    suspend fun transcribe(clip: RecordedAudio): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !isAvailable) return null

        val pcmFile = runCatching {
            File.createTempFile("question", ".pcm", context.cacheDir)
        }.getOrNull() ?: return null

        return try {
            withContext(Dispatchers.IO) { pcmFile.writeBytes(clip.pcm) }
            withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) { recognize(pcmFile, clip.sampleRate) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { pcmFile.delete() }
        }
    }

    /**
     * [SpeechRecognizer] is main-thread-only for both construction and calls, and reports through a
     * listener, so it is bridged into a suspending call here.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(pcmFile: File, sampleRate: Int): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrElse {
                Log.w(TAG, "Could not create on-device recognizer", it)
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val descriptor = runCatching {
                ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
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
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
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
