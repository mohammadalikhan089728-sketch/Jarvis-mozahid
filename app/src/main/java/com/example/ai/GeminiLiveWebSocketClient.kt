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
import java.util.concurrent.TimeUnit

data class LiveTurnResult(
    val transcript: String,
    val receivedNativeAudio: Boolean,
    val toolCalls: List<ToolCallRequest>,
    val isQuotaOrRateLimitError: Boolean = false,
    val errorMessage: String? = null
)

data class ToolCallRequest(
    val id: String,
    val name: String,
    val args: JSONObject
)

/**
 * Multimodal Gemini Live WebSocket client (BidiGenerateContent) for `gemini-3.1-flash-live-preview`.
 * Enforces Zero-Token-Wastage: sends single-turn `clientContent` payloads and preserves `resumeHandle`
 * across reconnects so full chat logs are never re-transmitted.
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
            val connected = connectAndSetup(apiKey, modelId)
            if (!connected.first) {
                return LiveTurnResult(
                    transcript = "",
                    receivedNativeAudio = false,
                    toolCalls = emptyList(),
                    isQuotaOrRateLimitError = connected.second,
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
            "[Local Memory Match: $memorySnippet]\n$userText"
        } else {
            userText
        }

        // Single-turn clientContent payload (zero history bloat)
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
                isQuotaOrRateLimitError = true,
                errorMessage = "Live WebSocket send failed"
            )
        }

        return withTimeoutOrNull(22_000L) { deferred.await() } ?: run {
            LiveTurnResult(
                transcript = turnTranscript.toString().trim(),
                receivedNativeAudio = receivedAudioInTurn,
                toolCalls = pendingToolCalls.toList(),
                isQuotaOrRateLimitError = turnTranscript.isEmpty() && pendingToolCalls.isEmpty(),
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
                isQuotaOrRateLimitError = true,
                errorMessage = "Tool response WebSocket closed"
            )
        }

        return withTimeoutOrNull(20_000L) { deferred.await() } ?: LiveTurnResult(
            transcript = turnTranscript.toString().trim(),
            receivedNativeAudio = receivedAudioInTurn,
            toolCalls = pendingToolCalls.toList(),
            isQuotaOrRateLimitError = false
        )
    }

    private suspend fun connectAndSetup(
        apiKey: String,
        modelId: String
    ): Triple<Boolean, Boolean, String?> {
        close()
        activeApiKey = apiKey
        isSetupComplete = false
        val setup = CompletableDeferred<Boolean>()
        setupDeferred = setup
        var failureIsQuota = true
        var failureMsg: String? = null

        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
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
                val quotaLike = code == 1008 || code == 1011 || code == 1013 ||
                    reason.contains("quota", ignoreCase = true) ||
                    reason.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    reason.contains("429") ||
                    reason.contains("not found", ignoreCase = true)
                failureIsQuota = quotaLike
                failureMsg = "Live closed ($code): $reason"
                isSetupComplete = false
                setup.complete(false)
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = turnTranscript.toString().trim(),
                        receivedNativeAudio = receivedAudioInTurn,
                        toolCalls = pendingToolCalls.toList(),
                        isQuotaOrRateLimitError = true,
                        errorMessage = failureMsg
                    )
                )
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                failureIsQuota = code == 429 || code == 404 || code == 403 || code == 503 || true
                failureMsg = t.message ?: "Live WebSocket failure ($code)"
                isSetupComplete = false
                setup.complete(false)
                turnDeferred?.complete(
                    LiveTurnResult(
                        transcript = "",
                        receivedNativeAudio = false,
                        toolCalls = emptyList(),
                        isQuotaOrRateLimitError = true,
                        errorMessage = failureMsg
                    )
                )
            }
        })

        val ok = withTimeoutOrNull(8_000L) { setup.await() } == true
        if (!ok) {
            close()
        }
        return Triple(ok, failureIsQuota, failureMsg)
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
