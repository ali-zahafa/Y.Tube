package com.tensei.ytxtube

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * What this device can decode in hardware, probed once from MediaCodecList. Used to prefer
 * renditions the hardware decoder can play (smooth playback, lower CPU, memory and battery)
 * over ones that would fall back to a software decoder.
 *
 * Unknown answers return true so that selection never rejects a stream on a failed probe;
 * ExoPlayer's own renderer capability checks still apply on top of this.
 */
object VideoDecoders {

    private class FamilyCaps(val hardware: Boolean, val caps: List<MediaCodecInfo.VideoCapabilities>)

    @Volatile
    private var probed: Map<VideoCodecFamily, FamilyCaps>? = null

    /** Probe off the main thread at startup so the first video open does not pay for it. */
    fun warmUp() {
        runCatching { families() }
    }

    private fun families(): Map<VideoCodecFamily, FamilyCaps> {
        probed?.let { return it }
        synchronized(this) {
            probed?.let { return it }
            val result = runCatching { probe() }
                .onFailure { Log.w("YTXTube", "decoder probe failed", it) }
                .getOrDefault(emptyMap())
            probed = result
            return result
        }
    }

    private fun probe(): Map<VideoCodecFamily, FamilyCaps> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        val out = HashMap<VideoCodecFamily, FamilyCaps>()
        for (family in VideoCodecFamily.entries) {
            if (family == VideoCodecFamily.OTHER) continue
            val decoders = infos.filter { info ->
                info.supportedTypes.any { it.equals(family.mime, ignoreCase = true) } &&
                    !info.name.lowercase().endsWith(".secure")
            }
            if (decoders.isEmpty()) continue
            val hardware = decoders.filter(::isHardware)
            val use = hardware.ifEmpty { decoders }
            val caps = use.mapNotNull { runCatching { it.getCapabilitiesForType(family.mime).videoCapabilities }.getOrNull() }
            out[family] = FamilyCaps(hardware.isNotEmpty(), caps)
        }
        Log.i(
            "YTXTube",
            "video decoders: " + out.entries.joinToString { (f, c) -> "$f=${if (c.hardware) "hw" else "sw"}" },
        )
        return out
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated
        // Same heuristic Media3 uses before API 29.
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") ||
            name.startsWith("omx.ffmpeg.") || name.contains(".sw.") || name.endsWith(".sw"))
    }

    /** True when the family has a hardware decoder (or the probe could not tell). */
    fun isHardware(family: VideoCodecFamily): Boolean {
        val all = families()
        if (all.isEmpty() || family == VideoCodecFamily.OTHER) return true
        return all[family]?.hardware ?: false
    }

    /** True when some decoder of this family (hardware if present) handles this size and rate. */
    fun canDecode(family: VideoCodecFamily, width: Int, height: Int, fps: Int): Boolean {
        val all = families()
        if (all.isEmpty() || family == VideoCodecFamily.OTHER) return true
        val entry = all[family] ?: return false
        if (entry.caps.isEmpty() || width <= 0 || height <= 0) return true
        return entry.caps.any { caps ->
            runCatching {
                if (fps > 0) caps.areSizeAndRateSupported(width, height, fps.toDouble()) || caps.areSizeAndRateSupported(height, width, fps.toDouble())
                else caps.isSizeSupported(width, height) || caps.isSizeSupported(height, width)
            }.getOrDefault(true)
        }
    }

    /** 1 when this rendition plays on a hardware decoder (or unknown), 0 when it needs software. */
    fun hardwareRank(codec: String?, width: Int, height: Int, fps: Int): Int {
        val family = VideoCodecFamily.of(codec)
        return if (isHardware(family) && canDecode(family, width, height, fps)) 1 else 0
    }
}
