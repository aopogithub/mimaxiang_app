package com.example.vaultbox.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class FaviconRepository(context: Context) {
    private val cacheDir = File(context.applicationContext.filesDir, "favicons").apply { mkdirs() }

    fun cachedIconFile(website: String): File? {
        val host = normalizedHost(website) ?: return null
        val file = iconFile(host)
        return if (file.exists() && file.length() > 0L) file else null
    }

    fun cachedBitmap(website: String): Bitmap? {
        val file = cachedIconFile(website) ?: return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun fetchIfNeeded(website: String): Bitmap? {
        val host = normalizedHost(website) ?: return null
        val file = iconFile(host)
        if (file.exists() && file.length() > 0L) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }

        val schemes = if (website.startsWith("http://", ignoreCase = true)) listOf("http") else listOf("https", "http")
        for (scheme in schemes) {
            val bitmap = runCatching { fetch("$scheme://$host/favicon.ico") }.getOrNull() ?: continue
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            return bitmap
        }
        return null
    }

    private fun fetch(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VaultBox/0.1")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType.orEmpty()
            if (!contentType.startsWith("image/", ignoreCase = true) && !url.endsWith(".ico")) return null
            connection.inputStream.use(BitmapFactory::decodeStream)
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizedHost(website: String): String? {
        val trimmed = website.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return runCatching { URL(withScheme).host.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun iconFile(host: String): File =
        File(cacheDir, "${host.sha256()}.png")

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
