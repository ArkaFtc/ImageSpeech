package com.example.test_project

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.contract.RecordedAudio
import com.example.test_project.ondevice.GemmaImageSpeechProcessor
import com.example.test_project.ondevice.GemmaModelStore
import com.example.test_project.ondevice.GemmaProfile
import com.example.test_project.ondevice.LiteRtGemmaModel
import com.example.test_project.ondevice.ModelPrompt
import com.example.test_project.processing.voice.SpeechSynthesizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device check of spoken questions. Needs the downloaded model. A synthesized voice stands in
 * for the microphone, converted to the microphone's 16 kHz mono format.
 */
@RunWith(AndroidJUnit4::class)
class GemmaAudioDiagnosticTest {
    @Test
    fun spokenQuestionVariants() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = GemmaModelStore(context)
        assumeTrue("Gemma model not downloaded", store.isPresent)
        val voice = SpeechSynthesizer(context)
        val spoken = voice.synthesize("What word is written on this sign?")
        val audio = toMicFormat(spoken.wav)
        val wav = audio.toWavBytes()
        Log.i(TAG, "audio ${audio.durationMs}ms bytes=${wav.size}")
        val image = GemmaImageSpeechProcessor.encodeJpeg(sign("EXIT"))
        val ask = GemmaImageSpeechProcessor.instruction(com.example.test_project.contract.ProcessingAction.ASK, "")
        val read = GemmaImageSpeechProcessor.instruction(com.example.test_project.contract.ProcessingAction.READ, "")
        val model = LiteRtGemmaModel(context, store, GemmaProfile.forDevice(context))
        try {
            suspend fun run(name: String, vararg turns: ModelPrompt) {
                val session = model.open(emptyList())
                try {
                    turns.forEachIndexed { index, prompt ->
                        val reply = session.send(prompt).toList().joinToString("")
                        Log.i(TAG, "$name turn${index + 1} images=${prompt.images.size} audio=${prompt.audioWav != null} -> ${reply.replace('\n', ' ')}")
                    }
                } finally {
                    session.close()
                }
            }
            val photo = GemmaImageSpeechProcessor.encodeJpeg(photo(3060, 4080))
            val preface = ModelPrompt(listOf(photo), null, GemmaImageSpeechProcessor.PREFACE_TEXT)
            val question = "Action: Ask. User question: What large word is at the top of the page?"
            suspend fun timed(name: String, session: com.example.test_project.ondevice.ModelSession, prompt: ModelPrompt): String {
                val t0 = android.os.SystemClock.elapsedRealtime()
                var first = -1L
                val reply = StringBuilder()
                session.send(prompt).collect {
                    if (first < 0) first = android.os.SystemClock.elapsedRealtime() - t0
                    reply.append(it)
                }
                Log.i(TAG, "$name firstTextMs=$first -> ${reply.toString().take(160).replace('\n', ' ')}")
                return reply.toString()
            }
            model.warmUp()

            // H: photo processed on its own, then a question.
            var t0 = android.os.SystemClock.elapsedRealtime()
            val h = model.open(emptyList(), preface)
            Log.i(TAG, "H preface prefill ms=${android.os.SystemClock.elapsedRealtime() - t0}")
            timed("H question after preface", h, ModelPrompt(emptyList(), null, question))
            timed("H follow-up", h, ModelPrompt(emptyList(), null, "Action: Ask. User question: How many lines of small text are there, roughly?"))
            h.close()

            // I: preface, a read cancelled partway, then a question on the same conversation.
            t0 = android.os.SystemClock.elapsedRealtime()
            val i = model.open(emptyList(), preface)
            Log.i(TAG, "I preface prefill ms=${android.os.SystemClock.elapsedRealtime() - t0}")
            val reading = launch {
                var n = 0
                i.send(ModelPrompt(emptyList(), null, read)).collect { if (++n == 5) Log.i(TAG, "I read got 5 fragments; cancelling") }
            }
            delay(CANCEL_AFTER_MS)
            reading.cancel()
            reading.join()
            timed("I question after cancelled read", i, ModelPrompt(emptyList(), null, question))
            timed("I spoken question after that", i, ModelPrompt(emptyList(), wav, ask))
            i.close()
        } finally {
            model.close()
            voice.close()
        }
    }

    /** A busy, camera-sized page: many lines of text over a textured background. */
    private fun photo(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(235, 230, 220))
        val paint = Paint().apply { color = Color.BLACK; textSize = 90f; isAntiAlias = true }
        canvas.drawText("EXIT", 1100f, 400f, Paint(paint).apply { textSize = 300f })
        for (line in 0 until 30) canvas.drawText("Line $line of a long printed page with small words", 120f, 700f + line * 110f, paint)
    }

    private fun sign(word: String): Bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(Color.rgb(0, 120, 60))
        canvas.drawText(word, 160f, 260f, Paint().apply { color = Color.WHITE; textSize = 180f; isAntiAlias = true })
    }

    /** Parses a PCM16 WAV, mixes to mono, and resamples linearly to 16 kHz. */
    private fun toMicFormat(wav: ByteArray): RecordedAudio {
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var channels = 1
        var rate = 16_000
        var data: ShortArray? = null
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "fmt ") {
                channels = buffer.getShort(offset + 10).toInt()
                rate = buffer.getInt(offset + 12)
            } else if (id == "data") {
                val count = minOf(size, wav.size - offset - 8) / 2
                data = ShortArray(count) { buffer.getShort(offset + 8 + it * 2) }
                break
            }
            offset += 8 + size + (size and 1)
        }
        val samples = checkNotNull(data)
        val mono = ShortArray(samples.size / channels) { frame ->
            (0 until channels).sumOf { samples[frame * channels + it].toInt() }.div(channels).toShort()
        }
        val outCount = (mono.size.toLong() * 16_000 / rate).toInt()
        val pcm = ByteBuffer.allocate(outCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outCount) {
            val position = i.toDouble() * rate / 16_000
            val low = position.toInt().coerceAtMost(mono.size - 1)
            val high = (low + 1).coerceAtMost(mono.size - 1)
            val fraction = position - low
            pcm.putShort((mono[low] * (1 - fraction) + mono[high] * fraction).toInt().toShort())
        }
        Log.i(TAG, "tts wav rate=$rate channels=$channels")
        return RecordedAudio(pcm.array(), 16_000, outCount * 1000L / 16_000, hasSpeech = true, voicedMs = 1000, peak = 5000.0)
    }

    private companion object {
        const val TAG = "GemmaAudioDiag"
        const val CANCEL_AFTER_MS = 6_000L
    }
}
