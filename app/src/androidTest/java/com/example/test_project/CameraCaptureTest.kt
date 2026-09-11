package com.example.test_project

import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.benchmark.ReaderBenchmarkActivity
import com.example.test_project.capture.PhotoCapture
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCaptureTest {
    @Test
    fun realCameraProducesHighResolutionStill() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = ProcessCameraProvider.getInstance(context).get(10, TimeUnit.SECONDS)
        val executor = ContextCompat.getMainExecutor(context)
        val ready = CompletableDeferred<Unit>()
        val result = CompletableDeferred<JSONObject>()
        ActivityScenario.launch(ReaderBenchmarkActivity::class.java).use { scenario ->
            val capture = PhotoCapture.create()
            try {
                scenario.onActivity { activity ->
                    val view = PreviewView(activity)
                    activity.setContentView(view)
                    val preview =
                        Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis =
                        ImageAnalysis.Builder()
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analysis.setAnalyzer(executor) { image ->
                        image.close()
                        ready.complete(Unit)
                    }
                    provider.bindToLifecycle(
                        activity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                        capture,
                    )
                }
                withTimeout(10_000) { ready.await() }
                val started = SystemClock.elapsedRealtime()
                withContext(Dispatchers.Main) {
                    capture.takePicture(
                        executor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    result.complete(
                                        JSONObject()
                                            .put("width", image.width)
                                            .put("height", image.height)
                                            .put(
                                                "captureMs",
                                                SystemClock.elapsedRealtime() - started,
                                            )
                                            .put("rotation", image.imageInfo.rotationDegrees)
                                    )
                                } finally {
                                    image.close()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                result.completeExceptionally(exception)
                            }
                        },
                    )
                }
                val record = withTimeout(15_000) { result.await() }
                File(context.filesDir, "camera-capture.json").writeText(record.toString(2))
                assertTrue(
                    record.toString(),
                    maxOf(record.getInt("width"), record.getInt("height")) >= 1920,
                )
                // Only dimensions/timing are retained; camera image content is discarded.
            } finally {
                withContext(Dispatchers.Main) { provider.unbindAll() }
            }
        }
    }
}
