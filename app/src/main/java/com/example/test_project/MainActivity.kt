package com.example.test_project

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.example.test_project.capture.CaptureFragment
import com.example.test_project.capture.ReviewFragment
import com.example.test_project.contract.ImageSpeechProcessor
import com.example.test_project.settings.GeminiKeyChangeFlow
import com.example.test_project.settings.GeminiQrPayload
import com.example.test_project.settings.QrImageDecoder
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Two screens: take the photo, then work through what was in it. */
class MainActivity : AppCompatActivity() {
    private val state: ImageSpeechViewModel by viewModels()
    val processor: ImageSpeechProcessor
        get() = state.processor
    val processorRouter get() = state.processor
    val hasGeminiKey: Boolean get() = state.keyStore.get() != null

    private lateinit var startupOverlay: View
    private lateinit var startupDetail: TextView
    private var settingsDialog: AlertDialog? = null
    private var settingsKeyField: EditText? = null

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content == null) {
            Toast.makeText(this, R.string.qr_scan_cancelled, Toast.LENGTH_LONG).show()
        } else {
            acceptQrPayload(content)
        }
    }

    private val qrCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchQrScanner()
            else Toast.makeText(this, R.string.qr_camera_denied, Toast.LENGTH_LONG).show()
        }

    private val qrImagePicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) {
                Toast.makeText(this, R.string.qr_import_cancelled, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    QrImageDecoder.decode(contentResolver, uri)
                }
                when (result) {
                    is QrImageDecoder.Result.Content -> acceptQrPayload(result.value)
                    QrImageDecoder.Result.Oversized -> showQrMessage(R.string.qr_oversized)
                    QrImageDecoder.Result.Unreadable -> showQrMessage(R.string.qr_unreadable)
                }
            }
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

    fun showGeminiSettings(stagedKey: String? = null) {
        settingsDialog?.dismiss()
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_gemini_settings, null)
        val key = content.findViewById<EditText>(R.id.editApiKey)
        val show = content.findViewById<CheckBox>(R.id.checkShowKey)
        val status = content.findViewById<TextView>(R.id.txtProcessingStatus)
        val savedKey = state.keyStore.get()
        key.setText(stagedKey ?: savedKey.orEmpty())
        status.setText(if (hasGeminiKey) R.string.gemini_processing else R.string.local_processing)
        show.setOnCheckedChangeListener { _, checked ->
            key.inputType = InputType.TYPE_CLASS_TEXT or
                if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.setSelection(key.text.length)
        }
        content.findViewById<Button>(R.id.btnShareKeyQr).apply {
            isEnabled = savedKey != null
            setOnClickListener { savedKey?.let(::showSharedKeyQr) }
        }
        content.findViewById<Button>(R.id.btnScanKeyQr).setOnClickListener { requestQrScanner() }
        content.findViewById<Button>(R.id.btnImportKeyQr).setOnClickListener {
            qrImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.processing_settings)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.clear, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            fun applyValue(value: String) {
                GeminiKeyChangeFlow(
                    store = state.keyStore::set,
                    cancelUi = {
                        (supportFragmentManager.findFragmentById(R.id.screen) as? ReviewFragment)
                            ?.cancelForSettingsChange()
                    },
                    clearConversation = state.processor::resetConversation,
                    cancelProcessing = { state.cancelProcessing(clearState = true) },
                ).apply(value)
                dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { applyValue(key.text.toString()) }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener { applyValue("") }
        }
        dialog.setOnDismissListener {
            if (settingsDialog === dialog) {
                settingsDialog = null
                settingsKeyField = null
            }
        }
        settingsDialog = dialog
        settingsKeyField = key
        dialog.show()
    }

    private fun requestQrScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScanner()
        } else {
            qrCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScanner() {
        qrScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_key_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private fun acceptQrPayload(payload: String) {
        when (val decoded = GeminiQrPayload.decode(payload)) {
            is GeminiQrPayload.DecodeResult.Success -> {
                val field = settingsKeyField
                if (field == null || !field.isAttachedToWindow) {
                    showGeminiSettings(decoded.key)
                } else {
                    field.setText(decoded.key)
                    field.setSelection(field.text.length)
                }
                Toast.makeText(this, R.string.qr_key_staged, Toast.LENGTH_LONG).show()
            }
            is GeminiQrPayload.DecodeResult.UnsupportedVersion ->
                showQrMessage(R.string.qr_unsupported_version)
            GeminiQrPayload.DecodeResult.Unsupported -> showQrMessage(R.string.qr_unsupported)
            GeminiQrPayload.DecodeResult.Malformed -> showQrMessage(R.string.qr_unsupported)
        }
    }

    private fun showSharedKeyQr(key: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                runCatching {
                    BarcodeEncoder().encodeBitmap(
                        GeminiQrPayload.encode(key),
                        BarcodeFormat.QR_CODE,
                        QR_SIZE_PX,
                        QR_SIZE_PX,
                    )
                }.getOrNull()
            }
            if (bitmap == null) {
                showQrMessage(R.string.qr_generation_failed)
                return@launch
            }
            val padding = (24 * resources.displayMetrics.density).toInt()
            val content = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(ImageView(context).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    contentDescription = getString(R.string.shared_key_qr_description)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addView(TextView(context).apply {
                    setText(R.string.shared_key_warning)
                    textSize = 16f
                    setPadding(0, padding / 2, 0, padding / 2)
                })
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.shared_key_qr)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { bitmap.recycle() }
                .show()
        }
    }

    private fun showQrMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        private const val QR_SIZE_PX = 720
    }
}
