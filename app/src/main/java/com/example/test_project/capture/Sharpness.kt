package com.example.test_project.capture

import android.graphics.Bitmap
import androidx.core.graphics.scale

/** Blur detection for captured frames. */
object Sharpness {

    private const val WORK_WIDTH = 320

    /**
     * Below this, every frame in the burst was blurred and the caller should say so rather than
     * reading garbage. Calibrated against downscaled grayscale input; expect a steady page to score
     * in the hundreds and a badly smeared one in the low tens.
     */
    const val USABLE_THRESHOLD = 45.0

    /** Variance of the Laplacian response. Higher is sharper. */
    fun score(bitmap: Bitmap): Double {
        val work = downscale(bitmap)
        val w = work.width
        val h = work.height
        if (w < 3 || h < 3) return 0.0

        val pixels = IntArray(w * h)
        work.getPixels(pixels, 0, w, 0, 0, w, h)
        if (work !== bitmap) work.recycle()

        // Luma, integer-weighted so the whole pass stays in int arithmetic.
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 151 + (p and 0xFF) * 28) shr 8
        }

        // 4-neighbour Laplacian over the interior; Welford would be overkill for one pass.
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val response = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i])
                sum += response
                sumSq += response.toDouble() * response
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n) - (mean * mean)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= WORK_WIDTH) return bitmap
        val height = (bitmap.height.toLong() * WORK_WIDTH / bitmap.width).toInt().coerceAtLeast(1)
        return bitmap.scale(WORK_WIDTH, height)
    }
}
