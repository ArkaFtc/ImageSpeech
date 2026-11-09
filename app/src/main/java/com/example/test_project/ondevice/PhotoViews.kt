package com.example.test_project.ondevice

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Gemma's vision encoder shrinks every image to about 2,520 16-pixel patches (roughly 672 x 912
 * for a portrait photo), so small print in a whole-page photo is only a few pixels tall. Overlapping
 * tiles of the same photo each get that full budget, so text in them is seen larger.
 */
object PhotoViews {
    /**
     * Splits [source] into [count] overlapping strips along its longer edge. Each strip is
     * [overlap] (a fraction of the strip) longer than an even split, so a line of text cut by one
     * boundary appears whole in the neighbouring strip.
     */
    fun strips(source: Bitmap, count: Int, overlap: Float = 0.15f): List<Bitmap> {
        require(count >= 1)
        if (count == 1) return listOf(source)
        val vertical = source.height >= source.width
        val long = if (vertical) source.height else source.width
        val step = long.toFloat() / count
        val length = (step * (1 + overlap)).roundToInt().coerceAtMost(long)
        return (0 until count).map { i ->
            val start = ((i * step) - (length - step) / 2).roundToInt().coerceIn(0, long - length)
            if (vertical) Bitmap.createBitmap(source, 0, start, source.width, length)
            else Bitmap.createBitmap(source, start, 0, length, source.height)
        }
    }

    /** Four overlapping quarters, in reading order: top left, top right, bottom left, bottom right. */
    fun quarters(source: Bitmap, overlap: Float = 0.15f): List<Bitmap> {
        val w = (source.width / 2f * (1 + overlap)).roundToInt().coerceAtMost(source.width)
        val h = (source.height / 2f * (1 + overlap)).roundToInt().coerceAtMost(source.height)
        return listOf(0 to 0, source.width - w to 0, 0 to source.height - h, source.width - w to source.height - h)
            .map { (x, y) -> Bitmap.createBitmap(source, x, y, w, h) }
    }
}
