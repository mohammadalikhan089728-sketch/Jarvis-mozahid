package com.example.control

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hands-Free Notification Auto-Reply Service:
 * Listens for incoming WhatsApp and SMS notifications. When Auto-Reply is enabled in Settings,
 * crafts a brief reply using the active Gemini circular engine, sends it directly via RemoteInput
 * without opening the messaging app, and speaks a concise report to Mozahid Sir.
 */
class JarvisNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentlyHandledKeys = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in SUPPORTED_PACKAGES) return

        val app = application as? JarvisApplication ?: return
        if (!app.vault.isAutoReplyEnabled()) return

        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sender.isEmpty() || message.isEmpty()) return
        if (sender.equals("WhatsApp", ignoreCase = true) || message.contains("new messages", ignoreCase = true)) return

        val replyAction = findReplyAction(notification) ?: return
        val remoteInput = replyAction.remoteInputs?.firstOrNull() ?: return

        val dedupKey = "$pkg|$sender|$message"
        val now = System.currentTimeMillis()
        synchronized(recentlyHandledKeys) {
            val prev = recentlyHandledKeys[dedupKey]
            if (prev != null && now - prev < 45_000L) return
            recentlyHandledKeys[dedupKey] = now
            if (recentlyHandledKeys.size > 40) {
                val firstKey = recentlyHandledKeys.keys.firstOrNull()
                if (firstKey != null) recentlyHandledKeys.remove(firstKey)
            }
        }

        scope.launch {
            val prompt = "Draft a very short, natural, polite 1-sentence reply on behalf of Mozahid " +
                "to a message from $sender saying: \"$message\". Return ONLY the reply text, nothing else."
            val generatedReply = app.fallbackEngine.generateCompactText(
                prompt = prompt,
                systemInstruction = "Reply naturally in the same language (Hindi, Hinglish, or English) in 3 to 12 words. Output only the message."
            )?.trim()?.removeSurrounding("\"")
                ?.take(140)
                ?: "I'm a bit busy right now, will get back to you shortly."

            val sent = dispatchRemoteInputReply(replyAction, remoteInput, generatedReply)
            if (sent) {
                val report = "Sir, $sender messaged \"$message\", so I replied \"$generatedReply\"."
                ExecutionController.postAutoReplyReport(report)
                app.voiceEngine.speakNeural(report)
            }
        }
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )

        fun isEnabled(context: Context): Boolean {
            val listeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val expected = ComponentName(context, JarvisNotificationService::class.java).flattenToString()
            return listeners.contains(expected, ignoreCase = true) ||
                listeners.contains(JarvisNotificationService::class.java.simpleName, ignoreCase = true)
        }
    }
}
