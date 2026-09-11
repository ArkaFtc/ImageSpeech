package com.example.test_project.benchmark

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/** Foreground host for instrumented audio tests; absent from release builds. */
class ReaderBenchmarkActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(
            TextView(this).apply {
                text = "Testing document reading and time to first audio…"
                textSize = 22f
                setPadding(32, 64, 32, 32)
            }
        )
    }
}
