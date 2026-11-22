package com.example.control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.example.JarvisApplication
import com.example.R
import com.example.alarm.JarvisAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Smart WhatsApp & SMS Watcher (`NotificationWatchService`):
 * - Monitors incoming WhatsApp and SMS notifications continuously in the background.
 * - Proactive WhatsApp Alerts: When an urgent/important message arrives, triggers either a
 *   high-priority summary notification OR an Incoming JARVIS Voice Call (`FullScreenIntent`)
 *   to inform Mozahid Sir who messaged and what they said.
 * - Hands-Free Auto-Reply: When Auto-Reply is enabled, replies directly via `RemoteInput`
 *   without opening the app, posts a concise notification, and speaks a brief report.
 */
class NotificationWatchService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentlyHandledKeys = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in SUPPORTED_PACKAGES) return

        val app = application as? JarvisApplication ?: return
        val autoReplyEnabled = app.vault.isAutoReplyEnabled()
        val proactiveCallsEnabled = app.vault.isProactiveWhatsAppCallsEnabled()
        if (!autoReplyEnabled && !proactiveCallsEnabled) return

        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sender.isEmpty() || message.isEmpty()) return
        if (sender.equals("WhatsApp", ignoreCase = true) ||
            message.contains("new messages", ignoreCase = true) ||
            message.contains("Checking for", ignoreCase = true)
        ) return

        val dedupKey = "$pkg|$sender|$message"
        val now = System.currentTimeMillis()
        synchronized(recentlyHandledKeys) {
            val prev = recentlyHandledKeys[dedupKey]
            if (prev != null && now - prev < 45_000L) return
            recentlyHandledKeys[dedupKey] = now
            if (recentlyHandledKeys.size > 40) {
                recentlyHandledKeys.keys.firstOrNull()?.let { recentlyHandledKeys.remove(it) }
            }
        }

        val isWhatsApp = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"
        val isUrgent = isUrgentMessage(sender, message)

        scope.launch {
            val replyAction = findReplyAction(notification)
            val remoteInput = replyAction?.remoteInputs?.firstOrNull()

            // 1. Hands-Free Auto-Reply via RemoteInput
            if (autoReplyEnabled && replyAction != null && remoteInput != null) {
                val prompt = "Draft a very short, natural, polite 1-sentence reply on behalf of Mozahid " +
                    "to a message from $sender saying: \"$message\". Return ONLY the reply text."
                val generatedReply = app.fallbackApiClient.generateCompactText(
                    prompt = prompt,
                    systemInstruction = "Reply naturally in the sender's language (Hindi, Hinglish, or English) in 3 to 12 words. Output only the reply."
                )?.trim()?.removeSurrounding("\"")?.take(140)
                    ?: "I'm a bit busy right now, will get back to you shortly."

                val sent = dispatchRemoteInputReply(replyAction, remoteInput, generatedReply)
                if (sent) {
                    val report = "Sir, $sender messaged \"$message\", so I replied \"$generatedReply\"."
                    ExecutionController.postAutoReplyReport(report)
                    postSummaryNotification("JARVIS Auto-Replied to $sender", report)
                    app.voiceEngine.speakNeural(report)
                }
            }

            // 2. Proactive WhatsApp Alert (Incoming JARVIS Voice Call for urgent messages, or priority notification)
            if (isWhatsApp && proactiveCallsEnabled) {
                if (isUrgent) {
                    val callScript = "Mozahid Sir, urgent WhatsApp message from $sender. They said: $message."
                    ExecutionController.postAutoReplyReport(callScript)
                    JarvisAlarmScheduler.triggerImmediateCall(
                        context = applicationContext,
                        reason = callScript,
                        callType = JarvisAlarmScheduler.CALL_TYPE_WHATSAPP_ALERT
                    )
                } else {
                    postSummaryNotification(
                        title = "WhatsApp Alert · $sender",
                        body = message
                    )
                }
            }
        }
    }

    private fun isUrgentMessage(sender: String, message: String): Boolean {
        val combined = "${sender.lowercase()} ${message.lowercase()}"
        return URGENT_KEYWORDS.any { keyword -> combined.contains(keyword) }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        return notification.actions?.firstOrNull { action ->
            val inputs = action.remoteInputs
            inputs != null && inputs.isNotEmpty()
        }
    }

    private fun dispatchRemoteInputReply(
        action: Notification.Action,
        remoteInput: RemoteInput,
        replyText: String
    ): Boolean {
        return try {
            val intent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
            action.actionIntent.send(applicationContext, 0, intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun postSummaryNotification(title: String, body: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "JARVIS WhatsApp & Auto-Reply Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 10_000).toInt() + 5000, notif)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "jarvis_whatsapp_alerts_channel"

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )

        private val URGENT_KEYWORDS = listOf(
            "urgent", "emergency", "call me", "call asap", "asap", "important",
            "jaldi", "turant", "call karo", "phone uthao", "help", "abhi"
        )

        fun isEnabled(context: Context): Boolean {
            val listeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val expected = ComponentName(context, NotificationWatchService::class.java).flattenToString()
            return listeners.contains(expected, ignoreCase = true) ||
                listeners.contains(NotificationWatchService::class.java.simpleName, ignoreCase = true)
        }
    }
}
