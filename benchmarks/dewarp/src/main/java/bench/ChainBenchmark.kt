package bench

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChainBenchmark : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    private fun emit(value: JSONObject) = sendStatus(0, Bundle().apply { putString("stream", value.toString() + "\n") })
    override fun onStart() {
        val method = args.getString("method", "baseline")
        val chain = args.getString("chain", "true") == "true"
        val count = args.getString("iterations", "3").toInt()
        val tag = "$method-${if (chain) "chain" else "alone"}"
        val report = JSONObject().put("method", method).put("chain", chain)
        val runs = JSONArray()
        report.put("runs", runs)
        val pm = targetContext.getSystemService(PowerManager::class.java)
        var dewarper: Dewarper? = null
        var ocr: LocalPPOCRv6Runner? = null
        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeFile(File(targetContext.filesDir, "warped.jpg").path)!!
            report.put("width", bitmap.width).put("height", bitmap.height).put("thermalStart", pm.currentThermalStatus)
            val init = now()
            dewarper = when (method) {
                "uvdoc" -> UVDoc(File(targetContext.filesDir, "uvdoc.onnx").path)
                "dewarp-gpu" -> DewarpNet(File(targetContext.filesDir, "dewarp.tflite").path, true)
                "dewarp-cpu" -> DewarpNet(File(targetContext.filesDir, "dewarp.tflite").path, false)
                "baseline" -> null
                else -> error("Unknown method: $method")
            }
            report.put("dewarpInitMs", ms(init))
            if (dewarper is UVDoc) report.put("modelMetadata", dewarper.metadata)
            val ocrInit = now()
            if (chain) ocr = LocalPPOCRv6Runner(targetContext)
            report.put("ocrInitMs", if (chain) ms(ocrInit) else 0.0)
            emit(JSONObject().put("event", "initialized").put("config", report))
            repeat(count + 1) { index ->
                val start = now()
                val warped = dewarper?.run(bitmap)
                val dewarpMs = ms(start)
                val flattened = warped?.bitmap ?: bitmap
                val ocrStart = now()
                val text = ocr?.runOcr(flattened)
                val ocrMs = ms(ocrStart)
                val totalMs = ms(start)
                val record = JSONObject().put("index", index).put("phase", if (index == 0) "first" else "measured")
                    .put("preprocessMs", warped?.preprocessMs ?: 0.0).put("inferenceMs", warped?.inferenceMs ?: 0.0)
                    .put("postprocessMs", warped?.postprocessMs ?: 0.0).put("dewarpMs", if (dewarper != null) dewarpMs else 0.0)
                    .put("ocrMs", if (chain) ocrMs else 0.0).put("totalMs", totalMs)
                    .put("thermalStatus", pm.currentThermalStatus).put("outputWidth", flattened.width).put("outputHeight", flattened.height)
                    .put("regions", text?.regions?.size ?: 0).put("text", text?.displayText ?: "")
                runs.put(record)
                // All disk I/O and reporting is outside timed intervals.
                if (index == 0) {
                    File(targetContext.filesDir, "$tag.png").outputStream().use { flattened.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    warped?.grid?.let { grid ->
                        val buffer = ByteBuffer.allocate(grid.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.asFloatBuffer().put(grid)
                        File(targetContext.filesDir, "$tag-grid.bin").writeBytes(buffer.array())
                    }
                }
                if (flattened !== bitmap) flattened.recycle()
                File(targetContext.filesDir, "$tag.json").writeText(report.toString(2))
                emit(record)
            }
            report.put("thermalEnd", pm.currentThermalStatus).put("success", true)
        } catch (error: Throwable) {
            report.put("success", false).put("error", error.stackTraceToString())
            emit(report)
        } finally {
            File(targetContext.filesDir, "$tag.json").writeText(report.toString(2))
            ocr?.close(); dewarper?.close(); bitmap?.recycle()
        }
        finish(if (report.optBoolean("success")) -1 else 0, Bundle().apply { putString("stream", "Completed $tag; success=${report.optBoolean("success")}\n") })
    }
}
