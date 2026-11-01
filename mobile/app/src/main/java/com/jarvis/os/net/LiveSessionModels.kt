package com.jarvis.os.net

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Parsed server messages from Gemini Live WebSocket.
 */
sealed class ServerMessage {
    object SetupComplete : ServerMessage()
    data class AudioData(val pcmData: ByteArray, val mimeType: String) : ServerMessage()
    data class ToolCall(val calls: List<FunctionCall>) : ServerMessage()
    data class UserTranscript(val text: String) : ServerMessage()
    data class AssistantTranscript(val text: String) : ServerMessage()
    object TurnComplete : ServerMessage()
    object Interrupted : ServerMessage()
    data class Unknown(val rawJson: String) : ServerMessage()
}

data class FunctionCall(
    val id: String,
    val name: String,
    val args: Map<String, Any?>
)

/**
 * Helpers for framing, serializing, and parsing Gemini Live bidirectional WebSocket payloads.
 */
object LiveSessionModels {
    const val DEFAULT_MODEL = "models/gemini-3.8-live"

    fun buildSetupJson(
        model: String = DEFAULT_MODEL,
        voice: String = "Aoede",
        systemPrompt: String = "You are JARVIS, a helpful and hyper-intelligent autonomous voice assistant.",
        extraTools: JSONArray = JSONArray()
    ): String {
        val root = JSONObject()
        val setup = JSONObject()
        setup.put("model", model)

        val generationConfig = JSONObject()
        val responseModalities = JSONArray()
        responseModalities.put("AUDIO")
        generationConfig.put("responseModalities", responseModalities)

        val speechConfig = JSONObject()
        val voiceConfig = JSONObject()
        val prebuiltVoiceConfig = JSONObject()
        prebuiltVoiceConfig.put("voiceName", voice)
        voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
        speechConfig.put("voiceConfig", voiceConfig)
        generationConfig.put("speechConfig", speechConfig)
        setup.put("generationConfig", generationConfig)
        setup.put("inputAudioTranscription", JSONObject())
        setup.put("outputAudioTranscription", JSONObject())
        setup.put("realtimeInputConfig", JSONObject()
            .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
            .put("automaticActivityDetection", JSONObject()
                .put("disabled", false)
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                .put("prefixPaddingMs", 80)
                .put("silenceDurationMs", 500)))

        val systemInstruction = JSONObject()
        val parts = JSONArray()
        val textPart = JSONObject()
        textPart.put("text", systemPrompt)
        parts.put(textPart)
        systemInstruction.put("parts", parts)
        setup.put("systemInstruction", systemInstruction)

        // Declare play_local_filler tool
        val tools = JSONArray()
        val toolObj = JSONObject()
        val funcDecls = JSONArray()
        val fillerFunc = JSONObject()
        fillerFunc.put("name", "play_local_filler")
        fillerFunc.put(
            "description",
            "Trigger an immediate local filler sound reflex on the mobile device while reasoning."
        )

        val params = JSONObject()
        params.put("type", "OBJECT")
        val properties = JSONObject()
        val contextProp = JSONObject()
        contextProp.put("type", "STRING")
        contextProp.put("description", "Category of task: general, search, code, database")
        properties.put("context_type", contextProp)
        params.put("properties", properties)
        fillerFunc.put("parameters", params)

        funcDecls.put(fillerFunc)
        for (index in 0 until extraTools.length()) funcDecls.put(extraTools.getJSONObject(index))
        toolObj.put("functionDeclarations", funcDecls)
        tools.put(toolObj)
        setup.put("tools", tools)

        root.put("setup", setup)
        return root.toString()
    }

    fun buildAudioChunkJson(pcmBytes: ByteArray): String {
        val base64Data = Base64.getEncoder().encodeToString(pcmBytes)
        val root = JSONObject()
        val realtimeInput = JSONObject()
        val chunk = JSONObject()
        chunk.put("mimeType", "audio/pcm;rate=16000")
        chunk.put("data", base64Data)
        realtimeInput.put("audio", chunk)
        root.put("realtimeInput", realtimeInput)
        return root.toString()
    }

