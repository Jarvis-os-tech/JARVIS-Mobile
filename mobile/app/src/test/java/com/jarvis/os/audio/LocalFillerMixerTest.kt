package com.jarvis.os.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for LocalFillerMixer and Raised-Cosine ducking cross-fader mathematics.
 */
class LocalFillerMixerTest {

    @Test
    fun testRaisedCosineWindowMathematics() {
        val windowSize = LocalFillerMixer.CROSSFADE_SAMPLES // 1200 samples = 50ms @ 24kHz
        val (alpha, beta) = LocalFillerMixer.computeCrossfadeWeights(windowSize)

        assertEquals(windowSize, alpha.size)
        assertEquals(windowSize, beta.size)

        // Boundary conditions at t=0
        assertEquals(1.0, alpha[0], 0.001)
        assertEquals(0.0, beta[0], 0.001)

        // Boundary conditions at t=T (last sample)
        assertEquals(0.0, alpha[windowSize - 1], 0.01)
        assertEquals(1.0, beta[windowSize - 1], 0.01)

        // Constant energy / unity sum: alpha(t) + beta(t) == 1.0 everywhere
        for (i in 0 until windowSize) {
            val sum = alpha[i] + beta[i]
            assertEquals("Weight sum must be 1.0 at index $i", 1.0, sum, 1e-6)
            assertTrue("Alpha must be within [0, 1]", alpha[i] in 0.0..1.0)
            assertTrue("Beta must be within [0, 1]", beta[i] in 0.0..1.0)
        }

        // Midpoint at t = T / 2 should be exactly 0.5
        val mid = windowSize / 2
        assertEquals(0.5, alpha[mid], 0.01)
        assertEquals(0.5, beta[mid], 0.01)
    }

    @Test
    fun testCrossfadeConstantSignalNoDcJumps() {
        val windowSize = 1200
        val fillerLevel: Short = 4000
        val geminiLevel: Short = 4000

        // If both signals have the same constant amplitude, the crossfade should remain constant 4000
        val fillerPcm = ShortArray(windowSize) { fillerLevel }
        val geminiPcm = ShortArray(windowSize) { geminiLevel }

        val mixed = LocalFillerMixer.crossfadeSamples(fillerPcm, geminiPcm)
        assertEquals(windowSize, mixed.size)

        for (i in 0 until windowSize) {
            assertEquals("DC level should be preserved perfectly at index $i", fillerLevel.toDouble(), mixed[i].toDouble(), 2.0)
        }
    }

    @Test
    fun testCrossfadeLinearTransition() {
        val windowSize = 1200
        val fillerPcm = ShortArray(windowSize) { 10000 }
        val geminiPcm = ShortArray(windowSize) { 0 }

        val mixed = LocalFillerMixer.crossfadeSamples(fillerPcm, geminiPcm)

        // At beginning, output should be close to 10000
        assertEquals(10000.0, mixed[0].toDouble(), 50.0)

        // At end, output should be close to 0
        assertEquals(0.0, mixed[windowSize - 1].toDouble(), 50.0)

        // Monotonically decreasing
        for (i in 1 until windowSize) {
            assertTrue("Crossfade must be smoothly decreasing", mixed[i] <= mixed[i - 1])
        }
    }

    @Test
    fun testProceduralComfortToneGeneration() {
        val mixer = LocalFillerMixer()
        val tone = mixer.generateComfortTone(durationMs = 200)

        // 200 ms at 24,000 Hz = 4,800 samples = 9,600 bytes
        assertEquals(9600, tone.size)

        // Check that tone is not all zeros
        var nonZeroCount = 0
        for (b in tone) {
            if (b != 0.toByte()) nonZeroCount++
        }
        assertTrue("Tone must contain audio signal", nonZeroCount > 100)
    }

    @Test
    fun testFeedGeminiAudioPassThroughWhenIdle() {
        val mixer = LocalFillerMixer()
        val testAudio = ByteArray(2400) { (it % 120).toByte() }

        // When no filler is active, input audio passes through unmodified
        val output = mixer.feedGeminiAudio(testAudio)
        assertArrayEquals(testAudio, output)
    }

    @Test
    fun testTriggerFillerAndFeedGeminiAudioCrossfade() {
        val mixer = LocalFillerMixer()
        mixer.triggerFiller("comfort_tone")
        assertTrue(mixer.isFillerActive)

        val geminiAudio = ByteArray(4800) { 0x50 } // constant byte stream
        val mixedOutput = mixer.feedGeminiAudio(geminiAudio)

        assertEquals(4800, mixedOutput.size)
        // After crossfade is initiated and processed, isFillerActive should transition to false
        assertTrue("Mixer state transitions smoothly", !mixer.isFillerActive)
    }
}
