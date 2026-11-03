package com.jarvis.os.audio

import org.junit.Assert.*
import org.junit.Test

class StandbyNoiseCalibrationTest {
    private fun pcm(energy: Int) = VadDetector.fromPcmShorts(ShortArray(320) { energy.toShort() })
    @Test fun quietSpeechIsDetectedWithoutAnArtificialNineHundredRmsFloor() {
        val vad = VadDetector(energyThreshold = 350.0)
        repeat(100) { vad.observeBackground(pcm(70)) }
        vad.reset()
        repeat(3) { vad.process(pcm(500)) }
        assertEquals(VadState.SPEECH, vad.process(pcm(500)).state)
    }
    @Test fun steadyBackgroundNoiseIsNotHeldAsAnEndlessUtterance() {
        val vad = VadDetector(energyThreshold = 350.0)
        repeat(100) { vad.observeBackground(pcm(700)) }
        vad.reset()
        repeat(100) { assertEquals(VadState.SILENCE, vad.process(pcm(700)).state) }
        repeat(3) { vad.process(pcm(2500)) }
        assertEquals(VadState.SPEECH, vad.process(pcm(2500)).state)
        repeat(20) { vad.process(pcm(700)) }
        assertEquals(VadState.SILENCE, vad.process(pcm(700)).state)
    }
    @Test fun shortSpeechPeaksDoNotPoisonStandbyNoiseEstimate() {
        val vad = VadDetector(energyThreshold = 350.0)
        repeat(80) { vad.observeBackground(pcm(80)) }
        repeat(20) { vad.observeBackground(pcm(9000)) }
        vad.reset()
        repeat(3) { vad.process(pcm(500)) }
        assertEquals(VadState.SPEECH, vad.process(pcm(500)).state)
    }
}
