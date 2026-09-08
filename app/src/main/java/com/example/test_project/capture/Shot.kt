package com.example.test_project.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.example.test_project.contract.RecordedAudio

class Shot(val frame: Bitmap, val sharpness: Double, var audio: RecordedAudio? = null)

class ShotViewModel : ViewModel() {
    var shot: Shot? = null
        private set
    fun hold(shot: Shot) { this.shot = shot }
}
