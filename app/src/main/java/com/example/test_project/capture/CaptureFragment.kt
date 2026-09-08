package com.example.test_project.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.test_project.MainActivity
import com.example.test_project.R
import com.example.test_project.contract.RecordedAudio
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Screen one: aim, then take the shot. */
class CaptureFragment : Fragment(R.layout.fragment_capture) {

    private val shots: ShotViewModel by activityViewModels()
    private val host get() = requireActivity() as MainActivity

    private lateinit var status: TextView
    private lateinit var shutter: Button

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // ---- frame state, touched only from the main thread or under frameLock ----
    private val frameLock = Any()
    @Volatile private var acceptingFrames = false
    @Volatile private var collecting = false

    /** Sharpest frame seen since the shutter. */
    private var bestFrame: Bitmap? = null
    private var bestScore = 0.0

    /**
     * The newest frame from outside a burst, kept so the shutter always has something to work with
     * even if the burst window somehow sees nothing.
     */
    private var latestFrame: Bitmap? = null
    private var latestScore = 0.0

    /** Completes when the analysis pipeline delivers its first frame. */
    @Volatile
    private var firstFrame = CompletableDeferred<Unit>()

    private var captureJob: Job? = null
    private val audio = AudioCapture()
    private var pressedAt = 0L
    private var cameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Read the granted state back rather than trusting the result map: only the permissions
        // still missing are asked for, so anything already held is absent from it.
        if (isGranted(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), R.string.camera_required, Toast.LENGTH_LONG).show()
            status.text = getString(R.string.camera_required)
            // Nothing will ever arrive from the preview, so stop the startup cover waiting for it.
            host.onPreviewSettled()
        }
        if (!isGranted(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(requireContext(), R.string.mic_optional, Toast.LENGTH_LONG).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        acceptingFrames = true
        firstFrame = CompletableDeferred()
        status = view.findViewById(R.id.txtCaptureStatus)
        shutter = view.findViewById(R.id.btnShutter)
        wireShutter()

        // Every missing permission, not just the camera. Gating the whole prompt on the camera
        // left the microphone unasked for good once the camera had been granted, and a press then
        // recorded silence - which the ask button reports as not having heard a question.
        val missing = REQUIRED_PERMISSIONS.filterNot(::isGranted)
        if (missing.isEmpty()) {
            startCamera()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        // Coming back from review means the last shot has been dealt with; the button is live again.
        shutter.isEnabled = true
        status.text = getString(R.string.capture_hint)
    }

    // ------------------------------------------------------------------ the shutter

    @SuppressLint("ClickableViewAccessibility")
    private fun wireShutter() {
        shutter.setOnClickListener { capture() }
        shutter.setOnTouchListener { button, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressedAt = SystemClock.elapsedRealtime()
                    button.isPressed = true
                    audio.start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    button.isPressed = false
                    val heldMs = SystemClock.elapsedRealtime() - pressedAt
                    capture(PressPolicy.audioFor(heldMs, audio.stop()))
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    button.isPressed = false
                    audio.release()
                    true
                }
                else -> false
            }
        }
    }

    private fun capture(clip: RecordedAudio? = null) {
        if (captureJob?.isActive == true) return
        shutter.isEnabled = false

        captureJob = viewLifecycleOwner.lifecycleScope.launch {
            setStatus(getString(R.string.capturing))

            // A press can land before the camera has produced anything - on a cold start, or on
            // the first press after resuming. Give the pipeline a moment to deliver rather than
            // reporting failure to someone who has no way to see that the preview was not ready.
            if (!hasAnyFrame()) {
                withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) { firstFrame.await() }
            }

            val captured = runBurst()
            if (captured == null) {
                fail(getString(R.string.no_frame))
                return@launch
            }
            val (frame, sharpness) = captured

            cameraProvider?.unbindAll()
            shots.hold(Shot(frame, sharpness, clip))
            host.showReview()
        }
    }

    /** Collects for [BURST_WINDOW_MS] and returns the sharpest frame with its score. */
    private suspend fun runBurst(): Pair<Bitmap, Double>? {
        synchronized(frameLock) {
            bestFrame?.recycle()
            bestFrame = null
            bestScore = 0.0
        }
        collecting = true
        try { delay(BURST_WINDOW_MS) } finally { collecting = false }

        return synchronized(frameLock) {
            val winner = bestFrame ?: latestFrame
            val score = if (bestFrame != null) bestScore else latestScore
            if (bestFrame != null) bestFrame = null else latestFrame = null
            winner?.let { it to score }
        }
    }

    private fun fail(message: String) {
        setStatus(message)
        shutter.isEnabled = true
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    /** Whether any frame is on hand yet. */
    private fun hasAnyFrame(): Boolean =
        synchronized(frameLock) { bestFrame != null || latestFrame != null }

    // ------------------------------------------------------------------ camera

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(requireContext(), permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val context = requireContext()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            // The listener is not lifecycle-aware, so the view may already be gone by now.
            if (!isAdded || view == null) return@addListener
            val cameraProvider = cameraProviderFuture.get()
            this.cameraProvider = cameraProvider

            val viewFinder = requireView().findViewById<PreviewView>(R.id.viewFinder)
            // COMPATIBLE backs the preview with a TextureView, which composites with sibling
            // views normally. The default PERFORMANCE mode uses a SurfaceView in its own layer,
            // which is the documented source of z-order trouble when views are drawn over the
            // preview - and this screen draws two.
            viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = viewFinder.surfaceProvider
            }

            // ImageAnalysis rather than ImageCapture: a rolling stream of frames gives the burst
            // for free and skips shutter lag, so the sharpest of several frames can be chosen
            // without asking the user to hold still for a second capture.
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
                host.onPreviewSettled()
                return@addListener
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                setStatus(getString(R.string.camera_required))
                host.onPreviewSettled()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Runs on the analysis executor for every frame; keeps the sharpest one seen while collecting. */
    private fun considerFrame(proxy: ImageProxy) {
        try {
            if (!acceptingFrames) return
            val upright = rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
            val score = Sharpness.score(upright)
            if (firstFrame.complete(Unit)) host.onPreviewSettled()
            synchronized(frameLock) {
                if (!acceptingFrames) {
                    upright.recycle()
                    return
                }
                // A frame is either the burst best or the standing latest, never both, so it moves
                // into one slot and the displaced bitmap is freed. No copies.
                if (collecting) {
                    if (bestFrame == null || score > bestScore) {
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
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStop() {
        captureJob?.cancel()
        collecting = false
        audio.release()
        shutter.isPressed = false
        super.onStop()
    }

    override fun onDestroyView() {
        acceptingFrames = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        audio.release()
        super.onDestroyView()
        captureJob?.cancel()
        captureJob = null
        collecting = false
        synchronized(frameLock) {
            bestFrame?.recycle()
            bestFrame = null
            latestFrame?.recycle()
            latestFrame = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ImageSpeech"

        /**
         * How long the burst collects before committing to a frame. Long enough to see several
         * frames and reject a smeared one, short enough that the shutter still feels immediate.
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
