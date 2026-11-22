package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneCallback
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ai.ActiveEngineState
import com.example.ai.ModelRingConfig
import com.example.alarm.JarvisAlarmScheduler
import com.example.control.ControlService
import com.example.control.JarvisNotificationService
import com.example.data.JarvisConfigSnapshot
import com.example.data.db.MemoryEntity
import com.example.ui.theme.JarvisCrimson
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisIndigo
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    config: JarvisConfigSnapshot,
    engineState: ActiveEngineState,
    memories: List<MemoryEntity>,
    statusBanner: String?,
    onBack: () -> Unit,
    onSaveGeminiKeys: (String, String) -> Unit,
    onSaveYouTubeCredentials: (apiKey: String, oauthJson: String) -> Unit,
    onConnectYouTube: () -> Unit,
    onManualOAuthCodeSubmit: (String) -> Unit,
    onUploadWatchFolderNow: () -> Unit,
    onRunYouTubeAnalyticsCheck: () -> Unit,
    onToggleAutoReply: (Boolean) -> Unit,
    onToggleProactiveYouTubeCalls: (Boolean) -> Unit,
    onWatchFolderPicked: (uri: String, displayName: String) -> Unit,
    onAddMemory: (String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onTestJarvisCallAlarm: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var key1 by remember(config.geminiKey1) { mutableStateOf(config.geminiKey1) }
    var key2 by remember(config.geminiKey2) { mutableStateOf(config.geminiKey2) }
    var ytApiKey by remember(config.youtubeApiKey) { mutableStateOf(config.youtubeApiKey) }
    var ytOAuthJson by remember(config.youtubeOAuthJson) { mutableStateOf(config.youtubeOAuthJson) }

    var showKey1 by remember { mutableStateOf(false) }
    var showKey2 by remember { mutableStateOf(false) }
    var showYtKey by remember { mutableStateOf(false) }

    var showManualCodeDialog by remember { mutableStateOf(false) }
    var manualOAuthCode by remember { mutableStateOf("") }

    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var newMemoryText by remember { mutableStateOf("") }

    // Live Android System Permission / Service Statuses refreshed onResume
    var accessibilityEnabled by remember { mutableStateOf(ControlService.isEnabled(context)) }
    var notificationAccessEnabled by remember { mutableStateOf(JarvisNotificationService.isEnabled(context)) }
    var overlayEnabled by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var exactAlarmEnabled by remember { mutableStateOf(JarvisAlarmScheduler.canScheduleExactAlarms(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = ControlService.isEnabled(context)
                notificationAccessEnabled = JarvisNotificationService.isEnabled(context)
                overlayEnabled = Settings.canDrawOverlays(context)
                exactAlarmEnabled = JarvisAlarmScheduler.canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // SAF Picker for OAuth 2.0 Client Credentials JSON file
    val jsonPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                ytOAuthJson = content
                onSaveYouTubeCredentials(ytApiKey, content)
            }
        }
    }

    // SAF Picker for Watch Folder (e.g. Jarvis_Uploads)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name ?: "Jarvis_Uploads"
            onWatchFolderPicked(uri.toString(), name)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back_button),
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "JARVIS Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Active Ring: ${engineState.badgeText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = JarvisCyan
                    )
                }
            }

            if (!statusBanner.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(JarvisCyan.copy(alpha = 0.10f))
                        .border(1.dp, JarvisCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = statusBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = JarvisCyan
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                // 1. DUAL GEMINI API KEYS
                item {
                    SettingsSectionCard(
                        title = "Dual Gemini API Keys",
                        subtitle = "Encrypted via Android Keystore (AES-256-GCM) · Zero Hardcoded Keys"
                    ) {
                        SecretTextField(
                            value = key1,
                            onValueChange = { key1 = it },
                            label = "Gemini API Key 1 (Primary)",
                            visible = showKey1,
                            onToggleVisible = { showKey1 = !showKey1 },
                            tag = "gemini_key_1_input"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        SecretTextField(
                            value = key2,
                            onValueChange = { key2 = it },
                            label = "Gemini API Key 2 (Silent Fallback)",
                            visible = showKey2,
                            onToggleVisible = { showKey2 = !showKey2 },
                            tag = "gemini_key_2_input"
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "2-Level Ring: " + ModelRingConfig.MODEL_RING.joinToString(" → ") { it.badgeLabel },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onSaveGeminiKeys(key1, key2) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = ObsidianBackground
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_gemini_keys_button")
                        ) {
                            Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Encrypted Keys", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }

                // 2. YOUTUBE DATA API v3 & OAUTH 2.0 INTEGRATION
                item {
                    SettingsSectionCard(
                        title = "YouTube Integration",
                        subtitle = "Auto-SEO Uploader & Proactive Channel Advisor (Test User Gmail Supported)"
                    ) {
                        if (config.isYouTubeConnected) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(JarvisEmerald.copy(alpha = 0.12f))
                                    .border(1.dp, JarvisEmerald.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = "Verified channel",
                                    tint = JarvisEmerald,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = config.youtubeChannelName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    if (config.youtubeChannelStats.isNotBlank()) {
                                        Text(
                                            text = config.youtubeChannelStats,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = JarvisEmerald
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        SecretTextField(
                            value = ytApiKey,
                            onValueChange = { ytApiKey = it },
                            label = "YouTube Data API v3 Key",
                            visible = showYtKey,
                            onToggleVisible = { showYtKey = !showYtKey },
                            tag = "youtube_api_key_input"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = ytOAuthJson,
                            onValueChange = { ytOAuthJson = it },
                            label = { Text("OAuth 2.0 Client Credentials (JSON)", color = TextSecondary) },
                            placeholder = { Text("{\"installed\":{\"client_id\":\"...\"}}", color = TextMuted) },
                            minLines = 3,
                            maxLines = 5,
                            colors = jarvisTextFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("youtube_oauth_json_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { jsonPickerLauncher.launch(arrayOf("application/json", "text/plain")) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("pick_oauth_json_button")
                            ) {
                                Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pick JSON", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { onSaveYouTubeCredentials(ytApiKey, ytOAuthJson) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("save_youtube_creds_button")
                            ) {
                                Text("Save Creds", maxLines = 1)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                onSaveYouTubeCredentials(ytApiKey, ytOAuthJson)
                                onConnectYouTube()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisIndigo,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("connect_verify_youtube_button")
                        ) {
                            Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connect & Verify YouTube Channel", style = MaterialTheme.typography.titleSmall)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = { showManualCodeDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Enter Auth Code", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            }
                            TextButton(
                                onClick = onUploadWatchFolderNow,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("upload_folder_now_button")
                            ) {
                                Text("Upload Folder", style = MaterialTheme.typography.labelMedium, color = JarvisCyan)
                            }
                            TextButton(
                                onClick = onRunYouTubeAnalyticsCheck,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("check_analytics_button")
                            ) {
                                Text("Check Stats", style = MaterialTheme.typography.labelMedium, color = JarvisCyan)
                            }
                        }
                    }
                }

                // 3. AUTO-REPLY & AUTOMATION TOGGLES
                item {
                    SettingsSectionCard(
                        title = "Auto-Reply & Automation",
                        subtitle = "Zero-Touch Phone Control, Notification Replies & Proactive Calls"
                    ) {
                        ToggleRow(
                            title = "Auto-Reply to Messages",
                            subtitle = "Reply directly to WhatsApp & SMS via RemoteInput and speak report",
                            checked = config.autoReplyEnabled,
                            onCheckedChange = onToggleAutoReply,
                            tag = "toggle_auto_reply"
                        )
                        HorizontalDivider(color = ObsidianBorder, modifier = Modifier.padding(vertical = 8.dp))
                        ToggleRow(
                            title = "Proactive YouTube Advisor Calls",
                            subtitle = "WorkManager analytics monitor calls you on view spikes or drops",
                            checked = config.proactiveYouTubeCallsEnabled,
                            onCheckedChange = onToggleProactiveYouTubeCalls,
                            tag = "toggle_proactive_yt"
                        )
                        HorizontalDivider(color = ObsidianBorder, modifier = Modifier.padding(vertical = 8.dp))
                        PermissionToggleRow(
                            title = "Accessibility Phone Control",
                            subtitle = if (accessibilityEnabled) "Active · Text-Tree reading & direct execution ready" else "Tap to enable ControlService in Android Accessibility",
                            granted = accessibilityEnabled,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                            tag = "toggle_accessibility_control"
                        )
                        HorizontalDivider(color = ObsidianBorder, modifier = Modifier.padding(vertical = 8.dp))
                        PermissionToggleRow(
                            title = "Notification Access",
                            subtitle = if (notificationAccessEnabled) "Active · Listening for WhatsApp & SMS replies" else "Tap to grant Notification Listener access",
                            granted = notificationAccessEnabled,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            tag = "toggle_notification_access"
                        )
                        HorizontalDivider(color = ObsidianBorder, modifier = Modifier.padding(vertical = 8.dp))
                        PermissionToggleRow(
                            title = "Top Floating Control Pill",
                            subtitle = if (overlayEnabled) "Active · Shows 'JARVIS is using phone' stop pill" else "Tap to allow display over other apps",
                            granted = overlayEnabled,
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            tag = "toggle_overlay_permission"
                        )
                        HorizontalDivider(color = ObsidianBorder, modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "JARVIS Voice-Call Alarm",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (exactAlarmEnabled) "Exact lock-screen alarms ready" else "Tap to test full-screen incoming call",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onTestJarvisCallAlarm,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("test_jarvis_call_button")
                            ) {
                                Icon(Icons.Outlined.PhoneCallback, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Call", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // 4. WATCH FOLDER PICKER
                item {
                    SettingsSectionCard(
                        title = "Watch Folder Picker",
                        subtitle = "Select local folder (e.g. Jarvis_Uploads) for Auto-SEO YouTube uploads"
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = config.watchFolderName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (config.watchFolderUri.isNotBlank()) JarvisCyan else TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (config.watchFolderUri.isNotBlank()) "SAF folder linked & ready" else "Place video + .txt topic note inside",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = { folderPickerLauncher.launch(null) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("watch_folder_picker_button")
                            ) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Select")
                            }
                        }
                    }
                }

                // 5. LOCAL MEMORY MANAGER (ROOM)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Local Memory Manager",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "On-device Room SQLite · Injects only top matching snippet",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        IconButton(
                            onClick = { showAddMemoryDialog = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("add_memory_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Add local memory",
                                tint = JarvisCyan
                            )
                        }
                    }
                }

                if (memories.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ObsidianSurface)
                                .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
                                .padding(18.dp)
                        ) {
                            Text(
                                text = "No saved notes yet. Ask JARVIS \"Remember that...\" or tap + to add a local memory.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    items(memories, key = { it.id }) { memory ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ObsidianSurface)
                                .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
                                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = memory.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                if (memory.keywords.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = memory.keywords.split(",").take(5).joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteMemory(memory.id) },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("delete_memory_${memory.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.cd_delete_memory),
                                    tint = JarvisCrimson.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // 6. ABOUT / CREDITS
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp)
                            .testTag("about_credits_section")
                    ) {
                        Text(
                            text = stringResource(R.string.about_credits_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Zero-Token-Wastage · 2-Level Circular Fallback · Local Room Engine",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }

    if (showManualCodeDialog) {
        AlertDialog(
            onDismissRequest = { showManualCodeDialog = false },
            containerColor = ObsidianSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Paste OAuth Authorization Code") },
            text = {
                Column {
                    Text(
                        "If Google redirected to localhost or showed a code, paste it below to verify your YouTube channel.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualOAuthCode,
                        onValueChange = { manualOAuthCode = it },
                        singleLine = true,
                        placeholder = { Text("4/0A...", color = TextMuted) },
                        colors = jarvisTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val code = manualOAuthCode.trim()
                        if (code.isNotEmpty()) {
                            onManualOAuthCodeSubmit(code)
                            manualOAuthCode = ""
                            showManualCodeDialog = false
                        }
                    }
                ) {
                    Text("Verify", color = JarvisCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualCodeDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    if (showAddMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemoryDialog = false },
            containerColor = ObsidianSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Add Local Memory") },
            text = {
                OutlinedTextField(
                    value = newMemoryText,
                    onValueChange = { newMemoryText = it },
                    placeholder = { Text("e.g. Bike keys are on the top shelf", color = TextMuted) },
                    minLines = 2,
                    colors = jarvisTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_memory_dialog_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val note = newMemoryText.trim()
                        if (note.isNotEmpty()) {
                            onAddMemory(note)
                            newMemoryText = ""
                            showAddMemoryDialog = false
                        }
                    },
                    modifier = Modifier.testTag("confirm_add_memory_button")
                ) {
                    Text("Save", color = JarvisCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemoryDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ObsidianSurface)
            .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    tag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = TextSecondary) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(R.string.cd_toggle_key_visibility),
                    tint = TextMuted
                )
            }
        },
        colors = jarvisTextFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianBackground,
                checkedTrackColor = JarvisCyan,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = ObsidianSurfaceVariant
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun PermissionToggleRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                if (granted) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Enabled",
                        tint = JarvisEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = granted,
            onCheckedChange = { onClick() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianBackground,
                checkedTrackColor = JarvisEmerald,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = ObsidianSurfaceVariant
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun jarvisTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyan,
    unfocusedBorderColor = ObsidianBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    cursorColor = JarvisCyan,
    focusedContainerColor = ObsidianBackground.copy(alpha = 0.6f),
    unfocusedContainerColor = ObsidianBackground.copy(alpha = 0.4f)
)
