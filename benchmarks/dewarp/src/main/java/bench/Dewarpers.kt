package bench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.nio.FloatBuffer
import kotlin.math.floor

fun now() = SystemClock.elapsedRealtimeNanos()
fun ms(since: Long) = (now() - since) / 1e6
data class DewarpResult(val bitmap: Bitmap, val preprocessMs: Double, val inferenceMs: Double, val postprocessMs: Double, val grid: FloatArray? = null)
interface Dewarper : AutoCloseable { fun run(bitmap: Bitmap): DewarpResult }

/** Normalized planar BGR; both published adapters use BGR input. */
fun bgr(bitmap: Bitmap): FloatArray {
    val n = bitmap.width * bitmap.height
    val pixels = IntArray(n)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return FloatArray(n * 3).also { out ->
        for (i in pixels.indices) {
            out[i] = (pixels[i] and 255) / 255f
            out[i + n] = ((pixels[i] shr 8) and 255) / 255f
            out[i + n * 2] = ((pixels[i] shr 16) and 255) / 255f
        }
    }
}

class UVDoc(path: String) : Dewarper {
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(path)
    val metadata = "inputs=${session.inputInfo}; outputs=${session.outputInfo}"
    override fun run(bitmap: Bitmap): DewarpResult {
        val start = now()
        val data = bgr(bitmap)
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, bitmap.height.toLong(), bitmap.width.toLong()))
        val pre = ms(start)
        val inferStart = now()
        val values: FloatArray
        val width: Int
        val height: Int
        input.use {
            session.run(mapOf(session.inputNames.first() to it)).use { outputs ->
                val tensor = outputs.get(0) as OnnxTensor
                val shape = tensor.info.shape
                check(shape[1] == 3L)
                height = shape[2].toInt(); width = shape[3].toInt()
                values = FloatArray(3 * width * height)
                tensor.floatBuffer.get(values)
            }
        }
        val infer = ms(inferStart)
        val postStart = now()
        val n = width * height
        // Official Paddle postprocessor reverses RGB output to OpenCV BGR.
        // Android Bitmap stores RGB, so no channel reversal is necessary here.
        val pixels = IntArray(n) { i ->
            val r = (values[i] * 255).toInt().coerceIn(0, 255)
            val g = (values[i + n] * 255).toInt().coerceIn(0, 255)
            val b = (values[i + n * 2] * 255).toInt().coerceIn(0, 255)
            (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        val result = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return DewarpResult(result, pre, infer, ms(postStart))
    }
    override fun close() = session.close()
}

class DewarpNet(path: String, gpu: Boolean) : Dewarper {
    private val options = CompiledModel.Options(if (gpu) Accelerator.GPU else Accelerator.CPU).apply {
        if (!gpu) cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
    }
    private val model = CompiledModel.create(path, options)
    private val inputs = model.createInputBuffers()
    private val outputs = model.createOutputBuffers()
    override fun run(bitmap: Bitmap): DewarpResult {
        val start = now()
        val small = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val input = bgr(small)
        if (small !== bitmap) small.recycle()
        val pre = ms(start)
        val inferStart = now()
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        val grid = outputs[0].readFloat()
        check(grid.size == 2 * 128 * 128 && grid.all { it.isFinite() })
        val infer = ms(inferStart)
        val postStart = now()
        val result = unwarp(bitmap, grid)
        return DewarpResult(result, pre, infer, ms(postStart), grid)
    }
    override fun close() {
        inputs.forEach { it.close() }; outputs.forEach { it.close() }; model.close()
    }
}

/** Reference: cv2.blur(3x3, REFLECT_101), cv2.resize, grid_sample(align_corners=True).
 * Bilinear image sampling uses zero padding, matching the published PyTorch call.
 */
fun unwarp(image: Bitmap, raw: FloatArray): Bitmap {
    val side = 128
    val plane = side * side
    val grid = FloatArray(raw.size)
    fun reflect(v: Int) = if (v < 0) -v else if (v >= side) 2 * side - 2 - v else v
    for (c in 0..1) for (y in 0 until side) for (x in 0 until side) {
        var sum = 0f
        for (dy in -1..1) for (dx in -1..1) sum += raw[c * plane + reflect(y + dy) * side + reflect(x + dx)]
        grid[c * plane + y * side + x] = sum / 9f
    }
    val w = image.width; val h = image.height
    val source = IntArray(w * h)
    image.getPixels(source, 0, w, 0, 0, w, h)
    val dest = IntArray(w * h)
    fun gridAt(c: Int, x0: Int, y0: Int, fx: Float, fy: Float): Float {
        val x = x0.coerceIn(0, side - 1); val xx = (x0 + 1).coerceIn(0, side - 1)
        val y = y0.coerceIn(0, side - 1); val yy = (y0 + 1).coerceIn(0, side - 1)
        val off = c * plane
        return (grid[off + y * side + x] * (1 - fx) + grid[off + y * side + xx] * fx) * (1 - fy) +
            (grid[off + yy * side + x] * (1 - fx) + grid[off + yy * side + xx] * fx) * fy
    }
    fun pixel(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) source[y * w + x] else 0
    for (y in 0 until h) for (x in 0 until w) {
        val gx = (x + .5f) * side / w - .5f; val gy = (y + .5f) * side / h - .5f
        val ix = floor(gx).toInt(); val iy = floor(gy).toInt()
        val sx = (gridAt(0, ix, iy, gx - ix, gy - iy) + 1) * (w - 1) / 2
        val sy = (gridAt(1, ix, iy, gx - ix, gy - iy) + 1) * (h - 1) / 2
        val xx = floor(sx).toInt(); val yy = floor(sy).toInt()
        val fx = sx - xx; val fy = sy - yy
        val p00 = pixel(xx, yy); val p10 = pixel(xx + 1, yy)
        val p01 = pixel(xx, yy + 1); val p11 = pixel(xx + 1, yy + 1)
        var color = 255 shl 24
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            val top = ((p00 shr shift) and 255) * (1 - fx) + ((p10 shr shift) and 255) * fx
            val bot = ((p01 shr shift) and 255) * (1 - fx) + ((p11 shr shift) and 255) * fx
            color = color or ((top * (1 - fy) + bot * fy).toInt().coerceIn(0, 255) shl shift)
        }
        dest[y * w + x] = color
    }
    return Bitmap.createBitmap(dest, w, h, Bitmap.Config.ARGB_8888)
}
