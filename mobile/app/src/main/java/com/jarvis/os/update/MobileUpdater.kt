package com.jarvis.os.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jarvis.os.net.DeviceToolClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** User-started APK updates, pinned to this package and its installed signing certificate. */
class MobileUpdater(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()

    // Public downloads never receive the desktop pairing credential. Cross-scheme redirects are blocked.
    private val internet = http.newBuilder().followRedirects(true).followSslRedirects(false).build()
    private val publicRelease = "https://github.com/Jarvis-os-tech/JARVIS-Mobile/releases/download/mobile-stable/current.json"

    private data class Source(val release: JSONObject, val client: OkHttpClient, val apk: Request)

    private fun source(): Source {
        try {
            internet.newCall(Request.Builder().url(publicRelease).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Online release unavailable")
                val body = response.body ?: throw IOException("Empty release")
                val bytes = body.byteStream().use { it.readBytesLimited(65536) }
                val release = JSONObject(String(bytes, Charsets.UTF_8))
                val url = release.getString("apk_url")
                require(url.startsWith("https://github.com/Jarvis-os-tech/JARVIS-Mobile/releases/download/")) {
                    "Invalid online update location"
                }
                return Source(release, internet, Request.Builder().url(url).build())
            }
        } catch (_: IOException) {
            // A local development release remains usable without internet.
            val bridge = DeviceToolClient(context)
            bridge.declarations()
            http.newCall(bridge.pairedUpdateRequest("/v1/mobile-release").get().build()).execute().use { response ->
                check(response.isSuccessful) { "No update channel available. Check your internet connection." }
                val body = response.body ?: error("Empty release")
                val release = JSONObject(String(body.byteStream().use { it.readBytesLimited(65536) }, Charsets.UTF_8))
                return Source(release, http, bridge.pairedUpdateRequest("/v1/mobile-release/apk").get().build())
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Update information is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    suspend fun update(report: (String) -> Unit): String {
        report("Checking for updates…")
        val apk = withContext(Dispatchers.IO) {
            val source = source()
            val release = source.release
            val own = packageInfo()
            require(release.getString("package") == context.packageName) { "Update belongs to another app" }
            val version = release.getLong("version_code")
            if (version <= appVersion(own)) return@withContext null
            withContext(Dispatchers.Main) { report("Downloading update…") }
            val sha = release.getString("sha256")
            require(Regex("^[a-f0-9]{64}$").matches(sha))
            val size = release.getLong("size")
            require(size in 1..200000000)
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(directory, "jarvis-$version.apk")
            if (!target.exists() || target.length() != size || hash(target) != sha) {
                val temporary = File(directory, "download.part")
                try {
                    source.client.newCall(source.apk).execute().use { response ->
                        check(response.isSuccessful) { "Update download unavailable" }
                        val body = response.body ?: error("Empty update")
                        body.byteStream().use { source -> temporary.outputStream().use { destination ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= size) { "Invalid update size" }
                                destination.write(buffer, 0, count)
                            }
                        } }
                    }
                    require(temporary.length() == size && hash(temporary) == sha) { "Update verification failed" }
                    require(temporary.renameTo(target))
                } finally { temporary.delete() }
            }
            verify(target, version)
            target
        } ?: return "JARVIS is up to date."
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Allow updates from JARVIS, then tap Update JARVIS again."
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Update verified. Approve installation in Android."
    }

    private fun packageInfo(): PackageInfo = context.packageManager.getPackageInfo(context.packageName,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

    fun verify(apk: File, expectedVersion: Long) {
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
            ?: error("Invalid Android package")
        require(archive.packageName == context.packageName && appVersion(archive) == expectedVersion && expectedVersion > appVersion(packageInfo()))
        fun signers(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet()
        }
        val installed = signers(packageInfo())
        require(installed.isNotEmpty() && installed == signers(archive)) { "Update signing identity does not match installed JARVIS" }
    }

    private fun appVersion(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer);if (count < 0) break;digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
