package com.example.test_project

import android.graphics.Bitmap
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ReadingEvent
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.ondevice.GemmaConfig
import com.example.test_project.ondevice.GemmaImageSpeechProcessor
import com.example.test_project.ondevice.GemmaUnavailableException
import com.example.test_project.ondevice.ModelPrompt
import com.example.test_project.ondevice.ModelSession
import com.example.test_project.ondevice.ModelTurn
import com.example.test_project.ondevice.OnDeviceModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaImageSpeechProcessorTest {
    /** Scripted replies; each send() takes the next one. */
    private class FakeModel(vararg replies: Reply) : OnDeviceModel {
        sealed interface Reply
        class Chunks(vararg val parts: String) : Reply
        class FailAfter(vararg val parts: String) : Reply
        object Hang : Reply

        val pending = ArrayDeque(replies.toList())
        val opened = mutableListOf<List<ModelTurn>>()
        val prompts = mutableListOf<ModelPrompt>()
        val sessions = mutableListOf<FakeSession>()
        var unavailable = false
        val started = CompletableDeferred<Unit>()

        override val isLoaded = true

        val prefaces = mutableListOf<ModelPrompt?>()

        /** When set, opening (photo processing) waits for it, like the slow native prefill. */
        var openGate: CompletableDeferred<Unit>? = null
        val openStarted = CompletableDeferred<Unit>()

        /** Photos the model was given up front, per opened conversation. */
        fun photosAtOpen(index: Int) =
            opened[index].sumOf { it.prompt.images.size } + (prefaces[index]?.images?.size ?: 0)

        override suspend fun open(history: List<ModelTurn>, preface: ModelPrompt?): ModelSession {
            prefaces += preface
            openStarted.complete(Unit)
            openGate?.await()
            if (unavailable) throw GemmaUnavailableException("Gemma could not start on this phone.")
            opened += history
            return FakeSession().also { sessions += it }
        }

        override fun close() = Unit

        inner class FakeSession : ModelSession {
            var closed = false

            override fun send(prompt: ModelPrompt): Flow<String> = flow {
                check(!closed) { "send on a closed session" }
                prompts += prompt
                when (val reply = pending.removeFirst()) {
                    is Chunks -> reply.parts.forEach { emit(it) }
                    is FailAfter -> {
                        reply.parts.forEach { emit(it) }
                        throw IllegalStateException("native failure")
                    }
                    Hang -> {
                        emit("Partial ")
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            }

            override fun close() {
                closed = true
            }
        }
    }

    private val spoken = mutableListOf<String>()
    private val speak: suspend (String) -> SpokenAudio = { text ->
        spoken += text
        SpokenAudio(text, ByteArray(0))
    }

    private fun processor(model: FakeModel) = GemmaImageSpeechProcessor(
        model = model,
        speak = speak,
        encode = { bitmap -> byteArrayOf(System.identityHashCode(bitmap).toByte()) },
        clock = { 0L },
    )

    private fun read(image: Bitmap, extra: List<Bitmap> = emptyList()) =
        ProcessingRequest(image, extra, action = ProcessingAction.READ)

    private fun ask(image: Bitmap, question: String, extra: List<Bitmap> = emptyList()) =
        ProcessingRequest(image, extra, action = ProcessingAction.ASK, question = question)

    @Test
    fun streamsTextAndSpeaksCompleteSentencesInOrder() = runTest {
        val model = FakeModel(FakeModel.Chunks("The label says shake ", "well. Store below ", "twenty five degrees"))
        val events = processor(model).process(read(bitmap())).toList()

        val text = events.filterIsInstance<ReadingEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("The label says shake well. Store below twenty five degrees", text)
        assertEquals(listOf("The label says shake well.", "Store below twenty five degrees"), spoken)
        assertEquals(spoken, events.filterIsInstance<ReadingEvent.Audio>().map { it.audio.text })
        val complete = events.last() as ReadingEvent.Complete
        assertEquals(text, complete.responseText)
        assertTrue(complete.blocks.isEmpty())
    }

    @Test
    fun preparingProcessesThePhotoWithoutReadingAnything() = runTest {
        val model = FakeModel(FakeModel.Chunks("It is a cookie box."))
        val gemma = processor(model)
        val photo = bitmap()

        val prepared = gemma.process(ProcessingRequest(photo, action = ProcessingAction.PREPARE)).toList()

        assertEquals(1, model.photosAtOpen(0))
        assertEquals("the photo is not left as a trailing user message", null, model.prefaces[0])
        val photoTurn = model.opened[0].single()
        assertEquals(1, photoTurn.prompt.images.size)
        assertEquals(GemmaImageSpeechProcessor.PREFACE_REPLY, photoTurn.reply)
        assertTrue("nothing generated", model.prompts.isEmpty())
        assertTrue("nothing spoken", spoken.isEmpty())
        assertTrue(prepared.none { it is ReadingEvent.TextDelta || it is ReadingEvent.Audio })
        assertEquals("", (prepared.last() as ReadingEvent.Complete).responseText)
        assertTrue(prepared.any { it is ReadingEvent.Status && it.text == GemmaImageSpeechProcessor.PHOTO_READY })

        // The first real request reuses the processed photo.
        gemma.process(ask(photo, "What is it?")).toList()
        assertEquals(1, model.opened.size)
        assertEquals(0, model.prompts[0].images.size)
    }

    @Test
    fun summarizeIsItsOwnRequest() = runTest {
        val model = FakeModel(FakeModel.Chunks("A box of maple cookies."))
        processor(model).process(ProcessingRequest(bitmap(), action = ProcessingAction.SUMMARIZE)).toList()

        assertTrue(model.prompts[0].text.contains("one to three sentences"))
    }

    @Test
    fun moreDetailFollowsTheShortSummaryInTheSameConversation() = runTest {
        val model = FakeModel(FakeModel.Chunks("A box of maple cookies."), FakeModel.Chunks("They are leaf shaped. Serve with tea."))
        val gemma = processor(model)
        val photo = bitmap()

        gemma.process(ProcessingRequest(photo, action = ProcessingAction.SUMMARIZE)).toList()
        val detailed = gemma.process(ProcessingRequest(photo, action = ProcessingAction.SUMMARIZE_DETAILED)).toList()

        assertEquals("no second photo processing", 1, model.opened.size)
        assertTrue(model.prompts[1].text.startsWith("Give a more detailed summary"))
        assertEquals("They are leaf shaped. Serve with tea.", (detailed.last() as ReadingEvent.Complete).responseText)
    }

    @Test
    fun followUpReusesTheConversationWithoutResendingTheImage() = runTest {
        val model = FakeModel(FakeModel.Chunks("First answer."), FakeModel.Chunks("Second answer."))
        val gemma = processor(model)
        val photo = bitmap()

        gemma.process(read(photo)).toList()
        gemma.process(ask(photo, "What does it mean?")).toList()

        assertEquals(1, model.opened.size)
        assertEquals("the photo is processed when the conversation opens", 1, model.photosAtOpen(0))
        assertEquals(0, model.prompts[0].images.size)
        assertEquals(0, model.prompts[1].images.size)
        assertEquals("the question goes in exactly as asked", "What does it mean?", model.prompts[1].text)
    }

    @Test
    fun anotherViewSendsOnlyTheNewPhoto() = runTest {
        val model = FakeModel(FakeModel.Chunks("Front."), FakeModel.Chunks("OK."), FakeModel.Chunks("Back."))
        val gemma = processor(model)
        val front = bitmap()
        val back = bitmap()

        gemma.process(read(front)).toList()
        val events = gemma.process(ask(front, "And the back?", listOf(back))).toList()

        assertEquals(1, model.opened.size)
        assertEquals(GemmaImageSpeechProcessor.ANOTHER_VIEW_TEXT, model.prompts[1].text)
        assertEquals(1, model.prompts[1].images.size)
        assertArrayEquals(byteArrayOf(System.identityHashCode(back).toByte()), model.prompts[1].images[0])
        // The question comes in its own turn, and the photo's reply is neither shown nor spoken.
        assertEquals(0, model.prompts[2].images.size)
        assertTrue(model.prompts[2].text.contains("And the back?"))
        assertEquals("Back.", (events.last() as ReadingEvent.Complete).responseText)
        assertFalse(spoken.contains("OK."))
    }

    @Test
    fun failureKeepsPartialTextAndTheNextRequestRebuildsFromCompletedTurns() = runTest {
        val model = FakeModel(
            FakeModel.Chunks("It is a warranty card."),
            FakeModel.FailAfter("The cover"),
            FakeModel.Chunks("It lasts two years."),
        )
        val gemma = processor(model)
        val photo = bitmap()

        gemma.process(read(photo)).toList()
        val failed = gemma.process(ask(photo, "How long?")).toList().last() as ReadingEvent.Failure
        assertEquals("The cover", failed.partialText)
        assertTrue(failed.canRetry)
        assertTrue(failed.canReadLocally)
        assertTrue(model.sessions[0].closed)

        gemma.process(ask(photo, "How long?")).toList()
        assertEquals(2, model.opened.size)
        val rebuilt = model.opened[1]
        assertEquals(2, rebuilt.size)
        assertEquals(1, rebuilt[0].prompt.images.size)
        assertEquals(GemmaImageSpeechProcessor.PREFACE_REPLY, rebuilt[0].reply)
        assertEquals(0, rebuilt[1].prompt.images.size)
        assertEquals("It is a warranty card.", rebuilt[1].reply)
        assertEquals(null, model.prefaces[1])
        // The rebuilt conversation already holds the photo, so it is not attached again.
        assertEquals(0, model.prompts[2].images.size)
    }

    @Test
    fun rebuiltConversationHoldsThePhotoOnce() = runTest {
        val model = FakeModel(
            FakeModel.Chunks("A cookie box."),
            FakeModel.Chunks("Tea."),
            FakeModel.FailAfter(),
            FakeModel.Chunks("Yes."),
        )
        val gemma = processor(model)
        val photo = bitmap()

        gemma.process(read(photo)).toList()
        gemma.process(ask(photo, "What goes with them?")).toList()
        gemma.process(ask(photo, "Are they sweet?")).toList()
        gemma.process(ask(photo, "Are they sweet?")).toList()

        assertEquals(2, model.opened.size)
        assertEquals(1, model.photosAtOpen(1))
        assertEquals(listOf(GemmaImageSpeechProcessor.PREFACE_REPLY, "A cookie box.", "Tea."), model.opened[1].map { it.reply })
        assertTrue("no question is sent with a photo", model.prompts.all { it.images.isEmpty() })
    }

    @Test
    fun cancelledReadKeepsTheProcessedPhotoForTheNextQuestion() = runTest {
        val model = FakeModel(FakeModel.Hang, FakeModel.Chunks("Fresh answer."))
        val gemma = processor(model)
        val photo = bitmap()

        val job = launch { gemma.process(read(photo)).collect {} }
        model.started.await()
        job.cancel()
        job.join()
        assertFalse(model.sessions[0].closed)

        val answer = gemma.process(ask(photo, "What is it?")).toList().last() as ReadingEvent.Complete
        assertEquals("Fresh answer.", answer.responseText)
        assertEquals("no second photo processing", 1, model.opened.size)
        assertEquals(0, model.prompts[1].images.size)
        assertFalse(spoken.contains("Partial"))
    }

    @Test
    fun questionDuringPhotoProcessingWaitsForItInsteadOfStartingOver() = runTest {
        val model = FakeModel(FakeModel.Chunks("It says EXIT.")).apply { openGate = CompletableDeferred() }
        val gemma = processor(model)
        val photo = bitmap()

        // The automatic read starts processing the photo, then Ask replaces it.
        val reading = launch { gemma.process(read(photo)).collect {} }
        model.openStarted.await()
        reading.cancel()
        reading.join()

        val waiting = CompletableDeferred<Unit>()
        val asking = async {
            gemma.process(ask(photo, "What does it say?"))
                .onEach { if (it is ReadingEvent.Status && it.text.contains("Looking at the photo")) waiting.complete(Unit) }
                .toList()
        }
        waiting.await() // the question is now waiting on the photo processing already under way
        model.openGate!!.complete(Unit)
        val events = asking.await()

        assertEquals("the photo was processed once", 1, model.prefaces.size)
        assertEquals("It says EXIT.", (events.last() as ReadingEvent.Complete).responseText)
    }

    @Test
    fun recordedAudioAfterAnInterruptedReadGetsAFreshConversation() = runTest {
        val model = FakeModel(FakeModel.Hang, FakeModel.Chunks("Yes."))
        val gemma = processor(model)
        val photo = bitmap()
        val job = launch { gemma.process(read(photo)).collect {} }
        model.started.await()
        job.cancel()
        job.join()

        val audio = RecordedAudio(ByteArray(3_200), 16_000, 100, hasSpeech = true, voicedMs = 100, peak = 5_000.0)
        gemma.process(ProcessingRequest(photo, audio = audio, action = ProcessingAction.ASK)).toList()

        assertEquals(2, model.opened.size)
        assertTrue(model.sessions[0].closed)
        assertEquals(1, model.photosAtOpen(1))
    }

    @Test
    fun newSubjectStartsAnEmptyConversation() = runTest {
        val model = FakeModel(FakeModel.Chunks("Old."), FakeModel.Chunks("New."))
        val gemma = processor(model)

        gemma.process(read(bitmap())).toList()
        gemma.resetConversation()
        assertTrue(model.sessions[0].closed)
        gemma.process(read(bitmap())).toList()

        assertEquals(2, model.opened.size)
        assertEquals("only the new photo", 1, model.opened[1].size)
        assertEquals(1, model.photosAtOpen(1))
    }

    @Test
    fun longConversationsAreRebuiltShorterButKeepThePhoto() = runTest {
        val long = "word ".repeat(GemmaConfig.MAX_CONTEXT_TOKENS) // far past the context budget
        val model = FakeModel(FakeModel.Chunks(long), FakeModel.Chunks("Short."))
        val gemma = processor(model)
        val photo = bitmap()

        gemma.process(read(photo)).toList()
        gemma.process(ask(photo, "Summarize it")).toList()

        assertEquals(2, model.opened.size)
        assertEquals("the oversized turn is dropped", listOf(GemmaImageSpeechProcessor.PREFACE_REPLY), model.opened[1].map { it.reply })
        assertEquals("the photo is processed again", 1, model.photosAtOpen(1))
    }

    @Test
    fun emptyAnswerIsAFailureNotACompletion() = runTest {
        val model = FakeModel(FakeModel.Chunks("  "))
        val last = processor(model).process(read(bitmap())).toList().last()
        assertTrue(last is ReadingEvent.Failure)
    }

    @Test
    fun spokenQuestionIsPassedAsAudioWithoutTranscription() = runTest {
        val model = FakeModel(FakeModel.Chunks("Yes."))
        val pcm = ByteArray(3_200)
        val audio = RecordedAudio(pcm, 16_000, 100, hasSpeech = true, voicedMs = 100, peak = 5_000.0)
        processor(model).process(ProcessingRequest(bitmap(), audio = audio, action = ProcessingAction.ASK)).toList()

        assertArrayEquals(audio.toWavBytes(), model.prompts[0].audioWav)
        assertTrue(model.prompts[0].text.contains("attached audio"))
    }

    @Test
    fun silentQuestionNeverReachesTheModel() = runTest {
        val model = FakeModel()
        val events = processor(model).process(ProcessingRequest(bitmap(), action = ProcessingAction.ASK)).toList()

        assertTrue(events.single() is ReadingEvent.Failure)
        assertTrue(model.opened.isEmpty())
    }

    @Test
    fun unavailableModelOffersLocalReadingInsteadOfRetry() = runTest {
        val model = FakeModel().apply { unavailable = true }
        val failure = processor(model).process(read(bitmap())).toList().last() as ReadingEvent.Failure

        assertFalse(failure.canRetry)
        assertTrue(failure.canReadLocally)
    }

    @Test
    fun replaySpeaksCachedTextWithoutTheModel() = runTest {
        val model = FakeModel()
        val audio = processor(model).read("Shake well. Keep cold.").toList()

        assertEquals(listOf("Shake well.", "Keep cold."), audio.map { it.text })
        assertTrue(model.opened.isEmpty())
    }

    @Test
    fun replacementRequestWaitsForTheCancelledOneToUnwind() = runTest {
        val model = FakeModel(FakeModel.Hang, FakeModel.Chunks("Second."))
        val gemma = processor(model)
        val photo = bitmap()

        val first = launch { gemma.process(read(photo)).collect {} }
        model.started.await()
        first.cancel()
        val second = gemma.process(read(photo)).toList()
        yield()

        assertEquals("the same conversation carries on", 1, model.sessions.size)
        assertEquals("Second.", (second.last() as ReadingEvent.Complete).responseText)
    }

    private companion object {
        /** Bitmap has no public constructor; the tests only need distinct identities. */
        fun bitmap(): Bitmap {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = field.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Bitmap::class.java) as Bitmap
        }
    }
}
