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
    val isRetryableError: Boolean = false,
    val errorMessage: String? = null
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val args: JSONObject
)

/**
 * Multimodal Gemini Live WebSocket client (BidiGenerateContent) for `gemini-3.1-flash-live-preview`.
 * Supports universal API credentials:
 * - Standard AI Studio keys (`AIza...`): `?key=...` + `x-goog-api-key`
 * - Cloud / OAuth / Express Bearer tokens (`AQ.Ab8...`, `ya29...`, etc.): `Authorization: Bearer <token>`
 *   with automatic retry using `?key=` if handshake rejects Bearer.
 * Enforces Zero-Token-Wastage via single-turn `clientContent` payloads and `resumeHandle` reconnects.
 */
class GeminiLiveWebSocketClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
) {
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var activeApiKey: String = ""

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
        modelId: String,
        userText: String,
        memorySnippet: String?,
        onAudioBase64: (String) -> Unit,
        onTextDelta: (String) -> Unit
    ): LiveTurnResult {
        onAudioChunk = onAudioBase64
        onTranscriptDelta = onTextDelta

        if (webSocket == null || !isSetupComplete || activeApiKey != apiKey) {
            val connected = connectWithUniversalAuth(apiKey, modelId)
            if (!connected.first) {
                return LiveTurnResult(
                    transcript = "",
                    receivedNativeAudio = false,
                    toolCalls = emptyList(),
                    isRetryableError = connected.second,
                    errorMessage = connected.third
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
                errorMessage = "Live WebSocket send failed"
            )
        }

        return withTimeoutOrNull(22_000L) { deferred.await() } ?: LiveTurnResult(
            transcript = turnTranscript.toString().trim(),
            receivedNativeAudio = receivedAudioInTurn,
            toolCalls = pendingToolCalls.toList(),
            isRetryableError = turnTranscript.isEmpty() && pendingToolCalls.isEmpty(),
            errorMessage = "Live response timeout"
        )
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

        return withTimeoutOrNull(20_000L) { deferred.await() } ?: LiveTurnResult(
            transcript = turnTranscript.toString().trim(),
            receivedNativeAudio = receivedAudioInTurn,
            toolCalls = pendingToolCalls.toList(),
            isRetryableError = false
        )
    }

    private suspend fun connectWithUniversalAuth(
        credential: String,
        modelId: String
    ): Triple<Boolean, Boolean, String?> {
        val clean = credential.trim()
        val isStandardKey = clean.startsWith("AIza")
        if (isStandardKey) {
            return connectOnce(clean, modelId, useBearerHeader = false)
        }
        // For AQ.Ab8... / OAuth / Express Bearer tokens: try Authorization: Bearer first,
        // then automatically fall back to ?key= if the endpoint expects a query key.
        val bearerAttempt = connectOnce(clean, modelId, useBearerHeader = true)
        if (bearerAttempt.first) return bearerAttempt
        return connectOnce(clean, modelId, useBearerHeader = false)
    }

    private suspend fun connectOnce(
        credential: String,
        modelId: String,
        useBearerHeader: Boolean
    ): Triple<Boolean, Boolean, String?> {
        close()
        activeApiKey = credential
        isSetupComplete = false
        val setup = CompletableDeferred<Boolean>()
        setupDeferred = setup
        var failureMsg: String? = null

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
                failureMsg = "Live closed ($code): $reason"
                isSetupComplete = false
                setup.complete(false)
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = turnTranscript.toString().trim(),
                        receivedNativeAudio = receivedAudioInTurn,
                        toolCalls = pendingToolCalls.toList(),
                        isRetryableError = true,
                        errorMessage = failureMsg
                    )
                )
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                failureMsg = t.message ?: "Live WebSocket failure ($code)"
                isSetupComplete = false
                setup.complete(false)
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = "",
                        receivedNativeAudio = false,
                        toolCalls = emptyList(),
                        isRetryableError = true,
                        errorMessage = failureMsg
                    )
                )
            }
        })

        val ok = withTimeoutOrNull(8_000L) { setup.await() } == true
        if (!ok) {
            close()
        }
        return Triple(ok, true, failureMsg)
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
