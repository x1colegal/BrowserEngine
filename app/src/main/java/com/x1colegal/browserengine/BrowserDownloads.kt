package com.x1colegal.browserengine

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeoutException

internal data class BrowserDownload(
    val id: String,
    val name: String,
    val mime: String,
    val state: DownloadState
)

internal enum class DownloadState(val label: String) {
    DOWNLOADING("Downloading…"), COMPLETE("Complete"), FAILED("Failed"), INTERRUPTED("Interrupted")
}

internal class BrowserDownloads private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directory = app.filesDir.resolve("downloads")
    val entries = mutableStateListOf<BrowserDownload>()

    init {
        val records = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrElse { JSONArray() }
        repeat(records.length()) { index ->
            val item = records.optJSONObject(index) ?: return@repeat
            val id = item.optString("id")
            if (!id.matches(Regex("[a-f0-9-]{36}"))) return@repeat
            val state = runCatching { DownloadState.valueOf(item.optString("state")) }
                .getOrDefault(DownloadState.INTERRUPTED)
            val name = cleanName(item.optString("name"))
            val previous = BrowserDownload(id, name, item.optString("mime", "application/octet-stream"), state)
            if (state == DownloadState.DOWNLOADING) {
                File(directory.resolve(id), "download.part").delete()
                entries.add(previous.copy(state = if (file(previous).isFile) DownloadState.COMPLETE
                    else DownloadState.INTERRUPTED))
            } else entries.add(previous)
        }
        save()
    }

    fun receive(response: WebResponse) {
        val body = response.body ?: return
        val mime = response.header("Content-Type")?.substringBefore(';') ?: "application/octet-stream"
        val header = response.header("Content-Disposition").orEmpty()
        val encoded = Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)?.groupValues?.get(1)
        val plain = Regex("""(?i)filename\s*=\s*(?:"([^"]+)"|([^;]+))""").find(header)
        val named = encoded?.let(Uri::decode) ?: plain?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val name = cleanName(named ?: Uri.parse(response.uri).lastPathSegment.orEmpty())
        val entry = BrowserDownload(UUID.randomUUID().toString(), name, mime, DownloadState.DOWNLOADING)
        entries.add(0, entry)
        save()
        scope.launch {
            val finished = withContext(Dispatchers.IO) {
                val dir = directory.resolve(entry.id)
                val part = dir.resolve("download.part")
                try {
                    body.use { input ->
                        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create download directory")
                        part.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                        if (!part.renameTo(file(entry))) throw IOException("Cannot finish download")
                    }
                    true
                } catch (_: IOException) {
                    part.delete()
                    false
                }
            }
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) {
                entries[index] = entry.copy(state = if (finished) DownloadState.COMPLETE else DownloadState.FAILED)
                save()
            }
        }
    }

    fun fetch(executor: GeckoWebExecutor, url: String, desktop: Boolean) {
        if (url.toHttpUriOrNull() == null) return
        scope.launch {
            val response = withContext(Dispatchers.IO) {
                val request = WebRequest.Builder(url).header("User-Agent", BrowserNetwork.agent(desktop)).build()
                val pending = executor.fetch(request)
                try {
                    pending.poll(20_000)
                } catch (_: TimeoutException) {
                    pending.withHandler(Handler(Looper.getMainLooper()))
                        .accept({ it?.body?.let { stream -> runCatching { stream.close() } } }, {})
                    null
                } catch (_: Exception) {
                    null
                }
            }
            if (response != null && response.statusCode in 200..299 && response.body != null) receive(response)
            else {
                response?.body?.let { runCatching { it.close() } }
                Toast.makeText(app, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun open(context: Context, entry: BrowserDownload) {
        val file = file(entry)
        if (entry.state != DownloadState.COMPLETE || !file.isFile) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun file(entry: BrowserDownload) = directory.resolve(entry.id).resolve(entry.name)

    private fun save() {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("id", entry.id).put("name", entry.name)
                .put("mime", entry.mime).put("state", entry.state.name))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    companion object {
        private var instance: BrowserDownloads? = null

        fun get(context: Context): BrowserDownloads =
            instance ?: BrowserDownloads(context.applicationContext).also { instance = it }

        private fun cleanName(value: String): String = value
            .replace(Regex("""[/\\\x00-\x1f\x7f]"""), "_")
            .trim().trim('.').take(120).ifBlank { "download" }
    }
}

internal fun WebResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
