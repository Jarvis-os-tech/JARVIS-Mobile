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
    fun reset() { recognizer?.reset();wakeCommand = null }

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
}

object WakePhrase {
    private val phrase = Regex("^\\s*hey\\s+jarvis\\b", RegexOption.IGNORE_CASE)
    fun matches(text: String): Boolean = phrase.containsMatchIn(text)
}

object GoodbyePhrase {
    fun matches(text: String): Boolean = text.trim().lowercase().replace(Regex("\\s+"), " ") in
        setOf("bye", "goodbye", "bye jarvis", "goodbye jarvis")
}
