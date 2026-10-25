package com.example.test_project.profile

import android.content.Context

/**
 * The user's profile, kept only on this phone in app-private storage. It is excluded from
 * backups and device transfer (res/xml), and is never sent anywhere: Gemma runs on the phone.
 */
object Profile {
    private const val PREFS = "profile"
    private const val KEY_DIETARY = "dietary_restrictions"
    private const val KEY_SET_UP = "set_up"

    /** Longest restrictions text kept, so the check stays a small part of Gemma's context. */
    const val MAX_DIETARY_CHARS = 500

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dietary(context: Context): String = prefs(context).getString(KEY_DIETARY, "").orEmpty()

    /** Whether the profile page has been shown once, saved or skipped. */
    fun isSetUp(context: Context): Boolean = prefs(context).getBoolean(KEY_SET_UP, false)

    fun save(context: Context, dietary: String) {
        prefs(context).edit()
            .putString(KEY_DIETARY, clean(dietary))
            .putBoolean(KEY_SET_UP, true)
            .apply()
    }

    fun markSetUp(context: Context) {
        prefs(context).edit().putBoolean(KEY_SET_UP, true).apply()
    }

    fun clean(dietary: String): String = dietary.trim().take(MAX_DIETARY_CHARS)
}
