package com.example.youtube

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.JarvisApplication
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class YouTubeUploadProgressState(
    val isUploading: Boolean = false,
    val percent: Int = 0,
    val statusText: String = "",
    val lastResultMessage: String? = null
)

/**
 * Shared live progress holder observed by both HomeScreen (subtle progress strip)
 * and SettingsScreen while YouTubeUploadWorker runs uninterrupted in the background.
 */
object YouTubeUploadProgressStore {
    private val _state = MutableStateFlow(YouTubeUploadProgressState())
    val state: StateFlow<YouTubeUploadProgressState> = _state.asStateFlow()

    fun update(isUploading: Boolean, percent: Int, statusText: String, lastResult: String? = null) {
        _state.value = YouTubeUploadProgressState(
            isUploading = isUploading,
            percent = percent.coerceIn(0, 100),
            statusText = statusText,
            lastResultMessage = lastResult ?: _state.value.lastResultMessage
        )
    }
}

/**
 * Persistent Background YouTube Auto-SEO Upload Worker:
 * Runs via WorkManager with Foreground Notification progress (`0%` to `100%`) so uploads continue
 * uninterrupted when the user minimizes JARVIS or keeps talking hands-free.
 */
class YouTubeUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApplication ?: return Result.failure()
        val spokenTopic = inputData.getString(KEY_SPOKEN_TOPIC)

        ensureChannel()
        YouTubeUploadProgressStore.update(true, 0, "Preparing YouTube upload...")
        runCatching { setForeground(createForegroundInfo(0, "Preparing YouTube upload...")) }

        val outcome = app.youtubeUploader.uploadLatestFromWatchFolder(
            spokenTopic = spokenTopic,
            onProgress = { pct, status ->
                YouTubeUploadProgressStore.update(true, pct, status)
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildProgressNotification(pct, status, ongoing = true)
                )
            }
        )

        return if (outcome.success) {
            YouTubeUploadProgressStore.update(
                isUploading = false,
                percent = 100,
                statusText = outcome.message,
                lastResult = outcome.message
            )
            notificationManager.notify(
                COMPLETION_NOTIFICATION_ID,
                buildProgressNotification(100, outcome.message, ongoing = false)
            )
            app.voiceEngine.speakNeural("Sir, your video ${outcome.title} has been uploaded to YouTube.")
            Result.success()
        } else {
            YouTubeUploadProgressStore.update(
                isUploading = false,
                percent = 0,
                statusText = outcome.message,
                lastResult = outcome.message
            )
            notificationManager.notify(
                COMPLETION_NOTIFICATION_ID,
                buildProgressNotification(0, outcome.message, ongoing = false)
            )
            Result.failure()
        }
    }

    private fun createForegroundInfo(percent: Int, status: String): ForegroundInfo {
        val notification = buildProgressNotification(percent, status, ongoing = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(
        percent: Int,
        status: String,
        ongoing: Boolean
    ) = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(if (ongoing) "JARVIS Uploading to YouTube · $percent%" else "JARVIS YouTube Upload")
        .setContentText(status)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .apply {
            if (ongoing) {
                setProgress(100, percent.coerceIn(0, 100), false)
            } else {
                setProgress(0, 0, false)
                setAutoCancel(true)
            }
        }
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Background YouTube Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live upload percentage progress for background YouTube uploads"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val WORK_NAME = "jarvis_youtube_background_upload"
        private const val CHANNEL_ID = "jarvis_youtube_upload_channel"
        private const val NOTIFICATION_ID = 9050
        private const val COMPLETION_NOTIFICATION_ID = 9051
        const val KEY_SPOKEN_TOPIC = "spoken_topic"

        fun enqueue(context: Context, spokenTopic: String? = null) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val input = Data.Builder()
                .apply {
                    if (!spokenTopic.isNullOrBlank()) putString(KEY_SPOKEN_TOPIC, spokenTopic)
                }
                .build()
            val request = OneTimeWorkRequestBuilder<YouTubeUploadWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
