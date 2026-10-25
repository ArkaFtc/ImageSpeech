package com.example.test_project.capture

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.example.test_project.TextScale
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.contract.TextBlock
import com.example.test_project.profile.Profile
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
    private lateinit var statusScroll: View
    private var typing = false
    private var renderedBlocks: List<TextBlock> = emptyList()

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
        // Stop only stops talking; the photo and the conversation about it stay.
        view.findViewById<View>(R.id.btnStop).setOnClickListener {
            stopVoice()
            val state = appState.review.value
            // Getting the photo ready is silent, and stopping it would only delay the next answer.
            if (state.running && state.action == ProcessingAction.PREPARE) return@setOnClickListener
            // Also drops answer audio still queued to play.
            appState.cancelProcessing(stoppedStatus = getString(R.string.stopped))
        }
        view.findViewById<Button>(R.id.btnReadAll).setOnClickListener {
            process(null, action = ProcessingAction.READ_ALL)
        }
        view.findViewById<Button>(R.id.btnSummarize).setOnClickListener {
            if (!host.hasGemma) status.setText(R.string.gemma_required_for_questions)
            else process(null, action = ProcessingAction.SUMMARIZE)
        }
        view.findViewById<Button>(R.id.btnMoreDetail).setOnClickListener {
            if (!host.hasGemma) status.setText(R.string.gemma_required_for_questions)
            else process(null, action = ProcessingAction.MORE_DETAIL)
        }
        view.findViewById<View>(R.id.btnReviewSettings).setOnClickListener { host.showProcessingSettings() }
        view.findViewById<Button>(R.id.btnRepeat).setOnClickListener { repeatShownText() }
        view.findViewById<Button>(R.id.btnTextSmaller).setOnClickListener { host.changeTextSize(-1) }
        view.findViewById<Button>(R.id.btnTextLarger).setOnClickListener { host.changeTextSize(1) }
        statusScroll = view.findViewById(R.id.statusScroll)
        onTextScaleChanged()
        // The screen ends above the keyboard (MainActivity). While it is open, the text box takes
        // the room the photo had, so the answer and the question being typed are both visible.
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.reviewPanels)) { _, insets ->
            setTyping(insets.isVisible(WindowInsetsCompat.Type.ime()))
            insets
        }
        val typed = view.findViewById<EditText>(R.id.editQuestion)
        questionField = typed
        speech = SpeechInput(requireContext()) { heard ->
            typed.setText(heard)
            typed.setSelection(typed.text.length)
        }
        val typedAsk = view.findViewById<Button>(R.id.btnTypedAsk)
        typedAsk.setOnClickListener {
            if (!host.hasGemma) {
                status.setText(R.string.gemma_required_for_questions)
            } else if (typed.text.toString().trim().isEmpty()) {
                status.setText(R.string.type_question_first)
            } else {
                hideKeyboard(typed)
                process(null, action = ProcessingAction.ASK, question = typed.text.toString())
            }
        }
        typed.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                typedAsk.performClick()
                true
            } else {
                false
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
        // Nothing is read aloud on its own: the photo is only made ready until the user asks,
        // except for the check against the dietary restrictions in the profile.
        val dietary = Profile.dietary(requireContext())
        val action = when {
            clip != null || spokenQuestion != null -> ProcessingAction.ASK
            host.hasGemma && dietary.isNotBlank() -> ProcessingAction.DIET_CHECK
            else -> ProcessingAction.PREPARE
        }
        val initial = ProcessingRequest(
            shot.frame,
            shot.additionalFrames.toList(),
            clip,
            shot.mode,
            action,
            if (action == ProcessingAction.DIET_CHECK) dietary else spokenQuestion,
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
                        errorActions.visibility = if (state.failure == null || typing) View.GONE else View.VISIBLE
                        // Repeat speaks what is already shown; while an answer is still being
                        // written it would talk over it.
                        view?.findViewById<Button>(R.id.btnRepeat)?.isEnabled = !state.running
                        updateMoreDetail()
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
                        if (renderedBlocks != state.blocks) {
                            renderedBlocks = state.blocks
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
                    status.text = block.text
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
        // Gemma's answers have no position in the photo; they stay in the text box, where
        // Repeat says them again.
        renderedBlocks.forEachIndexed { index, block -> addBlock(block, index + 1) }
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

    /** The whole text stays in the box while it is spoken, so Repeat can say all of it again. */
    private suspend fun play(stream: Flow<SpokenAudio>) {
        stream.collect { audio -> player.play(audio) }
    }

    /** Speaks [text] with the phone's voice, showing it in the box. No new model request. */
    private fun speak(text: String) {
        if (text.isBlank()) return
        // Anything still queued from the last answer must not play over this.
        appState.cancelProcessing()
        startVoice {
            status.text = text
            play(host.processor.read(text))
        }
    }

    private fun repeatShownText() {
        speak(status.text.toString().trim())
    }

    /** The text box grows with the text size, up to a limit that keeps the photo in view. */
    fun onTextScaleChanged() {
        if (!this::statusScroll.isInitialized) return
        if (!typing) setTyping(false)
        // Large text no longer fits two buttons side by side without breaking words.
        val row = view?.findViewById<LinearLayout>(R.id.readActions) ?: return
        val stacked = host.textScale >= STACK_BUTTONS_AT_SCALE
        row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val gap = (8 * resources.displayMetrics.density).roundToInt()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            val params = child.layoutParams as LinearLayout.LayoutParams
            params.width = if (stacked) LinearLayout.LayoutParams.MATCH_PARENT else 0
            // Side by side, both buttons take the taller one's height so they line up.
            params.height = if (stacked) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT
            params.weight = if (stacked) 0f else 1f
            params.marginStart = if (stacked || i == 0) 0 else gap
            params.topMargin = if (stacked && i > 0) gap else 0
            child.layoutParams = params
        }
    }

    private fun setTyping(open: Boolean) {
        typing = open
        val view = view ?: return
        val params = statusScroll.layoutParams as LinearLayout.LayoutParams
        if (open) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = (STATUS_HEIGHT_DP * minOf(host.textScale, 1.6f) * resources.displayMetrics.density).roundToInt()
            params.weight = 0f
        }
        statusScroll.layoutParams = params
        val hiddenWhileTyping = if (open) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.reviewSpacer).visibility = hiddenWhileTyping
        view.findViewById<View>(R.id.textTools).visibility = hiddenWhileTyping
        view.findViewById<View>(R.id.readActions).visibility = hiddenWhileTyping
        view.findViewById<View>(R.id.btnAsk).visibility = hiddenWhileTyping
        if (open) errorActions.visibility = View.GONE
        else errorActions.visibility = if (appState.review.value.failure == null) View.GONE else View.VISIBLE
        updateMoreDetail()
    }

    /** More detail is offered once a summary, an answer or a dietary check has finished. */
    private fun updateMoreDetail() {
        val state = appState.review.value
        val offered = !typing && state.complete && state.failure == null && state.response.isNotBlank() &&
            state.action in MORE_DETAIL_AFTER
        view?.findViewById<View>(R.id.btnMoreDetail)?.visibility = if (offered) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard(field: EditText) {
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
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

    private companion object {
        const val STATUS_HEIGHT_DP = 140
        const val STACK_BUTTONS_AT_SCALE = 1.3f
        val MORE_DETAIL_AFTER = setOf(ProcessingAction.SUMMARIZE, ProcessingAction.ASK, ProcessingAction.DIET_CHECK)
    }

    override fun onStop() {
        stopVoice()
        if (isRemoving || requireActivity().isFinishing) appState.cancelProcessing()
        speech?.cancel()
        recorder.release()
        super.onStop()
    }
}
