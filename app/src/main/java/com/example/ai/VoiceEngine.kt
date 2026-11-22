package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

/**
 * Unified Production Voice Engine:
 * - Initializes Android Neural TextToSpeech (hi-IN / en-IN) immediately on app startup,
 *   queuing any early speech so JARVIS always speaks every reply clearly aloud.
 * - Streams native 24kHz PCM audio chunks via AudioTrack when Gemini Live WebSocket is active.
 * - Checks `ContextCompat.checkSelfPermission(RECORD_AUDIO)` directly before capture and NEVER
 *   shows a false "Allow microphone" prompt when RECORD_AUDIO is already granted.
 * - Automatically falls back to direct 16kHz `AudioRecord` PCM capture (sending WAV to Gemini)
 *   if `SpeechRecognizer` is unavailable or fails on the device, with real diagnostic error messages.
 */
class VoiceEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var tts: TextToSpeech? = null
    @Volatile
    private var ttsReady = false
    private var pendingSpeechText: String? = null
    private var pendingSpeechCallback: (() -> Unit)? = null

    private var audioTrack: AudioTrack? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var pcmRecordJob: Job? = null

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

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                selectBestNeuralVoice(isHindi = false)
                tts?.setSpeechRate(1.02f)
                tts?.setPitch(0.97f)
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

                // Flush any queued speech requested while TTS was warming up
                val queuedText = pendingSpeechText
                val queuedCb = pendingSpeechCallback
                pendingSpeechText = null
                pendingSpeechCallback = null
                if (!queuedText.isNullOrBlank()) {
                    mainHandler.post { speakNeural(queuedText, queuedCb) }
                }
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
                        .thenByDescending {
                            it.name.contains("network", ignoreCase = true) ||
                                it.name.contains("neural", ignoreCase = true)
                        }
                        .thenBy { it.latency }
                )
                .firstOrNull()
            if (candidate != null) {
                engine.voice = candidate
            } else {
                engine.language = preferredLocale
            }
        } catch (_: Exception) {
            runCatching { engine.language = preferredLocale }
        }
    }

    fun speakNeural(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.replace(Regex("[*_`#]"), "").trim()
        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }
        if (!ttsReady || tts == null) {
            pendingSpeechText = clean
            pendingSpeechCallback = onDone
            return
        }
        onSpeechCompleteCallback = onDone
        val containsDevanagari = clean.any { it in '\u0900'..'\u097F' }
        val looksHinglish = !containsDevanagari && HINGLISH_MARKERS.any {
            clean.lowercase().contains(" $it ") || clean.lowercase().startsWith("$it ")
        }
        selectBestNeuralVoice(isHindi = containsDevanagari || looksHinglish)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
        if (result == TextToSpeech.ERROR) {
            onSpeechCompleteCallback = null
            onDone?.invoke()
        }
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
            // AudioTrack unavailable; Neural TTS fallback handles text
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
        pendingSpeechText = null
        pendingSpeechCallback = null
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

    /**
     * Starts voice capture using Android SpeechRecognizer when available, or automatically falls back
     * to direct 16kHz AudioRecord PCM capture (delivering WAV base64 to `onAudioWavCaptured`).
     * Never falsely reports a microphone permission error when RECORD_AUDIO is granted.
     */
    fun startListening(
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onAudioWavCaptured: ((String) -> Unit)? = null,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            stopSpeaking()
            stopListeningInternal()

            if (!hasMicrophonePermission()) {
                onError("Microphone permission denied.")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                if (onAudioWavCaptured != null) {
                    startDirectAudioRecordCapture(onPartialResult, onAudioWavCaptured, onError)
                } else {
                    onError("Speech recognizer unavailable. Long-press orb to type.")
                }
                return@post
            }

            try {
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

                        // If SpeechRecognizer fails with client/permission-service quirk while RECORD_AUDIO IS granted,
                        // fall back seamlessly to direct AudioRecord PCM capture when callback is available!
                        if (hasMicrophonePermission() &&
                            onAudioWavCaptured != null &&
                            error in setOf(
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                                SpeechRecognizer.ERROR_CLIENT,
                                SpeechRecognizer.ERROR_SERVER
                            )
                        ) {
                            startDirectAudioRecordCapture(onPartialResult, onAudioWavCaptured, onError)
                            return
                        }

                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Mic busy or audio error (code 3)"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic busy (code 8)"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Speech recognizer network error (code $error)"
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ""
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                if (hasMicrophonePermission()) {
                                    "Speech service unavailable (code 9)"
                                } else {
                                    "Microphone permission denied."
                                }
                            else -> "Recognizer error code $error"
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
            } catch (e: SecurityException) {
                _isListening.value = false
                if (hasMicrophonePermission() && onAudioWavCaptured != null) {
                    startDirectAudioRecordCapture(onPartialResult, onAudioWavCaptured, onError)
                } else {
                    onError("Audio security error: ${e.localizedMessage ?: "Mic access blocked"}")
                }
            } catch (e: Exception) {
                _isListening.value = false
                if (hasMicrophonePermission() && onAudioWavCaptured != null) {
                    startDirectAudioRecordCapture(onPartialResult, onAudioWavCaptured, onError)
                } else {
                    onError("Mic init error: ${e.localizedMessage ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Direct 16kHz PCM AudioRecord fallback used automatically when SpeechRecognizer is unavailable.
     * Captures live audio with RMS orb animation and silence detection, returning WAV base64.
     */
    @SuppressLint("MissingPermission")
    private fun startDirectAudioRecordCapture(
        onPartialResult: (String) -> Unit,
        onAudioWavCaptured: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!hasMicrophonePermission()) {
            onError("Microphone permission denied.")
            return
        }

        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("AudioRecord unsupported buffer size ($minBuf)")
            return
        }

        val bufferSize = (minBuf * 2).coerceAtLeast(4096)
        val recorder = try {
            val primary = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (primary.state == AudioRecord.STATE_INITIALIZED) {
                primary
            } else {
                primary.release()
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }
        } catch (e: Exception) {
            onError("Mic busy: ${e.localizedMessage ?: "Cannot initialize AudioRecord"}")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("Mic busy (AudioRecord failed to initialize)")
            return
        }

        audioRecord = recorder
        _isListening.value = true
        mainHandler.post { onPartialResult("Listening (Direct Audio), Sir...") }

        pcmRecordJob = audioScope.launch {
            val pcmOut = ByteArrayOutputStream()
            val chunk = ByteArray(bufferSize)
            try {
                recorder.startRecording()
                val startTime = System.currentTimeMillis()
                var speechDetected = false
                var silenceSince = 0L

                while (isActive && _isListening.value) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) break
                    pcmOut.write(chunk, 0, read)

                    // Calculate live RMS amplitude
                    var sum = 0.0
                    var samples = 0
                    var i = 0
                    while (i + 1 < read) {
                        val s = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                        val signed = if (s > 32767) s - 65536 else s
                        sum += abs(signed)
                        samples++
                        i += 2
                    }
                    val avgAmp = if (samples > 0) (sum / samples) / 12000.0 else 0.0
                    _amplitude.value = avgAmp.toFloat().coerceIn(0.06f, 1f)

                    val now = System.currentTimeMillis()
                    if (avgAmp > 0.08) {
                        speechDetected = true
                        silenceSince = 0L
                    } else if (speechDetected) {
                        if (silenceSince == 0L) silenceSince = now
                        if (now - silenceSince > 1400L) break
                    }

                    if (now - startTime > 6_500L) break
                }
            } catch (e: Exception) {
                mainHandler.post { onError("Mic busy: ${e.localizedMessage ?: "Audio capture error"}") }
                return@launch
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
                audioRecord = null
                _isListening.value = false
                _amplitude.value = 0f
            }

            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.size > 3200) {
                val wavBytes = wrapPcmAsWav(pcmBytes, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
                val b64 = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
                mainHandler.post { onAudioWavCaptured(b64) }
            } else {
                mainHandler.post { onError("") }
            }
        }
    }

    private fun wrapPcmAsWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }.array()
        return header + pcmData
    }

    fun stopListening() {
        mainHandler.post { stopListeningInternal() }
    }

    private fun stopListeningInternal() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null

        pcmRecordJob?.cancel()
        pcmRecordJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        _isListening.value = false
        _amplitude.value = 0f
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
            audioTrack?.release()
            tts?.shutdown()
        } catch (_: Exception) {
        }
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
