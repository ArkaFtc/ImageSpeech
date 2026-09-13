package com.example.test_project.remote

import com.google.gson.Gson
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val baseUrl: String = GeminiConfig.BASE_URL,
    private val gson: Gson = Gson(),
) {
    @OptIn(InternalCoroutinesApi::class)
    suspend fun stream(
        apiKey: String,
        requestBody: GeminiRequest,
        onText: suspend (String) -> Unit,
    ): GeminiResult = withContext(Dispatchers.IO) {
        val body = gson.toJson(requestBody).toRequestBody(JSON)
        val request = Request.Builder()
            .url("${baseUrl}models/${GeminiConfig.MODEL}:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        var responseText = ""
        try {
            val call = http.newCall(request)
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is kotlinx.coroutines.CancellationException) call.cancel()
            }
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val apiError = runCatching {
                        gson.fromJson(response.body?.string(), GeminiResponse::class.java).error
                    }.getOrNull()
                    return@withContext GeminiResult.Failed(httpFailure(response.code, apiError), "")
                }
                val source = response.body?.source()
                    ?: return@withContext GeminiResult.Failed(
                        GeminiFailure("Gemini returned an empty network response.", true), "")
                var finishReason: String? = null
                var failure: GeminiFailure? = null
                val pendingDeltas = ArrayDeque<String>()
                val parser = SseParser { data ->
                    if (data == "[DONE]") return@SseParser
                    val chunk = runCatching { gson.fromJson(data, GeminiResponse::class.java) }
                        .getOrElse {
                            failure = GeminiFailure("Gemini sent a response the app could not read.", true)
                            return@SseParser
                        }
                    chunk.error?.let { failure = httpFailure(it.code ?: 500, it) }
                    chunk.promptFeedback?.blockReason?.let {
                        failure = GeminiFailure("Gemini blocked this request ($it). Try a different photo or wording.", false)
                    }
                    chunk.candidates.orEmpty().forEach { candidate ->
                        candidate.finishReason?.let { finishReason = it }
                        candidate.content?.parts.orEmpty()
                            .filter { it.thought != true }
                            .mapNotNull { it.text }
                            .filter { it.isNotEmpty() }
                            .forEach { delta ->
                                responseText += delta
                                pendingDeltas.addLast(delta)
                            }
                    }
                }
                while (true) {
                    ensureActive()
                    val line = source.readUtf8Line() ?: break
                    // Okio holds incomplete UTF-8 and network chunks until a whole line exists.
                    parser.feed(line + "\n")
                    while (pendingDeltas.isNotEmpty()) onText(pendingDeltas.removeFirst())
                }
                parser.finish()
                while (pendingDeltas.isNotEmpty()) onText(pendingDeltas.removeFirst())
                failure?.let { return@withContext GeminiResult.Failed(it, responseText) }
                when (finishReason) {
                    "STOP" -> if (responseText.isBlank()) {
                        GeminiResult.Failed(GeminiFailure("Gemini returned no answer.", true), "")
                    } else GeminiResult.Complete(responseText, finishReason)
                    null -> GeminiResult.Failed(
                        GeminiFailure("The Gemini stream ended before the answer was complete.", true), responseText)
                    "MAX_TOKENS" -> GeminiResult.Failed(
                        GeminiFailure("Gemini stopped because the answer reached its output limit.", true),
                        responseText,
                    )
                    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
                        GeminiResult.Failed(
                            GeminiFailure("Gemini blocked the response ($finishReason). Try different wording or a different photo.", false),
                            responseText,
                        )
                    else -> GeminiResult.Failed(
                        GeminiFailure("Gemini stopped before finishing ($finishReason).", true), responseText)
                }
            }.also { cancellation?.dispose() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            GeminiResult.Failed(GeminiFailure("The Gemini request timed out. Check the connection and retry.", true), responseText)
        } catch (io: IOException) {
            GeminiResult.Failed(GeminiFailure("The Gemini connection was interrupted: ${io.message ?: "network error"}.", true), responseText)
        }
    }

    private fun httpFailure(code: Int, error: ApiError?): GeminiFailure {
        val detail = error?.message?.takeIf { it.isNotBlank() }
        return when (code) {
            400 -> GeminiFailure(detail ?: "Gemini rejected the request.", false)
            401, 403 -> GeminiFailure("The Gemini API key is invalid or does not have access. Check it in Settings.", false)
            429 -> GeminiFailure("The Gemini quota is exhausted. Wait for the provider quota to reset, then retry.", false, quota = true)
            in 500..599 -> GeminiFailure("Gemini is temporarily unavailable${detail?.let { ": $it" } ?: "."}", true)
            else -> GeminiFailure(detail ?: "Gemini request failed (HTTP $code).", true)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
