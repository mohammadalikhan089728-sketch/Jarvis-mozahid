package com.example.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Wakes the device via partial WakeLock and launches JarvisCallForegroundService
 * so the voice-call alarm rings reliably even when the screen is off or locked.
 */
class JarvisAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JARVIS:AlarmWakeLock"
        )
        wakeLock.acquire(15_000L)

        try {
            val alarmId = intent.getLongExtra(JarvisAlarmScheduler.EXTRA_ALARM_ID, -1L)
            val reason = intent.getStringExtra(JarvisAlarmScheduler.EXTRA_CALL_REASON)
                ?: "your scheduled task"
            val callType = intent.getStringExtra(JarvisAlarmScheduler.EXTRA_CALL_TYPE)
                ?: JarvisAlarmScheduler.CALL_TYPE_ALARM

            val serviceIntent = Intent(context, JarvisCallForegroundService::class.java).apply {
                action = JarvisCallForegroundService.ACTION_START_CALL
                putExtra(JarvisAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(JarvisAlarmScheduler.EXTRA_CALL_REASON, reason)
                putExtra(JarvisAlarmScheduler.EXTRA_CALL_TYPE, callType)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
