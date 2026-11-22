package com.example.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.db.MemoryRepository

object JarvisAlarmScheduler {

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_CALL_REASON = "extra_call_reason"
    const val EXTRA_CALL_TYPE = "extra_call_type"

    const val CALL_TYPE_ALARM = "alarm"
    const val CALL_TYPE_YOUTUBE_ADVISOR = "youtube_advisor"
    const val CALL_TYPE_WHATSAPP_ALERT = "whatsapp_alert"

    fun canScheduleExactAlarms(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }

    suspend fun scheduleVoiceCallAlarm(
        context: Context,
        repository: MemoryRepository,
        delayMinutes: Int,
        reason: String
    ): Long {
        val safeDelayMs = delayMinutes.coerceAtLeast(1) * 60_000L
        val triggerAt = System.currentTimeMillis() + safeDelayMs
        val cleanReason = reason.trim().ifEmpty { "your scheduled reminder" }
        val alarmId = repository.saveAlarm(triggerAt, cleanReason)

        scheduleSystemAlarm(context, alarmId.toInt(), triggerAt, cleanReason, CALL_TYPE_ALARM)
        return triggerAt
    }

    fun scheduleSystemAlarm(
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
        reason: String,
        callType: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, JarvisAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, requestCode.toLong())
            putExtra(EXTRA_CALL_REASON, reason)
            putExtra(EXTRA_CALL_TYPE, callType)
        }
        val operation = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = PendingIntent.getActivity(
            context,
            requestCode + 10_000,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
            alarmManager.setAlarmClock(clockInfo, operation)
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    /**
     * Immediately rings the full-screen JARVIS incoming call UI (used by Proactive YouTube Advisor
     * and the Settings test trigger).
     */
    fun triggerImmediateCall(
        context: Context,
        reason: String,
        callType: String = CALL_TYPE_ALARM
    ) {
        val serviceIntent = Intent(context, JarvisCallForegroundService::class.java).apply {
            action = JarvisCallForegroundService.ACTION_START_CALL
            putExtra(EXTRA_CALL_REASON, reason)
            putExtra(EXTRA_CALL_TYPE, callType)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
