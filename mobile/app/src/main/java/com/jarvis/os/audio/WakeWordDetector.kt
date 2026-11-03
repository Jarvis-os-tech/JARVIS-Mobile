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
    private var goodbyeActive = false
    private var goodbyeQuietFrames = 0

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
                "[\"bye\", \"goodbye\", \"bye jarvis\", \"goodbye jarvis\", \"[unk]\"]").apply { setWords(true) }
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
        val accepted = decoder.acceptWaveForm(pcm, pcm.size)
        val jsonStr = if (accepted) decoder.result else decoder.partialResult
        val result = try { JSONObject(jsonStr) } catch (_: Exception) { return false }
        val words = result.optJSONArray("result")
        val text = (if (accepted) result.optString("text") else result.optString("partial")).trim()

        var detected = false
        var jarvisWordIndex = -1

        if (words != null && words.length() >= 1) {
            for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                val word = w.optString("word", "").lowercase()
                val conf = w.optDouble("conf", 0.0)

                if (word == "jarvis") {
                    if (i > 0) {
                        val prev = words.getJSONObject(i - 1)
                        val prevWord = prev.optString("word", "").lowercase()
                        val prevConf = prev.optDouble("conf", 0.0)
                        if (prevWord in setOf("hey", "hi", "ok", "okay", "hello") && prevConf >= 0.45) {
                            jarvisWordIndex = i
                            detected = true
                            break
                        }
                    }
                    if (conf >= 0.75) {
                        jarvisWordIndex = i
                        detected = true
                        break
                    }
                }
            }
        }

        if (!detected && text.isNotBlank()) {
            val normalized = text.lowercase().replace(Regex("\\s+"), " ")
            if (WakePhrase.matches(text) ||
                normalized.contains("hey jarvis") ||
                normalized.contains("hello jarvis") ||
                normalized.contains("hi jarvis") ||
                normalized.contains("ok jarvis") ||
                normalized.contains("okay jarvis") ||
                normalized == "jarvis" ||
                normalized.startsWith("jarvis ")) {
                detected = true
            }
        }

        if (text.contains("jarvis", ignoreCase = true) || text.contains("hey", ignoreCase = true)) {
            android.util.Log.i("WakeWordDetector", "Wake check: text='$text' accepted=$detected")
        }

        if (detected) {
            val commandWords = mutableListOf<String>()
            if (words != null && jarvisWordIndex >= 0) {
                for (j in (jarvisWordIndex + 1) until words.length()) {
                    val w = words.getJSONObject(j).optString("word", "")
                    if (w.isNotBlank() && w != "[unk]") {
                        commandWords.add(w)
                    }
                }
            } else {
                val parts = text.split(Regex("\\s+"))
                val idx = parts.indexOfFirst { it.equals("jarvis", ignoreCase = true) }
                if (idx >= 0 && idx + 1 < parts.size) {
                    for (j in (idx + 1) until parts.size) {
                        if (parts[j] != "[unk]") commandWords.add(parts[j])
                    }
                }
            }
            wakeCommand = commandWords.joinToString(" ").takeUnless { it.isBlank() }
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
        val voiced = VadDetector.calculateRms(pcm) >= BASE_ENERGY_THRESHOLD
        if (!voiced && !goodbyeActive) return false
        goodbyeActive = true
        goodbyeQuietFrames = if (voiced) 0 else goodbyeQuietFrames + 1
        val complete = decoder.acceptWaveForm(pcm, pcm.size)
        if (!complete && goodbyeQuietFrames < 60) return false
        val raw = if (complete) decoder.result else decoder.finalResult
        decoder.reset(); goodbyeActive = false; goodbyeQuietFrames = 0
        val result = try { JSONObject(raw) } catch (_: Exception) { return false }
        val text = result.optString("text").trim().lowercase().replace(Regex("\\s+"), " ")
        if (!GoodbyePhrase.matches(text)) return false
        val words = result.optJSONArray("result") ?: return true
        if (words.length() == 0) return true
        for (i in 0 until words.length()) {
            val conf = words.getJSONObject(i).optDouble("conf", 0.0)
            if (conf < 0.65) return false
        }
        return true
    }

    @Synchronized
    fun resetGoodbye() { goodbyeRecognizer?.reset(); goodbyeActive = false; goodbyeQuietFrames = 0 }

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
        const val BASE_ENERGY_THRESHOLD = 80.0
        const val PRE_ROLL_CHUNKS = 8 // ~160ms pre-speech audio buffer
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
