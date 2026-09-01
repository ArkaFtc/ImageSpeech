package com.example.test_project

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Records the microphone for as long as the button is held.
 *
 * The clip is the source of truth for routing and, once the model's audio tower is wired up, the
 * question itself. It is captured at 16 kHz mono PCM16 because that is what both the on-device
 * recognizer and the model's audio encoder expect - resampling later would be pure loss.
 */
class AudioCapture {

    /**
     * One press worth of microphone.
     *
     * [hasSpeech] is the routing signal. Hold duration is deliberately not part of it: a fumbled
     * 1.2 s press with no speech in it must not reach the model, and a crisp 0.8 s question must
     * not be discarded. Duration decides only the deliberate tap, upstream in [IntentRouter].
     */
    data class Clip(
        val pcm: ByteArray,
        val sampleRate: Int,
        val durationMs: Long,
        val hasSpeech: Boolean,
        val voicedMs: Long,
        /** RMS of the loudest frame, on the PCM16 scale. See [isSilent]. */
        val peak: Double,
    ) {
        /**
         * Whether the microphone handed back nothing at all, as opposed to a quiet room.
         *
         * A muted mic, a revoked permission and an emulator with no host audio input all record a
         * clip of digital silence, which is indistinguishable from a held press with no question
         * in it unless the level is looked at. They need very different things said about them.
         */
        val isSilent: Boolean get() = peak < ABSOLUTE_FLOOR

        /** Writes the clip as a WAV file, which is what the recognizer wants to be handed. */
        fun writeWav(target: File) {
            FileOutputStream(target).use { out ->
                out.write(wavHeader(pcm.size, sampleRate))
                out.write(pcm)
            }
        }

        // Data classes with an array member need these written out to compare by content.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Clip) return false
            return pcm.contentEquals(other.pcm) &&
                sampleRate == other.sampleRate &&
                durationMs == other.durationMs &&
                hasSpeech == other.hasSpeech &&
                voicedMs == other.voicedMs &&
                peak == other.peak
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + hasSpeech.hashCode()
            result = 31 * result + voicedMs.hashCode()
            result = 31 * result + peak.hashCode()
            return result
        }
    }

    private var recorder: AudioRecord? = null
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
        } catch (e: IllegalArgumentException) {
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
        record.startRecording()

        worker = thread(name = "audio-capture") {
            val chunk = ByteArray(bufferBytes)
            while (capturing) {
                val read = record.read(chunk, 0, chunk.size)
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
    fun stop(): Clip? {
        if (!capturing) return null
        capturing = false

        worker?.join(STOP_JOIN_MS)
        worker = null

        recorder?.let { record ->
            runCatching { record.stop() }.onFailure { Log.w(TAG, "AudioRecord.stop failed", it) }
            record.release()
        }
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
        return Clip(
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
        worker?.join(STOP_JOIN_MS)
        worker = null
        recorder?.release()
        recorder = null
    }

    /**
     * Energy-based voice activity detection.
     *
     * The threshold is derived from the clip's own quiet frames rather than fixed, so a noisy room
     * raises the bar instead of reading as continuous speech. This is not trying to be a real VAD -
     * it only has to separate "the user said something" from "the user held the button in silence",
     * and both failure directions land on a route that still does something sensible.
     */
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

        private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
            val byteRate = sampleRate * BYTES_PER_SAMPLE
            val header = ByteArray(44)
            fun ascii(offset: Int, text: String) {
                for (i in text.indices) header[offset + i] = text[i].code.toByte()
            }
            fun int32(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = (value shr 8 and 0xFF).toByte()
                header[offset + 2] = (value shr 16 and 0xFF).toByte()
                header[offset + 3] = (value shr 24 and 0xFF).toByte()
            }
            fun int16(offset: Int, value: Int) {
                header[offset] = (value and 0xFF).toByte()
                header[offset + 1] = (value shr 8 and 0xFF).toByte()
            }

            ascii(0, "RIFF")
            int32(4, 36 + dataBytes)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            int32(16, 16)          // PCM chunk size
            int16(20, 1)           // PCM format
            int16(22, 1)           // mono
            int32(24, sampleRate)
            int32(28, byteRate)
            int16(32, BYTES_PER_SAMPLE)
            int16(34, 16)          // bits per sample
            ascii(36, "data")
            int32(40, dataBytes)
            return header
        }
    }
}
