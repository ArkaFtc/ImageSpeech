package com.example.test_project.capture

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.test_project.MainActivity
import com.example.test_project.ImageSpeechViewModel
import com.example.test_project.R
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.contract.TextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Reviews a frozen capture and plays the processor's audio stream. */
class ReviewFragment : Fragment(R.layout.fragment_review) {
    private val shots: ShotViewModel by activityViewModels()
    private val appState: ImageSpeechViewModel by activityViewModels()
    private val host
        get() = requireActivity() as MainActivity

    private val recorder = AudioCapture()
    private var speech: SpeechInput? = null
    private var questionField: EditText? = null
    /** Whether this press is going through speech to text rather than raw audio. */
    private var transcribing = false
    private var voiceJob: Job? = null
    private var pressedAt = 0L
    private lateinit var status: TextView
    private lateinit var blockList: FrameLayout
    private lateinit var shotImage: ImageView
    private lateinit var player: AudioPlayer
    private lateinit var errorActions: View
    private var renderedBlocks: List<TextBlock> = emptyList()
    private var renderedResponse = ""

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status.setText(if (granted) R.string.mic_permission_then_hold else R.string.mic_denied)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val shot =
            shots.shot
                ?: run {
                    host.showCapture()
                    return
                }
        status = view.findViewById(R.id.txtReviewStatus)
        blockList = view.findViewById(R.id.blockList)
        player = AudioPlayer(requireContext())
        errorActions = view.findViewById(R.id.errorActions)
        shotImage = view.findViewById(R.id.imgShot)
        shotImage.setImageBitmap(shot.frame)
        shotImage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> renderOverlays() }
        view.findViewById<Button>(R.id.btnRetake).setOnClickListener {
            stopVoice()
            appState.cancelProcessing(clearState = true)
            shots.clearSubject()
            host.processorRouter.resetConversation()
            host.showCapture()
        }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopVoice()
            appState.cancelProcessing()
            host.showCapture()
        }
        view.findViewById<Button>(R.id.btnReadAll).setOnClickListener {
            process(null, action = ProcessingAction.READ_ALL)
        }
        view.findViewById<Button>(R.id.btnSummarize).setOnClickListener {
            if (!host.hasGemma) status.setText(R.string.gemma_required_for_questions)
            else process(null, action = ProcessingAction.SUMMARIZE)
        }
        view.findViewById<View>(R.id.btnReviewSettings).setOnClickListener { host.showProcessingSettings() }
        val typed = view.findViewById<EditText>(R.id.editQuestion)
        questionField = typed
        speech = SpeechInput(requireContext()) { heard ->
            typed.setText(heard)
            typed.setSelection(typed.text.length)
        }
        view.findViewById<Button>(R.id.btnTypedAsk).setOnClickListener {
            if (!host.hasGemma) {
                status.setText(R.string.gemma_required_for_questions)
            } else if (typed.text.toString().trim().isEmpty()) {
                status.setText(R.string.type_question_first)
            } else {
                process(null, action = ProcessingAction.ASK, question = typed.text.toString())
            }
        }
        view.findViewById<Button>(R.id.btnRetry).setOnClickListener { appState.retry() }
        view.findViewById<Button>(R.id.btnReadLocally).setOnClickListener {
            appState.retry(forceLocal = true)
        }
        wireAsk(view.findViewById(R.id.btnAsk))
        val clip = shot.audio
        shot.audio = null
        val spokenQuestion = shot.question
        shot.question = null
        if (spokenQuestion != null) typed.setText(spokenQuestion)
        observeProcessing()
        val initial = ProcessingRequest(
            shot.frame,
            shot.additionalFrames.toList(),
            clip,
            shot.mode,
            // Nothing is read aloud on its own: the photo is only made ready until the user asks.
            if (clip != null || spokenQuestion != null) ProcessingAction.ASK else ProcessingAction.PREPARE,
            spokenQuestion,
        )
        appState.ensureInitial(initial)
    }

    private fun process(
        clip: RecordedAudio?,
        action: ProcessingAction = ProcessingAction.READ,
        question: String? = null,
    ) {
        val shot = shots.shot ?: return
        // A new request replaces whatever is being spoken now.
        stopVoice()
        appState.start(
            ProcessingRequest(
                image = shot.frame,
                additionalImages = shot.additionalFrames.toList(),
                audio = clip,
                mode = shot.mode,
                action = action,
                question = question,
            )
        )
    }

    private fun observeProcessing() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appState.review.collect { state ->
                        errorActions.visibility = if (state.failure == null) View.GONE else View.VISIBLE
                        val failure = state.failure
                        status.text = when {
                            failure != null && state.response.isNotBlank() -> state.response + "\n\nIncomplete response. " + failure.message
                            failure != null -> failure.message
                            state.blocks.isNotEmpty() && state.complete ->
                                resources.getQuantityString(
                                    R.plurals.blocks_found,
                                    state.blocks.size,
                                    state.blocks.size,
                                ) + ". " + getString(R.string.tap_box_to_read)
                            state.response.isNotBlank() -> state.response
                            state.status.isNotBlank() -> state.status
                            else -> getString(R.string.reading_text)
                        }
                        view?.findViewById<Button>(R.id.btnRetry)?.visibility =
                            if (failure?.canRetry == true) View.VISIBLE else View.GONE
                        view?.findViewById<Button>(R.id.btnReadLocally)?.visibility =
                            if (failure?.canReadLocally == true) View.VISIBLE else View.GONE
                        if (renderedBlocks != state.blocks || renderedResponse != state.response && state.complete) {
                            renderedBlocks = state.blocks
                            renderedResponse = if (state.complete) state.response else ""
                            renderOverlays()
                        }
                    }
                }
                launch {
                    appState.audio.collect { queued ->
                        if (appState.isCurrent(queued)) player.play(queued.audio)
                    }
                }
            }
        }
    }

    private fun addResponsePassage(text: String) {
        if (text.isBlank()) return
        val button = Button(requireContext()).apply {
            this.text = text
            isAllCaps = false
            contentDescription = getString(R.string.replay_response)
            setOnClickListener { startVoice { play(host.processor.read(text)) } }
        }
        val margin = (32 * resources.displayMetrics.density).roundToInt()
        blockList.addView(
            button,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply { marginStart = margin; marginEnd = margin },
        )
    }

    private fun addBlock(block: TextBlock, position: Int) {
        val shot = shots.shot ?: return
        val description =
            getString(R.string.streaming_block_description, block.page, position, block.preview)
        val minSize = (32 * resources.displayMetrics.density).roundToInt()
        val bounds = TextOverlayLayout.project(
            block,
            shot.frame.width,
            shot.frame.height,
            blockList.width,
            blockList.height,
            minSize,
        ) ?: return
        val box = View(requireContext()).apply {
            setBackgroundResource(R.drawable.bg_text_overlay)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startVoice {
                    setBackgroundResource(R.drawable.bg_text_overlay_active)
                    try {
                        play(host.processor.read(block.text))
                    } finally {
                        setBackgroundResource(R.drawable.bg_text_overlay)
                    }
                }
            }
        }
        blockList.addView(
            box,
            FrameLayout.LayoutParams(bounds.width, bounds.height).apply {
                leftMargin = bounds.left
                topMargin = bounds.top
            },
        )
    }

    private fun renderOverlays() {
        if (!this::blockList.isInitialized || blockList.width == 0 || !this::shotImage.isInitialized) return
        blockList.removeAllViews()
        renderedBlocks.forEachIndexed { index, block -> addBlock(block, index + 1) }
        if (renderedBlocks.isEmpty() && renderedResponse.isNotBlank()) {
            addResponsePassage(renderedResponse)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireAsk(button: Button) {
        val holding = HoldFeedback(button)
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopVoice()
                    if (!host.hasGemma) {
                        status.setText(R.string.gemma_required_for_questions)
                        return@setOnTouchListener true
                    }
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnTouchListener true
                    }
                    view.isPressed = true
                    holding.show()
                    pressedAt = SystemClock.elapsedRealtime()
                    status.setText(R.string.listening)
                    val recognizer = speech
                    transcribing = recognizer?.isAvailable == true
                    if (transcribing) {
                        questionField?.setText("")
                        recognizer?.start()
                    } else {
                        recorder.start()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    holding.hide()
                    if (!host.hasGemma || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@setOnTouchListener true
                    val heldMs = SystemClock.elapsedRealtime() - pressedAt
                    if (transcribing) {
                        status.setText(R.string.finishing_question)
                        speech?.finish { question ->
                            if (!isAdded) return@finish
                            if (question.isBlank()) status.setText(R.string.nothing_heard)
                            else process(null, ProcessingAction.ASK, question = question)
                        }
                    } else {
                        process(PressPolicy.audioFor(heldMs, recorder.stop()), ProcessingAction.ASK)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    holding.hide()
                    speech?.cancel()
                    recorder.release()
                    status.setText(R.string.hold_to_ask)
                    true
                }
                else -> false
            }
        }
    }

    private fun startVoice(work: suspend () -> Unit) {
        val previous = voiceJob
        stopVoice()
        voiceJob =
            viewLifecycleOwner.lifecycleScope.launch {
                previous?.cancelAndJoin()
                try {
                    work()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text = error.message ?: getString(R.string.tts_unavailable)
                }
            }
    }

    private suspend fun play(stream: Flow<SpokenAudio>) {
        stream.collect { audio ->
            status.text = audio.text
            player.play(audio)
        }
    }

    private fun stopVoice() {
        voiceJob?.cancel()
        player.stop()
    }

    override fun onDestroyView() {
        speech?.destroy()
        speech = null
        questionField = null
        super.onDestroyView()
    }

    fun cancelForSettingsChange() {
        stopVoice()
        appState.cancelProcessing(clearState = true)
        if (this::status.isInitialized) {
            status.setText(R.string.processing_changed)
        }
    }

    override fun onStop() {
        stopVoice()
        if (isRemoving || requireActivity().isFinishing) appState.cancelProcessing()
        speech?.cancel()
        recorder.release()
        super.onStop()
    }
}
