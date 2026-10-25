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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The dietary check on the real model: food photos get an answer, other photos stay silent.
 * Needs the downloaded model. Run with `am instrument`, never connectedAndroidTest (it uninstalls
 * the app and deletes the model).
 */
@RunWith(AndroidJUnit4::class)
class GemmaDietDeviceTest {
    @Test
    fun checksFoodAndStaysSilentOtherwise() = runBlocking {
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
        try {
            val sign = sign()
            for (restrictions in listOf("Allergic to walnuts.", "I don't eat meat.")) {
                gemma.resetConversation()
                val reply = check(gemma, sign, restrictions)
                Log.i(TAG, "exit sign [$restrictions] -> '$reply'")
                assertEquals("not food stays silent", "", reply)
            }
            val labels = listOf(
                label("BANANA BREAD", "Ingredients: wheat flour, bananas, sugar,", "walnuts, butter (milk), eggs, salt.", "Contains: wheat, milk, egg, tree nuts."),
                label("CHICKEN NOODLE SOUP", "Ingredients: water, chicken, egg noodles", "(wheat flour, eggs), carrots, celery,", "salt, chicken fat. Contains: wheat, egg."),
            )
            for ((name, photo) in labels) {
                for (restrictions in listOf("Allergic to walnuts.", "I don't eat meat.", "Allergic to milk and dairy.")) {
                    gemma.resetConversation()
                    val reply = check(gemma, photo, restrictions)
                    Log.i(TAG, "$name [$restrictions] -> ${reply.replace('\n', ' ')}")
                    assertTrue("food gets an answer", reply.isNotBlank())
                }
            }
            val cookies = realPhoto(context)
            if (cookies != null) {
                for (restrictions in listOf("Allergic to walnuts.", "I don't eat meat.", "Allergic to milk and dairy.", "Allergic to wheat and gluten.")) {
                    gemma.resetConversation()
                    val reply = check(gemma, cookies, restrictions)
                    Log.i(TAG, "cookie box [$restrictions] -> ${reply.replace('\n', ' ')}")
                    assertTrue("food gets an answer", reply.isNotBlank())
                }
            }
        } finally {
            gemma.close()
            gemma.awaitIdle()
            model.close()
        }
    }

    private suspend fun check(gemma: GemmaImageSpeechProcessor, photo: Bitmap, restrictions: String): String {
        val done = gemma.process(ProcessingRequest(photo, action = ProcessingAction.DIET_CHECK, question = restrictions)).toList().last()
        return (done as? ReadingEvent.Complete)?.responseText ?: error("check failed: $done")
    }

    private fun realPhoto(context: android.content.Context): Bitmap? {
        val screen = java.io.File(context.cacheDir, "last-photo-screen.png")
        if (!screen.isFile) return null
        val shot = BitmapFactory.decodeFile(screen.path) ?: return null
        return Bitmap.createBitmap(shot, 0, 735, shot.width, 870)
    }

    private fun label(name: String, vararg lines: String): Pair<String, Bitmap> =
        name to Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(Color.rgb(250, 245, 230))
            val title = Paint().apply { color = Color.rgb(150, 40, 20); isAntiAlias = true; textSize = 110f; isFakeBoldText = true }
            val body = Paint().apply { color = Color.BLACK; isAntiAlias = true; textSize = 62f }
            canvas.drawText(name, 80f, 180f, title)
            lines.forEachIndexed { i, line -> canvas.drawText(line, 80f, 360f + i * 110f, body) }
        }

    private fun sign(): Bitmap = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(0, 120, 60))
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = true; textSize = 360f; isFakeBoldText = true }
        canvas.drawText("EXIT", 380f, 580f, paint)
    }

    private companion object {
        const val TAG = "GemmaDietTest"
    }
}
