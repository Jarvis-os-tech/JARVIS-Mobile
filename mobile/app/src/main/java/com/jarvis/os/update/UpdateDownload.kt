package com.jarvis.os.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** User-triggered download. Interrupted transfers resume only on the next button press. */
internal object UpdateDownload {
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun fetch(client: OkHttpClient, request: Request, partial: File, size: Long, sha: String,
                      progress: suspend (Long) -> Unit) {
        require(size in 1..200000000 && Regex("[a-f0-9]{64}").matches(sha)) { "Invalid update information" }
        if (partial.exists() && partial.length() == size && hash(partial) == sha) { progress(size); return }
        if (partial.length() >= size) partial.delete()
        var offset = partial.length()
        progress(offset)
        val download = request.newBuilder().header("Cache-Control", "no-cache")
        if (offset > 0) download.header("Range", "bytes=$offset-")
        client.newCall(download.build()).execute().use { response ->
            if (response.code == 206) {
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("Update server returned an invalid download range")
                val start = range.groupValues[1].toLong()
                val end = range.groupValues[2].toLong()
                require(start == offset && end >= start && end < size && range.groupValues[3].toLong() == size) {
                    "Update server returned an unexpected download range"
                }
            } else if (response.code == 200) {
                offset = 0
            } else throw IOException("Update download failed (HTTP ${response.code}). Try again or open the public download.")
            val body = response.body ?: throw IOException("Update download returned no data")
            body.byteStream().use { input ->
                partial.outputStreamForAppend(offset > 0).use { output ->
                    val buffer = ByteArray(65536)
                    var total = offset
                    var lastPercent = -1L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > size) {
                            partial.delete()
                            throw IOException("Update download exceeded its verified size")
                        }
                        output.write(buffer, 0, count)
                        val percent = total * 100 / size
                        if (percent != lastPercent) { lastPercent = percent; progress(total) }
                    }
                }
            }
        }
        if (partial.length() != size) throw IOException("Download interrupted. Tap Update again to resume.")
        if (hash(partial) != sha) {
            partial.delete()
            throw IOException("Update verification failed. Tap Update again for a fresh download.")
        }
    }
    private fun File.outputStreamForAppend(append: Boolean) = java.io.FileOutputStream(this, append)
}
