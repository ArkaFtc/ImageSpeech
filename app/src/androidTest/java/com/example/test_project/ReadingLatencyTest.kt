package com.example.test_project

import android.graphics.*
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.benchmark.ReaderBenchmarkActivity
import com.example.test_project.capture.AudioPlayer
import com.example.test_project.contract.*
import com.example.test_project.processing.LocalImageSpeechProcessor
import com.example.test_project.processing.ocr.DocumentReader
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingLatencyTest {
    @Test
    fun photoSizedTimeToFirstAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original =
            instrumentation.context.assets.open("warped.jpg").use { BitmapFactory.decodeStream(it) }
        // Upscaling models a larger capture workload; it adds no new text detail.
        val image = Bitmap.createScaledBitmap(original, 2560, 1648, true)
        original.recycle()
        ActivityScenario.launch(ReaderBenchmarkActivity::class.java).use {
            val processor =
                withContext(Dispatchers.Main) { LocalImageSpeechProcessor(context) { null } }
            try {
                processor.awaitReady()
                val start = SystemClock.elapsedRealtime()
                val record = JSONObject().put("width", image.width).put("height", image.height)
                withTimeout(120_000) {
                    processor
                        .process(ProcessingRequest(image, mode = ReadingMode.BOOK_SPREAD))
                        .filterIsInstance<ReadingEvent.Audio>()
                        .take(1)
                        .collect { event ->
                            record
                                .put("firstAudioReadyMs", SystemClock.elapsedRealtime() - start)
                                .put("firstText", event.audio.text)
                            withContext(Dispatchers.Main) {
                                AudioPlayer(context).play(event.audio) {
                                    record.put(
                                        "playbackStartMs",
                                        SystemClock.elapsedRealtime() - start,
                                    )
                                }
                            }
                        }
                }
                assertTrue(record.has("playbackStartMs"))
                File(context.filesDir, "photo-sized-latency.json").writeText(record.toString(2))
            } finally {
                withContext(Dispatchers.Main) { processor.close() }
                image.recycle()
            }
        }
    }

    @Test
    fun bookFirstAudioAndFullRead() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap =
            instrumentation.context.assets.open("warped.jpg").use { BitmapFactory.decodeStream(it) }
        ActivityScenario.launch(ReaderBenchmarkActivity::class.java).use {
            val processor =
                withContext(Dispatchers.Main) { LocalImageSpeechProcessor(context) { null } }
            try {
                processor.awaitReady()
                val results = JSONArray()
                // Fresh processors are unnecessary: distinct Bitmap identities avoid OCR result
                // cache.
                repeat(3) { trial ->
                    val image = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    val start = SystemClock.elapsedRealtime()
                    var firstBlock: Long? = null
                    var firstAudio: Long? = null
                    var playbackStart: Long? = null
                    var firstText = ""
                    val text = StringBuilder()
                    var count = 0
                    var uncertain = 0
                    try {
                        withTimeout(240_000) {
                            processor
                                .process(ProcessingRequest(image, mode = ReadingMode.BOOK_SPREAD))
                                .collect { event ->
                                    when (event) {
                                        is ReadingEvent.Block -> {
                                            if (firstBlock == null)
                                                firstBlock = SystemClock.elapsedRealtime() - start
                                            count++
                                            if (event.block.uncertain) uncertain++
                                            text.append(event.block.text).append('\n')
                                        }
                                        is ReadingEvent.Audio ->
                                            if (firstAudio == null) {
                                                firstAudio = SystemClock.elapsedRealtime() - start
                                                firstText = event.audio.text
                                                // Actually start the first clip on the foreground
                                                // phone; later clips
                                                // are synthesized but not played to keep the
                                                // benchmark bounded.
                                                withContext(Dispatchers.Main) {
                                                    AudioPlayer(context).play(event.audio) {
                                                        playbackStart =
                                                            SystemClock.elapsedRealtime() - start
                                                    }
                                                }
                                            }
                                        else -> Unit
                                    }
                                }
                        }
                        assertNotNull("No audio generated", firstAudio)
                        assertNotNull("Playback never started", playbackStart)
                        val record =
                            JSONObject()
                                .put("trial", trial)
                                .put("firstBlockMs", firstBlock)
                                .put("firstAudioReadyMs", firstAudio)
                                .put("playbackStartMs", playbackStart)
                                .put("completeMs", SystemClock.elapsedRealtime() - start)
                                .put("firstText", firstText)
                                .put("blocks", count)
                                .put("uncertainBlocks", uncertain)
                                .put("text", text.toString())
                        results.put(record)
                        File(context.filesDir, "reading-latency.json")
                            .writeText(results.toString(2))
                        assertTrue(firstAudio!! < record.getLong("completeMs"))
                    } finally {
                        image.recycle()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { processor.close() }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun rotatedTextIsRecognized() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 38f
            }
        canvas.rotate(-8f, 450f, 250f)
        val expected = listOf("The blue train arrives at 9:30.", "Ticket number: 4827")
        canvas.drawText(expected[0], 65f, 200f, paint)
        canvas.drawText(expected[1], 65f, 270f, paint)
        try {
            withContext(Dispatchers.Default) {
                DocumentReader(context).use { reader ->
                    val blocks = mutableListOf<TextBlock>()
                    reader.read(
                        ProcessingRequest(image, mode = ReadingMode.TEXT),
                        {},
                        { blocks += it },
                    )
                    val actual = blocks.joinToString(" ") { it.text }
                    File(context.filesDir, "rotated-ocr.txt").writeText(actual)
                    assertEquals(expected.joinToString(" "), actual)
                }
            }
        } finally {
            image.recycle()
        }
    }
}
