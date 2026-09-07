package com.example.test_project

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Screen two: what was in the shot, one block at a time.
 *
 * The frame is frozen, which is what makes everything here possible. Blocks can be replayed, the
 * order can be skipped around, and a question can be asked about exactly the picture that was read
 * - none of which is true of a live preview, where the answer is already stale by the time it is
 * spoken.
 *
 * Two ways to hear something, and they stay strictly apart. Tapping a block speaks the recognized
 * text word for word, with no model involved. Holding the ask button sends the frame, the OCR block
 * and the question to the model. The first is the common case and must never wake the GPU.
 */
class ReviewFragment : Fragment(R.layout.fragment_review) {

    private val shots: ShotViewModel by activityViewModels()
    private val host get() = requireActivity() as MainActivity

    private lateinit var status: TextView
    private lateinit var blockList: LinearLayout

    private val audio = AudioCapture()

    private var blocks: List<TextBlock> = emptyList()

    /** What this screen says when it has nothing more specific to report. */
    private var summary = ""

    private var blockViews: List<TextView> = emptyList()

    /**
     * Everything the voice is currently doing - reading a block, reading the page, or streaming an
     * answer. One job rather than several because they are alternatives, not layers: starting any
     * of them means the others are over.
     */
    private var voiceJob: Job? = null

    /** The turn being opened, or already open, ahead of a question. See [prepareTurn]. */
    private var prefillJob: Job? = null
    private var readyTurn: SceneTurn? = null

    private var pressStartMs = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        status = view.findViewById(R.id.txtReviewStatus)
        blockList = view.findViewById(R.id.blockList)

        val shot = shots.shot
        if (shot == null) {
            // Nothing to review - the process was rebuilt without the frame. The camera is the
            // only screen that can produce one, so go back rather than showing an empty page.
            host.showCapture()
            return
        }

        view.findViewById<ImageView>(R.id.imgShot).setImageBitmap(shot.frame)
        view.findViewById<Button>(R.id.btnRetake).setOnClickListener { retake() }
        view.findViewById<Button>(R.id.btnReadAll).setOnClickListener {
            startVoice { readEverything() }
        }
        wireAskButton(view.findViewById(R.id.btnAsk))

        blocks = shot.blocks
        renderBlocks()

        summary = summarise(shot)
        setStatus(summary)
        // Only on the way in. A rotation rebuilds this screen, and hearing the summary again every
        // time the phone turns would be maddening.
        if (savedInstanceState == null) startVoice { host.speech.speak(summary) }

