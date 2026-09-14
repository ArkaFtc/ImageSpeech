package com.example.test_project.settings

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

/** Versioned, self-identifying content stored in a shared Gemini-key QR code. */
object GeminiQrPayload {
    private const val APP = "ImageSpeech"
    private const val SERVICE = "Gemini"
    private const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 4_096

    sealed interface DecodeResult {
        data class Success(val key: String) : DecodeResult
        data object Malformed : DecodeResult
        data object Unsupported : DecodeResult
        data class UnsupportedVersion(val version: Int) : DecodeResult
    }

    fun encode(key: String): String {
        val normalized = key.trim()
        require(normalized.isNotEmpty())
        return JsonObject().apply {
            addProperty("app", APP)
            addProperty("service", SERVICE)
            addProperty("version", VERSION)
            addProperty("key", normalized)
        }.toString()
    }

    fun decode(payload: String): DecodeResult {
        if (payload.toByteArray(StandardCharsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return DecodeResult.Malformed
        }
        val value = runCatching { JsonParser.parseString(payload) }.getOrNull()
            ?: return DecodeResult.Malformed
        if (!value.isJsonObject) return DecodeResult.Malformed
        val json = value.asJsonObject
        val app = json.string("app") ?: return DecodeResult.Malformed
        val service = json.string("service") ?: return DecodeResult.Malformed
        if (app != APP || service != SERVICE) return DecodeResult.Unsupported
        val versionElement = json.get("version") ?: return DecodeResult.Malformed
        if (!versionElement.isJsonPrimitive || !versionElement.asJsonPrimitive.isNumber) {
            return DecodeResult.Malformed
        }
        val version = versionElement.asString.toIntOrNull() ?: return DecodeResult.Malformed
        if (version != VERSION) return DecodeResult.UnsupportedVersion(version)
        val key = json.string("key") ?: return DecodeResult.Malformed
        if (key.trim().isEmpty()) return DecodeResult.Malformed
        return DecodeResult.Success(key)
    }

    private fun JsonObject.string(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }
}
