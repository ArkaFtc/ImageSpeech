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
import com.google.ai.edge.litertlm.SamplerConfig
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
        val preface = ModelPrompt(listOf(photo), null, GemmaImageSpeechProcessor.PREFACE_TEXT)
        val question = "Action: Ask. User question: What can I eat these with?"
        val profile = GemmaProfile.forDevice(context)

        suspend fun trial(model: LiteRtGemmaModel, layout: String, n: Int) {
            val session = when (layout) {
                "B" -> model.open(listOf(ModelTurn(preface, "OK.")), null)
                "D" -> model.open(emptyList(), null)
                else -> model.open(emptyList(), preface)
            }
            val ask = if (layout == "D") ModelPrompt(listOf(photo), null, question) else ModelPrompt(emptyList(), null, question)
            val reply = session.send(ask).toList().joinToString("").trim()
            session.close()
            val echo = reply.lowercase().startsWith("what can") || reply.lowercase().contains("user question")
            Log.i(TAG, "$layout#$n echo=$echo -> ${reply.take(140).replace('\n', ' ')}")
        }

        val random = LiteRtGemmaModel(context, store, profile)
        try {
            random.warmUp()
            repeat(5) { trial(random, "A", it) }
            repeat(5) { trial(random, "B", it) }
            repeat(3) { trial(random, "D", it) }
        } finally {
            random.close()
        }
        val greedy = LiteRtGemmaModel(context, store, profile, SamplerConfig(1, 1.0, 0.0, 0))
        try {
            repeat(3) { trial(greedy, "C", it) }
        } finally {
            greedy.close()
        }
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
