package com.example.test_project.contract

import java.io.File

data class RecordedAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val durationMs: Long,
    val hasSpeech: Boolean,
    val voicedMs: Long,
    /** RMS of the loudest frame, on the PCM16 scale. See [isSilent]. */
    val peak: Double,
) {
    /** Whether the microphone handed back nothing at all, as opposed to a quiet room. */
    val isSilent: Boolean get() = peak < ABSOLUTE_FLOOR

    /** Writes the clip as a WAV file, for the model audio input. */
    fun writeWav(target: File) {
        target.writeBytes(toWavBytes())
    }

    fun toWavBytes(): ByteArray = wavHeader(pcm.size, sampleRate) + pcm

    // Data classes with an array member need these written out to compare by content.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordedAudio) return false
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
    companion object {
        private const val ABSOLUTE_FLOOR = 350.0
        private const val BYTES_PER_SAMPLE = 2
        private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray =
            java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataBytes)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1) // PCM
                putShort(1) // Mono
                putInt(sampleRate)
                putInt(sampleRate * BYTES_PER_SAMPLE)
                putShort(BYTES_PER_SAMPLE.toShort())
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
            }.array()
    }
}
