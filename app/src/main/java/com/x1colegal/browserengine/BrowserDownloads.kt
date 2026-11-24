package com.x1colegal.browserengine

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeoutException

internal data class BrowserDownload(
    val id: String,
    val name: String,
    val mime: String,
    val state: DownloadState,
    val contentUri: String? = null,
    val filePath: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = -1L
)

internal enum class DownloadState(val label: String) {
    DOWNLOADING("Downloading…"), COMPLETE("Complete"), FAILED("Failed"), INTERRUPTED("Interrupted")
}

internal class BrowserDownloads private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeCancels = mutableMapOf<String, () -> Unit>()
    val entries = mutableStateListOf<BrowserDownload>()
    var permissionRequester: (((Boolean) -> Unit) -> Unit)? = null

    init {
        val records = runCatching { JSONArray(prefs.getString("entries", "[]")) }.getOrElse { JSONArray() }
        repeat(records.length()) { index ->
            val item = records.optJSONObject(index) ?: return@repeat
            val id = item.optString("id")
            if (!id.matches(Regex("[a-f0-9-]{36}"))) return@repeat
            val state = runCatching { DownloadState.valueOf(item.optString("state")) }
                .getOrDefault(DownloadState.INTERRUPTED)
            val name = cleanName(item.optString("name"))
            val contentUri = item.optString("contentUri").takeIf { it.isNotBlank() }
            val filePath = item.optString("filePath").takeIf { it.isNotBlank() }
            val previous = BrowserDownload(
                id = id,
                name = name,
                mime = item.optString("mime", "application/octet-stream"),
                state = if (state == DownloadState.DOWNLOADING) DownloadState.INTERRUPTED else state,
                contentUri = contentUri,
                filePath = filePath,
                bytesDownloaded = item.optLong("bytesDownloaded", 0L),
                totalBytes = item.optLong("totalBytes", -1L)
            )
            entries.add(previous)
        }
        save()
    }

    private fun withStoragePermission(onGranted: () -> Unit, onDenied: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
            return
        }
        val requester = permissionRequester
        if (requester == null) {
            Toast.makeText(app, "Storage permission is required to download files", Toast.LENGTH_SHORT).show()
            onDenied()
            return
        }
        requester { granted ->
            if (granted) onGranted()
            else {
                Toast.makeText(app, "Storage permission denied", Toast.LENGTH_SHORT).show()
                onDenied()
            }
        }
    }

    fun receive(response: WebResponse, incognito: Boolean = false) {
        val body = response.body ?: return
        withStoragePermission(
            onGranted = { startReceive(response, body, incognito) },
            onDenied = { runCatching { body.close() } }
        )
    }

    private fun startReceive(response: WebResponse, body: InputStream, incognito: Boolean) {
        val mime = response.header("Content-Type")?.substringBefore(';')?.trim()?.ifBlank { null }
            ?: "application/octet-stream"
        val header = response.header("Content-Disposition").orEmpty()
        val encoded = Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(header)?.groupValues?.get(1)
        val plain = Regex("""(?i)filename\s*=\s*(?:"([^"]+)"|([^;]+))""").find(header)
        val named = encoded?.let(Uri::decode) ?: plain?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val name = cleanName(named ?: Uri.parse(response.uri).lastPathSegment.orEmpty())
        val totalBytes = response.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: -1L
        val entry = BrowserDownload(
            id = UUID.randomUUID().toString(),
            name = name,
            mime = mime,
            state = DownloadState.DOWNLOADING,
            totalBytes = totalBytes
        )
        entries.add(0, entry)
        if (!incognito) save()

        val job = scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                writeDownload(entry, body, totalBytes) { bytes, speed, eta ->
                    scope.launch {
                        val idx = entries.indexOfFirst { it.id == entry.id }
                        if (idx >= 0 && entries[idx].state == DownloadState.DOWNLOADING) {
                            entries[idx] = entries[idx].copy(
                                bytesDownloaded = bytes,
                                speedBytesPerSec = speed,
                                etaSeconds = eta
                            )
                        }
                    }
                }
            }
            activeJobs.remove(entry.id)
            activeCancels.remove(entry.id)
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) {
                entries[index] = entries[index].copy(
                    name = outcome.finalName ?: entry.name,
                    state = if (outcome.success) DownloadState.COMPLETE else DownloadState.FAILED,
                    contentUri = outcome.contentUri,
                    filePath = outcome.filePath,
                    bytesDownloaded = outcome.bytesWritten,
                    speedBytesPerSec = 0L,
                    etaSeconds = -1L
                )
                if (!incognito) save()
            }
            if (!outcome.success && isActive) {
                Toast.makeText(app, "Download failed", Toast.LENGTH_SHORT).show()
            }
        }
        activeJobs[entry.id] = job
    }

    private data class WriteOutcome(
        val success: Boolean,
        val contentUri: String? = null,
        val filePath: String? = null,
        val finalName: String? = null,
        val bytesWritten: Long = 0L
    )

    private fun writeDownload(
        entry: BrowserDownload,
        body: InputStream,
        totalBytes: Long,
        onProgress: (Long, Long, Long) -> Unit
    ): WriteOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                put(MediaStore.Downloads.MIME_TYPE, entry.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return WriteOutcome(false)
            activeCancels[entry.id] = {
                runCatching { body.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
            return try {
                val written = body.use { input ->
                    val out = resolver.openOutputStream(uri) ?: throw IOException("Cannot open MediaStore stream")
                    out.use { output -> copyWithProgress(input, output, totalBytes, onProgress) }
                }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                val actualName = queryDisplayName(uri) ?: entry.name
                WriteOutcome(true, contentUri = uri.toString(), finalName = actualName, bytesWritten = written)
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                WriteOutcome(false)
            }
        }

        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return WriteOutcome(false)
        val target = uniqueFile(dir, entry.name)
        val part = File(dir, ".${target.name}.${entry.id.take(8)}.part")
        activeCancels[entry.id] = {
            runCatching { body.close() }
            runCatching { part.delete() }
        }
        return try {
            val written = body.use { input ->
                part.outputStream().use { output -> copyWithProgress(input, output, totalBytes, onProgress) }
            }
            if (!part.renameTo(target)) throw IOException("Cannot finish download")
            MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), arrayOf(entry.mime), null)
            WriteOutcome(true, filePath = target.absolutePath, finalName = target.name, bytesWritten = written)
        } catch (_: Exception) {
            part.delete()
            WriteOutcome(false)
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Long, Long, Long) -> Unit
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var windowBytes = 0L
        var lastTick = System.nanoTime()
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
            windowBytes += read
            val now = System.nanoTime()
            val elapsedNs = now - lastTick
            if (elapsedNs >= 450_000_000L) {
                val speed = (windowBytes * 1_000_000_000L) / elapsedNs.coerceAtLeast(1L)
                val eta = if (totalBytes > total && speed > 0) (totalBytes - total) / speed else -1L
                onProgress(total, speed, eta)
                lastTick = now
                windowBytes = 0L
            }
        }
        output.flush()
        return total
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    fun fetch(executor: GeckoWebExecutor, url: String, desktop: Boolean, incognito: Boolean = false) {
        if (url.toHttpUriOrNull() == null) return
        withStoragePermission(onGranted = {
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
                if (response != null && response.statusCode in 200..299 && response.body != null) {
                    startReceive(response, response.body!!, incognito)
                } else {
                    response?.body?.let { runCatching { it.close() } }
                    Toast.makeText(app, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    fun open(context: Context, entry: BrowserDownload) {
        if (entry.state != DownloadState.COMPLETE) return
        val uri = resolveViewUri(context, entry) ?: run {
            Toast.makeText(context, "Downloaded file not found", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun cancelAndDelete(entry: BrowserDownload) {
        activeJobs.remove(entry.id)?.cancel()
        val cancelHook = activeCancels.remove(entry.id)
        entries.removeAll { it.id == entry.id }
        save()
        if (cancelHook != null) {
            scope.launch(Dispatchers.IO) { cancelHook() }
        }
    }

    fun delete(entry: BrowserDownload) {
        scope.launch {
            withContext(Dispatchers.IO) {
                entry.contentUri?.let { raw ->
                    runCatching { app.contentResolver.delete(Uri.parse(raw), null, null) }
                }
                entry.filePath?.let { path ->
                    runCatching { File(path).delete() }
                }
            }
            entries.removeAll { it.id == entry.id }
            save()
        }
    }

    fun rename(entry: BrowserDownload, rawNewName: String) {
        val cleaned = cleanName(rawNewName)
        if (cleaned == entry.name) return
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && entry.contentUri != null) {
                    val uri = Uri.parse(entry.contentUri)
                    val ok = runCatching {
                        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, cleaned) }
                        app.contentResolver.update(uri, values, null, null) > 0
                    }.getOrDefault(false)
                    if (ok) entry.copy(name = queryDisplayName(uri) ?: cleaned) else null
                } else if (entry.filePath != null) {
                    val current = File(entry.filePath)
                    val parent = current.parentFile ?: return@withContext null
                    val target = uniqueFile(parent, cleaned)
                    if (current.exists() && current.renameTo(target)) {
                        MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), arrayOf(entry.mime), null)
                        entry.copy(name = target.name, filePath = target.absolutePath)
                    } else null
                } else null
            }
            if (updated != null) {
                val index = entries.indexOfFirst { it.id == entry.id }
                if (index >= 0) {
                    entries[index] = updated
                    save()
                }
            } else {
                Toast.makeText(app, "Could not rename file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveViewUri(context: Context, entry: BrowserDownload): Uri? {
        entry.contentUri?.let { return Uri.parse(it) }
        entry.filePath?.let { path ->
            val file = File(path)
            if (file.isFile) {
                return FileProvider.getUriForFile(context, "${context.packageName}.downloads", file)
            }
        }
        return null
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var count = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($count)$ext")
            count++
        }
        return candidate
    }

    private fun save() {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("mime", entry.mime)
                    .put("state", entry.state.name)
                    .put("contentUri", entry.contentUri ?: "")
                    .put("filePath", entry.filePath ?: "")
                    .put("bytesDownloaded", entry.bytesDownloaded)
                    .put("totalBytes", entry.totalBytes)
            )
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

        fun formatSpeed(bytesPerSec: Long): String = when {
            bytesPerSec >= 1_000_000_000L -> String.format("%.2f GB/s", bytesPerSec / 1_000_000_000.0)
            bytesPerSec >= 1_000_000L -> String.format("%.2f MB/s", bytesPerSec / 1_000_000.0)
            bytesPerSec >= 1_000L -> String.format("%.1f KB/s", bytesPerSec / 1_000.0)
            else -> "$bytesPerSec B/s"
        }

        fun formatEta(seconds: Long): String = when {
            seconds < 0 -> "Estimating…"
            seconds < 60 -> "${seconds}s left"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m left"
        }
    }
}

internal fun WebResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
