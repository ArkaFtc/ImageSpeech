package com.example.test_project

import android.content.Context
import java.io.File

/**
 * Where the model lives on disk, and where it comes from.
 *
 * The weights cannot ship in the APK - they are an order of magnitude larger than the 138 MB of
 * ONNX already in assets, and far past what Play will accept. So the app fetches them once, on
 * first run, and keeps them in its own external files directory.
 *
 * The backend and the file are coupled: a `-gpu` bundle carries GPU weights only and fails on CPU
 * with "TF_LITE_PREFILL_DECODE not found in the model". Changing the backend in
 * [GemmaSceneAnswerer] means changing [MODEL_NAME] and [DOWNLOAD_URL] to match.
 */
class ModelStore(context: Context) {

    private val directory = File(context.getExternalFilesDir(null), "models")

    val modelFile: File get() = File(directory, MODEL_NAME)

    /** Download target. Renamed onto [modelFile] only once the bytes are all present. */
    val partialFile: File get() = File(directory, "$MODEL_NAME.part")

    /**
     * True only for a complete model. Checking the exact byte count rather than mere existence is
     * what stops a half-finished or interrupted download from being loaded as if it were the model
     * - the engine's failure mode for a truncated bundle is a native crash, not an exception.
     */
    val isPresent: Boolean get() = modelFile.length() == EXPECTED_BYTES

    /** Bytes already fetched, for resuming and for progress. */
    val downloadedBytes: Long get() = partialFile.length()

    fun ensureDirectory(): Boolean = directory.isDirectory || directory.mkdirs()

    /** Removes a partial download. Used when the server will not honour a resume. */
    fun discardPartial() {
        partialFile.delete()
    }

    companion object {
        /**
         * The GPU-backend build from Google's own `litert-community` repo, matching the
         * `Backend.GPU()` engine config. The open-source `litert-torch export_hf` path exports text
         * and vision only, so a self-converted model would come up without the audio tower - this
         * artifact has to be the official one.
         */
        const val MODEL_NAME = "gemma-4-E2B-it-gpu.litertlm"

        const val DOWNLOAD_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_NAME"

        /** Exact size of the published artifact, used as the completeness check. */
        const val EXPECTED_BYTES = 2_008_432_640L

        val EXPECTED_MEGABYTES: Int get() = (EXPECTED_BYTES / (1024 * 1024)).toInt()
    }
}
