package com.example.test_project

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.edit
import kotlin.math.roundToInt

/**
 * The app's own text size, on top of the phone's font size setting. Applied to views in place
 * rather than by recreating the activity, so changing it never interrupts speech or the camera.
 */
object TextScale {
    val STEPS = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)
    private const val PREFS = "display"
    private const val KEY = "text_scale"

    /** The next step smaller ([direction] -1) or larger (+1), staying within [STEPS]. */
    fun next(current: Float, direction: Int): Float {
        val index = STEPS.indexOfFirst { it >= current - 0.001f }.let { if (it < 0) STEPS.lastIndex else it }
        return STEPS[(index + direction).coerceIn(0, STEPS.lastIndex)]
    }

    fun percent(scale: Float): Int = (scale * 100).roundToInt()

    fun load(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, 1f).coerceIn(STEPS.first(), STEPS.last())

    fun save(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putFloat(KEY, scale) }
    }

    /** Sizes every text view under [root]. Each view keeps its original size, so steps never compound. */
    fun apply(root: View, scale: Float) {
        if (root is TextView) {
            val base = root.getTag(R.id.tag_base_text_size) as? Float
                ?: root.textSize.also { root.setTag(R.id.tag_base_text_size, it) }
            root.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) apply(root.getChildAt(i), scale)
        }
    }
}
