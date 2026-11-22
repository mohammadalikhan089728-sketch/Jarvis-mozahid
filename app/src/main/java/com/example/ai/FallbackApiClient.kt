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
import java.util.concurrent.ConcurrentHashMap
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

private data class DiscoveredKeyModels(
    val bidiModels: List<String>,
    val restModels: List<String>,
    val fetchedAtMs: Long = System.currentTimeMillis()
)

/**
 * Production-grade 2-Level Circular Fallback + Dynamic Model Discovery Engine:
 * - Active Model Priority Ring (zero deprecated models):
 *   1. gemini-3.1-flash-live-preview (Live Voice WebSocket BidiGenerateContent)
 *   2. gemini-3.8-flash
 *   3. gemini-3.6-flash
 *   4. gemini-3.5-flash-lite
 *   5. gemini-decision-1
 *   6. gemini-4-hydrogen
 * - Preferred Model Support:
 *   * `auto` -> Circular Auto-Rotate across all 6 active models and Key 1 <-> Key 2 on 429/503/404.
 *   * Specific model ID -> Locks onto user-selected model, while still auto-discovering active
 *     `/v1beta/models` equivalents if that ID returns 404 for the provided API key.
 * - Dynamic `/v1beta/models` Discovery:
 *   * Whenever a model returns 404, fetches `/v1beta/models` using the active key's Smart Auth
 *     (`AIza...` -> `?key=` + `x-goog-api-key`; `AQ.Ab8...` -> `Authorization: Bearer` with `?key=` retry)
 *     and auto-maps to the closest active `generateContent` or `bidiGenerateContent` model.
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

    @Volatile
    private var lastResolvedModelId: String? = null

    private val discoveredModelsCache = ConcurrentHashMap<Int, DiscoveredKeyModels>()
    private val slidingWindow = ArrayDeque<SlidingMessage>(MAX_SLIDING_WINDOW)

    private val _engineState = MutableStateFlow(buildEngineState())
    val engineState: StateFlow<ActiveEngineState> = _engineState.asStateFlow()

    fun refreshState() {
        _engineState.value = buildEngineState()
    }

    fun resetRotationToStart() {
        currentModelIndex = 0
        currentKeyListIndex = 0
        lastResolvedModelId = null
        discoveredModelsCache.clear()
        liveClient.close()
        refreshState()
    }

    private fun buildEngineState(): ActiveEngineState {
        val keys = vault.getAvailableGeminiKeys()
        val safeKeyNum = if (keys.isEmpty()) 1 else keys[currentKeyListIndex % keys.size].first
        val preferred = vault.getPreferredModelId()
        val isAuto = preferred == ModelRingConfig.MODEL_MODE_AUTO
        val lockedSlot = if (isAuto) null else ModelRingConfig.findSlotById(preferred)
        val activeSlot = lockedSlot ?: ModelRingConfig.MODEL_RING[currentModelIndex]
        return ActiveEngineState(
            modelSlot = activeSlot,
            activeKeyNumber = safeKeyNum,
            isLiveConnected = activeSlot.isLiveWebSocket && liveClient.isConnected(),
            hasConfiguredKeys = keys.isNotEmpty(),
            isAutoRotate = isAuto,
            resolvedModelId = lastResolvedModelId
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
     * Respects the user's Preferred Model and dynamically queries `/v1beta/models` if a model returns 404.
     */
    suspend fun testApiConnection(): ApiConnectionTestResult = withContext(Dispatchers.IO) {
        val keys = vault.getAvailableGeminiKeys()
        if (keys.isEmpty()) {
            return@withContext ApiConnectionTestResult(
                success = false,
                message = "No API key saved. Enter Key 1 or Key 2 first."
            )
        }

        val preferred = vault.getPreferredModelId()
        val lockedSlot = if (preferred == ModelRingConfig.MODEL_MODE_AUTO) {
            null
        } else {
            ModelRingConfig.findSlotById(preferred)
        }

        val restRing = if (lockedSlot != null && !lockedSlot.isLiveWebSocket) {
            listOf(lockedSlot)
        } else {
            ModelRingConfig.MODEL_RING.filter { !it.isLiveWebSocket }
        }

        var lastError = "Connection failed"

        for ((keyNum, rawKey) in keys) {
            val cleanKey = rawKey.trim()
            val triedModelIds = mutableSetOf<String>()
            var hitAuthError = false

            // 1. Try configured active models first
            for (slot in restRing) {
                triedModelIds.add(slot.modelId)
                val res = pingRestModel(cleanKey, slot.modelId)
                if (res.error == null && res.text.isNotBlank()) {
                    val ringIdx = ModelRingConfig.MODEL_RING.indexOfFirst { it.modelId == slot.modelId }
                    if (ringIdx >= 0) currentModelIndex = ringIdx
                    currentKeyListIndex = keys.indexOfFirst { it.first == keyNum }.coerceAtLeast(0)
                    lastResolvedModelId = slot.modelId
                    refreshState()
                    return@withContext ApiConnectionTestResult(
                        success = true,
                        message = "Connected (Model: ${slot.modelId} · K$keyNum)",
                        modelId = slot.modelId,
                        keyNumber = keyNum
                    )
                }

                lastError = formatDiagnosticError(res.httpCode, res.error)
                if (res.httpCode == 401 || res.httpCode == 403) {
                    hitAuthError = true
                    break
                }

                // On 404, immediately discover active models from /v1beta/models for this key
                if (res.httpCode == 404) {
                    val discovered = discoverActiveModelsForKey(cleanKey)
                    val mapped = pickClosestRestModel(slot.modelId, discovered.restModels, triedModelIds)
                    if (mapped != null) {
                        triedModelIds.add(mapped)
                        val mappedRes = pingRestModel(cleanKey, mapped)
                        if (mappedRes.error == null && mappedRes.text.isNotBlank()) {
                            currentKeyListIndex = keys.indexOfFirst { it.first == keyNum }.coerceAtLeast(0)
                            lastResolvedModelId = mapped
                            refreshState()
                            return@withContext ApiConnectionTestResult(
                                success = true,
                                message = "Connected (Model: $mapped · K$keyNum)",
                                modelId = mapped,
                                keyNumber = keyNum
                            )
                        }
                        lastError = formatDiagnosticError(mappedRes.httpCode, mappedRes.error)
                        if (mappedRes.httpCode == 401 || mappedRes.httpCode == 403) {
                            hitAuthError = true
                            break
                        }
                    }
                } else if (!res.shouldRotateModel) {
                    break
                }
            }

            // 2. If all static slots returned 404/429 and key auth is valid, try remaining discovered models
            if (!hitAuthError) {
                val discovered = discoverActiveModelsForKey(cleanKey)
                for (activeId in discovered.restModels) {
                    if (!triedModelIds.add(activeId)) continue
                    val res = pingRestModel(cleanKey, activeId)
                    if (res.error == null && res.text.isNotBlank()) {
                        currentKeyListIndex = keys.indexOfFirst { it.first == keyNum }.coerceAtLeast(0)
                        lastResolvedModelId = activeId
                        refreshState()
                        return@withContext ApiConnectionTestResult(
                            success = true,
                            message = "Connected (Model: $activeId · K$keyNum)",
                            modelId = activeId,
                            keyNumber = keyNum
                        )
                    }
                    lastError = formatDiagnosticError(res.httpCode, res.error)
                    if (res.httpCode == 401 || res.httpCode == 403) break
                    if (!res.shouldRotateModel) break
                }
            }
        }

        ApiConnectionTestResult(
            success = false,
            message = lastError
        )
    }

    private fun pingRestModel(cleanKey: String, modelId: String): RestCallResult {
        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Hi")))
        )
        return executeRestWithSmartAuth(
            credential = cleanKey,
            modelId = modelId,
            contents = contents,
            includeTools = false,
            systemInstructionOverride = "Reply with only 'Online, Sir.'",
            jsonMimeType = false
        )
    }

    /**
     * Processes a user turn with 2-Level Circular Fallback (or locked Preferred Model)
     * plus dynamic `/v1beta/models` 404 auto-mapping.
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
     * Direct 16kHz WAV audio fallback when Android SpeechRecognizer is unavailable on device.
     */
    suspend fun processAudioTurn(
        audioWavBase64: String,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onSubtitleUpdate: (String) -> Unit
    ): AssistantTurnOutput = processTurnInternal(
        userText = "Listen to Mozahid Sir's spoken voice command and reply or execute tools immediately.",
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

        val preferred = vault.getPreferredModelId()
        val isAutoRotate = preferred == ModelRingConfig.MODEL_MODE_AUTO
        val lockedSlot = if (isAutoRotate) null else ModelRingConfig.findSlotById(preferred)

        val ringSize = ModelRingConfig.MODEL_RING.size
        val keysTried = mutableSetOf<Int>()
        var lastHttpCode = 0
        var lastErrorMsg = "Unable to reach Gemini API"

        while (keysTried.size < keys.size && shouldContinue()) {
            val activeKeys = vault.getAvailableGeminiKeys()
            if (activeKeys.isEmpty()) break

            val keyPair = activeKeys[currentKeyListIndex % activeKeys.size]
            val keyNum = keyPair.first
            val apiKey = keyPair.second.trim()
            if (!keysTried.add(keyNum)) break

            val triedRestIdsOnKey = mutableSetOf<String>()
            val slotsToTryCount = if (lockedSlot != null) 1 else ringSize
            var slotAttempts = 0
            var keyAuthFailed = false

            while (slotAttempts < slotsToTryCount && shouldContinue()) {
                val slot = lockedSlot ?: ModelRingConfig.MODEL_RING[currentModelIndex]
                _engineState.value = ActiveEngineState(
                    modelSlot = slot,
                    activeKeyNumber = keyNum,
                    isLiveConnected = slot.isLiveWebSocket && liveClient.isConnected(),
                    hasConfiguredKeys = true,
                    isAutoRotate = isAutoRotate,
                    resolvedModelId = lastResolvedModelId
                )

                // Audio WAV fallback uses REST multimodal models directly
                if (slot.isLiveWebSocket && audioWavBase64 != null) {
                    if (isAutoRotate) {
                        advanceToNextModel(activeKeys.size)
                        slotAttempts++
                        continue
                    } else {
                        // Even when locked to Live model, route direct WAV fallback to active REST model
                        val fallbackSlot = ModelRingConfig.MODEL_RING.first { !it.isLiveWebSocket }
                        val restRes = runRestWithDynamicDiscovery(
                            apiKey = apiKey,
                            slot = fallbackSlot,
                            keyNum = keyNum,
                            topMemory = topMemory,
                            audioWavBase64 = audioWavBase64,
                            triedRestIdsOnKey = triedRestIdsOnKey,
                            shouldContinue = shouldContinue,
                            executeTool = executeTool,
                            onSubtitleUpdate = onSubtitleUpdate
                        )
                        if (restRes.output != null) {
                            appendSlidingMessage("model", restRes.output.spokenText)
                            return@withContext restRes.output
                        }
                        lastHttpCode = restRes.httpCode
                        lastErrorMsg = restRes.errorMessage ?: lastErrorMsg
                        if (restRes.isAuthFailure) keyAuthFailed = true
                        break
                    }
                }

                if (slot.isLiveWebSocket) {
                    val cachedBidi = discoveredModelsCache[apiKey.hashCode()]?.bidiModels.orEmpty()
                    var liveAttempt = runLiveLoop(
                        apiKey = apiKey,
                        slot = slot,
                        discoveredBidiModels = cachedBidi,
                        userText = userText,
                        topMemory = topMemory,
                        shouldContinue = shouldContinue,
                        executeTool = executeTool,
                        onLiveAudioChunk = onLiveAudioChunk,
                        onSubtitleUpdate = onSubtitleUpdate
                    )

                    // On Live 404/unavailable, dynamically query /v1beta/models for bidiGenerateContent models
                    if (liveAttempt.output == null && !liveAttempt.isAuthFailure && cachedBidi.isEmpty()) {
                        val discovered = discoverActiveModelsForKey(apiKey)
                        val newBidi = discovered.bidiModels.filterNot { it.equals(slot.modelId, ignoreCase = true) }
                        if (newBidi.isNotEmpty() && shouldContinue()) {
                            liveAttempt = runLiveLoop(
                                apiKey = apiKey,
                                slot = slot,
                                discoveredBidiModels = newBidi,
                                userText = userText,
                                topMemory = topMemory,
                                shouldContinue = shouldContinue,
                                executeTool = executeTool,
                                onLiveAudioChunk = onLiveAudioChunk,
                                onSubtitleUpdate = onSubtitleUpdate
                            )
                        }
                    }

                    if (liveAttempt.output != null) {
                        appendSlidingMessage("model", liveAttempt.output.spokenText)
                        return@withContext liveAttempt.output
                    }

                    lastHttpCode = liveAttempt.httpCode
                    lastErrorMsg = liveAttempt.errorMessage ?: lastErrorMsg
                    if (liveAttempt.isAuthFailure) {
                        keyAuthFailed = true
                        break
                    }

                    if (isAutoRotate) {
                        advanceToNextModel(activeKeys.size)
                        slotAttempts++
                    } else {
                        // Locked to Live model, but Live WebSocket failed: fall back cleanly to discovered REST if needed
                        break
                    }
                } else {
                    val restAttempt = runRestWithDynamicDiscovery(
                        apiKey = apiKey,
                        slot = slot,
                        keyNum = keyNum,
                        topMemory = topMemory,
                        audioWavBase64 = audioWavBase64,
                        triedRestIdsOnKey = triedRestIdsOnKey,
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

                    if (restAttempt.isAuthFailure) {
                        keyAuthFailed = true
                        break
                    }
                    if (!restAttempt.shouldRotateModel) {
                        val diag = formatDiagnosticError(lastHttpCode, lastErrorMsg)
                        return@withContext AssistantTurnOutput(
                            spokenText = diag,
                            alreadySpokenViaLiveAudio = false,
                            usedModelBadge = _engineState.value.badgeText,
                            isError = true
                        )
                    }
                    if (isAutoRotate) {
                        advanceToNextModel(activeKeys.size)
                    }
                    slotAttempts++
                }
            }

            // If static slots returned 404/429 and auth succeeded, try any remaining dynamically discovered active REST models on this key
            if (!keyAuthFailed && shouldContinue()) {
                val discovered = discoverActiveModelsForKey(apiKey)
                for (discoveredModelId in discovered.restModels) {
                    if (!triedRestIdsOnKey.add(discoveredModelId)) continue
                    val dynamicSlot = ModelSlot(
                        modelId = discoveredModelId,
                        badgeLabel = lockedSlot?.badgeLabel ?: discoveredModelId.removePrefix("gemini-"),
                        isLiveWebSocket = false
                    )
                    val attempt = runRestLoop(
                        apiKey = apiKey,
                        slot = dynamicSlot,
                        actualModelId = discoveredModelId,
                        keyNum = keyNum,
                        topMemory = topMemory,
                        audioWavBase64 = audioWavBase64,
                        shouldContinue = shouldContinue,
                        executeTool = executeTool,
                        onSubtitleUpdate = onSubtitleUpdate
                    )
                    if (attempt.output != null) {
                        lastResolvedModelId = discoveredModelId
                        _engineState.value = buildEngineState()
                        appendSlidingMessage("model", attempt.output.spokenText)
                        return@withContext attempt.output
                    }
                    lastHttpCode = attempt.httpCode
                    lastErrorMsg = attempt.errorMessage ?: lastErrorMsg
                    if (attempt.isAuthFailure || !attempt.shouldRotateModel) break
                }
            }

            // Switch to Key 2 if available
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

    private suspend fun runRestWithDynamicDiscovery(
        apiKey: String,
        slot: ModelSlot,
        keyNum: Int,
        topMemory: String?,
        audioWavBase64: String?,
        triedRestIdsOnKey: MutableSet<String>,
        shouldContinue: () -> Boolean,
        executeTool: suspend (name: String, args: JSONObject) -> String,
        onSubtitleUpdate: (String) -> Unit
    ): LoopOutcome {
        triedRestIdsOnKey.add(slot.modelId)
        val primary = runRestLoop(
            apiKey = apiKey,
            slot = slot,
            actualModelId = slot.modelId,
            keyNum = keyNum,
            topMemory = topMemory,
            audioWavBase64 = audioWavBase64,
            shouldContinue = shouldContinue,
            executeTool = executeTool,
            onSubtitleUpdate = onSubtitleUpdate
        )
        if (primary.output != null || primary.isAuthFailure) {
            if (primary.output != null) lastResolvedModelId = slot.modelId
            return primary
        }

        // Dynamic Model Discovery on 404: fetch /v1beta/models and auto-map to closest active model
        if (primary.httpCode == 404 && shouldContinue()) {
            val discovered = discoverActiveModelsForKey(apiKey)
            val mappedId = pickClosestRestModel(slot.modelId, discovered.restModels, triedRestIdsOnKey)
            if (mappedId != null) {
                triedRestIdsOnKey.add(mappedId)
                val mappedAttempt = runRestLoop(
                    apiKey = apiKey,
                    slot = slot,
                    actualModelId = mappedId,
                    keyNum = keyNum,
                    topMemory = topMemory,
                    audioWavBase64 = audioWavBase64,
                    shouldContinue = shouldContinue,
                    executeTool = executeTool,
                    onSubtitleUpdate = onSubtitleUpdate
                )
                if (mappedAttempt.output != null) {
                    lastResolvedModelId = mappedId
                    _engineState.value = buildEngineState()
                }
                return mappedAttempt
            }
        }

        return primary
    }

    /**
     * Dynamically fetches `/v1beta/models` for the given API key using Smart Auth
     * (`AIza...` vs `AQ.Ab8...` Bearer) and caches active `generateContent` & `bidiGenerateContent` models.
     */
    private fun discoverActiveModelsForKey(apiKey: String): DiscoveredKeyModels {
        val cacheKey = apiKey.hashCode()
        val cached = discoveredModelsCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.fetchedAtMs < DISCOVERY_CACHE_TTL_MS) {
            return cached
        }

        val clean = apiKey.trim()
        val rawBody = fetchModelsJsonWithSmartAuth(clean) ?: return DiscoveredKeyModels(emptyList(), emptyList())

        val bidi = mutableListOf<String>()
        val rest = mutableListOf<String>()
        try {
            val modelsArr = JSONObject(rawBody).optJSONArray("models") ?: JSONArray()
            for (i in 0 until modelsArr.length()) {
                val obj = modelsArr.optJSONObject(i) ?: continue
                val rawName = obj.optString("name", "").removePrefix("models/").trim()
                if (rawName.isEmpty()) continue
                val lower = rawName.lowercase()
                if (!lower.startsWith("gemini-")) continue
                // Exclude deprecated legacy 1.0/1.5/2.0 families and non-chat utility models
                if (lower.contains("-1.0") || lower.contains("-1.5") || lower.contains("-2.0")) continue
                if (lower.contains("embedding") || lower.contains("imagen") ||
                    lower.contains("veo") || lower.contains("aqa") || lower.contains("tts")
                ) continue

                val methodsArr = obj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                var supportsGenerate = false
                var supportsBidi = false
                for (m in 0 until methodsArr.length()) {
                    when (methodsArr.optString(m)) {
                        "generateContent" -> supportsGenerate = true
                        "bidiGenerateContent" -> supportsBidi = true
                    }
                }
                if (supportsBidi) bidi.add(rawName)
                if (supportsGenerate && !lower.contains("live")) rest.add(rawName)
            }
        } catch (_: Exception) {
            // Ignore malformed response
        }

        val sortedBidi = bidi.distinct().sortedByDescending { scoreDiscoveredModel(it) }
        val sortedRest = rest.distinct().sortedByDescending { scoreDiscoveredModel(it) }
        val result = DiscoveredKeyModels(bidiModels = sortedBidi, restModels = sortedRest)
        if (sortedBidi.isNotEmpty() || sortedRest.isNotEmpty()) {
            discoveredModelsCache[cacheKey] = result
        }
        return result
    }

    private fun fetchModelsJsonWithSmartAuth(credential: String): String? {
        val clean = credential.trim()
        if (clean.startsWith("AIza")) {
            return fetchModelsJsonOnce(clean, useBearer = false).second
        }
        val (code, body) = fetchModelsJsonOnce(clean, useBearer = true)
        if (body != null) return body
        if (code == 401 || code == 403) {
            return fetchModelsJsonOnce(clean, useBearer = false).second
        }
        return null
    }

    private fun fetchModelsJsonOnce(credential: String, useBearer: Boolean): Pair<Int, String?> {
        val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"
        val reqBuilder = if (useBearer) {
            val bare = credential.removePrefix("Bearer ").trim()
            Request.Builder()
                .url(baseUrl)
                .addHeader("Authorization", "Bearer $bare")
                .get()
        } else {
            val encoded = URLEncoder.encode(credential, "UTF-8")
            Request.Builder()
                .url("$baseUrl&key=$encoded")
                .addHeader("x-goog-api-key", credential)
                .get()
        }
        return try {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.code to resp.body?.string() else resp.code to null
            }
        } catch (_: Exception) {
            0 to null
        }
    }

    private fun pickClosestRestModel(
        requestedModelId: String,
        discoveredRestModels: List<String>,
        alreadyTried: Set<String>
    ): String? {
        val available = discoveredRestModels.filterNot { it in alreadyTried }
        if (available.isEmpty()) return null
        val reqLower = requestedModelId.lowercase()
        return available.maxByOrNull { candidate ->
            val candLower = candidate.lowercase()
            var score = scoreDiscoveredModel(candidate)
            if (reqLower.contains("lite") && candLower.contains("lite")) score += 40
            if (reqLower.contains("flash") && candLower.contains("flash")) score += 25
            if (reqLower.contains("pro") && candLower.contains("pro")) score += 25
            score
        }
    }

    private fun scoreDiscoveredModel(modelId: String): Int {
        val lower = modelId.lowercase()
        var score = 0
        when {
            lower.contains("gemini-4") -> score += 140
            lower.contains("gemini-3") -> score += 120
            lower.contains("gemini-2.5") -> score += 100
            else -> score += 50
        }
        if (lower.contains("flash")) score += 30
        if (lower.contains("lite")) score -= 5
        if (lower.contains("latest")) score += 10
        if (lower.contains("preview") || lower.contains("exp")) score -= 8
        return score
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
        discoveredBidiModels: List<String>,
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
            discoveredBidiModels = discoveredBidiModels,
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

        if (result.activeModelId.isNotBlank()) {
            lastResolvedModelId = result.activeModelId
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
        actualModelId: String,
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
                modelId = actualModelId,
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
                errorMessage = "Empty response from $actualModelId"
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
            val tried = mutableSetOf<String>()
            var authFailed = false

            for (slot in restModels) {
                tried.add(slot.modelId)
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
                if (res.httpCode == 401 || res.httpCode == 403) {
                    authFailed = true
                    break
                }
                if (res.httpCode == 404) {
                    val discovered = discoverActiveModelsForKey(cleanKey)
                    val mapped = pickClosestRestModel(slot.modelId, discovered.restModels, tried)
                    if (mapped != null) {
                        tried.add(mapped)
                        val mappedRes = executeRestWithSmartAuth(
                            credential = cleanKey,
                            modelId = mapped,
                            contents = contents,
                            includeTools = false,
                            systemInstructionOverride = systemInstruction,
                            jsonMimeType = expectJson
                        )
                        if (mappedRes.error == null && mappedRes.text.isNotBlank()) {
                            return@withContext mappedRes.text.trim()
                        }
                        if (mappedRes.httpCode == 401 || mappedRes.httpCode == 403) {
                            authFailed = true
                            break
                        }
                    }
                } else if (!res.shouldRotateModel) {
                    break
                }
            }

            if (!authFailed) {
                val discovered = discoverActiveModelsForKey(cleanKey)
                for (activeId in discovered.restModels) {
                    if (!tried.add(activeId)) continue
                    val contents = JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                    val res = executeRestWithSmartAuth(
                        credential = cleanKey,
                        modelId = activeId,
                        contents = contents,
                        includeTools = false,
                        systemInstructionOverride = systemInstruction,
                        jsonMimeType = expectJson
                    )
                    if (res.error == null && res.text.isNotBlank()) {
                        return@withContext res.text.trim()
                    }
                    if (res.httpCode == 401 || res.httpCode == 403 || !res.shouldRotateModel) break
                }
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
        val cleanModel = modelId.removePrefix("models/").trim()
        val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"
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
        private const val DISCOVERY_CACHE_TTL_MS = 10 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
