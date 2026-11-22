package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class JarvisConfigSnapshot(
    val geminiKey1: String = "",
    val geminiKey2: String = "",
    val youtubeApiKey: String = "",
    val youtubeOAuthJson: String = "",
    val youtubeChannelName: String = "",
    val youtubeChannelId: String = "",
    val youtubeChannelStats: String = "",
    val isYouTubeConnected: Boolean = false,
    val autoReplyEnabled: Boolean = false,
    val proactiveYouTubeCallsEnabled: Boolean = false,
    val watchFolderUri: String = "",
    val watchFolderName: String = "Jarvis_Uploads (Not Selected)"
)

/**
 * Hardware-backed Android Keystore (AES-256-GCM) vault for storing API keys, OAuth tokens,
 * and assistant preferences securely on-device with zero hardcoded credentials.
 */
class SecureVault(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadSnapshot())
    val configFlow: StateFlow<JarvisConfigSnapshot> = _configFlow.asStateFlow()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteBuffer.allocate(4 + iv.size + encrypted.size)
                .putInt(iv.size)
                .put(iv)
                .put(encrypted)
                .array()
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decrypt(cipherText: String?): String {
        if (cipherText.isNullOrEmpty()) return ""
        return try {
            val combined = Base64.decode(cipherText, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(combined)
            val ivSize = buffer.int
            if (ivSize !in 12..16) return ""
            val iv = ByteArray(ivSize)
            buffer.get(iv)
            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun putSecret(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value.trim())).apply()
        refresh()
    }

    private fun getSecret(key: String): String = decrypt(prefs.getString(key, null))

    fun getGeminiKey1(): String = getSecret(KEY_GEMINI_1)
    fun getGeminiKey2(): String = getSecret(KEY_GEMINI_2)

    fun saveGeminiKeys(key1: String, key2: String) {
        prefs.edit()
            .putString(KEY_GEMINI_1, encrypt(key1.trim()))
            .putString(KEY_GEMINI_2, encrypt(key2.trim()))
            .apply()
        refresh()
    }

    fun getAvailableGeminiKeys(): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        val k1 = getGeminiKey1()
        val k2 = getGeminiKey2()
        if (k1.isNotBlank()) list.add(1 to k1)
        if (k2.isNotBlank()) list.add(2 to k2)
        return list
    }

    fun getYouTubeApiKey(): String = getSecret(KEY_YT_API)
    fun saveYouTubeApiKey(key: String) = putSecret(KEY_YT_API, key)

    fun getYouTubeOAuthJson(): String = getSecret(KEY_YT_OAUTH_JSON)
    fun saveYouTubeOAuthJson(json: String) = putSecret(KEY_YT_OAUTH_JSON, json)

    fun getYouTubeAccessToken(): String = getSecret(KEY_YT_ACCESS_TOKEN)
    fun getYouTubeRefreshToken(): String = getSecret(KEY_YT_REFRESH_TOKEN)
    fun getYouTubeTokenExpiry(): Long = prefs.getLong(KEY_YT_TOKEN_EXPIRY, 0L)

    fun saveYouTubeTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        val expiryAt = System.currentTimeMillis() + (expiresInSeconds * 1000L) - 60_000L
        val editor = prefs.edit()
            .putString(KEY_YT_ACCESS_TOKEN, encrypt(accessToken))
            .putLong(KEY_YT_TOKEN_EXPIRY, expiryAt)
        if (!refreshToken.isNullOrBlank()) {
            editor.putString(KEY_YT_REFRESH_TOKEN, encrypt(refreshToken))
        }
        editor.apply()
        refresh()
    }

    fun saveYouTubeChannelInfo(channelId: String, channelName: String, statsSummary: String) {
        prefs.edit()
            .putString(KEY_YT_CHANNEL_ID, channelId)
            .putString(KEY_YT_CHANNEL_NAME, channelName)
            .putString(KEY_YT_CHANNEL_STATS, statsSummary)
            .apply()
        refresh()
    }

    fun clearYouTubeConnection() {
        prefs.edit()
            .remove(KEY_YT_ACCESS_TOKEN)
            .remove(KEY_YT_REFRESH_TOKEN)
            .remove(KEY_YT_TOKEN_EXPIRY)
            .remove(KEY_YT_CHANNEL_ID)
            .remove(KEY_YT_CHANNEL_NAME)
            .remove(KEY_YT_CHANNEL_STATS)
            .apply()
        refresh()
    }

    fun savePkceCodeVerifier(verifier: String) {
        prefs.edit().putString(KEY_PKCE_VERIFIER, encrypt(verifier)).apply()
    }

    fun getPkceCodeVerifier(): String = getSecret(KEY_PKCE_VERIFIER)

    fun isAutoReplyEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_REPLY, false)
    fun setAutoReplyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_REPLY, enabled).apply()
        refresh()
    }

    fun isProactiveYouTubeCallsEnabled(): Boolean = prefs.getBoolean(KEY_PROACTIVE_YT_CALLS, false)
    fun setProactiveYouTubeCallsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROACTIVE_YT_CALLS, enabled).apply()
        refresh()
    }

    fun getWatchFolderUri(): String = prefs.getString(KEY_WATCH_FOLDER_URI, "") ?: ""
    fun getWatchFolderName(): String =
        prefs.getString(KEY_WATCH_FOLDER_NAME, "Jarvis_Uploads (Not Selected)")
            ?: "Jarvis_Uploads (Not Selected)"

    fun saveWatchFolder(uri: String, displayName: String) {
        prefs.edit()
            .putString(KEY_WATCH_FOLDER_URI, uri)
            .putString(KEY_WATCH_FOLDER_NAME, displayName)
            .apply()
        refresh()
    }

    fun getLastAlertedVideoId(): String = prefs.getString(KEY_LAST_ALERTED_VIDEO_ID, "") ?: ""
    fun setLastAlertedVideoId(videoId: String) {
        prefs.edit().putString(KEY_LAST_ALERTED_VIDEO_ID, videoId).apply()
    }

    fun getBaselineAverageViews(): Long = prefs.getLong(KEY_BASELINE_AVG_VIEWS, 0L)
    fun setBaselineAverageViews(views: Long) {
        prefs.edit().putLong(KEY_BASELINE_AVG_VIEWS, views).apply()
    }

    private fun loadSnapshot(): JarvisConfigSnapshot {
        val channelName = prefs.getString(KEY_YT_CHANNEL_NAME, "") ?: ""
        val accessToken = getYouTubeAccessToken()
        return JarvisConfigSnapshot(
            geminiKey1 = getGeminiKey1(),
            geminiKey2 = getGeminiKey2(),
            youtubeApiKey = getYouTubeApiKey(),
            youtubeOAuthJson = getYouTubeOAuthJson(),
            youtubeChannelName = channelName,
            youtubeChannelId = prefs.getString(KEY_YT_CHANNEL_ID, "") ?: "",
            youtubeChannelStats = prefs.getString(KEY_YT_CHANNEL_STATS, "") ?: "",
            isYouTubeConnected = channelName.isNotBlank() && accessToken.isNotBlank(),
            autoReplyEnabled = isAutoReplyEnabled(),
            proactiveYouTubeCallsEnabled = isProactiveYouTubeCallsEnabled(),
            watchFolderUri = getWatchFolderUri(),
            watchFolderName = getWatchFolderName()
        )
    }

    fun refresh() {
        _configFlow.value = loadSnapshot()
    }

    companion object {
        private const val PREFS_NAME = "jarvis_secure_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_aes_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val KEY_GEMINI_1 = "enc_gemini_key_1"
        private const val KEY_GEMINI_2 = "enc_gemini_key_2"
        private const val KEY_YT_API = "enc_youtube_api_key"
        private const val KEY_YT_OAUTH_JSON = "enc_youtube_oauth_json"
        private const val KEY_YT_ACCESS_TOKEN = "enc_youtube_access_token"
        private const val KEY_YT_REFRESH_TOKEN = "enc_youtube_refresh_token"
        private const val KEY_YT_TOKEN_EXPIRY = "youtube_token_expiry"
        private const val KEY_YT_CHANNEL_ID = "youtube_channel_id"
        private const val KEY_YT_CHANNEL_NAME = "youtube_channel_name"
        private const val KEY_YT_CHANNEL_STATS = "youtube_channel_stats"
        private const val KEY_PKCE_VERIFIER = "enc_pkce_verifier"
        private const val KEY_AUTO_REPLY = "auto_reply_enabled"
        private const val KEY_PROACTIVE_YT_CALLS = "proactive_yt_calls_enabled"
        private const val KEY_WATCH_FOLDER_URI = "watch_folder_uri"
        private const val KEY_WATCH_FOLDER_NAME = "watch_folder_name"
        private const val KEY_LAST_ALERTED_VIDEO_ID = "last_alerted_video_id"
        private const val KEY_BASELINE_AVG_VIEWS = "baseline_avg_views"
    }
}
