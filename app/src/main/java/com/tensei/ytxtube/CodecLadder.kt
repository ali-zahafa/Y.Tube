package com.tensei.ytxtube

import java.util.Locale

/** Video codec families YouTube serves, with the MIME type Android decoders register for. */
enum class VideoCodecFamily(val mime: String, val efficiency: Int) {
    AVC("video/avc", 0),
    VP9("video/x-vnd.on2.vp9", 1),
    AV1("video/av01", 2),
    OTHER("", -1);

    companion object {
        fun of(codec: String?): VideoCodecFamily {
            val c = codec?.lowercase(Locale.US)?.trim().orEmpty()
            return when {
                c.startsWith("avc") -> AVC
                c.startsWith("vp9") || c.startsWith("vp09") -> VP9
                c.startsWith("av01") -> AV1
                else -> OTHER
            }
        }
    }
}

/**
 * Chooses one codec family for the adaptive (Auto) ladder. Pure logic so it can be tested.
 *
 * ExoPlayer only adapts between tracks of a single MIME type unless mixed-MIME adaptation is
 * enabled (which forces a decoder re-init and a visible glitch on every switch). A ladder that
 * mixes H.264, VP9 and AV1 therefore got silently cut down to whichever codec had the most
 * rungs, sometimes losing the top resolution, and ignored whether the codec is hardware decoded.
 */
object CodecLadder {

    data class Candidate(
        val family: VideoCodecFamily,
        /** Highest quality tier this family offers that the device can decode. */
        val maxTier: Int,
        /** Decoded by a hardware decoder (true when unknown, so the choice falls back to tier). */
        val hardware: Boolean,
        /** Number of distinct tiers, i.e. how finely the player can adapt. */
        val rungs: Int,
    )

    /**
     * Order of preference: a hardware-decoded family (smooth, low CPU and battery) over a
     * software one; then the family that reaches the highest resolution; then the more
     * bandwidth-efficient codec (AV1 > VP9 > H.264); then the one with more rungs.
     */
    fun choose(candidates: List<Candidate>): VideoCodecFamily? = candidates
        .filter { it.family != VideoCodecFamily.OTHER && it.maxTier > 0 && it.rungs > 0 }
        .maxWithOrNull(
            compareBy<Candidate> { it.hardware }
                .thenBy { it.maxTier }
                .thenBy { it.family.efficiency }
                .thenBy { it.rungs },
        )
        ?.family
}
