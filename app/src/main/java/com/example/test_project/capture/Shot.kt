package com.example.test_project.capture

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.example.test_project.contract.ReadingMode
import com.example.test_project.contract.RecordedAudio

class Shot(
    val frame: Bitmap,
    val sharpness: Double,
    var audio: RecordedAudio? = null,
    val mode: ReadingMode = ReadingMode.TEXT,
) {
    val additionalFrames = mutableListOf<Bitmap>()
    var completedResponse: String = ""
    var completedBlocks: List<com.example.test_project.contract.TextBlock> = emptyList()
    var lastRequestCompleted: Boolean = false
}

class ShotViewModel : ViewModel() {
    var shot: Shot? = null
        private set
    var addingView: Boolean = false

    fun hold(shot: Shot) {
        if (addingView && this.shot != null) {
            this.shot?.additionalFrames?.let { frames ->
                frames.add(shot.frame)
                while (frames.size > 3) frames.removeAt(0).recycle()
            }
            addingView = false
        } else {
            this.shot?.let(::recycle)
            this.shot = shot
        }
    }

    fun beginAdditionalView() { addingView = true }

    fun clearSubject() {
        addingView = false
        shot?.let(::recycle)
        shot = null
    }

    private fun recycle(value: Shot) {
        if (!value.frame.isRecycled) value.frame.recycle()
        value.additionalFrames.forEach { if (!it.isRecycled) it.recycle() }
        value.additionalFrames.clear()
    }

    override fun onCleared() {
        clearSubject()
    }
}
