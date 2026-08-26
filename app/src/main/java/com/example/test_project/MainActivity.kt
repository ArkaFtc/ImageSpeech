package com.example.test_project

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.test_project.R // Import generated R class
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var ocrRunner: LocalPPOCRv6Runner
    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize OCR engine safely
        ocrRunner = LocalPPOCRv6Runner(this)

        initTextToSpeech()

        val btnTakePicture = findViewById<Button>(R.id.btnTakePicture)
        val txtResult = findViewById<TextView>(R.id.txtResult)

        // Request Camera Permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        btnTakePicture.setOnClickListener {
            btnTakePicture.isEnabled = false
            tts?.stop() // Cut off any previous reading before starting a new capture.
            txtResult.text = getString(R.string.recognizing)
            takePictureAndProcess { result ->
                runOnUiThread {
                    txtResult.text = result.displayText
                    btnTakePicture.isEnabled = true
                    // Only read actual transcribed text, never a status message.
                    if (result.hasText) speak(result.displayText)
                }
            }
        }
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e("TTS", "TextToSpeech init failed with status $status")
                Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
                return@TextToSpeech
            }

            val engine = tts ?: return@TextToSpeech
            val languageStatus = engine.setLanguage(Locale.getDefault())
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
                languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w("TTS", "Locale ${Locale.getDefault()} unavailable, falling back to US English")
                engine.setLanguage(Locale.US)
            }
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady || text.isBlank()) return
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview setup
            val preview = Preview.Builder()
                .build()
                .also {
                    val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // ImageCapture initialization
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePictureAndProcess(onResult: (LocalPPOCRv6Runner.OcrResult) -> Unit) {
        fun failure(message: String) = LocalPPOCRv6Runner.OcrResult(emptyList(), message)

        val capture = imageCapture ?: run {
            onResult(failure("Camera not initialized"))
            return
        }

        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraX", "Capture failed", exception)
                    onResult(failure("Capture failed: ${exception.message}"))
                }

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val bitmap = imageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        if (bitmap != null) {
                            val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)
                            onResult(ocrRunner.runOcr(rotatedBitmap))
                        } else {
                            onResult(failure("Failed to decode captured frame"))
                        }
                    } catch (e: Exception) {
                        Log.e("OCR", "Error during inference", e)
                        onResult(failure("Error: ${e.message}"))
                    }
                }
            }
        )
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Do not keep reading aloud once the app is no longer in front of the user.
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        ocrRunner.close()
        executor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val UTTERANCE_ID = "ocr_result"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}