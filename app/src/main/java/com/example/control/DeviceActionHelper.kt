package com.example.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * Executes direct device actions, app launching, calling, WhatsApp/Instagram/SMS direct sending,
 * hardware toggles (volume, mute, flashlight, battery), and standard system timers.
 */
class DeviceActionHelper(private val context: Context) {

    fun openApp(appNameOrPackage: String): String {
        val query = appNameOrPackage.trim().lowercase()
        val pm = context.packageManager

        // Direct package match
        pm.getLaunchIntentForPackage(appNameOrPackage.trim())?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "done"
        }

        // Known friendly aliases
        val knownMap = mapOf(
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "messages" to "com.google.android.apps.messaging",
            "settings" to "com.android.settings",
            "gmail" to "com.google.android.gm",
            "camera" to "com.android.camera2"
        )
        knownMap[query]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "done"
            }
        }

        // Search all launchable apps
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val matched = apps.firstOrNull { resolveInfo ->
            val label = resolveInfo.loadLabel(pm).toString().lowercase()
            label == query || label.contains(query) || resolveInfo.activityInfo.packageName.lowercase().contains(query)
        }
        if (matched != null) {
            val launch = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                return "done"
            }
        }
        return "error: app '$appNameOrPackage' not found"
    }

    fun callContact(target: String): String {
        val number = resolvePhoneNumber(target) ?: return "error: contact '$target' not found"
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "done"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    suspend fun whatsappSend(contact: String, message: String): String {
        val phone = resolvePhoneNumber(contact)?.replace(Regex("[^0-9+]"), "")
        if (!phone.isNullOrBlank()) {
            val encoded = URLEncoder.encode(message, "UTF-8")
            val digits = phone.removePrefix("+")
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=$encoded")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                delay(1400L)
                val svc = ControlService.instance
                if (svc != null && svc.clickSendButtonDirectly()) {
                    "done"
                } else {
                    "opened WhatsApp chat with prefilled message; call ui_read and ui_click Send"
                }
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        // Launch WhatsApp and guide autonomous Accessibility loop
        val opened = openApp("whatsapp")
        if (opened != "done") return opened
        delay(1000L)
        val svc = ControlService.instance ?: return "error: Accessibility ControlService not enabled"
        val searchResult = svc.uiClick(text = "Search")
        if (searchResult == "done") {
            delay(400L)
            svc.uiType(text = contact, pressSend = false)
            delay(700L)
            svc.uiClick(text = contact)
            delay(700L)
            svc.uiType(text = message, pressSend = true)
            return "done"
        }
        return "WhatsApp opened; call ui_read to find contact '$contact' and send '$message'"
    }

    suspend fun instagramDm(username: String, message: String): String {
        val opened = openApp("instagram")
        if (opened != "done") return opened
        delay(1200L)
        val svc = ControlService.instance ?: return "error: Accessibility ControlService not enabled"
        svc.uiClick(text = "Message")
        delay(700L)
        svc.uiClick(text = "Search")
        delay(400L)
        svc.uiType(text = username, pressSend = false)
        delay(800L)
        val tappedUser = svc.uiClick(text = username)
        if (tappedUser == "done") {
            delay(700L)
            svc.uiType(text = message, pressSend = true)
            return "done"
        }
        return "Instagram opened; use ui_read to select '$username' and send '$message'"
    }

    suspend fun smsSend(target: String, message: String): String {
        val number = resolvePhoneNumber(target) ?: return "error: contact '$target' not found"
        val hasSmsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPerm) {
            return try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(number, null, message, null, null)
                "done"
            } catch (e: Exception) {
                "error: ${e.message}"
            }
        }

        // Fallback via SMS intent + Accessibility direct Send
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            delay(1000L)
            if (ControlService.instance?.clickSendButtonDirectly() == true) {
                "done"
            } else {
                "SMS screen opened; use ui_click Send"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    fun deviceControl(action: String): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (action.lowercase().trim()) {
            "volume_up" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "done"
            }
            "volume_down" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "done"
            }
            "mute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "done"
            }
            "unmute" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                "done"
            }
            "flashlight_on" -> setFlashlight(true)
            "flashlight_off" -> setFlashlight(false)
            "battery" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                "done: battery is $pct% (${if (charging) "charging" else "not charging"})"
            }
            else -> "error: unknown device_control action '$action'"
        }
    }

    fun setDefaultTimer(seconds: Int, label: String): String {
        val safeSeconds = seconds.coerceAtLeast(1)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, safeSeconds)
            if (label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "done"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun setFlashlight(enabled: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "error: no flashlight hardware"
            cameraManager.setTorchMode(cameraId, enabled)
            "done"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun resolvePhoneNumber(target: String): String? {
        val trimmed = target.trim()
        if (trimmed.matches(Regex("^[+0-9][0-9\\s\\-()]{5,}$"))) {
            return trimmed
        }
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) return null

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$trimmed%")
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(1)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
