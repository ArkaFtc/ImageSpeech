package com.example.test_project

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two screens: take the photo, then work through what was in it.
 *
 * Splitting them is what makes the text browsable. A live preview has to answer "read it now", so
 * everything it found collapses into one utterance the user cannot steer; a frozen shot can offer
 * the page as blocks, so the header can be skipped, the third paragraph heard twice, and a question
 * asked about the same frame - none of which survives the camera moving.
 *
 * The Activity owns nothing about either screen. It holds the things that are too expensive to
 * rebuild when one replaces the other: the OCR engine, the voice, and the binding to the model.
 */
class MainActivity : AppCompatActivity() {

    /**
     * Opening the two ONNX models copies and maps ~138 MB, which is far too much to do on the main
     * thread - that stall was most of the black screen at launch. Everything that needs the engine
     * awaits this instead.
     */
    private val ocrEngineReady = CompletableDeferred<LocalPPOCRv6Runner?>()

    /** The same instance the deferred resolves to, kept for teardown without awaiting. */
    @Volatile
    private var ocrRunner: LocalPPOCRv6Runner? = null

    lateinit var speech: SpeechQueue
        private set

    lateinit var transcriber: Transcriber
        private set

    /**
     * The model lane, owned by [InferenceService] so it survives the Activity going away. Null
     * until the binding lands; every use treats that as "not available yet", which is the same
     * path a device without the model takes.
     */
    var sceneAnswerer: SceneAnswerer? = null
        private set

    private var serviceBound = false

    private lateinit var startupOverlay: View
    private lateinit var startupDetail: TextView
    private lateinit var downloadBanner: View
    private lateinit var downloadBar: ProgressBar
    private lateinit var downloadDetail: TextView

    /**
     * Completes when the camera screen has something to show - a first frame, or the news that
     * there will never be one. Both dismiss the startup cover; only one of them is good news, but
     * leaving the cover up on a device with no camera would look exactly like a hang.
     */
    private val previewSettled = CompletableDeferred<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        speech = SpeechQueue(this)
        transcriber = Transcriber(this)

        startupOverlay = findViewById(R.id.startupOverlay)
        startupDetail = findViewById(R.id.txtStartupDetail)
        downloadBanner = findViewById(R.id.downloadBanner)
        downloadBar = findViewById(R.id.barDownload)
        downloadDetail = findViewById(R.id.txtDownloadDetail)
        insetDownloadBanner()

        loadOcrEngine()
        dismissStartupWhenReady()

        lifecycleScope.launch {
            if (!speech.awaitReady()) {
                Toast.makeText(this@MainActivity, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.screen, CaptureFragment())
            }
        }

        startModelDownloadIfNeeded()
    }

    // ------------------------------------------------------------------ shared machinery

    /** Suspends until the reader is open. Null means it could not be opened on this device. */
    suspend fun ocrEngine(): LocalPPOCRv6Runner? = ocrEngineReady.await()

    /** Called by the camera screen once the preview has resolved one way or the other. */
    fun onPreviewSettled() {
        previewSettled.complete(Unit)
    }

    /** Moves to the review screen. Back returns to the camera, which is also "take another". */
    fun showReview() {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.screen, ReviewFragment())
            addToBackStack(REVIEW)
        }
    }

    /** Returns to the camera. Same entry as system back, so there is one way back to the preview. */
    fun showCapture() {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.popBackStack(REVIEW, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun loadOcrEngine() {
        lifecycleScope.launch {
            val runner = withContext(Dispatchers.IO) {
                runCatching { LocalPPOCRv6Runner(this@MainActivity) }
                    .onFailure { Log.e(TAG, "OCR engine failed to load", it) }
                    .getOrNull()
            }
            ocrRunner = runner
            ocrEngineReady.complete(runner)
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
            ocrEngineReady.await()
            startupDetail.text = getString(R.string.startup_camera)
            previewSettled.await()
            startupOverlay.visibility = View.GONE
        }
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

    override fun onStart() {
        super.onStart()
        // Bound rather than started: the engine should outlive a rotation, not the app.
        serviceBound = bindService(
            Intent(this, InferenceService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE,
        )
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

    /** The banner sits at the very top, so it has to make room for the status bar itself. */
    private fun insetDownloadBanner() {
        val basePadding = downloadBanner.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(downloadBanner) { view, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = basePadding + statusBar)
            insets
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStop() {
        super.onStop()
        // Do not keep reading aloud once the app is no longer in front of the user.
        speech.stop()

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            sceneAnswerer = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech.shutdown()
        ocrRunner?.close()
    }

    companion object {
        private const val TAG = "ImageSpeech"
        private const val REVIEW = "review"
    }
}
