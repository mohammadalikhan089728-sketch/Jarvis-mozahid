package com.example

import android.app.Application
import com.example.ai.CircularFallbackEngine
import com.example.ai.JarvisOrchestrator
import com.example.ai.VoiceEngine
import com.example.control.DeviceActionHelper
import com.example.control.FloatingPillManager
import com.example.data.SecureVault
import com.example.data.db.JarvisDatabase
import com.example.data.db.MemoryRepository
import com.example.youtube.YouTubeAnalyticsWorker
import com.example.youtube.YouTubeOAuthManager
import com.example.youtube.YouTubeUploader

/**
 * Lightweight Application container using clean constructor injection (zero heavy DI overhead).
 */
class JarvisApplication : Application() {

    lateinit var vault: SecureVault
        private set

    lateinit var database: JarvisDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var voiceEngine: VoiceEngine
        private set

    lateinit var fallbackEngine: CircularFallbackEngine
        private set

    lateinit var floatingPill: FloatingPillManager
        private set

    lateinit var deviceActions: DeviceActionHelper
        private set

    lateinit var youtubeOAuth: YouTubeOAuthManager
        private set

    lateinit var youtubeUploader: YouTubeUploader
        private set

    lateinit var orchestrator: JarvisOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        vault = SecureVault(this)
        database = JarvisDatabase.getInstance(this)
        memoryRepository = MemoryRepository(database.memoryDao(), database.alarmDao())
        voiceEngine = VoiceEngine(this)
        fallbackEngine = CircularFallbackEngine(vault, memoryRepository)
        floatingPill = FloatingPillManager(this)
        deviceActions = DeviceActionHelper(this)
        youtubeOAuth = YouTubeOAuthManager(vault)
        youtubeUploader = YouTubeUploader(this, vault, youtubeOAuth, fallbackEngine)
        orchestrator = JarvisOrchestrator(
            context = this,
            fallbackEngine = fallbackEngine,
            voiceEngine = voiceEngine,
            memoryRepository = memoryRepository,
            deviceActions = deviceActions,
            floatingPill = floatingPill,
            youtubeUploader = youtubeUploader
        )

        if (vault.isProactiveYouTubeCallsEnabled()) {
            YouTubeAnalyticsWorker.schedule(this)
        }
    }
}
