package com.ytube.player.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import com.ytube.player.engine.StreamFallbackEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

object SuperDownloader {

    /**
     * Multi-threaded Chunk Downloader (NewPipe GigaGet style).
     * Splits the stream into parallel HTTP Range chunks to defeat YouTube bandwidth throttling.
     */
    suspend fun downloadFileMultiThreaded(
        fileUrl: String,
        destinationFile: File,
        threadCount: Int = 8,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val profile = StreamFallbackEngine.getActiveProfile()
            val headConn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", profile.userAgent)
                connectTimeout = 8000
            }
            val totalSize = headConn.contentLengthLong
            headConn.disconnect()

            if (totalSize <= 0L) return@withContext false

            RandomAccessFile(destinationFile, "rw").use { it.setLength(totalSize) }

            val chunkSize = totalSize / threadCount
            val downloadedPerThread = LongArray(threadCount)

            val jobs = (0 until threadCount).map { index ->
                async(Dispatchers.IO) {
                    val startByte = index * chunkSize
                    val endByte = if (index == threadCount - 1) totalSize - 1 else (startByte + chunkSize - 1)

                    val conn = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", profile.userAgent)
                        setRequestProperty("Range", "bytes=$startByte-$endByte")
                        connectTimeout = 10_000
                        readTimeout = 15_000
                    }

                    conn.inputStream.use { input ->
                        RandomAccessFile(destinationFile, "rw").use { raf ->
                            raf.seek(startByte)
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                raf.write(buffer, 0, bytesRead)
                                downloadedPerThread[index] += bytesRead.toLong()
                                val totalDownloaded = downloadedPerThread.sum()
                                onProgress(totalDownloaded.toFloat() / totalSize.toFloat())
                            }
                        }
                    }
                    conn.disconnect()
                }
            }
            jobs.awaitAll()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Local On-Device DASH Muxer (Seal / NewPipe style).
     * Merges separate 1080p/4K video-only stream and M4A audio-only stream into a single MP4 file without FFmpeg bloat.
     */
    suspend fun muxVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outputMp4File: File
    ): Boolean = withContext(Dispatchers.IO) {
        val muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString( android.media.MediaFormat.KEY_MIME ) ?: ""
                if (mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i)
                    videoTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            var audioTrackIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString( android.media.MediaFormat.KEY_MIME ) ?: ""
                if (mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            if (videoTrackIndex == -1 || audioTrackIndex == -1) return@withContext false

            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024) // 1 MB buffer
            val bufferInfo = MediaCodec.BufferInfo()

            // Write Video Samples
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // Write Audio Samples
            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                audioExtractor.advance()
            }

            muxer.stop()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
        }
    }
}
