package com.example.test_project.capture

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.test_project.MainActivity
import com.example.test_project.R
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ReadingEvent
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.contract.TextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Reviews a frozen capture and plays the processor's audio stream. */
class ReviewFragment : Fragment(R.layout.fragment_review) {
    private val shots: ShotViewModel by activityViewModels()
    private val host
        get() = requireActivity() as MainActivity

    private val recorder = AudioCapture()
    private var voiceJob: Job? = null
    private var pressedAt = 0L
    private lateinit var status: TextView
    private lateinit var blockList: LinearLayout
    private lateinit var player: AudioPlayer

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
        view.findViewById<ImageView>(R.id.imgShot).setImageBitmap(shot.frame)
        view.findViewById<Button>(R.id.btnRetake).setOnClickListener {
            stopVoice()
            host.showCapture()
        }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopVoice()
            host.showCapture()
        }
        view.findViewById<Button>(R.id.btnReadAll).setOnClickListener { process(null) }
        wireAsk(view.findViewById(R.id.btnAsk))
        val clip = shot.audio
        shot.audio = null
        process(clip, speak = savedInstanceState == null)
    }

    private fun process(clip: RecordedAudio?, speak: Boolean = true) = startVoice {
        val shot = shots.shot ?: return@startVoice
        status.setText(R.string.reading_text)
        blockList.removeAllViews()
        var count = 0
        coroutineScope {
            val playback = Channel<SpokenAudio>(1)
            launch {
                for (audio in playback) {
                    status.text = audio.text
                    player.play(audio)
                }
            }
            try {
                host.processor.process(ProcessingRequest(shot.frame, clip, shot.mode)).collect {
                    event ->
                    when (event) {
                        is ReadingEvent.Status -> status.text = event.text
                        is ReadingEvent.Block -> addBlock(event.block, ++count)
                        is ReadingEvent.Audio ->
                            if (speak) {
                                playback.send(event.audio)
                            }
                        is ReadingEvent.Complete ->
                            status.text =
                                resources.getQuantityString(
                                    R.plurals.blocks_found,
                                    event.blocks.size,
                                    event.blocks.size,
                                )
                    }
                }
            } finally {
                playback.close()
            }
        }
    }

    private fun addBlock(block: TextBlock, position: Int) {
        val description =
            getString(R.string.streaming_block_description, block.page, position, block.preview)
        val button =
            (LayoutInflater.from(requireContext())
                    .inflate(R.layout.view_text_block, blockList, false) as Button)
                .apply {
                    text =
                        if (block.uncertain)
                            getString(R.string.uncertain_passage) + "\n" + block.text
                        else block.text
                    contentDescription = description
                    setOnClickListener {
                        startVoice {
                            setBackgroundResource(R.drawable.bg_text_block_active)
                            try {
                                play(host.processor.read(text.toString()))
                            } finally {
                                setBackgroundResource(R.drawable.bg_text_block)
                            }
                        }
                    }
                }
        blockList.addView(button)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireAsk(button: Button) {
        button.setOnClickListener { process(null) }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stopVoice()
                    view.isPressed = true
                    pressedAt = SystemClock.elapsedRealtime()
                    status.setText(R.string.listening)
                    recorder.start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    val heldMs = SystemClock.elapsedRealtime() - pressedAt
                    process(PressPolicy.audioFor(heldMs, recorder.stop()))
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
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
    }

    override fun onStop() {
        stopVoice()
        recorder.release()
        super.onStop()
    }
}
