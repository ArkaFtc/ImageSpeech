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
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's real question flow on the real model: the photo is processed first (PREPARE), then
 * questions follow. Counts replies that repeat the question instead of answering it.
 * Needs the downloaded model. Run with `am instrument`, never connectedAndroidTest (it uninstalls
 * the app and deletes the model).
 */
@RunWith(AndroidJUnit4::class)
class GemmaQuestionDeviceTest {
    @Test
    fun questionsAreAnsweredNotRepeated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GemmaModelStore(context)
        assumeTrue("Gemma model not downloaded", store.isPresent)
        val photo = realPhoto(context) ?: box()
        val profile = GemmaProfile.forDevice(context)
        val model = LiteRtGemmaModel(context, store, profile)
        val gemma = GemmaImageSpeechProcessor(
            model = model,
            speak = { SpokenAudio(it, ByteArray(0)) },
            contextTokens = profile.contextTokens,
            outputTokens = profile.outputTokens,
        )
        val subjects = InstrumentationRegistry.getArguments().getString("subjects")?.toInt() ?: 3
        var echoes = 0
        var asked = 0

        suspend fun ask(question: String, extra: List<Bitmap> = emptyList(), label: String) {
            val events = gemma.process(ProcessingRequest(photo, extra, action = ProcessingAction.ASK, question = question)).toList()
            val done = events.last()
            val reply = (done as? ReadingEvent.Complete)?.responseText ?: "FAILED: ${(done as? ReadingEvent.Failure)?.message}"
            val echo = isEcho(question, reply)
            asked++
            if (echo) echoes++
            val firstText = (done as? ReadingEvent.Complete)?.metrics?.firstTextMs
            Log.i(TAG, "$label echo=$echo firstTextMs=$firstText -> ${reply.take(140).replace('\n', ' ')}")
        }

        try {
            repeat(subjects) { n ->
                gemma.resetConversation()
                val started = System.currentTimeMillis()
                gemma.process(ProcessingRequest(photo, action = ProcessingAction.PREPARE)).toList()
                Log.i(TAG, "subject#$n prepareMs=${System.currentTimeMillis() - started}")
                ask("What can I eat these with?", label = "subject#$n first")
                ask("Is this a healthy snack?", label = "subject#$n follow-up")
            }
            // Another view added to the live conversation, then a question about both.
            ask("What does the second photo say?", listOf(box()), label = "another-view")
        } finally {
            gemma.close()
            gemma.awaitIdle()
            model.close()
        }
        Log.i(TAG, "echoes=$echoes of $asked")
        assertEquals("replies that repeated the question", 0, echoes)
    }

    /** The cookie photo saved while troubleshooting, when present. */
    private fun realPhoto(context: android.content.Context): Bitmap? {
        val screen = java.io.File(context.cacheDir, "last-photo-screen.png")
        if (!screen.isFile) return null
        val shot = BitmapFactory.decodeFile(screen.path) ?: return null
        // A screenshot of the review screen: the photo sits below the status box.
        return Bitmap.createBitmap(shot, 0, 735, shot.width, 870)
    }

    private fun isEcho(question: String, reply: String): Boolean {
        fun words(s: String) = s.lowercase().replace(Regex("[^a-z ]"), " ").split(' ').filter { it.isNotBlank() }
        val r = words(reply)
        val q = words(question)
        if (r.isEmpty()) return false
        val overlap = r.take(q.size).count { it in q }
        return reply.trim().endsWith("?") && r.size <= q.size + 3 && overlap >= q.size - 2 ||
            reply.lowercase().contains("user question")
    }

    private fun box(): Bitmap = Bitmap.createBitmap(1530, 2040, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(140, 60, 30))
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 130f }
        canvas.drawText("NUTRITION FACTS", 150f, 450f, paint)
        paint.textSize = 70f
        canvas.drawText("Serving size 3 cookies (30 g)", 150f, 700f, paint)
        canvas.drawText("Calories 150", 150f, 850f, paint)
        canvas.drawText("Total sugars 9 g", 150f, 1000f, paint)
    }

    private companion object {
        const val TAG = "GemmaQuestionTest"
    }
}
