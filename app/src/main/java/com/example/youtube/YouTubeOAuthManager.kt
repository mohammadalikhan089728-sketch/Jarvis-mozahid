package com.example.youtube

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.example.data.SecureVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class OAuthClientCredentials(
    val clientId: String,
    val clientSecret: String,
    val authUri: String,
    val tokenUri: String,
    val redirectUri: String
)

data class YouTubeVerificationResult(
    val success: Boolean,
    val channelName: String = "",
    val channelId: String = "",
    val statsSummary: String = "",
    val errorMessage: String = ""
)

/**
 * Handles Google OAuth 2.0 Client Credentials (JSON) parsing, PKCE Google Sign-In launch
 * (supporting Test User Gmail accounts), token exchange/refresh, and YouTube Data API v3 channel verification.
 */
class YouTubeOAuthManager(
    private val vault: SecureVault
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun parseCredentials(rawJson: String): OAuthClientCredentials? {
        if (rawJson.isBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            val block = root.optJSONObject("installed")
                ?: root.optJSONObject("web")
                ?: root
            val clientId = block.optString("client_id", "").trim()
            if (clientId.isEmpty()) return null
            val clientSecret = block.optString("client_secret", "").trim()
            val authUri = block.optString("auth_uri", DEFAULT_AUTH_URI).ifBlank { DEFAULT_AUTH_URI }
            val tokenUri = block.optString("token_uri", DEFAULT_TOKEN_URI).ifBlank { DEFAULT_TOKEN_URI }

            val redirects = block.optJSONArray("redirect_uris")
            val parsedRedirect = if (redirects != null && redirects.length() > 0) {
                var picked = APP_REDIRECT_URI
                for (i in 0 until redirects.length()) {
                    val candidate = redirects.optString(i, "")
                    if (candidate.startsWith("com.aistudio.jarvis")) {
                        picked = candidate
                        break
                    }
                }
                picked
            } else {
                APP_REDIRECT_URI
            }

            OAuthClientCredentials(
                clientId = clientId,
                clientSecret = clientSecret,
                authUri = authUri,
                tokenUri = tokenUri,
                redirectUri = parsedRedirect
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Launches Google Sign-In OAuth 2.0 consent flow in browser (supports Test User Gmail accounts).
     */
    fun launchOAuthConsentFlow(context: Context): Result<String> {
        val creds = parseCredentials(vault.getYouTubeOAuthJson())
            ?: return Result.failure(IllegalStateException("Please paste or pick valid OAuth 2.0 Client Credentials JSON first."))

        val codeVerifier = generateCodeVerifier()
        vault.savePkceCodeVerifier(codeVerifier)
        val codeChallenge = generateCodeChallenge(codeVerifier)

        val authUrl = Uri.parse(creds.authUri).buildUpon()
            .appendQueryParameter("client_id", creds.clientId)
            .appendQueryParameter("redirect_uri", creds.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", YOUTUBE_SCOPES)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        return try {
            val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success("Google Sign-In launched. Approve Test User access to verify your channel.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Exchanges the OAuth authorization code for access + refresh tokens and verifies the YouTube channel.
     */
    suspend fun exchangeCodeAndVerifyChannel(authCode: String): YouTubeVerificationResult =
        withContext(Dispatchers.IO) {
            val creds = parseCredentials(vault.getYouTubeOAuthJson())
                ?: return@withContext YouTubeVerificationResult(
                    success = false,
                    errorMessage = "Missing OAuth 2.0 Client Credentials JSON."
                )

            val verifier = vault.getPkceCodeVerifier()
            val formBuilder = FormBody.Builder()
                .add("code", authCode.trim())
                .add("client_id", creds.clientId)
                .add("redirect_uri", creds.redirectUri)
                .add("grant_type", "authorization_code")
            if (creds.clientSecret.isNotBlank()) {
                formBuilder.add("client_secret", creds.clientSecret)
            }
            if (verifier.isNotBlank()) {
                formBuilder.add("code_verifier", verifier)
            }

            val req = Request.Builder()
                .url(creds.tokenUri)
                .post(formBuilder.build())
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext YouTubeVerificationResult(
                            success = false,
                            errorMessage = "OAuth token exchange failed (${resp.code})"
                        )
                    }
                    val json = JSONObject(raw)
                    val accessToken = json.optString("access_token", "")
                    val refreshToken = json.optString("refresh_token", "")
                    val expiresIn = json.optLong("expires_in", 3600L)
                    if (accessToken.isBlank()) {
                        return@withContext YouTubeVerificationResult(
                            success = false,
                            errorMessage = "No access token returned by Google OAuth."
                        )
                    }
                    vault.saveYouTubeTokens(accessToken, refreshToken.ifBlank { null }, expiresIn)
                }
            } catch (e: Exception) {
                return@withContext YouTubeVerificationResult(
                    success = false,
                    errorMessage = e.message ?: "Network error during token exchange."
                )
            }

            verifyConnectedChannel()
        }

    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val current = vault.getYouTubeAccessToken()
        val expiry = vault.getYouTubeTokenExpiry()
        if (current.isNotBlank() && System.currentTimeMillis() < expiry) {
            return@withContext current
        }

        val refresh = vault.getYouTubeRefreshToken()
        val creds = parseCredentials(vault.getYouTubeOAuthJson())
        if (refresh.isBlank() || creds == null) return@withContext current.ifBlank { null }

        val formBuilder = FormBody.Builder()
            .add("client_id", creds.clientId)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
        if (creds.clientSecret.isNotBlank()) {
            formBuilder.add("client_secret", creds.clientSecret)
        }

        val req = Request.Builder().url(creds.tokenUri).post(formBuilder.build()).build()
        return@withContext try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                val newAccess = json.optString("access_token", "")
                val expiresIn = json.optLong("expires_in", 3600L)
                if (newAccess.isNotBlank()) {
                    vault.saveYouTubeTokens(newAccess, null, expiresIn)
                    newAccess
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Calls YouTube Data API v3 `channels.list?part=snippet,statistics&mine=true` to verify channel identity.
     */
    suspend fun verifyConnectedChannel(): YouTubeVerificationResult = withContext(Dispatchers.IO) {
        val accessToken = getValidAccessToken()
            ?: return@withContext YouTubeVerificationResult(
                success = false,
                errorMessage = "Not signed in to YouTube yet."
            )

        val apiKey = vault.getYouTubeApiKey()
        val url = buildString {
            append("https://www.googleapis.com/youtube/v3/channels?part=snippet,statistics&mine=true")
            if (apiKey.isNotBlank()) append("&key=").append(apiKey)
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return@withContext try {
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext YouTubeVerificationResult(
                        success = false,
                        errorMessage = "YouTube API error (${resp.code})"
                    )
                }
                val root = JSONObject(raw)
                val items = root.optJSONArray("items")
                val channel = items?.optJSONObject(0)
                    ?: return@withContext YouTubeVerificationResult(
                        success = false,
                        errorMessage = "No YouTube channel found on this Google account."
                    )

                val channelId = channel.optString("id", "")
                val title = channel.optJSONObject("snippet")?.optString("title", "Verified Channel").orEmpty()
                val stats = channel.optJSONObject("statistics")
                val subs = stats?.optString("subscriberCount", "0") ?: "0"
                val views = stats?.optString("viewCount", "0") ?: "0"
                val videos = stats?.optString("videoCount", "0") ?: "0"
                val summary = "$subs subs · $videos videos · $views views"

                vault.saveYouTubeChannelInfo(channelId, title, summary)
                YouTubeVerificationResult(
                    success = true,
                    channelName = title,
                    channelId = channelId,
                    statsSummary = summary
                )
            }
        } catch (e: Exception) {
            YouTubeVerificationResult(
                success = false,
                errorMessage = e.message ?: "Channel verification failed."
            )
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val APP_REDIRECT_URI = "com.aistudio.jarvis.mzhkqr:/oauth2redirect"
        private const val DEFAULT_AUTH_URI = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        private const val YOUTUBE_SCOPES =
            "https://www.googleapis.com/auth/youtube.upload https://www.googleapis.com/auth/youtube.readonly"
    }
}
