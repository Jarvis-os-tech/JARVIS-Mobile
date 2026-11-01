package com.jarvis.os.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** Android-only actions. Desktop execution stays in the existing shared executor. */
class PhoneActions(private val context: Context) {
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun launch(intent: Intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun permission(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    private fun needed(name: String) = JSONObject().put("status", "permission_required").put("permission", name)
        .put("message", "Open JARVIS and enable the requested phone permission; do not claim this action succeeded")

    fun system(args: Map<String, Any?>): JSONObject {
        val action = args["action"] as? String ?: error("System action required")
        val result = JSONObject().put("device", "phone").put("action", action)
        when (action) {
            "status" -> {
                result.put("media_volume_percent", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                result.put("brightness_percent", Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255)
                result.put("can_change_brightness", Settings.System.canWrite(context))
                result.put("can_read_contacts", permission(Manifest.permission.READ_CONTACTS))
                result.put("can_place_calls", permission(Manifest.permission.CALL_PHONE))
                result.put("voice_navigation_enabled", PhoneNavigationService.available)
            }
            "set_volume" -> {
                val percent = PhoneActionRules.percent(args["percent"])
                val stream = when (args["stream"] as? String ?: "media") {
                    "media" -> AudioManager.STREAM_MUSIC
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> error("Use media, ring or alarm volume")
                }
                audio.setStreamVolume(stream, (audio.getStreamMaxVolume(stream) * percent + 50) / 100, AudioManager.FLAG_SHOW_UI)
                result.put("percent", audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream))
            }
            "volume_up", "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (action == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "set_brightness" -> {
                if (!Settings.System.canWrite(context)) return needed("Modify system settings")
                val percent = PhoneActionRules.percent(args["percent"])
                check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
                check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, maxOf(1, percent * 255 / 100)))
                result.put("percent", percent)
            }
            "flashlight_on", "flashlight_off" -> {
                if (!permission(Manifest.permission.CAMERA)) return needed("Camera for flashlight")
                val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: error("No flashlight available")
                cameras.setTorchMode(id, action == "flashlight_on")
            }
            "play_pause", "next_track", "previous_track" -> {
                val key = when (action) {
                    "next_track" -> KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous_track" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
            "open_settings" -> {
                val target = when (args["screen"] as? String ?: "general") {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "brightness_permission" -> Settings.ACTION_MANAGE_WRITE_SETTINGS
                    "general" -> Settings.ACTION_SETTINGS
                    else -> error("Unsupported settings screen")
                }
                val intent = Intent(target)
                if (target == Settings.ACTION_MANAGE_WRITE_SETTINGS) intent.data = Uri.parse("package:${context.packageName}")
                launch(intent)
                return result.put("status", "settings_opened").put("message", "Settings opened; a toggle has not been changed yet. Use phone navigation or let the user choose.")
            }
            "set_alarm" -> {
                val hour = (args["hour"] as? Number)?.toInt() ?: error("Hour required")
                val minute = (args["minute"] as? Number)?.toInt() ?: 0
                require(hour in 0..23 && minute in 0..59)
                launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_MESSAGE, args["label"] as? String ?: "JARVIS alarm")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return result.put("status", "alarm_requested")
            }
            "set_timer" -> {
                val seconds = (args["seconds"] as? Number)?.toInt() ?: error("Duration in seconds required")
                require(seconds in 1..86400)
                launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, args["label"] as? String ?: "JARVIS timer").putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return result.put("status", "timer_requested")
            }
            else -> error("Unsupported phone system action")
        }
        return result.put("status", "completed")
    }

    fun contacts(query: String): JSONObject {
        require(query.trim().length in 1..100) { "Say a contact name to look up" }
        if (!permission(Manifest.permission.READ_CONTACTS)) return needed("Contacts")
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val matches = JSONArray()
        val seen = mutableSetOf<String>()
        context.contentResolver.query(uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL), null, null, Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            while (cursor.moveToNext() && matches.length() < 8) {
                val number = cursor.getString(2) ?: continue
                if (!seen.add(cursor.getString(0) + ":" + number.filter { it.isDigit() })) continue
                val label = Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4)).toString()
                matches.put(JSONObject().put("contact_id", cursor.getString(0)).put("name", cursor.getString(1))
                    .put("number", number).put("label", label))
            }
        }
        return JSONObject().put("status", "completed").put("matches", matches).put("device", "phone")
    }

    fun call(args: Map<String, Any?>): JSONObject {
        val recipient = (args["recipient"] as? String)?.trim() ?: error("User-requested recipient required")
        val direct = PhoneActionRules.isNumber(recipient)
        var number = recipient
        if (!direct) {
            val lookup = contacts(recipient)
            if (lookup.optString("status") != "completed") return lookup
            val matches = lookup.getJSONArray("matches")
            val exact = (0 until matches.length()).map { matches.getJSONObject(it) }
                .filter { it.optString("name").equals(recipient, ignoreCase = true) }
            val choices = if (exact.isNotEmpty()) exact else (0 until matches.length()).map { matches.getJSONObject(it) }
            val label = args["label"] as? String
            val selected = if (label.isNullOrBlank()) choices else choices.filter { it.optString("label").equals(label, ignoreCase = true) }
            if (selected.isEmpty()) return JSONObject().put("status", "not_found").put("message", "No matching phone number. Ask the user for the name or number.")
            if (selected.size != 1) return JSONObject().put("status", "needs_clarification").put("matches", JSONArray(selected))
                .put("message", "Ask which contact or phone-number label to call. No call has been placed.")
            number = selected.single().getString("number")
        }
        number = PhoneActionRules.number(number)
        if (!permission(Manifest.permission.CALL_PHONE)) return needed("Phone calls")
        val prefs = context.getSharedPreferences("jarvis_call_requests", Context.MODE_PRIVATE)
        synchronized(CALL_LOCK) {
            val now = System.currentTimeMillis()
            if (prefs.getString("number", "") == number && now - prefs.getLong("time", 0) < 30000) {
                return JSONObject().put("status", "already_requested").put("message", "This call was already requested. Do not place it again.")
            }
            prefs.edit().putString("number", number).putLong("time", now).commit()
            launch(callIntent(number))
        }
        return JSONObject().put("device", "phone").put("status", "call_requested")
            .put("message", "Android received the outgoing call request. This does not prove the other person answered.")
    }

    companion object {
        private val CALL_LOCK = Any()
        fun callIntent(number: String): Intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", PhoneActionRules.number(number), null))
    }
}

object PhoneActionRules {
    fun percent(value: Any?): Int {
        val number = (value as? Number)?.toDouble() ?: error("Percentage required")
        require(number.isFinite() && number in 0.0..100.0) { "Use a percentage from 0 to 100" }
        return number.toInt()
    }
    fun isNumber(value: String): Boolean = value.isNotBlank() && value.all { it.isDigit() || it in "+ ()-." }
    fun number(value: String): String {
        require(isNumber(value)) { "Use a normal phone number, not a service or USSD code" }
        val clean = value.filter { it.isDigit() || it == '+' }
        require(Regex("^\\+?[0-9]{6,15}$").matches(clean)) { "Emergency or service codes must be handled in the phone dialer" }
        return clean
    }
}
