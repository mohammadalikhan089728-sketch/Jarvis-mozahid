package com.example.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Unified Voice Engine providing seamless Voice Continuity:
 * - Plays native 24kHz PCM audio chunks when Gemini Live WebSocket is active.
 * - Seamlessly falls back to high-definition Android Neural TTS (en-IN / hi-IN) on standard models.
 * - Captures hands-free speech via SpeechRecognizer and streams live RMS amplitude for the AI Orb.
 */
class VoiceEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var audioTrack: AudioTrack? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private var onSpeechCompleteCallback: (() -> Unit)? = null
    private var speakingPulseRunnable: Runnable? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                selectBestNeuralVoice(isHindi = false)
                tts?.setSpeechRate(1.02f)
                tts?.setPitch(0.96f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        startSyntheticSpeechPulse()
                    }

                    override fun onDone(utteranceId: String?) {
                        stopSyntheticSpeechPulse()
                        _isSpeaking.value = false
                        _amplitude.value = 0f
                        val cb = onSpeechCompleteCallback
                        onSpeechCompleteCallback = null
                        mainHandler.post { cb?.invoke() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        stopSyntheticSpeechPulse()
                        _isSpeaking.value = false
                        _amplitude.value = 0f
                        val cb = onSpeechCompleteCallback
                        onSpeechCompleteCallback = null
                        mainHandler.post { cb?.invoke() }
                    }
                })
            }
        }
    }

    private fun selectBestNeuralVoice(isHindi: Boolean) {
        val engine = tts ?: return
        val preferredLocale = if (isHindi) Locale("hi", "IN") else Locale("en", "IN")
        try {
            val voices = engine.voices.orEmpty()
            val candidate = voices
                .filter { it.locale.language == preferredLocale.language }
                .sortedWith(
                    compareByDescending<android.speech.tts.Voice> { it.locale.country == "IN" }
                        .thenByDescending { it.quality }
                        .thenByDescending { it.name.contains("network", ignoreCase = true) || it.name.contains("neural", ignoreCase = true) }
                        .thenBy { it.latency }
                )
                .firstOrNull()
            if (candidate != null) {
                engine.voice = candidate
            } else {
                engine.language = preferredLocale
            }
        } catch (_: Exception) {
            engine.language = preferredLocale
        }
    }

    fun speakNeural(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.replace(Regex("[*_`#]"), "").trim()
        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }
        onSpeechCompleteCallback = onDone
        if (!ttsReady || tts == null) {
            onDone?.invoke()
            return
        }
        val containsDevanagari = clean.any { it in '\u0900'..'\u097F' }
        val looksHinglish = !containsDevanagari && HINGLISH_MARKERS.any {
            clean.lowercase().contains(" $it ") || clean.lowercase().startsWith("$it ")
        }
        selectBestNeuralVoice(isHindi = containsDevanagari || looksHinglish)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    /**
     * Streams raw base64 24kHz 16-bit mono PCM audio returned by Gemini Live BidiGenerateContent.
     */
    fun playLivePcmChunkBase64(base64Pcm: String) {
        try {
            val bytes = Base64.decode(base64Pcm, Base64.DEFAULT)
            if (bytes.isEmpty()) return
            ensureAudioTrack()
            _isSpeaking.value = true

            // Compute live RMS amplitude for orb animation
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < bytes.size) {
                val sample = (bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)
                val signed = if (sample > 32767) sample - 65536 else sample
                sum += abs(signed)
                count++
                i += 2
            }
            if (count > 0) {
                val avg = (sum / count) / 14000.0
                _amplitude.value = min(1f, avg.toFloat().coerceAtLeast(0.15f))
            }
            audioTrack?.write(bytes, 0, bytes.size)
        } catch (_: Exception) {
            // Fall back silently if audio device unavailable
        }
    }

    fun finishLiveAudioTurn() {
        _isSpeaking.value = false
        _amplitude.value = 0f
    }

    private fun ensureAudioTrack() {
        if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.play()
            }
            return
        }
        val sampleRate = 24_000
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    fun stopSpeaking() {
        stopSyntheticSpeechPulse()
        onSpeechCompleteCallback = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
        _isSpeaking.value = false
        _amplitude.value = 0f
    }

    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            stopSpeaking()
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onError("Voice recognition unavailable on this device. Long-press orb to type.")
                return@post
            }
            try {
                speechRecognizer?.destroy()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
                speechRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.08f, 1f)
                        _amplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        _amplitude.value = 0f
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        _amplitude.value = 0f
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during voice capture"
                            else -> "Voice input paused"
                        }
                        onError(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        _amplitude.value = 0f
                        val spoken = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (spoken.isNotEmpty()) {
                            onFinalResult(spoken)
                        } else {
                            onError("")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (partial.isNotEmpty()) {
                            onPartialResult(partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                _isListening.value = false
                onError("Unable to start voice recognition")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            _isListening.value = false
            _amplitude.value = 0f
        }
    }

    private fun startSyntheticSpeechPulse() {
        stopSyntheticSpeechPulse()
        var phase = 0.0
        val runnable = object : Runnable {
            override fun run() {
                if (!_isSpeaking.value) return
                phase += 0.45
                val level = (0.35 + 0.45 * abs(kotlin.math.sin(phase)) + 0.15 * abs(kotlin.math.cos(phase * 2.1))).toFloat()
                _amplitude.value = level.coerceIn(0.15f, 0.95f)
                mainHandler.postDelayed(this, 65L)
            }
        }
        speakingPulseRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopSyntheticSpeechPulse() {
        speakingPulseRunnable?.let { mainHandler.removeCallbacks(it) }
        speakingPulseRunnable = null
    }

    fun release() {
        stopSpeaking()
        stopListening()
        try {
            speechRecognizer?.destroy()
            audioTrack?.release()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        audioTrack = null
        tts = null
    }

    companion object {
        private val HINGLISH_MARKERS = listOf(
            "sir", "hai", "hain", "kar", "karo", "kardo", "bhej", "bhejo",
            "rehne", "padhne", "samay", "time", "ho", "gaya", "ji"
        )

        fun isStopCommand(text: String): Boolean {
            val lower = text.lowercase().trim()
            return lower == "stop" ||
                lower.contains("rehne do") ||
                lower.contains("ruk jao") ||
                lower.contains("band karo") ||
                lower == "cancel" ||
                lower == "jarvis stop"
        }
    }
}
