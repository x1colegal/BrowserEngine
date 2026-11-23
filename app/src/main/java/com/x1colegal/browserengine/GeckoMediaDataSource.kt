package com.x1colegal.browserengine

import android.net.Uri
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.upstream.BaseDataSource
import com.google.android.exoplayer2.upstream.DataSpec
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

private const val RANGE_BYTES = 8L * 1024 * 1024
private const val UNKNOWN_LENGTH = -1L
private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

internal class MediaRangeRequiredException :
    IOException("This media server must support HTTP byte ranges for large files")

@Suppress("DEPRECATION")
internal class GeckoMediaDataSource(
    private val executor: GeckoWebExecutor,
    private val userAgent: String,
    private val referer: String?
) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var resolvedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var stream: InputStream? = null
    private var position = 0L
    private var remaining = UNKNOWN_LENGTH
    private var chunkRemaining = 0L
    private var ranged = false
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        try {
            openChunk(dataSpec)
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (error: Exception) {
            try {
                close()
            } catch (closeError: IOException) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    private fun openChunk(dataSpec: DataSpec) {
        val url = dataSpec.uri.toString()
        val target = url.toHttpUriOrNull() ?: throw IOException("Unsupported media URL")
        val request = WebRequest.Builder(url).method(dataSpec.httpMethodString)
        for ((key, value) in dataSpec.httpRequestHeaders) request.header(key, value)
        request.header("User-Agent", userAgent)
        request.header("Accept-Encoding", "identity")
        referer?.toHttpUriOrNull()?.let { origin ->
            if (origin.host == target.host && origin.scheme == target.scheme && origin.port == target.port) {
                request.referrer(origin.buildUpon().fragment(null).build().toString())
            }
        }
        val rangeLength = if (remaining == UNKNOWN_LENGTH) RANGE_BYTES else minOf(RANGE_BYTES, remaining)
        val requestRange = dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && rangeLength > 0
        if (requestRange) request.header("Range", "bytes=$position-${position + rangeLength - 1}")
        dataSpec.httpBody?.let { body ->
            request.body(ByteBuffer.allocateDirect(body.size).apply { put(body); flip() })
        }

        val next = fetch(request.build())
        if (resolvedUri == null) {
            resolvedUri = Uri.parse(next.uri)
            responseHeaders = next.headers.mapValues { listOf(it.value) }
        }
        stream = next.body
        val status = next.statusCode
        when {
            status == 416 && next.header("Content-Range") == "bytes */$position" -> {
                remaining = 0
                chunkRemaining = 0
                closeChunk()
            }
            status !in 200..299 -> throw IOException("Media request returned HTTP $status")
            dataSpec.httpMethod == DataSpec.HTTP_METHOD_HEAD || status == 204 || status == 205 -> {
                remaining = 0
                chunkRemaining = 0
                closeChunk()
            }
            status == 206 && requestRange -> {
                val contentRange = next.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
                    ?: throw IOException("Media response has an invalid byte range")
                val first = contentRange.groupValues[1].toLongOrNull()
                val last = contentRange.groupValues[2].toLongOrNull()
                val total = contentRange.groupValues[3].toLongOrNull()
                if (first != position || last == null || last < position ||
                    last - position >= rangeLength || total != null && last >= total
                ) throw IOException("Media response is outside the requested byte range")
                if (stream == null) throw IOException("Media response has no body")
                chunkRemaining = last - position + 1
                if (total != null) {
                    val available = total - position
                    remaining = if (remaining == UNKNOWN_LENGTH) available else minOf(remaining, available)
                }
                ranged = true
            }
            else -> openUnranged(next)
        }
    }

    private fun openUnranged(next: WebResponse) {
        if (ranged) throw IOException("The server stopped honoring media byte ranges")
        val input = stream ?: throw IOException("Media response has no body")
        val length = next.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
        if (length != null && length > RANGE_BYTES || position > RANGE_BYTES) {
            throw MediaRangeRequiredException()
        }
        val size = if (length != null) {
            skipFully(input, position)
            length
        } else {
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > RANGE_BYTES) {
                    throw MediaRangeRequiredException()
                }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            if (position > bytes.size) throw EOFException("Media position is past the end of the file")
            closeChunk()
            stream = ByteArrayInputStream(bytes, position.toInt(), bytes.size - position.toInt())
            bytes.size.toLong()
        }
        val available = (size - position).coerceAtLeast(0)
        remaining = if (remaining == UNKNOWN_LENGTH) available else minOf(remaining, available)
        chunkRemaining = remaining
    }

    private fun fetch(request: WebRequest): WebResponse {
        val result = executor.fetch(request)
        return try {
            result.poll(25_000) ?: throw IOException("No media response")
        } catch (error: TimeoutException) {
            result.withHandler(android.os.Handler(android.os.Looper.getMainLooper()))
                .accept({ it?.body?.let { body -> runCatching { body.close() } } }, {})
            throw IOException("Media request timed out", error)
        } catch (error: Exception) {
            throw IOException("Gecko media request failed", error)
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        if (count == 0L) return
        val buffer = ByteArray(8192)
        var left = count
        while (left > 0) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException("Media response ended before the requested position")
            left -= read
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L && ranged) {
            closeChunk()
            openChunk(spec ?: throw IOException("Media data source is closed"))
        }
        if (remaining == 0L || chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), chunkRemaining).toInt()
        val count = stream?.read(buffer, offset, requested) ?: C.RESULT_END_OF_INPUT
        if (count < 0) throw EOFException("Media response ended before the advertised length")
        if (count > 0) {
            position += count
            chunkRemaining -= count
            if (remaining != UNKNOWN_LENGTH) remaining -= count
            bytesTransferred(count)
            if (chunkRemaining == 0L) closeChunk()
        }
        return count
    }

    private fun WebResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    override fun getUri(): Uri? = resolvedUri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    private fun closeChunk() {
        val input = stream
        stream = null
        input?.close()
    }

    override fun close() {
        try {
            closeChunk()
        } finally {
            spec = null
            resolvedUri = null
            responseHeaders = emptyMap()
            remaining = UNKNOWN_LENGTH
            chunkRemaining = 0
            ranged = false
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
