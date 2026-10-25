package com.example.test_project

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.test_project.processing.voice.SpeechSynthesizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Speech keeps working after the phone's speech service is killed, as Android may do. */
@RunWith(AndroidJUnit4::class)
class SpeechSynthesizerDeviceTest {
    @Test
    fun speechRecoversWhenTheSpeechServiceIsKilled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val voice = SpeechSynthesizer(context)
        val sentences = listOf("The label says shake well.", "Store below twenty five degrees.", "M4 by 0.7 millimetres.")
        try {
            voice.prepare()
            sentences.forEach { assertTrue("spoken before: $it", voice.synthesize(it).wav.size > 44) }
            assertEquals("nothing to say", 0, voice.synthesize(" — ").wav.size)

            val engine = TextToSpeech(context) { }.let { tts -> tts.defaultEngine.also { tts.shutdown() } }
            Log.i(TAG, "killing $engine")
            instrumentation.uiAutomation.executeShellCommand("am force-stop $engine").close()
            delay(1_000)

            sentences.forEach { text ->
                val started = System.currentTimeMillis()
                val wav = voice.synthesize(text).wav
                Log.i(TAG, "after kill: ${wav.size} bytes in ${System.currentTimeMillis() - started} ms for '$text'")
                assertTrue("spoken after: $text", wav.size > 44)
            }
        } finally {
            voice.close()
        }
    }

    private companion object {
        const val TAG = "SpeechSynthTest"
    }
}
