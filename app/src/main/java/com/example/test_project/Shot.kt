package com.example.test_project

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel

/**
 * One capture: the frame that was kept, and what the reader made of it.
 *
 * The frame is retained past OCR because the model lane still needs it - a question about the shot
 * is asked against the picture, not against the text that came out of it.
 */
class Shot(
    val frame: Bitmap,
    val ocr: LocalPPOCRv6Runner.OcrResult,
    val sharpness: Double,
) {
    val blocks: List<TextBlock> get() = ocr.blocks
}

/**
 * Carries the capture from the camera screen to the review screen.
 *
 * A ViewModel rather than an argument bundle for the obvious reason - a full-resolution frame does
 * not fit through a Binder transaction - and for a less obvious one: the review screen has to
 * survive a rotation without sending the user back to the camera to take the photo again.
 */
class ShotViewModel : ViewModel() {

    var shot: Shot? = null
        private set

    /**
     * Replaces the held capture.
     *
     * The displaced frame is dropped rather than recycled. A turn opened on the review screen may
     * still be encoding it on a background thread, and cancellation only takes effect at the next
     * suspension point - so recycling here would occasionally free a bitmap out from under a JNI
     * call. One collectable frame per photo is a cheap price for that not happening.
     */
    fun hold(shot: Shot) {
        this.shot = shot
    }

    override fun onCleared() {
        shot = null
        super.onCleared()
    }
}
