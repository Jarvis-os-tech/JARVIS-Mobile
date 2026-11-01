package com.jarvis.os.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit tests for PCM sample parsing, RMS calculation, zero-crossing detection,
 * frame slicing, and VAD attack/hangover state machines.
 */
class AudioPcmTest {

    @Test
    fun testPcmShortsRoundTrip() {
        val original = shortArrayOf(
            0,
            1,
            -1,
            32767,
            -32768,
            1234,
            -5678,
            256,
            -256
        )

        val bytes = VadDetector.fromPcmShorts(original)
        assertEquals(original.size * 2, bytes.size)

        val reconstructed = VadDetector.toPcmShorts(bytes)
        assertArrayEquals(original, reconstructed)
    }

    @Test
    fun testPcmByteOrderingLittleEndian() {
        // Value 0x1234 (4660) should be encoded as [0x34, 0x12] in Little Endian
        val sample = shortArrayOf(0x1234.toShort())
        val bytes = VadDetector.fromPcmShorts(sample)

        assertEquals(0x34.toByte(), bytes[0])
        assertEquals(0x12.toByte(), bytes[1])

        // Value -1 (0xFFFF) should be [0xFF, 0xFF]
        val minusOne = shortArrayOf((-1).toShort())
        val minusBytes = VadDetector.fromPcmShorts(minusOne)
        assertEquals(0xFF.toByte(), minusBytes[0])
        assertEquals(0xFF.toByte(), minusBytes[1])
    }

    @Test
    fun testRmsSilence() {
        val silence = ShortArray(320) { 0 }
        val rms = VadDetector.calculateRms(silence)
        assertEquals(0.0, rms, 0.001)

        val silenceBytes = VadDetector.fromPcmShorts(silence)
        val rmsFromBytes = VadDetector.calculateRms(silenceBytes)
        assertEquals(0.0, rmsFromBytes, 0.001)
    }

    @Test
    fun testRmsConstantAmplitude() {
        val constantValue: Short = 1500
        val constantSignal = ShortArray(320) { constantValue }
        val rms = VadDetector.calculateRms(constantSignal)
        assertEquals(1500.0, rms, 0.001)
    }

    @Test
    fun testRmsSineWave() {
        val sampleRate = 16000
        val frequency = 400.0 // 400 Hz
        val amplitude = 10000.0
        val numSamples = 320 // 20ms frame = exactly 8 full cycles of 400Hz

        val sineSamples = ShortArray(numSamples) { i ->
            (amplitude * sin(2.0 * PI * frequency * i / sampleRate)).toInt().toShort()
        }

        val rms = VadDetector.calculateRms(sineSamples)
        // Theoretical RMS of sine wave = Amplitude / sqrt(2) ≈ 7071.07
        val expectedRms = amplitude / kotlin.math.sqrt(2.0)
        assertEquals(expectedRms, rms, 100.0) // Within 1.5% margin
    }

    @Test
    fun testZeroCrossingDetection() {
        // Flat signal: zero transitions
        val flatSignal = ShortArray(100) { 500 }
        assertEquals(0, VadDetector.countZeroCrossings(flatSignal))

        // Alternating signal: should have 99 transitions
        val alternating = ShortArray(100) { i -> if (i % 2 == 0) 500.toShort() else (-500).toShort() }
        assertEquals(99, VadDetector.countZeroCrossings(alternating))

        // Sine wave at 400Hz in 20ms has 8 full cycles -> 16 zero crossings
        val sampleRate = 16000
        val frequency = 400.0
        val sine = ShortArray(320) { i ->
            (5000.0 * sin(2.0 * PI * frequency * i / sampleRate)).toInt().toShort()
        }
        val zcr = VadDetector.countZeroCrossings(sine)
        assertTrue("Expected ~16 zero crossings for 400Hz over 20ms, got $zcr", zcr in 14..18)
    }

    @Test
    fun testVadSilenceDetection() {
        val vad = VadDetector(energyThreshold = 1200.0, adaptiveNoiseFloor = false)
        val silenceFrame = VadDetector.fromPcmShorts(ShortArray(320) { 10 })

        for (i in 0 until 10) {
            val result = vad.process(silenceFrame)
            assertFalse("Expected isSpeech=false on silence", result.isSpeech)
            assertEquals(VadState.SILENCE, result.state)
        }
    }

