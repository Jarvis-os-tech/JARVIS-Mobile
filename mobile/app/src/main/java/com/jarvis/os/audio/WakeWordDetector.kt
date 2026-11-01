package com.jarvis.os.audio

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Offline standby decoder. No microphone audio leaves the phone here. */
class WakeWordDetector(private val context: Context) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var goodbyeRecognizer: Recognizer? = null
    private var wakeCommand: String? = null

    // Standby low-power gating state
    private val preRollBuffers = Array(PRE_ROLL_CHUNKS) { ByteArray(0) }
    private var preRollIndex = 0
    private var preRollCount = 0
    private var isVoiceActive = false
    private var hangoverRemaining = 0
    private var estimatedNoiseFloor = 100.0

    @Synchronized
    fun prepare() {
        if (recognizer != null) return
        val directory = File(context.noBackupFilesDir, "wake-model-en-us-0.15")
        val ready = File(directory, ".ready")
        if (!ready.exists()) {
            directory.deleteRecursively()
            copyAssets("wake-model", directory)
            ready.writeText("1")
        }
        val loaded = Model(directory.absolutePath)
        try {
            // Explicit competing phrases prevent bare Jarvis and other greetings from
            // being forced into "hey jarvis", while keeping live decoding lightweight.
            recognizer = Recognizer(loaded, 16000f,
                "[\"hey jarvis\", \"jarvis\", \"jarvis command\", \"okay jarvis\", \"ok jarvis\", \"hello jarvis\", \"hi jarvis\", \"bye jarvis\", \"hey\", \"[unk]\"]").apply { setWords(true) }
            goodbyeRecognizer = Recognizer(loaded, 16000f,
                "[\"bye\", \"goodbye\", \"bye jarvis\", \"goodbye jarvis\", \"[unk]\"]")
            model = loaded
        } catch (error: Exception) {
            loaded.close()
            throw error
        }
    }

    @Synchronized
    fun process(pcm: ByteArray): Boolean {
        if (recognizer == null) return false
        val energy = VadDetector.calculateRms(pcm)
        val effectiveThreshold = maxOf(BASE_ENERGY_THRESHOLD, estimatedNoiseFloor * 2.0)

        if (!isVoiceActive && energy < effectiveThreshold * 0.5) {
            estimatedNoiseFloor = 0.95 * estimatedNoiseFloor + 0.05 * energy
        }

        val hasEnergy = energy >= effectiveThreshold

        if (hasEnergy) {
            if (!isVoiceActive) {
                isVoiceActive = true
                val start = (preRollIndex - preRollCount + PRE_ROLL_CHUNKS) % PRE_ROLL_CHUNKS
                for (i in 0 until preRollCount) {
                    val idx = (start + i) % PRE_ROLL_CHUNKS
                    val buf = preRollBuffers[idx]
                    if (buf.isNotEmpty()) {
                        if (processDecoder(buf)) {
                            resetGating()
                            return true
                        }
                    }
                }
                preRollCount = 0
            }
            hangoverRemaining = HANGOVER_FRAMES
            val detected = processDecoder(pcm)
            if (detected) {
                resetGating()
                return true
            }
            return false
        } else if (isVoiceActive) {
            hangoverRemaining--
            val detected = processDecoder(pcm)
            if (detected) {
                resetGating()
                return true
            }
            if (hangoverRemaining <= 0) {
                isVoiceActive = false
                recognizer?.reset()
                preRollCount = 0
            }
            return false
        } else {
            // Standby silence: buffer into pre-roll ring without running heavy Vosk decoding
            preRollBuffers[preRollIndex] = pcm.copyOf()
            preRollIndex = (preRollIndex + 1) % PRE_ROLL_CHUNKS
            if (preRollCount < PRE_ROLL_CHUNKS) preRollCount++
            return false
        }
    }

    private fun resetGating() {
        isVoiceActive = false
        hangoverRemaining = 0
        preRollCount = 0
    }

    /** Direct un-gated decoder pass for speech frames. */
    @Synchronized
    fun processDecoder(pcm: ByteArray): Boolean {
        val decoder = recognizer ?: return false
        // Partial hypotheses can briefly invent "hey" before correcting to "Jarvis".
        if (!decoder.acceptWaveForm(pcm, pcm.size)) return false
        val result = JSONObject(decoder.result)
        val words = result.optJSONArray("result")
        val detected = WakePhrase.matches(result.optString("text")) && words != null && words.length() >= 2 &&
            words.getJSONObject(0).optString("word") == "hey" &&
            words.getJSONObject(1).optString("word") == "jarvis" &&
            words.getJSONObject(0).optDouble("conf", 0.0) >= 0.65 &&
            words.getJSONObject(1).optDouble("conf", 0.0) >= 0.7
        val text = result.optString("text")
        if (text.contains("jarvis") || text.startsWith("hey")) {
            val first = words?.optJSONObject(0)
            val second = words?.optJSONObject(1)
            android.util.Log.i("WakeWordDetector", "Wake check: exact=${WakePhrase.matches(text)} heyConfidence=${first?.optDouble("conf", 0.0)} nameConfidence=${second?.optDouble("conf", 0.0)} accepted=$detected")
        }
        if (detected) {
            wakeCommand = text.trim().split(Regex("\\s+")).drop(2).joinToString(" ").takeUnless { it.isBlank() || it.contains("[unk]") }
            decoder.reset()
        }
        return detected
    }

    @Synchronized
    fun reset() {
        recognizer?.reset()
        wakeCommand = null
        resetGating()
    }

    @Synchronized
    fun consumeWakeCommand(): String? = wakeCommand.also { wakeCommand = null }

    /** Final, standalone farewell only; partial phrases must not stop a longer request. */
    @Synchronized
    fun processGoodbye(pcm: ByteArray): Boolean {
        val decoder = goodbyeRecognizer ?: return false
        if (!decoder.acceptWaveForm(pcm, pcm.size)) return false
        val text = JSONObject(decoder.result).optString("text")
        return GoodbyePhrase.matches(text)
    }

    @Synchronized
    fun resetGoodbye() { goodbyeRecognizer?.reset() }

    @Synchronized
    fun close() {
        recognizer?.close()
        recognizer = null
        goodbyeRecognizer?.close()
        goodbyeRecognizer = null
        model?.close()
        model = null
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            context.assets.open(path).use { source -> destination.outputStream().use { source.copyTo(it) } }
        } else {
            check(destination.mkdirs() || destination.isDirectory)
            children.forEach { copyAssets("$path/$it", File(destination, it)) }
        }
    }

    companion object {
        const val BASE_ENERGY_THRESHOLD = 300.0
        const val PRE_ROLL_CHUNKS = 4 // ~80ms pre-speech audio buffer
        const val HANGOVER_FRAMES = 50 // ~1.0 second of trailing silence
    }
}

object WakePhrase {
    private val phrase = Regex("^\\s*hey\\s+jarvis\\b", RegexOption.IGNORE_CASE)
    fun matches(text: String): Boolean = phrase.containsMatchIn(text)
}

object GoodbyePhrase {
    fun matches(text: String): Boolean = text.trim().lowercase().replace(Regex("\\s+"), " ") in
        setOf("bye", "goodbye", "bye jarvis", "goodbye jarvis")
}
