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
    val usedModelBadge: String,
    val isError: Boolean = false
)

data class ApiConnectionTestResult(
    val success: Boolean,
    val message: String,
    val modelId: String = "",
    val keyNumber: Int = 1
)

/**
 * Production-grade Universal Fallback API Client & 2-Level Circular Fallback Engine:
 * - Uses ONLY valid Google Gemini API v1beta endpoints and real model IDs:
 *   1. gemini-3.1-flash-live-preview (Live WebSocket + live fallbacks)
 *   2. gemini-2.5-flash              (UI badge: 3.8-flash)
 *   3. gemini-2.5-flash-lite         (UI badge: 3.6-flash)
 *   4. gemini-2.0-flash              (UI badge: 3.5-flash-lite)
 *   5. gemini-2.0-flash-lite         (UI badge: decision-1)
 *   6. gemini-1.5-flash              (UI badge: 4-hydrogen)
 * - Smart Auth Header Handling:
 *   * Trims whitespace from saved keys.
 *   * `AIza...` -> `?key=...` AND `x-goog-api-key`
 *   * `AQ....` (OAuth / Cloud / Express) -> `Authorization: Bearer ...`, with 1 automatic retry via `?key=...` on 401/403.
 * - Zero Infinite Spinning:
 *   * Rotates to next model ONLY on 429 (Quota/Rate Limit), 503 (Overloaded), 404 (Model Not Found), or Live WS unavailable.
 *   * On 401/403 auth failure, jumps directly to API Key 2 (if configured) instead of spinning across 6 models on a dead key.
 *   * After 1 complete cycle across all configured keys, STOPS immediately and returns the real HTTP code + diagnostic message.
 */
