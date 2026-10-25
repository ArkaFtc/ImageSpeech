package com.example.test_project

import android.view.Surface
import com.example.test_project.capture.PhoneRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneRotationTest {
    @Test
    fun uprightIsNotRotated() {
        assertEquals(Surface.ROTATION_0, PhoneRotation.fromGravity(0f, 9.8f)!!.surfaceRotation)
    }

    @Test
    fun turnedClockwiseOrCounterclockwiseIsSideways() {
        // Top to the right: up is the phone's left edge, so gravity reads negative x.
        assertEquals(90, PhoneRotation.fromGravity(-9.8f, 0f)!!.degrees)
        assertEquals(Surface.ROTATION_270, PhoneRotation.fromGravity(-9.8f, 0f)!!.surfaceRotation)
        assertEquals(Surface.ROTATION_90, PhoneRotation.fromGravity(9.8f, 0f)!!.surfaceRotation)
        assertEquals(Surface.ROTATION_180, PhoneRotation.fromGravity(0f, -9.8f)!!.surfaceRotation)
    }

    @Test
    fun aPhoneAngledDownAtALabelStillCounts() {
        // Turned clockwise and tilted to about 15 degrees from flat.
        assertEquals(Surface.ROTATION_270, PhoneRotation.fromGravity(-2.5f, 0.2f)!!.surfaceRotation)
    }

    @Test
    fun aPhoneLyingFlatCannotTell() {
        // Measured on the test phone lying on a desk.
        assertNull(PhoneRotation.fromGravity(0.145f, -0.117f))
    }
}
