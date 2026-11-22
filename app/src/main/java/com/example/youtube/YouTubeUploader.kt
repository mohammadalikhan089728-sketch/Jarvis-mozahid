package com.example.youtube

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.ai.CircularFallbackEngine
import com.example.data.SecureVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
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
 * - Reads newest video and accompanying `.txt` topic note from the SAF Watch Folder (`Jarvis_Uploads`).
 * - Generates high-CTR viral Title, SEO Description, and Tags via the active Gemini circular engine.
 * - Streams video bytes directly to YouTube Data API v3 (`videos.insert` resumable upload).
 */
class YouTubeUploader(
    private val context: Context,
    private val vault: SecureVault,
    private val oauthManager: YouTubeOAuthManager,
    private val fallbackEngine: CircularFallbackEngine
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun uploadLatestFromWatchFolder(
        spokenTopic: String? = null,
        onStatus: (String) -> Unit = {}
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
                message = "YouTube channel not connected. Please verify in Settings first."
            )

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

        onStatus("Generating high-CTR SEO metadata...")
        val seo = generateViralSeo(topicContext, videoBaseName)

        onStatus("Initiating resumable upload to YouTube...")
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

        onStatus("Uploading ${videoFile.name} to YouTube...")
        val streamBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength(): Long = if (fileLength > 0L) fileLength else -1L
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(videoFile.uri)?.use { input ->
                    sink.writeAll(input.source())
                }
            }
        }

        val putRequest = Request.Builder()
            .url(uploadSessionUri)
            .addHeader("Authorization", "Bearer $accessToken")
            .put(streamBody)
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
            Generate high-CTR viral YouTube SEO metadata for this video topic:
            "$topicContext"
            Return STRICT JSON only with keys:
            {
              "title": "compelling under-70-char viral title",
              "description": "engaging SEO-rich description with hashtags",
              "tags": ["tag1", "tag2", "tag3", "tag4", "tag5", "tag6", "tag7", "tag8"]
            }
        """.trimIndent()

        val raw = fallbackEngine.generateCompactText(
            prompt = prompt,
            systemInstruction = "You are a YouTube growth & SEO strategist. Return strictly valid JSON only.",
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
