package org.isdm.companion.ui

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Pure URL policy, shared by initial navigation, links, and resource lookup. */
internal object ReadingUrlPolicy {
    enum class Destination { WEBVIEW, EXTERNAL_BROWSER, BLOCK }

    private fun parsed(value: String?): URI? {
        if (value.isNullOrBlank() || value.any { it <= ' ' || it == '\\' }) return null
        val uri = try { URI(value) } catch (_: URISyntaxException) { return null }
        if (uri.isOpaque || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        return uri
    }

    fun isTrustedLmsUrl(value: String?): Boolean {
        val uri = parsed(value) ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("lms.isdm.org.in", ignoreCase = true) && uri.port in setOf(-1, 443)
    }

    fun isExternalWebUrl(value: String?): Boolean {
        val uri = parsed(value) ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) in setOf("https", "http")
    }

    fun destination(value: String?, isMainFrame: Boolean): Destination {
        if (isTrustedLmsUrl(value)) return Destination.WEBVIEW
        if (!isExternalWebUrl(value)) return Destination.BLOCK
        if (isMainFrame) return Destination.EXTERNAL_BROWSER
        // An HTTPS PDF/document frame must not launch an external app.
        // This is not a trust grant: scripts are only injected into the trusted LMS page.
        return if (parsed(value)?.scheme.equals("https", ignoreCase = true)) Destination.WEBVIEW
        else Destination.BLOCK
    }
}
