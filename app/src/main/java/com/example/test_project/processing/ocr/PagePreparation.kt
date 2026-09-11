package com.example.test_project.processing.ocr

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Bounded working resolution plus conservative background removal. Never mutates the capture. */
internal object PagePreparation {
    fun spine(source: Bitmap): Int {
        val width = minOf(600, source.width)
        val height = (source.height.toDouble() * width / source.width).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        try {
            val pixels = IntArray(width * height)
            small.getPixels(pixels, 0, width, 0, 0, width, height)
            fun luma(pixel: Int) =
                ((pixel shr 16 and 255) * 77 + (pixel shr 8 and 255) * 151 + (pixel and 255) * 28) /
                    256.0
            val top = (height * .12).toInt()
            val bottom = (height * .82).toInt().coerceAtMost(height - 1)
            val profile =
                DoubleArray(width) { x ->
                    (top until bottom).sumOf { y ->
                        kotlin.math.abs(
                            luma(pixels[y * width + x]) - luma(pixels[(y + 1) * width + x])
                        )
                    }
                }
            return (SpineLocator.locate(profile).toDouble() * source.width / width).roundToInt()
        } finally {
            if (small !== source) small.recycle()
        }
    }

    fun prepare(source: Bitmap, cropPage: Boolean): Bitmap {
        val scale = minOf(1.0, 2000.0 / max(source.width, source.height))
        var result =
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        if (!cropPage) return result
        OpenCv.requireLoaded()
        val smallScale = minOf(1.0, 600.0 / max(result.width, result.height))
        val small =
            Bitmap.createScaledBitmap(
                result,
                (result.width * smallScale).roundToInt().coerceAtLeast(1),
                (result.height * smallScale).roundToInt().coerceAtLeast(1),
                true,
            )
        val rgba = Mat()
        val gray = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 40.0, 120.0)
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val frameArea = small.width.toDouble() * small.height
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }) {
                if (Imgproc.contourArea(contour) < frameArea * .45) break
                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * .02, true)
                    if (approx.total() != 4L) continue
                    val polygon = MatOfPoint(*approx.toArray())
                    val bounds =
                        try {
                            if (!Imgproc.isContourConvex(polygon)) continue
                            Imgproc.boundingRect(polygon)
                        } finally {
                            polygon.release()
                        }
                    // Keep a margin around the detected sheet. UVDoc handles the remaining
                    // geometry.
                    val margin = max(small.width, small.height) * .02
                    val left = ((bounds.x - margin) / smallScale).toInt().coerceAtLeast(0)
                    val top = ((bounds.y - margin) / smallScale).toInt().coerceAtLeast(0)
                    val right =
                        ((bounds.x + bounds.width + margin) / smallScale)
                            .toInt()
                            .coerceAtMost(result.width)
                    val bottom =
                        ((bounds.y + bounds.height + margin) / smallScale)
                            .toInt()
                            .coerceAtMost(result.height)
                    if (right > left && bottom > top) {
                        val cropped =
                            Bitmap.createBitmap(result, left, top, right - left, bottom - top)
                        if (result !== source && result !== cropped && result !== small)
                            result.recycle()
                        result = cropped
                    }
                    break
                } finally {
                    curve.release()
                    approx.release()
                }
            }
        } finally {
            contours.forEach { it.release() }
            rgba.release()
            gray.release()
            edges.release()
            hierarchy.release()
            if (small !== source && small !== result) small.recycle()
        }
        return result
    }
}
