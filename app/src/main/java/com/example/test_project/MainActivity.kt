package com.example.test_project

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.test_project.ui.theme.Test_projectTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as MlKitText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null

    private lateinit var textToSpeech: TextToSpeech

    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Text-to-Speech
        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val result =
                    textToSpeech.setLanguage(Locale.US)

                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        setContent {
            Test_projectTheme {
                CameraScreen()
            }
        }
    }

    @Composable
    fun CameraScreen() {

        val context = LocalContext.current

        var hasCameraPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        var recognizedText by remember {
            mutableStateOf("")
        }

        var isProcessing by remember {
            mutableStateOf(false)
        }

        val permissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                hasCameraPermission = granted
            }

        LaunchedEffect(Unit) {

            if (!hasCameraPermission) {

                permissionLauncher.launch(
                    Manifest.permission.CAMERA
                )
            }
        }

        if (hasCameraPermission) {

            Column(
                modifier = Modifier.fillMaxSize()
            ) {

                // Camera
                CameraPreview(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                // Take picture button
                Button(
                    enabled = !isProcessing,

                    onClick = {

                        isProcessing = true

                        takePhoto { text ->

                            recognizedText = text

                            isProcessing = false

                            // Read the recognized text
                            if (
                                text.isNotEmpty() &&
                                !text.startsWith("OCR error") &&
                                text != "No text detected."
                            ) {

                                speak(text)
                            }
                        }
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {

                    Text(
                        if (isProcessing)
                            "Processing..."
                        else
                            "Take Picture"
                    )
                }

                // OCR result
                Text(
                    text =
                        if (recognizedText.isEmpty()) {
                            "Recognized text will appear here"
                        } else {
                            recognizedText
                        },

                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }

        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),

                verticalArrangement =
                    Arrangement.Center
            ) {

                Text(
                    text =
                        "Camera permission is required."
                )

                Button(
                    onClick = {

                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {

                    Text("Allow Camera")
                }
            }
        }
    }

    @Composable
    fun CameraPreview(
        modifier: Modifier = Modifier
    ) {

        val context = LocalContext.current

        val previewView = remember {
            PreviewView(context)
        }

        AndroidView(

            factory = {
                previewView
            },

            modifier = modifier
        )

        LaunchedEffect(Unit) {

            val cameraProviderFuture =
                ProcessCameraProvider
                    .getInstance(context)

            cameraProviderFuture.addListener({

                val cameraProvider =
                    cameraProviderFuture.get()

                val preview =
                    Preview.Builder()
                        .build()

                preview.surfaceProvider =
                    previewView.surfaceProvider

                imageCapture =
                    ImageCapture.Builder()
                        .setCaptureMode(
                            ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        )
                        .build()

                val cameraSelector =
                    CameraSelector.DEFAULT_BACK_CAMERA

                try {

                    cameraProvider.unbindAll()

                    cameraProvider.bindToLifecycle(

                        this@MainActivity,

                        cameraSelector,

                        preview,

                        imageCapture
                    )

                } catch (e: Exception) {

                    e.printStackTrace()
                }

            }, ContextCompat.getMainExecutor(context))
        }
    }

    private fun takePhoto(
        onTextRecognized: (String) -> Unit
    ) {

        val capture =
            imageCapture ?: return

        capture.takePicture(

            ContextCompat.getMainExecutor(this),

            object :
                ImageCapture.OnImageCapturedCallback() {

                override fun onCaptureSuccess(
                    imageProxy:
                    androidx.camera.core.ImageProxy
                ) {

                    val mediaImage =
                        imageProxy.image

                    if (mediaImage != null) {

                        val image =
                            InputImage.fromMediaImage(

                                mediaImage,

                                imageProxy
                                    .imageInfo
                                    .rotationDegrees
                            )

                        recognizeText(
                            image,
                            onTextRecognized
                        )
                    }

                    imageProxy.close()
                }

                override fun onError(
                    exception:
                    ImageCaptureException
                ) {

                    exception.printStackTrace()

                    onTextRecognized(
                        "Error taking picture: " +
                                exception.message
                    )
                }
            }
        )
    }

    private fun recognizeText(
        image: InputImage,
        onTextRecognized: (String) -> Unit
    ) {

        val recognizer =
            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )

        recognizer
            .process(image)

            .addOnSuccessListener { result ->

                val cleanedText = postProcessOcrResult(result)

                if (cleanedText.isEmpty()) {

                    onTextRecognized(
                        "No text detected."
                    )

                } else {

                    onTextRecognized(cleanedText)
                }
            }

            .addOnFailureListener { exception ->

                onTextRecognized(
                    "OCR error: " +
                            exception.message
                )
            }

            .addOnCompleteListener {

                recognizer.close()
            }
    }

    /**
     * Post-processes raw ML Kit output instead of trusting result.text as-is.
     *
     * Steps:
     *  1. Flatten Text -> TextBlocks -> Lines, keeping each line's bounding box.
     *  2. Drop lines that are almost certainly noise (empty after trim, or
     *     suspiciously small bounding-box height relative to the rest of the
     *     detected text — tiny specks/watermarks tend to be misreads).
     *  3. Sort the surviving lines into natural reading order (top-to-bottom,
     *     then left-to-right for lines that are roughly on the same row —
     *     handles multi-column layouts like receipts/labels better than
     *     ML Kit's default block ordering in some cases).
     *  4. Normalize whitespace and join into a single cleaned string.
     */
    private fun postProcessOcrResult(result: MlKitText): String {

        // 1. Flatten into (line, boundingBox) pairs, skipping lines with no box.
        data class LineInfo(val text: String, val box: Rect)

        val lines = mutableListOf<LineInfo>()

        for (block in result.textBlocks) {
            for (line in block.lines) {

                val box = line.boundingBox ?: continue
                val trimmed = line.text.trim()

                if (trimmed.isNotEmpty()) {
                    lines.add(LineInfo(trimmed, box))
                }
            }
        }

        if (lines.isEmpty()) return ""

        // 2. Filter out likely-noise lines based on relative box height.
        // Very short/tiny detections next to much larger text are usually
        // misreads (stray marks, watermarks, edges of other objects).
        val avgHeight = lines.map { it.box.height() }.average()
        val minAcceptableHeight = avgHeight * 0.35 // tune as needed

        val filtered = lines.filter { line ->
            line.box.height() >= minAcceptableHeight ||
                    lines.size == 1 // don't filter if it's the only line found
        }

        val usable = filtered.ifEmpty { lines }

        // 3. Sort into reading order: group lines into rows by vertical
        // overlap, then order rows top-to-bottom and lines left-to-right
        // within each row.
        val sorted = usable.sortedWith(
            compareBy(
                { it.box.top / (avgHeight.toInt().coerceAtLeast(1)) },
                { it.box.left }
            )
        )

        // Merge lines that ended up on the same visual row (helps when two
        // short lines are side-by-side, e.g. a two-column label).
        val rows = mutableListOf<MutableList<LineInfo>>()

        for (line in sorted) {
            val row = rows.lastOrNull()

            val sameRow = row != null && run {
                val lastLine = row.last()
                val verticalOverlap = minOf(lastLine.box.bottom, line.box.bottom) -
                        maxOf(lastLine.box.top, line.box.top)
                val smallerHeight = minOf(lastLine.box.height(), line.box.height())
                smallerHeight > 0 && verticalOverlap.toFloat() / smallerHeight > 0.5f
            }

            if (sameRow && row != null) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }

        // 4. Build final string: left-to-right within a row, rows separated
        // by newlines, whitespace normalized.
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.box.left }
                .joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
        }.trim()
    }

    // 🔊 Text-to-Speech
    private fun speak(text: String) {

        if (!ttsReady) {
            return
        }

        textToSpeech.speak(

            text,

            TextToSpeech.QUEUE_FLUSH,

            null,

            "recognized_text"
        )
    }

    override fun onDestroy() {

        // Stop speaking when app closes
        textToSpeech.stop()
        textToSpeech.shutdown()

        super.onDestroy()
    }
}