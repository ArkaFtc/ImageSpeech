package com.example.test_project.processing.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.test_project.contract.TextBlock
import com.example.test_project.contract.TextBox
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** PP-OCR pipeline: DBNet detection -> box extraction -> CRNN/CTC recognition. */
class LocalPPOCRv6Runner(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val characterDict = mutableListOf<String>()

    private val detInputName: String
    private val recInputName: String

    init {
        // Copy models to cache to load via direct file path (bypasses Java Heap OOM)
        val detModelPath = copyAssetToCache("pp_ocrv6_det.onnx")
        detSession = env.createSession(detModelPath)

        val recModelPath = copyAssetToCache("pp_ocrv6_rec.onnx")
        recSession = env.createSession(recModelPath)

        detInputName = detSession.inputNames.first()
        recInputName = recSession.inputNames.first()

        // Character set is ["blank"] + dict lines + [" "], matching the 18710 rec classes.
        context.assets.open("pp_ocr_keys.txt").bufferedReader().useLines { lines ->
            characterDict.add(BLANK_TOKEN)
            lines.forEach { characterDict.add(it) }
            characterDict.add(" ")
        }
        Log.i(TAG, "Character set size: ${characterDict.size}")
    }

    private fun copyAssetToCache(fileName: String): String {
        val file = File(context.cacheDir, fileName)
        if (!file.exists()) {
            context.assets.open(fileName).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }

    /** Axis-aligned text box. */
    private data class Box(
        override val left: Int,
        override val top: Int,
        override val right: Int,
        override val bottom: Int,
    ) : TextBox {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** One detected region in both of the shapes it is needed in. */
    private data class Detection(val tight: Box, val padded: Box) : TextBox {
        override val left get() = tight.left
        override val top get() = tight.top
        override val right get() = tight.right
        override val bottom get() = tight.bottom
    }

    /** One recognized region: its text and where on the frame it sat. */
    data class Region(
        override val text: String,
        override val left: Int,
        override val top: Int,
        override val right: Int,
        override val bottom: Int,
    ) : TextLine

    /**
     * Outcome of one OCR pass. [regions] holds the recognized text in reading order and is empty
     * when nothing was read - in which case [message] explains why. Keeping the two apart lets the
     * caller show status text without reading it aloud.
     */
    data class OcrResult(val regions: List<Region>, val message: String? = null) {
        val hasText: Boolean get() = regions.isNotEmpty()

        /** Just the text, in reading order. */
        val lines: List<String> get() = regions.map { it.text }

        /**
         * The text grouped the way it is laid out: paragraphs, headings, rows. This is what the
         * review screen offers one at a time, so it is computed once per result rather than per
         * redraw.
         */
        val blocks: List<TextBlock> by lazy { TextBlocks.group(regions) }

        /** The recognized text, or the status message when there is none. */
        val displayText: String
            get() = if (hasText) lines.joinToString("\n") else (message ?: "No text detected")
    }

    /** Runs detection + recognition, returning one region per detected block of text. */
    fun runOcr(bitmap: Bitmap): OcrResult {
        val boxes = detect(bitmap)
        if (boxes.isEmpty()) return OcrResult(emptyList(), "No text detected")

        val regions = ArrayList<Region>(boxes.size)
        for (detection in boxes) {
            val padded = detection.padded
            val crop = try {
                Bitmap.createBitmap(bitmap, padded.left, padded.top, padded.width, padded.height)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Skipping invalid crop $padded", e)
                continue
            }
            val (text, confidence) = recognize(crop)
            if (crop != bitmap) crop.recycle()
            if (text.isNotBlank() && confidence >= MIN_REC_CONFIDENCE) {
                // Report the tight box: dilated boxes overlap, which would mislead any consumer
                // trying to reason about columns or layout from these coordinates.
                val tight = detection.tight
                regions.add(Region(text, tight.left, tight.top, tight.right, tight.bottom))
            }
        }

        return if (regions.isEmpty()) {
            OcrResult(emptyList(), "No text recognized (${boxes.size} region(s) found)")
        } else {
            OcrResult(regions)
        }
    }

    // ---------------------------------------------------------------- detection

    private fun detect(bitmap: Bitmap): List<Detection> {
        // Preserve aspect ratio; DBNet needs both sides to be multiples of 32.
        val scale = min(1.0f, DET_MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val detW = roundTo32(bitmap.width * scale)
        val detH = roundTo32(bitmap.height * scale)

        val resized = Bitmap.createScaledBitmap(bitmap, detW, detH, true)
        val input = bitmapToTensor(resized, DET_MEAN, DET_STD)
        if (resized != bitmap) resized.recycle()

        val probMap: FloatArray
        val mapW: Int
        val mapH: Int
        input.use { tensor ->
            detSession.run(mapOf(detInputName to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val shape = output.info.shape // [1, 1, H, W]
                mapH = shape[shape.size - 2].toInt()
                mapW = shape[shape.size - 1].toInt()
                probMap = FloatArray(mapW * mapH)
                output.floatBuffer.get(probMap, 0, probMap.size)
            }
        }

        // Map detection-space boxes back onto the original bitmap.
        val ratioX = bitmap.width.toFloat() / mapW
        val ratioY = bitmap.height.toFloat() / mapH

        return findBoxes(probMap, mapW, mapH)
            .map { box ->
                Box(
                    left = (box.left * ratioX).roundToInt().coerceIn(0, bitmap.width - 1),
                    top = (box.top * ratioY).roundToInt().coerceIn(0, bitmap.height - 1),
                    right = (box.right * ratioX).roundToInt().coerceIn(1, bitmap.width),
                    bottom = (box.bottom * ratioY).roundToInt().coerceIn(1, bitmap.height)
                )
            }
            .filter { it.width >= MIN_BOX_SIZE && it.height >= MIN_BOX_SIZE }
            .map { tight -> Detection(tight, unclip(tight, bitmap.width, bitmap.height)) }
            .sortedInReadingOrder()
    }

    /**
     * Binarizes the probability map and extracts one box per connected component
     * (8-connectivity flood fill), then expands each box to undo the DBNet shrink.
     */
    private fun findBoxes(probMap: FloatArray, width: Int, height: Int): List<Box> {
        val visited = BooleanArray(probMap.size)
        val stack = IntArray(probMap.size)
        val boxes = ArrayList<Box>()

        for (start in probMap.indices) {
            if (visited[start] || probMap[start] < BINARY_THRESHOLD) continue

            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true

            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            var pixelCount = 0
            var scoreSum = 0f

            while (stackSize > 0) {
                val index = stack[--stackSize]
                val x = index % width
                val y = index / width

                pixelCount++
                scoreSum += probMap[index]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val neighbour = ny * width + nx
                        if (!visited[neighbour] && probMap[neighbour] >= BINARY_THRESHOLD) {
                            visited[neighbour] = true
                            stack[stackSize++] = neighbour
                        }
                    }
                }
            }

            if (pixelCount < MIN_COMPONENT_PIXELS) continue
            if (scoreSum / pixelCount < BOX_SCORE_THRESHOLD) continue

            boxes.add(Box(minX, minY, maxX + 1, maxY + 1))
        }
        return boxes
    }

    /**
     * DBNet predicts a shrunk text region, so dilate the box back out using the standard
     * Vatti offset distance (area * ratio / perimeter) applied to a rectangle.
     */
    private fun unclip(box: Box, width: Int, height: Int): Box {
        val w = box.width.toFloat()
        val h = box.height.toFloat()
        val d = ((w * h * UNCLIP_RATIO) / (2f * (w + h))).roundToInt()
        return Box(
            left = (box.left - d).coerceIn(0, width - 1),
            top = (box.top - d).coerceIn(0, height - 1),
            right = (box.right + d).coerceIn(1, width),
            bottom = (box.bottom + d).coerceIn(1, height)
        )
    }

    /** Top-to-bottom, then left-to-right for boxes sitting on roughly the same line. */
    private fun List<Detection>.sortedInReadingOrder(): List<Detection> = ReadingOrder.sort(this)

    // -------------------------------------------------------------- recognition

    /** Recognizes one cropped text line, returning the text and its mean CTC confidence. */
    private fun recognize(crop: Bitmap): Pair<String, Float> {
        val targetWidth = (REC_HEIGHT.toFloat() * crop.width / crop.height)
            .roundToInt()
            .coerceIn(REC_MIN_WIDTH, REC_MAX_WIDTH)
        val resized = Bitmap.createScaledBitmap(crop, targetWidth, REC_HEIGHT, true)
        val input = bitmapToTensor(resized, REC_MEAN, REC_STD)
        if (resized != crop) resized.recycle()

        input.use { tensor ->
            recSession.run(mapOf(recInputName to tensor)).use { result ->
                val output = result.get(0) as OnnxTensor
                val shape = output.info.shape // [1, T, numClasses]
                return ctcGreedyDecode(output.floatBuffer, shape[1].toInt(), shape[2].toInt())
            }
        }
    }

    /** Greedy CTC: argmax per timestep, collapse repeats, drop blanks. */
    private fun ctcGreedyDecode(
        scores: FloatBuffer,
        timeSteps: Int,
        numClasses: Int
    ): Pair<String, Float> {
        val text = StringBuilder()
        var confidenceSum = 0f
        var charCount = 0
        var previousIndex = BLANK_INDEX

        for (t in 0 until timeSteps) {
            val offset = t * numClasses
            var bestIndex = 0
            var bestScore = scores.get(offset)
            for (c in 1 until numClasses) {
                val score = scores.get(offset + c)
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = c
                }
            }

            if (bestIndex != BLANK_INDEX && bestIndex != previousIndex &&
                bestIndex < characterDict.size
            ) {
                text.append(characterDict[bestIndex])
                confidenceSum += bestScore
                charCount++
            }
            previousIndex = bestIndex
        }

        val confidence = if (charCount > 0) confidenceSum / charCount else 0f
        return text.toString() to confidence
    }

    // ------------------------------------------------------------- preprocessing

    /** Converts a bitmap to a normalized NCHW planar-RGB float tensor. */
    private fun bitmapToTensor(bitmap: Bitmap, mean: FloatArray, std: FloatArray): OnnxTensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val channelStride = width * height
        val floatArray = FloatArray(3 * channelStride)

        for (i in 0 until channelStride) {
            val pixel = pixels[i]
            floatArray[i] = (((pixel shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            floatArray[i + channelStride] = (((pixel shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            floatArray[i + channelStride * 2] = ((pixel and 0xFF) / 255f - mean[2]) / std[2]
        }

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArray), shape)
    }

    private fun roundTo32(value: Float): Int = max(32, (value / 32f).roundToInt() * 32)

    fun close() {
        detSession.close()
        recSession.close()
    }

    companion object {
        private const val TAG = "LocalPPOCRv6Runner"

        private const val BLANK_TOKEN = "blank"
        private const val BLANK_INDEX = 0

        // Detection
        private const val DET_MAX_SIDE = 960
        private const val BINARY_THRESHOLD = 0.3f
        private const val BOX_SCORE_THRESHOLD = 0.7f
        private const val UNCLIP_RATIO = 1.8f
        private const val MIN_COMPONENT_PIXELS = 16
        private const val MIN_BOX_SIZE = 4
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        // Recognition
        private const val REC_HEIGHT = 48
        private const val REC_MIN_WIDTH = 16
        private const val REC_MAX_WIDTH = 1200
        private const val MIN_REC_CONFIDENCE = 0.7f
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}
