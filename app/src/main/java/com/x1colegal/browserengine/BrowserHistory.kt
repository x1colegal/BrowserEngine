package com.x1colegal.browserengine

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeoutException

internal enum class HistoryType { SITE, SEARCH }

internal data class HistoryEntry(
    val id: String,
    val type: HistoryType,
    val title: String,
    val url: String,
    val host: String,
    val timestamp: Long
)

internal class BrowserHistory private constructor(private val app: Context) {
    private val prefs = AppPrefs.get(app, "browser_history")
    private val faviconDir = app.filesDir.resolve("favicons")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val memoryCache = LruCache<String, ImageBitmap>(64)
    private val attemptedHosts = mutableSetOf<String>()
    val entries = mutableStateListOf<HistoryEntry>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    private val suppressedDownloadUrls = mutableMapOf<String, Long>()

    init {
        val records = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrElse { JSONArray() }
        repeat(records.length()) { index ->
            val item = records.optJSONObject(index) ?: return@repeat
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@repeat
            val type = runCatching { HistoryType.valueOf(item.optString("type")) }.getOrDefault(HistoryType.SITE)
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@repeat
            val title = item.optString("title").ifBlank { url }
            val host = item.optString("host")
            val timestamp = item.optLong("timestamp", 0L)
            entries.add(HistoryEntry(id, type, title, url, host, timestamp))
        }
    }

    fun recordSearch(query: String, url: String, incognito: Boolean) {
        if (incognito) return
        val clean = query.trim()
        if (clean.isEmpty() || url.toHttpUriOrNull() == null) return
        addEntry(HistoryEntry(UUID.randomUUID().toString(), HistoryType.SEARCH, clean, url, "", System.currentTimeMillis()))
    }

    fun suppressDownloadUrl(url: String) {
        val now = System.currentTimeMillis()
        suppressedDownloadUrls[url] = now
        suppressedDownloadUrls.entries.removeAll { now - it.value > 10_000L }
        if (entries.removeAll { it.type == HistoryType.SITE && it.url == url }) {
            save()
        }
    }

    fun recordSite(url: String, title: String, executor: GeckoWebExecutor, incognito: Boolean) {
        if (incognito) return
        val suppressedAt = suppressedDownloadUrls[url]
        if (suppressedAt != null && System.currentTimeMillis() - suppressedAt <= 10_000L) return
        val uri = url.toHttpUriOrNull() ?: return
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return
        val displayTitle = title.trim().ifBlank { host }
        val existingIndex = entries.indexOfFirst { it.type == HistoryType.SITE && it.url == url }
        if (existingIndex == 0) {
            entries[0] = entries[0].copy(title = displayTitle, timestamp = System.currentTimeMillis())
            save()
        } else {
            if (existingIndex > 0) entries.removeAt(existingIndex)
            addEntry(HistoryEntry(UUID.randomUUID().toString(), HistoryType.SITE, displayTitle, url, host, System.currentTimeMillis()))
        }
        ensureFavicon(uri, host, executor)
    }

    fun recordLocalFile(fileUri: String, title: String, incognito: Boolean) {
        if (incognito || fileUri.isBlank()) return
        val displayTitle = title.trim().ifBlank { fileUri.substringAfterLast('/').ifBlank { "Local file" } }
        val existingIndex = entries.indexOfFirst { it.type == HistoryType.SITE && it.url == fileUri }
        if (existingIndex == 0) {
            entries[0] = entries[0].copy(title = displayTitle, timestamp = System.currentTimeMillis())
            save()
        } else {
            if (existingIndex > 0) entries.removeAt(existingIndex)
            addEntry(HistoryEntry(UUID.randomUUID().toString(), HistoryType.SITE, displayTitle, fileUri, "", System.currentTimeMillis()))
        }
    }

    fun iconFor(host: String): ImageBitmap? {
        if (host.isBlank()) return null
        icons[host]?.let { return it }
        memoryCache.get(host)?.let {
            icons[host] = it
            return it
        }
        val file = faviconFile(host)
        if (file.isFile) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
                }
                if (bmp != null) {
                    memoryCache.put(host, bmp)
                    icons[host] = bmp
                }
            }
        }
        return null
    }

    private fun ensureFavicon(uri: Uri, host: String, executor: GeckoWebExecutor) {
        if (icons.containsKey(host) || memoryCache.get(host) != null) return
        val file = faviconFile(host)
        if (file.isFile) {
            iconFor(host)
            return
        }
        if (!attemptedHosts.add(host)) return
        val scheme = uri.scheme?.lowercase() ?: "https"
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val iconUrl = "$scheme://$host$port/favicon.ico"
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val req = WebRequest.Builder(iconUrl).header("User-Agent", BrowserNetwork.agent(false)).build()
                    val res = runCatching {
                        BrowserNetwork.fetchBlocking(executor, req, GeckoWebExecutor.FETCH_FLAGS_NO_REDIRECTS, 8_000L)
                    }.getOrNull()
                    if (res == null || res.statusCode !in 200..299 || res.body == null) {
                        res?.body?.let { runCatching { it.close() } }
                        return@runCatching null
                    }
                    val bytes = res.body!!.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(4096)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (out.size() + n > 256 * 1024) return@use null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: return@runCatching null
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
                    if (!faviconDir.exists()) faviconDir.mkdirs()
                    file.writeBytes(bytes)
                    decoded.asImageBitmap()
                }.getOrNull()
            }
            if (bmp != null) {
                memoryCache.put(host, bmp)
                icons[host] = bmp
            }
        }
    }

    fun remove(id: String) {
        entries.removeAll { it.id == id }
        save()
    }

    fun clear() {
        entries.clear()
        save()
        scope.launch(Dispatchers.IO) {
            runCatching { faviconDir.deleteRecursively() }
            memoryCache.evictAll()
        }
        icons.clear()
        attemptedHosts.clear()
    }

    private fun addEntry(entry: HistoryEntry) {
        entries.add(0, entry)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        save()
    }

    private fun faviconFile(host: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return faviconDir.resolve("$hex.ico")
    }

    private fun save() {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("type", entry.type.name)
                    .put("title", entry.title)
                    .put("url", entry.url)
                    .put("host", entry.host)
                    .put("timestamp", entry.timestamp)
            )
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    companion object {
        private const val MAX_ENTRIES = 300
        private var instance: BrowserHistory? = null

        fun get(context: Context): BrowserHistory =
            instance ?: BrowserHistory(context.applicationContext).also { instance = it }
    }
}
