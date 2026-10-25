package com.example.test_project

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The microphone mute switch on the camera screen. Muted, spoken questions are off; typed
 * questions still work. The microphone is only on when it is unmuted and Android allows it.
 */
object MicSetting {
    private const val PREFS = "settings"
    private const val KEY_MUTED = "mic_muted"

    fun isMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, false)

    fun setMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MUTED, muted).apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Whether spoken questions can be used now. */
    fun isOn(context: Context): Boolean = !isMuted(context) && hasPermission(context)
}
