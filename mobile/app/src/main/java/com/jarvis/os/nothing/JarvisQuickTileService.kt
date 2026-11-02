package com.jarvis.os.nothing

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.jarvis.os.service.JarvisVoiceService

/**
 * Quick Settings Tile for Nothing OS and Android system shade.
 * Enables one-tap toggling of "Hey Jarvis" standby from the notification panel,
 * Nothing OS expanded quick settings, or Nothing lock screen widgets.
 */
class JarvisQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val running = JarvisVoiceService.isRunning
        val intent = Intent(this, JarvisVoiceService::class.java).apply {
            action = if (running) JarvisVoiceService.ACTION_STOP else JarvisVoiceService.ACTION_START
        }
        if (!running) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            startService(intent)
        }
        updateTileState(!running)
    }

    private fun updateTileState(overrideRunning: Boolean? = null) {
        val isRunning = overrideRunning ?: JarvisVoiceService.isRunning
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Hey Jarvis"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) "Active Standby" else "Paused"
        }
        tile.updateTile()
    }
}