    @Test
    fun testVadAttackWindowOnset() {
        val vad = VadDetector(
            energyThreshold = 1200.0,
            attackFrames = 3,
            hangoverFrames = 5,
            adaptiveNoiseFloor = false
        )

        // Speech frame with RMS ~7071 (well above 1200)
        val speechFrame = VadDetector.fromPcmShorts(ShortArray(320) { i ->
            (10000.0 * sin(2.0 * PI * 400.0 * i / 16000)).toInt().toShort()
        })

        // Frame 1: Attack phase (consecutive speech = 1, need 3)
        val r1 = vad.process(speechFrame)
        assertFalse("Frame 1 should not trigger speech yet", r1.isSpeech)
        assertEquals(VadState.ATTACK, r1.state)
        assertEquals(1, r1.speechFrameCount)

        // Frame 2: Attack phase (consecutive speech = 2, need 3)
        val r2 = vad.process(speechFrame)
        assertFalse("Frame 2 should not trigger speech yet", r2.isSpeech)
        assertEquals(VadState.ATTACK, r2.state)
        assertEquals(2, r2.speechFrameCount)

        // Frame 3: Attack reached (consecutive speech = 3) -> SPEECH onset!
        val r3 = vad.process(speechFrame)
        assertTrue("Frame 3 must trigger speech", r3.isSpeech)
        assertEquals(VadState.SPEECH, r3.state)
        assertEquals(3, r3.speechFrameCount)

        // Frame 4: Speech continues
        val r4 = vad.process(speechFrame)
        assertTrue(r4.isSpeech)
        assertEquals(VadState.SPEECH, r4.state)
        assertEquals(4, r4.speechFrameCount)
    }

    @Test
    fun testVadRejectsTransientSpike() {
        val vad = VadDetector(
            energyThreshold = 1200.0,
            attackFrames = 3,
            hangoverFrames = 5,
            adaptiveNoiseFloor = false
        )

        val loudSpike = VadDetector.fromPcmShorts(ShortArray(320) { 5000 })
        val silenceFrame = VadDetector.fromPcmShorts(ShortArray(320) { 0 })

        // 1 single spike frame
        val r1 = vad.process(loudSpike)
        assertFalse(r1.isSpeech)
        assertEquals(VadState.ATTACK, r1.state)

        // Followed immediately by silence -> false alarm rejected!
        val r2 = vad.process(silenceFrame)
        assertFalse(r2.isSpeech)
        assertEquals(VadState.SILENCE, r2.state)
        assertEquals(0, r2.speechFrameCount)
    }

    @Test
    fun testVadHangoverDecay() {
        val hangoverCount = 4
        val vad = VadDetector(
            energyThreshold = 1200.0,
            attackFrames = 2,
            hangoverFrames = hangoverCount,
            adaptiveNoiseFloor = false
        )

        val speechFrame = VadDetector.fromPcmShorts(ShortArray(320) { i ->
            (8000.0 * sin(2.0 * PI * 400.0 * i / 16000)).toInt().toShort()
        })
        val silenceFrame = VadDetector.fromPcmShorts(ShortArray(320) { 0 })

        // Trigger speech
        vad.process(speechFrame)
        val onset = vad.process(speechFrame)
        assertTrue("Speech must be active", onset.isSpeech)
        assertEquals(VadState.SPEECH, onset.state)

        // Feed silence frames during hangover window
        for (i in 1..hangoverCount) {
            val hangoverResult = vad.process(silenceFrame)
            assertTrue("Frame $i during hangover must still report isSpeech=true", hangoverResult.isSpeech)
            assertEquals(VadState.HANGOVER, hangoverResult.state)
            assertEquals(i, hangoverResult.silenceFrameCount)
        }

        // Frame after hangover window expires -> switches to SILENCE
        val expiredResult = vad.process(silenceFrame)
        assertFalse("Frame after hangover must report isSpeech=false", expiredResult.isSpeech)
        assertEquals(VadState.SILENCE, expiredResult.state)
    }

    @Test
    fun testVadResumptionDuringHangover() {
        val vad = VadDetector(
            energyThreshold = 1200.0,
            attackFrames = 3,
            hangoverFrames = 5,
            adaptiveNoiseFloor = false
        )

        val speechFrame = VadDetector.fromPcmShorts(ShortArray(320) { i ->
            (8000.0 * sin(2.0 * PI * 400.0 * i / 16000)).toInt().toShort()
        })
        val silenceFrame = VadDetector.fromPcmShorts(ShortArray(320) { 0 })

        // Trigger speech (3 frames)
        vad.process(speechFrame)
        vad.process(speechFrame)
        val rSpeech = vad.process(speechFrame)
        assertEquals(VadState.SPEECH, rSpeech.state)

        // 2 frames of silence -> in HANGOVER
        vad.process(silenceFrame)
        val rHangover = vad.process(silenceFrame)
        assertEquals(VadState.HANGOVER, rHangover.state)

        // Next frame is speech again: should IMMEDIATELY resume SPEECH without attack delay
        val rResumed = vad.process(speechFrame)
        assertTrue(rResumed.isSpeech)
        assertEquals(VadState.SPEECH, rResumed.state)
    }

