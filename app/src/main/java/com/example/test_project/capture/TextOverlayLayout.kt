package com.example.test_project.capture

import com.example.test_project.contract.TextBlock
import kotlin.math.max
import kotlin.math.roundToInt

/** Projects OCR-space boxes onto a fit-center rendering of the original photograph. */
internal object TextOverlayLayout {
    data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)

    fun project(
        block: TextBlock,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        minTouchSize: Int,
    ): Bounds? {
        if (minOf(imageWidth, imageHeight, viewWidth, viewHeight) <= 0) return null
        val workingScale = minOf(1f, 2000f / max(imageWidth, imageHeight).toFloat())
        val workingWidth = (imageWidth * workingScale).roundToInt().coerceAtLeast(1)
        val workingHeight = (imageHeight * workingScale).roundToInt().coerceAtLeast(1)
        val fitScale = minOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        val imageLeft = (viewWidth - imageWidth * fitScale) / 2f
        val imageTop = (viewHeight - imageHeight * fitScale) / 2f
        val rawLeft = imageLeft + block.left * imageWidth.toFloat() / workingWidth * fitScale
        val rawTop = imageTop + block.top * imageHeight.toFloat() / workingHeight * fitScale
        val rawRight = imageLeft + block.right * imageWidth.toFloat() / workingWidth * fitScale
        val rawBottom = imageTop + block.bottom * imageHeight.toFloat() / workingHeight * fitScale
        val width = max((rawRight - rawLeft).roundToInt(), minTouchSize).coerceAtMost(viewWidth)
        val height = max((rawBottom - rawTop).roundToInt(), minTouchSize).coerceAtMost(viewHeight)
        val left = (rawLeft + (rawRight - rawLeft - width) / 2f).roundToInt().coerceIn(0, viewWidth - width)
        val top = (rawTop + (rawBottom - rawTop - height) / 2f).roundToInt().coerceIn(0, viewHeight - height)
        return Bounds(left, top, width, height)
    }
}
