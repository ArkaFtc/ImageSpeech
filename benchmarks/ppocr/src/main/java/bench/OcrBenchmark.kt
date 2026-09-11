package bench

import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import com.example.test_project.processing.ocr.LocalPPOCRv6Runner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Separate process/package; executes the unmodified production pipeline. */
class OcrBenchmark : Instrumentation() {
    private var iterations = 10
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        iterations = arguments?.getString("iterations")?.toInt() ?: 10
        start()
    }
    override fun onStart() {
        val output = JSONObject()
        try {
            val pm = targetContext.getSystemService(PowerManager::class.java)
            output.put("thermalStart", pm.currentThermalStatus)
            output.put("runtime", "onnxruntime-android:1.18.0; default CPU sessions")
            val bitmap = targetContext.assets.open("test.png").use { BitmapFactory.decodeStream(it) }!!
            output.put("width", bitmap.width).put("height", bitmap.height)
            output.put("modelsAlreadyCached", File(targetContext.cacheDir, "pp_ocrv6_det.onnx").exists())
            val start = SystemClock.elapsedRealtimeNanos()
            val runner = LocalPPOCRv6Runner(targetContext)
            output.put("initializationMs", (SystemClock.elapsedRealtimeNanos() - start) / 1e6)
            try {
                val runs = JSONArray()
                // First inference, two additional warmups, then measured steady-state passes.
                repeat(iterations + 3) { index ->
                    val before = SystemClock.elapsedRealtimeNanos()
                    val result = runner.runOcr(bitmap)
                    val ms = (SystemClock.elapsedRealtimeNanos() - before) / 1e6
                    val record = JSONObject().put("index", index)
                        .put("phase", if (index == 0) "first" else if (index < 3) "warmup" else "measured")
                        .put("elapsedMs", ms).put("regions", result.regions.size)
                        .put("text", result.displayText).put("thermalStatus", pm.currentThermalStatus)
                    runs.put(record)
                    sendStatus(0, Bundle().apply { putString("stream", record.toString() + "\n") })
                    check(result.hasText) { "OCR produced no text" }
                }
                output.put("runs", runs)
            } finally { runner.close(); bitmap.recycle() }
            output.put("thermalEnd", pm.currentThermalStatus)
            File(targetContext.filesDir, "results.json").writeText(output.toString(2))
            finish(-1, Bundle().apply { putString("stream", output.toString(2)) })
        } catch (error: Throwable) {
            finish(0, Bundle().apply { putString("stream", error.stackTraceToString()) })
        }
    }
}
