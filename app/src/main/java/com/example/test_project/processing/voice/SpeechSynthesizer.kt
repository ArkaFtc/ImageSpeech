package com.example.test_project.processing.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.test_project.contract.SpokenAudio
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Produces WAV audio without playing it. Only one synthesis runs at a time. */
class SpeechSynthesizer(private val context: Context) {
    private val ready = CompletableDeferred<Unit>()
    private val mutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var engine: TextToSpeech? = null
    private var prepared = false

    init {
        engine =
            TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
                else ready.completeExceptionally(IllegalStateException("Speech engine unavailable"))
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
                    id?.let(pending::remove)
                        ?.completeExceptionally(IllegalStateException("Speech synthesis failed"))
                }
            }
        )
        prepared = true
    }

    suspend fun synthesize(text: String): SpokenAudio =
        mutex.withLock {
            withTimeout(30_000) {
                configure()
                val tts = checkNotNull(engine)
                val file = File.createTempFile("speech", ".wav", context.cacheDir)
                val done = CompletableDeferred<Unit>()
                pending[file.name] = done
                try {
                    check(tts.synthesizeToFile(text, null, file, file.name) == TextToSpeech.SUCCESS)
                    done.await()
                    SpokenAudio(text, withContext(Dispatchers.IO) { file.readBytes() })
                } finally {
                    pending.remove(file.name)
                    tts.stop()
                    withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                }
            }
        }

    fun close() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        engine?.shutdown()
        engine = null
    }
}
