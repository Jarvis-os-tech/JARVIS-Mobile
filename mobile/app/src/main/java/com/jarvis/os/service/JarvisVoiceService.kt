package com.jarvis.os.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.os.JarvisApplication
import com.jarvis.os.audio.AudioCaptureEngine
import com.jarvis.os.audio.AudioPlaybackEngine
import com.jarvis.os.audio.LocalFillerMixer
import com.jarvis.os.audio.VadDetector
import com.jarvis.os.audio.VadState
import com.jarvis.os.audio.WakeWordDetector
import com.jarvis.os.gemini.ConversationState
import com.jarvis.os.overlay.OverlayUiState
import com.jarvis.os.overlay.OverlayWindowManager
import com.jarvis.os.net.GeminiLiveClient
import com.jarvis.os.net.DeviceToolClient
import com.jarvis.os.sync.CloudMemorySyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * Core ambient foreground daemon for JARVIS Mobile.
 * Coordinates audio capture, voice activity detection, local filler reflex,
 * full-duplex Gemini Live streaming, and Siri-style floating HUD overlay visibility.
 */
class JarvisVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    private val captureEngine = AudioCaptureEngine()
    private val playbackEngine = AudioPlaybackEngine()
    private val vadDetector = VadDetector(energyThreshold = 650.0)
    private val fillerMixer = LocalFillerMixer()
    private val geminiClient = GeminiLiveClient()
    private val deviceTools by lazy { DeviceToolClient(this) }
    private var sessionIdentity = ""
    private val toolCalls = mutableSetOf<String>()
    private val toolResults = mutableMapOf<String, String>()
    private var pendingToolCalls = 0
    private var commandAfterWake: String? = null
    private var overlayManager: OverlayWindowManager? = null

    private val _currentState = MutableStateFlow(ConversationState.IDLE)
    val currentState: StateFlow<ConversationState> = _currentState.asStateFlow()
    val isWakeListening: Boolean
        get() = enabled && captureEngine.isCapturing && !activeSession && !closingForGoodbye

    private var idleTimeoutJob: Job? = null
    private val wakeDetector by lazy { WakeWordDetector(this) }
    private var startupJob: Job? = null
    @Volatile private var enabled = false
    @Volatile private var activeSession = false
    private val responseLock = Any()
    @Volatile private var suppressOldResponse = false
    @Volatile private var modelTurnFinished = false
    @Volatile private var closingForGoodbye = false
    private val userTranscriptBuilder = java.lang.StringBuilder()
    private val assistantTranscriptBuilder = java.lang.StringBuilder()
    private var goodbyeTimeout: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): JarvisVoiceService = this@JarvisVoiceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "JarvisVoiceService onCreate")
        acquireWakeLock()
        setupGeminiCallbacks()
        try {
            overlayManager = OverlayWindowManager(this).apply {
                attach()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize overlay window: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "JarvisVoiceService onStartCommand action=$action")

        when (action) {
            ACTION_START -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification("Starting Hey Jarvis"),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, buildNotification("Starting Hey Jarvis"))
                    }
                } catch (error: SecurityException) {
                    Log.w(TAG, "Open JARVIS to enable microphone standby", error)
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVoicePipeline()
            }
            ACTION_STOP -> {
                turnOffAssistant()
            }
            ACTION_TOGGLE -> {
                if (enabled) {
                    stopVoicePipeline()
                } else {
                    startVoicePipeline()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS::VoiceServiceWakeLock")?.apply {
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    private fun setupGeminiCallbacks() {
        playbackEngine.onPlaybackDrained = {
            serviceScope.launch {
                if (closingForGoodbye) {
                    if (!playbackEngine.hasPendingAudio) finishGoodbye()
                } else finishReplyWhenDrained()
            }
        }
        geminiClient.onConnectionStateChanged = { ready ->
            serviceScope.launch {
                if (ready && activeSession) {
                    updateState(ConversationState.LISTENING)
                    val command = commandAfterWake
                    commandAfterWake = null
                    geminiClient.sendText(command ?: "The user just said Hey Jarvis. Say a short greeting and ask how you can help. Do not say the wake phrase yourself.")
                    scheduleStandby(20000)
                } else if (!ready && activeSession) {
                    enterStandby()
                    updateNotification("Connection lost. Say Hey Jarvis to retry.")
                }
            }
        }
        geminiClient.onUserTranscript = { text ->
            synchronized(responseLock) {
                userTranscriptBuilder.append(text)
            }
        }

        geminiClient.onAssistantTranscript = { text ->
            synchronized(responseLock) {
                assistantTranscriptBuilder.append(text)
            }
        }

        geminiClient.onAudioReceived = audio@ { rawPcm ->
            if (!activeSession) return@audio
            synchronized(responseLock) {
                if (suppressOldResponse || !activeSession) return@audio
                modelTurnFinished = false
                playbackEngine.playPcm(fillerMixer.feedGeminiAudio(rawPcm))
            }

            serviceScope.launch {
                if (activeSession && !suppressOldResponse && playbackEngine.hasPendingAudio) {
                    idleTimeoutJob?.cancel()
                    updateState(ConversationState.SPEAKING)
                }
            }
        }

        geminiClient.onTurnComplete = {
            Log.d(TAG, "Model turn complete")
            val userText: String
            val assistantText: String
            synchronized(responseLock) {
                suppressOldResponse = false
                modelTurnFinished = true
                userText = userTranscriptBuilder.toString().trim()
                assistantText = assistantTranscriptBuilder.toString().trim()
                userTranscriptBuilder.setLength(0)
                assistantTranscriptBuilder.setLength(0)
            }
            if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
                Log.i(TAG, "Syncing conversational turn to memory - user: ${userText.take(50)}, assistant: ${assistantText.take(50)}")
                if (userText.isNotEmpty()) {
                    CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "user", userText)
                }
                if (assistantText.isNotEmpty()) {
                    CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "model", assistantText)
                }
                CloudMemorySyncWorker.syncNow(this@JarvisVoiceService)
            }
            finishReplyWhenDrained()
        }

        geminiClient.onInterrupted = {
            Log.i(TAG, "Server confirmed voice interruption")
            val assistantText: String
            synchronized(responseLock) {
                playbackEngine.cancel()
                fillerMixer.cancel()
                suppressOldResponse = false
                modelTurnFinished = false
                assistantText = assistantTranscriptBuilder.toString().trim()
                assistantTranscriptBuilder.setLength(0)
            }
            if (assistantText.isNotEmpty()) {
                CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "model", "$assistantText [interrupted]")
                CloudMemorySyncWorker.syncNow(this@JarvisVoiceService)
            }
            serviceScope.launch {
                if (activeSession) {
                    updateState(ConversationState.LISTENING)
                    scheduleStandby(30000)
                }
            }
        }

        geminiClient.onToolCall = { call ->
            if (call.name == "play_local_filler") {
                val contextType = call.args["context_type"] as? String ?: "general"
                val fillerAudio = fillerMixer.triggerFiller(contextType)
                synchronized(responseLock) {
                    if (activeSession && !suppressOldResponse) playbackEngine.playPcm(fillerAudio)
                }
                geminiClient.sendToolResponse(call.id, call.name, "{\"status\":\"filler_active\"}")
            } else {
                serviceScope.launch {
                    if (!activeSession) return@launch
                    toolResults[call.id]?.let { result ->
                        geminiClient.sendToolResponse(call.id, call.name, result)
                        return@launch
                    }
                    if (!toolCalls.add(call.id)) return@launch
                    val identity = sessionIdentity
                    pendingToolCalls++
                    idleTimeoutJob?.cancel()
                    updateState(ConversationState.THINKING)
                    try {
                        if (call.name == "phone_turn_off") {
                            geminiClient.sendToolResponse(call.id, call.name, """{"status":"off","device":"phone"}""")
                            turnOffAssistant()
                        } else if (call.name == "phone_end_conversation") {
                            geminiClient.sendToolResponse(call.id, call.name, """{"status":"standby","device":"phone"}""")
                            sayGoodbyeAndStandby()
                        } else if (call.name == "phone_enter_standby") {
                            geminiClient.sendToolResponse(call.id, call.name, """{"status":"standby","device":"phone"}""")
                            delay(500)
                            if (sessionIdentity == identity) enterStandby()
                        } else {
                            val result = withContext(Dispatchers.IO) { deviceTools.execute(call, "$identity:${call.id}") }
                            if (activeSession && sessionIdentity == identity) {
                                toolResults[call.id] = result
                                geminiClient.sendToolResponse(call.id, call.name, result)
                            }
                        }
                    } finally {
                        pendingToolCalls--
                        if (activeSession && sessionIdentity == identity) scheduleStandby(30000)
                    }
                }
            }
        }
    }

    private fun startVoicePipeline() {
        if (enabled) return
        if (com.jarvis.os.sync.QrPairingScanner.isPaired(this)) CloudMemorySyncWorker.schedule(this)
        // Connect to Gemini Live if not already connected
        val apiKey = com.jarvis.os.sync.ApiCredentials.gemini(this)

        if (apiKey.isBlank()) {
            updateState(ConversationState.ERROR)
            updateNotification("Add your Gemini key or scan your PC JARVIS QR code.")
            return
        }
        enabled = true
        updateNotification("Preparing Hey Jarvis…")
        startupJob = serviceScope.launch {
          try {
            withContext(Dispatchers.IO) { wakeDetector.prepare() }
            if (!enabled) return@launch
            enterStandby()
            val started = captureEngine.startCapture(serviceScope) { chunk ->
            if (!enabled) return@startCapture
            if (closingForGoodbye) return@startCapture
            if (!activeSession) {
                if (wakeDetector.process(chunk)) serviceScope.launch { activateConversation(apiKey) }
                return@startCapture
            }
            if (wakeDetector.processGoodbye(chunk)) {
                serviceScope.launch { sayGoodbyeAndStandby() }
                return@startCapture
            }
            // Voice activity detection
            val vadResult = vadDetector.process(chunk)

            when (vadResult.state) {
                VadState.SPEECH -> {
                    // Cut the speaker locally on deliberate user barge-in (sufficient duration and energy over speaker audio).
                    if (vadResult.speechFrameCount >= 10 && vadResult.energy >= 2500.0 && playbackEngine.hasPendingAudio) {
                        synchronized(responseLock) {
                            if (!suppressOldResponse && playbackEngine.hasPendingAudio) {
                                suppressOldResponse = true
                                playbackEngine.cancel()
                                fillerMixer.cancel()
                                Log.i(TAG, "Local voice interruption: speaker stopped; microphone stays active")
                            }
                        }
                    }
                    serviceScope.launch {
                        if (activeSession && _currentState.value != ConversationState.LISTENING) {
                            scheduleStandby(30000)
                            updateState(ConversationState.LISTENING)
                        }
                    }
                    geminiClient.sendAudio(chunk)
                }

                VadState.HANGOVER -> {
                    // Still within speech continuation window
                    geminiClient.sendAudio(chunk)
                }

                VadState.SILENCE -> {
                    // The server's automatic VAD needs silence to end the user's turn.
                    geminiClient.sendAudio(chunk)
                    serviceScope.launch { if (activeSession && _currentState.value == ConversationState.LISTENING) {
                        // User finished speaking, now waiting for response
                        updateState(ConversationState.THINKING)
                    } }
                }

                VadState.ATTACK -> {
                    // Attack onset, buffer or stream
                    geminiClient.sendAudio(chunk)
                }
            }
        }
            if (!started) {
                enabled = false
                updateState(ConversationState.ERROR)
                updateNotification("Microphone unavailable. Allow microphone access and retry.")
            }
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            enabled = false
            Log.e(TAG, "Wake listener failed", error)
            updateState(ConversationState.ERROR)
            updateNotification("Hey Jarvis unavailable. Restart the assistant.")
          }
        }
    }

    private fun activateConversation(apiKey: String) {
        if (!enabled || activeSession) return
        activeSession = true
        commandAfterWake = wakeDetector.consumeWakeCommand()
        closingForGoodbye = false
        wakeDetector.resetGoodbye()
        sessionIdentity = java.util.UUID.randomUUID().toString()
        toolCalls.clear()
        toolResults.clear()
        suppressOldResponse = false
        modelTurnFinished = false
        synchronized(responseLock) {
            userTranscriptBuilder.setLength(0)
            assistantTranscriptBuilder.setLength(0)
        }
        vadDetector.reset()
        Log.i(TAG, "Hey Jarvis detected; activating conversation")
        updateState(ConversationState.THINKING)
        val identity = sessionIdentity
        serviceScope.launch {
            val tools = withContext(Dispatchers.IO) { deviceTools.declarations() }
            if (activeSession && sessionIdentity == identity) {
                geminiClient.connect(apiKey, extraTools = tools, systemPrompt = DeviceToolClient.SYSTEM_PROMPT)
            }
        }
        scheduleStandby(30000)
    }

    private fun enterStandby() {
        val userText: String
        val assistantText: String
        synchronized(responseLock) {
            userText = userTranscriptBuilder.toString().trim()
            assistantText = assistantTranscriptBuilder.toString().trim()
            userTranscriptBuilder.setLength(0)
            assistantTranscriptBuilder.setLength(0)
        }
        if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
            if (userText.isNotEmpty()) {
                CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "user", userText)
            }
            if (assistantText.isNotEmpty()) {
                CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "model", assistantText)
            }
            CloudMemorySyncWorker.syncNow(this@JarvisVoiceService)
        }
        activeSession = false
        suppressOldResponse = false
        modelTurnFinished = false
        idleTimeoutJob?.cancel()
        geminiClient.disconnect()
        playbackEngine.cancel()
        fillerMixer.cancel()
        wakeDetector.reset()
        updateState(ConversationState.IDLE)
        if (enabled) updateNotification("Standby · Say Hey Jarvis")
        Log.i(TAG, "Standby: local wake listener; cloud audio disabled")
    }

    private fun scheduleStandby(delayMs: Long) {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            delay(delayMs)
            if (pendingToolCalls == 0) enterStandby()
        }
    }

    private fun finishReplyWhenDrained() {
        serviceScope.launch {
            if (activeSession && pendingToolCalls == 0 && modelTurnFinished && !suppressOldResponse && !playbackEngine.hasPendingAudio) {
                updateState(ConversationState.LISTENING)
                scheduleStandby(15000)
            }
        }
    }

    private fun stopVoicePipeline() {
        val userText: String
        val assistantText: String
        synchronized(responseLock) {
            userText = userTranscriptBuilder.toString().trim()
            assistantText = assistantTranscriptBuilder.toString().trim()
            userTranscriptBuilder.setLength(0)
            assistantTranscriptBuilder.setLength(0)
        }
        if (userText.isNotEmpty() || assistantText.isNotEmpty()) {
            if (userText.isNotEmpty()) {
                CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "user", userText)
            }
            if (assistantText.isNotEmpty()) {
                CloudMemorySyncWorker.enqueueTurn(this@JarvisVoiceService, "model", assistantText)
            }
            CloudMemorySyncWorker.syncNow(this@JarvisVoiceService)
        }
        enabled = false
        activeSession = false
        startupJob?.cancel()
        idleTimeoutJob?.cancel()
        captureEngine.stopCapture()
        playbackEngine.cancel()
        playbackEngine.release()
        fillerMixer.cancel()
        geminiClient.disconnect()
        updateState(ConversationState.IDLE)
        updateNotification("Assistant off · Enable Hey Jarvis to start")
    }

    fun turnOffAssistant() {
        Log.i(TAG, "Assistant turned off: microphone and wake detection stopped")
        stopVoicePipeline()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun sayGoodbyeAndStandby() {
        if (closingForGoodbye || !enabled) return
        closingForGoodbye = true
        activeSession = false
        suppressOldResponse = true
        idleTimeoutJob?.cancel()
        geminiClient.disconnect()
        synchronized(responseLock) {
            playbackEngine.cancel()
            fillerMixer.cancel()
            updateState(ConversationState.SPEAKING)
            val pcm = assets.open("goodbye.pcm").use { it.readBytes() }
            if (playbackEngine.playPcm(pcm) < 0) {
                finishGoodbye()
                return
            }
        }
        Log.i(TAG, "Local goodbye: hiding after farewell; wake listener remains enabled")
        goodbyeTimeout = serviceScope.launch {
            delay(2500)
            finishGoodbye()
        }
    }

    private fun finishGoodbye() {
        if (!closingForGoodbye) return
        closingForGoodbye = false
        goodbyeTimeout?.cancel()
        enterStandby()
        getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit()
            .putLong("goodbye_close_requested", System.currentTimeMillis()).apply()
    }

    private fun updateState(newState: ConversationState, rms: Float = 0f) {
        _currentState.value = newState
        val uiState = when (newState) {
            ConversationState.IDLE -> OverlayUiState.Idle
            ConversationState.LISTENING -> OverlayUiState.Listening(rms)
            ConversationState.THINKING -> OverlayUiState.Thinking("Reasoning...")
            ConversationState.SPEAKING -> OverlayUiState.Speaking(rms)
            ConversationState.ERROR -> OverlayUiState.Idle
        }
        overlayManager?.updateState(uiState)
        updateNotification("JARVIS: ${newState.name.lowercase().replaceFirstChar { it.uppercase() }}")
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            null
        }

        val builder = NotificationCompat.Builder(this, JarvisApplication.CHANNEL_ID_VOICE)
            .setContentTitle("JARVIS Ambient Voice")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit()
            .putString("voice_status", statusText).apply()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "JarvisVoiceService onDestroy")
        stopVoicePipeline()
        goodbyeTimeout?.cancel()
        wakeDetector.close()
        overlayManager?.detach()
        overlayManager = null
        releaseWakeLock()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "JarvisVoiceService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.os.action.START"
        const val ACTION_STOP = "com.jarvis.os.action.STOP"
        const val ACTION_TOGGLE = "com.jarvis.os.action.TOGGLE"
    }
}
