package com.tensei.ytxtube

import java.util.Locale

/**
 * Naming, labelling and integrity rules for downloads. Pure Kotlin (no Android types) so the
 * rules can be unit tested.
 *
 * Audio downloads are saved exactly as YouTube serves them (AAC in an MP4 container, `.m4a`).
 * The app has no MP3 encoder, so it never offers "MP3" and never renames M4A/WebM/Opus data
 * to `.mp3`: the extension always matches the real container.
 */
object DownloadFormats {

    const val AUDIO_EXTENSION = "m4a"

    /** File extension that matches the container actually written. */
    fun extensionFor(container: String, audioOnly: Boolean): String = when {
        audioOnly -> AUDIO_EXTENSION
        container.equals("webm", ignoreCase = true) -> "webm"
        else -> "mp4"
    }

    fun containerLabel(container: String, audioOnly: Boolean): String = when (extensionFor(container, audioOnly)) {
        "m4a" -> "M4A"
        "webm" -> "WebM"
        else -> "MP4"
    }

    /** "avc1.640028" → "H.264", "vp09.00.41.08" → "VP9", "av01.0.08M.08" → "AV1". */
    fun videoCodecLabel(codec: String?): String? {
        val c = codec?.lowercase(Locale.US)?.trim().orEmpty()
        return when {
            c.isEmpty() -> null
            c.startsWith("avc") -> "H.264"
            c.startsWith("vp9") || c.startsWith("vp09") -> "VP9"
            c.startsWith("av01") -> "AV1"
            c.startsWith("hev") || c.startsWith("hvc") -> "H.265"
            else -> null
        }
    }

    /** "mp4a.40.2" → "AAC", "opus" → "Opus". */
    fun audioCodecLabel(codec: String?): String? {
        val c = codec?.lowercase(Locale.US)?.trim().orEmpty()
        return when {
            c.isEmpty() -> null
            c.startsWith("mp4a") || c == "aac" -> "AAC"
            c.startsWith("opus") -> "Opus"
            c.startsWith("vorbis") -> "Vorbis"
            c.startsWith("ac-3") || c.startsWith("ec-3") -> "Dolby"
            else -> null
        }
    }

    /** Bits per second → whole kbps, or 0 when unknown. */
    fun kbps(bitsPerSecond: Int): Int = if (bitsPerSecond > 0) (bitsPerSecond + 500) / 1000 else 0

    /**
     * Human readable format line, only stating what is known:
     * "M4A · AAC · 129 kbps", "MP4 · H.264 + AAC", "WebM · VP9 + Opus 160 kbps".
     */
    fun describe(
        container: String,
        audioOnly: Boolean,
        videoCodec: String?,
        audioCodec: String?,
        audioKbps: Int,
    ): String {
        val parts = mutableListOf(containerLabel(container, audioOnly))
        val v = if (audioOnly) null else videoCodecLabel(videoCodec)
        val a = audioCodecLabel(audioCodec)
        val audioText = listOfNotNull(a, audioKbps.takeIf { it > 0 }?.let { "$it kbps" }).joinToString(" ")
        when {
            audioOnly -> {
                a?.let(parts::add)
                if (audioKbps > 0) parts.add("$audioKbps kbps")
            }
            v != null && audioText.isNotEmpty() -> parts.add("$v + $audioText")
            v != null -> parts.add(v)
            audioText.isNotEmpty() -> parts.add(audioText)
        }
        return parts.joinToString(" · ")
    }

    /** Characters Android/FAT file systems refuse, plus control whitespace. */
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\n\\r\\t]")
    private val SPACES = Regex("\\s+")

    fun fileName(title: String, videoId: String, height: Int, container: String = "mp4", hdr: Boolean = false): String {
        val safe = title
            .replace(UNSAFE, " ")
            .replace(SPACES, " ")
            .trim()
            .take(64)
            .trim()
            .ifBlank { videoId }
        val audioOnly = height == 0
        val ext = extensionFor(container, audioOnly)
        if (audioOnly) return "$safe [Audio] $videoId.$ext"
        val hdrTag = if (hdr) " HDR" else ""
        return "$safe [${height}p$hdrTag] $videoId.$ext"
    }

    /**
     * Whether a partial file may be continued instead of starting over. Only when the server
     * honours byte ranges, the partial file belongs to the very same stream (same identity,
     * which includes the itag and the exact total length), and it is not longer than that total.
     */
    fun canResume(
        partialBytes: Long,
        savedIdentity: String?,
        currentIdentity: String,
        rangesSupported: Boolean,
        total: Long,
    ): Boolean = partialBytes > 0L &&
        rangesSupported &&
        total > 0L &&
        partialBytes <= total &&
        savedIdentity != null &&
        savedIdentity == currentIdentity

    /** Identity of one remote stream, used to decide whether a partial file still matches it. */
    fun streamIdentity(itag: Int, total: Long): String = "itag=$itag;len=$total"

    /** A finished transfer is only complete when both the received count and the file match the size. */
    fun isTransferComplete(received: Long, fileLength: Long, expected: Long): Boolean =
        expected > 0L && received == expected && fileLength == expected
}
