package com.example.test_project

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
import com.example.test_project.contract.ImageSpeechProcessor
import com.example.test_project.capture.CaptureFragment
import com.example.test_project.capture.ReviewFragment
import com.example.test_project.processing.LocalImageSpeechProcessor
import com.example.test_project.processing.model.InferenceService
import com.example.test_project.processing.model.ModelDownloadWorker
import com.example.test_project.processing.model.ModelStore
import com.example.test_project.processing.model.SceneAnswerer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Two screens: take the photo, then work through what was in it. */
class MainActivity : AppCompatActivity() {

    private lateinit var localProcessor: LocalImageSpeechProcessor
    val processor: ImageSpeechProcessor get() = localProcessor

    /**
     * The model lane, owned by [InferenceService] so it survives the Activity going away. Null
     * until the binding lands; every use treats that as "not available yet", which is the same
     * path a device without the model takes.
     */
    private var sceneAnswerer: SceneAnswerer? = null
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

        localProcessor = LocalImageSpeechProcessor(applicationContext) { sceneAnswerer }

        startupOverlay = findViewById(R.id.startupOverlay)
        startupDetail = findViewById(R.id.txtStartupDetail)
        downloadBanner = findViewById(R.id.downloadBanner)
        downloadBar = findViewById(R.id.barDownload)
        downloadDetail = findViewById(R.id.txtDownloadDetail)
        insetDownloadBanner()

        dismissStartupWhenReady()

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.screen, CaptureFragment())
            }
        }

        startModelDownloadIfNeeded()
    }

    // ------------------------------------------------------------------ shared machinery

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

    /**
     * Holds the cover until there is genuinely something behind it: the reader is open and the
     * camera has produced a frame. Until both are true the preview is a black rectangle, which
     * looks identical to a crash.
     */
    private fun dismissStartupWhenReady() {
        lifecycleScope.launch {
            startupDetail.text = getString(R.string.startup_text)
            localProcessor.awaitReady()
            if (supportFragmentManager.findFragmentById(R.id.screen) is ReviewFragment) {
                previewSettled.complete(Unit)
            }
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

    /** Queues the one-off model download and narrates it. */
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
                    }
                }

                if (info.state.isFinished) {
                    downloadBanner.visibility = View.GONE
                    if (info.state == WorkInfo.State.SUCCEEDED && announcedStart) {
                        Toast.makeText(this, R.string.download_finished, Toast.LENGTH_LONG).show()
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

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            sceneAnswerer = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        localProcessor.close()
    }

    companion object {
        private const val TAG = "ImageSpeech"
        private const val REVIEW = "review"
    }
}
