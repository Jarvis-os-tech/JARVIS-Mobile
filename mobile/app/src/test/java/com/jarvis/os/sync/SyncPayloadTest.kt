package com.jarvis.os.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for QR pairing payload decoding, verification,
 * and memory delta serialization.
 */
class SyncPayloadTest {

    @Test
    fun testParsePairingPayloadValid() {
        val json = """
            {
                "v": 1,
                "pid": "jarvis-os-508612",
                "dev": "laptop-g0pi-4f2a",
                "tok": "c89f8342de12479abec341258ef12401",
                "bkt": "jarvis-os-508612-memory-sync",
                "ep": "https://firestore.googleapis.com/v1/projects/jarvis-os-508612/databases/(default)/documents/sync_mailboxes/laptop-g0pi-4f2a",
                "key": "AIzaSyTestApiKey12345"
            }
        """.trimIndent()

        val payload = SyncPayloads.parsePairingPayload(json)
        assertNotNull(payload)
        assertEquals(1, payload!!.version)
        assertEquals("jarvis-os-508612", payload.projectId)
        assertEquals("laptop-g0pi-4f2a", payload.deviceId)
        assertEquals("c89f8342de12479abec341258ef12401", payload.authToken)
        assertEquals("jarvis-os-508612-memory-sync", payload.gcsBucket)
        assertEquals("AIzaSyTestApiKey12345", payload.geminiApiKey)
    }

    @Test
    fun testParsePairingPayloadWithoutApiKey() {
        val json = """
            {
                "v": 1,
                "pid": "jarvis-os-508612",
                "dev": "desktop-omarchy",
                "tok": "a1b2c3d4e5f6",
                "bkt": "jarvis-os-508612-memory-sync",
                "ep": "https://firestore.googleapis.com/v1/projects/jarvis-os-508612"
            }
        """.trimIndent()

        val payload = SyncPayloads.parsePairingPayload(json)
        assertNotNull(payload)
        assertEquals("desktop-omarchy", payload!!.deviceId)
        assertEquals(null, payload.geminiApiKey)
    }

    @Test
    fun testSerializeAndParseMemoryDelta() {
        val delta = MemoryDeltaRecord(
            id = "turn-001",
            timestamp = 1730441200000L,
            role = "user",
            content = "Remind me to pick up dry cleaning when I get back to town.",
            deviceId = "phone-pixel"
        )

        val json = SyncPayloads.serializeDeltaRecord(delta)
        assertTrue(json.contains("\"id\":\"turn-001\""))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("dry cleaning"))

        val reconstructed = SyncPayloads.parseDeltaRecord(json)
        assertNotNull(reconstructed)
        assertEquals(delta.id, reconstructed!!.id)
        assertEquals(delta.content, reconstructed.content)
        assertEquals(delta.role, reconstructed.role)
        assertEquals(delta.timestamp, reconstructed.timestamp)
    }

    @Test
    fun testBuildSyncBatchBundle() {
        val records = listOf(
            MemoryDeltaRecord(id = "1", role = "user", content = "Hello", deviceId = "phone"),
            MemoryDeltaRecord(id = "2", role = "model", content = "Good morning, sir.", deviceId = "phone")
        )

        val bundleJson = SyncPayloads.buildSyncBundleJson(
            deviceId = "phone",
            authToken = "secret-token",
            records = records
        )

        assertTrue(bundleJson.contains("\"records\":"))
        assertTrue(bundleJson.contains("\"deviceId\":\"phone\""))
        assertTrue(bundleJson.contains("\"authToken\":\"secret-token\""))
        assertTrue(bundleJson.contains("Good morning, sir."))
    }
}
