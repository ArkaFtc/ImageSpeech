package com.example.test_project.remote

import com.google.gson.annotations.SerializedName

data class GeminiRequest(
    val systemInstruction: GeminiContent,
    val contents: List<GeminiContent>,
    val generationConfig: GenerationConfig,
)

data class GeminiContent(val role: String? = null, val parts: List<GeminiPart>)

data class GeminiPart(
    val text: String? = null,
    val inlineData: InlineData? = null,
    val thought: Boolean? = null,
)

data class InlineData(val mimeType: String, val data: String)

data class GenerationConfig(
    val thinkingConfig: ThinkingConfig = ThinkingConfig(),
    val maxOutputTokens: Int = GeminiConfig.MAX_OUTPUT_TOKENS,
)

data class ThinkingConfig(val thinkingLevel: String = GeminiConfig.THINKING_LEVEL)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val promptFeedback: PromptFeedback? = null,
    val error: ApiError? = null,
)

data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
    val finishMessage: String? = null,
)

data class PromptFeedback(val blockReason: String? = null)
data class ApiError(val code: Int? = null, val message: String? = null, val status: String? = null)

data class ConversationTurn(val user: String, val model: String)

sealed class GeminiResult {
    data class Complete(val text: String, val finishReason: String?) : GeminiResult()
    data class Failed(val error: GeminiFailure, val partialText: String) : GeminiResult()
}

data class GeminiFailure(
    val message: String,
    val retryable: Boolean,
    val quota: Boolean = false,
)
