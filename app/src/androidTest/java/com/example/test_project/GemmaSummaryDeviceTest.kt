package com.example.test_project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.contract.ProcessingAction
import com.example.test_project.contract.ProcessingRequest
import com.example.test_project.contract.ReadingEvent
import com.example.test_project.contract.SpokenAudio
import com.example.test_project.ondevice.GemmaImageSpeechProcessor
import com.example.test_project.ondevice.GemmaModelStore
import com.example.test_project.ondevice.GemmaProfile
import com.example.test_project.ondevice.LiteRtGemmaModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The short summary stays within three sentences and More detail gives more, on the real model.
 * Needs the downloaded model. Run with `am instrument`, never connectedAndroidTest (it uninstalls
 * the app and deletes the model).
 */
@RunWith(AndroidJUnit4::class)
class GemmaSummaryDeviceTest {
    @Test
    fun shortSummaryThenMoreDetail() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GemmaModelStore(context)
        assumeTrue("Gemma model not downloaded", store.isPresent)
        val profile = GemmaProfile.forDevice(context)
        val model = LiteRtGemmaModel(context, store, profile)
        val gemma = GemmaImageSpeechProcessor(
            model = model,
            speak = { SpokenAudio(it, ByteArray(0)) },
            contextTokens = profile.contextTokens,
            outputTokens = profile.outputTokens,
        )
        val photos = listOfNotNull(realPhoto(context)?.let { "cookie box" to it }, "exit sign" to sign())
        try {
            for ((name, photo) in photos) {
                gemma.resetConversation()
                gemma.process(ProcessingRequest(photo, action = ProcessingAction.PREPARE)).toList()
                val short = answer(gemma, photo, ProcessingAction.SUMMARIZE)
                val detailed = answer(gemma, photo, ProcessingAction.MORE_DETAIL)
                Log.i(TAG, "$name short sentences=${sentences(short)} chars=${short.length} -> ${short.replace('\n', ' ')}")
                Log.i(TAG, "$name detailed sentences=${sentences(detailed)} chars=${detailed.length} -> ${detailed.take(400).replace('\n', ' ')}")
                assertTrue("$name: short summary has at most three sentences", sentences(short) in 1..3)
                assertTrue("$name: more detail is longer", detailed.length > short.length)
                assertTrue("$name: no Markdown symbols", "*#`".none { it in detailed })

                val asked = answer(gemma, photo, ProcessingAction.ASK, "What is this?")
                val askedMore = answer(gemma, photo, ProcessingAction.MORE_DETAIL)
                Log.i(TAG, "$name asked sentences=${sentences(asked)} -> ${asked.replace('\n', ' ')}")
                Log.i(TAG, "$name asked more chars=${askedMore.length} -> ${askedMore.take(400).replace('\n', ' ')}")
                assertTrue("$name: more detail after a question is longer", askedMore.length > asked.length)
            }
        } finally {
            gemma.close()
            gemma.awaitIdle()
            model.close()
        }
    }

    private suspend fun answer(gemma: GemmaImageSpeechProcessor, photo: Bitmap, action: ProcessingAction, question: String? = null): String {
        val done = gemma.process(ProcessingRequest(photo, action = action, question = question)).toList().last()
        return (done as? ReadingEvent.Complete)?.responseText ?: error("$action failed: $done")
    }

    /** Sentence count, ignoring decimal points such as "283.5". */
    private fun sentences(text: String): Int =
        Regex("[.!?]+(?=\\s|$)").findAll(text.trim()).count().coerceAtLeast(if (text.isBlank()) 0 else 1)

    private fun realPhoto(context: android.content.Context): Bitmap? {
        val screen = java.io.File(context.cacheDir, "last-photo-screen.png")
        if (!screen.isFile) return null
        val shot = BitmapFactory.decodeFile(screen.path) ?: return null
        return Bitmap.createBitmap(shot, 0, 735, shot.width, 870)
    }

    private fun sign(): Bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(0, 120, 60))
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 360f; isFakeBoldText = true }
        canvas.drawText("EXIT", 380f, 580f, paint)
    }

    private companion object {
        const val TAG = "GemmaSummaryTest"
    }
}
