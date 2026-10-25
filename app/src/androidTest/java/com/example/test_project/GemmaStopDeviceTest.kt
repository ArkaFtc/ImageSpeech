package com.example.test_project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ReadingEvent
import com.example.test_project.ondevice.GemmaImageSpeechProcessor
import com.example.test_project.ondevice.GemmaModelStore
import com.example.test_project.ondevice.GemmaProfile
import com.example.test_project.ondevice.LiteRtGemmaModel
import com.example.test_project.processing.voice.SpeechSynthesizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stopping an answer part way, then asking again, gets a new answer. Uses the phone's real voice,
 * as the app does. Run with `am instrument`, never connectedAndroidTest (it uninstalls the app and
 * deletes the model).
 */
@RunWith(AndroidJUnit4::class)
class GemmaStopDeviceTest {
    @Test
    fun askingAfterStopAnswers() = run(realVoice = true)

    @Test
    fun askingAfterStopAnswersWithoutVoice() = run(realVoice = false)

    private fun run(realVoice: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GemmaModelStore(context)
        assumeTrue("Gemma model not downloaded", store.isPresent)
        val profile = GemmaProfile.forDevice(context)
        val model = LiteRtGemmaModel(context, store, profile)
        val voice = SpeechSynthesizer(context)
        val gemma = GemmaImageSpeechProcessor(
            model = model,
            speak = if (realVoice) voice::synthesize else { text -> com.example.test_project.contract.SpokenAudio(text, ByteArray(0)) },
            contextTokens = profile.contextTokens,
            outputTokens = profile.outputTokens,
        )
        val photo = label()
        try {
            gemma.process(ProcessingRequest(photo, action = ProcessingAction.PREPARE)).toList()
            for (round in 1..2) {
                // Stopped after the first spoken passage is ready, like pressing Stop mid-answer.
                val first = launch {
                    gemma.process(ProcessingRequest(photo, action = ProcessingAction.ASK, question = "Read the label in detail")).collect { }
                }
                val started = SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(4_000)
                first.cancel()
                first.join()
                Log.i(TAG, "round $round stopped after ${SystemClock.elapsedRealtime() - started} ms")

                val asked = SystemClock.elapsedRealtime()
                val done = withTimeout(60_000) {
                    gemma.process(ProcessingRequest(photo, action = ProcessingAction.ASK, question = "What size are the screws?"))
                        .first { it is ReadingEvent.Complete || it is ReadingEvent.Failure }
                }
                Log.i(TAG, "voice=$realVoice round $round next answer after ${SystemClock.elapsedRealtime() - asked} ms -> $done")
                assertTrue("next answer completes", done is ReadingEvent.Complete)
            }
        } finally {
            gemma.close()
            gemma.awaitIdle()
            model.close()
            voice.close()
        }
    }

    private fun label(): Bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; isAntiAlias = true; textSize = 64f }
        listOf(
            "2802 Series Zinc-Plated Steel Button Head Screw",
            "M4 x 0.7mm, 20mm Length - 25 Pack",
            "WARNING: CHOKING HAZARD - small parts.",
            "Not for children under 3 years. Made in China.",
        ).forEachIndexed { i, line -> canvas.drawText(line, 60f, 200f + i * 140f, paint) }
    }

    private companion object {
        const val TAG = "GemmaStopTest"
    }
}
