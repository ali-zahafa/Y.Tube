package com.tensei.ytxtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatsTest {

    @Test
    fun audioDownloads_areM4a_neverMp3() {
        val name = DownloadFormats.fileName("Some Song", "dQw4w9WgXcQ", height = 0)
        assertEquals("Some Song [Audio] dQw4w9WgXcQ.m4a", name)
        assertFalse(name.endsWith(".mp3"))
        assertEquals("m4a", DownloadFormats.extensionFor("m4a", audioOnly = true))
        assertEquals("m4a", DownloadFormats.extensionFor("mp4", audioOnly = true))
    }

    @Test
    fun opusAudio_keepsItsWebmContainer_andQualityIsInTheName() {
        assertEquals("webm", DownloadFormats.extensionFor("webm", audioOnly = true))
        assertEquals("Song [Audio 160k] abcdefghijk.webm", DownloadFormats.fileName("Song", "abcdefghijk", 0, "webm", audioKbps = 160))
        assertEquals("Song [Audio 129k] abcdefghijk.m4a", DownloadFormats.fileName("Song", "abcdefghijk", 0, "m4a", audioKbps = 129))
        assertEquals("WebM · Opus · 160 kbps", DownloadFormats.describe("webm", true, null, "opus", 160))
        // Two qualities of one video never share a file name.
        assertFalse(
            DownloadFormats.fileName("Song", "abcdefghijk", 0, "m4a", audioKbps = 129) ==
                DownloadFormats.fileName("Song", "abcdefghijk", 0, "m4a", audioKbps = 49),
        )
    }

    @Test
    fun audioBitrateAndSize() {
        assertEquals(129_480, DownloadFormats.audioBitsPerSecond(129_480, 128))
        assertEquals(128_000, DownloadFormats.audioBitsPerSecond(-1, 128))
        assertEquals(0, DownloadFormats.audioBitsPerSecond(-1, -1))
        assertEquals(3_840_000L, DownloadFormats.estimateBytes(128_000, 240))
        assertEquals(-1L, DownloadFormats.estimateBytes(0, 240))
        assertEquals(-1L, DownloadFormats.estimateBytes(128_000, 0))
    }

    private fun c(itag: Int, container: String, bps: Int, original: Boolean) =
        DownloadFormats.AudioCandidate(itag, container, bps, original)

    @Test
    fun audioChoices_originalTrackOnly_bestFirst_eachItagOnce() {
        val streams = listOf(
            c(140, "m4a", 129_000, true),
            c(251, "webm", 135_000, true),
            c(249, "webm", 50_000, true),
            c(140, "m4a", 129_000, true), // same itag listed twice
            c(251, "webm", 140_000, false), // dubbed track
            c(600, "ogg", 300_000, true), // container that would need conversion
        )
        val chosen = DownloadFormats.audioChoices(streams) { it }
        assertEquals(listOf(251, 140, 249), chosen.map { it.itag })
        assertTrue(chosen.all { it.original })
    }

    @Test
    fun audioChoices_noOriginalMarked_keepsAll_andTiesPreferM4a() {
        val chosen = DownloadFormats.audioChoices(listOf(c(251, "webm", 128_000, false), c(140, "m4a", 128_000, false))) { it }
        assertEquals(listOf(140, 251), chosen.map { it.itag })
        assertTrue(DownloadFormats.audioChoices(emptyList<DownloadFormats.AudioCandidate>()) { it }.isEmpty())
    }

    @Test
    fun videoExtension_matchesContainer() {
        assertEquals("Clip [1080p] abcdefghijk.mp4", DownloadFormats.fileName("Clip", "abcdefghijk", 1080, "mp4"))
        assertEquals("Clip [2160p HDR] abcdefghijk.webm", DownloadFormats.fileName("Clip", "abcdefghijk", 2160, "webm", hdr = true))
    }

    @Test
    fun fileName_stripsUnsafeCharacters_andFallsBackToId() {
        assertEquals("a b c d [720p] abcdefghijk.mp4", DownloadFormats.fileName("a/b:c*d?", "abcdefghijk", 720))
        assertEquals("abcdefghijk [Audio] abcdefghijk.m4a", DownloadFormats.fileName(" \n\t ", "abcdefghijk", 0))
        val long = DownloadFormats.fileName("x".repeat(200), "abcdefghijk", 360)
        assertEquals(64, long.substringBefore(" [").length)
    }

    @Test
    fun codecLabels() {
        assertEquals("H.264", DownloadFormats.videoCodecLabel("avc1.640028"))
        assertEquals("VP9", DownloadFormats.videoCodecLabel("vp09.00.51.08"))
        assertEquals("AV1", DownloadFormats.videoCodecLabel("av01.0.08M.08"))
        assertEquals(null, DownloadFormats.videoCodecLabel(null))
        assertEquals("AAC", DownloadFormats.audioCodecLabel("mp4a.40.2"))
        assertEquals("Opus", DownloadFormats.audioCodecLabel("opus"))
        assertEquals(null, DownloadFormats.audioCodecLabel("something-new"))
    }

    @Test
    fun describe_onlyStatesKnownFacts() {
        assertEquals("M4A · AAC · 129 kbps", DownloadFormats.describe("m4a", true, null, "mp4a.40.2", 129))
        assertEquals("M4A · AAC", DownloadFormats.describe("m4a", true, null, "mp4a.40.2", 0))
        assertEquals("M4A", DownloadFormats.describe("m4a", true, null, null, 0))
        assertEquals("MP4 · H.264 + AAC 128 kbps", DownloadFormats.describe("mp4", false, "avc1.4d401f", "mp4a.40.2", 128))
        assertEquals("WebM · VP9 + Opus", DownloadFormats.describe("webm", false, "vp9", "opus", 0))
        assertEquals("MP4", DownloadFormats.describe("mp4", false, null, null, 0))
    }

    @Test
    fun kbps_roundsAndHandlesUnknown() {
        assertEquals(129, DownloadFormats.kbps(129_480))
        assertEquals(0, DownloadFormats.kbps(0))
        assertEquals(0, DownloadFormats.kbps(-1))
    }

    @Test
    fun resume_onlyWhenSameStreamAndRangesSupported() {
        val id = DownloadFormats.streamIdentity(140, 5_000_000)
        assertTrue(DownloadFormats.canResume(1_000, id, id, rangesSupported = true, total = 5_000_000))
        // Server ignores Range: must restart.
        assertFalse(DownloadFormats.canResume(1_000, id, id, rangesSupported = false, total = 5_000_000))
        // A different stream (other itag or length) must never be appended to.
        assertFalse(DownloadFormats.canResume(1_000, DownloadFormats.streamIdentity(251, 5_000_000), id, true, 5_000_000))
        assertFalse(DownloadFormats.canResume(1_000, DownloadFormats.streamIdentity(140, 4_000_000), id, true, 5_000_000))
        // No record of what the partial file is.
        assertFalse(DownloadFormats.canResume(1_000, null, id, true, 5_000_000))
        // Nothing to resume / partial longer than the stream.
        assertFalse(DownloadFormats.canResume(0, id, id, true, 5_000_000))
        assertFalse(DownloadFormats.canResume(6_000_000, id, id, true, 5_000_000))
    }

    @Test
    fun chunks_coverTheWholeFileWithoutGapsOrOverlap() {
        val total = 38L * 1024 * 1024 + 123
        var pos = 0L
        var requests = 0
        while (true) {
            val end = DownloadFormats.chunkEnd(pos, total, DownloadFormats.CHUNK_BYTES) ?: break
            assertTrue(end >= pos && end - pos + 1 <= DownloadFormats.CHUNK_BYTES)
            pos = end + 1
            requests++
        }
        assertEquals(total, pos)
        assertEquals(4, requests)
        // Resuming mid-file, finished, or unknown size.
        assertEquals(1_000L + DownloadFormats.CHUNK_BYTES - 1, DownloadFormats.chunkEnd(1_000, total, DownloadFormats.CHUNK_BYTES))
        assertEquals(null, DownloadFormats.chunkEnd(total, total, DownloadFormats.CHUNK_BYTES))
        assertEquals(null, DownloadFormats.chunkEnd(0, -1, DownloadFormats.CHUNK_BYTES))
        assertEquals(99L, DownloadFormats.chunkEnd(0, 100, DownloadFormats.CHUNK_BYTES))
    }

    @Test
    fun renamedFolder_pathsMoveWithIt_othersUntouched() {
        val old = "/storage/emulated/0/Download/YTXTube Downloads"
        val new = "/storage/emulated/0/Download/Y.tube Downloads"
        assertEquals(new, DownloadFormats.remapLegacyPath(old, old, new))
        assertEquals("$new/a.mp4", DownloadFormats.remapLegacyPath("$old/a.mp4", old, new))
        // A different folder that merely starts with the same letters is not moved.
        assertEquals("${old}2/a.mp4", DownloadFormats.remapLegacyPath("${old}2/a.mp4", old, new))
        assertEquals("/sdcard/Movies", DownloadFormats.remapLegacyPath("/sdcard/Movies", old, new))
    }

    @Test
    fun transferComplete_requiresExactSize() {
        assertTrue(DownloadFormats.isTransferComplete(100, 100, 100))
        assertFalse(DownloadFormats.isTransferComplete(99, 100, 100))
        assertFalse(DownloadFormats.isTransferComplete(100, 99, 100))
        assertFalse(DownloadFormats.isTransferComplete(0, 0, 0))
    }
}
