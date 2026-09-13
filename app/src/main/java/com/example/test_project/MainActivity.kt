package com.example.test_project

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.test_project.capture.CaptureFragment
import com.example.test_project.capture.ReviewFragment
import com.example.test_project.contract.ImageSpeechProcessor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Two screens: take the photo, then work through what was in it. */
class MainActivity : AppCompatActivity() {
    private val state: ImageSpeechViewModel by viewModels()
    val processor: ImageSpeechProcessor
        get() = state.processor
    val processorRouter get() = state.processor
    val hasGeminiKey: Boolean get() = state.keyStore.get() != null

    private lateinit var startupOverlay: View
    private lateinit var startupDetail: TextView

    /**
     * Completes when the camera screen has something to show - a first frame, or the news that
     * there will never be one. Both dismiss the startup cover; only one of them is good news, but
     * leaving the cover up on a device with no camera would look exactly like a hang.
     */
    private val previewSettled = CompletableDeferred<Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainRoot)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        startupOverlay = findViewById(R.id.startupOverlay)
        startupDetail = findViewById(R.id.txtStartupDetail)
        removeLegacyGemmaFiles()

        lifecycleScope.launch {
            runCatching { state.processor.prepareSpeech() }
                .onFailure { android.util.Log.w(TAG, "Speech not ready", it) }
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
     * Holds the cover until there is genuinely something behind it: the reader is open and the
     * camera has produced a frame. Until both are true the preview is a black rectangle, which
     * looks identical to a crash.
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

    fun showGeminiSettings() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_gemini_settings, null)
        val key = content.findViewById<EditText>(R.id.editApiKey)
        val show = content.findViewById<CheckBox>(R.id.checkShowKey)
        val status = content.findViewById<TextView>(R.id.txtProcessingStatus)
        key.setText(state.keyStore.get().orEmpty())
        status.setText(if (hasGeminiKey) R.string.gemini_processing else R.string.local_processing)
        show.setOnCheckedChangeListener { _, checked ->
            key.inputType = InputType.TYPE_CLASS_TEXT or
                if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.setSelection(key.text.length)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.processing_settings)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            fun applyValue(value: String) {
                if (state.keyStore.set(value)) {
                    (supportFragmentManager.findFragmentById(R.id.screen) as? ReviewFragment)
                        ?.cancelForSettingsChange()
                    state.processor.resetConversation()
                    state.cancelProcessing(clearState = true)
                }
                dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { applyValue(key.text.toString()) }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener { applyValue("") }
        }
        dialog.show()
    }

    /** Deletes exact artifacts left by builds that bundled the local Gemma question model. */
    private fun removeLegacyGemmaFiles() {
        val directory = File(getExternalFilesDir(null), "models")
        listOf(
            File(directory, LEGACY_GEMMA_FILE),
            File(directory, "$LEGACY_GEMMA_FILE.part"),
        ).forEach { file ->
            if (file.isFile && !file.delete()) {
                android.util.Log.w(TAG, "Could not remove obsolete local language model")
            }
        }
        if (directory.list()?.isEmpty() == true) directory.delete()
    }

    companion object {
        private const val TAG = "ImageSpeech"
        private const val REVIEW = "review"
        private const val LEGACY_GEMMA_FILE = "gemma-4-E2B-it-gpu.litertlm"
    }
}
