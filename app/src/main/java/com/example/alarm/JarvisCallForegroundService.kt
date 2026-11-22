package com.example.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Foreground Service managing the high-priority USE_FULL_SCREEN_INTENT incoming call notification,
 * device ringtone, and rhythmic vibration so JARVIS Call Alarms ring over the lock screen.
 */
class JarvisCallForegroundService : Service() {

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RINGING -> {
                stopRingtoneAndVibration()
                return START_NOT_STICKY
            }
            ACTION_END_CALL -> {
                stopRingtoneAndVibration()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val alarmId = intent?.getLongExtra(JarvisAlarmScheduler.EXTRA_ALARM_ID, -1L) ?: -1L
                val reason = intent?.getStringExtra(JarvisAlarmScheduler.EXTRA_CALL_REASON)
                    ?: "Study time"
                val callType = intent?.getStringExtra(JarvisAlarmScheduler.EXTRA_CALL_TYPE)
                    ?: JarvisAlarmScheduler.CALL_TYPE_ALARM

                createNotificationChannel()
                val notification = buildIncomingCallNotification(alarmId, reason, callType)
                startForeground(NOTIFICATION_ID, notification)

                startRingtoneAndVibration()
                launchFullScreenCallActivity(alarmId, reason, callType)
                return START_NOT_STICKY
            }
        }
    }

    private fun launchFullScreenCallActivity(alarmId: Long, reason: String, callType: String) {
        val callIntent = Intent(this, JarvisIncomingCallActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(JarvisAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(JarvisAlarmScheduler.EXTRA_CALL_REASON, reason)
            putExtra(JarvisAlarmScheduler.EXTRA_CALL_TYPE, callType)
        }
        runCatching { startActivity(callIntent) }
    }

    private fun buildIncomingCallNotification(
        alarmId: Long,
        reason: String,
        callType: String
    ): Notification {
        val fullScreenIntent = Intent(this, JarvisIncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(JarvisAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(JarvisAlarmScheduler.EXTRA_CALL_REASON, reason)
            putExtra(JarvisAlarmScheduler.EXTRA_CALL_TYPE, callType)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            201,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, JarvisCallForegroundService::class.java).apply {
            action = ACTION_END_CALL
        }
        val declinePendingIntent = PendingIntent.getService(
            this,
            202,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = when (callType) {
            JarvisAlarmScheduler.CALL_TYPE_YOUTUBE_ADVISOR -> "YouTube Channel Briefing"
            JarvisAlarmScheduler.CALL_TYPE_WHATSAPP_ALERT -> "Urgent WhatsApp Alert"
            else -> "Reminder: $reason"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.jarvis_calling_title))
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(0, "Answer", fullScreenPendingIntent)
            .addAction(0, "Decline", declinePendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Incoming Voice Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen incoming voice-call alarms and YouTube advisor briefings"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startRingtoneAndVibration() {
        stopRingtoneAndVibration()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val r = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                r.isLooping = true
            }
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
            ringtone = r
        } catch (_: Exception) {
        }

        try {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = vib
            val pattern = longArrayOf(0, 700, 900, 700, 900)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopRingtoneAndVibration() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        stopRingtoneAndVibration()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_voice_call_channel"
        const val NOTIFICATION_ID = 9001

        const val ACTION_START_CALL = "com.example.alarm.START_CALL"
        const val ACTION_STOP_RINGING = "com.example.alarm.STOP_RINGING"
        const val ACTION_END_CALL = "com.example.alarm.END_CALL"
    }
}
