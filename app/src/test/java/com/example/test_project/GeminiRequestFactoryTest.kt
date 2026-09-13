package com.example.test_project

import com.example.test_project.contract.RecordedAudio
import com.example.test_project.remote.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class GeminiRequestFactoryTest {
    @Test fun followUpContainsBoundedHistoryAndAllCurrentViews() {
        val history = (1..10).map { ConversationTurn("question $it", "answer $it") }
        val images = (1..5).map { EncodedImage(byteArrayOf(it.toByte())) }
        val request = GeminiRequestFactory.build("latest question", images, null, history)

        assertEquals(GeminiConfig.MAX_COMPLETED_TURNS * 2 + 1, request.contents.size)
        assertEquals("question 3", request.contents.first().parts.single().text)
        val latest = request.contents.last()
        assertEquals(GeminiConfig.MAX_IMAGES, latest.parts.count { it.inlineData != null })
        assertEquals("latest question", latest.parts.last().text)
        assertEquals("low", request.generationConfig.thinkingConfig.thinkingLevel)
        assertTrue(request.systemInstruction.parts.single().text!!.contains("Do not claim to have searched"))
    }

    @Test fun spokenQuestionIsSentAsAudioWithoutTranscription() {
        val pcm = byteArrayOf(0, 0, 1, 0, -1, -1)
        val audio = RecordedAudio(pcm, 16_000, 1, true, 1, 500.0)

        val request = GeminiRequestFactory.build(
            "Action: Ask.",
            listOf(EncodedImage(byteArrayOf(7))),
            audio,
            emptyList(),
        )

        val parts = request.contents.single().parts
        val audioPart = parts.single { it.inlineData?.mimeType == "audio/wav" }
        val decoded = Base64.getDecoder().decode(audioPart.inlineData!!.data)
        assertArrayEquals(audio.toWavBytes(), decoded)
        assertTrue(parts.any { it.text == "Answer the spoken request in the attached audio." })
    }
}
