package com.example.test_project.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.test_project.contract.RecordedAudio
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Records the microphone for as long as the button is held.
 *
 * It is captured at 16 kHz mono PCM16
 */
class AudioCapture {

    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val sink = ByteArrayOutputStream()

    @Volatile
    private var capturing = false

    /** Begins capture. Caller must already hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (capturing) return true

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord reports no usable buffer size")
            return false
        }
        val bufferBytes = minBuffer * 2

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                bufferBytes,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not construct AudioRecord", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialise")
            record.release()
            return false
        }

        sink.reset()
        recorder = record
        capturing = true
        try { record.startRecording() } catch (error: Exception) {
            capturing = false
            recorder = null
            record.release()
            Log.w(TAG, "Microphone unavailable", error)
            return false
        }

        worker = thread(name = "audio-capture") {
            val chunk = ByteArray(bufferBytes)
            while (capturing && recorder === record) {
                val read = runCatching { record.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                if (read > 0) {
                    synchronized(sink) { sink.write(chunk, 0, read) }
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read returned $read")
                    break
                }
            }
        }
        return true
    }

    /** Ends capture and scores the result for speech. Returns null if nothing was recorded. */
    fun stop(): RecordedAudio? {
        if (!capturing) return null
        capturing = false

        runCatching { recorder?.stop() }
        worker?.join(STOP_JOIN_MS)
        worker = null

        recorder?.release()
        recorder = null

        val pcm = synchronized(sink) { sink.toByteArray() }
        if (pcm.isEmpty()) return null

        val durationMs = pcm.size.toLong() * 1000 / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        val vad = score(pcm)
        // Every route out of a press that heard nothing looks the same to the user, so the levels
        // that decided it are logged: a peak near zero is a dead microphone, a peak well over the
        // threshold with no voiced frames is the detector being wrong.
        Log.d(
            TAG,
            "clip ${durationMs}ms voiced=${vad.voicedMs}ms peak=${vad.peak.toInt()} " +
                "floor=${vad.floor.toInt()} threshold=${vad.threshold.toInt()}",
        )
        return RecordedAudio(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            durationMs = durationMs,
            hasSpeech = vad.voicedMs >= MIN_VOICED_MS,
            voicedMs = vad.voicedMs,
            peak = vad.peak,
        )
    }

    fun release() {
        capturing = false
        runCatching { recorder?.stop() }
        worker?.join(STOP_JOIN_MS)
        worker = null
        recorder?.release()
        recorder = null
    }

    /** Energy-based voice activity detection. */
    private fun score(pcm: ByteArray): Vad {
        val samplesPerFrame = SAMPLE_RATE * FRAME_MS / 1000
        val frameBytes = samplesPerFrame * BYTES_PER_SAMPLE
        val frameCount = pcm.size / frameBytes
        if (frameCount < 3) return Vad(0, 0.0, 0.0, ABSOLUTE_FLOOR)

        val energies = DoubleArray(frameCount)
        for (f in 0 until frameCount) {
            var sumSq = 0.0
            val base = f * frameBytes
            for (s in 0 until samplesPerFrame) {
                val i = base + s * 2
                // PCM16 little-endian.
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                sumSq += sample.toDouble() * sample
            }
            energies[f] = kotlin.math.sqrt(sumSq / samplesPerFrame)
        }

        val floor = energies.sorted()[frameCount / 5]
        val threshold = maxOf(floor * NOISE_MULTIPLE, ABSOLUTE_FLOOR)
        val voicedFrames = energies.count { it > threshold }
        return Vad(
            voicedMs = voicedFrames.toLong() * FRAME_MS,
            peak = energies.max(),
            floor = floor,
            threshold = threshold,
        )
    }

    /** What [score] measured, kept together so the decision can be logged as well as used. */
    private data class Vad(
        val voicedMs: Long,
        val peak: Double,
        val floor: Double,
        val threshold: Double,
    )

    companion object {
        private const val TAG = "AudioCapture"

        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        private const val FRAME_MS = 20
        private const val STOP_JOIN_MS = 500L

        /** Roughly a syllable. Below this, treat the press as silence. */
        private const val MIN_VOICED_MS = 150L
        private const val NOISE_MULTIPLE = 3.0

        /** Keeps a dead-silent room from making its own noise floor look like speech. */
        private const val ABSOLUTE_FLOOR = 350.0

    }
}
