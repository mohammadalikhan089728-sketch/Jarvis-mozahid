package com.example.youtube

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.ai.FallbackApiClient
import com.example.data.SecureVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeneratedSeoMetadata(
    val title: String,
    val description: String,
    val tags: List<String>
)

data class YouTubeUploadOutcome(
    val success: Boolean,
    val videoId: String = "",
    val title: String = "",
    val message: String
)

/**
 * Folder-to-YouTube Auto-SEO Uploader:
 * - Reads newest video + `.txt` topic note (in Hindi, Hinglish, or English) from Watch Folder.
 * - Uses active FallbackApiClient model to generate high-CTR viral SEO Title, Description, and Tags
 *   matching the topic's target language.
 * - Uploads via YouTube Data API v3 (`videos.insert` resumable upload) with live byte-level percentage callbacks.
 */
class YouTubeUploader(
    private val context: Context,
    private val vault: SecureVault,
    private val oauthManager: YouTubeOAuthManager,
    private val fallbackApiClient: FallbackApiClient
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun uploadLatestFromWatchFolder(
        spokenTopic: String? = null,
        onProgress: (percent: Int, status: String) -> Unit = { _, _ -> }
    ): YouTubeUploadOutcome = withContext(Dispatchers.IO) {
        val folderUriStr = vault.getWatchFolderUri()
        if (folderUriStr.isBlank()) {
            return@withContext YouTubeUploadOutcome(
                success = false,
                message = "No watch folder selected. Please pick Jarvis_Uploads in Settings."
            )
        }

        val accessToken = oauthManager.getValidAccessToken()
            ?: return@withContext YouTubeUploadOutcome(
                success = false,
                message = "YouTube channel not connected. Please connect & verify in Settings."
            )

        onProgress(2, "Scanning ${vault.getWatchFolderName()}...")
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
            ?: return@withContext YouTubeUploadOutcome(
                success = false,
                message = "Unable to access watch folder."
            )

        val files = folder.listFiles()
        val videoFile = files
            .filter { file ->
                val name = file.name?.lowercase().orEmpty()
                file.isFile && VIDEO_EXTENSIONS.any { name.endsWith(it) }
            }
            .maxByOrNull { it.lastModified() }
            ?: return@withContext YouTubeUploadOutcome(
                success = false,
                message = "No video files found in ${vault.getWatchFolderName()}."
            )

        val videoBaseName = videoFile.name.orEmpty().substringBeforeLast(".")
        val matchingTxt = files.firstOrNull { file ->
            val name = file.name.orEmpty()
            file.isFile && name.endsWith(".txt", ignoreCase = true) &&
                (name.substringBeforeLast(".").equals(videoBaseName, ignoreCase = true) ||
                    files.count { it.name?.endsWith(".txt", ignoreCase = true) == true } == 1)
        }

        val noteText = matchingTxt?.uri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().trim() }
            }.getOrNull()
        }.orEmpty()

        val topicContext = buildString {
            if (!spokenTopic.isNullOrBlank()) append(spokenTopic).append("\n")
            if (noteText.isNotBlank()) append(noteText)
            if (isEmpty()) append(videoBaseName.replace('_', ' ').replace('-', ' '))
        }.trim()

        onProgress(8, "Generating viral SEO metadata...")
        val seo = generateViralSeo(topicContext, videoBaseName)

        onProgress(14, "Initializing YouTube resumable upload...")
        val mimeType = videoFile.type ?: "video/mp4"
        val fileLength = videoFile.length()

        val metadataJson = JSONObject()
            .put(
                "snippet",
                JSONObject()
                    .put("title", seo.title.take(98))
                    .put("description", seo.description.take(4800))
                    .put("tags", JSONArray(seo.tags.take(15)))
                    .put("categoryId", "22")
            )
            .put(
                "status",
                JSONObject()
                    .put("privacyStatus", "public")
                    .put("selfDeclaredMadeForKids", false)
            )

        val initUrl = buildString {
            append("https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status")
            val apiKey = vault.getYouTubeApiKey()
            if (apiKey.isNotBlank()) append("&key=").append(apiKey)
        }

        val initRequest = Request.Builder()
            .url(initUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("X-Upload-Content-Type", mimeType)
            .apply {
                if (fileLength > 0L) addHeader("X-Upload-Content-Length", fileLength.toString())
            }
            .post(metadataJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val uploadSessionUri = try {
            httpClient.newCall(initRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext YouTubeUploadOutcome(
                        success = false,
                        message = "YouTube upload init failed (HTTP ${resp.code})."
                    )
                }
                resp.header("Location")
            }
        } catch (e: Exception) {
            return@withContext YouTubeUploadOutcome(
                success = false,
                message = "Upload init error: ${e.message}"
            )
        } ?: return@withContext YouTubeUploadOutcome(
            success = false,
            message = "YouTube did not return a resumable upload Location."
        )

        onProgress(15, "Uploading ${seo.title.take(28)}...")
        val totalBytes = fileLength.coerceAtLeast(1L)

        val progressBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength(): Long = if (fileLength > 0L) fileLength else -1L

            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                var uploaded = 0L
                var lastReportedPercent = 15

                context.contentResolver.openInputStream(videoFile.uri)?.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                        uploaded += read

                        val rawPct = if (fileLength > 0L) {
                            15 + ((uploaded * 84L) / totalBytes).toInt()
                        } else {
                            (lastReportedPercent + 1).coerceAtMost(95)
                        }
                        val clamped = rawPct.coerceIn(15, 99)
                        if (clamped > lastReportedPercent) {
                            lastReportedPercent = clamped
                            onProgress(clamped, "Uploading ${seo.title.take(24)} · $clamped%")
                        }
                    }
                    sink.flush()
                }
            }
        }

        val putRequest = Request.Builder()
            .url(uploadSessionUri)
            .addHeader("Authorization", "Bearer $accessToken")
            .put(progressBody)
            .build()

        try {
            httpClient.newCall(putRequest).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext YouTubeUploadOutcome(
                        success = false,
                        message = "Video upload failed (HTTP ${resp.code})."
                    )
                }
                val videoId = runCatching { JSONObject(raw).optString("id", "") }.getOrDefault("")
                onProgress(100, "Uploaded '${seo.title}'")
                YouTubeUploadOutcome(
                    success = true,
                    videoId = videoId,
                    title = seo.title,
                    message = "Uploaded '${seo.title}' successfully to YouTube."
                )
            }
        } catch (e: Exception) {
            YouTubeUploadOutcome(
                success = false,
                message = "Upload transfer error: ${e.message}"
            )
        }
    }

    private suspend fun generateViralSeo(topicContext: String, fallbackName: String): GeneratedSeoMetadata {
        val prompt = """
            Generate high-CTR viral YouTube SEO metadata in the SAME language as the topic below (Hindi, Hinglish, or English):
            "$topicContext"
            Return STRICT JSON only:
            {
              "title": "compelling viral title under 70 chars",
              "description": "SEO-optimized description with relevant hashtags",
              "tags": ["tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7", "tag8"]
            }
        """.trimIndent()

        val raw = fallbackApiClient.generateCompactText(
            prompt = prompt,
            systemInstruction = "You are a YouTube growth & SEO strategist. Match the user's language (Hindi, Hinglish, or English). Return strictly valid JSON only.",
            expectJson = true
        )

        if (!raw.isNullOrBlank()) {
            try {
                val cleaned = raw.substringAfter("{").substringBeforeLast("}")
                val json = JSONObject("{$cleaned}")
                val title = json.optString("title", "").trim()
                val desc = json.optString("description", "").trim()
                val tagsJson = json.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsJson != null) {
                    for (i in 0 until tagsJson.length()) {
                        val t = tagsJson.optString(i, "").trim()
                        if (t.isNotEmpty()) tags.add(t)
                    }
                }
                if (title.isNotEmpty()) {
                    return GeneratedSeoMetadata(
                        title = title,
                        description = desc.ifEmpty { topicContext },
                        tags = tags.ifEmpty { listOf("viral", "trending", "youtube") }
                    )
                }
            } catch (_: Exception) {
            }
        }

        val cleanTitle = fallbackName.replace('_', ' ').replace('-', ' ')
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifBlank { "New Video Upload" }
        return GeneratedSeoMetadata(
            title = cleanTitle,
            description = "$topicContext\n\n#youtube #trending #viral",
            tags = listOf("youtube", "trending", "jarvis")
        )
    }

    companion object {
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".mov", ".mkv", ".webm", ".3gp")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
