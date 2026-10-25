package com.example.test_project

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ReadingEvent
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.contract.TextBlock
import com.example.test_project.processing.ProcessorRouter
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewRunState(
    val requestIdentity: List<Int> = emptyList(),
    val action: ProcessingAction? = null,
    val status: String = "",
    val blocks: List<TextBlock> = emptyList(),
    val response: String = "",
    val failure: ReadingEvent.Failure? = null,
    val running: Boolean = false,
    val complete: Boolean = false,
)

/** A spoken passage tagged with the request that produced it. */
class QueuedAudio(val generation: Long, val audio: SpokenAudio)

class ImageSpeechViewModel(application: Application) : AndroidViewModel(application) {
    val processor = ProcessorRouter(application)
    private val _review = MutableStateFlow(ReviewRunState())
    val review = _review.asStateFlow()
    // Passages queue here while earlier ones play, so incoming text keeps appearing on screen
    // instead of waiting behind playback. Bounded by the model's output limit.
    private val _audio = MutableSharedFlow<QueuedAudio>(extraBufferCapacity = 64)
    val audio = _audio.asSharedFlow()

    /** Passages queued by a request that has since been replaced or stopped must not play. */
    fun isCurrent(queued: QueuedAudio): Boolean = queued.generation == generation
    private var active: Job? = null
    @Volatile private var generation = 0L
    private var lastRequest: ProcessingRequest? = null

    fun ensureInitial(request: ProcessingRequest) {
        val identity = request.identity()
        if (_review.value.requestIdentity != identity) start(request)
    }

    fun start(request: ProcessingRequest, forceLocal: Boolean = false) {
        lastRequest = request
        active?.cancel()
        val mine = ++generation
        _review.value = ReviewRunState(requestIdentity = request.identity(), action = request.action, running = true)
        active = viewModelScope.launch {
            try {
                val source = if (forceLocal) processor.processLocally(request) else processor.process(request)
                source.collect { event ->
                    if (mine != generation) return@collect
                    when (event) {
                        is ReadingEvent.Status -> _review.value = _review.value.copy(status = event.text)
                        is ReadingEvent.Block -> _review.value = _review.value.copy(blocks = _review.value.blocks + event.block)
                        is ReadingEvent.TextDelta -> _review.value = _review.value.copy(response = _review.value.response + event.text)
                        is ReadingEvent.Audio -> _audio.emit(QueuedAudio(mine, event.audio))
                        is ReadingEvent.Complete -> _review.value = _review.value.copy(
                            blocks = event.blocks,
                            response = event.responseText,
                            running = false,
                            complete = true,
                        )
                        is ReadingEvent.Failure -> _review.value = _review.value.copy(
                            response = event.partialText,
                            failure = event,
                            running = false,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (mine == generation) _review.value = _review.value.copy(
                    failure = ReadingEvent.Failure(error.message ?: "Processing failed.", true, true),
                    running = false,
                )
            }
        }
    }

    fun retry(forceLocal: Boolean = false) {
        lastRequest?.let { start(it, forceLocal) }
    }

    fun cancelProcessing(clearState: Boolean = false) {
        generation++
        active?.cancel()
        active = null
        if (clearState) lastRequest = null
        _review.value = if (clearState) ReviewRunState() else _review.value.copy(running = false)
    }

    /** Stops processing and waits for it, so native model memory can be freed safely afterwards. */
    suspend fun stopProcessingAndWait() {
        val running = active
        cancelProcessing(clearState = true)
        running?.cancelAndJoin()
    }

    private fun ProcessingRequest.identity(): List<Int> =
        (listOf(image) + additionalImages).map(System::identityHashCode)

    override fun onCleared() {
        active?.cancel()
        processor.close()
    }
}
