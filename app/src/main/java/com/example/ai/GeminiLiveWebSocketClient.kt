package com.example.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LiveTurnResult(
    val transcript: String,
    val receivedNativeAudio: Boolean,
    val toolCalls: List<ToolCallRequest>,
    val httpCode: Int = 0,
    val isAuthFailure: Boolean = false,
    val isRetryableError: Boolean = false,
    val errorMessage: String? = null
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val args: JSONObject
)

/**
 * Multimodal Gemini Live WebSocket client (BidiGenerateContent):
 * - Tries `models/gemini-3.1-flash-live-preview` with fast fallback to
 *   `models/gemini-2.5-flash-native-audio-preview-09-2025` and `models/gemini-2.0-flash-exp`.
 * - Smart Auth: `AIza...` -> `?key=...` + `x-goog-api-key`; `AQ.` / Bearer -> `Authorization: Bearer ...`
 *   with automatic one-time retry using `?key=...` on 401/403.
 * - Zero-Token-Wastage: single-turn `clientContent` payloads and `resumeHandle` reconnects.
 */
class GeminiLiveWebSocketClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var activeApiKey: String = ""

    @Volatile
    private var activeLiveModel: String = ""

    @Volatile
    private var isSetupComplete = false

    @Volatile
    var resumeHandle: String? = null
        private set

    private var setupDeferred: CompletableDeferred<Boolean>? = null
    private var turnDeferred: CompletableDeferred<LiveTurnResult>? = null

    private val turnTranscript = StringBuilder()
    private val pendingToolCalls = mutableListOf<ToolCallRequest>()
    private var receivedAudioInTurn = false
    private var onAudioChunk: ((String) -> Unit)? = null
    private var onTranscriptDelta: ((String) -> Unit)? = null

    suspend fun sendSingleTurn(
        apiKey: String,
        primaryModelId: String,
        userText: String,
        memorySnippet: String?,
        onAudioBase64: (String) -> Unit,
        onTextDelta: (String) -> Unit
    ): LiveTurnResult {
        val cleanKey = apiKey.trim()
        onAudioChunk = onAudioBase64
        onTranscriptDelta = onTextDelta

        if (webSocket == null || !isSetupComplete || activeApiKey != cleanKey) {
            val candidates = (listOf(primaryModelId) + ModelRingConfig.LIVE_FALLBACK_MODELS).distinct()
            var lastCode = 0
            var lastMsg: String? = null
            var lastAuthFail = false
            var connected = false

            for (liveModel in candidates) {
                val attempt = connectWithSmartAuth(cleanKey, liveModel)
                if (attempt.connected) {
                    activeLiveModel = liveModel
                    connected = true
                    break
                }
                lastCode = attempt.httpCode
                lastMsg = attempt.errorMessage
                lastAuthFail = attempt.isAuthFailure
                if (attempt.isAuthFailure) {
                    // Don't waste time trying other live models with the same rejected credential
                    break
                }
            }

            if (!connected) {
                return LiveTurnResult(
                    transcript = "",
                    receivedNativeAudio = false,
                    toolCalls = emptyList(),
                    httpCode = lastCode,
                    isAuthFailure = lastAuthFail,
                    isRetryableError = true,
                    errorMessage = lastMsg ?: "Live WebSocket unavailable"
                )
            }
        }

        turnTranscript.clear()
        pendingToolCalls.clear()
        receivedAudioInTurn = false
        val deferred = CompletableDeferred<LiveTurnResult>()
        turnDeferred = deferred

        val promptWithMemory = if (!memorySnippet.isNullOrBlank()) {
            "[Local Memory: $memorySnippet]\n$userText"
        } else {
            userText
        }

        val clientContentPayload = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", promptWithMemory))
                            )
                    )
                )
                .put("turnComplete", true)
        )

        val sent = webSocket?.send(clientContentPayload.toString()) == true
        if (!sent) {
            close()
            return LiveTurnResult(
                transcript = "",
                receivedNativeAudio = false,
                toolCalls = emptyList(),
                isRetryableError = true,
                errorMessage = "Live WebSocket closed during send"
            )
        }

        return withTimeoutOrNull(14_000L) { deferred.await() } ?: run {
            close()
            LiveTurnResult(
                transcript = turnTranscript.toString().trim(),
                receivedNativeAudio = receivedAudioInTurn,
                toolCalls = pendingToolCalls.toList(),
                isRetryableError = turnTranscript.isEmpty() && pendingToolCalls.isEmpty(),
                errorMessage = "Live response timeout"
            )
        }
    }

    suspend fun sendToolResponses(
        responses: List<Pair<ToolCallRequest, String>>,
        onAudioBase64: (String) -> Unit,
        onTextDelta: (String) -> Unit
    ): LiveTurnResult {
        onAudioChunk = onAudioBase64
        onTranscriptDelta = onTextDelta
        turnTranscript.clear()
        pendingToolCalls.clear()
        receivedAudioInTurn = false

        val deferred = CompletableDeferred<LiveTurnResult>()
        turnDeferred = deferred

        val fnResponses = JSONArray()
        for ((req, result) in responses) {
            fnResponses.put(
                JSONObject()
                    .put("id", req.id)
                    .put("name", req.name)
                    .put("response", JSONObject().put("result", result))
            )
        }

        val payload = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", fnResponses)
        )

        val sent = webSocket?.send(payload.toString()) == true
        if (!sent) {
            return LiveTurnResult(
                transcript = "",
                receivedNativeAudio = false,
                toolCalls = emptyList(),
                isRetryableError = true,
                errorMessage = "Tool response WebSocket closed"
            )
        }

        return withTimeoutOrNull(14_000L) { deferred.await() } ?: LiveTurnResult(
            transcript = turnTranscript.toString().trim(),
            receivedNativeAudio = receivedAudioInTurn,
            toolCalls = pendingToolCalls.toList(),
            isRetryableError = false
        )
    }

    private data class ConnectAttempt(
        val connected: Boolean,
        val httpCode: Int = 0,
        val isAuthFailure: Boolean = false,
        val errorMessage: String? = null
    )

    private suspend fun connectWithSmartAuth(
        credential: String,
        modelId: String
    ): ConnectAttempt {
        val clean = credential.trim()
        if (clean.startsWith("AIza")) {
            return connectOnce(clean, modelId, useBearerHeader = false)
        }
        // AQ.Ab8... / Bearer token: try Authorization: Bearer first, retry once with ?key= on 401/403
        val bearerAttempt = connectOnce(clean, modelId, useBearerHeader = true)
        if (bearerAttempt.connected) return bearerAttempt
        if (bearerAttempt.httpCode in setOf(400, 401, 403) || bearerAttempt.isAuthFailure) {
            return connectOnce(clean, modelId, useBearerHeader = false)
        }
        return bearerAttempt
    }

    private suspend fun connectOnce(
        credential: String,
        modelId: String,
        useBearerHeader: Boolean
    ): ConnectAttempt {
        close()
        activeApiKey = credential
        isSetupComplete = false
        val setup = CompletableDeferred<Boolean>()
        setupDeferred = setup
        var failureCode = 0
        var failureMsg: String? = null
        var authFail = false

        val baseWsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        val requestBuilder = if (useBearerHeader) {
            val bareToken = credential.removePrefix("Bearer ").trim()
            Request.Builder()
                .url(baseWsUrl)
                .addHeader("Authorization", "Bearer $bareToken")
        } else {
            val encoded = URLEncoder.encode(credential, "UTF-8")
            Request.Builder()
                .url("$baseWsUrl?key=$encoded")
                .addHeader("x-goog-api-key", credential)
        }

        webSocket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val setupObj = JSONObject()
                    .put("model", "models/$modelId")
                    .put(
                        "generationConfig",
                        JSONObject()
                            .put("responseModalities", JSONArray().put("AUDIO"))
                            .put(
                                "speechConfig",
                                JSONObject().put(
                                    "voiceConfig",
                                    JSONObject().put(
                                        "prebuiltVoiceConfig",
                                        JSONObject().put("voiceName", "Aoede")
                                    )
                                )
                            )
                    )
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", ModelRingConfig.SYSTEM_PROMPT))
                        )
                    )
                    .put("tools", ModelRingConfig.buildToolDeclarationsJson())
                    .put(
                        "sessionResumption",
                        JSONObject().apply {
                            resumeHandle?.let { put("handle", it) }
                        }
                    )
                    .put("outputAudioTranscription", JSONObject())

                ws.send(JSONObject().put("setup", setupObj).toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                failureMsg = reason.ifBlank { "Live closed ($code)" }
                authFail = reason.contains("API key", ignoreCase = true) ||
                    reason.contains("UNAUTHENTICATED", ignoreCase = true) ||
                    reason.contains("PERMISSION_DENIED", ignoreCase = true)
                isSetupComplete = false
                setup.complete(false)
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = turnTranscript.toString().trim(),
                        receivedNativeAudio = receivedAudioInTurn,
                        toolCalls = pendingToolCalls.toList(),
                        httpCode = if (authFail) 401 else code,
                        isAuthFailure = authFail,
                        isRetryableError = true,
                        errorMessage = failureMsg
                    )
                )
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                failureCode = response?.code ?: 0
                authFail = failureCode == 401 || failureCode == 403
                failureMsg = response?.message?.takeIf { it.isNotBlank() }
                    ?: t.message
                    ?: "WebSocket failure ($failureCode)"
                isSetupComplete = false
                setup.complete(false)
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = "",
                        receivedNativeAudio = false,
                        toolCalls = emptyList(),
                        httpCode = failureCode,
                        isAuthFailure = authFail,
                        isRetryableError = true,
                        errorMessage = failureMsg
                    )
                )
            }
        })

        val ok = withTimeoutOrNull(4_500L) { setup.await() } == true
        if (!ok) {
            close()
        }
        return ConnectAttempt(
            connected = ok,
            httpCode = failureCode,
            isAuthFailure = authFail,
            errorMessage = failureMsg
        )
    }

    private fun handleServerMessage(raw: String) {
        try {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                isSetupComplete = true
                setupDeferred?.complete(true)
                return
            }

            json.optJSONObject("sessionResumptionUpdate")?.let { update ->
                val handle = update.optString("newHandle", "")
                if (handle.isNotBlank()) {
                    resumeHandle = handle
                }
            }

            json.optJSONObject("toolCall")?.let { toolCallObj ->
                val calls = toolCallObj.optJSONArray("functionCalls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val c = calls.optJSONObject(i) ?: continue
                        pendingToolCalls.add(
                            ToolCallRequest(
                                id = c.optString("id", "call_$i"),
                                name = c.optString("name", ""),
                                args = c.optJSONObject("args") ?: JSONObject()
                            )
                        )
                    }
                    if (pendingToolCalls.isNotEmpty()) {
                        turnDeferred?.complete(
                            LiveTurnResult(
                                transcript = turnTranscript.toString().trim(),
                                receivedNativeAudio = receivedAudioInTurn,
                                toolCalls = pendingToolCalls.toList()
                            )
                        )
                        return
                    }
                }
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            serverContent.optJSONObject("outputTranscription")?.optString("text")?.let { delta ->
                if (delta.isNotEmpty()) {
                    turnTranscript.append(delta)
                    onTranscriptDelta?.invoke(turnTranscript.toString().trim())
                }
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val text = part.optString("text", "")
                    if (text.isNotEmpty()) {
                        turnTranscript.append(text)
                        onTranscriptDelta?.invoke(turnTranscript.toString().trim())
                    }
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val mime = inlineData.optString("mimeType", "")
                        val data = inlineData.optString("data", "")
                        if (mime.startsWith("audio/") && data.isNotEmpty()) {
                            receivedAudioInTurn = true
                            onAudioChunk?.invoke(data)
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = turnTranscript.toString().trim(),
                        receivedNativeAudio = receivedAudioInTurn,
                        toolCalls = pendingToolCalls.toList()
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore malformed incremental frames
        }
    }

    fun isConnected(): Boolean = webSocket != null && isSetupComplete

    fun close() {
        isSetupComplete = false
        try {
            webSocket?.close(1000, "Session reset")
        } catch (_: Exception) {
        }
        webSocket = null
    }
}
