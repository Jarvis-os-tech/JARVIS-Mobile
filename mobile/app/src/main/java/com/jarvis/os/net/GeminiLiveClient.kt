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
        .build()
) {
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)

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
        if (isConnected.get()) {
            Log.w(TAG, "Already connected to Gemini Live")
            return
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live WebSocket opened successfully")

                // Transmit setup configuration
                val setupJson = LiveSessionModels.buildSetupJson(
                    model = model,
                    voice = voice,
                    systemPrompt = systemPrompt,
                    extraTools = extraTools
                )
                webSocket.send(setupJson)
                Log.d(TAG, "Setup packet transmitted to Gemini Live")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    for (msg in LiveSessionModels.parseServerMessages(text)) when (msg) {
                        is ServerMessage.SetupComplete -> {
                            isConnected.set(true)
                            Log.i(TAG, "Gemini Live session ready")
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
                isConnected.set(false)
                onConnectionStateChanged?.invoke(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Gemini Live WebSocket closed: $code / $reason")
                isConnected.set(false)
                onConnectionStateChanged?.invoke(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live WebSocket failure: ${t.message}", t)
                isConnected.set(false)
                onConnectionStateChanged?.invoke(false)
            }
        })
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
    }
}
