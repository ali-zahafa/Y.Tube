package com.tensei.freetube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.IOException

internal object SuperDownloader {
    private const val WORKERS = 8

    suspend fun appendBatch(
        url: String,
        destination: File,
        from: Long,
        total: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val ranges = (0 until WORKERS).mapNotNull { worker ->
            val start = from + worker * DownloadFormats.CHUNK_BYTES
            if (start >= total) null
            else DownloadFormats.chunkEnd(start, total, DownloadFormats.CHUNK_BYTES)?.let { end -> start..end }
        }
        val parent = destination.parentFile ?: throw IOException("Download directory missing")
        val temp = File(parent, "ranges-${java.util.UUID.randomUUID()}")
        if (!temp.mkdir()) throw IOException("Could not create a temporary range directory")
        val context = currentCoroutineContext()
        try {
            val parts = coroutineScope {
                ranges.mapIndexed { index, range ->
                    async(Dispatchers.IO) {
                        File(temp, "$index.part").also { fetchRange(url, range, total, it) }
                    }
                }.awaitAll()
            }
            if (destination.length() != from) throw IOException("Download changed during transfer")
            java.io.FileOutputStream(destination, true).buffered().use { output ->
                for (part in parts) {
                    context.ensureActive()
                    part.inputStream().use { it.copyTo(output) }
                    onProgress(part.length())
                }
            }
            return destination.length()
        } finally {
            temp.deleteRecursively()
        }
    }

    private suspend fun fetchRange(url: String, range: LongRange, total: Long, file: File) {
        val context = currentCoroutineContext()
        val request = Request.Builder().url(url)
            .header("Range", "bytes=${range.first}-${range.last}")
            .header("Accept-Encoding", "identity")
            .header("User-Agent", YouTubeStreamHttp.userAgentForUrl(url))
            .build()
        Net.client.newCall(request).execute().use { response ->
            val expected = "bytes ${range.first}-${range.last}/$total"
            if (response.code != 206 || response.header("Content-Range") != expected) {
                throw IOException("Server did not return the requested byte range")
            }
            val body = response.body ?: throw IOException("Empty range response")
            val length = range.last - range.first + 1
            var remaining = length
            file.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        context.ensureActive()
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count < 0) throw IOException("Incomplete range response")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                    if (input.read() != -1) throw IOException("Range response exceeded its declared length")
                }
            }
        }
    }

}