    fun buildToolResponseJson(callId: String, callName: String, resultJson: String): String {
        val root = JSONObject()
        val toolResponse = JSONObject()
        val functionResponses = JSONArray()
        val funcResp = JSONObject()
        funcResp.put("id", callId)
        funcResp.put("name", callName)

        val outputObj = try {
            JSONObject(resultJson)
        } catch (e: Exception) {
            val fallback = JSONObject()
            fallback.put("output", resultJson)
            fallback
        }
        val responseWrap = JSONObject()
        responseWrap.put("output", outputObj)
        funcResp.put("response", responseWrap)

        functionResponses.put(funcResp)
        toolResponse.put("functionResponses", functionResponses)
        root.put("toolResponse", toolResponse)
        return root.toString()
    }

    /** A packet can include both interruption/audio and end-of-turn events. */
    fun parseServerMessages(jsonString: String): List<ServerMessage> {
        val root = JSONObject(jsonString)
        val content = root.optJSONObject("serverContent") ?: return listOf(parseServerMessage(jsonString))
        val events = mutableListOf<ServerMessage>()

        val inputTrans = content.optJSONObject("inputTranscription")
        if (inputTrans != null && inputTrans.has("text")) {
            val text = inputTrans.getString("text")
            if (text.isNotBlank()) events.add(ServerMessage.UserTranscript(text))
        }

        val outputTrans = content.optJSONObject("outputTranscription")
        if (outputTrans != null && outputTrans.has("text")) {
            val text = outputTrans.getString("text")
            if (text.isNotBlank()) events.add(ServerMessage.AssistantTranscript(text))
        }

        if (content.optBoolean("interrupted")) {
            events.add(ServerMessage.Interrupted)
        } else {
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) for (index in 0 until parts.length()) {
                val part = parts.getJSONObject(index)
                val data = part.optJSONObject("inlineData")
                if (data != null) {
                    val mime = data.optString("mimeType", "audio/pcm;rate=24000")
                    if (mime.startsWith("audio/pcm")) {
                        events.add(ServerMessage.AudioData(Base64.getDecoder().decode(data.getString("data")), mime))
                    }
                }
                val partText = part.optString("text", "")
                if (partText.isNotBlank()) {
                    events.add(ServerMessage.AssistantTranscript(partText))
                }
            }
        }
        if (content.optBoolean("turnComplete")) events.add(ServerMessage.TurnComplete)
        return events.ifEmpty { listOf(ServerMessage.Unknown(jsonString)) }
    }

    fun parseServerMessage(jsonString: String): ServerMessage {
        val root = JSONObject(jsonString)
        if (root.has("setupComplete")) return ServerMessage.SetupComplete

        // Check for serverContent
        if (root.has("serverContent")) {
            val sc = root.getJSONObject("serverContent")
            if (sc.optBoolean("turnComplete", false)) {
                return ServerMessage.TurnComplete
            }
            if (sc.optBoolean("interrupted", false)) {
                return ServerMessage.Interrupted
            }
            if (sc.has("modelTurn")) {
                val mt = sc.getJSONObject("modelTurn")
                val parts = mt.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("inlineData")) {
                            val inlineData = part.getJSONObject("inlineData")
                            val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                            val dataBase64 = inlineData.getString("data")
                            val pcmBytes = Base64.getDecoder().decode(dataBase64)
                            return ServerMessage.AudioData(pcmBytes, mimeType)
                        }
                    }
                }
            }
        }

        // Check for toolCall
        if (root.has("toolCall")) {
            val tc = root.getJSONObject("toolCall")
            val callsList = mutableListOf<FunctionCall>()
            val funcCalls = tc.optJSONArray("functionCalls")
            if (funcCalls != null) {
                for (i in 0 until funcCalls.length()) {
                    val callObj = funcCalls.getJSONObject(i)
                    val id = callObj.getString("id")
                    val name = callObj.getString("name")
                    val argsMap = mutableMapOf<String, Any?>()
                    val argsObj = callObj.optJSONObject("args")
                    if (argsObj != null) {
                        val keys = argsObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            argsMap[k] = argsObj.opt(k)
                        }
                    }
                    callsList.add(FunctionCall(id, name, argsMap))
                }
            }
            return ServerMessage.ToolCall(callsList)
        }

        return ServerMessage.Unknown(jsonString)
    }
}
