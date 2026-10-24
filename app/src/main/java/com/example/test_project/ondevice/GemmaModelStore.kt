package com.example.test_project.ondevice

import android.content.Context
import java.io.File

/**
 * Where the downloaded Gemma file lives. App-specific external storage is private to the app,
 * removed on uninstall, and not part of Android backup, which suits a 2.6 GB download.
 */
class GemmaModelStore(
    private val directory: File,
    private val expectedBytes: Long = GemmaConfig.EXPECTED_BYTES,
) {
    constructor(context: Context) : this(
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models"),
    )

    val modelFile: File get() = File(directory, GemmaConfig.MODEL_NAME)

    /** Download target, renamed onto [modelFile] only once every byte is present. */
    val partialFile: File get() = File(directory, "${GemmaConfig.MODEL_NAME}.part")

    /** Exact length check: loading a truncated bundle crashes in native code rather than throwing. */
    val isPresent: Boolean get() = modelFile.length() == expectedBytes

    val downloadedBytes: Long get() = partialFile.length()

    fun ensureDirectory(): Boolean = directory.isDirectory || directory.mkdirs()

    fun discardPartial() {
        partialFile.delete()
    }

    /** Moves a complete download into place. Returns false for an incomplete one. */
    fun promotePartial(): Boolean {
        if (partialFile.length() != expectedBytes) return false
        modelFile.delete()
        return partialFile.renameTo(modelFile)
    }

    fun delete() {
        modelFile.delete()
        partialFile.delete()
    }
}