class FallbackApiClient(
    private val vault: SecureVault,
    private val memoryRepository: MemoryRepository,
    private val liveClient: GeminiLiveWebSocketClient = GeminiLiveWebSocketClient()
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
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

    fun resetRotationToStart() {
        currentModelIndex = 0
        currentKeyListIndex = 0
        liveClient.close()
        refreshState()
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
    private fun advanceToNextModel(availableKeysCount: Int) {
        currentModelIndex = (currentModelIndex + 1) % ModelRingConfig.MODEL_RING.size
        if (currentModelIndex == 0 && availableKeysCount > 1) {
            currentKeyListIndex = (currentKeyListIndex + 1) % availableKeysCount
        }
        liveClient.close()
        _engineState.value = buildEngineState()
    }

    @Synchronized
    private fun advanceToNextKey(availableKeysCount: Int) {
        currentModelIndex = 0
        if (availableKeysCount > 1) {
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
     * Quick "Hi" connection test used by the Settings "Test API Connection" button.
     */
    suspend fun testApiConnection(): ApiConnectionTestResult = withContext(Dispatchers.IO) {
        val keys = vault.getAvailableGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext ApiConnectionTestResult(
                success = false,
                message = "No API key saved. Enter Key 1 or Key 2 first."
            )
        }

        val restModels = ModelRingConfig.MODEL_RING.filter { !it.isLiveWebSocket }
        var lastError = "Connection failed"

        for ((keyNum, rawKey) in keys) {
            val cleanKey = rawKey.trim()
            for (slot in restModels) {
                val contents = JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "Hi")))
                )
                val res = executeRestWithSmartAuth(
                    credential = cleanKey,
                    modelId = slot.modelId,
                    contents = contents,
                    includeTools = false,
                    systemInstructionOverride = "Reply with only 'Online, Sir.'",
                    jsonMimeType = false
                )

                if (res.error == null && res.text.isNotBlank()) {
                    val ringIdx = ModelRingConfig.MODEL_RING.indexOfFirst { it.modelId == slot.modelId }
                    if (ringIdx >= 0) {
                        currentModelIndex = ringIdx
                        currentKeyListIndex = keys.indexOfFirst { it.first == keyNum }.coerceAtLeast(0)
                        refreshState()
                    }
                    return@withContext ApiConnectionTestResult(
                        success = true,
                        message = "Connected (Model: ${slot.modelId} · K$keyNum)",
                        modelId = slot.modelId,
                        keyNumber = keyNum
                    )
                }

                lastError = formatDiagnosticError(res.httpCode, res.error)
                // If auth error (401/403), skip remaining models for this key and test next key
                if (res.httpCode == 401 || res.httpCode == 403) {
                    break
                }
                // Only continue rotating models on 429, 503, 404
                if (!res.shouldRotateModel) {
                    break
                }
            }
        }

        ApiConnectionTestResult(
            success = false,
            message = lastError
        )
    }

    /**
     * Processes a standard text (or SpeechRecognizer transcribed) user turn with bounded single-cycle
     * rotation across configured keys & valid models, returning exact HTTP diagnostics on failure.
     */
    suspend fun processUserTurn(
        userText: String,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onLiveAudioChunk: (String) -> Unit,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput = processTurnInternal(
        userText = userText,
        audioWavBase64 = null,
        shouldContinue = shouldContinue,
        executeTool = executeTool,
        onLiveAudioChunk = onLiveAudioChunk,
        onSubtitleUpdate = onSubtitleUpdate
    )

    /**
     * Multimodal audio fallback when Android SpeechRecognizer is unavailable on device:
     * sends recorded 16kHz WAV audio directly to Gemini REST models (`gemini-2.5-flash` ring).
     */
    suspend fun processAudioTurn(
        audioWavBase64: String,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput = processTurnInternal(
        userText = "Listen to the user's spoken audio and respond or execute their requested tools immediately.",
        audioWavBase64 = audioWavBase64,
        shouldContinue = shouldContinue,
        executeTool = executeTool,
        onLiveAudioChunk = {},
        onSubtitleUpdate = onSubtitleUpdate
    )

    private suspend fun processTurnInternal(
        userText: String,
        audioWavBase64: String?,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onLiveAudioChunk: (String) -> Unit,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput = withContext(Dispatchers.IO) {
        val keys = vault.getAvailableGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext AssistantTurnOutput(
                spokenText = "Sir, please add your Gemini API Key in Settings first.",
                alreadySpokenViaLiveAudio = false,
                usedModelBadge = "No API Key",
                isError = true
            )
        }

        val topMemory = if (audioWavBase64 == null) {
            memoryRepository.findTopMatchingSnippet(userText)
        } else {
            null
        }
        if (audioWavBase64 == null) {
            appendSlidingMessage("user", userText)
        }

        val ringSize = ModelRingConfig.MODEL_RING.size
        val keysTried = mutableSetOf<Int>()
        var lastHttpCode = 0
        var lastErrorMsg = "Unable to reach Gemini API"

        // Bounded cycle: visit each configured key at most once, and each model on that key at most once
        while (keysTried.size < keys.size && shouldContinue()) {
            val activeKeys = vault.getAvailableGeminiKeys()
            if (activeKeys.isEmpty()) break

            val keyPair = activeKeys[currentKeyListIndex % activeKeys.size]
            val keyNum = keyPair.first
            val apiKey = keyPair.second.trim()
            if (!keysTried.add(keyNum)) break

            var modelsTriedOnThisKey = 0
            while (modelsTriedOnThisKey < ringSize && shouldContinue()) {
                val slot = ModelRingConfig.MODEL_RING[currentModelIndex]
                _engineState.value = ActiveEngineState(
                    modelSlot = slot,
                    activeKeyNumber = keyNum,
                    isLiveConnected = slot.isLiveWebSocket && liveClient.isConnected(),
                    hasConfiguredKeys = true
                )

                // Audio WAV fallback uses REST multimodal models directly (skip Live WebSocket slot)
                if (slot.isLiveWebSocket && audioWavBase64 != null) {
                    advanceToNextModel(activeKeys.size)
                    modelsTriedOnThisKey++
                    continue
                }

                if (slot.isLiveWebSocket) {
                    val liveAttempt = runLiveLoop(
                        apiKey = apiKey,
                        slot = slot,
                        userText = userText,
                        topMemory = topMemory,
                        shouldContinue = shouldContinue,
                        executeTool = executeTool,
                        onLiveAudioChunk = onLiveAudioChunk,
                        onSubtitleUpdate = onSubtitleUpdate
                    )
                    if (liveAttempt.output != null) {
                        appendSlidingMessage("model", liveAttempt.output.spokenText)
                        return@withContext liveAttempt.output
                    }
                    lastHttpCode = liveAttempt.httpCode
                    lastErrorMsg = liveAttempt.errorMessage ?: lastErrorMsg

                    if (liveAttempt.isAuthFailure) {
                        // Auth failed on this key -> jump immediately to next key
                        break
                    }
                    advanceToNextModel(activeKeys.size)
                    modelsTriedOnThisKey++
                } else {
                    val restAttempt = runRestLoop(
                        apiKey = apiKey,
                        slot = slot,
                        keyNum = keyNum,
                        topMemory = topMemory,
                        audioWavBase64 = audioWavBase64,
                        shouldContinue = shouldContinue,
                        executeTool = executeTool,
                        onSubtitleUpdate = onSubtitleUpdate
                    )
                    if (restAttempt.output != null) {
                        appendSlidingMessage("model", restAttempt.output.spokenText)
                        return@withContext restAttempt.output
                    }
                    lastHttpCode = restAttempt.httpCode
                    lastErrorMsg = restAttempt.errorMessage ?: lastErrorMsg

                    if (restAttempt.httpCode == 401 || restAttempt.httpCode == 403) {
                        // Auth error: do NOT spin through remaining models on the same invalid key
                        break
                    }
                    if (!restAttempt.shouldRotateModel) {
                        // Non-rotatable error (e.g. 400 malformed request or offline): stop immediately with real diagnostic
                        val diag = formatDiagnosticError(lastHttpCode, lastErrorMsg)
                        return@withContext AssistantTurnOutput(
                            spokenText = diag,
                            alreadySpokenViaLiveAudio = false,
                            usedModelBadge = _engineState.value.badgeText,
                            isError = true
                        )
                    }
                    advanceToNextModel(activeKeys.size)
                    modelsTriedOnThisKey++
                }
            }

            // Finished all models (or hit auth error) on this key -> move to next API key if available
            if (keysTried.size < activeKeys.size) {
                advanceToNextKey(activeKeys.size)
            }
        }

        val finalDiagnostic = formatDiagnosticError(lastHttpCode, lastErrorMsg)
        AssistantTurnOutput(
            spokenText = finalDiagnostic,
            alreadySpokenViaLiveAudio = false,
            usedModelBadge = _engineState.value.badgeText,
            isError = true
        )
    }

    private data class LoopOutcome(
        val output: AssistantTurnOutput? = null,
        val httpCode: Int = 0,
        val isAuthFailure: Boolean = false,
        val shouldRotateModel: Boolean = false,
        val errorMessage: String? = null
    )

    private suspend fun runLiveLoop(
        apiKey: String,
        slot: ModelSlot,
        userText: String,
        topMemory: String?,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onLiveAudioChunk: (String) -> Unit,
        onSubtitleUpdate: (String) -> Unit
    ): LoopOutcome {
        var result = liveClient.sendSingleTurn(
            apiKey = apiKey,
            primaryModelId = slot.modelId,
            userText = userText,
            memorySnippet = topMemory,
            onAudioBase64 = onLiveAudioChunk,
            onTextDelta = onSubtitleUpdate
        )

        if (result.isRetryableError && result.transcript.isEmpty() && result.toolCalls.isEmpty()) {
            return LoopOutcome(
                httpCode = result.httpCode,
                isAuthFailure = result.isAuthFailure,
                shouldRotateModel = true,
                errorMessage = result.errorMessage
            )
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
                return LoopOutcome(
                    output = AssistantTurnOutput(
                        spokenText = "Stopped, Sir.",
                        alreadySpokenViaLiveAudio = false,
                        usedModelBadge = _engineState.value.badgeText
                    )
                )
            }
            result = liveClient.sendToolResponses(
                responses = responses,
                onAudioBase64 = onLiveAudioChunk,
                onTextDelta = onSubtitleUpdate
            )
            if (result.isRetryableError && result.transcript.isEmpty() && result.toolCalls.isEmpty()) {
                return LoopOutcome(
                    httpCode = result.httpCode,
                    isAuthFailure = result.isAuthFailure,
                    shouldRotateModel = true,
                    errorMessage = result.errorMessage
                )
            }
        }

        val finalText = result.transcript.ifBlank {
            if (result.receivedNativeAudio) "Done, Sir." else ""
        }
        if (finalText.isBlank() && !result.receivedNativeAudio) {
            return LoopOutcome(
                httpCode = result.httpCode,
                shouldRotateModel = true,
                errorMessage = result.errorMessage ?: "Empty Live response"
            )
        }

        return LoopOutcome(
            output = AssistantTurnOutput(
                spokenText = finalText,
                alreadySpokenViaLiveAudio = result.receivedNativeAudio,
                usedModelBadge = _engineState.value.badgeText
            )
        )
    }

    private suspend fun runRestLoop(
        apiKey: String,
        slot: ModelSlot,
        keyNum: Int,
        topMemory: String?,
        audioWavBase64: String?,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onSubtitleUpdate: (String) -> Unit
    ): LoopOutcome {
        val contents = JSONArray()
        if (audioWavBase64 != null) {
            val parts = JSONArray()
                .put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "audio/wav")
                            .put("data", audioWavBase64)
                    )
                )
                .put(JSONObject().put("text", "Respond concisely to Mozahid Sir's voice command."))
            contents.put(JSONObject().put("role", "user").put("parts", parts))
        } else {
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
        }

        var step = 0
        while (step <= MAX_TOOL_STEPS && shouldContinue()) {
            val response = executeRestWithSmartAuth(
                credential = apiKey,
                modelId = slot.modelId,
                contents = contents,
                includeTools = true
            )

            if (response.error != null) {
                return LoopOutcome(
                    httpCode = response.httpCode,
                    isAuthFailure = response.httpCode == 401 || response.httpCode == 403,
                    shouldRotateModel = response.shouldRotateModel,
                    errorMessage = response.error
                )
            }

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
                        return LoopOutcome(
                            output = AssistantTurnOutput(
                                spokenText = "Stopped, Sir.",
                                alreadySpokenViaLiveAudio = false,
                                usedModelBadge = "${slot.badgeLabel} · K$keyNum"
                            )
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
                return LoopOutcome(
                    output = AssistantTurnOutput(
                        spokenText = text,
                        alreadySpokenViaLiveAudio = false,
                        usedModelBadge = "${slot.badgeLabel} · K$keyNum"
                    )
                )
            }

            return LoopOutcome(
                httpCode = response.httpCode,
                shouldRotateModel = true,
                errorMessage = "Empty response from ${slot.modelId}"
            )
        }

        return LoopOutcome(
            output = AssistantTurnOutput(
                spokenText = "Task completed, Sir.",
                alreadySpokenViaLiveAudio = false,
                usedModelBadge = "${slot.badgeLabel} · K$keyNum"
            )
        )
    }

    /**
     * Compact single-shot generation used by NotificationWatchService auto-reply,
     * YouTube Auto-SEO generator, and YouTube analytics briefings.
     */
    suspend fun generateCompactText(
        prompt: String,
        systemInstruction: String = ModelRingConfig.SYSTEM_PROMPT,
        expectJson: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val keys = vault.getAvailableGeminiKeys()
        if (keys.isEmpty()) return@withContext null

        val restModels = ModelRingConfig.MODEL_RING.filter { !it.isLiveWebSocket }
        for ((_, rawKey) in keys) {
            val cleanKey = rawKey.trim()
            for (slot in restModels) {
                val contents = JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
                val res = executeRestWithSmartAuth(
                    credential = cleanKey,
                    modelId = slot.modelId,
                    contents = contents,
                    includeTools = false,
                    systemInstructionOverride = systemInstruction,
                    jsonMimeType = expectJson
                )
                if (res.error == null && res.text.isNotBlank()) {
                    return@withContext res.text.trim()
                }
                if (res.httpCode == 401 || res.httpCode == 403) break
                if (!res.shouldRotateModel) break
            }
        }
        null
    }

    private data class RestCallResult(
        val text: String = "",
        val toolCalls: List<ToolCallRequest> = emptyList(),
        val shouldRotateModel: Boolean = false,
        val httpCode: Int = 0,
        val error: String? = null
    )

    /**
     * Smart Auth Header Handling:
     * - Trims saved API keys.
     * - `AIza...` -> query param `?key=$apiKey` AND header `x-goog-api-key: $apiKey`.
     * - `AQ....` (or non-AIza Bearer token) -> header `Authorization: Bearer $apiKey`;
     *   if HTTP 401 or 403 occurs, automatically retries ONCE with `?key=$apiKey` + `x-goog-api-key`.
     */
    private fun executeRestWithSmartAuth(
        credential: String,
        modelId: String,
        contents: JSONArray,
        includeTools: Boolean,
        systemInstructionOverride: String? = null,
        jsonMimeType: Boolean = false
    ): RestCallResult {
        val clean = credential.trim()
        if (clean.startsWith("AIza")) {
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

        val bearerRes = executeRestSingleAttempt(
            credential = clean,
            useBearer = true,
            modelId = modelId,
            contents = contents,
            includeTools = includeTools,
            systemInstructionOverride = systemInstructionOverride,
            jsonMimeType = jsonMimeType
        )
        if (bearerRes.error == null) return bearerRes

        // Retry once with ?key= if AQ./Bearer token got 401/403
        if (bearerRes.httpCode == 401 || bearerRes.httpCode == 403) {
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
                    val shortMsg = extractShortApiError(raw, resp.message)
                    // Rotate ONLY on 429 (Quota/Rate Limit), 503 (Overloaded), or 404 (Model Not Found)
                    val shouldRotate = resp.code == 429 ||
                        resp.code == 503 ||
                        resp.code == 404 ||
                        raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true)
                    return RestCallResult(
                        shouldRotateModel = shouldRotate,
                        httpCode = resp.code,
                        error = shortMsg
                    )
                }

                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates")
                    ?: return RestCallResult(shouldRotateModel = false, httpCode = resp.code, error = "Empty candidates")
                val first = candidates.optJSONObject(0)
                    ?: return RestCallResult(shouldRotateModel = false, httpCode = resp.code, error = "No candidate")
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
            RestCallResult(
                shouldRotateModel = false,
                httpCode = 0,
                error = "Network error: ${e.localizedMessage ?: "Check internet connection"}"
            )
        }
    }

    private fun extractShortApiError(rawBody: String, fallbackStatus: String): String {
        return try {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            val msg = errObj?.optString("message", "")?.trim().orEmpty()
            val status = errObj?.optString("status", "")?.trim().orEmpty()
            when {
                msg.isNotEmpty() -> msg.substringBefore("\n").take(90)
                status.isNotEmpty() -> status
                fallbackStatus.isNotBlank() -> fallbackStatus
                else -> "Request rejected"
            }
        } catch (_: Exception) {
            fallbackStatus.ifBlank { "Request rejected" }.take(90)
        }
    }

    private fun formatDiagnosticError(httpCode: Int, message: String?): String {
        val cleanMsg = message?.trim().orEmpty()
        return when {
            httpCode == 401 || httpCode == 403 ->
                "API Error $httpCode: ${cleanMsg.ifBlank { "Invalid or unauthorized API key" }}"
            httpCode == 429 ->
                "API Error 429: Quota exceeded across all configured keys"
            httpCode > 0 ->
                "API Error $httpCode: ${cleanMsg.ifBlank { "Request failed" }}"
            cleanMsg.isNotBlank() -> cleanMsg
            else -> "Connection failed. Check internet and API key in Settings."
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
