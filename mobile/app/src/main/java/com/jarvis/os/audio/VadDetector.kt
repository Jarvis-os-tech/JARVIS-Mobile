package com.jarvis.os.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Voice activity detection states.
 */
enum class VadState {
    SILENCE,
    ATTACK,
    SPEECH,
    HANGOVER
}

/**
 * Result of a single VAD frame analysis.
 */
data class VadResult(
    val isSpeech: Boolean,
    val state: VadState,
    val energy: Double,
    val zeroCrossings: Int = 0,
    val speechFrameCount: Int = 0,
    val silenceFrameCount: Int = 0
)

/**
 * Robust Voice Activity Detector combining RMS energy, zero-crossing boundary checks,
 * and adaptive attack/hangover state machines (with Silero VAD probability fallback).
 */
class VadDetector(
    var energyThreshold: Double = 1200.0,
    val attackFrames: Int = 3,
    val hangoverFrames: Int = 5,
    val adaptiveNoiseFloor: Boolean = true,
    val sileroProbabilityThreshold: Float = 0.5f
) {
    private var currentState: VadState = VadState.SILENCE
    private var speechFrames: Int = 0
    private var silenceFrames: Int = 0
    private var estimatedNoiseFloor: Double = 300.0

    /**
     * Process a raw 16-bit mono PCM frame (little-endian byte array).
     */
    fun process(frameBytes: ByteArray): VadResult {
        val shorts = toPcmShorts(frameBytes)
        return process(shorts)
    }

    /**
     * Process 16-bit PCM samples.
     */
    fun process(samples: ShortArray): VadResult {
        val energy = calculateRms(samples)
        val zcr = countZeroCrossings(samples)

        if (adaptiveNoiseFloor && currentState == VadState.SILENCE && energy < energyThreshold * 0.5) {
            estimatedNoiseFloor = 0.95 * estimatedNoiseFloor + 0.05 * energy
        }

        val effectiveThreshold = if (adaptiveNoiseFloor) {
            maxOf(energyThreshold, estimatedNoiseFloor * 3.0)
        } else {
            energyThreshold
        }

        val isVoiceCandidate = energy >= effectiveThreshold
        return updateState(isVoiceCandidate, energy, zcr)
    }

    /**
     * Process external neural model output (e.g. Silero VAD probability [0.0, 1.0]).
     */
    fun processProbability(prob: Float): VadResult {
        val isVoiceCandidate = prob >= sileroProbabilityThreshold
        val simulatedEnergy = (prob * 10000.0)
        return updateState(isVoiceCandidate, simulatedEnergy, 0)
    }

    private fun updateState(isVoiceCandidate: Boolean, energy: Double, zcr: Int): VadResult {
        when (currentState) {
            VadState.SILENCE -> {
                if (isVoiceCandidate) {
                    speechFrames = 1
                    silenceFrames = 0
                    if (speechFrames >= attackFrames) {
                        currentState = VadState.SPEECH
                    } else {
                        currentState = VadState.ATTACK
                    }
                } else {
                    speechFrames = 0
                    silenceFrames = 0
                    currentState = VadState.SILENCE
                }
            }

            VadState.ATTACK -> {
                if (isVoiceCandidate) {
                    speechFrames++
                    if (speechFrames >= attackFrames) {
                        currentState = VadState.SPEECH
                    } else {
                        currentState = VadState.ATTACK
                    }
                } else {
                    // False alarm / transient noise spike rejected
                    speechFrames = 0
                    silenceFrames = 0
                    currentState = VadState.SILENCE
                }
            }

            VadState.SPEECH -> {
                if (isVoiceCandidate) {
                    speechFrames++
                    silenceFrames = 0
                    currentState = VadState.SPEECH
                } else {
                    // Speech ended, start hangover window
                    silenceFrames = 1
                    if (silenceFrames > hangoverFrames) {
                        speechFrames = 0
                        currentState = VadState.SILENCE
                    } else {
                        currentState = VadState.HANGOVER
                    }
                }
            }

            VadState.HANGOVER -> {
                if (isVoiceCandidate) {
                    // Resumed speaking before hangover expired! Immediate resume
                    speechFrames++
                    silenceFrames = 0
                    currentState = VadState.SPEECH
                } else {
                    silenceFrames++
                    if (silenceFrames > hangoverFrames) {
                        speechFrames = 0
                        currentState = VadState.SILENCE
                    } else {
                        currentState = VadState.HANGOVER
                    }
                }
            }
        }

        val isSpeechOutput = (currentState == VadState.SPEECH || currentState == VadState.HANGOVER)
        return VadResult(
            isSpeech = isSpeechOutput,
            state = currentState,
            energy = energy,
            zeroCrossings = zcr,
            speechFrameCount = speechFrames,
            silenceFrameCount = silenceFrames
        )
    }

    /**
     * Reset the VAD state machine to clean silence.
     */
    fun reset() {
        currentState = VadState.SILENCE
        speechFrames = 0
        silenceFrames = 0
    }

    companion object {
        /**
         * Convert little-endian 16-bit PCM byte array to ShortArray.
         */
        fun toPcmShorts(bytes: ByteArray): ShortArray {
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return shorts
        }

        /**
         * Convert ShortArray to little-endian 16-bit PCM byte array.
         */
        fun fromPcmShorts(shorts: ShortArray): ByteArray {
            val bytes = ByteArray(shorts.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts)
            return bytes
        }

        /**
         * Compute Root-Mean-Square (RMS) amplitude of 16-bit PCM samples.
         */
        fun calculateRms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sumSquares = 0.0
            for (sample in samples) {
                val s = sample.toDouble()
                sumSquares += s * s
            }
            return sqrt(sumSquares / samples.size)
        }

        /**
         * Compute RMS amplitude from raw byte array.
         */
        fun calculateRms(bytes: ByteArray): Double {
            if (bytes.isEmpty()) return 0.0
            return calculateRms(toPcmShorts(bytes))
        }

        /**
         * Count zero-crossing transitions across the signal.
         */
        fun countZeroCrossings(samples: ShortArray): Int {
            if (samples.size < 2) return 0
            var crossings = 0
            for (i in 1 until samples.size) {
                val prev = samples[i - 1].toInt()
                val curr = samples[i].toInt()
                if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                    crossings++
                }
            }
            return crossings
        }

        /**
         * Slice byte buffer into fixed frame-sized chunks, returning the list of frames
         * and any residual bytes leftover.
         */
        fun sliceFrames(bytes: ByteArray, frameSize: Int): Pair<List<ByteArray>, ByteArray> {
            val numFrames = bytes.size / frameSize
            val frames = ArrayList<ByteArray>(numFrames)
            for (i in 0 until numFrames) {
                frames.add(bytes.copyOfRange(i * frameSize, (i + 1) * frameSize))
            }
            val residual = if (bytes.size > numFrames * frameSize) {
                bytes.copyOfRange(numFrames * frameSize, bytes.size)
            } else {
                ByteArray(0)
            }
            return Pair(frames, residual)
        }
    }
}
