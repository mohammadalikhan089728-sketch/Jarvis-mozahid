package com.example.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.JarvisApplication
import com.example.R
import com.example.ai.AssistantState
import com.example.ai.VoiceEngine
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.JarvisCrimson
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisIndigo
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-Screen Realistic Incoming Call UI ("JARVIS Calling...") that wakes over the lock screen.
 * When answered, JARVIS speaks the custom alarm reason or YouTube briefing immediately and
 * remains on the line listening for follow-up commands.
 */
class JarvisIncomingCallActivity : ComponentActivity() {

    private var alarmId: Long = -1L
    private var callReason: String = "Study time"
    private var callType: String = JarvisAlarmScheduler.CALL_TYPE_ALARM

    private val isAnsweredState = mutableStateOf(false)
    private val callSubtitleState = mutableStateOf("")
    private val callAssistantState = mutableStateOf(AssistantState.IDLE)
    private val isMutedState = mutableStateOf(false)
    private val isSpeakerState = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenWake()
        enableEdgeToEdge()

        alarmId = intent.getLongExtra(JarvisAlarmScheduler.EXTRA_ALARM_ID, -1L)
        callReason = intent.getStringExtra(JarvisAlarmScheduler.EXTRA_CALL_REASON)
            ?: "Study time"
        callType = intent.getStringExtra(JarvisAlarmScheduler.EXTRA_CALL_TYPE)
            ?: JarvisAlarmScheduler.CALL_TYPE_ALARM

        val app = application as JarvisApplication

        setContent {
            JarvisTheme {
                val amplitude by app.voiceEngine.amplitude.collectAsStateWithLifecycle()
                val answered by isAnsweredState
                val subtitle by callSubtitleState
                val state by callAssistantState
                val muted by isMutedState
                val speaker by isSpeakerState

                IncomingCallScreen(
                    answered = answered,
                    callType = callType,
                    reason = callReason,
                    subtitle = subtitle,
                    assistantState = state,
                    amplitude = amplitude,
                    isMuted = muted,
                    isSpeakerOn = speaker,
                    onAnswer = { answerCall(app) },
                    onDeclineOrEnd = { endCall(app) },
                    onToggleMute = {
                        isMutedState.value = !isMutedState.value
                        if (isMutedState.value) {
                            app.voiceEngine.stopListening()
                            callAssistantState.value = AssistantState.IDLE
                        } else if (isAnsweredState.value) {
                            listenForFollowUp(app)
                        }
                    },
                    onToggleSpeaker = {
                        isSpeakerState.value = !isSpeakerState.value
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = isSpeakerState.value
                    }
                )
            }
        }
    }

    private fun configureLockScreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun answerCall(app: JarvisApplication) {
        if (isAnsweredState.value) return
        isAnsweredState.value = true

        // Silence ringtone & vibration immediately
        startService(Intent(this, JarvisCallForegroundService::class.java).apply {
            action = JarvisCallForegroundService.ACTION_STOP_RINGING
        })

        // Deactivate one-shot alarm in Room
        if (alarmId > 0L) {
            lifecycleScope.launch { app.memoryRepository.deactivateAlarm(alarmId) }
        }

        val openingSpeech = if (callType == JarvisAlarmScheduler.CALL_TYPE_YOUTUBE_ADVISOR) {
            callReason
        } else {
            "Sir, it's time for $callReason. You set an alarm for this, please get started. I'm on the line if you need anything."
        }

        callSubtitleState.value = openingSpeech
        callAssistantState.value = AssistantState.SPEAKING
        app.voiceEngine.speakNeural(openingSpeech) {
            if (isAnsweredState.value && !isMutedState.value) {
                listenForFollowUp(app)
            }
        }
    }

    private fun listenForFollowUp(app: JarvisApplication) {
        if (!isAnsweredState.value || isMutedState.value) return
        callAssistantState.value = AssistantState.LISTENING
        app.voiceEngine.startListening(
            onPartialResult = { partial ->
                callSubtitleState.value = partial
            },
            onFinalResult = { spoken ->
                if (VoiceEngine.isStopCommand(spoken) || spoken.lowercase().contains("hang up")) {
                    endCall(app)
                    return@startListening
                }
                callSubtitleState.value = spoken
                callAssistantState.value = AssistantState.THINKING
                lifecycleScope.launch {
                    val output = app.orchestrator.handleCommand(spoken)
                    callSubtitleState.value = output.spokenText
                    if (!output.alreadySpokenViaLiveAudio) {
                        callAssistantState.value = AssistantState.SPEAKING
                        app.voiceEngine.speakNeural(output.spokenText) {
                            if (isAnsweredState.value && !isMutedState.value) {
                                listenForFollowUp(app)
                            }
                        }
                    } else {
                        callAssistantState.value = AssistantState.IDLE
                        delay(500L)
                        if (isAnsweredState.value && !isMutedState.value) {
                            listenForFollowUp(app)
                        }
                    }
                }
            },
            onError = {
                callAssistantState.value = AssistantState.IDLE
            }
        )
    }

    private fun endCall(app: JarvisApplication) {
        isAnsweredState.value = false
        app.voiceEngine.stopSpeaking()
        app.voiceEngine.stopListening()
        startService(Intent(this, JarvisCallForegroundService::class.java).apply {
            action = JarvisCallForegroundService.ACTION_END_CALL
        })
        finish()
    }

    override fun onDestroy() {
        val app = application as? JarvisApplication
        app?.voiceEngine?.stopListening()
        super.onDestroy()
    }
}

