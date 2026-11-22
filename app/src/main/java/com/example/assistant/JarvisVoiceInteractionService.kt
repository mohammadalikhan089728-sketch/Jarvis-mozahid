package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.example.MainActivity

/**
 * Registers JARVIS as an official Android System Digital Assistant App alongside
 * Google Assistant and ChatGPT in Settings > Apps > Default Apps > Digital Assistant App.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
    }
}

class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceInteractionSession(this)
    }
}

class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SUMMON_JARVIS
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(EXTRA_AUTO_LISTEN, true)
        }
        runCatching { context.startActivity(launchIntent) }
        hide()
    }

    companion object {
        const val ACTION_SUMMON_JARVIS = "com.example.assistant.ACTION_SUMMON_JARVIS"
        const val EXTRA_AUTO_LISTEN = "extra_auto_listen"
    }
}

/**
 * Companion RecognitionService required by Android so JARVIS appears cleanly
 * as a complete Voice Input & Digital Assistant choice in system settings.
 */
class JarvisRecognitionService : RecognitionService() {

    private var delegateRecognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        val cb = listener ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            runCatching { cb.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        try {
            delegateRecognizer?.destroy()
            delegateRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        runCatching { cb.readyForSpeech(params ?: Bundle()) }
                    }

                    override fun onBeginningOfSpeech() {
                        runCatching { cb.beginningOfSpeech() }
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        runCatching { cb.rmsChanged(rmsdB) }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        if (buffer != null) runCatching { cb.bufferReceived(buffer) }
                    }

                    override fun onEndOfSpeech() {
                        runCatching { cb.endOfSpeech() }
                    }

                    override fun onError(error: Int) {
                        runCatching { cb.error(error) }
                    }

                    override fun onResults(results: Bundle?) {
                        runCatching { cb.results(results ?: Bundle()) }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        runCatching { cb.partialResults(partialResults ?: Bundle()) }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                startListening(recognizerIntent ?: Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
            }
        } catch (_: Exception) {
            runCatching { cb.error(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    override fun onCancel(listener: Callback?) {
        runCatching { delegateRecognizer?.cancel() }
    }

    override fun onStopListening(listener: Callback?) {
        runCatching { delegateRecognizer?.stopListening() }
    }

    override fun onDestroy() {
        runCatching { delegateRecognizer?.destroy() }
        delegateRecognizer = null
        super.onDestroy()
    }
}
