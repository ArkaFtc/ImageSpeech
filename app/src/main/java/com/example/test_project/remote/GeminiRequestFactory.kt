package com.example.test_project.remote

import com.example.test_project.contract.RecordedAudio
import java.util.Base64

object GeminiRequestFactory {
    fun build(
        userInstruction: String,
        images: List<EncodedImage>,
        audio: RecordedAudio?,
        history: List<ConversationTurn>,
    ): GeminiRequest {
        val contents = buildList {
            history.takeLast(GeminiConfig.MAX_COMPLETED_TURNS).forEach { turn ->
                add(GeminiContent("user", listOf(GeminiPart(text = turn.user))))
                add(GeminiContent("model", listOf(GeminiPart(text = turn.model))))
            }
            add(GeminiContent("user", buildList {
                images.takeLast(GeminiConfig.MAX_IMAGES).forEach { image ->
                    add(GeminiPart(inlineData = InlineData(image.mimeType, Base64.getEncoder().encodeToString(image.bytes))))
                }
                audio?.takeIf { it.hasSpeech }?.let {
                    add(GeminiPart(inlineData = InlineData("audio/wav", Base64.getEncoder().encodeToString(it.toWavBytes()))))
                    add(GeminiPart(text = "Answer the spoken request in the attached audio."))
                }
                add(GeminiPart(text = userInstruction))
            }))
        }
        return GeminiRequest(
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = GeminiConfig.SYSTEM_PROMPT))),
            contents = contents,
            generationConfig = GenerationConfig(),
        )
    }
}
