package com.jarvis.os.nothing

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.nothing.ketchum.GlyphManager

/**
 * Controller for Nothing OS Glyph Interface.
 * Synchronizes Nothing Phone rear LED strips with JARVIS voice states:
 * - Idle/Standby: Off / Breathing pulse on wake
 * - Listening: Responsive A/C glyph illumination
 * - Thinking: Animated breathing sequence
 * - Speaking: Rhythmic voice pulsation
 *
 * Safely no-ops on non-Nothing devices or devices without Glyph hardware.
 */
class NothingGlyphController(private val context: Context) {

    private var glyphManager: GlyphManager? = null
    private var isConnected = false
    private var isSessionOpen = false

    companion object {
        private const val TAG = "NothingGlyphController"

        fun isNothingDevice(): Boolean {
            val manufacturer = Build.MANUFACTURER ?: ""
            val brand = Build.BRAND ?: ""
            return manufacturer.equals("Nothing", ignoreCase = true) ||
                   brand.equals("Nothing", ignoreCase = true)
        }
    }

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(componentName: ComponentName?) {
            Log.i(TAG, "Glyph service connected: $componentName")
            isConnected = true
            try {
                val registered = glyphManager?.register() ?: false
                Log.i(TAG, "GlyphManager register result: $registered")
                glyphManager?.openSession()
                isSessionOpen = true
                Log.i(TAG, "Glyph session opened successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed opening Glyph session: ${e.message}")
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName?) {
            Log.i(TAG, "Glyph service disconnected")
            isConnected = false
            isSessionOpen = false
        }
    }

    fun init() {
        if (!isNothingDevice()) {
            Log.d(TAG, "Device is not Nothing Phone; skipping Glyph initialization")
            return
        }

        try {
            glyphManager = GlyphManager.getInstance(context.applicationContext)
            glyphManager?.init(callback)
            Log.i(TAG, "GlyphManager initialized for Nothing device (${Build.MODEL})")
        } catch (e: Throwable) {
            Log.w(TAG, "GlyphManager initialization error: ${e.message}")
        }
    }

    fun onWakeWordDetected() {
        if (!isSessionOpen) return
        try {
            val builder = glyphManager?.glyphFrameBuilder ?: return
            val frame = builder
                .buildChannelA()
                .buildPeriod(500)
                .buildCycles(2)
                .buildInterval(100)
                .build()
            glyphManager?.animate(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Glyph wake error: ${e.message}")
        }
    }

    fun onListening() {
        if (!isSessionOpen) return
        try {
            val builder = glyphManager?.glyphFrameBuilder ?: return
            val frame = builder
                .buildChannelA()
                .buildPeriod(1200)
                .buildCycles(0)
                .build()
            glyphManager?.animate(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Glyph listening error: ${e.message}")
        }
    }

    fun onThinking() {
        if (!isSessionOpen) return
        try {
            val builder = glyphManager?.glyphFrameBuilder ?: return
            val frame = builder
                .buildChannelB()
                .buildPeriod(800)
                .buildCycles(0)
                .build()
            glyphManager?.animate(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Glyph thinking error: ${e.message}")
        }
    }

    fun onSpeaking() {
        if (!isSessionOpen) return
        try {
            val builder = glyphManager?.glyphFrameBuilder ?: return
            val frame = builder
                .buildChannelA()
                .buildChannelB()
                .buildPeriod(400)
                .buildCycles(0)
                .build()
            glyphManager?.animate(frame)
        } catch (e: Exception) {
            Log.w(TAG, "Glyph speaking error: ${e.message}")
        }
    }

    fun onIdle() {
        if (!isSessionOpen) return
        try {
            glyphManager?.turnOff()
        } catch (e: Exception) {
            Log.w(TAG, "Glyph idle turnOff error: ${e.message}")
        }
    }

    fun destroy() {
        if (!isNothingDevice()) return
        try {
            if (isSessionOpen) {
                glyphManager?.turnOff()
                glyphManager?.closeSession()
                isSessionOpen = false
            }
            glyphManager?.unInit()
            glyphManager = null
            isConnected = false
        } catch (e: Throwable) {
            Log.w(TAG, "Glyph teardown error: ${e.message}")
        }
    }
}
