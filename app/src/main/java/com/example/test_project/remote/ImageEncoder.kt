package com.example.test_project.remote

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class EncodedImage(val bytes: ByteArray, val mimeType: String = "image/jpeg")

object ImageEncoder {
    fun encode(source: Bitmap): EncodedImage {
        val longest = maxOf(source.width, source.height)
        val scale = minOf(1f, GeminiConfig.IMAGE_LONG_EDGE.toFloat() / longest)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt(),
                (source.height * scale).roundToInt(),
                true,
            )
        } else source
        return try {
            var result = ByteArray(0)
            for (quality in listOf(GeminiConfig.JPEG_QUALITY, 80, 70, 60)) {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
                result = output.toByteArray()
                if (result.size <= GeminiConfig.MAX_IMAGE_BYTES) break
            }
            check(result.size <= GeminiConfig.MAX_IMAGE_BYTES) { "Photo is too detailed to upload safely. Try a closer view." }
            EncodedImage(result)
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }
}
