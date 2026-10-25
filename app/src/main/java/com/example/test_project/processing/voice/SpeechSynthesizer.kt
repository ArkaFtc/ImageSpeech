package com.example.test_project.processing.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.test_project.contract.SpokenAudio
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** A passage could not be turned into speech, even after restarting the speech engine. */
class SpeechFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Produces WAV audio without playing it. Only one synthesis runs at a time. */
class SpeechSynthesizer(private val context: Context) {
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var ready = CompletableDeferred<Unit>()
    private var engine: TextToSpeech? = null
    private var prepared = false

    init {
        startEngine()
    }

    private fun startEngine() {
        val starting = CompletableDeferred<Unit>()
        ready = starting
        prepared = false
        engine =
            TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) starting.complete(Unit)
                else starting.completeExceptionally(SpeechFailure("Speech engine unavailable"))
            }
    }

    suspend fun prepare() = mutex.withLock { withTimeout(30_000) { configure() } }

    private suspend fun configure() {
        ready.await()
        val tts = checkNotNull(engine)
        if (prepared) return
        val language = tts.setLanguage(Locale.getDefault())
        if (language < 0) tts.setLanguage(Locale.US)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    id?.let(pending::remove)?.complete(Unit)
                }

                @Deprecated("Required by Android")
                override fun onError(id: String?) {
                    id?.let(pending::remove)?.completeExceptionally(SpeechFailure("Speech synthesis failed"))
                }

                override fun onError(id: String?, errorCode: Int) {
                    id?.let(pending::remove)?.completeExceptionally(SpeechFailure("Speech synthesis failed: $errorCode"))
                }

                override fun onStop(id: String?, interrupted: Boolean) {
                    id?.let(pending::remove)?.completeExceptionally(SpeechFailure("Speech synthesis stopped"))
                }
            }
        )
        prepared = true
    }

    /**
     * Turns [text] into speech. A passage that fails or never finishes is tried once more with a
     * restarted engine: the phone's speech service can be paused or killed in the background, and
     * a hung request used to time out as if the user had stopped, silencing the rest of the answer.
     * Throws [SpeechFailure] if it still fails; only the caller being cancelled throws a
     * [CancellationException].
     */
    suspend fun synthesize(text: String): SpokenAudio {
        // Nothing to say, such as a lone dash.
        if (text.none { it.isLetterOrDigit() }) return SpokenAudio(text, ByteArray(0))
        return mutex.withLock {
            try {
                attempt(text)
            } catch (failure: SpeechFailure) {
                Log.w(TAG, "Speech failed; restarting the engine and trying again", failure)
                engine?.shutdown()
                startEngine()
                attempt(text)
            }
        }
    }

    private suspend fun attempt(text: String): SpokenAudio =
        withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            try {
                configure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw SpeechFailure("Speech engine unavailable", error)
            }
            val tts = checkNotNull(engine)
            val file = File.createTempFile("speech", ".wav", context.cacheDir)
            val done = CompletableDeferred<Unit>()
            pending[file.name] = done
            try {
                if (tts.synthesizeToFile(text, null, file, file.name) != TextToSpeech.SUCCESS) {
                    throw SpeechFailure("Speech engine refused the passage")
                }
                done.await()
                val wav = withContext(Dispatchers.IO) { file.readBytes() }
                if (wav.size <= WAV_HEADER_BYTES) throw SpeechFailure("Speech engine produced no audio")
                SpokenAudio(text, wav)
            } finally {
                pending.remove(file.name)
                // Only an unfinished request needs stopping.
                if (!done.isCompleted) runCatching { tts.stop() }
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        } ?: throw SpeechFailure("Speech engine did not finish")

    fun close() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        engine?.shutdown()
        engine = null
    }

    private companion object {
        const val TAG = "SpeechSynthesizer"
        const val ATTEMPT_TIMEOUT_MS = 15_000L
        const val WAV_HEADER_BYTES = 44
    }
}
