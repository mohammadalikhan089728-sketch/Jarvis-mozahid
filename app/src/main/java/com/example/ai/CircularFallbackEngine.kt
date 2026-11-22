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
 * 2-Level Circular Fallback Engine (Zero Rate-Limit Errors):
 * - Level 1: Rotates silently across all 6 models in ModelRingConfig.MODEL_RING.
 * - Level 2: When all 6 models exhaust quota on API Key 1, silently switches to API Key 2
 *   and restarts from Model 1, looping infinitely.
 * - Zero-Token-Wastage: Only last 4 messages on REST calls; single-turn payloads on Live WebSocket;
 *   only top matching Room memory snippet injected per query.
 */
class CircularFallbackEngine(
    private val vault: SecureVault,
    private val memoryRepository: MemoryRepository,
    private val liveClient: GeminiLiveWebSocketClient = GeminiLiveWebSocketClient()
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
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

    private fun buildEngineState(): ActiveEngineState {
        val keys = vault.getAvailableGeminiKeys()
        val safeKeyNum = if (keys.isEmpty()) {
            1
        } else {
            keys[currentKeyListIndex % keys.size].first
        }
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
     * Executes a full user voice/text turn with autonomous multi-step tool loop and silent fallback.
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
                spokenText = "Sir, please configure at least one Gemini API Key in Settings to activate my neural link.",
                alreadySpokenViaLiveAudio = false,
                usedModelBadge = "No API Key"
            )
        }

        val topMemory = memoryRepository.findTopMatchingSnippet(userText)
        appendSlidingMessage("user", userText)

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

            if (slot.isLiveWebSocket) {
                val liveResult = runLiveLoop(
                    apiKey = apiKey,
                    slot = slot,
                    userText = userText,
                    topMemory = topMemory,
                    shouldContinue = shouldContinue,
                    executeTool = executeTool,
                    onLiveAudioChunk = onLiveAudioChunk,
                    onSubtitleUpdate = onSubtitleUpdate
                )
                if (liveResult != null) {
                    appendSlidingMessage("model", liveResult.spokenText)
                    return@withContext liveResult
                }
                // Silently rotate to next model (3.8-flash, etc.)
                rotateNext(activeKeys.size)
                attempts++
            } else {
                val restResult = runRestLoop(
                    apiKey = apiKey,
                    slot = slot,
                    keyNum = keyNum,
                    topMemory = topMemory,
                    shouldContinue = shouldContinue,
                    executeTool = executeTool,
                    onSubtitleUpdate = onSubtitleUpdate
                )
                if (restResult != null) {
                    appendSlidingMessage("model", restResult.spokenText)
                    return@withContext restResult
                }
                rotateNext(activeKeys.size)
                attempts++
            }
        }

        AssistantTurnOutput(
            spokenText = "Sir, all configured models are currently busy. Please verify your API keys in Settings.",
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

        if (result.isQuotaOrRateLimitError && result.transcript.isEmpty() && result.toolCalls.isEmpty()) {
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
                    spokenText = "Stopped as requested, Sir.",
                    alreadySpokenViaLiveAudio = false,
                    usedModelBadge = _engineState.value.badgeText
                )
            }
            result = liveClient.sendToolResponses(
                responses = responses,
                onAudioBase64 = onLiveAudioChunk,
                onTextDelta = onSubtitleUpdate
            )
            if (result.isQuotaOrRateLimitError && result.transcript.isEmpty() && result.toolCalls.isEmpty()) {
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
                "[Local Memory Match: $topMemory]\n${msg.text}"
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
            val response = executeRestGenerateContent(
                apiKey = apiKey,
                modelId = slot.modelId,
                contents = contents,
                includeTools = true
            )

            if (response.shouldRotate) return null
            if (response.error != null) return null

            val toolCalls = response.toolCalls
            if (toolCalls.isNotEmpty() && step < MAX_TOOL_STEPS) {
                // Append model functionCall turn
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

                // Execute tools immediately and append functionResponse turn
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
            spokenText = "Task loop finished, Sir.",
            alreadySpokenViaLiveAudio = false,
            usedModelBadge = "${slot.badgeLabel} · K$keyNum"
        )
    }

    /**
     * Lightweight single-shot completion using the exact same silent circular fallback ring
     * (used by Notification Auto-Reply and YouTube Auto-SEO Generator).
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
            val apiKey = keys[localKeyIdx].second
            val modelId = restModels[localModelIdx].modelId

            val contents = JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )

            val res = executeRestGenerateContent(
                apiKey = apiKey,
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
        val error: String? = null
    )

    private fun executeRestGenerateContent(
        apiKey: String,
        modelId: String,
        contents: JSONArray,
        includeTools: Boolean,
        systemInstructionOverride: String? = null,
        jsonMimeType: Boolean = false
    ): RestCallResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey"
        val bodyJson = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstructionOverride ?: ModelRingConfig.SYSTEM_PROMPT))
                )
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.4)
                    .put("maxOutputTokens", 350)
                    .apply {
                        if (jsonMimeType) put("responseMimeType", "application/json")
                    }
            )

        if (includeTools) {
            bodyJson.put("tools", ModelRingConfig.buildToolDeclarationsJson())
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            httpClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val isQuotaOrUnavailable = resp.code == 429 ||
                        resp.code == 404 ||
                        resp.code == 503 ||
                        raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        raw.contains("quota", ignoreCase = true) ||
                        raw.contains("rate", ignoreCase = true) ||
                        raw.contains("not found", ignoreCase = true)
                    return RestCallResult(
                        shouldRotate = isQuotaOrUnavailable,
                        error = "HTTP ${resp.code}"
                    )
                }

                val root = JSONObject(raw)
                val candidates = root.optJSONArray("candidates") ?: return RestCallResult(error = "Empty candidates")
                val first = candidates.optJSONObject(0) ?: return RestCallResult(error = "No candidate")
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
                RestCallResult(text = textBuilder.toString(), toolCalls = calls)
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
        private const val MAX_TOOL_STEPS = 12
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