    @Test
    fun testVadReset() {
        val vad = VadDetector(energyThreshold = 1200.0, attackFrames = 1, hangoverFrames = 5)
        val speechFrame = VadDetector.fromPcmShorts(ShortArray(320) { 5000 })
        val res = vad.process(speechFrame)
        assertTrue(res.isSpeech)

        vad.reset()

        val silenceFrame = VadDetector.fromPcmShorts(ShortArray(320) { 0 })
        val resAfterReset = vad.process(silenceFrame)
        assertFalse(resAfterReset.isSpeech)
        assertEquals(VadState.SILENCE, resAfterReset.state)
        assertEquals(0, resAfterReset.speechFrameCount)
    }

    @Test
    fun testSileroProbabilityFallback() {
        val vad = VadDetector(
            sileroProbabilityThreshold = 0.5f,
            attackFrames = 2,
            hangoverFrames = 2
        )

        // Feed high probability -> triggers speech
        val p1 = vad.processProbability(0.8f)
        assertEquals(VadState.ATTACK, p1.state)
        assertFalse(p1.isSpeech)

        val p2 = vad.processProbability(0.9f)
        assertEquals(VadState.SPEECH, p2.state)
        assertTrue(p2.isSpeech)

        // Feed low probability -> hangover
        val p3 = vad.processProbability(0.1f)
        assertEquals(VadState.HANGOVER, p3.state)
        assertTrue(p3.isSpeech)

        val p4 = vad.processProbability(0.2f)
        assertEquals(VadState.HANGOVER, p4.state)
        assertTrue(p4.isSpeech)

        // Hangover expired
        val p5 = vad.processProbability(0.05f)
        assertEquals(VadState.SILENCE, p5.state)
        assertFalse(p5.isSpeech)
    }

    @Test
    fun testFrameChunkingExact() {
        val frameSize = 640
        val input = ByteArray(frameSize * 3) { (it % 128).toByte() }

        val (frames, residual) = VadDetector.sliceFrames(input, frameSize)
        assertEquals(3, frames.size)
        assertEquals(0, residual.size)

        for (i in 0 until 3) {
            assertEquals(frameSize, frames[i].size)
            assertArrayEquals(
                input.copyOfRange(i * frameSize, (i + 1) * frameSize),
                frames[i]
            )
        }
    }

    @Test
    fun testFrameChunkingResidual() {
        val frameSize = 640
        val input = ByteArray(1500) { (it % 128).toByte() }

        val (frames, residual) = VadDetector.sliceFrames(input, frameSize)
        // 1500 / 640 = 2 frames (1280 bytes), 220 bytes residual
        assertEquals(2, frames.size)
        assertEquals(220, residual.size)

        assertArrayEquals(input.copyOfRange(0, 640), frames[0])
        assertArrayEquals(input.copyOfRange(640, 1280), frames[1])
        assertArrayEquals(input.copyOfRange(1280, 1500), residual)
    }

    @Test
    fun testFrameChunkingStreamBuffering() {
        val frameSize = 640
        var accumulator = ByteArray(0)

        val incomingStream = listOf(
            ByteArray(400) { 1 },
            ByteArray(400) { 2 },
            ByteArray(500) { 3 }
        )

        val collectedFrames = mutableListOf<ByteArray>()

        for (chunk in incomingStream) {
            val combined = accumulator + chunk
            val (frames, residual) = VadDetector.sliceFrames(combined, frameSize)
            collectedFrames.addAll(frames)
            accumulator = residual
        }

        // Total input: 400 + 400 + 500 = 1300 bytes -> 2 full frames (1280 bytes), 20 bytes leftover
        assertEquals(2, collectedFrames.size)
        assertEquals(640, collectedFrames[0].size)
        assertEquals(640, collectedFrames[1].size)
        assertEquals(20, accumulator.size)
    }

    @Test
    fun testZeroAllocationRmsEquivalence() {
        val random = java.util.Random(42)
        for (trial in 0 until 50) {
            val samples = ShortArray(320) { random.nextInt().toShort() }
            val bytes = VadDetector.fromPcmShorts(samples)
            val rmsShorts = VadDetector.calculateRms(samples)
            val rmsBytes = VadDetector.calculateRms(bytes)
            assertEquals("RMS mismatch in trial $trial", rmsShorts, rmsBytes, 1e-6)
        }
    }
}
