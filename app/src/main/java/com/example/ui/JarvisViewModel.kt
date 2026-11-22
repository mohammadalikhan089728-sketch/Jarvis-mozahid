package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApplication
import com.example.ai.ActiveEngineState
import com.example.ai.ApiConnectionTestResult
import com.example.ai.AssistantState
import com.example.alarm.JarvisAlarmScheduler
import com.example.assistant.JarvisKeepAliveService
import com.example.control.ExecutionController
import com.example.data.JarvisConfigSnapshot
import com.example.data.db.MemoryEntity
import com.example.youtube.YouTubeAnalyticsWorker
import com.example.youtube.YouTubeUploadProgressState
import com.example.youtube.YouTubeUploadProgressStore
import com.example.youtube.YouTubeUploadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JarvisApplication

    val config: StateFlow<JarvisConfigSnapshot> = app.vault.configFlow
    val engineState: StateFlow<ActiveEngineState> = app.fallbackApiClient.engineState
    val amplitude: StateFlow<Float> = app.voiceEngine.amplitude
    val uploadProgress: StateFlow<YouTubeUploadProgressState> = YouTubeUploadProgressStore.state

    val memories: StateFlow<List<MemoryEntity>> = app.memoryRepository.allMemories
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _assistantState = MutableStateFlow(AssistantState.IDLE)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _subtitle = MutableStateFlow("Tap the orb to speak, Sir.")
    val subtitle: StateFlow<String> = _subtitle.asStateFlow()

    private val _settingsBanner = MutableStateFlow<String?>(null)
    val settingsBanner: StateFlow<String?> = _settingsBanner.asStateFlow()

    private val _apiTestResult = MutableStateFlow<ApiConnectionTestResult?>(null)
    val apiTestResult: StateFlow<ApiConnectionTestResult?> = _apiTestResult.asStateFlow()

    private val _isTestingApi = MutableStateFlow(false)
    val isTestingApi: StateFlow<Boolean> = _isTestingApi.asStateFlow()

    private var activeJob: Job? = null

    init {
        viewModelScope.launch {
            ExecutionController.lastAutoReplyReport.collect { report ->
                if (!report.isNullOrBlank()) {
                    _subtitle.value = report
                }
            }
        }
    }

    fun onOrbTapped(hasAudioPermission: Boolean, requestAudioPermission: () -> Unit) {
        when (_assistantState.value) {
            AssistantState.LISTENING -> {
                app.voiceEngine.stopListening()
                _assistantState.value = AssistantState.IDLE
                _subtitle.value = "Standing by, Sir."
            }
            AssistantState.SPEAKING, AssistantState.THINKING, AssistantState.CONTROLLING -> {
                ExecutionController.requestStop()
                activeJob?.cancel()
                app.floatingPill.hide()
                app.voiceEngine.stopSpeaking()
                _assistantState.value = AssistantState.IDLE
                _subtitle.value = "Stopped, Sir."
            }
            AssistantState.IDLE -> {
                val micGrantedLive = hasAudioPermission || app.voiceEngine.hasMicrophonePermission()
                if (!micGrantedLive) {
                    requestAudioPermission()
                    return
                }
                startVoiceListening()
            }
        }
    }

    fun onMicPermissionDenied() {
        _assistantState.value = AssistantState.IDLE
        _subtitle.value = "Microphone permission is required for voice commands."
    }

    fun startVoiceListening() {
        if (!app.voiceEngine.hasMicrophonePermission()) {
            _assistantState.value = AssistantState.IDLE
            _subtitle.value = "Microphone permission is required for voice commands."
            return
        }
        _assistantState.value = AssistantState.LISTENING
        _subtitle.value = "Listening, Sir..."
        app.voiceEngine.startListening(
            onPartialResult = { partial ->
                _subtitle.value = partial
            },
            onFinalResult = { spoken ->
                _subtitle.value = spoken
                submitCommand(spoken)
            },
            onAudioWavCaptured = { wavBase64 ->
                submitAudioCommand(wavBase64)
            },
            onError = { errMsg ->
                _assistantState.value = AssistantState.IDLE
                if (errMsg.isNotBlank()) {
                    _subtitle.value = errMsg
                } else {
                    _subtitle.value = "Tap the orb to speak, Sir."
                }
            }
        )
    }

    fun submitCommand(commandText: String) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _subtitle.value = commandText
            val output = app.orchestrator.handleCommand(
                userText = commandText,
                onStateChange = { st -> _assistantState.value = st },
                onSubtitleUpdate = { sub -> _subtitle.value = sub }
            )
            _subtitle.value = output.spokenText
            if (output.isError || !output.alreadySpokenViaLiveAudio) {
                _assistantState.value = AssistantState.SPEAKING
                app.voiceEngine.speakNeural(output.spokenText) {
                    _assistantState.value = AssistantState.IDLE
                }
            } else {
                _assistantState.value = AssistantState.IDLE
            }
        }
    }

    fun submitAudioCommand(audioWavBase64: String) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _subtitle.value = "Processing voice, Sir..."
            val output = app.orchestrator.handleAudioCommand(
                audioWavBase64 = audioWavBase64,
                onStateChange = { st -> _assistantState.value = st },
                onSubtitleUpdate = { sub -> _subtitle.value = sub }
            )
            _subtitle.value = output.spokenText
            _assistantState.value = AssistantState.SPEAKING
            app.voiceEngine.speakNeural(output.spokenText) {
                _assistantState.value = AssistantState.IDLE
            }
        }
    }

    fun saveGeminiKeys(key1: String, key2: String) {
        app.vault.saveGeminiKeys(key1.trim(), key2.trim())
        app.fallbackApiClient.resetRotationToStart()
        _apiTestResult.value = null
        _settingsBanner.value = "Gemini API keys (AIza / AQ. Bearer) encrypted & saved in Android Keystore."
    }

    fun testApiConnection(key1: String, key2: String) {
        if (_isTestingApi.value) return
        viewModelScope.launch {
            _isTestingApi.value = true
            _apiTestResult.value = null
            app.vault.saveGeminiKeys(key1.trim(), key2.trim())
            app.fallbackApiClient.resetRotationToStart()
            val result = app.fallbackApiClient.testApiConnection()
            _apiTestResult.value = result
            _isTestingApi.value = false
        }
    }

    fun saveYouTubeCredentials(apiKey: String, oauthJson: String) {
        app.vault.saveYouTubeApiKey(apiKey)
        app.vault.saveYouTubeOAuthJson(oauthJson)
        _settingsBanner.value = "YouTube credentials encrypted & saved."
    }

    fun connectAndVerifyYouTube() {
        val result = app.youtubeOAuth.launchOAuthConsentFlow(app.applicationContext)
        _settingsBanner.value = result.fold(
            onSuccess = { it },
            onFailure = { it.message ?: "Unable to start Google Sign-In flow." }
        )
    }

    fun handleOAuthAuthorizationCode(authCode: String) {
        viewModelScope.launch {
            _settingsBanner.value = "Verifying YouTube channel via OAuth 2.0..."
            val result = app.youtubeOAuth.exchangeCodeAndVerifyChannel(authCode)
            _settingsBanner.value = if (result.success) {
                "Verified YouTube Channel: ${result.channelName}"
            } else {
                result.errorMessage
            }
        }
    }

    fun enqueueBackgroundYouTubeUpload(spokenTopic: String? = null) {
        YouTubeUploadWorker.enqueue(app.applicationContext, spokenTopic = spokenTopic)
        _settingsBanner.value = "Background YouTube Auto-SEO upload started with live progress."
    }

    fun runYouTubeAnalyticsCheckNow() {
        viewModelScope.launch {
            _settingsBanner.value = "Checking latest YouTube video performance..."
            val check = YouTubeAnalyticsWorker.checkChannelAndAdvise(app, forceCallOnThreshold = true)
            _settingsBanner.value = if (check.triggeredCall) {
                "Briefing call triggered · ${check.summary}"
            } else {
                check.summary
            }
        }
    }

    fun setAutoReplyEnabled(enabled: Boolean) {
        app.vault.setAutoReplyEnabled(enabled)
        _settingsBanner.value = if (enabled) "Hands-free Auto-Reply enabled." else "Auto-Reply disabled."
    }

    fun setProactiveWhatsAppCallsEnabled(enabled: Boolean) {
        app.vault.setProactiveWhatsAppCallsEnabled(enabled)
        _settingsBanner.value = if (enabled) {
            "Proactive WhatsApp Alerts & Urgent Voice Calls enabled."
        } else {
            "Proactive WhatsApp Alerts paused."
        }
    }

    fun setProactiveYouTubeCallsEnabled(enabled: Boolean) {
        app.vault.setProactiveYouTubeCallsEnabled(enabled)
        if (enabled) {
            YouTubeAnalyticsWorker.schedule(app.applicationContext)
            _settingsBanner.value = "Proactive YouTube Advisor background monitor scheduled."
        } else {
            YouTubeAnalyticsWorker.cancel(app.applicationContext)
            _settingsBanner.value = "Proactive YouTube Advisor monitor paused."
        }
    }

    fun setBackgroundKeepAliveEnabled(enabled: Boolean) {
        app.vault.setBackgroundKeepAliveEnabled(enabled)
        if (enabled) {
            JarvisKeepAliveService.start(app.applicationContext)
            _settingsBanner.value = "Persistent Background Keep-Alive Service started."
        } else {
            JarvisKeepAliveService.stop(app.applicationContext)
            _settingsBanner.value = "Background Keep-Alive Service stopped."
        }
    }

    fun saveWatchFolder(uri: String, displayName: String) {
        app.vault.saveWatchFolder(uri, displayName)
        _settingsBanner.value = "Watch folder linked: $displayName"
    }

    fun addMemory(note: String) {
        viewModelScope.launch {
            app.memoryRepository.saveMemory(note)
            _settingsBanner.value = "Saved to local Room memory."
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            app.memoryRepository.deleteMemory(id)
        }
    }

    fun triggerTestJarvisCall() {
        JarvisAlarmScheduler.triggerImmediateCall(
            context = app.applicationContext,
            reason = "Padhne ka time",
            callType = JarvisAlarmScheduler.CALL_TYPE_ALARM
        )
    }

    fun clearBanner() {
        _settingsBanner.value = null
    }
}
