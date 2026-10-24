package com.example.test_project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.ondevice.GemmaImageSpeechProcessor
import com.example.test_project.ondevice.GemmaModelStore
import com.example.test_project.ondevice.GemmaProfile
import com.example.test_project.ondevice.LiteRtGemmaModel
import com.example.test_project.ondevice.ModelPrompt
import com.example.test_project.ondevice.ModelTurn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How often does Gemma repeat the question instead of answering, per conversation layout? */
@RunWith(AndroidJUnit4::class)
class GemmaEchoDiagnosticTest {
    @Test
    fun echoRateByLayout() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GemmaModelStore(context)
        assumeTrue("Gemma model not downloaded", store.isPresent)
        // The real photo from the app when troubleshooting saved one, else a synthetic box.
        val saved = java.io.File(context.cacheDir, "last-photo.jpg")
        val screen = java.io.File(context.cacheDir, "last-photo-screen.png")
        val photo = when {
            // A screenshot of the review screen: crop out the photo below the status box.
            screen.isFile -> android.graphics.BitmapFactory.decodeFile(screen.path).let { shot ->
                GemmaImageSpeechProcessor.encodeJpeg(Bitmap.createBitmap(shot, 0, 735, shot.width, 870))
            }
            saved.isFile -> saved.readBytes()
            else -> GemmaImageSpeechProcessor.encodeJpeg(box())
        }
        Log.i(TAG, "photo ${if (screen.isFile) "from screenshot" else if (saved.isFile) "saved from app" else "synthetic"} bytes=${photo.size}")
        val questions = listOf(
            "What can I eat these with?",
            "Is this a healthy snack?",
            "What is this?",
            "What flavor are they?",
            "Explain what the text says in one sentence.",
        )
        val profile = GemmaProfile.forDevice(context)

        /**
         * Layouts: A = photo as a trailing user turn (no reply), B = photo turn answered "OK.",
         * G = photo turn with a natural reply, D = photo and question in one turn.
         * Wording: p = "Action: Ask. User question: ...", q = the bare question.
         */
        suspend fun trial(model: LiteRtGemmaModel, layout: Char, wording: Char, question: String) {
            val text = if (wording == 'p') "Action: Ask. User question: $question" else question
            val preface = ModelPrompt(listOf(photo), null, GemmaImageSpeechProcessor.PREFACE_TEXT)
            val started = System.currentTimeMillis()
            val session = when (layout) {
                'B' -> model.open(listOf(ModelTurn(preface, "OK.")), null)
                'G' -> model.open(listOf(ModelTurn(ModelPrompt(listOf(photo), null, "Here is a photo."), "I can see the photo. What would you like to know?")), null)
                'D' -> model.open(emptyList(), null)
                else -> model.open(emptyList(), preface)
            }
            val opened = System.currentTimeMillis()
            val ask = ModelPrompt(if (layout == 'D') listOf(photo) else emptyList(), null, text)
            val reply = try { session.send(ask).toList().joinToString("").trim() } finally { session.close() }
            val echo = isEcho(question, reply)
            Log.i(TAG, "$layout$wording q${questions.indexOf(question)} echo=$echo openMs=${opened - started} -> ${reply.take(220).replace('\n', ' ')}")
        }

        // Each step is a layout and wording, run once per question (replies are deterministic): -e plan Aq,Bp
        val plan = InstrumentationRegistry.getArguments().getString("plan") ?: "Aq,Bp,Gq,Gp"
        val model = LiteRtGemmaModel(context, store, profile)
        try {
            for (step in plan.split(',')) {
                for (question in questions) trial(model, step[0], step[1], question)
            }
        } finally {
            model.close()
        }
    }

    /** The reply restates the question instead of answering it. */
    private fun isEcho(question: String, reply: String): Boolean {
        fun words(s: String) = s.lowercase().replace(Regex("[^a-z ]"), " ").split(' ').filter { it.isNotBlank() }
        val r = words(reply)
        val q = words(question)
        if (r.isEmpty()) return false
        val overlap = r.take(q.size).count { it in q }
        return reply.trim().endsWith("?") && r.size <= q.size + 3 && overlap >= q.size - 2 ||
            reply.lowercase().contains("user question")
    }

    /** A cookie box front: brand, product name and a serving line. */
    private fun box(): Bitmap = Bitmap.createBitmap(3060, 4080, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(140, 60, 30))
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 260f }
        canvas.drawText("MAPLE LEAF", 400f, 900f, paint)
        canvas.drawText("CREAM COOKIES", 300f, 1250f, paint)
        paint.textSize = 120f
        canvas.drawText("Made with real maple syrup", 400f, 1700f, paint)
        canvas.drawText("Perfect with tea or coffee", 400f, 1900f, paint)
        canvas.drawText("NET WT 10 OZ (283 g)", 400f, 3600f, paint)
    }

    private companion object {
        const val TAG = "GemmaEchoDiag"
    }
}
