package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JarvisApplication
import com.example.ai.ActiveEngineState
import com.example.ai.AssistantState
import com.example.alarm.JarvisAlarmScheduler
import com.example.control.ExecutionController
import com.example.data.JarvisConfigSnapshot
import com.example.data.db.MemoryEntity
import com.example.youtube.YouTubeAnalyticsWorker
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
    val engineState: StateFlow<ActiveEngineState> = app.fallbackEngine.engineState
    val amplitude: StateFlow<Float> = app.voiceEngine.amplitude

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
                if (!hasAudioPermission) {
                    requestAudioPermission()
                    return
                }
                startVoiceListening()
            }
        }
    }

    fun startVoiceListening() {
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
            if (!output.alreadySpokenViaLiveAudio) {
                _assistantState.value = AssistantState.SPEAKING
                app.voiceEngine.speakNeural(output.spokenText) {
                    _assistantState.value = AssistantState.IDLE
                }
            } else {
                _assistantState.value = AssistantState.IDLE
            }
        }
    }

    fun saveGeminiKeys(key1: String, key2: String) {
        app.vault.saveGeminiKeys(key1, key2)
        app.fallbackEngine.closeLiveSession()
        app.fallbackEngine.refreshState()
        _settingsBanner.value = "Gemini API keys encrypted & saved in Android Keystore."
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

    fun uploadWatchFolderNow() {
        viewModelScope.launch {
            _settingsBanner.value = "Scanning watch folder for video & .txt note..."
            val outcome = app.youtubeUploader.uploadLatestFromWatchFolder { status ->
                _settingsBanner.value = status
            }
            _settingsBanner.value = outcome.message
        }
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
        _settingsBanner.value = if (enabled) "Auto-Reply enabled for WhatsApp & SMS." else "Auto-Reply disabled."
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
            reason = "Study time",
            callType = JarvisAlarmScheduler.CALL_TYPE_ALARM
        )
    }

    fun clearBanner() {
        _settingsBanner.value = null
    }
}
