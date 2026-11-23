package com.x1colegal.browserengine

import android.net.Uri

internal object BrowserNetwork {
    fun agent(desktop: Boolean): String {
        val platform = if (desktop) "X11; Linux x86_64" else "Android Cinnamon Bun"
        return "Mozilla/5.0 ($platform) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/157.0.0.0 Safari/537.36 EngineBrowser/${BuildConfig.VERSION_NAME}"
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
