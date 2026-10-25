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

    @Test
    fun realLabelIsDecisive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A real photo of a cookie box: "CONTAINS SOY, WHEAT. MAY CONTAIN TRACES OF MILK, EGG,
        // COCONUT." Soy is also in the ingredients (soy lecithin).
        val photos = realLabels(context)
        assumeTrue("real label photos not on the phone", photos.isNotEmpty())
        withGemma(context) { gemma ->
            val verdicts = mutableListOf<String>()
            for ((name, photo) in photos) {
                for ((restrictions, expect) in REAL_LABEL_CASES) {
                    gemma.resetConversation()
                    val reply = check(gemma, photo, restrictions)
                    val verdict = expect(reply.lowercase())
                    verdicts += "$name [$restrictions] $verdict"
                    Log.i(TAG, "$name [$restrictions] $verdict -> ${reply.replace('\n', ' ')}")
                }
            }
            assertTrue("real label verdicts: $verdicts", verdicts.none { "WRONG" in it })
        }
    }

    private suspend fun withGemma(context: android.content.Context, work: suspend (GemmaImageSpeechProcessor) -> Unit) {
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
            work(gemma)
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

    /**
     * The same cookie box twice: a full camera photo, and a screenshot of the camera preview.
     * Copied to the app's cache by hand; the test is skipped without them.
     */
    private fun realLabels(context: android.content.Context): List<Pair<String, Bitmap>> = buildList {
        val photo = java.io.File(context.cacheDir, "soy-label-photo.jpg")
        if (photo.isFile) BitmapFactory.decodeFile(photo.path)?.let { add("photo" to it) }
        val screen = java.io.File(context.cacheDir, "soy-label-screen.png")
        if (screen.isFile) BitmapFactory.decodeFile(screen.path)?.let { add("preview" to Bitmap.createBitmap(it, 53, 357, 973, 1183)) }
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

        private val UNSURE = listOf("cannot tell", "can't tell", "not visible", "unable to", "cannot confirm", "not sure", "can't be sure")

        private fun definite(word: String): (String) -> String = { reply ->
            when {
                word !in reply -> "WRONG: does not mention $word"
                "may contain $word" in reply || "might contain $word" in reply -> "WRONG: says may contain $word"
                UNSURE.any { it in reply } -> "WRONG: unsure"
                "looks safe" in reply || "does not go against" in reply -> "WRONG: calls it safe"
                else -> "ok"
            }
        }

        private fun traces(word: String): (String) -> String = { reply ->
            when {
                word !in reply -> "WRONG: does not mention $word"
                "may contain" !in reply && "traces" !in reply -> "WRONG: does not say may contain"
                UNSURE.any { it in reply } -> "WRONG: unsure"
                "looks safe" in reply -> "WRONG: calls it safe"
                else -> "ok"
            }
        }

        private val safe: (String) -> String = { reply ->
            when {
                UNSURE.any { it in reply } -> "WRONG: unsure"
                "not safe" in reply || "be careful" in reply -> "WRONG: calls it unsafe"
                "safe" !in reply -> "WRONG: does not say safe"
                else -> "ok"
            }
        }

        val REAL_LABEL_CASES = listOf(
            "Allergic to soy." to definite("soy"),
            "soy" to definite("soy"),
            "I can't eat soy or peanuts." to definite("soy"),
            "Allergic to walnuts and soy. Vegetarian." to definite("soy"),
            "Allergic to wheat and gluten." to definite("wheat"),
            "Allergic to milk." to traces("milk"),
            "Allergic to walnuts." to safe,
            "I don't eat meat." to safe,
        )
    }
}
