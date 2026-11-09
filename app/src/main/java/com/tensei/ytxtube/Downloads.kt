package com.tensei.ytxtube

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat as AndroidMediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat as StreamFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

private const val TAG = "YTXTubeDl"

enum class DownloadStatus { Queued, Downloading, Processing, Paused, Complete, Failed }





@Stable
class DownloadTask(
    val videoId: String,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val durationSec: Long,
    val height: Int,
    val fileName: String,
    val container: String = "mp4",
    val hdr: Boolean = false,
    downloaded: Long = 0L,
    total: Long = -1L,
    status: DownloadStatus = DownloadStatus.Queued,
    directory: String? = null,
    detail: String = "",
    /** itag of the audio stream the user picked in the Audio only section; 0 = default (best M4A). */
    val audioItag: Int = 0,
) {
    val key: String get() = if (audioItag > 0) "$videoId@a$audioItag" else "$videoId@$height"

    /** Folder this download is written to; null means the current download folder (older entries). */
    var directory by mutableStateOf(directory)
        internal set

    /** Real format of the saved file, e.g. "M4A · AAC · 129 kbps"; empty until known. */
    var detail by mutableStateOf(detail)
        internal set

    var downloaded by mutableStateOf(downloaded)
        internal set
    var total by mutableStateOf(total)
        internal set
    var status by mutableStateOf(status)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set

    val progress: Float
        get() = when {
            status == DownloadStatus.Complete || status == DownloadStatus.Processing -> 1f
            total > 0L -> (downloaded.toFloat() / total).coerceIn(0f, 1f)
            else -> 0f
        }

    val file: File get() = directory?.let { File(it, fileName) } ?: Downloads.fileFor(fileName)
    val localUri: String get() = Uri.fromFile(file).toString()
    val played: Boolean get() = status == DownloadStatus.Complete && file.exists()
}


data class DownloadOption(
    val height: Int,
    val muxed: VideoStream?,
    val video: VideoStream?,
    val audio: AudioStream?,
    val estimatedBytes: Long,
    val container: String = "mp4",
    val hdr: Boolean = false,
    /** True when [estimatedBytes] is the exact size reported by YouTube, false for a bitrate estimate. */
    val sizeExact: Boolean = false,
) {
    val needsMux: Boolean get() = muxed == null
    val label: String get() = "${height}p${if (hdr) " HDR" else ""}"
    val containerLabel: String get() = DownloadFormats.containerLabel(container, audioOnly = false)

    /** e.g. "MP4 · H.264 + AAC 128 kbps", built only from what the streams report. */
    val formatDetail: String
        get() {
            val v = muxed ?: video
            val audioCodec = if (muxed != null) muxed.codec?.substringAfter(',', "")?.trim()?.ifBlank { null } ?: "mp4a" else audio?.codec
            val kbps = if (muxed != null) 0 else DownloadFormats.kbps(max(audio?.averageBitrate ?: 0, audio?.bitrate ?: 0))
            return DownloadFormats.describe(container, audioOnly = false, videoCodec = v?.codec, audioCodec = audioCodec, audioKbps = kbps)
        }
}









/** One original audio stream offered in the Audio only section of the download dialog. */
data class AudioDownloadOption(
    val stream: AudioStream,
    /** "m4a" or "webm": the stream's own container, saved as-is. */
    val container: String,
    val bitsPerSecond: Int,
    val estimatedBytes: Long,
    val sizeExact: Boolean,
    /** The highest-quality original stream. */
    val best: Boolean,
) {
    val itag: Int get() = stream.itag
    val kbps: Int get() = DownloadFormats.kbps(bitsPerSecond)
    val codecLabel: String get() = DownloadFormats.audioCodecLabel(stream.codec)
        ?: if (container == "webm") "Opus" else "AAC"

    /** e.g. "M4A · AAC · 129 kbps". */
    val formatDetail: String
        get() = DownloadFormats.describe(container, audioOnly = true, videoCodec = null, audioCodec = stream.codec ?: codecLabel, audioKbps = kbps)
}

object Downloads {
    private const val MAX_ACTIVE = 2
    private const val PERSIST_KEY = "tasks"
    private const val PREFS = "downloads"

    val tasks: SnapshotStateList<DownloadTask> = mutableStateListOf()
    var initialized by mutableStateOf(false)
        private set

    val busy: Boolean
        get() = tasks.any {
            it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued ||
                it.status == DownloadStatus.Processing
        }


    val progress: Float
        get() {
            val active = tasks.filter {
                it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued ||
                    it.status == DownloadStatus.Processing
            }
            return if (active.isEmpty()) 0f else active.map { it.progress }.average().toFloat()
        }

    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var appContext: Context

    /** App-specific folder that never needs a storage permission; used when the public one is not writable. */
    private lateinit var fallbackDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val pumpLock = Any()
    private val lastPersist = ConcurrentHashMap<String, Long>()
    private val lastReport = ConcurrentHashMap<String, Long>()

    private const val KEY_CUSTOM_DIR = "custom_download_dir"

    val currentDownloadDir: File get() = if (::dir.isInitialized) dir else defaultDownloadDir()

