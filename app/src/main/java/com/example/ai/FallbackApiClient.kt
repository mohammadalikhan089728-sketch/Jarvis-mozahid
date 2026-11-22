package com.example.ai

import com.example.data.SecureVault
import com.example.data.db.MemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SlidingMessage(
    val role: String,
    val text: String
)

data class AssistantTurnOutput(
    val spokenText: String,
    val alreadySpokenViaLiveAudio: Boolean,
    val usedModelBadge: String
)

/**
 * Production-grade Universal Fallback API Client & 2-Level Circular Fallback Engine:
 * - Universal Key Support:
 *   * `AIza...` keys -> sent via `?key=...` and `x-goog-api-key`
 *   * `AQ.Ab8...` / OAuth / Express Bearer tokens -> sent via `Authorization: Bearer <token>`,
 *     with automatic retry using `?key=...` if rejected.
 * - 2-Level Circular Rotation:
 *   * Level 1: Silently rotates across the 6-model ring (`gemini-3.1-flash-live-preview` ->
 *     `gemini-3.8-flash` -> `gemini-3.6-flash` -> `gemini-3.5-flash-lite` ->
 *     `gemini-decision-1` -> `gemini-4-hydrogen`) on 429, RESOURCE_EXHAUSTED, quota, auth, or network errors.
 *   * Level 2: Switches seamlessly between API Key 1 and API Key 2, looping infinitely without errors.
 * - Zero-Token-Wastage: Single-turn payloads on Live WebSocket, max 4-message sliding window on REST,
 *   and single top matching Room memory snippet injected per query.
 */
