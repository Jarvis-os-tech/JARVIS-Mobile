package com.jarvis.os.net

import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LiveConnectionRecoveryTest {
    private class Socket(private val req: Request) : WebSocket {
        var cancelled = false
        val messages = mutableListOf<String>()
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { messages.add(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }
    private class Factory : WebSocket.Factory {
        val sockets = mutableListOf<Socket>()
        val listeners = mutableListOf<WebSocketListener>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val socket = Socket(request)
            sockets.add(socket); listeners.add(listener)
            return socket
        }
        fun ready(index: Int) = listeners[index].onMessage(sockets[index], "{\"setupComplete\":{}}")
    }
    @Test fun duplicateOldCloseCannotKillFallbackOrItsAudio() {
        val factory = Factory()
        val client = GeminiLiveClient(socketFactory = factory)
        val events = mutableListOf<Boolean>()
        client.onConnectionStateChanged = { events.add(it) }
        try {
            client.connect("test-key")
            factory.listeners[0].onClosing(factory.sockets[0], 1008, "model not supported")
            assertEquals(2, factory.sockets.size)
            factory.listeners[0].onClosed(factory.sockets[0], 1008, "model not supported")
            assertEquals(2, factory.sockets.size)
            assertTrue(events.isEmpty())
            factory.ready(1)
            factory.listeners[0].onFailure(factory.sockets[0], IOException("late old failure"), null)
            assertTrue(client.connected)
            assertTrue(client.sendAudio(ByteArray(640)))
            assertEquals(listOf(true), events)
        } finally { client.disconnect() }
    }
    @Test fun callbacksFromDisconnectedSessionCannotAffectNewSession() {
        val factory = Factory()
        val client = GeminiLiveClient(socketFactory = factory)
        try {
            client.connect("test-key"); client.disconnect(); client.connect("test-key")
            factory.ready(0)
            assertFalse(client.connected)
            factory.ready(1)
            assertTrue(client.connected)
            factory.listeners[0].onClosed(factory.sockets[0], 1000, "old session")
            assertTrue(client.connected)
        } finally { client.disconnect() }
    }
    @Test fun authenticationOrNetworkFailureDoesNotCycleThroughModels() {
        val factory = Factory()
        val client = GeminiLiveClient(socketFactory = factory)
        val events = mutableListOf<Boolean>()
        client.onConnectionStateChanged = { events.add(it) }
        try {
            client.connect("test-key")
            factory.listeners[0].onFailure(factory.sockets[0], IOException("network unavailable"), null)
            assertEquals(1, factory.sockets.size)
            assertEquals(listOf(false), events)
            assertTrue(factory.sockets[0].cancelled)
        } finally { client.disconnect() }
    }
    @Test fun missingSetupAcknowledgementTimesOutAndAllowsRetry() {
        val factory = Factory()
        val client = GeminiLiveClient(socketFactory = factory, setupTimeoutMs = 50)
        val failed = CountDownLatch(1)
        client.onConnectionStateChanged = { if (!it) failed.countDown() }
        try {
            client.connect("test-key")
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue(factory.sockets[0].cancelled)
            client.connect("test-key")
            assertEquals(2, factory.sockets.size)
        } finally { client.disconnect() }
    }
}
