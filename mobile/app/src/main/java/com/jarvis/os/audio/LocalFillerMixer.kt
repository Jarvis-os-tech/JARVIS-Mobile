package com.jarvis.os.audio

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * LocalFillerMixer manages instant procedural filler playback (comfort tone, humms)
 * and executes smooth, pop-free cross-fading when synthesized speech from Gemini arrives.
 *
 * Employs a C1-continuous Raised-Cosine ducking window over 50 ms (1,200 samples at 24 kHz)
 * ensuring alpha(t) + beta(t) == 1.0 everywhere with zero DC jumps.
 */
class LocalFillerMixer(
    private val sampleRate: Int = SAMPLE_RATE_HZ
) {
    private var activeFillerBytes: ByteArray? = null
    private var fillerPlaybackOffset: Int = 0
    private var isPlayingFiller: Boolean = false

    val isFillerActive: Boolean
        get() = isPlayingFiller

    /**
     * Start playing a local filler buffer.
     */
    @Synchronized
    fun triggerFiller(contextType: String = "general"): ByteArray {
        val baseFreq = when (contextType.lowercase()) {
            "search", "research" -> 261.63 // C4
            "tool", "execution" -> 293.66  // D4
            "code", "database" -> 329.63   // E4
            else -> 220.0                  // A3 warm default
        }
        val tone = generateComfortTone(durationMs = 800, sampleRate = sampleRate, baseFreq = baseFreq)
        activeFillerBytes = tone
        fillerPlaybackOffset = 0
        isPlayingFiller = true
        return tone
    }

    /**
     * Feed incoming synthesized Gemini Live audio stream.
     * If a local filler is currently active, executes a 50ms Raised-Cosine crossfade
     * between the trailing filler audio and incoming speech, then disengages the filler.
     */
    @Synchronized
    fun feedGeminiAudio(incomingBytes: ByteArray): ByteArray {
        if (!isPlayingFiller || activeFillerBytes == null) {
            return incomingBytes
        }

        val filler = activeFillerBytes!!
        val crossfadeByteSize = CROSSFADE_SAMPLES * 2 // 16-bit = 2 bytes per sample
        val crossfadeBytesCount = min(incomingBytes.size, crossfadeByteSize)
        val crossfadeSampleCount = crossfadeBytesCount / 2

        // Extract filler slice
        val fillerSlice = ShortArray(crossfadeSampleCount)
        val fillerShorts = VadDetector.toPcmShorts(filler)
        val availableFillerShorts = fillerShorts.size - (fillerPlaybackOffset / 2)

        for (i in 0 until crossfadeSampleCount) {
            if (i < availableFillerShorts) {
                fillerSlice[i] = fillerShorts[(fillerPlaybackOffset / 2) + i]
            } else {
                fillerSlice[i] = 0
            }
        }

        // Extract incoming slice
        val incomingShorts = VadDetector.toPcmShorts(incomingBytes)
        val incomingSlice = incomingShorts.copyOfRange(0, crossfadeSampleCount)

        // Perform Raised-Cosine Crossfade
        val crossfadedSlice = crossfadeSamples(fillerSlice, incomingSlice)

        // Build output buffer
        val outputShorts = ShortArray(incomingShorts.size)
        System.arraycopy(crossfadedSlice, 0, outputShorts, 0, crossfadeSampleCount)
        if (incomingShorts.size > crossfadeSampleCount) {
            System.arraycopy(
                incomingShorts,
                crossfadeSampleCount,
                outputShorts,
                crossfadeSampleCount,
                incomingShorts.size - crossfadeSampleCount
            )
        }

        // Disengage filler
        isPlayingFiller = false
        activeFillerBytes = null
        fillerPlaybackOffset = 0

        return VadDetector.fromPcmShorts(outputShorts)
    }

    /**
     * Stop and cancel active filler playback.
     */
    @Synchronized
    fun cancel() {
        isPlayingFiller = false
        activeFillerBytes = null
        fillerPlaybackOffset = 0
    }

    /**
     * Synthesize a warm, natural dual-frequency comfort tone with soft cosine envelope.
     */
    fun generateComfortTone(
        durationMs: Int = 500,
        sampleRate: Int = SAMPLE_RATE_HZ,
        baseFreq: Double = 220.0
    ): ByteArray {
        val totalSamples = (sampleRate * durationMs) / 1000
        val samples = ShortArray(totalSamples)

        val f1 = baseFreq
        val f2 = baseFreq * 1.5 // fifth harmonic
        val maxAmplitude = 5000.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate

            // Cosine envelope for attack and decay
            val envelope = if (i < 480) { // 20ms attack
                0.5 * (1.0 - cos(PI * i / 480.0))
            } else if (i > totalSamples - 480) { // 20ms decay
                val decayIdx = totalSamples - i
                0.5 * (1.0 - cos(PI * decayIdx / 480.0))
            } else {
                1.0
            }

            val s1 = sin(2.0 * PI * f1 * t)
            val s2 = sin(2.0 * PI * f2 * t) * 0.4
            val sample = ((s1 + s2) * maxAmplitude * envelope).toInt().coerceIn(-32768, 32767).toShort()
            samples[i] = sample
        }

        return VadDetector.fromPcmShorts(samples)
    }

    companion object {
        private const val TAG = "LocalFillerMixer"
        const val SAMPLE_RATE_HZ = 24000
        const val CROSSFADE_DURATION_MS = 50
        const val CROSSFADE_SAMPLES = (SAMPLE_RATE_HZ * CROSSFADE_DURATION_MS) / 1000 // 1200 samples

        /**
         * Compute Raised-Cosine alpha (fading out) and beta (fading in) weights.
         * Satisfies: alpha(t) + beta(t) == 1.0, alpha'(0) = alpha'(T) = 0 (C1 continuity).
         */
        fun computeCrossfadeWeights(n: Int): Pair<DoubleArray, DoubleArray> {
            val alpha = DoubleArray(n)
            val beta = DoubleArray(n)
            val denominator = if (n > 1) (n - 1).toDouble() else 1.0

            for (i in 0 until n) {
                val a = 0.5 * (1.0 + cos(PI * i / denominator))
                alpha[i] = a
                beta[i] = 1.0 - a
            }

            return Pair(alpha, beta)
        }

        /**
         * Cross-fade two ShortArray buffers over their length using Raised-Cosine weights.
         */
        fun crossfadeSamples(filler: ShortArray, incoming: ShortArray): ShortArray {
            val n = min(filler.size, incoming.size)
            val output = ShortArray(n)
            val (alpha, beta) = computeCrossfadeWeights(n)

            for (i in 0 until n) {
                val f = filler[i].toDouble()
                val inc = incoming[i].toDouble()
                val mixed = (f * alpha[i] + inc * beta[i]).toInt().coerceIn(-32768, 32767)
                output[i] = mixed.toShort()
            }

            return output
        }
    }
}
