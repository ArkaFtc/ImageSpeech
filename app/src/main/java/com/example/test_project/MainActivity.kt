package com.example.test_project

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.test_project.capture.CaptureFragment
import com.example.test_project.capture.ReviewFragment
import com.example.test_project.contract.ImageSpeechProcessor
import com.example.test_project.ondevice.GemmaConfig
import com.example.test_project.ondevice.GemmaDownloadWorker
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Two screens: take the photo, then work through what was in it. */
class MainActivity : AppCompatActivity() {
    private val state: ImageSpeechViewModel by viewModels()
    val processor: ImageSpeechProcessor
        get() = state.processor
    val processorRouter get() = state.processor

    /** Whether questions can be answered: the Gemma model is downloaded and able to start. */
    val hasGemma: Boolean get() = state.processor.isGemmaReady

    /** The app's text size, applied to every screen and dialog (see [TextScale]). */
    var textScale = 1f
        private set

    private lateinit var startupOverlay: View
    private lateinit var startupDetail: TextView
    private var settingsDialog: AlertDialog? = null
    private var settingsWatcher: Job? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The download runs either way; the permission only makes its progress visible.
            GemmaDownloadWorker.enqueue(this)
        }

    /**
     * Completes when the camera screen has something to show - a first frame, or the news that
     * there will never be one. Both dismiss the startup cover; only one of them is good news, but
     * leaving the cover up on a device with no camera would look exactly like a hang.
     */
    private val previewSettled = CompletableDeferred<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The app draws edge to edge, so Android no longer resizes it for the keyboard: the
        // screen ends above the keyboard here instead, keeping the question box visible.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom))
            insets
        }

        textScale = TextScale.load(this)
        TextScale.apply(findViewById(R.id.mainRoot), textScale)
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                    TextScale.apply(v, textScale)
                }
            },
            false,
        )

        startupOverlay = findViewById(R.id.startupOverlay)
        startupDetail = findViewById(R.id.txtStartupDetail)
        removeLegacyGemmaFile()

        lifecycleScope.launch {
            runCatching { state.processor.prepareSpeech() }
                .onFailure { android.util.Log.w(TAG, "Speech not ready", it) }
            prepareGemmaInBackground()
        }
        dismissStartupWhenReady()

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                replace(R.id.screen, CaptureFragment())
            }
        }
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

    /**
     * Returns to the camera. Same entry as system back, so there is one way back to the preview.
     */
    fun showCapture() {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.popBackStack(REVIEW, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    /**
     * Holds the cover until there is genuinely something behind it: the camera has produced a
     * frame. Until then the preview is a black rectangle, which looks identical to a crash.
     */
    private fun dismissStartupWhenReady() {
        lifecycleScope.launch {
            if (supportFragmentManager.findFragmentById(R.id.screen) is ReviewFragment) {
                previewSettled.complete(Unit)
            }
            startupDetail.text = getString(R.string.startup_camera)
            previewSettled.await()
            startupOverlay.visibility = View.GONE
        }
    }

    private suspend fun prepareGemmaInBackground() {
        runCatching { state.processor.prepareGemma() }
            .onFailure { android.util.Log.w(TAG, "Gemma not ready", it) }
    }

    // ------------------------------------------------------------------ text size

    /** One step smaller ([direction] -1) or larger (+1), applied everywhere at once and kept. */
    fun changeTextSize(direction: Int) {
        val next = TextScale.next(textScale, direction)
        val percent = TextScale.percent(next)
        val message = when {
            next == textScale && direction < 0 -> getString(R.string.text_size_smallest, percent)
            next == textScale -> getString(R.string.text_size_largest, percent)
            else -> getString(R.string.text_size_now, percent)
        }
        textScale = next
        TextScale.save(this, next)
        TextScale.apply(findViewById(R.id.mainRoot), next)
        settingsDialog?.window?.decorView?.let { TextScale.apply(it, next) }
        settingsDialog?.findViewById<TextView>(R.id.txtTextSizeValue)?.text = getString(R.string.text_size_now, percent)
        (supportFragmentManager.findFragmentById(R.id.screen) as? ReviewFragment)?.onTextScaleChanged()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ processing settings

    fun showProcessingSettings() {
        settingsDialog?.dismiss()
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_processing_settings, null)
        val status = content.findViewById<TextView>(R.id.txtProcessingStatus)
        val progress = content.findViewById<TextView>(R.id.txtDownloadProgress)
        val download = content.findViewById<Button>(R.id.btnDownloadGemma)
        val delete = content.findViewById<Button>(R.id.btnDeleteGemma)
        val enable = content.findViewById<Button>(R.id.btnEnableGemma)
        val router = state.processor
        content.findViewById<TextView>(R.id.txtTextSizeValue).text =
            getString(R.string.text_size_now, TextScale.percent(textScale))
        content.findViewById<View>(R.id.btnSettingsTextSmaller).setOnClickListener { changeTextSize(-1) }
        content.findViewById<View>(R.id.btnSettingsTextLarger).setOnClickListener { changeTextSize(1) }
        content.findViewById<View>(R.id.txtDeviceNote).visibility =
            if (router.gemmaProfile.useGpu) View.GONE else View.VISIBLE

        fun render(info: WorkInfo?) {
            val store = state.processor.modelStore
            val downloading = !store.isPresent && GemmaDownloadWorker.isActive(info)
            status.setText(
                when {
                    hasGemma -> R.string.gemma_processing
                    store.isPresent && router.gemmaHealth.isDisabled -> R.string.gemma_disabled_low_memory
                    store.isPresent -> R.string.gemma_failed_to_start
                    else -> R.string.local_processing
                },
            )
            progress.visibility = View.VISIBLE
            progress.text = when {
                store.isPresent -> getString(R.string.gemma_downloaded)
                info?.state == WorkInfo.State.RUNNING -> getString(
                    R.string.gemma_download_progress,
                    info.progress.getInt(GemmaDownloadWorker.KEY_PERCENT, GemmaDownloadWorker.percent(store.downloadedBytes)),
                )
                downloading -> getString(R.string.gemma_waiting_for_wifi)
                info?.state == WorkInfo.State.FAILED -> getString(R.string.gemma_download_failed)
                store.downloadedBytes > 0 -> getString(R.string.gemma_download_paused, GemmaDownloadWorker.percent(store.downloadedBytes))
                else -> getString(R.string.gemma_not_downloaded, GemmaConfig.EXPECTED_BYTES / 1_000_000_000.0)
            }
            download.visibility = if (store.isPresent) View.GONE else View.VISIBLE
            download.setText(if (downloading) R.string.cancel_download else R.string.download_gemma)
            download.setOnClickListener {
                if (downloading) GemmaDownloadWorker.cancel(this) else startGemmaDownload()
            }
            enable.visibility = if (store.isPresent && router.gemmaHealth.isDisabled) View.VISIBLE else View.GONE
            enable.setOnClickListener {
                router.enableGemma()
                render(info)
            }
            delete.visibility = if (store.isPresent || store.downloadedBytes > 0) View.VISIBLE else View.GONE
            delete.setOnClickListener { confirmDeleteGemma() }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.processing_settings)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        settingsWatcher?.cancel()
        settingsWatcher = lifecycleScope.launch {
            var wasPresent = state.processor.modelStore.isPresent
            WorkManager.getInstance(this@MainActivity)
                .getWorkInfosForUniqueWorkFlow(GemmaDownloadWorker.WORK_NAME)
                .collect { infos ->
                    val present = state.processor.modelStore.isPresent
                    if (present && !wasPresent) launch { prepareGemmaInBackground() }
                    wasPresent = present
                    render(infos.firstOrNull())
                }
        }
        dialog.setOnDismissListener {
            if (settingsDialog === dialog) {
                settingsDialog = null
                settingsWatcher?.cancel()
                settingsWatcher = null
            }
        }
        settingsDialog = dialog
        render(null)
        dialog.show()
        dialog.window?.decorView?.let { TextScale.apply(it, textScale) }
    }

    private fun startGemmaDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            GemmaDownloadWorker.enqueue(this)
        }
    }

    private fun confirmDeleteGemma() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_gemma_confirm)
            .setPositiveButton(R.string.delete_gemma) { _, _ -> deleteGemma() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Same order as any processing change: stop the UI and the running request, wait until it has
     * really stopped, and only then free the native model and delete its file.
     */
    private fun deleteGemma() {
        (supportFragmentManager.findFragmentById(R.id.screen) as? ReviewFragment)?.cancelForSettingsChange()
        lifecycleScope.launch {
            GemmaDownloadWorker.cancel(this@MainActivity)
            state.stopProcessingAndWait()
            withContext(Dispatchers.Default) { state.processor.releaseGemma() }
            withContext(Dispatchers.IO) { state.processor.modelStore.delete() }
            settingsDialog?.let { if (it.isShowing) showProcessingSettings() }
        }
    }

    /** Deletes the GPU-only Gemma file left by older builds; the current build uses another file. */
    private fun removeLegacyGemmaFile() {
        val directory = File(getExternalFilesDir(null), "models")
        listOf(File(directory, LEGACY_GEMMA_FILE), File(directory, "$LEGACY_GEMMA_FILE.part")).forEach { file ->
            if (file.isFile && !file.delete()) {
                android.util.Log.w(TAG, "Could not remove obsolete Gemma file")
            }
        }
    }

    companion object {
        private const val TAG = "ImageSpeech"
        private const val REVIEW = "review"
        private const val LEGACY_GEMMA_FILE = "gemma-4-E2B-it-gpu.litertlm"
    }
}
