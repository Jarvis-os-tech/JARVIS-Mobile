package com.jarvis.os.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jarvis.os.sync.CloudMemorySyncWorker

/**
 * BroadcastReceiver responsible for reviving the JARVIS Ambient Voice daemon
 * automatically upon device reboot or package updates.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "BootReceiver triggered with action: $action")

        if (action == "com.jarvis.os.action.SYNC_MEMORY") {
            Log.i(TAG, "Triggering immediate memory sync from broadcast")
            CloudMemorySyncWorker.syncNow(context)
            return
        }

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // Android 14+ requires a visible app to start microphone capture.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.i(TAG, "Open JARVIS after reboot to enable Hey Jarvis")
                return
            }
            val serviceIntent = Intent(context, JarvisVoiceService::class.java).apply {
                this.action = JarvisVoiceService.ACTION_START
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i(TAG, "JarvisVoiceService started successfully from BootReceiver")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start JarvisVoiceService from BootReceiver: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
