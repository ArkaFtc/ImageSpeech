package com.example.test_project.capture



object PressPolicy {
    const val AUDIO_THRESHOLD_MS = 400L
    fun <T> audioFor(heldMs: Long, clip: T?): T? = clip.takeIf { heldMs >= AUDIO_THRESHOLD_MS }
}
