package com.example.test_project.processing.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/** Official dynamic-resolution UVDoc export; the graph includes bilinear reconstruction. */
internal class UvDocRunner(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(ModelAssets.path(context, "uvdoc.onnx"))

    fun flatten(bitmap: Bitmap): Bitmap {
        val count = bitmap.width * bitmap.height
        val pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val data = FloatArray(count * 3)
        for (i in pixels.indices) for (c in 0..2) data[c * count + i] =
            ((pixels[i] shr (8 * c)) and 255) / 255f
        OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(data),
                longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong()),
            )
            .use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
                    val output = result.get(0) as OnnxTensor
                    val shape = output.info.shape
                    check(
                        shape.contentEquals(
                            longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong())
                        )
                    )
                    val values = FloatArray(count * 3)
                    output.floatBuffer.get(values)
                    for (i in pixels.indices) {
                        var pixel = 255 shl 24
                        for (c in 0..2) {
                            val value = values[c * count + i]
                            check(value.isFinite()) { "Invalid document correction output" }
                            pixel =
                                pixel or ((value * 255).toInt().coerceIn(0, 255) shl (16 - 8 * c))
                        }
                        pixels[i] = pixel
                    }
                    return Bitmap.createBitmap(
                        pixels,
                        bitmap.width,
                        bitmap.height,
                        Bitmap.Config.ARGB_8888,
                    )
                }
            }
    }

    override fun close() = session.close()
}
