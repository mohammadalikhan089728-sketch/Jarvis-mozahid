package com.example.youtube

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.JarvisApplication
import com.example.alarm.JarvisAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VideoAnalyticsCheckResult(
    val checked: Boolean,
    val summary: String,
    val triggeredCall: Boolean = false
)

/**
 * Proactive Background YouTube Analytics Advisor Worker:
 * Periodically fetches channel & latest video statistics (`channels.list`, `playlistItems.list`, `videos.list`)
 * via YouTube Data API v3. If views spike unusually high or drop below average, triggers an Incoming JARVIS Call
 * to brief Mozahid Sir with growth velocity or actionable improvement advice.
 */
class YouTubeAnalyticsWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApplication ?: return Result.success()
        if (!app.vault.isProactiveYouTubeCallsEnabled()) return Result.success()

        checkChannelAndAdvise(app, forceCallOnThreshold = true)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "jarvis_youtube_proactive_advisor"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<YouTubeAnalyticsWorker>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        suspend fun checkChannelAndAdvise(
            app: JarvisApplication,
            forceCallOnThreshold: Boolean = true
        ): VideoAnalyticsCheckResult = withContext(Dispatchers.IO) {
            val accessToken = app.youtubeOAuth.getValidAccessToken()
            val apiKey = app.vault.getYouTubeApiKey()
            if (accessToken.isNullOrBlank()) {
                return@withContext VideoAnalyticsCheckResult(
                    checked = false,
                    summary = "Connect & verify your YouTube channel first."
                )
            }

            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            try {
                // 1. Fetch uploads playlist ID & channel stats
                val chUrl = buildString {
                    append("https://www.googleapis.com/youtube/v3/channels?part=contentDetails,statistics,snippet&mine=true")
                    if (apiKey.isNotBlank()) append("&key=").append(apiKey)
                }
                val chResp = http.newCall(
                    Request.Builder().url(chUrl).addHeader("Authorization", "Bearer $accessToken").build()
                ).execute()
                val chBody = chResp.body?.string().orEmpty()
                if (!chResp.isSuccessful) {
                    return@withContext VideoAnalyticsCheckResult(false, "YouTube channels.list failed (${chResp.code})")
                }
                val chItem = JSONObject(chBody).optJSONArray("items")?.optJSONObject(0)
                    ?: return@withContext VideoAnalyticsCheckResult(false, "No channel found.")
                val uploadsPlaylistId = chItem.optJSONObject("contentDetails")
                    ?.optJSONObject("relatedPlaylists")
                    ?.optString("uploads", "")
                    .orEmpty()
                if (uploadsPlaylistId.isBlank()) {
                    return@withContext VideoAnalyticsCheckResult(false, "No uploads playlist found.")
                }

                // 2. Fetch last 6 uploaded video IDs
                val plUrl = buildString {
                    append("https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=6&playlistId=$uploadsPlaylistId")
                    if (apiKey.isNotBlank()) append("&key=").append(apiKey)
                }
                val plResp = http.newCall(
                    Request.Builder().url(plUrl).addHeader("Authorization", "Bearer $accessToken").build()
                ).execute()
                val plBody = plResp.body?.string().orEmpty()
                if (!plResp.isSuccessful) {
                    return@withContext VideoAnalyticsCheckResult(false, "Unable to read uploads list.")
                }
                val plItems = JSONObject(plBody).optJSONArray("items") ?: return@withContext VideoAnalyticsCheckResult(false, "No videos uploaded yet.")
                val videoIds = mutableListOf<String>()
                for (i in 0 until plItems.length()) {
                    val vid = plItems.optJSONObject(i)
                        ?.optJSONObject("contentDetails")
                        ?.optString("videoId", "")
                        .orEmpty()
                    if (vid.isNotBlank()) videoIds.add(vid)
                }
                if (videoIds.isEmpty()) {
                    return@withContext VideoAnalyticsCheckResult(true, "Channel has no uploaded videos yet.")
                }

                // 3. Fetch statistics via videos.list
                val vUrl = buildString {
                    append("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id=${videoIds.joinToString(",")}")
                    if (apiKey.isNotBlank()) append("&key=").append(apiKey)
                }
                val vResp = http.newCall(
                    Request.Builder().url(vUrl).addHeader("Authorization", "Bearer $accessToken").build()
                ).execute()
                val vBody = vResp.body?.string().orEmpty()
                if (!vResp.isSuccessful) {
                    return@withContext VideoAnalyticsCheckResult(false, "Unable to fetch video statistics.")
                }
                val vItems = JSONObject(vBody).optJSONArray("items") ?: return@withContext VideoAnalyticsCheckResult(false, "No video stats.")
                if (vItems.length() == 0) {
                    return@withContext VideoAnalyticsCheckResult(true, "No video metrics returned.")
                }

                val latest = vItems.getJSONObject(0)
                val latestId = latest.optString("id", "")
                val latestTitle = latest.optJSONObject("snippet")?.optString("title", "your latest video").orEmpty()
                val latestStats = latest.optJSONObject("statistics")
                val latestViews = latestStats?.optString("viewCount", "0")?.toLongOrNull() ?: 0L
                val latestLikes = latestStats?.optString("likeCount", "0")?.toLongOrNull() ?: 0L

                var baselineSum = 0L
                var baselineCount = 0
                for (i in 1 until vItems.length()) {
                    val views = vItems.getJSONObject(i).optJSONObject("statistics")
                        ?.optString("viewCount", "0")?.toLongOrNull() ?: 0L
                    baselineSum += views
                    baselineCount++
                }
                val avgViews = if (baselineCount > 0) {
                    (baselineSum / baselineCount).coerceAtLeast(10L)
                } else {
                    app.vault.getBaselineAverageViews().coerceAtLeast(100L)
                }
                app.vault.setBaselineAverageViews(avgViews)

                val isSpike = latestViews >= (avgViews * 1.45).toLong() && latestViews > 20
                val isDrop = latestViews <= (avgViews * 0.55).toLong()

                val summary = "Latest '$latestTitle': $latestViews views, $latestLikes likes (Avg: $avgViews)"

                if ((isSpike || isDrop) && forceCallOnThreshold && app.vault.getLastAlertedVideoId() != "$latestId:$isSpike") {
                    val rawBriefing = if (isSpike) {
                        "Sir, great news! Your latest video $latestTitle is trending up fast with $latestViews views and $latestLikes likes, well above your average of $avgViews views. Consider pinning a strong call-to-action comment to ride this momentum."
                    } else {
                        "Sir, quick YouTube briefing. Your latest video $latestTitle is pacing slower at $latestViews views compared to your average of $avgViews. I recommend testing a higher-contrast thumbnail and sharper hook title to lift CTR."
                    }

                    val polished = app.fallbackEngine.generateCompactText(
                        prompt = "Rewrite this YouTube advisor phone briefing for Mozahid Sir in 2 crisp, encouraging spoken sentences: $rawBriefing"
                    ) ?: rawBriefing

                    app.vault.setLastAlertedVideoId("$latestId:$isSpike")
                    JarvisAlarmScheduler.triggerImmediateCall(
                        context = app.applicationContext,
                        reason = polished,
                        callType = JarvisAlarmScheduler.CALL_TYPE_YOUTUBE_ADVISOR
                    )
                    return@withContext VideoAnalyticsCheckResult(true, summary, triggeredCall = true)
                }

                VideoAnalyticsCheckResult(true, summary, triggeredCall = false)
            } catch (e: Exception) {
                VideoAnalyticsCheckResult(false, "Analytics error: ${e.message}")
            }
        }
    }
}
