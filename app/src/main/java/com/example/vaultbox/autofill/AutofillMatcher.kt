package com.example.vaultbox.autofill

import java.net.IDN
import java.net.URI
import java.util.Locale

data class AutofillTarget(val website: String, val appPackage: String)

object AutofillMatcher {
    const val APP_PACKAGE_SEPARATOR = ","

    fun matches(target: AutofillTarget, appPackage: String?, webDomain: String?): Boolean {
        // A web page must match its own host, never the browser or WebView's app association.
        if (!webDomain.isNullOrBlank()) {
            val host = normalizeHost(webDomain) ?: return false
            return host == normalizeHost(target.website)
        }
        val packageName = appPackage?.trim().orEmpty()
        if (packageName.isBlank() || isBrowserPackage(packageName)) return false
        return packages(target.appPackage).any { it == packageName }
    }

    fun normalizeHost(value: String?): String? = runCatching {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = URI(when {
            clean.startsWith("//") -> "https:$clean"
            "://" in clean -> clean
            else -> "https://$clean"
        })
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        val authority = uri.rawAuthority ?: return null
        if ('@' in authority || '%' in authority || '\\' in authority) return null
        val host = uri.host ?: run {
            // URI.host is null for Unicode domains. Validate their ASCII form as well.
            val parts = authority.split(':')
            if (parts.size > 2) return null
            if (parts.size == 2 && parts[1].toIntOrNull() !in 0..65535) return null
            val ascii = IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES)
            URI("https://$ascii").host ?: return null
        }
        if (uri.port > 65535) return null
        host.lowercase(Locale.ROOT).trimEnd('.').removePrefix("www.").takeIf { it.isNotBlank() }
    }.getOrNull()

    fun packages(value: String): List<String> =
        value.split(APP_PACKAGE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun isBrowserPackage(packageName: String): Boolean =
        packageName.lowercase(Locale.ROOT) in BROWSER_PACKAGES ||
            packageName.contains("browser", ignoreCase = true)

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.android.browser", "org.mozilla.firefox", "org.mozilla.firefox_beta",
        "org.mozilla.fenix", "org.mozilla.focus", "com.microsoft.emmx",
        "com.opera.mini.native", "com.opera.touch", "com.duckduckgo.mobile.android"
    )
}