        prepareTurn()
    }

    // ------------------------------------------------------------------ the blocks

    private fun renderBlocks() {
        val inflater = LayoutInflater.from(requireContext())
        blockViews = blocks.mapIndexed { index, block ->
            val view = inflater.inflate(R.layout.view_text_block, blockList, false) as TextView
            view.text = block.text
            // Read out as "Block 2 of 5" before the text itself, so a screen reader user knows
            // where they are in the page without having to hear the whole thing first.
            view.contentDescription =
                getString(R.string.block_description, index + 1, blocks.size, block.preview)
            markAsButton(view)
            view.setOnClickListener { startVoice { readBlock(index) } }
            blockList.addView(view)
            view
        }
    }

    /** A TextView that acts like a button has to say so, or TalkBack will not offer to activate it. */
    private fun markAsButton(view: View) {
        ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        })
    }

    private suspend fun readBlock(index: Int) {
        highlight(index)
        speakText(blocks[index].text)
    }

    private suspend fun readEverything() {
        if (blocks.isEmpty()) {
            host.speech.speak(status.text.toString())
            return
        }
        for (index in blocks.indices) {
            highlight(index)
            speakText(blocks[index].text)
        }
    }

    /**
     * Marks the block the voice is on, and scrolls it into view.
     *
     * It trails the voice by up to [SpeechQueue.MAX_PENDING] queued utterances plus the one being
     * spoken, because queueing is what returns rather than speaking. Tightening that would mean
     * waiting for each utterance to finish before queueing the next, which is exactly the
     * gap-between-sentences the queue exists to avoid - so the highlight is deliberately
     * approximate.
     */
    private fun highlight(index: Int?) {
        blockViews.forEachIndexed { position, view ->
            val active = position == index
            view.setBackgroundResource(
                if (active) R.drawable.bg_text_block_active else R.drawable.bg_text_block
            )
            if (active) {
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
            }
        }
    }

    private fun summarise(shot: Shot): String = when {
        blocks.isNotEmpty() -> getString(
            R.string.review_summary,
            resources.getQuantityString(R.plurals.blocks_found, blocks.size, blocks.size),
        )

        shot.sharpness < Sharpness.USABLE_THRESHOLD -> getString(R.string.too_blurry)
        else -> getString(R.string.no_text_found)
    }

    private fun retake() {
        stopVoice()
        host.showCapture()
    }

    // ------------------------------------------------------------------ asking

    @SuppressLint("ClickableViewAccessibility")
    private fun wireAskButton(button: Button) {
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
        stopVoice()
        pressStartMs = SystemClock.elapsedRealtime()
        setStatus(getString(R.string.listening))

        if (!audio.start()) {
            Log.w(TAG, "Microphone unavailable; the ask button will only read the page")
        }
    }

    private fun onRelease(cancelled: Boolean) {
        val holdMs = SystemClock.elapsedRealtime() - pressStartMs
        val clip = audio.stop()

        if (cancelled) {
            setStatus(summary)
            return
        }

        startVoice {
            val transcript = clip?.let { host.transcriber.transcribe(it) }
            val decision = IntentRouter.route(holdMs, clip?.hasSpeech == true, transcript)
            Log.d(TAG, "hold=${holdMs}ms -> ${decision.route} (${decision.because})")

            // A held press with nothing in it is a question that did not get recorded - a covered
            // microphone, or a start that lost the race with the speaking. Reading the page back
            // would look like the app ignored the question, so say what happened instead.
            if (holdMs >= IntentRouter.TAP_THRESHOLD_MS && clip?.hasSpeech != true) {
                // Digital silence is not a quiet question - the microphone is muted, blocked or
                // unpermitted - and asking the user to speak again would never get them anywhere.
                val notice = getString(
                    if (clip == null || clip.isSilent) R.string.mic_silent else R.string.nothing_heard
                )
                setStatus(notice)
                host.speech.speak(notice)
                return@startVoice
            }

            when (decision.route) {
                IntentRouter.Route.READ_VERBATIM -> readEverything()
                IntentRouter.Route.ASK_MODEL -> ask(clip, transcript)
            }
        }
    }

    private suspend fun ask(clip: AudioCapture.Clip?, transcript: String?) {
        val turn = takeTurn()
        if (turn == null) {
            // Either the model is absent or the engine refused to come up. Saying so and then
            // reading the text is more use than an apology on its own.
            val notice = getString(R.string.model_unavailable)
            setStatus(notice)
            host.speech.speak(notice)
            if (blocks.isNotEmpty()) readEverything()
            return
        }

        setStatus(getString(R.string.thinking))
        try {
            speakStream(turn.ask(clip, transcript))
        } finally {
            turn.close()
            // The next question is almost always coming, and the user is about to spend a few
            // seconds listening to this answer - which is exactly the time the next prefill costs.
            prepareTurn()
        }
    }

    /**
     * Opens a turn ahead of the question being asked.
     *
     * The prefill is the expensive half - encoding the frame and the OCR block - and it depends on
     * nothing the user has said yet. Starting it when the screen opens, and again after each
     * answer, hides it under time the user was going to spend reading anyway.
     */
    private fun prepareTurn() {
        if (readyTurn != null || prefillJob?.isActive == true) return
        val answerer = host.sceneAnswerer ?: return
        if (!answerer.isAvailable) return
        val shot = shots.shot ?: return

        prefillJob = viewLifecycleOwner.lifecycleScope.launch {
            readyTurn = answerer.beginTurn(shot.frame, shot.ocr)
        }
    }

    /** The prefilled turn if there is one, otherwise one opened now. Null when there is no model. */
    private suspend fun takeTurn(): SceneTurn? {
        prefillJob?.join()
        readyTurn?.let {
            readyTurn = null
            return it
        }
        val answerer = host.sceneAnswerer ?: return null
        if (!answerer.isAvailable) return null
        val shot = shots.shot ?: return null
        return answerer.beginTurn(shot.frame, shot.ocr)
    }

    // ------------------------------------------------------------------ the voice

    /**
     * Hands the voice to [work], cancelling whatever it was doing.
     *
     * Every route to speech goes through here, which is what makes a new tap reliably interrupt
     * the old one - there is only ever one producer, and it is cancelled before the next starts.
     */
    private fun startVoice(work: suspend CoroutineScope.() -> Unit) {
        stopVoice()
        voiceJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                work()
            } finally {
                highlight(null)
            }
        }
    }

    private fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        host.speech.stop()
    }

    /** Speaks a whole block, cut into utterances so it can be interrupted between them. */
    private suspend fun speakText(text: String) {
        val chunker = SentenceChunker()
        for (sentence in chunker.offer(text)) host.speech.speak(sentence)
        chunker.flush()?.let { host.speech.speak(it) }
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
                host.speech.speak(sentence)
            }
        }
        chunker.flush()?.let { host.speech.speak(it) }
        if (spoken.isNotBlank()) setStatus(spoken.toString().trim())
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStop() {
        super.onStop()
        // Do not keep reading, generating or recording once the app is out of the way.
        stopVoice()
        prefillJob?.cancel()
        prefillJob = null
        readyTurn?.close()
        readyTurn = null
        audio.release()
    }

    override fun onStart() {
        super.onStart()
        // The turn was dropped on the way out; open the next one before it is needed.
        prepareTurn()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopVoice()
        prefillJob?.cancel()
        prefillJob = null
        readyTurn?.close()
        readyTurn = null
        audio.release()
        blockViews = emptyList()
    }

    private companion object {
        const val TAG = "ImageSpeech"
    }
}
