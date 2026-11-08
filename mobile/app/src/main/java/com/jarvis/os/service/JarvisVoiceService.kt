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
import com.jarvis.os.audio.ManualVoiceInput
import com.jarvis.os.audio.AudioPlaybackEngine
import com.jarvis.os.audio.LocalFillerMixer
import com.jarvis.os.audio.VadDetector
import com.jarvis.os.audio.VadState
import com.jarvis.os.audio.WakeWordDetector
import com.jarvis.os.gemini.ConversationState
import com.jarvis.os.overlay.OverlayUiState
import com.jarvis.os.overlay.OverlayWindowManager
import com.jarvis.os.nothing.NothingGlyphController
import com.jarvis.os.net.GeminiLiveClient
import com.jarvis.os.net.DeviceToolClient
import com.jarvis.os.sync.CloudMemorySyncWorker
import com.jarvis.os.sync.ApiCredentials
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
    private val vadDetector = VadDetector(energyThreshold = 350.0)
    private val fillerMixer = LocalFillerMixer()
    private val geminiClient = GeminiLiveClient()
    private val manualInput = ManualVoiceInput(send = { frame ->
        when (frame) {
            ManualVoiceInput.Frame.Start -> geminiClient.sendActivity(true)
            ManualVoiceInput.Frame.End -> geminiClient.sendActivity(false)
            is ManualVoiceInput.Frame.Audio -> geminiClient.sendAudio(frame.pcm)
        }
    })
    private val deviceTools by lazy { DeviceToolClient(this) }
    private var sessionIdentity = ""
    private var voiceApiKey = ""
    private val toolCalls = mutableSetOf<String>()
    private val toolResults = mutableMapOf<String, String>()
    private var pendingToolCalls = 0
    private var commandAfterWake: String? = null
    private var overlayManager: OverlayWindowManager? = null
    private val glyphController by lazy { NothingGlyphController(this) }

    private val _currentState = MutableStateFlow(ConversationState.IDLE)
    val currentState: StateFlow<ConversationState> = _currentState.asStateFlow()
    val isWakeListening: Boolean
        get() = enabled && captureEngine.isCapturing && !activeSession && !closingForGoodbye

    private val wakeDetector by lazy { WakeWordDetector(this) }
    private var startupJob: Job? = null
    @Volatile private var enabled = false
    @Volatile private var activeSession = false
    private val responseLock = Any()
    @Volatile private var suppressOldResponse = false
    @Volatile private var modelTurnFinished = false
    @Volatile private var closingForGoodbye = false
    @Volatile private var lastUserSpeechAt = 0L
    @Volatile private var firstReplyAudioPending = false
    private val userTranscriptBuilder = java.lang.StringBuilder()
    private val assistantTranscriptBuilder = java.lang.StringBuilder()
    private var goodbyeTimeout: Job? = null
    private var healthJob: Job? = null
    private var recoveryJob: Job? = null
    @Volatile private var recoveryAttempts = 0
    private var lastMicRestartAt = 0L
    @Volatile private var micRestartFailures = 0
    @Volatile private var responsePending = false
    @Volatile private var replyInProgress = false
    @Volatile private var lastVoiceProgressAt = 0L
    @Volatile private var bargeAckAt = 0L

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
        glyphController.init()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "JarvisVoiceService onStartCommand action=$action")

        when (action) {
            ACTION_START -> {
                isRunning = true
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
                    isRunning = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVoicePipeline()
            }
            ACTION_STOP -> {
                turnOffAssistant()
            }
            ACTION_ACTIVATE -> {
                isRunning = true
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification("JARVIS Listening"),
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, buildNotification("JARVIS Listening"))
                    }
                } catch (error: SecurityException) {
                    Log.w(TAG, "Open JARVIS to enable microphone", error)
                }
                val apiKey = ApiCredentials.gemini(this)
                if (apiKey.isNotBlank()) {
                    serviceScope.launch {
                        if (!enabled) {
                            startVoicePipeline()
                        }
                        startupJob?.join()
                        activateConversation(apiKey)
                    }
                }
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
        playbackEngine.onPlaybackFailure = {
            serviceScope.launch {
                if (enabled && activeSession && !closingForGoodbye) {
                    responsePending = false; replyInProgress = false
                    updateState(ConversationState.ERROR)
                    updateNotification("Speaker unavailable. Check media output, then ask again.")
                }
            }
        }
        playbackEngine.onPlaybackDrained = {
            serviceScope.launch {
                if (closingForGoodbye) {
                    if (!playbackEngine.hasPendingAudio) finishGoodbye()
                } else finishReplyWhenDrained()
            }
        }
        geminiClient.onConnectionStateChanged = { ready ->
            val eventSession = sessionIdentity
            serviceScope.launch {
                if (eventSession != sessionIdentity) return@launch
                if (ready && activeSession) {
                    updateState(ConversationState.LISTENING)
                    val command = commandAfterWake
                    commandAfterWake = null
                    val earlySpeech = try { manualInput.connected() } catch (_: ManualVoiceInput.TransportFailure) {
                        recoverConversation("Voice input could not upload")
                        return@launch
                    }
                    lastVoiceProgressAt = android.os.SystemClock.elapsedRealtime()
                    if (!earlySpeech) {
                        responsePending = true
                        firstReplyAudioPending = true
                        geminiClient.sendText(command ?: "The user just said Hey Jarvis. Say a short greeting and ask how you can help. Do not say the wake phrase yourself.")
                    } else Log.i(TAG, "Early user speech delivered; initial greeting skipped")
                } else if (!ready && activeSession && !closingForGoodbye) {
                    if (geminiClient.recoverableFailure) recoverConversation("Voice connection interrupted")
                    else {
                        enterStandby()
                        updateNotification("Voice setup failed. Check your key and internet connection, then retry.")
                    }
                }
            }
        }
        geminiClient.onUserTranscript = { text ->
            synchronized(responseLock) {
                if (activeSession) userTranscriptBuilder.append(text)
            }
        }

        geminiClient.onAssistantTranscript = { text ->
            synchronized(responseLock) {
                if (activeSession) assistantTranscriptBuilder.append(text)
            }
        }

        geminiClient.onAudioReceived = audio@ { rawPcm ->
            if (!activeSession) return@audio
            synchronized(responseLock) {
                if (suppressOldResponse || !activeSession) return@audio
                modelTurnFinished = false
                responsePending = false
                replyInProgress = true
                recoveryAttempts = 0
                lastVoiceProgressAt = android.os.SystemClock.elapsedRealtime()
                if (firstReplyAudioPending && lastUserSpeechAt > 0) {
                    firstReplyAudioPending = false
                    Log.i(TAG, "First reply audio ${android.os.SystemClock.elapsedRealtime() - lastUserSpeechAt}ms after last user speech frame")
                }
                val replacingFiller = fillerMixer.isFillerActive
                val speech = fillerMixer.feedGeminiAudio(rawPcm)
                if (replacingFiller) playbackEngine.cancel()
                playbackEngine.playPcm(speech)
            }

            serviceScope.launch {
                if (activeSession && !suppressOldResponse && playbackEngine.hasPendingAudio) {
                    updateState(ConversationState.SPEAKING)
                }
            }
        }

        geminiClient.onTurnComplete = completed@ {
            if (!activeSession || closingForGoodbye) return@completed
            Log.d(TAG, "Model turn complete")
            val userText: String
            val assistantText: String
            synchronized(responseLock) {
                suppressOldResponse = false
                modelTurnFinished = true
                bargeAckAt = 0L
                replyInProgress = false
                if (!firstReplyAudioPending) responsePending = false
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
            finishReplyWhenDrained()
        }

        geminiClient.onInterrupted = interrupted@ {
            if (!activeSession || closingForGoodbye) return@interrupted
            Log.i(TAG, "Server confirmed voice interruption")
            val assistantText: String
            synchronized(responseLock) {
                playbackEngine.cancel()
                fillerMixer.cancel()
                suppressOldResponse = false
                bargeAckAt = 0L
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
                }
            }
        }

        geminiClient.onToolCall = tool@ { call ->
            val callSession = sessionIdentity
            if (!activeSession || closingForGoodbye) return@tool
            if (call.name == "play_local_filler") {
                val contextType = call.args["context_type"] as? String ?: "general"
                val fillerAudio = fillerMixer.triggerFiller(contextType)
                synchronized(responseLock) {
                    if (activeSession && !suppressOldResponse) playbackEngine.playPcm(fillerAudio)
                }
                geminiClient.sendToolResponse(call.id, call.name, "{\"status\":\"filler_active\"}")
            } else {
                serviceScope.launch {
                    if (!activeSession || sessionIdentity != callSession) return@launch
                    toolResults[call.id]?.let { result ->
                        geminiClient.sendToolResponse(call.id, call.name, result)
                        return@launch
                    }
                    if (!toolCalls.add(call.id)) return@launch
                    val identity = sessionIdentity
                    pendingToolCalls++
                    lastVoiceProgressAt = android.os.SystemClock.elapsedRealtime()
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
                                responsePending = true
                                lastVoiceProgressAt = android.os.SystemClock.elapsedRealtime()
                            }
                        }
                    } finally {
                        pendingToolCalls--
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
        voiceApiKey = apiKey
        geminiClient.prewarm()
        lastMicRestartAt = 0L
        micRestartFailures = 0
        captureEngine.onFailureListener = { error ->
            Log.w(TAG, "Microphone capture engine error", error)
            if (activeSession) {
                serviceScope.launch { recoverConversation("Microphone stream error") }
            }
        }
        enabled = true
        serviceScope.launch(Dispatchers.IO) { deviceTools.refreshPcTools() }
        updateNotification("Preparing Hey Jarvis…")
        startupJob = serviceScope.launch {
          try {
            withContext(Dispatchers.IO) { wakeDetector.prepare() }
            if (!enabled) return@launch
            enterStandby()
            val started = captureEngine.startCapture(serviceScope, ::processMicrophoneChunk)
            if (started) startHealthMonitor()
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

    private fun processMicrophoneChunk(chunk: ByteArray) {
        if (!enabled || closingForGoodbye) return
        if (!activeSession) {
            vadDetector.observeBackground(chunk)
            if (wakeDetector.process(chunk)) serviceScope.launch { activateConversation(voiceApiKey) }
            return
        }
        if (!playbackEngine.hasPendingAudio && wakeDetector.processGoodbye(chunk)) {
            serviceScope.launch { sayGoodbyeAndStandby() }
            return
        }
        val vad = vadDetector.process(chunk)
        val speech = vad.state == VadState.SPEECH
        val playing = playbackEngine.hasPendingAudio
        val deliberate = vad.speechFrameCount >= 10 && vad.energy >= 2500.0
        val opening = !manualInput.turnOpen && speech && (!playing || deliberate)
        if (opening) {
            synchronized(responseLock) {
                if (playing || responsePending || replyInProgress) {
                    // A completed server turn needs no interruption acknowledgement.
                    suppressOldResponse = !modelTurnFinished
                    bargeAckAt = if (suppressOldResponse) android.os.SystemClock.elapsedRealtime() else 0L
                    playbackEngine.cancel(); fillerMixer.cancel()
                }
                responsePending = false; replyInProgress = false
                firstReplyAudioPending = true
            }
        }
        if (speech && (!playing || deliberate)) lastUserSpeechAt = android.os.SystemClock.elapsedRealtime()
        try {
            val result = manualInput.process(chunk, speech, playing, deliberate)
            if (result.opened) serviceScope.launch {
                if (activeSession) updateState(ConversationState.LISTENING)
            }
            if (result.closed) {
                responsePending = true
                lastVoiceProgressAt = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "User speech end sent explicitly")
                serviceScope.launch { if (activeSession) updateState(ConversationState.THINKING) }
            }
        } catch (_: ManualVoiceInput.TransportFailure) {
            serviceScope.launch { recoverConversation("Voice input backlog or upload failure") }
        }
    }

    private fun startHealthMonitor() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            while (enabled) {
                delay(500)
                val now = android.os.SystemClock.elapsedRealtime()
                if (captureEngine.isCapturing && now - captureEngine.lastFrameAt < 3000) {
                    micRestartFailures = 0
                } else if (!captureEngine.isCapturing || now - captureEngine.lastFrameAt > 6000) {
                    micRestartFailures++
                    if (micRestartFailures >= 4) {
                        Log.e(TAG, "Microphone capture permanently failed after $micRestartFailures consecutive restart attempts")
                        stopVoicePipeline()
                        isRunning = false
                        updateState(ConversationState.ERROR)
                        updateNotification("Microphone unavailable. Check microphone permission and tap Start.")
                        return@launch
                    }
                    lastMicRestartAt = now
                    Log.w(TAG, "Audio frame stalled or capture stopped; restarting capture (attempt $micRestartFailures/3)")
                    captureEngine.stopCapture()
                    delay(300)
                    if (!captureEngine.startCapture(serviceScope, ::processMicrophoneChunk)) {
                        if (micRestartFailures >= 3) {
                            stopVoicePipeline()
                            isRunning = false
                            updateState(ConversationState.ERROR)
                            updateNotification("Microphone unavailable. Open JARVIS to retry.")
                            return@launch
                        }
                    } else if (activeSession) {
                        recoverConversation("Microphone stream restarted")
                    }
                }
                if (!activeSession || !geminiClient.connected || pendingToolCalls > 0 || manualInput.turnOpen || closingForGoodbye) continue
                if (bargeAckAt > 0 && now - bargeAckAt > 4000) {
                    recoverConversation("Interruption acknowledgement timed out")
                } else if ((responsePending || replyInProgress) && now - lastVoiceProgressAt > 12000) {
                    recoverConversation("Voice response stalled")
                }
            }
        }
    }

    private fun recoverConversation(reason: String) {
        if (!enabled || !activeSession || closingForGoodbye || recoveryJob?.isActive == true) return
        Log.w(TAG, "Recovering voice: $reason")
        val key = com.jarvis.os.sync.ApiCredentials.gemini(this)
        val identity = sessionIdentity
        enterStandby(reconnecting = true)
        if (++recoveryAttempts > 3) {
            updateState(ConversationState.ERROR)
            updateNotification("Voice connection unavailable. Check internet or your key, then say Hey Jarvis.")
            return
        }
        updateState(ConversationState.THINKING)
        updateNotification("Reconnecting voice · previous actions will not be repeated")
        recoveryJob = serviceScope.launch {
            // Let any already-issued action finish; never replay it after a connection loss.
            while (pendingToolCalls > 0 && enabled) delay(200)
            delay(500L * recoveryAttempts)
            if (enabled && !activeSession && !closingForGoodbye && sessionIdentity == identity) activateConversation(key, recovering = true)
        }
    }

    private fun activateConversation(apiKey: String, recovering: Boolean = false) {
        if (!enabled || activeSession || pendingToolCalls > 0) return
        if (!recovering) recoveryAttempts = 0
        commandAfterWake = if (recovering) "The voice connection has recovered. Briefly say you are ready. Do not repeat any earlier command or action; wait for a new user request."
            else wakeDetector.consumeWakeCommand()
        manualInput.start()
        responsePending = false; replyInProgress = false; bargeAckAt = 0L
        lastUserSpeechAt = 0L
        firstReplyAudioPending = false
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
        activeSession = true
        glyphController.onWakeWordDetected()
        Log.i(TAG, "Hey Jarvis detected; activating conversation")
        updateState(ConversationState.THINKING)
        val identity = sessionIdentity
        val activatedAt = android.os.SystemClock.elapsedRealtime()
        serviceScope.launch(Dispatchers.IO) { deviceTools.refreshPcTools() }
        serviceScope.launch {
            val setup = withContext(Dispatchers.IO) {
                if (com.jarvis.os.sync.QrPairingScanner.isPaired(this@JarvisVoiceService)) {
                    try { CloudMemorySyncWorker.syncNow(this@JarvisVoiceService) }
                    catch (error: Exception) { Log.w(TAG, "Background memory sync schedule notice", error) }
                }
                val memoryContext = try {
                    com.jarvis.os.sync.ContinuousMemory(this@JarvisVoiceService).use { it.promptContext() }
                } catch (error: Exception) {
                    Log.w(TAG, "Local memory read fallback", error)
                    ""
                }
                deviceTools.declarations(refreshPc = false) to memoryContext
            }
            val tools = setup.first
            if (activeSession && sessionIdentity == identity) {
                Log.i(TAG, "Local activation setup completed in ${android.os.SystemClock.elapsedRealtime() - activatedAt}ms")
                geminiClient.connect(apiKey, extraTools = tools, systemPrompt = DeviceToolClient.SYSTEM_PROMPT + setup.second)
            }
        }
    }

    private fun enterStandby(reconnecting: Boolean = false) {
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
        manualInput.reset()
        responsePending = false; replyInProgress = false; bargeAckAt = 0L
        suppressOldResponse = false
        modelTurnFinished = false
        geminiClient.disconnect()
        playbackEngine.cancel()
        fillerMixer.cancel()
        wakeDetector.reset()
        if (!reconnecting) {
            recoveryJob?.cancel()
            updateState(ConversationState.IDLE)
            if (enabled) updateNotification("Standby · Say Hey Jarvis")
        }
        Log.i(TAG, if (reconnecting) "Voice session reset for recovery" else "Standby: local wake listener; cloud audio disabled")
    }

    private fun finishReplyWhenDrained() {
        serviceScope.launch {
            if (activeSession && pendingToolCalls == 0 && modelTurnFinished && !suppressOldResponse && !playbackEngine.hasPendingAudio) {
                updateState(ConversationState.LISTENING)
            }
        }
    }

    private fun stopVoicePipeline() {
        enabled = false
        activeSession = false
        healthJob?.cancel(); recoveryJob?.cancel()
        manualInput.reset()
        responsePending = false; replyInProgress = false; bargeAckAt = 0L
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
        voiceApiKey = ""
        startupJob?.cancel()
        captureEngine.onFailureListener = null
        captureEngine.stopCapture()
        micRestartFailures = 0
        playbackEngine.cancel()
        playbackEngine.release()
        fillerMixer.cancel()
        geminiClient.disconnect()
        updateState(ConversationState.IDLE)
        updateNotification("Assistant off · Enable Hey Jarvis to start")
    }

    fun turnOffAssistant() {
        Log.i(TAG, "Assistant turned off: microphone and wake detection stopped")
        isRunning = false
        stopVoicePipeline()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun sayGoodbyeAndStandby() {
        if (closingForGoodbye || !enabled) return
        closingForGoodbye = true
        activeSession = false
        suppressOldResponse = true
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
        when (newState) {
            ConversationState.IDLE -> glyphController.onIdle()
            ConversationState.LISTENING -> glyphController.onListening()
            ConversationState.THINKING -> glyphController.onThinking()
            ConversationState.SPEAKING -> glyphController.onSpeaking()
            ConversationState.ERROR -> glyphController.onIdle()
        }
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
        isRunning = false
        stopVoicePipeline()
        goodbyeTimeout?.cancel()
        wakeDetector.close()
        overlayManager?.detach()
        overlayManager = null
        glyphController.destroy()
        releaseWakeLock()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "JarvisVoiceService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.os.action.START"
        const val ACTION_STOP = "com.jarvis.os.action.STOP"
        const val ACTION_TOGGLE = "com.jarvis.os.action.TOGGLE"
        const val ACTION_ACTIVATE = "com.jarvis.os.action.ACTIVATE"

        @Volatile
        var isRunning: Boolean = false
    }
}
