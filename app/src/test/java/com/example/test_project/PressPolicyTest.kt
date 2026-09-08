package com.example.test_project

import com.example.test_project.capture.PressPolicy
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PressPolicyTest {
    private val recordedClip = byteArrayOf(1, 2, 3)

    @Test fun `short taps never cross the boundary with audio`() {
        for (duration in listOf(0L, 1L, 120L, 399L)) {
            assertNull(PressPolicy.audioFor(duration, recordedClip))
        }
    }

    @Test fun `exactly 400 milliseconds includes the original recording`() {
        assertSame(recordedClip, PressPolicy.audioFor(400, recordedClip))
        assertSame(recordedClip, PressPolicy.audioFor(1200, recordedClip))
    }

    @Test fun `missing microphone is valid for a held press`() {
        assertNull(PressPolicy.audioFor<ByteArray>(1200, null))
    }
}
