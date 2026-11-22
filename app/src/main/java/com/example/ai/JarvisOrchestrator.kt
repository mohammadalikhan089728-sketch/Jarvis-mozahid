package com.example.ai

import android.content.Context
import com.example.alarm.JarvisAlarmScheduler
import com.example.control.ControlService
import com.example.control.DeviceActionHelper
import com.example.control.ExecutionController
import com.example.control.FloatingPillManager
import com.example.data.db.MemoryRepository
import com.example.youtube.YouTubeUploadWorker
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Central tool-execution orchestrator connecting FallbackApiClient with:
 * - High-Speed Human-Like Accessibility ControlService & Top Floating Pill
 * - Device hardware / direct messaging actions
 * - Local Room Memory
 * - JARVIS Lock-Screen Voice-Call Alarms
 * - Background WorkManager YouTube Auto-SEO Uploader
 */
class JarvisOrchestrator(
    private val context: Context,
    private val fallbackApiClient: FallbackApiClient,
    private val voiceEngine: VoiceEngine,
    private val memoryRepository: MemoryRepository,
    private val deviceActions: DeviceActionHelper,
    private val floatingPill: FloatingPillManager
) {
    suspend fun handleCommand(
        userText: String,
        onStateChange: (AssistantState) -> Unit = {},
        onSubtitleUpdate: (String) -> Unit = {}
    ): AssistantTurnOutput {
        val trimmed = userText.trim()
        if (VoiceEngine.isStopCommand(trimmed)) {
            ExecutionController.requestStop()
            floatingPill.hide()
            voiceEngine.stopSpeaking()
            onStateChange(AssistantState.IDLE)
            val msg = "Stopped, Sir."
            onSubtitleUpdate(msg)
            return AssistantTurnOutput(
                spokenText = msg,
                alreadySpokenViaLiveAudio = false,
                usedModelBadge = fallbackApiClient.engineState.value.badgeText
            )
        }

        ExecutionController.resetStopFlag()
        onStateChange(AssistantState.THINKING)

        var usedPhoneControl = false

        val output = try {
            fallbackApiClient.processUserTurn(
                userText = trimmed,
                shouldContinue = { ExecutionController.shouldContinue() },
                executeTool = { name, args ->
                    if (isScreenControlTool(name)) {
                        if (!usedPhoneControl) {
                            usedPhoneControl = true
                            ExecutionController.beginControlSession()
                            onStateChange(AssistantState.CONTROLLING)
                            floatingPill.show {
                                voiceEngine.stopSpeaking()
                                onStateChange(AssistantState.IDLE)
                            }
                        }
                    }
                    executeToolByName(name, args)
                },
                onLiveAudioChunk = { base64Pcm ->
                    onStateChange(AssistantState.SPEAKING)
                    voiceEngine.playLivePcmChunkBase64(base64Pcm)
                },
                onSubtitleUpdate = { partial ->
                    if (partial.isNotBlank()) onSubtitleUpdate(partial)
                }
            )
        } finally {
            if (usedPhoneControl) {
                ExecutionController.endControlSession()
                floatingPill.hide()
            }
            voiceEngine.finishLiveAudioTurn()
        }

        return output
    }

    suspend fun handleAudioCommand(
        audioWavBase64: String,
        onStateChange: (AssistantState) -> Unit = {},
        onSubtitleUpdate: (String) -> Unit = {}
    ): AssistantTurnOutput {
        ExecutionController.resetStopFlag()
        onStateChange(AssistantState.THINKING)

        var usedPhoneControl = false

        return try {
            fallbackApiClient.processAudioTurn(
                audioWavBase64 = audioWavBase64,
                shouldContinue = { ExecutionController.shouldContinue() },
                executeTool = { name, args ->
                    if (isScreenControlTool(name)) {
                        if (!usedPhoneControl) {
                            usedPhoneControl = true
                            ExecutionController.beginControlSession()
                            onStateChange(AssistantState.CONTROLLING)
                            floatingPill.show {
                                voiceEngine.stopSpeaking()
                                onStateChange(AssistantState.IDLE)
                            }
                        }
                    }
                    executeToolByName(name, args)
                },
                onSubtitleUpdate = { partial ->
                    if (partial.isNotBlank()) onSubtitleUpdate(partial)
                }
            )
        } finally {
            if (usedPhoneControl) {
                ExecutionController.endControlSession()
                floatingPill.hide()
            }
            voiceEngine.finishLiveAudioTurn()
        }
    }

    private fun isScreenControlTool(name: String): Boolean {
        return name in setOf(
            "go_home", "open_app", "ui_read", "ui_click", "ui_type", "ui_scroll", "ui_back",
            "whatsapp_send", "instagram_dm", "sms_send", "call_contact"
        )
    }

    private suspend fun executeToolByName(
        name: String,
        args: JSONObject
    ): String {
        if (!ExecutionController.shouldContinue()) return "stopped"
        return when (name) {
            "go_home" -> {
                val svc = ControlService.instance
                    ?: return "error: Accessibility ControlService is disabled. Enable it in Settings."
                svc.goHome()
            }
            "open_app" -> {
                val appName = args.optString("app_name", "")
                val res = deviceActions.openApp(appName)
                delay(500L)
                res
            }
            "ui_read" -> {
                val svc = ControlService.instance
                    ?: return "error: Accessibility ControlService is disabled. Enable it in Settings."
                svc.uiRead()
            }
            "ui_click" -> {
                val svc = ControlService.instance
                    ?: return "error: Accessibility ControlService is disabled."
                val idx = if (args.has("index") && !args.isNull("index")) {
                    args.optInt("index", -1).takeIf { it > 0 }
                } else {
                    null
                }
                val txt = args.optString("text", "").takeIf { it.isNotBlank() }
                val res = svc.uiClick(index = idx, text = txt)
                delay(280L)
                res
            }
            "ui_type" -> {
                val svc = ControlService.instance
                    ?: return "error: Accessibility ControlService is disabled."
                val idx = if (args.has("index") && !args.isNull("index")) {
                    args.optInt("index", -1).takeIf { it > 0 }
                } else {
                    null
                }
                val label = args.optString("label", "").takeIf { it.isNotBlank() }
                val text = args.optString("text", "")
                val submit = args.optBoolean("submit", args.optBoolean("press_send", false))
                val res = svc.uiType(index = idx, label = label, text = text, submit = submit)
                delay(260L)
                res
            }
            "ui_scroll" -> {
                val svc = ControlService.instance
                    ?: return "error: Accessibility ControlService is disabled."
                val dir = args.optString("direction", "down")
                val res = svc.uiScroll(dir)
                delay(260L)
                res
            }
            "ui_back" -> {
                val svc = ControlService.instance
                    ?: return "error: Accessibility ControlService is disabled."
                val res = svc.uiBack()
                delay(240L)
                res
            }
            "call_contact" -> {
                val target = args.optString("target", "")
                deviceActions.callContact(target)
            }
            "whatsapp_send" -> {
                val contact = args.optString("contact", "")
                val message = args.optString("message", "")
                deviceActions.whatsappSend(contact, message)
            }
            "instagram_dm" -> {
                val username = args.optString("username", "")
                val message = args.optString("message", "")
                deviceActions.instagramDm(username, message)
            }
            "sms_send" -> {
                val target = args.optString("target", "")
                val message = args.optString("message", "")
                deviceActions.smsSend(target, message)
            }
            "device_control" -> {
                val action = args.optString("action", "")
                deviceActions.deviceControl(action)
            }
            "set_default_timer" -> {
                val seconds = args.optInt("seconds", 60)
                val label = args.optString("label", "JARVIS Timer")
                deviceActions.setDefaultTimer(seconds, label)
            }
            "set_jarvis_call_alarm" -> {
                val delayMinutes = args.optInt("delay_minutes", 1).coerceAtLeast(1)
                val reason = args.optString("reason", "your reminder")
                JarvisAlarmScheduler.scheduleVoiceCallAlarm(
                    context = context,
                    repository = memoryRepository,
                    delayMinutes = delayMinutes,
                    reason = reason
                )
                "done: JARVIS voice-call alarm scheduled in $delayMinutes minute(s) for '$reason'"
            }
            "save_memory" -> {
                val note = args.optString("note", "")
                if (note.isBlank()) {
                    "error: empty memory"
                } else {
                    memoryRepository.saveMemory(note)
                    "done"
                }
            }
            "recall_memory" -> {
                val query = args.optString("query", "")
                val match = memoryRepository.findTopMatchingSnippet(query)
                if (match != null) "done: $match" else "no matching local memory found"
            }
            "youtube_upload_folder" -> {
                val topic = args.optString("topic", "").takeIf { it.isNotBlank() }
                YouTubeUploadWorker.enqueue(context, spokenTopic = topic)
                "done: background YouTube Auto-SEO upload started with live progress notification"
            }
            else -> "error: unsupported tool '$name'"
        }
    }
}
