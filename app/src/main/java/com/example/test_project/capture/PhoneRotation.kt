package com.example.test_project.capture

import android.view.Surface
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Which way up the phone is held, from gravity, so sideways photos can be saved upright. */
data class PhoneRotation(
    /** 0 upright, 90 turned clockwise (top to the right), 180 upside down, 270 turned counterclockwise. */
    val degrees: Int,
) {
    /** The camera's target rotation for this way up. */
    val surfaceRotation: Int
        get() = when (degrees) {
            in 45 until 135 -> Surface.ROTATION_270
            in 135 until 225 -> Surface.ROTATION_180
            in 225 until 315 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }

    companion object {
        /** Sideways gravity needed to tell which way up the phone is: about 10 degrees of tilt. */
        const val MIN_TILT_GRAVITY = 1.7f

        /**
         * From the accelerometer's x and y readings; null when the phone lies too flat to tell.
         * The same formula as OrientationEventListener, which negates the readings first.
         */
        fun fromGravity(x: Float, y: Float): PhoneRotation? {
            if (x * x + y * y < MIN_TILT_GRAVITY * MIN_TILT_GRAVITY) return null
            val angle = Math.toDegrees(atan2(y, -x).toDouble()).roundToInt()
            return PhoneRotation(((90 - angle) % 360 + 360) % 360)
        }
    }
}
