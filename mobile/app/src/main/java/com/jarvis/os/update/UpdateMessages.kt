package com.jarvis.os.update

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal object UpdateMessages {
    fun failure(error: Exception): String = when (error) {
        is UnknownHostException -> "Cannot reach the public update server. Try another internet connection or Open public download."
        is SocketTimeoutException -> "The update server timed out. Tap Update again to resume, or Open public download."
        is SSLException -> "The secure update connection failed. Check the phone date/time or Open public download."
        else -> error.message?.take(220)?.takeIf { it.isNotBlank() }
            ?: "Update could not finish. Tap Update again or Open public download."
    }
}
