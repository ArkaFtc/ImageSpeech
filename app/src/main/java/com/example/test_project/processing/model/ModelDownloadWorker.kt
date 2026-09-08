package com.example.test_project.processing.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.test_project.R
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches the model once, on first run. */
open class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val store = ModelStore(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (store.isPresent) return@withContext Result.success()
        if (!store.ensureDirectory()) {
            Log.e(TAG, "Could not create the model directory")
            return@withContext Result.failure()
        }

        // A foreground notification keeps a download this long alive and visible. If the user
        // declined notifications the download still runs, just without the progress UI.
        runCatching { setForeground(notification(0)) }

        try {
            download()
        } catch (e: Exception) {
            Log.w(TAG, "Download interrupted", e)
            // Retry keeps the partial file, so the next attempt resumes rather than restarts.
            Result.retry()
        }
    }

    private suspend fun download(): Result {
        var have = store.downloadedBytes
        val connection = (URL(ModelStore.DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

        try {
            connection.connect()
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit

                HttpURLConnection.HTTP_OK -> {
                    // The server ignored the range request, so whatever is on disk is not a
                    // prefix of what is arriving. Start again rather than splice two halves.
                    if (have > 0) {
                        Log.w(TAG, "Resume refused; restarting the download")
                        store.discardPartial()
                        have = 0
                    }
                }

                else -> {
                    Log.e(TAG, "Unexpected response ${connection.responseCode}")
                    return Result.retry()
                }
            }

            RandomAccessFile(store.partialFile, "rw").use { out ->
                out.seek(have)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var sinceReport = 0L

                    while (true) {
                        if (isStopped) return Result.retry()
                        val read = input.read(buffer)
                        if (read <= 0) break

                        out.write(buffer, 0, read)
                        have += read
                        sinceReport += read

                        if (sinceReport >= REPORT_EVERY_BYTES) {
                            sinceReport = 0
                            publish(have)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        if (store.partialFile.length() != ModelStore.EXPECTED_BYTES) {
            // Short read: the connection ended early. Keep the partial and resume next time.
            Log.w(TAG, "Incomplete: ${store.partialFile.length()} of ${ModelStore.EXPECTED_BYTES}")
            return Result.retry()
        }

        if (!store.partialFile.renameTo(store.modelFile)) {
            Log.e(TAG, "Could not move the finished download into place")
            return Result.failure()
        }

        publish(ModelStore.EXPECTED_BYTES)
        return Result.success()
    }

    private suspend fun publish(bytes: Long) {
        val percent = ((bytes * 100) / ModelStore.EXPECTED_BYTES).toInt().coerceIn(0, 100)
        setProgress(Data.Builder().putInt(KEY_PERCENT, percent).build())
        runCatching { setForeground(notification(percent)) }
    }

    private fun notification(percent: Int): ForegroundInfo {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.download_title))
            .setContentText(context.getString(R.string.download_progress, percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ModelDownload"

        private const val CHANNEL_ID = "model-download"
        private const val NOTIFICATION_ID = 1
        private const val BUFFER_BYTES = 1 shl 16
        private const val REPORT_EVERY_BYTES = 8L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        const val WORK_NAME = "model-download"
        const val KEY_PERCENT = "percent"

        /** Queues the download if it is not already done or running. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun progress(info: WorkInfo?): Int? = when (info?.state) {
            WorkInfo.State.RUNNING -> info.progress.getInt(KEY_PERCENT, 0)
            else -> null
        }
    }
}