    fun defaultDownloadDir(): File {
        val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(pubDownloads, "YTXTube Downloads")
    }

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fallbackDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val customPath = prefs.getString(KEY_CUSTOM_DIR, null)
        val targetDir = if (!customPath.isNullOrBlank()) {
            File(customPath)
        } else {
            defaultDownloadDir()
        }
        dir = targetDir.apply { runCatching { mkdirs() } }
        initialized = true
        restore()
        persist()
    }

    fun setCustomDownloadDir(newDir: File) {
        runCatching { newDir.mkdirs() }
        dir = newDir
        prefs.edit().putString(KEY_CUSTOM_DIR, newDir.absolutePath).apply()
    }

    fun resetToDefaultDownloadDir() {
        val def = defaultDownloadDir().apply { runCatching { mkdirs() } }
        dir = def
        prefs.edit().remove(KEY_CUSTOM_DIR).apply()
    }

    fun fileFor(name: String): File = File(dir, name)


    suspend fun options(id: String): List<DownloadOption> = withContext(Dispatchers.IO) {
        val info = StreamCache.getOrLoad(id)
        HdrFormats.install(id, info)
        optionsFor(info)
    }

    /** The original audio streams of this video that can be saved without conversion, best first. */
    suspend fun audioOptions(id: String): List<AudioDownloadOption> = withContext(Dispatchers.IO) {
        audioOptionsFor(StreamCache.getOrLoad(id))
    }

    private fun audioContainer(stream: AudioStream): String? = when {
        stream.format == StreamFormat.M4A -> "m4a"
        stream.format == StreamFormat.WEBMA_OPUS -> "webm"
        stream.format == StreamFormat.WEBMA && stream.codec.orEmpty().startsWith("opus") -> "webm"
        else -> null
    }

    private fun audioOptionsFor(info: StreamInfo): List<AudioDownloadOption> {
        val streams = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP &&
                audioContainer(it) != null
        }
        val chosen = DownloadFormats.audioChoices(streams) { s ->
            DownloadFormats.AudioCandidate(
                itag = s.itag,
                container = audioContainer(s) ?: "",
                bitsPerSecond = DownloadFormats.audioBitsPerSecond(s.bitrate, s.averageBitrate),
                original = s.audioTrackType == null ||
                    s.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL,
            )
        }
        return chosen.mapIndexed { index, s ->
            val bps = DownloadFormats.audioBitsPerSecond(s.bitrate, s.averageBitrate)
            val exact = s.itagItem?.contentLength?.takeIf { it > 0 }
            AudioDownloadOption(
                stream = s,
                container = audioContainer(s) ?: DownloadFormats.AUDIO_EXTENSION,
                bitsPerSecond = bps,
                estimatedBytes = exact ?: DownloadFormats.estimateBytes(bps, info.duration),
                sizeExact = exact != null,
                best = index == 0,
            )
        }
    }

    private fun optionsFor(info: StreamInfo): List<DownloadOption> {
        val audioMp4 = bestAudio(info, webm = false)
        val audioOpus = bestAudio(info, webm = true)
        val muxed = info.videoStreams.filter {
            it.isUrl && it.height > 0 && it.format == StreamFormat.MPEG_4 &&
                it.codec.orEmpty().startsWith("avc")
        }
        val videos = info.videoOnlyStreams.filter {
            it.isUrl && it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP &&
                it.height > 0
        }
        val heights = sortedSetOf<Int>()
        muxed.forEach { heights.add(qualityTier(it.width, it.height)) }
        videos.forEach { heights.add(qualityTier(it.width, it.height)) }

        return heights.sortedDescending().mapNotNull { height ->
            val atHeight = videos.filter { qualityTier(it.width, it.height) == height }
            val sdr = atHeight.filterNot(HdrSupport::isHdrCodec)
            val hdr = atHeight.filter(HdrSupport::isHdrCodec)

            val muxedAt = muxed.filter { qualityTier(it.width, it.height) == height }
                .maxByOrNull { it.bitrate }
            if (muxedAt != null) {
                return@mapNotNull option(height, muxedAt, null, null, info, "mp4", false)
            }

            // MP4/H.264 is the most compatible choice through 1080p.
            val avc = sdr.filter {
                it.format == StreamFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc")
            }.maxByOrNull { it.bitrate }
            if (height <= 1080 && avc != null && audioMp4 != null) {
                return@mapNotNull option(height, null, avc, audioMp4, info, "mp4", false)
            }

            // 1440p/2160p: VP9 WebM + Opus, muxed by MediaMuxer on Android 10+.
            val vp9 = sdr.filter {
                it.format == StreamFormat.WEBM && it.codec.orEmpty().startsWith("vp9")
            }.maxByOrNull { it.bitrate }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vp9 != null && audioOpus != null) {
                return@mapNotNull option(height, null, vp9, audioOpus, info, "webm", false)
            }

            val av1 = sdr.filter {
                it.format == StreamFormat.MPEG_4 && it.codec.orEmpty().startsWith("av01")
            }.maxByOrNull { it.bitrate }
            if (Build.VERSION.SDK_INT >= 34 && av1 != null && audioMp4 != null) {
                return@mapNotNull option(height, null, av1, audioMp4, info, "mp4", false)
            }

            // A video that only ships HDR at this height is still downloadable.
            val hdrVp9 = hdr.filter {
                it.format == StreamFormat.WEBM && it.codec.orEmpty().startsWith("vp9")
            }.maxByOrNull { it.bitrate }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hdrVp9 != null && audioOpus != null) {
                return@mapNotNull option(height, null, hdrVp9, audioOpus, info, "webm", true)
            }
            val hdrAv1 = hdr.filter {
                it.format == StreamFormat.MPEG_4 && it.codec.orEmpty().startsWith("av01")
            }.maxByOrNull { it.bitrate }
            if (Build.VERSION.SDK_INT >= 34 && hdrAv1 != null && audioMp4 != null) {
                return@mapNotNull option(height, null, hdrAv1, audioMp4, info, "mp4", true)
            }
            null
        }
    }

    private fun option(
        height: Int,
        muxed: VideoStream?,
        video: VideoStream?,
        audio: AudioStream?,
        info: StreamInfo,
        container: String,
        hdr: Boolean,
    ): DownloadOption {
        val exact = if (muxed != null) {
            muxed.itagItem?.contentLength?.takeIf { it > 0 }
        } else {
            val v = video?.itagItem?.contentLength?.takeIf { it > 0 }
            val a = audio?.itagItem?.contentLength?.takeIf { it > 0 }
            if (v != null && a != null) v + a else null
        }
        if (exact != null) return DownloadOption(height, muxed, video, audio, exact, container, hdr, sizeExact = true)
        val bits = if (muxed != null) muxed.bitrate.toLong()
        else (video?.bitrate ?: 0).toLong() + (audio?.averageBitrate ?: 0)
        val estimate = if (bits > 0 && info.duration > 0) bits * info.duration / 8 else -1L
        return DownloadOption(height, muxed, video, audio, estimate, container, hdr)
    }

    private fun bestAudio(info: StreamInfo, webm: Boolean): AudioStream? = info.audioStreams
        .filter {
            if (webm) {
                it.format == StreamFormat.WEBMA_OPUS || it.format == StreamFormat.OPUS ||
                    (it.format == StreamFormat.WEBMA && it.codec.orEmpty().startsWith("opus"))
            } else {
                it.format == StreamFormat.M4A
            }
        }
        .maxByOrNull { max(it.averageBitrate, it.bitrate) }

    val musicDownloads: List<DownloadTask>
        get() = tasks.filter { (it.height == 0 || it.fileName.endsWith(".m4a")) && it.status == DownloadStatus.Complete }

    fun enqueueMusic(video: VideoItem): Boolean = enqueueAudio(video)

    fun enqueueAudio(video: VideoItem): Boolean {
        if (!initialized) return false
        val key = "${video.id}@0"
        if (tasks.any { it.key == key }) return false
        val name = DownloadFormats.fileName(video.title, video.id, 0)
        val task = DownloadTask(
            videoId = video.id,
            title = video.title,
            channel = video.channel,
            thumbnail = video.thumbnail,
            durationSec = video.durationSec,
            height = 0,
            fileName = name,
            container = DownloadFormats.AUDIO_EXTENSION,
            status = DownloadStatus.Queued,
            directory = dir.absolutePath,
        )
        tasks.add(0, task)
        persist()
        pump()
        return true
    }

    /** Downloads exactly the audio stream picked in the dialog, in its own container. */
    fun enqueueAudio(video: VideoItem, option: AudioDownloadOption): Boolean {
        if (!initialized) return false
        val key = "${video.id}@a${option.itag}"
        if (tasks.any { it.key == key }) return false
        val name = DownloadFormats.fileName(video.title, video.id, 0, option.container, audioKbps = option.kbps)
        if (tasks.any { it.fileName == name }) return false
        val task = DownloadTask(
            videoId = video.id,
            title = video.title,
            channel = video.channel,
            thumbnail = video.thumbnail,
            durationSec = video.durationSec,
            height = 0,
            fileName = name,
            container = option.container,
            total = option.estimatedBytes,
            status = DownloadStatus.Queued,
            directory = dir.absolutePath,
            detail = option.formatDetail,
            audioItag = option.itag,
        )
        tasks.add(0, task)
        persist()
        pump()
        return true
    }

    fun enqueue(video: VideoItem, option: DownloadOption): Boolean {
        if (!initialized) return false
        val key = "${video.id}@${option.height}"
        if (tasks.any { it.key == key }) return false
        val name = DownloadFormats.fileName(video.title, video.id, option.height, option.container, option.hdr)
        val task = DownloadTask(
            videoId = video.id,
            title = video.title,
            channel = video.channel,
            thumbnail = video.thumbnail,
            durationSec = video.durationSec,
            height = option.height,
            fileName = name,
            container = option.container,
            hdr = option.hdr,
            status = DownloadStatus.Queued,
            directory = dir.absolutePath,
            detail = option.formatDetail,
        )
        tasks.add(0, task)
        persist()
        pump()
        return true
    }

    fun pause(task: DownloadTask) {
        if (task.status != DownloadStatus.Downloading && task.status != DownloadStatus.Queued &&
            task.status != DownloadStatus.Processing
        ) return
        task.status = DownloadStatus.Paused
        jobs.remove(task.key)?.cancel()
        persist()
        pump()
    }

    /**
     * Continues a paused download, or retries a failed one. Partial data is kept: when the
     * download starts again each part is only continued if the server supports byte ranges
     * and the part still matches the same stream (see [preparePartial]); otherwise it restarts.
     */
    fun resume(task: DownloadTask) {
        if (task.status != DownloadStatus.Paused && task.status != DownloadStatus.Failed) return
        if (task.status == DownloadStatus.Failed) {
            // A failure may have left damaged data behind: retry from scratch.
            deleteWork(task)
            task.downloaded = 0L
            task.total = -1L
        }
        task.error = null
        task.status = DownloadStatus.Queued
        persist()
        pump()
    }

    fun remove(task: DownloadTask) {
        jobs.remove(task.key)?.cancel()
        tasks.remove(task)
        deleteWork(task)
        task.file.delete()
        persist()
        pump()
    }


    fun clearAll() {
        if (!initialized) return
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        tasks.toList().forEach { task ->
            deleteWork(task)
            runCatching { task.file.delete() }
        }
        tasks.clear()
        lastPersist.clear()
        lastReport.clear()
        prefs.edit().putString(PERSIST_KEY, "[]").apply()

        runCatching {
            dir.listFiles()?.forEach {
                if (it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".part.meta"))) it.delete()
            }
        }
    }

    /** Permanently deletes all downloaded video/music files and clears all tasks. */
    fun deleteAllDownloadsAndWork() {
        clearAll()
        runCatching {
            val d = currentDownloadDir
            if (d.exists() && d.isDirectory) {
                d.listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
            }
        }
    }

    private fun pump() {
        synchronized(pumpLock) {
            while (jobs.size < MAX_ACTIVE) {
                val next = tasks.firstOrNull { it.status == DownloadStatus.Queued } ?: return
                next.status = DownloadStatus.Downloading
                val job = scope.launch(start = CoroutineStart.LAZY) { execute(next) }
                jobs[next.key] = job
                job.start()
            }
        }
    }

    private suspend fun execute(task: DownloadTask) {
        var attempt = 0
        try {
            while (true) {
                try {
                    runDownload(task)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (attempt < 2) {
                        attempt++
                        Log.w(TAG, "download ${task.key} attempt $attempt failed; retrying", t)
                        StreamCache.invalidate(task.videoId)
                        // Parts that finished downloading are reused (validated again by
                        // preparePartial). If combining or verifying failed, start clean.
                        if (task.status == DownloadStatus.Processing || t is DamagedFileException) {
                            deleteWork(task)
                            task.downloaded = 0L
                        }
                        task.status = DownloadStatus.Downloading
                        kotlinx.coroutines.delay(1000L * attempt)
                        continue
                    }
                    throw t
                }
            }
        } catch (e: CancellationException) {
            if (task.status == DownloadStatus.Downloading || task.status == DownloadStatus.Processing) {
                task.status = DownloadStatus.Paused
            }
        } catch (t: Throwable) {
            Log.w(TAG, "download ${task.key} failed", t)
            task.error = friendlyError(t)
            task.status = DownloadStatus.Failed
            deleteWork(task)
        } finally {
            jobs.remove(task.key)
            persist()
            pump()
        }
    }

    private suspend fun runDownload(task: DownloadTask) {
        task.status = DownloadStatus.Downloading
        writableDirFor(task)
        StreamCache.invalidate(task.videoId)
        val info = StreamCache.getOrLoad(task.videoId)
        HdrFormats.install(task.videoId, info)
        val ctx = currentCoroutineContext()

        if (task.height == 0) {
            val audio = if (task.audioItag > 0) {
                // The exact stream picked in the dialog (fresh URL, same itag).
                audioOptionsFor(info).firstOrNull { it.itag == task.audioItag }?.stream
                    ?: throw IOException("That audio quality is no longer available")
            } else {
                bestAudio(info, webm = false) ?: throw IOException("No M4A audio stream is offered for this video")
            }
            task.detail = DownloadFormats.describe(
                container = if (task.audioItag > 0) task.container else DownloadFormats.AUDIO_EXTENSION,
                audioOnly = true,
                videoCodec = null,
                audioCodec = audio.codec,
                audioKbps = DownloadFormats.kbps(DownloadFormats.audioBitsPerSecond(audio.bitrate, audio.averageBitrate)),
            )
            val work = workFile(task, "audio")
            val remote = probe(audio.content)
            preparePartial(work, remote, audio.itag)
            task.total = remote.total
            val aDone = AtomicLong(work.lengthOrZero())
            task.downloaded = aDone.get()
            val received = transfer(audio.content, work, remote) { n -> report(task, aDone.addAndGet(n)) }
            if (!DownloadFormats.isTransferComplete(received, work.length(), remote.total)) {
                throw IOException("Audio download incomplete (${work.length()} of ${remote.total} bytes)")
            }
            ctx.ensureActive()
            val target = task.file
            moveInto(work, target)
            verifyMediaFile(target, needVideo = false, needAudio = true)
            finishTask(task, target)
            return
        }

        val option = optionsFor(info).firstOrNull { it.height == task.height }
            ?: throw IOException("That quality is no longer available")
        task.detail = option.formatDetail
        val muxed = option.muxed

        if (muxed != null) {
            task.total = option.estimatedBytes
            val work = workFile(task, "video")
            val remote = probe(muxed.content)
            preparePartial(work, remote, muxed.itag)
            task.total = remote.total
            val mDone = AtomicLong(work.lengthOrZero())
            task.downloaded = mDone.get()
            val received = transfer(muxed.content, work, remote) { n -> report(task, mDone.addAndGet(n)) }
            if (!DownloadFormats.isTransferComplete(received, work.length(), remote.total)) {
                throw IOException("Download incomplete (${work.length()} of ${remote.total} bytes)")
            }
            ctx.ensureActive()
            val target = task.file
            moveInto(work, target)
            verifyMediaFile(target, needVideo = true, needAudio = true)
            finishTask(task, target)
        } else {
            val video = option.video ?: throw IOException("No video stream")
            val audio = option.audio ?: throw IOException("No audio stream")
            task.total = option.estimatedBytes
            val vWork = workFile(task, "video")
            val aWork = workFile(task, "audio")

            val vRemote = probe(video.content)
            val aRemote = probe(audio.content)
            preparePartial(vWork, vRemote, video.itag)
            preparePartial(aWork, aRemote, audio.itag)
            task.total = vRemote.total + aRemote.total
            val vDone = AtomicLong(vWork.lengthOrZero())
            val aDone = AtomicLong(aWork.lengthOrZero())
            task.downloaded = vDone.get() + aDone.get()
            coroutineScope {
                launch {
                    val n = transfer(video.content, vWork, vRemote) { n ->
                        report(task, vDone.addAndGet(n) + aDone.get())
                    }
                    if (!DownloadFormats.isTransferComplete(n, vWork.length(), vRemote.total)) {
                        throw IOException("Video download incomplete (${vWork.length()} of ${vRemote.total} bytes)")
                    }
                }
                launch {
                    val n = transfer(audio.content, aWork, aRemote) { n ->
                        report(task, aDone.addAndGet(n) + vDone.get())
                    }
                    if (!DownloadFormats.isTransferComplete(n, aWork.length(), aRemote.total)) {
                        throw IOException("Audio download incomplete (${aWork.length()} of ${aRemote.total} bytes)")
                    }
                }
            }
            task.downloaded = task.total
            task.status = DownloadStatus.Processing
            persist()

            val target = task.file
            target.delete()
            mux(vWork, aWork, target, option.container) { ctx.ensureActive() }
            verifyMediaFile(target, needVideo = true, needAudio = true)
            deleteWork(task)
            finishTask(task, target)
        }
    }

    private fun File.lengthOrZero(): Long = if (exists()) length() else 0L

    private fun finishTask(task: DownloadTask, target: File) {
        task.total = target.length()
        task.downloaded = task.total
        task.error = null
        task.status = DownloadStatus.Complete
        persist()
        scanIntoMediaStore(target)
        Log.i(TAG, "download ${task.key} complete and verified: ${target.length()} bytes (${task.detail})")
    }

    private fun moveInto(work: File, target: File) {
        target.delete()
        if (!work.renameTo(target)) {
            work.copyTo(target, overwrite = true)
            work.delete()
        }
        metaFile(work).delete()
    }

    /**
     * Keeps a partial file only if it can genuinely be continued; otherwise deletes it so the
     * transfer restarts from byte 0. Records which stream the partial file belongs to.
     */
    private fun preparePartial(work: File, remote: Remote, itag: Int) {
        val meta = metaFile(work)
        val identity = DownloadFormats.streamIdentity(itag, remote.total)
        val saved = runCatching { if (meta.exists()) meta.readText().trim() else null }.getOrNull()
        val partial = work.lengthOrZero()
        if (partial > 0L) {
            if (DownloadFormats.canResume(partial, saved, identity, remote.ranges, remote.total)) {
                Log.i(TAG, "resuming ${work.name} at $partial of ${remote.total} bytes")
            } else {
                Log.i(TAG, "restarting ${work.name}: partial data cannot be continued (ranges=${remote.ranges})")
                work.delete()
            }
        }
        runCatching { meta.writeText(identity) }
    }

    /**
     * Picks the folder for a download. The chosen folder is used when it is writable; otherwise
     * (no storage permission on Android 9 and older, a folder scoped storage does not allow,
     * removed SD card) the app's own download folder is used, which never needs a permission.
     */
    private fun writableDirFor(task: DownloadTask): File {
        val current = task.directory?.let(::File) ?: dir
        if (isWritable(current)) {
            if (task.directory == null) task.directory = current.absolutePath
            return current
        }
        if (task.downloaded > 0L) deleteWork(task)
        if (isWritable(fallbackDir)) {
            Log.w(TAG, "${current.absolutePath} is not writable; saving ${task.key} to ${fallbackDir.absolutePath}")
            task.directory = fallbackDir.absolutePath
            task.downloaded = 0L
            persist()
            return fallbackDir
        }
        throw IOException("Storage is not available for downloads")
    }

    private fun isWritable(folder: File): Boolean = runCatching {
        folder.mkdirs()
        val probe = File(folder, ".ytxtube_probe.part")
        probe.writeText("")
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * Confirms the finished file is real media before calling it complete: it must open, and
     * contain the expected audio (and video) tracks.
     */
    private fun verifyMediaFile(file: File, needVideo: Boolean, needAudio: Boolean) {
        if (!file.exists() || file.length() <= 0L) throw DamagedFileException("The downloaded file is missing")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(AndroidMediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
            if ((needVideo && !hasVideo) || (needAudio && !hasAudio)) {
                throw DamagedFileException("The downloaded file is missing its ${if (needVideo && !hasVideo) "video" else "audio"} track")
            }
        } catch (e: DamagedFileException) {
            file.delete()
            throw e
        } catch (t: Throwable) {
            file.delete()
            throw DamagedFileException("The downloaded file could not be read", t)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Makes finished files show up in gallery and music apps (files written via File are not indexed). */
    private fun scanIntoMediaStore(file: File) {
        if (!::appContext.isInitialized) return
        if (file.absolutePath.startsWith(fallbackDir.absolutePath)) return
        runCatching { android.media.MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null) }
    }

    private fun friendlyError(t: Throwable): String = when {
        t is HttpStatusException && (t.code == 403 || t.code == 410) -> "YouTube refused the download (HTTP ${t.code}). Tap Retry."
        t is HttpStatusException -> "Server error (HTTP ${t.code}). Tap Retry."
        t is java.net.UnknownHostException || t is java.net.SocketTimeoutException || t is java.net.ConnectException ->
            "No connection. Tap Retry when you're back online."
        t.cause is java.net.UnknownHostException || t.cause is java.net.SocketTimeoutException ->
            "Connection lost. Tap Retry when you're back online."
        else -> t.message ?: "Download failed"
    }

    private fun report(task: DownloadTask, done: Long) {
        val now = SystemClock.elapsedRealtime()
        val last = lastReport[task.key] ?: 0L
        if (now - last >= 120L) {
            lastReport[task.key] = now
            task.downloaded = done.coerceAtMost(if (task.total > 0) task.total else done)
            val lastSaved = lastPersist[task.key] ?: 0L
            if (now - lastSaved >= 2_000L) {
                lastPersist[task.key] = now
                persist()
            }
        }
    }



    private data class Remote(val total: Long, val ranges: Boolean)


    private fun probe(url: String): Remote {
        fun makeProbeRequest(u: String, addRangeQuery: Boolean): Request {
            val finalUrl = if (addRangeQuery) withRange(u, 0L, 0L) else u
            return Request.Builder()
                .url(finalUrl)
                .header("Range", "bytes=0-0")
                .header("User-Agent", Net.USER_AGENT)
                .header("Referer", "https://www.youtube.com/")
                .build()
        }

        var res = runCatching { Net.client.newCall(makeProbeRequest(url, false)).execute() }.getOrNull()
        if (res == null || !res.isSuccessful) {
            res?.close()
            res = runCatching { Net.client.newCall(makeProbeRequest(url, true)).execute() }.getOrNull()
        }
        val response = res ?: throw IOException("Could not connect to video server")
        response.use {
            if (!it.isSuccessful) throw HttpStatusException(it.code)
            val contentRange = it.header("Content-Range")
            val total = contentRange?.substringAfterLast('/')?.toLongOrNull()
                ?: it.body?.contentLength()?.takeIf { len -> len > 0L }
                ?: -1L
            if (total <= 0L) throw IOException("Could not determine the video size")
            return Remote(total, ranges = it.code == 206)
        }
    }

    private suspend fun transfer(
        url: String,
        file: File,
        remote: Remote,
        onProgress: (Long) -> Unit,
    ): Long {
        var downloaded = if (file.exists()) file.length() else 0L
        if (remote.total > 0 && downloaded >= remote.total) return downloaded

        var attempts = 0
        val maxAttempts = 5
        val buffer = ByteArray(128 * 1024)
        val ctx = currentCoroutineContext()
        // YouTube throttles one long request to roughly playback speed (a 38 MB audio file took
        // as long as the audio itself), but serves each request of up to ~10 MB at full speed.
        // So the file is fetched as consecutive byte ranges, like NewPipe and yt-dlp do.
        var chunked = remote.ranges && remote.total > 0

        while (remote.total <= 0 || downloaded < remote.total) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", Net.USER_AGENT)
                    .header("Referer", "https://www.youtube.com/")

                val chunkEnd = if (chunked) DownloadFormats.chunkEnd(downloaded, remote.total, DownloadFormats.CHUNK_BYTES) else null
                if (chunkEnd != null) {
                    reqBuilder.header("Range", "bytes=$downloaded-$chunkEnd")
                } else if (downloaded > 0) {
                    reqBuilder.header("Range", "bytes=$downloaded-")
                }
                val before = downloaded

                Net.client.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code != 200 && response.code != 206) {
                        throw HttpStatusException(response.code)
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val isPartial = response.code == 206
                    if (isPartial) {
                        // The server must answer with the range that was asked for.
                        val start = response.header("Content-Range")
                            ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (start != null && start != downloaded) {
                            throw IOException("Server sent bytes from $start instead of $downloaded")
                        }
                    } else if (chunked) {
                        // Range ignored: take the whole file in this one response instead.
                        Log.i(TAG, "server ignored the range request; downloading in one piece")
                        chunked = false
                    }
                    val source = body.source()

                    RandomAccessFile(file, "rw").use { raf ->
                        if (isPartial && downloaded > 0) {
                            raf.seek(downloaded)
                        } else {
                            raf.seek(0)
                            raf.setLength(0)
                            downloaded = 0L
                        }

                        while (true) {
                            // The read loop is blocking, so check for Pause/Delete here;
                            // otherwise a cancelled download kept writing until it finished.
                            ctx.ensureActive()
                            val read = source.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            onProgress(read.toLong())
                            attempts = 0
                        }
                    }
                }

                if (remote.total <= 0 || downloaded >= remote.total) {
                    break
                }
                if (downloaded == before) throw IOException("The server sent no data")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                attempts++
                Log.w(TAG, "Download interrupted at $downloaded/${remote.total}, attempt $attempts of $maxAttempts", t)
                if (attempts >= maxAttempts) {
                    throw IOException("Download failed at $downloaded of ${remote.total} bytes: ${t.message}", t)
                }
                kotlinx.coroutines.delay(1000L * attempts)
            }
        }

        if (remote.total > 0 && downloaded < remote.total) {
            throw IOException("Incomplete download: received $downloaded of ${remote.total} bytes")
        }
        return downloaded
    }

    private fun withRange(url: String, start: Long, end: Long): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed.newBuilder()
            .removeAllQueryParameters("range")
            .addQueryParameter("range", "$start-$end")
            .build()
            .toString()
    }



    private fun mux(video: File, audio: File, out: File, container: String, checkCancelled: () -> Unit) {
        if (container == "webm" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("WebM downloads need Android 10 or newer")
        }
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoEx.setDataSource(video.absolutePath)
            audioEx.setDataSource(audio.absolutePath)
            val vTrack = findTrack(videoEx, "video/") ?: throw IOException("No video track")
            val aTrack = findTrack(audioEx, "audio/") ?: throw IOException("No audio track")
            videoEx.selectTrack(vTrack)
            audioEx.selectTrack(aTrack)

            val outputFormat = if (container == "webm") {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            muxer = MediaMuxer(out.absolutePath, outputFormat)
            val m = muxer
            val vOut = m.addTrack(videoEx.getTrackFormat(vTrack))
            val aOut = m.addTrack(audioEx.getTrackFormat(aTrack))
            m.start()

            val vBuffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val aBuffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var vSize = videoEx.readSampleData(vBuffer, 0)
            var aSize = audioEx.readSampleData(aBuffer, 0)
            val base = when {
                vSize >= 0 && aSize >= 0 -> min(videoEx.sampleTime, audioEx.sampleTime)
                vSize >= 0 -> videoEx.sampleTime
                aSize >= 0 -> audioEx.sampleTime
                else -> 0L
            }

            var lastVTime = -1L
            var lastATime = -1L
            while (vSize >= 0 || aSize >= 0) {
                checkCancelled()
                val vTime = if (vSize >= 0) videoEx.sampleTime else Long.MAX_VALUE
                val aTime = if (aSize >= 0) audioEx.sampleTime else Long.MAX_VALUE
                if (vTime <= aTime) {
                    val pts = (vTime - base).coerceAtLeast(lastVTime + 1)
                    lastVTime = pts
                    info.set(0, vSize, pts, videoEx.sampleFlags)
                    m.writeSampleData(vOut, vBuffer, info)
                    videoEx.advance()
                    vSize = videoEx.readSampleData(vBuffer, 0)
                } else {
                    val pts = (aTime - base).coerceAtLeast(lastATime + 1)
                    lastATime = pts
                    info.set(0, aSize, pts, audioEx.sampleFlags)
                    m.writeSampleData(aOut, aBuffer, info)
                    audioEx.advance()
                    aSize = audioEx.readSampleData(aBuffer, 0)
                }
            }

            m.stop()
        } catch (e: CancellationException) {
            out.delete()
            throw e
        } catch (t: Throwable) {
            out.delete()
            throw IOException("Could not combine video and audio", t)
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoEx.release() }
            runCatching { audioEx.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(AndroidMediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return null
    }



    private fun workFile(task: DownloadTask, kind: String): File {
        val variant = if (task.audioItag > 0) "a${task.audioItag}" else task.height.toString()
        return File(task.directory?.let(::File) ?: dir, "${task.videoId}_${variant}_$kind.part")
    }

    /** Sidecar recording which stream a partial file belongs to, so resuming never mixes streams. */
    private fun metaFile(work: File): File = File(work.parentFile, work.name + ".meta")

    private fun deleteWork(task: DownloadTask) {
        listOf(workFile(task, "video"), workFile(task, "audio")).forEach { work ->
            work.delete()
            metaFile(work).delete()
        }
    }

    private fun persist() {
        if (!initialized) return
        runCatching {
            val array = JSONArray()
            tasks.forEach { task ->
                array.put(
                    JSONObject()
                        .put("id", task.videoId)
                        .put("title", task.title)
                        .put("channel", task.channel)
                        .put("thumb", task.thumbnail)
                        .put("duration", task.durationSec)
                        .put("height", task.height)
                        .put("file", task.fileName)
                        .put("container", task.container)
                        .put("hdr", task.hdr)
                        .put("downloaded", task.downloaded)
                        .put("total", task.total)
                        .put("status", task.status.name)
                        .put("dir", task.directory)
                        .put("detail", task.detail)
                        .put("aitag", task.audioItag),
                )
            }
            prefs.edit().putString(PERSIST_KEY, array.toString()).apply()
        }
    }

    private fun restore() {
        val raw = prefs.getString(PERSIST_KEY, null)
        if (raw != null) runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val height = json.optInt("height")
                val name = json.optString("file")

                if (height < 0 || name.isBlank()) continue
                val status = runCatching {
                    DownloadStatus.valueOf(json.optString("status"))
                }.getOrDefault(DownloadStatus.Paused)
                val container = json.optString("container")
                    .ifBlank { if (name.endsWith(".webm")) "webm" else "mp4" }
                val task = DownloadTask(
                    videoId = json.optString("id"),
                    title = json.optString("title"),
                    channel = json.optString("channel"),
                    thumbnail = json.optString("thumb").takeIf { it.isNotBlank() && it != "null" },
                    durationSec = json.optLong("duration"),
                    height = height,
                    fileName = name,
                    container = container,
                    hdr = json.optBoolean("hdr", name.contains(" HDR]")),
                    downloaded = json.optLong("downloaded"),
                    total = json.optLong("total", -1L),
                    status = when (status) {
                        DownloadStatus.Complete, DownloadStatus.Failed -> status
                        // Interrupted by the app being closed: offer Resume, keeping partial data.
                        else -> DownloadStatus.Paused
                    },
                    directory = json.optString("dir").takeIf { it.isNotBlank() && it != "null" },
                    detail = json.optString("detail").takeIf { it != "null" }.orEmpty(),
                    audioItag = json.optInt("aitag", 0),
                )
                if (status == DownloadStatus.Complete && !task.file.exists()) continue
                if (tasks.any { it.fileName == name }) continue
                tasks.add(task)
            }
        }.onFailure { Log.w(TAG, "could not restore downloads", it) }



        runCatching {
            val existing = tasks.mapTo(HashSet()) { it.fileName }
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.length() <= 0L) return@forEach
                val name = f.name
                if (name.endsWith(".part") || name.endsWith(".meta") || name.startsWith(".")) return@forEach
                if (!existing.add(name)) return@forEach
                val audio = name.endsWith(".m4a") || name.contains(" [Audio")
                val ext = when {
                    name.endsWith(".webm") -> ".webm"
                    audio -> ".m4a"
                    else -> ".mp4"
                }
                if (!name.endsWith(ext)) return@forEach
                val stem = name.removeSuffix(ext)
                val id = stem.takeLast(11)
                if (id.length != 11) return@forEach
                val label = stem.dropLast(12).trim()
                val cut = label.lastIndexOf(" [")
                val title = (if (cut > 0) label.substring(0, cut) else label).trim()
                val height = Regex("\\[(\\d+)p]").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (audio) 0 else -1
                if (height < 0) return@forEach
                tasks.add(
                    DownloadTask(
                        videoId = id,
                        title = title.ifBlank { "Downloaded video" },
                        channel = "",
                        thumbnail = feedThumbnailUrl(id),
                        durationSec = 0L,
                        height = height,
                        fileName = name,
                        container = if (ext == ".webm") "webm" else "mp4",
                        hdr = label.contains(" HDR]"),
                        downloaded = f.length(),
                        total = f.length(),
                        status = DownloadStatus.Complete,
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "could not scan downloads", it) }
    }

    fun clearFinished() {
        tasks.filter { it.status == DownloadStatus.Complete }.forEach { task ->
            tasks.remove(task)
            task.file.delete()
        }
        persist()
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")

    /** The transfer finished but the file is not valid media; retrying must start from scratch. */
    private class DamagedFileException(message: String, cause: Throwable? = null) : IOException(message, cause)
}


fun DownloadTask.toVideoItem(): VideoItem = VideoItem(
    id = videoId,
    title = title,
    channel = channel,
    channelUrl = null,
    thumbnail = thumbnail,
    avatar = null,
    durationSec = durationSec,
    views = 0L,
    uploaded = null,
    localUri = localUri,
)
