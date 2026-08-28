package com.example.test_project

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Hold to ask; tap to read.
 *
 * The press is not just an input event - it opens a prefill window. The shutter and the microphone
 * start together, the sharpest frame of a short burst goes straight into OCR, and all of that
 * completes while the user is still speaking. By the time the button comes up, the expensive work
 * has already been paid for with time the user was spending anyway.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Opening the two ONNX models copies and maps ~138 MB, which is far too much to do on the main
     * thread - that stall was most of the black screen at launch. Everything that needs the engine
     * awaits this instead.
     */
    private val ocrEngine = CompletableDeferred<LocalPPOCRv6Runner?>()

    /** The same instance as [ocrEngine]'s result, kept for teardown without awaiting. */
    @Volatile
    private var ocrRunner: LocalPPOCRv6Runner? = null
    private lateinit var speech: SpeechQueue
    private lateinit var transcriber: Transcriber

    /**
     * The model lane, owned by [InferenceService] so it survives the Activity going away. Null
     * until the binding lands; every use treats that as "not available yet", which is the same
     * path a device without the model takes.
     */
    private var sceneAnswerer: SceneAnswerer? = null
    private var serviceBound = false

    private val audio = AudioCapture()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private lateinit var resultView: TextView
    private lateinit var startupOverlay: View
    private lateinit var startupDetail: TextView
    private lateinit var downloadBanner: View
    private lateinit var downloadBar: ProgressBar
    private lateinit var downloadDetail: TextView

    // ---- frame burst state, all touched only from the main thread or under frameLock ----
    private val frameLock = Any()
    @Volatile private var collecting = false

    /** Sharpest frame seen since the press. */
    private var bestFrame: Bitmap? = null
    private var bestScore = 0.0

    /**
     * The newest frame from before the press, kept so a press always has something to work with.
     * A quick tap can be over in less time than it takes one frame to arrive, and "no camera frame
     * was ready" is a terrible answer to a button the user cannot see themselves pressing.
     */
    private var latestFrame: Bitmap? = null
    private var latestScore = 0.0

    /**
     * Completes when the analysis pipeline delivers its first frame.
     *
     * Binding the camera does not mean frames are flowing yet - there is a warm-up of a few hundred
     * milliseconds after the session configures. A press inside that window has to wait for a frame
     * rather than conclude there will never be one.
     */
    private val firstFrame = CompletableDeferred<Unit>()

    // ---- per-press state, main thread only ----
    private var pressStartMs = 0L
    private var burstJob: Job? = null
    private var pendingOcr: Deferred<LocalPPOCRv6Runner.OcrResult>? = null
    private var capturedFrame: Bitmap? = null
    private var capturedScore = 0.0
    private var answerJob: Job? = null
    private var prefillJob: Job? = null
    private var openTurn: SceneTurn? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speech = SpeechQueue(this)
        transcriber = Transcriber(this)

        resultView = findViewById(R.id.txtResult)
        startupOverlay = findViewById(R.id.startupOverlay)
        startupDetail = findViewById(R.id.txtStartupDetail)
        downloadBanner = findViewById(R.id.downloadBanner)
        downloadBar = findViewById(R.id.barDownload)
        downloadDetail = findViewById(R.id.txtDownloadDetail)
        wireHoldButton(findViewById(R.id.btnAsk))
        insetDownloadBanner()

        loadOcrEngine()
        dismissStartupWhenReady()

        lifecycleScope.launch {
            if (!speech.awaitReady()) {
                Toast.makeText(this@MainActivity, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        startModelDownloadIfNeeded()
    }

    // ------------------------------------------------------------------ model plumbing

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            sceneAnswerer = (service as? InferenceService.LocalBinder)?.answerer
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sceneAnswerer = null
        }
    }

    /**
     * Queues the one-off model download and narrates it.
     *
     * The work itself waits for Wi-Fi and survives this Activity, so all there is to do here is
     * say what is happening - a user who cannot see a progress bar still needs to know why asking
     * questions does not work yet.
     */
    private fun startModelDownloadIfNeeded() {
        if (ModelStore(this).isPresent) return
        ModelDownloadWorker.enqueue(this)

        var announcedStart = false
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelDownloadWorker.WORK_NAME)
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                val percent = ModelDownloadWorker.progress(info)

                if (percent != null) {
                    downloadBanner.visibility = View.VISIBLE
                    downloadBar.progress = percent
                    downloadDetail.text = getString(R.string.download_banner_progress, percent)
                    if (!announcedStart) {
                        announcedStart = true
                        lifecycleScope.launch {
                            speech.speak(getString(R.string.download_started, ModelStore.EXPECTED_MEGABYTES))
                        }
                    }
                }

                if (info.state.isFinished) {
                    downloadBanner.visibility = View.GONE
                    if (info.state == WorkInfo.State.SUCCEEDED && announcedStart) {
                        lifecycleScope.launch { speech.speak(getString(R.string.download_finished)) }
                    }
                }
            }
    }

    override fun onStart() {
        super.onStart()
        // Bound rather than started: the engine should outlive a rotation, not the app.
        serviceBound = bindService(
            Intent(this, InferenceService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE,
        )
    }

    /** The banner sits at the very top, so it has to make room for the status bar itself. */
    private fun insetDownloadBanner() {
        val basePadding = downloadBanner.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(downloadBanner) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = basePadding + statusBar)
            insets
        }
    }

    // ------------------------------------------------------------------ startup

    private fun loadOcrEngine() {
        lifecycleScope.launch {
            val runner = withContext(Dispatchers.IO) {
                runCatching { LocalPPOCRv6Runner(this@MainActivity) }
                    .onFailure { Log.e(TAG, "OCR engine failed to load", it) }
                    .getOrNull()
            }
            ocrRunner = runner
            ocrEngine.complete(runner)
            startupDetail.text = getString(R.string.startup_camera)
        }
    }

    /**
     * Holds the cover until there is genuinely something behind it: the reader is open and the
     * camera has produced a frame. Until both are true the preview is a black rectangle, which
     * looks identical to a crash.
     */
    private fun dismissStartupWhenReady() {
        lifecycleScope.launch {
            startupDetail.text = getString(R.string.startup_text)
            ocrEngine.await()
            startupDetail.text = getString(R.string.startup_camera)
            firstFrame.await()
            startupOverlay.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ the gesture

    @SuppressLint("ClickableViewAccessibility")
    private fun wireHoldButton(button: Button) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    onPress()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.performClick()
                    onRelease(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
                    true
                }

                else -> false
            }
        }
    }

    private fun onPress() {
        // Barge-in: whatever is being said, and whatever is generating it, stops now. Nothing is
        // wasted, because the producer was never allowed to run far ahead of the voice.
        answerJob?.cancel()
        answerJob = null
        prefillJob?.cancel()
        prefillJob = null
        openTurn?.close()
        openTurn = null
        speech.stop()

        pressStartMs = SystemClock.elapsedRealtime()
        releaseCapturedFrame()
        setStatus(getString(R.string.listening))

        if (!audio.start()) {
            Log.w(TAG, "Microphone unavailable; routing will fall back to tap-only")
        }

        synchronized(frameLock) {
            bestFrame?.recycle()
            bestFrame = null
            bestScore = 0.0
        }
        collecting = true

        // Close the burst early and start OCR under the user's voice. Committing to a frame this
        // soon is what makes the prefill window possible; the burst is what keeps that from meaning
        // "commit to whichever frame happened to be blurred".
        burstJob = lifecycleScope.launch {
            delay(BURST_WINDOW_MS)
            harvestBurst()
        }

        // Past the tap threshold the press is a held question, so the frame and the OCR block are
        // worth encoding now. Gating on the threshold is what keeps the common case - a tap, which
        // never touches the model - from paying for a prefill it will throw away.
        prefillJob = lifecycleScope.launch {
            delay(IntentRouter.TAP_THRESHOLD_MS)
            val answerer = sceneAnswerer ?: return@launch
            if (!answerer.isAvailable) return@launch
            val ocr = pendingOcr ?: return@launch
            val frame = capturedFrame ?: return@launch
            val turn = answerer.beginTurn(frame, ocr.await()) ?: return@launch
            openTurn = turn
        }
    }

    private fun onRelease(cancelled: Boolean) {
        val holdMs = SystemClock.elapsedRealtime() - pressStartMs
        val clip = audio.stop()

        // If the press was shorter than the burst window, close it out now rather than waiting.
        // Press, release and this continuation all run on the main thread, so there is no race.
        burstJob?.cancel()
        burstJob = null

        val prefill = prefillJob
        prefillJob = null

        if (cancelled) {
            prefill?.cancel()
            releaseCapturedFrame()
            openTurn?.close()
            openTurn = null
            setStatus(getString(R.string.result_placeholder))
            return
        }

        setStatus(getString(R.string.working))
        answerJob = lifecycleScope.launch {
            // A press can land before the camera has produced anything - on a cold start, or on
            // the first press after resuming. Give the pipeline a moment to deliver rather than
            // reporting failure to someone who has no way to see that the preview was not ready.
            if (!hasAnyFrame()) {
                withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { firstFrame.await() }
            }
            harvestBurst()

            val frame = capturedFrame
            val score = capturedScore
            val ocr = pendingOcr
            capturedFrame = null
            pendingOcr = null

            if (frame == null || ocr == null) {
                prefill?.cancel()
                setStatus(getString(R.string.no_frame))
                speech.speak(getString(R.string.no_frame))
                return@launch
            }

            try {
                val result = ocr.await()
                val transcript = clip?.let { transcriber.transcribe(it) }
                val decision = IntentRouter.route(holdMs, clip?.hasSpeech == true, transcript)
                Log.d(TAG, "hold=${holdMs}ms sharpness=${"%.1f".format(score)} -> ${decision.route} (${decision.because})")

                // Let any in-flight prefill finish opening so the turn can be either used or closed.
                prefill?.join()
                val turn = openTurn
                openTurn = null

                when (decision.route) {
                    IntentRouter.Route.READ_VERBATIM -> {
                        // A held read-intent question opens a turn it never uses. That is the
                        // accepted cost of deciding the route only once the question is known.
                        turn?.close()
                        readVerbatim(result, score)
                    }

                    IntentRouter.Route.ASK_MODEL -> ask(turn, result, clip, transcript, score)
                }
            } finally {
                frame.recycle()
            }
        }
    }

    /** Whether any frame is on hand yet - one already harvested, or one waiting in either slot. */
    private fun hasAnyFrame(): Boolean {
        if (capturedFrame != null) return true
        return synchronized(frameLock) { bestFrame != null || latestFrame != null }
    }

    /** Closes the collection window and hands the winning frame to OCR. Idempotent. */
    private fun harvestBurst() {
        if (capturedFrame != null) return
        collecting = false
        val frame: Bitmap?
        val score: Double
        synchronized(frameLock) {
            // Fall back to the frame from just before the press - at most a frame-time stale, and
            // taken while the user was still aiming, so no worse a shot.
            if (bestFrame != null) {
                frame = bestFrame
                score = bestScore
                bestFrame = null
            } else {
                frame = latestFrame
                score = latestScore
                latestFrame = null
            }
        }
        if (frame == null) return

        capturedFrame = frame
        capturedScore = score
        pendingOcr = lifecycleScope.async(Dispatchers.Default) {
            val runner = ocrEngine.await()
                ?: return@async LocalPPOCRv6Runner.OcrResult(emptyList(), getString(R.string.ocr_unavailable))
            runner.runOcr(frame)
        }
    }

    // ------------------------------------------------------------------ the two lanes

    /** Lane 1 and 2: OCR text straight to the voice, word for word. No model, no GPU. */
    private suspend fun readVerbatim(result: LocalPPOCRv6Runner.OcrResult, sharpness: Double) {
        if (!result.hasText) {
            val message = if (sharpness < Sharpness.USABLE_THRESHOLD) {
                getString(R.string.too_blurry)
            } else {
                result.displayText
            }
            setStatus(message)
            speech.speak(message)
            return
        }

        setStatus(result.displayText)
        // A trailing newline per region gives the chunker a clean cut on every line.
        speakStream(result.lines.asFlow().map { "$it\n" })
    }

    /** Lane 3: the model answers, with the image, the OCR block, the clip and the transcript. */
    private suspend fun ask(
        turn: SceneTurn?,
        ocr: LocalPPOCRv6Runner.OcrResult,
        clip: AudioCapture.Clip?,
        transcript: String?,
        sharpness: Double,
    ) {
        if (turn == null) {
            // Either the model is absent or the engine refused to come up. Saying so and then
            // reading the text is more use than an apology on its own.
            val notice = getString(R.string.model_unavailable)
            setStatus(notice)
            speech.speak(notice)
            if (ocr.hasText) readVerbatim(ocr, sharpness)
            return
        }

        setStatus(getString(R.string.thinking))
        try {
            speakStream(turn.ask(clip, transcript))
        } finally {
            turn.close()
        }
    }

    /**
     * Speaks a token stream, and throttles it.
     *
     * The suspension inside [SpeechQueue.speak] propagates back through this collector to whatever
     * is producing tokens. That is the whole thermal design: the model runs a sentence or two ahead
     * of the voice and then idles, instead of pinning the GPU for the length of the answer.
     */
    private suspend fun speakStream(tokens: Flow<String>) {
        val chunker = SentenceChunker()
        val spoken = StringBuilder()
        tokens.collect { fragment ->
            spoken.append(fragment)
            for (sentence in chunker.offer(fragment)) {
                speech.speak(sentence)
            }
        }
        chunker.flush()?.let { speech.speak(it) }
        if (spoken.isNotBlank()) setStatus(spoken.toString().trim())
    }

    private fun setStatus(text: String) {
        resultView.text = text
    }

    // ------------------------------------------------------------------ camera

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
            // COMPATIBLE backs the preview with a TextureView, which composites with sibling
            // views normally. The default PERFORMANCE mode uses a SurfaceView in its own layer,
            // which is the documented source of z-order trouble when views are drawn over the
            // preview - and this screen draws two.
            viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = viewFinder.surfaceProvider
            }

            // ImageAnalysis rather than ImageCapture: a rolling stream of frames gives the burst
            // for free and skips shutter lag, which matters when the capture has to be finished
            // before the user stops talking.
            val analysis = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::considerFrame) }

            // The back camera is the one that matters, but it is not always the one that exists -
            // an emulator without it, or a device whose camera is held by something else, should
            // still give the user a working app rather than a dead preview.
            val selector = CAMERA_PREFERENCE.firstOrNull { candidate ->
                runCatching { cameraProvider.hasCamera(candidate) }.getOrDefault(false)
            }
            if (selector == null) {
                Log.e(TAG, "No usable camera on this device")
                setStatus(getString(R.string.camera_required))
                return@addListener
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, analysis)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                setStatus(getString(R.string.camera_required))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on the analysis executor for every frame; keeps the sharpest one seen while collecting. */
    private fun considerFrame(proxy: ImageProxy) {
        try {
            val upright = rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            val score = Sharpness.score(upright)
            firstFrame.complete(Unit)
            synchronized(frameLock) {
                // A frame is either the burst's best or the standing latest, never both, so it
                // moves into one slot and the displaced bitmap is freed. No copies.
                if (collecting) {
                    if (score > bestScore) {
                        bestFrame?.recycle()
                        bestFrame = upright
                        bestScore = score
                    } else {
                        upright.recycle()
                    }
                } else {
                    latestFrame?.recycle()
                    latestFrame = upright
                    latestScore = score
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame scoring failed", e)
        } finally {
            proxy.close()
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated =
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun releaseCapturedFrame() {
        pendingOcr?.cancel()
        pendingOcr = null
        capturedFrame?.recycle()
        capturedFrame = null
        capturedScore = 0.0
    }

    // ------------------------------------------------------------------ lifecycle

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        if (ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show()
        }
        if (ContextCompat.checkSelfPermission(baseContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.mic_optional, Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        super.onStop()
        // Do not keep reading aloud once the app is no longer in front of the user.
        answerJob?.cancel()
        answerJob = null
        prefillJob?.cancel()
        prefillJob = null
        openTurn?.close()
        openTurn = null
        speech.stop()
        audio.release()
        collecting = false

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            sceneAnswerer = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        answerJob?.cancel()
        prefillJob?.cancel()
        openTurn?.close()
        speech.shutdown()
        audio.release()
        ocrRunner?.close()
        releaseCapturedFrame()
        synchronized(frameLock) {
            bestFrame?.recycle()
            bestFrame = null
            latestFrame?.recycle()
            latestFrame = null
        }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ImageSpeech"
        private const val REQUEST_CODE_PERMISSIONS = 10

        /**
         * How long the burst collects before committing to a frame. Long enough to see several
         * frames and reject a smeared one, short enough that OCR still overlaps the question.
         */
        private const val BURST_WINDOW_MS = 300L

        /**
         * How long a press will wait for the camera to produce its first frame. Generous, because
         * the alternative is telling the user to try again when the app simply was not ready yet.
         */
        private const val FIRST_FRAME_TIMEOUT_MS = 3_000L

        private val CAMERA_PREFERENCE = listOf(
            CameraSelector.DEFAULT_BACK_CAMERA,
            CameraSelector.DEFAULT_FRONT_CAMERA,
        )

        /**
         * Notifications are a runtime permission from API 33. Without it the download and engine
         * notifications are silently dropped, so the work still runs but the user loses the only
         * visible sign that a 1.9 GB transfer is happening.
         */
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }
}
