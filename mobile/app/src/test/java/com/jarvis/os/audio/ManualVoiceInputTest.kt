package com.jarvis.os.audio

import org.junit.Assert.*
import org.junit.Test

class ManualVoiceInputTest {
    private fun frame(n: Int) = byteArrayOf(n.toByte(), 0)
    @Test fun commitsOneTurnAfterFourHundredMillisecondsOfQuiet() {
        val sent = mutableListOf<ManualVoiceInput.Frame>()
        val input = ManualVoiceInput(send = { sent.add(it); true })
        input.start(); assertFalse(input.connected())
        repeat(30) { input.process(frame(it), false, false, false) }
        assertTrue(sent.isEmpty())
        assertTrue(input.process(frame(50), true, false, false).opened)
        repeat(19) { assertFalse(input.process(frame(0), false, false, false).closed) }
        assertTrue(input.process(frame(0), false, false, false).closed)
        assertSame(ManualVoiceInput.Frame.Start, sent.first())
        assertSame(ManualVoiceInput.Frame.End, sent.last())
        assertEquals(1, sent.count { it === ManualVoiceInput.Frame.End })
        val count = sent.size
        repeat(200) { input.process(frame(0), false, false, false) }
        assertEquals(count, sent.size)
    }
    @Test fun shortPauseContinuesSameTurnAndPreservesFirstPhonemes() {
        val sent = mutableListOf<ManualVoiceInput.Frame>()
        val input = ManualVoiceInput(send = { sent.add(it); true })
        input.start(); input.connected()
        repeat(3) { input.process(frame(it), false, false, false) }
        input.process(frame(3), true, false, false)
        repeat(12) { input.process(frame(0), false, false, false) }
        input.process(frame(4), true, false, false)
        repeat(20) { input.process(frame(0), false, false, false) }
        assertEquals(1, sent.count { it === ManualVoiceInput.Frame.Start })
        assertEquals(listOf(0,1,2,3), sent.filterIsInstance<ManualVoiceInput.Frame.Audio>().take(4).map { it.pcm[0].toInt() })
    }
    @Test fun speechBeforeSetupIsDeliveredInOrderWithoutDuplicateGreeting() {
        val sent = mutableListOf<ManualVoiceInput.Frame>()
        val input = ManualVoiceInput(send = { sent.add(it); true })
        input.start()
        input.process(frame(1), true, false, false)
        repeat(20) { input.process(frame(0), false, false, false) }
        assertTrue(sent.isEmpty())
        assertTrue(input.connected())
        assertSame(ManualVoiceInput.Frame.Start, sent.first())
        assertSame(ManualVoiceInput.Frame.End, sent.last())
        assertEquals(21, sent.filterIsInstance<ManualVoiceInput.Frame.Audio>().size)
    }
    @Test fun speakerEchoDoesNotOpenTurnButDeliberateBargeInDoes() {
        val sent = mutableListOf<ManualVoiceInput.Frame>()
        val input = ManualVoiceInput(send = { sent.add(it); true })
        input.start(); input.connected()
        repeat(100) { assertFalse(input.process(frame(it), true, true, false).opened) }
        assertTrue(sent.isEmpty())
        val result = input.process(frame(101), true, true, true)
        assertTrue(result.opened); assertTrue(result.interrupted)
        assertEquals(12, sent.filterIsInstance<ManualVoiceInput.Frame.Audio>().size)
    }
    @Test fun fullSetupBufferFailsAsWholeTurnRatherThanDroppingBoundaries() {
        val input = ManualVoiceInput(send = { true }, maxBufferedFrames = 4)
        input.start()
        try {
            repeat(10) { input.process(frame(it), true, false, false) }
            fail("Backlog must cause recovery")
        } catch (_: ManualVoiceInput.TransportFailure) { }
        assertFalse(input.turnOpen)
        assertFalse(input.connected())
    }
    @Test fun failedUploadAndResetCannotReplayOldSpeech() {
        val sent = mutableListOf<ManualVoiceInput.Frame>()
        var accepts = false
        val input = ManualVoiceInput(send = { if (accepts) sent.add(it); accepts })
        input.start(); input.process(frame(5), true, false, false)
        try { input.connected(); fail("Upload failure must propagate") } catch (_: ManualVoiceInput.TransportFailure) { }
        accepts = true
        input.start(); assertFalse(input.connected())
        assertTrue(sent.isEmpty())
        input.process(frame(6), true, false, false)
        assertEquals(6, (sent[1] as ManualVoiceInput.Frame.Audio).pcm[0].toInt())
        input.reset()
        val count = sent.size
        repeat(25) { input.process(frame(0), false, false, false) }
        assertEquals(count, sent.size)
    }
}
