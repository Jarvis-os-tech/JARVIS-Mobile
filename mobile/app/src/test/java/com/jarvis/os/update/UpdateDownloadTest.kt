package com.jarvis.os.update

import java.net.ServerSocket
import java.net.InetAddress
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class UpdateDownloadTest {
    private val bytes = ByteArray(180000) { (it % 251).toByte() }
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private class Headers {
        val values = linkedMapOf<String, String>()
        fun add(name: String, value: String) { values[name.lowercase()] = value }
        fun getFirst(name: String): String? = values[name.lowercase()]
    }
    private class Exchange {
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        val responseBody = ByteArrayOutputStream()
        var status = 200
        var length = 0L
        fun sendResponseHeaders(code: Int, size: Long) { status = code; length = size }
    }
    private fun server(handler: (Exchange) -> Unit, check: (String) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val worker = thread(isDaemon = true, name = "update-test-http") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    val exchange = Exchange()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val parts = line.split(":", limit = 2)
                        exchange.requestHeaders.add(parts[0], parts[1].trim())
                    }
                    handler(exchange)
                    val headers = StringBuilder("HTTP/1.1 ${exchange.status} Test\r\nContent-Length: ${exchange.length}\r\nConnection: close\r\n")
                    exchange.responseHeaders.values.forEach { (name, value) -> headers.append("$name: $value\r\n") }
                    headers.append("\r\n")
                    try {
                        socket.getOutputStream().write(headers.toString().toByteArray())
                        socket.getOutputStream().write(exchange.responseBody.toByteArray())
                    } catch (_: IOException) { /* A rejected response may close early. */ }
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }
        try { check("http://127.0.0.1:${server.localPort}/apk") }
        finally { server.close(); worker.join(2000) }
        failure.get()?.let { throw AssertionError("Local test server failed", it) }
    }
    private fun temp(check: (File) -> Unit) {
        val file = Files.createTempFile("jarvis-update-test", ".part").toFile()
        try { check(file) } finally { file.delete() }
    }
    @Test fun publicDownloadReportsProgressAndVerifiesBytes() = temp { file ->
        server({ e -> e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.write(bytes) }) { url ->
            val progress = mutableListOf<Long>()
            runBlocking { UpdateDownload.fetch(OkHttpClient(), Request.Builder().url(url).build(), file, bytes.size.toLong(), sha(bytes)) { progress.add(it) } }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(0L, progress.first()); assertEquals(bytes.size.toLong(), progress.last())
            assertTrue(progress.size > 2)
        }
    }
    @Test fun interruptedDownloadResumesWithVerifiedRange() = temp { file ->
        val offset = 50000
        file.writeBytes(bytes.copyOfRange(0, offset))
        server({ e ->
            assertEquals("bytes=$offset-", e.requestHeaders.getFirst("Range"))
            e.responseHeaders.add("Content-Range", "bytes $offset-${bytes.size-1}/${bytes.size}")
            e.sendResponseHeaders(206, (bytes.size-offset).toLong())
            e.responseBody.write(bytes.copyOfRange(offset, bytes.size))
        }) { url ->
            runBlocking { UpdateDownload.fetch(OkHttpClient(), Request.Builder().url(url).build(), file, bytes.size.toLong(), sha(bytes)) {} }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun serverIgnoringRangeRestartsWithoutDuplicatingBytes() = temp { file ->
        file.writeBytes(bytes.copyOfRange(0, 50000))
        server({ e -> e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.write(bytes) }) { url ->
            runBlocking { UpdateDownload.fetch(OkHttpClient(), Request.Builder().url(url).build(), file, bytes.size.toLong(), sha(bytes)) {} }
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun incorrectChecksumIsRejectedAndPartialRemoved() = temp { file ->
        val corrupt = bytes.clone().also { it[0] = (it[0] + 1).toByte() }
        server({ e -> e.sendResponseHeaders(200, corrupt.size.toLong()); e.responseBody.write(corrupt) }) { url ->
            try {
                runBlocking { UpdateDownload.fetch(OkHttpClient(), Request.Builder().url(url).build(), file, bytes.size.toLong(), sha(bytes)) {} }
                fail("Corrupt download must not be accepted")
            } catch (error: IOException) { assertTrue(error.message.orEmpty().contains("verification failed")) }
            assertFalse(file.exists())
        }
    }
    @Test fun invalidResumeRangeCannotOverwriteExistingPartial() = temp { file ->
        val partial = bytes.copyOfRange(0, 50000); file.writeBytes(partial)
        server({ e ->
            e.responseHeaders.add("Content-Range", "bytes 0-${bytes.size-1}/${bytes.size}")
            e.sendResponseHeaders(206, bytes.size.toLong()); e.responseBody.write(bytes)
        }) { url ->
            try {
                runBlocking { UpdateDownload.fetch(OkHttpClient(), Request.Builder().url(url).build(), file, bytes.size.toLong(), sha(bytes)) {} }
                fail("Wrong range must fail")
            } catch (_: IllegalArgumentException) { }
            assertArrayEquals(partial, file.readBytes())
        }
    }
    @Test fun usefulErrorsAreNotRelabeledAsNetworkFailures() {
        assertEquals("Update signing identity does not match installed JARVIS", UpdateMessages.failure(IllegalArgumentException("Update signing identity does not match installed JARVIS")))
        assertTrue(UpdateMessages.failure(java.net.UnknownHostException()).contains("public update server"))
        assertTrue(UpdateMessages.failure(java.net.SocketTimeoutException()).contains("resume"))
    }
}
