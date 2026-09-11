package com.example.test_project.processing.ocr

/**
 * Refines an explicitly selected book using a broad low-texture central gutter. Ambiguous profiles
 * retain the centered split; never searches beyond the central quarter.
 */
object SpineLocator {
    fun locate(verticalTexture: DoubleArray): Int {
        val width = verticalTexture.size
        if (width < 20) return width / 2
        val radius = (width / 160).coerceAtLeast(2)
        val profile =
            DoubleArray(width) { x ->
                val start = (x - radius).coerceAtLeast(0)
                val end = (x + radius).coerceAtMost(width - 1)
                (start..end).sumOf { verticalTexture[it] } / (end - start + 1)
            }
        val lo = (width * .38).toInt()
        val hi = (width * .62).toInt()
        val center = (lo..hi).minBy { profile[it] }
        val median = profile.sliceArray(lo..hi).sorted()[(hi - lo) / 2]
        if (profile[center] >= median * .25) return width / 2
        val threshold = profile[center] + (median - profile[center]) * .12
        var left = center
        var right = center
        while (left > lo && profile[left - 1] <= threshold) left--
        while (right < hi && profile[right + 1] <= threshold) right++
        return if (right - left >= width * .012) (left + right) / 2 else width / 2
    }
}
