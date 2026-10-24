package com.example.test_project.ondevice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences

/**
 * Stops a phone that cannot hold Gemma from crashing on every photo. Android ends a process that
 * runs out of memory without any exception the app could catch, so Gemma work is marked as busy on
 * disk; if the next launch finds that mark and Android reports a low-memory kill, Gemma is turned
 * off until the user asks to try again.
 */
class GemmaHealth(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    @Volatile
    private var busyCount = 0

    val isDisabled: Boolean get() = preferences.getBoolean(KEY_DISABLED, false)

    /** Called once per launch, before anything loads Gemma. */
    fun checkLastExit(lastExitWasLowMemory: Boolean) {
        val wasBusy = preferences.getBoolean(KEY_BUSY, false)
        preferences.edit().apply {
            if (wasBusy && lastExitWasLowMemory) putBoolean(KEY_DISABLED, true)
            putBoolean(KEY_BUSY, false)
        }.commit()
    }

    fun checkLastExit(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val last = runCatching { manager.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() }.getOrNull()
        checkLastExit(last?.reason == ApplicationExitInfo.REASON_LOW_MEMORY)
    }

    fun enable() {
        preferences.edit().putBoolean(KEY_DISABLED, false).apply()
    }

    /**
     * Marks Gemma as working for the length of [block]. The write is synchronous because the
     * process may be killed moments later.
     */
    suspend fun <T> whileBusy(block: suspend () -> T): T {
        synchronized(this) {
            if (busyCount++ == 0) preferences.edit().putBoolean(KEY_BUSY, true).commit()
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                if (--busyCount == 0) preferences.edit().putBoolean(KEY_BUSY, false).apply()
            }
        }
    }

    companion object {
        const val FILE_NAME = "gemma_health"
        private const val KEY_BUSY = "busy"
        private const val KEY_DISABLED = "disabled_after_low_memory"
    }
}
