package com.example.test_project.processing.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.test_project.contract.TextBlock
import com.example.test_project.contract.TextBox
import java.nio.FloatBuffer
import kotlin.math.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Detector → rotated quadrilaterals → rectified line crops → CTC recognition. DocumentReader
 * serializes calls and owns the lifetime of these sessions.
 */
class LocalPPOCRv6Runner(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val dictionary =
        listOf("blank") +
            context.assets.open("pp_ocr_keys.txt").bufferedReader().use { it.readLines() } +
            " "

    init {
        OpenCv.requireLoaded()
        detSession = env.createSession(ModelAssets.path(context, "pp_ocrv6_det.onnx"))
        try {
            recSession = env.createSession(ModelAssets.path(context, "pp_ocrv6_rec.onnx"))
        } catch (error: Throwable) {
            detSession.close()
            throw error
        }
    }

    data class Region(
        override val text: String,
        override val left: Int,
        override val top: Int,
        override val right: Int,
        override val bottom: Int,
        val confidence: Float = 1f,
    ) : TextLine

    data class OcrResult(
        val regions: List<Region>,
        val message: String? = null,
        val documentBlocks: List<TextBlock>? = null,
    ) {
        val hasText
            get() = documentBlocks?.isNotEmpty() ?: regions.isNotEmpty()

        val lines
            get() = documentBlocks?.map { it.text } ?: regions.map { it.text }

        val blocks: List<TextBlock> by lazy { documentBlocks ?: TextBlocks.group(regions) }
        val displayText
            get() = if (hasText) lines.joinToString("\n") else message ?: "No text detected"
    }

    private data class Detection(val points: Array<Point>) : TextBox {
        override val left
            get() = points.minOf { it.x }.roundToInt()

        override val top
            get() = points.minOf { it.y }.roundToInt()

        override val right
            get() = points.maxOf { it.x }.roundToInt()

        override val bottom
            get() = points.maxOf { it.y }.roundToInt()
    }

    fun runOcr(bitmap: Bitmap): OcrResult =
        OcrResult(
            detect(bitmap, false)
                .map { recognize(bitmap, it) }
                .filter { it.text.isNotBlank() && it.confidence >= .7f }
        )

    /** Cooperatively cancellable between native inferences; each line arrives immediately. */
    suspend fun readPage(bitmap: Bitmap, document: Boolean, emit: suspend (Region) -> Unit) {
        currentCoroutineContext().ensureActive()
        val started = SystemClock.elapsedRealtime()
        val detections = detect(bitmap, document)
        Log.i(
            "ReadingTiming",
            "detectionMs=${SystemClock.elapsedRealtime()-started} candidates=${detections.size} size=${bitmap.width}x${bitmap.height}",
        )
        for (detection in detections) {
            currentCoroutineContext().ensureActive()
            var region = recognize(bitmap, detection)
            if (region.confidence < .7f) {
                currentCoroutineContext().ensureActive()
                val retry = recognize(bitmap, detection, 1.18)
                if (retry.confidence > region.confidence) region = retry
            }
            emit(region)
        }
    }

    private fun detect(bitmap: Bitmap, document: Boolean): List<Detection> {
        val scale = min(1.0, (if (document) 1280.0 else 960.0) / max(bitmap.width, bitmap.height))
        val w = max(32, (bitmap.width * scale / 32).roundToInt() * 32)
        val h = max(32, (bitmap.height * scale / 32).roundToInt() * 32)
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val values: FloatArray
        val mapW: Int
        val mapH: Int
        try {
            tensor(resized, floatArrayOf(.485f, .456f, .406f), floatArrayOf(.229f, .224f, .225f))
                .use { input ->
                    detSession.run(mapOf(detSession.inputNames.first() to input)).use { result ->
                        val output = result.get(0) as OnnxTensor
                        mapH = output.info.shape[2].toInt()
                        mapW = output.info.shape[3].toInt()
                        values = FloatArray(mapW * mapH)
                        output.floatBuffer.get(values)
                    }
                }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
        val binary = Mat(mapH, mapW, CvType.CV_8UC1)
        val probabilities = Mat(mapH, mapW, CvType.CV_32FC1)
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            binary.put(0, 0, ByteArray(values.size) { if (values[it] > .3f) 255.toByte() else 0 })
            probabilities.put(0, 0, values)
            Imgproc.findContours(
                binary,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
            val found = mutableListOf<Detection>()
            for (contour in contours.take(1000)) {
                val points = MatOfPoint2f(*contour.toArray())
                try {
                    val rect = Imgproc.minAreaRect(points)
                    if (min(rect.size.width, rect.size.height) < 3) continue
                    val mask = Mat.zeros(mapH, mapW, CvType.CV_8UC1)
                    val score =
                        try {
                            Imgproc.drawContours(
                                mask,
                                listOf(contour),
                                0,
                                Scalar(255.0),
                                Imgproc.FILLED,
                            )
                            Core.mean(probabilities, mask).`val`[0]
                        } finally {
                            mask.release()
                        }
                    if (score < .6) continue
                    // Bounding rectangle of the polygon's rounded offset, in its rotated frame.
                    val d = rect.size.area() * 1.5 / (2 * (rect.size.width + rect.size.height))
                    rect.size.width += 2 * d
                    rect.size.height += 2 * d
                    val quad = Array(4) { Point() }
                    rect.points(quad)
                    found +=
                        Detection(
                            order(
                                quad
                                    .map {
                                        Point(
                                            (it.x * bitmap.width / mapW).coerceIn(
                                                0.0,
                                                bitmap.width - 1.0,
                                            ),
                                            (it.y * bitmap.height / mapH).coerceIn(
                                                0.0,
                                                bitmap.height - 1.0,
                                            ),
                                        )
                                    }
                                    .toTypedArray()
                            )
                        )
                } finally {
                    points.release()
                }
            }
            return if (document) ColumnOrder.sort(found) else ReadingOrder.sort(found)
        } finally {
            contours.forEach { it.release() }
            binary.release()
            probabilities.release()
            hierarchy.release()
        }
    }

    private fun recognize(bitmap: Bitmap, detection: Detection, margin: Double = 1.0): Region {
        val points = detection.points.map { Point(it.x, it.y) }.toTypedArray()
        if (margin != 1.0) {
            val cx = points.map { it.x }.average()
            val cy = points.map { it.y }.average()
            points.forEach {
                it.x = cx + (it.x - cx) * margin
                it.y = cy + (it.y - cy) * margin
            }
        }
        fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val width =
            max(distance(points[0], points[1]), distance(points[3], points[2]))
                .roundToInt()
                .coerceAtLeast(2)
        val height =
            max(distance(points[0], points[3]), distance(points[1], points[2]))
                .roundToInt()
                .coerceAtLeast(2)
        val source = Mat()
        val crop = Mat()
        val from = MatOfPoint2f(*points)
        val to =
            MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0),
                Point(0.0, height - 1.0),
            )
        val transform = Imgproc.getPerspectiveTransform(from, to)
        val decoded: Pair<String, Float>
        try {
            Utils.bitmapToMat(bitmap, source)
            Imgproc.warpPerspective(
                source,
                crop,
                transform,
                Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE,
            )
            if (height > width * 1.5) Core.rotate(crop, crop, Core.ROTATE_90_COUNTERCLOCKWISE)
            val targetWidth = ceil(48.0 * crop.cols() / crop.rows()).toInt().coerceIn(16, 1600)
            val resized = Mat()
            try {
                Imgproc.resize(crop, resized, Size(targetWidth.toDouble(), 48.0))
                val normalized = Bitmap.createBitmap(targetWidth, 48, Bitmap.Config.ARGB_8888)
                try {
                    Utils.matToBitmap(resized, normalized)
                    tensor(normalized, floatArrayOf(.5f, .5f, .5f), floatArrayOf(.5f, .5f, .5f))
                        .use { input ->
                            recSession.run(mapOf(recSession.inputNames.first() to input)).use {
                                result ->
                                val output = result.get(0) as OnnxTensor
                                val shape = output.info.shape
                                val scores = FloatArray(shape[1].toInt() * shape[2].toInt())
                                output.floatBuffer.get(scores)
                                decoded = decode(scores, shape[1].toInt(), shape[2].toInt())
                            }
                        }
                } finally {
                    normalized.recycle()
                }
            } finally {
                resized.release()
            }
        } finally {
            source.release()
            crop.release()
            from.release()
            to.release()
            transform.release()
        }
        return Region(
            decoded.first,
            detection.left,
            detection.top,
            detection.right,
            detection.bottom,
            decoded.second,
        )
    }

    private fun decode(scores: FloatArray, steps: Int, classes: Int): Pair<String, Float> {
        val text = StringBuilder()
        var total = 0f
        var count = 0
        var previous = 0
        for (t in 0 until steps) {
            val offset = t * classes
            var best = 0
            for (c in 1 until classes) if (scores[offset + c] > scores[offset + best]) best = c
            if (best != 0 && best != previous && best < dictionary.size) {
                text.append(dictionary[best])
                total += scores[offset + best]
                count++
            }
            previous = best
        }
        return text.toString() to if (count == 0) 0f else total / count
    }

    /** Paddle's OpenCV inference path reads BGR. */
    private fun tensor(bitmap: Bitmap, mean: FloatArray, std: FloatArray): OnnxTensor {
        val n = bitmap.width * bitmap.height
        val pixels = IntArray(n)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val data = FloatArray(n * 3)
        for (i in 0 until n) for (c in 0..2) data[c * n + i] =
            (((pixels[i] shr (c * 8)) and 255) / 255f - mean[c]) / std[c]
        return OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(data),
            longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong()),
        )
    }

    private fun order(points: Array<Point>): Array<Point> {
        val sorted = points.sortedBy { it.x }
        val left = sorted.take(2).sortedBy { it.y }
        val right = sorted.takeLast(2).sortedBy { it.y }
        return arrayOf(left[0], right[0], right[1], left[1])
    }

    override fun close() {
        detSession.close()
        recSession.close()
    }
}
