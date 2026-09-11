package com.example.test_project.processing.ocr

import org.opencv.android.OpenCVLoader

internal object OpenCv {
    private val loaded by lazy { OpenCVLoader.initLocal() }

    fun requireLoaded() {
        check(loaded) { "Document image processing is unavailable" }
    }
}
