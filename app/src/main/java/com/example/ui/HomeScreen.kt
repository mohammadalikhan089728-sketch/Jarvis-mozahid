package com.example.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ai.ActiveEngineState
import com.example.ai.AssistantState
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisIndigo
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.youtube.YouTubeUploadProgressState

/**
 * Ultra-Clean Minimal Home Screen (kept 100% pure & uncluttered):
 * - Obsidian (#0A0E14) background with zero cards, boxes, or grids.
 * - Top Bar: "JARVIS" on top-left, subtle active model badge, single Settings gear icon top-right.
 * - Centerpiece: Glowing fluid animated AI Orb (tap to speak/stop; long-press for silent text input).
 * - Below Orb: Minimal 2-line live subtitle text.
 * - Clean subtle progress indicator appears ONLY while a background YouTube upload is actively running.
 * - Subtle Footer: "Created by Mozahid".
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    assistantState: AssistantState,
    subtitle: String,
    amplitude: Float,
    engineState: ActiveEngineState,
    uploadProgress: YouTubeUploadProgressState,
    onOrbTap: () -> Unit,
    onSubmitTextCommand: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var showCommandSheet by remember { mutableStateOf(false) }
    var quickCommandText by remember { mutableStateOf("") }
    val orbDescription = stringResource(R.string.cd_jarvis_orb)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        when (assistantState) {
                            AssistantState.LISTENING -> JarvisCyan.copy(alpha = 0.08f)
                            AssistantState.THINKING -> JarvisIndigo.copy(alpha = 0.11f)
                            AssistantState.SPEAKING -> JarvisCyan.copy(alpha = 0.10f)
                            AssistantState.CONTROLLING -> JarvisEmerald.copy(alpha = 0.09f)
                            AssistantState.IDLE -> JarvisIndigo.copy(alpha = 0.05f)
                        },
                        Color.Transparent
                    ),
                    radius = 950f
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar: JARVIS (left) + subtle active model indicator badge + Settings gear (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        modifier = Modifier.testTag("home_title_jarvis")
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianSurface.copy(alpha = 0.72f))
                            .border(
                                width = 1.dp,
                                color = if (engineState.hasConfiguredKeys) {
                                    JarvisCyan.copy(alpha = 0.24f)
                                } else {
                                    ObsidianBorder
                                },
                                shape = RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("active_model_badge")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (engineState.hasConfiguredKeys) JarvisCyan else TextMuted
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = engineState.badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (engineState.hasConfiguredKeys) TextSecondary else TextMuted,
                            maxLines = 1
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.cd_settings_button),
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Centerpiece: Glowing Fluid Animated AI Orb + Minimal 2-line Live Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(272.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = orbDescription }
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOrbTap,
                            onLongClick = { showCommandSheet = true }
                        )
                        .testTag("jarvis_ai_orb")
                ) {
                    JarvisOrb(
                        state = assistantState,
                        amplitude = amplitude,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "live_subtitle"
                ) { liveLine ->
                    Text(
                        text = liveLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (assistantState == AssistantState.IDLE) TextSecondary else TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .padding(horizontal = 16.dp)
                            .testTag("live_subtitle_text")
                    )
                }
            }

            // Bottom area: Subtle background YouTube upload progress (only when uploading) + Credit Footer
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                AnimatedVisibility(
                    visible = uploadProgress.isUploading,
                    enter = fadeIn(tween(250)),
                    exit = fadeOut(tween(250))
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth(0.76f)
                            .padding(bottom = 14.dp)
                            .testTag("home_youtube_upload_progress")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = uploadProgress.statusText.ifBlank { "Uploading to YouTube..." },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${uploadProgress.percent}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = JarvisCyan
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (uploadProgress.percent / 100f).coerceIn(0f, 1f) },
                            color = JarvisCyan,
                            trackColor = ObsidianBorder,
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.created_by_mozahid),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted.copy(alpha = 0.65f),
                    modifier = Modifier.testTag("footer_credit_mozahid")
                )
            }
        }
    }

    if (showCommandSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCommandSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ObsidianSurface,
            contentColor = TextPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Command JARVIS",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = quickCommandText,
                    onValueChange = { quickCommandText = it },
                    placeholder = {
                        Text(
                            "e.g. Home screen pe jao, YouTube kholo aur video upload karo",
                            color = TextMuted
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = JarvisCyan
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val cmd = quickCommandText.trim()
                                if (cmd.isNotEmpty()) {
                                    quickCommandText = ""
                                    showCommandSheet = false
                                    onSubmitTextCommand(cmd)
                                }
                            },
                            modifier = Modifier.testTag("send_quick_command_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = "Send command",
                                tint = JarvisCyan
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val cmd = quickCommandText.trim()
                            if (cmd.isNotEmpty()) {
                                quickCommandText = ""
                                showCommandSheet = false
                                onSubmitTextCommand(cmd)
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_command_input")
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
