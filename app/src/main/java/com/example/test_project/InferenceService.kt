package com.example.test_project

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Owns the model for as long as the app is in use.
 *
 * The engine is roughly 2 GB resident and takes seconds to bring up, so it cannot live and die with
 * the Activity - backgrounding the app would evict it and make the next press pay the whole load
 * again. Holding it in a foreground service is what buys the "keep the engine warm" property the
 * whole latency design assumes.
 *
 * The service is also the [SceneAnswerer] the Activity talks to, so nothing above it has to know
 * whether the model is in-process, absent, or unavailable on this device.
 */
class InferenceService : Service(), SceneAnswerer {

    inner class LocalBinder : Binder() {
        val answerer: SceneAnswerer get() = this@InferenceService
    }

    private val binder = LocalBinder()
    private lateinit var delegate: SceneAnswerer
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        val store = ModelStore(this)
        delegate = if (store.isPresent) {
            GemmaSceneAnswerer(this, store)
        } else {
            // The two read lanes still work, which is the whole point of keeping them independent.
            UnavailableSceneAnswerer(getString(R.string.model_unavailable))
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override val isAvailable: Boolean get() = delegate.isAvailable

    override suspend fun beginTurn(
        image: Bitmap,
        ocr: LocalPPOCRv6Runner.OcrResult,
    ): SceneTurn? {
        // Promote only once the model is actually about to be loaded. A session that never asks a
        // question - which is the common one - never shows a notification.
        promote()
        return delegate.beginTurn(image, ocr)
    }

    override fun close() {
        delegate.close()
        demote()
    }

    override fun onDestroy() {
        delegate.close()
        super.onDestroy()
    }

    private fun promote() {
        if (foreground) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.engine_resident_title))
            .setContentText(getString(R.string.engine_resident_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (e: Exception) {
            // Losing the promotion costs a reload after eviction, not correctness.
            Log.w(TAG, "Could not enter the foreground; the engine may be evicted", e)
        }
    }

    private fun demote() {
        if (!foreground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.engine_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "InferenceService"
        const val CHANNEL_ID = "inference-engine"
        const val NOTIFICATION_ID = 2
    }
}
