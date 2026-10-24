package com.example.test_project.ondevice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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

/**
 * Fetches the Gemma file once. A 2.6 GB download will be interrupted, so it runs under WorkManager,
 * resumes with a range request, and only succeeds when the byte count matches exactly.
 */
class GemmaDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val store = GemmaModelStore(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (store.isPresent) return@withContext Result.success()
        if (!store.ensureDirectory()) return@withContext Result.failure()
        // Without notification permission the download still runs, just without visible progress.
        runCatching { setForeground(foregroundInfo(percent(store.downloadedBytes))) }
        try {
            download()
        } catch (error: Exception) {
            Log.w(TAG, "Download interrupted", error)
            // The partial file is kept, so the retry resumes instead of starting over.
            Result.retry()
        }
    }

    private suspend fun download(): Result {
        var have = store.downloadedBytes
        if (have > GemmaConfig.EXPECTED_BYTES) {
            store.discardPartial()
            have = 0
        }
        val connection = (URL(GemmaConfig.DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> if (have > 0) {
                    // The server ignored the range, so the bytes on disk are not a prefix of these.
                    store.discardPartial()
                    have = 0
                }
                416 -> if (store.promotePartial()) return Result.success() else {
                    store.discardPartial()
                    return Result.retry()
                }
                else -> {
                    Log.w(TAG, "Unexpected response ${connection.responseCode}")
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
        if (!store.promotePartial()) {
            Log.w(TAG, "Incomplete download: ${store.downloadedBytes} of ${GemmaConfig.EXPECTED_BYTES}")
            return Result.retry()
        }
        publish(GemmaConfig.EXPECTED_BYTES)
        return Result.success()
    }

    private suspend fun publish(bytes: Long) {
        val percent = percent(bytes)
        setProgress(Data.Builder().putInt(KEY_PERCENT, percent).build())
        runCatching { setForeground(foregroundInfo(percent)) }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.gemma_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.gemma_download_title))
            .setContentText(context.getString(R.string.gemma_download_progress, percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "GemmaDownload"
        private const val CHANNEL_ID = "gemma-download"
        private const val NOTIFICATION_ID = 41
        private const val BUFFER_BYTES = 1 shl 16
        private const val REPORT_EVERY_BYTES = 16L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000

        const val WORK_NAME = "gemma-download"
        const val KEY_PERCENT = "percent"

        fun percent(bytes: Long): Int =
            ((bytes * 100) / GemmaConfig.EXPECTED_BYTES).toInt().coerceIn(0, 100)

        /** Wi-Fi only: 2.6 GB over cellular is not something to spend on someone's behalf. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GemmaDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun isActive(info: WorkInfo?): Boolean =
            info?.state == WorkInfo.State.ENQUEUED ||
                info?.state == WorkInfo.State.RUNNING ||
                info?.state == WorkInfo.State.BLOCKED
    }
}
