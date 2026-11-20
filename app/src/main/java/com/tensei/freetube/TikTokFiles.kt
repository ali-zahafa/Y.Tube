package com.tensei.freetube

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer

internal object TikTokFiles {
    private val client = OkHttpClient.Builder().followRedirects(false).build()

    fun downloadable(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        return uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            listOf("tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com", "tiktokv.com").any {
                host == it || host.endsWith(".$it")
            }
    }

    // Only used for an explicit download offered by the page.
    fun saveVideo(context: Context, url: String, dest: Uri) {
        require(downloadable(url))
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful && response.header("Content-Type").orEmpty().substringBefore(';') == "video/mp4")
            requireNotNull(context.contentResolver.openOutputStream(dest, "wt")).use { output ->
                requireNotNull(response.body).byteStream().copyTo(output)
            }
        }
    }

    fun saveAudio(context: Context, source: Uri, dest: Uri) {
        require(source != dest)
        val tmp = File.createTempFile("audio-", ".m4a", context.cacheDir)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val index = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            }
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val mux = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = mux.addTrack(format)
                mux.start()
                val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buffer = ByteBuffer.allocateDirect(size.coerceIn(65536, 4 * 1024 * 1024))
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val bytes = extractor.readSampleData(buffer, 0)
                    if (bytes < 0) break
                    check(bytes <= buffer.capacity())
                    info.set(0, bytes, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(track, buffer, info)
                    extractor.advance()
                }
                mux.stop()
            } finally { mux.release() }
            requireNotNull(context.contentResolver.openOutputStream(dest, "wt")).use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
        } finally {
            extractor.release()
            tmp.delete()
        }
    }
}