@Composable
private fun IncomingCallScreen(
    answered: Boolean,
    callType: String,
    reason: String,
    subtitle: String,
    assistantState: AssistantState,
    amplitude: Float,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onAnswer: () -> Unit,
    onDeclineOrEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(answered) {
        if (answered) {
            elapsedSeconds = 0
            while (true) {
                delay(1000L)
                elapsedSeconds++
            }
        }
    }

    val timerText = remember(elapsedSeconds) {
        val mins = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        String.format("%02d:%02d", mins, secs)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        if (answered) JarvisCyan.copy(alpha = 0.14f) else JarvisIndigo.copy(alpha = 0.22f),
                        ObsidianBackground
                    ),
                    radius = 900f
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top caller identity
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (answered) {
                        stringResource(R.string.jarvis_connected_title)
                    } else {
                        stringResource(R.string.jarvis_calling_title)
                    },
                    style = MaterialTheme.typography.displaySmall,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                val label = when {
                    answered -> timerText
                    callType == JarvisAlarmScheduler.CALL_TYPE_YOUTUBE_ADVISOR -> "YouTube Advisor · Priority Call"
                    else -> "Voice-Call Alarm · ${reason.take(42)}"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = JarvisCyan
                )
            }

            // Center pulsing JARVIS orb
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                JarvisOrb(
                    state = if (answered) assistantState else AssistantState.SPEAKING,
                    amplitude = if (answered) amplitude else 0.52f,
                    modifier = Modifier.size(230.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                AnimatedContent(targetState = subtitle, label = "call_subtitle") { text ->
                    Text(
                        text = text.ifBlank {
                            if (answered) "Listening, Sir..." else "Swipe right to answer JARVIS"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }

            // Bottom call actions
            if (!answered) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SwipeToAnswerBar(
                        onAnswer = onAnswer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("swipe_to_answer_bar")
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Surface(
                        onClick = onDeclineOrEnd,
                        shape = CircleShape,
                        color = JarvisCrimson.copy(alpha = 0.18f),
                        modifier = Modifier
                            .size(64.dp)
                            .border(1.dp, JarvisCrimson.copy(alpha = 0.5f), CircleShape)
                            .testTag("decline_call_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = stringResource(R.string.cd_decline_call),
                                tint = JarvisCrimson,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlCircle(
                        icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.cd_mute_mic),
                        active = isMuted,
                        onClick = onToggleMute,
                        tag = "call_mute_button"
                    )
                    Surface(
                        onClick = onDeclineOrEnd,
                        shape = CircleShape,
                        color = JarvisCrimson,
                        modifier = Modifier
                            .size(68.dp)
                            .testTag("end_call_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = stringResource(R.string.cd_end_call),
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    CallControlCircle(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = stringResource(R.string.cd_speaker_toggle),
                        active = isSpeakerOn,
                        onClick = onToggleSpeaker,
                        tag = "call_speaker_button"
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeToAnswerBar(
    onAnswer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val trackWidthDp = 280.dp
    val thumbSizeDp = 56.dp
    val density = LocalDensity.current
    val maxDragPx = with(density) { (trackWidthDp - thumbSizeDp - 12.dp).toPx() }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .width(trackWidthDp)
            .height(68.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianSurface)
            .border(1.dp, JarvisEmerald.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable { onAnswer() }
            .padding(6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(36.dp))
            Text(
                text = stringResource(R.string.swipe_to_answer),
                style = MaterialTheme.typography.titleMedium,
                color = JarvisEmerald
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = JarvisEmerald.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                .size(thumbSizeDp)
                .clip(CircleShape)
                .background(JarvisEmerald)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragOffsetPx >= maxDragPx * 0.65f) {
                                onAnswer()
                            } else {
                                dragOffsetPx = 0f
                            }
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(0f, maxDragPx)
                            if (dragOffsetPx >= maxDragPx * 0.92f) {
                                onAnswer()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Call,
                contentDescription = stringResource(R.string.cd_answer_call),
                tint = ObsidianBackground,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun CallControlCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) JarvisCyan.copy(alpha = 0.2f) else ObsidianSurface,
        modifier = Modifier
            .size(56.dp)
            .border(1.dp, if (active) JarvisCyan else ObsidianBorder, CircleShape)
            .testTag(tag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (active) JarvisCyan else TextMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
