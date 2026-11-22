package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.assistant.JarvisVoiceInteractionSession
import com.example.ui.HomeScreen
import com.example.ui.JarvisViewModel
import com.example.ui.SettingsScreen
import com.example.ui.theme.JarvisTheme

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            JarvisTheme {
                JarvisAppNavigation(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        // 1. OAuth 2.0 Redirect URI Handler for YouTube Channel Verification
        val data = intent.data
        if (data != null && data.scheme == "com.aistudio.jarvis.mzhkqr" && data.path == "/oauth2redirect") {
            val authCode = data.getQueryParameter("code")
            if (!authCode.isNullOrBlank()) {
                viewModel.handleOAuthAuthorizationCode(authCode)
                return
            }
        }

        // 2. Digital Assistant Summon (ACTION_ASSIST, ACTION_VOICE_COMMAND, or VoiceInteractionSession)
        val isAssistantSummon = intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND ||
            intent.action == JarvisVoiceInteractionSession.ACTION_SUMMON_JARVIS ||
            intent.getBooleanExtra(JarvisVoiceInteractionSession.EXTRA_AUTO_LISTEN, false)

        if (isAssistantSummon) {
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                viewModel.startVoiceListening()
            }
        }
    }
}

@Composable
private fun JarvisAppNavigation(viewModel: JarvisViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current

    val assistantState by viewModel.assistantState.collectAsStateWithLifecycle()
    val subtitle by viewModel.subtitle.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val settingsBanner by viewModel.settingsBanner.collectAsStateWithLifecycle()
    val apiTestResult by viewModel.apiTestResult.collectAsStateWithLifecycle()
    val isTestingApi by viewModel.isTestingApi.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Handled gracefully */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotif = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotif) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME
    ) {
        composable(ROUTE_HOME) {
            HomeScreen(
                assistantState = assistantState,
                subtitle = subtitle,
                amplitude = amplitude,
                engineState = engineState,
                uploadProgress = uploadProgress,
                onOrbTap = { hasAudioPermission, requestAudioPermission ->
                    viewModel.onOrbTapped(
                        hasAudioPermission = hasAudioPermission,
                        requestAudioPermission = requestAudioPermission
                    )
                },
                onMicPermissionGranted = { viewModel.startVoiceListening() },
                onMicPermissionDenied = { viewModel.onMicPermissionDenied() },
                onSubmitTextCommand = { cmd -> viewModel.submitCommand(cmd) },
                onOpenSettings = {
                    viewModel.clearBanner()
                    navController.navigate(ROUTE_SETTINGS)
                }
            )
        }

        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                config = config,
                engineState = engineState,
                uploadProgress = uploadProgress,
                memories = memories,
                statusBanner = settingsBanner,
                apiTestResult = apiTestResult,
                isTestingApi = isTestingApi,
                onBack = { navController.popBackStack() },
                onSaveGeminiKeys = { k1, k2 -> viewModel.saveGeminiKeys(k1, k2) },
                onTestApiConnection = { k1, k2 -> viewModel.testApiConnection(k1, k2) },
                onSaveYouTubeCredentials = { apiKey, json ->
                    viewModel.saveYouTubeCredentials(apiKey, json)
                },
                onConnectYouTube = { viewModel.connectAndVerifyYouTube() },
                onManualOAuthCodeSubmit = { code -> viewModel.handleOAuthAuthorizationCode(code) },
                onUploadWatchFolderNow = { viewModel.enqueueBackgroundYouTubeUpload() },
                onRunYouTubeAnalyticsCheck = { viewModel.runYouTubeAnalyticsCheckNow() },
                onToggleAutoReply = { viewModel.setAutoReplyEnabled(it) },
                onToggleProactiveWhatsAppCalls = { viewModel.setProactiveWhatsAppCallsEnabled(it) },
                onToggleProactiveYouTubeCalls = { viewModel.setProactiveYouTubeCallsEnabled(it) },
                onToggleBackgroundKeepAlive = { viewModel.setBackgroundKeepAliveEnabled(it) },
                onWatchFolderPicked = { uri, name -> viewModel.saveWatchFolder(uri, name) },
                onAddMemory = { viewModel.addMemory(it) },
                onDeleteMemory = { viewModel.deleteMemory(it) },
                onTestJarvisCallAlarm = { viewModel.triggerTestJarvisCall() }
            )
        }
    }
}
