package com.example.test_project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.example.test_project.ondevice.PhotoViews
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measurement, not a pass/fail test: how well Gemma reads small print when a photo is sent whole
 * versus as overlapping tiles, and how faithful "More detail" answers are under different prompts
 * and sampling. Results go to logcat under [TAG].
 *
 * Photos: JPEGs in the app's external files dir under `photos/`, plus a generated label with
 * known small print. Needs the downloaded model. Run with `am instrument` (see README), never
 * connectedAndroidTest, which uninstalls the app and deletes the model.
 *
 * Arguments: `part` = views | detail | all (default all).
 */
@RunWith(AndroidJUnit4::class)
class GemmaSmallPrintDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    private class Case(val name: String, val photo: Bitmap, val questions: List<Pair<String, List<String>>>)

    @Test
    fun measure() = runBlocking {
        val store = GemmaModelStore(context)
        assumeTrue("Gemma model not downloaded", store.isPresent)
        val part = args.getString("part") ?: "all"
        if (part == "views" || part == "all") views(store)
        if (part == "detail" || part == "all") detail(store)
    }

    private suspend fun views(store: GemmaModelStore) {
        val cases = buildList {
            File(context.getExternalFilesDir(null), "photos").listFiles { f -> f.extension == "jpg" }?.sorted()?.forEach { file ->
                add(Case(file.nameWithoutExtension, BitmapFactory.decodeFile(file.path), BOOK_QUESTIONS))
            }
            add(Case("label", label(), LABEL_QUESTIONS))
        }
        val layouts: Map<String, (Bitmap) -> List<Bitmap>> = linkedMapOf(
            "whole" to { b -> listOf(b) },
            "whole+2strips" to { b -> listOf(b) + PhotoViews.strips(b, 2) },
            "2strips" to { b -> PhotoViews.strips(b, 2) },
            "3strips" to { b -> PhotoViews.strips(b, 3) },
            "4quarters" to { b -> PhotoViews.quarters(b) },
        )
        val only = args.getString("layouts")?.split(',')?.toSet()
        // Room for four tiles; this phone's default context is 2,048 tokens.
        val profile = GemmaProfile.forDevice(context).let { it.copy(contextTokens = maxOf(it.contextTokens, 3_072)) }
        val model = LiteRtGemmaModel(context, store, profile, maxImages = 4)
        try {
            for (case in cases) for ((layout, split) in layouts) {
                if (only != null && layout !in only) continue
                val views = split(case.photo)
                val jpegs = views.map(GemmaImageSpeechProcessor::encodeJpeg)
                val started = System.currentTimeMillis()
                val session = model.open(listOf(ModelTurn(ModelPrompt(jpegs, null, preface(views.size, layout)), PREFACE_REPLY)))
                val prepareMs = System.currentTimeMillis() - started
                var right = 0
                try {
                    for ((question, expected) in case.questions) {
                        val t0 = System.currentTimeMillis()
                        val answer = session.send(ModelPrompt(emptyList(), null, question)).toList().joinToString("").trim()
                        val ok = expected.any { answer.lowercase().replace(" ", "").contains(it.lowercase().replace(" ", "")) }
                        if (ok) right++
                        Log.i(TAG, "${case.name} $layout ok=$ok ${System.currentTimeMillis() - t0}ms Q=$question A=${answer.take(160).replace('\n', ' ')}")
                    }
                } finally {
                    Log.i(TAG, "RESULT ${case.name} $layout images=${views.size} tokens=${session.tokenCount()} prepareMs=$prepareMs correct=$right/${case.questions.size} jpegBytes=${jpegs.sumOf { it.size }}")
                    session.close()
                }
            }
        } finally {
            model.close()
        }
    }

    private suspend fun detail(store: GemmaModelStore) {
        val photo = File(context.getExternalFilesDir(null), "photos").listFiles { f -> f.extension == "jpg" }?.minOrNull()
            ?.let { BitmapFactory.decodeFile(it.path) } ?: label()
        val samplers = linkedMapOf<String, SamplerConfig?>(
            "default" to null,
            "t0.3" to SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = 1),
        )
        val prompts = linkedMapOf(
            "old" to GemmaImageSpeechProcessor.instruction(com.example.test_project.contract.ProcessingAction.MORE_DETAIL, ""),
            "new" to (args.getString("detailPrompt") ?: NEW_DETAIL),
        )
        val repeats = args.getString("repeats")?.toInt() ?: 2
        for ((samplerName, sampler) in samplers) {
            val model = LiteRtGemmaModel(context, store, GemmaProfile.forDevice(context), sampler)
            try {
                for ((promptName, prompt) in prompts) repeat(repeats) { n ->
                    val session = model.open(
                        listOf(ModelTurn(ModelPrompt(listOf(GemmaImageSpeechProcessor.encodeJpeg(photo)), null, "Here is a photo."), PREFACE_REPLY)),
                    )
                    try {
                        val summary = session.send(ModelPrompt(emptyList(), null, SUMMARIZE)).toList().joinToString("").trim()
                        val t0 = System.currentTimeMillis()
                        val more = session.send(ModelPrompt(emptyList(), null, prompt)).toList().joinToString("").trim()
                        Log.i(TAG, "DETAIL sampler=$samplerName prompt=$promptName run=$n ${System.currentTimeMillis() - t0}ms SUMMARY=${summary.replace('\n', ' ')}")
                        Log.i(TAG, "DETAIL sampler=$samplerName prompt=$promptName run=$n MORE=${more.replace('\n', ' ')}")
                    } finally {
                        session.close()
                    }
                }
            } finally {
                model.close()
            }
        }
    }

    private fun preface(count: Int, layout: String): String = when {
        count == 1 -> "Here is a photo."
        layout.startsWith("whole+") -> "Here is a photo. The first image is the whole photo. The others are enlarged, overlapping parts of the same photo, in reading order, for reading small print."
        else -> "Here is a photo, split into $count enlarged, overlapping parts in reading order."
    }

    /** A package label with large, medium and small print whose wording is known. */
    private fun label(): Bitmap = Bitmap.createBitmap(3060, 4080, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(245, 240, 228))
        val paint = Paint().apply { color = Color.rgb(30, 30, 30); isAntiAlias = true }
        fun line(text: String, size: Float, y: Float) { paint.textSize = size; canvas.drawText(text, 220f, y, paint) }
        line("SUNRISE OATS", 260f, 600f)
        line("Whole grain rolled oats", 120f, 820f)
        line("Net weight 18 oz (510 g)", 100f, 1000f)
        line("Cook 1/2 cup oats in 1 cup water for 5 minutes.", 80f, 1300f)
        line("Contains: oats. Processed in a facility that also handles wheat and soy.", 44f, 2900f)
        line("Lot 4827B   Best by 03/2027", 40f, 3300f)
        line("Packed for Northwind Foods, 112 Alder Street, Fairview, OR 97024", 36f, 3700f)
    }

    private companion object {
        const val TAG = "SmallPrint"
        const val PREFACE_REPLY = "I can see the photo. What would you like to know?"
        const val SUMMARIZE = "Summarize this in one to three sentences: one for a little text, up to three for a lot. Say what it is and the most important information."
        const val NEW_DETAIL =
            "Give more detail about that, using only what is shown in the photo. Include the important " +
                "details it shows, such as numbers, dates, warnings and conditions, in a few short paragraphs " +
                "of plain sentences. Do not add causes, consequences, opinions or facts that are not shown. " +
                "If you add general knowledge, say that it is not from the photo. It will be read aloud, so " +
                "do not use bullet points, headings or symbols like asterisks."

        val BOOK_QUESTIONS = listOf(
            "What is the page number?" to listOf("250"),
            "What website is printed at the bottom of the page?" to listOf("princetonreview"),
            "How many hours a day did factory workers work?" to listOf("12 to 14", "12-14", "twelve to fourteen", "12 and 14"),
            "How many injuries to workers happened per year?" to listOf("500,000", "500000", "500 000"),
        )
        val LABEL_QUESTIONS = listOf(
            "What is the lot number?" to listOf("4827B"),
            "What is the best by date?" to listOf("03/2027", "march 2027", "3/2027"),
            "What street address is printed on the label?" to listOf("112 Alder"),
            "What else is processed in the same facility?" to listOf("wheat"),
        )
    }
}
