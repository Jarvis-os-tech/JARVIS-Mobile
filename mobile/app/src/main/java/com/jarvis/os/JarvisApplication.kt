package com.jarvis.os

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Main Application class for JARVIS Mobile.
 * Initializes notification channels and provides application-wide context.
 */
class JarvisApplication : Application() {

    companion object {
        const val CHANNEL_ID_VOICE = "jarvis_voice_channel"
        const val CHANNEL_NAME_VOICE = "JARVIS Ambient Voice Service"
        const val CHANNEL_DESC_VOICE = "Persistent notification channel for JARVIS background voice daemon"
        const val NOTIFICATION_ID_VOICE = 1001

        private var _instance: JarvisApplication? = null
        val instance: JarvisApplication
            get() = _instance ?: throw IllegalStateException("JarvisApplication is not initialized yet")

        val applicationContext: Context
            get() = instance.applicationContext
    }

    override fun onCreate() {
        super.onCreate()
        _instance = this
        createNotificationChannels()
    }

    /**
     * Creates notification channels required for foreground services on Android 8.0+ (API 26+).
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val voiceChannel = NotificationChannel(
                CHANNEL_ID_VOICE,
                CHANNEL_NAME_VOICE,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC_VOICE
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(voiceChannel)
        }
    }
}