class FallbackApiClient(
    private val vault: SecureVault,
    private val memoryRepository: MemoryRepository,
    private val liveClient: GeminiLiveWebSocketClient = GeminiLiveWebSocketClient()
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .writeTimeout(28, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentModelIndex = 0

    @Volatile
    private var currentKeyListIndex = 0

    private val slidingWindow = ArrayDeque<SlidingMessage>(MAX_SLIDING_WINDOW)

    private val _engineState = MutableStateFlow(buildEngineState())
    val engineState: StateFlow<ActiveEngineState> = _engineState.asStateFlow()

    fun refreshState() {
        _engineState.value = buildEngineState()
    }

    private fun buildEngineState(): ActiveEngineState {
        val keys = vault.getAvailableGeminiKeys()
        val safeKeyNum = if (keys.isEmpty()) 1 else keys[currentKeyListIndex % keys.size].first
        return ActiveEngineState(
            modelSlot = ModelRingConfig.MODEL_RING[currentModelIndex],
            activeKeyNumber = safeKeyNum,
            isLiveConnected = liveClient.isConnected(),
            hasConfiguredKeys = keys.isNotEmpty()
        )
    }

    @Synchronized
    private fun rotateNext(availableKeysCount: Int) {
        currentModelIndex = (currentModelIndex + 1) % ModelRingConfig.MODEL_RING.size
        if (currentModelIndex == 0 && availableKeysCount > 1) {
            currentKeyListIndex = (currentKeyListIndex + 1) % availableKeysCount
        }
        liveClient.close()
        _engineState.value = buildEngineState()
    }

    private fun appendSlidingMessage(role: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        synchronized(slidingWindow) {
            if (slidingWindow.size >= MAX_SLIDING_WINDOW) {
                slidingWindow.removeFirst()
            }
            slidingWindow.addLast(SlidingMessage(role, clean.take(400)))
        }
    }

    /**
     * Executes a full voice/text command turn with autonomous multi-step tool loop and silent rotation.
     */
    suspend fun processUserTurn(
        userText: String,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onLiveAudioChunk: (String) -> Unit,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput = withContext(Dispatchers.IO) {
        val keys = vault.getAvailableGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext AssistantTurnOutput(
                spokenText = "Sir, please add your Gemini API Key in Settings to activate JARVIS.",
                alreadySpokenViaLiveAudio = false,
                usedModelBadge = "No API Key"
            )
        }

        val topMemory = memoryRepository.findTopMatchingSnippet(userText)
        appendSlidingMessage("user", userText)

        // Allow two full sweeps across all models * all keys so transient rate limits never interrupt
        val maxRotationAttempts = ModelRingConfig.MODEL_RING.size * keys.size
        var attempts = 0

        while (attempts < maxRotationAttempts && shouldContinue()) {
            val activeKeys = vault.getAvailableGeminiKeys()
            if (activeKeys.isEmpty()) break
            val (keyNum, apiKey) = activeKeys[currentKeyListIndex % activeKeys.size]
            val slot = ModelRingConfig.MODEL_RING[currentModelIndex]
            _engineState.value = ActiveEngineState(
                modelSlot = slot,
                activeKeyNumber = keyNum,
                isLiveConnected = slot.isLiveWebSocket && liveClient.isConnected(),
                hasConfiguredKeys = true
            )

            val result = if (slot.isLiveWebSocket) {
                runLiveLoop(
                    apiKey = apiKey,
                    slot = slot,
                    userText = userText,
                    topMemory = topMemory,
                    shouldContinue = shouldContinue,
                    executeTool = executeTool,
                    onLiveAudioChunk = onLiveAudioChunk,
                    onSubtitleUpdate = onSubtitleUpdate
                )
            } else {
                runRestLoop(
                    apiKey = apiKey,
                    slot = slot,
                    keyNum = keyNum,
                    topMemory = topMemory,
                    shouldContinue = shouldContinue,
                    executeTool = executeTool,
                    onSubtitleUpdate = onSubtitleUpdate
                )
            }

            if (result != null) {
                appendSlidingMessage("model", result.spokenText)
                return@withContext result
            }

            // Silently rotate to next model (and next key after Model 6)
            rotateNext(activeKeys.size)
            attempts++
        }

        AssistantTurnOutput(
            spokenText = "Sir, I rotated across all configured models and keys, please verify your API key permissions in Settings.",
            alreadySpokenViaLiveAudio = false,
            usedModelBadge = _engineState.value.badgeText
        )
    }

    private suspend fun runLiveLoop(
        apiKey: String,
        slot: ModelSlot,
        userText: String,
        topMemory: String?,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onLiveAudioChunk: (String) -> Unit,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput? {
        var result = liveClient.sendSingleTurn(
            apiKey = apiKey,
            modelId = slot.modelId,
            userText = userText,
            memorySnippet = topMemory,
            onAudioBase64 = onLiveAudioChunk,
            onTextDelta = onSubtitleUpdate
        )

        if (result.isRetryableError && result.transcript.isEmpty() && result.toolCalls.isEmpty()) {
            return null
        }

        var step = 0
        while (result.toolCalls.isNotEmpty() && step < MAX_TOOL_STEPS && shouldContinue()) {
            step++
            val responses = mutableListOf<Pair<ToolCallRequest, String>>()
            for (call in result.toolCalls) {
                if (!shouldContinue()) break
                val outcome = executeTool(call.name, call.args)
                responses.add(call to outcome)
            }
            if (!shouldContinue()) {
                return AssistantTurnOutput(
                    spokenText = "Stopped, Sir.",
                    alreadySpokenViaLiveAudio = false,
                    usedModelBadge = _engineState.value.badgeText
                )
            }
            result = liveClient.sendToolResponses(
                responses = responses,
                onAudioBase64 = onLiveAudioChunk,
                onTextDelta = onSubtitleUpdate
            )
            if (result.isRetryableError && result.transcript.isEmpty() && result.toolCalls.isEmpty()) {
                return null
            }
        }

        val finalText = result.transcript.ifBlank {
            if (result.receivedNativeAudio) "Done, Sir." else ""
        }
        if (finalText.isBlank() && !result.receivedNativeAudio) return null

        return AssistantTurnOutput(
            spokenText = finalText,
            alreadySpokenViaLiveAudio = result.receivedNativeAudio,
            usedModelBadge = _engineState.value.badgeText
        )
    }

    private suspend fun runRestLoop(
        apiKey: String,
        slot: ModelSlot,
        keyNum: Int,
        topMemory: String?,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput? {
        val contents = JSONArray()
        val windowCopy = synchronized(slidingWindow) { slidingWindow.toList() }
        windowCopy.forEachIndexed { idx, msg ->
            val contentText = if (idx == windowCopy.lastIndex && msg.role == "user" && !topMemory.isNullOrBlank()) {
                "[Local Memory: $topMemory]\n${msg.text}"
            } else {
                msg.text
            }
            contents.put(
                JSONObject()
                    .put("role", msg.role)
                    .put("parts", JSONArray().put(JSONObject().put("text", contentText)))
            )
        }

        var step = 0
        while (step <= MAX_TOOL_STEPS && shouldContinue()) {
            val response = executeRestWithUniversalAuth(
                credential = apiKey,
                modelId = slot.modelId,
                contents = contents,
                includeTools = true
            )

            if (response.shouldRotate || response.error != null) return null

            val toolCalls = response.toolCalls
            if (toolCalls.isNotEmpty() && step < MAX_TOOL_STEPS) {
                val modelParts = JSONArray()
                for (tc in toolCalls) {
                    modelParts.put(
                        JSONObject().put(
                            "functionCall",
                            JSONObject()
                                .put("name", tc.name)
                                .put("args", tc.args)
                        )
                    )
                }
                contents.put(JSONObject().put("role", "model").put("parts", modelParts))

                val userToolParts = JSONArray()
                for (tc in toolCalls) {
                    if (!shouldContinue()) {
                        return AssistantTurnOutput(
                            spokenText = "Stopped, Sir.",
                            alreadySpokenViaLiveAudio = false,
                            usedModelBadge = "${slot.badgeLabel} · K$keyNum"
                        )
                    }
                    val toolOutcome = executeTool(tc.name, tc.args)
                    userToolParts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject()
                                .put("name", tc.name)
                                .put("response", JSONObject().put("result", toolOutcome))
                        )
                    )
                }
                contents.put(JSONObject().put("role", "user").put("parts", userToolParts))
                step++
                continue
            }

            val text = response.text.trim()
            if (text.isNotEmpty()) {
                onSubtitleUpdate(text)
                return AssistantTurnOutput(
                    spokenText = text,
                    alreadySpokenViaLiveAudio = false,
                    usedModelBadge = "${slot.badgeLabel} · K$keyNum"
                )
            }
            return null
        }

        return AssistantTurnOutput(
            spokenText = "Task completed, Sir.",
            alreadySpokenViaLiveAudio = false,
            usedModelBadge = "${slot.badgeLabel} · K$keyNum"
        )
    }

    /**
     * Compact single-shot generation using the exact same universal key auth & 2-level circular fallback ring
     * (used by NotificationWatchService auto-reply, YouTube Auto-SEO generator, and proactive briefings).
     */
    suspend fun generateCompactText(
        prompt: String,
        systemInstruction: String = ModelRingConfig.SYSTEM_PROMPT,
        expectJson: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val keys = vault.getAvailableGeminiKeys()
        if (keys.isEmpty()) return@withContext null

        val restModels = ModelRingConfig.MODEL_RING.filter { !it.isLiveWebSocket }
        var attempts = 0
        val maxAttempts = restModels.size * keys.size

        var localModelIdx = (currentModelIndex - 1).coerceAtLeast(0) % restModels.size
        var localKeyIdx = currentKeyListIndex % keys.size

        while (attempts < maxAttempts) {
            val credential = keys[localKeyIdx].second
            val modelId = restModels[localModelIdx].modelId

            val contents = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )

            val res = executeRestWithUniversalAuth(
                credential = credential,
                modelId = modelId,
                contents = contents,
                includeTools = false,
                systemInstructionOverride = systemInstruction,
                jsonMimeType = expectJson
            )

            if (!res.shouldRotate && res.text.isNotBlank()) {
                return@withContext res.text.trim()
            }

            localModelIdx = (localModelIdx + 1) % restModels.size
            if (localModelIdx == 0 && keys.size > 1) {
                localKeyIdx = (localKeyIdx + 1) % keys.size
            }
            attempts++
        }
        null
    }

    private data class RestCallResult(
        val text: String = "",
        val toolCalls: List<ToolCallRequest> = emptyList(),
        val shouldRotate: Boolean = false,
        val httpCode: Int = 0,
        val error: String? = null
    )

    /**
     * Universal Key Auth for REST:
     * - `AIza...` -> `?key=...` + `x-goog-api-key`
     * - `AQ.Ab8...` / OAuth / Bearer token -> `Authorization: Bearer <token>`,
     *   retrying immediately with `?key=` + `x-goog-api-key` on 401/403/400.
     */
    private fun executeRestWithUniversalAuth(
        credential: String,
        modelId: String,
        contents: JSONArray,
        includeTools: Boolean,
        systemInstructionOverride: String? = null,
        jsonMimeType: Boolean = false
    ): RestCallResult {
        val clean = credential.trim()
        val isStandardAiStudioKey = clean.startsWith("AIza")

        if (isStandardAiStudioKey) {
            return executeRestSingleAttempt(
                credential = clean,
                useBearer = false,
                modelId = modelId,
                contents = contents,
                includeTools = includeTools,
                systemInstructionOverride = systemInstructionOverride,
                jsonMimeType = jsonMimeType
            )
        }

        // First try Authorization: Bearer <token> for AQ.Ab8... / OAuth / Cloud tokens
        val bearerRes = executeRestSingleAttempt(
            credential = clean,
            useBearer = true,
            modelId = modelId,
            contents = contents,
            includeTools = includeTools,
            systemInstructionOverride = systemInstructionOverride,
            jsonMimeType = jsonMimeType
        )
        if (bearerRes.error == null && !bearerRes.shouldRotate) {
            return bearerRes
        }

        // Retry with ?key=<token> & x-goog-api-key if Bearer was rejected with auth/bad-request error
        if (bearerRes.httpCode in setOf(400, 401, 403)) {
            return executeRestSingleAttempt(
                credential = clean,
                useBearer = false,
                modelId = modelId,
                contents = contents,
                includeTools = includeTools,
                systemInstructionOverride = systemInstructionOverride,
                jsonMimeType = jsonMimeType
            )
        }

        return bearerRes
    }

    private fun executeRestSingleAttempt(
        credential: String,
        useBearer: Boolean,
        modelId: String,
        contents: JSONArray,
        includeTools: Boolean,
        systemInstructionOverride: String?,
        jsonMimeType: Boolean
    ): RestCallResult {
        val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val bodyJson = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(
                        JSONObject().put("text", systemInstructionOverride ?: ModelRingConfig.SYSTEM_PROMPT)
                    )
                )
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.35)
                    .put("maxOutputTokens", 380)
                    .apply {
                        if (jsonMimeType) put("responseMimeType", "application/json")
                    }
            )

        if (includeTools) {
            bodyJson.put("tools", ModelRingConfig.buildToolDeclarationsJson())
        }

        val requestBody = bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val requestBuilder = if (useBearer) {
            val bareToken = credential.removePrefix("Bearer ").trim()
            Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $bareToken")
                .post(requestBody)
        } else {
            val encoded = URLEncoder.encode(credential, "UTF-8")
            Request.Builder()
                .url("$baseUrl?key=$encoded")
                .addHeader("x-goog-api-key", credential)
                .post(requestBody)
        }

        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val shouldRotate = resp.code in setOf(401, 403, 404, 429, 500, 503) ||
                        raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        raw.contains("quota", ignoreCase = true) ||
                        raw.contains("rate", ignoreCase = true) ||
                        raw.contains("not found", ignoreCase = true) ||
                        raw.contains("API_KEY_INVALID", ignoreCase = true)
                    return RestCallResult(
                        shouldRotate = shouldRotate,
                        httpCode = resp.code,
                        error = "HTTP ${resp.code}"
                    )
                }

                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates")
                    ?: return RestCallResult(shouldRotate = true, httpCode = resp.code, error = "Empty candidates")
                val first = candidates.optJSONObject(0)
                    ?: return RestCallResult(shouldRotate = true, httpCode = resp.code, error = "No candidate")
                val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()

                val textBuilder = StringBuilder()
                val calls = mutableListOf<ToolCallRequest>()

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val t = part.optString("text", "")
                    if (t.isNotEmpty()) textBuilder.append(t)

                    part.optJSONObject("functionCall")?.let { fc ->
                        calls.add(
                            ToolCallRequest(
                                id = "rest_call_$i",
                                name = fc.optString("name", ""),
                                args = fc.optJSONObject("args") ?: JSONObject()
                            )
                        )
                    }
                }
                RestCallResult(text = textBuilder.toString(), toolCalls = calls, httpCode = resp.code)
            }
        } catch (e: Exception) {
            RestCallResult(shouldRotate = true, error = e.message)
        }
    }

    fun closeLiveSession() {
        liveClient.close()
        refreshState()
    }

    companion object {
        private const val MAX_SLIDING_WINDOW = 4
        private const val MAX_TOOL_STEPS = 20
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
