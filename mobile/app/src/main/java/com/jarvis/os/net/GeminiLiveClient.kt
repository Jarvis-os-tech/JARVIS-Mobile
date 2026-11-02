package com.jarvis.os.net

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance full-duplex WebSocket client communicating directly with
 * Google's Gemini Live bidirectional API (BidiGenerateContent).
 */
class GeminiLiveClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val socketFactory: WebSocket.Factory = client,
    private val setupTimeoutMs: Long = 15000
) {
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var userDisconnected = false
    private var activeModelIndex = 0
    private var generation = 0L
    private var terminalHandled = false
    private var setupTimeout: java.util.concurrent.ScheduledFuture<*>? = null

    @Synchronized private fun isCurrent(attempt: Long): Boolean =
        attempt == generation && !userDisconnected && !terminalHandled

    private var currentApiKey: String = ""
    private var currentVoice: String = "Aoede"
    private var currentSystemPrompt: String = ""
    private var currentExtraTools: org.json.JSONArray = org.json.JSONArray()

    var onAudioReceived: ((ByteArray) -> Unit)? = null
    var onUserTranscript: ((String) -> Unit)? = null
    var onAssistantTranscript: ((String) -> Unit)? = null
    var onTurnComplete: (() -> Unit)? = null
    var onInterrupted: (() -> Unit)? = null
    var onToolCall: ((FunctionCall) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean) -> Unit)? = null

    val connected: Boolean
        get() = isConnected.get()

    /**
     * Connect to the Gemini Live WebSocket endpoint and send setup handshake.
     */
    @Synchronized
    fun connect(
        apiKey: String,
        model: String = LiveSessionModels.DEFAULT_MODEL,
        voice: String = "Aoede",
        systemPrompt: String = "You are JARVIS, a helpful and hyper-intelligent autonomous voice assistant.",
        extraTools: org.json.JSONArray = org.json.JSONArray()
    ) {
        if (isConnected.get() || isConnecting.get()) {
            Log.w(TAG, "Already connected or connecting to Gemini Live")
            return
        }

        userDisconnected = false
        currentApiKey = apiKey
        currentVoice = voice
        currentSystemPrompt = systemPrompt
        currentExtraTools = extraTools

        val requestedIndex = LiveSessionModels.SUPPORTED_LIVE_MODELS.indexOf(model)
        activeModelIndex = if (requestedIndex >= 0) requestedIndex else 0

        startSessionWebSocket()
    }

    @Synchronized
    private fun startSessionWebSocket() {
        val attempt = ++generation
        terminalHandled = false
        setupTimeout?.cancel(false)
        val targetModel = LiveSessionModels.SUPPORTED_LIVE_MODELS.getOrElse(activeModelIndex) {
            LiveSessionModels.DEFAULT_MODEL
        }
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        isConnecting.set(true)
        Log.i(TAG, "Initiating Gemini Live WebSocket with model '$targetModel' (candidate ${activeModelIndex + 1}/${LiveSessionModels.SUPPORTED_LIVE_MODELS.size})")

        webSocket = socketFactory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(attempt)) { webSocket.cancel(); return }
                Log.i(TAG, "Gemini Live WebSocket opened successfully. Sending setup packet for model: $targetModel")

                val setupJson = LiveSessionModels.buildSetupJson(
                    model = targetModel,
                    voice = currentVoice,
                    systemPrompt = currentSystemPrompt,
                    extraTools = currentExtraTools
                )
                webSocket.send(setupJson)
                Log.d(TAG, "Setup packet transmitted to Gemini Live")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(attempt)) return
                try {
                    for (msg in LiveSessionModels.parseServerMessages(text)) when (msg) {
                        is ServerMessage.SetupComplete -> {
                            synchronized(this@GeminiLiveClient) {
                                if (!isCurrent(attempt)) return
                                setupTimeout?.cancel(false)
                                isConnecting.set(false)
                                isConnected.set(true)
                            }
                            Log.i(TAG, "Gemini Live session ready using model: $targetModel")
                            onConnectionStateChanged?.invoke(true)
                        }
                        is ServerMessage.AudioData -> {
                            onAudioReceived?.invoke(msg.pcmData)
                        }

                        is ServerMessage.UserTranscript -> {
                            onUserTranscript?.invoke(msg.text)
                        }

                        is ServerMessage.AssistantTranscript -> {
                            onAssistantTranscript?.invoke(msg.text)
                        }

                        is ServerMessage.TurnComplete -> {
                            Log.d(TAG, "TurnComplete event received")
                            onTurnComplete?.invoke()
                        }

                        is ServerMessage.Interrupted -> {
                            Log.d(TAG, "Interrupted event received (barge-in)")
                            onInterrupted?.invoke()
                        }

                        is ServerMessage.ToolCall -> {
                            for (call in msg.calls) {
                                Log.i(TAG, "ToolCall received: ${call.name} (id=${call.id})")
                                onToolCall?.invoke(call)
                            }
                        }

                        is ServerMessage.Unknown -> {
                            // Ignored or log debug
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling incoming Gemini message: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closing: $code / $reason")
                webSocket.close(code, null)
                handleSocketClose(attempt, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closed: $code / $reason")
                handleSocketClose(attempt, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val body = try { response?.body?.string() } catch (_: Exception) { null }
                // Exception messages can include the URL's API key; never log them.
                Log.e(TAG, "Gemini Live connection failed: httpCode=$code, type=${t.javaClass.simpleName}")
                handleSocketClose(attempt, code, body ?: "Socket failure")
            }
        })
        setupTimeout = timeoutExecutor.schedule({
            synchronized(this) {
                if (isCurrent(attempt) && isConnecting.get()) {
                    handleSocketClose(attempt, 0, "Voice setup timed out")
                }
            }
        }, setupTimeoutMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun handleSocketClose(attempt: Long, code: Int, reason: String) {
        if (!isCurrent(attempt)) return
        terminalHandled = true
        setupTimeout?.cancel(false)
        val wasConnected = isConnected.getAndSet(false)
        val wasConnecting = isConnecting.getAndSet(false)

        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        val isModelRejection = (code == 1008 || code == 404) &&
            (reason.contains("not supported", ignoreCase = true) || reason.contains("not found", ignoreCase = true))
        if (!wasConnected && wasConnecting && isModelRejection &&
            activeModelIndex + 1 < LiveSessionModels.SUPPORTED_LIVE_MODELS.size) {
            activeModelIndex++
            Log.w(TAG, "Live model unavailable; trying the next supported voice model")
            startSessionWebSocket()
            return
        }

        onConnectionStateChanged?.invoke(false)
    }

    /**
     * Stream an ongoing 16,000 Hz mono PCM chunk to Gemini Live.
     */
    fun sendAudio(chunk: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false

        val payload = LiveSessionModels.buildAudioChunkJson(chunk)
        return ws.send(payload)
    }

    fun sendText(text: String): Boolean {
        if (!connected) return false
        val part = org.json.JSONObject().put("text", text)
        val turn = org.json.JSONObject().put("role", "user")
            .put("parts", org.json.JSONArray().put(part))
        val content = org.json.JSONObject().put("turns", org.json.JSONArray().put(turn))
            .put("turnComplete", true)
        return webSocket?.send(org.json.JSONObject().put("clientContent", content).toString()) ?: false
    }

    /**
     * Respond to an executed tool call (e.g. play_local_filler acknowledgement).
     */
    fun sendToolResponse(callId: String, name: String, resultJson: String): Boolean {
        val ws = webSocket ?: return false
        if (!isConnected.get()) return false

        val payload = LiveSessionModels.buildToolResponseJson(callId, name, resultJson)
        return ws.send(payload)
    }

    /**
     * Gracefully close the WebSocket connection.
     */
    @Synchronized
    fun disconnect() {
        userDisconnected = true
        generation++
        terminalHandled = true
        setupTimeout?.cancel(false)
        isConnecting.set(false)
        try {
            webSocket?.close(1000, "Normal Closure")
            webSocket = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        } finally {
            isConnected.set(false)
            onConnectionStateChanged?.invoke(false)
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private val timeoutExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "jarvis-live-setup-timeout").apply { isDaemon = true }
        }
    }
}
