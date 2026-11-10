package com.tensei.ytxtube

import com.tensei.ytxtube.CodecLadder.Candidate
import com.tensei.ytxtube.VideoCodecFamily.AV1
import com.tensei.ytxtube.VideoCodecFamily.AVC
import com.tensei.ytxtube.VideoCodecFamily.OTHER
import com.tensei.ytxtube.VideoCodecFamily.VP9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecLadderTest {

    @Test
    fun familyFromCodecString() {
        assertEquals(AVC, VideoCodecFamily.of("avc1.640028"))
        assertEquals(VP9, VideoCodecFamily.of("vp9"))
        assertEquals(VP9, VideoCodecFamily.of("vp09.02.51.10"))
        assertEquals(AV1, VideoCodecFamily.of("av01.0.12M.08"))
        assertEquals(OTHER, VideoCodecFamily.of("hev1.2.4.L153"))
        assertEquals(OTHER, VideoCodecFamily.of(null))
    }

    @Test
    fun hardwareBeatsHigherSoftwareResolution() {
        val choice = CodecLadder.choose(
            listOf(
                Candidate(AVC, maxTier = 1080, hardware = true, rungs = 6),
                Candidate(AV1, maxTier = 2160, hardware = false, rungs = 8),
            ),
        )
        assertEquals(AVC, choice)
    }

    @Test
    fun amongHardwareFamilies_highestResolutionWins() {
        val choice = CodecLadder.choose(
            listOf(
                Candidate(AVC, 1080, true, 6),
                Candidate(VP9, 2160, true, 8),
            ),
        )
        assertEquals(VP9, choice)
    }

    @Test
    fun sameResolution_moreEfficientCodecWins() {
        val choice = CodecLadder.choose(
            listOf(
                Candidate(AVC, 1080, true, 6),
                Candidate(VP9, 1080, true, 6),
                Candidate(AV1, 1080, true, 6),
            ),
        )
        assertEquals(AV1, choice)
    }

    @Test
    fun noHardwareAnywhere_fallsBackToResolution() {
        val choice = CodecLadder.choose(
            listOf(
                Candidate(AVC, 720, false, 4),
                Candidate(VP9, 1080, false, 5),
            ),
        )
        assertEquals(VP9, choice)
    }

    @Test
    fun emptyOrUnusableFamilies_giveNull() {
        assertNull(CodecLadder.choose(emptyList()))
        assertNull(CodecLadder.choose(listOf(Candidate(OTHER, 1080, true, 3), Candidate(AVC, 0, true, 0))))
    }
}
