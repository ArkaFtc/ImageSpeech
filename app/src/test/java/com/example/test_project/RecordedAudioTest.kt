package com.example.test_project

import com.example.test_project.contract.RecordedAudio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedAudioTest {
    @Test fun `model audio is a mono PCM16 WAV with intact samples`() {
        val samples = byteArrayOf(0, 0, -1, 127, 0, -128)
        val clip = RecordedAudio(samples, 16000, 1, true, 1, 1000.0)
        val file = File.createTempFile("recording-test", ".wav")
        try {
            clip.writeWav(file)
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(bytes.size - 8, header.getInt(4))
            assertEquals(1, header.getShort(22).toInt())
            assertEquals(16000, header.getInt(24))
            assertEquals(16, header.getShort(34).toInt())
            assertEquals(samples.size, header.getInt(40))
            assertArrayEquals(samples, bytes.copyOfRange(44, bytes.size))
        } finally { file.delete() }
    }
}
