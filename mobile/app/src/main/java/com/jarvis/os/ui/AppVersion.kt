package com.jarvis.os.ui

import android.content.Context

internal fun installedVersionLabel(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName ?: info.versionCode.toString()}"
} catch (_: Exception) { "Version unavailable" }
