package com.example.test_project.capture

import android.content.res.ColorStateList
import android.widget.Button
import androidx.core.content.ContextCompat
import com.example.test_project.R

/**
 * Shows that a hold-to-talk button is listening: a light blue background and a small microphone
 * beside its label. Releasing restores exactly what the button looked like before.
 */
class HoldFeedback(private val button: Button) {
    private val originalTint: ColorStateList? = button.backgroundTintList
    private val originalText = button.textColors
    private val originalDescription = button.contentDescription
    private val originalDrawables = button.compoundDrawablesRelative.copyOf()
    var showing = false
        private set

    fun show() {
        if (showing) return
        showing = true
        val context = button.context
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.holding_background))
        button.setTextColor(ContextCompat.getColor(context, R.color.holding_text))
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_mic, 0, 0, 0)
        button.compoundDrawablePadding = (6 * context.resources.displayMetrics.density).toInt()
        button.contentDescription = context.getString(R.string.holding_to_talk)
        button.announceForAccessibility(context.getString(R.string.holding_to_talk))
    }

    fun hide() {
        if (!showing) return
        showing = false
        button.backgroundTintList = originalTint
        button.setTextColor(originalText)
        button.setCompoundDrawablesRelative(originalDrawables[0], originalDrawables[1], originalDrawables[2], originalDrawables[3])
        button.contentDescription = originalDescription
    }
}
