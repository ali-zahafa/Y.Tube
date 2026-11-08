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
        assertEquals("m4a", DownloadFormats.extensionFor("webm", audioOnly = true))
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
    fun transferComplete_requiresExactSize() {
        assertTrue(DownloadFormats.isTransferComplete(100, 100, 100))
        assertFalse(DownloadFormats.isTransferComplete(99, 100, 100))
        assertFalse(DownloadFormats.isTransferComplete(100, 99, 100))
        assertFalse(DownloadFormats.isTransferComplete(0, 0, 0))
    }
}
