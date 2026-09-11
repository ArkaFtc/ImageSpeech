package com.example.test_project.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.example.test_project.contract.SpokenAudio
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Playback is capture-side: cancelling the collector immediately releases the speaker. */
class AudioPlayer(private val context: Context) {
    suspend fun play(audio: SpokenAudio, onStarted: () -> Unit = {}) {
        val file = File.createTempFile("playback", ".wav", context.cacheDir)
        val player = MediaPlayer()
        val manager = context.getSystemService(AudioManager::class.java)
        var focus: AudioFocusRequest? = null
        try {
            withContext(Dispatchers.IO) { file.writeBytes(audio.wav) }
            val attributes =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            player.setAudioAttributes(attributes)
            player.setDataSource(file.path)
            suspendCancellableCoroutine<Unit> { continuation ->
                focus =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener { change ->
                            if (change == AudioManager.AUDIOFOCUS_LOSS && continuation.isActive) {
                                continuation.cancel()
                            }
                        }
                        .build()
                if (
                    manager.requestAudioFocus(checkNotNull(focus)) !=
                        AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                ) {
                    continuation.resumeWithException(
                        IllegalStateException("Audio output is unavailable")
                    )
                    return@suspendCancellableCoroutine
                }
                player.setOnPreparedListener {
                    it.start()
                    audio.readingStartedAtMs?.let { started ->
                        Log.i(
                            "ReadingTiming",
                            "playbackStartMs=${SystemClock.elapsedRealtime()-started}",
                        )
                    }
                    onStarted()
                }
                player.setOnCompletionListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                player.setOnErrorListener { _, what, extra ->
                    if (continuation.isActive)
                        continuation.resumeWithException(
                            IllegalStateException("Audio playback failed: $what/$extra")
                        )
                    true
                }
                player.prepareAsync()
            }
        } finally {
            player.release()
            focus?.let(manager::abandonAudioFocusRequest)
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }
}
