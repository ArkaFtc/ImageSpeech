package com.example.test_project.settings

import android.content.Context

/** App-private credential storage. This preference file is excluded from backup and transfer. */
class GeminiKeyStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun get(): String? = preferences.getString(KEY_API_KEY, null)?.trim()?.ifEmpty { null }

    /** Returns true only when routing actually changed. */
    fun set(value: String): Boolean {
        val normalized = value.trim()
        val previous = get().orEmpty()
        if (normalized == previous) return false
        preferences.edit().apply {
            if (normalized.isEmpty()) remove(KEY_API_KEY) else putString(KEY_API_KEY, normalized)
        }.apply()
        return true
    }

    companion object {
        const val FILE_NAME = "gemini_credentials"
        private const val KEY_API_KEY = "api_key"
    }
}
