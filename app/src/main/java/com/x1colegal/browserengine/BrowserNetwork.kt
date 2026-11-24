package com.x1colegal.browserengine

import android.net.Uri
import org.mozilla.geckoview.GeckoSession

internal object BrowserNetwork {
    fun agent(desktop: Boolean): String {
        val mobileAgent = GeckoSession.getDefaultUserAgent()
        val agent = if (desktop) {
            "Mozilla/5.0 (X11; Desktop; rv:${mobileAgent.substringAfter("; rv:")}"
        } else {
            mobileAgent
        }
        return "$agent EngineBrowser/${BuildConfig.VERSION_NAME}"
    }

    fun mediaMime(url: String, contentType: String?): String? {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        val path = url.toHttpUriOrNull()?.encodedPath?.lowercase().orEmpty()
        return when {
            path.endsWith(".m3u8") || mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") ->
                "application/x-mpegURL"
            path.endsWith(".mpd") || mime == "application/dash+xml" -> "application/dash+xml"
            mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true -> mime
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".mp3") -> "audio/mpeg"
            else -> null
        }
    }
}

internal fun String.toHttpUriOrNull(): Uri? = Uri.parse(this).takeIf { uri ->
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
        !uri.host.isNullOrBlank()
}

internal fun String.sameOriginAs(other: String): Boolean {
    val a = toHttpUriOrNull() ?: return false
    val b = other.toHttpUriOrNull() ?: return false
    return a.scheme.equals(b.scheme, ignoreCase = true) &&
        a.host.equals(b.host, ignoreCase = true) &&
        a.port == b.port
}
