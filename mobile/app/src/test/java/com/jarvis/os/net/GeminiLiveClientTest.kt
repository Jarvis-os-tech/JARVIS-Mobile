package com.jarvis.os.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for Gemini Live WebSocket message serialization, framing,
 * audio encoding, and server message decoding.
 */
class GeminiLiveClientTest {
    @Test fun interruptionIsNotLostWhenTurnAlsoCompletes() {
        val events = LiveSessionModels.parseServerMessages("""{"serverContent":{"interrupted":true,"turnComplete":true}}""")
        assertEquals(listOf(ServerMessage.Interrupted, ServerMessage.TurnComplete), events)
    }

    @Test fun allAudioPartsAreDeliveredBeforeTurnCompletes() {
        val events = LiveSessionModels.parseServerMessages("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AwQ="}}]},"turnComplete":true}}""")
        assertEquals(3, events.size)
        assertTrue((events[0] as ServerMessage.AudioData).pcmData.contentEquals(byteArrayOf(1, 2)))
        assertTrue((events[1] as ServerMessage.AudioData).pcmData.contentEquals(byteArrayOf(3, 4)))
        assertEquals(ServerMessage.TurnComplete, events[2])
    }
    @Test
    fun testSetupAcknowledgement() {
        assertEquals(ServerMessage.SetupComplete, LiveSessionModels.parseServerMessage("{\"setupComplete\":{}}"))
    }

    @Test
    fun testSetupMessageSerialization() {
        val setupJson = LiveSessionModels.buildSetupJson(
            voice = "Aoede",
            systemPrompt = "You are JARVIS, an autonomous voice assistant."
        )

        assertTrue("Setup must contain setup key", setupJson.contains("\"setup\":"))
        assertTrue("Setup must specify supported live model", setupJson.contains("models/gemini-3.8-live"))
        assertTrue("Setup must specify responseModalities AUDIO", setupJson.contains("\"AUDIO\""))
        assertTrue("Setup must configure Aoede voice", setupJson.contains("\"Aoede\""))
        assertTrue("Setup must declare play_local_filler tool", setupJson.contains("\"play_local_filler\""))
    }

    @Test
    fun testRealtimeAudioChunkSerialization() {
        val testPcm = ByteArray(640) { (it % 100).toByte() }
        val chunkJson = LiveSessionModels.buildAudioChunkJson(testPcm)

        assertTrue(chunkJson.contains("\"realtimeInput\":"))
        val input = org.json.JSONObject(chunkJson).getJSONObject("realtimeInput")
        assertEquals("audio/pcm;rate=16000", input.getJSONObject("audio").getString("mimeType"))
        assertTrue("Deprecated mediaChunks must not be used", !input.has("mediaChunks"))
        assertTrue(chunkJson.contains("\"audio/pcm;rate=16000\""))

        // Extract and verify base64 data
        val expectedBase64 = Base64.getEncoder().encodeToString(testPcm)
        assertTrue(chunkJson.contains(expectedBase64))
    }

    @Test
    fun testParseServerAudioChunk() {
        val originalPcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val base64Data = Base64.getEncoder().encodeToString(originalPcm)
        val serverJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {
                                "inlineData": {
                                    "mimeType": "audio/pcm;rate=24000",
                                    "data": "$base64Data"
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val parsed = LiveSessionModels.parseServerMessage(serverJson)
        assertTrue(parsed is ServerMessage.AudioData)
        val audioMsg = parsed as ServerMessage.AudioData
        assertEquals(8, audioMsg.pcmData.size)
        org.junit.Assert.assertArrayEquals(originalPcm, audioMsg.pcmData)
    }

    @Test
    fun testParseServerTurnComplete() {
        val serverJson = """
            {
                "serverContent": {
                    "turnComplete": true
                }
            }
        """.trimIndent()

        val parsed = LiveSessionModels.parseServerMessage(serverJson)
        assertTrue(parsed is ServerMessage.TurnComplete)
    }

    @Test
    fun testParseServerInterrupted() {
        val serverJson = """
            {
                "serverContent": {
                    "interrupted": true
                }
            }
        """.trimIndent()

        val parsed = LiveSessionModels.parseServerMessage(serverJson)
        assertTrue(parsed is ServerMessage.Interrupted)
    }

    @Test
    fun testParseServerToolCall() {
        val serverJson = """
            {
                "toolCall": {
                    "functionCalls": [
                        {
                            "id": "call-12345",
                            "name": "play_local_filler",
                            "args": {
                                "context_type": "search"
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        val parsed = LiveSessionModels.parseServerMessage(serverJson)
        assertTrue(parsed is ServerMessage.ToolCall)
        val toolCall = parsed as ServerMessage.ToolCall
        assertEquals(1, toolCall.calls.size)
        assertEquals("call-12345", toolCall.calls[0].id)
        assertEquals("play_local_filler", toolCall.calls[0].name)
        assertEquals("search", toolCall.calls[0].args["context_type"])
    }

    @Test
    fun testParseTranscripts() {
        val userJson = """
            {
                "serverContent": {
                    "inputTranscription": {
                        "text": "What is the capital of France?"
                    }
                }
            }
        """.trimIndent()
        val userEvents = LiveSessionModels.parseServerMessages(userJson)
        assertEquals(1, userEvents.size)
        assertTrue(userEvents[0] is ServerMessage.UserTranscript)
        assertEquals("What is the capital of France?", (userEvents[0] as ServerMessage.UserTranscript).text)

        val assistantJson = """
            {
                "serverContent": {
                    "outputTranscription": {
                        "text": "The capital is Paris."
                    }
                }
            }
        """.trimIndent()
        val assistantEvents = LiveSessionModels.parseServerMessages(assistantJson)
        assertEquals(1, assistantEvents.size)
        assertTrue(assistantEvents[0] is ServerMessage.AssistantTranscript)
        assertEquals("The capital is Paris.", (assistantEvents[0] as ServerMessage.AssistantTranscript).text)

        val modelTurnTextJson = """
            {
                "serverContent": {
                    "modelTurn": {
                        "parts": [
                            {
                                "text": "Bonjour! "
                            }
                        ]
                    }
                }
            }
        """.trimIndent()
        val modelTurnEvents = LiveSessionModels.parseServerMessages(modelTurnTextJson)
        assertEquals(1, modelTurnEvents.size)
        assertTrue(modelTurnEvents[0] is ServerMessage.AssistantTranscript)
        assertEquals("Bonjour! ", (modelTurnEvents[0] as ServerMessage.AssistantTranscript).text)
    }

    @Test
    fun testBuildToolResponseJson() {
        val responseJson = LiveSessionModels.buildToolResponseJson(
            callId = "call-12345",
            callName = "play_local_filler",
            resultJson = "{\"status\":\"started\"}"
        )

        assertTrue(responseJson.contains("\"toolResponse\":"))
        assertTrue(responseJson.contains("\"call-12345\""))
        assertTrue(responseJson.contains("\"play_local_filler\""))
        assertTrue(responseJson.contains("\"status\":\"started\""))
    }
}
